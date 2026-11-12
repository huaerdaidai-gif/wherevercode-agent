package wherevercode.agent.core.task

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.brain.ConversationEntity
import wherevercode.agent.core.security.PairingEntity
import wherevercode.agent.ui.DrawerPrefsCodec
import wherevercode.agent.ui.RoomDrawerPrefsStore

/**
 * Migration test 3 -> 4 (V5 Phase B C2, batch 2), Robolectric.
 *
 * Same approach as C1's TaskDatabaseMigrationTest (no
 * `androidx.room:room-testing` dependency): the v3 database is built
 * by hand with the framework helper, using the v3 schema — the v2
 * tables verbatim from TaskDatabaseMigrationTest plus the four C1
 * tables verbatim from [TaskDatabase.MIGRATION_2_3]. A task row, a
 * conversation row and a pairing row are written, then the file is
 * reopened through Room with MIGRATION_1_2 + … +
 * MIGRATION_7_8: Room runs the 3 -> 10 migrations and validates the
 * resulting schema against the v10 entities, so a mismatch in the
 * migration SQL fails here, in CI, before it can reach a phone.
 *
 * What this proves: old rows survive, the new drawer_prefs table
 * exists and accepts writes, and the full chain lands the database
 * at version 8. What it
 * does not prove: an upgrade on a real device file (same SQL, same
 * Room version).
 */
@RunWith(RobolectricTestRunner::class)
class TaskDatabaseMigration3To4Test {
    private val dbName = "migration-3-to-4-test.db"
    private var migrated: TaskDatabase? = null

    @After
    fun tearDown() {
        migrated?.close()
        RuntimeEnvironment.getApplication().deleteDatabase(dbName)
    }

