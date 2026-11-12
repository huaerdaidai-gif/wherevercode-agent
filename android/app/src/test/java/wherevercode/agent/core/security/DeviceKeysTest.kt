package wherevercode.agent.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceKeysTest {

    @Test
    fun `deriveSessionKey is symmetric between two devices`() {
        val a = DeviceKeys.generateKeyPair()
        val b = DeviceKeys.generateKeyPair()
        val keyAB = DeviceKeys.deriveSessionKey(a.privateKey, b.publicKey)
        val keyBA = DeviceKeys.deriveSessionKey(b.privateKey, a.publicKey)
        assertEquals(32, keyAB.size)
        assertArrayEquals(keyAB, keyBA)
    }

    @Test
    fun `different peers derive different session keys`() {
        val a = DeviceKeys.generateKeyPair()
        val b = DeviceKeys.generateKeyPair()
        val c = DeviceKeys.generateKeyPair()
        val keyAB = DeviceKeys.deriveSessionKey(a.privateKey, b.publicKey)
        val keyAC = DeviceKeys.deriveSessionKey(a.privateKey, c.publicKey)
        assertTrue(!keyAB.contentEquals(keyAC))
    }

    @Test
    fun `seal and open round-trip`() {
        val a = DeviceKeys.generateKeyPair()
        val b = DeviceKeys.generateKeyPair()
        val sessionKey = DeviceKeys.deriveSessionKey(a.privateKey, b.publicKey)
        val plaintext = "hello cross-device".toByteArray(Charsets.UTF_8)
        val sealed = DeviceKeys.seal(sessionKey, plaintext)
        // Layout (contract): 12-byte nonce first, 16-byte tag last.
        assertTrue(sealed.size == plaintext.size + 12 + 16)
        assertArrayEquals(plaintext, DeviceKeys.open(sessionKey, sealed))
    }

    @Test
    fun `open with the wrong key fails`() {
        val a = DeviceKeys.generateKeyPair()
        val b = DeviceKeys.generateKeyPair()
        val c = DeviceKeys.generateKeyPair()
        val sessionKey = DeviceKeys.deriveSessionKey(a.privateKey, b.publicKey)
        val wrongKey = DeviceKeys.deriveSessionKey(a.privateKey, c.publicKey)
        val sealed = DeviceKeys.seal(sessionKey, "secret".toByteArray(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) {
            DeviceKeys.open(wrongKey, sealed)
        }
    }

    @Test
    fun `open with tampered ciphertext fails`() {
        val a = DeviceKeys.generateKeyPair()
        val b = DeviceKeys.generateKeyPair()
        val sessionKey = DeviceKeys.deriveSessionKey(a.privateKey, b.publicKey)
        val sealed = DeviceKeys.seal(sessionKey, "secret".toByteArray(Charsets.UTF_8))
        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            DeviceKeys.open(sessionKey, tampered)
        }
    }

    @Test
    fun `hkdf info string is the cross-device v1 string`() {
        assertEquals("wherevercode-cross-device/v1", DeviceKeys.HKDF_INFO)
    }
}
