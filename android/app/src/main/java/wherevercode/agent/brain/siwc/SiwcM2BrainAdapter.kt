package wherevercode.agent.brain.siwc

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
 * SiwcM2BrainAdapter: the bridge between the SIWC BrainProvider (skeleton
 * design, d170ad9) and the M2 BrainAdapter contract the BrainEngine trusts.
 *
 * This is the FIRST integration point of the M3 integration: the place where
 * the brain-skeleton line and the M2 Android-app line first meet in one
 * module. The two contracts are deliberately NOT merged:
 * - [BrainProvider] stays the SIWC-facing layer (SIWC Preview capabilities,
 *   BrainRequest/BrainResponse, BrainError). It is swappable per provider.
 * - [BrainAdapter] stays the engine-facing layer (ChatRequest/BrainEvent,
 *   BrainKind, registry). The engine never sees a vendor branch.
 *
 * Mapping rules (all explicit, nothing silently dropped):
 * - Role mapping: system messages become [BrainRequest.instructions]
 *   (SIWC Preview has no system role); tool messages become USER-role
 *   messages prefixed with the tool result (Responses API carries tool
 *   results as function_call_output items; the minimal mapping renders
 *   them as text until that lands); user/assistant map 1:1.
 * - temperature / maxTokens: SIWC Preview rejects them. If the engine
 *   asks for them, this adapter emits an Unsupported error, never
 *   silently ignores them.
 * - model: blank means "not chosen yet" ([BrainRequest.model] = null);
 *   the provider resolves it at runtime from the account's model
 *   directory (GET /v1/models), never from a hardcoded default.
 * - Errors: AuthFailed / QuotaExceeded / Unsupported / NotConfigured are
 *   non-retryable (the engine must not fail over on 401/quota); only
 *   Transport is retryable.
 * - Providers must emit [BrainStreamEvent.Failed], not throw. If a
 *   provider throws, the engine treats it as a retryable transport
 *   failure (brain_call_failed); an auth failure thrown as an exception
 *   would be retried by mistake, so the Failed-event contract matters.
 */
