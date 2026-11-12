package wherevercode.agent.core.team

import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentRecord

/**
 * P3 团队频道（2026-11-11）：只读扇出 + 有界团队聊天的规则与模型。
 *
 * 依据：多 Agent 调研 P3 节（`research_notes/multi-agent-team-execution-20261111-1039`）
 * 与老项目对照 P3 节（`v5-old-project-results/DELIVERY.md`）。
 * 收敛结论是“一个总调度 + 一次性隔离子 Agent、建议-only”，老项目
 * 群聊后端真跑过，但缺两样东西，本文件补的就是这两样：
 * ① 扇出的结构化形态（只读分支 + 摘要回执，不并行写）；
 * ② 建议-only 围栏（群消息绝不直接触发派活）。
 *
 * 范围改（maintainer 2026-11-11 22:19）：频道成员不再是 2–3 位，而是
 * App 注册表里的**全部 Agent**。设备 Agent（Codex、CodeBuddy、
 * agy、Example Agent 等）与云端 Agent（Manus）没有端点客户端、不能
 * 直接进端点频道——调度器当它们的代理（[ChannelAgentRelay]）：
 * 频道里 @ 它们的消息由调度器中转到各自的 Hand/API，它们的回复
 * 再回流进频道。平台 Agent（Example Agent 等）是端点直连成员，消息直达，
 * 不走代理。
 *
 * “有界”现在的意思：短消息（500 字）、轮次上限（3 轮）、建议-only、
 * 只读扇出 ≤5——不再是成员数上限。
 *
 * 围栏是结构性的，不是文案：扇出走 [TeamFanoutRunner]，它只调
 * [wherevercode.agent.core.mcp.RemoteAgentChatPort.runAgent] 读一次
 * 摘要，不建 P0 任务、不碰 TaskStore、不写文件；中转走
 * [ChannelAgentRelay]，同样只做一次聊天调用、不建任务、不写文件；
 * 频道页（[wherevercode.agent.ui.TeamChannelController]）上没有任何
 * 改任务状态、认领、标记完成的入口——只有发消息、1:1 聊天、只读扇出
 * 三个动作。想派活必须回一对一聊天走正常派活流程。
 *
 * 不做：对等 swarm、自由群聊、LLM 经理、A2A、默认多 Agent。
 */
object TeamChatLimits {
    /** 一轮讨论的轮次上限（用户每发一条消息计一轮；到顶后须点“开始新一轮”）。 */
    const val MAX_ROUNDS: Int = 3

    /** 一条频道消息最长 500 字（短结构化消息；服务端上限 4000 字是另一回事）。 */
    const val MAX_MESSAGE_CHARS: Int = 500

    /** 一次只读扇出的分支上限（调研：扇出 ≤5）。 */
    const val MAX_FANOUT_BRANCHES: Int = 5

    /** 一个分支回执摘要最多收 2000 字（再长只截断、不丢分支）。 */
    const val FANOUT_SUMMARY_CHARS: Int = 2000

    /** 一个分支的等待上限（秒；与 RemoteAgentChatPort.runAgent 默认一致）。 */
    const val FANOUT_TIMEOUT_SECONDS: Long = 300L

    /** 一次 @ 中转的等待上限（秒；与 runAgent / Manus runChat 的等待口径一致）。 */
    const val RELAY_TIMEOUT_SECONDS: Long = 300L

    /** 一条中转回流的回复最多贴 2000 字（再长只截断、不丢回复）。 */
    const val RELAY_REPLY_CHARS: Int = 2000
}

/** 频道页的一位成员（本机 App 打头 + 注册表里的全部 Agent）。 */
data class TeamChannelMember(
    val agentId: String,
    val displayName: String,
    val isLocalApp: Boolean = false,
    /** 平台 Agent（端点直连成员，消息直达，不走代理中转）。 */
    val isPlatformAgent: Boolean = false,
)

/** 只读扇出的候选 Agent（由调用方从 Agent 记录解析出 RemoteTarget 再传进来）。 */
data class FanoutCandidate(
    val agentId: String,
    val deviceId: String?,
    val displayName: String,
)

/** [TeamChatRules.selectFanoutTargets] 的结果：选中的 + 因上限落选的个数。 */
data class FanoutSelection(
    val selected: List<FanoutCandidate>,
    val dropped: Int,
)

object TeamChatRules {

