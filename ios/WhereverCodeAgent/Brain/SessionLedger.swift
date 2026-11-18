import Foundation

/// Session ledger seam for the brain engine, ported from the Android
/// baseline `core/session/SessionLedger.kt` + `SessionTypes.kt` on
/// main @4496658e.
///
/// BrainEngine depends only on this interface (constructor
/// injection); it never touches session storage directly. The engine
/// calls `record` only: a CONTROL result short-circuits the turn (it
/// goes to the router, never reaches an adapter, and costs zero model
/// calls), while CHAT continues through the normal flow.
///
/// Scope note: the full ledger (sessions, messages, delivery states)
/// is the A2 session-centre batch. `LedgerResult`'s stored-message
/// and route-result payloads are omitted here because the engine
/// never reads them; they land with A2.
public enum MessageKind: String, Equatable {
    case chat = "CHAT"
    case control = "CONTROL"
    case task = "TASK"
    case event = "EVENT"
    case delivery = "DELIVERY"

    public var name: String { rawValue }
}

/// One incoming message, before classification.
public struct IncomingMessage: Equatable {
    public var conversationId: String
    public var senderId: String
    public var content: String
    public var recipientId: String? = nil
    public var taskId: String? = nil
    public var attemptId: String? = nil
    /// Advisory only: classification by the ledger is authoritative.
    public var messageTypeHint: MessageKind? = nil
    public var causationId: String? = nil
    /// Caller-supplied id (must be a UUID); generated when nil.
    public var messageId: String? = nil
    public var idempotencyKey: String? = nil
    public var operationId: String? = nil
    public var ttlSeconds: Int64? = nil
    public var authVersion: Int64 = 1
    public var goalVersion: Int64 = 1

    public init(
        conversationId: String,
        senderId: String,
        content: String,
        recipientId: String? = nil,
        taskId: String? = nil,
        attemptId: String? = nil,
        messageTypeHint: MessageKind? = nil,
        causationId: String? = nil,
        messageId: String? = nil,
        idempotencyKey: String? = nil,
        operationId: String? = nil,
        ttlSeconds: Int64? = nil,
        authVersion: Int64 = 1,
        goalVersion: Int64 = 1
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

/// What `record` did.
public struct LedgerResult: Equatable {
    /// Classified kind (CONTROL even when routing failed).
    public var kind: MessageKind
    public var ok: Bool
    public var errorCode: String? = nil
    public var reason: String? = nil
    /// User-visible one-line summary (metadata only, no body).
    public var summary: String

    public init(
        kind: MessageKind,
        ok: Bool,
        errorCode: String? = nil,
        reason: String? = nil,
        summary: String
    ) {
        self.kind = kind
        self.ok = ok
        self.errorCode = errorCode
        self.reason = reason
        self.summary = summary
    }
}

public protocol SessionLedger {
    func record(_ input: IncomingMessage) -> LedgerResult
}

/// Default ledger for callers without a session centre: records
/// nothing, classifies everything as chat, and never short-circuits
/// the brain.
public struct NoOpSessionLedger: SessionLedger {
    public init() {}

    public func record(_ input: IncomingMessage) -> LedgerResult {
        LedgerResult(kind: .chat, ok: true, summary: "未接会话账本，按普通聊天处理")
    }
}
