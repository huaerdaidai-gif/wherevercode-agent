package wherevercode.agent.core.task

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.control.ControlStateEntity

/**
 * Room DAO tests (Robolectric — the DAO needs an Android Context).
 * Run in CI (`./gradlew testDebugUnitTest`); they cannot run on a plain
 * JVM without Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class TaskStoreTest {
    private lateinit var db: TaskDatabase
    private lateinit var store: RoomTaskStore

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        store = RoomTaskStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun record(id: String, state: String = "queued") = TaskRecord(
        taskId = id,
        title = "task $id",
        description = "desc",
        state = state,
        createdAt = 1_700_000_000L,
        updatedAt = 1_700_000_000L,
        version = 1,
    )

    @Test
    fun `create and get round-trip`() {
        val created = store.createTask(record("t1"))
        assertEquals("t1", created.taskId)
        assertEquals("queued", created.state)
        assertEquals(1, created.version)
        assertEquals("t1", store.getTask("t1")?.taskId)
    }

    @Test
    fun `selected Hand route is persisted with the task and survives task updates`() {
        val created = store.createTask(
            record("t-hand").copy(
                handDeviceId = "device-b",
                handAuthorizationId = "grant-b-project",
                handFolder = "/authorized/project",
            ),
        )
        assertEquals("device-b", created.handDeviceId)
        assertEquals("grant-b-project", created.handAuthorizationId)
        assertEquals("/authorized/project", created.handFolder)

        val updated = store.updateTask("t-hand", mapOf("title" to "updated"), 1, 1, 1)
        assertEquals("device-b", updated?.handDeviceId)
        assertEquals("grant-b-project", updated?.handAuthorizationId)
        assertEquals("/authorized/project", updated?.handFolder)
    }

    @Test
    fun `partial Hand route is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            store.createTask(record("t-partial").copy(handDeviceId = "device-b"))
        }
    }

    @Test
    fun `duplicate create throws TaskAlreadyExists`() {
        store.createTask(record("t1"))
        assertThrows(TaskAlreadyExists::class.java) { store.createTask(record("t1")) }
    }

    @Test
    fun `update with the right version succeeds and bumps the version`() {
        store.createTask(record("t1"))
        val updated = store.updateTask("t1", mapOf("state" to "assigned"), 1, 1, 1)
        assertEquals("assigned", updated?.state)
        assertEquals(2L, updated?.version)
    }

    @Test
    fun `update with a stale version throws StaleTaskUpdate`() {
        store.createTask(record("t1"))
        store.updateTask("t1", mapOf("state" to "assigned"), 1, 1, 1)
        assertThrows(StaleTaskUpdate::class.java) {
            store.updateTask("t1", mapOf("state" to "working"), 1, 1, 1)
        }
    }

    @Test
    fun `illegal state transition throws and writes nothing`() {
        store.createTask(record("t1"))
        assertThrows(TaskStates.InvalidStateTransition::class.java) {
            store.updateTask("t1", mapOf("state" to "working"), 1, 1, 1)
        }
        assertEquals("queued", store.getTask("t1")?.state)
        assertEquals(1L, store.getTask("t1")?.version)
    }

    @Test
    fun `scheduler-owned fields in changes are dropped`() {
        store.createTask(record("t1"))
        val updated = store.updateTask(
            "t1",
            mapOf("title" to "new", "version" to 99L, "task_id" to "evil"),
            1, 1, 1,
        )
        assertEquals("new", updated?.title)
        assertEquals(2L, updated?.version)
        assertEquals("t1", updated?.taskId)
    }

    @Test
    fun `switching brains only changes brain_id, state and version move normally`() {
        store.createTask(record("t1"))
        val updated = store.updateTask("t1", mapOf("brain_id" to "brain-2"), 1, 1, 1)
        assertEquals("brain-2", updated?.brainId)
        assertEquals("queued", updated?.state)
    }

    @Test
    fun `expired lease returns the task to requeued and flags attention`() {
        store.createTask(record("t1"))
        store.updateTask(
            "t1",
            mapOf(
                "state" to "assigned",
                "assigned_agent_id" to "agent-1",
                "lease_owner" to "agent-1",
                "lease_expires_at" to (1_700_000_000L + 90),
            ),
            1, 1, 1,
        )
        val expired = store.expireLeases(1_700_000_000L + 91)
        assertEquals(listOf("t1"), expired)
        val task = store.getTask("t1")
        assertEquals("requeued", task?.state)
        assertEquals(true, task?.needsAttention)
        assertEquals(null, task?.leaseOwner)
    }

    @Test
    fun `third expired lease sends the task to dead letter failed, not back to the queue`() {
        // attempt 3 = 已被认领 3 次（认领时 attempt+1）；第 3 次过期
        // 即死信：不再自动重试、不再回队。
        store.createTask(record("t1"))
        store.updateTask(
            "t1",
            mapOf(
                "state" to "assigned",
                "attempt" to 3,
                "assigned_agent_id" to "agent-1",
                "lease_owner" to "agent-1",
                "lease_expires_at" to (1_700_000_000L + 90),
            ),
            1, 1, 1,
        )
        val expired = store.expireLeases(1_700_000_000L + 91)
        assertEquals(listOf("t1"), expired)
        val task = store.getTask("t1")
        assertEquals("failed", task?.state)
        assertEquals(true, task?.needsAttention)
        assertEquals(null, task?.leaseOwner)
        assertEquals(null, task?.memberId)
        assertEquals(null, task?.attemptId)
        assertTrue(task?.error?.contains("死信") == true)
    }

    @Test
    fun `expired lease of a chat turn task is never swept back to the queue`() {
        // 聊天轮次任务（带 sessionId）执行中不续租；扫只认
        // sessionId IS NULL 的分派任务，轮次镜像绝不被抢回来。
        store.createTask(record("t1").copy(sessionId = "session-1"))
        store.updateTask(
            "t1",
            mapOf(
                "state" to "assigned",
                "assigned_agent_id" to "agent-1",
                "lease_owner" to "agent-1",
                "lease_expires_at" to (1_700_000_000L + 90),
            ),
            1, 1, 1,
        )
        assertTrue(store.expireLeases(1_700_000_000L + 91).isEmpty())
        val task = store.getTask("t1")
        assertEquals("assigned", task?.state)
        assertEquals("agent-1", task?.leaseOwner)
        assertEquals(false, task?.needsAttention)
    }

    @Test
    fun `unexpired lease is not reclaimed`() {
        store.createTask(record("t1"))
        store.updateTask(
            "t1",
            mapOf(
                "state" to "assigned",
                "lease_owner" to "agent-1",
                "lease_expires_at" to (1_700_000_000L + 90),
            ),
            1, 1, 1,
        )
        assertTrue(store.expireLeases(1_700_000_000L + 10).isEmpty())
        assertEquals("assigned", store.getTask("t1")?.state)
    }

    @Test
    fun `dependency on a missing task throws TaskDependencyError`() {
        assertThrows(TaskDependencyError::class.java) {
            store.createTask(record("t1").copy(dependencies = listOf("ghost")))
        }
    }

    @Test
    fun `dependency cycle throws TaskDependencyError`() {
        store.createTask(record("a"))
        store.createTask(record("b").copy(dependencies = listOf("a")))
        // a -> b -> a would be a cycle.
        assertThrows(TaskDependencyError::class.java) {
            store.updateTask("a", mapOf("dependencies" to listOf("b")), 1, 1, 1)
        }
    }

    // -- M2: old-version writes are rejected, nothing is written. --------

    @Test
    fun `stale goalVersion write throws ControlVersionStale and writes nothing`() {
        store.createTask(record("t1"))
        assertThrows(ControlVersionStale::class.java) {
            store.updateTask("t1", mapOf("title" to "evil"), 1, 99, 1)
        }
        val task = store.getTask("t1")
        assertEquals("task t1", task?.title)
        assertEquals(1L, task?.version)
        assertEquals(1L, task?.goalVersion)
    }

    @Test
    fun `stale authEpoch write throws ControlVersionStale and writes nothing`() {
        store.createTask(record("t1"))
        val thrown = assertThrows(ControlVersionStale::class.java) {
            store.updateTask("t1", mapOf("title" to "evil"), 1, 1, 99)
        }
        assertEquals(ControlVersionStale.AUTH_EPOCH, thrown.field)
        assertEquals("task t1", store.getTask("t1")?.title)
        assertEquals(1L, store.getTask("t1")?.version)
    }

    @Test
    fun `control fields in changes are dropped, never applied`() {
        store.createTask(record("t1"))
        val updated = store.updateTask(
            "t1",
            mapOf("title" to "new", "goal_version" to 99L, "auth_epoch" to 99L),
            1, 1, 1,
        )
        assertEquals("new", updated?.title)
        assertEquals(1L, updated?.goalVersion)
        assertEquals(1L, updated?.authEpoch)
    }

    @Test
    fun `claim snapshot equals current counters`() {
        // A task created after counters were bumped carries the bumped
        // snapshot; a write with the old snapshot is stale.
        store.createTask(record("t1").copy(goalVersion = 3, authEpoch = 2))
        val task = store.getTask("t1")
        assertEquals(3L, task?.goalVersion)
        assertEquals(2L, task?.authEpoch)
        val updated = store.updateTask("t1", mapOf("state" to "assigned"), 1, 3, 2)
        assertEquals(3L, updated?.goalVersion)
        assertEquals(2L, updated?.authEpoch)
        assertThrows(ControlVersionStale::class.java) {
            store.updateTask("t1", mapOf("state" to "working"), 2, 1, 1)
        }
    }

    @Test
    fun `expired lease of a PAUSED task only flags attention, never re-queues`() {
        store.createTask(record("t1"))
        store.updateTask(
            "t1",
            mapOf(
                "state" to "assigned",
                "assigned_agent_id" to "agent-1",
                "lease_owner" to "agent-1",
                "lease_expires_at" to (1_700_000_000L + 90),
            ),
            1, 1, 1,
        )
        db.controlDao().upsertState(ControlStateEntity("t1", status = "PAUSED"))
        val expired = store.expireLeases(1_700_000_000L + 91)
        assertTrue(expired.isEmpty())
        val task = store.getTask("t1")
        assertEquals("assigned", task?.state)
        assertEquals(true, task?.needsAttention)
        assertEquals("agent-1", task?.leaseOwner)
    }

    @Test
    fun `expired lease of a STOPPING task only flags attention, never re-queues`() {
        store.createTask(record("t1"))
        store.updateTask(
            "t1",
            mapOf(
                "state" to "assigned",
                "assigned_agent_id" to "agent-1",
                "lease_owner" to "agent-1",
                "lease_expires_at" to (1_700_000_000L + 90),
            ),
            1, 1, 1,
        )
        store.updateTask("t1", mapOf("state" to "working"), 2, 1, 1)
        db.controlDao().upsertState(ControlStateEntity("t1", status = "STOPPING"))
        assertTrue(store.expireLeases(1_700_000_000L + 91).isEmpty())
        assertEquals("working", store.getTask("t1")?.state)
        assertEquals(true, store.getTask("t1")?.needsAttention)
    }
}
