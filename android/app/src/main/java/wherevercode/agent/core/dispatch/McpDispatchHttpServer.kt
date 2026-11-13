package wherevercode.agent.core.dispatch

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONTokener
import wherevercode.agent.core.mcp.McpGuard

/**
 * MCP Streamable HTTP 传输（最小实现，纯 java.net，Android 与 JVM 通用；
 * Android 没有 com.sun.net.httpserver，做法与 LoopbackCallbackServer 一致）。
 *
 * - 只接 `POST <path>`，请求体为一条 JSON-RPC 消息，响应为
 *   `application/json`（规范允许服务端对单请求直接回 JSON 而不开 SSE）。
 *   通知回 202。`GET` 回 405（本服务不推送服务端消息）。
 * - 鉴权：每个请求必须带 `Authorization: Bearer wcd_…`；不过回 401 +
 *   `WWW-Authenticate: Bearer`，并记审计（不带任何令牌片段）。
 * - 防 DNS rebinding：带 `Origin` 头且不在白名单里的请求回 403。
 * - 限流：复用 [McpGuard.RateLimiter]（每主体每分钟 60 次）→ 429。
 * - 体积：复用 [McpGuard.MAX_BODY_BYTES]；超出回 413，chunked 回 411。
 * - 慢连接：socket 读超时（默认 10 秒）后丢弃连接，不影响其他请求。
 * - 默认只绑 127.0.0.1。公网访问走反向隧道/中继（见 docs/mcp-dispatch-20261113.md），
 *   不在手机上直接开 0.0.0.0。
 */
