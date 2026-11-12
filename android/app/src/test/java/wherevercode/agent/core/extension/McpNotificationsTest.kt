package wherevercode.agent.core.extension.mcp

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.extension.ExtensionEntry
import wherevercode.agent.core.extension.ExtensionKind
import wherevercode.agent.core.extension.ExtensionRegistry
import wherevercode.agent.core.extension.InMemoryExtensionStore

/**
 * MCP 通知端到端测试：真实子进程服务器，无 mock。
 *
 * 服务器收到 test/add-tool 钩子后加一个 "bonus" 工具，并发出
 * notifications/tools/list_changed。客户端收到通知要自动重拉
 * 工具列表；管理器通过 onCatalogRefreshed 把缓存同步回来。
 * HTTP 路走 GET SSE 流（服务端主动消息通道）；stdio 路走分发线程。
 */
class McpNotificationsTest {

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
    fun `stdio tools list_changed auto refreshes tool cache`() = runBlocking {
        val client = McpClient(StdioMcpTransport(listOf("python3", McpTestSupport.serverScript().absolutePath, "--stdio")))
        try {
            client.initialize()
            assertEquals(listOf("add", "echo"), client.listTools().map { it.name }.sorted())
            var seenNotification: String? = null
            client.onNotification = { seenNotification = it.method }
            client.rawRequest("test/add-tool")
            assertTrue(
                "expected notifications/tools/list_changed",
                McpTestSupport.waitUntil { seenNotification == "notifications/tools/list_changed" },
            )
            assertTrue(
                "expected auto-refreshed tools to include bonus",
                McpTestSupport.waitUntil { client.tools.any { it.name == "bonus" } },
            )
            assertEquals(listOf("add", "bonus", "echo"), client.tools.map { it.name }.sorted())
            // 新工具真能调。
            val result = client.callTool("bonus", emptyMap())
            assertEquals("bonus tool works", result.text)
        } finally {
            client.close()
        }
    }

    @Test
    fun `manager syncs caches when server notifies tool change`() = runBlocking {
        val registry = ExtensionRegistry(InMemoryExtensionStore())
        val script = McpTestSupport.serverScript().absolutePath
        assertTrue(
            registry.register(
                ExtensionEntry(
                    id = "test-server", kind = ExtensionKind.MCP, displayName = "测试服务器",
                    source = "user",
                    extra = mapOf("transport" to "stdio", "command" to "python3 $script --stdio"),
                ),
            ),
        )
        var captured: McpClient? = null
        val manager = McpServerManager(
            registry,
            clientFactory = { entry -> defaultClient(entry).also { captured = it } },
        )
        val status = manager.connect("test-server")
        assertEquals(McpServerState.CONNECTED, status.state)
        assertEquals(2, status.toolCount)
        val client = captured ?: error("client not captured")
        client.rawRequest("test/add-tool")
        assertTrue(
            "expected manager toolCount to become 3 after notification",
            McpTestSupport.waitUntil { manager.statusOf("test-server").toolCount == 3 },
        )
        assertTrue(manager.toolDefs().any { it.name == "mcp_test_server_bonus" })
        manager.closeAll()
    }

    @Test
    fun `http tools list_changed arrives over GET stream and refreshes`() = runBlocking {
        val port = McpTestSupport.startServer(
            McpTestSupport.serverScript(), processes, "--http", "0",
        )
        val client = McpClient(HttpMcpTransport("http://127.0.0.1:$port/"))
        try {
            client.initialize()
            assertEquals(listOf("add", "echo"), client.listTools().map { it.name }.sorted())
            var seenNotification: String? = null
            client.onNotification = { seenNotification = it.method }
            client.rawRequest("test/add-tool")
            assertTrue(
                "expected notifications/tools/list_changed over GET stream",
                McpTestSupport.waitUntil(timeoutMs = 10_000) {
                    seenNotification == "notifications/tools/list_changed"
                },
            )
            assertTrue(
                "expected auto-refreshed tools to include bonus",
                McpTestSupport.waitUntil(timeoutMs = 10_000) {
                    client.tools.any { it.name == "bonus" }
                },
            )
        } finally {
            client.close()
        }
    }
}
