package wherevercode.agent.core.extension

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.Action
import wherevercode.agent.brain.ToolContext
import wherevercode.agent.brain.ToolOutcome
import wherevercode.agent.core.extension.mcp.McpToolSource
import wherevercode.agent.core.extension.plugin.LocalUtilsPlugin
import wherevercode.agent.core.extension.plugin.PluginRegistry

/**
 * 扩展工具桥测试（纯 JVM，插件真实、MCP 用替身来源）。
 *
 * 断言：两路工具名互不冲突、只读/审批判定按来源、校验与执行
 * 按前缀分流、未知工具报未知而不执行。MCP 的真实往返见 McpClientTest。
 */
class ExtensionToolBridgeTest {

    private class FakeMcp(
        private val defs: List<wherevercode.agent.brain.ToolDef> = listOf(
            wherevercode.agent.brain.ToolDef(
                name = "mcp_test_server_add",
                description = "fake add",
                inputSchema = mapOf("type" to "object"),
            ),
        ),
    ) : McpToolSource {
        var executed: Pair<String, Map<String, Any?>>? = null
        var connected = true

        override fun toolDefs(): List<wherevercode.agent.brain.ToolDef> =
            if (connected) defs else emptyList()

        override fun isReadOnly(toolName: String): Boolean = toolName.endsWith("_list")
        override fun needsApproval(toolName: String): Boolean = !isReadOnly(toolName)

        override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolOutcome {
            executed = toolName to args
            return ToolOutcome(ok = true, summary = "fake mcp ok", data = "42")
        }
    }

    private fun bridge(fakeMcp: FakeMcp = FakeMcp()): ExtensionToolBridge {
        val plugins = PluginRegistry(isEnabled = { true })
        plugins.register(LocalUtilsPlugin())
        return ExtensionToolBridge(plugins, fakeMcp)
    }

    private fun action(tool: String, args: Map<String, Any?> = emptyMap()): Action =
        Action(actionId = "test-1", tool = tool, args = args, idempotencyKey = "t:1")

    @Test
    fun `tool names from both sides are prefixed and distinct`() {
        val names = bridge().toolNames()
        assertTrue("plugin_local_utils_calc" in names)
        assertTrue("plugin_local_utils_local_time" in names)
        assertTrue("mcp_test_server_add" in names)
        assertEquals(names.size, names.distinct().size)
    }

    @Test
    fun `tool defs carry source descriptions`() {
        val defs = bridge().toolDefs().associateBy { it.name }
        assertTrue(defs["plugin_local_utils_calc"]!!.description.contains("本地小工具"))
        assertTrue(defs["mcp_test_server_add"]!!.description.contains("fake add"))
    }

    @Test
    fun `read-only and approval follow the source`() {
        val b = bridge()
        assertTrue(b.isReadOnly("plugin_local_utils_calc"))
        assertFalse(b.needsApproval("plugin_local_utils_calc"))
        assertFalse(b.isReadOnly("mcp_test_server_add"))
        assertTrue(b.needsApproval("mcp_test_server_add"))
        // 未知工具：不认作只读（按写类处理，最保守）。
        assertFalse(b.isReadOnly("plugin_nope"))
        assertTrue(b.needsApproval("plugin_nope"))
    }

    @Test
    fun `validate catches missing plugin args and unknown tools`() {
        val b = bridge()
        assertEquals("缺少参数 expression（须为非空字符串）", b.validate(action("plugin_local_utils_calc")))
        assertEquals(null, b.validate(action("plugin_local_utils_calc", mapOf("expression" to "1+2"))))
        assertEquals(null, b.validate(action("plugin_local_utils_local_time")))
        assertEquals(null, b.validate(action("mcp_test_server_add")))
        assertTrue(b.validate(action("nope_tool"))!!.contains("未知扩展工具"))
    }

    @Test
    fun `execute routes plugin calls to the real plugin`() = runBlocking {
        val b = bridge()
        val outcome = b.execute(action("plugin_local_utils_calc", mapOf("expression" to "6*7")), fakeContext(), stopped = { false })
        assertTrue(outcome.ok)
        assertEquals("42", outcome.data)
    }

    @Test
    fun `execute routes mcp calls to the mcp source`() = runBlocking {
        val fake = FakeMcp()
        val b = bridge(fake)
        val outcome = b.execute(action("mcp_test_server_add", mapOf("a" to 1)), fakeContext(), stopped = { false })
        assertTrue(outcome.ok)
        assertEquals("42", outcome.data)
        assertEquals("mcp_test_server_add" to mapOf<String, Any?>("a" to 1), fake.executed)
    }

    @Test
    fun `execute unknown tool is an error not a dispatch`() = runBlocking {
        val b = bridge()
        val outcome = b.execute(action("nope_tool"), fakeContext(), stopped = { false })
        assertFalse(outcome.ok)
        assertEquals("invalid_action", outcome.errorCode)
    }

    @Test
    fun `stopped turn never executes`() = runBlocking {
        val fake = FakeMcp()
        val b = bridge(fake)
        val outcome = b.execute(action("mcp_test_server_add"), fakeContext(), stopped = { true })
        assertFalse(outcome.ok)
        assertEquals("turn_stopped", outcome.errorCode)
        assertEquals(null, fake.executed)
    }

    @Test
    fun `disabled plugin tools disappear from the bridge`() {
        val plugins = PluginRegistry(isEnabled = { false })
        plugins.register(LocalUtilsPlugin())
        val fake = FakeMcp().apply { connected = false }
        val b = ExtensionToolBridge(plugins, fake)
        assertTrue(b.toolNames().isEmpty())
        assertTrue(b.toolDefs().isEmpty())
    }

    private fun fakeContext(): ToolContext = ToolContext(
        taskId = "t",
        agentId = "brain",
        approvalGate = object : wherevercode.agent.brain.ApprovalGate {
            override suspend fun requestApproval(action: Action): Boolean = true
            override fun isApprovalStillValid(action: Action): Boolean = true
        },
        workspaceRoot = java.io.File(""),
        deviceId = "local",
        folder = "",
    )
}
