package wherevercode.agent.core.team

import wherevercode.agent.core.agent.ChannelReaction
import wherevercode.agent.core.agent.ChannelReader
import wherevercode.agent.core.agent.ChannelSummary
import wherevercode.agent.core.agent.PlatformChannelMessage

/**
 * 频道补全二/三/四期的纯逻辑（无 Android 依赖，单测直覆盖）：
 * 表情反应、置顶、回复话题串、转发标注、消息中心聚合、已读名单
 * 文案、私聊判定、定时发送队列与发帖幂等键（客户端口径：
 * 键在入口一次性铸造，同一键重复提交只生效一次；只是 `ck_`+nonce，
 * 不是 AWM KeyMint）。
 */
object ChannelExtras {

    /** 界面提供的快捷反应（与 iOS 同一组）。 */
    val REACTION_CHOICES: List<String> = listOf("👍", "❤️", "🎉", "👀")

    /**
     * 列表行时间规范（双端同一条规则，与 iOS 同实现）：今天 HH:mm；
     * 昨天「昨天」；近 7 天内（不含今天/昨天）星期几；更早 yyyy-MM-dd。
     * 无效时间返回空串——不显示任何编造的时间。
     */
    fun listTimeLabel(epochSeconds: Double, nowSeconds: Double): String {
        if (epochSeconds <= 0) return ""
        fun dayStart(seconds: Double): Long {
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = (seconds * 1000).toLong()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
        val dayMillis = 86_400_000L
        val diffDays = (dayStart(nowSeconds) - dayStart(epochSeconds)) / dayMillis
        val date = java.util.Date((epochSeconds * 1000).toLong())
        return when {
            diffDays <= 0L -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(date)
            diffDays == 1L -> "昨天"
            diffDays < 7L -> {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = (epochSeconds * 1000).toLong()
                listOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")[
                    cal.get(java.util.Calendar.DAY_OF_WEEK) - 1
                ]
            }
            else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(date)
        }
    }

    /** 撤回消息在界面上的占位文案（正文已被平台清空）。 */
    const val RECALLED_PLACEHOLDER: String = "此消息已撤回"

    /** 反应聚合行文案：“👍 2 · ❤️ 1”；无反应返回空串。 */
    fun reactionText(reactions: List<ChannelReaction>): String =
        reactions.filter { it.count > 0 }
            .joinToString(" · ") { "${it.emoji} ${it.count}" }

    /** 我是否已对这条消息点了某个反应。 */
    fun myReaction(reactions: List<ChannelReaction>, emoji: String): Boolean =
        reactions.any { it.emoji == emoji && it.mine }

    /** 置顶消息（保持原时间序）。 */
    fun pinnedMessages(messages: List<PlatformChannelMessage>): List<PlatformChannelMessage> =
        messages.filter { it.pinned && !it.recalled }

    /** 展示正文：撤回显示占位，其余原样。 */
    fun displayText(message: PlatformChannelMessage): String =
        if (message.recalled) RECALLED_PLACEHOLDER else message.text

    /**
     * 回复引用行（全量对齐批，与 iOS 主列表同式）：“｜回复：<正文摘录>”；
     * 父消息不在列表里返回 null。iOS 话题内的 ID 截断式引用行是其自身
     * 缺陷，不作对齐目标——双端统一用本式。
     */
    fun replyQuote(messages: List<PlatformChannelMessage>, message: PlatformChannelMessage): String? {
        val parentId = message.replyTo ?: return null
        val parent = messages.firstOrNull { it.id == parentId } ?: return null
        val excerpt = displayText(parent).replace("\n", " ").take(40)
        return "｜回复：$excerpt"
    }

    /**
     * 回复话题串：按 reply_to 链把回复挂到根消息下（根按时间序，
     * 串内按时间序，depth 为层级）。父消息缺失的回复自成根。
     * 与“按任务分串”（[groupTeamMessages]）并存：任务串管归属，
     * 本函数管自由回复串的阅读顺序。
     */
    fun replyThreadOrder(messages: List<PlatformChannelMessage>): List<Pair<PlatformChannelMessage, Int>> {
        val byId = messages.associateBy { it.id }
        val children = mutableMapOf<String, MutableList<PlatformChannelMessage>>()
        val roots = mutableListOf<PlatformChannelMessage>()
        for (m in messages.sortedBy { it.createdAtSeconds }) {
            val parent = m.replyTo?.let { byId[it] }
            if (parent == null || parent.id == m.id) roots += m
            else children.getOrPut(parent.id) { mutableListOf() } += m
        }
        val out = mutableListOf<Pair<PlatformChannelMessage, Int>>()
        fun walk(msg: PlatformChannelMessage, depth: Int) {
            out += msg to depth
            children[msg.id]?.forEach { walk(it, depth + 1) }
        }
        roots.forEach { walk(it, 0) }
        return out
    }

