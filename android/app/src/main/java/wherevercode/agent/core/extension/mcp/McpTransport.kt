package wherevercode.agent.core.extension.mcp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wherevercode.agent.core.extension.mcp.McpJsonRpc.McpNotification
import wherevercode.agent.core.extension.mcp.McpJsonRpc.parseResponse

/**
 * MCP 传输层（自主实现，三种传输；协议面 MCP 2025-11-25）。
 *
 * 传输只负责“发一条 JSON-RPC、等一条带同 id 的回包”，外加“把服务端
 * 通知交给监听器”；协议语义（initialize / tools / resources /
 * prompts）在 [McpClient]。
 *
 * - [StdioMcpTransport]：本地子进程，stdin/stdout 各一行一条 JSON
 *   （NDJSON，与 Hand mcp_serve() 同形）。分发线程持续读 stdout：
 *   带 id 的回包按 id 交给等它的请求，无 id 的服务端通知交给
 *   [McpTransport.setNotificationListener] 登记的回调（请求在等回包
 *   时路过的通知也一样分发，不丢）。
 * - [HttpMcpTransport]：Streamable HTTP（2025-03-26/2025-11-25）。
 *   POST 一条 JSON-RPC；服务端在 initialize 回包头里给
 *   `Mcp-Session-Id` 时进入会话模式：后续请求都带该头，并另开一
 *   条 GET SSE 流收服务端主动发的消息（通知、以及可能走 GET 流回
 *   来的回包）；断流按 `Last-Event-ID` 重连（最多 3 次）；会话过期
 *   （POST 回 404）报 `mcp_session_expired`，由 [McpClient] 重新
 *   initialize 后重试一次。服务端不给会话 id 时退化成无状态 POST
 *   （与上一版行为一致）。
 * - [SseMcpTransport]：老版 SSE 会话模式（MCP 2024-11-05，仍有
 *   旧服务器在用，属兼容层）：GET `<url>/sse`（url 即 SSE 端点）
 *   开流，首个 `endpoint` 事件给 POST 地址（相对地址按 SSE 地址
 *   解析），之后 `message` 事件里的 JSON-RPC 回包/通知/服务端请求
 *   都走这条流；请求与应答都 POST 到 endpoint 地址（回 202）。
 *   流断了在会话期内重开（最多 3 次，退避 1/2/4 秒）；重开也失败
 *   时后续请求报 `mcp_connect_failed`，由管理器断线重连接手。
 *
 *
 * 认证：HTTP 传输可接 [McpAuthProvider]（见 McpAuth.kt）。每次请求
 * 前问它拿请求头；回 401 时问它处理（刷新/换 token），处理成功则
 * 重试一次。没接认证提供方时 401 就是 `mcp_http_error`。
 */
class McpException(message: String, val code: String = "mcp_error") : Exception(message)

interface McpTransport {
    suspend fun start()
    /**
     * 发一条请求，等同 id 的回包，返回 result 对象（非 JSONObject 时
     * 原样返回，由调用方按方法解释）。JSON-RPC error 与超时抛
     * [McpException]。服务端通知不经过这里，见 [setNotificationListener]。
     */
    suspend fun request(method: String, params: JSONObject?, timeoutMillis: Long = 60_000): Any?
    suspend fun notify(method: String, params: JSONObject?)
    suspend fun close()

    /** 登记服务端通知回调（可随时换/清掉）。传输保证通知不丢给请求等待。 */
    fun setNotificationListener(listener: ((McpNotification) -> Unit)?)

    /**
     * 登记服务端请求回调（roots/list 等有 id、有 method 的请求）。
     * 客户端收到后必须应答（见 [respond]/[respondError]），不能只丢弃。
     */
    fun setServerRequestListener(listener: ((McpJsonRpc.Response.ServerRequest) -> Unit)?)

    /**
     * 应答一条服务端请求（带同 id 的 result）。POST 型传输（Streamable
     * HTTP / SSE）发一次 POST；stdio 写一行。失败抛 [McpException]。
     */
    suspend fun respond(id: Long, result: JSONObject)

    /** 应答一条服务端请求为错误（未知方法用 -32601）。 */
    suspend fun respondError(id: Long, code: Int, message: String)
}

