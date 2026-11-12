package wherevercode.agent.core.extension.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP JSON-RPC 2.0 的最小消息层（自主实现，不引 MCP SDK）。
 *
 * 实现的协议面（MCP 2025-11-25，见连接器设计报告与 SPEC §2.1）：
 * - 请求：{"jsonrpc":"2.0","id":<n>,"method":...,"params":{...}}
 * - 通知：同上但无 id，服务端不回包（notifications/initialized；
 *   服务端 → 客户端的 notifications/… 也是这个形状）。
 * - 回包：{"jsonrpc":"2.0","id":<n>,"result":{...}} 或
 *   {"jsonrpc":"2.0","id":<n>,"error":{"code":<n>,"message":...}}。
 *
 * 服务端通知（无 id、有 method，如 notifications/tools/list_changed、
 * notifications/resources/updated）不再直接跳过：解析成
 * [Response.Notification]，由传输层交给客户端的通知回调。
 *
 * 服务端请求（有 id、有 method，如 roots/list）：解析成
 * [Response.ServerRequest]，由客户端应答（roots/list 回本机
 * 暴露的根目录；未知方法回 -32601）。2025-11-25 的客户端必须能
 * 回这种请求，否则声明了 roots 能力的服务器会在握手后卡住。
 *
 * 注意：Hand 的 tools/call 对未知工具不走 JSON-RPC error，而是走
 * result.isError=true（2026-11-11 VPS 实测），见 [McpClient]。
 * 另：不是所有服务器都实现全部原语——未声明 capabilities 的原语
 * （如 filesystem 服务器的 prompts）调了会回 -32601，调用方按
 * “该原语不存在”处理，不当致命错误（见 McpClient 的能力门）。
 */
object McpJsonRpc {

    /** 服务端通知（无 id、有 method）：目录变化、资源更新、进度。 */
    data class McpNotification(val method: String, val params: JSONObject?)

    fun request(id: Long, method: String, params: JSONObject?): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

    fun notification(method: String, params: JSONObject?): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }

    /** 对服务端请求（roots/list 等）的成功应答：带同 id 的 result。 */
    fun response(id: Long, result: JSONObject): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    /** 对服务端请求的错误应答：带同 id 的 error（未知方法用 -32601）。 */
    fun errorResponse(id: Long, code: Int, message: String): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", JSONObject().apply {
                put("code", code)
                put("message", message)
            })
        }

    sealed class Response {
        /** 成功回包（result 可能是 JSONObject，也可能是其它 JSON 值）。 */
        data class Result(val id: Long?, val result: Any?) : Response()

        /** JSON-RPC 层错误（方法不存在 -32601、解析失败 -32700 等）。 */
        data class Error(val id: Long?, val code: Int, val message: String) : Response()

        /** 服务端通知（无 id、有 method）：目录变化、资源更新、进度。 */
        data class Notification(val notification: McpNotification) : Response()

        /**
         * 服务端请求（有 id、有 method，如 roots/list）：客户端必须
         * 应答（result 或 error），不能只丢弃，否则服务端会一直等。
         */
        data class ServerRequest(val id: Long?, val method: String, val params: JSONObject?) : Response()

        /** 无法识别的行：调用方跳过，不当错误。 */
        data object Ignored : Response()
    }

    /** 解析一行回包。解析失败（不是 JSON）时按 Ignored 处理，由传输层决定是否继续等。 */
    fun parseResponse(line: String): Response {
        val obj = try {
            JSONObject(line)
        } catch (_: Exception) {
            return Response.Ignored
        }
        if (obj.optString("jsonrpc", "") != "2.0") return Response.Ignored
        if (!obj.has("id")) {
            // 无 id：有 method 就是服务端通知，否则无法识别。
            val method = obj.optString("method", "")
            if (method.isBlank()) return Response.Ignored
            return Response.Notification(McpNotification(method, obj.optJSONObject("params")))
        }
        val id = if (obj.isNull("id")) null else obj.optLong("id", -1).takeIf { it >= 0 }
        if (obj.has("method")) {
            // 有 id、有 method：服务端发来的请求（roots/list 等），
            // 不是回包也不是通知——调用方必须应答。
            val method = obj.optString("method", "")
            if (method.isBlank()) return Response.Ignored
            return Response.ServerRequest(id, method, obj.optJSONObject("params"))
        }
        if (!obj.has("error") && !obj.has("result")) return Response.Ignored
        if (obj.has("error")) {
            val err = obj.optJSONObject("error") ?: return Response.Ignored
            return Response.Error(id, err.optInt("code", -32603), err.optString("message", "未知错误"))
        }
        return Response.Result(id, obj.opt("result"))
    }

    /** JSONObject → Map（递归；JSONObject.NULL 变 null），供 ToolDef.inputSchema 用。 */
    fun JSONObject.toMap(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = fromJsonValue(opt(key))
        }
        return out
    }

    fun JSONArray.toList(): List<Any?> {
        val out = ArrayList<Any?>(length())
        for (i in 0 until length()) out += fromJsonValue(opt(i))
        return out
    }

    fun fromJsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.toMap()
        is JSONArray -> value.toList()
        else -> value
    }

    /** Map → JSONObject（递归；null 变 JSONObject.NULL），供 tools/call 参数用。 */
    fun mapToJson(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((key, value) in map) obj.put(key, toJsonValue(value))
        return obj
    }

    private fun toJsonValue(value: Any?): Any? = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            mapToJson(value as Map<String, Any?>)
        }
        is List<*> -> JSONArray().apply {
            for (item in value) put(toJsonValue(item))
        }
        else -> value
    }
}
