package wherevercode.agent.core.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import wherevercode.agent.core.mcp.WsConnection
import wherevercode.agent.core.mcp.WsFrameReader
import wherevercode.agent.core.mcp.WsReadResult
import wherevercode.agent.core.mcp.encodeWsFrame
import wherevercode.agent.core.mcp.verifyWsHandshakeResponse

/**
 * V5 频道 WebSocket 客户端（P3，2026-11-11）。
 *
 * 订阅端点 `GET /api/open/v1/channel/ws?channel_id=...`（Bearer 鉴权，
 * 若本地构建配置含匹配主机 pin 则叠加 pin 校验，否则使用系统默认
 * TLS 工厂；SSLSocket 始终启用 HTTPS 主机名校验。
 *
 * 帧编解码复用控制台 WS 的同一套（[WsFrameReader] / [encodeWsFrame] /
 * [verifyWsHandshakeResponse]，同一协议族，不另造一轮）：ping→pong、
 * close 握手、分片重组都在那里。单条上限 1MB。
 *
 * 线程模型与 [wherevercode.agent.core.mcp.ConsoleWsClient] 同款：
 * [run] 在调用线程阻塞跑读循环，直到服务端关闭、出错或本地 [close]
 * 才返回；[onText] 在读循环线程回调，不切线程——调用方
 * （[ChannelFeed]）自己决定要不要换线程。坏帧 / 坏握手 / 超时都只
 * 记在返回原因里，绝不抛到调用方、更不崩溃；重连由 ChannelFeed 负责。
 * 令牌只进 Authorization 请求头，永不进日志——握手请求字符串本身
 * 含令牌，调用方绝不能把它记日志。
 */
class ChannelWsClient(
    private val host: String,
    private val port: Int = 443,
    private val channelId: String,
    private val tokenProvider: () -> String?,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val socketFactory: (String, Int) -> Socket = { h, p ->
        val pin = PlatformEndpointPinning.configuredPin
        val factory = if (PlatformEndpointPinning.isPinnedHost(h, pin) && pin != null) {
            PlatformEndpointPinning.socketFactory(pin)
        } else {
            SSLSocketFactory.getDefault()
        }
        (factory.createSocket() as SSLSocket).also {
            it.connect(InetSocketAddress(h, p), connectTimeoutMs)
        }
    },
) : WsConnection {

    @Volatile
    private var socket: Socket? = null

    override val isOpen: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    override fun run(onText: (String) -> Unit) {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
        if (token == null) {
            onClosed("未配置平台凭证。")
            return
        }
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also {
            SecureRandom().nextBytes(it)
        })
        val sock = try {
            socketFactory(host, port).also { socket = it }
        } catch (e: Exception) {
            onClosed("连接失败：${(e.message ?: e.javaClass.simpleName).take(200)}")
            return
        }
        try {
            sock.soTimeout = readTimeoutMs
            if (sock is SSLSocket) {
                sock.sslParameters = sock.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                sock.startHandshake()
            }
            val out = sock.getOutputStream()
            out.write(buildHandshakeRequest(host, port, channelId, token, key).toByteArray(Charsets.US_ASCII))
            out.flush()
            val headerBlock = readHandshakeHeaders(sock)
            if (headerBlock == null) {
                onClosed("握手失败：服务端没有回响应头。")
                return
            }
            verifyWsHandshakeResponse(headerBlock, key)?.let { reason ->
                onClosed(reason)
                return
            }
            val reader = WsFrameReader(sock.getInputStream(), out)
            while (true) {
                when (val result = reader.readMessage()) {
                    is WsReadResult.Text -> onText(result.text)
                    is WsReadResult.Binary -> Unit // 频道协议里只有文本事件帧
                    is WsReadResult.Closed -> {
                        onClosed("频道连接已关闭。")
                        return
                    }
                    is WsReadResult.ProtocolError -> {
                        onClosed(result.reason)
                        return
                    }
                    // readMessage() 永远不会返回 WsRawFrame（reader 内部类型，
                    // 见 ConsoleWsClient 的注释）；else 只为满足密封类型的穷尽检查。
                    else -> Unit
                }
            }
        } catch (e: SocketTimeoutException) {
            onClosed("频道连接超时（30 秒无数据）。")
        } catch (e: IOException) {
            onClosed("频道连接断开：${(e.message ?: e.javaClass.simpleName).take(200)}")
        } catch (e: Exception) {
            onClosed("频道连接异常：${(e.message ?: e.javaClass.simpleName).take(200)}")
        } finally {
            try {
                sock.close()
            } catch (_: Exception) {
            }
            if (socket === sock) socket = null
        }
    }

    /**
     * 本次连接结束的原因（ChannelFeed 用它决定是报“已断开”还是静默重连）。
     * 默认空实现：只关心 onText 的调用方不用管它；ChannelFeed 会覆盖。
     */
    var onClosed: (String) -> Unit = {}

    override fun close() {
        val sock = socket ?: return
        try {
            sock.getOutputStream().write(
                encodeWsFrame(0x8, byteArrayOf(), masked = true),
            )
            sock.getOutputStream().flush()
        } catch (_: Exception) {
        }
        try {
            sock.close()
        } catch (_: Exception) {
        }
    }

    private fun readHandshakeHeaders(sock: Socket): String? {
        val input = sock.getInputStream()
        val buf = ByteArrayOutputStream()
        val window = ByteArray(1)
        while (buf.size() < MAX_HANDSHAKE_HEADER_BYTES) {
            val read = input.read(window)
            if (read < 0) return null
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
        return null
    }

    companion object {
        private const val MAX_HANDSHAKE_HEADER_BYTES: Int = 8 * 1024

        /** 拼频道订阅的 HTTP 升级请求（token 只进 Authorization 头）。 */
        fun buildHandshakeRequest(
            host: String,
            port: Int,
            channelId: String,
            token: String,
            key: String,
        ): String =
            "GET /api/open/v1/channel/ws?channel_id=$channelId HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Authorization: Bearer $token\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "\r\n"
    }
}
