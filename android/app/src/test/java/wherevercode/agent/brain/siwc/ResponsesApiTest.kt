package wherevercode.agent.brain.siwc

import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Responses API 客户端与流式解析器单测。
 *
 * 全部用本地 fake HTTP 层与录制的 SSE 文本做输入，绝不连接真实 OpenAI，
 * 不用真实账户凭据（SIWC 资格未确认，本次不做真实 E2E）。
 *
 * 运行（合入 M2 工程树后，文件位于
 * android/app/src/test/java/wherevercode/agent/brain/siwc/）：
 *   ./gradlew :app:testDebugUnitTest --tests "*ResponsesApiTest*"
 * 所需依赖 M2 工程已有：junit 4.13.2、kotlinx-coroutines-test 1.7.3、
 * org.json（testImplementation org.json:json:20240303）。
 */
class ResponsesApiTest {

    // ---------- Fake HTTP 层 ----------

    private class FakeConnection(
        override val statusCode: Int,
        private val body: String,
        private val errorBody: String?,
        private val onClose: () -> Unit,
    ) : ResponsesConnection {
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = errorBody
        override fun close() = onClose()
    }

    private class FakeTransport(
        private val statusCode: Int,
        private val body: String = "",
        private val errorBody: String? = null,
        private val ioExceptionOnOpen: IOException? = null,
    ) : ResponsesTransport {
        var opened = false
        var closed = false
        var lastUrl: String? = null
        var lastToken: String? = null
        var lastBody: String? = null

        override fun open(url: String, accessToken: String, requestBody: String): ResponsesConnection {
            opened = true
            lastUrl = url
            lastToken = accessToken
            lastBody = requestBody
            ioExceptionOnOpen?.let { throw it }
            return FakeConnection(statusCode, body, errorBody) { closed = true }
        }
    }

    private fun request(
        temperature: Double? = null,
        maxOutputTokens: Int? = null,
    ) = ResponsesApiRequest(
        model = "gpt-6.1-sol",
        messages = listOf(
            ResponsesInputMessage("user", "你好"),
            ResponsesInputMessage("assistant", "你好，有什么可以帮你？"),
            ResponsesInputMessage("user", "继续"),
        ),
        instructions = "你是 WhereverCode 的助手。",
        temperature = temperature,
        maxOutputTokens = maxOutputTokens,
    )

    private fun sse(vararg blocks: String): String = blocks.joinToString("") { "$it\n" }

    private fun delta(text: String) =
        "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\"," +
            "\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0," +
            "\"delta\":${JSONObject.quote(text)}}"

    private fun completed(
        inputTokens: Long = 10,
        outputTokens: Long = 5,
    ) = "event: response.completed\ndata: {\"type\":\"response.completed\"," +
        "\"response\":{\"id\":\"resp_abc\",\"status\":\"completed\"," +
        "\"usage\":{\"input_tokens\":$inputTokens,\"output_tokens\":$outputTokens," +
        "\"total_tokens\":${inputTokens + outputTokens}}}}"

    // ---------- 请求体：Preview 限制强制 ----------

    @Test
    fun `request body enforces SIWC preview limits`() {
        val body = buildResponsesRequestBody(request())
        assertEquals("gpt-6.1-sol", body.getString("model"))
        assertFalse("store 必须为 false", body.getBoolean("store"))
        assertTrue("stream 必须为 true", body.getBoolean("stream"))
        assertEquals("你是 WhereverCode 的助手。", body.getString("instructions"))
        assertFalse("不得带 temperature", body.has("temperature"))
        assertFalse("不得带 max_output_tokens", body.has("max_output_tokens"))
        assertFalse("不得带 previous_response_id", body.has("previous_response_id"))
        // input 为数组，且每次带全量历史（3 条）。
        val input = body.getJSONArray("input")
        assertEquals(3, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
        assertEquals("你好", input.getJSONObject(0).getString("content"))
        assertEquals("assistant", input.getJSONObject(1).getString("role"))
        assertEquals("user", input.getJSONObject(2).getString("role"))
    }

    @Test
    fun `request body rejects temperature instead of silently ignoring it`() {
        try {
            buildResponsesRequestBody(request(temperature = 0.7))
            fail("temperature 必须被拒绝")
        } catch (e: ResponsesApiError.Unsupported) {
            assertTrue(e.message!!.contains("temperature"))
        }
    }

    @Test
    fun `request body rejects maxOutputTokens instead of silently ignoring it`() {
        try {
            buildResponsesRequestBody(request(maxOutputTokens = 100))
            fail("max_output_tokens 必须被拒绝")
        } catch (e: ResponsesApiError.Unsupported) {
            assertTrue(e.message!!.contains("max_output_tokens"))
        }
    }

    @Test
    fun `request body rejects explicit system message`() {
        val bad = ResponsesApiRequest(
            model = "gpt-6.1-sol",
            messages = listOf(ResponsesInputMessage("system", "你是助手")),
        )
        try {
            buildResponsesRequestBody(bad)
            fail("system 消息必须被拒绝")
        } catch (e: ResponsesApiError.Unsupported) {
            assertTrue(e.message!!.contains("system"))
        }
    }

    @Test
    fun `request body rejects tool role message - SIWC has no tool role`() {
        val bad = ResponsesApiRequest(
            model = "gpt-6.1-sol",
            messages = listOf(ResponsesInputMessage("tool", "{\"tool\":\"list_files\",\"ok\":true}")),
        )
        try {
            buildResponsesRequestBody(bad)
            fail("tool 角色消息必须被拒绝")
        } catch (e: ResponsesApiError.Unsupported) {
            assertTrue(e.message!!.contains("tool"))
        }
    }

    @Test
    fun `request body encodes function tools in Responses shape`() {
        val withTool = request().copy(
            tools = listOf(
                ResponsesTool(
                    name = "read_file",
                    description = "读文件",
                    parametersJson = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}",
                ),
            ),
        )
        val body = buildResponsesRequestBody(withTool)
        val tool = body.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        assertEquals("read_file", tool.getString("name"))
        assertEquals("object", tool.getJSONObject("parameters").getString("type"))
    }

