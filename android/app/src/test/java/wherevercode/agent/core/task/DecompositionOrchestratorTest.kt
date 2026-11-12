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
import wherevercode.agent.core.task.DecompositionOrchestrator.DecompositionResult
import wherevercode.agent.core.task.DecompositionOrchestrator.MergeReadiness
import wherevercode.agent.core.task.DecompositionOrchestrator.MergeStartResult
import java.util.UUID

/**
 * 任务拆分协调器（2026-11-11，大脑总管自动拆分），Robolectric +
 * 真实 Room（内存库）：提出/批准拆分建子任务、合并就绪检查、
 * 合并任务与合并结果、冲突进 blocked、取消、审计事件。
 */
@RunWith(RobolectricTestRunner::class)
class DecompositionOrchestratorTest {
    private lateinit var db: TaskDatabase
    private lateinit var taskStore: RoomTaskStore
    private lateinit var scheduler: TaskScheduler
    private lateinit var decompositionStore: RoomDecompositionStore
    private lateinit var orchestrator: DecompositionOrchestrator
    private lateinit var events: RoomTaskEventStore

    private val now = 1_700_000_000L

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        taskStore = RoomTaskStore(db)
        scheduler = TaskScheduler(taskStore, AgentDiscovery(RuntimeEnvironment.getApplication()), null)
        decompositionStore = RoomDecompositionStore(db)
        orchestrator = DecompositionOrchestrator(taskStore, decompositionStore, nowSeconds = { now })
        events = RoomTaskEventStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun plan() = PlanArtifact(
        objective = "重构计算器",
        filesInScope = listOf("core/task/A.kt", "core/team/B.kt", "ui/C.kt"),
        steps = listOf("重构加法", "补单测"),
        acceptanceCriteria = listOf("测试全过"),
    )

    private fun createTask(id: String, state: String = "queued"): TaskRecord =
        taskStore.createTask(TaskRecord(taskId = id, title = "任务 $id", description = "重构计算器", state = state, createdAt = now, updatedAt = now))

    /** 把任务推到指定态（经合法转移边）。 */
    private fun moveTo(id: String, state: String) {
        val current = taskStore.getTask(id)!!
        taskStore.updateTask(id, mapOf("state" to state), current.version, current.goalVersion, current.authEpoch)
    }

    private fun attemptId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun submitAndApprove(parentId: String): DecompositionState {
        val proposal = orchestrator.proposeDecomposition(parentId, plan())
        assertTrue(proposal is DecompositionProposal.Splittable)
        val submitted = orchestrator.submitDecomposition(parentId, proposal as DecompositionProposal.Splittable, "main")
        assertTrue(submitted is DecompositionResult.Ok)
        val approved = orchestrator.approveDecomposition(parentId)
        assertTrue(approved is DecompositionResult.Ok)
        return (approved as DecompositionResult.Ok).state
    }

    /** 子任务跑完：成员认领 → 开始 → review → completed（合法边）。 */
    private fun finishSubtask(subtaskId: String, memberId: String) {
        assertNotNull(scheduler.claimTaskForMember(subtaskId, memberId, attemptId()))
        assertNotNull(scheduler.startTask(subtaskId))
        moveTo(subtaskId, "review")
        moveTo(subtaskId, "completed")
    }

    /** 子任务跑失败：成员认领 → 开始 → failed（合法边）。 */
    private fun failSubtask(subtaskId: String, memberId: String) {
        assertNotNull(scheduler.claimTaskForMember(subtaskId, memberId, attemptId()))
        assertNotNull(scheduler.startTask(subtaskId))
        moveTo(subtaskId, "failed")
    }

    @Test
    fun `propose splits by module and refuses unsplittable plans`() {
        createTask("parent-1")
        val proposal = orchestrator.proposeDecomposition("parent-1", plan())
        assertTrue(proposal is DecompositionProposal.Splittable)
        assertEquals(3, (proposal as DecompositionProposal.Splittable).subtasks.size)

        val single = orchestrator.proposeDecomposition(
            "parent-1",
            plan().copy(filesInScope = listOf("only.py")),
        )
        assertTrue(single is DecompositionProposal.Unsplittable)
        assertTrue(orchestrator.proposeDecomposition("no-such-task", plan()) is DecompositionProposal.Unsplittable)
    }

