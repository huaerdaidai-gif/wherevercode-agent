package wherevercode.agent.core.task

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.control.ControlPlane
import wherevercode.agent.core.control.ControlStatus
import wherevercode.agent.core.control.InMemoryControlAuditLog
import wherevercode.agent.core.control.RoomControlStore
import wherevercode.agent.core.security.RoomAuthorizationStore
import wherevercode.agent.core.security.RoomRevocationStore

@RunWith(RobolectricTestRunner::class)
class TaskClaimControlTransactionTest {
    private var firstDb: TaskDatabase? = null
    private var secondDb: TaskDatabase? = null

    private class PausingTaskStore(
        private val delegate: RoomTaskStore,
        private val updateReached: CountDownLatch,
        private val allowUpdate: CountDownLatch,
    ) : TaskStore, TaskControlCounterStore {
        override fun <T> withTaskLock(block: () -> T): T = delegate.withTaskLock(block)
        override fun createTask(record: TaskRecord) = delegate.createTask(record)
        override fun listTasks(projectId: String?, sessionId: String?) = delegate.listTasks(projectId, sessionId)
        override fun getTask(taskId: String) = delegate.getTask(taskId)
        override fun updateTask(
            taskId: String,
            changes: Map<String, Any?>,
            expectedVersion: Long,
            expectedGoalVersion: Long,
            expectedAuthEpoch: Long,
        ): TaskRecord? {
            if (changes["state"] == "assigned") {
                updateReached.countDown()
                check(allowUpdate.await(5, TimeUnit.SECONDS))
            }
            return delegate.updateTask(taskId, changes, expectedVersion, expectedGoalVersion, expectedAuthEpoch)
        }
        override fun expireLeases(nowEpochSeconds: Long) = delegate.expireLeases(nowEpochSeconds)
        override fun deleteTask(taskId: String) = delegate.deleteTask(taskId)
        override fun updateControlCounters(taskId: String, goalVersion: Long, authEpoch: Long) =
            delegate.updateControlCounters(taskId, goalVersion, authEpoch)
    }

    @After
    fun closeDatabases() {
        firstDb?.close()
        secondDb?.close()
    }

    @Test
    fun `pause and claim serialize across separate Room instances and start is frozen`() {
        val context = RuntimeEnvironment.getApplication()
        val dbName = "claim-control-${System.nanoTime()}.db"
        val dbA = TaskDatabase.create(context, dbName, allowMainThreadQueries = true)
        val dbB = TaskDatabase.create(context, dbName, allowMainThreadQueries = true)
        firstDb = dbA
        secondDb = dbB
        val storeA = RoomTaskStore(dbA)
        val storeB = RoomTaskStore(dbB)
        val controlA = RoomControlStore(dbA)
        val controlB = RoomControlStore(dbB)
        storeA.createTask(TaskRecord(taskId = "race-room-task", title = "race"))

        val plane = ControlPlane(
            taskStore = storeA,
            authStore = RoomAuthorizationStore(dbA),
            revocationStore = RoomRevocationStore(dbA),
            controlStore = controlA,
            auditLog = InMemoryControlAuditLog(),
            counterSync = storeA,
        )
        val updateReached = CountDownLatch(1)
        val allowUpdate = CountDownLatch(1)
        val gatedStore = PausingTaskStore(storeB, updateReached, allowUpdate)
        val scheduler = TaskScheduler(gatedStore, AgentDiscovery(context), controlB)
        var claimed: TaskRecord? = null
        val claimThread = Thread {
            claimed = scheduler.claimTaskForMember("race-room-task", "remote-agent", "attempt-room")
        }
        claimThread.start()
        assertTrue(updateReached.await(5, TimeUnit.SECONDS))

        val pauseStarted = CountDownLatch(1)
        val pauseFinished = CountDownLatch(1)
        var pauseStatus: ControlStatus? = null
        val pauseThread = Thread {
            pauseStarted.countDown()
            pauseStatus = plane.pause("race-room-task", "pause-room", "race test").status
            pauseFinished.countDown()
        }
        pauseThread.start()
        assertTrue(pauseStarted.await(5, TimeUnit.SECONDS))
        assertFalse("pause must wait for the in-flight claim transaction", pauseFinished.await(150, TimeUnit.MILLISECONDS))
        allowUpdate.countDown()
        claimThread.join(5_000)
        pauseThread.join(5_000)

        assertFalse(claimThread.isAlive)
        assertFalse(pauseThread.isAlive)
        assertNotNull(claimed)
        assertEquals(ControlStatus.PAUSING, pauseStatus)
        assertEquals(ControlStatus.PAUSING, controlB.getControlStatus("race-room-task"))
        assertNull("a frozen assigned task must not start", scheduler.startTask("race-room-task"))
        assertEquals("assigned", storeA.getTask("race-room-task")?.state)
    }
}
