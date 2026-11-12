package wherevercode.agent.ui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 聊天历史恢复（阶段 3，U1/批 4）：默认会话快照。
 *
 * 只快照默认会话（ChatViewModel.isDefaultSession() 为真时才保存；
 * 切到 Agent/任务会话时不覆盖默认快照）。快照只存用户原文、
 * 助手正文、终态与错误文案——不存 token、不存请求体、不存
 * 密钥；恢复时按原样重建时间线轮次（状态为终态，不会重跑）。
 * 进程被杀/重启后，主界面在默认会话时间线为空时从快照恢复。
 * 编解码是纯函数（ChatHistorySnapshot），可 JVM 单测；持久化
 * 走 SharedPreferences（Robolectric 覆盖，见 ChatHistoryRestoreTest）。
 *
 * 大脑/Agent 边界（maintainer 2026-11-12）：快照必须带说话者（speaker /
 * speakerIsBrain）与任务归属（taskId/taskTitle）——恢复出的轮次
 * 不许回落到含混的“AGENT”，Agent 回复不许丢归属。旧快照没有这几
 * 项，解码时按缺省补（说话者空、归属空），由调用方按对话方补齐。
 */
data class SavedTurn(
    val userText: String,
    val assistantText: String,
    val status: String,
    val error: String?,
    val speaker: String = "",
    val speakerIsBrain: Boolean = true,
    val taskId: String? = null,
    val taskTitle: String? = null,
    val routeLabel: String? = null,
)

object ChatHistorySnapshot {

    fun encode(turns: List<ChatTurnUi>): String {
        val array = JSONArray()
        for (turn in turns) {
            array.put(
                JSONObject()
                    .put("user", turn.userText)
                    .put("assistant", turn.assistantText)
                    .put("status", turn.status.name)
                    .put("error", turn.error ?: JSONObject.NULL)
                    .put("speaker", turn.speaker)
                    .put("speakerIsBrain", turn.speakerIsBrain)
                    .put("taskId", turn.taskAttribution?.taskId ?: JSONObject.NULL)
                    .put("taskTitle", turn.taskAttribution?.taskTitle ?: JSONObject.NULL)
                    .put("routeLabel", turn.routeLabel ?: JSONObject.NULL),
            )
        }
        return array.toString()
    }

    /** 坏快照容错：整段解析失败返回空列表，绝不因一条坏记录崩溃。 */
    fun decode(json: String): List<SavedTurn> {
        return try {
            val array = JSONArray(json)
            val out = mutableListOf<SavedTurn>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                out += SavedTurn(
                    userText = obj.optString("user", ""),
                    assistantText = obj.optString("assistant", ""),
                    status = obj.optString("status", "DONE"),
                    error = if (obj.isNull("error")) null else obj.optString("error", null),
                    speaker = obj.optString("speaker", ""),
                    speakerIsBrain = obj.optBoolean("speakerIsBrain", true),
                    taskId = if (obj.isNull("taskId")) null else obj.optString("taskId", null),
                    taskTitle = if (obj.isNull("taskTitle")) null else obj.optString("taskTitle", null),
                    routeLabel = if (obj.isNull("routeLabel")) null else obj.optString("routeLabel", null),
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }
}

class ChatHistoryStore(context: Context) {

    companion object {
        const val PREFS_NAME: String = "chat_history"
        const val KEY_MOTHER: String = "mother_session_v1"

        /**
         * V5 C3 chat-bugfix：当前默认会话的 conversationId。快照只存轮次
         * 文本，重启后不知道哪一行才是默认对话——靠这个 id 从 Room
         * 恢复完整历史（含助手正文）；快照是回退（修前建的对话没有 id）。
         */
        const val KEY_CURRENT_SESSION: String = "current_session_id_v1"

        /** 快照只留最近 50 轮，避免 SharedPreferences 膨胀。 */
        const val MAX_TURNS: Int = 50

        /**
         * 主流补齐（2026-11-11）：会话 → 项目绑定（会话 id → 项目 id 的 JSON 对象）。
         * 会话挂在项目上是主流脊柱的一环（会话 id → 任务 → 项目 = 工作区）；
         * 绑定只记 id，不存消息内容。切会话时恢复绑定，换项目不丢会话。
         */
        const val KEY_SESSION_PROJECTS: String = "session_projects_v1"

        /**
         * 大脑/Agent 边界（maintainer 2026-11-12 修订）：会话 → 直连 Agent 开关
         * （会话 id → true/false 的 JSON 对象，显式选择）。直连开 = 该
         * 对话里的消息只发给那个 Agent，大脑不拦截、不派活、不插话，
         * 由 Agent 自己接消息、自己干活；直连关 = 该对话改走大脑幕后
         * 派活。远端设备 Agent 对话默认直连开，未显式选过的会话不记；
         * 显式选过的开与关都存下来，重启后仍按用户的选择走。只记开关，
         * 不存消息内容。
         */
        const val KEY_DIRECT_AGENT_SESSIONS: String = "direct_agent_sessions_v1"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveDirectAgentSessions(flags: Map<String, Boolean>) {
        val obj = JSONObject()
        for ((sessionId, on) in flags) {
            if (sessionId.isNotBlank()) obj.put(sessionId, on)
        }
        prefs.edit().putString(KEY_DIRECT_AGENT_SESSIONS, obj.toString()).apply()
    }

    fun loadDirectAgentSessions(): Map<String, Boolean> {
        val json = prefs.getString(KEY_DIRECT_AGENT_SESSIONS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = LinkedHashMap<String, Boolean>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val sessionId = keys.next()
                if (sessionId.isNotBlank()) out[sessionId] = obj.optBoolean(sessionId, true)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun saveSessionProjects(bindings: Map<String, String>) {
        val obj = JSONObject()
        for ((sessionId, projectId) in bindings) {
            if (sessionId.isNotBlank() && projectId.isNotBlank()) obj.put(sessionId, projectId)
        }
        prefs.edit().putString(KEY_SESSION_PROJECTS, obj.toString()).apply()
    }

    fun loadSessionProjects(): Map<String, String> {
        val json = prefs.getString(KEY_SESSION_PROJECTS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val out = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val sessionId = keys.next()
                val projectId = obj.optString(sessionId, "")
                if (sessionId.isNotBlank() && projectId.isNotBlank()) out[sessionId] = projectId
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(turns: List<ChatTurnUi>) {
        val kept = turns.takeLast(MAX_TURNS)
        prefs.edit().putString(KEY_MOTHER, ChatHistorySnapshot.encode(kept)).apply()
    }

    fun load(): List<SavedTurn> {
        val json = prefs.getString(KEY_MOTHER, null) ?: return emptyList()
        return ChatHistorySnapshot.decode(json)
    }

    fun clear() {
        prefs.edit().remove(KEY_MOTHER).apply()
    }

    fun saveCurrentSessionId(sessionId: String) {
        prefs.edit().putString(KEY_CURRENT_SESSION, sessionId).apply()
    }

    fun loadCurrentSessionId(): String? = prefs.getString(KEY_CURRENT_SESSION, null)

    fun clearCurrentSessionId() {
        prefs.edit().remove(KEY_CURRENT_SESSION).apply()
    }
}
