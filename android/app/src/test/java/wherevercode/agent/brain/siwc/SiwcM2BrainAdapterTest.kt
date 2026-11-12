package wherevercode.agent.brain.siwc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.ChatMessage
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ToolDef

/**
 * SiwcM2BrainAdapter bridge tests.
 *
 * The fake provider is a TEST DOUBLE: it stands in for the real SIWC
 * adapter, which needs a user sign-in. No test double is ever presented
 * as a real model call. The tests pin the mapping contract between the
 * M2 BrainAdapter layer and the SIWC BrainProvider layer.
 */
class FakeBrainProvider(
    private val script: (BrainRequest) -> Flow<BrainStreamEvent>,
    private val configured: Boolean = true,
    private val modelSlugs: List<String> = emptyList(),
    private val listModelsError: BrainError? = null,
) : BrainProvider {
    override val providerId: String = "siwc-chatgpt"
    override val displayName: String = "ChatGPT (Sign in with ChatGPT)"
    override val capabilities: BrainCapabilities = BrainCapabilities(
        supportsStreaming = true,
        supportsTools = true,
        supportsSystemMessages = false,
        requiresFullHistory = true,
        supportsTemperature = false,
        supportsMaxOutputTokens = false,
        billsUserPlan = true,
        limitsNote = "SIWC Preview (test double)",
    )

    var lastRequest: BrainRequest? = null
        private set

    override suspend fun infer(request: BrainRequest): BrainResponse =
        throw BrainError.NotImplemented("test double: use streamInfer")

    override fun streamInfer(request: BrainRequest): Flow<BrainStreamEvent> {
        lastRequest = request
        return script(request)
    }

    override fun isConfigured(): Boolean = configured

    override suspend fun listModels(): Result<List<String>> =
        listModelsError?.let { Result.failure(it) } ?: Result.success(modelSlugs)

    override suspend fun revoke() {}
}

class SiwcM2BrainAdapterTest {

    private fun adapter(
        configured: Boolean = true,
        script: (BrainRequest) -> Flow<BrainStreamEvent>,
    ) = SiwcM2BrainAdapter(FakeBrainProvider(script, configured))

    @Test
    fun `adapter identity is siwc-chatgpt subscription`() {
        val adapter = adapter { flowOf() }
        assertEquals("siwc-chatgpt", adapter.adapterId)
        assertEquals("siwc-chatgpt", SiwcM2BrainAdapter.ADAPTER_ID)
        assertEquals("siwc-chatgpt", SiwcM2BrainAdapter.BRAIN_ID)
        assertEquals("siwc:tokens", SiwcM2BrainAdapter.AUTH_REF)
        assertEquals(wherevercode.agent.brain.BrainKind.SUBSCRIPTION, adapter.kind)
        assertEquals("ChatGPT (Sign in with ChatGPT)", adapter.label)
    }

    @Test
    fun `capabilities mirror the provider SIWC preview limits`() {
        val caps = adapter { flowOf() }.capabilities()
        assertTrue(caps.streaming)
        assertTrue(caps.toolCalling)
        assertFalse(caps.vision)
        assertNull(caps.maxContextTokens)
        assertTrue(caps.notes.contains("SIWC Preview"))
    }

    @Test
    fun `validateCredentials succeeds only when signed in`() = runTest {
        assertTrue(adapter { flowOf() }.validateCredentials().isSuccess)
        assertTrue(adapter(configured = false) { flowOf() }.validateCredentials().isFailure)
    }

    @Test
    fun `listModels maps the provider directory slugs to ModelInfo`() = runTest {
        val bridge = SiwcM2BrainAdapter(
            FakeBrainProvider(
                script = { flowOf() },
                modelSlugs = listOf("gpt-6-luna", "gpt-6.1-sol"),
            ),
        )
        val models = bridge.listModels().getOrThrow()
        assertEquals(
            listOf(
                wherevercode.agent.brain.ModelInfo(id = "gpt-6-luna", displayName = "gpt-6-luna"),
                wherevercode.agent.brain.ModelInfo(id = "gpt-6.1-sol", displayName = "gpt-6.1-sol"),
            ),
            models,
        )
    }