    // ---------- SSE 解析 ----------

    @Test
    fun `SSE text deltas concatenate and completed carries real usage`() {
        val malformed = mutableListOf<String>()
        val events = ResponsesStreamParser.parseSseText(
            sse(
                "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_abc\"}}",
                "event: response.in_progress\ndata: {\"type\":\"response.in_progress\"}",
                delta("你好"),
                delta("，世界"),
                "event: response.output_text.done\ndata: {\"type\":\"response.output_text.done\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"text\":\"你好，世界\"}",
                completed(),
            ),
            onMalformedLine = { malformed += it },
        )
        assertTrue("已知事件不得走 malformed：$malformed", malformed.isEmpty())
        assertEquals(3, events.size)
        assertEquals(
            ResponsesStreamEvent.TextDelta("你好", "msg_1", 0, 0),
            events[0],
        )
        assertEquals(
            ResponsesStreamEvent.TextDelta("，世界", "msg_1", 0, 0),
            events[1],
        )
        val done = events[2] as ResponsesStreamEvent.Completed
        assertEquals("resp_abc", done.responseId)
        assertEquals(10L, done.usage?.inputTokens)
        assertEquals(5L, done.usage?.outputTokens)
        assertEquals(15L, done.usage?.totalTokens)
        assertEquals("你好，世界", done.fullText)
    }

    @Test
    fun `output_text done without prior deltas emits its real text once`() {
        val events = ResponsesStreamParser.parseSseText(
            sse(
                "event: response.output_text.done\ndata: {\"type\":\"response.output_text.done\",\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"text\":\"完整文本\"}",
                completed(),
            ),
        )
        assertEquals(2, events.size)
        assertEquals(
            ResponsesStreamEvent.TextDelta("完整文本", "msg_1", 0, 0),
            events[0],
        )
        assertTrue(events[1] is ResponsesStreamEvent.Completed)
    }

    @Test
    fun `DONE sentinel after terminal is accepted and adds no event`() {
        val malformed = mutableListOf<String>()
        val events = ResponsesStreamParser.parseSseText(
            sse(delta("hi"), completed(), "data: [DONE]"),
            onMalformedLine = { malformed += it },
        )
        assertTrue(malformed.isEmpty())
        assertEquals(2, events.size)
        assertTrue(events[1] is ResponsesStreamEvent.Completed)
    }

    @Test
    fun `malformed line goes to callback and never fabricates an event`() {
        val malformed = mutableListOf<String>()
        val events = ResponsesStreamParser.parseSseText(
            sse(
                "this is not an SSE line",
                "event: response.output_text.delta\ndata: {not json",
                delta("真文本"),
                completed(),
            ),
            onMalformedLine = { malformed += it },
        )
        assertEquals(2, malformed.size)
        assertEquals(2, events.size)
        assertEquals(
            ResponsesStreamEvent.TextDelta("真文本", "msg_1", 0, 0),
            events[0],
        )
        assertTrue(events[1] is ResponsesStreamEvent.Completed)
    }

