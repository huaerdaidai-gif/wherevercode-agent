package wherevercode.agent.brain.cloudflare

import java.io.BufferedReader
import java.io.StringReader
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import wherevercode.agent.brain.siwc.BrainError
import wherevercode.agent.brain.siwc.BrainMessage
import wherevercode.agent.brain.siwc.BrainRequest
import wherevercode.agent.brain.siwc.BrainRole
import wherevercode.agent.brain.siwc.BrainStreamEvent
import wherevercode.agent.brain.siwc.BrainToolCall

/**
 * Cloudflare BrainAdapter 单测（fake HTTP 层 + 内存凭据存储）。
 *
 * 绝不连接真实 api.cloudflare.com，不用真实账户凭据，不做真实推理。
 */
class CloudflareBrainAdapterTest {

    private class FakeCredentialStore(var token: String? = "fake-token") : CloudflareCredentialStore {
        override fun saveToken(token: String) {
            this.token = token
        }

        override fun loadToken(): String? = token

        override fun clearToken() {
            token = null
        }
    }

    private class FakeConnection(
        override val statusCode: Int,
        private val body: String,
        private val errorBody: String?,
    ) : CloudflareConnection {
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = errorBody
        override fun close() {}
    }

    private class FakeTransport(
        private val statusCode: Int,
        private val body: String = "",
        private val errorBody: String? = null,
    ) : CloudflareTransport {
        var opened = false
        var lastUrl: String? = null
        var lastBody: String? = null

        override fun open(url: String, apiToken: String, requestBody: String): CloudflareConnection {
            opened = true
            lastUrl = url
            lastBody = requestBody
            return FakeConnection(statusCode, body, errorBody)
        }
    }

    private fun sse(vararg lines: String): String = lines.joinToString("\n")

    private fun adapter(
        transport: CloudflareTransport,
        token: String? = "fake-token",
        accountId: String? = "acct-123",
        model: String? = "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
    ): Pair<CloudflareBrainAdapter, FakeCredentialStore> {
        val store = FakeCredentialStore(token)
        val adapter = CloudflareBrainAdapter(
            credentialStore = store,
            apiClient = CloudflareApiClient(transport),
            accountIdProvider = { accountId },
            baseUrlProvider = { CloudflareApiClient.ACCOUNTS_BASE_URL },
            configuredModelProvider = { model },
        )
        return adapter to store
    }

    private fun request(model: String? = null) = BrainRequest(
        messages = listOf(BrainMessage(BrainRole.USER, "你好")),
        instructions = "你是助手。",
        model = model,
    )

    private suspend fun collectFailed(
        adapter: CloudflareBrainAdapter,
        req: BrainRequest,
    ): BrainError {
        val events = adapter.streamInfer(req).toList()
        val failed = events.filterIsInstance<BrainStreamEvent.Failed>().singleOrNull()
            ?: throw AssertionError("expected a Failed event, got: $events")
        return failed.error
    }

