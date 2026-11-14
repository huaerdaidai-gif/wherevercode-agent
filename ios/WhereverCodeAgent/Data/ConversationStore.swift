import Foundation

/// Multi-turn conversation history, ported from the Android
/// baseline (`brain/ConversationStore.kt` + `ConversationDatabase.kt`
/// on main @4496658e). History belongs to the conversation, not to
/// the brain: switching brains only changes `brainId`.
///
/// Storage shape (same as Android's Room tables in the shared
/// wherevercode-tasks.db):
/// - conversations(id, brainId, title, createdAtEpochSeconds)
/// - conversation_messages(id, conversationId, role, content,
///   source, createdAtEpochSeconds, taskId, toolCallsJson, toolCallId)
/// `toolCallsJson` is a JSON array string; a corrupt cell decodes to
/// an empty list and never breaks reads. Ordering is by
/// createdAtEpochSeconds with rowid as the tiebreak.

public struct ChatToolCall: Equatable, Codable {
    public var id: String
    public var name: String
    public var argumentsJson: String

    public init(id: String, name: String, argumentsJson: String) {
        self.id = id
        self.name = name
        self.argumentsJson = argumentsJson
    }
}

public struct Conversation: Equatable {
    public var id: String
    public var brainId: String?
    public var title: String
    public var createdAtEpochSeconds: Int64

    public init(id: String, brainId: String?, title: String, createdAtEpochSeconds: Int64) {
        self.id = id
        self.brainId = brainId
        self.title = title
        self.createdAtEpochSeconds = createdAtEpochSeconds
    }
}

public struct ConversationMessage: Equatable {
    public static let roles: Set<String> = ["user", "assistant", "tool", "system"]

    public var id: String
    public var conversationId: String
    /// One of user / assistant / tool / system.
    public var role: String
    public var content: String
    /// The real origin of the message (model id or Agent id), so the
    /// UI can always label where a bubble came from.
    public var source: String
    public var createdAtEpochSeconds: Int64
    /// Task this message belongs to, when part of a task discussion.
    public var taskId: String?
    /// Structured tool calls requested by an assistant message.
    public var toolCalls: [ChatToolCall]
    /// For a tool message: the call it answers.
    public var toolCallId: String?

    public init(
        id: String,
        conversationId: String,
        role: String,
        content: String,
        source: String,
        createdAtEpochSeconds: Int64,
        taskId: String? = nil,
        toolCalls: [ChatToolCall] = [],
        toolCallId: String? = nil
    ) {
        self.id = id
        self.conversationId = conversationId
        self.role = role
        self.content = content
        self.source = source
        self.createdAtEpochSeconds = createdAtEpochSeconds
        self.taskId = taskId
        self.toolCalls = toolCalls
        self.toolCallId = toolCallId
    }
}

public enum ConversationStoreError: Error, Equatable {
    case unknownConversation(String)
    case invalidRole(String)
    case duplicateConversation(String)
}

public protocol ConversationStore {
    func createConversation(brainId: String?, title: String) throws -> Conversation
    func getConversation(id: String) throws -> Conversation?
    /// Get by id, creating under that same id when missing — the
    /// caller-supplied id is reused, never replaced (the chat UI
    /// owns the session id; the engine must not mint a fresh row
    /// per turn).
    func getOrCreateConversation(id: String, brainId: String?, title: String) throws -> Conversation
    func listConversations() throws -> [Conversation]
    func updateConversationBrain(id: String, brainId: String?) throws
    @discardableResult
    func deleteConversation(id: String) throws -> Bool
    @discardableResult
    func addMessage(_ message: ConversationMessage) throws -> ConversationMessage
    /// All messages in insertion order; with a limit, the last N.
    func listMessages(conversationId: String, limit: Int?) throws -> [ConversationMessage]
    /// All messages bound to a task, across conversations.
    func listMessagesForTask(taskId: String) throws -> [ConversationMessage]
}

extension ConversationStore {
    public func listMessages(conversationId: String) throws -> [ConversationMessage] {
        try listMessages(conversationId: conversationId, limit: nil)
    }
}

/// In-memory implementation with the same contract (Android ships
/// both; this one backs previews and fast tests).
public final class InMemoryConversationStore: ConversationStore {
    private var conversations: [String: Conversation] = [:]
    private var messages: [String: [ConversationMessage]] = [:]
    private let lock = NSLock()

