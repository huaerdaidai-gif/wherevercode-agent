package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.session.SessionKind
import wherevercode.agent.core.session.SessionRecord

/**
 * 会话列表数据映射（阶段 3，U5/批 3c）：分组与文案。
 */
class SessionListItemsTest {

    private fun record(
        sessionId: String,
        name: String = "会话",
        kind: SessionKind = SessionKind.PRIVATE,
        taskId: String? = null,
        members: List<String> = listOf("user", "agent-a"),
        updatedAt: Long = 1_700_000_100L,
    ) = SessionRecord(
        sessionId = sessionId,
        name = name,
        kind = kind,
        ownerId = "user",
        members = members,
        taskId = taskId,
        createdAtEpochSeconds = 1_700_000_000L,
        updatedAtEpochSeconds = updatedAt,
    )

    @Test
    fun `chat prefixed sessions go to history`() {
        val items = buildSessionListItems(
            listOf(record("chat:abc-123", name = "chat:abc-123")),
        ) { "t$it" }
        assertEquals(1, items.size)
        assertTrue(items[0].isHistory)
    }

    @Test
    fun `named sessions are not history and keep kind labels`() {
        val items = buildSessionListItems(
            listOf(
                record("s-private", name = "CodeBuddy", kind = SessionKind.PRIVATE),
                record("s-group", name = "WhereverCode会话", kind = SessionKind.GROUP),
                record("s-task", name = "任务讨论", kind = SessionKind.TASK, taskId = "task-1"),
            ),
        ) { "t$it" }
        assertEquals(listOf("私聊", "团队", "任务"), items.map { it.kindLabel })
        assertTrue(items.none { it.isHistory })
        assertEquals("CodeBuddy", items[0].name)
        assertTrue(items[2].subtitle.contains("任务 task-1"))
    }

    @Test
    fun `subtitle carries member identities and update time`() {
        val items = buildSessionListItems(
            listOf(record("s-1", members = listOf("user", "a", "b"))),
        ) { "TIME" }
        assertTrue(items[0].subtitle.contains("3 位成员：user、a、b"))
        assertTrue(items[0].subtitle.contains("TIME"))
    }

    @Test
    fun `blank name falls back to session id`() {
        val items = buildSessionListItems(listOf(record("s-1", name = "  "))) { "t" }
        assertEquals("s-1", items[0].name)
    }

    @Test
    fun `ledger order is preserved`() {
        val items = buildSessionListItems(
            listOf(record("new"), record("old")),
        ) { "t" }
        assertEquals(listOf("new", "old"), items.map { it.sessionId })
        assertFalse(items.isEmpty())
    }

    @Test
    fun `history rows resolve conversation id and hide old metadata duplicates`() {
        val conversationId = "11111111-1111-4111-8111-111111111111"
        val legacy = record(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            name = "chat:$conversationId",
        )
        val canonical = record(conversationId, name = "chat:$conversationId")

        assertEquals(conversationId, chatConversationId(legacy))
        val items = buildSessionListItems(listOf(legacy, canonical)) { "time" }
        assertEquals(1, items.size)
        assertEquals(conversationId, items.single().sessionId)
    }
}
