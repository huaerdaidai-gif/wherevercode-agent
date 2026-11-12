package wherevercode.agent.brain.siwc

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * SIWC OAuth tests (M3 step 2). JUnit 4 + coroutines-test; Robolectric only
 * for the Keystore-backed store test ([KeystoreSiwcTokenStoreTest]).
 *
 * No test touches the real OpenAI servers, a real ChatGPT account, or the
 * plan APIs: the token / discovery / JWKS endpoints are all faked
 * ([FakeHttpTransport]), the ID tokens are real RS256 JWTs signed with a
 * throwaway test keypair, and the loopback callback uses a real local
 * ServerSocket on 127.0.0.1 only. A test that needs the network to pass is
 * a broken test.
 *
 * Conventions (M2): backtick test names, one behaviour per test, no mocks
 * of the code under test — the fakes sit at the transport / storage ports.
 */

// Fixed "now" for deterministic expiry tests: 2026-11-08T07:00:00Z.
private const val FIXED_NOW_MILLIS = 1_792_828_800_000L
private const val FIXED_NOW_SECONDS = 1_792_828_800L
private const val TEST_KID = "test-kid-1"
private const val TEST_HOST_ID = "urn:uuid:11111111-2222-4333-8444-555555555555"
private const val ISSUED_CLIENT_ID = "oaiapp_testissued123"

private val DISCOVERY_JSON = JSONObject().apply {
    put("issuer", "https://auth.openai.com")
    put("authorization_endpoint", "https://auth.openai.com/api/accounts/authorize")
    put("token_endpoint", "https://auth.openai.com/api/accounts/oauth/token")
    put("revocation_endpoint", "https://auth.openai.com/api/accounts/oauth/revoke")
    put("jwks_uri", "https://auth.openai.com/.well-known/jwks.json")
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
    issuer: String = "https://auth.openai.com",
    audience: String,
    subject: String = "sub-123",
    email: String? = "user@example.com",
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
    idToken: String,
    accessToken: String = "access-1",
    refreshToken: String? = "refresh-1",
    scope: String = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
    expiresIn: Long = 3600,
): String {
    val json = JSONObject()
    json.put("access_token", accessToken)
    refreshToken?.let { json.put("refresh_token", it) }
    json.put("id_token", idToken)
    json.put("token_type", "Bearer")
    json.put("expires_in", expiresIn)
    json.put("scope", scope)
    return json.toString()
}

/** In-memory [SiwcSecretStore] fake (no Android, no Keystore). */
private class InMemorySecretStore : SiwcSecretStore {
    val map = mutableMapOf<String, String>()
    override fun put(ref: String, value: String) {
        map[ref] = value
    }

    override fun get(ref: String): String? = map[ref]
    override fun delete(ref: String) {
        map.remove(ref)
    }
}

private class FixedHostIdStore : HostIdStore {
    override fun getOrCreateHostId(): String = TEST_HOST_ID
}

/** Fake [SiwcHttpTransport]: scripted discovery / JWKS / token / revoke. */
private class FakeHttpTransport : SiwcHttpTransport {
    data class Recorded(
        val method: String,
        val url: String,
        val params: Map<String, String> = emptyMap(),
    )

    val requests = mutableListOf<Recorded>()
    var discoveryJson: String = DISCOVERY_JSON
    var jwksJson: String = ""
    var tokenHandler: (Map<String, String>) -> SiwcHttpResponse =
        { throw AssertionError("unexpected token POST") }
    var revokeHandler: (Map<String, String>) -> SiwcHttpResponse =
        { SiwcHttpResponse(200, "") }
    var getThrows: IOException? = null
    var postThrows: IOException? = null

    fun tokenPosts(): List<Recorded> =
        requests.filter { it.method == "POST" && it.url == SiwcAuthManager.TOKEN_URL }

    fun revokePosts(): List<Recorded> =
        requests.filter { it.method == "POST" && it.url == SiwcAuthManager.REVOCATION_URL }

    override fun get(url: String): SiwcHttpResponse {
        requests += Recorded("GET", url)
        getThrows?.let { throw it }
        return when (url) {
            SiwcAuthManager.DISCOVERY_URL -> SiwcHttpResponse(200, discoveryJson)
            SiwcAuthManager.JWKS_URL -> SiwcHttpResponse(200, jwksJson)
            else -> throw AssertionError("unexpected GET $url")
        }
    }

    override fun postForm(url: String, params: Map<String, String>): SiwcHttpResponse {
        requests += Recorded("POST", url, params)
        postThrows?.let { throw it }
        return when (url) {
            SiwcAuthManager.TOKEN_URL -> tokenHandler(params)
            SiwcAuthManager.REVOCATION_URL -> revokeHandler(params)
            else -> throw AssertionError("unexpected POST $url")
        }
    }
}

