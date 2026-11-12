package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markdown 轻量渲染（阶段 3，U1）：标题/粗斜体/代码块/列表/链接。 */
class MarkdownLiteTest {

    @Test
    fun `headings parse with levels`() {
        val blocks = MarkdownLite.parse("# 大标题\n## 小标题\n正文")
        val headings = blocks.filterIsInstance<MdBlock.Heading>()
        assertEquals(listOf(1, 2), headings.map { it.level })
        assertEquals("大标题", MarkdownLite.inlineText(headings[0].inlines))
        assertEquals(1, blocks.filterIsInstance<MdBlock.Paragraph>().size)
    }

    @Test
    fun `bold italic and inline code parse inline`() {
        val inlines = MarkdownLite.parseInline("这是 **粗体** 和 *斜体* 与 `代码`")
        assertTrue(inlines.any { it is MdInline.Bold && it.text == "粗体" })
        assertTrue(inlines.any { it is MdInline.Italic && it.text == "斜体" })
        assertTrue(inlines.any { it is MdInline.Code && it.text == "代码" })
    }

    @Test
    fun `fenced code block keeps language and raw code`() {
        val blocks = MarkdownLite.parse("说明\n```kotlin\nfun x() {}\n```\n结尾")
        val code = blocks.filterIsInstance<MdBlock.CodeBlock>()
        assertEquals(1, code.size)
        assertEquals("kotlin", code.single().language)
        assertEquals("fun x() {}", code.single().code)
        assertEquals(1, MarkdownLite.codeBlocks("说明\n```kotlin\nfun x() {}\n```").size)
    }

    @Test
    fun `bullet list groups consecutive items`() {
        val blocks = MarkdownLite.parse("- 第一\n- 第二\n\n段落")
        val lists = blocks.filterIsInstance<MdBlock.BulletList>()
        assertEquals(1, lists.size)
        assertEquals(listOf("第一", "第二"), lists.single().items.map { MarkdownLite.inlineText(it) })
    }

    @Test
    fun `link keeps text and url separately`() {
        val inlines = MarkdownLite.parseInline("看[文档](https://example.com)吧")
        val link = inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("文档", link.text)
        assertEquals("https://example.com", link.url)
    }

    @Test
    fun `plainText strips markers but keeps words and code`() {
        val plain = MarkdownLite.plainText("# 标题\n这是 **粗体**\n```\ncode()\n```")
        assertTrue(plain.contains("标题"))
        assertTrue(plain.contains("粗体"))
        assertTrue(plain.contains("code()"))
        assertTrue(!plain.contains("**"))
        assertTrue(!plain.contains("```"))
    }

    @Test
    fun `unclosed markers stay plain text`() {
        val inlines = MarkdownLite.parseInline("这是 **没闭合")
        assertTrue(inlines.none { it is MdInline.Bold })
        assertEquals("这是 **没闭合", MarkdownLite.inlineText(inlines))
        val code = MarkdownLite.parseInline("这是 `没闭合")
        assertTrue(code.none { it is MdInline.Code })
    }

    @Test
    fun `quote and plain paragraphs parse`() {
        val blocks = MarkdownLite.parse("> 引用一句\n\n普通段落")
        assertEquals(1, blocks.filterIsInstance<MdBlock.Quote>().size)
        assertEquals(1, blocks.filterIsInstance<MdBlock.Paragraph>().size)
        assertEquals("引用一句", MarkdownLite.inlineText((blocks[0] as MdBlock.Quote).inlines))
    }
}
