package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 基础语法高亮（阶段 3，U2）：关键字/字符串/注释/数字的区间扫描。 */
class CodeHighlightTest {

    @Test
    fun `kotlin keywords are marked`() {
        val spans = CodeHighlight.highlight("fun main() {\n val x = 1\n}", "kotlin")
        val keywords = spans.filter { it.kind == CodeHighlight.Kind.KEYWORD }
            .map { "fun main() {\n val x = 1\n}".substring(it.start, it.end) }
        assertTrue(keywords.contains("fun"))
        assertTrue(keywords.contains("val"))
    }

    @Test
    fun `string literal is one string span`() {
        val code = "val s = \"hello\""
        val spans = CodeHighlight.highlight(code, "kotlin")
        val strings = spans.filter { it.kind == CodeHighlight.Kind.STRING }
        assertEquals(1, strings.size)
        assertEquals("\"hello\"", code.substring(strings.single().start, strings.single().end))
    }

    @Test
    fun `line comment runs to end of line`() {
        val code = "val x = 1 // note"
        val spans = CodeHighlight.highlight(code, "kotlin")
        val comments = spans.filter { it.kind == CodeHighlight.Kind.COMMENT }
        assertEquals(1, comments.size)
        assertEquals("// note", code.substring(comments.single().start, comments.single().end))
    }

    @Test
    fun `block comment is one span`() {
        val code = "/* multi\nline */ val x = 1"
        val spans = CodeHighlight.highlight(code, "kotlin")
        val comments = spans.filter { it.kind == CodeHighlight.Kind.COMMENT }
        assertEquals(1, comments.size)
        assertTrue(code.substring(comments.single().start, comments.single().end).startsWith("/*"))
    }

    @Test
    fun `numbers are marked`() {
        val spans = CodeHighlight.highlight("val x = 42", "kotlin")
        val numbers = spans.filter { it.kind == CodeHighlight.Kind.NUMBER }
        assertEquals(listOf("42"), numbers.map { "val x = 42".substring(it.start, it.end) })
    }

    @Test
    fun `python hash comment and def keyword`() {
        val code = "# hello\ndef f():\n    return 1"
        val spans = CodeHighlight.highlight(code, "python")
        assertTrue(spans.any { it.kind == CodeHighlight.Kind.COMMENT })
        val keywords = spans.filter { it.kind == CodeHighlight.Kind.KEYWORD }
            .map { code.substring(it.start, it.end) }
        assertTrue(keywords.contains("def"))
        assertTrue(keywords.contains("return"))
    }

    @Test
    fun `keyword inside a string is not a keyword`() {
        val code = "val s = \"fun val\""
        val spans = CodeHighlight.highlight(code, "kotlin")
        // 行首的 val 本身是关键字；字符串里的 fun/val 绝不能再标为关键字。
        val keywords = spans.filter { it.kind == CodeHighlight.Kind.KEYWORD }
            .map { code.substring(it.start, it.end) }
        assertEquals(listOf("val"), keywords)
        assertEquals(1, spans.count { it.kind == CodeHighlight.Kind.STRING })
    }

    @Test
    fun `languageFor maps extensions and unknown languages highlight nothing`() {
        assertEquals("kotlin", CodeHighlight.languageFor("Main.kt"))
        assertEquals("python", CodeHighlight.languageFor("run.py"))
        assertEquals("javascript", CodeHighlight.languageFor("app.js"))
        assertEquals("text", CodeHighlight.languageFor("notes.md"))
        assertTrue(CodeHighlight.highlight("fun x = 1", "text").isEmpty())
    }
}
