// WhereverCode Agent — iOS
//
// 上行补发队列：单调 seq、按幂等键去重、断线未确认事件按 seq 重放。
// 服务端确认帧是 task_event_ack（契约未单列，见交付说明）；
// 未确认的一直留在队列里，重连后整段按序再发，靠 (task_id, seq) 幂等。

import Foundation

public protocol UplinkQueuePersistence {
    func load() -> String?
    func save(_ text: String)
}

public final class InMemoryUplinkQueuePersistence: UplinkQueuePersistence {
    public var stored: String?
    public init() {}
    public func load() -> String? { stored }
    public func save(_ text: String) { stored = text }
}

public final class FileUplinkQueuePersistence: UplinkQueuePersistence {
    private let fileURL: URL

    public init(fileURL: URL) { self.fileURL = fileURL }

    public func load() -> String? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        return try? String(contentsOf: fileURL, encoding: .utf8)
    }

    public func save(_ text: String) {
        let manager = FileManager.default
        let directory = fileURL.deletingLastPathComponent()
        try? manager.createDirectory(at: directory, withIntermediateDirectories: true)
        try? text.write(to: fileURL, atomically: true, encoding: .utf8)
    }
}

public struct UplinkQueuedEvent {
    public let seq: Int64
    public let taskId: String
    public let kind: String
    public let actor: String
    public let at: Double
    public let payload: [String: Any]
    public let idempotencyKey: String
    public let acked: Bool

    public init(seq: Int64, taskId: String, kind: String, actor: String, at: Double,
                payload: [String: Any], idempotencyKey: String, acked: Bool = false) {
        self.seq = seq
        self.taskId = taskId
        self.kind = kind
        self.actor = actor
        self.at = at
        self.payload = payload
        self.idempotencyKey = idempotencyKey
        self.acked = acked
    }
}

/// 断线期间的上行事件队列。seq 从 1 起单调递增；同一幂等键不另铸 seq。
public final class UplinkReplayQueue {
    public private(set) var nextSeq: Int64 = 1

    private let persistence: UplinkQueuePersistence
    private let lock = NSLock()
    private var byKey: [String: UplinkQueuedEvent] = [:]
    private var pending: [Int64: UplinkQueuedEvent] = [:]
    private var ackedTokens: Set<String> = []

    public init(persistence: UplinkQueuePersistence = InMemoryUplinkQueuePersistence()) {
        self.persistence = persistence
        restore()
    }

    /// 铸造新 seq。同一 idempotencyKey 返回已有事件（含已确认的），不重复入队。
    @discardableResult
    public func enqueue(idempotencyKey: String, taskId: String, kind: String,
                        actor: String, at: Double, payload: [String: Any]) -> UplinkQueuedEvent {
        lock.lock(); defer { lock.unlock() }
        if let existing = byKey[idempotencyKey] { return existing }
        let seq = nextSeq
        nextSeq += 1
        let event = UplinkQueuedEvent(
            seq: seq, taskId: taskId, kind: kind, actor: actor, at: at,
            payload: payload, idempotencyKey: idempotencyKey, acked: false)
        byKey[idempotencyKey] = event
        pending[seq] = event
        persistLocked()
        return event
    }

    /// 收下调用方已有的 seq（账本序号）。重复 (task_id, seq) 或幂等键直接忽略。
    @discardableResult
    public func accept(seq: Int64, taskId: String, kind: String, actor: String,
                       at: Double, payload: [String: Any], idempotencyKey: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if seq <= 0 || taskId.isEmpty || idempotencyKey.isEmpty { return false }
        if byKey[idempotencyKey] != nil { return false }
        let token = Self.token(taskId: taskId, seq: seq)
        if ackedTokens.contains(token) || pending[seq] != nil {
            let remembered = UplinkQueuedEvent(
                seq: seq, taskId: taskId, kind: kind, actor: actor, at: at,
                payload: payload, idempotencyKey: idempotencyKey,
                acked: ackedTokens.contains(token))
            byKey[idempotencyKey] = remembered
            persistLocked()
            return false
        }
        if seq >= nextSeq { nextSeq = seq + 1 }
        let event = UplinkQueuedEvent(
            seq: seq, taskId: taskId, kind: kind, actor: actor, at: at,
            payload: payload, idempotencyKey: idempotencyKey, acked: false)
        byKey[idempotencyKey] = event
        pending[seq] = event
        persistLocked()
        return true
    }

