package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.ApprovalOutcome
import wherevercode.agent.brain.ApprovalRecord
import wherevercode.agent.brain.EngineEvent
import wherevercode.agent.brain.PendingApproval
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.team.MemberConnectionState
import wherevercode.agent.core.transport.RemoteDevice
import wherevercode.agent.ui.ConsoleSnapshot.ConsoleModule
import wherevercode.agent.ui.ConsoleSnapshot.TaskExecutor

/**
 * 控制台快照纯逻辑测试（V5 控制台，2026-11-11；纯 JVM JUnit 4，无 Robolectric）。
 *
 * 依据：PROJECT.md §7 工作台（全局总览）/ §3.2 自由度 5 档；
 * maintainer 2026-11-11 04:56（App 自己也是一个 Agent）与 05:04
 * （控制台的意义是全局可见性：一个地方看全所有设备、所有 Agent、
 * 所有任务、进度、日志、状态）。
 */
class ConsoleSnapshotTest {

    private fun task(
        id: String,
        state: String = "queued",
        updatedAt: Long = 100,
        leaseOwner: String? = null,
        assignedAgentId: String? = null,
        memberId: String? = null,
    ) = TaskRecord(
        taskId = id,
        title = "任务 $id",
        state = state,
        updatedAt = updatedAt,
        leaseOwner = leaseOwner,
        assignedAgentId = assignedAgentId,
        memberId = memberId,
    )

    private fun agent(
        id: String,
        name: String,
        kind: AgentKind = AgentKind.DEVICE,
        deviceId: String? = null,
        connectionState: MemberConnectionState = MemberConnectionState.NOT_CONNECTED,
        lastTestSummary: String? = null,
    ) = AgentRecord(
        agentId = id,
        displayName = name,
        kind = kind,
        access = AgentAccess.CLI,
        brainKind = BrainKind.MODEL_API,
        adapterId = "test",
        deviceId = deviceId,
        memberConnectionState = connectionState,
        lastTestSummary = lastTestSummary,
    )

    // ── 执行者归属 ────────────────────────────────────────────────

    @Test
    fun `local-brain lease means the app itself`() {
        val self = task("t1", leaseOwner = TurnTaskRecorder.LOCAL_AGENT_ID)
        assertEquals(TaskExecutor.Self, ConsoleSnapshot.executorOf(self))
        assertEquals("local-brain", TurnTaskRecorder.LOCAL_AGENT_ID)
    }

    @Test
    fun `assigned agent id means that member agent`() {
        val member = task("t2", assignedAgentId = "example-agent")
        assertEquals(TaskExecutor.Member("example-agent"), ConsoleSnapshot.executorOf(member))
    }

    @Test
    fun `member id means that member agent`() {
        val member = task("t3", memberId = "codebuddy")
        assertEquals(TaskExecutor.Member("codebuddy"), ConsoleSnapshot.executorOf(member))
    }

    @Test
    fun `other lease owner means that member agent`() {
        val member = task("t4", leaseOwner = "codex")
        assertEquals(TaskExecutor.Member("codex"), ConsoleSnapshot.executorOf(member))
    }

    @Test
    fun `no owner means unclaimed`() {
        assertEquals(TaskExecutor.Unclaimed, ConsoleSnapshot.executorOf(task("t5")))
    }

    @Test
    fun `executor labels are honest`() {
        val agents = mapOf("example-agent" to agent("example-agent", "Example Agent"))
        assertEquals("本机 App", ConsoleSnapshot.executorLabel(TaskExecutor.Self, agents))
        assertEquals("Example Agent", ConsoleSnapshot.executorLabel(TaskExecutor.Member("example-agent"), agents))
        assertEquals("unknown-id", ConsoleSnapshot.executorLabel(TaskExecutor.Member("unknown-id"), agents))
        assertEquals("未认领", ConsoleSnapshot.executorLabel(TaskExecutor.Unclaimed, agents))
    }

    // ── 自由度分档 ────────────────────────────────────────────────

    @Test
    fun `level one shows overview approvals and artifacts only`() {
        assertEquals(
            listOf(ConsoleModule.OVERVIEW, ConsoleModule.APPROVALS, ConsoleModule.ARTIFACTS),
            ConsoleSnapshot.visibleModules(1, emptySet()),
        )
    }

    @Test
    fun `level two adds tasks`() {
        assertEquals(
            listOf(ConsoleModule.OVERVIEW, ConsoleModule.APPROVALS, ConsoleModule.ARTIFACTS, ConsoleModule.TASKS),
            ConsoleSnapshot.visibleModules(2, emptySet()),
        )
    }

    @Test
    fun `level three adds devices and agents`() {
        assertEquals(
            listOf(
                ConsoleModule.OVERVIEW, ConsoleModule.APPROVALS, ConsoleModule.ARTIFACTS,
                ConsoleModule.TASKS, ConsoleModule.DEVICES_AGENTS,
            ),
            ConsoleSnapshot.visibleModules(3, emptySet()),
        )
    }

    @Test
    fun `level four adds logs and level five keeps all`() {
        val all = ConsoleModule.values().toList()
        assertEquals(all, ConsoleSnapshot.visibleModules(4, emptySet()))
        assertEquals(all, ConsoleSnapshot.visibleModules(5, emptySet()))
    }

    @Test
    fun `hidden modules are excluded at any level`() {
        assertEquals(
            listOf(ConsoleModule.OVERVIEW, ConsoleModule.APPROVALS, ConsoleModule.TASKS),
            ConsoleSnapshot.visibleModules(5, setOf(ConsoleModule.ARTIFACTS, ConsoleModule.DEVICES_AGENTS, ConsoleModule.LOGS)),
        )
    }

