package wherevercode.agent.core.team

import wherevercode.agent.core.agent.PlatformChannelMessage

/**
 * 频道成员权限门（2026-11-13，频道能力补齐）。
 *
 * 权限的最终裁决在服务端：未加入的频道，`agent_channel_join` 的说明
 * 写明“加入前只能看到频道名和参与者，不能读消息、不能发消息；加入后
 * 才能读写”——服务端会拦。本文件是 App 侧同一规则的前置检查：
 * - 没加入就不读、不发、不中转，并且给用户一句中文原因，而不是等
 *   服务端报错再猜；
 * - 参与者名单已知时，非成员发来的消息不显示、不中转，@ 非成员不转；
 * - 名单未知（服务端没给参与者，或当前频道不在目录里）时不断言——
 *   宁可放行一条真消息，也不误拦一条（fail-open for unknown，
 *   fail-closed for known-non-member）。
 *
 * 纯函数，无 IO；名单来自 [PlatformChannelInfo.participants]。
 */
object ChannelAccess {

    /** 未加入：读不到（服务端同样会拦）。 */
    fun canRead(joined: Boolean): Boolean = joined

    /** 未加入：发不出（服务端同样会拦）。 */
    fun canPost(joined: Boolean): Boolean = joined

    /** 发不出去时给用户看的中文原因；能发返回 null。 */
    fun postBlockedReason(joined: Boolean): String? =
        if (joined) null else "还没加入这个频道，发不出去。先点“加入频道”。"

    /**
     * 发送者是不是已知非成员：名单已知且发送者不在名单里 → true；
     * 名单未知（null 或空）→ false（不断言）；发送者 id 为空 → false
     * （身份不明的不拦，宁可显示一条来历不明的真消息）。
     */
    fun isNonMember(senderId: String, participantIds: Set<String>?): Boolean {
        if (senderId.isBlank()) return false
        if (participantIds.isNullOrEmpty()) return false
        return senderId !in participantIds
    }

    /**
     * 只留成员发来的消息（展示与中转共用同一口径）。名单未知时原样返回。
     */
    fun filterReadable(
        messages: List<PlatformChannelMessage>,
        participantIds: Set<String>?,
    ): List<PlatformChannelMessage> {
        if (participantIds.isNullOrEmpty()) return messages
        return messages.filter { it.agentId.isBlank() || it.agentId in participantIds }
    }

    /**
     * 中转目标是不是允许：名单未知时放行（不断言），名单已知且目标
     * 不在名单里 → 不转（未授权的成员收不到 @，也不回）。
     */
    fun relayTargetAllowed(agentId: String, participantIds: Set<String>?): Boolean {
        if (participantIds.isNullOrEmpty()) return true
        return agentId in participantIds
    }
}