    /** 转发来源标注（平台 forward_of 字段的统一格式）。 */
    fun forwardOfLabel(sourceChannelName: String, senderName: String): String {
        val channel = sourceChannelName.trim().ifBlank { "频道" }
        val sender = senderName.trim().ifBlank { "未知" }
        return "$channel · $sender"
    }

    /**
     * 这条消息是否 @ 了给定名字之一。218 口径 B 与 iOS 同一定义：
     * 平台约定 “@名字＋空格”（或换行/文末）才算提及，避免名字是
     * 更长词前缀时误报。
     */
    fun mentionsAny(message: PlatformChannelMessage, names: Collection<String>): Boolean {
        if (message.recalled) return false
        return names.any { name ->
            name.isNotBlank() && (
                message.text.contains("@$name ") ||
                    message.text.contains("@$name\n") ||
                    message.text.endsWith("@$name")
                )
        }
    }

    /**
     * 消息中心聚合（当前频道口径）：@我的消息 + 未读计数。
     * 未读按 [lastReadId] 之后的消息算（与频道页未读口径一致）。
     * 生效的 @所有人（[allMentionLive]）也算 @我——它本来就是全员提及。
     */
    fun buildMessageCenter(
        messages: List<PlatformChannelMessage>,
        myNames: Collection<String>,
        lastReadId: String?,
        privilegedSenderIds: Set<String> = emptySet(),
    ): MessageCenter {
        val readIndex = if (lastReadId == null) -1 else messages.indexOfFirst { it.id == lastReadId }
        val unread = if (lastReadId == null) emptyList()
        else messages.drop(readIndex + 1)
        val mentions = messages.filter {
            mentionsAny(it, myNames) || allMentionLive(it, privilegedSenderIds)
        }
        val unreadMentions = unread.count {
            mentionsAny(it, myNames) || allMentionLive(it, privilegedSenderIds)
        }
        return MessageCenter(
            mentionMessages = mentions,
            unreadCount = unread.size,
            unreadMentionCount = unreadMentions,
        )
    }

    /** 文本里是否写了 @所有人（`@all` / `@所有人`）。 */
    fun mentionsAllText(text: String): Boolean =
        text.contains("@all") || text.contains("@所有人")

    /**
     * 文本里是否写了 @所有人（`@all` / `@所有人`）。撤回的消息不算提及。
     * 注意：这只是文本检测，是否生效见 [allMentionLive]。
     */
    fun mentionsAll(message: PlatformChannelMessage): Boolean {
        if (message.recalled) return false
        return mentionsAllText(message.text)
    }

    /**
     * 这条 @所有人是否生效：只有 owner/协调者发的才算全员提及；其他人
     * 写的 @all 只当普通文本（不渲染提及、不计入消息中心、不触发提醒）。
     * 客户端已知的特权发送者是协调者（本机 App，见 TeamVoice）与本机
     * 自身 agent_id；频道 owner 的服务端语义待定，本轮不另造工具名。
     */
    fun allMentionLive(
        message: PlatformChannelMessage,
        privilegedSenderIds: Set<String>,
    ): Boolean = mentionsAll(message) && message.agentId in privilegedSenderIds

