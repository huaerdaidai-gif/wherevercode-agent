package wherevercode.agent.brain.muse

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
import wherevercode.agent.brain.generic.GenericOpenAiApiClient
import wherevercode.agent.brain.generic.GenericOpenAiEvent

/**
 * Muse Spark brain (Meta Model API, OpenAI-compatible).
 *
 * This is a first-class, configurable MODEL_API brain. It calls the
 * Muse Spark **model** through Meta's official Model API
 * (`https://api.meta.ai/v1`, see dev.meta.ai): standard
 * `/chat/completions` + `/models`, Bearer key, SSE streaming, native
 * tool_calls. The wire protocol is the shared [GenericOpenAiApiClient]
 * — no second HTTP/SSE implementation is invented here; the only
 * Muse-specific parts are the endpoint, the suggested model, and the
 * credential store.
 *
 * Honest scope, read this before expecting more from it:
 * - This adapter reaches the Muse Spark **model**, not the Muse
 *   **personal assistant** (the product with memory, connected
 *   accounts, tools and sessions). That assistant has no public API
 *   as of 2026-10: Meta does not sell an API for it, and the Muse
 *   Connector Platform runs the other way (Muse calls your service).
 *   So this adapter cannot give the App the assistant's memory,
 *   accounts, tools or conversation history — only model inference
 *   on a key the user supplies. The label and [capabilities] notes
 *   say this in the UI; do not present this brain as "Muse 本人".
 * - A real "Muse personal-assistant brain" would need Meta to ship
 *   an official assistant API (user OAuth, a chat/session endpoint,
 *   streaming, memory/tool access). That design and its gaps are
 *   written up in the delivery doc, not faked here.
 * - Third-party services that drive a Muse account by logging in
 *   with the user's email + password are deliberately NOT used:
 *   that is a credential handover and an unofficial bypass, and it
 *   violates the project's rule (official, authorised path only;
 *   never bypass, never borrow quota).
 *
 * Brain / hand separation (maintainer 2026-11-11: 脑在 A，手在 B):
 * this adapter is the brain (A) — it only thinks: it sends the
 * prompt to the model and returns the model's reply. It never
 * executes anything itself: no file writes, no commands, no
 * processes, no Hand calls. When the model asks for a tool, the
 * adapter only emits a [BrainEvent.ToolCall] intent; the engine
 * runs it through the hands (B) — the App's task engine / the
 * device Hand on the device that owns the work. The brain commands,
 * the hands execute; do not let this adapter grow a hand.
 *
 * Configuration comes from the caller (BrainBootstrap reads it from
 * [wherevercode.agent.brain.BrainConfig]):
 * - baseUrl: fixed to the official Meta endpoint by default,
 *   user-editable, must stay https;
 * - model: user-editable, populated from the live `/models` list,
 *   suggested `muse-spark-1.3` (the official quickstart model);
 * - API key: only in [MuseCredentialStore] (Keystore). The key is
 *   the user's own Meta Model API key, entered on the phone, never
 *   sent to Muse, never in `BrainConfig`, never in logs, never in
 *   an APK, never in the repository.
 *
 * Muse Spark is a reasoning model: the shared client sends only
 * `temperature` (if set) and `max_tokens` (if set) — never `stop`,
 * `logprobs` or `logit_bias`, which the model API rejects with
 * HTTP 400. Keep it that way; a test pins it.
 *
 * No key is ever hardcoded, and no model is silently substituted
 * when the configured one is blank. The only "suggested" values are
 * UI seeds.
 */
class MuseBrainAdapter(
    private val credentialStore: MuseCredentialStore,
    private val baseUrlProvider: () -> String? = { DEFAULT_BASE_URL },
    private val configuredModelProvider: () -> String? = { null },
    private val apiClient: GenericOpenAiApiClient = GenericOpenAiApiClient(),
) : BrainAdapter {

    override val adapterId: String = ADAPTER_ID
    override val kind: BrainKind = BrainKind.MODEL_API
    override val label: String = "Muse Spark"

    override fun capabilities(): BrainCapabilities = BrainCapabilities(
        streaming = true,
        toolCalling = true,
        vision = false,
        maxContextTokens = null,
        // Meta's Model API documents tool calling for Chat Completions
        // and the shared client parses OpenAI-format tool_calls, but no
        // live call has verified it end to end (no real key in this
        // build): stay null (unverified) rather than claiming true.
        toolCallingVerified = null,
        notes = "Meta Model API /chat/completions（Muse Spark 模型）；" +
            "工具调用使用标准 tool_calls/tool_call_id 配对；模型从账户的 /models 列表选择，不写死；" +
            "Key 只存本机 Keystore。注意：这是 Muse Spark 模型，不是个人助理 Muse（记忆/已连接账号/会话没有公开 API，本适配器够不着它）。",
    )

    override suspend fun validateCredentials(): Result<Unit> {
        val apiKey = credentialStore.loadApiKey()
            ?: return Result.failure(IllegalStateException("未配置 Muse API Key。"))
        val baseUrl = baseUrlProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("未配置 Muse 服务地址。"))
        return apiClient.listModels(apiKey, baseUrl).map { Unit }
    }

    override suspend fun listModels(): Result<List<ModelInfo>> {
        val apiKey = credentialStore.loadApiKey()
            ?: return Result.failure(IllegalStateException("未配置 Muse API Key。"))
        val baseUrl = baseUrlProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("未配置 Muse 服务地址。"))
        return apiClient.listModels(apiKey, baseUrl)
    }

    override fun chatStream(request: ChatRequest): Flow<BrainEvent> = flow {
        val apiKey = credentialStore.loadApiKey()
        if (apiKey.isNullOrBlank()) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "未配置 Muse API Key：请在设置里填写你自己的 Meta Model API Key（只存本机 Keystore，绝不发给任何人）。",
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
                    message = "未配置 Muse 服务地址：请在设置里填写 baseUrl。",
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
                    message = "未选择 Muse 模型：请先刷新模型列表并选择一个。",
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
                    is GenericOpenAiEvent.Text -> emit(BrainEvent.TextDelta(event.text))
                    is GenericOpenAiEvent.Reasoning -> emit(BrainEvent.ReasoningDelta(event.text))
                    is GenericOpenAiEvent.ToolCall -> {
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
                    message = "Muse 调用失败：${e.message ?: "未知错误"}",
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
        const val ADAPTER_ID: String = "model-api-muse"
        const val BRAIN_ID: String = "muse-spark"
        const val AUTH_REF: String = "muse:api-key"

        /** Official Meta Model API base URL (dev.meta.ai). Fixed default, user-editable. */
        const val DEFAULT_BASE_URL: String = "https://api.meta.ai/v1"

        /** Official quickstart model (dev.meta.ai). First-run suggestion only, never a silent fallback. */
        const val SUGGESTED_MODEL: String = "muse-spark-1.3"
    }
}
