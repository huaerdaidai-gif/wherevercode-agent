package wherevercode.agent.ui

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
import wherevercode.agent.brain.FakeTaskStore
import wherevercode.agent.brain.InMemoryConversationStore
import wherevercode.agent.brain.InMemoryEventLog
import wherevercode.agent.brain.InMemoryMemoryStore
import wherevercode.agent.brain.InMemorySkillStore
import wherevercode.agent.brain.ModelInfo
import wherevercode.agent.brain.SharedPreferencesBrainConfigStore
import wherevercode.agent.brain.ToolRegistry
import wherevercode.agent.brain.TurnController
import wherevercode.agent.brain.TurnControlState
import wherevercode.agent.brain.cloudflare.CloudflareBrainAdapter
import wherevercode.agent.brain.cloudflare.CloudflareM2BrainAdapter
import wherevercode.agent.brain.cloudflare.KeystoreCloudflareCredentialStore
import wherevercode.agent.brain.claude.ClaudeBrainAdapter
import wherevercode.agent.brain.claude.KeystoreClaudeCredentialStore
import wherevercode.agent.brain.deepseek.DeepSeekBrainAdapter
import wherevercode.agent.brain.deepseek.KeystoreDeepSeekCredentialStore
import wherevercode.agent.brain.generic.GenericOpenAiBrainAdapter
import wherevercode.agent.brain.generic.KeystoreGenericOpenAiCredentialStore
import wherevercode.agent.brain.grok.GrokBrainAdapter
import wherevercode.agent.brain.grok.KeystoreGrokCredentialStore
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
import wherevercode.agent.core.device.JvmDeviceAdapter
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.project.DispatchFolderSource
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
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.task.LocalTaskRunner
import wherevercode.agent.core.task.TaskExecutor
import wherevercode.agent.core.task.TaskExecutionResult
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStepEvent
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.workspace.FileWorkspaceAdapter
import wherevercode.agent.AgentDiscovery

