package wherevercode.agent.core.mcp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 控制台 WebSocket 客户端（V5 控制台实时推送的 App 一半，2026-11-11）。
 *
 * 协议（与 Hand 侧 WS 端点定死的同一份）：
 * - 同端口 18743：`GET /events?token=<64 hex>`，头含 Upgrade: websocket、
 *   Connection: Upgrade、Sec-WebSocket-Key、Sec-WebSocket-Version: 13；
 *   回 101 + Sec-WebSocket-Accept 才算接通（token 无效回 403）。
 * - 帧：客户端发帧必须掩码；服务端帧不掩码；处理分片重组、ping→pong、
 *   close 握手；单条上限 1MB；读超时 30 秒（配合服务端空闲 ping）。
 * - 每帧一个事件 JSON（解析不在这里，在 [wherevercode.agent.ui.ConsoleWsFeed]）。
 *
 * 零新依赖：纯 Kotlin + java.net.Socket 自己写最小客户端（握手 + 帧编解码），
 * 跟 App 里纯 Kotlin X25519（[wherevercode.agent.core.security.DeviceKeys]）
 * 的做法一致。无 Android 依赖，可 JVM 单测。
 *
 * 线程模型：[run] 在调用线程阻塞跑读循环，直到服务端关闭、出错或本地
 * [close] 才返回；调用方（ConsoleWsFeed）每台设备起一个专用线程跑它。
 * [onText] 在读循环线程回调，不切线程——调用方自己决定要不要换线程。
 * 坏帧 / 坏握手 / 超时都只记日志并结束本次连接（返回），绝不抛到调用方、
 * 更不崩溃；重连由 ConsoleWsFeed 的退避循环负责。
 */
interface WsConnection {
    /**
     * 阻塞运行：建连、握手、读帧循环，直到连接结束才返回。
     * 文本帧调 [onText]；二进制帧直接忽略（协议里只有文本事件帧）。
     */
    fun run(onText: (String) -> Unit)

    /** 发 close 帧并关 socket；幂等；会打断正在 [run] 里阻塞的读循环。 */
    fun close()

    /** run() 正在跑且 socket 未关时为 true。 */
    val isOpen: Boolean
}

/** WebSocket 帧操作码（RFC 6455 §5.2、§11.8）。 */
object WsOpcode {
    const val CONTINUATION: Int = 0x0
    const val TEXT: Int = 0x1
    const val BINARY: Int = 0x2
    const val CLOSE: Int = 0x8
    const val PING: Int = 0x9
    const val PONG: Int = 0xA
}

/** 读循环的一次结果：文本 / 二进制 / 干净关闭 / 协议错误。 */
sealed interface WsReadResult {
    data class Text(val text: String) : WsReadResult
    data class Binary(val bytes: ByteArray) : WsReadResult
    /** 对方发了 close 帧（已回 close），或流正常结束（EOF）。 */
    data object Closed : WsReadResult
    /** 协议违规（保留位、未知操作码、超长、掩码错、坏 UTF-8 等）：只记日志断开，不崩溃。 */
    data class ProtocolError(val reason: String) : WsReadResult
}

/** RFC 6455 §4.2.2 的固定 GUID，Sec-WebSocket-Accept = base64(sha1(key + GUID))。 */
const val WS_ACCEPT_GUID: String = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

/** 单条消息上限 1MB（协议定死；超了即协议错误并断开）。 */
const val WS_MAX_MESSAGE_BYTES: Int = 1024 * 1024

/** 握手响应头上限 8KB（只读头，不读体；超了即失败）。 */
private const val MAX_HANDSHAKE_HEADER_BYTES: Int = 8 * 1024

/** 计算 Sec-WebSocket-Accept（RFC 6455 §4.2.2；测试向量见 ConsoleWsClientTest）。 */
fun computeWsAccept(key: String): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest((key + WS_ACCEPT_GUID).toByteArray(Charsets.US_ASCII))
    return Base64.getEncoder().encodeToString(digest)
}

