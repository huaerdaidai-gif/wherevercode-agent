import Foundation

public struct BrainAction: Codable, Equatable, Identifiable, Sendable {
    public var id: String { actionId }
    public let actionId: String
    public let tool: String
    public let args: [String: String]
    public let requiresApproval: Bool
    public let idempotencyKey: String

    public init(
        actionId: String = UUID().uuidString,
        tool: String,
        args: [String: String] = [:],
        requiresApproval: Bool = true,
        idempotencyKey: String
    ) {
        self.actionId = actionId
        self.tool = tool
        self.args = args
        self.requiresApproval = requiresApproval
        self.idempotencyKey = idempotencyKey
    }
}

public protocol ApprovalGate: Sendable {
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

public final class InteractiveApprovalStore: ObservableObject, @unchecked Sendable {
    public struct PendingItem: Identifiable, Equatable, Sendable {
        public var id: String { action.actionId }
        public let action: BrainAction
        public let createdAtEpochSeconds: Int64
        public var decision: Bool?
    }

    @Published public private(set) var items: [PendingItem] = []
    @Published public private(set) var auditTrail: [String] = []

    public init() {}

    public func enqueue(_ action: BrainAction) {
        let item = PendingItem(action: action, createdAtEpochSeconds: Int64(Date().timeIntervalSince1970), decision: nil)
        items.insert(item, at: 0)
        auditTrail.insert("[\(item.createdAtEpochSeconds)] 待审批动作：\(action.tool) (\(action.idempotencyKey))", at: 0)
    }

    public func decide(actionId: String, approve: Bool) {
        guard let idx = items.firstIndex(where: { $0.id == actionId }) else { return }
        items[idx].decision = approve
        let label = approve ? "已批准" : "已拒绝"
        auditTrail.insert("[\(Int64(Date().timeIntervalSince1970))] \(label)：\(items[idx].action.tool) (\(items[idx].action.idempotencyKey))", at: 0)
    }
}
