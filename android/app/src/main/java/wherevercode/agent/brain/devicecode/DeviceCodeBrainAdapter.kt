package wherevercode.agent.brain.devicecode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import wherevercode.agent.brain.BrainAdapter
import wherevercode.agent.brain.BrainCapabilities
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigStore
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ModelInfo
import wherevercode.agent.brain.generic.GenericOpenAiApiClient
import wherevercode.agent.brain.generic.GenericOpenAiEvent
import wherevercode.agent.brain.siwc.BrainError

/**
 * Membership brain adapter for one device-code vendor (2026-11-12).
 *
 * Inference reuses the generic OpenAI-compatible client
 * ([GenericOpenAiApiClient]): Qwen's inference endpoint is OpenAI
 * compatible (`portal.qwen.ai/v1/chat/completions`) and Kimi's
 * coding endpoint is OpenAI compatible (`api.kimi.com/coding/v1`);
 * the only difference from an API-Key brain is where the Bearer
 * token comes from — the vendor's device-code token store, refreshed
 * before use, never a user-typed key. No second copy of the
 * chat-completions protocol is written here.
 *
 * Honesty notes (do not soften):
 * - Kimi's inference path is `/chat/completions` on the coding
 *   endpoint; the vendor docs describe the endpoint as Responses
 *   API as well. It must be walked through on a real device with
 *   maintainer's own account before it counts as delivered.
 * - Grok's inference endpoint (`api.x.ai/v1`) is the third-party
 *   route, not the official CLI path (`cli-chat-proxy.grok.com`);
 *   a 403 there is the documented allowlist risk, not a bug in
 *   this adapter — the adapter reports it verbatim.
 * - Tool calling is OFF for membership brains in this first cut
 *   (capabilities.toolCalling = false): the engine will not send
 *   tools, and a model-emitted tool call is refused honestly rather
 *   than executed with a membership token.
 */
