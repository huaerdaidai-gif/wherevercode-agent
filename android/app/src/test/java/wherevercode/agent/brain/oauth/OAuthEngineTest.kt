package wherevercode.agent.brain.oauth

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import wherevercode.agent.brain.siwc.BrainError
import wherevercode.agent.brain.siwc.SiwcAuthManager
import wherevercode.agent.brain.siwc.SiwcBrainAdapter
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Generic OAuth 2.0 + PKCE engine tests.
 *
 * The engine is exercised against a fictitious vendor ("Acme Test") with a
 * fake transport — never the real ChatGPT endpoints, never a real account.
 * The ID tokens are real RS256 JWTs signed with a throwaway test keypair.
 * All token values below are fake placeholders (`acme-access-1` etc.);
 * no real token or key appears in this file.
 *
 * Conventions (M2): backtick test names, one behaviour per test, no mocks
 * of the code under test — the fakes sit at the transport port.
 */

// Fixed "now" for deterministic expiry tests: 2026-11-08T07:00:00Z.
private const val FIXED_NOW_MILLIS = 1_792_828_800_000L
private const val FIXED_NOW_SECONDS = 1_792_828_800L
private const val ACME_TEST_KID = "acme-test-kid-1"
private const val ACME_TEST_HOST_ID = "urn:uuid:11111111-2222-4333-8444-555555555555"

/** Fictitious vendor: proves the engine is generic, not ChatGPT-shaped. */
private val ACME = VendorOAuthConfig(
    vendorId = "acme-test",
    displayName = "Acme Test",
    operatorName = "Acme",
    issuer = "https://auth.acme.test",
    discoveryUrl = "https://auth.acme.test/.well-known/openid-configuration",
    authorizationUrl = "https://auth.acme.test/oauth/authorize",
    tokenUrl = "https://auth.acme.test/oauth/token",
    revocationUrl = "https://auth.acme.test/oauth/revoke",
    jwksUri = "https://auth.acme.test/.well-known/jwks.json",
    scopes = listOf("openid", "profile", "acme.read"),
    resource = "https://api.acme.test/v1",
    dynamicClientId = "dynamic_acme_client",
    callbackPath = "/auth/callback",
    sharingScope = "acme.premium",
)

/** Same fictitious vendor, but with no `resource` and no dynamic client id. */
private val ACME_NO_RESOURCE = ACME.copy(
    vendorId = "acme-nores",
    resource = null,
    dynamicClientId = null,
)

/**
 * Discovery document for the fictitious vendor. The authorize endpoint
 * differs from the config fallback on purpose: it proves the engine uses
 * the discovered endpoint once discovery succeeds.
 */
private val ACME_DISCOVERY_JSON = JSONObject().apply {
    put("issuer", "https://auth.acme.test")
    put("authorization_endpoint", "https://auth.acme.test/oauth/authorize-live")
    put("token_endpoint", "https://auth.acme.test/oauth/token")
    put("revocation_endpoint", "https://auth.acme.test/oauth/revoke")
    put("jwks_uri", "https://auth.acme.test/.well-known/jwks.json")
}.toString()

private fun b64url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun stripLeadingZero(bytes: ByteArray): ByteArray =
    if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes

private fun generateRsaKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

private fun jwksJson(keyPair: KeyPair, kid: String): String {
    val pub = keyPair.public as RSAPublicKey
    val key = JSONObject()
    key.put("kty", "RSA")
    key.put("kid", kid)
    key.put("use", "sig")
    key.put("alg", "RS256")
    key.put("n", b64url(stripLeadingZero(pub.modulus.toByteArray())))
    key.put("e", b64url(stripLeadingZero(pub.publicExponent.toByteArray())))
    return JSONObject().put("keys", JSONArray().put(key)).toString()
}

