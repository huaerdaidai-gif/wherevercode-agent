package wherevercode.agent.brain.siwc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.brain.oauth.CHATGPT_AUTHORIZATION_URL
import wherevercode.agent.brain.oauth.CHATGPT_DISCOVERY_URL
import wherevercode.agent.brain.oauth.CHATGPT_ISSUER
import wherevercode.agent.brain.oauth.CHATGPT_JWKS_URI
import wherevercode.agent.brain.oauth.CHATGPT_REVOCATION_URL
import wherevercode.agent.brain.oauth.CHATGPT_SHARING_SCOPE
import wherevercode.agent.brain.oauth.CHATGPT_TOKEN_URL
import wherevercode.agent.brain.oauth.OAuthEngine
import wherevercode.agent.brain.oauth.OAuthHttpTransport
import wherevercode.agent.brain.oauth.OAuthIdTokenClaims
import wherevercode.agent.brain.oauth.OAuthPkce
import wherevercode.agent.brain.oauth.OAuthRevokeResult
import wherevercode.agent.brain.oauth.OAuthTokenResponse
import wherevercode.agent.brain.oauth.UrlConnectionOAuthHttpTransport
import wherevercode.agent.brain.oauth.VendorOAuthConfigs
import java.util.UUID

/**
 * Backwards-compatible names for the pieces extracted into
 * `wherevercode.agent.brain.oauth`. Existing callers and tests keep
 * compiling unchanged; new code should use the `OAuth*` names directly.
 */
typealias SiwcPkce = OAuthPkce
typealias SiwcIdTokenVerifier = wherevercode.agent.brain.oauth.OAuthIdTokenVerifier
typealias IdTokenClaims = OAuthIdTokenClaims
typealias SiwcHttpTransport = OAuthHttpTransport
typealias SiwcHttpResponse = wherevercode.agent.brain.oauth.OAuthHttpResponse
typealias UrlConnectionSiwcHttpTransport = UrlConnectionOAuthHttpTransport
typealias RevokeResult = OAuthRevokeResult

/**
 * SIWC (Sign in with ChatGPT) authorization layer — Kotlin native OAuth 2.0 /
 * OIDC + PKCE, no UI wiring (the UI opens the authorization URL this class
 * builds, e.g. in a Custom Tab; see [AuthorizationRequest.authorizationUrl]).
 *
 * The OAuth 2.0 + PKCE protocol itself lives in the generic
 * [OAuthEngine] (`wherevercode.agent.brain.oauth`), configured by
 * [VendorOAuthConfigs.CHATGPT]. This class owns only what is ChatGPT /
 * SIWC specific: the session (token store, granted scopes, identity,
 * pending attempt, dynamic-registration bookkeeping), the loopback
 * callback orchestration, and the sign-in / refresh / revoke decisions.
 * Adding another membership vendor means adding one config entry — the
 * protocol code is not copied.
 *
 * Official sources (verified 2026-11-08; see OAUTH_NOTES.md for the full table):
 * - Registration and sign-in guide:
 *   https://developers.openai.com/siwc/token-sharing-open-source/sign-in
 * - Accounts and sessions (refresh / revoke):
 *   https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
 * - OIDC discovery document (live, fetched 2026-11-08):
 *   https://auth.openai.com/.well-known/openid-configuration
 *   issuer https://auth.openai.com,
 *   authorization_endpoint https://auth.openai.com/api/accounts/authorize,
 *   token_endpoint https://auth.openai.com/api/accounts/oauth/token,
 *   revocation_endpoint https://auth.openai.com/api/accounts/oauth/revoke,
 *   jwks_uri https://auth.openai.com/.well-known/jwks.json
 * - SIWC Terms: https://openai.com/policies/sign-in-with-chatgpt-terms/
 * - Cookbook: https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt
 *
 * Rules enforced here (SIWC Terms §1/§3.1):
 * - Only the official sign-in flow. No passwords, no session cookies, no API
 *   keys, no generic API forwarding, no sharing one user's plan with another.
 * - Tokens live only on this device, in Keystore-backed storage
 *   ([BrainKeyStore] via [SiwcSecretStore]); never on a server, never in a
 *   log, never in a URL (except `id_token_hint`, which goes only to the
 *   OpenAI authorization endpoint, per the official docs).
 * - Sign-in is user-initiated only. Nothing here silently signs the user in:
 *   [beginSignIn] must be called from an explicit user action ("Continue
 *   with ChatGPT"), and [refreshTokens] only renews an existing grant.
 * - This layer produces no AI text and makes no inference calls.
 *
 * UNVERIFIED (see OAUTH_NOTES.md — do not present as officially supported):
 * - U1: The official docs describe desktop / locally-run apps (Electron,
 *   CLI). No official SIWC document mentions Android, Custom Tabs, or a
 *   mobile client. Whether OpenAI accepts a 127.0.0.1 loopback callback from
 *   an Android app, and whether dynamic registration accepts this client,
 *   is UNVERIFIED until a real user taps "Continue with ChatGPT" on the
 *   phone (a step this task explicitly does not do).
 * - U2: The docs say "only the port may vary" for the loopback redirect URI.
 *   This implementation therefore uses an ephemeral port (port 0) and the
 *   exact path /auth/callback, with host 127.0.0.1 (never `localhost`).
 *   Whether an ephemeral port is accepted (vs the documented 1455 example)
 *   is covered by "only the port may vary", but has not been exercised
 *   against the live server.
 * - U3: Eligibility of this private-repo app for plan usage (open-source /
 *   local-personal / selected-private-app) is still UNDECIDED upstream.
 *   This code performs no eligibility check of its own; [SignInResult]
 *   reports the granted scopes so the UI can show "plan usage not enabled"
 *   when `chatgpt.tokens.use.direct` was not granted.
 */
