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
import wherevercode.agent.core.agent.AgentLoginState
import wherevercode.agent.core.agent.AgentRecord
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
import wherevercode.agent.brain.FakeTaskStore
import wherevercode.agent.core.task.LocalTaskRunner
import wherevercode.agent.core.task.TaskExecutor
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStepEvent
import wherevercode.agent.core.task.TaskExecutionResult
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.workspace.FileWorkspaceAdapter
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.brain.ToolRegistry

/**
 * 阶段 A（V5 紧急开发）：ChatViewModel 按 Agent 路由。
 *
 * 复现 maintainer 2026-11-10 19:30 的 bug：选了云端 Agent 再发“在吗”，
 * 消息走了全局大脑 ChatGPT（SIWC），额度耗尽后原样返回英文报错。
 * 假适配器只冒充“哪个大脑回了话”（回答文本不同），不冒充真实模型
 * 调用：断言的是路由（调了谁、没调谁），不是模型质量。
 *
 * 先于修复写成（红）：[ChatViewModel.switchSession] 尚无 agent 参数，
 * [ChatViewModel.selectedAgent] 尚不存在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatViewModelAgentRoutingTest {

    /** Test double：可指定 adapterId 与回答脚本的假大脑。 */
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

    private lateinit var tmpDir: File
    private lateinit var configStore: SharedPreferencesBrainConfigStore
    private lateinit var secrets: MemorySecrets
    private lateinit var deepseekAdapter: ScriptedAdapter
    private lateinit var siwcAdapter: ScriptedAdapter
    private lateinit var viewModel: ChatViewModel

    private fun deepseekAgent() = AgentRecord(
        agentId = "deepseek",
        displayName = "DeepSeek",
        kind = AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = BrainKind.MODEL_API,
        adapterId = "deepseek",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-flash",
        authRef = "deepseek:api-key",
    )

    private fun chatgptAgent() = AgentRecord(
        agentId = "chatgpt",
        displayName = "ChatGPT",
        kind = AgentKind.CLOUD,
        access = AgentAccess.APP,
        brainKind = BrainKind.SUBSCRIPTION,
        adapterId = "subscription",
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
        tmpDir = Files.createTempDirectory("cvm-routing-test").toFile()
        secrets = MemorySecrets().also {
            it.put("deepseek:api-key", "test-key-not-real")
        }
        configStore = SharedPreferencesBrainConfigStore(context)
        configStore.saveConfig(
            BrainConfig(
                brainId = "deepseek",
                kind = BrainKind.MODEL_API,
                adapterId = "deepseek",
                label = "DeepSeek API",
                baseUrl = "https://api.deepseek.com",
                model = "deepseek-flash",
                authRef = "deepseek:api-key",
            ),
        )
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
        configStore.saveConfig(
            BrainConfig(
                brainId = "cli-membership",
                kind = BrainKind.SUBSCRIPTION,
                adapterId = "cli-membership",
                label = "CLI 会员额度",
                baseUrl = null,
                model = "codex",
                authRef = null,
                loginState = AgentLoginState.LOGGED_IN,
                cliBinary = "codex",
            ),
        )
        configStore.setCurrentBrainId("siwc-chatgpt")

        BrainAdapterRegistry.clearForTests()
        BrainAdapterRegistry.registerConfig(configStore.getConfig("deepseek")!!)
        BrainAdapterRegistry.registerConfig(configStore.getConfig("siwc-chatgpt")!!)
        BrainAdapterRegistry.registerConfig(configStore.getConfig("cli-membership")!!)
        deepseekAdapter = ScriptedAdapter("deepseek", answer("DEEPSEEK-ANSWER"))
        siwcAdapter = ScriptedAdapter("siwc-chatgpt", answer("SIWC-ANSWER"))
        BrainAdapterRegistry.registerAdapter(deepseekAdapter)
        BrainAdapterRegistry.registerAdapter(siwcAdapter)

        viewModel = ChatViewModel()
        viewModel.attach(buildComponents(), AndroidApprovalGate.get(context))
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
        val conversations: ConversationStore = InMemoryConversationStore()
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
        val cloudflareProvider = wherevercode.agent.brain.cloudflare.CloudflareBrainAdapter(
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

    /** 等一轮跑完（最长 10 秒）；超时返回 false，调用方据此断言失败。 */
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

    /**
     * 轮次可见的全部错误文本：ChatTimeline.finishTurn 在出错且正文
     * 为空时把错误原文写进 assistantText 并清掉 error 字段（避免
     * 错误卡重复），所以断言错误内容要两处一起看。
     */
    private fun visibleErrorText(): String =
        viewModel.stateNow().turns.joinToString("\n") {
            listOfNotNull(it.error, it.assistantText.takeIf { s -> s.isNotBlank() })
                .joinToString("\n")
        }

    @Test
    fun `selecting deepseek agent sends to deepseek not the global chatgpt brain`() {
        assertTrue(viewModel.switchSession("session-deepseek", "DeepSeek", deepseekAgent()))
        assertEquals("deepseek", viewModel.selectedAgent?.agentId)

        val result = viewModel.send("在吗")
        assertTrue(result.accepted)
        assertTrue(awaitIdle())

        assertEquals(1, deepseekAdapter.seenRequests.size)
        assertEquals(0, siwcAdapter.seenRequests.size)
        assertTrue(assistantText().contains("DEEPSEEK-ANSWER"))
        assertFalse(assistantText().contains("SIWC-ANSWER"))
    }

    @Test
    fun `switching agents keeps each history separate and restores it`() {
        viewModel.switchSession("session-deepseek", "DeepSeek", deepseekAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        assertTrue(assistantText().contains("DEEPSEEK-ANSWER"))

        viewModel.switchSession("session-chatgpt", "ChatGPT", chatgptAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        assertTrue(assistantText().contains("SIWC-ANSWER"))
        assertFalse(assistantText().contains("DEEPSEEK-ANSWER"))

        viewModel.switchSession("session-deepseek", "DeepSeek", deepseekAgent())
        assertTrue(assistantText().contains("DEEPSEEK-ANSWER"))
        assertFalse(assistantText().contains("SIWC-ANSWER"))
    }

    @Test
    fun `agent without a configured brain errors honestly and calls no brain`() {
        secrets.map.remove("deepseek:api-key")
        viewModel.switchSession("session-deepseek", "DeepSeek", deepseekAgent())

        val result = viewModel.send("在吗")

        assertFalse(result.accepted)
        assertTrue(result.message!!.contains("未配置"))
        assertEquals(0, deepseekAdapter.seenRequests.size)
        assertEquals(0, siwcAdapter.seenRequests.size)
    }

    @Test
    fun `manus agent never calls any brain and never builds a task`() {
        val manus = AgentRecord(
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
        viewModel.switchSession("session-manus", "Manus", manus)

        val result = viewModel.send("在吗")

        assertFalse(result.accepted)
        assertTrue(result.message!!.contains("Manus"))
        assertEquals(0, deepseekAdapter.seenRequests.size)
        assertEquals(0, siwcAdapter.seenRequests.size)
    }

    @Test
    fun `quota error is attributed to the brain that actually ran`() {
        val quotaSiwc = ScriptedAdapter("siwc-chatgpt") {
            flowOf(
                BrainEvent.Error(
                    code = "quota_exceeded",
                    message = "The ChatGPT user has reached their Subscription Sharing usage limit",
                    retryable = false,
                ),
                BrainEvent.Done("error"),
            )
        }
        BrainAdapterRegistry.clearForTests()
        BrainAdapterRegistry.registerConfig(configStore.getConfig("deepseek")!!)
        BrainAdapterRegistry.registerConfig(configStore.getConfig("siwc-chatgpt")!!)
        BrainAdapterRegistry.registerConfig(configStore.getConfig("cli-membership")!!)
        BrainAdapterRegistry.registerAdapter(deepseekAdapter)
        BrainAdapterRegistry.registerAdapter(quotaSiwc)

        viewModel.switchSession("session-chatgpt", "ChatGPT", chatgptAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        val error = visibleErrorText()
        assertTrue(error.contains("ChatGPT"))
        assertTrue(error.contains("额度"))
        assertTrue(error.contains("quota_exceeded"))
    }

    @Test
    fun `agent turn never fails over to another brain on a retryable error`() {
        val flakyDeepseek = ScriptedAdapter("deepseek") {
            flowOf(
                BrainEvent.Error(
                    code = "transport_error",
                    message = "deepseek 暂不可达（测试替身）",
                    retryable = true,
                ),
                BrainEvent.Done("error"),
            )
        }
        BrainAdapterRegistry.clearForTests()
        BrainAdapterRegistry.registerConfig(configStore.getConfig("deepseek")!!)
        BrainAdapterRegistry.registerConfig(configStore.getConfig("siwc-chatgpt")!!)
        BrainAdapterRegistry.registerConfig(configStore.getConfig("cli-membership")!!)
        BrainAdapterRegistry.registerAdapter(flakyDeepseek)
        BrainAdapterRegistry.registerAdapter(siwcAdapter)

        viewModel.switchSession("session-deepseek", "DeepSeek", deepseekAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        assertEquals(0, siwcAdapter.seenRequests.size)
        assertTrue(visibleErrorText().contains("transport_error"))
    }

    @Test
    fun `new conversation clears the selected agent`() {
        viewModel.switchSession("session-deepseek", "DeepSeek", deepseekAgent())
        assertEquals("deepseek", viewModel.selectedAgent?.agentId)

        assertTrue(viewModel.newConversation())

        assertEquals(null, viewModel.selectedAgent)
    }

    @Test
    fun `reopening the same session with an agent binds the agent`() {
        viewModel.switchSession("session-deepseek", "DeepSeek")
        assertEquals(null, viewModel.selectedAgent)

        viewModel.switchSession("session-deepseek", "DeepSeek", deepseekAgent())

        assertEquals("deepseek", viewModel.selectedAgent?.agentId)
    }
}
