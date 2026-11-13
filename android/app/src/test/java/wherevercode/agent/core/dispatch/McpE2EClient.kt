package wherevercode.agent.core.dispatch

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONObject

/** E2E 证据日志：写到 $MCP_DISPATCH_E2E_LOG_DIR（默认 build/mcp-dispatch-e2e）。令牌一律打码。 */
class E2ELog(name: String) {
    val file: File = run {
        val dir = File(System.getenv("MCP_DISPATCH_E2E_LOG_DIR") ?: "build/mcp-dispatch-e2e")
        dir.mkdirs()
        File(dir, "mcp-dispatch-e2e-$name.log").also { it.writeText("") }
    }
    private val secrets = mutableListOf<String>()

    fun hide(secret: String) { secrets += secret }

    fun line(text: String) {
        var safe = text
        secrets.forEach { safe = safe.replace(it, "wcd_[REDACTED]") }
        file.appendText("[${Instant.now()}] $safe\n")
    }
}

data class HttpResult(val status: Int, val body: String, val headers: Map<String, String>) {
    fun json(): JSONObject = JSONObject(body)
}

/** 真实 HTTP 的 MCP 客户端（HttpURLConnection），每次请求/响应写证据日志。 */
class McpE2EClient(
    private val url: String,
    private val token: String?,
    private val log: E2ELog,
    private val who: String,
    private val readTimeoutMs: Int = 10_000,
) {
    private var nextId = 1

    init { token?.let { log.hide(it) } }

    fun post(body: String, extraHeaders: Map<String, String> = emptyMap(), method: String = "POST"): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        conn.setRequestProperty("MCP-Protocol-Version", "2025-06-18")
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (method == "POST") {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        log.line("$who -> $method $url ${if (method == "POST") body.take(1500) + (if (body.length > 1500) "…(${body.length} bytes)" else "") else ""}")
        val status = conn.responseCode
        val stream = if (status >= 400) conn.errorStream else conn.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        val headers = conn.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") }
        log.line("$who <- HTTP $status $text")
        conn.disconnect()
        return HttpResult(status, text, headers)
    }

    fun rpc(method: String, params: JSONObject? = null): JSONObject {
        val req = JSONObject().put("jsonrpc", "2.0").put("id", nextId++).put("method", method)
        if (params != null) req.put("params", params)
        val r = post(req.toString())
        check(r.status == 200) { "HTTP ${r.status}: ${r.body}" }
        return r.json()
    }

    /** tools/call → structuredContent（isError 时也返回，由调用方断言）。 */
    fun tool(name: String, arguments: JSONObject = JSONObject()): JSONObject {
        val resp = rpc("tools/call", JSONObject().put("name", name).put("arguments", arguments))
        val result = resp.getJSONObject("result")
        return result.getJSONObject("structuredContent").put("_isError", result.getBoolean("isError"))
    }

    fun initialize(): JSONObject {
        val r = rpc("initialize", JSONObject()
            .put("protocolVersion", "2025-06-18")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", who).put("version", "e2e")))
        val note = post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString())
        check(note.status == 202) { "initialized notification should be 202, got ${note.status}" }
        return r
    }
}
