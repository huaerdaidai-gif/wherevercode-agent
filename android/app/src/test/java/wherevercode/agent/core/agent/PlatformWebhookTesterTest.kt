package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * “发一条测试通知”（V5，2026-11-11）：回包状态如实报，通就是通、
 * 不通就报具体原因，不伪装成功。假 HTTP 客户端，不发真实调用。
 */
class PlatformWebhookTesterTest {

    private class ScriptedHttp(
        private val status: Int,
        private val body: String = "ok",
    ) : WebhookHttpClient {
        data class Call(val url: String, val headers: Map<String, String>, val bodyJson: String)
        val calls = mutableListOf<Call>()
        override fun postJson(
            url: String,
            headers: Map<String, String>,
            bodyJson: String,
        ): Pair<Int, String> {
            calls += Call(url, headers, bodyJson)
            return status to body
        }
    }

    private class FailingHttp(
        private val message: String = "Unable to resolve host",
    ) : WebhookHttpClient {
        override fun postJson(
            url: String,
            headers: Map<String, String>,
            bodyJson: String,
        ): Pair<Int, String> = throw java.io.IOException(message)
    }

    @Test
    fun `http 200 is ok and the body is marked as a test`() {
        val http = ScriptedHttp(200, """{"received":true}""")
        val result = PlatformWebhookTester.testNotification(
            webhookUrl = "https://example.com/hook",
            platformAgentId = "example-agent",
            signingKey = null,
            httpClient = http,
        )

        val ok = result as WebhookTestResult.Ok
        assertEquals(200, ok.httpStatus)
        assertEquals(1, http.calls.size)
        assertEquals("https://example.com/hook", http.calls[0].url)
        val body = org.json.JSONObject(http.calls[0].bodyJson)
        assertEquals("wherevercode_test", body.optString("event"))
        assertEquals("example-agent", body.optString("agent_id"))
        assertTrue(!http.calls[0].headers.containsKey(PlatformWebhookTester.SIGNATURE_HEADER))
    }

    @Test
    fun `signing key adds an hmac header and nothing else leaks the key`() {
        val http = ScriptedHttp(200)
        PlatformWebhookTester.testNotification(
            webhookUrl = "https://example.com/hook",
            platformAgentId = "example-agent",
            signingKey = "test-secret-not-real",
            httpClient = http,
        )

        val header = http.calls[0].headers[PlatformWebhookTester.SIGNATURE_HEADER]
        assertTrue(header!!.startsWith("sha256="))
        val expected = "sha256=" + PlatformWebhookTester.hmacSha256Hex(
            "test-secret-not-real",
            http.calls[0].bodyJson,
        )
        assertEquals(expected, header)
        assertTrue(!http.calls[0].bodyJson.contains("test-secret-not-real"))
        assertTrue(!http.calls[0].headers.values.any { it.contains("test-secret-not-real") })
    }

    @Test
    fun `http 500 is reported with the real status not as success`() {
        val result = PlatformWebhookTester.testNotification(
            webhookUrl = "https://example.com/hook",
            platformAgentId = "example-agent",
            signingKey = null,
            httpClient = ScriptedHttp(500, "boom"),
        )

        val error = result as WebhookTestResult.HttpError
        assertEquals(500, error.httpStatus)
        assertTrue(error.bodyPreview.contains("boom"))
    }

    @Test
    fun `unreachable host reports the concrete reason`() {
        val result = PlatformWebhookTester.testNotification(
            webhookUrl = "https://no-such-host.example/hook",
            platformAgentId = "example-agent",
            signingKey = null,
            httpClient = FailingHttp("Unable to resolve host"),
        )

        val error = result as WebhookTestResult.NetworkError
        assertTrue(error.message.contains("Unable to resolve host"))
    }

    @Test
    fun `blank or schemeless url is refused without a call`() {
        val http = ScriptedHttp(200)
        val blank = PlatformWebhookTester.testNotification(
            webhookUrl = "   ",
            platformAgentId = "example-agent",
            signingKey = null,
            httpClient = http,
        )
        assertTrue(blank is WebhookTestResult.NetworkError)
        val schemeless = PlatformWebhookTester.testNotification(
            webhookUrl = "example.com/hook",
            platformAgentId = "example-agent",
            signingKey = null,
            httpClient = http,
        )
        assertTrue(schemeless is WebhookTestResult.NetworkError)
        assertTrue(http.calls.isEmpty())
    }
}
