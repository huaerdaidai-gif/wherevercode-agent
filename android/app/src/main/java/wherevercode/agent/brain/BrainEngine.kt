package wherevercode.agent.brain

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.project.ProjectLocation
import wherevercode.agent.core.security.SecretRedactor
import wherevercode.agent.core.session.IncomingMessage
import wherevercode.agent.core.session.MessageKind
import wherevercode.agent.core.session.NoOpSessionLedger
import wherevercode.agent.core.session.SessionLedger

/**
 * BrainEngine: conversation loop + tool-call loop (stage 2).
 *
 * Pure Kotlin + coroutines; no Android framework dependency. Approval is
 * injected through [ApprovalGate] so the engine can be unit-tested with a
 * fake gate; the default implementation refuses anything that needs
 * confirmation (UI wiring is stage 3).
 *
 * Flow (audit §4.2 / §4.6):
 * 1. ContextManager assembles messages.
 * 2. Adapter.chatStream() yields BrainEvents, normalised by StreamParser.
 * 3. A ToolCall event goes through ToolRegistry validation
 *    (structure -> permission -> approval) and only then executes; the
 *    result is fed back as a role=tool message.
 * 4. Every event is written to [EventLog] (redacted first).
 * 5. Switching brains only changes the conversation's brainId; the task
 *    row never moves.
 */
interface ApprovalGate {
    suspend fun requestApproval(action: Action): Boolean

    /**
     * 2026-11-09：执行前的第二道授权检查。ToolRegistry 在审批通过、
     * 真正动手之前调用；默认实现不做额外限制（测试替身/无 UI 场景），
     * Android 审批门用它作废"界面已销毁后仍然有效"的授权。
     */
    fun isApprovalStillValid(action: Action): Boolean = true
}

/** Default gate: refuse anything that needs confirmation. UI replaces it in stage 3. */
object DenyApprovalGate : ApprovalGate {
    override suspend fun requestApproval(action: Action): Boolean = false
}

data class EngineEvent(
    val seq: Long,
    val conversationId: String,
    val kind: String,
    val detail: String,
    val epochSeconds: Long,
)

interface EventLog {
    fun record(event: EngineEvent)
    fun entries(conversationId: String? = null): List<EngineEvent>
}

class InMemoryEventLog : EventLog {
    private val lock = Any()
    private val items = mutableListOf<EngineEvent>()
    private var seq = 0L

    override fun record(event: EngineEvent) {
        synchronized(lock) {
            seq += 1
            items += event.copy(seq = seq)
        }
    }

    override fun entries(conversationId: String?): List<EngineEvent> =
        synchronized(lock) {
            if (conversationId == null) items.toList()
            else items.filter { it.conversationId == conversationId }
        }
}

data class ChatOutcome(
    val conversationId: String,
    val assistantText: String,
    val toolCallsExecuted: Int,
    val brainId: String,
    val switchedBrain: Boolean,
)

/** P1 §5.4：turn 内控制态快照（由调用方从 ControlPlane / CancellationToken 映射而来）。 */
enum class TurnControlState {
    RUNNING,
    PAUSED,
    STOPPED,
    GOAL_CHANGED,
}

/** P1 §5.2：decideToolsForRound 用的预算快照（轮次 / 调用次数 / 总时长）。 */
data class TurnBudget(
    val roundIndex: Int,
    val toolCallsExecuted: Int,
    val maxToolRounds: Int,
    val maxToolCallsPerTurn: Int,
    val elapsedMillis: Long,
    val maxTurnMillis: Long,
) {
    fun isExhausted(): Boolean =
        roundIndex >= maxToolRounds ||
            toolCallsExecuted >= maxToolCallsPerTurn ||
            (maxTurnMillis > 0 && elapsedMillis >= maxTurnMillis)
}

/**
 * P1 §1.3：一条用户消息内已执行 / 已缓存的工具调用记录。
 * 只活在本轮 chat() 调用内；调用结束即丢弃，不跨消息、不跨目标。
 * 缓存 key 含 brainId（§10.6）：换大脑后旧缓存不命中，但台账本身不清空
 * （写侧重复判定与进度判定仍以全台账为准，防换大脑后写调用重复落盘）。
 */
data class ToolCallRecord(
    val key: String, // toolCallKey() 的归一化签名
    val name: String,
    val args: Map<String, Any?>,
    val roundIndex: Int,
    val outcome: ToolOutcome,
    val fromCache: Boolean,
    val resultHash: String,
    val executedAtEpochSeconds: Long,
    val brainId: String,
    val idempotencyKey: String,
)

/** P1 §1.3：每轮消息内的工具台账（内存）。台账是权威；seenToolCalls 只作写侧快速判定。 */
class TurnToolLedger {
    private val records = LinkedHashMap<String, ToolCallRecord>()

    private fun compositeKey(key: String, brainId: String): String = "$brainId|$key"

    fun record(r: ToolCallRecord) {
        records[compositeKey(r.key, r.brainId)] = r
    }

    fun lookup(key: String, brainId: String): ToolCallRecord? =
        records[compositeKey(key, brainId)]

    /** 不同工具签名数（去 brainId 后去重），供进度判定用。 */
    val distinctKeyCount: Int get() = records.values.map { it.key }.distinct().size

    /** 最新一条记录的结果 hash（无记录时为空串），供进度判定用。 */
    val lastResultHash: String get() = records.values.lastOrNull()?.resultHash ?: ""
}

