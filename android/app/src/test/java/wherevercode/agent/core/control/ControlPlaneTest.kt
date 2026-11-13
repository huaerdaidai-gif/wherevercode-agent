package wherevercode.agent.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.security.InMemoryAuthorizationStore
import wherevercode.agent.core.security.InMemoryRevocationStore
import wherevercode.agent.core.security.TaskCredentialIssuer
import wherevercode.agent.core.security.CredentialCheck
import wherevercode.agent.core.security.CredentialExpectation
import wherevercode.agent.core.task.ControlVersionStale
import wherevercode.agent.core.task.StaleTaskUpdate
import wherevercode.agent.core.task.TaskControlCounterStore
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStore

/**
 * ControlPlane tests (M2, plan §2.1). Pure JVM: in-memory stores only,
 * no Room. A controllable clock drives the 120s observe threshold.
 */
class ControlPlaneTest {

    /** Test double for the Room TaskStore: same version/counter checks, no Room. */
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
                        val target = value as String
                        TaskStates.checkTransition(next.state, target)
                        next.copy(state = target)
                    }
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

    private lateinit var taskStore: FakeTaskStore
    private lateinit var controlStore: InMemoryControlStore
    private lateinit var auditLog: InMemoryControlAuditLog
    private lateinit var revocation: InMemoryRevocationStore
    private lateinit var plane: ControlPlane
    private var now = 1_700_000_000L

    private val signingKey = "test-signing-key".toByteArray(Charsets.UTF_8)

    @Before
    fun setUp() {
        taskStore = FakeTaskStore()
        controlStore = InMemoryControlStore(nowEpochSeconds = { now })
        auditLog = InMemoryControlAuditLog()
        revocation = InMemoryRevocationStore()
        plane = ControlPlane(
            taskStore = taskStore,
            authStore = InMemoryAuthorizationStore(),
            revocationStore = revocation,
            controlStore = controlStore,
            auditLog = auditLog,
            nowEpochSeconds = { now },
        )
    }

    private fun task(id: String, state: String = "queued", leaseExpiresAt: Long? = null) =
        TaskRecord(taskId = id, title = "task $id", state = state, leaseExpiresAt = leaseExpiresAt)

    private fun create(id: String, state: String = "queued", leaseExpiresAt: Long? = null) =
        taskStore.createTask(task(id, state, leaseExpiresAt))

    @Test
    fun `pause with nothing in flight lands PAUSED directly`() {
        create("t1", "queued")
        val result = plane.pause("t1", "op-1", "user pause")
        assertTrue(result.ok)
        assertTrue(result.confirmed)
        assertEquals(ControlStatus.PAUSED, result.status)
        assertEquals(ControlStatus.PAUSED, plane.getControlStatus("t1"))
    }

    @Test
    fun `pause with work in flight is PAUSING, task still working, unconfirmed`() {
        create("t1", "working", leaseExpiresAt = now + 90)
        val result = plane.pause("t1", "op-1", "user pause")
        assertTrue(result.ok)
        assertFalse(result.confirmed)
        assertEquals(ControlStatus.PAUSING, result.status)
        assertEquals("working", taskStore.getTask("t1")?.state)
    }

    @Test
    fun `PAUSING to PAUSED confirms, and an unconfirmable executor stays PAUSING`() {
        create("t1", "working", leaseExpiresAt = now + 90)
        plane.pause("t1", "op-1", "user pause")
        // Executor still running with a live lease: cannot confirm.
        val stillWaiting = plane.confirmPaused("t1", "op-2", "confirm")
        assertTrue(stillWaiting.ok)
        assertFalse(stillWaiting.confirmed)
        assertEquals(ControlStatus.PAUSING, stillWaiting.status)
        // Lease expires: the executor is gone, confirmation lands.
        now += 91
        val confirmed = plane.confirmPaused("t1", "op-3", "confirm")
        assertTrue(confirmed.ok)
        assertTrue(confirmed.confirmed)
        assertEquals(ControlStatus.PAUSED, confirmed.status)
        // Confirming again is idempotent.
        val again = plane.confirmPaused("t1", "op-4", "confirm")
        assertTrue(again.ok)
        assertTrue(again.confirmed)
        assertEquals(ControlStatus.PAUSED, again.status)
    }

    @Test
    fun `orphaned local task pause freezes then requeues only after lease expiry`() {
        taskStore.createTask(
            task("orphan", "working", leaseExpiresAt = now + 90)
                .copy(assignedAgentId = "local"),
        )
        var controlCalls = 0
        plane.configureLocalExecutionControl(
            isExecuting = { false },
            handler = { _, _ -> controlCalls++; true },
        )

        val result = plane.pause("orphan", "op-orphan-pause", "user pause")

        assertTrue(result.ok)
        assertFalse(result.confirmed)
        assertEquals(ControlStatus.PAUSING, result.status)
        assertTrue(controlStore.isDispatchFrozen("orphan"))
        assertEquals(0, controlCalls)
        assertEquals("working", taskStore.getTask("orphan")!!.state)
        assertFalse(plane.resume("orphan", "op-orphan-early-resume", "too early").ok)
        assertTrue(controlStore.isDispatchFrozen("orphan"))

        now += 91
        plane.reconcileExpiredControls()
        assertEquals("requeued", taskStore.getTask("orphan")!!.state)
        assertEquals(ControlStatus.PAUSED, plane.getControlStatus("orphan"))
        assertFalse(controlStore.isDispatchFrozen("orphan"))
        assertTrue(plane.resume("orphan", "op-orphan-resume", "continue").ok)
        assertEquals(ControlStatus.RUNNING, plane.getControlStatus("orphan"))
    }

    @Test
    fun `orphaned local stop stays frozen then cancels after lease expiry without rerun`() {
        taskStore.createTask(
            task("orphan-stop", "working", leaseExpiresAt = now + 90)
                .copy(assignedAgentId = "local"),
        )
        var controlCalls = 0
        plane.configureLocalExecutionControl(
            isExecuting = { false },
            handler = { _, _ -> controlCalls++; true },
        )

        val result = plane.stop("orphan-stop", "op-orphan-stop", "user stop")
        assertTrue(result.ok)
        assertFalse(result.confirmed)
        assertEquals(ControlStatus.STOPPING, result.status)
        assertTrue(controlStore.isDispatchFrozen("orphan-stop"))
        assertEquals(0, controlCalls)

        now += 91
        plane.reconcileExpiredControls()

        val stopped = taskStore.getTask("orphan-stop")!!
        assertEquals("cancelled", stopped.state)
        assertFalse(TaskStates.isClaimable(stopped.state))
        assertEquals(ControlStatus.STOPPING, plane.getControlStatus("orphan-stop"))
        assertFalse(controlStore.isDispatchFrozen("orphan-stop"))
        assertEquals(0, controlCalls)
    }

    @Test
    fun `confirmed pause releases freeze and resume makes requeued task claimable`() {
        taskStore.createTask(
            task("pause-resume", "working", leaseExpiresAt = now + 90)
                .copy(assignedAgentId = "local"),
        )
        // Isolate ControlPlane from the live LocalTaskRunner while modeling
        // its safe-point transition working -> requeued.
        plane.configureLocalExecutionControl(isExecuting = { true }, handler = { _, _ -> true })
        assertEquals(
            ControlStatus.PAUSING,
            plane.pause("pause-resume", "op-pause", "user pause").status,
        )
        taskStore.updateTask(
            "pause-resume", mapOf("state" to "requeued"), 1, 1, 1,
        )

        val paused = plane.confirmPaused("pause-resume", "op-confirm", "safe point")
        assertTrue(paused.confirmed)
        assertEquals(ControlStatus.PAUSED, paused.status)
        assertFalse(controlStore.isDispatchFrozen("pause-resume"))

        val resumed = plane.resume("pause-resume", "op-resume", "continue")
        assertTrue(resumed.ok)
        assertEquals(ControlStatus.RUNNING, resumed.status)
        assertEquals("requeued", taskStore.getTask("pause-resume")!!.state)
    }

    @Test
    fun `resume after pause returns RUNNING and is idempotent without bump`() {
        create("t1", "queued")
        plane.pause("t1", "op-1", "pause")
        assertEquals(ControlStatus.PAUSED, plane.getControlStatus("t1"))
        val resumed = plane.resume("t1", "op-2", "resume")
        assertTrue(resumed.ok)
        assertTrue(resumed.confirmed)
        assertEquals(ControlStatus.RUNNING, resumed.status)
        // Resuming a RUNNING task is a no-op and bumps nothing.
        val goalBefore = controlStore.goalVersion("t1")
        val epochBefore = controlStore.authEpoch("t1")
        val again = plane.resume("t1", "op-3", "resume")
        assertTrue(again.ok)
        assertEquals(goalBefore, controlStore.goalVersion("t1"))
        assertEquals(epochBefore, controlStore.authEpoch("t1"))
    }

    @Test
    fun `pause and resume on a terminal task are refused`() {
        create("t1", "completed")
        val paused = plane.pause("t1", "op-1", "pause")
        assertFalse(paused.ok)
        assertEquals("control_terminal_task", paused.errorCode)
        val resumed = plane.resume("t1", "op-2", "resume")
        assertFalse(resumed.ok)
        assertEquals("control_terminal_task", resumed.errorCode)
    }

    @Test
    fun `stop with work in flight is STOPPING with cancel pending, task not cancelled, unconfirmed`() {
        create("t1", "working", leaseExpiresAt = now + 90)
        val result = plane.stop("t1", "op-1", "user stop")
        assertTrue(result.ok)
        assertFalse(result.confirmed)
        assertEquals(ControlStatus.STOPPING, result.status)
        assertEquals("working", taskStore.getTask("t1")?.state)
        assertTrue(controlStore.isCancelPending("t1"))
        assertEquals(ControlStatus.STOPPING, plane.getControlStatus("t1"))
    }

    @Test
    fun `stop confirmation lands the terminal state`() {
        create("t1", "working", leaseExpiresAt = now + 90)
        plane.stop("t1", "op-1", "stop")
        now += 91 // lease expires: executor gone.
        val confirmed = plane.confirmStop("t1", "op-2", "confirm")
        assertTrue(confirmed.ok)
        assertTrue(confirmed.confirmed)
        assertEquals("cancelled", taskStore.getTask("t1")?.state)
        assertFalse(controlStore.isCancelPending("t1"))
    }

    @Test
    fun `stop with nothing in flight cancels directly`() {
        create("t1", "queued")
        val result = plane.stop("t1", "op-1", "stop")
        assertTrue(result.ok)
        assertTrue(result.confirmed)
        assertEquals("cancelled", taskStore.getTask("t1")?.state)
    }

    @Test
    fun `repeat stop is no error and does not bump again`() {
        create("t1", "working", leaseExpiresAt = now + 90)
        val first = plane.stop("t1", "op-1", "stop")
        assertTrue(first.ok)
        val goalBefore = controlStore.goalVersion("t1")
        val epochBefore = controlStore.authEpoch("t1")
        val second = plane.stop("t1", "op-2", "stop again")
        assertTrue(second.ok)
        assertFalse(second.confirmed)
        assertEquals(ControlStatus.STOPPING, second.status)
        assertEquals(goalBefore, controlStore.goalVersion("t1"))
        assertEquals(epochBefore, controlStore.authEpoch("t1"))
    }

    @Test
    fun `stop still unconfirmed after 120s stays STOPPING and blocks new dispatch`() {
        create("t1", "working", leaseExpiresAt = now + 3600)
        plane.stop("t1", "op-1", "stop")
        now += 121 // past the 120s observe threshold.
        val confirmed = plane.confirmStop("t1", "op-2", "confirm")
        assertTrue(confirmed.ok)
        assertFalse("120s 后仍未确认不得强制落终态", confirmed.confirmed)
        assertEquals(ControlStatus.STOPPING, confirmed.status)
        assertEquals("working", taskStore.getTask("t1")?.state)
        assertTrue("未确认的停止继续阻止新派单", controlStore.isDispatchFrozen("t1"))
    }

    @Test
    fun `task ending naturally before stop confirmation keeps its result`() {
        create("t1", "working", leaseExpiresAt = now + 90)
        plane.stop("t1", "op-1", "stop")
        // The task completes on its own (working -> review -> completed).
        taskStore.updateTask("t1", mapOf("state" to "review"), 1, 1, 1)
        taskStore.updateTask("t1", mapOf("state" to "completed"), 2, 1, 1)
        val confirmed = plane.confirmStop("t1", "op-2", "confirm")
        assertTrue(confirmed.ok)
        assertTrue(confirmed.confirmed)
        assertEquals("completed", taskStore.getTask("t1")?.state)
    }

    @Test
    fun `retarget bumps goalVersion by one`() {
        create("t1", "queued")
        assertEquals(1L, controlStore.goalVersion("t1"))
        val result = plane.retarget("t1", "new goal", "op-1", "retarget")
        assertTrue(result.ok)
        assertEquals(2L, controlStore.goalVersion("t1"))
        assertEquals(2L, taskStore.getTask("t1")?.goalVersion)
        assertEquals("new goal", taskStore.getTask("t1")?.description)
    }

    @Test
    fun `revoke bumps epoch and invalidates old credentials`() {
        create("t1", "queued")
        val authStore = InMemoryAuthorizationStore()
        authStore.grant("agent-1", "/proj")
        val credential = TaskCredentialIssuer.issue(
            userId = "user-1", taskId = "t1", attempt = "a1",
            sourceDeviceId = "dev-a", targetDeviceId = "dev-b", agentId = "agent-1",
            folder = "/proj", signingKey = signingKey, epoch = 1, goalVersion = 1,
            nowEpochSeconds = now,
        )
        val expectation = CredentialExpectation(
            userId = "user-1", taskId = "t1", attempt = "a1", sourceDeviceId = "dev-a",
            targetDeviceId = "dev-b", agentId = "agent-1", folder = "/proj",
            epoch = 1, goalVersion = 1,
        )
        assertEquals(
            CredentialCheck.OK,
            TaskCredentialIssuer.verifyV2(credential, expectation, signingKey, revocation, authStore, now + 10),
        )
        val result = plane.revoke("t1", "op-1", "revoke")
        assertTrue(result.ok)
        assertEquals(2L, controlStore.authEpoch("t1"))
        // The old credential is now refused (task revoked; epoch also stale).
        assertEquals(
            CredentialCheck.TASK_REVOKED,
            TaskCredentialIssuer.verifyV2(credential, expectation, signingKey, revocation, authStore, now + 10),
        )
    }

    @Test
    fun `takeover freezes dispatch, flags attention, audits, and shows unconfirmed while external work runs`() {
        create("t1", "working", leaseExpiresAt = now + 90)
        val auditBefore = auditLog.listAll().size
        val result = plane.takeover("t1", "op-1", "takeover")
        assertTrue(result.ok)
        assertFalse("外部进程未停，须明确显示未确认", result.confirmed)
        assertEquals(ControlStatus.PAUSING, result.status)
        assertTrue(controlStore.isDispatchFrozen("t1"))
        assertEquals(true, taskStore.getTask("t1")?.needsAttention)
        assertEquals(auditBefore + 1, auditLog.listAll().size)
        // Old execution rights are fenced: the task is revoked.
        assertTrue(revocation.isTaskRevoked("t1"))
    }

    @Test
    fun `same operationId retried executes once`() {
        create("t1", "queued")
        val first = plane.retarget("t1", "goal v2", "op-same", "retarget")
        assertTrue(first.ok)
        assertEquals(2L, controlStore.goalVersion("t1"))
        val auditCount = auditLog.listAll().size
        val second = plane.retarget("t1", "goal v3", "op-same", "retarget again")
        assertTrue(second.ok)
        assertEquals("同一 operationId 重试只执行一次", 2L, controlStore.goalVersion("t1"))
        assertEquals("goal v2", taskStore.getTask("t1")?.description)
        assertEquals("审计只多 0 条（重放不记新审计）", auditCount, auditLog.listAll().size)
    }

    @Test
    fun `control action set is exactly the six first-batch actions`() {
        // U10: person-swap is a later explicit action; there is no entry
        // point for it here — the enum itself must not contain it.
        val names = ControlAction.values().map { it.name }.toSet()
        assertEquals(setOf("PAUSE", "RESUME", "STOP", "CHANGE_GOAL", "REVOKE", "TAKEOVER"), names)
    }

    @Test
    fun `control commands are not queued behind frozen dispatch`() {
        create("t1", "working", leaseExpiresAt = now + 90)
        plane.takeover("t1", "op-1", "takeover")
        assertTrue(controlStore.isDispatchFrozen("t1"))
        // Control still runs while dispatch is frozen.
        val stopped = plane.stop("t1", "op-2", "stop while frozen")
        assertTrue(stopped.ok)
        assertEquals(ControlStatus.STOPPING, stopped.status)
    }
}
