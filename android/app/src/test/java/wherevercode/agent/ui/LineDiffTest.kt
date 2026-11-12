package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 行级 diff（阶段 3，U2）：LCS 行对齐、行号、增删计数、二进制回退。 */
class LineDiffTest {

    @Test
    fun `identical texts are all context`() {
        val diff = LineDiff.diff("a.kt", "x\ny\n", "x\ny\n")
        assertEquals(0, diff.insertions)
        assertEquals(0, diff.deletions)
        assertFalse(diff.binary)
        assertTrue(diff.lines.all { it.kind == DiffLineKind.CONTEXT })
        assertEquals(listOf(1, 2), diff.lines.map { it.oldLine })
        assertEquals(listOf(1, 2), diff.lines.map { it.newLine })
    }

    @Test
    fun `single changed line is one delete plus one add with true line numbers`() {
        val diff = LineDiff.diff("a.kt", "a\nb\nc\n", "a\nB\nc\n")
        assertEquals(1, diff.insertions)
        assertEquals(1, diff.deletions)
        val deleted = diff.lines.filter { it.kind == DiffLineKind.DELETE }
        val added = diff.lines.filter { it.kind == DiffLineKind.ADD }
        assertEquals(listOf(2), deleted.map { it.oldLine })
        assertEquals(listOf(2), added.map { it.newLine })
        assertEquals("b", deleted.single().text)
        assertEquals("B", added.single().text)
    }

    @Test
    fun `appended lines are adds at the end`() {
        val diff = LineDiff.diff("a.kt", "a\n", "a\nb\nc\n")
        assertEquals(2, diff.insertions)
        assertEquals(0, diff.deletions)
        assertEquals(listOf(2, 3), diff.lines.filter { it.kind == DiffLineKind.ADD }.map { it.newLine })
    }

    @Test
    fun `removed lines are deletes`() {
        val diff = LineDiff.diff("a.kt", "a\nb\nc\n", "a\n")
        assertEquals(0, diff.insertions)
        assertEquals(2, diff.deletions)
        assertEquals(listOf("b", "c"), diff.lines.filter { it.kind == DiffLineKind.DELETE }.map { it.text })
    }

    @Test
    fun `empty old text is all adds and empty new text is all deletes`() {
        val added = LineDiff.diff("a.kt", "", "x\ny\n")
        assertEquals(2, added.insertions)
        assertEquals(0, added.deletions)
        val deleted = LineDiff.diff("a.kt", "x\ny\n", "")
        assertEquals(0, deleted.insertions)
        assertEquals(2, deleted.deletions)
    }

    @Test
    fun `nul byte means binary with no lines`() {
        val diff = LineDiff.diff("a.bin", "a\u0000b", "a\u0000c")
        assertTrue(diff.binary)
        assertTrue(diff.lines.isEmpty())
        assertEquals(0, diff.insertions)
        assertEquals(0, diff.deletions)
    }

    @Test
    fun `moved block keeps context around it`() {
        val diff = LineDiff.diff("a.kt", "a\nb\nc\nd\n", "a\nc\nb\nd\n")
        assertEquals(1, diff.insertions)
        assertEquals(1, diff.deletions)
        // LCS 对齐：a、c、d 为上下文，b 先删后增。
        assertEquals(3, diff.lines.count { it.kind == DiffLineKind.CONTEXT })
    }

    @Test
    fun `huge differing middle falls back to delete-all plus add-all without hanging`() {
        val old = (1..400).joinToString("\n") { "old-$it" }
        val new = (1..400).joinToString("\n") { "new-$it" }
        val diff = LineDiff.diff("big.kt", old, new)
        assertEquals(400, diff.insertions)
        assertEquals(400, diff.deletions)
    }
}
