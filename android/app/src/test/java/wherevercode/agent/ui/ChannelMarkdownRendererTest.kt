package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChannelMarkdownRendererTest {

    @Test
    fun `ChannelMarkdownRenderer renders safely and falls back cleanly without drop`() {
        val md = "这是 **粗体** 和 `代码` 与 [链接](https://example.com)\n- 列表项"
        val rendered = ChannelMarkdownRenderer.render(md)
        val text = rendered.toString()
        assertTrue(text.contains("粗体"))
        assertTrue(text.contains("代码"))
        assertTrue(text.contains("列表项"))

        // 遇到非法或普通文本绝不吞字
        val plain = "普通的未闭合 **标记"
        val plainRendered = ChannelMarkdownRenderer.render(plain)
        assertEquals("普通的未闭合 **标记", plainRendered.toString())
    }
}
