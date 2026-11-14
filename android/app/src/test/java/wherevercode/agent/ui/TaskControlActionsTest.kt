package wherevercode.agent.ui

import android.app.Application
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.core.control.*
import wherevercode.agent.core.security.InMemoryAuthorizationStore
import wherevercode.agent.core.security.InMemoryRevocationStore
import wherevercode.agent.core.task.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskControlActionsTest {
    private lateinit var db: TaskDatabase
    private lateinit var tasks: RoomTaskStore
    private lateinit var plane: ControlPlane
    private lateinit var actions: TaskControlActions

    @Before fun setup() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication<Application>())
        tasks = RoomTaskStore(db)
        plane = ControlPlane(tasks, InMemoryAuthorizationStore(), InMemoryRevocationStore(),
            controlStore = RoomControlStore(db), auditLog = RoomControlAuditLog(db.controlAuditDao()))
        actions = TaskControlActions(tasks, plane)
    }
    @After fun close() { db.close() }

    private fun create(id: String, working: Boolean = false, session: String? = null) {
        var task = tasks.createTask(TaskRecord(taskId = id, title = "test", state = "queued",
            sessionId = session, assignedAgentId = "remote", createdAt = 1, updatedAt = 1, version = 1))
        if (working) {
            task = tasks.updateTask(id, mapOf("state" to "assigned"), task.version, 1, 1)!!
            tasks.updateTask(id, mapOf("state" to "working"), task.version, 1, 1)
        }
    }

    @Test fun stopAndDeletePreserveRemoteWorkUntilReceipt() {
        create("remote", working = true)
        val result = actions.request("remote", ControlAction.STOP)
        assertTrue(result.ok)
        assertFalse(result.confirmed)
        assertTrue(TaskControlActions.message(result).contains("尚未确认"))
        assertEquals("working", tasks.getTask("remote")!!.state)
        assertFalse(actions.deleteAfterConfirmedStop("remote"))
        assertNotNull(tasks.getTask("remote"))
        plane.confirmRemoteStopped("remote", "receipt", "remote stopped")
        assertEquals("cancelled", tasks.getTask("remote")!!.state)
        assertTrue(actions.deleteAfterConfirmedStop("remote"))
        assertNull(tasks.getTask("remote"))
    }

    @Test fun queuedPauseResumeAndRetargetUsePersistedControlCounters() {
        create("queued")
        assertTrue(actions.request("queued", ControlAction.PAUSE).confirmed)
        assertEquals(ControlStatus.PAUSED, plane.getControlStatus("queued"))
        assertTrue(actions.request("queued", ControlAction.RESUME).confirmed)
        val version = tasks.getTask("queued")!!.goalVersion
        assertTrue(actions.request("queued", ControlAction.CHANGE_GOAL, "new objective").ok)
        assertEquals(version + 1, tasks.getTask("queued")!!.goalVersion)
        assertEquals("new objective", tasks.getTask("queued")!!.description)
        assertTrue(actions.deleteAfterConfirmedStop("queued"))
    }

    @Test fun activeRetargetAndChatMirrorDeleteAreRefused() {
        create("working", working = true)
        assertFalse(actions.request("working", ControlAction.CHANGE_GOAL, "replacement").ok)
        assertEquals(1L, tasks.getTask("working")!!.goalVersion)
        create("mirror", session = "chat:1")
        assertFalse(actions.deleteAfterConfirmedStop("mirror"))
        assertNotNull(tasks.getTask("mirror"))
    }
}