    public init() {}

    public func createConversation(brainId: String?, title: String) throws -> Conversation {
        let conversation = Conversation(
            id: UUID().uuidString, brainId: brainId, title: title,
            createdAtEpochSeconds: Int64(Date().timeIntervalSince1970))
        lock.lock(); defer { lock.unlock() }
        conversations[conversation.id] = conversation
        messages[conversation.id] = []
        return conversation
    }

    public func getConversation(id: String) throws -> Conversation? {
        lock.lock(); defer { lock.unlock() }
        return conversations[id]
    }

    public func getOrCreateConversation(id: String, brainId: String?, title: String) throws -> Conversation {
        lock.lock(); defer { lock.unlock() }
        if let existing = conversations[id] { return existing }
        let conversation = Conversation(
            id: id, brainId: brainId, title: title,
            createdAtEpochSeconds: Int64(Date().timeIntervalSince1970))
        conversations[id] = conversation
        messages[id] = []
        return conversation
    }

    public func listConversations() throws -> [Conversation] {
        lock.lock(); defer { lock.unlock() }
        return conversations.values.sorted { $0.createdAtEpochSeconds > $1.createdAtEpochSeconds }
    }

    public func updateConversationBrain(id: String, brainId: String?) throws {
        lock.lock(); defer { lock.unlock() }
        conversations[id]?.brainId = brainId
    }

    @discardableResult
    public func deleteConversation(id: String) throws -> Bool {
        lock.lock(); defer { lock.unlock() }
        messages.removeValue(forKey: id)
        return conversations.removeValue(forKey: id) != nil
    }

    @discardableResult
    public func addMessage(_ message: ConversationMessage) throws -> ConversationMessage {
        guard ConversationMessage.roles.contains(message.role) else {
            throw ConversationStoreError.invalidRole(message.role)
        }
        lock.lock(); defer { lock.unlock() }
        guard messages[message.conversationId] != nil else {
            throw ConversationStoreError.unknownConversation(message.conversationId)
        }
        messages[message.conversationId]?.append(message)
        return message
    }

    public func listMessages(conversationId: String, limit: Int?) throws -> [ConversationMessage] {
        lock.lock(); defer { lock.unlock() }
        let list = messages[conversationId] ?? []
        guard let limit else { return list }
        return Array(list.suffix(limit))
    }

    public func listMessagesForTask(taskId: String) throws -> [ConversationMessage] {
        lock.lock(); defer { lock.unlock() }
        return messages.values.flatMap { $0 }.filter { $0.taskId == taskId }
    }
}

/// SQLite-backed store; schema is created by `LocalData`.
public final class SQLiteConversationStore: ConversationStore {
    private let db: SQLiteDatabase

    public init(db: SQLiteDatabase) {
        self.db = db
    }

    private func conversation(from row: [String: SQLiteValue]) -> Conversation {
        Conversation(
            id: row["id"]?.textValue ?? "",
            brainId: row["brainId"]?.textValue,
            title: row["title"]?.textValue ?? "",
            createdAtEpochSeconds: row["createdAtEpochSeconds"]?.intValue ?? 0)
    }

    private func message(from row: [String: SQLiteValue]) -> ConversationMessage {
        let toolCalls: [ChatToolCall]
        if let json = row["toolCallsJson"]?.textValue,
           let data = json.data(using: .utf8),
           let decoded = try? JSONDecoder().decode([ChatToolCall].self, from: data) {
            toolCalls = decoded
        } else {
            toolCalls = []
        }
        return ConversationMessage(
            id: row["id"]?.textValue ?? "",
            conversationId: row["conversationId"]?.textValue ?? "",
            role: row["role"]?.textValue ?? "",
            content: row["content"]?.textValue ?? "",
            source: row["source"]?.textValue ?? "",
            createdAtEpochSeconds: row["createdAtEpochSeconds"]?.intValue ?? 0,
            taskId: row["taskId"]?.textValue,
            toolCalls: toolCalls,
            toolCallId: row["toolCallId"]?.textValue)
    }

