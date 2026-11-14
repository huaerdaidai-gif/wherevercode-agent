import Foundation

public enum SessionLedgerKind: String, Codable, CaseIterable, Sendable {
    case chat = "CHAT"
    case control = "CONTROL"
    case task = "TASK"
    case event = "EVENT"
    case delivery = "DELIVERY"
}

public struct SessionLedgerEntry: Codable, Equatable, Identifiable, Sendable {
    public var id: String { messageId }
    public let conversationId: String
    public let senderId: String
    public let content: String
    public let recipientId: String?
    public let taskId: String?
    public let attemptId: String?
    public let messageTypeHint: SessionLedgerKind
    public let causationId: String?
    public let messageId: String
    public let idempotencyKey: String
    public let operationId: String?
    public let ttlSeconds: Int
    public let authVersion: Int
    public let goalVersion: Int

    public init(
        conversationId: String,
        senderId: String,
        content: String,
        recipientId: String? = nil,
        taskId: String? = nil,
        attemptId: String? = nil,
        messageTypeHint: SessionLedgerKind = .chat,
        causationId: String? = nil,
        messageId: String = UUID().uuidString,
        idempotencyKey: String = UUID().uuidString,
        operationId: String? = nil,
        ttlSeconds: Int = 3600,
        authVersion: Int = 1,
        goalVersion: Int = 1
    ) {
        self.conversationId = conversationId
        self.senderId = senderId
        self.content = content
        self.recipientId = recipientId
        self.taskId = taskId
        self.attemptId = attemptId
        self.messageTypeHint = messageTypeHint
        self.causationId = causationId
        self.messageId = messageId
        self.idempotencyKey = idempotencyKey
        self.operationId = operationId
        self.ttlSeconds = ttlSeconds
        self.authVersion = authVersion
        self.goalVersion = goalVersion
    }
}