class StdioMcpTransport(
    private val command: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workingDir: String? = null,
) : McpTransport {
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var readerThread: Thread? = null
    private var dispatcherThread: Thread? = null
    private val incoming = LinkedBlockingQueue<String>()
    private val waiters = ConcurrentHashMap<Long, LinkedBlockingQueue<McpJsonRpc.Response>>()
    private val nextId = AtomicLong(1)

    @Volatile
    private var notificationListener: ((McpNotification) -> Unit)? = null

    @Volatile
    private var serverRequestListener: ((McpJsonRpc.Response.ServerRequest) -> Unit)? = null

    override fun setNotificationListener(listener: ((McpNotification) -> Unit)?) {
        notificationListener = listener
    }

    override fun setServerRequestListener(listener: ((McpJsonRpc.Response.ServerRequest) -> Unit)?) {
        serverRequestListener = listener
    }

    /** stdio 应答服务端请求：写一行带同 id 的 result（无须等回包）。 */
    override suspend fun respond(id: Long, result: JSONObject) {
        withContext(Dispatchers.IO) {
            val proc = process ?: throw McpException("MCP 传输未启动", "mcp_not_started")
            if (!proc.isAlive) throw McpException("MCP 服务器进程已退出", "mcp_exited")
            try {
                writer?.apply {
                    write(McpJsonRpc.response(id, result).toString())
                    write("\n")
                    flush()
                } ?: throw McpException("MCP 传输未启动", "mcp_not_started")
            } catch (e: McpException) {
                throw e
            } catch (e: Exception) {
                throw McpException("发送 MCP 应答失败：${e.message ?: "未知错误"}", "mcp_send_failed")
            }
        }
    }

    override suspend fun respondError(id: Long, code: Int, message: String) {
        withContext(Dispatchers.IO) {
        val proc = process ?: throw McpException("MCP 传输未启动", "mcp_not_started")
        if (!proc.isAlive) throw McpException("MCP 服务器进程已退出", "mcp_exited")
        try {
            writer?.apply {
                write(McpJsonRpc.errorResponse(id, code, message).toString())
                write("\n")
                flush()
            } ?: throw McpException("MCP 传输未启动", "mcp_not_started")
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            throw McpException("发送 MCP 应答失败：${e.message ?: "未知错误"}", "mcp_send_failed")
        }
        }
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext
        val builder = ProcessBuilder(command).redirectErrorStream(false)
        if (env.isNotEmpty()) builder.environment().putAll(env)
        if (!workingDir.isNullOrBlank()) builder.directory(java.io.File(workingDir))
        val proc = try {
            builder.start()
        } catch (e: Exception) {
            throw McpException("启动 MCP 服务器失败：${command.firstOrNull() ?: "?"}（${e.message ?: "未知错误"}）", "mcp_start_failed")
        }
        process = proc
        writer = OutputStreamWriter(proc.outputStream, Charsets.UTF_8)
        val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
        val rThread = Thread({
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) incoming.offer(line)
                }
            } catch (_: Exception) {
                // 进程退出/流关闭：队列里已有内容仍可消费，新的请求会超时。
            }
        }, "mcp-stdio-reader").apply { isDaemon = true }
        rThread.start()
        readerThread = rThread
        // 分发线程：回包按 id 交给等它的请求，通知交给监听器。
        val dThread = Thread({
            while (true) {
                val line = try {
                    incoming.take()
                } catch (_: InterruptedException) {
                    break
                }
                when (val resp = parseResponse(line)) {
                    is McpJsonRpc.Response.Result, is McpJsonRpc.Response.Error -> {
                        val id = when (resp) {
                            is McpJsonRpc.Response.Result -> resp.id
                            is McpJsonRpc.Response.Error -> resp.id
                            else -> null
                        }
                        if (id != null) {
                            waiters[id]?.offer(resp)
                        }
                        // 无 id 的回包（不应出现）：丢弃。
                    }
                    is McpJsonRpc.Response.ServerRequest -> {
                        try {
                            serverRequestListener?.invoke(resp)
                        } catch (_: Exception) {
                        }
                    }
                    is McpJsonRpc.Response.Notification -> {
                        try {
                            notificationListener?.invoke(resp.notification)
                        } catch (_: Exception) {
                        }
                    }
                    is McpJsonRpc.Response.Ignored -> {
                        // 脏行：跳过。
                    }
                }
                if (process?.isAlive != true && incoming.isEmpty()) break
            }
        }, "mcp-stdio-dispatcher").apply { isDaemon = true }
        dThread.start()
        dispatcherThread = dThread
        // 进程刚起就退出（如命令不存在/立即报错）：不等第一个请求就报出来。
        Thread.sleep(100)
        if (!proc.isAlive) {
            val err = try {
                proc.errorStream.readBytes().toString(Charsets.UTF_8).trim().take(300)
            } catch (_: Exception) {
                ""
            }
            closeQuietly()
            throw McpException(
                "MCP 服务器启动后立即退出（退出码 ${proc.exitValue()}）${if (err.isNotEmpty()) "：$err" else ""}",
                "mcp_start_failed",
            )
        }
    }

    override suspend fun request(method: String, params: JSONObject?, timeoutMillis: Long): Any? =
        withContext(Dispatchers.IO) {
            val proc = process ?: throw McpException("MCP 传输未启动", "mcp_not_started")
            if (!proc.isAlive) throw McpException("MCP 服务器进程已退出", "mcp_exited")
            val id = nextId.getAndIncrement()
            val waiter = LinkedBlockingQueue<McpJsonRpc.Response>()
            waiters[id] = waiter
            try {
                val line = McpJsonRpc.request(id, method, params).toString()
                try {
                    writer?.apply {
                        write(line)
                        write("\n")
                        flush()
                    } ?: throw McpException("MCP 传输未启动", "mcp_not_started")
                } catch (e: McpException) {
                    throw e
                } catch (e: Exception) {
                    throw McpException("发送 MCP 请求失败：${e.message ?: "未知错误"}", "mcp_send_failed")
                }
                val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
                while (true) {
                    val remainNanos = deadlineNanos - System.nanoTime()
                    if (remainNanos <= 0) {
                        throw McpException("等待 MCP 回包超时（${timeoutMillis}ms，方法 $method）", "mcp_timeout")
                    }
                    val resp = waiter.poll(remainNanos, TimeUnit.NANOSECONDS)
                        ?: throw McpException("等待 MCP 回包超时（${timeoutMillis}ms，方法 $method）", "mcp_timeout")
                    when (resp) {
                        is McpJsonRpc.Response.Result -> return@withContext resp.result
                        is McpJsonRpc.Response.Error -> {
                            throw McpException("MCP 服务端错误 ${resp.code}：${resp.message}", "mcp_rpc_error")
                        }
                        else -> {
                            // 通知不会进 waiter；其余情况继续等。
                        }
                    }
                }
            } finally {
                waiters.remove(id)
            }
        }

    override suspend fun notify(method: String, params: JSONObject?) = withContext(Dispatchers.IO) {
        val proc = process ?: return@withContext
        if (!proc.isAlive) return@withContext
        try {
            writer?.apply {
                write(McpJsonRpc.notification(method, params).toString())
                write("\n")
                flush()
            }
        } catch (_: Exception) {
            // 通知不回包：发不出去也只记，不抛错。
        }
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            process?.destroy()
            process?.waitFor(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        try {
            if (process?.isAlive == true) process?.destroyForcibly()
        } catch (_: Exception) {
        }
        try {
            dispatcherThread?.interrupt()
        } catch (_: Exception) {
        }
        process = null
        writer = null
        readerThread = null
        dispatcherThread = null
        waiters.clear()
    }
}

class HttpMcpTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val authProvider: McpAuthProvider? = null,
) : McpTransport {
    private val nextId = AtomicLong(1)

    /** 会话 id（服务端在 initialize 回包头里给；没有就是无状态模式）。 */
    @Volatile
    var sessionId: String? = null
        private set

    /** GET SSE 流最近一个事件的 id（重连时带 Last-Event-ID 续接）。 */
    @Volatile
    private var lastEventId: String? = null

    @Volatile
    private var notificationListener: ((McpNotification) -> Unit)? = null

    @Volatile
    private var serverRequestListener: ((McpJsonRpc.Response.ServerRequest) -> Unit)? = null

    @Volatile
    private var getStreamActive: Boolean = false

    private var getStreamThread: Thread? = null
    private val waiters = ConcurrentHashMap<Long, LinkedBlockingQueue<McpJsonRpc.Response>>()
    private val streamLock = Any()

    override fun setNotificationListener(listener: ((McpNotification) -> Unit)?) {
        notificationListener = listener
    }

    override fun setServerRequestListener(listener: ((McpJsonRpc.Response.ServerRequest) -> Unit)?) {
        serverRequestListener = listener
    }

    /**
     * Streamable HTTP 应答服务端请求：再 POST 一条带同 id 的
     * result（服务端按 id 配对，不回包体也行；202 即成功）。
     */
    override suspend fun respond(id: Long, result: JSONObject) {
        withContext(Dispatchers.IO) {
            postOnce(McpJsonRpc.response(id, result).toString(), 10_000)
        }
    }

    override suspend fun respondError(id: Long, code: Int, message: String) {
        withContext(Dispatchers.IO) {
            postOnce(McpJsonRpc.errorResponse(id, code, message).toString(), 10_000)
        }
    }

    /** GET SSE 流当前是否在跑（会话模式的服务端消息通道）。 */
    fun isGetStreamActive(): Boolean = getStreamActive

    override suspend fun start() {
        // HTTP 无建连动作：第一次 request 即验证。URL 格式现在就验，
        // 错 URL 不等到连接时才报。
        try {
            URL(url)
        } catch (_: Exception) {
            throw McpException("MCP 服务器地址不合法：$url", "mcp_bad_url")
        }
    }

    override suspend fun request(method: String, params: JSONObject?, timeoutMillis: Long): Any? =
        withContext(Dispatchers.IO) {
            postRequest(method, params, timeoutMillis, allowSessionRetry = true)
        }

    /**
     * 一次 POST 请求。回 404（会话不存在）或 400 且正文提到 session
     * （无会话 id）时视为会话过期，抛 [McpException]
     * （code=mcp_session_expired），由 McpClient 重新 initialize 后
     * 重试（allowSessionRetry=false 时不重试、直接抛）。
     */
    private suspend fun postRequest(
        method: String,
        params: JSONObject?,
        timeoutMillis: Long,
        allowSessionRetry: Boolean,
    ): Any? {
        val id = nextId.getAndIncrement()
        val waiter = LinkedBlockingQueue<McpJsonRpc.Response>()
        waiters[id] = waiter
        try {
            val body = McpJsonRpc.request(id, method, params).toString()
            val (status, respHeaders, rawBody) = postOnce(body, timeoutMillis)
            if (status == 404 || (status == 400 && rawBody.contains("session", ignoreCase = true))) {
                // 会话过期/缺失：官方 Python SDK v1.30 实测——无会话 id
                // 回 400 "Missing session ID"，会话不存在回 404 "Session
                // not found"。清掉会话 id，GET 流也停掉，交给调用方
                // 重新 initialize 再重试（仅一次，由 McpClient 控制）。
                stopGetStream()
                sessionId = null
                throw McpException("MCP 会话已过期（HTTP $status），需要重新连接", "mcp_session_expired")
            }
            if (status == 401 && authProvider != null) {
                val wwwAuth = respHeaders.entries
                    .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                    ?.value?.firstOrNull()
                val handled = try {
                    authProvider.handleUnauthorized(wwwAuth)
                } catch (_: Exception) {
                    false
                }
                if (handled && allowSessionRetry) {
                    return postRequest(method, params, timeoutMillis, allowSessionRetry = false)
                }
                throw McpException("MCP 服务器要求认证（HTTP 401）", "mcp_unauthorized")
            }
            if (status !in 200..299) {
                throw McpException(
                    "MCP 服务器 HTTP $status${if (rawBody.isNotBlank()) "：${rawBody.take(300)}" else ""}",
                    "mcp_http_error",
                )
            }
            val contentType = respHeaders.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value?.firstOrNull() ?: ""
            val lines = if ("text/event-stream" in contentType) {
                parseSseDataLines(rawBody)
            } else {
                listOf(rawBody.trim())
            }
            var lastError: McpException? = null
            for (line in lines) {
                if (line.isBlank()) continue
                when (val resp = parseResponse(line)) {
                    is McpJsonRpc.Response.Result -> {
                        if (resp.id == id) return resp.result
                        // 别人的回包（走 GET 流来的也可能先到）：交给 waiter。
                        if (resp.id != null) waiters[resp.id]?.offer(resp)
                    }
                    is McpJsonRpc.Response.Error -> {
                        if (resp.id == id) {
                            throw McpException("MCP 服务端错误 ${resp.code}：${resp.message}", "mcp_rpc_error")
                        }
                        if (resp.id != null) waiters[resp.id]?.offer(resp)
                    }
                    is McpJsonRpc.Response.ServerRequest -> {
                        try {
                            serverRequestListener?.invoke(resp)
                        } catch (_: Exception) {
                        }
                    }
                    is McpJsonRpc.Response.Notification -> {
                        dispatchNotification(resp.notification)
                    }
                    is McpJsonRpc.Response.Ignored -> {
                        lastError = McpException("MCP 服务器回包无法解析", "mcp_bad_response")
                    }
                }
            }
            // POST 回包里没找到本次请求的回包：可能走 GET 流回来，
            // 等一等 GET 流上的回包（会话模式才可能；无状态直接报错）。
            if (sessionId != null) {
                val deadlineNanos = System.nanoTime() + 10_000L * 1_000_000L
                while (true) {
                    val remainNanos = deadlineNanos - System.nanoTime()
                    if (remainNanos <= 0) break
                    val resp = waiter.poll(remainNanos, TimeUnit.NANOSECONDS) ?: break
                    when (resp) {
                        is McpJsonRpc.Response.Result -> return resp.result
                        is McpJsonRpc.Response.Error -> {
                            throw McpException("MCP 服务端错误 ${resp.code}：${resp.message}", "mcp_rpc_error")
                        }
                        else -> {
                            // 继续等。
                        }
                    }
                }
            }
            throw lastError ?: McpException("MCP 服务器没有返回本次请求的回包", "mcp_bad_response")
        } finally {
            waiters.remove(id)
        }
    }

    /** 一次裸 POST，返回（状态码、回包头、回包体）。401 时回包体是错误体。 */
    private suspend fun postOnce(body: String, timeoutMillis: Long): Triple<Int, Map<String, List<String>>, String> {
        val extraHeaders = try {
            authProvider?.authHeaders() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap<String, String>()
        }
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = timeoutMillis.toInt().coerceAtLeast(1_000)
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                val sid = sessionId
                if (!sid.isNullOrBlank()) setRequestProperty("Mcp-Session-Id", sid)
                for ((key, value) in headers) setRequestProperty(key, value)
                for ((key, value) in extraHeaders) setRequestProperty(key, value)
            }
        } catch (e: Exception) {
            throw McpException("连接 MCP 服务器失败：${e.message ?: "未知错误"}", "mcp_connect_failed")
        }
        try {
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                throw McpException("连接 MCP 服务器失败：${e.message ?: "未知错误"}", "mcp_connect_failed")
            }
            val status = try {
                conn.responseCode
            } catch (e: Exception) {
                throw McpException("读取 MCP 响应失败：${e.message ?: "未知错误"}", "mcp_http_error")
            }
            // 会话 id 在回包头里（initialize 回包才有；其余请求也可能带）。
            val newSession = conn.headerFields.entries
                .firstOrNull { it.key.equals("Mcp-Session-Id", ignoreCase = true) }
                ?.value?.firstOrNull()
            if (!newSession.isNullOrBlank() && newSession != sessionId) {
                sessionId = newSession
                // 新会话建立：开 GET 流收服务端主动消息。
                startGetStream()
            }
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val raw = try {
                stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            } catch (e: Exception) {
                throw McpException("读取 MCP 响应失败：${e.message ?: "未知错误"}", "mcp_http_error")
            }
            return Triple(status, conn.headerFields.filterKeys { it != null }, raw)
        } finally {
            conn.disconnect()
        }
    }

    /** 开 GET SSE 流（会话模式）：服务端主动消息的通道。 */
    private fun startGetStream() {
        synchronized(streamLock) {
            if (getStreamThread?.isAlive == true) return
            val sid = sessionId ?: return
            val thread = Thread({
                var attempt = 0
                while (attempt < MAX_GET_RECONNECTS) {
                    try {
                        runGetStreamOnce(sid)
                        // 正常结束（服务端关流）：不再重连。
                        break
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) {
                        attempt++
                        if (attempt >= MAX_GET_RECONNECTS) break
                        try {
                            Thread.sleep((1000L shl (attempt - 1)).coerceAtMost(4000L))
                        } catch (_: InterruptedException) {
                            break
                        }
                        if (sessionId == null) break
                    }
                }
                getStreamActive = false
            }, "mcp-http-get-stream").apply { isDaemon = true }
            getStreamThread = thread
            getStreamActive = true
            thread.start()
        }
    }

    /** 一次 GET SSE 流：读到服务端关流或出错即返回（由外层决定重连）。 */
    private fun runGetStreamOnce(session: String) {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 0 // 流式长连：不设读超时。
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Mcp-Session-Id", session)
                val last = lastEventId
                if (!last.isNullOrBlank()) setRequestProperty("Last-Event-ID", last)
                for ((key, value) in headers) setRequestProperty(key, value)
                try {
                    // GET 流跑在普通线程上，不是协程：authHeaders() 本身
                    // 只是读 token 存取（不做 IO），用 runBlocking 包一层。
                    val authHeaders = runBlocking { authProvider?.authHeaders() ?: emptyMap() }
                    authHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            throw McpException("打开 MCP 事件流失败：${e.message ?: "未知错误"}", "mcp_connect_failed")
        }
        try {
            val status = try {
                conn.responseCode
            } catch (e: Exception) {
                throw McpException("读取 MCP 事件流失败：${e.message ?: "未知错误"}", "mcp_http_error")
            }
            if (status == 404 || status == 405) {
                // 服务端不支持 GET 流（无状态服务器）：安静退出，不重试。
                return
            }
            if (status !in 200..299) {
                throw McpException("MCP 事件流 HTTP $status", "mcp_http_error")
            }
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            var eventId: String? = null
            var dataLines = mutableListOf<String>()
            fun flushEvent() {
                if (dataLines.isNotEmpty()) {
                    val data = dataLines.joinToString("\n")
                    if (eventId != null) lastEventId = eventId
                    for (line in data.lines()) {
                        if (line.isBlank()) continue
                        when (val resp = parseResponse(line)) {
                            is McpJsonRpc.Response.Result, is McpJsonRpc.Response.Error -> {
                                val id = when (resp) {
                                    is McpJsonRpc.Response.Result -> resp.id
                                    is McpJsonRpc.Response.Error -> resp.id
                                    else -> null
                                }
                                if (id != null) waiters[id]?.offer(resp)
                            }
                            is McpJsonRpc.Response.ServerRequest -> {
                                try {
                                    serverRequestListener?.invoke(resp)
                                } catch (_: Exception) {
                                }
                            }
                            is McpJsonRpc.Response.Notification -> dispatchNotification(resp.notification)
                            is McpJsonRpc.Response.Ignored -> {
                                // 脏行：跳过。
                            }
                        }
                    }
                }
                eventId = null
                dataLines = mutableListOf()
            }
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("id:") -> eventId = line.removePrefix("id:").trim()
                    line.startsWith("data:") -> dataLines += line.removePrefix("data:").trim()
                    line.startsWith("event:") -> {
                        // 事件类型（message 等）：本版不区分，都按 data 解析。
                    }
                    line.isBlank() -> flushEvent()
                    else -> {
                        // 注释行（:xxx）或未知字段：跳过。
                    }
                }
            }
            flushEvent()
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopGetStream() {
        synchronized(streamLock) {
            getStreamActive = false
            try {
                getStreamThread?.interrupt()
            } catch (_: Exception) {
            }
            getStreamThread = null
        }
    }

    private fun dispatchNotification(notification: McpNotification) {
        try {
            notificationListener?.invoke(notification)
        } catch (_: Exception) {
        }
    }

    /** 把 SSE 体按事件拆成 data 行（POST 回包是 text/event-stream 时用）。 */
    private fun parseSseDataLines(raw: String): List<String> {
        val out = mutableListOf<String>()
        var dataLines = mutableListOf<String>()
        fun flush() {
            if (dataLines.isNotEmpty()) out += dataLines.joinToString("\n")
            dataLines = mutableListOf()
        }
        for (line in raw.lines()) {
            when {
                line.startsWith("data:") -> dataLines += line.removePrefix("data:").trim()
                line.isBlank() -> flush()
                else -> {
                    // id:/event: 行：本版只在 GET 流里用，POST 回包里忽略。
                }
            }
        }
        flush()
        return out
    }

    override suspend fun notify(method: String, params: JSONObject?) {
        // Streamable HTTP 下通知仍是一次 POST，但不关心回包；失败只记不抛。
        // 通知不带 id，服务端回 202；这里直接走 postOnce，不等回包配对。
        try {
            withContext(Dispatchers.IO) {
                val body = McpJsonRpc.notification(method, params).toString()
                postOnce(body, 10_000)
            }
        } catch (_: Exception) {
        }
    }

    override suspend fun close() {
        stopGetStream()
        sessionId = null
        try {
            authProvider?.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** GET 流断线重连上限（超过就停流，POST 请求仍可用）。 */
        const val MAX_GET_RECONNECTS: Int = 3
    }
}

