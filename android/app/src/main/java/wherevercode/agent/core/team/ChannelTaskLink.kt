package wherevercode.agent.core.team

/**
 * 频道消息关联任务（2026-11-13，频道能力补齐）。
 *
 * 关联只作讨论备注：正文前加归属头（复用 [teamAttributionHeader]：
 * `[发送者 · 任务 <短id> · <标题>]`），读回时按任务分串（已有的
 * [groupTeamMessages]）。关联绝不等于派工——
 * - 频道发帖只调 `agent_channel_post`，不调任何任务接口（start_task /
 *   claim_task 不在频道路径上）；
 * - 中转与扇出同样只做聊天调用、不建任务（既有围栏，见 TeamChannel.kt 头）；
 * - 想派活必须回 1:1 聊天走正式任务/认领链路，频道页上没有派活入口。
 * 组合顺序（发送时）：先拼任务头，再拼引用行，最后是正文——归属头
 * 必须在正文开头，读回解析（[parseTeamAttribution]）才认得出任务。
 */
object ChannelTaskLink {
    /** 频道页关联任务行的提示：关联是备注，不是派工。 */
    const val NOT_DISPATCH_NOTE: String = "关联任务只作讨论备注，不派活；派活请去 1:1 聊天走正式流程。"

    /**
     * 在正文前加任务归属头。taskId 为空返回原正文（不关联）。
     * senderName 是发帖方在频道里的显示名（App 发帖用“本机 App”）。
     */
    fun encode(taskId: String, taskTitle: String?, body: String, senderName: String = "本机 App"): String {
        if (taskId.isBlank()) return body
        return teamAttributionHeader(senderName, taskId.trim(), taskTitle) + "\n" + body
    }

    /** 这条正文是否关联了任务（归属头里带任务 id）。 */
    fun isLinked(text: String): Boolean =
        parseTeamAttribution(text)?.taskId != null

    /** 关联的任务短 id（无关联返回 null）。 */
    fun linkedTaskId(text: String): String? =
        parseTeamAttribution(text)?.taskId
}
