package wherevercode.agent.brain.devicecode

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import wherevercode.agent.brain.BrainAdapter
import wherevercode.agent.brain.BrainCapabilities
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.LOGIN_ONLY_EXTRA_KEY
import wherevercode.agent.brain.ModelInfo
import wherevercode.agent.brain.oauth.VendorOAuthConfig

/**
 * Login-only brain adapter (2026-11-12, Gemini).
 *
 * Some vendors let the user sign in but offer no membership
 * inference endpoint at all. Gemini is the case: a Google account
 * OAuth token authorises Google API scopes, not the Gemini API
 * (which only takes an API Key); the internal Code Assist endpoint
 * is undocumented, its terms ban reuse, and its personal tier
 * stopped serving on 2026-06-18. Registering this adapter is what
 * makes the login entry visible and deletable in the brain list —
 * but every inference call is refused here, honestly, with the
 * reason and the stable alternative (API Key / Vertex AI). The
 * brain can never be "configured" (`isBrainConfigured` returns
 * false for it) and can never be switched to.
 *
 * The adapter is driven by the [VendorOAuthConfig] (fixed client id
 * support): until a V5 developer registers a V5-owned Desktop OAuth
 * client and fills `fixedClientId`, even login is unavailable and
 * the UI says so.
 */
class LoginOnlyBrainAdapter(
    val config: VendorOAuthConfig,
) : BrainAdapter {

    override val adapterId: String = ADAPTER_ID
    override val kind: BrainKind = BrainKind.SUBSCRIPTION
    override val label: String = config.displayName

    override fun capabilities(): BrainCapabilities = BrainCapabilities(
        streaming = false,
        toolCalling = false,
        vision = false,
        maxContextTokens = null,
        notes = "${config.displayName}仅登录，不能当大脑用推理：" +
            "会员 token 调不了 ${config.displayName} API；" +
            "稳妥路线是 API Key 或 Vertex AI。",
    )

    override suspend fun validateCredentials(): Result<Unit> =
        Result.failure(IllegalStateException(NOT_A_BRAIN_MESSAGE))

    /** A login-only brain has no model catalogue: empty list is honest. */
    override suspend fun listModels(): Result<List<ModelInfo>> = Result.success(emptyList())

    override fun chatStream(request: ChatRequest): Flow<BrainEvent> = flow {
        emit(
            BrainEvent.Error(
                code = "not_supported",
                message = NOT_A_BRAIN_MESSAGE,
                retryable = false,
            ),
        )
        emit(BrainEvent.Done("error"))
    }

    companion object {
        /** Stable adapter id for the Gemini login-only brain. */
        const val ADAPTER_ID: String = "oauth-gemini"

        const val NOT_A_BRAIN_MESSAGE: String =
            "Gemini 会员登录不能当大脑用推理：会员 token 调不了 Gemini API，" +
                "内部端点未文档化且条款禁止复用；请用 API Key（AI Studio）或 Vertex AI 作 Gemini 大脑。"

        /** `extra` marker written into the Gemini seed config. */
        const val EXTRA_LOGIN_ONLY: String = LOGIN_ONLY_EXTRA_KEY
    }
}
