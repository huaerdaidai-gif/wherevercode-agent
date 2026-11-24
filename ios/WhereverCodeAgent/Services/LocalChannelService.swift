// WhereverCode Agent — iOS
//
// 本地频道账本（频道三层一期·本地层，方案便签 №11）：把平台服务端
// 的频道语义搬进 App 本机——频道/成员/角色/消息/已读标记/反应/
// 置顶/撤回/Webhook 记录全在本机库里，零配置、零网络可用。
//
// 语义与安卓 LocalChannelService（同源规格）逐项对齐，包括：发帖
// 自动入群（不刷新 joined_at）、读消息的成员门禁（非成员同文案）、
// 私聊禁自行加入、历史不可见按 joined_at 截断、@所有人 仅特权
// 角色生效、effect_key 幂等、角色设置的 owner 专属与最后一个
// owner 保护。服务端尚未实现的二/三期操作（撤回/删除/置顶/反应/
// 已读名单/pinned_only）在这里按 App 客户端既有调用口径补齐——
// 本地模式下这些功能是真可用的，不是摆设。
//
// 线程：全部公开方法同一把可重入锁；每次变更后整表快照落盘
// （本地账本体量小，快照写足够；格式见 snapshotJson）。

import CryptoKit
import Foundation

/// 本地平台调用失败：消息即给用户看的真实原因，本地优先客户端
/// 把它原样包成 MCP 错误结果，与远程平台报错同一条呈现路径，
/// 绝不假成功。
public struct LocalServiceError: Error, Equatable {
    public let message: String
    public init(_ message: String) { self.message = message }
}

/// 本地频道账本的持久化口：生产实现写 App 私有文件，测试用内存实现。
public protocol LocalChannelPersistence {
    func load() -> String?
    func save(_ text: String)
}

/// 内存持久化（测试与无文件环境兜底；进程退出即丢，只用于这两处）。
public final class InMemoryLocalChannelPersistence: LocalChannelPersistence {
    public var stored: String?
    public init() {}
    public func load() -> String? { stored }
    public func save(_ text: String) { stored = text }
}

/// 本地消息总线的订阅令牌（取消订阅凭它定位监听器）。
public final class LocalChannelListenerToken {
    fileprivate let id = UUID()
    fileprivate let channelId: String
    fileprivate let handler: ([String: Any]) -> Void
    fileprivate init(channelId: String, handler: @escaping ([String: Any]) -> Void) {
        self.channelId = channelId
        self.handler = handler
    }
}

public final class LocalChannelService {
    private final class Agent {
        let id: String
        var name: String
        init(id: String, name: String) { self.id = id; self.name = name }
    }

    private final class Channel {
        let id: String
        var name: String
        var kind: String
        var archived: Bool
        var historyVisible: Bool
        let createdAt: Double
        init(id: String, name: String, kind: String, archived: Bool, historyVisible: Bool, createdAt: Double) {
            self.id = id; self.name = name; self.kind = kind
            self.archived = archived; self.historyVisible = historyVisible
            self.createdAt = createdAt
        }
    }

    private final class Member {
        let agentId: String
        var agentName: String
        var role: String
        let joinedAt: Double
        init(agentId: String, agentName: String, role: String, joinedAt: Double) {
            self.agentId = agentId; self.agentName = agentName
            self.role = role; self.joinedAt = joinedAt
        }
    }

    private final class Message {
        let id: String
        let channelId: String
        let agentId: String
        let agentName: String
        var text: String
        let replyTo: String?
        let createdAt: Double
        let seq: Int64
        let mentionsAll: Bool
        let attachmentRef: String?
        let forwardOf: String?
        var pinned: Bool
        var recalled: Bool
        init(id: String, channelId: String, agentId: String, agentName: String, text: String,
             replyTo: String?, createdAt: Double, seq: Int64, mentionsAll: Bool,
             attachmentRef: String?, forwardOf: String?, pinned: Bool = false, recalled: Bool = false) {
            self.id = id; self.channelId = channelId; self.agentId = agentId
            self.agentName = agentName; self.text = text; self.replyTo = replyTo
            self.createdAt = createdAt; self.seq = seq; self.mentionsAll = mentionsAll
            self.attachmentRef = attachmentRef; self.forwardOf = forwardOf
            self.pinned = pinned; self.recalled = recalled
        }
    }

    private final class Webhook {
        let id: String
        let channelId: String
        let name: String
        let secretHash: String
        let createdBy: String
        let createdAt: Double
        init(id: String, channelId: String, name: String, secretHash: String, createdBy: String, createdAt: Double) {
            self.id = id; self.channelId = channelId; self.name = name
            self.secretHash = secretHash; self.createdBy = createdBy; self.createdAt = createdAt
        }
    }

    private let persistence: LocalChannelPersistence
    private let taskPersistence: TaskDispatchPersistence
    private let clock: () -> Double
    private let lock = NSRecursiveLock()

    private var agents: [String: Agent] = [:]
    private var agentOrder: [String] = []
    private var channels: [String: Channel] = [:]
    private var channelOrder: [String] = []
    private var members: [String: [Member]] = [:]
    private var messages: [String: [Message]] = [:]
    /// messageId -> [(emoji, [agentId])]，两层都保插入顺序（同安卓 LinkedHashMap/LinkedHashSet）。
    private var reactions: [String: [(emoji: String, agentIds: [String])]] = [:]
    private var readMarkers: [String: String] = [:]
    private var webhooks: [Webhook] = []
    private var effectKeys: [String: String] = [:]
    private var seqCounter: Int64 = 0
    private var storedDefaultChannelId: String = ""

    private var listeners: [String: [LocalChannelListenerToken]] = [:]

