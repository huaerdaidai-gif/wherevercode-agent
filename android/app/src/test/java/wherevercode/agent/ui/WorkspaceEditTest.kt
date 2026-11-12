package wherevercode.agent.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import wherevercode.agent.core.workspace.ChangeKind
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * 项目页编辑（阶段 3，U2）：写轮次保存、二次确认门禁、diff、
 * undo/keep 真实生效。纯 JVM（FileWorkspaceAdapter 只用 java.io）。
 */
class WorkspaceEditTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun session(): WorkspaceEditSession {
        val workspace = temp.newFolder("workspace")
        val tasks = temp.newFolder("tasks")
        return WorkspaceEditSession(FileWorkspaceAdapter(workspace, tasks), WorkspaceBrowser(workspace))
    }

    private fun workspaceFile(session: WorkspaceEditSession, name: String, content: String): File {
        // 会话的浏览器根目录与适配器工作区是同一个 temp 文件夹。
        val root = temp.root.listFiles()?.firstOrNull { it.name == "workspace" }
            ?: throw IllegalStateException("no workspace dir")
        return File(root, name).apply { writeText(content) }
    }

    @Test
    fun `save writes through a round and records the change`() {
        val s = session()
        val file = workspaceFile(s, "hello.py", "print(1)\n")
        s.open("hello.py")
        s.updateDraft("print(1)\nprint(2)\n")

        val result = s.save(confirm = true)

        assertEquals("print(1)\nprint(2)\n", file.readText())
        assertEquals(1, result.changeSet.files.size)
        assertEquals(ChangeKind.M, result.changeSet.files.single().change)
        assertEquals(1, result.diff.insertions)
        assertEquals(0, result.diff.deletions)
    }

    @Test
    fun `save without confirmation refuses to write`() {
        val s = session()
        val file = workspaceFile(s, "a.txt", "old\n")
        s.open("a.txt")
        s.updateDraft("new\n")

        assertThrows(IllegalArgumentException::class.java) { s.save(confirm = false) }
        assertEquals("old\n", file.readText())
    }

    @Test
    fun `save with no changes opens no round and leaves the file alone`() {
        val s = session()
        val file = workspaceFile(s, "a.txt", "same\n")
        s.open("a.txt")
        s.updateDraft("same\n")

        assertFalse(s.hasChanges())
        val result = s.save(confirm = true)
        assertTrue(result.changeSet.files.isEmpty())
        assertEquals("same\n", file.readText())
    }

    @Test
    fun `diff reflects the draft before saving`() {
        val s = session()
        workspaceFile(s, "a.txt", "a\nb\nc\n")
        s.open("a.txt")
        s.updateDraft("a\nB\nc\nd\n")

        val diff = s.diff()
        assertEquals("a.txt", diff?.path)
        assertEquals(2, diff?.insertions)
        assertEquals(1, diff?.deletions)
    }

    @Test
    fun `undo restores the original content`() {
        val s = session()
        val file = workspaceFile(s, "a.txt", "one\ntwo\n")
        s.open("a.txt")
        s.updateDraft("one\nTWO\nthree\n")
        s.save(confirm = true)
        assertEquals("one\nTWO\nthree\n", file.readText())

        val result = s.undo()

        assertEquals(listOf("a.txt"), result.restored)
        assertEquals("one\ntwo\n", file.readText())
    }

    @Test
    fun `keep marks the round kept and clears the undo handle`() {
        val s = session()
        workspaceFile(s, "a.txt", "one\n")
        s.open("a.txt")
        s.updateDraft("one\ntwo\n")
        s.save(confirm = true)

        val result = s.keep()

        assertTrue(result.kept)
        assertThrows(IllegalStateException::class.java) { s.undo() }
    }

    @Test
    fun `second save diffs against the new original`() {
        val s = session()
        val file = workspaceFile(s, "a.txt", "1\n")
        s.open("a.txt")
        s.updateDraft("1\n2\n")
        s.save(confirm = true)
        s.updateDraft("1\n2\n3\n")

        val second = s.save(confirm = true)

        assertEquals(1, second.diff.insertions)
        assertEquals("1\n2\n3\n", file.readText())
    }

    @Test
    fun `opening a missing file leaves no session to save`() {
        val s = session()
        val read = s.open("nope.txt")

        assertEquals(WorkspaceReadKind.MISSING, read.kind)
        assertThrows(IllegalStateException::class.java) { s.save(confirm = true) }
    }
}
