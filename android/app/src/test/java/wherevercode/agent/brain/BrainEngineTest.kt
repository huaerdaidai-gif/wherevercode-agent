package wherevercode.agent.brain

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.security.AuditLog
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.session.LedgerHarness
import wherevercode.agent.core.session.MessageKind
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore
import wherevercode.agent.core.workspace.FileWorkspaceAdapter

/**
 * BrainEngine tool-loop tests.
 *
 * FakeAdapter / FakeApprovalGate / FakeTaskStore below are TEST DOUBLES
 * (测试替身): they stand in for a real model service, a real user
 * approving in the UI, and the Room store. No test double is ever
 * presented as a real model call.
 */
class FakeAdapter(
    private val script: (ChatRequest) -> Flow<BrainEvent>,
) : BrainAdapter {
    /** Test-only switch: false simulates a brain with no native tool calling. */
    var toolCalling: Boolean = true
    override val adapterId: String = "fake-test-adapter"
    override val kind: BrainKind = BrainKind.MODEL_API
    override val label: String = "FakeAdapter（测试替身）"
    override fun capabilities(): BrainCapabilities =
        BrainCapabilities(streaming = true, toolCalling = toolCalling)

    override suspend fun validateCredentials(): Result<Unit> = Result.success(Unit)
    override suspend fun listModels(): Result<List<ModelInfo>> =
        Result.success(listOf(ModelInfo("fake-model", "Fake Model（测试替身）")))

    override fun chatStream(request: ChatRequest): Flow<BrainEvent> = script(request)
}

class FakeApprovalGate(private val allow: Boolean) : ApprovalGate {
    val asked = mutableListOf<Action>()
    override suspend fun requestApproval(action: Action): Boolean {
        asked += action
        return allow
    }
}

/** Test double (测试替身) for the Room TaskStore. */
class FakeTaskStore : TaskStore {
    private val tasks = LinkedHashMap<String, TaskRecord>()
    override fun createTask(record: TaskRecord): TaskRecord {
        tasks[record.taskId] = record
        return record
    }
    override fun listTasks(projectId: String?, sessionId: String?): List<TaskRecord> =
        tasks.values.toList()
    override fun getTask(taskId: String): TaskRecord? = tasks[taskId]
    override fun updateTask(
        taskId: String,
        changes: Map<String, Any?>,
        expectedVersion: Long,
        expectedGoalVersion: Long,
        expectedAuthEpoch: Long,
    ): TaskRecord? {
        val current = tasks[taskId] ?: return null
        if (current.version != expectedVersion) return null
        if (current.goalVersion != expectedGoalVersion) return null
        if (current.authEpoch != expectedAuthEpoch) return null
        var next = current
        for ((key, value) in changes) {
            next = when (key) {
                "state" -> next.copy(state = value as String)
                "needs_attention" -> next.copy(needsAttention = value as Boolean)
                "assigned_agent_id" -> next.copy(assignedAgentId = value as? String)
                // V5 修复（2026-11-11）：Manus 任务轮次落行时写 result/error；
                // 假库之前直接丢弃这两键，测不到落行内容。
                "result" -> next.copy(result = value as? String)
                "error" -> next.copy(error = value as? String)
                else -> next
            }
        }
        next = next.copy(version = current.version + 1)
        tasks[taskId] = next
        return next
    }
    override fun expireLeases(nowEpochSeconds: Long): List<String> = emptyList()

    override fun deleteTask(taskId: String): Boolean = tasks.remove(taskId) != null
}

class BrainEngineTest {
    private lateinit var tmpDir: File
    private lateinit var workspaceRoot: File
    private lateinit var conversations: ConversationStore
    private lateinit var taskStore: FakeTaskStore
    private lateinit var auditLog: AuditLog
    private lateinit var tools: ToolRegistry
    private lateinit var eventLog: EventLog

    @Before
    fun setUp() {
        tmpDir = Files.createTempDirectory("brain-engine-test").toFile()
        workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        val taskRoot = File(tmpDir, "task-root").apply { mkdirs() }
        File(workspaceRoot, "README.md").writeText("hello from workspace", Charsets.UTF_8)
        File(tmpDir, "outside-secret.txt").writeText("OUTSIDE-MARKER", Charsets.UTF_8)
        val workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        val deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter)
        conversations = InMemoryConversationStore()
        taskStore = FakeTaskStore()
        auditLog = InMemoryAuditLog()
        eventLog = InMemoryEventLog()
        tools = ToolRegistry(deviceAdapter, workspaceAdapter, CommandLimits(), taskStore, auditLog)
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

