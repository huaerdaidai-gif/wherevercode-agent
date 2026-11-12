package wherevercode.agent.brain.oauth

import org.json.JSONObject
import wherevercode.agent.brain.siwc.BrainError
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * PKCE (RFC 7636) + state / nonce generation.
 *
 * - Verifier: 64 random bytes, base64url without padding (86 chars, inside
 *   the RFC 7636 43–128 range, unreserved alphabet only).
 * - Challenge: base64url(SHA-256(verifier)) without padding, method S256.
 * - State / nonce: 32 random bytes, base64url without padding; fresh per
 *   attempt, single-use (the caller consumes the pending attempt on
 *   callback, so a replayed state is rejected).
 *
 * Extracted verbatim from the SIWC flow (where it was `SiwcPkce`); that
 * name remains available as a typealias in `wherevercode.agent.brain.siwc`.
 *
 * RFC 7636 Appendix B test vector (asserted in tests):
 * verifier `dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk` →
 * challenge `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`.
 */
object OAuthPkce {
    private val base64Url = Base64.getUrlEncoder().withoutPadding()

    fun generateVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        return base64Url.encodeToString(bytes)
    }

    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url.encodeToString(digest)
    }

    fun generateState(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return base64Url.encodeToString(bytes)
    }

    fun generateNonce(random: SecureRandom = SecureRandom()): String =
        generateState(random)
}

/** Verified claims from an ID token. */
data class OAuthIdTokenClaims(
    val subject: String,
    val email: String?,
    val name: String?,
    val issuer: String,
    val audience: String,
    val expiresAtSeconds: Long,
)

/**
 * ID-token verification (OIDC Core): RS256 signature against the vendor's
 * published JWKS, plus issuer, audience (the issued client id), expiry, and
 * nonce checks. Implemented with `java.security` only — no new dependency.
 *
 * Extracted verbatim from the SIWC flow (where it was
 * `SiwcIdTokenVerifier`); that name remains available as a typealias in
 * `wherevercode.agent.brain.siwc`.
 *
 * @throws BrainError.AuthFailed on any verification failure (bad signature,
 *   wrong issuer/audience/nonce, expired, malformed). A JWKS fetch failure
 *   is the caller's [BrainError.Transport], raised before calling this.
 */
object OAuthIdTokenVerifier {