    public func createConversation(brainId: String?, title: String) throws -> Conversation {
        let conversation = Conversation(
            id: UUID().uuidString, brainId: brainId, title: title,
            createdAtEpochSeconds: Int64(Date().timeIntervalSince1970))
        try insert(conversation)
        return conversation
    }

    private func insert(_ conversation: Conversation) throws {
        do {
            try db.execute(
                "INSERT INTO conversations (id, brainId, title, createdAtEpochSeconds) VALUES (?, ?, ?, ?)",
                [.text(conversation.id),
                 conversation.brainId.map(SQLiteValue.text) ?? .null,
                 .text(conversation.title),
                 .int(conversation.createdAtEpochSeconds)])
        } catch {
            throw ConversationStoreError.duplicateConversation(conversation.id)
        }
    }

    public func getConversation(id: String) throws -> Conversation? {
        try db.query("SELECT * FROM conversations WHERE id = ?", [.text(id)]).first.map(conversation(from:))
    }

    public func getOrCreateConversation(id: String, brainId: String?, title: String) throws -> Conversation {
        if let existing = try getConversation(id: id) { return existing }
        let conversation = Conversation(
            id: id, brainId: brainId, title: title,
            createdAtEpochSeconds: Int64(Date().timeIntervalSince1970))
        do {
            try insert(conversation)
        } catch {
            // Lost the insert race with the same id: return the row
            // that won instead of failing the turn.
            if let raced = try getConversation(id: id) { return raced }
            throw error
        }
        return conversation
    }

    public func listConversations() throws -> [Conversation] {
        try db.query(
            "SELECT * FROM conversations ORDER BY createdAtEpochSeconds DESC, rowid ASC"
        ).map(conversation(from:))
    }

    public func updateConversationBrain(id: String, brainId: String?) throws {
        try db.execute(
            "UPDATE conversations SET brainId = ? WHERE id = ?",
            [brainId.map(SQLiteValue.text) ?? .null, .text(id)])
    }

    @discardableResult
    public func deleteConversation(id: String) throws -> Bool {
        try db.transaction {
            try db.execute("DELETE FROM conversation_messages WHERE conversationId = ?", [.text(id)])
            let before = try db.query("SELECT COUNT(*) AS c FROM conversations WHERE id = ?", [.text(id)])
            try db.execute("DELETE FROM conversations WHERE id = ?", [.text(id)])
            return (before.first?["c"]?.intValue ?? 0) > 0
        }
    }

    @discardableResult
    public func addMessage(_ message: ConversationMessage) throws -> ConversationMessage {
        guard ConversationMessage.roles.contains(message.role) else {
            throw ConversationStoreError.invalidRole(message.role)
        }
        guard try getConversation(id: message.conversationId) != nil else {
            throw ConversationStoreError.unknownConversation(message.conversationId)
        }
        let toolCallsJson: String
        if let data = try? JSONEncoder().encode(message.toolCalls),
           let json = String(data: data, encoding: .utf8) {
            toolCallsJson = json
        } else {
            toolCallsJson = "[]"
        }
        try db.execute(
            """
            INSERT INTO conversation_messages
            (id, conversationId, role, content, source, createdAtEpochSeconds, taskId, toolCallsJson, toolCallId)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [.text(message.id), .text(message.conversationId), .text(message.role),
             .text(message.content), .text(message.source), .int(message.createdAtEpochSeconds),
             message.taskId.map(SQLiteValue.text) ?? .null, .text(toolCallsJson),
             message.toolCallId.map(SQLiteValue.text) ?? .null])
        return message
    }

    public func listMessages(conversationId: String, limit: Int?) throws -> [ConversationMessage] {
        let rows = try db.query(
            """
            SELECT * FROM conversation_messages WHERE conversationId = ?
            ORDER BY createdAtEpochSeconds ASC, rowid ASC
            """,
            [.text(conversationId)])
        let list = rows.map(message(from:))
        guard let limit else { return list }
        return Array(list.suffix(limit))
    }

    public func listMessagesForTask(taskId: String) throws -> [ConversationMessage] {
        try db.query(
            """
            SELECT * FROM conversation_messages WHERE taskId = ?
            ORDER BY createdAtEpochSeconds ASC, rowid ASC
            """,
            [.text(taskId)]).map(message(from:))
    }
}
