package wherevercode.agent.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wherevercode.agent.AgentRunner
import wherevercode.agent.AndroidApprovalGate
import wherevercode.agent.brain.AgentChatRoute
import wherevercode.agent.brain.AgentChatRouter
import wherevercode.agent.brain.BrainAdapterRegistry
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.Message
import wherevercode.agent.brain.SettledTaskEvidence
import wherevercode.agent.brain.SkillRefiner
import wherevercode.agent.brain.siwc.BrainBootstrap
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.project.DispatchFolderSource
import wherevercode.agent.core.project.GitRepoRef
import wherevercode.agent.core.project.folderFallbackReason
import wherevercode.agent.core.agent.ManusChatEvent
import wherevercode.agent.core.agent.ManusChatPort
import wherevercode.agent.core.agent.ManusTaskAttachment
import wherevercode.agent.core.agent.PlatformAgentChatBridge
import wherevercode.agent.core.agent.PlatformChatEvent
import wherevercode.agent.core.agent.PlatformChatPort
import wherevercode.agent.core.task.EvalLogRecorder
import wherevercode.agent.core.task.EvalOutcome
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.task.TaskStepStatus
import wherevercode.agent.core.task.TriageMode
import wherevercode.agent.core.task.TriageRunner
import wherevercode.agent.core.control.ControlAction
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteControlSender
import wherevercode.agent.core.mcp.RemoteTarget

data class SendResult(
    val accepted: Boolean,
    val message: String? = null,
)

/**
 * Owns one chat session across Activity recreation. The engine turn runs in
 * this ViewModel's own scope, so rotating the device or switching the theme
 * does not cancel an in-flight turn or lose the visible transcript.
 *
 * It reuses the existing production machinery exactly as-is:
 * [BrainBootstrap.BrainComponents.engine], the per-turn
 * [wherevercode.agent.brain.TurnController], the process-wide
 * [AndroidApprovalGate], and the fallback [AgentRunner].
 */
class ChatViewModel : ViewModel() {

    companion object {
        /** 任务轮次观察轮询间隔（与 RemoteTaskPump 的 500ms 同口径）。 */
        private const val TASK_TURN_POLL_MS: Long = 500

        /**
         * 任务轮次观察时限（与 RemoteTaskDispatcher 的 600 秒超时同口径）。
         * 超时后聊天先行结束（任务仍在后台跑，任务页可见），不伪造完成、
         * 不杀任务。
         */
        private const val TASK_TURN_OBSERVE_TIMEOUT_MS: Long = 600_000

        /**
         * 远端轮次回复的归属头（2026-11-12 回复归属修复）：每条远端
         * Agent 回复正文前加一行，写清它属于哪个任务——任务编号前
         * 8 位 + 轮次标题（用户原话前 40 字，与任务行标题同一口径）。
         * 聊天、存库消息、任务结果三处一致，串任务时一眼分清这是
         * 哪轮的回复、不是别的任务的结果（2026-11-12 真机事故：
         * Codex 的“可靠性修复”回复被当成团队任务的结果，maintainer
         * 当场懵了）。标题取自用户原话，空原话时写“远端轮次”。
         */
        internal fun replyAttributionHeader(taskId: String, prompt: String): String {
            val title = prompt.trim().take(40).ifBlank { "远端轮次" }
            return "【任务 ${taskId.take(8)} · $title】"
        }
    }

    private var timeline = ChatTimeline()
    private val _state = MutableLiveData(timeline.snapshot())
    val state: LiveData<ChatUiState> = _state

    /**
     * 阶段 3（A1 P1/U5）：按会话保存的时间线。切换会话时当前时间线
     * 存进 [timelines]，目标会话的时间线取出来（没有就新建），各会话
     * 历史互不丢。引擎侧的 conversationId 与会话 id 是同一个值。
     */
    private val timelines = HashMap<String, ChatTimeline>()

    /**
     * 当前会话的显示名（会话列表用）。默认按“<名>会话”走大脑显示名
     * 设置（[BrainDisplayName.sessionLabel]，默认“WhereverCode会话”）；
     * 大脑改名后由宿主调 [setDefaultSessionLabel] 同步。
     */
    var defaultSessionLabel: String = BrainDisplayName.sessionLabel(null)
        private set

    private var currentSessionLabel: String = defaultSessionLabel

    /**
     * 大脑显示名改了之后同步默认会话标签；当前正在默认会话时连同改名，
     * 已切到 Agent/任务会话的不动。
     */
    fun setDefaultSessionLabel(label: String) {
        val wasDefault = isDefaultSession()
        defaultSessionLabel = label
        if (wasDefault) currentSessionLabel = label
    }

    /**
     * 阶段 3（U4）：上一轮是否以 PAUSED 结束。恢复以它为准绳——
     * 引擎停轮后控制态保持 PAUSED 直到恢复或新轮次 begin() 复位。
     */
    private var pausedTurn: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = AtomicBoolean(false)
    private val lastPublishMs = AtomicLong(0L)

    @Volatile
    private var components: BrainBootstrap.BrainComponents? = null

    @Volatile
    private var approvalGate: AndroidApprovalGate? = null

    @Volatile
    private var runner: AgentRunner? = null

    /**
     * 阶段 B（V5 紧急开发）：远端聊天接线（attachRemoteChat 注入，
     * MainActivity 在组件加载后接上）。未注入时远端 Agent 一律
     * NotRoutable，绝不悄悄走大脑。
     */
    @Volatile
    private var remotePort: RemoteAgentChatPort? = null

    @Volatile
    private var remoteControlSender: RemoteControlSender? = null

    @Volatile
    private var remoteTargetResolver: ((AgentRecord) -> RemoteTarget?)? = null

    @Volatile
    private var remoteAgentCheck: ((AgentRecord) -> Boolean)? = null

    /**
     * V5 修复（2026-11-11，能用的 Agent 显示“未验证”）：远端真实
     * 往返成功回调（MainActivity 接上）。一次真实往返（Agent 真回了
     * 内容）是比 hello 探测更强的可用证据：回调方把该 Agent 的记录标
     * 成已验证（LOGGED_IN），状态标签才反映对方的真实情况。只在
     * 终态 DONE 且助手正文非空时调用；空响应/失败/取消不调用——
     * 没回话的 Agent 不贴已验证。
     */
    @Volatile
    var onRemoteRoundSucceeded: ((AgentRecord) -> Unit)? = null

    /** 正在跑的远端轮次（null = 当前轮次不是远端轮次）。 */
    @Volatile
    private var remoteTurnJob: Job? = null

    @Volatile
    private var remoteTurnTaskId: String? = null

    /**
     * V5 修复（2026-11-11，CodeBuddy 卡死轮次）：实际发给 Hand 的
     * 远端任务 id（runAgent 的 taskId 参数）。2026-11-12 回复归属
     * 修复后，远端轮次必有任务行（建不出行就中止、不发 Hand），
     * [remoteRunTaskId] 与 [remoteTurnTaskId] 恒相等（随机 UUID
     * 回落已删）；控制指令一律用它。
     */
    @Volatile
    private var remoteRunTaskId: String? = null

    @Volatile
    private var remoteTurnTarget: RemoteTarget? = null

    /** 远端轮次是否已暂停（流保持连接，服务端 SIGSTOP）。 */
    @Volatile
    private var remoteTurnPaused: Boolean = false

    /** 远端轮次是否因用户改目标而结束（终态按 GOAL_CHANGED 落）。 */
    @Volatile
    private var remoteTurnGoalChanged: Boolean = false

    /**
     * 任务轮次（V5 修复，2026-11-11）：任务型指令走任务通道时的状态。
     * 与远端轮次对称——同一套 active 互斥、同一套暂停/停止/改目标；
     * 不同的是执行体不是一次 runAgent 调用，而是任务行本身
     * （RemoteTaskPump 认领执行），聊天只观察任务行、回填进度/结果。
     */
    @Volatile
    private var taskTurnJob: Job? = null

    @Volatile
    private var taskTurnTaskId: String? = null

    @Volatile
    private var taskTurnAgent: AgentRecord? = null

    @Volatile
    private var taskTurnTarget: RemoteTarget? = null

    /** 任务轮次是否已暂停（远端 SIGSTOP，观察流不断）。 */
    @Volatile
    private var taskTurnPaused: Boolean = false

    /** 任务轮次是否因用户改目标而结束（终态按 GOAL_CHANGED 落）。 */
    @Volatile
    private var taskTurnGoalChanged: Boolean = false

    /**
     * 阶段 B：接上远端聊天（Hand 直连）。[controlSender] 可为 null
     * （测试替身/未接线时）：此时远端轮次仍可跑，但暂停/继续/停止/
     * 改目标只能取消本地流、发不出远端控制指令——pause/stop 等会
     * 如实说明，不伪装已控制远端。
     */
    fun attachRemoteChat(
        port: RemoteAgentChatPort,
        controlSender: RemoteControlSender?,
        targetResolver: (AgentRecord) -> RemoteTarget?,
        isRemoteAgent: (AgentRecord) -> Boolean,
    ) {
        this.remotePort = port
        this.remoteControlSender = controlSender
        this.remoteTargetResolver = targetResolver
        this.remoteAgentCheck = isRemoteAgent
    }

    /**
     * 阶段 C（V5 紧急开发）：Manus 聊天接线（attachManusChat 注入，
     * MainActivity 在组件加载后接上）。未注入或 Key 未配置时 Manus
     * Agent 一律 NotRoutable，绝不悄悄走大脑。
     *
     * 通用对话模式（maintainer 2026-11-10 22:22）：Manus 只是后端适配，
     * 挂在同一套路由/会话/历史之下——同一 timelines、同一
     * ConversationStore、同一轮次→任务镜像；不另做 Manus 专属
     * 会话模型、历史或聊天流程。
     */
    @Volatile
    private var manusPort: ManusChatPort? = null

    @Volatile
    private var manusConfiguredCheck: ((AgentRecord) -> Boolean)? = null

    /** 正在跑的 Manus 轮次（null = 当前轮次不是 Manus 轮次）。 */
    @Volatile
    private var manusTurnJob: Job? = null

    @Volatile
    private var manusTurnTaskId: String? = null

    /**
     * Manus 任务轮次的分派任务行 id（startManusTaskTurn 建的
     * ChatTaskDispatcher 行；currentTurnTaskId() 优先返回它，
     * 暂停/停止/改目标/恢复据此落行）。
     */
    @Volatile
    private var manusTaskTurnTaskId: String? = null

    @Volatile
    private var manusTurnConversationId: String? = null

    @Volatile
    private var manusTurnAgent: AgentRecord? = null

    /** Manus 轮次是否已暂停（任务已 stopTask，下一条消息可继续同一任务）。 */
    @Volatile
    private var manusTurnPaused: Boolean = false

    /** Manus 轮次是否因用户改目标而结束（终态按 GOAL_CHANGED 落）。 */
    @Volatile
    private var manusTurnGoalChanged: Boolean = false

    fun attachManusChat(
        port: ManusChatPort,
        isManusConfigured: (AgentRecord) -> Boolean,
    ) {
        this.manusPort = port
        this.manusConfiguredCheck = isManusConfigured
    }

    /**
     * 平台 Agent 聊天接线（attachPlatformChat 注入，MainActivity 在
     * 组件加载后接上）。未注入或平台凭证未配置时平台 Agent 一律
     * NotRoutable，绝不悄悄走大脑。
     *
     * 与 Manus 同款：平台 Agent 只是后端适配，挂在同一套路由/会话/
     * 历史之下——同一 timelines、同一 ConversationStore、同一轮次→
     * 任务镜像；不另做平台 Agent 专属会话模型、历史或聊天流程。
     */
    @Volatile
    private var platformPort: PlatformChatPort? = null

    @Volatile
    private var platformConfiguredCheck: ((AgentRecord) -> Boolean)? = null

    /** 正在跑的平台轮次（null = 当前轮次不是平台轮次）。 */
    @Volatile
    private var platformTurnJob: Job? = null

    @Volatile
    private var platformTurnTaskId: String? = null

    @Volatile
    private var platformTurnAgent: AgentRecord? = null

    /** 平台轮次是否已暂停（本地等待已取消；已发出的消息仍在平台频道里）。 */
    @Volatile
    private var platformTurnPaused: Boolean = false

    /** 平台轮次是否因用户改目标而结束（终态按 GOAL_CHANGED 落）。 */
    @Volatile
    private var platformTurnGoalChanged: Boolean = false

    fun attachPlatformChat(
        port: PlatformChatPort,
        isPlatformAgentConfigured: (AgentRecord) -> Boolean,
    ) {
        this.platformPort = port
        this.platformConfiguredCheck = isPlatformAgentConfigured
    }

    @Volatile
    private var conversationId: String? = null

    /**
     * 主流补齐（2026-11-11）：会话挂在项目上。
     * 主流脊柱是会话 id → 任务 → 项目 = 工作区；一个会话可以挂在一个
     * 项目上（[currentProjectId]），切会话时恢复该会话的项目绑定，
     * 换项目不丢会话（会话按 id 存，见 timelines）。任务与对话双向挂 id
     * 本来就有（TaskRecord.sessionId / SessionMessage.taskId），这里补的
     * 是会话 → 项目这一向。
     * 绑定存在内存 [sessionProjects]（会话 id → 项目 id），经
     * [onSessionProjectsChanged] 回调由调用方持久化（ChatHistoryStore）。
     */
    @Volatile
    private var currentProjectId: String? = null
    private val sessionProjects = LinkedHashMap<String, String>()

    /** 会话 → 项目绑定变化时回调（调用方负责持久化）。 */
    var onSessionProjectsChanged: ((Map<String, String>) -> Unit)? = null

    /**
     * 当前聊天绑定的项目 id（调用方 MainActivity 提供，读全局聊天绑定
     * ChatProjectBinding.projectId；与 [gitHostProjectRefProvider] 同款
     * provider）。派活丢绑定根因（2026-11-12，任务 20106890）：
     * 项目页点选 / 聊天选项目只写全局绑定，不写会话绑定；[currentProjectId]
     * 字段只由“打开项目浏览家文件夹”那条少用流程（bindCurrentSessionToProject）
     * 或 switchSession 显式传 projectId 时才填，于是已绑定项目的对话派活
     * 时字段是 null、任务行丢了 projectId，而横幅读的是全局绑定、照常显示项目。
     * 所以这里是唯一口径：会话级绑定缺失时回落到全局聊天绑定。
     */
    var chatProjectIdProvider: (() -> String?)? = null

