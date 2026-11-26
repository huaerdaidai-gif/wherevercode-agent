import Foundation

// P3-MSG 消息中心聚合规则（iOS 端）。与 Android 端
// core/message/MessageCenterRules.kt 同口径；读语义（only_unread /
// markRead=false，绝不推进服务端已读标记）由 MessageCenterTests.swift
// 的 StubTransport 逐调用锁定，判定规则详见下方法注释。

/// 消息中心三个聚合区。
public enum MessageCenterSection: String, CaseIterable, Sendable {
    /// @我：正文里以 @<Name> 提及本机身份（平台寻址惯例就是正文前缀）。
    case mention
    /// 未读：用既有读接口 only_unread 拉回、且尚未被频道页消费的消息。
    case unread
    /// 任务回执：带任务归属头（任务卡状态）的消息。
    case taskReceipt
    /// 待落地任务：已建单但尚未绑定频道的任务（9.19）。
    case pendingTask
}

/// 待落地任务条目（9.19）：来自本地任务账本，不是频道消息。
public struct PendingTaskEntry: Identifiable, Equatable, Sendable {
    public var id: String { taskId }
    public let taskId: String
    public let title: String
    public let goal: String
    public let assigneeName: String
    public let stateLabel: String
    public let createdAtSeconds: Double
    public init(taskId: String, title: String, goal: String, assigneeName: String,
                stateLabel: String, createdAtSeconds: Double) {
        self.taskId = taskId
        self.title = title
        self.goal = goal
        self.assigneeName = assigneeName
        self.stateLabel = stateLabel
        self.createdAtSeconds = createdAtSeconds
    }
}

/// 消息中心的一条聚合条目（同一条消息可同时出现在多个区）。
public struct MessageCenterEntry: Identifiable, Equatable, Sendable {
    public var id: String { "\(section.rawValue):\(channelId):\(messageId)" }
    public let section: MessageCenterSection
    public let channelId: String
    public let channelName: String
    public let messageId: String
    public let senderName: String
    /// 展示正文：已剥归属头。
    public let text: String
    public let createdAtSeconds: Double
    /// @我 命中的本机别名。
    public let matchedAlias: String?
    /// 任务回执：归属头里的任务短 id（与 Android 端同口径）。
    public let taskId: String?
    /// 任务回执：归属头里的任务标题。
    public let taskTitle: String?

    public init(
        section: MessageCenterSection,
        channelId: String,
        channelName: String,
        messageId: String,
        senderName: String,
        text: String,
        createdAtSeconds: Double,
        matchedAlias: String? = nil,
        taskId: String? = nil,
        taskTitle: String? = nil
    ) {
        self.section = section
        self.channelId = channelId
        self.channelName = channelName
        self.messageId = messageId
        self.senderName = senderName
        self.text = text
        self.createdAtSeconds = createdAtSeconds
        self.matchedAlias = matchedAlias
        self.taskId = taskId
        self.taskTitle = taskTitle
    }
}

/// 一个待聚合的频道（id + 显示名）。
public struct MessageCenterChannelSource: Sendable {
    public let channelId: String
    public let channelName: String
    public init(channelId: String, channelName: String) {
        self.channelId = channelId
        self.channelName = channelName
    }
}

/// 消息中心聚合快照：各区一份条目（新→旧排序）。
public struct MessageCenterSnapshot: Equatable, Sendable {
    public var mentions: [MessageCenterEntry]
    public var unread: [MessageCenterEntry]
    public var taskReceipts: [MessageCenterEntry]
    /// 待落地任务（9.19）：本地任务账本里未绑定频道的任务。
    public var pendingTasks: [PendingTaskEntry]
    public init(
        mentions: [MessageCenterEntry] = [],
        unread: [MessageCenterEntry] = [],
        taskReceipts: [MessageCenterEntry] = [],
        pendingTasks: [PendingTaskEntry] = []
    ) {
        self.mentions = mentions
        self.unread = unread
        self.taskReceipts = taskReceipts
        self.pendingTasks = pendingTasks
    }
}

/// 消息中心读取口（本包新增的最小协议，桥接既有 ChannelTransport，
/// 不改传输文件本身）。桩测试直接实现本协议锁定读语义。
public protocol MessageCenterTransport {
    /// 与 ChannelTransport.pollMessages 同名参数同语义。
    func pollMessages(
        channelID: String,
        afterID: String?,
        limit: Int,
        onlyUnread: Bool,
        markRead: Bool
    ) async throws -> [ChannelMessage]
}

