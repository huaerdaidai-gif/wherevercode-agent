package wherevercode.agent.core.task

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * P2 流水线持久化（2026-11-11），Robolectric + 真实 Room（内存库）：
 * pipeline_states 一任务一行（plan JSON 往返）、pipeline_verdicts
 * append-only（只增不改）、阶段推进的审计事件与状态写同一事务。
 */
@RunWith(RobolectricTestRunner::class)
class PipelineStoreTest {
    private lateinit var db: TaskDatabase
    private lateinit var store: RoomPipelineStore
    private lateinit var events: RoomTaskEventStore

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        store = RoomPipelineStore(db)
        events = RoomTaskEventStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun plan() = PlanArtifact(
        objective = "做加法",
        filesInScope = listOf("calculator.py"),
        steps = listOf("实现 add"),
        acceptanceCriteria = listOf("add(1, 2) == 3"),
        testCommands = listOf("pytest"),
    )

    @Test
    fun `state round trip keeps stage plan and rework rounds`() {
        val state = PipelineState(
            taskId = "t1",
            stage = PipelineStage.CODE,
            planStatus = PipelinePlanStatus.APPROVED,
            planJson = plan().toJson(),
            reworkRounds = 1,
            coderAgentId = "codex-mac",
            createdAtEpochSeconds = 1_700_000_000L,
            updatedAtEpochSeconds = 1_700_000_001L,
        )
        store.saveState(state)
        val restored = store.getState("t1")!!
        assertEquals(PipelineStage.CODE, restored.stage)
        assertEquals(1, restored.reworkRounds)
        assertEquals("codex-mac", restored.coderAgentId)
        assertEquals("做加法", restored.approvedPlan?.objective)
        assertEquals(listOf("calculator.py"), restored.approvedPlan?.filesInScope)
    }

    @Test
    fun `unknown task has no state`() {
        assertNull(store.getState("no-such-task"))
    }

    @Test
    fun `verdicts append in order and never change`() {
        val first = PipelineVerdictRecord(
            verdictId = "v1", taskId = "t1", round = 0,
            verdict = ReviewVerdict.CHANGES_REQUESTED, reviewer = "reviewer-a",
            checklist = listOf("补单测"), createdAtEpochSeconds = 1_700_000_000L,
        )
        val second = PipelineVerdictRecord(
            verdictId = "v2", taskId = "t1", round = 1,
            verdict = ReviewVerdict.APPROVE, reviewer = "reviewer-a",
            createdAtEpochSeconds = 1_700_000_001L,
        )
        store.recordVerdict(first)
        store.recordVerdict(second)
        val listed = store.listVerdicts("t1")
        assertEquals(listOf(first, second), listed)
        assertEquals("补单测", listed[0].checklist.single())
        // 第二次读，历史一条不少（只增不改）。
        assertEquals(2, store.listVerdicts("t1").size)
    }

    @Test
    fun `stage event is written in the same transaction as the state`() {
        val task = TaskRecord(taskId = "t1", title = "加法", state = "queued")
        val state = PipelineState(taskId = "t1", stage = PipelineStage.PLAN)
        val event = TaskEvent(
            eventId = "e1", taskId = "t1", attemptId = null,
            kind = TaskEventKind.PIPELINE_ENABLED, fromState = "queued", toState = "queued",
            actor = "user", reason = "启用流水线", createdAtEpochSeconds = 1_700_000_000L,
        )
        store.saveState(state, event)
        assertNotNull(store.getState("t1"))
        val logged = events.listEvents("t1")
        assertEquals(1, logged.size)
        assertEquals(TaskEventKind.PIPELINE_ENABLED, logged[0].kind)
        assertEquals("启用流水线", logged[0].reason)
    }

    @Test
    fun `in-memory store keeps verdicts and events for tests`() {
        val memory = InMemoryPipelineStore()
        memory.saveState(PipelineState(taskId = "t9", stage = PipelineStage.PLAN))
        memory.recordVerdict(
            PipelineVerdictRecord(
                verdictId = "v9", taskId = "t9", round = 0,
                verdict = ReviewVerdict.APPROVE, reviewer = "user",
            ),
        )
        assertEquals(PipelineStage.PLAN, memory.getState("t9")?.stage)
        assertEquals(1, memory.listVerdicts("t9").size)
        assertTrue(memory.recordedEvents.isEmpty())
    }
}
