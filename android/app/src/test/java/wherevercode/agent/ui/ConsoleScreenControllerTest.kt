package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.brain.ApprovalOutcome
import wherevercode.agent.brain.ApprovalRecord
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.EngineEvent
import wherevercode.agent.brain.PendingApproval
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.team.MemberConnectionState
import wherevercode.agent.core.transport.RemoteDevice
import wherevercode.agent.ui.ConsoleSnapshot.ConsoleModule
import wherevercode.agent.ui.ConsoleSnapshot.Snapshot

/**
 * 控制台控制器渲染测试（V5 控制台，2026-11-11；Robolectric）。
 *
 * 只断言渲染出来的文字：全局总览是否一眼看全、App 自身行是否在场、
 * 自由度分档是否按档出模块、隐藏/恢复是否生效、空数据是否明说暂无
 * （不造假）。不启动 Activity、不碰真实 store。
 */
@RunWith(RobolectricTestRunner::class)
class ConsoleScreenControllerTest {

    private fun controller(): ConsoleScreenController = ConsoleScreenController(
        RuntimeEnvironment.getApplication(),
        onOpenTask = {},
        onOpenFile = {},
        onOpenChat = {},
        onOpenDevices = {},
        onOpenAgents = {},
        onRefresh = {},
    )

    private fun task(
        id: String,
        title: String,
        state: String = "queued",
        updatedAt: Long = 100,
        leaseOwner: String? = null,
        assignedAgentId: String? = null,
    ) = TaskRecord(
        taskId = id,
        title = title,
        state = state,
        updatedAt = updatedAt,
        leaseOwner = leaseOwner,
        assignedAgentId = assignedAgentId,
    )

    private fun agent(id: String, name: String) = AgentRecord(
        agentId = id,
        displayName = name,
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.MODEL_API,
        adapterId = "test",
        deviceId = "remote-device",
        memberConnectionState = MemberConnectionState.NOT_CONNECTED,
        lastTestSummary = "远端可用",
    )

    private fun snapshot(): Snapshot = ConsoleSnapshot.build(
        tasks = listOf(
            task("t1", "做计算器", "working", updatedAt = 300, leaseOwner = "local-brain"),
            task("t2", "修空响应", "queued", updatedAt = 200, assignedAgentId = "example-agent"),
            task("t3", "旧任务", "completed", updatedAt = 100, leaseOwner = "local-brain"),
        ),
        stepsByTask = mapOf(
            "t3" to listOf(TaskStep("s1", "t3", "c1", "write_file", "path=/tmp/calc.html")),
        ),
        events = listOf(EngineEvent(1, "c1", "turn_done", "done", 300)),
        pendingApproval = PendingApproval("p1", "write_file", "/tmp/calc.html", System.currentTimeMillis() + 30_000),
        approvalHistory = listOf(
            ApprovalRecord("a1", "write_file", "/tmp/old.txt", ApprovalOutcome.APPROVED, atEpochSeconds = 90),
        ),
        devices = listOf(RemoteDevice("remote-device", "remote-host", "192.0.2.10", 18743)),
        pairedDeviceIds = setOf("remote-device"),
        agents = listOf(agent("example-agent", "Example Agent")),
        generatedAtEpochSeconds = 300,
    )

    /** 递归收集全部 TextView 的文字。 */
    private fun allTexts(view: View): List<String> {
        val out = mutableListOf<String>()
        if (view is TextView) out += view.text.toString()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) out += allTexts(view.getChildAt(i))
        }
        return out
    }

    private fun assertContains(texts: List<String>, needle: String) {
        assertTrue("expected text containing <$needle> in $texts", texts.any { it.contains(needle) })
    }

    private fun assertNotContains(texts: List<String>, needle: String) {
        assertFalse("unexpected text containing <$needle> in $texts", texts.any { it.contains(needle) })
    }

    @Test
    fun `level three shows global overview with the app itself first`() {
        val c = controller()
        c.setLevel(3)
        c.render(snapshot())
        val texts = allTexts(c.root)
        // 总览：全局计数一眼看全。
        assertContains(texts, "进行中 1")
        assertContains(texts, "等待 1")
        assertContains(texts, "已完成 1")
        assertContains(texts, "设备 1 台")
        assertContains(texts, "Agent 2 个（含本机 App）")
        // 进行中任务与待审批在总览里。
        assertContains(texts, "做计算器")
        assertContains(texts, "等待审批：write_file")
        // App 自身行在场（首位），成员 Agent 其后。
        assertContains(texts, "本机 App")
        assertContains(texts, "本地大脑")
        assertContains(texts, "Example Agent")
        assertContains(texts, "remote-host")
        assertContains(texts, "已配对")
        // 3 档没有执行日志模块。
        assertNotContains(texts, "执行日志")
    }

    @Test
    fun `level four adds logs and level five calls it matrix stream`() {
        val c = controller()
        c.setLevel(4)
        c.render(snapshot())
        assertContains(allTexts(c.root), "执行日志")
        assertNotContains(allTexts(c.root), "Matrix 实时事件流")
        c.setLevel(5)
        c.render(snapshot())
        assertContains(allTexts(c.root), "Matrix 实时事件流")
    }

    @Test
    fun `hide and show module works`() {
        val c = controller()
        c.setLevel(3)
        c.render(snapshot())
        assertContains(allTexts(c.root), "待审批")
        c.hideModule(ConsoleModule.APPROVALS)
        c.render(snapshot())
        // 审批模块已隐藏（只剩总览里的一行“待审批 1 个”计数，不再有审批模块标题）。
        assertContains(allTexts(c.root), "已隐藏的模块")
        assertNotContains(allTexts(c.root), "审批历史")
        c.showModule(ConsoleModule.APPROVALS)
        c.render(snapshot())
        assertContains(allTexts(c.root), "审批历史")
    }

    @Test
    fun `agent filter shows only that executor tasks in the tasks module`() {
        val c = controller()
        c.setLevel(3)
        c.render(snapshot())
        // 未筛选时“做计算器”出现两次：总览的进行中任务 + 任务模块。
        assertEquals(2, allTexts(c.root).count { it == "做计算器" })
        c.setAgentFilter("example-agent")
        val texts = allTexts(c.root)
        // 筛选后任务模块只剩 Grok 的任务；总览是全局的，不受筛选影响，
        // 所以“做计算器”只剩总览里的一次。
        assertEquals(1, texts.count { it == "做计算器" })
        assertContains(texts, "修空响应")
    }

    @Test
    fun `empty snapshot says so honestly and never fakes data`() {
        val c = controller()
        c.setLevel(3)
        val empty = ConsoleSnapshot.build(
            tasks = emptyList(),
            stepsByTask = emptyMap(),
            events = emptyList(),
            pendingApproval = null,
            approvalHistory = emptyList(),
            devices = emptyList(),
            pairedDeviceIds = emptySet(),
            agents = emptyList(),
            generatedAtEpochSeconds = 0,
        )
        c.render(empty)
        val texts = allTexts(c.root)
        assertContains(texts, "暂无进行中任务")
        assertContains(texts, "暂无待审批")
        assertContains(texts, "暂无远程设备")
        assertContains(texts, "本机 App")
        // 没有编出来的任务、设备、Agent。
        assertNotContains(texts, "做计算器")
        assertNotContains(texts, "Example Agent")
    }

    @Test
    fun `loading state says loading and never fakes an empty overview`() {
        val c = controller()
        c.renderLoading()
        val texts = allTexts(c.root)
        assertContains(texts, "大脑加载中，控制台暂不可读。")
        assertNotContains(texts, "进行中 0")
    }
}