/// 既有 ChannelTransport 的适配器：参数原样透传，不加不减。
public struct ChannelTransportMessageCenterAdapter: MessageCenterTransport {
    public let transport: ChannelTransport
    public init(transport: ChannelTransport) {
        self.transport = transport
    }

    public func pollMessages(
        channelID: String,
        afterID: String?,
        limit: Int,
        onlyUnread: Bool,
        markRead: Bool
    ) async throws -> [ChannelMessage] {
        try await transport.pollMessages(
            channelID: channelID,
            afterID: afterID,
            limit: limit,
            onlyUnread: onlyUnread,
            markRead: markRead
        )
    }
}

/**
 * 消息中心聚合规则（纯逻辑，XCTest 直接覆盖）。
 *
 * 判定口径（与 Android 端 MessageCenterRules 一致，苹果为准同规则）：
 * - @我：平台寻址无收件方字段，收件方只能写在正文前缀（`@<Name> `），
 *   见 ChannelTransport 头注释。因此 @我 = 正文（剥归属头后）含
 *   `@<别名>`、别名等于本机身份别名集合之一；边界按“后随字符不是
 *   英文字母/数字”判定，单字中文别名（如“我”）后随汉字不算命中
 *   （“@我们”≠“@我”），多字别名（如“协调者”）后随汉字算命中。
 * - 任务回执：归属头解析与 Android TeamChannel.parseTeamAttribution
 *   同协议（新【】格式 + 旧 [] 格式）——头里带任务短 id 即任务回执，
 *   不另造协议。
 * - 未读：只认 loader 用 only_unread=true、mark_read=false 拉回的那一页
 *   （mark_read=false 绝不推进服务端已读标记，频道页的未读状态不会被
 *   消息中心误消费——已读语义见 MessageCenterLoader 注释）。
 */
public enum MessageCenterRules {
    /// 每区最多呈现条数（一页读回上限，超出如实截断并计数）。
    public static let maxPerSection = 20

    /// 归属头解析结果（与 Android TeamAttribution 同构）。
    public struct TeamAttribution: Equatable {
        public let senderName: String
        public let taskId: String?
        public let taskTitle: String?
    }

    /// 正文里命中的本机别名；未命中返回 nil（边界规则见头注释）。
    public static func mentionAlias(in text: String, selfNames: [String]) -> String? {
        let aliases = selfNames
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .enumerated()
            .reduce(into: [String]()) { seen, pair in
                let lowered = pair.element.lowercased()
                if !seen.contains(where: { $0.lowercased() == lowered }) {
                    seen.append(pair.element)
                }
            }
            .sorted { $0.count > $1.count }
        guard !aliases.isEmpty else { return nil }
        let chars = Array(text)
        var i = 0
        while let at = chars[i...].firstIndex(of: "@") {
            let prev = at > 0 ? chars[at - 1] : nil
            // 前随英文字母/数字视为词内 @（如邮箱、串 id），不算提及。
            let prevBlocking = prev.map { ($0.isLetter || $0.isNumber) && !isCjk($0) } ?? false
            if !prevBlocking {
                for alias in aliases {
                    let aliasChars = Array(alias)
                    let end = at + 1 + aliasChars.count
                    guard end <= chars.count else { continue }
                    guard Array(chars[(at + 1)..<end]).elementsEqual(
                        aliasChars,
                        by: { $0.lowercased() == $1.lowercased() }
                    ) else { continue }
                    let next = end < chars.count ? chars[end] : nil
                    let latinContinuation = next.map { ($0.isLetter || $0.isNumber) && !isCjk($0) } ?? false
                    let cjkContinuation = aliasChars.count == 1 && isCjk(aliasChars[0]) &&
                        next.map(isCjk) == true
                    if !latinContinuation && !cjkContinuation { return alias }
                }
            }
            i = at + 1
            if i >= chars.count { break }
        }
        return nil
    }

    static func isCjk(_ c: Character) -> Bool {
        guard let scalar = c.unicodeScalars.first, c.unicodeScalars.count == 1 else { return false }
        switch scalar.value {
        case 0x4E00...0x9FFF, 0x3400...0x4DBF, 0x20000...0x2A6DF, 0xF900...0xFAFF: return true
        default: return false
        }
    }

