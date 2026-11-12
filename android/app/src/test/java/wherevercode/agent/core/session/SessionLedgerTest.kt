package wherevercode.agent.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.control.ControlCommandPort
import wherevercode.agent.core.control.ControlResult
import wherevercode.agent.core.control.ControlStatus
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.security.InMemoryAuthorizationStore
import wherevercode.agent.core.task.ControlVersionStale
import wherevercode.agent.core.task.StaleTaskUpdate
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore

// ---------------------------------------------------------------------------
// Shared in-memory fakes for the session-centre tests (same package, so
// ControlInstructionRouterTest / DeliveryTrackerTest reuse them directly;
// BrainEngineTest imports them). They mimic the Room ABORT behaviour:
// a duplicate primary key throws instead of silently overwriting.
// ---------------------------------------------------------------------------

class FakeSessionDao : SessionDao {
    val rows = LinkedHashMap<String, SessionEntity>()

    override fun insert(session: SessionEntity) {
        if (rows.containsKey(session.sessionId)) {
            throw IllegalStateException("duplicate session ${session.sessionId}")
        }
        rows[session.sessionId] = session
    }

    override fun getById(sessionId: String): SessionEntity? = rows[sessionId]

    override fun listByOwner(ownerId: String): List<SessionEntity> =
        rows.values.filter { it.ownerId == ownerId }
            .sortedByDescending { it.createdAtEpochSeconds }

    override fun countByOwner(ownerId: String): Int =
        rows.values.count { it.ownerId == ownerId }

    override fun listByTask(taskId: String): List<SessionEntity> =
        rows.values.filter { it.taskId == taskId }

    override fun bindTask(sessionId: String, taskId: String): Int {
        val current = rows[sessionId] ?: return 0
        if (current.taskId != null) return 0
        rows[sessionId] = current.copy(taskId = taskId)
        return 1
    }

    override fun deleteById(sessionId: String): Int =
        if (rows.remove(sessionId) != null) 1 else 0
}

class FakeSessionMessageDao : SessionMessageDao {
    val rows = LinkedHashMap<String, SessionMessageEntity>()

    override fun insert(message: SessionMessageEntity) {
        if (rows.containsKey(message.messageId)) {
            throw IllegalStateException("duplicate message ${message.messageId}")
        }
        rows[message.messageId] = message
    }

    override fun getById(messageId: String): SessionMessageEntity? = rows[messageId]

    override fun listByConversation(
        conversationId: String,
        limit: Int,
        offset: Int,
    ): List<SessionMessageEntity> =
        rows.values.filter { it.conversationId == conversationId }
            .sortedBy { it.createdAtEpochSeconds }
            .drop(offset).take(limit)

    override fun countByConversation(conversationId: String): Int =
        rows.values.count { it.conversationId == conversationId }

    override fun listByTask(taskId: String): List<SessionMessageEntity> =
        rows.values.filter { it.taskId == taskId }
            .sortedBy { it.createdAtEpochSeconds }

    override fun deleteByConversation(conversationId: String): Int {
        val doomed = rows.values.filter { it.conversationId == conversationId }
            .map { it.messageId }
        doomed.forEach { rows.remove(it) }
        return doomed.size
    }
}

class FakeSessionDeliveryDao : SessionDeliveryDao {
    val rows = LinkedHashMap<Pair<String, String>, SessionDeliveryEntity>()

    override fun insert(delivery: SessionDeliveryEntity) {
        val key = delivery.messageId to delivery.memberId
        if (rows.containsKey(key)) {
            throw IllegalStateException("duplicate delivery $key")
        }
        rows[key] = delivery
    }

    override fun get(messageId: String, memberId: String): SessionDeliveryEntity? =
        rows[messageId to memberId]

    override fun listByMessage(messageId: String): List<SessionDeliveryEntity> =
        rows.values.filter { it.messageId == messageId }

    override fun listUnread(memberId: String): List<SessionDeliveryEntity> =
        rows.values.filter { it.memberId == memberId && it.deliveryState != "READ" }

    override fun updateState(
        messageId: String,
        memberId: String,
        state: String,
        now: Long,
    ): Int {
        val key = messageId to memberId
        val current = rows[key] ?: return 0
        rows[key] = current.copy(deliveryState = state, updatedAtEpochSeconds = now)
        return 1
    }

    override fun deleteByMessages(messageIds: List<String>): Int {
        val doomed = rows.keys.filter { it.first in messageIds }
        doomed.forEach { rows.remove(it) }
        return doomed.size
    }
}

