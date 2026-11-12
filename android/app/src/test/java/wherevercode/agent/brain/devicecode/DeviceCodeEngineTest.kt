package wherevercode.agent.brain.devicecode

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.oauth.OAuthHttpResponse
import wherevercode.agent.brain.oauth.OAuthHttpTransport
import wherevercode.agent.brain.siwc.BrainError

/**
 * RFC 8628 device-code engine tests (2026-11-12, membership add-all).
 *
 * Fake transport only: zero real calls, zero real accounts. The
 * fake records every request (URL, form params, headers) so the
 * tests pin the exact wire shape per vendor — including Kimi's
 * `X-Msh-*` device headers and the PKCE challenge/verifier pairing.
 */
class DeviceCodeEngineTest {

    private class FakeTransport : OAuthHttpTransport {
        val requests = mutableListOf<Triple<String, Map<String, String>, Map<String, String>>>()
        var scripted: ArrayDeque<OAuthHttpResponse> = ArrayDeque()

        fun respond(status: Int, body: String) {
            scripted.addLast(OAuthHttpResponse(status, body))
        }

        override fun postForm(url: String, params: Map<String, String>): OAuthHttpResponse =
            postForm(url, params, emptyMap())

        override fun postForm(
            url: String,
            params: Map<String, String>,
            headers: Map<String, String>,
        ): OAuthHttpResponse {
            requests.add(Triple(url, params, headers))
            return scripted.removeFirstOrNull()
                ?: OAuthHttpResponse(500, """{"error":"server_error"}""")
        }

        override fun get(url: String): OAuthHttpResponse =
            OAuthHttpResponse(404, "")
    }

    private fun engine(
        config: DeviceCodeConfig = DeviceCodeConfigs.QWEN,
        transport: FakeTransport = FakeTransport(),
        nowMillis: Long = 1_000_000L,
        sleeps: MutableList<Long> = mutableListOf(),
    ) = DeviceCodeEngine(
        config = config,
        http = transport,
        clockMillis = { nowMillis },
        sleeper = { sleeps.add(it) },
    )

    private fun deviceCodeBody(
        userCode: String = "ABCD-1234",
        interval: Long = 5,
        expiresIn: Long = 600,
    ) = JSONObject().apply {
        put("device_code", "device-code-1")
        put("user_code", userCode)
        put("verification_uri", "https://chat.qwen.ai/device")
        put("verification_uri_complete", "https://chat.qwen.ai/device?code=$userCode")
        put("expires_in", expiresIn)
        put("interval", interval)
    }.toString()

    private fun tokenBody(
        access: String = "access-1",
        refresh: String = "refresh-1",
        expiresIn: Long = 3600,
    ) = JSONObject().apply {
        put("access_token", access)
        put("refresh_token", refresh)
        put("expires_in", expiresIn)
        put("scope", "openid profile email model.completion")
    }.toString()

    @Test
    fun `requestDeviceCode sends client id scope and PKCE challenge`() = kotlinx.coroutines.test.runTest {
        val transport = FakeTransport()
        transport.respond(200, deviceCodeBody())
        val response = engine(transport = transport).requestDeviceCode("challenge-abc")
        assertEquals("device-code-1", response.deviceCode)
        assertEquals("ABCD-1234", response.userCode)
        assertEquals("https://chat.qwen.ai/device?code=ABCD-1234", response.verificationUriComplete)
        assertEquals(600L, response.expiresInSeconds)
        assertEquals(5L, response.intervalSeconds)
        val (url, params, _) = transport.requests.single()
        assertEquals(DeviceCodeConfigs.QWEN.deviceCodeUrl, url)
        assertEquals("f0304373b74a44d2b584a3fb70ca9e56", params["client_id"])
        assertEquals("openid profile email model.completion", params["scope"])
        assertEquals("challenge-abc", params["code_challenge"])
        assertEquals("S256", params["code_challenge_method"])
    }

    @Test
    fun `requestDeviceCode forwards Kimi device headers`() = kotlinx.coroutines.test.runTest {
        val transport = FakeTransport()
        transport.respond(200, deviceCodeBody())
        val headers = mapOf(
            "X-Msh-Platform" to "android",
            "X-Msh-Device-Id" to "device-uuid-1",
        )
        engine(config = DeviceCodeConfigs.KIMI, transport = transport)
            .requestDeviceCode("challenge-kimi", headers)
        val (url, params, sentHeaders) = transport.requests.single()
        assertEquals(DeviceCodeConfigs.KIMI.deviceCodeUrl, url)
        assertEquals("17e5f671-d194-4dfb-9706-5516cb48c098", params["client_id"])
        assertNull("Kimi sends no scope", params["scope"])
        assertEquals("android", sentHeaders["X-Msh-Platform"])
        assertEquals("device-uuid-1", sentHeaders["X-Msh-Device-Id"])
    }

    @Test
    fun `pollForToken waits through authorization_pending then saves tokens`() =
        kotlinx.coroutines.test.runTest {
            val transport = FakeTransport()
            transport.respond(400, """{"error":"authorization_pending"}""")
            transport.respond(200, tokenBody())
            val sleeps = mutableListOf<Long>()
            val tokens = engine(transport = transport, sleeps = sleeps).pollForToken(
                deviceCode = "device-code-1",
                codeVerifier = "verifier-abc",
                intervalSeconds = 5,
                expiresInSeconds = 600,
            )
            assertEquals("access-1", tokens.accessToken)
            assertEquals("refresh-1", tokens.refreshToken)
            assertEquals(3600L, tokens.expiresInSeconds)
            assertEquals(listOf(5_000L), sleeps)
            assertEquals(2, transport.requests.size)
            val (_, params, _) = transport.requests.last()
            assertEquals("urn:ietf:params:oauth:grant-type:device_code", params["grant_type"])
            assertEquals("device-code-1", params["device_code"])
            assertEquals("verifier-abc", params["code_verifier"])
            assertEquals("f0304373b74a44d2b584a3fb70ca9e56", params["client_id"])
        }