    /**
     * 当前会话挂在哪个项目上。优先级：会话级绑定 > 全局聊天绑定；
     * 两个都没有为 null（派活仍允许，走回落，D5 口径不变）。
     * provider 抛错时当无全局绑定处理，不让一次取绑定失败毁掉派活
     * （与 ToolRegistry.handleDispatch 同款兜底）。
     */
    fun currentProjectId(): String? {
        currentProjectId?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            chatProjectIdProvider?.invoke()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 大脑/Agent 边界（maintainer 2026-11-12 修订定稿，一个对话、一个说话者）：
     * 会话 → 直连 Agent 开关（会话 id → true/false，显式选择）。
     *
     * 修订后的分法——Agent 对话里大脑默认就是关闭的：消息直接发给那
     * 个 Agent，大脑不说话、不拦截、不派活，由 Agent 自己接消息、自己
     * 干活。开关是显式改回派活的控制：关掉直连 = 该对话改走大脑幕后
     * 派活（任务型指令建派活任务行、走泵、进度回填），适合需要泵/
     * 租约/审查的复杂任务；开着直连 = 消息直达 Agent，活由轮次镜像
     * 记进任务系统（见 TurnTaskRecorder）。
     *
     * 只对远端设备 Agent 会话有效（设备 Agent 才有“直连”可言）；
     * 大脑对话、Manus、平台 Agent 不受影响。开关状态按会话持久化
     * （经 [onDirectAgentModeChanged] 回调由调用方持久化，
     * ChatHistoryStore），切会话不串。未显式选过的会话按默认（直连开）。
     */
    private val directAgentSessions = LinkedHashMap<String, Boolean>()

    /** 会话 → 直连开关变化时回调（调用方负责持久化）。 */
    var onDirectAgentModeChanged: ((Map<String, Boolean>) -> Unit)? = null

    /**
     * 当前会话是否直连 Agent（大脑关闭）。远端设备 Agent 对话默认直
     * 连（true），除非用户显式关过；无会话、非远端设备 Agent 对话时
     * 为 false（直连不适用）。
     */
    fun isDirectAgentMode(): Boolean {
        val convId = conversationId ?: return false
        if (selectedAgent?.deviceId == null) return false
        return directAgentSessions[convId] ?: true
    }

    /**
     * 开/关当前会话的直连 Agent。只在当前会话有远端设备 Agent 时有
     * 效；其他会话调用无效（不记、不回调）。开关后刷新时间线，让横
     * 幅与状态行立刻反映新状态。显式选择都记下来（开与关都存），
     * 持久化后重启仍按用户的选择走，不回默认。
     */
    fun setDirectAgentMode(on: Boolean): Boolean {
        if (selectedAgent?.deviceId == null) return false
        val convId = conversationId ?: UUID.randomUUID().toString().also {
            conversationId = it
        }
        directAgentSessions[convId] = on
        onDirectAgentModeChanged?.invoke(directAgentSessions.toMap())
        publish(true)
        return true
    }

    /** 启动时恢复会话 → 直连开关（调用方从 ChatHistoryStore 读出后传入）。 */
    fun restoreDirectAgentSessions(flags: Map<String, Boolean>) {
        directAgentSessions.clear()
        directAgentSessions.putAll(flags.filterKeys { it.isNotBlank() })
    }

    /**
     * 大脑/Agent 边界（maintainer 2026-11-12）：显式标任务——用户发消息时
     * 明确说“这句是任务”，不用靠 App 猜。输入框里的“任务”开关置起
     * 后，下一条消息带 forceTask 发出（一定建派活任务行，不受意图
     * 分类与直连开关影响）。发送接受后调用方清掉（见 MainActivity
     * sendPrompt）；发送被拒时保留，下次重发仍带。只影响下一条消息，
     * 不持久化、不跨会话。
     */
    @Volatile
    private var forceTaskNext: Boolean = false

    /** 下一条消息是否被显式标为任务。 */
    fun isForceTaskNext(): Boolean = forceTaskNext

    /** 点按输入框“任务”开关：切换下一条消息的显式标任务，返回新状态。 */
    fun toggleForceTaskNext(): Boolean {
        forceTaskNext = !forceTaskNext
        publish(true)
        return forceTaskNext
    }

    /** 发送接受后清掉显式标任务（只影响一条消息）。 */
    fun clearForceTaskNext() {
        forceTaskNext = false
        publish(true)
    }

    /**
     * P1 建议行待用的路由决策（2026-11-11）：用户在“选 Agent”里点了
     * 建议行后记下（决策 id + 被建议的 Agent id），下一条任务型指令
     * 建行成功时由 startTaskTurn 把决策与任务绑起来（供 eval 回看
     * “建议准不准”）。只认同一 Agent 的任务（切到别的 Agent 就不对
     * 上号）；手动点普通行、开新对话时由调用方清掉。
     */
    private var pendingRouting: PendingRouting? = null

    private data class PendingRouting(val decisionId: String, val agentId: String)

    /** 用户点了建议行：记下决策，下一条任务建行时绑定。 */
    fun acceptSuggestedAgent(decisionId: String, agentId: String) {
        pendingRouting = PendingRouting(decisionId, agentId)
    }

    /** 用户手动点了普通 Agent 行：建议作废，不再绑定。 */
    fun clearPendingRouting() {
        pendingRouting = null
    }

    /** 某会话挂在哪个项目上（未绑定为 null）。 */
    fun projectIdForSession(sessionId: String): String? = sessionProjects[sessionId]

    /** 启动时恢复会话 → 项目绑定（调用方从 ChatHistoryStore 读出后传入）。 */
    fun restoreSessionProjects(bindings: Map<String, String>) {
        sessionProjects.clear()
        sessionProjects.putAll(bindings.filterKeys { it.isNotBlank() })
        currentProjectId = conversationId?.let { sessionProjects[it] }
    }

    /**
     * 把当前会话绑到一个项目上（打开项目时调用）。
     * 绑定只记会话 id → 项目 id，不动时间线、不清历史；换项目不丢会话。
     */
    fun bindCurrentSessionToProject(projectId: String) {
        val convId = conversationId ?: return
        currentProjectId = projectId
        sessionProjects[convId] = projectId
        onSessionProjectsChanged?.invoke(sessionProjects.toMap())
    }

    /**
     * 阶段 A（V5 紧急开发）：当前会话选中的 Agent（openAgentSession
     * 传入；openSession 从会话账本 members 恢复）。null = 默认会话，
     * 走全局大脑。切换会话/新对话时一并更新，绝不残留到别的会话。
     */
    var selectedAgent: AgentRecord? = null
        private set

    /**
     * git 家（2026-11-11）：当前聊天项目的 git 家（由 MainActivity
     * 按打开的项目提供；无 git 家时返回 null）。send() 在任务型指令上
     * 用它改道：git 家的任务不进远端 CLI 任务通道，由本机大脑经该站
     * API 执行。
     */
    var gitHostProjectRefProvider: (() -> GitRepoRef?)? = null

    /**
     * V5 跨设备开发（2026-11-12，D4）：项目 id → 显示名（宿主注入，
     * 如 MainActivity 经 ProjectStore 查）。远端轮次回落时时间线备注
     * 要写清项目名；未注入或查不到时备注用项目 id，未绑定时写“未绑定项目”。
     */
    var projectNameProvider: ((String) -> String?)? = null

    /** GitHub 闭环（2026-11-11）：新对话时调用方清空聊天项目。 */
    var onNewConversation: (() -> Unit)? = null

    /**
     * 阶段 1（U3）：轮次 -> 任务镜像。attach() 时由 components 建出；
     * 为 null 时轮次只走聊天（绝不因镜像失败打断聊天）。
     */
    @Volatile
    private var turnRecorder: TurnTaskRecorder? = null

    fun attach(components: BrainBootstrap.BrainComponents, gate: AndroidApprovalGate) {
        this.components = components
        this.approvalGate = gate
        this.turnRecorder = TurnTaskRecorder(
            components.taskStore,
            components.taskScheduler,
            components.taskStepStore,
        )
    }

    /** 当前轮次对应的任务 id（无轮次/无任务行时为 null），供任务页逐行暂停委托。 */
    fun currentTurnTaskId(): String? =
        manusTaskTurnTaskId ?: turnRecorder?.currentTaskId()

    fun attachRunner(agentRunner: AgentRunner) {
        this.runner = agentRunner
    }

    fun stateNow(): ChatUiState = timeline.snapshot()

    /** Append a standalone, honest notice (e.g. wiring failure). */
    fun note(message: String) {
        timeline.beginSystemTurn(message)
        publish(true)
    }

    fun isRunning(): Boolean = active.get()

    fun newConversation(): Boolean {
        if (active.get()) {
            if (!releaseZombieTurnIfAny()) {
                timeline.beginSystemTurn("上一轮还在执行，先停止或等它结束，再开新对话。")
                publish(true)
                return false
            }
            // 僵尸轮次已清，继续往下开新对话。
        }
        // 旧会话的时间线留在 timelines 里（切换回来还能看到）；当前
        // 切到一条新的空时间线，conversationId 由下一次 send() 生成。
        conversationId?.let { timelines[it] = timeline }
        conversationId = null
        selectedAgent = null
        // 聊天选项目（2026-11-11，合包对齐主流分支 ae21dffc + 选项目修复）：
        // 新对话保留上次的项目，不清 currentProjectId；新会话 id 由下一次
        // send() 生成时记到 sessionProjects 里并持久化（见 send()）。
        timeline = ChatTimeline()
        pausedTurn = false
        // GitHub 闭环（2026-11-11）：新对话保留上次的聊天项目（调用方
        // 的 ChatProjectBinding.onNewConversation 故意不清空；之前清掉
        // 是 bug——开新对话发测试消息时绑定被悄悄清掉，聊天里又没选项目
        // 入口，直接卡死）。
        onNewConversation?.invoke()
        publish(true)
        // 阶段 3（U1）：新对话清空默认会话时间线后，回调让调用方同步清空快照。
        onTurnFinished?.invoke()
        return true
    }

    /**
     * V5 修复（2026-11-11，CodeBuddy 卡死轮次）：强制开新对话逃生口。
     *
     * 用户点“新对话”被“上一轮还在执行”拦住、且停止也不管用时用：
     * 取消所有在跑轮次 job、掐断远端连接、清全部轮次状态、记一条
     * 说明、开新对话。宁可丢掉一轮卡死的旧轮次，也不让用户永远
     * 开不了新对话。旧轮次的 job 之后若跑到 finally，只会清一遍
     * 已清的状态（幂等），不会污染新时间线。
     */
    fun forceNewConversation(): Boolean {
        remoteRunTaskId?.let { remotePort?.abortRun(it) }
        remoteTurnJob?.cancel()
        manusTurnJob?.cancel()
        taskTurnJob?.cancel()
        components?.turnController?.stop()
        clearTurnState()
        timeline.appendNote(null, "已强制结束上一轮（旧轮次未正常结束），开启新对话。")
        publish(true)
        return newConversation()
    }

    /**
     * V5 修复（2026-11-11，CodeBuddy 卡死轮次）：僵尸轮次自愈。
     *
     * active 还占着、但三类有 job 的轮次（远端/Manus/任务）的 job
     * 全都不在跑（job 引用还在、isActive 已 false：finally 没跑到
     * 或中途抛了）= 僵尸状态，拦截是假的，自动清掉放行。
     * 脑轮次没有独立 job 字段，无法区分“在跑”与“僵尸”，一律不
     * 自动放行（宁可拦，不可误放正在跑的大脑轮次）；脑轮次卡住
     * 时走停止（turnController.stop）或强制开新对话。
     */
    private fun releaseZombieTurnIfAny(): Boolean {
        if (!active.get()) return false
        val remoteZombie = remoteTurnJob != null && remoteTurnJob?.isActive != true
        val manusZombie = manusTurnJob != null && manusTurnJob?.isActive != true
        val taskZombie = taskTurnJob != null && taskTurnJob?.isActive != true
        if (!remoteZombie && !manusZombie && !taskZombie) return false
        clearTurnState()
        timeline.appendNote(null, "上一轮已不在执行（残留状态已清理），可以继续。")
        publish(true)
        return true
    }

    /** 清掉全部轮次互斥与残留状态（僵尸自愈与强制开新对话共用）。 */
    private fun clearTurnState() {
        remoteTurnJob = null
        remoteTurnTaskId = null
        remoteRunTaskId = null
        remoteTurnTarget = null
        remoteTurnPaused = false
        remoteTurnGoalChanged = false
        manusTurnJob = null
        manusTurnTaskId = null
        manusTaskTurnTaskId = null
        manusTurnConversationId = null
        manusTurnAgent = null
        manusTurnPaused = false
        manusTurnGoalChanged = false
        taskTurnJob = null
        taskTurnTaskId = null
        taskTurnAgent = null
        taskTurnTarget = null
        taskTurnPaused = false
        taskTurnGoalChanged = false
        pausedTurn = false
        active.set(false)
    }

    /**
     * 阶段 3（A1 P1/U5）：切换到另一个会话（默认/某 Agent/某任务）。
     * 各会话时间线分开保存，切换不丢历史；运行中拒绝切换。
     * 返回 false 时已在聊天里写明原因。
     *
     * 阶段 A：[agent] 是该会话的 Agent（有则绑定，无则清除）。
     * 同一会话重开时也以传入的 agent 为准（传入 null 时保留已有绑定，
     * 调用方不知道时不销毁）；切到另一会话时以传入值为准。
     *
     * V5 修复（2026-11-11，新对话也带尾巴）：[fresh] 为 true 时不
     * 用该会话缓存的旧时间线，改用一条全新空时间线（旧时间线仍在
     * conversationStore 的旧 conversationId 下，历史入口可找回，
     * 不丢失）。调用方在“刚点过新对话的干净状态”下打开 Agent 会话
     * 时传 true——否则 openAgentSession 会把旧轮次（连同它的尾巴）
     * 带回来，新对话被悄悄撤销。用户主动打开历史会话时不传 fresh，
     * 该看到历史就看到历史。
     *
     * 主流补齐（2026-11-11）：[projectId] 为该会话要挂的项目。
     * 传入时绑定（覆盖旧绑定）；不传时恢复该会话已有的项目绑定
     * （没有则清除）。切会话不丢项目绑定，换项目不丢会话。
     */
    fun switchSession(
        sessionId: String,
        label: String,
        agent: AgentRecord? = null,
        fresh: Boolean = false,
        projectId: String? = null,
    ): Boolean {
        if (active.get()) {
            if (!releaseZombieTurnIfAny()) {
                timeline.beginSystemTurn("上一轮还在执行，先停止或等它结束，再切换会话。")
                publish(true)
                return false
            }
            // 僵尸轮次已清，继续往下切换会话。
        }
        if (sessionId == conversationId) {
            if (agent != null) selectedAgent = agent
            currentSessionLabel = label
            if (projectId != null) {
                currentProjectId = projectId
                sessionProjects[sessionId] = projectId
                onSessionProjectsChanged?.invoke(sessionProjects.toMap())
            }
            publish(true)
            return true
        }
        val currentId = conversationId
            ?: if (timeline.snapshot().turns.isNotEmpty()) {
                UUID.randomUUID().toString().also { conversationId = it }
            } else {
                null
            }
        if (currentId != null) timelines[currentId] = timeline
        timeline = if (fresh) {
            ChatTimeline().also { timelines[sessionId] = it }
        } else {
            timelines.getOrPut(sessionId) { ChatTimeline() }
        }
        conversationId = sessionId
        currentSessionLabel = label
        selectedAgent = agent
        // 恢复该会话的项目绑定：传入了就用传入的（覆盖），没传就用已存的，没有则清除。
        currentProjectId = projectId ?: sessionProjects[sessionId]
        if (projectId != null) {
            sessionProjects[sessionId] = projectId
            onSessionProjectsChanged?.invoke(sessionProjects.toMap())
        }
        pausedTurn = false
        publish(true)
        return true
    }

    fun currentSessionId(): String? = conversationId

    fun currentSessionLabel(): String = currentSessionLabel

    /** 上一轮是否以 PAUSED 结束（可恢复）。 */
    fun hasPausedTurn(): Boolean = pausedTurn && !active.get()

    /**
     * 阶段 3（U1）：轮次结束回调（MainActivity 接上后保存默认会话
     * 快照）。只在轮次真正终态时调用；调用方自行判断是否默认会话。
     */
    var onTurnFinished: (() -> Unit)? = null

    /** 阶段 3（U1）：当前是否默认会话（默认标签；切到 Agent/任务会话后为 false）。 */
    fun isDefaultSession(): Boolean = currentSessionLabel == defaultSessionLabel

    /** 阶段 3（U1）：默认会话当前全部轮次；非默认会话时返回空列表（不覆盖默认快照）。 */
    fun defaultTurns(): List<ChatTurnUi> =
        if (isDefaultSession()) timeline.snapshot().turns else emptyList()

    /**
     * 阶段 3（U1）：从快照恢复默认会话。只在当前时间线为空时恢复，
     * 绝不覆盖已有对话；恢复出的轮次为终态，不重跑。
     */
    fun restoreDefaultTurns(turns: List<SavedTurn>) {
        if (timeline.snapshot().turns.isNotEmpty()) return
        if (!isDefaultSession()) return
        restoreSessionTurns(turns)
    }

    /**
     * V5 C3 chat-bugfix：从持久化轮次恢复任意会话的时间线（Room 恢复
     * 用）。只在当前时间线为空时恢复，绝不覆盖已有对话；恢复出的轮次
     * 为终态，不重跑。调用方须先切到目标会话（[switchSession]）。
     */
    fun restoreSessionTurns(turns: List<SavedTurn>) {
        if (timeline.snapshot().turns.isNotEmpty()) return
        appendRestoredTurns(turns)
    }

    /** Replace a cached timeline with the persisted source of truth on history open. */
    fun replaceSessionTurns(turns: List<SavedTurn>) {
        timeline.clear()
        appendRestoredTurns(turns)
    }

    private fun appendRestoredTurns(turns: List<SavedTurn>) {
        // 大脑/Agent 边界（maintainer 2026-11-12，一个对话、一个说话者）：
        // 恢复出的轮次说话者不能丢——快照/Room 里有就用存的；没有
        // （旧快照/Room 恢复）就按当前对话方补：Agent 对话补 Agent 名
        // （speakerIsBrain=false），大脑对话补大脑名。绝不让恢复出的
        // 轮次回落到含混的“AGENT”。任务归属有就带上。
        val partnerName = selectedAgent?.displayName ?: currentBrainLabelForRestore()
        val partnerIsBrain = selectedAgent == null
        for (saved in turns) {
            val status = try {
                TurnStatus.valueOf(saved.status)
            } catch (_: Exception) {
                TurnStatus.DONE
            }
            val speaker = saved.speaker.ifBlank { partnerName }
            val speakerIsBrain = if (saved.speaker.isBlank()) partnerIsBrain else saved.speakerIsBrain
            val attribution = saved.taskId?.takeIf { it.isNotBlank() }?.let {
                TurnTaskAttribution(taskId = it, taskTitle = saved.taskTitle ?: "")
            }
            timeline.restoreTurn(
                saved.userText,
                saved.assistantText,
                status,
                saved.error,
                speaker = speaker,
                speakerIsBrain = speakerIsBrain,
                taskAttribution = attribution,
                routeLabel = saved.routeLabel,
            )
        }
        publish(true)
    }

    /** 恢复用大脑显示名（无 Agent 的大脑对话；取不到时回落到“大脑”）。 */
    private fun currentBrainLabelForRestore(): String {
        val brainId = try {
            components?.configStore?.currentBrainId()
        } catch (_: Exception) {
            null
        }
        return brainId?.let { brainLabel(it) } ?: "大脑"
    }

    /**
     * V5 C3 chat-bugfix：重启恢复——把一个已持久化的会话认作当前会话
     * （只在时间线为空时；label 保持默认会话标签不变）。
     */
    fun adoptRestoredSession(sessionId: String, turns: List<SavedTurn>) {
        if (timeline.snapshot().turns.isNotEmpty()) return
        conversationId = sessionId
        restoreSessionTurns(turns)
    }

    /**
     * V5 按主流改（maintainer 2026-11-11）：“+”菜单附件与网页搜索。
     * [attachments]：本轮随附的附件（相机/照片/文件，已落到 App 私有目录）；
     * [webSearchEnabled]：网页搜索开关（只对大脑轮次有效）。
     * 空文本 + 有附件可以发（只发一张图也行）。
     */
    /**
     * @param forceTask 用户显式标为任务（输入框“任务”开关）：这条消息
     * 一定按任务处理、建派活任务行，不受意图分类与直连开关影响。
     * 默认 false（由意图分类决定）。
     */
    fun send(
        prompt: String,
        attachments: List<ChatAttachment> = emptyList(),
        webSearchEnabled: Boolean = false,
        forceTask: Boolean = false,
    ): SendResult {
        val text = prompt.trim()
        if (text.isEmpty() && attachments.isEmpty()) return SendResult(false)
        if (active.get()) {
            if (releaseZombieTurnIfAny()) {
                // 僵尸轮次已清，继续往下发新消息。
            } else {
                val message = "上一轮还在执行：请先在任务页停止，或等它结束再发新消息（同一时刻只跑一轮）。"
                timeline.beginSystemTurn(message)
                publish(true)
                return SendResult(false, message)
            }
        }
        val comps = components ?: run {
            val message = "大脑还在加载，请稍候再发送。"
            timeline.beginSystemTurn(message)
            publish(true)
            return SendResult(false, message)
        }
        // 阶段 1（U3）：分派任务执行期间不开始新轮次——执行器与聊天轮次
        // 共用一个进程级 TurnController，并发会互相干扰。任务页可见该
        // 分派任务的实时状态，等它结束后再发。
        if (comps.taskRunner.isBusy()) {
            val message = "任务调度器正在认领/执行一个分派任务，请稍后再发送（分派任务执行期间不能同时开始新轮次）。"
            timeline.beginSystemTurn(message)
            publish(true)
            return SendResult(false, message)
        }
        // P2 自动执行（2026-11-11）：流水线泵正在调规划/审查大脑时同样
        // 不开始新轮次——同一时刻只跑一轮（runner 的泵在有在途工作时
        // 本来就会 defer，这里守的是反方向：用户在 runner 调用中途发消息）。
        if (comps.pipelineRunner?.isBusy() == true) {
            val message = "流水线正在自动规划/审查，请稍后再发送（同一时刻只跑一轮）。"
            timeline.beginSystemTurn(message)
            publish(true)
            return SendResult(false, message)
        }
        // 聊天选项目（2026-11-11，合包对齐主流分支 ae21dffc + 选项目修复）：
        // 新对话保留上次的项目——会话 id 在这里生成时就把当前项目记到
        // 该会话上并持久化，否则新会话没有绑定、横幅与任务改道都会丢。
        // 派活丢绑定根因同款（2026-11-12）：这里读的必须是有效绑定
        // currentProjectId()（含全局回落），不是字段——字段在只走全局
        // 绑定的对话里是 null，照抄字段新会话永远记不上绑定。
        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString()
            currentProjectId()?.let {
                sessionProjects[conversationId!!] = it
                onSessionProjectsChanged?.invoke(sessionProjects.toMap())
            }
        }
        // V5 按主流改：两份正文——气泡里显示的（附件只留描述行）和发给
        // 对方（大脑/Agent）的（文本文件内容 inline、图片走 vision 通道）。
        // 任务型指令的判断用用户原文，不用展开后的正文。
        val displayText = ChatAttachments.buildDisplayText(text, attachments)
        val modelText = ChatAttachments.buildModelText(text, attachments)
        val imageDataUrls = attachments
            .filter { it.kind == ChatAttachmentKind.IMAGE && it.imageDataUrl != null }
            .map { it.imageDataUrl!! }
        // 图片发给看不见图片的对方时，轮次里要有一句诚实说明（不发给
        // 大脑轮次：大脑轮次由引擎按 vision 能力在模型正文里说明）。
        val imageNoteForNonBrain =
            if (imageDataUrls.isNotEmpty()) "图片无法发给当前对话方：对方看不到图片内容，只收到了图片的文件名。图片要能被看懂，需要发给支持看图片的大脑。"
            else null
        val currentBrainId = comps.configStore.currentBrainId()
        // git 家（2026-11-11）：当前聊天项目的当前家是 git 家、且是任务型
        // 指令时，不走远端 CLI 任务通道——远端 Agent 只做设备文件夹的活，
        // git 家没有设备文件夹可下；由本机大脑经该站 API 执行，
        // 分支/PR 落在该站上。当前家是设备家/本地家时不进这里，
        // 直走所选 Agent 的远端通道（2026-11-12 派活按当前家路由）。
        // 纯聊天（“在吗”）不受影响，仍走所选 Agent。
        // 无所选 Agent 的默认会话本来就走大脑，这里只补一句说明。
        val gitHostRef = gitHostProjectRefProvider?.invoke()
        if (gitHostRef != null && ChatTaskDispatcher.isTaskInstruction(text)) {
            return startBrainTurn(
                comps,
                text,
                allowFailover = true,
                introNote = "该项目是 ${gitHostRef.coordinates.host.displayName} 家（${gitHostRef.coordinates.namespacePath}）：由本机大脑经 ${gitHostRef.coordinates.host.displayName} API 执行，不克隆、不下到本地；分支与 PR/合并请求开在 ${gitHostRef.coordinates.host.displayName} 上。",
            )
        }
        val agent = selectedAgent
        if (agent != null) {
            // 阶段 A：选中 Agent 的消息按 Agent 路由，不再无条件走全局大脑。
            // NotRoutable 时诚实报错、不发出、不回落（尤其不回落 ChatGPT）。
            return when (val route = AgentChatRouter.resolve(
                agent,
                comps.configStore.listConfigs(),
                comps::isBrainConfigured,
                isRemoteAgent = remoteAgentCheck ?: { false },
                isManusConfigured = manusConfiguredCheck ?: { false },
                isPlatformAgentConfigured = platformConfiguredCheck ?: { false },
            )) {
                is AgentChatRoute.UseBrain -> {
                    // 大脑/Agent 边界（maintainer 2026-11-12，一个对话、一个说话者）：
                    // 远端设备 Agent 对话里大脑绝不能以说话者身份开口。
                    // 路由若把大脑路线给了远端设备 Agent，是路由矛盾——
                    // 强制走远端路线（任务型建行、纯聊天直连），绝不让
                    // 大脑轮次出现在这里。脑后 Agent（deviceId 为空、
                    // 本身就是大脑在说话）才走 startBrainTurn，抬头写
                    // 大脑名（turnLabel 冒充已删）。
                    if (agent.deviceId != null) {
                        if (ChatTaskDispatcher.isTaskInstruction(text)) startTaskTurn(
                            comps, agent, displayText,
                            modelPrompt = modelText,
                            attachments = attachments,
                            imageNote = imageNoteForNonBrain,
                        )
                        else startRemoteTurn(
                            comps, agent, displayText,
                            modelPrompt = modelText,
                            attachments = attachments,
                            imageNote = imageNoteForNonBrain,
                        )
                    } else startBrainTurn(
                        comps,
                        displayText,
                        brainIdOverride = route.brainId,
                        allowFailover = false,
                        modelPrompt = modelText,
                        attachments = attachments,
                        imageDataUrls = imageDataUrls,
                        webSearchEnabled = webSearchEnabled,
                    )
                }
                // 阶段 B：远程设备 Agent 走 Hand 直连，不走任何大脑。
                // V5 修复（2026-11-11）：任务型指令走任务通道——建分派
                // 任务行交给远端泵执行、进度/结果回填聊天；纯聊天
                // （“在吗”）仍走一次远端轮次。
                //
                // 大脑/Agent 边界（maintainer 2026-11-12）：直连 Agent（大脑
                // 关闭）开时，任务型指令也不建行、不派活——该对话里的
                // 消息一律直发 Agent，由 Agent 自己接消息、自己干活；
                // 状态行写明这次走的是直连，让用户看得出走的是哪条路。
                //
                // 自然对话与任务的边界（maintainer 2026-11-12）：每条消息先
                // 按意图分类（TaskIntentClassifier）：
                // - 用户显式标为任务（forceTask）：一定建派活任务行
                //   （走泵），不受分类与直连开关影响——这是用户纠正
                //   分类、说“这句是任务”的办法。
                // - 直连开：其余消息一律直发 Agent（分类只作标注，
                //   不改路由）；意图不明时状态行标明。
                // - 直连关：分类为任务 → 建派活任务行；闲聊/不明 →
                //   直发 Agent 轮次；意图不明时状态行标明。
                // 安全默认：拿不准时按闲聊处理（不乱干活），但标明。
                is AgentChatRoute.UseRemoteAgent -> {
                    val intent = if (forceTask) TaskIntent.TASK
                        else TaskIntentClassifier.classify(text)
                    val intentNote = if (intent == TaskIntent.UNCERTAIN)
                        "意图不明，已按闲聊处理；如需派活，请标为任务后重发。"
                    else null
                    when {
                        forceTask -> startTaskTurn(
                            comps, agent, displayText,
                            modelPrompt = modelText,
                            attachments = attachments,
                            imageNote = imageNoteForNonBrain,
                        )
                        isDirectAgentMode() -> startRemoteTurn(
                            comps, agent, displayText,
                            modelPrompt = modelText,
                            attachments = attachments,
                            imageNote = imageNoteForNonBrain,
                            routeLabel = listOfNotNull(
                                "直连 ${agent.displayName}（大脑已关闭）：消息直接发给 Agent，大脑不派活、不插话。",
                                intentNote,
                            ).joinToString(" "),
                        )
                        intent == TaskIntent.TASK -> startTaskTurn(
                            comps, agent, displayText,
                            modelPrompt = modelText,
                            attachments = attachments,
                            imageNote = imageNoteForNonBrain,
                        )
                        else -> startRemoteTurn(
                            comps, agent, displayText,
                            modelPrompt = modelText,
                            attachments = attachments,
                            imageNote = imageNoteForNonBrain,
                            routeLabel = intentNote,
                        )
                    }
                }
                // 阶段 C：Manus 走聊天桥接（任务型 API 转聊天消息），
                // 同一套通用会话/历史，不另做 Manus 专属流程。
                // V5 修复（2026-11-11）：任务型指令走任务通道——建分派
                // 任务行、由本轮直接驱动 Manus 执行（Manus 是云端 Agent，
                // 没有设备，远端泵不认领；见 startManusTaskTurn）；纯聊天
                // （“在吗”）仍走一次 Manus 轮次。
                is AgentChatRoute.UseManus ->
                    if (ChatTaskDispatcher.isTaskInstruction(text)) startManusTaskTurn(
                        comps, agent, displayText,
                        modelPrompt = modelText,
                        attachments = attachments,
                        imageNote = imageNoteForNonBrain,
                    )
                    else startManusTurn(
                        comps, agent, displayText,
                        modelPrompt = modelText,
                        attachments = attachments,
                        imageNote = imageNoteForNonBrain,
                    )
                // 平台 Agent：消息经平台频道发往该 Agent、轮询收回复，
                // 不经过任何大脑。任务型指令也走同一条频道消息（平台
                // 侧按任务处理），不另建任务行——任务页指派暂不支持
                // 平台 Agent 认领。
                is AgentChatRoute.UsePlatformAgent -> startPlatformTurn(comps, agent, text)
                is AgentChatRoute.NotRoutable -> {
                    timeline.beginSystemTurn(route.message)
                    publish(true)
                    SendResult(false, route.message)
                }
                AgentChatRoute.UseGlobalBrain -> startBrainTurn(
                    comps,
                    displayText,
                    modelPrompt = modelText,
                    attachments = attachments,
                    imageDataUrls = imageDataUrls,
                    webSearchEnabled = webSearchEnabled,
                )
            }
        }
        val useBrain = currentBrainId != null && comps.isBrainConfigured(currentBrainId)
        return if (useBrain) {
            startBrainTurn(
                comps,
                displayText,
                modelPrompt = modelText,
                attachments = attachments,
                imageDataUrls = imageDataUrls,
                webSearchEnabled = webSearchEnabled,
            )
        } else {
            startFallbackTurn(displayText)
        }
    }