    /**
     * 一次发送意图的重试：effect_key 只铸一次，失败重试复用同一键，
     * 平台按 (频道, 发送者, 键) 去重，不会双发。默认最多发 2 次
     * （首发 + 1 次重试）；全部失败时抛最后一次的异常。
     */
    fun <T> sendWithSameEffectKey(
        key: String,
        maxAttempts: Int = 2,
        attempt: (String) -> T,
    ): T {
        require(maxAttempts >= 1) { "maxAttempts 至少为 1" }
        var lastError: Exception? = null
        repeat(maxAttempts) {
            try {
                return attempt(key)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("发送失败。")
    }

    /** 已读回执名单文案：“已读：甲、乙 · 未读：丙”；无人读/全员已读如实写。 */
    fun readersText(readers: List<ChannelReader>): String {
        if (readers.isEmpty()) return "暂无成员已读信息。"
        val read = readers.filter { it.read }.map { it.agentName.ifBlank { it.agentId } }
        val unread = readers.filter { !it.read }.map { it.agentName.ifBlank { it.agentId } }
        val parts = mutableListOf<String>()
        parts += "已读：" + if (read.isEmpty()) "暂无人" else read.joinToString("、")
        if (unread.isNotEmpty()) parts += "未读：" + unread.joinToString("、")
        return parts.joinToString(" · ")
    }

    /** 是否私聊频道（平台 kind=dm）。 */
    fun isDm(channel: ChannelSummary): Boolean = channel.kind == "dm"

    /** 普通频道（非私聊、未归档优先由调用方排）。 */
    fun groupChannels(channels: List<ChannelSummary>): List<ChannelSummary> =
        channels.filter { !isDm(it) }

    /** 私聊频道列表。 */
    fun dmChannels(channels: List<ChannelSummary>): List<ChannelSummary> =
        channels.filter { isDm(it) }

    /**
     * 发帖幂等键（客户端口径）：入口处为“这一次发送意图”铸造，
     * 键 = 前缀 + 调用方给的随机 nonce（已清洗）；重试/重复点按复用
     * 同一键，平台按 (频道, 发送者, 键) 去重，只落一条。
     * 注意：这只是 `ck_`+nonce，不是 AWM KeyMint。
     */
    fun buildEffectKey(nonce: String): String {
        val cleaned = nonce.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(100)
        return "ck_$cleaned"
    }

    /**
     * AWM 证据分级标签（四期证据回显；与 server/awm/evidence.py 同口径：
     * E0 已入队 / E1 已传输 / E2 设备已动作 / E3 权威回执确认 / H 人工接力）。
     * 频道只呈现，不凭观察冒充 E3。
     */
    fun evidenceGradeLabel(grade: String?): String = when (grade) {
        "E0" -> "E0 已入队"
        "E1" -> "E1 已送达"
        "E2" -> "E2 已执行"
        "E3" -> "E3 已确认生效"
        "H" -> "H 人工接力"
        else -> ""
    }

    /** 多份证据取最高级（优先级与 evidence.py 的 highest_grade 一致：E3>H>E2>E1>E0）。 */
    fun highestEvidenceGrade(grades: Collection<String>): String? {
        for (grade in listOf("E3", "H", "E2", "E1", "E0")) {
            if (grade in grades) return grade
        }
        return null
    }

    /** 到期可发的定时消息（按到期时间排）。 */
    fun dueScheduled(
        items: List<ScheduledChannelPost>,
        nowSeconds: Double,
    ): List<ScheduledChannelPost> =
        items.filter { it.dueAtSeconds <= nowSeconds }.sortedBy { it.dueAtSeconds }

    /** 定时消息的人话时间差（“约 10 分钟后”粗粒度即可，精确时间由界面另显）。 */
    fun scheduledDelayText(delaySeconds: Long): String = when {
        delaySeconds <= 60 -> "约 1 分钟后"
        delaySeconds < 3600 -> "约 ${delaySeconds / 60} 分钟后"
        delaySeconds < 86400 -> "约 ${delaySeconds / 3600} 小时后"
        else -> "约 ${delaySeconds / 86400} 天后"
    }
}

/** 消息中心聚合结果（频道补全三期）。 */
data class MessageCenter(
    val mentionMessages: List<PlatformChannelMessage>,
    val unreadCount: Int,
    val unreadMentionCount: Int,
)

/**
 * 一条待发的定时消息（频道补全二期·定时发送）：发送前可取消；
 * 到期由 App 在频道刷新时发出，发帖带 [effectKey] 防重复。
 * 持久化由调用方负责（本机偏好存储），本类只是数据形状。
 */
data class ScheduledChannelPost(
    val id: String,
    val channelId: String,
    val text: String,
    val dueAtSeconds: Double,
    val effectKey: String,
    val attachmentRef: String? = null,
)
