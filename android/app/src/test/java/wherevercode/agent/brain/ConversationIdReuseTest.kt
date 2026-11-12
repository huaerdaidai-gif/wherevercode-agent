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
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * 失败测试（maintainer 2026-11-10 07:46 “对话容易丢失”主根因）：同一会话 id 的两轮
 * 聊天必须落在同一个 conversation 行里，第二轮的模型上下文须包含第一轮。
 *
 * 根因：`ConversationStore.createConversation(brainId, title)` 不接受 id，
 * `BrainEngine.chat` 里 `getConversation(id)` 永远 miss，于是每轮都新建一个
 * conversation 行（碎片化），每行只有当轮一句话；模型 history 也只有当轮。
 * 全部假大脑（FakeAdapter 测试替身），零真实调用。
 */
class ConversationIdReuseTest {

    private lateinit var tmpDir: File
    private lateinit var conversations: ConversationStore
    private lateinit var eventLog: EventLog

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("conversation-reuse-test").toFile()
        val workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        val taskRoot = File(tmpDir, "task-root").apply { mkdirs() }
        val workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        val deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter)
        conversations = InMemoryConversationStore()
        eventLog = InMemoryEventLog()
        val tools = ToolRegistry(
            deviceAdapter, workspaceAdapter, CommandLimits(), FakeTaskStore(), InMemoryAuditLog(),
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
        BrainAdapterRegistry.registerAdapter(
            FakeAdapter { flowOf(BrainEvent.TextDelta("收到"), BrainEvent.Done("stop")) },
        )
        engine = BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversations,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            tools = tools,
            eventLog = eventLog,
            approvalGate = FakeApprovalGate(allow = true),
            currentBrainId = { "fake-brain" },
        )
    }

    private lateinit var engine: BrainEngine

    @After
    fun tearDown() {
        BrainAdapterRegistry.clearForTests()
        tmpDir.deleteRecursively()
    }

    @Test
    fun `two turns with the same conversation id share one conversation row`() = runTest {
        val sessionId = "session-1"
        engine.chat(sessionId, "做一个计算器小程序").toList()
        engine.chat(sessionId, "再加一个开方功能").toList()

        assertEquals(1, conversations.listConversations().size)
        val messages = conversations.listMessages(sessionId)
        assertTrue(messages.any { it.role == "user" && it.content == "做一个计算器小程序" })
        assertTrue(messages.any { it.role == "user" && it.content == "再加一个开方功能" })
        assertTrue(messages.any { it.role == "assistant" })
    }
}
