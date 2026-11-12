package wherevercode.agent.core.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.control.ControlAuditEntity
import wherevercode.agent.core.control.ControlCounterEntity
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskEntity

/**
 * Room tests (Robolectric — the DAOs need an Android Context).
 * Run in CI (`./gradlew testDebugUnitTest`); they cannot run on a plain
 * JVM without Robolectric, so locally they only need to compile.
 *
 * The tests run on the merged TaskDatabase (in-memory): the session
 * tables live in the same database as the tasks (U5), so there is no
 * test-local @Database class — an earlier revision had one and Room
 * failed to find its generated implementation at build time
 * (CI run 42433998905: RuntimeException in setUp). The same-database
 * transaction test below covers control / task / message / audit in
 * one transaction on that merged database.
 */
@RunWith(RobolectricTestRunner::class)
class SessionDatabaseTest {
    private lateinit var db: TaskDatabase
    private lateinit var sessions: SessionDao
    private lateinit var messages: SessionMessageDao
    private lateinit var deliveries: SessionDeliveryDao

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        sessions = db.sessionDao()
        messages = db.sessionMessageDao()
        deliveries = db.sessionDeliveryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sessionEntity(
        id: String = "sess-1",
        ownerId: String = "user",
        taskId: String? = null,
    ) = SessionEntity(
        sessionId = id,
        name = "私聊",
        kind = "PRIVATE",
        ownerId = ownerId,
        membersJson = "[\"user\",\"muse\"]",
        taskId = taskId,
        goalId = "goal-1",
        attemptId = "attempt-1",
        createdAtEpochSeconds = 1_700_000_000L,
        updatedAtEpochSeconds = 1_700_000_000L,
    )

    private fun messageEntity(
        id: String = "33333333-3333-4333-8333-333333333333",
        conversationId: String = "sess-1",
        createdAt: Long = 1_700_000_000L,
    ) = SessionMessageEntity(
        messageId = id,
        conversationId = conversationId,
        senderId = "user",
        recipientId = "muse",
        taskId = "t1",
        attemptId = "a1",
        messageType = "CONTROL",
        causationId = "44444444-4444-4444-8444-444444444444",
        idempotencyKey = "idem-1",
        operationId = "op-1",
        deliveryState = "PERSISTED",
        authVersion = 7,
        goalVersion = 3,
        ttlSeconds = 3600,
        content = "暂停 t1 原因：需要先停一下",
        createdAtEpochSeconds = createdAt,
        auditActor = "user",
        auditAction = "control_recorded",
        auditEpochSeconds = createdAt,
        auditOk = true,
    )

    @Test
    fun `message persists and reads back with all WACF fields`() {
        sessions.insert(sessionEntity())
        messages.insert(messageEntity())
        val back = messages.getById("33333333-3333-4333-8333-333333333333")!!
        assertEquals("sess-1", back.conversationId)
        assertEquals("user", back.senderId)
        assertEquals("muse", back.recipientId)
        assertEquals("t1", back.taskId)
        assertEquals("a1", back.attemptId)
        assertEquals("CONTROL", back.messageType)
        assertEquals("44444444-4444-4444-8444-444444444444", back.causationId)
        assertEquals("idem-1", back.idempotencyKey)
        assertEquals("op-1", back.operationId)
        assertEquals("PERSISTED", back.deliveryState)
        assertEquals(7L, back.authVersion)
        assertEquals(3L, back.goalVersion)
        assertEquals(3600L, back.ttlSeconds)
        assertEquals("暂停 t1 原因：需要先停一下", back.content)
        assertEquals("user", back.auditActor)
        assertEquals("control_recorded", back.auditAction)
    }

