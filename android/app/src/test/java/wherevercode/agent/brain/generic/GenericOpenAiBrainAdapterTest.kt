package wherevercode.agent.brain.generic

import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigStore
import wherevercode.agent.brain.BrainConfigValidator
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatMessage
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ToolDef

/**
 * Generic OpenAI-compatible brain adapter tests. All fake: the scripted
 * transport stands in for a real provider, so no test ever touches the
 * network, spends money, or uses a real key (the fake key is the
 * literal string "test-key-generic-1"). The adapter id, the tool-call
 * round trip (stage 2), the /models probe behaviour and the baseUrl
 * rules are pinned here.
 */
class GenericOpenAiBrainAdapterTest {

    private class FakeCredentialStore(
        private val keys: MutableMap<String, String> = mutableMapOf(),
    ) : GenericOpenAiCredentialStore {
        override fun saveApiKey(authRef: String, apiKey: String) { keys[authRef] = apiKey }
        override fun loadApiKey(authRef: String): String? = keys[authRef]
        override fun clearApiKey(authRef: String) { keys.remove(authRef) }
    }

    private class FakeConfigStore(
        private val configs: MutableMap<String, BrainConfig> = mutableMapOf(),
        var currentId: String? = null,
    ) : BrainConfigStore {
        override fun listConfigs(): List<BrainConfig> = configs.values.toList()
        override fun getConfig(brainId: String): BrainConfig? = configs[brainId]
        override fun saveConfig(config: BrainConfig) { configs[config.brainId] = config }
        override fun deleteConfig(brainId: String) { configs.remove(brainId) }
        override fun currentBrainId(): String? = currentId
        override fun setCurrentBrainId(brainId: String?) { currentId = brainId }
    }

    private class FakeConnection(
        private val body: String,
        override val statusCode: Int,
        private val errorBody: String? = null,
    ) : GenericOpenAiConnection {
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = errorBody
        override fun close() = Unit
    }

    private class FakeTransport(
        var postBody: String = "",
        var postStatus: Int = 200,
        var postErrorBody: String? = null,
        var modelsBody: String = """{"data":[{"id":"openai/gpt-4.1"},{"id":"anthropic/claude-haiku-5.5"}]}""",
        var getStatus: Int = 200,
        var getErrorBody: String? = null,
        var getError: IOException? = null,
    ) : GenericOpenAiTransport {
        var postCalls = 0
            private set
        var getCalls = 0
            private set
        var lastPostUrl: String? = null
            private set
        var lastPostApiKey: String? = null
            private set
        var lastPostBody: String? = null
            private set
        var lastGetUrl: String? = null
            private set

        override fun post(url: String, apiKey: String, requestBody: String): GenericOpenAiConnection {
            postCalls += 1
            lastPostUrl = url
            lastPostApiKey = apiKey
            lastPostBody = requestBody
            return FakeConnection(postBody, postStatus, postErrorBody)
        }

        override fun get(url: String, apiKey: String): GenericOpenAiConnection {
            getCalls += 1
            lastGetUrl = url
            getError?.let { throw it }
            return FakeConnection(modelsBody, getStatus, getErrorBody)
        }
    }

    private fun genericConfig(
        brainId: String = "model-api-openrouter",
        baseUrl: String = "https://openrouter.ai/api/v1",
        model: String = "openai/gpt-4.1",
    ) = BrainConfig(
        brainId = brainId,
        kind = BrainKind.MODEL_API,
        adapterId = "model-api-generic",
        label = "OpenRouter",
        baseUrl = baseUrl,
        model = model,
        authRef = "model-api:$brainId",
        fallbackBrainIds = emptyList(),
    )

    private fun setup(
        config: BrainConfig? = genericConfig(),
        key: String? = "test-key-generic-1",
        currentId: String? = "model-api-openrouter",
        transport: FakeTransport = FakeTransport(),
    ): Triple<GenericOpenAiBrainAdapter, FakeConfigStore, FakeTransport> {
        val store = FakeConfigStore(
            configs = if (config == null) mutableMapOf() else mutableMapOf(config.brainId to config),
            currentId = currentId,
        )
        val credentials = FakeCredentialStore()
        if (config != null && key != null) credentials.saveApiKey(config.authRef!!, key)
        val adapter = GenericOpenAiBrainAdapter(
            configStore = store,
            credentialStore = credentials,
            apiClient = GenericOpenAiApiClient(transport),
        )
        return Triple(adapter, store, transport)
    }

