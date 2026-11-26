package wherevercode.agent.core.message

import wherevercode.agent.core.agent.PlatformChannelMessage
import wherevercode.agent.core.agent.PlatformChannelTransport
import wherevercode.agent.core.team.TeamAttribution
import wherevercode.agent.core.team.parseTeamAttribution
import wherevercode.agent.core.team.stripTeamAttributionHeader

/** 消息中心三个聚合区。 */
enum class MessageCenterSection {
    /** @我：正文里以 @<Name> 提及本机身份（平台寻址惯例就是正文前缀）。 */
    MENTION,

    /** 未读：用既有读接口 only_unread 拉回、且尚未被频道页消费的消息。 */
    UNREAD,

    /** 任务回执：带任务归属头（任务卡状态）的消息。 */
    TASK_RECEIPT,

    /** 待落地任务：已建单但尚未绑定频道的任务（9.19）。 */
    PENDING_TASK,
}

/** 待落地任务条目（9.19）：来自本地任务账本，不是频道消息。 */
data class PendingTaskEntry(
    val taskId: String,
    val title: String,
    val goal: String,
    val assigneeName: String,
    val stateLabel: String,
    val createdAtSeconds: Double,
)

/** 消息中心的一条聚合条目（同一条消息可同时出现在多个区）。 */
data class MessageCenterEntry(
    val section: MessageCenterSection,
    val channelId: String,
    val channelName: String,
    val messageId: String,
    val senderName: String,
    /** 展示正文：已剥归属头。 */
    val text: String,
    val createdAtSeconds: Double,
    /** @我 命中的本机别名。 */
    val matchedAlias: String? = null,
    /** 任务回执：归属头里的任务短 id（前 8 位，与频道页串 id 同口径）。 */
    val taskId: String? = null,
    /** 任务回执：归属头里的任务标题。 */
    val taskTitle: String? = null,
)

/** 一个待聚合的频道（id + 显示名）。 */
data class MessageCenterChannelSource(val channelId: String, val channelName: String)

/** 消息中心聚合快照：各区一份条目（新→旧排序）。 */
data class MessageCenterSnapshot(
    val mentions: List<MessageCenterEntry> = emptyList(),
    val unread: List<MessageCenterEntry> = emptyList(),
    val taskReceipts: List<MessageCenterEntry> = emptyList(),
    /** 待落地任务（9.19）：本地任务账本里未绑定频道的任务。 */
    val pendingTasks: List<PendingTaskEntry> = emptyList(),
)

/**
 * 消息中心聚合规则（纯函数，无 Android 依赖）。
 *
 * 判定口径（与基线注释一致，2026-11-11 实测的传输参数口径）：
 * - @我：平台寻址无收件方字段，收件方只能写在正文前缀（`@<Name> `），
 *   见 PlatformChannelTransport 头注释。因此 @我 = 正文（剥归属头后）
 *   含 `@<别名>`、别名等于本机身份别名集合之一；别名边界按“后随字符
 *   不是英文字母/数字”判定（“@我们”不算命中“我”，中文无词边界概念）。
 * - 任务回执：复用基线已有的 [parseTeamAttribution] 归属头解析——
 *   头里带任务短 id 即任务回执，不另造协议。
 * - 未读：只认 [MessageCenterLoader] 用 only_unread=true、mark_read=false
 *   拉回的那一页（mark_read=false 绝不推进服务端已读标记，频道页的
 *   未读状态不会被消息中心误消费——已读语义见该类注释）。
 */
object MessageCenterRules {
    /** 每区最多呈现条数（一页读回上限，超出如实截断并计数）。 */
    const val MAX_PER_SECTION = 20

