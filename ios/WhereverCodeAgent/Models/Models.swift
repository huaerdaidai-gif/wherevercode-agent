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
/// One aggregated emoji reaction on a channel message (phase-2).
public struct ChannelReaction: Identifiable, Equatable, Hashable, Codable, Sendable {
    public let emoji: String
    public let count: Int
    public let mine: Bool

    public var id: String { emoji }

    public init(emoji: String, count: Int, mine: Bool = false) {
        self.emoji = emoji
        self.count = count
        self.mine = mine
    }

    public static func list(from value: Any?) -> [ChannelReaction] {
        guard let raw = value as? [[String: Any]] else { return [] }
        return raw.compactMap { dict in
            guard let emoji = stringField(dict, keys: ["emoji"]), !emoji.isEmpty else { return nil }
            return ChannelReaction(
                emoji: emoji,
                count: (dict["count"] as? NSNumber)?.intValue ?? 0,
                mine: (dict["mine"] as? Bool) ?? false
            )
        }
    }
}

/// One member's read state for a message (phase-3 read receipts).
public struct ChannelReader: Identifiable, Equatable, Hashable, Sendable {
    public let agentId: String
    public let agentName: String
    public let read: Bool

    public var id: String { agentId }

    public init(agentId: String, agentName: String, read: Bool) {
        self.agentId = agentId
        self.agentName = agentName
        self.read = read
    }

    public static func list(from dict: [String: Any]) -> [ChannelReader] {
        guard let raw = dict["readers"] as? [[String: Any]] else { return [] }
        return raw.compactMap { entry in
            guard let agentId = stringField(entry, keys: ["agent_id"]), !agentId.isEmpty else { return nil }
            return ChannelReader(
                agentId: agentId,
                agentName: stringField(entry, keys: ["agent_name"]) ?? "",
                read: (entry["read"] as? Bool) ?? false
            )
        }
    }
}

public struct ChannelMessage: Identifiable, Equatable, Hashable, Codable, Sendable {
    public let id: String
    public let agentId: String
    public let agentName: String
    public let isAgent: Bool?
    public let text: String
    public let replyTo: String?
    /// Unix time in seconds, as sent by the server (fractional).
    public let createdAtSeconds: Double
    /// Recalled by its sender (phase-2): the server blanks the text;
    /// the UI shows a placeholder instead.
    public let recalled: Bool
    /// Pinned in the channel (phase-2).
    public let pinned: Bool
    /// Aggregated emoji reactions (phase-2).
    public let reactions: [ChannelReaction]
    /// Attachment reference (phase-2, reference system: no binaries).
    public let attachmentRef: String?
    /// Forwarding provenance label (phase-2).
    public let forwardOf: String?

    public init(
        id: String,
        agentId: String,
        agentName: String,
        text: String,
        replyTo: String? = nil,
        createdAtSeconds: Double = 0,
        recalled: Bool = false,
        pinned: Bool = false,
        reactions: [ChannelReaction] = [],
        attachmentRef: String? = nil,
        forwardOf: String? = nil,
        isAgent: Bool? = nil
    ) {
        self.id = id
        self.agentId = agentId
        self.agentName = agentName
        self.isAgent = isAgent
        self.text = text
        self.replyTo = replyTo
        self.createdAtSeconds = createdAtSeconds
        self.recalled = recalled
        self.pinned = pinned
        self.reactions = reactions
        self.attachmentRef = attachmentRef
        self.forwardOf = forwardOf
    }

    public var createdAt: Date {
        Date(timeIntervalSince1970: createdAtSeconds)
    }

    /// Short display name: the agent name when present, otherwise the id.
    public var displaySender: String {
        agentName.isEmpty ? (agentId.isEmpty ? "?" : agentId) : agentName
    }

    private enum CodingKeys: String, CodingKey {
        case id, agentId, agentName, isAgent, text, replyTo, createdAtSeconds
        case recalled, pinned, reactions, attachmentRef, forwardOf
    }