/**
 * 拼 HTTP 升级请求。token 是 64 位 hex，只含 [0-9a-f]，可直接进 query，
 * 不用 URL 编码；不是这个形状的 token 在 [ConsoleWsClient.run] 入口就拒绝。
 */
fun buildWsHandshakeRequest(host: String, port: Int, token: String, key: String): String =
    "GET /events?token=$token HTTP/1.1\r\n" +
        "Host: $host:$port\r\n" +
        "Upgrade: websocket\r\n" +
        "Connection: Upgrade\r\n" +
        "Sec-WebSocket-Key: $key\r\n" +
        "Sec-WebSocket-Version: 13\r\n" +
        "\r\n"

/**
 * 校验握手响应头块（状态行 + 头，`\r\n\r\n` 之前部分）。
 * 成功返回 null；失败返回中文原因（状态行不是 101 / 缺 Accept /
 * Accept 对不上——Accept 对不上说明对方不是按本 key 回的，
 * 可能是串话或中间人，必须拒绝）。
 */
fun verifyWsHandshakeResponse(headerBlock: String, key: String): String? {
    val lines = headerBlock.split("\r\n")
    val statusLine = lines.firstOrNull() ?: return "握手响应为空"
    if (!statusLine.startsWith("HTTP/1.1 101") && !statusLine.startsWith("HTTP/1.0 101")) {
        return "握手失败：服务端未回 101（$statusLine）"
    }
    var accept: String? = null
    for (line in lines.drop(1)) {
        val idx = line.indexOf(':')
        if (idx < 0) continue
        if (line.substring(0, idx).trim().equals("Sec-WebSocket-Accept", ignoreCase = true)) {
            accept = line.substring(idx + 1).trim()
        }
    }
    if (accept == null) return "握手失败：服务端没回 Sec-WebSocket-Accept"
    if (accept != computeWsAccept(key)) return "握手失败：Sec-WebSocket-Accept 对不上（可能串话）"
    return null
}

/**
 * 编一帧（RFC 6455 §5.2）。客户端发帧必须掩码（masked=true 时用随机掩码，
 * 除非调用方为单测传入固定 [maskKey]）；服务端帧不掩码。
 * payload 超 1MB 时抛 IllegalArgumentException（调用方不得发出超长帧）。
 */
fun encodeWsFrame(
    opcode: Int,
    payload: ByteArray,
    masked: Boolean,
    maskKey: ByteArray? = null,
    fin: Boolean = true,
): ByteArray {
    require(opcode in 0..0xF) { "ws_bad_opcode" }
    require(payload.size <= WS_MAX_MESSAGE_BYTES) { "ws_frame_too_large" }
    val out = ByteArrayOutputStream()
    out.write((if (fin) 0x80 else 0x00) or opcode) // FIN 位 + RSV 全 0。
    val mask = if (masked) {
        maskKey ?: ByteArray(4).also { SecureRandom().nextBytes(it) }
    } else {
        null
    }
    require(mask == null || mask.size == 4) { "ws_bad_mask" }
    val len = payload.size
    when {
        len < 126 -> out.write((if (masked) 0x80 else 0x00) or len)
        len < 65536 -> {
            out.write((if (masked) 0x80 else 0x00) or 126)
            out.write(len shr 8)
            out.write(len and 0xFF)
        }
        else -> {
            out.write((if (masked) 0x80 else 0x00) or 127)
            // 64 位长度必须用 Long 算：Int 的 shr 只取位移的低 5 位，
            // `len shr 56` 会变成 `len shr 24`，长度字节全错（70KB 帧曾因此被拒）。
            val longLen = len.toLong()
            for (shift in 56 downTo 0 step 8) out.write(((longLen shr shift) and 0xFF).toInt())
        }
    }
    if (mask != null) {
        out.write(mask)
        for (i in payload.indices) out.write((payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)) and 0xFF)
    } else {
        out.write(payload)
    }
    return out.toByteArray()
}