    /// 未确认事件，按 seq 升序。这就是重连后的补发顺序。
    public func replay() -> [UplinkQueuedEvent] {
        lock.lock(); defer { lock.unlock() }
        return pending.values.sorted { $0.seq < $1.seq }
    }

    public var pendingCount: Int {
        lock.lock(); defer { lock.unlock() }
        return pending.count
    }

    /// 确认一帧。重复确认是空操作。task_id 对不上则不删。
    public func acknowledge(taskId: String, seq: Int64) {
        lock.lock(); defer { lock.unlock() }
        let token = Self.token(taskId: taskId, seq: seq)
        if let existing = pending[seq] {
            guard existing.taskId == taskId else { return }
            pending.removeValue(forKey: seq)
            if var stored = byKey[existing.idempotencyKey] {
                stored = UplinkQueuedEvent(
                    seq: stored.seq, taskId: stored.taskId, kind: stored.kind,
                    actor: stored.actor, at: stored.at, payload: stored.payload,
                    idempotencyKey: stored.idempotencyKey, acked: true)
                byKey[existing.idempotencyKey] = stored
            }
        }
        if ackedTokens.contains(token) && pending[seq] == nil {
            return
        }
        ackedTokens.insert(token)
        persistLocked()
    }

    private static func token(taskId: String, seq: Int64) -> String {
        "\(taskId)|\(seq)"
    }

    private func persistLocked() {
        var events: [[String: Any]] = []
        for event in byKey.values.sorted(by: { $0.seq < $1.seq }) {
            events.append([
                "seq": event.seq,
                "task_id": event.taskId,
                "kind": event.kind,
                "actor": event.actor,
                "at": event.at,
                "payload": event.payload,
                "idempotency_key": event.idempotencyKey,
                "acked": event.acked || ackedTokens.contains(Self.token(taskId: event.taskId, seq: event.seq)),
            ])
        }
        let root: [String: Any] = ["next_seq": nextSeq, "events": events]
        guard let data = try? JSONSerialization.data(withJSONObject: root),
              let text = String(data: data, encoding: .utf8) else { return }
        persistence.save(text)
    }

    private func restore() {
        guard let text = persistence.load(),
              let data = text.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data),
              let root = parsed as? [String: Any] else { return }
        let storedNext = jsonInt64(root["next_seq"]) ?? 1
        nextSeq = max(1, storedNext)
        byKey = [:]
        pending = [:]
        ackedTokens = []
        for item in (root["events"] as? [[String: Any]]) ?? [] {
            guard let seq = jsonInt64(item["seq"]), seq > 0,
                  let taskId = item["task_id"] as? String, !taskId.isEmpty,
                  let kind = item["kind"] as? String,
                  let key = item["idempotency_key"] as? String, !key.isEmpty else { continue }
            let actor = (item["actor"] as? String) ?? ""
            let at = jsonDouble(item["at"]) ?? 0
            let payload = item["payload"] as? [String: Any] ?? [:]
            let acked = (item["acked"] as? Bool) == true
                || ((item["acked"] as? NSNumber).map { CFGetTypeID($0) == CFBooleanGetTypeID() && $0.boolValue } ?? false)
            let event = UplinkQueuedEvent(
                seq: seq, taskId: taskId, kind: kind, actor: actor, at: at,
                payload: payload, idempotencyKey: key, acked: acked)
            byKey[key] = event
            if acked {
                ackedTokens.insert(Self.token(taskId: taskId, seq: seq))
            } else {
                pending[seq] = event
            }
            if seq >= nextSeq { nextSeq = seq + 1 }
        }
    }
}

/// 断线重连等待：第 0 次 1s，之后翻倍，封顶 60s。
public enum SeatUplinkBackoff {
    public static func delay(attempt: Int, base: TimeInterval = SeatUplinkSpec.reconnectBase,
                             cap: TimeInterval = SeatUplinkSpec.reconnectCap) -> TimeInterval {
        let steps = max(0, min(attempt, 16))
        let raw = base * pow(2.0, Double(steps))
        if raw.isNaN || raw.isInfinite { return cap }
        return min(cap, max(base, raw))
    }
}
