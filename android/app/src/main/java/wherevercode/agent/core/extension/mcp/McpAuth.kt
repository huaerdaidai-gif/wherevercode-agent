package wherevercode.agent.core.extension.mcp

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * MCP 远程服务器认证（MCP 2025-11-25 的 OAuth 2.1 口径 + 预置 Bearer）。
 *
 * 主流做法（连接器设计报告 B5、SPEC §2.1）：
 * - 简单情况：配置里 `headers.Authorization: Bearer <token>`，由
 *   [BearerTokenAuthProvider] 直接带上（token 本身只存本机 Keystore，
 *   配置里只放 `keystore:<引用名>`，由调用方在建 provider 时解出来）。
 * - 正式 OAuth：服务端回 401 + `WWW-Authenticate: Bearer
 *   resource_metadata="..."`，客户端按 MCP 的 OAuth 2.1 流程走：
 *   受保护资源元数据 → 授权服务器元数据 → 授权码 + PKCE → 换 token
 *   → 重试请求；access token 过期用 refresh_token 换。
 *
 * [OAuthMcpAuthProvider] 只实现协议部分：取授权码那步（开浏览器、
 * 用户确认、回环收码）由调用方以 `codeProvider` 注入——App 侧用
 * 现有的 OAuth 回环流程接，测试里用跟随重定向取码。token 的存取
 * 由 [McpTokenStore] 注入（App 侧接 Keystore，测试用内存版）。
 * 没接认证提供方时 401 就是 `mcp_http_error`，不伪装已认证。
 */

/** MCP 认证提供方：每次请求前问它拿请求头；401 时问它处理。 */
interface McpAuthProvider {
    /** 本次请求要带的认证请求头（无 token 时返回空 map）。 */
    suspend fun authHeaders(): Map<String, String>

    /**
     * 服务端回 401 时调用。返回 true = 已拿到（或刷新出）有效 token，
     * 调用方应重试一次本次请求；false = 处理不了，调用方按未认证报错。
     * `wwwAuthenticate` 是 401 回包的 WWW-Authenticate 头（可空）。
     */
    suspend fun handleUnauthorized(wwwAuthenticate: String?): Boolean

    /**
     * 释放认证方持有的资源（OAuth 回环服务器等）。默认无事可做；
     * 传输层 close() 时调。客户端断开后认证方不应再留监听端口。
     */
    fun close() {}
}

/** 预置 Bearer token（配置里的 Authorization 头已解出明文时用）。 */
class BearerTokenAuthProvider(private val token: String) : McpAuthProvider {
    override suspend fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer $token")

    override suspend fun handleUnauthorized(wwwAuthenticate: String?): Boolean = false
}

/**
 * API Key 请求头认证（主流做法：`X-Api-Key: <key>`，或厂商自定义
 * 头名，如 `X-API-Key`、`Api-Key`）。
 *
 * 配置里的 headers 有 `X-Api-Key`（大小写不限）时由
 * [authProviderForHeaders] 自动建成这个提供方——401 时不重试
 * （API Key 换不了，只能由用户在配置里换 key），只负责每次请求
 * 带上头。key 本身只存本机 Keystore，配置里只放
 * `keystore:<引用名>`，由调用方在建提供方时解出来。
 */
class ApiKeyAuthProvider(
    private val headerName: String = "X-Api-Key",
    private val apiKey: String,
) : McpAuthProvider {
    override suspend fun authHeaders(): Map<String, String> =
        mapOf(headerName to apiKey)

    override suspend fun handleUnauthorized(wwwAuthenticate: String?): Boolean = false
}

data class McpTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMillis: Long? = null,
    val tokenType: String = "Bearer",
) {
    fun isExpired(nowEpochMillis: Long = System.currentTimeMillis()): Boolean {
        val exp = expiresAtEpochMillis ?: return false
        // 提前 30 秒算过期，避免边缘过期。
        return nowEpochMillis + 30_000L >= exp
    }
}

/** token 存取（App 侧接 Keystore；测试用内存版）。 */
interface McpTokenStore {
    fun load(): McpTokens?
    fun save(tokens: McpTokens)
    fun clear()
}

class InMemoryMcpTokenStore : McpTokenStore {
    @Volatile
    private var tokens: McpTokens? = null
    override fun load(): McpTokens? = tokens
    override fun save(tokens: McpTokens) {
        this.tokens = tokens
    }
    override fun clear() {
        tokens = null
    }
}