    fun verify(
        idToken: String,
        jwksJson: String,
        expectedIssuer: String,
        expectedAudience: String,
        expectedNonce: String?,
        nowMillis: Long,
    ): OAuthIdTokenClaims {
        val parts = idToken.split(".")
        if (parts.size != 3) {
            throw BrainError.AuthFailed("ID token 格式不正确。", errorCode = "id_token_format")
        }
        val (headerSegment, payloadSegment, signatureSegment) = parts
        val header = parseJsonSegment(headerSegment, "ID token header")
        if (header.optString("alg", "") != "RS256") {
            throw BrainError.AuthFailed("ID token 的签名算法不是 RS256。", errorCode = "id_token_alg")
        }
        val kid = header.optString("kid", "")
        if (kid.isBlank()) {
            throw BrainError.AuthFailed("ID token 没有 kid，无法选择验证密钥。", errorCode = "id_token_no_kid")
        }
        val payload = parseJsonSegment(payloadSegment, "ID token payload")
        val claims = checkClaims(
            payload = payload,
            expectedIssuer = expectedIssuer,
            expectedAudience = expectedAudience,
            expectedNonce = expectedNonce,
            nowMillis = nowMillis,
        )
        val publicKey = findRsaKey(jwksJson, kid)
        val signatureValid = try {
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(publicKey)
            signature.update("$headerSegment.$payloadSegment".toByteArray(Charsets.US_ASCII))
            signature.verify(base64UrlDecode(signatureSegment))
        } catch (e: BrainError) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!signatureValid) {
            throw BrainError.AuthFailed("ID token 签名验证失败。", errorCode = "id_token_bad_signature")
        }
        return claims
    }

    private fun checkClaims(
        payload: JSONObject,
        expectedIssuer: String,
        expectedAudience: String,
        expectedNonce: String?,
        nowMillis: Long,
    ): OAuthIdTokenClaims {
        val issuer = payload.optString("iss", "")
        if (issuer != expectedIssuer) {
            throw BrainError.AuthFailed("ID token 的签发方不正确。", errorCode = "id_token_issuer")
        }
        val audience = audienceOf(payload)
        if (audience != expectedAudience) {
            throw BrainError.AuthFailed("ID token 的受众与本次注册的 client_id 不符。", errorCode = "id_token_audience")
        }
        val expiresAtSeconds = payload.optLong("exp", 0L)
        if (expiresAtSeconds <= 0L || expiresAtSeconds * 1_000L <= nowMillis) {
            throw BrainError.AuthFailed("ID token 已过期。", errorCode = "id_token_expired")
        }
        if (expectedNonce != null && payload.optString("nonce", "") != expectedNonce) {
            throw BrainError.AuthFailed("ID token 的 nonce 与本次登录请求不符。", errorCode = "id_token_nonce")
        }
        val subject = payload.optString("sub", "")
        if (subject.isBlank()) {
            throw BrainError.AuthFailed("ID token 没有 subject。", errorCode = "id_token_no_subject")
        }
        return OAuthIdTokenClaims(
            subject = subject,
            email = payload.optString("email", "").ifBlank { null },
            name = payload.optString("name", "").ifBlank { null },
            issuer = issuer,
            audience = audience,
            expiresAtSeconds = expiresAtSeconds,
        )
    }

    /** `aud` may be a string or an array; this flow expects exactly one audience. */
    private fun audienceOf(payload: JSONObject): String {
        val audArray = payload.optJSONArray("aud")
        if (audArray != null) {
            if (audArray.length() != 1) return ""
            return audArray.optString(0, "")
        }
        return payload.optString("aud", "")
    }

    private fun findRsaKey(jwksJson: String, kid: String): java.security.PublicKey {
        val keys = try {
            JSONObject(jwksJson).optJSONArray("keys")
                ?: throw BrainError.AuthFailed("JWKS 中没有密钥。", errorCode = "jwks_no_keys")
        } catch (e: BrainError) {
            throw e
        } catch (_: Exception) {
            throw BrainError.AuthFailed("JWKS 无法解析。", errorCode = "jwks_unparseable")
        }
        for (i in 0 until keys.length()) {
            val key = keys.optJSONObject(i) ?: continue
            if (key.optString("kid", "") != kid) continue
            if (key.optString("kty", "") != "RSA") continue
            val modulus = base64UrlDecode(key.optString("n", ""))
            val exponent = base64UrlDecode(key.optString("e", ""))
            if (modulus.isEmpty() || exponent.isEmpty()) continue
            return try {
                val spec = RSAPublicKeySpec(
                    BigInteger(1, modulus),
                    BigInteger(1, exponent),
                )
                KeyFactory.getInstance("RSA").generatePublic(spec)
            } catch (_: Exception) {
                throw BrainError.AuthFailed("JWKS 中的 RSA 密钥无法使用。", errorCode = "jwks_key_unusable")
            }
        }
        throw BrainError.AuthFailed("JWKS 中没有与 ID token kid 匹配的密钥。", errorCode = "jwks_no_matching_key")
    }

    private fun parseJsonSegment(segment: String, what: String): JSONObject {
        return try {
            JSONObject(String(base64UrlDecode(segment), Charsets.UTF_8))
        } catch (_: Exception) {
            throw BrainError.AuthFailed("$what 无法解析。", errorCode = "id_token_format")
        }
    }

    /** Base64url decode with missing padding restored. */
    fun base64UrlDecode(value: String): ByteArray {
        if (value.isBlank()) return ByteArray(0)
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return Base64.getUrlDecoder().decode(padded)
    }
}
