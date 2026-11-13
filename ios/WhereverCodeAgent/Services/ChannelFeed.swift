// WhereverCode Agent — iOS MVP
//
// V5 channel message stream: WebSocket push first, polling fallback.
//
// Rules (mirroring the Android ChannelFeed, same numbers):
// - On start: pull one page of latest messages, then open the WS.
// - While the WS is live, new messages arrive as
//   `{"type":"message","message":{...}}`. `hello` / `ping` / unknown
//   types are ignored. A 20-second ping keeps the socket alive.
// - On disconnect: state becomes POLLING, poll every 15 seconds
//   (cursor = newest seen id, so a gap is picked back up), and try
//   the WS again after 45 seconds. On reconnect, back to LIVE.
// - Dedup by message id (WS push and polling can overlap); at most
//   1000 ids are remembered, oldest dropped first.
// - The token only ever goes in the `Authorization` header of the
//   WS upgrade request, never in a log or error string.

import Foundation

/// Parse one WS event frame. Returns a message only for
/// `{"type":"message","message":{...}}` with a non-empty id.
public func parseChannelWsEvent(_ text: String) -> ChannelMessage? {
    guard let data = text.data(using: .utf8),
          let json = try? JSONSerialization.jsonObject(with: data),
          let obj = json as? [String: Any],
          obj["type"] as? String == "message",
          let message = obj["message"] as? [String: Any] else {
        return nil
    }
    return ChannelMessage.from(dict: message)
}

/// Id-based dedup for the feed. Pure value type, unit-tested.
public struct MessageDeduper {
    public static let maxSeenIDs = 1000
    private var seen: [String] = []
    private var seenSet: Set<String> = []

    public init() {}

    /// Returns only the messages not seen before, in arrival order.
    public mutating func filterFresh(_ messages: [ChannelMessage]) -> [ChannelMessage] {
        var fresh: [ChannelMessage] = []
        for message in messages where !message.id.isEmpty {
            if seenSet.insert(message.id).inserted {
                seen.append(message.id)
                fresh.append(message)
            }
        }
        if seen.count > Self.maxSeenIDs {
            let drop = seen.count - Self.maxSeenIDs
            let dropped = seen.prefix(drop)
            seen.removeFirst(drop)
            for id in dropped { seenSet.remove(id) }
        }
        return fresh
    }

    public var seenCount: Int { seen.count }
}

/// Feed connection state machine. Pure transitions, unit-tested.
public struct FeedStateMachine {
    public enum Event {
        case started
        case wsOpened
        case wsClosed
        case reconnectDue
        case stopped
    }

    public private(set) var state: ChannelConnectionState = .stopped

    public init() {}

    public mutating func apply(_ event: Event) {
        switch (state, event) {
        case (_, .stopped):
            state = .stopped
        case (.stopped, .started):
            state = .connecting
        case (.connecting, .wsOpened), (.polling, .wsOpened):
            state = .live
        case (.live, .wsClosed), (.connecting, .wsClosed):
            state = .polling
        case (.polling, .reconnectDue):
            state = .connecting
        case (.live, .started), (.polling, .started), (.connecting, .started):
            break // already running; start is idempotent
        case (.stopped, .wsOpened), (.stopped, .wsClosed), (.stopped, .reconnectDue),
             (.live, .reconnectDue), (.live, .wsOpened),
             (.polling, .wsClosed), (.connecting, .reconnectDue):
            break // no transition for these combinations
        }
    }
}

/// Live channel feed: owns the WS task, the ping timer and the
/// polling-fallback loop. Callbacks are delivered on the main actor.
public final class ChannelFeed {
    public static let pollIntervalSeconds: TimeInterval = 15
    public static let reconnectAfterSeconds: TimeInterval = 45
    public static let pingIntervalSeconds: TimeInterval = 20

    private let channelID: String
    private let transport: ChannelTransport
    private let webSocketURL: URL
    private let tokenProvider: () -> String?
    private let session: URLSession

    private var stateMachine = FeedStateMachine()
    private var deduper = MessageDeduper()
    private var lastSeenID: String?
    private var webSocketTask: URLSessionWebSocketTask?
    private var runTask: Task<Void, Never>?
    private var running = false