    private fun engine(
        adapter: BrainAdapter,
        gate: ApprovalGate,
        failoverPolicy: FailoverPolicy = FailoverPolicy(),
        maxToolCallsPerTurn: Int = 8,
        maxTurnMillis: Long = 120_000,
        controlState: () -> TurnControlState = { TurnControlState.RUNNING },
        nowMillis: () -> Long = { System.currentTimeMillis() },
        currentBrainId: () -> String? = { "fake-brain" },
    ): BrainEngine {
        BrainAdapterRegistry.registerAdapter(adapter)
        return BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversations,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            tools = tools,
            eventLog = eventLog,
            approvalGate = gate,
            currentBrainId = currentBrainId,
            maxToolCallsPerTurn = maxToolCallsPerTurn,
            maxTurnMillis = maxTurnMillis,
            controlState = controlState,
            nowMillis = nowMillis,
            failoverPolicy = failoverPolicy,
        )
    }

    /** 真实执行的工具调用次数（EventLog "tool_result" 条数；缓存命中记 "tool_result_cached"，不计入）。 */
    private fun realExecutions(conversationId: String): Int =
        eventLog.entries(conversationId).count { it.kind == "tool_result" }

    private fun cachedHits(conversationId: String): Int =
        eventLog.entries(conversationId).count { it.kind == "tool_result_cached" }

    /** 第 index 个请求里最新一条 role=tool 消息的 JSON（null = 该请求里没有 tool 消息）。 */
    private fun toolJsonOf(seenRequests: List<ChatRequest>, index: Int): JSONObject? {
        val msg = seenRequests.getOrNull(index)?.messages?.lastOrNull { it.role == "tool" }
            ?: return null
        return JSONObject(msg.content)
    }

    /** M2 M9: same engine, with a real session ledger injected. */
    private fun engineWithLedger(
        adapter: BrainAdapter,
        gate: ApprovalGate,
        harness: LedgerHarness,
    ): BrainEngine {
        BrainAdapterRegistry.registerAdapter(adapter)
        return BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversations,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            tools = tools,
            eventLog = eventLog,
            approvalGate = gate,
            currentBrainId = { "fake-brain" },
            sessionLedger = harness.ledger,
        )
    }

    /** Script: first turn emits a read_file call; after the tool result, emit final text. */
    private fun readFileScript(path: String): (ChatRequest) -> Flow<BrainEvent> = { request ->
        val hasToolResult = request.messages.any { it.role == "tool" }
        if (hasToolResult) {
            flowOf(BrainEvent.TextDelta("已读取完毕"), BrainEvent.Done("stop"))
        } else {
            flowOf(
                BrainEvent.ToolCall("call-1", "read_file", mapOf("path" to path)),
                BrainEvent.Done("stop"),
            )
        }
    }

    @Test
    fun `tool loop completes read_file and feeds the result back`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val engine = engine(FakeAdapter(readFileScript("README.md")), FakeApprovalGate(allow = true))
        val events = engine.chat(conversation.id, "请读一下 README").toList()
        assertTrue(events.any { it is BrainEvent.ToolCall && it.name == "read_file" })
        val result = events.filterIsInstance<BrainEvent.ToolResult>().firstOrNull()
        assertTrue(result != null && result.ok)
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("已读取完毕") })
        assertTrue(events.last() is BrainEvent.Done)
        // The tool result was fed back as a role=tool message in history.
        val messages = conversations.listMessages(conversation.id)
        assertTrue(messages.any { it.role == "tool" && it.content.contains("hello from workspace") })
        assertTrue(messages.any { it.role == "assistant" && it.source == "fake-brain" })
    }

    @Test
    fun `validator refusal really does not execute - root-outside path`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "读根外文件")
        val engine = engine(FakeAdapter(readFileScript("../outside-secret.txt")), FakeApprovalGate(allow = true))
        val events = engine.chat(conversation.id, "请读外面的文件").toList()
        val result = events.filterIsInstance<BrainEvent.ToolResult>().firstOrNull()
        assertTrue(result != null && !result.ok)
        assertTrue(result!!.summary.contains("不允许的路径"))
        // The outside file was never read: its marker appears nowhere.
        val allText = conversations.listMessages(conversation.id).joinToString("\n") { it.content } +
            events.joinToString("\n") { it.toString() }
        assertFalse(allText.contains("OUTSIDE-MARKER"))
    }

    @Test
    fun `no brain configured reports honestly instead of inventing a reply`() = runTest {
        val conversation = conversations.createConversation(null, "无大脑")
        val engine = BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversations,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            tools = tools,
            eventLog = eventLog,
            approvalGate = FakeApprovalGate(allow = true),
            currentBrainId = { null },
        )
        val events = engine.chat(conversation.id, "你好").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "brain_not_configured")
        assertTrue(error!!.message.contains("未配置大脑"))
    }

    @Test
    fun `old conversation with null brainId is backfilled from current brain`() = runTest {
        // 2026-11-11 brain_not_configured 回填：12:50–18:13 current 为
        // null 的窗口里落库的旧会话行 brainId 为 null，18:13 切 zhipu
        // 后新会话正常、旧会话一碰就复发。本轮应按当前大脑继续，并把
        // 纠正写回会话行。
        val conversation = conversations.createConversation(null, "旧会话")
        val engine = engine(
            FakeAdapter { flowOf(BrainEvent.TextDelta("收到"), BrainEvent.Done("stop")) },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "你好").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("收到") })
        assertEquals("fake-brain", conversations.getConversation(conversation.id)?.brainId)
    }

    @Test
    fun `old task rerun with null brainId succeeds and corrects the task row`() = runTest {
        // 任务执行器以 taskId 当 conversationId；旧任务行 brainId 为
        // null 时重跑同一任务应成功（任务 64aa19e0 的复发机制）。
        val taskId = "task-64aa19e0-rerun"
        conversations.getOrCreateConversation(taskId, null, "旧任务")
        val engine = engine(
            FakeAdapter { flowOf(BrainEvent.TextDelta("收到"), BrainEvent.Done("stop")) },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(taskId, "继续这个任务").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("收到") })
        assertEquals("fake-brain", conversations.getConversation(taskId)?.brainId)
    }

    @Test
    fun `backfill does not steal a conversation bound to another brain`() = runTest {
        // 已绑了别的已配置大脑的会话，current 换了也不应被偷切。
        // 这也是 startBrainTurn 不直接传 effectiveBrainId 的原因：
        // 回填只补 null 行，绝不改已有绑定。
        BrainAdapterRegistry.registerConfig(
            BrainConfig(
                brainId = "other-brain",
                kind = BrainKind.MODEL_API,
                adapterId = "fake-test-adapter",
                label = "Other（测试替身）",
                baseUrl = null,
                model = "fake-model",
                authRef = null,
            ),
        )
        val conversation = conversations.createConversation("other-brain", "已绑别脑")
        val engine = engine(
            FakeAdapter { flowOf(BrainEvent.TextDelta("收到"), BrainEvent.Done("stop")) },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "你好").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        assertEquals("other-brain", conversations.getConversation(conversation.id)?.brainId)
    }

    @Test
    fun `secrets are redacted before they reach the event log`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "密钥")
        val engine = engine(
            FakeAdapter { flowOf(BrainEvent.TextDelta("收到"), BrainEvent.Done("stop")) },
            FakeApprovalGate(allow = true),
        )
        engine.chat(conversation.id, "我的 key 是 sk-abcdefghijklmnop123456").toList()
        // The event log (what leaves the chat) must never carry the key.
        val logText = eventLog.entries(conversation.id).joinToString("\n") { it.detail }
        assertFalse(logText.contains("sk-abcdefghijklmnop123456"))
        // The user's own words stay verbatim in local history only; they
        // never enter logs, exports or task results unredacted.
        val stored = conversations.listMessages(conversation.id)
        assertTrue(stored.any { it.role == "user" })
    }

    @Test
    fun `M2 - CONTROL short-circuits with zero adapter calls and both ledger rows recorded`() = runTest {
        val harness = LedgerHarness()
        harness.addTask("t1", state = "working")
        var adapterCalls = 0
        val adapter = FakeAdapter {
            adapterCalls += 1
            flowOf(BrainEvent.TextDelta("不应该出现"), BrainEvent.Done("stop"))
        }
        val conversation = conversations.createConversation("fake-brain", "控制")
        val engine = engineWithLedger(adapter, FakeApprovalGate(allow = true), harness)
        val events = engine.chat(conversation.id, "暂停 t1 原因：需要先停一下").toList()
        assertEquals(0, adapterCalls)
        assertTrue(events.none { it is BrainEvent.Usage })
        assertTrue(events.last() is BrainEvent.Done)
        // Session message row (CONTROL) and task intent row both exist.
        val rows = harness.ledger.listMessages(conversation.id)
        assertEquals(1, rows.size)
        assertEquals(MessageKind.CONTROL, rows.single().messageType)
        val task = harness.taskStore.getTask("t1")!!
        assertEquals(2L, task.version)
        assertTrue(task.needsAttention)
        assertEquals(listOf("pause:t1"), harness.port.calls)
    }

    @Test
    fun `M2 - CHAT flows normally and history matches the session message row`() = runTest {
        val harness = LedgerHarness()
        var adapterCalls = 0
        val adapter = FakeAdapter { request ->
            adapterCalls += 1
            readFileScript("README.md")(request)
        }
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val engine = engineWithLedger(adapter, FakeApprovalGate(allow = true), harness)
        val events = engine.chat(conversation.id, "请读一下 README").toList()
        assertTrue(adapterCalls > 0)
        assertTrue(events.any { it is BrainEvent.ToolCall && it.name == "read_file" })
        val historyUser = conversations.listMessages(conversation.id).first { it.role == "user" }
        val sessionRows = harness.ledger.listMessages(conversation.id)
        assertEquals(1, sessionRows.size)
        assertEquals(historyUser.content, sessionRows.single().content)
        assertEquals(MessageKind.CHAT, sessionRows.single().messageType)
    }

    @Test
    fun `M2 - without an injected ledger the existing behaviour is unchanged`() = runTest {
        var adapterCalls = 0
        val adapter = FakeAdapter { request ->
            adapterCalls += 1
            readFileScript("README.md")(request)
        }
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val engine = engine(adapter, FakeApprovalGate(allow = true))
        val events = engine.chat(conversation.id, "请读一下 README").toList()
        assertTrue(adapterCalls > 0)
        assertTrue(events.any { it is BrainEvent.ToolCall && it.name == "read_file" })
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("已读取完毕") })
    }

    // ---------- P1: tool_round_limit 修复（2026-11-08） ----------

    /**
     * 复现 K70 真机现象的模型行为（fake transport / 测试替身）：
     * 拿到工具定义就回工具调用（问候语也不例外），拿不到工具定义才回文字。
     * 这正是默认 8B 模型在真机上的表现：连接成功，但“在吗”走到 tool_round_limit。
     */
    private fun greetingModelScript(
        seenRequests: MutableList<ChatRequest>,
    ): (ChatRequest) -> Flow<BrainEvent> = { request ->
        seenRequests += request
        if (request.tools.isEmpty()) {
            flowOf(BrainEvent.TextDelta("在呢，有什么可以帮你？"), BrainEvent.Done("stop"))
        } else {
            flowOf(
                BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                BrainEvent.Done("stop"),
            )
        }
    }

    @Test
    fun `P1 repro - greeting answers in text without tools or tool_round_limit`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "在吗")
        val engine = engine(
            FakeAdapter(greetingModelScript(seenRequests)),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "在吗").toList()
        // 首轮不带工具定义：模型直接回文字，而不是被工具列表 primed 去行动。
        assertTrue("首轮请求不应带工具定义", seenRequests.first().tools.isEmpty())
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("在呢") })
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_round_limit" })
        assertTrue(events.none { it is BrainEvent.Error })
        assertTrue(events.last() is BrainEvent.Done)
    }

    @Test
    fun `P1 - action intent still carries tools on the first round`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val adapter = FakeAdapter({ request ->
            seenRequests += request
            readFileScript("README.md")(request)
        })
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val engine = engine(adapter, FakeApprovalGate(allow = true))
        val events = engine.chat(conversation.id, "请读一下 README").toList()
        assertTrue(seenRequests.first().tools.any { it.name == "read_file" })
        assertTrue(events.any { it is BrainEvent.ToolCall && it.name == "read_file" })
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("已读取完毕") })
    }

    @Test
    fun `P1 - intent split classifies greetings as chat and actions as tools`() {
        assertFalse(BrainEngine.looksActionOriented("在吗"))
        assertFalse(BrainEngine.looksActionOriented("你好"))
        assertFalse(BrainEngine.looksActionOriented("今天天气怎么样？"))
        assertTrue(BrainEngine.looksActionOriented("请读一下 README"))
        assertTrue(BrainEngine.looksActionOriented("列出工作区里的文件"))
        assertTrue(BrainEngine.looksActionOriented("帮我运行一下测试"))
        assertTrue(BrainEngine.looksActionOriented("list the files in the workspace"))
        // F2（2026-11-08）：K70 ③ 轮回归——“任务”作宾语只是提问话题，
        // 不是行动指令；派任务改由明确动词触发。
        assertFalse(BrainEngine.looksActionOriented("你是谁？你能帮我完成什么任务？"))
        assertFalse(BrainEngine.looksActionOriented("What tasks can you help me with?"))
        assertTrue(BrainEngine.looksActionOriented("派个任务给 Codex"))
        assertTrue(BrainEngine.looksActionOriented("新建一个任务交给 Codex"))
        assertTrue(BrainEngine.looksActionOriented("把这个任务交给 Codex"))
        assertTrue(BrainEngine.looksActionOriented("assign this task to Codex"))
    }

    @Test
    fun `P1 - repeating the same write call stops with tool_call_repeated`() = runTest {
        val gate = FakeApprovalGate(allow = true)
        val conversation = conversations.createConversation("fake-brain", "写文件")
        val engine = engine(
            FakeAdapter {
                flowOf(
                    BrainEvent.ToolCall(
                        "call-1",
                        "write_file",
                        mapOf("path" to "README.md", "content" to "new content"),
                    ),
                    BrainEvent.Done("stop"),
                )
            },
            gate,
        )
        val events = engine.chat(conversation.id, "请把 README 改成 new content").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_call_repeated")
        assertTrue(error!!.message.contains("重复调用"))
        // 写类重复即停：同一调用只执行了一次，没有走到 8 轮上限；审批只问过一次。
        assertEquals(1, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertEquals(1, realExecutions(conversation.id))
        assertEquals(1, gate.asked.size)
        assertEquals("new content", File(workspaceRoot, "README.md").readText())
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_round_limit" })
    }

    @Test
    fun `P1 - two silent tool rounds stop with tool_no_progress`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter {
                // 每轮都同参再调 list_files("")（只读）：第一次真实执行，
                // 之后命中缓存（不执行），连续两轮无进展后由护栏 2 停。
                flowOf(
                    BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                    BrainEvent.Done("stop"),
                )
            },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请列出文件").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_no_progress")
        // 真实执行只有一次（缓存命中不执行）；ToolResult 事件含 2 次缓存回填。
        assertEquals(1, realExecutions(conversation.id))
        assertEquals(2, cachedHits(conversation.id))
        assertEquals(3, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_round_limit" })
    }

    @Test
    fun `P1 - loop limit of 8 rounds is retained for ever-changing calls`() = runTest {
        var round = 0
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter {
                round += 1
                flowOf(
                    // 每轮都有文字增量、每次换新参数：三道护栏都不触发，
                    // 只能由保留的 8 轮上限兜底。
                    BrainEvent.TextDelta("正在处理第 $round 轮"),
                    BrainEvent.ToolCall("call-$round", "list_files", mapOf("path" to "dir-$round")),
                    BrainEvent.Done("stop"),
                )
            },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请列出文件").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_round_limit")
        assertEquals(8, events.filterIsInstance<BrainEvent.ToolResult>().size)
    }

    @Test
    fun `P1 - tool failure is fed back once then the model answers in text`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val adapter = FakeAdapter({ request ->
            seenRequests += request
            if (request.messages.any { it.role == "tool" }) {
                flowOf(BrainEvent.TextDelta("这个文件不在工作区内，我没法读。"), BrainEvent.Done("stop"))
            } else {
                flowOf(
                    BrainEvent.ToolCall("call-1", "read_file", mapOf("path" to "../outside-secret.txt")),
                    BrainEvent.Done("stop"),
                )
            }
        })
        val conversation = conversations.createConversation("fake-brain", "读根外文件")
        val engine = engine(adapter, FakeApprovalGate(allow = true))
        val events = engine.chat(conversation.id, "请读外面的文件").toList()
        val result = events.filterIsInstance<BrainEvent.ToolResult>().firstOrNull()
        assertTrue(result != null && !result.ok)
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("不在工作区内") })
        assertTrue(events.none { it is BrainEvent.Error })
        // 失败结果确实回填给了模型（第二轮请求里带 tool 消息），内容是
        // 官方范式的结果 JSON：ok=false，summary 为失败原因。
        assertTrue(seenRequests.size >= 2)
        val toolMessage = seenRequests[1].messages.firstOrNull { it.role == "tool" }
        assertTrue(toolMessage != null)
        val toolJson = JSONObject(toolMessage!!.content)
        assertFalse(toolJson.getBoolean("ok"))
        assertTrue(toolJson.getString("summary").contains("不允许的路径"))
    }

    @Test
    fun `P1 - repeating the same terminal failure stops instead of looping`() = runTest {
        var round = 0
        val conversation = conversations.createConversation("fake-brain", "读根外文件")
        val engine = engine(
            FakeAdapter {
                round += 1
                flowOf(
                    // 每轮带一句文字，避免零文字护栏先触发；失败分类重复才是本案刹车。
                    BrainEvent.TextDelta("再试一个路径"),
                    BrainEvent.ToolCall(
                        "call-$round",
                        "read_file",
                        mapOf("path" to "../secret-$round.txt"),
                    ),
                    BrainEvent.Done("stop"),
                )
            },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请读外面的文件").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_repeated_failure")
        assertEquals(2, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_round_limit" })
    }

    @Test
    fun `P1 - quota error is reported once with no same-brain retry and no SIWC fallback`() = runTest {
        var adapterCalls = 0
        val adapter = FakeAdapter {
            adapterCalls += 1
            flowOf(
                BrainEvent.Error(
                    code = "quota_exceeded",
                    message = "Cloudflare 今日免费额度已用完。",
                    retryable = false,
                ),
                BrainEvent.Done("error"),
            )
        }
        val conversation = conversations.createConversation("fake-brain", "在吗")
        val engine = engine(
            adapter,
            FakeApprovalGate(allow = true),
            failoverPolicy = FailoverPolicy(
                maxSameBrainRetries = 1,
                fallbackBrainIds = listOf("siwc-chatgpt"),
            ),
        )
        val events = engine.chat(conversation.id, "在吗").toList()
        assertEquals(1, adapterCalls)
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "quota_exceeded")
        assertFalse(error!!.retryable)
        assertEquals(BrainEvent.Done("error"), events.last())
    }

    @Test
    fun `P1 - model without tool support gets no tools and answers in text`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val adapter = FakeAdapter { request ->
            seenRequests += request
            flowOf(BrainEvent.TextDelta("这是 README 的内容摘要。"), BrainEvent.Done("stop"))
        }.also { it.toolCalling = false }
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val engine = engine(adapter, FakeApprovalGate(allow = true))
        val events = engine.chat(conversation.id, "请读一下 README").toList()
        assertTrue(seenRequests.first().tools.isEmpty())
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("内容摘要") })
        assertTrue(events.none { it is BrainEvent.ToolCall })
        assertTrue(events.none { it is BrainEvent.Error })
    }

    @Test
    fun `P1 - tool-less brain is not primed with tools it cannot call`() = runTest {
        // 通用大脑放开切换（maintainer 2026-11-11）：首轮上下文只列本轮大脑
        // 真正能调的工具。不能调工具的大脑收到行动型消息时，上下文里
        // 不能出现“可用工具”提示——否则模型会被 primed 去“执行”它
        // 调不了的工具，而请求里实际一个工具定义都没有。
        val seenRequests = mutableListOf<ChatRequest>()
        val adapter = FakeAdapter { request ->
            seenRequests += request
            flowOf(BrainEvent.TextDelta("这是文字回答。"), BrainEvent.Done("stop"))
        }.also { it.toolCalling = false }
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val engine = engine(adapter, FakeApprovalGate(allow = true))
        engine.chat(conversation.id, "请读一下 README").toList()
        assertTrue(seenRequests.isNotEmpty())
        assertTrue(seenRequests.first().tools.isEmpty())
        assertTrue(
            seenRequests.first().messages.none {
                it.role == "system" && it.content.contains("可用工具")
            },
        )
    }

    @Test
    fun `P1 - tool-capable brain still gets the tool list in context`() = runTest {
        // 正向对照：能调工具的大脑，行动型消息的首轮上下文仍列出工具。
        val seenRequests = mutableListOf<ChatRequest>()
        val adapter = FakeAdapter { request ->
            seenRequests += request
            flowOf(BrainEvent.TextDelta("已读。"), BrainEvent.Done("stop"))
        }
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val engine = engine(adapter, FakeApprovalGate(allow = true))
        engine.chat(conversation.id, "请读一下 README").toList()
        assertTrue(seenRequests.isNotEmpty())
        assertTrue(seenRequests.first().tools.isNotEmpty())
        assertTrue(
            seenRequests.first().messages.any {
                it.role == "system" && it.content.contains("可用工具")
            },
        )
    }

    @Test
    fun `P1 - empty model response is reported as an explicit error`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "在吗")
        val engine = engine(
            FakeAdapter { flowOf(BrainEvent.Done("stop")) },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "在吗").toList()
        // 2026-11-09 K70 blank-bubble fix: an empty turn must never look like
        // a successful reply; it reports empty_response and Done("error").
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("empty_response", error.code)
        assertEquals(BrainEvent.Done("error"), events.last())
    }

    @Test
    fun `tool_call_repeated fix - tool result is fed back as official tool-role JSON with assistant echo`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val adapter = FakeAdapter({ request ->
            seenRequests += request
            readFileScript("README.md")(request)
        })
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val engine = engine(adapter, FakeApprovalGate(allow = true))
        engine.chat(conversation.id, "请读一下 README").toList()
        assertTrue(seenRequests.size >= 2)
        val secondMessages = seenRequests[1].messages
        // 官方范式：assistant 回显在前（工具调用 JSON），tool 结果在后。
        val echo = secondMessages.firstOrNull { it.role == "assistant" }
        assertTrue(echo != null)
        val echoJson = JSONObject(echo!!.content)
        assertEquals("read_file", echoJson.getString("name"))
        assertEquals("README.md", echoJson.getJSONObject("arguments").getString("path"))
        val toolMessage = secondMessages.firstOrNull { it.role == "tool" }
        assertTrue(toolMessage != null)
        assertEquals("read_file", toolMessage!!.name)
        val toolJson = JSONObject(toolMessage.content)
        assertEquals("read_file", toolJson.getString("tool"))
        assertTrue(toolJson.getBoolean("ok"))
        assertTrue(toolJson.getString("summary").contains("已读取"))
        assertTrue(toolJson.getString("data").contains("hello from workspace"))
        // USER 形态变通做法已废止：回填里不再有指引文本与“工具…执行结果：”前缀。
        assertTrue(secondMessages.none { it.content.contains("不要重复调用同一工具") })
        assertTrue(secondMessages.none { it.content.contains("工具执行结果：") })
        assertTrue(secondMessages.none { it.content.contains("请基于它继续") })
    }

    // ---------- tool_call_repeated 修复（2026-11-08） ----------

    /**
     * K70 复现的端到端回归：模型首轮调 list_files（工作区有文件），
     * 工具结果回填后模型第二轮直接回自然语言汇报——不再同参再调。
     * 假模型按“有没有收到工具结果”行动：收到 tool 消息就回文字。
     * （P1 多步骤后 tools 不再被剥夺，所以不能再按 tools 是否为空分流。）
     */
    private fun listFilesThenReportScript(
        seenRequests: MutableList<ChatRequest>,
    ): (ChatRequest) -> Flow<BrainEvent> = { request ->
        seenRequests += request
        if (request.messages.any { it.role == "tool" }) {
            flowOf(BrainEvent.TextDelta("工作区里有 README.md 这一个文件。"), BrainEvent.Done("stop"))
        } else {
            flowOf(
                BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                BrainEvent.Done("stop"),
            )
        }
    }

    @Test
    fun `tool_call_repeated fix - list_files result is reported in text, second round carries tools`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter(listFilesThenReportScript(seenRequests)),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "列出工作区里的文件").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        // 工具只执行了一次；最终文本含文件名。
        assertEquals(1, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("README.md") })
        assertTrue(seenRequests.size >= 2)
        // P1 多步骤：结构性拦截已移除——回填后第二轮仍带工具定义，
        // 模型是自己选择回文字（不是协议剥夺了工具）。
        assertTrue("回填后第二轮应仍带工具定义", seenRequests[1].tools.isNotEmpty())
        // 官方回填：assistant 回显 JSON + role="tool" 结果 JSON，name 已填。
        val secondMessages = seenRequests[1].messages
        val echo = secondMessages.firstOrNull { it.role == "assistant" }
        assertTrue(echo != null)
        assertEquals("list_files", JSONObject(echo!!.content).getString("name"))
        val toolMessage = secondMessages.firstOrNull { it.role == "tool" }
        assertTrue(toolMessage != null)
        assertEquals("list_files", toolMessage!!.name)
        val toolJson = JSONObject(toolMessage.content)
        assertEquals("list_files", toolJson.getString("tool"))
        assertTrue(toolJson.getBoolean("ok"))
        assertTrue(toolJson.getString("summary").contains("（1 项）"))
        assertTrue(toolJson.getString("data").contains("README.md"))
        // 两条回填消息都持久化进了历史存储。
        val stored = conversations.listMessages(conversation.id)
        assertTrue(stored.any { it.role == "assistant" && it.source == "tool-call:list_files" })
        assertTrue(stored.any { it.role == "tool" && it.source == "tool:list_files" })
    }

    @Test
    fun `tool_call_repeated fix - empty workspace answers directly without another call`() = runTest {
        File(workspaceRoot, "README.md").delete()
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                if (request.messages.any { it.role == "tool" }) {
                    flowOf(BrainEvent.TextDelta("工作区是空的，没有文件。"), BrainEvent.Done("stop"))
                } else {
                    flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "列出工作区里的文件").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        assertEquals(1, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("工作区是空的") })
        // 空目录摘要本身就是自足答案：0 项，模型无需再调工具——这是模型决策，
        // 不是协议剥夺：第二轮仍带工具定义。
        assertTrue(seenRequests[1].tools.isNotEmpty())
        val toolMessage = seenRequests[1].messages.firstOrNull { it.role == "tool" }
        assertTrue(toolMessage != null)
        val toolJson = JSONObject(toolMessage!!.content)
        assertTrue(toolJson.getBoolean("ok"))
        assertTrue(toolJson.getString("summary").contains("（0 项）"))
        assertTrue(toolJson.getString("summary").contains("工作区是空的"))
        // P1 §6：三态身份字段随 tool JSON 透传。
        assertEquals("empty", toolJson.getString("state"))
        assertEquals("", toolJson.getString("path"))
        assertEquals("app-workspace", toolJson.getString("workspace"))
        assertEquals("local", toolJson.getString("host"))
        assertTrue(toolJson.getString("summary").contains("本机应用工作区"))
    }

    @Test
    fun `tool_call_repeated fix - unreadable directory fails clearly without same-arg retry`() = runTest {
        val root = File(workspaceRoot, ".")
        // 先确认本环境能让 listFiles() 返回 null（以非 root 运行、chmod 000
        // 即可；若环境是 root 导致权限位拦不住，跳过本测试而非误报失败）。
        root.setReadable(false, false)
        root.setExecutable(false, false)
        try {
            assumeTrue("本环境无法模拟不可读目录", root.listFiles() == null)
            // 直接执行：错误分类明确、可理解。
            val outcome = tools.execute(
                Action(
                    actionId = "call-perm",
                    tool = "list_files",
                    args = mapOf("path" to ""),
                    requiresApproval = false,
                    idempotencyKey = "perm-test",
                ),
                ToolContext(
                    taskId = "perm-test",
                    agentId = "brain",
                    approvalGate = FakeApprovalGate(allow = true),
                    workspaceRoot = workspaceRoot,
                    deviceId = "local",
                    folder = workspaceRoot.path,
                ),
            )
            assertFalse(outcome.ok)
            assertEquals("hand_not_readable", outcome.errorCode)
            assertTrue(outcome.summary.contains("无法读取该目录"))
            // 引擎流程：失败只反馈一次，不再同参重试、不空转。
            val seenRequests = mutableListOf<ChatRequest>()
            val conversation = conversations.createConversation("fake-brain", "列文件")
            val engine = engine(
                FakeAdapter({ request ->
                    seenRequests += request
                    if (request.messages.any { it.role == "tool" }) {
                        flowOf(
                            BrainEvent.TextDelta("这个目录我打不开，没有权限查看。"),
                            BrainEvent.Done("stop"),
                        )
                    } else {
                        flowOf(
                            BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                            BrainEvent.Done("stop"),
                        )
                    }
                }),
                FakeApprovalGate(allow = true),
            )
            val events = engine.chat(conversation.id, "列出工作区里的文件").toList()
            assertTrue(events.none { it is BrainEvent.Error })
            assertEquals(1, events.filterIsInstance<BrainEvent.ToolResult>().size)
            assertTrue(events.filterIsInstance<BrainEvent.ToolResult>().single().ok.not())
            assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("没有权限") })
            // P1 §6：不可读不是空目录——tool JSON 带 not_readable 三态字段。
            val toolJson = JSONObject(
                seenRequests[1].messages.first { it.role == "tool" }.content,
            )
            assertFalse(toolJson.getBoolean("ok"))
            assertEquals("not_readable", toolJson.getString("state"))
            assertTrue(toolJson.getString("summary").contains("不是空目录"))
        } finally {
            root.setReadable(true, false)
            root.setExecutable(true, false)
        }
    }

    @Test
    fun `tool_call_repeated fix - tool failure is fed back once as JSON and the model answers in text`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val adapter = FakeAdapter({ request ->
            seenRequests += request
            if (request.messages.any { it.role == "tool" }) {
                flowOf(BrainEvent.TextDelta("这个文件不在工作区内，我没法读。"), BrainEvent.Done("stop"))
            } else {
                flowOf(
                    BrainEvent.ToolCall("call-1", "read_file", mapOf("path" to "../outside-secret.txt")),
                    BrainEvent.Done("stop"),
                )
            }
        })
        val conversation = conversations.createConversation("fake-brain", "读根外文件")
        val engine = engine(adapter, FakeApprovalGate(allow = true))
        val events = engine.chat(conversation.id, "请读外面的文件").toList()
        val result = events.filterIsInstance<BrainEvent.ToolResult>().firstOrNull()
        assertTrue(result != null && !result.ok)
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("不在工作区内") })
        assertTrue(events.none { it is BrainEvent.Error })
        // 失败只反馈一次：第二轮模型直接回文字（P1 多步骤后第二轮仍带工具定义，
        // 模型是自己选择回文字，不再是“无工具可调”）。
        assertTrue(seenRequests.size >= 2)
        assertTrue(seenRequests[1].tools.isNotEmpty())
        val toolJson = JSONObject(
            seenRequests[1].messages.first { it.role == "tool" }.content,
        )
        assertFalse(toolJson.getBoolean("ok"))
    }

    @Test
    fun `tool_call_repeated fix - empty blank and dot paths are the same call`() = runTest {
        var round = 0
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter { request ->
                round += 1
                seenRequests += request
                // 三种写法轮流出现：缺省、""、"."——归一化后是同一签名。
                val args = when (round) {
                    1 -> emptyMap<String, Any?>()
                    2 -> mapOf("path" to "")
                    else -> mapOf("path" to ".")
                }
                flowOf(
                    BrainEvent.ToolCall("call-$round", "list_files", args),
                    BrainEvent.Done("stop"),
                )
            },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请列出文件").toList()
        // P1 多步骤：只读同参不再由护栏 1 一刀切停——第二轮（"" 写法）
        // 命中缓存回填（cached=true，不执行），连续无进展后由护栏 2 停。
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_no_progress")
        assertEquals(1, realExecutions(conversation.id))
        assertEquals(2, cachedHits(conversation.id))
        // 第三轮请求里带的是第二轮缓存命中的回填（cached=true，原轮次 0）；
        // 第二轮请求里的 tool 消息还是首次真实执行的结果，不带 cached。
        val thirdToolJson = toolJsonOf(seenRequests, 2)
        assertTrue(thirdToolJson != null && thirdToolJson.getBoolean("cached"))
        assertEquals(0, thirdToolJson!!.getInt("cachedAtRound"))
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_round_limit" })
    }

    @Test
    fun `tool_call_repeated fix - model retrying after feedback gets tools and a cache hit`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                // 假模型无视回填结果，回填后仍试图同参再调。
                flowOf(
                    BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                    BrainEvent.Done("stop"),
                )
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请列出文件").toList()
        assertTrue(seenRequests.size >= 2)
        // P1 多步骤：结构性拦截已移除——第二轮请求仍带工具定义。
        assertTrue(seenRequests[1].tools.isNotEmpty())
        // 只读同参命中缓存（不执行），连续无进展后由护栏 2 停。
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_no_progress")
        assertEquals(1, realExecutions(conversation.id))
        // 第三轮请求里带的是第二轮缓存命中的回填（cached=true）。
        val thirdToolJson = toolJsonOf(seenRequests, 2)
        assertTrue(thirdToolJson != null && thirdToolJson.getBoolean("cached"))
    }

    // ---------- P1 多步骤（2026-11-08）：§8.2 新增用例 #2–#17 ----------

    @Test
    fun `P1 multistep 4 - unconfigured workspace is reported, never listed as empty`() = runTest {
        val events = mutableListOf<Pair<String, String>>()
        val unconfigured = ToolRegistry(
            JvmDeviceAdapter(emptyList()),
            FileWorkspaceAdapter(workspaceRoot, File(tmpDir, "task-root-2").apply { mkdirs() }),
            CommandLimits(),
            FakeTaskStore(),
            InMemoryAuditLog(),
            eventSink = { kind, detail -> events += kind to detail },
        )
        assertEquals(null, unconfigured.workspaceRoot)
        assertEquals(WorkspaceState.NOT_CONFIGURED, unconfigured.workspaceState())
        val outcome = unconfigured.execute(
            Action(
                actionId = "call-1",
                tool = "list_files",
                args = mapOf("path" to ""),
                requiresApproval = false,
                idempotencyKey = "p1-4",
            ),
            ToolContext(
                taskId = "p1-4",
                agentId = "brain",
                approvalGate = FakeApprovalGate(allow = true),
                workspaceRoot = File(""),
                deviceId = "local",
                folder = "",
            ),
        )
        assertFalse(outcome.ok)
        assertEquals("workspace_not_configured", outcome.errorCode)
        assertEquals("not_configured", outcome.workspaceState)
        assertTrue(outcome.summary.contains("未配置工作区"))
        // 绝不断言“（0 项）”：未配置不是空。
        assertFalse(outcome.summary.contains("0 项"))
    }

    @Test
    fun `P1 multistep 5 - list read write test final completes in five rounds`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val gate = FakeApprovalGate(allow = true)
        val conversation = conversations.createConversation("fake-brain", "改 README")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                val toolCount = request.messages.count { it.role == "tool" }
                when (toolCount) {
                    0 -> flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                    1 -> flowOf(
                        BrainEvent.ToolCall("call-2", "read_file", mapOf("path" to "README.md")),
                        BrainEvent.Done("stop"),
                    )
                    2 -> flowOf(
                        BrainEvent.ToolCall(
                            "call-3",
                            "write_file",
                            mapOf("path" to "README.md", "content" to "hello from workspace\nhello"),
                        ),
                        BrainEvent.Done("stop"),
                    )
                    3 -> flowOf(
                        BrainEvent.ToolCall(
                            "call-4",
                            "run_command",
                            mapOf("argv" to listOf("cat", "README.md")),
                        ),
                        BrainEvent.Done("stop"),
                    )
                    else -> flowOf(
                        BrainEvent.TextDelta("已写入并验证：文件内容包含 hello。"),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            gate,
        )
        val events = engine.chat(conversation.id, "请列出文件，读 README.md，改 README.md 末尾加一行 hello，然后运行 cat 验证").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        // 5 轮内完成；真实执行 4 次；每轮 tools 非空（拦截已移除的直接证据）。
        assertEquals(4, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertEquals(4, realExecutions(conversation.id))
        assertTrue(seenRequests.size == 5)
        assertTrue(seenRequests.all { it.tools.isNotEmpty() })
        // 回填顺序每步都是 assistant 回显在前、tool 结果在后。
        for (i in 1..4) {
            val msgs = seenRequests[i].messages
            val echoIdx = msgs.indexOfFirst { it.role == "assistant" }
            val toolIdx = msgs.indexOfFirst { it.role == "tool" }
            assertTrue(echoIdx >= 0 && toolIdx > echoIdx)
        }
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("hello") })
        assertTrue(File(workspaceRoot, "README.md").readText().contains("hello"))
        assertTrue(gate.asked.any { it.tool == "write_file" })
    }

    @Test
    fun `P1 multistep 6 - same tool with different params executes twice`() = runTest {
        File(workspaceRoot, "NOTES.md").writeText("notes-content", Charsets.UTF_8)
        val conversation = conversations.createConversation("fake-brain", "读两份文件")
        val engine = engine(
            FakeAdapter({ request ->
                val toolCount = request.messages.count { it.role == "tool" }
                when (toolCount) {
                    0 -> flowOf(
                        BrainEvent.ToolCall("call-1", "read_file", mapOf("path" to "README.md")),
                        BrainEvent.Done("stop"),
                    )
                    1 -> flowOf(
                        BrainEvent.ToolCall("call-2", "read_file", mapOf("path" to "NOTES.md")),
                        BrainEvent.Done("stop"),
                    )
                    else -> flowOf(
                        BrainEvent.TextDelta("两份都读完了。"),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "读一下 README 和 NOTES").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        // 两次都真实执行（签名不同，不命中缓存）。
        assertEquals(2, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertEquals(2, realExecutions(conversation.id))
        assertEquals(0, cachedHits(conversation.id))
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_call_repeated" })
        val stored = conversations.listMessages(conversation.id)
        assertTrue(stored.any { it.role == "tool" && it.content.contains("hello from workspace") })
        assertTrue(stored.any { it.role == "tool" && it.content.contains("notes-content") })
    }

    @Test
    fun `P1 multistep 7 - repeated read-only call hits cache once then stops on no progress`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                val toolCount = request.messages.count { it.role == "tool" }
                flowOf(
                    BrainEvent.ToolCall(
                        "call-${toolCount + 1}",
                        "list_files",
                        mapOf("path" to ""),
                    ),
                    BrainEvent.Done("stop"),
                )
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请列出文件").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_no_progress")
        // 第一次重复：缓存命中回填，cached=true，不执行；第二次重复且无文字增长：停。
        assertEquals(1, realExecutions(conversation.id))
        assertEquals(2, cachedHits(conversation.id))
        val thirdToolJson = toolJsonOf(seenRequests, 2)
        assertTrue(thirdToolJson != null && thirdToolJson.getBoolean("cached"))
        assertEquals(0, thirdToolJson!!.getInt("cachedAtRound"))
        // toolCallId 用本次调用的 id（call-2），不是原记录的 call-1。
        assertEquals("call-2", seenRequests[2].messages.last { it.role == "tool" }.toolCallId)
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_call_repeated" })
    }

    @Test
    fun `P1 multistep 8b - repeated non-readonly run_command stops without re-executing`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "跑命令")
        val engine = engine(
            FakeAdapter {
                flowOf(
                    BrainEvent.ToolCall(
                        "call-1",
                        "run_command",
                        mapOf("argv" to listOf("sleep", "0")),
                    ),
                    BrainEvent.Done("stop"),
                )
            },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请运行 sleep 0").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_call_repeated")
        // 非只读 run_command 按写类处理：重复即停，只执行一次。
        assertEquals(1, realExecutions(conversation.id))
        assertEquals(0, cachedHits(conversation.id))
    }

    @Test
    fun `P1 multistep 9 - failed tool call can retry with different params`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val engine = engine(
            FakeAdapter({ request ->
                val toolCount = request.messages.count { it.role == "tool" }
                when (toolCount) {
                    0 -> flowOf(
                        BrainEvent.ToolCall("call-1", "read_file", mapOf("path" to "missing.md")),
                        BrainEvent.Done("stop"),
                    )
                    1 -> flowOf(
                        BrainEvent.ToolCall("call-2", "read_file", mapOf("path" to "README.md")),
                        BrainEvent.Done("stop"),
                    )
                    else -> flowOf(
                        BrainEvent.TextDelta("读到了：hello from workspace"),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "读一下 missing，再读 README").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        assertEquals(2, events.filterIsInstance<BrainEvent.ToolResult>().size)
        val first = events.filterIsInstance<BrainEvent.ToolResult>()[0]
        assertFalse(first.ok)
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("hello from workspace") })
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_repeated_failure" })
    }

    @Test
    fun `P1 multistep 3b - failed read repeated on the same target stops with tool_repeated_failure, never loops`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "读不存在的文件")
        val engine = engine(
            FakeAdapter {
                flowOf(
                    BrainEvent.ToolCall("call-1", "read_file", mapOf("path" to "missing.md")),
                    BrainEvent.Done("stop"),
                )
            },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "读一下 missing.md").toList()
        // tool_no_progress 修复（2026-11-09）第 1 条：同一归一化目标第二次
        // hand_not_found 即按终结性失败处理（护栏 3 同款终结），不再等护栏 2
        // 在 3 次执行后收敛——同一目标重复读不到，再试也没有意义。
        // 失败结果仍不缓存：两次同参都会真实执行，但第二次失败即停，不无限循环。
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_repeated_failure")
        assertEquals(2, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertEquals(2, realExecutions(conversation.id))
        assertEquals(0, cachedHits(conversation.id))
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_round_limit" })
    }

    @Test
    fun `P1 multistep 11 - missing tool result is detected by the guard function`() {
        // 直测守卫函数：上一轮的 ToolCall 在消息里找不到对应 tool 消息。
        val calls = listOf(
            BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
            BrainEvent.ToolCall("call-2", "read_file", mapOf("path" to "README.md")),
        )
        val complete = listOf(
            ChatMessage(role = "user", content = "列文件"),
            ChatMessage(role = "assistant", content = "{\"name\":\"list_files\"}"),
            ChatMessage(role = "tool", content = "{\"tool\":\"list_files\"}", toolCallId = "call-1", name = "list_files"),
            ChatMessage(role = "assistant", content = "{\"name\":\"read_file\"}"),
            ChatMessage(role = "tool", content = "{\"tool\":\"read_file\"}", toolCallId = "call-2", name = "read_file"),
        )
        assertTrue(BrainEngine.findMissingToolResults(complete, calls).isEmpty())
        // 删掉第二条 tool 消息（模拟回填丢失 / 压缩丢结果）。
        val lost = complete.filterNot { it.role == "tool" && it.toolCallId == "call-2" }
        assertEquals(listOf("call-2"), BrainEngine.findMissingToolResults(lost, calls))
        assertTrue(BrainEngine.findMissingToolResults(complete, emptyList()).isEmpty())
    }

    @Test
    fun `P1 multistep 12 - quota exhausted after tools keeps executed results and never retries`() = runTest {
        var adapterCalls = 0
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter({ request ->
                adapterCalls += 1
                seenRequests += request
                if (request.messages.any { it.role == "tool" }) {
                    flowOf(
                        BrainEvent.TextDelta("Cloudflare 今日免费额度已用完。"),
                        BrainEvent.Error(
                            code = "quota_exceeded",
                            message = "Cloudflare 今日免费额度已用完。",
                            retryable = false,
                        ),
                        BrainEvent.Done("error"),
                    )
                } else {
                    flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
            failoverPolicy = FailoverPolicy(
                maxSameBrainRetries = 1,
                fallbackBrainIds = listOf("siwc-chatgpt"),
            ),
        )
        val events = engine.chat(conversation.id, "列出工作区里的文件").toList()
        assertEquals(2, adapterCalls)
        // 超额提示 TextDelta 先出、Error 后出；无同大脑重试、无 SIWC fallback。
        val textIdx = events.indexOfFirst { it is BrainEvent.TextDelta }
        val errIdx = events.indexOfFirst { it is BrainEvent.Error }
        assertTrue(textIdx >= 0 && errIdx > textIdx)
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "quota_exceeded")
        assertFalse(error!!.retryable)
        // 已执行步骤的结果已回填持久化。
        assertEquals(1, events.filterIsInstance<BrainEvent.ToolResult>().size)
        val stored = conversations.listMessages(conversation.id)
        assertTrue(stored.any { it.role == "tool" && it.content.contains("README.md") })
        assertEquals(BrainEvent.Done("error"), events.last())
    }

    @Test
    fun `P1 multistep 13a - stop mid-turn executes zero further calls including writes`() = runTest {
        var control = TurnControlState.RUNNING
        val conversation = conversations.createConversation("fake-brain", "改文件")
        val engine = engine(
            FakeAdapter({ request ->
                val toolCount = request.messages.count { it.role == "tool" }
                when (toolCount) {
                    0 -> flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                    else -> {
                        // 停止指令在第二轮模型决策后到达：写调用已在待执行队列里。
                        control = TurnControlState.STOPPED
                        flowOf(
                            BrainEvent.ToolCall(
                                "call-2",
                                "write_file",
                                mapOf("path" to "README.md", "content" to "must-not-land"),
                            ),
                            BrainEvent.Done("stop"),
                        )
                    }
                }
            }),
            FakeApprovalGate(allow = true),
            controlState = { control },
        )
        val events = engine.chat(conversation.id, "列出文件然后改 README").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "turn_stopped")
        assertEquals(1, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertEquals(1, realExecutions(conversation.id))
        assertEquals("hello from workspace", File(workspaceRoot, "README.md").readText())
        assertEquals(BrainEvent.Done("stopped"), events.last())
    }

    @Test
    fun `P1 multistep 13b - pause mid-turn stops new calls with turn_paused`() = runTest {
        var control = TurnControlState.RUNNING
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter({ request ->
                val toolCount = request.messages.count { it.role == "tool" }
                if (toolCount == 0) {
                    flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                } else {
                    control = TurnControlState.PAUSED
                    flowOf(
                        BrainEvent.ToolCall("call-2", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
            controlState = { control },
        )
        val events = engine.chat(conversation.id, "请列出文件").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "turn_paused")
        assertEquals(1, realExecutions(conversation.id))
        assertEquals(BrainEvent.Done("paused"), events.last())
    }

    @Test
    fun `P1 multistep 13c - goal change voids the old turn with goal_changed`() = runTest {
        var control = TurnControlState.RUNNING
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter({ request ->
                val toolCount = request.messages.count { it.role == "tool" }
                if (toolCount == 0) {
                    flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                } else {
                    control = TurnControlState.GOAL_CHANGED
                    flowOf(
                        BrainEvent.ToolCall("call-2", "read_file", mapOf("path" to "README.md")),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
            controlState = { control },
        )
        val events = engine.chat(conversation.id, "请列出文件").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "goal_changed")
        assertEquals(1, realExecutions(conversation.id))
        assertEquals(BrainEvent.Done("goal_changed"), events.last())
    }

    @Test
    fun `P1 multistep 13d - registry second gate refuses execution when stopped`() = runTest {
        val outcome = tools.execute(
            Action(
                actionId = "call-x",
                tool = "write_file",
                args = mapOf("path" to "README.md", "content" to "must-not-land"),
                requiresApproval = false,
                idempotencyKey = "p1-13d",
            ),
            ToolContext(
                taskId = "p1-13d",
                agentId = "brain",
                approvalGate = FakeApprovalGate(allow = true),
                workspaceRoot = workspaceRoot,
                deviceId = "local",
                folder = workspaceRoot.path,
            ),
            stopped = { true },
        )
        assertFalse(outcome.ok)
        assertEquals("turn_stopped", outcome.errorCode)
        assertEquals("hello from workspace", File(workspaceRoot, "README.md").readText())
    }

    @Test
    fun `P1 multistep 14a - tool call budget stops the chain with tool_call_budget_exceeded`() = runTest {
        File(workspaceRoot, "NOTES.md").writeText("notes-content", Charsets.UTF_8)
        File(workspaceRoot, "THIRD.md").writeText("third-content", Charsets.UTF_8)
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val engine = engine(
            FakeAdapter({ request ->
                val toolCount = request.messages.count { it.role == "tool" }
                when (toolCount) {
                    0 -> flowOf(
                        BrainEvent.ToolCall("call-1", "read_file", mapOf("path" to "README.md")),
                        BrainEvent.Done("stop"),
                    )
                    1 -> flowOf(
                        BrainEvent.ToolCall("call-2", "read_file", mapOf("path" to "NOTES.md")),
                        BrainEvent.Done("stop"),
                    )
                    2 -> flowOf(
                        BrainEvent.ToolCall("call-3", "read_file", mapOf("path" to "THIRD.md")),
                        BrainEvent.Done("stop"),
                    )
                    else -> flowOf(BrainEvent.TextDelta("读完了"), BrainEvent.Done("stop"))
                }
            }),
            FakeApprovalGate(allow = true),
            maxToolCallsPerTurn = 2,
        )
        val events = engine.chat(conversation.id, "反复读 README、NOTES、THIRD").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_call_budget_exceeded")
        // 预算只计真实执行：前两次不同参数各执行一次，第三次执行前即停。
        assertEquals(2, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertEquals(2, realExecutions(conversation.id))
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_round_limit" })
        // 超限后 tools 为空（模型只能文字收尾）：超限发生在执行前，本轮无新请求。
        assertEquals(3, events.filterIsInstance<BrainEvent.ToolCall>().size)
    }

    @Test
    fun `P1 multistep 14b - turn timeout stops with turn_timeout on a controlled clock`() = runTest {
        var now = 0L
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter({ request ->
                // 每次模型调用推进可控时钟 60 秒：第二轮头即超 120 秒上限。
                now += 60_000
                val toolCount = request.messages.count { it.role == "tool" }
                if (toolCount == 0) {
                    flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                } else {
                    flowOf(
                        BrainEvent.ToolCall("call-2", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
            maxTurnMillis = 120_000,
            nowMillis = { now },
        )
        val events = engine.chat(conversation.id, "请列出文件").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "turn_timeout")
        assertEquals(1, realExecutions(conversation.id))
    }

    @Test
    fun `P1 multistep 16 - model ignoring tools ends in text with a tools_unused log`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "读文件")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                flowOf(BrainEvent.TextDelta("我直接回答，不调工具。"), BrainEvent.Done("stop"))
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请读一下 README").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        assertTrue(events.none { it is BrainEvent.ToolCall })
        assertTrue(seenRequests.first().tools.isNotEmpty())
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("直接回答") })
        assertTrue(
            eventLog.entries(conversation.id).any { it.kind == "tools_unused" },
        )
    }

    @Test
    fun `P1 multistep 17 - tool role rejected by server stops as unsupported without user-form retry`() = runTest {
        var adapterCalls = 0
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter({ request ->
                adapterCalls += 1
                seenRequests += request
                if (request.messages.any { it.role == "tool" }) {
                    // 服务端拒绝 role=tool（400 → Unsupported）：如实停，
                    // 不自动把回填改回 USER 形态重试。
                    flowOf(
                        BrainEvent.Error(
                            code = "unsupported",
                            message = "服务端不支持 role=tool 的回填。",
                            retryable = false,
                        ),
                        BrainEvent.Done("error"),
                    )
                } else {
                    flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "列出工作区里的文件").toList()
        assertEquals(2, adapterCalls)
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "unsupported")
        assertEquals(BrainEvent.Done("error"), events.last())
        // 没有第三次调用（即没有 USER 形态重试）；没有任何 user 消息里出现
        // 工具结果内容（ContextManager 本就会把当前消息追加一次，所以 user
        // 消息恒为 2 条内容相同的消息，断言点是内容而非条数）。
        assertTrue(seenRequests.all { req ->
            req.messages.filter { it.role == "user" }.none { it.content.contains("README.md") }
        })
    }

    @Test
    fun `P1 multistep failover - cache does not cross brains after a switch`() = runTest {
        BrainAdapterRegistry.registerConfig(
            BrainConfig(
                brainId = "fake-brain-2",
                kind = BrainKind.MODEL_API,
                adapterId = "fake-test-adapter",
                label = "Fake 2（测试替身）",
                baseUrl = null,
                model = "fake-model",
                authRef = null,
            ),
        )
        val conversation = conversations.createConversation("fake-brain", "列文件")
        var errorEmitted = false
        val engine = engine(
            FakeAdapter({ request ->
                val toolCount = request.messages.count { it.role == "tool" }
                when {
                    toolCount == 0 -> flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                    toolCount == 1 && !errorEmitted -> {
                        // 第一轮执行后报一次可重试错误：同大脑重试 0 次，
                        // 引擎按 fallback 列表换到 fake-brain-2，重试同一轮。
                        errorEmitted = true
                        flowOf(
                            BrainEvent.Error(
                                code = "brain_call_failed",
                                message = "临时失败",
                                retryable = true,
                            ),
                            BrainEvent.Done("error"),
                        )
                    }
                    toolCount == 1 -> flowOf(
                        // 换大脑后模型再次发出同一只读调用：缓存 key 含 brainId，
                        // 旧缓存不命中，会重新执行一次（读调用重执行无害）。
                        BrainEvent.ToolCall("call-2", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                    else -> flowOf(
                        BrainEvent.TextDelta("换大脑后重新列出并汇报完毕。"),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
            failoverPolicy = FailoverPolicy(
                maxSameBrainRetries = 0,
                fallbackBrainIds = listOf("fake-brain-2"),
            ),
        )
        val events = engine.chat(conversation.id, "请列出文件").toList()
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_call_repeated" })
        // 同一调用在两个大脑下各真实执行一次（缓存不跨大脑），随后文字收尾。
        assertEquals(2, realExecutions(conversation.id))
        assertEquals(0, cachedHits(conversation.id))
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("换大脑后") })
        assertEquals(BrainEvent.Done("switched_brain"), events.last())
    }

    @Test
    fun `P1 multistep cache - cache does not cross user messages`() = runTest {
        // 注意：第二条消息的请求里仍带第一条消息的 tool 消息（历史），
        // 所以脚本不能按“请求里有没有 tool 消息”分流，改按调用次数分流。
        var adapterCalls = 0
        val script: (ChatRequest) -> Flow<BrainEvent> = {
            adapterCalls += 1
            if (adapterCalls == 3 || adapterCalls == 5) {
                flowOf(BrainEvent.TextDelta("列完了。"), BrainEvent.Done("stop"))
            } else {
                flowOf(
                    BrainEvent.ToolCall("call-$adapterCalls", "list_files", mapOf("path" to "")),
                    BrainEvent.Done("stop"),
                )
            }
        }
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(FakeAdapter(script), FakeApprovalGate(allow = true))
        engine.chat(conversation.id, "请列出文件").toList()
        assertEquals(1, realExecutions(conversation.id))
        // 下一条消息是新的用户意图：同一调用必须重新执行，不得用旧缓存回答。
        engine.chat(conversation.id, "再列一次文件").toList()
        assertEquals(2, realExecutions(conversation.id))
        // 第一条消息内第 2 次调用命中过一次缓存（同一条消息内）；跨消息不命中。
        assertEquals(1, cachedHits(conversation.id))
    }

    @Test
    fun `P1 multistep validateOnly - cache hit rechecks permission without executing`() {
        val context = ToolContext(
            taskId = "validate-only",
            agentId = "brain",
            approvalGate = FakeApprovalGate(allow = true),
            workspaceRoot = workspaceRoot,
            deviceId = "local",
            folder = workspaceRoot.path,
        )
        val okAction = Action(
            actionId = "call-1",
            tool = "read_file",
            args = mapOf("path" to "README.md"),
            requiresApproval = false,
            idempotencyKey = "validate-ok",
        )
        assertEquals(null, tools.validateOnly(okAction, context))
        val badAction = Action(
            actionId = "call-2",
            tool = "read_file",
            args = mapOf("path" to "../outside-secret.txt"),
            requiresApproval = false,
            idempotencyKey = "validate-bad",
        )
        val denied = tools.validateOnly(badAction, context)
        assertTrue(denied != null && !denied.ok)
        assertEquals("path_not_allowed", denied!!.errorCode)
        // validateOnly 不执行：目标文件内容未出现在任何结果里。
        assertFalse(denied.summary.contains("OUTSIDE-MARKER"))
    }

    // ---------- resolveInWorkspace 绝对路径门禁（V5 Phase B C2，批 2；
    // GPT 8393f336 (b) 安全门禁：真实模型触发写文件前，绝对路径必须按
    // 绝对路径校验，越界拒绝；只用 validateOnly 断言，不执行、不落盘） ----------

    private fun validateContext(): ToolContext = ToolContext(
        taskId = "validate-abs",
        agentId = "brain",
        approvalGate = FakeApprovalGate(allow = true),
        workspaceRoot = workspaceRoot,
        deviceId = "local",
        folder = workspaceRoot.path,
    )

    private fun validateRead(path: String) = tools.validateOnly(
        Action(
            actionId = "call-abs",
            tool = "read_file",
            args = mapOf("path" to path),
            requiresApproval = false,
            idempotencyKey = "validate-abs-${path.hashCode()}",
        ),
        validateContext(),
    )

    @Test
    fun `absolute path outside the workspace is denied, not reinterpreted as relative`() {
        // 旧行为会把绝对路径按相对路径拼进工作区（读错文件）；现在必须拒绝。
        val outside = File(tmpDir, "outside-secret.txt").absolutePath
        val denied = validateRead(outside)
        assertTrue(denied != null && !denied.ok)
        assertEquals("path_not_allowed", denied!!.errorCode)
        assertFalse(denied.summary.contains("OUTSIDE-MARKER"))
    }

    @Test
    fun `absolute path inside the workspace is allowed`() {
        val inside = File(workspaceRoot, "README.md").absolutePath
        assertEquals(null, validateRead(inside))
    }

    @Test
    fun `absolute path with dot-dot escaping the workspace is denied`() {
        val escape = workspaceRoot.absolutePath + "/sub/../../outside-secret.txt"
        val denied = validateRead(escape)
        assertTrue(denied != null && !denied.ok)
        assertEquals("path_not_allowed", denied!!.errorCode)
        assertFalse(denied.summary.contains("OUTSIDE-MARKER"))
    }

    @Test
    fun `symlink inside the workspace pointing outside is denied`() {
        val link = File(workspaceRoot, "link-outside.txt")
        try {
            Files.createSymbolicLink(link.toPath(), File(tmpDir, "outside-secret.txt").toPath())
        } catch (_: Exception) {
            // 文件系统不支持符号链接时跳过（不断言失败，CI 环境支持即可）。
            return
        }
        try {
            val denied = validateRead("link-outside.txt")
            assertTrue(denied != null && !denied.ok)
            assertEquals("path_not_allowed", denied!!.errorCode)
            assertFalse(denied.summary.contains("OUTSIDE-MARKER"))
        } finally {
            link.delete()
        }
    }

    @Test
    fun `write_file with an absolute outside path is denied`() {
        val outside = File(tmpDir, "outside-secret.txt").absolutePath
        val denied = tools.validateOnly(
            Action(
                actionId = "call-abs-write",
                tool = "write_file",
                args = mapOf("path" to outside, "content" to "pwned"),
                requiresApproval = false,
                idempotencyKey = "validate-abs-write",
            ),
            validateContext(),
        )
        assertTrue(denied != null && !denied.ok)
        assertEquals("path_not_allowed", denied!!.errorCode)
        // 目标文件未被改动。
        assertEquals(
            "OUTSIDE-MARKER",
            File(tmpDir, "outside-secret.txt").readText(Charsets.UTF_8),
        )
    }

    // ---------- ask_user 重复调用修复（2026-11-08）：F1 / F2 / F3 ----------

    @Test
    fun `ask_user fix F1 - asking the user ends the turn with the question as text`() = runTest {
        var adapterCalls = 0
        val conversation = conversations.createConversation("fake-brain", "问用户")
        val engine = engine(
            FakeAdapter {
                adapterCalls += 1
                // 假模型复现 8B 行为：工具在场就调 ask_user，且会重复调；
                // 引擎必须在第一次成功后即终结，不给第二次机会。
                flowOf(
                    BrainEvent.ToolCall("call-1", "ask_user", mapOf("question" to "下一步要做什么？")),
                    BrainEvent.Done("stop"),
                )
            },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请列出文件并问我下一步做什么").toList()
        // 只调了一次模型：没有第二轮，没有重复调用。
        assertEquals(1, adapterCalls)
        assertEquals(1, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertTrue(events.filterIsInstance<BrainEvent.ToolResult>().single().ok)
        // 问题以文字交给用户（先于 Done，晚于 ToolResult）。
        val resultIdx = events.indexOfFirst { it is BrainEvent.ToolResult }
        val textIdx = events.indexOfFirst { it is BrainEvent.TextDelta }
        assertTrue(textIdx > resultIdx)
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("下一步要做什么？") })
        assertTrue(events.none { it is BrainEvent.Error })
        assertEquals(BrainEvent.Done("awaiting_user"), events.last())
        assertEquals(1, realExecutions(conversation.id))
        // 问题作为助手回复持久化：用户下一条消息即是回答。
        val stored = conversations.listMessages(conversation.id)
        assertTrue(stored.any { it.role == "assistant" && it.content.contains("下一步要做什么？") })
    }

    @Test
    fun `ask_user fix F2 - who-are-you question with 任务 answers in text without ask_user`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "你是谁")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                // 复现 8B：工具在场就调 ask_user，无工具才回文字。
                if (request.tools.isEmpty()) {
                    flowOf(BrainEvent.TextDelta("我是 WhereverCode 超级 Agent，帮你调度任务。"), BrainEvent.Done("stop"))
                } else {
                    flowOf(
                        BrainEvent.ToolCall("call-1", "ask_user", mapOf("question" to "你想让我帮你完成什么任务？")),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "你是谁？你能帮我完成什么任务？").toList()
        // K70 ③ 轮原样回归：首轮请求不带工具定义，模型只能回文字。
        assertTrue("首轮请求不应带工具定义", seenRequests.first().tools.isEmpty())
        assertTrue(events.none { it is BrainEvent.ToolCall })
        assertTrue(events.none { it is BrainEvent.Error })
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("超级 Agent") })
        assertTrue(events.last() is BrainEvent.Done)
    }

    @Test
    fun `ask_user fix F4-history - cross-round history with tool messages does not retrigger ask_user`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "列文件")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                val lastUser = request.messages.lastOrNull { it.role == "user" }?.content ?: ""
                when {
                    // 第一轮：列文件——工具在场调 list_files，回填后回文字。
                    lastUser.contains("列出工作区") && !request.messages.any { it.role == "tool" } ->
                        flowOf(BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")), BrainEvent.Done("stop"))
                    lastUser.contains("列出工作区") ->
                        flowOf(BrainEvent.TextDelta("工作区里有 README.md。"), BrainEvent.Done("stop"))
                    // 第二轮：自我介绍问句——工具在场就调 ask_user（回归陷阱），无工具才回文字。
                    request.tools.isEmpty() ->
                        flowOf(BrainEvent.TextDelta("我是 WhereverCode 超级 Agent。"), BrainEvent.Done("stop"))
                    else ->
                        flowOf(BrainEvent.ToolCall("call-2", "ask_user", mapOf("question" to "你想让我帮你完成什么任务？")), BrainEvent.Done("stop"))
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val first = engine.chat(conversation.id, "请列出工作区里的文件").toList()
        assertTrue(first.none { it is BrainEvent.Error })
        assertEquals(1, realExecutions(conversation.id))
        val beforeSecond = seenRequests.size
        val second = engine.chat(conversation.id, "你是谁？你能帮我完成什么任务？").toList()
        assertTrue(second.none { it is BrainEvent.ToolCall })
        assertTrue(second.none { it is BrainEvent.Error })
        assertTrue(second.any { it is BrainEvent.TextDelta && it.text.contains("超级 Agent") })
        // 第二轮首轮请求不带工具：上一轮的 tool 消息只在历史里，不改变分流。
        assertTrue(seenRequests[beforeSecond].tools.isEmpty())
        // 跨轮不重复执行：全程只真实执行一次 list_files。
        assertEquals(1, realExecutions(conversation.id))
    }

    @Test
    fun `ask_user fix F3 - repeated write call with existing text returns the text instead of erroring`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "写文件")
        val engine = engine(
            FakeAdapter {
                flowOf(
                    BrainEvent.TextDelta("我先回一句，再写文件。"),
                    BrainEvent.ToolCall("call-1", "write_file", mapOf("path" to "README.md", "content" to "new content")),
                    BrainEvent.Done("stop"),
                )
            },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请把 README 改成 new content").toList()
        // 护栏 1 触发时已有文字：返回文字正常结束，不报 tool_call_repeated。
        assertTrue(events.none { it is BrainEvent.Error })
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("我先回一句") })
        assertEquals(BrainEvent.Done("stop"), events.last())
        // 写类重复即停：同一调用只执行了一次。
        assertEquals(1, realExecutions(conversation.id))
        assertEquals("new content", File(workspaceRoot, "README.md").readText())
    }

    // ---------- K70 缺参工具调用修复（2026-11-09）：判定 §7 第 1–4 条 ----------
    // 以下均为 fake transport（测试替身）单测：覆盖缺参执行前校验、回填 errorCode、
    // 空目录收敛、缺参 ask_user 终结、同轮依赖调用不再盲目发出；不是真实模型调用，
    // 不冒充真机验证。

    @Test
    fun `missing params - read_file without path fails invalid_action and feedback carries errorCode`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "读文件缺参")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                if (request.messages.any { it.role == "tool" }) {
                    // 收到缺参反馈后，假模型改以文字回答，不再重试坏调用。
                    flowOf(BrainEvent.TextDelta("收到缺参错误，不再读文件。"), BrainEvent.Done("stop"))
                } else {
                    // 复现 K70 ③：read_file 缺必填 path。
                    flowOf(BrainEvent.ToolCall("call-1", "read_file", emptyMap()), BrainEvent.Done("stop"))
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请读一下文件").toList()
        // 执行前校验拦截：未执行 handler，明确缺参错误。
        val result = events.filterIsInstance<BrainEvent.ToolResult>().single()
        assertFalse(result.ok)
        assertEquals(1, realExecutions(conversation.id))
        assertTrue(events.none { it is BrainEvent.Error })
        // 回填 JSON 带 errorCode（§7 第 1 条），不只靠中文 summary。
        assertEquals(2, seenRequests.size)
        val toolJson = toolJsonOf(seenRequests, 1)
        assertEquals("invalid_action", toolJson?.optString("errorCode"))
        assertTrue(toolJson?.optString("summary")?.contains("缺少参数 path") == true)
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("不再读文件") })
        assertEquals(BrainEvent.Done("stop"), events.last())
    }

    @Test
    fun `missing params - ask_user without question ends the turn immediately without retry`() = runTest {
        var adapterCalls = 0
        val conversation = conversations.createConversation("fake-brain", "问用户缺参")
        val engine = engine(
            FakeAdapter {
                adapterCalls += 1
                // 复现 K70 ③：ask_user 缺必填 question；假模型若有第二轮会再问，
                // 引擎必须在第一次缺参后即终结，不给重试机会。
                flowOf(BrainEvent.ToolCall("call-1", "ask_user", emptyMap()), BrainEvent.Done("stop"))
            },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请列出文件并问我下一步做什么").toList()
        // 只调了一次模型：没有第二轮，没有重试。
        assertEquals(1, adapterCalls)
        assertEquals(1, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertFalse(events.filterIsInstance<BrainEvent.ToolResult>().single().ok)
        assertEquals(1, realExecutions(conversation.id))
        assertTrue(events.none { it is BrainEvent.Error })
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("提问失败：缺少问题内容") })
        assertEquals(BrainEvent.Done("ask_user_invalid"), events.last())
    }

    @Test
    fun `missing params - same-round list plus missing-path read on empty workspace does not re-offer read_file`() = runTest {
        // 空目录：删掉 setUp 建的 README.md，使工作区根真正为空。
        assertTrue(File(workspaceRoot, "README.md").delete())
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "空目录同轮")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                if (request.messages.any { it.role == "tool" }) {
                    // 回填后假模型收敛为文字回答，不再调 read_file。
                    flowOf(BrainEvent.TextDelta("工作区是空的，没有文件。"), BrainEvent.Done("stop"))
                } else {
                    // 复现 K70 ③ 同轮三连发（此处为 list + 缺参 read 两连）：
                    // 模型在没看到 list 结果前就并行发出了依赖调用。
                    flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.ToolCall("call-2", "read_file", emptyMap()),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请检查当前工作区：先列出所有文件，再读取其中一个文本文件的内容").toList()
        // 首轮带 read_file（过滤是状态驱动的，空目录确认前不过滤）。
        assertTrue(seenRequests.first().tools.any { it.name == "read_file" })
        // list_files 确认空目录后，下一轮请求不再提供 read_file（§7 第 2 条）。
        assertEquals(2, seenRequests.size)
        assertTrue(seenRequests[1].tools.none { it.name == "read_file" })
        assertTrue(seenRequests[1].tools.any { it.name == "list_files" })
        // 缺参 read_file 只执行了一次（执行前校验拦截），不再重发。
        assertEquals(1, events.filterIsInstance<BrainEvent.ToolCall>().count { it.name == "read_file" })
        assertEquals(2, realExecutions(conversation.id))
        assertTrue(events.none { it is BrainEvent.Error })
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("工作区是空的") })
        assertEquals(BrainEvent.Done("stop"), events.last())
    }

    @Test
    fun `missing params - tool schemas carry per-parameter descriptions and read dependency`() {
        val readFile = ToolRegistry.toolDef("read_file")
        @Suppress("UNCHECKED_CAST")
        val readProps = readFile.inputSchema["properties"] as Map<String, Map<String, Any?>>
        val pathDesc = readProps.getValue("path")["description"] as String
        assertTrue(pathDesc.contains("list_files"))
        assertTrue(pathDesc.contains("不得与 list_files 同轮发出"))
        val askUser = ToolRegistry.toolDef("ask_user")
        @Suppress("UNCHECKED_CAST")
        val askProps = askUser.inputSchema["properties"] as Map<String, Map<String, Any?>>
        val questionDesc = askProps.getValue("question")["description"] as String
        assertTrue(questionDesc.contains("完整问题"))
        // SYSTEM_PROMPT 同样写明调用依赖（§7 第 4 条）。
        assertTrue(BrainEngine.SYSTEM_PROMPT.contains("不得与 list_files 同一轮发出"))
        assertTrue(BrainEngine.SYSTEM_PROMPT.contains("工作区已确认为空时直接用文字回答"))
    }

    // ---- tool_no_progress 修复（2026-11-09）回归：以下均为 fake transport
    // 测试替身，不是真实模型调用；真实工作区链路仍待 K70 真机验收。----

    @Test
    fun `no-progress fix - real workspace multi-step list read final contains marker`() = runTest {
        // 真实文件工作区：K70 地面真相的测试替身版（README.txt，标记“蓝莓派 42 号”）。
        File(workspaceRoot, "README.txt").writeText("蓝莓派 42 号", Charsets.UTF_8)
        val conversation = conversations.createConversation("fake-brain", "多步读文件")
        val engine = engine(
            FakeAdapter({ request ->
                val toolCount = request.messages.count { it.role == "tool" }
                when (toolCount) {
                    0 -> flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                    1 -> flowOf(
                        BrainEvent.ToolCall("call-2", "read_file", mapOf("path" to "README.txt")),
                        BrainEvent.Done("stop"),
                    )
                    else -> flowOf(
                        BrainEvent.TextDelta("文件内容是：蓝莓派 42 号"),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(
            conversation.id,
            "请自主完成以下任务：先调用工具列出当前工作区文件，再读取 README.txt，最后告诉我文件的完整内容。不要猜测内容，不要让我逐步下指令。",
        ).toList()
        assertTrue(events.none { it is BrainEvent.Error })
        assertEquals(2, realExecutions(conversation.id))
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("蓝莓派 42 号") })
        assertEquals(BrainEvent.Done("stop"), events.last())
    }

    @Test
    fun `missing params - ambiguous listing is not guessed and availableFiles drives the retry`() = runTest {
        File(workspaceRoot, "NOTES.md").writeText("notes-content", Charsets.UTF_8)
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "同轮歧义清单")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                val toolCount = request.messages.count { it.role == "tool" }
                when (toolCount) {
                    0 -> flowOf(
                        BrainEvent.ToolCall("call-list", "list_files", mapOf("path" to "")),
                        BrainEvent.ToolCall("call-read", "read_file", emptyMap()),
                        BrainEvent.Done("stop"),
                    )
                    2 -> flowOf(
                        BrainEvent.ToolCall("call-read2", "read_file", mapOf("path" to "README.md")),
                        BrainEvent.Done("stop"),
                    )
                    else -> flowOf(BrainEvent.TextDelta("hello from workspace"), BrainEvent.Done("stop"))
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "列出文件，然后读取其中一个文件").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        // Two files and no filename in the request: the engine must NOT pick one.
        assertTrue(eventLog.entries(conversation.id).none { it.kind == "tool_arg_repaired" })
        val failedRead = JSONObject(seenRequests[1].messages.last { it.role == "tool" }.content)
        assertEquals("invalid_action", failedRead.getString("errorCode"))
        assertTrue(failedRead.getString("availableFiles").contains("README.md"))
        assertTrue(failedRead.getString("availableFiles").contains("NOTES.md"))
        // The model then chooses README.md explicitly and succeeds.
        assertEquals(3, realExecutions(conversation.id))
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("hello from workspace") })
    }

    @Test
    fun `no-progress fix - same-round list plus missing-path read is repaired before execution`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "同轮并行读")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                if (request.messages.any { it.role == "tool" }) {
                    flowOf(BrainEvent.TextDelta("读到了：hello from workspace"), BrainEvent.Done("stop"))
                } else {
                    // K70 chain A R0: list_files and a missing-path read_file
                    // in the SAME assistant round.
                    flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.ToolCall("call-2", "read_file", emptyMap()),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请列出文件，再读取 README.md").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        // 2026-11-09: the missing path is repaired from the listing produced
        // by the preceding call in the same round, so the read succeeds
        // instead of becoming invalid_action + availableFiles + a retry.
        assertEquals(2, realExecutions(conversation.id))
        assertEquals(2, seenRequests.size)
        assertTrue(eventLog.entries(conversation.id).any { it.kind == "tool_arg_repaired" })
        val readJson = JSONObject(
            seenRequests[1].messages.last { it.role == "tool" }.content,
        )
        assertTrue(readJson.getBoolean("ok"))
        assertEquals("README.md", readJson.getString("path"))
        assertTrue(readJson.getString("data").contains("hello from workspace"))
        assertTrue(!readJson.has("errorCode"))
        assertTrue(!readJson.has("availableFiles"))
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("hello from workspace") })
        assertEquals(BrainEvent.Done("stop"), events.last())
    }

    @Test
    fun `no-progress fix - absolute-path read repeated on the same target stops with tool_repeated_failure`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "绝对路径重试")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                val toolCount = request.messages.count { it.role == "tool" }
                when (toolCount) {
                    0 -> flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                    1 -> flowOf(
                        BrainEvent.ToolCall(
                            "call-2", "read_file",
                            // 绝对路径写在工作区内（旧夹具写的是测试工作区外的
                            // 绝对路径，旧解析器会悄悄把它当相对路径拼进工作区；
                            // GPT 8393f336 (b) 门禁修掉该行为后，区外绝对路径
                            // 直接 path_not_allowed，不再与第 3 轮落到同一目标）。
                            // 两种写法都取工作区内绝对路径：归一化
                            // （normalizeWorkspacePath）只去 "." 与空段，不去
                            // 临时目录前缀，所以两种写法必须同为区内绝对路径，
                            // 归一化后才是同一目标 key，护栏 3 才能按原意触发。
                            mapOf(
                                "path" to workspaceRoot.absolutePath +
                                    "/data/data/wherevercode.agent/files/workspace/README.md",
                            ),
                        ),
                        BrainEvent.Done("stop"),
                    )
                    else -> flowOf(
                        // 第二次换一种写法重试同一目标（"/./" 中缀）：归一化后仍是同一目标。
                        BrainEvent.ToolCall(
                            "call-3", "read_file",
                            mapOf(
                                "path" to workspaceRoot.absolutePath +
                                    "/data/data/wherevercode.agent/files/workspace/./README.md",
                            ),
                        ),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请列出文件，再读取 README.md").toList()
        // 有限轮内收敛：第二次同一目标 hand_not_found 即停，不无限换路径形重试。
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_repeated_failure")
        assertEquals(3, realExecutions(conversation.id))
        assertEquals(3, seenRequests.size)
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_round_limit" })
        assertTrue(events.none { it is BrainEvent.Error && it.code == "tool_no_progress" })
    }

    @Test
    fun `no-progress fix - different missing files do not trip the repeated failure guard`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "读两个不存在的文件")
        val engine = engine(
            FakeAdapter({ request ->
                val toolCount = request.messages.count { it.role == "tool" }
                when (toolCount) {
                    0 -> flowOf(
                        BrainEvent.ToolCall("call-1", "read_file", mapOf("path" to "missing-a.md")),
                        BrainEvent.Done("stop"),
                    )
                    1 -> flowOf(
                        BrainEvent.ToolCall("call-2", "read_file", mapOf("path" to "missing-b.md")),
                        BrainEvent.Done("stop"),
                    )
                    else -> flowOf(
                        BrainEvent.TextDelta("两个文件都不存在，我没法读。"),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "读一下 missing-a，再读 missing-b").toList()
        // 不同目标各给一次重试机会：两次 hand_not_found 都只回填，不触发护栏 3。
        assertTrue(events.none { it is BrainEvent.Error })
        assertEquals(2, realExecutions(conversation.id))
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("都不存在") })
    }

    @Test
    fun `no-progress fix - tool_no_progress error carries the recent failure summary`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "轮换读不存在的文件")
        val engine = engine(
            FakeAdapter({ request ->
                val toolCount = request.messages.count { it.role == "tool" }
                // 每轮换一个不同不存在的文件：护栏 1（同一目标重复）不触发，
                // 护栏 2 在连续两轮无进展后收敛；终结信息须带最近失败摘要。
                val target = "missing-${('a' + toolCount)}.md"
                flowOf(
                    BrainEvent.ToolCall("call-${toolCount + 1}", "read_file", mapOf("path" to target)),
                    BrainEvent.Done("stop"),
                )
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "读几个不存在的文件").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().firstOrNull()
        assertTrue(error != null && error.code == "tool_no_progress")
        // 终结文案保留旧句前缀，并附带最近失败摘要（工具名+errorCode）。
        assertTrue(error!!.message.startsWith("大脑连续调用工具却没有给出文字回复，本轮停止。"))
        assertTrue(error.message.contains("read_file(hand_not_found)"))
        // 护栏 2 算术不动：失败不计进展，3 次执行、4 次模型调用后在轮头触发。
        assertEquals(3, events.filterIsInstance<BrainEvent.ToolResult>().size)
        assertEquals(3, realExecutions(conversation.id))
        assertEquals(0, cachedHits(conversation.id))
    }

    @Test
    fun `no-progress fix - empty workspace converges to a final text answer`() = runTest {
        // 空目录收敛回归（a5205bcd 的 workspaceKnownEmpty 已到位，本案只补覆盖）：
        // 删掉 setUp 建的 README.md，使工作区根真正为空。
        assertTrue(File(workspaceRoot, "README.md").delete())
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "空目录收敛")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                if (request.messages.any { it.role == "tool" }) {
                    flowOf(BrainEvent.TextDelta("工作区是空的，没有文件。"), BrainEvent.Done("stop"))
                } else {
                    flowOf(
                        BrainEvent.ToolCall("call-1", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "请列出当前工作区的文件，并告诉我工作区是否为空").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        // list_files 确认空目录后，下一轮请求不再提供 read_file，直接以文字收尾。
        assertEquals(2, seenRequests.size)
        assertTrue(seenRequests[1].tools.none { it.name == "read_file" })
        assertEquals(1, realExecutions(conversation.id))
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("工作区是空的") })
        assertEquals(BrainEvent.Done("stop"), events.last())
    }

    // ------------------------------------------------------------------
    // 2026-11-09 K70 root-cause regression: native tool pairing + missing
    // read_file path repair (no model call; fake adapter only).
    // ------------------------------------------------------------------

    @Test
    fun `K70 2026-11-09 - list read report completes and pairs tool_call_id`() = runTest {
        File(workspaceRoot, "README.txt").writeText("蓝莓派 42 号", Charsets.UTF_8)
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "读 README.txt")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                val toolCount = request.messages.count { it.role == "tool" }
                when (toolCount) {
                    0 -> flowOf(
                        BrainEvent.ToolCall("call-list", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                    1 -> flowOf(
                        // The exact K70 bad shape: read_file with no path.
                        BrainEvent.ToolCall("call-read", "read_file", emptyMap()),
                        BrainEvent.Done("stop"),
                    )
                    else -> flowOf(
                        BrainEvent.TextDelta("README.txt 的内容是：蓝莓派 42 号"),
                        BrainEvent.Done("stop"),
                    )
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(
            conversation.id,
            "列出工作区文件，再读取 README.txt，最后告诉我完整内容",
        ).toList()

        assertTrue("不应出现 invalid_action/tool_repeated/tool_no_progress：$events",
            events.none { it is BrainEvent.Error })
        val results = events.filterIsInstance<BrainEvent.ToolResult>()
        assertEquals(2, results.size)
        assertTrue(results.all { it.ok })
        assertEquals(2, realExecutions(conversation.id))
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("蓝莓派 42 号") })
        assertTrue(eventLog.entries(conversation.id).any { it.kind == "tool_arg_repaired" })

        // Round 2 request: the list call and its result are paired by id.
        val afterList = seenRequests[1].messages
        val listEcho = afterList.last { it.role == "assistant" }
        assertEquals("call-list", listEcho.toolCalls.single().id)
        assertEquals("list_files", listEcho.toolCalls.single().name)
        val listResult = afterList.last { it.role == "tool" }
        assertEquals("call-list", listResult.toolCallId)

        // Round 3 request: the repaired read call is paired too, and the
        // model receives the actual file content in the tool result.
        val afterRead = seenRequests[2].messages
        val readEcho = afterRead.last { it.role == "assistant" }
        assertEquals("call-read", readEcho.toolCalls.single().id)
        assertEquals(
            "README.txt",
            JSONObject(readEcho.toolCalls.single().argumentsJson).getString("path"),
        )
        val readResult = afterRead.last { it.role == "tool" }
        assertEquals("call-read", readResult.toolCallId)
        assertTrue(readResult.content.contains("蓝莓派 42 号"))

        // Both pairings are persisted for the next user turn.
        val stored = conversations.listMessages(conversation.id)
        assertTrue(stored.any { it.role == "assistant" && it.toolCalls.any { c -> c.id == "call-read" } })
        assertTrue(stored.any { it.role == "tool" && it.toolCallId == "call-read" })
    }

    @Test
    fun `K70 2026-11-09 - missing path repair picks the file the user named`() = runTest {
        File(workspaceRoot, "README.txt").writeText("蓝莓派 42 号", Charsets.UTF_8)
        File(workspaceRoot, "NOTES.md").writeText("notes", Charsets.UTF_8)
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "读 README.txt")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                val toolCount = request.messages.count { it.role == "tool" }
                when (toolCount) {
                    0 -> flowOf(
                        BrainEvent.ToolCall("call-list", "list_files", mapOf("path" to "")),
                        BrainEvent.Done("stop"),
                    )
                    1 -> flowOf(
                        BrainEvent.ToolCall("call-read", "read_file", emptyMap()),
                        BrainEvent.Done("stop"),
                    )
                    else -> flowOf(BrainEvent.TextDelta("蓝莓派 42 号"), BrainEvent.Done("stop"))
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "读取 README.txt 的完整内容").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        val readResult = events.filterIsInstance<BrainEvent.ToolResult>().last()
        assertTrue(readResult.ok)
        val readEcho = seenRequests[2].messages.last { it.role == "assistant" }
        assertEquals(
            "README.txt",
            JSONObject(readEcho.toolCalls.single().argumentsJson).getString("path"),
        )
        assertEquals(0, cachedHits(conversation.id))
    }

    @Test
    fun `K70 2026-11-09 - multiple tool calls in one round are one assistant message`() = runTest {
        File(workspaceRoot, "NOTES.md").writeText("notes-content", Charsets.UTF_8)
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "读两份文件")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                val toolCount = request.messages.count { it.role == "tool" }
                if (toolCount == 0) {
                    flowOf(
                        BrainEvent.ToolCall("call-a", "read_file", mapOf("path" to "README.md")),
                        BrainEvent.ToolCall("call-b", "read_file", mapOf("path" to "NOTES.md")),
                        BrainEvent.Done("stop"),
                    )
                } else {
                    flowOf(BrainEvent.TextDelta("两份都读完了。"), BrainEvent.Done("stop"))
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "读 README.md 和 NOTES.md").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        assertEquals(2, events.filterIsInstance<BrainEvent.ToolResult>().size)

        val second = seenRequests[1].messages
        val assistantWithCalls = second.filter { it.role == "assistant" }
        assertEquals(1, assistantWithCalls.size)
        assertEquals(listOf("call-a", "call-b"), assistantWithCalls[0].toolCalls.map { it.id })
        val toolResults = second.filter { it.role == "tool" }
        assertEquals(2, toolResults.size)
        assertEquals(listOf("call-a", "call-b"), toolResults.map { it.toolCallId })
        assertTrue(toolResults[0].content.contains("hello from workspace"))
        assertTrue(toolResults[1].content.contains("notes-content"))
    }

    @Test
    fun `K70 2026-11-09 - same-round list_files then missing-path read is repaired`() = runTest {
        File(workspaceRoot, "README.txt").writeText("蓝莓派 42 号", Charsets.UTF_8)
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "同轮列并读")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                val toolCount = request.messages.count { it.role == "tool" }
                if (toolCount == 0) {
                    // Both calls in the SAME assistant round: list_files is
                    // executed first, then read_file has no path.
                    flowOf(
                        BrainEvent.ToolCall("call-list", "list_files", mapOf("path" to "")),
                        BrainEvent.ToolCall("call-read", "read_file", emptyMap()),
                        BrainEvent.Done("stop"),
                    )
                } else {
                    flowOf(BrainEvent.TextDelta("蓝莓派 42 号"), BrainEvent.Done("stop"))
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "列出文件并读取 README.txt").toList()
        assertTrue(events.none { it is BrainEvent.Error })
        val results = events.filterIsInstance<BrainEvent.ToolResult>()
        assertEquals(2, results.size)
        assertTrue(results.all { it.ok })
        val readJson = JSONObject(seenRequests[1].messages.last { it.role == "tool" }.content)
        assertEquals("README.txt", readJson.getString("path"))
        assertTrue(readJson.getString("data").contains("蓝莓派 42 号"))
        assertTrue(eventLog.entries(conversation.id).any { it.kind == "tool_arg_repaired" })
    }

    @Test
    fun `2026-11-09 - empty adapter flow reports empty_response instead of a blank turn`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "空回复")
        val engine = engine(
            FakeAdapter { flowOf() },
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "你好").toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("empty_response", error.code)
        assertTrue(error.retryable)
        assertEquals(BrainEvent.Done("error"), events.last())
    }

    @Test
    fun `2026-11-09 - cancellation propagates and is never converted to an error or blank turn`() = runTest {
        val conversation = conversations.createConversation("fake-brain", "取消")
        val engine = engine(
            FakeAdapter { throw CancellationException("user cancelled") },
            FakeApprovalGate(allow = true),
        )
        var cancelled = false
        try {
            engine.chat(conversation.id, "读文件").toList()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("CancellationException 必须透传给 UI，而不是伪造完成/错误", cancelled)
    }

    @Test
    fun `K70 2026-11-09 - missing path without a listing is still invalid_action`() = runTest {
        val seenRequests = mutableListOf<ChatRequest>()
        val conversation = conversations.createConversation("fake-brain", "直接读")
        val engine = engine(
            FakeAdapter({ request ->
                seenRequests += request
                val toolCount = request.messages.count { it.role == "tool" }
                if (toolCount == 0) {
                    flowOf(
                        BrainEvent.ToolCall("call-read", "read_file", emptyMap()),
                        BrainEvent.Done("stop"),
                    )
                } else {
                    flowOf(BrainEvent.TextDelta("缺少路径，我无法读取。"), BrainEvent.Done("stop"))
                }
            }),
            FakeApprovalGate(allow = true),
        )
        val events = engine.chat(conversation.id, "读一下文件").toList()
        val result = events.filterIsInstance<BrainEvent.ToolResult>().single()
        assertFalse(result.ok)
        assertEquals(1, realExecutions(conversation.id))
        assertTrue(eventLog.entries(conversation.id).none { it.kind == "tool_arg_repaired" })
        val toolJson = JSONObject(seenRequests[1].messages.last { it.role == "tool" }.content)
        assertEquals("invalid_action", toolJson.getString("errorCode"))
        assertFalse(toolJson.has("availableFiles"))
        assertTrue(events.any { it is BrainEvent.TextDelta && it.text.contains("缺少路径") })
    }

    /** 2026-11-09：write_file 一轮结束后必须释放写轮次；下一个会话仍能写。 */
    private fun writeFileScript(path: String, content: String): (ChatRequest) -> Flow<BrainEvent> = { request ->
        if (request.messages.any { it.role == "tool" }) {
            flowOf(BrainEvent.TextDelta("已写入"), BrainEvent.Done("stop"))
        } else {
            flowOf(
                BrainEvent.ToolCall("call-write", "write_file", mapOf("path" to path, "content" to content)),
                BrainEvent.Done("stop"),
            )
        }
    }

    @Test
    fun `consecutive turns in different conversations can both write files (no leaked round)`() = runTest {
        val first = conversations.createConversation("fake-brain", "第一轮")
        val second = conversations.createConversation("fake-brain", "第二轮")
        val gate = FakeApprovalGate(allow = true)

        val firstEvents = engine(FakeAdapter(writeFileScript("agent-test/one.txt", "ONE")), gate)
            .chat(first.id, "写 one.txt").toList()
        assertTrue(firstEvents.filterIsInstance<BrainEvent.ToolResult>().single().ok)
        assertEquals("ONE", File(workspaceRoot, "agent-test/one.txt").readText())

        // 旧实现里第一轮 beginRound 后永不 finish，第二轮（新会话 = 新 taskId）
        // 会收到 workspace_round_active；本测试锁死该回归。
        val secondEvents = engine(FakeAdapter(writeFileScript("agent-test/two.txt", "TWO")), gate)
            .chat(second.id, "写 two.txt").toList()
        val secondResult = secondEvents.filterIsInstance<BrainEvent.ToolResult>().single()
        assertTrue("第二轮写入失败：${secondResult.summary}", secondResult.ok)
        assertEquals("TWO", File(workspaceRoot, "agent-test/two.txt").readText())
        assertTrue(
            eventLog.entries(second.id).none {
                it.kind == "tool_result" && it.detail.contains("workspace_round_active")
            },
        )
    }

    @Test
    fun `run_command in a later turn still runs after an earlier write turn`() = runTest {
        val first = conversations.createConversation("fake-brain", "先写")
        val second = conversations.createConversation("fake-brain", "再跑")
        val gate = FakeApprovalGate(allow = true)
        engine(FakeAdapter(writeFileScript("agent-test/three.txt", "THREE")), gate)
            .chat(first.id, "写 three.txt").toList()

        val runScript: (ChatRequest) -> Flow<BrainEvent> = { request ->
            if (request.messages.any { it.role == "tool" }) {
                flowOf(BrainEvent.TextDelta("跑完了"), BrainEvent.Done("stop"))
            } else {
                flowOf(
                    BrainEvent.ToolCall("call-run", "run_command", mapOf("argv" to listOf("sleep", "0"))),
                    BrainEvent.Done("stop"),
                )
            }
        }
        val events = engine(FakeAdapter(runScript), gate).chat(second.id, "跑 sleep 0").toList()
        val result = events.filterIsInstance<BrainEvent.ToolResult>().single()
        assertTrue("第二轮命令失败：${result.summary}", result.ok)
        assertEquals("命令退出码 0", result.summary)
    }

}