    /**
     * 校验一条待发的频道消息。返回 null = 可发；否则返回要显示给用户
     * 的中文原因。轮次上限按调用方传入的本会话已发轮次数算。
     */
    fun validatePost(text: String, roundsUsed: Int): String? {
        if (text.isBlank()) return "消息不能为空。"
        if (text.length > TeamChatLimits.MAX_MESSAGE_CHARS) {
            return "消息太长（${text.length}/${TeamChatLimits.MAX_MESSAGE_CHARS} 字），" +
                "请拆短再发。"
        }
        if (roundsUsed >= TeamChatLimits.MAX_ROUNDS) {
            return "本轮讨论已到 ${TeamChatLimits.MAX_ROUNDS} 轮上限，" +
                "请点“开始新一轮”再继续。"
        }
        return null
    }

    /**
     * 从候选里挑最多 [maxBranches] 个扇出分支。
     * 口径：先按设备去重（每台设备先取一个候选），再按原顺序补满——
     * 同一台设备上的两个 Agent 看的是同一份代码，重复扇出没有意义；
     * 顺序即调用方给的顺序（通常是成员列表顺序），不另造排序分。
     * deviceId 为 null 的候选（本机）自成一组。
     */
    fun selectFanoutTargets(
        candidates: List<FanoutCandidate>,
        maxBranches: Int = TeamChatLimits.MAX_FANOUT_BRANCHES,
    ): FanoutSelection {
        if (maxBranches <= 0) return FanoutSelection(emptyList(), candidates.size)
        val selected = mutableListOf<FanoutCandidate>()
        val seenDevices = mutableSetOf<String?>()
        for (candidate in candidates) {
            if (selected.size >= maxBranches) break
            if (candidate.deviceId !in seenDevices) {
                seenDevices += candidate.deviceId
                selected += candidate
            }
        }
        if (selected.size < maxBranches) {
            for (candidate in candidates) {
                if (selected.size >= maxBranches) break
                if (candidate !in selected) selected += candidate
            }
        }
        return FanoutSelection(selected, candidates.size - selected.size)
    }

    /**
     * 拼发给每个分支的只读简报。只读指令写进简报正文（分支 Agent 看到
     * 的第一行就是约束），结构保证见本文件头：Runner 不建任务、不写文件。
     */
    fun buildFanoutBrief(question: String, taskTitle: String?, taskGoal: String?): String {
        val context = when {
            !taskTitle.isNullOrBlank() && !taskGoal.isNullOrBlank() ->
                "任务“$taskTitle”：$taskGoal"
            !taskTitle.isNullOrBlank() -> "任务“$taskTitle”"
            !taskGoal.isNullOrBlank() -> "任务：$taskGoal"
            else -> "没有关联任务，只问下面这个问题"
        }
        return "[只读咨询] $question\n（$context）\n" +
            "只读：不要改任何文件、不要认领任务、不要提交结果。" +
            "只返回不超过 500 字的摘要，并引用证据（文件名/行号）。"
    }

    /**
     * 拼 @ 中转时发给被 @ Agent 的转达正文：说明这是频道里的一条
     * @ 消息、不是派活。只回短回复，不改文件、不建任务、不认领——
     * 建议-only 的围栏同样写进正文（结构保证见本文件头）。
     */
    fun buildRelayBrief(senderName: String, text: String): String =
        "[团队频道 @ 转达] $senderName 在频道里 @ 了你：\n$text\n" +
            "只回一段短回复，不要改任何文件、不要建任务、不要认领派活；" +
            "你的回复会原样贴回频道。"
}

/**
 * 频道成员表：本机 App 打头 + 注册表里的全部 Agent（maintainer 2026-11-11
 * 22:19：全部 Agent 拉入频道，不只 2–3 个）。
 *
 * 纯函数，不读 store：调用方传 [AgentStore.listAgents()] 的结果。
 * 顺序即注册表顺序（与 Agent 页一致，不另造排序）；按 agentId 去重，
 * 注册表为准；平台 Agent（brainKind == PLATFORM_AGENT）标出来——
 * 它是端点直连成员，消息直达，中转时跳过它。
 */
fun buildChannelMembers(agents: List<AgentRecord>): List<TeamChannelMember> {
    val members = mutableListOf(
        TeamChannelMember(agentId = "local-app", displayName = "本机 App", isLocalApp = true),
    )
    val seen = mutableSetOf("local-app")
    // Agent 开关（2026-11-12）：停用的 Agent 不进频道（成员表里不出现，
    // 也不参与 @ 中转）；记录本身不删，重新启用后下一刷新即回来（热插拔）。
    for (agent in agents) {
        if (!agent.enabled) continue
        if (!seen.add(agent.agentId)) continue
        members += TeamChannelMember(
            agentId = agent.agentId,
            displayName = agent.displayName,
            isLocalApp = false,
            isPlatformAgent = agent.brainKind == BrainKind.PLATFORM_AGENT,
        )
    }
    return members
}

