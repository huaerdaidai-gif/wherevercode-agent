package wherevercode.agent.core.a2a

import java.io.IOException
import java.util.UUID
import org.json.JSONObject
import wherevercode.agent.core.agent.PlatformAgent
import wherevercode.agent.core.agent.PlatformHttpClient
import wherevercode.agent.core.agent.UrlConnectionPlatformHttpClient

/**
 * A2A 客户端（最小形态）：对 V5 平台端点的三个 JSON-RPC 2.0 方法。
 *
 * - `fetchCard`：取对方的 Agent 卡（公开文档，不用令牌）。
 * - `sendMessage`：给一个已注册 Agent 发任务消息（`message/send`）。
 * - `getTask`：查一个任务的状态（`tasks/get`）。
 * - `resubscribe`：重新订阅任务进度流（`tasks/resubscribe`），返回
 *   快照 + WS 路径；流本身走端点 WS（见 [A2aResubscribe.streamPath]）。
 *
 * 任务语义复用平台队列（版本化任务包、租约、幂等），不另造一套；
 * 认证复用现有 Bearer（v5_ 令牌），令牌只进 Authorization 请求头，
 * 永不进请求体、日志与错误消息。
 *
 * 方法都是同步阻塞调用（底层是 [PlatformHttpClient]），调用方自己
 * 放后台线程。没有 UI：发现与调用是给大脑/调度器用的，不是给人点的。
 */
class A2aClient(
    private val tokenProvider: () -> String?,
    private val httpClient: PlatformHttpClient = UrlConnectionPlatformHttpClient(),
    private val endpoint: String = a2aEndpoint(null),
    private val cardUrl: String = agentCardUrl(null),
) {
    companion object {
        /** A2A 端点路径（固定后缀；主机由平台地址字段定，见 [a2aEndpoint]）。 */
        const val A2A_PATH: String = "/api/open/v1/a2a"

        /** Agent 卡路径（公开文档，见 [agentCardUrl]）。 */
        const val CARD_PATH: String = "/.well-known/agent.json"

        /** 任务进度流路径（固定后缀；接在卡片 `url` 的主机后面）。 */
        const val TASK_WS_PATH: String = "/api/open/v1/tasks/ws"

        /**
         * A2A 端点（与 [PlatformAgent.mcpEndpoint] 同一主机规则；未配置时返回空串）。
         */
        fun a2aEndpoint(platformHost: String?): String {
            val host = platformHost?.trim().takeIf { !it.isNullOrBlank() }
                ?: PlatformAgent.DEFAULT_PLATFORM_HOST
            return host.takeIf { it.isNotBlank() }?.let { "https://$it$A2A_PATH" }.orEmpty()
        }

        /** Agent 卡地址（与 [a2aEndpoint] 同一主机规则）。 */
        fun agentCardUrl(platformHost: String?): String {
            val host = platformHost?.trim().takeIf { !it.isNullOrBlank() }
                ?: PlatformAgent.DEFAULT_PLATFORM_HOST
            return host.takeIf { it.isNotBlank() }?.let { "https://$it$CARD_PATH" }.orEmpty()
        }
    }

    /** 取对方的 Agent 卡：公开文档，不用令牌。 */
    @Throws(IOException::class)
    fun fetchCard(): AgentCard {
        val raw = httpClient.getJson(cardUrl, null)
        return try {
            AgentCardParser.parse(raw)
        } catch (e: IllegalArgumentException) {
            throw IOException("对方 Agent 卡无法解析：${e.message}", e)
        } catch (_: Exception) {
            throw IOException("对方返回了无法解析的 Agent 卡。")
        }
    }

    /**
     * 给 [assigneeId] 发任务消息：goal 是任务目标正文；
     * [idempotencyKey] 相同两次调用只建一个任务（平台按 key 去重）。
     */
    @Throws(IOException::class)
    fun sendMessage(
        assigneeId: String,
        goal: String,
        projectId: String? = null,
        title: String? = null,
        idempotencyKey: String? = null,
    ): A2aTask {
        require(assigneeId.isNotBlank()) { "assigneeId 不能为空" }
        require(goal.isNotBlank()) { "goal 不能为空" }
        val metadata = JSONObject().apply {
            put("assignee", assigneeId)
            if (!projectId.isNullOrBlank()) put("projectId", projectId)
            if (!title.isNullOrBlank()) put("title", title)
            if (!idempotencyKey.isNullOrBlank()) put("idempotencyKey", idempotencyKey)
        }
        val params = JSONObject().apply {
            put("message", JSONObject().apply {
                put("role", "user")
                put("parts", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("kind", "text")
                        put("text", goal)
                    })
                })
            })
            put("metadata", metadata)
        }
        return A2aTaskParser.parse(call("message/send", params))
    }

    /** 查一个任务的状态；[historyLength] > 0 时附带最近若干条进度事件。 */
    @Throws(IOException::class)
    fun getTask(taskId: String, historyLength: Int = 0): A2aTask {
        require(taskId.isNotBlank()) { "taskId 不能为空" }
        val params = JSONObject().apply {
            put("id", taskId)
            if (historyLength > 0) put("historyLength", historyLength)
        }
        return A2aTaskParser.parse(call("tasks/get", params))
    }

    /**
     * 重新订阅任务进度流：返回当前快照 + WS 路径。断线后先调
     * [getTask] 补快照，再连 WS——流地址是卡片 `url` 的主机 +
     * [streamPath] + `?task_id=<id>`（Bearer + Upgrade: websocket）。
     */
    @Throws(IOException::class)
    fun resubscribe(taskId: String): A2aResubscribe {
        require(taskId.isNotBlank()) { "taskId 不能为空" }
        val result = call("tasks/resubscribe", JSONObject().apply {
            put("id", taskId)
        })
        return A2aResubscribe(
            task = A2aTaskParser.parse(result.optJSONObject("task") ?: JSONObject()),
            streamPath = result.optString("streamPath", TASK_WS_PATH),
            note = result.optString("note", ""),
        )
    }

    /**
     * 一次 A2A JSON-RPC 调用，返回 `result`。传输失败、平台报错或
     * 响应无法解析时抛 [IOException]，消息里绝不带令牌。
     */
    @Throws(IOException::class)
    private fun call(method: String, params: JSONObject): JSONObject {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw IOException("未配置平台 Agent 凭证。")
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", UUID.randomUUID().toString())
            put("method", method)
            put("params", params)
        }.toString()
        val raw = httpClient.postJson(endpoint, token, body)
        val envelope = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw IOException("平台返回了无法解析的响应。")
        }
        val error = envelope.optJSONObject("error")
        if (error != null) {
            val detail = error.optString("message", "").takeIf { it.isNotBlank() }
                ?: "平台调用失败"
            throw IOException(detail.take(300))
        }
        return envelope.optJSONObject("result") ?: JSONObject()
    }
}

/** `tasks/resubscribe` 的返回：快照 + 进度流地址。 */
data class A2aResubscribe(
    val task: A2aTask,
    val streamPath: String,
    val note: String,
)
