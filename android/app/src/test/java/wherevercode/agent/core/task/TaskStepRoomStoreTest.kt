package wherevercode.agent.core.task

import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Room tests (Robolectric — the DAOs need an Android Context).
 * Run in CI (`./gradlew testDebugUnitTest`); they cannot run on a plain
 * JVM without Robolectric, so locally they only need to compile.
 *
 * Covers the V5 Phase B C1 (W2) task_steps table: DAO round-trip +
 * [RoomTaskStepStore] behaviour parity with [InMemoryTaskStepStore]
 * (same-second insertion order, finish/close semantics, status
 * validation).
 */
@RunWith(RobolectricTestRunner::class)
class TaskStepRoomStoreTest {
    private lateinit var db: TaskDatabase
    private lateinit var store: RoomTaskStepStore

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        store = RoomTaskStepStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun step(
        toolCallId: String,
        toolName: String,
        startedAt: Long = System.currentTimeMillis() / 1000,
    ) = TaskStep(
        stepId = UUID.randomUUID().toString(),
        taskId = "t1",
        toolCallId = toolCallId,
        toolName = toolName,
        argsSummary = "args",
        startedAtEpochSeconds = startedAt,
    )

    @Test
    fun `add and list round-trip with all fields`() {
        val added = store.addStep(step("c1", "write_file", 1_700_000_000L))
        val back = store.listSteps("t1").single()
        assertEquals(added.stepId, back.stepId)
        assertEquals("write_file", back.toolName)
        assertEquals("args", back.argsSummary)
        assertEquals(TaskStepStatus.RUNNING, back.status)
        assertNull(back.resultSummary)
        assertEquals(1_700_000_000L, back.startedAtEpochSeconds)
        assertNull(back.finishedAtEpochSeconds)
        assertTrue(store.listSteps("t2").isEmpty())
    }

    @Test
    fun `same-second steps keep insertion order`() {
        val now = System.currentTimeMillis() / 1000
        store.addStep(step("c1", "write_file", now))
        store.addStep(step("c2", "run_command", now))
        store.addStep(step("c3", "read_file", now))
        assertEquals(
            listOf("write_file", "run_command", "read_file"),
            store.listSteps("t1").map { it.toolName },
        )
    }

    @Test
    fun `earlier steps sort before later ones`() {
        val now = System.currentTimeMillis() / 1000
        store.addStep(step("c2", "run_command", now + 5))
        store.addStep(step("c1", "write_file", now))
        assertEquals(
            listOf("write_file", "run_command"),
            store.listSteps("t1").map { it.toolName },
        )
    }

    @Test
    fun `finishStep moves a running step to success with result and finish time`() {
        store.addStep(step("c1", "write_file", 1_700_000_000L))
        val finished = store.finishStep("t1", "c1", TaskStepStatus.SUCCESS, "wrote 3 lines")!!
        assertEquals(TaskStepStatus.SUCCESS, finished.status)
        assertEquals("wrote 3 lines", finished.resultSummary)
        assertTrue((finished.finishedAtEpochSeconds ?: 0) > 0)
        assertEquals(TaskStepStatus.SUCCESS, store.listSteps("t1").single().status)
    }

    @Test
    fun `finishStep on a finished step returns it unchanged`() {
        store.addStep(step("c1", "write_file", 1_700_000_000L))
        store.finishStep("t1", "c1", TaskStepStatus.SUCCESS, "done")
        val again = store.finishStep("t1", "c1", TaskStepStatus.FAILED, "late error")!!
        assertEquals(TaskStepStatus.SUCCESS, again.status)
        assertEquals("done", again.resultSummary)
    }

    @Test
    fun `finishStep on an unknown step returns null`() {
        assertNull(store.finishStep("t1", "nope", TaskStepStatus.SUCCESS, null))
    }

    @Test
    fun `finishStep with an unknown status throws`() {
        store.addStep(step("c1", "write_file"))
        assertThrows(IllegalArgumentException::class.java) {
            store.finishStep("t1", "c1", "weird", null)
        }
    }

    @Test
    fun `closeRunningSteps closes only running steps and counts them`() {
        store.addStep(step("c1", "write_file"))
        store.addStep(step("c2", "run_command"))
        store.finishStep("t1", "c1", TaskStepStatus.SUCCESS, "done")
        assertEquals(1, store.closeRunningSteps("t1"))
        val steps = store.listSteps("t1")
        assertEquals(TaskStepStatus.SUCCESS, steps.first { it.toolCallId == "c1" }.status)
        assertEquals(TaskStepStatus.CANCELLED, steps.first { it.toolCallId == "c2" }.status)
        assertTrue((steps.first { it.toolCallId == "c2" }.finishedAtEpochSeconds ?: 0) > 0)
        assertEquals(0, store.closeRunningSteps("t1"))
    }

    @Test
    fun `closeRunningSteps with an unknown status throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            store.closeRunningSteps("t1", "weird")
        }
    }

    @Test
    fun `re-adding the same stepId overwrites like the in-memory store`() {
        val first = step("c1", "write_file", 1_700_000_000L)
        store.addStep(first)
        store.addStep(first.copy(toolName = "read_file"))
        val back = store.listSteps("t1")
        assertEquals(1, back.size)
        assertEquals("read_file", back.single().toolName)
    }
}
