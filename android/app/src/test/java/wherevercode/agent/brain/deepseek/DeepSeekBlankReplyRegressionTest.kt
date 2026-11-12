package wherevercode.agent.brain.deepseek

import java.io.BufferedReader
import java.io.StringReader
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import wherevercode.agent.brain.BrainAdapterRegistry
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainEngine
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ContextManager
import wherevercode.agent.brain.DenyApprovalGate
import wherevercode.agent.brain.InMemoryConversationStore
import wherevercode.agent.brain.InMemoryEventLog
import wherevercode.agent.brain.InMemoryMemoryStore
import wherevercode.agent.brain.ToolRegistry
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * 2026-11-09 K70 空白气泡回归（安卓真实 org.json 语义）。
 *
 * 真机 APK 使用 Android 框架的 org.json：`optString(name, fallback)` 在值为
 * JSON null 时返回字符串 "null"，而不是 fallback（JVM 单元测试里的
 * org.json:json 返回 fallback，所以旧单测全绿、真机全黑）。DeepSeek/OpenAI
 * 的标准流式块每个非终止块都带 `"finish_reason": null`；解析器因此把第一个
 * 块当成终止块，后续所有 content/tool_calls 全被丢弃 → 引擎拿到一个空回合
 * → UI 留下空白气泡，且没有任何错误日志。
 *
 * 本测试必须用 Robolectric 跑，才能使用 Android 的 org.json 实现；JVM
 * org.json 测不出这个差异。测试走完整链路：
 * DeepSeekApiClient(fake transport) → DeepSeekBrainAdapter → BrainEngine。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeepSeekBlankReplyRegressionTest {

    private class FakeCredentialStore(var key: String? = "test-key") : DeepSeekCredentialStore {
        override fun saveApiKey(apiKey: String) { key = apiKey }
        override fun loadApiKey(): String? = key
        override fun clearApiKey() { key = null }
    }

    private class FakeConnection(private val body: String) : DeepSeekConnection {
        override val statusCode: Int = 200
        override fun openBodyReader(): BufferedReader = BufferedReader(StringReader(body))
        override fun readErrorBody(): String? = null
        override fun close() = Unit
    }

    private class ScriptedTransport(private val bodies: MutableList<String>) : DeepSeekTransport {
        val requestBodies = mutableListOf<String>()
        override fun post(url: String, apiKey: String, requestBody: String): DeepSeekConnection {
            requestBodies += requestBody
            if (bodies.isEmpty()) error("unexpected extra DeepSeek call")
            return FakeConnection(bodies.removeAt(0))
        }
        override fun get(url: String, apiKey: String): DeepSeekConnection =
            FakeConnection("""{"object":"list","data":[]}""")
    }

    private class EmptyTaskStore : TaskStore {
        override fun createTask(record: TaskRecord): TaskRecord = record
        override fun listTasks(projectId: String?, sessionId: String?): List<TaskRecord> = emptyList()
        override fun getTask(taskId: String): TaskRecord? = null
        override fun updateTask(
            taskId: String,
            changes: Map<String, Any?>,
            expectedVersion: Long,
            expectedGoalVersion: Long,
            expectedAuthEpoch: Long,
        ): TaskRecord? = null
        override fun expireLeases(nowEpochSeconds: Long): List<String> = emptyList()

        override fun deleteTask(taskId: String): Boolean = false
    }

    /** 标准 OpenAI/DeepSeek 流：[role] -> [tool call] -> [finish] -> [DONE]。 */
    private fun toolCallStream(id: String, name: String, argsJson: String): String = """
        data: {"id":"x","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

        data: {"id":"x","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"$id","type":"function","function":{"name":"$name","arguments":$argsJson}}]},"finish_reason":null}]}

        data: {"id":"x","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

        data: [DONE]

    """.trimIndent()

    /** 标准文本流：每个非终止块 finish_reason 都是 JSON null。 */
    private fun textStream(vararg chunks: String): String = buildString {
        append("""data: {"id":"x","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""")
        append("\n\n")
        for (chunk in chunks) {
            append("""data: {"id":"x","choices":[{"index":0,"delta":{"content":"$chunk"},"finish_reason":null}]}""")
            append("\n\n")
        }
        append("""data: {"id":"x","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")
        append("\n\n")
        append("data: [DONE]\n")
    }

    @Test
    fun `Android JSON null finish_reason does not truncate the rest of the stream`() {
        val events = DeepSeekStreamParser.parseSseText(
            """
            data: {"choices":[{"delta":{"role":"assistant","content":""},"finish_reason":null}]}

            data: {"choices":[{"delta":{"content":"蓝莓派 42 号"},"finish_reason":null}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """.trimIndent(),
        )
        assertTrue(
            "finish_reason=null 不能终止流；事件：$events",
            events.contains(DeepSeekEvent.Text("蓝莓派 42 号")),
        )
        assertTrue(events.any { it is DeepSeekEvent.Finished })
        assertTrue(events.none { it is DeepSeekEvent.Failed })
    }

    @Before
    fun setUp() {
        BrainAdapterRegistry.clearForTests()
    }

    @After
    fun tearDown() {
        BrainAdapterRegistry.clearForTests()
    }

    @Test
    fun `K70 prompt list then read then answer produces text on Android JSON`() = runTest {
        val tmp = Files.createTempDirectory("deepseek-k70").toFile()
        try {
            val workspace = java.io.File(tmp, "workspace").apply { mkdirs() }
            java.io.File(workspace, "README.txt").writeText("蓝莓派 42 号", Charsets.UTF_8)
            val taskRoot = java.io.File(tmp, "tasks").apply { mkdirs() }
            val workspaceAdapter = FileWorkspaceAdapter(workspace, taskRoot)
            val deviceAdapter = JvmDeviceAdapter(listOf(workspace), workspaceAdapter)
            val tools = ToolRegistry(
                deviceAdapter,
                workspaceAdapter,
                CommandLimits(),
                EmptyTaskStore(),
                InMemoryAuditLog(),
            )
            val transport = ScriptedTransport(
                mutableListOf(
                    toolCallStream("call_list", "list_files", "{}"),
                    toolCallStream("call_read", "read_file", """{"path":"README.txt"}"""),
                    textStream("README.txt 的完整内容是：", "蓝莓派 42 号"),
                ),
            )
            val adapter = DeepSeekBrainAdapter(
                credentialStore = FakeCredentialStore(),
                baseUrlProvider = { "https://api.deepseek.com" },
                configuredModelProvider = { "deepseek-flash" },
                apiClient = DeepSeekApiClient(transport),
            )
            BrainAdapterRegistry.registerAdapter(adapter)
            BrainAdapterRegistry.registerConfig(
                BrainConfig(
                    brainId = "deepseek",
                    kind = BrainKind.MODEL_API,
                    adapterId = adapter.adapterId,
                    label = "DeepSeek API",
                    baseUrl = "https://api.deepseek.com",
                    model = "deepseek-flash",
                    authRef = "deepseek:api-key",
                ),
            )
            // The engine's own store keeps history across tool rounds.
            val store = InMemoryConversationStore()
            val engineWithStore = BrainEngine(
                adapters = BrainAdapterRegistry,
                conversations = store,
                contextManager = ContextManager(),
                memory = InMemoryMemoryStore(),
                tools = tools,
                eventLog = InMemoryEventLog(),
                approvalGate = DenyApprovalGate,
                currentBrainId = { "deepseek" },
            )
            val conv = store.createConversation("deepseek", "K70")
            val events = engineWithStore.chat(
                conv.id,
                "先列出工作区文件，再读取 README.txt，最后回复完整内容",
            ).toList()

            assertTrue(
                "工具链应真实执行 list_files/read_file，事件：$events",
                events.filterIsInstance<BrainEvent.ToolCall>()
                    .map { it.name }
                    .containsAll(listOf("list_files", "read_file")),
            )
            val text = events.filterIsInstance<BrainEvent.TextDelta>().joinToString("") { it.text }
            assertTrue("最终回复不能是空响应，事件：$events", text.contains("蓝莓派 42 号"))
            assertTrue("不应出现错误，事件：$events", events.none { it is BrainEvent.Error })
            // 第二次请求必须携带 role=tool + tool_call_id 的原生配对。
            assertTrue(transport.requestBodies.size >= 2)
            assertTrue(transport.requestBodies[1].contains("\"tool_call_id\":\"call_list\""))
        } finally {
            tmp.deleteRecursively()
        }
    }
}
