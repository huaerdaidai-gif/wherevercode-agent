package wherevercode.agent.core.team

/**
 * 频道成员本地屏蔽（2026-11-13，频道能力补齐）。
 *
 * 服务端没有“屏蔽成员 / 踢人 / 退频道”的工具——频道成员由平台管理，
 * App 内能做的成员管理是：查看成员（名、角色）、@ 他、找他 1:1 聊、
 * 以及本机屏蔽（只影响本机显示，不影响频道、不通知对方、不改服务端）。
 * 本类只存“本机屏蔽了谁”，按频道分键；id 与显示名都存——中转回流
 * 的回复是 App 代贴的（agentId 是平台 Agent 的 id），屏蔽要按归属头
 * 里的真实发送者名匹配才拦得住。
 * 存储经 [ChannelKeyValueStore]（App 侧是 SharedPreferences 适配，
 * 见 TeamChannelController；测试用内存假实现）。
 */
class ChannelMuteStore(private val store: ChannelKeyValueStore) {

    private fun idsKey(channelId: String): String = "channel_muted_ids_$channelId"
    private fun namesKey(channelId: String): String = "channel_muted_names_$channelId"

    /** 本频道已屏蔽的成员 id。 */
    fun mutedAgentIds(channelId: String): Set<String> =
        readSet(idsKey(channelId))

    /** 本频道已屏蔽的成员显示名（原样存，匹配时不分大小写）。 */
    fun mutedNames(channelId: String): Set<String> =
        readSet(namesKey(channelId))

    /**
     * 是否屏蔽：id 命中，或显示名命中（不分大小写、去空白）。
     * local-app（本机）永不屏蔽——屏蔽自己会把自己的回音也藏掉。
     */
    fun isMuted(channelId: String, agentId: String, displayName: String): Boolean {
        if (agentId == "local-app") return false
        if (agentId.isNotBlank() && agentId in mutedAgentIds(channelId)) return true
        val name = displayName.trim().lowercase()
        if (name.isBlank()) return false
        return mutedNames(channelId).any { it.trim().lowercase() == name }
    }

    /** 屏蔽 / 取消屏蔽一位成员（id 与显示名同时记 / 同时清）。 */
    fun setMuted(channelId: String, agentId: String, displayName: String, muted: Boolean) {
        if (agentId.isBlank() && displayName.isBlank()) return
        val ids = mutedAgentIds(channelId).toMutableSet()
        val names = mutedNames(channelId).toMutableSet()
        if (muted) {
            if (agentId.isNotBlank()) ids += agentId
            if (displayName.isNotBlank()) names += displayName.trim()
        } else {
            ids -= agentId
            names.removeAll { it.trim().lowercase() == displayName.trim().lowercase() }
        }
        writeSet(idsKey(channelId), ids)
        writeSet(namesKey(channelId), names)
    }

    private fun readSet(key: String): Set<String> =
        store.getString(key).orEmpty().split("\n").map { it.trim() }
            .filter { it.isNotBlank() }.toSet()

    private fun writeSet(key: String, values: Set<String>) {
        store.putString(key, values.sorted().joinToString("\n"))
    }
}
