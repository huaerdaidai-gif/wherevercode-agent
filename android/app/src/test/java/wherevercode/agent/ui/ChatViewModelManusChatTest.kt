package wherevercode.agent.ui

import wherevercode.agent.core.task.testTaskScheduler
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
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.workspace.FileWorkspaceAdapter
import wherevercode.agent.brain.ToolRegistry

/**
 * 阶段 C（V5 紧急开发）：选 Manus 后，消息真的走 Manus 聊天端口。
 *
 * 红测试：ChatViewModel.attachManusChat / Manus 轮次尚不存在。
 *
 * 假 Manus 只冒充“Manus 回了什么话”（回答文本不同），不冒充真实
 * Manus 调用：断言的是路由（调了 Manus、没调大脑）与回填、持久化、
 * 多轮同一会话、切换隔离。真机验证（K70 上一次真实 Manus 任务，
 * 走免费积分）仍标未验证，等协调员验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatViewModelManusChatTest {

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

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(
            "wherevercode_brain_configs",
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        tmpDir = Files.createTempDirectory("cvm-manus-test").toFile()
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

        manusPort = ScriptedManusPort(
            listOf(
                ManusChatEvent.Progress("Manus 任务已创建，正在执行…"),
                ManusChatEvent.Result("你好，我是 Manus。"),
            ),
        )
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
        val taskStore = FakeTaskStore()
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
        val taskStepStore = InMemoryTaskStepStore()
        val scheduler = testTaskScheduler(taskStore, AgentDiscovery(context))
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

    private fun visibleErrorText(): String =
        viewModel.stateNow().turns.joinToString("\n") {
            listOfNotNull(it.error, it.assistantText.takeIf { s -> s.isNotBlank() })
                .joinToString("\n")
        }

    @Test
    fun `selecting manus sends to manus not the global chatgpt brain`() {
        assertTrue(viewModel.switchSession("session-manus", "Manus", manusAgent()))
        assertEquals("manus", viewModel.selectedAgent?.agentId)

        val result = viewModel.send("在吗")
        assertTrue(result.accepted)
        assertTrue(awaitIdle())

        assertEquals(1, manusPort.calls.size)
        assertEquals("manus", manusPort.calls[0].agentId)
        assertEquals("在吗", manusPort.calls[0].prompt)
        assertEquals(0, siwcAdapter.seenRequests.size)
        assertTrue(assistantText().contains("你好，我是 Manus。"))
        assertFalse(assistantText().contains("SIWC-ANSWER"))
    }

    @Test
    fun `manus turn persists messages to the conversation store`() {
        viewModel.switchSession("session-manus", "Manus", manusAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        val sessionId = viewModel.currentSessionId()!!
        val messages = conversations.listMessages(sessionId)
        assertTrue(messages.any { it.role == "user" && it.content.contains("在吗") })
        assertTrue(messages.any { it.role == "assistant" && it.content.contains("我是 Manus") })
    }

    @Test
    fun `manus failed error ends the turn honestly and calls no brain`() {
        manusPort = ScriptedManusPort(
            listOf(
                ManusChatEvent.Failed("Manus 调用失败（unauthenticated）：密钥被拒绝（401 未认证），请检查 Manus API Key。"),
            ),
        )
        viewModel.attachManusChat(
            port = manusPort,
            isManusConfigured = { manusConfigured },
        )
        viewModel.switchSession("session-manus", "Manus", manusAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        val error = visibleErrorText()
        assertTrue(error.contains("Manus"))
        assertTrue(error.contains("unauthenticated"))
        assertFalse(error.contains("ChatGPT"))
        assertEquals(0, siwcAdapter.seenRequests.size)
    }

    @Test
    fun `unconfigured manus errors honestly and sends nothing`() {
        manusConfigured = false
        viewModel.switchSession("session-manus", "Manus", manusAgent())

        val result = viewModel.send("在吗")

        assertFalse(result.accepted)
        assertTrue(result.message!!.contains("Manus"))
        assertEquals(0, manusPort.calls.size)
        assertEquals(0, siwcAdapter.seenRequests.size)
    }

    @Test
    fun `multi-turn follow-up in the same session reuses the same conversation`() {
        viewModel.switchSession("session-manus", "Manus", manusAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        assertTrue(viewModel.send("继续").accepted)
        assertTrue(awaitIdle())

        assertEquals(2, manusPort.calls.size)
        assertEquals("同一会话的多轮必须走同一 conversationId，桥接层才能跟进同一 Manus 任务",
            manusPort.calls[0].conversationId, manusPort.calls[1].conversationId)
        assertEquals("继续", manusPort.calls[1].prompt)
        assertEquals(0, siwcAdapter.seenRequests.size)
    }

    @Test
    fun `switching from manus to default session keeps histories separate`() {
        viewModel.switchSession("session-manus", "Manus", manusAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        assertTrue(assistantText().contains("我是 Manus"))

        viewModel.switchSession("session-default", "WhereverCode会话")
        assertTrue(viewModel.send("你好").accepted)
        assertTrue(awaitIdle())
        assertTrue(assistantText().contains("SIWC-ANSWER"))
        assertFalse(assistantText().contains("我是 Manus"))

        viewModel.switchSession("session-manus", "Manus", manusAgent())
        assertTrue(assistantText().contains("我是 Manus"))
        assertFalse(assistantText().contains("SIWC-ANSWER"))
    }
}
