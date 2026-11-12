package wherevercode.agent.core.session

import wherevercode.agent.core.security.AuditEntry
import wherevercode.agent.core.security.AuditLog
import wherevercode.agent.core.security.AuthorizationStore
import wherevercode.agent.core.security.utcNowIso
import wherevercode.agent.core.task.ControlVersionStale
import wherevercode.agent.core.task.StaleTaskUpdate

/**
 * SessionLedger (M2 S3): the ONLY write entry for session messages.
 *
 * Order of work for every message: classify -> validate -> persist ->
 * route. Nothing writes a session message row except through here, and
 * nothing writes the task ledger for a control instruction except
 * through the router this ledger calls (S4 refuses unrecorded
 * messages, so there is no path that writes TaskStore without S3).
 *
 * - Ordinary chat never touches the task ledger (the task version
 *   does not move).
 * - A control instruction is persisted first, then routed via S4
 *   (intent in the task ledger + handover to ControlPlane). A failed
 *   route still leaves the message row and its PENDING delivery rows
 *   in place — failures never lose the message.
 * - A message is stored exactly once: the messageId primary key plus
 *   a pre-insert lookup make a repeated submission return the
 *   existing row without a second task write.
 * - The mother (supervisor) sees CONTROL ledger rows and task-related
 *   metadata, never private CHAT bodies (U11) — see [readMessage].
 * - Formal control / execution actions go into the shared ledger;
 *   secret-shaped content is redacted with the existing SecretRedactor
 *   before storage, never refused and never stored verbatim.
 */

/** One incoming message, before classification. */
data class IncomingMessage(
    val conversationId: String,
    val senderId: String,
    val content: String,
    val recipientId: String? = null,
    val taskId: String? = null,
    val attemptId: String? = null,
    /** Advisory only: S3 classification is authoritative. */
    val messageTypeHint: MessageKind? = null,
    val causationId: String? = null,
    /** Caller-supplied id (must be a UUID); generated when null. */
    val messageId: String? = null,
    val idempotencyKey: String? = null,
    val operationId: String? = null,
    val ttlSeconds: Long? = null,
    val authVersion: Long = 1,
    val goalVersion: Long = 1,
)

/** What record() did. [message] is null only when nothing was stored
 * (a validation refusal). */
data class LedgerResult(
    /** Classified kind (CONTROL even when routing failed). */
    val kind: MessageKind,
    val message: SessionMessage?,
    val ok: Boolean,
    val errorCode: String? = null,
    val reason: String? = null,
    /** User-visible one-line summary (metadata only, no body). */
    val summary: String,
    /** Router answer for CONTROL messages (null otherwise). */
    val control: RouteResult? = null,
)

/** The session ledger port. BrainEngine depends only on this interface
 * (constructor injection); it never touches session storage directly. */
interface SessionLedger {
    fun record(input: IncomingMessage): LedgerResult
    fun createSession(
        name: String,
        kind: SessionKind,
        ownerId: String,
        members: List<String>,
        taskId: String? = null,
        goalId: String? = null,
        attemptId: String? = null,
    ): SessionRecord

    fun getSession(sessionId: String): SessionRecord?
    fun getMessage(messageId: String): SessionMessage?
    fun listMessages(conversationId: String, limit: Int = 100, offset: Int = 0): List<SessionMessage>

    /**
     * 阶段 3（U5）：列出某用户名下的全部会话（按创建时间倒序），供
     * 会话中心展示。自动物化的 `chat:<id>` 会话也在其中，调用方须
     * 自行分组（具名会话在前，历史对话在后）。
     */
    fun listSessions(ownerId: String): List<SessionRecord>

    /**
     * U11 read rule: session members read everything in their session;
     * the supervisor ([motherAgentId]) may read CONTROL / TASK / EVENT /
     * DELIVERY rows but never a private CHAT body. Returns null when the
     * viewer may not see the row at all.
     */
    fun readMessage(viewerId: String, messageId: String): SessionMessage?

    /**
     * V5 C3 chat-bugfix: delete one session and everything under it
     * (messages + delivery rows). Returns true when a session row was
     * removed, false when the id did not exist (nothing deleted).
     */
    fun deleteSession(sessionId: String): Boolean
}

/** Default ledger for callers without a session centre: records nothing,
 * classifies everything as chat, and never short-circuits the brain. */