    private fun request(
        model: String = "openai/gpt-4.1",
        tools: List<ToolDef> = emptyList(),
    ) = ChatRequest(
        messages = listOf(ChatMessage(role = "user", content = "hi")),
        model = model,
        tools = tools,
    )

    private fun textStream(text: String = "你好") = """
        data: {"choices":[{"delta":{"content":"$text"}}]}
        data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
        data: [DONE]
    """.trimIndent()

    @Test
    fun `identity is model-api-generic MODEL_API with streaming on and tools on`() {
        val (adapter, _, _) = setup()
        assertEquals("model-api-generic", adapter.adapterId)
        assertEquals(BrainKind.MODEL_API, adapter.kind)
        assertEquals("通用模型 API", adapter.label)
        assertTrue(adapter.capabilities().streaming)
        assertTrue("toolCalling must be true from stage 2", adapter.capabilities().toolCalling)
        // Claimed, not verified: toolCallingVerified stays null until a
        // real-device (K70) run proves the round trip.
        assertNull(adapter.capabilities().toolCallingVerified)
    }

    @Test
    fun `chat stream posts to baseUrl chat completions and maps text then Done`() = runTest {
        // forBrain proves per-brain resolution: the current brain is a
        // different one, yet this call still reaches the OpenRouter config.
        val (adapter, _, transport) = setup(
            currentId = "deepseek",
            transport = FakeTransport(postBody = textStream()),
        )
        val events = adapter.forBrain("model-api-openrouter").chatStream(request()).toList()
        assertEquals(
            listOf(BrainEvent.TextDelta("你好"), BrainEvent.Done("stop")),
            events,
        )
        assertEquals("https://openrouter.ai/api/v1/chat/completions", transport.lastPostUrl)
        assertEquals("test-key-generic-1", transport.lastPostApiKey)
        assertTrue(transport.lastPostBody!!.contains("\"stream\":true"))
        assertTrue(transport.lastPostBody!!.contains("\"model\":\"openai/gpt-4.1\""))
    }

