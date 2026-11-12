package wherevercode.agent.brain.grok

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
 * Grok brain (xAI API, OpenAI-compatible).
 *
 * This is a first-class, configurable MODEL_API brain. It calls the
 * Grok **model** through xAI's official API (`https://api.x.ai/v1`,
 * see docs.x.ai / console.x.ai): standard `/chat/completions` +
 * `/models`, Bearer key, SSE streaming, native tool_calls. The wire
 * protocol is the shared [GenericOpenAiApiClient] — no second HTTP/SSE
 * implementation is invented here; the only Grok-specific parts are
 * the endpoint, the suggested model, and the credential store.
 *
 * Honest scope, read this before expecting more from it:
 * - This adapter reaches the Grok **model** (grok-4.7), not the
 *   **Example Agent** agent (the phone App + persistent cloud computer
 *   teammate, docs.x.ai/example-agent). Example Agent has no public chat API
 *   as of 2026-11: the official docs describe talking to a Bot only
 *   by messaging it in the Example Agent app / X Chat / Slack — no
 *   endpoint lists Bots, sends a message, or reads a transcript.
 *   So this adapter cannot give the App that Bot's memory, tools,
 *   routines, skills or conversation history — only model inference
 *   on a key the user supplies. The label and [capabilities] notes
 *   say this in the UI; do not present this brain as "Example Agent 本人".
 * - A real "Example Agent agent" connection would need xAI to ship an
 *   official Bot API (list Bots, send a message, read the reply,
 *   dispatch a task). That design and its gaps are written up in
 *   the delivery doc, not faked here. The one official programmatic
 *   surface that exists today is a routine's webhook trigger (POST
 *   starts a routine run); it wakes a Bot but does not hold a
 *   conversation or return results, so it is not a chat/task API.
 * - Third-party services that drive a Example Agent account by logging
 *   in with the user's session / credentials (e.g. community
 *   `example-agent-cli`, which drives the desktop app's session through
 *   an internal gateway) are deliberately NOT used: that is an
 *   unofficial bypass on undocumented routes, and it violates the
 *   project's rule (official, authorised path only; never bypass,
 *   never borrow quota).
 * - "代码不许下到 Example Agent" (maintainer's standing rule): this adapter
 *   sends only the chat prompt to the xAI model API. No project
 *   file, no repository, no workspace path is ever sent to Grok
 *   Bot's cloud computer — the adapter never touches the Bot at
 *   all. If a task needs the Bot agent itself to work on code, that
 *   is a separate decision for maintainer (it would require giving the
 *   Bot code); do not smuggle code to the Bot through this adapter.
 * - Billing / quota (maintainer 2026-11-11, same lesson as Muse Spark):
 *   the xAI API is separately billed — grok-4.7 costs $2.00 per
 *   1M input tokens and $6.00 per 1M output tokens — and does NOT
 *   share any Grok/X subscription or membership quota. Without
 *   paying for API credits, this adapter cannot be used. To spend
 *   maintainer's existing Grok membership quota, use the example-agent CLI
 *   membership brain (Hand-discovered, already in the App), not
 *   this one. Do not present this brain as "free" or "using your
 *   existing Grok membership".
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
 * - baseUrl: fixed to the official xAI endpoint by default,
 *   user-editable, must stay https;
 * - model: user-editable, populated from the live `/models` list,
 *   suggested `grok-4.7` (the official flagship model for chat and
 *   code, docs.x.ai);
 * - API key: only in [GrokCredentialStore] (Keystore). The key is
 *   the user's own xAI API key (starts with `xai-`, created at
 *   console.x.ai), entered on the phone, never sent to Muse, never
 *   in `BrainConfig`, never in logs, never in an APK, never in the
 *   repository.
 *
 * xAI endpoint note: xAI marks `/chat/completions` as the legacy
 * endpoint and recommends the Responses API (`/v1/responses`) for
 * new code, but `/chat/completions` still works and is what the
 * shared OpenAI-compatible client speaks. Keep it that way until
 * the shared client gains a Responses mode; do not invent a second
 * client here.
 *
 * grok-4.7 is a reasoning model: the shared client sends only
 * `temperature` (if set) and `max_tokens` (if set) — never `stop`,
 * `logprobs` or `logit_bias`. Keep it that way; a test pins it.
 *
 * No key is ever hardcoded, and no model is silently substituted
 * when the configured one is blank. The only "suggested" values are
 * UI seeds.
 */