/**
 * 帧读取器：从 [input] 读完整消息（分片重组），控制帧就地处理。
 * - ping → 立即回 pong（写 [output]，与 ping 同 payload），继续读；
 * - pong → 丢弃，继续读；
 * - close → 回一个 close 帧，返回 [WsReadResult.Closed]；
 * - 文本/二进制分片 → 攒满一个 FIN 才返回一次结果；
 * - 协议违规 → 返回 [WsReadResult.ProtocolError]（不抛，调用方记日志断开）。
 * 服务端帧必须不掩码（掩码的服务端帧是协议违规）；控制帧必须 FIN=1 且
 * payload ≤ 125 字节；文本帧按 UTF-8 解，坏 UTF-8 是协议错误。
 */
class WsFrameReader(
    private val input: InputStream,
    private val output: OutputStream? = null,
) {
    fun readMessage(): WsReadResult {
        var messageOpcode = -1
        val fragments = ByteArrayOutputStream()
        while (true) {
            val frame = try {
                readRawFrame()
            } catch (e: EOFException) {
                return WsReadResult.Closed
            } catch (e: IOException) {
                // 读超时（SocketTimeoutException 是 IOException）与 IO 错都
                // 在这里收敛：调用方按断开处理（记日志、重连），不崩溃。
                return WsReadResult.ProtocolError("读帧失败：${e.message}")
            }
            if (frame is WsReadResult.ProtocolError || frame is WsReadResult.Closed) return frame
            val raw = (frame as WsRawFrame)
            when (raw.opcode) {
                WsOpcode.PING -> {
                    // 控制帧之间不许插分片：ping 出现在分片中间是协议违规。
                    if (messageOpcode != -1) {
                        return WsReadResult.ProtocolError("ping 插在分片消息中间")
                    }
                    try {
                        output?.write(encodeWsFrame(WsOpcode.PONG, raw.payload, masked = true))
                        output?.flush()
                    } catch (_: Exception) {
                        return WsReadResult.ProtocolError("回 pong 失败")
                    }
                    continue
                }
                WsOpcode.PONG -> {
                    if (messageOpcode != -1) {
                        return WsReadResult.ProtocolError("pong 插在分片消息中间")
                    }
                    continue
                }
                WsOpcode.CLOSE -> {
                    try {
                        output?.write(encodeWsFrame(WsOpcode.CLOSE, CLOSE_NORMAL, masked = true))
                        output?.flush()
                    } catch (_: Exception) {
                        // 回 close 失败也照常关闭，不另报错。
                    }
                    return WsReadResult.Closed
                }
                WsOpcode.TEXT, WsOpcode.BINARY -> {
                    if (messageOpcode != -1) {
                        return WsReadResult.ProtocolError("新消息插在分片消息中间")
                    }
                    if (!raw.fin) {
                        messageOpcode = raw.opcode
                        fragments.write(raw.payload)
                        continue
                    }
                    return finishMessage(raw.opcode, raw.payload)
                }
                WsOpcode.CONTINUATION -> {
                    if (messageOpcode == -1) {
                        return WsReadResult.ProtocolError("没有开头就收到 continuation 帧")
                    }
                    if (fragments.size() + raw.payload.size > WS_MAX_MESSAGE_BYTES) {
                        return WsReadResult.ProtocolError("消息超过 1MB 上限")
                    }
                    fragments.write(raw.payload)
                    if (!raw.fin) continue
                    val bytes = fragments.toByteArray()
                    val opcode = messageOpcode
                    messageOpcode = -1
                    return finishMessage(opcode, bytes)
                }
                else -> return WsReadResult.ProtocolError("未知操作码 ${raw.opcode}")
            }
        }
    }

    private fun finishMessage(opcode: Int, bytes: ByteArray): WsReadResult =
        if (opcode == WsOpcode.TEXT) {
            // String(bytes, UTF_8) 会把坏字节换成 U+FFFD 而不抛错：
            // 先严格校验一次，坏 UTF-8 不进事件流。
            if (!isValidUtf8(bytes)) {
                WsReadResult.ProtocolError("文本帧不是合法 UTF-8")
            } else {
                WsReadResult.Text(String(bytes, Charsets.UTF_8))
            }
        } else {
            WsReadResult.Binary(bytes)
        }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        // 用 CharsetDecoder 的严格模式，不用 U+FFFD 替换。
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 一帧原始内容（调用方只关心 opcode/fin/payload；校验已在此做）。 */
    private data class WsRawFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray) :
        WsReadResult

    private fun readRawFrame(): WsReadResult {
        val b0 = readByteOrEof() ?: throw EOFException()
        val b1 = readByteOrEof() ?: throw EOFException()
        val fin = (b0 and 0x80) != 0
        val rsv = b0 and 0x70
        if (rsv != 0) return WsReadResult.ProtocolError("保留位 RSV 非 0（不支持扩展）")
        val opcode = b0 and 0x0F
        if (opcode !in setOf(
                WsOpcode.CONTINUATION, WsOpcode.TEXT, WsOpcode.BINARY,
                WsOpcode.CLOSE, WsOpcode.PING, WsOpcode.PONG,
            )
        ) {
            return WsReadResult.ProtocolError("未知操作码 $opcode")
        }
        val masked = (b1 and 0x80) != 0
        // 服务端帧不掩码：掩码的服务端帧直接拒收（RFC 6455 §5.1）。
        if (masked) return WsReadResult.ProtocolError("服务端帧带掩码（协议违规）")
        val lenCode = b1 and 0x7F
        val length: Long = when (lenCode) {
            126 -> ((readByteOrEof() ?: throw EOFException()).toLong() shl 8) or
                (readByteOrEof() ?: throw EOFException()).toLong()
            127 -> {
                var v = 0L
                for (i in 0 until 8) {
                    v = (v shl 8) or (readByteOrEof() ?: throw EOFException()).toLong()
                }
                // 最高位是 64 位长度的符号位：置位即非法长度。
                if (v < 0) return WsReadResult.ProtocolError("帧长度非法")
                v
            }
            else -> lenCode.toLong()
        }
        val isControl = opcode >= WsOpcode.CLOSE
        if (isControl && !fin) return WsReadResult.ProtocolError("控制帧被分片")
        if (isControl && length > 125) return WsReadResult.ProtocolError("控制帧超过 125 字节")
        if (length > WS_MAX_MESSAGE_BYTES) {
            return WsReadResult.ProtocolError("帧超过 1MB 上限")
        }
        val payload = readExact(length.toInt())
        return WsRawFrame(fin, opcode, payload)
    }

    private fun readByteOrEof(): Int? {
        val b = input.read()
        return if (b < 0) null else b
    }

    private fun readExact(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return buf
    }

    companion object {
        /** close 帧 payload：状态码 1000（正常关闭），无 reason。 */
        val CLOSE_NORMAL: ByteArray = byteArrayOf(0x03, 0xE8.toByte())
    }
}

