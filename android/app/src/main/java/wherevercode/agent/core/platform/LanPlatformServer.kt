package wherevercode.agent.core.platform

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.core.mcp.WsOpcode
import wherevercode.agent.core.mcp.computeWsAccept
import wherevercode.agent.core.mcp.encodeWsFrame

/**
 * 内网平台服务（频道三层二期·内网层，方案便签 №11）：把一期的
 * 本地账本 [LocalChannelService] 以与公网平台完全相同的线协议
 * 挂到局域网——同网设备用现成的平台客户端（PlatformChannelMcpTransport
 * ＋ ChannelWsClient）连进来，就是多了一位普通成员，全功能不依赖
 * 公网。这里只做三件事：HTTP 上的 MCP tools/call、WS 订阅推送、
 * 令牌鉴权（[LanTokenRegistry]）；一切频道语义（成员门禁、4000 字、
 * 幂等、角色保护）仍由账本 dispatch 执行，本类不复制规则。
 *
 * 结构与 [wherevercode.agent.core.dispatch.McpDispatchHttpServer]
 * 同款（纯 java.net 手写 HTTP，Android/JVM 通用）。与它的区别：
 * 本服务按产品定版绑 0.0.0.0 供同网连入，鉴权主体是内网成员令牌；
 * WS 订阅按远程平台同口径做成员门禁（非成员 403，不泄漏消息）。
 *
 * 诚实口径：服务活在 App 进程里，App 被杀/退后台被回收即停；
 * 界面文案须如实标注，不冒充常驻服务器。
 *
 * 分支：feat/channel-lan-layer-224-20261123（基线 223 安卓本地层）。
 * 本文件是服务端核心；加入方 http 通道与宿主界面在后续提交接入。
 */
