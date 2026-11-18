// WhereverCode Agent — iOS MVP
//
// Platform channel calls over MCP. Only two things happen here:
// posting a message and reading messages back. No channel creation,
// no task dispatch, no project access — those belong to the platform.
//
// Argument shapes verified against the live server, 2026-11-13:
// - `agent_channel_post`: `channel_id` + `text` (max 4000 chars) +
//   optional `reply_to`. There is NO recipient field; addressing an
//   agent is done with an `@<Name> ` prefix inside the text.
// - `agent_channel_messages`: `channel_id`, optional `limit` /
//   `after_id` / `before_id` / `q` / `only_unread` / `mark_read`.
// - `agent_channel_list`: no arguments.

import Foundation

public struct ChannelTransport {
    public static let maxPostLength = 4000
    public static let defaultPollLimit = 20

    private let client: McpClient

    public init(client: McpClient) {
        self.client = client
    }

    /// Channels visible to this token.
    public func listChannels() async throws -> [ChannelInfo] {
        let result = try await client.callTool(name: "agent_channel_list")
        return ChannelInfo.list(from: result)
    }

    /// Join a channel so its messages appear for this token.
    public func joinChannel(channelID: String) async throws {
        _ = try await client.callTool(name: "agent_channel_join", arguments: ["channel_id": channelID])
    }

    /// Post one message. Returns the posted message id (""
    /// when the server does not return one).
    public func postMessage(channelID: String, text: String, replyTo: String? = nil) async throws -> String {
        guard text.count <= Self.maxPostLength else {
            throw McpError.transport("消息超过 \(Self.maxPostLength) 字，无法发送。")
        }
        var args: [String: Any] = ["channel_id": channelID, "text": text]
        if let replyTo, !replyTo.isEmpty { args["reply_to"] = replyTo }
        let result = try await client.callTool(name: "agent_channel_post", arguments: args)
        if let id = result["id"] as? String, !id.isEmpty { return id }
        if let message = result["message"] as? [String: Any],
           let id = message["id"] as? String { return id }
        return ""
    }

    /// Messages in `channelID` after `afterID` (nil = latest page).
    /// `onlyUnread` / `markRead` are passed through to the server
    /// exactly as documented for `agent_channel_messages`.
    public func pollMessages(
        channelID: String,
        afterID: String? = nil,
        limit: Int = defaultPollLimit,
        onlyUnread: Bool = false,
        markRead: Bool = false
    ) async throws -> [ChannelMessage] {
        var args: [String: Any] = ["channel_id": channelID, "limit": limit]
        if let afterID, !afterID.isEmpty { args["after_id"] = afterID }
        if onlyUnread { args["only_unread"] = true }
        if markRead { args["mark_read"] = true }
        let result = try await client.callTool(name: "agent_channel_messages", arguments: args)
        return ChannelMessage.list(from: result)
    }
}