class GrokBrainAdapter(
    private val credentialStore: GrokCredentialStore,
    private val baseUrlProvider: () -> String? = { DEFAULT_BASE_URL },
    private val configuredModelProvider: () -> String? = { null },
    private val apiClient: GenericOpenAiApiClient = GenericOpenAiApiClient(),
) : BrainAdapter {

    override val adapterId: String = ADAPTER_ID
    override val kind: BrainKind = BrainKind.MODEL_API
    override val label: String = "Grok"

    override fun capabilities(): BrainCapabilities = BrainCapabilities(
        streaming = true,
        toolCalling = true,
        // V5 按主流改（maintainer 2026-11-11）：Grok 4 系列支持图片输入，
        // xAI 官方文档（docs.x.ai）与第三方实测均确认 /v1/chat/completions
        // 接受 OpenAI 同款 image_url parts（JPEG/PNG，单张 20 MiB 以内）。
        // 所以 vision=true 是厂商文档能力的自报，不是猜测；K70 真机验
        // 证（切到 Grok 大脑发一张图）仍待做，见 DELIVERY。
        vision = true,
        maxContextTokens = null,
        // xAI's API documents function calling for Chat Completions
        // and the shared client parses OpenAI-format tool_calls, but no
        // live call has verified it end to end (no real key in this
        // build): stay null (unverified) rather than claiming true.
        toolCallingVerified = null,
        notes = "xAI API /chat/completions（Grok 模型）；" +
            "工具调用使用标准 tool_calls/tool_call_id 配对；模型从账户的 /models 列表选择，不写死；" +
            "支持图片输入（vision，image_url parts，JPEG/PNG 单张 20 MiB 以内）；" +
            "Key 只存本机 Keystore。注意：这是 Grok 模型，不是 Example Agent 本人（手机 App + 云电脑的那个 Agent 没有公开聊天 API，本适配器够不着它；代码不许下到 Example Agent）。",
    )

    override suspend fun validateCredentials(): Result<Unit> {
        val apiKey = credentialStore.loadApiKey()
            ?: return Result.failure(IllegalStateException("未配置 Grok API Key。"))
        val baseUrl = baseUrlProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("未配置 Grok 服务地址。"))
        return apiClient.listModels(apiKey, baseUrl).map { Unit }
    }

    override suspend fun listModels(): Result<List<ModelInfo>> {
        val apiKey = credentialStore.loadApiKey()
            ?: return Result.failure(IllegalStateException("未配置 Grok API Key。"))
        val baseUrl = baseUrlProvider()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("未配置 Grok 服务地址。"))
        return apiClient.listModels(apiKey, baseUrl)
    }

    override fun chatStream(request: ChatRequest): Flow<BrainEvent> = flow {
        val apiKey = credentialStore.loadApiKey()
        if (apiKey.isNullOrBlank()) {
            emit(
                BrainEvent.Error(
                    code = "not_configured",
                    message = "未配置 Grok API Key：请在设置里填写你自己的 xAI API Key（只存本机 Keystore，绝不发给任何人）。",
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
                    message = "未配置 Grok 服务地址：请在设置里填写 baseUrl。",
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
                    message = "未选择 Grok 模型：请先刷新模型列表并选择一个。",
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
                    message = "Grok 调用失败：${e.message ?: "未知错误"}",
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
        const val ADAPTER_ID: String = "model-api-grok"
        const val BRAIN_ID: String = "grok"
        const val AUTH_REF: String = "grok:api-key"

        /** Official xAI API base URL (docs.x.ai). Fixed default, user-editable. */
        const val DEFAULT_BASE_URL: String = "https://api.x.ai/v1"

        /** Official flagship model for chat and code (docs.x.ai). First-run suggestion only, never a silent fallback. */
        const val SUGGESTED_MODEL: String = "grok-4.7"
    }
}
