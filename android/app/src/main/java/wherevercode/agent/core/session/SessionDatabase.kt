package wherevercode.agent.core.session

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONArray

/**
 * SessionDatabase (M2 S2): Room persistence for the session centre —
 * exactly three tables + their DAOs, no @Database class.
 *
 * The tables live in the same Room database as the tasks
 * (`wherevercode-tasks.db`, U5: one database, separate tables, so one
 * transaction covers control / task / message / audit). The @Database
 * class itself lives in TaskStore.kt and is registered there by the
 * merge coordinator; tests use a test-local @Database class in
 * SessionDatabaseTest. S3/S4/S5 constructors take only these DAO
 * interfaces, never a database class.
 *
 * Tables:
 * - sessions: one row per conversation. A task discussion binds exactly
 *   one taskId (U9); the row also carries goalId/attemptId so the
 *   discussion stays traceable to its goal/attempt.
 * - session_messages: one row per message; messageId is the primary key
 *   (one message is stored exactly once). All ten WACF §5.1 fields are
 *   columns here; enums are stored as their names (no TypeConverter).
 * - session_deliveries: one row per (message, member); metadata only,
 *   never the body (U11).
 */

// ---------------------------------------------------------------------------
// sessions
// ---------------------------------------------------------------------------

@Entity(
    tableName = "sessions",
    indices = [Index("ownerId"), Index("taskId")],
)
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val name: String,
    /** SessionKind name (PRIVATE / GROUP / TASK). */
    val kind: String,
    val ownerId: String,
    /** Member agent ids, JSON array string. */
    val membersJson: String,
    /** Task-bound discussion only; once set it is never rebound (U9). */
    val taskId: String? = null,
    val goalId: String? = null,
    val attemptId: String? = null,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
)

fun SessionRecord.toEntity(): SessionEntity {
    val array = JSONArray()
    members.forEach { array.put(it) }
    return SessionEntity(
        sessionId = sessionId,
        name = name,
        kind = kind.name,
        ownerId = ownerId,
        membersJson = array.toString(),
        taskId = taskId,
        goalId = goalId,
        attemptId = attemptId,
        createdAtEpochSeconds = createdAtEpochSeconds,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )
}

