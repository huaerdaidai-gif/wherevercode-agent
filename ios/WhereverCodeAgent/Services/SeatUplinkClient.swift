// WhereverCode Agent — iOS
//
// 席位上行客户端：连上先 seat_register，30 秒 WebSocket ping（信令不带 token），
// 断线指数退避重连（1s→60s）。task_dispatch 交给本地账本落单后回 task_ack。
// 本地状态迁移进补发队列：在线按序发送，离线缓存，重连后未确认的按 seq 再发。

import Foundation

public protocol LocalTaskUplink: AnyObject {
    func recordLedgerEvents(_ events: [LedgerUplinkEvent])
}

public protocol SeatUplinkSocket: AnyObject {
    var onOpen: (() -> Void)? { get set }
    var onText: ((String) -> Void)? { get set }
    var onDisconnect: (() -> Void)? { get set }
    func connect(url: URL, bearerToken: String)
    func send(_ text: String)
    func ping()
    func disconnect()
}

public protocol SeatUplinkScheduling: AnyObject {
    func schedule(after seconds: TimeInterval, _ work: @escaping () -> Void) -> Any
    func cancel(_ token: Any)
}

public final class DispatchSeatScheduler: SeatUplinkScheduling {
    private let queue: DispatchQueue

    public init(queue: DispatchQueue = .main) { self.queue = queue }

    public func schedule(after seconds: TimeInterval, _ work: @escaping () -> Void) -> Any {
        let item = DispatchWorkItem(block: work)
        queue.asyncAfter(deadline: .now() + seconds, execute: item)
        return item
    }

    public func cancel(_ token: Any) {
        (token as? DispatchWorkItem)?.cancel()
    }
}

public struct SeatUplinkConfig {
    public var url: URL
    public var bearerToken: String
    public var appInstanceId: String
    public var agentId: String
    public var platform: String
    public var appVersion: String
    public var heartbeatInterval: TimeInterval
    public var reconnectBase: TimeInterval
    public var reconnectCap: TimeInterval
    public var channelIds: () -> [String]

    public init(
        url: URL = SeatUplinkSpec.productionURL,
        bearerToken: String,
        appInstanceId: String,
        agentId: String = LocalPlatform.appAgentID,
        platform: String = SeatUplinkSpec.platformIOS,
        appVersion: String,
        heartbeatInterval: TimeInterval = SeatUplinkSpec.heartbeatInterval,
        reconnectBase: TimeInterval = SeatUplinkSpec.reconnectBase,
        reconnectCap: TimeInterval = SeatUplinkSpec.reconnectCap,
        channelIds: @escaping () -> [String] = { [] }
    ) {
        self.url = url
        self.bearerToken = bearerToken
        self.appInstanceId = appInstanceId
        self.agentId = agentId
        self.platform = platform
        self.appVersion = appVersion
        self.heartbeatInterval = heartbeatInterval
        self.reconnectBase = reconnectBase
        self.reconnectCap = reconnectCap
        self.channelIds = channelIds
    }
}

public enum SeatUplinkIdentity {
    public static func appInstanceId(defaults: UserDefaults = .standard) -> String {
        if let existing = defaults.string(forKey: SeatUplinkSpec.appInstanceDefaultsKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines), !existing.isEmpty {
            return existing
        }
        let created = UUID().uuidString.lowercased()
        defaults.set(created, forKey: SeatUplinkSpec.appInstanceDefaultsKey)
        return created
    }
}

public final class SeatUplinkClient: LocalTaskUplink {
    public var onTaskDispatch: (([String: Any]) -> String?)?

    private let config: SeatUplinkConfig
    private let socket: SeatUplinkSocket
    private let queue: UplinkReplayQueue
    private let scheduler: SeatUplinkScheduling
    private let lock = NSLock()
    private var running = false
    private var connecting = false
    private var connected = false
    private var reconnectAttempt = 0
    private var reconnectToken: Any?
    private var heartbeatToken: Any?
    private var flushedThisConnection: Set<Int64> = []

