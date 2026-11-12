package wherevercode.agent.brain.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wherevercode.agent.brain.siwc.BrainError
import java.io.IOException
import java.net.URLEncoder

/**
 * Endpoints for one vendor, resolved from its OIDC discovery document
 * (with the [VendorOAuthConfig] fallbacks when discovery is unreachable).
 */
data class OAuthEndpoints(
    val authorizeUrl: String,
    val tokenUrl: String,
    val revocationUrl: String,
    val jwksUri: String,
)

/**
 * A parsed token-endpoint response. Field values are exactly what the
 * server returned (blank refresh / ID tokens become null); the caller
 * decides what a missing value means for its grant type — e.g. the
 * authorization-code flow requires an ID token, while a refresh response
 * may legitimately omit a rotated refresh token.
 */
data class OAuthTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSeconds: Long,
    val scope: String,
)

/** Result of [OAuthEngine.revokeToken]. Local tokens are cleared either way by the caller. */
data class OAuthRevokeResult(
    /** True when the server confirmed revocation (empty HTTP 200). */
    val remoteRevoked: Boolean,
    val attempts: Int,
)

/**
 * Generic OAuth 2.0 + PKCE engine. The protocol is implemented exactly
 * once, here; everything vendor-specific comes from the [VendorOAuthConfig]
 * passed in (endpoints, scopes, resource, dynamic client id, display
 * names). Adding a vendor means adding one config entry — never a second
 * copy of this flow.
 *
 * What this engine owns (extracted verbatim from the SIWC flow, which was
 * the first and so far only vendor):
 * - authorization-URL construction ([buildAuthorizationUrl]) — vendor
 *   extras (e.g. `agent_name_hint`, `ext_agent_host_id`, `id_token_hint`,
 *   `login_hint`) are passed in as an ordered map and land right after
 *   `client_id`, before the standard parameters;
 * - PKCE / state / nonce ([OAuthPkce]);
 * - OIDC discovery with verified-constant fallback ([resolveEndpoints]);
 * - code exchange and refresh grants ([exchangeAuthorizationCode],
 *   [refreshGrant]) — form POST plus the shared error mapping;
 * - ID-token verification ([verifyIdToken] → [OAuthIdTokenVerifier]);
 * - revocation with backoff retries ([revokeToken]).
 *
 * What this engine does NOT own (deliberately):
 * - the loopback callback socket: [wherevercode.agent.brain.siwc.LoopbackCallbackServer]
 *   is already vendor-neutral (it only returns the parsed query; the
 *   caller validates state before using the code) and is reused untouched;
 *   the caller orchestrates start / await / stop around its own pending
 *   attempt. Wrapping it here would add a `brain.oauth` → `brain.siwc`
 *   dependency for zero behaviour.
 * - any session state: tokens, granted scopes, identity, the pending
 *   attempt, dynamic-registration bookkeeping, and the "which client id
 *   was used" decisions stay with the vendor's manager (e.g.
 *   `SiwcAuthManager`). The engine is stateless apart from its in-memory
 *   endpoint cache.
 * - any inference call: this layer produces no AI text.
 *
 * Error mapping (same as the SIWC flow): network errors and 5xx/429 become
 * [BrainError.Transport] (a retry may help); `invalid_grant` /
 * `invalid_client` become [BrainError.AuthFailed] and invoke
 * `onGrantDead` (the grant is dead — the caller clears its stored tokens);
 * other 4xx become [BrainError.AuthFailed] with the server's error code.
 * No token value is ever included in a message. Messages use
 * `config.displayName` / `config.operatorName`, so the ChatGPT flow's
 * wording is byte-identical to before the extraction.
 *
 * Note on imports: [BrainError] lives in `brain.siwc` (it is the shared
 * brain error type); that is this file's only dependency on that package.
 */
