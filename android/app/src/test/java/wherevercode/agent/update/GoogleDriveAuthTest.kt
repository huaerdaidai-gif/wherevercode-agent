package wherevercode.agent.update

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import wherevercode.agent.brain.oauth.OAuthEngine
import wherevercode.agent.brain.oauth.OAuthHttpResponse
import wherevercode.agent.brain.oauth.OAuthHttpTransport
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * GoogleDriveAuth 测试：fake OAuth 传输 + 真 RS256 ID token（现签现验，
 * 一次性测试密钥对）+ 真回环回调服务器（127.0.0.1）。不碰真 Google。
 *
 * 约定（同 SIWC 测试）：backtick 测试名，一个行为一个测试，fake 只放在
 * 传输 / 存储端口。
 */
private const val FIXED_NOW_MILLIS = 1_792_828_800_000L
private const val FIXED_NOW_SECONDS = 1_792_828_800L
private const val TEST_KID = "test-kid-1"
private const val TEST_CLIENT_ID = "123456789012-abcdefg.apps.googleusercontent.com"
private const val TEST_CLIENT_SECRET = "fixture-client-secret"
private const val TEST_EMAIL = "test-user@example.invalid"

private val DISCOVERY_JSON = JSONObject().apply {
    put("issuer", "https://accounts.google.com")
    put("authorization_endpoint", "https://accounts.google.com/o/oauth2/v2/auth")
    put("token_endpoint", "https://oauth2.googleapis.com/token")
    put("revocation_endpoint", "https://oauth2.googleapis.com/revoke")
    put("jwks_uri", "https://www.googleapis.com/oauth2/v3/certs")
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

private fun makeIdToken(
    keyPair: KeyPair,
    kid: String,
    issuer: String = "https://accounts.google.com",
    audience: String,
    subject: String = "sub-123",
    email: String? = TEST_EMAIL,
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

private class MemoryTokenStore : GoogleDriveTokenStore {
    var tokens: GoogleDriveTokens? = null
    override fun saveTokens(tokens: GoogleDriveTokens) {
        this.tokens = tokens
    }

    override fun loadTokens(): GoogleDriveTokens? = tokens
    override fun clearTokens() {
        tokens = null
    }
}

/** 内存客户端密钥存储（测试用；存什么值测什么值）。 */
private class MemoryClientSecretStore(
    var secret: String? = TEST_CLIENT_SECRET,
) : GoogleDriveClientSecretStore {
    override fun saveSecret(secret: String) {
        this.secret = secret
    }

    override fun loadSecret(): String? = secret
    override fun clearSecret() {
        secret = null
    }
}

/** 按 URL 分流的 fake Google OAuth 传输（open：完整登录测试在子类里重签 ID token）。 */
private open class FakeGoogleTransport(
    private val keyPair: KeyPair,
) : OAuthHttpTransport {
    var tokenGrant: String? = null
    var refreshSucceeds: Boolean = true

    /** 最近一次 /token POST 的参数（换码 / 刷新构造断言用）。 */
    var lastTokenParams: Map<String, String>? = null

    /** /token POST 的次数（未配密钥时必须为 0：请求根本不该发出去）。 */
    var tokenPostCount: Int = 0

    override open fun postForm(url: String, params: Map<String, String>): OAuthHttpResponse {
        tokenGrant = params["grant_type"]
        if (url.contains("/token")) {
            tokenPostCount++
            lastTokenParams = params.toMap()
            if (params["grant_type"] == "refresh_token" && !refreshSucceeds) {
                return OAuthHttpResponse(400, """{"error":"invalid_grant"}""")
            }
            val json = JSONObject()
            val isRefresh = params["grant_type"] == "refresh_token"
            json.put("access_token", if (isRefresh) "access-2" else "access-1")
            // refresh 响应不带新 refresh token：调用方应沿用旧的。
            if (!isRefresh) json.put("refresh_token", "refresh-1")
            json.put("expires_in", 3600)
            json.put("scope", "openid email https://www.googleapis.com/auth/drive.readonly")
            // 基础 fake 不发 ID token（完整登录测试在子类里按本次 nonce 重签）。
            return OAuthHttpResponse(200, json.toString())
        }
        if (url.contains("/revoke")) {
            return OAuthHttpResponse(200, "")
        }
        return OAuthHttpResponse(404, "{}")
    }

    override fun get(url: String): OAuthHttpResponse {
        return when {
            url.contains("openid-configuration") -> OAuthHttpResponse(200, DISCOVERY_JSON)
            url.contains("oauth2/v3/certs") -> OAuthHttpResponse(200, jwksJson(keyPair, TEST_KID))
            else -> OAuthHttpResponse(404, "{}")
        }
    }
}

/** 不碰 socket 的回环回调替身：beginSignIn 拿它当服务器，测试在调 completeSignIn 之前把要回的回调填进去。真实 socket 回环在某些沙箱里不通，换码 / 验签的错误映射用替身测；真服务器路径由现有测试在 CI 里覆盖。 */
private class FakeCallbackServer : wherevercode.agent.brain.siwc.LoopbackCallbackServer(0) {
    var result: wherevercode.agent.brain.siwc.LoopbackCallbackResult? = null
    var stopped: Boolean = false

    override fun start(): Int = 40979

    override fun awaitCallback(timeoutMs: Long): wherevercode.agent.brain.siwc.LoopbackCallbackResult {
        return result ?: throw IllegalStateException("fake callback result not set")
    }

    override fun stop() {
        stopped = true
    }
}

class GoogleDriveAuthTest {

    private fun newAuth(
        transport: FakeGoogleTransport,
        store: MemoryTokenStore,
        logLines: MutableList<String> = mutableListOf(),
        secretStore: MemoryClientSecretStore = MemoryClientSecretStore(),
        serverFactory: (Int) -> wherevercode.agent.brain.siwc.LoopbackCallbackServer =
            { port -> wherevercode.agent.brain.siwc.LoopbackCallbackServer(port) },
    ): GoogleDriveAuth {
        val engine = OAuthEngine(
            GOOGLE_DRIVE_OAUTH_CONFIG,
            transport,
            clockMillis = { FIXED_NOW_MILLIS },
        )
        return GoogleDriveAuth(
            engine,
            store,
            secretStore,
            serverFactory,
            clockMillis = { FIXED_NOW_MILLIS },
            logError = { logLines.add(it) },
        )
    }

    @Test
    fun `beginSignIn builds a Google auth url with offline access and PKCE`() = runTest {
        val keyPair = generateRsaKeyPair()
        val transport = FakeGoogleTransport(keyPair)
        val auth = newAuth(transport, MemoryTokenStore())
        val request = auth.beginSignIn(TEST_CLIENT_ID)
        try {
            val url = request.authorizationUrl
            assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
            assertTrue(url.contains("client_id=$TEST_CLIENT_ID"))
            assertTrue(url.contains("drive.readonly"))
            assertTrue(url.contains("access_type=offline"))
            assertTrue(url.contains("prompt=consent"))
            assertTrue(url.contains("code_challenge_method=S256"))
            assertTrue(url.contains("response_type=code"))
            assertTrue(request.redirectUri.startsWith("http://127.0.0.1:"))
            assertTrue(request.redirectUri.endsWith("/auth/callback"))
            assertTrue(request.state.isNotBlank())
            assertTrue(request.codeVerifier.isNotBlank())
        } finally {
            request.server.stop()
        }
    }

    @Test
    fun `beginSignIn without a client id fails`() = runTest {
        val keyPair = generateRsaKeyPair()
        val auth = newAuth(FakeGoogleTransport(keyPair), MemoryTokenStore())
        try {
            auth.beginSignIn("")
            fail("expected failure for blank client id")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    /**
     * 完整登录：fake 传输返回按本次 nonce 签的真 ID token；用真 HTTP GET
     * 模拟浏览器回环回调。
     */
    @Test
    fun `complete sign-in verifies the id token and saves tokens`() = runTest {
        val keyPair = generateRsaKeyPair()
        val transport = object : FakeGoogleTransport(keyPair) {
            override fun postForm(url: String, params: Map<String, String>): OAuthHttpResponse {
                if (url.contains("/token") && params["grant_type"] == "authorization_code") {
                    // 注意：nonce 在 beginSignIn 时才生成，这里用测试在回调前
                    // 注入的 nonce——见下面的 idTokenForNonce。
                    val json = JSONObject()
                    json.put("access_token", "access-1")
                    json.put("refresh_token", "refresh-1")
                    json.put("expires_in", 3600)
                    json.put("scope", "openid email https://www.googleapis.com/auth/drive.readonly")
                    json.put("id_token", idTokenForNonce)
                    return OAuthHttpResponse(200, json.toString())
                }
                return super.postForm(url, params)
            }

            var idTokenForNonce: String = ""
        }
        val store = MemoryTokenStore()
        val auth = newAuth(transport, store)
        val request = auth.beginSignIn(TEST_CLIENT_ID)
        transport.idTokenForNonce = makeIdToken(
            keyPair = keyPair,
            kid = TEST_KID,
            audience = TEST_CLIENT_ID,
            nonce = request.nonce,
            expiresAtSeconds = FIXED_NOW_SECONDS + 3600,
        )
        val callbackThread = Thread {
            val url = URL("${request.redirectUri}?code=test-code-1&state=${request.state}")
            (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                inputStream.use { it.readBytes() }
            }
        }
        callbackThread.start()
        val tokens = auth.completeSignIn(request)
        callbackThread.join(5000)
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
        assertEquals(TEST_CLIENT_ID, tokens.clientId)
        assertEquals(TEST_EMAIL, tokens.accountLabel)
        assertEquals(FIXED_NOW_MILLIS + 3_600_000L, tokens.accessTokenExpiresAtMillis)
        assertEquals(tokens, store.loadTokens())
    }

    @Test
    fun `callback with a wrong state is rejected`() = runTest {
        val keyPair = generateRsaKeyPair()
        val transport = FakeGoogleTransport(keyPair)
        val store = MemoryTokenStore()
        val auth = newAuth(transport, store)
        val request = auth.beginSignIn(TEST_CLIENT_ID)
        val callbackThread = Thread {
            val url = URL("${request.redirectUri}?code=test-code-1&state=wrong-state")
            (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                inputStream.use { it.readBytes() }
            }
        }
        callbackThread.start()
        try {
            auth.completeSignIn(request)
            fail("expected AuthFailed for state mismatch")
        } catch (e: AppUpdateError.AuthFailed) {
            assertTrue(e.message!!.contains("state"))
        } finally {
            callbackThread.join(5000)
        }
        assertNull(store.loadTokens())
    }

    /**
     * 回归（2026-11-11 真机）：token 端点回 invalid_grant 时，状态行
     * 必须报具体错误码和失败步骤，不再只显示“授权已失效”；同一份
     * 诊断写进日志；不存 token。用替身回调服务器（不碰 socket）。
     */
    @Test
    fun `token endpoint invalid_grant surfaces the error code and step`() = runTest {
        val keyPair = generateRsaKeyPair()
        val store = MemoryTokenStore()
        val logLines = mutableListOf<String>()
        val server = FakeCallbackServer()
        val auth = newAuth(failingCodeExchangeTransport(keyPair, "invalid_grant"), store, logLines) { server }
        val request = auth.beginSignIn(TEST_CLIENT_ID)
        server.result = codeCallback(request.state)
        try {
            auth.completeSignIn(request)
            fail("expected AuthFailed for invalid_grant")
        } catch (e: AppUpdateError.AuthFailed) {
            assertTrue(e.message!!.contains("invalid_grant"))
            assertTrue(e.message!!.contains("换取令牌"))
            assertTrue(e.message!!.contains("重新连接"))
        }
        assertTrue(logLines.any { it.contains("invalid_grant") && it.contains("换取令牌") })
        assertNull(store.loadTokens())
    }

    /**
     * 回归：token 端点回 invalid_client 时，状态行同样要报具体错误码，
     * 而不是通用文案。注意：2026-11-11 真机报的其实是 invalid_request
     * （缺 client_secret），不是 invalid_client——本测试只覆盖
     * invalid_client 这一码的现形，不再把它当成真机根因。
     */
    @Test
    fun `token endpoint invalid_client surfaces the error code and step`() = runTest {
        val keyPair = generateRsaKeyPair()
        val store = MemoryTokenStore()
        val logLines = mutableListOf<String>()
        val server = FakeCallbackServer()
        val auth = newAuth(failingCodeExchangeTransport(keyPair, "invalid_client"), store, logLines) { server }
        val request = auth.beginSignIn(TEST_CLIENT_ID)
        server.result = codeCallback(request.state)
        try {
            auth.completeSignIn(request)
            fail("expected AuthFailed for invalid_client")
        } catch (e: AppUpdateError.AuthFailed) {
            assertTrue(e.message!!.contains("invalid_client"))
            assertTrue(e.message!!.contains("换取令牌"))
            assertTrue(e.message!!.contains("重新连接"))
        }
        assertTrue(logLines.any { it.contains("invalid_client") && it.contains("换取令牌") })
        assertNull(store.loadTokens())
    }

    /**
     * 根因回归（2026-11-11 真机 invalid_request）：Google 的桌面应用
     * 客户端在令牌端点强制要求 client_secret（即使走了 PKCE），不带
     * 密钥换码必报 `invalid_request: client_secret is missing.`。
     * 换码请求必须带上密钥，且 grant_type / code / redirect_uri /
     * client_id / code_verifier 一个不少——请求构造本身要能被测出来。
     */
    @Test
    fun `code exchange sends client_secret and the full parameter set`() = runTest {
        val keyPair = generateRsaKeyPair()
        val transport = FakeGoogleTransport(keyPair)
        val store = MemoryTokenStore()
        val server = FakeCallbackServer()
        val auth = newAuth(transport, store, secretStore = MemoryClientSecretStore()) { server }
        val request = auth.beginSignIn(TEST_CLIENT_ID)
        server.result = codeCallback(request.state)
        val tokens = auth.completeSignIn(request)
        assertEquals("access-1", tokens.accessToken)
        val params = transport.lastTokenParams!!
        assertEquals("authorization_code", params["grant_type"])
        assertEquals("auth-code-1", params["code"])
        assertEquals(request.redirectUri, params["redirect_uri"])
        assertEquals(TEST_CLIENT_ID, params["client_id"])
        assertEquals(request.codeVerifier, params["code_verifier"])
        assertEquals(TEST_CLIENT_SECRET, params["client_secret"])
    }

    /**
     * 根因回归：未配客户端密钥时，换码请求根本不该发出去（发了也是
     * 同一个 invalid_request，还白白消耗一次 code）——直接报明去
     * 设置里填密钥；token 端点零请求；不存 token。
     */
    @Test
    fun `code exchange without a stored secret fails before any network call`() = runTest {
        val keyPair = generateRsaKeyPair()
        val transport = FakeGoogleTransport(keyPair)
        val store = MemoryTokenStore()
        val logLines = mutableListOf<String>()
        val server = FakeCallbackServer()
        val auth = newAuth(
            transport,
            store,
            logLines,
            secretStore = MemoryClientSecretStore(secret = null),
        ) { server }
        val request = auth.beginSignIn(TEST_CLIENT_ID)
        server.result = codeCallback(request.state)
        try {
            auth.completeSignIn(request)
            fail("expected AuthFailed for missing client secret")
        } catch (e: AppUpdateError.AuthFailed) {
            assertTrue(e.message!!.contains("客户端密钥"))
            assertTrue(e.message!!.contains("换取令牌"))
        }
        assertEquals(0, transport.tokenPostCount)
        assertNull(transport.lastTokenParams)
        assertTrue(logLines.any { it.contains("客户端密钥未配") })
        assertNull(store.loadTokens())
    }

    /**
     * 根因回归：刷新令牌同样强制要求 client_secret（实测：不带密钥
     * 刷新也报 invalid_request: client_secret is missing.），刷新
     * 请求必须带上密钥。
     */
    @Test
    fun `refresh sends client_secret`() = runTest {
        val keyPair = generateRsaKeyPair()
        val transport = FakeGoogleTransport(keyPair)
        val store = MemoryTokenStore()
        store.saveTokens(
            GoogleDriveTokens(
                accessToken = "access-old",
                refreshToken = "refresh-1",
                idToken = null,
                clientId = TEST_CLIENT_ID,
                accessTokenExpiresAtMillis = FIXED_NOW_MILLIS - 1000L,
                accountLabel = TEST_EMAIL,
            ),
        )
        val auth = newAuth(transport, store, secretStore = MemoryClientSecretStore())
        assertEquals("access-2", auth.ensureAccessToken(TEST_CLIENT_ID))
        val params = transport.lastTokenParams!!
        assertEquals("refresh_token", params["grant_type"])
        assertEquals("refresh-1", params["refresh_token"])
        assertEquals(TEST_CLIENT_ID, params["client_id"])
        assertEquals(TEST_CLIENT_SECRET, params["client_secret"])
    }

    /**
     * 诊断回归：token 端点回 invalid_request 时，状态行必须同时报
     * 错误码和 Google 的 error_description（"client_secret is
     * missing." 才是真正可操作的诊断，只看 invalid_request 猜不出
     * 缺了什么）；日志里同样有；不存 token。
     */
    @Test
    fun `token endpoint invalid_request surfaces the error description`() = runTest {
        val keyPair = generateRsaKeyPair()
        val store = MemoryTokenStore()
        val logLines = mutableListOf<String>()
        val server = FakeCallbackServer()
        val transport = object : FakeGoogleTransport(keyPair) {
            override fun postForm(url: String, params: Map<String, String>): OAuthHttpResponse {
                if (params["grant_type"] == "authorization_code") {
                    return OAuthHttpResponse(
                        400,
                        """{"error":"invalid_request","error_description":"client_secret is missing."}""",
                    )
                }
                return super.postForm(url, params)
            }
        }
        val auth = newAuth(transport, store, logLines) { server }
        val request = auth.beginSignIn(TEST_CLIENT_ID)
        server.result = codeCallback(request.state)
        try {
            auth.completeSignIn(request)
            fail("expected AuthFailed for invalid_request")
        } catch (e: AppUpdateError.AuthFailed) {
            assertTrue(e.message!!.contains("invalid_request"))
            assertTrue(e.message!!.contains("client_secret is missing."))
            assertTrue(e.message!!.contains("换取令牌"))
        }
        assertTrue(
            logLines.any {
                it.contains("invalid_request") && it.contains("client_secret is missing.")
            },
        )
        // 失败时日志里要有请求参数名清单（只记名字，不记任何密钥值）。
        assertTrue(
            logLines.any {
                it.contains("grant_type") && it.contains("code_verifier") &&
                    it.contains("client_secret") && !it.contains(TEST_CLIENT_SECRET)
            },
        )
        assertNull(store.loadTokens())
    }

    /**
     * 回归：回调带 error（用户在 Google 侧拒绝、或应用未过验证被
     * access_denied）时，状态行必须显示拒绝原因（access_denied），
     * 日志同样记录；不存 token。
     */
    @Test
    fun `callback carrying an error surfaces the refusal reason`() = runTest {
        val keyPair = generateRsaKeyPair()
        val store = MemoryTokenStore()
        val logLines = mutableListOf<String>()
        val server = FakeCallbackServer()
        val auth = newAuth(FakeGoogleTransport(keyPair), store, logLines) { server }
        val request = auth.beginSignIn(TEST_CLIENT_ID)
        server.result = wherevercode.agent.brain.siwc.LoopbackCallbackResult(
            code = null,
            state = request.state,
            clientId = null,
            scope = null,
            error = "access_denied",
            errorDescription = "The user denied your request.",
            rawQuery = emptyMap(),
        )
        try {
            auth.completeSignIn(request)
            fail("expected AuthFailed for callback error")
        } catch (e: AppUpdateError.AuthFailed) {
            assertTrue(e.message!!.contains("access_denied"))
        }
        assertTrue(logLines.any { it.contains("access_denied") })
        assertNull(store.loadTokens())
    }

    /**
     * 回归：ID token 签名验不过时，状态行必须报“验证 ID token”步骤
     * 和 id_token_bad_signature 码；同一份诊断写进日志；不存 token。
     */
    @Test
    fun `bad-signature id token surfaces the verify step and code`() = runTest {
        val keyPair = generateRsaKeyPair()
        val wrongKeyPair = generateRsaKeyPair()
        val transport = object : FakeGoogleTransport(keyPair) {
            override fun postForm(url: String, params: Map<String, String>): OAuthHttpResponse {
                if (url.contains("/token") && params["grant_type"] == "authorization_code") {
                    // ID token 由另一把密钥签：JWKS 里是 keyPair 的公钥，验签必败。
                    val json = JSONObject()
                    json.put("access_token", "access-1")
                    json.put("refresh_token", "refresh-1")
                    json.put("expires_in", 3600)
                    json.put("id_token", idTokenForNonce)
                    return OAuthHttpResponse(200, json.toString())
                }
                return super.postForm(url, params)
            }

            var idTokenForNonce: String = ""
        }
        val store = MemoryTokenStore()
        val logLines = mutableListOf<String>()
        val server = FakeCallbackServer()
        val auth = newAuth(transport, store, logLines) { server }
        val request = auth.beginSignIn(TEST_CLIENT_ID)
        transport.idTokenForNonce = makeIdToken(
            keyPair = wrongKeyPair,
            kid = TEST_KID,
            audience = TEST_CLIENT_ID,
            nonce = request.nonce,
            expiresAtSeconds = FIXED_NOW_SECONDS + 3600,
        )
        server.result = codeCallback(request.state)
        try {
            auth.completeSignIn(request)
            fail("expected AuthFailed for bad id token signature")
        } catch (e: AppUpdateError.AuthFailed) {
            assertTrue(e.message!!.contains("id_token_bad_signature"))
            assertTrue(e.message!!.contains("验证 ID token"))
            assertTrue(e.message!!.contains("重新连接"))
        }
        assertTrue(logLines.any { it.contains("id_token_bad_signature") && it.contains("验证 ID token") })
        assertNull(store.loadTokens())
    }

    /** token 端点对换码请求回指定 4xx 错误的传输。 */
    private fun failingCodeExchangeTransport(keyPair: KeyPair, error: String): FakeGoogleTransport {
        return object : FakeGoogleTransport(keyPair) {
            override fun postForm(url: String, params: Map<String, String>): OAuthHttpResponse {
                if (params["grant_type"] == "authorization_code") {
                    return OAuthHttpResponse(400, """{"error":"$error"}""")
                }
                return super.postForm(url, params)
            }
        }
    }

    /** 带 code 的回环回调（替身服务器用）。 */
    private fun codeCallback(state: String): wherevercode.agent.brain.siwc.LoopbackCallbackResult {
        return wherevercode.agent.brain.siwc.LoopbackCallbackResult(
            code = "auth-code-1",
            state = state,
            clientId = null,
            scope = null,
            error = null,
            errorDescription = null,
            rawQuery = emptyMap(),
        )
    }

    @Test
    fun `ensureAccessToken returns the saved token while unexpired`() = runTest {
        val keyPair = generateRsaKeyPair()
        val store = MemoryTokenStore()
        store.saveTokens(
            GoogleDriveTokens(
                accessToken = "access-1",
                refreshToken = "refresh-1",
                idToken = null,
                clientId = TEST_CLIENT_ID,
                accessTokenExpiresAtMillis = FIXED_NOW_MILLIS + 3_600_000L,
                accountLabel = TEST_EMAIL,
            ),
        )
        val auth = newAuth(FakeGoogleTransport(keyPair), store)
        assertEquals("access-1", auth.ensureAccessToken(TEST_CLIENT_ID))
    }

    @Test
    fun `ensureAccessToken refreshes an expired token and keeps the old refresh token`() = runTest {
        val keyPair = generateRsaKeyPair()
        val transport = FakeGoogleTransport(keyPair)
        val store = MemoryTokenStore()
        store.saveTokens(
            GoogleDriveTokens(
                accessToken = "access-old",
                refreshToken = "refresh-1",
                idToken = null,
                clientId = TEST_CLIENT_ID,
                accessTokenExpiresAtMillis = FIXED_NOW_MILLIS - 1000L,
                accountLabel = TEST_EMAIL,
            ),
        )
        val auth = newAuth(transport, store)
        assertEquals("access-2", auth.ensureAccessToken(TEST_CLIENT_ID))
        assertEquals("refresh_token", transport.tokenGrant)
        val saved = store.loadTokens()!!
        assertEquals("access-2", saved.accessToken)
        assertEquals("refresh-1", saved.refreshToken)
    }

    @Test
    fun `ensureAccessToken with a dead refresh grant clears and fails`() = runTest {
        val keyPair = generateRsaKeyPair()
        val transport = FakeGoogleTransport(keyPair).apply { refreshSucceeds = false }
        val store = MemoryTokenStore()
        store.saveTokens(
            GoogleDriveTokens(
                accessToken = "access-old",
                refreshToken = "refresh-dead",
                idToken = null,
                clientId = TEST_CLIENT_ID,
                accessTokenExpiresAtMillis = FIXED_NOW_MILLIS - 1000L,
                accountLabel = TEST_EMAIL,
            ),
        )
        val auth = newAuth(transport, store)
        try {
            auth.ensureAccessToken(TEST_CLIENT_ID)
            fail("expected AuthFailed for invalid_grant")
        } catch (e: AppUpdateError.AuthFailed) {
            assertTrue(e.message!!.contains("重新连接"))
        }
        assertNull(store.loadTokens())
    }

    @Test
    fun `ensureAccessToken without any tokens is NotSignedIn`() = runTest {
        val keyPair = generateRsaKeyPair()
        val auth = newAuth(FakeGoogleTransport(keyPair), MemoryTokenStore())
        try {
            auth.ensureAccessToken(TEST_CLIENT_ID)
            fail("expected NotSignedIn")
        } catch (e: AppUpdateError.NotSignedIn) {
            assertTrue(e.message!!.contains("未连接"))
        }
    }

    @Test
    fun `signOut clears the stored tokens`() = runTest {
        val keyPair = generateRsaKeyPair()
        val store = MemoryTokenStore()
        store.saveTokens(
            GoogleDriveTokens(
                accessToken = "access-1",
                refreshToken = "refresh-1",
                idToken = null,
                clientId = TEST_CLIENT_ID,
                accessTokenExpiresAtMillis = FIXED_NOW_MILLIS + 3_600_000L,
                accountLabel = TEST_EMAIL,
            ),
        )
        val auth = newAuth(FakeGoogleTransport(keyPair), store)
        auth.signOut()
        assertNull(store.loadTokens())
    }

    /**
     * 回归：底层密钥库不可用时（Robolectric 里没有 AndroidKeyStore，
     * 设置页 bind 时就会走到这里），构造不碰 Keystore、读当作未登录、
     * 清不抛错——不让一次读操作炸掉 Activity 启动（CI 第二/三轮 5 败
     * 的根因：BrainKeyStore 构造器里就建 EncryptedSharedPreferences）。
     */
    @Test
    fun `keystore-backed store fails closed when the secret store throws`() {
        val throwingSecrets = object : wherevercode.agent.brain.siwc.SiwcSecretStore {
            override fun put(ref: String, value: String): Unit =
                throw java.security.KeyStoreException("AndroidKeyStore unavailable")

            override fun get(ref: String): String? =
                throw java.security.KeyStoreException("AndroidKeyStore unavailable")

            override fun delete(ref: String): Unit =
                throw java.security.KeyStoreException("AndroidKeyStore unavailable")
        }
        val store = KeystoreGoogleDriveTokenStore { throwingSecrets }
        assertNull(store.loadTokens())
        store.clearTokens() // 不抛错
        val keyPair = generateRsaKeyPair()
        val auth = GoogleDriveAuth(
            OAuthEngine(GOOGLE_DRIVE_OAUTH_CONFIG, FakeGoogleTransport(keyPair), clockMillis = { FIXED_NOW_MILLIS }),
            store,
            MemoryClientSecretStore(),
            clockMillis = { FIXED_NOW_MILLIS },
            // 纯 JVM 单测里 android.util.Log 是 Stub：显式传空 logger，不碰它。
            logError = {},
        )
        assertFalse(auth.isSignedIn())
    }
}
