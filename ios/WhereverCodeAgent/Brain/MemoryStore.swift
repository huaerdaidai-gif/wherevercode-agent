import Foundation

/// MemoryStore: long-term memory in three layers, ported from the
/// Android baseline `brain/MemoryStore.kt` on main @4496658e.
///
/// Layers:
/// 1. FACT — user-confirmed facts (preferences, project info).
/// 2. TASK_SUMMARY — conclusions of completed tasks.
/// 3. DEVICE_AUTHORIZATION — device list and authorization state.
///    NEVER any key, token or password.
///
/// Write rule (hard): memory is written ONLY when the user explicitly
/// confirms, or when a task settles at completion. The brain must
/// NEVER upgrade chat content into a "fact" on its own. `addFact`
/// refuses unconfirmed writes; `addTaskSummary` requires the
/// task-settlement flag. Anything that looks like a secret is refused
/// outright.
public enum MemoryKind: String, Equatable {
    case fact = "FACT"
    case taskSummary = "TASK_SUMMARY"
    case deviceAuthorization = "DEVICE_AUTHORIZATION"

    /// The Kotlin enum name, used in summary lines.
    public var name: String { rawValue }
}

public struct MemoryEntry: Equatable {
    public var id: String
    public var kind: MemoryKind
    public var key: String
    public var value: String
    public var source: String
    public var createdAtEpochSeconds: Int64
    public var confirmedByUser: Bool

    public init(
        id: String,
        kind: MemoryKind,
        key: String,
        value: String,
        source: String,
        createdAtEpochSeconds: Int64,
        confirmedByUser: Bool
    ) {
        self.id = id
        self.kind = kind
        self.key = key
        self.value = value
        self.source = source
        self.createdAtEpochSeconds = createdAtEpochSeconds
        self.confirmedByUser = confirmedByUser
    }
}

public protocol MemoryStore {
    /// Returns false (no write) unless `confirmedByUser` is true.
    func addFact(entry: MemoryEntry, confirmedByUser: Bool) -> Bool
    /// Task settlement only: `settled` must be true, otherwise no
    /// write.
    func addTaskSummary(taskId: String, summary: String, settled: Bool) -> Bool
    func addDeviceAuthorization(deviceId: String, note: String) -> Bool
    func list(kind: MemoryKind) -> [MemoryEntry]
    func get(kind: MemoryKind, key: String) -> MemoryEntry?
    @discardableResult
    func remove(kind: MemoryKind, key: String) -> Bool
    /// One text block for ContextManager, annotated with source +
    /// time.
    func summarize() -> String
}

public final class InMemoryMemoryStore: MemoryStore {
    private let lock = NSLock()
    private var entries: [String: MemoryEntry] = [:]
    private var order: [String] = []

    public init() {}

    private func compositeKey(_ kind: MemoryKind, _ key: String) -> String {
        "\(kind.name)|\(key)"
    }

    private func put(_ entry: MemoryEntry) {
        let key = compositeKey(entry.kind, entry.key)
        if entries[key] == nil { order.append(key) }
        entries[key] = entry
    }

    public func addFact(entry: MemoryEntry, confirmedByUser: Bool) -> Bool {
        if !confirmedByUser { return false }
        if SecretRedactor.containsSecret(entry.value) || SecretRedactor.containsSecret(entry.key) {
            return false
        }
        lock.lock(); defer { lock.unlock() }
        var stored = entry
        stored.confirmedByUser = true
        put(stored)
        return true
    }

    public func addTaskSummary(taskId: String, summary: String, settled: Bool) -> Bool {
        if !settled { return false }
        if SecretRedactor.containsSecret(summary) { return false }
        let entry = MemoryEntry(
            id: UUID().uuidString,
            kind: .taskSummary,
            key: taskId,
            value: summary,
            source: "task:\(taskId)",
            createdAtEpochSeconds: Int64(Date().timeIntervalSince1970),
            confirmedByUser: false)
        lock.lock(); defer { lock.unlock() }
        put(entry)
        return true
    }

    public func addDeviceAuthorization(deviceId: String, note: String) -> Bool {
        if SecretRedactor.containsSecret(note) { return false }
        let entry = MemoryEntry(
            id: UUID().uuidString,
            kind: .deviceAuthorization,
            key: deviceId,
            value: note,
            source: "device:\(deviceId)",
            createdAtEpochSeconds: Int64(Date().timeIntervalSince1970),
            confirmedByUser: true)
        lock.lock(); defer { lock.unlock() }
        put(entry)
        return true
    }

    public func list(kind: MemoryKind) -> [MemoryEntry] {
        lock.lock(); defer { lock.unlock() }
        return order.compactMap { entries[$0] }.filter { $0.kind == kind }
    }

    public func get(kind: MemoryKind, key: String) -> MemoryEntry? {
        lock.lock(); defer { lock.unlock() }
        return entries[compositeKey(kind, key)]
    }

    @discardableResult
    public func remove(kind: MemoryKind, key: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let composite = compositeKey(kind, key)
        guard entries.removeValue(forKey: composite) != nil else { return false }
        order.removeAll { $0 == composite }
        return true
    }

    public func summarize() -> String {
        lock.lock(); defer { lock.unlock() }
        let lines = order.compactMap { entries[$0] }
        if lines.isEmpty { return "" }
        return lines.map { entry in
            "- [\(entry.kind.name)] \(entry.key)：\(entry.value)（来源：\(entry.source)）"
        }.joined(separator: "\n")
    }
}
