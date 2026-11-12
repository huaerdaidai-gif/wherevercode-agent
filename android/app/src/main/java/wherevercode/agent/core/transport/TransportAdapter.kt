package wherevercode.agent.core.transport

import kotlinx.coroutines.flow.Flow

/**
 * 跨设备传输抽象层（阶段 2 B 路）。
 *
 * 依据：架构设计 §6（传输必须可替换；旧项目走平台服务器中继的
 * `CrossDeviceRelay.submit()` / `/api/cross-device` 路由全部废弃，
 * 新链路是设备直连，平台服务器在新链路里不存在）。
 *
 * 本接口不得绑定 mDNS 或 HTTPS 实现：[LanTransport] 只是其中一种实现，
 * 之后可替换为 Tailscale 直连（审计 §6.2 候选 A）或用户自备中继（候选 C），
 * [HandClient] 等上层只依赖本接口。
 *
 * 约定：每条 [TransportConnection] 只发一次请求信封（[TransportConnection.send]），
 * 之后是服务端流式回包（[TransportConnection.incoming]）。这正是
 * [wherevercode.agent.core.mcp.HandClient] 的调用模式：
 * 一次请求、多帧回包（进度 → 结果/阻塞）。
 */
data class DiscoveredPeer(
    /** 设备 id（配对与凭证里的 targetDeviceId 就是它）。 */
    val deviceId: String,
    /** 显示名（来自 mDNS 服务名，如“K70”），仅供 UI 显示。 */
    val displayName: String,
    /** 局域网地址（由 mDNS 解析得到，不进 TXT 记录）。 */
    val host: String,
    val port: Int,
    /**
     * 对端 TLS 身份公钥的 SHA-256 指纹（小写 hex，64 位）。
     * 是公钥的摘要、可公开显示，供用户在配对时核对；不是密钥本身。
     */
    val fingerprintSha256: String,
    /** 能力清单，如 ["hand", "relay"]，来自 TXT 记录 caps 字段。 */
    val capabilities: List<String>,
    /**
     * 回落地址（与 [host] 同端口）：[host] 连不上（超时/拒绝）时
     * [wherevercode.agent.core.transport.DirectTransport] 才试它。
     * 由 [wherevercode.agent.core.transport.RemoteDevice] 的公网直连
     * 地址填：host = 公网直连地址（优先），fallbackHost = 原内网/
     * Tailnet 地址。LAN 发现的对端无回落，为 null。只影响 TCP 建连
     * 阶段：连上之后 hello/密封失败不回落（对方已应答，回落到同一
     * 台 Hand 没有意义，还会掩盖配对/鉴权错误）。
     */
    val fallbackHost: String? = null,
) {
    /** 建连时按序尝试的地址：首选在前，回落地址在后（与首选相同时去重）。 */
    fun hostsInPriorityOrder(): List<String> =
        listOf(host) + listOfNotNull(fallbackHost).filter { it != host }
}

interface TransportAdapter {
    /**
     * 发现局域网内的对端设备。
     * @param timeoutMs 发现等待时长，默认 5000ms。
     * @return 发现到的对端；一个也没发现时返回空列表，不抛错。
     */
    suspend fun discover(timeoutMs: Long = 5000): List<DiscoveredPeer>

    /**
     * 与对端建连。建连时校验对端证书指纹必须与
     * [DiscoveredPeer.fingerprintSha256] 一致，否则抛错、不得建连。
     */
    suspend fun connect(peer: DiscoveredPeer): TransportConnection

    /** 关闭本传输层持有的资源（如发现监听）。已建立的连接各自负责关闭。 */
    suspend fun close()
}

interface TransportConnection {
    /**
     * 发送一次请求信封（已 seal 的密文帧）。
     * 每连接只能调一次；重复调用抛 [IllegalStateException]。
     *
     * V5 修复（2026-11-11，Grok/CodeBuddy 对话“空响应”）：发信封前
     * 必须先调 [sendHello]——Hand 服务端（server/hand_server.py）每连接
     * 先读 hello 帧拿客户端设备 id 与公钥、派生会话密钥并核对配对，
     * 再读密封信封；不发 hello 直接发信封会被服务端静默丢弃，连接
     * 关闭、零帧回包，App 侧即“空响应”。[LanTransport] 的 HTTPS
     * 单 POST 不需要 hello，用默认空实现即可。
     */
    suspend fun send(bytes: ByteArray)

    /**
     * 在 [send] 之前先发 hello 帧（本机设备 id + 本机公钥）。
     * 默认空实现：不需要 hello 的传输不用改。需要 hello 的传输
     * （[DirectTransport]）必须重写：把 hello 帧记下来，[incoming]
     * 写请求时先写 hello 帧、再写密封信封，并跳过服务端的 hello
     * 回包（只认密封帧）。
     */
    suspend fun sendHello(deviceId: String, publicKey: ByteArray) {}

    /**
     * 流式回包：每帧是对方发来的已 seal 的密文。
     * 服务端发完终态帧（结果/错误）后关闭流，本 Flow 随之正常结束。
     */
    fun incoming(): Flow<ByteArray>

    /** 关闭连接。幂等；关闭后 [incoming] 正常结束，不再抛错。 */
    suspend fun close()
}
