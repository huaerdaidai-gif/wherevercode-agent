package wherevercode.agent.core.platform

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 内网平台服务（频道三层二期）：loopback 真套接字验收——健康检查、
 * 令牌鉴权、成员以登记身份发帖/读帖、WS 握手（含 RFC 6455 示例
 * Accept 值）与推送到达、非成员订阅 403。频道语义本身由
 * LocalChannelServiceTest 覆盖，这里只验网络门面与身份传递。
 */
class LanPlatformServerTest {

    private var now = 5_000.0
    private lateinit var service: LocalChannelService
    private lateinit var registry: LanTokenRegistry
    private lateinit var server: LanPlatformServer
    private var port: Int = -1

    @Before
    fun setUp() {
        service = LocalChannelService(InMemoryLocalChannelPersistence()) { now }
        registry = LanTokenRegistry()
        server = LanPlatformServer({ service }, registry, bindHost = "127.0.0.1", requestedPort = 0)
        server.start()
        port = server.port
        assertTrue(port > 0)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ---- HTTP 基础 ---------------------------------------------------------

    @Test
    fun `health endpoint answers without a token`() {
        val (status, body) = http("GET", "/", null, null)
        assertEquals(200, status)
        val json = JSONObject(body)
        assertTrue(json.getBoolean("ok"))
        assertEquals("lan-platform", json.getString("service"))
    }

    @Test
    fun `tools call without or with a bad token is 401`() {
        val body = rpcBody("agent_channel_list", JSONObject())
        assertEquals(401, http("POST", "/api/open/v1/mcp", null, body).first)
        assertEquals(401, http("POST", "/api/open/v1/mcp", "deadbeef".repeat(4), body).first)
    }

    @Test
    fun `unknown path is 404`() {
        assertEquals(404, http("GET", "/nope", null, null).first)
    }

    // ---- 成员身份与发读 ------------------------------------------------------

    @Test
    fun `lan members post and read under their issued identities`() {
        val alice = registry.issue("甲机")
        val bob = registry.issue("乙机")

        val channels = callTool(alice.token, "agent_channel_list", JSONObject())
        val channelId = channels.getJSONArray("channels").getJSONObject(0).getString("id")

        val posted = callTool(
            alice.token, "agent_channel_post",
            JSONObject().put("channel_id", channelId).put("text", "lan-hello"),
        )
        // agent_channel_post 的结果就是消息 JSON 本身（与服务端同形）。
        assertEquals("lan-hello", posted.getString("text"))
        assertEquals(alice.agentId, posted.getString("agent_id"))
        assertEquals("甲机", posted.getString("agent_name"))

        callTool(bob.token, "agent_channel_join", JSONObject().put("channel_id", channelId))
        val read = callTool(
            bob.token, "agent_channel_messages",
            JSONObject().put("channel_id", channelId),
        )
        val texts = (0 until read.getJSONArray("messages").length()).map {
            read.getJSONArray("messages").getJSONObject(it).getString("text")
        }
        assertTrue(texts.contains("lan-hello"))
    }

    @Test
    fun `tool failure comes back as an mcp error result not a fake success`() {
        val alice = registry.issue("甲机")
        val channels = callTool(alice.token, "agent_channel_list", JSONObject())
        val channelId = channels.getJSONArray("channels").getJSONObject(0).getString("id")
        // 甲机还没入群就读消息：成员门禁必须原样以 isError 呈现。
        val (status, raw) = http(
            "POST", "/api/open/v1/mcp", alice.token,
            rpcBody(
                "agent_channel_messages",
                JSONObject().put("channel_id", channelId),
            ),
        )
        assertEquals(200, status)
        val result = JSONObject(raw).getJSONObject("result")
        assertTrue(result.getBoolean("isError"))
    }

    // ---- WebSocket -----------------------------------------------------------

    @Test
    fun `ws handshake uses the rfc6455 accept value and pushes new posts`() {
        val alice = registry.issue("甲机")
        val bob = registry.issue("乙机")
        val channels = callTool(alice.token, "agent_channel_list", JSONObject())
        val channelId = channels.getJSONArray("channels").getJSONObject(0).getString("id")
        callTool(
            alice.token, "agent_channel_post",
            JSONObject().put("channel_id", channelId).put("text", "seed"),
        )

        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
        socket.soTimeout = 5_000
        val out = socket.getOutputStream()
        // RFC 6455 §1.3 的示例 key，对应 Accept 是定值——独立校验握手实现。
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val request = "GET /api/open/v1/channel/ws?channel_id=$channelId HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Authorization: Bearer ${alice.token}\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"
        out.write(request.toByteArray(Charsets.US_ASCII))
        out.flush()
        val input = BufferedInputStream(socket.getInputStream())
        val head = readHeaderBlock(input)
        assertTrue(head, head.startsWith("HTTP/1.1 101"))
        assertTrue(head, head.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))

        callTool(
            bob.token, "agent_channel_post",
            JSONObject().put("channel_id", channelId).put("text", "lan-push"),
        )
        val payload = readServerFrame(input)
        val event = JSONObject(payload)
        assertEquals("message", event.getString("type"))
        assertEquals("lan-push", event.getJSONObject("message").getString("text"))
        assertEquals(bob.agentId, event.getJSONObject("message").getString("agent_id"))
        socket.close()
    }