    @Test
    fun `session-message association and pagination boundaries`() {
        sessions.insert(sessionEntity())
        repeat(5) { i ->
            messages.insert(
                messageEntity(
                    id = "55555555-5555-4555-8555-55555555550$i",
                    createdAt = 1_700_000_000L + i,
                ).copy(content = "msg $i"),
            )
        }
        assertEquals(5, messages.countByConversation("sess-1"))
        val firstTwo = messages.listByConversation("sess-1", limit = 2, offset = 0)
        assertEquals(listOf("msg 0", "msg 1"), firstTwo.map { it.content })
        val lastOne = messages.listByConversation("sess-1", limit = 2, offset = 4)
        assertEquals(listOf("msg 4"), lastOne.map { it.content })
        assertTrue(messages.listByConversation("sess-1", limit = 2, offset = 5).isEmpty())
        assertEquals(5, messages.listByTask("t1").size)
    }

    @Test
    fun `task binding - stored first with null taskId, bound later, text unchanged, goal and attempt traceable`() {
        sessions.insert(sessionEntity(taskId = null))
        messages.insert(messageEntity())
        val bound = bindSessionTask(sessions, "sess-1", "t1")
        assertEquals("t1", bound.taskId)
        assertEquals("goal-1", bound.goalId)
        assertEquals("attempt-1", bound.attemptId)
        assertEquals("t1", sessions.getById("sess-1")!!.taskId)
        // Binding never touches the messages.
        assertEquals(
            "暂停 t1 原因：需要先停一下",
            messages.getById("33333333-3333-4333-8333-333333333333")!!.content,
        )
    }

    @Test
    fun `task discussion is one session to one taskId - rebinding is refused`() {
        sessions.insert(sessionEntity(taskId = null))
        assertEquals("t1", bindSessionTask(sessions, "sess-1", "t1").taskId)
        assertThrows(IllegalStateException::class.java) {
            bindSessionTask(sessions, "sess-1", "t2")
        }
        assertEquals("t1", sessions.getById("sess-1")!!.taskId)
        // Binding the same task again is an idempotent no-op.
        assertEquals("t1", bindSessionTask(sessions, "sess-1", "t1").taskId)
        assertEquals(1, sessions.listByTask("t1").size)
    }

    @Test
    fun `the same messageId written twice leaves exactly one row`() {
        sessions.insert(sessionEntity())
        messages.insert(messageEntity())
        assertThrows(Exception::class.java) { messages.insert(messageEntity()) }
        assertEquals(1, messages.countByConversation("sess-1"))
    }

    @Test
    fun `history stays readable after the task is cancelled - cancel never deletes`() {
        sessions.insert(sessionEntity(taskId = "t1"))
        messages.insert(messageEntity())
        // Cancelling a task is a task-ledger concern; it never deletes
        // session rows. Deletion is an explicit user action (V5 C3
        // chat-bugfix: SessionLedger.deleteSession), never a side effect.
        assertEquals(1, messages.listByTask("t1").size)
        assertEquals(
            "暂停 t1 原因：需要先停一下",
            messages.listByConversation("sess-1", limit = 10, offset = 0).single().content,
        )
        assertEquals("t1", sessions.getById("sess-1")!!.taskId)
    }

    @Test
    fun `session delete removes deliveries then messages then the session row`() {
        sessions.insert(sessionEntity())
        messages.insert(messageEntity())
        deliveries.insert(
            SessionDeliveryEntity(
                messageId = "33333333-3333-4333-8333-333333333333",
                memberId = "muse",
                deliveryState = "PERSISTED",
                updatedAtEpochSeconds = 1_700_000_000L,
            ),
        )
        assertEquals(
            1,
            deliveries.deleteByMessages(
                listOf("33333333-3333-4333-8333-333333333333"),
            ),
        )
        assertEquals(1, messages.deleteByConversation("sess-1"))
        assertEquals(1, sessions.deleteById("sess-1"))
        assertNull(sessions.getById("sess-1"))
        assertEquals(0, messages.countByConversation("sess-1"))
        assertTrue(
            deliveries.listByMessage("33333333-3333-4333-8333-333333333333").isEmpty(),
        )
        // Deleting an unknown session removes nothing.
        assertEquals(0, sessions.deleteById("no-such-session"))
        assertEquals(0, messages.deleteByConversation("no-such-session"))
    }