/** Build a real RS256 JWT (header.payload.signature) for the given claims. */
private fun makeIdToken(
    keyPair: KeyPair,
    kid: String,
    issuer: String = "https://auth.acme.test",
    audience: String,
    subject: String = "acme-sub-123",
    email: String? = "user@acme.test",
    nonce: String?,
    expiresAtSeconds: Long,
    issuedAtSeconds: Long = expiresAtSeconds - 3600,
): String {
    val header = JSONObject().put("alg", "RS256").put("kid", kid).put("typ", "JWT")
    val payload = JSONObject()
    payload.put("iss", issuer)
    payload.put("aud", audience)
    payload.put("sub", subject)
    email?.let { payload.put("email", it) }
    nonce?.let { payload.put("nonce", it) }
    payload.put("exp", expiresAtSeconds)
    payload.put("iat", issuedAtSeconds)
    val headerSegment = b64url(header.toString().toByteArray(Charsets.UTF_8))
    val payloadSegment = b64url(payload.toString().toByteArray(Charsets.UTF_8))
    val signingInput = "$headerSegment.$payloadSegment".toByteArray(Charsets.US_ASCII)
    val signature = Signature.getInstance("SHA256withRSA").apply {
        initSign(keyPair.private)
        update(signingInput)
    }.sign()
    return "$headerSegment.$payloadSegment.${b64url(signature)}"
}

private fun tokenResponseJson(
    accessToken: String = "acme-access-1",
    refreshToken: String? = "acme-refresh-1",
    idToken: String? = "fake-id-token",
    scope: String = "openid profile acme.read",
    expiresIn: Long? = 3600,
): String {
    val json = JSONObject()
    json.put("access_token", accessToken)
    refreshToken?.let { json.put("refresh_token", it) }
    idToken?.let { json.put("id_token", it) }
    json.put("token_type", "Bearer")
    expiresIn?.let { json.put("expires_in", it) }
    json.put("scope", scope)
    return json.toString()
}

/** Fake [OAuthHttpTransport]: scripted discovery / JWKS / token / revoke. */
private class FakeOAuthTransport : OAuthHttpTransport {
    data class Recorded(
        val method: String,
        val url: String,
        val params: Map<String, String> = emptyMap(),
    )

    val requests = mutableListOf<Recorded>()
    var discoveryJson: String = ACME_DISCOVERY_JSON
    var discoveryStatus: Int = 200
    var jwksJson: String = ""
    var tokenHandler: (Map<String, String>) -> OAuthHttpResponse =
        { throw AssertionError("unexpected token POST") }
    var revokeHandler: (Map<String, String>) -> OAuthHttpResponse =
        { OAuthHttpResponse(200, "") }
    var getThrows: IOException? = null
    var postThrows: IOException? = null

    fun discoveryGets(): List<Recorded> =
        requests.filter { it.method == "GET" && it.url == ACME.discoveryUrl }

    fun tokenPosts(): List<Recorded> =
        requests.filter { it.method == "POST" && it.url == ACME.tokenUrl }

    fun revokePosts(): List<Recorded> =
        requests.filter { it.method == "POST" && it.url == ACME.revocationUrl }

    override fun get(url: String): OAuthHttpResponse {
        requests += Recorded("GET", url)
        getThrows?.let { throw it }
        return when (url) {
            ACME.discoveryUrl -> OAuthHttpResponse(discoveryStatus, discoveryJson)
            ACME.jwksUri -> OAuthHttpResponse(200, jwksJson)
            else -> throw AssertionError("unexpected GET $url")
        }
    }

    override fun postForm(url: String, params: Map<String, String>): OAuthHttpResponse {
        requests += Recorded("POST", url, params)
        postThrows?.let { throw it }
        return when (url) {
            ACME.tokenUrl -> tokenHandler(params)
            ACME.revocationUrl -> revokeHandler(params)
            else -> throw AssertionError("unexpected POST $url")
        }
    }
}

