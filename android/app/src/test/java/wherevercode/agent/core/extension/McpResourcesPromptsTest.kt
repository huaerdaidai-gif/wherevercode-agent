package wherevercode.agent.core.extension.mcp

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.extension.ExtensionEntry
import wherevercode.agent.core.extension.ExtensionKind
import wherevercode.agent.core.extension.ExtensionRegistry
import wherevercode.agent.core.extension.InMemoryExtensionStore

/**
 * MCP resources / prompts 端到端测试：真实子进程服务器，无 mock。
 *
 * 服务器是本仓库自研的最小 MCP 服务器（minimal_mcp_server.py）：
 * 资源 test://note、test://numbers 是真内容，提示 greet/summarize
 * 是真模板；订阅 + test/update-resource 钩子验证
 * notifications/resources/updated。--legacy 模式模拟老 Hand
 * 服务器（2024-11-05、只声明 tools）：客户端要按空列表处理，
 * 不能因为 -32601 就把连接搞垮。
 */
class McpResourcesPromptsTest {

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
    fun `stdio resources list read returns real content`() = runBlocking {
        val client = McpClient(StdioMcpTransport(listOf("python3", McpTestSupport.serverScript().absolutePath, "--stdio")))
        try {
            val info = client.initialize()
            assertEquals("2025-11-25", info.protocolVersion)
            assertTrue(client.supportsResources())
            val resources = client.listResources()
            assertEquals(listOf("test://note", "test://numbers"), resources.map { it.uri })
            assertEquals("note", resources.first { it.uri == "test://note" }.name)
            val contents = client.readResource("test://note")
            assertEquals(1, contents.size)
            assertEquals("test://note", contents[0].uri)
            assertEquals("hello resource\n", contents[0].text)
            val numbers = client.readResource("test://numbers")
            assertEquals("1,2,3", numbers[0].text)
        } finally {
            client.close()
        }
    }

    @Test
    fun `stdio prompts list get fills arguments`() = runBlocking {
        val client = McpClient(StdioMcpTransport(listOf("python3", McpTestSupport.serverScript().absolutePath, "--stdio")))
        try {
            client.initialize()
            assertTrue(client.supportsPrompts())
            val prompts = client.listPrompts()
            assertEquals(listOf("greet", "summarize"), prompts.map { it.name }.sorted())
            val greet = prompts.first { it.name == "greet" }
            assertEquals(listOf("name"), greet.arguments.map { it.name })
            assertTrue(greet.arguments.first().required)
            val result = client.getPrompt("greet", mapOf("name" to "maintainer"))
            assertEquals(1, result.messages.size)
            assertEquals("user", result.messages[0].role)
            assertEquals("Hello, maintainer!", result.messages[0].text)
            val summary = client.getPrompt("summarize")
            assertTrue(summary.messages[0].text.contains("summarize"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `stdio subscribe then update fires onResourceUpdated`() = runBlocking {
        val client = McpClient(StdioMcpTransport(listOf("python3", McpTestSupport.serverScript().absolutePath, "--stdio")))
        try {
            client.initialize()
            assertTrue(client.supportsResourceSubscribe())
            var updatedUri: String? = null
            client.onResourceUpdated = { updatedUri = it }
            client.subscribeResource("test://note")
            assertTrue(client.subscribedResources.contains("test://note"))
            client.rawRequest("test/update-resource", mapOf("uri" to "test://note"))
            assertTrue(
                "expected onResourceUpdated(test://note)",
                McpTestSupport.waitUntil { updatedUri == "test://note" },
            )
            client.unsubscribeResource("test://note")
            assertFalse(client.subscribedResources.contains("test://note"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `manager connect lists resources and prompts with counts`() = runBlocking {
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
        val manager = McpServerManager(registry)
        val status = manager.connect("test-server")
        assertEquals(McpServerState.CONNECTED, status.state)
        assertEquals(2, status.toolCount)
        assertEquals(2, status.resourceCount)
        assertEquals(2, status.promptCount)
        assertEquals(listOf("test://note", "test://numbers"), manager.resources("test-server").map { it.uri })
        assertEquals(listOf("greet", "summarize"), manager.prompts("test-server").map { it.name }.sorted())
        val contents = manager.readResource("test-server", "test://note")
        assertEquals("hello resource\n", contents[0].text)
        val prompt = manager.getPrompt("test-server", "greet", mapOf("name" to "maintainer"))
        assertEquals("Hello, maintainer!", prompt.messages[0].text)
        manager.closeAll()
    }

    @Test
    fun `legacy server without resources prompts still connects`() = runBlocking {
        val client = McpClient(StdioMcpTransport(listOf("python3", McpTestSupport.serverScript().absolutePath, "--stdio", "--legacy")))
        try {
            val info = client.initialize()
            // 老服务器报旧版本：如实记录，不拒连。
            assertEquals("2024-11-05", info.protocolVersion)
            assertFalse(client.supportsResources())
            assertFalse(client.supportsPrompts())
            // 没声明的能力直接返回空列表，不发请求、不抛错。
            assertTrue(client.listResources().isEmpty())
            assertTrue(client.listPrompts().isEmpty())
            // tools 照常能用。
            val tools = client.listTools()
            assertEquals(listOf("add", "echo"), tools.map { it.name }.sorted())
            val sum = client.callTool("add", mapOf("a" to 1, "b" to 2))
            assertEquals("3", sum.text)
        } finally {
            client.close()
        }
    }

    @Test
    fun `manager connect to legacy server reports zero resources prompts`() = runBlocking {
        val registry = ExtensionRegistry(InMemoryExtensionStore())
        val script = McpTestSupport.serverScript().absolutePath
        assertTrue(
            registry.register(
                ExtensionEntry(
                    id = "legacy-server", kind = ExtensionKind.MCP, displayName = "老服务器",
                    source = "user",
                    extra = mapOf("transport" to "stdio", "command" to "python3 $script --stdio --legacy"),
                ),
            ),
        )
        val manager = McpServerManager(registry)
        val status = manager.connect("legacy-server")
        assertEquals(McpServerState.CONNECTED, status.state)
        assertEquals(2, status.toolCount)
        assertEquals(0, status.resourceCount)
        assertEquals(0, status.promptCount)
        assertTrue(manager.resources("legacy-server").isEmpty())
        assertTrue(manager.prompts("legacy-server").isEmpty())
        manager.closeAll()
    }
}
