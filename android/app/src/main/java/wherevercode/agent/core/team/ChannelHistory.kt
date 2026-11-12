package wherevercode.agent.core.team

import wherevercode.agent.core.agent.PlatformChannelMessage

/**
 * 频道历史记录、分页与搜索（2026-11-13，频道能力补齐）。
 *
 * 两层：
 * - 客户端层（本文件）：对本机已加载的消息做搜索、分页、合并去重、
 *   未读计数。消息按时间升序（早 → 晚）；id 为空的消息直接丢弃
 *   （没有 id 就没法去重、没法翻页）。
 * - 服务端层（待接线，MCP 工人 + MainActivity）：`agent_channel_messages`
 *   本来就支持 `before_id`（往上翻更早的消息）与 `q`（群里搜消息，
 *   最长 200 字），那才是全量历史；客户端搜索只覆盖已加载的部分，
 *   搜不到要明说“只搜了本机已加载的消息”，不要谎称全频道搜过。
 * 纯函数，无 IO。
 */
object ChannelHistory {
    /** 一页的消息条数（与 ChannelFeed.POLL_LIMIT 同口径）。 */
    const val PAGE_SIZE: Int = 20

    /** 本机最多保留的消息条数（超了只留最新的；与 MainActivity 的 100 条上限不同——这里是合并工具的上限）。 */
    const val MAX_KEPT_MESSAGES: Int = 500

    /**
     * 客户端搜索：正文或发送者名含 query（不分大小写）。query 为空
     * 返回原列表。只搜已加载的消息，不代表全频道。
     */
    fun search(messages: List<PlatformChannelMessage>, query: String): List<PlatformChannelMessage> {
        val q = query.trim()
        if (q.isBlank()) return messages
        return messages.filter { message ->
            message.text.contains(q, ignoreCase = true) ||
                message.agentName.contains(q, ignoreCase = true)
        }
    }

    /**
     * 合并多页消息：按 id 去重（先到的赢）、按时间升序排（时间相同时
     * 保持原顺序）、只留最新 [MAX_KEPT_MESSAGES] 条。
     */
    fun merge(
        existing: List<PlatformChannelMessage>,
        incoming: List<PlatformChannelMessage>,
    ): List<PlatformChannelMessage> {
        val seen = LinkedHashSet<String>()
        val all = mutableListOf<PlatformChannelMessage>()
        for (message in existing + incoming) {
            if (message.id.isBlank()) continue
            if (seen.add(message.id)) all += message
        }
        val sorted = all.sortedBy { it.createdAtSeconds }
        return if (sorted.size > MAX_KEPT_MESSAGES) {
            sorted.subList(sorted.size - MAX_KEPT_MESSAGES, sorted.size)
        } else {
            sorted
        }
    }

    /**
     * 往上翻一页更早的消息：返回 [beforeId] 之前（不含它）的最多
     * [pageSize] 条；beforeId 为空或找不着返回最新的 [pageSize] 条。
     * 供“加载更早”用；服务端 `before_id` 接线后由传输层做同样的事。
     */
    fun pageOlder(
        messages: List<PlatformChannelMessage>,
        beforeId: String?,
        pageSize: Int = PAGE_SIZE,
    ): List<PlatformChannelMessage> {
        if (messages.isEmpty() || pageSize <= 0) return emptyList()
        val beforeIndex = if (beforeId.isNullOrBlank()) {
            messages.size
        } else {
            messages.indexOfFirst { it.id == beforeId }.takeIf { it >= 0 } ?: messages.size
        }
        val start = (beforeIndex - pageSize).coerceAtLeast(0)
        return messages.subList(start, beforeIndex)
    }

    /**
     * 本机窗口：显示最新的 [visibleCount] 条（频道页默认 20 条，
     * “加载更早”每次 +20）。返回要显示的那一段（仍按时间升序）。
     */
    fun visibleWindow(
        messages: List<PlatformChannelMessage>,
        visibleCount: Int,
    ): List<PlatformChannelMessage> {
        if (visibleCount <= 0) return emptyList()
        return messages.takeLast(visibleCount.coerceAtMost(messages.size))
    }

    /** 窗口外还剩多少条更早的消息（“加载更早”按钮的数字来源）。 */
    fun olderRemaining(messages: List<PlatformChannelMessage>, visibleCount: Int): Int =
        (messages.size - visibleCount).coerceAtLeast(0)

    /**
     * 未读条数：[lastReadId] 之后的消息数。lastReadId 为空或找不着
     * → 全部算未读（从没读过）；在最后一条 → 0。
     */
    fun countUnreadAfter(
        messages: List<PlatformChannelMessage>,
        lastReadId: String?,
    ): Int {
        if (lastReadId.isNullOrBlank()) return messages.size
        val index = messages.indexOfFirst { it.id == lastReadId }
        if (index < 0) return messages.size
        return messages.size - index - 1
    }
}