// ---------------------------------------------------------------------------
// 团队对话按任务分串 + 发言权（2026-11-12，回复归属修复的团队部分）。
//
// maintainer 2026-11-12 06:38 定：单兵是一个说话者、一个话题；团队是多个
// 说话者、多个任务混在一个流里，没有按任务分开，回复也不标是哪个任务
// 的——所以才串任务。分法（同日 06:40 纠正“协调者唯一声音”太绝对后定稿）：
// - 讨论（商量方案、头脑风暴）：团队里各 Agent 都可以回，这才是组团
//   讨论的意义；但每条回复必须带发送者 + 任务/话题归属，不能混、不能串。
// - 任务汇报（进度、结果、交付）：协调者统一向用户汇报（一个声音），
//   各 Agent 向协调者汇报，不直接在用户面前各说各的。
// 所以：讨论是多个声音、每条署名；任务汇报是一个声音。
//
// 落法（App 侧，平台端点不改）：
// - 团队消息的归属头与一对一回复归属头同口径（任务编号前 8 位 + 标题），
//   只是多了发送者（多人共用一个流，发言者必须写清）；App 贴回频道的
//   中转回复一律带此头，读回时解析它还原发送者与任务。
// - 分串是纯函数：同一 taskId 的消息进同一串，无归属的进“综合讨论”串。
// - 汇报的发言权是纯规则：Agent 的汇报须经协调者转达（routeReportToCoordinator），
//   不得直接贴给用户；频道现有的贴回路径（中转、扇出）一律是讨论，
//   本身不贴汇报——汇报将来要进频道时必须走这道门。
// ---------------------------------------------------------------------------

/** 团队对话消息的两种性质：讨论 vs 任务汇报。 */
enum class TeamMessageKind {
    /** 商量方案、头脑风暴：多声音，各署名。 */
    DISCUSSION,

    /** 进度、结果、交付：协调者一个声音。 */
    REPORT,
}

/** 团队对话里的一条消息（展示用视图）：发送者 + 正文 + 任务归属 + 性质。 */
data class TeamThreadMessage(
    val messageId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val taskId: String? = null,
    val taskTitle: String? = null,
    val kind: TeamMessageKind = TeamMessageKind.DISCUSSION,
    val createdAtSeconds: Double = 0.0,
)

/** 一个任务的讨论串：同一任务的消息成组，串内按时间排。 */
data class TeamThread(
    val taskId: String?,
    val taskTitle: String?,
    val messages: List<TeamThreadMessage>,
)

/** 归属头解析结果：发送者 + 任务（短 id）+ 标题；无头返回 null。 */
data class TeamAttribution(
    val senderName: String,
    val taskId: String?,
    val taskTitle: String?,
)

/**
 * 团队消息归属头：发送者 + 任务（与一对一回复归属头同口径：任务编号
 * 前 8 位 + 标题；多人共用一个流，发言者必须写清）。无任务时只写发送者。
 */
fun teamAttributionHeader(senderName: String, taskId: String?, taskTitle: String?): String {
    val sender = senderName.trim().ifBlank { "未知" }
    if (taskId.isNullOrBlank()) return "【$sender】"
    val shortId = taskId.take(8)
    val title = taskTitle?.trim()?.take(40)?.takeIf { it.isNotBlank() }
    return if (title != null) "【$sender · 任务 $shortId · $title】" else "【$sender · 任务 $shortId】"
}

/**
 * 从团队消息正文里解析 App 盖的归属头。认两种格式：
 * - 新格式：`【发送者 · 任务 <短id> · <标题>】` 或 `【发送者】`（本修复起中转回复用的头）；
 * - 旧格式：`[发送者] 正文`（2026-11-12 之前的中转前缀，只有发送者、无任务）。
 * 无头返回 null（调用方用消息自带的发送者字段、无任务归属）。
 */
fun parseTeamAttribution(text: String): TeamAttribution? {
    val trimmed = text.trimStart()
    if (trimmed.startsWith("【")) {
        val end = trimmed.indexOf('】')
        if (end > 1) {
            val inner = trimmed.substring(1, end)
            val parts = inner.split(" · ", limit = 3)
            val sender = parts[0].trim().takeIf { it.isNotBlank() } ?: return null
            if (parts.size == 1) return TeamAttribution(sender, null, null)
            val taskPart = parts.getOrNull(1) ?: return null
            if (!taskPart.startsWith("任务 ")) return null
            val taskId = taskPart.removePrefix("任务 ").trim().takeIf { it.isNotBlank() } ?: return null
            val title = parts.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }
            return TeamAttribution(sender, taskId, title)
        }
    }
    if (trimmed.startsWith("[")) {
        val end = trimmed.indexOf(']')
        if (end > 1) {
            val sender = trimmed.substring(1, end).trim().takeIf { it.isNotBlank() } ?: return null
            return TeamAttribution(sender, null, null)
        }
    }
    return null
}

