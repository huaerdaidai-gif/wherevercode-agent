package wherevercode.agent.brain.deepseek

import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader
import java.net.SocketTimeoutException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.ChatMessage
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ChatToolCall
import wherevercode.agent.brain.ToolDef

/**
 * Wire-level tests for the DeepSeek client. All HTTP is a fake transport:
 * no network, no real key, no billed call.
 */
class DeepSeekApiClientTest {

    private class FakeConnection(
        private val body: String,
        override val statusCode: Int,
        private val errorBody: String? = null,
    ) : DeepSeekConnection {
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = errorBody
        override fun close() = Unit
    }

    private class FakeTransport(
        var body: String = "",
        var status: Int = 200,
        var errorBody: String? = null,
        var modelsBody: String = "{}",
        var modelsStatus: Int = 200,
    ) : DeepSeekTransport {
        var lastUrl: String? = null
        var lastKey: String? = null
        var lastRequestBody: String? = null
        var lastGetUrl: String? = null

        override fun post(url: String, apiKey: String, requestBody: String): DeepSeekConnection {
            lastUrl = url
            lastKey = apiKey
            lastRequestBody = requestBody
            return FakeConnection(body, status, errorBody)
        }

        override fun get(url: String, apiKey: String): DeepSeekConnection {
            lastUrl = url
            lastKey = apiKey
            lastGetUrl = url
            return FakeConnection(modelsBody, modelsStatus, errorBody)
        }
    }

    private class TimeoutConnection : DeepSeekConnection {
        override val statusCode: Int = 200
        override fun openBodyReader(): BufferedReader =
            BufferedReader(object : Reader() {
                override fun read(cbuf: CharArray, off: Int, len: Int): Int =
                    throw SocketTimeoutException("read timed out")
                override fun close() = Unit
            })
        override fun readErrorBody(): String? = null
        override fun close() = Unit
    }

    private class TimeoutTransport : DeepSeekTransport {
        override fun post(url: String, apiKey: String, requestBody: String): DeepSeekConnection =
            TimeoutConnection()
        override fun get(url: String, apiKey: String): DeepSeekConnection = TimeoutConnection()
    }