/**
 * 老版 SSE 会话模式传输（MCP 2024-11-05，兼容层）。
 *
 * 握手（规范 2024-11-05）：
 * 1. GET [sseUrl]（就是 SSE 端点，如 `https://host/sse`），
 *    `Accept: text/event-stream`。
 * 2. 首个事件必须是 `endpoint`，data 是 POST 地址：绝对 URL
 *    直接用，相对地址按 SSE 地址解析（如 `/messages?session_id=…`）。
 * 3. 之后 `message` 事件的 data 是一条 JSON-RPC（回包、通知或
 *    服务端请求）。请求与应答都 POST 到 endpoint 地址，服务端
 *    回 202（回包不走 POST 回包体，走流）。
 *
 * 与 Streamable HTTP 的区别：这里没有 Mcp-Session-Id 头，会话
 * 全靠 endpoint URL 里的 session_id；POST 只回 202，不回内容。
 * 流断了在会话期内重开（同一 SSE 地址，同一 endpoint）；重开
 * 也失败时后续请求报 `mcp_connect_failed`，由管理器断线重连。
 */
class SseMcpTransport(
    private val sseUrl: String,
    private val headers: Map<String, String> = emptyMap(),
    private val authProvider: McpAuthProvider? = null,
) : McpTransport {
    private val nextId = AtomicLong(1)
    private val waiters = ConcurrentHashMap<Long, LinkedBlockingQueue<McpJsonRpc.Response>>()

    @Volatile
    private var notificationListener: ((McpNotification) -> Unit)? = null

    @Volatile
    private var serverRequestListener: ((McpJsonRpc.Response.ServerRequest) -> Unit)? = null

    /** endpoint 事件给出的 POST 地址（没学到之前请求不能发）。 */
    @Volatile
    private var postUrl: String? = null

    @Volatile
    private var streamActive: Boolean = false

    @Volatile
    private var closed: Boolean = false

    private var streamThread: Thread? = null
    private val streamLock = Any()

    override fun setNotificationListener(listener: ((McpNotification) -> Unit)?) {
        notificationListener = listener
    }

    override fun setServerRequestListener(listener: ((McpJsonRpc.Response.ServerRequest) -> Unit)?) {
        serverRequestListener = listener
    }

    override suspend fun start() = withContext(Dispatchers.IO) {
        try {
            URL(sseUrl)
        } catch (_: Exception) {
            throw McpException("MCP 服务器地址不合法：$sseUrl", "mcp_bad_url")
        }
        closed = false
        startStreamThread()
        // 等 endpoint 事件（最多 10 秒）：没学到 POST 地址就报连不上，
        // 不等到第一个请求才报。
        val deadlineNanos = System.nanoTime() + ENDPOINT_WAIT_MILLIS * 1_000_000L
        while (postUrl == null) {
            if (closed) throw McpException("MCP SSE 连接已关闭", "mcp_connect_failed")
            if (System.nanoTime() > deadlineNanos) {
                throw McpException("MCP 服务器没有返回 endpoint 地址（SSE 流未建立）", "mcp_connect_failed")
            }
            if (!streamActive && streamThread?.isAlive != true) {
                throw McpException("MCP SSE 流打开失败", "mcp_connect_failed")
            }
            Thread.sleep(50)
        }
    }

    override suspend fun request(method: String, params: JSONObject?, timeoutMillis: Long): Any? =
        withContext(Dispatchers.IO) {
            val target = awaitPostUrl(timeoutMillis)
                ?: throw McpException("MCP SSE 连接未建立（没有 endpoint 地址）", "mcp_connect_failed")
            val id = nextId.getAndIncrement()
            val waiter = LinkedBlockingQueue<McpJsonRpc.Response>()
            waiters[id] = waiter
            try {
                postJson(target, McpJsonRpc.request(id, method, params).toString(), timeoutMillis, allowAuthRetry = true)
                val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
                while (true) {
                    val remainNanos = deadlineNanos - System.nanoTime()
                    if (remainNanos <= 0) {
                        throw McpException("等待 MCP 回包超时（${timeoutMillis}ms，方法 $method）", "mcp_timeout")
                    }
                    val resp = waiter.poll(remainNanos, TimeUnit.NANOSECONDS)
                        ?: throw McpException("等待 MCP 回包超时（${timeoutMillis}ms，方法 $method）", "mcp_timeout")
                    when (resp) {
                        is McpJsonRpc.Response.Result -> return@withContext resp.result
                        is McpJsonRpc.Response.Error -> {
                            throw McpException("MCP 服务端错误 ${resp.code}：${resp.message}", "mcp_rpc_error")
                        }
                        else -> {
                            // 通知/服务端请求不会进 waiter；其余情况继续等。
                        }
                    }
                }
            } finally {
                waiters.remove(id)
            }
        }

    override suspend fun notify(method: String, params: JSONObject?) {
        try {
            withContext(Dispatchers.IO) {
                val target = awaitPostUrl(10_000) ?: return@withContext
                postJson(target, McpJsonRpc.notification(method, params).toString(), 10_000, allowAuthRetry = false)
            }
        } catch (_: Exception) {
            // 通知不回包：发不出去也只记，不抛错。
        }
    }

    override suspend fun respond(id: Long, result: JSONObject) = withContext(Dispatchers.IO) {
        val target = awaitPostUrl(10_000)
            ?: throw McpException("MCP SSE 连接未建立（没有 endpoint 地址）", "mcp_connect_failed")
        postJson(target, McpJsonRpc.response(id, result).toString(), 10_000, allowAuthRetry = true)
    }

    override suspend fun respondError(id: Long, code: Int, message: String) = withContext(Dispatchers.IO) {
        val target = awaitPostUrl(10_000)
            ?: throw McpException("MCP SSE 连接未建立（没有 endpoint 地址）", "mcp_connect_failed")
        postJson(target, McpJsonRpc.errorResponse(id, code, message).toString(), 10_000, allowAuthRetry = true)
    }

    override suspend fun close() {
        closed = true
        synchronized(streamLock) {
            try {
                streamThread?.interrupt()
            } catch (_: Exception) {
            }
            streamThread = null
        }
        streamActive = false
        postUrl = null
        waiters.clear()
        try {
            authProvider?.close()
        } catch (_: Exception) {
        }
    }

    /** 等 endpoint 地址（start() 已等过；这里是请求时的二次确认）。 */
    private fun awaitPostUrl(timeoutMillis: Long): String? {
        val deadlineNanos = System.nanoTime() + timeoutMillis.coerceAtMost(10_000L) * 1_000_000L
        while (true) {
            val url = postUrl
            if (url != null) return url
            if (closed || System.nanoTime() > deadlineNanos) return null
            Thread.sleep(50)
        }
    }

    /** 一次 POST（JSON 体）。401 时问认证方处理，处理成功重试一次。 */
    private fun postJson(target: String, body: String, timeoutMillis: Long, allowAuthRetry: Boolean) {
        val extraHeaders = try {
            runBlocking { authProvider?.authHeaders() ?: emptyMap() }
        } catch (_: Exception) {
            emptyMap<String, String>()
        }
        val conn = try {
            (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = timeoutMillis.toInt().coerceAtLeast(1_000)
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                for ((key, value) in headers) setRequestProperty(key, value)
                for ((key, value) in extraHeaders) setRequestProperty(key, value)
            }
        } catch (e: Exception) {
            throw McpException("连接 MCP 服务器失败：${e.message ?: "未知错误"}", "mcp_connect_failed")
        }
        try {
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                throw McpException("连接 MCP 服务器失败：${e.message ?: "未知错误"}", "mcp_connect_failed")
            }
            val status = try {
                conn.responseCode
            } catch (e: Exception) {
                throw McpException("读取 MCP 响应失败：${e.message ?: "未知错误"}", "mcp_http_error")
            }
            if (status == 401 && authProvider != null && allowAuthRetry) {
                val wwwAuth = conn.headerFields.entries
                    .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                    ?.value?.firstOrNull()
                val handled = try {
                    runBlocking { authProvider.handleUnauthorized(wwwAuth) }
                } catch (_: Exception) {
                    false
                }
                if (handled) {
                    postJson(target, body, timeoutMillis, allowAuthRetry = false)
                    return
                }
                throw McpException("MCP 服务器要求认证（HTTP 401）", "mcp_unauthorized")
            }
            if (status !in 200..299) {
                val raw = try {
                    conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
                } catch (_: Exception) {
                    ""
                }
                throw McpException(
                    "MCP 服务器 HTTP $status${if (raw.isNotBlank()) "：${raw.take(300)}" else ""}",
                    "mcp_http_error",
                )
            }
            // 202/200：回包走流，不在这里读体（体可能为空）。
            try {
                conn.inputStream?.readBytes()
            } catch (_: Exception) {
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun startStreamThread() {
        synchronized(streamLock) {
            if (streamThread?.isAlive == true) return
            streamActive = true
            val thread = Thread({
                var attempt = 0
                while (!closed) {
                    try {
                        runStreamOnce()
                        // 服务端正常关流：不再重开（会话已结束）。
                        break
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) {
                        if (closed) break
                        attempt++
                        if (attempt > MAX_STREAM_RECONNECTS) break
                        try {
                            Thread.sleep((1000L shl (attempt - 1)).coerceAtMost(4000L))
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                streamActive = false
            }, "mcp-sse-stream").apply { isDaemon = true }
            streamThread = thread
            thread.start()
        }
    }

    /** 一次 GET SSE 流：读到服务端关流或出错即返回（外层决定重开）。 */
    private fun runStreamOnce() {
        val extraHeaders = try {
            runBlocking { authProvider?.authHeaders() ?: emptyMap() }
        } catch (_: Exception) {
            emptyMap<String, String>()
        }
        val conn = try {
            (URL(sseUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 0 // 流式长连：不设读超时。
                setRequestProperty("Accept", "text/event-stream")
                for ((key, value) in headers) setRequestProperty(key, value)
                for ((key, value) in extraHeaders) setRequestProperty(key, value)
            }
        } catch (e: Exception) {
            throw McpException("打开 MCP SSE 流失败：${e.message ?: "未知错误"}", "mcp_connect_failed")
        }
        try {
            val status = try {
                conn.responseCode
            } catch (e: Exception) {
                throw McpException("读取 MCP SSE 流失败：${e.message ?: "未知错误"}", "mcp_http_error")
            }
            if (status == 401 && authProvider != null) {
                val wwwAuth = conn.headerFields.entries
                    .firstOrNull { it.key.equals("WWW-Authenticate", ignoreCase = true) }
                    ?.value?.firstOrNull()
                val handled = try {
                    runBlocking { authProvider.handleUnauthorized(wwwAuth) }
                } catch (_: Exception) {
                    false
                }
                if (handled) {
                    // 认证好了：这次 run 直接重试一次（不算断线重连）。
                    runStreamOnce()
                    return
                }
                throw McpException("MCP 服务器要求认证（HTTP 401）", "mcp_unauthorized")
            }
            if (status !in 200..299) {
                throw McpException("MCP SSE 流 HTTP $status", "mcp_http_error")
            }
            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            var eventType: String? = null
            var dataLines = mutableListOf<String>()
            fun flushEvent() {
                if (dataLines.isEmpty()) {
                    eventType = null
                    return
                }
                val data = dataLines.joinToString("\n")
                dataLines = mutableListOf()
                val type = eventType
                eventType = null
                when (type) {
                    "endpoint" -> {
                        // 首个 endpoint 事件：学到 POST 地址。
                        val raw = data.trim()
                        if (raw.isNotEmpty() && postUrl == null) {
                            postUrl = resolvePostUrl(raw)
                        }
                    }
                    "message", null -> {
                        for (line in data.lines()) {
                            if (line.isBlank()) continue
                            dispatchLine(line)
                        }
                    }
                    else -> {
                        // 其余事件类型：跳过。
                    }
                }
            }
            while (!closed) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> dataLines += line.removePrefix("data:").trim()
                    line.startsWith("id:") -> {
                        // 老版 SSE 的事件 id：本版不做 Last-Event-ID 续接
                        // （会话靠 endpoint URL 里的 session_id 续）。
                    }
                    line.isBlank() -> flushEvent()
                    line.startsWith(":") -> {
                        // 注释行：跳过。
                    }
                    else -> {
                        // 未知字段：跳过。
                    }
                }
            }
            flushEvent()
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun dispatchLine(line: String) {
        when (val resp = parseResponse(line)) {
            is McpJsonRpc.Response.Result, is McpJsonRpc.Response.Error -> {
                val id = when (resp) {
                    is McpJsonRpc.Response.Result -> resp.id
                    is McpJsonRpc.Response.Error -> resp.id
                    else -> null
                }
                if (id != null) waiters[id]?.offer(resp)
            }
            is McpJsonRpc.Response.ServerRequest -> {
                try {
                    serverRequestListener?.invoke(resp)
                } catch (_: Exception) {
                }
            }
            is McpJsonRpc.Response.Notification -> {
                try {
                    notificationListener?.invoke(resp.notification)
                } catch (_: Exception) {
                }
            }
            is McpJsonRpc.Response.Ignored -> {
                // 脏行：跳过。
            }
        }
    }

    /** endpoint 地址解析：绝对 URL 直接用，相对地址按 SSE 地址解析。 */
    private fun resolvePostUrl(raw: String): String {
        return try {
            if (raw.startsWith("http://") || raw.startsWith("https://")) raw
            else URL(URL(sseUrl), raw).toString()
        } catch (_: Exception) {
            raw
        }
    }

    companion object {
        /** 等 endpoint 事件的最长等待（start() 时）。 */
        const val ENDPOINT_WAIT_MILLIS: Long = 10_000

        /** SSE 流断线重开上限（超过就停流，后续请求报连不上）。 */
        const val MAX_STREAM_RECONNECTS: Int = 3
    }
}
