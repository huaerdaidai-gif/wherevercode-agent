package wherevercode.agent.brain.siwc

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Loopback OAuth callback server.
 *
 * Binds a [ServerSocket] to 127.0.0.1 only (never 0.0.0.0), accepts exactly
 * ONE connection, parses `GET /auth/callback?code=...&state=...`, answers
 * with a minimal "you can close this tab" page, and closes immediately.
 *
 * Official contract (registration/sign-in guide):
 * - redirect URI is an http loopback callback on 127.0.0.1, path
 *   `/auth/callback`; only the port may vary; do NOT substitute `localhost`.
 * - The listener must be started BEFORE the browser is opened, and the exact
 *   same URI (including its port) must be sent in the authorization request
 *   and in the code exchange.
 *
 * Security:
 * - Only loopback remotes are accepted; a non-loopback connection is closed
 *   unread and reported as a transport failure.
 * - Only `GET /auth/callback` is accepted; anything else gets 404/405 and
 *   is reported as a transport failure (no code is ever accepted from an
 *   unexpected path or method).
 * - The server holds no tokens and logs nothing; it only returns the parsed
 *   query to the caller, which validates state before using the code.
 *
 * Threading: [awaitCallback] blocks the calling thread on `accept()`; call
 * it from `Dispatchers.IO` (see [SiwcAuthManager.completeSignIn]). Use
 * [stop] from any thread to unblock it.
 *
 * The class is `open` so tests can substitute a fake callback source
 * (the real server binds a socket, which some sandboxes block); production
 * always uses the real one.
 */
open class LoopbackCallbackServer(
    /** Port to bind, 0 for an ephemeral port (default). */
    private val requestedPort: Int = 0,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptedSocket: Socket? = null

    /** True after [start] until [stop]. */
    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    /** The port actually bound (only valid after [start]). */
    val port: Int
        get() = serverSocket?.localPort ?: -1

    /** The exact redirect URI to send in the authorization request. */
    val redirectUri: String
        get() = "http://127.0.0.1:$port${SiwcBrainAdapter.SIWC_CALLBACK_PATH}"

    /**
     * Bind to 127.0.0.1 and return the actual port. Must be called before
     * the authorization URL is built (the URL embeds this port).
     */
    @Synchronized
    open fun start(): Int {
        if (serverSocket != null) return port
        val socket = ServerSocket(requestedPort, 1, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        return socket.localPort
    }

    /**
     * Wait for the single callback (up to [timeoutMs]) and return the parsed
     * query. The server is stopped before returning, in all cases.
     *
     * @throws BrainError.Transport on timeout, on a non-loopback connection,
     *   on a wrong path/method, or on any socket failure.
     */
    open fun awaitCallback(timeoutMs: Long): LoopbackCallbackResult {
        val server = serverSocket
            ?: throw BrainError.Transport("回调服务器尚未启动。")
        server.soTimeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        try {
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                throw BrainError.Transport(
                    "等待 ChatGPT 登录回调超时（${timeoutMs / 1_000} 秒内未返回）。",
                    e,
                )
            }
            acceptedSocket = socket
            return handleConnection(socket)
        } finally {
            stop()
        }
    }

    /** Stop the server and close any accepted connection. Idempotent. */
    @Synchronized
    open fun stop() {
        try {
            acceptedSocket?.close()
        } catch (_: Exception) {
        }
        acceptedSocket = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleConnection(socket: Socket): LoopbackCallbackResult {
        if (!socket.inetAddress.isLoopbackAddress) {
            // Never read from, or answer, a non-loopback peer.
            try {
                socket.close()
            } catch (_: Exception) {
            }
            throw BrainError.Transport("拒绝了非本机的回调连接。")
        }
        socket.soTimeout = READ_TIMEOUT_MS
        val reader = BufferedReader(
            InputStreamReader(socket.getInputStream(), Charsets.UTF_8),
        )
        val requestLine = reader.readLine()
            ?: throw BrainError.Transport("回调连接没有发送请求行。")
        val requestParts = requestLine.split(" ")
        if (requestParts.size < 2 || requestParts[0] != "GET") {
            sendPlain(socket, 405, "Method Not Allowed")
            throw BrainError.Transport("回调请求不是 GET。")
        }
        val rawTarget = requestParts[1]
        val path = rawTarget.substringBefore("?")
        if (path != SiwcBrainAdapter.SIWC_CALLBACK_PATH) {
            sendPlain(socket, 404, "Not Found")
            throw BrainError.Transport("回调路径不是 /auth/callback。")
        }
        // Drain the request headers (until the blank line) so the browser
        // sees a complete response; header contents are never used.
        try {
            var line: String?
            do {
                line = reader.readLine()
            } while (line != null && line.isNotEmpty())
        } catch (_: Exception) {
            // A truncated header block is not fatal: the query is already parsed.
        }
        val query = parseQuery(rawTarget.substringAfter("?", ""))
        // 回调带 error（用户拒绝 / 授权失败）时不冒充“登录完成”：浏览器
        // 看到失败页，App 侧状态行会显示完整原因。
        if (query["error"] != null) {
            sendCallbackErrorPage(socket, query["error"]!!)
        } else {
            sendCallbackPage(socket)
        }
        return LoopbackCallbackResult(
            code = query["code"],
            state = query["state"],
            clientId = query["client_id"],
            scope = query["scope"],
            error = query["error"],
            errorDescription = query["error_description"],
            rawQuery = query,
        )
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore("=")
            val value = pair.substringAfter("=", "")
            out[urlDecode(key)] = urlDecode(value)
        }
        return out
    }

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, "UTF-8")

    private fun sendCallbackPage(socket: Socket) {
        val body = """
            <!doctype html>
            <html><head><meta charset="utf-8">
            <title>WhereverCode</title></head>
            <body><h1>登录完成</h1>
            <p>已返回 WhereverCode，可以关闭这个标签页。</p>
            </body></html>
        """.trimIndent()
        sendHtml(socket, 200, body)
    }

    /** 回调带 error 时的页面：错误码不是秘密，直接显示；不冒充成功。 */
    private fun sendCallbackErrorPage(socket: Socket, error: String) {
        val body = """
            <!doctype html>
            <html><head><meta charset="utf-8">
            <title>WhereverCode</title></head>
            <body><h1>登录失败</h1>
            <p>Google 拒绝了登录（$error）。请回 App 查看原因。</p>
            </body></html>
        """.trimIndent()
        sendHtml(socket, 200, body)
    }

    private fun sendPlain(socket: Socket, status: Int, message: String) {
        sendHtml(socket, status, message)
    }

    private fun sendHtml(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "OK"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        try {
            socket.getOutputStream().use { out ->
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(bytes)
                out.flush()
            }
        } catch (_: Exception) {
            // The browser may have gone away; the parsed result still stands.
        }
    }

    companion object {
        /** Read timeout for one accepted callback connection. */
        const val READ_TIMEOUT_MS = 15_000
    }
}

/**
 * Parsed loopback callback. The manager validates [state] and [error]
 * before using [code]; a non-null [error] (e.g. `access_denied`) means the
 * user denied or the authorization failed — no code may be exchanged.
 */
data class LoopbackCallbackResult(
    val code: String?,
    val state: String?,
    /** Issued client id (present on a new registration; may be omitted on reauth). */
    val clientId: String?,
    val scope: String?,
    val error: String?,
    val errorDescription: String?,
    val rawQuery: Map<String, String>,
)
