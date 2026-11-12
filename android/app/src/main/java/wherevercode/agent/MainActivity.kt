package wherevercode.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import wherevercode.agent.brain.AgentChatRoute
import wherevercode.agent.brain.AgentChatRouter
import wherevercode.agent.brain.BrainAdapterRegistry
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigValidator
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.brain.EngineEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.cli.CliMembershipBrainAdapter
import wherevercode.agent.brain.claude.ClaudeBrainAdapter
import wherevercode.agent.brain.cloudflare.CloudflareBrainAdapter
import wherevercode.agent.brain.deepseek.DeepSeekApiClient
import wherevercode.agent.brain.deepseek.DeepSeekBrainAdapter
import wherevercode.agent.brain.devicecode.DeviceCodeConfigs
import wherevercode.agent.brain.grok.GrokBrainAdapter
import wherevercode.agent.brain.muse.MuseBrainAdapter
import wherevercode.agent.brain.siwc.BrainBootstrap
import wherevercode.agent.brain.siwc.SiwcM2BrainAdapter
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentBrainWiring
import wherevercode.agent.core.agent.AgentConnectionTester
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.CloudAgent
import wherevercode.agent.core.agent.CloudAgentCredentialStore
import wherevercode.agent.core.agent.CloudAgentForm
import wherevercode.agent.core.agent.CloudAgentMcpEndpoint
import wherevercode.agent.core.agent.CloudAgentSetupSheet
import wherevercode.agent.core.agent.DeviceAgentDiscovery
import wherevercode.agent.core.agent.DeviceAgentForm
import wherevercode.agent.core.agent.AgentLoginState
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.agent.DeviceProbeResult
import wherevercode.agent.core.agent.enabledAgents
import wherevercode.agent.core.agent.setAgentEnabled
import wherevercode.agent.core.agent.AgentSecretEdit
import wherevercode.agent.core.agent.AgentStore
import wherevercode.agent.core.autonomy.AutonomyMode
import wherevercode.agent.core.autonomy.AutonomyPolicy
import wherevercode.agent.core.autonomy.AutonomyStore
import wherevercode.agent.core.autonomy.ExecutionLevel
import wherevercode.agent.core.autonomy.Initiative
import wherevercode.agent.core.autonomy.UiFreedom
import wherevercode.agent.core.agent.ManusChatBridge
import wherevercode.agent.core.agent.ManusChatPort
import wherevercode.agent.core.agent.ManusTaskAdapter
import wherevercode.agent.core.agent.ChannelConnectionState
import wherevercode.agent.core.agent.ChannelFeed
import wherevercode.agent.core.agent.ChannelWsClient
import wherevercode.agent.core.agent.PlatformAgent
import wherevercode.agent.core.agent.PlatformAgentChatBridge
import wherevercode.agent.core.agent.PlatformAgentCredentialStore
import wherevercode.agent.core.agent.PlatformAgentForm
import wherevercode.agent.core.agent.PlatformChannelMessage
import wherevercode.agent.core.agent.PlatformChannelMcpTransport
import wherevercode.agent.core.agent.PlatformEndpointPinning
import wherevercode.agent.core.agent.PlatformTokenIssuer
import wherevercode.agent.core.agent.IssueTokenResult
import wherevercode.agent.core.agent.PlatformChatPort
import wherevercode.agent.core.agent.PlatformProbeResult
import wherevercode.agent.core.agent.PlatformWebhookTester
import wherevercode.agent.core.agent.WebhookTestResult
import wherevercode.agent.core.agent.SharedPreferencesAgentStore
import wherevercode.agent.core.agent.SharedPreferencesManusTaskIdStore
import wherevercode.agent.core.agent.SharedPreferencesPlatformReplyCursorStore
import wherevercode.agent.core.control.HandRemoteControlSender
import wherevercode.agent.core.install.AssetReader
import wherevercode.agent.core.install.DeviceInstallController
import wherevercode.agent.core.install.HandInstallBundleLoader
import wherevercode.agent.core.install.JschSshSessionFactory
import wherevercode.agent.core.mcp.HandClient
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.HandRemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.mcp.WsTokenResult
import wherevercode.agent.core.project.ChatProjectBinding
import wherevercode.agent.core.project.activeGitHome
import wherevercode.agent.core.project.checkoutCandidates
import wherevercode.agent.core.project.DeviceProjectSource
import wherevercode.agent.core.project.GitHost
import wherevercode.agent.core.project.GitHostCredentialStore
import wherevercode.agent.core.project.GitHostProjectPort
import wherevercode.agent.core.project.GitRepoRef
import wherevercode.agent.core.project.KeystoreGitHostCredentialStore
import wherevercode.agent.core.project.OpenFolderLocationKind
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.project.ProjectHomeKind
import wherevercode.agent.core.project.ProjectLocation
import wherevercode.agent.core.project.ProjectStore
import wherevercode.agent.core.project.RemoteFolderEntry
import wherevercode.agent.core.project.dispatchBlockedReason
import wherevercode.agent.core.project.resolveDispatchFolderForProject
import wherevercode.agent.core.project.DispatchFolderResult
import wherevercode.agent.core.project.SharedPreferencesProjectStore
import wherevercode.agent.core.project.deviceProjectSources
import wherevercode.agent.core.project.deviceSourceAvailability
import wherevercode.agent.core.project.folderBasename
import wherevercode.agent.core.project.hostProjectListing
import wherevercode.agent.core.project.remoteFolderPickerListing
import wherevercode.agent.core.project.isValidLocalWorkspacePath
import wherevercode.agent.core.project.openFolderEntryOptions
import wherevercode.agent.core.project.parseFolderListJson
import wherevercode.agent.core.project.GitRepoKind
import wherevercode.agent.core.project.parseGitHostRepoUrl
import wherevercode.agent.core.project.parseGitRepoUrl
import wherevercode.agent.core.project.syncFolderWarning
import wherevercode.agent.core.project.projectHostUsableDevices
import wherevercode.agent.core.project.resolveProjectRelativePath
import wherevercode.agent.core.project.slugProjectId
import wherevercode.agent.core.project.uniqueProjectDisplayName
import wherevercode.agent.core.security.DeviceIdentityStore
import wherevercode.agent.core.security.DeviceKeys
import wherevercode.agent.core.security.KeyValueSecrets
import wherevercode.agent.core.security.RemoteCredentialKeys
import wherevercode.agent.core.security.MigratingPairingTrustStore
import wherevercode.agent.core.security.PairingManager
import wherevercode.agent.core.security.PairingTrustStore
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingSnapshotCache
import wherevercode.agent.core.security.PairingState
import wherevercode.agent.core.team.ChannelAgentRelay
import wherevercode.agent.core.team.FanoutCandidate
import wherevercode.agent.core.team.InMemoryFanoutStore
import wherevercode.agent.core.team.RelayStatus
import wherevercode.agent.core.team.RemotePipelineAgentCaller
import wherevercode.agent.core.team.RemoteTaskDispatcher
import wherevercode.agent.core.team.RemoteTaskPump
import wherevercode.agent.core.team.TeamChannelMember
import wherevercode.agent.core.team.TeamChatLimits
import wherevercode.agent.core.team.TeamChatRules
import wherevercode.agent.core.team.TeamFanoutRunner
import wherevercode.agent.core.team.buildChannelMembers
import wherevercode.agent.core.team.loadPairingRecord
import wherevercode.agent.core.team.resolveRemoteTargetFromRecords
import wherevercode.agent.core.transport.DirectTransport
import wherevercode.agent.core.transport.HostCapabilities
import wherevercode.agent.core.transport.PairConfirmResult
import wherevercode.agent.core.transport.PairingClient
import wherevercode.agent.core.transport.RemoteAgentInfo
import wherevercode.agent.core.transport.RemoteDevice
import wherevercode.agent.core.transport.RemoteDeviceRow
import wherevercode.agent.core.transport.handServerCapabilities
import wherevercode.agent.core.transport.handServerProtocolCapabilities
import wherevercode.agent.core.transport.RemoteDeviceStore
import wherevercode.agent.core.transport.RemoteDeviceValidation
import wherevercode.agent.core.transport.RemoteHello
import wherevercode.agent.core.transport.SharedPreferencesRemoteDeviceStore
import wherevercode.agent.core.transport.buildRemoteDeviceRows
import wherevercode.agent.core.security.SecretRedactor
import wherevercode.agent.core.session.SessionKind
import wherevercode.agent.core.task.CapabilityCards
import wherevercode.agent.core.task.CapabilityRouter
import wherevercode.agent.core.task.DeliveryCard
import wherevercode.agent.core.task.DeliveryCardView
import wherevercode.agent.core.task.DeliveryRoundResult
import wherevercode.agent.core.task.DeliveryTestVerdict
import wherevercode.agent.core.task.EvalLogRecorder
import wherevercode.agent.core.task.EvalScorecard
import wherevercode.agent.core.task.MechanicalCheckResult
import wherevercode.agent.core.task.PipelineOrchestrator
import wherevercode.agent.core.task.PipelineRunnerHookImpl
import wherevercode.agent.core.task.PipelineState
import wherevercode.agent.core.task.PipelineTestVerdict
import wherevercode.agent.core.task.PlanArtifact
import wherevercode.agent.core.task.ReviewVerdict
import wherevercode.agent.core.task.RoutingDecision
import wherevercode.agent.core.task.RoutingRequirement
import wherevercode.agent.core.task.SharedPreferencesCapabilityCardStore
import wherevercode.agent.core.task.SharedPreferencesEvalLogStore
import wherevercode.agent.core.task.SharedPreferencesRouterSettings
import wherevercode.agent.core.task.SharedPreferencesRoutingDecisionLog
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskLibraryGrouping
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskRetry
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.task.TaskStepStatus
import wherevercode.agent.core.task.TaskType
import wherevercode.agent.core.task.TriageRouter
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.LanTransport
import wherevercode.agent.ui.ChatProjectPickerDialog
import wherevercode.agent.ui.SessionListController
import wherevercode.agent.ui.PipelineAction
import wherevercode.agent.ui.TaskDetailArtifacts
import wherevercode.agent.ui.TaskDetailController
import wherevercode.agent.ui.taskStateLabel
import wherevercode.agent.ui.approvalOutcomeLabel
import wherevercode.agent.ui.buildSessionListItems
import wherevercode.agent.core.extension.ExtensionEntry
import wherevercode.agent.core.extension.ExtensionKind
import wherevercode.agent.core.extension.mcp.McpConfigException
import wherevercode.agent.core.extension.mcp.McpServerState
import wherevercode.agent.core.extension.mcp.McpServersConfig
import wherevercode.agent.ui.AgentScreenController
import wherevercode.agent.ui.AppUpdateSettings
import wherevercode.agent.ui.BrainDisplayName
import wherevercode.agent.ui.BrainListRowView
import wherevercode.agent.ui.BrainLoginRows
import wherevercode.agent.ui.ChatHistoryStore
import wherevercode.agent.ui.ChatSessionRestore
import wherevercode.agent.ui.ChatTurnView
import wherevercode.agent.ui.ChatUiState
import wherevercode.agent.ui.ChatViewModel
import wherevercode.agent.ui.ChatMode
import wherevercode.agent.ui.ChatAttachment
import wherevercode.agent.ui.ChatAttachmentKind
import wherevercode.agent.ui.ChatAttachments
import wherevercode.agent.ui.ConsoleScreenController
import wherevercode.agent.ui.TeamChannelController
import wherevercode.agent.ui.TeamChannelSnapshot
import wherevercode.agent.ui.ConsoleSnapshot
import wherevercode.agent.ui.ConsoleWsFeed
import wherevercode.agent.ui.EventGroupViews
import wherevercode.agent.ui.EventLogGrouping
import wherevercode.agent.ui.McpPluginScreenController
import wherevercode.agent.ui.DrawerBadges
import wherevercode.agent.ui.DrawerController
import wherevercode.agent.ui.DrawerRouter
import wherevercode.agent.ui.DrawerView
import wherevercode.agent.ui.EntryRegistry
import wherevercode.agent.ui.EntryScreen
import wherevercode.agent.ui.HandUpdateController
import wherevercode.agent.ui.SharedPrefsHandSyncSettingsStore
import wherevercode.agent.ui.HomeScreenController
import wherevercode.agent.ui.ListDialog
import wherevercode.agent.ui.PlusMenuSheet
import wherevercode.agent.ui.OnboardingController
import wherevercode.agent.ui.OnboardingView
import wherevercode.agent.ui.RoomDrawerPrefsStore
import wherevercode.agent.ui.ProjectScreenController
import wherevercode.agent.ui.RemoteFolderPicker
import wherevercode.agent.ui.ResultsAggregation
import wherevercode.agent.ui.ResultsScreenController
import wherevercode.agent.ui.ToolCallStatus
import wherevercode.agent.ui.TurnStatus
import wherevercode.agent.ui.UiKit
import wherevercode.agent.ui.VersionInfo
import wherevercode.agent.ui.WorkMode

/**
 * UI V2: a native Android development workbench for the standalone super
 * Agent. V5 Phase B C2: a single minimal chat home plus a nestable left
 * drawer (see V5-UI.md); the four page controllers (projects / tasks /
 * agents / session list) and the settings sheet are mounted on drawer
 * entries via showEntry() / DrawerRouter, not on bottom tabs.
 *
 * What is reused, not reimplemented:
 * - DeepSeek / Cloudflare / ChatGPT inference: [BrainBootstrap] and
 *   [wherevercode.agent.brain.BrainEngine], unchanged.
 * - Tool calls + approval popups: the engine's `BrainEvent` stream and the
 *   process-wide [AndroidApprovalGate].
 * - Pause / stop / change-goal: [wherevercode.agent.brain.TurnController].
 * - Project files: the real `filesDir/workspace`, read-only viewer here;
 *   writes remain the Agent's approved `write_file` / `run_command` path.
 * - Agent registry + device data: [AgentDiscovery] and Build/package facts.
 * - Local Python / shell / Node: [RuntimeEnv] and [AgentRunner].
 *
 * Deliberately not present: any connection to the old WhereverCode platform,
 * a re-designed collaboration layer, a fake editor, a fake online-device list,
 * or a fixed Agent role.
 */
class MainActivity : AppCompatActivity() {

    private val toolApprovalGate by lazy { AndroidApprovalGate.get(this) }

    private lateinit var chatViewModel: ChatViewModel
    private lateinit var runtime: RuntimeEnv
    private lateinit var runner: AgentRunner
    /** 阶段 3（U1）：默认会话历史快照（SharedPreferences）。 */
    private val chatHistoryStore by lazy { ChatHistoryStore(this) }

    private lateinit var screenContainer: View
    private lateinit var screenChat: View
    private lateinit var screenProjects: View
    private lateinit var screenTasks: View
    private lateinit var screenAgents: View
    private lateinit var screenSettings: View

    /** V5 应用更新（2026-11-11）：设置页“关于”分节的更新控制器。 */
    private lateinit var appUpdateSettings: AppUpdateSettings

    /** 自主度（2026-11-11，maintainer 22:43 两种模式）：设置页“自主度”分节四行。 */
    private val autonomyStore by lazy { AutonomyStore(applicationContext) }
    private lateinit var autonomyModeRow: TextView
    private lateinit var autonomyInitiativeRow: TextView
    private lateinit var autonomyExecutionRow: TextView
    private lateinit var autonomyUiFreedomRow: TextView

    /** V5 C2-3-R4c：大脑显示名只存 SharedPreferences（见 [BrainDisplayName]）。 */

    /** V5 Phase B C2（批 2，第 3 步）：成果占位屏与静态帮助页（程序化拼装）。 */
    private lateinit var screenResults: View
    private lateinit var resultsController: ResultsScreenController
    private lateinit var screenHelp: View

    /** V5 控制台（2026-11-11）：按需展开的专业工作台（抽屉“控制台”进入）。 */
    private lateinit var screenConsole: View
    private lateinit var consoleController: ConsoleScreenController
    /** 抽屉深链带入的控制台目标模块（待审批/运行日志）；渲染后清掉。 */
    private var consoleSection: ConsoleSnapshot.ConsoleSection? = null
    /** 上次渲染的控制台快照签名（内容不变不重建，避免 5 秒刷新打断阅读）。 */
    private var lastConsoleSig: String? = null
    /** 正在进行的控制台刷新（后台线程）；进行中时新刷新跳过，等下一轮。 */
    @Volatile
    private var consoleRefreshRunning = false

    /** V5 MCP / 插件（2026-11-11）：扩展管理屏（抽屉“扩展与资源”分组进入）。 */
    private lateinit var screenMcpPlugins: View
    private lateinit var mcpPluginController: McpPluginScreenController
    /** 正在进行的 MCP/插件刷新（后台线程）；进行中时新刷新跳过，等下一轮。 */
    @Volatile
    private var mcpPluginsRefreshRunning = false

    /** V5 团队频道（P3，2026-11-11）：有界团队聊天 + 只读扇出。 */
    private lateinit var screenTeamChannel: View
    private lateinit var teamChannelController: TeamChannelController
    private var teamChannelFeed: ChannelFeed? = null
    /** 频道消息（feed 推送 + 首轮轮询攒的，时间升序；最多留 100 条）。 */
    private val teamChannelMessages = mutableListOf<PlatformChannelMessage>()
    /**
     * @ 中转的防重/防回环 id 集（ChannelAgentRelay 与本类共用，见该类注释）：
     * 已中转过的频道消息 id + 本机贴回频道的中转回复 id。feed 线程与
     * 中转协程都会读写，用同步集合。
     */
    private val teamChannelRelayedIds =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val teamChannelRelayOutputIds =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** 本会话已发轮次（发一条消息计一轮；上限见 TeamChatLimits.MAX_ROUNDS）。 */
    private var teamChannelRoundsUsed = 0
    /** 频道页一次性提示（发帖/扇出失败原因等；下次刷新时由快照带出）。 */
    private var teamChannelNotice: String? = null
    /** 正在进行的频道刷新（后台线程）；进行中时新刷新跳过，等下一轮。 */
    @Volatile
    private var teamChannelRefreshRunning = false
    /** 正在进行的只读扇出；进行中时新的扇出请求直接拒绝（不等、不排队）。 */
    @Volatile
    private var teamChannelFanoutRunning = false

    /** V5 C2-3-R4 (v4.3-01)：顶栏是品牌行，不再有会话标题/大脑 pill；
     * 会话状态在聊天屏内，大脑状态在设置页大脑分节。 */

    private lateinit var chatScroll: ScrollView
    private lateinit var messageList: LinearLayout
    private lateinit var inputPrompt: EditText
    /** V5 Phase B C2（批 2）：发送按钮与 + 号按钮（输入状态驱动发送）。 */
    private lateinit var buttonSend: View
    private lateinit var buttonPlus: View
    /** V5 按主流改（maintainer 2026-11-11）：“+”菜单附件——待发送的附件与输入框上方条带。 */
    private val pendingAttachments = mutableListOf<ChatAttachment>()
    private var pendingStrip: LinearLayout? = null
    private var pendingPhotoFile: File? = null
    private var pendingPhotoUri: Uri? = null
    private lateinit var takePhotoLauncher: ActivityResultLauncher<Uri>
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>
    private lateinit var pickFileLauncher: ActivityResultLauncher<String>
    private lateinit var requestCameraPermissionLauncher: ActivityResultLauncher<String>
    /** V5 按主流改：网页搜索开关（SharedPreferences 持久化，默认关）。 */
    private val chatPrefs: SharedPreferences by lazy { getSharedPreferences(PREFS_CHAT, MODE_PRIVATE) }
    private var webSearchEnabled: Boolean = false
    /** V5 C2-3-R1：输入行 Agent 选择器（原型图输入行，点开已有选 Agent 对话框）。 */
    private lateinit var buttonAgentSelect: TextView
    private lateinit var buttonTaskMark: TextView
    /** 设置清理 2（2026-11-11，Claude 标准）：聊天输入框里的当前大脑胶囊。 */
    private lateinit var brainCapsule: TextView
    /** 阶段 3（U4/U6）：审批等待条、聊天页内控制行、回到底部按钮。 */
    private lateinit var approvalBanner: TextView
    private lateinit var chatControls: View
    private lateinit var buttonChatPause: TextView
    private lateinit var buttonChatStop: TextView
    private lateinit var buttonChatResume: TextView
    /** V5 C2-3-R1：控制行补齐改目标/控制台（照 v2-02，一行等宽 pill）。 */
    private lateinit var buttonChatChangeGoal: TextView
    private lateinit var buttonChatConsole: TextView
    private lateinit var buttonScrollBottom: TextView

    private lateinit var brainStatus: TextView
    private lateinit var brainDisplayNameView: TextView
    /** 阶段 A：聊天顶部“当前设备 · 对话方”横幅（screen_chat.xml）。 */
    private lateinit var chatRouteBanner: TextView
    private lateinit var buttonSignInChatGpt: Button
    private lateinit var buttonSignOut: Button
    private lateinit var cloudflareStatus: TextView
    private lateinit var cfAccountId: EditText
    private lateinit var cfApiToken: EditText
    private lateinit var cfModel: EditText
    private lateinit var deepseekStatus: TextView
    private lateinit var dsBaseUrl: EditText
    private lateinit var dsModel: EditText
    private lateinit var dsApiKey: EditText
    private lateinit var deepseekModels: TextView
    /** Grok / Muse Spark / Claude 设置节（2026-11-12，大脑登陆方式补全）：与 DeepSeek 节同形。 */
    private lateinit var grokStatus: TextView
    private lateinit var grokBaseUrl: EditText
    private lateinit var grokModel: EditText
    private lateinit var grokApiKey: EditText
    private lateinit var grokModels: TextView
    private lateinit var museStatus: TextView
    private lateinit var museBaseUrl: EditText
    private lateinit var museModel: EditText
    private lateinit var museApiKey: EditText
    private lateinit var museModels: TextView
    private lateinit var claudeStatus: TextView
    private lateinit var claudeBaseUrl: EditText
    private lateinit var claudeModel: EditText
    private lateinit var claudeApiKey: EditText
    private lateinit var claudeModels: TextView
    /** git 家授权（2026-11-11）：设置页每站一行授权（GitHub / GitLab / Gitee）。 */
    private data class GitHostAuthRow(
        val host: GitHost,
        val statusView: TextView,
        val tokenInput: EditText,
        val saveButton: View,
        val clearButton: View,
    )

    private val gitHostAuthRows = mutableListOf<GitHostAuthRow>()

    /** 平台令牌（2026-11-12，maintainer：App 没有 token 生成按钮）：设置页“平台令牌”分节。 */
    private lateinit var platformTokenStatus: TextView
    private lateinit var platformTokenAgentId: EditText
    private lateinit var platformTokenName: EditText

    // V5 C3-C：设置页大脑多模式登陆（统一列表 / SIWC 模型选择 /
    // 通用 MODEL_API 表单 / CLI 行 / 换号·取消）。
    private lateinit var brainListContainer: LinearLayout
    private lateinit var siwcModelStatus: TextView
    private lateinit var buttonRefreshSiwcModels: Button
    private lateinit var buttonPickSiwcModel: Button
    private lateinit var buttonSwitchChatGptAccount: Button
    private lateinit var buttonCancelSignIn: Button
    private lateinit var genericBrainLabel: EditText
    private lateinit var genericBaseUrl: EditText
    private lateinit var genericModel: EditText
    private lateinit var genericApiKey: EditText
    private lateinit var genericModels: TextView
    private lateinit var cliBrainSection: View
    private lateinit var cliBrainStatus: TextView
    private lateinit var cliBrainBinary: TextView
    private lateinit var cliBrainNote: TextView
    private lateinit var buttonVerifyCli: Button
    /** 设置清理 2（2026-11-11）：大脑高级配置折叠容器与开关。 */
    private lateinit var brainAdvancedContainer: View
    private lateinit var buttonBrainAdvanced: TextView
    private var siwcSigningIn = false
    private var siwcSignInCancelled = false
    /** 设备码登录进行中时为 true：用户点“取消登录”即停轮询。 */
    private var deviceCodeLoginCancelled = false
    private var siwcModels: List<String> = emptyList()
    /** 阶段 3（U6）：设置分节——工作区路径与关于版本。 */
    private lateinit var workspacePath: TextView
    private lateinit var workspaceAdvancedDetails: TextView
    private lateinit var buttonAdvancedInfo: TextView
    private lateinit var aboutVersion: TextView

    private lateinit var currentStatusDot: TextView
    private lateinit var currentStatusText: TextView
    private lateinit var currentStatusChip: TextView
    private lateinit var currentBrain: TextView
    private lateinit var currentStarted: TextView
    private lateinit var currentNote: TextView
    private lateinit var buttonPause: Button
    private lateinit var buttonStop: Button
    private lateinit var buttonChangeGoal: Button
    private lateinit var eventList: LinearLayout
    private lateinit var taskList: LinearLayout
    /** 阶段 3（U4）：任务页审批等待条 + 审批历史列表。 */
    private lateinit var taskApprovalBanner: TextView
    private lateinit var approvalHistoryList: LinearLayout
    private var lastApprovalHistorySig: String? = null

    /** 阶段 3（U4）：审批倒计时 ticker（1 秒一跳；仅运行中或有等待审批时续跑）。 */
    private val approvalHandler = Handler(Looper.getMainLooper())
    private var approvalTickerActive = false
    private val approvalTick = Runnable { tickApproval() }

    /** 控制台自动刷新 ticker：控制台打开时每 5 秒重读一次快照，切走即停。 */
    private val consoleHandler = Handler(Looper.getMainLooper())
    private var consoleTickerActive = false
    private val consoleTick = Runnable { tickConsole() }

    /**
     * 控制台 WS 订阅（V5 控制台实时推送，2026-11-11）：
     * WS 只是加速器——事件到了立即刷新，不等 5 秒轮询；
     * 5 秒轮询 ticker 是安全网，永远保持运行（不关），WS 断了轮询照样
     * 每 5 秒更新，控制台绝不因此变空白。WS 状态只做内部状态，
     * 绝不据此在界面上写设备“在线/离线”。
     */
    private var consoleWsFeed: ConsoleWsFeed? = null

    private lateinit var agentList: LinearLayout
    private lateinit var deviceInfo: LinearLayout

    /** 阶段 3（A1 P1）：已添加的 Agent 记录（SharedPreferences 持久化）。 */
    private lateinit var agentStore: AgentStore

    /** 阶段 3（A1 P1）：Agent 连接测试（MODEL_API 走 /models，CLI 走 --version）。 */
    private lateinit var agentTester: AgentConnectionTester

    /** 密钥值只进 Keystore（与 BrainConfig 同款规则）。 */
    private val brainKeyStore by lazy { BrainKeyStore(applicationContext) }

    private lateinit var projectController: ProjectScreenController
    private lateinit var agentController: AgentScreenController
    /** Hand 自更新控制器（2026-11-12）：设备卡“更新 Hand”与设置页入口。 */
    private var handUpdateController: HandUpdateController? = null
    /** App 内置安装包的 Hand 版本（SHA-256 前 12 位），读不到为 ""。 */
    private var cachedBundledHandVersion: String? = null

    private var brainComponents: BrainBootstrap.BrainComponents? = null
    /** 阶段 1（U3）：任务列表自动刷新的去重签名（见 maybeRefreshTasks）。 */
    private var lastTaskRefreshSig: String? = null
    private val turnViews = LinkedHashMap<String, ChatTurnView>()
    private val turnRevisions = HashMap<String, Long>()
    private var welcomeView: View? = null
    private var lastTurnCount = 0
    /**
     * V5 Phase B C2（批 2，第 3 步）：当前抽屉入口 id——路由状态的唯一
     * 来源，默认首页（新对话）。从设置页返回的上一个非设置入口记在
     * [lastEntry]。旧的 Tab int 状态（currentTab/lastTab/settingsVisible）
     * 已删除，不留两种状态源（方案 §1.2）。
     */
    private var currentEntry: String = DrawerController.ID_NEW_CONVERSATION
    private var lastEntry: String = DrawerController.ID_NEW_CONVERSATION
    /** 阶段 3（U3/批 3a）：任务详情浮层（复用设置浮层的宿主模式）。 */
    private var taskDetailVisible = false
    private var taskDetailTaskId: String? = null
    private var pendingTaskDetailId: String? = null
    /** 2026-11-11（事件密度）：任务屏“运行事件”分组的展开状态（刷新后不丢）。 */
    private val expandedEventGroupKeys = mutableSetOf<String>()
    private var showAllEventGroups = false
    private lateinit var taskDetailController: TaskDetailController
    /** 阶段 3（U5/批 3c）：会话列表（聊天 Tab 首屏）与是否在对话内。 */
    private lateinit var sessionListController: SessionListController
    private var inConversation = false
    /**
     * V5 Phase B C2（批 2）：抽屉模型与视图。定制经 Room 持久化
     * （[RoomDrawerPrefsStore]）；[drawerPrefsStore] 为 null 表示
     * Room 打不开，抽屉用默认 10 项继续跑，不崩溃、不删库。
     */
    private val drawerController = DrawerController()
    private var drawerView: DrawerView? = null
    private var drawerPrefsStore: RoomDrawerPrefsStore? = null
    private var drawerOpen = false
    private lateinit var drawerContainer: android.widget.FrameLayout
    private lateinit var drawerScrim: View

    /** V5 Phase B C2（批 2）：首页状态模型（问候开关、输入与发送状态机、+ 号菜单）。 */
    private val homeScreenController = HomeScreenController()
    /**
     * V5 Phase B C2（批 3，R3）：首次使用引导三屏。模型 + 视图在
     * ui/OnboardingController.kt 与 ui/OnboardingView.kt；这里只做
     * 接线（首次启动才出现、持久化、3/3 真实状态、ADB 引导动作）。
     * 引导不是抽屉入口，不进 EntryRegistry；设置页不加“重新查看
     * 引导”回看入口（后续批次 TODO，见 maybeShowOnboarding）。
     */
    private val onboardingController by lazy {
        OnboardingController(
            SharedPrefsOnboardingStore(getSharedPreferences(PREFS_ONBOARDING, MODE_PRIVATE)),
        )
    }
    private var onboardingView: OnboardingView? = null
    /**
     * 阶段 3（U5/批 3c）：LAN 发现 + 配对。V5 C1：配对记录已 Room 持久化
     * （BrainBootstrap.roomStoresOrMemory，经 BrainComponents.pairingTrustStore），
     * 重启后仍在。V5 加设备修复（2026-11-11）：不再按首次访问时大脑是
     * 否建好二选一缓存内存/ Room——大脑建好前配对先写内存，大脑建好
     * 后自动迁进 Room（见 MigratingPairingTrustStore）；旧写法首次
     * 访问即缓存内存，重启后配对全丢，是“添加设备不能用”的根因之一。
     */
    private val lanTransport by lazy { LanTransport(applicationContext) }
    private val pairingStore: PairingTrustStore by lazy {
        MigratingPairingTrustStore(resolvePersistent = { brainComponents?.pairingTrustStore })
    }
    private val pairingManager by lazy { PairingManager(pairingStore) }

    /**
     * 配对记录的内存快照（V5 修复，2026-11-11）：Room 默认禁止主线程
     * 查询（TaskDatabase.create 不开 allowMainThreadQueries），而对话
     * 路由判定跑在 UI 线程——旧代码在 UI 线程直接读 pairingStore，
     * Room 抛 IllegalStateException，被 resolveRemoteTarget 的
     * catch 当成“未配对”吞掉，于是同一台设备测试（后台线程）说可用、
     * 对话（UI 线程）说未配对。快照由后台线程刷新（见
     * refreshPairingSnapshot），UI 线程只读快照，不碰 Room。
     */
    private val pairingSnapshot = PairingSnapshotCache()
    private val brainLoading = AtomicBoolean(false)

    /**
     * 阶段 B（V5 紧急开发）：远端设备直连接线。
     *
     * - [remoteDeviceStore]：手动添加的远程设备（显示名/主机/端口）；
     * - [deviceIdentityStore]：本机设备身份（X25519 密钥对，私钥只进
     *   BrainKeyStore，见 DeviceIdentityStore）；
     * - [directTransport]：Tailnet 内明文 TCP 直连（无需 Tailscale SDK）；
     * - [remoteChatPort]：Hand 直连口（聊天/分派/控制共用）；
     * - [remoteControlSender]：ControlPlane 的远端控制投递；
     * - [remoteTaskPump]：排队里远程任务的认领 + 执行泵（组件加载后启动，
     *   onDestroy 停）。
     */
    private val remoteDeviceStore: RemoteDeviceStore by lazy {
        SharedPreferencesRemoteDeviceStore(applicationContext)
    }

    /**
     * 项目添加（V5 项目添加，2026-11-11）：项目 = 名 + 家（设备 + 文件夹），
     * 一个项目可以有多个家（主流模型：家的列表），源码留在家设备上，绝不克隆/复制
     * （复用老项目 device+project 模型，见 ProjectStore）。
     */
    private val projectStore: ProjectStore by lazy {
        SharedPreferencesProjectStore(applicationContext)
    }

    /**
     * git 家（2026-11-11）：家可以是 GitHub / GitLab / Gitee 仓库本身。
     * 授权存在本机 Keystore，用户自己在 App 里填，绝不经过聊天。
     * Agent 经 [GitHostProjectPort] 调该站 API 读/改/推分支/开 PR
     * （GitLab 叫合并请求）。
     */
    private val gitHostCredentialStore: GitHostCredentialStore by lazy {
        KeystoreGitHostCredentialStore.create(applicationContext)
    }
    private val gitHostProjectPort: GitHostProjectPort by lazy {
        GitHostProjectPort(credentialStore = gitHostCredentialStore)
    }

    /**
     * GitHub 闭环（2026-11-11）：聊天项目 = 最后在项目页打开的项目。
     * 只存项目 id（SharedPreferences `wherevercode_chat`，重启后仍记得）；
     * 新对话保留上次的项目（2026-11-11 修：之前新对话清空绑定，maintainer
     * 开新对话发测试消息时绑定被悄悄清掉，聊天里又没选项目入口，直接
     * 卡死；见 ChatProjectBinding.onNewConversation）。git 家的任
     * 务由大脑经该站 API 执行，见 [resolveChatGitHostProject]。
     */
    private val chatProjectBinding: ChatProjectBinding by lazy {
        ChatProjectBinding(
            load = {
                try {
                    getSharedPreferences("wherevercode_chat", MODE_PRIVATE)
                        .getString("chat_project_id", null)
                } catch (_: Exception) {
                    null
                }
            },
            save = { id ->
                try {
                    getSharedPreferences("wherevercode_chat", MODE_PRIVATE).edit()
                        .putString("chat_project_id", id).apply()
                } catch (_: Exception) {
                    // 存不住也不挡对话：内存里的值仍有效。
                }
            },
        )
    }

    /** 远端文件夹选择器（Hand list；列出失败时如实报错，不存假项目）。 */
    private val remoteFolderPicker: RemoteFolderPicker by lazy {
        RemoteFolderPicker(this) { target, relativePath, callback ->
            listRemoteFolderEntries(target, relativePath, callback)
        }
    }
    private val deviceIdentityStore by lazy {
        DeviceIdentityStore(object : KeyValueSecrets {
            override fun get(ref: String): String? = brainKeyStore.getSecret(ref)
            override fun put(ref: String, value: String) = brainKeyStore.putSecret(ref, value)
        })
    }
    private val directTransport by lazy { DirectTransport() }
    private val remoteChatPort: RemoteAgentChatPort by lazy {
        val identity = deviceIdentityStore.getOrCreate()
        HandRemoteAgentChatPort(
            transport = directTransport,
            ownDeviceId = identity.deviceId,
            ownKeyPair = DeviceKeys.KeyPair(identity.publicKey, identity.privateKey),
            trustChecker = pairingManager,
        )
    }
    private val remoteControlSender by lazy { HandRemoteControlSender(remoteChatPort) }
    private var remoteTaskPump: RemoteTaskPump? = null
    /**
     * P2：每任务最近一次机械检查结果（内存，重启即丢；审查 verdict 行
     * 里另有快照）。详情页“通过审查”须先有它且无 FAILED 才放行。
     */
    private val lastMechanicalByTask = HashMap<String, MechanicalCheckResult>()

    /**
     * 阶段 C（V5 紧急开发）：Manus 聊天桥接。Key 在调用时现取
     * （authRef → BrainKeyStore），绝不缓存、不记日志、不进记录；
     * 任务 id 持久化在 SharedPreferences（跟进同一任务用）。
     */
    private val manusChatPort: ManusChatPort by lazy {
        ManusChatBridge(
            adapter = ManusTaskAdapter(),
            apiKeyProvider = { agent -> agent.authRef?.let { brainKeyStore.getSecret(it) } },
            taskIdStore = SharedPreferencesManusTaskIdStore(applicationContext),
            // 回复落在非 assistant_message 字段时记一条诊断（不静默丢掉）。
            onDiagnostic = { android.util.Log.d("ManusChatBridge", it) },
        )
    }

    /** 阶段 C：该 Manus Agent 是否已配置 API Key（Keystore 里有值）。 */
    private fun isManusConfigured(agent: AgentRecord): Boolean =
        ManusTaskAdapter.isManusRecord(agent) &&
            agent.authRef?.let { brainKeyStore.getSecret(it) } != null

    /**
     * 平台 Agent 凭证存储（V5，2026-11-11）：App 的平台凭证（App 在
     * 平台上注册的 Agent 的 Bearer 令牌）+ 每平台 Agent 的 webhook
     * 签名密钥，全走本机 Keystore，绝不进记录、不进日志。
     */
    private val platformCredentialStore: PlatformAgentCredentialStore by lazy {
        PlatformAgentCredentialStore.create(applicationContext)
    }

    /** 云端 Agent 凭证存储（V5，2026-11-11）：每云端 Agent 一把随机 Bearer 钥匙，只存本机 Keystore。 */
    private val cloudCredentialStore: CloudAgentCredentialStore by lazy {
        CloudAgentCredentialStore.create(applicationContext)
    }

    /**
     * 平台 Agent 聊天桥接（V5，2026-11-11）。平台凭证在调用时现取
     * （Keystore 固定引用名），绝不缓存、不记日志、不进记录；只发
     * 任务消息，不传代码、不推代码。
     */
    private val platformChatPort: PlatformChatPort by lazy {
        PlatformAgentChatBridge(
            tokenProvider = { platformCredentialStore.loadPlatformToken() },
            cursorStore = SharedPreferencesPlatformReplyCursorStore(applicationContext),
        )
    }

    /** 该平台 Agent 是否已配置平台凭证（Keystore 里有 App 的平台凭证）。 */
    private fun isPlatformAgentConfigured(agent: AgentRecord): Boolean =
        PlatformAgent.isPlatformAgentRecord(agent) &&
            platformCredentialStore.loadPlatformToken() != null

    /**
     * 平台 Agent 连接探针（V5，2026-11-11）：只读探查平台频道一页
     * 消息，不发消息、不唤醒对方。调用方在后台线程调用（见
     * testAgentConnection）；401/403 单独报凭证被拒，其他失败报原因。
     */
    private fun probePlatformAgent(record: AgentRecord): PlatformProbeResult {
        val token = platformCredentialStore.loadPlatformToken()
            ?: return PlatformProbeResult.Unreachable("未配置平台凭证：请先在平台 Agent 表单的平台凭证栏填入 App 的平台凭证。")
        val channelId = PlatformAgent.effectiveChannelId(record.platformChannelId)
        val transport = PlatformChannelMcpTransport(
            tokenProvider = { token },
            endpoint = PlatformAgent.mcpEndpoint(record.platformHost),
        )
        return try {
            kotlinx.coroutines.runBlocking { transport.pollMessages(channelId, null, 1) }
            PlatformProbeResult.ChannelReadable
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if ("401" in message || "403" in message) PlatformProbeResult.Unauthorized
            else PlatformProbeResult.Unreachable(message.ifBlank { "未知错误" }.take(200))
        }
    }

    /**
     * 阶段 B：由 Agent 记录找到它的远端目标（设备已配对才返回，否则 null）。
     *
     * V5 修复（2026-11-11）：配对记录改读内存快照 [pairingSnapshot]，
     * 不再在 UI 线程直读 [pairingStore]。根因——Room 默认禁止主线程
     * 查询（TaskDatabase.create 不开 allowMainThreadQueries），而本
     * 方法经横幅/发送跑在 UI 线程：旧代码的 pairingStore.load 在 UI
     * 线程抛 IllegalStateException，被这里的 catch 当成“未配对”吞
     * 掉；同一台设备，测试在后台线程读库能通过，对话在 UI 线程读库
     * 永远失败。快照由后台线程刷新（refreshPairingSnapshot）。
     * 另：旧代码的 pairingManager.isTrusted 二次读库是空转——它拿
     * 记录里的指纹跟记录自己比，恒真；本轮改判对方公钥非空（建
     * HandClient 真正需要的条件，与 blockedReason 口径统一），判定
     * 本身见纯函数 resolveRemoteTargetFromRecords。
     *
     * V5 跨设备开发（2026-11-12，A3）：[projectId] 非空时派活文件夹
     * 取该项目在目标设备上的检出文件夹（[resolveDispatchFolderForProject]）；
     * 无检出时回落 Agent 文件夹并在 target.folderSource 上标明。
     * projectId 为空时即今日行为（Agent 文件夹）。调用方传任务时传
     * `task.projectId`；只有当前聊天绑定可用时（如远端聊天轮次）传
     * 绑定。注意：这里不自动取当前聊天绑定——浏览文件夹等非执行用途
     * 也走本函数，自动取绑定会把浏览的相对路径算错；绑定只由各调用
     * 方显式传入。
     */
    private fun resolveRemoteTarget(agent: AgentRecord, projectId: String? = null): RemoteTarget? {
        val deviceId = agent.deviceId ?: return null
        val device = try {
            remoteDeviceStore.getDevice(deviceId)
        } catch (_: Exception) {
            null
        } ?: return null
        val record = pairingSnapshot.load(deviceId)
        val folderOverride = try {
            when (val result = resolveDispatchFolderForProject(projectStore, projectId, agent)) {
                is DispatchFolderResult.Resolved -> result.folder
                // D7 的“不可派”只由任务泵执行（留 queued + needs_attention）；
                // 聊天/流水线/扇出/中转等无任务可留队的调用方仍回落
                // Agent 文件夹（今日行为），不把对话也拦下来。
                is DispatchFolderResult.NotDispatchable -> null
                null -> null
            }
        } catch (_: Exception) {
            null
        }
        val target = resolveRemoteTargetFromRecords(agent, device, record, folderOverride)
        if (target == null) {
            Log.d(
                "RemoteTarget",
                "resolveRemoteTarget null: agent=${agent.agentId} deviceId=$deviceId " +
                    "pairingState=${record?.state} " +
                    "peerKey=${record?.peerPublicKey != null} " +
                    "folderBlank=${agent.deviceFolder.isNullOrBlank()}",
            )
        }
        return target
    }

    /** 当前聊天绑定的项目 id（取不到时 null；调用方传给解析函数作 projectId）。 */
    private fun currentChatProjectId(): String? = try {
        chatViewModel.currentProjectId()
    } catch (_: Exception) {
        null
    }

    /** 阶段 B：该 Agent 是否已接入远端（路由判定用，与 resolveRemoteTarget 同口径）。 */
    private fun isRemoteAgent(agent: AgentRecord): Boolean = resolveRemoteTarget(agent) != null

    /**
     * 后台刷一遍配对快照（V5 修复，2026-11-11）。Room I/O 只能在后台
     * 线程做；刷完回到 UI 线程刷新一次路由横幅，让“未接入”在配对
     * 实际已就绪时自己翻过来。任何线程都可调。
     */
    private fun refreshPairingSnapshot() {
        Thread {
            try {
                pairingSnapshot.refresh(pairingStore)
            } catch (_: Exception) {
                return@Thread
            }
            // 2026-11-11 真机根因：旧存储记录没有 capabilities 键，
            // projectHost 缺省 false 会把真能托管的设备挡在加项目门外。
            // 已配对 CONFIRMED 的设备就是一台 Hand 服务端（配对只在
            // hello 成功后确认），给它补上 Hand 能力位，让存储收敛到
            // 真实。回填失败不影响快照本身。
            try {
                backfillHandCapabilities()
            } catch (_: Exception) {
                // 忽略：下次刷新时再试。
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateChatRouteBanner()
            }
        }.start()
    }

    /**
     * 回填 Hand 能力位（2026-11-11 真机根因，K70）：存储记录是全缺省
     * 能力位（没有 capabilities 键）但配对已 CONFIRMED 的设备，补上
     * [handServerCapabilities]。只补已确认配对的——未配对/待确认/已拒
     * 绝的设备不动，不伪造能力。
     */
    private fun backfillHandCapabilities() {
        val devices = try {
            remoteDeviceStore.listDevices()
        } catch (_: Exception) {
            return
        }
        for (device in devices) {
            if (device.capabilities != HostCapabilities()) continue
            val record = pairingSnapshot.load(device.deviceId) ?: continue
            if (record.state != PairingState.CONFIRMED) continue
            val updated = device.copy(
                capabilities = handServerCapabilities(),
                protocolCapabilities = handServerProtocolCapabilities(),
            )
            try {
                remoteDeviceStore.saveDevice(updated)
            } catch (_: Exception) {
                // 保留旧记录：门已不靠静态位，下次刷新时再试。
            }
        }
    }

    /**
     * 远端 Agent 路由报“未接入”后的后台重验（V5 修复，2026-11-11）。
     * 对话判定只信本地快照；快照若因启动窗口/陈旧而缺记录，即使用户
     * 的配对实际在库里，发送也会被拒。本方法用测试同款探针
     * （probeRemoteDeviceAgent：读库 + live hello）重验一次，并刷
     * 新快照：探针说 Available 且路由翻转为可达时，刷新横幅并提示
     * 用户重新发送。探针只读不写，不新建任何信任；配对记录缺失时
     * verdict 不变，用户仍须先走配对流程。
     */
    private fun reverifyRemoteAgent(agent: AgentRecord) {
        Thread {
            val probe = try {
                probeRemoteDeviceAgent(agent)
            } catch (_: Exception) {
                null
            }
            try {
                pairingSnapshot.refresh(pairingStore)
            } catch (_: Exception) {
                // 快照失败不影响重验结论；下次刷新/发送时再试。
            }
            if (probe is DeviceProbeResult.Available) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (isRemoteAgent(agent)) {
                        updateChatRouteBanner()
                        toast("已重新核对：${agent.displayName} 已接入，可以重新发送。")
                    }
                }
            }
        }.start()
    }

    /**
     * 阶段 B：由分派任务的 assignedAgentId 找到它的远端目标（ControlPlane/泵共用）。
     * [task] 非空时按任务的 projectId 解析派活文件夹（V5 跨设备开发，
     * 2026-11-12，A3）；为空时沿用 Agent 文件夹。ControlPlane 与流水线
     * 调用方无任务上下文，传空即今日行为（控制指令按 task_id 找在途执行，
     * folder 只须是已授权文件夹）。
     */
    private fun resolveRemoteTargetForMember(agentId: String?, task: TaskRecord? = null): RemoteTarget? {
        if (agentId == null) return null
        val agent = try {
            agentStore.getAgent(agentId)
        } catch (_: Exception) {
            null
        } ?: return null
        return resolveRemoteTarget(agent, task?.projectId)
    }

    /** 阶段 B：该成员是否属于远程设备（泵的取活判定用）。 */
    private fun isRemoteMember(agentId: String): Boolean {
        return try {
            agentStore.getAgent(agentId)?.deviceId != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * P2 自动执行（2026-11-11）：远端审查者候选——agentStore 里已配对
     * 设备、且能解析到远端目标的 Agent。解析不到目标的不进候选（审查
     * 者选不到它，runner 如实报无审查者可用，不编一个）。本机大脑候选
     * 由 BrainBootstrap 从已配置大脑里取，不在这里。
     */
    private fun pipelineRemoteReviewerCandidates(): List<PipelineOrchestrator.ReviewerCandidate> {
        val out = ArrayList<PipelineOrchestrator.ReviewerCandidate>()
        try {
            agentStore.listAgents().forEach { agent ->
                // Agent 开关（2026-11-12）：停用的 Agent 不做审查者候选。
                if (!agent.enabled) return@forEach
                val deviceId = agent.deviceId ?: return@forEach
                if (resolveRemoteTargetForMember(agent.agentId) == null) return@forEach
                out += PipelineOrchestrator.ReviewerCandidate(
                    agentId = agent.agentId,
                    deviceId = deviceId,
                    label = agent.displayName,
                )
            }
        } catch (_: Exception) {
            // 候选读失败：只剩本机大脑候选，不杀 runner。
        }
        return out
    }

    private val themePrefs by lazy { getSharedPreferences("ui_v2_theme", MODE_PRIVATE) }
    private val brainNamePrefs by lazy { getSharedPreferences(BrainDisplayName.PREFS_FILE, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyStoredTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // V5 按主流改（maintainer 2026-11-11）：“+”菜单附件选择器与相机权限。
        // 必须在 onCreate 里注册（Activity 结果回调的注册点）。
        registerAttachmentLaunchers()
        webSearchEnabled = chatPrefs.getBoolean(KEY_WEB_SEARCH_ENABLED, false)
        bindShell()
        inflateScreens()
        bindChat()
        bindProjects()
        bindTasks()
        bindAgents()
        bindSettings()
        bindDrawer()
        loadDrawerPrefs()
        // GitHub 闭环（2026-11-11）：恢复上次的聊天项目（重启后仍记得）。
        loadChatProjectId()

        runtime = RuntimeEnv(applicationContext)
        runner = AgentRunner(applicationContext, runtime)
        toolApprovalGate.attach(this)
        startService(Intent(this, AgentService::class.java))

        chatViewModel = ViewModelProvider(this)[ChatViewModel::class.java]
        chatViewModel.attachRunner(runner)
        // git 家（2026-11-11）：聊天项目的 git 家提供方；新对话
        // 保留上次的项目绑定（ChatProjectBinding.onNewConversation 故意
        // 不清空，之前清掉是 bug，见 ChatProjectBinding）。
        chatViewModel.gitHostProjectRefProvider = ::resolveChatGitHostRef
        // V5 跨设备开发（2026-11-12，派活丢绑定根因）：当前聊天绑定的
        // 项目 id（全局聊天绑定）。会话级绑定缺失时 currentProjectId()
        // 回落到它，派活才带得上项目；两个都没有时返回 null（走回落）。
        chatViewModel.chatProjectIdProvider = { chatProjectBinding.projectId }
        // V5 跨设备开发（2026-11-12，D4）：远端轮次回落时时间线备注的
        // 项目名来源；查不到时 ViewModel 用项目 id，未绑定时写“未绑定项目”。
        chatViewModel.projectNameProvider = { projectId ->
            try {
                projectStore.getProject(projectId)?.name
            } catch (_: Exception) {
                null
            }
        }
        chatViewModel.onNewConversation = { chatProjectBinding.onNewConversation() }
        // V5 C2-3-R4c：大脑显示名（默认 WhereverCode）同步到默认会话标签
        // 与会话列表行名；设置页行值在 bindSettings 里已绑，这里只同步标签。
        applyBrainDisplayName()
        // 阶段 3（U1）：默认会话快照——轮次结束时保存，启动时恢复。
        chatViewModel.onTurnFinished = {
            if (chatViewModel.isDefaultSession()) {
                chatHistoryStore.save(chatViewModel.defaultTurns())
                // V5 C3 chat-bugfix：记下默认会话 id，重启后从 Room 恢复
                // 完整历史；快照是回退（修前建的对话没有 id）。
                chatViewModel.currentSessionId()?.let { chatHistoryStore.saveCurrentSessionId(it) }
            }
        }
        // 主流补齐（2026-11-11）：会话 → 项目绑定持久化。
        // 绑定变化时写 ChatHistoryStore；启动时恢复（会话切回来还能看到它挂的项目）。
        chatViewModel.onSessionProjectsChanged = { bindings ->
            chatHistoryStore.saveSessionProjects(bindings)
        }
        chatViewModel.restoreSessionProjects(chatHistoryStore.loadSessionProjects())
        // 大脑/Agent 边界（maintainer 2026-11-12）：会话 → 直连 Agent 开关持久化。
        // 开关变化时写 ChatHistoryStore；启动时恢复（会话切回来开关还在）。
        chatViewModel.onDirectAgentModeChanged = { flags ->
            chatHistoryStore.saveDirectAgentSessions(flags)
        }
        chatViewModel.restoreDirectAgentSessions(chatHistoryStore.loadDirectAgentSessions())
        chatViewModel.state.observe(this) { state ->
            renderChat(state)
            // V5 C2（批 2）：轮次到终态时才 finishSend（方案 §1.5 的决定：
            // 不在 beginSend 后立即 finishSend，避免“发送中”一闪而过；
            // 输入框早已在发送接受时清空，这里只同步模型状态，并把
            // 发送中用户又敲的字（仍在输入框里）写回模型，不丢字）。
            if (homeScreenController.state().isSending && !state.running) {
                homeScreenController.finishSend()
                homeScreenController.setInput(inputPrompt.text.toString())
                updateSendButton()
            }
            updateTaskStatus(state)
            maybeRefreshTasks(state)
        }
        // V5 C2（批 2，第 3 步）：旋转恢复当前抽屉入口（String）+ 是否在
        // 对话内；进程被杀后重开时 savedInstanceState 为空，自然落首页。
        val startEntry = savedInstanceState?.getString(KEY_ENTRY)
            ?: DrawerController.ID_NEW_CONVERSATION
        inConversation = savedInstanceState?.getBoolean(KEY_IN_CONVERSATION, false) ?: false
        pendingTaskDetailId = savedInstanceState?.getString(KEY_TASK_DETAIL)
        showEntry(startEntry)
        loadBrainComponents()
        maybeShowOnboarding(savedInstanceState?.getString(KEY_ONBOARDING_STEP))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_ENTRY, currentEntry)
        outState.putBoolean(KEY_IN_CONVERSATION, inConversation)
        taskDetailTaskId?.let { outState.putString(KEY_TASK_DETAIL, it) }
        onboardingView?.let { outState.putString(KEY_ONBOARDING_STEP, onboardingController.step().name) }
        super.onSaveInstanceState(outState)
    }

    // ------------------------------------------------------------------
    // Shell / navigation
    // ------------------------------------------------------------------

    private fun bindShell() {
        screenContainer = findViewById(R.id.screenContainer)
        drawerContainer = findViewById(R.id.drawerContainer)
        drawerScrim = findViewById(R.id.drawerScrim)
        drawerScrim.setOnClickListener { closeDrawer() }
        findViewById<View>(R.id.buttonMenu).setOnClickListener { openDrawer() }
        // V5 C2-3-R1c：顶栏搜索（v4.3-01）→ 会话（真实入口）。
        findViewById<View>(R.id.buttonSearch).setOnClickListener {
            showEntry(DrawerController.ID_HISTORY_SEARCH)
        }
        // V5 C2-3-R4 (v4.3-01)：铃铛只有图标、没有红点——App 内没有通知
        // 功能，红点是伪造状态；点按只给“通知中心待开发”提示，绝不编
        // 造通知数。大脑 pill 与“新建”已删：大脑状态在设置页大脑分节
        // （refreshBrainStatus 写 brainStatus），新对话走抽屉“对话”
        // 入口（onDrawerEntrySelected 的 nav_chat 分支调 newConversationAndShow）。
        findViewById<View>(R.id.buttonBell).setOnClickListener {
            toast("通知中心待开发")
        }
    }

    // ------------------------------------------------------------------
    // Drawer (V5 Phase B C2, batch 2)
    // ------------------------------------------------------------------

    private fun bindDrawer() {
        drawerView = DrawerView(
            this,
            drawerController,
            onEntrySelected = { id -> onDrawerEntrySelected(id) },
            onPrefsChanged = { saveDrawerPrefs() },
            activeTargetProvider = { currentEntry },
            userLabelProvider = { drawerUserLabel() },
            themeLabelProvider = { currentThemeLabel() },
        ).also { view ->
            drawerContainer.addView(view.root)
        }
        drawerContainer.visibility = View.GONE
        drawerContainer.post {
            drawerContainer.translationX = -drawerContainer.width.toFloat()
        }
    }

    /**
     * 后台读抽屉定制：读到之前先渲染默认 10 项，读到后再
     * applyPrefs() + 刷新抽屉（定制晚到可接受，不得为等持久化阻塞
     * 首屏）。Room 打不开时用默认 10 项继续跑，当次定制不持久化。
     *
     * 注意：这里单独建一个 TaskDatabase 实例。BrainComponents 不
     * 暴露数据库（BrainBootstrap 是 C1 文件，批 2 不碰）；同一进程
     * 内多个 Room 实例共用一个库文件是 Room 支持的做法，抽屉定制
     * 只经过这一个实例读写，不与大脑实例的缓存互相干扰。
     */
    private fun loadDrawerPrefs() {
        Thread {
            val store = try {
                RoomDrawerPrefsStore(TaskDatabase.create(applicationContext))
            } catch (_: Exception) {
                null
            }
            val prefs = try {
                store?.load()
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (store != null && prefs != null) {
                    drawerPrefsStore = store
                    drawerController.applyPrefs(prefs)
                }
                drawerView?.refresh()
            }
        }.start()
    }

    private fun saveDrawerPrefs() {
        val store = drawerPrefsStore ?: return
        val prefs = drawerController.toPrefs()
        Thread {
            try {
                store.save(prefs)
            } catch (_: Exception) {
                // 下一次定制再重试；抽屉在内存模型上继续跑，绝不崩溃。
            }
        }.start()
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        drawerView?.refresh()
        refreshDrawerBadges()
        drawerContainer.visibility = View.VISIBLE
        drawerScrim.visibility = View.VISIBLE
        drawerScrim.alpha = 0f
        drawerContainer.post {
            drawerContainer.animate().translationX(0f).setDuration(220).start()
        }
        drawerScrim.animate().alpha(1f).setDuration(220).start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        drawerScrim.animate().alpha(0f).setDuration(200).start()
        drawerContainer.animate()
            .translationX(-drawerContainer.width.toFloat())
            .setDuration(200)
            .withEndAction {
                if (!drawerOpen) {
                    drawerContainer.visibility = View.GONE
                    drawerScrim.visibility = View.GONE
                }
            }
            .start()
    }

    /**
     * V5 Phase B C2（批 3，R2）：抽屉入口点按——入口 id 是注册表 id
     * （见 [EntryRegistry]），不再是旧扁平抽屉的 10 项 id。
     * 待开发：只 toast 提示，不关抽屉、不伪装已实现。已实现：对话
     * （→新对话动作，建新对话再回首页）；“界面与主题”走
     * [openRegistryEntry] 的 ACTION 分支调 cycleTheme()；其余按
     * 注册表目标路由（见 showEntry）。注册表外的控制器 id（嵌套
     * 的自定义入口）按旧路由兜底。
     */
    private fun onDrawerEntrySelected(registryId: String) {
        val entry = EntryRegistry.find(registryId)
        if (entry == null) {
            closeDrawer()
            if (registryId == DrawerController.ID_NEW_CONVERSATION) {
                newConversationAndShow()
            } else {
                showEntry(registryId)
            }
            return
        }
        if (entry.status == EntryRegistry.Status.TODO) {
            toast(entry.todoMessage ?: "${entry.title}待开发")
            return
        }
        if (registryId == "nav_chat") {
            closeDrawer()
            newConversationAndShow()
            return
        }
        closeDrawer()
        openRegistryEntry(registryId)
    }

    /**
     * 顶栏“新建”与抽屉“新对话”共用：运行中拒绝并 toast（与
     * newConversationFromList 同款文案；ChatViewModel 也会在聊天里
     * 写一条系统说明）。成功才 reset 首页状态并回首页。
     */
    private fun newConversationAndShow() {
        if (chatViewModel.newConversation()) {
            // V5 C3 chat-bugfix：新对话不再是旧默认会话，旧 id 作废。
            chatHistoryStore.clearCurrentSessionId()
            homeScreenController.reset()
            inConversation = true
            showEntry(DrawerController.ID_NEW_CONVERSATION)
        } else {
            offerForceNewConversation()
        }
    }

    /**
     * V5 修复（2026-11-11，CodeBuddy 卡死轮次）：新对话被“上一轮
     * 还在执行”拦住时，给用户“强制开新对话”逃生口——旧轮次卡死
     * 也不让用户永远开不了新对话（ViewModel 的 forceNewConversation
     * 会取消在跑 job、掐断远端连接、清掉轮次状态）。
     */
    private fun offerForceNewConversation() {
        AlertDialog.Builder(this)
            .setTitle("上一轮还在执行")
            .setMessage("上一轮还没结束。如果它已经卡住（长时间没反应、停止也不管用），可以强制结束它并开新对话；旧轮次会被丢弃。")
            .setPositiveButton("强制开新对话") { _, _ ->
                if (chatViewModel.forceNewConversation()) {
                    chatHistoryStore.clearCurrentSessionId()
                    homeScreenController.reset()
                    inConversation = true
                    showEntry(DrawerController.ID_NEW_CONVERSATION)
                } else {
                    toast("强制开新对话失败，请重试。")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Onboarding (V5 Phase B C2, batch 3, R3)
    // ------------------------------------------------------------------

    /**
     * 首次启动才出现引导三屏（遮罩盖在整个壳之上，含顶栏与抽屉）；
     * 已完成过的用户直接进聊天首页。旋转重建时按 savedInstanceState
     * 恢复当前屏（方向已随选随存，不丢）。
     * TODO（后续批次）：设置页加“重新查看引导”回看入口；本步只留
     * 首次自动出现，不做回看入口。
     */
    private fun maybeShowOnboarding(savedStep: String?) {
        if (!onboardingController.shouldShow() || onboardingView != null) return
        savedStep?.let { name ->
            runCatching { OnboardingController.Step.valueOf(name) }.getOrNull()?.let {
                onboardingController.setStep(it)
            }
        }
        val view = OnboardingView(
            this,
            onboardingController,
            readinessProvider = { onboardingReadiness() },
            onFinished = { hideOnboarding() },
            onAdbGuide = { openAdbGuideSettings() },
            onImportRestore = { toast(OnboardingController.IMPORT_TODO) },
        )
        onboardingView = view
        findViewById<ViewGroup>(android.R.id.content).addView(view.root)
    }

    private fun hideOnboarding() {
        val view = onboardingView ?: return
        onboardingView = null
        (view.root.parent as? ViewGroup)?.removeView(view.root)
        showEntry(DrawerController.ID_NEW_CONVERSATION)
    }

    /**
     * 3/3 三行状态（诚实三态；没有检测手段的一律 NOT_CHECKED）。
     * - 大脑：大脑组件未加载完 → 未检测；否则按当前大脑是否已配置
     *   （与 refreshBrainStatus 同一判定：isBrainConfigured）。
     * - 基础执行环境：工作区目录可写探针（mkdirs + canWrite；只证
     *   明工作区可写，Shell/Python/Node 的逐项探针是后续批次），
     *   探针异常或不可写 → 未检测，绝不写死可用。
     * - 设备连接：大脑未加载 → 未检测；否则已确认配对 ≥1 条 → 可用，
     *   否则未配置（配对读失败 → 未检测）。
     */
    private fun onboardingReadiness(): OnboardingController.ReadinessRows {
        val components = brainComponents
            ?: return OnboardingController.ReadinessRows(
                OnboardingController.Readiness.NOT_CHECKED,
                OnboardingController.Readiness.NOT_CHECKED,
                OnboardingController.Readiness.NOT_CHECKED,
            )
        val currentBrainId = components.configStore.currentBrainId()
        val brain = if (currentBrainId != null && components.isBrainConfigured(currentBrainId)) {
            OnboardingController.Readiness.AVAILABLE
        } else {
            OnboardingController.Readiness.NOT_CONFIGURED
        }
        val execEnv = try {
            val dir = java.io.File(filesDir, "workspace").apply { mkdirs() }
            if (dir.isDirectory && dir.canWrite()) {
                OnboardingController.Readiness.AVAILABLE
            } else {
                OnboardingController.Readiness.NOT_CHECKED
            }
        } catch (_: Exception) {
            OnboardingController.Readiness.NOT_CHECKED
        }
        val device = try {
            val confirmed = pairingStore.all().any { it.state == PairingState.CONFIRMED }
            if (confirmed) {
                OnboardingController.Readiness.AVAILABLE
            } else {
                OnboardingController.Readiness.NOT_CONFIGURED
            }
        } catch (_: Exception) {
            OnboardingController.Readiness.NOT_CHECKED
        }
        return OnboardingController.ReadinessRows(brain, execEnv, device)
    }

    /**
     * 2/3 “一键引导设置”：只做引导动作——打开系统开发者设置，让
     * 用户按三步开启无线调试；点按后留在 2/3 屏（视图不推进），
     * 不声称已配对。真正的无线配对接线是 C3/A1 的活。
     * 打不开开发者设置时退到系统设置页，再打不开只给提示。
     */
    private fun openAdbGuideSettings() {
        val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
        try {
            startActivity(devIntent)
            toast("已打开开发者设置：按上面三步开启无线调试；无线配对接线待开发")
        } catch (_: Exception) {
            try {
                startActivity(fallbackIntent)
                toast("开发者设置打不开，已打开系统设置：请手动进开发者选项开启无线调试")
            } catch (_: Exception) {
                toast("打不开系统设置：请手动进设置 → 开发者选项，开启无线调试")
            }
        }
    }

    /** 引导完成态 + 方向 id 的 SharedPreferences 实现（最轻，不新建 Room 表）。 */
    private class SharedPrefsOnboardingStore(
        private val prefs: SharedPreferences,
    ) : OnboardingController.Store {
        override fun isCompleted(): Boolean = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        override fun setCompleted() {
            prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
        }
        override fun directionId(): String? = prefs.getString(KEY_ONBOARDING_DIRECTION, null)
        override fun setDirectionId(id: String?) {
            prefs.edit().putString(KEY_ONBOARDING_DIRECTION, id).apply()
        }
    }

    private fun inflateScreens() {
        val inflater = LayoutInflater.from(this)
        val container = screenContainer as android.widget.FrameLayout
        screenChat = inflater.inflate(R.layout.screen_chat, container, false)
        screenProjects = inflater.inflate(R.layout.screen_projects, container, false)
        screenTasks = inflater.inflate(R.layout.screen_tasks, container, false)
        screenAgents = inflater.inflate(R.layout.screen_agents, container, false)
        screenSettings = inflater.inflate(R.layout.screen_settings, container, false)
        container.addView(screenChat)
        container.addView(screenProjects)
        container.addView(screenTasks)
        container.addView(screenAgents)
        container.addView(screenSettings)
        taskDetailController = TaskDetailController(
            this,
            onBack = { hideTaskDetail() },
            onOpenFile = { path -> openDetailFile(path) },
            onRefresh = { taskDetailTaskId?.let { openTaskDetail(it) } },
            controlsProvider = { task -> taskControls(task) },
        )
        container.addView(taskDetailController.root)
        taskDetailController.root.visibility = View.GONE
        sessionListController = SessionListController(
            this,
            onOpenDefault = { openDefaultConversation() },
            onOpenSession = { sessionId, label -> openSession(sessionId, label) },
            onNewConversation = { newConversationFromList() },
            // V5 C3 chat-bugfix：删除入口（二次确认见 confirmDeleteSession）。
            onDeleteSession = { sessionId, label -> confirmDeleteSession(sessionId, label) },
            onDeleteDefault = { confirmDeleteDefaultConversation() },
        )
        container.addView(sessionListController.root)
        sessionListController.root.visibility = View.GONE
        resultsController = ResultsScreenController(
            this,
            onOpenFile = { path -> openDetailFile(path) },
            onOpenTasks = { showEntry(DrawerController.ID_TASKS) },
        )
        screenResults = resultsController.root
        container.addView(screenResults)
        screenResults.visibility = View.GONE
        screenHelp = buildHelpPage()
        container.addView(screenHelp)
        screenHelp.visibility = View.GONE
        consoleController = ConsoleScreenController(
            this,
            onOpenTask = { taskId -> openTaskDetail(taskId) },
            onOpenFile = { path -> openDetailFile(path) },
            onOpenChat = { showEntry(DrawerController.ID_NEW_CONVERSATION) },
            onOpenDevices = { showEntry(DrawerController.ID_DEVICES) },
            onOpenAgents = { showEntry(DrawerController.ID_AGENTS_TEAM) },
            onRefresh = { refreshConsole() },
        )
        screenConsole = consoleController.root
        container.addView(screenConsole)
        screenConsole.visibility = View.GONE
        mcpPluginController = McpPluginScreenController(
            this,
            onToggleServer = { serverId, enabled -> toggleMcpServer(serverId, enabled) },
            onConnectServer = { serverId -> connectMcpServer(serverId) },
            onTogglePlugin = { pluginId, enabled -> togglePlugin(pluginId, enabled) },
            onRefresh = { refreshMcpPlugins() },
            onEditServer = { serverId -> editMcpServer(serverId) },
            onDeleteServer = { serverId -> deleteMcpServer(serverId) },
            onSaveServer = { form, existingId -> saveMcpServer(form, existingId) },
            onImportServers = { json -> importMcpServers(json) },
        )
        screenMcpPlugins = mcpPluginController.root
        container.addView(screenMcpPlugins)
        screenMcpPlugins.visibility = View.GONE
        teamChannelController = TeamChannelController(
            this,
            onOpenChatWithAgent = { agentId ->
                agentStore.getAgent(agentId)?.let { openAgentSession(it) }
            },
            onPost = { text -> postTeamChannelMessage(text) },
            onMarkRead = { markTeamChannelRead() },
            onNewRound = {
                teamChannelRoundsUsed = 0
                refreshTeamChannel()
            },
            onStartFanout = { question -> startTeamFanout(question) },
            onRefresh = { refreshTeamChannel() },
        )
        screenTeamChannel = teamChannelController.root
        container.addView(screenTeamChannel)
        screenTeamChannel.visibility = View.GONE
        screenChat.visibility = View.VISIBLE
        screenProjects.visibility = View.GONE
        screenTasks.visibility = View.GONE
        screenAgents.visibility = View.GONE
        screenSettings.visibility = View.GONE
    }

    /**
     * V5 C2（批 2，第 3 步）：静态帮助页。只写已实现功能的用法；
     * “教我使用”交互式导师留批 3，这里只是一段静态文本。
     */
    private fun buildHelpPage(): View {
        val scroll = ScrollView(this)
        val root = UiKit.vertical(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(root)
        root.addView(UiKit.text(this, "帮助", 20f, R.color.text_primary, bold = true))
        val sections = listOf(
            "聊天" to "在首页输入框发一句话，${brainDisplayName()}会推理并调用工具。写文件与命令执行前会弹审批，通过才执行；拒绝或界面销毁后授权立即作废。",
            "暂停 / 停止 / 改目标" to "运行中的轮次可在聊天页控制行点“暂停/停止”，或在任务页点“暂停/停止/改目标”。停止会二次确认；停止后本轮不会自动续跑。",
            "抽屉" to "点左上角 ☰ 打开抽屉：对话、项目、团队、设备、工具与连接、控制台，工作空间与扩展与资源分组里有工作台与设置、界面与主题、运行中的任务、待审批、成果、帮助与 MCP / 插件。运行中任务与待审批的角标是真实数量，没有就不显示；标“待开发”的入口点按只给提示。长按已实现的入口可隐藏或收藏。",
            "项目" to "项目页可浏览工作区文件、查看与编辑代码（保存前二次确认，可撤销/保留）、问 Agent。终端页未实现，不在项目页里伪装。",
            "Agent 与团队" to "Agent 页可添加云端模型 API、订阅授权 Agent、本机 CLI Agent、设备上的 Agent、平台 Agent（webhook 唤醒）或云端 Agent（MCP 连接器），可对话、指派、测试、编辑、移除。设备管理在独立的设备页；未配对设备标“不可用”。",
            "教我使用" to "交互式导师尚未实现。先按上面几节用；遇到问题回首页直接问${brainDisplayName()}。",
        )
        sections.forEach { (title, body) ->
            root.addView(
                UiKit.text(this, title, 14f, R.color.text_primary, bold = true).apply {
                    setPadding(0, dp(16), 0, dp(4))
                },
            )
            root.addView(UiKit.text(this, body, 12f, R.color.text_secondary))
        }
        return scroll
    }

    /**
     * V5 Phase B C2（批 2，第 3 步）：抽屉路由表（唯一事实来源见
     * [DrawerRouter]）。各屏控制器本体一字不动，只换挂载点；
     * 路由时执行对应屏的刷新动作（原 showTab() 的 when 分支原样平移）。
     * 未知 id 路由到首页，不崩溃。路由前先关任务详情浮层（原语义）。
     * 注意：showEntry(new_conversation) 只显示首页聊天屏，不新建对话；
     * 建新对话是 newConversationAndShow() 的动作（抽屉“新对话”点按、
     * 顶栏“新建”按钮、会话列表“新对话”各走自己的 newConversation()）。
     */
    private fun showEntry(entryId: String) {
        hideTaskDetail()
        val screen = DrawerRouter.screenFor(entryId)
        val entry = when (screen) {
            EntryScreen.HOME -> DrawerController.ID_NEW_CONVERSATION
            EntryScreen.HISTORY -> DrawerController.ID_HISTORY_SEARCH
            EntryScreen.PROJECTS -> DrawerController.ID_PROJECTS
            EntryScreen.TASKS -> DrawerController.ID_TASKS
            EntryScreen.AGENTS -> DrawerController.ID_AGENTS_TEAM
            EntryScreen.DEVICES -> DrawerController.ID_DEVICES
            EntryScreen.TOOLS -> DrawerController.ID_TOOLS_CONNECTIONS
            EntryScreen.RESULTS -> DrawerController.ID_RESULTS
            EntryScreen.HELP -> DrawerController.ID_HELP
            EntryScreen.SETTINGS -> DrawerController.ID_SETTINGS
            EntryScreen.CONSOLE -> DrawerController.ID_CONSOLE
            EntryScreen.MCP_PLUGINS -> DrawerController.ID_MCP_PLUGINS
            EntryScreen.TEAM_CHANNEL -> DrawerController.ID_TEAM_CHANNEL
        }
        if (!isSettingsEntry(entry)) lastEntry = entry
        currentEntry = entry
        screenChat.visibility = if (screen == EntryScreen.HOME) View.VISIBLE else View.GONE
        screenProjects.visibility = if (screen == EntryScreen.PROJECTS) View.VISIBLE else View.GONE
        screenTasks.visibility = if (screen == EntryScreen.TASKS) View.VISIBLE else View.GONE
        screenAgents.visibility =
            if (screen == EntryScreen.AGENTS || screen == EntryScreen.DEVICES) View.VISIBLE else View.GONE
        if (screen == EntryScreen.AGENTS || screen == EntryScreen.DEVICES) {
            configureAgentScreenForDevices(screen == EntryScreen.DEVICES)
        }
        screenSettings.visibility =
            if (screen == EntryScreen.SETTINGS || screen == EntryScreen.TOOLS) View.VISIBLE else View.GONE
        sessionListController.root.visibility =
            if (screen == EntryScreen.HISTORY) View.VISIBLE else View.GONE
        screenResults.visibility = if (screen == EntryScreen.RESULTS) View.VISIBLE else View.GONE
        screenHelp.visibility = if (screen == EntryScreen.HELP) View.VISIBLE else View.GONE
        screenConsole.visibility = if (screen == EntryScreen.CONSOLE) View.VISIBLE else View.GONE
        screenMcpPlugins.visibility = if (screen == EntryScreen.MCP_PLUGINS) View.VISIBLE else View.GONE
        screenTeamChannel.visibility = if (screen == EntryScreen.TEAM_CHANNEL) View.VISIBLE else View.GONE
        // 离开控制台即停自动刷新（不空转耗电）；进入时由 CONSOLE 分支起 ticker。
        if (screen != EntryScreen.CONSOLE) stopConsoleTicker()
        // 离开频道即停 WS 订阅与兜底轮询（不空转耗电）；进入时由 TEAM_CHANNEL 分支重起。
        if (screen != EntryScreen.TEAM_CHANNEL) stopTeamChannelFeed()
        when (screen) {
            EntryScreen.HOME -> {
                if (inConversation) {
                    chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
            EntryScreen.HISTORY -> refreshSessionList()
            EntryScreen.PROJECTS -> projectController.refresh()
            EntryScreen.TASKS -> {
                updateTaskStatus(chatViewModel.stateNow())
                refreshEvents()
                refreshTasks()
            }
            EntryScreen.AGENTS -> refreshAgents()
            EntryScreen.DEVICES -> refreshAgents()
            EntryScreen.TOOLS -> {
                refreshBrainStatus()
                // 设置页是单 ScrollView：回到页顶即大脑分节（分节在页首）。
                screenSettings.post { (screenSettings as ScrollView).scrollTo(0, 0) }
            }
            EntryScreen.SETTINGS -> {
                refreshBrainStatus()
                // V5 应用更新：设置页打开时，已登录且上次检查超过 5 分钟就自动检查一次。
                if (::appUpdateSettings.isInitialized) appUpdateSettings.onSettingsShown()
            }
            EntryScreen.RESULTS -> refreshResults()
            EntryScreen.HELP -> Unit
            EntryScreen.CONSOLE -> {
                refreshConsole()
                startConsoleTicker()
            }
            EntryScreen.MCP_PLUGINS -> refreshMcpPlugins()
            EntryScreen.TEAM_CHANNEL -> {
                refreshTeamChannel()
                startTeamChannelFeed()
            }
        }
    }

    /** 设置页与“工具与连接”入口共用同一屏；返回键与 lastEntry 按设置处理。 */
    private fun isSettingsEntry(entry: String): Boolean =
        entry == DrawerController.ID_SETTINGS || entry == DrawerController.ID_TOOLS_CONNECTIONS

    private fun hideSettings() {
        // 离开设置页即放弃未保存的通用大脑编辑（恢复新增模式、表单
        // 清空），否则下次回来名称栏还禁用着、表单还填着旧值。
        if (editingGenericBrainId != null) exitGenericEditMode()
        refreshBrainStatus()
        showEntry(lastEntry)
    }

    private fun configureAgentScreenForDevices(devicesOnly: Boolean) {
        val agentOnlyIds = listOf(
            R.id.agentHeader,
            R.id.buttonAddAgent,
            R.id.agentAddIntro,
        )
        agentOnlyIds.forEach { id ->
            screenAgents.findViewById<View>(id).visibility =
                if (devicesOnly) View.GONE else View.VISIBLE
        }
        screenAgents.findViewById<View>(R.id.deviceSectionHeader).visibility =
            if (devicesOnly) View.VISIBLE else View.GONE
        deviceInfo.visibility = if (devicesOnly) View.VISIBLE else View.GONE
        screenAgents.findViewById<TextView>(R.id.deviceSectionTitle).text =
            if (devicesOnly) "设备与授权" else "本机设备"
        if (devicesOnly) {
            screenAgents.post { (screenAgents as ScrollView).scrollTo(0, 0) }
        }
    }

    // ------------------------------------------------------------------
    // Session centre (stage 3, U5 / batch 3c)
    // ------------------------------------------------------------------

    /** 打开默认对话（当前时间线，不切换会话）。 */
    private fun openDefaultConversation() {
        inConversation = true
        showEntry(DrawerController.ID_NEW_CONVERSATION)
    }

    private fun openSession(sessionId: String, label: String) {
        if (chatViewModel.isRunning()) {
            toast("上一轮还在执行，先停止或等它结束，再切换会话。")
            return
        }
        // V5 C3 chat-bugfix：时间线从 Room 恢复，不只读内存。
        // switchSession 只读内存时间线，重启后点开会话曾是空时间线。
        val components = brainComponents
        if (components == null) {
            showSessionReadError("会话存储尚未就绪，请稍后重试。", "storage_not_ready")
            return
        }
        Thread {
            val restored = try {
                val session = components.sessionLedger.getSession(sessionId)
                val sessionName = session?.name.orEmpty()
                val conversationId = if (sessionName.startsWith("chat:")) {
                    sessionName.removePrefix("chat:")
                } else {
                    sessionId
                }
                // 阶段 A：会话账本 members 里除 owner 外的第一位就是该会话的
                // Agent（openAgentSession 建会话时写入）；有则恢复绑定，
                // 无则为默认/任务会话，selectedAgent 清空，不残留。
                val agent = session?.members?.firstOrNull { it != "user" }
                    ?.let { agentStore.getAgent(it) }
                Triple(
                    conversationId,
                    ChatSessionRestore.messagesToTurns(
                        components.conversationStore.listMessages(conversationId),
                    ),
                    agent,
                )
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        showSessionReadError(
                            "历史消息读取失败。请重试；当前会话未被切换。",
                            error.javaClass.simpleName,
                        )
                    }
                }
                return@Thread
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val (conversationId, turns, agent) = restored
                if (!chatViewModel.switchSession(conversationId, label, agent)) return@runOnUiThread
                chatViewModel.replaceSessionTurns(turns)
                if (turns.isEmpty()) {
                    toast("这是一个空会话，没有保存的消息。")
                }
                inConversation = true
                showEntry(DrawerController.ID_NEW_CONVERSATION)
            }
        }.start()
    }

    private fun showSessionReadError(message: String, diagnosticCode: String) {
        AlertDialog.Builder(this)
            .setTitle("无法打开会话")
            .setMessage("$message\n诊断码：$diagnosticCode")
            .setPositiveButton("知道了", null)
            .show()
    }

    /**
     * V5 C3 chat-bugfix：删除一个会话（二次确认）。删 ledger 会话行 +
     * conversation 历史；当前会话被删时切回新空对话并刷新列表。
     * 运行中拒绝删除（与切换/新建同一口径）。
     */
    private fun confirmDeleteSession(sessionId: String, label: String) {
        if (chatViewModel.isRunning()) {
            toast("上一轮还在执行，先停止或等它结束，再删除会话。")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("删除会话“$label”？该对话的全部消息记录都会删除，无法恢复。")
            .setPositiveButton("删除") { _, _ -> deleteSession(sessionId) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSession(sessionId: String) {
        val components = brainComponents
        Thread {
            val sessionName = try {
                components?.sessionLedger?.getSession(sessionId)?.name.orEmpty()
            } catch (_: Exception) {
                ""
            }
            val conversationId = if (sessionName.startsWith("chat:")) {
                sessionName.removePrefix("chat:")
            } else {
                sessionId
            }
            var removed = false
            val relatedSessionIds = try {
                components?.sessionLedger?.listSessions("user")
                    ?.filter { it.name == "chat:$conversationId" }
                    ?.map { it.sessionId }
                    .orEmpty()
                    .plus(sessionId)
                    .plus(conversationId)
                    .distinct()
            } catch (_: Exception) {
                listOf(sessionId, conversationId).distinct()
            }
            // Pre-fix rows used random metadata ids while their ledger
            // messages used the chat conversation id. Clear every matching
            // alias only after the explicit delete confirmation above.
            relatedSessionIds.forEach { relatedId ->
                try {
                    removed = (components?.sessionLedger?.deleteSession(relatedId) == true) || removed
                } catch (_: Exception) {
                }
            }
            try {
                if (components?.conversationStore?.deleteConversation(conversationId) == true) {
                    removed = true
                }
            } catch (_: Exception) {
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (chatViewModel.currentSessionId() == conversationId) chatViewModel.newConversation()
                refreshSessionList()
                toast(if (removed) "已删除会话。" else "该会话已不存在。")
            }
        }.start()
    }

    /**
     * V5 C3 chat-bugfix：删除当前默认对话（二次确认）。删 Room 记录 +
     * 快照 + 已存会话 id，切回新空对话。
     */
    private fun confirmDeleteDefaultConversation() {
        if (chatViewModel.isRunning()) {
            toast("上一轮还在执行，先停止或等它结束，再删除对话。")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除当前对话")
            .setMessage("删除当前默认对话？该对话的全部消息记录与快照都会删除，无法恢复。")
            .setPositiveButton("删除") { _, _ -> deleteDefaultConversation() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteDefaultConversation() {
        val sessionId = chatViewModel.currentSessionId()
        val components = brainComponents
        Thread {
            try {
                if (sessionId != null) {
                    components?.sessionLedger?.deleteSession(sessionId)
                    components?.conversationStore?.deleteConversation(sessionId)
                }
            } catch (_: Exception) {
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                chatHistoryStore.clear()
                chatHistoryStore.clearCurrentSessionId()
                chatViewModel.newConversation()
                refreshSessionList()
                toast("已删除当前对话。")
            }
        }.start()
    }

    private fun backToSessionList() {
        inConversation = false
        showEntry(DrawerController.ID_HISTORY_SEARCH)
    }

    private fun newConversationFromList() {
        if (chatViewModel.isRunning()) {
            offerForceNewConversation()
            return
        }
        if (chatViewModel.newConversation()) {
            // V5 C3 chat-bugfix：新对话不再是旧默认会话，旧 id 作废。
            chatHistoryStore.clearCurrentSessionId()
            inConversation = true
            showEntry(DrawerController.ID_NEW_CONVERSATION)
        }
    }

    private fun refreshSessionList() {
        val components = brainComponents
        if (components == null) {
            sessionListController.render("大脑加载中，稍候再看会话列表。", emptyList())
            return
        }
        Thread {
            val sessions = try {
                components.sessionLedger.listSessions("user")
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && currentEntry == DrawerController.ID_HISTORY_SEARCH) {
                        sessionListController.renderError(
                            "历史会话读取失败，当前没有显示为空列表。诊断码：${error.javaClass.simpleName}",
                        )
                    }
                }
                return@Thread
            }
            // V5 C3 chat-bugfix：`chat:<id>` 碎片行用首条用户消息作显示名，
            // 裸 uuid 片段没法认。单条失败不影响整表。
            val titles = mutableMapOf<String, String>()
            val summaries = mutableMapOf<String, String>()
            for (session in sessions) {
                if (!session.name.startsWith("chat:")) continue
                try {
                    val conversationId = session.name.removePrefix("chat:")
                    val messages = components.conversationStore.listMessages(conversationId)
                    val first = messages.firstOrNull { it.role == "user" }
                    val lastReply = messages.lastOrNull { it.role == "assistant" }
                    val text = first?.content?.trim().orEmpty()
                    lastReply?.content?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        summaries[session.sessionId] = it.replace(Regex("\\s+"), " ").take(64)
                    }
                    titles[session.sessionId] = when {
                        text.isNotEmpty() -> text.take(40)
                        messages.isEmpty() -> "空会话"
                        else -> "没有可显示的对话"
                    }
                } catch (_: Exception) {
                    titles[session.sessionId] = "暂时无法读取"
                }
            }
            val items = buildSessionListItems(
                sessions,
                titleOverrides = titles,
                summaryOverrides = summaries,
                timeLabel = { epochSeconds -> UiKit.shortTime(epochSeconds) },
            )
            val defaultSubtitle = "当前：${chatViewModel.currentSessionLabel()}"
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sessionListController.render(defaultSubtitle, items)
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    private fun bindChat() {
        chatScroll = screenChat.findViewById(R.id.chatScroll)
        messageList = screenChat.findViewById(R.id.messageList)
        inputPrompt = screenChat.findViewById(R.id.inputPrompt)
        approvalBanner = screenChat.findViewById(R.id.approvalBanner)
        chatRouteBanner = screenChat.findViewById(R.id.chatRouteBanner)
        chatControls = screenChat.findViewById(R.id.chatControls)
        buttonChatPause = screenChat.findViewById(R.id.buttonChatPause)
        buttonChatStop = screenChat.findViewById(R.id.buttonChatStop)
        buttonChatResume = screenChat.findViewById(R.id.buttonChatResume)
        buttonChatChangeGoal = screenChat.findViewById(R.id.buttonChatChangeGoal)
        buttonChatConsole = screenChat.findViewById(R.id.buttonChatConsole)
        buttonAgentSelect = screenChat.findViewById(R.id.buttonAgentSelect)
        brainCapsule = screenChat.findViewById(R.id.brainCapsule)
        buttonTaskMark = screenChat.findViewById(R.id.buttonTaskMark)
        buttonTaskMark.setOnClickListener {
            // 大脑/Agent 边界（maintainer 2026-11-12）：显式标任务开关——点按
            // 切换下一条消息是否按任务处理；置起时发完自动复位（见
            // sendPrompt）。开关状态在输入框里看得见、点得到。
            chatViewModel.toggleForceTaskNext()
            updateTaskMarkChip()
        }
        updateTaskMarkChip()
        buttonScrollBottom = screenChat.findViewById(R.id.buttonScrollBottom)
        buttonSend = screenChat.findViewById(R.id.buttonSend)
        buttonPlus = screenChat.findViewById(R.id.buttonPlus)
        buttonSend.setOnClickListener { sendPrompt() }
        buttonPlus.setOnClickListener { showPlusMenu() }
        // V5 C2（批 2）：输入框状态驱动发送按钮（HomeScreenController）。
        inputPrompt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(s: Editable?) {
                homeScreenController.setInput(s?.toString().orEmpty())
                updateSendButton()
            }
        })
        // V5 C2（批 2，第 4 步）：聊天屏内标题行已删（标题与新建功能
        // 原在 activity 顶栏；V5 C2-3-R4 起顶栏改为品牌行，新建走抽屉
        // “对话”入口，见 bindShell）。
        // 阶段 3（U6）：键盘发送键（IME actionSend）直接发送。
        inputPrompt.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendPrompt()
                true
            } else {
                false
            }
        }
        // 阶段 3（U4）：聊天页内控制——暂停/停止/继续。
        buttonChatPause.setOnClickListener {
            toast(chatViewModel.pause())
            updateTaskStatus(chatViewModel.stateNow())
        }
        buttonChatStop.setOnClickListener {
            // V5 C1：停止二次确认——确认后才走原有停止逻辑。
            confirmStopDialog("停止执行？", "确定要停止当前正在执行的轮次吗？停止后本轮不会自动续跑。") {
                toast(chatViewModel.stop())
                updateTaskStatus(chatViewModel.stateNow())
            }
        }
        buttonChatResume.setOnClickListener {
            toast(chatViewModel.resume())
            updateTaskStatus(chatViewModel.stateNow())
        }
        // V5 C2-3-R1：控制行补齐改目标/控制台（照 v2-02，一行等宽 pill）。
        // 改目标与任务页按钮同一语义（见 bindTasks）：先改目标，再回聊天输新目标。
        buttonChatChangeGoal.setOnClickListener {
            toast(chatViewModel.changeGoal())
            inConversation = true
            showEntry(DrawerController.ID_NEW_CONVERSATION)
            inputPrompt.requestFocus()
        }
        buttonChatConsole.setOnClickListener {
            // V5 C2-3-R1d：控制台是注册表里的待开发入口（去重后六大页面
            // 一份，后续批次做）——点按只给提示，不再把工具与连接冒充成控制台。
            openRegistryEntry("nav_console")
        }
        // V5 C2-3-R1：原型图输入行里的 Agent 选择器（已有选 Agent 对话框）。
        buttonAgentSelect.setOnClickListener { showSelectAgentDialog() }
        // 设置清理 2（2026-11-11，Claude 标准）：当前大脑胶囊，点按打开
        // 设置页大脑列表切换（列表本身就是干净的切换器）。
        brainCapsule.setOnClickListener { showEntry(DrawerController.ID_SETTINGS) }
        // V5 C2-3-R1 / maintainer 02:42：工作模式 id 持久化位（接线口子）。
        // 当前只有对话协作已实现；切换 UI 是第二步的活，这里只读存量、
        // 不展示切换、不改模式。
        homeScreenController.setWorkModeId(
            WorkMode.loadModeId(getSharedPreferences(WorkMode.PREFS_FILE, MODE_PRIVATE)).id,
        )
        // 阶段 3（U6）：回到底部按钮；仅在用户上滑离开底部时显示。
        buttonScrollBottom.setOnClickListener {
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
        chatScroll.viewTreeObserver.addOnScrollChangedListener { updateScrollBottomButton() }
        updateSendButton()
    }

    /** V5 C2（批 2）：发送按钮跟随 HomeScreenController.canSend()。 */
    private fun updateSendButton() {
        if (!::buttonSend.isInitialized) return
        // V5 按主流改：有待发附件时即使输入框为空也可以发送（只发一张图也行）。
        val canSend = homeScreenController.canSend(allowEmptyText = pendingAttachments.isNotEmpty())
        buttonSend.isEnabled = canSend
        buttonSend.alpha = if (canSend) 1f else 0.4f
    }

    /** V5-UI：+ 号菜单是底部抽屉“添加到对话”（Claude 参照），只放真实可用的操作。 */
    private fun showPlusMenu() {
        homeScreenController.togglePlusMenu()
        val dialog = PlusMenuSheet.show(
            activity = this,
            onDismiss = { homeScreenController.closePlusMenu() },
            onAction = { action ->
                homeScreenController.closePlusMenu()
                onPlusMenuAction(action)
            },
            webSearchEnabled = webSearchEnabled,
        )
        dialog.show()
    }

    private fun onPlusMenuAction(action: HomeScreenController.PlusMenuAction) {
        when (action) {
            HomeScreenController.PlusMenuAction.OPEN_PROJECTS -> showChatProjectPicker()
            HomeScreenController.PlusMenuAction.OPEN_TOOLS -> showEntry(DrawerController.ID_TOOLS_CONNECTIONS)
            HomeScreenController.PlusMenuAction.SELECT_AGENT -> showSelectAgentDialog()
            HomeScreenController.PlusMenuAction.SELECT_DEVICE -> showSelectDeviceDialog()
            // V5 按主流改（maintainer 2026-11-11）：第一批——相机 / 照片 / 文件 / 网页搜索。
            HomeScreenController.PlusMenuAction.TAKE_PHOTO -> launchCamera()
            HomeScreenController.PlusMenuAction.PICK_IMAGE -> pickImageLauncher.launch("image/*")
            HomeScreenController.PlusMenuAction.PICK_FILE -> pickFileLauncher.launch("*/*")
            HomeScreenController.PlusMenuAction.TOGGLE_WEB_SEARCH -> {
                webSearchEnabled = !webSearchEnabled
                chatPrefs.edit().putBoolean(KEY_WEB_SEARCH_ENABLED, webSearchEnabled).apply()
                toast(if (webSearchEnabled) "网页搜索已开启：大脑回答前会先联网搜" else "网页搜索已关闭")
            }
        }
    }

    /**
     * + 号菜单“选 Agent”：列真实已添加 Agent（agentStore），选后开私聊。
     * P1 确定性自动派活（2026-11-11）：先在后台算一遍路由（要读 Room
     * 任务库算负载，主线程不碰 Room），有首选时在列表顶部加一行
     * “★ 建议：…”——点它就是确认建议，点别的行就是改派；用户自己
     * 选 Agent 仍是功能，不是缺口。建议只按当前聊天项目 + 开发任务
     * 类型算（还没输入任务内容，类型未知时按开发任务算）。
     */
    /**
     * 三个对话模式（maintainer 2026-11-12 06:46）：模式切换——点横幅的模式段
     * 打开。三项各是一句话说明（不另加 setMessage：AlertDialog 配
     * description 会悄悄丢选项列表，见 update8 的空对话框 bug）。
     * - 大脑对话 → 开新对话（跟大脑说，像 Codex/Claude App）。
     * - 单兵对话 → 选 Agent 对话框（挑一个 Agent 一对一，不分同设备/
     *   远程设备）。
     * - 团队对话 → 频道屏（跟所有设备、所有 Agent）。
     * 当前模式在标题里标出（“当前：大脑对话”）。
     */
    private fun showChatModePicker() {
        val current = ChatMode.forConversation(chatViewModel.selectedAgent)
        val modes = ChatMode.values()
        val labels = modes.map { mode ->
            val mark = if (mode == current) "● " else ""
            "$mark${mode.label} —— ${mode.description}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("切换对话模式（当前：${current.label}）")
            .setItems(labels) { _, which ->
                when (modes[which]) {
                    ChatMode.BRAIN -> newConversationAndShow()
                    ChatMode.SINGLE_AGENT -> showSelectAgentDialog()
                    ChatMode.TEAM -> showEntry(DrawerController.ID_TEAM_CHANNEL)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSelectAgentDialog() {
        Thread {
            val agents = try {
                agentStore.listAgents()
            } catch (_: Exception) {
                emptyList()
            }
            // Agent 开关（2026-11-12）：选择器只列启用的 Agent——停用
            // 的不在这里出现（也不进建议）；全停用时如实提示去 Agent 页
            // 重新启用，不假装“暂无已添加 Agent”。
            val enabled = agents.enabledAgents()
            val suggestion = suggestAgentForCurrentChat(enabled)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showSelectAgentDialogWithSuggestion(enabled, suggestion, allDisabled = agents.isNotEmpty() && enabled.isEmpty())
            }
        }.start()
    }

    private sealed interface AgentPickerRow {
        data class Suggested(val decision: RoutingDecision, val agent: AgentRecord) : AgentPickerRow
        data class Plain(val agent: AgentRecord) : AgentPickerRow
    }

    private fun showSelectAgentDialogWithSuggestion(
        agents: List<AgentRecord>,
        suggestion: AgentPickerRow.Suggested?,
        allDisabled: Boolean = false,
    ) {
        if (agents.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("选 Agent")
                .setMessage(
                    if (allDisabled) "Agent 都已停用：在 Agent 页把开关打开后可选。"
                    else "暂无已添加 Agent",
                )
                .setPositiveButton("去 Agent 页添加") { _, _ -> showEntry(DrawerController.ID_AGENTS_TEAM) }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val rows = mutableListOf<AgentPickerRow>()
        suggestion?.let { rows += it }
        agents.forEach { rows += AgentPickerRow.Plain(it) }
        val labels = rows.map { row ->
            when (row) {
                is AgentPickerRow.Suggested ->
                    "★ 建议：${row.agent.displayName} —— ${row.decision.shortReason}"
                is AgentPickerRow.Plain ->
                    row.agent.displayName + if (row.agent.dispatchable) "" else "（仅对话）"
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选 Agent")
            .setItems(labels) { _, which ->
                when (val row = rows[which]) {
                    is AgentPickerRow.Suggested -> {
                        // 点建议行 = 确认建议：记下决策，下一条任务建行时绑定。
                        chatViewModel.acceptSuggestedAgent(row.decision.decisionId, row.agent.agentId)
                        openAgentSession(row.agent)
                    }
                    is AgentPickerRow.Plain -> {
                        // 点普通行 = 改派/手动选：建议作废，不再绑定。
                        chatViewModel.clearPendingRouting()
                        openAgentSession(row.agent)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * P1 建议：按当前聊天项目 + 开发任务类型跑一遍确定性路由。
     * 任何一步失败都返回 null（无建议、对话框照旧），绝不因建议
     * 算不出来就挡住选 Agent。路由决策记进日志（供 eval 回看），
     * 只有算出首选时才返回。
     */
    private fun suggestAgentForCurrentChat(agents: List<AgentRecord>): AgentPickerRow.Suggested? {
        if (agents.isEmpty()) return null
        return try {
            val comps = brainComponents
            val evalLogStore = comps?.evalLogStore ?: SharedPreferencesEvalLogStore(this)
            val routingLog = comps?.routingDecisionLog ?: SharedPreferencesRoutingDecisionLog(this)
            val cardStore = comps?.capabilityCardStore ?: SharedPreferencesCapabilityCardStore(this)
            val devices = try {
                remoteDeviceStore.listDevices()
            } catch (_: Exception) {
                emptyList()
            }
            val pairedDeviceIds = devices.mapNotNull { device ->
                device.deviceId.takeIf {
                    pairingSnapshot.load(it)?.state == PairingState.CONFIRMED
                }
            }.toSet()
            val projectId = try {
                chatViewModel.currentProjectId()
            } catch (_: Exception) {
                null
            }
            val projectLocations = if (projectId != null) {
                try {
                    projectStore.locationsForProject(projectId)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            val allLocations = try {
                projectStore.listProjects().flatMap { project ->
                    projectStore.locationsForProject(project.projectId)
                }
            } catch (_: Exception) {
                emptyList()
            }
            val tasks = try {
                comps?.taskStore?.listTasks() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            val evalEntries = try {
                evalLogStore.listEntries()
            } catch (_: Exception) {
                emptyList()
            }
            val now = System.currentTimeMillis() / 1000
            val cards = CapabilityCards.build(
                agents = agents,
                devices = devices,
                pairedDeviceIds = pairedDeviceIds,
                allLocations = allLocations,
                tasks = tasks,
                evalEntries = evalEntries,
                helloRepos = try {
                    cardStore.helloRepos()
                } catch (_: Exception) {
                    emptyMap()
                },
                nowEpochSeconds = now,
            )
            val requirement = RoutingRequirement(
                projectId = projectId,
                taskType = TaskType.CODE,
                allowMetered = false,
                nowEpochSeconds = now,
            )
            val cooldowns = EvalScorecard.consecutiveFailures(evalEntries, now)
            val decision = CapabilityRouter.route(
                cards = cards,
                requirement = requirement,
                cooldowns = cooldowns,
                projectHasDeviceLocation = projectLocations.any { it.homeKind == ProjectHomeKind.DEVICE },
            )
            try {
                routingLog.record(decision)
            } catch (_: Exception) {
                // 决策日志记失败不影响建议本身。
            }
            val chosenId = decision.chosenAgentId ?: return null
            val agent = agents.firstOrNull { it.agentId == chosenId } ?: return null
            AgentPickerRow.Suggested(decision, agent)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * P1 能力卡：hello 成功一次，就把 Hand 广播的仓库快照存下来
     * （分支/commit 实测值，供下次路由建卡用）。存失败不影响配对 /
     * 探测主流程。调用点：配对成功、重新发现、远端探针、发起配对。
     */
    private fun noteHelloForCapabilityCards(hello: RemoteHello) {
        try {
            val store = brainComponents?.capabilityCardStore
                ?: SharedPreferencesCapabilityCardStore(this)
            store.noteHello(hello.deviceId, hello)
        } catch (_: Exception) {
            // 快照存失败不影响主流程。
        }
    }

    /**
     * + 号菜单“选设备”：后台做一次真实 LAN 发现（5 秒 mDNS，与
     * refreshAgents 同一数据源）+ 已配对记录；未接入设备一律标
     * “不可用”，绝不伪造在线态。已配对设备目前路由到 Agent 页内
     * 的设备区段（设备独立入口是批 2 第 3 步的 showEntry 路由）。
     */
    private fun showSelectDeviceDialog() {
        toast("正在发现局域网设备…")
        Thread {
            val discovered = try {
                runBlocking { lanTransport.discover(5000) }
            } catch (_: Exception) {
                emptyList()
            }
            val pairings = try {
                pairingStore.all()
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showDevicePicker(discovered, pairings)
            }
        }.start()
    }

    private fun showDevicePicker(
        discovered: List<DiscoveredPeer>,
        pairings: List<PairingRecord>,
    ) {
        val pairedIds = pairings.map { it.deviceId }.toSet()
        val rows = mutableListOf<Pair<String, Boolean>>()
        // rowRemoteDeviceIds 与 rows 一一对应：null = 局域网/已配对设备，
        // "ADD" = 添加远程设备入口，其余 = 远程设备 id。
        val rowRemoteDeviceIds = mutableListOf<String?>()
        pairings.forEach { record ->
            val stateLabel = when (record.state) {
                PairingState.CONFIRMED -> "已配对"
                PairingState.PENDING -> "待确认（不可用）"
                else -> "已拒绝（不可用）"
            }
            rows += "${record.displayName} · $stateLabel" to (record.state == PairingState.CONFIRMED)
            rowRemoteDeviceIds += null
        }
        discovered.filter { it.deviceId !in pairedIds }.forEach { peer ->
            rows += "${peer.displayName} · 未配对（不可用）" to false
            rowRemoteDeviceIds += null
        }
        // 阶段 B：已添加的远程设备（手动添加的 VPS 等）也列出来；
        // 状态以配对记录为准，未配对一律标“不可用”，不伪造在线态。
        try {
            remoteDeviceStore.listDevices().forEach { device ->
                val record = pairings.firstOrNull { it.deviceId == device.deviceId }
                val stateLabel = when (record?.state) {
                    PairingState.CONFIRMED -> "已配对"
                    PairingState.PENDING -> "待确认（不可用）"
                    else -> "未配对（不可用）"
                }
                // V5 公网直连（2026-11-11）：列表只标走线（公网/内网），
                // 不贴完整 IP；两个地址在设备页“编辑地址”里看改。
                val route = if (device.hasPublicAddress()) "公网" else "内网"
                rows += "${device.displayName} · $stateLabel（远程·$route）" to (record?.state == PairingState.CONFIRMED)
                rowRemoteDeviceIds += device.deviceId
            }
        } catch (_: Exception) {
        }
        rows += "＋ 添加远程设备…" to true
        rowRemoteDeviceIds += "ADD"
        AlertDialog.Builder(this)
            .setTitle("选设备")
            .setItems(rows.map { it.first }.toTypedArray()) { _, which ->
                val remoteId = rowRemoteDeviceIds[which]
                if (remoteId == "ADD") {
                    showAddRemoteDeviceDialog()
                    return@setItems
                }
                if (rows[which].second) {
                    showEntry(DrawerController.ID_DEVICES)
                    toast(
                        if (remoteId != null) "远程设备已接入，可在 Agent 页选它的 Agent 对话"
                        else "设备在 Agent 页内查看",
                    )
                } else {
                    toast("该设备未接入，跨设备对话尚未实现")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 阶段 B（V5 紧急开发）：添加远程设备（手动填主机/端口，如远程设备）。
     *
     * 流程：填显示名 + 主机 + 端口 → 后台 hello 探测（拿对方真实
     * device_id、公钥与 Agent 清单；探测本身不建立任何信任）→
     * 弹窗请用户核对服务端指纹 → 确认后存设备、配对、保存发现的
     * Agent 记录。指纹须与 VPS 上 `hand_server.py fingerprint`
     * 的输出一致，否则不要确认。
     */
    /**
     * 添加设备入口（V5 配对集成，2026-11-11；maintainer 10:30 定：主流通用
     * 技术优先，三条路按顺序排）。
     *
     * 路 1（先走）：局域网发现（5 秒 mDNS，`_wherevercode._tcp`；
     * Hand 启动后广播，没广播的设备这里看不到）→ 点一台进配对
     * （指纹核对 + 6 位配对码，双向确认）；没发现就手输 IP（同屏入口）。
     * 路 2 / 路 3（后走）：“在新设备上安装 Hand”→ 通用一键运行脚本
     * 在前、V5 一键安装（App 直连 SSH）在后（DeviceInstallController
     * 的路线选择）。两个入口（聊天设备选择器 + 设备页 onAddRemote）
     * 都汇到这里。
     */
    private fun showAddRemoteDeviceDialog() {
        toast("正在发现局域网设备…")
        Thread {
            val discovered = try {
                runBlocking { lanTransport.discover(5000) }
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showAddDeviceSheet(discovered)
            }
        }.start()
    }

    /** 添加设备单：发现的设备（点一台进配对）+ 手输 IP + 去装 Hand。 */
    private fun showAddDeviceSheet(discovered: List<DiscoveredPeer>) {
        val rows = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        discovered.forEach { peer ->
            val caps = if (peer.capabilities.isNotEmpty()) {
                " · ${peer.capabilities.size} 项能力"
            } else {
                ""
            }
            rows += "${peer.displayName}（${peer.deviceId}）$caps"
            actions += { requestPairing(peer) }
        }
        if (discovered.isEmpty()) {
            rows += "局域网内没有发现 Hand 设备"
            actions += {
                toast("局域网内没有发现 Hand 设备：请确认目标设备与手机在同一 Wi-Fi、Hand 在运行；或用下面的“手输 IP 地址”。")
            }
        }
        rows += "手输 IP 地址…"
        actions += { showManualRemoteDeviceForm() }
        rows += "在新设备上安装 Hand…"
        actions += { startDeviceInstall() }
        AlertDialog.Builder(this)
            .setTitle("添加设备")
            .setItems(rows.toTypedArray()) { _, which ->
                actions[which]()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 路 2 / 路 3：去新设备上装 Hand（通用脚本在前、V5 一键安装在后）。 */
    private fun startDeviceInstall() {
        val assetReader = AssetReader { path ->
            try {
                assets.open(path).use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
        }
        DeviceInstallController(
            activity = this,
            assetReader = assetReader,
            sessionFactory = JschSshSessionFactory(),
            onPairInstalled = { displayName, host, port ->
                probeAndAddRemoteDevice(displayName, host, port)
            },
            onPairExisting = { showManualRemoteDeviceForm() },
        ).start()
    }

    /** 手动配对表单（已装 Hand 的设备：填显示名 + 主机 + 端口 → 连接）。 */
    private fun showManualRemoteDeviceForm() {
        val nameInput = EditText(this).apply {
            hint = "显示名，如：远程设备"
            setSingleLine(true)
        }
        val hostInput = EditText(this).apply {
            hint = "内网地址，如：192.0.2.10"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        // V5 公网直连（2026-11-11）：可选；填了连接优先走它，
        // 连不上自动回落内网地址。留空 = 只走内网地址。
        val publicHostInput = EditText(this).apply {
            hint = "公网直连地址（可选），如：公网 IP；留空只走内网"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val portInput = EditText(this).apply {
            setText(DirectTransport.HAND_PORT.toString())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(nameInput)
            addView(hostInput)
            addView(publicHostInput)
            addView(portInput)
        }
        AlertDialog.Builder(this)
            .setTitle("添加远程设备")
            .setMessage("填 VPS 的 Tailscale IP 与 Hand 端口（默认 18743）。有公网直连地址可再填一行：连接优先走公网，连不上自动回落内网。连接后会请你核对服务端指纹，确认后才配对。")
            .setView(container)
            .setPositiveButton("连接") { _, _ ->
                val displayName = nameInput.text.toString().trim()
                val host = hostInput.text.toString().trim()
                val publicHost = publicHostInput.text.toString().trim()
                val port = portInput.text.toString().trim().toIntOrNull()
                    ?: DirectTransport.HAND_PORT
                if (displayName.isEmpty() || host.isEmpty()) {
                    toast("请填写显示名与主机。")
                    return@setPositiveButton
                }
                probeAndAddRemoteDevice(displayName, host, port, publicHost)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun probeAndAddRemoteDevice(displayName: String, host: String, port: Int, publicHost: String = "") {
        toast("正在连接 $host:$port…")
        Thread {
            try {
                val identity = deviceIdentityStore.getOrCreate()
                // V5 公网直连：探测也走首选→回落——填了公网地址先探
                // 公网，探不上再探内网；两路都探不上才报失败。
                val probePeer = DiscoveredPeer(
                    deviceId = "probe",
                    displayName = displayName,
                    host = publicHost.ifBlank { host },
                    port = port,
                    fingerprintSha256 = "",
                    capabilities = listOf("hand"),
                    fallbackHost = if (publicHost.isNotBlank() && publicHost != host) host else null,
                )
                val hello = runBlocking {
                    directTransport.fetchHelloUnverified(
                        probePeer,
                        identity.deviceId,
                        identity.publicKey,
                    )
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    showPairingDialog(displayName, host, port, hello, publicHost)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("连接失败：${e.message}") }
            }
        }.start()
    }

    /**
     * 配对对话框（V5 配对集成，2026-11-11）：双向确认，一次完成两边。
     *
     * 第一道（手机验对方）：显示服务端指纹全文（前 16 位大字 + 全文），
     * 用户核对与目标设备上显示的指纹一致（安装时已显示，或跑
     * hand_server.py fingerprint 查看）。第二道（对方验手机）：
     * 用户填入 6 位配对码（安装时已显示，或在目标设备上跑
     * hand_server.py pair-code 查看）；App 发密封配对确认帧，
     * 对方验码通过才 APPROVED——手机侧与 Hand 侧同时完成，不再
     * 出现“手机以为配好了、Hand 那边还是待批准”的半截状态。
     * 码错/锁定/超时都如实报错，不静默。
     */
    private fun showPairingDialog(
        displayName: String,
        host: String,
        port: Int,
        hello: RemoteHello,
        publicHost: String = "",
    ) {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(hello.publicKey)
            .joinToString("") { "%02x".format(it) }
        val agentsText = if (hello.agents.isEmpty()) {
            "该设备上没有可用的 Agent。"
        } else {
            "发现 ${hello.agents.size} 个 Agent：" +
                hello.agents.joinToString("、") { it.displayName }
        }
        val codeInput = EditText(this).apply {
            hint = "6 位配对码"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(PairingClient.PAIR_CODE_LENGTH))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(
                TextView(this@MainActivity).apply {
                    text = "设备：${hello.displayName}（${hello.deviceId}）\n" +
                        "指纹：${fingerprint.take(16)}…\n$fingerprint\n$agentsText\n\n" +
                        "请核对指纹与目标设备上显示的一致（安装时已显示，或跑 hand_server.py fingerprint 查看）。"
                    setTextIsSelectable(true)
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "配对码（6 位数字）："
                    setPadding(0, dp(12), 0, dp(4))
                },
            )
            addView(codeInput)
            addView(
                TextView(this@MainActivity).apply {
                    text = "配对码在目标设备安装时已显示，或在目标设备上跑 hand_server.py pair-code 查看。"
                    setPadding(0, dp(6), 0, 0)
                },
            )
        }
        AlertDialog.Builder(this)
            .setTitle("配对：核对指纹 + 配对码")
            .setView(container)
            .setPositiveButton("确认配对") { _, _ ->
                val code = codeInput.text.toString().trim()
                if (!PairingClient.isValidPairCode(code)) {
                    toast("配对码必须是 6 位数字。")
                    return@setPositiveButton
                }
                confirmPairingWithCode(displayName, host, port, hello, fingerprint, code, publicHost)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 配对码发给对方验：密封配对确认帧，对方批准后才落盘、才算配对完成。 */
    private fun confirmPairingWithCode(
        displayName: String,
        host: String,
        port: Int,
        hello: RemoteHello,
        fingerprint: String,
        code: String,
        publicHost: String = "",
    ) {
        toast("正在向 ${hello.displayName} 发送配对确认…")
        Thread {
            try {
                val identity = deviceIdentityStore.getOrCreate()
                val peer = DiscoveredPeer(
                    deviceId = hello.deviceId,
                    displayName = displayName,
                    host = host,
                    port = port,
                    fingerprintSha256 = fingerprint,
                    capabilities = listOf("hand"),
                )
                val result = runBlocking {
                    PairingClient.sendPairConfirm(
                        peer,
                        identity.deviceId,
                        identity.privateKey,
                        hello.publicKey,
                        code,
                    )
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when (result) {
                        PairConfirmResult.Approved -> {
                            confirmRemoteDevice(displayName, host, port, hello, fingerprint, publicHost)
                        }
                        is PairConfirmResult.Rejected -> {
                            // 码错/锁定：服务端原话就是原因，留在配对对话框里重填。
                            toast(result.reason)
                            showPairingDialog(displayName, host, port, hello, publicHost)
                        }
                        is PairConfirmResult.Failed -> {
                            toast(result.reason)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("配对失败：${e.message}") }
            }
        }.start()
    }

    private fun confirmRemoteDevice(
        displayName: String,
        host: String,
        port: Int,
        hello: RemoteHello,
        fingerprint: String,
        publicHost: String = "",
    ) {
        Thread {
            try {
                val nowSeconds = System.currentTimeMillis() / 1000
                // 断点续配时保留最初添加时间，不拿重配时间冒充。
                val existing = try {
                    remoteDeviceStore.getDevice(hello.deviceId)
                } catch (_: Exception) {
                    null
                }
                val addedAt = existing?.addedAtEpochSeconds?.takeIf { it > 0 } ?: nowSeconds
                val device = RemoteDevice(
                    deviceId = hello.deviceId,
                    displayName = displayName,
                    host = host,
                    port = port,
                    publicHost = publicHost,
                    addedAtEpochSeconds = addedAt,
                    // 刚配对成功：hello 通过，本次即最后在线；对方刚批准，
                    // 服务端状态就是 APPROVED（不是 hello 里旧的 PENDING）。
                    lastSeenEpochSeconds = nowSeconds,
                    lastServerStatus = "APPROVED",
                    // Hand 自更新（2026-11-12）：配对成功时 hello 里报的
                    // Hand 版本原样存下来，设备卡据此显示“可更新/最新”。
                    handVersion = hello.handVersion,
                    // 经 Hand hello 加进来的设备就是一台 Hand 服务端：
                    // 能托管项目文件夹、执行 Agent、读写工作区（能力以服务端
                    // 代码为准；控制面/建项目/collab/git 基线一律关着）。
                    capabilities = handServerCapabilities(),
                    protocolCapabilities = handServerProtocolCapabilities(),
                )
                val deviceErrors = RemoteDeviceValidation.validate(device)
                if (deviceErrors.isNotEmpty()) {
                    throw IllegalArgumentException(
                        "设备 id（${hello.deviceId}）不符合要求：${deviceErrors.joinToString("；")}。" +
                            "服务端报的 id 原样采用；如仍不符合，请在服务端用 hand_server.py serve " +
                            "--device-id <字母/数字/-/_/.> 重启服务。",
                    )
                }
                remoteDeviceStore.saveDevice(device)
                val peer = device.toDiscoveredPeer(fingerprint)
                pairingManager.requestPairing(peer)
                pairingManager.confirm(hello.deviceId, hello.publicKey)
                // V5 修复（2026-11-11）：配对刚确认，顺手刷一遍快照，
                // 让对话路由立刻认这台设备，不用等下次刷新。
                refreshPairingSnapshot()
                // P1 能力卡：hello 成功，存仓库快照。
                noteHelloForCapabilityCards(hello)
                // V5 自动发现（2026-11-11）：配对 + hello 成功后按 hello
                // 列表自动建 Agent 记录。去重按 deviceId + agentId：手工
                // 已加的保留不动（不覆盖测试结果与登录态），同 agentId 属
                // 别台设备的跳过（AgentStore 全局按 agentId 存，覆盖会毁
                // 掉另一台设备的 Agent）。只存可用的；不可用的跳过并如实说明。
                val available = hello.agents.filter { it.available && it.folder.isNotBlank() }
                val (created, discovery) = DeviceAgentDiscovery.discover(
                    hello.deviceId,
                    hello.agents,
                    agentStore.listAgents(),
                )
                created.forEach { agentStore.saveAgent(it) }
                runOnUiThread {
                    toast(
                        "已配对 $displayName：${DeviceAgentDiscovery.summarize(discovery)}。",
                    )
                    refreshAgents()
                    // V5 项目添加：配对成功后问一句要不要顺手加项目
                    //（列出刚发现的 Agent 文件夹作候选；不强制，可暂不添加）。
                    offerAddProjectAfterPairing(device, available)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("保存远程设备失败：${e.message}") }
            }
        }.start()
    }

    /**
     * 重新发现某台已配对设备上的 Agent（V5，2026-11-11）：设备页每台已
     * 配对远程设备卡片上的“重新发现 Agent”。场景：设备是在自动发现上
     * 线前加的，或服务端后来又装了新 Agent——重拉一次 hello，按同一
     * 去重口径补建缺失的记录，已有的不动。只读 hello，不改配对、不删记录。
     */
    private fun rediscoverDeviceAgents(device: RemoteDevice) {
        toast("正在重新发现 ${device.displayName} 上的 Agent…")
        Thread {
            try {
                val pairing = loadPairingRecord(
                    device.deviceId,
                    brainComponents?.pairingTrustStore,
                    pairingStore,
                )
                if (pairing == null || pairing.state != PairingState.CONFIRMED) {
                    runOnUiThread { toast("${device.displayName} 未配对：请先完成配对，再重新发现 Agent。") }
                    return@Thread
                }
                val identity = deviceIdentityStore.getOrCreate()
                val peer = device.toDiscoveredPeer(pairing.fingerprintSha256)
                val hello = runBlocking {
                    directTransport.fetchHello(peer, identity.deviceId, identity.publicKey)
                }
                // hello 成功：记最后在线与服务端批准状态（列表如实显示）。
                // Hand 自更新（2026-11-12）：顺手记远端 Hand 版本。
                try {
                    remoteDeviceStore.saveDevice(
                        device.copy(
                            lastSeenEpochSeconds = System.currentTimeMillis() / 1000,
                            lastServerStatus = hello.clientStatus,
                            handVersion = hello.handVersion,
                        ),
                    )
                } catch (_: Exception) {
                }
                // P1 能力卡：hello 成功，存仓库快照。
                noteHelloForCapabilityCards(hello)
                val (created, discovery) = DeviceAgentDiscovery.discover(
                    device.deviceId,
                    hello.agents,
                    agentStore.listAgents(),
                )
                created.forEach { agentStore.saveAgent(it) }
                runOnUiThread {
                    toast("${device.displayName}：${DeviceAgentDiscovery.summarize(discovery)}。")
                    refreshAgents()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("重新发现失败：${e.message}") }
            }
        }.start()
    }

    /**
     * 远程设备地址详情/编辑（V5 公网直连，2026-11-11）：设备页卡片上
     * 的“编辑地址”。两个地址都能看能改：内网地址（Tailnet/LAN）与
     * 公网直连地址（可选，留空 = 清空，只走内网）。连接优先走公网，
     * 公网连不上自动回落内网，不用手动切换。改地址不改配对：设备 id、
     * 能力、配对记录原样保留，保存后仍是同一台已配对设备。
     */
    private fun showEditRemoteDeviceDialog(device: RemoteDevice) {
        val nameInput = EditText(this).apply {
            setText(device.displayName)
            setSingleLine(true)
        }
        val hostInput = EditText(this).apply {
            setText(device.host)
            hint = "内网地址，如：192.0.2.10"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val publicHostInput = EditText(this).apply {
            setText(device.publicHost)
            hint = "公网直连地址（可选）；留空 = 清空，只走内网"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val portInput = EditText(this).apply {
            setText(device.port.toString())
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(nameInput)
            addView(hostInput)
            addView(publicHostInput)
            addView(portInput)
        }
        AlertDialog.Builder(this)
            .setTitle("设备地址：${device.displayName}")
            .setMessage(
                "内网地址是 Tailnet/LAN 地址；公网直连地址填了就优先走公网，" +
                    "公网连不上自动回落内网。改地址不改配对（设备 id 与指纹不变）。",
            )
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val updated = device.copy(
                    displayName = nameInput.text.toString().trim(),
                    host = hostInput.text.toString().trim(),
                    publicHost = publicHostInput.text.toString().trim(),
                    port = portInput.text.toString().trim().toIntOrNull()
                        ?: device.port,
                )
                val errors = RemoteDeviceValidation.validate(updated)
                if (errors.isNotEmpty()) {
                    toast("保存失败：${errors.joinToString("；")}")
                    return@setPositiveButton
                }
                try {
                    remoteDeviceStore.saveDevice(updated)
                    toast("已保存 ${updated.displayName} 的地址（${updated.routeLabel()}）。")
                    refreshAgents()
                } catch (e: Exception) {
                    toast("保存失败：${e.message}")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Projects (V5 项目添加，2026-11-11)
    // ------------------------------------------------------------------

    /** 设备 id → 显示名（项目列表/详情用；找不到时回落 id 本身，不伪造）。 */
    private fun deviceDisplayName(deviceId: String): String {
        return try {
            remoteDeviceStore.getDevice(deviceId)?.displayName ?: deviceId
        } catch (_: Exception) {
            deviceId
        }
    }

    /** 某设备上已存的 Agent 文件夹（hello 发现时存下的 deviceFolder）。 */
    private fun agentFoldersOnDevice(deviceId: String): List<AgentRecord> {
        return try {
            agentStore.listAgents().filter {
                it.deviceId == deviceId && !it.deviceFolder.isNullOrBlank()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 项目页“设备上的项目来源”（V5，2026-11-11；maintainer 设计纠正）：
     * 已配对设备的 Agent 文件夹（只取可路由的，不凭空造）。来源驱动
     * 项目页的一节，点开即浏览——不用先注册项目。
     *
     * 可用性（2026-11-11 项目页清理）：不可用的来源也列出来灰显，
     * 不再像以前那样直接丢掉（只灰显、永不自动删除，与设备列表
     * 同一规矩）。判定只用配对快照（[deviceSourceAvailability]）——
     * 配对状态只说信任，不说在线；CONFIRMED 但失联的设备（如 Mac）
     * 这里仍算可用。在线态接线点：配对分支的最后在线
     * （RemoteDevice.lastSeenEpochSeconds，fix/v5-device-pairing-
     * mainstream-20261111）落地后，在这里的解析器里拼上——
     * 如 lastSeen 长时间未更新就标不可用、原因写“最后在线：X前”；
     * 不要另起一套在线判定，也不要在这里发 hello 探测。
     */
    /** 项目名 → 未占用的项目 id（碰撞时自动加后缀，名不变）。 */
    private fun uniqueProjectSlug(name: String): String {
        var slug = slugProjectId(name)
        var suffix = 2
        while (projectStore.getProject(slug) != null) {
            slug = "${slugProjectId(name)}-$suffix"
            suffix++
        }
        return slug
    }

    /**
     * 列出远端文件夹（供 RemoteFolderPicker 用；回调在主线程）。
     * 走 Hand list：凭证 folder = 该 Agent 的授权文件夹，path = 相对子路径；
     * Result 的 output 解析成条目，Blocked/无回包都如实转成失败。
     */
    private fun listRemoteFolderEntries(
        target: RemoteTarget,
        relativePath: String,
        callback: (Result<List<RemoteFolderEntry>>) -> Unit,
    ) {
        Thread {
            try {
                val events = runBlocking {
                    remoteChatPort.listFolder(
                        target,
                        taskId = UUID.randomUUID().toString(),
                        relativePath = relativePath,
                    ).toList()
                }
                val result = events.firstOrNull { it is HandEvent.Result } as? HandEvent.Result
                val blocked = events.firstOrNull { it is HandEvent.Blocked } as? HandEvent.Blocked
                val outcome: Result<List<RemoteFolderEntry>> = when {
                    result != null -> runCatching { parseFolderListJson(result.output) }
                    blocked != null -> Result.failure(IllegalStateException(blocked.reason))
                    else -> Result.failure(IllegalStateException("远端没有返回文件夹列表"))
                }
                runOnUiThread { callback(outcome) }
            } catch (e: Exception) {
                runOnUiThread { callback(Result.failure(e)) }
            }
        }.start()
    }

    /**
     * 配对成功后问一句要不要顺手加项目：列出刚发现的 Agent 文件夹作候选；
     * 点一个就直接拿该文件夹当作家，再填名存项目（不浏览文件系统：
     * 就从已开放文件夹里选，maintainer 2026-11-11）。
     * 不强制：点“暂不添加”就只加设备不加项目。
     * 老项目规矩：只有 project_host 能力的设备才能托管项目；
     * 经 Hand 加进来的设备都有该能力，没有的设备不问（摆设）。
     */
    private fun offerAddProjectAfterPairing(device: RemoteDevice, availableAgents: List<RemoteAgentInfo>) {
        if (!device.capabilities.projectHost) return
        if (availableAgents.isEmpty()) return
        val items = availableAgents.map { "${it.displayName} · ${it.folder}" } + "暂不添加"
        // setMessage + setItems 会让列表不显示（见 ListDialog）：走 ListDialog。
        ListDialog.show(
            this,
            "为 ${device.displayName} 添加项目？",
            "项目住在设备上：选一个 Agent 的文件夹作为项目之家。",
            items,
            negativeLabel = "暂不添加",
        ) { which ->
            if (which >= availableAgents.size) return@show
            val info = availableAgents[which]
            if (info.folder.isBlank()) {
                toast("该 Agent 没有已开放文件夹，无法添加项目。")
                return@show
            }
            showAddProjectNameDialog(device.deviceId, info.folder)
        }
    }

    /**
     * 能托管项目的已配对设备（与 [showAddProjectFlow] 同一判定）：
     * 只看“有 Agent 文件夹能路由到它”（已配对 + Hand 在跑 + 有文件夹），
     * 不拿静态能力位 projectHost 当否决项——K70 三台已配对设备的旧存储
     * 记录都没有 capabilities 键，静态位缺省 false 会把真能托管的设备
     * 挡在门外（2026-11-11 真机根因）。判定本身见纯函数
     * [projectHostUsableDevices]。
     */
    private fun usableProjectHostDevices(): List<RemoteDevice> {
        val devices = try {
            remoteDeviceStore.listDevices()
        } catch (_: Exception) {
            emptyList()
        }
        val routableDeviceIds = devices.mapNotNull { device ->
            val routable = try {
                agentFoldersOnDevice(device.deviceId).any { resolveRemoteTarget(it) != null }
            } catch (_: Exception) {
                false
            }
            if (routable) device.deviceId else null
        }.toSet()
        return projectHostUsableDevices(devices, routableDeviceIds)
    }

    /**
     * 远程设备文件夹流程（“打开文件夹”唯一入口里的“远程设备上的文件夹”；
     * 主流做法：先连设备——走已有的添加/配对流程，连上后打开那台设备上的文件夹）。
     * 选设备（有 Agent 文件夹能路由到的已配对远程设备；只有一台时直接
     * 进下一步）→ 选文件夹（已有 DEVICE 家的项目，当前家置顶默认选中）
     * → 浏览选家文件夹 → 填名/仓库地址 →
     * 注册项目 + 指定家。家文件夹须经 Hand list 真实列出才可选；文件夹
     * 占用冲突由 ProjectStore 拒绝，不存假项目。forProjectId 非空 = 给已
     * 注册的项目指定家（只存位置）。
     *
     * 先选设备——列表每台带状态（已配对 / 待确认（不可用）等，配对快照
     * 里的真实状态，绝不显示假在线态）；再选该设备上已开放的文件夹；
     * 填名；创建时家就定下来。不要先让用户去翻文件夹再回头定设备。
     */
    private fun showAddProjectFlow(forProjectId: String? = null) {
        val usable = usableProjectHostDevices()
        if (usable.isEmpty()) {
            toast("还没有能托管项目的已配对设备：请先在设备页添加并配对一台 Hand 设备。")
            return
        }
        if (usable.size == 1) {
            showAddProjectPickOpenFolder(usable[0].deviceId, forProjectId)
            return
        }
        val pairings = usable.mapNotNull { device -> pairingSnapshot.load(device.deviceId) }
        val rows = buildRemoteDeviceRows(usable, pairings)
        // setMessage + setItems 会让设备列表不显示（见 ListDialog）：走 ListDialog。
        ListDialog.show(
            this,
            "选一台设备",
            "选一台设备，下一步选一个文件夹作为项目之家。",
            rows.map {
                "${it.device.displayName} · ${it.device.host}:${it.device.port} · ${it.stateLabel}"
            },
        ) { which ->
            showAddProjectPickOpenFolder(rows[which].device.deviceId, forProjectId)
        }
    }

    /**
     * 选文件夹作为项目之家（老项目“新建项目”对话框的下一步，
     * 2026-11-11 maintainer 参照：选了设备后下面出现“已开放文件夹”下拉，
     * 列出该设备已开放的文件夹，选一个）：候选集见
     * [remoteFolderPickerListing]——不只列 Agent 文件夹，该项目在本
     * 设备上已有的 DEVICE 家（当前家置顶、默认选中、标“当前家”）也
     * 在内；Agent 文件夹只是候选之一，不再被当成项目创建目标
     * （2026-11-12，maintainer 05:32 实证：仓库文件夹明明已是当前家，
     * 弹框里却只剩三个 Agent 文件夹）。直接选一个文件夹当作家，
     * 不浏览文件系统（maintainer：就从已开放文件夹里选）。
     * 文案不再写“将在已开放文件夹中创建”——那句话把 Agent 文件夹
     * 说成了创建目标，是本次实证里 maintainer 骂的根源之一。
     */
    private fun showAddProjectPickOpenFolder(deviceId: String, forProjectId: String? = null) {
        val agentsById = agentFoldersOnDevice(deviceId)
            .filter { resolveRemoteTarget(it) != null }
            .associateBy { it.agentId }
        // 该项目在本设备上已有的 DEVICE 家（ProjectStore 是 App 侧唯一
        // 真实来源；Hand 的已授权列表不下发，见 remoteFolderPickerListing
        // 的注释）。新建项目时 forProjectId 为 null，无家。
        val currentHomeFolder: String?
        val otherHomeFolders: List<String>
        if (forProjectId != null) {
            val homes = projectStore.locationsForProject(forProjectId)
                .filter { it.homeKind == ProjectHomeKind.DEVICE && it.hostId == deviceId }
            val active = projectStore.getActiveHome(forProjectId)
                ?.takeIf { it.homeKind == ProjectHomeKind.DEVICE && it.hostId == deviceId }
            currentHomeFolder = active?.workspacePath
            otherHomeFolders = homes.map { it.workspacePath }
                .filter { it != currentHomeFolder }
                .sorted()
        } else {
            currentHomeFolder = null
            otherHomeFolders = emptyList()
        }
        val listing = remoteFolderPickerListing(
            deviceId,
            agentsById.values.toList(),
            currentHomeFolder,
            otherHomeFolders,
        )
        if (listing.entries.isEmpty()) {
            toast("该设备上没有可用的文件夹：请确认设备已配对且 Hand 服务在运行。")
            return
        }
        val onFolderPicked: (String) -> Unit = { fullPath ->
            if (forProjectId != null) {
                assignProjectHome(forProjectId, deviceId, fullPath)
            } else {
                showAddProjectNameDialog(deviceId, fullPath)
            }
        }
        if (listing.entries.size == 1) {
            onFolderPicked(listing.entries[0].folder)
            return
        }
        // setMessage + setItems 会让文件夹列表不显示（见 ListDialog）：走 ListDialog。
        // 当前家置顶并默认选中（ListDialog 画 ✓ + 加粗）；没有当前家时无默认选中。
        val selectedIndex = listing.entries.indexOfFirst { it.isCurrentHome }.takeIf { it >= 0 }
        ListDialog.show(
            this,
            "已开放文件夹",
            "选一个文件夹作为项目之家。",
            listing.entries.map {
                when {
                    it.isCurrentHome -> "当前家 · ${it.folder}"
                    it.isProjectHome -> "本项目的家 · ${it.folder}"
                    else -> "${it.displayName} · ${it.folder}"
                }
            },
            selectedIndex = selectedIndex,
        ) { which ->
            onFolderPicked(listing.entries[which].folder)
        }
    }

    /** 填项目名（默认取文件夹名）+ 可选仓库地址，确认后注册项目 + 指定家。 */
    private fun showAddProjectNameDialog(deviceId: String, folder: String) {
        val nameInput = EditText(this).apply {
            hint = "项目名，如：wherevercode-agent"
            setText(folderBasename(folder))
            setSingleLine(true)
        }
        val repoInput = EditText(this).apply {
            hint = "仓库地址（可选），如 https://github.com/owner/repo 或 https://huggingface.co/spaces/owner/name"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(nameInput)
            addView(repoInput)
        }
        // .git 落在网盘同步目录里会被同步损坏：只警告，不拦截（见
        // SyncFolderWarning）。文件夹照样可以当作家。
        val syncWarning = syncFolderWarning(folder)
        val message = buildString {
            append("家：${deviceDisplayName(deviceId)} · $folder\n项目只住在这台设备上，不会复制到别处。")
            if (syncWarning != null) append("\n$syncWarning")
        }
        AlertDialog.Builder(this)
            .setTitle("添加项目")
            .setMessage(message)
            .setView(container)
            .setPositiveButton("添加项目") { _, _ ->
                val name = nameInput.text.toString().trim()
                val repo = repoInput.text.toString().trim().ifEmpty { null }
                saveProject(name, deviceId, folder, repo)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 存项目：先注册逻辑项目，再指定家（位置）。名不能为空；
     * 家指定失败时，本次新建的逻辑项目一起撤掉（加项目带家是一次
     * 完整动作；想只注册项目请走“注册项目”）。文件夹占用冲突由 store
     * 拒绝并如实报错。
     */
    private fun saveProject(name: String, deviceId: String, folder: String, repoUrl: String?) {
        if (name.isBlank()) {
            toast("请填写项目名。")
            return
        }
        val slug = uniqueProjectSlug(name)
        // 注册重名时显示名自动加数字后缀（2026-11-11：两个项目都叫
        // wherevercode-agent 时行名完全一样，只能靠副标题区分，极易点错）。
        val displayName = uniqueProjectDisplayName(name, existingProjectNames())
        try {
            projectStore.saveProject(Project(projectId = slug, name = displayName, repoUrl = repoUrl))
        } catch (e: Exception) {
            toast("添加项目失败：${e.message}")
            return
        }
        try {
            projectStore.saveLocation(ProjectLocation.create(slug, deviceId, folder))
        } catch (e: Exception) {
            projectStore.deleteProject(slug)
            toast("添加项目失败：${e.message}")
            return
        }
        toast("项目已添加：$displayName（${deviceDisplayName(deviceId)} · $folder）")
        projectController.refresh()
        syncProjectFolderAuthorization(deviceId, folder, displayName)
    }

    /** 已注册项目的显示名列表（注册重名判断用；读失败按空处理，不挡注册）。 */
    private fun existingProjectNames(): List<String> = try {
        projectStore.listProjects().map { it.name }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * 给已注册的项目指定家（项目详情里的“指定家…”/“换家…”）。
     * 只存位置，不动逻辑项目。主流模型是家的列表：加家不移旧家，
     * 新家存完直接设为当前家（浏览时用它）；旧家仍在列表里，
     * 可在详情里“切换家…”切回去。文件夹占用冲突由 store 拒绝。
     * （旧实现先移旧家再存新家、失败恢复旧家，是 one-home 的遗留，
     * 多家模型下不再需要。）
     */
    private fun assignProjectHome(projectId: String, deviceId: String, folder: String) {
        // .git 落在网盘同步目录里会被同步损坏：只警告，不拦截，照样指定家。
        syncFolderWarning(folder)?.let { toast(it) }
        val location = ProjectLocation.create(projectId, deviceId, folder)
        try {
            projectStore.saveLocation(location)
            projectStore.setActiveHome(projectId, location.locationId)
        } catch (e: Exception) {
            toast("指定家失败：${e.message}")
            return
        }
        val name = projectStore.getProject(projectId)?.name ?: projectId
        toast("项目“$name”的家已指定：${deviceDisplayName(deviceId)} · $folder")
        projectController.refresh()
        syncProjectFolderAuthorization(deviceId, folder, name)
    }

    /**
     * V5 跨设备开发（2026-11-12，D2 新口径）：指定家/链检出成功后，
     * 把项目文件夹同步授权给该设备上的设备 Agent。
     *
     * 机制：对该设备上每个有文件夹的 Agent，走现有 Hand 密封通道发
     * `authorize_project_folder` 动作（见
     * [wherevercode.agent.core.mcp.RemoteAgentChatPort.authorizeProjectFolder]；
     * 参数 agent_id + folder；信封 folder 用该 Agent 已授权的文件夹，
     * 凭证才能通过 Hand 校验）。Hand 侧把该文件夹逐项记入该 Agent 的
     * `auth.json` 新 key `authorized_project_folders`（精确相等，
     * D3），与现有三路授权合并。
     *
     * 失败如实报告：Hand 不在线 / 未配对 / 超时 / Hand 侧尚未实现该
     * 动作时，逐项记失败并 toast 汇总（成功几个、失败原因），绝不静
     * 默假装已授权；家本身已指定成功，不因同步失败回滚。本机家
     * （hostId "local"）不走 Hand，直接返回。
     * 注意：Hand 侧实现由另一工人按新口径重做，App 侧本次只定义调用
     * 与失败处理，集成验证待 Hand 侧落地（见 APP_NOTES）。
     */
    private fun syncProjectFolderAuthorization(deviceId: String, folder: String, projectName: String) {
        if (deviceId == ProjectLocation.LOCAL_HOME_HOST_ID) return
        Thread {
            val agents = agentFoldersOnDevice(deviceId)
            if (agents.isEmpty()) {
                runOnUiThread {
                    toast("家已指定，但设备 ${deviceDisplayName(deviceId)} 上没有设备 Agent 可同步授权：请先在设备上添加 Agent。")
                }
                return@Thread
            }
            var okCount = 0
            val failures = mutableListOf<String>()
            runBlocking {
                for (agent in agents) {
                    val target = try {
                        resolveRemoteTarget(agent)
                    } catch (_: Exception) {
                        null
                    }
                    if (target == null) {
                        failures += "${agent.displayName} 未配对/不可达"
                        continue
                    }
                    val result = authorizeProjectFolderOnce(target, folder)
                    if (result.isSuccess) {
                        okCount++
                    } else {
                        failures += "${agent.displayName}：${result.exceptionOrNull()?.message ?: "未知错误"}"
                    }
                }
            }
            runOnUiThread {
                if (failures.isEmpty()) {
                    toast("已同步授权到设备：$okCount 个 Agent 可进 $folder")
                } else {
                    toast(
                        "家已指定，但同步授权到设备失败（$okCount/${agents.size} 成功）：${
                            failures.joinToString("；").take(200)
                        }；Hand 上线或实现该动作后请重试。",
                    )
                }
            }
        }.start()
    }

    /** 一次授权调用：等终态 Result/Blocked，30 秒无回包判失败（中文原因）。 */
    private suspend fun authorizeProjectFolderOnce(
        target: RemoteTarget,
        folder: String,
    ): Result<Unit> {
        val terminal = try {
            withTimeoutOrNull(30_000) {
                remoteChatPort.authorizeProjectFolder(target, folder)
                    .firstOrNull { it is HandEvent.Result || it is HandEvent.Blocked }
            }
        } catch (e: Exception) {
            return Result.failure(e)
        } ?: return Result.failure(IllegalStateException("30 秒内无回包，Hand 可能不在线"))
        return when (terminal) {
            is HandEvent.Result -> Result.success(Unit)
            is HandEvent.Blocked -> Result.failure(IllegalStateException(terminal.reason))
            else -> Result.failure(IllegalStateException("未知回包"))
        }
    }

    /**
     * 给已注册项目指定家（项目行“···”管理单里的“指定家…”/“换家…”）：
     * 三个位置各是一颗按钮，不再有“选来源”的菜单；管理单里的改名/
     * 删除两项不动。项目页加新项目走唯一的“打开文件夹”入口（点开是
     * 三项位置单子的对话框），也不经过这里。
     */
    private fun showAssignHomeSheet(project: Project) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("给“${project.name}”指定家")
            .setView(container)
            .setNegativeButton("取消", null)
            .create()
        fun addEntry(label: String, onTap: () -> Unit) {
            container.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    dialog.dismiss()
                    onTap()
                }
            })
        }
        addEntry("打开文件夹") { showAddProjectLocalFlow(forProjectId = project.projectId) }
        addEntry("远程设备上的文件夹") { showAddProjectFlow(forProjectId = project.projectId) }
        addEntry("粘 git 仓库地址") { showAddProjectGitHostFlow(forProjectId = project.projectId) }
        dialog.show()
    }

    /**
     * git 仓库地址入口：粘地址即登记一个位置（GitHub / GitLab / Gitee
     * 三站按地址分流，家就是该仓库，Agent 经该站 API 读，不拉代码）。
     * 登记完再找设备上已有的检出链过去（见 [linkExistingCheckout]）；
     * 没有检出就如实报“代码不在设备上”，只留 git 家。
     * - 已有项目（指定家/换家）：已有有效仓库地址直接设家；没有就请用户填，
     *   先把地址存回项目上再设家。
     * - 新建项目：按仓库名建项目（名 = 仓库名），家就是该仓库；同名项目已
     *   注册过（很可能就是没家的 wherevercode-agent）就不再新建，直接给它
     *   补上仓库地址并指定 git 家。
     */
    private fun showAddProjectGitHostFlow(forProjectId: String? = null) {
        if (forProjectId != null) {
            val project = try {
                projectStore.getProject(forProjectId)
            } catch (_: Exception) {
                null
            }
            if (project == null) {
                toast("项目找不到了，请刷新项目页再试。")
                return
            }
            val existing = project.repoUrl?.let { parseGitHostRepoUrl(it) }
            if (existing != null) {
                assignGitHostHome(project)
                return
            }
            promptGitRepoUrl { url ->
                try {
                    projectStore.saveProject(project.copy(repoUrl = url))
                } catch (e: Exception) {
                    toast("保存仓库地址失败：${e.message}")
                    return@promptGitRepoUrl
                }
                assignGitHostHome(project.copy(repoUrl = url))
            }
            return
        }
        promptGitRepoUrl { url ->
            createOrAttachGitHostProject(url)
        }
    }

    /**
     * 填的不是 GitHub / GitLab / Gitee 地址时，按实说清它是什么（Space /
     * 模型仓库 / 其它 git 地址），不笼统报"不是有效链接"。
     */
    private fun invalidGitRepoUrlMessage(url: String): String {
        val parsed = parseGitRepoUrl(url)
        return when (parsed?.kind) {
            GitRepoKind.HF_SPACE -> "这是 Hugging Face Space 代码仓库，git 家只支持 GitHub / GitLab / Gitee；Space 要当作家，先在某台设备上检出，再用“打开文件夹”把它加进来。"
            GitRepoKind.HF_MODEL -> "这是 Hugging Face 模型仓库（权重），不能当作项目；要当作家请用 Space 代码仓库。"
            GitRepoKind.HF_DATASET -> "这是 Hugging Face 数据集仓库，不能当作项目；要当作家请用 Space 代码仓库。"
            else -> "仓库地址不是有效的 GitHub / GitLab / Gitee 链接。"
        }
    }

    /** 请用户填一个 git 托管仓库地址（GitHub / GitLab / Gitee）。 */
    private fun promptGitRepoUrl(onUrl: (String) -> Unit) {
        val repoInput = EditText(this).apply {
            hint = "仓库地址，如 https://github.com/owner/repo 或 https://gitlab.com/group/project"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(repoInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Git 仓库地址")
            .setMessage("填一个 GitHub / GitLab / Gitee 仓库地址，登记为项目位置（Agent 经该站 API 读，不拉代码）。私有仓库用设置里该站的授权进。某台设备上已有这个仓库的检出，会问你要不要链过去；没有就只登记地址。")
            .setView(container)
            .setPositiveButton("继续") { _, _ ->
                val url = repoInput.text.toString().trim()
                if (parseGitHostRepoUrl(url) == null) {
                    toast(invalidGitRepoUrlMessage(url))
                    return@setPositiveButton
                }
                onUrl(url)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 按 git 仓库地址新建项目（家就是该仓库）：同名项目已注册过就
     * 不再新建，直接给它补上仓库地址并指定 git 家；新建时家指定失败，
     * 本次新建的逻辑项目一起撤掉（加项目带家是一次完整动作）。
     */
    private fun createOrAttachGitHostProject(url: String) {
        val parsed = parseGitHostRepoUrl(url) ?: run {
            toast(invalidGitRepoUrlMessage(url))
            return
        }
        val existing = try {
            projectStore.getProject(slugProjectId(parsed.repoName))
        } catch (_: Exception) {
            null
        }
        if (existing != null) {
            try {
                projectStore.saveProject(existing.copy(repoUrl = url))
            } catch (e: Exception) {
                toast("保存仓库地址失败：${e.message}")
                return
            }
            assignGitHostHome(existing.copy(repoUrl = url))
            return
        }
        val slug = uniqueProjectSlug(parsed.repoName)
        val displayName = uniqueProjectDisplayName(parsed.repoName, existingProjectNames())
        try {
            projectStore.saveProject(Project(projectId = slug, name = displayName, repoUrl = url))
        } catch (e: Exception) {
            toast("添加项目失败：${e.message}")
            return
        }
        try {
            projectStore.saveLocation(
                ProjectLocation.createGitHost(slug, parsed.host, parsed.namespacePath),
            )
        } catch (e: Exception) {
            projectStore.deleteProject(slug)
            toast("添加项目失败：${e.message}")
            return
        }
        toast("项目已添加：$displayName（${parsed.host.displayName} · ${parsed.namespacePath}）")
        linkExistingCheckout(Project(projectId = slug, name = displayName, repoUrl = url), parsed.repoName)
        projectController.refresh()
    }

    /**
     * git 仓库登记后，找一台设备上已有的检出链过去（2026-11-11 主流做法：
     * git 地址只登记位置，不拉代码；代码已经在某台设备上，就链过去当作家）。
     * - 本机：工作区里与仓库同名、且里面有 .git 的文件夹，直接链为本机家。
     * - 远程设备：已开放文件夹里与仓库同名的，列出来请用户认——是该仓库
     *   的检出才点它链过去，不是就点“都不是”。
     * - 都没有：如实报“代码不在设备上”，只留 git 家（经 API 读）。
     * 链过去的家直接设为当前家（多家模型：新家存完即当前家），git 家仍在
     * 家的列表里，可在详情里“切换家…”切回去。
     */
    private fun linkExistingCheckout(project: Project, repoName: String) {
        val localDir = java.io.File(java.io.File(filesDir, "workspace"), repoName)
        if (localDir.isDirectory && java.io.File(localDir, ".git").exists()) {
            val location = ProjectLocation.createLocal(project.projectId, repoName)
            try {
                projectStore.saveLocation(location)
                projectStore.setActiveHome(project.projectId, location.locationId)
            } catch (e: Exception) {
                toast("链本机检出失败：${e.message}")
                return
            }
            toast("已链到本机已有的检出：本机 · $repoName")
            projectController.refresh()
            return
        }
        val candidates = remoteCheckoutCandidates(repoName)
        if (candidates.isEmpty()) {
            toast("代码不在设备上：${project.name} 只登记了仓库地址（经 API 读）；要让 Agent 干活，先把代码放到一台设备上，再指定家。")
            return
        }
        ListDialog.show(
            this,
            "链到设备上的检出？",
            "以下设备上有与仓库同名的文件夹。确认是该仓库的检出再点它链过去；不是就点“都不是”。",
            candidates.map { "${deviceDisplayName(it.first)} · ${it.second}" },
            negativeLabel = "都不是",
        ) { which ->
            val (deviceId, folder) = candidates[which]
            val location = ProjectLocation.create(project.projectId, deviceId, folder)
            try {
                projectStore.saveLocation(location)
                projectStore.setActiveHome(project.projectId, location.locationId)
            } catch (e: Exception) {
                toast("链检出失败：${e.message}")
                return@show
            }
            toast("已链到设备上的检出：${deviceDisplayName(deviceId)} · $folder")
            projectController.refresh()
            syncProjectFolderAuthorization(deviceId, folder, project.name)
        }
    }

    /** 各可用设备上、与仓库同名的已开放文件夹（设备 id + 文件夹）。 */
    private fun remoteCheckoutCandidates(repoName: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (device in usableProjectHostDevices()) {
            val agents = agentFoldersOnDevice(device.deviceId)
                .filter { resolveRemoteTarget(it) != null }
            val listing = try {
                hostProjectListing(device.deviceId, agents)
            } catch (_: Exception) {
                continue
            }
            for (folder in checkoutCandidates(repoName, listing.entries.map { it.folder })) {
                out += device.deviceId to folder
            }
        }
        return out
    }

    /**
     * “打开文件夹”唯一入口（2026-11-12，maintainer：项目来源收成一个）：
     * 一个入口、一个对话框、一张单子，按位置种类列三项，每项有名 + 一行说明。
     * 老项目 example.invalid 的“打开 / 导入项目”对话框有 8 个选项，它清晰的
     * 原因是一个入口、一个对话框、一张单子；V5 乱的原因是三个入口散在项目页
     * 底部、不是一个对话框。8 个老选项按种类收进这三类（本机/局域网/远程
     * 设备/网盘都是文件夹；GitHub/HF/GitLab-Gitee/Git URL 都是 git 地址登记
     * 位置），不照搬 8 个并列。新建项目不用单列：本机流程里有“新建文件夹…”。
     * forProjectId 非空 = 给已注册的项目指定家（只存位置）；指定家/换家
     * 仍走 showAssignHomeSheet 的已有流程。
     */
    private fun showOpenFolderEntry(forProjectId: String? = null) {
        val options = openFolderEntryOptions()
        ListDialog.showWithDescriptions(
            this,
            "打开文件夹",
            "选一个位置打开它作为项目之家。",
            options.map { it.name to it.description },
        ) { which ->
            when (options[which].kind) {
                OpenFolderLocationKind.LOCAL -> showAddProjectLocalFlow(forProjectId)
                OpenFolderLocationKind.REMOTE -> showAddProjectFlow(forProjectId)
                OpenFolderLocationKind.GIT -> showAddProjectGitHostFlow(forProjectId)
            }
        }
    }

    /**
     * 本机文件夹流程（“打开文件夹”唯一入口里的“本机文件夹”；主流做法：
     * VS Code 只有一个“打开文件夹”，本机/挂载/同步文件夹都是文件夹，
     * 不分项）：选本机工作区里已有的文件夹，或新建一个当作家。只从已开放
     * 的文件夹里选，不浏览文件系统（与远程设备的“已开放文件夹”下拉同一口径）。
     * 新建文件夹直接在本机工作区下建。
     */
    private fun showAddProjectLocalFlow(forProjectId: String? = null) {
        val workspaceDir = java.io.File(filesDir, "workspace").apply { mkdirs() }
        val existing = try {
            workspaceDir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val items = existing.map { "文件夹 · $it" } + "新建文件夹…"
        // setMessage + setItems 会让文件夹列表不显示（见 ListDialog）：走 ListDialog。
        ListDialog.show(
            this,
            "打开文件夹",
            "选本机工作区里已有的文件夹，或新建一个作为项目之家。挂载/同步下来的文件夹也是文件夹，直接选。",
            items,
        ) { which ->
            if (which < existing.size) {
                onLocalFolderPicked(existing[which], forProjectId)
            } else {
                promptNewLocalFolder { name ->
                    onLocalFolderPicked(name, forProjectId)
                }
            }
        }
    }

    /** 新建本机文件夹：只填一个文件夹名（单段，不含 /），直接建在本机工作区下。 */
    private fun promptNewLocalFolder(onCreated: (String) -> Unit) {
        val nameInput = EditText(this).apply {
            hint = "文件夹名，如 my-project"
            setSingleLine(true)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(nameInput)
        }
        AlertDialog.Builder(this)
            .setTitle("新建文件夹")
            .setMessage("在本机工作区下新建一个文件夹作为项目之家。")
            .setView(container)
            .setPositiveButton("新建") { _, _ ->
                val name = nameInput.text.toString().trim()
                if ("/" in name || !isValidLocalWorkspacePath(name)) {
                    toast("文件夹名不合法：填一个单段文件夹名，不要含 / 或 ..。")
                    return@setPositiveButton
                }
                val dir = java.io.File(java.io.File(filesDir, "workspace"), name)
                if (dir.exists() && !dir.isDirectory) {
                    toast("已有同名文件，不是文件夹。")
                    return@setPositiveButton
                }
                if (!dir.exists() && !dir.mkdirs()) {
                    toast("新建文件夹失败。")
                    return@setPositiveButton
                }
                onCreated(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选好本机文件夹：已有项目就指定/换家，新项目就填名创建。 */
    private fun onLocalFolderPicked(relativePath: String, forProjectId: String?) {
        if (forProjectId != null) {
            assignLocalProjectHome(forProjectId, relativePath)
        } else {
            showAddProjectLocalNameDialog(relativePath)
        }
    }

    /** 填项目名（默认取文件夹名）+ 可选仓库地址，确认后注册项目 + 指定本机家。 */
    private fun showAddProjectLocalNameDialog(relativePath: String) {
        val nameInput = EditText(this).apply {
            hint = "项目名，如：wherevercode-agent"
            setText(folderBasename(relativePath))
            setSingleLine(true)
        }
        val repoInput = EditText(this).apply {
            hint = "仓库地址（可选），如 https://github.com/owner/repo 或 https://huggingface.co/spaces/owner/name"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(nameInput)
            addView(repoInput)
        }
        AlertDialog.Builder(this)
            .setTitle("添加项目")
            .setMessage("家：本机 · $relativePath\n项目只住在本机工作区里，不会复制到别处。")
            .setView(container)
            .setPositiveButton("添加项目") { _, _ ->
                val name = nameInput.text.toString().trim()
                val repo = repoInput.text.toString().trim().ifEmpty { null }
                saveLocalProject(name, relativePath, repo)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 存项目（本机家）：先注册逻辑项目，再指定家。名不能为空；
     * 家指定失败时，本次新建的逻辑项目一起撤掉（加项目带家是一次
     * 完整动作；想只注册项目请走“注册项目”）。one-home 冲突由 store
     * 拒绝并如实报错。
     */
    private fun saveLocalProject(name: String, relativePath: String, repoUrl: String?) {
        if (name.isBlank()) {
            toast("请填写项目名。")
            return
        }
        if (!isValidLocalWorkspacePath(relativePath)) {
            toast("本机文件夹不合法。")
            return
        }
        val slug = uniqueProjectSlug(name)
        val displayName = uniqueProjectDisplayName(name, existingProjectNames())
        try {
            projectStore.saveProject(Project(projectId = slug, name = displayName, repoUrl = repoUrl))
        } catch (e: Exception) {
            toast("添加项目失败：${e.message}")
            return
        }
        try {
            projectStore.saveLocation(ProjectLocation.createLocal(slug, relativePath))
        } catch (e: Exception) {
            projectStore.deleteProject(slug)
            toast("添加项目失败：${e.message}")
            return
        }
        toast("项目已添加：$displayName（本机 · $relativePath）")
        projectController.refresh()
    }

    /**
     * 给已注册的项目指定本机家（长按管理的“指定家…”/“换家…”）。
     * 只存位置，不动逻辑项目。已有家时是换家：先移走旧位置再存新家；
     * 新家存失败时把旧家恢复，项目不丢家。one-home 冲突由 store 拒绝。
     */
    private fun assignLocalProjectHome(projectId: String, relativePath: String) {
        if (!isValidLocalWorkspacePath(relativePath)) {
            toast("本机文件夹不合法。")
            return
        }
        val oldHome = try {
            projectStore.locationsForProject(projectId).firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (oldHome != null) {
            try {
                projectStore.removeLocation(oldHome.locationId)
            } catch (_: Exception) {
                // 旧家移不走也继续：保存时 store 会报 one-home 冲突。
            }
        }
        try {
            projectStore.saveLocation(ProjectLocation.createLocal(projectId, relativePath))
        } catch (e: Exception) {
            if (oldHome != null) {
                try {
                    projectStore.saveLocation(oldHome)
                } catch (_: Exception) {
                    // 旧家恢复失败：项目暂为未指定家，用户可长按项目重新指定。
                }
            }
            toast("指定家失败：${e.message}")
            return
        }
        val name = projectStore.getProject(projectId)?.name ?: projectId
        toast("项目“$name”的家已指定：本机 · $relativePath")
        projectController.refresh()
    }

    /** 把项目的家设为它的 git 仓库（git 家）。
     * 主流模型是家的列表：加 git 家不移旧家，存完直接设为当前家。 */
    private fun assignGitHostHome(project: Project) {
        val parsed = project.repoUrl?.let { parseGitHostRepoUrl(it) }
        if (parsed == null) {
            toast("该项目的仓库地址不是有效的 GitHub / GitLab / Gitee 链接，无法把家设为 git 仓库。")
            return
        }
        val location = ProjectLocation.createGitHost(
            project.projectId,
            parsed.host,
            parsed.namespacePath,
        )
        try {
            projectStore.saveLocation(location)
            projectStore.setActiveHome(project.projectId, location.locationId)
        } catch (e: Exception) {
            toast("指定家失败：${e.message}")
            return
        }
        toast("项目“${project.name}”的家已指定：${parsed.host.displayName} · ${parsed.namespacePath}")
        linkExistingCheckout(project, parsed.repoName)
        projectController.refresh()
    }

    /**
     * 点已注册项目 = 干净地打开它（2026-11-11，maintainer：点项目不能 dumped 出
     * 一堆管理动作）：有家就浏览家内容（GitHub 家列仓库根目录，设备家经
     * Hand 浏览文件夹，本机家打开本机文件树），无家就留在列表页——下面
     * 只有一个“打开文件夹”入口，点开它选一个位置给它指定家。增删改添
     * 不在主点击上，长按项目行才出管理。
     */
    private fun openProject(project: Project) {
        val home = try {
            projectStore.locationsForProject(project.projectId).firstOrNull()
        } catch (_: Exception) {
            null
        }
        if (home == null) {
            toast("该项目还没有家：点下面的“打开文件夹”，选一个位置给它指定家。")
            projectController.refresh()
            return
        }
        browseProjectFolder(project)
    }

    /**
     * 项目详情里的“浏览文件夹”：经 Hand list 列家文件夹。
     * 先取项目的当前家（active home；未设过时为第一个家），再找一家
     * 文件夹覆盖该位置的 Agent（凭证 folder 须是该 Agent 在设备上的
     * 授权文件夹，项目文件夹是它本身或它下面的子路径）；找不到就如实
     * 说该文件夹未授权，不伪造浏览结果。项目未指定家时如实提示，不打开浏览。
     *
     * git 家不走 Hand：经该站 API 列仓库根目录（见 [browseGitHostRepo]）。
     */
    /**
     * GitHub 闭环（2026-11-11）：聊天项目管理，见 [ChatProjectBinding]。
     * 只存项目 id（SharedPreferences `wherevercode_chat`）；新对话保留
     * 上次的项目，不清空。读/写失败一律返回 null，不抛错、不伪造项目。
     */
    private fun loadChatProjectId() {
        // 触发 ChatProjectBinding 懒加载（首次访问时从 prefs 读出上次的绑定）。
        chatProjectBinding.projectId
    }

    /** 切换聊天项目（点项目行 / 聊天选项目入口都走这里）：立即生效，横幅即时更新。 */
    private fun setChatProject(projectId: String?) {
        chatProjectBinding.setProject(projectId)
        updateChatRouteBanner()
    }

    /**
     * 项目行点选开关（2026-11-11，maintainer：点一下选、再点一下取消，
     * 直接在列表上做，不弹窗、不分步）。选定 = 绑定为当前聊天项目
     * （与聊天里选项目同一路）；取消选定 = 清除绑定。改完重画列表，
     * 选中态（对勾 + 高亮 + “进入”）即时更新。
     */
    private fun toggleChatProject(project: Project) {
        if (chatProjectBinding.projectId == project.projectId) {
            setChatProject(null)
            note("已取消选择项目：${project.name}。")
        } else {
            setChatProject(project.projectId)
            note("已选项目：${project.name}。聊天将用它（横幅已更新）。")
        }
        projectController.refresh()
    }

    /** 当前聊天项目的 git 家（当前家是 git 家才返回；其余返回 null）。
     * 派活按当前家路由（2026-11-12）：项目有 GitHub 家、但当前家是
     * DEVICE 家（Mac）时不走大脑 git API 路——直走远端通道派给该
     * 设备上的 Agent。旧口径取家的列表第一项，有 GitHub 家就触发，
     * 与当前家无关（2026-11-12 09:46 实证已证实是断的）。 */
    private fun resolveChatGitHostProject(): Pair<Project, ProjectLocation>? {
        val projectId = chatProjectBinding.projectId ?: return null
        val withHomes = try {
            projectStore.projectWithHomes(projectId)
        } catch (_: Exception) {
            null
        } ?: return null
        return activeGitHome(withHomes)
    }

    /** 当前聊天项目的 git 家坐标（供 ChatViewModel 任务改道用）。 */
    private fun resolveChatGitHostRef(): GitRepoRef? {
        val (project, location) = resolveChatGitHostProject() ?: return null
        return gitHostProjectPort.resolveRepoRef(project, location)
    }

    /**
     * 聊天选项目（2026-11-11，maintainer：新对话清空绑定后聊天里选不了项目）。
     * 弹出已注册项目列表，点即切换聊天绑定（[setChatProject]，横幅立即
     * 更新）；无项目时不编假条目，给“去项目页添加”入口。项目页入口仍在
     * 对话框的“打开项目页”按钮里（"+" 菜单 → 项目原来是进项目页）。
     */
    private fun showChatProjectPicker() {
        val projects = try {
            projectStore.listProjects()
        } catch (_: Exception) {
            emptyList()
        }
        ChatProjectPickerDialog.show(
            context = this,
            projects = projects,
            homeLabel = ::projectHomeLabel,
            onPick = { projectId ->
                setChatProject(projectId)
                val name = try {
                    projectStore.getProject(projectId)?.name
                } catch (_: Exception) {
                    null
                } ?: projectId
                toast("聊天已切换到项目：$name")
            },
            onOpenProjects = { showEntry(DrawerController.ID_PROJECTS) },
        )
    }

    /** 选项目列表 / 横幅里的家标签：当前家的标签（不是家的列表第一项）。 */
    private fun projectHomeLabel(project: Project): String {
        val home = try {
            projectStore.getActiveHome(project.projectId)
        } catch (_: Exception) {
            null
        }
        return when {
            home == null -> "未指定家"
            home.gitHost() != null -> "${home.gitHost()?.displayName} · ${home.workspacePath}"
            home.homeKind == ProjectHomeKind.LOCAL -> "本机 · ${home.workspacePath}"
            else -> "设备 · ${deviceDisplayName(home.hostId)}"
        }
    }

    private fun browseProjectFolder(project: Project) {
        // GitHub 闭环（2026-11-11）：打开哪个项目，对话就关联哪个项目
        // （横幅会显示；GitHub 家的任务由大脑经 GitHub API 执行）。
        setChatProject(project.projectId)
        val location = try {
            projectStore.getActiveHome(project.projectId)
        } catch (_: Exception) {
            null
        }
        if (location == null) {
            toast("该项目未指定家：请先指定家。")
            return
        }
        if (location.homeKind == ProjectHomeKind.LOCAL) {
            projectController.openLocalFolder(location.workspacePath)
            return
        }
        // 主流补齐（2026-11-11）：打开项目时把当前会话绑到这个项目上
        // （会话 → 项目；换项目不丢会话，任务与对话按 id 存，与家无关）。
        // 本基底里“打开项目”就是浏览家文件夹（项目详情里的“浏览文件夹”）；
        // 合包分支的 openProject 若另有入口，也应在这里同款绑定。
        chatViewModel.bindCurrentSessionToProject(project.projectId)
        if (location.gitHost() != null) {
            browseGitHostRepo(project, location)
            return
        }
        val agent = agentFoldersOnDevice(location.hostId).firstOrNull { record ->
            val folder = record.deviceFolder ?: return@firstOrNull false
            resolveProjectRelativePath(folder, location.workspacePath) != null
        }
        if (agent == null) {
            toast("该项目文件夹未授权给设备上的任何 Agent，无法浏览：请先在设备上授权该文件夹。")
            return
        }
        val target = resolveRemoteTarget(agent)
        if (target == null) {
            toast("设备未配对，无法浏览项目文件夹。")
            return
        }
        val relative = resolveProjectRelativePath(agent.deviceFolder!!, location.workspacePath) ?: ""
        remoteFolderPicker.browseFolder(
            target,
            initialRelativePath = relative,
            title = "浏览 ${project.name}",
        )
    }

    /**
     * git 家的“浏览仓库”：经该站 API 列仓库根目录（只读）。
     * 没配授权时先请用户填自己的该站授权（存在本机 Keystore）。
     * 网络调用走后台线程（仿 testGenericModels 的 Thread + runBlocking），
     * 不堵 UI；失败如实报错，不编假目录。
     */
    private fun browseGitHostRepo(project: Project, location: ProjectLocation) {
        val host = location.gitHost() ?: run {
            toast("该项目不是 git 家。")
            return
        }
        ensureGitHostToken(host) {
            toast("正在读取 ${host.displayName} 仓库 ${location.workspacePath}…")
            Thread {
                val result = runBlocking { gitHostProjectPort.listRoot(project, location) }
                runOnUiThread {
                    result.onSuccess { entries ->
                        if (entries.isEmpty()) {
                            toast("该仓库是空的（没有文件）。")
                            return@onSuccess
                        }
                        val lines = entries.sortedWith(
                            compareBy({ it.type != "dir" }, { it.name.lowercase() }),
                        ).joinToString("\n") { entry ->
                            if (entry.type == "dir") "[目录] ${entry.name}/" else "[文件] ${entry.name}"
                        }
                        AlertDialog.Builder(this)
                            .setTitle("浏览 ${project.name}（${host.displayName} · ${location.workspacePath}）")
                            .setMessage(lines)
                            .setPositiveButton("关闭", null)
                            .show()
                    }.onFailure { e ->
                        toast("读取 ${host.displayName} 仓库失败：${e.message}")
                    }
                }
            }.start()
        }
    }

    /**
     * git 家授权守门：已有就直接跑 [onReady]；没有就弹框请用户填自己
     * 的该站授权（token），保存到本机 Keystore 后再跑。授权只存本机，
     * 绝不经过聊天、不发给 Muse、不写进日志。
     */
    private fun ensureGitHostToken(host: GitHost, onReady: () -> Unit) {
        // Keystore 读失败时按“未配”处理，走填 token 的弹框；
        // 保存那步自带 try/catch 会如实报失败，不静默吞掉。
        val hasToken = try {
            gitHostCredentialStore.hasToken(host)
        } catch (e: Exception) {
            false
        }
        if (hasToken) {
            onReady()
            return
        }
        val tokenInput = EditText(this).apply {
            hint = "${host.displayName} 授权（token）"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(tokenInput)
        }
        AlertDialog.Builder(this)
            .setTitle("填写 ${host.displayName} 授权")
            .setMessage("要读这个 ${host.displayName} 仓库，需要你自己的 ${host.displayName} 授权。授权只存在本机，不会发给任何人。\n${host.tokenHelp}")
            .setView(container)
            .setPositiveButton("保存授权") { _, _ ->
                val token = tokenInput.text.toString().trim()
                if (token.isBlank()) {
                    toast("授权不能为空。")
                    return@setPositiveButton
                }
                try {
                    gitHostCredentialStore.saveToken(host, token)
                } catch (e: Exception) {
                    toast("保存授权失败：${e.message}")
                    return@setPositiveButton
                }
                toast("${host.displayName} 授权已保存到本机。")
                onReady()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateScrollBottomButton() {
        if (!::buttonScrollBottom.isInitialized) return
        buttonScrollBottom.visibility = if (isNearBottom()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------
    // Approval visibility (stage 3, U4)
    // ------------------------------------------------------------------

    /**
     * 审批倒计时 ticker：每秒读一次待审批（内存字段，无 IO），刷新
     * 聊天页/任务页的等待条。仅在运行中或有等待审批时续跑，
     * 空闲即停，不空转耗电。底部导航徽标已随四 Tab 删除（批 2 第 4 步）：
     * 运行中指示由聊天页内控制行、审批等待由两处等待条承接，不新增徽标。
     */
    private fun pokeApprovalTicker() {
        if (approvalTickerActive) return
        approvalTickerActive = true
        approvalHandler.post(approvalTick)
    }

    private fun tickApproval() {
        val pending = try {
            toolApprovalGate.pendingApproval()
        } catch (_: Exception) {
            null
        }
        val running = try {
            chatViewModel.stateNow().running
        } catch (_: Exception) {
            false
        }
        if (pending != null) {
            val remaining = ((pending.deadlineEpochMillis - System.currentTimeMillis()) / 1000)
                .coerceAtLeast(0)
            val text = "等待审批：${pending.tool}" +
                (pending.target?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") +
                "，剩余 $remaining 秒，超时自动拒绝"
            approvalBanner.text = text
            approvalBanner.visibility = View.VISIBLE
            taskApprovalBanner.text = text
            taskApprovalBanner.visibility = View.VISIBLE
        } else {
            approvalBanner.visibility = View.GONE
            taskApprovalBanner.visibility = View.GONE
        }
        if (currentEntry == DrawerController.ID_TASKS) refreshApprovalHistory()
        if (pending != null || running) {
            approvalHandler.postDelayed(approvalTick, 1000)
        } else {
            approvalTickerActive = false
        }
    }

    /** 任务页审批历史（最新 20 条倒序；签名去重，内容不变不重建）。 */
    private fun refreshApprovalHistory() {
        if (!::approvalHistoryList.isInitialized) return
        val history = try {
            toolApprovalGate.approvalHistory()
        } catch (_: Exception) {
            return
        }
        val sig = history.joinToString("|") { "${it.actionId}:${it.outcome}:${it.atEpochSeconds}" }
        if (sig == lastApprovalHistorySig) return
        lastApprovalHistorySig = sig
        approvalHistoryList.removeAllViews()
        if (history.isEmpty()) {
            approvalHistoryList.addView(hint("暂无审批记录。"))
            return
        }
        history.takeLast(20).reversed().forEach { record ->
            val line = buildString {
                append("${UiKit.shortTime(record.atEpochSeconds)} · ${record.tool}")
                record.target?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                append(" · ${approvalOutcomeLabel(record.outcome, record.cached)}")
            }
            approvalHistoryList.addView(
                UiKit.text(this, line, 11f, R.color.text_secondary).apply {
                    setBackgroundResource(R.drawable.bg_system_note)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(5) }
                },
            )
        }
    }

    private fun sendPrompt() {
        val prompt = inputPrompt.text.toString().trim()
        // V5 按主流改：空文本 + 有附件也可以发（只发一张图/一个文件也行）。
        val attachments = pendingAttachments.toList()
        if (prompt.isEmpty() && attachments.isEmpty()) return
        // V5 C2（批 2）：HomeScreenController 门控——空输入或发送中
        // 在这里就拒绝，不进 ChatViewModel；停止二次确认（C1）不受
        // 影响，它包在停止按钮的监听器里，不走这条路。
        homeScreenController.setInput(prompt)
        if (!homeScreenController.beginSend(allowEmptyText = attachments.isNotEmpty())) return
        // Agent 开关（2026-11-12）：会话里对方 Agent 刚被停用时，本条
        // 不发——现读记录（开关翻完立即生效，不用重启、不用重开会话）；
        // 大脑对话（selectedAgent 为 null）不受影响。
        val currentAgentId = if (::chatViewModel.isInitialized) chatViewModel.selectedAgent?.agentId else null
        if (currentAgentId != null) {
            val liveEnabled = try {
                agentStore.getAgent(currentAgentId)?.enabled ?: true
            } catch (_: Exception) {
                true
            }
            if (!liveEnabled) {
                homeScreenController.cancelSend()
                updateSendButton()
                toast("该 Agent 已停用：在 Agent 页重新启用后可继续对话。")
                return
            }
        }
        updateSendButton()
        // 大脑/Agent 边界（maintainer 2026-11-12）：显式标任务——输入框
        // “任务”开关置起时，这条消息一定按任务处理（建派活任务行）。
        // 接受后清开关（只影响一条消息）、刷新胶囊；被拒时保留，
        // 下次重发仍带。
        val forceTask = chatViewModel.isForceTaskNext()
        val result = chatViewModel.send(prompt, attachments, webSearchEnabled, forceTask = forceTask)
        if (result.accepted) {
            if (forceTask) {
                chatViewModel.clearForceTaskNext()
                updateTaskMarkChip()
            }
            // 输入框立即清空（原有体验）；模型保持“发送中”，直到轮次
            // 到终态，observer 再调 finishSend()（见 onCreate）。
            // 附件随本轮发出，清空待发条带。
            inputPrompt.text.clear()
            pendingAttachments.clear()
            refreshPendingStrip()
            updateSendButton()
            hideKeyboard()
            inConversation = true
            showEntry(DrawerController.ID_NEW_CONVERSATION)
        } else {
            homeScreenController.cancelSend()
            updateSendButton()
            if (result.message != null) {
                showEntry(DrawerController.ID_NEW_CONVERSATION)
            }
            // V5 修复（2026-11-11）：远端 Agent 发送被路由拒了（“未接入”），
            // 用测试同款探针后台重验一次——测试能通过的设备，对话也必须能发出去。
            val agent = chatViewModel.selectedAgent
            if (agent?.deviceId != null) reverifyRemoteAgent(agent)
        }
    }

    /**
     * V5 按主流改（maintainer 2026-11-11）：“+”菜单附件选择器。
     * - 相机：TakePicture + FileProvider（照片先落 cacheDir/camera/）；
     * - 相册/文件：GetContent（SAF 选图 / 选文件）。
     * 选到的内容在 IO 线程拷进 App 私有附件目录再进待发条带；
     * 失败（太大/读不出/图片处理失败）如实 toast，不静默丢附件。
     */
    private fun registerAttachmentLaunchers() {
        takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) onPhotoTaken() else pendingPhotoFile?.delete()
            pendingPhotoFile = null
            pendingPhotoUri = null
        }
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onPickedContentUri(uri, ChatAttachmentKind.IMAGE, "图片")
        }
        pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onPickedContentUri(uri, ChatAttachmentKind.FILE, "文件")
        }
        requestCameraPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) launchCamera() else toast("需要相机权限才能拍照")
            }
    }

    private fun launchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        val photoFile = File(cacheDir, "camera/${UUID.randomUUID()}.jpg").apply {
            parentFile?.mkdirs()
        }
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        } catch (_: Exception) {
            toast("相机启动失败：无法创建照片文件")
            return
        }
        pendingPhotoFile = photoFile
        pendingPhotoUri = uri
        takePhotoLauncher.launch(uri)
    }

    private fun onPhotoTaken() {
        val photoFile = pendingPhotoFile ?: return
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val displayName = "IMG_$stamp.jpg"
        lifecycleScope.launch(Dispatchers.IO) {
            val moved = ChatAttachments.moveIntoAttachmentsDir(this@MainActivity, photoFile, displayName)
            if (moved == null) {
                withContext(Dispatchers.Main) { toast("照片保存失败，没法发这张图") }
                return@launch
            }
            val dataUrl = ChatAttachments.buildImageDataUrl(moved)
            if (dataUrl == null) {
                withContext(Dispatchers.Main) { toast("照片处理失败，没法发这张图") }
                return@launch
            }
            val contentUri = try {
                FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", moved).toString()
            } catch (_: Exception) {
                null
            }
            val attachment = ChatAttachment(
                id = UUID.randomUUID().toString(),
                kind = ChatAttachmentKind.IMAGE,
                displayName = displayName,
                mimeType = "image/jpeg",
                sizeBytes = moved.length(),
                localFile = moved,
                imageDataUrl = dataUrl,
                contentUri = contentUri,
            )
            withContext(Dispatchers.Main) {
                pendingAttachments += attachment
                refreshPendingStrip()
                updateSendButton()
            }
        }
    }

    private fun onPickedContentUri(uri: Uri, kind: ChatAttachmentKind, fallbackName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val displayName = ChatAttachments.displayNameOf(this@MainActivity, uri, fallbackName)
            val mimeType = try {
                contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            }
            val file = ChatAttachments.copyIntoAttachmentsDir(this@MainActivity, uri, displayName)
            if (file == null) {
                withContext(Dispatchers.Main) { toast("附件太大或读不出来（单件上限 20 MB）") }
                return@launch
            }
            val isImage = ChatAttachments.isImage(displayName, mimeType)
            val effectiveKind = if (isImage) ChatAttachmentKind.IMAGE else kind
            val textContent =
                if (!isImage && ChatAttachments.isText(displayName, mimeType)) {
                    ChatAttachments.readTextContent(file)
                } else {
                    null
                }
            val dataUrl = if (isImage) ChatAttachments.buildImageDataUrl(file) else null
            if (isImage && dataUrl == null) {
                withContext(Dispatchers.Main) { toast("图片处理失败，没法发这张图") }
                return@launch
            }
            val contentUri = try {
                FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file).toString()
            } catch (_: Exception) {
                null
            }
            val attachment = ChatAttachment(
                id = UUID.randomUUID().toString(),
                kind = effectiveKind,
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = file.length(),
                localFile = file,
                textContent = textContent,
                imageDataUrl = dataUrl,
                contentUri = contentUri,
            )
            withContext(Dispatchers.Main) {
                pendingAttachments += attachment
                refreshPendingStrip()
                updateSendButton()
            }
        }
    }

    /**
     * 待发附件条带：输入框上方一横排小芯片（图片：名 / 文件：名 + ✕）。
     * 点 ✕ 移除；发出去后清空。条带建在 inputBox 最上方（EditText 之前）。
     */
    private fun refreshPendingStrip() {
        val inputBox = screenChat.findViewById<LinearLayout>(R.id.inputBox) ?: return
        if (pendingStrip == null) {
            pendingStrip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
            }
            inputBox.addView(pendingStrip, 0)
        }
        val strip = pendingStrip ?: return
        strip.removeAllViews()
        if (pendingAttachments.isEmpty()) {
            strip.visibility = View.GONE
            return
        }
        strip.visibility = View.VISIBLE
        pendingAttachments.forEach { attachment ->
            val label = if (attachment.kind == ChatAttachmentKind.IMAGE) {
                "图片：${attachment.displayName}"
            } else {
                "文件：${attachment.displayName}（${ChatAttachments.formatSize(attachment.sizeBytes)}）"
            }
            val chip = UiKit.text(this, "$label ✕", 12f, R.color.text_primary).apply {
                setBackgroundResource(R.drawable.bg_chip_home)
                setPadding(UiKit.dp(this@MainActivity, 10), UiKit.dp(this@MainActivity, 6), UiKit.dp(this@MainActivity, 10), UiKit.dp(this@MainActivity, 6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginEnd = UiKit.dp(this@MainActivity, 8)
                    topMargin = UiKit.dp(this@MainActivity, 4)
                    bottomMargin = UiKit.dp(this@MainActivity, 4)
                }
                contentDescription = "移除附件：${attachment.displayName}"
                setOnClickListener {
                    pendingAttachments.remove(attachment)
                    refreshPendingStrip()
                    updateSendButton()
                }
            }
            strip.addView(chip)
        }
    }

    private fun hideKeyboard() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        manager.hideSoftInputFromWindow(inputPrompt.windowToken, 0)
    }

    private fun renderChat(state: ChatUiState) {
        updateChatRouteBanner()
        val stickToBottom = isNearBottom()
        val wanted = state.turns.map { it.id }.toSet()
        val stale = turnViews.keys.filter { it !in wanted }
        stale.forEach { id ->
            turnViews.remove(id)?.let { messageList.removeView(it) }
            turnRevisions.remove(id)
        }
        state.turns.forEach { turn ->
            val view = turnViews.getOrPut(turn.id) {
                ChatTurnView(
                    this,
                    onCopy = { text -> copyToClipboard(text) },
                    onRegenerate = { prompt ->
                        // V5 C2（批 2）：重新生成同样走首页发送门控。
                        homeScreenController.setInput(prompt)
                        if (homeScreenController.beginSend()) {
                            val result = chatViewModel.send(prompt)
                            if (!result.accepted) {
                                homeScreenController.cancelSend()
                                toast(result.message ?: "无法重新生成")
                                // V5 修复（2026-11-11）：与 sendPrompt 同款重验，
                                // 远端 Agent 被拒时用测试探针后台再核对一次。
                                val agent = chatViewModel.selectedAgent
                                if (agent?.deviceId != null) reverifyRemoteAgent(agent)
                            }
                            updateSendButton()
                        } else {
                            toast("上一轮还在执行，请稍后再试")
                        }
                    },
                ).also { messageList.addView(it) }
            }
            if (turnRevisions[turn.id] != turn.revision) {
                view.bind(turn)
                turnRevisions[turn.id] = turn.revision
            }
        }
        if (state.turns.isEmpty()) {
            // V5 C2（批 2）：问候只在“未手动关闭、且尚未开始聊天”时出现。
            if (homeScreenController.greetingVisible()) {
                if (welcomeView == null) {
                    welcomeView = buildWelcomeView().also { messageList.addView(it) }
                }
                welcomeView?.visibility = View.VISIBLE
            } else {
                welcomeView?.visibility = View.GONE
            }
        } else {
            // 有轮次即已开始聊天：首页转为消息流，问候不再出现。
            if (!homeScreenController.state().conversationStarted) {
                homeScreenController.onConversationStarted()
            }
            welcomeView?.visibility = View.GONE
        }
        if (stickToBottom || state.turns.size > lastTurnCount) {
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
        lastTurnCount = state.turns.size
        updateSendButton()
    }

    private fun isNearBottom(): Boolean {
        val child = chatScroll.getChildAt(0) ?: return true
        val distance = child.bottom - (chatScroll.height + chatScroll.scrollY)
        return distance < UiKit.dp(this, 140)
    }

    /**
     * V5 C2-3-R1c：问候区照 v4.3-01 简洁首页——居中品牌 + 问候 + 引导文案 +
     * 4 个建议 pill（点按填输入框）。问候与建议均可关闭（行为保留）；
     * V5 原型图的 4 个导航入口由抽屉承接（R2）；4 个建议 pill 只填输入框，
     * 不代发、不伪装已发送。
     * 配色走 home_* token（浅色默认 Claude 极简，深色档原型霓虹）。
     */
    private fun buildWelcomeView(): View {
        val card = UiKit.vertical(this).apply {
            // 原型图问候直接落在首页底色上，不另套大卡片。
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8); bottomMargin = dp(12) }
        }
        card.addView(
            UiKit.text(this, "✕ 关闭问候", 11f, R.color.home_text_secondary).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                gravity = android.view.Gravity.END
                setPadding(0, 0, 0, dp(4))
                setOnClickListener {
                    // V5 C2（批 2）：问候可关闭，关闭后不再出现。
                    homeScreenController.dismissGreeting()
                    welcomeView?.let { messageList.removeView(it) }
                    welcomeView = null
                    renderChat(chatViewModel.stateNow())
                }
            },
        )
        // V5 C2-3-R4 (v4.3-01)：中央品牌——紫色 ∞ 标志（与抽屉品牌头
        // 同一做法：UiKit.text 的 "∞" + home_gradient_start，见
        // DrawerView.addBrandHeader）+ WhereverCode + 副标题（已是
        // 问候保持轻量；产品名已在顶栏显示。
        // V5 C2-3-R4c（隐私修正）：问候固定“你好”，不读已存身份、
        // 不显示真人名字（见 HomeScreenController.greeting）。
        val greetingView =
            UiKit.text(this, HomeScreenController.greeting(), 22f, R.color.home_text, bold = true).apply {
                gravity = android.view.Gravity.CENTER
            }
        card.addView(greetingView)
        card.addView(
            UiKit.text(this, "有什么想一起解决的？", 14f, R.color.home_text_secondary).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(4), 0, dp(14))
            },
        )
        // V5 C2-3-R1d（v4.3-01）：4 个建议 pill 取自入口注册表
        // （唯一事实来源）；点按把意图填进输入框，等用户补齐细节再
        // 发送（建议只填，不代发）。原型图的 4 个导航入口（建议项目/
        // 连接设备/Agent 团队/任务中心）由抽屉承接（R2）。
        if (homeScreenController.suggestionsVisible()) {
            EntryRegistry.homeSuggestions().take(2).chunked(2).forEach { rowSuggestions ->
                val row = UiKit.horizontal(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(8) }
                }
                rowSuggestions.forEachIndexed { index, entry ->
                    row.addView(
                        homeSuggestionPill(entry.iconGlyph, entry.title) { applySuggestion(entry.target) },
                        LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                            marginEnd = if (index == 0) dp(8) else 0
                        },
                    )
                }
                card.addView(row)
            }
            card.addView(
                UiKit.text(this, "收起建议", 11f, R.color.home_text_secondary).apply {
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(2), 0, 0)
                    setOnClickListener {
                        homeScreenController.dismissSuggestions()
                        welcomeView?.let { messageList.removeView(it) }
                        welcomeView = null
                        renderChat(chatViewModel.stateNow())
                    }
                },
            )
        }
        return card
    }

    /** V5 C2-3-R1c：问候区建议 pill（v4.3-01）：白底细边框圆 pill + 字标 + 建议名。 */
    private fun homeSuggestionPill(glyph: String, label: String, onClick: () -> Unit): View {
        val row = UiKit.horizontal(this).apply {
            background = UiKit.rounded(this@MainActivity, R.color.home_card, 999, R.color.home_card_border, 1)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(
            UiKit.text(this, glyph, 13f, R.color.home_link, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            },
        )
        row.addView(UiKit.text(this, label, 13f, R.color.home_text))
        return row
    }

    /**
     * V5 C2-3-R1c：建议 pill 点按 → 把意图填进输入框并聚焦，等用户补齐
     * 细节再发送（建议只填，不代发、不伪造已发送）。
     */
    private fun applySuggestion(text: String) {
        if (!::inputPrompt.isInitialized) return
        inputPrompt.setText(text)
        inputPrompt.setSelection(text.length)
        inputPrompt.requestFocus()
    }

    /**
     * V5 C2-3-R1d：按入口注册表打开入口（首页与抽屉的唯一事实来源）。
     * 已实现 → 按目标打开；待开发 → 只给提示，绝不伪装已实现。
     * SCREEN 类入口由父入口带参数打开（任务详情带任务 id），不走这里。
     * ACTION 类入口是应用内动作（如 nav_theme → cycleTheme()）。
     */
    private fun openRegistryEntry(id: String) {
        val entry = EntryRegistry.find(id) ?: return
        if (entry.status == EntryRegistry.Status.TODO) {
            toast(entry.todoMessage ?: "${entry.title}待开发")
            return
        }
        // 控制台深链：抽屉“待审批”/“运行日志”入口打开控制台并定位到对应模块。
        consoleSection = when (id) {
            "nav_pending_approval" -> ConsoleSnapshot.ConsoleSection.APPROVALS
            "feature_run_logs" -> ConsoleSnapshot.ConsoleSection.LOGS
            else -> null
        }
        when (entry.targetKind) {
            EntryRegistry.TargetKind.DRAWER -> showEntry(entry.target)
            EntryRegistry.TargetKind.SUGGESTION -> applySuggestion(entry.target)
            EntryRegistry.TargetKind.ACTION -> when (entry.target) {
                "cycle_theme" -> cycleTheme()
                else -> toast("${entry.title}待开发")
            }
            else -> toast("${entry.title}待开发")
        }
    }

    // ------------------------------------------------------------------
    // Projects
    // ------------------------------------------------------------------

    private fun bindProjects() {
        projectController = ProjectScreenController(
            context = this,
            rootLabel = screenProjects.findViewById(R.id.projectRoot),
            treePane = screenProjects.findViewById(R.id.projectTreePane),
            treeList = screenProjects.findViewById(R.id.projectTreeList),
            treeScroll = screenProjects.findViewById(R.id.projectTreeScroll),
            emptyView = screenProjects.findViewById(R.id.projectEmpty),
            searchInput = screenProjects.findViewById(R.id.projectSearch),
            treeMoreButton = screenProjects.findViewById(R.id.buttonTreeMore),
            codePane = screenProjects.findViewById(R.id.codeViewerPane),
            codePath = screenProjects.findViewById(R.id.codePath),
            codeMeta = screenProjects.findViewById(R.id.codeMeta),
            codeContent = screenProjects.findViewById(R.id.codeContent),
            codeEditor = screenProjects.findViewById(R.id.codeEditor),
            buttonCodeEdit = screenProjects.findViewById(R.id.buttonCodeEdit),
            buttonCodeSave = screenProjects.findViewById(R.id.buttonCodeSave),
            buttonCodeUndo = screenProjects.findViewById(R.id.buttonCodeUndo),
            buttonCodeKeep = screenProjects.findViewById(R.id.buttonCodeKeep),
            buttonCodeMore = screenProjects.findViewById(R.id.buttonCodeMore),
            buttonCodeCopy = screenProjects.findViewById(R.id.buttonCodeCopy),
            onAskAgent = { relativePath ->
                inputPrompt.setText(
                    "请查看并修改工作区文件 $relativePath。先读取它说明现状，再按我的要求修改；写之前等我确认。",
                )
                inConversation = true
                showEntry(DrawerController.ID_NEW_CONVERSATION)
                inputPrompt.requestFocus()
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            },
            onCopyText = { text -> copyToClipboard(text) },
            onToast = { message -> toast(message) },
            // 项目列表（V5 项目添加）：列表在先，本机工作区是列表里的一行。
            projectListPane = screenProjects.findViewById(R.id.projectListPane),
            projectList = screenProjects.findViewById(R.id.projectList),
            projectListEmpty = screenProjects.findViewById(R.id.projectListEmpty),
            buttonAddProject = screenProjects.findViewById(R.id.buttonAddProject),
            projectStore = projectStore,
            deviceDisplayName = { deviceId -> deviceDisplayName(deviceId) },
            // “打开文件夹”是唯一入口（2026-11-12，maintainer：项目来源收成一个）：
            // 点开是一个对话框，里面按位置种类列三项（本机文件夹 / 远程设备上
            // 的文件夹 / git 仓库地址），每项有名 + 一行说明；onAddProject 只作
            // 旧调用方的回退，默认走“打开文件夹”。
            onAddProject = { showOpenFolderEntry() },
            onOpenFolder = { showOpenFolderEntry() },
            onAssignHome = { project -> showAssignHomeSheet(project) },
            onOpenProject = { project -> openProject(project) },
            // 点选（2026-11-11，maintainer：点一下选、再点一下取消）：选中态按
            // 当前聊天项目画，点行切换绑定（选定即生效、取消即清除）。
            chatProjectId = { chatProjectBinding.projectId },
            onToggleSelectProject = { project -> toggleChatProject(project) },
            // 删项目（2026-11-11）：删的是当前选中项目时，选中态与
            // 聊天绑定一起清，不留指着已删项目的野绑定。
            onDeleteProject = { project ->
                if (chatProjectBinding.projectId == project.projectId) {
                    setChatProject(null)
                    note("已删除当前选中项目“${project.name}”，聊天绑定已清除。")
                }
            },
        )
        screenProjects.findViewById<View>(R.id.buttonProjectRefresh).setOnClickListener {
            projectController.refresh()
        }
        screenProjects.findViewById<View>(R.id.buttonCodeBack).setOnClickListener {
            projectController.closeCode()
        }
        screenProjects.findViewById<View>(R.id.buttonAskAgent).setOnClickListener {
            projectController.askAgentAboutCurrentFile()
        }
    }

    // ------------------------------------------------------------------
    // Tasks (real turn state + existing controls)
    // ------------------------------------------------------------------

    private fun bindTasks() {
        currentStatusDot = screenTasks.findViewById(R.id.currentStatusDot)
        currentStatusText = screenTasks.findViewById(R.id.currentStatusText)
        currentStatusChip = screenTasks.findViewById(R.id.currentStatusChip)
        currentBrain = screenTasks.findViewById(R.id.currentBrain)
        currentStarted = screenTasks.findViewById(R.id.currentStarted)
        currentNote = screenTasks.findViewById(R.id.currentNote)
        buttonPause = screenTasks.findViewById(R.id.buttonPause)
        buttonStop = screenTasks.findViewById(R.id.buttonStop)
        buttonChangeGoal = screenTasks.findViewById(R.id.buttonChangeGoal)
        eventList = screenTasks.findViewById(R.id.eventList)
        taskList = screenTasks.findViewById(R.id.taskList)
        taskApprovalBanner = screenTasks.findViewById(R.id.taskApprovalBanner)
        approvalHistoryList = screenTasks.findViewById(R.id.approvalHistoryList)

        buttonPause.setOnClickListener { toast(chatViewModel.pause()); updateTaskStatus(chatViewModel.stateNow()) }
        buttonStop.setOnClickListener {
            // V5 C1：停止二次确认——确认后才走原有停止逻辑。
            confirmStopDialog("停止执行？", "确定要停止当前正在执行的轮次吗？停止后本轮不会自动续跑。") {
                toast(chatViewModel.stop())
                updateTaskStatus(chatViewModel.stateNow())
            }
        }
        buttonChangeGoal.setOnClickListener {
            toast(chatViewModel.changeGoal())
            inConversation = true
            showEntry(DrawerController.ID_NEW_CONVERSATION)
            inputPrompt.requestFocus()
        }
        screenTasks.findViewById<View>(R.id.buttonEventRefresh).setOnClickListener { refreshEvents() }
        screenTasks.findViewById<View>(R.id.buttonTaskRefresh).setOnClickListener { refreshTasks() }
    }

    private fun updateTaskStatus(state: ChatUiState) {
        val last = state.turns.lastOrNull()
        val running = state.running
        val runningStatus = last?.status
        val (label, chip, colorRes) = when {
            running -> Triple("运行中", "本轮执行中", R.color.accent)
            runningStatus == TurnStatus.PAUSED -> Triple("已暂停", "无运行轮次", R.color.warning)
            runningStatus == TurnStatus.STOPPED -> Triple("已停止", "无运行轮次", R.color.danger)
            runningStatus == TurnStatus.GOAL_CHANGED -> Triple("已改目标", "无运行轮次", R.color.warning)
            runningStatus == TurnStatus.ERROR -> Triple("上一轮出错", "无运行轮次", R.color.danger)
            last != null -> Triple("空闲", "无运行轮次", R.color.success)
            else -> Triple("空闲", "无运行轮次", R.color.text_tertiary)
        }
        currentStatusText.text = label
        currentStatusDot.setTextColor(UiKit.color(this, colorRes))
        currentStatusChip.text = chip
        currentStatusChip.setBackgroundResource(if (running) R.drawable.bg_chip_accent else R.drawable.bg_chip)
        currentStatusChip.setTextColor(
            UiKit.color(this, if (running) R.color.accent else R.color.text_secondary),
        )
        // 聊天页总原则（maintainer 2026-11-11）：跟谁对话就显示谁的真实信息。
        // 任务页这行也一样：Agent 会话里 state.brainLabel 是 Agent 名，
        // 再写“大脑：”就是跟对话方对不上的信息，改按 selectedAgent 分辨。
        val currentAgent = if (::chatViewModel.isInitialized) chatViewModel.selectedAgent else null
        currentBrain.text = if (currentAgent != null) {
            "对话方：" + currentAgent.displayName
        } else {
            "大脑：" + state.brainLabel.ifBlank { brainShortLabel() }
        }
        currentStarted.text = if (state.startedAtMillis != null) {
            "开始时间：${UiKit.timeLabel(state.startedAtMillis)}"
        } else {
            "开始时间：—"
        }
        currentNote.text = when {
            running -> "暂停/停止/改目标都在引擎安全检查点生效；不会自动续跑旧轮次。"
            last?.notes?.isNotEmpty() == true -> last.notes.last()
            last?.error != null -> last.error
            else -> "没有正在执行的轮次。发送一条消息后，这里会显示真实状态。"
        }
        buttonPause.isEnabled = running
        buttonStop.isEnabled = running
        buttonChangeGoal.isEnabled = running
        listOf(buttonPause, buttonStop, buttonChangeGoal).forEach {
            it.alpha = if (running) 1f else 0.45f
        }
        updateChatControls(state)
        pokeApprovalTicker()
    }

    /** 阶段 3（U4）：聊天页内控制行——运行中显暂停/停止/改目标/控制台，
     * 已暂停显继续/改目标/控制台，空闲隐藏。 */
    private fun updateChatControls(state: ChatUiState) {
        if (!::chatControls.isInitialized) return
        val running = state.running
        val paused = !running && chatViewModel.hasPausedTurn()
        chatControls.visibility = if (running || paused) View.VISIBLE else View.GONE
        buttonChatPause.visibility = if (running) View.VISIBLE else View.GONE
        buttonChatStop.visibility = if (running) View.VISIBLE else View.GONE
        buttonChatResume.visibility = if (paused) View.VISIBLE else View.GONE
        // V5 C2-3-R1：改目标/控制台在行可见时常显（运行中与已暂停都可点）。
        if (::buttonChatChangeGoal.isInitialized) {
            buttonChatChangeGoal.visibility = if (running || paused) View.VISIBLE else View.GONE
        }
        if (::buttonChatConsole.isInitialized) {
            buttonChatConsole.visibility = if (running || paused) View.VISIBLE else View.GONE
        }
    }

    /**
     * 阶段 1（U3）：任务列表自动刷新。聊天状态每次 publish 都到这里，
     * 但 Room 读 + 卡片重建只在任务可见状态变化时做：运行开关、轮次数、
     * 末轮状态、工具调用数/运行中数。分派任务的状态变化走
     * taskRunner.onTasksChanged -> refreshTasks()。
     */
    private fun maybeRefreshTasks(state: ChatUiState) {
        val last = state.turns.lastOrNull()
        val sig = listOf(
            state.running,
            state.turns.size,
            last?.status,
            last?.toolCalls?.size,
            last?.toolCalls?.count { it.status == ToolCallStatus.RUNNING },
        ).joinToString("|")
        if (sig == lastTaskRefreshSig) return
        lastTaskRefreshSig = sig
        refreshTasks()
    }

    private fun refreshEvents() {
        val components = brainComponents
        if (components == null) {
            eventList.removeAllViews()
            eventList.addView(hint("大脑加载中，事件日志暂不可读。"))
            return
        }
        Thread {
            val entries = components.eventLog.entries(null).takeLast(40).reversed()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                eventList.removeAllViews()
                if (entries.isEmpty()) {
                    eventList.addView(hint("暂无运行事件。"))
                    return@runOnUiThread
                }
                // 2026-11-11（事件密度）：按会话 + 10 分钟簇分组折叠，
                // 默认只展最新 3 组；无实质内容事件不单独占行，只计数。
                // 展开状态记在字段里，刷新后不丢。
                val groups = EventLogGrouping.groupEvents(entries)
                EventGroupViews.render(
                    context = this,
                    container = eventList,
                    groups = groups,
                    expandedKeys = expandedEventGroupKeys,
                    showAll = showAllEventGroups,
                    onStateChanged = { refreshEvents() },
                    onToggleShowAll = {
                        if (showAllEventGroups) {
                            showAllEventGroups = false
                            expandedEventGroupKeys.clear()
                        } else {
                            showAllEventGroups = true
                        }
                        refreshEvents()
                    },
                )
            }
        }.start()
    }

    private fun refreshTasks() {
        val components = brainComponents
        if (components == null) {
            taskList.removeAllViews()
            taskList.addView(hint("大脑加载中，持久任务库暂不可读。"))
            return
        }
        Thread {
            val tasks = try {
                components.taskStore.listTasks(null, null)
            } catch (e: Exception) {
                null
            }
            // a-3 交付卡：后台线程一次读齐各任务的交付卡与步骤（与
            // refreshResults 的双循环同一模式，不得放主线程）。
            val deliveries = mutableMapOf<String, Pair<DeliveryCard?, List<TaskStep>>>()
            tasks?.forEach { task ->
                val card = try {
                    components.deliveryStore.cardFor(task.taskId)
                } catch (_: Exception) {
                    null
                }
                val steps = try {
                    components.taskStepStore.listSteps(task.taskId)
                } catch (_: Exception) {
                    emptyList()
                }
                deliveries[task.taskId] = card to steps
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                taskList.removeAllViews()
                if (tasks == null) {
                    taskList.addView(hint("读取持久任务库失败；详见运行事件/日志。"))
                    return@runOnUiThread
                }
                if (tasks.isEmpty()) {
                    taskList.addView(
                        hint(
                            "暂时没有任务。对话任务与分派任务会在有记录时显示在这里。",
                        ),
                    )
                    return@runOnUiThread
                }
                // 2026-11-11（两行归并）：派发轮次行与执行任务行是同一
                // 逻辑任务，只占主行一张卡；轮次与重试挂在主行下方作
                // 附属行，点进对应详情。组按主行 updatedAt 倒序，与
                // 原列表排序一致。
                TaskLibraryGrouping.groupLibraryTasks(tasks).forEach { group ->
                    val delivery = deliveries[group.primary.taskId]
                    taskList.addView(
                        taskCard(group.primary, delivery?.first, delivery?.second ?: emptyList()),
                    )
                    group.dispatchTurn?.let { turn ->
                        taskList.addView(
                            taskGroupSubRow(
                                "由聊天轮次派发 · ${turn.title.take(30)} · " +
                                    "${taskStateLabel(turn.state)} · ${UiKit.shortTime(turn.updatedAt)}",
                                turn.taskId,
                            ),
                        )
                    }
                    group.retries.forEach { retry ->
                        taskList.addView(
                            taskGroupSubRow(
                                "重试 · ${taskStateLabel(retry.state)} · ${UiKit.shortTime(retry.updatedAt)}",
                                retry.taskId,
                            ),
                        )
                    }
                }
            }
        }.start()
    }

    /**
     * V5 C2（批 3，第 2 步）：成果聚合。仿 [refreshTasks] 的双循环：
     * 后台线程 `listTasks(null, null)` → 逐任务 `listSteps(taskId)` →
     * [ResultsAggregation.aggregate] 算汇总与分组 → 回主线程渲染。
     * 双循环在后台线程做，不得放主线程（勘查 R3-1）；回主线程后
     * 若用户已切走（`currentEntry` 不再是成果入口）则不往已隐藏的屏写视图。
     */
    private fun refreshResults() {
        val components = brainComponents
        if (components == null) {
            resultsController.renderLoading()
            return
        }
        Thread {
            val tasks = try {
                components.taskStore.listTasks(null, null)
            } catch (e: Exception) {
                null
            }
            val stepsByTask = mutableMapOf<String, List<TaskStep>>()
            tasks?.forEach { task ->
                stepsByTask[task.taskId] = try {
                    components.taskStepStore.listSteps(task.taskId)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val summary = tasks?.let { ResultsAggregation.aggregate(it, stepsByTask) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (currentEntry != DrawerController.ID_RESULTS) return@runOnUiThread
                if (summary == null) {
                    resultsController.renderError()
                    return@runOnUiThread
                }
                resultsController.render(summary)
            }
        }.start()
    }

    /**
     * V5 控制台刷新（2026-11-11）：仿 [refreshResults] 的后台组装模式。
     * 后台线程一次读全：任务库、逐任务步骤、全局事件流（最新 40 条）、
     * 待审批 + 审批历史、远程设备 + 配对记录、Agent 库 → [ConsoleSnapshot.build]
     * 拼快照 → 回主线程渲染。回主线程后若用户已切走（`currentEntry`
     * 不再是控制台）则不往已隐藏的屏写视图。内容不变（签名相同）不重建，
     * 避免 5 秒自动刷新打断正在读控制台的用户；待审批倒计时按 5 秒取整
     * 进签名，有待审批时每轮都刷新，倒计时不 stale。
     * 渲染后消费抽屉深链带入的 [consoleSection]（待审批/运行日志定位）。
     */
    private fun refreshConsole() {
        val components = brainComponents
        if (components == null) {
            if (::consoleController.isInitialized) consoleController.renderLoading()
            return
        }
        if (consoleRefreshRunning) return
        consoleRefreshRunning = true
        Thread {
            val tasks = try {
                components.taskStore.listTasks(null, null)
            } catch (e: Exception) {
                null
            }
            val stepsByTask = mutableMapOf<String, List<TaskStep>>()
            tasks?.forEach { task ->
                stepsByTask[task.taskId] = try {
                    components.taskStepStore.listSteps(task.taskId)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            val events = try {
                components.eventLog.entries(null).takeLast(40).reversed()
            } catch (e: Exception) {
                emptyList()
            }
            // P0：任务事件审计轨迹（Room，重启仍在；与上面内存里的
            // 大脑轮次 feed 分开读，分开渲染）。
            val taskEvents = try {
                components.taskEventStore.recentEvents(40)
            } catch (e: Exception) {
                emptyList()
            }
            val pending = try {
                toolApprovalGate.pendingApproval()
            } catch (e: Exception) {
                null
            }
            val history = try {
                toolApprovalGate.approvalHistory()
            } catch (e: Exception) {
                emptyList()
            }
            val devices = try {
                remoteDeviceStore.listDevices()
            } catch (e: Exception) {
                emptyList()
            }
            val pairedIds = try {
                pairingStore.all().map { it.deviceId }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
            val agents = try {
                agentStore.listAgents()
            } catch (e: Exception) {
                emptyList()
            }
            val snapshot = tasks?.let {
                ConsoleSnapshot.build(
                    tasks = it,
                    stepsByTask = stepsByTask,
                    events = events,
                    pendingApproval = pending,
                    approvalHistory = history,
                    devices = devices,
                    pairedDeviceIds = pairedIds,
                    agents = agents,
                    generatedAtEpochSeconds = System.currentTimeMillis() / 1000,
                    taskEvents = taskEvents,
                )
            }
            runOnUiThread {
                consoleRefreshRunning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (currentEntry != DrawerController.ID_CONSOLE) return@runOnUiThread
                if (snapshot == null) {
                    consoleController.renderError()
                    return@runOnUiThread
                }
                val sig = consoleSig(snapshot)
                if (sig == lastConsoleSig) return@runOnUiThread
                lastConsoleSig = sig
                consoleController.render(snapshot)
                consoleSection?.let { section ->
                    consoleSection = null
                    consoleController.scrollToSection(section)
                }
            }
        }.start()
    }

    /**
     * V5 MCP / 插件刷新（2026-11-11）：仿 [refreshConsole] 的后台组装模式。
     * 后台线程读注册表快照（MCP 服务器 + 插件 + 七类计数）→ 回主线程渲染。
     * 大脑未建好时只显示加载态，不伪造空列表。
     */
    private fun refreshMcpPlugins() {
        val components = brainComponents
        if (components == null) {
            if (::mcpPluginController.isInitialized) mcpPluginController.renderLoading()
            return
        }
        if (mcpPluginsRefreshRunning) return
        mcpPluginsRefreshRunning = true
        Thread {
            val state = buildMcpPluginsState(components)
            runOnUiThread {
                mcpPluginsRefreshRunning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (currentEntry != DrawerController.ID_MCP_PLUGINS) return@runOnUiThread
                mcpPluginController.render(state)
            }
        }.start()
    }

    /** 从大脑组件拼 MCP/插件屏的状态（纯读注册表与管理器快照，不连网、不拉进程）。 */
    private fun buildMcpPluginsState(
        components: BrainBootstrap.BrainComponents,
    ): McpPluginScreenController.McpPluginScreenState {
        val registry = components.extensionRegistry
        val servers = components.mcpServerManager.servers().map { view ->
            val entry = view.entry
            val status = view.status
            val transportLabel = when (entry.extra["transport"]) {
                "http" -> "HTTP · ${entry.extra["url"].orEmpty()}"
                "sse" -> "SSE · ${entry.extra["url"].orEmpty()}"
                else -> "stdio · ${entry.extra["command"].orEmpty()}"
            }
            val statusText = when {
                !entry.enabled -> "未启用"
                status.state == McpServerState.CONNECTED -> "已连接 · ${status.toolCount} 个工具"
                status.state == McpServerState.FAILED -> "连接失败：${status.lastError ?: "未知错误"}"
                else -> "未连接"
            }
            McpPluginScreenController.McpServerRow(
                id = entry.id,
                displayName = entry.displayName,
                transportLabel = transportLabel,
                toolCount = if (status.state == McpServerState.CONNECTED) status.toolCount else null,
                statusText = statusText,
                enabled = entry.enabled,
                detail = entry.note.orEmpty(),
            )
        }
        val codePlugins = components.pluginRegistry.all().map { plugin ->
            val row = registry.get(ExtensionKind.PLUGIN, plugin.id)
            McpPluginScreenController.PluginRow(
                id = plugin.id,
                displayName = plugin.displayName,
                version = plugin.version,
                toolNames = plugin.tools.map { it.name },
                enabled = row?.enabled != false,
                detail = row?.note.orEmpty(),
            )
        }
        // 只在注册表里、不在代码插件里的行（如 Hand 的 healthcheck）也列出来：
        // 无本地工具，只读展示，不提供假操作。
        val codePluginIds = codePlugins.map { it.id }.toSet()
        val registryOnlyPlugins = registry.all(ExtensionKind.PLUGIN)
            .filter { it.id !in codePluginIds }
            .map { entry ->
                McpPluginScreenController.PluginRow(
                    id = entry.id,
                    displayName = entry.displayName,
                    version = entry.version,
                    toolNames = emptyList(),
                    enabled = entry.enabled,
                    detail = listOf(entry.implementation, entry.note.orEmpty())
                        .filter { it.isNotEmpty() }.joinToString("；"),
                )
            }
        return McpPluginScreenController.McpPluginScreenState(
            servers = servers,
            plugins = codePlugins + registryOnlyPlugins,
            kindCounts = registry.countsByKind().mapKeys { it.key.id },
        )
    }

    /** MCP 服务器启用开关：停用即断开；写注册表，后台做完刷新本屏。 */
    private fun toggleMcpServer(serverId: String, enabled: Boolean) {
        Thread {
            val components = brainComponents ?: return@Thread
            runBlocking { components.mcpServerManager.setEnabled(serverId, enabled) }
            runOnUiThread { refreshMcpPlugins() }
        }.start()
    }

    /** 插件启用开关：写注册表；大脑下一轮即按新开关列工具。 */
    private fun togglePlugin(pluginId: String, enabled: Boolean) {
        Thread {
            val components = brainComponents ?: return@Thread
            components.extensionRegistry.setEnabled(ExtensionKind.PLUGIN, pluginId, enabled)
            runOnUiThread { refreshMcpPlugins() }
        }.start()
    }

    /** 连接一台 MCP 服务器：真实走 initialize → tools/list；结果回主线程刷新并提示。 */
    private fun connectMcpServer(serverId: String) {
        Thread {
            val components = brainComponents ?: return@Thread
            val status = runBlocking { components.mcpServerManager.connect(serverId) }
            runOnUiThread {
                when (status.state) {
                    McpServerState.CONNECTED ->
                        toast("已连接 $serverId：${status.toolCount} 个工具可用")
                    McpServerState.FAILED ->
                        toast("连接失败：${status.lastError ?: "未知错误"}")
                    McpServerState.UNKNOWN -> toast("未连接：$serverId")
                }
                refreshMcpPlugins()
            }
        }.start()
    }

    /** 编辑一台 MCP 服务器：按注册表行拼表单，打开编辑对话框。 */
    private fun editMcpServer(serverId: String) {
        val components = brainComponents ?: return
        val entry = components.extensionRegistry.get(ExtensionKind.MCP, serverId) ?: run {
            toast("注册表里没有这个 MCP 服务器：$serverId")
            return
        }
        val headers = entry.extra["headersJson"]?.let { json ->
            try {
                val obj = org.json.JSONObject(json)
                val map = LinkedHashMap<String, String>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = obj.optString(key, "")
                }
                map
            } catch (_: Exception) {
                emptyMap<String, String>()
            }
        } ?: emptyMap()
        mcpPluginController.showServerForm(
            McpPluginScreenController.McpServerFormData(
                name = entry.id,
                transport = entry.extra["transport"] ?: "stdio",
                url = entry.extra["url"].orEmpty(),
                command = entry.extra["command"].orEmpty(),
                headers = headers,
                oauth = entry.extra["oauth"] == "true",
                oauthClientId = entry.extra["oauthClientId"].orEmpty(),
                oauthScopes = entry.extra["oauthScopes"].orEmpty(),
            ),
        )
    }

    /** 保存 MCP 服务器表单：添加（existingId 为空）或编辑（覆盖配置，开关保留）。 */
    private fun saveMcpServer(form: McpPluginScreenController.McpServerFormData, existingId: String?) {
        Thread {
            val components = brainComponents ?: return@Thread
            val registry = components.extensionRegistry
            val extra = mutableMapOf<String, String>()
            when (form.transport) {
                "http", "sse" -> {
                    extra["transport"] = form.transport
                    extra["url"] = form.url
                    if (form.headers.isNotEmpty()) {
                        extra["headersJson"] = org.json.JSONObject(form.headers).toString()
                    }
                    if (form.oauth) {
                        extra["oauth"] = "true"
                        if (form.oauthClientId.isNotBlank()) extra["oauthClientId"] = form.oauthClientId
                        if (form.oauthScopes.isNotBlank()) extra["oauthScopes"] = form.oauthScopes
                    }
                }
                else -> {
                    extra["transport"] = "stdio"
                    extra["command"] = form.command
                }
            }
            // 后台写注册表，主线程只提示 + 刷新（toast 必须在主线程）。
            val (saved, message) = if (existingId == null) {
                if (registry.get(ExtensionKind.MCP, form.name) != null) {
                    false to "已有同名 MCP 服务器：${form.name}"
                } else {
                    registry.register(
                        ExtensionEntry(
                            id = form.name,
                            kind = ExtensionKind.MCP,
                            displayName = form.name,
                            source = "user",
                            enabled = true,
                            extra = extra,
                        ),
                    )
                    true to "已添加 MCP 服务器：${form.name}"
                }
            } else {
                val existing = registry.get(ExtensionKind.MCP, existingId)
                if (existing == null) {
                    false to "注册表里没有这个 MCP 服务器：$existingId"
                } else {
                    registry.update(
                        ExtensionEntry(
                            id = existingId,
                            kind = ExtensionKind.MCP,
                            displayName = existing.displayName,
                            source = "user",
                            enabled = existing.enabled,
                            extra = extra,
                        ),
                    )
                    true to "已保存 MCP 服务器：$existingId"
                }
            }
            runOnUiThread {
                toast(message)
                if (saved) refreshMcpPlugins()
            }
        }.start()
    }

    /** 删除一台 MCP 服务器：先断开（停重连），再删注册表行（内置行删不掉）。 */
    private fun deleteMcpServer(serverId: String) {
        Thread {
            val components = brainComponents ?: return@Thread
            runBlocking { components.mcpServerManager.disconnect(serverId) }
            val removed = components.extensionRegistry.remove(ExtensionKind.MCP, serverId)
            runOnUiThread {
                if (removed) {
                    toast("已删除 MCP 服务器：$serverId")
                } else {
                    toast("删不掉：$serverId 是内置服务器，只能停用")
                }
                refreshMcpPlugins()
            }
        }.start()
    }

    /** 导入 mcpServers JSON：同名覆盖配置，开关保留；格式不对报原因。 */
    private fun importMcpServers(json: String) {
        Thread {
            val components = brainComponents ?: return@Thread
            val imported = try {
                McpServersConfig.importToRegistry(json, components.extensionRegistry)
            } catch (e: McpConfigException) {
                runOnUiThread { toast("导入失败：${e.message ?: "配置格式不对"}") }
                return@Thread
            } catch (e: Exception) {
                runOnUiThread { toast("导入失败：${e.message ?: "未知错误"}") }
                return@Thread
            }
            runOnUiThread {
                if (imported.isEmpty()) {
                    toast("配置里没有 MCP 服务器。")
                } else {
                    toast("已导入 ${imported.size} 个 MCP 服务器：${imported.joinToString("、")}")
                }
                refreshMcpPlugins()
            }
        }.start()
    }

    /** 控制台快照签名：任务状态/更新时间 + 待审批 + 事件数 + 审批历史数 + 设备/Agent 数。 */
    private fun consoleSig(snapshot: ConsoleSnapshot.Snapshot): String {
        val pendingPart = snapshot.pendingApproval?.let {
            "${it.actionId}:${(it.deadlineEpochMillis - System.currentTimeMillis()) / 5000}"
        } ?: "none"
        return buildString {
            append("tasks:")
            snapshot.tasks.joinTo(this, ",") { "${it.taskId}:${it.state}:${it.updatedAt}" }
            append("|pending:").append(pendingPart)
            append("|events:").append(snapshot.events.size)
            append(":").append(snapshot.events.firstOrNull()?.epochSeconds ?: 0)
            append("|taskEvents:").append(snapshot.taskEvents.size)
            append(":").append(snapshot.taskEvents.firstOrNull()?.eventId ?: "")
            append("|history:").append(snapshot.approvalHistory.size)
            append("|devices:").append(snapshot.devices.size)
            append("|agents:").append(snapshot.agents.size)
        }
    }

    /** 控制台自动刷新 ticker：打开时每 5 秒重读一次快照，切走即停。 */
    private fun startConsoleTicker() {
        if (consoleTickerActive) return
        consoleTickerActive = true
        consoleHandler.post(consoleTick)
        // WS 订阅与 ticker 同开同停（见 startConsoleWsFeed 的兜底注释）：
        // ticker 是安全网，WS 只是让事件到了立即刷新的加速器。
        startConsoleWsFeed()
    }

    private fun stopConsoleTicker() {
        consoleTickerActive = false
        consoleHandler.removeCallbacks(consoleTick)
        consoleWsFeed?.stop()
        consoleWsFeed = null
    }

    // ------------------------------------------------------------------
    // V5 团队频道（P3，2026-11-11）：有界团队聊天 + 只读扇出。
    // ------------------------------------------------------------------

    /**
     * 频道端点（host，channelId）：取第一条地址和频道均已配置的平台
     * Agent 记录。公开构建没有预置这些值；未配置时频道页显示配置指引。
     */
    private fun teamChannelEndpoint(): Pair<String, String>? {
        if (platformCredentialStore.loadPlatformToken().isNullOrBlank()) return null
        val records = try {
            agentStore.listAgents()
        } catch (_: Exception) {
            emptyList()
        }.filter { PlatformAgent.isPlatformAgentRecord(it) }
        val record = records.firstOrNull { !it.platformHost.isNullOrBlank() } ?: return null
        val host = record.platformHost!!.trim()
        val channelId = record.platformChannelId?.trim().takeIf { !it.isNullOrBlank() }
            ?: PlatformAgent.V5_CHANNEL_ID
        if (channelId.isBlank()) return null
        return host to channelId
    }

    /**
     * 频道成员（maintainer 2026-11-11 22:19：全部 Agent 拉入频道）：本机 App
     * 打头 + 注册表里的全部 Agent（[buildChannelMembers]，注册表顺序，
     * 按 agentId 去重）。平台 Agent 标出来——它是端点直连成员。
     */
    private fun teamChannelMembers(): List<TeamChannelMember> {
        val agents = try {
            agentStore.listAgents()
        } catch (_: Exception) {
            emptyList()
        }
        return buildChannelMembers(agents)
    }

    /** 已读标记（本机持久化，键按频道分；服务端标记由 markTeamChannelRead 同步推）。 */
    private fun teamChannelLastReadId(channelId: String): String? =
        getSharedPreferences("team_channel_prefs", MODE_PRIVATE)
            .getString("last_read_$channelId", null)

    private fun saveTeamChannelLastReadId(channelId: String, messageId: String) {
        getSharedPreferences("team_channel_prefs", MODE_PRIVATE)
            .edit().putString("last_read_$channelId", messageId).apply()
    }

    /**
     * 组装频道页快照（后台线程调；每块 try/catch，坏一块不拖垮整页）。
     * 未读数口径：上次“标为已读”之后到的消息条数；第一次打开没有
     * 标记时，首屏即视为已读（未读 0，标记记到最新一条）——用户正
     * 看着首屏，没必要再标一遍未读。
     */
    private fun buildTeamChannelSnapshot(): TeamChannelSnapshot {
        val endpoint = teamChannelEndpoint()
        if (endpoint == null) {
            return TeamChannelSnapshot(
                configured = false,
                setupHint = "团队频道还没法连：先去 Agent 页加一个平台 Agent，" +
                    "并在表单的平台凭证栏填入 App 的平台凭证" +
                    "（由你的平台管理员签发）。",
                channelId = PlatformAgent.V5_CHANNEL_ID,
                connectionState = ChannelConnectionState.STOPPED,
                connectionError = null,
                members = teamChannelMembers(),
                messages = emptyList(),
                unreadCount = 0,
                unreadCapped = false,
                roundsUsed = teamChannelRoundsUsed,
                fanoutRuns = emptyList(),
                fanoutRunning = teamChannelFanoutRunning,
                notice = teamChannelNotice,
            )
        }
        val (host, channelId) = endpoint
        val messages = synchronized(teamChannelMessages) { teamChannelMessages.toList() }
        val lastReadId = teamChannelLastReadId(channelId)
        val unreadCount = if (lastReadId == null) {
            messages.lastOrNull()?.let { saveTeamChannelLastReadId(channelId, it.id) }
            0
        } else {
            val index = messages.indexOfFirst { it.id == lastReadId }
            if (index < 0) messages.size else messages.size - index - 1
        }
        val fanoutRuns = try {
            brainComponents?.fanoutStore?.listRuns(5) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val feed = teamChannelFeed
        return TeamChannelSnapshot(
            configured = true,
            setupHint = "",
            channelId = channelId,
            connectionState = feed?.connectionState
                ?: ChannelConnectionState.STOPPED,
            connectionError = feed?.lastError,
            members = teamChannelMembers(),
            messages = messages,
            unreadCount = unreadCount,
            unreadCapped = false,
            roundsUsed = teamChannelRoundsUsed,
            fanoutRuns = fanoutRuns,
            fanoutRunning = teamChannelFanoutRunning,
            notice = teamChannelNotice,
        )
    }

    private fun refreshTeamChannel() {
        if (!::teamChannelController.isInitialized) return
        if (teamChannelRefreshRunning) return
        teamChannelRefreshRunning = true
        Thread {
            val snapshot = try {
                buildTeamChannelSnapshot()
            } catch (e: Exception) {
                teamChannelNotice =
                    "频道刷新失败：${(e.message ?: e.javaClass.simpleName).take(200)}"
                null
            }
            runOnUiThread {
                teamChannelRefreshRunning = false
                if (!::teamChannelController.isInitialized) return@runOnUiThread
                if (snapshot != null) {
                    teamChannelController.render(snapshot)
                } else {
                    // 快照组装失败也至少把失败原因刷出来，不静默。
                    refreshTeamChannelFallback()
                }
            }
        }.start()
    }

    /** 快照组装失败时的兜底渲染（只报失败原因，不丢已有消息）。 */
    private fun refreshTeamChannelFallback() {
        val endpoint = teamChannelEndpoint()
        val messages = synchronized(teamChannelMessages) { teamChannelMessages.toList() }
        teamChannelController.render(
            TeamChannelSnapshot(
                configured = endpoint != null,
                setupHint = "团队频道还没法连：先去 Agent 页加一个平台 Agent。",
                channelId = endpoint?.second.orEmpty(),
                connectionState = teamChannelFeed?.connectionState
                    ?: ChannelConnectionState.STOPPED,
                connectionError = teamChannelFeed?.lastError,
                members = teamChannelMembers(),
                messages = messages,
                unreadCount = 0,
                unreadCapped = false,
                roundsUsed = teamChannelRoundsUsed,
                fanoutRuns = emptyList(),
                fanoutRunning = teamChannelFanoutRunning,
                notice = teamChannelNotice,
            ),
        )
    }

    /** 起频道消息流（WS 推送为主，断线轮询兜底；离开页面即停）。 */
    private fun startTeamChannelFeed() {
        val (host, channelId) = teamChannelEndpoint() ?: return
        if (teamChannelFeed != null) return
        val transport = PlatformChannelMcpTransport(
            tokenProvider = { platformCredentialStore.loadPlatformToken() },
            endpoint = PlatformAgent.mcpEndpoint(host),
        )
        val feed = ChannelFeed(
            channelId = channelId,
            transport = transport,
            wsFactory = { _, onClosed ->
                ChannelWsClient(
                    host = host,
                    port = 443,
                    channelId = channelId,
                    tokenProvider = { platformCredentialStore.loadPlatformToken() },
                ).also { it.onClosed = onClosed }
            },
            onMessages = { messages ->
                synchronized(teamChannelMessages) {
                    teamChannelMessages.addAll(messages)
                    if (teamChannelMessages.size > 100) {
                        teamChannelMessages.subList(
                            0,
                            teamChannelMessages.size - 100,
                        ).clear()
                    }
                }
                // 刚到的消息里 @ 了设备/云端 Agent 的，调度器当中转
                // （ChannelAgentRelay：转到 Hand/API，回复贴回频道）。
                maybeRelayChannelMessages(messages)
                runOnUiThread { refreshTeamChannel() }
            },
            onConnectionChange = { _, _ ->
                runOnUiThread { refreshTeamChannel() }
            },
        )
        teamChannelFeed = feed
        feed.start()
    }

    private fun stopTeamChannelFeed() {
        teamChannelFeed?.stop()
        teamChannelFeed = null
    }

    /**
     * @ 中转（maintainer 2026-11-11 22:19：全部 Agent 拉入频道，设备/云端
     * Agent 由调度器当代理）。feed 刚收到的消息里 @ 了设备/云端
     * Agent 的，转给 [ChannelAgentRelay]：中转到 Hand/API，回复贴回
     * 频道；转不成的在提示行如实写原因（不往频道里贴失败、不假装回了）。
     *
     * 只在频道页打开、feed 跑着时发生；频道页关掉后 feed 停，中转也
     * 停（边界，见 DELIVERY）。中转不计轮次（轮次只计用户发帖），
     * 不建任务、不写文件——建议-only 的围栏在 Relay 里。
     */
    private fun maybeRelayChannelMessages(messages: List<PlatformChannelMessage>) {
        val (host, channelId) = teamChannelEndpoint() ?: return
        val agents = try {
            agentStore.listAgents()
        } catch (_: Exception) {
            emptyList()
        }
        if (agents.isEmpty()) return
        val transport = PlatformChannelMcpTransport(
            tokenProvider = { platformCredentialStore.loadPlatformToken() },
            endpoint = PlatformAgent.mcpEndpoint(host),
        )
        val relay = ChannelAgentRelay(
            handPort = remoteChatPort,
            manusPort = manusChatPort,
            transport = transport,
            channelId = channelId,
            resolveTarget = ::resolveRemoteTarget,
            isManusConfigured = ::isManusConfigured,
            relayedIds = teamChannelRelayedIds,
            relayOutputIds = teamChannelRelayOutputIds,
        )
        val todo = messages.filter { message ->
            message.id !in teamChannelRelayedIds &&
                message.id !in teamChannelRelayOutputIds &&
                relay.findMentions(message.text, agents).isNotEmpty()
        }
        if (todo.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val failures = mutableListOf<String>()
            for (message in todo) {
                val outcomes = try {
                    relay.relay(message, agents)
                } catch (e: Exception) {
                    teamChannelNotice =
                        "中转失败：${(e.message ?: e.javaClass.simpleName).take(200)}"
                    break
                }
                outcomes.filter { it.status == RelayStatus.FAILED }.forEach {
                    failures += "@${it.displayName} 没回：${it.detail.orEmpty().take(100)}"
                }
            }
            if (failures.isNotEmpty()) teamChannelNotice = failures.joinToString("；")
            withContext(Dispatchers.Main) { refreshTeamChannel() }
        }
    }

    /** 往频道发一条消息（后台线程；成功计一轮，失败把原因写进提示）。 */
    private fun postTeamChannelMessage(text: String) {
        val (host, channelId) = teamChannelEndpoint() ?: run {
            teamChannelNotice = "频道还没配好，发不出去。"
            refreshTeamChannel()
            return
        }
        Thread {
            try {
                val transport = PlatformChannelMcpTransport(
                    tokenProvider = { platformCredentialStore.loadPlatformToken() },
                    endpoint = PlatformAgent.mcpEndpoint(host),
                )
                runBlocking { transport.postMessage(channelId, text) }
                teamChannelRoundsUsed++
                teamChannelNotice = null
            } catch (e: Exception) {
                teamChannelNotice =
                    "发帖失败：${(e.message ?: e.javaClass.simpleName).take(200)}"
            }
            runOnUiThread { refreshTeamChannel() }
        }.start()
    }

    /** 标为已读：服务端标记推到最新一条 + 本机标记同步（后台线程）。 */
    private fun markTeamChannelRead() {
        val (host, channelId) = teamChannelEndpoint() ?: return
        Thread {
            try {
                val transport = PlatformChannelMcpTransport(
                    tokenProvider = { platformCredentialStore.loadPlatformToken() },
                    endpoint = PlatformAgent.mcpEndpoint(host),
                )
                runBlocking {
                    transport.pollMessages(channelId, null, 1, markRead = true)
                }
                val latestId = synchronized(teamChannelMessages) {
                    teamChannelMessages.lastOrNull()?.id
                }
                if (latestId != null) saveTeamChannelLastReadId(channelId, latestId)
                teamChannelNotice = null
            } catch (e: Exception) {
                teamChannelNotice =
                    "标为已读失败：${(e.message ?: e.javaClass.simpleName).take(200)}"
            }
            runOnUiThread { refreshTeamChannel() }
        }.start()
    }

    /**
     * 只读扇出：把一个问题同时问最多 5 个分支（只读摘要，不并行写）。
     * 目标只取能经 Hand 直连的设备 Agent（[resolveRemoteTarget] 能
     * 解析出的）；平台 Agent（Example Agent 等）走频道本身，不走扇出。
     * 没有目标时明说，不假装问了。进行中时新的请求直接拒绝。
     */
    private fun startTeamFanout(question: String) {
        if (teamChannelFanoutRunning) {
            teamChannelNotice = "正在问大家，等这一轮回完再问。"
            refreshTeamChannel()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            teamChannelFanoutRunning = true
            withContext(Dispatchers.Main) { refreshTeamChannel() }
            try {
                val agents = try {
                    agentStore.listAgents()
                } catch (_: Exception) {
                    emptyList()
                }
                // Agent 开关（2026-11-12）：停用的 Agent 不进扇出候选。
                val candidates = agents.filter { it.enabled }.mapNotNull { agent ->
                    resolveRemoteTarget(agent)?.let { target ->
                        FanoutCandidate(
                            agentId = agent.agentId,
                            deviceId = agent.deviceId,
                            displayName = agent.displayName,
                        ) to target
                    }
                }
                val selection = TeamChatRules.selectFanoutTargets(
                    candidates.map { it.first },
                )
                if (selection.selected.isEmpty()) {
                    teamChannelNotice = "没有能问的 Agent：先在 Agent 页加一台已配对" +
                        "设备上的 Agent，并给它指定工作文件夹。"
                    return@launch
                }
                if (selection.dropped > 0) {
                    teamChannelNotice =
                        "问了 ${selection.selected.size} 个分支，" +
                            "${selection.dropped} 个因上限没问。"
                } else {
                    teamChannelNotice = null
                }
                val selectedTargets = selection.selected.mapNotNull { candidate ->
                    candidates.firstOrNull { it.first.agentId == candidate.agentId }
                }
                val brief = TeamChatRules.buildFanoutBrief(question, null, null)
                val store = brainComponents?.fanoutStore ?: InMemoryFanoutStore()
                TeamFanoutRunner(remoteChatPort, store).run(brief, selectedTargets)
            } catch (e: Exception) {
                teamChannelNotice =
                    "问大家失败：${(e.message ?: e.javaClass.simpleName).take(200)}"
            } finally {
                teamChannelFanoutRunning = false
                withContext(Dispatchers.Main) { refreshTeamChannel() }
            }
        }
    }

    /**
     * 启动控制台 WS 订阅（V5 控制台实时推送，2026-11-11）。
     *
     * 只连已配对设备：RemoteDeviceStore 的设备 + 配对快照 CONFIRMED 且
     * 有对方公钥（requestWsToken 建会话必需）；未配对/待确认/无公钥的
     * 设备不连、不重试——token 本来就领不到，重试只是空转。
     * 大脑未建好（brainComponents 为 null，eventLog 还不存在）时不启动；
     * 下次打开控制台会再调 startConsoleTicker，再试一次。
     *
     * 事件回调（WS 工作线程）：写 eventLog（kind "remote_agent_event"，
     * conversationId 空字符串，走全局事件流）→ 立即调 refreshConsole()
     *（不等 5 秒轮询；refreshConsole 自己有去重签名，同一快照不重渲染）。
     * 回调里 resolve brainComponents 而不用启动时捕获的 components：
     * 大脑重建后事件仍进当前 eventLog。
     */
    private fun startConsoleWsFeed() {
        if (brainComponents == null) return
        val devices = pairedWsDevices()
        if (devices.isEmpty()) {
            Log.d("ConsoleWs", "无已配对设备，不启动 WS 订阅（轮询仍在跑）")
            return
        }
        val feed = ConsoleWsFeed(
            tokenProvider = { peer ->
                when (val result = wsHandClient(peer)?.requestWsToken(peer)) {
                    is WsTokenResult.Ok -> result.token
                    is WsTokenResult.Failed -> {
                        Log.d("ConsoleWs", "领 WS token 失败 ${peer.displayName}：${result.reason}")
                        null
                    }
                    null -> {
                        Log.d("ConsoleWs", "领 WS token 失败 ${peer.displayName}：无法建 HandClient（无对方公钥）")
                        null
                    }
                }
            },
            onEvent = { event ->
                val eventLog = brainComponents?.eventLog ?: return@ConsoleWsFeed
                // 2026-11-11：远端事件是唯一不走 BrainEngine.log() 的
                // 写路径——这里直接补脱敏（进度文案来自远端 Agent 原文，
                // 与其他事件进日志前先过 SecretRedactor 的规矩对齐）。
                eventLog.record(
                    EngineEvent(
                        seq = 0, // InMemoryEventLog.record 时自动给 seq，传 0 即可。
                        conversationId = "",
                        kind = "remote_agent_event",
                        detail = SecretRedactor.redact(event.detail, 2000),
                        epochSeconds = event.epochSeconds,
                    ),
                )
                refreshConsole()
            },
            onLog = { Log.d("ConsoleWs", it) },
        )
        consoleWsFeed = feed
        feed.start(devices)
    }

    /**
     * 给 WS 订阅用的 HandClient（与 RemoteAgentSession.clientFor 同款：
     * 签名密钥由会话密钥派生，requestWsToken 本身不用签名密钥——
     * 它不带任务凭证——但 HandClient 构造要求传，这里照传）。
     * 对方公钥缺失或会话密钥协商失败时返回 null（调用方记日志，不连）。
     */
    /**
     * App 内置安装包的 Hand 版本（SHA-256 前 12 位），读不到为 ""。
     * 只读一次（App 内置包运行期不变）；设备卡据此算“可更新/最新”。
     */
    private fun bundledHandVersion(): String {
        cachedBundledHandVersion?.let { return it }
        val version = try {
            HandInstallBundleLoader.load(AssetReader { path ->
                try {
                    assets.open(path).use { it.readBytes() }
                } catch (_: Exception) {
                    null
                }
            })?.version ?: ""
        } catch (_: Exception) {
            ""
        }
        cachedBundledHandVersion = version
        return version
    }

    private fun wsHandClient(peer: DiscoveredPeer): HandClient? {
        val identity = deviceIdentityStore.getOrCreate()
        val peerKey = pairingManager.peerPublicKey(peer.deviceId) ?: return null
        val sessionKey = try {
            DeviceKeys.deriveSessionKey(identity.privateKey, peerKey)
        } catch (_: Exception) {
            return null
        }
        return HandClient(
            transport = directTransport,
            ownDeviceId = identity.deviceId,
            ownKeyPair = DeviceKeys.KeyPair(identity.publicKey, identity.privateKey),
            signingKey = RemoteCredentialKeys.signingKey(sessionKey),
            trustChecker = pairingManager,
        )
    }

    /** 已配对且能建 WS 会话的设备（配对快照 CONFIRMED + 有对方公钥）。 */
    private fun pairedWsDevices(): List<DiscoveredPeer> {
        val devices = try {
            remoteDeviceStore.listDevices()
        } catch (_: Exception) {
            return emptyList()
        }
        return devices.mapNotNull { device ->
            val record = pairingSnapshot.load(device.deviceId) ?: return@mapNotNull null
            if (record.state != PairingState.CONFIRMED) return@mapNotNull null
            if (record.peerPublicKey == null) return@mapNotNull null
            device.toDiscoveredPeer(record.fingerprintSha256)
        }
    }

    private fun tickConsole() {
        if (currentEntry != DrawerController.ID_CONSOLE) {
            consoleTickerActive = false
            return
        }
        refreshConsole()
        consoleHandler.postDelayed(consoleTick, 5000)
    }

    private fun taskCard(task: TaskRecord, deliveryCard: DeliveryCard? = null, steps: List<TaskStep> = emptyList()): View {
        val card = UiKit.vertical(this).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val top = UiKit.horizontal(this)
        top.addView(
            UiKit.text(
                this,
                task.title.ifBlank { "未命名任务" },
                13f,
                R.color.text_primary,
                bold = true,
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        top.addView(stateChip(task.state))
        card.addView(top)
        card.addView(
            UiKit.text(
                this,
                "${taskStateLabel(task.state)} · 更新：${UiKit.shortTime(task.updatedAt)}",
                10f,
                R.color.text_tertiary,
            ).apply { setPadding(0, dp(6), 0, 0) },
        )
        task.description?.takeIf { it.isNotBlank() && it != task.title }?.let { description ->
            card.addView(
                UiKit.text(this, description, 11f, R.color.text_secondary).apply {
                    setPadding(0, dp(6), 0, 0)
                    maxLines = 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
        }
        // 阶段 1（U3）：渲染终态产物/错误。result 是大脑最终文字或
        // 停止说明，error 是真实失败原因；两者此前存在但从不渲染。
        task.result?.takeIf { it.isNotBlank() }?.let { result ->
            card.addView(
                UiKit.text(this, "结果：$result", 11f, R.color.text_secondary).apply {
                    setPadding(0, dp(6), 0, 0)
                    maxLines = 6
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
        }
        task.error?.takeIf { it.isNotBlank() }?.let { error ->
            card.addView(
                UiKit.text(this, "错误：$error", 11f, R.color.danger).apply {
                    setPadding(0, dp(6), 0, 0)
                    maxLines = 6
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
        }
        // a-3 交付卡：只在有产物时渲染；没有产物的任务不画一张空卡。
        if (deliveryCard != null) {
            card.addView(deliveryCardView(task, deliveryCard, steps))
        }
        // Tool arguments, internal ids and runtime events stay in the
        // task detail screen under the opt-in diagnostics controls.
        card.addView(taskControls(task))
        // 阶段 3（U3/批 3a）：点任务卡进详情页（控制按钮自己消费点击，不触发）。
        card.setOnClickListener { openTaskDetail(task.taskId) }
        return card
    }

    /** 任务库归并组的附属行：派发轮次 / 重试，点进对应任务详情。 */
    private fun taskGroupSubRow(label: String, taskId: String): View {
        return UiKit.text(this, "↳ $label", 10f, R.color.text_secondary).apply {
            setPadding(dp(14), dp(2), dp(14), dp(8))
            setOnClickListener { openTaskDetail(taskId) }
        }
    }

    // ------------------------------------------------------------------
    // a-3 任务交付卡 / 产物展示
    // ------------------------------------------------------------------

    /**
     * 任务页交付卡（字段清单复用老项目 taskDeliveryCard +
     * renderExecutionTaskFiles；锚点按报告 §7：轮次 id + ChangeSet +
     * artifact sha256）。文件行点开进项目页查看（复用详情页产物链接
     * openDetailFile）；保留/撤销只在任务终态且本轮未定论时可点。
     */
    private fun deliveryCardView(task: TaskRecord, deliveryCard: DeliveryCard, steps: List<TaskStep>): View {
        val terminal = TaskStates.isTerminal(task.state)
        val stepsDone = steps.count { it.status == TaskStepStatus.SUCCESS }.takeIf { steps.isNotEmpty() }
        val stepsTotal = steps.size.takeIf { it > 0 }
        val view = DeliveryCardView.build(deliveryCard, stepsDone, stepsTotal, terminal)
        val section = UiKit.vertical(this).apply {
            setBackgroundResource(R.drawable.bg_system_note)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        section.addView(UiKit.text(this, "交付", 13f, R.color.text_primary, bold = true))
        section.addView(
            UiKit.text(this, view.summaryLine, 11f, R.color.text_secondary).apply {
                setPadding(0, dp(4), 0, 0)
            },
        )
        view.progressLine?.let { progress ->
            section.addView(
                UiKit.text(this, progress, 11f, R.color.text_secondary).apply {
                    setPadding(0, dp(3), 0, 0)
                },
            )
        }
        section.addView(
            UiKit.text(
                this,
                view.verdictLine,
                11f,
                if (deliveryCard.verdict == DeliveryTestVerdict.FAILED) {
                    R.color.danger
                } else {
                    R.color.text_secondary
                },
            ).apply { setPadding(0, dp(3), 0, 0) },
        )
        view.files.forEach { file ->
            section.addView(
                UiKit.text(this, "▸ ${file.kindLabel} ${file.path}", 12f, R.color.accent, mono = true).apply {
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    setOnClickListener { openDetailFile(file.path) }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(5) }
                },
            )
        }
        view.rounds.forEach { round ->
            val row = UiKit.horizontal(this).apply {
                setPadding(0, dp(6), 0, 0)
            }
            row.addView(
                UiKit.text(
                    this,
                    "轮次 ${round.shortRoundId}：${round.fileCount} 个文件" +
                        (round.resultLabel?.let { " · $it" } ?: ""),
                    11f,
                    R.color.text_secondary,
                ),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            if (round.canKeep) {
                row.addView(taskButton("保留") { keepDeliveryRound(task, round.roundId) })
            }
            if (round.canUndo) {
                row.addView(taskButton("撤销") { undoDeliveryRound(task, round.roundId) })
            }
            section.addView(row)
        }
        if (view.rounds.any { it.resultLabel == null } && !terminal) {
            section.addView(
                UiKit.text(this, "任务进行中，结束后再保留/撤销。", 10f, R.color.text_tertiary).apply {
                    setPadding(0, dp(4), 0, 0)
                },
            )
        }
        section.addView(
            UiKit.text(this, view.anchorLine, 10f, R.color.text_tertiary, mono = true).apply {
                setPadding(0, dp(6), 0, 0)
            },
        )
        section.addView(
            UiKit.text(this, view.noteLine, 10f, R.color.text_tertiary).apply {
                setPadding(0, dp(3), 0, 0)
            },
        )
        return section
    }

    /**
     * a-3：保留/撤销二次确认。复用现有 AlertDialog 弹窗模式
     *（见 confirmStopDialog）：点确认走操作，点“取消”、按返回键
     * 或点框外什么都不发生。
     */
    private fun confirmActionDialog(
        title: String,
        message: String,
        positiveLabel: String,
        onConfirmed: () -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveLabel) { _, _ -> onConfirmed() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun keepDeliveryRound(task: TaskRecord, roundId: String) {
        confirmActionDialog(
            "保留本轮改动？",
            "保留这些文件，删除撤销备份；保留后无法再撤销。",
            "保留",
        ) {
            applyDeliveryRound(task, roundId, DeliveryRoundResult.KEPT)
        }
    }

    private fun undoDeliveryRound(task: TaskRecord, roundId: String) {
        // 撤销二次确认文案复用老项目（映射 D4）：“将恢复任务开始前的文件；
        // 如果你后来改过这些文件，撤销会停止”。
        confirmActionDialog(
            "撤销本轮改动？",
            "将恢复任务开始前的文件；如果你后来改过这些文件，撤销会停止。",
            "撤销",
        ) {
            applyDeliveryRound(task, roundId, DeliveryRoundResult.UNDONE)
        }
    }

    /**
     * 保留/撤销执行：文件操作在后台线程做（FileWorkspaceAdapter 有
     * 文件 IO），结果记进交付卡存储（轮次 id 绑定，对不上不记），
     * 再回主线程提示并刷新任务页。
     */
    private fun applyDeliveryRound(task: TaskRecord, roundId: String, result: DeliveryRoundResult) {
        val components = brainComponents ?: return
        Thread {
            try {
                val message = when (result) {
                    DeliveryRoundResult.KEPT -> {
                        components.workspaceAdapter.keep(roundId, confirm = true)
                        "已保留本轮改动。"
                    }
                    DeliveryRoundResult.UNDONE -> {
                        val undo = components.workspaceAdapter.undo(roundId, confirm = true)
                        if (undo.alreadyUndone) {
                            "已撤销过。"
                        } else {
                            buildString {
                                append("已撤销：恢复 ${undo.restored.size} 个文件，删除 ${undo.deleted.size} 个新增文件。")
                                if (undo.notRestored.isNotEmpty()) {
                                    append(" ${undo.notRestored.size} 个文件无法恢复（轮次结束后又被改动过）。")
                                }
                            }
                        }
                    }
                }
                components.deliveryStore.markRound(task.taskId, roundId, result)
                runOnUiThread {
                    toast(message)
                    refreshTasks()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast("操作失败：${e.message ?: "未知错误"}")
                    refreshTasks()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Task detail (stage 3, U3 / batch 3a)
    // ------------------------------------------------------------------

    /**
     * 打开任务详情浮层（复用设置浮层的宿主模式：盖在当前屏之上，
     * 返回键/顶部返回先关详情，再走正常返回栈）。
     * 审批匹配是尽力而为：审批按工具调用记录，不按任务归属，只取
     * target 命中产物路径或任务标题的记录，并给出全量条数。
     */
    private fun openTaskDetail(taskId: String) {
        val components = brainComponents ?: run {
            toast("大脑还在加载，请稍候再打开详情。")
            return
        }
        taskDetailTaskId = taskId
        taskDetailVisible = true
        screenSettings.visibility = View.GONE
        taskDetailController.root.visibility = View.VISIBLE
        taskDetailController.root.post { taskDetailController.root.fullScroll(View.FOCUS_UP) }
        Thread {
            val task = try {
                components.taskStore.getTask(taskId)
            } catch (_: Exception) {
                null
            }
            val steps = try {
                components.taskStepStore.listSteps(taskId)
            } catch (_: Exception) {
                emptyList()
            }
            val events = try {
                val sessionId = task?.sessionId
                if (sessionId == null) emptyList() else components.eventLog.entries(sessionId)
            } catch (_: Exception) {
                emptyList()
            }
            // P0：本任务的状态轨迹（审计，Room；旧任务在事件日志落地
            // 前创建时为空，详情页明标）。
            val taskEvents = try {
                components.taskEventStore.listEvents(taskId, 200)
            } catch (_: Exception) {
                emptyList()
            }
            val history = try {
                toolApprovalGate.approvalHistory()
            } catch (_: Exception) {
                emptyList()
            }
            val artifactPaths = TaskDetailArtifacts.extractPaths(steps)
            val approvals = history.filter { record ->
                val target = record.target ?: return@filter false
                artifactPaths.any { path -> target.contains(path) } ||
                    (task?.title?.isNotBlank() == true && target.contains(task.title))
            }
            // P2：流水线视图（阶段/plan/verdict 历史；未启用时 null，
            // 详情页只显示单 Agent 说明）。Room 读已在后台线程。
            val pipelineView = try {
                components.pipelineOrchestrator?.viewState(taskId, lastMechanicalByTask[taskId])
            } catch (_: Exception) {
                null
            }
            // 自主度（2026-11-11）：大脑总管的自动决定（记在控制审计里），
            // 任务详情页“自动决定”一节展示；门未接线时为空。
            val autoDecisions = try {
                components.autonomyGate?.autoDecisionsFor(taskId) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (!taskDetailVisible || taskDetailTaskId != taskId || isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                if (task == null) {
                    toast("任务不存在或已被删除。")
                    hideTaskDetail()
                    return@runOnUiThread
                }
                taskDetailController.render(
                    task, steps, events, approvals, history.size, artifactPaths, taskEvents,
                    pipeline = pipelineView,
                    onPipelineAction = { action -> handlePipelineAction(taskId, action, artifactPaths) },
                    autoDecisions = autoDecisions,
                )
            }
        }.start()
    }

    private fun hideTaskDetail() {
        taskDetailVisible = false
        taskDetailTaskId = null
        if (::taskDetailController.isInitialized) {
            taskDetailController.root.visibility = View.GONE
        }
    }

    /** 详情页产物链接：切到项目页并打开该文件（不存在时查看器如实报错）。 */
    private fun openDetailFile(relativePath: String) {
        hideTaskDetail()
        showEntry(DrawerController.ID_PROJECTS)
        projectController.openFileAt(relativePath)
    }

    /**
     * 阶段 1（U3）：逐任务控制。停止 / 重试真实接线；改目标仅 queued
     * 任务可改（弹框改描述）；暂停仅当前轮次任务可用（委托
     * ChatViewModel.pause()，安全检查点语义）；其余情况页脚明标未实现。
     */
    private fun taskControls(task: TaskRecord): View {
        val row = UiKit.horizontal(this).apply {
            setPadding(0, dp(10), 0, 0)
        }
        val terminal = TaskStates.isTerminal(task.state)
        if (!terminal) {
            if (task.taskId == chatViewModel.currentTurnTaskId() && chatViewModel.isRunning()) {
                row.addView(taskButton("暂停") { toast(chatViewModel.pause()); refreshTasks() })
            }
            row.addView(taskButton("停止") {
                // V5 C1：停止二次确认——确认后才走原有 stopTask 逻辑。
                confirmStopDialog("停止任务？", "确定要停止该任务吗？已停止的任务不会自动续跑。") { stopTask(task) }
            })
        }
        if (terminal) {
            row.addView(taskButton("重试") { retryTask(task) })
        }
        if (task.state == "review") {
            // P2：审查页打回/返工入口（P0 只留了 requestRework API，未接
            // 按钮）。流水线任务走协调器（计返工轮次、守 2 轮上限）；
            // 未启用流水线的任务走 P0 requestRework 原样。
            row.addView(taskButton("打回返工") { showReworkDialog(task) })
        }
        if (task.state == "queued") {
            row.addView(taskButton("改目标") { editTaskGoal(task) })
        }
        // “···”管理单：删除任务（可见入口，不藏长按里；项目/大脑
        // 管理入口的同款做法）。卡片与详情页共用 taskControls，
        // 两个入口一次接上；“···”常在，底下的“无可用操作”分支
        // 不再成立，随之删掉。
        row.addView(taskButton("···") { showTaskManageMenu(task) })
        return row
    }

    private fun taskButton(label: String, onClick: () -> Unit): View {
        return UiKit.text(this, label, 11f, R.color.text_primary, bold = true).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
        }
    }

    /**
     * V5 C1：停止二次确认。复用现有 AlertDialog 弹窗模式（见 editTaskGoal）：
     * 点"停止"走原有停止逻辑，点"取消"、按返回键或点框外什么都不发生。
     */
    private fun confirmStopDialog(title: String, message: String, onConfirmed: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("停止") { _, _ -> onConfirmed() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun stopTask(task: TaskRecord) {
        // 当前轮次任务：走聊天控制（安全检查点停轮）。
        if (task.taskId == chatViewModel.currentTurnTaskId()) {
            toast(chatViewModel.stop())
            refreshTasks()
            return
        }
        val components = brainComponents ?: return
        // 正在执行的分派任务：取消执行 job，runner 记为 cancelled。
        if (components.taskRunner.cancel(task.taskId)) {
            toast("已请求停止分派任务。")
            refreshTasks()
            return
        }
        // 未执行的 queued/assigned 任务：直接记 cancelled（合法迁移）。
        Thread {
            try {
                val current = components.taskStore.getTask(task.taskId)
                if (current != null && !TaskStates.isTerminal(current.state)) {
                    components.taskStore.updateTask(
                        task.taskId,
                        mapOf("state" to "cancelled", "result" to "任务被用户停止。"),
                        current.version,
                        current.goalVersion,
                        current.authEpoch,
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { toast("停止失败：${e.message}") }
                return@Thread
            }
            runOnUiThread { refreshTasks() }
        }.start()
    }

    private fun retryTask(task: TaskRecord) {
        val components = brainComponents ?: return
        // 终态任务无出边（TaskStates）：重试 = 新建一条 queued 副本
        // （sessionId 置空，交 runner 认领执行），原任务行保留不动。
        // 副本构造见 TaskRetry：保留 projectId / assignedAgentId /
        // memberId / brainId，parentTaskId 指回原任务——丢了指派的
        // 副本会被本地执行器认领，在手机上本地跑本该去远端的任务。
        Thread {
            try {
                val now = System.currentTimeMillis() / 1000
                val copy = TaskRetry.buildRetryCopy(task, now)
                components.taskStore.createTask(copy)
                runOnUiThread {
                    toast("已重新入队：${copy.title.ifBlank { copy.taskId }}")
                    refreshTasks()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("重试失败：${e.message}") }
            }
        }.start()
    }

    /**
     * 任务“···”管理单（项目/大脑管理入口的同款做法：可见按钮 +
     * ListDialog，不藏长按里）。目前只有删除一项：删除是破坏性
     * 操作，单独成项、进二次确认，不与查看详情混在一个单子里
     * （点卡片本身就是查看详情）。
     */
    private fun showTaskManageMenu(task: TaskRecord) {
        ListDialog.show(
            this,
            task.title.ifBlank { task.taskId },
            "只删除任务库里的这条记录（任务行、步骤、事件与审查记录）。仓库、分支与聊天消息不受影响。",
            listOf("删除任务"),
        ) { confirmDeleteTask(task) }
    }

    /**
     * 删除二次确认（复用停止/删除会话的 AlertDialog 模式）：
     * 点“删除”才走删除，点“取消”、按返回键或点框外什么都不发生。
     * 文案写明删除范围：只删任务记录本身，不动仓库、分支与聊天消息。
     */
    private fun confirmDeleteTask(task: TaskRecord) {
        val running = !TaskStates.isTerminal(task.state)
        val message = buildString {
            append("删除任务“${task.title.ifBlank { task.taskId }}”？")
            append("只删除任务库里的这条记录，仓库、分支与聊天消息不受影响，无法恢复。")
            if (running) append("任务正在执行，会先停止再删除。")
        }
        AlertDialog.Builder(this)
            .setTitle("删除任务")
            .setMessage(message)
            .setPositiveButton("删除") { _, _ -> deleteTaskRecord(task) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 删除任务记录：正在跑的先停再删；删完回任务列表、不留空页，
     * 任务库与成果汇总立刻刷新，不留幽灵行。
     *
     * 先停：当前轮次任务走聊天控制停轮；分派任务取消 runner job；
     * 其余非终态任务记 cancelled——与 stopTask 同款语义。任何一
     * 步失败都不拦删除：行删掉后，后续写回按任务不存在拒绝，不会
     * 复活；远端成员的迟到回传按 attemptId 围栏拒绝（P0）。
     */
    private fun deleteTaskRecord(task: TaskRecord) {
        val components = brainComponents ?: return
        val isCurrentTurn = task.taskId == chatViewModel.currentTurnTaskId()
        if (isCurrentTurn && chatViewModel.isRunning()) {
            chatViewModel.stop()
        }
        Thread {
            try {
                val current = components.taskStore.getTask(task.taskId)
                if (current != null && !TaskStates.isTerminal(current.state) && !isCurrentTurn) {
                    try {
                        if (!components.taskRunner.cancel(task.taskId)) {
                            components.taskStore.updateTask(
                                task.taskId,
                                mapOf("state" to "cancelled", "result" to "任务被用户删除。"),
                                current.version,
                                current.goalVersion,
                                current.authEpoch,
                            )
                        }
                    } catch (_: Exception) {
                        // 停止失败不拦删除：行删掉即终态，后续写回会被拒绝。
                    }
                }
                val removed = components.taskStore.deleteTask(task.taskId)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (taskDetailVisible && taskDetailTaskId == task.taskId) hideTaskDetail()
                    refreshTasks()
                    refreshResults()
                    toast(if (removed) "已删除任务。" else "任务已不存在。")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("删除失败：${e.message}") }
            }
        }.start()
    }

    private fun editTaskGoal(task: TaskRecord) {
        val components = brainComponents ?: return
        val input = EditText(this).apply {
            setText(task.description ?: task.title)
            setSingleLine(false)
            minLines = 3
        }
        AlertDialog.Builder(this)
            .setTitle("改目标")
            .setMessage("仅修改 queued 任务的目标描述；执行中的任务改目标未实现。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val goal = input.text.toString().trim()
                if (goal.isBlank()) {
                    toast("目标不能为空。")
                    return@setPositiveButton
                }
                Thread {
                    try {
                        val current = components.taskStore.getTask(task.taskId)
                        if (current == null || current.state != "queued") {
                            runOnUiThread { toast("任务已不在 queued，改目标未生效。") }
                            return@Thread
                        }
                        components.taskStore.updateTask(
                            task.taskId,
                            mapOf(
                                "description" to SecretRedactor.redact(goal, 4000),
                                "title" to SecretRedactor.redact(goal.take(40), 100),
                            ),
                            current.version,
                            current.goalVersion,
                            current.authEpoch,
                        )
                        runOnUiThread { refreshTasks() }
                    } catch (e: Exception) {
                        runOnUiThread { toast("改目标失败：${e.message}") }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * P2：详情页流水线按钮的接线（后台线程跑协调器，跑完刷新详情）。
     * [artifactPaths] 是打开详情时已算好的改动文件清单（write_file
     * 步骤抽出），机械检查用它当 changedFiles。
     */
    private fun handlePipelineAction(
        taskId: String,
        action: PipelineAction,
        artifactPaths: List<String>,
    ) {
        val components = brainComponents ?: run {
            toast("大脑还在加载，请稍候再试。")
            return
        }
        val orchestrator = components.pipelineOrchestrator ?: run {
            toast("流水线未就绪（大脑组件里没有协调器）。")
            return
        }
        when (action) {
            PipelineAction.ENABLE -> runPipelineOp(taskId) {
                orchestrator.enablePipeline(taskId)
            }
            PipelineAction.SUBMIT_PLAN -> showPlanDraftDialog(taskId)
            PipelineAction.APPROVE_PLAN -> runPipelineOp(taskId) {
                orchestrator.approvePlan(taskId)
            }
            PipelineAction.RUN_MECHANICAL_CHECKS -> {
                Thread {
                    val task = try {
                        components.taskStore.getTask(taskId)
                    } catch (_: Exception) {
                        null
                    }
                    if (task == null) {
                        runOnUiThread { toast("任务不存在。") }
                        return@Thread
                    }
                    // 本机路径今天没有任何测试 verdict 记录（成员回传的
                    // testVerdict 在主代码里尚无生产调用方）：如实传
                    // NOT_RUN，plan 列了测试命令时机械检查会判 FAILED。
                    val mechanical = orchestrator.runMechanicalChecks(
                        taskId, artifactPaths, PipelineTestVerdict.NOT_RUN,
                    )
                    runOnUiThread {
                        if (mechanical == null) {
                            toast("没有已批准的 plan，无法运行机械检查。")
                        } else {
                            lastMechanicalByTask[taskId] = mechanical
                            toast(mechanical.summaryText().lines().firstOrNull() ?: "机械检查完成。")
                            openTaskDetail(taskId)
                        }
                    }
                }.start()
            }
            PipelineAction.APPROVE_REVIEW -> {
                val mechanical = lastMechanicalByTask[taskId]
                if (mechanical == null) {
                    toast("先点“运行机械检查”，机械检查通过后才能通过审查。")
                    return
                }
                runPipelineOp(taskId) {
                    orchestrator.recordReview(
                        taskId, ReviewVerdict.APPROVE, "user",
                        mechanical = mechanical,
                    )
                }
            }
            PipelineAction.REQUEST_REWORK -> {
                val task = try {
                    components.taskStore.getTask(taskId)
                } catch (_: Exception) {
                    null
                }
                if (task == null) {
                    toast("任务不存在。")
                } else {
                    showReworkDialog(task)
                }
            }
        }
    }

    /** P2：协调器调用的通用包装（后台线程 + toast + 刷新详情）。 */
    private fun runPipelineOp(
        taskId: String,
        op: () -> PipelineOrchestrator.PipelineResult,
    ) {
        Thread {
            val result = try {
                op()
            } catch (e: Exception) {
                runOnUiThread { toast("流水线操作失败：${e.message}") }
                return@Thread
            }
            runOnUiThread {
                when (result) {
                    is PipelineOrchestrator.PipelineResult.Ok -> {
                        result.note?.let { toast(it) }
                        refreshTasks()
                        openTaskDetail(taskId)
                    }
                    is PipelineOrchestrator.PipelineResult.Rejected -> toast(result.reason)
                }
            }
        }.start()
    }

    /**
     * P2：plan 草稿对话框（目标 / 步骤 / 验收标准 / 测试命令 /
     * 文件范围，每组一行一条）。plan 由用户或规划者提供内容、用户
     * 在此提交草稿；批准是另一道门（详情页“批准 plan”按钮）。
     */
    private fun showPlanDraftDialog(taskId: String) {
        val components = brainComponents ?: return
        val orchestrator = components.pipelineOrchestrator ?: run {
            toast("流水线未就绪（大脑组件里没有协调器）。")
            return
        }
        val task = try {
            components.taskStore.getTask(taskId)
        } catch (_: Exception) {
            null
        }
        val objectiveInput = EditText(this).apply {
            setText(task?.description ?: task?.title ?: "")
            setSingleLine(false)
            minLines = 2
            hint = "目标（一段话）"
        }
        val stepsInput = EditText(this).apply {
            setSingleLine(false)
            minLines = 3
            hint = "实施步骤（一行一条，至少 1 条）"
        }
        val criteriaInput = EditText(this).apply {
            setSingleLine(false)
            minLines = 2
            hint = "验收标准（一行一条，至少 1 条）"
        }
        val testsInput = EditText(this).apply {
            setSingleLine(false)
            minLines = 2
            hint = "测试命令（一行一条，可空）"
        }
        val filesInput = EditText(this).apply {
            setSingleLine(false)
            minLines = 2
            hint = "文件清单（一行一个相对路径，可空 = 整个项目家）"
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(objectiveInput)
            addView(stepsInput)
            addView(criteriaInput)
            addView(testsInput)
            addView(filesInput)
        }
        AlertDialog.Builder(this)
            .setTitle("提交 plan 草稿")
            .setMessage("plan 批准前不写代码；目标、步骤、验收标准缺一不可。")
            .setView(layout)
            .setPositiveButton("提交草稿") { _, _ ->
                val plan = PlanArtifact.fromLines(
                    objective = objectiveInput.text.toString(),
                    steps = stepsInput.text.toString().lines(),
                    acceptanceCriteria = criteriaInput.text.toString().lines(),
                    testCommands = testsInput.text.toString().lines(),
                    filesInScope = filesInput.text.toString().lines(),
                    plannerId = "user",
                )
                runPipelineOp(taskId) { orchestrator.submitPlan(taskId, plan, "user") }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * P2：打回返工对话框（原因 = 返工清单，一行一条；空着不许打回）。
     * 流水线任务走协调器（计轮次、守 2 轮上限），其余任务走 P0
     * requestRework 原样。
     */
    private fun showReworkDialog(task: TaskRecord) {
        val components = brainComponents ?: return
        val input = EditText(this).apply {
            setSingleLine(false)
            minLines = 3
            hint = "打回原因（返工清单，一行一条）"
        }
        AlertDialog.Builder(this)
            .setTitle("打回返工")
            .setMessage("打回后任务回返工态重新认领；流水线任务返工上限 2 轮。")
            .setView(input)
            .setPositiveButton("打回") { _, _ ->
                val reason = input.text.toString().trim()
                if (reason.isBlank()) {
                    toast("打回须写原因，不能空着打回。")
                    return@setPositiveButton
                }
                val orchestrator = components.pipelineOrchestrator
                Thread {
                    val result = try {
                        if (orchestrator != null) {
                            orchestrator.userRequestRework(task.taskId, reason)
                        } else {
                            val reworked = components.taskScheduler.requestRework(task.taskId, reason.take(4000))
                            if (reworked != null) {
                                PipelineOrchestrator.PipelineResult.Ok(
                                    PipelineState(task.taskId, enabled = false),
                                    "已打回返工",
                                )
                            } else {
                                PipelineOrchestrator.PipelineResult.Rejected("打回返工失败：任务不在 review 态")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast("打回返工失败：${e.message}") }
                        return@Thread
                    }
                    runOnUiThread {
                        when (result) {
                            is PipelineOrchestrator.PipelineResult.Ok -> {
                                result.note?.let { toast(it) }
                                refreshTasks()
                                openTaskDetail(task.taskId)
                            }
                            is PipelineOrchestrator.PipelineResult.Rejected -> toast(result.reason)
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun stateChip(state: String): TextView {
        val terminal = TaskStates.isTerminal(state)
        val success = state == "completed"
        val danger = state == "failed" || state == "cancelled"
        val color = when {
            success -> R.color.success
            danger -> R.color.danger
            terminal -> R.color.text_tertiary
            else -> R.color.warning
        }
        val background = when {
            success -> R.drawable.bg_chip_success
            danger -> R.drawable.bg_chip_danger
            else -> R.drawable.bg_chip_warning
        }
        return UiKit.text(this, state, 10f, color).apply {
            setBackgroundResource(background)
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
    }

    // ------------------------------------------------------------------
    // Agents / device / diagnostics
    // ------------------------------------------------------------------

    private fun bindAgents() {
        agentList = screenAgents.findViewById(R.id.agentList)
        deviceInfo = screenAgents.findViewById(R.id.deviceInfo)
        agentStore = SharedPreferencesAgentStore(applicationContext)
        agentTester = AgentConnectionTester(
            versionRunner = { binary -> AgentDiscovery(applicationContext).probeBinary(binary) },
            deviceProbe = { record -> probeRemoteDeviceAgent(record) },
            platformProbe = { record -> probePlatformAgent(record) },
        )
        agentController = AgentScreenController(this, agentList, deviceInfo)
        agentController.actions = object : AgentScreenController.AgentCardActions {
            override fun onChat(agent: AgentRecord) = openAgentSession(agent)
            override fun onAssign(agent: AgentRecord) = assignTaskToAgent(agent)
            override fun onTest(agent: AgentRecord) = testAgentConnection(agent)
            override fun onEdit(agent: AgentRecord) = showAgentForm(agent)
            override fun onRemove(agent: AgentRecord) = removeAgent(agent)
            // Agent 开关（2026-11-12）：关掉只是停用，不是删除；保存后
            // 立即重画列表（热插拔，不用重启 App）。存失败时如实提示，
            // 不假装开关已翻（开关的视觉态由重画后的记录决定）。
            override fun onToggle(agent: AgentRecord, enabled: Boolean) {
                Thread {
                    val ok = try {
                        agentStore.setAgentEnabled(agent.agentId, enabled)
                    } catch (_: Exception) {
                        false
                    }
                    runOnUiThread {
                        if (ok) {
                            toast(
                                if (enabled) "已启用 ${agent.displayName}：可选、可进频道、可接派活。"
                                else "已停用 ${agent.displayName}：不在选 Agent 里出现，不进频道，不接派活。",
                            )
                            refreshAgents()
                        } else {
                            toast("开关没翻过去：${agent.displayName} 的记录找不到了，请刷新 Agent 页再试。")
                            refreshAgents()
                        }
                    }
                }.start()
            }
        }
        // Hand 自更新（2026-11-12）：设备卡“更新 Hand”与设置页入口。
        // 管理调用走密封通道（HandClient.requestHandAdmin），hello 探针
        // 走 DirectTransport.fetchHello（与 rediscoverDeviceAgents 同款）。
        handUpdateController = HandUpdateController(
            activity = this,
            scope = lifecycleScope,
            settingsStore = SharedPrefsHandSyncSettingsStore(this),
            loadBundle = {
                HandInstallBundleLoader.load(AssetReader { path ->
                    try {
                        assets.open(path).use { it.readBytes() }
                    } catch (_: Exception) {
                        null
                    }
                })
            },
            peerFor = { device ->
                val record = try {
                    pairingSnapshot.load(device.deviceId)
                } catch (_: Exception) {
                    null
                }
                if (record?.state == PairingState.CONFIRMED) {
                    device.toDiscoveredPeer(record.fingerprintSha256)
                } else {
                    null
                }
            },
            handClientFor = { peer -> wsHandClient(peer) },
            probeHello = { device ->
                val peer = try {
                    val record = pairingSnapshot.load(device.deviceId)
                    if (record?.state == PairingState.CONFIRMED) {
                        device.toDiscoveredPeer(record.fingerprintSha256)
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
                if (peer == null) {
                    null
                } else {
                    try {
                        val identity = deviceIdentityStore.getOrCreate()
                        directTransport.fetchHello(peer, identity.deviceId, identity.publicKey)
                    } catch (_: Exception) {
                        null
                    }
                }
            },
            persistHandVersion = { device, version ->
                try {
                    remoteDeviceStore.saveDevice(device.copy(handVersion = version))
                } catch (_: Exception) {
                }
            },
            onRequestFirstInstall = { startDeviceInstall() },
        )
        screenAgents.findViewById<View>(R.id.buttonAgentRefresh).setOnClickListener { refreshAgents() }
        screenAgents.findViewById<View>(R.id.buttonDeviceRefresh).setOnClickListener { refreshAgents() }
        screenAgents.findViewById<View>(R.id.buttonAddAgent).setOnClickListener { showAgentForm(null) }
        agentController.renderDevice(deviceLines())
    }

    private fun refreshAgents() {
        val devicesOnly = currentEntry == DrawerController.ID_DEVICES
        agentController.renderDevice(deviceLines())
        // V5 设备点按刷新修复（2026-11-11）：此前设备页先调
        // showDevicesLoading() 清空整个列表，再等 5 秒 mDNS 发现才重
        // 画——点完设备列表先空白 5 秒，看着像卡住；清空又把
        // ScrollView 的滚动位置压回顶部，刚加/刚确认的设备在“远程设
        // 备”区段、首屏之外，要用户自己下滑才看得到结果。现改为两段
        // 画：本地数据（配对 + 远程设备，Room/SharedPreferences，毫
        // 秒级）先读先画，点完立刻出结果；5 秒 mDNS 发现回来后再补
        // 一次已发现设备。两次重画都保存/恢复滚动位置。Room 禁止主
        // 线程访问，本地读仍在后台线程做，不上 UI 线程。
        if (!devicesOnly) agentController.showLoading()
        Thread {
            val added = if (devicesOnly) emptyList() else try {
                agentStore.listAgents()
            } catch (e: Exception) {
                emptyList<AgentRecord>()
            }
            // 设备页第一段：本地数据先画（不等 mDNS）。
            if (devicesOnly) {
                val pairingsNow = try {
                    pairingStore.all()
                } catch (e: Exception) {
                    emptyList()
                }
                // V5 加设备修复（2026-11-11）：设备页此前只传 LAN 发现 + 配对
                // 记录，手动添加的远程设备永远不出现——加完也看不见，用户眼
                // 里就是“添加设备不能用”。现把远程设备连同配对状态一起传
                // 过去，设备页单列“远程设备”区段并给添加入口。
                val remoteRowsNow = try {
                    buildRemoteDeviceRows(remoteDeviceStore.listDevices(), pairingsNow, bundledHandVersion())
                } catch (e: Exception) {
                    emptyList()
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (currentEntry != DrawerController.ID_DEVICES) return@runOnUiThread
                    renderDeviceListNow(emptyList(), pairingsNow, remoteRowsNow)
                }
            }
            // 阶段 3（U5/批 3c）：真实 LAN 发现（5 秒 mDNS）+ 配对记录。
            val discovered = if (devicesOnly) try {
                runBlocking { lanTransport.discover(5000) }
            } catch (e: Exception) {
                emptyList()
            } else emptyList()
            val pairings = if (devicesOnly) try {
                pairingStore.all()
            } catch (e: Exception) {
                emptyList()
            } else emptyList()
            val remoteRows = if (devicesOnly) try {
                buildRemoteDeviceRows(remoteDeviceStore.listDevices(), pairings, bundledHandVersion())
            } catch (e: Exception) {
                emptyList()
            } else emptyList()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (devicesOnly) {
                    // 用户 5 秒内已切走别的页：本轮不画，目标页进页时自己会
                    // 刷（showEntry 调 refreshAgents），不把设备区段画到别页。
                    if (currentEntry != DrawerController.ID_DEVICES) return@runOnUiThread
                    renderDeviceListNow(discovered, pairings, remoteRows)
                } else {
                    // Agent 页只列已添加的 Agent（2026-11-11：沙箱扫描区已删）。
                    agentController.clearAgents()
                    agentController.renderAddedAgents(added)
                }
            }
        }.start()
    }

    /**
     * 设备页重画（点设备后立刻出结果用）：清列表 + 按给定数据重画，
     * 并恢复重画前的滚动位置——不清滚动位置时，列表一清空内容高度
     * 塌掉，ScrollView 会把 scrollY 压回 0，刚操作的设备在首屏外，
     * 用户得自己下滑才看得到（即“点设备卡住、扫一下才出结果”）。
     * 只在设备页用；Agent 页不动。
     */
    private fun renderDeviceListNow(
        discovered: List<DiscoveredPeer>,
        pairings: List<PairingRecord>,
        remoteRows: List<RemoteDeviceRow>,
    ) {
        val scrollView = screenAgents as ScrollView
        val savedY = scrollView.scrollY
        agentController.clearAgents()
        agentController.renderDevice(deviceLines())
        agentController.renderDevices(
            discovered,
            pairings,
            onPair = { peer -> requestPairing(peer) },
            onConfirm = { record -> confirmPairing(record) },
            onReject = { record -> rejectPairing(record) },
            onRevoke = { record -> revokePairing(record) },
            remoteDevices = remoteRows,
            // 未配对远程设备断点续配：有地址，直接 hello → 指纹 + 配对码。
            onResumePairing = { device ->
                probeAndAddRemoteDevice(device.displayName, device.host, device.port)
            },
            onAddRemote = { showAddRemoteDeviceDialog() },
            onRediscover = { device -> rediscoverDeviceAgents(device) },
            onEditRemote = { device -> showEditRemoteDeviceDialog(device) },
            onUpdateHand = { device -> handUpdateController?.updateDevice(device) },
        )
        scrollView.post { scrollView.scrollTo(0, savedY) }
    }

    // ------------------------------------------------------------------
    // Devices: pairing (stage 3, U5 / batch 3c)
    // ------------------------------------------------------------------

    /**
     * 发起配对（V5 配对集成，2026-11-11）：局域网发现的设备，点一台
     * 就 hello 探测 → 配对对话框（指纹核对 + 6 位配对码）。不再是
     * “先记一条待确认、再回本页点确认”的两截流程。
     */
    private fun requestPairing(peer: DiscoveredPeer) {
        toast("正在连接 ${peer.displayName}…")
        Thread {
            try {
                val identity = deviceIdentityStore.getOrCreate()
                val hello = runBlocking {
                    directTransport.fetchHello(peer, identity.deviceId, identity.publicKey)
                }
                // P1 能力卡：hello 成功，存仓库快照（配对前也存，快照按
                // deviceId+agentId 记，配对后建卡时能用上）。
                noteHelloForCapabilityCards(hello)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    showPairingDialog(peer.displayName, peer.host, peer.port, hello)
                }
            } catch (e: Exception) {
                runOnUiThread { toast("连接失败：${e.message}") }
            }
        }.start()
    }

    /**
     * 待确认的配对记录断点续配（V5 配对集成，2026-11-11）：先在局域网
     * 内重新找到该设备，再进配对对话框（指纹 + 配对码）。找不到就
     * 如实说，不编造；不在局域网的设备请走“添加设备 → 手输 IP”。
     */
    private fun confirmPairing(record: PairingRecord) {
        toast("正在局域网内找 ${record.displayName}…")
        Thread {
            val discovered = try {
                runBlocking { lanTransport.discover(5000) }
            } catch (_: Exception) {
                emptyList()
            }
            val peer = discovered.firstOrNull { it.deviceId == record.deviceId }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (peer == null) {
                    toast("局域网内没找到 ${record.displayName}：请确认它在同一 Wi-Fi 且 Hand 在运行；不在局域网的设备请用“添加设备 → 手输 IP”配对。")
                } else {
                    requestPairing(peer)
                }
            }
        }.start()
    }

    private fun rejectPairing(record: PairingRecord) {
        Thread {
            try {
                pairingManager.reject(record.deviceId, "用户在本机拒绝了配对请求")
                refreshPairingSnapshot()
                runOnUiThread {
                    toast("已拒绝配对。")
                    refreshAgents()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("拒绝失败：${e.message}") }
            }
        }.start()
    }

    private fun revokePairing(record: PairingRecord) {
        Thread {
            try {
                pairingManager.revoke(record.deviceId, "用户撤销配对")
                refreshPairingSnapshot()
                runOnUiThread {
                    toast("已撤销配对。")
                    refreshAgents()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("撤销失败：${e.message}") }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Agents: add / manage (stage 3, A1 P1)
    // ------------------------------------------------------------------

    /**
     * 三类合一的添加/编辑表单（P1）：云端模型 API / 订阅授权 Agent /
     * 本机 CLI Agent。密钥值只进 Keystore，记录里只存 authRef；
     * 保存前跑 AgentRecord.validate()，非法不落盘。
     */
    private fun showAgentForm(existing: AgentRecord?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        fun label(text: String): TextView = UiKit.text(this, text, 12f, R.color.text_primary, bold = true).apply {
            setPadding(0, dp(10), 0, dp(4))
        }
        fun field(hint: String, initial: String? = null, password: Boolean = false): EditText =
            EditText(this).apply {
                setHint(hint)
                setText(initial ?: "")
                if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextSize(13f)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundResource(R.drawable.bg_input)
            }
        val nameField = field("名字，如 DeepSeek 闪电", existing?.displayName)
        val typeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val typeApi = RadioButton(this).apply { text = "云端模型 API（填服务地址 + 模型 + 密钥）" }
        val typeSub = RadioButton(this).apply { text = "订阅授权 Agent（已登录的 CLI 会员额度，无密钥框）" }
        val typeCli = RadioButton(this).apply { text = "本机 CLI Agent（填二进制名，如 codebuddy）" }
        // 设备上的 Agent（V5，2026-11-11）：远程设备（Mac / VPS / 小米 12）
        // 上的 CLI，不是手机沙箱里的；选了之后出现设备选择器 + 工作文件夹。
        val typeDevice = RadioButton(this).apply { text = "设备上的 Agent（远程设备上的 CLI，如 Mac / VPS 上的 codex）" }
        // 平台 Agent（V5，2026-11-11，maintainer 点名要的纠正性功能）：
        // 平台（example.invalid）上的 Agent，如 Example Agent——有自己的 App +
        // 云电脑，没有公开聊天 API，但平台能触到它：平台给它配了实时
        // 唤醒（有单派给它时平台往它的 webhook 地址发通知，它醒来后
        // 自己用连接器领单）。App 经平台频道给它发消息、轮询收回复；
        // 只发任务消息，不传代码、不推代码。
        val typePlatform = RadioButton(this).apply { text = "平台 Agent（平台上的 Agent，如 Example Agent：走平台通道 + webhook 唤醒）" }
        // 云端 Agent（V5，2026-11-11，老项目“添加云端 Agent”）：
        // 对方把 App 加为自定义 MCP 连接器、自己来领单——方向与平台
        // Agent 相反，是 Agent 来连 App，不是 App 去调 Agent。
        val typeCloud = RadioButton(this).apply { text = "云端 Agent（MCP 连接器：对方 App 自己来领单）" }
        typeGroup.addView(typeApi)
        typeGroup.addView(typeSub)
        typeGroup.addView(typeCli)
        typeGroup.addView(typeDevice)
        typeGroup.addView(typePlatform)
        typeGroup.addView(typeCloud)
        // Manus（云端任务型）：任务型 API，不走 /models 探针；勾选后
        // 适配器默认 manus、服务地址默认 https://api.manus.ai、模型栏
        // 改填档位（standard / lite / max）。只在 MODEL_API 下显示。
        val manusBox = CheckBox(this).apply {
            text = "Manus（云端任务型 API：任务型接口，不走 /models 探针）"
            isChecked = existing?.let { ManusTaskAdapter.isManusRecord(it) } ?: false
        }
        when (existing?.brainKind) {
            BrainKind.SUBSCRIPTION -> typeSub.isChecked = true
            BrainKind.AGENT_RUNTIME -> typeCli.isChecked = true
            BrainKind.PLATFORM_AGENT -> typePlatform.isChecked = true
            BrainKind.CLOUD_AGENT -> typeCloud.isChecked = true
            else -> typeApi.isChecked = true
        }
        // 编辑已有远端 Agent 时预选“设备上的 Agent”（它与本机 CLI
        // 共用 AGENT_RUNTIME，但 deviceId 非空才是远端）。
        if (existing?.deviceId != null && existing.brainKind == BrainKind.AGENT_RUNTIME) {
            typeDevice.isChecked = true
        }
        val baseUrlField = field("服务地址，如 https://api.deepseek.com；本地模型可用 http://127.0.0.1:8080", existing?.baseUrl)
        val modelField = field("模型，如 deepseek-flash", existing?.model)
        val adapterField = field("适配器 id（默认 deepseek；订阅填 subscription；CLI 填二进制名）", existing?.adapterId)
        val binaryField = field("CLI 二进制名，如 codebuddy", existing?.binary)
        // 设备上的 Agent：设备下拉框取 RemoteDeviceStore 的真数据
        // （Mac / remote-host / 小米 12 等用户手动加过的远程设备）；
        // 没加过设备时下拉框为空，保存时明说“先加设备”，不静默失败。
        val remoteDevices: List<RemoteDevice> = try {
            remoteDeviceStore.listDevices()
        } catch (_: Exception) {
            emptyList()
        }
        val deviceSpinner = Spinner(this)
        deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            remoteDevices.map { "${it.displayName}（${it.host}:${it.port}）" },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        existing?.deviceId?.let { id ->
            val index = remoteDevices.indexOfFirst { it.deviceId == id }
            if (index >= 0) deviceSpinner.setSelection(index)
        }
        val deviceFolderField = field("设备上的工作文件夹，如 /Users/example-user（聊天与任务路由要用）", existing?.deviceFolder)
        val deviceEmptyHint = UiKit.text(
            this,
            "还没加设备：请先去设备页添加远程设备，再回来加 Agent。",
            11f,
            R.color.text_tertiary,
        ).apply { visibility = if (remoteDevices.isEmpty()) View.VISIBLE else View.GONE }
        val authRefField = field("密钥引用名，如 key:deepseek-1；不填则无密钥", existing?.authRef)
        val secretField = field("密钥值（只存本机 Keystore，不进记录；留空表示不改）", null, password = true)
        val capsField = field("能力，逗号分隔，如 dev,review；可空", existing?.capabilities?.joinToString(","))
        val dispatchableBox = CheckBox(this).apply {
            text = "可派单（不可派单的 Agent 只对话、不接任务）"
            isChecked = existing?.dispatchable ?: true
        }
        val limitsField = field("已知限制，可空", existing?.knownLimits)
        form.addView(label("名字"))
        form.addView(nameField)
        form.addView(label("类型"))
        form.addView(typeGroup)
        form.addView(manusBox)
        // 设备上的 Agent 专区：只在选了“设备上的 Agent”时显示
        // （refreshVisibility 里按类型开关整个区）。
        val deviceSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        deviceSection.addView(label("设备（已加的远程设备）"))
        deviceSection.addView(deviceSpinner)
        deviceSection.addView(deviceEmptyHint)
        deviceSection.addView(label("设备上的工作文件夹"))
        deviceSection.addView(deviceFolderField)
        form.addView(deviceSection)
        // 平台 Agent 专区（V5，2026-11-11）：只在选了“平台 Agent”时显示。
        // 平台凭证是 App 级的（App 在平台上注册的 Agent 的 Bearer 令牌），
        // 所有平台 Agent 共用这一份；webhook 签名密钥是每 Agent 一个。
        // 两类值都只进本机 Keystore，表单里只留引用名、不留值。
        val platformSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val platformAgentIdField = field("平台 Agent ID，如 example-agent", existing?.platformAgentId)
        val platformHostField = field("平台地址主机名（必填）", existing?.platformHost)
        val platformEndpointNote = UiKit.text(
            this,
            PlatformAgent.ENDPOINT_NOTE,
            11f,
            R.color.text_tertiary,
        )
        // 专用频道（V5，2026-11-11）：只预填已配的专用频道；旧记录里
        // 存的“第一批”群 id 不预填——空着就是仍走共享群，别让它看着
        // 像已经配了专用频道。
        val platformChannelIdField = field(
            "专用频道 ID（可选）",
            existing?.platformChannelId?.takeIf { it != PlatformAgent.DEFAULT_CHANNEL_ID },
        )
        val platformChannelNote = UiKit.text(
            this,
            PlatformAgent.CHANNEL_NOTE,
            11f,
            R.color.text_tertiary,
        )
        val webhookUrlField = field("webhook 地址（可选；唤醒配置已在平台上，这里只供测试通知用）", existing?.webhookUrl)
        val webhookSecretField = field("webhook 签名密钥值（可选；只存本机 Keystore，留空表示不改）", null, password = true)
        val platformTokenField = field("App 的平台凭证值（在平台服务器终端签发后填入（v5_ 开头）；只存本机 Keystore，留空表示不改）", null, password = true)
        val directGithubBox = CheckBox(this).apply {
            text = "直接读 GitHub（该 Agent 能直接读 GitHub 审查完整代码）"
            isChecked = existing?.directGithubRead ?: false
        }
        val webhookTestStatus = UiKit.text(
            this,
            "还没发过测试通知。",
            11f,
            R.color.text_tertiary,
        )
        val testWebhookButton = Button(this).apply {
            text = "发一条测试通知"
            setOnClickListener {
                val hook = webhookUrlField.text.toString().trim()
                if (hook.isBlank()) {
                    webhookTestStatus.text = "请先填 webhook 地址。"
                    toast("请先填 webhook 地址。")
                    return@setOnClickListener
                }
                val targetId = platformAgentIdField.text.toString().trim()
                    .ifBlank { existing?.platformAgentId ?: "" }
                if (targetId.isBlank()) {
                    webhookTestStatus.text = "请先填平台 Agent ID。"
                    toast("请先填平台 Agent ID。")
                    return@setOnClickListener
                }
                val typedSecret = webhookSecretField.text.toString()
                val storedSecret = existing?.let {
                    platformCredentialStore.loadWebhookSecret(it.agentId)
                }
                val signingKey = typedSecret.ifBlank { storedSecret }
                webhookTestStatus.text = "正在发测试通知…"
                Thread {
                    val result = PlatformWebhookTester.testNotification(
                        webhookUrl = hook,
                        platformAgentId = targetId,
                        signingKey = signingKey,
                    )
                    val summary = when (result) {
                        is WebhookTestResult.Ok ->
                            "测试通知已通：webhook 回 HTTP ${result.httpStatus}（只证明端点可达，不证明平台会用它唤醒对方）。"
                        is WebhookTestResult.HttpError ->
                            "测试通知不通：webhook 回 HTTP ${result.httpStatus}（${result.bodyPreview.ifBlank { "无回包说明" }}）。"
                        is WebhookTestResult.NetworkError ->
                            "测试通知发不出去：${result.message}"
                    }
                    runOnUiThread {
                        webhookTestStatus.text = summary
                        toast(summary)
                    }
                }.start()
            }
        }
        platformSection.addView(label("平台 Agent ID"))
        platformSection.addView(platformAgentIdField)
        platformSection.addView(label("平台地址"))
        platformSection.addView(platformHostField)
        platformSection.addView(platformEndpointNote)
        platformSection.addView(label("专用频道 ID"))
        platformSection.addView(platformChannelIdField)
        platformSection.addView(platformChannelNote)
        platformSection.addView(label("webhook 地址"))
        platformSection.addView(webhookUrlField)
        platformSection.addView(label("webhook 签名密钥值"))
        platformSection.addView(webhookSecretField)
        platformSection.addView(label("App 的平台凭证值"))
        platformSection.addView(platformTokenField)
        platformSection.addView(directGithubBox)
        platformSection.addView(testWebhookButton)
        platformSection.addView(webhookTestStatus)
        form.addView(platformSection)
        // 云端 Agent 专区（V5，2026-11-11）：只在选了“云端 Agent”时显示。
        // 在哪干活只做分组、不改变权限；角色开发+审查默认勾；钥匙保存时
        // 现生成、只存本机 Keystore，设置说明里展示出来让用户去对方
        // App 里填（保存后自动弹出一次设置说明）。
        val cloudSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val cloudHomeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val cloudHomeCloud = RadioButton(this).apply { text = "云端" }
        val cloudHomeDevice = RadioButton(this).apply { text = "归到某设备下（只做分组，不改变权限）" }
        cloudHomeGroup.addView(cloudHomeCloud)
        cloudHomeGroup.addView(cloudHomeDevice)
        if (existing?.cloudHomeDeviceId != null) cloudHomeDevice.isChecked = true
        else cloudHomeCloud.isChecked = true
        val cloudHomeSpinner = Spinner(this)
        cloudHomeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            remoteDevices.map { "${it.displayName}（${it.host}:${it.port}）" },
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        existing?.cloudHomeDeviceId?.let { id ->
            val index = remoteDevices.indexOfFirst { it.deviceId == id }
            if (index >= 0) cloudHomeSpinner.setSelection(index)
        }
        val roleDevBox = CheckBox(this).apply {
            text = "开发（领活写代码）"
            isChecked = existing?.cloudRoles?.contains(CloudAgent.ROLE_DEVELOP) ?: true
        }
        val roleReviewBox = CheckBox(this).apply {
            text = "审查（审查别人的交付）"
            isChecked = existing?.cloudRoles?.contains(CloudAgent.ROLE_REVIEW) ?: true
        }
        val roleDeliverBox = CheckBox(this).apply {
            text = "递代码（把交付的改动递给读不到 GitHub 的审查方）"
            isChecked = existing?.cloudRoles?.contains(CloudAgent.ROLE_DELIVER) ?: false
        }
        val canDispatchBox = CheckBox(this).apply {
            text = "允许它派活（开着能给其他 Agent 派活，关掉只能领活、读、交结果）"
            isChecked = existing?.canDispatch ?: true
        }
        val setupSheetButton = Button(this).apply {
            text = "查看设置说明（连接地址 + 钥匙 + 各 App 设置）"
            visibility = if (existing != null && CloudAgent.isCloudAgentRecord(existing)) View.VISIBLE else View.GONE
            setOnClickListener {
                val record = existing ?: return@setOnClickListener
                val key = cloudCredentialStore.loadKey(record.agentId)
                val sheet = if (key == null) {
                    "钥匙在 Keystore 里找不到：请重新保存一次该 Agent（保存时会生成一把新钥匙）。"
                } else {
                    CloudAgentSetupSheet.build(record, key, CloudAgentMcpEndpoint.publicUrl())
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("设置说明：${record.displayName}")
                    .setMessage(sheet)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
        cloudSection.addView(label("在哪干活"))
        cloudSection.addView(cloudHomeGroup)
        cloudSection.addView(cloudHomeSpinner)
        cloudSection.addView(label("角色（可多选）"))
        cloudSection.addView(roleDevBox)
        cloudSection.addView(roleReviewBox)
        cloudSection.addView(roleDeliverBox)
        cloudSection.addView(canDispatchBox)
        cloudSection.addView(setupSheetButton)
        form.addView(cloudSection)
        form.addView(label("服务地址"))
        form.addView(baseUrlField)
        form.addView(label("模型"))
        form.addView(modelField)
        form.addView(label("适配器 id"))
        form.addView(adapterField)
        form.addView(label("CLI 二进制名"))
        form.addView(binaryField)
        form.addView(label("密钥引用名"))
        form.addView(authRefField)
        form.addView(label("密钥值"))
        form.addView(secretField)
        form.addView(label("能力"))
        form.addView(capsField)
        form.addView(dispatchableBox)
        form.addView(label("已知限制"))
        form.addView(limitsField)

        fun currentKind(): BrainKind = when {
            typeSub.isChecked -> BrainKind.SUBSCRIPTION
            typeCli.isChecked || typeDevice.isChecked -> BrainKind.AGENT_RUNTIME
            typePlatform.isChecked -> BrainKind.PLATFORM_AGENT
            typeCloud.isChecked -> BrainKind.CLOUD_AGENT
            else -> BrainKind.MODEL_API
        }
        // “设备上的 Agent”与“本机 CLI”共用 AGENT_RUNTIME，区别只在
        // deviceId：前者指向一台远程设备，后者 deviceId 为空。
        fun isDeviceAgent(): Boolean = typeDevice.isChecked
        fun isPlatformAgent(): Boolean = typePlatform.isChecked
        fun isCloudAgent(): Boolean = typeCloud.isChecked
        fun refreshVisibility() {
            val kind = currentKind()
            val isManus = kind == BrainKind.MODEL_API && manusBox.isChecked
            manusBox.visibility = if (kind == BrainKind.MODEL_API) View.VISIBLE else View.GONE
            baseUrlField.visibility = if (kind == BrainKind.MODEL_API) View.VISIBLE else View.GONE
            modelField.visibility = if (kind == BrainKind.MODEL_API) View.VISIBLE else View.GONE
            modelField.hint = if (isManus) {
                "档位：standard / lite / max（默认 standard；“Manus 2.0 Lite”填 lite）"
            } else {
                "模型，如 deepseek-flash"
            }
            binaryField.visibility = if (kind == BrainKind.AGENT_RUNTIME) View.VISIBLE else View.GONE
            binaryField.hint = if (isDeviceAgent()) "设备上的 CLI 二进制名，如 codex" else "CLI 二进制名，如 codebuddy"
            deviceSection.visibility = if (isDeviceAgent()) View.VISIBLE else View.GONE
            platformSection.visibility = if (isPlatformAgent()) View.VISIBLE else View.GONE
            cloudSection.visibility = if (isCloudAgent()) View.VISIBLE else View.GONE
            cloudHomeSpinner.visibility = if (isCloudAgent() && cloudHomeDevice.isChecked) View.VISIBLE else View.GONE
            authRefField.visibility = if (kind == BrainKind.MODEL_API) View.VISIBLE else View.GONE
            secretField.visibility = if (kind == BrainKind.MODEL_API) View.VISIBLE else View.GONE
        }
        typeGroup.setOnCheckedChangeListener { _, _ -> refreshVisibility() }
        cloudHomeGroup.setOnCheckedChangeListener { _, _ -> refreshVisibility() }
        manusBox.setOnCheckedChangeListener { _, _ -> refreshVisibility() }
        refreshVisibility()

        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加 Agent" else "编辑 Agent")
            .setView(scroll)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val kind = currentKind()
                val name = nameField.text.toString().trim()
                // 设备上的 Agent（V5，2026-11-11）：记录由 DeviceAgentForm
                // 纯逻辑拼（deviceId/deviceFolder 由表单值定），不建本机
                // CLI 大脑 stub——远端二进制不是手机沙箱里的 CLI；
                // 不走通用大脑，聊天走 Hand 直连（见 AgentChatRouter）。
                if (isDeviceAgent()) {
                    val selectedDevice = remoteDevices.getOrNull(deviceSpinner.selectedItemPosition)
                    val built = DeviceAgentForm.buildRecord(
                        name = name,
                        agentId = existing?.agentId ?: slugAgentId(name),
                        devicesEmpty = remoteDevices.isEmpty(),
                        deviceId = selectedDevice?.deviceId,
                        binary = binaryField.text.toString(),
                        deviceFolder = deviceFolderField.text.toString(),
                        adapterId = adapterField.text.toString(),
                        capabilities = capsField.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        dispatchable = dispatchableBox.isChecked,
                        knownLimits = limitsField.text.toString().trim().ifBlank { null },
                        existing = existing,
                    )
                    when (built) {
                        is DeviceAgentForm.Result.Rejected -> {
                            toast(built.message)
                            return@setOnClickListener
                        }
                        is DeviceAgentForm.Result.Ok -> {
                            val record = built.record
                            // 与下方三类共用同一套“id 唯一 + 落盘 + 关框 +
                            // 提示”；这里单独写一份，不动三类的保存路径。
                            var finalId = record.agentId
                            if (existing == null && agentStore.getAgent(finalId) != null) {
                                var suffix = 2
                                while (agentStore.getAgent("$finalId-$suffix") != null) suffix += 1
                                finalId = "$finalId-$suffix"
                                toast("id 已存在，已改为 $finalId。")
                            }
                            try {
                                agentStore.saveAgent(record.copy(agentId = finalId))
                            } catch (e: Exception) {
                                toast("保存失败：${e.message}")
                                return@setOnClickListener
                            }
                            dialog.dismiss()
                            val paired = try {
                                pairingStore.load(selectedDevice?.deviceId ?: "")?.state == PairingState.CONFIRMED
                            } catch (_: Exception) {
                                false
                            }
                            toast(
                                "已保存 Agent：$name（设备上的 Agent，走远程 Hand 路由" +
                                    if (paired) "）" else "；设备尚未配对，配对后才能对话）",
                            )
                            refreshAgents()
                            return@setOnClickListener
                        }
                    }
                }
                // 平台 Agent（V5，2026-11-11）：记录由 PlatformAgentForm
                // 纯逻辑拼，不建大脑 stub、不走通用大脑，聊天走平台频道
                // （见 AgentChatRouter.UsePlatformAgent）。平台凭证与
                // webhook 签名密钥只进本机 Keystore，记录里只存引用名。
                if (isPlatformAgent()) {
                    // id 唯一性先定（后缀影响 webhook 密钥引用名，须在拼
                    // 记录之前定下来；改名提示只在真正保存成功时再 toast）。
                    var finalId = existing?.agentId ?: slugAgentId(name)
                    var renamed = false
                    if (existing == null && agentStore.getAgent(finalId) != null) {
                        var suffix = 2
                        while (agentStore.getAgent("$finalId-$suffix") != null) suffix += 1
                        finalId = "$finalId-$suffix"
                        renamed = true
                    }
                    val built = PlatformAgentForm.buildRecord(
                        name = name,
                        agentId = finalId,
                        platformAgentId = platformAgentIdField.text.toString(),
                        platformHost = platformHostField.text.toString(),
                        platformChannelId = platformChannelIdField.text.toString(),
                        webhookUrl = webhookUrlField.text.toString(),
                        webhookSecretRef = if (webhookSecretField.text.toString().isNotBlank() ||
                            existing?.webhookSecretRef != null
                        ) {
                            PlatformAgentCredentialStore.webhookSecretRefFor(finalId)
                        } else {
                            null
                        },
                        directGithubRead = directGithubBox.isChecked,
                        capabilities = capsField.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        dispatchable = dispatchableBox.isChecked,
                        knownLimits = limitsField.text.toString().trim().ifBlank { null },
                        existing = existing,
                    )
                    when (built) {
                        is PlatformAgentForm.Result.Rejected -> {
                            toast(built.message)
                            return@setOnClickListener
                        }
                        is PlatformAgentForm.Result.Ok -> {
                            val record = built.record
                            try {
                                val typedToken = platformTokenField.text.toString()
                                if (typedToken.isNotBlank()) {
                                    platformCredentialStore.savePlatformToken(typedToken)
                                }
                                val typedSecret = webhookSecretField.text.toString()
                                if (typedSecret.isNotBlank()) {
                                    platformCredentialStore.saveWebhookSecret(finalId, typedSecret)
                                }
                                agentStore.saveAgent(record)
                            } catch (e: Exception) {
                                toast("保存失败：${e.message}")
                                return@setOnClickListener
                            }
                            dialog.dismiss()
                            if (renamed) toast("id 已存在，已改为 $finalId。")
                            val tokenNote = if (platformTokenField.text.toString().isNotBlank() ||
                                platformCredentialStore.loadPlatformToken() != null
                            ) {
                                "走平台通道对话"
                            } else {
                                "平台凭证未填：先保存了记录，对话前请在表单里填入 App 的平台凭证"
                            }
                            toast("已保存 Agent：$name（平台 Agent，$tokenNote）")
                            refreshAgents()
                            return@setOnClickListener
                        }
                    }
                }
                // 云端 Agent（V5，2026-11-11）：记录由 CloudAgentForm
                // 纯逻辑拼，不建大脑 stub、不走通用大脑——对方把 App 加
                // 为自定义 MCP 连接器、自己来领单。钥匙保存时现生成、
                // 只存本机 Keystore；保存后弹出设置说明（连接地址 +
                // 钥匙 + 各 App 设置）。
                if (isCloudAgent()) {
                    var finalId = existing?.agentId ?: slugAgentId(name)
                    if (existing == null && agentStore.getAgent(finalId) != null) {
                        var suffix = 2
                        while (agentStore.getAgent("$finalId-$suffix") != null) suffix += 1
                        finalId = "$finalId-$suffix"
                        toast("id 已存在，已改为 $finalId。")
                    }
                    val roles = mutableListOf<String>()
                    if (roleDevBox.isChecked) roles += CloudAgent.ROLE_DEVELOP
                    if (roleReviewBox.isChecked) roles += CloudAgent.ROLE_REVIEW
                    if (roleDeliverBox.isChecked) roles += CloudAgent.ROLE_DELIVER
                    val homeDevice = if (cloudHomeDevice.isChecked) {
                        remoteDevices.getOrNull(cloudHomeSpinner.selectedItemPosition)?.deviceId
                    } else {
                        null
                    }
                    val built = CloudAgentForm.buildRecord(
                        name = name,
                        agentId = finalId,
                        cloudHomeDeviceId = homeDevice,
                        roles = roles,
                        canDispatch = canDispatchBox.isChecked,
                        keyRef = CloudAgentCredentialStore.keyRefFor(finalId),
                        capabilities = capsField.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        dispatchable = dispatchableBox.isChecked,
                        knownLimits = limitsField.text.toString().trim().ifBlank { null },
                        existing = existing,
                    )
                    when (built) {
                        is CloudAgentForm.Result.Rejected -> {
                            toast(built.message)
                            return@setOnClickListener
                        }
                        is CloudAgentForm.Result.Ok -> {
                            val record = built.record
                            try {
                                var key = cloudCredentialStore.loadKey(finalId)
                                if (key == null) {
                                    key = CloudAgentCredentialStore.generateKey()
                                    cloudCredentialStore.saveKey(finalId, key)
                                }
                                agentStore.saveAgent(record)
                                dialog.dismiss()
                                toast("已保存 Agent：$name（云端 Agent，钥匙已生成并只存本机 Keystore）")
                                refreshAgents()
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("设置说明：$name")
                                    .setMessage(
                                        CloudAgentSetupSheet.build(
                                            record,
                                            key,
                                            CloudAgentMcpEndpoint.publicUrl(),
                                        ),
                                    )
                                    .setPositiveButton("知道了", null)
                                    .show()
                            } catch (e: Exception) {
                                toast("保存失败：${e.message}")
                                return@setOnClickListener
                            }
                            return@setOnClickListener
                        }
                    }
                }
                val baseUrl = baseUrlField.text.toString().trim().ifBlank { null }
                val rawModel = modelField.text.toString().trim().ifBlank { null }
                // Manus（云端任务型）：服务地址留空默认 https://api.manus.ai；
                // 模型栏填的是任务档位（standard / lite / max），不是模型 ID——
                // "Manus 2.0 Lite" 映射为 lite；未知值拒绝保存，不原样传入。
                if (kind == BrainKind.MODEL_API && manusBox.isChecked && rawModel != null &&
                    !ManusTaskAdapter.isKnownManusProfileInput(rawModel)
                ) {
                    toast("Manus 档位只能填 standard / lite / max（“Manus 2.0 Lite”填 lite）。")
                    return@setOnClickListener
                }
                val resolvedBaseUrl = if (kind == BrainKind.MODEL_API && manusBox.isChecked && baseUrl == null) {
                    ManusTaskAdapter.DEFAULT_BASE_URL
                } else {
                    baseUrl
                }
                val model = if (kind == BrainKind.MODEL_API && manusBox.isChecked) {
                    ManusTaskAdapter.agentProfileFor(rawModel) ?: "standard"
                } else {
                    rawModel
                }
                val adapterId = adapterField.text.toString().trim().ifBlank {
                    when (kind) {
                        BrainKind.MODEL_API -> if (manusBox.isChecked) "manus" else "deepseek"
                        BrainKind.SUBSCRIPTION -> "subscription"
                        BrainKind.AGENT_RUNTIME -> binaryField.text.toString().trim().ifBlank { "cli" }
                        // 平台/云端 Agent 在上面已提前保存、到不了这里；
                        // 分支只为让 when 穷尽（BrainKind 新增的两项）。
                        BrainKind.PLATFORM_AGENT -> PlatformAgent.ADAPTER_ID
                        BrainKind.CLOUD_AGENT -> CloudAgent.ADAPTER_ID
                    }
                }
                val binary = binaryField.text.toString().trim().ifBlank { null }
                val authRef = authRefField.text.toString().trim().ifBlank { null }
                val secret = secretField.text.toString()
                if (secret.isNotBlank() && authRef == null) {
                    toast("填了密钥值时必须填密钥引用名。")
                    return@setOnClickListener
                }
                val agentKind = when (kind) {
                    BrainKind.MODEL_API -> if (baseUrl != null && AgentRecord.isLocalModelUrl(baseUrl)) AgentKind.LOCAL_MODEL else AgentKind.CLOUD
                    BrainKind.SUBSCRIPTION -> AgentKind.CLOUD
                    BrainKind.AGENT_RUNTIME -> AgentKind.DEVICE
                    BrainKind.PLATFORM_AGENT -> AgentKind.CLOUD
                    BrainKind.CLOUD_AGENT -> AgentKind.CLOUD
                }
                val access = when (kind) {
                    BrainKind.MODEL_API -> AgentAccess.API
                    BrainKind.SUBSCRIPTION -> AgentAccess.APP
                    BrainKind.AGENT_RUNTIME -> AgentAccess.CLI
                    BrainKind.PLATFORM_AGENT -> AgentAccess.WEBHOOK
                    BrainKind.CLOUD_AGENT -> AgentAccess.CONNECTOR
                }
                val agentId = existing?.agentId ?: slugAgentId(name)
                val record = AgentRecord(
                    agentId = agentId,
                    displayName = name,
                    kind = agentKind,
                    access = access,
                    brainKind = kind,
                    adapterId = adapterId,
                    binary = binary,
                    baseUrl = resolvedBaseUrl,
                    model = model,
                    authRef = authRef,
                    capabilities = capsField.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    quotaRule = existing?.quotaRule ?: AgentRecord.defaultQuotaRule(agentKind),
                    dispatchable = dispatchableBox.isChecked,
                    // 开关（2026-11-12）：编辑不重置开关——停用的改完仍是停用。
                    enabled = existing?.enabled ?: true,
                    knownLimits = limitsField.text.toString().trim().ifBlank { null },
                    addHint = existing?.addHint,
                    loginState = existing?.loginState ?: wherevercode.agent.core.agent.AgentLoginState.UNKNOWN,
                    lastTestedAtEpochSeconds = existing?.lastTestedAtEpochSeconds,
                    lastTestSummary = existing?.lastTestSummary,
                    createdAtEpochSeconds = existing?.createdAtEpochSeconds ?: 0,
                    updatedAtEpochSeconds = existing?.updatedAtEpochSeconds ?: 0,
                )
                val errors = record.validate()
                if (errors.isNotEmpty()) {
                    toast("保存失败：${errors.joinToString("；")}")
                    return@setOnClickListener
                }
                // 添加 Agent → 大脑接线（V5 紧急开发，2026-11-11）：表单以前
                // 只存 AgentRecord，不建 BrainConfig，聊天路由找不到配置，
                // Agent 永远“未接入”。MODEL_API（非 Manus）在此顺带配好
                // 通用大脑，保存后就能直接对话；编辑时已接好的线不动
                // （已能走通的既有大脑不拆），只修不通的。
                val components = brainComponents
                val configs = components?.configStore?.listConfigs().orEmpty()
                val isNewAgent = existing == null
                var dedicatedKeyNote: String? = null
                val genericBrain: BrainConfig? = when {
                    kind != BrainKind.MODEL_API -> null
                    AgentBrainWiring.isManus(record) -> null
                    !isNewAgent && components != null &&
                        AgentChatRouter.resolve(
                            record, configs, components::isBrainConfigured,
                            isRemoteAgent = ::isRemoteAgent,
                            isManusConfigured = ::isManusConfigured,
                            isPlatformAgentConfigured = ::isPlatformAgentConfigured,
                        ) is AgentChatRoute.UseBrain -> {
                        if (AgentSecretEdit.shouldWriteSecret(secret)) {
                            dedicatedKeyNote = "对话走设置里已接好的大脑，表单里新填的 Key 未生效（要换 Key 请去设置里改对应大脑）"
                        }
                        null
                    }
                    components == null -> {
                        toast("大脑还在加载，请稍候再保存。")
                        return@setOnClickListener
                    }
                    else -> AgentBrainWiring.brainConfigFor(record, configs)
                }
                val wired = genericBrain?.let { AgentBrainWiring.wiredRecord(record, it) } ?: record
                // 密钥：新填的写到通用大脑的引用名下；没新填时，编辑旧记录
                // 且旧引用名下已有 Key 的，把旧 Key 迁到通用引用名下；
                // 两者都没 Key 且不是本机模型时拒绝保存（无 Key 的通用大脑
                // 发不出消息，存了也是坏的）。
                var keyToStore: String? = null
                if (genericBrain != null) {
                    if (AgentSecretEdit.shouldWriteSecret(secret)) {
                        keyToStore = secret
                    } else if (existing?.authRef != null) {
                        keyToStore = try {
                            brainKeyStore.getSecret(existing.authRef)?.takeIf { it.isNotBlank() }
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (keyToStore == null && AgentBrainWiring.requiresKey(record)) {
                        toast("保存失败：模型 API 需要填写密钥值（只存本机 Keystore）。本机模型可不填。")
                        return@setOnClickListener
                    }
                }
                // CLI Agent：全局 CLI 大脑（cli-membership）只有一个，
                // 保存时若还没有就按这次填的二进制建一个 stub（登录态仍是
                // 未验证，对话前须在设置里点“验证一次”）；已接了另一个
                // 二进制时不动它，只如实提示。
                var cliNote: String? = null
                if (kind == BrainKind.AGENT_RUNTIME) {
                    if (components == null) {
                        toast("大脑还在加载，请稍候再保存。")
                        return@setOnClickListener
                    }
                    val cliConfig = components.configStore.getConfig(BrainBootstrap.CLI_BRAIN_ID)
                    when {
                        cliConfig == null -> {
                            val stub = BrainConfig(
                                brainId = BrainBootstrap.CLI_BRAIN_ID,
                                kind = BrainKind.SUBSCRIPTION,
                                adapterId = BrainBootstrap.CLI_BRAIN_ID,
                                label = "CLI 会员额度",
                                baseUrl = null,
                                model = "",
                                authRef = null,
                                cliBinary = binary,
                            )
                            components.configStore.saveConfig(stub)
                            BrainAdapterRegistry.registerConfig(stub)
                            cliNote = "CLI 需先在设置里点“验证一次”才能对话"
                        }
                        cliConfig.cliBinary != binary ->
                            cliNote = "CLI 大脑当前接的是 ${cliConfig.cliBinary}，不是 $binary，请先在设置里切换 CLI 再对话"
                        cliConfig.loginState != AgentLoginState.LOGGED_IN ->
                            cliNote = "CLI 需先在设置里点“验证一次”才能对话"
                    }
                }
                // 订阅 Agent：走 SIWC ChatGPT；没登录时如实提示，不造假。
                var subNote: String? = null
                if (kind == BrainKind.SUBSCRIPTION && components != null &&
                    !components.isBrainConfigured(BrainBootstrap.SIWC_BRAIN_ID)
                ) {
                    subNote = "订阅 Agent 需先在设置里完成 ChatGPT 登录才能对话"
                }
                // 先存大脑与 Key，再存记录：大脑存失败时记录也不存，
                // 不留“记录在、大脑不在”的半截状态。
                try {
                    if (genericBrain != null) {
                        val key = keyToStore
                        if (key != null) {
                            genericBrain.authRef?.let { brainKeyStore.putSecret(it, key) }
                        }
                        components?.configStore?.saveConfig(genericBrain)
                        BrainAdapterRegistry.registerConfig(genericBrain)
                    } else if (AgentSecretEdit.shouldWriteSecret(secret) && authRef != null) {
                        brainKeyStore.putSecret(authRef, secret)
                    }
                } catch (e: Exception) {
                    toast("保存失败：${e.message}")
                    return@setOnClickListener
                }
                if (agentStore.getAgent(agentId) == null && existing == null) {
                    // 新建时 id 必须唯一（slug 冲突时加后缀）。
                    var candidate = agentId
                    var suffix = 2
                    while (agentStore.getAgent(candidate) != null) {
                        candidate = "$agentId-$suffix"
                        suffix += 1
                    }
                    if (candidate != agentId) {
                        toast("id 已存在，已改为 $candidate。")
                    }
                    try {
                        agentStore.saveAgent(wired.copy(agentId = candidate))
                    } catch (e: Exception) {
                        toast("保存失败：${e.message}")
                        return@setOnClickListener
                    }
                } else {
                    try {
                        agentStore.saveAgent(wired)
                    } catch (e: Exception) {
                        toast("保存失败：${e.message}")
                        return@setOnClickListener
                    }
                }
                dialog.dismiss()
                val note = when {
                    genericBrain != null -> "（已配好通用大脑，可直接对话）"
                    dedicatedKeyNote != null -> "（$dedicatedKeyNote）"
                    cliNote != null -> "（$cliNote）"
                    subNote != null -> "（$subNote）"
                    else -> ""
                }
                toast("已保存 Agent：$name$note")
                refreshAgents()
            }
        }
        dialog.show()
    }

    private fun slugAgentId(name: String): String {
        // 只留 ASCII 小写字母/数字：AgentRecord.validate 的 id 规则
        // （^[a-z0-9][a-z0-9_-]*$）不接受中文——isLetterOrDigit()
        // 对汉字也返回 true，旧写法给中文名算出的 id 存不进去，
        // 表单会报“Agent id 只能用小写字母、数字、-、_”。
        val slug = name.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "agent" }
        return slug.take(40)
    }

    /**
     * 打开与某 Agent 的独立会话（会话账本 PRIVATE 会话；消息按 Agent
     * 路由，见 [AgentChatRouter]——不再是“消息走大脑”）。
     */
    private fun openAgentSession(agent: AgentRecord) {
        // Agent 开关（2026-11-12）：停用的 Agent 不开新对话——现读
        // 记录（不用传进来的旧对象，开关翻完立即生效）；已打开的会话
        // 由 sendPrompt 的发送守门拦下，不在这里处理。
        val liveEnabled = try {
            agentStore.getAgent(agent.agentId)?.enabled ?: true
        } catch (_: Exception) {
            true
        }
        if (!liveEnabled) {
            toast("该 Agent 已停用：在 Agent 页重新启用后可对话。")
            return
        }
        val components = brainComponents ?: run {
            toast("大脑还在加载，请稍候再打开对话。")
            return
        }
        Thread {
            try {
                val existing = components.sessionLedger.listSessions("user").firstOrNull { session ->
                    session.kind == SessionKind.PRIVATE && agent.agentId in session.members
                }
                val session = existing ?: components.sessionLedger.createSession(
                    name = agent.displayName,
                    kind = SessionKind.PRIVATE,
                    ownerId = "user",
                    members = listOf(agent.agentId),
                )
                val isNew = existing == null
                runOnUiThread {
                    // V5 修复（2026-11-11，新对话也带尾巴）：刚点过新对话
                    // 的干净状态（无会话 id、无轮次）下打开 Agent 会话时
                    // 传 fresh=true——不把该 Agent 缓存的旧轮次（连同
                    // 它的尾巴）带回来，新对话才真正干净。其他情况
                    // （切换会话、打开历史）不传，历史照常恢复。
                    val fresh = chatViewModel.currentSessionId() == null &&
                        chatViewModel.stateNow().turns.isEmpty()
                    if (chatViewModel.switchSession(session.sessionId, agent.displayName, agent, fresh)) {
                        inConversation = true
                        showEntry(DrawerController.ID_NEW_CONVERSATION)
                        if (isNew) {
                            // 阶段 A：提示必须与实际路由一致。未接入的目标如实
                            // 写“未接入”，不写“消息走大脑”。
                            val route = AgentChatRouter.resolve(
                                agent,
                                components.configStore.listConfigs(),
                                components::isBrainConfigured,
                                isRemoteAgent = ::isRemoteAgent,
                                isManusConfigured = ::isManusConfigured,
                                isPlatformAgentConfigured = ::isPlatformAgentConfigured,
                            )
                            // 阶段 B：远端 Agent 的任务由远端泵发到设备上执行，
                            // 不再是“等它认领（跨设备执行待 P2）”。
                            // 阶段 C：Manus 的任务型指令直接在聊天里创建
                            // Manus 任务执行，任务页指派暂不支持 Manus 认领。
                            val taskNote = if (route is AgentChatRoute.UseRemoteAgent) {
                                "指派给它的任务会进入任务页 queued，由远端泵发到设备上执行。"
                            } else if (route is AgentChatRoute.UseManus) {
                                "聊天里的任务型指令会直接创建 Manus 任务执行；任务页指派暂不支持 Manus 认领。"
                            } else if (route is AgentChatRoute.UsePlatformAgent) {
                                "聊天里的任务型指令会直接发给它执行；任务页指派暂不支持平台 Agent 认领。"
                            } else if (CloudAgent.isCloudAgentRecord(agent)) {
                                "任务页指派暂不支持云端 Agent：它要把 App 加为自定义 MCP 连接器、自己来领单，且 App 的 MCP 端点尚未开通。"
                            } else {
                                "指派给它的任务会进入任务页 queued，等它认领（跨设备执行待 P2）。"
                            }
                            chatViewModel.note(
                                AgentChatRouter.describeRoute(agent, route) + taskNote,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("打开对话失败：${e.message}") }
            }
        }.start()
    }

    /** 指派任务给某 Agent：建 queued 任务行并写 assigned_agent_id；本地不代执行。 */
    private fun assignTaskToAgent(agent: AgentRecord) {
        if (!agent.dispatchable) {
            toast("该 Agent 不可派单（dispatchable=false），只对话、不接任务。")
            return
        }
        val components = brainComponents ?: run {
            toast("大脑还在加载，请稍候再指派。")
            return
        }
        val input = EditText(this).apply {
            hint = "任务目标，如：写一个 calculator.py 并跑测试"
            setSingleLine(false)
            minLines = 3
        }
        AlertDialog.Builder(this)
            .setTitle("指派任务给 ${agent.displayName}")
            .setMessage(
                if (agent.deviceId != null) {
                    "任务进入 queued，远端泵会发到设备上执行；本地执行器不代执行。"
                } else {
                    "任务进入 queued 等 ${agent.displayName} 认领；本地执行器不代执行（跨设备执行待 P2）。"
                },
            )
            .setView(input)
            .setPositiveButton("指派") { _, _ ->
                val goal = input.text.toString().trim()
                if (goal.isBlank()) {
                    toast("任务目标不能为空。")
                    return@setPositiveButton
                }
                Thread {
                    try {
                        val now = System.currentTimeMillis() / 1000
                        val record = TaskRecord(
                            taskId = UUID.randomUUID().toString(),
                            title = goal.take(40),
                            description = goal,
                            state = "queued",
                            // V5 跨设备开发（2026-11-12，A1）：手动指派
                            // 同样带当前聊天绑定作 projectId。
                            projectId = currentChatProjectId(),
                            assignedAgentId = agent.agentId,
                            createdAt = now,
                            updatedAt = now,
                            version = 1,
                        )
                        components.taskStore.createTask(record)
                        runOnUiThread {
                            toast("已指派给 ${agent.displayName}，进入 queued 等认领。")
                            refreshTasks()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast("指派失败：${e.message}") }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 测试某 Agent 的连接（结果写回记录：登录态 + 摘要 + 时间）。 */
    private fun testAgentConnection(agent: AgentRecord) {
        toast("正在测试 ${agent.displayName} 的连接…")
        Thread {
            try {
                val updated = agentTester.testAndUpdate(agent) { ref ->
                    ref?.let { brainKeyStore.getSecret(it) }
                }
                agentStore.saveAgent(updated)
                runOnUiThread {
                    toast(updated.lastTestSummary ?: "测试完成。")
                    refreshAgents()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("测试失败：${e.message}") }
            }
        }.start()
    }

    /**
     * V5 修复（2026-11-11，能用的 Agent 显示“未验证”）：一次真实
     * 往返成功后把该 Agent 标成已验证。Agent 真回了消息是比 hello
     * 探测更强的可用证据，状态标签必须反映对方的真实情况；已是
     * LOGGED_IN 时不动（不重写摘要、不刷时间）。后台读最新记录再
     * 存、UI 刷新，与 testAgentConnection 同款模式；标验证失败不
     * 打断聊天（状态标签下次打开 Agent 页时仍按旧值显示，不伪造、
     * 不报错打扰对话）。
     */
    private fun markAgentVerifiedByRealRound(agent: AgentRecord) {
        if (agent.loginState == AgentLoginState.LOGGED_IN) return
        Thread {
            try {
                val current = agentStore.getAgent(agent.agentId) ?: agent
                if (current.loginState == AgentLoginState.LOGGED_IN) return@Thread
                agentStore.saveAgent(
                    current.copy(
                        loginState = AgentLoginState.LOGGED_IN,
                        lastTestSummary = "认证通过：${current.displayName} 已在对话中真实回过消息（真实往返成功，非 hello 探测）。",
                    ),
                )
                runOnUiThread { refreshAgents() }
            } catch (_: Exception) {
                // 标验证失败不打断聊天，见上。
            }
        }.start()
    }

    /**
     * 远端设备 Agent 探针（V5 修复，2026-11-11）：问设备上的 Hand，
     * 不问本机沙箱。hello 握手只交换公钥与 Agent 清单，不含秘密、
     * 不执行命令；复用加设备时的同一条握手（probeAndAddRemoteDevice
     * 用 fetchHelloUnverified 首探，本处设备已在册，用 fetchHello
     * 再核对 device_id 一致，防串话）。在 testAgentConnection 的
     * 后台线程里跑，runBlocking 在此线程内是安全的（与
     * probeAndAddRemoteDevice 同模式）。设备未添加/未配对时如实
     * 报未添加/未配对，绝不回落本机沙箱测试。
     */
    private fun probeRemoteDeviceAgent(record: AgentRecord): DeviceProbeResult {
        val deviceId = record.deviceId
            ?: return DeviceProbeResult.Unreachable("该 Agent 没有归属设备 id，无法做远端探测。")
        val device = try {
            remoteDeviceStore.getDevice(deviceId)
        } catch (_: Exception) {
            null
        } ?: return DeviceProbeResult.Unreachable("设备 $deviceId 未添加：请先在设备页添加该设备。")
        // V5 修复（2026-11-11）：配对读取与对话通道同一顺序（持久库
        // 优先，再读迁移库），见 loadPairingRecord。
        val pairing = loadPairingRecord(
            deviceId,
            brainComponents?.pairingTrustStore,
            pairingStore,
        )
        if (pairing == null || pairing.state != PairingState.CONFIRMED) {
            return DeviceProbeResult.Unreachable("设备 ${device.displayName} 未配对：请先在设备页完成配对，配对后才能测试。")
        }
        val peer = device.toDiscoveredPeer(pairing.fingerprintSha256)
        val identity = try {
            deviceIdentityStore.getOrCreate()
        } catch (e: Exception) {
            return DeviceProbeResult.Unreachable("本机设备身份不可用：${e.message ?: "未知错误"}。")
        }
        val hello = try {
            runBlocking { directTransport.fetchHello(peer, identity.deviceId, identity.publicKey) }
        } catch (e: Exception) {
            return DeviceProbeResult.Unreachable("与 ${device.displayName} 的 hello 握手失败：${e.message ?: "未知错误"}。")
        }
        // P1 能力卡：hello 成功，存仓库快照。
        noteHelloForCapabilityCards(hello)
        // V5 修复（2026-11-11，hello 通、run 就 reset）：本机在远端
        // 的配对状态是 PENDING 时，hello 通、但 run 调用会被服务端
        // 拒绝。不能因为 hello 通就报 Available，必须单独报待批准，
        // 否则用户看到“远端可用”，一发消息就 reset。"" 是旧版服务端
        // 没给状态，不知道——按老样子走（不挡路，由调用时的明确
        // 错误再报）。
        if (hello.clientStatus == "PENDING") {
            return DeviceProbeResult.PendingApproval(device.displayName)
        }
        val info = hello.agents.firstOrNull { it.agentId == record.agentId }
            ?: return DeviceProbeResult.AgentMissing(device.displayName)
        if (!info.available) {
            return DeviceProbeResult.AgentUnavailable(device.displayName, info.folder)
        }
        return DeviceProbeResult.Available(device.displayName, info.displayName, info.folder)
    }

    private fun removeAgent(agent: AgentRecord) {
        AlertDialog.Builder(this)
            .setTitle("移除 Agent")
            .setMessage("确定移除 ${agent.displayName} 吗？它的独立会话与已指派任务不受影响。")
            .setPositiveButton("移除") { _, _ ->
                Thread {
                    val ref = agent.authRef
                    agentStore.deleteAgent(agent.agentId)
                    // 密钥清理：没有其他 Agent 记录与大脑配置再用该引用时才删 Keystore 值。
                    if (ref != null) {
                        val stillUsed = agentStore.listAgents().any { it.authRef == ref } ||
                            (brainComponents?.configStore?.listConfigs()?.any { it.authRef == ref } ?: false)
                        if (!stillUsed) {
                            try {
                                brainKeyStore.deleteSecret(ref)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    runOnUiThread {
                        toast("已移除 ${agent.displayName}。")
                        refreshAgents()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deviceLines(): List<String> {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignored = pm.isIgnoringBatteryOptimizations(packageName)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        return listOf(
            "型号：${Build.MANUFACTURER} ${Build.MODEL}",
            "Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
            "ABI：${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}",
            "应用：$packageName · $version",
            "电池优化：${if (ignored) "已忽略（好）" else "未忽略；K70 还需自启动 + 省电策略“无限制”"}",
            "前台服务：已请求启动；Android 不承诺进程永久存活",
        )
    }

    // ------------------------------------------------------------------
    // Settings / brains (reused production configuration paths)
    // ------------------------------------------------------------------

    private fun bindSettings() {
        brainStatus = screenSettings.findViewById(R.id.brainStatus)
        brainDisplayNameView = screenSettings.findViewById(R.id.brainDisplayName)
        brainDisplayNameView.text = brainDisplayName()
        screenSettings.findViewById<View>(R.id.brainNameRow).setOnClickListener { showBrainNameDialog() }
        buttonSignInChatGpt = screenSettings.findViewById(R.id.buttonSignInChatGpt)
        buttonSignOut = screenSettings.findViewById(R.id.buttonSignOut)
        cloudflareStatus = screenSettings.findViewById(R.id.cloudflareStatus)
        cfAccountId = screenSettings.findViewById(R.id.cfAccountId)
        cfApiToken = screenSettings.findViewById(R.id.cfApiToken)
        cfModel = screenSettings.findViewById(R.id.cfModel)
        deepseekStatus = screenSettings.findViewById(R.id.deepseekStatus)
        dsBaseUrl = screenSettings.findViewById(R.id.dsBaseUrl)
        dsModel = screenSettings.findViewById(R.id.dsModel)
        dsApiKey = screenSettings.findViewById(R.id.dsApiKey)
        deepseekModels = screenSettings.findViewById(R.id.deepseekModels)
        // Grok / Muse Spark / Claude 设置节（2026-11-12，大脑登陆方式补全）。
        grokStatus = screenSettings.findViewById(R.id.grokStatus)
        grokBaseUrl = screenSettings.findViewById(R.id.grokBaseUrl)
        grokModel = screenSettings.findViewById(R.id.grokModel)
        grokApiKey = screenSettings.findViewById(R.id.grokApiKey)
        grokModels = screenSettings.findViewById(R.id.grokModels)
        museStatus = screenSettings.findViewById(R.id.museStatus)
        museBaseUrl = screenSettings.findViewById(R.id.museBaseUrl)
        museModel = screenSettings.findViewById(R.id.museModel)
        museApiKey = screenSettings.findViewById(R.id.museApiKey)
        museModels = screenSettings.findViewById(R.id.museModels)
        claudeStatus = screenSettings.findViewById(R.id.claudeStatus)
        claudeBaseUrl = screenSettings.findViewById(R.id.claudeBaseUrl)
        claudeModel = screenSettings.findViewById(R.id.claudeModel)
        claudeApiKey = screenSettings.findViewById(R.id.claudeApiKey)
        claudeModels = screenSettings.findViewById(R.id.claudeModels)
        // V5 C3-C：大脑多模式登陆的新行（文案集中在 BrainLoginRows）。
        brainListContainer = screenSettings.findViewById(R.id.brainListContainer)
        siwcModelStatus = screenSettings.findViewById(R.id.siwcModelStatus)
        buttonRefreshSiwcModels = screenSettings.findViewById(R.id.buttonRefreshSiwcModels)
        buttonPickSiwcModel = screenSettings.findViewById(R.id.buttonPickSiwcModel)
        buttonSwitchChatGptAccount =
            screenSettings.findViewById(R.id.buttonSwitchChatGptAccount)
        buttonCancelSignIn = screenSettings.findViewById(R.id.buttonCancelSignIn)
        genericBrainLabel = screenSettings.findViewById(R.id.genericBrainLabel)
        genericBaseUrl = screenSettings.findViewById(R.id.genericBaseUrl)
        genericModel = screenSettings.findViewById(R.id.genericModel)
        genericApiKey = screenSettings.findViewById(R.id.genericApiKey)
        genericModels = screenSettings.findViewById(R.id.genericModels)
        cliBrainSection = screenSettings.findViewById(R.id.cliBrainSection)
        cliBrainStatus = screenSettings.findViewById(R.id.cliBrainStatus)
        cliBrainBinary = screenSettings.findViewById(R.id.cliBrainBinary)
        cliBrainNote = screenSettings.findViewById(R.id.cliBrainNote)
        buttonVerifyCli = screenSettings.findViewById(R.id.buttonVerifyCli)
        buttonVerifyCli.text = BrainLoginRows.CLI_VERIFY_BUTTON_TEXT
        cliBrainNote.text = BrainLoginRows.CLI_VERIFY_NOTE
        workspacePath = screenSettings.findViewById(R.id.workspacePath)
        workspaceAdvancedDetails = screenSettings.findViewById(R.id.workspaceAdvancedDetails)
        buttonAdvancedInfo = screenSettings.findViewById(R.id.buttonAdvancedInfo)
        aboutVersion = screenSettings.findViewById(R.id.aboutVersion)
        buttonAdvancedInfo.setOnClickListener {
            val expanded = workspaceAdvancedDetails.visibility != View.VISIBLE
            workspaceAdvancedDetails.visibility = if (expanded) View.VISIBLE else View.GONE
            buttonAdvancedInfo.text = if (expanded) "收起高级信息" else "高级信息"
        }

        screenSettings.findViewById<View>(R.id.buttonSettingsBack).setOnClickListener { hideSettings() }
        buttonSignInChatGpt.isEnabled = false
        buttonSignInChatGpt.setOnClickListener { signInWithChatGpt() }
        buttonSignOut.setOnClickListener { signOut() }
        screenSettings.findViewById<View>(R.id.buttonSaveCloudflare).setOnClickListener { saveCloudflare() }
        screenSettings.findViewById<View>(R.id.buttonClearCloudflare).setOnClickListener { clearCloudflare() }
        screenSettings.findViewById<View>(R.id.buttonSaveDeepSeek).setOnClickListener { saveDeepSeek() }
        screenSettings.findViewById<View>(R.id.buttonClearDeepSeek).setOnClickListener { clearDeepSeek() }
        screenSettings.findViewById<View>(R.id.buttonRefreshDeepSeekModels).setOnClickListener {
            refreshDeepSeekModels()
        }
        // Grok / Muse Spark / Claude 设置节（2026-11-12，大脑登陆方式补全）。
        screenSettings.findViewById<View>(R.id.buttonSaveGrok).setOnClickListener { saveGrok() }
        screenSettings.findViewById<View>(R.id.buttonClearGrok).setOnClickListener { clearGrok() }
        screenSettings.findViewById<View>(R.id.buttonRefreshGrokModels).setOnClickListener {
            refreshGrokModels()
        }
        screenSettings.findViewById<View>(R.id.buttonSaveMuse).setOnClickListener { saveMuse() }
        screenSettings.findViewById<View>(R.id.buttonClearMuse).setOnClickListener { clearMuse() }
        screenSettings.findViewById<View>(R.id.buttonRefreshMuseModels).setOnClickListener {
            refreshMuseModels()
        }
        screenSettings.findViewById<View>(R.id.buttonSaveClaude).setOnClickListener { saveClaude() }
        screenSettings.findViewById<View>(R.id.buttonClearClaude).setOnClickListener { clearClaude() }
        screenSettings.findViewById<View>(R.id.buttonRefreshClaudeModels).setOnClickListener {
            refreshClaudeModels()
        }
        // V5 C3-C：大脑多模式登陆的新入口（只在用户点按时动作）。
        buttonSwitchChatGptAccount.setOnClickListener { switchChatGptAccount() }
        buttonCancelSignIn.setOnClickListener { cancelChatGptSignIn() }
        buttonRefreshSiwcModels.setOnClickListener { refreshSiwcModels() }
        buttonPickSiwcModel.setOnClickListener { pickSiwcModel() }
        screenSettings.findViewById<View>(R.id.buttonTestGenericModels).setOnClickListener {
            testGenericModels()
        }
        screenSettings.findViewById<View>(R.id.buttonSaveGenericBrain).setOnClickListener {
            saveGenericBrain()
        }
        buttonVerifyCli.setOnClickListener { verifyCliBrain() }
        // 设置清理 2（2026-11-11，Claude 极简标准）：Cloudflare /
        // DeepSeek / Grok / Muse Spark / Claude / 通用表单 / CLI 七节收到“高级配置”折叠里，默认不
        // 展开；设置页首屏只留干净的大脑切换器。
        brainAdvancedContainer = screenSettings.findViewById(R.id.brainAdvancedContainer)
        buttonBrainAdvanced = screenSettings.findViewById(R.id.buttonBrainAdvanced)
        buttonBrainAdvanced.setOnClickListener {
            val expanded = brainAdvancedContainer.visibility != View.VISIBLE
            brainAdvancedContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            buttonBrainAdvanced.text = if (expanded) "收起高级配置" else "高级配置"
        }
        // V5 应用更新（2026-11-11）：设置页“关于”分节的更新区（检查更新 /
        // 下载并安装 / 连接 Google Drive / 设置客户端 ID）。
        appUpdateSettings = AppUpdateSettings.create(
            activity = this,
            note = ::note,
            openAuthUrl = { url ->
                CustomTabsIntent.Builder().build()
                    .launchUrl(this, Uri.parse(url))
            },
        )
        appUpdateSettings.bind(screenSettings)
        // git 家授权（2026-11-11）：设置页每站一行“授权”——填/换 token、
        // 清除、只显示“已配/未配”，绝不显示 token 值。token 只存本机 Keystore。
        gitHostAuthRows.clear()
        gitHostAuthRows += GitHostAuthRow(
            GitHost.GITHUB,
            screenSettings.findViewById(R.id.githubAuthStatus),
            screenSettings.findViewById(R.id.githubTokenInput),
            screenSettings.findViewById(R.id.buttonSaveGithubToken),
            screenSettings.findViewById(R.id.buttonClearGithubToken),
        )
        gitHostAuthRows += GitHostAuthRow(
            GitHost.GITLAB,
            screenSettings.findViewById(R.id.gitlabAuthStatus),
            screenSettings.findViewById(R.id.gitlabTokenInput),
            screenSettings.findViewById(R.id.buttonSaveGitlabToken),
            screenSettings.findViewById(R.id.buttonClearGitlabToken),
        )
        gitHostAuthRows += GitHostAuthRow(
            GitHost.GITEE,
            screenSettings.findViewById(R.id.giteeAuthStatus),
            screenSettings.findViewById(R.id.giteeTokenInput),
            screenSettings.findViewById(R.id.buttonSaveGiteeToken),
            screenSettings.findViewById(R.id.buttonClearGiteeToken),
        )
        gitHostAuthRows.forEach { row ->
            row.saveButton.setOnClickListener { saveGitHostToken(row) }
            row.clearButton.setOnClickListener { clearGitHostToken(row) }
        }
        refreshGitHostAuthRows()
        // 平台令牌（2026-11-12，maintainer：App 没有 token 生成按钮）：设置页“平台令牌”分节。
        bindPlatformTokenSettings()
        // 自主度（2026-11-11，maintainer 22:43 两种模式）：设置页“自主度”分节。
        bindAutonomySettings()
        // Hand 同步（2026-11-12，maintainer 定：App 更新后自动同步已连接设备的 Hand）。
        bindHandSyncSettings()
    }

    /**
     * 平台令牌设置（2026-11-12，maintainer：App 没有 token 生成按钮）。
     *
     * 设置页“平台令牌”分节：状态行 + 签发对象 + 名称 + “生成 Token”
     * 按钮。签发用 App 自己的平台凭证（Keystore，现取、不缓存），
     * 不用用户再输凭证、不用去终端；签发出的令牌只在弹窗里显示一次，
     * 带“复制”按钮，关闭后不再显示、不存任何地方。
     */
    private fun bindPlatformTokenSettings() {
        platformTokenStatus = screenSettings.findViewById(R.id.platformTokenStatus)
        platformTokenAgentId = screenSettings.findViewById(R.id.platformTokenAgentId)
        platformTokenName = screenSettings.findViewById(R.id.platformTokenName)
        if (platformTokenAgentId.text.isNullOrBlank()) {
            platformTokenAgentId.setText(PlatformTokenIssuer.DEFAULT_AGENT_ID)
        }
        if (platformTokenName.text.isNullOrBlank()) {
            platformTokenName.setText(PlatformTokenIssuer.DEFAULT_NAME)
        }
        refreshPlatformTokenStatus()
        screenSettings.findViewById<View>(R.id.buttonGeneratePlatformToken)
            .setOnClickListener { generatePlatformToken() }
    }

    /** 平台令牌分节的状态行：有没有平台凭证、签发走哪个端点。 */
    private fun refreshPlatformTokenStatus() {
        val endpoint = platformTokenEndpoint()
        platformTokenStatus.text = if (endpoint == null) {
            "平台：未连接（请先在平台 Agent 设置里填写平台凭证）"
        } else {
            "平台：已连接（$endpoint）"
        }
    }

    /**
     * 令牌签发用的平台端点：须有 App 的平台凭证；主机取第一条有平台
     * 地址的平台 Agent 记录；公开版没有预置默认服务端点。
     * 没配平台凭证时返回 null——按钮只给提示，不假装能签发。
     */
    private fun platformTokenEndpoint(): String? {
        // 凭证存取失败时（如 Robolectric 里 AndroidKeyStore 不可用）当未连接，
        // 不让设置页初始化崩掉；真机上 Keystore 可用，行为不变。
        val token = try {
            platformCredentialStore.loadPlatformToken()
        } catch (_: Exception) {
            null
        }
        if (token.isNullOrBlank()) return null
        val records = try {
            agentStore.listAgents()
        } catch (_: Exception) {
            emptyList()
        }.filter { PlatformAgent.isPlatformAgentRecord(it) }
        val host = records.firstOrNull { !it.platformHost.isNullOrBlank() }
            ?.platformHost?.trim()
            ?: PlatformAgent.DEFAULT_PLATFORM_HOST
        if (host.isBlank()) return null
        return PlatformAgent.mcpEndpoint(host).takeIf { it.isNotBlank() }
    }

    /** 点“生成 Token”：后台调平台签发，成功弹一次令牌，失败只报原因。 */
    private fun generatePlatformToken() {
        val endpoint = platformTokenEndpoint()
        if (endpoint == null) {
            toast("未连接平台：请先在平台 Agent 设置里填写平台凭证。")
            return
        }
        val agentId = platformTokenAgentId.text?.toString().orEmpty()
        val name = platformTokenName.text?.toString().orEmpty()
        toast("正在签发令牌…")
        Thread {
            val transport = PlatformChannelMcpTransport(
                tokenProvider = { platformCredentialStore.loadPlatformToken() },
                endpoint = endpoint,
            )
            val issuer = PlatformTokenIssuer { toolName, args ->
                transport.callTool(toolName, args)
            }
            val result = issuer.issueToken(agentId, name)
            runOnUiThread {
                when (result) {
                    is IssueTokenResult.Issued -> showIssuedTokenDialog(result)
                    is IssueTokenResult.Failed -> toast(result.message)
                }
            }
        }.start()
    }

    /**
     * 签发出的令牌只显示一次：弹窗里显示令牌 + “复制”按钮；关闭后
     * 不再显示，也不在任何地方存它（不进 Keystore、不进记录、不进
     * 日志）。令牌值只出现在这一个对话框里。
     */
    private fun showIssuedTokenDialog(issued: IssueTokenResult.Issued) {
        AlertDialog.Builder(this)
            .setTitle("令牌已签发（只显示一次）")
            .setMessage(
                "Agent：${issued.agentId}\n名称：${issued.name}\n\n${issued.token}\n\n" +
                    "马上复制收好：关闭后这里不再显示，服务端也查不回原文，" +
                    "丢了只能重新签发。",
            )
            .setPositiveButton("复制") { _, _ -> copyToClipboard(issued.token) }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 自主度设置行（V5-UI.md §4 三种独立控制）。
     *
     * 四行全是可见入口（不藏长按）：模式、主动性、执行权限、界面自由度，
     * 点按走 ListDialog 选一项。切换只写 SharedPreferences 档位——
     * 门每次决策实时读档位，切换立刻对进行中的任务生效；已批过的决定
     * 不动（这里绝不调 ApprovalBroker.invalidate()）。
     */
    private fun bindAutonomySettings() {
        autonomyModeRow = screenSettings.findViewById(R.id.autonomyModeRow)
        autonomyInitiativeRow = screenSettings.findViewById(R.id.autonomyInitiativeRow)
        autonomyExecutionRow = screenSettings.findViewById(R.id.autonomyExecutionRow)
        autonomyUiFreedomRow = screenSettings.findViewById(R.id.autonomyUiFreedomRow)
        autonomyModeRow.setOnClickListener { showAutonomyModePicker() }
        autonomyInitiativeRow.setOnClickListener { showAutonomyInitiativePicker() }
        autonomyExecutionRow.setOnClickListener { showAutonomyExecutionPicker() }
        autonomyUiFreedomRow.setOnClickListener { showAutonomyUiFreedomPicker() }
        refreshAutonomyRows()
    }

    /**
     * Hand 同步设置行（2026-11-12，maintainer 定：App 更新后自动同步 Hand）。
     *
     * 两行全是可见入口（不藏长按）：自动同步开关（默认关，点按切换；
     * 开着时更新 Hand 跳过确认框，进度与结果照常显示）、检查全部设备
     * 更新（只检查、不自动更新，报汇总；更新走设备卡的“更新 Hand”）。
     */
    private fun bindHandSyncSettings() {
        val store = SharedPrefsHandSyncSettingsStore(this)
        val autoSyncRow = screenSettings.findViewById<TextView>(R.id.handAutoSyncRow)
        val checkAllRow = screenSettings.findViewById<View>(R.id.handCheckAllRow)
        fun refreshAutoSyncRow() {
            autoSyncRow.text =
                if (store.isAutoSyncEnabled()) "自动同步 Hand：开（点按切换）"
                else "自动同步 Hand：关（点按切换）"
        }
        refreshAutoSyncRow()
        autoSyncRow.setOnClickListener {
            store.setAutoSyncEnabled(!store.isAutoSyncEnabled())
            refreshAutoSyncRow()
            toast(
                if (store.isAutoSyncEnabled()) "已打开自动同步 Hand：以后更新 Hand 不再每次确认"
                else "已关闭自动同步 Hand：每次更新前都会先问你",
            )
        }
        checkAllRow.setOnClickListener {
            val devices = try {
                remoteDeviceStore.listDevices()
            } catch (_: Exception) {
                emptyList()
            }
            if (devices.isEmpty()) {
                toast("没有已连接的设备")
            } else {
                handUpdateController?.checkAllDevices(devices)
            }
        }
    }

    private fun refreshAutonomyRows() {
        val settings = autonomyStore.loadAll()
        val mode = AutonomyPolicy.modeOf(settings.initiative, settings.executionLevel)
        autonomyModeRow.text = "模式：${mode.label}（点按切换）"
        autonomyInitiativeRow.text = "主动性：${settings.initiative.label}（点按切换）"
        autonomyExecutionRow.text = "执行权限：${settings.executionLevel.label}（点按切换）"
        autonomyUiFreedomRow.text = "界面自由度：${settings.uiFreedom.label}（点按切换）"
    }

    private fun showAutonomyModePicker() {
        val modes = AutonomyMode.values()
        ListDialog.show(
            this,
            "切换模式",
            "监督模式：plan 批准、派发确认、审查 verdict 每一道都等你点。全权模式：大脑总管自动批、自动派、自动采纳，返工上限 2 轮不变，超限升级给你；自动决定记审计，可查可打回。切换立刻对进行中的任务生效，已批过的决定不动。",
            modes.map { it.label },
        ) { which ->
            autonomyStore.applyMode(modes[which])
            refreshAutonomyRows()
            toast("已切换到${modes[which].label}。")
        }
    }

    private fun showAutonomyInitiativePicker() {
        val values = Initiative.values()
        ListDialog.show(
            this,
            "主动性",
            "大脑总管多主动：被动响应只答不做；提醒、建议由你决定；授权范围内有限自主与高自主可自动批 plan、自动派发、自动采纳审查 verdict。",
            values.map { "${it.level} · ${it.label}" },
        ) { which ->
            autonomyStore.saveInitiative(values[which])
            refreshAutonomyRows()
        }
    }

    private fun showAutonomyExecutionPicker() {
        val values = ExecutionLevel.values()
        ListDialog.show(
            this,
            "执行权限",
            "写操作按等级审批，只读永远自动：L0 全部写操作都审批；L1 放行本机写文件；L2 放行 git 家写文件与建分支；L3 放行开 PR 与写命令；L4 全部放行。",
            values.map { it.label },
        ) { which ->
            autonomyStore.saveExecutionLevel(values[which])
            refreshAutonomyRows()
        }
    }

    private fun showAutonomyUiFreedomPicker() {
        val values = UiFreedom.values()
        ListDialog.show(
            this,
            "界面自由度",
            "只控制显示复杂度与引导，不参与任何执行决策。",
            values.map { it.label },
        ) { which ->
            autonomyStore.saveUiFreedom(values[which])
            refreshAutonomyRows()
        }
    }

    private fun loadBrainComponents() {
        if (!brainLoading.compareAndSet(false, true)) return
        brainStatus.text = "${brainDisplayName()}：加载中…"
        Thread {
            try {
                // 阶段 B：远端控制接线必须在首次构建前传入（ControlPlane
                // 在 build() 内创建，事后无法再接线）；AgentGraph 已建好
                // 时再次调用直接返回旧 components，接线保持第一次的。
                val components = AgentGraph.getOrBuild(
                    applicationContext,
                    toolApprovalGate,
                    remoteTargetResolver = ::resolveRemoteTargetForMember,
                    remoteControlSender = remoteControlSender,
                    // git 家（2026-11-11）：当前聊天项目的 git 家。
                    gitHostProjectContext = ::resolveChatGitHostProject,
                    // V5 跨设备开发（2026-11-12，A1/D5）：dispatch_task
                    // 建行时取当前聊天绑定作 projectId（绑定为准）。
                    currentProjectId = ::currentChatProjectId,
                    // P2 自动执行（2026-11-11）：审查者候选里的远端成员走
                    // Hand（本机大脑候选由 BrainBootstrap 自己取已配置大脑）。
                    pipelineRemoteCaller = RemotePipelineAgentCaller(
                        remoteChatPort,
                        targetFor = ::resolveRemoteTargetForMember,
                    ),
                    pipelineRemoteReviewerCandidates = ::pipelineRemoteReviewerCandidates,
                    // MCP 通用化（2026-11-12）：MCP OAuth 授权时用
                    // Custom Tabs 开浏览器（回环收码在 McpOAuthWiring）。
                    openOAuthUrl = { url ->
                        runOnUiThread {
                            CustomTabsIntent.Builder().build()
                                .launchUrl(this, Uri.parse(url))
                        }
                    },
                )
                runOnUiThread {
                    brainComponents = components
                    chatViewModel.attach(components, toolApprovalGate)
                    // 阶段 B：接上远端聊天（Hand 直连）+ 启动远程任务泵。
                    // V5 跨设备开发（2026-11-12，A3）：远端聊天轮次按当前
                    // 聊天绑定的项目解析派活文件夹（轮次任务行经 A1 同样
                    // 带该绑定，两边口径一致）；无绑定时回落 Agent 文件夹。
                    chatViewModel.attachRemoteChat(
                        port = remoteChatPort,
                        controlSender = remoteControlSender,
                        targetResolver = { agent -> resolveRemoteTarget(agent, currentChatProjectId()) },
                        isRemoteAgent = ::isRemoteAgent,
                    )
                    // V5 修复（2026-11-11，能用的 Agent 显示“未验证”）：
                    // 一次真实往返成功后把该 Agent 标成已验证——Agent
                    // 真回了消息是比 hello 探测更强的可用证据，状态
                    // 标签必须反映对方的真实情况。
                    chatViewModel.onRemoteRoundSucceeded = { agent ->
                        markAgentVerifiedByRealRound(agent)
                    }
                    // V5 修复（2026-11-11）：大脑建好后刷一遍配对快照，
                    // UI 线程的路由判定（横幅/发送）才读得到最新配对状态。
                    refreshPairingSnapshot()
                    // 阶段 C：接上 Manus 聊天（任务型 API 转聊天消息）。
                    chatViewModel.attachManusChat(
                        port = manusChatPort,
                        isManusConfigured = ::isManusConfigured,
                    )
                    // 平台 Agent：接上平台频道聊天（正文带收件方前缀 →
                    // 平台频道 → 轮询收回复；只发任务消息，不传代码）。
                    chatViewModel.attachPlatformChat(
                        port = platformChatPort,
                        isPlatformAgentConfigured = ::isPlatformAgentConfigured,
                    )
                    // V5 C3 chat-bugfix：默认会话历史恢复优先走 Room（完整
                    // 历史，含助手正文）；快照是回退（修前建的对话没有 id）。
                    if (chatViewModel.stateNow().turns.isEmpty()) {
                        val savedId = chatHistoryStore.loadCurrentSessionId()
                        if (savedId != null) {
                            Thread {
                                val turns = try {
                                    ChatSessionRestore.messagesToTurns(
                                        components.conversationStore.listMessages(savedId),
                                    )
                                } catch (_: Exception) {
                                    emptyList()
                                }
                                runOnUiThread {
                                    if (isFinishing || isDestroyed) return@runOnUiThread
                                    if (turns.isNotEmpty()) {
                                        chatViewModel.adoptRestoredSession(savedId, turns)
                                    } else {
                                        val saved = chatHistoryStore.load()
                                        if (saved.isNotEmpty()) {
                                            chatViewModel.restoreDefaultTurns(saved)
                                        }
                                    }
                                }
                            }.start()
                        } else {
                            val saved = chatHistoryStore.load()
                            if (saved.isNotEmpty()) {
                                chatViewModel.restoreDefaultTurns(saved)
                            }
                        }
                    }
                    // 阶段 1（U3）：分派任务状态变化 -> 刷新任务列表；启动本地认领循环。
                    components.taskRunner.onTasksChanged = { refreshTasks() }
                    components.taskRunner.start()
                    // 阶段 B：远程任务泵——排队里指派给远程 Agent 的任务，
                    // 由泵认领并经 Hand 发到远端执行（与本地执行器并行，
                    // 各做各的任务，单任务规则各自守）。
                    val pump = RemoteTaskPump(
                        taskStore = components.taskStore,
                        scheduler = components.taskScheduler,
                        stepStore = components.taskStepStore,
                        dispatcher = RemoteTaskDispatcher(
                            taskStore = components.taskStore,
                            stepStore = components.taskStepStore,
                            port = remoteChatPort,
                            // P2：远端编码发 plan 批准后的包、完成停 review。
                            pipelineHook = components.pipelineOrchestrator?.let { PipelineRunnerHookImpl(it) },
                        ),
                        // V5 跨设备开发（2026-11-12，A3/A5）：泵按 (任务,
                        // 成员) 解析目标，派活文件夹取任务项目的检出文件夹；
                        // 回落事件写项目名，注入项目库。
                        targetResolver = { task, agentId -> resolveRemoteTargetForMember(agentId, task) },
                        isRemoteMember = ::isRemoteMember,
                        // 2026-11-12 回复归属修复：指派了不存在 Agent
                        // 的排队任务，泵标 needs_attention + 原因，不静默等。
                        agentExists = { agentId ->
                            try {
                                agentStore.getAgent(agentId) != null
                            } catch (_: Exception) {
                                true
                            }
                        },
                        // P1 eval 日志：远端任务落终态时记一条。
                        evalRecorder = EvalLogRecorder(
                            components.evalLogStore,
                            components.routingDecisionLog,
                        ),
                        // P2：plan 未批准的流水线任务不认领编码。
                        pipelineGate = { id -> components.pipelineOrchestrator?.canStartCoding(id) ?: true },
                        projectStore = projectStore,
                        // V5 跨设备开发（2026-11-12，D7 新口径）：git 家且
                        // 目标设备上无 DEVICE 检出时不认领、不派活。
                        notDispatchableReason = { task, agentId ->
                            try {
                                agentStore.getAgent(agentId)?.let { dispatchBlockedReason(projectStore, task, it) }
                            } catch (_: Exception) {
                                null
                            }
                        },
                        // Agent 开关（2026-11-12）：关掉的 Agent 不接派活。
                        // 每轮现读记录——开关翻完下一轮（500ms 内）即生效，
                        // 不用重启 App；记录不存在按启用处理（目标解析不到
                        // 时自有 markUnroutable 兜底）。
                        isAgentEnabled = { agentId ->
                            try {
                                agentStore.getAgent(agentId)?.enabled ?: true
                            } catch (_: Exception) {
                                true
                            }
                        },
                    )
                    remoteTaskPump = pump
                    pump.start()
                    // P2 自动执行（2026-11-11）：规划者自动起草 + 审查者自动
                    // 执行的泵。runner 在 BrainBootstrap.build() 里已建好
                    // （本机大脑 + 远端成员双调用方）；这里只启动它，任务
                    // 列表变化时刷新。批准门不变：草稿仍等用户点批准。
                    components.pipelineRunner?.let { runner ->
                        runner.onTasksChanged = { refreshTasks() }
                        runner.start()
                    }
                    buttonSignInChatGpt.isEnabled = true
                    refreshBrainStatus()
                    refreshCloudflareSection()
                    refreshDeepSeekSection()
                    refreshGrokSection()
                    refreshMuseSection()
                    refreshClaudeSection()
                    refreshEvents()
                    refreshTasks()
                    if (currentEntry == DrawerController.ID_AGENTS_TEAM ||
                        currentEntry == DrawerController.ID_DEVICES
                    ) refreshAgents()
                    // 阶段 3（U3/批 3a）：旋转重建后恢复详情浮层。
                    pendingTaskDetailId?.let { id ->
                        pendingTaskDetailId = null
                        if (!isFinishing && !isDestroyed) openTaskDetail(id)
                    }
                }
            } catch (e: Exception) {
                brainLoading.set(false)
                runOnUiThread {
                    brainStatus.text = "${brainDisplayName()}：加载失败（${e.message}）"
                    chatViewModel.note(
                        "大脑依赖加载失败：${e.message ?: "未知错误"}。聊天会退回到设备 Agent；" +
                            "配置页仍可打开，但保存/登录需要等依赖恢复。",
                    )
                }
            }
        }.start()
    }

    /** 阶段 3（U6）：设置“工作区/关于”分节的只读内容（单一工作区 + 版本）。 */
    private fun refreshWorkspaceSection() {
        if (!::workspacePath.isInitialized) return
        val components = brainComponents
        workspacePath.text = if (components == null) {
            "本机工作区信息暂不可用"
        } else {
            "本机工作区已配置；具体路径见高级信息。"
        }
        workspaceAdvancedDetails.text = buildString {
            append("工作区路径：${components?.workspaceDir?.absolutePath ?: "暂不可用"}\n")
            append("审批：写文件/命令执行由系统审批弹窗控制，拒绝或界面销毁后授权立即作废。\n")
            append("本地执行：Python 为 APK 内嵌 CPython，shell 在 App 沙箱内运行。")
        }
        // V5 C1：保留现有版本行不动，在其后追加短 SHA 与构建时间（VersionInfo）。
        aboutVersion.text = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            VersionInfo.aboutText("WhereverCode 超级 Agent · ${info.versionName}（${info.versionCode}）")
        } catch (_: Exception) {
            VersionInfo.aboutText("WhereverCode 超级 Agent")
        }
    }

    /**
     * 大脑显示名（V5 C2-3-R4c）：设置页可自定义，默认 WhereverCode；
     * 设置页大脑状态行、帮助页与默认会话标签都用它。问候固定“你好”，
     * 与大脑名无关。空白输入恢复默认（删键，见 [BrainDisplayName]）。
     */
    /**
     * 阶段 A（V5 紧急开发）：聊天顶部显示当前设备与对话方。
     * 默认会话：“当前设备：本机 · 对话方：<大脑显示名>”；
     * Agent 会话：“当前设备：<云端/本机（CLI）/远程设备（未接入）> ·
     * 对话方：<Agent 名>”，未接入的目标缀“（未接入）”。
     * 每次聊天渲染都刷新（切换会话/新对话/改名都会经过 renderChat
     * 或 refreshBrainStatus），绝不让顶部显示与实际路由脱节。
     */
    /**
     * 大脑/Agent 边界（maintainer 2026-11-12）：显式标任务开关的状态——置起
     * 时“任务 ✓”且用正文色（醒目），放下时“任务”且用次要色。只影响
     * 下一条消息，发完自动复位（见 sendPrompt）。
     */
    private fun updateTaskMarkChip() {
        if (!::buttonTaskMark.isInitialized || !::chatViewModel.isInitialized) return
        if (chatViewModel.isForceTaskNext()) {
            buttonTaskMark.text = "任务 ✓"
            buttonTaskMark.setTextColor(getColor(R.color.home_text))
        } else {
            buttonTaskMark.text = "任务"
            buttonTaskMark.setTextColor(getColor(R.color.home_text_secondary))
        }
    }

    private fun updateChatRouteBanner() {
        if (!::chatRouteBanner.isInitialized || !::chatViewModel.isInitialized) return
        val agent = chatViewModel.selectedAgent
        // GitHub 闭环（2026-11-11）：横幅显示当前聊天项目（打开哪个项目，
        // 对话就关联哪个项目；GitHub 家会标出仓库名）。
        // 聊天选项目（2026-11-11）：横幅里的项目部分可点，点开选项目列表
        // 切换绑定（下划线提示可点；没绑项目时没有项目部分，走 "+" 菜单）。
        val projectSuffix = chatProjectBinding.projectId?.let { id ->
            val name = try {
                projectStore.getProject(id)?.name
            } catch (_: Exception) {
                null
            } ?: id
            val home = try {
                projectStore.getActiveHome(id)
            } catch (_: Exception) {
                null
            }
            val homeLabel = when {
                home == null -> "未指定家"
                home.gitHost() != null -> "${home.gitHost()?.displayName} · ${home.workspacePath}"
                home.homeKind == ProjectHomeKind.LOCAL -> "本机 · ${home.workspacePath}"
                else -> "设备 · ${deviceDisplayName(home.hostId)}"
            }
            " · 项目：$name（$homeLabel）"
        }.orEmpty()
        // 大脑/Agent 边界（maintainer 2026-11-12 修订）：直连 Agent 开关只在
        // 远端设备 Agent 对话里出现——点按切换，状态看得见（开/关写在
        // 横幅上）、点得到（整段可点）。默认开：消息直接发给该 Agent，
        // 大脑不拦截、不派活、不插话；关 = 该对话改走大脑幕后派活
        // （任务型指令建派活任务行、走泵）。开关是显式选择，按会话记。
        val directSuffix = if (agent != null && agent.deviceId != null) {
            if (chatViewModel.isDirectAgentMode()) " · 直连 Agent：开" else " · 直连 Agent：关"
        } else {
            ""
        }
        // 三个对话模式（maintainer 2026-11-12 06:46）：横幅开头明确标出当前
        // 是哪个模式（大脑对话 / 单兵对话），用户一眼看得出在跟谁说
        // 话；模式段可点，点开模式切换（大脑/单兵/团队）。团队对话
        // 是频道屏（TEAM_CHANNEL），不在聊天屏。
        val mode = ChatMode.forConversation(agent)
        val modePrefix = "模式：${mode.label}"
        val text = if (agent == null) {
            "$modePrefix · 当前设备：本机 · 对话方：${brainDisplayName()}$projectSuffix"
        } else {
            val route = brainComponents?.let {
                AgentChatRouter.resolve(
                    agent,
                    it.configStore.listConfigs(),
                    it::isBrainConfigured,
                    isRemoteAgent = ::isRemoteAgent,
                    isManusConfigured = ::isManusConfigured,
                    isPlatformAgentConfigured = ::isPlatformAgentConfigured,
                )
            }
            val suffix = if (route is AgentChatRoute.NotRoutable) "（未接入）" else ""
            "$modePrefix · 当前设备：${AgentChatRouter.deviceLabel(agent)} · 对话方：${agent.displayName}$suffix$projectSuffix$directSuffix"
        }
        val spannable = SpannableStringBuilder(text)
        // 模式段可点：点开三个模式的切换（大脑/单兵/团队）。
        spannable.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    showChatModePicker()
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = true
                }
            },
            0,
            modePrefix.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        val baseEnd = text.length - directSuffix.length
        if (projectSuffix.isNotEmpty()) {
            val start = baseEnd - projectSuffix.length
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        showChatProjectPicker()
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = true
                    }
                },
                start,
                baseEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (directSuffix.isNotEmpty()) {
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        // 直连开关：关了就是真的关了（大脑不再拦截该对话
                        // 的消息）；开了就只跟该 Agent 说话。横幅经
                        // publish → renderChat 立刻刷新，状态马上可见。
                        chatViewModel.setDirectAgentMode(!chatViewModel.isDirectAgentMode())
                        updateChatRouteBanner()
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = true
                    }
                },
                baseEnd,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        chatRouteBanner.text = spannable
        chatRouteBanner.movementMethod = LinkMovementMethod.getInstance()
        chatRouteBanner.visibility = View.VISIBLE
        // V5 聊天清理 20261111：输入框里的设备/Agent 胶囊与横幅同源刷新——
        // 未选 Agent 显示“团队 ⌄”，选了显示当前对话方展示名 + ⌄；点按
        // 开选 Agent 对话框（见 buttonAgentSelect 的点击监听）。
        if (::buttonAgentSelect.isInitialized) {
            buttonAgentSelect.text = if (agent == null) {
                "团队 ⌄"
            } else {
                "${agent.displayName} ⌄"
            }
        }
        // maintainer 2026-11-11：大脑胶囊与横幅同源刷新——跟远程 Agent 对话
        // 时不显示（推理不走全局大脑，显示它是误导）；大脑对话时照常显示。
        refreshBrainCapsule()
    }

    /**
     * 输入框大脑胶囊（maintainer 2026-11-11：能清理就清理掉）：
     * 只在跟大脑对话（未选 Agent）时显示；跟远程 Agent 对话时隐藏——
     * 推理不走全局大脑，显示 DeepSeek 是误导。Agent 下拉已显示对话方，
     * 信息不丢。跟 updateChatRouteBanner 同源刷新：会话/对话方一切换
     * （renderChat 经 publish 调 updateChatRouteBanner）胶囊状态就跟着变。
     */
    private fun refreshBrainCapsule() {
        if (!::brainCapsule.isInitialized) return
        val agent = if (::chatViewModel.isInitialized) chatViewModel.selectedAgent else null
        if (agent == null) {
            brainCapsule.text = brainShortLabel()
            brainCapsule.visibility = View.VISIBLE
        } else {
            brainCapsule.visibility = View.GONE
        }
    }

    private fun brainDisplayName(): String = BrainDisplayName.load(brainNamePrefs)

    /** 大脑改名后同步各处：设置行值、默认会话标签、会话列表行名、状态行。 */
    private fun applyBrainDisplayName() {
        val name = brainDisplayName()
        if (::brainDisplayNameView.isInitialized) brainDisplayNameView.text = name
        if (::chatViewModel.isInitialized) {
            chatViewModel.setDefaultSessionLabel(BrainDisplayName.sessionLabel(name))
        }
        if (::sessionListController.isInitialized) {
            sessionListController.defaultSessionLabel = BrainDisplayName.sessionLabel(name)
        }
    }

    /** 设置页“大脑名称”编辑框（复用项目里的 AlertDialog 写法）。 */
    private fun showBrainNameDialog() {
        val input = EditText(this).apply {
            setText(brainDisplayName())
            setSingleLine(true)
            setTextSize(13f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_input)
            hint = "留空恢复默认（WhereverCode）"
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("大脑名称")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                BrainDisplayName.save(brainNamePrefs, input.text.toString())
                applyBrainDisplayName()
                refreshBrainStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 大脑状态的次级入口：设置页大脑分节（screen_settings.xml 的
     * brainStatus，页首“大脑”分节）。V5 C2-3-R4 起顶栏不再有大脑
     * pill：当前大脑（厂商/模型/已连接或未配置）只在这里显示，文案
     * 取自真实配置，绝不编造；行首用大脑显示名（默认 WhereverCode）。
     */
    private fun refreshBrainStatus() {
        // 阶段 A：大脑改名/切换后聊天顶部横幅同步（默认会话显示大脑名）。
        if (::chatRouteBanner.isInitialized) updateChatRouteBanner()
        refreshWorkspaceSection()
        // 设置清理 2（2026-11-11，Claude 标准）：聊天输入框里的当前大脑
        // 胶囊与大脑状态同刷；未加载/未连接时显示“未连接”。跟 Agent 对话
        // 时胶囊隐藏（见 refreshBrainCapsule）。
        refreshBrainCapsule()
        val name = brainDisplayName()
        val components = brainComponents ?: run {
            brainStatus.text = "$name：加载中…"
            buttonSignInChatGpt.visibility = View.VISIBLE
            buttonSignOut.visibility = View.GONE
            return
        }
        val signedIn = components.authManager.isSignedIn()
        val currentBrainId = components.configStore.currentBrainId()
        // V5 C3-C：状态行刷新时同步刷新大脑列表、SIWC 模型行与 CLI 行。
        refreshBrainList()
        refreshSiwcModelRow()
        refreshCliRow()
        updateSiwcSignInButtons()
        // API Key 大脑（DeepSeek / Grok / Muse Spark / Claude）：同口径
        // 分支——已配置写“已连接”，未配置写去高级配置里填什么。
        val apiBrainLabel = when (currentBrainId) {
            BrainBootstrap.DEEPSEEK_BRAIN_ID -> "DeepSeek"
            BrainBootstrap.GROK_BRAIN_ID -> "Grok"
            BrainBootstrap.MUSE_BRAIN_ID -> "Muse Spark"
            BrainBootstrap.CLAUDE_BRAIN_ID -> "Claude"
            else -> null
        }
        if (apiBrainLabel != null && currentBrainId != null) {
            buttonSignInChatGpt.visibility = View.GONE
            buttonSignOut.visibility = View.GONE
            if (components.isBrainConfigured(currentBrainId)) {
                brainStatus.text = "$name：$apiBrainLabel API · 已连接"
            } else {
                brainStatus.text = "$name：$apiBrainLabel（未配置：请在下方高级配置里填写 API Key、Base URL 与模型）"
            }
            return
        }
        if (currentBrainId == BrainBootstrap.CLOUDFLARE_BRAIN_ID) {
            buttonSignInChatGpt.visibility = View.GONE
            buttonSignOut.visibility = View.GONE
            if (components.isBrainConfigured(currentBrainId)) {
                brainStatus.text = "$name：Cloudflare Workers AI · 已连接"
            } else {
                brainStatus.text = "$name：Cloudflare（未配置：请在下方高级配置里填写 Account ID、API Token 与模型）"
            }
            return
        }
        if (currentBrainId == BrainBootstrap.CLI_BRAIN_ID) {
            buttonSignInChatGpt.visibility = View.GONE
            buttonSignOut.visibility = View.GONE
            val cliConfig = components.configStore.getConfig(BrainBootstrap.CLI_BRAIN_ID)
            brainStatus.text = "$name：CLI 会员额度 · " +
                (cliConfig?.let { BrainLoginRows.cliStatusText(it) } ?: "未配置")
            return
        }
        // 设备码会员大脑（Qwen / Kimi / Grok 会员，2026-11-12）：
        // 状态行只说登录与模型两件事，不冒充已验证的推理。
        if (currentBrainId != null &&
            DeviceCodeConfigs.isDeviceCodeBrainId(currentBrainId)
        ) {
            buttonSignInChatGpt.visibility = View.GONE
            buttonSignOut.visibility = View.GONE
            val manager = components.deviceCodeManagers[currentBrainId]
            val config = components.configStore.getConfig(currentBrainId)
            val signedIn = manager?.isSignedIn() == true
            val model = config?.model?.takeIf { it.isNotBlank() }
            brainStatus.text = when {
                signedIn && model != null ->
                    "$name：${config?.label} · 已登录（模型 $model）"
                signedIn ->
                    "$name：${config?.label}（已登录，未填模型：请在列表“···”→“编辑模型…”里填）"
                else ->
                    "$name：${config?.label}（未登录：请在列表“···”→“登录…”里完成设备码登录）"
            }
            return
        }
        // Gemini（仅登录，2026-11-12）：登录也不能当大脑，状态行写明。
        if (currentBrainId == BrainBootstrap.GEMINI_BRAIN_ID) {
            buttonSignInChatGpt.visibility = View.GONE
            buttonSignOut.visibility = View.GONE
            brainStatus.text = "$name：Gemini（${BrainLoginRows.GEMINI_LOGIN_ONLY_NOTE}）"
            return
        }
        // 通用 MODEL_API 大脑已可设为全局大脑（maintainer 2026-11-11 决定
        // 放开切换）：状态行如实写“已连接”（与其它支持工具调用的大脑
        // 同口径，不写“接入中”，也不写“仅 Agent 对话可用”；工具调用
        // 尚未在真机上验证，见切换提示）。
        val currentConfig = currentBrainId?.let { components.configStore.getConfig(it) }
        if (currentConfig?.adapterId == BrainLoginRows.GENERIC_ADAPTER_ID) {
            buttonSignInChatGpt.visibility = View.GONE
            buttonSignOut.visibility = View.GONE
            brainStatus.text = if (currentBrainId != null && components.isBrainConfigured(currentBrainId)) {
                "$name：${currentConfig.label} · 已连接"
            } else {
                "$name：${currentConfig.label}（未配置：请在下方配置并保存）"
            }
            return
        }
        if (signedIn && currentBrainId == BrainBootstrap.SIWC_BRAIN_ID) {
            brainStatus.text = "$name：ChatGPT（Sign in with ChatGPT）· 已连接"
            buttonSignInChatGpt.visibility = View.GONE
            buttonSignOut.visibility = View.VISIBLE
        } else {
            brainStatus.text = "$name：未连接（登录 ChatGPT，或在设置配置 Cloudflare / DeepSeek / Grok / Muse Spark / Claude）"
            buttonSignInChatGpt.visibility = View.VISIBLE
            buttonSignOut.visibility = View.GONE
        }
    }

    private fun brainShortLabel(): String = when (val id = brainComponents?.configStore?.currentBrainId()) {
        BrainBootstrap.DEEPSEEK_BRAIN_ID -> "DeepSeek"
        BrainBootstrap.GROK_BRAIN_ID -> "Grok"
        BrainBootstrap.MUSE_BRAIN_ID -> "Muse Spark"
        BrainBootstrap.CLAUDE_BRAIN_ID -> "Claude"
        BrainBootstrap.CLOUDFLARE_BRAIN_ID -> "Cloudflare"
        BrainBootstrap.SIWC_BRAIN_ID -> "ChatGPT"
        BrainBootstrap.CLI_BRAIN_ID -> "CLI 会员额度"
        null -> "未连接"
        // 通用大脑显示配置名（label），不贴 brainId（"model-api-zhipu"
        // 这种 id 不是给人看的；放开切换后通用大脑会进聊天胶囊）。
        else -> brainComponents?.configStore?.getConfig(id)?.label ?: id
    }

    private fun refreshCloudflareSection() {
        val components = brainComponents ?: return
        val config = components.configStore.getConfig(BrainBootstrap.CLOUDFLARE_BRAIN_ID)
        cfAccountId.setText(
            config?.extra?.get(CloudflareBrainAdapter.EXTRA_ACCOUNT_ID) ?: "",
        )
        cfModel.setText(
            config?.model?.takeIf { it.isNotBlank() }
                ?: CloudflareBrainAdapter.SUGGESTED_DEFAULT_MODEL,
        )
        cloudflareStatus.text = if (components.isBrainConfigured(BrainBootstrap.CLOUDFLARE_BRAIN_ID)) {
            "Cloudflare：已配置（模型 ${config?.model ?: ""}；Token 已存本机 Keystore）"
        } else {
            "Cloudflare：未配置（请填 Account ID、API Token 与模型，再点保存）"
        }
    }

    private fun saveCloudflare() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再保存 Cloudflare 配置。")
            return
        }
        val accountId = cfAccountId.text.toString().trim()
        val token = cfApiToken.text.toString().trim()
        val model = cfModel.text.toString().trim()
        if (accountId.isEmpty() || model.isEmpty()) {
            note("Cloudflare 未保存：Account ID 与模型都不能为空。")
            return
        }
        val hasStoredToken = components.cloudflareCredentialStore.loadToken() != null
        if (token.isEmpty() && !hasStoredToken) {
            note("Cloudflare 未保存：请填写 API Token（只显示一次，保存在本机 Keystore）。")
            return
        }
        Thread {
            if (token.isNotEmpty()) components.cloudflareCredentialStore.saveToken(token)
            val current = components.configStore.getConfig(BrainBootstrap.CLOUDFLARE_BRAIN_ID)
                ?: BrainConfig(
                    brainId = BrainBootstrap.CLOUDFLARE_BRAIN_ID,
                    kind = BrainKind.MODEL_API,
                    adapterId = BrainBootstrap.CLOUDFLARE_BRAIN_ID,
                    label = "Cloudflare Workers AI",
                    baseUrl = CloudflareBrainAdapter.ACCOUNTS_BASE_URL,
                    model = model,
                    authRef = BrainBootstrap.CLOUDFLARE_AUTH_REF,
                    fallbackBrainIds = listOf(BrainBootstrap.SIWC_BRAIN_ID),
                )
            components.configStore.saveConfig(
                current.copy(
                    model = model,
                    extra = current.extra + (CloudflareBrainAdapter.EXTRA_ACCOUNT_ID to accountId),
                ),
            )
            components.configStore.setCurrentBrainId(BrainBootstrap.CLOUDFLARE_BRAIN_ID)
            runOnUiThread {
                cfApiToken.text.clear()
                refreshBrainStatus()
                refreshCloudflareSection()
                note("已保存 Cloudflare 配置并选用它（模型 $model）。现在发一句话即可。")
            }
        }.start()
    }

    private fun clearCloudflare() {
        val components = brainComponents ?: return
        Thread {
            runBlocking { components.cloudflareProvider.revoke() }
            if (components.configStore.currentBrainId() == BrainBootstrap.CLOUDFLARE_BRAIN_ID) {
                components.configStore.setCurrentBrainId(null)
            }
            runOnUiThread {
                refreshBrainStatus()
                refreshCloudflareSection()
                note(
                    "已清除本机的 Cloudflare Token，大脑回到未连接状态。" +
                        "如需彻底作废该 Token，请在 Cloudflare Dashboard → API Tokens 删除它。",
                )
            }
        }.start()
    }

    private fun refreshDeepSeekSection() {
        val components = brainComponents ?: return
        val config = components.configStore.getConfig(BrainBootstrap.DEEPSEEK_BRAIN_ID)
        dsBaseUrl.setText(
            config?.baseUrl?.takeIf { it.isNotBlank() } ?: DeepSeekBrainAdapter.DEFAULT_BASE_URL,
        )
        dsModel.setText(
            config?.model?.takeIf { it.isNotBlank() } ?: DeepSeekApiClient.SUGGESTED_MODELS.first(),
        )
        deepseekStatus.text = if (components.isBrainConfigured(BrainBootstrap.DEEPSEEK_BRAIN_ID)) {
            "DeepSeek：已配置（模型 ${config?.model ?: ""}；Key 已存本机 Keystore）"
        } else {
            "DeepSeek：未配置（填 Base URL、模型与 API Key，再点保存）"
        }
    }

    private fun saveDeepSeek() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再保存 DeepSeek 配置。")
            return
        }
        val baseUrl = dsBaseUrl.text.toString().trim().trimEnd('/')
        val model = dsModel.text.toString().trim()
        val apiKey = dsApiKey.text.toString().trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            note("DeepSeek 未保存：Base URL 与模型都不能为空。")
            return
        }
        if (!BrainConfigValidator.isAllowedBaseUrl(baseUrl)) {
            note("DeepSeek 未保存：Base URL 只允许 https（本地模型可用 http://127.0.0.1 或 http://localhost）。")
            return
        }
        val hasStoredKey = components.deepSeekCredentialStore.loadApiKey() != null
        if (apiKey.isEmpty() && !hasStoredKey) {
            note("DeepSeek 未保存：请填写 API Key（只存本机 Keystore）。")
            return
        }
        Thread {
            if (apiKey.isNotEmpty()) components.deepSeekCredentialStore.saveApiKey(apiKey)
            val current = components.configStore.getConfig(BrainBootstrap.DEEPSEEK_BRAIN_ID)
                ?: BrainConfig(
                    brainId = BrainBootstrap.DEEPSEEK_BRAIN_ID,
                    kind = BrainKind.MODEL_API,
                    adapterId = DeepSeekBrainAdapter.ADAPTER_ID,
                    label = "DeepSeek API",
                    baseUrl = baseUrl,
                    model = model,
                    authRef = BrainBootstrap.DEEPSEEK_AUTH_REF,
                    fallbackBrainIds = emptyList(),
                )
            components.configStore.saveConfig(
                current.copy(
                    kind = BrainKind.MODEL_API,
                    adapterId = DeepSeekBrainAdapter.ADAPTER_ID,
                    label = "DeepSeek API",
                    baseUrl = baseUrl,
                    model = model,
                    authRef = BrainBootstrap.DEEPSEEK_AUTH_REF,
                ),
            )
            components.configStore.setCurrentBrainId(BrainBootstrap.DEEPSEEK_BRAIN_ID)
            runOnUiThread {
                dsApiKey.text.clear()
                refreshBrainStatus()
                refreshDeepSeekSection()
                note("已保存 DeepSeek 配置并选用它（模型 $model）。现在发一句话即可。")
            }
        }.start()
    }

    private fun clearDeepSeek() {
        val components = brainComponents ?: return
        Thread {
            components.deepSeekCredentialStore.clearApiKey()
            if (components.configStore.currentBrainId() == BrainBootstrap.DEEPSEEK_BRAIN_ID) {
                components.configStore.setCurrentBrainId(null)
            }
            runOnUiThread {
                deepseekModels.text = "点“刷新模型列表”从账户读取可用模型。"
                refreshBrainStatus()
                refreshDeepSeekSection()
                note(
                    "已清除本机的 DeepSeek API Key，大脑回到未连接状态。" +
                        "如需彻底作废该 Key，请到 DeepSeek 控制台删除它。",
                )
            }
        }.start()
    }

    // ---- Grok API 设置节（2026-11-12，大脑登陆方式补全）：与 DeepSeek 节同形。 ----
    private fun refreshGrokSection() {
        val components = brainComponents ?: return
        val config = components.configStore.getConfig(BrainBootstrap.GROK_BRAIN_ID)
        grokBaseUrl.setText(
            config?.baseUrl?.takeIf { it.isNotBlank() } ?: GrokBrainAdapter.DEFAULT_BASE_URL,
        )
        grokModel.setText(
            config?.model?.takeIf { it.isNotBlank() } ?: GrokBrainAdapter.SUGGESTED_MODEL,
        )
        grokStatus.text = if (components.isBrainConfigured(BrainBootstrap.GROK_BRAIN_ID)) {
            "Grok：已配置（模型 ${config?.model ?: ""}；Key 已存本机 Keystore）"
        } else {
            "Grok：未配置（填 Base URL、模型与 API Key，再点保存）"
        }
    }

    private fun saveGrok() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再保存 Grok 配置。")
            return
        }
        val baseUrl = grokBaseUrl.text.toString().trim().trimEnd('/')
        val model = grokModel.text.toString().trim()
        val apiKey = grokApiKey.text.toString().trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            note("Grok 未保存：Base URL 与模型都不能为空。")
            return
        }
        if (!BrainConfigValidator.isAllowedBaseUrl(baseUrl)) {
            note("Grok 未保存：Base URL 只允许 https（本地模型可用 http://127.0.0.1 或 http://localhost）。")
            return
        }
        val hasStoredKey = components.grokCredentialStore.loadApiKey() != null
        if (apiKey.isEmpty() && !hasStoredKey) {
            note("Grok 未保存：请填写 API Key（只存本机 Keystore）。")
            return
        }
        Thread {
            if (apiKey.isNotEmpty()) components.grokCredentialStore.saveApiKey(apiKey)
            val current = components.configStore.getConfig(BrainBootstrap.GROK_BRAIN_ID)
                ?: BrainConfig(
                    brainId = BrainBootstrap.GROK_BRAIN_ID,
                    kind = BrainKind.MODEL_API,
                    adapterId = GrokBrainAdapter.ADAPTER_ID,
                    label = "Grok",
                    baseUrl = baseUrl,
                    model = model,
                    authRef = BrainBootstrap.GROK_AUTH_REF,
                    fallbackBrainIds = emptyList(),
                )
            components.configStore.saveConfig(
                current.copy(
                    kind = BrainKind.MODEL_API,
                    adapterId = GrokBrainAdapter.ADAPTER_ID,
                    label = "Grok",
                    baseUrl = baseUrl,
                    model = model,
                    authRef = BrainBootstrap.GROK_AUTH_REF,
                ),
            )
            components.configStore.setCurrentBrainId(BrainBootstrap.GROK_BRAIN_ID)
            runOnUiThread {
                grokApiKey.text.clear()
                refreshBrainStatus()
                refreshGrokSection()
                note("已保存 Grok 配置并选用它（模型 $model）。现在发一句话即可。")
            }
        }.start()
    }

    private fun clearGrok() {
        val components = brainComponents ?: return
        Thread {
            components.grokCredentialStore.clearApiKey()
            if (components.configStore.currentBrainId() == BrainBootstrap.GROK_BRAIN_ID) {
                components.configStore.setCurrentBrainId(null)
            }
            runOnUiThread {
                grokModels.text = "点“刷新模型列表”从账户读取可用模型。"
                refreshBrainStatus()
                refreshGrokSection()
                note(
                    "已清除本机的 Grok API Key，大脑回到未连接状态。" +
                        "如需彻底作废该 Key，请到 xAI 控制台删除它。",
                )
            }
        }.start()
    }

    // ---- Muse Spark 设置节（2026-11-12，大脑登陆方式补全）：与 DeepSeek 节同形。 ----
    private fun refreshMuseSection() {
        val components = brainComponents ?: return
        val config = components.configStore.getConfig(BrainBootstrap.MUSE_BRAIN_ID)
        museBaseUrl.setText(
            config?.baseUrl?.takeIf { it.isNotBlank() } ?: MuseBrainAdapter.DEFAULT_BASE_URL,
        )
        museModel.setText(
            config?.model?.takeIf { it.isNotBlank() } ?: MuseBrainAdapter.SUGGESTED_MODEL,
        )
        museStatus.text = if (components.isBrainConfigured(BrainBootstrap.MUSE_BRAIN_ID)) {
            "Muse Spark：已配置（模型 ${config?.model ?: ""}；Key 已存本机 Keystore）"
        } else {
            "Muse Spark：未配置（填 Base URL、模型与 API Key，再点保存）"
        }
    }

    private fun saveMuse() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再保存 Muse Spark 配置。")
            return
        }
        val baseUrl = museBaseUrl.text.toString().trim().trimEnd('/')
        val model = museModel.text.toString().trim()
        val apiKey = museApiKey.text.toString().trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            note("Muse Spark 未保存：Base URL 与模型都不能为空。")
            return
        }
        if (!BrainConfigValidator.isAllowedBaseUrl(baseUrl)) {
            note("Muse Spark 未保存：Base URL 只允许 https（本地模型可用 http://127.0.0.1 或 http://localhost）。")
            return
        }
        val hasStoredKey = components.museCredentialStore.loadApiKey() != null
        if (apiKey.isEmpty() && !hasStoredKey) {
            note("Muse Spark 未保存：请填写 API Key（只存本机 Keystore）。")
            return
        }
        Thread {
            if (apiKey.isNotEmpty()) components.museCredentialStore.saveApiKey(apiKey)
            val current = components.configStore.getConfig(BrainBootstrap.MUSE_BRAIN_ID)
                ?: BrainConfig(
                    brainId = BrainBootstrap.MUSE_BRAIN_ID,
                    kind = BrainKind.MODEL_API,
                    adapterId = MuseBrainAdapter.ADAPTER_ID,
                    label = "Muse Spark",
                    baseUrl = baseUrl,
                    model = model,
                    authRef = BrainBootstrap.MUSE_AUTH_REF,
                    fallbackBrainIds = emptyList(),
                )
            components.configStore.saveConfig(
                current.copy(
                    kind = BrainKind.MODEL_API,
                    adapterId = MuseBrainAdapter.ADAPTER_ID,
                    label = "Muse Spark",
                    baseUrl = baseUrl,
                    model = model,
                    authRef = BrainBootstrap.MUSE_AUTH_REF,
                ),
            )
            components.configStore.setCurrentBrainId(BrainBootstrap.MUSE_BRAIN_ID)
            runOnUiThread {
                museApiKey.text.clear()
                refreshBrainStatus()
                refreshMuseSection()
                note("已保存 Muse Spark 配置并选用它（模型 $model）。现在发一句话即可。")
            }
        }.start()
    }

    private fun clearMuse() {
        val components = brainComponents ?: return
        Thread {
            components.museCredentialStore.clearApiKey()
            if (components.configStore.currentBrainId() == BrainBootstrap.MUSE_BRAIN_ID) {
                components.configStore.setCurrentBrainId(null)
            }
            runOnUiThread {
                museModels.text = "点“刷新模型列表”从账户读取可用模型。"
                refreshBrainStatus()
                refreshMuseSection()
                note(
                    "已清除本机的 Muse Spark API Key，大脑回到未连接状态。" +
                        "如需彻底作废该 Key，请到 Meta 开发者后台删除它。",
                )
            }
        }.start()
    }

    // ---- Claude API 设置节（2026-11-12，大脑登陆方式补全）：与 DeepSeek 节同形。 ----
    private fun refreshClaudeSection() {
        val components = brainComponents ?: return
        val config = components.configStore.getConfig(BrainBootstrap.CLAUDE_BRAIN_ID)
        claudeBaseUrl.setText(
            config?.baseUrl?.takeIf { it.isNotBlank() } ?: ClaudeBrainAdapter.DEFAULT_BASE_URL,
        )
        claudeModel.setText(
            config?.model?.takeIf { it.isNotBlank() } ?: ClaudeBrainAdapter.SUGGESTED_MODEL,
        )
        claudeStatus.text = if (components.isBrainConfigured(BrainBootstrap.CLAUDE_BRAIN_ID)) {
            "Claude：已配置（模型 ${config?.model ?: ""}；Key 已存本机 Keystore）"
        } else {
            "Claude：未配置（填 Base URL、模型与 API Key，再点保存）"
        }
    }

    private fun saveClaude() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再保存 Claude 配置。")
            return
        }
        val baseUrl = claudeBaseUrl.text.toString().trim().trimEnd('/')
        val model = claudeModel.text.toString().trim()
        val apiKey = claudeApiKey.text.toString().trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            note("Claude 未保存：Base URL 与模型都不能为空。")
            return
        }
        if (!BrainConfigValidator.isAllowedBaseUrl(baseUrl)) {
            note("Claude 未保存：Base URL 只允许 https（本地模型可用 http://127.0.0.1 或 http://localhost）。")
            return
        }
        val hasStoredKey = components.claudeCredentialStore.loadApiKey() != null
        if (apiKey.isEmpty() && !hasStoredKey) {
            note("Claude 未保存：请填写 API Key（只存本机 Keystore）。")
            return
        }
        Thread {
            if (apiKey.isNotEmpty()) components.claudeCredentialStore.saveApiKey(apiKey)
            val current = components.configStore.getConfig(BrainBootstrap.CLAUDE_BRAIN_ID)
                ?: BrainConfig(
                    brainId = BrainBootstrap.CLAUDE_BRAIN_ID,
                    kind = BrainKind.MODEL_API,
                    adapterId = ClaudeBrainAdapter.ADAPTER_ID,
                    label = "Claude API",
                    baseUrl = baseUrl,
                    model = model,
                    authRef = BrainBootstrap.CLAUDE_AUTH_REF,
                    fallbackBrainIds = emptyList(),
                )
            components.configStore.saveConfig(
                current.copy(
                    kind = BrainKind.MODEL_API,
                    adapterId = ClaudeBrainAdapter.ADAPTER_ID,
                    label = "Claude API",
                    baseUrl = baseUrl,
                    model = model,
                    authRef = BrainBootstrap.CLAUDE_AUTH_REF,
                ),
            )
            components.configStore.setCurrentBrainId(BrainBootstrap.CLAUDE_BRAIN_ID)
            runOnUiThread {
                claudeApiKey.text.clear()
                refreshBrainStatus()
                refreshClaudeSection()
                note("已保存 Claude 配置并选用它（模型 $model）。现在发一句话即可。")
            }
        }.start()
    }

    private fun clearClaude() {
        val components = brainComponents ?: return
        Thread {
            components.claudeCredentialStore.clearApiKey()
            if (components.configStore.currentBrainId() == BrainBootstrap.CLAUDE_BRAIN_ID) {
                components.configStore.setCurrentBrainId(null)
            }
            runOnUiThread {
                claudeModels.text = "点“刷新模型列表”从账户读取可用模型。"
                refreshBrainStatus()
                refreshClaudeSection()
                note(
                    "已清除本机的 Claude API Key，大脑回到未连接状态。" +
                        "如需彻底作废该 Key，请到 Anthropic 控制台删除它。",
                )
            }
        }.start()
    }

    /**
     * git 家授权（2026-11-11）：设置页每站一行的授权状态。
     * 状态只显示“已配/未配”，绝不显示 token 值；token 只存本机 Keystore。
     */
    private fun refreshGitHostAuthRows() {
        gitHostAuthRows.forEach { row ->
            row.statusView.text = try {
                if (gitHostCredentialStore.hasToken(row.host)) {
                    "${row.host.displayName} 授权：已配（只存本机 Keystore，不显示 token）"
                } else {
                    "${row.host.displayName} 授权：未配（填 token 后才能读/写 ${row.host.displayName} 家的项目）"
                }
            } catch (e: Exception) {
                // 本机 Keystore 不可用时（例如 Robolectric 单测环境没有
                // AndroidKeyStore，BrainKeyStore 构造即抛 KeyStoreException）
                // 不能让设置页初始化炸掉整个 Activity；状态如实显示
                // 无法读取，不谎报“未配”。
                "${row.host.displayName} 授权：无法读取（本机 Keystore 不可用）"
            }
        }
    }

    private fun saveGitHostToken(row: GitHostAuthRow) {
        val token = row.tokenInput.text.toString().trim()
        if (token.isBlank()) {
            note("${row.host.displayName} 授权未保存：请粘贴你的 ${row.host.displayName} 授权（token）。")
            return
        }
        try {
            gitHostCredentialStore.saveToken(row.host, token)
        } catch (e: Exception) {
            note("保存 ${row.host.displayName} 授权失败：${e.message}")
            return
        }
        row.tokenInput.text.clear()
        refreshGitHostAuthRows()
        note("${row.host.displayName} 授权已保存到本机 Keystore。")
    }

    private fun clearGitHostToken(row: GitHostAuthRow) {
        try {
            gitHostCredentialStore.clearToken(row.host)
        } catch (e: Exception) {
            note("清除 ${row.host.displayName} 授权失败：${e.message}")
            return
        }
        row.tokenInput.text.clear()
        refreshGitHostAuthRows()
        note("已清除本机的 ${row.host.displayName} 授权。如需彻底作废该 token，请到 ${row.host.displayName} 网页删除它。")
    }

    private fun refreshDeepSeekModels() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再刷新 DeepSeek 模型。")
            return
        }
        val baseUrl = dsBaseUrl.text.toString().trim().trimEnd('/')
        val typedKey = dsApiKey.text.toString().trim()
        if (baseUrl.isEmpty()) {
            note("请先填写 DeepSeek Base URL。")
            return
        }
        if (!BrainConfigValidator.isAllowedBaseUrl(baseUrl)) {
            note("Base URL 只允许 https（本地模型可用 http://127.0.0.1 或 http://localhost）。")
            return
        }
        if (typedKey.isEmpty() && components.deepSeekCredentialStore.loadApiKey() == null) {
            note("请先填写 DeepSeek API Key，再刷新模型列表。")
            return
        }
        deepseekModels.text = "正在读取 DeepSeek 模型列表…"
        Thread {
            if (typedKey.isNotEmpty()) components.deepSeekCredentialStore.saveApiKey(typedKey)
            val current = components.configStore.getConfig(BrainBootstrap.DEEPSEEK_BRAIN_ID)
                ?: BrainConfig(
                    brainId = BrainBootstrap.DEEPSEEK_BRAIN_ID,
                    kind = BrainKind.MODEL_API,
                    adapterId = DeepSeekBrainAdapter.ADAPTER_ID,
                    label = "DeepSeek API",
                    baseUrl = baseUrl,
                    model = DeepSeekApiClient.SUGGESTED_MODELS.first(),
                    authRef = BrainBootstrap.DEEPSEEK_AUTH_REF,
                    fallbackBrainIds = emptyList(),
                )
            components.configStore.saveConfig(current.copy(baseUrl = baseUrl))
            val result = runBlocking { components.deepSeekAdapter.listModels() }
            runOnUiThread {
                if (typedKey.isNotEmpty()) dsApiKey.text.clear()
                result.onSuccess { models ->
                    deepseekModels.text = if (models.isEmpty()) {
                        "账户没有返回任何模型；可手动填写模型 ID。"
                    } else {
                        "可用模型：" + models.joinToString("、") { it.id }
                    }
                    if (models.isNotEmpty() && dsModel.text.isBlank()) {
                        dsModel.setText(models.first().id)
                    }
                    note("已从你的 DeepSeek 账户读取 ${models.size} 个模型。选好后点“保存并选用”。")
                }.onFailure { e ->
                    deepseekModels.text = "读取模型列表失败：${e.message}"
                    note("读取 DeepSeek 模型列表失败：${e.message}")
                }
                refreshDeepSeekSection()
            }
        }.start()
    }

    /** 刷新 Grok 模型列表（2026-11-12）：与 refreshDeepSeekModels 同形。 */
    private fun refreshGrokModels() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再刷新 Grok 模型。")
            return
        }
        val baseUrl = grokBaseUrl.text.toString().trim().trimEnd('/')
        val typedKey = grokApiKey.text.toString().trim()
        if (baseUrl.isEmpty()) {
            note("请先填写 Grok Base URL。")
            return
        }
        if (!BrainConfigValidator.isAllowedBaseUrl(baseUrl)) {
            note("Base URL 只允许 https（本地模型可用 http://127.0.0.1 或 http://localhost）。")
            return
        }
        if (typedKey.isEmpty() && components.grokCredentialStore.loadApiKey() == null) {
            note("请先填写 Grok API Key，再刷新模型列表。")
            return
        }
        grokModels.text = "正在读取 Grok 模型列表…"
        Thread {
            if (typedKey.isNotEmpty()) components.grokCredentialStore.saveApiKey(typedKey)
            val current = components.configStore.getConfig(BrainBootstrap.GROK_BRAIN_ID)
                ?: BrainConfig(
                    brainId = BrainBootstrap.GROK_BRAIN_ID,
                    kind = BrainKind.MODEL_API,
                    adapterId = GrokBrainAdapter.ADAPTER_ID,
                    label = "Grok",
                    baseUrl = baseUrl,
                    model = GrokBrainAdapter.SUGGESTED_MODEL,
                    authRef = BrainBootstrap.GROK_AUTH_REF,
                    fallbackBrainIds = emptyList(),
                )
            components.configStore.saveConfig(current.copy(baseUrl = baseUrl))
            val result = runBlocking { components.grokAdapter.listModels() }
            runOnUiThread {
                if (typedKey.isNotEmpty()) grokApiKey.text.clear()
                result.onSuccess { models ->
                    grokModels.text = if (models.isEmpty()) {
                        "账户没有返回任何模型；可手动填写模型 ID。"
                    } else {
                        "可用模型：" + models.joinToString("、") { it.id }
                    }
                    if (models.isNotEmpty() && grokModel.text.isBlank()) {
                        grokModel.setText(models.first().id)
                    }
                    note("已从你的 Grok 账户读取 ${models.size} 个模型。选好后点“保存并选用”。")
                }.onFailure { e ->
                    grokModels.text = "读取模型列表失败：${e.message}"
                    note("读取 Grok 模型列表失败：${e.message}")
                }
                refreshGrokSection()
            }
        }.start()
    }

    /** 刷新 Muse Spark 模型列表（2026-11-12）：与 refreshDeepSeekModels 同形。 */
    private fun refreshMuseModels() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再刷新 Muse Spark 模型。")
            return
        }
        val baseUrl = museBaseUrl.text.toString().trim().trimEnd('/')
        val typedKey = museApiKey.text.toString().trim()
        if (baseUrl.isEmpty()) {
            note("请先填写 Muse Spark Base URL。")
            return
        }
        if (!BrainConfigValidator.isAllowedBaseUrl(baseUrl)) {
            note("Base URL 只允许 https（本地模型可用 http://127.0.0.1 或 http://localhost）。")
            return
        }
        if (typedKey.isEmpty() && components.museCredentialStore.loadApiKey() == null) {
            note("请先填写 Muse Spark API Key，再刷新模型列表。")
            return
        }
        museModels.text = "正在读取 Muse Spark 模型列表…"
        Thread {
            if (typedKey.isNotEmpty()) components.museCredentialStore.saveApiKey(typedKey)
            val current = components.configStore.getConfig(BrainBootstrap.MUSE_BRAIN_ID)
                ?: BrainConfig(
                    brainId = BrainBootstrap.MUSE_BRAIN_ID,
                    kind = BrainKind.MODEL_API,
                    adapterId = MuseBrainAdapter.ADAPTER_ID,
                    label = "Muse Spark",
                    baseUrl = baseUrl,
                    model = MuseBrainAdapter.SUGGESTED_MODEL,
                    authRef = BrainBootstrap.MUSE_AUTH_REF,
                    fallbackBrainIds = emptyList(),
                )
            components.configStore.saveConfig(current.copy(baseUrl = baseUrl))
            val result = runBlocking { components.museAdapter.listModels() }
            runOnUiThread {
                if (typedKey.isNotEmpty()) museApiKey.text.clear()
                result.onSuccess { models ->
                    museModels.text = if (models.isEmpty()) {
                        "账户没有返回任何模型；可手动填写模型 ID。"
                    } else {
                        "可用模型：" + models.joinToString("、") { it.id }
                    }
                    if (models.isNotEmpty() && museModel.text.isBlank()) {
                        museModel.setText(models.first().id)
                    }
                    note("已从你的 Muse Spark 账户读取 ${models.size} 个模型。选好后点“保存并选用”。")
                }.onFailure { e ->
                    museModels.text = "读取模型列表失败：${e.message}"
                    note("读取 Muse Spark 模型列表失败：${e.message}")
                }
                refreshMuseSection()
            }
        }.start()
    }

    /** 刷新 Claude 模型列表（2026-11-12）：与 refreshDeepSeekModels 同形。 */
    private fun refreshClaudeModels() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再刷新 Claude 模型。")
            return
        }
        val baseUrl = claudeBaseUrl.text.toString().trim().trimEnd('/')
        val typedKey = claudeApiKey.text.toString().trim()
        if (baseUrl.isEmpty()) {
            note("请先填写 Claude Base URL。")
            return
        }
        if (!BrainConfigValidator.isAllowedBaseUrl(baseUrl)) {
            note("Base URL 只允许 https（本地模型可用 http://127.0.0.1 或 http://localhost）。")
            return
        }
        if (typedKey.isEmpty() && components.claudeCredentialStore.loadApiKey() == null) {
            note("请先填写 Claude API Key，再刷新模型列表。")
            return
        }
        claudeModels.text = "正在读取 Claude 模型列表…"
        Thread {
            if (typedKey.isNotEmpty()) components.claudeCredentialStore.saveApiKey(typedKey)
            val current = components.configStore.getConfig(BrainBootstrap.CLAUDE_BRAIN_ID)
                ?: BrainConfig(
                    brainId = BrainBootstrap.CLAUDE_BRAIN_ID,
                    kind = BrainKind.MODEL_API,
                    adapterId = ClaudeBrainAdapter.ADAPTER_ID,
                    label = "Claude API",
                    baseUrl = baseUrl,
                    model = ClaudeBrainAdapter.SUGGESTED_MODEL,
                    authRef = BrainBootstrap.CLAUDE_AUTH_REF,
                    fallbackBrainIds = emptyList(),
                )
            components.configStore.saveConfig(current.copy(baseUrl = baseUrl))
            val result = runBlocking { components.claudeAdapter.listModels() }
            runOnUiThread {
                if (typedKey.isNotEmpty()) claudeApiKey.text.clear()
                result.onSuccess { models ->
                    claudeModels.text = if (models.isEmpty()) {
                        "账户没有返回任何模型；可手动填写模型 ID。"
                    } else {
                        "可用模型：" + models.joinToString("、") { it.id }
                    }
                    if (models.isNotEmpty() && claudeModel.text.isBlank()) {
                        claudeModel.setText(models.first().id)
                    }
                    note("已从你的 Claude 账户读取 ${models.size} 个模型。选好后点“保存并选用”。")
                }.onFailure { e ->
                    claudeModels.text = "读取模型列表失败：${e.message}"
                    note("读取 Claude 模型列表失败：${e.message}")
                }
                refreshClaudeSection()
            }
        }.start()
    }

    /**
     * V5 C3-C：ChatGPT 登录（SIWC）。[newProfile] 为 true 时注册新账号
     * （`beginSignIn(newProfile = true)`，不用已存的 client id 与登录
     * 提示）；登录等待中显示“取消登录”，点按调 `cancelSignIn()` 停
     * 回调服务器——关掉 Custom Tab 不用再等满 10 分钟超时。
     * 登录中不要退出设置页（见 [BrainLoginRows.SIWC_SIGNING_IN_NOTE]）。
     */
    private fun signInWithChatGpt(newProfile: Boolean = false) {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再点登录。")
            return
        }
        buttonSignInChatGpt.isEnabled = false
        siwcSigningIn = true
        siwcSignInCancelled = false
        updateSiwcSignInButtons()
        brainStatus.text = "${brainDisplayName()}：正在打开 ChatGPT 登录…"
        Thread {
            try {
                val request = runBlocking { components.authManager.beginSignIn(newProfile) }
                runOnUiThread {
                    CustomTabsIntent.Builder().build()
                        .launchUrl(this, Uri.parse(request.authorizationUrl))
                    note(BrainLoginRows.SIWC_SIGNING_IN_NOTE)
                }
                val result = runBlocking { components.authManager.completeSignIn(request) }
                components.configStore.setCurrentBrainId(BrainBootstrap.SIWC_BRAIN_ID)
                runOnUiThread {
                    siwcSigningIn = false
                    buttonSignInChatGpt.isEnabled = true
                    refreshBrainStatus()
                    val scopes = if (result.sharingEnabled) "套餐用量已授权" else "仅身份登录（未获套餐用量授权）"
                    note("已登录 ChatGPT：$scopes。现在发一句话即可。")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    siwcSigningIn = false
                    buttonSignInChatGpt.isEnabled = true
                    refreshBrainStatus()
                    if (siwcSignInCancelled) {
                        note("已取消 ChatGPT 登录。")
                    } else {
                        note("ChatGPT 登录未完成（${e.message}）。可以重试，或继续用设备 Agent。")
                    }
                }
            }
        }.start()
    }

    /**
     * V5 C3-C：SIWC“切换账号”。换号顺序（审计建议“新注册成功后再忘
     * 旧注册”，但 `forgetSavedRegistration()` 会清掉本地 token——成
     * 功后调它会把新登录一起清掉，所以不能按字面顺序跑）：本流程先
     * 弹确认，用户确认后先忘旧注册、再以 newProfile=true 打开新账号
     * 登录；新登录失败/取消时旧登录已清，需重新登录——确认框里写明。
     * 新登录成功后新 token 覆盖旧 token，旧注册即被取代，无需再忘。
     */
    private fun switchChatGptAccount() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再切换账号。")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("切换 ChatGPT 账号")
            .setMessage(
                "切换账号会先清除本机已保存的 ChatGPT 登录，再打开新账号登录；" +
                    "如果中途取消，需要重新登录。确定切换吗？",
            )
            .setPositiveButton("确定切换") { _, _ ->
                components.authManager.forgetSavedRegistration()
                if (components.configStore.currentBrainId() == BrainBootstrap.SIWC_BRAIN_ID) {
                    components.configStore.setCurrentBrainId(null)
                }
                refreshBrainStatus()
                signInWithChatGpt(newProfile = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** V5 C3-C：登录等待中的“取消登录”——停回调服务器，不用等超时。 */
    private fun cancelChatGptSignIn() {
        val components = brainComponents ?: return
        siwcSignInCancelled = true
        components.authManager.cancelSignIn()
        note("正在取消 ChatGPT 登录…")
    }

    /**
     * V5 C3-C：换号/取消两个按钮的显隐。切换账号只在已登录且不在
     * 登录等待中时显示；取消登录只在登录等待中显示。
     */
    private fun updateSiwcSignInButtons() {
        if (!::buttonCancelSignIn.isInitialized) return
        val signedIn = brainComponents?.authManager?.isSignedIn() == true
        buttonCancelSignIn.visibility = if (siwcSigningIn) View.VISIBLE else View.GONE
        buttonSwitchChatGptAccount.visibility =
            if (signedIn && !siwcSigningIn) View.VISIBLE else View.GONE
    }

    private fun signOut() {
        val components = brainComponents ?: return
        buttonSignOut.isEnabled = false
        brainStatus.text = "${brainDisplayName()}：正在退出…"
        Thread {
            try {
                runBlocking { components.authManager.revoke() }
            } catch (_: Exception) {
                runBlocking { components.authManager.signOut() }
            }
            components.configStore.setCurrentBrainId(null)
            runOnUiThread {
                buttonSignOut.isEnabled = true
                refreshBrainStatus()
                note("已退出 ChatGPT，大脑回到未连接状态。")
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // 会员大脑设备码登录（2026-11-12，Qwen / Kimi / Grok 会员）：
    // RFC 8628 设备码流——App 要码、用户在浏览器里输码授权、App
    // 按 interval 轮询换 token。登录中不要退出设置页，可取消。
    // 全程不用真实账号测试：测试用 fake transport（见
    // DeviceCodeEngineTest / DeviceCodeManagerTest），真机验证等
    // maintainer 自己用自己的账号走通一次 Qwen + Kimi 再算交付。
    // ------------------------------------------------------------------

    /** 从大脑行的“···”→“登录…”启动设备码登录。 */
    private fun startDeviceCodeLogin(brainId: String) {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再点登录。")
            return
        }
        val deviceConfig = DeviceCodeConfigs.byId(brainId) ?: run {
            note("该大脑不支持设备码登录。")
            return
        }
        val manager = components.deviceCodeManagers[brainId] ?: run {
            note("大脑还在加载，请稍候再点登录。")
            return
        }
        deviceCodeLoginCancelled = false
        brainStatus.text = "${brainDisplayName()}：正在向${deviceConfig.displayName}要设备码…"
        Thread {
            try {
                val attempt = runBlocking { manager.beginLogin() }
                runOnUiThread {
                    showDeviceCodeDialog(deviceConfig, attempt)
                    note(BrainLoginRows.DEVICE_CODE_SIGNING_IN_NOTE)
                }
                runBlocking {
                    manager.awaitAuthorization(attempt) { deviceCodeLoginCancelled }
                }
                runOnUiThread {
                    refreshBrainStatus()
                    refreshBrainList()
                    val modelHint =
                        if (components.configStore.getConfig(brainId)?.model.isNullOrBlank()) {
                            "再在“···”→“编辑模型…”里填好模型，即可设为当前大脑。"
                        } else {
                            "现在发一句话即可。"
                        }
                    note("已登录${deviceConfig.displayName}。$modelHint")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    refreshBrainStatus()
                    if (deviceCodeLoginCancelled) {
                        note("已取消${deviceConfig.displayName}登录。")
                    } else {
                        note("${deviceConfig.displayName}登录未完成（${e.message}）。可以重试，或继续用设备 Agent。")
                    }
                }
            }
        }.start()
    }

    /** 设备码弹框：验证码 + 验证地址 + 合规/风险行 + 打开浏览器 / 取消。 */
    private fun showDeviceCodeDialog(
        deviceConfig: wherevercode.agent.brain.devicecode.DeviceCodeConfig,
        attempt: wherevercode.agent.brain.devicecode.DeviceCodeLoginAttempt,
    ) {
        AlertDialog.Builder(this)
            .setTitle(BrainLoginRows.DEVICE_CODE_LOGIN_DIALOG_TITLE)
            .setMessage(
                BrainLoginRows.deviceCodeLoginDialogText(
                    displayName = deviceConfig.displayName,
                    verificationUriComplete = attempt.response.verificationUriComplete,
                    userCode = attempt.response.userCode,
                    complianceNote = deviceConfig.complianceNote,
                    experimentalNote = deviceConfig.experimentalNote,
                ),
            )
            .setPositiveButton("打开浏览器") { _, _ ->
                CustomTabsIntent.Builder().build()
                    .launchUrl(this, Uri.parse(attempt.response.verificationUriComplete))
            }
            .setNegativeButton("取消登录") { _, _ ->
                deviceCodeLoginCancelled = true
            }
            .setOnCancelListener { deviceCodeLoginCancelled = true }
            .show()
    }

    /** 设备码大脑“退出登录”：只清本机 token（无厂商吊销端点可调，见 DeviceCodeManager.signOut）。 */
    private fun signOutDeviceCode(config: BrainConfig) {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再退出。")
            return
        }
        val manager = components.deviceCodeManagers[config.brainId] ?: run {
            note("大脑还在加载，请稍候再退出。")
            return
        }
        manager.signOut()
        if (components.configStore.currentBrainId() == config.brainId) {
            components.configStore.setCurrentBrainId(null)
        }
        refreshBrainStatus()
        note("已退出${config.label}：本机 token 已清。厂商侧的授权如需吊销，请去厂商账号页操作。")
    }

    /** 设备码大脑“编辑模型…”：只改模型名（厂商模型目录里的名字，不写死）。 */
    private fun editDeviceCodeModel(config: BrainConfig) {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再改模型。")
            return
        }
        val input = EditText(this).apply {
            setText(config.model)
            hint = BrainLoginRows.DEVICE_CODE_MODEL_EDIT_HINT
        }
        AlertDialog.Builder(this)
            .setTitle("编辑模型（${config.label}）")
            .setMessage(BrainLoginRows.DEVICE_CODE_MODEL_EDIT_HINT)
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val model = input.text.toString().trim()
                val updated = config.copy(model = model)
                components.configStore.saveConfig(updated)
                BrainAdapterRegistry.registerConfig(updated)
                refreshBrainStatus()
                note(if (model.isBlank()) "模型已清空：填好模型后才能设为当前大脑。" else "模型已保存为 $model。")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // V5 C3-C：设置页大脑多模式登陆（统一列表 / SIWC 模型选择 /
    // 通用 MODEL_API 表单 / CLI 行）。切换只在设置页做；聊天页保持
    // 只读大脑标签（ChatViewModel.brainLabel），不加模型切换器。
    // ------------------------------------------------------------------

    /** 统一大脑列表：行由 [BrainLoginRows.buildRows] 生成，状态取真实值。 */
    private fun refreshBrainList() {
        if (!::brainListContainer.isInitialized) return
        val components = brainComponents ?: return
        val rows = BrainLoginRows.buildRows(
            configs = components.configStore.listConfigs(),
            currentBrainId = components.configStore.currentBrainId(),
            isConfigured = { id -> components.isBrainConfigured(id) },
            siwcSignedIn = components.authManager.isSignedIn(),
        )
        brainListContainer.removeAllViews()
        for (row in rows) brainListContainer.addView(buildBrainListRow(row))
        // 可见的添加入口（2026-11-11，maintainer：大脑增删改添要看得见）：
        // 列表末尾“＋ 添加大脑…”，点即进下方通用大脑添加表单（新增模式）。
        brainListContainer.addView(
            UiKit.text(this, "＋ 添加大脑…", 13f, R.color.text_primary, bold = true).apply {
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setBackgroundResource(R.drawable.bg_card)
                setOnClickListener { addGenericBrain() }
            },
        )
        // 已删除的一线大脑（2026-11-12，完全删除的另一半）：墓碑里且
        // 无配置的脑在列表末尾以“＋ 重新添加 <名>”出现，点即清墓碑、
        // 写回种子配置（Key / token 已删干净，须在对应配置节里重填
        // 自己的 Key 或重新登录）。通用大脑删了就是删了（本来就是
        // 用户自己建的），重新添加走上面的“＋ 添加大脑…”表单，不在
        // 这里单列。
        val deletedIds = components.configStore.deletedBrainIds()
            .filter { id ->
                BrainBootstrap.seedConfigFor(id) != null &&
                    components.configStore.getConfig(id) == null
            }.sorted()
        for (id in deletedIds) {
            val seed = BrainBootstrap.seedConfigFor(id) ?: continue
            brainListContainer.addView(
                UiKit.text(this, "＋ 重新添加 ${seed.label}", 13f, R.color.text_primary, bold = true).apply {
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setBackgroundResource(R.drawable.bg_card)
                    setOnClickListener { restoreBrain(id) }
                },
            )
        }
        // 端侧小模型路由 v1（可选，默认关闭）：大脑列表末尾一行设置入口，
        // 不进“···”管理——maintainer 2026-11-11：管理动作必须有可见入口。
        brainListContainer.addView(buildTriageSettingsRow(components))
    }

    /**
     * 分流小模型设置行（端侧小模型路由 v1）：大脑列表末尾的可见入口，
     * 与大脑行同一卡片样式。状态只说已开启 / 已关闭 + 用的是哪个大脑
     * （名与模型来自配置，不编造）；关闭时文案引导用免费档小模型
     * （见 [TriageRouter.FREE_TIER_HINTS] 的完整清单）。
     * 点开列已配置的通用大脑 + “关闭分流”；只用 setTitle + setItems，
     * 不加 setMessage（AlertDialog 不能同时用 setMessage + setItems，
     * 2026-11-11 弹框空 bug 的教训）。
     */
    private fun buildTriageSettingsRow(components: BrainBootstrap.BrainComponents): View {
        val settings = SharedPreferencesRouterSettings(this)
        val container = UiKit.horizontal(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.bg_card)
            isClickable = true
            isFocusable = true
        }
        val textCol = UiKit.vertical(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(UiKit.text(this, "分流小模型（可选）", 12f, R.color.text_primary, bold = true))
        val statusText = if (settings.triageEnabled) {
            val label = settings.triageBrainId?.let { components.configStore.getConfig(it) }
                ?.let { config ->
                    val name = config.label.takeIf { it.isNotBlank() } ?: config.brainId
                    val model = config.model.takeIf { it.isNotBlank() }
                    if (model != null) "$name · $model" else name
                } ?: settings.triageBrainId ?: "未知"
            "已开启 · $label（只给模式建议，不改派单）"
        } else {
            "已关闭 · 建议用免费档小模型（如 GLM-4.7-Flash）"
        }
        textCol.addView(UiKit.text(this, statusText, 11f, R.color.text_secondary))
        container.addView(textCol)
        if (settings.triageEnabled) {
            container.addView(UiKit.text(this, "✓", 16f, R.color.accent, bold = true))
        }
        container.setOnClickListener { showTriagePickerDialog(components) }
        return container
    }

    /**
     * 分流小模型选择器：只列已配置的通用大脑（分流走通用适配器），
     * 首项是“关闭分流”。不写死任何默认模型、不自动选模型——
     * 绝不默认用付费模型；用户自己挑，文案只引导免费档。
     * 选中即保存并刷新本行；没配通用大脑时先提示去添加，不开空单。
     */
    private fun showTriagePickerDialog(components: BrainBootstrap.BrainComponents) {
        val settings = SharedPreferencesRouterSettings(this)
        val genericBrains = components.configStore.listConfigs()
            .filter { BrainLoginRows.isGenericBrain(it) }
        if (genericBrains.isEmpty()) {
            note("先在下方添加一个通用大脑（建议用免费档小模型，如 GLM-4.7-Flash），再开分流。")
            return
        }
        val labels = listOf("关闭分流") + genericBrains.map { config ->
            val name = config.label.takeIf { it.isNotBlank() } ?: config.brainId
            val model = config.model.takeIf { it.isNotBlank() }
            if (model != null) "$name · $model" else name
        }
        AlertDialog.Builder(this)
            .setTitle("分流小模型（可选）")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    settings.save(false, null)
                    refreshBrainList()
                    note("分流已关闭：派活只走 P1 确定性打分。")
                } else {
                    val picked = genericBrains[which - 1]
                    settings.save(true, picked.brainId)
                    refreshBrainList()
                    val name = picked.label.takeIf { it.isNotBlank() } ?: picked.brainId
                    note("分流已开启：$name 只给模式建议（单 Agent / P2 流水线），不改派单；小模型不可用时自动回落到 P1。")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 大脑列表一行：渲染见 [BrainListRowView]（名 + 一句话状态 + 右侧
     * 打勾；点行即切换；行尾“···”与长按同进管理）。切换只在设置页做；
     * 聊天页保持只读大脑标签（ChatViewModel.brainLabel），不加模型切换器。
     */
    private fun buildBrainListRow(row: BrainLoginRows.BrainListRow): View {
        val manageConfig = brainComponents?.configStore?.getConfig(row.brainId)
        return BrainListRowView.build(
            context = this,
            row = row,
            config = manageConfig,
            onSwitch = { switchBrain(row) },
            onManage = { config -> showBrainManage(config) },
        )
    }

    /** “＋ 添加大脑…”：退出编辑模式（新增），展开高级配置里的通用表单，
     * 定位并聚焦名称栏。表单本来收在折叠里，不展开用户找不到——
     * 藏着的等于没有。 */
    private fun addGenericBrain() {
        if (!::genericBrainLabel.isInitialized) return
        exitGenericEditMode()
        if (::brainAdvancedContainer.isInitialized &&
            brainAdvancedContainer.visibility != View.VISIBLE
        ) {
            brainAdvancedContainer.visibility = View.VISIBLE
            buttonBrainAdvanced.text = "收起高级配置"
        }
        genericBrainLabel.post {
            genericBrainLabel.requestFocus()
            (screenSettings as? ScrollView)?.requestChildFocus(genericBrainLabel, genericBrainLabel)
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(genericBrainLabel, InputMethodManager.SHOW_IMPLICIT)
        }
        note("添加大脑：在下方通用表单里填名称、服务地址、模型、Key，点保存。")
    }

    /**
     * 显式切换大脑：只切已配置的脑（未配置的给原因、不静默）；切脑后
     * 回聊天页发下一句话即用新脑（send() 每轮重读 currentBrainId）。
     * 切到通用大脑时多一句诚实提示：第二阶段起支持工具调用，可执行
     * 任务；写操作会先请用户确认；工具调用尚未在真机上验证（maintainer
     * 2026-11-11 决定放开切换时“第一阶段不支持工具调用”的边界已由
     * 第二阶段取代）。
     */
    private fun switchBrain(row: BrainLoginRows.BrainListRow) {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再切换。")
            return
        }
        if (row.isCurrent) {
            note("已经是当前大脑：${row.title}。")
            return
        }
        when (val decision = BrainLoginRows.switchDecision(row)) {
            BrainLoginRows.SwitchDecision.Allowed -> {
                components.configStore.setCurrentBrainId(row.brainId)
                refreshBrainStatus()
                val toolNote =
                    if (components.configStore.getConfig(row.brainId)?.adapterId ==
                        BrainLoginRows.GENERIC_ADAPTER_ID
                    ) {
                        "该大脑支持工具调用，可执行任务；写操作会先请你确认。（工具调用尚未在真机上验证）"
                    } else {
                        ""
                    }
                note("已切换到${row.title}。回聊天页发下一句话即用它。$toolNote")
            }
            is BrainLoginRows.SwitchDecision.Blocked -> note(decision.reason)
        }
    }

    /**
     * 大脑管理（maintainer 2026-11-11：大脑列表“增删改添都要有”，与项目页
     * 管理同一模式）：全部类型都有动作（见 BrainLoginRows.manageActions）；
     * 点按行仍是切换。设备码会员大脑的“登录…”走设备码流（见
     * startDeviceCodeLogin），“编辑模型…”改模型名；“退出登录”对
     * SIWC 是服务端 signOut，对设备码大脑是清本机 token。
     */
    /**
     * 大脑“···”管理（2026-11-12 起全部类型都有动作，见
     * BrainLoginRows.manageActions；此前只有通用大脑有入口，一线大脑
     * 只看详情）：
     * - “编辑…”：通用大脑进通用表单；一线 API 大脑定位到对应的配置节；
     * - “删除…”：二次确认后彻底删除（配置 + Keystore 凭证 + 注册表注销，
     *   一线大脑另记墓碑防复活）；
     * - “退出登录”（SIWC）：退出 ChatGPT 登录（见 signOut）。
     */
    private fun showBrainManage(config: BrainConfig) {
        val actions = BrainLoginRows.manageActions(config)
        if (actions.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(config.label)
                .setMessage(BrainLoginRows.brainManageDetail(config))
                .setNegativeButton("关闭", null)
                .show()
            return
        }
        // setMessage + setItems 会让管理动作不显示（见 ListDialog）：
        // 大脑“···”点了只剩详情 + 关闭，编辑/删除看不见，走 ListDialog。
        ListDialog.show(
            this,
            config.label,
            BrainLoginRows.brainManageDetail(config),
            actions,
            negativeLabel = "关闭",
        ) { which ->
            when (actions[which]) {
                "编辑…" -> {
                    if (BrainLoginRows.isGenericBrain(config)) {
                        startEditGenericBrain(config)
                    } else {
                        scrollToBrainSection(config)
                    }
                }
                "登录…" -> startDeviceCodeLogin(config.brainId)
                "编辑模型…" -> editDeviceCodeModel(config)
                "删除…" -> confirmDeleteBrain(config)
                "退出登录" -> {
                    if (config.brainId == BrainBootstrap.SIWC_BRAIN_ID) signOut()
                    else signOutDeviceCode(config)
                }
            }
        }
    }

    /**
     * 编辑一线 API 大脑：定位到设置页里对应的配置节（Cloudflare /
     * DeepSeek / Grok / Muse Spark / Claude），展开“高级配置”折叠，
     * 滚动并聚焦该节的第一栏。SIWC、CLI、设备码会员大脑与 Gemini
     * 没有编辑面——SIWC 的管理动作是“退出登录”，CLI 的配置来自
     * Agent 表单，设备码大脑的模型在管理对话框里改，Gemini 只有删除。
     */
    private fun scrollToBrainSection(config: BrainConfig) {
        if (!::screenSettings.isInitialized) return
        val target: EditText? = when (config.brainId) {
            BrainBootstrap.CLOUDFLARE_BRAIN_ID -> if (::cfAccountId.isInitialized) cfAccountId else null
            BrainBootstrap.DEEPSEEK_BRAIN_ID -> if (::dsBaseUrl.isInitialized) dsBaseUrl else null
            BrainBootstrap.GROK_BRAIN_ID -> if (::grokBaseUrl.isInitialized) grokBaseUrl else null
            BrainBootstrap.MUSE_BRAIN_ID -> if (::museBaseUrl.isInitialized) museBaseUrl else null
            BrainBootstrap.CLAUDE_BRAIN_ID -> if (::claudeBaseUrl.isInitialized) claudeBaseUrl else null
            else -> null
        }
        if (target == null) {
            note("“${config.label}”没有单独的配置节。")
            return
        }
        if (::brainAdvancedContainer.isInitialized &&
            brainAdvancedContainer.visibility != View.VISIBLE
        ) {
            brainAdvancedContainer.visibility = View.VISIBLE
            buttonBrainAdvanced.text = "收起高级配置"
        }
        target.post {
            target.requestFocus()
            (screenSettings as? ScrollView)?.requestChildFocus(target, target)
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
        note("正在编辑 ${config.label}：可改模型、地址；Key 留空表示不换，填了才换。改完点该节的保存。")
    }

    /** 正在编辑的通用大脑 id（null = 新增模式）。名称是 brainId 的
     * 来源，编辑时不让改名：改了名会按新名新建一个脑、旧的还留在
     * 列表里——编辑只改模型、地址、Key。 */
    private var editingGenericBrainId: String? = null

    /**
     * 编辑通用大脑：复用下方通用表单，预填该脑的模型与地址，Key 框
     * 留空表示“不换 Key”（填了才换）；名称栏禁用，不让改名。改完点
     * 表单的保存（saveGenericBrain，编辑模式下按原 brainId upsert）。
     */
    private fun startEditGenericBrain(config: BrainConfig) {
        if (!::genericBrainLabel.isInitialized) return
        editingGenericBrainId = config.brainId
        genericBrainLabel.setText(config.label)
        genericBrainLabel.isEnabled = false
        genericBaseUrl.setText(config.baseUrl ?: "")
        genericModel.setText(config.model)
        genericApiKey.text.clear()
        genericBaseUrl.requestFocus()
        note("正在编辑 ${config.label}：可改模型、地址；Key 留空表示不换，填了才换。名称不可改，改完点下方保存。")
    }

    /** 退出通用大脑编辑模式：恢复新增模式（名称栏可用、表单清空）。 */
    private fun exitGenericEditMode() {
        editingGenericBrainId = null
        if (!::genericBrainLabel.isInitialized) return
        genericBrainLabel.isEnabled = true
        genericBrainLabel.text.clear()
        genericBaseUrl.text.clear()
        genericModel.text.clear()
        genericApiKey.text.clear()
    }

    /** 删除大脑：二次确认。文案由 BrainLoginRows.deleteWarning 生成。 */
    private fun confirmDeleteBrain(config: BrainConfig) {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再删除。")
            return
        }
        val isCurrent = components.configStore.currentBrainId() == config.brainId
        val agentNames = agentsUsingBrain(config)
        AlertDialog.Builder(this)
            .setTitle("删除大脑“${config.label}”？")
            .setMessage(BrainLoginRows.deleteWarning(config, isCurrent, agentNames))
            .setPositiveButton("删除") { _, _ -> deleteBrain(config) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 正用这个大脑的 Agent 名：按 authRef 匹配（接线时 wiredRecord
     * 把 Agent 的 authRef 写成大脑的 Key 引用名），再按名字兜底
     * （接线修复前已存的旧记录可能没写 authRef）。
     * 删脑不删 Agent 记录：Agent 留在列表里，之后发消息时路由会
     * 诚实报未接入（AgentChatRouter.resolveModelApi 的 NotRoutable），
     * 不静默回落到别的脑。
     */
    private fun agentsUsingBrain(config: BrainConfig): List<String> {
        if (!::agentStore.isInitialized) return emptyList()
        return agentStore.listAgents()
            .filter { agent ->
                agent.brainKind == BrainKind.MODEL_API &&
                    (agent.authRef == config.authRef ||
                        (agent.adapterId == BrainLoginRows.GENERIC_ADAPTER_ID &&
                            agent.displayName == config.label))
            }
            .map { it.displayName }
            .distinct()
    }

    /**
     * 执行删除大脑（2026-11-12 起全部类型）：删配置 + 删 Keystore 里
     * 对应的 Key + 从引擎注册表注销 + 一线大脑记墓碑（下次启动不播种
     * 复活，自动更新后也不复活——墓碑存在 SharedPreferences 里，更新
     * 只换 APK 不动它）。SIWC 另尽力 revoke 服务端会话、Cloudflare
     * 另尽力 revoke Token（失败不拦删除：Key 已从本机删掉，未配的脑
     * 本来也调不起来）。设备码大脑的 token 与配置同删（authRef
     * "devicecode:<厂商>" 一并清掉）。删的是当前大脑时 current 按
     * store 行为置空，列表刷新后如实显示未选大脑，不自动偷偷切到别的
     * 脑。删脑不删 Agent 记录（见 agentsUsingBrain 的说明）。已删的
     * 一线大脑在列表末尾“＋ 重新添加”。
     */
    private fun deleteBrain(config: BrainConfig) {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再删除。")
            return
        }
        val wasCurrent = components.configStore.currentBrainId() == config.brainId
        val authRef = config.authRef ?: BrainLoginRows.genericAuthRef(config.brainId)
        val isSeedable = config.brainId in BrainBootstrap.SEEDABLE_BRAIN_IDS
        Thread {
            // 服务端会话尽力吊销（只对有服务端会话的两路；失败不拦删除）。
            // revoke 是 suspend 函数，Thread 里用 runBlocking 包一层
            // （与 signOut 同款写法）。
            if (config.brainId == BrainBootstrap.SIWC_BRAIN_ID) {
                try {
                    runBlocking { components.authManager.revoke() }
                } catch (_: Exception) {
                    try {
                        runBlocking { components.authManager.signOut() }
                    } catch (_: Exception) {
                        // ignore
                    }
                }
            }
            if (config.brainId == BrainBootstrap.CLOUDFLARE_BRAIN_ID) {
                try {
                    runBlocking { components.cloudflareProvider.revoke() }
                } catch (_: Exception) {
                    // ignore
                }
            }
            try {
                BrainKeyStore(applicationContext).deleteSecret(authRef)
            } catch (_: Exception) {
                // Key 删不掉不拦删除本身：配置删了行就没了，未配的脑
                // 本来也调不起来；只记日志，不弹给用户。
                android.util.Log.w("MainActivity", "deleteBrain: key delete failed for $authRef")
            }
            components.configStore.deleteConfig(config.brainId)
            if (isSeedable) components.configStore.markBrainDeleted(config.brainId)
            BrainAdapterRegistry.unregisterConfig(config.brainId)
            runOnUiThread {
                if (editingGenericBrainId == config.brainId) exitGenericEditMode()
                refreshBrainStatus()
                val currentNote = if (wasCurrent) "当前大脑已清空，请在列表里另选一个大脑。" else ""
                val restoreNote = if (isSeedable) "列表末尾可重新添加。" else ""
                val keyNote = if (config.authRef.isNullOrBlank()) "配置已删" else "配置与 Key 已删"
                note("已删除 ${config.label}（$keyNote）。$restoreNote$currentNote")
            }
        }.start()
    }

    /** “＋ 重新添加 <一线大脑>”：清墓碑、写回种子配置并刷新列表。 */
    private fun restoreBrain(brainId: String) {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再重新添加。")
            return
        }
        if (!BrainBootstrap.restoreSeededBrain(components.configStore, brainId)) {
            note("无法重新添加该大脑。")
            return
        }
        val label = components.configStore.getConfig(brainId)?.label ?: brainId
        refreshBrainStatus()
        if (DeviceCodeConfigs.isDeviceCodeBrainId(brainId)) {
            note("已重新添加 $label：请在“···”里点“登录…”完成设备码登录，再填好模型。")
        } else if (brainId == BrainBootstrap.GEMINI_BRAIN_ID) {
            note("已重新添加 $label：登录入口待开发者注册 V5 自有的 Desktop OAuth client 后开放。")
        } else {
            note("已重新添加 $label：请在下方对应配置节里填写你的 Key，再点保存。")
        }
    }

    /** SIWC 模型行：当前模型 + 选择按钮可用态（登录且已取到目录才可选）。 */
    private fun refreshSiwcModelRow() {
        if (!::siwcModelStatus.isInitialized) return
        val components = brainComponents ?: return
        val model = components.configStore.getConfig(BrainBootstrap.SIWC_BRAIN_ID)?.model
        siwcModelStatus.text = BrainLoginRows.siwcModelPickerText(model)
        updateSiwcPickEnabled()
    }

    private fun updateSiwcPickEnabled() {
        if (!::buttonPickSiwcModel.isInitialized) return
        val signedIn = brainComponents?.authManager?.isSignedIn() == true
        buttonPickSiwcModel.isEnabled = signedIn && siwcModels.isNotEmpty()
    }

    /**
     * SIWC 模型选择器：调桥接层 [SiwcM2BrainAdapter.listModels]（真实
     * 目录 GET /v1/models），不是只回显已配置模型；失败/空目录原样
     * 显示，不回退写死模型。
     */
    private fun refreshSiwcModels() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再刷新模型。")
            return
        }
        if (!components.authManager.isSignedIn()) {
            note("请先登录 ChatGPT，再刷新模型列表。")
            return
        }
        val bridge = BrainAdapterRegistry.get(SiwcM2BrainAdapter.ADAPTER_ID)
            as? SiwcM2BrainAdapter ?: run {
                note("SIWC 模型桥未注册：请重启 App 后重试。")
                return
            }
        siwcModelStatus.text = "正在读取 ChatGPT 账号模型目录…"
        Thread {
            val result = runBlocking { bridge.listModels() }
            runOnUiThread {
                result.onSuccess { models ->
                    siwcModels = models.map { it.id }
                    val current =
                        components.configStore.getConfig(BrainBootstrap.SIWC_BRAIN_ID)?.model
                    siwcModelStatus.text =
                        BrainLoginRows.siwcModelsText(siwcModels) + "；" +
                            BrainLoginRows.siwcCurrentModelText(current)
                    note("已从你的 ChatGPT 账号读取 ${models.size} 个模型。点“选择模型”切换。")
                }.onFailure { e ->
                    siwcModelStatus.text = "读取模型目录失败：${e.message}"
                    note("读取 ChatGPT 模型目录失败：${e.message}")
                }
                updateSiwcPickEnabled()
            }
        }.start()
    }

    /** SIWC 模型选择框：单选目录，选后写回 BrainConfig.model。 */
    private fun pickSiwcModel() {
        if (siwcModels.isEmpty()) {
            note("请先点“刷新模型列表”。")
            return
        }
        val components = brainComponents ?: return
        val current = components.configStore.getConfig(BrainBootstrap.SIWC_BRAIN_ID)
            ?.model?.takeIf { it.isNotBlank() }
        val checked = siwcModels.indexOf(current)
        var selected = checked
        AlertDialog.Builder(this)
            .setTitle("选择 ChatGPT 模型")
            .setSingleChoiceItems(siwcModels.toTypedArray(), checked) { _, which ->
                selected = which
            }
            .setPositiveButton("保存") { _, _ ->
                if (selected < 0) {
                    note("请先选一个模型。")
                    return@setPositiveButton
                }
                saveSiwcModel(siwcModels[selected])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * SIWC 选模型写回：经 saveConfig 写回 BrainConfig.model，再同步
     * 注册表，本进程无需重启即生效——与 modelStore.saveModel 同路。
     */
    private fun saveSiwcModel(model: String) {
        val components = brainComponents ?: return
        val current = components.configStore.getConfig(BrainBootstrap.SIWC_BRAIN_ID)
            ?: BrainConfig(
                brainId = BrainBootstrap.SIWC_BRAIN_ID,
                kind = BrainKind.SUBSCRIPTION,
                adapterId = BrainBootstrap.SIWC_BRAIN_ID,
                label = "ChatGPT (Sign in with ChatGPT)",
                baseUrl = null,
                model = "",
                authRef = SiwcM2BrainAdapter.AUTH_REF,
            )
        val updated = BrainLoginRows.withSiwcModel(current, model)
        components.configStore.saveConfig(updated)
        BrainAdapterRegistry.registerConfig(updated)
        refreshBrainStatus()
        note("已选模型 $model（已保存，重启仍在）。现在发一句话即用它。")
    }

    /**
     * 通用 MODEL_API“测试连接”：只跑免费 /models 目录探针
     * （DeepSeekApiClient.listModels，GET {baseUrl}/models），不推理、
     * 不计费。探针用的 Key 是输入框里现填的，不先写 Keystore。
     * 文案只写动作（“测试连接”“正在读取可用模型”），不写实现细节。
     */
    private fun testGenericModels() {
        val baseUrl = genericBaseUrl.text.toString().trim().trimEnd('/')
        val apiKey = genericApiKey.text.toString().trim()
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            note("请先填写 Base URL 与 API Key，再测试连接。")
            return
        }
        if (!BrainConfigValidator.isAllowedBaseUrl(baseUrl)) {
            note("Base URL 只允许 https（本地模型可用 http://127.0.0.1 或 http://localhost）。")
            return
        }
        genericModels.text = "正在读取可用模型…"
        Thread {
            val result = runBlocking { DeepSeekApiClient().listModels(apiKey, baseUrl) }
            runOnUiThread {
                result.onSuccess { models ->
                    genericModels.text = if (models.isEmpty()) {
                        "该服务没有返回任何模型；可手动填写模型 ID。"
                    } else {
                        "可用模型：" + models.joinToString("、") { it.id }
                    }
                    if (models.isNotEmpty() && genericModel.text.isBlank()) {
                        genericModel.setText(models.first().id)
                    }
                    note("测试连接成功：读到 ${models.size} 个模型。")
                }.onFailure { e ->
                    genericModels.text = "测试连接失败：${e.message}"
                    note("测试连接失败：${e.message}")
                }
            }
        }.start()
    }

    /**
     * 通用 MODEL_API 保存：复用 saveCloudflare/saveDeepSeek 的路径——
     * Key 只进 Keystore（authRef 只存引用名）、校验走
     * BrainConfigValidator、保存后清空 Key 输入框。
     * 注意：推理已接线（GenericOpenAiBrainAdapter 已注册），Agent
     * 路由可用；已配置的通用大脑也可点按设为全局大脑（maintainer
     * 2026-11-11 决定放开切换），第二阶段起支持工具调用，切过
     * 去可执行任务。保存后不自动设为当前脑（新增与编辑都不偷切）。
     * 编辑模式（editingGenericBrainId 非空）下按原 brainId upsert，
     * 名称栏已禁用，不会因改名新建一个脑。
     */
    private fun saveGenericBrain() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再保存。")
            return
        }
        val label = genericBrainLabel.text.toString().trim()
        val baseUrl = genericBaseUrl.text.toString().trim().trimEnd('/')
        val model = genericModel.text.toString().trim()
        val apiKey = genericApiKey.text.toString().trim()
        // 编辑模式按原 brainId 存（名称栏已禁用，label 本来就是原名）；
        // 新增模式按 label slug 化出 brainId。
        val brainId = editingGenericBrainId ?: BrainLoginRows.genericBrainId(label)
        val authRef = BrainLoginRows.genericAuthRef(brainId)
        val keyStore = try {
            BrainKeyStore(applicationContext)
        } catch (e: Exception) {
            note("无法打开本机 Keystore，未保存：${e.message}")
            return
        }
        val hasStoredKey = keyStore.getSecret(authRef) != null
        val errors = BrainLoginRows.validateGenericForm(label, baseUrl, model, apiKey, hasStoredKey)
        if (errors.isNotEmpty()) {
            note("未保存：" + errors.joinToString("；"))
            return
        }
        Thread {
            if (apiKey.isNotEmpty()) keyStore.putSecret(authRef, apiKey)
            val current = components.configStore.getConfig(brainId)
                ?: BrainConfig(
                    brainId = brainId,
                    kind = BrainKind.MODEL_API,
                    adapterId = BrainLoginRows.GENERIC_ADAPTER_ID,
                    label = label,
                    baseUrl = baseUrl,
                    model = model,
                    authRef = authRef,
                    fallbackBrainIds = emptyList(),
                )
            // 保存后立即注册进引擎的注册表：引擎按 brainId 找配置走注册表，
            // 只存不注册要等下次启动才生效（2026-11-11 添加 Agent 修复）。
            val saved = current.copy(
                kind = BrainKind.MODEL_API,
                adapterId = BrainLoginRows.GENERIC_ADAPTER_ID,
                label = label,
                baseUrl = baseUrl,
                model = model,
                authRef = authRef,
            )
            components.configStore.saveConfig(saved)
            runOnUiThread {
                BrainAdapterRegistry.registerConfig(saved)
                // 编辑模式保存后退出编辑（恢复新增模式、表单清空）；
                // 新增模式只清空 Key 框（沿用旧行为）。
                if (editingGenericBrainId != null) {
                    exitGenericEditMode()
                } else {
                    genericApiKey.text.clear()
                }
                refreshBrainStatus()
                // 提示按实写（2026-11-12）：本机模型可无 Key——只在真的
                // 存了 Key（本次填了、或此前已存）时说“Key 已存”，无 Key
                // 的本机模型不说这句。
                val keySaved = apiKey.isNotEmpty() || hasStoredKey
                val keyNote = if (keySaved) "（Key 已存本机 Keystore）" else "（本机模型，无需 Key）"
                note("已保存 $label$keyNote。${BrainLoginRows.GENERIC_SAVED_NOTE}")
            }
        }.start()
    }

    /** CLI 大脑行：身份是 cliBinary，状态取上次验证的写回值。
     * 设置清理 2（2026-11-11）：未配 CLI 二进制时整节隐藏（见
     * [BrainLoginRows.shouldShowCliSection]），不拿“未发现”占设置
     * 页；提供方只写二进制名，不加“不是模型名”之类的实现说明。 */
    private fun refreshCliRow() {
        if (!::cliBrainStatus.isInitialized) return
        val components = brainComponents ?: return
        val config = components.configStore.getConfig(BrainBootstrap.CLI_BRAIN_ID)
        if (::cliBrainSection.isInitialized) {
            cliBrainSection.visibility =
                if (BrainLoginRows.shouldShowCliSection(config)) View.VISIBLE else View.GONE
        }
        if (config == null) {
            cliBrainStatus.text = "CLI 会员额度：未配置"
            cliBrainBinary.text = "提供方：未选择"
        } else {
            cliBrainStatus.text = BrainLoginRows.cliStatusText(config)
            cliBrainBinary.text = "提供方：" +
                (config.cliBinary?.takeIf { it.isNotBlank() } ?: "未选择")
        }
    }

    /**
     * CLI“验证一次”：只在用户明确点按时调 validateCredentials()，
     * 跑一次最小真实调用，会消耗一次会员调用——按钮文案已写明，
     * 启动时、发现时绝不自动跑。结果由适配器写回
     * loginState/lastTestedAt/lastTestSummary。
     */
    private fun verifyCliBrain() {
        val components = brainComponents ?: run {
            note("大脑还在加载，请稍候再验证。")
            return
        }
        val adapter = BrainAdapterRegistry.get(BrainBootstrap.CLI_BRAIN_ID)
            as? CliMembershipBrainAdapter ?: run {
                note("CLI 大脑未注册：请重启 App 后重试。")
                return
            }
        val binary = components.configStore.getConfig(BrainBootstrap.CLI_BRAIN_ID)
            ?.cliBinary?.takeIf { it.isNotBlank() } ?: run {
                note("未选择 CLI：请先在 Agent 页添加一个 CLI Agent，再回来验证。")
                return
            }
        buttonVerifyCli.isEnabled = false
        cliBrainStatus.text = "正在验证 $binary…"
        Thread {
            val result = runBlocking { adapter.validateCredentials() }
            runOnUiThread {
                buttonVerifyCli.isEnabled = true
                refreshBrainStatus()
                result.onSuccess {
                    note("CLI 大脑验证成功：$binary 登录已确认。")
                }.onFailure { e ->
                    note("CLI 大脑验证未通过：${e.message}")
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Theme / helpers
    // ------------------------------------------------------------------

    /**
     * V5 C2-3-R1：主题 id 持久化在 themePrefs（KEY_THEME）里；未存过时
     * 默认浅色极简（maintainer 2026-11-10 02:40 定：第一步默认浅色极简，
     * V4.3 图第一屏 Claude 风格）。深色档 = V5 原型图深色霓虹 token 集。
     * 注意：顶栏主题按钮已删（见 bindShell），cycleTheme() 的入口已接
     * 在抽屉“界面与主题”（R2）；第二步的主题选择器 UI 不在此处。
     */
    private fun applyStoredTheme() {
        val mode = themePrefs.getString(KEY_THEME, THEME_LIGHT)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    /** 当前主题名（抽屉“界面与主题”行尾显示，与 cycleTheme() 的档位文案一致）。 */
    private fun currentThemeLabel(): String = when (themePrefs.getString(KEY_THEME, THEME_LIGHT)) {
        THEME_LIGHT -> "浅色"
        THEME_DARK -> "深色"
        else -> "跟随系统"
    }

    /**
     * 抽屉底部用户行标签。App 内没有登录用户名可取（不写死真人姓名，
     * 见首页问候的同款处理），只显示登录状态；有用户档案后再接真实用户名。
     */
    private fun drawerUserLabel(): String = try {
        if (brainComponents?.authManager?.isSignedIn() == true) "已登录" else "未登录"
    } catch (_: Exception) {
        "未登录"
    }

    /**
     * 打开抽屉时刷新角标：运行中任务数读任务库（后台线程），待审批数
     * 读审批门（内存字段）。任一数读不到为 null——抽屉里不显示角标，
     * 绝不伪造数字。
     */
    private fun refreshDrawerBadges() {
        val components = brainComponents
        Thread {
            val running = try {
                components?.taskStore?.listTasks(null, null)?.let { DrawerBadges.runningCount(it) }
            } catch (_: Exception) {
                null
            }
            val pending = try {
                DrawerBadges.pendingCount(toolApprovalGate.pendingApproval() != null)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                drawerView?.setBadges(DrawerBadges(runningTasks = running, pendingApprovals = pending))
            }
        }.start()
    }

    private fun cycleTheme() {
        val current = themePrefs.getString(KEY_THEME, THEME_LIGHT)
        val next = when (current) {
            THEME_SYSTEM -> THEME_LIGHT
            THEME_LIGHT -> THEME_DARK
            else -> THEME_SYSTEM
        }
        themePrefs.edit().putString(KEY_THEME, next).apply()
        applyStoredTheme()
        val label = when (next) {
            THEME_LIGHT -> "浅色"
            THEME_DARK -> "深色"
            else -> "跟随系统"
        }
        toast("主题：$label")
    }

    private fun note(message: String) {
        chatViewModel.note(message)
    }

    private fun hint(value: String): TextView = UiKit.text(this, value, 11f, R.color.text_tertiary).apply {
        setBackgroundResource(R.drawable.bg_system_note)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 阶段 3（U1/U2）：复制文本到剪贴板（逐轮复制、复制代码、复制文件内容共用）。 */
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("WhereverCode", text))
        toast("已复制")
    }

    private fun dp(value: Int): Int = UiKit.dp(this, value)

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (onboardingView != null) {
            // 引导中返回键 = 回上一屏；欢迎页已无上一屏时走系统返回
            // （退出 Activity，不记完成，下次启动引导还会出现——这是
            // “关闭”出口；“稍后再看”才是记完成的退出）。
            if (onboardingController.back()) {
                onboardingView?.refresh()
            } else {
                super.onBackPressed()
            }
            return
        }
        if (drawerOpen) {
            closeDrawer()
            return
        }
        if (isSettingsEntry(currentEntry)) {
            hideSettings()
            return
        }
        if (taskDetailVisible) {
            hideTaskDetail()
            return
        }
        if (currentEntry == DrawerController.ID_PROJECTS && projectController.hasCodeOpen()) {
            projectController.closeCode()
            return
        }
        // 后退（2026-11-11，maintainer：从项目里回来有返回）：正在浏览项目
        // 文件夹树时，系统返回键先回项目列表，而不是直接跳去聊天页。
        if (currentEntry == DrawerController.ID_PROJECTS && projectController.hasTreeOpen()) {
            projectController.showProjectList()
            return
        }
        if (currentEntry == DrawerController.ID_NEW_CONVERSATION && inConversation) {
            backToSessionList()
            return
        }
        if (currentEntry != DrawerController.ID_NEW_CONVERSATION) {
            showEntry(DrawerController.ID_NEW_CONVERSATION)
            return
        }
        super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        // 阶段 3（U4）：回前台时补一次审批 ticker（审批可能在后台到达）。
        pokeApprovalTicker()
    }

    override fun onDestroy() {
        // The turn lives in ChatViewModel, not the Activity: detaching the
        // gate must not stop an in-flight turn during a theme/config change.
        // A permanently finishing Activity lets ViewModel.onCleared() stop it.
        approvalHandler.removeCallbacks(approvalTick)
        approvalTickerActive = false
        consoleHandler.removeCallbacks(consoleTick)
        consoleTickerActive = false
        // 控制台 WS 订阅与 ticker 同停（onDestroy 不走 stopConsoleTicker，
        // 这里直接停，避免 Activity 销毁后工作线程还连着 Hand）。
        consoleWsFeed?.stop()
        consoleWsFeed = null
        // 阶段 B：停远程任务泵（只停取新任务，不中断已在跑的一次执行）。
        remoteTaskPump?.stop()
        // P2 自动执行：停流水线泵（只停取新任务，不中断已在跑的一次调用）。
        brainComponents?.pipelineRunner?.stop()
        toolApprovalGate.detach(this)
        super.onDestroy()
    }

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ENTRY = "ui_entry"
        private const val KEY_IN_CONVERSATION = "ui_in_conversation"
        private const val KEY_TASK_DETAIL = "ui_task_detail"
        /** V5 C2-3-R3：引导持久化（SharedPreferences 文件名 + 键）。 */
        private const val PREFS_ONBOARDING = "onboarding_v5"
        private const val KEY_ONBOARDING_DONE = "onboarding_completed"
        private const val KEY_ONBOARDING_DIRECTION = "onboarding_direction"
        private const val KEY_ONBOARDING_STEP = "ui_onboarding_step"
        /** V5 按主流改（maintainer 2026-11-11）：“+”菜单网页搜索开关的持久化。 */
        private const val PREFS_CHAT = "wherevercode_chat_prefs"
        private const val KEY_WEB_SEARCH_ENABLED = "web_search_enabled"
        private const val THEME_SYSTEM = "system"
        private const val THEME_LIGHT = "light"
        private const val THEME_DARK = "dark"
    }
}