    @Test
    fun `level names follow the spec`() {
        assertEquals("小白", ConsoleSnapshot.levelName(1))
        assertEquals("普通", ConsoleSnapshot.levelName(2))
        assertEquals("标准", ConsoleSnapshot.levelName(3))
        assertEquals("进阶", ConsoleSnapshot.levelName(4))
        assertEquals("高阶", ConsoleSnapshot.levelName(5))
        assertEquals(3, ConsoleSnapshot.DEFAULT_LEVEL)
    }

    // ── 快照组装（全局可见性） ────────────────────────────────────

    @Test
    fun `build puts the app itself first with real task counts`() {
        val tasks = listOf(
            task("t1", "working", updatedAt = 300, leaseOwner = "local-brain"),
            task("t2", "completed", updatedAt = 200, leaseOwner = "local-brain"),
            task("t3", "queued", updatedAt = 100, assignedAgentId = "example-agent"),
        )
        val snapshot = ConsoleSnapshot.build(
            tasks = tasks,
            stepsByTask = emptyMap(),
            events = emptyList(),
            pendingApproval = null,
            approvalHistory = emptyList(),
            devices = listOf(RemoteDevice("remote-device", "remote-host", "192.0.2.10", 18743)),
            pairedDeviceIds = setOf("remote-device"),
            agents = listOf(agent("example-agent", "Example Agent", deviceId = "remote-device")),
            generatedAtEpochSeconds = 400,
        )
        // App 自身在首位，任务数按 leaseOwner 真实统计。
        val self = snapshot.agents.first()
        assertTrue(self.isSelf)
        assertEquals("本机 App", self.displayName)
        assertEquals(2, self.taskCount)
        assertEquals(300L, self.lastActivityEpochSeconds)
        // 成员 Agent 其后，任务数按归属统计。
        val grok = snapshot.agents[1]
        assertEquals("Example Agent", grok.displayName)
        assertEquals(1, grok.taskCount)
        // 总览计数。
        assertEquals(1, snapshot.activeTasks.size)
        assertEquals(1, snapshot.queuedTasks.size)
        assertEquals(1, snapshot.completedCount)
        assertEquals(0, snapshot.failedCount)
        assertEquals(2, snapshot.selfTasks.size)
        // 设备行：已配对真实来自配对记录。
        assertEquals(1, snapshot.devices.size)
        assertTrue(snapshot.devices.first().paired)
        assertEquals(1, snapshot.devices.first().agentCount)
    }

    @Test
    fun `build with no data shows empty globals honestly`() {
        val snapshot = ConsoleSnapshot.build(
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
        // 只有 App 自身一行（任务数为 0），没有成员、没有设备。
        assertEquals(1, snapshot.agents.size)
        assertTrue(snapshot.agents.first().isSelf)
        assertEquals(0, snapshot.agents.first().taskCount)
        assertTrue(snapshot.devices.isEmpty())
        assertTrue(snapshot.tasks.isEmpty())
        assertTrue(snapshot.events.isEmpty())
    }

    @Test
    fun `approval history is newest first and capped at twenty`() {
        val history = (1..25).map { i ->
            ApprovalRecord("a$i", "write_file", "/tmp/f$i", ApprovalOutcome.APPROVED, atEpochSeconds = i.toLong())
        }
        val snapshot = ConsoleSnapshot.build(
            tasks = emptyList(),
            stepsByTask = emptyMap(),
            events = emptyList(),
            pendingApproval = PendingApproval("p1", "write_file", "/tmp/x", 9999L),
            approvalHistory = history,
            devices = emptyList(),
            pairedDeviceIds = emptySet(),
            agents = emptyList(),
            generatedAtEpochSeconds = 0,
        )
        assertEquals(20, snapshot.approvalHistory.size)
        assertEquals("a25", snapshot.approvalHistory.first().actionId)
        assertEquals("a6", snapshot.approvalHistory.last().actionId)
        assertEquals("p1", snapshot.pendingApproval?.actionId)
    }

    @Test
    fun `artifact paths come only from write_file steps`() {
        val tasks = listOf(task("t1", "completed", updatedAt = 100))
        val steps = mapOf(
            "t1" to listOf(
                TaskStep("s1", "t1", "c1", "write_file", "path=/tmp/a.txt"),
                TaskStep("s2", "t1", "c2", "run_command", "cmd=ls"),
            ),
        )
        val snapshot = ConsoleSnapshot.build(
            tasks = tasks,
            stepsByTask = steps,
            events = listOf(EngineEvent(1, "c1", "turn_done", "done", 100)),
            pendingApproval = null,
            approvalHistory = emptyList(),
            devices = emptyList(),
            pairedDeviceIds = emptySet(),
            agents = emptyList(),
            generatedAtEpochSeconds = 0,
        )
        assertEquals(1, snapshot.results.distinctArtifactCount)
        assertEquals(listOf("/tmp/a.txt"), snapshot.results.tasks.first().artifactPaths)
        assertEquals(1, snapshot.events.size)
    }

    @Test
    fun `member connection labels never show raw enum names`() {
        assertEquals("未连接（等待安排）", ConsoleSnapshot.memberConnectionLabel(MemberConnectionState.NOT_CONNECTED))
        assertEquals("已连接", ConsoleSnapshot.memberConnectionLabel(MemberConnectionState.CONNECTED))
        assertEquals("不可用（连接已断开）", ConsoleSnapshot.memberConnectionLabel(MemberConnectionState.UNAVAILABLE))
        assertEquals("云端", ConsoleSnapshot.agentKindLabel(AgentKind.CLOUD))
        assertEquals("设备", ConsoleSnapshot.agentKindLabel(AgentKind.DEVICE))
        assertEquals("本机模型", ConsoleSnapshot.agentKindLabel(AgentKind.LOCAL_MODEL))
    }
}