/** Shared fixture: real store class + fake transport / secrets / host id. */
private class Fixture {
    val secrets = InMemorySecretStore()
    val tokenStore = KeystoreSiwcTokenStore(secrets)
    val fake = FakeHttpTransport()
    val keyPair: KeyPair = generateRsaKeyPair()
    var nowMillis: Long = FIXED_NOW_MILLIS
    val manager = SiwcAuthManager(
        tokenStore = tokenStore,
        hostIdStore = FixedHostIdStore(),
        secrets = secrets,
        http = fake,
        clockMillis = { nowMillis },
        sleeper = {},
    )

    init {
        fake.jwksJson = jwksJson(keyPair, TEST_KID)
    }

    fun seedTokens(
        accessToken: String = "old-access",
        refreshToken: String? = "old-refresh",
        idToken: String? = null,
        clientId: String = ISSUED_CLIENT_ID,
        expiresAtMillis: Long = FIXED_NOW_MILLIS + 3_600_000L,
    ) {
        tokenStore.saveTokens(
            SiwcTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                idToken = idToken,
                clientId = clientId,
                hostId = TEST_HOST_ID,
                accessTokenExpiresAtMillis = expiresAtMillis,
            ),
        )
    }

    fun seedIdentity(email: String = "user@example.com") {
        secrets.put(
            SiwcAuthManager.KEY_IDENTITY_JSON,
            JSONObject().put("sub", "sub-123").put("email", email).toString(),
        )
    }

    fun validIdToken(
        audience: String,
        nonce: String?,
        expiresAtSeconds: Long = FIXED_NOW_SECONDS + 3600,
        keyPair: KeyPair = this.keyPair,
    ): String = makeIdToken(
        keyPair = keyPair,
        kid = TEST_KID,
        audience = audience,
        nonce = nonce,
        expiresAtSeconds = expiresAtSeconds,
    )

    fun callback(
        request: AuthorizationRequest,
        code: String = "code-1",
        clientId: String? = ISSUED_CLIENT_ID,
        error: String? = null,
    ) = LoopbackCallbackResult(
        code = if (error == null) code else null,
        state = request.state,
        clientId = clientId,
        scope = null,
        error = error,
        errorDescription = null,
        rawQuery = emptyMap(),
    )
}

