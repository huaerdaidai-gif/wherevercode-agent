import Foundation

/// Key-value persistence over the shared local database — the iOS
/// counterpart of Android's SharedPreferences-backed stores
/// (AgentStore's JSON, ChatHistoryStore's prefs, cursor stores).
public final class KeyValueStore {
    private let db: SQLiteDatabase

    public init(db: SQLiteDatabase) {
        self.db = db
    }

    public func get(_ key: String) throws -> String? {
        try db.query("SELECT value FROM kv WHERE key = ?", [.text(key)]).first?["value"]?.textValue
    }

    public func set(_ key: String, _ value: String?) throws {
        if let value {
            try db.execute(
                "INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                [.text(key), .text(value)])
        } else {
            try db.execute("DELETE FROM kv WHERE key = ?", [.text(key)])
        }
    }

    public func getJSON<T: Decodable>(_ key: String, as type: T.Type) throws -> T? {
        guard let raw = try get(key), let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    public func setJSON<T: Encodable>(_ key: String, _ value: T?) throws {
        guard let value else {
            try set(key, nil)
            return
        }
        let data = try JSONEncoder().encode(value)
        try set(key, String(data: data, encoding: .utf8))
    }
}

/// Per-channel read cursor: the last platform message id the App
/// has seen per channel (Android: PlatformReplyCursorStore).
public final class ReplyCursorStore {
    private let kv: KeyValueStore

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    private func key(channelId: String) -> String { "channelCursor.\(channelId)" }

    public func lastSeenMessageId(channelId: String) throws -> String? {
        try kv.get(key(channelId: channelId))
    }

    public func setLastSeenMessageId(channelId: String, messageId: String?) throws {
        try kv.set(key(channelId: channelId), messageId)
    }
}

/// Chat-UI prefs mirroring Android's `ui/ChatHistoryStore.kt`:
/// current session id, session→project bindings, direct-agent
/// session flags. (The saved-turns payload itself moves to the
/// conversation store; these small bindings stay key-value, as on
/// Android.)
public final class ChatHistoryPrefs {
    private let kv: KeyValueStore

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    public var currentSessionId: String? {
        get { try? kv.get("chat.currentSessionId") ?? nil }
        set { try? kv.set("chat.currentSessionId", newValue) }
    }

    public func sessionProjects() throws -> [String: String] {
        try kv.getJSON("chat.sessionProjects", as: [String: String].self) ?? [:]
    }

    public func setSessionProjects(_ bindings: [String: String]) throws {
        try kv.setJSON("chat.sessionProjects", bindings)
    }

    public func directAgentSessions() throws -> [String: Bool] {
        try kv.getJSON("chat.directAgentSessions", as: [String: Bool].self) ?? [:]
    }

    public func setDirectAgentSessions(_ flags: [String: Bool]) throws {
        try kv.setJSON("chat.directAgentSessions", flags)
    }
}