    /**
     * 附件进轮次：卡片（点按用系统查看器打开）+ 图片看不见时的诚实说明。
     * 只在有附件时调；无附件不改时间线。
     */
    private fun attachToTurn(turnId: String, attachments: List<ChatAttachment>, imageNote: String?) {
        if (attachments.isNotEmpty()) {
            timeline.addAttachments(
                turnId,
                attachments.map { ChatAttachmentUi(key = it.id, name = it.displayName, url = it.contentUri) },
            )
        }
        if (imageNote != null) timeline.appendNote(turnId, imageNote)
    }

    /** 当前大脑是否支持看图片（按配置的 adapter 自报的 vision 能力）。 */
    private fun brainSupportsVision(comps: BrainBootstrap.BrainComponents, brainId: String?): Boolean {
        val id = brainId ?: return false
        val config = comps.configStore.listConfigs().firstOrNull { it.brainId == id } ?: return false
        return BrainAdapterRegistry.get(config.adapterId)?.capabilities()?.vision ?: false
    }

    private fun startBrainTurn(
        comps: BrainBootstrap.BrainComponents,
        prompt: String,
        brainIdOverride: String? = null,
        allowFailover: Boolean = true,
        /** GitHub 闭环（2026-11-11）：轮次开头的说明备注（可空）。 */
        introNote: String? = null,
        /** 发给模型的正文（附件展开版）；默认等于 [prompt]（无附件时）。 */
        modelPrompt: String = prompt,
        /** 本轮随附的附件（时间线卡片用）；默认空。 */
        attachments: List<ChatAttachment> = emptyList(),
        /** 本轮随附的图片 data URL（vision 通道用）；默认空。 */
        imageDataUrls: List<String> = emptyList(),
        /** 网页搜索开关；默认关。 */
        webSearchEnabled: Boolean = false,
    ): SendResult {
        if (!active.compareAndSet(false, true)) {
            return SendResult(false, "上一轮还在执行。")
        }
        comps.turnController.begin()
        pausedTurn = false
        val convId = conversationId ?: UUID.randomUUID().toString().also {
            conversationId = it
        }
        // 大脑/Agent 边界（maintainer 2026-11-12 定稿，一个对话、一个说话者）：
        // 大脑轮次的抬头永远是大脑名，绝不借 Agent 的名——“Agent 借大
        // 脑回话”的 turnLabel 已删（2026-11-12 事故：大脑在 Codex 对话
        // 里顶着 Agent 名开口，maintainer 当场问“这他妈谁在说话”）。大脑想
        // 说话就开大脑对话；Agent 对话里大脑只在幕后派活，不开说话轮次。
        // （脑后 Agent：CLI/模型 API 构成的 Agent 本身就是大脑在说话，
        // 抬头写大脑名是实话；远端设备 Agent 走 UseRemoteAgent，不到这里。）
        val effectiveBrainId = brainIdOverride ?: comps.configStore.currentBrainId()
        val displayLabel = brainLabel(effectiveBrainId)
        val turnId = timeline.beginUserTurn(prompt, displayLabel, true)
        if (introNote != null) timeline.appendStatusNote(turnId, introNote)
        attachToTurn(turnId, attachments, null)
        // V5 按主流改：图片发给了不支持 vision 的大脑——轮次里明说，
        // 不让用户以为大脑看到了图；引擎也会在模型正文里再说一次。
        if (imageDataUrls.isNotEmpty() && !brainSupportsVision(comps, effectiveBrainId)) {
            timeline.appendNote(
                turnId,
                "当前大脑（$displayLabel）不支持看图片：图片已发到对话里，但大脑看不到图片内容，不会按图片内容回答。",
            )
        }
        publish(true)

        scope.launch {
            var cancelled = false
            var terminalStatus: TurnStatus? = null
            var terminalError: String? = null
            var doneReason: String? = null
            // 阶段 1（U3）：轮次开任务。失败返回 null 时本轮只走聊天。
            // 记实际执行的大脑（Agent 会话是 override 的大脑，不是全局选择）。
            val taskId = turnRecorder?.beginTurn(prompt, convId, effectiveBrainId, currentProjectId())
            try {
                comps.engine.chat(
                    convId,
                    prompt,
                    brainIdOverride,
                    allowFailover,
                    currentMessageOverride = modelPrompt.takeIf { it != prompt },
                    imageDataUrls = imageDataUrls,
                    webSearchEnabled = webSearchEnabled,
                ).collect { event ->
                    when (event) {
                        is BrainEvent.TextDelta -> {
                            timeline.appendAssistantText(turnId, event.text)
                            publish()
                        }
                        is BrainEvent.ReasoningDelta -> {
                            timeline.appendReasoning(turnId, event.text)
                            publish()
                        }
                        is BrainEvent.ToolCall -> {
                            timeline.addToolCall(turnId, event.id, event.name, event.args)
                            taskId?.let { turnRecorder?.recordToolCall(it, event.id, event.name, event.args) }
                            publish(true)
                        }
                        is BrainEvent.ToolResult -> {
                            timeline.addToolResult(turnId, event.id, event.ok, event.summary)
                            taskId?.let { turnRecorder?.recordToolResult(it, event.id, event.ok, event.summary) }
                            publish(true)
                        }
                        is BrainEvent.Usage -> {
                            // 用量只存大脑轮次的数：这条分支只在大脑事件流里，
                            // Manus/远端轮次走另一条事件流，永远到不了这里；
                            // 显示与否由界面按说话者再定（Agent 说话不显示用量）。
                            timeline.setUsage(turnId, event.inputTokens, event.outputTokens)
                            publish()
                        }
                        is BrainEvent.Error -> {
                            when (event.code) {
                                "turn_paused" -> {
                                    terminalStatus = TurnStatus.PAUSED
                                    timeline.appendNote(turnId, event.message)
                                }
                                "turn_stopped" -> {
                                    terminalStatus = TurnStatus.STOPPED
                                    timeline.appendNote(turnId, event.message)
                                }
                                "goal_changed" -> {
                                    terminalStatus = TurnStatus.GOAL_CHANGED
                                    timeline.appendNote(turnId, event.message)
                                }
                                else -> {
                                    terminalStatus = TurnStatus.ERROR
                                    terminalError = formatTurnError(event, displayLabel)
                                }
                            }
                            publish(true)
                        }
                        is BrainEvent.Done -> {
                            doneReason = event.finishReason
                        }
                    }
                }
            } catch (_: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                terminalStatus = TurnStatus.ERROR
                terminalError = "调用${displayLabel}失败：${e.message ?: "未知错误"}"
            } finally {
                timeline.cancelRunningTools(turnId)
                val snapshot = timeline.snapshot()
                val turn = snapshot.turns.lastOrNull { it.id == turnId }
                val terminal = terminalStatus
                val resolvedStatus = when {
                    cancelled -> TurnStatus.CANCELLED
                    terminal != null -> terminal
                    turn != null && turn.assistantText.isBlank() && turn.toolCalls.isEmpty() ->
                        TurnStatus.EMPTY
                    else -> TurnStatus.DONE
                }
                val resolutionMessage = when (resolvedStatus) {
                    TurnStatus.EMPTY ->
                        if (brainIdOverride != null) {
                            "该 Agent 没有返回可显示的内容（空响应）。请重试；若持续出现，请检查该 Agent 的配置与网络。"
                        } else {
                            "大脑没有返回可显示的内容（空响应）。请重试；若持续出现，请检查大脑配置与网络。"
                        }
                    TurnStatus.CANCELLED ->
                        "本轮已取消。"
                    else -> terminalError
                }
                if (resolvedStatus == TurnStatus.EMPTY ||
                    resolvedStatus == TurnStatus.CANCELLED
                ) {
                    timeline.appendNote(turnId, resolutionMessage.orEmpty())
                }
                if (resolvedStatus == TurnStatus.DONE &&
                    turn != null &&
                    turn.assistantText.isBlank() &&
                    turn.toolCalls.isNotEmpty()
                ) {
                    timeline.appendNote(turnId, "工具已执行，但模型没有给出最终文字回复；请查看上方工具结果。")
                }
                timeline.finishTurn(
                    turnId = turnId,
                    status = resolvedStatus,
                    error = if (resolvedStatus == TurnStatus.ERROR) resolutionMessage else null,
                )
                // 阶段 1（U3）：轮次终态写回任务行（review -> completed /
                // failed / cancelled，见 TurnTaskRecorder）。
                taskId?.let {
                    turnRecorder?.finishTurn(
                        taskId = it,
                        status = resolvedStatus,
                        assistantText = turn?.assistantText.orEmpty(),
                        error = if (resolvedStatus == TurnStatus.ERROR) resolutionMessage else null,
                        hadToolCalls = turn?.toolCalls?.isNotEmpty() ?: false,
                    )
                }
                if (doneReason == "no_brain" && resolvedStatus == TurnStatus.ERROR) {
                    timeline.appendNote(turnId, "请到设置里配置一个大脑。")
                }
                // 阶段 3（U4）：PAUSED 时不复位控制态——resume() 以它为准绳；
                // 新一轮的 begin() 会复位。其他终态照常复位。
                pausedTurn = resolvedStatus == TurnStatus.PAUSED
                if (!pausedTurn) comps.turnController.reset()
                active.set(false)
                publish(true)
                onTurnFinished?.invoke()
            }
        }
        return SendResult(true)
    }