    /// 任务派工服务（contracts/task-dispatch/spec.md）：dispatch 的
    /// 一行钩子按工具名路由进来；bridge 在 W8 接线（事件写回频道）。
    private let taskBridge = WeakTaskBridge()
    private lazy var taskDispatch: TaskDispatchService = TaskDispatchService(
        store: TaskDispatchStore(persistence: taskPersistence, clock: clock),
        directory: { [weak self] id in self?.agents[id]?.name },
        bridge: taskBridge,
        clock: clock
    )

    /// spec §5 写回落点：以本机 App 身份把任务事件写成频道消息，
    /// 正文末尾带 [task:<id>] 引用行（W11 任务卡按它关联渲染）。
    /// 频道不存在/已归档时静默跳过——写回失败不回滚任务操作。
    /// 与 postMessage 同语义：发帖自动入群（App 作为成员出现）。
    func postTaskEventMessage(channelId: String, taskId: String, text: String) {
        lock.lock(); defer { lock.unlock() }
        guard let channel = channels[channelId], !channel.archived else { return }
        if members[channel.id] == nil { members[channel.id] = [] }
        if !(members[channel.id]?.contains(where: { $0.agentId == LocalPlatform.appAgentID }) ?? false) {
            members[channel.id]?.append(
                Member(agentId: LocalPlatform.appAgentID,
                       agentName: LocalPlatform.appAgentName,
                       role: "member", joinedAt: clock()))
        }
        seqCounter += 1
        let message = Message(
            id: newId(),
            channelId: channel.id,
            agentId: LocalPlatform.appAgentID,
            agentName: LocalPlatform.appAgentName,
            text: text + "\n[task:" + taskId + "]",
            replyTo: nil,
            createdAt: clock(),
            seq: seqCounter,
            mentionsAll: false,
            attachmentRef: nil,
            forwardOf: nil
        )
        messages[channel.id, default: []].append(message)
        persist()
        fireMessage(channelId: channel.id,
                    messageJson: messageJson(message, viewerId: LocalPlatform.appAgentID))
    }

    public init(persistence: LocalChannelPersistence, clock: @escaping () -> Double = { Date().timeIntervalSince1970 }, taskPersistence: TaskDispatchPersistence = InMemoryTaskDispatchPersistence()) {
        self.persistence = persistence
        self.taskPersistence = taskPersistence
        self.clock = clock
        restore()
        ensureSeed()
        taskBridge.target = self
    }

    // ---- 身份与种子 -------------------------------------------------------

    /// 登记一位本机已知 Agent（本机 App 自己 + 注册表里的 Agent）。
    public func registerAgent(agentId: String, agentName: String) {
        lock.lock(); defer { lock.unlock() }
        registerAgentLocked(agentId: agentId, agentName: agentName)
    }

    private func registerAgentLocked(agentId: String, agentName: String) {
        let id = agentId.trimmingCharacters(in: .whitespacesAndNewlines)
        if id.isEmpty { return }
        let trimmed = agentName.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = trimmed.isEmpty ? id : trimmed
        if let existing = agents[id] {
            if existing.name != name {
                existing.name = name
                persist()
            }
        } else {
            agents[id] = Agent(id: id, name: name)
            agentOrder.append(id)
            persist()
        }
    }

    public func isRegisteredAgent(_ agentId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return agents[agentId] != nil
    }

    /// 本地默认频道 id（首次启动种子建好，之后稳定不变）。
    public func defaultChannelId() -> String {
        lock.lock(); defer { lock.unlock() }
        return storedDefaultChannelId
    }

    public func channelExists(_ channelId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return channels[channelId] != nil
    }

    /// 某 Agent 是否某频道成员（内网层 WS 订阅门禁用，与安卓同口径）。
    public func isChannelMember(channelId: String, agentId: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return memberOf(channelId, agentId) != nil
    }

    private func ensureSeed() {
        registerAgentLocked(agentId: LocalPlatform.appAgentID, agentName: LocalPlatform.appAgentName)
        if storedDefaultChannelId.isEmpty || channels[storedDefaultChannelId] == nil {
            let channel = Channel(
                id: newId(),
                name: Self.defaultChannelName,
                kind: "channel",
                archived: false,
                historyVisible: true,
                createdAt: clock()
            )
            channels[channel.id] = channel
            channelOrder.append(channel.id)
            members[channel.id] = [
                Member(agentId: LocalPlatform.appAgentID, agentName: LocalPlatform.appAgentName, role: "owner", joinedAt: clock()),
            ]
            messages[channel.id] = []
            storedDefaultChannelId = channel.id
            persist()
        }
    }

    // ---- 实时推送（本地总线，喂给频道页的 WS 形状事件） --------------------

    public func addMessageListener(channelId: String, handler: @escaping ([String: Any]) -> Void) -> LocalChannelListenerToken {
        lock.lock(); defer { lock.unlock() }
        let token = LocalChannelListenerToken(channelId: channelId, handler: handler)
        listeners[channelId, default: []].append(token)
        return token
    }

    public func removeMessageListener(_ token: LocalChannelListenerToken) {
        lock.lock(); defer { lock.unlock() }
        listeners[token.channelId]?.removeAll { $0.id == token.id }
    }

    private func fireMessage(channelId: String, messageJson: [String: Any]) {
        let targets = listeners[channelId] ?? []
        if targets.isEmpty { return }
        let event: [String: Any] = [
            "type": "message",
            "channel_id": channelId,
            "message": messageJson,
        ]
        for target in targets {
            target.handler(event)
        }
    }

    // ---- 工具分发（与平台 MCP 工具同名同参同结果形状） ---------------------

