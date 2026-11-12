package wherevercode.agent.core.agent

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.core.mcp.WsOpcode
import wherevercode.agent.core.mcp.computeWsAccept
import wherevercode.agent.core.mcp.encodeWsFrame

/**
 * P3 频道 WS 客户端测试（2026-11-11）。
 *
 * 回环 socket，不 mock 握手：测试服务器真算 Accept、发一条频道
 * 消息事件 + 一个 ping、读客户端的 pong 与 close。超时一律放宽
 * 到 30 秒（共享 VM 负载高，线程几秒抢不到 CPU 是常态；本测试
 * 验的是协议不是时延）。令牌只用假串 "v5_test"，不碰任何真令牌。
 */
@RunWith(RobolectricTestRunner::class)
class ChannelWsClientTest {

    private val channelId = "dedicated-channel-test"
    private val token = "v5_test-token-for-channel-ws"

    @Test
    fun `buildHandshakeRequest carries the channel path and bearer token`() {
        val request = ChannelWsClient.buildHandshakeRequest(
            host = "192.0.2.10",
            port = 443,
            channelId = channelId,
            token = token,
            key = "test-websocket-key",
        )
        assertTrue(request.startsWith("GET /api/open/v1/channel/ws?channel_id=$channelId "))
        assertTrue(request.contains("Host: 192.0.2.10:443\r\n"))
        assertTrue(request.contains("Authorization: Bearer $token\r\n"))
        assertTrue(request.contains("Sec-WebSocket-Key: test-websocket-key\r\n"))
        assertTrue(request.contains("Upgrade: websocket\r\n"))
    }

    @Test
    fun `no token never connects`() {
        val server = ServerSocket(0)
        server.soTimeout = 600
        val accepted = CountDownLatch(1)
        val acceptThread = Thread {
            try {
                server.accept()
                accepted.countDown()
            } catch (_: SocketTimeoutException) {
                // 预期：没令牌，客户端根本不该建连。
            } catch (_: Exception) {
            } finally {
                try { server.close() } catch (_: Exception) { }
            }
        }.apply { isDaemon = true }
        acceptThread.start()
        val closed = mutableListOf<String>()
        val client = ChannelWsClient(
            host = "127.0.0.1",
            port = server.localPort,
            channelId = channelId,
            tokenProvider = { null },
            socketFactory = { h, p -> Socket(h, p) },
        ).also { it.onClosed = { reason -> closed.add(reason) } }
        client.run { }
        acceptThread.join(3_000)
        assertEquals(1, accepted.count)
        assertEquals(listOf("未配置平台凭证。"), closed)
    }

    @Test
    fun `liveLoopback receives a channel message and answers ping`() {
        val server = ServerSocket(0)
        val port = server.localPort
        val eventJson = """{"type":"message","channel_id":"$channelId","message":{"id":"m1","agent_id":"example-agent","agent_name":"Example Agent","text":"在的","created_at":1794390000.0}}"""
        val serverSawPong = CountDownLatch(1)
        val serverSawClose = CountDownLatch(1)
        val serverSawAuth = CountDownLatch(1)
        val serverError = AtomicReference<String?>(null)
        val serverThread = Thread {
            var socket: Socket? = null
            try {
                server.soTimeout = 30_000
                socket = server.accept()
                socket.soTimeout = 30_000
                val input = socket.inputStream
                val output = socket.outputStream
                val request = readHttpHeaders(input)
                assertTrue(request.startsWith("GET /api/open/v1/channel/ws?channel_id=$channelId "))
                if (request.contains("Authorization: Bearer $token\r\n")) {
                    serverSawAuth.countDown()
                }
                val key = request.lines()
                    .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                    ?: throw IllegalStateException("缺 Sec-WebSocket-Key")
                val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${computeWsAccept(key)}\r\n" +
                    "\r\n"
                output.write(response.toByteArray(Charsets.US_ASCII))
                output.flush()
                output.write(encodeWsFrame(WsOpcode.TEXT, eventJson.toByteArray(Charsets.UTF_8), masked = false))
                output.write(encodeWsFrame(WsOpcode.PING, "p".toByteArray(Charsets.UTF_8), masked = false))
                output.flush()
                val deadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < deadline &&
                    (serverSawPong.count > 0 || serverSawClose.count > 0)
                ) {
                    val frame = readClientFrame(input) ?: break
                    if (frame.first == WsOpcode.PONG) serverSawPong.countDown()
                    if (frame.first == WsOpcode.CLOSE) serverSawClose.countDown()
                }
            } catch (e: Throwable) {
                serverError.set(e.message)
            } finally {
                try { socket?.close() } catch (_: Exception) { }
                try { server.close() } catch (_: Exception) { }
            }
        }.apply { isDaemon = true }
        serverThread.start()

