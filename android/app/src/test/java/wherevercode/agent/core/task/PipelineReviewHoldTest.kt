package wherevercode.agent.core.task

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.task.PipelineOrchestrator.PipelineResult
import wherevercode.agent.core.team.InMemoryTeamMemberStore
import wherevercode.agent.core.team.MemberConnectionState
import wherevercode.agent.core.team.MemberFinishState
import wherevercode.agent.core.team.MemberManager
import wherevercode.agent.core.team.MemberReturnReceiver
import wherevercode.agent.core.team.MemberShapeStatus
import wherevercode.agent.core.team.MemberTransport
import wherevercode.agent.core.team.TeamMember

/**
 * P2 执行器 hold（2026-11-11）：流水线任务的编码完成停在 review，
 * 不自动 completed；未启用流水线的任务行为与 P0 完全一致。
 *
 * 两条路：本地执行器（LocalTaskRunner.finishTask）与成员回传
 * （MemberReturnReceiver.finishTask）。远端执行器同款 hold 由
 * RemoteTaskDispatcher.finishAsCompleted 实现，逻辑与本地一致，
 * 其测试在 TaskAttemptFenceTest 的远端围栏模式之外另由本文件的
 * 本地 hold 覆盖行为契约（hold 判定函数是同一个 hook）。
 */
@RunWith(RobolectricTestRunner::class)
class PipelineReviewHoldTest {

    private class OkExecutor : TaskExecutor {
        override suspend fun execute(
            task: TaskRecord,
            onStep: (TaskStepEvent) -> Unit,
        ): TaskExecutionResult = TaskExecutionResult(success = true, summary = "完成")
    }

    private lateinit var taskStore: DispatchFakeTaskStore
    private lateinit var scheduler: TaskScheduler
    private lateinit var pipelineStore: InMemoryPipelineStore
    private lateinit var orchestrator: PipelineOrchestrator
    private lateinit var runner: LocalTaskRunner

    private val now = 1_700_000_000L

    @Before
    fun setUp() {
        taskStore = DispatchFakeTaskStore()
        scheduler = testTaskScheduler(taskStore, AgentDiscovery(RuntimeEnvironment.getApplication()))
        pipelineStore = InMemoryPipelineStore()
        orchestrator = PipelineOrchestrator(taskStore, scheduler, pipelineStore, nowSeconds = { now })
        runner = LocalTaskRunner(
            taskStore,
            scheduler,
            InMemoryTaskStepStore(),
            OkExecutor(),
            pollIntervalMs = 60_000,
            leaseRenewIntervalMs = 60_000,
            pipelineHook = PipelineRunnerHookImpl(orchestrator),
        )
    }

    @After
    fun tearDown() {
        runner.stop()
    }

    private fun plan() = PlanArtifact(
        objective = "做加法",
        filesInScope = listOf("calculator.py"),
        steps = listOf("实现 add"),
        acceptanceCriteria = listOf("add(1, 2) == 3"),
    )

    private fun queued(id: String) = taskStore.createTask(
        TaskRecord(
            taskId = id, title = "任务 $id", description = "做加法",
            state = "queued", assignedAgentId = LocalTaskRunner.LOCAL_AGENT_ID,
            createdAt = now,
        ),
    )

    @Test
    fun `pipeline task with an approved plan stops at review after local execution`() = runBlocking {
        queued("p1")
        assertTrue(orchestrator.enablePipeline("p1") is PipelineResult.Ok)
        assertTrue(orchestrator.submitPlan("p1", plan()) is PipelineResult.Ok)
        assertTrue(orchestrator.approvePlan("p1") is PipelineResult.Ok)
        runner.pumpOnce()
        val task = taskStore.getTask("p1")!!
        assertEquals("review", task.state)
        assertEquals(PipelineStage.REVIEW, pipelineStore.getState("p1")?.stage)
        assertEquals(LocalTaskRunner.LOCAL_AGENT_ID, pipelineStore.getState("p1")?.coderAgentId)
    }

    @Test
    fun `pipeline task without an approved plan is never claimed by the local pump`() = runBlocking {
        queued("p1")
        assertTrue(orchestrator.enablePipeline("p1") is PipelineResult.Ok)
        runner.pumpOnce()
        val task = taskStore.getTask("p1")!!
        assertEquals("queued", task.state)
        assertEquals(PipelineStage.PLAN, pipelineStore.getState("p1")?.stage)
    }

    @Test
    fun `non-pipeline task still auto-completes as before`() = runBlocking {
        queued("s1")
        runner.pumpOnce()
        assertEquals("completed", taskStore.getTask("s1")?.state)
    }

    @Test
    fun `member finish holds a pipeline task at review`() {
        val tasks = DispatchFakeTaskStore()
        val members = InMemoryTeamMemberStore()
        val manager = MemberManager(members, InMemoryAuditLog())
        manager.addMember(
            TeamMember.remoteCliMember(
                memberId = "codex-mac", agentId = "codex-cli",
                displayName = "Mac 上的 Codex", transport = MemberTransport.LAN,
                endpointDeviceId = "mac-1", endpointLabel = "Mac",
                shapeStatus = MemberShapeStatus.VERIFIED,
            ),
        )
        manager.grantProject("codex-mac", "proj-a", "user")
        manager.setConnectionState("codex-mac", MemberConnectionState.CONNECTED)
        val memberOrchestrator = PipelineOrchestrator(tasks, testTaskScheduler(tasks, AgentDiscovery(RuntimeEnvironment.getApplication())), InMemoryPipelineStore(), nowSeconds = { now })
        val receiver = MemberReturnReceiver(tasks, members, nowSeconds = { now }, pipelineHook = PipelineRunnerHookImpl(memberOrchestrator))
        tasks.createTask(TaskRecord(taskId = "m1", projectId = "proj-a", title = "任务 m1"))
        memberOrchestrator.enablePipeline("m1")
        memberOrchestrator.submitPlan("m1", plan())
        memberOrchestrator.approvePlan("m1")
        tasks.updateTask(
            "m1",
            mapOf(
                "state" to "assigned", "assigned_agent_id" to "codex-mac",
                "member_id" to "codex-mac", "attempt" to 1, "attempt_id" to "att-1",
            ),
            1, 1, 1,
        )
        val finished = receiver.finishTask("m1", "codex-mac", "att-1", MemberFinishState.COMPLETED, result = "改完了")
        assertTrue(finished is wherevercode.agent.core.team.MemberFinishResult.Finished)
        val task = (finished as wherevercode.agent.core.team.MemberFinishResult.Finished).task
        assertEquals("review", task.state)
        assertEquals(PipelineStage.REVIEW, memberOrchestrator.viewState("m1")?.stage)
    }
}