    /**
     * 端侧小模型路由 v1（可选，默认关闭）：任务已建行后，在后台协程里
     * 调一次用户在设置里配好的小模型，对任务做意图 / 复杂度分流，
     * 只给模式建议（单 Agent 直接做 / 建议走 P2 流水线）。
     *
     * 不改派单、不替用户决定、不挡任务：任务观察（taskTurnJob）照常
     * 跑；分流建议只是聊天里的一条备注 + 路由决策日志上的一条附加
     * 记录，用户采纳与否自己定；P2 本身仍是 opt-in。
     *
     * 严格回落：设置未开 / 未配分流大脑 / 适配器取不到 / 调用超时 /
     * 报错 / 空回 / 解析失败 → TriageRunner.run 返回 null，
     * 这里直接 return，不记分流、不写备注、不报错。
     */
    private fun maybeRunTriage(
        comps: BrainBootstrap.BrainComponents,
        taskId: String,
        turnId: String,
        taskText: String,
        decisionId: String?,
    ) {
        val settings = try {
            comps.routerSettings
        } catch (_: Exception) {
            return
        }
        if (!settings.triageEnabled) return
        val triageBrainId = settings.triageBrainId ?: return
        val adapter = try {
            comps.genericAdapter.forBrain(triageBrainId)
        } catch (_: Exception) {
            return
        }
        val modelLabel = try {
            comps.configStore.getConfig(triageBrainId)?.let { config ->
                val name = config.label.takeIf { it.isNotBlank() } ?: triageBrainId
                val model = config.model.takeIf { it.isNotBlank() }
                if (model != null) "$name · $model" else name
            } ?: triageBrainId
        } catch (_: Exception) {
            triageBrainId
        }
        scope.launch {
            val record = try {
                TriageRunner.run(adapter, modelLabel, taskText, settings.triageTimeoutMs)
            } catch (_: Exception) {
                null
            }
            if (record == null) return@launch // 无声回落：不记分流、不写备注。
            // 记进 P1 路由决策日志（有绑定的 P1 决策时才附加；手动选
            // Agent 的任务只有聊天备注，不另造记录类型）。
            if (decisionId != null) {
                try {
                    val decision = comps.routingDecisionLog.listDecisions()
                        .firstOrNull { it.decisionId == decisionId }
                    if (decision != null) {
                        comps.routingDecisionLog.record(decision.copy(triage = record))
                    }
                } catch (_: Exception) {
                    // 日志失败不影响任务。
                }
            }
            // 聊天里可见的分流建议（只建议，不替用户决定）；本轮已结束
            // （任务观察已收尾）就不追加，免得往已终态的轮次里塞备注。
            try {
                if (taskTurnTaskId == taskId) {
                    val modeText = if (record.suggestedMode == TriageMode.PIPELINE) {
                        "建议走 P2 流水线（规划→编码→审查）"
                    } else {
                        "建议单 Agent 直接做"
                    }
                    timeline.appendNote(
                        turnId,
                        "分流小模型建议：$modeText——${record.reason}（${record.modelLabel}，" +
                            "${record.latencyMs}ms）。是否采纳由你定，任务照常进行。",
                    )
                    publish()
                }
            } catch (_: Exception) {
                // 备注失败不影响任务。
            }
        }
    }

    /**
     * 阶段 B（V5 紧急开发）：远端轮次——消息经 Hand 直连发往远程设备
     * 上的 Agent（如 VPS 上的 Example Agent），不经过任何大脑。
     *
     * 与 [startBrainTurn] 对称：同一套 active 互斥、同一套轮次→任务
     * 镜像（TurnTaskRecorder）、同一套终态落法；不同的是执行体是
     * [RemoteAgentChatPort.runAgent] 的 Hand 事件流：
     * - Progress → 轮次内备注（可见进度）；
     * - Result → 助手正文；
     * - Blocked → 终态 ERROR，带远端原样原因，不回落大脑、不伪装成功。
     * 结束后用户 + 助手消息持久化到 ConversationStore（与引擎的
     * 脑轮次同一张表，恢复历史时同一条链路读回）。
     */
    private fun startRemoteTurn(
        comps: BrainBootstrap.BrainComponents,
        agent: AgentRecord,
        prompt: String,
        /** 发给远端 Agent 的正文（附件展开版）；默认等于 [prompt]。 */
        modelPrompt: String = prompt,
        /** 本轮随附的附件（时间线卡片用）；默认空。 */
        attachments: List<ChatAttachment> = emptyList(),
        /** 图片看不见时的诚实说明；默认 null。 */
        imageNote: String? = null,
        /**
         * 大脑/Agent 边界（maintainer 2026-11-12）：本次走的路线的可见说明
         * （如直连模式、意图不明）。以持久的路线标注贴在本轮（见
         * ChatTimeline.setRouteLabel），不走 transient 的状态行——
         * 状态行会被进度 ticker 每秒覆盖，轮次结束后也消失；路线
         * 标注轮次结束后仍在，用户事后也看得出这条消息走的是哪条路。
         * null = 不标（普通远端轮次）。
         */
        routeLabel: String? = null,
    ): SendResult {
        val port = remotePort ?: run {
            val message = "远端聊天未接线：请重启 App 后重试。"
            timeline.beginSystemTurn(message)
            publish(true)
            return SendResult(false, message)
        }
        val target = remoteTargetResolver?.invoke(agent) ?: run {
            val message = "该远程设备上的 Agent 未接入：设备未配对或未连接，消息未发出。"
            timeline.beginSystemTurn(message)
            publish(true)
            return SendResult(false, message)
        }
        if (!active.compareAndSet(false, true)) {
            return SendResult(false, "上一轮还在执行。")
        }
        pausedTurn = false
        remoteTurnPaused = false
        remoteTurnGoalChanged = false
        val convId = conversationId ?: UUID.randomUUID().toString().also {
            conversationId = it
        }
        val turnId = timeline.beginUserTurn(prompt, agent.displayName, false)
        attachToTurn(turnId, attachments, imageNote)
        if (routeLabel != null) timeline.setRouteLabel(turnId, routeLabel)
        // V5 跨设备开发（2026-11-12，D4 新口径 (b)）：本轮回落到 Agent
        // 文件夹时在对话时间线标明一行（不静默）；项目检出命中时不标。
        // 来源写清项目名/设备/Agent 文件夹（与 folder_fallback 事件
        // 的 reason 同一模板，两处说法一致）；项目名查不到时用项目 id，
        // 未绑定时如实写“未绑定项目”，不编一个。
        if (target.folderSource == DispatchFolderSource.AGENT_FALLBACK) {
            val projectName = currentProjectId()?.let { id ->
                try {
                    projectNameProvider?.invoke(id)
                } catch (_: Exception) {
                    null
                } ?: id
            }
            timeline.appendNote(
                turnId,
                folderFallbackReason(projectName, target.peer.deviceId, target.folder),
            )
        }
        publish(true)
        // 轮次开任务（2026-11-12 回复归属修复）：远端轮次用
        // beginRemoteRound 只建行、不经调度器认领门禁。轮次行是
        // “记录”不是“可调度任务”，sessionId 非空故泵/执行器永不
        // 认领；active 互斥保证一轮一任务。旧代码走 beginTurn（调
        // 度器全局单任务门禁，一脑在手时认领失败回 null），拿 null
        // 就改用随机 UUID 发 Hand，活跑在任务表外面（2026-11-12
        // 真机事故：两次 run 调用 e7690865 / 21504781 在 K70 任务
        // 表里无行）。建行失败时中止轮次、如实报错，绝不发 Hand。
        // 任务行的 goalVersion/authEpoch 即本次调用的栅栏快照。
        val taskId = turnRecorder?.beginRemoteRound(prompt, convId, agent.agentId, currentProjectId())
        if (taskId == null) {
            active.set(false)
            val detail = turnRecorder?.lastBeginFailure()
            val message = if (detail.isNullOrBlank()) {
                "轮次任务建行失败，消息未发出（任务记录器不可用），请重试。"
            } else {
                "轮次任务建行失败，消息未发出：$detail"
            }
            timeline.finishTurn(turnId, TurnStatus.ERROR, message)
            publish(true)
            return SendResult(false, message)
        }
        // 大脑/Agent 边界修订：镜像按真正干活的远端 Agent 认领，本轮挂任务归属。
        timeline.setTaskAttribution(turnId, taskId, prompt)
        val snapshot = comps.taskStore.getTask(taskId)
        val goalVersion = snapshot?.goalVersion ?: 1L
        val epoch = snapshot?.authEpoch ?: 1L
        remoteTurnTaskId = taskId
        remoteTurnTarget = target
        val remoteTaskId = taskId
        remoteRunTaskId = remoteTaskId
        remoteTurnJob = scope.launch {
            var cancelled = false
            var terminalStatus: TurnStatus? = null
            var terminalError: String? = null
            val assistantText = StringBuilder()
            // V5 修复（2026-11-11，CodeBuddy 卡死轮次）：冷启动无输出
            // 期间每秒刷新一条状态行（appendStatusNote 只留最新一条，
            // 不刷屏；ChatTurnView 只在轮次运行中显示它，终态自动消失）。
            // 用户看得到“正在启动 CodeBuddy…已等待 N 秒”，不会以为
            // 死了去按停止；首条 Progress/Result/Blocked 到就停。
            // ticker 是本 job 的子协程，轮次结束自动取消；finally 里
            // 也显式取消，防止终态事件漏掉时它一直跑。
            var progressTicker: Job? = null
            progressTicker = launch {
                val startedAt = System.currentTimeMillis()
                while (isActive) {
                    val waited = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                    timeline.appendStatusNote(turnId, "正在启动 ${agent.displayName}…已等待 ${waited} 秒")
                    publish()
                    delay(1000)
                }
            }
            try {
                port.runAgent(
                    target = target,
                    taskId = remoteTaskId,
                    text = modelPrompt,
                    timeoutSeconds = 300,
                    goalVersion = goalVersion,
                    epoch = epoch,
                ).collect { event ->
                    when (event) {
                        is HandEvent.Progress -> {
                            progressTicker?.cancel()
                            // V5 修复（2026-11-11，回复带“尾巴”）：进度帧
                            // 不再进 notes——服务端每行 stdout 既发进度帧
                            // 又在终态发完整 output，旧代码把同一文本
                            // 渲染两次（notes 卡片 + 助手气泡，608 图）。
                            // 进度是回复的流式增量，直接流进助手正文；
                            // Result 再用 setAssistantText 以权威全文
                            // 快照整体替换（不累加），同一轮只出现一次。
                            // 提示性 notes（空响应/取消/改目标）仍在
                            // finally 里追加，不受影响。
                            if (event.message.isNotBlank()) {
                                timeline.appendAssistantText(turnId, event.message + "\n")
                                publish()
                            }
                        }
                        is HandEvent.Result -> {
                            progressTicker?.cancel()
                            timeline.setAssistantText(turnId, event.output)
                            assistantText.clear()
                            assistantText.append(event.output)
                            publish()
                        }
                        is HandEvent.Blocked -> {
                            progressTicker?.cancel()
                            terminalStatus = TurnStatus.ERROR
                            terminalError = event.reason
                            publish(true)
                        }
                    }
                }
            } catch (_: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                terminalStatus = TurnStatus.ERROR
                terminalError = "调用${agent.displayName}失败：${e.message ?: "未知错误"}"
            } finally {
                progressTicker?.cancel()
                // V5 修复（2026-11-11，CodeBuddy 卡死轮次）：状态清理
                // 放内层 finally——终态落法里任何一步抛了（持久化、
                // 任务镜像、回调），active 也必须清掉，否则用户永远
                // 被“上一轮还在执行”锁死。
                try {
                    timeline.cancelRunningTools(turnId)
                    val resolvedStatus = when {
                        remoteTurnGoalChanged -> TurnStatus.GOAL_CHANGED
                        cancelled -> TurnStatus.CANCELLED
                        terminalStatus != null -> terminalStatus!!
                        assistantText.isBlank() -> TurnStatus.EMPTY
                        else -> TurnStatus.DONE
                    }
                    val resolutionMessage = when (resolvedStatus) {
                        TurnStatus.EMPTY ->
                            "该 Agent 没有返回可显示的内容（空响应）。请重试；若持续出现，请检查该 Agent 与远端设备的连接。"
                        TurnStatus.CANCELLED -> "本轮已取消。"
                        TurnStatus.GOAL_CHANGED -> "本轮已改目标，剩余步骤未执行。"
                        else -> terminalError
                    }
                    if (resolvedStatus == TurnStatus.EMPTY ||
                        resolvedStatus == TurnStatus.CANCELLED ||
                        resolvedStatus == TurnStatus.GOAL_CHANGED
                    ) {
                        timeline.appendNote(turnId, resolutionMessage.orEmpty())
                    }
                    // 2026-11-12 回复归属修复：有真实回复时，正文前
                    // 加任务归属头（任务编号 + 轮次标题），聊天、存库
                    // 消息、任务结果三处一致；空响应/失败/取消不加
                    // （没有回复可归属，note 已如实说明）。
                    if (resolvedStatus == TurnStatus.DONE && assistantText.isNotBlank()) {
                        val attributed = replyAttributionHeader(taskId, prompt) + "\n" + assistantText.toString()
                        assistantText.clear()
                        assistantText.append(attributed)
                        timeline.setAssistantText(turnId, attributed)
                    }
                    timeline.finishTurn(
                        turnId = turnId,
                        status = resolvedStatus,
                        error = if (resolvedStatus == TurnStatus.ERROR) resolutionMessage else null,
                    )
                    turnRecorder?.finishTurn(
                        taskId = taskId,
                        status = resolvedStatus,
                        assistantText = assistantText.toString(),
                        error = if (resolvedStatus == TurnStatus.ERROR) resolutionMessage else null,
                        hadToolCalls = false,
                    )
                    persistRemoteTurn(convId, prompt, assistantText.toString(), taskId)
                    pausedTurn = resolvedStatus == TurnStatus.PAUSED
                    // V5 修复（2026-11-11，能用的 Agent 显示“未验证”）：
                    // 终态 DONE 且真回了内容 = 一次真实往返成功，比 hello
                    // 探测更强的可用证据；通知回调方把该 Agent 标成已验证。
                    // 空响应/失败/取消不调用。
                    if (resolvedStatus == TurnStatus.DONE && assistantText.isNotBlank()) {
                        onRemoteRoundSucceeded?.invoke(agent)
                    }
                } finally {
                    remoteTurnJob = null
                    remoteTurnTaskId = null
                    remoteRunTaskId = null
                    remoteTurnTarget = null
                    remoteTurnPaused = false
                    active.set(false)
                    publish(true)
                    onTurnFinished?.invoke()
                }
            }
        }
        return SendResult(true)
    }