    /// 任务回执判定：归属头带任务短 id 即回执（无头或无任务返回 nil）。
    /// 协议与 Android 端 parseTeamAttribution 一致：
    /// - 新格式：`【发送者 · 任务 <短id> · <标题>】` 或 `【发送者】`；
    /// - 旧格式：`[发送者] 正文`（无任务）。
    public static func teamAttribution(in text: String) -> TeamAttribution? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("【") {
            guard let end = trimmed.firstIndex(of: "】"), end > trimmed.index(after: trimmed.startIndex) else {
                return nil
            }
            let inner = String(trimmed[trimmed.index(after: trimmed.startIndex)..<end])
            // 与 Android 端 split(" · ", limit = 3) 同语义：标题里可再含 " · "。
            let rawParts = inner.components(separatedBy: " · ")
            let parts: [String] = rawParts.count > 3
                ? [rawParts[0], rawParts[1], rawParts[2...].joined(separator: " · ")]
                : rawParts
            guard let sender = parts.first.map({ $0.trimmingCharacters(in: .whitespaces) }),
                  !sender.isEmpty else { return nil }
            guard parts.count > 1 else { return TeamAttribution(senderName: sender, taskId: nil, taskTitle: nil) }
            let taskPart = parts[1].trimmingCharacters(in: .whitespaces)
            guard taskPart.hasPrefix("任务 ") else { return nil }
            let taskId = taskPart.dropFirst("任务 ".count)
                .trimmingCharacters(in: .whitespaces)
            guard !taskId.isEmpty else { return nil }
            let title = parts.count > 2
                ? parts[2].trimmingCharacters(in: .whitespaces)
                : nil
            return TeamAttribution(
                senderName: sender,
                taskId: taskId.isEmpty ? nil : taskId,
                taskTitle: (title?.isEmpty == false) ? title : nil
            )
        }
        if trimmed.hasPrefix("[") {
            guard let end = trimmed.firstIndex(of: "]"), end > trimmed.index(after: trimmed.startIndex) else {
                return nil
            }
            let sender = String(trimmed[trimmed.index(after: trimmed.startIndex)..<end])
                .trimmingCharacters(in: .whitespaces)
            guard !sender.isEmpty else { return nil }
            return TeamAttribution(senderName: sender, taskId: nil, taskTitle: nil)
        }
        return nil
    }

    /// 条目展示正文：剥掉归属头（无头原样返回）。
    public static func displayText(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("【"), let end = trimmed.firstIndex(of: "】") {
            return String(trimmed[trimmed.index(after: end)...]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if trimmed.hasPrefix("["), let end = trimmed.firstIndex(of: "]") {
            return String(trimmed[trimmed.index(after: end)...]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return text
    }

    /// 条目发送者：归属头署名优先（中转回复的头才是真发声者），否则用传输自带的 agentName。
    static func senderName(_ message: ChannelMessage, attribution: TeamAttribution?) -> String {
        if let name = attribution?.senderName, !name.isEmpty { return name }
        return message.displaySender
    }

    /// 把一个频道的两页读回聚合成本区的三类条目（纯函数，不写已读标记）。
    public static func buildEntries(
        channelId: String,
        channelName: String,
        recent: [ChannelMessage],
        unread: [ChannelMessage],
        selfNames: [String]
    ) -> MessageCenterSnapshot {
        var mentions: [MessageCenterEntry] = []
        var receipts: [MessageCenterEntry] = []
        var seenMention = Set<String>()
        var seenReceipt = Set<String>()
        for message in recent {
            let attribution = teamAttribution(in: message.text)
            let body = displayText(message.text)
            if let alias = mentionAlias(in: body, selfNames: selfNames), seenMention.insert(message.id).inserted {
                mentions.append(MessageCenterEntry(
                    section: .mention,
                    channelId: channelId,
                    channelName: channelName,
                    messageId: message.id,
                    senderName: senderName(message, attribution: attribution),
                    text: body,
                    createdAtSeconds: message.createdAtSeconds,
                    matchedAlias: alias
                ))
            }
            if let task = teamAttribution(in: message.text), task.taskId != nil,
               seenReceipt.insert(message.id).inserted {
                receipts.append(MessageCenterEntry(
                    section: .taskReceipt,
                    channelId: channelId,
                    channelName: channelName,
                    messageId: message.id,
                    senderName: senderName(message, attribution: attribution),
                    text: body,
                    createdAtSeconds: message.createdAtSeconds,
                    taskId: task.taskId,
                    taskTitle: task.taskTitle
                ))
            }
        }
        var unreadEntries: [MessageCenterEntry] = []
        var seenUnread = Set<String>()
        for message in unread {
            guard seenUnread.insert(message.id).inserted else { continue }
            let attribution = teamAttribution(in: message.text)
            unreadEntries.append(MessageCenterEntry(
                section: .unread,
                channelId: channelId,
                channelName: channelName,
                messageId: message.id,
                senderName: senderName(message, attribution: attribution),
                text: displayText(message.text),
                createdAtSeconds: message.createdAtSeconds
            ))
        }
        func cap(_ list: [MessageCenterEntry]) -> [MessageCenterEntry] {
            Array(list.sorted { $0.createdAtSeconds > $1.createdAtSeconds }.prefix(maxPerSection))
        }
        return MessageCenterSnapshot(
            mentions: cap(mentions),
            unread: cap(unreadEntries),
            taskReceipts: cap(receipts)
        )
    }

    /// 合并多个频道的聚合结果（区内按新→旧排序、截断到 maxPerSection）。
    public static func merge(_ snapshots: [MessageCenterSnapshot]) -> MessageCenterSnapshot {
        func cap(_ list: [MessageCenterEntry]) -> [MessageCenterEntry] {
            var seen = Set<String>()
            let deduped = list.filter { seen.insert("\($0.section.rawValue)|\($0.messageId)").inserted }
            return Array(deduped.sorted { $0.createdAtSeconds > $1.createdAtSeconds }.prefix(maxPerSection))
        }
        return MessageCenterSnapshot(
            mentions: cap(snapshots.flatMap(\.mentions)),
            unread: cap(snapshots.flatMap(\.unread)),
            taskReceipts: cap(snapshots.flatMap(\.taskReceipts))
        )
    }
}

/**
 * 消息中心的读取管道：只用既有读接口（ChannelTransport.pollMessages），
 * 绝不动传输层。
 *
 * 已读语义（必须与桩传输测试一起读）：
 * - @我 / 任务回执 区：`pollMessages(channelID, nil, 20, onlyUnread: false,
 *   markRead: false)`——普通读最新一页，不碰已读标记。
 * - 未读 区：`pollMessages(channelID, nil, 20, onlyUnread: true,
 *   markRead: false)`——与服务端已读标记联动（读到哪标到哪）取“已读
 *   标记之后”的消息；markRead=false 意味着本调用不推进服务端已读标记，
 *   频道页的未读状态不会被消息中心误消费。唯一允许推已读的是频道页
 *   自己（用户真正浏览频道时的 markRead 路径），消息中心不做。
 */
public final class MessageCenterLoader: @unchecked Sendable {
    /// 每频道每页读回条数（与传输默认页宽一致）。
    public let pageLimit: Int
    private let transport: MessageCenterTransport

    public init(transport: MessageCenterTransport, pageLimit: Int = 20) {
        self.transport = transport
        self.pageLimit = pageLimit
    }

    /// 逐频道拉取并聚合。任一频道读失败即抛出（调用方如实呈现，绝不假装成功）。
    public func fetch(
        channels: [MessageCenterChannelSource],
        selfNames: [String]
    ) async throws -> MessageCenterSnapshot {
        var parts: [MessageCenterSnapshot] = []
        for channel in channels {
            let recent = try await transport.pollMessages(
                channelID: channel.channelId,
                afterID: nil,
                limit: pageLimit,
                onlyUnread: false,
                markRead: false
            )
            let unread = try await transport.pollMessages(
                channelID: channel.channelId,
                afterID: nil,
                limit: pageLimit,
                onlyUnread: true,
                markRead: false
            )
            parts.append(MessageCenterRules.buildEntries(
                channelId: channel.channelId,
                channelName: channel.channelName,
                recent: recent,
                unread: unread,
                selfNames: selfNames
            ))
        }
        return MessageCenterRules.merge(parts)
    }
}