/** Shared fixture: engine for the fictitious vendor + fake transport. */
private class Fixture(
    val config: VendorOAuthConfig = ACME,
) {
    val fake = FakeOAuthTransport()
    val keyPair: KeyPair = generateRsaKeyPair()
    var nowMillis: Long = FIXED_NOW_MILLIS
    val engine = OAuthEngine(
        config = config,
        http = fake,
        clockMillis = { nowMillis },
        sleeper = {},
    )

    init {
        fake.jwksJson = jwksJson(keyPair, ACME_TEST_KID)
    }

    fun validIdToken(
        audience: String,
        nonce: String?,
        expiresAtSeconds: Long = FIXED_NOW_SECONDS + 3600,
        keyPair: KeyPair = this.keyPair,
    ): String = makeIdToken(
        keyPair = keyPair,
        kid = ACME_TEST_KID,
        audience = audience,
        nonce = nonce,
        expiresAtSeconds = expiresAtSeconds,
    )
}

private suspend fun assertAuthFailedSuspend(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: BrainError.AuthFailed) {
        return
    }
    fail("expected BrainError.AuthFailed")
}

private suspend fun assertTransportSuspend(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: BrainError.Transport) {
        return
    }
    fail("expected BrainError.Transport")
}

// ----------------------------------------------------------------------
// Authorization URL
// ----------------------------------------------------------------------

class OAuthAuthorizationUrlTest {

    private val fixture = Fixture()

    @Test
    fun `buildAuthorizationUrl is deterministic for fixed inputs`() {
        val url = fixture.engine.buildAuthorizationUrl(
            clientId = "dynamic_acme_client",
            redirectUri = "http://127.0.0.1:1455/auth/callback",
            state = "state-1",
            nonce = "nonce-1",
            codeChallenge = "challenge-1",
            extraParams = linkedMapOf(
                "agent_name_hint" to "AcmeTestApp",
                "ext_agent_host_id" to ACME_TEST_HOST_ID,
            ),
        )
        assertEquals(
            "https://auth.acme.test/oauth/authorize" +
                "?client_id=dynamic_acme_client" +
                "&agent_name_hint=AcmeTestApp" +
                "&ext_agent_host_id=urn%3Auuid%3A11111111-2222-4333-8444-555555555555" +
                "&response_type=code" +
                "&redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback" +
                "&scope=openid+profile+acme.read" +
                "&resource=https%3A%2F%2Fapi.acme.test%2Fv1" +
                "&state=state-1&nonce=nonce-1" +
                "&code_challenge_method=S256&code_challenge=challenge-1",
            url,
        )
    }

    @Test
    fun `buildAuthorizationUrl omits resource when the config has none`() {
        val engine = Fixture(ACME_NO_RESOURCE).engine
        val url = engine.buildAuthorizationUrl(
            clientId = "acme-fixed-client",
            redirectUri = "http://127.0.0.1:1455/auth/callback",
            state = "state-1",
            nonce = "nonce-1",
            codeChallenge = "challenge-1",
        )
        assertFalse(url.contains("resource="))
        assertTrue(url.contains("scope=openid+profile+acme.read"))
        assertTrue(url.contains("client_id=acme-fixed-client&response_type=code"))
    }

    @Test
    fun `buildAuthorizationUrl uses the discovered authorize endpoint after resolve`() = runTest {
        val endpoints = fixture.engine.resolveEndpoints()
        assertEquals("https://auth.acme.test/oauth/authorize-live", endpoints.authorizeUrl)
        val url = fixture.engine.buildAuthorizationUrl(
            clientId = "dynamic_acme_client",
            redirectUri = "http://127.0.0.1:1455/auth/callback",
            state = "state-1",
            nonce = "nonce-1",
            codeChallenge = "challenge-1",
        )
        assertTrue(url.startsWith("https://auth.acme.test/oauth/authorize-live?"))
    }
}

// ----------------------------------------------------------------------
// Discovery
// ----------------------------------------------------------------------

class OAuthDiscoveryTest {

    private val fixture = Fixture()