/** Test double for the Room TaskStore (5-arg updateTask, M2 contract). */
class SessionFakeTaskStore : TaskStore {
    val tasks = LinkedHashMap<String, TaskRecord>()
    var failUpdateWith: Exception? = null

    override fun createTask(record: TaskRecord): TaskRecord {
        tasks[record.taskId] = record
        return record
    }

    override fun listTasks(projectId: String?, sessionId: String?): List<TaskRecord> =
        tasks.values.toList()

    override fun getTask(taskId: String): TaskRecord? = tasks[taskId]

    override fun updateTask(
        taskId: String,
        changes: Map<String, Any?>,
        expectedVersion: Long,
        expectedGoalVersion: Long,
        expectedAuthEpoch: Long,
    ): TaskRecord? {
        failUpdateWith?.let { throw it }
        val current = tasks[taskId] ?: return null
        if (current.version != expectedVersion) {
            throw StaleTaskUpdate(taskId, expectedVersion, current.version)
        }
        if (current.goalVersion != expectedGoalVersion) {
            throw ControlVersionStale(
                taskId, ControlVersionStale.GOAL_VERSION,
                expectedGoalVersion, current.goalVersion,
            )
        }
        if (current.authEpoch != expectedAuthEpoch) {
            throw ControlVersionStale(
                taskId, ControlVersionStale.AUTH_EPOCH,
                expectedAuthEpoch, current.authEpoch,
            )
        }
        var next = current
        for ((key, value) in changes) {
            next = when (key) {
                "state" -> next.copy(state = value as String)
                "needs_attention" -> next.copy(needsAttention = value as Boolean)
                "result" -> next.copy(result = value as? String)
                else -> next
            }
        }
        next = next.copy(version = current.version + 1)
        tasks[taskId] = next
        return next
    }

    override fun expireLeases(nowEpochSeconds: Long): List<String> = emptyList()

    override fun deleteTask(taskId: String): Boolean = tasks.remove(taskId) != null
}

/** Test double for ControlCommandPort: records calls, never executes. */
class FakeControlCommandPort(
    var nextResult: ControlResult = ControlResult(
        ok = true, confirmed = true, status = ControlStatus.PAUSED,
    ),
) : ControlCommandPort {
    val calls = mutableListOf<String>()

    override fun pause(taskId: String, operationId: String, reason: String): ControlResult {
        calls += "pause:$taskId"
        return nextResult
    }

    override fun resume(taskId: String, operationId: String, reason: String): ControlResult {
        calls += "resume:$taskId"
        return nextResult
    }

    override fun stop(taskId: String, operationId: String, reason: String): ControlResult {
        calls += "stop:$taskId"
        return nextResult
    }

    override fun retarget(
        taskId: String,
        newGoal: String,
        operationId: String,
        reason: String,
    ): ControlResult {
        calls += "retarget:$taskId"
        return nextResult
    }

    override fun revoke(taskId: String, operationId: String, reason: String): ControlResult {
        calls += "revoke:$taskId"
        return nextResult
    }

    override fun takeover(taskId: String, operationId: String, reason: String): ControlResult {
        calls += "takeover:$taskId"
        return nextResult
    }

    override fun getControlStatus(taskId: String): ControlStatus = ControlStatus.RUNNING
}

/** One ledger + all its fakes, wired the way production wires them. */
class LedgerHarness(
    val delegatedControlEnabled: Boolean = false,
) {
    val sessionDao = FakeSessionDao()
    val messageDao = FakeSessionMessageDao()
    val deliveryDao = FakeSessionDeliveryDao()
    val taskStore = SessionFakeTaskStore()
    val authStore = InMemoryAuthorizationStore()
    val auditLog = InMemoryAuditLog()
    val port = FakeControlCommandPort()
    val router = ControlInstructionRouter(
        taskStore, messageDao, port, authStore, auditLog, delegatedControlEnabled,
    )
    val ledger = SessionLedgerImpl(
        sessionDao, messageDao, deliveryDao, router, authStore, auditLog,
    )

    fun addTask(taskId: String, state: String = "working"): TaskRecord {
        val record = TaskRecord(
            taskId = taskId, title = "task $taskId", state = state,
            version = 1, goalVersion = 1, authEpoch = 1,
        )
        taskStore.createTask(record)
        return record
    }

    fun privateSession(
        ownerId: String = "user",
        members: List<String> = listOf("user", "muse"),
    ): SessionRecord =
        ledger.createSession("私聊", SessionKind.PRIVATE, ownerId, members)

    fun groupSession(
        ownerId: String = "user",
        members: List<String> = listOf("user", "mother", "muse", "target-agent"),
    ): SessionRecord =
        ledger.createSession("群聊", SessionKind.GROUP, ownerId, members)
}

