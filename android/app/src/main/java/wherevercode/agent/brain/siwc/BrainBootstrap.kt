package wherevercode.agent.brain.siwc

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.flow.collect
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.AgentRunner
import wherevercode.agent.RuntimeEnv
import wherevercode.agent.brain.BrainAdapterRegistry
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigStore
import wherevercode.agent.brain.BrainConfigValidator
import wherevercode.agent.brain.LOGIN_ONLY_EXTRA_KEY
import wherevercode.agent.brain.ApprovalGate
import wherevercode.agent.brain.BrainEngine
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.EngineEvent
import wherevercode.agent.brain.EventLog
import wherevercode.agent.brain.FailoverPolicy
import wherevercode.agent.brain.cli.CliMembershipBrainAdapter
import wherevercode.agent.brain.cli.CliRunner
import wherevercode.agent.brain.devicecode.DeviceCodeBrainAdapter
import wherevercode.agent.brain.devicecode.DeviceCodeConfigs
import wherevercode.agent.brain.devicecode.DeviceCodeManager
import wherevercode.agent.brain.devicecode.DeviceCodeTokenStore
import wherevercode.agent.brain.devicecode.KimiDeviceHeaders
import wherevercode.agent.brain.devicecode.LoginOnlyBrainAdapter
import wherevercode.agent.brain.oauth.UrlConnectionOAuthHttpTransport
import wherevercode.agent.brain.oauth.VendorOAuthConfigs
import wherevercode.agent.brain.cloudflare.CloudflareBrainAdapter
import wherevercode.agent.brain.cloudflare.CloudflareM2BrainAdapter
import wherevercode.agent.brain.cloudflare.KeystoreCloudflareCredentialStore
import wherevercode.agent.brain.claude.ClaudeBrainAdapter
import wherevercode.agent.brain.claude.KeystoreClaudeCredentialStore
import wherevercode.agent.brain.deepseek.DeepSeekBrainAdapter
import wherevercode.agent.brain.deepseek.DeepSeekApiClient
import wherevercode.agent.brain.deepseek.KeystoreDeepSeekCredentialStore
import wherevercode.agent.brain.grok.GrokBrainAdapter
import wherevercode.agent.brain.grok.KeystoreGrokCredentialStore
import wherevercode.agent.brain.generic.GenericOpenAiBrainAdapter
import wherevercode.agent.brain.generic.KeystoreGenericOpenAiCredentialStore
import wherevercode.agent.brain.muse.KeystoreMuseCredentialStore
import wherevercode.agent.brain.muse.MuseBrainAdapter
import wherevercode.agent.core.agent.AgentLoginState
import wherevercode.agent.brain.ContextManager
import wherevercode.agent.brain.ConversationStore
import wherevercode.agent.brain.DenyApprovalGate
import wherevercode.agent.brain.InMemoryConversationStore
import wherevercode.agent.brain.InMemoryEventLog
import wherevercode.agent.brain.InMemoryMemoryStore
import wherevercode.agent.brain.InMemorySkillStore
import wherevercode.agent.brain.SkillRefiner
import wherevercode.agent.brain.SkillStore
import wherevercode.agent.brain.RoomConversationStore
import wherevercode.agent.brain.SharedPreferencesBrainConfigStore
import wherevercode.agent.brain.ToolRegistry
import wherevercode.agent.brain.TurnController
import wherevercode.agent.core.autonomy.AutonomyGate
import wherevercode.agent.core.autonomy.AutonomyStore
import wherevercode.agent.core.autonomy.DefaultAutonomyGate
import wherevercode.agent.core.control.ControlPlane
import wherevercode.agent.core.control.ControlAction
import wherevercode.agent.core.control.RoomControlAuditLog
import wherevercode.agent.core.control.RoomControlStore
import wherevercode.agent.core.mcp.RemoteControlSender
import wherevercode.agent.core.device.AndroidAppAdapter
import wherevercode.agent.core.extension.ExtensionKind
import wherevercode.agent.core.extension.ExtensionRegistry
import wherevercode.agent.core.extension.ExtensionToolBridge
import wherevercode.agent.core.extension.InMemoryExtensionStore
import wherevercode.agent.core.extension.SharedPreferencesExtensionStore
import wherevercode.agent.core.extension.mcp.McpRoot
import wherevercode.agent.core.extension.mcp.McpServerManager
import wherevercode.agent.core.extension.mcp.StaticMcpRootsProvider
import wherevercode.agent.core.extension.plugin.LocalUtilsPlugin
import wherevercode.agent.core.extension.plugin.PluginRegistry
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.project.GitHostProjectPort
import wherevercode.agent.core.project.KeystoreGitHostCredentialStore
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.project.ProjectLocation
import wherevercode.agent.core.python.PreferEmbeddedPythonRuntime
import wherevercode.agent.core.python.ProcessPythonRuntime
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.security.InMemoryPairingTrustStore
import wherevercode.agent.core.security.PairingTrustStore
import wherevercode.agent.core.security.RoomAuthorizationStore
import wherevercode.agent.core.security.RoomPairingStore
import wherevercode.agent.core.security.RoomRevocationStore
import wherevercode.agent.core.security.SecretRedactor
import wherevercode.agent.core.session.ControlInstructionRouter
import wherevercode.agent.core.session.SessionLedger
import wherevercode.agent.core.session.SessionLedgerImpl
import wherevercode.agent.core.task.BrainEnginePipelineAgentCaller
import wherevercode.agent.core.task.CapabilityCardStore
import wherevercode.agent.core.task.DeliveryCardStore
import wherevercode.agent.core.task.InMemoryDeliveryCardStore
import wherevercode.agent.core.task.CompositePipelineAgentCaller
import wherevercode.agent.core.task.EvalLogRecorder
import wherevercode.agent.core.task.EvalLogStore
import wherevercode.agent.core.task.InMemoryCapabilityCardStore
import wherevercode.agent.core.task.InMemoryEvalLogStore
import wherevercode.agent.core.task.InMemoryPipelineStore
import wherevercode.agent.core.task.InMemoryRoutingDecisionLog
import wherevercode.agent.core.task.InMemoryRouterSettings
import wherevercode.agent.core.task.InMemoryTaskEventStore
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.task.LocalTaskRunner
import wherevercode.agent.core.task.PipelineAgentCaller
import wherevercode.agent.core.task.PipelineOrchestrator
import wherevercode.agent.core.task.PipelineReviewInputs
import wherevercode.agent.core.task.PipelineRunner
import wherevercode.agent.core.task.PipelineRunnerHookImpl
import wherevercode.agent.core.task.PipelineStore
import wherevercode.agent.core.task.TaskStepArtifacts
import wherevercode.agent.core.task.RoomPipelineStore
import wherevercode.agent.core.team.FanoutStore
import wherevercode.agent.core.team.InMemoryFanoutStore
import wherevercode.agent.core.team.RoomFanoutStore
import wherevercode.agent.core.task.RoomSkillStore
import wherevercode.agent.core.task.RoomTaskEventStore
import wherevercode.agent.core.task.RoomTaskStepStore
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.RoutingDecisionLog
import wherevercode.agent.core.task.RouterSettings
import wherevercode.agent.core.task.SharedPreferencesCapabilityCardStore
import wherevercode.agent.core.task.SharedPreferencesEvalLogStore
import wherevercode.agent.core.task.SharedPreferencesRoutingDecisionLog
import wherevercode.agent.core.task.SharedPreferencesRouterSettings
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskDatabaseProvider
import wherevercode.agent.core.task.TaskEventStore
import wherevercode.agent.core.task.TaskExecutionResult
import wherevercode.agent.core.task.TaskExecutor
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStepEvent
import wherevercode.agent.core.task.TaskStepStore
import wherevercode.agent.core.task.TaskStore
import wherevercode.agent.core.workspace.FileWorkspaceAdapter
import wherevercode.agent.python.ChaquopyPythonRuntime
import java.io.File

/**
 * App-level wiring for the M2 [BrainEngine] plus the SIWC brain (M3)
 * and the Cloudflare Workers AI brain (free tier).
 *
 * Owns the one-time assembly of the engine's dependencies and the
 * registration of the `siwc-chatgpt` and `cloudflare-workers-ai` brains.
 * The engine itself is unchanged (M2): this file only constructs it and
 * plugs the adapters into the registry it already reads.
 *
 * [build] is idempotent: calling it twice re-registers the same
 * adapter id / config id and returns a fresh engine over the same
 * registry. Callers should cache the returned components for the
 * lifetime of the activity/process.
 *
 * No network, no account, no model call happens here: registration
 * only makes the brain *available*; a real inference requires the
 * user to sign in (SiwcAuthManager) and the engine to call
 * [BrainEngine.chat].
 */
object BrainBootstrap {

    /** Brain id for the SIWC / ChatGPT brain (stable across builds). */
    const val SIWC_BRAIN_ID = "siwc-chatgpt"

    /** The authRef the bridge expects for the SIWC adapter. */
    const val SIWC_AUTH_REF = "siwc:tokens"

    /** Brain id for the Cloudflare Workers AI brain (stable across builds). */
    const val CLOUDFLARE_BRAIN_ID = "cloudflare-workers-ai"

    /** The authRef the Cloudflare adapter expects (Keystore entry for the API Token). */
    const val CLOUDFLARE_AUTH_REF = "cloudflare:token"

    /** Brain id for the configurable DeepSeek API brain (stable across builds). */
    const val DEEPSEEK_BRAIN_ID = "deepseek"

    /** The authRef the DeepSeek adapter expects (Keystore entry for the API key). */
    const val DEEPSEEK_AUTH_REF = "deepseek:api-key"

    /** Brain id for the Muse Spark brain (stable across builds). */
    const val MUSE_BRAIN_ID = "muse-spark"

    /** The authRef the Muse adapter expects (Keystore entry for the API key). */
    const val MUSE_AUTH_REF = "muse:api-key"

    /** Brain id for the configurable Grok API brain (stable across builds). */
    const val GROK_BRAIN_ID = "grok"

    /** The authRef the Grok adapter expects (Keystore entry for the API key). */
    const val GROK_AUTH_REF = "grok:api-key"

