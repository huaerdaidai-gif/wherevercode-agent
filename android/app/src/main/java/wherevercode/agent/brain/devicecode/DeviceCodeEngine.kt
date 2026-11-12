package wherevercode.agent.brain.devicecode

import kotlinx.coroutines.delay
import org.json.JSONObject
import wherevercode.agent.brain.oauth.OAuthEngine
import wherevercode.agent.brain.oauth.OAuthHttpTransport
import wherevercode.agent.brain.oauth.OAuthPkce
import wherevercode.agent.brain.siwc.BrainError
import java.io.IOException

/**
 * One device-code response (RFC 8628 §3.2): what the server returns
 * for `POST device/code`, and what the user is shown.
 */
data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

/** One token-endpoint response for the device-code / refresh grants. */
data class DeviceCodeTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
    val scope: String,
    /** Epoch millis when the tokens were obtained (for expiry math). */
    val obtainedAtMillis: Long,
) {
    /**
     * True when the access token is expired or about to expire
     * (60s leeway: a token that dies mid-turn is a failed turn).
     * A token with no refresh token and expired is dead — the user
     * must log in again.
     */
    fun isExpired(nowMillis: Long, leewaySeconds: Long = 60L): Boolean =
        obtainedAtMillis + (expiresInSeconds - leewaySeconds).coerceAtLeast(0L) * 1_000L <= nowMillis

    /** True when a refresh attempt is possible at all. */
    val refreshable: Boolean get() = !refreshToken.isNullOrBlank()
}

/**
 * RFC 8628 device-code flow engine (2026-11-12, membership add-all).
 * One engine covers Qwen + Kimi + Grok: the protocol is implemented
 * exactly once, here; everything vendor-specific comes from the
 * [DeviceCodeConfig] passed in (endpoints, client id, scopes,
 * inference base, device headers).
 *
 * Flow (the caller orchestrates the user-facing half):
 * 1. [requestDeviceCode] — POST device/code, get `user_code` +
 *    `verification_uri_complete`; the caller shows them and the user
 *    authorizes in a browser on any device.
 * 2. [pollForToken] — poll the token endpoint every `interval`
 *    seconds with `grant_type=device_code` + the PKCE verifier until
 *    the user authorizes (or the code expires / is denied).
 * 3. [refreshTokens] — `grant_type=refresh_token` when the access
 *    token expires.
 *
 * PKCE (S256) is sent on both steps: `code_challenge` with the
 * device-code request, `code_verifier` with the token request
 * ([OAuthPkce] — the same helper the authorization-code engine
 * uses). No client secret is sent for any vendor here: all three are
 * public clients.
 *
 * Error mapping (same caliber as [OAuthEngine]): network errors and
 * 5xx/429 become [BrainError.Transport] (a retry may help);
 * `expired_token` / `access_denied` / `invalid_grant` /
 * `invalid_client` become [BrainError.AuthFailed] and invoke
 * `onGrantDead` (the grant is dead — the caller clears its stored
 * tokens); `authorization_pending` / `slow_down` are not errors —
 * the poll loop handles them. No token value is ever included in a
 * message. Messages use `config.displayName` / `config.operatorName`.
 *
 * This engine is stateless apart from nothing: the caller owns the
 * pending attempt (device code + PKCE verifier) and the stored
 * tokens ([DeviceCodeTokenStore]). This layer produces no AI text.
 */
