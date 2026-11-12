package wherevercode.agent.brain.deepseek

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
 * DeepSeek API brain (OpenAI-compatible).
 *
 * This is the first-class, configurable MODEL_API brain added on
 * 2026-11-09. It is deliberately a direct [BrainAdapter] rather than a
 * provider + bridge pair: there is exactly one wire protocol
 * (`/chat/completions`), and the engine-facing contract is the only one
 * that needs vendor-specific mapping.
 *
 * Configuration comes from the caller (BrainBootstrap reads it from
 * [wherevercode.agent.brain.BrainConfig]):
 * - baseUrl: user-editable, default `https://api.deepseek.com`;
 * - model: user-editable, populated from the live `/models` list;
 * - API key: only in [DeepSeekCredentialStore] (Keystore).
 *
 * No key is ever hardcoded, and no model is silently substituted when the
 * configured one is blank. The only "suggested" values are UI seeds.
 */
class DeepSeekBrainAdapter(
    private val credentialStore: DeepSeekCredentialStore,
    private val baseUrlProvider: () -> String? = { DeepSeekApiClient.DEFAULT_BASE_URL },
    private val configuredModelProvider: () -> String? = { null },
    private val apiClient: DeepSeekApiClient = DeepSeekApiClient(),
) : BrainAdapter {

    override val adapterId: String = ADAPTER_ID
    override val kind: BrainKind = BrainKind.MODEL_API
    override val label: String = "DeepSeek API"

    override fun capabilities(): BrainCapabilities = BrainCapabilities(
        streaming = true,
        toolCalling = true,
        vision = false,
        maxContextTokens = null,
        notes = "OpenAI 兼容 /chat/completions；工具调用使用标准 tool_calls/tool_call_id 配对。" +
            "模型从账户的 /models 列表选择，不写死；Key 只存本机 Keystore。",
    )

    override suspend fun validateCredentials(): Result<Unit> {
        val apiKey = credentialStore.loadApiKey()
            ?: return Result.failure(IllegalStateException("未配置 DeepSeek API Key。"))
        val baseUrl = baseUrlProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("未配置 DeepSeek 服务地址。"))
        return apiClient.listModels(apiKey, baseUrl).map { Unit }
    }

    override suspend fun listModels(): Result<List<ModelInfo>> {
        val apiKey = credentialStore.loadApiKey()
            ?: return Result.failure(IllegalStateException("未配置 DeepSeek API Key。"))
        val baseUrl = baseUrlProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("未配置 DeepSeek 服务地址。"))
        return apiClient.listModels(apiKey, baseUrl)
    }

    override fun chatStream(request: ChatRequest): Flow<BrainEvent> = flow {
        val apiKey = credentialStore.loadApiKey()
        if (apiKey.isNullOrBlank()) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "未配置 DeepSeek API Key：请在控制台的 DeepSeek 设置里填写你自己的 Key。",
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
                    message = "未配置 DeepSeek 服务地址：请在设置里填写 baseUrl。",
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
                    message = "未选择 DeepSeek 模型：请先刷新模型列表并选择一个。",
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
                    is DeepSeekEvent.Text -> emit(BrainEvent.TextDelta(event.text))
                    is DeepSeekEvent.Reasoning -> emit(BrainEvent.ReasoningDelta(event.text))
                    is DeepSeekEvent.ToolCall -> {
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
                    is DeepSeekEvent.Usage -> emit(
                        BrainEvent.Usage(event.inputTokens, event.outputTokens),
                    )
                    is DeepSeekEvent.Finished -> emit(BrainEvent.Done(event.reason))
                    is DeepSeekEvent.Failed -> {
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
                    message = "DeepSeek 调用失败：${e.message ?: "未知错误"}",
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
        const val ADAPTER_ID: String = "deepseek"
        const val BRAIN_ID: String = "deepseek"
        const val AUTH_REF: String = "deepseek:api-key"
        const val DEFAULT_BASE_URL: String = DeepSeekApiClient.DEFAULT_BASE_URL
    }
}