    /** Brain id for the configurable Claude API brain (stable across builds). */
    const val CLAUDE_BRAIN_ID = "claude"

    /** The authRef the Claude adapter expects (Keystore entry for the API key). */
    const val CLAUDE_AUTH_REF = "claude:api-key"

    // ---- Membership brains, device-code flow (2026-11-12) -------------
    // Qwen / Kimi / Grok membership log in through the RFC 8628
    // device-code engine (brain/devicecode/); Gemini logs in through
    // the authorization-code engine with a fixed client id (see
    // VendorOAuthConfigs.GEMINI — login-only, never an inference
    // brain). Brain ids are stable across builds. Auth refs are the
    // Keystore entries for the vendors' device-code tokens
    // ("devicecode:<vendor>"); Gemini has no authRef (no tokens are
    // ever usable for inference there).
    /** Brain id for the Qwen membership brain. */
    const val QWEN_BRAIN_ID = "qwen"

    /** The authRef the Qwen device-code token store expects. */
    const val QWEN_AUTH_REF = "devicecode:qwen"

    /** Brain id for the Kimi Code subscription brain. */
    const val KIMI_BRAIN_ID = "kimi"

    /** The authRef the Kimi device-code token store expects. */
    const val KIMI_AUTH_REF = "devicecode:kimi"

    /** Brain id for the Grok membership brain (experimental). */
    const val GROK_MEMBERSHIP_BRAIN_ID = "grok-membership"

    /** The authRef the Grok device-code token store expects. */
    const val GROK_MEMBERSHIP_AUTH_REF = "devicecode:grok-membership"

    /** Brain id for the Gemini login-only entry. */
    const val GEMINI_BRAIN_ID = "gemini"

    /** First-line brain ids that build() seeds on first run. */
    val SEEDABLE_BRAIN_IDS: Set<String> = setOf(
        SIWC_BRAIN_ID,
        CLOUDFLARE_BRAIN_ID,
        DEEPSEEK_BRAIN_ID,
        MUSE_BRAIN_ID,
        GROK_BRAIN_ID,
        CLAUDE_BRAIN_ID,
        QWEN_BRAIN_ID,
        KIMI_BRAIN_ID,
        GROK_MEMBERSHIP_BRAIN_ID,
        GEMINI_BRAIN_ID,
    )

    /**
     * The first-run seed config for a seedable brain id, or null for any
     * other id. The seed is only a first-run suggestion (model/baseUrl);
     * the credential is never seeded — the user enters their own key
     * or logs in with their own account. Single source of truth for
     * seeds: build() and [restoreSeededBrain] both use it.
     *
     * Membership seeds (Qwen / Kimi / Grok): model is blank ("not
     * chosen yet" — the user fills the vendor model name in the
     * manage dialog); baseUrl is the vendor inference endpoint the
     * membership token is sent to. Gemini's seed is a login-only
     * shell (no model, no authRef, extra login_only).
     */
    fun seedConfigFor(brainId: String): BrainConfig? = when (brainId) {
        SIWC_BRAIN_ID -> BrainConfig(
            brainId = SIWC_BRAIN_ID,
            kind = BrainKind.SUBSCRIPTION,
            adapterId = SIWC_BRAIN_ID,
            label = "ChatGPT (Sign in with ChatGPT)",
            baseUrl = null,
            model = "",
            authRef = SIWC_AUTH_REF,
        )
        CLOUDFLARE_BRAIN_ID -> BrainConfig(
            brainId = CLOUDFLARE_BRAIN_ID,
            kind = BrainKind.MODEL_API,
            adapterId = CLOUDFLARE_BRAIN_ID,
            label = "Cloudflare Workers AI",
            baseUrl = CloudflareBrainAdapter.ACCOUNTS_BASE_URL,
            model = CloudflareBrainAdapter.SUGGESTED_DEFAULT_MODEL,
            authRef = CLOUDFLARE_AUTH_REF,
            extra = mapOf(CloudflareBrainAdapter.EXTRA_ACCOUNT_ID to ""),
            fallbackBrainIds = listOf(SIWC_BRAIN_ID),
        )
        DEEPSEEK_BRAIN_ID -> BrainConfig(
            brainId = DEEPSEEK_BRAIN_ID,
            kind = BrainKind.MODEL_API,
            adapterId = DeepSeekBrainAdapter.ADAPTER_ID,
            label = "DeepSeek API",
            baseUrl = DeepSeekBrainAdapter.DEFAULT_BASE_URL,
            model = DeepSeekApiClient.SUGGESTED_MODELS.first(),
            authRef = DEEPSEEK_AUTH_REF,
            fallbackBrainIds = emptyList(),
        )
        MUSE_BRAIN_ID -> BrainConfig(
            brainId = MUSE_BRAIN_ID,
            kind = BrainKind.MODEL_API,
            adapterId = MuseBrainAdapter.ADAPTER_ID,
            label = "Muse Spark",
            baseUrl = MuseBrainAdapter.DEFAULT_BASE_URL,
            model = MuseBrainAdapter.SUGGESTED_MODEL,
            authRef = MUSE_AUTH_REF,
            fallbackBrainIds = emptyList(),
        )
        GROK_BRAIN_ID -> BrainConfig(
            brainId = GROK_BRAIN_ID,
            kind = BrainKind.MODEL_API,
            adapterId = GrokBrainAdapter.ADAPTER_ID,
            label = "Grok",
            baseUrl = GrokBrainAdapter.DEFAULT_BASE_URL,
            model = GrokBrainAdapter.SUGGESTED_MODEL,
            authRef = GROK_AUTH_REF,
            fallbackBrainIds = emptyList(),
        )
        CLAUDE_BRAIN_ID -> BrainConfig(
            brainId = CLAUDE_BRAIN_ID,
            kind = BrainKind.MODEL_API,
            adapterId = ClaudeBrainAdapter.ADAPTER_ID,
            label = "Claude API",
            baseUrl = ClaudeBrainAdapter.DEFAULT_BASE_URL,
            model = ClaudeBrainAdapter.SUGGESTED_MODEL,
            authRef = CLAUDE_AUTH_REF,
            fallbackBrainIds = emptyList(),
        )
        QWEN_BRAIN_ID -> BrainConfig(
            brainId = QWEN_BRAIN_ID,
            kind = BrainKind.SUBSCRIPTION,
            adapterId = DeviceCodeConfigs.QWEN.adapterId,
            label = DeviceCodeConfigs.QWEN.displayName,
            baseUrl = DeviceCodeConfigs.QWEN.inferenceBaseUrl,
            model = "",
            authRef = QWEN_AUTH_REF,
            fallbackBrainIds = emptyList(),
        )
        KIMI_BRAIN_ID -> BrainConfig(
            brainId = KIMI_BRAIN_ID,
            kind = BrainKind.SUBSCRIPTION,
            adapterId = DeviceCodeConfigs.KIMI.adapterId,
            label = DeviceCodeConfigs.KIMI.displayName,
            baseUrl = DeviceCodeConfigs.KIMI.inferenceBaseUrl,
            model = "",
            authRef = KIMI_AUTH_REF,
            fallbackBrainIds = emptyList(),
        )
        GROK_MEMBERSHIP_BRAIN_ID -> BrainConfig(
            brainId = GROK_MEMBERSHIP_BRAIN_ID,
            kind = BrainKind.SUBSCRIPTION,
            adapterId = DeviceCodeConfigs.GROK.adapterId,
            label = DeviceCodeConfigs.GROK.displayName,
            baseUrl = DeviceCodeConfigs.GROK.inferenceBaseUrl,
            model = "",
            authRef = GROK_MEMBERSHIP_AUTH_REF,
            fallbackBrainIds = emptyList(),
        )
        GEMINI_BRAIN_ID -> BrainConfig(
            brainId = GEMINI_BRAIN_ID,
            kind = BrainKind.SUBSCRIPTION,
            adapterId = LoginOnlyBrainAdapter.ADAPTER_ID,
            label = "Gemini（仅登录）",
            baseUrl = null,
            model = "",
            authRef = null,
            extra = mapOf(LOGIN_ONLY_EXTRA_KEY to "true"),
            fallbackBrainIds = emptyList(),
        )
        else -> null
    }

    /**
     * The config build() should register for a first-line brain, or null
     * when it must NOT be (re)created. Returns the stored config when
     * one exists (never overwritten by the seed); returns the seed on
     * first run; returns null when the user deleted this brain — the
     * tombstone in [BrainConfigStore.deletedBrainIds] blocks BOTH the
     * persistent seed AND the in-memory registerConfig, so a deleted
     * brain stays deleted across restarts AND across app updates
     * (an update replaces the APK only; the SharedPreferences file
     * holding the tombstones survives it, so the next launch reads
     * the same tombstones and still skips the seed). This is the one
     * seeding gate: every build() seeding path goes through it, and
     * no other path writes a seed config.
     */
    fun seededConfig(configStore: BrainConfigStore, brainId: String): BrainConfig? {
        configStore.getConfig(brainId)?.let { return it }
        if (brainId in configStore.deletedBrainIds()) return null
        return seedConfigFor(brainId)
    }

    /**
     * Re-add a deleted first-line brain: clear its tombstone and, if it
     * has no stored config, write the seed config back and register it.
     * Returns false when the brain id is not a seedable first-line brain.
     * The seed is the same first-run suggestion build() uses; a deleted
     * credential is NOT restored — it was wiped, and the user enters
     * their own key again (API brains) or logs in again with their own
     * account (membership brains).
     */
    fun restoreSeededBrain(configStore: BrainConfigStore, brainId: String): Boolean {
        val seed = seedConfigFor(brainId) ?: return false
        configStore.clearBrainDeleted(brainId)
        val config = configStore.getConfig(brainId) ?: seed.also { configStore.saveConfig(it) }
        BrainAdapterRegistry.registerConfig(config)
        return true
    }

    /** Brain id for the CLI membership brain (stable across builds). */
    const val CLI_BRAIN_ID = "cli-membership"

