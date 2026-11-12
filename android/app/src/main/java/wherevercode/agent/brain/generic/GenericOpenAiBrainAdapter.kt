package wherevercode.agent.brain.generic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import wherevercode.agent.brain.BrainAdapter
import wherevercode.agent.brain.BrainCapabilities
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigStore
import wherevercode.agent.brain.BrainConfigValidator
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ModelInfo
import wherevercode.agent.brain.StreamParser

/**
 * Generic OpenAI-compatible brain (C3 second stage, W-adapter).
 *
 * One adapter instance, registered under adapterId `model-api-generic`,
 * serves EVERY user-created generic brain: the first-stage form saves
 * one config per label with brainId `model-api-<slug>` (see
 * [wherevercode.agent.ui.BrainLoginRows.genericBrainId]) and the same
 * adapterId, so a stored config needs no migration — registering this
 * adapter is what wires it. The engine calls [chatStream] with only a
 * model (no brain id), so the adapter resolves its config through
 * [brainIdProvider], which defaults to the current brain id; [forBrain]
 * binds one explicit brain id for per-row calls (e.g. a settings-row
 * "测试连接" on a brain that is not current).
 *
 * Configuration comes from the caller (BrainBootstrap reads it from
 * [BrainConfig]):
 * - baseUrl: user-editable, no vendor default; validated with
 *   [BrainConfigValidator.isAllowedBaseUrl] (https-only, local-model
 *   http://127.0.0.1 / http://localhost exception) at call time, so a
 *   stored config that predates validation still fails loudly instead
 *   of calling a disallowed address;
 * - model: user-editable, populated from the live `/models` list; blank
 *   means "not chosen yet" and errors at request time, never a silent
 *   hardcoded substitute;
 * - API key: only in [GenericOpenAiCredentialStore] (Keystore) under
 *   authRef `model-api:<brainId>`; [BrainConfig] holds only the
 *   reference name.
 *
 * Tools: toolCalling = true from stage 2 (maintainer 2026-11-11: "派人做").
 * The engine sends tool definitions in the request (toolsForTurn, only
 * when this adapter reports tool support) and executes tool calls the
 * model issues through the shared ToolRegistry chain (read-only tools
 * run directly, the rest go through approval — same caliber as every
 * other tool-capable brain). Mid-stream tool calls are parsed to
 * BrainEvent.ToolCall with the DeepSeek adapter's pattern (malformed
 * arguments are an error, never silently dropped); tool results are
 * fed back by the engine as role="tool" messages and the turn loops
 * until the model answers in text or the engine's round/call budget
 * stops it. toolCallingVerified is NOT set here: stage-2 support is
 * claimed by the adapter, real-device (K70) verification is still
 * pending — see DELIVERY.md, do not present it as verified.
 *
 * No key is ever hardcoded, and no model is silently substituted when
 * the configured one is blank. The only "suggested" values are the
 * [GenericOpenAiPresets] base URLs the settings form offers.
 */