    @Test
    fun `chat stream uses the configured model when the request model is blank`() = runTest {
        val (adapter, _, transport) = setup(transport = FakeTransport(postBody = textStream()))
        val events = adapter.chatStream(request(model = "")).toList()
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text == "你好" })
        assertTrue(transport.lastPostBody!!.contains("\"model\":\"openai/gpt-4.1\""))
    }

    @Test
    fun `chat stream emits a mid-stream tool call for the engine to execute`() = runTest {
        val body = """
            data: {"choices":[{"delta":{"content":"我先看看。"}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read_file","arguments":"{\"path\":"}}]}}]}
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"README.txt\"}"}}]}}]}
            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
            data: [DONE]
        """.trimIndent()
        val (adapter, _, _) = setup(transport = FakeTransport(postBody = body))
        val events = adapter.chatStream(request()).toList()
        assertEquals(3, events.size)
        assertEquals(BrainEvent.TextDelta("我先看看。"), events[0])
        val call = events[1] as BrainEvent.ToolCall
        assertEquals("call_1", call.id)
        assertEquals("read_file", call.name)
        assertEquals(mapOf("path" to "README.txt"), call.args)
        assertEquals(BrainEvent.Done("tool_calls"), events[2])
    }

    @Test
    fun `chat stream fails the turn on malformed tool call arguments`() = runTest {
        val body = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_9","function":{"name":"read_file","arguments":"{not json"}}]}}]}
            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
            data: [DONE]
        """.trimIndent()
        val (adapter, _, _) = setup(transport = FakeTransport(postBody = body))
        val events = adapter.chatStream(request()).toList()
        assertEquals(2, events.size)
        val error = events[0] as BrainEvent.Error
        assertEquals("tool_arguments_invalid", error.code)
        assertFalse(error.retryable)
        assertEquals(BrainEvent.Done("error"), events[1])
        assertTrue(events.none { it is BrainEvent.ToolCall })
    }

    @Test
    fun `chat stream sends tool definitions when the request carries tools`() = runTest {
        val (adapter, _, transport) = setup(transport = FakeTransport(postBody = textStream()))
        val events = adapter.chatStream(
            request(tools = listOf(ToolDef("read_file", "read a file"))),
        ).toList()
        assertEquals(1, transport.postCalls)
        val body = transport.lastPostBody!!
        assertTrue(body.contains("\"tools\""))
        assertTrue(body.contains("\"tool_choice\":\"auto\""))
        assertTrue(body.contains("\"name\":\"read_file\""))
        assertEquals(
            listOf(BrainEvent.TextDelta("你好"), BrainEvent.Done("stop")),
            events,
        )
    }

    @Test
    fun `chat stream without a stored key fails not_configured without an HTTP call`() = runTest {
        val (adapter, _, transport) = setup(key = null, transport = FakeTransport(postBody = textStream()))
        val events = adapter.chatStream(request()).toList()
        assertEquals(0, transport.postCalls)
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("not_configured", error.code)
        assertFalse(error.retryable)
        assertEquals(BrainEvent.Done("error"), events.last())
    }

    @Test
    fun `chat stream without a model fails not_configured instead of silently choosing one`() = runTest {
        val (adapter, _, transport) = setup(
            config = genericConfig(model = ""),
            transport = FakeTransport(postBody = textStream()),
        )
        val events = adapter.chatStream(request(model = "")).toList()
        assertEquals(0, transport.postCalls)
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("not_configured", error.code)
        assertTrue(error.message.contains("模型"))
    }

    @Test
    fun `chat stream without a baseUrl fails not_configured`() = runTest {
        val (adapter, _, transport) = setup(
            config = genericConfig(baseUrl = ""),
            transport = FakeTransport(postBody = textStream()),
        )
        val events = adapter.chatStream(request()).toList()
        assertEquals(0, transport.postCalls)
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("not_configured", error.code)
        assertTrue(error.message.contains("服务地址"))
    }

    @Test
    fun `chat stream refuses a LAN http baseUrl without an HTTP call`() = runTest {
        val (adapter, _, transport) = setup(
            config = genericConfig(baseUrl = "http://192.168.1.5:11434/v1"),
            transport = FakeTransport(postBody = textStream()),
        )
        val events = adapter.chatStream(request()).toList()
        assertEquals(0, transport.postCalls)
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("not_configured", error.code)
        assertTrue(error.message.contains("https"))
    }

    @Test
    fun `listModels returns the live catalogue from GET models`() = runTest {
        val (adapter, _, transport) = setup()
        val models = adapter.listModels().getOrThrow()
        assertEquals(listOf("openai/gpt-4.1", "anthropic/claude-haiku-5.5"), models.map { it.id })
        assertEquals("https://openrouter.ai/api/v1/models", transport.lastGetUrl)
        assertEquals(1, transport.getCalls)
    }

    @Test
    fun `listModels empty catalogue is an honest empty success`() = runTest {
        val (adapter, _, _) = setup(
            transport = FakeTransport(modelsBody = """{"data":[]}"""),
        )
        val models = adapter.listModels().getOrThrow()
        assertTrue(models.isEmpty())
    }

    @Test
    fun `listModels 401 fails without falling back to a hardcoded model`() = runTest {
        val (adapter, _, _) = setup(
            transport = FakeTransport(
                getStatus = 401,
                getErrorBody = """{"error":{"message":"invalid key","code":"invalid_api_key"}}""",
            ),
        )
        val result = adapter.listModels()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("401"))
    }

    @Test
    fun `listModels network failure fails`() = runTest {
        val (adapter, _, _) = setup(
            transport = FakeTransport(getError = IOException("connection reset")),
        )
        val result = adapter.listModels()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("连接模型 API 失败"))
    }

    @Test
    fun `validateCredentials succeeds with a key and fails without one or on 401`() = runTest {
        val (adapter, _, _) = setup()
        assertTrue(adapter.validateCredentials().isSuccess)
        val (noKey, _, _) = setup(key = null)
        val noKeyResult = noKey.validateCredentials()
        assertTrue(noKeyResult.isFailure)
        assertTrue(noKeyResult.exceptionOrNull()!!.message!!.contains("API Key"))
        val (badKey, _, _) = setup(
            transport = FakeTransport(
                getStatus = 401,
                getErrorBody = """{"error":{"message":"invalid key"}}""",
            ),
        )
        assertTrue(badKey.validateCredentials().isFailure)
    }

    @Test
    fun `baseUrl validation allows https and local http but rejects LAN http`() {
        fun errorsFor(baseUrl: String) = BrainConfigValidator.validate(genericConfig(baseUrl = baseUrl))
        assertTrue(errorsFor("https://openrouter.ai/api/v1").isEmpty())
        assertTrue(errorsFor("http://127.0.0.1:11434/v1").isEmpty())
        assertTrue(errorsFor("http://localhost:1234/v1").isEmpty())
        val lan = errorsFor("http://192.168.1.5:11434/v1")
        assertTrue(lan.isNotEmpty())
        assertTrue(lan.any { it.contains("服务地址") })
    }

    @Test
    fun `chat stream without a key reaches a local model instead of failing not_configured`() = runTest {
        val (adapter, _, transport) = setup(
            config = genericConfig(baseUrl = "http://127.0.0.1:11434/v1", model = "llama3"),
            key = null,
            transport = FakeTransport(postBody = textStream()),
        )
        val events = adapter.chatStream(request(model = "llama3")).toList()
        assertEquals(
            listOf(BrainEvent.TextDelta("你好"), BrainEvent.Done("stop")),
            events,
        )
        assertEquals(1, transport.postCalls)
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", transport.lastPostUrl)
        assertEquals("", transport.lastPostApiKey)
    }

    @Test
    fun `listModels without a key reaches a local model`() = runTest {
        val (adapter, _, transport) = setup(
            config = genericConfig(baseUrl = "http://localhost:1234/v1", model = "local"),
            key = null,
        )
        val models = adapter.listModels().getOrThrow()
        assertEquals(listOf("openai/gpt-4.1", "anthropic/claude-haiku-5.5"), models.map { it.id })
        assertEquals(1, transport.getCalls)
        assertEquals("http://localhost:1234/v1/models", transport.lastGetUrl)
    }

    @Test
    fun `validateCredentials without a key succeeds for a local model`() = runTest {
        val (adapter, _, _) = setup(
            config = genericConfig(baseUrl = "http://127.0.0.1:11434/v1", model = "llama3"),
            key = null,
        )
        assertTrue(adapter.validateCredentials().isSuccess)
    }

    @Test
    fun `presets cover the verified OpenAI-compatible providers with baseUrl only and no Anthropic`() {
        val presets = GenericOpenAiPresets.PRESETS
        assertEquals(
            listOf(
                "https://api.openai.com/v1",
                "https://openrouter.ai/api/v1",
                "https://api.deepseek.com",
                "https://api.meta.ai/v1",
                "https://api.x.ai/v1",
                "https://generativelanguage.googleapis.com/v1beta/openai/",
                "http://localhost:11434/v1",
                "http://localhost:1234/v1",
            ),
            presets.map { it.baseUrl },
        )
        assertTrue(presets.none { it.displayName.contains("Anthropic") })
        assertTrue(presets.none { it.baseUrl.contains("anthropic") })
        assertTrue(presets.all { BrainConfigValidator.isAllowedBaseUrl(it.baseUrl) })
        assertEquals(
            "Google Gemini",
            GenericOpenAiPresets.presetForBaseUrl(
                "https://generativelanguage.googleapis.com/v1beta/openai",
            )?.displayName,
        )
        assertEquals(
            "OpenRouter",
            GenericOpenAiPresets.presetForBaseUrl("https://openrouter.ai/api/v1/")?.displayName,
        )
    }
}