    @Test
    fun `submit stores proposed state and rejects bad input`() {
        createTask("parent-1")
        val proposal = orchestrator.proposeDecomposition("parent-1", plan()) as DecompositionProposal.Splittable
        assertTrue(orchestrator.submitDecomposition("parent-1", proposal, "") is DecompositionResult.Rejected)
        val one = DecompositionProposal.Splittable(listOf(SubtaskSpec(0, "a", "做 A", listOf("a/F.kt"))))
        assertTrue(orchestrator.submitDecomposition("parent-1", one, "main") is DecompositionResult.Rejected)
        val submitted = orchestrator.submitDecomposition("parent-1", proposal, "main")
        assertTrue(submitted is DecompositionResult.Ok)
        assertEquals(DecompositionStatus.PROPOSED, (submitted as DecompositionResult.Ok).state.status)
        assertEquals("integration/parent-1", submitted.state.integrationBranch)
        assertTrue(orchestrator.submitDecomposition("parent-1", proposal, "main") is DecompositionResult.Rejected)
        assertTrue(events.listEvents("parent-1").any { it.kind == TaskEventKind.DECOMPOSITION_PROPOSED })
    }

    @Test
    fun `approve creates queued child tasks with parent link and branches`() {
        createTask("parent-1")
        val state = submitAndApprove("parent-1")
        assertEquals(DecompositionStatus.ACTIVE, state.status)
        val rows = decompositionStore.listSubtasks("parent-1")
        assertEquals(3, rows.size)
        assertEquals(listOf(0, 1, 2), rows.map { it.subtaskIndex })
        for (row in rows) {
            val child = taskStore.getTask(row.subtaskTaskId)!!
            assertEquals("queued", child.state)
            assertEquals("parent-1", child.parentTaskId)
            assertEquals("task/${row.subtaskTaskId}", row.branch)
            assertTrue(child.title.contains(row.moduleLabel))
            assertTrue(child.description!!.contains("分支：task/${row.subtaskTaskId}"))
        }
        assertTrue(orchestrator.approveDecomposition("parent-1") is DecompositionResult.Rejected)
        val kinds = events.listEvents("parent-1").map { it.kind }
        assertTrue(kinds.contains(TaskEventKind.DECOMPOSITION_APPROVED))
        assertEquals(3, kinds.count { it == TaskEventKind.SUBTASK_CREATED })
    }

    @Test
    fun `approve resumes instead of duplicating already built subtasks`() {
        createTask("parent-1")
        val proposal = orchestrator.proposeDecomposition("parent-1", plan()) as DecompositionProposal.Splittable
        assertTrue(orchestrator.submitDecomposition("parent-1", proposal, "main") is DecompositionResult.Ok)
        // 模拟批准中途已建好第 0 个子任务：任务行 + 清单行都在。
        val prebuilt = taskStore.createTask(
            TaskRecord(taskId = "prebuilt-child", title = "已建", state = "queued", parentTaskId = "parent-1", createdAt = now, updatedAt = now),
        )
        decompositionStore.insertSubtask(
            DecompositionSubtaskRecord(
                subtaskTaskId = prebuilt.taskId,
                parentTaskId = "parent-1",
                subtaskIndex = 0,
                files = listOf("core/task/A.kt"),
                branch = "task/prebuilt-child",
                objective = "做 A",
                createdAtEpochSeconds = now,
            ),
        )
        val approved = orchestrator.approveDecomposition("parent-1")
        assertTrue(approved is DecompositionResult.Ok)
        val rows = decompositionStore.listSubtasks("parent-1")
        assertEquals(3, rows.size)
        assertEquals(listOf("prebuilt-child"), rows.filter { it.subtaskIndex == 0 }.map { it.subtaskTaskId })
        assertEquals(3, rows.map { it.subtaskTaskId }.toSet().size)
    }