    @Test
    fun `ws subscribe by a non member is refused 403`() {
        val alice = registry.issue("甲机")
        val carol = registry.issue("丙机")
        val channels = callTool(alice.token, "agent_channel_list", JSONObject())
        val channelId = channels.getJSONArray("channels").getJSONObject(0).getString("id")
        val response = wsRefusedResponse(carol.token, channelId)
        assertTrue(response, response.startsWith("HTTP/1.1 403"))
        assertTrue(response, response.contains("not_a_channel_member"))
    }

    @Test
    fun `ws subscribe with a bad token is refused 401`() {
        val alice = registry.issue("甲机")
        val channels = callTool(alice.token, "agent_channel_list", JSONObject())
        val channelId = channels.getJSONArray("channels").getJSONObject(0).getString("id")
        val response = wsRefusedResponse("deadbeef".repeat(4), channelId)
        assertTrue(response, response.startsWith("HTTP/1.1 401"))
    }

    // ---- 客户端小工具 ---------------------------------------------------------

    private fun rpcBody(tool: String, arguments: JSONObject): String = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "tools/call")
        put("params", JSONObject().apply {
            put("name", tool)
            put("arguments", arguments)
        })
    }.toString()

    /** 发一次 tools/call 并解出工具结果 JSON（失败断言 isError=false）。 */
    private fun callTool(token: String, tool: String, arguments: JSONObject): JSONObject {
        val (status, raw) = http("POST", "/api/open/v1/mcp", token, rpcBody(tool, arguments))
        assertEquals("tool $tool http", 200, status)
        val result = JSONObject(raw).getJSONObject("result")
        assertFalse(
            "tool $tool errored: ${result.optJSONArray("content")}",
            result.optBoolean("isError", false),
        )
        val text = result.getJSONArray("content").getJSONObject(0).getString("text")
        return JSONObject(text)
    }

    /** 一次短连接 HTTP 请求，返回（状态码，正文）。 */
    private fun http(method: String, path: String, token: String?, body: String?): Pair<Int, String> {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val bodyBytes = body?.toByteArray(Charsets.UTF_8)
            val head = StringBuilder()
            head.append("$method $path HTTP/1.1\r\n")
            head.append("Host: 127.0.0.1:$port\r\n")
            if (token != null) head.append("Authorization: Bearer $token\r\n")
            if (bodyBytes != null) {
                head.append("Content-Type: application/json\r\n")
                head.append("Content-Length: ${bodyBytes.size}\r\n")
            }
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.US_ASCII))
            if (bodyBytes != null) out.write(bodyBytes)
            out.flush()
            val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val status = raw.substringAfter("HTTP/1.1 ").substringBefore(" ").toInt()
            val responseBody = raw.substringAfter("\r\n\r\n", "")
            return status to responseBody
        }
    }

    private fun wsHandshakeHead(token: String, channelId: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val request = "GET /api/open/v1/channel/ws?channel_id=$channelId HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Authorization: Bearer $token\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            out.write(request.toByteArray(Charsets.US_ASCII))
            out.flush()
            return readHeaderBlock(BufferedInputStream(socket.getInputStream()))
        }
    }

    /** 拒绝类握手：读完整个响应（头＋体），供断言状态行与错误原因。 */
    private fun wsRefusedResponse(token: String, channelId: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 5_000
            val out = socket.getOutputStream()
            val request = "GET /api/open/v1/channel/ws?channel_id=$channelId HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Authorization: Bearer $token\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            out.write(request.toByteArray(Charsets.US_ASCII))
            out.flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun readHeaderBlock(input: BufferedInputStream): String {
        val buf = ByteArrayOutputStream()
        val window = ByteArray(1)
        while (buf.size() < 16 * 1024) {
            val read = input.read(window)
            assertTrue("eof during headers", read >= 0)
            buf.write(window[0].toInt())
            val bytes = buf.toByteArray()
            if (bytes.size >= 4 &&
                bytes[bytes.size - 4] == '\r'.code.toByte() &&
                bytes[bytes.size - 3] == '\n'.code.toByte() &&
                bytes[bytes.size - 2] == '\r'.code.toByte() &&
                bytes[bytes.size - 1] == '\n'.code.toByte()
            ) {
                return bytes.toString(Charsets.US_ASCII)
            }
        }
        throw AssertionError("header block too large")
    }

    /** 读一帧服务端帧（不掩码），返回文本 payload。 */
    private fun readServerFrame(input: BufferedInputStream): String {
        val b0 = input.read()
        val b1 = input.read()
        assertTrue(b0 >= 0 && b1 >= 0)
        assertEquals(0x1, b0 and 0x0F)
        assertEquals(0, b1 and 0x80)
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            length = ((input.read() and 0xFF) shl 8 or (input.read() and 0xFF)).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or (input.read() and 0xFF).toLong() }
        }
        val payload = ByteArray(length.toInt())
        var read = 0
        while (read < payload.size) {
            val n = input.read(payload, read, payload.size - read)
            assertTrue(n >= 0)
            read += n
        }
        return payload.toString(Charsets.UTF_8)
    }
}