    /// 执行一个平台频道工具，返回工具结果字典（即 MCP content 文本
    /// 的解析对象）。失败抛 LocalServiceError，文案与服务端同口径。
    public func dispatch(tool: String, args: [String: Any], callerId: String, callerName: String) throws -> [String: Any] {
        lock.lock(); defer { lock.unlock() }
        if TaskDispatchService.isTaskTool(tool) {
            do {
                // 作用域：本机 App 身份 = owner，其余调用方 = agent；
                // 内网 owner 令牌的作用域识别在 W12 接注册表后补。
                let scope = callerId == LocalPlatform.appAgentID
                    ? TaskDispatchService.scopeOwner : TaskDispatchService.scopeAgent
                return try taskDispatch.dispatch(tool: tool, args: args, callerId: callerId, callerName: callerName, callerScope: scope)
            } catch let error as TaskDispatchError {
                throw LocalServiceError(error.message)
            }
        }
        switch tool {
        case "agent_channel_list":
            return ["channels": channelOrder.compactMap { channels[$0] }.map { channelJson($0, viewerId: callerId) }]
        case "agent_channel_join":
            return try joinChannel(args, callerId: callerId, callerName: callerName)
        case "agent_channel_open_dm":
            return try openDm(args, callerId: callerId, callerName: callerName)
        case "agent_channel_post":
            return try postMessage(args, callerId: callerId, callerName: callerName)
        case "agent_channel_messages":
            return ["messages": try listMessages(args, callerId: callerId)]
        case "agent_channel_create":
            return try createChannel(args, callerId: callerId, callerName: callerName)
        case "agent_channel_rename":
            return try renameChannel(args, callerId: callerId)
        case "agent_channel_set_archived":
            return try setArchived(args, callerId: callerId)
        case "agent_channel_set_history_visible":
            return try setHistoryVisible(args, callerId: callerId)
        case "agent_channel_delete":
            return try deleteChannel(args, callerId: callerId)
        case "agent_channel_add_member":
            return try addMember(args, callerId: callerId)
        case "agent_channel_remove_member":
            return try removeMember(args, callerId: callerId)
        case "agent_channel_set_member_role":
            return try setMemberRole(args, callerId: callerId)
        case "agent_channel_create_webhook":
            return try createWebhook(args, callerId: callerId)
        case "agent_channel_list_webhooks":
            return try listWebhooks(args, callerId: callerId)
        case "agent_channel_delete_webhook":
            return try deleteWebhook(args, callerId: callerId)
        case "agent_channel_recall":
            return try recallMessage(args, callerId: callerId)
        case "agent_channel_delete_message":
            return try deleteMessage(args, callerId: callerId)
        case "agent_channel_pin":
            return try pinMessage(args, callerId: callerId)
        case "agent_channel_react":
            return try reactMessage(args, callerId: callerId)
        case "agent_channel_readers":
            return try channelReaders(args, callerId: callerId)
        default:
            throw LocalServiceError("本地平台没有这个工具：\(tool)")
        }
    }

    // ---- 频道与成员 --------------------------------------------------------

    private func channelJson(_ channel: Channel, viewerId: String?) -> [String: Any] {
        var participants: [[String: Any]] = []
        for member in members[channel.id] ?? [] {
            participants.append([
                "agent_id": member.agentId,
                "agent_name": member.agentName,
                "role": member.role,
                "is_self": viewerId != nil && member.agentId == viewerId,
            ])
        }
        return [
            "id": channel.id,
            "name": channel.name,
            "kind": channel.kind,
            "archived": channel.archived,
            "history_visible": channel.historyVisible,
            "participants": participants,
        ]
    }

    private func requireChannel(_ channelId: String) throws -> Channel {
        guard let channel = channels[channelId] else {
            throw LocalServiceError("频道不存在")
        }
        return channel
    }

    private func memberOf(_ channelId: String, _ agentId: String) -> Member? {
        members[channelId]?.first { $0.agentId == agentId }
    }

    @discardableResult
    private func requireMember(_ channelId: String, _ agentId: String) throws -> Member {
        guard let member = memberOf(channelId, agentId) else {
            throw LocalServiceError("你不是该频道成员")
        }
        return member
    }

    private func joinChannel(_ args: [String: Any], callerId: String, callerName: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        if channel.kind == "dm" { throw LocalServiceError("私聊不能自行加入") }
        if memberOf(channel.id, callerId) == nil {
            members[channel.id, default: []].append(
                Member(agentId: callerId, agentName: callerName, role: "member", joinedAt: clock()))
            persist()
        }
        return channelJson(channel, viewerId: callerId)
    }

    private func openDm(_ args: [String: Any], callerId: String, callerName: String) throws -> [String: Any] {
        let targetId = try reqText(args, "agent_id", 120)
        if targetId == callerId { throw LocalServiceError("不能与自己私聊") }
        guard let target = agents[targetId] else {
            throw LocalServiceError("agent \(targetId) 不存在")
        }
        let existing = channelOrder.compactMap { channels[$0] }.first { channel in
            channel.kind == "dm" && Set((members[channel.id] ?? []).map { $0.agentId }) == Set([callerId, targetId])
        }
        if let existing { return channelJson(existing, viewerId: callerId) }
        let channel = Channel(id: newId(), name: target.name, kind: "dm", archived: false, historyVisible: true, createdAt: clock())
        channels[channel.id] = channel
        channelOrder.append(channel.id)
        members[channel.id] = [
            Member(agentId: callerId, agentName: callerName, role: "member", joinedAt: clock()),
            Member(agentId: targetId, agentName: target.name, role: "member", joinedAt: clock()),
        ]
        messages[channel.id] = []
        persist()
        return channelJson(channel, viewerId: callerId)
    }

