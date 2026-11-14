package wherevercode.agent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.ToolRegistry
import wherevercode.agent.core.workspace.WorkspaceGuard

/**
 * 2026-11-13 安全加固回归：命令执行权限。
 * 白名单只看 argv[0] 时，白名单命令本身的参数就能执行任意程序或写任意
 * 文件；只读判定只看 argv[0] 时，危险命令能免审批运行。
 */
class CommandHardeningTest {

    private val limits = CommandLimits()

    private fun refused(vararg argv: String) {
        val error = assertThrows(LimitError::class.java) { limits.checkCommand(argv.toList()) }
        assertEquals("hand_command_not_allowed", error.code)
        assertFalse(validateCommand(argv.toList()).first)
    }

    private fun allowed(vararg argv: String) {
        limits.checkCommand(argv.toList())
        assertTrue(validateCommand(argv.toList()).first)
    }

    @Test
    fun `find actions that execute or delete are refused`() {
        refused("find", ".", "-exec", "sh", "-c", "id", ";")
        refused("find", ".", "-execdir", "rm", "{}", ";")
        refused("find", ".", "-delete")
        refused("find", ".", "-ok", "rm", "{}", ";")
        refused("find", ".", "-fprint", "/tmp/out")
        allowed("find", ".", "-name", "*.kt")
    }

    @Test
    fun `git read-only subcommands cannot write files or run external programs`() {
        refused("git", "diff", "--output=/tmp/x")
        refused("git", "log", "--output", "/tmp/x")
        refused("git", "diff", "--ext-diff")
        refused("git", "show", "--textconv", "HEAD:a")
        allowed("git", "log", "-c", "--oneline")
        allowed("git", "diff", "--no-ext-diff", "--stat")
        allowed("git", "diff", "--output-indicator-new=+")
        refused("git", "push")
    }

    @Test
    fun `python inline code is refused in every short-option spelling`() {
        refused("python3", "-c", "print(1)")
        refused("python3", "-cprint(1)")
        refused("python3", "-uc", "print(1)")
        refused("python", "-B", "-c", "print(1)")
        refused("python3", "-W", "ignore", "-uc", "print(1)")
        allowed("python3", "-Wonce", "script.py")
        allowed("python3", "-m", "unittest", "discover")
    }

    @Test
    fun `node eval and print are refused`() {
        refused("node", "-e", "1")
        refused("node", "-p", "1")
        refused("node", "-pe", "1")
        refused("node", "--eval=1")
        refused("node", "--print", "1")
        allowed("node", "--test")
    }

    @Test
    fun `dangerousArgumentReason is null for ordinary commands`() {
        assertNull(dangerousArgumentReason(listOf("ls", "-la")))
        assertNull(dangerousArgumentReason(listOf("grep", "-rn", "TODO", "src")))
    }

    @Test
    fun `read-only auto approval no longer covers destructive or escaping commands`() {
        assertTrue(ToolRegistry.isReadOnlyRun(listOf("ls", "-la")))
        assertTrue(ToolRegistry.isReadOnlyRun(listOf("cat", "src/a.txt")))
        assertTrue(ToolRegistry.isReadOnlyRun(listOf("git", "status")))
        assertTrue(ToolRegistry.isReadOnlyRun(listOf("find", ".", "-name", "*.kt")))

        assertFalse(ToolRegistry.isReadOnlyRun(listOf("find", ".", "-delete")))
        assertFalse(ToolRegistry.isReadOnlyRun(listOf("find", ".", "-exec", "rm", "{}", ";")))
        assertFalse(ToolRegistry.isReadOnlyRun(listOf("git", "push")))
        assertFalse(ToolRegistry.isReadOnlyRun(listOf("git", "reset", "--hard")))
        assertFalse(ToolRegistry.isReadOnlyRun(listOf("git", "diff", "--output=/tmp/x")))
        assertFalse(ToolRegistry.isReadOnlyRun(listOf("cat", "/data/data/wherevercode.agent/shared_prefs/x.xml")))
        assertFalse(ToolRegistry.isReadOnlyRun(listOf("cat", "../../secret")))
        assertFalse(ToolRegistry.isReadOnlyRun(listOf("tail", "~/.ssh/id_rsa")))
    }

    @Test
    fun `workspace guard treats git output and ext-diff as may-write`() {
        assertTrue(WorkspaceGuard.commandMayWrite("git diff --output=x.patch"))
        assertTrue(WorkspaceGuard.commandMayWrite("git log --ext-diff"))
        assertFalse(WorkspaceGuard.commandMayWrite("git status"))
    }
}
