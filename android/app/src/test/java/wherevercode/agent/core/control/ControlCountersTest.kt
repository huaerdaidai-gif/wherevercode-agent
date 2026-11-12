package wherevercode.agent.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.security.InMemoryAuthorizationStore
import wherevercode.agent.core.security.InMemoryRevocationStore
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore
import wherevercode.agent.core.task.TaskControlCounterStore

/**
 * GoalVersion + AuthorizationEpoch tests (M2, plan §2.1).
 * Persistence across restart is covered by ControlStoreTest (Room);
 * this file tests the in-memory counter semantics.
 */
class ControlCountersTest {

    /** Minimal TaskStore double (ControlPlane needs a store to read tasks). */
    private class FakeTaskStore : TaskStore, TaskControlCounterStore {
        val tasks = LinkedHashMap<String, TaskRecord>()
        override fun createTask(record: TaskRecord): TaskRecord {
            tasks[record.taskId] = record
            return record
        }

        override fun listTasks(projectId: String?, sessionId: String?): List<TaskRecord> =
            tasks.values.toList()

        override fun getTask(taskId: String): TaskRecord? = tasks[taskId]

        override fun updateTask(
            taskId: String,
            changes: Map<String, Any?>,
            expectedVersion: Long,
            expectedGoalVersion: Long,
            expectedAuthEpoch: Long,
        ): TaskRecord? {
            val current = tasks[taskId] ?: return null
            var next = current
            for ((key, value) in changes) {
                next = when (key) {
                    "state" -> next.copy(state = value as String)
                    "description" -> next.copy(description = value as? String)
                    "needs_attention" -> next.copy(needsAttention = value as Boolean)
                    else -> next
                }
            }
            next = next.copy(version = current.version + 1)
            tasks[taskId] = next
            return next
        }

        override fun updateControlCounters(taskId: String, goalVersion: Long, authEpoch: Long): TaskRecord? {
            val current = tasks[taskId] ?: return null
            val next = current.copy(goalVersion = goalVersion, authEpoch = authEpoch)
            tasks[taskId] = next
            return next
        }

        override fun expireLeases(nowEpochSeconds: Long): List<String> = emptyList()

        override fun deleteTask(taskId: String): Boolean = tasks.remove(taskId) != null
    }

    private lateinit var controlStore: InMemoryControlStore
    private lateinit var plane: ControlPlane
    private lateinit var taskStore: FakeTaskStore

    @Before
    fun setUp() {
        controlStore = InMemoryControlStore()
        taskStore = FakeTaskStore()
        taskStore.createTask(TaskRecord(taskId = "t1", title = "t1", state = "queued"))
        plane = ControlPlane(
            taskStore = taskStore,
            authStore = InMemoryAuthorizationStore(),
            revocationStore = InMemoryRevocationStore(),
            controlStore = controlStore,
            auditLog = InMemoryControlAuditLog(),
        )
    }

    @Test
    fun `counters start at 1`() {
        assertEquals(1L, GoalVersion(controlStore).current("t1"))
        assertEquals(1L, AuthorizationEpoch(controlStore).current("t1"))
        // Unknown tasks also read as 1 (no row yet).
        assertEquals(1L, GoalVersion(controlStore).current("never-seen"))
        assertEquals(1L, AuthorizationEpoch(controlStore).current("never-seen"))
    }

    @Test
    fun `counters only increase, never go back`() {
        val goal = GoalVersion(controlStore)
        val epoch = AuthorizationEpoch(controlStore)
        assertEquals(2L, goal.bump("t1"))
        assertEquals(3L, goal.bump("t1"))
        assertEquals(2L, epoch.bump("t1"))
        assertEquals(3L, goal.current("t1"))
        assertEquals(2L, epoch.current("t1"))
    }

    @Test
    fun `only ControlPlane bumps the counters`() {
        // Direct grant / revoke / credential-revoke calls that do NOT go
        // through ControlPlane must leave the counters untouched.
        val authStore = InMemoryAuthorizationStore()
        authStore.grant("agent-1", "/proj")
        authStore.revoke("agent-1", "/proj")
        val revocation = InMemoryRevocationStore()
        revocation.revoke("cred-1")
        revocation.revokeTask("t1")
        assertEquals(1L, controlStore.goalVersion("t1"))
        assertEquals(1L, controlStore.authEpoch("t1"))
        // Through ControlPlane they move: retarget bumps goal, revoke bumps epoch.
        plane.retarget("t1", "new goal", "op-1", "retarget")
        plane.revoke("t1", "op-2", "revoke")
        assertEquals(2L, controlStore.goalVersion("t1"))
        assertEquals(2L, controlStore.authEpoch("t1"))
    }

    @Test
    fun `claim snapshot equals the current counters`() {
        plane.retarget("t1", "goal v2", "op-1", "retarget")
        plane.revoke("t1", "op-2", "revoke")
        val task = taskStore.getTask("t1")
        assertEquals(2L, task?.goalVersion)
        assertEquals(2L, task?.authEpoch)
        assertEquals(2L, GoalVersion(controlStore).current("t1"))
        assertEquals(2L, AuthorizationEpoch(controlStore).current("t1"))
    }
}