    data class BrainComponents(
        val engine: BrainEngine,
        val authManager: SiwcAuthManager,
        val tokenStore: SiwcTokenStore,
        val brainAdapter: SiwcBrainAdapter,
        val configStore: SharedPreferencesBrainConfigStore,
        val cloudflareCredentialStore: KeystoreCloudflareCredentialStore,
        val cloudflareProvider: CloudflareBrainAdapter,
        val cloudflareBridge: CloudflareM2BrainAdapter,
        val deepSeekAdapter: DeepSeekBrainAdapter,
        val deepSeekCredentialStore: KeystoreDeepSeekCredentialStore,
        val museAdapter: MuseBrainAdapter,
        val museCredentialStore: KeystoreMuseCredentialStore,

        val grokAdapter: GrokBrainAdapter,
        val grokCredentialStore: KeystoreGrokCredentialStore,
        val claudeAdapter: ClaudeBrainAdapter,
        val claudeCredentialStore: KeystoreClaudeCredentialStore,
        /**
         * Membership brains, device-code flow (2026-11-12): one login
         * manager + one Keystore token store + one inference adapter
         * per vendor, keyed by brain id ("qwen" / "kimi" /
         * "grok-membership"). Defaults are empty maps so direct
         * constructions in tests keep compiling; production always
         * passes the real maps from build(). A missing entry means
         * "not wired" — the brain reports not-configured and the UI
         * says the brain is still loading, never a fake login.
         */
        val deviceCodeManagers: Map<String, DeviceCodeManager> = emptyMap(),
        val deviceCodeTokenStores: Map<String, DeviceCodeTokenStore> = emptyMap(),
        val deviceCodeAdapters: Map<String, DeviceCodeBrainAdapter> = emptyMap(),
        /**
         * Gemini login-only adapter (2026-11-12): registered so the
         * login entry is visible and deletable; it refuses every
         * inference call honestly. Null in direct test constructions.
         */
        val geminiLoginOnlyAdapter: LoginOnlyBrainAdapter? = null,
        /** 通用 OpenAI 兼容大脑（C3 第二阶段 W-adapter）：一个 adapterId 服务全部用户自建通用大脑。 */
        val genericAdapter: GenericOpenAiBrainAdapter,
        val genericCredentialStore: KeystoreGenericOpenAiCredentialStore,
        /** 2026-11-09：本进程的写轮次/工作区适配器（诊断与恢复用）。 */
        val workspaceAdapter: FileWorkspaceAdapter,
        /** 2026-11-09：真实 Python 运行时（内嵌 Chaquopy + 系统 python3 回退）。 */
        val pythonRuntime: PreferEmbeddedPythonRuntime,
        /** 2026-11-09：暂停/停止/改目标的本轮控制态，UI 写入、引擎读取。 */
        val turnController: TurnController,
        /** UI V2 任务页：真实持久任务库。阶段 1 起聊天轮次经 TurnTaskRecorder 自动写入。 */
        val taskStore: TaskStore,
        /** App task scheduler sharing the persistent control-plane freeze gate. */
        val taskScheduler: TaskScheduler,
        /** Same control authority used by MCP dispatch, remote pumps, and UI commands. */
        val controlPlane: ControlPlane? = null,
        /** 阶段 1：每任务执行步骤（V5 C1 起 Room 持久化，重启仍在）。 */
        val taskStepStore: TaskStepStore,
        /** a-3 交付卡：任务交付卡存储（进程内，见 InMemoryDeliveryCardStore）。 */
        val deliveryStore: DeliveryCardStore = InMemoryDeliveryCardStore(),
        /**
         * P0 耐久任务引擎（2026-11-11）：任务事件日志（审计轨迹，
         * Room，同一 TaskDatabase version 8；探针失败回退内存）。
         * 控制台 LOGS 模块与任务详情页经它读状态轨迹。默认值是
         * 内存实现（只在直接构造 BrainComponents 的测试里生效；
         * 生产经 BrainBootstrap.build() 传入真实实例）。
         */
        val taskEventStore: TaskEventStore = InMemoryTaskEventStore(),
        /**
         * P2 流水线（2026-11-11）：流水线态 + verdict 历史（Room，
         * 同一 TaskDatabase version 9；探针失败回退内存）与协调器。
         * 默认值只在直接构造 BrainComponents 的测试里生效；生产经
         * BrainBootstrap.build() 传入真实实例。orchestrator 为 null
         * 时 UI 的流水线入口不可用（不崩溃、不假装可用）。
         */
        val pipelineStore: PipelineStore = InMemoryPipelineStore(),
        val pipelineOrchestrator: PipelineOrchestrator? = null,
        /**
         * P2 自动执行（2026-11-11）：规划者自动起草 + 审查者自动执行
         * 的泵（见 PipelineRunner）。默认 null（直接构造的测试里无泵）；
         * 生产经 BrainBootstrap.build() 传入真实实例，MainActivity
         * 在组件加载后 start()、onDestroy 时 stop()。为 null 时流水线
         * 只剩手动入口（用户填 plan、用户点 verdict），不崩溃。
         */
        val pipelineRunner: PipelineRunner? = null,
        /**
         * P3 只读扇出（2026-11-11）：一次扇出一行 + 一个分支一行
         * （Room，同一 TaskDatabase version 10；探针失败回退内存）。
         * 默认值只在直接构造 BrainComponents 的测试里生效；生产经
         * BrainBootstrap.build() 传入真实实例。
         */
        val fanoutStore: FanoutStore = InMemoryFanoutStore(),
        /**
         * V5 技能记忆（2026-11-11）：项目长期技能库（Room，同一
         * TaskDatabase version 7；探针失败回退内存）。ChatViewModel
         * 的 Manus 任务终态与返工入口经它打磨技能。
         */
        val skillStore: SkillStore,
        /**
         * V5 Phase B C1：配对信任记录（Room 持久化，重启仍在）。
         * MainActivity 经 MigratingPairingTrustStore 接本字段：大脑建
         * 好前配对先写内存，大脑建好后自动迁进 Room，不再丢记录
         * （V5 加设备修复，2026-11-11）。
         */
        val pairingTrustStore: PairingTrustStore,
        /**
         * 阶段 1：本地分派执行器。认领 sessionId 为 null 的 queued 任务
         * （即 dispatch_task 派出的任务），用本机大脑执行其 goal；跨设备
         * 执行不在本轮，相关任务保持 queued 可见。
         */
        val taskRunner: LocalTaskRunner,
        /** UI V2 任务页：本进程真实事件日志（已脱敏）。 */
        val eventLog: EventLog,
        /**
         * 阶段 3（A1 P1）：真实会话账本（此前引擎默认 NoOpSessionLedger，
         * 轮次只分类不落账）。会话中心与 Agent 独立会话由此读写；引擎
         * 每轮经它分类（CONTROL 短路、CHAT 落账）。
         */
        val sessionLedger: SessionLedger,
        /**
         * V5 C3 chat-bugfix：聊天历史库（Room，与任务库同一 TaskDatabase；
         * 探针失败时与步骤/配对一起回退到内存实现，见 roomStoresOrMemory）。
         * UI 经它恢复时间线与删除会话；自 id 复用修复起，会话 id 就是
         * conversation id，两边用同一个 id 读写。
         */
        val conversationStore: ConversationStore,
        /** UI V2 项目页：真实 App 工作区根目录。 */
        val workspaceDir: File,
        /**
         * 2026-11-11 MCP + 插件：App 侧统一扩展注册表（七类，与 Hand
         * 侧同一套 id 与分类）、插件注册表、MCP 服务器管理器。
         * UI 的 MCP/插件屏经这三个字段读写，不另起注册表。
         * 默认值是“空内存注册表”（无插件、无服务器）：只在直接构造
         * BrainComponents 的测试里生效；生产经 BrainBootstrap.build()
         * 传入真实实例。
         */
        val extensionRegistry: ExtensionRegistry = ExtensionRegistry(InMemoryExtensionStore()),
        val pluginRegistry: PluginRegistry = PluginRegistry(),
        val mcpServerManager: McpServerManager = McpServerManager(extensionRegistry),
        /**
         * P1 确定性自动派活（2026-11-11）：eval 日志、路由决策日志、
         * 能力卡 hello 快照。默认值是内存版（只在直接构造
         * BrainComponents 的测试里生效；生产经 BrainBootstrap.build()
         * 传入 SharedPreferences 版）。
         */
        val evalLogStore: EvalLogStore = InMemoryEvalLogStore(),
        val routingDecisionLog: RoutingDecisionLog = InMemoryRoutingDecisionLog(),
        /** 端侧小模型路由 v1：分流设置（默认关闭）。 */
        val routerSettings: RouterSettings = InMemoryRouterSettings(),
        val capabilityCardStore: CapabilityCardStore = InMemoryCapabilityCardStore(),
        /**
         * 自主度（2026-11-11）：设置页读写三档；门供 ToolRegistry 与
         * 分派调用。默认值 null = 未接线（只在直接构造 BrainComponents
         * 的测试里生效；生产经 BrainBootstrap.build() 传入真实实例）。
         */
        val autonomyStore: AutonomyStore? = null,
        val autonomyGate: AutonomyGate? = null,
    ) {
        /**
         * True when the given brain is configured and ready for a turn.
         * Wiring-layer check (the engine itself stays vendor-free):
         * SIWC = signed in; Cloudflare = token + Account ID + model present.
         * Unknown brain ids are never "configured".
         */
        fun isBrainConfigured(brainId: String): Boolean = when (brainId) {
            SIWC_BRAIN_ID -> authManager.isSignedIn()
            CLOUDFLARE_BRAIN_ID -> cloudflareProvider.isConfigured()
            DEEPSEEK_BRAIN_ID ->
                deepSeekCredentialStore.loadApiKey() != null &&
                    !configStore.getConfig(DEEPSEEK_BRAIN_ID)?.model.isNullOrBlank() &&
                    !configStore.getConfig(DEEPSEEK_BRAIN_ID)?.baseUrl.isNullOrBlank()
            MUSE_BRAIN_ID ->
                museCredentialStore.loadApiKey() != null &&
                    !configStore.getConfig(MUSE_BRAIN_ID)?.model.isNullOrBlank() &&
                    !configStore.getConfig(MUSE_BRAIN_ID)?.baseUrl.isNullOrBlank()

            GROK_BRAIN_ID ->
                grokCredentialStore.loadApiKey() != null &&
                    !configStore.getConfig(GROK_BRAIN_ID)?.model.isNullOrBlank() &&
                    !configStore.getConfig(GROK_BRAIN_ID)?.baseUrl.isNullOrBlank()
            CLAUDE_BRAIN_ID ->
                claudeCredentialStore.loadApiKey() != null &&
                    !configStore.getConfig(CLAUDE_BRAIN_ID)?.model.isNullOrBlank() &&
                    !configStore.getConfig(CLAUDE_BRAIN_ID)?.baseUrl.isNullOrBlank()
            QWEN_BRAIN_ID, KIMI_BRAIN_ID, GROK_MEMBERSHIP_BRAIN_ID -> {
                // Membership brain (device-code): usable when the
                // vendor token is live (or refreshable) AND the user
                // filled a model name. A signed-in brain with a blank
                // model is not configured — the model is never silently
                // defaulted (see the seed: model = "").
                val manager = deviceCodeManagers[brainId]
                val config = configStore.getConfig(brainId)
                manager?.isSignedIn() == true && !config?.model.isNullOrBlank()
            }
            GEMINI_BRAIN_ID -> false // Login-only: signing in never makes it a brain.
            CLI_BRAIN_ID ->
                configStore.getConfig(CLI_BRAIN_ID)?.let { config ->
                    !config.cliBinary.isNullOrBlank() &&
                        config.loginState == AgentLoginState.LOGGED_IN
                } == true
            else -> {
                // 通用 MODEL_API 大脑（C3 第二阶段 W-adapter）：brainId 由
                // 用户标签 slug 化（"model-api-<slug>"），多个通用大脑共用
                // 同一个 adapterId "model-api-generic"。已配置 = Key +
                // 模型 + 服务地址齐全（与 DeepSeek 分支同口径）；不是
                // 通用大脑的 id 一律不算已配置。本机模型例外：地址是
                // 127.0.0.1/localhost 时可无 Key（与连接测试探针同口径，
                // 见 AgentConnectionTester.testModelApi）。
                val config = configStore.getConfig(brainId)
                config != null &&
                    config.adapterId == GenericOpenAiBrainAdapter.ADAPTER_ID &&
                    (config.authRef?.let { genericCredentialStore.loadApiKey(it) } != null ||
                        BrainConfigValidator.isLocalModelUrl(config.baseUrl ?: "")) &&
                    config.model.isNotBlank() &&
                    !config.baseUrl.isNullOrBlank()
            }
        }
    }

