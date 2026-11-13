package wherevercode.agent.core.task

import kotlinx.coroutines.runBlocking
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
import wherevercode.agent.core.task.PipelineRunner.PlannerResult
import wherevercode.agent.core.task.PipelineRunner.ReviewerResult

/**
 * P2 流水线自动执行（2026-11-11），Robolectric + 真实 Room（内存库）+ 假 Agent 调用方：
 * 规划者自动起草（草稿待批、批准门不动）、解析失败落草稿失败态、
 * 审查者自动执行（机械先行、verdict 落盘、返工上限 2 轮）、失败进
 * failed + needsAttention、事件进 task_events。
 */
@RunWith(RobolectricTestRunner::class)
class PipelineRunnerTest {
    private lateinit var db: TaskDatabase
    private lateinit var taskStore: RoomTaskStore
    private lateinit var scheduler: TaskScheduler
    private lateinit var pipelineStore: RoomPipelineStore
    private lateinit var orchestrator: PipelineOrchestrator
    private lateinit var events: RoomTaskEventStore

    private val now = 1_700_000_000L

    /** 假 Agent 调用方：按脚本回放，记下每次调用的 agentId/prompt/会话 id。 */
    private class FakeCaller(
        var handler: (agentId: String?, prompt: String, conversationId: String) -> PipelineAgentCallResult,
    ) : PipelineAgentCaller {
        data class Call(val agentId: String?, val deviceId: String?, val prompt: String, val conversationId: String)
        val calls = ArrayList<Call>()
        override fun canCall(agentId: String?, deviceId: String?): Boolean = true
        override suspend fun callAgent(
            taskId: String,
            agentId: String?,
            deviceId: String?,
            prompt: String,
            conversationId: String,
        ): PipelineAgentCallResult {
            calls += Call(agentId, deviceId, prompt, conversationId)
            return handler(agentId, prompt, conversationId)
        }
    }

    private lateinit var caller: FakeCaller

    private fun planJson() = """{"version":1,"objective":"做加法","files_in_scope":["calculator.py"],"steps":["实现 add","补单测"],"acceptance_criteria":["add(1, 2) == 3"],"test_commands":[],"planner_id":"planner-a","created_at":1700000000}"""

    private fun planNoTests() = PlanArtifact(
        objective = "做加法",
        filesInScope = listOf("calculator.py"),
        steps = listOf("实现 add", "补单测"),
        acceptanceCriteria = listOf("add(1, 2) == 3"),
        testCommands = emptyList(),
    )

    private fun planWithTests() = planNoTests().copy(testCommands = listOf("pytest"))

    private fun runner(
        candidates: () -> List<PipelineOrchestrator.ReviewerCandidate> = { emptyList() },
        inputs: (TaskRecord) -> PipelineReviewInputs = { PipelineReviewInputs() },
        isWorkInHand: () -> Boolean = { false },
    ) = PipelineRunner(
        taskStore = taskStore,
        orchestrator = orchestrator,
        pipelineStore = pipelineStore,
        caller = caller,
        reviewerCandidates = candidates,
        reviewInputs = inputs,
        isWorkInHand = isWorkInHand,
        nowSeconds = { now },
    )

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        taskStore = RoomTaskStore(db)
        scheduler = testTaskScheduler(taskStore, AgentDiscovery(RuntimeEnvironment.getApplication()))
        pipelineStore = RoomPipelineStore(db)
        orchestrator = PipelineOrchestrator(taskStore, scheduler, pipelineStore, nowSeconds = { now })
        events = RoomTaskEventStore(db)
        caller = FakeCaller { _, _, _ -> PipelineAgentCallResult.Failure("未设脚本") }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun createTask(id: String, brainId: String? = null): TaskRecord =
        taskStore.createTask(
            TaskRecord(
                taskId = id, title = "任务 $id", description = "做加法",
                state = "queued", createdAt = now, updatedAt = now, brainId = brainId,
            ),
        )

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

    private fun enableAndApprove(id: String, plan: PlanArtifact = planNoTests()) {
        assertTrue(orchestrator.enablePipeline(id) is PipelineResult.Ok)
        assertTrue(orchestrator.submitPlan(id, plan, "planner-a") is PipelineResult.Ok)
        val approved = orchestrator.approvePlan(id)
        assertTrue(approved is PipelineResult.Ok)
        assertEquals(PipelineStage.CODE, (approved as PipelineResult.Ok).state.stage)
    }

