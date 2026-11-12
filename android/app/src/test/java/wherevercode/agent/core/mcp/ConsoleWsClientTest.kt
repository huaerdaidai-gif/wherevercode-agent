package wherevercode.agent.core.mcp

import java.io.ByteArrayInputStream
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConsoleWsClient 测试（JUnit4 纯 JVM 测试）。
 *
 * 握手与帧编解码用纯函数单测；真连接用本地回环 socket 起一个最小
 * 测试 WS 服务（不 mock 握手本身：服务端按 RFC 6455 真算 Accept、
 * 真读客户端掩码帧、真发 ping 等 pong）。不触外网。
 */
class ConsoleWsClientTest {

    private fun textFrame(payload: String): ByteArray =
        encodeWsFrame(WsOpcode.TEXT, payload.toByteArray(Charsets.UTF_8), masked = false)

    private fun readerFor(vararg frames: ByteArray): Pair<WsFrameReader, ByteArrayOutputStream> {
        val combined = ByteArrayOutputStream()
        frames.forEach { combined.write(it) }
        val out = ByteArrayOutputStream()
        return WsFrameReader(ByteArrayInputStream(combined.toByteArray()), out) to out
    }

    @Test
    fun acceptVector_matchesRfc6455Example() {
        // RFC 6455 §1.3 的例子：key dGhlIHNhbXBsZSBub25jZQ== → s3pPLMBiTxaQ9kYGzzhZRbK+xOo=。
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            computeWsAccept("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    @Test
    fun handshakeRequest_hasUpgradeHeadersAndToken() {
        val req = buildWsHandshakeRequest("192.0.2.10", 18743, "a".repeat(64), "KEY==")
        assertTrue(req.startsWith("GET /events?token=${"a".repeat(64)} HTTP/1.1\r\n"))
        assertTrue(req.contains("Host: 192.0.2.10:18743\r\n"))
        assertTrue(req.contains("Upgrade: websocket\r\n"))
        assertTrue(req.contains("Connection: Upgrade\r\n"))
        assertTrue(req.contains("Sec-WebSocket-Key: KEY==\r\n"))
        assertTrue(req.contains("Sec-WebSocket-Version: 13\r\n"))
    }

    @Test
    fun verifyHandshake_okAndFailures() {
        val key = "test-websocket-nonce"
        val accept = computeWsAccept(key)
        val good = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n"
        assertNull(verifyWsHandshakeResponse(good, key))
        assertNotNull(verifyWsHandshakeResponse("HTTP/1.1 403 Forbidden\r\n\r\n", key))
        assertNotNull(
            verifyWsHandshakeResponse(
                "HTTP/1.1 101 Switching Protocols\r\nSec-WebSocket-Accept: wrong\r\n",
                key,
            ),
        )
        assertNotNull(
            verifyWsHandshakeResponse("HTTP/1.1 101 Switching Protocols\r\n", key),
        )
    }

    @Test
    fun encodeDecodeRoundTrip_maskedAndUnmasked() {
        val text = "hello 事件：example-agent 开始执行任务"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val masked = encodeWsFrame(
            WsOpcode.TEXT, bytes,
            masked = true, maskKey = byteArrayOf(1, 2, 3, 4),
        )
        // 客户端帧必须掩码（第二字节最高位置位）；掩码在 2..5 字节，
        // 手动解掩码后 payload 必须与原文一致。
        assertTrue((masked[1].toInt() and 0x80) != 0)
        assertEquals(1, masked[2].toInt())
        assertEquals(2, masked[3].toInt())
        assertEquals(3, masked[4].toInt())
        assertEquals(4, masked[5].toInt())
        val payload = ByteArray(bytes.size) { i ->
            (masked[6 + i].toInt() xor (masked[2 + i % 4].toInt() and 0xFF)).toByte()
        }
        assertEquals(text, String(payload, Charsets.UTF_8))
        // reader 只读服务端帧（不掩码）：带掩码的帧按协议就该拒收，
        // 所以这里只把不掩码的帧喂给 reader。
        val (reader, _) = readerFor(textFrame(text))
        assertEquals(WsReadResult.Text(text), reader.readMessage())
    }

    @Test
    fun encodeDecodeRoundTrip_extendedLengths() {
        val medium = "x".repeat(200)
        val (r1, _) = readerFor(textFrame(medium))
        assertEquals(WsReadResult.Text(medium), r1.readMessage())
        val large = "y".repeat(70_000)
        val (r2, _) = readerFor(textFrame(large))
        assertEquals(WsReadResult.Text(large), r2.readMessage())
    }

    @Test
    fun fragmentedMessage_reassembled() {
        val first = encodeWsFrame(
            WsOpcode.TEXT, "hello ".toByteArray(Charsets.UTF_8),
            masked = false, fin = false,
        )
        val second = encodeWsFrame(
            WsOpcode.CONTINUATION, "world".toByteArray(Charsets.UTF_8),
            masked = false, fin = true,
        )
        val (reader, _) = readerFor(first, second)
        assertEquals(WsReadResult.Text("hello world"), reader.readMessage())
    }

    @Test
    fun ping_repliesPongAndContinues() {
        val ping = encodeWsFrame(WsOpcode.PING, "abc".toByteArray(Charsets.UTF_8), masked = false)
        val (reader, out) = readerFor(ping, textFrame("hi"))
        assertEquals(WsReadResult.Text("hi"), reader.readMessage())
        // 回的 pong：0x8A（FIN+pong）、掩码位 + 长度 3、4 字节掩码、掩码后 payload。
        val pong = out.toByteArray()
        assertEquals(0x8A, pong[0].toInt() and 0xFF)
        assertEquals(0x83, pong[1].toInt() and 0xFF)
        val mask = pong.copyOfRange(2, 6)
        val payload = ByteArray(3) { i ->
            (pong[6 + i].toInt() xor (mask[i].toInt() and 0xFF)).toByte()
        }
        assertEquals("abc", String(payload, Charsets.UTF_8))
    }

    @Test
    fun closeFrame_returnsClosedAndRepliesClose() {
        val close = encodeWsFrame(WsOpcode.CLOSE, byteArrayOf(0x03, 0xE8.toByte()), masked = false)
        val (reader, out) = readerFor(close)
        assertEquals(WsReadResult.Closed, reader.readMessage())
        val reply = out.toByteArray()
        assertTrue(reply.isNotEmpty())
        assertEquals(0x88, reply[0].toInt() and 0xFF)
    }

    @Test
    fun eof_returnsClosedNotCrash() {
        val (reader, _) = readerFor()
        assertEquals(WsReadResult.Closed, reader.readMessage())
    }

    @Test
    fun oversizedFrame_isProtocolError() {
        // 0x81（FIN+text）、0x7F（64 位长度）、长度 1MB+1；不发 payload，
        // 读到长度就该拒绝，不应等 payload。
        val header = byteArrayOf(
            0x81.toByte(), 0x7F,
            0, 0, 0, 0, 0, 0x10, 0x00, 0x01,
        )
        val (reader, _) = readerFor(header)
        val result = reader.readMessage()
        assertTrue(result is WsReadResult.ProtocolError)
    }

    @Test
    fun maskedServerFrame_isProtocolError() {
        // 服务端帧带掩码是协议违规：0x81、0x80|1、掩码、payload。
        val frame = byteArrayOf(
            0x81.toByte(), 0x81.toByte(),
            1, 2, 3, 4,
            ('x'.code xor 1).toByte(),
        )
        val (reader, _) = readerFor(frame)
        assertTrue(reader.readMessage() is WsReadResult.ProtocolError)
    }

    @Test
    fun unknownOpcode_isProtocolError() {
        val frame = encodeWsFrame(0x3, "x".toByteArray(Charsets.UTF_8), masked = false)
        val (reader, _) = readerFor(frame)
        assertTrue(reader.readMessage() is WsReadResult.ProtocolError)
    }

    @Test
    fun badUtf8_isProtocolErrorNotCrash() {
        val frame = encodeWsFrame(WsOpcode.TEXT, byteArrayOf(0xFF.toByte(), 0xFE.toByte()), masked = false)
        val (reader, _) = readerFor(frame)
        assertTrue(reader.readMessage() is WsReadResult.ProtocolError)
    }

    @Test
    fun continuationWithoutStart_isProtocolError() {
        val frame = encodeWsFrame(
            WsOpcode.CONTINUATION, "x".toByteArray(Charsets.UTF_8),
            masked = false, fin = true,
        )
        val (reader, _) = readerFor(frame)
        assertTrue(reader.readMessage() is WsReadResult.ProtocolError)
    }

    @Test
    fun badToken_neverConnects() {
        val server = ServerSocket(0)
        server.soTimeout = 600
        val port = server.localPort
        val accepted = CountDownLatch(1)
        val acceptedRemote = AtomicReference<String?>(null)
        val acceptThread = Thread {
            try {
                val socket = server.accept()
                acceptedRemote.set(socket.remoteSocketAddress.toString())
                accepted.countDown()
                try { socket.close() } catch (_: Exception) { }
            } catch (_: SocketTimeoutException) {
                // 预期：token 不合法，客户端根本不该建连。
            } catch (_: Exception) {
            } finally {
                try { server.close() } catch (_: Exception) { }
            }
        }
        acceptThread.start()
        ConsoleWsClient("127.0.0.1", port, "not-a-token").run { }
        // 等 accept 线程跑完 600ms 超时窗口再断言（排除时序 flake）。
        acceptThread.join(3_000)
        // CountDownLatch 初始值就是 1：从没连上时 count 仍是 1；
        // 只有真的连上来了（countDown 后）才是 0。
        assertEquals(
            "token 不合法时客户端不应建连，实际连上了",
            1, accepted.count,
        )
    }

    /**
     * 最小测试 WS 服务（回环 socket，不 mock 握手）：
     * 真算 Accept、发一个文本事件 + 一个 ping、读客户端的 pong 与 close。
     */
    @Test
    fun liveLoopback_textPingClose() {
        val server = ServerSocket(0)
        val port = server.localPort
        val eventJson = """{"v":1,"type":"event","event":"agent.run.started","ts":1790000000,"device_id":"remote-device","data":{"task_id":"t1","agent_id":"example-agent"}}"""
        val serverSawPong = CountDownLatch(1)
        val serverSawClose = CountDownLatch(1)
        val serverError = AtomicReference<String?>(null)
        // 超时一律放宽到 30 秒：这台共享 VM 负载经常冲到 6 以上，
        // 线程几秒抢不到 CPU 是常态；本测试验的是协议不是时延。
        // 服务端线程设 daemon：跑偏了也不拖住 JVM 退出。
        val serverThread = Thread {
            var socket: Socket? = null
            try {
                server.soTimeout = 30_000
                socket = server.accept()
                socket.soTimeout = 30_000
                val input = socket.inputStream
                val output = socket.outputStream
                // 读 HTTP 升级请求到 \r\n\r\n。
                val request = readHttpHeaders(input)
                val key = request.lines()
                    .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                    ?: throw IllegalStateException("缺 Sec-WebSocket-Key")
                assertTrue(request.contains("GET /events?token=${"a".repeat(64)} "))
                val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${computeWsAccept(key)}\r\n" +
                    "\r\n"
                output.write(response.toByteArray(Charsets.US_ASCII))
                output.flush()
                // 发文本事件（服务端帧不掩码）+ ping。
                output.write(encodeWsFrame(WsOpcode.TEXT, eventJson.toByteArray(Charsets.UTF_8), masked = false))
                output.write(encodeWsFrame(WsOpcode.PING, "p".toByteArray(Charsets.UTF_8), masked = false))
                output.flush()
                // 读客户端帧：等 pong 与 close（客户端帧带掩码）。
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
        val client = ConsoleWsClient("127.0.0.1", port, "a".repeat(64), readTimeoutMs = 30_000)
        val clientThread = Thread { client.run { received.add(it) } }
        clientThread.start()
        assertTrue("客户端未收到文本事件", waitUntil(30_000) { received.size >= 1 })
        assertEquals(eventJson, received[0])
        client.close()
        clientThread.join(30_000)
        assertTrue("服务端未收到 pong", serverSawPong.await(30, TimeUnit.SECONDS))
        assertTrue("服务端未收到 close", serverSawClose.await(30, TimeUnit.SECONDS))
        assertNull(serverError.get())
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
                window.elementAt(0) == '\r'.code && window.elementAt(1) == '\n'.code &&
                window.elementAt(2) == '\r'.code && window.elementAt(3) == '\n'.code
            ) {
                break
            }
        }
        return buf.toString(Charsets.US_ASCII.name())
    }

    /** 读一帧客户端帧（带掩码），返回 (opcode, 解掩码后 payload)，EOF 返回 null。 */
    private fun readClientFrame(input: InputStream): Pair<Int, ByteArray>? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = b1 and 0x7F
        if (len == 126) {
            val hi = input.read()
            val lo = input.read()
            len = (hi shl 8) or lo
        } else if (len == 127) {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or input.read().toLong()
            len = v.toInt()
        }
        val mask = if (masked) ByteArray(4).also { readExact(input, it) } else null
        val payload = ByteArray(len).also { readExact(input, it) }
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)).toByte()
            }
        }
        return opcode to payload
    }

    private fun readExact(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) throw java.io.EOFException()
            off += r
        }
    }
}