class LanPlatformServer(
    private val serviceProvider: () -> LocalChannelService?,
    private val registry: LanTokenRegistry,
    private val bindHost: String = "0.0.0.0",
    private val requestedPort: Int = DEFAULT_PORT,
    private val readTimeoutMillis: Int = 15_000,
    private val workerThreads: Int = 8,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var pool: ExecutorService? = null
    private val wsSockets = java.util.Collections.synchronizedSet(HashSet<Socket>())

    val port: Int get() = serverSocket?.localPort ?: -1

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    @Synchronized
    fun start(): LanPlatformServer {
        check(serverSocket == null) { "已启动" }
        val socket = ServerSocket(requestedPort, 50, InetAddress.getByName(bindHost))
        serverSocket = socket
        val workers = Executors.newFixedThreadPool(workerThreads) { r ->
            Thread(r, "lan-platform-worker").apply { isDaemon = true }
        }
        pool = workers
        acceptThread = Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                }
                try {
                    workers.execute { serve(client) }
                } catch (_: Exception) {
                    runCatching { client.close() }
                }
            }
        }, "lan-platform-accept").apply { isDaemon = true; start() }
        return this
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(wsSockets) {
            for (socket in wsSockets.toList()) runCatching { socket.close() }
            wsSockets.clear()
        }
        pool?.shutdownNow()
        pool?.awaitTermination(2, TimeUnit.SECONDS)
        pool = null
        acceptThread = null
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = readTimeoutMillis
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            val request = try {
                readRequest(input)
            } catch (e: BodyRejected) {
                respond(out, jsonError(e.status, if (e.status == 413) "payload too large" else "length required"))
                client.close()
                return
            }
            if (request == null) {
                client.close()
                return
            }
            if (request.method == "GET" && request.target.substringBefore('?') == WS_PATH &&
                request.headers["upgrade"]?.equals("websocket", ignoreCase = true) == true
            ) {
                // WS 连接自管生命周期（长连接），serve 不再关这条 socket。
                serveWs(client, input, out, request)
                return
            }
            respond(out, handle(request))
            client.close()
        } catch (_: SocketTimeoutException) {
            runCatching { client.close() }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    data class HttpRequest(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray?)

    data class HttpResponse(
        val status: Int,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
    )

    /** HTTP 路由（纯请求→响应，WS 升级不在这里处理）。 */
    internal fun handle(req: HttpRequest): HttpResponse {
        val path = req.target.substringBefore('?')
        return when {
            req.method == "GET" && (path == "/" || path == "/api/open/v1/health") ->
                HttpResponse(
                    200,
                    JSONObject().put("ok", true).put("service", "lan-platform").put("version", "1").toString(),
                    mapOf("Content-Type" to "application/json"),
                )
            req.method == "POST" && path == MCP_PATH -> handleMcp(req)
            else -> jsonError(404, "not found")
        }
    }

    private fun handleMcp(req: HttpRequest): HttpResponse {
        val entry = authenticate(req) ?: return jsonError(401, "unauthorized")
        val service = serviceProvider() ?: return jsonError(503, "本地平台服务未启动")
        // 登记身份（幂等）：open_dm 等工具要求对方是已登记 Agent。
        service.registerAgent(entry.agentId, entry.agentName)
        val body = req.body ?: return jsonError(400, "missing body")
        val request = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            return jsonError(400, "bad json")
        }
        val id: Any = if (request.has("id")) request.get("id") else JSONObject.NULL
        if (request.optString("method", "") == "tools/list") {
            // W12 角色化暴露：任务工具按令牌作用域过滤（干活侧
            // 只见 9 个、机主见 16 个），频道工具全量（与 iOS 同口径）。
            val scope = if (entry.scope == "owner") {
                TaskDispatchService.SCOPE_OWNER
            } else {
                TaskDispatchService.SCOPE_AGENT
            }
            val tools = JSONArray().apply {
                for (name in LocalPlatform.toolNames(scope)) {
                    put(JSONObject().put("name", name))
                }
            }
            return rpcEnvelope(id, JSONObject().put("tools", tools))
        }
        if (request.optString("method", "") != "tools/call") {
            return rpcEnvelope(id, errorResult("内网平台只支持 tools/call"))
        }
        val params = request.optJSONObject("params") ?: JSONObject()
        val name = params.optString("name", "")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        return try {
            val result = service.dispatch(name, arguments, entry.agentId, entry.agentName)
            rpcEnvelope(id, okResult(result))
        } catch (e: LocalServiceException) {
            rpcEnvelope(id, errorResult(e.message ?: "内网平台调用失败"))
        }
    }

    private fun authenticate(req: HttpRequest): LanTokenRegistry.Entry? {
        val auth = req.headers["authorization"] ?: return null
        val token = if (auth.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) {
            auth.substring(7).trim()
        } else {
            null
        }
        return registry.authenticate(token)
    }

    // ---- WebSocket 订阅 ----------------------------------------------------

    private fun serveWs(client: Socket, input: BufferedInputStream, out: OutputStream, req: HttpRequest) {
        fun refuse(status: Int, message: String) {
            runCatching { respond(out, jsonError(status, message)) }
            runCatching { client.close() }
        }
        val entry = authenticate(req) ?: run { refuse(401, "unauthorized"); return }
        val service = serviceProvider() ?: run { refuse(503, "本地平台服务未启动"); return }
        service.registerAgent(entry.agentId, entry.agentName)
        val channelId = queryParam(req.target, "channel_id")?.takeIf { it.isNotBlank() }
            ?: run { refuse(400, "missing channel_id"); return }
        if (!service.channelExists(channelId)) {
            refuse(404, "channel_not_found")
            return
        }
        if (!service.isChannelMember(channelId, entry.agentId)) {
            // 与远程平台成员门禁同口径：非成员订阅直接拒，不给任何消息。
            refuse(403, "not_a_channel_member")
            return
        }
        val key = req.headers["sec-websocket-key"]?.takeIf { it.isNotBlank() }
            ?: run { refuse(400, "missing sec-websocket-key"); return }
        val accept = computeWsAccept(key)
        val head = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()

        client.soTimeout = 0 // 长连接：读循环靠 close/对端关闭收尾，不用读超时。
        wsSockets += client
        val writeLock = Any()
        val listener: (JSONObject) -> Unit = { event ->
            try {
                val frame = encodeWsFrame(WsOpcode.TEXT, event.toString().toByteArray(Charsets.UTF_8), masked = false)
                synchronized(writeLock) {
                    out.write(frame)
                    out.flush()
                }
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }
        service.addMessageListener(channelId, listener)
        try {
            while (true) {
                val frame = readClientFrame(input) ?: break
                when (frame.opcode) {
                    WsOpcode.PING -> synchronized(writeLock) {
                        out.write(encodeWsFrame(WsOpcode.PONG, frame.payload, masked = false))
                        out.flush()
                    }
                    WsOpcode.CLOSE -> {
                        synchronized(writeLock) {
                            runCatching {
                                out.write(encodeWsFrame(WsOpcode.CLOSE, frame.payload, masked = false))
                                out.flush()
                            }
                        }
                        break
                    }
                    else -> Unit // 频道协议里客户端不发数据帧；收到也忽略。
                }
            }
        } catch (_: Exception) {
            // 对端断开/协议损坏：收尾即可。
        } finally {
            service.removeMessageListener(channelId, listener)
            wsSockets -= client
            runCatching { client.close() }
        }
    }

    private class ClientFrame(val opcode: Int, val payload: ByteArray)

    /**
     * 读一帧客户端 WS 帧（客户端帧必须带掩码，RFC 6455 §5.3）。
     * 流结束/协议损坏返回 null。与客户端用的 WsFrameReader 分工：
     * 那个只收无掩码的服务端帧，本函数只收有掩码的客户端帧。
     */
    private fun readClientFrame(input: InputStream): ClientFrame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        if (!masked) return null
        var length = (b1 and 0x7F).toLong()
        when (length) {
            126L -> {
                val hi = input.read()
                val lo = input.read()
                if (hi < 0 || lo < 0) return null
                length = ((hi and 0xFF) shl 8 or (lo and 0xFF)).toLong()
            }
            127L -> {
                length = 0
                repeat(8) {
                    val b = input.read()
                    if (b < 0) return null
                    length = (length shl 8) or (b and 0xFF).toLong()
                }
            }
        }
        if (length < 0 || length > MAX_WS_PAYLOAD_BYTES) return null
        val maskKey = ByteArray(4)
        var got = 0
        while (got < 4) {
            val n = input.read(maskKey, got, 4 - got)
            if (n < 0) return null
            got += n
        }
        val payload = ByteArray(length.toInt())
        var read = 0
        while (read < payload.size) {
            val n = input.read(payload, read, payload.size - read)
            if (n < 0) return null
            read += n
        }
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor (maskKey[i % 4].toInt() and 0xFF)).toByte()
        }
        return ClientFrame(opcode, payload)
    }

    // ---- HTTP 读写（与 McpDispatchHttpServer 同款，体积上限按频道消息收紧） ----

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
            if (length > MAX_BODY_BYTES) throw BodyRejected(413)
            val buf = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) return null
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
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 411 -> "Length Required"; 413 -> "Payload Too Large"
        503 -> "Service Unavailable"; else -> "Error"
    }

    private fun jsonError(status: Int, message: String) =
        HttpResponse(status, JSONObject().put("error", message).toString(), mapOf("Content-Type" to "application/json"))

    private fun rpcEnvelope(id: Any, result: JSONObject): HttpResponse = HttpResponse(
        200,
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }.toString(),
        mapOf("Content-Type" to "application/json"),
    )

    private fun okResult(result: JSONObject): JSONObject = JSONObject().apply {
        put("content", JSONArray().put(JSONObject().apply {
            put("type", "text")
            put("text", result.toString())
        }))
        put("isError", false)
    }

    private fun errorResult(message: String): JSONObject = JSONObject().apply {
        put("content", JSONArray().put(JSONObject().apply {
            put("type", "text")
            put("text", message)
        }))
        put("isError", true)
    }

    private fun queryParam(target: String, name: String): String? {
        val query = target.substringAfter('?', "")
        if (query.isBlank()) return null
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            if (key == name) {
                val raw = pair.substringAfter('=', "")
                return try {
                    java.net.URLDecoder.decode(raw, "UTF-8")
                } catch (_: Exception) {
                    raw
                }
            }
        }
        return null
    }

    companion object {
        const val DEFAULT_PORT: Int = 18744
        const val MCP_PATH: String = "/api/open/v1/mcp"
        const val WS_PATH: String = "/api/open/v1/channel/ws"
        const val MAX_HEADER_BYTES: Int = 16 * 1024
        const val MAX_HEADERS: Int = 64
        const val MAX_BODY_BYTES: Int = 1024 * 1024
        const val MAX_WS_PAYLOAD_BYTES: Long = 1024 * 1024
    }
}