    @Test
    fun `discovery returns live endpoints and caches them`() = runTest {
        val first = fixture.engine.resolveEndpoints()
        assertEquals("https://auth.acme.test/oauth/authorize-live", first.authorizeUrl)
        assertEquals("https://auth.acme.test/oauth/token", first.tokenUrl)
        val second = fixture.engine.resolveEndpoints()
        assertEquals(first, second)
        assertEquals(1, fixture.fake.discoveryGets().size)
    }

    @Test
    fun `discovery non-200 falls back to config constants and does not cache`() = runTest {
        fixture.fake.discoveryStatus = 500
        val fallback = fixture.engine.resolveEndpoints()
        assertEquals(ACME.authorizationUrl, fallback.authorizeUrl)
        assertEquals(ACME.tokenUrl, fallback.tokenUrl)
        // Discovery recovers: the next call resolves the live endpoints.
        fixture.fake.discoveryStatus = 200
        val live = fixture.engine.resolveEndpoints()
        assertEquals("https://auth.acme.test/oauth/authorize-live", live.authorizeUrl)
        assertEquals(2, fixture.fake.discoveryGets().size)
    }

    @Test
    fun `discovery issuer mismatch falls back and does not cache`() = runTest {
        fixture.fake.discoveryJson = JSONObject(ACME_DISCOVERY_JSON)
            .put("issuer", "https://auth.evil.test").toString()
        val fallback = fixture.engine.resolveEndpoints()
        assertEquals(ACME.authorizationUrl, fallback.authorizeUrl)
        fixture.fake.discoveryJson = ACME_DISCOVERY_JSON
        val live = fixture.engine.resolveEndpoints()
        assertEquals("https://auth.acme.test/oauth/authorize-live", live.authorizeUrl)
    }

    @Test
    fun `discovery network failure falls back to config constants`() = runTest {
        fixture.fake.getThrows = IOException("no route")
        val fallback = fixture.engine.resolveEndpoints()
        assertEquals(ACME.authorizationUrl, fallback.authorizeUrl)
        assertEquals(ACME.revocationUrl, fallback.revocationUrl)
        assertEquals(ACME.jwksUri, fallback.jwksUri)
    }
}

// ----------------------------------------------------------------------
// Code exchange / refresh
// ----------------------------------------------------------------------

class OAuthTokenTest {

    private val fixture = Fixture()

    @Test
    fun `exchangeAuthorizationCode posts the grant and parses the response`() = runTest {
        fixture.fake.tokenHandler = { params ->
            assertEquals("authorization_code", params["grant_type"])
            assertEquals("acme-issued-1", params["client_id"])
            assertEquals("code-1", params["code"])
            assertEquals("verifier-1", params["code_verifier"])
            assertEquals("http://127.0.0.1:1455/auth/callback", params["redirect_uri"])
            assertEquals("https://api.acme.test/v1", params["resource"])
            OAuthHttpResponse(200, tokenResponseJson())
        }
        val response = fixture.engine.exchangeAuthorizationCode(
            tokenUrl = ACME.tokenUrl,
            params = mapOf(
                "grant_type" to "authorization_code",
                "client_id" to "acme-issued-1",
                "code" to "code-1",
                "code_verifier" to "verifier-1",
                "redirect_uri" to "http://127.0.0.1:1455/auth/callback",
                "resource" to "https://api.acme.test/v1",
            ),
        )
        assertEquals("acme-access-1", response.accessToken)
        assertEquals("acme-refresh-1", response.refreshToken)
        assertEquals("fake-id-token", response.idToken)
        assertEquals(3600L, response.expiresInSeconds)
        assertEquals("openid profile acme.read", response.scope)
    }

    @Test
    fun `exchangeAuthorizationCode without expires_in defaults to 3600`() = runTest {
        fixture.fake.tokenHandler = { _ ->
            OAuthHttpResponse(200, tokenResponseJson(expiresIn = null))
        }
        val response = fixture.engine.exchangeAuthorizationCode(
            tokenUrl = ACME.tokenUrl,
            params = mapOf("grant_type" to "authorization_code"),
        )
        assertEquals(3600L, response.expiresInSeconds)
    }