    @Test
    fun `listModels without a provider directory stays empty and provider failures propagate`() = runTest {
        // A provider with no catalogue (the BrainProvider default) still
        // reports an empty list honestly.
        val empty = adapter { flowOf() }.listModels().getOrThrow()
        assertTrue(empty.isEmpty())
        // A provider failure is returned as-is, never replaced by an
        // empty list or a hardcoded model.
        val failing = SiwcM2BrainAdapter(
            FakeBrainProvider(
                script = { flowOf() },
                listModelsError = BrainError.AuthFailed("token expired"),
            ),
        )
        val result = failing.listModels()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is BrainError.AuthFailed)
    }

    @Test
    fun `system messages become instructions, tool results become user text`() = runTest {
        val fake = FakeBrainProvider({ flowOf(BrainStreamEvent.Completed(BrainResponse("hi", "m"))) })
        val bridge = SiwcM2BrainAdapter(fake)
        val request = ChatRequest(
            messages = listOf(
                ChatMessage("system", "Be brief."),
                ChatMessage("user", "Hello"),
                ChatMessage("tool", "file content", toolCallId = "c1", name = "read_file"),
                ChatMessage("assistant", "Got it."),
            ),
            model = "gpt-6.1-sol",
        )
        bridge.chatStream(request).toList()
        val sent = fake.lastRequest!!
        assertEquals("Be brief.", sent.instructions)
        assertEquals("gpt-6.1-sol", sent.model)
        assertEquals(3, sent.messages.size)
        assertEquals(BrainRole.USER, sent.messages[0].role)
        assertEquals("Hello", sent.messages[0].content)
        assertEquals(BrainRole.USER, sent.messages[1].role)
        assertTrue(sent.messages[1].content.startsWith("工具read_file执行结果："))
        assertTrue(sent.messages[1].content.contains("file content"))
        assertEquals(BrainRole.ASSISTANT, sent.messages[2].role)
    }

    @Test
    fun `blank model is passed as null for the provider to resolve at runtime`() = runTest {
        val fake = FakeBrainProvider({ flowOf(BrainStreamEvent.Completed(BrainResponse("hi", "m"))) })
        SiwcM2BrainAdapter(fake).chatStream(
            ChatRequest(messages = listOf(ChatMessage("user", "hi")), model = ""),
        ).toList()
        assertNull(fake.lastRequest!!.model)
    }

    @Test
    fun `temperature or maxTokens is rejected, provider never called`() = runTest {
        var called = false
        val fake = FakeBrainProvider({ called = true; flowOf() })
        val bridge = SiwcM2BrainAdapter(fake)
        val events = bridge.chatStream(
            ChatRequest(
                messages = listOf(ChatMessage("user", "hi")),
                model = "",
                temperature = 0.7,
            ),
        ).toList()
        assertFalse(called)
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("unsupported", error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun `delta completed usage map to M2 events`() = runTest {
        val bridge = adapter {
            flowOf(
                BrainStreamEvent.Delta("Hello "),
                BrainStreamEvent.Delta("world"),
                BrainStreamEvent.Completed(
                    BrainResponse("Hello world", "gpt-6.1-sol", BrainUsage(10L, 5L), billedToUserPlan = true),
                ),
            )
        }
        val events = bridge.chatStream(
            ChatRequest(messages = listOf(ChatMessage("user", "hi")), model = ""),
        ).toList()
        assertEquals(
            listOf(
                BrainEvent.TextDelta("Hello "),
                BrainEvent.TextDelta("world"),
                BrainEvent.Usage(10, 5),
                BrainEvent.Done("stop"),
            ),
            events,
        )
    }

    @Test
    fun `auth and quota failures are non-retryable`() = runTest {
        val authEvents = adapter {
            flowOf(BrainStreamEvent.Failed(BrainError.AuthFailed("token expired")))
        }.chatStream(ChatRequest(messages = listOf(ChatMessage("user", "hi")), model = "")).toList()
        val authError = authEvents.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("auth_failed", authError.code)
        assertFalse(authError.retryable)

        val quotaEvents = adapter {
            flowOf(BrainStreamEvent.Failed(BrainError.QuotaExceeded("plan exhausted")))
        }.chatStream(ChatRequest(messages = listOf(ChatMessage("user", "hi")), model = "")).toList()
        val quotaError = quotaEvents.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("quota_exceeded", quotaError.code)
        assertFalse(quotaError.retryable)
    }

    @Test
    fun `transport failure is retryable`() = runTest {
        val events = adapter {
            flowOf(BrainStreamEvent.Failed(BrainError.Transport("connection reset")))
        }.chatStream(ChatRequest(messages = listOf(ChatMessage("user", "hi")), model = "")).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("transport_error", error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun `not-configured failure maps to not_configured`() = runTest {
        val events = adapter {
            flowOf(BrainStreamEvent.Failed(BrainError.NotConfigured("sign in first")))
        }.chatStream(ChatRequest(messages = listOf(ChatMessage("user", "hi")), model = "")).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("not_configured", error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun `tool call maps to M2 ToolCall with parsed args`() = runTest {
        val bridge = adapter {
            flowOf(
                BrainStreamEvent.ToolCall(
                    id = "call-1",
                    name = "read_file",
                    argumentsJson = """{"path":"README.md"}""",
                ),
                BrainStreamEvent.Completed(BrainResponse("", "m")),
            )
        }
        val events = bridge.chatStream(
            ChatRequest(messages = listOf(ChatMessage("user", "hi")), model = ""),
        ).toList()
        val call = events.filterIsInstance<BrainEvent.ToolCall>().single()
        assertEquals("call-1", call.id)
        assertEquals("read_file", call.name)
        assertEquals("README.md", call.args["path"])
    }

    @Test
    fun `malformed tool args fall back to empty map`() = runTest {
        val bridge = adapter {
            flowOf(
                BrainStreamEvent.ToolCall("call-2", "read_file", "{not json"),
                BrainStreamEvent.Completed(BrainResponse("", "m")),
            )
        }
        val events = bridge.chatStream(
            ChatRequest(messages = listOf(ChatMessage("user", "hi")), model = ""),
        ).toList()
        val call = events.filterIsInstance<BrainEvent.ToolCall>().single()
        assertTrue(call.args.isEmpty())
    }

    @Test
    fun `tools are passed through as BrainTool JSON`() = runTest {
        val fake = FakeBrainProvider({ flowOf(BrainStreamEvent.Completed(BrainResponse("hi", "m"))) })
        SiwcM2BrainAdapter(fake).chatStream(
            ChatRequest(
                messages = listOf(ChatMessage("user", "hi")),
                model = "",
                tools = listOf(
                    ToolDef(
                        name = "read_file",
                        description = "read",
                        inputSchema = mapOf("type" to "object"),
                    ),
                ),
            ),
        ).toList()
        val tool = fake.lastRequest!!.tools.single()
        assertEquals("read_file", tool.name)
        assertTrue(tool.parametersJson.contains("object"))
    }
}
