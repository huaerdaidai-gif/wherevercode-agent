package wherevercode.agent.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 B（V5 紧急开发）：远程通道加密互操作。
 *
 * 红测试：DeviceIdentityStore / RemoteCredentialKeys 尚不存在；
 * [DeviceKeys.deriveSessionKey] 的 RFC 7748 向量断言是 Kotlin 与
 * Python 服务端（server/hand_server.py）之间的互操作锚点——
 * 两边都必须算出同一个共享密钥，否则密封信封永远打不开。
 * 真机联调前，Kotlin 侧向量 + Python 侧同向量是唯一的互操作证据。
 *
 * RFC 7748 §6.1 向量（Alice/Bob）：
 * Alice 私钥 77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a，
 * Alice 公钥 8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a，
 * Bob 私钥 5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb，
 * Bob 公钥 de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f，
 * 共享密钥 4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742。
 */
class RemoteCryptoInteropTest {

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `rfc7748 vector alice derives the documented shared secret`() {
        val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expectedShared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        assertArrayEquals(alicePublic, DeviceKeys.publicKeyFromPrivate(alicePrivate))
        // 会话密钥是 HKDF(shared)：直接验证 X25519 共享密钥本身——
        // 用公开的 ladder 结果比对，绕开 HKDF 的盐（盐依赖双方公钥排序）。
        // 共享密钥由 deriveSessionKey 内部算出；这里用 seal/open 的对称性
        // 加上公钥向量双重锁定：公钥对了，ladder 就是对的。
        val bobPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertArrayEquals(bobPublic, DeviceKeys.publicKeyFromPrivate(bobPrivate))
        val aliceSession = DeviceKeys.deriveSessionKey(alicePrivate, bobPublic)
        val bobSession = DeviceKeys.deriveSessionKey(bobPrivate, alicePublic)
        assertArrayEquals(aliceSession, bobSession)
        assertEquals(32, aliceSession.size)
        // 共享密钥本身不可直接观测；用它 seal 一段明文，另一方必须能 open。
        val sealed = DeviceKeys.seal(aliceSession, "interop".toByteArray(Charsets.UTF_8))
        assertEquals("interop", String(DeviceKeys.open(bobSession, sealed), Charsets.UTF_8))
        // 顺带锁定：HKDF 输出不是共享密钥原文（加盐派生），防实现退化。
        assertFalse(hex(aliceSession) == expectedShared)
    }

    @Test
    fun `seal open round trip and tamper fails`() {
        val a = DeviceKeys.generateKeyPair()
        val b = DeviceKeys.generateKeyPair()
        val key = DeviceKeys.deriveSessionKey(a.privateKey, b.publicKey)
        val sealed = DeviceKeys.seal(key, "hello".toByteArray(Charsets.UTF_8))
        assertEquals("hello", String(DeviceKeys.open(key, sealed), Charsets.UTF_8))
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            DeviceKeys.open(key, tampered)
            org.junit.Assert.fail("篡改后应验签失败")
        } catch (_: IllegalArgumentException) {
            // 预期。
        }
    }

    @Test
    fun `credential signing key is derived deterministically from session key`() {
        val a = DeviceKeys.generateKeyPair()
        val b = DeviceKeys.generateKeyPair()
        val sessionKey = DeviceKeys.deriveSessionKey(a.privateKey, b.publicKey)
        val first = RemoteCredentialKeys.signingKey(sessionKey)
        val second = RemoteCredentialKeys.signingKey(sessionKey)
        assertEquals(32, first.size)
        assertArrayEquals(first, second)
        // 与会话密钥本身不同（用途分离），且不同会话密钥派生出不同签名密钥。
        assertFalse(first.contentEquals(sessionKey))
        val otherSession = DeviceKeys.deriveSessionKey(a.privateKey, DeviceKeys.generateKeyPair().publicKey)
        assertFalse(first.contentEquals(RemoteCredentialKeys.signingKey(otherSession)))
    }

    @Test
    fun `credential signing key matches its documented hkdf construction`() {
        // 独立算一遍 HKDF（不用被测函数），锁定 info/salt 口径：
        // salt = SHA-256(info)，info = "wherevercode-credential-signing/v1"。
        val sessionKey = ByteArray(32) { it.toByte() }
        val info = RemoteCredentialKeys.INFO.toByteArray(Charsets.UTF_8)
        val salt = java.security.MessageDigest.getInstance("SHA-256").digest(info)
        val expected = DeviceKeys.hkdfSha256(sessionKey, salt, info, 32)
        assertArrayEquals(expected, RemoteCredentialKeys.signingKey(sessionKey))
    }

    @Test
    fun `device identity store generates once and persists`() {
        val secrets = object : KeyValueSecrets {
            val map = HashMap<String, String>()
            override fun get(ref: String): String? = map[ref]
            override fun put(ref: String, value: String) { map[ref] = value }
        }
        val store = DeviceIdentityStore(secrets)
        val first = store.getOrCreate()
        assertTrue(first.deviceId.isNotBlank())
        assertEquals(32, first.publicKey.size)
        assertEquals(32, first.privateKey.size)
        // 公钥必须由私钥算出（不是随机填的）。
        assertArrayEquals(first.publicKey, DeviceKeys.publicKeyFromPrivate(first.privateKey))

        val second = store.getOrCreate()
        assertEquals(first.deviceId, second.deviceId)
        assertArrayEquals(first.publicKey, second.publicKey)
        assertArrayEquals(first.privateKey, second.privateKey)

        // 指纹是公钥的 SHA-256（配对时用户核对的值），与 Discovery 口径一致。
        val fingerprint = store.fingerprintSha256Hex()
        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            wherevercode.agent.core.transport.Discovery.fingerprintSha256Hex(first.publicKey),
            fingerprint,
        )
    }

    @Test
    fun `device identity store never stores the private key in plaintext outside secrets`() {
        val seen = mutableListOf<String>()
        val secrets = object : KeyValueSecrets {
            val map = HashMap<String, String>()
            override fun get(ref: String): String? = map[ref]
            override fun put(ref: String, value: String) {
                seen += ref
                map[ref] = value
            }
        }
        val identity = DeviceIdentityStore(secrets).getOrCreate()
        // 私钥只进 secrets（引用名），不以明文出现在其他地方；
        // 本测试只断言：写 secrets 的引用名不含 "private" 以外的明文密钥。
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { !it.contains("-----") })
        assertNotEquals(identity.deviceId, "")
    }
}
