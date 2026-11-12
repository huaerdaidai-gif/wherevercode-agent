package wherevercode.agent.brain.cloudflare

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import wherevercode.agent.brain.siwc.BrainCapabilities
import wherevercode.agent.brain.siwc.BrainError
import wherevercode.agent.brain.siwc.BrainMessage
import wherevercode.agent.brain.siwc.BrainProvider
import wherevercode.agent.brain.siwc.BrainRequest
import wherevercode.agent.brain.siwc.BrainResponse
import wherevercode.agent.brain.siwc.BrainRole
import wherevercode.agent.brain.siwc.BrainStreamEvent

/**
 * Cloudflare M2 桥接单测（fake BrainProvider）。
 *
 * 绝不连接真实 api.cloudflare.com，不用真实账户凭据，不做真实推理。
 */
class CloudflareM2BrainAdapterTest {

    private class FakeProvider(
        var events: List<BrainStreamEvent> = emptyList(),
        var configured: Boolean = true,
        var lastRequest: BrainRequest? = null,
    ) : BrainProvider {
        override val providerId: String = "cloudflare-workers-ai"
        override val displayName: String = "Cloudflare Workers AI"
        override val capabilities: BrainCapabilities = BrainCapabilities(
            supportsStreaming = true,
            supportsTools = true,
            supportsSystemMessages = true,
            requiresFullHistory = true,
            supportsTemperature = true,
            supportsMaxOutputTokens = true,
            billsUserPlan = false,
            limitsNote = "test",
        )

        override suspend fun infer(request: BrainRequest): BrainResponse {
            throw UnsupportedOperationException("fake uses streamInfer")
        }

        override fun streamInfer(request: BrainRequest): Flow<BrainStreamEvent> {
            lastRequest = request
            return flowOf(*events.toTypedArray())
        }

        override fun isConfigured(): Boolean = configured

        override suspend fun revoke() {
            configured = false
        }
    }

    private fun bridge(
        provider: FakeProvider = FakeProvider(),
        model: String? = "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
    ) = CloudflareM2BrainAdapter(provider, configuredModel = { model })

    private fun chatRequest() = ChatRequest(
        messages = listOf(
            ChatMessage(role = "system", content = "你是助手。"),
            ChatMessage(role = "user", content = "你好"),
        ),
        model = "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
    )

    @Test
    fun `adapter identity is MODEL_API cloudflare`() {
        val b = bridge()
        assertEquals("cloudflare-workers-ai", b.adapterId)
        assertEquals(BrainKind.MODEL_API, b.kind)
        assertEquals("Cloudflare Workers AI", b.label)
    }

    @Test
    fun `validateCredentials succeeds when configured and fails when not`() = runTest {
        assertTrue(bridge().validateCredentials().isSuccess)
        val result = bridge(FakeProvider(configured = false)).validateCredentials()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Cloudflare"))
    }

    @Test
    fun `listModels returns the configured model only`() = runTest {
        val models = bridge().listModels().getOrThrow()
        assertEquals(1, models.size)
        assertEquals("@cf/meta/llama-3.1-8b-instruct-fp8-fast", models[0].id)
        assertTrue(bridge(model = null).listModels().getOrThrow().isEmpty())
        assertTrue(bridge(model = "").listModels().getOrThrow().isEmpty())
    }

