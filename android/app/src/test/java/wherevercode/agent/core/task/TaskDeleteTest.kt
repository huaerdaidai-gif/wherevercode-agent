package wherevercode.agent.core.task

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.control.ControlAuditEntity
import wherevercode.agent.core.control.ControlCounterEntity
import wherevercode.agent.core.control.ControlOperationEntity
import wherevercode.agent.core.control.ControlStateEntity
import wherevercode.agent.core.team.MemberReturnEntity
import wherevercode.agent.core.team.MemberTaskControlEntity

/**
 * [RoomTaskStore.deleteTask] tests (Robolectric — the DAO needs an
 * Android Context). Run in CI (`./gradlew testDebugUnitTest`).
 *
 * 覆盖：删干净（任务行 + 步骤 + 控制行 + 成员回传/控制 + 事件/
 * verdict 行）、删不存在的任务、删两次、聊天记录与会话不受影响、
 * 新表不存在时不炸、运行中任务也能删（先停是 UI 层的职责）。
 */
@RunWith(RobolectricTestRunner::class)
class TaskDeleteTest {
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

    private fun seedScopedRows(taskId: String) {
        RoomTaskStepStore(db).addStep(
            TaskStep(stepId = "s-$taskId", taskId = taskId, toolCallId = "c1", toolName = "read_file"),
        )
        db.controlDao().upsertState(ControlStateEntity(taskId = taskId, status = "RUNNING", updatedAt = 1))
        db.controlDao().upsertCounter(ControlCounterEntity(taskId = taskId, goalVersion = 2, authEpoch = 1))
        db.controlDao().insertOperation(
            ControlOperationEntity(
                operationId = "op-$taskId", taskId = taskId, action = "stop",
                ok = true, confirmed = true, status = "done", recordedAt = 1,
            ),
        )
        db.controlAuditDao().insert(
            ControlAuditEntity(
                taskId = taskId, actor = "user", action = "stop", goalVersion = 1,
                epoch = 1, operationId = "op-$taskId", ok = true, ts = 1,
            ),
        )
        db.memberReturnDao().insert(
            MemberReturnEntity(taskId = taskId, attemptId = "a1", memberId = "m1", recordedAtEpochSeconds = 1),
        )
        db.memberTaskControlDao().upsert(
            MemberTaskControlEntity(taskId = taskId, memberId = "m1", updatedAtEpochSeconds = 1),
        )
    }

