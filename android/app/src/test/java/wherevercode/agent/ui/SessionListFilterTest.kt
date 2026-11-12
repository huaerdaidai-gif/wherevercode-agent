package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史搜索框过滤（V5 Phase B C2，批 3，第 1 步；纯 JVM JUnit 4）。
 *
 * 只匹配条目字段：会话名、类型、副标题（含任务 id）、会话 id；
 * 不搜消息正文（见 SessionListFilter 的 KDoc 与批 3 勘查 §2.2）。
 */
class SessionListFilterTest {

    private fun item(
        sessionId: String,
        name: String = "会话",
        kindLabel: String = "私聊",
        subtitle: String = "成员 2 · 更新 t",
        isHistory: Boolean = false,
    ) = SessionListController.SessionListItem(
        sessionId = sessionId,
        name = name,
        kindLabel = kindLabel,
        subtitle = subtitle,
        isHistory = isHistory,
    )

    private val items = listOf(
        item("s-1", name = "CodeBuddy", kindLabel = "私聊"),
        item("s-2", name = "任务讨论", kindLabel = "任务", subtitle = "成员 3 · 更新 t · 任务 task-1"),
        item("chat:abc-123", name = "chat:abc-123", kindLabel = "私聊", isHistory = true),
    )

    @Test
    fun `empty query returns everything in order`() {
        assertEquals(items, SessionListFilter.filter(items, ""))
    }

    @Test
    fun `blank query returns everything`() {
        assertEquals(items, SessionListFilter.filter(items, "   "))
    }

    @Test
    fun `matches by name substring`() {
        val result = SessionListFilter.filter(items, "任务讨论")
        assertEquals(listOf("s-2"), result.map { it.sessionId })
    }

    @Test
    fun `matches by kind label`() {
        val result = SessionListFilter.filter(items, "任务")
        assertEquals(listOf("s-2"), result.map { it.sessionId })
    }

    @Test
    fun `matches by task id inside subtitle`() {
        val result = SessionListFilter.filter(items, "task-1")
        assertEquals(listOf("s-2"), result.map { it.sessionId })
    }

    @Test
    fun `matches history sessions by session id substring`() {
        val result = SessionListFilter.filter(items, "abc")
        assertEquals(listOf("chat:abc-123"), result.map { it.sessionId })
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(
            listOf("s-1"),
            SessionListFilter.filter(items, "codebuddy").map { it.sessionId },
        )
        assertEquals(
            listOf("s-1"),
            SessionListFilter.filter(items, "CODEBUDDY").map { it.sessionId },
        )
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(SessionListFilter.filter(items, "zzz-no-match").isEmpty())
    }

    @Test
    fun `matches helper accepts empty query for any item`() {
        assertTrue(SessionListFilter.matches(items[0], ""))
        assertTrue(SessionListFilter.matches(items[0], "code"))
    }
}