fun SessionEntity.toRecord(): SessionRecord {
    val members = mutableListOf<String>()
    try {
        val array = JSONArray(membersJson)
        for (i in 0 until array.length()) {
            val id = array.optString(i, "")
            if (id.isNotEmpty()) members += id
        }
    } catch (_: Exception) {
        // A corrupt members cell never breaks reads; treat as empty.
    }
    return SessionRecord(
        sessionId = sessionId,
        name = name,
        kind = runCatching { SessionKind.valueOf(kind) }.getOrDefault(SessionKind.PRIVATE),
        ownerId = ownerId,
        members = members,
        taskId = taskId,
        goalId = goalId,
        attemptId = attemptId,
        createdAtEpochSeconds = createdAtEpochSeconds,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    fun getById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE ownerId = :ownerId ORDER BY createdAtEpochSeconds DESC")
    fun listByOwner(ownerId: String): List<SessionEntity>

    @Query("SELECT COUNT(*) FROM sessions WHERE ownerId = :ownerId")
    fun countByOwner(ownerId: String): Int

    @Query("SELECT * FROM sessions WHERE taskId = :taskId")
    fun listByTask(taskId: String): List<SessionEntity>

    /**
     * V5 C3 chat-bugfix: delete one session row; returns rows removed
     * (0 = unknown id, 1 = deleted). Messages/deliveries are deleted
     * first by [SessionLedger.deleteSession] — this only removes the row.
     */
    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    fun deleteById(sessionId: String): Int

    /**
     * Bind a task to a task-discussion session. Only binds when the row
     * has no task yet; returns the number of rows changed (1 = bound,
     * 0 = already bound). Rebinding is refused by [bindSessionTask].
     */
    @Query(
        "UPDATE sessions SET taskId = :taskId " +
            "WHERE sessionId = :sessionId AND taskId IS NULL",
    )
    fun bindTask(sessionId: String, taskId: String): Int
}

/**
 * Bind [taskId] to a session, or throw. Idempotent for the same task;
 * binding a different task to an already-bound session is refused
 * (one session, one taskId — U9). The original messages are untouched.
 */
fun bindSessionTask(sessionDao: SessionDao, sessionId: String, taskId: String): SessionRecord {
    val current = sessionDao.getById(sessionId)?.toRecord()
        ?: throw IllegalArgumentException("unknown session: $sessionId")
    if (current.taskId == taskId) return current
    if (current.taskId != null) {
        throw IllegalStateException(
            "session $sessionId is already bound to task ${current.taskId}",
        )
    }
    val changed = sessionDao.bindTask(sessionId, taskId)
    if (changed != 1) {
        val raced = sessionDao.getById(sessionId)?.toRecord()
        if (raced?.taskId == taskId) return raced
        throw IllegalStateException("session $sessionId could not be bound to task $taskId")
    }
    return current.copy(taskId = taskId)
}

// ---------------------------------------------------------------------------
// session_messages
// ---------------------------------------------------------------------------

@Entity(
    tableName = "session_messages",
    indices = [Index("conversationId"), Index("taskId")],
)
data class SessionMessageEntity(
    /** WACF §5.1 field 1: UUID primary key — one message, one row. */
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val senderId: String,
    val recipientId: String? = null,
    val taskId: String? = null,
    val attemptId: String? = null,
    /** MessageKind name (CHAT / CONTROL / TASK / EVENT / DELIVERY). */
    val messageType: String,
    val causationId: String? = null,
    val idempotencyKey: String,
    val operationId: String? = null,
    /** DeliveryState name (PERSISTED / DELIVERED / CLAIMED / READ). */
    val deliveryState: String,
    val authVersion: Long = 1,
    val goalVersion: Long = 1,
    val ttlSeconds: Long? = null,
    /** Body, already cleaned + secret-redacted by the ledger. */
    val content: String,
    val createdAtEpochSeconds: Long,
    /** Audit metadata only (actor/action/ts/ok/errorCode), never the body. */
    val auditActor: String? = null,
    val auditAction: String? = null,
    val auditEpochSeconds: Long? = null,
    val auditOk: Boolean? = null,
    val auditErrorCode: String? = null,
)

fun SessionMessage.toEntity(): SessionMessageEntity =
    SessionMessageEntity(
        messageId = messageId,
        conversationId = conversationId,
        senderId = senderId,
        recipientId = recipientId,
        taskId = taskId,
        attemptId = attemptId,
        messageType = messageType.name,
        causationId = causationId,
        idempotencyKey = idempotencyKey,
        operationId = operationId,
        deliveryState = deliveryState.name,
        authVersion = authVersion,
        goalVersion = goalVersion,
        ttlSeconds = ttlSeconds,
        content = content,
        createdAtEpochSeconds = createdAtEpochSeconds,
        auditActor = audit?.actor,
        auditAction = audit?.action,
        auditEpochSeconds = audit?.epochSeconds,
        auditOk = audit?.ok,
        auditErrorCode = audit?.errorCode,
    )

fun SessionMessageEntity.toMessage(): SessionMessage =
    SessionMessage(
        messageId = messageId,
        conversationId = conversationId,
        senderId = senderId,
        recipientId = recipientId,
        taskId = taskId,
        attemptId = attemptId,
        messageType = runCatching { MessageKind.valueOf(messageType) }
            .getOrDefault(MessageKind.CHAT),
        causationId = causationId,
        idempotencyKey = idempotencyKey,
        operationId = operationId,
        deliveryState = runCatching { DeliveryState.valueOf(deliveryState) }
            .getOrDefault(DeliveryState.PERSISTED),
        authVersion = authVersion,
        goalVersion = goalVersion,
        ttlSeconds = ttlSeconds,
        content = content,
        createdAtEpochSeconds = createdAtEpochSeconds,
        audit = if (auditActor != null && auditAction != null && auditEpochSeconds != null) {
            MessageAuditMeta(
                actor = auditActor,
                action = auditAction,
                epochSeconds = auditEpochSeconds,
                ok = auditOk ?: false,
                errorCode = auditErrorCode,
            )
        } else {
            null
        },
    )

@Dao
interface SessionMessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(message: SessionMessageEntity)

    @Query("SELECT * FROM session_messages WHERE messageId = :messageId")
    fun getById(messageId: String): SessionMessageEntity?

    @Query(
        "SELECT * FROM session_messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAtEpochSeconds ASC LIMIT :limit OFFSET :offset",
    )
    fun listByConversation(conversationId: String, limit: Int, offset: Int): List<SessionMessageEntity>

    @Query("SELECT COUNT(*) FROM session_messages WHERE conversationId = :conversationId")
    fun countByConversation(conversationId: String): Int

    @Query(
        "SELECT * FROM session_messages WHERE taskId = :taskId " +
            "ORDER BY createdAtEpochSeconds ASC",
    )
    fun listByTask(taskId: String): List<SessionMessageEntity>

    /**
     * V5 C3 chat-bugfix: delete every message of one conversation;
     * returns rows removed. Runs after the delivery rows are gone
     * (see [SessionDeliveryDao.deleteByMessages]).
     */
    @Query("DELETE FROM session_messages WHERE conversationId = :conversationId")
    fun deleteByConversation(conversationId: String): Int
}

// ---------------------------------------------------------------------------
// session_deliveries (metadata only, never the body — U11)
// ---------------------------------------------------------------------------

@Entity(
    tableName = "session_deliveries",
    primaryKeys = ["messageId", "memberId"],
    indices = [Index("memberId")],
)
data class SessionDeliveryEntity(
    val messageId: String,
    val memberId: String,
    /** DeliveryState name (PERSISTED / DELIVERED / CLAIMED / READ). */
    val deliveryState: String,
    val updatedAtEpochSeconds: Long,
) {
    fun state(): DeliveryState =
        runCatching { DeliveryState.valueOf(deliveryState) }
            .getOrDefault(DeliveryState.PERSISTED)
}

@Dao
interface SessionDeliveryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(delivery: SessionDeliveryEntity)

    @Query(
        "SELECT * FROM session_deliveries " +
            "WHERE messageId = :messageId AND memberId = :memberId",
    )
    fun get(messageId: String, memberId: String): SessionDeliveryEntity?

    @Query("SELECT * FROM session_deliveries WHERE messageId = :messageId")
    fun listByMessage(messageId: String): List<SessionDeliveryEntity>

    @Query(
        "SELECT * FROM session_deliveries WHERE memberId = :memberId " +
            "AND deliveryState != 'READ'",
    )
    fun listUnread(memberId: String): List<SessionDeliveryEntity>

    @Query(
        "UPDATE session_deliveries SET deliveryState = :state, " +
            "updatedAtEpochSeconds = :now " +
            "WHERE messageId = :messageId AND memberId = :memberId",
    )
    fun updateState(messageId: String, memberId: String, state: String, now: Long): Int

    /**
     * V5 C3 chat-bugfix: delete delivery rows for the given messages;
     * returns rows removed. Must run BEFORE the messages themselves
     * are deleted. An empty list deletes nothing (callers guard it).
     */
    @Query("DELETE FROM session_deliveries WHERE messageId IN (:messageIds)")
    fun deleteByMessages(messageIds: List<String>): Int
}
