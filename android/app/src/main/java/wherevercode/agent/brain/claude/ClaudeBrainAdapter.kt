package wherevercode.agent.brain.claude

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import wherevercode.agent.brain.BrainAdapter
import wherevercode.agent.brain.BrainCapabilities
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ModelInfo
import wherevercode.agent.brain.StreamParser

/**
 * Claude API brain (Anthropic Messages API).
 *
 * This is the first-class, configurable MODEL_API brain for Claude, added
 * 2026-11-12 (brain login methods completion). It is deliberately a direct
 * [BrainAdapter] rather than a provider + bridge pair: there is exactly one
 * wire protocol (`/v1/messages`), and the engine-facing contract is the
 * only one that needs vendor-specific mapping.
 *
 * Why a dedicated adapter instead of the generic OpenAI-compatible one
 * (see `GenericOpenAiPresets.kt`): Anthropic has no OpenAI-compatible
 * endpoint. Its API is the Messages API (`/v1/messages`, `x-api-key`
 * header, `anthropic-version`, top-level `system`, content blocks,
 * `tool_use`/`tool_result` blocks, strict role alternation). Feeding an
 * Anthropic key into the generic adapter would build a connection that
 * can never work. The [ClaudeApiClient] speaks the real protocol.
 *
 * Configuration comes from the caller (BrainBootstrap reads it from
 * [wherevercode.agent.brain.BrainConfig]):
 * - baseUrl: user-editable, default `https://api.anthropic.com`;
 * - model: user-editable, populated from the live `/v1/models` list;
 * - API key: only in [ClaudeCredentialStore] (Keystore).
 *
 * No key is ever hardcoded, and no model is silently substituted when the
 * configured one is blank. The only "suggested" values are UI seeds.
 */
class ClaudeBrainAdapter(
    private val credentialStore: ClaudeCredentialStore,
    private val baseUrlProvider: () -> String? = { ClaudeApiClient.DEFAULT_BASE_URL },
    private val configuredModelProvider: () -> String? = { null },
    private val apiClient: ClaudeApiClient = ClaudeApiClient(),
) : BrainAdapter {

    override val adapterId: String = ADAPTER_ID
    override val kind: BrainKind = BrainKind.MODEL_API
    override val label: String = "Claude API"

    override fun capabilities(): BrainCapabilities = BrainCapabilities(
        streaming = true,
        toolCalling = true,
        vision = false,
        maxContextTokens = null,
        notes = "Anthropic Messages API /v1/messages；工具调用使用原生 tool_use/tool_result 内容块。" +
            "模型从账户的 /v1/models 列表选择，不写死；Key 只存本机 Keystore。",
    )

    override suspend fun validateCredentials(): Result<Unit> {
        val apiKey = credentialStore.loadApiKey()
            ?: return Result.failure(IllegalStateException("未配置 Claude API Key。"))
        val baseUrl = baseUrlProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("未配置 Claude 服务地址。"))
        return apiClient.listModels(apiKey, baseUrl).map { Unit }
    }

    override suspend fun listModels(): Result<List<ModelInfo>> {
        val apiKey = credentialStore.loadApiKey()
            ?: return Result.failure(IllegalStateException("未配置 Claude API Key。"))
        val baseUrl = baseUrlProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("未配置 Claude 服务地址。"))
        return apiClient.listModels(apiKey, baseUrl)
    }

    override fun chatStream(request: ChatRequest): Flow<BrainEvent> = flow {
        val apiKey = credentialStore.loadApiKey()
        if (apiKey.isNullOrBlank()) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "未配置 Claude API Key：请先在设置里填写你自己的 Key。",
                    retryable = false,
                ),
            )
            emit(BrainEvent.Done("error"))
            return@flow
        }
        val baseUrl = baseUrlProvider()?.takeIf { it.isNotBlank() }
        if (baseUrl == null) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "未配置 Claude 服务地址：请在设置里填写 baseUrl。",
                    retryable = false,
                ),
            )
            emit(BrainEvent.Done("error"))
            return@flow
        }
        val model = request.model.takeIf { it.isNotBlank() }
            ?: configuredModelProvider()?.takeIf { it.isNotBlank() }
        if (model == null) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "未选择 Claude 模型：请先刷新模型列表并选择一个。",
                    retryable = false,
                ),
            )
            emit(BrainEvent.Done("error"))
            return@flow
        }
        val effectiveRequest = request.copy(model = model)
        try {
            apiClient.streamChat(effectiveRequest, apiKey, baseUrl).collect { event ->
                when (event) {
                    is ClaudeEvent.Text -> emit(BrainEvent.TextDelta(event.text))
                    is ClaudeEvent.ToolCall -> {
                        val args = parseToolArgs(event.argumentsJson)
                        if (args == null) {
                            emit(
                                BrainEvent.Error(
                                    code = "tool_arguments_invalid",
                                    message = "模型返回的工具参数不是合法 JSON（${event.name}），已停止本轮。",
                                    retryable = false,
                                ),
                            )
                            emit(BrainEvent.Done("error"))
                        } else {
                            emit(BrainEvent.ToolCall(event.id, event.name, args))
                        }
                    }
                    is ClaudeEvent.Usage -> emit(
                        BrainEvent.Usage(event.inputTokens, event.outputTokens),
                    )
                    is ClaudeEvent.Finished -> emit(BrainEvent.Done(event.reason))
                    is ClaudeEvent.Failed -> {
                        emit(
                            BrainEvent.Error(
                                code = event.code,
                                message = event.message,
                                retryable = event.retryable,
                            ),
                        )
                        emit(BrainEvent.Done("error"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(
                BrainEvent.Error(
                    code = "brain_call_failed",
                    message = "Claude 调用失败：${e.message ?: "未知错误"}",
                    retryable = true,
                ),
            )
            emit(BrainEvent.Done("error"))
        }
    }

    /** Clear the locally stored API key (the user can also delete it server-side). */
    fun revoke() {
        credentialStore.clearApiKey()
    }

    /** null = malformed JSON; an empty object is valid. */
    private fun parseToolArgs(argumentsJson: String): Map<String, Any?>? {
        val raw = argumentsJson.trim()
        if (raw.isEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            StreamParser.jsonObjectToMap(obj)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ADAPTER_ID: String = "model-api-claude"
        const val BRAIN_ID: String = "claude"
        const val AUTH_REF: String = "claude:api-key"
        const val DEFAULT_BASE_URL: String = ClaudeApiClient.DEFAULT_BASE_URL
        const val SUGGESTED_MODEL: String = "claude-sonnet-4-5"
    }
}
