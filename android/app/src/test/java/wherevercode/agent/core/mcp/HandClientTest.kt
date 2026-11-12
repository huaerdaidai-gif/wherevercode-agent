package wherevercode.agent.core.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.security.DeviceKeys
import wherevercode.agent.core.security.PeerTrustChecker
import wherevercode.agent.core.security.parseTaskCredential
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.TransportAdapter
import wherevercode.agent.core.transport.TransportConnection

/**
 * HandClient 测试（JUnit4 纯 JVM 测试）。
 *
 * NsdManager 与真实网络一律用测试替身：[FakeTransport] / [FakeTransportConnection]
 * 不触网，只按脚本回放已 seal 的帧。
 * 加密 / 凭证 / 信封用 A 路按契约实现的真实 API（DeviceKeys / HandProtocol /
 * TaskCredentialIssuer），本文件只测 HandClient 自身的流程逻辑。
 * 真机验证见回报里的“局域网真机验证步骤清单”，在设备端跑。
 */
// 测试替身：假传输连接，按脚本回放帧。
private class FakeTransportConnection(
    private val scriptedFrames: List<ByteArray>,
    private val onBeforeFrame: ((index: Int) -> Unit)? = null,
) : TransportConnection {
    var sentBytes: ByteArray? = null
    var closed = false
    /** 调用顺序记录：sendHello 记 "hello:<deviceId>"，send 记 "send"。 */
    val events = mutableListOf<String>()
    var helloDeviceId: String? = null
    var helloPublicKey: ByteArray? = null

    override suspend fun sendHello(deviceId: String, publicKey: ByteArray) {
        helloDeviceId = deviceId
        helloPublicKey = publicKey
        events.add("hello:$deviceId")
    }

    override suspend fun send(bytes: ByteArray) {
        sentBytes = bytes
        events.add("send")
    }

    override fun incoming(): Flow<ByteArray> = flow {
        scriptedFrames.forEachIndexed { index, frame ->
            onBeforeFrame?.invoke(index)
            emit(frame)
        }
    }

    override suspend fun close() {
        closed = true
    }
}

// 测试替身：假传输层，只返回上面那个假连接。
private class FakeTransport(
    private val connection: TransportConnection,
) : TransportAdapter {
    var connectCalled = false

    override suspend fun discover(timeoutMs: Long): List<DiscoveredPeer> = emptyList()

    override suspend fun connect(peer: DiscoveredPeer): TransportConnection {
        connectCalled = true
        return connection
    }

    override suspend fun close() {}
}

// 测试替身：假信任判定。
private class FakeTrustChecker(
    var trusted: Boolean = true,
    private val peerPublicKey: ByteArray? = null,
) : PeerTrustChecker {
    override fun isTrusted(peer: DiscoveredPeer): Boolean = trusted

    override fun trustFailureReason(peer: DiscoveredPeer): String? =
        if (trusted) null else "未配对：请先在目标设备上点一次确认，完成配对后再试"

    override fun peerPublicKey(deviceId: String): ByteArray? = peerPublicKey
}

class HandClientTest {

    private val ownKeyPair = DeviceKeys.generateKeyPair()
    private val peerKeyPair = DeviceKeys.generateKeyPair()
    private val signingKey = "test-signing-key".toByteArray(Charsets.UTF_8)

    private val peer = DiscoveredPeer(
        deviceId = "xiaomi12",
        displayName = "小米 12",
        host = "192.168.1.7",
        port = 8443,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )

    private var nowSeconds = 1_000_000L

    private fun sessionKey(): ByteArray =
        DeviceKeys.deriveSessionKey(ownKeyPair.privateKey, peerKeyPair.publicKey)

    private fun sealedFrame(key: ByteArray, envelope: HandEnvelope): ByteArray =
        DeviceKeys.seal(key, HandProtocol.toJson(envelope).toByteArray(Charsets.UTF_8))

    private fun responseEnvelope(
        type: String,
        seq: Long,
        args: Map<String, String> = emptyMap(),
    ) = HandEnvelope(
        type = type,
        seq = seq,
        taskId = "task-1",
        agentId = "muse",
        sourceDeviceId = "xiaomi12",
        targetDeviceId = "k70",
        folder = "/proj",
        action = "read",
        args = args,
    )

    private fun client(
        transport: TransportAdapter,
        trustChecker: PeerTrustChecker,
    ) = HandClient(
        transport = transport,
        ownDeviceId = "k70",
        ownKeyPair = ownKeyPair,
        signingKey = signingKey,
        trustChecker = trustChecker,
        nowEpochSeconds = { nowSeconds },
    )

    private fun request() = HandCallRequest(
        taskId = "task-1",
        agentId = "muse",
        peer = peer,
        peerPublicKey = peerKeyPair.publicKey,
        folder = "/proj",
        action = "read",
        args = mapOf("path" to "README.md"),
    )

