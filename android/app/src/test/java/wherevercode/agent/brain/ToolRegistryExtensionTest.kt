package wherevercode.agent.brain

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.extension.ExtensionToolBridge
import wherevercode.agent.core.extension.mcp.McpToolSource
import wherevercode.agent.core.extension.plugin.LocalUtilsPlugin
import wherevercode.agent.core.extension.plugin.PluginRegistry
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * ToolRegistry + 扩展工具测试：扩展工具进同一条调用链。
 *
 * 断言：扩展工具名出现在 allToolNames / toolsForCapabilities 里；
 * 只读扩展工具不弹审批直接执行、结果回填；需要审批的扩展工具
 * 走同一道审批门（拒绝即不执行）；未知扩展工具报 invalid_action。
 * 插件用真实 LocalUtilsPlugin；MCP 用替身来源（真实往返见 McpClientTest）。
 */
class ToolRegistryExtensionTest {

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var tools: ToolRegistry

    private class ApprovingMcp : McpToolSource {
        var calls = 0
        override fun toolDefs(): List<ToolDef> = listOf(
            ToolDef(
                name = "mcp_fake_server_write_note",
                description = "fake write (needs approval)",
                inputSchema = mapOf("type" to "object"),
            ),
        )

        override fun isReadOnly(toolName: String): Boolean = false
        override fun needsApproval(toolName: String): Boolean = true

        override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolOutcome {
            calls++
            return ToolOutcome(ok = true, summary = "fake mcp write ok", data = "written")
        }
    }

    private val mcp = ApprovingMcp()

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("tool-registry-ext-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        val taskRoot = File(tmpDir, "task-root").apply { mkdirs() }
        val workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        val deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter)
        val plugins = PluginRegistry(isEnabled = { true })
        plugins.register(LocalUtilsPlugin())
        val bridge = ExtensionToolBridge(plugins, mcp)
        tools = ToolRegistry(
            deviceAdapter, workspaceAdapter, CommandLimits(),
            FakeTaskStore(), InMemoryAuditLog(), extensionTools = bridge,
        )
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun context(gate: ApprovalGate): ToolContext = ToolContext(
        taskId = "t1",
        agentId = "brain",
        approvalGate = gate,
        workspaceRoot = workspaceRoot,
        deviceId = "local",
        folder = "",
    )

    private fun action(tool: String, args: Map<String, Any?> = emptyMap()): Action =
        Action(actionId = "a1", tool = tool, args = args, idempotencyKey = "t1:1")

    @Test
    fun `extension tools appear in allToolNames and toolsForCapabilities`() {
        assertTrue("plugin_local_utils_calc" in tools.allToolNames())
        assertTrue("mcp_fake_server_write_note" in tools.allToolNames())
        assertTrue("read_file" in tools.allToolNames())
        val defs = tools.toolsForCapabilities(null, tools.allToolNames())
        assertTrue(defs.any { it.name == "plugin_local_utils_calc" })
        assertTrue(defs.any { it.name == "mcp_fake_server_write_note" })
        // 大脑无工具能力时：扩展工具也不列。
        val noTools = tools.toolsForCapabilities(
            BrainCapabilities(streaming = true, toolCalling = false),
            tools.allToolNames(),
        )
        assertTrue(noTools.isEmpty())
    }

    @Test
    fun `read-only extension tool executes without approval`() = runBlocking {
        val gate = FakeApprovalGate(allow = false)
        val outcome = tools.execute(
            action("plugin_local_utils_calc", mapOf("expression" to "6*7")),
            context(gate),
        )
        assertTrue(outcome.ok)
        assertEquals("42", outcome.data)
        assertTrue("read-only extension tool must not ask approval", gate.asked.isEmpty())
    }

    @Test
    fun `extension tool needing approval asks the gate and executes when approved`() = runBlocking {
        val gate = FakeApprovalGate(allow = true)
        val outcome = tools.execute(action("mcp_fake_server_write_note"), context(gate))
        assertTrue(outcome.ok)
        assertEquals("written", outcome.data)
        assertEquals(1, mcp.calls)
        assertEquals(1, gate.asked.size)
        assertEquals("mcp_fake_server_write_note", gate.asked.first().tool)
    }

    @Test
    fun `extension tool needing approval never executes when denied`() = runBlocking {
        val gate = FakeApprovalGate(allow = false)
        val outcome = tools.execute(action("mcp_fake_server_write_note"), context(gate))
        assertFalse(outcome.ok)
        assertEquals("user_denied", outcome.errorCode)
        assertEquals(0, mcp.calls)
    }

    @Test
    fun `unknown extension tool is invalid_action`() = runBlocking {
        val gate = FakeApprovalGate(allow = true)
        val outcome = tools.execute(action("plugin_nope"), context(gate))
        assertFalse(outcome.ok)
        assertEquals("invalid_action", outcome.errorCode)
        assertTrue(gate.asked.isEmpty())
    }

    @Test
    fun `extension tool missing required arg is invalid_action`() = runBlocking {
        val gate = FakeApprovalGate(allow = true)
        val outcome = tools.execute(action("plugin_local_utils_calc"), context(gate))
        assertFalse(outcome.ok)
        assertEquals("invalid_action", outcome.errorCode)
        assertTrue(gate.asked.isEmpty())
    }

    @Test
    fun `isReadOnlyExtensionTool delegates to the bridge`() {
        assertTrue(tools.isReadOnlyExtensionTool("plugin_local_utils_calc"))
        assertFalse(tools.isReadOnlyExtensionTool("mcp_fake_server_write_note"))
        assertFalse(tools.isReadOnlyExtensionTool("read_file"))
    }

    @Test
    fun `registry without extension tools is unchanged`() {
        val taskRoot2 = File(tmpDir, "t2").apply { mkdirs() }
        val adapter2 = FileWorkspaceAdapter(workspaceRoot, taskRoot2)
        val plain = ToolRegistry(
            JvmDeviceAdapter(listOf(workspaceRoot), adapter2),
            adapter2,
            CommandLimits(), FakeTaskStore(), InMemoryAuditLog(),
        )
        assertFalse("plugin_local_utils_calc" in plain.allToolNames())
        assertFalse(plain.isReadOnlyExtensionTool("plugin_local_utils_calc"))
    }
}
