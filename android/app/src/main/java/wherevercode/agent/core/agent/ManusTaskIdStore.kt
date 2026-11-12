package wherevercode.agent.core.agent

import android.content.Context

/**
 * Manus 聊天会话 → Manus 任务 id 的持久映射。
 *
 * 多轮连续的关键：同一聊天会话的跟进消息必须发到同一个 Manus
 * 任务上（POST /v2/task.sendMessage），而不是每次新建任务。
 * 任务 id 在创建成功后立即持久化；App 重启后仍能跟进同一任务。
 * 只存 task_id，不存密钥、不存消息内容。
 */
interface ManusTaskIdStore {
    fun get(conversationId: String): String?
    fun put(conversationId: String, taskId: String)
    fun clear(conversationId: String)
}

/** SharedPreferences 实现（生产用）。 */
class SharedPreferencesManusTaskIdStore(context: Context) : ManusTaskIdStore {
    private val prefs = context.getSharedPreferences(
        "wherevercode_manus_chat_tasks",
        Context.MODE_PRIVATE,
    )

    override fun get(conversationId: String): String? =
        prefs.getString(key(conversationId), null)

    override fun put(conversationId: String, taskId: String) {
        prefs.edit().putString(key(conversationId), taskId).apply()
    }

    override fun clear(conversationId: String) {
        prefs.edit().remove(key(conversationId)).apply()
    }

    private fun key(conversationId: String): String = "task:$conversationId"
}

/** 内存实现（单测用）。 */
class InMemoryManusTaskIdStore : ManusTaskIdStore {
    private val map = HashMap<String, String>()
    override fun get(conversationId: String): String? = map[conversationId]
    override fun put(conversationId: String, taskId: String) {
        map[conversationId] = taskId
    }
    override fun clear(conversationId: String) {
        map.remove(conversationId)
    }
}