    @Test
    fun `unknown event type goes to malformed callback`() {
        val malformed = mutableListOf<String>()
        val events = ResponsesStreamParser.parseSseText(
            sse(
                "event: response.something_new\ndata: {\"type\":\"response.something_new\"}",
                delta("x"),
                completed(),
            ),
            onMalformedLine = { malformed += it },
        )
        assertEquals(1, malformed.size)
        assertEquals(2, events.size)
    }

    @Test
    fun `function call arguments stream concatenates and done carries full arguments`() {
        val events = ResponsesStreamParser.parseSseText(
            sse(
                "event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"read_file\"}}",
                "event: response.function_call_arguments.delta\ndata: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"output_index\":1,\"delta\":\"{\\\"path\\\":\"}",
                "event: response.function_call_arguments.delta\ndata: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"output_index\":1,\"delta\":\"\\\"a.txt\\\"}\"}",
                "event: response.function_call_arguments.done\ndata: {\"type\":\"response.function_call_arguments.done\",\"item_id\":\"fc_1\",\"output_index\":1,\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}",
                completed(),
            ),
        )
        assertEquals(4, events.size)
        assertEquals(
            ResponsesStreamEvent.ToolCallArgumentsDelta("fc_1", 1, "{\"path\":"),
            events[0],
        )
        assertEquals(
            ResponsesStreamEvent.ToolCallArgumentsDelta("fc_1", 1, "\"a.txt\"}"),
            events[1],
        )
        val done = events[2] as ResponsesStreamEvent.ToolCallDone
        assertEquals("fc_1", done.itemId)
        assertEquals("read_file", done.name)
        assertEquals("call_1", done.callId)
        assertEquals("{\"path\":\"a.txt\"}", done.arguments)
        assertTrue(events[3] is ResponsesStreamEvent.Completed)
    }

    @Test
    fun `response failed emits Failed and no Completed`() {
        val events = ResponsesStreamParser.parseSseText(
            sse(
                delta("部分文本"),
                "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}",
            ),
        )
        assertEquals(2, events.size)
        assertTrue(events[0] is ResponsesStreamEvent.TextDelta)
        val failed = events[1] as ResponsesStreamEvent.Failed
        assertEquals("server_error", failed.code)
        assertEquals("boom", failed.message)
        assertTrue("server_error 可重试", failed.retryable)
    }

    @Test
    fun `response incomplete emits non-retryable Failed`() {
        val events = ResponsesStreamParser.parseSseText(
            sse(
                "event: response.incomplete\ndata: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}",
            ),
        )
        assertEquals(1, events.size)
        val failed = events[0] as ResponsesStreamEvent.Failed
        assertEquals("incomplete", failed.code)
        assertTrue(failed.message.contains("max_output_tokens"))
        assertFalse(failed.retryable)
    }

    @Test
    fun `stream level error event emits Failed`() {
        val events = ResponsesStreamParser.parseSseText(
            sse(
                "event: error\ndata: {\"type\":\"error\",\"code\":\"server_error\",\"message\":\"stream broke\"}",
            ),
        )
        assertEquals(1, events.size)
        val failed = events[0] as ResponsesStreamEvent.Failed
        assertEquals("server_error", failed.code)
        assertEquals("stream broke", failed.message)
    }

    // ---------- 客户端：HTTP 错误映射 ----------

    private fun clientFor(
        status: Int,
        body: String = "",
        errorBody: String? = null,
        ioExceptionOnOpen: IOException? = null,
    ): Pair<ResponsesApiClient, FakeTransport> {
        val transport = FakeTransport(status, body, errorBody, ioExceptionOnOpen)
        return ResponsesApiClient(transport = transport) to transport
    }