    private fun eventKinds(id: String): List<String> = events.listEvents(id).map { it.kind }

    @Test
    fun plannerDraftsPlanAndWaitsForApproval() = runBlocking {
        val id = "t-plan"
        createTask(id, brainId = "zhipu")
        assertTrue(orchestrator.enablePipeline(id) is PipelineResult.Ok)
        caller.handler = { _, _, _ -> PipelineAgentCallResult.Text("先想想：\n```json\n${planJson()}\n```\n以上是 plan。") }

        val result = runner().runPlanner(id)

        assertTrue(result is PlannerResult.DraftReady)
        assertEquals("做加法", (result as PlannerResult.DraftReady).plan.objective)
        val state = pipelineStore.getState(id)!!
        assertEquals(PipelinePlanStatus.DRAFT, state.planStatus)
        assertEquals(PipelineStage.PLAN, state.stage)
        // 批准门不动：草稿待批，任务仍 queued，编码认领仍被门禁挡住。
        assertEquals("queued", taskStore.getTask(id)?.state)
        assertFalse(orchestrator.canStartCoding(id))
        // 调的是任务的大脑（task.brainId），会话 id 与任务绑定。
        assertEquals(1, caller.calls.size)
        assertEquals("zhipu", caller.calls[0].agentId)
        assertEquals("pipeline-plan-$id", caller.calls[0].conversationId)
        assertTrue(caller.calls[0].prompt.contains("只出 plan，不写代码"))
        // 事件：规划开始 + 草稿待批（P2 的 PIPELINE_PLAN_SUBMITTED）。
        val kinds = eventKinds(id)
        assertTrue(kinds.contains(TaskEventKind.PIPELINE_PLAN_STARTED))
        assertTrue(kinds.contains(TaskEventKind.PIPELINE_PLAN_SUBMITTED))
    }

    @Test
    fun plannerParseFailureMarksDraftFailedWithoutFabricating() = runBlocking {
        val id = "t-plan-bad"
        createTask(id)
        assertTrue(orchestrator.enablePipeline(id) is PipelineResult.Ok)
        caller.handler = { _, _, _ -> PipelineAgentCallResult.Text("我觉得应该先实现 add 再补单测，验收是 add(1, 2) == 3。") }

        val result = runner().runPlanner(id)

        assertTrue(result is PlannerResult.DraftFailed)
        val state = pipelineStore.getState(id)!!
        assertEquals(PipelinePlanStatus.FAILED, state.planStatus)
        assertNull(state.draftPlan)
        // 任务留 queued 待手动提交，挂 needsAttention + 真实原因。
        val task = taskStore.getTask(id)!!
        assertEquals("queued", task.state)
        assertTrue(task.needsAttention)
        assertTrue((task.error ?: "").contains("解析失败"))
        assertTrue(eventKinds(id).contains(TaskEventKind.PIPELINE_PLAN_FAILED))
        // FAILED 不自动重试：再跑一次直接跳过，不烧第二次调用。
        val again = runner().runPlanner(id)
        assertTrue(again is PlannerResult.Skipped)
        assertEquals(1, caller.calls.size)
    }

    @Test
    fun plannerCallerFailureMarksDraftFailed() = runBlocking {
        val id = "t-plan-nobrain"
        createTask(id)
        assertTrue(orchestrator.enablePipeline(id) is PipelineResult.Ok)
        caller.handler = { _, _, _ -> PipelineAgentCallResult.Failure("brain_not_configured：未配置大脑") }

        val result = runner().runPlanner(id)

        assertTrue(result is PlannerResult.DraftFailed)
        assertEquals(PipelinePlanStatus.FAILED, pipelineStore.getState(id)?.planStatus)
        assertTrue(taskStore.getTask(id)?.needsAttention == true)
    }

