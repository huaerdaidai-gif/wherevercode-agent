package wherevercode.agent.brain

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.core.task.TaskDatabase

/**
 * Conversation persistence (V5 Phase B C1, W2): Room tables for the
 * multi-turn history that [ConversationStore] describes.
 *
 * Two tables, both in the shared [TaskDatabase] (`wherevercode-tasks.db`):
 * - conversations: one row per conversation ([Conversation] fields).
 * - conversation_messages: one row per message ([Message] fields).
 *
 * Mapping notes (same conventions as SessionDatabase.kt):
 * - [Message.toolCalls] is stored as a JSON array string in
 *   `toolCallsJson` (`[{"id":…,"name":…,"argumentsJson":…}]`, org.json,
 *   like TaskStore's dependenciesJson). A corrupt cell never breaks
 *   reads: it decodes to an empty list.
 * - Ordering is by `createdAtEpochSeconds` with `rowid` as the tiebreak,
 *   so same-second rows keep insertion order — the in-memory store's
 *   stable-sort behaviour (see TaskSteps.kt's CI 42558012978 note).
 * - No foreign key from messages to conversations: deleting a
 *   conversation deletes its messages explicitly in [RoomConversationStore]
 *   (same transaction), so a missing parent row can never strand a
 *   message, and the migration stays CREATE-only.
 */
@Entity(
    tableName = "conversations",
    indices = [Index("createdAtEpochSeconds")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val brainId: String? = null,
    val title: String,
    val createdAtEpochSeconds: Long,
)

fun Conversation.toEntity(): ConversationEntity =
    ConversationEntity(
        id = id,
        brainId = brainId,
        title = title,
        createdAtEpochSeconds = createdAtEpochSeconds,
    )

fun ConversationEntity.toConversation(): Conversation =
    Conversation(
        id = id,
        brainId = brainId,
        title = title,
        createdAtEpochSeconds = createdAtEpochSeconds,
    )

@Entity(
    tableName = "conversation_messages",
    indices = [Index("conversationId"), Index("taskId")],
)
data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val source: String,
    val createdAtEpochSeconds: Long,
    val taskId: String? = null,
    /** [Message.toolCalls] as a JSON array string; "[]" when empty. */
    val toolCallsJson: String = "[]",
    val toolCallId: String? = null,
)

fun Message.toEntity(): ConversationMessageEntity {
    val array = JSONArray()
    toolCalls.forEach { call ->
        array.put(
            JSONObject()
                .put("id", call.id)
                .put("name", call.name)
                .put("argumentsJson", call.argumentsJson),
        )
    }
    return ConversationMessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        source = source,
        createdAtEpochSeconds = createdAtEpochSeconds,
        taskId = taskId,
        toolCallsJson = array.toString(),
        toolCallId = toolCallId,
    )
}

fun ConversationMessageEntity.toMessage(): Message {
    val calls = mutableListOf<ChatToolCall>()
    try {
        val array = JSONArray(toolCallsJson)
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            calls += ChatToolCall(
                id = obj.optString("id", ""),
                name = obj.optString("name", ""),
                argumentsJson = obj.optString("argumentsJson", ""),
            )
        }
    } catch (_: Exception) {
        // A corrupt toolCalls cell never breaks reads; treat as no calls.
        calls.clear()
    }
    return Message(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        source = source,
        createdAtEpochSeconds = createdAtEpochSeconds,
        taskId = taskId,
        toolCalls = calls,
        toolCallId = toolCallId,
    )
}

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getById(id: String): ConversationEntity?

    @Query(
        "SELECT * FROM conversations " +
            "ORDER BY createdAtEpochSeconds DESC, rowid ASC",
    )
    fun listAll(): List<ConversationEntity>

    @Query("UPDATE conversations SET brainId = :brainId WHERE id = :id")
    fun updateBrain(id: String, brainId: String?): Int

    @Query("DELETE FROM conversations WHERE id = :id")
    fun deleteById(id: String): Int

    /** Probe query: forces Room to open the file (and run migrations). */
    @Query("SELECT COUNT(*) FROM conversations")
    fun countAll(): Int
}

