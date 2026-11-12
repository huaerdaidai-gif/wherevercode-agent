package wherevercode.agent.ui

/**
 * 历史搜索框的纯 UI 侧过滤（V5 Phase B C2，批 3，第 1 步；纯 Kotlin，可单测）。
 *
 * 只匹配条目字段：会话名、类型、副标题（含任务 id）、会话 id。
 * 不搜消息正文：SessionListItem 不含任何消息摘要，SessionMessageDao 也无 LIKE 查询，
 * 本次不加 DAO、不做全文搜索（见批 3 勘查 §2.2）。大小写不敏感子串匹配；
 * 空 query 返回全量（保持原顺序与原分组，调用方再按具名 / 历史分段渲染）。
 */
object SessionListFilter {

    fun filter(
        items: List<SessionListController.SessionListItem>,
        query: String,
    ): List<SessionListController.SessionListItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return items
        return items.filter { item -> matches(item, needle) }
    }

    fun matches(item: SessionListController.SessionListItem, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        return item.name.lowercase().contains(needle) ||
            item.kindLabel.lowercase().contains(needle) ||
            item.subtitle.lowercase().contains(needle) ||
            item.sessionId.lowercase().contains(needle)
    }
}
