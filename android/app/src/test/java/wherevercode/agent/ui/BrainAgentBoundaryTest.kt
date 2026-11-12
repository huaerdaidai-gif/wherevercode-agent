package wherevercode.agent.ui

import android.content.Context
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import wherevercode.agent.brain.Message
import wherevercode.agent.brain.ModelInfo
import wherevercode.agent.brain.SharedPreferencesBrainConfigStore
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
import wherevercode.agent.core.task.TaskExecutionResult
import wherevercode.agent.core.task.TaskExecutor
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStepEvent
import wherevercode.agent.core.workspace.FileWorkspaceAdapter
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.project.DispatchFolderSource
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.brain.ToolRegistry

/**
 * 大脑/Agent 边界（maintainer 2026-11-12 定稿，一个对话、一个说话者）：
 * Agent 对话里只有那个 Agent 说话，大脑只在幕后派活；想跟大脑说
 * 就开大脑对话。
 *
 * 覆盖：
 * - 大脑与 Agent 身份区分（大脑轮次标大脑名，Agent 轮次标 Agent 名）
 * - 任务型消息只走派活、不重复发给 Agent（一条消息只走一条路）
 * - 大脑派活只出系统行、不以说话者身份开口
 * - Agent 回复带任务归属（轮次挂 taskAttribution，快照/Room 恢复不丢）
 * - 用户能控制谈话对象（顶栏对象唯一说话；直连开关关了大脑真的不插手）
 *
 * 假远端只冒充“远端回了什么话”，不冒充真实 VPS；断言的是路由与
 * 身份，不冒充真实推理。真机联调仍标未验证，等协调员验收。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BrainAgentBoundaryTest {

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

    private lateinit var tmpDir: File
    private lateinit var configStore: SharedPreferencesBrainConfigStore
    private lateinit var secrets: MemorySecrets
    private lateinit var siwcAdapter: ScriptedAdapter
    private lateinit var viewModel: ChatViewModel
    private lateinit var conversations: ConversationStore
    private lateinit var remotePort: ScriptedRemotePort
    private lateinit var taskStore: FakeTaskStore
    private lateinit var scheduler: TaskScheduler
    private lateinit var remoteTarget: RemoteTarget

    /** 远端设备 Agent：Mac/VPS 上的 Codex/Example Agent 这类（deviceId 非空）。 */
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

    /** 脑后 Agent：本身就是大脑在说话（deviceId 为空、走模型 API）。 */
    private fun brainBackedAgent() = AgentRecord(
        agentId = "test-brain-agent",
        displayName = "测试大脑 Agent",
        // 脑后 Agent 是模型 API 构成的云端服务（AgentKind.CLOUD），不是
        // 另一台设备上的 Agent——误写 DEVICE 会让 AgentChatRouter 把它
        // 当成未接入的远端设备 Agent 拒收（NotRoutable），send 被拒。
        kind = AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = BrainKind.MODEL_API,
        adapterId = "siwc-chatgpt",
        deviceId = null,
        deviceFolder = null,
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
        tmpDir = Files.createTempDirectory("brain-boundary-test").toFile()
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
        remoteTarget = RemoteTarget(
            peer = DiscoveredPeer(
                deviceId = "remote-host",
                displayName = "远程设备",
                host = "192.0.2.10",
                port = 18743,
                fingerprintSha256 = "ab".repeat(32),
                capabilities = listOf("hand"),
            ),
            agentId = "example-agent",
            folder = "/workspace",
            folderSource = DispatchFolderSource.AGENT_FALLBACK,
        )
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

    /** 派活任务行（sessionId 为 null）；轮次镜像带 sessionId，不算派活。 */
    private fun dispatchedTasks(): List<TaskRecord> =
        taskStore.listTasks(null, null).filter { it.sessionId == null }

    /** 把唯一一条派活任务标完成，让任务观察循环尽快收尾。 */
    private fun completeDispatchedTask(result: String) {
        val task = dispatchedTasks().single()
        taskStore.updateTask(task.taskId, mapOf("state" to "completed", "result" to result), 1, 1, 1)
    }

    // ---- 身份：大脑与 Agent 分得清 ----

    @Test
    fun `brain turn in a brain-backed agent conversation is labeled with the brain name`() {
        assertTrue(viewModel.switchSession("session-brain-agent", "测试大脑 Agent", brainBackedAgent()))
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        val turns = viewModel.stateNow().turns
        assertEquals(1, turns.size)
        // 抬头是大脑名（ChatGPT），不是 Agent 的显示名——大脑不再借 Agent 的名开口。
        assertEquals("ChatGPT", turns[0].speaker)
        assertTrue(turns[0].speakerIsBrain)
        assertTrue(turns[0].assistantText.contains("SIWC-ANSWER"))
    }

    @Test
    fun `remote agent turn is labeled with the agent name and is not a brain turn`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        val turns = viewModel.stateNow().turns
        assertEquals(1, turns.size)
        assertEquals("Example Agent", turns[0].speaker)
        assertFalse(turns[0].speakerIsBrain)
        assertTrue(turns[0].assistantText.contains("在的，有什么可以帮你？"))
        assertEquals(0, siwcAdapter.seenRequests.size)
    }

    // ---- 路由：一条消息只走一条路（大脑派活开时） ----
    //
    // 修订（maintainer 2026-11-12）：Agent 对话默认直连（大脑关闭），任务型
    // 指令默认直发 Agent；下面三条测的是用户显式关掉直连、改走大脑
    // 幕后派活时的派活路径（建派活任务行、走泵、进度回填）。

    @Test
    fun `task instruction dispatches when brain dispatch is turned on`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        // 显式关掉直连，改走大脑幕后派活。
        assertTrue(viewModel.setDirectAgentMode(false))
        assertFalse(viewModel.isDirectAgentMode())

        assertTrue(viewModel.send("新建一个文件 test.txt").accepted)

        // 只建了一条派活任务行（sessionId 为 null 的才是派活）。
        assertEquals(1, dispatchedTasks().size)
        // 没走远端直连轮次：消息只进了派活通道，没有又发给 Agent 当聊天。
        assertEquals(0, remotePort.calls.size)
        // 大脑没被当成说话者调起。
        assertEquals(0, siwcAdapter.seenRequests.size)
        // 派活确认是状态行，不是说话轮次。
        val turn = viewModel.stateNow().turns.single()
        assertTrue(turn.statusNote?.contains("任务已创建") == true)
        assertEquals("Example Agent", turn.speaker)
        assertFalse(turn.speakerIsBrain)

        completeDispatchedTask("文件已创建")
        assertTrue(awaitIdle())
    }

    @Test
    fun `dispatch confirmation is a status line not a brain speaker turn`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        assertTrue(viewModel.setDirectAgentMode(false))

        assertTrue(viewModel.send("新建一个文件 test.txt").accepted)

        val turns = viewModel.stateNow().turns
        // 全对话没有一条大脑说话轮次——大脑只在幕后派活。
        assertTrue(turns.none { it.speakerIsBrain })
        // 派活确认只在状态行里，不占说话者位置。
        assertTrue(turns.single().statusNote?.contains("任务已创建") == true)

        completeDispatchedTask("文件已创建")
        assertTrue(awaitIdle())
    }

    @Test
    fun `task turn carries task attribution`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        assertTrue(viewModel.setDirectAgentMode(false))

        assertTrue(viewModel.send("新建一个文件 test.txt").accepted)

        val task = dispatchedTasks().single()
        val turn = viewModel.stateNow().turns.single()
        assertNotNull(turn.taskAttribution)
        assertEquals(task.taskId, turn.taskAttribution!!.taskId)
        assertTrue(turn.taskAttribution!!.taskTitle.contains("新建一个文件"))

        completeDispatchedTask("文件已创建")
        assertTrue(awaitIdle())
        // 结果回填后归属还在。
        assertEquals(task.taskId, viewModel.stateNow().turns.single().taskAttribution!!.taskId)
    }

    // ---- 直连（大脑关闭）：消息直达 Agent，活进任务系统 ----
    //
    // 修订（maintainer 2026-11-12）：Agent 对话默认直连。消息直发 Agent、
    // 大脑不拦截、不派活、不插话；活由轮次镜像记进任务系统——镜像是
    // 真的任务行（带 sessionId、按该 Agent 认领、终态写回），任务库
    // 里自成一张卡，轮次挂任务归属。直达的活不是表外活。

    @Test
    fun `direct mode by default sends a task instruction straight to the agent and the work enters the task system`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        // 默认就是直连（大脑关闭），不用开开关。
        assertTrue(viewModel.isDirectAgentMode())

        assertTrue(viewModel.send("新建一个文件 test.txt").accepted)
        assertTrue(awaitIdle())

        // 消息直发 Agent（远端轮次），不建派活任务行。
        assertEquals(1, remotePort.calls.size)
        assertEquals("新建一个文件 test.txt", remotePort.calls[0].text)
        assertEquals(0, dispatchedTasks().size)
        // 大脑没被调起。
        assertEquals(0, siwcAdapter.seenRequests.size)
        // 状态行写明这次走的是直连。
        val turn = viewModel.stateNow().turns.single()
        assertTrue(turn.routeLabel?.contains("直连") == true)
        assertEquals("Example Agent", turn.speaker)
        assertFalse(turn.speakerIsBrain)

        // 活进任务系统：轮次镜像是真的任务行（带 sessionId），按该
        // Agent 认领（assignedAgentId = Example Agent），终态已写回完成。
        val mirrors = taskStore.listTasks(null, null).filter { it.sessionId != null }
        assertEquals(1, mirrors.size)
        assertEquals("example-agent", mirrors[0].assignedAgentId)
        assertEquals("completed", mirrors[0].state)
        assertTrue(mirrors[0].result?.contains("在的，有什么可以帮你？") == true)
        // 轮次挂任务归属：Agent 的回复归到这条镜像任务上。
        assertNotNull(turn.taskAttribution)
        assertEquals(mirrors[0].taskId, turn.taskAttribution!!.taskId)
    }

    @Test
    fun `direct mode toggle only works in a remote device agent conversation`() {
        // 大脑对话里没有可直连的 Agent：开关无效。
        assertTrue(viewModel.newConversation())
        assertFalse(viewModel.setDirectAgentMode(true))
        assertFalse(viewModel.isDirectAgentMode())

        // 远端设备 Agent 对话里默认直连开；开关可关、可再开。
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        assertTrue(viewModel.isDirectAgentMode())
        assertTrue(viewModel.setDirectAgentMode(false))
        assertFalse(viewModel.isDirectAgentMode())
        assertTrue(viewModel.setDirectAgentMode(true))
        assertTrue(viewModel.isDirectAgentMode())
    }

    @Test
    fun `direct mode choice is remembered per conversation`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        // 默认直连开；显式关掉后记下来。
        assertTrue(viewModel.isDirectAgentMode())
        assertTrue(viewModel.setDirectAgentMode(false))
        assertFalse(viewModel.isDirectAgentMode())

        // 恢复显式关：仍关（不回默认）。
        viewModel.restoreDirectAgentSessions(mapOf("session-grok" to false))
        assertFalse(viewModel.isDirectAgentMode())
        // 恢复显式开：开。
        viewModel.restoreDirectAgentSessions(mapOf("session-grok" to true))
        assertTrue(viewModel.isDirectAgentMode())
        // 恢复空（没选过）：回默认直连开。
        viewModel.restoreDirectAgentSessions(emptyMap())
        assertTrue(viewModel.isDirectAgentMode())
    }

    // ---- 谈话对象控制：顶栏写着谁，就只跟谁说 ----

    @Test
    fun `each conversation only carries its own partner as speaker`() {
        // Agent 对话：只有 Example Agent 说话。
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        val grokTurns = viewModel.stateNow().turns
        assertTrue(grokTurns.all { it.speaker.isBlank() || it.speaker == "Example Agent" })
        assertTrue(grokTurns.none { it.speakerIsBrain && it.speaker.isNotBlank() })

        // 开大脑对话：只有大脑说话。
        assertTrue(viewModel.newConversation())
        assertNull(viewModel.selectedAgent)
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        val brainTurns = viewModel.stateNow().turns
        assertEquals(1, brainTurns.size)
        assertEquals("ChatGPT", brainTurns[0].speaker)
        assertTrue(brainTurns[0].speakerIsBrain)
    }

    // ---- 恢复：说话者与归属不丢 ----

    @Test
    fun `snapshot round trip preserves speaker and task attribution`() {
        val turn = ChatTurnUi(
            id = "turn-1",
            userText = "新建一个文件 test.txt",
            assistantText = "文件已创建",
            reasoning = "",
            protocolRedacted = false,
            toolCalls = emptyList(),
            notes = emptyList(),
            status = TurnStatus.DONE,
            error = null,
            revision = 1,
            speaker = "Example Agent",
            speakerIsBrain = false,
            taskAttribution = TurnTaskAttribution(taskId = "task-123", taskTitle = "新建一个文件 test.txt"),
            routeLabel = "直连 Example Agent（大脑已关闭）",
        )
        val decoded = ChatHistorySnapshot.decode(ChatHistorySnapshot.encode(listOf(turn)))
        assertEquals(1, decoded.size)
        assertEquals("Example Agent", decoded[0].speaker)
        assertFalse(decoded[0].speakerIsBrain)
        assertEquals("task-123", decoded[0].taskId)
        assertEquals("新建一个文件 test.txt", decoded[0].taskTitle)
        assertEquals("直连 Example Agent（大脑已关闭）", decoded[0].routeLabel)
    }

    @Test
    fun `snapshot decode tolerates old snapshots without speaker fields`() {
        val decoded = ChatHistorySnapshot.decode(
            """[{"user":"hi","assistant":"hello","status":"DONE","error":null}]""",
        )
        assertEquals(1, decoded.size)
        assertEquals("", decoded[0].speaker)
        assertTrue(decoded[0].speakerIsBrain)
        assertNull(decoded[0].routeLabel)
        assertNull(decoded[0].taskId)
    }

    @Test
    fun `room restore carries the task id`() {
        val messages = listOf(
            Message(
                id = "m1",
                conversationId = "s1",
                role = "user",
                content = "新建一个文件 test.txt",
                source = "user",
                createdAtEpochSeconds = 1,
            ),
            Message(
                id = "m2",
                conversationId = "s1",
                role = "assistant",
                content = "文件已创建",
                source = "example-agent",
                createdAtEpochSeconds = 2,
                taskId = "task-123",
            ),
        )
        val turns = ChatSessionRestore.messagesToTurns(messages)
        assertEquals(1, turns.size)
        assertEquals("task-123", turns[0].taskId)
    }

    @Test
    fun `restored turn with a blank speaker takes the conversation partner`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        viewModel.restoreSessionTurns(
            listOf(
                SavedTurn(
                    userText = "在吗",
                    assistantText = "在的",
                    status = TurnStatus.DONE.name,
                    error = null,
                    speaker = "",
                ),
            ),
        )
        val turns = viewModel.stateNow().turns
        assertEquals(1, turns.size)
        // 旧轮次没存说话者，按对话方补——不回落到“AGENT”。
        assertEquals("Example Agent", turns[0].speaker)
        assertFalse(turns[0].speakerIsBrain)
    }

    // ---- 自然对话与任务的边界（maintainer 2026-11-12） ----
    //
    // 同一句自然对话可能是闲聊也可能是任务指令；判错了代价很大——
    // 闲聊当成任务会乱干活，任务当成闲聊会没干活。分类（TaskIntent
    // Classifier）按意图判：问候/纯问句是闲聊，含动作词的指令是任务，
    // 含动作词的纯问句 / 有请求词无动作词是不明。拿不准时按闲聊处理
    // （不乱干活），但必须标明“意图不明”，用户能用显式标任务纠正。
    // 以下四条在派活模式（直连关）与默认直连模式下分别覆盖。

    @Test
    fun `chat does not create a dispatch task`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        assertTrue(viewModel.setDirectAgentMode(false))

        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())

        // 闲聊只走一次远端轮次，不建派活任务行。
        assertEquals(0, dispatchedTasks().size)
        assertEquals(1, remotePort.calls.size)
        assertEquals("在吗", remotePort.calls[0].text)
        assertEquals(0, siwcAdapter.seenRequests.size)
        // 没被标为任务：持久路线标注为空（不是悄悄建行）。
        val turn = viewModel.stateNow().turns.single()
        assertNull(turn.routeLabel)
    }

    @Test
    fun `task instruction creates a dispatch task and is marked as a task`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        assertTrue(viewModel.setDirectAgentMode(false))

        assertTrue(viewModel.send("新建一个文件 test.txt").accepted)

        // 任务指令建派活任务行，不重复发给 Agent 当聊天。
        assertEquals(1, dispatchedTasks().size)
        assertEquals(0, remotePort.calls.size)
        assertEquals(0, siwcAdapter.seenRequests.size)
        // 标明“已识别为任务”，不是悄悄建行。
        val turn = viewModel.stateNow().turns.single()
        assertTrue(turn.statusNote?.contains("已识别为任务") == true)

        completeDispatchedTask("文件已创建")
        assertTrue(awaitIdle())
    }

    @Test
    fun `unclear intent is marked and can be corrected by explicitly marking it a task`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        assertTrue(viewModel.setDirectAgentMode(false))

        // “这个文件是干嘛的？”含动作词（文件）但纯问句：意图不明。
        assertTrue(viewModel.send("这个文件是干嘛的？").accepted)
        assertTrue(awaitIdle())

        // 拿不准按闲聊处理：不建派活任务行，走一次远端轮次；但标明。
        assertEquals(0, dispatchedTasks().size)
        assertEquals(1, remotePort.calls.size)
        val firstTurn = viewModel.stateNow().turns.single()
        assertTrue(firstTurn.routeLabel?.contains("意图不明") == true)

        // 纠正：同一句标为任务重发，一定建派活任务行。
        assertTrue(viewModel.send("这个文件是干嘛的？", forceTask = true).accepted)
        assertEquals(1, dispatchedTasks().size)
        // 纠正的这条没再发给 Agent 当聊天。
        assertEquals(1, remotePort.calls.size)
        val secondTurn = viewModel.stateNow().turns[1]
        assertTrue(secondTurn.statusNote?.contains("已识别为任务") == true)

        completeDispatchedTask("已说明文件用途")
        assertTrue(awaitIdle())
    }

    @Test
    fun `explicitly marked task always creates a dispatch task even in direct mode`() {
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        // 默认直连（大脑关闭）下，“在吗”本来只是闲聊直发 Agent。
        assertTrue(viewModel.isDirectAgentMode())

        // 用户显式标为任务：一定建派活任务行，不受分类与直连开关影响。
        assertTrue(viewModel.send("在吗", forceTask = true).accepted)

        assertEquals(1, dispatchedTasks().size)
        assertEquals(0, remotePort.calls.size)
        assertEquals(0, siwcAdapter.seenRequests.size)
        val turn = viewModel.stateNow().turns.single()
        assertTrue(turn.statusNote?.contains("已识别为任务") == true)

        completeDispatchedTask("已按任务处理")
        assertTrue(awaitIdle())
    }

    @Test
    fun `intent classifier separates chat task and uncertain`() {
        // 闲聊：问候、纯问句。
        assertEquals(TaskIntent.CHAT, TaskIntentClassifier.classify("在吗"))
        assertEquals(TaskIntent.CHAT, TaskIntentClassifier.classify("你好"))
        assertEquals(TaskIntent.CHAT, TaskIntentClassifier.classify("今天天气怎么样？"))
        // 任务：含动作词的指令；请求词 + 动作词的问句也是任务。
        assertEquals(TaskIntent.TASK, TaskIntentClassifier.classify("新建一个文件 test.txt"))
        assertEquals(TaskIntent.TASK, TaskIntentClassifier.classify("能不能帮我新建一个文件？"))
        assertEquals(TaskIntent.TASK, TaskIntentClassifier.classify("请帮我运行测试"))
        // 不明：含动作词的纯问句（问的是东西本身）；有请求词但无动作词。
        assertEquals(TaskIntent.UNCERTAIN, TaskIntentClassifier.classify("这个文件是干嘛的？"))
        assertEquals(TaskIntent.UNCERTAIN, TaskIntentClassifier.classify("帮我看一下"))
    }

    // ---- 三个对话模式（maintainer 2026-11-12 06:46/06:47/06:48） ----
    //
    // 模式是“跟谁说话”的区分，不是老项目‘商量/动手’那种分法：
    // ① 大脑对话（跟大脑说，像 Codex/Claude App），② 单兵对话
    // （一对一跟一个 Agent，不分同设备/远程设备），③ 团队对话/
    // 频道（跟所有设备、所有 Agent）。每个模式明确标出、可切换。

    /** 同设备 Agent（本机 Codex）：deviceId 为空，走本机 CLI。 */
    private fun localCodexAgent() = AgentRecord(
        agentId = "codex",
        displayName = "Codex",
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.AGENT_RUNTIME,
        adapterId = "codex",
        binary = "codex",
        deviceId = null,
        deviceFolder = null,
    )

    @Test
    fun `three modes each have a clear identifier`() {
        // 大脑对话：没选 Agent。
        assertEquals(ChatMode.BRAIN, ChatMode.forConversation(null))
        assertEquals("大脑对话", ChatMode.BRAIN.label)
        // 单兵对话：选了一个 Agent——远程设备（VPS 的 Example Agent）。
        assertEquals(ChatMode.SINGLE_AGENT, ChatMode.forConversation(grokAgent()))
        assertEquals("单兵对话", ChatMode.SINGLE_AGENT.label)
        // 单兵对话不分同设备/远程设备：本机 Codex 也是同一个模式。
        assertEquals(ChatMode.SINGLE_AGENT, ChatMode.forConversation(localCodexAgent()))
        // 团队对话/频道：跟所有设备、所有 Agent（频道屏的模式）。
        assertEquals("团队对话", ChatMode.TEAM.label)
        assertTrue(ChatMode.TEAM.description.contains("所有设备"))
        assertTrue(ChatMode.TEAM.description.contains("所有 Agent"))
        // 三个模式名互不相同，用户一眼分得清。
        val labels = ChatMode.values().map { it.label }.toSet()
        assertEquals(3, labels.size)
    }

    @Test
    fun `switching modes changes the speaker by the mode rule`() {
        // 大脑对话：只有大脑说话（像 Codex/Claude App，直接跟大脑聊）。
        assertTrue(viewModel.newConversation())
        assertEquals(ChatMode.BRAIN, ChatMode.forConversation(viewModel.selectedAgent))
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        val brainTurns = viewModel.stateNow().turns
        assertEquals(1, brainTurns.size)
        assertEquals("ChatGPT", brainTurns[0].speaker)
        assertTrue(brainTurns[0].speakerIsBrain)

        // 切到单兵对话（远程设备 Agent）：只有那个 Agent 说话，大脑不插话。
        assertTrue(viewModel.switchSession("session-grok", "Example Agent", grokAgent()))
        assertEquals(ChatMode.SINGLE_AGENT, ChatMode.forConversation(viewModel.selectedAgent))
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        val grokTurns = viewModel.stateNow().turns
        assertTrue(grokTurns.all { it.speaker.isBlank() || it.speaker == "Example Agent" })
        assertTrue(grokTurns.none { it.speakerIsBrain && it.speaker.isNotBlank() })

        // 切回大脑对话：又只有大脑说话。
        assertTrue(viewModel.newConversation())
        assertEquals(ChatMode.BRAIN, ChatMode.forConversation(viewModel.selectedAgent))
        assertTrue(viewModel.send("在吗").accepted)
        assertTrue(awaitIdle())
        val brainTurns2 = viewModel.stateNow().turns
        assertEquals(1, brainTurns2.size)
        assertEquals("ChatGPT", brainTurns2[0].speaker)
        assertTrue(brainTurns2[0].speakerIsBrain)
    }
}