    @Test
    fun sealedRoundTrip_progressThenResult() = runBlocking {
        val connection = FakeTransportConnection(
            listOf(
                sealedFrame(
                    sessionKey(),
                    responseEnvelope(
                        HandClient.TYPE_PROGRESS, 2,
                        mapOf("message" to "已读 3 个文件", "percent" to "40"),
                    ),
                ),
                sealedFrame(
                    sessionKey(),
                    responseEnvelope(
                        HandClient.TYPE_RESULT, 3,
                        mapOf("output" to "done"),
                    ),
                ),
            ),
        )
        val events = client(FakeTransport(connection), FakeTrustChecker())
            .call(request()).toList()
        assertEquals(
            listOf(
                HandEvent.Progress(2, "已读 3 个文件", 40),
                HandEvent.Result(3, "done"),
            ),
            events,
        )
        assertTrue(connection.closed)
    }

    @Test
    fun credentialSentWithEnvelope() = runBlocking {
        val connection = FakeTransportConnection(
            listOf(
                sealedFrame(
                    sessionKey(),
                    responseEnvelope(HandClient.TYPE_RESULT, 2, mapOf("output" to "ok")),
                ),
            ),
        )
        client(FakeTransport(connection), FakeTrustChecker()).call(request()).toList()
        val sent = connection.sentBytes
        assertTrue(sent != null)
        val opened = DeviceKeys.open(sessionKey(), sent!!)
        val envelope = HandProtocol.parseJson(String(opened, Charsets.UTF_8))
        assertEquals("task-1", envelope?.taskId)
        val credential = parseTaskCredential(envelope?.args?.get("credential").orEmpty())
        assertEquals("task-1", credential?.taskId)
        assertEquals("xiaomi12", credential?.targetDeviceId)
        assertEquals("/proj", credential?.folder)
        assertFalse(credential == null)
    }

    @Test
    fun credentialExpiresMidFlight_blocks() = runBlocking {
        // 凭证 TTL 900s：1_000_000 发出，1_000_900 过期。
        val connection = FakeTransportConnection(
            listOf(
                sealedFrame(
                    sessionKey(),
                    responseEnvelope(
                        HandClient.TYPE_PROGRESS, 2,
                        mapOf("message" to "开始"),
                    ),
                ),
                sealedFrame(
                    sessionKey(),
                    responseEnvelope(HandClient.TYPE_RESULT, 3, mapOf("output" to "done")),
                ),
            ),
            onBeforeFrame = { index ->
                // 第二帧到达前，时间已越过凭证有效期。
                if (index == 1) nowSeconds = 1_000_901L
            },
        )
        val events = client(FakeTransport(connection), FakeTrustChecker())
            .call(request()).toList()
        assertEquals(2, events.size)
        assertTrue(events[0] is HandEvent.Progress)
        val blocked = events[1] as HandEvent.Blocked
        assertEquals(HandProtocol.Errors.EXPIRED_CREDENTIAL, blocked.code)
        assertTrue(blocked.reason.contains("过期"))
        assertFalse(events.any { it is HandEvent.Result })
    }

    @Test
    fun replayedProgressSeq_dropped() = runBlocking {
        val progress = sealedFrame(
            sessionKey(),
            responseEnvelope(
                HandClient.TYPE_PROGRESS, 2,
                mapOf("message" to "进度"),
            ),
        )
        val connection = FakeTransportConnection(
            listOf(
                progress,
                progress, // 重放：同一 seq 的同一帧再发一次。
                sealedFrame(
                    sessionKey(),
                    responseEnvelope(HandClient.TYPE_RESULT, 3, mapOf("output" to "ok")),
                ),
            ),
        )
        val events = client(FakeTransport(connection), FakeTrustChecker())
            .call(request()).toList()
        // 重放的进度只出现一次，不阻塞任务。
        assertEquals(
            listOf(
                HandEvent.Progress(2, "进度", null),
                HandEvent.Result(3, "ok"),
            ),
            events,
        )
    }

    @Test
    fun untrustedPeer_blockedWithChineseReason_noSend() = runBlocking {
        val connection = FakeTransportConnection(emptyList())
        val transport = FakeTransport(connection)
        val events = client(transport, FakeTrustChecker(trusted = false))
            .call(request()).toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertTrue(blocked.reason.contains("未配对"))
        // 未建连、未发送，不回落本地执行（本类无本地执行路径）。
        assertFalse(transport.connectCalled)
        assertNull(connection.sentBytes)
    }

    @Test
    fun tamperedFrame_blocked() = runBlocking {
        // 用错误的会话密钥 seal：对方回包验签必须失败。
        val wrongKey = DeviceKeys.deriveSessionKey(
            ownKeyPair.privateKey,
            DeviceKeys.generateKeyPair().publicKey,
        )
        val connection = FakeTransportConnection(
            listOf(
                sealedFrame(
                    wrongKey,
                    responseEnvelope(HandClient.TYPE_RESULT, 2, mapOf("output" to "ok")),
                ),
            ),
        )
        val events = client(FakeTransport(connection), FakeTrustChecker())
            .call(request()).toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertEquals(HandProtocol.Errors.INVALID_CREDENTIAL, blocked.code)
        assertTrue(blocked.reason.contains("验签失败"))
        assertFalse(events.any { it is HandEvent.Result })
    }

