package wherevercode.agent.core.a2a

import org.json.JSONObject

/**
 * A2A 任务状态（服务端 V5 队列状态 → A2A 状态的映射，见
 * [A2aTaskParser.stateOf]）。`UNKNOWN` 是兜底：服务端以后加了新状态，
 * 客户端不至于整个解析失败。
 */
enum class A2aTaskState {
    SUBMITTED,
    WORKING,
    COMPLETED,
    CANCELED,
    INPUT_REQUIRED,
    UNKNOWN,
}

/**
 * 一个 A2A 任务（服务端 `tasks/get` / `message/send` 返回的形状）。
 * `id` 与 `contextId` 都是 V5 任务 id：调用方按 id 寻址，不用另记
 * context。`artifacts` 是交付证据拼成的文本行（branch / commit /
 * pr，没有证据时为空）。`history` 只在调用方主动要时才带
 * （`tasks/get` 的 `historyLength`）。
 */
data class A2aTask(
    val id: String,
    val contextId: String,
    val state: A2aTaskState,
    val stateTimestamp: String,
    val artifacts: List<String>,
    val assignee: String,
    val projectId: String,
    val round: Int,
    val maxRounds: Int,
    val title: String,
    val history: List<String> = emptyList(),
)

object A2aTaskParser {
    /** V5 队列状态 → A2A 状态（与服务端 `_A2A_STATE_MAP` 同一张表）。 */
    fun stateOf(serverState: String): A2aTaskState = when (serverState) {
        "submitted" -> A2aTaskState.SUBMITTED
        "working" -> A2aTaskState.WORKING
        "completed" -> A2aTaskState.COMPLETED
        "canceled" -> A2aTaskState.CANCELED
        "input-required" -> A2aTaskState.INPUT_REQUIRED
        else -> A2aTaskState.UNKNOWN
    }

    fun parse(json: JSONObject): A2aTask {
        val id = json.optString("id", "").trim()
        require(id.isNotBlank()) { "A2A 任务缺少 id" }
        val status = json.optJSONObject("status") ?: JSONObject()
        val artifacts = mutableListOf<String>()
        val artifactsJson = json.optJSONArray("artifacts")
        if (artifactsJson != null) {
            for (i in 0 until artifactsJson.length()) {
                val artifact = artifactsJson.optJSONObject(i) ?: continue
                val parts = artifact.optJSONArray("parts")
                if (parts != null) {
                    for (j in 0 until parts.length()) {
                        val text = parts.optJSONObject(j)?.optString("text", "")
                        if (!text.isNullOrBlank()) artifacts += text
                    }
                }
            }
        }
        val history = mutableListOf<String>()
        val historyJson = json.optJSONArray("history")
        if (historyJson != null) {
            for (i in 0 until historyJson.length()) {
                val message = historyJson.optJSONObject(i) ?: continue
                val parts = message.optJSONArray("parts")
                if (parts != null) {
                    for (j in 0 until parts.length()) {
                        val text = parts.optJSONObject(j)?.optString("text", "")
                        if (!text.isNullOrBlank()) history += text
                    }
                }
            }
        }
        val metadata = json.optJSONObject("metadata") ?: JSONObject()
        return A2aTask(
            id = id,
            contextId = json.optString("contextId", id),
            state = stateOf(status.optString("state", "")),
            stateTimestamp = status.optString("timestamp", ""),
            artifacts = artifacts,
            assignee = metadata.optString("assignee", ""),
            projectId = metadata.optString("projectId", ""),
            round = metadata.optInt("round", 0),
            maxRounds = metadata.optInt("maxRounds", 0),
            title = metadata.optString("title", ""),
            history = history,
        )
    }

    fun parse(text: String): A2aTask = parse(JSONObject(text))
}
