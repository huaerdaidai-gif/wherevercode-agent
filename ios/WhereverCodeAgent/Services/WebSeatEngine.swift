// WhereverCode Agent — iOS
//
// 网页席位引擎：App 在前台时，按席位轮询它所在的频道（15 秒兜底
// 节奏，与频道页的轮询兜底一致），命中触发闸的消息交给席位的
// WebView 会话提问，回复贴回原话题。App 退到后台引擎停转，席位
// 状态如实变「休息中」——不冒充常驻（方案便签 №21 定版口径）。

import Foundation

public enum WebSeatStatus: Equatable, Sendable {
    case off        // 未启用
    case needsToken // 平台令牌未签发
    case needsLogin // 网页未登录
    case online     // 在线
    case resting    // 休息中（App 不在前台）

    public var label: String {
        switch self {
        case .off: return "已停用"
        case .needsToken: return "未签发令牌"
        case .needsLogin: return "未登录"
        case .online: return "在线"
        case .resting: return "休息中"
        }
    }
}

@MainActor
public final class WebSeatEngine: ObservableObject {
    @Published public private(set) var statuses: [String: WebSeatStatus] = [:]

    private let store: WebSeatStore
    private let endpoint: URL
    private let ownerAgentId: String
    private let appClient: McpClient?

    private var sessions: [String: WebSeatSession] = [:]
    private var loops: [String: Task<Void, Never>] = [:]
    private var runtimes: [String: SeatRuntime] = [:]
    public private(set) var running = false

    private final class SeatRuntime {
        var cursors: [String: String] = [:]
        var threads: [String: String] = [:]
        var msgRoot: [String: String] = [:]
        var attempts: [String: Int] = [:]
        var dmChannels: Set<String> = []
    }

    public init(store: WebSeatStore, endpoint: URL, ownerAgentId: String, appClient: McpClient?) {
        self.store = store
        self.endpoint = endpoint
        self.ownerAgentId = ownerAgentId
        self.appClient = appClient
    }

    // MARK: - Sessions (the UI attaches these web views)

    public func session(for seat: WebSeatRecord) -> WebSeatSession {
        if let existing = sessions[seat.seatId] { return existing }
        let created = WebSeatSession(seat: seat)
        sessions[seat.seatId] = created
        return created
    }

    // MARK: - Lifecycle

    public func start() {
        running = true
        let seats = (try? store.listSeats()) ?? []
        for seat in seats {
            refreshStatus(seat)
            if seat.enabled && store.hasToken(seatId: seat.seatId) {
                startLoop(seat)
            }
        }
    }

    public func stop() {
        running = false
        for (_, task) in loops { task.cancel() }
        loops.removeAll()
        let seats = (try? store.listSeats()) ?? []
        for seat in seats where seat.enabled {
            statuses[seat.seatId] = store.hasToken(seatId: seat.seatId) ? .resting : .needsToken
        }
    }

    private func refreshStatus(_ seat: WebSeatRecord) {
        if !seat.enabled {
            statuses[seat.seatId] = .off
        } else if !store.hasToken(seatId: seat.seatId) {
            statuses[seat.seatId] = .needsToken
        } else if !running {
            statuses[seat.seatId] = .resting
        }
    }

    /// Re-probe the login state and update the published status.
    public func refreshLogin(_ seat: WebSeatRecord) async {
        guard seat.enabled, store.hasToken(seatId: seat.seatId) else {
            refreshStatus(seat)
            return
        }
        let ok = await session(for: seat).probeLogin()
        statuses[seat.seatId] = ok ? .online : .needsLogin
    }

    // MARK: - Seat management

    /// Create a seat for a site and issue its platform token with
    /// the owner's credential. Returns the record and an optional
    /// user-readable provisioning error.
    public func addSeat(site: WebSeatSite, channelIds: [String]) async -> (WebSeatRecord, String?) {
        let suffix = String(UUID().uuidString.prefix(4)).lowercased()
        var record = WebSeatRecord(
            seatId: "\(site.driverId)-\(suffix)", site: site,
            dataStoreId: UUID().uuidString)
        record.channelIds = channelIds
        try? store.saveSeat(record)
        let error = await provision(record)
        refreshStatus(record)
        if running && error == nil { startLoop(record) }
        return (record, error)
    }

    /// Issue (or re-issue) the seat's platform token. The token
    /// value passes through memory only, straight into the Keychain.
    public func provision(_ seat: WebSeatRecord) async -> String? {
        guard let appClient else {
            return "未连接平台：先在设置里连好平台，再添加席位。"
        }
        let issuer = PlatformTokenIssuer(callTool: { name, args in
            try await appClient.callTool(name: name, arguments: args)
        })
        switch await issuer.issueToken(agentId: seat.platformAgentId, name: "App 网页席位") {
        case .issued(let issued):
            do {
                try store.saveToken(issued.token, seatId: seat.seatId)
                return nil
            } catch {
                return "令牌已签发但存入钥匙串失败。"
            }
        case .failed(let message):
            return message
        }
    }

    public func deleteSeat(_ seat: WebSeatRecord) {
        loops[seat.seatId]?.cancel()
        loops.removeValue(forKey: seat.seatId)
        runtimes.removeValue(forKey: seat.seatId)
        sessions.removeValue(forKey: seat.seatId)
        statuses.removeValue(forKey: seat.seatId)
        try? store.deleteSeat(seatId: seat.seatId)
    }