@Dao
interface ConversationMessageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(message: ConversationMessageEntity)

    @Query(
        "SELECT * FROM conversation_messages WHERE conversationId = :conversationId " +
            "ORDER BY createdAtEpochSeconds ASC, rowid ASC",
    )
    fun listByConversation(conversationId: String): List<ConversationMessageEntity>

    @Query(
        "SELECT * FROM conversation_messages WHERE taskId = :taskId " +
            "ORDER BY createdAtEpochSeconds ASC, rowid ASC",
    )
    fun listByTask(taskId: String): List<ConversationMessageEntity>

    @Query("DELETE FROM conversation_messages WHERE conversationId = :conversationId")
    fun deleteByConversation(conversationId: String): Int
}

/**
 * Room-backed [ConversationStore]. Behaviour matches
 * [InMemoryConversationStore] exactly, including its error contract:
 * [addMessage] throws [IllegalArgumentException] for an unknown
 * conversation, [deleteConversation] returns false when the id does
 * not exist, and [listMessages] with a limit returns the last N
 * messages in insertion order.
 *
 * Callers must call this off the main thread: Room forbids main-thread
 * database access and these methods are synchronous (same rule as
 * [wherevercode.agent.core.task.RoomTaskStore]).
 */
class RoomConversationStore(private val db: TaskDatabase) : ConversationStore {
    private val conversations: ConversationDao = db.conversationDao()
    private val messages: ConversationMessageDao = db.conversationMessageDao()
    private val lock = Any()

    override fun createConversation(brainId: String?, title: String): Conversation {
        val conversation = Conversation(
            id = java.util.UUID.randomUUID().toString(),
            brainId = brainId,
            title = title,
            createdAtEpochSeconds = System.currentTimeMillis() / 1000,
        )
        synchronized(lock) { conversations.insert(conversation.toEntity()) }
        return conversation
    }

    override fun getConversation(id: String): Conversation? =
        synchronized(lock) { conversations.getById(id)?.toConversation() }

    override fun getOrCreateConversation(
        id: String,
        brainId: String?,
        title: String,
    ): Conversation {
        synchronized(lock) {
            conversations.getById(id)?.toConversation()?.let { return it }
            val conversation = Conversation(
                id = id,
                brainId = brainId,
                title = title,
                createdAtEpochSeconds = System.currentTimeMillis() / 1000,
            )
            try {
                conversations.insert(conversation.toEntity())
            } catch (e: RuntimeException) {
                // Lost the insert race with the same id: return the row
                // that won instead of failing the turn.
                val raced = conversations.getById(id)?.toConversation()
                if (raced != null) return raced
                throw e
            }
            return conversation
        }
    }

    override fun listConversations(): List<Conversation> =
        synchronized(lock) { conversations.listAll().map { it.toConversation() } }

    override fun updateConversationBrain(id: String, brainId: String?) {
        synchronized(lock) { conversations.updateBrain(id, brainId) }
    }

    override fun deleteConversation(id: String): Boolean =
        synchronized(lock) {
            // Explicit Runnable: RoomDatabase has both runInTransaction
            // (Runnable) and runInTransaction (Callable) overloads, and
            // a bare trailing lambda is ambiguous between them.
            var deleted = false
            db.runInTransaction(Runnable {
                messages.deleteByConversation(id)
                deleted = conversations.deleteById(id) == 1
            })
            deleted
        }

    override fun addMessage(message: Message): Message {
        synchronized(lock) {
            if (conversations.getById(message.conversationId) == null) {
                throw IllegalArgumentException(
                    "unknown conversation: ${message.conversationId}",
                )
            }
            messages.insert(message.toEntity())
        }
        return message
    }

    override fun listMessages(conversationId: String, limit: Int?): List<Message> =
        synchronized(lock) {
            val list = messages.listByConversation(conversationId).map { it.toMessage() }
            if (limit == null) list else list.takeLast(limit)
        }

    override fun listMessagesForTask(taskId: String): List<Message> =
        synchronized(lock) { messages.listByTask(taskId).map { it.toMessage() } }
}