    @Test
    fun `exchangeAuthorizationCode without refresh token returns null refresh`() = runTest {
        fixture.fake.tokenHandler = { _ ->
            OAuthHttpResponse(200, tokenResponseJson(refreshToken = null))
        }
        val response = fixture.engine.exchangeAuthorizationCode(
            tokenUrl = ACME.tokenUrl,
            params = mapOf("grant_type" to "authorization_code"),
        )
        assertNull(response.refreshToken)
    }

    @Test
    fun `exchangeAuthorizationCode invalid_grant is AuthFailed and calls onGrantDead`() = runTest {
        var grantDeadCalls = 0
        fixture.fake.tokenHandler = { _ ->
            OAuthHttpResponse(400, """{"error":"invalid_grant"}""")
        }
        assertAuthFailedSuspend {
            fixture.engine.exchangeAuthorizationCode(
                tokenUrl = ACME.tokenUrl,
                params = mapOf("grant_type" to "authorization_code"),
                onGrantDead = { grantDeadCalls++ },
            )
        }
        assertEquals(1, grantDeadCalls)
    }

    @Test
    fun `exchangeAuthorizationCode invalid_client is AuthFailed and calls onGrantDead`() = runTest {
        var grantDeadCalls = 0
        fixture.fake.tokenHandler = { _ ->
            OAuthHttpResponse(400, """{"error":"invalid_client"}""")
        }
        assertAuthFailedSuspend {
            fixture.engine.exchangeAuthorizationCode(
                tokenUrl = ACME.tokenUrl,
                params = mapOf("grant_type" to "authorization_code"),
                onGrantDead = { grantDeadCalls++ },
            )
        }
        assertEquals(1, grantDeadCalls)
    }

    @Test
    fun `exchangeAuthorizationCode 4xx includes the error_description in the message`() = runTest {
        // 回归（2026-11-11 真机 invalid_request）：只看 error 码猜不出
        // 缺了什么，Google 的 error_description（如 "client_secret is
        // missing."）才是可操作的诊断，必须进消息（状态行 + 日志）。
        fixture.fake.tokenHandler = { _ ->
            OAuthHttpResponse(
                400,
                """{"error":"invalid_request","error_description":"client_secret is missing."}""",
            )
        }
        try {
            fixture.engine.exchangeAuthorizationCode(
                tokenUrl = ACME.tokenUrl,
                params = mapOf("grant_type" to "authorization_code"),
            )
            fail("expected BrainError.AuthFailed")
        } catch (e: BrainError.AuthFailed) {
            assertEquals("invalid_request", e.errorCode)
            assertTrue(e.message!!.contains("invalid_request"))
            assertTrue(e.message!!.contains("client_secret is missing."))
        }
    }

    @Test
    fun `exchangeAuthorizationCode 500 is Transport and does not call onGrantDead`() = runTest {
        var grantDeadCalls = 0
        fixture.fake.tokenHandler = { _ -> OAuthHttpResponse(500, "boom") }
        assertTransportSuspend {
            fixture.engine.exchangeAuthorizationCode(
                tokenUrl = ACME.tokenUrl,
                params = mapOf("grant_type" to "authorization_code"),
                onGrantDead = { grantDeadCalls++ },
            )
        }
        assertEquals(0, grantDeadCalls)
    }

    @Test
    fun `exchangeAuthorizationCode network failure is Transport`() = runTest {
        fixture.fake.postThrows = IOException("no route")
        assertTransportSuspend {
            fixture.engine.exchangeAuthorizationCode(
                tokenUrl = ACME.tokenUrl,
                params = mapOf("grant_type" to "authorization_code"),
            )
        }
    }

    @Test
    fun `exchangeAuthorizationCode malformed JSON is Transport`() = runTest {
        fixture.fake.tokenHandler = { _ -> OAuthHttpResponse(200, "{not json") }
        assertTransportSuspend {
            fixture.engine.exchangeAuthorizationCode(
                tokenUrl = ACME.tokenUrl,
                params = mapOf("grant_type" to "authorization_code"),
            )
        }
    }

