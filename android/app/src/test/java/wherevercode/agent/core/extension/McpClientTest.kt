package wherevercode.agent.core.extension.mcp

import java.io.File
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
 * MCP 客户端端到端测试：真实子进程服务器，无 mock。
 *
 * 服务器是本仓库自研的最小 MCP 服务器（app/src/test/resources 下
 * minimal_mcp_server.py，stdlib  only）：stdio 与 Streamable HTTP
 * 两路都跑完整流程 initialize → tools/list → tools/call，工具真返回结果。
 * CI（ubuntu-latest）自带 python3；工作流里已有 python3 -m py_compile
 * 一步，python3 可用是既定前提。
 */
class McpClientTest {

    private val processes = mutableListOf<Process>()

    @Before
    fun setUp() {
        // 服务器脚本必须存在：先 classpath 资源，再按仓库相对路径找。
        assertTrue("minimal MCP server script missing", serverScript().exists())
    }

    @After
    fun tearDown() {
        for (proc in processes) {
            try {
                proc.destroyForcibly()
                proc.waitFor()
            } catch (_: Exception) {
            }
        }
        processes.clear()
    }

    @Test
    fun `stdio full flow initialize list call returns real results`() = runBlocking {
        val client = McpClient(StdioMcpTransport(listOf("python3", serverScript().absolutePath, "--stdio")))
        try {
            val info = client.initialize()
            assertEquals("minimal-mcp-test", info.name)
            assertEquals("1", info.version)
            assertEquals("2025-11-25", info.protocolVersion)
            val tools = client.listTools()
            assertEquals(listOf("add", "echo"), tools.map { it.name }.sorted())
            val sum = client.callTool("add", mapOf("a" to 2, "b" to 3))
            assertFalse(sum.isError)
            assertEquals("5", sum.text)
            val echo = client.callTool("echo", mapOf("text" to "你好 MCP"))
            assertFalse(echo.isError)
            assertEquals("你好 MCP", echo.text)
            client.ping()
        } finally {
            client.close()
        }
    }

    @Test
    fun `stdio unknown tool returns isError instead of throwing`() = runBlocking {
        val client = McpClient(StdioMcpTransport(listOf("python3", serverScript().absolutePath, "--stdio")))
        try {
            client.initialize()
            val result = client.callTool("nosuchtool", emptyMap())
            assertTrue(result.isError)
            assertTrue(result.text.contains("nosuchtool"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `http full flow initialize list call returns real results`() = runBlocking {
        val port = startHttpServer()
        val client = McpClient(HttpMcpTransport("http://127.0.0.1:$port/"))
        try {
            val info = client.initialize()
            assertEquals("minimal-mcp-test", info.name)
            assertEquals("2025-11-25", info.protocolVersion)
            val tools = client.listTools()
            assertEquals(listOf("add", "echo"), tools.map { it.name }.sorted())
            val sum = client.callTool("add", mapOf("a" to 40, "b" to 2))
            assertFalse(sum.isError)
            assertEquals("42", sum.text)
        } finally {
            client.close()
        }
    }

    @Test
    fun `http unknown server is a clear failure not a fake online`() = runBlocking {
        val client = McpClient(HttpMcpTransport("http://127.0.0.1:1/"))
        try {
            client.initialize()
            error("should have thrown")
        } catch (e: McpException) {
            assertTrue(e.code == "mcp_connect_failed" || e.code == "mcp_http_error")
        } finally {
            client.close()
        }
    }

    @Test
    fun `manager connects to a real server and exposes prefixed brain tools`() = runBlocking {
        val registry = ExtensionRegistry(InMemoryExtensionStore())
        val script = serverScript().absolutePath
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
        assertEquals(McpServerState.UNKNOWN, manager.statusOf("test-server").state)
        // 没连之前：大脑看不到 MCP 工具。
        assertTrue(manager.toolDefs().isEmpty())
        val status = manager.connect("test-server")
        assertEquals(McpServerState.CONNECTED, status.state)
        assertEquals(2, status.toolCount)
        assertEquals("minimal-mcp-test", status.serverName)
        val defs = manager.toolDefs()
        assertEquals(
            listOf("mcp_test_server_add", "mcp_test_server_echo"),
            defs.map { it.name }.sorted(),
        )
        // 只读判定：echo/add 都不是 list/read，按本版规则一律审批。
        assertFalse(manager.isReadOnly("mcp_test_server_echo"))
        assertTrue(manager.needsApproval("mcp_test_server_echo"))
        // 经管理器真实调工具。
        val outcome = manager.execute("mcp_test_server_add", mapOf("a" to 20, "b" to 22))
        assertTrue(outcome.ok)
        assertEquals("42", outcome.data)
        manager.closeAll()
        assertEquals(McpServerState.UNKNOWN, manager.statusOf("test-server").state)
        assertTrue(manager.toolDefs().isEmpty())
    }

    @Test
    fun `manager connect failure records reason and exposes no tools`() = runBlocking {
        val registry = ExtensionRegistry(InMemoryExtensionStore())
        assertTrue(
            registry.register(
                ExtensionEntry(
                    id = "bad-server", kind = ExtensionKind.MCP, displayName = "坏服务器",
                    source = "user",
                    extra = mapOf("transport" to "stdio", "command" to "python3 /no/such/server.py"),
                ),
            ),
        )
        val manager = McpServerManager(registry)
        val status = manager.connect("bad-server")
        assertEquals(McpServerState.FAILED, status.state)
        assertTrue(status.lastError!!.isNotBlank())
        assertTrue(manager.toolDefs().isEmpty())
        val outcome = manager.execute("mcp_bad_server_add", mapOf("a" to 1, "b" to 2))
        assertFalse(outcome.ok)
        assertEquals("mcp_not_connected", outcome.errorCode)
    }

    @Test
    fun `tool name prefix round trips for ids with dashes`() {
        assertEquals("mcp_hand_mcp_stdio_list", McpServerManager.toolNameFor("hand-mcp-stdio", "list"))
        val parsed = McpServerManager.parseToolName("mcp_hand_mcp_stdio_list", setOf("hand-mcp-stdio"))
        assertEquals("hand_mcp_stdio" to "list", parsed)
    }

    private fun serverScript(): File {
        val resource = javaClass.classLoader
            ?.getResource("wherevercode/agent/core/extension/minimal_mcp_server.py")
        if (resource != null) {
            val file = File(resource.toURI())
            if (file.exists()) return file
        }
        val candidates = listOf(
            File("android/app/src/test/resources/wherevercode/agent/core/extension/minimal_mcp_server.py"),
            File("app/src/test/resources/wherevercode/agent/core/extension/minimal_mcp_server.py"),
        )
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }

    /** 起 HTTP 服务器，读 READY 行里的端口；超时抛错。 */
    private fun startHttpServer(): Int {
        val proc = ProcessBuilder(
            "python3", serverScript().absolutePath, "--http", "0",
        ).redirectErrorStream(true).start()
        processes += proc
        val reader = proc.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) error("HTTP test server exited early")
            if (reader.ready()) {
                val line = reader.readLine() ?: continue
                if (line.startsWith("READY ")) return line.removePrefix("READY ").trim().toInt()
            } else {
                Thread.sleep(50)
            }
        }
        error("HTTP test server did not print READY in time")
    }
}
