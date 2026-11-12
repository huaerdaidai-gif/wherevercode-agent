package wherevercode.agent.core.security

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DeviceKeys: X25519 + HKDF + AES-256-GCM envelope (stage 2).
 *
 * Ported from the old project's `cross_device_crypto.py`
 * (blob 347d199a, main fd6b43d2):
 * - X25519 key agreement (RFC 7748) — old code used a pure-Python ladder;
 *   this port uses a pure-Kotlin ladder over BigInteger.
 * - Session key: HKDF-SHA256 over the shared secret, salt = SHA-256 of
 *   the two public keys sorted (order-independent), info =
 *   "wherevercode-cross-device/v1" (kept byte-identical so old devices
 *   derive the same session key).
 * - Payload: AES-256-GCM. NOTE the deliberate difference from the old
 *   code: the old `seal()` was an HMAC-SHA256 keystream + tag (it said
 *   so in its own docstring: "it is NOT AES-GCM"). This port uses real
 *   AES-256-GCM because the stage-2 contract fixes the wire format as
 *   "12-byte random nonce first, 16-byte tag last", which is the GCM
 *   layout; the old HMAC construction must NOT be described as AES-GCM.
 *   Interop note: a session key derived here matches the old derivation,
 *   but a sealed blob from the old HMAC construction cannot be opened
 *   here (and vice versa) — the envelope format changed with the
 *   contract, by design.
 *
 * X25519 implementation choice (contract allows two options): pure-Kotlin
 * RFC 7748, NOT BouncyCastle. Reasons: minSdk is 26 and JCA "XDH" needs
 * API 31+; a pure-Kotlin ladder adds no dependency, works identically in
 * JVM unit tests and on the phone, and the old project already proved a
 * pure-language ladder. ECDH P-256 is NOT used (it would break interop
 * with old devices).
 */
object DeviceKeys {
    const val HKDF_INFO: String = "wherevercode-cross-device/v1"

    data class KeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE_POINT: ByteArray = ByteArray(32).also { it[0] = 9 }
    private const val NONCE_BYTES: Int = 12
    private const val TAG_BITS: Int = 128

    fun generateKeyPair(): KeyPair {
        val privateKey = ByteArray(32)
        SecureRandom().nextBytes(privateKey)
        return KeyPair(publicKeyFromPrivate(privateKey), privateKey)
    }

    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "cross_device_crypto_bad_key" }
        return x25519(clamp(privateKey), BASE_POINT)
    }

    /**
     * X25519 + HKDF-SHA256 -> 32-byte session key. Symmetric: either side
     * derives the same key from (own private, peer public).
     */
    fun deriveSessionKey(ownPrivateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(ownPrivateKey.size == 32 && peerPublicKey.size == 32) {
            "cross_device_crypto_bad_key"
        }
        val ownPublic = publicKeyFromPrivate(ownPrivateKey)
        val shared = x25519(clamp(ownPrivateKey), peerPublicKey)
        val (first, second) = if (compareBytes(ownPublic, peerPublicKey) <= 0) {
            ownPublic to peerPublicKey
        } else {
            peerPublicKey to ownPublic
        }
        val salt = MessageDigest.getInstance("SHA-256").digest(first + second)
        return hkdfSha256(shared, salt, HKDF_INFO.toByteArray(Charsets.UTF_8), 32)
    }

    /**
     * Seal: AES-256-GCM. Output layout (contract): 12-byte random nonce,
     * then ciphertext, then the 16-byte tag last (GCM appends the tag to
     * the cipher output, so nonce + cipherOutput is exactly that layout).
     */
    fun seal(sessionKey: ByteArray, plaintext: ByteArray): ByteArray {
        require(sessionKey.size == 32) { "cross_device_crypto_bad_session_key" }
        val nonce = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val sealed = cipher.doFinal(plaintext)
        return nonce + sealed
    }

    /** Open a sealed blob. Any tamper or wrong key throws IllegalArgumentException. */
    fun open(sessionKey: ByteArray, sealed: ByteArray): ByteArray {
        require(sessionKey.size == 32) { "cross_device_crypto_bad_session_key" }
        if (sealed.size < NONCE_BYTES + TAG_BITS / 8) {
            throw IllegalArgumentException("cross_device_crypto_bad_sealed")
        }
        val nonce = sealed.copyOfRange(0, NONCE_BYTES)
        val cipherText = sealed.copyOfRange(NONCE_BYTES, sealed.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            throw IllegalArgumentException("cross_device_crypto_bad_tag", e)
        }
    }

    /** HKDF-SHA256 (RFC 5869): extract + expand. */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        var out = ByteArray(0)
        var counter = 1
        var previous = ByteArray(0)
        while (out.size < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(byteArrayOf(counter.toByte()))
            previous = mac.doFinal()
            out += previous
            counter++
        }
        return out.copyOfRange(0, length)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    private fun clamp(raw: ByteArray): BigInteger {
        val key = raw.copyOf()
        key[0] = (key[0].toInt() and 248).toByte()
        key[31] = (key[31].toInt() and 127).toByte()
        key[31] = (key[31].toInt() or 64).toByte()
        return BigInteger(1, key.reversedArray())
    }

    private fun decodeU(data: ByteArray): BigInteger {
        val reversed = data.reversedArray()
        val mask = BigInteger.TWO.pow(255).subtract(BigInteger.ONE)
        return BigInteger(1, reversed).and(mask)
    }

    private fun encodeU(value: BigInteger): ByteArray {
        val masked = value.and(BigInteger.TWO.pow(255).subtract(BigInteger.ONE))
        val bigEndian = masked.toByteArray()
        val stripped = if (bigEndian.size > 32) bigEndian.copyOfRange(bigEndian.size - 32, bigEndian.size) else bigEndian
        val padded = ByteArray(32)
        stripped.copyInto(padded, 32 - stripped.size)
        return padded.reversedArray()
    }

    /** Montgomery ladder, RFC 7748. Returns the 32-byte shared u-coordinate. */
    private fun x25519(scalar: BigInteger, pointBytes: ByteArray): ByteArray {
        val u = decodeU(pointBytes)
        var x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0
        for (bit in 254 downTo 0) {
            val k = scalar.testBit(bit)
            swap = swap xor (if (k) 1 else 0)
            if (swap == 1) {
                val t = x2; x2 = x3; x3 = t
                val t2 = z2; z2 = z3; z3 = t2
            }
            swap = if (k) 1 else 0
            val a = (x2 + z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            x3 = (da + cb).mod(P).let { it.multiply(it).mod(P) }
            z3 = x1.multiply((da - cb).mod(P).let { it.multiply(it).mod(P) }).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa + A24.multiply(e)).mod(P)
        }
        if (swap == 1) {
            val t = x2; x2 = x3; x3 = t
            val t2 = z2; z2 = z3; z3 = t2
        }
        return encodeU(x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P))
    }
}
