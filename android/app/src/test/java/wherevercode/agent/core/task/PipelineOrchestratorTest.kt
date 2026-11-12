package wherevercode.agent.core.task

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.task.PipelineOrchestrator.PipelineResult

/**
 * P2 流水线协调器（2026-11-11），Robolectric + 真实 Room（内存库）：
 * plan 门（未批准不编码）、编码包填 plan、审查通过/打回、返工上限
 * 2 轮、机械检查 FAILED 拦 approve、verdict 历史、审查者优选、
 * 审查输入干净（只含 plan + diff + 机械检查）。
 */
@RunWith(RobolectricTestRunner::class)
class PipelineOrchestratorTest {
    private lateinit var db: TaskDatabase
    private lateinit var taskStore: RoomTaskStore
    private lateinit var scheduler: TaskScheduler
    private lateinit var pipelineStore: RoomPipelineStore
    private lateinit var orchestrator: PipelineOrchestrator
    private lateinit var events: RoomTaskEventStore

    private val now = 1_700_000_000L

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        taskStore = RoomTaskStore(db)
        scheduler = TaskScheduler(taskStore, AgentDiscovery(RuntimeEnvironment.getApplication()), null)
        pipelineStore = RoomPipelineStore(db)
        orchestrator = PipelineOrchestrator(taskStore, scheduler, pipelineStore, nowSeconds = { now })
        events = RoomTaskEventStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun plan() = PlanArtifact(
        objective = "做加法",
        filesInScope = listOf("calculator.py"),
        steps = listOf("实现 add", "补单测"),
        acceptanceCriteria = listOf("add(1, 2) == 3"),
        testCommands = listOf("pytest"),
    )

    private fun createTask(id: String): TaskRecord =
        taskStore.createTask(TaskRecord(taskId = id, title = "任务 $id", description = "做加法", state = "queued", createdAt = now, updatedAt = now))

    /** 把任务推到指定态（经合法转移边）。 */
    private fun moveTo(id: String, state: String) {
        val current = taskStore.getTask(id)!!
        taskStore.updateTask(id, mapOf("state" to state), current.version, current.goalVersion, current.authEpoch)
    }

    /** 编码完成：assigned → working → review（执行器 hold 后停在 review）。 */
    private fun finishCode(id: String) {
        scheduler.claimTask(id, "codex-mac")
        scheduler.startTask(id)
        moveTo(id, "review")
        assertTrue(orchestrator.advanceCodeToReview(id))
        assertEquals("review", taskStore.getTask(id)?.state)
    }

    private fun enableAndApprove(id: String) {
        assertTrue(orchestrator.enablePipeline(id) is PipelineResult.Ok)
        assertTrue(orchestrator.submitPlan(id, plan(), "planner-a") is PipelineResult.Ok)
        val approved = orchestrator.approvePlan(id)
        assertTrue(approved is PipelineResult.Ok)
        assertEquals(PipelineStage.CODE, (approved as PipelineResult.Ok).state.stage)
    }

    @Test
    fun `coding is blocked until the plan is approved`() {
        createTask("t1")
        assertTrue(orchestrator.enablePipeline("t1") is PipelineResult.Ok)
        assertFalse(orchestrator.canStartCoding("t1"))
        assertTrue(orchestrator.submitPlan("t1", plan()) is PipelineResult.Ok)
        assertFalse("草稿未批准仍不许编码", orchestrator.canStartCoding("t1"))
        assertTrue(orchestrator.approvePlan("t1") is PipelineResult.Ok)
        assertTrue(orchestrator.canStartCoding("t1"))
    }

    @Test
    fun `non-pipeline tasks are never gated`() {
        createTask("t1")
        assertTrue(orchestrator.canStartCoding("t1"))
    }

    @Test
    fun `invalid plan is rejected and not stored`() {
        createTask("t1")
        orchestrator.enablePipeline("t1")
        val bad = PlanArtifact(objective = "", steps = emptyList(), acceptanceCriteria = emptyList())
        val result = orchestrator.submitPlan("t1", bad)
        assertTrue(result is PipelineResult.Rejected)
        assertEquals(PipelinePlanStatus.NONE, pipelineStore.getState("t1")?.planStatus)
    }

    @Test
    fun `approve without a draft is rejected`() {
        createTask("t1")
        orchestrator.enablePipeline("t1")
        assertTrue(orchestrator.approvePlan("t1") is PipelineResult.Rejected)
    }

