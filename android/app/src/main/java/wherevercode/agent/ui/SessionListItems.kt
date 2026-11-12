package wherevercode.agent.ui

import wherevercode.agent.core.session.SessionKind
import wherevercode.agent.core.session.SessionRecord

/**
 * 会话列表的数据映射（阶段 3，U5/批 3c；纯 Kotlin，可单测）。
 *
 * 分组规则（与 SessionLedger 的约定一致）：
 * - 自动物化的 `chat:<id>` 会话归入“历史对话”（无名、仅按时间排）；
 * - 其余具名会话按 kind 分：PRIVATE 私聊 / GROUP 团队 / TASK 任务。
 * 顺序沿用 listSessions 的结果（创建时间倒序），此处不重排。
 *
 * V5 C3 chat-bugfix：`chat:` 前缀在会话 NAME 上，不在 sessionId 上
 * （sessionId 是随机 UUID）——旧代码按 sessionId 判断，恒为 false。
 * [titleOverrides] 是调用方给的显示名（例如碎片行的首条用户消息），
 * 只换显示名，不改分组。
 */
fun buildSessionListItems(
    sessions: List<SessionRecord>,
    titleOverrides: Map<String, String> = emptyMap(),
    summaryOverrides: Map<String, String> = emptyMap(),
    timeLabel: (Long) -> String,
): List<SessionListController.SessionListItem> {
    // Older builds created a fresh random metadata session id for every
    // chat turn. Keep the rows in storage, but show one row per real chat;
    // prefer the canonical id created by the fixed writer when present.
    val preferredHistoryRows = sessions
        .filter { it.name.startsWith("chat:") }
        .groupBy(::chatConversationId)
        .mapValues { (conversationId, rows) ->
            rows.firstOrNull { it.sessionId == conversationId } ?: rows.first()
        }
        .values
        .mapTo(mutableSetOf()) { it.sessionId }

    return sessions
        .filter { !it.name.startsWith("chat:") || it.sessionId in preferredHistoryRows }
        .map { record ->
        val kindLabel = when (record.kind) {
            SessionKind.PRIVATE -> "私聊"
            SessionKind.GROUP -> "团队"
            SessionKind.TASK -> "任务"
        }
        val subtitle = buildString {
            append("成员 ${record.members.size} · 更新 ${timeLabel(record.updatedAtEpochSeconds)}")
            record.taskId?.let { append(" · 任务 $it") }
            summaryOverrides[record.sessionId]?.takeIf { it.isNotBlank() }?.let {
                append(" · 摘要：${it.take(64)}")
            }
        }
        SessionListController.SessionListItem(
            sessionId = record.sessionId,
            name = titleOverrides[record.sessionId]?.takeIf { it.isNotBlank() }
                ?: record.name.ifBlank { record.sessionId },
            kindLabel = kindLabel,
            subtitle = subtitle,
            isHistory = record.name.startsWith("chat:"),
        )
    }
}

/** Resolve the chat-store id encoded in legacy `chat:<conversationId>` rows. */
fun chatConversationId(record: SessionRecord): String =
    record.name.removePrefix("chat:").takeIf { record.name.startsWith("chat:") && it.isNotBlank() }
        ?: record.sessionId
