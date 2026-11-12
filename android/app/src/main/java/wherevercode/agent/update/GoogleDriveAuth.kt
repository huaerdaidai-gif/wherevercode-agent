package wherevercode.agent.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.brain.oauth.OAuthEngine
import wherevercode.agent.brain.oauth.OAuthHttpTransport
import wherevercode.agent.brain.oauth.OAuthPkce
import wherevercode.agent.brain.oauth.VendorOAuthConfig
import wherevercode.agent.brain.siwc.BrainError
import wherevercode.agent.brain.siwc.LoopbackCallbackResult
import wherevercode.agent.brain.siwc.LoopbackCallbackServer
import wherevercode.agent.brain.siwc.SiwcSecretStore

/**
 * Google Drive 更新用的 OAuth 2.0 登录（Google 账号，只读 Drive）。
 *
 * 复用 SIWC 的通用 OAuth 引擎（[OAuthEngine]：PKCE、code 换 token、
 * refresh、revoke、ID token 验证只实现一次）与回环回调服务器
 * ([LoopbackCallbackServer]：绑 127.0.0.1，路径 /auth/callback——Google
 * 与 SIWC 用同一路径、不同端口，各自起停，互不干扰）。
 *
 * 与 SIWC 不同的一点：Google 没有动态注册，client id 是固定的——由
 * maintainer 在 Google Cloud Console 建一次（类型：桌面应用），填进 App
 * 设置。客户端 ID 是公开值（不是密钥），存在普通 SharedPreferences。
 *
 * 客户端密钥（client_secret）必须填：Google 的桌面应用客户端在令牌
 * 端点强制要求提供 client_secret，即使走了 PKCE——2026-11-11 真机
 * 实测，不带密钥换码必报 `invalid_request: client_secret is
 * missing.`（Google 文档把密钥标成“可选”，端点实际强制要求；
 * 这是 Google 特有的行为，不是 OAuth 规范的要求）。密钥只存本机
 * Keystore（[KeystoreGoogleDriveClientSecretStore]），绝不进代码、
 * 聊天、日志；设置页只显示“已配 / 未配”，绝不显示密钥值。授权时
 * 额外带 `access_type=offline&prompt=consent`，否则 Google 只在第
 * 一次授权发 refresh token，之后静默刷新拿不到。
 *
 * 端点以 Google 官方 OIDC discovery 文档为准（2026-11-10 实测
 * `https://accounts.google.com/.well-known/openid-configuration`）：
 * issuer `https://accounts.google.com`，authorize
 * `https://accounts.google.com/o/oauth2/v2/auth`，token
 * `https://oauth2.googleapis.com/token`，revoke
 * `https://oauth2.googleapis.com/revoke`，jwks
 * `https://www.googleapis.com/oauth2/v3/certs`；PKCE S256 在
 * `code_challenge_methods_supported` 里。
 */

/** Google OAuth 的固定配置（公开端点元数据，不含任何密钥）。 */
val GOOGLE_DRIVE_OAUTH_CONFIG: VendorOAuthConfig = VendorOAuthConfig(
    vendorId = "google-drive-update",
    displayName = "Google Drive",
    operatorName = "Google",
    issuer = "https://accounts.google.com",
    discoveryUrl = "https://accounts.google.com/.well-known/openid-configuration",
    authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth",
    tokenUrl = "https://oauth2.googleapis.com/token",
    revocationUrl = "https://oauth2.googleapis.com/revoke",
    jwksUri = "https://www.googleapis.com/oauth2/v3/certs",
    scopes = listOf("openid", "email", DRIVE_READONLY_SCOPE),
    resource = null,
    // Google 没有动态注册：client id 固定，由用户在设置里填（见 GoogleDriveAuth）。
    dynamicClientId = null,
    callbackPath = "/auth/callback",
    sharingScope = null,
)

/** 一次待完成的 Drive 登录（单次使用：回调后即消费，重放会被拒）。 */
data class DriveAuthorizationRequest(
    val authorizationUrl: String,
    val redirectUri: String,
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val clientId: String,
    val server: LoopbackCallbackServer,
)

