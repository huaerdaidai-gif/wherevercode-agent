package wherevercode.agent.ui

import android.content.Context
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import wherevercode.agent.core.mcp.RemoteControlResult
import wherevercode.agent.core.mcp.RemoteControlSender
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
import wherevercode.agent.core.task.LocalTaskRunner
import wherevercode.agent.core.task.TaskExecutor
import wherevercode.agent.core.task.TaskExecutionResult
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStepEvent
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.workspace.FileWorkspaceAdapter
import wherevercode.agent.brain.ToolRegistry

/**
 * 阶段 B（V5 紧急开发）：选远程设备 Agent 后，消息真的发给它。
 *
 * 红测试：ChatViewModel.attachRemoteChat / 远端轮次尚不存在。
 *
 * 假远端只冒充“远端回了什么话”（回答文本不同），不冒充真实 VPS：
 * 断言的是路由（调了远端、没调大脑）与回填、持久化、切换隔离。
 * 真机联调（K70 → VPS → Example Agent）仍标未验证，等协调员验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChatViewModelRemoteAgentTest {

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

    /** 测试替身：按脚本回放远端 Hand 事件，记下收到的调用。 */
    private class ScriptedRemotePort(
        private val events: List<HandEvent>,
    ) : RemoteAgentChatPort {
        data class Call(val target: RemoteTarget, val taskId: String, val text: String)
        val calls = mutableListOf<Call>()
        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> {
            calls += Call(target, taskId, text)
            return flowOf(*events.toTypedArray())
        }

        override fun sendControl(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()
    }

    /** 测试替身：记下收到的远端控制指令（目标、任务 id、动作）。 */
    private class RecordingControlSender : RemoteControlSender {
        data class Sent(val target: RemoteTarget, val taskId: String, val command: RemoteControlCommand)
        val sent = mutableListOf<Sent>()
        val taskIds: List<String> get() = sent.map { it.taskId }
        override fun send(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
            onResult: (RemoteControlResult) -> Unit,
        ) {
            sent += Sent(target, taskId, command)
            onResult(RemoteControlResult(true, "ok"))
        }
    }

    /**
     * 测试替身：模拟卡在阻塞读里的远端轮次——cancel() 中断不了
     * NonCancellable 里的等待，只有 abortRun 能结束它（对应
     * HandClient 阻塞 socket 读的真实行为）。
     */
    private class StuckRemotePort : RemoteAgentChatPort {
        data class Call(val target: RemoteTarget, val taskId: String, val text: String)
        val calls = mutableListOf<Call>()
        val aborted = mutableListOf<String>()
        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> {
            calls += Call(target, taskId, text)
            return flow {
                withContext(NonCancellable) {
                    while (!aborted.contains(taskId)) delay(50)
                }
                throw CancellationException("远端连接已掐断（测试替身）")
            }
        }

        override fun sendControl(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()

        override fun abortRun(taskId: String) {
            aborted += taskId
        }
    }

    /** 测试替身：首条远端事件延迟到来，模拟 CodeBuddy 冷启动无输出。 */
    private class SlowRemotePort(
        private val firstEventDelayMs: Long,
    ) : RemoteAgentChatPort {
        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> = flow {
            delay(firstEventDelayMs)
            emit(HandEvent.Progress(2, "Example Agent 正在执行…"))
            emit(HandEvent.Result(3, "在的，有什么可以帮你？"))
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
        deviceId = "remote-host",
        displayName = "远程设备",
        host = "192.0.2.10",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )
    // 本文件的远端聊天场景默认走项目检出文件夹（不触发回落备注）；
    // 回落备注由下面专用的 `fallback turn marks the agent folder in the timeline` 测。
    private val remoteTarget = RemoteTarget(
        peer = peer,
        agentId = "example-agent",
        folder = "/workspace",
        folderSource = DispatchFolderSource.PROJECT,
    )

    private fun grokAgent() = AgentRecord(
        agentId = "example-agent",
        displayName = "Example Agent",
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.AGENT_RUNTIME,
        adapterId = "example-agent",
        binary = "example-agent",
        deviceId = "remote-host",
        deviceFolder = "/workspace",
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
        tmpDir = Files.createTempDirectory("cvm-remote-test").toFile()
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

        remotePort = ScriptedRemotePort(
            listOf(
                HandEvent.Progress(2, "Example Agent 正在执行…"),
                HandEvent.Result(3, "在的，有什么可以帮你？"),
            ),
        )
        viewModel = ChatViewModel()
        viewModel.attach(buildComponents(), AndroidApprovalGate.get(context))
        viewModel.attachRemoteChat(
            port = remotePort,
            controlSender = null,
            targetResolver = { agent -> if (agent.agentId == "example-agent") remoteTarget else null },
            isRemoteAgent = { agent -> agent.agentId == "example-agent" },
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

    private fun assistantText(): String =
        viewModel.stateNow().turns.joinToString("\n") { it.assistantText }

    private fun visibleErrorText(): String =
        viewModel.stateNow().turns.joinToString("\n") {
            listOfNotNull(it.error, it.assistantText.takeIf { s -> s.isNotBlank() })
                .joinToString("\n")
        }

    @Test
    fun `selecting grok build sends to remote not the global chatgpt brain`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        assertEquals("example-agent", viewModel.selectedAgent?.agentId)

        val result = viewModel.send("在吗")
        assertTrue(result.accepted)
        assertTrue(awaitIdle())

        assertEquals(1, remotePort.calls.size)
        assertEquals("example-agent", remotePort.calls[0].target.agentId)
        assertEquals("在吗", remotePort.calls[0].text)
        assertEquals(0, siwcAdapter.seenRequests.size)
        assertTrue(assistantText().contains("在的，有什么可以帮你？"))
        assertFalse(assistantText().contains("SIWC-ANSWER"))
    }

    @Test
    fun `remote turn persists messages to the conversation store`() {
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        val sessionId = viewModel.currentSessionId()!!
        val messages = conversations.listMessages(sessionId)
        assertTrue(messages.any { it.role == "user" && it.content.contains("在吗") })
        assertTrue(messages.any { it.role == "assistant" && it.content.contains("在的") })
    }

    @Test
    fun `remote blocked error ends the turn honestly and calls no brain`() {
        remotePort = ScriptedRemotePort(
            listOf(
                HandEvent.Blocked("远端设备离线：请检查 VPS 是否在线", "cross_device_offline"),
            ),
        )
        viewModel.attachRemoteChat(
            port = remotePort,
            controlSender = null,
            targetResolver = { agent -> if (agent.agentId == "example-agent") remoteTarget else null },
            isRemoteAgent = { agent -> agent.agentId == "example-agent" },
        )
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        assertTrue(visibleErrorText().contains("远端设备离线"))
        assertEquals(0, siwcAdapter.seenRequests.size)
    }

    @Test
    fun `unresolved remote target errors honestly and sends nothing`() {
        viewModel.attachRemoteChat(
            port = remotePort,
            controlSender = null,
            targetResolver = { null },
            isRemoteAgent = { agent -> agent.agentId == "example-agent" },
        )
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())

        val result = viewModel.send("在吗")

        assertFalse(result.accepted)
        assertTrue(result.message!!.contains("未接入") || result.message!!.contains("未配对"))
        assertEquals(0, remotePort.calls.size)
        assertEquals(0, siwcAdapter.seenRequests.size)
    }

    @Test
    fun `switching from remote to default session keeps histories separate`() {
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        assertTrue(assistantText().contains("在的，有什么可以帮你？"))

        viewModel.switchSession("session-default", "WhereverCode会话")
        assertTrue(viewModel.send("你好").accepted)
        assertTrue(awaitIdle())
        assertTrue(assistantText().contains("SIWC-ANSWER"))
        assertFalse(assistantText().contains("在的，有什么可以帮你？"))

        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(assistantText().contains("在的，有什么可以帮你？"))
        assertFalse(assistantText().contains("SIWC-ANSWER"))
    }

    @Test
    fun `same reply in progress and result renders only once with no tail note`() {
        // V5 修复（2026-11-11，608 图）：服务端每行 stdout 既发进度帧
        // 又在终态发完整 output；旧代码把同一文本渲染两次（notes 卡片
        // + 助手气泡）。进度流进助手正文、Result 快照替换后，同一轮
        // 只出现一次，且不留尾巴 notes。
        val reply = "在。我是平台服务器上的分身助手，直接说要办的事就行。"
        remotePort = ScriptedRemotePort(
            listOf(
                HandEvent.Progress(2, reply),
                HandEvent.Result(3, reply),
            ),
        )
        viewModel.attachRemoteChat(
            port = remotePort,
            controlSender = null,
            targetResolver = { agent -> if (agent.agentId == "example-agent") remoteTarget else null },
            isRemoteAgent = { agent -> agent.agentId == "example-agent" },
        )
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        val turns = viewModel.stateNow().turns
        assertEquals(1, turns.size)
        // 2026-11-12 回复归属修复：正文前加归属头（任务编号 + 轮次
        // 标题）；回复本身仍只出现一次，不留尾巴 notes。
        val runTaskId = remotePort.calls.single().taskId
        assertEquals("【任务 ${runTaskId.take(8)} · 在吗】\n$reply", turns[0].assistantText.trim())
        assertEquals(1, turns[0].assistantText.split(reply).size - 1)
        assertTrue(turns[0].notes.isEmpty())
    }

    @Test
    fun `fallback turn marks the project name device and agent folder in the timeline`() {
        // V5 跨设备开发（2026-11-12，D4 新口径 (b)）：本轮回落到 Agent
        // 文件夹时，对话时间线要标明一行（不静默）；项目检出命中时不标。
        // 来源写清项目名/设备/Agent 文件夹，与 folder_fallback 事件
        // 的 reason 同一模板。
        viewModel.projectNameProvider = { "WhereverCode Agent" }
        viewModel.attachRemoteChat(
            port = remotePort,
            controlSender = null,
            targetResolver = { agent ->
                if (agent.agentId == "example-agent") {
                    remoteTarget.copy(folderSource = DispatchFolderSource.AGENT_FALLBACK)
                } else {
                    null
                }
            },
            isRemoteAgent = { agent -> agent.agentId == "example-agent" },
        )
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        viewModel.bindCurrentSessionToProject("wherevercode-agent")
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        val turns = viewModel.stateNow().turns
        assertEquals(1, turns.size)
        assertEquals(1, turns[0].notes.size)
        assertTrue(turns[0].notes[0].contains("WhereverCode Agent"))
        assertTrue(turns[0].notes[0].contains("remote-host"))
        assertTrue(turns[0].notes[0].contains("Agent 文件夹 /workspace"))
    }

    @Test
    fun `fallback turn of an unbound session says so instead of inventing a project`() {
        viewModel.attachRemoteChat(
            port = remotePort,
            controlSender = null,
            targetResolver = { agent ->
                if (agent.agentId == "example-agent") {
                    remoteTarget.copy(folderSource = DispatchFolderSource.AGENT_FALLBACK)
                } else {
                    null
                }
            },
            isRemoteAgent = { agent -> agent.agentId == "example-agent" },
        )
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        val turns = viewModel.stateNow().turns
        assertEquals(1, turns.size)
        assertEquals(1, turns[0].notes.size)
        assertTrue(turns[0].notes[0].contains("未绑定项目"))
        assertTrue(turns[0].notes[0].contains("Agent 文件夹 /workspace"))
    }

    @Test
    fun `successful remote round notifies onRemoteRoundSucceeded`() {
        // V5 修复（2026-11-11，能用的 Agent 显示“未验证”）：一次真实
        // 往返成功后，ViewModel 须通知回调方把该 Agent 标成已验证。
        val seen = mutableListOf<AgentRecord>()
        viewModel.onRemoteRoundSucceeded = { seen += it }
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        assertEquals(listOf("example-agent"), seen.map { it.agentId })
    }

    @Test
    fun `blocked remote round does not notify onRemoteRoundSucceeded`() {
        // 没回话的 Agent 不贴已验证：Blocked 终态不调回调。
        remotePort = ScriptedRemotePort(
            listOf(
                HandEvent.Blocked("远端设备离线：请检查 VPS 是否在线", "cross_device_offline"),
            ),
        )
        viewModel.attachRemoteChat(
            port = remotePort,
            controlSender = null,
            targetResolver = { agent -> if (agent.agentId == "example-agent") remoteTarget else null },
            isRemoteAgent = { agent -> agent.agentId == "example-agent" },
        )
        val seen = mutableListOf<AgentRecord>()
        viewModel.onRemoteRoundSucceeded = { seen += it }
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `new conversation then opening the same agent session starts fresh`() {
        // V5 修复（2026-11-11，新对话也带尾巴）：点“新对话”后选同一
        // 远端 Agent，fresh=true 时不把旧轮次带回来；不传 fresh 时旧
        // 轮次照常恢复（见上一个测试）。
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        assertTrue(assistantText().contains("在的，有什么可以帮你？"))

        assertTrue(viewModel.newConversation())
        assertTrue(viewModel.stateNow().turns.isEmpty())

        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent(), fresh = true))
        assertTrue(viewModel.stateNow().turns.isEmpty())
        assertEquals("session-grok", viewModel.currentSessionId())
    }

    // -- V5 修复（2026-11-11，CodeBuddy 卡死轮次）回归测试 --------

    private fun attachStuckTurn(
        stuck: StuckRemotePort,
        controlSender: RemoteControlSender?,
    ) {
        viewModel.attachRemoteChat(
            port = stuck,
            controlSender = controlSender,
            targetResolver = { agent -> if (agent.agentId == "example-agent") remoteTarget else null },
            isRemoteAgent = { agent -> agent.agentId == "example-agent" },
        )
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(viewModel.send("在吗").accepted)
        // 等远端流确实跑起来（port 收到调用），再按停止。
        val deadline = System.currentTimeMillis() + 5_000
        while (stuck.calls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(1, stuck.calls.size)
        assertTrue("轮次应在跑", viewModel.isRunning())
    }

    @Test
    fun `stopping a stuck remote turn ends it and a new conversation can start`() {
        // 远端流卡在阻塞读里时，只 cancel job 断不了读，轮次占着
        // “执行中”、用户开不了新对话。停止必须先掐连接（abortRun），
        // 轮次马上终态，新对话能开。
        val stuck = StuckRemotePort()
        val controlSender = RecordingControlSender()
        attachStuckTurn(stuck, controlSender)

        viewModel.stop()
        assertTrue("停止后轮次应终态", awaitIdle())
        assertEquals("停止应掐断远端连接", listOf(stuck.calls.single().taskId), stuck.aborted)
        assertTrue("停止后应能开新对话", viewModel.newConversation())
    }

    @Test
    fun `remote round creates a real task row even when another task is in hand`() {
        // 2026-11-12 回复归属修复：远端轮次不再走 beginTurn（调度器
        // 全局单任务门禁，一脑在手时认领失败回 null，旧代码拿 null
        // 就改用随机 UUID 发 Hand，活跑在任务表外面——真机事故
        // e7690865 / 21504781）。这里先占一个在手任务（旧代码下
        // beginTurn 必回 null），再断言：发给 Hand 的 run 任务 id
        // 是任务库里真实的一行（不是随机 UUID），停止指令用的也是它。
        assertNotNull(taskStore.createTask(TaskRecord(taskId = "blocker", title = "blocker")))
        assertNotNull(scheduler.claimTask("blocker", TurnTaskRecorder.LOCAL_AGENT_ID))
        assertNotNull(scheduler.startTask("blocker"))
        assertFalse("占位任务在手时调度器应拒绝再认领", scheduler.canClaim())

        val stuck = StuckRemotePort()
        val controlSender = RecordingControlSender()
        attachStuckTurn(stuck, controlSender)

        val runTaskId = stuck.calls.single().taskId
        val row = taskStore.getTask(runTaskId)
        assertNotNull("发给 Hand 的任务 id 必须是任务库里的真实行", row)
        assertEquals("example-agent", row?.assignedAgentId)
        assertEquals("session-grok", row?.sessionId)

        viewModel.stop()
        assertTrue("停止后轮次应终态", awaitIdle())
        assertEquals("停止指令必须用实际发给 Hand 的任务 id", listOf(runTaskId), controlSender.taskIds)
        assertEquals("停止应掐断该任务的远端连接", listOf(runTaskId), stuck.aborted)
    }

    @Test
    fun `remote reply carries the task attribution header`() {
        // 2026-11-12 回复归属修复：远端回复正文前加归属头（任务编号
        // + 轮次标题），聊天、存库消息、任务结果三处一致；串任务时
        // 一眼分清这是哪轮的回复（真机事故：Codex 的“可靠性修复”
        // 被当成团队任务的结果）。
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        val runTaskId = remotePort.calls.single().taskId
        val header = "【任务 ${runTaskId.take(8)} · 在吗】"
        val text = assistantText()
        assertTrue("回复正文应以归属头开头，实际：$text", text.startsWith(header))
        assertTrue(text.contains("在的，有什么可以帮你？"))
        // 存库消息与任务结果带同一归属头。
        val sessionId = viewModel.currentSessionId()!!
        val stored = conversations.listMessages(sessionId)
            .first { it.role == "assistant" }.content
        assertTrue("存库消息应带归属头，实际：$stored", stored.startsWith(header))
        val row = taskStore.getTask(runTaskId)
        assertNotNull(row)
        assertEquals("completed", row?.state)
        assertTrue("任务结果应带归属头，实际：${row?.result}", row?.result?.startsWith(header) == true)
    }

    @Test
    fun `cold start shows waiting progress before the first reply`() {
        // 冷启动约 43 秒无输出，界面上什么都没有，用户以为死了才
        // 取消。无输出期间必须有可见的进度状态（“正在启动 … 已等待
        // N 秒”），首条远端事件到就停。
        val slow = SlowRemotePort(firstEventDelayMs = 3000)
        viewModel.attachRemoteChat(
            port = slow,
            controlSender = null,
            targetResolver = { agent -> if (agent.agentId == "example-agent") remoteTarget else null },
            isRemoteAgent = { agent -> agent.agentId == "example-agent" },
        )
        viewModel.switchSession("session-grok", "Example Agent", grokAgent())
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue("无输出期间轮次应在跑", viewModel.isRunning())
        var statusNote = ""
        val deadline = System.currentTimeMillis() + 2500
        while (System.currentTimeMillis() < deadline) {
            statusNote = viewModel.stateNow().turns.singleOrNull()?.statusNote.orEmpty()
            if (statusNote.contains("正在启动")) break
            Thread.sleep(100)
        }
        assertTrue("冷启动应显示正在启动，实际：$statusNote", statusNote.contains("正在启动"))
        assertTrue("冷启动应显示已等待秒数，实际：$statusNote", statusNote.contains("已等待"))
        assertTrue(awaitIdle())
        assertTrue(assistantText().contains("在的，有什么可以帮你？"))
    }

    @Test
    fun `force new conversation escapes a stuck remote turn`() {
        // 停止也不管用时，“强制开新对话”必须能清掉卡死的旧轮次、
        // 开新对话、继续发消息。
        val stuck = StuckRemotePort()
        attachStuckTurn(stuck, null)

        assertTrue("强制开新对话应成功", viewModel.forceNewConversation())
        assertFalse("强制后不应再显示运行中", viewModel.isRunning())
        assertTrue(viewModel.stateNow().turns.isEmpty())
        // 新对话里能继续发消息（同一 Agent，换一个会回话的端口）。
        viewModel.attachRemoteChat(
            port = remotePort,
            controlSender = null,
            targetResolver = { agent -> if (agent.agentId == "example-agent") remoteTarget else null },
            isRemoteAgent = { agent -> agent.agentId == "example-agent" },
        )
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent(), fresh = true))
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        assertTrue(assistantText().contains("在的，有什么可以帮你？"))
    }
}
