import Foundation

public struct FeedStateMachine: Equatable, Sendable {
    public private(set) var state: ChannelConnectionState = .stopped

    public init() {}

    public mutating func started() { state = .connecting }
    public mutating func wsOpened() { state = .live }
    public mutating func wsClosed() { state = .polling }
    public mutating func reconnectDue() { state = .connecting }
    public mutating func stopped() { state = .stopped }
}

public struct MessageDeduper: Sendable {
    private var seenSet: Set<String> = []

    public init() {}

    public mutating func filterNew(_ messages: [ChannelMessage]) -> [ChannelMessage] {
        var out: [ChannelMessage] = []
        for m in messages {
            if seenSet.insert(m.id).inserted {
                out.append(m)
            }
        }
        return out
    }
}

public final class ChannelFeed {
    public let channelID: String
    public let transport: ChannelTransport
    public let webSocketURL: URL
    public let tokenProvider: @Sendable () -> String?
    public var onMessages: (([ChannelMessage]) -> Void)?
    public var onStateChange: ((ChannelConnectionState) -> Void)?

    private var stateMachine = FeedStateMachine()
    private var deduper = MessageDeduper()
    private var lastSeenID: String?
    private var running = false

    public init(
        channelID: String,
        transport: ChannelTransport,
        webSocketURL: URL,
        tokenProvider: @escaping @Sendable () -> String?
    ) {
        self.channelID = channelID
        self.transport = transport
        self.webSocketURL = webSocketURL
        self.tokenProvider = tokenProvider
    }

    public func start() {
        guard !running else { return }
        running = true
        stateMachine.started()
        onStateChange?(stateMachine.state)
    }

    public func stop() {
        running = false
        stateMachine.stopped()
        onStateChange?(stateMachine.state)
    }

    public func pollOnce() async throws {
        let msgs = try await transport.pollMessages(channelID: channelID, afterID: lastSeenID)
        var d = deduper
        let fresh = d.filterNew(msgs)
        deduper = d
        if let last = fresh.last {
            lastSeenID = last.id
        }
        if !fresh.isEmpty {
            onMessages?(fresh)
        }
    }
}
