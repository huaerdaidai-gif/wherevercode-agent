package wherevercode.agent.core.team

/**
 * 频道中转去重存储（2026-11-13，频道能力补齐）。
 *
 * 问题：[ChannelAgentRelay] 的防重 id 集（relayedIds / relayOutputIds）
 * 现在只活在 MainActivity 的内存里——App 重启后丢掉，feed 首轮拉回
 * 的最新 20 条里若有重启前已中转过的 @ 消息，会再中转一次，频道里
 * 出现重复回复。本文件给中转一个可持久化的去重口：
 * - 进程内默认用 [InMemoryChannelRelayDedupStore]（与现状同口径）；
 * - MainActivity 接线后传 [KeyValueChannelRelayDedupStore]（SharedPreferences
 *   持久化，见 DELIVERY 接线段），重启也不重复中转。
 * 存的只是消息 id（不含正文、不含令牌），按频道分键。
 */
interface ChannelRelayDedupStore {
    fun isRelayed(messageId: String): Boolean
    fun markRelayed(messageId: String)
    fun isRelayOutput(messageId: String): Boolean
    fun markRelayOutput(messageId: String)
}

/** 最小的键值口径（SharedPreferences 的子集）：测试用内存假实现，App 侧用 SharedPreferences 适配。 */
interface ChannelKeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** 内存去重（进程内；App 重启后丢失——与 MainActivity 现有 id 集同口径）。 */
class InMemoryChannelRelayDedupStore(
    private val maxKept: Int = MAX_KEPT,
) : ChannelRelayDedupStore {
    private val relayed = LinkedHashSet<String>()
    private val outputs = LinkedHashSet<String>()

    @Synchronized
    override fun isRelayed(messageId: String): Boolean = messageId in relayed

    @Synchronized
    override fun markRelayed(messageId: String) {
        if (messageId.isBlank()) return
        relayed += messageId
        prune(relayed)
    }

    @Synchronized
    override fun isRelayOutput(messageId: String): Boolean = messageId in outputs

    @Synchronized
    override fun markRelayOutput(messageId: String) {
        if (messageId.isBlank()) return
        outputs += messageId
        prune(outputs)
    }

    private fun prune(set: LinkedHashSet<String>) {
        while (set.size > maxKept) {
            val iterator = set.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            } else {
                break
            }
        }
    }

    companion object {
        const val MAX_KEPT: Int = 1000
    }
}

/**
 * 键值持久化去重：id 按插入顺序拼成逗号串存在一个键里（消息 id 是
 * hex/uuid 形态，不含逗号）。两个键：已中转 / 本机回流。超 [maxKept]
 * 时从最旧的开始丢。
 */
class KeyValueChannelRelayDedupStore(
    private val store: ChannelKeyValueStore,
    private val channelId: String,
    private val maxKept: Int = InMemoryChannelRelayDedupStore.MAX_KEPT,
) : ChannelRelayDedupStore {
    private fun relayedKey(): String = "channel_relayed_$channelId"
    private fun outputKey(): String = "channel_relay_output_$channelId"

    override fun isRelayed(messageId: String): Boolean =
        messageId.isNotBlank() && readIds(relayedKey()).contains(messageId)

    override fun markRelayed(messageId: String) {
        if (messageId.isBlank()) return
        writeIds(relayedKey(), readIds(relayedKey()) + messageId)
    }

    override fun isRelayOutput(messageId: String): Boolean =
        messageId.isNotBlank() && readIds(outputKey()).contains(messageId)

    override fun markRelayOutput(messageId: String) {
        if (messageId.isBlank()) return
        writeIds(outputKey(), readIds(outputKey()) + messageId)
    }

    private fun readIds(key: String): List<String> =
        store.getString(key).orEmpty().split(",").filter { it.isNotBlank() }

    private fun writeIds(key: String, ids: List<String>) {
        val kept = if (ids.size > maxKept) ids.subList(ids.size - maxKept, ids.size) else ids
        store.putString(key, kept.joinToString(","))
    }
}
