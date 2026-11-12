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
import java.util.UUID

/**
 * 任务拆分持久化（2026-11-11，大脑总管自动拆分），Robolectric +
 * 真实 Room（内存库）：decomposition_states 一父任务一行（方案
 * JSON 往返）、decomposition_subtasks append-only（只增不改）、
 * 状态推进的审计事件与状态写同一事务。
 */
@RunWith(RobolectricTestRunner::class)
class DecompositionStoreTest {
    private lateinit var db: TaskDatabase
    private lateinit var store: RoomDecompositionStore
    private lateinit var events: RoomTaskEventStore

    private val now = 1_700_000_000L

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        store = RoomDecompositionStore(db)
        events = RoomTaskEventStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun state() = DecompositionState(
        taskId = "parent-1",
        status = DecompositionStatus.PROPOSED,
        integrationBranch = "integration/parent-1",
        baseBranch = "main",
        proposalJson = TaskDecomposer.proposalToJson(
            DecompositionProposal.Splittable(
                listOf(
                    SubtaskSpec(0, "a", "做 A", listOf("a/Foo.kt")),
                    SubtaskSpec(1, "b", "做 B", listOf("b/Bar.kt")),
                ),
            ),
        ),
        createdAtEpochSeconds = now,
        updatedAtEpochSeconds = now,
    )

    @Test
    fun `state round trip keeps status branches and proposal`() {
        store.saveState(state())
        val restored = store.getState("parent-1")!!
        assertEquals(DecompositionStatus.PROPOSED, restored.status)
        assertEquals("integration/parent-1", restored.integrationBranch)
        assertEquals("main", restored.baseBranch)
        val proposal = restored.proposal!!
        assertEquals(2, proposal.subtasks.size)
        assertEquals(listOf("a/Foo.kt"), proposal.subtasks[0].files)
        assertNull(store.getState("no-such-task"))
    }

    @Test
    fun `subtasks append only and list by parent in index order`() {
        store.saveState(state())
        val first = DecompositionSubtaskRecord(
            subtaskTaskId = "sub-1",
            parentTaskId = "parent-1",
            subtaskIndex = 0,
            files = listOf("a/Foo.kt"),
            branch = "task/sub-1",
            objective = "做 A",
            createdAtEpochSeconds = now,
        )
        val second = first.copy(subtaskTaskId = "sub-2", subtaskIndex = 1, files = listOf("b/Bar.kt"), branch = "task/sub-2", objective = "做 B")
        store.insertSubtask(second)
        store.insertSubtask(first)
        val listed = store.listSubtasks("parent-1")
        assertEquals(listOf("sub-1", "sub-2"), listed.map { it.subtaskTaskId })
        assertEquals(listOf("a/Foo.kt"), listed[0].files)
        assertTrue(store.listSubtasks("no-such-parent").isEmpty())
    }

    @Test
    fun `state save with event lands both in one transaction`() {
        val task = TaskRecord(taskId = "parent-1", title = "父任务", state = "queued", createdAt = now, updatedAt = now)
        val event = TaskEvent(
            eventId = UUID.randomUUID().toString(),
            taskId = "parent-1",
            attemptId = null,
            kind = TaskEventKind.DECOMPOSITION_PROPOSED,
            fromState = "queued",
            toState = "queued",
            actor = "user",
            reason = "提出拆分",
            createdAtEpochSeconds = now,
        )
        store.saveState(state(), event)
        assertNotNull(store.getState("parent-1"))
        val listed = events.listEvents("parent-1")
        assertEquals(1, listed.size)
        assertEquals(TaskEventKind.DECOMPOSITION_PROPOSED, listed[0].kind)
    }

    @Test
    fun `in memory store round trip and recorded events`() {
        val memory = InMemoryDecompositionStore()
        memory.saveState(state())
        assertEquals(DecompositionStatus.PROPOSED, memory.getState("parent-1")?.status)
        val event = TaskEvent(
            eventId = UUID.randomUUID().toString(),
            taskId = "parent-1",
            attemptId = null,
            kind = TaskEventKind.DECOMPOSITION_APPROVED,
            fromState = "queued",
            toState = "queued",
            actor = "user",
            reason = "批准拆分",
            createdAtEpochSeconds = now,
        )
        memory.saveState(state().copy(status = DecompositionStatus.ACTIVE), event)
        assertEquals(DecompositionStatus.ACTIVE, memory.getState("parent-1")?.status)
        assertEquals(1, memory.recordedEvents.size)
        assertEquals(TaskEventKind.DECOMPOSITION_APPROVED, memory.recordedEvents[0].kind)
    }
}
