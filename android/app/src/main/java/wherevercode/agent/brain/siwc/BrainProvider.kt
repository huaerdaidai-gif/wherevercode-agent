package wherevercode.agent.brain.siwc

import kotlinx.coroutines.flow.Flow

/**
 * BrainProvider — a switchable inference brain for the WhereverCode super Agent.
 *
 * Design principles:
 * - The super Agent is a dispatcher; the brain is a replaceable member, not a
 *   fixed role. ChatGPT (via Sign in with ChatGPT) is the first verification
 *   object, not the permanent or only brain.
 * - Only officially supported authorization is used. No passwords, no session
 *   cookies, no reverse-engineered clients, no generic API forwarding, and no
 *   sharing one user's plan with another user.
 * - Providers declare their capabilities; callers must respect them. A provider
 *   that cannot do something must fail loudly ([BrainError.Unsupported]) rather
 *   than silently degrading or faking a reply.
 * - No mock inference. If there is no available brain, the dispatcher must
 *   report "no available brain" rather than returning a fabricated answer.
 */
interface BrainProvider {

    /** Stable provider id, e.g. "siwc-chatgpt", "local-minicpm". Never user-visible. */
    val providerId: String

    /** Human-readable name, e.g. "ChatGPT (Sign in with ChatGPT)". */
    val displayName: String

    /** What this provider can and cannot do. Callers must check before calling. */
    val capabilities: BrainCapabilities

    /**
     * Run one inference turn and return the full text.
     *
     * Implementations must enforce their capabilities (for example SIWC requires
     * store=false, stream=true, full history in input, and no system messages).
     * Throws [BrainError] on failure; never returns a fake or mock response.
     */
    suspend fun infer(request: BrainRequest): BrainResponse

    /**
     * Stream one inference turn as events (delta, completed, error).
     *
     * If the provider does not support streaming, it must throw
     * [BrainError.Unsupported] rather than buffering the whole reply and
     * faking a stream.
     */
    fun streamInfer(request: BrainRequest): Flow<BrainStreamEvent>

    /** True when the provider is configured and ready (for example the user is signed in). */
    fun isConfigured(): Boolean

    /**
     * The account's model directory, when the provider has one to probe
     * (SIWC: GET /v1/models, visibility == "list"). Providers without a
     * model catalogue return an empty list — honest, not a failure — so
     * the default is empty and only providers with a real directory
     * override it. Failures are returned as [Result.failure] with the
     * provider's [BrainError], never replaced by a hardcoded model.
     */
    suspend fun listModels(): Result<List<String>> = Result.success(emptyList())

    /**
     * Revoke this provider's authorization and delete all locally stored
     * credentials for it. After [revoke], [isConfigured] must return false.
     *
     * Revocation must also be reported to the authorization server when the
     * provider supports it; deleting local tokens alone is not enough while
     * the server still holds a live grant.
     */
    suspend fun revoke()
}

/** A single message in the conversation history. */
data class BrainMessage(
    val role: BrainRole,
    val content: String,
    /**
     * Tool result pairing (P1-2, 2026-11-09): the id of the tool call this
     * tool-role message answers. Null for every non-tool message and for
     * legacy tool messages recorded before ids were carried (providers
     * that require pairing must downgrade those instead of sending an
     * unpaired `role="tool"` message — see DeepSeekApiClient).
     */
    val toolCallId: String? = null,
    /**
     * Assistant tool calls, in wire order (P1-2, 2026-11-09). Empty for
     * every non-assistant message and for assistant messages that only
     * carry text. The legacy [content] echo is kept so providers without
     * native tool support still receive something readable.
     */
    val toolCalls: List<BrainToolCall> = emptyList(),
)

/** One tool call carried by an assistant message (wire shape, cf. [wherevercode.agent.brain.ChatToolCall]). */
data class BrainToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * Message roles.
 *
 * Note: there is no SYSTEM role. SIWC Preview does not support explicit system
 * messages; system-level guidance goes in [BrainRequest.instructions] instead,
 * which providers send as `instructions` (Responses API) or as a developer
 * message where the provider supports it.
 * TOOL is the official tool-result role (Cloudflare native API): the engine
 * emits it after a tool executes, preceded by an assistant echo of the tool
 * call. Providers that have no tool role (SIWC / Responses API) must never
 * receive it — their bridge maps tool results to USER instead, and their
 * HTTP layer rejects a TOOL message loudly rather than silently degrading.
 */
enum class BrainRole {
    USER,
    ASSISTANT,
    DEVELOPER,
    TOOL,
}

