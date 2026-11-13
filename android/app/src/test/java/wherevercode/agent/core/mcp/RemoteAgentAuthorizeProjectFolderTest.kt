package wherevercode.agent.core.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.security.DeviceKeys
import wherevercode.agent.core.security.PeerTrustChecker
import wherevercode.agent.core.transport.DirectTransport
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.TransportAdapter
import wherevercode.agent.core.transport.TransportConnection

/**
 * V5 跨设备开发（2026-11-12，D2 新口径）：authorize_project_folder
 * 密封信封动作。
 *
 * 全部用假传输（不触网）：断言 App 侧发往 Hand 的信封长什么样——
 * action=authorize_project_folder，args["agent_id"] / args["folder"]
 * 是要授权的 Agent 与项目文件夹，信封 folder 就是待授权的项目
 * 文件夹（凭证继续绑定已有授权的 Agent 工作目录；项目文件夹只放
 * args，Hand 在通过密封、配对和凭证校验后执行明确的 grant）；taskId 用 authorize-
 * 前缀；未配对时直接 Blocked，绝不回落本地。
 * Hand 侧落盘（auth.json 中 Agent 对应的目录列表）由真实密封
 * 全流程测试 AuthorizeProjectFolderE2ETest 覆盖（本地起真 Hand）。
 */
class RemoteAgentAuthorizeProjectFolderTest {

    private val ownKeyPair = DeviceKeys.generateKeyPair()
    private val peerKeyPair = DeviceKeys.generateKeyPair()
    private val peer = DiscoveredPeer(
        deviceId = "remote-host",
        displayName = "远程设备",
        host = "192.0.2.10",
        port = DirectTransport.HAND_PORT,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )
    private val target = RemoteTarget(peer = peer, agentId = "example-agent", folder = "/workspace/example-project")
    private var nowSeconds = 1_000_000L

    /**
     * 假连接：incoming() 在 send() 之后才求值（冷流），按已发信封的
     * taskId 拼回包——HandClient 按 taskId 核对回包，写死 taskId 的
     * 脚本帧会被丢掉。
     */
    private inner class LazyResponseConnection : TransportConnection {
        var sentBytes: ByteArray? = null
        var closed = false
        override suspend fun send(bytes: ByteArray) { sentBytes = bytes }
        override fun incoming(): Flow<ByteArray> = flow {
            val sent = sentBytes ?: return@flow
            val req = HandProtocol.parseJson(String(DeviceKeys.open(sessionKey(), sent), Charsets.UTF_8))
            val reply = HandEnvelope(
                type = HandClient.TYPE_RESULT,
                seq = 2,
                taskId = req?.taskId ?: "unknown",
                agentId = "example-agent",
                sourceDeviceId = "remote-host",
                targetDeviceId = "k70",
                folder = "/workspace/example-project",
                action = "authorize_project_folder",
                args = mapOf("output" to "authorized"),
            )
            emit(DeviceKeys.seal(sessionKey(), HandProtocol.toJson(reply).toByteArray(Charsets.UTF_8)))
        }
        override suspend fun close() { closed = true }
    }

    private class FakeTransport(private val connection: TransportConnection) : TransportAdapter {
        override suspend fun discover(timeoutMs: Long): List<DiscoveredPeer> = emptyList()
        override suspend fun connect(peer: DiscoveredPeer): TransportConnection = connection
        override suspend fun close() {}
    }

    private inner class FakeTrustChecker(
        var trusted: Boolean = true,
    ) : PeerTrustChecker {
        override fun isTrusted(peer: DiscoveredPeer): Boolean = trusted
        override fun trustFailureReason(peer: DiscoveredPeer): String? =
            if (trusted) null else "未配对：请先在目标设备上点一次确认，完成配对后再试"
        override fun peerPublicKey(deviceId: String): ByteArray? = peerKeyPair.publicKey
    }

    private fun sessionKey(): ByteArray =
        DeviceKeys.deriveSessionKey(ownKeyPair.privateKey, peerKeyPair.publicKey)

    private fun port(
        transport: TransportAdapter,
        trusted: Boolean = true,
    ) = HandRemoteAgentChatPort(
        transport = transport,
        ownDeviceId = "k70",
        ownKeyPair = ownKeyPair,
        trustChecker = FakeTrustChecker(trusted),
        nowEpochSeconds = { nowSeconds },
    )

    private fun sentEnvelope(connection: LazyResponseConnection): HandEnvelope? {
        val sent = connection.sentBytes ?: return null
        val opened = DeviceKeys.open(sessionKey(), sent)
        return HandProtocol.parseJson(String(opened, Charsets.UTF_8))
    }

    @Test
    fun `authorize sends the sealed envelope and streams the result back`() = runBlocking {
        val connection = LazyResponseConnection()
        val events = port(FakeTransport(connection))
            .authorizeProjectFolder(target, "/workspace/wherevercode-agent")
            .toList()
        val envelope = sentEnvelope(connection)
        assertEquals("authorize_project_folder", envelope?.action)
        assertEquals("example-agent", envelope?.args?.get("agent_id"))
        assertEquals("/workspace/wherevercode-agent", envelope?.args?.get("folder"))
        assertEquals(target.folder, envelope?.folder)
        assertTrue("taskId 应为 authorize- 前缀", envelope?.taskId?.startsWith("authorize-") == true)
        assertTrue("凭证应随信封发送", envelope?.args?.containsKey("credential") == true)
        assertEquals(listOf(HandEvent.Result(2, "authorized")), events)
        assertTrue(connection.closed)
    }

    @Test
    fun `authorize is blocked when the peer is not paired`() = runBlocking {
        val connection = LazyResponseConnection()
        val events = port(FakeTransport(connection), trusted = false)
            .authorizeProjectFolder(target, "/workspace/wherevercode-agent")
            .toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertTrue(blocked.reason.contains("未配对"))
        assertEquals(null, connection.sentBytes)
    }
}