class SessionLedgerTest {

    @Test
    fun `chat session id matches the conversation id and remains stable across turns`() {
        val h = LedgerHarness()
        val conversationId = "11111111-1111-4111-8111-111111111111"

        val first = h.ledger.record(
            IncomingMessage(conversationId, "user", "第一条消息"),
        )
        val second = h.ledger.record(
            IncomingMessage(conversationId, "user", "第二条消息"),
        )

        assertTrue(first.ok)
        assertTrue(second.ok)
        assertEquals(conversationId, first.message!!.conversationId)
        assertEquals(conversationId, second.message!!.conversationId)
        assertEquals(conversationId, h.ledger.getSession(conversationId)!!.sessionId)
        assertEquals(1, h.sessionDao.rows.size)
        assertNotNull(h.ledger.readMessage("user", first.message!!.messageId))
    }

    @Test
    fun `GPT - private CONTROL lands in both the session row and the task row`() {
        val h = LedgerHarness()
        h.addTask("t1")
        val session = h.privateSession()
        val result = h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                content = "暂停 t1 原因：需要先停一下",
            ),
        )
        assertTrue(result.ok)
        assertEquals(MessageKind.CONTROL, result.kind)
        // Session row exists…
        val message = result.message
        assertNotNull(message)
        val stored = h.messageDao.getById(message!!.messageId)
        assertNotNull(stored)
        assertEquals("CONTROL", stored!!.messageType)
        // …and the task row exists with version +1 and intent recorded.
        val task = h.taskStore.getTask("t1")
        assertNotNull(task)
        assertEquals(2L, task!!.version)
        assertTrue(task.needsAttention)
        assertEquals("working", task.state) // intent only: state never moves here
        assertEquals(listOf("pause:t1"), h.port.calls)
    }

    @Test
    fun `no path writes TaskStore without going through S3`() {
        val h = LedgerHarness()
        h.addTask("t1")
        val session = h.privateSession()
        // A CONTROL message that was never recorded has no ledger receipt:
        // the router must refuse it and the task row must not move.
        val ghost = newSessionMessage(
            conversationId = session.sessionId,
            senderId = "user",
            content = "暂停 t1 原因：需要先停一下",
            messageType = MessageKind.CONTROL,
        )
        assertThrows(IllegalStateException::class.java) { h.router.route(ghost, session) }
        assertEquals(1L, h.taskStore.getTask("t1")!!.version)
        assertTrue(h.port.calls.isEmpty())
        // The same message recorded through the ledger routes fine.
        val recorded = h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                content = "暂停 t1 原因：需要先停一下",
                messageId = ghost.messageId,
            ),
        )
        assertTrue(recorded.ok)
        assertEquals(2L, h.taskStore.getTask("t1")!!.version)
    }

    @Test
    fun `ordinary chat never touches the task ledger`() {
        val h = LedgerHarness()
        h.addTask("t1")
        val session = h.privateSession()
        val result = h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                content = "今天天气不错",
            ),
        )
        assertTrue(result.ok)
        assertEquals(MessageKind.CHAT, result.kind)
        assertNotNull(result.message)
        assertEquals(1L, h.taskStore.getTask("t1")!!.version)
        assertFalse(h.taskStore.getTask("t1")!!.needsAttention)
        assertTrue(h.port.calls.isEmpty())
    }

    @Test
    fun `control syncs the relevant members - mother, participants and target agent`() {
        val h = LedgerHarness()
        h.addTask("t1")
        val session = h.groupSession()
        val result = h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                recipientId = "target-agent",
                content = "暂停 t1 原因：需要先停一下",
            ),
        )
        assertTrue(result.ok)
        val messageId = result.message!!.messageId
        val rows = h.deliveryDao.listByMessage(messageId)
        assertEquals(4, rows.size)
        val byMember = rows.associateBy { it.memberId }
        // Local user (sender) already holds it; the rest are pending pickup.
        assertEquals("DELIVERED", byMember["user"]!!.deliveryState)
        assertEquals("PERSISTED", byMember["mother"]!!.deliveryState)
        assertEquals("PERSISTED", byMember["muse"]!!.deliveryState)
        assertEquals("PERSISTED", byMember["target-agent"]!!.deliveryState)
    }

    @Test
    fun `a failed route never loses the message - row stays, delivery pending, audit ok=false`() {
        val h = LedgerHarness()
        h.addTask("t1")
        h.taskStore.failUpdateWith = ControlVersionStale(
            "t1", ControlVersionStale.GOAL_VERSION, 99, 1,
        )
        val session = h.privateSession()
        val result = h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                content = "暂停 t1 原因：需要先停一下",
            ),
        )
        assertFalse(result.ok)
        assertEquals(MessageKind.CONTROL, result.kind)
        // The message row is still there…
        val message = result.message
        assertNotNull(message)
        assertNotNull(h.messageDao.getById(message!!.messageId))
        // …delivery rows stay pending (PERSISTED, not faked)…
        val rows = h.deliveryDao.listByMessage(message.messageId)
        assertTrue(rows.any { it.memberId == "muse" && it.deliveryState == "PERSISTED" })
        // …and the task row never moved.
        assertEquals(1L, h.taskStore.getTask("t1")!!.version)
        // The audit line for the failure exists with ok=false.
        val audits = h.auditLog.readEntries()
        assertTrue(audits.any { it.action == "session_control" && !it.ok })
    }

    @Test
    fun `audit never leaks secrets`() {
        val h = LedgerHarness()
        val session = h.privateSession()
        val secret = "test-ledger-secret"
        val result = h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                content = "我的 key 是 $secret",
            ),
        )
        assertTrue(result.ok)
        val stored = h.messageDao.getById(result.message!!.messageId)!!
        assertFalse(stored.content.contains(secret))
        assertTrue(stored.content.contains("[REDACTED]"))
        val auditText = h.auditLog.readEntries().joinToString("\n") { it.toString() }
        assertFalse(auditText.contains(secret))
        assertFalse(auditText.contains("我的 key 是"))
    }

    @Test
    fun `a repeated submission does not write the ledger twice`() {
        val h = LedgerHarness()
        h.addTask("t1")
        val session = h.privateSession()
        val input = IncomingMessage(
            conversationId = session.sessionId,
            senderId = "user",
            content = "暂停 t1 原因：需要先停一下",
            messageId = "11111111-1111-4111-8111-111111111111",
        )
        val first = h.ledger.record(input)
        val second = h.ledger.record(input)
        assertTrue(first.ok)
        assertTrue(second.ok)
        assertEquals(first.message!!.messageId, second.message!!.messageId)
        assertEquals(1, h.messageDao.rows.size)
        // The task version bumped exactly once, the port ran once.
        assertEquals(2L, h.taskStore.getTask("t1")!!.version)
        assertEquals(listOf("pause:t1"), h.port.calls)
    }

    @Test
    fun `WACF fields round-trip and the mother sees CONTROL rows but never private CHAT bodies`() {
        val h = LedgerHarness()
        h.addTask("t1")
        val session = h.privateSession()
        val parent = h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                content = "先讨论一下 t1",
                taskId = "t1",
            ),
        ).message!!
        val child = h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                recipientId = "muse",
                content = "收到，继续",
                taskId = "t1",
                attemptId = "a1",
                causationId = parent.messageId,
                messageId = "22222222-2222-4222-8222-222222222222",
                idempotencyKey = "idem-1",
                operationId = "op-1",
                authVersion = 7,
                goalVersion = 3,
                ttlSeconds = 3600,
            ),
        ).message!!
        val back = h.ledger.getMessage(child.messageId)!!
        assertEquals("22222222-2222-4222-8222-222222222222", back.messageId)
        assertEquals("user", back.senderId)
        assertEquals("muse", back.recipientId)
        assertEquals("t1", back.taskId)
        assertEquals("a1", back.attemptId)
        assertEquals(MessageKind.CHAT, back.messageType)
        assertEquals(parent.messageId, back.causationId)
        assertEquals("idem-1", back.idempotencyKey)
        assertEquals("op-1", back.operationId)
        assertEquals(DeliveryState.PERSISTED, back.deliveryState)
        assertEquals(7L, back.authVersion)
        assertEquals(3L, back.goalVersion)
        assertEquals(3600L, back.ttlSeconds)
        assertEquals("收到，继续", back.content)
        // U11: the mother (not a member of this private session) may not
        // read the CHAT body…
        assertNull(h.ledger.readMessage("mother", child.messageId))
        assertNull(h.ledger.readMessage("stranger", child.messageId))
        // …but a CONTROL row is visible to her.
        val control = h.ledger.record(
            IncomingMessage(
                conversationId = session.sessionId,
                senderId = "user",
                content = "暂停 t1 原因：需要先停一下",
            ),
        ).message!!
        assertNotNull(h.ledger.readMessage("mother", control.messageId))
        assertEquals(
            "暂停 t1 原因：需要先停一下",
            h.ledger.readMessage("mother", control.messageId)!!.content,
        )
        // Members read everything in their own session.
        assertNotNull(h.ledger.readMessage("muse", child.messageId))
    }
}