/** 去掉正文开头的归属头（展示时发送者与任务已由串头/发送者行写清，不重复贴）。无头原样返回。 */
fun stripTeamAttributionHeader(text: String): String {
    val trimmed = text.trimStart()
    if (trimmed.startsWith("【")) {
        val end = trimmed.indexOf('】')
        if (end >= 0) return trimmed.substring(end + 1).trimStart()
    }
    if (trimmed.startsWith("[")) {
        val end = trimmed.indexOf(']')
        if (end >= 0) return trimmed.substring(end + 1).trimStart()
    }
    return text
}

/**
 * 按任务成组/成串：同一 taskId 的消息进同一串；无任务归属的进一个
 * “综合讨论”串（taskId 为 null）。串按首条时间排，串内按时间排
 * （时间相同时保持原顺序，不打散同一轮的回复）。
 */
fun groupTeamMessages(messages: List<TeamThreadMessage>): List<TeamThread> {
    val byTask = LinkedHashMap<String?, MutableList<TeamThreadMessage>>()
    for (m in messages) {
        byTask.getOrPut(m.taskId) { mutableListOf() } += m
    }
    val threads = byTask.map { (taskId, msgs) ->
        val sorted = msgs.sortedBy { it.createdAtSeconds }
        val title = sorted.firstOrNull { !it.taskTitle.isNullOrBlank() }?.taskTitle
        TeamThread(taskId = taskId, taskTitle = title, messages = sorted)
    }
    return threads.sortedBy { it.messages.firstOrNull()?.createdAtSeconds ?: 0.0 }
}

/** 串头标题：有任务写“任务 <短id> · <标题>”（无标题只写编号），无任务写“综合讨论”。 */
fun teamThreadTitle(thread: TeamThread): String {
    val taskId = thread.taskId
    if (taskId.isNullOrBlank()) return "综合讨论"
    val title = thread.taskTitle?.trim()?.takeIf { it.isNotBlank() }
    return if (title != null) "任务 ${taskId.take(8)} · $title" else "任务 ${taskId.take(8)}"
}

/**
 * 团队对话发言权（maintainer 2026-11-12 06:40 定稿）：
 * - 讨论：多声音，各署名——任何 Agent 都可以直接回。
 * - 任务汇报：只走协调者一个声音——Agent 的汇报须经协调者转达，
 *   不得直接贴给用户。
 */
object TeamVoice {
    /** 协调者（本机 App）：任务汇报时跟用户说话的唯一声音。 */
    const val COORDINATOR_ID: String = "local-app"

    /** 协调者在频道里的显示名。 */
    const val COORDINATOR_NAME: String = "协调者"

    /** 某性质的消息是否允许 Agent 直接发声。讨论允许，汇报不允许。 */
    fun directAgentVoiceAllowed(kind: TeamMessageKind): Boolean =
        kind == TeamMessageKind.DISCUSSION

    /**
     * 把一条 Agent 的任务汇报转成协调者口径（一个声音）：发送者改
     * 协调者，正文前加“代 <Agent> 汇报”，任务归属保留。非汇报消息
     * 与协调者本人的消息原样返回。频道里汇报将来要露面时必须走这道门，
     * 不得把 Agent 的汇报原文直接贴给用户。
     */
    fun routeReportToCoordinator(message: TeamThreadMessage): TeamThreadMessage {
        if (message.kind != TeamMessageKind.REPORT) return message
        if (message.senderId == COORDINATOR_ID) return message
        return message.copy(
            senderId = COORDINATOR_ID,
            senderName = COORDINATOR_NAME,
            text = "代 ${message.senderName} 汇报：${message.text}",
        )
    }
}

/**
 * 频道消息 → 团队讨论串视图：发送者与任务归属优先从正文的归属头解析
 * （App 贴回的中转回复带的头）；无头时用消息自带的发送者字段、无任务
 * 归属。正文去掉归属头再展示（发送者与任务已由串头/发送者行写清）。
 * 频道消息一律是讨论（汇报不直接进频道，见 [TeamVoice]）。
 */
fun teamMessageFromChannel(message: wherevercode.agent.core.agent.PlatformChannelMessage): TeamThreadMessage {
    val attr = parseTeamAttribution(message.text)
    val senderName = attr?.senderName
        ?: message.agentName.ifBlank { message.agentId.ifBlank { "未知" } }
    return TeamThreadMessage(
        messageId = message.id,
        senderId = message.agentId,
        senderName = senderName,
        text = stripTeamAttributionHeader(message.text),
        taskId = attr?.taskId,
        taskTitle = attr?.taskTitle,
        kind = TeamMessageKind.DISCUSSION,
        createdAtSeconds = message.createdAtSeconds,
    )
}
