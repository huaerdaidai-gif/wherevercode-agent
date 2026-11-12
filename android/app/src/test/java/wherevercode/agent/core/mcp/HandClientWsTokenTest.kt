package wherevercode.agent.core.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.security.DeviceKeys
import wherevercode.agent.core.security.PeerTrustChecker
import wherevercode.agent.core.security.RemoteCredentialKeys
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.TransportAdapter
import wherevercode.agent.core.transport.TransportConnection

/**
 * HandClient.requestWsToken 测试（JUnit4 纯 JVM 测试）。
 *
 * 传输用测试替身（按脚本回放一帧密封回包，不触网）；加密 / 信封用
 * 真实 API（DeviceKeys / HandProtocol）。验证：领 token 的信封是
 * wherevercode_ws_token 且不带任务凭证；回包开包取 token；失败
 * 一律回 Failed 不抛错。
 */
private class WsTokenFakeConnection(
    private val scriptedFrames: List<ByteArray>,
) : TransportConnection {
    var sentBytes: ByteArray? = null
    var closed = false

    override suspend fun sendHello(deviceId: String, publicKey: ByteArray) {}

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

private class WsTokenFakeTransport(
    private val connection: TransportConnection,
) : TransportAdapter {
    override suspend fun discover(timeoutMs: Long): List<DiscoveredPeer> = emptyList()

    override suspend fun connect(peer: DiscoveredPeer): TransportConnection = connection

    override suspend fun close() {}
}

private class WsTokenFakeTrust(
    var trusted: Boolean = true,
    private val peerPublicKey: ByteArray? = null,
) : PeerTrustChecker {
    override fun isTrusted(peer: DiscoveredPeer): Boolean = trusted

    override fun trustFailureReason(peer: DiscoveredPeer): String? =
        if (trusted) null else "未配对：请先在目标设备上点一次确认，完成配对后再试"

    override fun peerPublicKey(deviceId: String): ByteArray? = peerPublicKey
}

class HandClientWsTokenTest {

    private val ownKeyPair = DeviceKeys.generateKeyPair()
    private val peerKeyPair = DeviceKeys.generateKeyPair()
    private val token = "c".repeat(64)

    private val peer = DiscoveredPeer(
        deviceId = "remote-device",
        displayName = "remote-device",
        host = "192.0.2.10",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )

    private fun sessionKey(): ByteArray =
        DeviceKeys.deriveSessionKey(ownKeyPair.privateKey, peerKeyPair.publicKey)

    private fun client(connection: TransportConnection, trust: PeerTrustChecker) =
        HandClient(
            transport = WsTokenFakeTransport(connection),
            ownDeviceId = "k70",
            ownKeyPair = ownKeyPair,
            signingKey = RemoteCredentialKeys.signingKey(sessionKey()),
            trustChecker = trust,
            nowEpochSeconds = { 1_000_000L },
        )

    private fun trust() = WsTokenFakeTrust(trusted = true, peerPublicKey = peerKeyPair.publicKey)

    /** 服务端回包原文（密封前）：type 必须是 wherevercode_ws_token_reply。 */
    private fun replyJson(tokenValue: String = token, expiresIn: Long = 120): String {
        val args = JSONObject()
        args.put("token", tokenValue)
        args.put("expires_in", expiresIn)
        val obj = JSONObject()
        obj.put("type", HandClient.TYPE_WS_TOKEN_REPLY)
        obj.put("version", 1)
        obj.put("seq", 1)
        obj.put("task_id", "")
        obj.put("agent_id", "")
        obj.put("source_device_id", "remote-device")
        obj.put("target_device_id", "k70")
        obj.put("folder", "")
        obj.put("action", "")
        obj.put("args", args)
        return obj.toString()
    }

    private fun sealedReply(json: String): ByteArray =
        DeviceKeys.seal(sessionKey(), json.toByteArray(Charsets.UTF_8))

    @Test
    fun ok_returnsTokenAndExpiresIn() = runBlocking {
        val connection = WsTokenFakeConnection(listOf(sealedReply(replyJson())))
        val result = client(connection, trust()).requestWsToken(peer)
        assertTrue(result is WsTokenResult.Ok)
        val ok = result as WsTokenResult.Ok
        assertEquals(token, ok.token)
        assertEquals(120L, ok.expiresInSeconds)
        assertTrue(connection.closed)
    }

    @Test
    fun requestEnvelope_isWsTokenTypeWithoutCredential() = runBlocking {
        val connection = WsTokenFakeConnection(listOf(sealedReply(replyJson())))
        client(connection, trust()).requestWsToken(peer)
        val sent = requireNotNull(connection.sentBytes) { "未发出任何请求" }
        val opened = DeviceKeys.open(sessionKey(), sent)
        val obj = JSONObject(String(opened, Charsets.UTF_8))
        assertEquals(HandClient.TYPE_WS_TOKEN, obj.optString("type"))
        assertEquals(1, obj.optInt("version"))
        assertEquals(1L, obj.optLong("seq"))
        assertEquals("", obj.optString("task_id"))
        assertEquals("", obj.optString("agent_id"))
        assertEquals("", obj.optString("action"))
        assertEquals("k70", obj.optString("source_device_id"))
        assertEquals("remote-device", obj.optString("target_device_id"))
        // 关键：不带任务凭证（这不是 _dispatch 调用）。
        assertTrue(!obj.optJSONObject("args").has("credential"))
    }

    @Test
    fun untrusted_returnsFailed() = runBlocking {
        val connection = WsTokenFakeConnection(listOf(sealedReply(replyJson())))
        val result = client(connection, WsTokenFakeTrust(trusted = false, peerPublicKey = peerKeyPair.publicKey))
            .requestWsToken(peer)
        assertTrue(result is WsTokenResult.Failed)
        assertTrue((result as WsTokenResult.Failed).reason.contains("未配对"))
    }

    @Test
    fun noPeerKey_returnsFailed() = runBlocking {
        val connection = WsTokenFakeConnection(listOf(sealedReply(replyJson())))
        val result = client(connection, WsTokenFakeTrust(trusted = true, peerPublicKey = null))
            .requestWsToken(peer)
        assertTrue(result is WsTokenResult.Failed)
    }

    @Test
    fun wrongReplyType_returnsFailed() = runBlocking {
        val bad = JSONObject(replyJson()).apply { put("type", "cross_device_result") }.toString()
        val connection = WsTokenFakeConnection(listOf(sealedReply(bad)))
        val result = client(connection, trust()).requestWsToken(peer)
        assertTrue(result is WsTokenResult.Failed)
    }

    @Test
    fun badTokenFormat_returnsFailed() = runBlocking {
        val connection = WsTokenFakeConnection(listOf(sealedReply(replyJson(tokenValue = "xyz"))))
        val result = client(connection, trust()).requestWsToken(peer)
        assertTrue(result is WsTokenResult.Failed)
    }

    @Test
    fun tamperedFrame_returnsFailed() = runBlocking {
        val sealedBytes = sealedReply(replyJson())
        sealedBytes[sealedBytes.size - 1] = (sealedBytes[sealedBytes.size - 1].toInt() xor 0xFF).toByte()
        val connection = WsTokenFakeConnection(listOf(sealedBytes))
        val result = client(connection, trust()).requestWsToken(peer)
        assertTrue(result is WsTokenResult.Failed)
    }

    @Test
    fun malformedJson_returnsFailed() = runBlocking {
        val connection = WsTokenFakeConnection(listOf(sealedReply("{not json")))
        val result = client(connection, trust()).requestWsToken(peer)
        assertTrue(result is WsTokenResult.Failed)
    }

    @Test
    fun noFrames_returnsFailedNotHang() = runBlocking {
        val connection = WsTokenFakeConnection(emptyList())
        val result = client(connection, trust()).requestWsToken(peer)
        assertTrue(result is WsTokenResult.Failed)
    }
}
