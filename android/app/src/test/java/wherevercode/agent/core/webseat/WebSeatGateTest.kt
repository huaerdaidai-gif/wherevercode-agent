package wherevercode.agent.core.webseat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网页席位触发闸与分片（与 iOS WebSeatTests 同一组用例）：
 * 门铃规则——带 @ 谁发都触发；不带 @ 只认机主（私聊必答、
 * 话题内跟进）；其他席位不带 @ 永不触发；自己不答自己。
 */
class WebSeatGateTest {

    private val site = WebSeatSite.CLAUDE
    private val handles = site.mentionHandles
    private val others = site.otherHandles()

    private fun gate(
        sender: String,
        text: String,
        root: String? = null,
        dm: Boolean = false,
    ): Boolean = WebSeatGate.shouldRespond(
        senderId = sender,
        ownerAgentId = "app",
        seatAgentId = "claude-web",
        text = text,
        threadRoot = root,
        isDM = dm,
        handles = handles,
        otherHandles = others,
    )

    @Test
    fun ownerMentionTriggers() {
        assertTrue(gate("app", "@Claude 在吗"))
    }

    @Test
    fun botMentionTriggersDoorbell() {
        assertTrue(gate("gpt-chat", "@Claude 你怎么看"))
    }

    @Test
    fun botPlainMessageNeverTriggers() {
        assertFalse(gate("gpt-chat", "我觉得方案不错"))
    }

    @Test
    fun seatNeverAnswersItself() {
        assertFalse(gate("claude-web", "@Claude 自问自答"))
    }

    @Test
    fun ownerThreadReplyContinuesWithoutMention() {
        assertTrue(gate("app", "接着说", root = "m1"))
    }

    @Test
    fun botThreadReplyNeverContinues() {
        assertFalse(gate("gpt-chat", "我也说一句", root = "m1"))
    }

    @Test
    fun ownerThreadReplyAddressedElsewhereDoesNotTrigger() {
        assertFalse(gate("app", "@Grok 你来说", root = "m1"))
    }

    @Test
    fun ownerTopLevelWithoutMentionDoesNotTrigger() {
        assertFalse(gate("app", "大家看看这个"))
    }

    @Test
    fun dmFromOwnerAlwaysTriggers() {
        assertTrue(gate("app", "在吗", dm = true))
    }

    @Test
    fun dmFromBotNeverTriggers() {
        assertFalse(gate("gpt-chat", "在吗", dm = true))
    }

    @Test
    fun stripMentions() {
        assertEquals("在吗", WebSeatGate.stripMentions("@Claude 在吗", handles))
    }

    @Test
    fun chunkShortTextUnchanged() {
        assertEquals(listOf("短消息"), chunkWebSeatText("短消息"))
    }

    @Test
    fun chunkLongTextSplitsWithinLimit() {
        val text = "x".repeat(8000)
        val chunks = chunkWebSeatText(text)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.length <= 3900 })
        assertEquals(8000, chunks.joinToString("").length)
    }

    @Test
    fun chunkPrefersNewlineBoundary() {
        val text = "a".repeat(3890) + "\n" + "b".repeat(100)
        val chunks = chunkWebSeatText(text)
        assertEquals(2, chunks.size)
        assertEquals(3890, chunks[0].length)
    }

    @Test
    fun probeLooksLoggedIn() {
        assertTrue(ClaudeSeatScripts.probeLooksLoggedIn("https://claude.ai/new", true))
        assertFalse(ClaudeSeatScripts.probeLooksLoggedIn("https://claude.ai/login", true))
        assertFalse(ClaudeSeatScripts.probeLooksLoggedIn("https://claude.ai/new", false))
    }
}