    @Test
    fun `text deltas and usage flow through`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Delta("你好"),
                BrainStreamEvent.Completed(
                    BrainResponse(
                        text = "你好",
                        model = "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
                        usage = wherevercode.agent.brain.siwc.BrainUsage(
                            inputTokens = 10,
                            outputTokens = 2,
                        ),
                    ),
                ),
            ),
        )
        val events = bridge(provider).chatStream(chatRequest()).toList()
        assertEquals(BrainEvent.TextDelta("你好"), events[0])
        assertEquals(BrainEvent.Usage(inputTokens = 10, outputTokens = 2), events[1])
        assertEquals(BrainEvent.Done("stop"), events[2])
    }

    @Test
    fun `system messages become instructions and tool messages become tool-role messages`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Completed(
                    BrainResponse(text = "", model = "m"),
                ),
            ),
        )
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(role = "system", content = "系统指引"),
                ChatMessage(role = "user", content = "做点事"),
                ChatMessage(role = "tool", content = "结果", name = "read_file"),
            ),
            model = "m",
        )
        bridge(provider).chatStream(request).toList()
        val brainRequest = provider.lastRequest!!
        assertEquals("系统指引", brainRequest.instructions)
        assertEquals(2, brainRequest.messages.size)
        assertEquals(BrainRole.USER, brainRequest.messages[0].role)
        // 官方回填范式：tool 结果原样发为 TOOL 角色，不加前缀、不补指引。
        assertEquals(BrainRole.TOOL, brainRequest.messages[1].role)
        assertEquals("结果", brainRequest.messages[1].content)
    }

    @Test
    fun `blank model is passed as null to the provider`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Completed(BrainResponse(text = "", model = "m")),
            ),
        )
        bridge(provider).chatStream(chatRequest().copy(model = "")).toList()
        assertEquals(null, provider.lastRequest!!.model)
    }

    @Test
    fun `temperature and maxTokens pass through to the provider`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Completed(BrainResponse(text = "", model = "m")),
            ),
        )
        bridge(provider)
            .chatStream(chatRequest().copy(temperature = 0.7, maxTokens = 512))
            .toList()
        assertEquals(0.7, provider.lastRequest!!.temperature)
        assertEquals(512, provider.lastRequest!!.maxOutputTokens)
    }

    @Test
    fun `tool calls flow through with parsed args`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.ToolCall(
                    id = "cf-call-1",
                    name = "read_file",
                    argumentsJson = "{\"path\":\"/a/b.txt\"}",
                ),
                BrainStreamEvent.Completed(BrainResponse(text = "", model = "m")),
            ),
        )
        val events = bridge(provider).chatStream(chatRequest()).toList()
        val call = events.filterIsInstance<BrainEvent.ToolCall>().single()
        assertEquals("cf-call-1", call.id)
        assertEquals("read_file", call.name)
        assertEquals("/a/b.txt", call.args["path"])
    }

    @Test
    fun `malformed tool arguments are reported not guessed like DeepSeek`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.ToolCall(
                    id = "call-1",
                    name = "read_file",
                    argumentsJson = "{not-json}",
                ),
                BrainStreamEvent.Completed(BrainResponse(text = "", model = "m")),
            ),
        )
        val events = bridge(provider).chatStream(chatRequest()).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("tool_arguments_invalid", error.code)
        assertFalse(error.retryable)
        assertTrue(events.none { it is BrainEvent.ToolCall })
        // 与 DeepSeek 的同款测试一致：只断言错误唯一且无 ToolCall。
        // 注：FakeProvider 随后还会发 Completed，本桥接（与 DeepSeek
        // 桥接一样）会再发一个 Done("stop")；引擎见首个 Done 即停轮，
        // 双 Done 是两边共有的既有行为，不在本轮 P1-3 改动范围。
    }

    @Test
    fun `empty object arguments are a valid call not a malformed one`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.ToolCall(
                    id = "call-1",
                    name = "list_files",
                    argumentsJson = "{}",
                ),
                BrainStreamEvent.Completed(BrainResponse(text = "", model = "m")),
            ),
        )
        val events = bridge(provider).chatStream(chatRequest()).toList()
        // {} 与非法 JSON 必须区分：{} 正常发调用，非法 JSON 报
        // tool_arguments_invalid（P1-3 / B7）。
        val call = events.filterIsInstance<BrainEvent.ToolCall>().single()
        assertEquals("list_files", call.name)
        assertTrue(call.args.isEmpty())
        assertTrue(events.none { it is BrainEvent.Error })
    }

    @Test
    fun `assistant tool calls and tool result ids pass through to the provider`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Completed(BrainResponse(text = "", model = "m")),
            ),
        )
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(role = "user", content = "列出文件并读 README.txt"),
                ChatMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        wherevercode.agent.brain.ChatToolCall("call-1", "list_files", "{}"),
                        wherevercode.agent.brain.ChatToolCall(
                            "call-2",
                            "read_file",
                            "{\"path\":\"README.txt\"}",
                        ),
                    ),
                ),
                ChatMessage(role = "tool", content = "{\"ok\":true}", toolCallId = "call-1"),
                ChatMessage(role = "tool", content = "{\"ok\":true}", toolCallId = "call-2"),
            ),
            model = "m",
        )
        bridge(provider).chatStream(request).toList()
        val brainRequest = provider.lastRequest!!
        assertEquals(4, brainRequest.messages.size)
        // 一条 assistant 带 2 个调用，不拆成两组。
        val assistant = brainRequest.messages[1]
        assertEquals(BrainRole.ASSISTANT, assistant.role)
        assertEquals(2, assistant.toolCalls.size)
        assertEquals("call-1", assistant.toolCalls[0].id)
        assertEquals("list_files", assistant.toolCalls[0].name)
        assertEquals("call-2", assistant.toolCalls[1].id)
        assertEquals("read_file", assistant.toolCalls[1].name)
        // 两条 tool 结果各带自己的 id，顺序与调用一致。
        assertEquals(BrainRole.TOOL, brainRequest.messages[2].role)
        assertEquals("call-1", brainRequest.messages[2].toolCallId)
        assertEquals(BrainRole.TOOL, brainRequest.messages[3].role)
        assertEquals("call-2", brainRequest.messages[3].toolCallId)
    }

    @Test
    fun `quota exceeded emits the notice first and a non-retryable error`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Failed(
                    BrainError.QuotaExceeded("Cloudflare 今日免费额度已用完（每天 10,000 Neurons）。"),
                ),
            ),
        )
        val events = bridge(provider).chatStream(chatRequest()).toList()
        assertEquals(3, events.size)
        val notice = events[0] as BrainEvent.TextDelta
        assertTrue(notice.text.contains("今日免费额度已用完"))
        assertTrue(notice.text.contains("10,000 Neurons"))
        val error = events[1] as BrainEvent.Error
        assertEquals("quota_exceeded", error.code)
        // P1: 额度耗尽不再 retryable——引擎不再同大脑重试，也不再回落
        // 到已知耗尽的 SIWC 池；明确提示后停止。
        assertFalse(error.retryable)
        assertEquals(BrainEvent.Done("error"), events[2])
    }

    @Test
    fun `auth failure is non-retryable and carries no quota notice`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Failed(BrainError.AuthFailed("bad token")),
            ),
        )
        val events = bridge(provider).chatStream(chatRequest()).toList()
        assertEquals(2, events.size)
        val error = events[0] as BrainEvent.Error
        assertEquals("auth_failed", error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun `unsupported 5035 is non-retryable`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Failed(
                    BrainError.Unsupported("模型需要 Workers Paid 计划"),
                ),
            ),
        )
        val events = bridge(provider).chatStream(chatRequest()).toList()
        val error = events[0] as BrainEvent.Error
        assertEquals("unsupported", error.code)
        assertFalse(error.retryable)
        assertTrue(error.message.contains("Workers Paid"))
    }

    @Test
    fun `tool results pass through as tool-role messages with no prefix and no guidance`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Completed(
                    BrainResponse(text = "", model = "m"),
                ),
            ),
        )
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(role = "system", content = "系统指引"),
                ChatMessage(role = "user", content = "做点事"),
                ChatMessage(role = "tool", content = "结果", name = "read_file"),
            ),
            model = "m",
        )
        bridge(provider).chatStream(request).toList()
        val brainRequest = provider.lastRequest!!
        assertEquals(BrainRole.TOOL, brainRequest.messages[1].role)
        assertEquals("结果", brainRequest.messages[1].content)
        // 旧的 USER 形态回填已废止：不再拼“工具…执行结果：”前缀，
        // 也不再补“不要重复调用同一工具”指引。
        assertFalse(brainRequest.messages.any { it.content.contains("工具执行结果：") })
        assertFalse(brainRequest.messages.any { it.content.contains("不要重复调用同一工具") })
        assertFalse(brainRequest.messages.any { it.content.contains("请基于它继续") })
    }

    @Test
    fun `assistant tool-call echo passes through unchanged`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Completed(
                    BrainResponse(text = "", model = "m"),
                ),
            ),
        )
        val echoJson = "{\"name\":\"list_files\",\"arguments\":{}}"
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(role = "user", content = "列出文件"),
                ChatMessage(role = "assistant", content = echoJson),
                ChatMessage(
                    role = "tool",
                    content = "{\"tool\":\"list_files\",\"ok\":true,\"summary\":\"已列出 （1 项）\",\"data\":\"file README.md\"}",
                    name = "list_files",
                ),
            ),
            model = "m",
        )
        bridge(provider).chatStream(request).toList()
        val brainRequest = provider.lastRequest!!
        assertEquals(3, brainRequest.messages.size)
        assertEquals(BrainRole.USER, brainRequest.messages[0].role)
        // assistant 回显即官方范式的一部分，原样过。
        assertEquals(BrainRole.ASSISTANT, brainRequest.messages[1].role)
        assertEquals(echoJson, brainRequest.messages[1].content)
        assertEquals(BrainRole.TOOL, brainRequest.messages[2].role)
        assertTrue(brainRequest.messages[2].content.contains("\"tool\":\"list_files\""))
    }

    @Test
    fun `explicit model passes through unchanged with no hardcoded fallback`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Completed(BrainResponse(text = "ok", model = "m")),
            ),
        )
        bridge(provider)
            .chatStream(chatRequest().copy(model = "@cf/openai/gpt-oss-120b"))
            .toList()
        assertEquals("@cf/openai/gpt-oss-120b", provider.lastRequest!!.model)
    }

    @Test
    fun `transport errors are retryable`() = runTest {
        val provider = FakeProvider(
            events = listOf(
                BrainStreamEvent.Failed(BrainError.Transport("down")),
            ),
        )
        val events = bridge(provider).chatStream(chatRequest()).toList()
        val error = events[0] as BrainEvent.Error
        assertEquals("transport_error", error.code)
        assertTrue(error.retryable)
    }
}
