package wherevercode.agent.ui

import android.content.Context
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.AndroidApprovalGate
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.brain.BrainAdapter
import wherevercode.agent.brain.BrainAdapterRegistry
import wherevercode.agent.brain.BrainCapabilities
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainEngine
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ContextManager
import wherevercode.agent.brain.ConversationStore
import wherevercode.agent.brain.FailoverPolicy
import wherevercode.agent.brain.FakeTaskStore
import wherevercode.agent.brain.InMemoryConversationStore
import wherevercode.agent.brain.InMemoryEventLog
import wherevercode.agent.brain.InMemoryMemoryStore
import wherevercode.agent.brain.InMemorySkillStore
import wherevercode.agent.brain.ModelInfo
import wherevercode.agent.brain.SharedPreferencesBrainConfigStore
import wherevercode.agent.brain.TurnController
import wherevercode.agent.brain.TurnControlState
import wherevercode.agent.brain.claude.ClaudeBrainAdapter
import wherevercode.agent.brain.claude.KeystoreClaudeCredentialStore
import wherevercode.agent.brain.cloudflare.CloudflareBrainAdapter
import wherevercode.agent.brain.cloudflare.CloudflareM2BrainAdapter
import wherevercode.agent.brain.cloudflare.KeystoreCloudflareCredentialStore
import wherevercode.agent.brain.deepseek.DeepSeekBrainAdapter
import wherevercode.agent.brain.deepseek.KeystoreDeepSeekCredentialStore
import wherevercode.agent.brain.grok.GrokBrainAdapter
import wherevercode.agent.brain.grok.KeystoreGrokCredentialStore
import wherevercode.agent.brain.generic.GenericOpenAiBrainAdapter
import wherevercode.agent.brain.generic.KeystoreGenericOpenAiCredentialStore
import wherevercode.agent.brain.muse.KeystoreMuseCredentialStore
import wherevercode.agent.brain.muse.MuseBrainAdapter
import wherevercode.agent.brain.siwc.BrainBootstrap
import wherevercode.agent.brain.siwc.SiwcAuthManager
import wherevercode.agent.brain.siwc.SiwcBrainAdapter
import wherevercode.agent.brain.siwc.SiwcSecretStore
import wherevercode.agent.brain.siwc.SiwcTokenStore
import wherevercode.agent.brain.siwc.SiwcTokens
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.agent.ManusChatEvent
import wherevercode.agent.core.agent.ManusChatPort
import wherevercode.agent.core.agent.ManusTaskAttachment
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.python.PythonProbe
import wherevercode.agent.core.python.PythonRunRequest
import wherevercode.agent.core.python.PythonRunResult
import wherevercode.agent.core.python.PythonRuntime
import wherevercode.agent.core.python.PreferEmbeddedPythonRuntime
import wherevercode.agent.core.security.AuditLog
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.security.InMemoryPairingTrustStore
import wherevercode.agent.core.session.NoOpSessionLedger
import wherevercode.agent.core.task.LocalTaskRunner
import wherevercode.agent.core.task.TaskExecutor
import wherevercode.agent.core.task.TaskExecutionResult
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStepEvent
import wherevercode.agent.core.task.TaskStepStatus
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.workspace.FileWorkspaceAdapter
import wherevercode.agent.brain.ToolRegistry

