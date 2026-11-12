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

/**
 * Migration test 7 -> 8 (P0 耐久任务引擎，2026-11-11)，Robolectric。
 *
 * Same approach as TaskDatabaseMigration6To7Test (no
 * `androidx.room:room-testing` dependency): the v7 database is built
 * by hand with the framework helper, using the v7 schema — the v6
 * tables verbatim from TaskDatabaseMigration6To7Test plus the
 * `skills` table verbatim from MIGRATION_6_7. A task row, a skill
 * row, a conversation row, a pairing row and a member row (v7 shape)
 * are written, then the file is reopened through Room with
 * MIGRATION_1_2 + … + MIGRATION_8_9: Room runs the 7 -> 8 migration (then 8 -> 9, then 9 -> 10)
 * and validates the resulting schema against the v10 entities, so a
 * mismatch in the migration SQL fails here, in CI, before it can
 * reach a phone.
 *
 * What this proves: old rows survive, the new `task_events` table
 * exists (empty), and a task event written through
 * [RoomTaskEventStore] after the migration round-trips (the
 * migration's CREATE TABLE matches [TaskEventEntity] exactly,
 * including the index). What it does not prove: an upgrade on a
 * real device file (same SQL, same Room version).
 */
@RunWith(RobolectricTestRunner::class)
class TaskDatabaseMigration7To8Test {
    private val dbName = "migration-7-to-8-test.db"
    private var migrated: TaskDatabase? = null

    @After
    fun tearDown() {
        migrated?.close()
        RuntimeEnvironment.getApplication().deleteDatabase(dbName)
    }