class ConsoleWsClient(
    private val host: String,
    private val port: Int,
    private val token: String,
    /** 建连超时 5 秒；读超时 30 秒（配合服务端空闲 ping，见协议）。 */
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
    private val onLog: (String) -> Unit = {},
) : WsConnection {

    companion object {
        val TOKEN_REGEX: Regex = Regex("^[0-9a-f]{64}$")
    }

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var closed = false

    @Volatile
    private var running = false

    override val isOpen: Boolean get() = running && !closed && socket?.isClosed == false

    override fun run(onText: (String) -> Unit) {
        if (!TOKEN_REGEX.matches(token)) {
            onLog("ConsoleWs: token 不是 64 位 hex，拒绝连接 $host:$port")
            return
        }
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        var sock: Socket? = null
        try {
            sock = Socket()
            try {
                sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (e: SocketTimeoutException) {
                // 连接超时单独记：它不是“读不到帧”，是 TCP 根本没通。
                onLog("ConsoleWs: 连接超时 $host:$port（${connectTimeoutMs}ms 内没连上）")
                return
            }
            sock.soTimeout = readTimeoutMs
            socket = sock
            running = true
            val input = sock.inputStream.buffered()
            val output = sock.outputStream.buffered()
            output.write(buildWsHandshakeRequest(host, port, token, key).toByteArray(Charsets.US_ASCII))
            output.flush()
            val headerBlock = readHandshakeHeaders(input)
            if (headerBlock == null) {
                onLog("ConsoleWs: 握手响应头超过 ${MAX_HANDSHAKE_HEADER_BYTES}B 或连接已断")
                return
            }
            verifyWsHandshakeResponse(headerBlock, key)?.let { reason ->
                onLog("ConsoleWs: $reason（$host:$port）")
                return
            }
            onLog("ConsoleWs: 已接通 $host:$port")
            val reader = WsFrameReader(input, output)
            while (!closed) {
                when (val result = reader.readMessage()) {
                    is WsReadResult.Text -> {
                        try {
                            onText(result.text)
                        } catch (e: Exception) {
                            // 事件回调抛错不能断了读循环：记日志继续读。
                            onLog("ConsoleWs: 事件回调失败：${e.message}")
                        }
                    }
                    is WsReadResult.Binary -> {
                        // 协议里只有文本事件帧；二进制帧忽略并记日志。
                        onLog("ConsoleWs: 收到二进制帧（${result.bytes.size}B），已忽略")
                    }
                    is WsReadResult.Closed -> {
                        onLog("ConsoleWs: 对方关闭连接 $host:$port")
                        return
                    }
                    is WsReadResult.ProtocolError -> {
                        onLog("ConsoleWs: 协议错误，断开 $host:$port：${result.reason}")
                        return
                    }
                    // readMessage() 永远不会返回 WsRawFrame（它是 reader 内部
                    // 的中间类型）；else 只为满足密封接口的穷尽检查。
                    else -> onLog("ConsoleWs: 收到意外的内部帧类型，已忽略")
                }
            }
        } catch (e: SocketTimeoutException) {
            // 读超时（soTimeout）：这么久无任何帧（含服务端空闲 ping），
            // 连接已死，按断开处理，由 ConsoleWsFeed 重连；不是崩溃。
            // 文案写真实的 readTimeoutMs，不硬写 30 秒。
            onLog("ConsoleWs: 读超时 ${readTimeoutMs}ms 内无帧，连接断开 $host:$port")
        } catch (e: IOException) {
            if (!closed) onLog("ConsoleWs: 连接断开 $host:$port：${e.message}")
        } catch (e: Exception) {
            onLog("ConsoleWs: 连接异常断开 $host:$port：${e.message}")
        } finally {
            running = false
            try {
                sock?.close()
            } catch (_: Exception) {
            }
            socket = null
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // 先发 close 帧（客户端帧必须掩码），再关 socket 打断阻塞的读循环。
        // 读循环可能正在用 output，写失败就忽略——关 socket 本身就够了。
        try {
            val sock = socket
            if (sock != null && !sock.isClosed) {
                val out = sock.outputStream
                out.write(encodeWsFrame(WsOpcode.CLOSE, WsFrameReader.CLOSE_NORMAL, masked = true))
                out.flush()
            }
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    /** 读到 `\r\n\r\n` 为止的响应头；超长或 EOF 返回 null。 */
    private fun readHandshakeHeaders(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        val window = ArrayDeque<Int>()
        while (buf.size() < MAX_HANDSHAKE_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            window.addLast(b)
            if (window.size > 4) window.removeFirst()
            if (window.size == 4 &&
                window.elementAt(0) == '\r'.code && window.elementAt(1) == '\n'.code &&
                window.elementAt(2) == '\r'.code && window.elementAt(3) == '\n'.code
            ) {
                return buf.toString(Charsets.US_ASCII.name())
            }
        }
        return null
    }
}
