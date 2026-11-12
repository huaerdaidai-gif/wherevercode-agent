package wherevercode.agent.core.extension.mcp

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Streamable HTTP 会话模式端到端测试：真实子进程服务器，无 mock。
 *
 * 覆盖 MCP 2025-11-25 的会话行为：
 * - initialize 回 Mcp-Session-Id，后续请求带上；
 * - 会话过期（test/expire-session 钩子删掉服务端会话）时，下一次
 *   请求回 404，客户端重新 initialize 一次再重试，调用方无感；
 * - 重建后的会话是新 id，工具照常能用。
 *
 * 无会话 400 / 冒名会话 404 的服务端行为已用官方 Python SDK
 * （v1.30.0）与本仓库测试服务器双向实测，见 DELIVERY.md。
 */
class McpHttpSessionTest {

    private val processes = mutableListOf<Process>()

    @Before
    fun setUp() {
        assertTrue("minimal MCP server script missing", McpTestSupport.serverScript().exists())
    }

    @After
    fun tearDown() {
        McpTestSupport.killAll(processes)
    }

    @Test
    fun `session expiry triggers re-initialize and retry`() = runBlocking {
        val port = McpTestSupport.startServer(
            McpTestSupport.serverScript(), processes, "--http", "0",
        )
        val client = McpClient(HttpMcpTransport("http://127.0.0.1:$port/"))
        try {
            val info = client.initialize()
            assertEquals("2025-11-25", info.protocolVersion)
            assertEquals(listOf("add", "echo"), client.listTools().map { it.name }.sorted())
            val sum = client.callTool("add", mapOf("a" to 19, "b" to 23))
            assertEquals("42", sum.text)
            // 服务端删掉这个会话；下一条请求会 404。
            client.rawRequest("test/expire-session")
            // 客户端应自动重新 initialize 再重试：调用方只看到正常结果。
            val toolsAfterExpiry = client.listTools()
            assertEquals(listOf("add", "echo"), toolsAfterExpiry.map { it.name }.sorted())
            val sumAfterExpiry = client.callTool("add", mapOf("a" to 1, "b" to 2))
            assertEquals("3", sumAfterExpiry.text)
            // 重建后仍是同一台服务器、同一协议版本。
            assertEquals("minimal-mcp-test", client.serverInfo?.name)
            assertEquals("2025-11-25", client.serverInfo?.protocolVersion)
        } finally {
            client.close()
        }
    }

    @Test
    fun `two clients get independent sessions`() = runBlocking {
        val port = McpTestSupport.startServer(
            McpTestSupport.serverScript(), processes, "--http", "0",
        )
        val first = McpClient(HttpMcpTransport("http://127.0.0.1:$port/"))
        val second = McpClient(HttpMcpTransport("http://127.0.0.1:$port/"))
        try {
            first.initialize()
            second.initialize()
            assertEquals(listOf("add", "echo"), first.listTools().map { it.name }.sorted())
            assertEquals(listOf("add", "echo"), second.listTools().map { it.name }.sorted())
            // 只让第一个会话过期：第二个不受影响（会话是独立的）。
            first.rawRequest("test/expire-session")
            val secondTools = second.listTools()
            assertEquals(listOf("add", "echo"), secondTools.map { it.name }.sorted())
            // 第一个自动重建后也能用。
            val firstTools = first.listTools()
            assertEquals(listOf("add", "echo"), firstTools.map { it.name }.sorted())
        } finally {
            first.close()
            second.close()
        }
    }
}
