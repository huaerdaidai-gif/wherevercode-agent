package wherevercode.agent.core.agent

import android.content.Context

/**
 * 平台 Agent 频道回复游标：上一次已显示给用户的对方回复的消息 id。
 *
 * 为什么需要它（2026-11-11，Example Agent 回复丢了那次的教训）：一轮等待
 * 只轮询自己发帖之后的消息——上一轮等待中途断掉（手机休眠、轮询
 * 失败），对方在断档里回的消息，下一轮永远看不到，因为新一轮的
 * 轮询起点是自己新发的帖子 id。游标按频道+对方记下“已消费到哪条
 * 回复”，下一轮先从游标往后找，找到就先当作答案显示，再等新消息。
 * 只存消息 id，不存密钥、不存消息内容；对方名归一化后再做键
 * （"example agent" 与 "example-agent" 共用一个游标）。
 */
interface PlatformReplyCursorStore {
    fun get(channelId: String, targetId: String): String?
    fun put(channelId: String, targetId: String, messageId: String)
    fun clear(channelId: String, targetId: String)
}

/** SharedPreferences 实现（生产用）。 */
class SharedPreferencesPlatformReplyCursorStore(context: Context) : PlatformReplyCursorStore {
    private val prefs = context.getSharedPreferences(
        "wherevercode_platform_reply_cursor",
        Context.MODE_PRIVATE,
    )

    override fun get(channelId: String, targetId: String): String? =
        prefs.getString(key(channelId, targetId), null)

    override fun put(channelId: String, targetId: String, messageId: String) {
        prefs.edit().putString(key(channelId, targetId), messageId).apply()
    }

    override fun clear(channelId: String, targetId: String) {
        prefs.edit().remove(key(channelId, targetId)).apply()
    }

    private fun key(channelId: String, targetId: String): String =
        "cursor:$channelId:${PlatformAgent.normalizeAgentName(targetId)}"
}

/** 内存实现（单测用）。 */
class InMemoryPlatformReplyCursorStore : PlatformReplyCursorStore {
    private val map = HashMap<String, String>()
    override fun get(channelId: String, targetId: String): String? = map[key(channelId, targetId)]
    override fun put(channelId: String, targetId: String, messageId: String) {
        map[key(channelId, targetId)] = messageId
    }

    override fun clear(channelId: String, targetId: String) {
        map.remove(key(channelId, targetId))
    }

    private fun key(channelId: String, targetId: String): String =
        "cursor:$channelId:${PlatformAgent.normalizeAgentName(targetId)}"
}
