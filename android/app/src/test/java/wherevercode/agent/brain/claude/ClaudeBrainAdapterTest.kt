package wherevercode.agent.brain.claude

import java.io.BufferedReader
import java.io.StringReader
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatMessage
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ChatToolCall
import wherevercode.agent.brain.ToolDef

/**
 * Claude adapter tests with a fake transport and an in-memory credential
 * store. No network, no real key, no billed call. The fake transport
 * returns canned SSE shaped like Anthropic's Messages API stream.
 */
class ClaudeBrainAdapterTest {

    private class FakeCredentialStore(var key: String? = null) : ClaudeCredentialStore {
        override fun saveApiKey(apiKey: String) { key = apiKey }
        override fun loadApiKey(): String? = key
        override fun clearApiKey() { key = null }
    }

    private class FakeConnection(
        private val body: String,
        override val statusCode: Int,
        private val errorBody: String? = null,
    ) : ClaudeConnection {
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = errorBody
        override fun close() = Unit
    }

    private class FakeTransport(
        var body: String = "",
        var status: Int = 200,
        var modelsBody: String = """{"data":[{"id":"claude-sonnet-4-5"},{"id":"claude-opus-4-5"}]}""",
        var errorBody: String? = null,
    ) : ClaudeTransport {
        var lastUrl: String? = null
        var lastApiKey: String? = null
        var lastRequestBody: String? = null
        override fun post(url: String, apiKey: String, requestBody: String): ClaudeConnection {
            lastUrl = url
            lastApiKey = apiKey
            lastRequestBody = requestBody
            return FakeConnection(body, status, errorBody)
        }
        override fun get(url: String, apiKey: String): ClaudeConnection =
            FakeConnection(modelsBody, status, errorBody)
    }

    private fun adapter(
        store: FakeCredentialStore = FakeCredentialStore("sk-ant-test-key"),
        transport: FakeTransport = FakeTransport(),
        model: String = "claude-sonnet-4-5",
    ) = ClaudeBrainAdapter(
        credentialStore = store,
        baseUrlProvider = { "https://api.anthropic.com" },
        configuredModelProvider = { model },
        apiClient = ClaudeApiClient(transport),
    )

    private fun request() = ChatRequest(
        model = "claude-sonnet-4-5",
        messages = listOf(ChatMessage(role = "user", content = "读 README.txt")),
    )

    private val textSse = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-5","stop_reason":null,"usage":{"input_tokens":12,"output_tokens":0}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}

        event: content_block_stop
        data: {"type":"content_block_stop","index":0}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":5}}

