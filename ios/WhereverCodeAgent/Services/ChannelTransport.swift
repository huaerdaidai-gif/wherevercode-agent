// WhereverCode Agent — iOS MVP
//
// Platform channel calls over MCP. The core here is posting a
// message and reading messages back; channel management calls
// (create/rename/archive/delete/members, phase-1 completion) live
// in the marked section below. No task dispatch, no project access.
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

    /// Post with phase-2/4 extras: `effectKey` idempotency key (the
    /// server dedupes retries on it), `attachmentRef` (reference
    /// system — a Drive/delivery reference, never a binary) and
    /// `forwardOf` forwarding provenance label.
    public func postMessageEx(
        channelID: String,
        text: String,
        replyTo: String? = nil,
        effectKey: String? = nil,
        attachmentRef: String? = nil,
        forwardOf: String? = nil
    ) async throws -> String {
        guard text.count <= Self.maxPostLength else {
            throw McpError.transport("消息超过 \(Self.maxPostLength) 字，无法发送。")
        }
        var args: [String: Any] = ["channel_id": channelID, "text": text]
        if let replyTo, !replyTo.isEmpty { args["reply_to"] = replyTo }
        if let effectKey, !effectKey.isEmpty { args["effect_key"] = effectKey }
        if let attachmentRef, !attachmentRef.isEmpty { args["attachment_ref"] = attachmentRef }
        if let forwardOf, !forwardOf.isEmpty { args["forward_of"] = forwardOf }
        let result = try await client.callTool(name: "agent_channel_post", arguments: args)
        if let id = result["id"] as? String, !id.isEmpty { return id }
        if let message = result["message"] as? [String: Any],
           let id = message["id"] as? String { return id }
        return ""
    }

    /// Messages in `channelID` after `afterID` (nil = latest page).
    /// `onlyUnread` / `markRead` are passed through to the server
    /// exactly as documented for `agent_channel_messages`.
    /// `query` (phase-1: in-channel search) is passed through as the
    /// server's `q` argument; results are whatever the server returns.
    /// №12 filters pass through untouched: `beforeID` pages older
    /// history, `sender` matches one member (id or display name),
    /// `fromTs`/`toTs` bound a Unix-second window (from inclusive,
    /// to exclusive), `kind` is image / file / link.
    public func pollMessages(
        channelID: String,
        afterID: String? = nil,
        beforeID: String? = nil,
        limit: Int = defaultPollLimit,
        onlyUnread: Bool = false,
        markRead: Bool = false,
        query: String? = nil,
        sender: String? = nil,
        fromTs: Int? = nil,
        toTs: Int? = nil,
        kind: String? = nil
    ) async throws -> [ChannelMessage] {
        var args: [String: Any] = ["channel_id": channelID, "limit": limit]
        if let afterID, !afterID.isEmpty { args["after_id"] = afterID }
        if let beforeID, !beforeID.isEmpty { args["before_id"] = beforeID }
        if onlyUnread { args["only_unread"] = true }
        if markRead { args["mark_read"] = true }
        if let query, !query.isEmpty { args["q"] = query }
        if let sender, !sender.isEmpty { args["sender"] = sender }
        if let fromTs { args["from_ts"] = fromTs }
        if let toTs { args["to_ts"] = toTs }
        if let kind, !kind.isEmpty { args["kind"] = kind }
        let result = try await client.callTool(name: "agent_channel_messages", arguments: args)
        return ChannelMessage.list(from: result)
    }

    /// New-member history visibility switch (№12). Only the channel
    /// owner / a coordinator may flip it; the server's real error
    /// surfaces untouched when it refuses.
    public func setHistoryVisible(channelID: String, visible: Bool) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_set_history_visible",
            arguments: ["channel_id": channelID, "visible": visible])
    }

    // MARK: - Channel management (phase-1 completion)
    //
    // Tool names follow the existing agent_channel_* convention, with
    // the same argument shapes as the Android transport. NOTE: the
    // platform server does not expose these tools yet (the server is
    // not part of this repo); calls surface the server's real error
    // and callers must show it — never fake success. Once the server
    // adds the tools, this layer works unchanged.

    /// Create a channel; returns the server's raw result dictionary.
    public func createChannel(name: String) async throws -> [String: Any] {
        try await client.callTool(name: "agent_channel_create", arguments: ["name": name])
    }

    /// Rename a channel.
    public func renameChannel(channelID: String, name: String) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_rename",
            arguments: ["channel_id": channelID, "name": name])
    }

    /// Archive or unarchive a channel.
    public func setChannelArchived(channelID: String, archived: Bool) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_set_archived",
            arguments: ["channel_id": channelID, "archived": archived])
    }

    /// Delete a channel. Destructive: callers must confirm first.
    public func deleteChannel(channelID: String) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_delete",
            arguments: ["channel_id": channelID])
    }

    /// Invite one agent into a channel.
    public func addChannelMember(channelID: String, agentID: String) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_add_member",
            arguments: ["channel_id": channelID, "agent_id": agentID])
    }

    /// Remove one agent from a channel.
    public func removeChannelMember(channelID: String, agentID: String) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_remove_member",
            arguments: ["channel_id": channelID, "agent_id": agentID])
    }

    // MARK: - Message actions & phase-3 services
    //
    // Same honesty rule as the management section: when the server
    // does not expose a tool yet, the call surfaces its real error.

    /// Recall one of my own messages (server blanks the text and
    /// leaves a placeholder; the action is audit-logged server-side).
    public func recallMessage(channelID: String, messageID: String) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_recall",
            arguments: ["channel_id": channelID, "message_id": messageID])
    }

    /// Delete a message (sender, or channel admin for governance).
    public func deleteMessage(channelID: String, messageID: String) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_delete_message",
            arguments: ["channel_id": channelID, "message_id": messageID])
    }

    /// Pin or unpin a message.
    public func setMessagePinned(channelID: String, messageID: String, pinned: Bool) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_pin",
            arguments: ["channel_id": channelID, "message_id": messageID, "pinned": pinned])
    }

    /// Toggle one of my emoji reactions on a message.
    public func reactMessage(channelID: String, messageID: String, emoji: String, on: Bool) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_react",
            arguments: ["channel_id": channelID, "message_id": messageID, "emoji": emoji, "on": on])
    }

    /// Who has read up to a message (phase-3 read receipts).
    public func channelReaders(channelID: String, messageID: String) async throws -> [ChannelReader] {
        let result = try await client.callTool(
            name: "agent_channel_readers",
            arguments: ["channel_id": channelID, "message_id": messageID])
        return ChannelReader.list(from: result)
    }

    /// Pinned messages only (server `pinned_only`).
    public func pollPinnedMessages(channelID: String) async throws -> [ChannelMessage] {
        let result = try await client.callTool(
            name: "agent_channel_messages",
            arguments: ["channel_id": channelID, "limit": 100, "pinned_only": true])
        return ChannelMessage.list(from: result)
    }

    /// Open (or fetch) the DM channel with one channel member
    /// (phase-3 DM; entry is the in-page 频道/私聊 segment, scheme A).
    public func openDm(agentID: String) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_open_dm",
            arguments: ["agent_id": agentID])
    }

    /// Create an inbound webhook for a channel; the token is returned
    /// exactly once and is user-managed.
    public func createChannelWebhook(channelID: String) async throws -> [String: Any] {
        try await client.callTool(
            name: "agent_channel_create_webhook",
            arguments: ["channel_id": channelID])
    }
}