    /**
     * V5 修复（2026-11-11）：任务轮次——任务型指令走任务通道。
     *
     * 与 [startRemoteTurn] 的区别：执行体不是一次 runAgent 调用，
     * 而是任务行本身。先由 [ChatTaskDispatcher] 建分派任务行
     * （sessionId=null，assignedAgentId=所选 Agent，state=queued），
     * 已在跑的 RemoteTaskPump 认领并经 Hand 发到远端执行；聊天只
     * 观察任务行：
     * - 新步骤的结果摘要 → 轮次内备注（可见进度）；
     * - completed → 任务 result 回填为助手正文；
     * - failed → 终态 ERROR，带任务 error 原文；
     * - cancelled → 终态 CANCELLED；
     * - 泵标 needs_attention（目标不可达）→ 终态 ERROR，带泵的
     *   原文（任务行留在 queued，配对后可重试，不静默等）。
     * 建行失败时回落到普通远端轮次（聊天不断）；任务行丢失、
     * 观察超时都如实结束本轮，不伪造完成、不杀任务。
     */
    private fun startTaskTurn(
        comps: BrainBootstrap.BrainComponents,
        agent: AgentRecord,
        prompt: String,
        /** 发给远端 Agent 的任务目标（附件展开版）；默认等于 [prompt]。 */
        modelPrompt: String = prompt,
        /** 本轮随附的附件（时间线卡片用）；默认空。 */
        attachments: List<ChatAttachment> = emptyList(),
        /** 图片看不见时的诚实说明；默认 null。 */
        imageNote: String? = null,
    ): SendResult {
        if (!active.compareAndSet(false, true)) {
            return SendResult(false, "上一轮还在执行。")
        }
        val taskId = ChatTaskDispatcher.dispatch(
            taskStore = comps.taskStore,
            agent = agent,
            prompt = modelPrompt,
            brainId = null,
            projectId = currentProjectId(),
        )
        if (taskId == null) {
            // 建行失败（任务库不可用）：回落到普通远端轮次，聊天不断。
            active.set(false)
            return startRemoteTurn(comps, agent, prompt, modelPrompt, attachments, imageNote)
        }
        // P1 路由决策绑定：用户点了建议行、且本任务就是派给被建议的
        // Agent——把决策与任务绑起来（eval 回看“建议准不准”用）。
        // Agent 不对上号就不绑；无论绑不绑，用一次就清掉，不留给下一条任务。
        // 绑上的 decisionId 也记下来，供端侧小模型分流（v1）成功时附加
        // 分流记录；没绑上就是手动选 Agent，分流只有聊天备注、不记决策。
        var boundDecisionId: String? = null
        pendingRouting?.takeIf { it.agentId == agent.agentId }?.let { pending ->
            boundDecisionId = pending.decisionId
            try {
                comps.routingDecisionLog.attachTask(pending.decisionId, taskId)
            } catch (_: Exception) {
                // 绑定失败不影响任务执行。
            }
        }
        pendingRouting = null
        val target = remoteTargetResolver?.invoke(agent)
        if (target == null) {
            // 路由已核实可达，这里再拿不到目标是竞态：任务行已建
            // （泵会标 needs_attention），聊天如实说，不静默等。
            active.set(false)
            val message = "该远程设备上的 Agent 未接入：设备未配对或未连接，任务已进 queued 等认领，详见任务页。"
            timeline.beginSystemTurn(message)
            publish(true)
            return SendResult(false, message)
        }
        pausedTurn = false
        taskTurnPaused = false
        taskTurnGoalChanged = false
        val convId = conversationId ?: UUID.randomUUID().toString().also {
            conversationId = it
        }
        val turnId = timeline.beginUserTurn(prompt, agent.displayName, false)
        attachToTurn(turnId, attachments, imageNote)
        // 自然对话与任务的边界（maintainer 2026-11-12）：这条消息被识别为
        // 任务——状态行明确写出来（“已识别为任务”），不是悄悄建行。
        // 分类错了用户能纠正：删任务（任务页已有）或重发时标任务。
        timeline.appendStatusNote(turnId, "已识别为任务：任务已创建，进入 queued 等 ${agent.displayName} 执行；进度与结果会回填到这里。")
        // 大脑/Agent 边界（maintainer 2026-11-12）：本轮挂任务归属——Agent
        // 的回复回填到这里时带任务编号/标题，不许裸贴进无关对话。
        timeline.setTaskAttribution(turnId, taskId, prompt)
        publish(true)
        taskTurnTaskId = taskId
        taskTurnAgent = agent
        taskTurnTarget = target
        // 端侧小模型路由 v1（可选，默认关闭）：任务已建行、P1 决策已绑定
        // 之后才跑；小模型只给模式建议，不改派单、不挡任务；失败一律
        // 无声回落。分流在后台协程里跑，任务观察不受影响。
        maybeRunTriage(comps, taskId, turnId, modelPrompt, boundDecisionId)
        val assistantText = StringBuilder()
        taskTurnJob = scope.launch {
            var cancelled = false
            var terminalStatus: TurnStatus? = null
            var terminalError: String? = null
            val seenStepIds = HashSet<String>()
            val deadlineMs = System.currentTimeMillis() + TASK_TURN_OBSERVE_TIMEOUT_MS
            try {
                while (System.currentTimeMillis() < deadlineMs) {
                    val task = try {
                        comps.taskStore.getTask(taskId)
                    } catch (_: Exception) {
                        null
                    }
                    if (task == null) {
                        terminalStatus = TurnStatus.ERROR
                        terminalError = "任务行丢失：任务可能已被删除，详见任务页。"
                        break
                    }
                    // 进度：新步骤的结果摘要回填为备注（只写一次）。
                    try {
                        val steps = comps.taskStepStore.listSteps(taskId)
                        for (step in steps) {
                            if (seenStepIds.add(step.stepId)) {
                                val summary = step.resultSummary?.takeIf { it.isNotBlank() }
                                    ?: step.argsSummary.takeIf { it.isNotBlank() }
                                    ?: continue
                                timeline.appendNote(turnId, summary)
                                publish()
                            }
                        }
                    } catch (_: Exception) {
                        // 步骤读失败不打断观察。
                    }
                    // 泵标了 needs_attention：目标不可达，任务不会执行——
                    // 聊天如实报错并结束本轮（任务行留在 queued，配对后可重试）。
                    if (task.needsAttention && task.error != null) {
                        terminalStatus = TurnStatus.ERROR
                        terminalError = task.error
                        break
                    }
                    when (task.state) {
                        "completed" -> {
                            val result = task.result.orEmpty()
                            if (result.isNotBlank()) {
                                timeline.appendAssistantText(turnId, result)
                                assistantText.append(result)
                            } else {
                                timeline.appendNote(turnId, "任务已完成（无文字结果，详见任务页）。")
                            }
                            terminalStatus = TurnStatus.DONE
                            break
                        }
                        "failed" -> {
                            terminalStatus = TurnStatus.ERROR
                            terminalError = task.error ?: "任务执行失败，未返回原因。"
                            break
                        }
                        "cancelled" -> {
                            terminalStatus = TurnStatus.CANCELLED
                            break
                        }
                    }
                    delay(TASK_TURN_POLL_MS)
                }
                if (terminalStatus == null) {
                    // 观察超时：任务仍在后台跑（任务页可见），聊天先行结束，
                    // 不伪造完成、不杀任务。
                    timeline.appendNote(turnId, "任务仍在后台执行（已超过观察时限），详见任务页。")
                    terminalStatus = TurnStatus.DONE
                }
            } catch (_: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                terminalStatus = TurnStatus.ERROR
                terminalError = "观察任务失败：${e.message ?: "未知错误"}"
            } finally {
                timeline.cancelRunningTools(turnId)
                val resolvedStatus = when {
                    taskTurnGoalChanged -> TurnStatus.GOAL_CHANGED
                    cancelled -> TurnStatus.CANCELLED
                    terminalStatus != null -> terminalStatus!!
                    assistantText.isBlank() -> TurnStatus.EMPTY
                    else -> TurnStatus.DONE
                }
                val resolutionMessage = when (resolvedStatus) {
                    TurnStatus.EMPTY ->
                        "该 Agent 没有返回可显示的内容（空响应）。请重试；若持续出现，请检查该 Agent 与远端设备的连接。"
                    TurnStatus.CANCELLED -> "本轮已取消。"
                    TurnStatus.GOAL_CHANGED -> "本轮已改目标，剩余步骤未执行。"
                    else -> terminalError
                }
                if (resolvedStatus == TurnStatus.EMPTY ||
                    resolvedStatus == TurnStatus.CANCELLED ||
                    resolvedStatus == TurnStatus.GOAL_CHANGED
                ) {
                    timeline.appendNote(turnId, resolutionMessage.orEmpty())
                }
                timeline.finishTurn(
                    turnId = turnId,
                    status = resolvedStatus,
                    error = if (resolvedStatus == TurnStatus.ERROR) resolutionMessage else null,
                )
                persistRemoteTurn(convId, prompt, assistantText.toString(), taskId)
                pausedTurn = resolvedStatus == TurnStatus.PAUSED
                taskTurnJob = null
                taskTurnTaskId = null
                taskTurnAgent = null
                taskTurnTarget = null
                taskTurnPaused = false
                active.set(false)
                publish(true)
                onTurnFinished?.invoke()
                // V5 修复（2026-11-11，能用的 Agent 显示“未验证”）：
                // 任务完成且真回了结果 = 一次真实往返成功，同 startRemoteTurn。
                if (resolvedStatus == TurnStatus.DONE && assistantText.isNotBlank()) {
                    onRemoteRoundSucceeded?.invoke(agent)
                }
            }
        }
        return SendResult(true)
    }

    // -- 任务轮次控制（V5 修复，2026-11-11）：暂停/继续/停止/改目标
    // 发往远端 Hand（与远端轮次同一套控制通道、同一个 taskId）；
    // 观察流本身只在停止/改目标时取消；暂停不断观察（任务行仍为
    // working，服务端 SIGSTOP）。--

    private fun sendTaskTurnControl(action: ControlAction): String? {
        val target = taskTurnTarget ?: return "任务目标丢失，无法发送控制指令。"
        val taskId = taskTurnTaskId ?: return "任务丢失，无法发送控制指令。"
        val sender = remoteControlSender
            ?: return "远端控制未接线：控制指令发不出去，任务仍在远端运行，请检查后重试。"
        val task = try {
            components?.taskStore?.getTask(taskId)
        } catch (_: Exception) {
            null
        }
        sender.send(
            target,
            taskId,
            RemoteControlCommand(
                action = action,
                operationId = UUID.randomUUID().toString(),
                goalVersion = task?.goalVersion ?: 1L,
                epoch = task?.authEpoch ?: 1L,
            ),
        ) { result ->
            timeline.appendNote(
                null,
                if (result.confirmed) "远端已确认。" else "远端控制未确认：${result.message}",
            )
            publish(true)
        }
        return null
    }

