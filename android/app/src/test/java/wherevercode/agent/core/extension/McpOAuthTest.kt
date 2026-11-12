package wherevercode.agent.core.extension.mcp

import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

/**
 * MCP OAuth 2.1 端到端测试：真实本地 OAuth 服务器，无 mock。
 *
 * 服务器是本仓库自研的 minimal_oauth_server.py（stdlib only）：
 * 受保护资源元数据 → 授权服务器元数据 → /authorize 发码 →
 * /token 验 PKCE（S256，错 verifier 回 400 invalid_grant）→
 * Bearer 守着 /mcp。codeProvider 一步模拟用户在浏览器里点
 * 同意（GET authorize URL 取码）；没有浏览器也能跑。
 */
class McpOAuthTest {

    private val processes = mutableListOf<Process>()

    @Before
    fun setUp() {
        assertTrue("minimal OAuth server script missing", McpTestSupport.serverScript("minimal_oauth_server.py").exists())
    }

    @After
    fun tearDown() {
        McpTestSupport.killAll(processes)
    }

    /** 模拟用户在浏览器里点同意：GET 授权 URL，取回 JSON 里的 code。 */
    private fun browserCodeProvider(authorizationUrl: String): String? {
        val body = URL(authorizationUrl).readText()
        return JSONObject(body).optString("code", "").takeIf { it.isNotBlank() }
    }

    @Test
    fun `oauth flow on 401 gets token and tools work`() = runBlocking {
        val port = McpTestSupport.startServer(
            McpTestSupport.serverScript("minimal_oauth_server.py"), processes, "0",
        )
        val mcpUrl = "http://127.0.0.1:$port/mcp"
        val tokenStore = InMemoryMcpTokenStore()
        val authProvider = OAuthMcpAuthProvider(
            serverUrl = mcpUrl,
            clientId = "wherevercode-test",
            redirectUri = "http://127.0.0.1:9/cb",
            tokenStore = tokenStore,
            codeProvider = ::browserCodeProvider,
        )
        val client = McpClient(HttpMcpTransport(mcpUrl, authProvider = authProvider))
        try {
            // 第一次 POST 401 → OAuth 流程 → 重试成功；调用方只看到成功。
            val info = client.initialize()
            assertEquals("minimal-mcp-test", info.name)
            assertEquals("2025-11-25", info.protocolVersion)
            val stored = tokenStore.load()
            assertTrue(stored != null)
            assertTrue(stored!!.accessToken.startsWith("tok-"))
            assertTrue(stored.refreshToken?.startsWith("ref-") == true)
            val tools = client.listTools()
            assertEquals(listOf("add", "echo"), tools.map { it.name }.sorted())
            val sum = client.callTool("add", mapOf("a" to 40, "b" to 2))
            assertEquals("42", sum.text)
        } finally {
            client.close()
        }
    }

    @Test
    fun `expired token refreshes and request succeeds`() = runBlocking {
        val port = McpTestSupport.startServer(
            McpTestSupport.serverScript("minimal_oauth_server.py"), processes, "0",
        )
        val mcpUrl = "http://127.0.0.1:$port/mcp"
        val tokenStore = InMemoryMcpTokenStore()
        val authProvider = OAuthMcpAuthProvider(
            serverUrl = mcpUrl,
            clientId = "wherevercode-test",
            redirectUri = "http://127.0.0.1:9/cb",
            tokenStore = tokenStore,
            codeProvider = ::browserCodeProvider,
        )
        val client = McpClient(HttpMcpTransport(mcpUrl, authProvider = authProvider))
        try {
            client.initialize()
            val first = tokenStore.load()!!
            val firstAccess = first.accessToken
            // 模拟 access token 被吊销且已过期：下次请求 401，客户端
            // 要先走 refresh_token 换新 token 再重试。
            tokenStore.save(
                first.copy(
                    accessToken = "tok-revoked",
                    expiresAtEpochMillis = System.currentTimeMillis() - 1_000,
                ),
            )
            val tools = client.listTools()
            assertEquals(listOf("add", "echo"), tools.map { it.name }.sorted())
            val refreshed = tokenStore.load()!!
            assertNotEquals("tok-revoked", refreshed.accessToken)
            assertNotEquals(firstAccess, refreshed.accessToken)
            assertTrue(refreshed.accessToken.startsWith("tok-"))
            // refresh_token 没变（服务端复用同一条）。
            assertEquals(first.refreshToken, refreshed.refreshToken)
        } finally {
            client.close()
        }
    }

    @Test
    fun `no auth provider 401 is a clear failure not fake online`() = runBlocking {
        val port = McpTestSupport.startServer(
            McpTestSupport.serverScript("minimal_oauth_server.py"), processes, "0",
        )
        val mcpUrl = "http://127.0.0.1:$port/mcp"
        val client = McpClient(HttpMcpTransport(mcpUrl))
        try {
            client.initialize()
            error("should have thrown")
        } catch (e: McpException) {
            assertEquals("mcp_http_error", e.code)
        } finally {
            client.close()
        }
    }

    @Test
    fun `bearer header auth works without oauth flow`() = runBlocking {
        val port = McpTestSupport.startServer(
            McpTestSupport.serverScript("minimal_oauth_server.py"), processes, "0",
        )
        val mcpUrl = "http://127.0.0.1:$port/mcp"
        // 先用 OAuth 流程拿一个真 token，再用预置 Bearer 直连。
        val tokenStore = InMemoryMcpTokenStore()
        val oauth = OAuthMcpAuthProvider(
            serverUrl = mcpUrl,
            clientId = "wherevercode-test",
            redirectUri = "http://127.0.0.1:9/cb",
            tokenStore = tokenStore,
            codeProvider = ::browserCodeProvider,
        )
        val first = McpClient(HttpMcpTransport(mcpUrl, authProvider = oauth))
        try {
            first.initialize()
        } finally {
            first.close()
        }
        val token = tokenStore.load()!!.accessToken
        val bearer = McpClient(
            HttpMcpTransport(mcpUrl, authProvider = BearerTokenAuthProvider(token)),
        )
        try {
            val info = bearer.initialize()
            assertEquals("minimal-mcp-test", info.name)
            assertEquals(listOf("add", "echo"), bearer.listTools().map { it.name }.sorted())
        } finally {
            bearer.close()
        }
    }
}