    private func createChannel(_ args: [String: Any], callerId: String, callerName: String) throws -> [String: Any] {
        let name = try reqText(args, "name", 100)
        let channel = Channel(id: newId(), name: name, kind: "channel", archived: false, historyVisible: true, createdAt: clock())
        channels[channel.id] = channel
        channelOrder.append(channel.id)
        members[channel.id] = [Member(agentId: callerId, agentName: callerName, role: "owner", joinedAt: clock())]
        messages[channel.id] = []
        persist()
        return channelJson(channel, viewerId: callerId)
    }

    private func renameChannel(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        try requireMember(channel.id, callerId)
        channel.name = try reqText(args, "name", 100)
        persist()
        return channelJson(channel, viewerId: callerId)
    }

    private func setArchived(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        try requireMember(channel.id, callerId)
        channel.archived = optBool(args, "archived", false)
        persist()
        return channelJson(channel, viewerId: callerId)
    }

    private func setHistoryVisible(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        try requireMember(channel.id, callerId)
        channel.historyVisible = optBool(args, "visible", true)
        persist()
        return channelJson(channel, viewerId: callerId)
    }

    private func deleteChannel(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        if memberOf(channel.id, callerId)?.role != "owner" {
            throw LocalServiceError("只有频道 owner 才能删除频道")
        }
        channels.removeValue(forKey: channel.id)
        channelOrder.removeAll { $0 == channel.id }
        members.removeValue(forKey: channel.id)
        let removed = messages.removeValue(forKey: channel.id) ?? []
        for message in removed { reactions.removeValue(forKey: message.id) }
        readMarkers = readMarkers.filter { !$0.key.hasPrefix(channel.id + "|") }
        webhooks.removeAll { $0.channelId == channel.id }
        persist()
        return ["deleted": true, "channel_id": channel.id]
    }

    private func addMember(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        let targetId = try reqText(args, "agent_id", 120)
        guard let target = agents[targetId] else {
            throw LocalServiceError("agent \(targetId) 不存在")
        }
        if members[channel.id] == nil { members[channel.id] = [] }
        if let existing = members[channel.id]?.first(where: { $0.agentId == targetId }) {
            existing.agentName = target.name
        } else {
            members[channel.id]?.append(
                Member(agentId: targetId, agentName: target.name, role: "member", joinedAt: clock()))
        }
        persist()
        return channelJson(channel, viewerId: callerId)
    }

    private func removeMember(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        let targetId = try reqText(args, "agent_id", 120)
        members[channel.id]?.removeAll { $0.agentId == targetId }
        persist()
        return channelJson(channel, viewerId: callerId)
    }

    private func setMemberRole(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        let memberId = try reqText(args, "member_id", 120)
        let role = try normalizeRole(optString(args, "role"))
        if memberOf(channel.id, callerId)?.role != "owner" {
            throw LocalServiceError("只有频道 owner 才能设置成员角色")
        }
        guard let target = memberOf(channel.id, memberId) else {
            throw LocalServiceError("目标不是该频道成员")
        }
        let previous = target.role
        if previous == "owner" && memberId != callerId {
            throw LocalServiceError("owner 的角色不能被他人更改")
        }
        if previous == "owner" && role != "owner" {
            let owners = (members[channel.id] ?? []).filter { $0.role == "owner" }.count
            if owners <= 1 {
                throw LocalServiceError("频道至少要保留一个 owner，最后一个 owner 不能降级")
            }
        }
        target.role = role
        persist()
        return [
            "channel_id": channel.id,
            "member_id": memberId,
            "role": role,
            "role_label": Self.roleLabels[role] ?? role,
            "previous_role": previous,
        ]
    }

    private func isPrivileged(_ role: String?) -> Bool {
        role == "owner" || role == "coordinator"
    }