    @Test
    fun plannerSkipsWhenDraftAlreadyExists() = runBlocking {
        val id = "t-plan-dup"
        createTask(id)
        assertTrue(orchestrator.enablePipeline(id) is PipelineResult.Ok)
        assertTrue(orchestrator.submitPlan(id, planNoTests(), "user") is PipelineResult.Ok)
        caller.handler = { _, _, _ -> PipelineAgentCallResult.Text(planJson()) }

        val result = runner().runPlanner(id)

        assertTrue(result is PlannerResult.Skipped)
        assertEquals(0, caller.calls.size)
    }

    @Test
    fun reviewerApprovesWithCleanContextAndDifferentAgent() = runBlocking {
        val id = "t-review-ok"
        createTask(id)
        enableAndApprove(id)
        finishCode(id)
        // 编码者在 dev-mac 上的 codex-mac；候选里有同 Agent 同设备、
        // 同 Agent 不同设备、不同 Agent 同设备、不同 Agent 不同设备。
        val st = pipelineStore.getState(id)!!
        pipelineStore.saveState(st.copy(coderDeviceId = "dev-mac"))
        val candidates = listOf(
            PipelineOrchestrator.ReviewerCandidate("codex-mac", "dev-mac", "编码者本人"),
            PipelineOrchestrator.ReviewerCandidate("codex-mac", "dev-other", "同一 Agent 另一设备"),
            PipelineOrchestrator.ReviewerCandidate("brain-other", "dev-mac", "另一大脑同设备"),
            PipelineOrchestrator.ReviewerCandidate("brain-x", "dev-other", "另一大脑另一设备"),
        )
        caller.handler = { _, _, _ -> PipelineAgentCallResult.Text("approve") }

        val result = runner(candidates = { candidates }).runReview(id)

        assertTrue(result is ReviewerResult.Approved)
        // 选的是“不同 Agent 且不同设备”的 brain-x，不是编码者。
        assertEquals(1, caller.calls.size)
        assertEquals("brain-x", caller.calls[0].agentId)
        assertEquals("dev-other", caller.calls[0].deviceId)
        assertEquals("pipeline-review-$id-r0", caller.calls[0].conversationId)
        // 干净上下文：审查输入与 P2 buildReviewInput 的输出逐字一致，
        // 不含调用方能塞的任何额外内容（签名里没有聊天记录参数）。
        val task = taskStore.getTask(id)!!
        val mechanical = orchestrator.runMechanicalChecks(id, emptyList(), PipelineTestVerdict.NOT_RUN)
        val expected = orchestrator.buildReviewInput(task, planNoTests(), null, emptyList(), mechanical)
        assertEquals(expected, caller.calls[0].prompt)
        // 任务 completed，verdict 落盘，审查者记 brain-x。
        assertEquals("completed", taskStore.getTask(id)?.state)
        val verdicts = pipelineStore.listVerdicts(id)
        assertEquals(1, verdicts.size)
        assertEquals(ReviewVerdict.APPROVE, verdicts[0].verdict)
        assertEquals("brain-x", verdicts[0].reviewer)
        assertEquals(PipelineStage.DONE, pipelineStore.getState(id)?.stage)
        val kinds = eventKinds(id)
        assertTrue(kinds.contains(TaskEventKind.PIPELINE_REVIEW_STARTED))
        assertTrue(kinds.contains(TaskEventKind.PIPELINE_REVIEW_APPROVED))
        // 同轮不重复审查：阶段已是 done，再跑直接跳过。
        assertTrue(runner(candidates = { candidates }).runReview(id) is ReviewerResult.Skipped)
        assertEquals(1, caller.calls.size)
    }

    @Test
    fun reviewerChangesRequestedReworksWithChecklist() = runBlocking {
        val id = "t-review-rework"
        createTask(id)
        enableAndApprove(id)
        finishCode(id)
        val candidates = listOf(PipelineOrchestrator.ReviewerCandidate("brain-x", null, "审查者"))
        caller.handler = { _, _, _ ->
            PipelineAgentCallResult.Text("""{"verdict":"changes_requested","checklist":["补单测","修边界条件"]}""")
        }

        val result = runner(candidates = { candidates }).runReview(id)

        assertTrue(result is ReviewerResult.ChangesRequested)
        assertEquals(1, (result as ReviewerResult.ChangesRequested).reworkRounds)
        assertEquals("rework", taskStore.getTask(id)?.state)
        assertEquals(PipelineStage.CODE, pipelineStore.getState(id)?.stage)
        assertEquals(1, pipelineStore.getState(id)?.reworkRounds)
        val verdicts = pipelineStore.listVerdicts(id)
        assertEquals(1, verdicts.size)
        assertEquals(listOf("补单测", "修边界条件"), verdicts[0].checklist)
        assertTrue(eventKinds(id).contains(TaskEventKind.PIPELINE_REWORK_REQUESTED))
    }