    /**
     * V5 Phase B C1（W2）：会话、步骤、配对三家 Room 实现，或全套内存回退。
     *
     * 回退逻辑（写进代码以便后人查）：Room 的 open / 迁移是懒触发的，
     * `TaskDatabase.create()` 本身不打开文件，所以这里先跑一条廉价
     * `countAll()` 探针，强制在 build 线程（后台线程，
     * 见 MainActivity.loadBrainComponents）上打开数据库并跑完
     * MIGRATION_2_3。探针抛任何异常（迁移失败、schema 校验失败、文件
     * 损坏……）时，三家 store 一起回退到内存实现：build() 不抛错，
     * App 正常启动，聊天/步骤/配对恢复到 C1 之前“重启丢失”的旧行为。
     * 回退只覆盖这三家 store：任务、授权、会话账本仍用同一 TaskDatabase
     *（它们是 C1 之前的既有行为，不在本任务范围内）；且全程绝不
     * fallbackToDestructiveMigration——坏库宁可内存回退，不可删库。
     */
    private fun roomStoresOrMemory(
        db: TaskDatabase,
    ): Triple<ConversationStore, TaskStepStore, PairingTrustStore> {
        return try {
            db.conversationDao().countAll()
            Triple(
                RoomConversationStore(db),
                RoomTaskStepStore(db),
                RoomPairingStore(db),
            )
        } catch (_: Exception) {
            Triple(
                InMemoryConversationStore(),
                InMemoryTaskStepStore(),
                InMemoryPairingTrustStore(),
            )
        }
    }

    /**
     * V5 技能记忆（2026-11-11）：技能库探针。Room 打开失败时回退
     * 内存实现（与 roomStoresOrMemory 同款规矩：build() 绝不因此
     * 抛错，代价是技能重启丢失）。
     */
    private fun roomSkillStoreOrMemory(db: TaskDatabase): SkillStore {
        return try {
            db.skillDao().listForProject("")
            RoomSkillStore(db)
        } catch (_: Exception) {
            InMemorySkillStore()
        }
    }

    /**
     * P0 耐久任务引擎（2026-11-11）：任务事件日志探针。Room 打开失败时
     * 回退内存实现（与 roomStoresOrMemory 同款规矩：build() 绝不因此
     * 抛错，代价是事件重启丢失——审计轨迹降级，不丢任务本身）。
     */
    private fun roomTaskEventStoreOrMemory(db: TaskDatabase): TaskEventStore {
        return try {
            db.taskEventDao().countAll()
            RoomTaskEventStore(db)
        } catch (_: Exception) {
            InMemoryTaskEventStore()
        }
    }

    /**
     * P3 只读扇出探针（2026-11-11）：Room 打开失败时回退内存实现
     * （与 roomPipelineStoreOrMemory 同款规矩：build() 绝不因此
     * 抛错，代价是扇出记录重启丢失——扇出记录降级，不丢任务本身）。
     */
    private fun roomFanoutStoreOrMemory(db: TaskDatabase): FanoutStore {
        return try {
            db.fanoutRunDao().getRun("__probe__")
            RoomFanoutStore(db.fanoutRunDao(), db.fanoutBranchDao())
        } catch (_: Exception) {
            InMemoryFanoutStore()
        }
    }

    /**
     * P2 流水线探针（2026-11-11）：Room 打开失败时回退内存实现
     * （与 roomTaskEventStoreOrMemory 同款规矩：build() 绝不因此
     * 抛错，代价是流水线态重启丢失——流水线降级，不丢任务本身）。
     */
    private fun roomPipelineStoreOrMemory(db: TaskDatabase): PipelineStore {
        return try {
            db.pipelineVerdictDao().countByTask("__probe__")
            RoomPipelineStore(db)
        } catch (_: Exception) {
            InMemoryPipelineStore()
        }
    }

