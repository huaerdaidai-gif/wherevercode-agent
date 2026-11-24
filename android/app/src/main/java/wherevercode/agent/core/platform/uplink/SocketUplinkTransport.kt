package wherevercode.agent.core.platform.uplink

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocketFactory
import wherevercode.agent.core.mcp.WsFrameReader
import wherevercode.agent.core.mcp.WsOpcode
import wherevercode.agent.core.mcp.WsReadResult
import wherevercode.agent.core.mcp.encodeWsFrame
import wherevercode.agent.core.mcp.verifyWsHandshakeResponse

/**
 * 基于裸 Socket 的 WS 传输（复用 core/mcp 的 RFC 6455 帧编解码，不引新依赖）。
 * ws:// 与 wss:// 都支持（开发期指本地 mock 用 ws://）；握手带 Bearer Token。
 * 读超时 = 2.5 个心跳周期：ping/pong 照常刷新读，超时即视为断线触发重连。
 */
class SocketUplinkTransport(
    private val config: UplinkConfig,
    private val connectTimeoutMs: Int = 10_000,
) : UplinkTransport {
    override fun connect(): UplinkConnection {
        val uri = URI(config.url)
        val secure = when (uri.scheme?.lowercase()) {
            "wss" -> true
            "ws" -> false
            else -> throw IllegalArgumentException("上行地址必须是 ws:// 或 wss://：${config.url}")
        }
        val host = uri.host ?: throw IllegalArgumentException("上行地址缺主机：${config.url}")
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val plain = Socket()
        plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
        val socket: Socket = if (secure) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true)
        } else {
            plain
        }
        try {
            socket.soTimeout = (config.heartbeatMs * 5 / 2).coerceAtLeast(1_000L).toInt()
            val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = Base64.getEncoder().encodeToString(keyBytes)
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Authorization: Bearer ${config.token}\r\n" +
                "\r\n"
            val out = socket.getOutputStream()
            out.write(request.toByteArray(Charsets.US_ASCII))
            out.flush()
            val header = readHeader(socket)
            verifyWsHandshakeResponse(header, key)?.let { throw java.io.IOException(it) }
            return SocketConnection(socket)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun readHeader(socket: Socket): String {
        val input = socket.getInputStream()
        val buf = ByteArrayOutputStream()
        while (buf.size() < 8 * 1024) {
            val b = input.read()
            if (b < 0) throw java.io.IOException("握手中连接被关闭")
            buf.write(b)
            val n = buf.size()
            if (n >= 4) {
                val a = buf.toByteArray()
                if (a[n - 4] == '\r'.code.toByte() && a[n - 3] == '\n'.code.toByte() &&
                    a[n - 2] == '\r'.code.toByte() && a[n - 1] == '\n'.code.toByte()
                ) {
                    return String(a, 0, n - 4, Charsets.US_ASCII)
                }
            }
        }
        throw java.io.IOException("握手响应头过长")
    }

    private class SocketConnection(private val socket: Socket) : UplinkConnection {
        private val lock = Any()
        private val rawOut: OutputStream = socket.getOutputStream()

        /** 写与读线程（回 pong）共用，整帧一次写，加锁互斥。 */
        private val syncOut = object : OutputStream() {
            override fun write(b: Int) = synchronized(lock) { rawOut.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = synchronized(lock) { rawOut.write(b, off, len) }
            override fun flush() = synchronized(lock) { rawOut.flush() }
        }
        private val reader = WsFrameReader(socket.getInputStream(), syncOut)

        private fun send(opcode: Int, payload: ByteArray): Boolean = try {
            val frame = encodeWsFrame(opcode, payload, masked = true)
            synchronized(lock) {
                rawOut.write(frame)
                rawOut.flush()
            }
            true
        } catch (_: Exception) {
            false
        }

        override fun sendText(text: String) = send(WsOpcode.TEXT, text.toByteArray(Charsets.UTF_8))
        override fun sendPing() = send(WsOpcode.PING, ByteArray(0))

        override fun readText(): String? {
            while (true) {
                when (val r = reader.readMessage()) {
                    is WsReadResult.Text -> return r.text
                    is WsReadResult.Binary -> continue
                    else -> return null
                }
            }
        }

        override fun close() {
            runCatching { send(WsOpcode.CLOSE, byteArrayOf(0x03, 0xE8.toByte())) }
            runCatching { socket.close() }
        }
    }
}