object NoOpSessionLedger : SessionLedger {
    override fun record(input: IncomingMessage): LedgerResult =
        LedgerResult(
            kind = MessageKind.CHAT,
            message = null,
            ok = true,
            summary = "未接会话账本，按普通聊天处理",
        )

    override fun createSession(
        name: String,
        kind: SessionKind,
        ownerId: String,
        members: List<String>,
        taskId: String?,
        goalId: String?,
        attemptId: String?,
    ): SessionRecord =
        throw UnsupportedOperationException("no session ledger configured")

    override fun getSession(sessionId: String): SessionRecord? = null
    override fun getMessage(messageId: String): SessionMessage? = null
    override fun listMessages(conversationId: String, limit: Int, offset: Int): List<SessionMessage> =
        emptyList()

    override fun listSessions(ownerId: String): List<SessionRecord> = emptyList()

    override fun readMessage(viewerId: String, messageId: String): SessionMessage? = null

    override fun deleteSession(sessionId: String): Boolean = false
}

class SessionLedgerImpl(
    private val sessionDao: SessionDao,
    private val messageDao: SessionMessageDao,
    private val deliveryDao: SessionDeliveryDao,
    private val controlRouter: ControlInstructionRouter,
    private val authStore: AuthorizationStore,
    private val auditLog: AuditLog? = null,
    /** Supervisor ("mother") id: may read CONTROL rows, never CHAT bodies. */
    private val motherAgentId: String = "mother",
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : SessionLedger {
    private val lock = Any()
    private val deliveryTracker = DeliveryTracker(deliveryDao, nowSeconds)

    override fun createSession(
        name: String,
        kind: SessionKind,
        ownerId: String,
        members: List<String>,
        taskId: String?,
        goalId: String?,
        attemptId: String?,
    ): SessionRecord {
        val cleanName = validateSessionName(name)
        val memberList = validateMembers(members + ownerId)
        require(ownerId.isNotBlank()) { "session owner must not be empty" }
        require(ownerId !in RESERVED_SENDER_IDS) { "session owner id is reserved: $ownerId" }
        synchronized(lock) {
            requireSessionSlot(sessionDao.countByOwner(ownerId))
            val now = nowSeconds()
            val record = SessionRecord(
                sessionId = SessionMessage.newId(),
                name = cleanName,
                kind = kind,
                ownerId = ownerId,
                members = memberList,
                taskId = taskId,
                goalId = goalId,
                attemptId = attemptId,
                createdAtEpochSeconds = now,
                updatedAtEpochSeconds = now,
            )
            sessionDao.insert(record.toEntity())
            return record
        }
    }

    override fun getSession(sessionId: String): SessionRecord? =
        sessionDao.getById(sessionId)?.toRecord()

    override fun getMessage(messageId: String): SessionMessage? =
        messageDao.getById(messageId)?.toMessage()

    override fun listMessages(
        conversationId: String,
        limit: Int,
        offset: Int,
    ): List<SessionMessage> =
        messageDao.listByConversation(conversationId, limit, offset).map { it.toMessage() }

    override fun listSessions(ownerId: String): List<SessionRecord> =
        sessionDao.listByOwner(ownerId).map { it.toRecord() }

    override fun deleteSession(sessionId: String): Boolean {
        synchronized(lock) {
            // Deliveries first (they reference the messages), then the
            // messages, then the session row itself.
            val messageIds = messageDao.listByConversation(sessionId, Int.MAX_VALUE, 0)
                .map { it.messageId }
            if (messageIds.isNotEmpty()) deliveryDao.deleteByMessages(messageIds)
            messageDao.deleteByConversation(sessionId)
            return sessionDao.deleteById(sessionId) == 1
        }
    }

    override fun readMessage(viewerId: String, messageId: String): SessionMessage? {
        val row = messageDao.getById(messageId)?.toMessage() ?: return null
        val session = sessionDao.getById(row.conversationId)?.toRecord() ?: return null
        if (viewerId in session.members) return row
        if (viewerId == motherAgentId && row.messageType != MessageKind.CHAT) return row
        return null
    }

    override fun record(input: IncomingMessage): LedgerResult {
        synchronized(lock) {
            return recordLocked(input)
        }
    }

    private fun recordLocked(input: IncomingMessage): LedgerResult {
        val now = nowSeconds()
        // The brain's conversation ids auto-materialise as private
        // sessions owned by the sender, so chat() needs only one call.
        val session = sessionDao.getById(input.conversationId)?.toRecord()
            ?: materializeChatSession(input.conversationId, input.senderId)

        // Validate (refusals store nothing and return ok=false).
        val validated = try {
            validateSender(input.senderId, session.members)
            val cleaned = validateMessageContent(input.content)
            val messageId = input.messageId ?: SessionMessage.newId()
            require(SessionMessage.isValidMessageId(messageId)) {
                "messageId must be a UUID"
            }
            if (input.causationId != null) {
                val parent = messageDao.getById(input.causationId)?.toMessage()
                validateReplyTo(input.causationId, parent, input.conversationId)
            }
            val ttl = input.ttlSeconds
            if (ttl != null && ttl < 0) {
                throw IllegalArgumentException("ttl must not be negative")
            }
            Triple(cleaned, messageId, ttl)
        } catch (e: IllegalArgumentException) {
            return LedgerResult(
                kind = input.messageTypeHint ?: MessageKind.CHAT,
                message = null,
                ok = false,
                errorCode = "invalid_message",
                reason = e.message,
                summary = "消息未记录：${e.message}",
            )
        } catch (e: IllegalStateException) {
            return LedgerResult(
                kind = input.messageTypeHint ?: MessageKind.CHAT,
                message = null,
                ok = false,
                errorCode = "invalid_message",
                reason = e.message,
                summary = "消息未记录：${e.message}",
            )
        }
        val (cleaned, messageId, ttlSeconds) = validated

        // TTL gate: a negative ttl is already refused in validation
        // above; the pure expiry rule itself (createdAt + ttl < now)
        // lives on SessionMessage.isExpired and is covered in
        // SessionTypesTest.

        // Idempotency: one messageId, one row, one task write.
        messageDao.getById(messageId)?.toMessage()?.let { existing ->
            return LedgerResult(
                kind = existing.messageType,
                message = existing,
                ok = true,
                summary = "重复提交，已按原消息返回，未重复写账本",
            )
        }

        // Classify (deterministic; the sender counts as authorized when
        // they own the session or the device grant is present — the
        // router re-checks with the delegation switch before acting).
        val senderAuthorized =
            input.senderId == session.ownerId || authStore.deviceAuthorized()
        // Classify against the same redacted body that is persisted. Otherwise
        // a secret-bearing message could be reclassified as control text after
        // redaction, and the unredacted body would also remain in the audit
        // metadata path.
        val safeContent = redactMessageContent(cleaned)
        val classification = classify(safeContent, senderAuthorized)

        val kind = when (classification) {
            is Classification.Control -> MessageKind.CONTROL
            else -> MessageKind.CHAT
        }
        val auditMeta = when (classification) {
            is Classification.Rejected -> MessageAuditMeta(
                actor = input.senderId,
                action = "control_rejected",
                epochSeconds = now,
                ok = false,
                errorCode = classification.errorCode,
            )
            is Classification.Control -> MessageAuditMeta(
                actor = input.senderId,
                action = "control_recorded",
                epochSeconds = now,
                ok = true,
            )
            is Classification.Chat -> null
        }
        val message = newSessionMessage(
            conversationId = input.conversationId,
            senderId = input.senderId,
            content = safeContent,
            recipientId = input.recipientId,
            taskId = input.taskId,
            attemptId = input.attemptId,
            messageType = kind,
            causationId = input.causationId,
            messageId = messageId,
            idempotencyKey = input.idempotencyKey,
            operationId = input.operationId,
            authVersion = input.authVersion,
            goalVersion = input.goalVersion,
            ttlSeconds = ttlSeconds,
            createdAtEpochSeconds = now,
        ).copy(audit = auditMeta)

        try {
            messageDao.insert(message.toEntity())
        } catch (_: RuntimeException) {
            // Lost the insert race with an identical messageId: return
            // the row that won instead of writing twice.
            val existing = messageDao.getById(messageId)?.toMessage()
            if (existing != null) {
                return LedgerResult(
                    kind = existing.messageType,
                    message = existing,
                    ok = true,
                    summary = "重复提交，已按原消息返回，未重复写账本",
                )
            }
            throw IllegalStateException("failed to persist message $messageId")
        }

        // Delivery rows: sender DELIVERED, everyone else PERSISTED
        // (pending pickup — never faked as delivered).
        val deliveries = deliveryTracker.ensureRows(
            messageId = messageId,
            memberIds = session.members,
            senderId = input.senderId,
        )
        val deliveredCount = deliveries.count { it.state() == DeliveryState.DELIVERED }
        val pendingCount = deliveries.size - deliveredCount

        when (classification) {
            is Classification.Chat -> {
                audit(message, "session_record", true, null, deliveredCount, pendingCount)
                return LedgerResult(
                    kind = MessageKind.CHAT,
                    message = message,
                    ok = true,
                    summary = "已记录会话消息",
                )
            }
            is Classification.Rejected -> {
                audit(
                    message, "session_rejected", false,
                    classification.errorCode, deliveredCount, pendingCount,
                )
                return LedgerResult(
                    kind = MessageKind.CHAT,
                    message = message,
                    ok = false,
                    errorCode = classification.errorCode,
                    reason = classification.reason,
                    summary = "控制指令未执行：${classification.reason}",
                )
            }
            is Classification.Control -> {
                // A stale version from the router means the task moved
                // under us: the message row stays, delivery rows stay
                // PENDING, and the failure is reported + audited —
                // failures never lose the message.
                val routed = try {
                    controlRouter.route(message, session)
                } catch (e: StaleTaskUpdate) {
                    RouteResult(
                        ok = false,
                        errorCode = "stale_task_version",
                        reason = e.message,
                        recipientId = message.recipientId,
                    )
                } catch (e: ControlVersionStale) {
                    RouteResult(
                        ok = false,
                        errorCode = "stale_control_version",
                        reason = e.message,
                        recipientId = message.recipientId,
                    )
                }
                audit(
                    message, "session_control", routed.ok,
                    routed.errorCode, deliveredCount, pendingCount,
                )
                val verb = classification.instruction.verb.name
                return if (routed.ok) {
                    LedgerResult(
                        kind = MessageKind.CONTROL,
                        message = message,
                        ok = true,
                        control = routed,
                        summary = "已记录控制指令：$verb 任务 " +
                            "${classification.instruction.taskId}（原因：" +
                            "${classification.instruction.reason}），" +
                            "已交接控制层",
                    )
                } else {
                    LedgerResult(
                        kind = MessageKind.CONTROL,
                        message = message,
                        ok = false,
                        errorCode = routed.errorCode,
                        reason = routed.reason,
                        control = routed,
                        summary = "控制指令已记录但未执行：${routed.reason}",
                    )
                }
            }
        }
    }

    /**
     * Chat conversation ids are supplied by the chat UI and are also the
     * conversationId stored on ledger messages. Keep that id as the session
     * primary key too. The previous createSession() path minted a second
     * random id, leaving a metadata-only session row that could not resolve
     * its messages through getSession()/readMessage().
     */
    private fun materializeChatSession(conversationId: String, ownerId: String): SessionRecord {
        val members = validateMembers(listOf(ownerId))
        require(ownerId.isNotBlank()) { "session owner must not be empty" }
        require(ownerId !in RESERVED_SENDER_IDS) { "session owner id is reserved: $ownerId" }
        synchronized(lock) {
            requireSessionSlot(sessionDao.countByOwner(ownerId))
            val now = nowSeconds()
            return SessionRecord(
                sessionId = conversationId,
                name = validateSessionName("chat:${conversationId.take(40)}"),
                kind = SessionKind.PRIVATE,
                ownerId = ownerId,
                members = members,
                createdAtEpochSeconds = now,
                updatedAtEpochSeconds = now,
            ).also { sessionDao.insert(it.toEntity()) }
        }
    }

    private fun audit(
        message: SessionMessage,
        action: String,
        ok: Boolean,
        errorCode: String?,
        deliveredCount: Int,
        pendingCount: Int,
    ) {
        val log = auditLog ?: return
        log.record(
            AuditEntry(
                ts = utcNowIso(),
                taskId = message.taskId ?: message.conversationId,
                agentId = message.senderId,
                action = action,
                ok = ok,
                errorCode = errorCode,
                sessionId = message.conversationId,
                // Metadata only: message id + counts, never the body.
                result = mapOf(
                    "message_id" to message.messageId,
                    "delivered_count" to deliveredCount,
                    "pending_count" to pendingCount,
                ),
            ),
        )
    }
}