    /// Tolerant decoding: payloads/caches written before phase-2 lack
    /// the new fields; they decode as false/empty instead of failing.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        agentId = try c.decodeIfPresent(String.self, forKey: .agentId) ?? ""
        agentName = try c.decodeIfPresent(String.self, forKey: .agentName) ?? ""
        isAgent = try c.decodeIfPresent(Bool.self, forKey: .isAgent)
        text = try c.decodeIfPresent(String.self, forKey: .text) ?? ""
        replyTo = try c.decodeIfPresent(String.self, forKey: .replyTo)
        createdAtSeconds = try c.decodeIfPresent(Double.self, forKey: .createdAtSeconds) ?? 0
        recalled = try c.decodeIfPresent(Bool.self, forKey: .recalled) ?? false
        pinned = try c.decodeIfPresent(Bool.self, forKey: .pinned) ?? false
        reactions = try c.decodeIfPresent([ChannelReaction].self, forKey: .reactions) ?? []
        attachmentRef = try c.decodeIfPresent(String.self, forKey: .attachmentRef)
        forwardOf = try c.decodeIfPresent(String.self, forKey: .forwardOf)
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
            createdAtSeconds: numberField(dict, keys: ["created_at"]) ?? 0,
            recalled: (dict["recalled"] as? Bool) ?? false,
            pinned: (dict["pinned"] as? Bool) ?? false,
            reactions: ChannelReaction.list(from: dict["reactions"]),
            attachmentRef: stringField(dict, keys: ["attachment_ref"]),
            forwardOf: stringField(dict, keys: ["forward_of"]),
            isAgent: dict["is_agent"] as? Bool
        )
    }

    /// Build a list from a `messages` array, dropping entries without ids.
    public static func list(from dict: [String: Any]) -> [ChannelMessage] {
        guard let raw = dict["messages"] as? [[String: Any]] else { return [] }
        return raw.compactMap(ChannelMessage.from(dict:))
    }
}

/// Identity and role from the platform channel participant list.
public struct ChannelParticipant: Equatable, Hashable, Codable, Sendable {
    public let id: String
    public let name: String
    public let role: String?
    public let isAgent: Bool?
    public let isSelf: Bool

    public var canMentionAll: Bool {
        guard let role else { return false }
        return ["owner", "coordinator", "所有者", "项目主管", "协调者"].contains(
            role.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    }
}

/// One channel the current token can see (from `agent_channel_list`).
public struct ChannelInfo: Identifiable, Equatable, Hashable, Codable, Sendable {
    public let id: String
    public let name: String
    public let participants: [String]
    public let participantDetails: [ChannelParticipant]
    public let lastMessageText: String?
    /// Archived flag (phase-1 channel management); absent on the wire = false.
    public let isArchived: Bool
    /// Channel kind (phase-3): "channel" or "dm" (direct message).
    public let kind: String

    public init(id: String, name: String, participants: [String] = [], lastMessageText: String? = nil, isArchived: Bool = false, kind: String = "channel", participantDetails: [ChannelParticipant] = []) {
        self.id = id
        self.name = name
        self.participants = participants
        self.participantDetails = participantDetails
        self.lastMessageText = lastMessageText
        self.isArchived = isArchived
        self.kind = kind
    }

    /// True for direct-message channels (phase-3 DM, scheme A segment).
    public var isDM: Bool { kind == "dm" }

    private enum CodingKeys: String, CodingKey {
        case id, name, participants, participantDetails, lastMessageText, isArchived, kind
    }

    /// Tolerant decoding: caches written before phase-3 lack `kind`.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        participants = try c.decodeIfPresent([String].self, forKey: .participants) ?? []
        participantDetails = try c.decodeIfPresent([ChannelParticipant].self, forKey: .participantDetails) ?? []
        lastMessageText = try c.decodeIfPresent(String.self, forKey: .lastMessageText)
        isArchived = try c.decodeIfPresent(Bool.self, forKey: .isArchived) ?? false
        kind = try c.decodeIfPresent(String.self, forKey: .kind) ?? "channel"
    }

    public var displayName: String { name.isEmpty ? id : name }

