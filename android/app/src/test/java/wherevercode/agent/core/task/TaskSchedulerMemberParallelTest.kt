package wherevercode.agent.core.task

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
import wherevercode.agent.core.control.InMemoryControlStore
import java.util.UUID

/**
 * 成员并行认领（2026-11-11，任务拆分）：成员路径的门禁是按成员的，
 * 不是全局的——不同成员可同时各持一个任务；同一成员仍一次一个；
 * 本地路径 [TaskScheduler.claimTask] 仍是全局一次一任务（本机大脑
 * 只有一个 TurnController）。
 *
 * FakeTaskStore 与 TaskSchedulerTest 同款（内存任务行 + 版本锁）。
 */
@RunWith(RobolectricTestRunner::class)
class TaskSchedulerMemberParallelTest {

    private class FakeTaskStore : TaskStore, TaskControlCounterStore {
        val tasks = LinkedHashMap<String, TaskRecord>()

        override fun createTask(record: TaskRecord): TaskRecord {
            tasks[record.taskId] = record
            return record
        }

        override fun listTasks(projectId: String?, sessionId: String?): List<TaskRecord> =
            tasks.values.toList()

        override fun getTask(taskId: String): TaskRecord? = tasks[taskId]

        override fun deleteTask(taskId: String): Boolean = tasks.remove(taskId) != null

        override fun updateTask(
            taskId: String,
            changes: Map<String, Any?>,
            expectedVersion: Long,
            expectedGoalVersion: Long,
            expectedAuthEpoch: Long,
        ): TaskRecord? {
            val current = tasks[taskId] ?: return null
            if (current.version != expectedVersion) throw StaleTaskUpdate(taskId, expectedVersion, current.version)
            if (current.goalVersion != expectedGoalVersion) {
                throw ControlVersionStale(taskId, ControlVersionStale.GOAL_VERSION, expectedGoalVersion, current.goalVersion)
            }
            if (current.authEpoch != expectedAuthEpoch) {
                throw ControlVersionStale(taskId, ControlVersionStale.AUTH_EPOCH, expectedAuthEpoch, current.authEpoch)
            }
            var next = current
            for ((key, value) in changes) {
                next = when (key) {
                    "state" -> {
                        TaskStates.checkTransition(next.state, value as String)
                        next.copy(state = value as String)
                    }
                    "assigned_agent_id" -> next.copy(assignedAgentId = value as? String)
                    "member_id" -> next.copy(memberId = value as? String)
                    "attempt" -> next.copy(attempt = (value as? Number)?.toInt() ?: next.attempt)
                    "attempt_id" -> next.copy(attemptId = value as? String)
                    "lease_owner" -> next.copy(leaseOwner = value as? String)
                    "lease_expires_at" -> next.copy(leaseExpiresAt = (value as? Number)?.toLong())
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
    }

    private lateinit var taskStore: FakeTaskStore
    private lateinit var controlStore: InMemoryControlStore
    private lateinit var scheduler: TaskScheduler

    @Before
    fun setUp() {
        taskStore = FakeTaskStore()
        controlStore = InMemoryControlStore()
        val discovery = AgentDiscovery(RuntimeEnvironment.getApplication())
        scheduler = TaskScheduler(taskStore, discovery, controlStore)
    }

    private fun queued(id: String) =
        taskStore.createTask(TaskRecord(taskId = id, title = id, state = "queued"))

    private fun attemptId(): String = UUID.randomUUID().toString().replace("-", "")

    @Test
    fun `two different members can hold tasks at the same time`() {
        queued("t1")
        queued("t2")
        val first = scheduler.claimTaskForMember("t1", "member-a", attemptId())
        assertNotNull(first)
        assertEquals("assigned", first?.state)
        assertEquals("member-a", first?.memberId)
        val second = scheduler.claimTaskForMember("t2", "member-b", attemptId())
        assertNotNull(second)
        assertEquals("assigned", second?.state)
        assertEquals(2, scheduler.activeWorkCount())
        assertEquals(1, scheduler.activeWorkCountForMember("member-a"))
        assertEquals(1, scheduler.activeWorkCountForMember("member-b"))
    }

    @Test
    fun `same member cannot hold two tasks`() {
        queued("t1")
        queued("t2")
        assertNotNull(scheduler.claimTaskForMember("t1", "member-a", attemptId()))
        assertFalse(scheduler.canClaimForMember("member-a"))
        assertNull(scheduler.claimTaskForMember("t2", "member-a", attemptId()))
        assertEquals("queued", taskStore.getTask("t2")?.state)
    }

    @Test
    fun `local claim stays globally single task`() {
        queued("t1")
        queued("t2")
        assertNotNull(scheduler.claimTask("t1", "local"))
        assertFalse(scheduler.canClaim())
        assertNull(scheduler.claimTask("t2", "local"))
        assertNull(scheduler.claimTask("t2", "other-agent"))
    }

    @Test
    fun `member claim works while a local task is in hand`() {
        queued("t1")
        queued("t2")
        assertNotNull(scheduler.claimTask("t1", "local"))
        // 本地任务在途不挡成员认领（不同执行体，不共享 TurnController）。
        assertTrue(scheduler.canClaimForMember("member-a"))
        assertNotNull(scheduler.claimTaskForMember("t2", "member-a", attemptId()))
    }

    @Test
    fun `frozen task does not block other members claims`() {
        queued("t1")
        queued("t2")
        controlStore.setDispatchFrozen("t2", true)
        assertTrue(scheduler.canClaimForMember("member-a"))
        assertNotNull(scheduler.claimTaskForMember("t1", "member-a", attemptId()))
        assertNull(scheduler.claimTaskForMember("t2", "member-b", attemptId()))
        assertEquals("queued", taskStore.getTask("t2")?.state)
        controlStore.setDispatchFrozen("t2", false)
        assertNotNull(scheduler.claimTaskForMember("t2", "member-b", attemptId()))
    }
}