    private func normalizeRole(_ raw: String) throws -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let role = Self.roleAliases[trimmed] ?? trimmed
        if !Self.roles.contains(role) {
            throw LocalServiceError("role 只能是 owner / coordinator(协调者) / member(成员)")
        }
        return role
    }

    // ---- 消息 ---------------------------------------------------------------

    private func messageJson(_ message: Message, viewerId: String) -> [String: Any] {
        var obj: [String: Any] = [
            "id": message.id,
            "channel_id": message.channelId,
            "agent_id": message.agentId,
            "agent_name": message.agentName,
            "text": message.recalled ? "" : message.text,
            "created_at": message.createdAt,
        ]
        if let replyTo = message.replyTo { obj["reply_to"] = replyTo }
        if message.mentionsAll { obj["mentions_all"] = true }
        if let ref = message.attachmentRef, !ref.isEmpty { obj["attachment_ref"] = ref }
        if let fwd = message.forwardOf, !fwd.isEmpty { obj["forward_of"] = fwd }
        if message.recalled { obj["recalled"] = true }
        if message.pinned { obj["pinned"] = true }
        if let byEmoji = reactions[message.id], !byEmoji.isEmpty {
            var array: [[String: Any]] = []
            for entry in byEmoji where !entry.agentIds.isEmpty {
                array.append([
                    "emoji": entry.emoji,
                    "count": entry.agentIds.count,
                    "mine": entry.agentIds.contains(viewerId),
                ])
            }
            if !array.isEmpty { obj["reactions"] = array }
        }
        return obj
    }

    private func postMessage(_ args: [String: Any], callerId: String, callerName: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        let text = try reqText(args, "text", Self.maxPostChars)
        let attachmentRaw = optString(args, "attachment_ref").trimmingCharacters(in: .whitespacesAndNewlines)
        let attachmentRef: String? = attachmentRaw.isEmpty ? nil : String(attachmentRaw.prefix(500))
        var replyTo: String? = nil
        let replyRaw = optString(args, "reply_to")
        if !replyRaw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            if replyRaw.utf16.count > 120 { throw LocalServiceError("reply_to 格式无效") }
            replyTo = replyRaw
        }
        let forwardRaw = optString(args, "forward_of").trimmingCharacters(in: .whitespacesAndNewlines)
        let forwardOf: String? = forwardRaw.isEmpty ? nil : forwardRaw
        let effectKey = optString(args, "effect_key").trimmingCharacters(in: .whitespacesAndNewlines)
        if !effectKey.isEmpty {
            if !Self.isValidEffectKey(effectKey) {
                throw LocalServiceError("effect_key 格式无效")
            }
            let priorId = effectKeys["\(channel.id)|\(callerId)|\(effectKey)"]
            if let priorId, let prior = (messages[channel.id] ?? []).first(where: { $0.id == priorId }) {
                return messageJson(prior, viewerId: callerId)
            }
        }
        // 发帖自动入群（与服务端同语义；入群时间只记第一次，
        // 历史不可见频道的截断线靠它，不能被发帖刷新）。
        if members[channel.id] == nil { members[channel.id] = [] }
        if let existing = members[channel.id]?.first(where: { $0.agentId == callerId }) {
            existing.agentName = callerName
        } else {
            members[channel.id]?.append(
                Member(agentId: callerId, agentName: callerName, role: "member", joinedAt: clock()))
        }
        let role = memberOf(channel.id, callerId)?.role
        seqCounter += 1
        let message = Message(
            id: newId(),
            channelId: channel.id,
            agentId: callerId,
            agentName: callerName,
            text: text,
            replyTo: replyTo,
            createdAt: clock(),
            seq: seqCounter,
            mentionsAll: Self.detectMentionsAll(text) && isPrivileged(role),
            attachmentRef: attachmentRef,
            forwardOf: forwardOf
        )
        messages[channel.id, default: []].append(message)
        if !effectKey.isEmpty {
            effectKeys["\(channel.id)|\(callerId)|\(effectKey)"] = message.id
        }
        persist()
        let json = messageJson(message, viewerId: callerId)
        fireMessage(channelId: channel.id, messageJson: json)
        return json
    }

    private func listMessages(_ args: [String: Any], callerId: String) throws -> [[String: Any]] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        guard let me = memberOf(channel.id, callerId) else {
            throw LocalServiceError("你不是该频道成员，无法读取频道消息")
        }
        let limit = min(100, max(1, has(args, "limit") ? optInt(args, "limit", 20) : 20))
        let onlyUnread = optBool(args, "only_unread", false)
        let markRead = optBool(args, "mark_read", false)
        let pinnedOnly = optBool(args, "pinned_only", false)
        var pool = messages[channel.id] ?? []
        if !channel.historyVisible {
            pool = pool.filter { $0.createdAt >= me.joinedAt }
        }
        if pinnedOnly { pool = pool.filter { $0.pinned } }
        let sender = optString(args, "sender").trimmingCharacters(in: .whitespacesAndNewlines)
        if !sender.isEmpty {
            pool = pool.filter { $0.agentId == sender || $0.agentName == sender }
        }
        if has(args, "from_ts") {
            let from = optDouble(args, "from_ts", 0)
            pool = pool.filter { $0.createdAt >= from }
        }
        if has(args, "to_ts") {
            let to = optDouble(args, "to_ts", 0)
            pool = pool.filter { $0.createdAt < to }
        }
        let kind = optString(args, "kind").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if !kind.isEmpty {
            switch kind {
            case "link":
                pool = pool.filter { $0.text.contains("http") }
            case "image":
                pool = pool.filter { $0.attachmentRef.map { isImageRef($0) } ?? false }
            case "file":
                pool = pool.filter { $0.attachmentRef.map { !isImageRef($0) } ?? false }
            default:
                throw LocalServiceError("kind 只能是 image / file / link")
            }
        }
        let query = optString(args, "q").trimmingCharacters(in: .whitespacesAndNewlines)
        if !query.isEmpty {
            if query.utf16.count > 200 { throw LocalServiceError("q 不能超过 200 个字符") }
            pool = pool.filter { $0.text.contains(query) }
        }
        var afterCursor: Message? = nil
        if onlyUnread {
            if let markerId = readMarkers["\(channel.id)|\(callerId)"] {
                afterCursor = (messages[channel.id] ?? []).first { $0.id == markerId }
            }
        } else if !optString(args, "after_id").isEmpty {
            afterCursor = try cursorOf(channel.id, optString(args, "after_id"))
        }
        var beforeCursor: Message? = nil
        if !optString(args, "before_id").isEmpty {
            beforeCursor = try cursorOf(channel.id, optString(args, "before_id"))
        }
        if let after = afterCursor {
            pool = pool.filter { isAfter($0, after) }
        }
        if let before = beforeCursor {
            pool = pool.filter { isBefore($0, before) }
        }
        let ascending = beforeCursor == nil && (afterCursor != nil || has(args, "from_ts"))
        let sorted = pool.sorted(by: Self.messageOrder)
        let picked = ascending ? Array(sorted.prefix(limit)) : Array(sorted.suffix(limit))
        if (markRead || onlyUnread) && !picked.isEmpty {
            readMarkers["\(channel.id)|\(callerId)"] = picked.last!.id
            persist()
        }
        return picked.map { messageJson($0, viewerId: callerId) }
    }

    private func cursorOf(_ channelId: String, _ messageId: String) throws -> Message {
        guard let message = (messages[channelId] ?? []).first(where: { $0.id == messageId }) else {
            throw LocalServiceError("消息不存在")
        }
        return message
    }

    private func isAfter(_ message: Message, _ cursor: Message) -> Bool {
        message.createdAt > cursor.createdAt ||
            (message.createdAt == cursor.createdAt && message.seq > cursor.seq)
    }

    private func isBefore(_ message: Message, _ cursor: Message) -> Bool {
        message.createdAt < cursor.createdAt ||
            (message.createdAt == cursor.createdAt && message.seq < cursor.seq)
    }

    private func requireMessage(_ channelId: String, _ messageId: String) throws -> Message {
        guard let message = (messages[channelId] ?? []).first(where: { $0.id == messageId }) else {
            throw LocalServiceError("消息不存在")
        }
        return message
    }

    private func recallMessage(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        let message = try requireMessage(channel.id, reqText(args, "message_id", 120))
        if message.agentId != callerId {
            throw LocalServiceError("只能撤回自己发的消息")
        }
        message.recalled = true
        message.text = ""
        persist()
        return messageJson(message, viewerId: callerId)
    }

    private func deleteMessage(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        let message = try requireMessage(channel.id, reqText(args, "message_id", 120))
        let role = memberOf(channel.id, callerId)?.role
        if message.agentId != callerId && !isPrivileged(role) {
            throw LocalServiceError("只能删除自己发的消息，或由频道管理员删除")
        }
        messages[channel.id]?.removeAll { $0.id == message.id }
        reactions.removeValue(forKey: message.id)
        persist()
        return ["deleted": true, "message_id": message.id]
    }

    private func pinMessage(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        try requireMember(channel.id, callerId)
        let message = try requireMessage(channel.id, reqText(args, "message_id", 120))
        message.pinned = optBool(args, "pinned", false)
        persist()
        return messageJson(message, viewerId: callerId)
    }

    private func reactMessage(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        try requireMember(channel.id, callerId)
        let message = try requireMessage(channel.id, reqText(args, "message_id", 120))
        let emoji = optString(args, "emoji").trimmingCharacters(in: .whitespacesAndNewlines)
        if emoji.isEmpty || emoji.utf16.count > 16 {
            throw LocalServiceError("emoji 格式无效")
        }
        var byEmoji = reactions[message.id] ?? []
        if let idx = byEmoji.firstIndex(where: { $0.emoji == emoji }) {
            var ids = byEmoji[idx].agentIds
            if optBool(args, "on", true) {
                if !ids.contains(callerId) { ids.append(callerId) }
            } else {
                ids.removeAll { $0 == callerId }
            }
            if ids.isEmpty {
                byEmoji.remove(at: idx)
            } else {
                byEmoji[idx].agentIds = ids
            }
        } else if optBool(args, "on", true) {
            byEmoji.append((emoji: emoji, agentIds: [callerId]))
        }
        reactions[message.id] = byEmoji
        persist()
        return messageJson(message, viewerId: callerId)
    }

    private func channelReaders(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        try requireMember(channel.id, callerId)
        let message = try requireMessage(channel.id, reqText(args, "message_id", 120))
        var readers: [[String: Any]] = []
        for member in members[channel.id] ?? [] {
            var read = false
            if let markerId = readMarkers["\(channel.id)|\(member.agentId)"],
               let marker = (messages[channel.id] ?? []).first(where: { $0.id == markerId }) {
                read = !isBefore(marker, message)
            }
            readers.append([
                "agent_id": member.agentId,
                "agent_name": member.agentName,
                "read": read,
            ])
        }
        return [
            "channel_id": channel.id,
            "message_id": message.id,
            "readers": readers,
        ]
    }

    // ---- Webhook 记录（入帖端点随内网层开通；记录与权限语义先与服务端一致） ----

    private func requirePrivileged(_ channelId: String, _ callerId: String) throws {
        _ = try requireChannel(channelId)
        if !isPrivileged(memberOf(channelId, callerId)?.role) {
            throw LocalServiceError("只有频道 owner / 协调者才能管理 Webhook")
        }
    }

    private func createWebhook(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        try requirePrivileged(channel.id, callerId)
        let name = try reqText(args, "name", 100)
        let webhookId = "whk_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").prefix(16).lowercased()
        let secret = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
            + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        webhooks.append(Webhook(
            id: webhookId, channelId: channel.id, name: name,
            secretHash: sha256Hex(secret), createdBy: callerId, createdAt: clock()))
        persist()
        return [
            "webhook_id": webhookId,
            "channel_id": channel.id,
            "name": name,
            "secret": secret,
            "url_path": Self.webhookPathPrefix + webhookId,
            "signature": "请求头 X-Webhook-Signature: sha256=HMAC_SHA256(key=sha256_hex(secret), body)",
            "note": "secret 只在这一次返回，库里只存哈希，丢了只能删掉重建。本地模式的入帖端点随内网层（三层二期）开通。",
        ]
    }

    private func listWebhooks(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        try requirePrivileged(channel.id, callerId)
        var array: [[String: Any]] = []
        for webhook in webhooks.filter({ $0.channelId == channel.id }).sorted(by: { $0.createdAt < $1.createdAt }) {
            array.append([
                "webhook_id": webhook.id,
                "name": webhook.name,
                "created_by": webhook.createdBy,
                "created_at": webhook.createdAt,
                "url_path": Self.webhookPathPrefix + webhook.id,
            ])
        }
        return ["channel_id": channel.id, "webhooks": array]
    }

    private func deleteWebhook(_ args: [String: Any], callerId: String) throws -> [String: Any] {
        let channel = try requireChannel(reqText(args, "channel_id", 120))
        try requirePrivileged(channel.id, callerId)
        let webhookId = try reqText(args, "webhook_id", 120)
        webhooks.removeAll { $0.id == webhookId && $0.channelId == channel.id }
        persist()
        return ["deleted": true, "webhook_id": webhookId]
    }

    // ---- 工具函数 -------------------------------------------------------------

    /// 与服务端 _text 同口径：去空白、必填、长度上限；返回收拾后的文本。
    private func reqText(_ args: [String: Any], _ field: String, _ maxLen: Int) throws -> String {
        let value = optString(args, field).trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty { throw LocalServiceError("\(field) 不能为空") }
        if value.utf16.count > maxLen { throw LocalServiceError("\(field) 不能超过 \(maxLen) 个字符") }
        return value
    }

    private func has(_ args: [String: Any], _ key: String) -> Bool {
        guard let value = args[key] else { return false }
        return !(value is NSNull)
    }

    private func optString(_ args: [String: Any], _ key: String, _ def: String = "") -> String {
        if let value = args[key] as? String { return value }
        if let value = args[key] as? NSNumber { return value.stringValue }
        return def
    }

    private func optBool(_ args: [String: Any], _ key: String, _ def: Bool) -> Bool {
        if let value = args[key] as? Bool { return value }
        return def
    }

    private func optInt(_ args: [String: Any], _ key: String, _ def: Int) -> Int {
        if let value = args[key] as? NSNumber { return value.intValue }
        return def
    }

    private func optDouble(_ args: [String: Any], _ key: String, _ def: Double) -> Double {
        if let value = args[key] as? NSNumber { return value.doubleValue }
        return def
    }

    private func newId() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }

    private func isImageRef(_ ref: String) -> Bool {
        let lower = ref.lowercased()
        return Self.imageExts.contains { lower.hasSuffix($0) }
    }

    private func sha256Hex(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    // ---- 持久化（整表快照 JSON） ----------------------------------------------

    private func persist() {
        do {
            let data = try JSONSerialization.data(withJSONObject: snapshotJson())
            if let text = String(data: data, encoding: .utf8) {
                persistence.save(text)
            }
        } catch {
            // 落盘失败不拖垮当次操作；内存状态仍在，下次变更会再存。
        }
    }

    private func snapshotJson() -> [String: Any] {
        var root: [String: Any] = [:]
        root["version"] = 1
        root["default_channel_id"] = storedDefaultChannelId
        root["seq"] = seqCounter
        root["agents"] = agentOrder.compactMap { agents[$0] }.map {
            ["id": $0.id, "name": $0.name] as [String: Any]
        }
        root["channels"] = channelOrder.compactMap { channels[$0] }.map { channel in
            [
                "id": channel.id,
                "name": channel.name,
                "kind": channel.kind,
                "archived": channel.archived,
                "history_visible": channel.historyVisible,
                "created_at": channel.createdAt,
            ] as [String: Any]
        }
        var memberRows: [[String: Any]] = []
        for channelId in channelOrder {
            for member in members[channelId] ?? [] {
                memberRows.append([
                    "channel_id": channelId,
                    "agent_id": member.agentId,
                    "agent_name": member.agentName,
                    "role": member.role,
                    "joined_at": member.joinedAt,
                ])
            }
        }
        root["members"] = memberRows
        var messageRows: [[String: Any]] = []
        for channelId in channelOrder {
            for message in messages[channelId] ?? [] {
                var row: [String: Any] = [
                    "id": message.id,
                    "channel_id": message.channelId,
                    "agent_id": message.agentId,
                    "agent_name": message.agentName,
                    "text": message.text,
                    "created_at": message.createdAt,
                    "seq": message.seq,
                    "mentions_all": message.mentionsAll,
                    "pinned": message.pinned,
                    "recalled": message.recalled,
                ]
                if let replyTo = message.replyTo { row["reply_to"] = replyTo }
                if let ref = message.attachmentRef { row["attachment_ref"] = ref }
                if let fwd = message.forwardOf { row["forward_of"] = fwd }
                messageRows.append(row)
            }
        }
        root["messages"] = messageRows
        var reactionRows: [[String: Any]] = []
        for (messageId, byEmoji) in reactions {
            for entry in byEmoji {
                for agentId in entry.agentIds {
                    reactionRows.append([
                        "message_id": messageId,
                        "emoji": entry.emoji,
                        "agent_id": agentId,
                    ])
                }
            }
        }
        root["reactions"] = reactionRows
        var markerRows: [[String: Any]] = []
        for (key, messageId) in readMarkers {
            let parts = key.split(separator: "|", maxSplits: 1)
            if parts.count != 2 { continue }
            markerRows.append([
                "channel_id": String(parts[0]),
                "agent_id": String(parts[1]),
                "message_id": messageId,
            ])
        }
        root["read_markers"] = markerRows
        root["webhooks"] = webhooks.map { webhook in
            [
                "id": webhook.id,
                "channel_id": webhook.channelId,
                "name": webhook.name,
                "secret_hash": webhook.secretHash,
                "created_by": webhook.createdBy,
                "created_at": webhook.createdAt,
            ] as [String: Any]
        }
        root["effect_keys"] = effectKeys.map { ["key": $0.key, "message_id": $0.value] as [String: Any] }
        return root
    }

    private func restore() {
        guard let raw = persistence.load(),
              let data = raw.data(using: .utf8),
              let parsed = try? JSONSerialization.jsonObject(with: data),
              let root = parsed as? [String: Any] else {
            return
        }
        storedDefaultChannelId = optString(root, "default_channel_id")
        seqCounter = Int64(optInt(root, "seq", 0))
        for row in (root["agents"] as? [[String: Any]]) ?? [] {
            let id = optString(row, "id")
            if id.isEmpty { continue }
            agents[id] = Agent(id: id, name: optString(row, "name", id))
            agentOrder.append(id)
        }
        for row in (root["channels"] as? [[String: Any]]) ?? [] {
            let id = optString(row, "id")
            if id.isEmpty { continue }
            let kind = optString(row, "kind", "channel")
            channels[id] = Channel(
                id: id,
                name: optString(row, "name"),
                kind: kind.isEmpty ? "channel" : kind,
                archived: optBool(row, "archived", false),
                historyVisible: optBool(row, "history_visible", true),
                createdAt: optDouble(row, "created_at", 0)
            )
            channelOrder.append(id)
            members[id] = []
            messages[id] = []
        }
        for row in (root["members"] as? [[String: Any]]) ?? [] {
            let channelId = optString(row, "channel_id")
            let agentId = optString(row, "agent_id")
            if channelId.isEmpty || agentId.isEmpty || channels[channelId] == nil { continue }
            let role = optString(row, "role", "member")
            members[channelId, default: []].append(Member(
                agentId: agentId,
                agentName: optString(row, "agent_name", agentId),
                role: role.isEmpty ? "member" : role,
                joinedAt: optDouble(row, "joined_at", 0)
            ))
        }
        for row in (root["messages"] as? [[String: Any]]) ?? [] {
            let channelId = optString(row, "channel_id")
            let id = optString(row, "id")
            if channelId.isEmpty || id.isEmpty || channels[channelId] == nil { continue }
            let replyTo = optString(row, "reply_to")
            let attachmentRef = optString(row, "attachment_ref")
            let forwardOf = optString(row, "forward_of")
            messages[channelId, default: []].append(Message(
                id: id,
                channelId: channelId,
                agentId: optString(row, "agent_id"),
                agentName: optString(row, "agent_name"),
                text: optString(row, "text"),
                replyTo: replyTo.isEmpty ? nil : replyTo,
                createdAt: optDouble(row, "created_at", 0),
                seq: Int64(optInt(row, "seq", 0)),
                mentionsAll: optBool(row, "mentions_all", false),
                attachmentRef: attachmentRef.isEmpty ? nil : attachmentRef,
                forwardOf: forwardOf.isEmpty ? nil : forwardOf,
                pinned: optBool(row, "pinned", false),
                recalled: optBool(row, "recalled", false)
            ))
        }
        for row in (root["reactions"] as? [[String: Any]]) ?? [] {
            let messageId = optString(row, "message_id")
            let emoji = optString(row, "emoji")
            let agentId = optString(row, "agent_id")
            if messageId.isEmpty || emoji.isEmpty || agentId.isEmpty { continue }
            var byEmoji = reactions[messageId] ?? []
            if let idx = byEmoji.firstIndex(where: { $0.emoji == emoji }) {
                if !byEmoji[idx].agentIds.contains(agentId) {
                    byEmoji[idx].agentIds.append(agentId)
                }
            } else {
                byEmoji.append((emoji: emoji, agentIds: [agentId]))
            }
            reactions[messageId] = byEmoji
        }
        for row in (root["read_markers"] as? [[String: Any]]) ?? [] {
            let channelId = optString(row, "channel_id")
            let agentId = optString(row, "agent_id")
            let messageId = optString(row, "message_id")
            if channelId.isEmpty || agentId.isEmpty || messageId.isEmpty { continue }
            readMarkers["\(channelId)|\(agentId)"] = messageId
        }
        for row in (root["webhooks"] as? [[String: Any]]) ?? [] {
            let id = optString(row, "id")
            if id.isEmpty { continue }
            webhooks.append(Webhook(
                id: id,
                channelId: optString(row, "channel_id"),
                name: optString(row, "name"),
                secretHash: optString(row, "secret_hash"),
                createdBy: optString(row, "created_by"),
                createdAt: optDouble(row, "created_at", 0)
            ))
        }
        for row in (root["effect_keys"] as? [[String: Any]]) ?? [] {
            let key = optString(row, "key")
            let messageId = optString(row, "message_id")
            if key.isEmpty || messageId.isEmpty { continue }
            effectKeys[key] = messageId
        }
    }

    // ---- 常量 ---------------------------------------------------------------

    public static let defaultChannelName = "本地频道"
    public static let maxPostChars = 4000
    public static let webhookPathPrefix = "/api/open/v1/channel-webhooks/"

    private static let roles: Set<String> = ["owner", "coordinator", "member"]
    private static let roleLabels: [String: String] = [
        "owner": "频道主",
        "coordinator": "协调者",
        "member": "成员",
    ]
    private static let roleAliases: [String: String] = [
        "频道主": "owner",
        "协调者": "coordinator",
        "成员": "member",
    ]
    private static let imageExts: [String] = [
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".bmp", ".svg",
    ]
    private static let messageOrder: (Message, Message) -> Bool = { a, b in
        if a.createdAt != b.createdAt { return a.createdAt < b.createdAt }
        return a.seq < b.seq
    }
    private static let effectKeyRegex = try! NSRegularExpression(
        pattern: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$")
    private static let atAllCodeRegex = try! NSRegularExpression(
        pattern: "```.*?(?:```|\\z)|`[^`\\n]*`",
        options: [.dotMatchesLineSeparators])
    private static let atAllRegex = try! NSRegularExpression(
        pattern: "(?<![A-Za-z0-9_.+\\-/@])@(?:all(?![A-Za-z0-9_]|[.\\-][A-Za-z0-9])|所有人)")

    private static func isValidEffectKey(_ key: String) -> Bool {
        let range = NSRange(key.startIndex..<key.endIndex, in: key)
        guard let match = effectKeyRegex.firstMatch(in: key, range: range) else { return false }
        return match.range.location == 0 && match.range.length == range.length
    }

    /// @所有人 检测（与服务端同规则：先剥代码段，大小写敏感）。
    public static func detectMentionsAll(_ text: String) -> Bool {
        let full = NSRange(text.startIndex..<text.endIndex, in: text)
        let stripped = atAllCodeRegex.stringByReplacingMatches(
            in: text, range: full, withTemplate: " ")
        let range = NSRange(stripped.startIndex..<stripped.endIndex, in: stripped)
        return atAllRegex.firstMatch(in: stripped, range: range) != nil
    }
}