    private fun countSql(sql: String, arg: String): Int {
        val cursor = db.openHelper.readableDatabase.query(sql, arrayOf<Any?>(arg))
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    @Test
    fun `delete removes task and all its scoped rows`() {
        store.createTask(record("t1"))
        seedScopedRows("t1")
        assertEquals(1, RoomTaskStepStore(db).listSteps("t1").size)
        assertEquals(1, db.controlAuditDao().listForTask("t1").size)

        assertTrue(store.deleteTask("t1"))

        assertNull(store.getTask("t1"))
        assertTrue(store.listTasks(null, null).isEmpty())
        assertTrue(RoomTaskStepStore(db).listSteps("t1").isEmpty())
        assertNull(db.controlDao().getState("t1"))
        assertNull(db.controlDao().getCounter("t1"))
        assertNull(db.controlDao().getOperation("op-t1"))
        assertTrue(db.controlAuditDao().listForTask("t1").isEmpty())
        assertTrue(db.memberReturnDao().listByTask("t1").isEmpty())
        assertNull(db.memberTaskControlDao().getByTask("t1"))
    }

    @Test
    fun `delete missing task returns false`() {
        assertFalse(store.deleteTask("nope"))
    }

    @Test
    fun `delete twice second returns false`() {
        store.createTask(record("t1"))
        assertTrue(store.deleteTask("t1"))
        assertFalse(store.deleteTask("t1"))
    }

    @Test
    fun `delete keeps chat messages and sessions`() {
        store.createTask(record("t1"))
        val writable = db.openHelper.writableDatabase
        writable.execSQL(
            "INSERT INTO conversations (id, brainId, title, createdAtEpochSeconds) " +
                "VALUES ('conv1', NULL, 't', 1)",
        )
        writable.execSQL(
            "INSERT INTO conversation_messages (id, conversationId, role, content, source, " +
                "createdAtEpochSeconds, taskId, toolCallsJson, toolCallId) " +
                "VALUES ('m1', 'conv1', 'user', 'hi', 'chat', 1, 't1', '[]', NULL)",
        )
        writable.execSQL(
            "INSERT INTO sessions (sessionId, name, kind, ownerId, membersJson, taskId, " +
                "goalId, attemptId, createdAtEpochSeconds, updatedAtEpochSeconds) " +
                "VALUES ('s1', 'chat:conv1', 'chat', 'user', '[]', 't1', NULL, NULL, 1, 1)",
        )

        assertTrue(store.deleteTask("t1"))

        assertEquals(
            1,
            countSql("SELECT COUNT(*) FROM conversation_messages WHERE taskId = ?", "t1"),
        )
        assertEquals(1, countSql("SELECT COUNT(*) FROM sessions WHERE taskId = ?", "t1"))
    }

    @Test
    fun `delete removes event and verdict rows when those tables exist`() {
        store.createTask(record("t1"))
        store.createTask(record("t2"))
        // task_events / pipeline_verdicts 在合包树里已是 Room 表（P0/P2），
        // 不用再 CREATE TABLE；按 Room 实体的 NOT NULL 列直接插行。
        // 注意：Kotlin 默认值（= 0 / = "[]"）不会变成 SQL DEFAULT，
        // Room 建表是纯 NOT NULL 无默认值，漏列必报 NOT NULL 约束错。
        val writable = db.openHelper.writableDatabase
        writable.execSQL("INSERT INTO task_events (eventId, taskId, kind, toState, createdAtEpochSeconds) VALUES ('e-t1', 't1', 'claimed', 'failed', 1700000000)")
        writable.execSQL("INSERT INTO task_events (eventId, taskId, kind, toState, createdAtEpochSeconds) VALUES ('e-t2', 't2', 'claimed', 'failed', 1700000000)")
        writable.execSQL("INSERT INTO pipeline_verdicts (verdictId, taskId, round, verdict, reviewer, checklistJson, createdAtEpochSeconds) VALUES ('v-t1', 't1', 1, 'approve', 'reviewer', '[]', 1700000000)")
        writable.execSQL("INSERT INTO pipeline_verdicts (verdictId, taskId, round, verdict, reviewer, checklistJson, createdAtEpochSeconds) VALUES ('v-t2', 't2', 1, 'approve', 'reviewer', '[]', 1700000000)")

        assertTrue(store.deleteTask("t1"))

        assertEquals(0, countSql("SELECT COUNT(*) FROM task_events WHERE taskId = ?", "t1"))
        // t2 留 2 行：createTask 写 1 条 CREATED 事件 + 本测试插的 1 条 claimed。
        assertEquals(2, countSql("SELECT COUNT(*) FROM task_events WHERE taskId = ?", "t2"))
        assertEquals(0, countSql("SELECT COUNT(*) FROM pipeline_verdicts WHERE taskId = ?", "t1"))
        assertEquals(1, countSql("SELECT COUNT(*) FROM pipeline_verdicts WHERE taskId = ?", "t2"))
    }

    @Test
    fun `delete running task removes the row`() {
        store.createTask(record("t1"))
        store.updateTask("t1", mapOf("state" to "assigned"), 1, 1, 1)
        store.updateTask("t1", mapOf("state" to "working"), 2, 1, 1)
        assertEquals("working", store.getTask("t1")?.state)
        assertTrue(store.deleteTask("t1"))
        assertNull(store.getTask("t1"))
    }

    @Test
    fun `delete blank id returns false`() {
        assertFalse(store.deleteTask("   "))
    }
}
