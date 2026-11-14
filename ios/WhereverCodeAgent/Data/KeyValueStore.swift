import Foundation

public final class KeyValueStore {
    private let db: SQLiteDatabase

    public init(db: SQLiteDatabase) throws {
        self.db = db
        try db.execute("""
        CREATE TABLE IF NOT EXISTS kv (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """)
    }

    public func get(_ key: String) throws -> String? {
        let rows = try db.query("SELECT value FROM kv WHERE key = ?", parameters: [.text(key)])
        return rows.first?["value"]?.stringValue
    }

    public func set(_ key: String, _ value: String?) throws {
        if let value {
            try db.execute(
                "INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                parameters: [.text(key), .text(value)]
            )
        } else {
            try db.execute("DELETE FROM kv WHERE key = ?", parameters: [.text(key)])
        }
    }

    public func getJSON<T: Decodable>(_ key: String, as type: T.Type) throws -> T? {
        guard let str = try get(key) else { return nil }
        return try JSONDecoder().decode(T.self, from: Data(str.utf8))
    }

    public func setJSON<T: Encodable>(_ key: String, _ value: T?) throws {
        if let value {
            let data = try JSONEncoder().encode(value)
            let str = String(data: data, encoding: .utf8) ?? "{}"
            try set(key, str)
        } else {
            try set(key, nil)
        }
    }
}

public final class ReplyCursorStore {
    private let kv: KeyValueStore

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    public func lastSeenMessageId(channelId: String) throws -> String? {
        try kv.get("cursor.\(channelId)")
    }

    public func setLastSeenMessageId(channelId: String, messageId: String?) throws {
        try kv.set("cursor.\(channelId)", messageId)
    }
}

public final class ChatHistoryPrefs {
    private let kv: KeyValueStore

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    public func currentSessionId() throws -> String? {
        try kv.get("chat.currentSessionId")
    }

    public func setCurrentSessionId(_ id: String?) throws {
        try kv.set("chat.currentSessionId", id)
    }
}
