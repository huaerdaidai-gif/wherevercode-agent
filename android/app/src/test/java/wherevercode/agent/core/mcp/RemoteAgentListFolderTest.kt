package wherevercode.agent.core.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.project.parseFolderListJson
import wherevercode.agent.core.security.DeviceKeys
import wherevercode.agent.core.security.PeerTrustChecker
import wherevercode.agent.core.transport.DirectTransport
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.TransportAdapter
import wherevercode.agent.core.transport.TransportConnection

/**
 * V5 项目添加（2026-11-11）：Hand list 终于有了生产调用方。
 *
 * 全部用假传输（不触网）：断言发往远端的 list 信封长什么样
 * （action=list，args["path"] = 相对子路径），以及 Result 的
 * JSON 文件夹列表如何流回调用方；未配对时直接 Blocked，绝不回落本地。
 */
class RemoteAgentListFolderTest {

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

    private class FakeConnection(
        private val scriptedFrames: List<ByteArray>,
    ) : TransportConnection {
        var sentBytes: ByteArray? = null
        var closed = false
        override suspend fun send(bytes: ByteArray) { sentBytes = bytes }
        override fun incoming(): Flow<ByteArray> = flow { scriptedFrames.forEach { emit(it) } }
        override suspend fun close() { closed = true }
    }

    private class FakeTransport(private val connection: FakeConnection) : TransportAdapter {
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

    private fun sealedFrame(envelope: HandEnvelope): ByteArray =
        DeviceKeys.seal(sessionKey(), HandProtocol.toJson(envelope).toByteArray(Charsets.UTF_8))

    private fun responseEnvelope(
        type: String,
        seq: Long,
        args: Map<String, String> = emptyMap(),
        taskId: String = "task-list-1",
    ) = HandEnvelope(
        type = type,
        seq = seq,
        taskId = taskId,
        agentId = "example-agent",
        sourceDeviceId = "remote-host",
        targetDeviceId = "k70",
        folder = "/workspace/example-project",
        action = "list",
        args = args,
    )

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

    private fun sentEnvelope(connection: FakeConnection): HandEnvelope? {
        val sent = connection.sentBytes ?: return null
        val opened = DeviceKeys.open(sessionKey(), sent)
        return HandProtocol.parseJson(String(opened, Charsets.UTF_8))
    }

    @Test
    fun `listFolder sends list envelope with relative path and streams result`() = runBlocking {
        val listJson = """[{"name":"run-grok.sh","is_dir":false},{"name":"logs","is_dir":true}]"""
        val connection = FakeConnection(
            listOf(
                sealedFrame(
                    responseEnvelope(
                        HandClient.TYPE_RESULT, 2,
                        mapOf("output" to listJson),
                    ),
                ),
            ),
        )
        val events = port(FakeTransport(connection))
            .listFolder(target, taskId = "task-list-1", relativePath = "").toList()
        assertEquals(1, events.size)
        val result = events[0] as HandEvent.Result
        val entries = parseFolderListJson(result.output)
        assertEquals(2, entries.size)
        assertEquals("run-grok.sh", entries[0].name)
        assertTrue(entries[1].isDir)
        val envelope = sentEnvelope(connection)
        assertEquals("list", envelope?.action)
        assertEquals("task-list-1", envelope?.taskId)
        assertEquals("example-agent", envelope?.agentId)
        assertEquals("/workspace/example-project", envelope?.folder)
        assertEquals("", envelope?.args?.get("path"))
    }

    @Test
    fun `listFolder subpath is passed through`() = runBlocking {
        val connection = FakeConnection(
            listOf(
                sealedFrame(
                    responseEnvelope(
                        HandClient.TYPE_RESULT, 2,
                        mapOf("output" to "[]"),
                        taskId = "task-list-2",
                    ),
                ),
            ),
        )
        port(FakeTransport(connection))
            .listFolder(target, taskId = "task-list-2", relativePath = "logs").toList()
        val envelope = sentEnvelope(connection)
        assertEquals("list", envelope?.action)
        assertEquals("logs", envelope?.args?.get("path"))
    }

    @Test
    fun `listFolder surfaces server path error as blocked`() = runBlocking {
        val connection = FakeConnection(
            listOf(
                sealedFrame(
                    responseEnvelope(
                        HandClient.TYPE_ERROR, 2,
                        mapOf("code" to "path_not_allowed", "message" to "不允许的路径：该路径不在授权文件夹内。"),
                        taskId = "task-list-3",
                    ),
                ),
            ),
        )
        val events = port(FakeTransport(connection))
            .listFolder(target, taskId = "task-list-3", relativePath = "../etc").toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertEquals("path_not_allowed", blocked.code)
        assertTrue(blocked.reason.contains("不在授权文件夹内"))
    }

    @Test
    fun `listFolder to untrusted peer is blocked and sends nothing`() = runBlocking {
        val connection = FakeConnection(emptyList())
        val transport = FakeTransport(connection)
        val events = port(transport, trusted = false)
            .listFolder(target, taskId = "task-list-4", relativePath = "").toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertTrue(blocked.reason.contains("未配对"))
        assertNull(connection.sentBytes)
    }
}
