package wherevercode.agent.core.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.security.DeviceKeys
import wherevercode.agent.core.security.PeerTrustChecker
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.TransportAdapter
import wherevercode.agent.core.transport.TransportConnection

/**
 * Hand 设备管理调用测试（2026-11-12，自更新 update_hand）。
 *
 * 与 HandClientTest 同款替身：假传输按脚本回放已 seal 的帧，不触网；
 * 加密 / 信封用真实 API（DeviceKeys / HandProtocol）。只测
 * HandClient.requestHandAdmin 的流程逻辑与 parseHandAdminReply。
 * 真机验证见 DELIVERY.md（密封全流程须在真机上跑）。
 */
private class AdminFakeTransportConnection(
    private val scriptedFrames: List<ByteArray>,
) : TransportConnection {
    var sentBytes: ByteArray? = null
    var closed = false
    var helloDeviceId: String? = null

    override suspend fun sendHello(deviceId: String, publicKey: ByteArray) {
        helloDeviceId = deviceId
    }

    override suspend fun send(bytes: ByteArray) {
        sentBytes = bytes
    }

    override fun incoming(): Flow<ByteArray> = flow {
        scriptedFrames.forEach { emit(it) }
    }

    override suspend fun close() {
        closed = true
    }
}

private class AdminFakeTransport(
    private val connection: TransportConnection,
) : TransportAdapter {
    override suspend fun discover(timeoutMs: Long): List<DiscoveredPeer> = emptyList()

    override suspend fun connect(peer: DiscoveredPeer): TransportConnection = connection

    override suspend fun close() {}
}

private class AdminFakeTrustChecker(
    var trusted: Boolean = true,
    private val peerPublicKey: ByteArray? = null,
) : PeerTrustChecker {
    override fun isTrusted(peer: DiscoveredPeer): Boolean = trusted

    override fun trustFailureReason(peer: DiscoveredPeer): String? =
        if (trusted) null else "未配对：请先在目标设备上点一次确认，完成配对后再试"

    override fun peerPublicKey(deviceId: String): ByteArray? = peerPublicKey
}

class HandAdminClientTest {

    private val ownKeyPair = DeviceKeys.generateKeyPair()
    private val peerKeyPair = DeviceKeys.generateKeyPair()
    private val signingKey = "test-signing-key".toByteArray(Charsets.UTF_8)

    private val peer = DiscoveredPeer(
        deviceId = "mac",
        displayName = "Mac",
        host = "192.168.1.4",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )

    private fun sessionKey(): ByteArray =
        DeviceKeys.deriveSessionKey(ownKeyPair.privateKey, peerKeyPair.publicKey)

    private fun client(
        transport: TransportAdapter,
        trustChecker: PeerTrustChecker,
    ) = HandClient(
        transport = transport,
        ownDeviceId = "k70",
        ownKeyPair = ownKeyPair,
        signingKey = signingKey,
        trustChecker = trustChecker,
        nowEpochSeconds = { 1_000_000L },
    )

    /** 管理回包的原始 JSON（不是 HandEnvelope：parseHandAdminReply 直接读 JSON）。 */
    private fun adminReplyJson(args: Map<String, String>): String {
        val obj = JSONObject()
        obj.put("type", HandProtocol.DEVICE_ADMIN_REPLY_TYPE)
        obj.put("version", 1)
        val argsObj = JSONObject()
        args.forEach { (k, v) -> argsObj.put(k, v) }
        obj.put("args", argsObj)
        return obj.toString()
    }

    private fun sealedReply(args: Map<String, String>): ByteArray =
        DeviceKeys.seal(sessionKey(), adminReplyJson(args).toByteArray(Charsets.UTF_8))

    @Test
    fun `requestHandAdmin sends confirmed admin envelope and returns args`() = runBlocking {
        val connection = AdminFakeTransportConnection(
            listOf(sealedReply(mapOf("staged" to "true", "version" to "237ad2080ea3"))),
        )
        val result = client(
            AdminFakeTransport(connection),
            AdminFakeTrustChecker(peerPublicKey = peerKeyPair.publicKey),
        ).requestHandAdmin(
            peer,
            "update_hand",
            mapOf("phase" to "stage", "version" to "237ad2080ea3"),
        )
        assertTrue(result is HandAdminResult.Ok)
        val ok = result as HandAdminResult.Ok
        assertEquals("true", ok.args["staged"])
        assertEquals("237ad2080ea3", ok.args["version"])
        // 发出去的信封：管理类型 + update_hand + confirmed=true。
        val sent = JSONObject(
            String(DeviceKeys.open(sessionKey(), connection.sentBytes!!), Charsets.UTF_8),
        )
        assertEquals(HandProtocol.DEVICE_ADMIN_TYPE, sent.getString("type"))
        assertEquals("update_hand", sent.getString("action"))
        assertTrue(sent.getBoolean("confirmed"))
        assertEquals("stage", sent.getJSONObject("args").getString("phase"))
        assertTrue(connection.closed)
    }

    @Test
    fun `requestHandAdmin rejects untrusted peer`() = runBlocking {
        val connection = AdminFakeTransportConnection(emptyList())
        val result = client(
            AdminFakeTransport(connection),
            AdminFakeTrustChecker(trusted = false, peerPublicKey = peerKeyPair.publicKey),
        ).requestHandAdmin(peer, "update_hand", mapOf("phase" to "stage"))
        assertTrue(result is HandAdminResult.Failed)
        assertEquals(null, connection.sentBytes)
    }

    @Test
    fun `requestHandAdmin fails on wrong reply type`() = runBlocking {
        val bad = JSONObject()
        bad.put("type", "cross_device_result")
        bad.put("version", 1)
        bad.put("args", JSONObject())
        val connection = AdminFakeTransportConnection(
            listOf(DeviceKeys.seal(sessionKey(), bad.toString().toByteArray(Charsets.UTF_8))),
        )
        val result = client(
            AdminFakeTransport(connection),
            AdminFakeTrustChecker(peerPublicKey = peerKeyPair.publicKey),
        ).requestHandAdmin(peer, "update_hand", mapOf("phase" to "stage"))
        assertTrue(result is HandAdminResult.Failed)
    }

    @Test
    fun `parseHandAdminReply accepts valid reply`() {
        val parsed = parseHandAdminReply(adminReplyJson(mapOf("applied" to "true")))
        assertTrue(parsed is HandAdminResult.Ok)
        assertEquals("true", (parsed as HandAdminResult.Ok).args["applied"])
    }

    @Test
    fun `parseHandAdminReply rejects wrong type version and bad json`() {
        assertNull(parseHandAdminReply(adminReplyJson(mapOf()).replace(
            HandProtocol.DEVICE_ADMIN_REPLY_TYPE, "cross_device_result",
        )))
        assertNull(parseHandAdminReply(adminReplyJson(mapOf()).replace("\"version\":1", "\"version\":2")))
        val noArgs = JSONObject()
        noArgs.put("type", HandProtocol.DEVICE_ADMIN_REPLY_TYPE)
        noArgs.put("version", 1)
        assertNull(parseHandAdminReply(noArgs.toString()))
        assertNull(parseHandAdminReply("not json"))
    }
}
