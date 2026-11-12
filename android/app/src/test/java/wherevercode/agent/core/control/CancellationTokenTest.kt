package wherevercode.agent.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore

/**
 * CancellationToken tests (M2, plan §2.1). Persistence across restart
 * is covered by ControlStoreTest (Room); this file tests token semantics.
 */
class CancellationTokenTest {

    private class FakeTaskStore(vararg tasks: TaskRecord) : TaskStore {
        private val map = tasks.associateBy { it.taskId }.toMutableMap()
        override fun createTask(record: TaskRecord): TaskRecord {
            map[record.taskId] = record
            return record
        }

        override fun listTasks(projectId: String?, sessionId: String?): List<TaskRecord> =
            map.values.toList()

        override fun getTask(taskId: String): TaskRecord? = map[taskId]

        override fun updateTask(
            taskId: String,
            changes: Map<String, Any?>,
            expectedVersion: Long,
            expectedGoalVersion: Long,
            expectedAuthEpoch: Long,
        ): TaskRecord? = map[taskId]

        override fun expireLeases(nowEpochSeconds: Long): List<String> = emptyList()

        override fun deleteTask(taskId: String): Boolean = map.remove(taskId) != null
    }

    private lateinit var store: InMemoryControlStore
    private var now = 1_700_000_000L
    private lateinit var token: CancellationToken

    @Before
    fun setUp() {
        store = InMemoryControlStore(nowEpochSeconds = { now })
        token = CancellationToken(store, FakeTaskStore(), nowEpochSeconds = { now })
    }

    @Test
    fun `request is true, confirm is false and clears`() {
        assertFalse(token.isRequested("t1"))
        assertTrue(token.request("t1"))
        assertTrue(token.isRequested("t1"))
        assertEquals(now, token.requestedAt("t1"))
        assertTrue(token.confirm("t1"))
        assertFalse(token.isRequested("t1"))
        assertEquals(null, token.requestedAt("t1"))
    }

    @Test
    fun `confirm without request is refused`() {
        assertFalse(token.confirm("t1"))
        assertFalse(token.isRequested("t1"))
    }

    @Test
    fun `past 120s the token is still pending, never auto-cleared`() {
        token.request("t1")
        now += 121
        assertTrue("120s 后仍未确认须保持待确认", token.isRequested("t1"))
        assertTrue(token.isPastObserveThreshold("t1"))
        assertTrue(token.confirm("t1"))
        assertFalse(token.isRequested("t1"))
    }

    @Test
    fun `request on a terminal task is a no-op`() {
        val terminalStore = FakeTaskStore(TaskRecord(taskId = "t9", state = "completed"))
        val terminalToken = CancellationToken(store, terminalStore, nowEpochSeconds = { now })
        assertFalse(terminalToken.request("t9"))
        assertFalse(terminalToken.isRequested("t9"))
    }

    @Test
    fun `request twice keeps the first timestamp`() {
        token.request("t1")
        val first = token.requestedAt("t1")
        now += 10
        token.request("t1")
        assertEquals(first, token.requestedAt("t1"))
    }
}