class DeviceCodeBrainAdapter(
    val config: DeviceCodeConfig,
    private val manager: DeviceCodeManager,
    private val configStore: BrainConfigStore,
    private val apiClient: GenericOpenAiApiClient = GenericOpenAiApiClient(),
    private val brainIdProvider: () -> String? = { configStore.currentBrainId() },
) : BrainAdapter {

    override val adapterId: String = config.adapterId
    override val kind: BrainKind = BrainKind.SUBSCRIPTION
    override val label: String = config.displayName

    override fun capabilities(): BrainCapabilities = BrainCapabilities(
        streaming = true,
        toolCalling = false,
        vision = false,
        maxContextTokens = null,
        notes = "${config.displayName}会员 token 推理（${config.inferenceBaseUrl}）；" +
            "token 只存本机 Keystore，过期自动用 refresh_token 续。" +
            (if (config.experimental) " ${config.experimentalNote}" else "") +
            (if (config.complianceNote.isNotBlank()) " ${config.complianceNote}" else ""),
    )

    /** A view of this adapter bound to one explicit brain id. */
    fun forBrain(brainId: String): DeviceCodeBrainAdapter =
        DeviceCodeBrainAdapter(config, manager, configStore, apiClient) { brainId }

    override suspend fun validateCredentials(): Result<Unit> {
        val resolved = resolve() ?: return Result.failure(
            IllegalStateException("未配置${config.displayName}大脑。"),
        )
        val token = try {
            manager.ensureFreshAccessToken()
        } catch (e: BrainError.AuthFailed) {
            return Result.failure(IllegalStateException(e.message))
        }
        return apiClient.listModels(token, resolved.baseUrl).map { Unit }
    }

    override suspend fun listModels(): Result<List<ModelInfo>> {
        val resolved = resolve() ?: return Result.failure(
            IllegalStateException("未配置${config.displayName}大脑。"),
        )
        val token = try {
            manager.ensureFreshAccessToken()
        } catch (e: BrainError.AuthFailed) {
            return Result.failure(IllegalStateException(e.message))
        }
        return apiClient.listModels(token, resolved.baseUrl)
    }

    override fun chatStream(request: ChatRequest): Flow<wherevercode.agent.brain.BrainEvent> = flow {
        val resolved = resolve()
        if (resolved == null) {
            emitNotConfigured("未配置${config.displayName}大脑：请先在设置里登录。")
            return@flow
        }
        val model = request.model.takeIf { it.isNotBlank() }
            ?: resolved.config.model.takeIf { it.isNotBlank() }
        if (model == null) {
            emitNotConfigured(
                "未选择模型：请先在设置里“${config.displayName}”行的“···”→“编辑模型…”里填模型名（以厂商模型目录为准）。",
            )
            return@flow
        }
        val token = try {
            manager.ensureFreshAccessToken()
        } catch (e: BrainError.AuthFailed) {
            emit(
                wherevercode.agent.brain.BrainEvent.Error(
                    code = "auth_failed",
                    message = e.message ?: "${config.displayName} 未登录。",
                    retryable = false,
                ),
            )
            emit(wherevercode.agent.brain.BrainEvent.Done("error"))
            return@flow
        }
        // One refresh-and-retry on 401: the token may have died
        // between ensureFreshAccessToken and the call.
        var refreshedOnce = false
        var currentToken = token
        while (true) {
            var sawAuthFailed = false
            var failed = false
            try {
                apiClient.streamChat(request.copy(model = model), currentToken, resolved.baseUrl)
                    .collect { event ->
                        if (failed) return@collect
                        when (event) {
                            is GenericOpenAiEvent.Text -> emit(
                                wherevercode.agent.brain.BrainEvent.TextDelta(event.text),
                            )
                            is GenericOpenAiEvent.Reasoning -> emit(
                                wherevercode.agent.brain.BrainEvent.ReasoningDelta(event.text),
                            )
                            is GenericOpenAiEvent.ToolCall -> {
                                // Membership brains do not do tool
                                // calling in this first cut: refuse
                                // honestly, never execute with a
                                // membership token.
                                failed = true
                                emit(
                                    wherevercode.agent.brain.BrainEvent.Error(
                                        code = "unsupported",
                                        message = "${config.displayName}会员大脑暂不支持工具调用：本次请求要求调用工具 ${event.name}，已拒绝，未执行。",
                                        retryable = false,
                                    ),
                                )
                                emit(wherevercode.agent.brain.BrainEvent.Done("error"))
                            }
                            is GenericOpenAiEvent.Usage -> emit(
                                wherevercode.agent.brain.BrainEvent.Usage(
                                    event.inputTokens,
                                    event.outputTokens,
                                ),
                            )
                            is GenericOpenAiEvent.Finished -> emit(
                                wherevercode.agent.brain.BrainEvent.Done(event.reason),
                            )
                            is GenericOpenAiEvent.Failed -> {
                                if (event.code == "auth_failed" && !refreshedOnce) {
                                    sawAuthFailed = true
                                } else {
                                    failed = true
                                    emit(
                                        wherevercode.agent.brain.BrainEvent.Error(
                                            code = event.code,
                                            message = event.message,
                                            retryable = event.retryable,
                                        ),
                                    )
                                    emit(wherevercode.agent.brain.BrainEvent.Done("error"))
                                }
                            }
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(
                    wherevercode.agent.brain.BrainEvent.Error(
                        code = "brain_call_failed",
                        message = "${config.displayName}调用失败：${e.message ?: "未知错误"}",
                        retryable = true,
                    ),
                )
                emit(wherevercode.agent.brain.BrainEvent.Done("error"))
                return@flow
            }
            if (!sawAuthFailed || failed) return@flow
            // 401 once: refresh and retry the whole turn.
            refreshedOnce = true
            currentToken = try {
                manager.refreshTokens().accessToken
            } catch (e: BrainError.AuthFailed) {
                emit(
                    wherevercode.agent.brain.BrainEvent.Error(
                        code = "auth_failed",
                        message = e.message ?: "${config.displayName} 授权已失效，请重新登录。",
                        retryable = false,
                    ),
                )
                emit(wherevercode.agent.brain.BrainEvent.Done("error"))
                return@flow
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<wherevercode.agent.brain.BrainEvent>.emitNotConfigured(
        message: String,
    ) {
        emit(
            wherevercode.agent.brain.BrainEvent.Error(
                code = "not_configured",
                message = message,
                retryable = false,
            ),
        )
        emit(wherevercode.agent.brain.BrainEvent.Done("error"))
    }

    private fun resolve(): Resolved? {
        val brainId = brainIdProvider() ?: return null
        val config = configStore.getConfig(brainId)
            ?.takeIf { it.adapterId == adapterId } ?: return null
        val baseUrl = config.baseUrl?.takeIf { it.isNotBlank() } ?: return null
        return Resolved(config, baseUrl)
    }

    private data class Resolved(val config: BrainConfig, val baseUrl: String)
}