    @Test
    fun `refreshGrant posts the refresh grant and parses the response`() = runTest {
        fixture.fake.tokenHandler = { params ->
            assertEquals("refresh_token", params["grant_type"])
            assertEquals("acme-issued-1", params["client_id"])
            assertEquals("acme-refresh-1", params["refresh_token"])
            assertEquals("https://api.acme.test/v1", params["resource"])
            assertFalse("refresh must omit scope", params.containsKey("scope"))
            OAuthHttpResponse(
                200,
                tokenResponseJson(accessToken = "acme-access-2", refreshToken = "acme-refresh-2"),
            )
        }
        val response = fixture.engine.refreshGrant(
            tokenUrl = ACME.tokenUrl,
            params = mapOf(
                "grant_type" to "refresh_token",
                "client_id" to "acme-issued-1",
                "refresh_token" to "acme-refresh-1",
                "resource" to "https://api.acme.test/v1",
            ),
        )
        assertEquals("acme-access-2", response.accessToken)
        assertEquals("acme-refresh-2", response.refreshToken)
    }

    @Test
    fun `refreshGrant invalid_grant is AuthFailed and calls onGrantDead`() = runTest {
        var grantDeadCalls = 0
        fixture.fake.tokenHandler = { _ ->
            OAuthHttpResponse(400, """{"error":"invalid_grant"}""")
        }
        assertAuthFailedSuspend {
            fixture.engine.refreshGrant(
                tokenUrl = ACME.tokenUrl,
                params = mapOf("grant_type" to "refresh_token"),
                onGrantDead = { grantDeadCalls++ },
            )
        }
        assertEquals(1, grantDeadCalls)
    }
}

// ----------------------------------------------------------------------
// ID-token verification
// ----------------------------------------------------------------------

class OAuthIdTokenTest {

    private val fixture = Fixture()

    @Test
    fun `verifyIdToken accepts a valid token`() = runTest {
        val idToken = fixture.validIdToken("acme-issued-1", "nonce-1")
        val claims = fixture.engine.verifyIdToken(idToken, "acme-issued-1", "nonce-1")
        assertEquals("acme-sub-123", claims.subject)
        assertEquals("user@acme.test", claims.email)
        assertEquals("https://auth.acme.test", claims.issuer)
        assertEquals("acme-issued-1", claims.audience)
    }

    @Test
    fun `verifyIdToken rejects a wrong audience`() = runTest {
        val idToken = fixture.validIdToken("acme-someone-else", "nonce-1")
        assertAuthFailedSuspend {
            fixture.engine.verifyIdToken(idToken, "acme-issued-1", "nonce-1")
        }
    }

    @Test
    fun `verifyIdToken rejects a wrong nonce`() = runTest {
        val idToken = fixture.validIdToken("acme-issued-1", "some-other-nonce")
        assertAuthFailedSuspend {
            fixture.engine.verifyIdToken(idToken, "acme-issued-1", "nonce-1")
        }
    }

    @Test
    fun `verifyIdToken rejects an expired token`() = runTest {
        val idToken = fixture.validIdToken(
            "acme-issued-1",
            "nonce-1",
            expiresAtSeconds = FIXED_NOW_SECONDS - 10,
        )
        assertAuthFailedSuspend {
            fixture.engine.verifyIdToken(idToken, "acme-issued-1", "nonce-1")
        }
    }

    @Test
    fun `verifyIdToken rejects a token signed with another key`() = runTest {
        val otherKey = generateRsaKeyPair()
        val idToken = fixture.validIdToken("acme-issued-1", "nonce-1", keyPair = otherKey)
        assertAuthFailedSuspend {
            fixture.engine.verifyIdToken(idToken, "acme-issued-1", "nonce-1")
        }
    }