class OAuthEngine(
    val config: VendorOAuthConfig,
    private val http: OAuthHttpTransport,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    /** Endpoints resolved from the OIDC discovery document, cached in memory. */
    @Volatile
    private var cachedEndpoints: OAuthEndpoints? = null

    // ------------------------------------------------------------------
    // Authorization URL (pure construction; no network, no server)
    // ------------------------------------------------------------------

    /**
     * Build the authorization URL for one attempt. The caller opens this
     * URL in a Custom Tab / system browser; the engine never opens a
     * browser itself.
     *
     * Parameter order is part of the contract (existing SIWC tests assert
     * the exact string): `client_id`, then `extraParams` in insertion
     * order, then `response_type`, `redirect_uri`, `scope`, `resource`
     * (omitted when the config has none), `state`, `nonce`,
     * `code_challenge_method=S256`, `code_challenge`.
     */
    fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        nonce: String,
        codeChallenge: String,
        extraParams: Map<String, String> = emptyMap(),
    ): String {
        val params = linkedMapOf<String, String>()
        params["client_id"] = clientId
        params.putAll(extraParams)
        params["response_type"] = "code"
        params["redirect_uri"] = redirectUri
        params["scope"] = config.scopes.joinToString(" ")
        config.resource?.let { params["resource"] = it }
        params["state"] = state
        params["nonce"] = nonce
        params["code_challenge_method"] = "S256"
        params["code_challenge"] = codeChallenge
        val query = params.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        val authorizeUrl = cachedEndpoints?.authorizeUrl ?: config.authorizationUrl
        return "$authorizeUrl?$query"
    }

    // ------------------------------------------------------------------
    // Endpoints (OIDC discovery, with verified constants as fallback)
    // ------------------------------------------------------------------

    /**
     * Resolve endpoints from the OIDC discovery document, falling back to
     * the config's verified constants. The discovery issuer must match
     * [VendorOAuthConfig.issuer]; a mismatched document is ignored.
     * A failed discovery is NOT cached: the next call retries it.
     */
    suspend fun resolveEndpoints(): OAuthEndpoints {
        cachedEndpoints?.let { return it }
        val fallback = OAuthEndpoints(
            authorizeUrl = config.authorizationUrl,
            tokenUrl = config.tokenUrl,
            revocationUrl = config.revocationUrl,
            jwksUri = config.jwksUri,
        )
        val resolved = try {
            val response = io { http.get(config.discoveryUrl) }
            if (response.status != 200) return fallback
            val json = JSONObject(response.body)
            if (json.optString("issuer", "") != config.issuer) return fallback
            OAuthEndpoints(
                authorizeUrl = json.optString("authorization_endpoint", "")
                    .ifBlank { config.authorizationUrl },
                tokenUrl = json.optString("token_endpoint", "")
                    .ifBlank { config.tokenUrl },
                revocationUrl = json.optString("revocation_endpoint", "")
                    .ifBlank { config.revocationUrl },
                jwksUri = json.optString("jwks_uri", "").ifBlank { config.jwksUri },
            )
        } catch (_: IOException) {
            fallback
        } catch (_: Exception) {
            fallback
        }
        cachedEndpoints = resolved
        return resolved
    }

    // ------------------------------------------------------------------
    // Code exchange / refresh (form POST + shared error mapping)
    // ------------------------------------------------------------------

    /**
     * Exchange an authorization code for tokens. POSTs a form-encoded
     * `authorization_code` grant to [tokenUrl] with the params the caller
     * built (issued client id — never a dynamic placeholder — code, PKCE
     * verifier, exact redirect URI, resource). No client secret is
     * required for a public client.
     */
    suspend fun exchangeAuthorizationCode(
        tokenUrl: String,
        params: Map<String, String>,
        onGrantDead: () -> Unit = {},
    ): OAuthTokenResponse = postTokenFormChecked(tokenUrl, params, onGrantDead)

    /**
     * Refresh a grant. POSTs a form-encoded `grant_type=refresh_token`
     * grant to [tokenUrl] with the params the caller built (issued client
     * id, saved refresh token, resource; omit `scope` to retain the grant).
     */
    suspend fun refreshGrant(
        tokenUrl: String,
        params: Map<String, String>,
        onGrantDead: () -> Unit = {},
    ): OAuthTokenResponse = postTokenFormChecked(tokenUrl, params, onGrantDead)

    /**
     * POST a token form and map failures: network errors and 5xx/429
     * become [BrainError.Transport]; `invalid_grant` / `invalid_client`
     * invoke [onGrantDead] and become [BrainError.AuthFailed]; other 4xx
     * become [BrainError.AuthFailed] with the server's error code (no
     * token values are ever included in the message).
     */
    private suspend fun postTokenFormChecked(
        url: String,
        params: Map<String, String>,
        onGrantDead: () -> Unit,
    ): OAuthTokenResponse {
        val response = try {
            io { http.postForm(url, params) }
        } catch (e: IOException) {
            throw BrainError.Transport("连接 ${config.operatorName} 令牌端点失败。", e)
        }
        if (response.status !in 200..299) {
            val error = parseErrorCode(response.body)
            // Google 的错误体里还有 error_description（如
            // "client_secret is missing."），它是真正可操作的诊断：
            // 只看 error 码（invalid_request）猜不出缺了什么。带上它
            // （截断、单行化；Google 的描述是固定文案，不含请求里的
            // 任何 token 值）。
            val description = parseErrorDescription(response.body)
            val detailSuffix = description?.let { "：$it" } ?: ""
            when {
                response.status in 500..599 || response.status == 429 ->
                    throw BrainError.Transport(
                        "${config.operatorName} 令牌端点暂不可用（HTTP ${response.status}），可稍后重试。",
                    )
                error == "invalid_grant" || error == "invalid_client" -> {
                    onGrantDead()
                    throw BrainError.AuthFailed(
                        "${config.displayName} 授权已失效（$error$detailSuffix），请重新登录。",
                        errorCode = error,
                    )
                }
                else -> throw BrainError.AuthFailed(
                    "${config.displayName} 令牌请求失败" +
                        (error?.let { "（$it$detailSuffix）" } ?: "（HTTP ${response.status}）") +
                        "。",
                    errorCode = error,
                )
            }
        }
        return parseTokenResponse(response)
    }

    private fun parseTokenResponse(response: OAuthHttpResponse): OAuthTokenResponse {
        val json = try {
            JSONObject(response.body)
        } catch (_: Exception) {
            throw BrainError.Transport("${config.operatorName} 令牌端点返回了无法解析的响应。")
        }
        return OAuthTokenResponse(
            accessToken = json.optString("access_token", ""),
            refreshToken = json.optString("refresh_token", "").ifBlank { null },
            idToken = json.optString("id_token", "").ifBlank { null },
            expiresInSeconds = json.optLong("expires_in", DEFAULT_EXPIRES_IN_SECONDS),
            scope = json.optString("scope", ""),
        )
    }

    private fun parseErrorCode(body: String): String? {
        return try {
            JSONObject(body).optString("error", "").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 令牌端点错误体里的 error_description（如 "client_secret is
     * missing."）：截断到 [MAX_ERROR_DESCRIPTION_CHARS] 并单行化。
     * Google 的描述是固定文案，不含请求里的任何 token 值；截断是
     * 防止状态行被撑爆的上限，不是过滤。
     */
    private fun parseErrorDescription(body: String): String? {
        return try {
            JSONObject(body).optString("error_description", "").ifBlank { null }
                ?.replace(Regex("[\\p{Cntrl}]"), " ")
                ?.trim()
                ?.take(MAX_ERROR_DESCRIPTION_CHARS)
                ?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // ID-token verification
    // ------------------------------------------------------------------

    /**
     * Verify an ID token: fetch the vendor JWKS (a fetch failure is
     * [BrainError.Transport]) and verify RS256 signature, issuer,
     * audience, expiry, and nonce via [OAuthIdTokenVerifier].
     */
    suspend fun verifyIdToken(
        idToken: String,
        expectedAudience: String,
        expectedNonce: String?,
    ): OAuthIdTokenClaims {
        val endpoints = resolveEndpoints()
        val jwksResponse = try {
            io { http.get(endpoints.jwksUri) }
        } catch (e: IOException) {
            throw BrainError.Transport("无法获取 ${config.operatorName} 的 JWKS，ID token 无法验证。", e)
        }
        if (jwksResponse.status != 200) {
            throw BrainError.Transport(
                "获取 ${config.operatorName} 的 JWKS 失败（HTTP ${jwksResponse.status}），ID token 无法验证。",
            )
        }
        return OAuthIdTokenVerifier.verify(
            idToken = idToken,
            jwksJson = jwksResponse.body,
            expectedIssuer = config.issuer,
            expectedAudience = expectedAudience,
            expectedNonce = expectedNonce,
            nowMillis = clockMillis(),
        )
    }

    // ------------------------------------------------------------------
    // Revoke
    // ------------------------------------------------------------------

    /**
     * Revoke a token at the authorization server: form-encoded POST with
     * the params the caller built (`token`, `token_type_hint`,
     * `client_id`). An empty HTTP 200 is success, including for an
     * already-invalid token.
     *
     * On network failure / 5xx / 429, retries with backoff (up to
     * [maxAttempts]) while the token is still available. Other 4xx are
     * terminal. The caller clears its local tokens either way — this
     * method only reports whether the server confirmed revocation.
     */
    suspend fun revokeToken(
        revocationUrl: String,
        params: Map<String, String>,
        maxAttempts: Int,
        backoffMillis: Long,
    ): OAuthRevokeResult {
        var attempts = 0
        var remoteRevoked = false
        while (attempts < maxAttempts && !remoteRevoked) {
            attempts++
            try {
                val response = io {
                    http.postForm(revocationUrl, params)
                }
                if (response.status == 200) {
                    remoteRevoked = true
                } else if (response.status in 500..599 || response.status == 429) {
                    if (attempts < maxAttempts) sleeper(backoffMillis * attempts)
                } else {
                    // 4xx other than 429: terminal (e.g. invalid client). Stop.
                    break
                }
            } catch (_: IOException) {
                if (attempts < maxAttempts) sleeper(backoffMillis * attempts)
            }
        }
        return OAuthRevokeResult(remoteRevoked = remoteRevoked, attempts = attempts)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** Access tokens last 1 hour (`expires_in: 3600`) per the SIWC docs; the default when a token response omits `expires_in`. */
        const val DEFAULT_EXPIRES_IN_SECONDS = 3600L

        /** error_description 进用户文案的上限（状态行一行能放下）。 */
        const val MAX_ERROR_DESCRIPTION_CHARS = 200

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8")
    }
}