    @Test
    fun `pollForToken backs off on slow_down`() = kotlinx.coroutines.test.runTest {
        val transport = FakeTransport()
        transport.respond(400, """{"error":"slow_down"}""")
        transport.respond(200, tokenBody())
        val sleeps = mutableListOf<Long>()
        engine(transport = transport, sleeps = sleeps).pollForToken(
            deviceCode = "device-code-1",
            codeVerifier = "verifier-abc",
            intervalSeconds = 5,
            expiresInSeconds = 600,
        )
        assertEquals(listOf(10_000L), sleeps)
    }

    @Test
    fun `pollForToken fails on expired_token and access_denied`() =
        kotlinx.coroutines.test.runTest {
            for (error in listOf("expired_token", "access_denied")) {
                val transport = FakeTransport()
                transport.respond(400, """{"error":"$error"}""")
                var grantDead = false
                try {
                    engine(transport = transport).pollForToken(
                        deviceCode = "device-code-1",
                        codeVerifier = "verifier-abc",
                        intervalSeconds = 5,
                        expiresInSeconds = 600,
                        onGrantDead = { grantDead = true },
                    )
                    error("expected $error to fail")
                } catch (e: BrainError.AuthFailed) {
                    assertEquals(error, e.errorCode)
                }
                assertTrue("onGrantDead must fire for $error", grantDead)
            }
        }

    @Test
    fun `pollForToken honours cancellation`() = kotlinx.coroutines.test.runTest {
        val transport = FakeTransport()
        try {
            engine(transport = transport).pollForToken(
                deviceCode = "device-code-1",
                codeVerifier = "verifier-abc",
                intervalSeconds = 5,
                expiresInSeconds = 600,
                isCancelled = { true },
            )
            error("expected cancellation to fail")
        } catch (e: BrainError.AuthFailed) {
            assertEquals("device_code_cancelled", e.errorCode)
        }
        assertTrue("no request may be sent after cancellation", transport.requests.isEmpty())
    }

    @Test
    fun `refreshTokens posts refresh grant and keeps new tokens`() =
        kotlinx.coroutines.test.runTest {
            val transport = FakeTransport()
            transport.respond(200, tokenBody(access = "access-2", refresh = "refresh-2"))
            val tokens = engine(transport = transport)
                .refreshTokens("refresh-1")
            assertEquals("access-2", tokens.accessToken)
            assertEquals("refresh-2", tokens.refreshToken)
            val (url, params, _) = transport.requests.single()
            assertEquals(DeviceCodeConfigs.QWEN.tokenUrl, url)
            assertEquals("refresh_token", params["grant_type"])
            assertEquals("refresh-1", params["refresh_token"])
        }

    @Test
    fun `refreshTokens with invalid_grant invokes onGrantDead`() =
        kotlinx.coroutines.test.runTest {
            val transport = FakeTransport()
            transport.respond(400, """{"error":"invalid_grant"}""")
            var grantDead = false
            try {
                engine(transport = transport).refreshTokens(
                    "refresh-1",
                    onGrantDead = { grantDead = true },
                )
                error("expected invalid_grant to fail")
            } catch (e: BrainError.AuthFailed) {
                assertEquals("invalid_grant", e.errorCode)
            }
            assertTrue(grantDead)
        }

    @Test
    fun `pollForToken tolerates a 200 carrying an error body`() =
        kotlinx.coroutines.test.runTest {
            // RFC 8628 says pending comes back as HTTP 400, but some
            // servers answer 200 + {"error": ...} — the engine must
            // wait on that too, never treat it as a token grant.
            val transport = FakeTransport()
            transport.respond(200, """{"error":"authorization_pending"}""")
            transport.respond(200, tokenBody())
            val sleeps = mutableListOf<Long>()
            val tokens = engine(transport = transport, sleeps = sleeps).pollForToken(
                deviceCode = "device-code-1",
                codeVerifier = "verifier-abc",
                intervalSeconds = 5,
                expiresInSeconds = 600,
            )
            assertEquals("access-1", tokens.accessToken)
            assertEquals(listOf(5_000L), sleeps)
        }

    @Test
    fun `device code response without user code is a transport error`() =
        kotlinx.coroutines.test.runTest {
            val transport = FakeTransport()
            transport.respond(200, """{"device_code":"x"}""")
            try {
                engine(transport = transport).requestDeviceCode("challenge-abc")
                error("expected incomplete response to fail")
            } catch (e: BrainError.Transport) {
                assertTrue(e.message!!.contains("不完整"))
            }
        }

    @Test
    fun `tokens expire with leeway and report refreshability`() {
        val live = DeviceCodeTokens("a", "r", 3600, "", obtainedAtMillis = 1_000_000L)
        assertFalse(live.isExpired(1_000_000L + 3_000_000L))
        assertTrue(live.isExpired(1_000_000L + 3_600_000L))
        assertTrue(live.refreshable)
        val dead = DeviceCodeTokens("a", null, 3600, "", obtainedAtMillis = 0L)
        assertTrue(dead.isExpired(1_000_000L + 3_600_000L))
        assertFalse(dead.refreshable)
    }
}
