import Foundation

public struct ChatToolCall: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let name: String
    public let argumentsJson: String

    public init(id: String, name: String, argumentsJson: String) {
        self.id = id
        self.name = name
        self.argumentsJson = argumentsJson
    }
}

public struct Conversation: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public var brainId: String?
    public var title: String
    public let createdAtEpochSeconds: Int64

    public init(id: String, brainId: String?, title: String, createdAtEpochSeconds: Int64) {
        self.id = id
        self.brainId = brainId
        self.title = title
        self.createdAtEpochSeconds = createdAtEpochSeconds
    }
}

public struct ConversationMessage: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let conversationId: String
    public let role: String
    public let content: String
    public let source: String
    public let createdAtEpochSeconds: Int64
    public let taskId: String?
    public let toolCalls: [ChatToolCall]
    public let toolCallId: String?

    public init(
        id: String = UUID().uuidString,
        conversationId: String,
        role: String,
        content: String,
        source: String = "local",
        createdAtEpochSeconds: Int64 = Int64(Date().timeIntervalSince1970),
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

public enum ConversationStoreError: Error, LocalizedError, Equatable {
    case unknownConversation(String)
    case invalidRole(String)
    case duplicateConversation(String)

    public var errorDescription: String? {
        switch self {
        case .unknownConversation(let id): return "未知会话：\(id)"
        case .invalidRole(let role): return "不支持的消息角色：\(role)"
        case .duplicateConversation(let id): return "重复会话：\(id)"
        }
    }
}

public protocol ConversationStore: AnyObject {
    func createConversation(brainId: String?, title: String) throws -> Conversation
    func getOrCreateConversation(id: String, brainId: String?, title: String) throws -> Conversation
    func getConversation(id: String) throws -> Conversation?
    func listConversations() throws -> [Conversation]
    func updateConversationBrain(id: String, brainId: String?) throws
    func deleteConversation(id: String) throws
    func appendMessage(_ message: ConversationMessage) throws
    func listMessages(conversationId: String) throws -> [ConversationMessage]
    func listMessagesForTask(taskId: String) throws -> [ConversationMessage]
}

public final class InMemoryConversationStore: ConversationStore {
    private var conversations: [String: Conversation] = [:]
    private var messages: [String: [ConversationMessage]] = [:]
    private let lock = NSLock()

    public init() {}

    public func createConversation(brainId: String?, title: String) throws -> Conversation {
        let conv = Conversation(
            id: UUID().uuidString,
            brainId: brainId,
            title: title,
            createdAtEpochSeconds: Int64(Date().timeIntervalSince1970)
        )
        lock.lock()
        defer { lock.unlock() }
        conversations[conv.id] = conv
        messages[conv.id] = []
        return conv
    }

    public func getOrCreateConversation(id: String, brainId: String?, title: String) throws -> Conversation {
        lock.lock()
        defer { lock.unlock() }
        if let existing = conversations[id] { return existing }
        let conv = Conversation(
            id: id,
            brainId: brainId,
            title: title,
            createdAtEpochSeconds: Int64(Date().timeIntervalSince1970)
        )
        conversations[id] = conv
        messages[id] = []
        return conv
    }

    public func getConversation(id: String) throws -> Conversation? {
        lock.lock()
        defer { lock.unlock() }
        return conversations[id]
    }

    public func listConversations() throws -> [Conversation] {
        lock.lock()
        defer { lock.unlock() }
        return conversations.values.sorted { $0.createdAtEpochSeconds > $1.createdAtEpochSeconds }
    }

    public func updateConversationBrain(id: String, brainId: String?) throws {
        lock.lock()
        defer { lock.unlock() }
        guard var conv = conversations[id] else { throw ConversationStoreError.unknownConversation(id) }
        conv.brainId = brainId
        conversations[id] = conv
    }

    public func deleteConversation(id: String) throws {
        lock.lock()
        defer { lock.unlock() }
        conversations.removeValue(forKey: id)
        messages.removeValue(forKey: id)
    }

    public func appendMessage(_ message: ConversationMessage) throws {
        lock.lock()
        defer { lock.unlock() }
        guard conversations[message.conversationId] != nil else {
            throw ConversationStoreError.unknownConversation(message.conversationId)
        }
        messages[message.conversationId, default: []].append(message)
    }

    public func listMessages(conversationId: String) throws -> [ConversationMessage] {
        lock.lock()
        defer { lock.unlock() }
        return messages[conversationId] ?? []
    }

    public func listMessagesForTask(taskId: String) throws -> [ConversationMessage] {
        lock.lock()
        defer { lock.unlock() }
        return messages.values.flatMap { $0 }.filter { $0.taskId == taskId }
    }
}

public final class SQLiteConversationStore: ConversationStore {
    private let db: SQLiteDatabase

    public init(db: SQLiteDatabase) throws {
        self.db = db
        try db.execute("""
        CREATE TABLE IF NOT EXISTS conversations (
            id TEXT PRIMARY KEY,
            brainId TEXT,
            title TEXT NOT NULL,
            createdAtEpochSeconds INTEGER NOT NULL
        )
        """)
        try db.execute("CREATE INDEX IF NOT EXISTS index_conversations_createdAtEpochSeconds ON conversations (createdAtEpochSeconds)")
        try db.execute("""
        CREATE TABLE IF NOT EXISTS conversation_messages (
            id TEXT PRIMARY KEY,
            conversationId TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            source TEXT NOT NULL,
            createdAtEpochSeconds INTEGER NOT NULL,
            taskId TEXT,
            toolCallsJson TEXT NOT NULL DEFAULT '[]',
            toolCallId TEXT
        )
        """)
        try db.execute("CREATE INDEX IF NOT EXISTS index_conversation_messages_conversationId ON conversation_messages (conversationId)")
        try db.execute("CREATE INDEX IF NOT EXISTS index_conversation_messages_taskId ON conversation_messages (taskId)")
    }

    public func createConversation(brainId: String?, title: String) throws -> Conversation {
        let conv = Conversation(
            id: UUID().uuidString,
            brainId: brainId,
            title: title,
            createdAtEpochSeconds: Int64(Date().timeIntervalSince1970)
        )
        try db.execute(
            "INSERT INTO conversations (id, brainId, title, createdAtEpochSeconds) VALUES (?, ?, ?, ?)",
            parameters: [
                .text(conv.id),
                brainId.map(SQLiteValue.text) ?? .null,
                .text(conv.title),
                .int(conv.createdAtEpochSeconds)
            ]
        )
        return conv
    }

    public func getOrCreateConversation(id: String, brainId: String?, title: String) throws -> Conversation {
        if let existing = try getConversation(id: id) {
            return existing
        }
        let conv = Conversation(
            id: id,
            brainId: brainId,
            title: title,
            createdAtEpochSeconds: Int64(Date().timeIntervalSince1970)
        )
        try db.execute(
            "INSERT INTO conversations (id, brainId, title, createdAtEpochSeconds) VALUES (?, ?, ?, ?)",
            parameters: [
                .text(conv.id),
                brainId.map(SQLiteValue.text) ?? .null,
                .text(conv.title),
                .int(conv.createdAtEpochSeconds)
            ]
        )
        return conv
    }

    public func getConversation(id: String) throws -> Conversation? {
        let rows = try db.query("SELECT * FROM conversations WHERE id = ?", parameters: [.text(id)])
        guard let row = rows.first else { return nil }
        return Conversation(
            id: row["id"]?.stringValue ?? id,
            brainId: row["brainId"]?.stringValue,
            title: row["title"]?.stringValue ?? "",
            createdAtEpochSeconds: row["createdAtEpochSeconds"]?.int64Value ?? 0
        )
    }

    public func listConversations() throws -> [Conversation] {
        let rows = try db.query("SELECT * FROM conversations ORDER BY createdAtEpochSeconds DESC, rowid ASC")
        return rows.map { row in
            Conversation(
                id: row["id"]?.stringValue ?? "",
                brainId: row["brainId"]?.stringValue,
                title: row["title"]?.stringValue ?? "",
                createdAtEpochSeconds: row["createdAtEpochSeconds"]?.int64Value ?? 0
            )
        }
    }

    public func updateConversationBrain(id: String, brainId: String?) throws {
        try db.execute(
            "UPDATE conversations SET brainId = ? WHERE id = ?",
            parameters: [brainId.map(SQLiteValue.text) ?? .null, .text(id)]
        )
    }

    public func deleteConversation(id: String) throws {
        try db.inTransaction {
            try db.execute("DELETE FROM conversation_messages WHERE conversationId = ?", parameters: [.text(id)])
            try db.execute("DELETE FROM conversations WHERE id = ?", parameters: [.text(id)])
        }
    }

    public func appendMessage(_ message: ConversationMessage) throws {
        let toolJsonData = (try? JSONEncoder().encode(message.toolCalls)) ?? Data("[]".utf8)
        let toolJson = String(data: toolJsonData, encoding: .utf8) ?? "[]"
        try db.execute(
            """
            INSERT INTO conversation_messages
            (id, conversationId, role, content, source, createdAtEpochSeconds, taskId, toolCallsJson, toolCallId)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            parameters: [
                .text(message.id),
                .text(message.conversationId),
                .text(message.role),
                .text(message.content),
                .text(message.source),
                .int(message.createdAtEpochSeconds),
                message.taskId.map(SQLiteValue.text) ?? .null,
                .text(toolJson),
                message.toolCallId.map(SQLiteValue.text) ?? .null
            ]
        )
    }

    public func listMessages(conversationId: String) throws -> [ConversationMessage] {
        let rows = try db.query(
            "SELECT * FROM conversation_messages WHERE conversationId = ? ORDER BY createdAtEpochSeconds ASC, rowid ASC",
            parameters: [.text(conversationId)]
        )
        return rows.map(decodeMessage)
    }

    public func listMessagesForTask(taskId: String) throws -> [ConversationMessage] {
        let rows = try db.query(
            "SELECT * FROM conversation_messages WHERE taskId = ? ORDER BY createdAtEpochSeconds ASC, rowid ASC",
            parameters: [.text(taskId)]
        )
        return rows.map(decodeMessage)
    }

    private func decodeMessage(_ row: [String: SQLiteValue]) -> ConversationMessage {
        let jsonStr = row["toolCallsJson"]?.stringValue ?? "[]"
        let calls = (try? JSONDecoder().decode([ChatToolCall].self, from: Data(jsonStr.utf8))) ?? []
        return ConversationMessage(
            id: row["id"]?.stringValue ?? UUID().uuidString,
            conversationId: row["conversationId"]?.stringValue ?? "",
            role: row["role"]?.stringValue ?? "user",
            content: row["content"]?.stringValue ?? "",
            source: row["source"]?.stringValue ?? "local",
            createdAtEpochSeconds: row["createdAtEpochSeconds"]?.int64Value ?? 0,
            taskId: row["taskId"]?.stringValue,
            toolCalls: calls,
            toolCallId: row["toolCallId"]?.stringValue
        )
    }
}
