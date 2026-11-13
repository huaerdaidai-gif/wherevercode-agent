// WhereverCode Agent — iOS MVP
//
// Models shared by the channel, task and settings features.
// All server payloads are decoded tolerantly: unknown fields are ignored
// and missing optional fields fall back to empty values, so a server-side
// field rename never crashes the app. Nothing here contains credentials,
// real addresses or personal names.

import Foundation

/// One message read back from a V5 platform channel.
///
/// Wire shape (verified against the live MCP server, 2026-11-13):
/// `{"id": ..., "agent_id": ..., "agent_name": ..., "text": ...,
///   "reply_to": ... | null, "created_at": <unix seconds, fractional>}`.
public struct ChannelMessage: Identifiable, Equatable, Hashable {
    public let id: String
    public let agentId: String
    public let agentName: String
    public let text: String
    public let replyTo: String?
    /// Unix time in seconds, as sent by the server (fractional).
    public let createdAtSeconds: Double

    public init(
        id: String,
        agentId: String,
        agentName: String,
        text: String,
        replyTo: String? = nil,
        createdAtSeconds: Double = 0
    ) {
        self.id = id
        self.agentId = agentId
        self.agentName = agentName
        self.text = text
        self.replyTo = replyTo
        self.createdAtSeconds = createdAtSeconds
    }

    public var createdAt: Date {
        Date(timeIntervalSince1970: createdAtSeconds)
    }

    /// Short display name: the agent name when present, otherwise the id.
    public var displaySender: String {
        agentName.isEmpty ? (agentId.isEmpty ? "?" : agentId) : agentName
    }

    /// Build from a decoded tool-result dictionary. Returns nil when the
    /// message has no usable id (such entries are dropped, never shown).
    public static func from(dict: [String: Any]) -> ChannelMessage? {
        guard let id = stringField(dict, keys: ["id"]), !id.isEmpty else { return nil }
        return ChannelMessage(
            id: id,
            agentId: stringField(dict, keys: ["agent_id"]) ?? "",
            agentName: stringField(dict, keys: ["agent_name"]) ?? "",
            text: stringField(dict, keys: ["text"]) ?? "",
            replyTo: stringField(dict, keys: ["reply_to"]),
            createdAtSeconds: numberField(dict, keys: ["created_at"]) ?? 0
        )
    }

    /// Build a list from a `messages` array, dropping entries without ids.
    public static func list(from dict: [String: Any]) -> [ChannelMessage] {
        guard let raw = dict["messages"] as? [[String: Any]] else { return [] }
        return raw.compactMap(ChannelMessage.from(dict:))
    }
}

/// One channel the current token can see (from `agent_channel_list`).
public struct ChannelInfo: Identifiable, Equatable, Hashable {
    public let id: String
    public let name: String
    public let participants: [String]
    public let lastMessageText: String?

    public init(id: String, name: String, participants: [String] = [], lastMessageText: String? = nil) {
        self.id = id
        self.name = name
        self.participants = participants
        self.lastMessageText = lastMessageText
    }

    public var displayName: String { name.isEmpty ? id : name }

    public static func from(dict: [String: Any]) -> ChannelInfo? {
        guard let id = stringField(dict, keys: ["id", "channel_id"]), !id.isEmpty else { return nil }
        var participants: [String] = []
        if let raw = dict["participants"] as? [String] {
            participants = raw
        } else if let raw = dict["participants"] as? [[String: Any]] {
            participants = raw.compactMap { stringField($0, keys: ["agent_name", "name", "agent_id"]) }
        }
        var last: String?
        if let message = dict["last_message"] as? [String: Any] {
            last = stringField(message, keys: ["text"])
        } else {
            last = stringField(dict, keys: ["last_message_text"])
        }
        return ChannelInfo(
            id: id,
            name: stringField(dict, keys: ["name"]) ?? "",
            participants: participants,
            lastMessageText: last
        )
    }

    public static func list(from dict: [String: Any]) -> [ChannelInfo] {
        guard let raw = dict["channels"] as? [[String: Any]] else { return [] }
        return raw.compactMap(ChannelInfo.from(dict:))
    }
}

/// A task assigned to this agent token (from `list_my_tasks`).
///
/// The server does not publish a fixed task object schema, so every field
/// is read tolerantly: several candidate keys are tried and anything
/// missing falls back to an empty value. The task is never considered
/// "done" from this struct alone — completion is decided by the server.
public struct AgentTask: Identifiable, Equatable, Hashable {
    public let id: String
    public let title: String
    public let detail: String
    /// Raw status string from the server (for example "queued" / "claimed").
    /// Displayed as-is; the app never invents a status mapping.
    public let status: String
    public let version: Int?

    public init(id: String, title: String, detail: String = "", status: String = "", version: Int? = nil) {
        self.id = id
        self.title = title
        self.detail = detail
        self.status = status
        self.version = version
    }

    public var displayTitle: String { title.isEmpty ? id : title }

    public static func from(dict: [String: Any]) -> AgentTask? {
        guard let id = stringField(dict, keys: ["id", "task_id"]), !id.isEmpty else { return nil }
        var version: Int?
        if let v = dict["version"] as? Int { version = v }
        else if let v = dict["expected_version"] as? Int { version = v }
        else if let v = dict["version"] as? Double { version = Int(v) }
        return AgentTask(
            id: id,
            title: stringField(dict, keys: ["title", "summary", "text", "description"]) ?? "",
            detail: stringField(dict, keys: ["description", "detail", "body"]) ?? "",
            status: stringField(dict, keys: ["status", "state"]) ?? "",
            version: version
        )
    }

    public static func list(from dict: [String: Any]) -> [AgentTask] {
        guard let raw = dict["tasks"] as? [[String: Any]] else { return [] }
        return raw.compactMap(AgentTask.from(dict:))
    }
}

/// Channel connection state, shown honestly in the chat header.
/// LIVE means messages arrive by WebSocket push; POLLING means the
/// socket is down and the app is falling back to polling.
public enum ChannelConnectionState: Equatable {
    case stopped
    case connecting
    case live
    case polling

    public var label: String {
        switch self {
        case .stopped: return "未连接"
        case .connecting: return "连接中"
        case .live: return "实时"
        case .polling: return "轮询兜底"
        }
    }
}

/// Helpers shared by the tolerant dictionary decoders above.
func stringField(_ dict: [String: Any], keys: [String]) -> String? {
    for key in keys {
        if let value = dict[key] as? String, !value.isEmpty { return value }
        if let value = dict[key] as? NSNumber { return value.stringValue }
    }
    return nil
}

func numberField(_ dict: [String: Any], keys: [String]) -> Double? {
    for key in keys {
        if let value = dict[key] as? Double { return value }
        if let value = dict[key] as? Int { return Double(value) }
        if let value = dict[key] as? NSNumber { return value.doubleValue }
        if let value = dict[key] as? String, let parsed = Double(value) { return parsed }
    }
    return nil
}
