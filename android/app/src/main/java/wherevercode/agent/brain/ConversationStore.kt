package wherevercode.agent.brain

/**
 * ConversationStore: multi-turn conversation history (stage 2).
 *
 * Model (audit section 4.2): Conversation(id, brainId, title, createdAt)
 * + Message(id, conversationId, role, content, source, createdAt).
 * role is one of user / assistant / tool / system. `source` records the
 * real origin of every assistant message (model id or Agent id), so the
 * UI can always label where a bubble came from.
 *
 * History belongs to the conversation, not to the brain: switching brains
 * only changes [Conversation.brainId], history is kept.
 *
 * This file ships the interface + an in-memory implementation.
 * Room persistence is stage 3 UI work; the interface is deliberately
 * storage-shaped so a Room implementation can replace the in-memory one
 * without touching BrainEngine.
 */
data class Conversation(
    val id: String,
    val brainId: String?,
    val title: String,
    val createdAtEpochSeconds: Long,
)

data class Message(
    val id: String,
    val conversationId: String,
    val role: String, // user | assistant | tool | system
    val content: String,
    val source: String,
    val createdAtEpochSeconds: Long,
    /**
     * M2 M8: task this message belongs to, when it is part of a task
     * discussion. Null by default — existing call sites and tests are
     * untouched. Bound after the fact (a message is stored first, then
     * linked to its task); the content never changes on binding.
     */
    val taskId: String? = null,
    /**
     * Tool protocol pairing (2026-11-09 root-cause fix). An assistant
     * message that requested tools keeps the structured calls; a tool
     * message keeps the id it answers. Persisting both is what lets a
     * later turn rebuild a valid native tool history instead of an
     * unpaired `role="tool"` message that OpenAI-compatible APIs reject.
     */
    val toolCalls: List<ChatToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    init {
        require(role in ROLES) { "unknown message role: $role" }
    }

    companion object {
        val ROLES: Set<String> = setOf("user", "assistant", "tool", "system")
    }
}

interface ConversationStore {
    fun createConversation(brainId: String?, title: String): Conversation
    fun getConversation(id: String): Conversation?

    /**
     * Get the conversation with [id], creating it under that same id
     * when missing — the caller-supplied id is reused, never replaced.
     *
     * V5 C3 chat-bugfix: the chat UI owns the session id, so the engine
     * must not mint a fresh row per turn. Before this fix, [BrainEngine]
     * called [getConversation] (always a miss for a UI-owned id) then
     * [createConversation] (fresh UUID), so every turn fragmented into
     * its own conversation + session row and the model only ever saw
     * the current turn. Default: old behaviour (a fresh id) for stores
     * that do not override; both production stores override below.
     */
    fun getOrCreateConversation(id: String, brainId: String?, title: String): Conversation {
        return getConversation(id) ?: createConversation(brainId, title)
    }

    fun listConversations(): List<Conversation>
    fun updateConversationBrain(id: String, brainId: String?)
    fun deleteConversation(id: String): Boolean
    fun addMessage(message: Message): Message
    fun listMessages(conversationId: String, limit: Int? = null): List<Message>
    /** M2 M8: all messages bound to [taskId], across conversations. */
    fun listMessagesForTask(taskId: String): List<Message>
}

class InMemoryConversationStore : ConversationStore {
    private val lock = Any()
    private val conversations = LinkedHashMap<String, Conversation>()
    private val messages = LinkedHashMap<String, MutableList<Message>>()

    override fun createConversation(brainId: String?, title: String): Conversation {
        val conversation = Conversation(
            id = java.util.UUID.randomUUID().toString(),
            brainId = brainId,
            title = title,
            createdAtEpochSeconds = System.currentTimeMillis() / 1000,
        )
        synchronized(lock) {
            conversations[conversation.id] = conversation
            messages[conversation.id] = mutableListOf()
        }
        return conversation
    }

    override fun getConversation(id: String): Conversation? =
        synchronized(lock) { conversations[id] }

    override fun getOrCreateConversation(
        id: String,
        brainId: String?,
        title: String,
    ): Conversation {
        synchronized(lock) {
            val existing = conversations[id]
            if (existing != null) return existing
            val conversation = Conversation(
                id = id,
                brainId = brainId,
                title = title,
                createdAtEpochSeconds = System.currentTimeMillis() / 1000,
            )
            conversations[id] = conversation
            messages[id] = mutableListOf()
            return conversation
        }
    }

    override fun listConversations(): List<Conversation> =
        synchronized(lock) { conversations.values.sortedByDescending { it.createdAtEpochSeconds } }

    override fun updateConversationBrain(id: String, brainId: String?) {
        synchronized(lock) {
            val current = conversations[id] ?: return
            conversations[id] = current.copy(brainId = brainId)
        }
    }

    override fun deleteConversation(id: String): Boolean =
        synchronized(lock) {
            messages.remove(id)
            conversations.remove(id) != null
        }

    override fun addMessage(message: Message): Message {
        synchronized(lock) {
            val list = messages[message.conversationId]
                ?: throw IllegalArgumentException("unknown conversation: ${message.conversationId}")
            list += message
        }
        return message
    }

    override fun listMessages(conversationId: String, limit: Int?): List<Message> =
        synchronized(lock) {
            val list = messages[conversationId]?.toList() ?: emptyList()
            if (limit == null) list else list.takeLast(limit)
        }

    override fun listMessagesForTask(taskId: String): List<Message> =
        synchronized(lock) {
            messages.values.flatten().filter { it.taskId == taskId }
        }
}
