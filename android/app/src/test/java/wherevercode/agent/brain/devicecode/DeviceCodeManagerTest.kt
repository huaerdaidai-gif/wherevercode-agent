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
import wherevercode.agent.brain.siwc.SiwcSecretStore

/**
 * Device-code token store + manager tests (2026-11-12).
 *
 * Fake secret store + fake transport only: zero real calls, zero
 * real accounts. Pins the credential rules — tokens persist under
 * the vendor authRef only, refresh keeps an unrotated refresh token,
 * a dead grant wipes the store, and sign-out wipes it too (the
 * delete path relies on the same wipe).
 */
class DeviceCodeManagerTest {

    private class FakeSecrets : SiwcSecretStore {
        val values = mutableMapOf<String, String>()
        override fun put(ref: String, value: String) {
            values[ref] = value
        }

        override fun get(ref: String): String? = values[ref]
        override fun delete(ref: String) {
            values.remove(ref)
        }
    }

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

        override fun get(url: String): OAuthHttpResponse = OAuthHttpResponse(404, "")
    }

    private fun tokenBody(
        access: String = "access-1",
        refresh: String? = "refresh-1",
        expiresIn: Long = 3600,
    ) = JSONObject().apply {
        put("access_token", access)
        if (refresh != null) put("refresh_token", refresh)
        put("expires_in", expiresIn)
        put("scope", "openid profile email model.completion")
    }.toString()

    private fun deviceCodeBody() = JSONObject().apply {
        put("device_code", "device-code-1")
        put("user_code", "ABCD-1234")
        put("verification_uri", "https://chat.qwen.ai/device")
        put("verification_uri_complete", "https://chat.qwen.ai/device?code=ABCD-1234")
        put("expires_in", 600)
        put("interval", 5)
    }.toString()

    private fun setup(
        nowMillis: Long = 1_000_000L,
        transport: FakeTransport = FakeTransport(),
        secrets: FakeSecrets = FakeSecrets(),
    ): Triple<DeviceCodeManager, DeviceCodeTokenStore, FakeTransport> {
        val store = DeviceCodeTokenStore(secrets, DeviceCodeConfigs.QWEN.authRef)
        val manager = DeviceCodeManager(
            config = DeviceCodeConfigs.QWEN,
            tokenStore = store,
            http = transport,
            clockMillis = { nowMillis },
        )
        return Triple(manager, store, transport)
    }

    // ---- token store ----

    @Test
    fun `tokens round-trip under the vendor authRef only`() {
        val secrets = FakeSecrets()
        val store = DeviceCodeTokenStore(secrets, "devicecode:qwen")
        assertNull(store.loadTokens())
        assertFalse(store.hasTokens())
        store.saveTokens(DeviceCodeTokens("a", "r", 3600, "openid", 1_000_000L))
        assertEquals(setOf("devicecode:qwen"), secrets.values.keys)
        val loaded = store.loadTokens()!!
        assertEquals("a", loaded.accessToken)
        assertEquals("r", loaded.refreshToken)
        assertTrue(store.hasTokens())
        store.clearTokens()
        assertNull(store.loadTokens())
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun `malformed stored JSON loads as no tokens`() {
        val secrets = FakeSecrets()
        secrets.put("devicecode:qwen", "not-json{{{")
        val store = DeviceCodeTokenStore(secrets, "devicecode:qwen")
        assertNull(store.loadTokens())
    }

    // ---- manager: login ----

    @Test
    fun `begin then await saves tokens after pending`() =
        kotlinx.coroutines.test.runTest {
            val (manager, store, transport) = setup()
            transport.respond(200, deviceCodeBody())
            val attempt = manager.beginLogin()
            assertEquals("ABCD-1234", attempt.response.userCode)
            assertTrue("verifier must be a fresh PKCE value", attempt.codeVerifier.length >= 43)
            transport.respond(400, """{"error":"authorization_pending"}""")
            transport.respond(200, tokenBody())
            val tokens = manager.awaitAuthorization(attempt)
            assertEquals("access-1", tokens.accessToken)
            assertEquals("access-1", store.loadTokens()?.accessToken)
            assertTrue(manager.isSignedIn())
        }

    @Test
    fun `await failure on expired code clears any stored tokens`() =
        kotlinx.coroutines.test.runTest {
            val (manager, store, transport) = setup()
            store.saveTokens(DeviceCodeTokens("old", "old-refresh", 3600, "", 1_000_000L))
            transport.respond(200, deviceCodeBody())
            val attempt = manager.beginLogin()
            transport.respond(400, """{"error":"expired_token"}""")
            try {
                manager.awaitAuthorization(attempt)
                error("expected expired_token to fail")
            } catch (e: BrainError.AuthFailed) {
                assertEquals("expired_token", e.errorCode)
            }
            assertNull("a dead grant must not linger", store.loadTokens())
            assertFalse(manager.isSignedIn())
        }

    // ---- manager: refresh ----

    @Test
    fun `ensureFreshAccessToken returns a live token without a call`() =
        kotlinx.coroutines.test.runTest {
            val transport = FakeTransport()
            val secrets = FakeSecrets()
            val store = DeviceCodeTokenStore(secrets, DeviceCodeConfigs.QWEN.authRef)
            store.saveTokens(DeviceCodeTokens("live", "r", 3600, "", 1_000_000L))
            val live = DeviceCodeManager(
                config = DeviceCodeConfigs.QWEN,
                tokenStore = store,
                http = transport,
                clockMillis = { 1_000_000L },
            )
            assertEquals("live", live.ensureFreshAccessToken())
            assertTrue("no refresh call for a live token", transport.requests.isEmpty())
        }

    @Test
    fun `ensureFreshAccessToken refreshes an expired token and keeps the old refresh token when unrotated`() =
        kotlinx.coroutines.test.runTest {
            val (manager, store, transport) = setup(nowMillis = 2_000_000L)
            store.saveTokens(DeviceCodeTokens("old", "refresh-1", 60, "", obtainedAtMillis = 0L))
            transport.respond(200, tokenBody(access = "access-2", refresh = null))
            assertEquals("access-2", manager.ensureFreshAccessToken())
            assertEquals("access-2", store.loadTokens()?.accessToken)
            assertEquals(
                "an unrotated refresh response must keep the old refresh token",
                "refresh-1",
                store.loadTokens()?.refreshToken,
            )
        }

    @Test
    fun `ensureFreshAccessToken with no tokens fails as not signed in`() =
        kotlinx.coroutines.test.runTest {
            val (manager, _, _) = setup()
            try {
                manager.ensureFreshAccessToken()
                error("expected not-signed-in to fail")
            } catch (e: BrainError.AuthFailed) {
                assertEquals("device_code_not_signed_in", e.errorCode)
            }
        }

    @Test
    fun `ensureFreshAccessToken with expired unrefreshable tokens wipes the store`() =
        kotlinx.coroutines.test.runTest {
            val (manager, store, _) = setup(nowMillis = 2_000_000L)
            store.saveTokens(DeviceCodeTokens("old", null, 60, "", obtainedAtMillis = 0L))
            try {
                manager.ensureFreshAccessToken()
                error("expected expired grant to fail")
            } catch (e: BrainError.AuthFailed) {
                assertEquals("device_code_expired", e.errorCode)
            }
            assertNull(store.loadTokens())
        }

    @Test
    fun `signOut wipes the tokens`() {
        val (manager, store, _) = setup()
        store.saveTokens(DeviceCodeTokens("a", "r", 3600, "", 1_000_000L))
        assertTrue(manager.isSignedIn())
        manager.signOut()
        assertNull(store.loadTokens())
        assertFalse(manager.isSignedIn())
    }
}