    public func setEnabled(_ seat: WebSeatRecord, enabled: Bool) {
        var updated = seat
        updated.enabled = enabled
        try? store.saveSeat(updated)
        if enabled {
            refreshStatus(updated)
            if running && store.hasToken(seatId: seat.seatId) { startLoop(updated) }
        } else {
            loops[seat.seatId]?.cancel()
            loops.removeValue(forKey: seat.seatId)
            statuses[seat.seatId] = .off
        }
    }

    public func setChannels(_ seat: WebSeatRecord, channelIds: [String]) {
        var updated = seat
        updated.channelIds = channelIds
        try? store.saveSeat(updated)
        if running && updated.enabled && store.hasToken(seatId: seat.seatId) {
            loops[seat.seatId]?.cancel()
            startLoop(updated)
        }
    }

    // MARK: - Poll loop

    private func startLoop(_ seat: WebSeatRecord) {
        guard loops[seat.seatId] == nil else { return }
        let runtime = runtimes[seat.seatId] ?? SeatRuntime()
        runtimes[seat.seatId] = runtime
        loops[seat.seatId] = Task { [weak self] in
            await self?.runSeat(seat, runtime: runtime)
        }
    }

    private func transport(for seat: WebSeatRecord) -> ChannelTransport {
        let client = McpClient(endpoint: endpoint, tokenProvider: { [store] in
            store.loadToken(seatId: seat.seatId)
        })
        return ChannelTransport(client: client)
    }

    private func runSeat(_ seat: WebSeatRecord, runtime: SeatRuntime) async {
        guard let site = seat.site else { return }
        let transport = self.transport(for: seat)
        // Join watched channels and DM channels; start cursors at
        // the latest message (never replay history into the seat).
        var watched = seat.channelIds
        if let channels = try? await transport.listChannels() {
            runtime.dmChannels = Set(channels.filter {
                $0.kind == "dm" && $0.participants.contains(ownerAgentId)
            }.map { $0.id })
            watched += runtime.dmChannels.filter { !watched.contains($0) }
        }
        for channelId in watched {
            try? await transport.joinChannel(channelID: channelId)
            if runtime.cursors[channelId] == nil,
               let page = try? await transport.pollMessages(channelID: channelId, afterID: nil) {
                runtime.cursors[channelId] = page.last?.id
            }
        }
        if statuses[seat.seatId] != .needsLogin {
            statuses[seat.seatId] = .online
        }
        while !Task.isCancelled && running {
            for channelId in watched {
                let fresh = (try? await transport.pollMessages(
                    channelID: channelId, afterID: runtime.cursors[channelId])) ?? []
                for message in fresh {
                    await handle(seat, site: site, runtime: runtime,
                                 transport: transport, channelId: channelId, message: message)
                    runtime.cursors[channelId] = message.id
                }
            }
            try? await Task.sleep(nanoseconds: 15_000_000_000)
        }
    }

    private func handle(
        _ seat: WebSeatRecord, site: WebSeatSite, runtime: SeatRuntime,
        transport: ChannelTransport, channelId: String, message: ChannelMessage
    ) async {
        let root = message.replyTo.flatMap { runtime.msgRoot[$0] }
        let isDM = runtime.dmChannels.contains(channelId)
        let hit = WebSeatGate.shouldRespond(
            senderId: message.agentId,
            ownerAgentId: ownerAgentId,
            seatAgentId: seat.platformAgentId,
            text: message.text,
            threadRoot: root,
            isDM: isDM,
            handles: site.mentionHandles,
            otherHandles: site.otherHandles())
        if let root, message.agentId == ownerAgentId {
            runtime.msgRoot[message.id] = root
        }
        guard hit else { return }
        let effectiveRoot = root ?? (message.replyTo ?? message.id)
        let stripped = WebSeatGate.stripMentions(message.text, handles: site.mentionHandles)
        let question = stripped.isEmpty ? message.text : stripped
        runtime.attempts[message.id, default: 0] += 1
        do {
            let (reply, href) = try await session(for: seat).ask(
                question: question, target: runtime.threads[effectiveRoot] ?? "new")
            let chunks = Self.chunkText(reply)
            for (index, chunk) in chunks.enumerated() {
                let postedId = try await transport.postMessageEx(
                    channelID: channelId, text: chunk, replyTo: message.id,
                    effectKey: "webseat-\(seat.seatId)-\(message.id)-\(index)")
                if !postedId.isEmpty { runtime.msgRoot[postedId] = effectiveRoot }
            }
            if !href.isEmpty { runtime.threads[effectiveRoot] = href }
            runtime.msgRoot[message.id] = effectiveRoot
            runtime.attempts.removeValue(forKey: message.id)
            statuses[seat.seatId] = .online
        } catch {
            if (runtime.attempts[message.id] ?? 0) >= 3 {
                _ = try? await transport.postMessageEx(
                    channelID: channelId,
                    text: "网页版 \(site.shortName) 未能完成回复：暂时不可用，请稍后再试。",
                    replyTo: message.id,
                    effectKey: "webseat-fail-\(seat.seatId)-\(message.id)")
                runtime.attempts.removeValue(forKey: message.id)
            }
        }
    }

    /// Split an over-long reply into channel-sized chunks
    /// (server limit 4000; keep headroom).
    public static func chunkText(_ text: String, limit: Int = 3900) -> [String] {
        guard text.count > limit else { return [text] }
        var chunks: [String] = []
        var rest = Substring(text)
        while rest.count > limit {
            let cut = rest.index(rest.startIndex, offsetBy: limit)
            var split = cut
            if let newline = rest[..<cut].lastIndex(of: "\n") {
                split = newline
            }
            chunks.append(String(rest[..<split]))
            rest = rest[rest.index(after: split)...]
        }
        if !rest.isEmpty { chunks.append(String(rest)) }
        return chunks
    }
}
