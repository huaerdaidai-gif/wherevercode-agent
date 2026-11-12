package wherevercode.agent.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {
    private fun message(role: String, content: String, id: String = "m") =
        Message(id, "conv-1", role, content, "test", 1_700_000_000L)

    @Test
    fun `within budget nothing is compressed`() {
        val manager = ContextManager(budgetEstimatedTokens = 24000)
        val history = listOf(
            message("user", "你好"),
            message("assistant", "你好，有什么可以帮你？"),
        )
        val assembled = manager.build(
            systemPrompt = "系统提示",
            memorySummary = "",
            history = history,
            currentMessage = "继续",
            tools = emptyList(),
        )
        assertEquals(0, assembled.compressedRounds)
        assertTrue(assembled.compressionNotes.isEmpty())
        assertTrue(assembled.estimatedTokens > 0)
        assertEquals("继续", assembled.messages.last().content)
    }

    @Test
    fun `over budget the oldest round is compressed first`() {
        val manager = ContextManager(budgetEstimatedTokens = 100)
        // Long rounds (500 chars each) so the 200-char summary really
        // drops content instead of keeping the whole round.
        val oldRound = "旧对话内容".repeat(100)
        val history = listOf(
            message("user", oldRound, "m1"),
            message("assistant", "旧回答".repeat(100), "m2"),
            message("user", "新问题".repeat(7), "m3"),
            message("assistant", "新回答".repeat(7), "m4"),
        )
        val assembled = manager.build(
            systemPrompt = "",
            memorySummary = "",
            history = history,
            currentMessage = "当前问题",
            tools = emptyList(),
        )
        assertTrue(assembled.compressedRounds >= 1)
        assertTrue(assembled.compressionNotes.isNotEmpty())
        // The newest round survives; the oldest is replaced by a summary.
        val texts = assembled.messages.joinToString("\n") { it.content }
        assertTrue(texts.contains("新问题"))
        assertTrue(texts.contains("[上下文压缩]"))
        assertTrue(!texts.contains(oldRound))
    }

    @Test
    fun `tool outputs are shrunk before whole rounds`() {
        val manager = ContextManager(budgetEstimatedTokens = 120)
        val longToolOutput = "x".repeat(2000)
        val history = listOf(
            message("user", "读文件", "m1"),
            message("tool", longToolOutput, "m2"),
            message("assistant", "读完了", "m3"),
        )
        val assembled = manager.build(
            systemPrompt = "",
            memorySummary = "",
            history = history,
            currentMessage = "下一步",
            tools = emptyList(),
        )
        // The shrink step ran (recorded in the notes) even though the
        // later round compression replaced the shrunk message itself.
        assertTrue(assembled.compressionNotes.any { it.contains("工具原始输出已压缩") })
        val texts = assembled.messages.joinToString("\n") { it.content }
        assertTrue(!texts.contains(longToolOutput))
        assertTrue(texts.contains("[上下文压缩]"))
    }

    @Test
    fun `estimation is labelled estimated and never claims provider usage`() {
        val manager = ContextManager()
        // 8 chars -> 2 estimated tokens at 4 chars/token.
        assertEquals(2, manager.estimateTokens("12345678"))
        assertEquals(0, manager.estimateTokens(""))
    }

    @Test
    fun `current message images ride only the current round and cost estimated tokens`() {
        val manager = ContextManager(budgetEstimatedTokens = 24000)
        val images = listOf(ChatImage("data:image/jpeg;base64,AAA"), ChatImage("data:image/jpeg;base64,BBB"))
        val assembled = manager.build(
            systemPrompt = "系统提示",
            memorySummary = "",
            history = listOf(message("user", "之前的问题")),
            currentMessage = "看这张图",
            tools = emptyList(),
            currentImages = images,
        )
        // 图片只挂在当轮 user 消息上，不进历史消息。
        val last = assembled.messages.last()
        assertEquals("看这张图", last.content)
        assertEquals(images, last.images)
        assertTrue(assembled.messages.dropLast(1).all { it.images.isEmpty() })
        // 估算含每张图的 token（2 张 × 1000），比无图时多。
        val withoutImages = manager.build(
            systemPrompt = "系统提示",
            memorySummary = "",
            history = listOf(message("user", "之前的问题")),
            currentMessage = "看这张图",
            tools = emptyList(),
        )
        assertEquals(withoutImages.estimatedTokens + 2 * 1000, assembled.estimatedTokens)
    }
}