    @Test
    fun `verifyIdToken rejects a wrong issuer`() = runTest {
        val idToken = makeIdToken(
            keyPair = fixture.keyPair,
            kid = ACME_TEST_KID,
            issuer = "https://auth.evil.test",
            audience = "acme-issued-1",
            nonce = "nonce-1",
            expiresAtSeconds = FIXED_NOW_SECONDS + 3600,
        )
        assertAuthFailedSuspend {
            fixture.engine.verifyIdToken(idToken, "acme-issued-1", "nonce-1")
        }
    }

    @Test
    fun `verifyIdToken JWKS fetch failure is Transport`() = runTest {
        val idToken = fixture.validIdToken("acme-issued-1", "nonce-1")
        fixture.fake.getThrows = IOException("no route")
        // Discovery itself fails first (fallback constants), then JWKS fails.
        assertTransportSuspend {
            fixture.engine.verifyIdToken(idToken, "acme-issued-1", "nonce-1")
        }
    }
}

// ----------------------------------------------------------------------
// Revoke
// ----------------------------------------------------------------------

class OAuthRevokeTest {

    private val fixture = Fixture()

    private fun revokeParams() = mapOf(
        "token" to "acme-refresh-1",
        "token_type_hint" to "refresh_token",
        "client_id" to "acme-issued-1",
    )

    @Test
    fun `revokeToken success on first attempt`() = runTest {
        val result = fixture.engine.revokeToken(
            revocationUrl = ACME.revocationUrl,
            params = revokeParams(),
            maxAttempts = 3,
            backoffMillis = 1_000L,
        )
        assertTrue(result.remoteRevoked)
        assertEquals(1, result.attempts)
        val call = fixture.fake.revokePosts().single()
        assertEquals("acme-issued-1", call.params["client_id"])
        assertEquals("acme-refresh-1", call.params["token"])
        assertEquals("refresh_token", call.params["token_type_hint"])
    }

    @Test
    fun `revokeToken retries after 500 then succeeds`() = runTest {
        var calls = 0
        fixture.fake.revokeHandler = { _ ->
            calls++
            if (calls == 1) OAuthHttpResponse(500, "boom") else OAuthHttpResponse(200, "")
        }
        val result = fixture.engine.revokeToken(
            revocationUrl = ACME.revocationUrl,
            params = revokeParams(),
            maxAttempts = 3,
            backoffMillis = 1_000L,
        )
        assertTrue(result.remoteRevoked)
        assertEquals(2, result.attempts)
    }

    @Test
    fun `revokeToken gives up after max attempts`() = runTest {
        fixture.fake.revokeHandler = { _ -> OAuthHttpResponse(500, "boom") }
        val result = fixture.engine.revokeToken(
            revocationUrl = ACME.revocationUrl,
            params = revokeParams(),
            maxAttempts = 3,
            backoffMillis = 1_000L,
        )
        assertFalse(result.remoteRevoked)
        assertEquals(3, result.attempts)
    }

    @Test
    fun `revokeToken stops on terminal 400`() = runTest {
        fixture.fake.revokeHandler = { _ -> OAuthHttpResponse(400, """{"error":"invalid_client"}""") }
        val result = fixture.engine.revokeToken(
            revocationUrl = ACME.revocationUrl,
            params = revokeParams(),
            maxAttempts = 3,
            backoffMillis = 1_000L,
        )
        assertFalse(result.remoteRevoked)
        assertEquals(1, result.attempts)
    }

    @Test
    fun `revokeToken retries after network failure then succeeds`() = runTest {
        var calls = 0
        fixture.fake.revokeHandler = { _ ->
            calls++
            if (calls == 1) throw IOException("no route") else OAuthHttpResponse(200, "")
        }
        // postThrows would fire on every call; the handler throws instead.
        val result = fixture.engine.revokeToken(
            revocationUrl = ACME.revocationUrl,
            params = revokeParams(),
            maxAttempts = 3,
            backoffMillis = 1_000L,
        )
        assertTrue(result.remoteRevoked)
        assertEquals(2, result.attempts)
    }
}