    @Test
    fun `streamInfer emits real text and completed with the configured model`() = runTest {
        val transport = FakeTransport(
            200,
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "",
                "data: [DONE]",
                "",
            ),
        )
        val (adapter, _) = adapter(transport)
        val events = adapter.streamInfer(request()).toList()
        assertEquals(2, events.size)
        assertEquals(BrainStreamEvent.Delta("你好"), events[0])
        val completed = (events[1] as BrainStreamEvent.Completed).response
        assertEquals("你好", completed.text)
        assertEquals("@cf/meta/llama-3.1-8b-instruct-fp8-fast", completed.model)
        assertFalse(completed.billedToUserPlan)
        assertTrue(transport.opened)
    }

    @Test
    fun `tool-role messages reach the request body as role tool`() = runTest {
        val transport = FakeTransport(
            200,
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "",
                "data: [DONE]",
                "",
            ),
        )
        val (adapter, _) = adapter(transport)
        val req = BrainRequest(
            messages = listOf(
                BrainMessage(BrainRole.USER, "列出文件"),
                BrainMessage(
                    BrainRole.ASSISTANT,
                    "{\"name\":\"list_files\",\"arguments\":{}}",
                    toolCalls = listOf(
                        BrainToolCall(id = "call-1", name = "list_files", argumentsJson = "{}"),
                    ),
                ),
                BrainMessage(
                    BrainRole.TOOL,
                    "{\"tool\":\"list_files\",\"ok\":true,\"summary\":\"已列出 （1 项）\"}",
                    toolCallId = "call-1",
                ),
            ),
            instructions = "你是助手。",
        )
        adapter.streamInfer(req).toList()
        val sentBody = org.json.JSONObject(transport.lastBody!!)
        val messages = sentBody.getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        val assistant = messages.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        val calls = assistant.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        assertEquals("call-1", calls.getJSONObject(0).getString("id"))
        assertEquals("list_files", calls.getJSONObject(0).getJSONObject("function").getString("name"))
        val tool = messages.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call-1", tool.getString("tool_call_id"))
        assertTrue(tool.getString("content").contains("\"tool\":\"list_files\""))
        assertFalse(transport.lastBody!!.contains("工具执行结果："))
    }

    @Test
    fun `server-sent tool calls keep their real id instead of a client-made one`() = runTest {
        val transport = FakeTransport(
            200,
            sse(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-42\",\"type\":\"function\",\"function\":{\"name\":\"list_files\",\"arguments\":\"{}\"}}]}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                "",
                "data: [DONE]",
                "",
            ),
        )
        val (adapter, _) = adapter(transport)
        val events = adapter.streamInfer(request()).toList()
        val call = events.filterIsInstance<BrainStreamEvent.ToolCall>().single()
        assertEquals("call-42", call.id)
        assertEquals("list_files", call.name)
        assertEquals("{}", call.argumentsJson)
    }

    @Test
    fun `infer collects the stream into one response`() = runTest {
        val transport = FakeTransport(
            200,
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"甲\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{\"content\":\"乙\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "",
                "data: [DONE]",
                "",
            ),
        )
        val (adapter, _) = adapter(transport)
        val response = adapter.infer(request())
        assertEquals("甲乙", response.text)
    }

    @Test
    fun `explicit request model wins over the configured model`() = runTest {
        val transport = FakeTransport(
            200,
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
                "",
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "",
                "data: [DONE]",
                "",
            ),
        )
        val (adapter, _) = adapter(transport)
        adapter.streamInfer(request(model = "@cf/openai/gpt-oss-120b")).toList()
        // 模型不在 URL 里，改在请求体 model 字段；URL 固定为 chat completions。
        assertTrue(transport.lastUrl!!.endsWith("/ai/v1/chat/completions"))
        assertFalse(transport.lastUrl!!.contains("@cf/openai/gpt-oss-120b"))
        val sentBody = org.json.JSONObject(transport.lastBody!!)
        assertEquals("@cf/openai/gpt-oss-120b", sentBody.getString("model"))
    }

    @Test
    fun `no token yields NotConfigured and no HTTP call`() = runTest {
        val transport = FakeTransport(200, "")
        val (adapter, _) = adapter(transport, token = null)
        assertFalse(adapter.isConfigured())
        val error = collectFailed(adapter, request())
        assertTrue(error is BrainError.NotConfigured)
        assertFalse(transport.opened)
    }

    @Test
    fun `no account id yields NotConfigured and no HTTP call`() = runTest {
        val transport = FakeTransport(200, "")
        val (adapter, _) = adapter(transport, accountId = null)
        assertFalse(adapter.isConfigured())
        val error = collectFailed(adapter, request())
        assertTrue(error is BrainError.NotConfigured)
        assertFalse(transport.opened)
    }

    @Test
    fun `blank configured model and no explicit model yields NotConfigured`() = runTest {
        val transport = FakeTransport(200, "")
        val (adapter, _) = adapter(transport, model = "")
        assertFalse(adapter.isConfigured())
        val error = collectFailed(adapter, request())
        assertTrue(error is BrainError.NotConfigured)
        assertTrue(error.message!!.contains("模型"))
        assertFalse(transport.opened)
    }

    @Test
    fun `429 with 3036 yields QuotaExceeded mentioning the reset`() = runTest {
        val errorBody = "{\"success\":false,\"errors\":[{\"code\":3036," +
            "\"message\":\"You have used up your daily free allocation of 10,000 neurons.\"}]}"
        val transport = FakeTransport(429, errorBody = errorBody)
        val (adapter, _) = adapter(transport)
        val error = collectFailed(adapter, request())
        assertTrue(error is BrainError.QuotaExceeded)
        assertTrue(error.message!!.contains("00:00 UTC"))
    }

    @Test
    fun `401 yields AuthFailed`() = runTest {
        val transport = FakeTransport(
            401,
            errorBody = "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Authentication error\"}]}",
        )
        val (adapter, _) = adapter(transport)
        val error = collectFailed(adapter, request())
        assertTrue(error is BrainError.AuthFailed)
    }

    @Test
    fun `403 with 5035 yields Unsupported naming the paid plan`() = runTest {
        val transport = FakeTransport(
            403,
            errorBody = "{\"success\":false,\"errors\":[{\"code\":5035," +
                "\"message\":\"This model requires a Workers Paid plan.\"}]}",
        )
        val (adapter, _) = adapter(transport)
        val error = collectFailed(adapter, request())
        assertTrue(error is BrainError.Unsupported)
        assertTrue(error.message!!.contains("Workers Paid"))
    }

    @Test
    fun `temperature above 5 yields Unsupported without an HTTP call`() = runTest {
        val transport = FakeTransport(200, "")
        val (adapter, _) = adapter(transport)
        val error = collectFailed(adapter, request().copy(temperature = 6.0))
        assertTrue(error is BrainError.Unsupported)
        assertFalse(transport.opened)
    }

    @Test
    fun `capabilities report streaming tools system messages and no plan billing`() {
        val (adapter, _) = adapter(FakeTransport(200))
        val caps = adapter.capabilities
        assertTrue(caps.supportsStreaming)
        assertTrue(caps.supportsTools)
        assertTrue(caps.supportsSystemMessages)
        assertTrue(caps.requiresFullHistory)
        assertTrue(caps.supportsTemperature)
        assertTrue(caps.supportsMaxOutputTokens)
        assertFalse(caps.billsUserPlan)
    }

    @Test
    fun `revoke clears the token and unconfigures the provider`() = runTest {
        val (adapter, store) = adapter(FakeTransport(200))
        assertTrue(adapter.isConfigured())
        adapter.revoke()
        assertEquals(null, store.loadToken())
        assertFalse(adapter.isConfigured())
    }

    @Test
    fun `provider id and display name are stable`() {
        val (adapter, _) = adapter(FakeTransport(200))
        assertEquals("cloudflare-workers-ai", adapter.providerId)
        assertEquals("Cloudflare Workers AI", adapter.displayName)
        assertEquals(
            "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
            CloudflareBrainAdapter.SUGGESTED_DEFAULT_MODEL,
        )
    }
}
