package wherevercode.agent.brain.devicecode

import wherevercode.agent.brain.oauth.OAuthHttpTransport
import wherevercode.agent.brain.oauth.OAuthPkce
import wherevercode.agent.brain.siwc.BrainError

/**
 * One in-flight device-code login attempt: the server-issued code
 * plus the PKCE verifier the token request must present. The caller
 * (MainActivity) shows [response] to the user and calls
 * [DeviceCodeManager.awaitAuthorization] to wait for the user to
 * authorize in a browser. The verifier is never shown to the user
 * and never leaves the device except to the vendor's own token
 * endpoint.
 */
data class DeviceCodeLoginAttempt(
    val response: DeviceCodeResponse,
    val codeVerifier: String,
)

/**
 * Device-code login orchestration for one vendor (2026-11-12,
 * membership add-all). The engine ([DeviceCodeEngine]) owns the
 * protocol; this class owns the session: PKCE generation, the
 * pending attempt, token persistence ([DeviceCodeTokenStore]), and
 * refresh-before-use.
 *
 * No network, no account, no model call happens here beyond the
 * vendor's own OAuth endpoints. No token value is ever logged or
 * included in an error message.
 */
class DeviceCodeManager(
    val config: DeviceCodeConfig,
    private val tokenStore: DeviceCodeTokenStore,
    private val http: OAuthHttpTransport,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val deviceHeaders: () -> Map<String, String> = { emptyMap() },
) {
    private val engine = DeviceCodeEngine(config, http, clockMillis)

    /** True when any tokens are stored (even expired ones). */
    fun hasTokens(): Boolean = tokenStore.hasTokens()

    /**
     * True when the vendor can be used right now: a live access
     * token, or an expired one with a refresh token to renew it.
     * Used by `isBrainConfigured` (the model still has to be filled
     * in separately — see BrainBootstrap).
     */
    fun isSignedIn(): Boolean {
        val tokens = tokenStore.loadTokens() ?: return false
        return !tokens.isExpired(clockMillis()) || tokens.refreshable
    }

    /**
     * Begin a login: generate a fresh PKCE verifier/challenge and ask
     * the vendor for a device code. The caller shows
     * [DeviceCodeLoginAttempt.response] (user code + verification
     * URL) to the user, then calls [awaitAuthorization].
     */
    suspend fun beginLogin(): DeviceCodeLoginAttempt {
        val verifier = OAuthPkce.generateVerifier()
        val challenge = OAuthPkce.challengeFor(verifier)
        val response = engine.requestDeviceCode(challenge, deviceHeaders())
        return DeviceCodeLoginAttempt(response, verifier)
    }

    /**
     * Wait for the user to authorize in a browser, then save the
     * tokens. Polls per the server's interval until authorization,
     * expiry, denial, or `isCancelled`. On grant death the stored
     * tokens are cleared so a stale grant cannot linger.
     */
    suspend fun awaitAuthorization(
        attempt: DeviceCodeLoginAttempt,
        isCancelled: () -> Boolean = { false },
    ): DeviceCodeTokens {
        val tokens = engine.pollForToken(
            deviceCode = attempt.response.deviceCode,
            codeVerifier = attempt.codeVerifier,
            intervalSeconds = attempt.response.intervalSeconds,
            expiresInSeconds = attempt.response.expiresInSeconds,
            extraHeaders = deviceHeaders(),
            isCancelled = isCancelled,
            onGrantDead = { tokenStore.clearTokens() },
        )
        tokenStore.saveTokens(tokens)
        return tokens
    }

    /**
     * A fresh access token for an inference call: refresh first when
     * the stored one is expired (or about to expire) and refreshable.
     * Throws [BrainError.AuthFailed] when there is no usable grant —
     * the caller reports "please log in again" and never retries
     * with a dead token.
     */
    suspend fun ensureFreshAccessToken(): String {
        val tokens = tokenStore.loadTokens()
            ?: throw BrainError.AuthFailed(
                "${config.displayName} 未登录：请先登录。",
                errorCode = "device_code_not_signed_in",
            )
        if (!tokens.isExpired(clockMillis())) return tokens.accessToken
        if (!tokens.refreshable) {
            tokenStore.clearTokens()
            throw BrainError.AuthFailed(
                "${config.displayName} 登录已过期，请重新登录。",
                errorCode = "device_code_expired",
            )
        }
        return refreshTokens().accessToken
    }

    /** Refresh the stored grant; clears the store when the grant is dead. */
    suspend fun refreshTokens(): DeviceCodeTokens {
        val tokens = tokenStore.loadTokens()
            ?: throw BrainError.AuthFailed(
                "${config.displayName} 未登录：请先登录。",
                errorCode = "device_code_not_signed_in",
            )
        val refreshToken = tokens.refreshToken
            ?: throw BrainError.AuthFailed(
                "${config.displayName} 登录已过期，请重新登录。",
                errorCode = "device_code_expired",
            )
        val fresh = engine.refreshTokens(
            refreshToken = refreshToken,
            extraHeaders = deviceHeaders(),
            onGrantDead = { tokenStore.clearTokens() },
        )
        // A refresh response may omit a rotated refresh token: keep
        // the old one rather than dropping the ability to refresh.
        val kept = if (fresh.refreshToken.isNullOrBlank()) {
            fresh.copy(refreshToken = refreshToken)
        } else {
            fresh
        }
        tokenStore.saveTokens(kept)
        return kept
    }

    /**
     * Sign out: wipe the local tokens. There is no documented
     * revocation endpoint for Qwen / Kimi / Grok device-code grants,
     * so sign-out is local-only — the vendor-side grant stays valid
     * until it expires; the user can revoke it in the vendor's own
     * account page. (Deleting the brain also wipes the tokens; see
     * MainActivity.deleteBrain.)
     */
    fun signOut() {
        tokenStore.clearTokens()
    }
}