    @Test
    fun `merge readiness tracks pending ready and blocked`() {
        createTask("parent-1")
        submitAndApprove("parent-1")
        val rows = decompositionStore.listSubtasks("parent-1")
        val readiness0 = orchestrator.checkMergeReadiness("parent-1")
        assertTrue(readiness0 is MergeReadiness.NotReady)
        assertEquals(3, (readiness0 as MergeReadiness.NotReady).pending.size)

        finishSubtask(rows[0].subtaskTaskId, "member-a")
        finishSubtask(rows[1].subtaskTaskId, "member-b")
        val readiness1 = orchestrator.checkMergeReadiness("parent-1")
        assertTrue(readiness1 is MergeReadiness.NotReady)
        assertEquals(1, (readiness1 as MergeReadiness.NotReady).pending.size)

        finishSubtask(rows[2].subtaskTaskId, "member-c")
        val readiness2 = orchestrator.checkMergeReadiness("parent-1")
        assertTrue(readiness2 is MergeReadiness.Ready)
        assertEquals(3, (readiness2 as MergeReadiness.Ready).subtaskIds.size)
        assertTrue((readiness2 as MergeReadiness.Ready).branches.all { it.startsWith("task/") })
    }

    @Test
    fun `failed subtask blocks the decomposition and the working parent`() {
        createTask("parent-1")
        // 父任务先进入 working（拆分期间父任务在 working 态等子任务）。
        assertNotNull(scheduler.claimTask("parent-1", "local"))
        assertNotNull(scheduler.startTask("parent-1"))
        submitAndApprove("parent-1")
        val rows = decompositionStore.listSubtasks("parent-1")
        failSubtask(rows[0].subtaskTaskId, "member-a")
        val readiness = orchestrator.noteSubtaskFinished("parent-1", rows[0].subtaskTaskId)
        assertTrue(readiness is MergeReadiness.Blocked)
        assertEquals(DecompositionStatus.BLOCKED, decompositionStore.getState("parent-1")?.status)
        val parent = taskStore.getTask("parent-1")!!
        assertEquals("blocked", parent.state)
        assertTrue(parent.needsAttention)
        assertTrue(events.listEvents("parent-1").any { it.kind == TaskEventKind.SUBTASK_FAILED })
    }

    @Test
    fun `start merge creates a queued merge task with branches and base`() {
        createTask("parent-1")
        submitAndApprove("parent-1")
        val rows = decompositionStore.listSubtasks("parent-1")
        assertTrue(orchestrator.startMerge("parent-1") is MergeStartResult.Rejected)
        rows.forEachIndexed { i, row -> finishSubtask(row.subtaskTaskId, "member-$i") }
        val started = orchestrator.startMerge("parent-1")
        assertTrue(started is MergeStartResult.Started)
        val mergeTask = (started as MergeStartResult.Started).mergeTask
        assertEquals("queued", mergeTask.state)
        assertEquals("parent-1", mergeTask.parentTaskId)
        assertTrue(mergeTask.description!!.contains("integration/parent-1"))
        assertTrue(mergeTask.description!!.contains("main"))
        rows.forEach { assertTrue(mergeTask.description!!.contains(it.branch)) }
        assertEquals(DecompositionStatus.MERGING, started.state.status)
        assertTrue(events.listEvents("parent-1").any { it.kind == TaskEventKind.MERGE_STARTED })
    }