class SiwcAuthManager(
    private val tokenStore: SiwcTokenStore,
    private val hostIdStore: HostIdStore,
    private val secrets: SiwcSecretStore,
    private val http: SiwcHttpTransport = UrlConnectionSiwcHttpTransport(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val agentName: String = DEFAULT_AGENT_NAME,
    private val callbackServerFactory: (Int) -> LoopbackCallbackServer = { port ->
        LoopbackCallbackServer(port)
    },
) {

    /**
     * The generic OAuth 2.0 + PKCE engine, configured for ChatGPT. One
     * implementation of the protocol; everything ChatGPT-specific lives
     * in [VendorOAuthConfigs.CHATGPT]. The engine owns authorization-URL
     * construction, discovery, code exchange / refresh, ID-token
     * verification, and revocation; this class owns the SIWC session
     * around it.
     */
    private val engine = OAuthEngine(
        config = VendorOAuthConfigs.CHATGPT,
        http = http,
        clockMillis = clockMillis,
        sleeper = sleeper,
    )

    /** One pending authorization attempt. Single-use: consumed on callback. */
    private data class PendingAttempt(
        val state: String,
        val nonce: String,
        val codeVerifier: String,
        val redirectUri: String,
        /** `dynamic_agent_client` for a new registration, else the issued id. */
        val clientIdUsed: String,
        val isNewRegistration: Boolean,
        val hostId: String,
        val server: LoopbackCallbackServer,
    )

    @Volatile
    private var pendingAttempt: PendingAttempt? = null
    private val signInMutex = Mutex()
    private val refreshMutex = Mutex()

    // ------------------------------------------------------------------
    // Sign-in state
    // ------------------------------------------------------------------

    /**
     * True when a usable sign-in exists: stored tokens plus either a refresh
     * token (renewable) or an unexpired access token. An expired access token
     * with no refresh token is not a sign-in.
     */
    fun isSignedIn(): Boolean {
        val tokens = tokenStore.loadTokens() ?: return false
        if (tokens.refreshToken != null) return true
        return tokens.accessTokenExpiresAtMillis > clockMillis()
    }

    /** Stored tokens, or null when signed out. Never log the returned value. */
    fun currentTokens(): SiwcTokens? = tokenStore.loadTokens()

    /** True when the granted scopes include ChatGPT plan usage. */
    fun hasSharingEnabled(): Boolean =
        loadGrantedScopes().contains(SHARING_SCOPE)

    /** Granted scopes from the last successful token response, or empty. */
    fun loadGrantedScopes(): List<String> {
        val raw = secrets.get(KEY_GRANTED_SCOPES) ?: return emptyList()
        return raw.split(" ").filter { it.isNotBlank() }
    }

    // ------------------------------------------------------------------
    // Authorization URL (pure construction; no network, no server)
    // ------------------------------------------------------------------

    /**
     * Build the authorization URL for one attempt. The UI opens this URL in
     * a Custom Tab / system browser; the manager never opens a browser itself.
     *
     * Parameters follow the official registration/sign-in guide exactly:
     * `client_id` is `dynamic_agent_client` on first registration (plus
     * `agent_name_hint` + `ext_agent_host_id`) and the issued client id on
     * reauthorization (plus `id_token_hint` / `login_hint`, no
     * `agent_name_hint`). `redirect_uri` is an http loopback URI on
     * 127.0.0.1 with path /auth/callback; `localhost` is never used.
     *
     * The URL itself is built by the generic engine; the SIWC-only parts
     * are the extra parameters passed here, in this order.
     */
    fun buildAuthorizationUrl(
        clientId: String,
        agentNameHint: String?,
        hostId: String,
        redirectUri: String,
        state: String,
        nonce: String,
        codeChallenge: String,
        idTokenHint: String?,
        loginHint: String?,
    ): String {
        val extraParams = linkedMapOf<String, String>()
        if (agentNameHint != null) extraParams["agent_name_hint"] = agentNameHint
        extraParams["ext_agent_host_id"] = hostId
        if (idTokenHint != null) extraParams["id_token_hint"] = idTokenHint
        if (loginHint != null) extraParams["login_hint"] = loginHint
        return engine.buildAuthorizationUrl(
            clientId = clientId,
            redirectUri = redirectUri,
            state = state,
            nonce = nonce,
            codeChallenge = codeChallenge,
            extraParams = extraParams,
        )
    }

    // ------------------------------------------------------------------
    // Sign-in flow (user-initiated only)
    // ------------------------------------------------------------------

    /**
     * Start a user-initiated sign-in: start the loopback callback server,
     * generate fresh state / nonce / PKCE values, and build the authorization
     * URL. The caller opens [AuthorizationRequest.authorizationUrl] (Custom
     * Tab) and then calls [completeSignIn].
     *
     * @param newProfile true to register a new account (fresh
     *   `dynamic_agent_client` registration). False reuses the saved issued
     *   client id for the signed-in account, if any.
     * @param port loopback port, 0 for an ephemeral port (default). Only the
     *   port may vary; scheme, host and path are fixed by the spec.
     * @throws BrainError.AuthFailed if another sign-in is already in progress.
     */
    suspend fun beginSignIn(
        newProfile: Boolean = false,
        port: Int = 0,
    ): AuthorizationRequest = signInMutex.withLock {
        if (pendingAttempt != null) {
            throw BrainError.AuthFailed(
                "已有进行中的 ChatGPT 登录，请先完成或取消它。",
            )
        }
        val endpoints = engine.resolveEndpoints()
        val hostId = hostIdStore.getOrCreateHostId()
        val savedTokens = tokenStore.loadTokens()
        val savedClientId = savedTokens?.clientId
            ?: secrets.get(KEY_PENDING_CLIENT_ID)
        val isNewRegistration = newProfile || savedClientId.isNullOrBlank()
        val dynamicClientId = checkNotNull(engine.config.dynamicClientId) {
            "ChatGPT OAuth 配置缺少 dynamic client id，无法注册新账号。"
        }
        val clientIdUsed =
            if (isNewRegistration) dynamicClientId else savedClientId!!
        val state = OAuthPkce.generateState()
        val nonce = OAuthPkce.generateNonce()
        val verifier = OAuthPkce.generateVerifier()
        val challenge = OAuthPkce.challengeFor(verifier)

        val server = callbackServerFactory(port)
        val actualPort = io { server.start() }
        val redirectUri =
            "http://127.0.0.1:$actualPort${engine.config.callbackPath}"

        // Reauthorization hints only: never sent on a new registration.
        val idTokenHint = if (!isNewRegistration) savedTokens?.idToken else null
        val loginHint = if (!isNewRegistration) loadIdentityEmail() else null

        val url = buildAuthorizationUrl(
            clientId = clientIdUsed,
            agentNameHint = if (isNewRegistration) agentName else null,
            hostId = hostId,
            redirectUri = redirectUri,
            state = state,
            nonce = nonce,
            codeChallenge = challenge,
            idTokenHint = idTokenHint,
            loginHint = loginHint,
        )
        pendingAttempt = PendingAttempt(
            state = state,
            nonce = nonce,
            codeVerifier = verifier,
            redirectUri = redirectUri,
            clientIdUsed = clientIdUsed,
            isNewRegistration = isNewRegistration,
            hostId = hostId,
            server = server,
        )
        AuthorizationRequest(
            authorizationUrl = url,
            redirectUri = redirectUri,
            state = state,
            nonce = nonce,
            codeVerifier = verifier,
            clientIdUsed = clientIdUsed,
            hostId = hostId,
            isNewRegistration = isNewRegistration,
            server = server,
            authorizeEndpoint = endpoints.authorizeUrl,
        )
    }

    /**
     * Wait for the loopback callback (blocks up to [timeoutMs]) and complete
     * the sign-in: validate state, exchange the code, verify the ID token,
     * save the tokens. The callback server is stopped afterwards in all cases.
     *
     * @throws BrainError.AuthFailed on state mismatch, user denial, bad code,
     *   or ID-token verification failure. [BrainError.Transport] on callback
     *   timeout or network failure.
     */
    suspend fun completeSignIn(
        request: AuthorizationRequest,
        timeoutMs: Long = DEFAULT_CALLBACK_TIMEOUT_MS,
    ): SignInResult {
        val callback = try {
            io { request.server.awaitCallback(timeoutMs) }
        } catch (e: Exception) {
            cancelPendingAttempt(request)
            throw e
        }
        return try {
            handleCallback(callback)
        } finally {
            request.server.stop()
        }
    }

    /** Cancel a pending sign-in (user backed out). Stops the callback server. */
    fun cancelSignIn() {
        val attempt = pendingAttempt
        pendingAttempt = null
        attempt?.server?.stop()
    }

    private fun cancelPendingAttempt(request: AuthorizationRequest) {
        if (pendingAttempt?.state == request.state) pendingAttempt = null
        request.server.stop()
    }

    /**
     * Handle a parsed loopback callback. Consumes the pending attempt
     * (single-use: a replayed callback is rejected because the attempt is
     * gone). Saves the issued client id BEFORE exchanging the code, so a
     * failed/expired code can still be retried with the issued id (official
     * guide §3). Verifies the ID token before saving any credentials.
     *
     * @throws BrainError.AuthFailed / [BrainError.Transport] (see
     *   [completeSignIn]).
     */
    suspend fun handleCallback(callback: LoopbackCallbackResult): SignInResult =
        signInMutex.withLock {
            val attempt = pendingAttempt
                ?: throw BrainError.AuthFailed(
                    "没有进行中的 ChatGPT 登录，无法处理回调。",
                )
            // Consume first: the same state can never complete twice.
            pendingAttempt = null
            try {
                return handleCallbackConsumed(attempt, callback)
            } finally {
                // The callback arrived (or failed validation): the loopback
                // server has served its purpose. Stopping is idempotent, so
                // completeSignIn's own finally-stop is harmless.
                attempt.server.stop()
            }
        }

    private suspend fun handleCallbackConsumed(
        attempt: PendingAttempt,
        callback: LoopbackCallbackResult,
    ): SignInResult {
        if (callback.error != null) {
            throw BrainError.AuthFailed(
                "ChatGPT 登录被拒绝或失败 (${callback.error}" +
                    (callback.errorDescription?.let { ": $it" } ?: "") + ")。",
            )
        }
            if (callback.state != attempt.state) {
                throw BrainError.AuthFailed("登录回调的 state 不匹配，已拒绝。")
            }
            val code = callback.code
                ?: throw BrainError.AuthFailed("登录回调中没有授权码。")
            val clientId: String
            if (attempt.isNewRegistration) {
                val issued = callback.clientId
                    ?: throw BrainError.AuthFailed(
                        "首次注册回调中没有签发的 client_id，注册未完成。",
                    )
                // Save the registration before exchanging the code.
                secrets.put(KEY_PENDING_CLIENT_ID, issued)
                clientId = issued
            } else {
                if (callback.clientId != null &&
                    callback.clientId != attempt.clientIdUsed
                ) {
                    throw BrainError.AuthFailed(
                        "登录回调的 client_id 与当前账号的注册不一致，已拒绝。",
                    )
                }
                clientId = attempt.clientIdUsed
            }
            val result = exchangeCode(
                code = code,
                clientId = clientId,
                codeVerifier = attempt.codeVerifier,
                redirectUri = attempt.redirectUri,
                hostId = attempt.hostId,
                previousTokens = tokenStore.loadTokens(),
                expectedNonce = attempt.nonce,
            )
            return result
        }

    // ------------------------------------------------------------------
    // Code exchange / ID-token verification
    // ------------------------------------------------------------------

    /**
     * Exchange an authorization code for tokens and verify the ID token.
     * The form POST and its error mapping are the engine's
     * [OAuthEngine.exchangeAuthorizationCode]; this method adds only the
     * SIWC-specific steps: the code grant is posted with the ISSUED client
     * id (never `dynamic_agent_client`), the code, the PKCE verifier, the
     * exact redirect URI, and the resource. No client secret is required
     * (public client, `token_endpoint_auth_methods` includes `none` per
     * the discovery document).
     */
    private suspend fun exchangeCode(
        code: String,
        clientId: String,
        codeVerifier: String,
        redirectUri: String,
        hostId: String,
        previousTokens: SiwcTokens?,
        expectedNonce: String,
    ): SignInResult {
        val endpoints = engine.resolveEndpoints()
        val params = buildMap<String, String> {
            put("grant_type", "authorization_code")
            put("client_id", clientId)
            put("code", code)
            put("code_verifier", codeVerifier)
            put("redirect_uri", redirectUri)
            engine.config.resource?.let { put("resource", it) }
        }
        val tokenResponse = engine.exchangeAuthorizationCode(
            tokenUrl = endpoints.tokenUrl,
            params = params,
            onGrantDead = ::clearLocalCredentials,
        )
        if (tokenResponse.accessToken.isBlank()) {
            throw BrainError.AuthFailed("令牌端点没有返回 access_token。")
        }
        if (tokenResponse.idToken.isNullOrBlank()) {
            throw BrainError.AuthFailed("令牌端点没有返回可验证的 ID token。")
        }
        val claims = verifyIdToken(
            idToken = tokenResponse.idToken,
            expectedAudience = clientId,
            expectedNonce = expectedNonce,
        )
        val tokens = buildTokens(
            response = tokenResponse,
            clientId = clientId,
            hostId = hostId,
            previousTokens = previousTokens,
        )
        tokenStore.saveTokens(tokens)
        saveGrantedScopes(tokenResponse.scope)
        saveIdentity(claims)
        val granted = loadGrantedScopes()
        return SignInResult(
            tokens = tokens,
            sharingEnabled = granted.contains(SHARING_SCOPE),
            subject = claims.subject,
            email = claims.email,
            grantedScopes = granted,
        )
    }

    private fun buildTokens(
        response: OAuthTokenResponse,
        clientId: String,
        hostId: String,
        previousTokens: SiwcTokens?,
    ): SiwcTokens {
        return SiwcTokens(
            accessToken = response.accessToken,
            // A refresh response may omit a rotated refresh token; keep the old one.
            refreshToken = response.refreshToken ?: previousTokens?.refreshToken,
            idToken = response.idToken ?: previousTokens?.idToken,
            clientId = clientId,
            hostId = hostId,
            accessTokenExpiresAtMillis =
                clockMillis() + response.expiresInSeconds * 1_000L,
            accountLabel = previousTokens?.accountLabel,
        )
    }

    /** Verify an ID token: RS256 signature (JWKS), issuer, audience, expiry, nonce. */
    private suspend fun verifyIdToken(
        idToken: String,
        expectedAudience: String,
        expectedNonce: String?,
    ): IdTokenClaims = engine.verifyIdToken(
        idToken = idToken,
        expectedAudience = expectedAudience,
        expectedNonce = expectedNonce,
    )

    // ------------------------------------------------------------------
    // Refresh (rotating refresh tokens, serialized)
    // ------------------------------------------------------------------

    /**
     * Return usable tokens, refreshing first when the access token is expired
     * (60 s skew margin). Refreshes for the same session are serialized so
     * two callers can never race a rotating refresh token. The latest
     * replacement is stored together with its expiry and scopes.
     *
     * Official refresh contract (profiles-and-sessions guide): POST
     * form-encoded `grant_type=refresh_token`, the ISSUED client id saved
     * with this token set (not `dynamic_agent_client`), the saved refresh
     * token, and `resource=https://api.openai.com/v1`; omit `scope` to
     * retain the grant. The grant POST itself is the engine's
     * [OAuthEngine.refreshGrant].
     *
     * @throws BrainError.NotConfigured when nothing is stored;
     *   [BrainError.AuthFailed] when the grant is dead (`invalid_grant` —
     *   the user must sign in again; stored tokens are cleared);
     *   [BrainError.Transport] on network / 5xx (stored tokens are kept).
     */
    suspend fun refreshTokens(): SiwcTokens = refreshMutex.withLock {
        val stored = tokenStore.loadTokens()
            ?: throw BrainError.NotConfigured("尚未登录 ChatGPT，请先登录。")
        if (stored.accessTokenExpiresAtMillis > clockMillis() + REFRESH_SKEW_MILLIS) {
            return stored
        }
        val refreshToken = stored.refreshToken
            ?: throw BrainError.AuthFailed("登录已过期且无 refresh_token，请重新登录。")
        val endpoints = engine.resolveEndpoints()
        val params = buildMap<String, String> {
            put("grant_type", "refresh_token")
            put("client_id", stored.clientId)
            put("refresh_token", refreshToken)
            engine.config.resource?.let { put("resource", it) }
        }
        val tokenResponse = engine.refreshGrant(
            tokenUrl = endpoints.tokenUrl,
            params = params,
            onGrantDead = ::clearLocalCredentials,
        )
        if (tokenResponse.accessToken.isBlank()) {
            throw BrainError.AuthFailed("刷新令牌没有返回新的 access_token。")
        }
        val refreshed = buildTokens(
            response = tokenResponse,
            clientId = stored.clientId,
            hostId = stored.hostId,
            previousTokens = stored,
        )
        tokenStore.saveTokens(refreshed)
        if (tokenResponse.scope.isNotBlank()) saveGrantedScopes(tokenResponse.scope)
        refreshed
    }

    /** Fresh access token for an inference call (refreshes when needed). */
    suspend fun ensureFreshAccessToken(): String = refreshTokens().accessToken

    // ------------------------------------------------------------------
    // Revoke / sign out
    // ------------------------------------------------------------------

    /**
     * Revoke the renewable session at the authorization server, then clear
     * local tokens. Uses the `revocation_endpoint` from the OIDC discovery
     * document: form-encoded POST with `token=<refresh_token>`,
     * `token_type_hint=refresh_token`, and the issued `client_id`. An empty
     * HTTP 200 is success, including for an already-invalid token.
     *
     * On network failure / 5xx, retries with backoff (up to
     * [MAX_REVOKE_ATTEMPTS]) while the refresh token is still available.
     * Local tokens are cleared even when remote revocation is unconfirmed —
     * in that case [RevokeResult.remoteRevoked] is false and the UI must tell
     * the user remote revocation was not confirmed (they can disconnect the
     * app in ChatGPT Settings). Revoking does NOT delete the registered
     * client: the issued client id and host id are retained for a later
     * sign-in, per the official guide. The retry loop is the engine's
     * [OAuthEngine.revokeToken].
     */
    suspend fun revoke(): RevokeResult {
        val stored = tokenStore.loadTokens()
            ?: return RevokeResult(remoteRevoked = false, attempts = 0)
        val endpoints = engine.resolveEndpoints()
        val (token, hint) = if (stored.refreshToken != null) {
            stored.refreshToken to "refresh_token"
        } else {
            stored.accessToken to "access_token"
        }
        val params = mapOf(
            "token" to token,
            "token_type_hint" to hint,
            "client_id" to stored.clientId,
        )
        val result = engine.revokeToken(
            revocationUrl = endpoints.revocationUrl,
            params = params,
            maxAttempts = MAX_REVOKE_ATTEMPTS,
            backoffMillis = REVOKE_BACKOFF_MILLIS,
        )
        clearLocalCredentials()
        return result
    }

    /**
     * Sign out locally only: stop using the tokens and clear them. No remote
     * call. Prefer [revoke] when the user means "disconnect"; use this when
     * the network is unavailable or the user only wants to sign out here.
     * The issued client id and host id are retained for a later sign-in.
     */
    fun signOut() {
        clearLocalCredentials()
    }

    /**
     * Forget the saved client registration entirely (e.g. the user removes
     * the account). The next sign-in registers a fresh client. The host id
     * is NOT forgotten: it identifies this installation, not the account.
     */
    fun forgetSavedRegistration() {
        clearLocalCredentials()
        secrets.delete(KEY_PENDING_CLIENT_ID)
    }

    private fun clearLocalCredentials() {
        tokenStore.clearTokens()
        secrets.delete(KEY_GRANTED_SCOPES)
        secrets.delete(KEY_IDENTITY_JSON)
    }

    // ------------------------------------------------------------------
    // Stored session metadata (granted scopes + verified identity)
    // ------------------------------------------------------------------

    private fun saveGrantedScopes(scope: String) {
        if (scope.isBlank()) return
        secrets.put(KEY_GRANTED_SCOPES, scope.trim())
    }

    private fun saveIdentity(claims: IdTokenClaims) {
        val json = JSONObject()
        json.put("sub", claims.subject)
        claims.email?.let { json.put("email", it) }
        claims.name?.let { json.put("name", it) }
        secrets.put(KEY_IDENTITY_JSON, json.toString())
    }

    private fun loadIdentityEmail(): String? {
        val raw = secrets.get(KEY_IDENTITY_JSON) ?: return null
        return try {
            JSONObject(raw).optString("email", "").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** OIDC issuer for SIWC (verified against the live discovery document). */
        const val ISSUER = CHATGPT_ISSUER

        /** Authorization endpoint (discovery `authorization_endpoint`). */
        const val AUTHORIZE_URL = CHATGPT_AUTHORIZATION_URL

        /** Token endpoint (discovery `token_endpoint`). */
        const val TOKEN_URL = CHATGPT_TOKEN_URL

        /** Revocation endpoint (discovery `revocation_endpoint`). */
        const val REVOCATION_URL = CHATGPT_REVOCATION_URL

        /** JWKS URI (discovery `jwks_uri`). */
        const val JWKS_URL = CHATGPT_JWKS_URI

        /** OIDC discovery document URL. */
        const val DISCOVERY_URL = CHATGPT_DISCOVERY_URL

        /** Scope that spends the user's ChatGPT plan (plan usage is opt-in). */
        const val SHARING_SCOPE = CHATGPT_SHARING_SCOPE

        /** `agent_name_hint` for first registration: this app's actual name. */
        const val DEFAULT_AGENT_NAME = "WhereverCode"

        /** Access tokens last 1 hour (`expires_in: 3600`) per the official docs. */
        const val DEFAULT_EXPIRES_IN_SECONDS = OAuthEngine.DEFAULT_EXPIRES_IN_SECONDS

        /** Refresh when the access token expires within this margin. */
        const val REFRESH_SKEW_MILLIS = 60_000L

        /** Callback wait: 10 minutes (same as the official DevKit's sign-in timeout). */
        const val DEFAULT_CALLBACK_TIMEOUT_MS = 10 * 60 * 1_000L

        /** Revocation retries (network failure / 5xx only). */
        const val MAX_REVOKE_ATTEMPTS = 3

        /** Base backoff between revocation attempts; multiplied by attempt number. */
        const val REVOKE_BACKOFF_MILLIS = 1_000L

        /** Secure-storage keys (reference names only, never secret values). */
        const val KEY_PENDING_CLIENT_ID = "siwc:pending-client-id"
        const val KEY_GRANTED_SCOPES = "siwc:granted-scopes"
        const val KEY_IDENTITY_JSON = "siwc:identity"

        /**
         * Build a manager with the production wiring: [BrainKeyStore]-backed
         * secrets, SharedPreferences host id, real HTTP transport. The UI
         * layer passes the application [Context]; tests use the primary
         * constructor with fakes instead.
         */
        fun create(
            context: Context,
            tokenStore: SiwcTokenStore,
            agentName: String = DEFAULT_AGENT_NAME,
        ): SiwcAuthManager {
            val appContext = context.applicationContext
            return SiwcAuthManager(
                tokenStore = tokenStore,
                hostIdStore = SharedPreferencesHostIdStore(appContext),
                secrets = BrainKeyStoreSecretStore(BrainKeyStore(appContext)),
                agentName = agentName,
            )
        }
    }
}

/** One started sign-in attempt: open [authorizationUrl], then [SiwcAuthManager.completeSignIn]. */
data class AuthorizationRequest(
    /** Full authorization URL to open in a Custom Tab / system browser. */
    val authorizationUrl: String,
    /** Exact loopback redirect URI (same value is sent in the code exchange). */
    val redirectUri: String,
    val state: String,
    val nonce: String,
    /** PKCE verifier (kept in memory only; never sent except to the token endpoint). */
    val codeVerifier: String,
    /** `dynamic_agent_client` for a new registration, else the issued id. */
    val clientIdUsed: String,
    /** Stable per-install host id (`ext_agent_host_id`, `urn:uuid:…`). */
    val hostId: String,
    val isNewRegistration: Boolean,
    /** The running loopback server; the manager stops it after the callback. */
    val server: LoopbackCallbackServer,
    /** Authorize endpoint actually used (discovery-resolved or the constant). */
    val authorizeEndpoint: String,
)

/** Result of a completed sign-in. Tokens are already saved. */
data class SignInResult(
    val tokens: SiwcTokens,
    /**
     * True only when the granted scopes include `chatgpt.tokens.use.direct`.
     * A successful identity sign-in alone does NOT grant plan usage — the UI
     * must show "plan usage not enabled" when this is false.
     */
    val sharingEnabled: Boolean,
    /** Verified ID-token subject: the stable account identity. */
    val subject: String,
    val email: String?,
    val grantedScopes: List<String>,
)

/**
 * Small secret-storage port. The production implementation is [BrainKeyStore]
 * (Keystore-backed EncryptedSharedPreferences `"wherevercode_brain_keys"`,
 * M2 [BrainKeyStore]); no new encryption is created for SIWC. Tests use an
 * in-memory fake.
 */
interface SiwcSecretStore {
    fun put(ref: String, value: String)
    fun get(ref: String): String?
    fun delete(ref: String)
}

/** [SiwcSecretStore] backed by M2's [BrainKeyStore]. */
class BrainKeyStoreSecretStore(
    private val keyStore: BrainKeyStore,
) : SiwcSecretStore {
    override fun put(ref: String, value: String) = keyStore.putSecret(ref, value)
    override fun get(ref: String): String? = keyStore.getSecret(ref)
    override fun delete(ref: String) = keyStore.deleteSecret(ref)
}

/**
 * The stable per-install host id (`ext_agent_host_id`). It is an opaque
 * identifier, not a token or proof of identity (official docs), so plain
 * SharedPreferences is the documented-equivalent storage; it is never a
 * secret and never leaves the device except to OpenAI's authorize endpoint.
 * Format: `urn:uuid:<uuid4>`, generated once and reused across restarts and
 * account switches.
 */
interface HostIdStore {
    fun getOrCreateHostId(): String
}

/** [HostIdStore] backed by plain SharedPreferences. */
class SharedPreferencesHostIdStore(context: Context) : HostIdStore {
    private val prefs = context.getSharedPreferences(
        "wherevercode_siwc_host",
        Context.MODE_PRIVATE,
    )

    override fun getOrCreateHostId(): String {
        val existing = prefs.getString(KEY_HOST_ID, null)
        if (existing != null && existing.startsWith("urn:uuid:")) return existing
        val hostId = "urn:uuid:${UUID.randomUUID()}"
        prefs.edit().putString(KEY_HOST_ID, hostId).apply()
        return hostId
    }

    companion object {
        const val KEY_HOST_ID = "ext_agent_host_id"
    }
}
