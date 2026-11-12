package wherevercode.agent.core.team

import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.agent.ManusChatEvent
import wherevercode.agent.core.agent.ManusChatPort
import wherevercode.agent.core.agent.PlatformChannelMessage
import wherevercode.agent.core.agent.PlatformChannelTransport
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteTarget

/** 一次 @ 中转的结果（供调用方写提示，不直接进频道）。 */
enum class RelayStatus {
    /** 已回且已贴回频道。 */
    RELAYED,

    /** 不用转（平台直连成员，消息直达）。 */
    SKIPPED,

    /** 没转成（原因见 detail，调用方如实显示，不假装回了）。 */
    FAILED,
}

data class RelayOutcome(
    val agentId: String,
    val displayName: String,
    val status: RelayStatus,
    val detail: String? = null,
)

/**
 * 频道代理中转（P3 范围改，maintainer 2026-11-11 22:19：全部 Agent 拉入频道）。
 *
 * 设备 Agent（Codex、CodeBuddy、agy、Example Agent 等）与云端 Agent
 * （Manus）没有端点客户端、不能直接进端点频道，调度器当它们的代理：
 * 频道里 @ 它们的消息（`@agentId` 或 `@显示名`，不分大小写）由本类
 * 中转到各自的 Hand/API，它们的回复再经 [PlatformChannelTransport]
 * 以归属头（[teamAttributionHeader]：发送者 + 任务）贴回频道。
 * 平台 Agent（Example Agent 等）是端点直连成员，消息直达，本类跳过它。
 *
 * 建议-only 是结构性的：中转只做一次聊天调用（Hand 的
 * [RemoteAgentChatPort.runAgent] 或 Manus 的 [ManusChatPort.runChat]），
 * 不建 P0 任务、不碰 TaskStore、不写文件；转达正文
 * （[TeamChatRules.buildRelayBrief]）第一行就写明这是 @ 转达、不是派活。
 *
 * 防回环（两层，见 [relay]）：
 * 1. 每条频道消息只中转一次（[relayedIds]，处理前先记，防止重叠
 *    的 feed 批次重复触发）；
 * 2. 本机贴回频道的中转回复（[relayOutputIds]）永不再触发中转——
 *    否则 Codex 的回流里万一又带 @，调度器会自己跟自己聊下去。
 * 两组 id 由调用方（MainActivity）持有、跨中转实例共享；本类只
 * 在同步块里读写它们。
 *
 * Manus 计费提醒：每次中转首条会在 Manus 侧建一个真实任务（按任务
 * 计费），跟进不建新任务；本类用固定会话 id
 * `team-channel-relay-<agentId>`，同一 Agent 的中转走同一任务线程。
 * 免费积分耗尽或要切付费时，Manus 侧报失败，本类如实记 FAILED，
 * 绝不静默切付费（见 ManusChatBridge 的计费注释）。
 *
 * 中转只在频道页打开、feed 跑着时发生（调用方在 feed 的 onMessages
 * 里调本类）；频道页关掉后 feed 停，中转也停——这是当前边界，
 * 不是 bug（见 DELIVERY）。
 */