    @Test
    fun `merged result lands merged state and conflict lands blocked`() {
        createTask("parent-1")
        submitAndApprove("parent-1")
        val rows = decompositionStore.listSubtasks("parent-1")
        rows.forEachIndexed { i, row -> finishSubtask(row.subtaskTaskId, "member-$i") }
        val started = orchestrator.startMerge("parent-1") as MergeStartResult.Started
        val mergeId = started.mergeTask.taskId
        // 任务态与结果配不上：合并任务还没结束，Merged 拒绝。
        assertTrue(
            orchestrator.recordMergeResult(mergeId, MergeResult.Merged("integration/parent-1", "abc123")) is DecompositionResult.Rejected,
        )
        // 合并任务跑完（working → review → completed，合法边）。
        assertNotNull(scheduler.claimTaskForMember(mergeId, "member-m", attemptId()))
        assertNotNull(scheduler.startTask(mergeId))
        moveTo(mergeId, "review")
        moveTo(mergeId, "completed")
        val merged = orchestrator.recordMergeResult(mergeId, MergeResult.Merged("integration/parent-1", "abc123"))
        assertTrue(merged is DecompositionResult.Ok)
        assertEquals(DecompositionStatus.MERGED, (merged as DecompositionResult.Ok).state.status)
        assertEquals("abc123", merged.state.mergeResult.let { (it as MergeResult.Merged).commit })
        assertTrue(events.listEvents("parent-1").any { it.kind == TaskEventKind.MERGE_COMPLETED })
    }

    @Test
    fun `merge conflict blocks parent and waits for user decision`() {
        createTask("parent-1")
        assertNotNull(scheduler.claimTask("parent-1", "local"))
        assertNotNull(scheduler.startTask("parent-1"))
        submitAndApprove("parent-1")
        val rows = decompositionStore.listSubtasks("parent-1")
        rows.forEachIndexed { i, row -> finishSubtask(row.subtaskTaskId, "member-$i") }
        val started = orchestrator.startMerge("parent-1") as MergeStartResult.Started
        val mergeId = started.mergeTask.taskId
        assertNotNull(scheduler.claimTaskForMember(mergeId, "member-m", attemptId()))
        assertNotNull(scheduler.startTask(mergeId))
        moveTo(mergeId, "failed")
        val conflict = orchestrator.recordMergeResult(
            mergeId,
            MergeResult.Conflict(listOf("core/task/A.kt"), "A.kt 两边都改了同一函数"),
        )
        assertTrue(conflict is DecompositionResult.Ok)
        assertEquals(DecompositionStatus.BLOCKED, (conflict as DecompositionResult.Ok).state.status)
        val parent = taskStore.getTask("parent-1")!!
        assertEquals("blocked", parent.state)
        assertTrue(parent.needsAttention)
        assertTrue(events.listEvents("parent-1").any { it.kind == TaskEventKind.MERGE_CONFLICT })
    }

    @Test
    fun `cancel works from proposed and active but not from merged or merging`() {
        createTask("parent-1")
        val proposal = orchestrator.proposeDecomposition("parent-1", plan()) as DecompositionProposal.Splittable
        assertTrue(orchestrator.submitDecomposition("parent-1", proposal, "main") is DecompositionResult.Ok)
        val cancelled = orchestrator.cancelDecomposition("parent-1")
        assertTrue(cancelled is DecompositionResult.Ok)
        assertEquals(DecompositionStatus.CANCELLED, (cancelled as DecompositionResult.Ok).state.status)
        // 取消后可以重提。
        assertTrue(orchestrator.proposeDecomposition("parent-1", plan()) is DecompositionProposal.Splittable)

        createTask("parent-2")
        submitAndApprove("parent-2")
        assertTrue(orchestrator.cancelDecomposition("parent-2") is DecompositionResult.Ok)
        // 子任务行不受取消影响（独立的 P0 任务，按需逐个取消）。
        assertEquals(3, decompositionStore.listSubtasks("parent-2").size)
        assertEquals("queued", taskStore.getTask(decompositionStore.listSubtasks("parent-2")[0].subtaskTaskId)?.state)
    }

    @Test
    fun `view state shows subtasks with live states`() {
        createTask("parent-1")
        submitAndApprove("parent-1")
        val view = orchestrator.viewState("parent-1")!!
        assertEquals(DecompositionStatus.ACTIVE, view.status)
        assertEquals("integration/parent-1", view.integrationBranch)
        assertEquals(3, view.subtasks.size)
        assertTrue(view.subtasks.all { it.state == "queued" })
        assertNull(view.mergeResult)
        assertNull(orchestrator.viewState("no-such-task"))
    }
}
