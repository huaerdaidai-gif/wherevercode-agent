package wherevercode.agent.brain.cloudflare

import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import wherevercode.agent.brain.siwc.BrainError

/**
 * Cloudflare Workers AI 客户端与流式解析器单测。
 *
 * 全部用本地 fake HTTP 层与录制的 SSE 文本做输入，绝不连接真实
 * api.cloudflare.com，不用真实账户凭据，不做真实推理。
 *
 * 运行（合入工程树后，文件位于
 * android/app/src/test/java/wherevercode/agent/brain/cloudflare/）：
 *   ./gradlew :app:testDebugUnitTest --tests "*CloudflareApiTest*"
 * 所需依赖 M2 工程已有：junit 4.13.2、kotlinx-coroutines-test 1.7.3、
 * org.json（testImplementation org.json:json:20240303）。
 */
class CloudflareApiTest {

    // ---------- Fake HTTP 层 ----------

    private class FakeConnection(
        override val statusCode: Int,
        private val body: String,
        private val errorBody: String?,
        private val onClose: () -> Unit,
    ) : CloudflareConnection {
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = errorBody
        override fun close() = onClose()
    }

    private class FakeTransport(
        private val statusCode: Int,
        private val body: String = "",
        private val errorBody: String? = null,
        private val ioExceptionOnOpen: IOException? = null,
    ) : CloudflareTransport {
        var opened = false
        var closed = false
        var lastUrl: String? = null
        var lastToken: String? = null
        var lastBody: String? = null

        override fun open(url: String, apiToken: String, requestBody: String): CloudflareConnection {
            opened = true
            lastUrl = url
            lastToken = apiToken
            lastBody = requestBody
            ioExceptionOnOpen?.let { throw it }
            return FakeConnection(statusCode, body, errorBody) { closed = true }
        }
    }

    private fun request(
        temperature: Double? = null,
        maxOutputTokens: Int? = null,
    ) = CloudflareApiRequest(
        model = "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
        messages = listOf(
            CloudflareInputMessage("user", "你好"),
            CloudflareInputMessage("assistant", "你好，有什么可以帮你？"),
            CloudflareInputMessage("user", "继续"),
        ),
        instructions = "你是 WhereverCode 的助手。",
        temperature = temperature,
        maxOutputTokens = maxOutputTokens,
    )

    private fun client(transport: CloudflareTransport) = CloudflareApiClient(transport)

    private fun sseText(vararg lines: String): String = lines.joinToString("\n")

    // ---------- 正常回复与流式解析 ----------