    @Test
    fun reviewerInvalidVerdictFailsTaskWithoutRecording() = runBlocking {
        val id = "t-review-bad"
        createTask(id)
        enableAndApprove(id)
        finishCode(id)
        val candidates = listOf(PipelineOrchestrator.ReviewerCandidate("brain-x", null, "审查者"))
        caller.handler = { _, _, _ -> PipelineAgentCallResult.Text("看着还行，差不多能用了。") }

        val result = runner(candidates = { candidates }).runReview(id)

        assertTrue(result is ReviewerResult.Failed)
        val task = taskStore.getTask(id)!!
        assertEquals("failed", task.state)
        assertTrue(task.needsAttention)
        assertTrue((task.error ?: "").contains("无法解析"))
        assertEquals(0, pipelineStore.listVerdicts(id).size)
        assertTrue(eventKinds(id).contains(TaskEventKind.PIPELINE_REVIEW_FAILED))
    }

    @Test
    fun reviewerNoAnswerFailsTask() = runBlocking {
        val id = "t-review-silent"
        createTask(id)
        enableAndApprove(id)
        finishCode(id)
        val candidates = listOf(PipelineOrchestrator.ReviewerCandidate("brain-x", null, "审查者"))
        caller.handler = { _, _, _ -> PipelineAgentCallResult.Failure("调用超时（600 秒未返回）") }

        val result = runner(candidates = { candidates }).runReview(id)

        assertTrue(result is ReviewerResult.Failed)
        assertEquals("failed", taskStore.getTask(id)?.state)
        assertTrue(taskStore.getTask(id)?.needsAttention == true)
    }

    @Test
    fun mechanicalFailureSkipsLlmReview() = runBlocking {
        val id = "t-review-mech"
        createTask(id)
        enableAndApprove(id, planWithTests())
        finishCode(id)
        val candidates = listOf(PipelineOrchestrator.ReviewerCandidate("brain-x", null, "审查者"))
        caller.handler = { _, _, _ -> PipelineAgentCallResult.Text("approve") }

        // plan 要求跑 pytest 但测试 verdict 是 NOT_RUN：机械 FAILED。
        val result = runner(candidates = { candidates }).runReview(id)

        assertTrue(result is ReviewerResult.ChangesRequested)
        // LLM 审查根本没调（省一次调用，也守住了顺序）。
        assertEquals(0, caller.calls.size)
        assertEquals("rework", taskStore.getTask(id)?.state)
        val verdicts = pipelineStore.listVerdicts(id)
        assertEquals(1, verdicts.size)
        assertEquals(PipelineOrchestrator.MECHANICAL_REVIEWER, verdicts[0].reviewer)
        assertTrue(verdicts[0].checklist.isNotEmpty())
    }

    @Test
    fun reviewerNoCandidatesFailsTask() = runBlocking {
        val id = "t-review-nobody"
        createTask(id)
        enableAndApprove(id)
        finishCode(id)
        caller.handler = { _, _, _ -> PipelineAgentCallResult.Text("approve") }

        val result = runner(candidates = { emptyList() }).runReview(id)

        assertTrue(result is ReviewerResult.Failed)
        assertEquals("failed", taskStore.getTask(id)?.state)
        assertEquals(0, caller.calls.size)
    }

