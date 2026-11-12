package wherevercode.agent.core.extension.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.extension.ExtensionKind
import wherevercode.agent.core.extension.ExtensionRegistry
import wherevercode.agent.core.extension.InMemoryExtensionStore

/**
 * mcpServers JSON 配置格式测试（纯解析/校验，无服务器、无 mock）。
 *
 * 主流格式（MCP 官方文档、连接器设计报告 B5、SPEC §2.1）：
 * {"mcpServers": {"name": {"command", "args", "env"} |
 *                          {"url", "headers"}}}
 * 不要用 VS Code 的 servers 键，远程地址键用 url。
 */
class McpServersConfigTest {

    private val json = """
        {
          "mcpServers": {
            "fs": {
              "command": "npx",
              "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
              "env": {"FS_ROOT": "/tmp"},
              "cwd": "/tmp"
            },
            "remote": {
              "url": "https://example.com/mcp",
              "headers": {"Authorization": "Bearer abc"}
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parse accepts mcpServers stdio and remote`() {
        val parsed = McpServersConfig.parse(json)
        assertEquals(2, parsed.size)
        val fs = parsed.getValue("fs")
        assertEquals(McpServerTransport.STDIO, fs.transport)
        assertEquals("npx", fs.command)
        assertEquals(
            listOf("-y", "@modelcontextprotocol/server-filesystem", "/tmp"),
            fs.args,
        )
        assertEquals(mapOf("FS_ROOT" to "/tmp"), fs.env)
        assertEquals("/tmp", fs.cwd)
        val remote = parsed.getValue("remote")
        assertEquals(McpServerTransport.REMOTE, remote.transport)
        assertEquals("https://example.com/mcp", remote.url)
        assertEquals(mapOf("Authorization" to "Bearer abc"), remote.headers)
    }

    @Test
    fun `parse rejects VS Code servers key`() {
        try {
            McpServersConfig.parse("""{"servers": {"x": {"command": "true"}}}""")
            error("should have thrown")
        } catch (e: McpConfigException) {
            assertTrue(e.message!!.contains("mcpServers"))
        }
    }

    @Test
    fun `parse rejects missing root key`() {
        try {
            McpServersConfig.parse("""{"other": {}}""")
            error("should have thrown")
        } catch (e: McpConfigException) {
            assertTrue(e.message!!.contains("mcpServers"))
        }
    }

    @Test
    fun `parse rejects command and url together`() {
        try {
            McpServersConfig.parse(
                """{"mcpServers": {"x": {"command": "true", "url": "https://example.com/mcp"}}}""",
            )
            error("should have thrown")
        } catch (e: McpConfigException) {
            assertTrue(e.message!!.contains("二选一"))
        }
    }

    @Test
    fun `parse rejects neither command nor url`() {
        try {
            McpServersConfig.parse("""{"mcpServers": {"x": {"args": []}}}""")
            error("should have thrown")
        } catch (e: McpConfigException) {
            assertTrue(e.message!!.contains("command"))
        }
    }

    @Test
    fun `parse rejects bad url`() {
        try {
            McpServersConfig.parse("""{"mcpServers": {"x": {"url": "not a url"}}}""")
            error("should have thrown")
        } catch (e: McpConfigException) {
            assertTrue(e.message!!.contains("url"))
        }
    }

    @Test
    fun `export round trips through parse`() {
        val entries = McpServersConfig.parse(json)
        val exported = McpServersConfig.export(entries)
        val reparsed = McpServersConfig.parse(exported)
        assertEquals(entries, reparsed)
    }

    @Test
    fun `resolveValue expands env vars and keystore refs`() {
        val resolver = object : McpCredentialResolver {
            override fun resolve(ref: String): String? =
                if (ref == "my-token") "SECRET-123" else null
        }
        assertEquals("SECRET-123", McpServersConfig.resolveValue("keystore:my-token", resolver))
        assertEquals("plain", McpServersConfig.resolveValue("plain", resolver))
        // 环境变量：PATH 在 CI/本地都有。
        val path = System.getenv("PATH") ?: ""
        if (path.isNotBlank()) {
            assertEquals(path, McpServersConfig.resolveValue("\${PATH}", resolver))
        }
        // 解不出的 ${VAR} 原样保留（不编造值、不抛错）。
        assertEquals(
            "\${DEFINITELY_NOT_SET_12345}",
            McpServersConfig.resolveValue("\${DEFINITELY_NOT_SET_12345}", resolver),
        )
        // 解不出的 keystore 引用抛错，不当明文用。
        try {
            McpServersConfig.resolveValue("keystore:missing-ref", resolver)
            error("should have thrown")
        } catch (e: McpException) {
            assertEquals("mcp_credential_unresolved", e.code)
        }
    }

    @Test
    fun `importToRegistry and exportFromRegistry round trip`() {
        val registry = ExtensionRegistry(InMemoryExtensionStore())
        val imported = McpServersConfig.importToRegistry(json, registry)
        // JSONObject 的键顺序不保证：只比集合，不比顺序。
        assertEquals(listOf("fs", "remote"), imported.sorted())
        // 注册表里本来就有一条内置 Hand MCP 行，导进两条后一共三条。
        assertEquals(3, registry.all(ExtensionKind.MCP).size)
        val fsEntry = registry.get(ExtensionKind.MCP, "fs")!!
        assertEquals("stdio", fsEntry.extra["transport"])
        assertEquals("npx", fsEntry.extra["command"])
        assertTrue(fsEntry.extra["argsJson"]!!.contains("server-filesystem"))
        assertTrue(fsEntry.extra["envJson"]!!.contains("FS_ROOT"))
        assertEquals("/tmp", fsEntry.extra["cwd"])
        val remoteEntry = registry.get(ExtensionKind.MCP, "remote")!!
        assertEquals("http", remoteEntry.extra["transport"])
        assertEquals("https://example.com/mcp", remoteEntry.extra["url"])
        assertTrue(remoteEntry.extra["headersJson"]!!.contains("Bearer abc"))
        val exported = McpServersConfig.exportFromRegistry(registry)
        val reparsed = McpServersConfig.parse(exported)
        // 导出含内置 Hand MCP 行：只比本次导进的两条。
        val expected = McpServersConfig.parse(json)
        assertEquals(expected["fs"], reparsed["fs"])
        assertEquals(expected["remote"], reparsed["remote"])
    }
}