/** 已存的 Drive 授权（Keystore 里一条 JSON）。 */
data class GoogleDriveTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val clientId: String,
    val accessTokenExpiresAtMillis: Long,
    val accountLabel: String?,
)

/** Drive token 的本地存储（只存 token 本身）。 */
interface GoogleDriveTokenStore {
    fun saveTokens(tokens: GoogleDriveTokens)
    fun loadTokens(): GoogleDriveTokens?
    fun clearTokens()
}

/**
 * [GoogleDriveTokenStore] 的 Keystore 实现（与 SIWC 同一个
 * [BrainKeyStore]，不另建加密）。损坏或不完整的记录读作 null（视为未
 * 登录），下次成功登录覆盖。
 *
 * 懒建 [BrainKeyStore]：它的构造器里就建 EncryptedSharedPreferences
 * + MasterKey，构造时就碰 AndroidKeyStore。Robolectric 里没有
 * AndroidKeyStore，构造时建会在设置页 bind 时炸掉 Activity 启动
 * （CI 第二/三轮 5 败的根因）。所以构造时只留工厂，第一次真正用时
 * 才建；建失败时读当未登录、清尽力、存报明确错误。
 */
class KeystoreGoogleDriveTokenStore(
    private val secretsFactory: () -> SiwcSecretStore,
) : GoogleDriveTokenStore {

    @Volatile
    private var secrets: SiwcSecretStore? = null

    @Volatile
    private var secretsBroken: Boolean = false

    /** 第一次用时才建 BrainKeyStore；建失败返回 null（当作未登录）。 */
    private fun secretsOrNull(): SiwcSecretStore? {
        secrets?.let { return it }
        if (secretsBroken) return null
        return try {
            val created = secretsFactory()
            secrets = created
            created
        } catch (_: Exception) {
            secretsBroken = true
            null
        }
    }

    override fun saveTokens(tokens: GoogleDriveTokens) {
        val store = secretsOrNull()
            ?: throw IllegalStateException("本机安全存储不可用，无法保存 Google Drive 登录信息。")
        store.put(AUTH_REF, toJson(tokens))
    }

    override fun loadTokens(): GoogleDriveTokens? {
        return try {
            val raw = secretsOrNull()?.get(AUTH_REF) ?: return null
            fromJson(raw)
        } catch (_: Exception) {
            // Keystore 不可用时当作未登录，不让一次读操作炸掉设置页。
            null
        }
    }

    override fun clearTokens() {
        try {
            secretsOrNull()?.delete(AUTH_REF)
        } catch (_: Exception) {
            // 尽力清理：删不掉也不挡主流程（下次登录会覆盖）。
        }
    }

    private fun toJson(tokens: GoogleDriveTokens): String {
        val json = JSONObject()
        json.put(FIELD_ACCESS_TOKEN, tokens.accessToken)
        tokens.refreshToken?.let { json.put(FIELD_REFRESH_TOKEN, it) }
        tokens.idToken?.let { json.put(FIELD_ID_TOKEN, it) }
        json.put(FIELD_CLIENT_ID, tokens.clientId)
        json.put(FIELD_EXPIRES_AT_MILLIS, tokens.accessTokenExpiresAtMillis)
        tokens.accountLabel?.let { json.put(FIELD_ACCOUNT_LABEL, it) }
        return json.toString()
    }

    private fun fromJson(raw: String): GoogleDriveTokens? {
        val json = JSONObject(raw)
        val accessToken = json.optString(FIELD_ACCESS_TOKEN, "")
        val clientId = json.optString(FIELD_CLIENT_ID, "")
        if (accessToken.isBlank() || clientId.isBlank()) return null
        if (!json.has(FIELD_EXPIRES_AT_MILLIS)) return null
        return GoogleDriveTokens(
            accessToken = accessToken,
            refreshToken = json.optString(FIELD_REFRESH_TOKEN, "").ifBlank { null },
            idToken = json.optString(FIELD_ID_TOKEN, "").ifBlank { null },
            clientId = clientId,
            accessTokenExpiresAtMillis = json.optLong(FIELD_EXPIRES_AT_MILLIS),
            accountLabel = json.optString(FIELD_ACCOUNT_LABEL, "").ifBlank { null },
        )
    }

    companion object {
        /** Drive token 在 BrainKeyStore 里的固定引用名。 */
        const val AUTH_REF = "drive:tokens"

        const val FIELD_ACCESS_TOKEN = "access_token"
        const val FIELD_REFRESH_TOKEN = "refresh_token"
        const val FIELD_ID_TOKEN = "id_token"
        const val FIELD_CLIENT_ID = "client_id"
        const val FIELD_EXPIRES_AT_MILLIS = "expires_at_millis"
        const val FIELD_ACCOUNT_LABEL = "account_label"

        /** Production wiring：Keystore-backed secrets（懒建，见类注释）。 */
        fun create(context: Context): KeystoreGoogleDriveTokenStore {
            val appContext = context.applicationContext
            return KeystoreGoogleDriveTokenStore {
                wherevercode.agent.brain.siwc.BrainKeyStoreSecretStore(
                    BrainKeyStore(appContext),
                )
            }
        }
    }
}

