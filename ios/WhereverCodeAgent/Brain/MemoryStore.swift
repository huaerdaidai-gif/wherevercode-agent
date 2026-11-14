import Foundation

public enum MemoryKind: String, Codable, CaseIterable, Sendable {
    case fact = "FACT"
    case taskSummary = "TASK_SUMMARY"
    case deviceAuthorization = "DEVICE_AUTHORIZATION"
}

public struct MemoryEntry: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let kind: MemoryKind
    public var key: String
    public var value: String
    public var source: String
    public let createdAtEpochSeconds: Int64
    public var confirmedByUser: Bool

    public init(
        id: String = UUID().uuidString,
        kind: MemoryKind = .fact,
        key: String,
        value: String,
        source: String = "user",
        createdAtEpochSeconds: Int64 = Int64(Date().timeIntervalSince1970),
        confirmedByUser: Bool = true
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

public protocol MemoryStore: AnyObject {
    func listEntries() -> [MemoryEntry]
    func addFact(key: String, value: String, confirmedByUser: Bool)
    func addTaskSummary(taskId: String, summary: String, settled: Bool) -> Bool
    func deleteEntry(id: String)
}

public final class InMemoryMemoryStore: MemoryStore {
    private var entries: [String: MemoryEntry] = [:]
    private var order: [String] = []
    private let lock = NSLock()

    public init() {}

    public func listEntries() -> [MemoryEntry] {
        lock.lock()
        defer { lock.unlock() }
        return order.compactMap { entries[$0] }
    }

    public func addFact(key: String, value: String, confirmedByUser: Bool = true) {
        lock.lock()
        defer { lock.unlock() }
        let entry = MemoryEntry(kind: .fact, key: key, value: SecretRedactor.redact(value), confirmedByUser: confirmedByUser)
        entries[entry.id] = entry
        order.insert(entry.id, at: 0)
    }

    public func addTaskSummary(taskId: String, summary: String, settled: Bool) -> Bool {
        guard settled else { return false }
        lock.lock()
        defer { lock.unlock() }
        let entry = MemoryEntry(kind: .taskSummary, key: "task:\(taskId)", value: SecretRedactor.redact(summary), source: "task_ledger", confirmedByUser: true)
        entries[entry.id] = entry
        order.insert(entry.id, at: 0)
        return true
    }

    public func deleteEntry(id: String) {
        lock.lock()
        defer { lock.unlock() }
        entries.removeValue(forKey: id)
        order.removeAll { $0 == id }
    }
}
