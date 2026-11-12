package wherevercode.agent.core.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.security.InMemoryAuthorizationStore
import wherevercode.agent.core.security.InMemoryRevocationStore
import wherevercode.agent.core.security.TaskCredential
import wherevercode.agent.core.security.TaskCredentialIssuer
import wherevercode.agent.core.security.toWire
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore

/**
 * ExecutionFence tests (M2, plan §2.1). Pure JVM: in-memory stores.
 * The "hand.call zero times on denial" half is covered by HandServerTest.
 */
class ExecutionFenceTest {

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

    private val signingKey = "test-signing-key".toByteArray(Charsets.UTF_8)
    private val now = 1_700_000_000L
    private lateinit var authStore: InMemoryAuthorizationStore
    private lateinit var revocation: InMemoryRevocationStore
    private lateinit var controlStore: InMemoryControlStore
    private lateinit var fence: ExecutionFence

    private val task = TaskRecord(taskId = "t1", title = "t1", state = "working")

    @Before
    fun setUp() {
        authStore = InMemoryAuthorizationStore()
        authStore.grant("agent-1", "/proj")
        revocation = InMemoryRevocationStore()
        controlStore = InMemoryControlStore()
        fence = ExecutionFence(
            taskStore = FakeTaskStore(task),
            authStore = authStore,
            revocationStore = revocation,
            controlStore = controlStore,
            signingKey = signingKey,
            nowEpochSeconds = { now },
        )
    }

    private fun credential(
        epoch: Long = 1,
        goalVersion: Long = 1,
        at: Long = now,
        taskId: String = "t1",
    ): TaskCredential = TaskCredentialIssuer.issue(
        userId = "user-1", taskId = taskId, attempt = "a1",
        sourceDeviceId = "dev-a", targetDeviceId = "dev-b", agentId = "agent-1",
        folder = "/proj", signingKey = signingKey, epoch = epoch, goalVersion = goalVersion,
        nowEpochSeconds = at,
    )

    private fun request(
        action: String = "write",
        wire: String? = credential().toWire(),
        callerReadOnly: Boolean = false,
    ) = FenceRequest(
        taskId = "t1", agentId = "agent-1", folder = "/proj", action = action,
        credentialWire = wire, targetDeviceId = "dev-b",
        userId = "user-1", attempt = "a1", sourceDeviceId = "dev-a",
        callerReadOnly = callerReadOnly,
    )

    @Test
    fun `all four checks pass allows the write`() {
        val result = fence.check(request())
        assertTrue(result.allowed)
        assertFalse(result.readOnly)
    }

    @Test
    fun `missing grant denies with a distinct code`() {
        authStore.revoke("agent-1", "/proj")
        val result = fence.check(request())
        assertFalse(result.allowed)
        assertEquals(FenceErrors.NO_GRANT, result.errorCode)
        assertTrue(result.messageZh!!.contains("授权"))
    }

    @Test
    fun `forged signature denies as invalid, expired credential as expired`() {
        val forged = credential().copy(signature = ByteArray(32) { 0 })
        assertEquals(FenceErrors.CREDENTIAL_INVALID, fence.check(request(wire = forged.toWire())).errorCode)
        val expired = credential(at = now - 1000)
        assertEquals(FenceErrors.CREDENTIAL_EXPIRED, fence.check(request(wire = expired.toWire())).errorCode)
    }

    @Test
    fun `missing credential denies`() {
        val result = fence.check(request(wire = null))
        assertFalse(result.allowed)
        assertEquals(FenceErrors.CREDENTIAL_MISSING, result.errorCode)
    }

    @Test
    fun `revoked credential denies`() {
        val cred = credential()
        revocation.revoke(cred.credentialId)
        assertEquals(FenceErrors.CREDENTIAL_REVOKED, fence.check(request(wire = cred.toWire())).errorCode)
    }

    @Test
    fun `stale epoch denies with a distinct code`() {
        controlStore.bumpAuthEpoch("t1") // current epoch is now 2.
        assertEquals(FenceErrors.EPOCH_STALE, fence.check(request()).errorCode)
    }

    @Test
    fun `stale goal version denies with a distinct code`() {
        controlStore.bumpGoalVersion("t1") // current goalVersion is now 2.
        assertEquals(FenceErrors.GOAL_VERSION_STALE, fence.check(request()).errorCode)
    }

    @Test
    fun `PAUSED and STOPPING deny writes but allow reads`() {
        controlStore.setControlStatus("t1", ControlStatus.PAUSED)
        val pausedWrite = fence.check(request("write"))
        assertFalse(pausedWrite.allowed)
        assertEquals(FenceErrors.PAUSED, pausedWrite.errorCode)
        assertTrue(pausedWrite.readOnly)
        assertTrue(fence.check(request("read")).allowed)
        assertTrue(fence.check(request("list")).allowed)
        controlStore.setControlStatus("t1", ControlStatus.STOPPING)
        assertEquals(FenceErrors.STOPPING, fence.check(request("run")).errorCode)
        assertTrue(fence.check(request("read")).allowed)
    }

    @Test
    fun `wrong task binding denies`() {
        val other = credential(taskId = "t2")
        // The fence expects task t1; the credential belongs to t2.
        val result = fence.check(request(wire = other.toWire()))
        assertFalse(result.allowed)
        assertEquals(FenceErrors.CREDENTIAL_BINDING, result.errorCode)
    }

    @Test
    fun `caller reported readOnly is ignored`() {
        // Caller claims readOnly=true on a RUNNING task: ignored, write allowed.
        assertTrue(fence.check(request(callerReadOnly = true)).allowed)
        // Caller claims readOnly=false on a PAUSED task: ignored, write still denied.
        controlStore.setControlStatus("t1", ControlStatus.PAUSED)
        assertFalse(fence.check(request(callerReadOnly = false)).allowed)
    }

    @Test
    fun `unknown task denies`() {
        val result = fence.check(request().copy(taskId = "ghost"))
        assertFalse(result.allowed)
        assertEquals(FenceErrors.TASK_NOT_FOUND, result.errorCode)
    }

    @Test
    fun `terminal task denies writes`() {
        val terminalFence = ExecutionFence(
            taskStore = FakeTaskStore(task.copy(state = "completed")),
            authStore = authStore,
            revocationStore = revocation,
            controlStore = controlStore,
            signingKey = signingKey,
            nowEpochSeconds = { now },
        )
        val result = terminalFence.check(request())
        assertFalse(result.allowed)
        assertEquals(FenceErrors.TASK_NOT_RUNNING, result.errorCode)
    }
}