class McpDispatchHttpServer(
    private val service: McpDispatchService,
    private val bindHost: String = "127.0.0.1",
    private val requestedPort: Int = 0,
    val path: String = DEFAULT_PATH,
    private val allowedOrigins: Set<String> = emptySet(),
    private val readTimeoutMillis: Int = 10_000,
    private val rateLimiter: McpGuard.RateLimiter = McpGuard.RateLimiter(),
    private val workerThreads: Int = 4,
    /** 测试钩子：每次 tools/call 处理前执行（模拟慢工具 / 超时）。 */
    private val beforeHandle: (() -> Unit)? = null,
) {
    private val protocol = McpDispatchProtocol(service)
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null

    val port: Int get() = serverSocket?.localPort ?: -1

    val url: String get() = "http://$bindHost:$port$path"

    @Synchronized
    fun start(): McpDispatchHttpServer {
        check(serverSocket == null) { "已启动" }
        val socket = ServerSocket(requestedPort, 50, InetAddress.getByName(bindHost))
        serverSocket = socket
        val workers = Executors.newFixedThreadPool(workerThreads) { r ->
            Thread(r, "mcp-dispatch-worker").apply { isDaemon = true }
        }
        pool = workers
        acceptThread = Thread({
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: SocketException) { break }
                try {
                    workers.execute { serve(client) }
                } catch (_: Exception) {
                    runCatching { client.close() }
                }
            }
        }, "mcp-dispatch-accept").apply { isDaemon = true; start() }
        return this
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        pool?.shutdownNow()
        pool?.awaitTermination(2, TimeUnit.SECONDS)
        pool = null
        acceptThread = null
    }

    private fun serve(client: Socket) {
        client.use { s ->
            try {
                s.soTimeout = readTimeoutMillis
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val request = try {
                    readRequest(input)
                } catch (e: BodyRejected) {
                    respond(out, jsonError(e.status, if (e.status == 413) "payload too large" else "length required"))
                    return
                } ?: return
                respond(out, handle(request))
            } catch (_: SocketTimeoutException) {
                // 慢/断开的客户端：直接丢弃，不处理半截请求。
            } catch (_: Exception) {
                // 客户端中途断开等：丢弃，服务继续。
            }
        }
    }

    data class HttpRequest(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray?)

    data class HttpResponse(
        val status: Int,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
    )

    internal fun handle(req: HttpRequest): HttpResponse {
        val target = req.target.substringBefore('?')
        if (target != path) return jsonError(404, "not found")
        val origin = req.headers["origin"]
        if (origin != null && origin !in allowedOrigins) {
            service.auditRejected("anonymous", "mcp_dispatch.http", "origin_forbidden")
            return jsonError(403, "origin not allowed")
        }
        if (req.method == "GET") return HttpResponse(405, "", mapOf("Allow" to "POST"))
        if (req.method == "DELETE") return HttpResponse(405, "", mapOf("Allow" to "POST"))
        if (req.method != "POST") return HttpResponse(405, "", mapOf("Allow" to "POST"))
        val auth = req.headers["authorization"] ?: ""
        val token = if (auth.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) auth.substring(7).trim() else null
        val principal = service.tokens.authenticate(token)
        if (principal == null) {
            service.auditRejected("anonymous", "mcp_dispatch.auth", if (token == null) "missing_token" else "invalid_token")
            return HttpResponse(
                401,
                McpDispatchProtocol.error(JSONObject.NULL, McpDispatchProtocol.UNAUTHORIZED, "unauthorized").toString(),
                mapOf("WWW-Authenticate" to "Bearer realm=\"wherevercode-dispatch\"", "Content-Type" to "application/json"),
            )
        }
        when (val rl = rateLimiter.check(principal.auditId)) {
            is McpGuard.RateLimitResult.Limited -> {
                service.auditRejected(principal.auditId, "mcp_dispatch.http", "rate_limited")
                return HttpResponse(
                    429,
                    McpDispatchProtocol.error(JSONObject.NULL, -32002, McpGuard.rateLimitMessage(rl.retryAfterSeconds)).toString(),
                    mapOf("Retry-After" to rl.retryAfterSeconds.toString(), "Content-Type" to "application/json"),
                )
            }
            else -> Unit
        }
        val body = req.body ?: return jsonError(400, "missing body")
        val parsed = try {
            JSONTokener(String(body, Charsets.UTF_8)).nextValue()
        } catch (_: Exception) {
            return HttpResponse(
                400,
                McpDispatchProtocol.error(JSONObject.NULL, McpDispatchProtocol.PARSE_ERROR, "parse error").toString(),
                mapOf("Content-Type" to "application/json"),
            )
        }
        val isInitialize = (parsed as? JSONObject)?.optString("method") == "initialize"
        if ((parsed as? JSONObject)?.optString("method") == "tools/call") beforeHandle?.invoke()
        val response = protocol.handle(parsed, principal) ?: return HttpResponse(202)
        val headers = mutableMapOf("Content-Type" to "application/json")
        if (isInitialize) headers["Mcp-Session-Id"] = UUID.randomUUID().toString()
        return HttpResponse(200, response.toString(), headers)
    }

    private fun jsonError(status: Int, message: String) =
        HttpResponse(status, JSONObject().put("error", message).toString(), mapOf("Content-Type" to "application/json"))

    /** 读一条 HTTP/1.1 请求；格式错误/超限直接回错误并返回 null。 */
    private fun readRequest(input: InputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            headerBytes.write(b)
            matched = if (b.toByte() == terminator[matched]) matched + 1 else if (b.toByte() == terminator[0]) 1 else 0
            if (headerBytes.size() > MAX_HEADER_BYTES) return null
        }
        val lines = headerBytes.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val parts = lines[0].split(" ")
        if (parts.size < 3) return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1).take(MAX_HEADERS)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        var body: ByteArray? = null
        if (headers["transfer-encoding"] != null) {
            throw BodyRejected(411)
        }
        val lengthHeader = headers["content-length"]
        if (lengthHeader != null) {
            val length = lengthHeader.toIntOrNull() ?: return null
            if (length < 0) return null
            if (length > McpGuard.MAX_BODY_BYTES) throw BodyRejected(413)
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) return null // 客户端中途断开：半截请求不处理
                read += n
            }
            body = buf
        }
        return HttpRequest(parts[0].uppercase(), parts[1], headers, body)
    }

    private class BodyRejected(val status: Int) : Exception()

    private fun respond(out: OutputStream, resp: HttpResponse) {
        val bodyBytes = resp.body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(resp.status).append(' ').append(reason(resp.status)).append("\r\n")
        resp.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bodyBytes)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 411 -> "Length Required"; 413 -> "Payload Too Large"
        429 -> "Too Many Requests"; else -> "Error"
    }

    companion object {
        const val DEFAULT_PATH = "/mcp"
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_HEADERS = 64
    }
}
