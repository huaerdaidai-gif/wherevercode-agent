package wherevercode.agent.core.transport

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.security.DeviceKeys

/**
 * 配对链接解析（PairingOfferParser）的纯逻辑测试：链接由测试按 Hand
 * 侧同一格式拼（offer JSON → Base64url → wherevercode://pair?d=…），
 * 覆盖正常、裸粘贴、指纹被篡改、过期、错类型、垃圾输入。
 * 与真 Hand 的端到端 secret 配对在 PairingClientTest 里（真子进程）。
 */
class PairingOfferTest {

    private val serverKeys = DeviceKeys.generateKeyPair()
    private val fingerprint = Discovery.fingerprintSha256Hex(serverKeys.publicKey)
    private val now = 1_800_000_000L

    private fun offerJson(
        fingerprintOverride: String? = null,
        expiresAt: Long = now + 600,
        type: String = "wherevercode-pair",
    ): JSONObject = JSONObject().apply {
        put("v", 1)
        put("type", type)
        put("server_id", "Android-x")
        put("display_name", "Android-x")
        put("public_key_b64", Base64.getEncoder().encodeToString(serverKeys.publicKey))
        put("fingerprint", fingerprintOverride ?: fingerprint)
        put("port", 18743)
        put("hosts", org.json.JSONArray(listOf("192.168.1.7", "100.94.147.89")))
        put("offer_id", "0123456789abcdef")
        put("secret", "0123456789abcdef0123456789abcdef")
        put("expires_at", expiresAt)
    }

    private fun linkOf(obj: JSONObject): String {
        val raw = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(obj.toString().toByteArray(Charsets.UTF_8))
        return "wherevercode://pair?d=$raw"
    }

    @Test
    fun `valid link parses with verified fingerprint`() {
        val parsed = PairingOfferParser.parse(linkOf(offerJson()), now)
        assertTrue(parsed is PairingOfferParse.Ok)
        val offer = (parsed as PairingOfferParse.Ok).offer
        assertEquals("Android-x", offer.serverId)
        assertEquals(fingerprint, offer.fingerprintSha256)
        assertTrue(offer.publicKey.contentEquals(serverKeys.publicKey))
        assertEquals(listOf("192.168.1.7", "100.94.147.89"), offer.hosts)
        assertEquals(2, offer.candidatePeers().size)
        assertEquals(18743, offer.candidatePeers()[0].port)
    }

    @Test
    fun `bare base64url payload without scheme also parses`() {
        val link = linkOf(offerJson())
        val bare = link.removePrefix("wherevercode://pair?d=")
        val parsed = PairingOfferParser.parse("  $bare  ", now)
        assertTrue(parsed is PairingOfferParse.Ok)
    }

    @Test
    fun `tampered fingerprint is rejected`() {
        val parsed = PairingOfferParser.parse(
            linkOf(offerJson(fingerprintOverride = "0".repeat(64))), now)
        assertTrue(parsed is PairingOfferParse.Failed)
        assertTrue((parsed as PairingOfferParse.Failed).reason.contains("指纹"))
    }

    @Test
    fun `expired offer is rejected`() {
        val parsed = PairingOfferParser.parse(
            linkOf(offerJson(expiresAt = now - 1)), now)
        assertTrue(parsed is PairingOfferParse.Failed)
        assertTrue((parsed as PairingOfferParse.Failed).reason.contains("过期"))
    }

    @Test
    fun `wrong type and garbage are rejected`() {
        val wrongType = PairingOfferParser.parse(
            linkOf(offerJson(type = "something-else")), now)
        assertTrue(wrongType is PairingOfferParse.Failed)
        assertTrue(PairingOfferParser.parse("not a link at all", now)
            is PairingOfferParse.Failed)
        assertTrue(PairingOfferParser.parse("", now) is PairingOfferParse.Failed)
    }
}
