package wherevercode.agent.core.team

import org.json.JSONObject
import wherevercode.agent.core.agent.PlatformChannelMessage

/**
 * 频道目录（2026-11-13，频道能力补齐）：列出平台上的对话频道、加入频道。
 *
 * 服务端工具口径（2026-11-13 经 tools/list 实测）：
 * - `agent_channel_list`（无参数）：列出本账号的对话频道。已加入的带
 *   `last_message` / `unread_count` / `first_unread_id`；未加入的只给
 *   名和参与者，不给最新消息和未读数。
 * - `agent_channel_join`（`channel_id`）：加入一个同账号频道。加入前只
 *   能看到频道名和参与者，不能读消息、不能发消息；加入后才能读写——
 *   这就是服务端的成员权限 enforcement，App 侧的门（[ChannelAccess]）
 *   只是同一规则的前置检查，绝不替代服务端。
 * - `agent_channel_create`：服务端有这个工具，但 Funk 2026-11-04 只
 *   授权了 list/join/messages/post，明确让 create 保持屏蔽——本文件
 *   刻意不提供创建频道的任何入口。想开新频道须由 Funk 另行授权，
 *   不要顺带加进去。
 *
 * 本文件只做模型 + 结果解析 + 一个调用端口（[ChannelDirectoryPort]）：
 * 真正发 MCP 调用的实现在 MainActivity/传输层（`agent_channel_list` /
 * `agent_channel_join` 经 `PlatformChannelMcpTransport.callTool`），
 * 接线见 DELIVERY。解析是容错的：缺字段按“不知道”处理，坏条目跳过，
 * 绝不因一条坏数据丢掉整个列表。
 */
data class ChannelParticipant(
    val agentId: String,
    val agentName: String,
    val joinedAtSeconds: Double = 0.0,
)

/** 一个对话频道（`agent_channel_list` 一条结果）。 */
data class PlatformChannelInfo(
    val id: String,
    val name: String,
    /** 我方身份是否已加入（见 [parseChannelList] 的推断规则）。 */
    val joined: Boolean,
    val participants: List<ChannelParticipant> = emptyList(),
    val lastMessage: PlatformChannelMessage? = null,
    val unreadCount: Int = 0,
    val firstUnreadId: String? = null,
) {
    fun hasParticipant(agentId: String): Boolean =
        participants.any { it.agentId == agentId }

    fun participantIds(): Set<String> = participants.map { it.agentId }.toSet()
}

/** 频道目录调用口：实现方经 MCP 调 `agent_channel_list` / `agent_channel_join`。 */
interface ChannelDirectoryPort {
    /** 列出本账号的对话频道（`agent_channel_list`，无参数）。 */
    suspend fun listChannels(): List<PlatformChannelInfo>

    /**
     * 加入一个频道（`agent_channel_join`）。成功返回 true；服务端拒绝
     * （如频道不存在）时抛异常，由调用方把原因如实显示给用户。
     */
    suspend fun joinChannel(channelId: String): Boolean
}

/** 已加入的频道（按列表顺序）。 */
fun joinedChannels(channels: List<PlatformChannelInfo>): List<PlatformChannelInfo> =
    channels.filter { it.joined }

/** 还没加入、可加入的频道（按列表顺序）。 */
fun joinableChannels(channels: List<PlatformChannelInfo>): List<PlatformChannelInfo> =
    channels.filter { !it.joined }

/**
 * 保证当前频道在列表里：服务端列表万一没给当前频道（例如所用令牌的
 * 身份与列表身份不一致），UI 仍要显示当前频道——补一条“已加入、成员
 * 未知”的占位（成员未知时 [ChannelAccess] 不做成员断言，不会误拦）。
 */
fun mergeCurrentChannel(
    channels: List<PlatformChannelInfo>,
    currentId: String,
    currentName: String?,
): List<PlatformChannelInfo> {
    if (channels.any { it.id == currentId }) return channels
    val name = currentName?.trim()?.takeIf { it.isNotBlank() } ?: currentId
    return channels + PlatformChannelInfo(id = currentId, name = name, joined = true)
}

/**
 * 解析 `agent_channel_list` 的工具 JSON。
 * 形状：`{"ok":true,"channels":[{id,name,created_at,
 * participants:[{agent_id,agent_name,joined_at}],
 * last_message:{id,channel_id,agent_id,agent_name,text,reply_to,created_at},
 * unread_count,first_unread_id}]}`（未加入的没有后三项）。
 *
 * `joined` 推断（按可靠程度）：
 * 1. 服务端给了显式的 `joined` 布尔就信它；
 * 2. 否则我方身份（[selfAgentId]）在 participants 里 → 已加入；
 * 3. 否则带 `last_message` / `unread_count` / `first_unread_id` 任一 → 已加入
 *    （未加入的按服务端说明不给这三项）；
 * 4. 否则按未加入处理（宁可多显示一个“加入”按钮，也不谎称已加入）。
 * 无 id 或无 name 的条目跳过；`channels` 缺失返回空表。
 */
fun parseChannelList(toolJson: JSONObject, selfAgentId: String? = null): List<PlatformChannelInfo> {
    val channels = toolJson.optJSONArray("channels") ?: return emptyList()
    val out = mutableListOf<PlatformChannelInfo>()
    for (i in 0 until channels.length()) {
        val obj = channels.optJSONObject(i) ?: continue
        val id = obj.optString("id", "").trim()
        val name = obj.optString("name", "").trim()
        if (id.isBlank() || name.isBlank()) continue
        val participants = mutableListOf<ChannelParticipant>()
        val parts = obj.optJSONArray("participants")
        if (parts != null) {
            for (j in 0 until parts.length()) {
                val p = parts.optJSONObject(j) ?: continue
                val pid = p.optString("agent_id", "").trim()
                if (pid.isBlank()) continue
                participants += ChannelParticipant(
                    agentId = pid,
                    agentName = p.optString("agent_name", pid).trim().ifBlank { pid },
                    joinedAtSeconds = p.optDouble("joined_at", 0.0),
                )
            }
        }
        val lastMessage = obj.optJSONObject("last_message")?.let { m ->
            PlatformChannelMessage(
                id = m.optString("id", ""),
                agentId = m.optString("agent_id", ""),
                agentName = m.optString("agent_name", ""),
                text = m.optString("text", ""),
                createdAtSeconds = m.optDouble("created_at", 0.0),
            )
        }
        val joined = when {
            obj.has("joined") -> obj.optBoolean("joined", false)
            !selfAgentId.isNullOrBlank() && participants.any { it.agentId == selfAgentId } -> true
            lastMessage != null || obj.has("unread_count") || obj.has("first_unread_id") -> true
            else -> false
        }
        out += PlatformChannelInfo(
            id = id,
            name = name,
            joined = joined,
            participants = participants,
            lastMessage = lastMessage,
            unreadCount = obj.optInt("unread_count", 0).coerceAtLeast(0),
            firstUnreadId = obj.optString("first_unread_id", "").trim().takeIf { it.isNotBlank() },
        )
    }
    return out
}