/**
 * Drive OAuth 客户端密钥的本地存储（只存密钥本身）。
 *
 * 根因（2026-11-11 真机 + 令牌端点实测）：Google 的桌面应用客户端
 * 在令牌端点强制要求提供 client_secret，即使走了 PKCE；不带密钥换
 * 码必报 `invalid_request: client_secret is missing.`，刷新同样。
 * 所以密钥必须存在——存在本机 Keystore，与 token 同一个
 * [BrainKeyStore]，不另建加密；密钥值永不出现在界面文案、日志、
 * 报错里，设置页只显示“已配 / 未配”。
 */
interface GoogleDriveClientSecretStore {
    fun saveSecret(secret: String)
    fun loadSecret(): String?
    fun clearSecret()
}

/**
 * [GoogleDriveClientSecretStore] 的 Keystore 实现。懒建
 * [BrainKeyStore] 的原因与 [KeystoreGoogleDriveTokenStore] 相同
 * （构造时就碰 AndroidKeyStore，Robolectric 里会炸设置页）；
 * 建失败时读当作未配、存报明确错误。
 */
class KeystoreGoogleDriveClientSecretStore(
    private val secretsFactory: () -> SiwcSecretStore,
) : GoogleDriveClientSecretStore {

    @Volatile
    private var secrets: SiwcSecretStore? = null

    @Volatile
    private var secretsBroken: Boolean = false

    /** 第一次用时才建 BrainKeyStore；建失败返回 null（当作未配）。 */
    private fun secretsOrNull(): SiwcSecretStore? {
        secrets?.let { return it }
        if (secretsBroken) return null
        return try {
            val created = secretsFactory()
            secrets = created
            created
        } catch (_: Exception) {
            secretsBroken = true
            null
        }
    }

    override fun saveSecret(secret: String) {
        val store = secretsOrNull()
            ?: throw IllegalStateException("本机安全存储不可用，无法保存 Google OAuth 客户端密钥。")
        store.put(SECRET_REF, secret)
    }

    override fun loadSecret(): String? {
        return try {
            secretsOrNull()?.get(SECRET_REF)?.ifBlank { null }
        } catch (_: Exception) {
            // Keystore 不可用时当作未配，不让一次读操作炸掉设置页。
            null
        }
    }

    override fun clearSecret() {
        try {
            secretsOrNull()?.delete(SECRET_REF)
        } catch (_: Exception) {
            // 尽力清理：删不掉也不挡主流程（下次保存会覆盖）。
        }
    }

    companion object {
        /** 客户端密钥在 BrainKeyStore 里的固定引用名。 */
        const val SECRET_REF = "drive:client_secret"

        /** Production wiring：Keystore-backed secrets（懒建，见类注释）。 */
        fun create(context: Context): KeystoreGoogleDriveClientSecretStore {
            val appContext = context.applicationContext
            return KeystoreGoogleDriveClientSecretStore {
                wherevercode.agent.brain.siwc.BrainKeyStoreSecretStore(
                    BrainKeyStore(appContext),
                )
            }
        }
    }
}