    private fun request() = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(
            ChatMessage(role = "system", content = "你是助手。"),
            ChatMessage(role = "user", content = "列出文件，再读 README.txt"),
            ChatMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(
                    ChatToolCall(id = "call_1", name = "list_files", argumentsJson = "{}"),
                ),
            ),
            ChatMessage(
                role = "tool",
                content = """{"tool":"list_files","ok":true}""",
                toolCallId = "call_1",
                name = "list_files",
            ),
            ChatMessage(role = "tool", content = "legacy unpaired result"),
        ),
        tools = listOf(
            ToolDef(
                name = "read_file",
                description = "读取文件",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "路径"),
                    ),
                    "required" to listOf("path"),
                ),
            ),
        ),
    )

    @Test
    fun `request body uses native assistant tool_calls and matching tool_call_id`() {
        val body = DeepSeekApiClient.buildDeepSeekRequestBody(request())
        assertEquals("deepseek-flash", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(5, messages.length())

        val assistant = messages.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        assertTrue(assistant.isNull("content"))
        val calls = assistant.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        val call = calls.getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("function", call.getString("type"))
        assertEquals("list_files", call.getJSONObject("function").getString("name"))
        assertEquals("{}", call.getJSONObject("function").getString("arguments"))

        val tool = messages.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call_1", tool.getString("tool_call_id"))
        assertTrue(tool.getString("content").contains("list_files"))

        // Legacy history without an id must NOT be sent as an invalid
        // role="tool" message (DeepSeek rejects it); it is downgraded.
        val legacy = messages.getJSONObject(4)
        assertEquals("user", legacy.getString("role"))
        assertTrue(legacy.getString("content").contains("legacy unpaired result"))

        val tools = body.getJSONArray("tools")
        assertEquals("function", tools.getJSONObject(0).getString("type"))
        val function = tools.getJSONObject(0).getJSONObject("function")
        assertEquals("read_file", function.getString("name"))
        assertEquals("object", function.getJSONObject("parameters").getString("type"))
        assertEquals("auto", body.getString("tool_choice"))
    }

    @Test
    fun `URLs are normalized for base, v1 and full endpoint forms`() {
        assertEquals(
            "https://api.deepseek.com/chat/completions",
            DeepSeekApiClient.chatCompletionsUrl("https://api.deepseek.com/"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            DeepSeekApiClient.chatCompletionsUrl("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            DeepSeekApiClient.modelsUrl("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/models",
            DeepSeekApiClient.modelsUrl("https://api.deepseek.com"),
        )
    }

    @Test
    fun `fragmented tool call stream maps to a complete ToolCall event`() = runTest {
        val transport = FakeTransport(
            body = """
                data: {"choices":[{"delta":{"content":"我先列目录。"}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_0","type":"function","function":{"name":"list_files","arguments":""}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"\"}"}}]}}]}
                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
                data: [DONE]
            """.trimIndent(),
        )
        val client = DeepSeekApiClient(transport)
        val events = client.streamChat(request(), "test-key-123456", "https://api.deepseek.com").toList()
        assertTrue(events.contains(DeepSeekEvent.Text("我先列目录。")))
        val call = events.filterIsInstance<DeepSeekEvent.ToolCall>().single()
        assertEquals("call_0", call.id)
        assertEquals("list_files", call.name)
        assertEquals("""{"path":""}""", call.argumentsJson)
        assertEquals("https://api.deepseek.com/chat/completions", transport.lastUrl)
        assertEquals("test-key-123456", transport.lastKey)
        // The key is only in the header; it must never be in the body.
        assertFalse(transport.lastRequestBody!!.contains("test-key-123456"))
    }

    @Test
    fun `HTTP 401 is a non-retryable auth failure without leaking the key`() = runTest {
        val transport = FakeTransport(
            status = 401,
            errorBody = """{"error":{"message":"Authentication Fails","type":"authentication_error"}}""",
        )
        val client = DeepSeekApiClient(transport)
        val events = client.streamChat(request(), "test-key-123456", "https://api.deepseek.com").toList()
        val failure = events.filterIsInstance<DeepSeekEvent.Failed>().single()
        assertEquals("auth_failed", failure.code)
        assertFalse(failure.retryable)
        assertFalse(failure.message.contains("test-key-123456"))
    }

    @Test
    fun `HTTP 429 is retryable and 402 is not`() = runTest {
        val rateLimited = DeepSeekApiClient(
            FakeTransport(status = 429, errorBody = """{"error":{"message":"rate limited"}}"""),
        ).streamChat(request(), "test-key-123456", "https://api.deepseek.com").toList()
            .filterIsInstance<DeepSeekEvent.Failed>().single()
        assertEquals("rate_limited", rateLimited.code)
        assertTrue(rateLimited.retryable)

        val noBalance = DeepSeekApiClient(
            FakeTransport(status = 402, errorBody = """{"error":{"message":"Insufficient Balance"}}"""),
        ).streamChat(request(), "test-key-123456", "https://api.deepseek.com").toList()
            .filterIsInstance<DeepSeekEvent.Failed>().single()
        assertEquals("insufficient_balance", noBalance.code)
        assertFalse(noBalance.retryable)
    }

    @Test
    fun `stream read timeout is a clear retryable transport error`() = runTest {
        val events = DeepSeekApiClient(TimeoutTransport())
            .streamChat(request(), "test-key-123456", "https://api.deepseek.com")
            .toList()
        val failure = events.filterIsInstance<DeepSeekEvent.Failed>().single()
        assertEquals("transport_error", failure.code)
        assertTrue(failure.retryable)
        assertTrue(failure.message.contains("超时"))
    }

    @Test
    fun `listModels reads the account catalogue`() = runTest {
        val transport = FakeTransport(
            modelsBody = """{"object":"list","data":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"}]}""",
        )
        val models = DeepSeekApiClient(transport)
            .listModels("test-key-123456", "https://api.deepseek.com")
            .getOrThrow()
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models.map { it.id })
        assertEquals("https://api.deepseek.com/models", transport.lastGetUrl)
    }

    @Test
    fun `blank key is rejected before any HTTP call`() = runTest {
        val transport = FakeTransport()
        val events = DeepSeekApiClient(transport)
            .streamChat(request(), "", "https://api.deepseek.com")
            .toList()
        val failure = events.filterIsInstance<DeepSeekEvent.Failed>().single()
        assertEquals("not_configured", failure.code)
        assertEquals(null, transport.lastUrl)
    }

    @Test
    fun `request body never carries the api key`() {
        val body = DeepSeekApiClient.buildDeepSeekRequestBody(request()).toString()
        assertFalse(body.contains("test-key-123456"))
        // Sanity: the nested tool schema is what we expect.
        val parsed = JSONObject(body)
        assertTrue(parsed.getJSONArray("messages").length() == 5)
    }
}
