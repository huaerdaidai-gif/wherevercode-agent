package wherevercode.agent.brain

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * 2026-11-09 写轮次泄漏回归（K70 workspace_round_active / hand_run_failed）。
 *
 * 根因：ToolRegistry 每个 taskId 只 beginRound，从不 finishRound，轮次在
 * 进程生命周期内永久活动；第二轮（新会话 = 新 taskId）写文件被拒，
 * run_command 又被兜底改写成 hand_run_failed。
 *
 * 本文件锁定：正常结束、审批拒绝、写失败、命令取消、遗留回收、
 * 外部属主并发保护六条路径，以及重构后的机器码不再被吞。
 */
class RoundLifecycleRegressionTest {

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var taskRoot: File
    private lateinit var workspaceAdapter: FileWorkspaceAdapter
    private lateinit var tools: ToolRegistry
    private val roundEvents = mutableListOf<Pair<String, String>>()

    private class AllowGate : ApprovalGate {
        override suspend fun requestApproval(action: Action): Boolean = true
    }

    private class RevokeAfterApprovalGate : ApprovalGate {
        override suspend fun requestApproval(action: Action): Boolean = true
        override fun isApprovalStillValid(action: Action): Boolean = false
    }

    private class DeleteWorkspaceOnApprovalGate(private val workspace: File) : ApprovalGate {
        override suspend fun requestApproval(action: Action): Boolean {
            workspace.deleteRecursively()
            return true
        }
    }

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("round-lifecycle-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        taskRoot = File(tmpDir, "tasks").apply { mkdirs() }
        workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        tools = ToolRegistry(
            deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter),
            workspaceAdapter = workspaceAdapter,
            limits = CommandLimits(),
            taskStore = FakeTaskStore(),
            auditLog = InMemoryAuditLog(),
            pythonRuntime = null,
            onRoundEvent = { kind, detail -> roundEvents += kind to detail },
        )
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun context(gate: ApprovalGate, taskId: String = "task-1") = ToolContext(
        taskId = taskId,
        agentId = "brain",
        approvalGate = gate,
        workspaceRoot = workspaceRoot,
        deviceId = "local",
        folder = workspaceRoot.path,
    )

    private fun writeAction(path: String, content: String, id: String = "call-1") = Action(
        actionId = id,
        tool = "write_file",
        args = mapOf("path" to path, "content" to content),
        idempotencyKey = "idem-$id",
    )

    private fun runAction(argv: List<String>, id: String = "call-run") = Action(
        actionId = id,
        tool = "run_command",
        args = mapOf("argv" to argv, "timeout" to 30),
        idempotencyKey = "idem-$id",
    )

    @Test
    fun `write round is released after success and a second task can write`() = runTest {
        val first = tools.execute(writeAction("agent-test/one.txt", "ONE"), context(AllowGate()))
        assertTrue(first.summary, first.ok)
        assertNull("写轮次必须在正常结束后释放", workspaceAdapter.activeRound())

        val second = tools.execute(
            writeAction("agent-test/two.txt", "TWO", id = "call-2"),
            context(AllowGate(), taskId = "task-2"),
        )
        assertTrue("第二个任务不应被旧轮次阻塞：${second.summary}", second.ok)
        assertEquals("TWO", File(workspaceRoot, "agent-test/two.txt").readText())
        assertNull(workspaceAdapter.activeRound())
    }

    @Test
    fun `write_file round can be undone and restores the original content`() = runTest {
        val file = File(workspaceRoot, "agent-test/one.txt").apply {
            parentFile?.mkdirs()
            writeText("OLD")
        }
        val written = tools.execute(writeAction("agent-test/one.txt", "NEW"), context(AllowGate()))
        assertTrue(written.summary, written.ok)
        assertEquals("NEW", file.readText())

        // ToolRegistry 传给 guardWrite 的是绝对路径 File；修前备份落在
        // rounds/<id>/tmp/... 嵌套目录，undo 会把该文件列进 notRestored。
        val undo = workspaceAdapter.undo()
        assertEquals(listOf("agent-test/one.txt"), undo.restored)
        assertTrue("undo 不得有未恢复文件：${undo.notRestored}", undo.notRestored.isEmpty())
        assertEquals("OLD", file.readText())
    }

    @Test
    fun `approval denial never opens a round and never writes`() = runTest {
        val denied = tools.execute(writeAction("agent-test/no.txt", "NO"), context(FakeApprovalGate(allow = false)))
        assertEquals("user_denied", denied.errorCode)
        assertFalse(File(workspaceRoot, "agent-test/no.txt").exists())
        assertNull(workspaceAdapter.activeRound())
    }