    @Test
    fun `enable on a terminal task is rejected`() {
        createTask("t1")
        moveTo("t1", "cancelled")
        assertTrue(orchestrator.enablePipeline("t1") is PipelineResult.Rejected)
    }

    @Test
    fun `coder packet is filled from the approved plan`() {
        val task = createTask("t1")
        enableAndApprove("t1")
        val packet = orchestrator.coderPacket(task)
        assertEquals(listOf("add(1, 2) == 3"), packet.acceptanceCriteria)
        assertEquals(listOf("calculator.py"), packet.filesInScope)
    }

    @Test
    fun `review approve completes the task and records the verdict`() {
        createTask("t1")
        enableAndApprove("t1")
        finishCode("t1")
        val mechanical = orchestrator.runMechanicalChecks("t1", listOf("calculator.py"), PipelineTestVerdict.PASSED)!!
        assertTrue(mechanical.passed)
        val result = orchestrator.recordReview("t1", ReviewVerdict.APPROVE, "reviewer-a", mechanical = mechanical)
        assertTrue(result is PipelineResult.Ok)
        assertEquals("completed", taskStore.getTask("t1")?.state)
        assertEquals(PipelineStage.DONE, pipelineStore.getState("t1")?.stage)
        val verdicts = pipelineStore.listVerdicts("t1")
        assertEquals(1, verdicts.size)
        assertEquals(ReviewVerdict.APPROVE, verdicts[0].verdict)
        assertEquals("reviewer-a", verdicts[0].reviewer)
        assertTrue(events.listEvents("t1").any { it.kind == TaskEventKind.PIPELINE_REVIEW_APPROVED })
    }

    @Test
    fun `mechanical failure blocks approve`() {
        createTask("t1")
        enableAndApprove("t1")
        finishCode("t1")
        val mechanical = orchestrator.runMechanicalChecks("t1", listOf("calculator.py"), PipelineTestVerdict.FAILED)!!
        assertFalse(mechanical.passed)
        val result = orchestrator.recordReview("t1", ReviewVerdict.APPROVE, "reviewer-a", mechanical = mechanical)
        assertTrue(result is PipelineResult.Rejected)
        assertEquals("review", taskStore.getTask("t1")?.state)
        assertTrue("机械失败时 approve 不得落 verdict", pipelineStore.listVerdicts("t1").isEmpty())
    }

    @Test
    fun `rework without a checklist is rejected`() {
        createTask("t1")
        enableAndApprove("t1")
        finishCode("t1")
        val result = orchestrator.recordReview("t1", ReviewVerdict.CHANGES_REQUESTED, "reviewer-a", checklist = emptyList())
        assertTrue(result is PipelineResult.Rejected)
        assertEquals("review", taskStore.getTask("t1")?.state)
    }

    @Test
    fun `changes requested sends the task back through rework with a round counted`() {
        createTask("t1")
        enableAndApprove("t1")
        finishCode("t1")
        val result = orchestrator.recordReview(
            "t1", ReviewVerdict.CHANGES_REQUESTED, "reviewer-a",
            checklist = listOf("补单测", "add 越界要报错"),
        )
        assertTrue(result is PipelineResult.Ok)
        assertEquals("rework", taskStore.getTask("t1")?.state)
        assertEquals(PipelineStage.CODE, pipelineStore.getState("t1")?.stage)
        assertEquals(1, pipelineStore.getState("t1")?.reworkRounds)
        val verdicts = pipelineStore.listVerdicts("t1")
        assertEquals(1, verdicts.size)
        assertEquals(listOf("补单测", "add 越界要报错"), verdicts[0].checklist)
        // 返工轮次与租约认领次数分开计：attempt 仍是编码那次认领的 1。
        assertEquals(1, taskStore.getTask("t1")?.attempt)
    }

