package wherevercode.agent.brain

import android.content.Context
import org.json.JSONArray
import wherevercode.agent.brain.generic.FreeProviderPresets

/**
 * 智能路由（三件套之三，2026-11-18 定案）：
 * - 以免费为主、付费可选：路由默认只在免费模型之间切换；付费模型
 *   只有在用户主动打开 [ModelRoutingSettings.allowPaid] 后才进池。
 * - v1 只做优先级顺序 + 故障切换：遇可重试失败（402 额度/429 限流
 *   /5xx 等，retryable 由适配器判定）按池顺序切到下一个并重发，
 *   用户无感；401/400 等不可重试错误不切（切了也没用，立即报出）——
 *   切换机制复用既有 [Failover]/[FailoverPolicy]，本文件只管
 *   「池怎么定、顺序怎么排」。
 * - 总开关默认关；关时引擎行为与从前一字不变。
 * - 池只收 MODEL_API 模型连接；大脑/会员/Agent 不进模型路由池
 *   （概念分离：模型是模型、Agent 是 Agent）。
 */
data class ModelRoutingSettings(
    val enabled: Boolean = false,
    val allowPaid: Boolean = false,
    /** 路由池顺序（brainId）：靠前优先被切到；池外模型不参与。 */
    val orderedBrainIds: List<String> = emptyList(),
)

object ModelRouting {

    /** 免费档标记：从免费预设/自动发现添加的配置带 tier=free。 */
    fun isFree(config: BrainConfig): Boolean =
        config.extra[FreeProviderPresets.EXTRA_TIER] == FreeProviderPresets.TIER_FREE

    /**
     * 本轮的切换顺序：池内、仍存在、MODEL_API、档次合规（未开付费
     * 时只留免费）、排除当前模型本身。池顺序即优先级。
     */
    fun fallbackOrder(
        settings: ModelRoutingSettings,
        configs: List<BrainConfig>,
        currentBrainId: String,
    ): List<String> {
        if (!settings.enabled) return emptyList()
        val byId = configs.associateBy { it.brainId }
        return settings.orderedBrainIds.filter { id ->
            if (id == currentBrainId) return@filter false
            val config = byId[id] ?: return@filter false
            if (config.kind != BrainKind.MODEL_API) return@filter false
            settings.allowPaid || isFree(config)
        }
    }

    fun policyFor(
        settings: ModelRoutingSettings,
        configs: List<BrainConfig>,
        currentBrainId: String,
    ): FailoverPolicy = FailoverPolicy(fallbackBrainIds = fallbackOrder(settings, configs, currentBrainId))
}

interface ModelRoutingStore {
    fun load(): ModelRoutingSettings
    fun save(settings: ModelRoutingSettings)
}

/** 设置存本机 SharedPreferences（无密钥内容：只有开关与 brainId 顺序）。 */
class SharedPreferencesModelRoutingStore(context: Context) : ModelRoutingStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): ModelRoutingSettings {
        val order = mutableListOf<String>()
        try {
            val array = JSONArray(prefs.getString(KEY_ORDER, "[]"))
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let { order += it }
            }
        } catch (_: Exception) {
            order.clear()
        }
        return ModelRoutingSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            allowPaid = prefs.getBoolean(KEY_ALLOW_PAID, false),
            orderedBrainIds = order,
        )
    }

    override fun save(settings: ModelRoutingSettings) {
        val array = JSONArray()
        settings.orderedBrainIds.forEach { array.put(it) }
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putBoolean(KEY_ALLOW_PAID, settings.allowPaid)
            .putString(KEY_ORDER, array.toString())
            .apply()
    }

    companion object {
        const val FILE: String = "wherevercode_model_routing"
        const val KEY_ENABLED: String = "routing_enabled"
        const val KEY_ALLOW_PAID: String = "routing_allow_paid"
        const val KEY_ORDER: String = "routing_order_json"
    }
}