    /** Build a version-7 database file exactly as a shipped v7 app left it. */
    private fun createV7Database() {
        val context = RuntimeEnvironment.getApplication()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(7) {
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
                            "authEpoch INTEGER NOT NULL, " +
                            // 5 -> 6: member-claim columns.
                            "memberId TEXT, " +
                            "attempt INTEGER NOT NULL DEFAULT 0, " +
                            "attemptId TEXT)",
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
                    // C2 batch 2 table, verbatim from MIGRATION_3_4.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS drawer_prefs (" +
                            "id TEXT NOT NULL PRIMARY KEY, " +
                            "orderJson TEXT NOT NULL, " +
                            "hiddenJson TEXT NOT NULL, " +
                            "favoritesJson TEXT NOT NULL, " +
                            "nestedJson TEXT NOT NULL, " +
                            "moreExpanded INTEGER NOT NULL)",
                    )
                    // Member tables, verbatim from MIGRATION_4_5, plus the
                    // 5 -> 6 columns (quotaState / driverAgentId). commit
                    // 是 SQLite 保留字，手写 SQL 必须加引号（见迁移本身）。
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS team_members (" +
                            "memberId TEXT NOT NULL PRIMARY KEY, " +
                            "agentId TEXT NOT NULL, " +
                            "displayName TEXT NOT NULL, " +
                            "role TEXT NOT NULL, " +
                            "kind TEXT NOT NULL, " +
                            "transport TEXT NOT NULL, " +
                            "endpointDeviceId TEXT, " +
                            "endpointLabel TEXT, " +
                            "endpointNote TEXT, " +
                            "baseUrl TEXT, " +
                            "authKind TEXT NOT NULL, " +
                            "authRef TEXT, " +
                            "quotaNote TEXT, " +
                            "shapeStatus TEXT NOT NULL, " +
                            "connectionState TEXT NOT NULL, " +
                            "connectivity TEXT NOT NULL, " +
                            "authorizedProjectIdsJson TEXT NOT NULL, " +
                            "lastStateUpdatedAtEpochSeconds INTEGER, " +
                            "createdAtEpochSeconds INTEGER NOT NULL, " +
                            "updatedAtEpochSeconds INTEGER NOT NULL, " +
                            // 5 -> 6: quota state + driver agent.
                            "quotaState TEXT NOT NULL DEFAULT 'UNKNOWN', " +
                            "driverAgentId TEXT)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS member_project_grants (" +
                            "memberId TEXT NOT NULL, " +
                            "projectId TEXT NOT NULL, " +
                            "grantedAtEpochSeconds INTEGER NOT NULL, " +
                            "grantedBy TEXT NOT NULL, " +
                            "PRIMARY KEY(memberId, projectId))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS member_returns (" +
                            "taskId TEXT NOT NULL, " +
                            "attemptId TEXT NOT NULL, " +
                            "memberId TEXT NOT NULL, " +
                            "branch TEXT, " +
                            "\"commit\" TEXT, " +
                            "prUrl TEXT, " +
                            "totalPages INTEGER, " +
                            "pagesReceived INTEGER, " +
                            "truncated INTEGER NOT NULL, " +
                            "filesJson TEXT NOT NULL, " +
                            "summary TEXT, " +
                            "logsJson TEXT NOT NULL, " +
                            "diffSummary TEXT, " +
                            "testVerdict TEXT, " +
                            "testSummary TEXT, " +
                            "recordedAtEpochSeconds INTEGER NOT NULL, " +
                            "PRIMARY KEY(taskId, attemptId))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS member_task_controls (" +
                            "taskId TEXT NOT NULL PRIMARY KEY, " +
                            "memberId TEXT NOT NULL, " +
                            "paused INTEGER NOT NULL, " +
                            "pendingGoal TEXT, " +
                            "updatedAtEpochSeconds INTEGER NOT NULL)",
                    )
                    // 6 -> 7: skills 表，逐字照抄 MIGRATION_6_7。
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS skills (" +
                            "id TEXT NOT NULL PRIMARY KEY, " +
                            "projectId TEXT NOT NULL, " +
                            "name TEXT NOT NULL, " +
                            "triggersJson TEXT NOT NULL DEFAULT '[]', " +
                            "summary TEXT NOT NULL DEFAULT '', " +
                            "stepsJson TEXT NOT NULL DEFAULT '[]', " +
                            "toolsJson TEXT NOT NULL DEFAULT '[]', " +
                            "pitfallsJson TEXT NOT NULL DEFAULT '[]', " +
                            "evidenceTaskIdsJson TEXT NOT NULL DEFAULT '[]', " +
                            "successCount INTEGER NOT NULL DEFAULT 0, " +
                            "failureCount INTEGER NOT NULL DEFAULT 0, " +
                            "lastUsedEpochSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "createdAtEpochSeconds INTEGER NOT NULL DEFAULT 0, " +
                            "updatedAtEpochSeconds INTEGER NOT NULL DEFAULT 0)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_skills_projectId ON skills (projectId)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_skills_updatedAtEpochSeconds " +
                            "ON skills (updatedAtEpochSeconds)",
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
                "createdAt, updatedAt, version, needsAttention, goalVersion, authEpoch, " +
                "memberId, attempt, attemptId) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "t1", "旧任务", "queued", "[]", 0,
                1_700_000_000L, 1_700_000_000L, 1L, 0, 1L, 1L,
                null, 0, null,
            ),
        )
        db.execSQL(
            "INSERT INTO skills (id, projectId, name, triggersJson, summary, " +
                "stepsJson, toolsJson, pitfallsJson, evidenceTaskIdsJson, " +
                "successCount, failureCount, lastUsedEpochSeconds, " +
                "createdAtEpochSeconds, updatedAtEpochSeconds) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "s1", "proj-9", "新建文件写内容", "[\"notes\",\"write_file\"]",
                "先 list_files 看现状，再 write_file 一次写完。",
                "[\"list_files 列出工作区\",\"write_file 写入文件\"]",
                "[\"list_files\",\"write_file\"]", "[\"不要先写文件再看目录\"]",
                "[\"t1\"]", 2, 1,
                1_700_000_300L, 1_700_000_300L, 1_700_000_300L,
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
        db.execSQL(
            "INSERT INTO team_members (memberId, agentId, displayName, role, kind, " +
                "transport, endpointDeviceId, endpointLabel, endpointNote, baseUrl, " +
                "authKind, authRef, quotaNote, shapeStatus, connectionState, " +
                "connectivity, authorizedProjectIdsJson, lastStateUpdatedAtEpochSeconds, " +
                "createdAtEpochSeconds, updatedAtEpochSeconds, quotaState, driverAgentId) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                "codex-mac", "codex-cli", "Mac 上的 Codex", "DEVELOPER", "DEVICE_CLI",
                "LAN", "mac-1", "Mac", null, null, "CLI_LOGIN", null, null,
                "VERIFIED", "NOT_CONNECTED", "NOT_CHECKED", "[]", null,
                1_700_000_200L, 1_700_000_200L, "UNKNOWN", null,
            ),
        )
        db.close()
        helper.close()
    }

    @Test
    fun `v7 rows survive the 7 to 8 migration and task events table is usable`() {
        createV7Database()
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
        // Opening the database runs MIGRATION_7_8 and validates the
        // resulting schema against the v10 entities; reaching this line
        // already proves the migration SQL matches the entities.
        assertEquals(10, db.openHelper.readableDatabase.version)

        // Old rows are untouched.
        assertEquals("旧任务", db.taskDao().getById("t1")!!.title)
        assertEquals("旧会话", db.conversationDao().getById("c1")!!.title)
        assertEquals("CONFIRMED", db.pairingDao().load("xiaomi12")!!.state)
        assertEquals("新建文件写内容", RoomSkillStore(db).get("s1")!!.name)

        // The new task_events table starts empty.
        val eventStore = RoomTaskEventStore(db)
        assertTrue(eventStore.listEvents("t1").isEmpty())
        assertTrue(eventStore.recentEvents(40).isEmpty())

        // An event written through the store after the migration
        // round-trips exactly (table + index match TaskEventEntity).
        val event = TaskEvent(
            eventId = "e1",
            taskId = "t1",
            attemptId = "att-1",
            kind = TaskEventKind.CREATED,
            fromState = null,
            toState = "queued",
            actor = "system",
            reason = null,
            createdAtEpochSeconds = 1_700_000_400L,
        )
        db.taskEventDao().insert(event.toEntity())
        assertEquals(listOf(event), eventStore.listEvents("t1"))
        assertEquals(listOf(event), eventStore.recentEvents(40))
    }
}