    @Test
    fun `two rework rounds then the third request fails the task for the user`() {
        createTask("t1")
        enableAndApprove("t1")
        repeat(2) { round ->
            finishCode("t1")
            val result = orchestrator.recordReview(
                "t1", ReviewVerdict.CHANGES_REQUESTED, "reviewer-a",
                checklist = listOf("第 ${round + 1} 轮返工清单"),
            )
            assertTrue(result is PipelineResult.Ok)
            assertEquals(round + 1, pipelineStore.getState("t1")?.reworkRounds)
        }
        // 第三轮编码完成、再次打回：已达 2 轮上限。
        finishCode("t1")
        val exhausted = orchestrator.recordReview(
            "t1", ReviewVerdict.CHANGES_REQUESTED, "reviewer-a",
            checklist = listOf("还是不过"),
        )
        assertTrue(exhausted is PipelineResult.Ok)
        val task = taskStore.getTask("t1")!!
        assertEquals("failed", task.state)
        assertTrue(task.needsAttention)
        assertTrue(task.error!!.contains("2 轮上限"))
        assertEquals(2, pipelineStore.getState("t1")?.reworkRounds)
        assertEquals(3, pipelineStore.listVerdicts("t1").size)
        assertTrue(events.listEvents("t1").any { it.kind == TaskEventKind.PIPELINE_REWORK_EXHAUSTED })
    }

    @Test
    fun `mechanical gate rework does not waste LLM review`() {
        createTask("t1")
        enableAndApprove("t1")
        finishCode("t1")
        val mechanical = orchestrator.runMechanicalChecks("t1", listOf("calculator.py", "keys.py"), PipelineTestVerdict.PASSED)!!
        assertFalse(mechanical.passed)
        val result = orchestrator.recordReview(
            "t1", ReviewVerdict.CHANGES_REQUESTED, PipelineOrchestrator.MECHANICAL_REVIEWER,
            checklist = mechanical.failed.map { "${it.name}：${it.detail}" },
            mechanical = mechanical,
        )
        assertTrue(result is PipelineResult.Ok)
        assertEquals("rework", taskStore.getTask("t1")?.state)
        assertEquals(PipelineOrchestrator.MECHANICAL_REVIEWER, pipelineStore.listVerdicts("t1")[0].reviewer)
    }

    @Test
    fun `reviewer selection prefers a different agent on a different device`() {
        val coder = "codex-mac"
        val candidates = listOf(
            PipelineOrchestrator.ReviewerCandidate("codex-mac", "mac-1", "同一 Agent 同设备"),
            PipelineOrchestrator.ReviewerCandidate("claude-mac", "mac-1", "不同 Agent 同设备"),
            PipelineOrchestrator.ReviewerCandidate("codex-vps", "vps-1", "不同 Agent 不同设备"),
        )
        val picked = orchestrator.selectReviewer(coder, "mac-1", candidates)
        assertEquals("codex-vps", picked?.agentId)
        assertNull(orchestrator.selectReviewer(coder, "mac-1", emptyList()))
    }

    @Test
    fun `review input contains only plan diff and mechanical results`() {
        val task = createTask("t1")
        enableAndApprove("t1")
        val mechanical = MechanicalCheckResult(
            checks = listOf(MechanicalCheck("测试", MechanicalOutcome.PASSED, "PASSED")),
        )
        val input = orchestrator.buildReviewInput(
            task, plan(), "diff：+10/-2 calculator.py",
            listOf("calculator.py"), mechanical,
        )
        assertTrue(input.contains("做加法"))
        assertTrue(input.contains("add(1, 2) == 3"))
        assertTrue(input.contains("diff：+10/-2 calculator.py"))
        assertTrue(input.contains("机械检查"))
        assertTrue(input.contains("changes_requested"))
        assertTrue(input.contains("不要索取聊天记录"))
    }

    @Test
    fun `user rework on a non-pipeline task falls back to plain requestRework`() {
        createTask("t1")
        moveTo("t1", "assigned")
        moveTo("t1", "working")
        moveTo("t1", "review")
        val result = orchestrator.userRequestRework("t1", "这里不对")
        assertTrue(result is PipelineResult.Ok)
        assertEquals("rework", taskStore.getTask("t1")?.state)
    }

    @Test
    fun `user rework with an empty reason is rejected`() {
        createTask("t1")
        moveTo("t1", "assigned")
        moveTo("t1", "working")
        moveTo("t1", "review")
        assertTrue(orchestrator.userRequestRework("t1", "   ") is PipelineResult.Rejected)
        assertEquals("review", taskStore.getTask("t1")?.state)
    }

    @Test
    fun `planner prompt tells the planner to write no code`() {
        val task = createTask("t1")
        val prompt = orchestrator.buildPlannerPrompt(task)
        assertTrue(prompt.contains("只出 plan，不写代码"))
        assertTrue(prompt.contains("做加法"))
        assertNotNull(prompt)
    }
}