class BrainEngine(
    private val adapters: BrainAdapterRegistry,
    private val conversations: ConversationStore,
    private val contextManager: ContextManager,
    private val memory: MemoryStore,
    /**
     * V5 技能记忆（2026-11-11）：项目长期技能库。默认 null（老调用
     * 方与测试不动）；生产由 BrainBootstrap 注入 RoomSkillStore。
     * 每轮按任务内容捞相关技能进上下文（最多 3 条、约 300 token，
     * 无相关技能不注入），不是把全部历史塞进 prompt。
     */
    private val skillStore: SkillStore? = null,
    private val tools: ToolRegistry,
    private val eventLog: EventLog,
    private val approvalGate: ApprovalGate,
    private val currentBrainId: () -> String?,
    private val maxToolRounds: Int = 8,
    /**
     * P1 §5.2：真实执行次数上限（缓存命中不计）与单条消息总时长上限。
     * 默认 8 / 120 秒，不改动现有行为边界；maxTurnMillis=0 表示不限（仅测试用）。
     */
    private val maxToolCallsPerTurn: Int = 8,
    private val maxTurnMillis: Long = 120_000,
    /**
     * P1 §5.4：控制态快照（主 enforcement 的输入）。默认恒为运行态，
     * 现有调用点与测试不动；生产接线应映射 ControlPlane / CancellationToken。
     */
    private val controlState: () -> TurnControlState = { TurnControlState.RUNNING },
    /** 测试用时钟接缝：默认系统时间；测试可注入可控时钟以断言 turn_timeout。 */
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val failoverPolicy: FailoverPolicy = FailoverPolicy(),
    /**
     * M2 M9: optional session ledger. Default is the no-op ledger, so
     * existing callers and tests are untouched. When a real ledger is
     * injected, every turn is classified by S3 first: CONTROL
     * short-circuits here — it goes to the router, never reaches an
     * adapter, and costs zero model calls — while CHAT continues
     * through the existing flow and is recorded as a session message.
     * The engine only calls this interface; it never touches session
     * storage directly, and control still works when no brain is
     * configured (user sovereignty sits above the brain).
     */
    private val sessionLedger: SessionLedger = NoOpSessionLedger,
    /**
     * git 家（2026-11-11）：当前聊天项目的 git 家（由调用方
     * MainActivity 按打开的项目提供；无 git 家时返回 null）。每轮
     * chat() 开始时取一次快照：有 git 家才给模型 git 家工具。
     */
    private val gitHostProjectContext: () -> Pair<Project, ProjectLocation>? = { null },
    /**
     * 网页搜索客户端（V5 按主流改，maintainer 2026-11-11）：“+”菜单的网页搜索
     * 开关开了才用。默认走 Bing 网页搜索（无 Key），测试可注入假实现。
     */
    private val webSearch: WebSearchClient = WebSearchClient(),
) {
    /**
     * One user turn. Returns a cold Flow of BrainEvents (text deltas,
     * tool calls/results, usage, errors, done). The caller collects it;
     * history is persisted as the turn completes.
     *
     * [brainIdOverride] switches the brain for this conversation only;
     * the conversation row keeps its history, only brainId changes.
     *
     * 阶段 A：[allowFailover] = false 时，本轮可重试失败也不跨大脑
     * 回落（同大脑重试一次仍保留）。选中 Agent 的轮次必须传 false：
     * 用户选了 DeepSeek，失败后悄悄切到 ChatGPT 就是 bug 本身。
     * 默认 true，保持默认会话与分派任务的既有 failover 行为。
     *
     * V5 技能记忆：[projectId] 是本轮所属项目（分派任务传
     * task.projectId；自由聊天传 null → 只捞全局技能）。
     * skillStore 为空或无相关技能时不注入。
     *
     * V5 按主流改（maintainer 2026-11-11）：
     * - [currentMessageOverride]：发给模型的当轮正文（附件展开后的版本，
     *   含文本文件内容）；不传时用 [userText]。对话历史里存的永远是
     *   [userText]（附件只留描述行），文件内容不进历史、不每轮重发。
     * - [imageDataUrls]：当轮随附的图片（下采样后的 data URL）。只贴在
     *   当轮的 user 消息上；不支持 vision 的大脑在每轮发请求前过滤掉。
     * - [webSearchEnabled]：网页搜索开关。开了才联网搜，搜完把结果以
     *   system 消息插进本轮上下文；关了不搜、不联网。
     */
    fun chat(
        conversationId: String,
        userText: String,
        brainIdOverride: String? = null,
        allowFailover: Boolean = true,
        /**
         * a-3 交付卡：本轮的轮次任务 id（TurnTaskRecorder 开的任务）。
         * 有值时工具调用的 ToolContext.taskId 用它，写轮次的 ChangeSet
         * 才能按任务归属；null 时回落到会话 id（既有调用点不动）。
         */
        taskId: String? = null,
        projectId: String? = null,
        currentMessageOverride: String? = null,
        imageDataUrls: List<String> = emptyList(),
        webSearchEnabled: Boolean = false,
    ): Flow<BrainEvent> = flow {
        // 2026-11-09 blank-bubble fix: remember whether a specific Error
        // event was already emitted, so the final empty-response guard does
        // not replace/duplicate it. Every Error emission goes through
        // [emitError].
        var terminalErrorEmitted = false
        suspend fun emitError(err: BrainEvent.Error) {
            terminalErrorEmitted = true
            emit(err)
        }
        // V5 C3 chat-bugfix: reuse the caller-supplied conversation id.
        // createConversation() mints a fresh id, so before this fix every
        // turn fragmented into its own conversation + session row (and the
        // model only ever saw the current turn).
        val conversation = conversations.getOrCreateConversation(
            conversationId,
            brainIdOverride ?: currentBrainId(),
            userText.take(40),
        )
        val requestedBrainId = brainIdOverride ?: conversation.brainId
        // M2 M9: classify first. A CONTROL instruction short-circuits
        // here — recorded + routed by the ledger, no adapter call, no
        // model cost — and works even with no brain configured.
        val ledgerResult = sessionLedger.record(
            IncomingMessage(
                conversationId = conversation.id,
                senderId = "user",
                content = userText,
            ),
        )
        if (ledgerResult.kind == MessageKind.CONTROL) {
            val summary = ledgerResult.summary
            emit(BrainEvent.TextDelta(summary))
            log(conversation.id, "control_routed", SecretRedactor.redact(summary, 500))
            emit(BrainEvent.Done(if (ledgerResult.ok) "control" else "control_rejected"))
            return@flow
        }
        // 2026-11-11 brain_not_configured 回填：旧会话/旧任务行的
        // brainId 可能为 null（在 current 为 null 的窗口里落库），之后
        // 全局大脑再怎么改也不会自动纠正，旧会话一碰就复发。本轮没有
        // 大脑 id 时回落到当前全局大脑，并把纠正写回会话行；回落后
        // 仍为空才如实报未配置。绝不在这里“默认选一个已配置大脑”——
        // 无默认大脑是既定语义。CONTROL 短路在前，不受影响。
        var backfilledBrainId = false
        val effectiveBrainId: String? = when {
            !requestedBrainId.isNullOrBlank() -> requestedBrainId
            else -> currentBrainId()?.takeIf { it.isNotBlank() }?.also { fallback ->
                conversations.updateConversationBrain(conversation.id, fallback)
                log(conversation.id, "brain_backfilled", "旧会话未绑大脑，已按当前大脑 $fallback 继续")
                backfilledBrainId = true
            }
        }
        if (effectiveBrainId.isNullOrBlank()) {
            val err = BrainEvent.Error(
                code = "brain_not_configured",
                message = "未配置大脑：请先在设置里配置一个大脑，再发送消息。",
                retryable = false,
            )
            emitError(err)
            emit(BrainEvent.Done("error"))
            log(conversation.id, "error", err.message)
            return@flow
        }
        if (!backfilledBrainId && effectiveBrainId != conversation.brainId) {
            conversations.updateConversationBrain(conversation.id, effectiveBrainId)
            log(conversation.id, "brain_switched", "大脑已切换为 $effectiveBrainId，任务与历史不变")
        }

        conversations.addMessage(
            Message(
                id = newId(),
                conversationId = conversation.id,
                role = "user",
                content = userText,
                source = "user",
                createdAtEpochSeconds = nowSeconds(),
            ),
        )
        log(conversation.id, "user_message", SecretRedactor.redact(userText, 500))

        val history = conversations.listMessages(conversation.id)
        // git 家（2026-11-11）：本轮的 git 家快照（打开的项目
        // 是 git 家才有）；快照只活在本轮内，不跨消息。
        val gitHostContext = gitHostProjectContext()
        // P1 首轮分流：问候/闲聊首轮不带工具——上下文里的“可用工具”提示
        // 也不列出，否则系统提示仍会 primed 模型去行动；需要行动的首轮
        // 照常带全量工具定义。
        // 诚实边界（通用大脑放开切换，2026-11-11）：首轮工具按本轮大脑
        // 的真实能力算，不用 null 能力（null 会列全量工具）。通用大脑
        // 第二阶段起支持工具调用，首轮按实列工具，上下文与请求一致，
        // 模型只会用它能调的工具，不会被 primed 去“执行”它调不了的工具。
        val actionOriented = looksActionOriented(userText)
        val requestedCaps = effectiveBrainId.let { id ->
            adapters.configFor(id)?.let { adapters.get(it.adapterId) }?.capabilities()
        }
        val firstRoundTools = if (actionOriented) {
            toolsForTurn(requestedCaps, gitHostContext)
        } else {
            emptyList()
        }
        // V5 技能记忆：按本轮任务内容捞相关技能（项目技能优先、
        // 全局技能补位；无相关技能返回 ""，不烧 token）。
        val skillSummary =
            skillStore?.summarizeForPrompt(
                SkillMemory.projectIdOrGlobal(projectId),
                userText,
            ) ?: ""
        val assembled = contextManager.build(
            systemPrompt = SYSTEM_PROMPT,
            memorySummary = memory.summarize(),
            history = history,
            // V5 按主流改：发给模型的当轮正文是附件展开版（文本文件内容
            // inline），历史里存的仍是 userText（附件只留描述行）。
            currentMessage = currentMessageOverride ?: userText,
            tools = firstRoundTools,
            skillSummary = skillSummary,
            currentImages = imageDataUrls.map { ChatImage(it) },
        )
        val messages = assembled.messages.toMutableList()
        // V5 按主流改：当轮 user 消息的实例引用——图片只贴在它身上，
        // 每轮发请求前按当轮大脑的 vision 能力决定留不留（见 messagesForRound）。
        val currentUserMessage = messages.last()
        // V5 按主流改：网页搜索开关开了才搜。结果以 system 消息插在当轮
        // user 消息前面（只进本轮上下文，不持久化进对话历史）；搜不到 /
        // 搜失败也插一条诚实说明，不让模型以为自己查过网。
        if (webSearchEnabled) {
            messages.add(messages.size - 1, ChatMessage(role = "system", content = runWebSearch(conversation.id, userText)))
        }
        // V5 按主流改：每轮发请求前的图片过滤。当轮大脑支持 vision
        // 才把图片发过去；不支持时去掉图片，并在正文里明说看不到——
        // 绝不让模型对着一张它收不到的图片瞎编内容。failover 换大脑后
        // 也按新大脑的能力重算（所以每轮都算，不是一次定死）。
        // 注意：从原始 messages 列表每轮重新映射，正文里的提示不会累加。
        fun messagesForRound(adapter: BrainAdapter): List<ChatMessage> {
            if (imageDataUrls.isEmpty()) return messages.toList()
            if (adapter.capabilities().vision) return messages.toList()
            return messages.map { msg ->
                if (msg === currentUserMessage) {
                    msg.copy(
                        images = emptyList(),
                        content = msg.content +
                            "\n[注意：本轮随附了一张图片，但当前大脑不支持看图片。" +
                            "你收不到图片内容，请如实告诉用户你看不到图片，不要猜测图片里有什么。]",
                    )
                } else {
                    msg
                }
            }
        }
        // P1 §6.1：workspaceRoot 可能为 null（未配置）。ToolContext.workspaceRoot
        // 只是占位（真实解析走 ToolRegistry 自身的 root，root 为空时
        // checkPermission 即返回 path_not_allowed），此处用空 File 占位，
        // 不得为占位而回退到 File("/")。
        val toolContext = ToolContext(
            taskId = taskId ?: conversation.id,
            agentId = "brain",
            approvalGate = approvalGate,
            workspaceRoot = tools.workspaceRoot ?: File(""),
            deviceId = "local",
            folder = tools.workspaceRoot?.path ?: "",
            gitHostProject = gitHostContext?.first,
            gitHostLocation = gitHostContext?.second,
        )

        // Explicit non-null type: this var is reassigned in the failover
        // loop below, so without the annotation the inferred type would be
        // String? (the smart cast from the isNullOrBlank check above does
        // not carry into the inferred type of a reassigned var).
        var brainId: String = effectiveBrainId
        var switched = false
        var sameBrainAttempts = 0
        val triedBrains = mutableSetOf<String>()
        var toolCallsExecuted = 0
        val assistantText = StringBuilder()
        var finished = false
        // P1 多步骤（2026-11-08）：台账（权威）+ 旧 seen 集合（写侧快速判定）
        // + 终结性失败集合 + 三维进度判定。台账只活在本轮 chat() 内。
        val ledger = TurnToolLedger()
        val turnStartedAt = nowMillis()
        val seenToolCalls = mutableSetOf<String>()
        val seenTerminalFailures = mutableSetOf<String>()
        // tool_no_progress 修复（2026-11-09）：同一归一化目标的 hand_not_found
        // 重复出现即按终结性失败处理（护栏 3 同款终结，见 appendFeedback）；
        // recentFailures 供护栏 2 终结时附带失败摘要；knownListings 记本轮内
        // 已成功列出的目录内容，供失败 read_file 的回填附带可用文件清单。
        // 三者只活在本轮 chat() 内，不跨消息。
        val seenNotFoundFailures = mutableSetOf<String>()
        val recentFailures = mutableListOf<String>()
        val knownListings = mutableMapOf<String, String>()
        var noProgressRounds = 0
        var lastProgressSignature: String? = null
        var lastPendingToolCalls: List<BrainEvent.ToolCall> = emptyList()
        var doneReason: String? = null
        var roundIndex = 0
        // 缺参修复 §7 第 2 条：本轮内已知工作区根目录为空（list_files 回填
        // workspaceState=="empty" 且 path 为根）。一旦确认即不再把 read_file
        // 发给模型当选项（见 decideToolsForRound）；读到文件 / 非空清单后
        // 清零（noteWorkspaceState）。只活在本轮 chat() 内，不跨消息。
        var workspaceKnownEmpty = false

        /**
         * 缺参修复 §7 第 2 条：根据一次工具执行结果更新“空目录已知”态。
         * 只有根目录的 empty 才置位（子目录为空不代表根为空）；任何
         * not_empty（根/子目录非空清单，或 read_file 读到文件）都清零。
         */
        fun noteWorkspaceState(outcome: ToolOutcome) {
            if (outcome.workspaceState == "empty" && outcome.path.isNullOrEmpty()) {
                workspaceKnownEmpty = true
            } else if (outcome.workspaceState == "not_empty") {
                workspaceKnownEmpty = false
            }
        }

        /**
         * tool_no_progress 修复（2026-11-09）：归一化工作区相对路径，
         * 与 ToolRegistry.resolveInWorkspace 的切分规则一致（去空白、反斜杠
         * 转正斜杠、丢空段与 "." 段），使 "README.txt"、"./README.txt"、
         * "/data/.../README.txt" 等写法在“同一目标”判定时归为同一目标。
         * 只作判定的 key，不作路径解析（".." 仍保留在 key 里，解析层自会拒绝）。
         */
        fun normalizeWorkspacePath(raw: String): String =
            raw.trim().replace('\\', '/').split("/")
                .filter { it.isNotEmpty() && it != "." }.joinToString("/")

        /** hand_not_found 的“同一目标” key：工具名 + 归一化 path；无 path 时退回完整调用签名。 */
        fun notFoundTargetKey(call: BrainEvent.ToolCall): String {
            val path = call.args["path"] as? String
            return if (path != null) "${call.name}|hand_not_found|${normalizeWorkspacePath(path)}"
            else toolCallKey(call)
        }

        /**
         * tool_no_progress 修复（2026-11-09）：为一次失败的 read_file
         * 找本轮内已知的可用文件清单。优先取目标所在目录的清单；该目录
         * 未列过时退回根目录清单（根清单含 "dir <子目录>" 行，仍能提示
         * 模型用相对路径）。本轮内尚未成功列过任何目录时返回 null，不编造。
         */
        fun listingForFailedRead(call: BrainEvent.ToolCall): String? {
            val rawPath = call.args["path"] as? String
            val parent = if (rawPath.isNullOrBlank()) "" else
                normalizeWorkspacePath(rawPath).split("/").dropLast(1).joinToString("/")
            if (knownListings.containsKey(parent)) return knownListings[parent]
            return knownListings[""]
        }

        /**
         * 2026-11-09 root-cause fix: deterministic argument repair for the
         * exact K70 failure shape. The model calls `read_file` with a
         * missing path in the same round as (or right after) `list_files`;
         * previously that became `invalid_action`, then
         * `tool_repeated_failure` / `tool_no_progress`.
         *
         * This repairs ONLY a missing/blank `path`, ONLY from a listing that
         * this turn already received from `list_files` (never from the disk
         * behind the tool layer, never by inventing a file). If the listing
         * has exactly one file we use it; if it has several, we use a file
         * the user explicitly named. Otherwise the call is left untouched and
         * the existing invalid_action feedback still applies.
         */
        fun repairMissingReadPath(call: BrainEvent.ToolCall): BrainEvent.ToolCall {
            if (call.name != "read_file") return call
            val rawPath = call.args["path"] as? String
            if (!rawPath.isNullOrBlank()) return call
            val files = knownListings.entries.flatMap { (dir, data) ->
                data.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("file ") }
                    .map { it.removePrefix("file ").trim() }
                    .filter { it.isNotEmpty() }
                    .map { name -> if (dir.isBlank()) name else "$dir/$name" }
            }.distinct()
            if (files.isEmpty()) return call
            val mentioned = FILE_NAME_PATTERN.findAll(userText)
                .map { it.value }
                .filter { name ->
                    files.any { it == name || it.substringAfterLast('/') == name }
                }
                .distinct()
                .toList()
            val candidate = when {
                mentioned.size == 1 -> files.first {
                    it == mentioned[0] || it.substringAfterLast('/') == mentioned[0]
                }
                mentioned.isEmpty() && files.size == 1 -> files[0]
                else -> null
            } ?: return call
            val repaired = call.copy(args = call.args + ("path" to candidate))
            log(
                conversation.id, "tool_arg_repaired",
                "read_file 缺少 path，已按本轮 list_files 结果补为 $candidate（调用 ${call.id}）",
            )
            return repaired
        }

        /**
         * P1 §3.2/§5.4：控制态停止处理。返回 true = 本轮必须立即停
         * （剩余调用零执行）；只发 Error 事件，Done 由循环后统一发
         * （doneReason 在此设定）。PAUSED 发 turn_paused（等待用户恢复指令，
         * 不得自动续跑旧 turn），STOPPED 发 turn_stopped，GOAL_CHANGED
         * 发 goal_changed（旧 turn 作废，缓存不沿用）。
         */
        suspend fun stopForControl(state: TurnControlState): Boolean {
            if (state == TurnControlState.RUNNING) return false
            val (code, message, reason) = when (state) {
                TurnControlState.PAUSED -> Triple(
                    "turn_paused",
                    "已暂停：不再发新工具调用，等用户恢复指令。",
                    "paused",
                )
                TurnControlState.STOPPED -> Triple(
                    "turn_stopped",
                    "已停止：剩余工具调用不再执行。",
                    "stopped",
                )
                TurnControlState.GOAL_CHANGED -> Triple(
                    "goal_changed",
                    "目标已变更：旧轮次作废，剩余工具调用不再执行。",
                    "goal_changed",
                )
                TurnControlState.RUNNING -> Triple("", "", "")
            }
            emitError(BrainEvent.Error(code = code, message = message, retryable = false))
            log(conversation.id, "error", message)
            doneReason = reason
            return true
        }

        /**
         * F3（2026-11-08）：护栏 1 的收敛处理。写类同参重复时，若已有文字
         * 先返回文字再停（与护栏 2 同款处理：finished=true，循环后按
         * Done("stop") 正常结束）；无文字时才报 tool_call_repeated。
         * 两处护栏 1（轮前检查与轮内复查）共用。
         */
        suspend fun stopForRepeatedWriteCall(call: BrainEvent.ToolCall) {
            if (assistantText.isNotBlank()) {
                log(
                    conversation.id, "tool_call_repeated",
                    "模型重复调用了同一工具（${call.name}，参数相同），已有文字，返回文字并停止",
                )
                finished = true
            } else {
                val err = BrainEvent.Error(
                    code = "tool_call_repeated",
                    message = "模型重复调用了同一工具（${call.name}，参数相同），已停止，避免空转。",
                    retryable = false,
                )
                emitError(err)
                log(conversation.id, "error", err.message)
            }
        }

        /**
         * 2026-11-09 root-cause fix: append ONE assistant message per round
         * that requested tools, carrying the structured tool calls (ids
         * included) before any tool result. A round with N calls must be
         * one assistant message with N `tool_calls` followed by N tool
         * messages, not N separate assistant/tool pairs (OpenAI-compatible
         * APIs reject the latter and cannot pair the results).
         *
         * The readable JSON echo is kept in [ChatMessage.content] for
         * adapters that do not understand native `tool_calls` (the
         * Cloudflare native path), while native adapters use [toolCalls].
         * Both are persisted so a later turn can rebuild the pairing.
         */
        fun appendAssistantToolCalls(calls: List<BrainEvent.ToolCall>) {
            if (calls.isEmpty()) return
            val structured = calls.map { call ->
                ChatToolCall(
                    id = call.id,
                    name = call.name,
                    argumentsJson = JSONObject(call.args).toString(),
                )
            }
            val echo = if (calls.size == 1) {
                val call = calls.first()
                JSONObject()
                    .put("id", call.id)
                    .put("name", call.name)
                    .put("arguments", JSONObject(call.args))
                    .toString()
            } else {
                org.json.JSONArray().apply {
                    calls.forEach { call ->
                        put(
                            JSONObject()
                                .put("id", call.id)
                                .put("name", call.name)
                                .put("arguments", JSONObject(call.args)),
                        )
                    }
                }.toString()
            }
            messages += ChatMessage(
                role = "assistant",
                content = echo,
                toolCalls = structured,
            )
            conversations.addMessage(
                Message(
                    id = newId(),
                    conversationId = conversation.id,
                    role = "assistant",
                    content = SecretRedactor.redact(echo, 2000),
                    source = "tool-call:" + calls.joinToString(",") { it.name },
                    createdAtEpochSeconds = nowSeconds(),
                    toolCalls = structured,
                ),
            )
        }

        /**
         * P1 §3.2：官方回填的 tool 结果消息（role=tool + tool_call_id），
         * 真实执行与缓存命中共用。assistant 的 tool_calls 已由
         * [appendAssistantToolCalls] 在整轮执行前一次性追加。
         * 返回 true = 回填后护栏 3 触发，本轮必须停。
         */
        suspend fun appendFeedback(
            call: BrainEvent.ToolCall,
            outcome: ToolOutcome,
            fromCache: Boolean,
            cachedAtRound: Int?,
        ): Boolean {
            val toolJson = JSONObject()
                .put("tool", call.name)
                .put("ok", outcome.ok)
                .put("summary", outcome.summary)
            // tool_no_progress 修复（2026-11-09）第 2 条：记下本轮内成功列出的
            // 目录内容，供后继失败 read_file 的回填附带可用文件清单。
            if (call.name == "list_files" && outcome.ok && outcome.data != null) {
                knownListings[outcome.path ?: ""] = outcome.data
            }
            // 缺参修复 §7 第 1 条：回填带上机器码 errorCode（如 invalid_action），
            // 模型与日志不再只靠中文 summary 识别失败原因。
            if (outcome.errorCode != null) toolJson.put("errorCode", outcome.errorCode)
            if (outcome.data != null) toolJson.put("data", outcome.data)
            else toolJson.put("data", JSONObject.NULL)
            // tool_no_progress 修复（2026-11-09）第 2 条：缺参 / 错形 read_file
            // 的回填附带本轮内已知的可用文件清单（availableFiles），而不是只报
            // 机器码；模型不必再猜路径。清单未知时不附带、不编造。
            if (call.name == "read_file" && !outcome.ok &&
                (outcome.errorCode == "invalid_action" || outcome.errorCode == "hand_not_found")
            ) {
                listingForFailedRead(call)?.let { toolJson.put("availableFiles", it.take(2000)) }
            }
            if (fromCache) {
                toolJson.put("cached", true)
                if (cachedAtRound != null) toolJson.put("cachedAtRound", cachedAtRound)
            }
            if (outcome.workspaceState != null) toolJson.put("state", outcome.workspaceState)
            if (outcome.path != null) toolJson.put("path", outcome.path)
            if (outcome.workspaceLabel != null) toolJson.put("workspace", outcome.workspaceLabel)
            if (outcome.host != null) toolJson.put("host", outcome.host)
            val toolText = toolJson.toString()
            messages += ChatMessage(
                role = "tool",
                content = toolText,
                toolCallId = call.id,
                name = call.name,
            )
            conversations.addMessage(
                Message(
                    id = newId(),
                    conversationId = conversation.id,
                    role = "tool",
                    content = SecretRedactor.redact(toolText, 2000),
                    source = "tool:${call.name}",
                    createdAtEpochSeconds = nowSeconds(),
                    toolCallId = call.id,
                ),
            )
            // tool_no_progress 修复（2026-11-09）第 3 条：记最近失败，供护栏 2
            // 终结时附带失败摘要（只记最近 4 条；失败与缓存命中仍不计进展——
            // 触发算术本身不动）。
            if (!outcome.ok) {
                recentFailures += "${call.name}(${outcome.errorCode ?: "failed"})"
                while (recentFailures.size > 4) recentFailures.removeAt(0)
            }
            // P1 护栏 3（不动）：终结性失败第一次只反馈；同一（工具，分类）再现即停。
            if (!outcome.ok && outcome.errorCode in TERMINAL_FAILURE_CODES) {
                val failureKey = "${call.name}|${outcome.errorCode}"
                if (!seenTerminalFailures.add(failureKey)) {
                    val err = BrainEvent.Error(
                        code = "tool_repeated_failure",
                        message = "工具 ${call.name} 再次以同样原因失败（${outcome.errorCode}），已停止，不再重试。",
                        retryable = false,
                    )
                    emitError(err)
                    log(conversation.id, "error", err.message)
                    return true
                }
            }
            // tool_no_progress 修复（2026-11-09）第 1 条：hand_not_found 不在
            // TERMINAL_FAILURE_CODES 内，模型可无限换绝对路径形重试、只由护栏 2
            // 收敛；同一归一化目标第二次 hand_not_found 即按终结性失败处理，
            // 走护栏 3 同款终结（tool_repeated_failure）。不同目标互不干扰：
            // 先后读两个不同不存在的文件仍各给一次重试机会。
            if (!outcome.ok && outcome.errorCode == "hand_not_found") {
                val notFoundKey = notFoundTargetKey(call)
                if (!seenNotFoundFailures.add(notFoundKey)) {
                    val err = BrainEvent.Error(
                        code = "tool_repeated_failure",
                        message = "工具 ${call.name} 再次以同样原因失败（${outcome.errorCode}），已停止，不再重试。",
                        retryable = false,
                    )
                    emitError(err)
                    log(conversation.id, "error", "${err.message} 目标=$notFoundKey")
                    return true
                }
            }
            return false
        }

        outer@ while (!finished) {
            // P1 §8 #11：工具结果丢失守卫——上一轮有 ToolCall，但本轮请求里
            // 缺对应的 tool 消息时，不得继续执行新调用，直接停（不得让模型
            // 在没有结果的情况下编造“已读取…”的文字）。
            val missingResults = findMissingToolResults(messages, lastPendingToolCalls)
            if (missingResults.isNotEmpty()) {
                val err = BrainEvent.Error(
                    code = "tool_result_missing",
                    message = "工具结果丢失（未找到对应的 tool 消息：${missingResults.joinToString(",")}），已停止。",
                    retryable = false,
                )
                emitError(err)
                log(conversation.id, "error", err.message)
                break@outer
            }
            // P1 §5.4：循环头控制态检查（主 enforcement 之一）。
            if (stopForControl(controlState())) break@outer
            // P1 §5.2：轮次上限（按 roundIndex 计）与总时长检查在循环头。
            if (roundIndex >= maxToolRounds) {
                val err = BrainEvent.Error(
                    code = "tool_round_limit",
                    message = "工具调用已达上限 $maxToolRounds 轮，本轮停止。",
                    retryable = false,
                )
                emitError(err)
                log(conversation.id, "error", err.message)
                break@outer
            }
            if (maxTurnMillis > 0 && nowMillis() - turnStartedAt >= maxTurnMillis) {
                val err = BrainEvent.Error(
                    code = "turn_timeout",
                    message = "本轮已超过总时长上限 ${maxTurnMillis} 毫秒，已停止。",
                    retryable = false,
                )
                emitError(err)
                log(conversation.id, "error", err.message)
                break@outer
            }
            val config = adapters.configFor(brainId)
            val adapter = config?.let { adapters.get(it.adapterId) }
            if (adapter == null) {
                val err = BrainEvent.Error(
                    code = "brain_not_found",
                    message = "找不到大脑配置 $brainId：请检查大脑设置。",
                    retryable = false,
                )
                emitError(err)
                log(conversation.id, "error", err.message)
                break@outer
            }
            // P1 多步骤（2026-11-08）：a13f2529 的结构性拦截（本轮内执行过
            // 工具后后续轮次 tools=emptyList）已由本设计移除；多步链由 §3
            // 的缓存 + 进度判定收敛，不再由 tools 缺席收敛。首轮分流保留。
            val budget = TurnBudget(
                roundIndex = roundIndex,
                toolCallsExecuted = toolCallsExecuted,
                maxToolRounds = maxToolRounds,
                maxToolCallsPerTurn = maxToolCallsPerTurn,
                elapsedMillis = nowMillis() - turnStartedAt,
                maxTurnMillis = maxTurnMillis,
            )
            val toolDefs = decideToolsForRound(
                roundIndex = roundIndex,
                actionOriented = actionOriented,
                capabilities = adapter.capabilities(),
                budget = budget,
                controlState = controlState(),
                workspaceKnownEmpty = workspaceKnownEmpty,
                toolsForCapabilities = { caps ->
                    toolsForTurn(caps, gitHostContext)
                },
            )
            // 缺参修复 §7 第 2 条：空目录已知时本轮不再提供 read_file。
            if (workspaceKnownEmpty && toolDefs.none { it.name == "read_file" }) {
                log(conversation.id, "empty_workspace_no_read", "工作区已知为空，本轮不再提供 read_file")
            }
            val request = ChatRequest(
                messages = messagesForRound(adapter),
                model = config.model,
                tools = toolDefs,
            )
            var roundError: BrainEvent.Error? = null
            var sawToolCalls = false
            val pendingToolCalls = mutableListOf<BrainEvent.ToolCall>()
            try {
                adapter.chatStream(request).collect { event ->
                    when (event) {
                        is BrainEvent.TextDelta -> {
                            assistantText.append(event.text)
                            emit(BrainEvent.TextDelta(SecretRedactor.redactStreamChunk(event.text)))
                        }
                        is BrainEvent.ReasoningDelta -> emit(event)
                        is BrainEvent.ToolCall -> {
                            sawToolCalls = true
                            pendingToolCalls += event
                            emit(event)
                        }
                        is BrainEvent.Usage -> emit(event)
                        is BrainEvent.Error -> roundError = event
                        is BrainEvent.Done -> { /* end of this adapter turn */ }
                        is BrainEvent.ToolResult -> emit(event)
                    }
                }
            } catch (e: CancellationException) {
                // Cancellation is not a brain failure: propagate it so the
                // caller/UI can show an explicit cancelled state instead of
                // a fabricated error or a blank bubble.
                throw e
            } catch (e: Exception) {
                roundError = BrainEvent.Error(
                    code = "brain_call_failed",
                    message = "大脑调用失败：${e.message ?: "未知错误"}",
                    retryable = true,
                )
            }
            if (roundError != null) {
                val err = roundError!!
                log(conversation.id, "error", "${err.code}: ${err.message}")
                if (Failover.shouldRetry(err, sameBrainAttempts, failoverPolicy)) {
                    sameBrainAttempts += 1
                    log(conversation.id, "failover_retry", "同大脑重试第 $sameBrainAttempts 次")
                    continue@outer
                }
                triedBrains += brainId
                // Non-retryable errors report immediately (same rule as
                // FailoverRunner): no fallback switch on e.g. 401.
                // 阶段 A：allowFailover=false 的轮次（选中 Agent）也不回落：
                // 可重试失败直接报出，绝不悄悄换一个大脑/换一个计费方。
                val next = if (allowFailover && err.retryable) {
                    Failover.nextFallback(brainId, failoverPolicy, triedBrains)
                } else {
                    null
                }
                if (next != null) {
                    brainId = next
                    switched = true
                    sameBrainAttempts = 0
                    conversations.updateConversationBrain(conversation.id, brainId)
                    log(conversation.id, "brain_switched", "失败后切换大脑为 $brainId")
                    continue@outer
                }
                emitError(err)
                emit(BrainEvent.Done("error"))
                return@flow
            }
            sameBrainAttempts = 0
            if (!sawToolCalls) {
                // P1 §8 #16：tools 在场但模型未调用工具——正常文字结束，
                // 不报错、不伪造工具调用；记一条事件供 §7.4 分级用。
                if (toolDefs.isNotEmpty()) {
                    log(conversation.id, "tools_unused", "tools 在场但模型本轮未调用工具")
                }
                finished = true
                break@outer
            }
            // P1 护栏 1（调整，不删除）：读写分流。只读调用同参命中缓存
            // 时回填（见执行循环），不再一刀切停；写类调用同参重复仍立即
            // 停、不执行、不落盘——仅限上一次成功（见执行循环的记入点）：
            // 失败的同一调用允许重试（开发循环“失败→修复→再跑”必须重跑
            // 同一条测试命令；失败重试由护栏 2/3 与预算收敛）。
            // 白名单制：不在可缓存集合的一律按写类处理。
            // F3（2026-11-08）：已有文字时先返回文字再停（与护栏 2 同款处理），
            // 无文字时才报 tool_call_repeated——写类重复不应直接报错吞掉已有文字。
            val repeatedWriteCall = pendingToolCalls.firstOrNull {
                toolCallKey(it) in seenToolCalls && !isReadOnlyToolCall(it)
            }
            if (repeatedWriteCall != null) {
                stopForRepeatedWriteCall(repeatedWriteCall)
                break@outer
            }
            // P1 护栏 2（调整，不删除）：三维进展判定（文字长度 + 新签名数
            // + 最新结果 hash），阈值仍 2 轮；执行前触发保留——无进展的调用
            // 不再执行。noProgressRounds 在上一轮回填后算出（见循环尾）。
            if (noProgressRounds >= 2) {
                // tool_no_progress 修复（2026-11-09）第 3 条：终结时附带最近失败
                // 摘要（工具名+errorCode），便于区分“模型无进展”与“持续失败”；
                // 触发算术本身不动（失败与缓存命中仍不计进展）。无失败记录时
                // 文案与旧版逐字相同。
                val failureSummary = recentFailures.takeLast(3).joinToString("、")
                val failureSuffix =
                    if (failureSummary.isNotEmpty()) "最近失败：$failureSummary。" else ""
                if (assistantText.isNotBlank()) {
                    log(
                        conversation.id, "tool_no_progress",
                        "连续两轮无进展，返回已有文字并停止工具循环$failureSuffix",
                    )
                    finished = true
                    break@outer
                }
                val err = BrainEvent.Error(
                    code = "tool_no_progress",
                    message = "大脑连续调用工具却没有给出文字回复，本轮停止。$failureSuffix",
                    retryable = false,
                )
                emitError(err)
                log(conversation.id, "error", err.message)
                break@outer
            }
            // 2026-11-09 root-cause fix: repair missing read_file paths from a
            // listing this turn already received, then append ONE assistant
            // message carrying every structured tool call (with ids) for the
            // round. Tool results are appended per call below with matching
            // tool_call_id values.
            val effectiveToolCalls = pendingToolCalls.map { repairMissingReadPath(it) }
            appendAssistantToolCalls(effectiveToolCalls)
            for ((callIndex, originalCall) in effectiveToolCalls.withIndex()) {
                // Same-round list_files → read_file: the pre-pass above ran
                // before the listing existed. Try the repair again now that
                // earlier calls in this round may have populated
                // knownListings. The assistant echo keeps the original call
                // arguments; the tool result is paired by id and carries the
                // resolved path, so the model can see what was read.
                val call = repairMissingReadPath(originalCall)
                // P1 §5.4：每次执行前控制态检查（主 enforcement 之二）。
                if (stopForControl(controlState())) break@outer
                // P1 §5.2：调用次数预算检查（执行前）。
                if (toolCallsExecuted >= maxToolCallsPerTurn) {
                    val err = BrainEvent.Error(
                        code = "tool_call_budget_exceeded",
                        message = "工具调用次数已达上限 $maxToolCallsPerTurn 次，本轮停止。",
                        retryable = false,
                    )
                    emitError(err)
                    log(conversation.id, "error", err.message)
                    break@outer
                }
                val key = toolCallKey(call)
                val cached = ledger.lookup(key, brainId)
                if (cached != null && isReadOnlyToolCall(call)) {
                    // P1 §3.2：只读缓存命中——不真实执行、不耗预算、不重新审批；
                    // 命中前先 validateOnly() 重检权限（授权可能已被撤销）。
                    val recheck = tools.validateOnly(
                        Action(
                            actionId = call.id,
                            tool = call.name,
                            args = call.args,
                            requiresApproval = false,
                            idempotencyKey = cached.idempotencyKey,
                        ),
                        toolContext,
                    )
                    if (recheck != null) {
                        emit(BrainEvent.ToolResult(call.id, recheck.ok, recheck.summary))
                        log(conversation.id, "cache_hit_denied", "${call.name} 缓存命中但重检拒绝：${recheck.errorCode}")
                        noteWorkspaceState(recheck)
                        if (appendFeedback(call, recheck, fromCache = false, cachedAtRound = null)) break@outer
                    } else {
                        val summary = cached.outcome.summary + "（缓存结果，未重新执行）"
                        emit(BrainEvent.ToolResult(call.id, cached.outcome.ok, summary))
                        log(conversation.id, "tool_result_cached", "${call.name} key=$key 原轮次=${cached.roundIndex}")
                        // 回填仍是 assistant 回显 + role=tool 结果 JSON；
                        // toolCallId 用本次调用的 id（不是原记录的 id）。
                        noteWorkspaceState(cached.outcome)
                        if (appendFeedback(call, cached.outcome, fromCache = true, cachedAtRound = cached.roundIndex)) break@outer
                    }
                    continue
                }
                // 同一轮内两个相同写调用：轮前护栏 1 只查一次，此处再拦一道，
                // 防 pendingToolCalls 内的重复写调用在本轮内重复落盘。
                // F3 同款处理：已有文字时返回文字再停，无文字时才报错。
                if (key in seenToolCalls && !isReadOnlyToolCall(call)) {
                    stopForRepeatedWriteCall(call)
                    break@outer
                }
                val idempotencyKey = PlanValidator.idempotencyKey(conversation.id, toolCallsExecuted)
                val action = Action(
                    actionId = call.id,
                    tool = call.name,
                    args = call.args,
                    requiresApproval = false,
                    idempotencyKey = idempotencyKey,
                )
                // P1 §5.4：注册表层第二道停止门（纵深，不依赖引擎）。
                val outcome = try {
                    tools.execute(
                        action,
                        toolContext,
                        stopped = {
                            val s = controlState()
                            s == TurnControlState.STOPPED || s == TurnControlState.GOAL_CHANGED
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A tool throwing must still produce a clear result for
                    // the UI and the model; it must not leave the turn blank.
                    ToolOutcome(
                        ok = false,
                        summary = "工具执行异常：${e.message ?: "未知错误"}",
                        errorCode = "tool_failed",
                    )
                }
                toolCallsExecuted += 1
                // 2026-11-09 阶段 2 B4：只有成功执行过的调用才记入护栏 1
                // 的重复判定——失败的同一调用允许重试（见轮前护栏 1 注释）。
                if (outcome.ok) seenToolCalls += key
                emit(BrainEvent.ToolResult(call.id, outcome.ok, outcome.summary))
                log(conversation.id, "tool_result", "${call.name} ok=${outcome.ok} ${outcome.summary}")
                // 缺参修复 §7 第 2 条：执行结果里的工作区状态更新“空目录已知”态。
                noteWorkspaceState(outcome)
                // 失败结果不缓存（失败可能是瞬时的；缓存失败会把一次性失败冻成永久失败）。
                if (outcome.ok) {
                    ledger.record(
                        ToolCallRecord(
                            key = key,
                            name = call.name,
                            args = call.args,
                            roundIndex = roundIndex,
                            outcome = outcome,
                            fromCache = false,
                            resultHash = resultHash(call.name, outcome),
                            executedAtEpochSeconds = nowSeconds(),
                            brainId = brainId,
                            idempotencyKey = idempotencyKey,
                        ),
                    )
                }
                // 官方回填范式（Cloudflare Function calling 总页，2026-11-08
                // 核查确认）：先回显 assistant 的工具调用（JSON），再发
                // role="tool" 的结果（JSON 字符串）。顺序不能错；两条都进
                // 本轮 messages 与历史存储（source 分别标 "tool-call:<名>"
                // 与 "tool:<名>"）。UI 是流式气泡渲染、不重放历史存储，
                // 回显 JSON 不会出现在聊天界面。工具结果不再追加
                // TOOL_RESULT_GUIDANCE 文本指引（该变通做法已随官方范式废止）。
                if (appendFeedback(call, outcome, fromCache = false, cachedAtRound = null)) break@outer
                // F1 ask_user 终结语义（2026-11-08）：提问成功即结束本轮——
                // 问题以文字交给用户，doneReason="awaiting_user"，不再调模型。
                // ask_user 本无等待语义（同步返回 ok、循环立刻再问模型），
                // K70 ③ 轮正是因此重复同参调用并撞护栏 1。提问不是幂等读操作，
                // 不进只读缓存白名单；失败（ok=false）时不终结，照常回填给模型重试。
                if (call.name == "ask_user" && outcome.ok) {
                    val question = outcome.data ?: outcome.summary
                    assistantText.append(question)
                    emit(BrainEvent.TextDelta(question))
                    log(conversation.id, "ask_user_awaiting", SecretRedactor.redact(question, 500))
                    doneReason = "awaiting_user"
                    finished = true
                    break@outer
                }
                // 缺参修复 §7 第 3 条：ask_user 缺 question 时直接终结本轮——
                // 提问本身坏了（结构校验 invalid_action），回填重试无意义，
                // 不再耗一轮让模型重问。以文字说明失败并结束，
                // doneReason="ask_user_invalid"。仅限缺参（invalid_action）；
                // 其他失败仍按原有回填重试路径走。
                if (call.name == "ask_user" && !outcome.ok && outcome.errorCode == "invalid_action") {
                    val failureText = "提问失败：缺少问题内容（${outcome.summary}），请直接用文字回答。"
                    assistantText.append(failureText)
                    emit(BrainEvent.TextDelta(failureText))
                    log(conversation.id, "ask_user_invalid", SecretRedactor.redact(failureText, 500))
                    doneReason = "ask_user_invalid"
                    finished = true
                    break@outer
                }
                // P1 §5.2：执行后复核一次（防一轮多调用 overshoot）。
                if (toolCallsExecuted >= maxToolCallsPerTurn && callIndex < effectiveToolCalls.lastIndex) {
                    val err = BrainEvent.Error(
                        code = "tool_call_budget_exceeded",
                        message = "工具调用次数已达上限 $maxToolCallsPerTurn 次，本轮停止。",
                        retryable = false,
                    )
                    emitError(err)
                    log(conversation.id, "error", err.message)
                    break@outer
                }
            }
            lastPendingToolCalls = effectiveToolCalls
            // P1 护栏 2 的三维进展签名（回填后、roundIndex+=1 之前计算一次）：
            // 文字长度 + 新签名数 + 最新结果 hash；与上一轮相同即 +1，否则清零。
            val progressSignature =
                "${assistantText.length}|${ledger.distinctKeyCount}|${ledger.lastResultHash}"
            if (lastProgressSignature != null && progressSignature == lastProgressSignature) {
                noProgressRounds += 1
            } else {
                noProgressRounds = 0
            }
            lastProgressSignature = progressSignature
            roundIndex += 1
        }

        val finalText = assistantText.toString()
        // 2026-11-09 blank-bubble fix: a turn that produced no user-visible
        // text is never a silent success. The UI must show a concrete error
        // instead of leaving the streamed bubble empty.
        if (finalText.isBlank()) {
            if (terminalErrorEmitted) {
                // A specific terminal error (control pause/stop, guard, API
                // failure) was already emitted; do not replace it with the
                // generic empty-response error.
                emit(BrainEvent.Done(doneReason ?: "error"))
                return@flow
            }
            val toolNote = if (toolCallsExecuted == 0) {
                "也没有执行任何工具调用"
            } else {
                "工具已执行 $toolCallsExecuted 次，但没有文字结论"
            }
            val err = BrainEvent.Error(
                code = "empty_response",
                message = "大脑没有返回可显示的文字回复（空响应）$toolNote。请重试；若持续出现，请检查 DeepSeek 配置与网络。",
                retryable = true,
            )
            emitError(err)
            log(conversation.id, "error", err.message)
            emit(BrainEvent.Done("error"))
            return@flow
        }
        conversations.addMessage(
            Message(
                id = newId(),
                conversationId = conversation.id,
                role = "assistant",
                content = SecretRedactor.redact(finalText, 12000),
                source = brainId,
                createdAtEpochSeconds = nowSeconds(),
            ),
        )
        log(
            conversation.id, "turn_done",
            "brain=$brainId tools=$toolCallsExecuted switched=$switched",
        )
        emit(
            BrainEvent.Done(
                doneReason ?: when {
                    terminalErrorEmitted -> "error"
                    switched -> "switched_brain"
                    else -> "stop"
                },
            ),
        )
        // Outcome is observable through the conversation + event log; the
        // Flow itself is the streaming channel, per the BrainEvent contract.
        @Suppress("UNUSED_VARIABLE")
        val outcome = ChatOutcome(conversation.id, finalText, toolCallsExecuted, brainId, switched)
    }

    /**
     * 网页搜索（V5 按主流改，maintainer 2026-11-11）：只在开关开着时调。
     * 返回插进本轮上下文的 system 消息正文——搜到、没搜到、搜失败
     * 三种情况各有一句诚实说明，绝不让模型以为自己查过网。
     * 搜索本身可能抛异常（网络/解析），这里全包住：失败也是一条说明，
     * 不让搜索失败把整轮聊天拖垮。
     */
    private suspend fun runWebSearch(conversationId: String, query: String): String {
        val result = webSearch.search(query)
        if (result.isFailure) {
            val reason = result.exceptionOrNull()?.message ?: "未知错误"
            log(conversationId, "web_search_failed", SecretRedactor.redact(reason, 200))
            return "联网搜索失败（$reason）：本次回答没有使用搜索结果，请按已有知识回答，并说明你的知识可能不是最新的。"
        }
        val found = result.getOrThrow()
        if (found.hits.isEmpty()) {
            return "联网搜索（${found.source}）没有找到与“${found.query}”相关的结果：本次回答没有使用搜索结果，请按已有知识回答，并说明你的知识可能不是最新的。"
        }
        return WebSearchClient.formatForModel(found)
    }

    /**
     * P1 §2.2：决定本轮发给模型的工具定义。取代 a13f2529 的结构性拦截：
     * “已执行过工具”本身不再是停工具的理由；停工具只发生在首轮无需行动、
     * 模型无工具能力、预算耗尽、控制态停止四种情形。按判断顺序，先命中先返回。
     */
    /**
     * git 家（2026-11-11）：本轮给模型的工具 = 本地工具（权限过滤后）
     * + 当前 git 家的 git 家工具（无 git 家时不加，模型看不到）。
     * 诚实边界（通用大脑放开切换，2026-11-11）：适配器不支持工具调用
     * 时本地与 git 家工具一律不列——列出来模型也调不了，只会 primed
     * 它去行动（首轮上下文的“可用工具”提示与请求里的工具定义必须一致，
     * 见 chat() 的 firstRoundTools）。
     */
    private fun toolsForTurn(
        capabilities: BrainCapabilities?,
        gitHostContext: Pair<Project, ProjectLocation>?,
    ): List<ToolDef> {
        if (capabilities != null && !capabilities.toolCalling) return emptyList()
        val local = tools.toolsForCapabilities(capabilities, authorizedTools = tools.allToolNames())
        val gitHost = tools.gitHostToolsFor(gitHostContext?.first, gitHostContext?.second)
        return local + gitHost
    }

    private fun decideToolsForRound(
        roundIndex: Int,
        actionOriented: Boolean,
        capabilities: BrainCapabilities?,
        budget: TurnBudget,
        controlState: TurnControlState,
        /**
         * 缺参修复 §7 第 2 条：工作区根目录已知为空时，不再把 read_file
         * 发给模型当选项——没有文件可读，read_file 必然缺参或编造路径。
         * 其余工具（list_files、ask_user 等）不受影响。
         */
        workspaceKnownEmpty: Boolean = false,
        toolsForCapabilities: (BrainCapabilities?) -> List<ToolDef>,
    ): List<ToolDef> {
        if (controlState != TurnControlState.RUNNING) return emptyList()
        if (budget.isExhausted()) return emptyList()
        if (roundIndex == 0 && !actionOriented) return emptyList()
        if (capabilities == null || !capabilities.toolCalling) return emptyList()
        val defs = toolsForCapabilities(capabilities)
        if (!workspaceKnownEmpty) return defs
        return defs.filter { it.name != "read_file" }
    }

    /**
     * P1 §3.1：可缓存的只读调用（白名单制）。list_files / read_file 与
     * argv[0] 在 READ_ONLY_COMMANDS 内的 run_command；不在白名单的一律按
     * 写类处理（write_file、dispatch_task、非只读 run_command、ask_user）。
     */
    private fun isReadOnlyToolCall(call: BrainEvent.ToolCall): Boolean = when (call.name) {
        "list_files", "read_file" -> true
        // 2026-11-11 MCP + 插件：扩展工具的只读判定问来源（插件的
        // local_time/calc、Hand MCP 的 list/read 等）；来源不认的按写类处理。
        else -> if (call.name.startsWith("plugin_") || call.name.startsWith("mcp_")) {
            tools.isReadOnlyExtensionTool(call.name)
        } else if (call.name == "run_command") {
            val argv = call.args["argv"]
            val parsed = when (argv) {
                is List<*> -> argv.mapNotNull { it as? String }.takeIf { it.isNotEmpty() }
                is String -> try {
                    val array = org.json.JSONArray(argv)
                    (0 until array.length()).map { array.optString(it, "") }
                        .filter { it.isNotEmpty() }
                        .takeIf { it.isNotEmpty() }
                } catch (_: Exception) {
                    null
                }
                else -> null
            }
            ToolRegistry.isReadOnlyRun(parsed)
        } else {
            false
        }
    }

    /**
     * P1 §1.3：结果 hash（进度判定与“同一调用但结果变了”的识别）。
     * 只取 summary + data 前 2000 字符；写类调用取 summary + errorCode，
     * 避免把文件内容当 hash 输入。
     */
    private fun resultHash(toolName: String, outcome: ToolOutcome): String {
        val raw = if (toolName == "write_file" || toolName == "dispatch_task") {
            "${outcome.summary}|${outcome.errorCode ?: ""}"
        } else {
            "${outcome.summary}|${outcome.data?.take(2000) ?: ""}"
        }
        return raw.hashCode().toString(16)
    }

    /**
     * P1 护栏 1 的调用签名：工具名 + 排序后的参数。同一签名重复即停。
     *
     * 归一化（tool_call_repeated 修复，2026-11-08）：字符串值先 trim；
     * path 类参数（"path"、"cwd"）的值为空白或 "." 时视为默认工作区根
     * 目录，该条目直接从签名中去掉——缺省 path、""、"." 三种写法是同一
     * 个调用，不得绕过护栏 1。其余参数保持原值（只 trim，不做空白归一）。
     */
    private fun toolCallKey(call: BrainEvent.ToolCall): String {
        val args = call.args.entries.sortedBy { it.key }
            .filterNot { (key, value) ->
                // 只对字符串型的 path 类参数做空白归一；非字符串值保持原样。
                (key == "path" || key == "cwd") &&
                    (value as? String)?.trim().let { it != null && (it.isBlank() || it == ".") }
            }
            .joinToString(",") { (key, value) ->
                val text = if (value is String) value.trim() else "$value"
                "$key=$text"
            }
        return "${call.name}|$args"
    }

    private fun log(conversationId: String, kind: String, detail: String) {        eventLog.record(
            EngineEvent(
                seq = 0,
                conversationId = conversationId,
                kind = kind,
                detail = SecretRedactor.redact(detail, 2000),
                epochSeconds = nowSeconds(),
            ),
        )
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun newId(): String = java.util.UUID.randomUUID().toString()

    companion object {
        /**
         * P1 §8 #11：工具结果丢失检测——上一轮的 ToolCall 里，哪些在本轮
         * 请求消息里找不到对应的 role=tool 消息（按 toolCallId 配对）。
         * 返回缺失的调用 id 列表；空 = 回填完整。供引擎循环头守卫与测试直测。
         */
        internal fun findMissingToolResults(
            messages: List<ChatMessage>,
            calls: List<BrainEvent.ToolCall>,
        ): List<String> {
            if (calls.isEmpty()) return emptyList()
            val present = messages
                .filter { it.role == "tool" && it.toolCallId != null }
                .map { it.toolCallId }
                .toSet()
            return calls.filter { it.id !in present }.map { it.id }
        }

        const val SYSTEM_PROMPT: String =
            "你是 WhereverCode 超级 Agent 的大脑。能直接回答的问题，直接用文字回答，" +
                "不要调用工具；只有需要读文件、跑命令、派任务时才调用工具。" +
                "你不直接改文件；所有行动由程序验证后执行。" +
                "不知道就说不知道，不要编造。" +
                "读文件前须先列出目录：read_file 的 path 只能取自已返回的 list_files 结果，" +
                "不得与 list_files 同一轮发出；工作区已确认为空时直接用文字回答，不要再调 read_file。" +
                "做开发任务时按此循环：先 list_files 看现状；用 write_file 每次写一个文件；" +
                "用 run_command 的 [\"python3\", \"--version\"] 检查真实 Python 环境；" +
                "可用就运行真实测试并读取退出码和输出，失败按输出修复，最多重试三轮；" +
                "不可用就如实报告能力缺口。绝不声称执行了没有实际执行的命令，" +
                "也不编造测试结果或退出码。"

        /**
         * 终结性工具失败分类：失败原因不在参数/权限本身时重试也没有意义，
         * 第一次只把原因反馈给模型，同一（工具，失败分类）再出现即停。
         * 其他失败（文件不存在、执行失败等）仍可换个参数重试一次，
         * 但同一调用（工具名 + 参数）重复时由 tool_call_repeated 兜底。
         */
        val TERMINAL_FAILURE_CODES: Set<String> = setOf(
            "invalid_action", "invalid_argument", "path_not_allowed", "user_denied",
            // 2026-11-09 审批安全：授权在参数变化/界面销毁后失效，重试同一
            // 调用没有意义（要么重新走完整审批，要么等用户回到界面）。
            "approval_action_changed", "approval_revoked",
        )

        /**
         * P1 首轮分流：判断本轮用户消息是否需要行动。问候/闲聊（“在吗”、
         * “你好”）返回 false——首轮不带工具定义，让模型直接用文字回答；
         * 提到读/写/跑/查/派等行动或文件/命令时返回 true，首轮正常带工具。
         * 这是启发式分流，不是语义理解：漏判时模型仍可用文字回复，
         * 用户换个说法即可。
         * F2（2026-11-08）：“任务”一词已移出——作宾语时多为提问话题
         * （“能帮我完成什么任务？”），不是行动指令；K70 ③ 轮只因末尾
         * “任务”二字就被误判为行动导向。派任务改由明确动词触发：
         * “派”“交给”“新建”（+英文 dispatch/assign）。旧注释“误判时
         * 模型仍可直接回文字（工具在场不强制调用）”已被 K70 证伪：
         * 默认 8B 模型工具在场就调工具，故分流必须保守，宁可漏判（模型
         * 仍可文字回复）不可误判（工具在场即可能失控）。
         */
        private val ACTION_WORDS: List<String> = listOf(
            "读", "写", "改", "删", "创建", "新建", "列出", "运行", "执行",
            "派", "交给", "打开", "下载", "上传", "部署", "测试", "编译", "构建",
            "检查", "复制", "移动", "安装", "提交", "搜索", "查找", "查看",
            "文件", "文件夹", "目录", "代码", "命令", "日志",
        )
        private val ACTION_WORDS_EN: List<Regex> = listOf(
            "read", "write", "edit", "delete", "create", "list", "run",
            "execute", "dispatch", "assign", "check", "find", "search", "open",
            "build", "test", "deploy", "show", "file", "command",
            "code", "cat", "ls",
        ).map { Regex("\\b$it\\b", RegexOption.IGNORE_CASE) }

        /**
         * 2026-11-09 root-cause fix: file names the user explicitly wrote
         * ("读取 README.txt"). Used only to choose among files that a
         * `list_files` result already returned this turn; never used to
         * invent a path when no listing exists.
         */
        private val FILE_NAME_PATTERN: Regex = Regex(
            """[\p{L}\p{N}_.\-]+\.(?:txt|md|markdown|json|kt|kts|java|py|js|ts|tsx|jsx|css|html|xml|ya?ml|csv|log|sh|gradle|properties|toml|ini|cfg|conf)""",
            RegexOption.IGNORE_CASE,
        )

        fun looksActionOriented(text: String): Boolean {
            if (ACTION_WORDS.any { it in text }) return true
            return ACTION_WORDS_EN.any { it.containsMatchIn(text) }
        }
    }
}