    @Test
    fun `the 101st session for one user is refused`() {
        repeat(100) { i ->
            sessions.insert(sessionEntity(id = "sess-$i"))
        }
        assertEquals(100, sessions.countByOwner("user"))
        assertThrows(IllegalArgumentException::class.java) { requireSessionSlot(100) }
        requireSessionSlot(99)
    }

    @Test
    fun `delivery rows round-trip, one row per message and member, metadata only`() {
        val row = SessionDeliveryEntity(
            messageId = "33333333-3333-4333-8333-333333333333",
            memberId = "muse",
            deliveryState = "PERSISTED",
            updatedAtEpochSeconds = 1_700_000_000L,
        )
        deliveries.insert(row)
        assertEquals("PERSISTED", deliveries.get(row.messageId, "muse")!!.deliveryState)
        assertThrows(Exception::class.java) { deliveries.insert(row) }
        assertEquals(1, deliveries.listByMessage(row.messageId).size)
        assertEquals(1, deliveries.updateState(row.messageId, "muse", "CLAIMED", 1_700_000_001L))
        assertEquals("CLAIMED", deliveries.get(row.messageId, "muse")!!.deliveryState)
        // Unread = not READ; a READ row drops out of the unread set.
        val other = row.copy(memberId = "mother")
        deliveries.insert(other)
        assertEquals(2, deliveries.listUnread("muse").size + deliveries.listUnread("mother").size)
        deliveries.updateState(row.messageId, "mother", "READ", 1_700_000_002L)
        assertEquals(0, deliveries.listUnread("mother").size)
        assertEquals(1, deliveries.listUnread("muse").size)
    }

    @Test
    fun `control task message and audit commit and roll back together in one database (U5)`() {
        // Plan §2.2 item 8: control / task / message / audit in one
        // transaction on the merged TaskDatabase (set up above).
        val merged = db
        // Commit path: one transaction writes a task row, a control
        // counter, a session message and an audit row; all four
        // read back afterwards.
        merged.runInTransaction {
            merged.taskDao().insert(TaskEntity(taskId = "t1", title = "u5"))
            merged.controlDao().upsertCounter(ControlCounterEntity("t1"))
            merged.sessionMessageDao().insert(messageEntity())
            merged.controlAuditDao().insert(
                ControlAuditEntity(
                    taskId = "t1", actor = "user", action = "PAUSE",
                    goalVersion = 1, epoch = 1, operationId = "op-1",
                    ok = true, ts = 1_700_000_000L,
                ),
            )
        }
        assertEquals("u5", merged.taskDao().getById("t1")!!.title)
        assertEquals(1L, merged.controlDao().getCounter("t1")!!.goalVersion)
        assertEquals(1, merged.sessionMessageDao().countByConversation("sess-1"))
        assertEquals(1, merged.controlAuditDao().listForTask("t1").size)

        // Rollback path: the same four writes inside a transaction
        // that throws must leave nothing behind.
        assertThrows(RuntimeException::class.java) {
            merged.runInTransaction {
                merged.taskDao().insert(TaskEntity(taskId = "t2", title = "u5-rollback"))
                merged.controlDao().upsertCounter(ControlCounterEntity("t2"))
                merged.sessionMessageDao().insert(
                    messageEntity(id = "66666666-6666-4666-8666-666666666666"),
                )
                merged.controlAuditDao().insert(
                    ControlAuditEntity(
                        taskId = "t2", actor = "user", action = "PAUSE",
                        goalVersion = 1, epoch = 1, operationId = "op-2",
                        ok = true, ts = 1_700_000_000L,
                    ),
                )
                throw RuntimeException("boom")
            }
        }
        assertNull(merged.taskDao().getById("t2"))
        assertNull(merged.controlDao().getCounter("t2"))
        assertNull(
            merged.sessionMessageDao().getById("66666666-6666-4666-8666-666666666666"),
        )
        assertTrue(merged.controlAuditDao().listForTask("t2").isEmpty())
    }
}