/**
 * MCP OAuth 2.1 认证提供方（自主实现，不引 OAuth 库）。
 *
 * 流程（401 触发）：
 * 1. 从 WWW-Authenticate 里取 `resource_metadata` URL（没有就按
 *    MCP 默认路径 `/.well-known/oauth-protected-resource` + 服务器
 *    地址猜一个）；GET 受保护资源元数据，取 `authorization_servers[0]`。
 * 2. GET 授权服务器元数据（`authorization_servers[0]` + 
 *    `/.well-known/oauth-authorization-server`，或元数据里直接给的
 *    `authorization_endpoint`/`token_endpoint`）。
 * 3. 生成 PKCE（code_verifier 随机 64 字符，code_challenge =
 *    BASE64URL(SHA-256(verifier))），拼授权 URL 交给 `codeProvider`
 *    取授权码（App 侧：开浏览器 + 回环收码；拿不到码返回 null 即失败）。
 * 4. POST token_endpoint 换 token（grant_type=authorization_code，
 *    带 code_verifier），存进 [McpTokenStore]，返回 true。
 *
 * 已有 refresh_token 且 access token 过期时，先走 refresh 流程
 * （grant_type=refresh_token），失败再走完整授权流程。
 */
class OAuthMcpAuthProvider(
    private val serverUrl: String,
    private val clientId: String,
    private val redirectUri: String,
    private val scopes: List<String> = emptyList(),
    private val tokenStore: McpTokenStore = InMemoryMcpTokenStore(),
    private val codeProvider: suspend (authorizationUrl: String) -> String?,
    private val httpGet: suspend (url: String, headers: Map<String, String>) -> Pair<Int, String> = ::defaultHttpGet,
    private val httpPostForm: suspend (url: String, form: Map<String, String>) -> Pair<Int, String> = ::defaultHttpPostForm,
    /** 每次授权现取 redirect_uri（回环服务器懒启动时用；不传则用 [redirectUri]）。 */
    private val redirectUriProvider: (() -> String)? = null,
    /** 认证方关闭时调（App 侧：停回环服务器；不传则无事可做）。 */
    private val onClose: (() -> Unit)? = null,
) : McpAuthProvider {

    @Volatile
    private var metadata: OAuthServerMetadata? = null

    override suspend fun authHeaders(): Map<String, String> {
        val tokens = tokenStore.load() ?: return emptyMap()
        if (tokens.accessToken.isBlank()) return emptyMap()
        return mapOf("Authorization" to "${tokens.tokenType} ${tokens.accessToken}")
    }

    override suspend fun handleUnauthorized(wwwAuthenticate: String?): Boolean {
        // 先试 refresh（已有 refresh_token 且 access token 过期）。
        val existing = tokenStore.load()
        if (existing?.refreshToken != null && existing.isExpired()) {
            if (tryRefresh(existing.refreshToken)) return true
        }
        return try {
            val meta = metadata ?: discover(wwwAuthenticate).also { metadata = it }
            val code = requestAuthorizationCode(meta) ?: return false
            exchangeCode(meta, code)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun tryRefresh(refreshToken: String): Boolean {
        return try {
            val meta = metadata ?: discover(null).also { metadata = it }
            val form = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            )
            val (status, body) = httpPostForm(meta.tokenEndpoint, form)
            if (status !in 200..299) return false
            val tokens = parseTokens(body, refreshToken) ?: return false
            tokenStore.save(tokens)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 发现授权服务器：受保护资源元数据 → 授权服务器元数据。 */
    private suspend fun discover(wwwAuthenticate: String?): OAuthServerMetadata {
        val resourceMetadataUrl = parseWwwAuthenticateResourceMetadata(wwwAuthenticate)
            ?: defaultResourceMetadataUrl(serverUrl)
        val (status, body) = httpGet(resourceMetadataUrl, emptyMap())
        if (status !in 200..299) {
            throw McpException("取 OAuth 受保护资源元数据失败（HTTP $status）", "mcp_oauth_discovery_failed")
        }
        val resourceMeta = JSONObject(body)
        val authServers = resourceMeta.optJSONArray("authorization_servers")
        val authServer = authServers?.optString(0, "")?.takeIf { it.isNotBlank() }
            ?: throw McpException("OAuth 受保护资源元数据里没有 authorization_servers", "mcp_oauth_discovery_failed")
        return fetchAuthorizationServerMetadata(authServer)
    }

    private suspend fun fetchAuthorizationServerMetadata(issuer: String): OAuthServerMetadata {
        // 先按标准 well-known 路径取；元数据里也可能直接带端点。
        val wellKnown = issuer.trimEnd('/') + "/.well-known/oauth-authorization-server"
        val (status, body) = httpGet(wellKnown, emptyMap())
        if (status !in 200..299) {
            throw McpException("取 OAuth 授权服务器元数据失败（HTTP $status）", "mcp_oauth_discovery_failed")
        }
        val meta = JSONObject(body)
        val authEndpoint = meta.optString("authorization_endpoint", "")
        val tokenEndpoint = meta.optString("token_endpoint", "")
        if (authEndpoint.isBlank() || tokenEndpoint.isBlank()) {
            throw McpException("OAuth 授权服务器元数据缺 authorization_endpoint/token_endpoint", "mcp_oauth_discovery_failed")
        }
        return OAuthServerMetadata(
            issuer = meta.optString("issuer", issuer),
            authorizationEndpoint = authEndpoint,
            tokenEndpoint = tokenEndpoint,
        )
    }

    private suspend fun requestAuthorizationCode(meta: OAuthServerMetadata): String? {
        val verifier = generateCodeVerifier()
        val challenge = codeChallengeS256(verifier)
        // OAuth 2.1 要求 state：随机值，codeProvider 负责校验回调回来
        // 的 state 与这里发出去的是否一致（见 McpOAuthWiring）。
        val state = generateState()
        val redirect = try {
            redirectUriProvider?.invoke() ?: redirectUri
        } catch (_: Exception) {
            return null
        }
        val query = buildString {
            append("response_type=code")
            append("&client_id=").append(urlEncode(clientId))
            append("&redirect_uri=").append(urlEncode(redirect))
            if (scopes.isNotEmpty()) append("&scope=").append(urlEncode(scopes.joinToString(" ")))
            append("&code_challenge=").append(challenge)
            append("&code_challenge_method=S256")
            append("&state=").append(state)
            append("&resource=").append(urlEncode(serverUrl))
        }
        val authorizationUrl = meta.authorizationEndpoint +
            (if (meta.authorizationEndpoint.contains("?")) "&" else "?") + query
        val code = codeProvider(authorizationUrl) ?: return null
        // 换 token 需要同一个 verifier 与 redirect_uri：暂存在本次调用
        // 的闭包里——用成员字段传（单次授权流程串行，不并发）。
        pendingVerifier = verifier
        pendingRedirectUri = redirect
        return code
    }

    @Volatile
    private var pendingVerifier: String? = null

    @Volatile
    private var pendingRedirectUri: String? = null

    override fun close() {
        try {
            onClose?.invoke()
        } catch (_: Exception) {
        }
    }

    private suspend fun exchangeCode(meta: OAuthServerMetadata, code: String): Boolean {
        val verifier = pendingVerifier
        val redirect = pendingRedirectUri
        pendingVerifier = null
        pendingRedirectUri = null
        if (verifier.isNullOrBlank() || redirect.isNullOrBlank()) return false
        val form = mutableMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirect,
            "client_id" to clientId,
            "code_verifier" to verifier,
        )
        val (status, body) = httpPostForm(meta.tokenEndpoint, form)
        if (status !in 200..299) return false
        val tokens = parseTokens(body, null) ?: return false
        tokenStore.save(tokens)
        return true
    }

    private fun parseTokens(body: String, fallbackRefreshToken: String?): McpTokens? {
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        val access = json.optString("access_token", "")
        if (access.isBlank()) return null
        val expiresIn = json.optLong("expires_in", -1)
        val expiresAt = if (expiresIn > 0) {
            System.currentTimeMillis() + expiresIn * 1000L
        } else {
            null
        }
        val refresh = json.optString("refresh_token", "").takeIf { it.isNotBlank() } ?: fallbackRefreshToken
        return McpTokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochMillis = expiresAt,
            tokenType = json.optString("token_type", "Bearer").takeIf { it.isNotBlank() } ?: "Bearer",
        )
    }

    companion object {
        data class OAuthServerMetadata(
            val issuer: String,
            val authorizationEndpoint: String,
            val tokenEndpoint: String,
        )

        /** WWW-Authenticate: Bearer resource_metadata="..." 里取元数据地址。 */
        fun parseWwwAuthenticateResourceMetadata(wwwAuthenticate: String?): String? {
            if (wwwAuthenticate.isNullOrBlank()) return null
            val match = Regex("""resource_metadata\s*=\s*"([^"]+)"""").find(wwwAuthenticate)
            return match?.groupValues?.getOrNull(1)
        }

        fun defaultResourceMetadataUrl(serverUrl: String): String {
            val base = serverUrl.trimEnd('/')
            return "$base/.well-known/oauth-protected-resource"
        }

        fun generateCodeVerifier(): String {
            val bytes = ByteArray(48)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        /** OAuth 2.1 state：随机 32 字节，授权 URL 与回调比对用。 */
        fun generateState(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun codeChallengeS256(verifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}

/** 默认 HTTP GET（HttpURLConnection，IO 线程）。返回（状态码、响应体）。 */
private suspend fun defaultHttpGet(url: String, headers: Map<String, String>): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            status to body
        } finally {
            conn.disconnect()
        }
    }

/** 默认 HTTP POST（application/x-www-form-urlencoded）。返回（状态码、响应体）。 */
private suspend fun defaultHttpPostForm(url: String, form: Map<String, String>): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        val body = form.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val respBody = stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            status to respBody
        } finally {
            conn.disconnect()
        }
    }