/**
 * V5 修复（2026-11-11）：发给 Manus 的任务型指令。
 *
 * 回归：K70 真机（合并包 fix/v5-five-fixes-20261111）上 Manus 聊天通了，
 * 但任务型指令只剩文字回复——UseManus 分支永远走 startManusTurn，
 * ChatTaskDispatcher 只在 UseRemoteAgent 分支被查；Manus 是云端 Agent
 * 没有设备，远端泵永远不认领；产物 attachments 也被解析丢掉。
 *
 * 全部用假 Manus 端口，不发真实请求、零计费调用。真机验证（K70 上一次
 * 真实 Manus 任务，走免费积分）仍标未验证，等 maintainer 验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatViewModelManusTaskTest {

    private class ScriptedAdapter(
        override val adapterId: String,
        private val script: (ChatRequest) -> Flow<BrainEvent>,
    ) : BrainAdapter {
        val seenRequests = mutableListOf<ChatRequest>()
        override val kind: BrainKind = BrainKind.MODEL_API
        override val label: String = "Scripted（测试替身）"
        override fun capabilities(): BrainCapabilities =
            BrainCapabilities(streaming = true, toolCalling = false)
        override suspend fun validateCredentials(): Result<Unit> = Result.success(Unit)
        override suspend fun listModels(): Result<List<ModelInfo>> =
            Result.success(listOf(ModelInfo("fake-model", "Fake")))
        override fun chatStream(request: ChatRequest): Flow<BrainEvent> {
            seenRequests += request
            return script(request)
        }
    }

    private class MemorySecrets : SiwcSecretStore {
        val map = HashMap<String, String>()
        override fun put(ref: String, value: String) { map[ref] = value }
        override fun get(ref: String): String? = map[ref]
        override fun delete(ref: String) { map.remove(ref) }
    }

    private class MemoryTokens(private val tokens: SiwcTokens?) : SiwcTokenStore {
        override fun saveTokens(tokens: SiwcTokens) = Unit
        override fun loadTokens(): SiwcTokens? = tokens
        override fun clearTokens() = Unit
    }

    private class NoopExecutor : TaskExecutor {
        override suspend fun execute(
            task: TaskRecord,
            onStep: (TaskStepEvent) -> Unit,
        ): TaskExecutionResult = TaskExecutionResult(false, "not used in chat tests")
    }

    private class UnavailablePython : PythonRuntime {
        override fun probe(): PythonProbe = PythonProbe(false, "测试替身：不可用")
        override fun run(request: PythonRunRequest): PythonRunResult =
            PythonRunResult(false, "测试替身", 1, "", "测试替身：不可用")
    }

    /** 测试替身：按脚本回放 Manus 聊天事件，记下收到的调用。 */
    private class ScriptedManusPort(
        private val events: List<ManusChatEvent>,
    ) : ManusChatPort {
        data class Call(val conversationId: String, val agentId: String, val prompt: String)
        val calls = mutableListOf<Call>()
        val stopped = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        override fun runChat(
            conversationId: String,
            agent: AgentRecord,
            prompt: String,
        ): Flow<ManusChatEvent> {
            calls += Call(conversationId, agent.agentId, prompt)
            return flowOf(*events.toTypedArray())
        }
        override fun stopTask(conversationId: String, agent: AgentRecord): Boolean {
            stopped += conversationId
            return true
        }
        override fun forgetConversation(conversationId: String) {
            forgotten += conversationId
        }
    }

    private lateinit var tmpDir: File
    private lateinit var configStore: SharedPreferencesBrainConfigStore
    private lateinit var secrets: MemorySecrets
    private lateinit var siwcAdapter: ScriptedAdapter
    private lateinit var viewModel: ChatViewModel
    private lateinit var conversations: ConversationStore
    private lateinit var taskStore: FakeTaskStore
    private lateinit var taskStepStore: InMemoryTaskStepStore
    private lateinit var manusPort: ScriptedManusPort
    private var manusConfigured: Boolean = true

    private fun manusAgent() = AgentRecord(
        agentId = "manus",
        displayName = "Manus",
        kind = AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = BrainKind.MODEL_API,
        adapterId = "manus",
        baseUrl = "https://api.manus.ai",
        model = "standard",
        authRef = "manus:api-key",
    )

    private fun answer(text: String): (ChatRequest) -> Flow<BrainEvent> = {
        flowOf(BrainEvent.TextDelta(text), BrainEvent.Done("stop"))
    }

    private fun reportFile() = ManusTaskAttachment(
        filename = "report.md",
        type = "file",
        url = "https://manus.im/dl/abc",
        path = "/home/user/report.md",
        contentType = "text/markdown",
        fileUid = "f_1",
        versionUid = "v_1",
    )

    /** 任务型 Manus 执行的完整流式脚本：进度 → 快照 → 工具 → 新快照 → 附件 → 终态。 */
    private fun taskEvents(): List<ManusChatEvent> = listOf(
        ManusChatEvent.Progress("Manus 任务已创建，正在执行…"),
        ManusChatEvent.Progress("正在写报告"),
        ManusChatEvent.TextSnapshot("m2", "我先看一下需求。"),
        ManusChatEvent.ToolUsed("m3", "shell", "已运行 cat", "读取文件", true),
        ManusChatEvent.TextSnapshot("m4", "报告已完成。"),
        ManusChatEvent.Attachments(listOf(reportFile())),
        ManusChatEvent.Result("报告已完成。", listOf(reportFile())),
    )

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(
            "wherevercode_brain_configs",
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        tmpDir = Files.createTempDirectory("cvm-manus-task-test").toFile()
        secrets = MemorySecrets().also {
            it.put("deepseek:api-key", "test-key-not-real")
        }
        configStore = SharedPreferencesBrainConfigStore(context)
        configStore.saveConfig(
            BrainConfig(
                brainId = "siwc-chatgpt",
                kind = BrainKind.SUBSCRIPTION,
                adapterId = "siwc-chatgpt",
                label = "ChatGPT (Sign in with ChatGPT)",
                baseUrl = null,
                model = "gpt-6.1-sol",
                authRef = "siwc:tokens",
            ),
        )
        configStore.setCurrentBrainId("siwc-chatgpt")

        BrainAdapterRegistry.clearForTests()
        BrainAdapterRegistry.registerConfig(configStore.getConfig("siwc-chatgpt")!!)
        siwcAdapter = ScriptedAdapter("siwc-chatgpt", answer("SIWC-ANSWER"))
        BrainAdapterRegistry.registerAdapter(siwcAdapter)

        manusPort = ScriptedManusPort(taskEvents())
        viewModel = ChatViewModel()
        viewModel.attach(buildComponents(), AndroidApprovalGate.get(context))
        viewModel.attachManusChat(
            port = manusPort,
            isManusConfigured = { manusConfigured },
        )
    }

    @After
    fun tearDown() {
        AndroidApprovalGate.resetForTests()
        BrainAdapterRegistry.clearForTests()
        tmpDir.deleteRecursively()
    }

    private fun buildComponents(): BrainBootstrap.BrainComponents {
        val context = RuntimeEnvironment.getApplication()
        val workspaceRoot = File(tmpDir, "workspace").apply { mkdirs() }
        val taskRoot = File(tmpDir, "task-root").apply { mkdirs() }
        val workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
        val deviceAdapter = JvmDeviceAdapter(listOf(workspaceRoot), workspaceAdapter)
        taskStore = FakeTaskStore()
        val auditLog: AuditLog = InMemoryAuditLog()
        val eventLog = InMemoryEventLog()
        val tools = ToolRegistry(deviceAdapter, workspaceAdapter, CommandLimits(), taskStore, auditLog)
        conversations = InMemoryConversationStore()
        val tokens = SiwcTokens(
            accessToken = "test-access-not-real",
            refreshToken = "test-refresh-not-real",
            idToken = null,
            clientId = "test-client",
            hostId = "urn:uuid:00000000-0000-0000-0000-000000000000",
            accessTokenExpiresAtMillis = System.currentTimeMillis() + 3600_000,
        )
        val tokenStore = MemoryTokens(tokens)
        val authManager = SiwcAuthManager(
            tokenStore = tokenStore,
            hostIdStore = object : wherevercode.agent.brain.siwc.HostIdStore {
                override fun getOrCreateHostId(): String =
                    "urn:uuid:00000000-0000-0000-0000-000000000000"
            },
            secrets = secrets,
        )
        val engine = BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversations,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            tools = tools,
            eventLog = eventLog,
            approvalGate = AndroidApprovalGate.get(context),
            currentBrainId = { configStore.currentBrainId() },
            failoverPolicy = FailoverPolicy(fallbackBrainIds = listOf("siwc-chatgpt")),
            controlState = { TurnControlState.RUNNING },
            sessionLedger = NoOpSessionLedger,
        )
        taskStepStore = InMemoryTaskStepStore()
        val scheduler = TaskScheduler(taskStore, AgentDiscovery(context))
        val taskRunner = LocalTaskRunner(taskStore, scheduler, taskStepStore, NoopExecutor())
        val cloudflareStore = KeystoreCloudflareCredentialStore(secrets)
        val deepSeekStore = KeystoreDeepSeekCredentialStore(secrets)
        val museStore = KeystoreMuseCredentialStore(secrets)
        val grokStore = KeystoreGrokCredentialStore(secrets)
        val claudeStore = KeystoreClaudeCredentialStore(secrets)
        val genericStore = KeystoreGenericOpenAiCredentialStore(secrets)
        val genericAdapter = GenericOpenAiBrainAdapter(configStore, genericStore)
        val cloudflareProvider = CloudflareBrainAdapter(
            credentialStore = cloudflareStore,
            accountIdProvider = { null },
            baseUrlProvider = { null },
            configuredModelProvider = { null },
        )
        return BrainBootstrap.BrainComponents(
            engine = engine,
            authManager = authManager,
            tokenStore = tokenStore,
            brainAdapter = SiwcBrainAdapter(tokenStore, authManager),
            configStore = configStore,
            cloudflareCredentialStore = cloudflareStore,
            cloudflareProvider = cloudflareProvider,
            cloudflareBridge = CloudflareM2BrainAdapter(cloudflareProvider) { null },
            deepSeekAdapter = DeepSeekBrainAdapter(deepSeekStore),
            deepSeekCredentialStore = deepSeekStore,
            museAdapter = MuseBrainAdapter(museStore),
            museCredentialStore = museStore,
            grokAdapter = GrokBrainAdapter(grokStore),
            grokCredentialStore = grokStore,
            claudeAdapter = ClaudeBrainAdapter(claudeStore),
            claudeCredentialStore = claudeStore,
            genericAdapter = genericAdapter,
            genericCredentialStore = genericStore,
            workspaceAdapter = workspaceAdapter,
            pythonRuntime = PreferEmbeddedPythonRuntime(UnavailablePython()),
            turnController = TurnController(),
            taskStore = taskStore,
            taskScheduler = scheduler,
            taskStepStore = taskStepStore,
            pairingTrustStore = InMemoryPairingTrustStore(),
            taskRunner = taskRunner,
            eventLog = eventLog,
            sessionLedger = NoOpSessionLedger,
            conversationStore = conversations,
            workspaceDir = workspaceRoot,
            skillStore = InMemorySkillStore(),
        )
    }

    private fun awaitIdle(): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (!viewModel.isRunning()) return true
            Thread.sleep(50)
        }
        return !viewModel.isRunning()
    }

    private fun assistantText(): String =
        viewModel.stateNow().turns.joinToString("\n") { it.assistantText }

    private fun allNotes(): String =
        viewModel.stateNow().turns.flatMap { it.notes }.joinToString("\n")

    private fun dispatchedRow(): TaskRecord =
        taskStore.listTasks().single { it.sessionId == null }

    private fun visibleErrorText(): String =
        viewModel.stateNow().turns.joinToString("\n") {
            listOfNotNull(it.error, it.assistantText.takeIf { s -> s.isNotBlank() })
                .joinToString("\n")
        }

    @Test
    fun `task instruction to manus builds a dispatched row and finishes it completed with streamed content`() {
        assertTrue(viewModel.switchSession("session-manus", "Manus", manusAgent()))
        assertTrue(viewModel.send("写个报告").accepted)
        assertTrue(awaitIdle())

        assertEquals(1, manusPort.calls.size)
        assertEquals("写个报告", manusPort.calls[0].prompt)
        assertEquals(0, siwcAdapter.seenRequests.size)

        // 分派任务行：sessionId 为 null（泵可认领的形态），本轮自己认领执行。
        val row = dispatchedRow()
        assertEquals("completed", row.state)
        assertEquals("manus", row.assignedAgentId)
        assertTrue(row.result!!.contains("报告已完成。"))

        // 流式内容实时落对话：快照正文 + 工具卡 + 产物汇总 + 用时。
        assertTrue(assistantText().contains("报告已完成。"))
        assertTrue(assistantText().contains("我先看一下需求。"))
        val toolCalls = viewModel.stateNow().turns.flatMap { it.toolCalls }
        assertEquals(1, toolCalls.size)
        assertEquals("shell", toolCalls[0].name)
        assertEquals(ToolCallStatus.SUCCESS, toolCalls[0].status)
        assertEquals("读取文件", toolCalls[0].resultSummary)
        // V5 聊天清理 20261111：产物不再是正文里的一行“产物（共 N 个文件）”
        // 文字，改成正式产物卡片（attachments）；卡片内容与原来同一份附件。
        val attachments = viewModel.stateNow().turns.flatMap { it.attachments }
        assertEquals(1, attachments.size)
        assertEquals("report.md", attachments[0].name)
        assertEquals("https://manus.im/dl/abc", attachments[0].url)
        assertTrue("终态应有耗时备注", allNotes().contains("用时"))
        assertEquals(TurnStatus.DONE, viewModel.stateNow().turns.single().status)
        // 说话者是 Manus（Agent），不是大脑：轮次抬头写 Manus，用量行不认它。
        val turn = viewModel.stateNow().turns.single()
        assertEquals("Manus", turn.speaker)
        assertFalse(turn.speakerIsBrain)
    }

    @Test
    fun `manus task turn records tool steps`() {
        assertTrue(viewModel.switchSession("session-manus", "Manus", manusAgent()))
        assertTrue(viewModel.send("写个报告").accepted)
        assertTrue(awaitIdle())

        val row = dispatchedRow()
        val steps = taskStepStore.listSteps(row.taskId)
        assertEquals(1, steps.size)
        assertEquals("shell", steps[0].toolName)
        assertEquals(TaskStepStatus.SUCCESS, steps[0].status)
    }

    @Test
    fun `plain greeting to manus builds no dispatched row`() {
        manusPort = ScriptedManusPort(
            listOf(
                ManusChatEvent.Progress("Manus 任务已创建，正在执行…"),
                ManusChatEvent.Result("你好，我是 Manus。"),
            ),
        )
        viewModel.attachManusChat(
            port = manusPort,
            isManusConfigured = { manusConfigured },
        )
        assertTrue(viewModel.switchSession("session-manus", "Manus", manusAgent()))
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        assertEquals(1, manusPort.calls.size)
        assertEquals(0, siwcAdapter.seenRequests.size)
        assertTrue("纯聊天绝不能建分派任务行", taskStore.listTasks().none { it.sessionId == null })
        assertTrue(assistantText().contains("你好，我是 Manus。"))
    }

    @Test
    fun `manus task failure lands the row failed and ends the turn honestly`() {
        manusPort = ScriptedManusPort(
            listOf(
                ManusChatEvent.Failed("Manus 调用失败（unauthenticated）：密钥被拒绝（401 未认证），请检查 Manus API Key。"),
            ),
        )
        viewModel.attachManusChat(
            port = manusPort,
            isManusConfigured = { manusConfigured },
        )
        assertTrue(viewModel.switchSession("session-manus", "Manus", manusAgent()))
        assertTrue(viewModel.send("写个报告").accepted)
        assertTrue(awaitIdle())

        val row = dispatchedRow()
        assertEquals("failed", row.state)
        assertTrue(row.error!!.contains("unauthenticated"))
        val error = visibleErrorText()
        assertTrue(error.contains("Manus"))
        assertTrue(error.contains("unauthenticated"))
        assertFalse(error.contains("ChatGPT"))
        assertEquals(TurnStatus.ERROR, viewModel.stateNow().turns.single().status)
        assertEquals(0, siwcAdapter.seenRequests.size)
    }
}