/**
 * Google Drive 登录的管理器：开始登录（建授权 URL，调用方用 Custom Tab
 * 打开）→ 等回调完成登录（验 state、换 code、验 ID token、存 token）
 * → 取可用 access token（过期自动 refresh）→ 退出（revoke + 清本地）。
 *
 * 失败一律转成 [AppUpdateError]（[BrainError] 是大脑层的错误类型，
 * 更新层不直接往外抛它；消息里不含任何 token 值）。
 *
 * 失败诊断不吞细节：换码 / 验 ID token / 刷新失败时，用户看到的状态行
 * 里有失败步骤和 Google 回的具体错误码（如 invalid_grant），同一份
 * 诊断也写进 logcat（[Log.e]，步骤 + 错误码，不含任何 token 值）——
 * 2026-11-11 真机上只显示一句“授权已失效”，外面根本看不出是哪一步
 * 死的、是哪个错误码，就是因为这里曾把引擎消息整个换成固定文案。
 */
class GoogleDriveAuth(
    private val engine: OAuthEngine,
    private val tokenStore: GoogleDriveTokenStore,
    private val clientSecretStore: GoogleDriveClientSecretStore,
    private val callbackServerFactory: (Int) -> LoopbackCallbackServer = { port ->
        LoopbackCallbackServer(port)
    },
    private val clockMillis: () -> Long = System::currentTimeMillis,
    /**
     * 失败诊断的出口：步骤 + 错误码 + 引擎原文。默认写 logcat；测试
     * 注入捕获用的 lambda（纯 JVM 单测里 android.util.Log 是 Stub，
     * 直接调会炸）。消息里永远不含 token 值。
     */
    private val logError: (String) -> Unit = { message -> Log.e(TAG, message) },
) {
    private val signInMutex = Mutex()
    private var pendingAttempt: PendingDriveAttempt? = null

    private data class PendingDriveAttempt(
        val state: String,
        val nonce: String,
        val codeVerifier: String,
        val redirectUri: String,
        val clientId: String,
        val server: LoopbackCallbackServer,
    )

    fun loadTokens(): GoogleDriveTokens? = tokenStore.loadTokens()

    fun isSignedIn(): Boolean = loadTokens() != null

    /** 客户端密钥是否已配（只报有无，永不返回密钥值）。 */
    fun isClientSecretConfigured(): Boolean =
        !clientSecretStore.loadSecret().isNullOrBlank()

    /** 存客户端密钥（设置页填入时调；只存本机 Keystore）。 */
    fun saveClientSecret(secret: String) = clientSecretStore.saveSecret(secret)

    /** 清客户端密钥（设置页清除时调）。 */
    fun clearClientSecret() = clientSecretStore.clearSecret()

    /**
     * 换码 / 刷新用的客户端密钥。Google 的桌面应用客户端在令牌端点
     * 强制要求提供 client_secret（即使走了 PKCE），缺了就报
     * invalid_request——2026-11-11 真机就是死在这里。未配时直接
     * 报明，不发请求（发了也是同一个错，还白白消耗一次 code）。
     */
    private fun requireClientSecret(step: String): String {
        val secret = clientSecretStore.loadSecret()?.ifBlank { null }
        if (secret != null) return secret
        val message = "Google Drive 登录失败（$step）：Google 换取令牌需要客户端密钥，" +
            "请在设置里填写客户端密钥（Google Cloud Console 里该桌面应用客户端的密钥，" +
            "以 GOCSPX- 开头，只存本机 Keystore），请重新连接。"
        logError("Google Drive 授权失败：步骤=$step，客户端密钥未配。")
        throw AppUpdateError.AuthFailed(message)
    }

    /**
     * 开始一次用户发起的登录：起回环回调服务器，生成 state / nonce /
     * PKCE，建授权 URL。调用方用 Custom Tab 打开返回的 URL，再调
     * [completeSignIn] 等回调。
     */
    suspend fun beginSignIn(
        clientId: String,
        port: Int = 0,
    ): DriveAuthorizationRequest = signInMutex.withLock {
        require(clientId.isNotBlank()) { "Google OAuth 客户端 ID 不能为空" }
        if (pendingAttempt != null) {
            throw AppUpdateError.AuthFailed("已有进行中的 Google Drive 登录，请先完成或取消它。")
        }
        val endpoints = try {
            engine.resolveEndpoints()
        } catch (e: BrainError) {
            throw mapBrainError(e, step = "解析服务端点")
        }
        val state = OAuthPkce.generateState()
        val nonce = OAuthPkce.generateNonce()
        val verifier = OAuthPkce.generateVerifier()
        val challenge = OAuthPkce.challengeFor(verifier)
        val server = callbackServerFactory(port)
        val actualPort = withContext(Dispatchers.IO) { server.start() }
        val redirectUri = "http://127.0.0.1:$actualPort${engine.config.callbackPath}"
        val url = engine.buildAuthorizationUrl(
            clientId = clientId,
            redirectUri = redirectUri,
            state = state,
            nonce = nonce,
            codeChallenge = challenge,
            // offline：要 refresh token 才能静默刷新；consent：强制再发一次 refresh token。
            extraParams = mapOf("access_type" to "offline", "prompt" to "consent"),
        )
        pendingAttempt = PendingDriveAttempt(
            state = state,
            nonce = nonce,
            codeVerifier = verifier,
            redirectUri = redirectUri,
            clientId = clientId,
            server = server,
        )
        DriveAuthorizationRequest(
            authorizationUrl = url,
            redirectUri = redirectUri,
            state = state,
            nonce = nonce,
            codeVerifier = verifier,
            clientId = clientId,
            server = server,
        )
    }

    /**
     * 等回环回调（最多 [timeoutMs]）并完成登录：验 state、换 code、验
     * ID token、存 token。回调服务器在所有情况下都会停掉。
     */
    suspend fun completeSignIn(
        request: DriveAuthorizationRequest,
        timeoutMs: Long = DEFAULT_CALLBACK_TIMEOUT_MS,
    ): GoogleDriveTokens {
        val callback = try {
            withContext(Dispatchers.IO) { request.server.awaitCallback(timeoutMs) }
        } catch (e: Exception) {
            cancelPendingAttempt(request)
            throw mapBrainError(e, step = "等待回调")
        }
        try {
            return handleCallback(request, callback)
        } finally {
            request.server.stop()
        }
    }

    /** 取消一次进行中的登录（用户关掉了 Custom Tab）。停回调服务器。 */
    fun cancelSignIn() {
        val attempt = pendingAttempt
        pendingAttempt = null
        attempt?.server?.stop()
    }

    private fun cancelPendingAttempt(request: DriveAuthorizationRequest) {
        if (pendingAttempt?.state == request.state) pendingAttempt = null
        request.server.stop()
    }

    private suspend fun handleCallback(
        request: DriveAuthorizationRequest,
        callback: LoopbackCallbackResult,
    ): GoogleDriveTokens {
        val attempt = pendingAttempt
        pendingAttempt = null
        if (attempt == null || attempt.state != request.state) {
            logError("Google Drive 登录已过期或被重复提交，已拒绝。")
            throw AppUpdateError.AuthFailed("Google Drive 登录已过期或被重复提交，请重新登录。")
        }
        if (callback.error != null) {
            logError(
                "Google Drive 登录被拒：${callback.error}" +
                    (callback.errorDescription?.let { "（$it）" } ?: ""),
            )
            throw AppUpdateError.AuthFailed(
                "Google Drive 登录被拒绝或失败（${callback.error}" +
                    (callback.errorDescription?.let { ": $it" } ?: "") + "）。",
            )
        }
        if (callback.state != attempt.state) {
            logError("Google Drive 登录回调的 state 不符，已拒绝。")
            throw AppUpdateError.AuthFailed("Google Drive 登录回调的 state 不符，已拒绝。")
        }
        val code = callback.code
        if (code.isNullOrBlank()) {
            logError("Google Drive 登录没有返回授权码。")
            throw AppUpdateError.AuthFailed("Google Drive 登录没有返回授权码。")
        }
        val endpoints = try {
            engine.resolveEndpoints()
        } catch (e: BrainError) {
            throw mapBrainError(e, step = "解析服务端点")
        }
        // Google 的桌面应用客户端在令牌端点强制要求 client_secret
        // （即使走了 PKCE）：缺了换码必报 invalid_request。未配时
        // requireClientSecret 直接报明，不发请求。
        val clientSecret = requireClientSecret(step = "换取令牌")
        val tokenResponse = try {
            engine.exchangeAuthorizationCode(
                tokenUrl = endpoints.tokenUrl,
                params = mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to attempt.redirectUri,
                    "client_id" to attempt.clientId,
                    "code_verifier" to attempt.codeVerifier,
                    "client_secret" to clientSecret,
                ),
                onGrantDead = { tokenStore.clearTokens() },
            )
        } catch (e: BrainError) {
            // 诊断：换码失败时记下请求的参数名清单（只记名字，不记
            // 任何 code / token / 密钥的值），下次直接定位是缺了
            // 哪个参数，而不是对着一个错误码猜。
            logError(
                "Google Drive 换取令牌请求参数名：grant_type、code、" +
                    "redirect_uri、client_id、code_verifier、client_secret" +
                    "（客户端密钥已配）。",
            )
            throw mapBrainError(e, step = "换取令牌")
        }
        // ID token 验过才存：签名、签发方、受众（本次 client id）、过期、nonce 全验。
        val claims = tokenResponse.idToken?.let { idToken ->
            try {
                engine.verifyIdToken(
                    idToken = idToken,
                    expectedAudience = attempt.clientId,
                    expectedNonce = attempt.nonce,
                )
            } catch (e: BrainError) {
                throw mapBrainError(e, step = "验证 ID token")
            }
        }
        val tokens = GoogleDriveTokens(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken,
            idToken = tokenResponse.idToken,
            clientId = attempt.clientId,
            accessTokenExpiresAtMillis = clockMillis() +
                tokenResponse.expiresInSeconds * 1_000L,
            accountLabel = claims?.email,
        )
        tokenStore.saveTokens(tokens)
        return tokens
    }

    /**
     * 取一个可用的 access token：未过期直接用；过期且有 refresh token
     * 就刷新（refresh 响应没给新 refresh token 时沿用旧的）；没
     * refresh token 或刷新报 invalid_grant → 清本地并抛 AuthFailed
     * （要重新登录）。
     */
    suspend fun ensureAccessToken(clientId: String): String {
        val saved = tokenStore.loadTokens()
            ?: throw AppUpdateError.NotSignedIn()
        if (saved.clientId != clientId) {
            // 换了客户端 ID：旧授权不属于这个客户端，不能用。
            tokenStore.clearTokens()
            throw AppUpdateError.NotSignedIn("Google OAuth 客户端 ID 换了，请重新连接 Google Drive。")
        }
        if (clockMillis() + REFRESH_SKEW_MILLIS < saved.accessTokenExpiresAtMillis) {
            return saved.accessToken
        }
        val refreshToken = saved.refreshToken
            ?: throw AppUpdateError.AuthFailed("Google Drive 授权已过期（没有 refresh token），请重新连接。")
        val endpoints = try {
            engine.resolveEndpoints()
        } catch (e: BrainError) {
            throw mapBrainError(e, step = "解析服务端点")
        }
        // 刷新同样强制要求 client_secret（实测：不带密钥刷新也报
        // invalid_request: client_secret is missing.）。
        val clientSecret = requireClientSecret(step = "刷新令牌")
        val response = try {
            engine.refreshGrant(
                tokenUrl = endpoints.tokenUrl,
                params = mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                ),
                onGrantDead = { tokenStore.clearTokens() },
            )
        } catch (e: BrainError) {
            logError(
                "Google Drive 刷新令牌请求参数名：grant_type、refresh_token、" +
                    "client_id、client_secret（客户端密钥已配）。",
            )
            throw mapBrainError(e, step = "刷新令牌")
        }
        val refreshed = saved.copy(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken ?: saved.refreshToken,
            idToken = response.idToken ?: saved.idToken,
            accessTokenExpiresAtMillis = clockMillis() +
                response.expiresInSeconds * 1_000L,
        )
        tokenStore.saveTokens(refreshed)
        return refreshed.accessToken
    }

    /** 退出：先在服务端作废 access token（失败也不阻塞），再清本地。 */
    suspend fun signOut() {
        val saved = tokenStore.loadTokens()
        if (saved != null) {
            try {
                val endpoints = engine.resolveEndpoints()
                val revokeParams = mutableMapOf(
                    "token" to saved.accessToken,
                    "token_type_hint" to "access_token",
                    "client_id" to saved.clientId,
                )
                // 有密钥就带上（与换码 / 刷新一致）；没配也不挡退出。
                clientSecretStore.loadSecret()?.ifBlank { null }?.let {
                    revokeParams["client_secret"] = it
                }
                engine.revokeToken(
                    revocationUrl = endpoints.revocationUrl,
                    params = revokeParams,
                    maxAttempts = 2,
                    backoffMillis = 500L,
                )
            } catch (_: Exception) {
                // 撤销失败不阻塞退出：本地清掉就是退出了。
            }
        }
        tokenStore.clearTokens()
        pendingAttempt?.server?.stop()
        pendingAttempt = null
    }

    /**
     * 把引擎错误转成更新层的错误。关键：不吞细节——AuthFailed 的用户文案
     * 里保留失败步骤和 Google 回的具体错误码（[BrainError.AuthFailed.errorCode]，
     * 如 invalid_grant），同一份诊断写进 logcat。消息里永远不含 token 值：
     * 引擎与验签消息里只有错误码、HTTP 状态和中文说明。
     */
    private fun mapBrainError(e: Exception, step: String? = null): AppUpdateError {
        val mapped = when (e) {
            is AppUpdateError -> e
            is BrainError.Transport -> AppUpdateError.Transport(
                withStep(step, e.message ?: "连接 Google 失败。"),
            )
            is BrainError.AuthFailed -> {
                // invalid_grant 类：授权已死，清本地。
                tokenStore.clearTokens()
                AppUpdateError.AuthFailed(authFailedMessage(e, step))
            }
            is BrainError -> AppUpdateError.Transport(
                withStep(step, e.message ?: "Google 登录失败。"),
            )
            else -> AppUpdateError.Transport(
                withStep(step, e.message ?: "Google 登录失败。"),
            )
        }
        logError(
            "Google Drive 授权失败：步骤=${step ?: "未知"}" +
                (if (e is BrainError.AuthFailed) "，错误码=${e.errorCode ?: "无"}" else "") +
                "，${e.message ?: e.javaClass.simpleName}",
        )
        return mapped
    }

    /** 步骤前缀：有步骤就加“（步骤）”，否则原样。 */
    private fun withStep(step: String?, message: String): String =
        if (step == null) message else "（$step）$message"

    /**
     * AuthFailed 的用户文案：步骤 + 引擎原文（已含错误码时不再重复贴码）
     * + “请重新连接”。引擎原文末尾的“请重新登录。”去掉，避免一句话里
     * 出现两个动作。
     */
    private fun authFailedMessage(e: BrainError.AuthFailed, step: String?): String {
        val code = e.errorCode
        val base = (e.message ?: "授权已失效")
            .removeSuffix("，请重新登录。")
            .removeSuffix("。")
            .ifBlank { "授权已失效" }
        val detail = if (code != null && !base.contains(code)) "$base（$code）" else base
        return "Google Drive 登录失败" +
            (step?.let { "（$it）" } ?: "") +
            "：$detail，请重新连接。"
    }

    companion object {
        /** logcat 标签。 */
        private const val TAG = "GoogleDriveAuth"
        /** 等回调的默认超时（与 SIWC 一致：10 分钟）。 */
        const val DEFAULT_CALLBACK_TIMEOUT_MS = 600_000L

        /** 提前 60 秒刷新 access token，避免用到一半过期。 */
        const val REFRESH_SKEW_MILLIS = 60_000L

        /** Production wiring：引擎 + Keystore token 存储 + Keystore 密钥存储。 */
        fun create(
            context: Context,
            http: OAuthHttpTransport,
        ): GoogleDriveAuth {
            return GoogleDriveAuth(
                engine = OAuthEngine(GOOGLE_DRIVE_OAUTH_CONFIG, http),
                tokenStore = KeystoreGoogleDriveTokenStore.create(context),
                clientSecretStore = KeystoreGoogleDriveClientSecretStore.create(context),
            )
        }
    }
}