// ----------------------------------------------------------------------
// PKCE
// ----------------------------------------------------------------------

class OAuthPkceTest {

    @Test
    fun `RFC 7636 Appendix B test vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OAuthPkce.challengeFor(verifier),
        )
    }

    @Test
    fun `generated verifier is 43 to 128 chars of unreserved alphabet`() {
        repeat(20) {
            val verifier = OAuthPkce.generateVerifier()
            assertTrue(verifier.length in 43..128)
            assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            assertFalse(verifier.contains("="))
            assertFalse(verifier.contains("+"))
            assertFalse(verifier.contains("/"))
        }
    }

    @Test
    fun `state and nonce are fresh random values`() {
        val state1 = OAuthPkce.generateState()
        val state2 = OAuthPkce.generateState()
        val nonce = OAuthPkce.generateNonce()
        assertTrue(state1 != state2)
        assertEquals(43, state1.length)
        assertEquals(43, nonce.length)
    }
}

// ----------------------------------------------------------------------
// Vendor table
// ----------------------------------------------------------------------

class VendorOAuthConfigsTest {

    @Test
    fun `table holds ChatGPT and login-only Gemini`() {
        assertEquals(
            listOf(VendorOAuthConfigs.CHATGPT, VendorOAuthConfigs.GEMINI),
            VendorOAuthConfigs.all,
        )
        assertEquals(
            listOf("chatgpt", "gemini"),
            VendorOAuthConfigs.all.map { it.vendorId },
        )
    }

    @Test
    fun `Gemini is login-only with no client id registered yet`() {
        val config = VendorOAuthConfigs.GEMINI
        assertFalse("Gemini login must never be presented as an inference brain", config.inferenceSupported)
        assertNull("no V5-owned Desktop OAuth client is registered yet", config.fixedClientId)
        assertNull("Google has no DCR", config.dynamicClientId)
        assertNull("Gemini has no plan-usage sharing scope", config.sharingScope)
    }

    @Test
    fun `ChatGPT config matches the SIWC constants`() {
        val config = VendorOAuthConfigs.CHATGPT
        assertEquals("chatgpt", config.vendorId)
        assertEquals("ChatGPT", config.displayName)
        assertEquals("OpenAI", config.operatorName)
        assertEquals(SiwcAuthManager.ISSUER, config.issuer)
        assertEquals(SiwcAuthManager.DISCOVERY_URL, config.discoveryUrl)
        assertEquals(SiwcAuthManager.AUTHORIZE_URL, config.authorizationUrl)
        assertEquals(SiwcAuthManager.TOKEN_URL, config.tokenUrl)
        assertEquals(SiwcAuthManager.REVOCATION_URL, config.revocationUrl)
        assertEquals(SiwcAuthManager.JWKS_URL, config.jwksUri)
        assertEquals(SiwcAuthManager.SHARING_SCOPE, config.sharingScope)
        assertEquals(SiwcBrainAdapter.SIWC_SCOPES, config.scopes)
        assertEquals(SiwcBrainAdapter.SIWC_RESOURCE, config.resource)
        assertEquals(SiwcBrainAdapter.SIWC_DYNAMIC_CLIENT_ID, config.dynamicClientId)
        assertEquals(SiwcBrainAdapter.SIWC_CALLBACK_PATH, config.callbackPath)
    }

    @Test
    fun `unverified vendors are not in the table`() {
        val vendorIds = VendorOAuthConfigs.all.map { it.vendorId }
        // Gemini IS in the table now, but only as a login-only entry
        // (inferenceSupported = false, fixedClientId = null); it is not
        // an unverified-vendor omission any more. Grok lives in the
        // device-code table, not here.
        for (unverified in listOf("grok", "workbuddy")) {
            assertFalse("unverified vendor $unverified must not be configured", vendorIds.contains(unverified))
        }
    }
}
