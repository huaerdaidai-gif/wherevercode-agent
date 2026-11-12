package wherevercode.agent.core.dispatch

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP JSON-RPC 2.0 处理（与传输无关）：initialize / ping / tools/list /
 * tools/call，以及 通知类消息（notifications，无响应）。工具级失败按 MCP 规范
 * 回 `isError: true` 的结果（structuredContent 带稳定 error_code），
 * 协议级错误回 JSON-RPC error。
 */
class McpDispatchProtocol(
    private val service: McpDispatchService,
    private val serverName: String = SERVER_NAME,
    private val serverVersion: String = SERVER_VERSION,
) {
    /** 处理一条已鉴权的消息；通知返回 null（HTTP 层回 202）。 */
    fun handle(message: Any?, principal: DispatchPrincipal): JSONObject? {
        if (message is JSONArray) {
            return error(JSONObject.NULL, INVALID_REQUEST, "不支持 JSON-RPC 批量请求")
        }
        if (message !is JSONObject) return error(JSONObject.NULL, INVALID_REQUEST, "请求须为 JSON 对象")
        val id = if (message.has("id")) message.opt("id") else null
        if (message.optString("jsonrpc") != "2.0") return error(id ?: JSONObject.NULL, INVALID_REQUEST, "jsonrpc 须为 2.0")
        val method = message.optString("method", "")
        if (method.isEmpty()) {
            // 客户端回来的 response（不该出现）：忽略。
            return null
        }
        if (id == null) return null // notification（如 notifications/initialized）
        val params = message.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> ok(id, initializeResult(params))
            "ping" -> ok(id, JSONObject())
            "tools/list" -> ok(id, JSONObject().put("tools", JSONArray().apply {
                service.toolsFor(principal).forEach { t ->
                    put(JSONObject().put("name", t.name).put("description", t.description).put("inputSchema", t.inputSchema))
                }
            }))
            "tools/call" -> toolsCall(id, params, principal)
            else -> error(id, METHOD_NOT_FOUND, "未知方法：${method.take(64)}")
        }
    }

    private fun initializeResult(params: JSONObject): JSONObject {
        val requested = params.optString("protocolVersion", "")
        val version = if (requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first()
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
            .put("serverInfo", JSONObject().put("name", serverName).put("version", serverVersion))
            .put(
                "instructions",
                "WhereverCode App 独立派工 MCP：任务落在 App 本机 TaskStore。派工方用 create_task/assign_task/" +
                    "pause_task/resume_task/stop_task/change_goal/get_task_status；执行方用 list_my_tasks/claim_task/" +
                    "report_progress/submit_result。改状态的调用请带 idempotency_key，重试用同一个键。",
            )
    }

    private fun toolsCall(id: Any, params: JSONObject, principal: DispatchPrincipal): JSONObject {
        val name = params.optString("name", "")
        if (name.isEmpty()) return error(id, INVALID_PARAMS, "缺少工具名")
        val rawArgs = params.opt("arguments")
        val args = when (rawArgs) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> rawArgs
            else -> return error(id, INVALID_PARAMS, "arguments 须为对象")
        }
        return try {
            val result = service.call(principal, name, args)
            ok(id, JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.toString())))
                .put("structuredContent", result)
                .put("isError", false))
        } catch (e: UnknownToolException) {
            error(id, INVALID_PARAMS, "未知工具：${name.take(64)}")
        } catch (e: DispatchException) {
            val body = JSONObject().put("ok", false).put("error_code", e.code).put("message", e.message ?: "")
            if (e.data != null) body.put("data", e.data)
            ok(id, JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", body.toString())))
                .put("structuredContent", body)
                .put("isError", true))
        }
    }

    companion object {
        const val SERVER_NAME = "wherevercode-dispatch"
        const val SERVER_VERSION = "0.1.0"
        val SUPPORTED_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val UNAUTHORIZED = -32001

        fun ok(id: Any, result: JSONObject): JSONObject =
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

        fun error(id: Any, code: Int, message: String): JSONObject =
            JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message))
    }
}
