package wherevercode.agent.core.autonomy

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.control.ControlAuditEntry
import wherevercode.agent.core.control.InMemoryControlAuditLog

/**
 * 自主度三档 + 门（maintainer 2026-11-11 22:43，两种模式），Robolectric。
 *
 * 钉死的语义：
 * - 默认 = 监督模式（审批门全开），与 update9 之前行为一致；
 * - 全权模式四道门全自动批，但返工上限 2 轮一到就升级给用户；
 * - 派发跟主动性走，不跟 L 级；写操作跟 L 级走，只读永远自动；
 * - 自动决定记审计（actor = 大脑总管），只读与“门不管”的工具不记；
 * - 模式切换实时生效（门每次决策重读档位），已批过的决定不动
 *   （本类不测 ApprovalBroker，见门注释）。
 */
@RunWith(RobolectricTestRunner::class)
class AutonomyTest {
    private val now = 1_700_000_000L
    private lateinit var store: AutonomyStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("test_autonomy", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = AutonomyStore(context, "test_autonomy")
    }

    @After
    fun tearDown() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("test_autonomy", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun gate(): Pair<DefaultAutonomyGate, InMemoryControlAuditLog> {
        val log = InMemoryControlAuditLog()
        return DefaultAutonomyGate(store, log, nowEpochSeconds = { now }) to log
    }

    // -- 默认值：监督模式，门全开 ------------------------------------------

    @Test
    fun `defaults are supervision mode with all gates asking`() {
        val settings = store.loadAll()
        assertEquals(UiFreedom.STANDARD, settings.uiFreedom)
        assertEquals(Initiative.SUGGEST, settings.initiative)
        assertEquals(ExecutionLevel.L0, settings.executionLevel)
        assertEquals(AutonomyMode.SUPERVISED, store.currentMode())
        assertTrue(AutonomyPolicy.planApprovalRequired(settings.initiative))
        assertTrue(AutonomyPolicy.dispatchConfirmationRequired(settings.initiative))
        assertTrue(AutonomyPolicy.verdictApprovalRequired(settings.initiative, 0))
    }

    @Test
    fun `illegal stored values fall back to defaults`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("test_autonomy", Context.MODE_PRIVATE).edit()
            .putInt(AutonomyStore.KEY_INITIATIVE, 99)
            .putInt(AutonomyStore.KEY_EXECUTION_LEVEL, -1)
            .putInt(AutonomyStore.KEY_UI_FREEDOM, 7)
            .commit()
        assertEquals(Initiative.SUGGEST, store.loadInitiative())
        assertEquals(ExecutionLevel.L0, store.loadExecutionLevel())
        assertEquals(UiFreedom.STANDARD, store.loadUiFreedom())
    }

    @Test
    fun `applyMode writes presets and currentMode follows`() {
        store.applyMode(AutonomyMode.FULL_AUTO)
        assertEquals(Initiative.HIGH_AUTO, store.loadInitiative())
        assertEquals(ExecutionLevel.L4, store.loadExecutionLevel())
        assertEquals(AutonomyMode.FULL_AUTO, store.currentMode())
        store.applyMode(AutonomyMode.SUPERVISED)
        assertEquals(Initiative.SUGGEST, store.loadInitiative())
        assertEquals(ExecutionLevel.L0, store.loadExecutionLevel())
        assertEquals(AutonomyMode.SUPERVISED, store.currentMode())
    }

    @Test
    fun `modeOf needs both initiative and level high`() {
        assertEquals(AutonomyMode.SUPERVISED, AutonomyPolicy.modeOf(Initiative.HIGH_AUTO, ExecutionLevel.L0))
        assertEquals(AutonomyMode.SUPERVISED, AutonomyPolicy.modeOf(Initiative.SUGGEST, ExecutionLevel.L4))
        assertEquals(AutonomyMode.FULL_AUTO, AutonomyPolicy.modeOf(Initiative.LIMITED_AUTO, ExecutionLevel.L3))
        assertEquals(AutonomyMode.FULL_AUTO, AutonomyPolicy.modeOf(Initiative.HIGH_AUTO, ExecutionLevel.L4))
    }

    // -- 写操作梯子 ---------------------------------------------------------

    @Test
    fun `reads are always automatic regardless of level`() {
        for (tool in listOf("read_file", "list_files", "list_github_files", "read_github_file", "ask_user")) {
            assertEquals(false, AutonomyPolicy.writeNeedsApproval(tool, ExecutionLevel.L0, false))
        }
    }

    @Test
    fun `write ladder by level`() {
        // write_file：L1 起放行。
        assertEquals(true, AutonomyPolicy.writeNeedsApproval("write_file", ExecutionLevel.L0, false))
        assertEquals(false, AutonomyPolicy.writeNeedsApproval("write_file", ExecutionLevel.L1, false))
        // git 家写文件与建分支：L2 起放行。
        assertEquals(true, AutonomyPolicy.writeNeedsApproval("write_github_file", ExecutionLevel.L1, false))
        assertEquals(false, AutonomyPolicy.writeNeedsApproval("write_github_file", ExecutionLevel.L2, false))
        assertEquals(true, AutonomyPolicy.writeNeedsApproval("create_github_branch", ExecutionLevel.L1, false))
        assertEquals(false, AutonomyPolicy.writeNeedsApproval("create_github_branch", ExecutionLevel.L2, false))
        // 开 PR：L3 起放行。
        assertEquals(true, AutonomyPolicy.writeNeedsApproval("create_github_pr", ExecutionLevel.L2, false))
        assertEquals(false, AutonomyPolicy.writeNeedsApproval("create_github_pr", ExecutionLevel.L3, false))
        // 非只读 run_command：L3 起放行；只读命令永远自动。
        assertEquals(true, AutonomyPolicy.writeNeedsApproval("run_command", ExecutionLevel.L2, false))
        assertEquals(false, AutonomyPolicy.writeNeedsApproval("run_command", ExecutionLevel.L3, false))
        assertEquals(false, AutonomyPolicy.writeNeedsApproval("run_command", ExecutionLevel.L0, true))
    }

