package wherevercode.agent.core.team

import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.agent.PlatformAgent
import wherevercode.agent.core.agent.PlatformChannelMessage

/**
 * 频道发送者权限（2026-11-13 安全加固）。
 *
 * 平台频道是多方共用的：除了用户在 Agent 页登记的平台 Agent，频道里
 * 还可能有任何持有平台令牌的参与者。此前：
 * - **@ 中转**不看是谁发的——频道里任何人发一句 `@Codex …`，App 就会把它
 *   转给用户配对设备上的 Agent 去跑（Hand runAgent），等于把本机设备的
 *   执行入口开放给了整个频道；
 * - **归属头**（`【Codex · 任务 …】`）完全信正文——任何人都能在正文里
 *   冒充别的 Agent 署名。
 *
 * 规则（发送者身份只认平台服务端给的 `agent_id`，不认正文）：
 * - 受信发送者 = 本机 App 自己发出的消息（按本进程记下的消息 id）
 *   + 由这些消息学到的 App 自身 agent_id + 用户在 Agent 页登记且启用的
 *   平台 Agent（`platformAgentId` 归一化比对）。
 * - 只有受信发送者的 @ 才中转；其余如实报“未中转”，不执行。
 * - 归属头里的署名只有在发帖者是 App 自己（中转回帖）或署名与真实发送者
 *   一致时才采信；否则显示真实发送者并标“自称 X”。
 */
object ChannelSenderPolicy {

    fun isTrustedRelaySender(
        message: PlatformChannelMessage,
        agents: List<AgentRecord>,
        ownPostIds: Set<String>,
        selfAgentIds: Set<String>,
    ): Boolean {
        if (message.id.isNotEmpty() && message.id in ownPostIds) return true
        val sender = message.agentId.trim()
        if (sender.isEmpty()) return false
        if (selfAgentIds.any { PlatformAgent.sameAgent(it, sender) }) return true
        return agents.any { agent ->
            agent.enabled &&
                agent.brainKind == BrainKind.PLATFORM_AGENT &&
                !agent.platformAgentId.isNullOrBlank() &&
                PlatformAgent.sameAgent(agent.platformAgentId!!, sender)
        }
    }

    /** 从 App 自己发出的消息（按 id）学到 App 在平台上的 agent_id。 */
    fun learnSelfAgentIds(messages: List<PlatformChannelMessage>, ownPostIds: Set<String>): Set<String> =
        messages.filter { it.id.isNotEmpty() && it.id in ownPostIds && it.agentId.isNotBlank() }
            .map { it.agentId }
            .toSet()
}