    /** Build a version-3 database file exactly as a shipped v3 app left it. */
    private fun createV3Database() {
        val context = RuntimeEnvironment.getApplication()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // v2 tables, verbatim from TaskDatabaseMigrationTest.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS tasks (" +
                            "taskId TEXT NOT NULL PRIMARY KEY, " +
                            "projectId TEXT, sessionId TEXT, " +
                            "title TEXT NOT NULL, description TEXT, " +
                            "state TEXT NOT NULL, assignedAgentId TEXT, " +
                            "parentTaskId TEXT, dependenciesJson TEXT NOT NULL, " +
                            "priority INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                            "startedAt INTEGER, finishedAt INTEGER, " +
                            "updatedAt INTEGER NOT NULL, result TEXT, error TEXT, " +
                            "version INTEGER NOT NULL, brainId TEXT, " +
                            "needsAttention INTEGER NOT NULL, leaseOwner TEXT, " +
                            "leaseExpiresAt INTEGER, goalVersion INTEGER NOT NULL, " +
                            "authEpoch INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS control_counters (" +
                            "taskId TEXT NOT NULL PRIMARY KEY, " +
                            "goalVersion INTEGER NOT NULL DEFAULT 1, " +
                            "authEpoch INTEGER NOT NULL DEFAULT 1)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS control_state (" +
                            "taskId TEXT NOT NULL PRIMARY KEY, " +
                            "status TEXT NOT NULL DEFAULT 'RUNNING', " +
                            "cancelPending INTEGER NOT NULL DEFAULT 0, " +
                            "stopRequestedAt INTEGER, " +
                            "dispatchFrozen INTEGER NOT NULL DEFAULT 0, " +
                            "updatedAt INTEGER NOT NULL DEFAULT 0)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS control_audit_log (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "taskId TEXT NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, " +
                            "goalVersion INTEGER NOT NULL, epoch INTEGER NOT NULL, " +
                            "operationId TEXT NOT NULL, ok INTEGER NOT NULL, " +
                            "errorCode TEXT, reason TEXT, ts INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS control_operation_ids (" +
                            "operationId TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, " +
                            "action TEXT NOT NULL, ok INTEGER NOT NULL, confirmed INTEGER NOT NULL, " +
                            "status TEXT NOT NULL, errorCode TEXT, reason TEXT, " +
                            "recordedAt INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS grants (" +
                            "agentId TEXT NOT NULL, folder TEXT NOT NULL, " +
                            "grantedAt TEXT NOT NULL, grantedBy TEXT NOT NULL DEFAULT 'local', " +
                            "PRIMARY KEY(agentId, folder))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS device_grant (" +
                            "id INTEGER NOT NULL PRIMARY KEY, " +
                            "grantedAt TEXT NOT NULL, grantedBy TEXT NOT NULL DEFAULT 'local')",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS revoked_credentials (" +
                            "credentialId TEXT NOT NULL PRIMARY KEY, revokedAt INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS revoked_tasks (" +
                            "taskId TEXT NOT NULL PRIMARY KEY, revokedAt INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS revoked_hosts (" +
                            "hostId TEXT NOT NULL PRIMARY KEY, revokedAt INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS sessions (" +
                            "sessionId TEXT NOT NULL PRIMARY KEY, " +
                            "name TEXT NOT NULL, kind TEXT NOT NULL, " +
                            "ownerId TEXT NOT NULL, membersJson TEXT NOT NULL, " +
                            "taskId TEXT, goalId TEXT, attemptId TEXT, " +
                            "createdAtEpochSeconds INTEGER NOT NULL, " +
                            "updatedAtEpochSeconds INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_ownerId ON sessions (ownerId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_taskId ON sessions (taskId)")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS session_messages (" +
                            "messageId TEXT NOT NULL PRIMARY KEY, " +
                            "conversationId TEXT NOT NULL, senderId TEXT NOT NULL, " +
                            "recipientId TEXT, taskId TEXT, attemptId TEXT, " +
                            "messageType TEXT NOT NULL, causationId TEXT, " +
                            "idempotencyKey TEXT NOT NULL, operationId TEXT, " +
                            "deliveryState TEXT NOT NULL, " +
                            "authVersion INTEGER NOT NULL, goalVersion INTEGER NOT NULL, " +
                            "ttlSeconds INTEGER, content TEXT NOT NULL, " +
                            "createdAtEpochSeconds INTEGER NOT NULL, " +
                            "auditActor TEXT, auditAction TEXT, " +
                            "auditEpochSeconds INTEGER, auditOk INTEGER, " +
                            "auditErrorCode TEXT)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_session_messages_conversationId " +
                            "ON session_messages (conversationId)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_session_messages_taskId " +
                            "ON session_messages (taskId)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS session_deliveries (" +
                            "messageId TEXT NOT NULL, memberId TEXT NOT NULL, " +
                            "deliveryState TEXT NOT NULL, " +
                            "updatedAtEpochSeconds INTEGER NOT NULL, " +
                            "PRIMARY KEY(messageId, memberId))",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_session_deliveries_memberId " +
                            "ON session_deliveries (memberId)",
                    )
                    // C1 tables, verbatim from MIGRATION_2_3.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS conversations (" +
                            "id TEXT NOT NULL PRIMARY KEY, " +
                            "brainId TEXT, " +
                            "title TEXT NOT NULL, " +
                            "createdAtEpochSeconds INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_conversations_createdAtEpochSeconds " +
                            "ON conversations (createdAtEpochSeconds)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS conversation_messages (" +
                            "id TEXT NOT NULL PRIMARY KEY, " +
                            "conversationId TEXT NOT NULL, " +
                            "role TEXT NOT NULL, " +
                            "content TEXT NOT NULL, " +
                            "source TEXT NOT NULL, " +
                            "createdAtEpochSeconds INTEGER NOT NULL, " +
                            "taskId TEXT, " +
                            "toolCallsJson TEXT NOT NULL, " +
                            "toolCallId TEXT)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_conversation_messages_conversationId " +
                            "ON conversation_messages (conversationId)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_conversation_messages_taskId " +
                            "ON conversation_messages (taskId)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS task_steps (" +
                            "stepId TEXT NOT NULL PRIMARY KEY, " +
                            "taskId TEXT NOT NULL, " +
                            "toolCallId TEXT NOT NULL, " +
                            "toolName TEXT NOT NULL, " +
                            "argsSummary TEXT NOT NULL, " +
                            "status TEXT NOT NULL, " +
                            "resultSummary TEXT, " +
                            "startedAtEpochSeconds INTEGER NOT NULL, " +
                            "finishedAtEpochSeconds INTEGER)",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_task_steps_taskId " +
                            "ON task_steps (taskId)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS pairings (" +
                            "deviceId TEXT NOT NULL PRIMARY KEY, " +
                            "displayName TEXT NOT NULL, " +
                            "fingerprintSha256 TEXT NOT NULL, " +
                            "peerPublicKey BLOB, " +
                            "state TEXT NOT NULL, " +
                            "reason TEXT, " +
                            "updatedAtEpochSeconds INTEGER NOT NULL)",
                    )
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO tasks (taskId, title, state, dependenciesJson, priority, " +
                "createdAt, updatedAt, version, needsAttention, goalVersion, authEpoch) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "t1", "旧任务", "queued", "[]", 0,
                1_700_000_000L, 1_700_000_000L, 1L, 0, 1L, 1L,
            ),
        )
        db.execSQL(
            "INSERT INTO conversations (id, brainId, title, createdAtEpochSeconds) " +
                "VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("c1", "deepseek", "旧会话", 1_700_000_100L),
        )
        db.execSQL(
            "INSERT INTO pairings (deviceId, displayName, fingerprintSha256, state, " +
                "updatedAtEpochSeconds) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("xiaomi12", "小米 12", "ab".repeat(32), "CONFIRMED", 1_700_000_104L),
        )
        db.close()
        helper.close()
    }

    @Test
    fun `v3 rows survive the 3 to 8 migration and drawer_prefs accepts writes`() {
        createV3Database()
        val context = RuntimeEnvironment.getApplication()
        val db = Room.databaseBuilder(context, TaskDatabase::class.java, dbName)
            .addMigrations(
                TaskDatabase.MIGRATION_1_2,
                TaskDatabase.MIGRATION_2_3,
                TaskDatabase.MIGRATION_3_4,
                TaskDatabase.MIGRATION_4_5,
                TaskDatabase.MIGRATION_5_6,
                TaskDatabase.MIGRATION_6_7,
                TaskDatabase.MIGRATION_7_8,
                TaskDatabase.MIGRATION_8_9,
                TaskDatabase.MIGRATION_9_10,
                TaskDatabase.MIGRATION_10_11,
            )
            .allowMainThreadQueries()
            .build()
        migrated = db
        // Opening the database runs MIGRATION_3_4 + MIGRATION_4_5 +
        // MIGRATION_5_6 + MIGRATION_6_7 + MIGRATION_7_8 + MIGRATION_8_9 + MIGRATION_9_10 and validates the
        // resulting schema against the v10 entities; reaching this line
        // already proves the migration SQL matches the entities.
        assertEquals(11, db.openHelper.readableDatabase.version)

        // Old rows are untouched.
        assertEquals("旧任务", db.taskDao().getById("t1")!!.title)
        assertEquals("旧会话", db.conversationDao().getById("c1")!!.title)
        assertEquals("CONFIRMED", db.pairingDao().load("xiaomi12")!!.state)

        // The new table exists, starts empty, and accepts writes through
        // the store (codec included).
        assertEquals(0, db.drawerPrefsDao().countAll())
        val store = RoomDrawerPrefsStore(db)
        val prefs = wherevercode.agent.ui.DrawerController.DrawerPrefs(
            hidden = setOf(wherevercode.agent.ui.DrawerController.ID_RESULTS),
            moreExpanded = true,
        )
        store.save(prefs)
        assertEquals(prefs, store.load())
        assertTrue(DrawerPrefsCodec.decode(db.drawerPrefsDao().get("default")).moreExpanded)
    }
}