    @Test
    fun `streaming success emits deltas then completed with full text`() = runTest {
        val body = sseText(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val events = client(transport)
            .streamResponse(request(), "acct-123", "test-token", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        assertEquals(3, events.size)
        assertEquals(CloudflareStreamEvent.TextDelta("你好"), events[0])
        assertEquals(CloudflareStreamEvent.TextDelta("，世界"), events[1])
        val completed = events[2] as CloudflareStreamEvent.Completed
        assertEquals("你好，世界", completed.fullText)
        assertTrue(transport.opened)
        assertTrue(transport.closed)
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/acct-123/ai/v1/chat/completions",
            transport.lastUrl,
        )
        assertEquals("test-token", transport.lastToken)
        val sentBody = JSONObject(transport.lastBody!!)
        assertTrue(sentBody.getBoolean("stream"))
        assertEquals("@cf/meta/llama-3.1-8b-instruct-fp8-fast", sentBody.getString("model"))
        val messages = sentBody.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("你是 WhereverCode 的助手。", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `tool role messages are emitted with tool_call_id in the request body`() = runTest {
        val body = sseText(
            "data: {\"choices\":[{\"delta\":{\"content\":\"工作区里有 README.md。\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val request = CloudflareApiRequest(
            model = "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
            messages = listOf(
                CloudflareInputMessage("user", "列出工作区里的文件"),
                // P1-2 配对形态：assistant 带 tool_calls 在前，
                // role="tool" 的结果带 tool_call_id 在后。
                CloudflareInputMessage(
                    "assistant",
                    "{\"name\":\"list_files\",\"arguments\":{}}",
                    toolCalls = listOf(
                        CloudflareToolCall(
                            id = "call-1",
                            name = "list_files",
                            argumentsJson = "{}",
                        ),
                    ),
                ),
                CloudflareInputMessage(
                    "tool",
                    "{\"tool\":\"list_files\",\"ok\":true,\"summary\":\"已列出 （1 项）\",\"data\":\"file README.md\"}",
                    toolCallId = "call-1",
                ),
            ),
            instructions = "你是 WhereverCode 的助手。",
        )
        client(transport)
            .streamResponse(request, "acct-123", "test-token", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        val sentBody = JSONObject(transport.lastBody!!)
        val messages = sentBody.getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        val assistant = messages.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        // 旧的纯 content 回显已废止：有 tool_calls 时 content 为 null。
        assertTrue(assistant.isNull("content"))
        val calls = assistant.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        assertEquals("call-1", calls.getJSONObject(0).getString("id"))
        assertEquals("function", calls.getJSONObject(0).getString("type"))
        val fn = calls.getJSONObject(0).getJSONObject("function")
        assertEquals("list_files", fn.getString("name"))
        assertEquals("{}", fn.getString("arguments"))
        val tool = messages.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call-1", tool.getString("tool_call_id"))
        assertTrue(tool.getString("content").contains("\"tool\":\"list_files\""))
        // 旧的 USER 形态回填已废止：请求体内不再有“工具…执行结果：”前缀。
        assertFalse(transport.lastBody!!.contains("工具执行结果："))
    }

    @Test
    fun `multi-call round sends one assistant message with N tool_calls then N tool messages in order`() = runTest {
        val body = sseText(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val request = CloudflareApiRequest(
            model = "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
            messages = listOf(
                CloudflareInputMessage("user", "列出文件并读 README.txt"),
                CloudflareInputMessage(
                    "assistant",
                    "",
                    toolCalls = listOf(
                        CloudflareToolCall("call-1", "list_files", "{}"),
                        CloudflareToolCall("call-2", "read_file", "{\"path\":\"README.txt\"}"),
                    ),
                ),
                CloudflareInputMessage("tool", "{\"tool\":\"list_files\",\"ok\":true}", toolCallId = "call-1"),
                CloudflareInputMessage("tool", "{\"tool\":\"read_file\",\"ok\":true}", toolCallId = "call-2"),
            ),
        )
        client(transport)
            .streamResponse(request, "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        val sentBody = JSONObject(transport.lastBody!!)
        val messages = sentBody.getJSONArray("messages")
        assertEquals(4, messages.length())
        // 一条 assistant 带 2 个 tool_calls，不拆成两组。
        val calls = messages.getJSONObject(1).getJSONArray("tool_calls")
        assertEquals(2, calls.length())
        assertEquals("call-1", calls.getJSONObject(0).getString("id"))
        assertEquals("list_files", calls.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals("call-2", calls.getJSONObject(1).getString("id"))
        assertEquals("read_file", calls.getJSONObject(1).getJSONObject("function").getString("name"))
        assertEquals("{\"path\":\"README.txt\"}", calls.getJSONObject(1).getJSONObject("function").getString("arguments"))
        // 后跟 2 条 tool 消息，顺序与 id 与 tool_calls 一致。
        assertEquals("tool", messages.getJSONObject(2).getString("role"))
        assertEquals("call-1", messages.getJSONObject(2).getString("tool_call_id"))
        assertEquals("tool", messages.getJSONObject(3).getString("role"))
        assertEquals("call-2", messages.getJSONObject(3).getString("tool_call_id"))
    }

    @Test
    fun `tool message without an id is downgraded to a user-visible result note`() = runTest {
        val body = sseText(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val request = CloudflareApiRequest(
            model = "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
            messages = listOf(
                CloudflareInputMessage("user", "列出文件"),
                // 旧持久化记录：tool 消息没有 toolCallId。
                CloudflareInputMessage("tool", "{\"tool\":\"list_files\",\"ok\":true}"),
            ),
        )
        client(transport)
            .streamResponse(request, "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        val sentBody = JSONObject(transport.lastBody!!)
        val messages = sentBody.getJSONArray("messages")
        assertEquals(2, messages.length())
        // 无 id 的 role="tool" 绝不发出；信息以 user 消息保留。
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertTrue(messages.getJSONObject(1).getString("content").startsWith("[工具结果]\n"))
        assertFalse(transport.lastBody!!.contains("\"role\":\"tool\""))
    }

    @Test
    fun `system role in messages is still rejected`() = runTest {
        val transport = FakeTransport(200, "")
        val request = CloudflareApiRequest(
            model = "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
            messages = listOf(CloudflareInputMessage("system", "不应直接进 messages")),
        )
        try {
            client(transport)
                .streamResponse(request, "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
                .toList()
            fail("system 角色应被拒绝")
        } catch (e: BrainError.Unsupported) {
            assertTrue(e.message!!.contains("system"))
        }
    }

    @Test
    fun `streaming usage in final chunk is reported`() = runTest {
        val body = sseText(
            "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}",
            "",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val events = client(transport)
            .streamResponse(request(), "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        val completed = events.last() as CloudflareStreamEvent.Completed
        assertEquals(12L, completed.usage?.inputTokens)
        assertEquals(3L, completed.usage?.outputTokens)
        assertEquals(15L, completed.usage?.totalTokens)
    }

    @Test
    fun `tool_calls in a chunk become ToolCallDone with the server-sent id`() = runTest {
        val body = sseText(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-9\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"/a/b.txt\\\"}\"}}]}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val events = client(transport)
            .streamResponse(request(), "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        val call = events.filterIsInstance<CloudflareStreamEvent.ToolCallDone>().single()
        assertEquals("read_file", call.name)
        assertEquals("/a/b.txt", JSONObject(call.arguments).getString("path"))
        // P1-2：id 是服务端真实发的，不再是客户端自造的 cf-call-N。
        assertEquals("call-9", call.callId)
    }

    @Test
    fun `tool-call arguments fragmented across chunks accumulate before emitting`() = runTest {
        val body = sseText(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-7\",\"type\":\"function\",\"function\":{\"name\":\"read_file\",\"arguments\":\"\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"path\\\":\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"README.txt\\\"}\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val events = client(transport)
            .streamResponse(request(), "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        val call = events.filterIsInstance<CloudflareStreamEvent.ToolCallDone>().single()
        assertEquals("call-7", call.callId)
        assertEquals("read_file", call.name)
        assertEquals("README.txt", JSONObject(call.arguments).getString("path"))
    }

    @Test
    fun `string arguments in tool_calls pass through unchanged`() = runTest {
        val body = sseText(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-3\",\"type\":\"function\",\"function\":{\"name\":\"x\",\"arguments\":\"{\\\"a\\\":1}\"}}]}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
            "",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val events = client(transport)
            .streamResponse(request(), "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        val call = events.filterIsInstance<CloudflareStreamEvent.ToolCallDone>().single()
        assertEquals("{\"a\":1}", call.arguments)
        assertEquals("call-3", call.callId)
    }

    @Test
    fun `multi-line data payload split across SSE lines reassembles`() = runTest {
        // 一个 JSON 事件体被拆成三行 data: 发来（SSE 分片）：解析器须先
        // 攒着，等拼出完整 JSON 再分发，绝不产出半截内容。
        val body = sseText(
            "data: {",
            "data:   \"choices\": [{\"delta\": {\"content\": \"你好\"}}]",
            "data: }",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val events = client(transport)
            .streamResponse(request(), "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        assertEquals(2, events.size)
        assertEquals(CloudflareStreamEvent.TextDelta("你好"), events[0])
        val completed = events[1] as CloudflareStreamEvent.Completed
        assertEquals("你好", completed.fullText)
    }

    @Test
    fun `empty response chunk completes with no text delta`() = runTest {
        val body = sseText(
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val events = client(transport)
            .streamResponse(request(), "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        assertTrue(events.none { it is CloudflareStreamEvent.TextDelta })
        val completed = events.last() as CloudflareStreamEvent.Completed
        assertEquals(null, completed.fullText)
    }

    @Test
    fun `malformed SSE line is reported and no fake text is emitted`() = runTest {
        val malformed = mutableListOf<String>()
        val body = sseText(
            "data: not-json",
            "",
            "data: {\"choices\":[{\"delta\":{\"content\":\"真\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val events = CloudflareApiClient(transport, onMalformedLine = { malformed += it })
            .streamResponse(request(), "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        assertEquals(1, malformed.size)
        assertTrue(events.any { it is CloudflareStreamEvent.TextDelta && it.text == "真" })
        assertEquals(2, events.size) // TextDelta + Completed
    }

    @Test
    fun `stream cut before DONE emits Failed after prior deltas`() = runTest {
        val body = sseText(
            "data: {\"choices\":[{\"delta\":{\"content\":\"半\"}}]}",
            "",
        )
        val transport = FakeTransport(200, body)
        val events = client(transport)
            .streamResponse(request(), "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        assertEquals(2, events.size)
        assertEquals(CloudflareStreamEvent.TextDelta("半"), events[0])
        val failed = events[1] as CloudflareStreamEvent.Failed
        assertEquals("stream_ended_without_terminal", failed.code)
        assertTrue(failed.retryable)
    }

    @Test
    fun `stream cut before DONE with no events throws Transport`() = runTest {
        val transport = FakeTransport(200, "")
        try {
            client(transport)
                .streamResponse(request(), "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
                .toList()
            fail("expected BrainError.Transport")
        } catch (e: BrainError.Transport) {
            assertTrue(e.message!!.contains("data: [DONE]"))
        }
    }

    // ---------- 请求发出前的校验 ----------

    @Test
    fun `blank token fails before any HTTP call`() = runTest {
        val transport = FakeTransport(200, "")
        try {
            client(transport)
                .streamResponse(request(), "acct-123", "  ", CloudflareApiClient.ACCOUNTS_BASE_URL)
                .toList()
            fail("expected BrainError.NotConfigured")
        } catch (e: BrainError.NotConfigured) {
            assertFalse(transport.opened)
        }
    }

    @Test
    fun `blank account id fails before any HTTP call`() = runTest {
        val transport = FakeTransport(200, "")
        try {
            client(transport)
                .streamResponse(request(), "", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
                .toList()
            fail("expected BrainError.NotConfigured")
        } catch (e: BrainError.NotConfigured) {
            assertFalse(transport.opened)
        }
    }

    @Test
    fun `temperature out of range is rejected locally`() = runTest {
        val transport = FakeTransport(200, "")
        try {
            client(transport)
                .streamResponse(
                    request(temperature = 5.5),
                    "acct-123",
                    "tok",
                    CloudflareApiClient.ACCOUNTS_BASE_URL,
                )
                .toList()
            fail("expected BrainError.Unsupported")
        } catch (e: BrainError.Unsupported) {
            assertFalse(transport.opened)
            assertTrue(e.message!!.contains("0–5"))
        }
    }

    @Test
    fun `max_tokens below 1 is rejected locally`() = runTest {
        val transport = FakeTransport(200, "")
        try {
            client(transport)
                .streamResponse(
                    request(maxOutputTokens = 0),
                    "acct-123",
                    "tok",
                    CloudflareApiClient.ACCOUNTS_BASE_URL,
                )
                .toList()
            fail("expected BrainError.Unsupported")
        } catch (e: BrainError.Unsupported) {
            assertFalse(transport.opened)
        }
    }

    @Test
    fun `tools are sent in the nested OpenAI shape with tool_choice auto`() = runTest {
        val body = sseText(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}",
            "",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "",
            "data: [DONE]",
            "",
        )
        val transport = FakeTransport(200, body)
        val req = request().copy(
            tools = listOf(
                CloudflareTool(
                    name = "read_file",
                    description = "Read a file",
                    parametersJson = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}",
                ),
            ),
        )
        client(transport)
            .streamResponse(req, "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
            .toList()
        val sentBody = JSONObject(transport.lastBody!!)
        val tools = sentBody.getJSONArray("tools")
        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        val fn = tool.getJSONObject("function")
        assertEquals("read_file", fn.getString("name"))
        assertEquals("Read a file", fn.getString("description"))
        assertEquals("object", fn.getJSONObject("parameters").getString("type"))
        assertEquals("auto", sentBody.getString("tool_choice"))
    }

    @Test
    fun `baseUrl with trailing slash builds a clean chat completions URL`() {
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/acct-1/ai/v1/chat/completions",
            CloudflareApiClient.buildChatCompletionsUrl(
                "https://api.cloudflare.com/client/v4/accounts/",
                "acct-1",
            ),
        )
    }

    // ---------- HTTP 错误映射 ----------

    private fun errorBody(code: Any, message: String): String =
        JSONObject()
            .put("success", false)
            .put(
                "errors",
                org.json.JSONArray().put(
                    JSONObject().put("code", code).put("message", message),
                ),
            )
            .toString()

    private suspend fun expectError(
        status: Int,
        errorBody: String?,
        expected: Class<out BrainError>,
    ): BrainError {
        val transport = FakeTransport(status, errorBody = errorBody)
        try {
            client(transport)
                .streamResponse(request(), "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
                .toList()
        } catch (e: BrainError) {
            assertTrue("expected ${expected.simpleName} but got ${e::class.simpleName}", expected.isInstance(e))
            assertTrue(transport.closed)
            return e
        }
        fail("expected ${expected.simpleName}")
        throw AssertionError("unreachable")
    }

    @Test
    fun `429 with official code 3036 is QuotaExceeded with reset wording`() = runTest {
        val error = expectError(
            429,
            errorBody(3036, "You have used up your daily free allocation of 10,000 neurons."),
            BrainError.QuotaExceeded::class.java,
        )
        assertTrue(error.message!!.contains("00:00 UTC"))
        assertTrue(error.message!!.contains("可能持续更久"))
    }

    @Test
    fun `429 with third-party code 4006 is also QuotaExceeded`() = runTest {
        val error = expectError(
            429,
            errorBody(4006, "AiError: you have used up your daily free allocation of 10,000 neurons"),
            BrainError.QuotaExceeded::class.java,
        )
        assertTrue(error.message!!.contains("10,000 Neurons"))
    }

    @Test
    fun `429 with code 3040 is retryable Transport`() = runTest {
        expectError(429, errorBody(3040, "Capacity exceeded."), BrainError.Transport::class.java)
    }

    @Test
    fun `429 without a quota code is retryable Transport not quota`() = runTest {
        val error = expectError(
            429,
            errorBody(10001, "Rate limited."),
            BrainError.Transport::class.java,
        )
        assertTrue(error.message!!.contains("速率受限"))
        assertFalse(error.message!!.contains("额度已用完"))
    }

    @Test
    fun `401 is AuthFailed`() = runTest {
        val error = expectError(401, errorBody(10000, "Authentication error"), BrainError.AuthFailed::class.java)
        assertTrue(error.message!!.contains("API Token"))
    }

    @Test
    fun `403 with code 5035 is Unsupported naming the paid plan`() = runTest {
        val error = expectError(
            403,
            errorBody(5035, "This model requires a Workers Paid plan."),
            BrainError.Unsupported::class.java,
        )
        assertTrue(error.message!!.contains("Workers Paid"))
        assertTrue(error.message!!.contains("@cf/meta/llama-3.1-8b-instruct-fp8-fast"))
    }

    @Test
    fun `403 without 5035 is AuthFailed about permissions`() = runTest {
        val error = expectError(
            403,
            errorBody(10000, "Authentication error"),
            BrainError.AuthFailed::class.java,
        )
        assertTrue(error.message!!.contains("Workers AI"))
    }

    @Test
    fun `400 is Unsupported`() = runTest {
        expectError(400, errorBody(3007, "Bad input."), BrainError.Unsupported::class.java)
    }

    @Test
    fun `500 is Transport`() = runTest {
        expectError(500, null, BrainError.Transport::class.java)
    }

    @Test
    fun `network failure on open is Transport`() = runTest {
        val transport = FakeTransport(
            200,
            ioExceptionOnOpen = IOException("unreachable"),
        )
        try {
            client(transport)
                .streamResponse(request(), "acct-123", "tok", CloudflareApiClient.ACCOUNTS_BASE_URL)
                .toList()
            fail("expected BrainError.Transport")
        } catch (e: BrainError.Transport) {
            assertTrue(e.message!!.contains("连接 Cloudflare"))
        }
    }

    @Test
    fun `error body parser accepts string codes`() {
        val client = CloudflareApiClient(FakeTransport(200))
        val (code, message) = client.parseErrorBody(
            "{\"success\":false,\"errors\":[{\"code\":\"3036\",\"message\":\"quota\"}]}",
        )
        assertEquals("3036", code)
        assertEquals("quota", message)
    }

    @Test
    fun `error body parser accepts the OpenAI error shape`() {
        val client = CloudflareApiClient(FakeTransport(200))
        val (code, message) = client.parseErrorBody(
            "{\"error\":{\"message\":\"You have used up your daily free allocation of 10,000 neurons.\",\"type\":\"quota_exceeded\",\"code\":3036}}",
        )
        assertEquals("3036", code)
        assertTrue(message!!.contains("daily free allocation"))
    }

    @Test
    fun `429 with an OpenAI-shape quota error is QuotaExceeded`() = runTest {
        val error = expectError(
            429,
            "{\"error\":{\"message\":\"You have used up your daily free allocation of 10,000 neurons.\",\"type\":\"quota_exceeded\",\"code\":3036}}",
            BrainError.QuotaExceeded::class.java,
        )
        assertTrue(error.message!!.contains("00:00 UTC"))
    }

    @Test
    fun `404 is Unsupported naming the model`() = runTest {
        val error = expectError(
            404,
            "{\"error\":{\"message\":\"Model not found.\",\"type\":\"not_found\",\"code\":\"model_not_found\"}}",
            BrainError.Unsupported::class.java,
        )
        assertTrue(error.message!!.contains("模型"))
    }

    @Test
    fun `error body parser returns nulls for empty body`() {
        val client = CloudflareApiClient(FakeTransport(200))
        assertEquals(null to null, client.parseErrorBody(null))
        assertEquals(null to null, client.parseErrorBody(""))
        assertEquals(null to null, client.parseErrorBody("not json"))
    }
}