/**
 * 派活丢项目绑定（2026-11-12，任务 20106890 回归）：
 * maintainer 在已绑定项目 wherevercode-agent-2 的对话里派给 Mac Codex，
 * 任务行建行时 projectId=None。根因是两套绑定脱节——项目页点选 /
 * 聊天选项目只写全局聊天绑定（ChatProjectBinding.projectId，横幅
 * 读它），而派活读的是会话级绑定 ChatViewModel.currentProjectId()，
 * 它只由“打开项目浏览家文件夹”那条少用流程才填；本对话从来没经过
 * 那条流程，于是字段是 null。
 *
 * 修法（唯一口径）：currentProjectId() 在会话级绑定缺失时回落到
 * 全局聊天绑定（MainActivity 接线 chatProjectIdProvider）。
 * 两条派活路（远端任务通道 startTaskTurn、大脑 dispatch_task 经
 * ToolRegistry → MainActivity.currentChatProjectId）都读它，一处修好。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatViewModelProjectBindingTest {

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

    private class ScriptedRemotePort(
        private var events: List<HandEvent>,
        private var holdUntilReleased: Boolean = false,
    ) : RemoteAgentChatPort {
        data class Call(val target: RemoteTarget, val taskId: String, val text: String)
        val calls = mutableListOf<Call>()
        private val callStarted = CountDownLatch(1)
        private val releaseEvents = CompletableDeferred<Unit>()

        fun awaitCall(): Boolean = callStarted.await(10, TimeUnit.SECONDS)

        fun releaseEvents() {
            releaseEvents.complete(Unit)
        }

        fun configure(events: List<HandEvent>, holdUntilReleased: Boolean) {
            this.events = events
            this.holdUntilReleased = holdUntilReleased
        }

        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> {
            calls += Call(target, taskId, text)
            callStarted.countDown()
            if (!holdUntilReleased) return flowOf(*events.toTypedArray())
            return flow {
                releaseEvents.await()
                events.forEach { emit(it) }
            }
        }

        override fun sendControl(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()
    }

    private lateinit var tmpDir: File
    private lateinit var configStore: SharedPreferencesBrainConfigStore
    private lateinit var secrets: MemorySecrets
    private lateinit var siwcAdapter: ScriptedAdapter
    private lateinit var viewModel: ChatViewModel
    private lateinit var conversations: ConversationStore
    private lateinit var remotePort: ScriptedRemotePort
    private lateinit var taskStore: FakeTaskStore
    private lateinit var scheduler: TaskScheduler

    private val peer = DiscoveredPeer(
        deviceId = "mac",
        displayName = "Mac",
        host = "192.168.1.4",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )
    private val codexTarget = RemoteTarget(
        peer = peer,
        agentId = "codex",
        folder = "/Users/example-user/Projects/wherevercode-agent",
        folderSource = DispatchFolderSource.PROJECT,
    )

    private fun codexAgent() = AgentRecord(
        agentId = "codex",
        displayName = "Codex",
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.AGENT_RUNTIME,
        adapterId = "codex",
        binary = "codex",
        deviceId = "mac",
        deviceFolder = "/Users/example-user/v5-codex-work",
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
        tmpDir = Files.createTempDirectory("cvm-binding-test").toFile()
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

        remotePort = ScriptedRemotePort(emptyList())
        viewModel = ChatViewModel()
        viewModel.attach(buildComponents(), AndroidApprovalGate.get(context))
        viewModel.attachRemoteChat(
            port = remotePort,
            controlSender = null,
            targetResolver = { agent -> if (agent.agentId == "codex") codexTarget else null },
            isRemoteAgent = { agent -> agent.agentId == "codex" },
        )
    }

    @After
    fun tearDown() {
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
        val taskStepStore = InMemoryTaskStepStore()
        scheduler = TaskScheduler(taskStore, AgentDiscovery(context))
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

    @Test
    fun `global chat binding fills missing session binding`() {
        // 20106890 回归：会话从来没经过“打开项目浏览家文件夹”，会话级
        // 绑定为空；全局聊天绑定（项目页点选）已是 wherevercode-agent-2。
        // 修前 currentProjectId() 返回 null，任务行丢 projectId。
        assertTrue(viewModel.switchSession("session-codex", "Codex", codexAgent()))
        viewModel.chatProjectIdProvider = { "wherevercode-agent-2" }
        assertEquals("wherevercode-agent-2", viewModel.currentProjectId())
    }

    @Test
    fun `session binding wins over global binding`() {
        assertTrue(viewModel.switchSession("session-codex", "Codex", codexAgent()))
        viewModel.bindCurrentSessionToProject("project-a")
        viewModel.chatProjectIdProvider = { "project-b" }
        assertEquals("project-a", viewModel.currentProjectId())
    }

    @Test
    fun `no binding returns null and dispatch still falls back`() {
        // D5 口径不变：两个绑定都没有时为 null，派活走回落、不拦下。
        assertTrue(viewModel.switchSession("session-codex", "Codex", codexAgent()))
        assertNull(viewModel.currentProjectId())
    }

    @Test
    fun `blank provider value returns null`() {
        assertTrue(viewModel.switchSession("session-codex", "Codex", codexAgent()))
        viewModel.chatProjectIdProvider = { "   " }
        assertNull(viewModel.currentProjectId())
    }

    @Test
    fun `throwing provider returns null without crashing`() {
        assertTrue(viewModel.switchSession("session-codex", "Codex", codexAgent()))
        viewModel.chatProjectIdProvider = { throw IllegalStateException("绑定读失败") }
        assertNull(viewModel.currentProjectId())
    }

    @Test
    fun `restored session binding wins and global fills other sessions`() {
        viewModel.restoreSessionProjects(mapOf("session-codex" to "project-a"))
        viewModel.chatProjectIdProvider = { "wherevercode-agent-2" }
        assertTrue(viewModel.switchSession("session-codex", "Codex", codexAgent()))
        assertEquals("project-a", viewModel.currentProjectId())
        assertTrue(viewModel.switchSession("session-other", "Other", null))
        assertEquals("wherevercode-agent-2", viewModel.currentProjectId())
    }

    @Test
    fun `bound chat dispatch carries projectId`() {
        // 端到端：已绑定项目的对话发任务型指令，任务行必须带 projectId。
        remotePort.configure(
            events = listOf(
                HandEvent.Result(
                    1,
                    "分支 test/team-check-20261111 已建，提交 abc123",
                ),
            ),
            holdUntilReleased = true,
        )
        assertTrue(viewModel.switchSession("session-codex", "Codex", codexAgent()))
        viewModel.chatProjectIdProvider = { "wherevercode-agent-2" }
        val result = viewModel.send("新建分支 test/team-check-20261111 并提交文件 team-check-20261111.txt，内容为团队任务测试 2026-11-11")
        assertTrue(result.accepted)
        assertTrue("远端调用应在任务行建好后发出", remotePort.awaitCall())

        val task = taskStore.listTasks(null, null).single()
        assertEquals("wherevercode-agent-2", task.projectId)
        assertEquals("codex", task.assignedAgentId)
        assertEquals("远端消息发出后任务应处于执行中", "working", task.state)
        assertEquals("远端调用应真实发出", 1, remotePort.calls.size)
        remotePort.releaseEvents()
        assertTrue("远端返回终态后轮次应结束", awaitIdle())
        val completedTask = taskStore.listTasks(null, null).single()
        assertEquals("completed", completedTask.state)
        assertTrue(completedTask.result?.contains("已建，提交 abc123") == true)
    }

    @Test
    fun `unbound chat dispatch carries no projectId`() {
        // 端到端：无绑定时任务行 projectId 为 null，仍允许派活走回落。
        remotePort.configure(
            events = listOf(
                HandEvent.Result(
                    1,
                    "分支 test/team-check-20261111 已建，提交 abc123",
                ),
            ),
            holdUntilReleased = true,
        )
        assertTrue(viewModel.switchSession("session-codex", "Codex", codexAgent()))
        val result = viewModel.send("新建分支 test/team-check-20261111 并提交文件 team-check-20261111.txt，内容为团队任务测试 2026-11-11")
        assertTrue(result.accepted)
        assertTrue("远端调用应在任务行建好后发出", remotePort.awaitCall())

        val task = taskStore.listTasks(null, null).single()
        assertNull(task.projectId)
        assertEquals("codex", task.assignedAgentId)
        assertEquals("远端消息发出后任务应处于执行中", "working", task.state)
        assertEquals("远端调用应真实发出", 1, remotePort.calls.size)
        remotePort.releaseEvents()
        assertTrue("远端返回终态后轮次应结束", awaitIdle())
        val completedTask = taskStore.listTasks(null, null).single()
        assertEquals("completed", completedTask.state)
        assertTrue(completedTask.result?.contains("已建，提交 abc123") == true)
    }
}
