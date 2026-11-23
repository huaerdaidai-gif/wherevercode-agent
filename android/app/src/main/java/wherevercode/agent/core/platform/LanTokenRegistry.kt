package wherevercode.agent.core.platform

import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内网成员令牌注册表（频道三层二期·内网层）：宿主 App 给同网的
 * 其他设备/ Agent 签发加入令牌，令牌只映射到一个登记身份
 * （agentId + 显示名），[LanPlatformServer] 靠它鉴权——没有令牌
 * 就没有内网身份，成员门禁仍由 [LocalChannelService] 按 agentId
 * 执行，这里不另造权限逻辑。
 *
 * 持久化口与本地账本同款：生产实现写 App 私有文件
 * （lan_platform_tokens.json），测试用内存回调。令牌是 32 位 hex
 * 随机串，只存宿主本机；撤销即刻生效（服务端每请求现查注册表）。
 */
class LanTokenRegistry(
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
) {
    data class Entry(
        val token: String,
        val agentId: String,
        val agentName: String,
        val createdAtMs: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()

    init {
        restore()
    }

    /** 给一位新成员签发令牌（名字是宿主给对方起的显示名）。 */
    @Synchronized
    fun issue(agentName: String): Entry {
        val name = agentName.trim().ifBlank { "内网成员" }
        var agentId: String
        do {
            agentId = "lan-" + randomHex(3)
        } while (entries.values.any { it.agentId == agentId })
        val entry = Entry(
            token = randomHex(16),
            agentId = agentId,
            agentName = name,
            createdAtMs = System.currentTimeMillis(),
        )
        entries[entry.token] = entry
        persist()
        return entry
    }

    /** 令牌换登记身份；未知/已撤销返回 null。 */
    @Synchronized
    fun authenticate(token: String?): Entry? {
        val key = token?.trim().orEmpty()
        if (key.isBlank()) return null
        return entries[key]
    }

    /** 撤销一枚令牌；返回是否确有这枚令牌。 */
    @Synchronized
    fun revoke(token: String): Boolean {
        val removed = entries.remove(token.trim()) != null
        if (removed) persist()
        return removed
    }

    /** 当前全部登记（界面展示用，含令牌本身——只给宿主本机界面看）。 */
    @Synchronized
    fun list(): List<Entry> = entries.values.toList()

    private fun randomHex(bytes: Int): String {
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return raw.joinToString("") { "%02x".format(it) }
    }

    private fun persist() {
        val root = JSONObject().apply {
            put("entries", JSONArray().apply {
                for (entry in entries.values) {
                    put(JSONObject().apply {
                        put("token", entry.token)
                        put("agent_id", entry.agentId)
                        put("agent_name", entry.agentName)
                        put("created_at_ms", entry.createdAtMs)
                    })
                }
            })
        }
        try {
            save(root.toString())
        } catch (_: Exception) {
            // 落盘失败不拖垮签发：内存表仍在，本进程内有效。
        }
    }

    private fun restore() {
        val text = try {
            load()
        } catch (_: Exception) {
            null
        } ?: return
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        val rows = root.optJSONArray("entries") ?: return
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val token = row.optString("token", "").trim()
            val agentId = row.optString("agent_id", "").trim()
            if (token.isBlank() || agentId.isBlank()) continue
            entries[token] = Entry(
                token = token,
                agentId = agentId,
                agentName = row.optString("agent_name", "").ifBlank { agentId },
                createdAtMs = row.optLong("created_at_ms", 0L),
            )
        }
    }
}