        event: message_stop
        data: {"type":"message_stop"}
    """.trimIndent()

    private val toolSse = """
        event: message_start
        data: {"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-5","stop_reason":null,"usage":{"input_tokens":20,"output_tokens":0}}}

        event: content_block_start
        data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"list_files","input":{}}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}

        event: content_block_delta
        data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"src\"}"}}

        event: message_delta
        data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":10}}

        event: message_stop
        data: {"type":"message_stop"}
    """.trimIndent()

    @Test
    fun `identity and capabilities are honest`() {
        val a = adapter()
        assertEquals("model-api-claude", a.adapterId)
        assertEquals(BrainKind.MODEL_API, a.kind)
        assertEquals("Claude API", a.label)
        assertTrue(a.capabilities().streaming)
        assertTrue(a.capabilities().toolCalling)
    }

    @Test
    fun `text stream emits deltas usage and Done end_turn`() = runTest {
        val transport = FakeTransport(body = textSse)
        val events = adapter(transport = transport).chatStream(request()).toList()
        assertEquals(
            listOf(
                BrainEvent.Usage(12, null),
                BrainEvent.TextDelta("你好"),
                BrainEvent.Usage(null, 5),
                BrainEvent.Done("end_turn"),
            ),
            events,
        )
        assertEquals("https://api.anthropic.com/v1/messages", transport.lastUrl)
        assertEquals("sk-ant-test-key", transport.lastApiKey)
    }

    @Test
    fun `tool_use input fragments accumulate into one ToolCall`() = runTest {
        val transport = FakeTransport(body = toolSse)
        val events = adapter(transport = transport).chatStream(request()).toList()
        // message_delta emits its usage BEFORE the terminal finishEvents,
        // so Usage(null, 10) precedes the ToolCall.
        assertEquals(
            listOf(
                BrainEvent.Usage(20, null),
                BrainEvent.Usage(null, 10),
                BrainEvent.ToolCall("toolu_1", "list_files", mapOf("path" to "src")),
                BrainEvent.Done("tool_use"),
            ),
            events,
        )
    }

    @Test
    fun `missing key is not_configured not a fake reply`() = runTest {
        val events = adapter(
            store = FakeCredentialStore(null),
            transport = FakeTransport(body = textSse),
        ).chatStream(request()).toList()
        assertEquals(
            listOf(
                BrainEvent.Error("not_configured", "未配置 Claude API Key：请先在设置里填写你自己的 Key。", false),
                BrainEvent.Done("error"),
            ),
            events,
        )
    }

    @Test
    fun `http 401 maps to auth_failed`() = runTest {
        val transport = FakeTransport(
            body = "",
            status = 401,
            modelsBody = "",
            errorBody = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
        )
        // Drive the error path through listModels (same mapHttpError).
        val result = adapter(transport = transport).listModels()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("401"))
        assertTrue(result.exceptionOrNull()!!.message!!.contains("invalid x-api-key"))
    }

    @Test
    fun `listModels parses v1 models ids`() = runTest {
        val models = adapter().listModels().getOrThrow()
        assertEquals(listOf("claude-sonnet-4-5", "claude-opus-4-5"), models.map { it.id })
    }

    @Test
    fun `stream cut before message_stop is a retryable failure`() = runTest {
        val cut = textSse.substringBefore("event: message_delta")
        val events = adapter(transport = FakeTransport(body = cut)).chatStream(request()).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("stream_ended_without_terminal", error.code)
        assertTrue(error.retryable)
        assertFalse(events.any { it is BrainEvent.Done && it.finishReason == "end_turn" })
    }

    @Test
    fun `request body uses Messages API shape`() {
        val body = ClaudeApiClient.buildClaudeRequestBody(
            ChatRequest(
                model = "claude-sonnet-4-5",
                messages = listOf(
                    ChatMessage(role = "system", content = "你是助手。"),
                    ChatMessage(role = "user", content = "列文件"),
                    ChatMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(
                            ChatToolCall("toolu_9", "list_files", """{"path":"src"}"""),
                        ),
                    ),
                    ChatMessage(role = "tool", content = "a.txt", toolCallId = "toolu_9"),
                    ChatMessage(role = "user", content = "还有吗"),
                ),
                tools = listOf(
                    ToolDef(
                        name = "list_files",
                        description = "列文件",
                        inputSchema = mapOf("type" to "object"),
                    ),
                ),
            ),
        )
        // system is a top-level parameter, not a message.
        assertEquals("你是助手。", body.getString("system"))
        assertEquals("claude-sonnet-4-5", body.getString("model"))
        assertEquals(4096, body.getInt("max_tokens"))
        assertTrue(body.getBoolean("stream"))
        // Tools use the native shape: no `type: function` wrapper.
        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0)
        assertEquals("list_files", tool.getString("name"))
        assertEquals("object", tool.getJSONObject("input_schema").getString("type"))
        assertFalse(tool.has("type"))
        // Messages: user, assistant(tool_use), user(tool_result + next text
        // merged into one user message — strict alternation holds.
        val messages = body.getJSONArray("messages")
        assertEquals(3, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        val assistantBlocks = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("tool_use", assistantBlocks.getJSONObject(0).getString("type"))
        assertEquals("toolu_9", assistantBlocks.getJSONObject(0).getString("id"))
        assertEquals("src", assistantBlocks.getJSONObject(0).getJSONObject("input").getString("path"))
        val userBlocks = messages.getJSONObject(2).getJSONArray("content")
        assertEquals("tool_result", userBlocks.getJSONObject(0).getString("type"))
        assertEquals("toolu_9", userBlocks.getJSONObject(0).getString("tool_use_id"))
        assertEquals("text", userBlocks.getJSONObject(1).getString("type"))
        assertEquals("还有吗", userBlocks.getJSONObject(1).getString("text"))
    }

    @Test
    fun `trailing assistant message is dropped instead of 400ing`() {
        val body = ClaudeApiClient.buildClaudeRequestBody(
            ChatRequest(
                model = "claude-sonnet-4-5",
                messages = listOf(
                    ChatMessage(role = "user", content = "hi"),
                    ChatMessage(
                        role = "assistant",
                        content = "",
                        toolCalls = listOf(
                            ChatToolCall("toolu_1", "list_files", "{}"),
                        ),
                    ),
                ),
            ),
        )
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `parser ignores unknown event types and reports stream errors`() {
        val events = ClaudeStreamParser.parseSseText(
            """
            event: ping
            data: {"type":"ping"}

            event: future_type
            data: {"type":"something_new","foo":1}

            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}
            """.trimIndent(),
        )
        val failed = events.filterIsInstance<ClaudeEvent.Failed>()
        assertEquals(1, failed.size)
        assertEquals("overloaded_error", failed.single().code)
        assertTrue(failed.single().retryable)
    }

    @Test
    fun `revoke clears the stored key`() {
        val store = FakeCredentialStore("sk-ant-test-key")
        val a = adapter(store = store)
        a.revoke()
        assertNull(store.loadApiKey())
    }

    @Test
    fun `empty stream is a failure not an empty reply`() = runTest {
        val events = adapter(transport = FakeTransport(body = "")).chatStream(request()).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("stream_empty", error.code)
    }
}