class DeviceCodeEngine(
    val config: DeviceCodeConfig,
    private val http: OAuthHttpTransport,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    // ------------------------------------------------------------------
    // Step 1: device code
    // ------------------------------------------------------------------

    /**
     * Request a device code. `codeChallenge` is the S256 PKCE
     * challenge for the verifier the caller keeps for the token
     * request. `extraHeaders` carries vendor device headers (Kimi
     * `X-Msh-*`); empty for vendors that need none.
     */
    suspend fun requestDeviceCode(
        codeChallenge: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): DeviceCodeResponse {
        val params = linkedMapOf<String, String>()
        params["client_id"] = config.clientId
        if (config.scopes.isNotEmpty()) params["scope"] = config.scopes.joinToString(" ")
        params["code_challenge"] = codeChallenge
        params["code_challenge_method"] = "S256"
        val response = try {
            http.postForm(config.deviceCodeUrl, params, extraHeaders)
        } catch (e: IOException) {
            throw BrainError.Transport("连接 ${config.operatorName} 设备码端点失败。", e)
        }
        if (response.status !in 200..299) {
            val error = parseErrorCode(response.body)
            when {
                response.status in 500..599 || response.status == 429 ->
                    throw BrainError.Transport(
                        "${config.operatorName} 设备码端点暂不可用（HTTP ${response.status}），可稍后重试。",
                    )
                else -> throw BrainError.AuthFailed(
                    "${config.displayName} 设备码请求失败" +
                        (error?.let { "（$it）" } ?: "（HTTP ${response.status}）") +
                        "。",
                    errorCode = error,
                )
            }
        }
        val json = try {
            JSONObject(response.body)
        } catch (_: Exception) {
            throw BrainError.Transport("${config.operatorName} 设备码端点返回了无法解析的响应。")
        }
        val deviceCode = json.optString("device_code", "")
        val userCode = json.optString("user_code", "")
        val verificationUri = json.optString("verification_uri", "")
        val verificationUriComplete = json.optString("verification_uri_complete", "")
            .ifBlank { verificationUri }
        if (deviceCode.isBlank() || userCode.isBlank() || verificationUri.isBlank()) {
            throw BrainError.Transport("${config.operatorName} 设备码端点返回了不完整的响应。")
        }
        return DeviceCodeResponse(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = verificationUriComplete,
            expiresInSeconds = json.optLong("expires_in", DEFAULT_DEVICE_CODE_EXPIRES_SECONDS),
            intervalSeconds = json.optLong("interval", DEFAULT_POLL_INTERVAL_SECONDS)
                .coerceAtLeast(MIN_POLL_INTERVAL_SECONDS),
        )
    }

    // ------------------------------------------------------------------
    // Step 2: poll for tokens
    // ------------------------------------------------------------------

    /**
     * Poll the token endpoint until the user authorizes. Sleeps
     * `intervalSeconds` between attempts (grown by 5s on `slow_down`,
     * per RFC 8628 §3.5); gives up on `expired_token` /
     * `access_denied` / `invalid_grant` / `invalid_client`, and when
     * the device code's own lifetime runs out. `isCancelled` is
     * checked before every attempt so the user can stop waiting.
     * Returns the token set (with [DeviceCodeTokens.obtainedAtMillis]
     * stamped from [clockMillis]).
     */
    suspend fun pollForToken(
        deviceCode: String,
        codeVerifier: String,
        intervalSeconds: Long,
        expiresInSeconds: Long,
        extraHeaders: Map<String, String> = emptyMap(),
        isCancelled: () -> Boolean = { false },
        onGrantDead: () -> Unit = {},
    ): DeviceCodeTokens {
        val deadlineMillis = clockMillis() + expiresInSeconds * 1_000L
        var intervalMillis = intervalSeconds.coerceAtLeast(MIN_POLL_INTERVAL_SECONDS) * 1_000L
        while (true) {
            if (isCancelled()) {
                throw BrainError.AuthFailed(
                    "已取消${config.displayName}登录。",
                    errorCode = "device_code_cancelled",
                )
            }
            if (clockMillis() >= deadlineMillis) {
                onGrantDead()
                throw BrainError.AuthFailed(
                    "${config.displayName} 设备码已过期，请重新开始登录。",
                    errorCode = "expired_token",
                )
            }
            val params = linkedMapOf<String, String>()
            params["grant_type"] = DEVICE_CODE_GRANT_TYPE
            params["device_code"] = deviceCode
            params["client_id"] = config.clientId
            params["code_verifier"] = codeVerifier
            val response = try {
                http.postForm(config.tokenUrl, params, extraHeaders)
            } catch (e: IOException) {
                throw BrainError.Transport("连接 ${config.operatorName} 令牌端点失败。", e)
            }
            // Error first, status second: RFC 8628 §3.5 signals
            // pending/slow_down/expired/denied with HTTP 400 + an
            // `error` field, but some servers answer 200 with an
            // `error` body instead — a 200 that carries `error` is
            // never a token grant, so it is handled here too.
            val error = parseErrorCode(response.body)
            if (error != null) {
                when (error) {
                    // Not an error: keep waiting.
                    "authorization_pending" -> {
                        sleeper(intervalMillis)
                    }
                    // Not an error: back off, then keep waiting.
                    "slow_down" -> {
                        intervalMillis += SLOW_DOWN_EXTRA_MILLIS
                        sleeper(intervalMillis)
                    }
                    "expired_token" -> {
                        onGrantDead()
                        throw BrainError.AuthFailed(
                            "${config.displayName} 设备码已过期，请重新开始登录。",
                            errorCode = error,
                        )
                    }
                    "access_denied" -> {
                        onGrantDead()
                        throw BrainError.AuthFailed(
                            "${config.displayName} 登录被拒绝：请确认是在你自己的账号上授权。",
                            errorCode = error,
                        )
                    }
                    "invalid_grant", "invalid_client" -> {
                        onGrantDead()
                        throw BrainError.AuthFailed(
                            "${config.displayName} 授权已失效（$error），请重新登录。",
                            errorCode = error,
                        )
                    }
                    else -> {
                        if (response.status in 500..599 || response.status == 429) {
                            throw BrainError.Transport(
                                "${config.operatorName} 令牌端点暂不可用（HTTP ${response.status}），可稍后重试。",
                            )
                        }
                        throw BrainError.AuthFailed(
                            "${config.displayName} 令牌请求失败（$error）。",
                            errorCode = error,
                        )
                    }
                }
                continue
            }
            if (response.status in 200..299) return parseTokenResponse(response.body)
            if (response.status in 500..599 || response.status == 429) {
                throw BrainError.Transport(
                    "${config.operatorName} 令牌端点暂不可用（HTTP ${response.status}），可稍后重试。",
                )
            }
            throw BrainError.AuthFailed(
                "${config.displayName} 令牌请求失败（HTTP ${response.status}）。",
            )
        }
    }

    // ------------------------------------------------------------------
    // Step 3: refresh
    // ------------------------------------------------------------------

    /**
     * Refresh a grant. POSTs a form-encoded `grant_type=refresh_token`
     * grant. A missing refresh token is a caller bug (check
     * [DeviceCodeTokens.refreshable] first); an `invalid_grant` here
     * means the grant is dead and invokes `onGrantDead`.
     */
    suspend fun refreshTokens(
        refreshToken: String,
        extraHeaders: Map<String, String> = emptyMap(),
        onGrantDead: () -> Unit = {},
    ): DeviceCodeTokens {
        val params = linkedMapOf<String, String>()
        params["grant_type"] = "refresh_token"
        params["refresh_token"] = refreshToken
        params["client_id"] = config.clientId
        val response = try {
            http.postForm(config.tokenUrl, params, extraHeaders)
        } catch (e: IOException) {
            throw BrainError.Transport("连接 ${config.operatorName} 令牌端点失败。", e)
        }
        // Error first, status second (same rule as pollForToken):
        // a 200 carrying an `error` field is never a token grant.
        val error = parseErrorCode(response.body)
        if (error != null) {
            when {
                response.status in 500..599 || response.status == 429 ->
                    throw BrainError.Transport(
                        "${config.operatorName} 令牌端点暂不可用（HTTP ${response.status}），可稍后重试。",
                    )
                error == "invalid_grant" || error == "invalid_client" -> {
                    onGrantDead()
                    throw BrainError.AuthFailed(
                        "${config.displayName} 授权已失效（$error），请重新登录。",
                        errorCode = error,
                    )
                }
                else -> throw BrainError.AuthFailed(
                    "${config.displayName} 令牌刷新失败（$error）。",
                    errorCode = error,
                )
            }
        }
        if (response.status in 200..299) return parseTokenResponse(response.body)
        if (response.status in 500..599 || response.status == 429) {
            throw BrainError.Transport(
                "${config.operatorName} 令牌端点暂不可用（HTTP ${response.status}），可稍后重试。",
            )
        }
        throw BrainError.AuthFailed(
            "${config.displayName} 令牌刷新失败（HTTP ${response.status}）。",
        )
    }

    private fun parseTokenResponse(body: String): DeviceCodeTokens {
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            throw BrainError.Transport("${config.operatorName} 令牌端点返回了无法解析的响应。")
        }
        val accessToken = json.optString("access_token", "")
        if (accessToken.isBlank()) {
            throw BrainError.Transport("${config.operatorName} 令牌端点返回了不完整的响应。")
        }
        return DeviceCodeTokens(
            accessToken = accessToken,
            refreshToken = json.optString("refresh_token", "").ifBlank { null },
            expiresInSeconds = json.optLong(
                "expires_in",
                OAuthEngine.DEFAULT_EXPIRES_IN_SECONDS,
            ),
            scope = json.optString("scope", ""),
            obtainedAtMillis = clockMillis(),
        )
    }

    private fun parseErrorCode(body: String): String? {
        return try {
            JSONObject(body).optString("error", "").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** RFC 8628 device-code grant type. */
        const val DEVICE_CODE_GRANT_TYPE: String =
            "urn:ietf:params:oauth:grant-type:device_code"

        /** Server omitted `expires_in`: device codes live 10 minutes by default. */
        const val DEFAULT_DEVICE_CODE_EXPIRES_SECONDS: Long = 600L

        /** Server omitted `interval`: poll every 5 seconds by default. */
        const val DEFAULT_POLL_INTERVAL_SECONDS: Long = 5L

        /** Never poll faster than once per second, whatever the server says. */
        const val MIN_POLL_INTERVAL_SECONDS: Long = 1L

        /** `slow_down` grows the poll interval by 5 seconds (RFC 8628 §3.5). */
        const val SLOW_DOWN_EXTRA_MILLIS: Long = 5_000L
    }
}
