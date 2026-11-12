package wherevercode.agent.brain.siwc

import java.net.URLDecoder
import java.security.MessageDigest
import android.content.Context
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.core.extension.ExtensionEntry
import wherevercode.agent.core.extension.mcp.InMemoryMcpTokenStore
import wherevercode.agent.core.extension.mcp.McpAuthProvider
import wherevercode.agent.core.extension.mcp.McpTokenStore
import wherevercode.agent.core.extension.mcp.McpTokens
import wherevercode.agent.core.extension.mcp.OAuthMcpAuthProvider

/**
 * MCP OAuth 的 App 侧接线（MCP 2025-11-25 的 OAuth 2.1 口径）。
 *
 * 把 [OAuthMcpAuthProvider] 的两个注入点接到现成的回环流程上
 * （与 SIWC 共用 [LoopbackCallbackServer]，只绑 127.0.0.1、只接
 * 一次 `GET /auth/callback`）：
 * - `redirectUriProvider`：懒启动回环服务器，现取真实 redirect_uri
 *   （端口只有在 401 真发生时才知道；不 401 就不起监听端口）。
 * - `codeProvider`：开浏览器（调用方给的 [openUrl]，MainActivity
 *   里是 Custom Tabs）→ 等回环回调 → 校验 state → 交码。
 *
 * state 校验（OAuth 2.1）：回调回来的 state 必须等于授权 URL 里
 * 的 state；对不上就是伪造/串了的回调，码不能用，直接失败。
 * 用户拒绝（回调带 error）同样失败，不冒充成功。
 *
 * token 存取：生产 wiring 通过 [KeystoreMcpTokenStore] 复用 Android
 * Keystore 加密存储，按服务器 ID 隔离；测试仍可注入内存版。
 *
 * 线程：[awaitCode] 在 Dispatchers.IO 上等回调；[openUrl] 由调用
 * 方负责切到主线程（Custom Tabs 要 Activity 上下文）。
 */
class McpOAuthWiring(
    private val openUrl: (String) -> Unit,
    private val callbackServerFactory: () -> LoopbackCallbackServer = { LoopbackCallbackServer() },
    private val callbackTimeoutMs: Long = DEFAULT_CALLBACK_TIMEOUT_MS,
) {
    @Volatile
    private var callbackServer: LoopbackCallbackServer? = null

    /**
     * 每次授权现取 redirect_uri：第一次调用时懒启动回环服务器，
     * 返回它的真实地址（端口是现绑的临时端口）。授权 URL 与换
     * token 用的是同一个（见 OAuthMcpAuthProvider）。
     */
    @Synchronized
    fun redirectUri(): String {
        val server = callbackServer ?: callbackServerFactory().also { callbackServer = it }
        if (!server.isRunning) server.start()
        return "http://127.0.0.1:${server.port}${SiwcBrainAdapter.SIWC_CALLBACK_PATH}"
    }

    /**
     * 取授权码：开浏览器，等回环回调（最多 [callbackTimeoutMs]），
     * 校验 state 与 error 后交码；拿不到码返回 null（OAuth 流程失败）。
     * 无论成功失败，等完即停回环服务器，不留监听端口。
     */
    suspend fun awaitCode(authorizationUrl: String): String? = withContext(Dispatchers.IO) {
        val expectedState = parseQueryParam(authorizationUrl, "state")
        val server = callbackServer ?: return@withContext null
        try {
            openUrl(authorizationUrl)
        } catch (_: Exception) {
            return@withContext null
        }
        val result = try {
            server.awaitCallback(callbackTimeoutMs)
        } catch (_: Exception) {
            null
        } finally {
            close()
        } ?: return@withContext null
        if (result.error != null) return@withContext null
        val code = result.code
        if (code.isNullOrBlank()) return@withContext null
        // state 对不上：伪造/串了的回调，码不能用。
        if (expectedState != null && result.state != expectedState) return@withContext null
        code
    }

    /** 停回环服务器（客户端断开、或等完回调后调）。幂等。 */
    @Synchronized
    fun close() {
        try {
            callbackServer?.stop()
        } catch (_: Exception) {
        }
        callbackServer = null
    }

    companion object {
        /** 等用户在浏览器里点完授权的最长等待：5 分钟。 */
        const val DEFAULT_CALLBACK_TIMEOUT_MS: Long = 5 * 60 * 1_000L

        /** 从 URL 的 query 里取一个参数（未 URL 解码的值先解码）。 */
        fun parseQueryParam(url: String, name: String): String? {
            val query = url.substringAfter("?", "")
            if (query.isBlank()) return null
            for (pair in query.split("&")) {
                if (pair.isEmpty()) continue
                val key = pair.substringBefore("=")
                if (key == name) {
                    return try {
                        URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8")
                    } catch (_: Exception) {
                        pair.substringAfter("=", "")
                    }
                }
            }
            return null
        }
    }
}

