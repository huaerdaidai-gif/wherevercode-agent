package wherevercode.agent.brain.siwc

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * SIWC (Sign in with ChatGPT) BrainAdapter — REAL implementation (M3).
 *
 * This is no longer the skeleton (d170ad9): [infer] / [streamInfer] now
 * perform real authorization (via [SiwcAuthManager]) and real inference
 * (via [ResponsesApiClient] against the official Responses API). No mock
 * inference, no fake replies: only server-returned text is emitted.
 *
 * Flow:
 * 1. [SiwcAuthManager.ensureFreshAccessToken] returns a valid OAuth access
 *    token (refreshing the grant when it is near expiry). No token here
 *    means NotConfigured; a dead grant means AuthFailed — both surface as
 *    [BrainStreamEvent.Failed], never as fabricated text.
 * 2. [ResponsesApiClient.streamResponse] POSTs to the official Responses API
 *    with that token (Bearer), enforcing the SIWC Preview limits
 *    (store=false, stream=true, full history, instructions, no system
 *    messages, no temperature / max_output_tokens).
 *    The model is resolved per turn before the call: explicit config wins,
 *    else the persisted resolution, else runtime discovery from the
 *    account's own model directory ([SiwcModelDiscovery] — GET /v1/models,
 *    visibility == "list"). There is no hardcoded default model; a 400
 *    model_not_found triggers one rediscovery + retry with the newly
 *    selected model, then surfaces a clear error (see [streamInfer]).
 * 3. Responses SSE events are translated to [BrainStreamEvent]:
 *    TextDelta -> Delta; ToolCallDone -> ToolCall (arguments accumulated by
 *    the parser); Completed -> Completed (usage = server-reported numbers
 *    only); Failed -> Failed (partial text is discarded by the caller).
 *
 * Authorization rules (SIWC Terms, https://openai.com/policies/sign-in-with-chatgpt-terms/):
 * - Only the official sign-in flow. Never ask for passwords or session cookies.
 * - Tokens are stored locally, under the user's control (Android Keystore).
 * - Requests are only for the authenticated user, from that user's own activity
 *   or expressly authorized automations. Never use one user's plan for another
 *   user, and never let another user's activity trigger requests to this
 *   user's account.
 * - Never turn plan usage into a generic API, and never charge for it.
 *
 * SIWC Preview limits encoded in [capabilities] (see
 * https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations):
 * store=false, stream=true, full history in `input`, no system messages,
 * no tool_search, no Code Interpreter, no hosted MCP. The adapter enforces
 * these; it never silently drops a system message or a temperature setting.
 *
 * UNVERIFIED (see the OAuth / Responses handover notes): whether OpenAI
 * accepts dynamic registration + loopback callback from an Android app is
 * NOT confirmed by any official document; the first real sign-in must be
 * done by the user on the K70 after eligibility is confirmed. This file
 * implements the official loopback contract; it does not claim Android is
 * officially supported.
 */
class SiwcBrainAdapter(
    private val tokenStore: SiwcTokenStore,
    private val authManager: SiwcAuthManager,
    private val responsesClient: ResponsesApiClient = ResponsesApiClient(),
    /**
     * Runtime model discovery (GET /v1/models with the same SIWC token).
     * Injectable for tests; production uses the real endpoint.
     */
    private val modelDiscovery: SiwcModelDiscovery = SiwcModelDiscovery(),
    /**
     * Persists the model resolved from the account's directory (plain slug,
     * never a token). Null = do not persist (each turn re-discovers when
     * no explicit model is set). Wired by BrainBootstrap to the
     * BrainConfigStore so a resolved model survives restarts.
     */
    private val modelStore: SiwcModelStore? = null,
) : BrainProvider {

    override val providerId: String = "siwc-chatgpt"

    override val displayName: String = "ChatGPT (Sign in with ChatGPT)"

    override val capabilities: BrainCapabilities = BrainCapabilities(
        supportsStreaming = true,
        // Function / custom tools only, in a namespace; no hosted tools.
        supportsTools = true,
        // SIWC Preview: no explicit system messages. Use instructions instead.
        supportsSystemMessages = false,
        // SIWC Preview: full history in input, no previous_response_id.
        requiresFullHistory = true,
        // SIWC Preview: temperature is not a supported field.
        supportsTemperature = false,
        // SIWC Preview: max_output_tokens is not a supported field.
        supportsMaxOutputTokens = false,
        billsUserPlan = true,
        limitsNote = "SIWC Preview: store=false, stream=true, full history in input, " +
            "no system messages, no tool_search, no Code Interpreter, no hosted MCP. " +
            "See https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations",
    )

    override fun isConfigured(): Boolean {
        // Configured = the user has signed in and tokens are stored locally.
        return tokenStore.loadTokens() != null
    }

    /**
     * The account's real model directory (C3): fresh access token first,
     * then [SiwcModelDiscovery] GET /v1/models (visibility == "list").
     * Any failure is returned as-is ([BrainError]) — never wrapped, never
     * replaced by a hardcoded model. No Responses call is made here and
     * no model is selected or persisted; this only exposes the directory
     * (the per-turn selection in [resolveModel] is unchanged).
     */
    override suspend fun listModels(): Result<List<String>> = try {
        val accessToken = authManager.ensureFreshAccessToken()
        val catalog = modelDiscovery.discover(accessToken)
        Result.success(catalog.models)
    } catch (e: BrainError) {
        Result.failure(e)
    }

    override suspend fun infer(request: BrainRequest): BrainResponse {
        // Non-streaming path: collect the stream. Tool calls cannot be
        // represented in a BrainResponse, so a turn that calls tools is an
        // explicit Unsupported — callers that need tools must use streamInfer.
        val text = StringBuilder()
        var model = ""
        var usage: BrainUsage? = null
        streamInfer(request).collect { event ->
            when (event) {
                is BrainStreamEvent.Delta -> text.append(event.text)
                is BrainStreamEvent.ToolCall -> throw BrainError.Unsupported(
                    "infer() 不支持工具调用（本次回复要求调用工具 ${event.name}）；请用 streamInfer()。",
                )
                is BrainStreamEvent.Completed -> {
                    model = event.response.model
                    usage = event.response.usage
                }
                is BrainStreamEvent.Failed -> throw event.error
            }
        }
        return BrainResponse(
            text = text.toString(),
            model = model,
            usage = usage,
            billedToUserPlan = true,
        )
    }

    override fun streamInfer(request: BrainRequest): Flow<BrainStreamEvent> = flow {
        // Capability enforcement first: never silently drop these.
        if (request.temperature != null) {
            throw BrainError.Unsupported("SIWC Preview 不支持 temperature，请去掉该参数。")
        }
        if (request.maxOutputTokens != null) {
            throw BrainError.Unsupported("SIWC Preview 不支持 max_output_tokens，请去掉该参数。")
        }
        // Fresh access token (refreshes the grant when near expiry).
        // Throws BrainError.NotConfigured / AuthFailed / Transport.
        val accessToken = try {
            authManager.ensureFreshAccessToken()
        } catch (e: BrainError) {
            emit(BrainStreamEvent.Failed(e))
            return@flow
        }
        // Resolve the model for this turn (M3 blocker fix): an explicit
        // choice wins; otherwise the persisted resolution, else runtime
        // discovery from the account's own model directory. There is NO
        // hardcoded default — the old DEFAULT_MODEL = "gpt-5" was removed
        // because the server rejects it with 400 model_not_found.
        // Discovery failure / empty directory throws a BrainError here and
        // surfaces as Failed; no Responses call is made in that case.
        val explicitModel = request.model?.takeIf { it.isNotBlank() }
        val model = try {
            resolveModel(accessToken, explicitModel)
        } catch (e: BrainError) {
            emit(BrainStreamEvent.Failed(e))
            return@flow
        }
        val apiRequest = ResponsesApiRequest(
            model = model,
            messages = request.messages.map { message ->
                ResponsesInputMessage(
                    role = message.role.name.lowercase(),
                    content = message.content,
                )
            },
            instructions = request.instructions,
            tools = request.tools.map { tool ->
                ResponsesTool(
                    name = tool.name,
                    description = tool.description,
                    parametersJson = tool.parametersJson,
                )
            },
        )
        try {
            collectResponseStream(apiRequest, accessToken)
        } catch (e: ModelNotFoundSignal) {
            // The server says this model is not in the account's directory.
            // Invalidate the cached directory, discover again, and retry
            // ONCE with the newly selected model. Only the HTTP-400 case
            // (no event emitted yet) retries: if text was already streamed,
            // a retry would duplicate it, so in-stream model_not_found
            // surfaces directly via mapStreamFailure instead.
            modelDiscovery.invalidate()
            val retryModel = try {
                resolveModel(accessToken, explicitModel = null, allowStored = false)
            } catch (e2: BrainError) {
                emit(BrainStreamEvent.Failed(e2))
                return@flow
            }
            if (retryModel == apiRequest.model) {
                // The fresh directory still selects the same model the
                // server just rejected: retrying would fail identically.
                emit(BrainStreamEvent.Failed(modelNotFoundError(retryModel)))
                return@flow
            }
            try {
                collectResponseStream(apiRequest.copy(model = retryModel), accessToken)
            } catch (e2: ModelNotFoundSignal) {
                emit(BrainStreamEvent.Failed(modelNotFoundError(retryModel)))
            }
        }
    }

    /**
     * One Responses attempt: stream events through, translating them to
     * [BrainStreamEvent]. HTTP/auth/quota failures become Failed events;
     * HTTP 400 model_not_found throws [ModelNotFoundSignal] instead, so
     * the caller can rediscover and retry once (no event has been emitted
     * yet at that point, so a retry cannot duplicate text).
     */
    private suspend fun FlowCollector<BrainStreamEvent>.collectResponseStream(
        apiRequest: ResponsesApiRequest,
        accessToken: String,
    ) {
        val fullText = StringBuilder()
        try {
            responsesClient.streamResponse(apiRequest, accessToken).collect { event ->
                when (event) {
                    is ResponsesStreamEvent.TextDelta -> {
                        fullText.append(event.text)
                        emit(BrainStreamEvent.Delta(event.text))
                    }
                    is ResponsesStreamEvent.ToolCallArgumentsDelta -> {
                        // Arguments are accumulated by the parser; the
                        // complete call arrives as ToolCallDone. Deliberately
                        // ignored here (not emitted as partial calls).
                    }
                    is ResponsesStreamEvent.ToolCallDone -> {
                        emit(
                            BrainStreamEvent.ToolCall(
                                id = event.callId ?: event.itemId
                                    ?: "call-${event.outputIndex ?: 0}",
                                name = event.name ?: "",
                                argumentsJson = event.arguments,
                            ),
                        )
                    }
                    is ResponsesStreamEvent.Completed -> {
                        emit(
                            BrainStreamEvent.Completed(
                                BrainResponse(
                                    text = event.fullText ?: fullText.toString(),
                                    model = apiRequest.model,
                                    usage = event.usage?.let { usage ->
                                        BrainUsage(
                                            inputTokens = usage.inputTokens,
                                            outputTokens = usage.outputTokens,
                                        )
                                    },
                                    billedToUserPlan = true,
                                ),
                            ),
                        )
                    }
                    is ResponsesStreamEvent.Failed -> {
                        emit(BrainStreamEvent.Failed(mapStreamFailure(event, apiRequest.model)))
                    }
                }
            }
        } catch (e: ResponsesApiError) {
            if (e is ResponsesApiError.Unsupported && e.serverCode == "model_not_found") {
                throw ModelNotFoundSignal(e)
            }
            emit(BrainStreamEvent.Failed(mapApiError(e)))
        } catch (e: BrainError) {
            emit(BrainStreamEvent.Failed(e))
        }
    }

    /**
     * Resolve the model for this turn (M3 blocker fix).
     * - Explicit model (config / caller): used as-is. The server is the
     *   final judge; a 400 model_not_found is handled by the retry in
     *   [streamInfer].
     * - Otherwise the last resolved model persisted via [modelStore]
     *   (unless [allowStored] is false, e.g. right after a
     *   model_not_found, when the stored value is the suspect one).
     * - Otherwise discover the account's directory ([SiwcModelDiscovery])
     *   and select from it (prefer gpt-6.1-sol, else the first listed
     *   slug), then persist the choice. Discovery failure or an empty
     *   directory throws a [BrainError] — never a hardcoded fallback model.
     */
    private suspend fun resolveModel(
        accessToken: String,
        explicitModel: String?,
        allowStored: Boolean = true,
    ): String {
        explicitModel?.let { return it }
        if (allowStored) {
            modelStore?.loadModel()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        val catalog = modelDiscovery.discover(accessToken)
        val selected = SiwcModelDiscovery.selectModel(catalog)
            ?: throw BrainError.Unsupported(
                "该 ChatGPT 账号的可用模型目录为空：无法确定用哪个模型推理，" +
                    "请检查该账号的计划与授权范围，或重新登录。",
            )
        modelStore?.saveModel(selected)
        return selected
    }

    /**
     * The user-facing error for a model the server says is not in the
     * account's directory. Names the cause and the next step; never blames
     * the network, and never suggests retrying the same model.
     */
    private fun modelNotFoundError(model: String): BrainError.Unsupported =
        BrainError.Unsupported(
            "模型 $model 不在你的 ChatGPT 账号可用目录里（model_not_found），" +
                "已按账号目录重新选择仍失败。请在设置里更换模型，或退出后重新登录 ChatGPT。",
        )

    /**
     * Internal control-flow signal: one attempt failed with HTTP 400
     * model_not_found before any event was emitted. Carries the server
     * error so the caller can rediscover + retry once. Never surfaces
     * to the user or the engine.
     */
    private class ModelNotFoundSignal(val error: ResponsesApiError.Unsupported) : Exception()

    override suspend fun revoke() {
        // Revoke at the authorization server, then clear local tokens.
        // SiwcAuthManager.revoke() already clears local tokens even when
        // remote revocation is unconfirmed; clear again here so the local
        // copy is gone even if revoke() itself throws.
        try {
            authManager.revoke()
        } finally {
            tokenStore.clearTokens()
        }
    }

    /**
     * Map a stream-level failure to a BrainError. HTTP-level auth/quota
     * already arrive as [ResponsesApiError] (thrown); this mapping is the
     * safety net for server-sent error codes inside the stream.
     */
    private fun mapStreamFailure(event: ResponsesStreamEvent.Failed, model: String): BrainError =
        when (event.code) {
            "invalid_token", "unauthorized", "authentication_failed", "invalid_api_key" ->
                BrainError.AuthFailed(event.message)
            "insufficient_quota", "quota_exceeded", "rate_limit_exceeded", "plan_not_eligible" ->
                BrainError.QuotaExceeded(event.message)
            "model_not_found" ->
                BrainError.Unsupported(
                    "模型 $model 不在你的 ChatGPT 账号可用目录里（model_not_found）。" +
                        "请在设置里更换模型，或退出后重新登录 ChatGPT。",
                )
            else -> BrainError.Transport(event.message)
        }

    /** ResponsesApiError and BrainError correspond 1:1 (see ResponsesApiClient). */
    private fun mapApiError(error: ResponsesApiError): BrainError = when (error) {
        is ResponsesApiError.NotConfigured -> BrainError.NotConfigured(error.message ?: "未登录 ChatGPT。")
        is ResponsesApiError.AuthFailed -> BrainError.AuthFailed(error.message ?: "ChatGPT 授权失败。")
        is ResponsesApiError.QuotaExceeded -> BrainError.QuotaExceeded(error.message ?: "ChatGPT 套餐额度不足。")
        is ResponsesApiError.Unsupported -> BrainError.Unsupported(error.message ?: "该请求不受 SIWC Preview 支持。")
        is ResponsesApiError.Transport -> BrainError.Transport(error.message ?: "网络或服务器错误。")
    }

    companion object {
        /** Official SIWC constants, for reference. See the SIWC Cookbook. */
        const val SIWC_RESOURCE = "https://api.openai.com/v1"
        const val SIWC_RESPONSES_URL = "https://api.openai.com/v1/responses"
        const val SIWC_USAGE_URL = "https://chatgpt.com/settings/usage"

        /** Dynamic registration: sent on first sign-in; OpenAI issues a real client_id. */
        const val SIWC_DYNAMIC_CLIENT_ID = "dynamic_agent_client"

        /**
         * Scopes for the SIWC sign-in request. `offline_access` requests a
         * refresh token; `resource.invoke` with `chatgpt.tokens.use.direct`
         * requests API access using the user's ChatGPT plan.
         */
        val SIWC_SCOPES: List<String> = listOf(
            "openid",
            "profile",
            "email",
            "offline_access",
            "resource.invoke",
            "chatgpt.tokens.use.direct",
        )

        /** OAuth callback path on the loopback listener (http://127.0.0.1:<port>). */
        const val SIWC_CALLBACK_PATH = "/auth/callback"

        // No hardcoded default model (M3 blocker fix, 2026-11-08).
        // The old DEFAULT_MODEL = "gpt-5" was removed: the server rejects
        // it with 400 model_not_found for ChatGPT-account sign-in. The
        // model is now resolved per turn — explicit config, persisted
        // resolution, or runtime discovery from the account's own directory
        // (GET /v1/models, visibility == "list"). The directory-internal
        // preference is SiwcModelDiscovery.PREFERRED_MODEL ("gpt-6.1-sol",
        // the model the official SIWC examples use); it is a preference
        // inside a discovered directory, never a fallback when discovery
        // fails or the directory is empty.
    }
}

/**
 * Local token storage for SIWC.
 *
 * Implementations must store tokens only on the device, under the user's
 * control (Android Keystore + EncryptedSharedPreferences or EncryptedFile).
 * Never store tokens on a server, never put them in a log, and never send
 * them anywhere except the official OpenAI endpoints for this user.
 */
interface SiwcTokenStore {
    fun saveTokens(tokens: SiwcTokens)
    fun loadTokens(): SiwcTokens?
    fun clearTokens()
}

/**
 * SIWC OAuth tokens for one signed-in user. Never log these values.
 *
 * One instance belongs to exactly one user. It must never be used to serve
 * another user's requests.
 */
data class SiwcTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    /** The client_id OpenAI issued during dynamic registration. */
    val clientId: String,
    /** The per-install host id (ext_agent_host_id), stable across restarts. */
    val hostId: String,
    /** Epoch millis when the access token expires. */
    val accessTokenExpiresAtMillis: Long,
    /** The ChatGPT workspace / account the user selected, when known. */
    val accountLabel: String? = null,
)