        val received = mutableListOf<String>()
        val closed = mutableListOf<String>()
        val client = ChannelWsClient(
            host = "127.0.0.1",
            port = port,
            channelId = channelId,
            tokenProvider = { token },
            socketFactory = { h, p -> Socket(h, p) },
            readTimeoutMs = 30_000,
        ).also { it.onClosed = { reason -> closed.add(reason) } }
        val clientThread = Thread { client.run { received.add(it) } }.apply { isDaemon = true }
        clientThread.start()
        assertTrue("客户端未收到频道消息", waitUntil(30_000) { received.size >= 1 })
        assertEquals(eventJson, received[0])
        // 消息事件能解析成 PlatformChannelMessage。
        val message = parseChannelWsEvent(received[0])
        assertEquals("m1", message?.id)
        assertEquals("Example Agent", message?.agentName)
        assertEquals("在的", message?.text)
        assertTrue("服务端未收到 Authorization", serverSawAuth.await(30, TimeUnit.SECONDS))
        client.close()
        clientThread.join(30_000)
        assertTrue("服务端未收到 pong", serverSawPong.await(30, TimeUnit.SECONDS))
        assertTrue("服务端未收到 close", serverSawClose.await(30, TimeUnit.SECONDS))
        assertNull(serverError.get())
    }

    @Test
    fun `http 401 handshake ends the run with a reason`() {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            var socket: Socket? = null
            try {
                server.soTimeout = 30_000
                socket = server.accept()
                socket.soTimeout = 30_000
                readHttpHeaders(socket.inputStream)
                val body = """{"ok":false,"error":"need_token"}"""
                val response = "HTTP/1.1 401 Unauthorized\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                    "\r\n$body"
                socket.outputStream.write(response.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                Thread.sleep(2_000)
            } catch (_: Exception) {
            } finally {
                try { socket?.close() } catch (_: Exception) { }
                try { server.close() } catch (_: Exception) { }
            }
        }.apply { isDaemon = true }
        serverThread.start()
        val closed = mutableListOf<String>()
        val client = ChannelWsClient(
            host = "127.0.0.1",
            port = port,
            channelId = channelId,
            tokenProvider = { token },
            socketFactory = { h, p -> Socket(h, p) },
            readTimeoutMs = 30_000,
        ).also { it.onClosed = { reason -> closed.add(reason) } }
        client.run { }
        assertEquals(1, closed.size)
        assertTrue(closed[0].contains("401"))
    }

    @Test
    fun `parseChannelWsEvent ignores hello ping and garbage`() {
        assertNull(parseChannelWsEvent("""{"type":"hello","channel_id":"$channelId"}"""))
        assertNull(parseChannelWsEvent("""{"type":"ping","channel_id":"$channelId"}"""))
        assertNull(parseChannelWsEvent("not json"))
        assertNull(parseChannelWsEvent("""{"type":"message","message":{"text":"no id"}}"""))
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun readHttpHeaders(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        val window = ArrayDeque<Int>()
        while (true) {
            val b = input.read()
            if (b < 0) break
            buf.write(b)
            window.addLast(b)
            if (window.size > 4) window.removeFirst()
            if (window.size == 4 &&
                window[0] == '\r'.code && window[1] == '\n'.code &&
                window[2] == '\r'.code && window[3] == '\n'.code
            ) {
                break
            }
        }
        return buf.toString(Charsets.US_ASCII.name())
    }

    /** 读一帧客户端帧（带掩码）：返回（操作码，payload），流尽返回 null。 */
    private fun readClientFrame(input: InputStream): Pair<Int, ByteArray>? {
        val head = ByteArray(2)
        if (readFully(input, head) < 2) return null
        val opcode = head[0].toInt() and 0x0F
        var len = head[1].toInt() and 0x7F
        val masked = head[1].toInt() and 0x80 != 0
        if (len == 126) {
            val ext = ByteArray(2)
            if (readFully(input, ext) < 2) return null
            len = ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
        } else if (len == 127) {
            val ext = ByteArray(8)
            if (readFully(input, ext) < 8) return null
            len = ext.toList().fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        }
        val mask = if (masked) ByteArray(4).also { if (readFully(input, it) < 4) return null } else null
        val payload = ByteArray(len)
        if (len > 0 && readFully(input, payload) < len) return null
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)).toByte()
            }
        }
        return opcode to payload
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }
}