    @Test
    fun `HTTP 401 maps to AuthFailed and is not retryable`() = runTest {
        val (client, _) = clientFor(
            401,
            errorBody = "{\"error\":{\"message\":\"Incorrect API key\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}",
        )
        try {
            client.streamResponse(request(), "bad-token").toList()
            fail("401 必须抛 AuthFailed")
        } catch (e: ResponsesApiError.AuthFailed) {
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun `HTTP 403 maps to QuotaExceeded for eligibility`() = runTest {
        val (client, _) = clientFor(
            403,
            errorBody = "{\"error\":{\"message\":\"not eligible\",\"code\":\"forbidden\"}}",
        )
        try {
            client.streamResponse(request(), "token").toList()
            fail("403 必须抛 QuotaExceeded")
        } catch (e: ResponsesApiError.QuotaExceeded) {
            assertTrue(e.message!!.contains("403"))
        }
    }

    @Test
    fun `HTTP 429 maps to QuotaExceeded`() = runTest {
        val (client, _) = clientFor(429)
        try {
            client.streamResponse(request(), "token").toList()
            fail("429 必须抛 QuotaExceeded")
        } catch (e: ResponsesApiError.QuotaExceeded) {
            assertTrue(e.message!!.contains("429"))
        }
    }

    @Test
    fun `HTTP 500 and Cloudflare 520 map to retryable Transport`() = runTest {
        for (status in listOf(500, 502, 503, 520)) {
            val (client, _) = clientFor(status)
            try {
                client.streamResponse(request(), "token").toList()
                fail("HTTP $status 必须抛 Transport")
            } catch (e: ResponsesApiError.Transport) {
                assertTrue(e.message!!.contains(status.toString()))
            }
        }
    }

    @Test
    fun `network disconnect on open maps to Transport`() = runTest {
        val (client, _) = clientFor(
            200,
            ioExceptionOnOpen = IOException("Network unreachable"),
        )
        try {
            client.streamResponse(request(), "token").toList()
            fail("网络断开必须抛 Transport")
        } catch (e: ResponsesApiError.Transport) {
            assertTrue(e.message!!.contains("Network unreachable"))
        }
    }

    @Test
    fun `blank token is NotConfigured before any network use`() = runTest {
        val (client, transport) = clientFor(200, sse(delta("x"), completed()))
        try {
            client.streamResponse(request(), "   ").toList()
            fail("空 token 必须抛 NotConfigured")
        } catch (e: ResponsesApiError.NotConfigured) {
            assertFalse("空 token 时绝不能联网", transport.opened)
        }
    }

    // ---------- 客户端：无假回复保证 ----------

    @Test
    fun `no fake reply when stream fails before any delta`() = runTest {
        val (client, _) = clientFor(
            200,
            sse(
                "event: response.created\ndata: {\"type\":\"response.created\"}",
                "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"invalid_prompt\",\"message\":\"bad input\"}}}",
            ),
        )
        val events = client.streamResponse(request(), "token").toList()
        assertTrue("失败前未收到真实 delta，绝不能有 TextDelta", events.none { it is ResponsesStreamEvent.TextDelta })
        assertEquals(1, events.size)
        val failed = events[0] as ResponsesStreamEvent.Failed
        assertEquals("invalid_prompt", failed.code)
        assertFalse(failed.retryable)
    }

    @Test
    fun `partial text is not treated as success when stream fails later`() = runTest {
        val (client, _) = clientFor(
            200,
            sse(
                delta("部分"),
                "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}",
            ),
        )
        val events = client.streamResponse(request(), "token").toList()
        assertEquals(2, events.size)
        assertTrue(events[0] is ResponsesStreamEvent.TextDelta)
        assertTrue(events[1] is ResponsesStreamEvent.Failed)
        assertTrue("失败时绝不能有 Completed", events.none { it is ResponsesStreamEvent.Completed })
    }

    @Test
    fun `stream ending without terminal throws Transport when nothing was emitted`() = runTest {
        val (client, _) = clientFor(
            200,
            sse("event: response.created\ndata: {\"type\":\"response.created\"}"),
        )
        try {
            client.streamResponse(request(), "token").toList()
            fail("无终结事件的断流必须抛 Transport")
        } catch (e: ResponsesApiError.Transport) {
            assertTrue(e.message!!.contains("response.completed"))
        }
    }

    @Test
    fun `stream ending without terminal after deltas ends with Failed event`() = runTest {
        val (client, _) = clientFor(200, sse(delta("部分")))
        val events = client.streamResponse(request(), "token").toList()
        assertEquals(2, events.size)
        assertTrue(events[0] is ResponsesStreamEvent.TextDelta)
        val failed = events[1] as ResponsesStreamEvent.Failed
        assertEquals("stream_ended_without_terminal", failed.code)
        assertTrue(failed.retryable)
    }

    @Test
    fun `client sends Bearer token and official endpoint`() = runTest {
        val (client, transport) = clientFor(200, sse(delta("ok"), completed()))
        client.streamResponse(request(), "oauth-token-123").toList()
        assertEquals(ResponsesApiClient.RESPONSES_URL, transport.lastUrl)
        assertEquals("oauth-token-123", transport.lastToken)
        val sentBody = JSONObject(transport.lastBody!!)
        assertFalse(sentBody.getBoolean("store"))
        assertTrue(sentBody.getBoolean("stream"))
    }

    @Test
    fun `cancelling collection closes the connection`() {
        val (client, transport) = clientFor(
            200,
            sse(delta("a"), delta("b"), delta("c"), completed()),
        )
        runBlocking {
            val first = client.streamResponse(request(), "token").take(1).toList()
            assertEquals(1, first.size)
        }
        assertTrue("取消后必须断开连接", transport.closed)
    }
}
