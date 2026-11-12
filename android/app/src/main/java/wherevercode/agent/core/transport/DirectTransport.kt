package wherevercode.agent.core.transport

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阶段 B（V5 紧急开发）：直连远程 Hand 的传输。
 *
 * 事实：K70 与远程设备 在同一 Tailnet 上，App 可直接向 VPS 的
 * Tailscale IP 发起 TCP 连接（Android 侧不需要 Tailscale SDK）。
 * 本传输就是这条直连：明文 TCP（Tailnet 私网内）+ 与 [LanTransport]
 * 相同的线格式（4 字节大端长度 + 负载，见 [writeFrame]/[readFrame]）。
 * 认证与加密不在 TCP 层：信封本身由 [wherevercode.agent.core.security.DeviceKeys]
 * 密封（X25519 会话密钥 + AES-256-GCM），任务凭证另做 HMAC 绑定；
 * 明文 TCP 上即使有人能看到字节，也打不开信封、伪造不了调用。
 *
 * 与 [LanTransport] 的区别：
 * - 无 mDNS：远程设备是用户手动添加的（[RemoteDeviceStore]），
 *   [discover] 永远返回空列表，不伪造发现结果。
 * - 无 TLS：VPS 侧 Hand 服务端是自研 Python 服务（server/hand_server.py），
 *   不做证书 pinning；对端身份由配对时核对的公钥指纹锚定
 *   （[fetchHello] 拿到的公钥须与配对记录一致，否则 HandClient
 *   的会话密钥协商失败，调用直接拒绝）。
 *
 * Hello 握手（明文，不含任何秘密，只交换公钥与 Agent 清单）：
 * 客户端发 `{"type":"wherevercode_hello","version":1,
 * "device_id":本机id,"public_key_b64":本机X25519公钥}`；
 * 服务端回 `{"type":"wherevercode_hello","version":1,"device_id":…,
 * "display_name":…,"public_key_b64":…,"agents":[{agent_id,
 * display_name,kind,capabilities,folder}]}`。
 * Hello 只用于“列出 VPS 上真实存在的 Agent”与配对时的指纹核对，
 * 绝不执行任何命令；执行一律走密封的 Hand 信封。
 *
 * V5 修复（2026-11-11，Grok/CodeBuddy 对话“空响应”）：hello 不只在
 * [fetchHello] 探测时发——服务端（server/hand_server.py）每条连接
 * 都先读 hello 帧再读密封信封，所以每次调用（[TransportConnection.send]）
 * 之前都必须先发 hello（[TransportConnection.sendHello]），否则服务端
 * 把密封信封当 hello 解析失败、静默关闭连接，App 侧零帧回包即“空响应”。
 * 调用连接上的 hello 回包由 [DirectTransportConnection] 跳过并核对
 * （设备 id 与公钥指纹须与配对记录一致），只把密封帧交给上层。
 *
 * V5 公网直连（2026-11-11，maintainer 点名通信走美国公网 IP）：
 * [DiscoveredPeer.host] 是首选地址（填了公网直连地址的设备，
 * host 就是公网地址），[DiscoveredPeer.fallbackHost] 是回落地址
 * （原内网/Tailnet 地址）。建连只在 TCP 连接阶段回落：首选超时/
 * 拒绝时自动试回落地址，成功则继续本次调用，失败则两路的错误都
 * 报出来；回落发生时记 Log.w，不静默。连上之后 hello/密封失败
 * 不回落——对方已应答，回落到同一台 Hand 没有意义，还会掩盖
 * 配对/鉴权错误（未配对在公网接口上同样被拒，见服务端
 * cross_device_not_authorized）。
 */
data class RemoteAgentInfo(
    val agentId: String,
    val displayName: String,
    val kind: String,
    val capabilities: List<String> = emptyList(),
    /** 该 Agent 在远端的工作文件夹（任务凭证绑定的 folder）。 */
    val folder: String = "",
    /** 服务端核查过该 Agent 的执行入口是否存在（脚本在不在）。 */
    val available: Boolean = true,
    /**
     * P1 能力卡（2026-11-11）：Hand 广播的工作文件夹 git 检出信息
     * （分支 + 短 commit）。旧版服务端不发此字段时为空（未知，不编造）；
     * 不是 git 仓库时服务端发空数组，同样为空。
     */
    val repos: List<HelloRepoCheckout> = emptyList(),
)

/** Hand hello 里一个 git 检出的实测值（分支/commit 缺失时为 null）。 */
data class HelloRepoCheckout(
    val path: String,
    val branch: String?,
    val commit: String?,
)

