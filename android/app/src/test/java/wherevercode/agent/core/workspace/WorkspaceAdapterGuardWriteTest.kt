package wherevercode.agent.core.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import wherevercode.agent.core.mcp.LocalHand

/**
 * V5 C1 W1 guardWrite 根因回归：
 * [FileWorkspaceAdapter.resolveWorkspacePath] 对绝对路径输入曾按完整
 * 输入分段拼 rel（如 "data/.../workspace/sub/a.txt"），备份落在
 * rounds/<id>/data/... 嵌套目录；而 [FileWorkspaceAdapter.undo] 按
 * manifest 的工作区相对路径（"sub/a.txt"）找备份，找不到就报
 * notRestored。修后 rel 一律按目标文件相对工作区根目录算。
 *
 * 覆盖：绝对路径改文件 undo 恢复（M）、绝对路径新增文件 undo 删除
 * （A）、绝对路径删文件 undo 恢复（D）、相对路径行为不变、工作区外
 * 拒绝、根目录本身、备份落点、以及 Hand（LocalHand）绝对路径写轮次。
 * ToolRegistry 层（绝对路径 File 进 guardWrite）的用例见
 * RoundLifecycleRegressionTest。
 */
class WorkspaceAdapterGuardWriteTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var workspace: File
    private lateinit var taskRoot: File
    private lateinit var adapter: FileWorkspaceAdapter

    @Before
    fun setUp() {
        workspace = temp.newFolder("workspace")
        taskRoot = temp.newFolder("tasks")
        adapter = FileWorkspaceAdapter(workspace, taskRoot)
    }

    private fun writeFile(rel: String, content: String): File =
        File(workspace, rel).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun startRoundAndGuard(file: File): Pair<String, GuardWriteResult> {
        val ref = adapter.beginRound(taskId = "task-1")
        adapter.activateForWrite()
        return ref.roundId to adapter.guardWrite(file)
    }

    @Test
    fun `absolute path resolves to workspace-relative path`() {
        val target = writeFile("sub/a.txt", "OLD\n")

        val (rel, file) = adapter.resolveWorkspacePath(target.path)

        assertEquals("sub/a.txt", rel)
        assertEquals(target.canonicalFile, file)
    }

    @Test
    fun `relative path resolves unchanged`() {
        val (rel, file) = adapter.resolveWorkspacePath("sub/a.txt")

        assertEquals("sub/a.txt", rel)
        assertEquals(File(workspace, "sub/a.txt").canonicalFile, file)
    }

    @Test
    fun `absolute path with dot segments resolves to workspace-relative path`() {
        val (rel, _) = adapter.resolveWorkspacePath(File(workspace, "sub/./a.txt").path)

        assertEquals("sub/a.txt", rel)
    }

    @Test
    fun `paths outside the workspace are rejected`() {
        val outside = temp.newFile("outside.txt")

        val absolute = assertThrows(WorkspaceException::class.java) {
            adapter.resolveWorkspacePath(outside.path)
        }
        assertEquals("workspace_outside_workspace", absolute.code)

        val dotDot = assertThrows(WorkspaceException::class.java) {
            adapter.resolveWorkspacePath("../outside.txt")
        }
        assertEquals("workspace_outside_workspace", dotDot.code)

        val system = assertThrows(WorkspaceException::class.java) {
            adapter.resolveWorkspacePath("/etc/passwd")
        }
        assertEquals("workspace_outside_workspace", system.code)
    }

    @Test
    fun `workspace root itself resolves to empty relative path`() {
        val (rel, _) = adapter.resolveWorkspacePath(workspace.path)

        assertEquals("", rel)
    }

    @Test
    fun `absolute path guardWrite then undo restores a modified file`() {
        val file = writeFile("sub/a.txt", "OLD\n")
        val (roundId, guard) = startRoundAndGuard(file)
        assertEquals("sub/a.txt", guard.path)
        assertTrue("既存文件必须先备份", guard.backedUp)

        // 备份必须落在 rounds/<id>/sub/a.txt，而不是绝对路径嵌套目录
        //（修前是 rounds/<id>/tmp/.../workspace/sub/a.txt）。
        val backupDir = adapter.roundDir(roundId)
        assertTrue(File(backupDir, "sub/a.txt").isFile)
        val firstSegment = workspace.canonicalPath.split("/").filter { it.isNotEmpty() }.first()
        assertFalse("备份不得按绝对路径嵌套存放", File(backupDir, firstSegment).exists())

        file.writeText("NEW\n")
        val changes = adapter.finishRound(roundId)
        assertEquals(listOf(ChangedFile("sub/a.txt", ChangeKind.M)), changes.files)
        assertEquals("NEW\n", file.readText())

        val undo = adapter.undo(roundId, confirm = true)
        assertEquals(listOf("sub/a.txt"), undo.restored)
        assertTrue("undo 不得有未恢复文件：${undo.notRestored}", undo.notRestored.isEmpty())
        assertEquals("OLD\n", file.readText())
    }

    @Test
    fun `absolute path guardWrite then undo deletes an added file`() {
        val file = File(workspace, "sub/new.txt")
        val (roundId, guard) = startRoundAndGuard(file)
        assertEquals("sub/new.txt", guard.path)
        assertFalse("新增文件无旧内容可备份", guard.backedUp)

        file.parentFile?.mkdirs()
        file.writeText("NEW\n")
        val changes = adapter.finishRound(roundId)
        assertEquals(listOf(ChangedFile("sub/new.txt", ChangeKind.A)), changes.files)

        val undo = adapter.undo(roundId, confirm = true)
        assertEquals(listOf("sub/new.txt"), undo.deleted)
        assertTrue("undo 不得有未恢复文件：${undo.notRestored}", undo.notRestored.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `relative path guardWrite then undo still restores`() {
        val file = writeFile("sub/a.txt", "OLD\n")
        // 与 WorkspaceEdit 相同写法：传相对路径 File。
        val (roundId, guard) = startRoundAndGuard(File("sub/a.txt"))
        assertEquals("sub/a.txt", guard.path)
        assertTrue(guard.backedUp)

        file.writeText("NEW\n")
        adapter.finishRound(roundId)

        val undo = adapter.undo(roundId, confirm = true)
        assertEquals(listOf("sub/a.txt"), undo.restored)
        assertTrue("undo 不得有未恢复文件：${undo.notRestored}", undo.notRestored.isEmpty())
        assertEquals("OLD\n", file.readText())
    }

    @Test
    fun `absolute path guardWrite then undo restores a deleted file`() {
        val file = writeFile("sub/a.txt", "OLD\n")
        val (roundId, _) = startRoundAndGuard(file)

        assertTrue(file.delete())
        val changes = adapter.finishRound(roundId)
        assertEquals(listOf(ChangedFile("sub/a.txt", ChangeKind.D)), changes.files)

        val undo = adapter.undo(roundId, confirm = true)
        assertEquals(listOf("sub/a.txt"), undo.restored)
        assertTrue("undo 不得有未恢复文件：${undo.notRestored}", undo.notRestored.isEmpty())
        assertEquals("OLD\n", file.readText())
    }

    @Test
    fun `guardWrite rejects the workspace root itself`() {
        val ref = adapter.beginRound(taskId = "task-1")
        adapter.activateForWrite()

        val ex = assertThrows(WorkspaceException::class.java) {
            adapter.guardWrite(workspace)
        }
        assertEquals("workspace_invalid_path", ex.code)
        assertEquals(ref.roundId, adapter.activeRound()?.roundId)
    }

    @Test
    fun `LocalHand absolute path write then undo restores`() {
        val file = writeFile("sub/hand.txt", "OLD\n")
        val hand = LocalHand(workspace, taskRoot, adapter)

        val result = hand.call(
            "write",
            mapOf("path" to file.path, "content" to "NEW\n"),
            "task-hand",
        )
        assertEquals("sub/hand.txt", result["path"])
        assertEquals("NEW\n", file.readText())
        hand.finish()

        val undo = adapter.undo(confirm = true)
        assertEquals(listOf("sub/hand.txt"), undo.restored)
        assertTrue("undo 不得有未恢复文件：${undo.notRestored}", undo.notRestored.isEmpty())
        assertEquals("OLD\n", file.readText())
    }
}