    public init(config: SeatUplinkConfig, socket: SeatUplinkSocket,
                queue: UplinkReplayQueue = UplinkReplayQueue(),
                scheduler: SeatUplinkScheduling = DispatchSeatScheduler()) {
        self.config = config
        self.socket = socket
        self.queue = queue
        self.scheduler = scheduler
        socket.onOpen = { [weak self] in self?.handleOpen() }
        socket.onText = { [weak self] text in self?.handleText(text) }
        socket.onDisconnect = { [weak self] in self?.handleDisconnect() }
    }

    public var pendingCount: Int { queue.pendingCount }

    public func start() {
        lock.lock()
        if running {
            lock.unlock()
            return
        }
        running = true
        lock.unlock()
        connectNow()
    }

    public func stop() {
        lock.lock()
        running = false
        connecting = false
        connected = false
        let reconnect = reconnectToken
        let heart = heartbeatToken
        reconnectToken = nil
        heartbeatToken = nil
        flushedThisConnection.removeAll()
        lock.unlock()
        if let reconnect { scheduler.cancel(reconnect) }
        if let heart { scheduler.cancel(heart) }
        socket.disconnect()
    }

    public func recordLedgerEvents(_ events: [LedgerUplinkEvent]) {
        lock.lock()
        for event in events where SeatUplinkSpec.eventKinds.contains(event.kind) {
            _ = queue.accept(
                seq: event.seq,
                taskId: event.taskId,
                kind: event.kind,
                actor: event.actor,
                at: event.at,
                payload: ["text": event.text],
                idempotencyKey: "\(event.taskId)|\(event.seq)")
        }
        let texts = takeFlushLocked()
        lock.unlock()
        send(texts)
    }

    private func connectNow() {
        lock.lock()
        guard running else { lock.unlock(); return }
        connecting = true
        connected = false
        lock.unlock()
        socket.connect(url: config.url, bearerToken: config.bearerToken)
    }

    private func handleOpen() {
        lock.lock()
        guard running else { lock.unlock(); return }
        connecting = false
        connected = true
        reconnectAttempt = 0
        flushedThisConnection.removeAll()
        let register = (try? SeatFrameCodec.encode(.seatRegister(
            appInstanceId: config.appInstanceId,
            agentId: config.agentId,
            channels: config.channelIds(),
            platform: config.platform,
            appVersion: config.appVersion))) ?? ""
        var texts: [String] = []
        if !register.isEmpty { texts.append(register) }
        texts.append(contentsOf: takeFlushLocked())
        lock.unlock()
        send(texts)
        startHeartbeat()
    }

    private func handleText(_ text: String) {
        guard let frame = try? SeatFrameCodec.decode(text) else { return }
        switch frame {
        case .taskDispatch(let task):
            guard let taskId = onTaskDispatch?(task)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !taskId.isEmpty else { return }
            if let ack = try? SeatFrameCodec.encode(.taskAck(taskId: taskId)) {
                socket.send(ack)
            }
        case .taskEventAck(let taskId, let seq):
            lock.lock()
            queue.acknowledge(taskId: taskId, seq: seq)
            flushedThisConnection.remove(seq)
            lock.unlock()
        default:
            break
        }
    }

    private func handleDisconnect() {
        lock.lock()
        let shouldReconnect = running && (connected || connecting)
        if !shouldReconnect {
            lock.unlock()
            return
        }
        connected = false
        connecting = false
        flushedThisConnection.removeAll()
        let delay = SeatUplinkBackoff.delay(
            attempt: reconnectAttempt, base: config.reconnectBase, cap: config.reconnectCap)
        reconnectAttempt += 1
        let previousHeart = heartbeatToken
        heartbeatToken = nil
        let previousReconnect = reconnectToken
        lock.unlock()
        if let previousHeart { scheduler.cancel(previousHeart) }
        if let previousReconnect { scheduler.cancel(previousReconnect) }
        let token = scheduler.schedule(after: delay) { [weak self] in
            self?.connectNow()
        }
        lock.lock()
        if running {
            reconnectToken = token
        } else {
            lock.unlock()
            scheduler.cancel(token)
            return
        }
        lock.unlock()
    }