class SiwcM2BrainAdapter(
    private val provider: BrainProvider,
) : BrainAdapter {

    override val adapterId: String = ADAPTER_ID
    override val kind: BrainKind = BrainKind.SUBSCRIPTION
    override val label: String = provider.displayName

    override fun capabilities(): BrainCapabilities = BrainCapabilities(
        streaming = provider.capabilities.supportsStreaming,
        // SIWC Preview supports function/custom tools only, in a namespace;
        // no hosted tools. The engine only offers tools the adapter reports.
        toolCalling = provider.capabilities.supportsTools,
        vision = false,
        maxContextTokens = null, // unknown: the provider reports no context limit
        notes = provider.capabilities.limitsNote,
    )

    override suspend fun validateCredentials(): Result<Unit> =
        if (provider.isConfigured()) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException("未登录 ChatGPT：请先使用 ChatGPT 登录，再发送消息。"),
            )
        }

    override suspend fun listModels(): Result<List<ModelInfo>> =
        // The real directory comes from the provider (SIWC: GET /v1/models,
        // visibility == "list"). The catalogue carries slugs only, so the
        // display name is the slug itself. A provider failure propagates
        // as-is; an empty list only means "no models" when the provider
        // itself has no catalogue (its default) — never a faked directory.
        provider.listModels().map { slugs ->
            slugs.map { ModelInfo(id = it, displayName = it) }
        }

    override fun chatStream(request: ChatRequest): Flow<BrainEvent> = flow {
        // SIWC Preview rejects temperature / max_output_tokens. Fail loudly.
        if (request.temperature != null || request.maxTokens != null) {
            emit(
                BrainEvent.Error(
                    code = "unsupported",
                    message = "SIWC Preview 不支持 temperature / max_output_tokens，请去掉这两个参数。",
                    retryable = false,
                ),
            )
            emit(BrainEvent.Done("error"))
            return@flow
        }
        val brainRequest = mapRequest(request)
        provider.streamInfer(brainRequest).collect { event ->
            when (event) {
                is BrainStreamEvent.Delta ->
                    emit(BrainEvent.TextDelta(event.text))
                is BrainStreamEvent.ToolCall ->
                    emit(
                        BrainEvent.ToolCall(
                            id = event.id,
                            name = event.name,
                            args = parseToolArgs(event.argumentsJson),
                        ),
                    )
                is BrainStreamEvent.Completed -> {
                    event.response.usage?.let { usage ->
                        emit(
                            BrainEvent.Usage(
                                inputTokens = usage.inputTokens?.toInt(),
                                outputTokens = usage.outputTokens?.toInt(),
                            ),
                        )
                    }
                    emit(BrainEvent.Done("stop"))
                }
                is BrainStreamEvent.Failed -> {
                    emit(mapError(event.error))
                    emit(BrainEvent.Done("error"))
                }
            }
        }
    }

    private fun mapRequest(request: ChatRequest): BrainRequest {
        val instructions = StringBuilder()
        val messages = mutableListOf<BrainMessage>()
        for (message in request.messages) {
            when (message.role) {
                "system" -> {
                    if (instructions.isNotEmpty()) instructions.append("\n")
                    instructions.append(message.content)
                }
                "assistant" ->
                    messages += BrainMessage(BrainRole.ASSISTANT, message.content)
                "tool" ->
                    // Minimal mapping: render the tool result as text. The
                    // Responses API function_call_output shape lands later.
                    messages += BrainMessage(
                        BrainRole.USER,
                        "工具${message.name ?: ""}执行结果：${message.content}",
                    )
                else ->
                    messages += BrainMessage(BrainRole.USER, message.content)
            }
        }
        return BrainRequest(
            messages = messages,
            instructions = instructions.toString().takeIf { it.isNotBlank() },
            model = request.model.takeIf { it.isNotBlank() },
            tools = request.tools.map { tool ->
                BrainTool(
                    name = tool.name,
                    description = tool.description,
                    parametersJson = JSONObject(tool.inputSchema as Map<*, *>).toString(),
                )
            },
        )
    }

    /**
     * Parse a tool-call arguments JSON string into a Map. The Responses
     * parser already validated the JSON; if it is somehow malformed here,
     * return an empty map and let the engine's ToolRegistry fail the call
     * at structure validation (with a clear reason) rather than inventing
     * arguments or crashing the stream.
     */
    private fun parseToolArgs(argumentsJson: String): Map<String, Any?> {
        if (argumentsJson.isBlank()) return emptyMap()
        return try {
            StreamParser.jsonObjectToMap(JSONObject(argumentsJson))
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun mapError(error: BrainError): BrainEvent.Error = when (error) {
        is BrainError.NotConfigured -> BrainEvent.Error(
            code = "not_configured",
            message = error.message ?: "未登录 ChatGPT。",
            retryable = false,
        )
        is BrainError.AuthFailed -> BrainEvent.Error(
            code = "auth_failed",
            message = error.message ?: "ChatGPT 授权失败，请重新登录。",
            retryable = false,
        )
        is BrainError.QuotaExceeded -> BrainEvent.Error(
            code = "quota_exceeded",
            message = error.message ?: "ChatGPT 套餐额度已耗尽或本次请求不在套餐覆盖内。",
            retryable = false,
        )
        is BrainError.Unsupported -> BrainEvent.Error(
            code = "unsupported",
            message = error.message ?: "该大脑不支持本次请求。",
            retryable = false,
        )
        is BrainError.Transport -> BrainEvent.Error(
            code = "transport_error",
            message = error.message ?: "网络或服务器错误，请稍后重试。",
            retryable = true,
        )
        is BrainError.NotImplemented -> BrainEvent.Error(
            code = "not_implemented",
            message = error.message ?: "该大脑尚未实现。",
            retryable = false,
        )
    }

    companion object {
        /** Stable M2 adapter id and brain id for the SIWC brain. */
        const val ADAPTER_ID: String = "siwc-chatgpt"

        /** BrainConfig.brainId for the SIWC brain. */
        const val BRAIN_ID: String = "siwc-chatgpt"

        /** BrainKeyStore authRef holding the SIWC tokens (see KeystoreSiwcTokenStore). */
        const val AUTH_REF: String = "siwc:tokens"
    }
}
