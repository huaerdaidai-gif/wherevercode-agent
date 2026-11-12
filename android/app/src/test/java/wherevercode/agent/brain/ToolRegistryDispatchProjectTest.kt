package wherevercode.agent.brain

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * V5 跨设备开发（2026-11-12，A1/D5 新口径）：dispatch_task 建任务
 * 行时的 projectId 优先级。
 *
 * - 工具参数 project_id 有值时以参数为准（与当前聊天绑定冲突时
 *   参数赢）；
 * - 无参数时取当前聊天绑定；
 * - 都没有时任务不带项目（仍允许派活，走回落 + folder_fallback
 *   事件，不拦下）。
 */
class ToolRegistryDispatchProjectTest {

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var taskStore: FakeTaskStore

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("tool-registry-dispatch-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        taskStore = FakeTaskStore()
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private object EmptyMcp : McpToolSource {
        override fun toolDefs(): List<ToolDef> = emptyList()
        override fun isReadOnly(toolName: String): Boolean = true
        override fun needsApproval(toolName: String): Boolean = false
        override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolOutcome =
            ToolOutcome(ok = true, summary = "noop")
    }

    private fun registry(currentProjectId: (() -> String?)?): ToolRegistry {
        val taskRoot = File(tmpDir, "task-root").apply { mkdirs() }
        val workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        val deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter)
        val plugins = PluginRegistry(isEnabled = { true })
        plugins.register(LocalUtilsPlugin())
        return ToolRegistry(
            deviceAdapter, workspaceAdapter, CommandLimits(),
            taskStore, InMemoryAuditLog(),
            extensionTools = ExtensionToolBridge(plugins, EmptyMcp),
            currentProjectId = currentProjectId,
        )
    }

    private fun context() = ToolContext(
        taskId = "t1",
        agentId = "brain",
        approvalGate = FakeApprovalGate(allow = true),
        workspaceRoot = workspaceRoot,
        deviceId = "local",
        folder = "",
    )

    private fun dispatch(tools: ToolRegistry, args: Map<String, Any?>): String? = runBlocking {
        val outcome = tools.execute(
            Action(actionId = "a1", tool = "dispatch_task", args = args, idempotencyKey = "t1:1"),
            context(),
        )
        assertTrue("dispatch_task 应成功：${outcome.summary}", outcome.ok)
        val taskId = outcome.data as? String
        assertTrue("dispatch 应返回 task_id", taskId != null)
        taskStore.getTask(taskId!!)?.projectId
    }

    @Test
    fun `project_id param wins over the current chat binding`() {
        val tools = registry(currentProjectId = { "bound-project" })
        val projectId = dispatch(
            tools,
            mapOf(
                "goal" to "改代码",
                "assignee" to "codex-mac",
                "project_id" to "param-project",
            ),
        )
        assertEquals("param-project", projectId)
    }

    @Test
    fun `no project_id param falls back to the current chat binding`() {
        val tools = registry(currentProjectId = { "bound-project" })
        val projectId = dispatch(
            tools,
            mapOf("goal" to "改代码", "assignee" to "codex-mac"),
        )
        assertEquals("bound-project", projectId)
    }

    @Test
    fun `no param and no binding leaves the task unbound`() {
        val tools = registry(currentProjectId = { null })
        val projectId = dispatch(
            tools,
            mapOf("goal" to "改代码", "assignee" to "codex-mac"),
        )
        assertNull(projectId)
    }

    @Test
    fun `a throwing binding provider does not kill dispatch with a param`() {
        val tools = registry(currentProjectId = { throw IllegalStateException("绑定读失败") })
        val projectId = dispatch(
            tools,
            mapOf(
                "goal" to "改代码",
                "assignee" to "codex-mac",
                "project_id" to "param-project",
            ),
        )
        assertEquals("param-project", projectId)
    }
}
