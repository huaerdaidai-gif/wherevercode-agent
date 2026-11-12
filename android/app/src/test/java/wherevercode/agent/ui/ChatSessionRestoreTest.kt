package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import wherevercode.agent.brain.Message

/**
 * 失败测试（maintainer 2026-11-10 07:46 “无法打开”）：从 Room 恢复会话时间线时，
 * user/assistant 消息按顺序配对成轮次；tool 行不进时间线；只有用户话没有助手
 * 回复的轮次照常恢复（正文为空、终态）。
 * 纯 Kotlin 纯函数测试，无 Android 依赖，零真实调用。
 */
class ChatSessionRestoreTest {

    private fun msg(role: String, content: String, at: Long) =
        Message(
            id = "m-$role-$at",
            conversationId = "s1",
            role = role,
            content = content,
            source = role,
            createdAtEpochSeconds = at,
        )

    @Test
    fun `pairs user and assistant messages into turns in order`() {
        val turns = ChatSessionRestore.messagesToTurns(
            listOf(
                msg("user", "做一个计算器小程序", 1),
                msg("assistant", "已做好。", 2),
                msg("user", "再加一个开方功能", 3),
                msg("assistant", "已加上。", 4),
            ),
        )
        assertEquals(2, turns.size)
        assertEquals("做一个计算器小程序", turns[0].userText)
        assertEquals("已做好。", turns[0].assistantText)
        assertEquals("再加一个开方功能", turns[1].userText)
        assertEquals("已加上。", turns[1].assistantText)
        assertEquals("DONE", turns[0].status)
    }

    @Test
    fun `tool messages are skipped and a trailing user-only turn is kept`() {
        val turns = ChatSessionRestore.messagesToTurns(
            listOf(
                msg("user", "读一下 README", 1),
                msg("tool", "{\"tool\": \"read_file\"}", 2),
                msg("assistant", "已读取完毕", 3),
                msg("user", "还没说完就断了", 4),
            ),
        )
        assertEquals(2, turns.size)
        assertEquals("读一下 README", turns[0].userText)
        assertEquals("已读取完毕", turns[0].assistantText)
        assertEquals("还没说完就断了", turns[1].userText)
        assertEquals("", turns[1].assistantText)
    }

    @Test
    fun `assistant-only rows without a user turn are ignored`() {
        val turns = ChatSessionRestore.messagesToTurns(
            listOf(
                msg("assistant", "孤儿回复", 1),
                msg("user", "正常问题", 2),
                msg("assistant", "正常回复", 3),
            ),
        )
        assertEquals(1, turns.size)
        assertEquals("正常问题", turns[0].userText)
        assertEquals("正常回复", turns[0].assistantText)
    }
}
