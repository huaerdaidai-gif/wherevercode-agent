package wherevercode.agent.brain

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.task.RoomSkillStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * 技能记忆端到端（真实链路，非 mock 大脑行为）：
 * 真实 RoomSkillStore（内存 Room）+ 真实 ContextManager +
 * 真实 BrainEngine；FakeAdapter 只是测试替身，代替真实模型服务
 * 发工具调用（BrainEngineTest 同款做法），脚本按“请求里有没有
 * 技能块”决定走探索路线还是走已证明的做法。
 *
 * 同一个真实任务跑两遍：
 * - 第一遍：库里没技能，大脑先 list_files 摸索，再 write_file；
 *   任务成功后 SkillRefiner 从执行过程里抽出技能存进 Room。
 * - 第二遍：同一任务，大脑收到技能块，直接 write_file，不再
 *   先 list_files 摸索 —— 少走一步弯路。
 *
 * K70 真机验证（装含此分支的包后）见 DELIVERY.md §7。
 */
@RunWith(RobolectricTestRunner::class)
class SkillMemoryEndToEndTest {
    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var conversations: ConversationStore
    private lateinit var taskStore: FakeTaskStore
    private lateinit var tools: ToolRegistry
    private lateinit var eventLog: EventLog
    private lateinit var db: TaskDatabase
    private lateinit var skillStore: RoomSkillStore

    private val goal = "请在工作区新建 notes.txt 并写入 hello skill"

    /** 每次 adapter 收到的请求（断言技能块是否进了上下文用）。 */
    private val seenRequests = mutableListOf<ChatRequest>()

    /**
     * 测试替身脚本：有技能块 → 直接走已证明的做法（write_file）；
     * 没技能块 → 先 list_files 摸索，看到结果再 write_file。
     */
    private val script: (ChatRequest) -> Flow<BrainEvent> = { request ->
        seenRequests += request
        val hasSkill = request.messages.any { it.role == "system" && it.content.contains("【技能") }
        val toolCount = request.messages.count { it.role == "tool" }
        when {
            hasSkill && toolCount == 0 -> flowOf(
                BrainEvent.ToolCall("c1", "write_file", mapOf("path" to "notes.txt", "content" to "hello skill")),
                BrainEvent.Done("stop"),
            )
            hasSkill -> flowOf(BrainEvent.TextDelta("已按技能做法完成"), BrainEvent.Done("stop"))
            toolCount == 0 -> flowOf(
                BrainEvent.ToolCall("c1", "list_files", mapOf("path" to "")),
                BrainEvent.Done("stop"),
            )
            toolCount == 1 -> flowOf(
                BrainEvent.ToolCall("c2", "write_file", mapOf("path" to "notes.txt", "content" to "hello skill")),
                BrainEvent.Done("stop"),
            )
            else -> flowOf(BrainEvent.TextDelta("已完成"), BrainEvent.Done("stop"))
        }
    }

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("skill-memory-e2e-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        val taskRoot = File(tmpDir, "task-root").apply { mkdirs() }
        val workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        val deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter)
        conversations = InMemoryConversationStore()
        taskStore = FakeTaskStore()
        eventLog = InMemoryEventLog()
        tools = ToolRegistry(deviceAdapter, workspaceAdapter, CommandLimits(), taskStore, InMemoryAuditLog())
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        skillStore = RoomSkillStore(db)
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
        db.close()
        tmpDir.deleteRecursively()
    }

    private fun engine(adapter: BrainAdapter = FakeAdapter(script)): BrainEngine {
        BrainAdapterRegistry.registerAdapter(adapter)
        return BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversations,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            skillStore = skillStore,
            tools = tools,
            eventLog = eventLog,
            approvalGate = FakeApprovalGate(allow = true),
            currentBrainId = { "fake-brain" },
        )
    }

    private fun toolNames(events: List<BrainEvent>): List<String> =
        events.filterIsInstance<BrainEvent.ToolCall>().map { it.name }

    @Test
    fun `second run reuses the skill and skips the exploratory step`() = runTest {
        val engine = engine()

        // ---- 第一遍：库里没技能，大脑先摸索再写。----
        val first = conversations.createConversation("fake-brain", "第一遍")
        val firstEvents = engine.chat(first.id, goal).toList()
        assertEquals(listOf("list_files", "write_file"), toolNames(firstEvents))
        assertTrue(firstEvents.filterIsInstance<BrainEvent.ToolResult>().all { it.ok })
        assertEquals("hello skill", File(workspaceRoot, "notes.txt").readText())
        // 第一遍的请求里没有技能块。
        assertFalse(seenRequests.any { req -> req.messages.any { it.content.contains("【技能") } })
        assertTrue(skillStore.listForProject("").isEmpty())

        // ---- 任务成功，抽技能存进 Room（LocalTaskRunner 终态同款调用）。----
        val captured = SkillRefiner(skillStore).onTaskSettled(
            SettledTaskEvidence(
                taskId = "task-run-1",
                projectId = null,
                goal = goal,
                success = true,
                error = null,
                toolsUsed = listOf("list_files", "write_file"),
                keySteps = listOf("list_files 列出工作区", "write_file 写入 notes.txt"),
            ),
        )
        assertTrue(captured != null)
        val stored = skillStore.listForProject("")
        assertEquals(1, stored.size)
        assertEquals(1, stored.single().successCount)
        assertTrue(stored.single().triggers.contains("write_file"))

        // ---- 第二遍：同一任务，技能块进了上下文，直接写，不再摸索。----
        seenRequests.clear()
        val second = conversations.createConversation("fake-brain", "第二遍")
        val secondEvents = engine.chat(second.id, goal).toList()
        assertEquals(listOf("write_file"), toolNames(secondEvents))
        assertTrue(secondEvents.filterIsInstance<BrainEvent.ToolResult>().all { it.ok })
        assertTrue(
            seenRequests.first().messages.any { it.role == "system" && it.content.contains("【技能") },
        )
        // 注入的是第一遍捕获的真实技能摘要（keySteps 拼成的做法）。
        assertTrue(
            seenRequests.first().messages.any { it.content.contains("write_file 写入 notes.txt") },
        )

        // 技能被用过后仍在库里（第二遍没把它消费掉）。
        assertEquals(1, skillStore.listForProject("").size)
    }

    @Test
    fun `unrelated task gets no skill injected`() = runTest {
        SkillRefiner(skillStore).onTaskSettled(
            SettledTaskEvidence(
                taskId = "task-run-1",
                projectId = null,
                goal = goal,
                success = true,
                error = null,
                toolsUsed = listOf("list_files", "write_file"),
                keySteps = listOf("list_files 列出工作区", "write_file 写入 notes.txt"),
            ),
        )
        val conv = conversations.createConversation("fake-brain", "无关任务")
        // 纯文本替身：不调任何工具，只看请求里有没有技能块。
        val textOnly = FakeAdapter { request ->
            seenRequests += request
            flowOf(BrainEvent.TextDelta("与技能无关"), BrainEvent.Done("stop"))
        }
        val engine = engine(textOnly)
        engine.chat(conv.id, "请部署 docker 容器").toList()
        assertFalse(seenRequests.any { req -> req.messages.any { it.content.contains("【技能") } })
    }
}
