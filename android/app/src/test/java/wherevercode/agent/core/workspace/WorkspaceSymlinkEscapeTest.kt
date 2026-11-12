package wherevercode.agent.core.workspace

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import wherevercode.agent.core.mcp.HandError
import wherevercode.agent.core.mcp.LocalHand

/**
 * 2026-11-13 安全加固回归：文件访问越权（符号链接 / 规范化）。
 *
 * 修前：[FileWorkspaceAdapter.resolveWorkspacePath] 只解析“已存在的父目录”，
 * 叶子是指向工作区外的悬空链接、或某级不存在目录之上的链接指向工作区外时，
 * 解析结果看起来仍在工作区内，随后的写入跟随链接落到工作区之外。
 * Hand 的 run 动作参数完全不查路径，`cat /外部文件` 可直接读工作区外。
 */
class WorkspaceSymlinkEscapeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var workspace: File
    private lateinit var taskRoot: File
    private lateinit var outside: File
    private lateinit var adapter: FileWorkspaceAdapter

    @Before
    fun setUp() {
        workspace = temp.newFolder("workspace")
        taskRoot = temp.newFolder("tasks")
        outside = temp.newFolder("outside")
        adapter = FileWorkspaceAdapter(workspace, taskRoot)
    }

    private fun assertOutside(raw: String) {
        val error = assertThrows(WorkspaceException::class.java) { adapter.resolveWorkspacePath(raw) }
        assertEquals("workspace_outside_workspace", error.code)
    }

    @Test
    fun `dangling symlink leaf to outside is rejected for relative and absolute input`() {
        Files.createSymbolicLink(File(workspace, "evil.txt").toPath(), File(outside, "planted.txt").toPath())
        assertOutside("evil.txt")
        assertOutside(File(workspace, "evil.txt").path)
    }

    @Test
    fun `existing symlink leaf to an outside file is rejected`() {
        val secret = File(outside, "secret.txt").apply { writeText("TOP SECRET") }
        Files.createSymbolicLink(File(workspace, "peek.txt").toPath(), secret.toPath())
        assertOutside("peek.txt")
    }

    @Test
    fun `symlinked directory to outside with a not-yet-existing subpath is rejected`() {
        Files.createSymbolicLink(File(workspace, "out").toPath(), outside.toPath())
        assertOutside("out/new/dir/file.txt")
    }

    @Test
    fun `hand write through a dangling symlink never lands outside`() {
        Files.createSymbolicLink(File(workspace, "evil.txt").toPath(), File(outside, "planted.txt").toPath())
        val hand = LocalHand(workspace, taskRoot, adapter)
        val error = assertThrows(HandError::class.java) {
            hand.call("write", mapOf("path" to "evil.txt", "content" to "pwned"), "task-1")
        }
        assertEquals("workspace_outside_workspace", error.code)
        assertFalse("工作区外不得出现被写入的文件", File(outside, "planted.txt").exists())
    }

    @Test
    fun `hand read through an outside symlink is refused`() {
        val secret = File(outside, "secret.txt").apply { writeText("TOP SECRET") }
        Files.createSymbolicLink(File(workspace, "peek.txt").toPath(), secret.toPath())
        val hand = LocalHand(workspace, taskRoot, adapter)
        assertThrows(HandError::class.java) {
            hand.call("read", mapOf("path" to "peek.txt"), "task-1")
        }
    }

    @Test
    fun `hand run refuses arguments that point outside the authorised folder`() {
        val secret = File(outside, "secret.txt").apply { writeText("TOP SECRET") }
        val hand = LocalHand(workspace, taskRoot, adapter)
        val error = assertThrows(HandError::class.java) {
            hand.call("run", mapOf("argv" to JSONArray(listOf("cat", secret.path)).toString()), "task-1")
        }
        assertEquals("hand_path_not_allowed", error.code)
        val dotdot = assertThrows(HandError::class.java) {
            hand.call("run", mapOf("argv" to JSONArray(listOf("cat", "../outside/secret.txt")).toString()), "task-1")
        }
        assertEquals("hand_path_not_allowed", dotdot.code)
    }

    @Test
    fun `hand run refuses find -exec before anything is spawned`() {
        val hand = LocalHand(workspace, taskRoot, adapter)
        val error = assertThrows(HandError::class.java) {
            hand.call(
                "run",
                mapOf("argv" to JSONArray(listOf("find", ".", "-exec", "touch", File(outside, "x").path, ";")).toString()),
                "task-1",
            )
        }
        assertEquals("hand_command_not_allowed", error.code)
        assertFalse(File(outside, "x").exists())
    }

    @Test
    fun `ordinary in-workspace paths still work`() {
        File(workspace, "src").mkdirs()
        val (rel, file) = adapter.resolveWorkspacePath("src/new.txt")
        assertEquals("src/new.txt", rel)
        assertTrue(file.path.startsWith(workspace.canonicalPath))
    }
}