/**
 * 按注册表行建 MCP OAuth 认证方（App 侧工厂，见 BrainBootstrap）。
 *
 * 约定（注册表 extra 里配，用户在 MCP 服务器表单里填）：
 * - `oauth=true` 才走 OAuth；否则返回 null（走配置头的 Bearer /
 *   X-Api-Key 认证，或无认证）。
 * - `oauthClientId`：OAuth 客户端 id（不填默认 `wherevercode-agent`；
 *   支持动态注册的服务端可先走 DCR 拿到 id 再填）。
 * - `oauthScopes`：空格分隔的 scope（可空）。
 *
 * 注意：OAuth 认证方是按服务器建的（一个服务器一个 wiring、一个
 * 回环服务器），不在多台服务器之间共用。
 */
fun mcpOAuthProviderFor(
    entry: ExtensionEntry,
    serverUrl: String,
    wiring: McpOAuthWiring,
    tokenStore: McpTokenStore = InMemoryMcpTokenStore(),
): McpAuthProvider? {
    if (entry.extra["oauth"] != "true") return null
    val clientId = entry.extra["oauthClientId"]?.takeIf { it.isNotBlank() } ?: "wherevercode-agent"
    val scopes = entry.extra["oauthScopes"]?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
    return OAuthMcpAuthProvider(
        serverUrl = serverUrl,
        clientId = clientId,
        // 构造值不用：redirectUriProvider 每次授权现取真实地址。
        redirectUri = "",
        scopes = scopes,
        tokenStore = tokenStore,
        codeProvider = { url -> wiring.awaitCode(url) },
        redirectUriProvider = { wiring.redirectUri() },
        onClose = { wiring.close() },
    )
}

/** Keystore encrypted MCP OAuth token store, partitioned by MCP server ID. */
class KeystoreMcpTokenStore(
    private val secrets: McpSecretStore,
    serverId: String,
) : McpTokenStore {
    private val authRef = "mcp-oauth:" + MessageDigest.getInstance("SHA-256")
        .digest(serverId.toByteArray(Charsets.UTF_8))
        .copyOfRange(0, 16)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    override fun load(): McpTokens? {
        val raw = secrets.get(authRef) ?: return null
        return try {
            val json = JSONObject(raw)
            val access = json.optString("access_token", "")
            if (access.isBlank()) return null
            McpTokens(
                accessToken = access,
                refreshToken = json.optString("refresh_token", "").ifBlank { null },
                expiresAtEpochMillis = if (json.has("expires_at_ms")) json.optLong("expires_at_ms") else null,
                tokenType = json.optString("token_type", "Bearer").ifBlank { "Bearer" },
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun save(tokens: McpTokens) {
        val json = JSONObject().apply {
            put("access_token", tokens.accessToken)
            tokens.refreshToken?.let { put("refresh_token", it) }
            tokens.expiresAtEpochMillis?.let { put("expires_at_ms", it) }
            put("token_type", tokens.tokenType)
        }
        secrets.put(authRef, json.toString())
    }

    override fun clear() = secrets.delete(authRef)

    companion object {
        fun create(context: Context, serverId: String): KeystoreMcpTokenStore =
            KeystoreMcpTokenStore(BrainMcpSecretStore(BrainKeyStore(context.applicationContext)), serverId)
    }
}

/** Secret port keeps OAuth token serialization testable without Android Keystore. */
interface McpSecretStore {
    fun put(authRef: String, value: String)
    fun get(authRef: String): String?
    fun delete(authRef: String)
}

private class BrainMcpSecretStore(private val keys: BrainKeyStore) : McpSecretStore {
    override fun put(authRef: String, value: String) = keys.putSecret(authRef, value)
    override fun get(authRef: String): String? = keys.getSecret(authRef)
    override fun delete(authRef: String) = keys.deleteSecret(authRef)
}
