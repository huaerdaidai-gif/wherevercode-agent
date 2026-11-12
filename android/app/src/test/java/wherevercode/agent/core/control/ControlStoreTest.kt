package wherevercode.agent.core.control

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskRecord

/**
 * ControlStore tests (M2, plan §2.1). Room/Robolectric — runs in CI,
 * not on a plain JVM. Also covers the restart-persistence points of
 * ControlCountersTest / CancellationTokenTest / ControlAuditLogTest
 * (counters, cancel_pending and audit rows survive a reopen).
 */
@RunWith(RobolectricTestRunner::class)
class ControlStoreTest {
    private lateinit var db: TaskDatabase
    private val dbName = "control-store-test.db"

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        RuntimeEnvironment.getApplication().deleteDatabase(dbName)
    }

    /**
     * Creates a version-1 database (tasks table only, old columns) the
     * way Room v1 did: plain SQLiteDatabase, no SQLiteOpenHelper
     * subclass (the helper's `use {}` form throws ClassCastException
     * under Robolectric, CI run 42433998905). `version = 1` is what
     * makes Room run MIGRATION_1_2 when it opens the file as v2.
     */
    private fun buildV1Database(context: Context, name: String) {
        context.deleteDatabase(name)
        val sqlite = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        sqlite.execSQL(
            "CREATE TABLE IF NOT EXISTS tasks (" +
                "taskId TEXT NOT NULL PRIMARY KEY, projectId TEXT, sessionId TEXT, " +
                "title TEXT NOT NULL DEFAULT '', description TEXT, " +
                "state TEXT NOT NULL DEFAULT 'queued', assignedAgentId TEXT, " +
                "parentTaskId TEXT, dependenciesJson TEXT NOT NULL DEFAULT '[]', " +
                "priority INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL DEFAULT 0, " +
                "startedAt INTEGER, finishedAt INTEGER, updatedAt INTEGER NOT NULL DEFAULT 0, " +
                "result TEXT, error TEXT, version INTEGER NOT NULL DEFAULT 1, " +
                "brainId TEXT, needsAttention INTEGER NOT NULL DEFAULT 0, " +
                "leaseOwner TEXT, leaseExpiresAt INTEGER)",
        )
        sqlite.execSQL(
            "INSERT INTO tasks (taskId, title, state, createdAt, updatedAt, version) " +
                "VALUES ('old-1', 'old task', 'queued', 1700000000, 1700000000, 1)",
        )
        sqlite.version = 1
        sqlite.close()
    }

    @Test
    fun `migration 1 to 2 keeps task rows, creates empty control tables, counters start at 1`() {
        val context = RuntimeEnvironment.getApplication()
        // Build a v1 database with one task row, then close it.
        buildV1Database(context, dbName)
        // Room opens it as v2: migration runs, the row survives.
        db = TaskDatabase.create(context, dbName, allowMainThreadQueries = true)
        val store = wherevercode.agent.core.task.RoomTaskStore(db)
        val task = store.getTask("old-1")
        assertEquals("old task", task?.title)
        assertEquals("queued", task?.state)
        assertEquals(1L, task?.goalVersion)
        assertEquals(1L, task?.authEpoch)
        // New tables exist and start empty; counters read as 1.
        val controlStore = RoomControlStore(db)
        assertEquals(1L, controlStore.goalVersion("old-1"))
        assertEquals(1L, controlStore.authEpoch("old-1"))
        assertNull(controlStore.getControlStatus("old-1"))
        assertTrue(RoomControlAuditLog(db.controlAuditDao()).listAll().isEmpty())
        // Restart persistence: bump, close, reopen, still there.
        assertEquals(2L, controlStore.bumpGoalVersion("old-1"))
        controlStore.setCancelPending("old-1", pending = true, requestedAt = 1_700_000_000L)
        db.close()
        db = TaskDatabase.create(context, dbName, allowMainThreadQueries = true)
        val reopened = RoomControlStore(db)
        assertEquals(2L, reopened.goalVersion("old-1"))
        assertTrue(reopened.isCancelPending("old-1"))
        assertEquals(1_700_000_000L, reopened.cancelRequestedAt("old-1"))
    }

    @Test
    fun `control, task, grant and revocation tables coexist in one database`() {
        val context = RuntimeEnvironment.getApplication()
        db = TaskDatabase.inMemory(context)
        val taskStore = wherevercode.agent.core.task.RoomTaskStore(db)
        taskStore.createTask(TaskRecord(taskId = "t1", title = "t1", state = "queued"))
        val controlStore = RoomControlStore(db)
        controlStore.setControlStatus("t1", ControlStatus.PAUSED)
        controlStore.bumpGoalVersion("t1")
        val audit = RoomControlAuditLog(db.controlAuditDao())
        audit.record(
            ControlAuditEntry(
                taskId = "t1", actor = "user", action = "PAUSE",
                goalVersion = 2, epoch = 1, operationId = "op-1",
                ok = true, ts = 1_700_000_000L,
            ),
        )
        val authStore = wherevercode.agent.core.security.RoomAuthorizationStore(db)
        authStore.grant("agent-1", "/proj")
        val revocation = wherevercode.agent.core.security.RoomRevocationStore(db)
        revocation.revokeTask("t1")
        // Every table reads back from the same database file.
        assertEquals("t1", taskStore.getTask("t1")?.taskId)
        assertEquals(ControlStatus.PAUSED, controlStore.getControlStatus("t1"))
        assertEquals(2L, controlStore.goalVersion("t1"))
        assertEquals(1, audit.listForTask("t1").size)
        assertTrue(authStore.isAuthorized("agent-1", "/proj"))
        assertTrue(revocation.isTaskRevoked("t1"))
    }

    @Test
    fun `fresh empty database starts empty and never crashes`() {
        val context = RuntimeEnvironment.getApplication()
        db = TaskDatabase.inMemory(context)
        val controlStore = RoomControlStore(db)
        assertEquals(1L, controlStore.goalVersion("never-seen"))
        assertEquals(1L, controlStore.authEpoch("never-seen"))
        assertNull(controlStore.getControlStatus("never-seen"))
        assertEquals(null, controlStore.getOperation("never-seen"))
        assertTrue(RoomControlAuditLog(db.controlAuditDao()).listAll().isEmpty())
        assertTrue(wherevercode.agent.core.security.RoomAuthorizationStore(db).listGrants().isEmpty())
        // The in-memory store starts empty the same way.
        val memory = InMemoryControlStore()
        assertEquals(1L, memory.goalVersion("never-seen"))
        assertNull(memory.getControlStatus("never-seen"))
    }
}
