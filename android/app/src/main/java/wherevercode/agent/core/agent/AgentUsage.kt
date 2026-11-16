package wherevercode.agent.core.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 账号用量（Paseo 式连接第 4 件，2026-11-17）：Hand 经设备上已登录的
 * CLI 本人查回的归一化用量，App 只收展示字段——套餐名、各窗口用量
 * 百分比与重置时间、积分余额。原始凭据只留在设备、只由 CLI 本人
 * 使用，Hand 与 App 都不读不存（协议见 step6-paseo-connect-design.md
 * §3.6，Hand 侧 `agent_usage` 设备管理动作）。
 *
 * 诚实口径：[status] 不是 OK 时 plan/windows/credits 一律为空，
 * [detail] 是中文实话（如未登录、无规格），界面不得拿空值拼假用量。
 */
data class UsageWindow(
    /** "5h" / "weekly" / 厂商给的其他窗口标签。 */
    val kind: String,
    /** 窗口长度（分钟）；厂商没给时为 null。 */
    val windowMinutes: Int?,
    /** 已用百分比 0–100。 */
    val usedPercent: Double,
    /** 重置时间（epoch 秒）；厂商没给时为 null。 */
    val resetsAtEpochSeconds: Long?,
)

data class AgentUsage(
    val agentId: String,
    val provider: String,
    /** ok / not_logged_in / unsupported / error（未知值原样保留）。 */
    val status: String,
    /** 套餐显示名（如 Plus）；未知为 null。 */
    val plan: String?,
    val windows: List<UsageWindow>,
    /** 积分余额（厂商原样字符串，如 "0"）；未知为 null。 */
    val creditsBalance: String?,
    val creditsUnlimited: Boolean,
    val queriedAtEpochSeconds: Long,
    /** status 非 ok 时的中文原因；ok 时为空串。 */
    val detail: String,
) {
    val isOk: Boolean get() = status == "ok"

    /** 5 小时窗口（Codex 的 primary）；没有就 null，不拿别的窗口冒充。 */
    fun fiveHourWindow(): UsageWindow? = windows.firstOrNull { it.kind == "5h" }

    /** 每周窗口（Codex 的 secondary）；没有就 null。 */
    fun weeklyWindow(): UsageWindow? = windows.firstOrNull { it.kind == "weekly" }

    /** 设备页一行摘要（ok 时）：如「Plus · 5 小时 0% · 每周 100% · 积分 0」。 */
    fun summaryLine(): String {
        if (!isOk) return detail.ifBlank { "用量未知" }
        val parts = mutableListOf<String>()
        plan?.takeIf { it.isNotBlank() }?.let { parts += it }
        fiveHourWindow()?.let { parts += "5 小时 ${formatPercent(it.usedPercent)}" }
        weeklyWindow()?.let { parts += "每周 ${formatPercent(it.usedPercent)}" }
        windows.filter { it.kind != "5h" && it.kind != "weekly" }.forEach {
            parts += "${it.kind} ${formatPercent(it.usedPercent)}"
        }
        when {
            creditsUnlimited -> parts += "积分不限"
            creditsBalance != null -> parts += "积分 $creditsBalance"
        }
        return if (parts.isEmpty()) "已查到用量（无窗口数据）" else parts.joinToString(" · ")
    }

    companion object {
        /** 百分比显示：整数不带小数点，其余保留一位。 */
        fun formatPercent(value: Double): String =
            if (value == value.toLong().toDouble()) "${value.toLong()}%"
            else "${(Math.round(value * 10) / 10.0)}%"
    }
}

/** 解析 Hand 回包 args 里的 usage JSON 数组字符串（纯函数，可单测）。 */
object AgentUsageParser {
    /** 坏 JSON / 非数组 → 空列表（调用方按「没查到」如实显示，不崩溃）。 */
    fun parseList(usageJson: String): List<AgentUsage> {
        val array = try {
            JSONArray(usageJson)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = mutableListOf<AgentUsage>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseOne(obj)?.let { out += it }
        }
        return out
    }

    fun parseOne(obj: JSONObject): AgentUsage? {
        val agentId = obj.optString("agent_id", "").trim()
        if (agentId.isEmpty()) return null
        val windows = mutableListOf<UsageWindow>()
        val windowsArray = obj.optJSONArray("windows")
        if (windowsArray != null) {
            for (i in 0 until windowsArray.length()) {
                val w = windowsArray.optJSONObject(i) ?: continue
                if (!w.has("used_percent")) continue
                windows += UsageWindow(
                    kind = w.optString("kind", "window").ifBlank { "window" },
                    windowMinutes = if (w.isNull("window_minutes")) null
                        else w.optInt("window_minutes", 0).takeIf { it > 0 },
                    usedPercent = w.optDouble("used_percent", 0.0),
                    resetsAtEpochSeconds = if (w.isNull("resets_at")) null
                        else w.optLong("resets_at", 0L).takeIf { it > 0 },
                )
            }
        }
        return AgentUsage(
            agentId = agentId,
            provider = obj.optString("provider", agentId).ifBlank { agentId },
            status = obj.optString("status", "error").ifBlank { "error" },
            plan = obj.optString("plan", "").trim().takeIf { it.isNotEmpty() },
            windows = windows,
            creditsBalance = if (obj.isNull("credits_balance")) null
                else obj.optString("credits_balance", "").takeIf { it.isNotEmpty() },
            creditsUnlimited = obj.optBoolean("credits_unlimited", false),
            queriedAtEpochSeconds = obj.optLong("queried_at", 0L),
            detail = obj.optString("detail", ""),
        )
    }
}
