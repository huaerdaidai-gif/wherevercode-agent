package wherevercode.agent.core.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.control.ControlAction
import wherevercode.agent.core.security.DeviceKeys
import wherevercode.agent.core.security.PeerTrustChecker
import wherevercode.agent.core.security.parseTaskCredential
import wherevercode.agent.core.transport.DirectTransport
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.TransportAdapter
import wherevercode.agent.core.transport.TransportConnection

/**
 * 阶段 B（V5 紧急开发）：HandClient 终于有了生产调用方。
 *
 * 红测试：RemoteTarget / RemoteAgentChatPort / HandRemoteAgentChatPort /
 * RemoteControlCommand 尚不存在。
 *
 * 全部用假传输（不触网）：断言发往远端的 run 信封长什么样
 * （action=run，argv=["agent", agentId, 文本]），以及 Progress/Result/
 * Blocked 如何流回调用方；控制指令走同一通道，argv=["__control__", …]。
 */
class RemoteAgentSessionTest {

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
    private val target = RemoteTarget(peer = peer, agentId = "example-agent", folder = "/workspace")
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
        var connectCalled = false
        override suspend fun discover(timeoutMs: Long): List<DiscoveredPeer> = emptyList()
        override suspend fun connect(peer: DiscoveredPeer): TransportConnection {
            connectCalled = true
            return connection
        }
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
        taskId: String = "task-1",
    ) = HandEnvelope(
        type = type,
        seq = seq,
        taskId = taskId,
        agentId = "example-agent",
        sourceDeviceId = "remote-host",
        targetDeviceId = "k70",
        folder = "/workspace",
        action = "run",
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
    fun `runAgent sends run envelope with agent argv and streams progress then result`() = runBlocking {
        val connection = FakeConnection(
            listOf(
                sealedFrame(responseEnvelope(HandClient.TYPE_PROGRESS, 2, mapOf("message" to "Example Agent 正在执行…"))),
                sealedFrame(responseEnvelope(HandClient.TYPE_RESULT, 3, mapOf("output" to "在的，有什么可以帮你？"))),
            ),
        )
        val events = port(FakeTransport(connection))
            .runAgent(target, taskId = "task-1", text = "在吗").toList()
        assertEquals(
            listOf(
                HandEvent.Progress(2, "Example Agent 正在执行…", null),
                HandEvent.Result(3, "在的，有什么可以帮你？"),
            ),
            events,
        )
        val envelope = sentEnvelope(connection)
        assertEquals("run", envelope?.action)
        assertEquals("task-1", envelope?.taskId)
        assertEquals("example-agent", envelope?.agentId)
        assertEquals("/workspace", envelope?.folder)
        val argv = org.json.JSONArray(envelope?.args?.get("argv") ?: "[]")
        assertEquals("agent", argv.getString(0))
        assertEquals("example-agent", argv.getString(1))
        assertEquals("在吗", argv.getString(2))
        // 栅栏快照随信封发出：服务端用它校验 epoch/goalVersion，
        // 改目标后旧快照的调用会被拒绝（不是静默执行旧目标）。
        val control = org.json.JSONObject(envelope?.args?.get("control") ?: "{}")
        assertEquals(1, control.getLong("goal_version"))
        assertEquals(1, control.getLong("epoch"))
        // 凭证随信封发出，且绑定该任务/目标/文件夹。
        val credential = parseTaskCredential(envelope?.args?.get("credential").orEmpty())
        assertEquals("task-1", credential?.taskId)
        assertEquals("remote-host", credential?.targetDeviceId)
        assertEquals("/workspace", credential?.folder)
    }

    @Test
    fun `runAgent to untrusted peer is blocked and sends nothing`() = runBlocking {
        val connection = FakeConnection(emptyList())
        val transport = FakeTransport(connection)
        val events = port(transport, trusted = false)
            .runAgent(target, taskId = "task-1", text = "在吗").toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertTrue(blocked.reason.contains("未配对"))
        assertNull(connection.sentBytes)
    }

    @Test
    fun `runAgent surfaces remote error as blocked with server code`() = runBlocking {
        val connection = FakeConnection(
            listOf(
                sealedFrame(
                    responseEnvelope(
                        HandClient.TYPE_ERROR, 2,
                        mapOf("code" to "hand_not_found", "message" to "Agent 脚本不存在"),
                    ),
                ),
            ),
        )
        val events = port(FakeTransport(connection))
            .runAgent(target, taskId = "task-1", text = "在吗").toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertEquals("hand_not_found", blocked.code)
        assertTrue(blocked.reason.contains("Agent 脚本不存在"))
    }

    @Test
    fun `sendControl sends control argv with action operation and goal version`() = runBlocking {
        val connection = FakeConnection(
            listOf(
                sealedFrame(
                    responseEnvelope(
                        HandClient.TYPE_RESULT, 2,
                        mapOf("output" to """{"control":"paused","confirmed":true}"""),
                        taskId = "task-9",
                    ),
                ),
            ),
        )
        val events = port(FakeTransport(connection)).sendControl(
            target,
            taskId = "task-9",
            command = RemoteControlCommand(
                action = ControlAction.PAUSE,
                operationId = "op-1",
                goalVersion = 3,
                epoch = 2,
            ),
        ).toList()
        assertEquals(1, events.size)
        assertTrue(events[0] is HandEvent.Result)
        val envelope = sentEnvelope(connection)
        assertEquals("run", envelope?.action)
        assertEquals("task-9", envelope?.taskId)
        val argv = org.json.JSONArray(envelope?.args?.get("argv") ?: "[]")
        assertEquals("__control__", argv.getString(0))
        assertEquals("pause", argv.getString(1))
        assertEquals("op-1", argv.getString(2))
        // 控制信封同样带任务凭证与执行栅栏快照（goalVersion/epoch 在参数里）。
        val control = org.json.JSONObject(envelope?.args?.get("control") ?: "{}")
        assertEquals(3, control.getLong("goal_version"))
        assertEquals(2, control.getLong("epoch"))
    }

    @Test
    fun `sendControl retarget carries the new goal`() = runBlocking {
        val connection = FakeConnection(
            listOf(
                sealedFrame(
                    responseEnvelope(
                        HandClient.TYPE_RESULT, 2,
                        mapOf("output" to """{"control":"retargeted","confirmed":true}"""),
                        taskId = "task-9",
                    ),
                ),
            ),
        )
        port(FakeTransport(connection)).sendControl(
            target,
            taskId = "task-9",
            command = RemoteControlCommand(
                action = ControlAction.CHANGE_GOAL,
                operationId = "op-2",
                newGoal = "改成写 README",
                goalVersion = 4,
                epoch = 2,
            ),
        ).toList()
        val envelope = sentEnvelope(connection)
        val argv = org.json.JSONArray(envelope?.args?.get("argv") ?: "[]")
        assertEquals("__control__", argv.getString(0))
        assertEquals("change_goal", argv.getString(1))
        assertEquals("改成写 README", argv.getString(3))
    }

    @Test
    fun `chat and control never fall back to local execution`() = runBlocking {
        // 本地无任何执行路径：HandClient 只认注入的传输；断网时事件是
        // Blocked，不是本地代跑的结果。
        val failing = object : TransportAdapter {
            override suspend fun discover(timeoutMs: Long): List<DiscoveredPeer> = emptyList()
            override suspend fun connect(peer: DiscoveredPeer): TransportConnection =
                throw java.io.IOException("连不上（测试替身）")
            override suspend fun close() {}
        }
        val events = port(failing).runAgent(target, taskId = "task-1", text = "在吗").toList()
        assertEquals(1, events.size)
        assertTrue(events[0] is HandEvent.Blocked)
        assertTrue((events[0] as HandEvent.Blocked).reason.contains("连接"))
    }
}