    private func startHeartbeat() {
        lock.lock()
        let previous = heartbeatToken
        heartbeatToken = nil
        let interval = config.heartbeatInterval
        let should = running && connected
        lock.unlock()
        if let previous { scheduler.cancel(previous) }
        guard should else { return }
        let token = scheduler.schedule(after: interval) { [weak self] in
            self?.fireHeartbeat()
        }
        lock.lock()
        if running && connected {
            heartbeatToken = token
        } else {
            lock.unlock()
            scheduler.cancel(token)
            return
        }
        lock.unlock()
    }

    private func fireHeartbeat() {
        lock.lock()
        let should = running && connected
        lock.unlock()
        guard should else { return }
        socket.ping()
        startHeartbeat()
    }

    /// 调用方持有 lock。只收集当前连接还没写出去的未确认事件。
    private func takeFlushLocked() -> [String] {
        guard connected else { return [] }
        var texts: [String] = []
        for event in queue.replay() {
            if flushedThisConnection.contains(event.seq) { continue }
            guard let text = try? SeatFrameCodec.encode(.taskEvent(
                taskId: event.taskId,
                seq: event.seq,
                kind: event.kind,
                actor: event.actor,
                at: event.at,
                payload: event.payload)) else { continue }
            flushedThisConnection.insert(event.seq)
            texts.append(text)
        }
        return texts
    }

    private func send(_ texts: [String]) {
        for text in texts where !text.isEmpty {
            socket.send(text)
        }
    }
}

/// 生产套接字。握手带 Bearer；心跳用 WebSocket ping，帧体不放 token。
public final class URLSessionSeatSocket: NSObject, SeatUplinkSocket, URLSessionWebSocketDelegate {
    public var onOpen: (() -> Void)?
    public var onText: ((String) -> Void)?
    public var onDisconnect: (() -> Void)?

    private let lock = NSLock()
    private var task: URLSessionWebSocketTask?
    private var open = false
    private lazy var session: URLSession = {
        URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }()

    public func connect(url: URL, bearerToken: String) {
        lock.lock()
        task?.cancel(with: .goingAway, reason: nil)
        var request = URLRequest(url: url)
        let token = bearerToken.trimmingCharacters(in: .whitespacesAndNewlines)
        if !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let created = session.webSocketTask(with: request)
        task = created
        open = false
        lock.unlock()
        created.resume()
        receiveLoop(created)
    }

    public func send(_ text: String) {
        lock.lock()
        let current = task
        lock.unlock()
        current?.send(.string(text)) { [weak self] error in
            if error != nil { self?.fail() }
        }
    }

    public func ping() {
        lock.lock()
        let current = task
        lock.unlock()
        current?.sendPing { [weak self] error in
            if error != nil { self?.fail() }
        }
    }

    public func disconnect() {
        lock.lock()
        let current = task
        task = nil
        open = false
        lock.unlock()
        current?.cancel(with: .normalClosure, reason: nil)
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                           didOpenWithProtocol protocol: String?) {
        lock.lock()
        guard task === webSocketTask else { lock.unlock(); return }
        open = true
        lock.unlock()
        onOpen?()
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                           didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        fail(webSocketTask)
    }

    private func receiveLoop(_ socketTask: URLSessionWebSocketTask) {
        socketTask.receive { [weak self] result in
            guard let self else { return }
            self.lock.lock()
            let current = self.task === socketTask
            self.lock.unlock()
            guard current else { return }
            switch result {
            case .success(let message):
                if case .string(let text) = message { self.onText?(text) }
                self.receiveLoop(socketTask)
            case .failure:
                self.fail(socketTask)
            }
        }
    }

    private func fail(_ socketTask: URLSessionWebSocketTask? = nil) {
        lock.lock()
        if let socketTask, task !== socketTask {
            lock.unlock()
            return
        }
        let shouldNotify = task != nil || open
        task = nil
        open = false
        lock.unlock()
        if shouldNotify { onDisconnect?() }
    }
}
