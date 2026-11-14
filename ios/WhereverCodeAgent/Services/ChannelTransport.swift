import Foundation

public enum ChannelConnectionState: String, Equatable, Sendable {
    case stopped
    case connecting
    case live
    case polling
}

public struct ChannelTransport: Sendable {
    public let client: McpClient

    public init(client: McpClient) {
        self.client = client
    }

    public func listChannels() async throws -> [ChannelInfo] {
        let res = try await client.callTool(name: "agent_channel_list", arguments: [:])
        let items = (res["channels"] as? [[String: Any]]) ?? []
        return items.compactMap { item in
            guard let id = (item["channel_id"] as? String) ?? (item["id"] as? String) else { return nil }
            let name = (item["name"] as? String) ?? id
            let parts = (item["participants"] as? [String]) ?? []
            let last = item["last_message_text"] as? String
            return ChannelInfo(id: id, name: name, participants: parts, lastMessageText: last)
        }
    }

    public func joinChannel(channelID: String) async throws {
        _ = try await client.callTool(name: "agent_channel_join", arguments: ["channel_id": channelID])
    }

    public func postMessage(channelID: String, text: String, replyTo: String? = nil) async throws -> String {
        var args: [String: Any] = ["channel_id": channelID, "text": text]
        if let replyTo { args["reply_to"] = replyTo }
        let res = try await client.callTool(name: "agent_channel_post", arguments: args)
        return (res["message_id"] as? String) ?? UUID().uuidString
    }

    public func pollMessages(
        channelID: String,
        afterID: String? = nil,
        limit: Int = 50,
        onlyUnread: Bool = false,
        markRead: Bool = true
    ) async throws -> [ChannelMessage] {
        var args: [String: Any] = [
            "channel_id": channelID,
            "limit": limit,
            "only_unread": onlyUnread,
            "mark_read": markRead
        ]
        if let afterID { args["after_id"] = afterID }
        let res = try await client.callTool(name: "agent_channel_messages", arguments: args)
        let list = (res["messages"] as? [[String: Any]]) ?? []
        return list.compactMap { m in
            guard let id = (m["message_id"] as? String) ?? (m["id"] as? String),
                  let text = m["text"] as? String else { return nil }
            let agentId = (m["agent_id"] as? String) ?? "unknown"
            let agentName = (m["agent_name"] as? String) ?? agentId
            let replyTo = m["reply_to"] as? String
            let ts = (m["created_at"] as? Double) ?? Date().timeIntervalSince1970
            return ChannelMessage(id: id, agentId: agentId, agentName: agentName, text: text, replyTo: replyTo, createdAtSeconds: ts)
        }
    }
}