    @Test
    fun `write failure still releases the round`() = runTest {
        File(workspaceRoot, "blocked").mkdirs()
        val failed = tools.execute(writeAction("blocked", "X"), context(AllowGate()))
        assertFalse(failed.ok)
        assertEquals("hand_write_failed", failed.errorCode)
        assertNull("写入失败也必须释放轮次", workspaceAdapter.activeRound())

        val next = tools.execute(
            writeAction("agent-test/after.txt", "AFTER", id = "call-2"),
            context(AllowGate(), taskId = "task-2"),
        )
        assertTrue(next.summary, next.ok)
    }

    @Test
    fun `read-only command takes no write round while a hand round is active`() = runTest {
        val hand = workspaceAdapter.beginRound(taskId = "hand-task", owner = "hand")
        val echo = tools.execute(runAction(listOf("echo", "hi")), context(AllowGate()))
        assertTrue(echo.summary, echo.ok)
        assertEquals("只读命令不得占用/改动轮次", hand.roundId, workspaceAdapter.activeRound()?.roundId)

        // 写类命令必须尊重 hand 的并发保护：报 workspace_round_active，
        // 而不是旧实现里被兜底吞成的 hand_run_failed。
        val sleep = tools.execute(runAction(listOf("sleep", "0"), id = "call-2"), context(AllowGate()))
        assertFalse(sleep.ok)
        assertEquals("workspace_round_active", sleep.errorCode)
        assertEquals(hand.roundId, workspaceAdapter.activeRound()?.roundId)
        assertTrue(workspaceAdapter.abandonRound(hand.roundId, "test cleanup"))
    }

    @Test
    fun `stale brain-owned round is reclaimed instead of blocking forever`() = runTest {
        val stale = workspaceAdapter.beginRound(taskId = "previous-turn", owner = ToolRegistry.ROUND_OWNER)
        assertNotNull(workspaceAdapter.activeRound())

        val outcome = tools.execute(
            writeAction("agent-test/recovered.txt", "OK"),
            context(AllowGate(), taskId = "new-turn"),
        )
        assertTrue("遗留轮次应被回收，而不是永久阻塞：${outcome.summary}", outcome.ok)
        assertNull(workspaceAdapter.activeRound())
        assertTrue(roundEvents.any { it.first == "workspace_round_reclaimed" })
        assertTrue(roundEvents.any { it.first == "workspace_round_finish" })
        assertTrue(roundEvents.none { it.first == "workspace_round_begin" && stale.roundId in it.second })
    }

    @Test
    fun `cancellation during a running command releases the round`() = runTest {
        var checks = 0
        val outcome = tools.execute(
            runAction(listOf("sleep", "10")),
            context(AllowGate()),
            stopped = { checks++ > 1 },
        )
        assertEquals("turn_stopped", outcome.errorCode)
        assertNull("取消后必须释放轮次", workspaceAdapter.activeRound())
    }

    @Test
    fun `approval revoked after allow prevents execution`() = runTest {
        val outcome = tools.execute(
            writeAction("agent-test/revoked.txt", "REVOKED"),
            context(RevokeAfterApprovalGate()),
        )
        assertEquals("approval_revoked", outcome.errorCode)
        assertFalse(File(workspaceRoot, "agent-test/revoked.txt").exists())
        assertNull(workspaceAdapter.activeRound())
    }

    @Test
    fun `post-approval permission recheck blocks execution`() = runTest {
        val outcome = tools.execute(
            writeAction("agent-test/gone.txt", "GONE"),
            context(DeleteWorkspaceOnApprovalGate(workspaceRoot)),
        )
        assertEquals("workspace_not_configured", outcome.errorCode)
        assertNull(workspaceAdapter.activeRound())
    }

    @Test
    fun `startup recovery removes marked stale rounds and keeps unknown dirs`() = runTest {
        val roundsDir = File(taskRoot, "rounds").apply { mkdirs() }
        val staleDir = File(roundsDir, "stale-round").apply { mkdirs() }
        File(staleDir, "round.json").writeText("{}")
        val unknownDir = File(roundsDir, "user-data").apply { mkdirs() }
        File(unknownDir, "keep.txt").writeText("keep")

        val restarted = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        val report = restarted.recoverStaleRounds()
        assertTrue(report.error ?: "", report.lockAvailable)
        assertTrue(report.removedRoundIds.contains("stale-round"))
        assertTrue(report.keptRoundIds.contains("user-data"))
        assertFalse(staleDir.exists())
        assertTrue(File(unknownDir, "keep.txt").isFile)
    }

    @Test
    fun `abandonRound only reclaims the named round`() = runTest {
        val current = workspaceAdapter.beginRound(taskId = "t", owner = ToolRegistry.ROUND_OWNER)
        assertFalse(workspaceAdapter.abandonRound("not-this-round", "test"))
        assertNotNull(workspaceAdapter.activeRound())
        assertTrue(workspaceAdapter.abandonRound(current.roundId, "test"))
        assertNull(workspaceAdapter.activeRound())
    }
}