class GenericOpenAiBrainAdapter(
    private val configStore: BrainConfigStore,
    private val credentialStore: GenericOpenAiCredentialStore,
    private val apiClient: GenericOpenAiApiClient = GenericOpenAiApiClient(),
    private val brainIdProvider: () -> String? = { configStore.currentBrainId() },
) : BrainAdapter {

    override val adapterId: String = ADAPTER_ID
    override val kind: BrainKind = BrainKind.MODEL_API
    override val label: String = "通用模型 API"

    override fun capabilities(): BrainCapabilities = BrainCapabilities(
        streaming = true,
        // Stage 2 (maintainer 2026-11-11): tool calling is on. The request
        // carries tool definitions, the stream parser already emits
        // tool_calls, and this adapter emits them as BrainEvent.ToolCall
        // for the engine to execute. toolCallingVerified stays null:
        // support is claimed here, real-device verification is pending.
        toolCalling = true,
        vision = false,
        maxContextTokens = null,
        notes = "OpenAI 兼容 /chat/completions；模型从账户的 /models 列表选择，不写死；" +
            "Key 只存本机 Keystore。第二阶段支持工具调用（写操作走审批）。",
    )

    /** A view of this adapter bound to one explicit brain id. */
    fun forBrain(brainId: String): GenericOpenAiBrainAdapter =
        GenericOpenAiBrainAdapter(configStore, credentialStore, apiClient) { brainId }

    override suspend fun validateCredentials(): Result<Unit> {
        val config = resolveConfig()
            ?: return Result.failure(
                IllegalStateException("未配置通用模型 API 大脑：请先在设置里添加一个通用模型 API 大脑。"),
            )
        val baseUrl = config.baseUrl?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("未配置服务地址：请先在设置里填写 baseUrl。"))
        // 本机模型可无 Key（地址是 127.0.0.1/localhost 时不发
        // Authorization 头，见 GenericOpenAiApiClient）；非本机地址
        // 无 Key 仍拒绝，不静默调一个没 Key 的远端。
        val apiKey = credentialStore.loadApiKey(config.authRef ?: "")
        if (apiKey.isNullOrBlank() && !BrainConfigValidator.isLocalModelUrl(baseUrl)) {
            return Result.failure(IllegalStateException("未配置 API Key：请先在设置里填写你自己的 Key。"))
        }
        return apiClient.listModels(apiKey ?: "", baseUrl).map { Unit }
    }

    override suspend fun listModels(): Result<List<ModelInfo>> {
        val config = resolveConfig()
            ?: return Result.failure(
                IllegalStateException("未配置通用模型 API 大脑：请先在设置里添加一个通用模型 API 大脑。"),
            )
        val baseUrl = config.baseUrl?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("未配置服务地址：请先在设置里填写 baseUrl。"))
        val apiKey = credentialStore.loadApiKey(config.authRef ?: "")
        if (apiKey.isNullOrBlank() && !BrainConfigValidator.isLocalModelUrl(baseUrl)) {
            return Result.failure(IllegalStateException("未配置 API Key：请先在设置里填写你自己的 Key。"))
        }
        return apiClient.listModels(apiKey ?: "", baseUrl)
    }

    override fun chatStream(request: ChatRequest): Flow<BrainEvent> = flow {
        // Stage 2: tools are no longer rejected — the request body
        // builder (GenericOpenAiApiClient) already sends them as
        // OpenAI-compatible function definitions, and the engine only
        // passes tools this adapter's capabilities allow.
        val config = resolveConfig()
        if (config == null) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "未配置通用模型 API 大脑：请先在设置里添加一个通用模型 API 大脑。",
                    retryable = false,
                ),
            )
            emit(BrainEvent.Done("error"))
            return@flow
        }
        val baseUrl = config.baseUrl?.takeIf { it.isNotBlank() }
        if (baseUrl == null) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "未配置服务地址：请先在设置里填写 baseUrl。",
                    retryable = false,
                ),
            )
            emit(BrainEvent.Done("error"))
            return@flow
        }
        // 本机模型可无 Key（不发 Authorization 头）；非本机地址无 Key
        // 仍拒绝，不静默调一个没 Key 的远端。
        val apiKey = credentialStore.loadApiKey(config.authRef ?: "")
        if (apiKey.isNullOrBlank() && !BrainConfigValidator.isLocalModelUrl(baseUrl)) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "未配置 API Key：请先在设置里填写你自己的 Key（只存本机 Keystore）。",
                    retryable = false,
                ),
            )
            emit(BrainEvent.Done("error"))
            return@flow
        }
        if (!BrainConfigValidator.isAllowedBaseUrl(baseUrl)) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "服务地址只允许 https（本地模型允许 http://127.0.0.1 或 http://localhost）：$baseUrl",
                    retryable = false,
                ),
            )
            emit(BrainEvent.Done("error"))
            return@flow
        }
        val model = request.model.takeIf { it.isNotBlank() }
            ?: config.model.takeIf { it.isNotBlank() }
        if (model == null) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "未选择模型：请先刷新模型列表并选择一个。",
                    retryable = false,
                ),
            )
            emit(BrainEvent.Done("error"))
            return@flow
        }
        val effectiveRequest = request.copy(model = model)
        // Set once a malformed tool-call argument fails the turn: later
        // events from the same stream must not leak out as text after
        // the error (the turn already failed).
        var failed = false
        try {
            apiClient.streamChat(effectiveRequest, apiKey ?: "", baseUrl).collect { event ->
                if (failed) return@collect
                when (event) {
                    is GenericOpenAiEvent.Text -> emit(BrainEvent.TextDelta(event.text))
                    is GenericOpenAiEvent.Reasoning -> emit(BrainEvent.ReasoningDelta(event.text))
                    is GenericOpenAiEvent.ToolCall -> {
                        // Stage 2: emit the call for the engine to
                        // execute (DeepSeek adapter's pattern).
                        // Malformed arguments are an error, never
                        // silently dropped and never executed with
                        // guessed arguments.
                        val args = parseToolArgs(event.argumentsJson)
                        if (args == null) {
                            failed = true
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
                    is GenericOpenAiEvent.Usage -> emit(
                        BrainEvent.Usage(event.inputTokens, event.outputTokens),
                    )
                    is GenericOpenAiEvent.Finished -> emit(BrainEvent.Done(event.reason))
                    is GenericOpenAiEvent.Failed -> {
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
                    message = "通用模型 API 调用失败：${e.message ?: "未知错误"}",
                    retryable = true,
                ),
            )
            emit(BrainEvent.Done("error"))
        }
    }

    /** null = malformed JSON; an empty object is valid. (DeepSeek adapter's pattern.) */
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

    /**
     * Resolve the config this call is for: the brain id from
     * [brainIdProvider], and only a config whose adapterId is this
     * adapter's. A current brain that belongs to another adapter (e.g.
     * DeepSeek) is NOT this adapter's config — resolving it would read
     * the wrong key and baseUrl.
     */
    private fun resolveConfig(): BrainConfig? {
        val brainId = brainIdProvider() ?: return null
        return configStore.getConfig(brainId)?.takeIf { it.adapterId == ADAPTER_ID }
    }

    companion object {
        /**
         * Stable adapter id for every generic OpenAI-compatible brain.
         * Must stay identical to
         * [wherevercode.agent.ui.BrainLoginRows.GENERIC_ADAPTER_ID] —
         * the form saves configs with that id, and the engine resolves
         * brainId -> adapter through it. (The adapter cannot import the
         * UI object, so the value is duplicated here with this note.)
         */
        const val ADAPTER_ID: String = "model-api-generic"
    }
}
