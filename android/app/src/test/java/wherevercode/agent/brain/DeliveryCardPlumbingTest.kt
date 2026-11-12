package wherevercode.agent.brain

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.workspace.ChangeSet
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * a-3 交付卡接线：写轮次的 ChangeSet 必须按真实轮次任务 id 归属，
 * 不能按会话 id 归属。
 *
 * 背景：ToolRegistry 的写轮次拿到的 taskId 来自 ToolContext，而
 * BrainEngine 以前写死 `taskId = conversation.id`（同一会话多轮次
 * 的改动会串到同一把 key 上，无法分到某一轮任务）。本测试锁定：
 * engine.chat(taskId=…) 传进来的轮次任务 id 原样到达 onChangeSet；
 * 不传时回落到会话 id（既有调用点与测试不动）。
 *
 * FakeAdapter / FakeApprovalGate / FakeTaskStore 复用 BrainEngineTest
 * 的测试替身（同一包内可见）。
 */
class DeliveryCardPlumbingTest {

    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var workspaceAdapter: FileWorkspaceAdapter
    private lateinit var tools: ToolRegistry
    private val seen = mutableListOf<Triple<String, String, ChangeSet>>()

    private fun writeScript(path: String): (ChatRequest) -> Flow<BrainEvent> = { request ->
        val hasToolResult = request.messages.any { it.role == "tool" }
        if (hasToolResult) {
            flowOf(BrainEvent.TextDelta("已写好"), BrainEvent.Done("stop"))
        } else {
            flowOf(
                BrainEvent.ToolCall(
                    "call-1",
                    "write_file",
                    mapOf("path" to path, "content" to "hello"),
                ),
                BrainEvent.Done("stop"),
            )
        }
    }

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("delivery-card-plumbing-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        val taskRoot = File(tmpDir, "task-root").apply { mkdirs() }
        workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        tools = ToolRegistry(
            deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter),
            workspaceAdapter = workspaceAdapter,
            limits = CommandLimits(),
            taskStore = FakeTaskStore(),
            auditLog = InMemoryAuditLog(),
            pythonRuntime = null,
            onChangeSet = { taskId, roundId, changeSet ->
                seen += Triple(taskId, roundId, changeSet)
            },
        )
        BrainAdapterRegistry.clearForTests()
        BrainAdapterRegistry.registerConfig(
            BrainConfig(
                brainId = "fake-brain",
                kind = BrainKind.MODEL_API,
                adapterId = "fake-test-adapter",
                label = "Fake（测试替身）",
                baseUrl = null,
                model = "fake-model",
                authRef = null,
            ),
        )
    }

    @After
    fun tearDown() {
        BrainAdapterRegistry.clearForTests()
        tmpDir.deleteRecursively()
    }

    @Test
    fun `write round changeset is attributed to the turn task id`() = runTest {
        val conversations = InMemoryConversationStore()
        val conversation = conversations.createConversation("fake-brain", "写个文件")
        val eng = BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversations,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            tools = tools,
            eventLog = InMemoryEventLog(),
            approvalGate = FakeApprovalGate(allow = true),
            currentBrainId = { "fake-brain" },
        )
        BrainAdapterRegistry.registerAdapter(FakeAdapter(writeScript("out/hello.txt")))
        val events = eng.chat(
            conversation.id,
            "写个文件",
            taskId = "task-plumbing-1",
        ).toList()
        assertTrue(events.any { it is BrainEvent.ToolResult && it.ok })
        assertEquals(1, seen.size)
        assertEquals("task-plumbing-1", seen.single().first)
        assertEquals(listOf("out/hello.txt"), seen.single().third.files.map { it.path })
    }

    @Test
    fun `without task id the changeset falls back to the conversation id`() = runTest {
        val conversations = InMemoryConversationStore()
        val conversation = conversations.createConversation("fake-brain", "写个文件")
        val eng = BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversations,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            tools = tools,
            eventLog = InMemoryEventLog(),
            approvalGate = FakeApprovalGate(allow = true),
            currentBrainId = { "fake-brain" },
        )
        BrainAdapterRegistry.registerAdapter(FakeAdapter(writeScript("out/hello.txt")))
        eng.chat(conversation.id, "写个文件").toList()
        assertEquals(1, seen.size)
        assertEquals(conversation.id, seen.single().first)
    }
}