    @Test
    fun `dispatch and unknown tools are not decided by the level ladder`() {
        assertNull(AutonomyPolicy.writeNeedsApproval("dispatch_task", ExecutionLevel.L4, false))
        assertNull(AutonomyPolicy.writeNeedsApproval("some_extension_tool", ExecutionLevel.L4, false))
    }

    // -- 门：监督模式等用户 --------------------------------------------------

    @Test
    fun `supervision mode defers every gate to the user and records nothing`() {
        val (gate, log) = gate()
        assertTrue(gate.planDecision("t1") is AutonomyDecision.NeedsUser)
        assertTrue(gate.dispatchDecision("t1", "codex-mac") is AutonomyDecision.NeedsUser)
        assertTrue(gate.verdictDecision("t1", "approve", 0) is AutonomyDecision.NeedsUser)
        assertTrue(gate.writeDecision("t1", "write_file", false) is AutonomyDecision.NeedsUser)
        assertTrue(log.listAll().isEmpty())
    }

    // -- 门：全权模式自动批并记审计 ------------------------------------------

    @Test
    fun `full auto approves all four gates and records each decision`() {
        store.applyMode(AutonomyMode.FULL_AUTO)
        val (gate, log) = gate()
        val plan = gate.planDecision("t1")
        assertTrue(plan is AutonomyDecision.AutoApproved)
        val dispatch = gate.dispatchDecision("t1", "codex-mac")
        assertTrue(dispatch is AutonomyDecision.AutoApproved)
        val verdict = gate.verdictDecision("t1", "approve", 1)
        assertTrue(verdict is AutonomyDecision.AutoApproved)
        val write = gate.writeDecision("t1", "write_file", false)
        assertTrue(write is AutonomyDecision.AutoApproved)
        val entries = log.listForTask("t1")
        assertEquals(
            listOf("auto_approve_plan", "auto_dispatch", "auto_adopt_verdict", "auto_approve_write"),
            entries.map { it.action },
        )
        assertTrue(entries.all { it.actor == DefaultAutonomyGate.ACTOR && it.ok && it.ts == now })
        assertTrue(entries.all { it.reason?.contains("4") == true })
        assertEquals(entries, gate.autoDecisionsFor("t1"))
    }

    @Test
    fun `verdict auto-adopt stops at the rework cap even in full auto`() {
        store.applyMode(AutonomyMode.FULL_AUTO)
        val (gate, log) = gate()
        assertTrue(gate.verdictDecision("t1", "changes_requested", 1) is AutonomyDecision.AutoApproved)
        val capped = gate.verdictDecision("t1", "changes_requested", 2)
        assertTrue(capped is AutonomyDecision.NeedsUser)
        assertTrue((capped as AutonomyDecision.NeedsUser).reason.contains("返工已达上限"))
        assertEquals(1, log.listForTask("t1").size)
    }

    @Test
    fun `dispatch follows initiative not execution level`() {
        store.saveInitiative(Initiative.HIGH_AUTO)
        store.saveExecutionLevel(ExecutionLevel.L0)
        val (gate, _) = gate()
        assertTrue(gate.dispatchDecision("t1", "codex-mac") is AutonomyDecision.AutoApproved)
        assertTrue(gate.writeDecision("t1", "write_file", false) is AutonomyDecision.NeedsUser)
        store.saveInitiative(Initiative.SUGGEST)
        store.saveExecutionLevel(ExecutionLevel.L4)
        assertTrue(gate.dispatchDecision("t1", "codex-mac") is AutonomyDecision.NeedsUser)
        assertTrue(gate.writeDecision("t1", "write_file", false) is AutonomyDecision.AutoApproved)
    }

    @Test
    fun `reads are auto-approved but not recorded`() {
        store.applyMode(AutonomyMode.FULL_AUTO)
        val (gate, log) = gate()
        assertTrue(gate.writeDecision("t1", "read_file", false) is AutonomyDecision.AutoApproved)
        assertTrue(log.listAll().isEmpty())
        assertNull(gate.writeDecision("t1", "some_extension_tool", false))
    }

    @Test
    fun `mode switch applies to in-flight tasks on the next decision`() {
        val (gate, log) = gate()
        assertTrue(gate.planDecision("t1") is AutonomyDecision.NeedsUser)
        store.applyMode(AutonomyMode.FULL_AUTO)
        assertTrue(gate.planDecision("t1") is AutonomyDecision.AutoApproved)
        assertEquals(1, log.listForTask("t1").size)
        store.applyMode(AutonomyMode.SUPERVISED)
        assertTrue(gate.planDecision("t1") is AutonomyDecision.NeedsUser)
        assertEquals(1, log.listForTask("t1").size)
    }

    @Test
    fun `autoDecisionsFor only returns the manager actor rows`() {
        store.applyMode(AutonomyMode.FULL_AUTO)
        val (gate, log) = gate()
        log.record(
            ControlAuditEntry(
                taskId = "t1", actor = "user", action = "PAUSE",
                goalVersion = 1, epoch = 1, operationId = "op-user",
                ok = true, ts = now,
            ),
        )
        gate.planDecision("t1")
        val mine = gate.autoDecisionsFor("t1")
        assertEquals(1, mine.size)
        assertEquals("auto_approve_plan", mine[0].action)
    }
}
