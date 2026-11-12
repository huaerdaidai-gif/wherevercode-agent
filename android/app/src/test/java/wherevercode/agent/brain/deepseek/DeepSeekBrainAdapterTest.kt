package wherevercode.agent.brain.deepseek

import java.io.BufferedReader
import java.io.StringReader
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatMessage
import wherevercode.agent.brain.ChatRequest

/**
 * Adapter tests with a fake transport and an in-memory credential store.
 * No network, no real key, no billed call.
 */
class DeepSeekBrainAdapterTest {

    private class FakeCredentialStore(var key: String? = null) : DeepSeekCredentialStore {
        override fun saveApiKey(apiKey: String) { key = apiKey }
        override fun loadApiKey(): String? = key
        override fun clearApiKey() { key = null }
    }

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
        var modelsBody: String = """{"data":[{"id":"deepseek-flash"},{"id":"deepseek-v4-pro"}]}""",
    ) : DeepSeekTransport {
        var lastRequestBody: String? = null
        override fun post(url: String, apiKey: String, requestBody: String): DeepSeekConnection {
            lastRequestBody = requestBody
            return FakeConnection(body, status)
        }
        override fun get(url: String, apiKey: String): DeepSeekConnection =
            FakeConnection(modelsBody, 200)
    }

    private fun adapter(
        store: FakeCredentialStore = FakeCredentialStore("test-key-123456"),
        transport: FakeTransport = FakeTransport(),
        model: String = "deepseek-flash",
    ) = DeepSeekBrainAdapter(
        credentialStore = store,
        baseUrlProvider = { "https://api.deepseek.com" },
        configuredModelProvider = { model },
        apiClient = DeepSeekApiClient(transport),
    )

    private fun request() = ChatRequest(
        model = "deepseek-flash",
        messages = listOf(ChatMessage(role = "user", content = "读 README.txt")),
    )

    @Test
    fun `identity and capabilities are honest`() {
        val a = adapter()
        assertEquals("deepseek", a.adapterId)
        assertEquals(BrainKind.MODEL_API, a.kind)
        assertTrue(a.capabilities().streaming)
        assertTrue(a.capabilities().toolCalling)
    }

    @Test
    fun `chat stream maps text and tool calls with parsed arguments`() = runTest {
        val transport = FakeTransport(
            body = """
                data: {"choices":[{"delta":{"content":"我先看看。"}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"path\":\"README.txt\"}"}}]}}]}
                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
                data: [DONE]
            """.trimIndent(),
        )
        val events = adapter(transport = transport).chatStream(request()).toList()
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text == "我先看看。" })
        val call = events.filterIsInstance<BrainEvent.ToolCall>().single()
        assertEquals("call_1", call.id)
        assertEquals("read_file", call.name)
        assertEquals("README.txt", call.args["path"])
        assertTrue(events.any { it is BrainEvent.Done })
        assertTrue(transport.lastRequestBody!!.contains("\"stream\":true"))
        assertTrue(transport.lastRequestBody!!.contains("\"messages\""))
    }

    @Test
    fun `missing key fails without any HTTP call`() = runTest {
        val transport = FakeTransport()
        val events = adapter(store = FakeCredentialStore(null), transport = transport)
            .chatStream(request())
            .toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("not_configured", error.code)
        assertFalse(error.retryable)
        assertEquals(null, transport.lastRequestBody)
    }

    @Test
    fun `blank configured model fails clearly instead of silently choosing one`() = runTest {
        val events = adapter(model = "").chatStream(request().copy(model = "")).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("not_configured", error.code)
        assertTrue(error.message.contains("模型"))
    }

    @Test
    fun `malformed tool arguments are reported not guessed`() = runTest {
        val transport = FakeTransport(
            body = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read_file","arguments":"{not-json}"}}]}}]}
                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
                data: [DONE]
            """.trimIndent(),
        )
        val events = adapter(transport = transport).chatStream(request()).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("tool_arguments_invalid", error.code)
        assertTrue(events.none { it is BrainEvent.ToolCall })
    }

    @Test
    fun `empty stream is an explicit error event not a silent Done`() = runTest {
        val transport = FakeTransport(body = "data: [DONE]\n")
        val events = adapter(transport = transport).chatStream(request()).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("empty_response", error.code)
        assertTrue(error.retryable)
        assertEquals(BrainEvent.Done("error"), events.last())
    }

    @Test
    fun `listModels returns the live catalogue`() = runTest {
        val models = adapter().listModels().getOrThrow()
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models.map { it.id })
    }

    @Test
    fun `validateCredentials succeeds with a key and fails without one`() = runTest {
        assertTrue(adapter().validateCredentials().isSuccess)
        val noKey = adapter(store = FakeCredentialStore(null)).validateCredentials()
        assertTrue(noKey.isFailure)
        assertTrue(noKey.exceptionOrNull()!!.message!!.contains("API Key"))
    }

    @Test
    fun `revoke clears the stored key`() {
        val store = FakeCredentialStore("test-key-123456")
        val a = adapter(store = store)
        kotlinx.coroutines.runBlocking { a.revoke() }
        assertEquals(null, store.key)
    }
}