class ChannelAgentRelay(
    private val handPort: RemoteAgentChatPort,
    private val manusPort: ManusChatPort,
    private val transport: PlatformChannelTransport,
    private val channelId: String,
    private val resolveTarget: (AgentRecord) -> RemoteTarget?,
    private val isManusConfigured: (AgentRecord) -> Boolean,
    /** 已中转过的频道消息 id（调用方持有；本类只在同步块里读写）。 */
    private val relayedIds: MutableSet<String>,
    /** 本机贴回频道的中转回复 id（调用方持有；同上）。 */
    private val relayOutputIds: MutableSet<String>,
    private val timeoutSeconds: Long = TeamChatLimits.RELAY_TIMEOUT_SECONDS,
    /** runAgent/runChat 自身超时之外的兜底余量（秒；与扇出同款 30 秒）。 */
    private val timeoutMarginSeconds: Long = 30,
) {
    /** 两组 id 共用一把锁（调用方传进来的集合本身也要同步访问，见下）。 */
    private val idLock = Any()

    /**
     * 找出一条消息里 @ 到的、需要代理的 Agent：注册表里非平台 Agent，
     * 正文含 `@agentId` 或 `@显示名`（不分大小写；显示名也按归一化
     * 匹配，如 `@grokbot` 能命中 “Example Agent”——平台 Agent 本就跳过，
     * 这里只是举例）。没带 @ 的正文不命中——@ 是寻址机制，不 @
     * 就只发给频道里直连的成员，不群发打扰设备/云端 Agent。
     */
    fun findMentions(text: String, agents: List<AgentRecord>): List<AgentRecord> {
        if (text.isBlank()) return emptyList()
        val normalizedText = normalizeName(text)
        return agents.filter { agent ->
            // Agent 开关（2026-11-12）：停用的 Agent 不接 @ 中转——
            // 频道里 @ 它也不回，重新启用后才恢复。
            if (!agent.enabled) return@filter false
            if (agent.brainKind == BrainKind.PLATFORM_AGENT) return@filter false
            text.contains("@${agent.agentId}", ignoreCase = true) ||
                text.contains("@${agent.displayName}", ignoreCase = true) ||
                normalizedText.contains("@${normalizeName(agent.displayName)}")
        }
    }

    /**
     * 中转一条频道消息：对每个 @ 到的 Agent 各转一次（顺序转，失败
     * 一个不影响下一个）。无 @、已转过、是本机回流时返回空表。
     * 调用方按返回的 outcomes 写提示；FAILED 的绝不假装回了。
     */
    suspend fun relay(
        message: PlatformChannelMessage,
        agents: List<AgentRecord>,
    ): List<RelayOutcome> {
        val alreadyHandled = synchronized(idLock) {
            if (message.id in relayedIds || message.id in relayOutputIds) true
            else {
                relayedIds += message.id
                false
            }
        }
        if (alreadyHandled) return emptyList()
        val mentions = findMentions(message.text, agents)
        if (mentions.isEmpty()) return emptyList()
        val sender = message.agentName.ifBlank { message.agentId.ifBlank { "频道里有人" } }
        val brief = TeamChatRules.buildRelayBrief(sender, message.text)
        return mentions.map { agent -> relayToAgent(message, agent, brief) }
    }

    private suspend fun relayToAgent(
        message: PlatformChannelMessage,
        agent: AgentRecord,
        brief: String,
    ): RelayOutcome {
        if (agent.brainKind == BrainKind.PLATFORM_AGENT) {
            return RelayOutcome(agent.agentId, agent.displayName, RelayStatus.SKIPPED, "平台直连，不走代理")
        }
        val callId = "relay-${message.id}-${agent.agentId}"
        val reply: String? = try {
            val target = resolveTarget(agent)
            when {
                target != null -> runHandRelay(target, callId, brief)
                isManusConfigured(agent) -> runManusRelay(agent, brief)
                agent.deviceId != null ->
                    return RelayOutcome(
                        agent.agentId,
                        agent.displayName,
                        RelayStatus.FAILED,
                        "设备 ${agent.deviceId} 未配对或没指定工作文件夹，中转不到它。",
                    )
                else ->
                    return RelayOutcome(
                        agent.agentId,
                        agent.displayName,
                        RelayStatus.FAILED,
                        "没有可用的中转通道：既无 Hand 直连，Manus 也没配 API Key。",
                    )
            }
        } catch (e: Exception) {
            return RelayOutcome(
                agent.agentId,
                agent.displayName,
                RelayStatus.FAILED,
                (e.message ?: e.javaClass.simpleName).take(200),
            )
        }
        if (reply.isNullOrBlank()) {
            return RelayOutcome(
                agent.agentId,
                agent.displayName,
                RelayStatus.FAILED,
                "它回了空消息，没贴回频道。",
            )
        }
        return try {
            // 2026-11-12 团队对话分串：中转回复带结构化归属头（发送者
            // + 任务），不再是裸 "[显示名] " 前缀；任务从被 @ 的那条
            // 消息的归属头继承（它有任务标签时才带，没有就只写发送者）。
            // 这是讨论：多声音、各署名。
            val taskAttr = parseTeamAttribution(message.text)
            val header = teamAttributionHeader(agent.displayName, taskAttr?.taskId, taskAttr?.taskTitle)
            val postedId = transport.postMessage(
                channelId,
                "$header\n${reply.take(TeamChatLimits.RELAY_REPLY_CHARS)}",
            )
            synchronized(idLock) { relayOutputIds += postedId }
            RelayOutcome(agent.agentId, agent.displayName, RelayStatus.RELAYED)
        } catch (e: Exception) {
            RelayOutcome(
                agent.agentId,
                agent.displayName,
                RelayStatus.FAILED,
                "回流进频道失败：${(e.message ?: e.javaClass.simpleName).take(200)}",
            )
        }
    }

    /** Hand 直连一次聊天调用：只收终态 Result/Blocked，超时判失败（不抛）。 */
    private suspend fun runHandRelay(
        target: RemoteTarget,
        callId: String,
        brief: String,
    ): String? {
        val event = withTimeoutOrNull((timeoutSeconds + timeoutMarginSeconds) * 1000) {
            handPort.runAgent(
                target = target,
                taskId = callId,
                text = brief,
                timeoutSeconds = timeoutSeconds,
            ).firstOrNull { it is HandEvent.Result || it is HandEvent.Blocked }
        } ?: throw IllegalStateException("在 ${timeoutSeconds} 秒内没有回终态。")
        return when (event) {
            is HandEvent.Result -> event.output
            is HandEvent.Blocked -> throw IllegalStateException(event.reason.take(200))
            else -> null
        }
    }

    /** Manus 一次聊天调用：只收终态 Result/Failed，超时判失败（不抛）。 */
    private suspend fun runManusRelay(agent: AgentRecord, brief: String): String? {
        val event = withTimeoutOrNull((timeoutSeconds + timeoutMarginSeconds) * 1000) {
            manusPort.runChat(
                conversationId = "team-channel-relay-${agent.agentId}",
                agent = agent,
                prompt = brief,
            ).firstOrNull { it is ManusChatEvent.Result || it is ManusChatEvent.Failed }
        } ?: throw IllegalStateException("在 ${timeoutSeconds} 秒内没有回终态。")
        return when (event) {
            is ManusChatEvent.Result -> event.text
            is ManusChatEvent.Failed -> throw IllegalStateException(event.message.take(200))
            else -> null
        }
    }

    /**
     * 名字归一化：与 [wherevercode.agent.core.agent.PlatformAgent.normalizeAgentName]
     * 同规则（去空白、横杠、下划线，转小写），这里不引 PlatformAgent
     * 是为避免 core.team → core.agent 的包循环——规则只有三行，
     * 改动时两处同步改。
     */
    private fun normalizeName(name: String): String =
        name.lowercase().filter { it != ' ' && it != '-' && it != '_' }
}
