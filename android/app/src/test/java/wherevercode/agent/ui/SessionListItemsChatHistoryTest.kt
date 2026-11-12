package wherevercode.agent.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import wherevercode.agent.core.session.SessionKind
import wherevercode.agent.core.session.SessionRecord

/**
 * 失败测试：自动物化的 `chat:<id>` 会话应归入“历史对话”区。
 *
 * 根因（`SessionListItems.kt`）：isHistory 检查的是 `record.sessionId` 是否以
 * `chat:` 开头，但 sessionId 是随机 UUID，`chat:` 前缀实际在 `name` 上
 * （账本自动物化时 `name = "chat:<conversationId前40字>"`），所以恒为 false，
 * 碎片会话全进“会话”具名区。纯 JVM JUnit 4，无 Robolectric。
 */
class SessionListItemsChatHistoryTest {

    @Test
    fun `auto-materialized chat session is history even though its id is a uuid`() {
        val record = SessionRecord(
            sessionId = "11111111-2222-3333-4444-555555555555",
            name = "chat:11111111-2222-3333-4444-555555555555",
            kind = SessionKind.PRIVATE,
            ownerId = "user",
            members = listOf("user"),
            createdAtEpochSeconds = 100,
            updatedAtEpochSeconds = 200,
        )
        val items = buildSessionListItems(listOf(record)) { "t" }
        assertTrue(items.single().isHistory)
    }

    @Test
    fun `history row uses conversation title and last reply summary instead of uuid`() {
        val record = SessionRecord(
            sessionId = "random-ledger-id",
            name = "chat:conversation-id",
            kind = SessionKind.PRIVATE,
            ownerId = "user",
            members = listOf("user"),
            createdAtEpochSeconds = 100,
            updatedAtEpochSeconds = 200,
        )
        val item = buildSessionListItems(
            sessions = listOf(record),
            titleOverrides = mapOf(record.sessionId to "请修复这个问题"),
            summaryOverrides = mapOf(record.sessionId to "已修复并补充回归检查"),
            timeLabel = { "t" },
        ).single()
        assertEquals("请修复这个问题", item.name)
        assertTrue(item.subtitle.contains("已修复并补充回归检查"))
        assertFalse(item.name.contains("conversation-id"))
    }

    @Test
    fun `history summary is capped at sixty four characters`() {
        val record = SessionRecord(
            sessionId = "ledger",
            name = "chat:conversation",
            kind = SessionKind.PRIVATE,
            ownerId = "user",
            members = listOf("user"),
            createdAtEpochSeconds = 100,
            updatedAtEpochSeconds = 200,
        )
        val item = buildSessionListItems(
            sessions = listOf(record),
            summaryOverrides = mapOf(record.sessionId to "x".repeat(100)),
            timeLabel = { "t" },
        ).single()
        assertEquals(64, item.subtitle.substringAfter("摘要：").length)
    }
}
