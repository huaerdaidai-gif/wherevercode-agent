package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 频道引用回复 + 任务关联测试（2026-11-13）。
 * 引用行编进正文第一行（`> 回复 #id 发送者：摘要`），读回渲染成引用块；
 * 任务关联只加归属头（只作备注，不派活）。
 * 注意：期望的归属头用 [teamAttributionHeader] 现拼，不在测试里硬写
 * 归属头字面量（字面量里的全角括号在工具链传输中会被清洗掉）。
 */
@RunWith(RobolectricTestRunner::class)
class ChannelQuoteTest {

    @Test
    fun `quote encode parse strip round-trip`() {
        val quote = ChannelQuote(
            messageId = "m123",
            senderName = "Codex",
            excerpt = "行，先补测试。",
        )
        val encoded = ChannelQuoteCodec.encode(quote, "那就这么办。")
        assertEquals("> 回复 #m123 Codex：行，先补测试。\n那就这么办。", encoded)
        val parsed = ChannelQuoteCodec.parse(encoded)
        assertEquals("m123", parsed?.messageId)
        assertEquals("Codex", parsed?.senderName)
        assertEquals("行，先补测试。", parsed?.excerpt)
        assertEquals("那就这么办。", ChannelQuoteCodec.strip(encoded))
    }

    @Test
    fun `quote parse accepts sender names with spaces`() {
        val parsed = ChannelQuoteCodec.parse("> 回复 #m9 Example Agent：大家早。\n收到。")
        assertEquals("m9", parsed?.messageId)
        assertEquals("Example Agent", parsed?.senderName)
        assertEquals("大家早。", parsed?.excerpt)
    }

    @Test
    fun `quote parse rejects malformed lines`() {
        assertNull(ChannelQuoteCodec.parse("大家早。"))
        assertNull(ChannelQuoteCodec.parse("> 回复 Codex：没有 id。"))
        assertNull(ChannelQuoteCodec.parse("> 回复 #m1：没有发送者。"))
        assertNull(ChannelQuoteCodec.parse("> 回复 #m1 Codex 没有冒号"))
        // 没有引用行时 strip 原样返回。
        assertEquals("大家早。", ChannelQuoteCodec.strip("大家早。"))
    }

    @Test
    fun `quote encode truncates a long excerpt to eighty chars`() {
        val long = "x".repeat(200)
        val encoded = ChannelQuoteCodec.encode(ChannelQuote("m1", "Codex", long), "正文")
        val parsed = ChannelQuoteCodec.parse(encoded)
        assertEquals(80, parsed?.excerpt?.length)
    }

    @Test
    fun `excerptOf strips the attribution header and quote line`() {
        val text = teamAttributionHeader("Codex", "abc12345-def0", "修登录闪退") +
            "\n行，先补测试。\n第二行不进摘要。"
        assertEquals("行，先补测试。", ChannelQuoteCodec.excerptOf(text))
        assertEquals("", ChannelQuoteCodec.excerptOf(""))
        // 已带引用行的不再重复引。
        val quoted = ChannelQuoteCodec.encode(ChannelQuote("m1", "Codex", "行。"), "那就这么办。")
        assertEquals("那就这么办。", ChannelQuoteCodec.excerptOf(quoted))
    }
}

/**
 * 频道消息关联任务测试（2026-11-13）：关联只加归属头、只作备注，
 * 绝不调任务接口（本文件断言的只是编码形状；不派活是结构保证，
 * 见 ChannelTaskLink 的文档与频道页——频道路径上没有任务调用）。
 */
@RunWith(RobolectricTestRunner::class)
class ChannelTaskLinkTest {

    @Test
    fun `task link prepends the attribution header`() {
        val encoded = ChannelTaskLink.encode("abc12345-def0", "修登录闪退", "这个方案行。")
        val expectedHeader = teamAttributionHeader("本机 App", "abc12345-def0", "修登录闪退")
        assertEquals("$expectedHeader\n这个方案行。", encoded)
        assertTrue(ChannelTaskLink.isLinked(encoded))
        assertEquals("abc12345", ChannelTaskLink.linkedTaskId(encoded))
        // 读回按任务分串（与既有 groupTeamMessages 同口径）。
        val view = teamMessageFromChannel(
            wherevercode.agent.core.agent.PlatformChannelMessage(
                id = "m1",
                agentId = "local-app",
                agentName = "本机 App",
                text = encoded,
                createdAtSeconds = 10.0,
            ),
        )
        assertEquals("abc12345", view.taskId)
        assertEquals("修登录闪退", view.taskTitle)
        assertEquals("这个方案行。", view.text)
    }

    @Test
    fun `blank task id links nothing`() {
        assertEquals("正文", ChannelTaskLink.encode("", null, "正文"))
        assertEquals("正文", ChannelTaskLink.encode("   ", null, "正文"))
        assertTrue(!ChannelTaskLink.isLinked("正文"))
        assertNull(ChannelTaskLink.linkedTaskId("正文"))
    }

    @Test
    fun `task link composes header first then quote then body`() {
        // 组合顺序（发送时）：任务头 → 引用行 → 正文；归属头必须在
        // 正文开头，读回解析才认得出任务。
        val quote = ChannelQuote("m7", "Codex", "行。")
        var composed = ChannelQuoteCodec.encode(quote, "那就这么办。")
        composed = ChannelTaskLink.encode("abc12345", null, composed)
        val expectedHeader = teamAttributionHeader("本机 App", "abc12345", null)
        assertTrue(composed.startsWith("$expectedHeader\n> 回复 #m7 Codex：行。\n那就这么办。"))
        assertEquals("abc12345", ChannelTaskLink.linkedTaskId(composed))
        // 引用行在任务头后面：先去任务头，再解析引用行。
        assertEquals("m7", ChannelQuoteCodec.parse(stripTeamAttributionHeader(composed))?.messageId)
    }
}