    public var onMessages: (([ChannelMessage]) -> Void)?
    public var onStateChange: ((ChannelConnectionState, String?) -> Void)?

    public private(set) var lastError: String?

    public init(
        channelID: String,
        transport: ChannelTransport,
        webSocketURL: URL,
        tokenProvider: @escaping () -> String?,
        session: URLSession = .shared
    ) {
        self.channelID = channelID
        self.transport = transport
        self.webSocketURL = webSocketURL
        self.tokenProvider = tokenProvider
        self.session = session
    }

    public var connectionState: ChannelConnectionState { stateMachine.state }

    /// Idempotent start.
    public func start() {
        guard !running else { return }
        running = true
        setState(.started)
        runTask = Task { [weak self] in await self?.loop() }
    }

    public func stop() {
        running = false
        runTask?.cancel()
        runTask = nil
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        setState(.stopped)
    }

    // MARK: - Loop

    private func loop() async {
        await pollOnce()
        while running && !Task.isCancelled {
            if stateMachine.state == .polling {
                setState(.reconnectDue) // polling -> connecting
            } else {
                setState(.started) // stopped -> connecting; no-op otherwise
            }
            _ = await runWebSocket()
            if !running || Task.isCancelled { break }
            // Socket closed or failed: fall back to polling.
            setState(.wsClosed)
            await pollFallback()
        }
        setState(.stopped)
    }

    /// Poll until the reconnect deadline, then return so the WS is retried.
    private func pollFallback() async {
        let deadline = Date().addingTimeInterval(Self.reconnectAfterSeconds)
        while running && !Task.isCancelled && Date() < deadline {
            await pollOnce()
            try? await Task.sleep(nanoseconds: UInt64(Self.pollIntervalSeconds * 1_000_000_000))
        }
    }

    private func pollOnce() async {
        do {
            let messages = try await transport.pollMessages(channelID: channelID, afterID: lastSeenID)
            deliver(messages)
        } catch {
            lastError = (error as? McpError)?.message ?? "轮询失败。"
            publishState()
        }
    }

    /// Open the WS and run the receive loop until it closes or errors.
    /// Returns true when a socket was opened at all.
    private func runWebSocket() async -> Bool {
        guard let token = tokenProvider(), !token.isEmpty else {
            lastError = "未配置平台 Agent 凭证。"
            publishState()
            return false
        }
        var request = URLRequest(url: webSocketURL)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue(McpClient.userAgent, forHTTPHeaderField: "User-Agent")
        request.timeoutInterval = 30
        let task = session.webSocketTask(with: request)
        webSocketTask = task
        task.resume()
        // The first successful receive proves the socket is live; until
        // then the state stays CONNECTING.
        let pingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(Self.pingIntervalSeconds * 1_000_000_000))
                guard let self, self.running else { return }
                task.sendPing { _ in }
            }
        }
        defer {
            pingTask.cancel()
            webSocketTask = nil
        }
        while running && !Task.isCancelled {
            do {
                let message = try await task.receive()
                switch message {
                case .string(let text):
                    if stateMachine.state != .live { setState(.wsOpened) }
                    if let parsed = parseChannelWsEvent(text) {
                        deliver([parsed])
                    }
                case .data:
                    break // channel protocol carries text frames only
                @unknown default:
                    break
                }
            } catch {
                if running && !Task.isCancelled {
                    lastError = "频道连接断开。"
                }
                return true
            }
        }
        return true
    }

    private func deliver(_ messages: [ChannelMessage]) {
        let fresh = deduper.filterFresh(messages)
        guard !fresh.isEmpty else { return }
        lastSeenID = fresh.last?.id ?? lastSeenID
        let copy = fresh
        Task { @MainActor in self.onMessages?(copy) }
    }

    private func setState(_ event: FeedStateMachine.Event) {
        stateMachine.apply(event)
        publishState()
    }

    private func publishState() {
        let state = stateMachine.state
        let error = lastError
        Task { @MainActor in self.onStateChange?(state, error) }
    }
}
