package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.control.InMemoryControlStore
import wherevercode.agent.AgentDiscovery

/**
 * TaskScheduler freeze tests (M2, plan §2.1). In-memory task store +
 * in-memory control store as the freeze port; Robolectric only because
 * AgentDiscovery needs a real Context at construction (the claim path
 * never calls discovery — only matchAgent does, and no test here does).
 * The anonymous `object : Context() {}` subclass does not compile
 * against the real Android SDK (Context has abstract members such as
 * getAssets()), so the application context comes from Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class TaskSchedulerTest {

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

        override fun deleteTask(taskId: String): Boolean = tasks.remove(taskId) != null
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

    @Test
    fun `frozen dispatch blocks new claims`() {
        queued("t1")
        controlStore.setDispatchFrozen("t1", true)
        assertFalse("冻结时 canClaim 为 false", scheduler.canClaim())
        assertNull("冻结时 claimTask 返回 null", scheduler.claimTask("t1", "agent-1"))
        assertEquals("queued", taskStore.getTask("t1")?.state)
    }

    @Test
    fun `unfreezing restores normal claiming`() {
        queued("t1")
        controlStore.setDispatchFrozen("t1", true)
        assertNull(scheduler.claimTask("t1", "agent-1"))
        controlStore.setDispatchFrozen("t1", false)
        assertTrue(scheduler.canClaim())
        val claimed = scheduler.claimTask("t1", "agent-1")
        assertNotNull(claimed)
        assertEquals("assigned", claimed?.state)
    }

    @Test
    fun `freezing does not affect already claimed work`() {
        queued("t1")
        val claimed = scheduler.claimTask("t1", "agent-1")
        assertNotNull(claimed)
        // Freeze lands after the claim: the claimed task keeps working.
        controlStore.setDispatchFrozen("t1", true)
        assertEquals("assigned", taskStore.getTask("t1")?.state)
        assertEquals(1, scheduler.activeWorkCount())
        // And a second task cannot be claimed while any task is frozen.
        queued("t2")
        assertNull(scheduler.claimTask("t2", "agent-1"))
    }

    @Test
    fun `claim bumps attempt and issues an attempt id with a 600s lease`() {
        queued("t1")
        val before = System.currentTimeMillis() / 1000
        val claimed = scheduler.claimTask("t1", "agent-1")
        assertNotNull(claimed)
        assertEquals("assigned", claimed?.state)
        assertEquals(1, claimed?.attempt)
        assertEquals(32, claimed?.attemptId?.length)
        assertEquals("agent-1", claimed?.leaseOwner)
        // 租约 600 秒（P0，编码任务时长），允许调用前后 1 秒误差。
        val lease = claimed?.leaseExpiresAt ?: 0L
        assertTrue("lease $lease should be ~600s after $before", lease - before in 599L..601L)
        // 第二次认领同一任务被拒（已不是可认领态）。
        assertNull(scheduler.claimTask("t1", "agent-1"))
    }

    // 拆成两条单测：认领器一次只认一个任务（canClaim 的单任务规则），
    // 同一条单测里连认两次，第二认会被单任务规则挡住、与认领态无关。
    @Test
    fun `claim admits requeued tasks`() {
        taskStore.createTask(TaskRecord(taskId = "t1", title = "t1", state = "requeued", attempt = 1))
        val claimed = scheduler.claimTask("t1", "agent-1")
        assertNotNull(claimed)
        assertEquals(2, claimed?.attempt)
        assertEquals("assigned", claimed?.state)
    }

    @Test
    fun `claim admits rework tasks`() {
        taskStore.createTask(TaskRecord(taskId = "t2", title = "t2", state = "rework", attempt = 2))
        val reclaimed = scheduler.claimTask("t2", "agent-1")
        assertNotNull(reclaimed)
        assertEquals(3, reclaimed?.attempt)
        assertEquals("assigned", reclaimed?.state)
    }

    @Test
    fun `requestRework moves review to rework and refuses other states`() {
        taskStore.createTask(TaskRecord(taskId = "t1", title = "t1", state = "review"))
        val reworked = scheduler.requestRework("t1", "审查打回：缺少测试")
        assertNotNull(reworked)
        assertEquals("rework", reworked?.state)
        // review → working 直边已删：打回只能走 rework。
        assertNull(scheduler.requestRework("t1", "再打回一次"))
        taskStore.createTask(TaskRecord(taskId = "t2", title = "t2", state = "queued"))
        assertNull(scheduler.requestRework("t2", "未审核不能打回"))
    }

    @Test
    fun `non-RUNNING control states are not claimable`() {
        queued("t1")
        controlStore.setControlStatus("t1", wherevercode.agent.core.control.ControlStatus.PAUSED)
        assertNull(scheduler.claimTask("t1", "agent-1"))
        controlStore.setControlStatus("t1", wherevercode.agent.core.control.ControlStatus.RUNNING)
        assertNotNull(scheduler.claimTask("t1", "agent-1"))
    }

    @Test
    fun `review does not count as work in hand`() {
        taskStore.createTask(TaskRecord(taskId = "t1", title = "t1", state = "review"))
        assertEquals(0, scheduler.activeWorkCount())
        assertTrue(scheduler.canClaim())
        queued("t2")
        assertNotNull(scheduler.claimTask("t2", "agent-1"))
    }
}