    fun build(
        context: Context,
        approvalGate: ApprovalGate = DenyApprovalGate,
        /**
         * 阶段 B（V5 紧急开发）：远端控制接线（可选；不传时 ControlPlane
         * 行为与原来完全一致）。由调用方（MainActivity）在 build 之前
         * 备好 resolver/sender——ControlPlane 在 build() 内创建，
         * 事后无法再接线。
         */
        remoteTargetResolver: ((String?) -> RemoteTarget?)? = null,
        remoteControlSender: RemoteControlSender? = null,
        /**
         * git 家（2026-11-11）：当前聊天项目的 git 家（由调用方
         * MainActivity 按打开的项目提供；不传时引擎看不到 git 家工具）。
         * 只在首次构建时生效（AgentGraph 缓存，见 getOrBuild）。
         */
        gitHostProjectContext: (() -> Pair<Project, ProjectLocation>?)? = null,
        /**
         * V5 跨设备开发（2026-11-12，A1/D5）：当前聊天绑定的项目 id
         * （由调用方 MainActivity 按打开的项目提供；不传时
         * dispatch_task 的任务不带项目）。只在首次构建时生效。
         */
        currentProjectId: (() -> String?)? = null,
        /**
         * P2 自动执行（2026-11-11）：远端成员调用方（可选；MainActivity
         * 用它的 remoteChatPort 现建，见 RemotePipelineAgentCaller）。
         * 不传时审查者候选只有本机大脑；传了则与本机调用方合成
         * （Composite 按 canCall 路由：远端成员走 Hand，本机大脑走
         * engine.chat）。只在首次构建时生效（AgentGraph 缓存）。
         */
        pipelineRemoteCaller: PipelineAgentCaller? = null,
        /**
         * P2 自动执行（2026-11-11）：远端审查者候选（可选；MainActivity
         * 按 agentStore 里已配对的设备 Agent 提供）。本机大脑候选由
         * build() 自己从 configStore 里取已配置的大脑。只在首次构建
         * 时生效（AgentGraph 缓存）。
         */
        pipelineRemoteReviewerCandidates: (() -> List<PipelineOrchestrator.ReviewerCandidate>)? = null,
        /**
         * MCP 通用化（2026-11-12）：MCP OAuth 授权时开浏览器的回调
         * （可选；MainActivity 传 Custom Tabs 打开，不传时配了
         * oauth=true 的服务器在 401 时直接失败，不会悄悄跳过认证）。
         * 只在首次构建时生效（AgentGraph 缓存）。
         */
        openOAuthUrl: ((String) -> Unit)? = null,
    ): BrainComponents {
        val appContext = context.applicationContext

        // ---- Tool layer (M2) -----------------------------------------
        val workspaceDir = File(appContext.filesDir, "workspace").apply { mkdirs() }
        val taskRootDir = File(appContext.filesDir, "tasks").apply { mkdirs() }
        val deviceAdapter = AndroidAppAdapter(listOf(workspaceDir))
        val workspaceAdapter = FileWorkspaceAdapter(workspaceDir, taskRootDir)
        /**
         * a-3 交付卡：本进程的交付卡存储（ToolRegistry 写轮次结算后
         * 经 onChangeSet 记入；进程内有效，重启即空，与轮次备份同寿）。
         */
        val deliveryStore: DeliveryCardStore = InMemoryDeliveryCardStore()
        // 阶段 3（A1 P1）：数据库实例只建一次——任务库、授权、撤销、
        // 会话账本共用同一 TaskDatabase（会话表与任务表同一库，见
        // TaskDatabase MIGRATION_1_2 注释）。
        val taskDb = TaskDatabaseProvider.get(appContext)
        val taskStore = RoomTaskStore(taskDb)
        // V5 Phase B C1（W2）：会话历史、任务步骤、配对记录改走 Room
        // （与任务库同一 TaskDatabase，version 3）。见 roomStoresOrMemory：
        // 探针打开失败时三者一起回退到内存实现，build() 绝不因此抛错、
        // App 绝不在启动时崩溃（代价是这三类数据重启丢失，恢复旧行为）。
        val (conversationStore, taskStepStore, pairingTrustStore) =
            roomStoresOrMemory(taskDb)
        // V5 技能记忆（2026-11-11）：项目长期技能库（Room，同一
        // TaskDatabase version 7；探针失败回退内存，见
        // roomSkillStoreOrMemory）。
        val skillStore: SkillStore = roomSkillStoreOrMemory(taskDb)
        // P0 耐久任务引擎（2026-11-11）：任务事件日志（审计轨迹，
        // Room，同一 TaskDatabase version 8；探针失败回退内存，见
        // roomTaskEventStoreOrMemory）。
        val taskEventStore: TaskEventStore = roomTaskEventStoreOrMemory(taskDb)
        // 调度与控制共用同一个持久化状态源，保证 MCP / 本机入口的
        // PAUSING、PAUSED、STOPPING 冻结在所有认领路径一致生效。
        val controlStore = RoomControlStore(taskDb)
        val taskScheduler = TaskScheduler(taskStore, AgentDiscovery(appContext), controlStore)
        // P2 流水线（2026-11-11）：流水线态 + verdict 历史（Room，
        // 同一 TaskDatabase version 9；探针失败回退内存）与协调器；
        // 本地执行器经钩子查它（plan 未批准不认领、编码完成停 review）。
        val pipelineStore: PipelineStore = roomPipelineStoreOrMemory(taskDb)
        val pipelineOrchestrator = PipelineOrchestrator(taskStore, taskScheduler, pipelineStore)
        val pipelineHook = PipelineRunnerHookImpl(pipelineOrchestrator)
        // P3 只读扇出（2026-11-11）：一次扇出一行 + 一个分支一行
        // （Room，同一 TaskDatabase version 10；探针失败回退内存）。
        val fanoutStore: FanoutStore = roomFanoutStoreOrMemory(taskDb)
        val auditLog = InMemoryAuditLog()
        // 引擎、Cloudflare 适配器与写轮次观测共用同一 EventLog（先建）。
        val eventLog = InMemoryEventLog()

        // 2026-11-09 App 重启后的遗留轮次处理：轮次状态只在内存，磁盘上
        // 的轮次目录只可能来自上一进程。只在拿到工作区锁（没有活着的
        // 写入者）时清理带标记的目录；报告写进 EventLog。
        val recovery = workspaceAdapter.recoverStaleRounds()
        eventLog.record(
            EngineEvent(
                seq = 0,
                conversationId = "",
                kind = "workspace_recovery",
                detail = "lock_available=${recovery.lockAvailable} " +
                    "removed=${recovery.removedRoundIds.size} " +
                    "kept=${recovery.keptRoundIds.size}" +
                    (recovery.error?.let { " error=$it" } ?: ""),
                epochSeconds = System.currentTimeMillis() / 1000,
            ),
        )

        // 真实 Python：优先 APK 内嵌 CPython（Chaquopy，不受 W^X 限制），
        // 不可用时回退系统 python3；两者都不可用 → python_unavailable，
        // 明确报告能力缺口，绝不模拟执行。
        val pythonRuntime = PreferEmbeddedPythonRuntime(
            embedded = ChaquopyPythonRuntime(appContext),
            fallback = ProcessPythonRuntime(),
        )

        // 2026-11-11 MCP + 插件：扩展工具栈（注册表 → 插件 → MCP
        // 服务器 → 工具桥），在 ToolRegistry 之前建好——桥要作为可选
        // 参数传进 ToolRegistry，扩展工具才能进大脑的工具调用链。
        // 启用开关以注册表为准：插件停用后大脑不再看到它的工具。
        val extensionRegistry = ExtensionRegistry(SharedPreferencesExtensionStore(appContext))
        val pluginRegistry = PluginRegistry(
            isEnabled = { pluginId ->
                extensionRegistry.get(ExtensionKind.PLUGIN, pluginId)?.enabled != false
            },
        )
        pluginRegistry.register(LocalUtilsPlugin())
        // 2026-11-12 MCP 通用化：App 侧 OAuth 接线 + Roots。
        // OAuth：注册表行配了 oauth=true 时才走 OAuth（McpOAuthWiring，
        // 回环收码 + state 校验；openOAuthUrl 不传时不开浏览器，401 即
        // 失败）。一个服务器一个 wiring、一个回环服务器，不共用。
        // Roots：只暴露 App 工作区根目录（file://…），服务端问
        // roots/list 时按它应答；不声明更多目录。
        val mcpOAuthWiringFactory: (() -> McpOAuthWiring)? = openOAuthUrl?.let { open ->
            { McpOAuthWiring(openUrl = open) }
        }
        val mcpServerManager = McpServerManager(
            extensionRegistry,
            authProviderFactory = { entry ->
                val factory = mcpOAuthWiringFactory
                if (factory == null) {
                    null
                } else {
                    mcpOAuthProviderFor(entry, entry.extra["url"].orEmpty(), factory())
                }
            },
            rootsProvider = StaticMcpRootsProvider(
                listOf(McpRoot(uri = workspaceDir.toURI().toString(), name = "workspace")),
            ),
        )
        val extensionTools = ExtensionToolBridge(pluginRegistry, mcpServerManager)

        // 自主度门（2026-11-11，maintainer 22:43 两种模式）：档位存本机
        // SharedPreferences；自动决定记 Room control_audit_log（与
        // 任务库同库，重启仍在；不新增表、不改库版本）。默认监督模式，
        // 行为与原来一致。
        val autonomyStore = AutonomyStore(appContext)
        val autonomyGate: AutonomyGate = DefaultAutonomyGate(
            autonomyStore,
            RoomControlAuditLog(taskDb.controlAuditDao()),
            controlStore,
        )

        val toolRegistry = ToolRegistry(
            deviceAdapter = deviceAdapter,
            workspaceAdapter = workspaceAdapter,
            limits = CommandLimits(),
            taskStore = taskStore,
            auditLog = auditLog,
            pythonRuntime = pythonRuntime,
            // git 家（2026-11-11）：git 家入口接进工具注册表；
            // token 从本机 Keystore 取（用户在设置里各站授权行填）。
            gitHostProjectPort = GitHostProjectPort(
                credentialStore = KeystoreGitHostCredentialStore.create(appContext),
            ),
            onRoundEvent = { kind, detail ->
                eventLog.record(
                    EngineEvent(
                        seq = 0,
                        conversationId = "",
                        kind = kind,
                        detail = SecretRedactor.redact(detail, 500),
                        epochSeconds = System.currentTimeMillis() / 1000,
                    ),
                )
            },
            // a-3 交付卡：写轮次结算的 ChangeSet 按任务记入交付卡存储。
            onChangeSet = { taskId, roundId, changeSet ->
                deliveryStore.recordRound(taskId, roundId, changeSet)
            },
            extensionTools = extensionTools,
            // 自主度门（2026-11-11）：写操作审批按 L0–L4 梯子与主动性
            // 派发门走，自动放行记审计；默认监督模式，行为与原来一致。
            autonomyGate = autonomyGate,
            // V5 跨设备开发（2026-11-12，A1/D5）：dispatch_task 建行
            // 时取当前聊天绑定作 projectId。
            currentProjectId = currentProjectId,
        )

        // ---- Brain config store (M2, unchanged) ------------------------
        val configStore = SharedPreferencesBrainConfigStore(appContext)

        // ---- SIWC auth + provider (M3) ---------------------------------
        val tokenStore = KeystoreSiwcTokenStore.create(appContext)
        val authManager = SiwcAuthManager.create(appContext, tokenStore)
        // Model resolution store (M3 blocker fix): the model resolved from
        // the account's directory is persisted via saveConfig — this is the
        // first real saveConfig caller for the SIWC brain (the old wiring
        // registered model = "" and never wrote it back, so persistence
        // could never override it). saveModel also updates the in-memory
        // registry, so the engine sees the resolved model in this process
        // without a restart.
        val modelStore = object : SiwcModelStore {
            override fun loadModel(): String? =
                configStore.getConfig(SIWC_BRAIN_ID)?.model?.takeIf { it.isNotBlank() }

            override fun saveModel(model: String) {
                // Tombstone guard (2026-11-12, membership add-all):
                // a deleted SIWC brain must NOT be rebuilt by a stale
                // model resolution — the old "rebuild it if it was
                // ever deleted" path was a resurrection bypass around
                // seededConfig. If the user deleted the brain, the
                // resolved model is dropped, not persisted.
                if (SIWC_BRAIN_ID in configStore.deletedBrainIds()) return
                // The config is seeded into the store at build (below), so
                // it normally exists; rebuild it if it was ever deleted.
                val current = configStore.getConfig(SIWC_BRAIN_ID)
                    ?: BrainConfig(
                        brainId = SIWC_BRAIN_ID,
                        kind = BrainKind.SUBSCRIPTION,
                        adapterId = SIWC_BRAIN_ID,
                        label = "ChatGPT (Sign in with ChatGPT)",
                        baseUrl = null,
                        model = "",
                        authRef = SIWC_AUTH_REF,
                    )
                val updated = current.copy(model = model)
                configStore.saveConfig(updated)
                BrainAdapterRegistry.registerConfig(updated)
            }
        }
        val provider = SiwcBrainAdapter(
            tokenStore = tokenStore,
            authManager = authManager,
            modelDiscovery = SiwcModelDiscovery(),
            modelStore = modelStore,
        )
        val bridge = SiwcM2BrainAdapter(provider)

        // ---- Registry --------------------------------------------------
        // The engine reads this registry, so registering here is what
        // makes the brain selectable. The bridge validates the config
        // at stream time (adapterId + authRef + empty model allowed).
        // model: a previously resolved + persisted model is reused; blank
        // means "not chosen yet" and the adapter resolves it at runtime
        // from the account's model directory (never a hardcoded default).
        // A stored SIWC config is used exactly as-is (including its
        // resolved model); on first run the seed has a blank model ("not
        // chosen yet", resolved at runtime from the account's model
        // directory). A deleted SIWC brain is not reseeded (tombstone).
        val siwcConfig = seededConfig(configStore, SIWC_BRAIN_ID)
        BrainAdapterRegistry.registerAdapter(bridge)
        if (siwcConfig != null) {
            BrainAdapterRegistry.registerConfig(siwcConfig)
            // Seed the persisted store on first run: registerConfig only writes
            // the in-memory registry, so without this the store would never
            // hold the SIWC config and saveModel could never persist a resolved
            // model. Existing stored configs are left untouched.
            if (configStore.getConfig(SIWC_BRAIN_ID) == null) {
                configStore.saveConfig(siwcConfig)
            }
        }

        // ---- Cloudflare Workers AI provider (free tier) -----------------
        // Second switchable brain, parallel to SIWC: SIWC stays registered
        // and selectable; the engine never sees a vendor branch.
        // Configuration lives in BrainConfig (MODEL_API): baseUrl is the
        // official accounts prefix, model is the user-chosen model id
        // (first-run seed = SUGGESTED_DEFAULT_MODEL, editable in settings),
        // extra["account_id"] is the Account ID (not a secret), and the API
        // Token lives only in Keystore under authRef "cloudflare:token".
        // fallbackBrainIds = [siwc-chatgpt]: 仅用于可重试的失败
        // （网络/限速等）。Cloudflare 额度耗尽（QuotaExceeded）自 P1 起
        // 为 non-retryable，引擎直接报出超额提示并停止，不再同大脑重试、
        // 也不再回落到已知耗尽的 SIWC 池。
        val cloudflareCredentialStore = KeystoreCloudflareCredentialStore.create(appContext)
        // 缺参修复 §7 第 5 条：引擎与 Cloudflare 适配器共用同一个 EventLog。
        // SSE 畸形行经 onMalformedLine 记入同一日志（kind="sse_malformed_line"），
        // 设备端可查；流层无会话 id，conversationId 记空串，按 kind 查，
        // 不按会话过滤。行内容先脱敏（SecretRedactor），不记 Token。
        val cloudflareProvider = CloudflareBrainAdapter(
            credentialStore = cloudflareCredentialStore,
            onMalformedLine = { line ->
                eventLog.record(
                    EngineEvent(
                        seq = 0,
                        conversationId = "",
                        kind = "sse_malformed_line",
                        detail = SecretRedactor.redact(line, 500),
                        epochSeconds = System.currentTimeMillis() / 1000,
                    ),
                )
            },
            accountIdProvider = {
                configStore.getConfig(CLOUDFLARE_BRAIN_ID)
                    ?.extra?.get(CloudflareBrainAdapter.EXTRA_ACCOUNT_ID)
                    ?.takeIf { it.isNotBlank() }
            },
            baseUrlProvider = {
                configStore.getConfig(CLOUDFLARE_BRAIN_ID)?.baseUrl?.takeIf { it.isNotBlank() }
            },
            configuredModelProvider = {
                configStore.getConfig(CLOUDFLARE_BRAIN_ID)?.model?.takeIf { it.isNotBlank() }
            },
        )
        val cloudflareBridge = CloudflareM2BrainAdapter(
            provider = cloudflareProvider,
            configuredModel = {
                configStore.getConfig(CLOUDFLARE_BRAIN_ID)?.model?.takeIf { it.isNotBlank() }
            },
        )
        // A stored Cloudflare config is used exactly as-is — including a
        // blank model (which means "not chosen yet" and errors at request
        // time). The suggested model is only the first-run seed, never a
        // silent substitute for a stored value. A deleted Cloudflare
        // brain is not reseeded (tombstone).
        val cloudflareConfig = seededConfig(configStore, CLOUDFLARE_BRAIN_ID)
        BrainAdapterRegistry.registerAdapter(cloudflareBridge)
        if (cloudflareConfig != null) {
            BrainAdapterRegistry.registerConfig(cloudflareConfig)
            // Seed the persisted store on first run (same reason as SIWC above).
            // A stored Cloudflare config is left untouched: the user's model
            // choice and Account ID are never overwritten by the seed.
            if (configStore.getConfig(CLOUDFLARE_BRAIN_ID) == null) {
                configStore.saveConfig(cloudflareConfig)
            }
        }
        // The engine's fallback order comes from the Cloudflare config:
        // a retryable Cloudflare failure (e.g. transport) falls back to
        // SIWC; a non-retryable failure (e.g. quota exhausted, auth)
        // reports immediately with no fallback. The policy
        // is read once at build; editing the fallback list needs an app
        // restart to take effect.
        val cloudflareFallback = configStore.getConfig(CLOUDFLARE_BRAIN_ID)?.fallbackBrainIds
            ?: listOf(SIWC_BRAIN_ID)

        // ---- DeepSeek API (2026-11-09, configurable MODEL_API brain) ----
        // Key: only in Keystore under AUTH_REF. baseUrl / model: plain
        // BrainConfig, user-editable and never seeded from chat content.
        // The model list is live (`GET /models`); the seed is only a first-run
        // suggestion, never a silent fallback at request time.
        val deepSeekCredentialStore = KeystoreDeepSeekCredentialStore.create(appContext)
        val deepSeekAdapter = DeepSeekBrainAdapter(
            credentialStore = deepSeekCredentialStore,
            baseUrlProvider = {
                configStore.getConfig(DEEPSEEK_BRAIN_ID)?.baseUrl?.takeIf { it.isNotBlank() }
            },
            configuredModelProvider = {
                configStore.getConfig(DEEPSEEK_BRAIN_ID)?.model?.takeIf { it.isNotBlank() }
            },
        )
        val deepSeekConfig = seededConfig(configStore, DEEPSEEK_BRAIN_ID)
        BrainAdapterRegistry.registerAdapter(deepSeekAdapter)
        if (deepSeekConfig != null) {
            BrainAdapterRegistry.registerConfig(deepSeekConfig)
            if (configStore.getConfig(DEEPSEEK_BRAIN_ID) == null) {
                configStore.saveConfig(deepSeekConfig)
            }
        }

        // ---- Muse Spark (Meta Model API, 2026-11-11, configurable MODEL_API brain) ----
        // Calls the Muse Spark model through Meta's official Model API
        // (https://api.meta.ai/v1). Key: only in Keystore under AUTH_REF.
        // baseUrl / model: plain BrainConfig, user-editable and never
        // seeded from chat content. The model list is live (`GET /models`);
        // the seed is only a first-run suggestion, never a silent fallback
        // at request time. Honest scope: this is the Muse Spark model,
        // not the personal Muse assistant (no public API for that); the
        // adapter label and capabilities notes say so in the UI.
        val museCredentialStore = KeystoreMuseCredentialStore.create(appContext)
        val museAdapter = MuseBrainAdapter(
            credentialStore = museCredentialStore,
            baseUrlProvider = {
                configStore.getConfig(MUSE_BRAIN_ID)?.baseUrl?.takeIf { it.isNotBlank() }
            },
            configuredModelProvider = {
                configStore.getConfig(MUSE_BRAIN_ID)?.model?.takeIf { it.isNotBlank() }
            },
        )
        val museConfig = seededConfig(configStore, MUSE_BRAIN_ID)
        BrainAdapterRegistry.registerAdapter(museAdapter)
        if (museConfig != null) {
            BrainAdapterRegistry.registerConfig(museConfig)
            if (configStore.getConfig(MUSE_BRAIN_ID) == null) {
                configStore.saveConfig(museConfig)
            }
        }

        // ---- Grok API (2026-11-11, configurable MODEL_API brain) ----
        // Same shape as DeepSeek above. Key: only in Keystore under
        // AUTH_REF. baseUrl / model: plain BrainConfig, user-editable
        // and never seeded from chat content. The model list is live
        // (`GET /models`); the seed is only a first-run suggestion,
        // never a silent fallback at request time.
        // This reaches the Grok MODEL via xAI's official API, not the
        // Example Agent agent (phone App + cloud computer, no public chat
        // API). Billing: the xAI API is separately billed (grok-4.7
        // $2.00/1M input, $6.00/1M output) and does NOT share any
        // Grok/X subscription or membership quota — same as Muse
        // Spark vs the Muse subscription. To spend maintainer's existing
        // Grok membership quota, use the example-agent CLI membership
        // brain (Hand-discovered, already in the App), not this one.
        val grokCredentialStore = KeystoreGrokCredentialStore.create(appContext)
        val grokAdapter = GrokBrainAdapter(
            credentialStore = grokCredentialStore,
            baseUrlProvider = {
                configStore.getConfig(GROK_BRAIN_ID)?.baseUrl?.takeIf { it.isNotBlank() }
            },
            configuredModelProvider = {
                configStore.getConfig(GROK_BRAIN_ID)?.model?.takeIf { it.isNotBlank() }
            },
        )
        val grokConfig = seededConfig(configStore, GROK_BRAIN_ID)
        BrainAdapterRegistry.registerAdapter(grokAdapter)
        if (grokConfig != null) {
            BrainAdapterRegistry.registerConfig(grokConfig)
            if (configStore.getConfig(GROK_BRAIN_ID) == null) {
                configStore.saveConfig(grokConfig)
            }
        }

        // ---- Claude API (2026-11-12, configurable MODEL_API brain) ----
        // Anthropic Messages API — NOT OpenAI-compatible (see
        // ClaudeBrainAdapter: no OpenAI-compatible endpoint exists for
        // Anthropic, so this is a dedicated adapter, not the generic
        // one). Key: only in Keystore under AUTH_REF. baseUrl / model:
        // plain BrainConfig, user-editable. The model list is live
        // (`GET /v1/models`); the seed is only a first-run suggestion,
        // never a silent fallback at request time.
        val claudeCredentialStore = KeystoreClaudeCredentialStore.create(appContext)
        val claudeAdapter = ClaudeBrainAdapter(
            credentialStore = claudeCredentialStore,
            baseUrlProvider = {
                configStore.getConfig(CLAUDE_BRAIN_ID)?.baseUrl?.takeIf { it.isNotBlank() }
            },
            configuredModelProvider = {
                configStore.getConfig(CLAUDE_BRAIN_ID)?.model?.takeIf { it.isNotBlank() }
            },
        )
        val claudeConfig = seededConfig(configStore, CLAUDE_BRAIN_ID)
        BrainAdapterRegistry.registerAdapter(claudeAdapter)
        if (claudeConfig != null) {
            BrainAdapterRegistry.registerConfig(claudeConfig)
            if (configStore.getConfig(CLAUDE_BRAIN_ID) == null) {
                configStore.saveConfig(claudeConfig)
            }
        }

        // ---- Membership brains, device-code flow (2026-11-12) ---------
        // Qwen / Kimi / Grok membership: one login manager + one
        // Keystore token store + one inference adapter per vendor.
        // Tokens live only in Keystore under "devicecode:<vendor>"
        // (see DeviceCodeTokenStore); the config holds only the
        // authRef. Seeding goes through seededConfig like every other
        // first-line brain: a deleted membership brain is not
        // reseeded (tombstone), including after an app update.
        // Inference reuses the generic OpenAI-compatible client via
        // DeviceCodeBrainAdapter — no second chat-completions copy.
        // Kimi's OAuth calls carry X-Msh-* device headers; Qwen and
        // Grok send none. No CLI OAuth token is ever read, stored,
        // or relayed here: the device-code token is the user's own
        // OAuth grant to V5 itself, obtained in the App's own login
        // flow — a different thing from a CLI's stored credential,
        // which this code never touches.
        val deviceCodeTransport = UrlConnectionOAuthHttpTransport()
        val deviceCodeManagers = mutableMapOf<String, DeviceCodeManager>()
        val deviceCodeTokenStores = mutableMapOf<String, DeviceCodeTokenStore>()
        val deviceCodeAdapters = mutableMapOf<String, DeviceCodeBrainAdapter>()
        for (deviceCodeConfig in DeviceCodeConfigs.all) {
            val tokenStore = DeviceCodeTokenStore.create(
                appContext,
                deviceCodeConfig.authRef,
            )
            val manager = DeviceCodeManager(
                config = deviceCodeConfig,
                tokenStore = tokenStore,
                http = deviceCodeTransport,
                deviceHeaders = {
                    if (deviceCodeConfig.needsDeviceHeaders) {
                        KimiDeviceHeaders.forContext(appContext)
                    } else {
                        emptyMap()
                    }
                },
            )
            val adapter = DeviceCodeBrainAdapter(
                config = deviceCodeConfig,
                manager = manager,
                configStore = configStore,
            )
            deviceCodeManagers[deviceCodeConfig.brainId] = manager
            deviceCodeTokenStores[deviceCodeConfig.brainId] = tokenStore
            deviceCodeAdapters[deviceCodeConfig.brainId] = adapter
            BrainAdapterRegistry.registerAdapter(adapter)
            val seeded = seededConfig(configStore, deviceCodeConfig.brainId)
            if (seeded != null) {
                BrainAdapterRegistry.registerConfig(seeded)
                if (configStore.getConfig(deviceCodeConfig.brainId) == null) {
                    configStore.saveConfig(seeded)
                }
            }
        }

        // ---- Gemini login-only entry (2026-11-12) ----------------------
        // The Gemini brain is a login shell, not an inference brain:
        // the adapter refuses every chat call honestly (see
        // LoginOnlyBrainAdapter), isBrainConfigured always returns
        // false for it, and the row can never be switched to. The
        // entry exists so the login can be added (once a V5 developer
        // registers a V5-owned Desktop OAuth client and fills
        // VendorOAuthConfigs.GEMINI.fixedClientId) and, per maintainer's
        // rule, so it can be fully deleted like every other brain.
        val geminiLoginOnlyAdapter = LoginOnlyBrainAdapter(VendorOAuthConfigs.GEMINI)
        BrainAdapterRegistry.registerAdapter(geminiLoginOnlyAdapter)
        val geminiConfig = seededConfig(configStore, GEMINI_BRAIN_ID)
        if (geminiConfig != null) {
            BrainAdapterRegistry.registerConfig(geminiConfig)
            if (configStore.getConfig(GEMINI_BRAIN_ID) == null) {
                configStore.saveConfig(geminiConfig)
            }
        }

        // ---- CLI 会员额度大脑 (C3-D) ------------------------------------
        // Borrows an already-logged-in device CLI's membership quota as
        // the brain: no key, no baseUrl, no model catalogue. The adapter
        // reads its config live from the store (cliBinary + loginState).
        // 设置清理 2 后端照应（2026-11-11，前后端要照应、不留尾巴）：
        // 沙箱种子已删——沙箱里装 CLI 是 11 月 8 号已纠正的错误方向，
        // 沙箱里从来没有 CLI，种子每次都写下一个 cliBinary 为空的空壳
        // 配置，大脑列表现在直接跳过它（见 BrainLoginRows.buildRows）。
        // cli-membership 配置的唯一写入方是 Agent 表单：用户加 CLI
        // Agent 时若还没有就按填的二进制建一个 stub（见 MainActivity
        // showAgentForm）。适配器只注册、不配配置；已有配置由下面通用
        // 一节的 listConfigs 注册接线。
        // 顺带清掉此前种子留下的空壳：空壳永远成不了当前大脑
        // （isBrainConfigured 要求 cliBinary 非空且已登录），也进不了
        // 大脑列表，删它不改变任何行为。
        val cliRunner = CliRunner(AgentRunner(appContext, RuntimeEnv(appContext)))
        val cliAdapter = CliMembershipBrainAdapter(
            configStore = configStore,
            cliRunner = cliRunner,
        )
        BrainAdapterRegistry.registerAdapter(cliAdapter)
        val storedCli = configStore.getConfig(CLI_BRAIN_ID)
        if (storedCli != null && storedCli.cliBinary.isNullOrBlank()) {
            configStore.deleteConfig(CLI_BRAIN_ID)
        }

        // ---- 通用 OpenAI 兼容大脑 (C3 第二阶段 W-adapter) -------------
        // 一个 adapterId "model-api-generic" 服务全部用户自建的通用大脑：
        // brainId 由用户标签 slug 化（BrainLoginRows.genericBrainId，
        // "model-api-<slug>"），Key 引用名 "model-api:<brainId>"（与
        // saveGenericBrain 的 BrainKeyStore.putSecret 同路）。不种子化任何
        // 固定 brainId：通用大脑是用户创建的，种子一个用户没加过的大脑
        // 是造假。fallbackBrainIds 留空：失败不自动换脑、不换计费方
        // （通用配置保存时即为空列表，见 saveGenericBrain）。
        // 注意：只注册适配器不够——引擎按 brainId 找配置走的是注册表
        // （BrainAdapterRegistry.configFor），已存的通用配置必须也
        // 在此注册，否则聊天走到引擎会报 brain_not_found（2026-11-11
        // 添加 Agent 修复时发现：此前这句注释写的“注册适配器即接线”
        // 是错的，设置页加的通用大脑从未真正接线）。
        val genericCredentialStore = KeystoreGenericOpenAiCredentialStore.create(appContext)
        val genericAdapter = GenericOpenAiBrainAdapter(
            configStore = configStore,
            credentialStore = genericCredentialStore,
        )
        BrainAdapterRegistry.registerAdapter(genericAdapter)
        configStore.listConfigs().forEach { BrainAdapterRegistry.registerConfig(it) }

        // ---- Engine (M2, unchanged) ------------------------------------
        // currentBrainId reads the persisted selection: if the user has
        // never chosen, it is null and the engine reports "no brain
        // configured" honestly (never falls back to a hidden default).
        val turnController = TurnController()
        // 阶段 3（A1 P1）：真实会话账本。ControlPlane 是 CONTROL 指令
        // 唯一的出口（router 只记录意图 + 移交，从不直接执行）；授权
        // 用 Room grants 表（与 TaskStore 同库）。此前 build() 未传
        // sessionLedger，引擎一直用 NoOpSessionLedger。
        val authStore = RoomAuthorizationStore(taskDb)
        val controlPlane = ControlPlane(
            taskStore,
            authStore,
            RoomRevocationStore(taskDb),
            controlStore = controlStore,
            remoteTargetResolver = remoteTargetResolver,
            remoteControlSender = remoteControlSender,
        )
        val controlRouter = ControlInstructionRouter(
            taskStore,
            taskDb.sessionMessageDao(),
            controlPlane,
            authStore,
        )
        val sessionLedger: SessionLedger = SessionLedgerImpl(
            taskDb.sessionDao(),
            taskDb.sessionMessageDao(),
            taskDb.sessionDeliveryDao(),
            controlRouter,
            authStore,
        )
        val engine = BrainEngine(
            adapters = BrainAdapterRegistry,
            conversations = conversationStore,
            contextManager = ContextManager(),
            memory = InMemoryMemoryStore(),
            skillStore = skillStore,
            tools = toolRegistry,
            eventLog = eventLog,
            approvalGate = approvalGate,
            currentBrainId = { configStore.currentBrainId() },
            failoverPolicy = FailoverPolicy(fallbackBrainIds = cloudflareFallback),
            controlState = { turnController.snapshot() },
            sessionLedger = sessionLedger,
            // git 家（2026-11-11）：当前聊天项目的 git 家。
            gitHostProjectContext = gitHostProjectContext ?: { null },
        )

        // 阶段 1（U3）：本地分派执行器。执行体就是一次 engine.chat()
        // 轮次（conversationId = 任务 id，goal = 任务描述）：本机能做的
        // Python 任务由大脑的工具循环真实执行，工具事件记为任务步骤；
        // 无大脑配置时 engine 如实报 brain_not_configured，任务记 failed，
        // 绝不伪造成功。TurnController 由执行器 begin/reset，与聊天轮次
        // 共用一个进程级控制器——并发由 runner（activeWorkCount 检查）
        // 与 ChatViewModel（runner 忙时拒发）双向守住。
        val taskExecutor = object : TaskExecutor {
            override suspend fun execute(
                task: TaskRecord,
                onStep: (TaskStepEvent) -> Unit,
            ): TaskExecutionResult {
                turnController.begin()
                return try {
                    val finalText = StringBuilder()
                    var failure: String? = null
                    // V5 技能记忆：分派任务带 projectId，大脑按任务内容
                    // 捞本项目相关技能进上下文（无项目时捞全局技能）。
                    // P0：发给大脑的是任务包（目标原文在前、契约段落
                    // 在后），不是裸目标；本地执行与远端执行收同一份包。
                    // P2：流水线任务发 plan 批准后的包（验收标准与文件
                    // 清单已填进包里）。
                    // 2026-11-11 brain_not_configured 次修复：任务行自带
                    // 的大脑 id（建任务时写入的 brainId）之前没人读，任务
                    // 生死全看执行时刻的全局 current；旧任务行 brainId
                    // 为 null 时再回落到当前全局大脑。仍为空时引擎如实
                    // 报 brain_not_configured，任务记 failed，绝不伪造成功。
                    engine.chat(
                        task.taskId,
                        pipelineOrchestrator.coderPacket(task).toPromptText(),
                        brainIdOverride = task.brainId ?: configStore.currentBrainId(),
                        projectId = task.projectId,
                    ).collect { event ->
                        when (event) {
                            is BrainEvent.ToolCall -> onStep(
                                TaskStepEvent.Started(
                                    toolCallId = event.id,
                                    toolName = event.name,
                                    argsSummary = SecretRedactor.redact(
                                        event.args.entries.joinToString(", ") { (key, value) ->
                                            "$key=${value.toString().take(120)}"
                                        },
                                        500,
                                    ),
                                ),
                            )
                            is BrainEvent.ToolResult -> onStep(
                                TaskStepEvent.Finished(
                                    toolCallId = event.id,
                                    ok = event.ok,
                                    resultSummary = SecretRedactor.redact(event.summary, 1000),
                                ),
                            )
                            is BrainEvent.TextDelta -> finalText.append(event.text)
                            is BrainEvent.Error -> failure = "${event.code}：${event.message}"
                            is BrainEvent.ReasoningDelta -> Unit
                            is BrainEvent.Usage -> Unit
                            is BrainEvent.Done -> Unit
                        }
                    }
                    // failure 是被 collect 闭包捕获的 var，if 后的智能转型
                    // 不可用，先快照到局部 val 再判断。
                    val failureText = failure
                    if (failureText != null) {
                        TaskExecutionResult(
                            success = false,
                            summary = "",
                            error = SecretRedactor.redact(failureText, 4000),
                        )
                    } else {
                        TaskExecutionResult(
                            success = true,
                            summary = SecretRedactor.redact(finalText.toString(), 4000),
                        )
                    }
                } finally {
                    turnController.reset()
                }
            }
        }
        // P1 确定性自动派活（2026-11-11）：eval 日志 + 路由决策日志 +
        // 能力卡 hello 快照（SharedPreferences 持久化，重启仍在）。
        val evalLogStore: EvalLogStore = SharedPreferencesEvalLogStore(appContext)
        val routingDecisionLog: RoutingDecisionLog = SharedPreferencesRoutingDecisionLog(appContext)
        val routerSettings: RouterSettings = SharedPreferencesRouterSettings(appContext)
        val capabilityCardStore: CapabilityCardStore = SharedPreferencesCapabilityCardStore(appContext)
        // V5 技能记忆：任务落终态时捕获技能（成功记做法，失败记坑）。
        val taskRunner = LocalTaskRunner(
            taskStore,
            taskScheduler,
            taskStepStore,
            taskExecutor,
            skillRefiner = SkillRefiner(skillStore),
            evalRecorder = EvalLogRecorder(evalLogStore, routingDecisionLog),
            // P2：plan 未批准不认领、编码完成停 review（见 LocalTaskRunner）。
            pipelineHook = pipelineHook,
            // P0 round-4: DEBUG-only instrumentation claim hold (see
            // LocalTaskRunner.claimHold). Release builds never check.
            claimHold = {
                wherevercode.agent.BuildConfig.DEBUG &&
                    java.io.File(
                        appContext.getExternalFilesDir(null),
                        "issue8_claim_hold",
                    ).exists()
            },
        )
        controlPlane.configureLocalExecutionControl(
            isExecuting = { taskId -> taskRunner.isExecuting(taskId) },
        ) { taskId, action ->
            // TurnController is shared with foreground chat. Never let a
            // stale/orphaned task control interrupt a different live turn.
            if (!taskRunner.isExecuting(taskId)) return@configureLocalExecutionControl false
            val task = taskStore.getTask(taskId) ?: return@configureLocalExecutionControl false
            if (!task.assignedAgentId.equals(LocalTaskRunner.LOCAL_AGENT_ID, ignoreCase = true)) {
                return@configureLocalExecutionControl false
            }
            when (action) {
                ControlAction.PAUSE -> turnController.pause()
                ControlAction.STOP -> turnController.stop()
                else -> false
            }
        }
        taskRunner.onControlCheckpoint = { taskId, outcome ->
            val operationId = "local-control-${UUID.randomUUID()}"
            when (outcome) {
                "paused" -> controlPlane.confirmPaused(taskId, operationId, "本机执行器到达大脑安全检查点")
                "stopped" -> controlPlane.confirmStop(taskId, operationId, "本机执行器到达大脑安全检查点")
            }
        }
        taskRunner.onLeaseSweep = { controlPlane.reconcileExpiredLocalControls() }

        val components = BrainComponents(
            engine = engine,
            authManager = authManager,
            tokenStore = tokenStore,
            brainAdapter = provider,
            configStore = configStore,
            cloudflareCredentialStore = cloudflareCredentialStore,
            cloudflareProvider = cloudflareProvider,
            cloudflareBridge = cloudflareBridge,
            deepSeekAdapter = deepSeekAdapter,
            deepSeekCredentialStore = deepSeekCredentialStore,
            museAdapter = museAdapter,
            museCredentialStore = museCredentialStore,

            grokAdapter = grokAdapter,
            grokCredentialStore = grokCredentialStore,
            claudeAdapter = claudeAdapter,
            claudeCredentialStore = claudeCredentialStore,
            deviceCodeManagers = deviceCodeManagers,
            deviceCodeTokenStores = deviceCodeTokenStores,
            deviceCodeAdapters = deviceCodeAdapters,
            geminiLoginOnlyAdapter = geminiLoginOnlyAdapter,
            genericAdapter = genericAdapter,
            genericCredentialStore = genericCredentialStore,
            workspaceAdapter = workspaceAdapter,
            pythonRuntime = pythonRuntime,
            turnController = turnController,
            taskStore = taskStore,
            taskScheduler = taskScheduler,
            controlPlane = controlPlane,
            taskStepStore = taskStepStore,
            deliveryStore = deliveryStore,
            taskEventStore = taskEventStore,
            pipelineStore = pipelineStore,
            pipelineOrchestrator = pipelineOrchestrator,
            fanoutStore = fanoutStore,
            skillStore = skillStore,
            pairingTrustStore = pairingTrustStore,
            taskRunner = taskRunner,
            eventLog = eventLog,
            sessionLedger = sessionLedger,
            conversationStore = conversationStore,
            workspaceDir = workspaceDir,
            extensionRegistry = extensionRegistry,
            pluginRegistry = pluginRegistry,
            mcpServerManager = mcpServerManager,
            evalLogStore = evalLogStore,
            routingDecisionLog = routingDecisionLog,
            routerSettings = routerSettings,
            capabilityCardStore = capabilityCardStore,
            autonomyStore = autonomyStore,
            autonomyGate = autonomyGate,
        )

        // P2 自动执行（2026-11-11）：规划者自动起草 + 审查者自动执行的泵。
        // 调用方：本机大脑（engine.chat，allowFailover=false）+ 可选的
        // 远端成员（Hand，调用方传 pipelineRemoteCaller）；审查者候选：
        // 已配置的本机大脑（不同大脑优先，见 selectReviewer）+ 可选的
        // 远端成员（调用方传 pipelineRemoteReviewerCandidates）。
        // 审查输入的改动文件清单从 write_file 步骤抽（与详情页同口径）；
        // diff 摘要与测试 verdict 今天没有生产来源，如实空着（见
        // PipelineReviewInputs 默认值），不编。
        val localCaller = BrainEnginePipelineAgentCaller(engine)
        val pipelineCaller: PipelineAgentCaller =
            if (pipelineRemoteCaller != null) {
                CompositePipelineAgentCaller(listOf(pipelineRemoteCaller, localCaller))
            } else {
                localCaller
            }
        val pipelineRunner = PipelineRunner(
            taskStore = taskStore,
            orchestrator = pipelineOrchestrator,
            pipelineStore = pipelineStore,
            caller = pipelineCaller,
            reviewerCandidates = {
                val out = ArrayList<PipelineOrchestrator.ReviewerCandidate>()
                try {
                    configStore.listConfigs().forEach { config ->
                        if (components.isBrainConfigured(config.brainId)) {
                            out += PipelineOrchestrator.ReviewerCandidate(
                                agentId = config.brainId,
                                deviceId = null,
                                label = config.label,
                            )
                        }
                    }
                } catch (_: Exception) {
                    // 大脑候选读失败：只剩远端候选，不杀 runner。
                }
                try {
                    pipelineRemoteReviewerCandidates?.invoke()?.let { out += it }
                } catch (_: Exception) {
                    // 远端候选读失败：只剩本机大脑候选，不杀 runner。
                }
                out
            },
            reviewInputs = { task ->
                val paths = try {
                    TaskStepArtifacts.extractPaths(taskStepStore.listSteps(task.taskId))
                } catch (_: Exception) {
                    emptyList()
                }
                PipelineReviewInputs(changedFiles = paths)
            },
            // 本机大脑一次只跑一轮：编码执行或聊天轮次在途时泵 defer，
            // 与 LocalTaskRunner 的 activeWorkCount 门禁同款。
            isWorkInHand = { taskScheduler.activeWorkCount() > 0 },
        )
        return components.copy(pipelineRunner = pipelineRunner)
    }
}