data class RemoteHello(
    val deviceId: String,
    val displayName: String,
    val publicKey: ByteArray,
    val agents: List<RemoteAgentInfo>,
    /**
     * 本机在远端 Hand 上的配对状态（服务端 hello 回包的 client_status）。
     * "APPROVED" = 已批准；"PENDING" = 远端尚未批准此手机（hello 通、
     * 但 run 调用会被拒）；"" = 旧版服务端没给，不知道。V5 修复
     * （2026-11-11，hello 通、run 就 reset）：探测不能只看 hello
     * 通不通，必须看这个状态，否则 PENDING 也会被标“远端可用”。
     */
    val clientStatus: String = "",
    /**
     * Hand 自更新（2026-11-12）：远端 Hand 的代码版本（文件内容
     * SHA-256 前 12 位，与 App 内置安装包同款定义）。"" = 旧版
     * 服务端没给，不知道（不得当成最新、也不得当成落后）。
     */
    val handVersion: String = "",
    /** 远端 Hand 是否支持密封自更新（update_hand 动作）。旧版为 false。 */
    val updateSupported: Boolean = false,
)

class DirectTransport(
    /**
     * 回落发生时的通知（V5 公网直连，2026-11-11）：默认记 Log.w。
     * 单测可注入记录器断言回落被如实记录——plain JVM 单测上
     * android.util.Log 是 stub，直接调会抛 RuntimeException，
     * 所以默认实现也包一层 try/catch：回落本身已成功，不因日志
     * 写不进而失败；真机上 Log.w 正常写 logcat。
     */
    private val onFallback: ((String) -> Unit)? = null,
) : TransportAdapter {

    companion object {
        private const val TAG = "DirectTransport"
        const val CONNECT_TIMEOUT_MS = 5_000

        /**
         * hello 探测的读超时（30 秒）：hello 是立即回包的短握手，
         * 30 秒内无回包即判定对方不是 Hand 服务端或已离线。
         */
        const val READ_TIMEOUT_MS = 30_000

        /**
         * V5 修复（2026-11-11，CodeBuddy“在吗”两次“传输中断：
         * Read timed out”）：远端 Agent 调用连接的读超时（3 分钟）。
         * 服务端只在 CLI 出 stdout 行时才发进度帧，CodeBuddy 冷启动
         * 约 43 秒无任何输出——30 秒读超时在首帧到达前就断了连接，
         * Grok 回得快所以只断 CodeBuddy。3 分钟是冷启动 43 秒的
         * 4 倍以上余量，仍远低于服务端执行超时（300 秒）与任务凭证
         * TTL（900 秒），死连接仍能被发现。只放宽调用连接；
         * hello 探测仍用 [READ_TIMEOUT_MS]（30 秒），保持快速失败。
         */
        const val CALL_READ_TIMEOUT_MS = 180_000

        /** VPS 侧 Hand 服务端的默认端口（server/hand_server.py --port 默认值）。 */
        const val HAND_PORT = 18743

        const val MAX_FRAME_BYTES = LanTransport.MAX_FRAME_BYTES
        const val HELLO_TYPE = "wherevercode_hello"
        const val HELLO_VERSION = 1

        private val AGENT_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")

        /**
         * 严格解析 hello 回包。任何一项不合规都抛 [IllegalArgumentException]
         *（中文原因），调用方不得把半截结果当成已发现的设备。
         * [expectedDeviceId] 是添加记录里的设备 id：回包的 device_id
         * 必须与它一致，否则拒绝（防串话/冒名）。
         */
        fun parseHello(json: String, expectedDeviceId: String?): RemoteHello {
            val obj = try {
                JSONObject(json)
            } catch (_: Exception) {
                throw IllegalArgumentException("远端 hello 不是合法 JSON：对方可能不是 Hand 服务端")
            }
            if (obj.optString("type", "") != HELLO_TYPE) {
                throw IllegalArgumentException("远端 hello 类型不对：对方可能不是 Hand 服务端")
            }
            if (obj.optInt("version", -1) != HELLO_VERSION) {
                throw IllegalArgumentException("远端 hello 版本不受支持（期望 $HELLO_VERSION）")
            }
            val deviceId = obj.optString("device_id", "").trim()
            if (deviceId.isEmpty()) {
                throw IllegalArgumentException("远端 hello 缺少 device_id")
            }
            // expectedDeviceId 为 null = 添加设备时的首次探测：还不知道
            // 对方 device_id，只校验类型/版本/公钥，不校验 id 一致。
            if (expectedDeviceId != null && deviceId != expectedDeviceId) {
                throw IllegalArgumentException(
                    "远端设备 id（$deviceId）与添加记录（$expectedDeviceId）不一致：可能连错了设备",
                )
            }
            val publicKey = try {
                val raw = obj.optString("public_key_b64", "")
                if (raw.isBlank()) throw IllegalArgumentException("远端 hello 缺少公钥")
                java.util.Base64.getDecoder().decode(raw)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (_: Exception) {
                throw IllegalArgumentException("远端 hello 的公钥不是合法 Base64")
            }
            if (publicKey.size != 32) {
                throw IllegalArgumentException("远端 hello 的公钥不是 32 字节：对方密钥无效")
            }
            val agents = mutableListOf<RemoteAgentInfo>()
            val array = obj.optJSONArray("agents")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val agentId = entry.optString("agent_id", "").trim()
                    if (!AGENT_ID_REGEX.matches(agentId)) continue
                    val displayName = entry.optString("display_name", "").trim()
                    if (displayName.isEmpty()) continue
                    val caps = mutableListOf<String>()
                    val capsArray = entry.optJSONArray("capabilities")
                    if (capsArray != null) {
                        for (j in 0 until capsArray.length()) {
                            val cap = capsArray.optString(j, "").trim()
                            if (cap.isNotEmpty()) caps += cap
                        }
                    }
                    agents += RemoteAgentInfo(
                        agentId = agentId,
                        displayName = displayName,
                        kind = entry.optString("kind", "cli").trim().ifBlank { "cli" },
                        capabilities = caps,
                        folder = entry.optString("folder", "").trim(),
                        available = entry.optBoolean("available", true),
                        // P1 能力卡：repos 是可选 enrichment——一条坏记录
                        // 绝不能让整个 hello 失败，只跳过该条。
                        repos = parseHelloRepos(entry.optJSONArray("repos")),
                    )
                }
            }
            return RemoteHello(
                deviceId = deviceId,
                displayName = obj.optString("display_name", deviceId).trim().ifBlank { deviceId },
                publicKey = publicKey,
                agents = agents,
                // V5 修复（2026-11-11，hello 通、run 就 reset）：服务端
                // 在 hello 回包里给本机的配对状态；旧版服务端没这字段
                // 时 optString 给 ""（不知道），调用方不得把“不知道”
                // 当成已批准、也不得当成未批准。
                clientStatus = obj.optString("client_status", "").trim(),
                // Hand 自更新（2026-11-12）：服务端报自己的代码版本
                // （文件内容 SHA-256 前 12 位）与是否支持自更新；
                // 旧版服务端没这两个字段时按 "" / false（不知道）。
                handVersion = obj.optString("hand_version", "").trim(),
                updateSupported = obj.optBoolean("update_supported", false),
            )
        }

        /**
         * P1 能力卡：解析 hello 里一个 Agent 的 "repos" 数组。
         * 形状 `[{"path","branch","commit"}]`；path 为空或整条不是
         * 对象时跳过该条；branch/commit 缺失 = 未知（null），不编造。
         * 整个字段缺失（旧版服务端）时返回空列表。绝不抛异常。
         */
        private fun parseHelloRepos(array: org.json.JSONArray?): List<HelloRepoCheckout> {
            if (array == null) return emptyList()
            val out = mutableListOf<HelloRepoCheckout>()
            for (i in 0 until array.length()) {
                val repo = try {
                    array.optJSONObject(i)
                } catch (_: Exception) {
                    null
                } ?: continue
                val path = repo.optString("path", "").trim()
                if (path.isEmpty()) continue
                out += HelloRepoCheckout(
                    path = path,
                    branch = repo.optString("branch", "").trim().ifBlank { null },
                    commit = repo.optString("commit", "").trim().ifBlank { null },
                )
            }
            return out
        }
    }

    override suspend fun discover(timeoutMs: Long): List<DiscoveredPeer> = emptyList()

    override suspend fun connect(peer: DiscoveredPeer): TransportConnection {
        val socket = withContext(Dispatchers.IO) {
            try {
                // 调用连接：远端 Agent 执行（冷启动可达 43 秒以上）期间
                // 无帧是正常的，用 CALL_READ_TIMEOUT_MS（3 分钟），
                // 不是 hello 探测的 30 秒（见 CALL_READ_TIMEOUT_MS 注释）。
                connectWithFallback(peer, CALL_READ_TIMEOUT_MS)
            } catch (e: IOException) {
                throw IOException("连接 ${peer.displayName}（${peer.host}:${peer.port}）失败：${e.message}，请检查设备在线且 Hand 在运行", e)
            }
        }
        return DirectTransportConnection(socket, peer)
    }

    /**
     * 建连（带回落）：先连首选地址；只有 TCP 连接本身抛
     * [IOException]（超时/拒绝）且有回落地址时，才试回落地址。
     * 回落成功记 Log.w（不静默）；两路都失败时把两路错误都带上。
     * 不在此处理 hello/密封阶段的失败——见类注释。
     */
    private fun connectWithFallback(peer: DiscoveredPeer, readTimeoutMs: Int): Socket {
        return try {
            connectSocket(peer.host, peer.port, readTimeoutMs)
        } catch (primary: IOException) {
            val fallback = peer.fallbackHost
            if (fallback.isNullOrBlank() || fallback == peer.host) throw primary
            logFallback(
                "首选地址连接失败（${primary.message}），回落到备用地址继续连接 ${peer.displayName}",
            )
            try {
                connectSocket(fallback, peer.port, readTimeoutMs)
            } catch (fallbackError: IOException) {
                throw IOException(
                    "首选地址与备用地址均连接失败：首选（${primary.message}）；备用（${fallbackError.message}）",
                    fallbackError,
                )
            }
        }
    }

    /** 回落通知：默认 Log.w；注入了记录器时走记录器；Log 是 stub 时不抛。 */
    private fun logFallback(message: String) {
        try {
            onFallback?.invoke(message) ?: Log.w(TAG, message)
        } catch (_: RuntimeException) {
            // plain-JVM 单测里 android.util.Log 是 stub：回落本身已
            // 成功，不因日志写不进而失败；真机上 Log.w 正常写 logcat。
        }
    }

    /** 一次 TCP 建连 + 读超时设置；失败时关掉半开的 socket 再抛错。 */
    private fun connectSocket(host: String, port: Int, readTimeoutMs: Int): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = readTimeoutMs
            return socket
        } catch (e: IOException) {
            try { socket.close() } catch (_: Exception) { }
            throw e
        }
    }

    /**
     * 一次 hello 握手：发本机公钥，拿对方公钥与 Agent 清单。
     * 全程一个短连接；任何一步失败都抛错，不返回半截结果。
     */
    suspend fun fetchHello(
        peer: DiscoveredPeer,
        ownDeviceId: String,
        ownPublicKey: ByteArray,
    ): RemoteHello = doFetchHello(peer, ownDeviceId, ownPublicKey, peer.deviceId)

    /**
     * 添加设备时的首次探测：还不知道对方 device_id，只校验类型/版本/
     * 公钥，不校验 id 一致。拿到真实 device_id 后调用方再存记录、
     * 再请用户核对指纹确认配对——探测本身不建立任何信任。
     */
    suspend fun fetchHelloUnverified(
        peer: DiscoveredPeer,
        ownDeviceId: String,
        ownPublicKey: ByteArray,
    ): RemoteHello = doFetchHello(peer, ownDeviceId, ownPublicKey, null)

    private suspend fun doFetchHello(
        peer: DiscoveredPeer,
        ownDeviceId: String,
        ownPublicKey: ByteArray,
        expectedDeviceId: String?,
    ): RemoteHello {
        require(ownPublicKey.size == 32) { "本机公钥必须是 32 字节" }
        return withContext(Dispatchers.IO) {
            // hello 建连同样走首选→回落（只在 TCP 连接阶段回落；
            // 连上之后 hello 读/解析失败不回落，见类注释）。
            val socket = connectWithFallback(peer, READ_TIMEOUT_MS)
            try {
                val request = JSONObject()
                request.put("type", HELLO_TYPE)
                request.put("version", HELLO_VERSION)
                request.put("device_id", ownDeviceId)
                request.put(
                    "public_key_b64",
                    java.util.Base64.getEncoder().encodeToString(ownPublicKey),
                )
                val framed = java.io.ByteArrayOutputStream()
                writeFrame(framed, request.toString().toByteArray(Charsets.UTF_8))
                socket.outputStream.write(framed.toByteArray())
                socket.outputStream.flush()
                val frame = readFrame(socket.inputStream)
                    ?: throw IOException("远端没有回 hello：对方可能不是 Hand 服务端")
                parseHello(String(frame, Charsets.UTF_8), expectedDeviceId)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: IOException) {
                throw IOException("与 ${peer.displayName} 的 hello 握手失败：${e.message}", e)
            } finally {
                try { socket.close() } catch (_: Exception) { }
            }
        }
    }

    override suspend fun close() {
        // 本实现不在传输层持有长效资源；已建立的连接各自负责关闭。
    }

    private inner class DirectTransportConnection(
        private val socket: Socket,
        private val peer: DiscoveredPeer,
    ) : TransportConnection {

        @Volatile
        private var closed = false

        private var requestBytes: ByteArray? = null
        private var helloBytes: ByteArray? = null
        private var started = false

        /**
         * V5 修复（2026-11-11，Grok/CodeBuddy 对话“空响应”）：
         * Hand 服务端每连接先读 hello 帧（拿客户端设备 id 与公钥、
         * 派生会话密钥并核对配对），再读密封信封。旧代码只发密封
         * 信封，服务端解析失败静默关闭、零帧回包。hello 帧与
         * [doFetchHello] 的请求同款。
         */
        override suspend fun sendHello(deviceId: String, publicKey: ByteArray) {
            check(!started) { "该连接已开始接收回包，不能再发 hello：先 hello 再发请求信封" }
            check(helloBytes == null) { "该连接已发过 hello，不能重复发送" }
            val obj = JSONObject()
            obj.put("type", HELLO_TYPE)
            obj.put("version", HELLO_VERSION)
            obj.put("device_id", deviceId)
            obj.put("public_key_b64", java.util.Base64.getEncoder().encodeToString(publicKey))
            helloBytes = obj.toString().toByteArray(Charsets.UTF_8)
        }

        override suspend fun send(bytes: ByteArray) {
            check(!started) { "该连接已开始接收回包，不能再发送请求：每连接只发一次请求信封" }
            check(requestBytes == null) { "该连接已发送过请求信封，不能重复发送" }
            requestBytes = bytes
        }

        override fun incoming(): Flow<ByteArray> = flow {
            val body = requestBytes
                ?: throw IllegalStateException("请先调用 send() 发送请求信封，再读取回包")
            val hello = helloBytes
            started = true
            val framed = java.io.ByteArrayOutputStream().also { out ->
                // 先写 hello 帧（有才写），再写密封信封帧；服务端按此顺序读。
                if (hello != null) writeFrame(out, hello)
                writeFrame(out, body)
            }.toByteArray()
            val out = socket.outputStream
            out.write(framed)
            out.flush()
            val input = socket.inputStream
            var firstFrame = hello != null
            while (!closed) {
                val frame = try {
                    readFrame(input)
                } catch (e: IOException) {
                    // 自己调 close() 断开的属于正常结束，不当错误抛。
                    if (closed) break else throw e
                } ?: break
                // 发了 hello 时，第一帧是服务端的 hello 回包（明文 JSON，
                // 含对端公钥与 Agent 列表），不是密封帧：核对后跳过，
                // 不交给上层验签。不是 hello 回包的第一帧（服务端没回
                // hello）按密封帧原样发出，保持容忍。
                if (firstFrame) {
                    firstFrame = false
                    if (isHelloReply(frame)) {
                        verifyHelloReply(frame)
                        continue
                    }
                }
                emit(frame)
            }
        }.flowOn(Dispatchers.IO)

        /** 首帧是 hello 回包当且仅当能解析为 type=wherevercode_hello 的 JSON。 */
        private fun isHelloReply(frame: ByteArray): Boolean {
            return try {
                JSONObject(String(frame, Charsets.UTF_8)).optString("type") == HELLO_TYPE
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 核对 hello 回包：复用 [parseHello] 的类型/版本/设备 id/
         * 公钥长度校验，再核对公钥指纹必须与配对记录一致；任何不符
         * 都 fail closed，不把来路不明的回包当密封帧交给上层。
         */
        private fun verifyHelloReply(frame: ByteArray) {
            val hello = try {
                parseHello(String(frame, Charsets.UTF_8), peer.deviceId)
            } catch (e: Exception) {
                throw IOException("远端 hello 回包无效（${e.message}）：请检查目标设备 Hand 服务")
            }
            if (!Discovery.verifyPinnedFingerprint(hello.publicKey, peer.fingerprintSha256)) {
                throw IOException(
                    "远端 hello 回包公钥指纹与配对记录不符：请在目标设备上核对指纹，" +
                        "确认无误后重新配对（可能遭遇中间人，或设备密钥已更换）",
                )
            }
        }

        override suspend fun close() {
            closed = true
            try { socket.close() } catch (_: Exception) { }
        }
    }
}