    @Test
    fun helloSentBeforeSealedEnvelope() = runBlocking {
        // V5 修复（2026-11-11，Grok/CodeBuddy 对话“空响应”）：
        // Hand 服务端每连接先读 hello 帧再读密封信封；不发 hello
        // 直接发信封会被服务端静默丢弃。call() 必须先 hello 后发信封，
        // hello 里是本机设备 id 与本机公钥。
        val connection = FakeTransportConnection(
            listOf(
                sealedFrame(
                    sessionKey(),
                    responseEnvelope(HandClient.TYPE_RESULT, 2, mapOf("output" to "ok")),
                ),
            ),
        )
        client(FakeTransport(connection), FakeTrustChecker()).call(request()).toList()
        assertEquals(listOf("hello:k70", "send"), connection.events)
        assertEquals("k70", connection.helloDeviceId)
        assertTrue(connection.helloPublicKey?.contentEquals(ownKeyPair.publicKey) == true)
    }

    @Test
    fun zeroFrames_blockedNotSilentEmpty() = runBlocking {
        // V5 修复（2026-11-11，Grok/CodeBuddy 对话“空响应”）：
        // 服务端静默关闭、零帧回包时，过去是静默空事件、聊天层只能
        // 报“空响应”；现在必须给一个可诊断的 Blocked。
        val connection = FakeTransportConnection(emptyList())
        val events = client(FakeTransport(connection), FakeTrustChecker())
            .call(request()).toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertTrue(blocked.reason.contains("没有返回结果"))
        assertFalse(events.any { it is HandEvent.Result })
    }

    @Test
    fun disallowedAction_blocked() = runBlocking {
        val connection = FakeTransportConnection(emptyList())
        val events = client(FakeTransport(connection), FakeTrustChecker())
            .call(request().copy(action = "delete")).toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertEquals(HandProtocol.Errors.COMMAND_NOT_ALLOWED, blocked.code)
        assertTrue(blocked.reason.contains("只允许"))
        assertNull(connection.sentBytes)
    }

    @Test
    fun serverNotAuthorizedError_showsServerPendingMessage() = runBlocking {
        // V5 修复（2026-11-11，hello 通、run 就 reset）：服务端对
        // PENDING 客户端回密封的 cross_device_not_authorized，带明确
        // 的“尚未批准”文案；客户端必须原样显示服务端文案，不得
        // 回落到 HandProtocol 的文件夹授权兜底文案（那是另一回事）。
        val connection = FakeTransportConnection(
            listOf(
                sealedFrame(
                    sessionKey(),
                    responseEnvelope(
                        HandClient.TYPE_ERROR, 2,
                        mapOf(
                            "code" to HandProtocol.Errors.NOT_AUTHORIZED,
                            "message" to "远端设备尚未批准此手机（配对状态 PENDING）：请在远端设备上批准此手机后再试。",
                        ),
                    ),
                ),
            ),
        )
        val events = client(FakeTransport(connection), FakeTrustChecker())
            .call(request()).toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertEquals(HandProtocol.Errors.NOT_AUTHORIZED, blocked.code)
        assertTrue(blocked.reason.contains("尚未批准"))
        assertTrue(blocked.reason.contains("PENDING"))
        assertFalse(blocked.reason.contains("文件夹"))
        assertFalse(events.any { it is HandEvent.Result })
    }

    @Test
    fun connectionReset_mentionsPairingApproval() = runBlocking {
        // V5 修复（2026-11-11，hello 通、run 就 reset）：旧版 Hand
        // 对 PENDING 直接掐连接，客户端只看到 Connection reset。
        // 文案不能只说“检查网络”——hello 已通，reset 极可能是
        // 远端尚未批准此手机，必须把配对这个可能原因说出来。
        val connection = ResettingTransportConnection()
        val events = client(FakeTransport(connection), FakeTrustChecker())
            .call(request()).toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertTrue(blocked.reason.contains("Connection reset"))
        assertTrue(blocked.reason.contains("批准"))
        assertFalse(events.any { it is HandEvent.Result })
    }
}

// 测试替身：incoming 一抛 Connection reset（模拟旧版 Hand 对 PENDING
// 直接掐连接），其余与 FakeTransportConnection 同款。
private class ResettingTransportConnection : TransportConnection {
    override suspend fun sendHello(deviceId: String, publicKey: ByteArray) {}
    override suspend fun send(bytes: ByteArray) {}
    override fun incoming(): Flow<ByteArray> = flow {
        throw java.net.SocketException("Connection reset")
    }
    override suspend fun close() {}
}
