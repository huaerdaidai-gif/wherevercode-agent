package wherevercode.agent.brain

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.extension.ExtensionToolProvider
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * 2026-11-13 安全加固 + 异常恢复回归（ToolRegistry 层）：
 * - 文件访问越权：符号链接把 read/write 带出工作区；
 * - 命令执行权限：工作区外路径参数 / 危险参数不得免审批；
 * - 停止后写入：审批等待期间用户停止，内置与扩展工具都不得执行；
 * - 工具超时：超时的命令被杀掉并报 hand_timeout，不挂住、不留写轮次；
 * - 任务取消：运行中的命令在停止后被杀掉并报 turn_stopped。
 */
class ToolRegistrySecurityRegressionTest {

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var outside: File
    private lateinit var workspaceAdapter: FileWorkspaceAdapter

    private class AllowGate : ApprovalGate {
        var asked = 0
        override suspend fun requestApproval(action: Action): Boolean {
            asked++
            return true
        }
    }

    private class DenyGate : ApprovalGate {
        var asked = 0
        override suspend fun requestApproval(action: Action): Boolean {
            asked++
            return false
        }
    }

    /** 审批期间用户点了停止：批准返回 true，但停止标志在此刻翻起。 */
    private class StopDuringApprovalGate(private val stop: () -> Unit) : ApprovalGate {
        override suspend fun requestApproval(action: Action): Boolean {
            stop()
            return true
        }
    }

    private class RecordingExtension : ExtensionToolProvider {
        var executed = 0
        override fun toolNames(): Set<String> = setOf("mcp_remote_write")
        override fun toolDefs(): List<ToolDef> = emptyList()
        override fun isReadOnly(toolName: String): Boolean = false
        override fun needsApproval(toolName: String): Boolean = true
        override fun validate(action: Action): String? = null
        override suspend fun execute(action: Action, context: ToolContext, stopped: () -> Boolean): ToolOutcome {
            executed++
            return ToolOutcome(ok = true, summary = "remote write done")
        }
    }

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("tool-security-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        outside = File(tmpDir, "outside").apply { mkdirs() }
        workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, File(tmpDir, "tasks").apply { mkdirs() })
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun tools(extension: ExtensionToolProvider? = null) = ToolRegistry(
        deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter),
        workspaceAdapter = workspaceAdapter,
        limits = CommandLimits(),
        taskStore = FakeTaskStore(),
        auditLog = InMemoryAuditLog(),
        pythonRuntime = null,
        extensionTools = extension,
    )

    private fun context(gate: ApprovalGate) = ToolContext(
        taskId = "task-1",
        agentId = "brain",
        approvalGate = gate,
        workspaceRoot = workspaceRoot,
        deviceId = "local",
        folder = workspaceRoot.path,
    )

    private fun action(tool: String, args: Map<String, Any?>, id: String = "call-1") =
        Action(actionId = id, tool = tool, args = args, idempotencyKey = "idem-$id")

    @Test
    fun `read_file through a symlink to outside is denied`() = runTest {
        val secret = File(outside, "secret.txt").apply { writeText("TOP SECRET") }
        Files.createSymbolicLink(File(workspaceRoot, "peek.txt").toPath(), secret.toPath())
        val outcome = tools().execute(action("read_file", mapOf("path" to "peek.txt")), context(AllowGate()))
        assertFalse(outcome.ok)
        assertEquals("path_not_allowed", outcome.errorCode)
        assertFalse((outcome.data ?: "").contains("TOP SECRET"))
    }

    @Test
    fun `write_file through a dangling symlink is denied and nothing lands outside`() = runTest {
        Files.createSymbolicLink(File(workspaceRoot, "evil.txt").toPath(), File(outside, "planted.txt").toPath())
        val outcome = tools().execute(
            action("write_file", mapOf("path" to "evil.txt", "content" to "pwned")),
            context(AllowGate()),
        )
        assertFalse(outcome.ok)
        assertEquals("path_not_allowed", outcome.errorCode)
        assertFalse(File(outside, "planted.txt").exists())
    }

    @Test
    fun `read-only command with an outside path argument now asks the user`() = runTest {
        File(outside, "secret.txt").writeText("TOP SECRET")
        val gate = DenyGate()
        val outcome = tools().execute(
            action("run_command", mapOf("argv" to listOf("cat", File(outside, "secret.txt").path))),
            context(gate),
        )
        assertEquals("工作区外路径必须经用户审批", 1, gate.asked)
        assertEquals("user_denied", outcome.errorCode)
        assertFalse((outcome.data ?: "").contains("TOP SECRET"))
    }

    @Test
    fun `find -delete is refused outright and deletes nothing`() = runTest {
        val keep = File(workspaceRoot, "keep.txt").apply { writeText("keep") }
        val gate = AllowGate()
        val outcome = tools().execute(
            action("run_command", mapOf("argv" to listOf("find", ".", "-name", "keep.txt", "-delete"))),
            context(gate),
        )
        assertFalse(outcome.ok)
        assertEquals("hand_command_not_allowed", outcome.errorCode)
        assertEquals(0, gate.asked)
        assertTrue(keep.exists())
    }

    @Test
    fun `stop pressed during approval prevents the write`() = runTest {
        var stopped = false
        val outcome = tools().execute(
            action("write_file", mapOf("path" to "late.txt", "content" to "late")),
            context(StopDuringApprovalGate { stopped = true }),
            stopped = { stopped },
        )
        assertEquals("turn_stopped", outcome.errorCode)
        assertFalse("停止后不得再写文件", File(workspaceRoot, "late.txt").exists())
        assertEquals(null, workspaceAdapter.activeRound())
    }

    @Test
    fun `stop pressed during approval prevents an extension (MCP) tool call`() = runTest {
        var stopped = false
        val extension = RecordingExtension()
        val outcome = tools(extension).execute(
            action("mcp_remote_write", mapOf("x" to "y")),
            context(StopDuringApprovalGate { stopped = true }),
            stopped = { stopped },
        )
        assertEquals("turn_stopped", outcome.errorCode)
        assertEquals("停止后扩展工具不得执行", 0, extension.executed)
    }

    @Test
    fun `tool timeout kills the command and releases the write round`() = runTest {
        val started = System.nanoTime()
        val outcome = tools().execute(
            action("run_command", mapOf("argv" to listOf("sleep", "20"), "timeout" to 1)),
            context(AllowGate()),
        )
        val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertFalse(outcome.ok)
        assertEquals("hand_timeout", outcome.errorCode)
        assertTrue("超时必须及时返回，实际 $elapsedSeconds 秒", elapsedSeconds < 15)
        assertEquals("超时后写轮次必须释放", null, workspaceAdapter.activeRound())
    }

    @Test
    fun `cancelling a running command kills it and reports turn_stopped`() = runTest {
        val deadline = System.currentTimeMillis() + 500
        val started = System.nanoTime()
        val outcome = tools().execute(
            action("run_command", mapOf("argv" to listOf("sleep", "20"), "timeout" to 60)),
            context(AllowGate()),
            stopped = { System.currentTimeMillis() > deadline },
        )
        val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertEquals("turn_stopped", outcome.errorCode)
        assertTrue("停止后命令必须被杀掉，实际 $elapsedSeconds 秒", elapsedSeconds < 15)
        assertEquals(null, workspaceAdapter.activeRound())
    }
}
