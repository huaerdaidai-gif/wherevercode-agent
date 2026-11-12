package wherevercode.agent.core.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery 纯逻辑测试（JUnit4 纯 JVM 测试）。
 * 本文件不依赖 NsdManager 与真实网络，不用测试替身。
 */
class DiscoveryTest {

    private val fingerprint = "ab".repeat(32) // 64 位合法 hex

    private fun txtBytes(vararg pairs: Pair<String, String>): Map<String, ByteArray> =
        pairs.associate { (k, v) -> k to v.toByteArray(Charsets.UTF_8) }

    @Test
    fun parseTxtRecord_fullRecord_parses() {
        val parsed = Discovery.parseTxtRecord(
            txtBytes(
                "id" to "xiaomi12",
                "fp" to fingerprint,
                "caps" to "hand,relay",
            ),
        )
        assertEquals("xiaomi12", parsed?.deviceId)
        assertEquals(fingerprint, parsed?.fingerprintSha256)
        assertEquals(listOf("hand", "relay"), parsed?.capabilities)
    }

    @Test
    fun parseTxtRecord_capsEmpty_givesEmptyList() {
        val parsed = Discovery.parseTxtRecord(
            txtBytes("id" to "k70", "fp" to fingerprint),
        )
        assertEquals(emptyList<String>(), parsed?.capabilities)
    }

    @Test
    fun parseTxtRecord_capsWithSpacesAndEmpties_trimmed() {
        val parsed = Discovery.parseTxtRecord(
            txtBytes("id" to "k70", "fp" to fingerprint, "caps" to " hand ,, relay "),
        )
        assertEquals(listOf("hand", "relay"), parsed?.capabilities)
    }

    @Test
    fun parseTxtRecord_missingFingerprint_returnsNull() {
        val parsed = Discovery.parseTxtRecord(txtBytes("id" to "k70", "caps" to "hand"))
        assertNull(parsed)
    }

    @Test
    fun parseTxtRecord_badFingerprint_returnsNull() {
        // 63 位：长度不对。
        val short = Discovery.parseTxtRecord(
            txtBytes("id" to "k70", "fp" to "ab".repeat(31) + "a"),
        )
        assertNull(short)
        // 含非 hex 字符。
        val badChar = Discovery.parseTxtRecord(
            txtBytes("id" to "k70", "fp" to "zz".repeat(32)),
        )
        assertNull(badChar)
    }

    @Test
    fun parseTxtRecord_missingId_returnsNull() {
        val parsed = Discovery.parseTxtRecord(txtBytes("fp" to fingerprint))
        assertNull(parsed)
    }

    @Test
    fun isValidFingerprintSha256_cases() {
        assertTrue(Discovery.isValidFingerprintSha256("ab".repeat(32)))
        assertTrue(Discovery.isValidFingerprintSha256("AB".repeat(32)))
        assertTrue(Discovery.isValidFingerprintSha256("0123456789abcdef".repeat(4)))
        assertFalse(Discovery.isValidFingerprintSha256("ab".repeat(31) + "a"))
        assertFalse(Discovery.isValidFingerprintSha256("ab".repeat(32) + "ab"))
        assertFalse(Discovery.isValidFingerprintSha256("zz".repeat(32)))
        assertFalse(Discovery.isValidFingerprintSha256(""))
    }

    @Test
    fun fingerprintSha256Hex_knownVector() {
        // SHA-256("abc") 的公开标准值。
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Discovery.fingerprintSha256Hex("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun verifyPinnedFingerprint_matchAndMismatch() {
        val key = "some-public-key".toByteArray(Charsets.UTF_8)
        val fp = Discovery.fingerprintSha256Hex(key)
        assertTrue(Discovery.verifyPinnedFingerprint(key, fp))
        // 大小写不敏感。
        assertTrue(Discovery.verifyPinnedFingerprint(key, fp.uppercase()))
        // 别的公钥不通过。
        val other = "other-public-key".toByteArray(Charsets.UTF_8)
        assertFalse(Discovery.verifyPinnedFingerprint(other, fp))
        // 格式非法的期望值一律不通过。
        assertFalse(Discovery.verifyPinnedFingerprint(key, "not-a-fingerprint"))
    }

    @Test
    fun toDiscoveredPeer_valid_buildsPeer() {
        val txt = Discovery.ParsedTxtRecord("xiaomi12", fingerprint, listOf("hand"))
        val peer = Discovery.toDiscoveredPeer("小米 12", "192.168.1.7", 8443, txt)
        assertEquals("xiaomi12", peer?.deviceId)
        assertEquals("小米 12", peer?.displayName)
        assertEquals("192.168.1.7", peer?.host)
        assertEquals(8443, peer?.port)
        assertEquals(fingerprint, peer?.fingerprintSha256)
        assertEquals(listOf("hand"), peer?.capabilities)
    }

    @Test
    fun toDiscoveredPeer_badHostOrPort_returnsNull() {
        val txt = Discovery.ParsedTxtRecord("xiaomi12", fingerprint, emptyList())
        assertNull(Discovery.toDiscoveredPeer("小米 12", "", 8443, txt))
        assertNull(Discovery.toDiscoveredPeer("小米 12", "   ", 8443, txt))
        assertNull(Discovery.toDiscoveredPeer("小米 12", "192.168.1.7", 0, txt))
        assertNull(Discovery.toDiscoveredPeer("小米 12", "192.168.1.7", 65536, txt))
    }
}