    private fun pauseTaskTurn(): String {
        val error = sendTaskTurnControl(ControlAction.PAUSE)
        if (error != null) {
            timeline.appendNote(null, error)
            publish(true)
            return error
        }
        taskTurnPaused = true
        pausedTurn = true
        val message = "已向远端发送暂停指令：任务会在原地停下，详见任务页。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    private fun resumeTaskTurn(): String {
        val error = sendTaskTurnControl(ControlAction.RESUME)
        if (error != null) {
            timeline.appendNote(null, error)
            publish(true)
            return error
        }
        taskTurnPaused = false
        pausedTurn = false
        val message = "已向远端发送继续指令：任务继续执行。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    private fun stopTaskTurn(): String {
        val error = sendTaskTurnControl(ControlAction.STOP)
        if (error != null) {
            timeline.appendNote(null, "$error 本地轮次先行结束，远端任务可能仍在运行。")
        }
        taskTurnGoalChanged = false
        taskTurnJob?.cancel()
        val message = "已请求停止：已向远端发送停止指令，正在结束本轮。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    private fun changeGoalTaskTurn(): String {
        val error = sendTaskTurnControl(ControlAction.STOP)
        if (error != null) {
            timeline.appendNote(null, "$error 本地轮次先行结束，远端任务可能仍在运行。")
        }
        taskTurnGoalChanged = true
        taskTurnJob?.cancel()
        val message = "旧目标已作废：已向远端发送停止指令；请在输入框写下新目标并发送。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    /**
     * 阶段 C（V5 紧急开发）：Manus 轮次——消息经 Manus 聊天桥接发往
     * Manus 云端任务型 API，不经过任何大脑。
     *
     * 与 [startRemoteTurn] 对称：同一套 active 互斥、同一套轮次→任务
     * 镜像（TurnTaskRecorder）、同一套终态落法、同一张
     * ConversationStore（source "manus"，恢复历史时同一条链路读回）；
     * 不同的是执行体是 [ManusChatPort.runChat] 的聊天事件流：
     * - Progress → 轮次内备注（真实进度）；
     * - Result → 助手正文；
     * - Failed → 终态 ERROR，文案已归属 Manus，不回落大脑、不伪装成功。
     * 多轮连续靠 conversationId → Manus task_id 的持久映射
     * （桥接层内），不是靠新开任务。
     */
    private fun startManusTurn(
        comps: BrainBootstrap.BrainComponents,
        agent: AgentRecord,
        prompt: String,
        /** 发给 Manus 的正文（附件展开版）；默认等于 [prompt]。 */
        modelPrompt: String = prompt,
        /** 本轮随附的附件（时间线卡片用）；默认空。 */
        attachments: List<ChatAttachment> = emptyList(),
        /** 图片看不见时的诚实说明；默认 null。 */
        imageNote: String? = null,
    ): SendResult {
        val port = manusPort ?: run {
            val message = "Manus 聊天未接线：请重启 App 后重试。"
            timeline.beginSystemTurn(message)
            publish(true)
            return SendResult(false, message)
        }
        if (!active.compareAndSet(false, true)) {
            return SendResult(false, "上一轮还在执行。")
        }
        pausedTurn = false
        manusTurnPaused = false
        manusTurnGoalChanged = false
        val convId = conversationId ?: UUID.randomUUID().toString().also {
            conversationId = it
        }
        val turnId = timeline.beginUserTurn(prompt, agent.displayName, false)
        attachToTurn(turnId, attachments, imageNote)
        publish(true)
        // 轮次开任务（2026-11-12 回复归属修复）：与远端轮次同款，
        // 用 beginRemoteRound 只建行、不经调度器认领门禁；建不出行
        // 就中止轮次、如实报错，绝不让回复没有任务行可归属。
        // 镜像按 Manus 认领、本轮挂归属（大脑/Agent 边界修订）。
        val taskId = turnRecorder?.beginRemoteRound(prompt, convId, agent.agentId, currentProjectId())
        if (taskId == null) {
            active.set(false)
            val detail = turnRecorder?.lastBeginFailure()
            val message = if (detail.isNullOrBlank()) {
                "轮次任务建行失败，消息未发出（任务记录器不可用），请重试。"
            } else {
                "轮次任务建行失败，消息未发出：$detail"
            }
            timeline.finishTurn(turnId, TurnStatus.ERROR, message)
            publish(true)
            return SendResult(false, message)
        }
        timeline.setTaskAttribution(turnId, taskId, prompt)
        manusTurnTaskId = taskId
        manusTurnConversationId = convId
        manusTurnAgent = agent
        val assistantText = StringBuilder()
        manusTurnJob = scope.launch {
            var cancelled = false
            var terminalStatus: TurnStatus? = null
            var terminalError: String? = null
            // 流式状态（本轮内）：快照按消息 id 记、工具按次数记、附件按
            // file_uid/url 去重；与 startManusTaskTurn 同一套 helper。
            val stream = ManusStreamState()
            try {
                port.runChat(convId, agent, modelPrompt).collect { event ->
                    when (event) {
                        is ManusChatEvent.Failed -> {
                            terminalStatus = TurnStatus.ERROR
                            terminalError = event.message
                            publish(true)
                        }
                        else -> applyManusStreamEvent(
                            event = event,
                            stream = stream,
                            turnId = turnId,
                            assistantText = assistantText,
                        )
                    }
                }
            } catch (_: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                terminalStatus = TurnStatus.ERROR
                terminalError = "调用${agent.displayName}失败：${e.message ?: "未知错误"}"
            } finally {
                timeline.cancelRunningTools(turnId)
                val resolvedStatus = when {
                    manusTurnGoalChanged -> TurnStatus.GOAL_CHANGED
                    manusTurnPaused -> TurnStatus.PAUSED
                    cancelled -> TurnStatus.CANCELLED
                    terminalStatus != null -> terminalStatus!!
                    assistantText.isBlank() -> TurnStatus.EMPTY
                    else -> TurnStatus.DONE
                }
                val resolutionMessage = when (resolvedStatus) {
                    TurnStatus.EMPTY ->
                        "该 Agent 没有返回可显示的内容（空响应）。请重试；若持续出现，请检查该 Agent 的配置与网络。"
                    TurnStatus.CANCELLED -> "本轮已取消。"
                    TurnStatus.PAUSED -> "本轮已暂停：Manus 任务已停止，发送新消息可继续同一任务。"
                    TurnStatus.GOAL_CHANGED -> "本轮已改目标，剩余步骤未执行。"
                    else -> terminalError
                }
                if (resolvedStatus == TurnStatus.EMPTY ||
                    resolvedStatus == TurnStatus.CANCELLED ||
                    resolvedStatus == TurnStatus.PAUSED ||
                    resolvedStatus == TurnStatus.GOAL_CHANGED
                ) {
                    timeline.appendNote(turnId, resolutionMessage.orEmpty())
                }
                // 2026-11-12 回复归属修复：有真实回复时，正文前加
                // 任务归属头（任务编号 + 轮次标题），聊天、存库消息、
                // 任务结果三处一致；空响应/失败/取消/暂停不加。
                if (resolvedStatus == TurnStatus.DONE && assistantText.isNotBlank()) {
                    val attributed = replyAttributionHeader(taskId, prompt) + "\n" + assistantText.toString()
                    assistantText.clear()
                    assistantText.append(attributed)
                    timeline.setAssistantText(turnId, attributed)
                }
                timeline.finishTurn(
                    turnId = turnId,
                    status = resolvedStatus,
                    error = if (resolvedStatus == TurnStatus.ERROR) resolutionMessage else null,
                )
                turnRecorder?.finishTurn(
                    taskId = taskId,
                    status = resolvedStatus,
                    assistantText = assistantText.toString(),
                    error = if (resolvedStatus == TurnStatus.ERROR) resolutionMessage else null,
                    hadToolCalls = stream.toolCount > 0,
                )
                persistRemoteTurn(convId, prompt, assistantText.toString(), taskId, source = "manus")
                pausedTurn = resolvedStatus == TurnStatus.PAUSED
                manusTurnJob = null
                manusTurnTaskId = null
                manusTurnConversationId = null
                manusTurnAgent = null
                // 已暂停时保留 manusTurnPaused（resume() 靠它认出可恢复
                // 的 Manus 轮次）；其他终态才复位。
                if (resolvedStatus != TurnStatus.PAUSED) manusTurnPaused = false
                active.set(false)
                publish(true)
                onTurnFinished?.invoke()
            }
        }
        return SendResult(true)
    }

    /**
     * 平台 Agent 轮次（V5，2026-11-11）：消息经平台频道发往该 Agent，
     * 不经过任何大脑。
     *
     * 与 [startManusTurn] 对称：同一套 active 互斥、同一套轮次→任务
     * 镜像（TurnTaskRecorder）、同一套终态落法、同一张
     * ConversationStore（source "platform"，恢复历史时同一条链路读回）；
     * 不同的是执行体是 [PlatformChatPort.runChat] 的聊天事件流：
     * - Progress → 轮次内备注（已发往平台 / 正在等回复）；
     * - Result → 助手正文；
     * - Failed → 终态 ERROR，文案已归属平台通道，不回落大脑、不伪装成功。
     * 只发任务消息，不传代码、不推代码。对方先发确认消息会被当作
     * 答案（桥接层的已知局限，见 [PlatformAgentChatBridge]）。
     * 暂停/停止/改目标只取消本地等待：已发出的消息仍在平台频道里，
     * 对方可能还会回复，那条回复不会再显示在本轮——如实说明，不假
     * 装“已撤回”。
     */
    private fun startPlatformTurn(
        comps: BrainBootstrap.BrainComponents,
        agent: AgentRecord,
        prompt: String,
    ): SendResult {
        val port = platformPort ?: run {
            val message = "平台 Agent 聊天未接线：请重启 App 后重试。"
            timeline.beginSystemTurn(message)
            publish(true)
            return SendResult(false, message)
        }
        if (!active.compareAndSet(false, true)) {
            return SendResult(false, "上一轮还在执行。")
        }
        pausedTurn = false
        platformTurnPaused = false
        platformTurnGoalChanged = false
        val convId = conversationId ?: UUID.randomUUID().toString().also {
            conversationId = it
        }
        val turnId = timeline.beginUserTurn(prompt, agent.displayName, false)
        publish(true)
        // 轮次开任务（2026-11-12 回复归属修复）：与远端/Manus 轮次
        // 同款，用 beginRemoteRound 只建行、不经调度器认领门禁；
        // 建不出行就中止轮次、如实报错，绝不让回复没有任务行可归属。
        // 镜像按该平台 Agent 认领、本轮挂归属（大脑/Agent 边界修订）。
        val taskId = turnRecorder?.beginRemoteRound(prompt, convId, agent.agentId, currentProjectId())
        if (taskId == null) {
            active.set(false)
            val detail = turnRecorder?.lastBeginFailure()
            val message = if (detail.isNullOrBlank()) {
                "轮次任务建行失败，消息未发出（任务记录器不可用），请重试。"
            } else {
                "轮次任务建行失败，消息未发出：$detail"
            }
            timeline.finishTurn(turnId, TurnStatus.ERROR, message)
            publish(true)
            return SendResult(false, message)
        }
        timeline.setTaskAttribution(turnId, taskId, prompt)
        platformTurnTaskId = taskId
        platformTurnAgent = agent
        val assistantText = StringBuilder()
        platformTurnJob = scope.launch {
            var cancelled = false
            var terminalStatus: TurnStatus? = null
            var terminalError: String? = null
            try {
                port.runChat(convId, agent, prompt).collect { event ->
                    when (event) {
                        is PlatformChatEvent.Progress -> {
                            timeline.appendNote(turnId, event.message)
                            publish()
                        }
                        is PlatformChatEvent.Result -> {
                            assistantText.append(event.text)
                            timeline.appendAssistantText(turnId, event.text)
                            publish()
                        }
                        is PlatformChatEvent.Failed -> {
                            terminalStatus = TurnStatus.ERROR
                            terminalError = event.message
                            publish(true)
                        }
                    }
                }
            } catch (_: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                terminalStatus = TurnStatus.ERROR
                terminalError = "调用${agent.displayName}失败：${e.message ?: "未知错误"}"
            } finally {
                val resolvedStatus = when {
                    platformTurnGoalChanged -> TurnStatus.GOAL_CHANGED
                    platformTurnPaused -> TurnStatus.PAUSED
                    cancelled -> TurnStatus.CANCELLED
                    terminalStatus != null -> terminalStatus!!
                    assistantText.isBlank() -> TurnStatus.EMPTY
                    else -> TurnStatus.DONE
                }
                val resolutionMessage = when (resolvedStatus) {
                    TurnStatus.EMPTY ->
                        "该 Agent 没有返回可显示的内容（空响应）。请重试；若持续出现，请检查该 Agent 的配置与网络。"
                    TurnStatus.CANCELLED -> "本轮已取消。"
                    TurnStatus.PAUSED -> "本轮已暂停：本地等待已取消；已发出的消息仍在平台频道里，对方可能还会回复，那条回复不会再显示在本轮。"
                    TurnStatus.GOAL_CHANGED -> "本轮已改目标，剩余步骤未执行。"
                    else -> terminalError
                }
                if (resolvedStatus == TurnStatus.EMPTY ||
                    resolvedStatus == TurnStatus.CANCELLED ||
                    resolvedStatus == TurnStatus.PAUSED ||
                    resolvedStatus == TurnStatus.GOAL_CHANGED
                ) {
                    timeline.appendNote(turnId, resolutionMessage.orEmpty())
                }
                // 2026-11-12 回复归属修复：有真实回复时，正文前加
                // 任务归属头（任务编号 + 轮次标题），聊天、存库消息、
                // 任务结果三处一致；空响应/失败/取消/暂停不加。
                if (resolvedStatus == TurnStatus.DONE && assistantText.isNotBlank()) {
                    val attributed = replyAttributionHeader(taskId, prompt) + "\n" + assistantText.toString()
                    assistantText.clear()
                    assistantText.append(attributed)
                    timeline.setAssistantText(turnId, attributed)
                }
                timeline.finishTurn(
                    turnId = turnId,
                    status = resolvedStatus,
                    error = if (resolvedStatus == TurnStatus.ERROR) resolutionMessage else null,
                )
                turnRecorder?.finishTurn(
                    taskId = taskId,
                    status = resolvedStatus,
                    assistantText = assistantText.toString(),
                    error = if (resolvedStatus == TurnStatus.ERROR) resolutionMessage else null,
                    hadToolCalls = false,
                )
                persistRemoteTurn(convId, prompt, assistantText.toString(), taskId, source = "platform")
                pausedTurn = resolvedStatus == TurnStatus.PAUSED
                platformTurnJob = null
                platformTurnTaskId = null
                platformTurnAgent = null
                // 已暂停时保留 platformTurnPaused（resume() 靠它认出可恢复
                // 的平台轮次）；其他终态才复位。
                if (resolvedStatus != TurnStatus.PAUSED) platformTurnPaused = false
                active.set(false)
                publish(true)
                onTurnFinished?.invoke()
                // 终态 DONE 且真回了内容 = 一次真实往返成功，比频道
                // 可读探测更强的可用证据；通知回调方把该 Agent 标成已验证。
                if (resolvedStatus == TurnStatus.DONE && assistantText.isNotBlank()) {
                    onRemoteRoundSucceeded?.invoke(agent)
                }
            }
        }
        return SendResult(true)
    }

    /**
     * Manus 任务轮次（V5 修复，2026-11-11）：发给 Manus 的任务型指令。
     *
     * 为什么不能复用 startTaskTurn：Manus 是云端 Agent，没有设备，
     * RemoteTaskPump.isRemoteMember 要求 deviceId != null，Manus 行
     * 永远不会被泵认领；startTaskTurn 只会等 600 秒然后报“仍在后台”。
     * 所以本轮直接驱动 Manus：建分派任务行（任务页可见、可暂停/停止/
     * 改目标）→ 本轮认领并启动 → 桥接的流式事件（进度/工具/快照/附件）
     * 实时落对话 → 终态按 working→review→completed / failed /
     * cancelled 落行（与 LocalTaskRunner 同款落法）。
     */
    private fun startManusTaskTurn(
        comps: BrainBootstrap.BrainComponents,
        agent: AgentRecord,
        prompt: String,
        /** 发给 Manus 的任务目标（附件展开版）；默认等于 [prompt]。 */
        modelPrompt: String = prompt,
        /** 本轮随附的附件（时间线卡片用）；默认空。 */
        attachments: List<ChatAttachment> = emptyList(),
        /** 图片看不见时的诚实说明；默认 null。 */
        imageNote: String? = null,
    ): SendResult {
        val port = manusPort ?: run {
            val message = "Manus 聊天未接线：请重启 App 后重试。"
            timeline.beginSystemTurn(message)
            publish(true)
            return SendResult(false, message)
        }
        if (!active.compareAndSet(false, true)) {
            return SendResult(false, "上一轮还在执行。")
        }
        val taskId = ChatTaskDispatcher.dispatch(
            taskStore = comps.taskStore,
            agent = agent,
            prompt = modelPrompt,
            brainId = null,
            projectId = currentProjectId(),
        )
        if (taskId == null) {
            // 建行失败（任务库不可用）：回落到普通 Manus 轮次，聊天不断。
            active.set(false)
            return startManusTurn(comps, agent, prompt, modelPrompt, attachments, imageNote)
        }
        pausedTurn = false
        manusTurnPaused = false
        manusTurnGoalChanged = false
        // 认领并启动（本轮自己就是执行者，不等远端泵）。认领失败说明
        // 已有其他任务在手（一次一任务）或任务库异常：行若还在 queued
        // 就取消（它是本轮刚建的孤儿行），再回落普通聊天并明说。
        val claimed = comps.taskScheduler.claimTask(taskId, agent.agentId)
        val started = claimed?.let { comps.taskScheduler.startTask(taskId) }
        if (started == null) {
            val current = try {
                comps.taskStore.getTask(taskId)
            } catch (_: Exception) {
                null
            }
            if (current?.state == "queued") {
                updateTaskRow(
                    comps,
                    taskId,
                    mapOf("state" to "cancelled", "error" to "任务认领失败，已取消。"),
                )
            }
            active.set(false)
            val reason = if (!comps.taskScheduler.canClaim()) "已有其他任务在手" else "任务库异常"
            timeline.beginSystemTurn("任务行认领失败（$reason），已改走普通聊天（本轮只对话、不执行任务）。")
            publish(true)
            return startManusTurn(comps, agent, prompt)
        }
        val convId = conversationId ?: UUID.randomUUID().toString().also {
            conversationId = it
        }
        val turnId = timeline.beginUserTurn(prompt, agent.displayName, false)
        attachToTurn(turnId, attachments, imageNote)
        timeline.appendStatusNote(turnId, "任务已创建，正在由 ${agent.displayName} 执行；进度与结果会实时流到这里。")
        publish(true)
        manusTaskTurnTaskId = taskId
        manusTurnTaskId = taskId
        manusTurnConversationId = convId
        manusTurnAgent = agent
        val assistantText = StringBuilder()
        val startedAtMs = System.currentTimeMillis()
        manusTurnJob = scope.launch {
            var cancelled = false
            var terminalStatus: TurnStatus? = null
            var terminalError: String? = null
            val stream = ManusStreamState()
            try {
                port.runChat(convId, agent, modelPrompt).collect { event ->
                    when (event) {
                        is ManusChatEvent.Failed -> {
                            terminalStatus = TurnStatus.ERROR
                            terminalError = event.message
                            publish(true)
                        }
                        else -> applyManusStreamEvent(
                            event = event,
                            stream = stream,
                            turnId = turnId,
                            assistantText = assistantText,
                            taskId = taskId,
                            taskComponents = comps,
                        )
                    }
                }
            } catch (_: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                terminalStatus = TurnStatus.ERROR
                terminalError = "调用${agent.displayName}失败：${e.message ?: "未知错误"}"
            } finally {
                timeline.cancelRunningTools(turnId)
                val resolvedStatus = when {
                    manusTurnGoalChanged -> TurnStatus.GOAL_CHANGED
                    manusTurnPaused -> TurnStatus.PAUSED
                    cancelled -> TurnStatus.CANCELLED
                    terminalStatus != null -> terminalStatus!!
                    assistantText.isBlank() && stream.attachments.isEmpty() -> TurnStatus.EMPTY
                    else -> TurnStatus.DONE
                }
                val resolutionMessage = when (resolvedStatus) {
                    TurnStatus.EMPTY ->
                        "该 Agent 没有返回可显示的内容（空响应）。请重试；若持续出现，请检查该 Agent 的配置与网络。"
                    TurnStatus.CANCELLED -> "本轮已取消。"
                    TurnStatus.PAUSED -> "本轮已暂停：Manus 任务已停止，发送新消息可继续同一任务。"
                    TurnStatus.GOAL_CHANGED -> "本轮已改目标，剩余步骤未执行。"
                    else -> terminalError
                }
                if (resolvedStatus == TurnStatus.EMPTY ||
                    resolvedStatus == TurnStatus.CANCELLED ||
                    resolvedStatus == TurnStatus.PAUSED ||
                    resolvedStatus == TurnStatus.GOAL_CHANGED
                ) {
                    timeline.appendNote(turnId, resolutionMessage.orEmpty())
                }
                if (resolvedStatus == TurnStatus.DONE || resolvedStatus == TurnStatus.EMPTY) {
                    timeline.appendNote(turnId, "用时 ${formatElapsed(System.currentTimeMillis() - startedAtMs)}。")
                }
                timeline.finishTurn(
                    turnId = turnId,
                    status = resolvedStatus,
                    error = if (resolvedStatus == TurnStatus.ERROR) resolutionMessage else null,
                )
                // 任务行落终态（与 LocalTaskRunner 同款：working→review→
                // completed；review 不可直达 completed 以外的终态，故
                // failed/cancelled 从 working 直落）。
                finishManusTaskRow(
                    comps = comps,
                    taskId = taskId,
                    status = resolvedStatus,
                    assistantText = assistantText.toString(),
                    error = if (resolvedStatus == TurnStatus.ERROR) resolutionMessage else null,
                )
                persistRemoteTurn(convId, prompt, assistantText.toString(), taskId, source = "manus")
                pausedTurn = resolvedStatus == TurnStatus.PAUSED
                manusTurnJob = null
                manusTurnTaskId = null
                manusTaskTurnTaskId = null
                manusTurnConversationId = null
                manusTurnAgent = null
                // 已暂停时保留 manusTurnPaused（resume() 靠它认出可恢复
                // 的 Manus 轮次）；其他终态才复位。
                if (resolvedStatus != TurnStatus.PAUSED) manusTurnPaused = false
                active.set(false)
                publish(true)
                onTurnFinished?.invoke()
            }
        }
        return SendResult(true)
    }

    /**
     * 一轮 Manus 流式事件的去重/累计状态（本轮内，不持久化）：
     * snapshots 按 assistant_message id 记最新全文快照；
     * attachments 按 file_uid/url 去重累计；toolCount 记工具卡数。
     */
    private class ManusStreamState {
        val snapshots = LinkedHashMap<String, String>()
        val seenAttachmentKeys = HashSet<String>()
        val attachments = ArrayList<ManusTaskAttachment>()
        var toolCount: Int = 0
    }

    /**
     * 把一个 Manus 流式事件落到对话时间线（startManusTurn 与
     * startManusTaskTurn 共用；任务轮次多记一步任务步骤）。
     * - Progress：备注行；
     * - TextSnapshot：累计快照整体替换正文（老项目 turn_timeline.js
     *   同规则：替换不累加；多条 assistant_message 按到达顺序拼接）；
     * - ToolUsed：工具卡（运行中→成功/失败；API 只在完成时才可见，
     *   无进行中信号，不伪造）+ 任务步骤；
     * - Attachments：产物汇总备注；
     * - Result：终态正文（已流出的不重复贴）+ 终态页带来的新附件；
     * - Failed：由调用方处理终态，这里不重复。
     */
    private fun applyManusStreamEvent(
        event: ManusChatEvent,
        stream: ManusStreamState,
        turnId: String,
        assistantText: StringBuilder,
        taskId: String? = null,
        taskComponents: BrainBootstrap.BrainComponents? = null,
    ) {
        when (event) {
            is ManusChatEvent.Progress -> {
                timeline.appendStatusNote(turnId, event.message)
                publish()
            }
            is ManusChatEvent.TextSnapshot -> {
                stream.snapshots[event.messageId] = event.text
                val combined = stream.snapshots.values.joinToString("\n\n")
                timeline.setAssistantText(turnId, combined)
                assistantText.clear()
                assistantText.append(combined)
                publish()
            }
            is ManusChatEvent.ToolUsed -> {
                stream.toolCount++
                val callId = "manus-${event.id}"
                timeline.addToolCall(turnId, callId, event.tool, mapOf("brief" to event.brief))
                timeline.addToolResult(turnId, callId, event.ok, event.detail.ifBlank { event.brief })
                recordManusTaskStep(taskId, taskComponents, callId, event)
                publish()
            }
            is ManusChatEvent.Attachments -> {
                val fresh = event.files.filter { stream.seenAttachmentKeys.add(it.dedupeKey()) }
                if (fresh.isEmpty()) return
                stream.attachments.addAll(fresh)
                timeline.addAttachments(turnId, toChatAttachments(fresh))
                publish()
            }
            is ManusChatEvent.Result -> {
                applyManusResultText(event, stream, turnId, assistantText)
            }
            is ManusChatEvent.Failed -> {
                // Failed 由调用方处理终态（terminalStatus），这里不重复。
            }
        }
    }

    /**
     * 终态正文回填（旧口径：仍为完整正文）。已通过快照流出的不重复贴；
     * 未流出的部分整体替换（快照是同一条消息的旧版本，绝不拼接）。
     * 终态页带来的、流式里没见过的附件（防御：桥接正常已先发
     * Attachments）一并汇总。
     */
    private fun applyManusResultText(
        event: ManusChatEvent.Result,
        stream: ManusStreamState,
        turnId: String,
        assistantText: StringBuilder,
    ) {
        val text = event.text
        if (text.isNotBlank() && !assistantText.contains(text)) {
            timeline.setAssistantText(turnId, text)
            assistantText.clear()
            assistantText.append(text)
            publish()
        }
        val fresh = event.attachments.filter { stream.seenAttachmentKeys.add(it.dedupeKey()) }
        if (fresh.isNotEmpty()) {
            stream.attachments.addAll(fresh)
            timeline.addAttachments(turnId, toChatAttachments(fresh))
            publish()
        }
    }

    /**
     * V5 聊天清理 20261111：Manus 附件转产物卡片数据（文件名 + 临时链接）。
     * 只转已有结构，不编内容；Manus API 不提供文件 +/- 行数，卡片也不显示；
     * 链接临时有效，失效时不编新链接（卡片只注明“临时链接”）。
     */
    private fun toChatAttachments(files: List<ManusTaskAttachment>): List<ChatAttachmentUi> =
        files.map { file ->
            ChatAttachmentUi(
                key = file.dedupeKey(),
                name = file.displayName(),
                url = file.url?.takeIf { it.isNotBlank() },
            )
        }

    /** 一个 Manus 工具使用记一步任务步骤（任务轮次才记；写库失败不打断聊天）。 */
    private fun recordManusTaskStep(
        taskId: String?,
        comps: BrainBootstrap.BrainComponents?,
        callId: String,
        event: ManusChatEvent.ToolUsed,
    ) {
        if (taskId == null || comps == null) return
        try {
            comps.taskStepStore.addStep(
                TaskStep(
                    stepId = UUID.randomUUID().toString(),
                    taskId = taskId,
                    toolCallId = callId,
                    toolName = event.tool,
                    argsSummary = event.brief.take(4000),
                    status = TaskStepStatus.RUNNING,
                    startedAtEpochSeconds = System.currentTimeMillis() / 1000,
                ),
            )
            comps.taskStepStore.finishStep(
                taskId = taskId,
                toolCallId = callId,
                status = if (event.ok) TaskStepStatus.SUCCESS else TaskStepStatus.FAILED,
                resultSummary = event.detail.takeIf { it.isNotBlank() }?.take(4000),
            )
        } catch (_: Exception) {
            // 步骤写库失败不打断聊天。
        }
    }

    /** 读现版 + optimistic 写（LocalTaskRunner 同款）；写失败返回 null，不抛。 */
    private fun updateTaskRow(
        comps: BrainBootstrap.BrainComponents,
        taskId: String,
        changes: Map<String, Any?>,
    ): TaskRecord? {
        return try {
            val current = comps.taskStore.getTask(taskId) ?: return null
            comps.taskStore.updateTask(
                taskId,
                changes,
                current.version,
                current.goalVersion,
                current.authEpoch,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Manus 任务行落终态（本轮自己就是执行者，不等泵；与
     * LocalTaskRunner 同款落法：working→review→completed；
     * failed/cancelled 从 working 直落；paused 落 blocked）。
     * 行状态读现版再写，写失败不打断聊天（行留在最后真实状态，
     * 绝不伪造完成）。
     */
    /**
     * V5 技能记忆：Manus 任务行落终态后抽技能（成功记做法，失败记
     * 坑；取消/暂停不是终态结论，不记）。与 LocalTaskRunner
     * .captureSkill 同款逻辑（Manus 轮次不走本地泵，须单独接）。
     * 捕获失败绝不影响落行。
     */
    private fun captureManusSkill(
        comps: BrainBootstrap.BrainComponents,
        taskId: String,
        success: Boolean,
        error: String?,
    ) {
        try {
            val task = comps.taskStore.getTask(taskId) ?: return
            val steps =
                try {
                    comps.taskStepStore.listSteps(taskId)
                } catch (_: Exception) {
                    emptyList()
                }
            val successful = steps.filter { it.status == TaskStepStatus.SUCCESS }
            val evidence =
                SettledTaskEvidence(
                    taskId = taskId,
                    projectId = task.projectId,
                    goal = (task.description ?: task.title).trim(),
                    success = success,
                    error = error?.take(500),
                    toolsUsed = successful.map { it.toolName }.distinct(),
                    keySteps =
                        successful.take(6).map { step ->
                            val detail =
                                step.resultSummary?.take(100)?.ifBlank { null }
                                    ?: step.argsSummary.take(100)
                            "${step.toolName} $detail".trim()
                        }.filter { it.isNotBlank() },
                )
            SkillRefiner(comps.skillStore).onTaskSettled(evidence)
        } catch (_: Exception) {
            // 技能捕获失败不影响落行。
        }
    }

    private fun finishManusTaskRow(
        comps: BrainBootstrap.BrainComponents,
        taskId: String,
        status: TurnStatus,
        assistantText: String,
        error: String?,
    ) {
        try {
            comps.taskStepStore.closeRunningSteps(taskId)
        } catch (_: Exception) {
            // 步骤收尾失败不打断落行。
        }
        when (status) {
            TurnStatus.DONE, TurnStatus.EMPTY -> {
                if (updateTaskRow(comps, taskId, mapOf("state" to "review")) == null) return
                updateTaskRow(
                    comps,
                    taskId,
                    mapOf("state" to "completed", "result" to assistantText.take(4000)),
                )
                // V5 技能记忆：成功任务抽技能。
                captureManusSkill(comps, taskId, true, null)
                // P1 eval 日志：终态已落，记一条。
                recordManusEval(comps, taskId, true)
            }
            TurnStatus.ERROR -> {
                updateTaskRow(
                    comps,
                    taskId,
                    mapOf("state" to "failed", "error" to (error ?: "执行失败").take(4000)),
                )
                // V5 技能记忆：失败任务记坑（若有相关技能）。
                captureManusSkill(comps, taskId, false, error)
                // P1 eval 日志：终态已落，记一条。
                recordManusEval(comps, taskId, false)
            }
            TurnStatus.CANCELLED, TurnStatus.GOAL_CHANGED -> {
                updateTaskRow(
                    comps,
                    taskId,
                    mapOf("state" to "cancelled", "result" to "本轮已取消，剩余步骤未执行。"),
                )
            }
            TurnStatus.PAUSED -> {
                updateTaskRow(comps, taskId, mapOf("state" to "blocked"))
            }
            TurnStatus.RUNNING, TurnStatus.STOPPED -> {
                // 轮次终态不可能是 RUNNING/STOPPED：走到这里说明调用方
                // 传错了，行留在最后真实状态，绝不伪造。
            }
        }
    }

    /**
     * P1 eval 日志：Manus 任务落终态后记一条（与 captureManusSkill
     * 同款：失败自己吞，不打断落行）。Manus 是云端 Agent，
     * deviceId 为空、assignedViaFallback="cloud"；本轮开始时间在
     * 此处拿不到，wallClock 记 null（不编造），耗时等以后接上
     * turn 开始时间再补。
     */
    private fun recordManusEval(
        comps: BrainBootstrap.BrainComponents,
        taskId: String,
        success: Boolean,
    ) {
        try {
            val task = comps.taskStore.getTask(taskId) ?: return
            EvalLogRecorder(comps.evalLogStore, comps.routingDecisionLog)
                .recordTerminal(
                    task,
                    if (success) EvalOutcome.COMPLETED else EvalOutcome.FAILED,
                    mode = "single",
                    startedAtMs = null,
                    deviceId = "",
                    assignedViaFallback = "cloud",
                )
        } catch (_: Exception) {
            // eval 记录失败不打断落行。
        }
    }

    /** "用时 X 分 Y 秒"（老项目 v6-task-duration 同形式；不足一分钟只写秒）。 */
    private fun formatElapsed(elapsedMs: Long): String {
        val seconds = (elapsedMs / 1000).coerceAtLeast(0)
        val minutes = seconds / 60
        val rest = seconds % 60
        return if (minutes > 0) "$minutes 分 $rest 秒" else "$rest 秒"
    }

    /** 远端轮次的用户 + 助手消息写进 ConversationStore（与脑轮次同一张表）。 */
    private fun persistRemoteTurn(
        convId: String,
        prompt: String,
        assistantText: String,
        taskId: String?,
        source: String = "remote-agent",
    ) {
        val store = components?.conversationStore ?: return
        try {
            store.getOrCreateConversation(convId, null, currentSessionLabel)
            val now = System.currentTimeMillis() / 1000
            store.addMessage(
                Message(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = "user",
                    content = prompt,
                    source = "user",
                    createdAtEpochSeconds = now,
                    taskId = taskId,
                ),
            )
            if (assistantText.isNotBlank()) {
                store.addMessage(
                    Message(
                        id = UUID.randomUUID().toString(),
                        conversationId = convId,
                        role = "assistant",
                        content = assistantText,
                        source = source,
                        createdAtEpochSeconds = now,
                        taskId = taskId,
                    ),
                )
            }
        } catch (_: Exception) {
            // 持久化失败不打断聊天；界面时间线仍在，重启后该轮次可能缺失。
        }
    }

    // -- 远端轮次控制（阶段 B）：暂停/继续/停止/改目标发往远端 Hand，
    // 流本身只在停止/改目标时取消；暂停不断流（服务端 SIGSTOP）。--

    private fun currentRemoteCounters(): Pair<Long, Long> {
        val task = remoteTurnTaskId?.let { components?.taskStore?.getTask(it) }
        return (task?.goalVersion ?: 1L) to (task?.authEpoch ?: 1L)
    }

    private fun sendRemoteControl(action: ControlAction): String? {
        val target = remoteTurnTarget ?: return "远端轮次目标丢失，无法发送控制指令。"
        // V5 修复（2026-11-11，CodeBuddy 卡死轮次）：控制指令用实际
        // 发给 Hand 的任务 id（[remoteRunTaskId]）。2026-11-12 回复
        // 归属修复后它与任务行 id（[remoteTurnTaskId]）恒相等。
        val taskId = remoteRunTaskId ?: return "远端轮次任务丢失，无法发送控制指令。"
        val sender = remoteControlSender
            ?: return "远端控制未接线：控制指令发不出去，本轮仍在远端运行，请检查后重试。"
        val (goalVersion, epoch) = currentRemoteCounters()
        sender.send(
            target,
            taskId,
            RemoteControlCommand(
                action = action,
                operationId = UUID.randomUUID().toString(),
                goalVersion = goalVersion,
                epoch = epoch,
            ),
        ) { result ->
            timeline.appendNote(
                null,
                if (result.confirmed) "远端已确认。" else "远端控制未确认：${result.message}",
            )
            publish(true)
        }
        return null
    }

    private fun pauseRemoteTurn(): String {
        val error = sendRemoteControl(ControlAction.PAUSE)
        if (error != null) {
            timeline.appendNote(null, error)
            publish(true)
            return error
        }
        remoteTurnPaused = true
        pausedTurn = true
        val message = "已向远端发送暂停指令：远端执行会在原地停下，流保持连接。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    private fun resumeRemoteTurn(): String {
        val error = sendRemoteControl(ControlAction.RESUME)
        if (error != null) {
            timeline.appendNote(null, error)
            publish(true)
            return error
        }
        remoteTurnPaused = false
        pausedTurn = false
        val message = "已向远端发送继续指令：远端执行继续。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    private fun stopRemoteTurn(): String {
        val error = sendRemoteControl(ControlAction.STOP)
        if (error != null) {
            // 控制发不出去也照常取消本地流（不让界面卡住），但如实说明
            // 远端可能还在跑。
            timeline.appendNote(null, "$error 本地轮次先行结束，远端执行可能仍在运行。")
        }
        remoteTurnGoalChanged = false
        // V5 修复（2026-11-11，CodeBuddy 卡死轮次）：光 cancel()
        // 中断不了阻塞 socket 读，轮次要等 180 秒读超时才终态。
        // 先掐连接（abortRun 关 socket，阻塞读立刻抛），再 cancel
        // job，轮次的 finally 马上跑、active 马上清。
        remoteRunTaskId?.let { remotePort?.abortRun(it) }
        remoteTurnJob?.cancel()
        val message = "已请求停止：已向远端发送停止指令，正在结束本轮。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    private fun changeGoalRemoteTurn(): String {
        // 改目标时新目标用户还没输入：先发 STOP 停掉在途执行（线级
        // STOP；服务端侧的 goal_version 涨幅走 ControlPlane 的
        // CHANGE_GOAL，不走聊天轮次），新目标随下一条消息发。
        val error = sendRemoteControl(ControlAction.STOP)
        if (error != null) {
            timeline.appendNote(null, "$error 本地轮次先行结束，远端执行可能仍在运行。")
        }
        remoteTurnGoalChanged = true
        remoteRunTaskId?.let { remotePort?.abortRun(it) }
        remoteTurnJob?.cancel()
        val message = "旧目标已作废：已向远端发送停止指令；请在输入框写下新目标并发送。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    // -- Manus 轮次控制（阶段 C）：暂停/停止/改目标都先停掉 Manus
    // 侧的任务（stopTask），再取消本地流；暂停不断上下文（任务
    // id 保留，下一条消息继续同一任务），改目标才忘掉旧任务。--

    private fun stopManusTaskBestEffort(): Boolean {
        val convId = manusTurnConversationId ?: return false
        val agent = manusTurnAgent ?: return false
        return try {
            manusPort?.stopTask(convId, agent) ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun pauseManusTurn(): String {
        if (!stopManusTaskBestEffort()) {
            timeline.appendNote(null, "Manus 任务停止指令未确认：本地轮次先行停下，Manus 侧任务可能仍在运行。")
        }
        manusTurnPaused = true
        pausedTurn = true
        manusTurnJob?.cancel()
        val message = "已请求暂停 Manus 任务：已向 Manus 发送停止指令，本轮停下；发送新消息可继续同一任务。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    private fun stopManusTurn(): String {
        stopManusTaskBestEffort()
        manusTurnPaused = false
        manusTurnGoalChanged = false
        manusTurnJob?.cancel()
        val message = "已请求停止：已向 Manus 发送停止指令，正在结束本轮。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    private fun changeGoalManusTurn(): String {
        stopManusTaskBestEffort()
        // 改目标后忘掉旧任务：下一条消息建新任务，不把旧目标的
        // 上下文带进新目标。
        manusTurnConversationId?.let { manusPort?.forgetConversation(it) }
        manusTurnGoalChanged = true
        manusTurnJob?.cancel()
        val message = "旧目标已作废：已向 Manus 发送停止指令；请在输入框写下新目标并发送。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    // -- 平台 Agent 轮次控制（V5，2026-11-11）：没有远端控制指令可发
    // （消息一旦发出就留在平台频道里，撤不回）；暂停/停止/改目标都只
    // 取消本地等待，并如实说明已发消息的去向。暂停不断上下文：恢复时
    // 发一条跟进消息问对方继续（见 resume() 的平台分支）。--

    private fun pausePlatformTurn(): String {
        platformTurnPaused = true
        pausedTurn = true
        platformTurnJob?.cancel()
        val message = "已暂停：本地等待已取消；已发出的消息仍在平台频道里，对方可能还会回复，那条回复不会再显示在本轮。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    private fun stopPlatformTurn(): String {
        platformTurnPaused = false
        platformTurnGoalChanged = false
        platformTurnJob?.cancel()
        val message = "已请求停止：本地等待已取消；已发出的消息仍在平台频道里，对方可能还会回复，那条回复不会再显示在本轮。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    private fun changeGoalPlatformTurn(): String {
        platformTurnGoalChanged = true
        platformTurnJob?.cancel()
        val message = "旧目标已作废：本地等待已取消；已发出的消息仍在平台频道里，请在输入框写下新目标并发送。"
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    private fun startFallbackTurn(prompt: String): SendResult {
        if (!active.compareAndSet(false, true)) {
            return SendResult(false, "上一轮还在执行。")
        }
        val turnId = timeline.beginUserTurn(prompt, "设备 Agent（未连接大脑）", false)
        timeline.appendNote(
            turnId,
            "大脑未连接：这句话走本机设备 Agent。若 CodeBuddy/Codex 都没装，会如实失败。" +
                "想获得模型回复，请在设置里配置 DeepSeek/Cloudflare 或登录 ChatGPT。",
        )
        publish(true)
        val agentRunner = runner
        if (agentRunner == null) {
            timeline.appendNote(turnId, "AgentRunner 尚未初始化，本轮未执行。")
            timeline.finishTurn(turnId, TurnStatus.ERROR, "AgentRunner 未初始化")
            active.set(false)
            publish(true)
            return SendResult(false, "AgentRunner 未初始化")
        }
        scope.launch {
            // 阶段 1（U3）：无大脑的回退轮次也镜像为任务（sessionId 带
            // chat-fallback 前缀，runner 永不认领；见 TurnTaskRecorder）。
            val taskId = turnRecorder?.beginTurn(prompt, "chat-fallback:$turnId", null, currentProjectId())
            var fallbackStatus = TurnStatus.ERROR
            var fallbackError: String? = "设备 Agent 执行失败"
            var fallbackText = ""
            try {
                val result = agentRunner.runPrompt(prompt) { line ->
                    timeline.appendAssistantText(turnId, line + "\n")
                    publish()
                }
                timeline.appendAssistantText(turnId, result)
                val failed = result.startsWith("Agent 失败") ||
                    result.startsWith("Agent 超时") ||
                    result.contains("都没装")
                fallbackStatus = if (failed) TurnStatus.ERROR else TurnStatus.DONE
                fallbackError = if (failed) result else null
                fallbackText = result
                timeline.finishTurn(
                    turnId,
                    fallbackStatus,
                    fallbackError,
                )
            } catch (e: Exception) {
                fallbackStatus = TurnStatus.ERROR
                fallbackError = e.message ?: "设备 Agent 执行失败"
                timeline.appendNote(turnId, "设备 Agent 执行失败：${e.message ?: "未知错误"}")
                timeline.finishTurn(turnId, TurnStatus.ERROR, e.message ?: "设备 Agent 执行失败")
            } finally {
                taskId?.let {
                    turnRecorder?.finishTurn(
                        taskId = it,
                        status = fallbackStatus,
                        assistantText = fallbackText,
                        error = fallbackError,
                        hadToolCalls = false,
                    )
                }
                active.set(false)
                publish(true)
                onTurnFinished?.invoke()
            }
        }
        return SendResult(true)
    }

    fun pause(): String {
        if (remoteTurnJob != null && active.get()) return pauseRemoteTurn()
        if (manusTurnJob != null && active.get()) return pauseManusTurn()
        if (platformTurnJob != null && active.get()) return pausePlatformTurn()
        if (taskTurnJob != null && active.get()) return pauseTaskTurn()
        val comps = components ?: return "大脑还在加载，请稍候。"
        if (!active.get()) return "当前没有正在执行的一轮，无需暂停。"
        val changed = comps.turnController.pause()
        approvalGate?.rejectPending("用户暂停")
        val message = if (changed) {
            "已请求暂停：本轮会在下一个安全检查点停下，不会自动续跑。"
        } else {
            "本轮已在停止/改目标状态，暂停不再生效。"
        }
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    fun stop(): String {
        if (remoteTurnJob != null && active.get()) return stopRemoteTurn()
        if (manusTurnJob != null && active.get()) return stopManusTurn()
        if (platformTurnJob != null && active.get()) return stopPlatformTurn()
        if (taskTurnJob != null && active.get()) return stopTaskTurn()
        // V5 修复（2026-11-11，CodeBuddy 卡死轮次）：三类 job 全空
        // 但 active 还占着 = 僵尸残留，清掉、如实报告，不让停止
        // 走大脑路径白白返回“已请求停止”却什么都没清。
        if (active.get() && releaseZombieTurnIfAny()) {
            return "上一轮已不在执行，残留状态已清理，可以继续。"
        }
        val comps = components ?: return "大脑还在加载，请稍候。"
        if (!active.get()) return "当前没有正在执行的一轮，无需停止。"
        val changed = comps.turnController.stop()
        approvalGate?.rejectPending("用户停止")
        val message = if (changed) {
            "已请求停止：正在运行的工具会尽快终止，本轮不会自动重启。"
        } else {
            "本轮已处于停止状态。"
        }
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    fun changeGoal(): String {
        if (remoteTurnJob != null && active.get()) return changeGoalRemoteTurn()
        if (manusTurnJob != null && active.get()) return changeGoalManusTurn()
        if (platformTurnJob != null && active.get()) return changeGoalPlatformTurn()
        if (taskTurnJob != null && active.get()) return changeGoalTaskTurn()
        val comps = components ?: return "大脑还在加载，请稍候。"
        if (!active.get()) {
            return "当前没有正在执行的一轮：直接输入新目标发送即可。"
        }
        val changed = comps.turnController.changeGoal()
        approvalGate?.rejectPending("用户改目标")
        val message = if (changed) {
            "旧目标已作废：本轮会在安全检查点停下；请在输入框写下新目标并发送。"
        } else {
            "本轮已停止，请在输入框写下新目标并发送。"
        }
        timeline.appendNote(null, message)
        publish(true)
        return message
    }

    /**
     * 阶段 3（U4）：恢复上一轮已暂停的剩余步骤。
     *
     * 语义：引擎停轮后本轮的流已经结束，恢复不是“唤醒已停的流”，
     * 而是在同一会话里开一个继续轮次（复用对话历史，模型接着跑剩余
     * 步骤）。旧轮次的任务行保持 cancelled（历史记录），新轮次开新
     * 任务行。没有可恢复的已暂停轮次时如实返回，不伪造恢复。
     */
    fun resume(): String {
        // 阶段 B：远端已暂停的轮次不是“开一个继续轮次”，而是向远端
        // 发 RESUME（服务端 SIGCONT），流继续走。
        if (remoteTurnPaused && remoteTurnJob != null) return resumeRemoteTurn()
        // V5 修复（2026-11-11）：任务轮次已暂停时向远端发 RESUME。
        if (taskTurnPaused && taskTurnJob != null) return resumeTaskTurn()
        // 阶段 C：Manus 已暂停的轮次是向同一 Manus 任务发跟进消息
        // （桥接层走 sendMessage 恢复已停止的任务），不是新开任务。
        if (manusTurnPaused && !active.get()) {
            manusTurnPaused = false
            pausedTurn = false
            val result = send("请继续执行上一步未完成的剩余工作，不要从头重做。")
            return if (result.accepted) {
                "已恢复：继续执行上一轮的剩余步骤。"
            } else {
                result.message ?: "恢复失败，请重试。"
            }
        }
        // 平台 Agent 已暂停的轮次：向同一 Agent 发一条跟进消息问它
        // 继续（上一条消息已留在频道里，不重发原问题）。
        if (platformTurnPaused && !active.get()) {
            platformTurnPaused = false
            pausedTurn = false
            val result = send("请继续执行上一步未完成的剩余工作，不要从头重做。")
            return if (result.accepted) {
                "已恢复：继续执行上一轮的剩余步骤。"
            } else {
                result.message ?: "恢复失败，请重试。"
            }
        }
        val comps = components ?: return "大脑还在加载，请稍候。"
        if (active.get()) return "上一轮还在执行，无需恢复。"
        if (!pausedTurn) return "没有可恢复的已暂停轮次。"
        if (!comps.turnController.resume()) {
            pausedTurn = false
            return "没有可恢复的已暂停轮次（控制态已复位）。"
        }
        pausedTurn = false
        val result = send("请继续执行上一步未完成的剩余工作，不要从头重做。")
        return if (result.accepted) {
            "已恢复：继续执行上一轮的剩余步骤。"
        } else {
            result.message ?: "恢复失败，请重试。"
        }
    }

    /**
     * 阶段 A：错误归属真实调用方。额度错误（quota_exceeded）必须写明
     * 是哪个大脑/Agent 的额度耗尽，不能把服务端英文原样当回复贴出来，
     * 让用户误以为是另一个 Agent 的问题。原始错误码与原文保留在后，
     * 供诊断；中文归属在前，供用户判断。
     */
    private fun formatTurnError(event: BrainEvent.Error, callerLabel: String): String =
        if (event.code == "quota_exceeded") {
            "quota_exceeded：${callerLabel}额度已耗尽：${event.message}"
        } else {
            "${event.code}：${event.message}"
        }

    private fun brainLabel(brainId: String?): String = when (brainId) {
        BrainBootstrap.DEEPSEEK_BRAIN_ID -> "DeepSeek"
        BrainBootstrap.CLOUDFLARE_BRAIN_ID -> "Cloudflare Workers AI"
        BrainBootstrap.SIWC_BRAIN_ID -> "ChatGPT"
        BrainBootstrap.CLI_BRAIN_ID -> "CLI 会员额度"
        null -> "未连接"
        // 通用大脑（model-api-<slug> 等）：用配置里的名字；没有配置时
        // 如实写 brainId，不编一个名字。
        else -> components?.configStore?.getConfig(brainId)?.label?.takeIf { it.isNotBlank() }
            ?: brainId
    }

    private fun publish(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastPublishMs.get()
        if (!force && now - last < 40L) return
        lastPublishMs.set(now)
        _state.postValue(timeline.snapshot())
    }

    override fun onCleared() {
        components?.turnController?.stop()
        active.set(false)
        scope.cancel()
        super.onCleared()
    }
}