    public static func from(dict: [String: Any]) -> ChannelInfo? {
        guard let id = stringField(dict, keys: ["id", "channel_id"]), !id.isEmpty else { return nil }
        let ownerID = stringField(dict, keys: ["owner_agent_id", "owner_id"])
        let details: [ChannelParticipant]
        if let raw = dict["participants"] as? [[String: Any]] {
            details = raw.compactMap { participant in
                guard let memberID = stringField(participant, keys: ["agent_id", "id"]), !memberID.isEmpty else { return nil }
                return ChannelParticipant(
                    id: memberID,
                    name: stringField(participant, keys: ["agent_name", "name"]) ?? memberID,
                    role: memberID == ownerID ? "owner" : stringField(participant, keys: ["role"]),
                    isAgent: participant["is_agent"] as? Bool,
                    isSelf: participant["is_self"] as? Bool ?? false
                )
            }
        } else {
            details = (dict["participants"] as? [String] ?? []).filter { !$0.isEmpty }.map {
                ChannelParticipant(id: $0, name: $0, role: nil, isAgent: nil, isSelf: false)
            }
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
            participants: details.map(\.name),
            lastMessageText: last,
            isArchived: (dict["archived"] as? Bool) ?? (dict["is_archived"] as? Bool) ?? false,
            kind: stringField(dict, keys: ["kind"]) ?? "channel",
            participantDetails: details
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
public struct AgentTask: Identifiable, Equatable, Hashable, Codable, Sendable {
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
public enum ChannelConnectionState: String, Equatable, Sendable {
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

/// A feature the product design reserves but the V5 server does not expose yet.
public enum ReservedFeature: String, CaseIterable, Identifiable, Sendable {
    case approval
    case pauseTask
    case resumeTask
    case stopTask
    case retargetTask
    case deviceManagement
    case brainChat
    case singleAgentChat

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .approval: return "审批"
        case .pauseTask: return "暂停任务"
        case .resumeTask: return "恢复任务"
        case .stopTask: return "停止任务"
        case .retargetTask: return "改目标"
        case .deviceManagement: return "设备管理"
        case .brainChat: return "大脑对话"
        case .singleAgentChat: return "单兵对话"
        }
    }

    public var note: String {
        switch self {
        case .approval:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有审批工具，无法真实执行；任务提交前请先在频道里人工确认。"
        case .pauseTask:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有暂停任务工具，无法真实执行。"
        case .resumeTask:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有恢复任务工具，无法真实执行。"
        case .stopTask:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有停止任务工具，无法真实执行；不要的任务请用「放手任务」交回。"
        case .retargetTask:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有改目标工具，无法真实执行；请放手任务后由调度重新派发。"
        case .deviceManagement:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有设备 / Agent 列表与配对工具，本页读不到真实设备；设备需先在 Android 端或平台侧添加。"
        case .brainChat:
            return "未实现：大脑对话尚未接入，先用团队频道与任务页。"
        case .singleAgentChat:
            return "未实现：单兵对话尚未接入，先用团队频道与任务页。"
        }
    }

    public var notImplementedReason: String { note }
}

// MARK: - Channel completion (phases 2-4): pure helpers, mirrored with Android

/// Pure channel-completion logic (no UI, no I/O): reactions, pins,
/// reply quotes, message center aggregation, read-receipt text,
/// forwarding labels, post effect keys and the scheduled-send queue.
/// Mirrors Android's ChannelExtras so both ends behave identically.
public enum ChannelExtras {
    public static let reactionChoices = ["👍", "❤️", "🎉", "👀"]
    public static let recalledPlaceholder = "此消息已撤回"

    public static func reactionText(_ reactions: [ChannelReaction]) -> String {
        reactions.filter { $0.count > 0 }
            .map { "\($0.emoji) \($0.count)" }
            .joined(separator: " · ")
    }

    public static func myReaction(_ reactions: [ChannelReaction], emoji: String) -> Bool {
        reactions.contains { $0.emoji == emoji && $0.mine }
    }

    public static func pinnedMessages(_ messages: [ChannelMessage]) -> [ChannelMessage] {
        messages.filter { $0.pinned && !$0.recalled }
    }

    public static func displayText(_ message: ChannelMessage) -> String {
        message.recalled ? recalledPlaceholder : message.text
    }

    /// "回复 <sender>：<excerpt>" for a reply whose parent is in the list.
    public static func replyQuote(in messages: [ChannelMessage], for message: ChannelMessage) -> String? {
        guard let parentId = message.replyTo,
              let parent = messages.first(where: { $0.id == parentId }) else { return nil }
        let excerpt = String(displayText(parent).replacingOccurrences(of: "\n", with: " ").prefix(40))
        return "回复 \(parent.displaySender)：\(excerpt)"
    }

    public static func forwardOfLabel(sourceChannelName: String, senderName: String) -> String {
        let channel = sourceChannelName.trimmingCharacters(in: .whitespaces).isEmpty ? "频道" : sourceChannelName
        let sender = senderName.trimmingCharacters(in: .whitespaces).isEmpty ? "未知" : senderName
        return "\(channel) · \(sender)"
    }

    public static func mentionsAny(_ message: ChannelMessage, names: [String]) -> Bool {
        if message.recalled { return false }
        return names.contains { !$0.isEmpty && message.text.contains("@\($0)") }
    }

    /// 文本里是否写了 @所有人（`@all` / `@所有人`）。
    public static func mentionsAllText(_ text: String) -> Bool {
        text.contains("@all") || text.contains("@所有人")
    }

    /// 文本里是否写了 @所有人。撤回的消息不算提及。
    public static func mentionsAll(_ message: ChannelMessage) -> Bool {
        if message.recalled { return false }
        return mentionsAllText(message.text)
    }

    /// 这条 @所有人是否生效：只有 owner/协调者发的才算全员提及；
    /// 其他人写的 @all 只当普通文本（不渲染提及、不计入消息中心）。
    /// 客户端已知的特权发送者是本机自己；频道 owner 的服务端语义
    /// 待定，本轮不另造工具名。
    public static func allMentionLive(
        _ message: ChannelMessage, privilegedSenderIds: Set<String>
    ) -> Bool {
        mentionsAll(message) && privilegedSenderIds.contains(message.agentId)
    }

    /// Message-center aggregate for one channel (phase-3).
    /// A live @所有人 (see ``allMentionLive``) also counts as a
    /// mention — it is addressed to everyone by definition.
    public static func buildMessageCenter(
        messages: [ChannelMessage],
        myNames: [String],
        lastReadId: String?,
        privilegedSenderIds: Set<String> = []
    ) -> MessageCenter {
        // Mirrors Android: no marker = nothing counted; a marker that
        // is not in the list counts everything as unread.
        let unread: [ChannelMessage]
        if lastReadId == nil {
            unread = []
        } else if let readIndex = messages.firstIndex(where: { $0.id == lastReadId }) {
            unread = Array(messages.dropFirst(readIndex + 1))
        } else {
            unread = messages
        }
        func isMention(_ m: ChannelMessage) -> Bool {
            mentionsAny(m, names: myNames) || allMentionLive(m, privilegedSenderIds: privilegedSenderIds)
        }
        let mentions = messages.filter { isMention($0) }
        let unreadMentions = unread.filter { isMention($0) }.count
        return MessageCenter(
            mentionMessages: mentions,
            unreadCount: unread.count,
            unreadMentionCount: unreadMentions
        )
    }

    public static func readersText(_ readers: [ChannelReader]) -> String {
        if readers.isEmpty { return "暂无成员已读信息。" }
        let read = readers.filter { $0.read }.map { $0.agentName.isEmpty ? $0.agentId : $0.agentName }
        let unread = readers.filter { !$0.read }.map { $0.agentName.isEmpty ? $0.agentId : $0.agentName }
        var parts = ["已读：" + (read.isEmpty ? "暂无人" : read.joined(separator: "、"))]
        if !unread.isEmpty { parts.append("未读：" + unread.joined(separator: "、")) }
        return parts.joined(separator: " · ")
    }

    /// Post idempotency key (client-side discipline):
    /// minted once per send intent; retries reuse it and the server
    /// dedupes on (channel, sender, key). This is just `ck_`+nonce,
    /// not an AWM KeyMint.
    public static func buildEffectKey(nonce: String) -> String {
        let cleaned = nonce.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        return "ck_" + String(cleaned.prefix(100))
    }

    /// AWM evidence grade label (phase-4 evidence display; same
    /// grades as server/awm/evidence.py). Display only — an
    /// observation never poses as an E3 authoritative receipt.
    public static func evidenceGradeLabel(_ grade: String?) -> String {
        switch grade {
        case "E0": return "E0 已入队"
        case "E1": return "E1 已送达"
        case "E2": return "E2 已执行"
        case "E3": return "E3 已确认生效"
        case "H": return "H 人工接力"
        default: return ""
        }
    }

    /// Highest grade wins, in evidence.py's order: E3 > H > E2 > E1 > E0.
    public static func highestEvidenceGrade(_ grades: [String]) -> String? {
        for grade in ["E3", "H", "E2", "E1", "E0"] where grades.contains(grade) {
            return grade
        }
        return nil
    }

    public static func dueScheduled(_ items: [ScheduledChannelPost], nowSeconds: Double) -> [ScheduledChannelPost] {
        items.filter { $0.dueAtSeconds <= nowSeconds }.sorted { $0.dueAtSeconds < $1.dueAtSeconds }
    }

    /// 频道群主角色。与 ``ChannelParticipant/canMentionAll`` 分开：
    /// 后者含群主和协调者两档，不能拿来当“不可移除”。
    public static let channelOwnerRoles: Set<String> = [
        "owner", "所有者", "项目主管", "群主", "频道群主",
    ]

    public static func isChannelOwnerRole(_ role: String?) -> Bool {
        guard let role else { return false }
        let normalized = role.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return channelOwnerRoles.contains(normalized)
    }

    /// 成员移除保护：只有本人和频道群主。协调者即使能 @所有人也可移除。
    public static func isProtectedChannelMember(isSelf: Bool, role: String?) -> Bool {
        isSelf || isChannelOwnerRole(role)
    }

    /// 转发 @所有人门闩。无目标频道权限时返回应展示的错误，调用方不得发帖。
    public static func forwardMentionAllBlockReason(text: String, canMentionAllInTarget: Bool) -> String? {
        guard mentionsAllText(text), !canMentionAllInTarget else { return nil }
        return "只有目标频道 owner/协调者可以转发 @所有人，消息未发送。"
    }

    public enum ForwardFailure: Error {
        case blocked(String)
        case failed(String)

        public var message: String {
            switch self {
            case .blocked(let message), .failed(let message):
                return message
            }
        }
    }

    /// 先过目标频道 @所有人门闩，被拦时不调用 `post`。
    public static func forwardMessage(
        text: String,
        canMentionAllInTarget: Bool,
        post: () async throws -> String
    ) async -> Result<String, ForwardFailure> {
        if let reason = forwardMentionAllBlockReason(
            text: text, canMentionAllInTarget: canMentionAllInTarget) {
            return .failure(.blocked(reason))
        }
        do {
            return .success(try await post())
        } catch {
            let message = (error as? McpError)?.message ?? "转发失败。"
            return .failure(.failed(message))
        }
    }

    /// 展示用 isAgent。平台明确给了 is_agent 就用平台值（含本人）；
    /// 缺失时本人按非 Agent 兜底，其他人再看成员名单。
    public static func displayedIsAgent(
        platformIsAgent: Bool?,
        senderIsSelf: Bool,
        rosterIsAgent: Bool? = nil
    ) -> Bool {
        if let platformIsAgent { return platformIsAgent }
        if senderIsSelf { return false }
        return rosterIsAgent ?? false
    }

    /// 定时发送回执。空 id 视为未确认：队列原样返回，effect_key 不动。
    public static func scheduledQueueAfterFlush(
        items: [ScheduledChannelPost],
        itemID: String,
        postedID: String
    ) -> [ScheduledChannelPost] {
        guard !postedID.isEmpty else { return items }
        return items.filter { $0.id != itemID }
    }

    /// Effective unread count considering per-channel notification mode (phase-3 orphan).
    public static func effectiveUnreadCount(
        unreadCount: Int,
        unreadMentionCount: Int,
        notifyMode: String
    ) -> Int {
        switch notifyMode {
        case "静音", "免打扰":
            return 0
        case "仅@我":
            return unreadMentionCount
        default:
            return unreadCount
        }
    }

    /// Safe markdown rendering with foolproof plain-text fallback (phase-3 orphan).
    public static func safeMarkdownAttributedString(_ text: String) -> AttributedString {
        if let attr = try? AttributedString(markdown: text, options: AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)) {
            return attr
        }
        return AttributedString(text)
    }
}

/// Message-center aggregate (phase-3).
public struct MessageCenter: Equatable, Sendable {
    public let mentionMessages: [ChannelMessage]
    public let unreadCount: Int
    public let unreadMentionCount: Int

    public init(mentionMessages: [ChannelMessage], unreadCount: Int, unreadMentionCount: Int) {
        self.mentionMessages = mentionMessages
        self.unreadCount = unreadCount
        self.unreadMentionCount = unreadMentionCount
    }
}

/// One scheduled channel post (phase-2): persisted on-device by the
/// caller; sent when due with its effect key so replays never
/// double-post.
public struct ScheduledChannelPost: Identifiable, Equatable, Codable, Sendable {
    public let id: String
    public let channelId: String
    public let text: String
    public let dueAtSeconds: Double
    public let effectKey: String
    public let attachmentRef: String?
    /// 回复目标（定时发一条回复时用；与安卓 ScheduledChannelPost 同形）。
    public let replyToId: String?

    public init(id: String, channelId: String, text: String, dueAtSeconds: Double, effectKey: String, attachmentRef: String? = nil, replyToId: String? = nil) {
        self.id = id
        self.channelId = channelId
        self.text = text
        self.dueAtSeconds = dueAtSeconds
        self.effectKey = effectKey
        self.attachmentRef = attachmentRef
        self.replyToId = replyToId
    }
}

/// Channel notification level (phase-3 orphan align).
public enum ChannelNotifyMode: String, CaseIterable, Sendable {
    case all = "全部消息"
    case mentionsOnly = "仅@我"
    case muted = "静音"

    public var prompt: String {
        switch self {
        case .all: return "接收所有新消息通知与未读计数"
        case .mentionsOnly: return "仅接收提及我的消息通知与未读提醒"
        case .muted: return "不接收通知，不计入未读提醒"
        }
    }
}

/// Team thread model (phase-3 orphan align, dual-client unified).
public struct TeamThread: Identifiable, Equatable, Hashable, Sendable {
    public var id: String { rootMessageId ?? taskId ?? "general" }
    public let taskId: String?
    public let taskTitle: String?
    public let rootMessageId: String?
    public let topicTitle: String?
    public let messages: [ChannelMessage]

    public init(
        taskId: String? = nil,
        taskTitle: String? = nil,
        rootMessageId: String? = nil,
        topicTitle: String? = nil,
        messages: [ChannelMessage] = []
    ) {
        self.taskId = taskId
        self.taskTitle = taskTitle
        self.rootMessageId = rootMessageId
        self.topicTitle = topicTitle
        self.messages = messages
    }

    public var isTopicThread: Bool {
        rootMessageId != nil
    }
}

public func groupTopicThreads(messages: [ChannelMessage]) -> [TeamThread] {
    if messages.isEmpty { return [] }
    let msgMap = Dictionary(messages.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    var rootIds: [String] = []
    for m in messages {
        guard let replyId = m.replyTo, !replyId.isEmpty, msgMap[replyId] != nil else { continue }
        var curr: String? = replyId
        var depth = 0
        while let c = curr, depth < 20 {
            if let parent = msgMap[c], let pReply = parent.replyTo, !pReply.isEmpty, msgMap[pReply] != nil, pReply != c {
                curr = pReply
                depth += 1
            } else {
                break
            }
        }
        if let root = curr, !rootIds.contains(root) {
            rootIds.append(root)
        }
    }

    var threads: [TeamThread] = []
    for rootId in rootIds {
        guard let rootMsg = msgMap[rootId] else { continue }
        var threadMsgs: [ChannelMessage] = [rootMsg]
        for m in messages {
            if m.id == rootId { continue }
            var p = m.replyTo
            var depth = 0
            while let currentP = p, currentP != rootId, depth < 20 {
                p = msgMap[currentP]?.replyTo
                depth += 1
            }
            if p == rootId {
                threadMsgs.append(m)
            }
        }
        let sorted = threadMsgs.sorted { $0.createdAtSeconds < $1.createdAtSeconds }
        let title = String(rootMsg.text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(30))
            .replacingOccurrences(of: "\n", with: " ")
        threads.append(
            TeamThread(
                taskId: nil,
                taskTitle: nil,
                rootMessageId: rootId,
                topicTitle: title.isEmpty ? "话题讨论" : title,
                messages: sorted
            )
        )
    }
    return threads.sorted { ($0.messages.last?.createdAtSeconds ?? 0) > ($1.messages.last?.createdAtSeconds ?? 0) }
}

public func teamThreadTitle(thread: TeamThread) -> String {
    if let topic = thread.topicTitle, !topic.isEmpty {
        return "话题 · \(topic)"
    }
    if let taskId = thread.taskId, !taskId.isEmpty {
        if let title = thread.taskTitle, !title.isEmpty {
            return "任务 \(String(taskId.prefix(8))) · \(title)"
        }
        return "任务 \(String(taskId.prefix(8)))"
    }
    return "综合讨论"
}

/// Typing signaling event frame (zero token, no model call).
public struct ChannelTypingEvent: Equatable, Sendable {
    public let channelId: String
    public let senderId: String
    public let senderName: String
    public let isTyping: Bool

    public init(channelId: String, senderId: String, senderName: String, isTyping: Bool = true) {
        self.channelId = channelId
        self.senderId = senderId
        self.senderName = senderName
        self.isTyping = isTyping
    }
}
