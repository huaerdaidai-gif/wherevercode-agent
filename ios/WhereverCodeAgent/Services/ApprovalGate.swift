// WhereverCode Agent — iOS v1.5 Build 6
//
// Combines PR #13's FeatureError + CapabilityGate with BrainEngine's
// ApprovalGate + DenyApprovalGate and Build 6's InteractiveApprovalStore.

import Foundation
import Combine

public enum FeatureError: Error, LocalizedError, Equatable {
    case notImplemented(ReservedFeature)

    public var message: String {
        switch self {
        case .notImplemented(let feature):
            return "\(feature.label)未实现。\(feature.note)"
        }
    }

    public var errorDescription: String? { message }
}

public enum CapabilityGate {
    public static func require(_ feature: ReservedFeature) throws -> Never {
        throw FeatureError.notImplemented(feature)
    }
}

public protocol ApprovalGate {
    func requestApproval(action: BrainAction) async -> Bool
    func isApprovalStillValid(action: BrainAction) -> Bool
}

extension ApprovalGate {
    public func isApprovalStillValid(action: BrainAction) -> Bool { true }
}

public struct DenyApprovalGate: ApprovalGate {
    public init() {}
    public func requestApproval(action: BrainAction) async -> Bool { false }
}

public struct PendingApprovalItem: Identifiable, Equatable, Sendable {
    public let id: String
    public let action: BrainAction
    public let requestedAt: Date
    public var decision: Bool?

    public init(id: String = UUID().uuidString, action: BrainAction, requestedAt: Date = Date(), decision: Bool? = nil) {
        self.id = id
        self.action = action
        self.requestedAt = requestedAt
        self.decision = decision
    }
}

public final class InteractiveApprovalStore: ObservableObject, ApprovalGate, @unchecked Sendable {
    private let lock = NSLock()
    private var _items: [PendingApprovalItem] = []
    private var _auditTrail: [String] = []

    public var items: [PendingApprovalItem] {
        lock.lock()
        defer { lock.unlock() }
        return _items
    }

    public var auditTrail: [String] {
        lock.lock()
        defer { lock.unlock() }
        return _auditTrail
    }

    public init() {}

    public func enqueue(_ action: BrainAction) {
        let item = PendingApprovalItem(id: action.id, action: action)
        let entry = "[PENDING] \(action.tool) (key=\(action.idempotencyKey))"
        lock.lock()
        _items.insert(item, at: 0)
        _auditTrail.insert(entry, at: 0)
        lock.unlock()
        notifyObservers()
    }

    public func decide(actionId: String, approve: Bool) {
        lock.lock()
        guard let idx = _items.firstIndex(where: { $0.id == actionId }) else {
            lock.unlock()
            return
        }
        _items[idx].decision = approve
        let verb = approve ? "APPROVED" : "REJECTED"
        let tool = _items[idx].action.tool
        _auditTrail.insert("[\(verb)] \(tool) (id=\(actionId))", at: 0)
        lock.unlock()
        notifyObservers()
    }

    private func notifyObservers() {
        if Thread.isMainThread {
            objectWillChange.send()
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.objectWillChange.send()
            }
        }
    }

    public func requestApproval(action: BrainAction) async -> Bool {
        guard action.requiresApproval else { return true }
        enqueue(action)
        return false
    }
}
