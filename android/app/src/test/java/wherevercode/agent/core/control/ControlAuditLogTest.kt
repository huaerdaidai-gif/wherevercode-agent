package wherevercode.agent.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.security.InMemoryAuthorizationStore
import wherevercode.agent.core.security.InMemoryRevocationStore
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore

/**
 * ControlAuditLog tests (M2, plan §2.1). Persistence across restart is
 * covered by ControlStoreTest (Room); this file tests audit semantics.
 */
class ControlAuditLogTest {

    private class FakeTaskStore : TaskStore {
        private val map = mutableMapOf<String, TaskRecord>()
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

    /** An audit log that always fails (simulates a broken audit write). */
    private class FailingAuditLog : ControlAuditLog {
        override fun record(entry: ControlAuditEntry): ControlAuditEntry =
            throw IllegalStateException("audit disk full")

        override fun listForTask(taskId: String): List<ControlAuditEntry> = emptyList()
        override fun listAll(): List<ControlAuditEntry> = emptyList()
    }

    private lateinit var taskStore: FakeTaskStore
    private lateinit var controlStore: InMemoryControlStore
    private lateinit var auditLog: InMemoryControlAuditLog

    @Before
    fun setUp() {
        taskStore = FakeTaskStore()
        taskStore.createTask(TaskRecord(taskId = "t1", title = "t1", state = "queued"))
        controlStore = InMemoryControlStore()
        auditLog = InMemoryControlAuditLog()
    }

    private fun plane(log: ControlAuditLog = auditLog) = ControlPlane(
        taskStore = taskStore,
        authStore = InMemoryAuthorizationStore(),
        revocationStore = InMemoryRevocationStore(),
        controlStore = controlStore,
        auditLog = log,
    )

    @Test
    fun `each command writes exactly one audit row with its operationId`() {
        plane().pause("t1", "op-1", "pause")
        plane().resume("t1", "op-2", "resume")
        val entries = auditLog.listForTask("t1")
        assertEquals(2, entries.size)
        assertEquals("op-1", entries[0].operationId)
        assertEquals("PAUSE", entries[0].action)
        assertEquals("op-2", entries[1].operationId)
        assertEquals("RESUME", entries[1].action)
        assertTrue(entries.all { it.ok })
        assertEquals("user", entries[0].actor)
    }

    @Test
    fun `a failed audit write rolls the command back`() {
        val failingPlane = plane(FailingAuditLog())
        val result = failingPlane.pause("t1", "op-1", "pause")
        assertFalse(result.ok)
        assertEquals("control_failed", result.errorCode)
        // The command rolled back: no PAUSED state, no dedup row.
        assertEquals(ControlStatus.RUNNING, plane().getControlStatus("t1"))
        assertEquals(null, controlStore.getOperation("op-1"))
    }

    @Test
    fun `audit rows never contain credential or file content`() {
        plane().pause("t1", "op-1", "pause")
        val entry = auditLog.listForTask("t1").single()
        val text = listOf(
            entry.taskId, entry.actor, entry.action, entry.operationId,
            entry.errorCode, entry.reason,
        ).joinToString("|")
        assertFalse(text.contains("sig_b64"))
        assertFalse(text.contains("credential"))
        assertEquals(1L, entry.goalVersion)
        assertEquals(1L, entry.epoch)
    }

    @Test
    fun `refused commands are audited with ok false`() {
        taskStore.createTask(TaskRecord(taskId = "t2", title = "t2", state = "completed"))
        val result = plane().pause("t2", "op-9", "pause")
        assertFalse(result.ok)
        val entries = auditLog.listForTask("t2")
        assertEquals(1, entries.size)
        assertFalse(entries[0].ok)
        assertEquals("control_terminal_task", entries[0].errorCode)
    }
}