private fun assertAuthFailed(block: () -> Unit) {
    try {
        block()
    } catch (e: BrainError.AuthFailed) {
        return
    }
    fail("expected BrainError.AuthFailed")
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
// PKCE / state / nonce
// ----------------------------------------------------------------------

class SiwcPkceTest {

    @Test
    fun `RFC 7636 Appendix B test vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            SiwcPkce.challengeFor(verifier),
        )
    }

    @Test
    fun `generated verifier is 43 to 128 chars of unreserved alphabet`() {
        repeat(20) {
            val verifier = SiwcPkce.generateVerifier()
            assertTrue(verifier.length in 43..128)
            assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
            assertFalse(verifier.contains("="))
            assertFalse(verifier.contains("+"))
            assertFalse(verifier.contains("/"))
        }
    }

    @Test
    fun `challenge is base64url without padding and changes with the verifier`() {
        val challenge1 = SiwcPkce.challengeFor(SiwcPkce.generateVerifier())
        val challenge2 = SiwcPkce.challengeFor(SiwcPkce.generateVerifier())
        assertEquals(43, challenge1.length)
        assertFalse(challenge1.contains("="))
        assertNotEquals(challenge1, challenge2)
    }

    @Test
    fun `state and nonce are fresh random values`() {
        val state1 = SiwcPkce.generateState()
        val state2 = SiwcPkce.generateState()
        val nonce = SiwcPkce.generateNonce()
        assertNotEquals(state1, state2)
        assertEquals(43, state1.length)
        assertEquals(43, nonce.length)
    }
}

// ----------------------------------------------------------------------
// Authorization URL
// ----------------------------------------------------------------------

class SiwcAuthorizationUrlTest {

    private val fixture = Fixture()

    @After
    fun tearDown() {
        fixture.manager.cancelSignIn()
    }

    @Test
    fun `new registration URL carries dynamic client id and all required params`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        val url = request.authorizationUrl
        assertTrue(url.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        assertTrue(url.contains("client_id=dynamic_agent_client"))
        assertTrue(url.contains("agent_name_hint=WhereverCode"))
        assertTrue(url.contains("ext_agent_host_id=" + TEST_HOST_ID.replace(":", "%3A")))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A"))
        assertTrue(url.contains("%2Fauth%2Fcallback"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(
            url.contains("code_challenge=" + SiwcPkce.challengeFor(request.codeVerifier)),
        )
        assertTrue(url.contains("state=" + request.state))
        assertTrue(url.contains("nonce=" + request.nonce))
        assertTrue(url.contains("resource=https%3A%2F%2Fapi.openai.com%2Fv1"))
        // All six scopes (URL-encoded spaces are '+').
        for (scope in listOf(
            "openid", "profile", "email", "offline_access",
            "resource.invoke", "chatgpt.tokens.use.direct",
        )) {
            assertTrue("missing scope $scope in $url", url.contains(scope))
        }
        assertFalse(url.contains("id_token_hint"))
        assertFalse(url.contains("login_hint"))
        assertFalse(url.contains("localhost"))
        assertEquals(request.redirectUri, "http://127.0.0.1:${request.server.port}/auth/callback")
    }

    @Test
    fun `reauthorization reuses issued client id and omits agent name hint`() = runTest {
        fixture.seedTokens(idToken = "old-id-token")
        fixture.seedIdentity()
        val request = fixture.manager.beginSignIn(newProfile = false)
        val url = request.authorizationUrl
        assertTrue(url.contains("client_id=$ISSUED_CLIENT_ID"))
        assertFalse(url.contains("dynamic_agent_client"))
        assertFalse(url.contains("agent_name_hint"))
        assertTrue(url.contains("id_token_hint=old-id-token"))
        assertTrue(url.contains("login_hint=user%40example.com"))
        assertFalse(request.isNewRegistration)
    }

    @Test
    fun `second beginSignIn while one is pending is rejected`() = runTest {
        fixture.manager.beginSignIn(newProfile = true)
        assertAuthFailedSuspend { fixture.manager.beginSignIn(newProfile = true) }
        fixture.manager.cancelSignIn()
        // After cancel, a fresh attempt works again.
        val request = fixture.manager.beginSignIn(newProfile = true)
        assertTrue(request.isNewRegistration)
    }

    @Test
    fun `buildAuthorizationUrl is deterministic for fixed inputs`() {
        val url = fixture.manager.buildAuthorizationUrl(
            clientId = "dynamic_agent_client",
            agentNameHint = "WhereverCode",
            hostId = TEST_HOST_ID,
            redirectUri = "http://127.0.0.1:1455/auth/callback",
            state = "state-1",
            nonce = "nonce-1",
            codeChallenge = "challenge-1",
            idTokenHint = null,
            loginHint = null,
        )
        assertEquals(
            "https://auth.openai.com/api/accounts/authorize" +
                "?client_id=dynamic_agent_client" +
                "&agent_name_hint=WhereverCode" +
                "&ext_agent_host_id=urn%3Auuid%3A11111111-2222-4333-8444-555555555555" +
                "&response_type=code" +
                "&redirect_uri=http%3A%2F%2F127.0.0.1%3A1455%2Fauth%2Fcallback" +
                "&scope=openid+profile+email+offline_access+resource.invoke+chatgpt.tokens.use.direct" +
                "&resource=https%3A%2F%2Fapi.openai.com%2Fv1" +
                "&state=state-1&nonce=nonce-1" +
                "&code_challenge_method=S256&code_challenge=challenge-1",
            url,
        )
    }
}

// ----------------------------------------------------------------------
// Callback handling / code exchange / ID-token verification
// ----------------------------------------------------------------------

class SiwcCallbackTest {

    private val fixture = Fixture()

    @After
    fun tearDown() {
        fixture.manager.cancelSignIn()
    }

    @Test
    fun `wrong state is rejected and no token call happens`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        val bad = fixture.callback(request).copy(state = "wrong-state")
        assertAuthFailedSuspend { fixture.manager.handleCallback(bad) }
        assertTrue(fixture.fake.tokenPosts().isEmpty())
    }

    @Test
    fun `replayed callback is rejected after the first success`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        fixture.fake.tokenHandler = { _ ->
            val idToken = fixture.validIdToken(ISSUED_CLIENT_ID, request.nonce)
            SiwcHttpResponse(200, tokenResponseJson(idToken))
        }
        val result = fixture.manager.handleCallback(fixture.callback(request))
        assertTrue(result.sharingEnabled)
        // Same callback again: the pending attempt was consumed.
        assertAuthFailedSuspend { fixture.manager.handleCallback(fixture.callback(request)) }
    }

    @Test
    fun `access_denied error is AuthFailed and no token call happens`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        assertAuthFailedSuspend {
            fixture.manager.handleCallback(fixture.callback(request, error = "access_denied"))
        }
        assertTrue(fixture.fake.tokenPosts().isEmpty())
    }

    @Test
    fun `missing code is rejected`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        val noCode = fixture.callback(request).copy(code = null)
        assertAuthFailedSuspend { fixture.manager.handleCallback(noCode) }
    }

    @Test
    fun `new registration without issued client id is incomplete`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        val noClientId = fixture.callback(request).copy(clientId = null)
        assertAuthFailedSuspend { fixture.manager.handleCallback(noClientId) }
        assertTrue(fixture.fake.tokenPosts().isEmpty())
    }

    @Test
    fun `reauthorization with a different client id is rejected`() = runTest {
        fixture.seedTokens()
        val request = fixture.manager.beginSignIn(newProfile = false)
        val otherClient = fixture.callback(request).copy(clientId = "oaiapp_someone_else")
        assertAuthFailedSuspend { fixture.manager.handleCallback(otherClient) }
        assertTrue(fixture.fake.tokenPosts().isEmpty())
    }

    @Test
    fun `successful new registration saves tokens and issued client id`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        fixture.fake.tokenHandler = { params ->
            assertEquals("authorization_code", params["grant_type"])
            assertEquals(ISSUED_CLIENT_ID, params["client_id"])
            assertEquals("code-1", params["code"])
            assertEquals(request.codeVerifier, params["code_verifier"])
            assertEquals(request.redirectUri, params["redirect_uri"])
            assertEquals("https://api.openai.com/v1", params["resource"])
            val idToken = fixture.validIdToken(ISSUED_CLIENT_ID, request.nonce)
            SiwcHttpResponse(200, tokenResponseJson(idToken))
        }
        val result = fixture.manager.handleCallback(fixture.callback(request))
        assertTrue(result.sharingEnabled)
        assertEquals("sub-123", result.subject)
        assertEquals("user@example.com", result.email)
        assertTrue(result.grantedScopes.contains("chatgpt.tokens.use.direct"))
        val saved = fixture.tokenStore.loadTokens()!!
        assertEquals("access-1", saved.accessToken)
        assertEquals("refresh-1", saved.refreshToken)
        assertEquals(ISSUED_CLIENT_ID, saved.clientId)
        assertEquals(TEST_HOST_ID, saved.hostId)
        assertEquals(FIXED_NOW_MILLIS + 3_600_000L, saved.accessTokenExpiresAtMillis)
        // Issued client id persisted BEFORE the exchange, for later sign-ins.
        assertEquals(
            ISSUED_CLIENT_ID,
            fixture.secrets.get(SiwcAuthManager.KEY_PENDING_CLIENT_ID),
        )
        assertTrue(fixture.manager.isSignedIn())
        assertTrue(fixture.manager.hasSharingEnabled())
    }

    @Test
    fun `identity sign-in without plan scope still saves tokens but sharing is false`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        fixture.fake.tokenHandler = { _ ->
            val idToken = fixture.validIdToken(ISSUED_CLIENT_ID, request.nonce)
            SiwcHttpResponse(
                200,
                tokenResponseJson(idToken, scope = "openid profile email offline_access"),
            )
        }
        val result = fixture.manager.handleCallback(fixture.callback(request))
        assertFalse(result.sharingEnabled)
        assertFalse(result.grantedScopes.contains("chatgpt.tokens.use.direct"))
        assertTrue(fixture.manager.isSignedIn())
        assertFalse(fixture.manager.hasSharingEnabled())
    }

    @Test
    fun `ID token with wrong audience is rejected and nothing is saved`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        fixture.fake.tokenHandler = { _ ->
            val idToken = fixture.validIdToken("oaiapp_someone_else", request.nonce)
            SiwcHttpResponse(200, tokenResponseJson(idToken))
        }
        assertAuthFailedSuspend { fixture.manager.handleCallback(fixture.callback(request)) }
        assertNull(fixture.tokenStore.loadTokens())
    }

    @Test
    fun `ID token with wrong nonce is rejected`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        fixture.fake.tokenHandler = { _ ->
            val idToken = fixture.validIdToken(ISSUED_CLIENT_ID, "some-other-nonce")
            SiwcHttpResponse(200, tokenResponseJson(idToken))
        }
        assertAuthFailedSuspend { fixture.manager.handleCallback(fixture.callback(request)) }
        assertNull(fixture.tokenStore.loadTokens())
    }

    @Test
    fun `expired ID token is rejected`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        fixture.fake.tokenHandler = { _ ->
            val idToken = fixture.validIdToken(
                ISSUED_CLIENT_ID,
                request.nonce,
                expiresAtSeconds = FIXED_NOW_SECONDS - 10,
            )
            SiwcHttpResponse(200, tokenResponseJson(idToken))
        }
        assertAuthFailedSuspend { fixture.manager.handleCallback(fixture.callback(request)) }
    }

    @Test
    fun `ID token signed with another key is rejected`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        val otherKey = generateRsaKeyPair()
        fixture.fake.tokenHandler = { _ ->
            val idToken = fixture.validIdToken(
                ISSUED_CLIENT_ID,
                request.nonce,
                keyPair = otherKey,
            )
            SiwcHttpResponse(200, tokenResponseJson(idToken))
        }
        assertAuthFailedSuspend { fixture.manager.handleCallback(fixture.callback(request)) }
        assertNull(fixture.tokenStore.loadTokens())
    }

    @Test
    fun `ID token with wrong issuer is rejected`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        fixture.fake.tokenHandler = { _ ->
            val idToken = makeIdToken(
                keyPair = fixture.keyPair,
                kid = TEST_KID,
                issuer = "https://auth.evil.example",
                audience = ISSUED_CLIENT_ID,
                nonce = request.nonce,
                expiresAtSeconds = FIXED_NOW_SECONDS + 3600,
            )
            SiwcHttpResponse(200, tokenResponseJson(idToken))
        }
        assertAuthFailedSuspend { fixture.manager.handleCallback(fixture.callback(request)) }
    }

    @Test
    fun `token endpoint invalid_grant is AuthFailed and clears stored tokens`() = runTest {
        fixture.seedTokens()
        val request = fixture.manager.beginSignIn(newProfile = false)
        fixture.fake.tokenHandler = { _ ->
            SiwcHttpResponse(400, """{"error":"invalid_grant"}""")
        }
        assertAuthFailedSuspend { fixture.manager.handleCallback(fixture.callback(request)) }
        assertNull(fixture.tokenStore.loadTokens())
        assertFalse(fixture.manager.isSignedIn())
    }

    @Test
    fun `token endpoint 500 is Transport and stored tokens are kept`() = runTest {
        fixture.seedTokens()
        val request = fixture.manager.beginSignIn(newProfile = false)
        fixture.fake.tokenHandler = { _ -> SiwcHttpResponse(500, "boom") }
        assertTransportSuspend { fixture.manager.handleCallback(fixture.callback(request)) }
        assertEquals("old-access", fixture.tokenStore.loadTokens()!!.accessToken)
    }

    @Test
    fun `network failure on token exchange is Transport`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        fixture.fake.postThrows = IOException("no route")
        assertTransportSuspend { fixture.manager.handleCallback(fixture.callback(request)) }
    }

    @Test
    fun `JWKS fetch failure is Transport`() = runTest {
        val request = fixture.manager.beginSignIn(newProfile = true)
        fixture.fake.tokenHandler = { _ ->
            val idToken = fixture.validIdToken(ISSUED_CLIENT_ID, request.nonce)
            SiwcHttpResponse(200, tokenResponseJson(idToken))
        }
        fixture.fake.getThrows = IOException("no route")
        // Discovery itself fails first (fallback constants), then JWKS fails.
        assertTransportSuspend { fixture.manager.handleCallback(fixture.callback(request)) }
    }
}

// ----------------------------------------------------------------------
// Refresh
// ----------------------------------------------------------------------

class SiwcRefreshTest {

    private val fixture = Fixture()

    @After
    fun tearDown() {
        fixture.manager.cancelSignIn()
    }

    @Test
    fun `fresh access token returns stored tokens without a network call`() = runTest {
        fixture.seedTokens(expiresAtMillis = FIXED_NOW_MILLIS + 3_600_000L)
        val tokens = fixture.manager.refreshTokens()
        assertEquals("old-access", tokens.accessToken)
        assertTrue(fixture.fake.requests.isEmpty())
    }

    @Test
    fun `expired access token refreshes with the issued client id and rotates`() = runTest {
        fixture.seedTokens(expiresAtMillis = FIXED_NOW_MILLIS - 1_000L)
        fixture.fake.tokenHandler = { params ->
            assertEquals("refresh_token", params["grant_type"])
            assertEquals(ISSUED_CLIENT_ID, params["client_id"])
            assertEquals("old-refresh", params["refresh_token"])
            assertEquals("https://api.openai.com/v1", params["resource"])
            assertFalse("refresh must omit scope", params.containsKey("scope"))
            assertFalse("refresh must never send dynamic_agent_client",
                params["client_id"] == "dynamic_agent_client")
            SiwcHttpResponse(
                200,
                tokenResponseJson(
                    idToken = fixture.validIdToken(ISSUED_CLIENT_ID, null),
                    accessToken = "access-2",
                    refreshToken = "refresh-2",
                ),
            )
        }
        val refreshed = fixture.manager.refreshTokens()
        assertEquals("access-2", refreshed.accessToken)
        assertEquals("refresh-2", refreshed.refreshToken)
        val saved = fixture.tokenStore.loadTokens()!!
        assertEquals("access-2", saved.accessToken)
        assertEquals("refresh-2", saved.refreshToken)
        assertEquals(ISSUED_CLIENT_ID, saved.clientId)
        assertEquals(FIXED_NOW_MILLIS + 3_600_000L, saved.accessTokenExpiresAtMillis)
    }

    @Test
    fun `refresh response without a new refresh token keeps the old one`() = runTest {
        fixture.seedTokens(expiresAtMillis = FIXED_NOW_MILLIS - 1_000L)
        fixture.fake.tokenHandler = { _ ->
            SiwcHttpResponse(
                200,
                tokenResponseJson(
                    idToken = fixture.validIdToken(ISSUED_CLIENT_ID, null),
                    accessToken = "access-2",
                    refreshToken = null,
                ),
            )
        }
        val refreshed = fixture.manager.refreshTokens()
        assertEquals("old-refresh", refreshed.refreshToken)
    }

    @Test
    fun `refresh invalid_grant is AuthFailed and clears stored tokens`() = runTest {
        fixture.seedTokens(expiresAtMillis = FIXED_NOW_MILLIS - 1_000L)
        fixture.fake.tokenHandler = { _ ->
            SiwcHttpResponse(400, """{"error":"invalid_grant"}""")
        }
        assertAuthFailedSuspend { fixture.manager.refreshTokens() }
        assertNull(fixture.tokenStore.loadTokens())
    }

    @Test
    fun `refresh network failure is Transport and stored tokens are kept`() = runTest {
        fixture.seedTokens(expiresAtMillis = FIXED_NOW_MILLIS - 1_000L)
        fixture.fake.postThrows = IOException("no route")
        assertTransportSuspend { fixture.manager.refreshTokens() }
        assertEquals("old-access", fixture.tokenStore.loadTokens()!!.accessToken)
    }

    @Test
    fun `refresh with nothing stored is NotConfigured`() = runTest {
        try {
            fixture.manager.refreshTokens()
            fail("expected NotConfigured")
        } catch (e: BrainError.NotConfigured) {
            // Expected.
        }
    }

    @Test
    fun `expired access token without refresh token is AuthFailed`() = runTest {
        fixture.seedTokens(
            refreshToken = null,
            expiresAtMillis = FIXED_NOW_MILLIS - 1_000L,
        )
        assertAuthFailedSuspend { fixture.manager.refreshTokens() }
    }
}

// ----------------------------------------------------------------------
// Revoke / sign out / signed-in state
// ----------------------------------------------------------------------

class SiwcRevokeTest {

    private val fixture = Fixture()

    @After
    fun tearDown() {
        fixture.manager.cancelSignIn()
    }

    @Test
    fun `revoke success clears local tokens but keeps client registration`() = runTest {
        fixture.seedTokens(idToken = "old-id-token")
        fixture.seedIdentity()
        fixture.secrets.put(SiwcAuthManager.KEY_PENDING_CLIENT_ID, ISSUED_CLIENT_ID)
        fixture.secrets.put(SiwcAuthManager.KEY_GRANTED_SCOPES, "openid chatgpt.tokens.use.direct")
        val result = fixture.manager.revoke()
        assertTrue(result.remoteRevoked)
        assertEquals(1, result.attempts)
        val revokeCall = fixture.fake.revokePosts().single()
        assertEquals(ISSUED_CLIENT_ID, revokeCall.params["client_id"])
        assertEquals("old-refresh", revokeCall.params["token"])
        assertEquals("refresh_token", revokeCall.params["token_type_hint"])
        assertNull(fixture.tokenStore.loadTokens())
        assertFalse(fixture.manager.isSignedIn())
        // Registration + host id are retained for a later sign-in.
        assertEquals(
            ISSUED_CLIENT_ID,
            fixture.secrets.get(SiwcAuthManager.KEY_PENDING_CLIENT_ID),
        )
    }

    @Test
    fun `revoke retries after 500 then succeeds`() = runTest {
        fixture.seedTokens()
        var calls = 0
        fixture.fake.revokeHandler = { _ ->
            calls++
            if (calls == 1) SiwcHttpResponse(500, "boom") else SiwcHttpResponse(200, "")
        }
        val result = fixture.manager.revoke()
        assertTrue(result.remoteRevoked)
        assertEquals(2, result.attempts)
        assertNull(fixture.tokenStore.loadTokens())
    }

    @Test
    fun `revoke gives up after max attempts but still clears local tokens`() = runTest {
        fixture.seedTokens()
        fixture.fake.revokeHandler = { _ -> SiwcHttpResponse(500, "boom") }
        val result = fixture.manager.revoke()
        assertFalse(result.remoteRevoked)
        assertEquals(SiwcAuthManager.MAX_REVOKE_ATTEMPTS, result.attempts)
        assertNull(fixture.tokenStore.loadTokens())
    }

    @Test
    fun `revoke with nothing stored does nothing`() = runTest {
        val result = fixture.manager.revoke()
        assertFalse(result.remoteRevoked)
        assertEquals(0, result.attempts)
        assertTrue(fixture.fake.requests.isEmpty())
    }

    @Test
    fun `signOut clears local tokens without any network call`() = runTest {
        fixture.seedTokens()
        fixture.manager.signOut()
        assertNull(fixture.tokenStore.loadTokens())
        assertFalse(fixture.manager.isSignedIn())
        assertTrue(fixture.fake.requests.isEmpty())
    }

    @Test
    fun `isSignedIn reflects stored tokens and expiry`() {
        assertFalse(fixture.manager.isSignedIn())
        fixture.seedTokens(expiresAtMillis = FIXED_NOW_MILLIS + 3_600_000L)
        assertTrue(fixture.manager.isSignedIn())
        // Expired access token but a refresh token: still signed in.
        fixture.seedTokens(expiresAtMillis = FIXED_NOW_MILLIS - 1_000L)
        assertTrue(fixture.manager.isSignedIn())
        // Expired access token and no refresh token: signed out.
        fixture.seedTokens(
            refreshToken = null,
            expiresAtMillis = FIXED_NOW_MILLIS - 1_000L,
        )
        assertFalse(fixture.manager.isSignedIn())
    }
}

// ----------------------------------------------------------------------
// Loopback callback server (real local sockets, no external network)
// ----------------------------------------------------------------------

class LoopbackCallbackServerTest {

    private fun get(port: Int, path: String): HttpURLConnection {
        val connection = URL("http://127.0.0.1:$port$path").openConnection()
            as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return connection
    }

    @Test
    fun `start binds 127_0_0_1 and redirectUri has the exact callback path`() {
        val server = LoopbackCallbackServer(0)
        try {
            val port = server.start()
            assertTrue(port > 0)
            assertTrue(server.isRunning)
            assertEquals("http://127.0.0.1:$port/auth/callback", server.redirectUri)
        } finally {
            server.stop()
        }
        assertFalse(server.isRunning)
    }

    @Test
    fun `callback parses code state clientId and scope then stops`() {
        val server = LoopbackCallbackServer(0)
        val port = server.start()
        var result: LoopbackCallbackResult? = null
        var failure: Throwable? = null
        val thread = Thread {
            try {
                result = server.awaitCallback(5_000)
            } catch (e: Throwable) {
                failure = e
            }
        }
        thread.start()
        val connection = get(port, "/auth/callback?code=abc123&state=s1&client_id=oaiapp_x&scope=a+b")
        assertEquals(200, connection.responseCode)
        assertTrue(connection.inputStream.bufferedReader().readText().contains("登录完成"))
        thread.join(5_000)
        assertNull(failure)
        val parsed = result!!
        assertEquals("abc123", parsed.code)
        assertEquals("s1", parsed.state)
        assertEquals("oaiapp_x", parsed.clientId)
        assertEquals("a b", parsed.scope)
        assertFalse(server.isRunning)
        // The server is closed: a second connection is refused.
        try {
            get(port, "/auth/callback?code=x").responseCode
            fail("expected the second connection to be refused")
        } catch (_: IOException) {
            // Expected.
        }
    }

    @Test
    fun `error callback parses the error param`() {
        val server = LoopbackCallbackServer(0)
        val port = server.start()
        var result: LoopbackCallbackResult? = null
        val thread = Thread { result = server.awaitCallback(5_000) }
        thread.start()
        val connection = get(port, "/auth/callback?error=access_denied&state=s9")
        assertEquals(200, connection.responseCode)
        connection.inputStream.bufferedReader().readText()
        thread.join(5_000)
        assertEquals("access_denied", result!!.error)
        assertEquals("s9", result!!.state)
        assertNull(result!!.code)
    }

    @Test
    fun `wrong path gets 404 and awaitCallback throws Transport`() {
        val server = LoopbackCallbackServer(0)
        val port = server.start()
        var failure: Throwable? = null
        val thread = Thread {
            try {
                server.awaitCallback(5_000)
            } catch (e: Throwable) {
                failure = e
            }
        }
        thread.start()
        val connection = get(port, "/other?code=x")
        assertEquals(404, connection.responseCode)
        thread.join(5_000)
        assertTrue(failure is BrainError.Transport)
        assertFalse(server.isRunning)
    }

    @Test
    fun `awaitCallback times out with Transport`() {
        val server = LoopbackCallbackServer(0)
        server.start()
        try {
            server.awaitCallback(200)
            fail("expected Transport on timeout")
        } catch (e: BrainError.Transport) {
            assertTrue(e.message!!.contains("超时"))
        } finally {
            server.stop()
        }
    }
}

// ----------------------------------------------------------------------
// Token store (in-memory secrets: pure JVM; Robolectric: real BrainKeyStore)
// ----------------------------------------------------------------------

class KeystoreSiwcTokenStoreMemoryTest {

    private fun tokens() = SiwcTokens(
        accessToken = "access-1",
        refreshToken = "refresh-1",
        idToken = "id-1",
        clientId = ISSUED_CLIENT_ID,
        hostId = TEST_HOST_ID,
        accessTokenExpiresAtMillis = FIXED_NOW_MILLIS + 3_600_000L,
        accountLabel = "user@example.com",
    )

    @Test
    fun `save and load round-trips all fields`() {
        val store = KeystoreSiwcTokenStore(InMemorySecretStore())
        assertNull(store.loadTokens())
        store.saveTokens(tokens())
        assertEquals(tokens(), store.loadTokens())
    }

    @Test
    fun `nullable fields round-trip as null`() {
        val store = KeystoreSiwcTokenStore(InMemorySecretStore())
        store.saveTokens(tokens().copy(refreshToken = null, idToken = null, accountLabel = null))
        val loaded = store.loadTokens()!!
        assertNull(loaded.refreshToken)
        assertNull(loaded.idToken)
        assertNull(loaded.accountLabel)
    }

    @Test
    fun `clearTokens removes the record`() {
        val store = KeystoreSiwcTokenStore(InMemorySecretStore())
        store.saveTokens(tokens())
        store.clearTokens()
        assertNull(store.loadTokens())
    }

    @Test
    fun `corrupt record loads as null`() {
        val secrets = InMemorySecretStore()
        secrets.put(KeystoreSiwcTokenStore.AUTH_REF, "{not json")
        val store = KeystoreSiwcTokenStore(secrets)
        assertNull(store.loadTokens())
    }

    @Test
    fun `record missing required fields loads as null`() {
        val secrets = InMemorySecretStore()
        secrets.put(
            KeystoreSiwcTokenStore.AUTH_REF,
            JSONObject().put("access_token", "x").toString(),
        )
        val store = KeystoreSiwcTokenStore(secrets)
        assertNull(store.loadTokens())
    }

    @Test
    fun `save overwrites the previous record`() {
        val store = KeystoreSiwcTokenStore(InMemorySecretStore())
        store.saveTokens(tokens())
        store.saveTokens(tokens().copy(accessToken = "access-2"))
        assertEquals("access-2", store.loadTokens()!!.accessToken)
    }
}

@RunWith(RobolectricTestRunner::class)
class KeystoreSiwcTokenStoreTest {

    @Test
    fun `round-trip through the real BrainKeyStore`() {
        // Robolectric does not implement AndroidKeyStore; this test only has
        // meaning on a real Android runtime (or a JVM with a provider
        // installed). Skip honestly instead of failing the whole suite for
        // an environment capability that is absent.
        assumeTrue(
            "AndroidKeyStore is not available in this JVM/Robolectric environment",
            androidKeyStoreAvailable(),
        )
        val context = RuntimeEnvironment.getApplication()
        val store = KeystoreSiwcTokenStore.create(context)
        val tokens = SiwcTokens(
            accessToken = "access-1",
            refreshToken = "refresh-1",
            idToken = "id-1",
            clientId = ISSUED_CLIENT_ID,
            hostId = TEST_HOST_ID,
            accessTokenExpiresAtMillis = FIXED_NOW_MILLIS + 3_600_000L,
        )
        try {
            assertNull(store.loadTokens())
            store.saveTokens(tokens)
            assertEquals(tokens, store.loadTokens())
            store.clearTokens()
            assertNull(store.loadTokens())
        } finally {
            // Never leave test tokens in the store, even on failure.
            store.clearTokens()
        }
    }

    @Test
    fun `host id store generates a stable urn uuid id`() {
        val context = RuntimeEnvironment.getApplication()
        val store = SharedPreferencesHostIdStore(context)
        val first = store.getOrCreateHostId()
        assertTrue(first.startsWith("urn:uuid:"))
        assertEquals(first, store.getOrCreateHostId())
    }

    private fun androidKeyStoreAvailable(): Boolean = try {
        KeyStore.getInstance("AndroidKeyStore").load(null)
        true
    } catch (_: Throwable) {
        false
    }
}
