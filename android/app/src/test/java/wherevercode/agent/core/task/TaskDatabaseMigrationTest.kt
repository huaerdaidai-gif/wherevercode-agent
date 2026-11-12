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
import wherevercode.agent.brain.ConversationMessageEntity
import wherevercode.agent.core.security.PairingEntity

/**
 * Migration test 2 -> 3 (V5 Phase B C1, W2), Robolectric.
 *
 * No `androidx.room:room-testing` dependency is declared (build.gradle.kts
 * is out of scope for this task), so there is no MigrationTestHelper
 * here: the v2 database is built by hand with the framework helper,
 * using the v2 schema — the tasks table as Room generates it from
 * [TaskEntity], and the control / grant / revocation / session tables
 * copied verbatim from [TaskDatabase.MIGRATION_1_2]. A task row, a
 * session row and a control-counter row are written, then the file is
 * reopened through Room with MIGRATION_1_2 + MIGRATION_2_3 +
 * MIGRATION_3_4 (C2 batch 2 bumped the database to v4, so the 2 -> 4
 * chain is what a real upgrade runs now): Room runs the 2 -> 3 and
 * 3 -> 4 migrations and validates the resulting schema against the
 * v7 entities, so a mismatch in the migration SQL fails here, in CI,
 * before it can reach a phone.
 *
 * What this proves: old rows survive, the new tables exist and accept
 * writes, and the database version is 4. What it does not prove: an
 * upgrade on a real device file (same SQL, same Room version — the
 * manual step for that is listed in the W2 report).
 */
@RunWith(RobolectricTestRunner::class)
class TaskDatabaseMigrationTest {
    private val dbName = "migration-2-to-3-test.db"
    private var migrated: TaskDatabase? = null

    @After
    fun tearDown() {
        migrated?.close()
        RuntimeEnvironment.getApplication().deleteDatabase(dbName)
    }

    /** Build a version-2 database file exactly as a shipped v2 app left it. */
    private fun createV2Database() {
        val context = RuntimeEnvironment.getApplication()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
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
                    // Control / grant / revocation tables, verbatim from
                    // MIGRATION_1_2 (including their DEFAULT clauses).
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
                    // Session tables (S2), verbatim from MIGRATION_1_2.
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
            "INSERT INTO sessions (sessionId, name, kind, ownerId, membersJson, " +
                "createdAtEpochSeconds, updatedAtEpochSeconds) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "sess-1", "私聊", "PRIVATE", "user", "[\"user\",\"muse\"]",
                1_700_000_000L, 1_700_000_000L,
            ),
        )
        db.execSQL(
            "INSERT INTO control_counters (taskId, goalVersion, authEpoch) VALUES (?, ?, ?)",
            arrayOf<Any?>("t1", 3L, 2L),
        )
        db.close()
        helper.close()
    }

    @Test
    fun `v2 rows survive the 2 to 9 migration and the new tables accept writes`() {
        createV2Database()
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
            )
            .allowMainThreadQueries()
            .build()
        migrated = db
        // Opening the database runs MIGRATION_2_3 + MIGRATION_3_4 +
        // MIGRATION_4_5 + MIGRATION_5_6 + MIGRATION_6_7 + MIGRATION_7_8 + MIGRATION_8_9 + MIGRATION_9_10
        // and validates the resulting schema against the v10 entities;
        // reaching this line already proves the migration SQL matches
        // the entities.
        assertEquals(10, db.openHelper.readableDatabase.version)

        // Old rows are untouched.
        assertEquals("旧任务", db.taskDao().getById("t1")!!.title)
        assertEquals("queued", db.taskDao().getById("t1")!!.state)
        assertEquals("私聊", db.sessionDao().getById("sess-1")!!.name)
        assertEquals(3L, db.controlDao().getCounter("t1")!!.goalVersion)
        assertEquals(2L, db.controlDao().getCounter("t1")!!.authEpoch)

        // The new tables exist, start empty, and accept writes.
        assertEquals(0, db.conversationDao().countAll())
        db.conversationDao().insert(
            ConversationEntity("c1", "deepseek", "迁移后会话", 1_700_000_100L),
        )
        db.conversationMessageDao().insert(
            ConversationMessageEntity(
                id = "m1", conversationId = "c1", role = "user",
                content = "迁移后还能写", source = "user",
                createdAtEpochSeconds = 1_700_000_101L,
            ),
        )
        db.taskStepDao().insert(
            TaskStepEntity(
                stepId = "s1", taskId = "t1", toolCallId = "call-1",
                toolName = "write_file", status = "success",
                resultSummary = "ok", startedAtEpochSeconds = 1_700_000_102L,
                finishedAtEpochSeconds = 1_700_000_103L,
            ),
        )
        db.pairingDao().upsert(
            PairingEntity(
                deviceId = "xiaomi12", displayName = "小米 12",
                fingerprintSha256 = "ab".repeat(32),
                peerPublicKey = byteArrayOf(1, 2, 3),
                state = "CONFIRMED", updatedAtEpochSeconds = 1_700_000_104L,
            ),
        )
        assertEquals("迁移后会话", db.conversationDao().getById("c1")!!.title)
        assertEquals(
            "迁移后还能写",
            db.conversationMessageDao().listByConversation("c1").single().content,
        )
        assertEquals("write_file", db.taskStepDao().listByTask("t1").single().toolName)
        assertEquals("CONFIRMED", db.pairingDao().load("xiaomi12")!!.state)
        assertTrue(db.conversationDao().countAll() == 1)
    }
}