    @Test
    fun reworkExhaustionAfterTwoRoundsFailsTask() = runBlocking {
        val id = "t-review-exhaust"
        createTask(id)
        enableAndApprove(id)
        val candidates = listOf(PipelineOrchestrator.ReviewerCandidate("brain-x", null, "审查者"))
        caller.handler = { _, _, _ ->
            PipelineAgentCallResult.Text("""{"verdict":"changes_requested","checklist":["再改一版"]}""")
        }
        val r = runner(candidates = { candidates })
        finishCode(id)
        assertTrue(r.runReview(id) is ReviewerResult.ChangesRequested)
        // 回到 review：rework → assigned → working → review。
        scheduler.claimTask(id, "codex-mac")
        scheduler.startTask(id)
        moveTo(id, "review")
        assertTrue(orchestrator.advanceCodeToReview(id))
        assertTrue(r.runReview(id) is ReviewerResult.ChangesRequested)
        assertEquals(2, pipelineStore.getState(id)?.reworkRounds)
        scheduler.claimTask(id, "codex-mac")
        scheduler.startTask(id)
        moveTo(id, "review")
        assertTrue(orchestrator.advanceCodeToReview(id))
        // 第 3 次打回：已达 2 轮上限，任务进 failed。
        val third = r.runReview(id)
        assertTrue(third is ReviewerResult.Failed)
        val task = taskStore.getTask(id)!!
        assertEquals("failed", task.state)
        assertTrue(task.needsAttention)
        assertEquals(3, pipelineStore.listVerdicts(id).size)
        assertTrue(eventKinds(id).contains(TaskEventKind.PIPELINE_REWORK_EXHAUSTED))
    }

    @Test
    fun pumpOnceRunsPlannerThenReviewerAndDefersWhenBusy() = runBlocking {
        val planId = "t-pump-plan"
        createTask(planId)
        assertTrue(orchestrator.enablePipeline(planId) is PipelineResult.Ok)
        caller.handler = { _, _, _ -> PipelineAgentCallResult.Text(planJson()) }
        assertTrue(runner().pumpOnce())
        assertEquals(PipelinePlanStatus.DRAFT, pipelineStore.getState(planId)?.planStatus)

        val reviewId = "t-pump-review"
        createTask(reviewId)
        enableAndApprove(reviewId)
        finishCode(reviewId)
        caller.handler = { _, _, _ -> PipelineAgentCallResult.Text("approve") }
        val candidates = listOf(PipelineOrchestrator.ReviewerCandidate("brain-x", null, "审查者"))
        assertTrue(runner(candidates = { candidates }).pumpOnce())
        assertEquals("completed", taskStore.getTask(reviewId)?.state)

        // 有在途工作时整轮 defer，不调 Agent。
        val callsBefore = caller.calls.size
        assertFalse(runner(isWorkInHand = { true }).pumpOnce())
        assertEquals(callsBefore, caller.calls.size)
    }

    @Test
    fun parsePlanJsonAcceptsFencedJsonAndRejectsGarbage() {
        assertNotNull(parsePlanJson("```json\n${planJson()}\n```"))
        assertNull(parsePlanJson("没有 JSON，只有一段话"))
        assertNull(parsePlanJson("""{"version":1,"objective":"","steps":[],"acceptance_criteria":[]}"""))
        assertNull(parsePlanJson("""{"version":99,"objective":"x","steps":["s"],"acceptance_criteria":["a"]}"""))
    }

    @Test
    fun parseReviewVerdictRules() {
        assertEquals(ReviewVerdict.APPROVE, parseReviewVerdict("approve")?.verdict)
        assertEquals(
            ReviewVerdict.APPROVE,
            parseReviewVerdict("""{"verdict":"approve"}""")?.verdict,
        )
        val rework = parseReviewVerdict(
            """{"verdict":"changes_requested","checklist":["补单测"]}""",
        )
        assertEquals(ReviewVerdict.CHANGES_REQUESTED, rework?.verdict)
        assertEquals(listOf("补单测"), rework?.checklist)
        val plain = parseReviewVerdict("changes_requested\n- 补单测\n- 修边界")
        assertEquals(ReviewVerdict.CHANGES_REQUESTED, plain?.verdict)
        assertEquals(listOf("补单测", "修边界"), plain?.checklist)
        // 非法值 / 歧义 / 空清单一律无效，不猜。
        assertNull(parseReviewVerdict("""{"verdict":"looks_good"}"""))
        assertNull(parseReviewVerdict("approve 吧，不过 changes_requested 也行"))
        assertNull(parseReviewVerdict("还行"))
        assertNull(parseReviewVerdict("changes_requested"))
        assertNull(parseReviewVerdict("""{"verdict":"changes_requested","checklist":[]}"""))
    }
}