    /**
     * 正文里命中的本机别名；未命中返回 null。
     * [selfNames] 传本机身份别名（如 “本机 App”“协调者”“我”）与本机
     * Agent 显示名（提及本机 Agent 也算 @我，调用方组装）。
     * 边界规则：@ 后按最长别名优先匹配；
     * - 后随拉丁字母/数字不算命中（“@我们”不因前缀“我”命中，“abc@x”是词内 @）；
     * - 单字中文别名（如“我”）后随汉字不算命中（“@我们”≠“@我”），
     *   多字别名（如“协调者”）后随汉字算命中（“@协调者一下”是有效提及）。
     */
    fun mentionAlias(text: String, selfNames: Collection<String>): String? {
        if (selfNames.isEmpty()) return null
        val aliases = selfNames.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sortedByDescending { it.length }
        if (aliases.isEmpty()) return null
        var i = text.indexOf('@')
        while (i >= 0) {
            val prev = text.getOrNull(i - 1)
            // 前随英文字母/数字视为词内 @（如邮箱、串 id），不算提及。
            if (prev == null || !prev.isLetterOrDigit() || isCjk(prev)) {
                for (alias in aliases) {
                    val end = i + 1 + alias.length
                    if (end > text.length) continue
                    if (!text.regionMatches(i + 1, alias, 0, alias.length, ignoreCase = true)) continue
                    val next = text.getOrNull(end)
                    val latinContinuation = next != null && next.isLetterOrDigit() && !isCjk(next)
                    val cjkContinuation = alias.length == 1 && isCjk(alias.first()) &&
                        next != null && isCjk(next)
                    if (!latinContinuation && !cjkContinuation) return alias
                }
            }
            i = text.indexOf('@', i + 1)
        }
        return null
    }

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c) ?: return false
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
    }

    /** 任务回执判定：归属头带任务短 id 即回执（无头或无任务返回 null）。 */
    fun taskAttribution(text: String): TeamAttribution? =
        parseTeamAttribution(text)?.takeIf { !it.taskId.isNullOrBlank() }

    /** 条目展示正文：剥掉归属头。 */
    fun displayText(text: String): String = stripTeamAttributionHeader(text).trim()

    /** 条目发送者：归属头署名优先（中转回复的头才是真发声者），否则用传输自带的 agent_name。 */
    fun senderName(message: PlatformChannelMessage, attribution: TeamAttribution?): String =
        attribution?.senderName?.takeIf { it.isNotBlank() } ?: message.agentName

    /**
     * 把一个频道的两页读回（recent = 普通读最新一页，unread = only_unread
     * 一页）聚合成本区的三类条目。纯函数：不做任何已读标记写入。
     */
    fun buildEntries(
        channelId: String,
        channelName: String,
        recent: List<PlatformChannelMessage>,
        unread: List<PlatformChannelMessage>,
        selfNames: Collection<String>,
    ): MessageCenterSnapshot {
        val mentions = mutableListOf<MessageCenterEntry>()
        val receipts = mutableListOf<MessageCenterEntry>()
        val seenMention = HashSet<String>()
        val seenReceipt = HashSet<String>()
        for (message in recent) {
            val attribution = parseTeamAttribution(message.text)
            val body = displayText(message.text)
            val alias = mentionAlias(body, selfNames)
            if (alias != null && seenMention.add(message.id)) {
                mentions += MessageCenterEntry(
                    section = MessageCenterSection.MENTION,
                    channelId = channelId,
                    channelName = channelName,
                    messageId = message.id,
                    senderName = senderName(message, attribution),
                    text = body,
                    createdAtSeconds = message.createdAtSeconds,
                    matchedAlias = alias,
                )
            }
            val task = taskAttribution(message.text)
            if (task != null && seenReceipt.add(message.id)) {
                receipts += MessageCenterEntry(
                    section = MessageCenterSection.TASK_RECEIPT,
                    channelId = channelId,
                    channelName = channelName,
                    messageId = message.id,
                    senderName = senderName(message, attribution),
                    text = body,
                    createdAtSeconds = message.createdAtSeconds,
                    taskId = task.taskId,
                    taskTitle = task.taskTitle,
                )
            }
        }
        val unreadEntries = mutableListOf<MessageCenterEntry>()
        val seenUnread = HashSet<String>()
        for (message in unread) {
            if (seenUnread.add(message.id)) {
                val attribution = parseTeamAttribution(message.text)
                unreadEntries += MessageCenterEntry(
                    section = MessageCenterSection.UNREAD,
                    channelId = channelId,
                    channelName = channelName,
                    messageId = message.id,
                    senderName = senderName(message, attribution),
                    text = displayText(message.text),
                    createdAtSeconds = message.createdAtSeconds,
                )
            }
        }
        fun cap(list: List<MessageCenterEntry>) = list
            .sortedByDescending { it.createdAtSeconds }
            .take(MAX_PER_SECTION)
        return MessageCenterSnapshot(
            mentions = cap(mentions),
            unread = cap(unreadEntries),
            taskReceipts = cap(receipts),
        )
    }

    /** 合并多个频道的聚合结果（区内按新→旧排序、截断到 [MAX_PER_SECTION]）。 */
    fun merge(snapshots: List<MessageCenterSnapshot>): MessageCenterSnapshot {
        fun cap(list: List<MessageCenterEntry>) = list
            .distinctBy { it.section to it.messageId }
            .sortedByDescending { it.createdAtSeconds }
            .take(MAX_PER_SECTION)
        return MessageCenterSnapshot(
            mentions = cap(snapshots.flatMap { it.mentions }),
            unread = cap(snapshots.flatMap { it.unread }),
            taskReceipts = cap(snapshots.flatMap { it.taskReceipts }),
        )
    }
}

/**
 * 消息中心的读取管道：只用既有读接口（pollMessages），绝不动传输层。
 *
 * 已读语义（必须与桩传输测试一起读）：
 * - @我 / 任务回执 区：`pollMessages(channelId, null, 20, onlyUnread=false,
 *   markRead=false)`——普通读最新一页，不碰已读标记。
 * - 未读 区：`pollMessages(channelId, null, 20, onlyUnread=true,
 *   markRead=false)`——与服务端已读标记联动（读到哪标到哪）取“已读标记
 *   之后”的消息；markRead=false 意味着本调用不推进服务端已读标记，
 *   频道页的未读状态（其探针同样只用 only_unread 探测、用户真正浏览
 *   频道页时才 mark_read）不会被消息中心误消费。
 * - 本类永远不发起 markRead=true 的调用；唯一允许推已读的是频道页
 *   自己（TeamChannelController 的 onMarkRead 路径），消息中心不做。
 */
class MessageCenterLoader(private val transport: PlatformChannelTransport) {
    /** 每频道每页读回条数（与传输默认页宽一致）。 */
    val pageLimit: Int = 20

    /** 逐频道拉取并聚合。任一频道读失败即抛出（调用方如实呈现，绝不假装成功）。 */
    suspend fun fetch(
        channels: List<MessageCenterChannelSource>,
        selfNames: Collection<String>,
    ): MessageCenterSnapshot {
        val parts = mutableListOf<MessageCenterSnapshot>()
        for (channel in channels) {
            val recent = transport.pollMessages(
                channel.channelId, null, pageLimit, onlyUnread = false, markRead = false,
            )
            val unread = transport.pollMessages(
                channel.channelId, null, pageLimit, onlyUnread = true, markRead = false,
            )
            parts += MessageCenterRules.buildEntries(
                channel.channelId, channel.channelName, recent, unread, selfNames,
            )
        }
        return MessageCenterRules.merge(parts)
    }
}