/** One inference request. */
data class BrainRequest(
    /**
     * Full conversation history. Providers that require full history (for
     * example SIWC, which has no previous_response_id) need all of it in
     * every request.
     */
    val messages: List<BrainMessage>,

    /**
     * System-level guidance. Sent as `instructions` (Responses API), not as
     * a system message, so it works under SIWC Preview limits.
     */
    val instructions: String? = null,

    /** Model identifier, for example a slug from the provider's model list. Null means provider default. */
    val model: String? = null,

    /** Function / custom tools. Must respect the provider's tool constraints. */
    val tools: List<BrainTool> = emptyList(),

    /**
     * Optional sampling knobs. Providers that do not support them must reject
     * the request with [BrainError.Unsupported], not silently ignore them.
     */
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

/** A function / custom tool the brain may call. */
data class BrainTool(
    val name: String,
    val description: String,
    /** JSON Schema for the tool's parameters, as a JSON string. */
    val parametersJson: String,
)

/** One inference response (non-streaming). */
data class BrainResponse(
    val text: String,
    val model: String,
    /** Usage in the provider's own units, when reported. Null when not reported. */
    val usage: BrainUsage? = null,
    /** True when the response was billed to the user's own plan (SIWC). */
    val billedToUserPlan: Boolean = false,
)

/** Token usage, when the provider reports it. */
data class BrainUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

/** One streaming event. */
sealed class BrainStreamEvent {
    /** A piece of newly generated text. */
    data class Delta(val text: String) : BrainStreamEvent()

    /**
     * A tool call the brain wants executed. M3 integration addition: the
     * skeleton's event model had no way to carry a tool call even though
     * [BrainCapabilities.supportsTools] is true for SIWC (function tools).
     * The Responses API surfaces tool calls as
     * `function_call_arguments.done` (see ResponsesStreamParser); the
     * SIWC adapter accumulates the argument deltas and emits one ToolCall
     * per completed call. [argumentsJson] is the raw arguments JSON string
     * (may be "{}"); the bridge parses it into a Map for the M2 engine.
     */
    data class ToolCall(val id: String, val name: String, val argumentsJson: String) : BrainStreamEvent()

    /** The turn completed; carries the full response. */
    data class Completed(val response: BrainResponse) : BrainStreamEvent()

    /** The turn failed; carries the error. No partial text is kept as a success. */
    data class Failed(val error: BrainError) : BrainStreamEvent()
}

/**
 * What a provider can and cannot do.
 *
 * The values below are per-provider facts, not wishes. For SIWC Preview they
 * encode the official Preview limitations; for other providers they encode
 * that provider's own limits.
 */
data class BrainCapabilities(
    val supportsStreaming: Boolean,
    val supportsTools: Boolean,
    /** False for SIWC Preview: no explicit system messages. Use instructions instead. */
    val supportsSystemMessages: Boolean,
    /** True when the provider needs the full conversation history in every request. */
    val requiresFullHistory: Boolean,
    /** True when the caller may set temperature. False for SIWC Preview. */
    val supportsTemperature: Boolean,
    /** True when the caller may set max output tokens. False for SIWC Preview. */
    val supportsMaxOutputTokens: Boolean,
    /** True when the provider bills the user's own plan (SIWC). False for API-key providers. */
    val billsUserPlan: Boolean,
    /** Human-readable note on the provider's limits, for example the SIWC Preview limits. */
    val limitsNote: String = "",
)

/**
 * Errors a [BrainProvider] may throw. Never fake a success.
 *
 * Callers must surface these honestly: an auth failure means "sign in again",
 * a quota failure means "plan/quota exhausted", an unsupported request means
 * "this brain cannot do that". Do not retry an auth or quota error as if it
 * were a transient network error, and do not fall back to a different user's
 * plan.
 */
sealed class BrainError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The provider is not configured (for example the user has not signed in). */
    class NotConfigured(message: String) : BrainError(message)

    /**
     * Authorization failed or expired; the user must sign in again.
     * [errorCode] is the machine-readable code the server returned when
     * there is one (e.g. `invalid_grant` for a dead OAuth grant, or an
     * `id_token_*` code for a failed ID-token check); null when the
     * failure has no server code. It is never a secret.
     */
    class AuthFailed(
        message: String,
        cause: Throwable? = null,
        val errorCode: String? = null,
    ) : BrainError(message, cause)

    /** The user's plan / quota is exhausted, or the request is not eligible for the plan. */
    class QuotaExceeded(message: String) : BrainError(message)

    /** The request asked for something the provider does not support. */
    class Unsupported(message: String) : BrainError(message)

    /** Network or server error (including upstream 5xx). A retry may help. */
    class Transport(message: String, cause: Throwable? = null) : BrainError(message, cause)

    /** The provider is not implemented yet (skeleton). Not a user-facing error. */
    class NotImplemented(message: String) : BrainError(message)
}
