package wherevercode.agent.ui

import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.devicecode.DeviceCodeConfigs

/**
 * 模型页“已添加模型”的行数据（纯逻辑，无 Android 依赖，可单测）。
 *
 * 与设置页大脑列表彻底分家（2026-11-19，Funk 定：大脑已砍开放一边，
 * 模型页不许拿大脑列表改标题充数）：数据源是已配置的模型连接
 * （configStore 里的 BrainConfig 本来就是模型连接：模型 API 地址、
 * CLI 会员、订阅授权），在这里按连接类型分组呈现——模型 API /
 * CLI 会员 / 订阅授权分开列；行只报名称、模型与真实状态，不带
 * “点按切换大脑”的语义，管理动作仍走既有管理单（调用方接线）。
 *
 * 诚实规则与大脑列表同源（状态取真实值，绝不写死“已连接”）；
 * 状态文案复用 [BrainLoginRows] 的纯函数（只读复用，不改其行为）：
 * - CLI 连接的身份是 cliBinary，不是 model 字段；未配二进制时整行
 *   不出现（没有 CLI 是正常态，不拿“未发现”占位）。
 * - Gemini 仅登录、不能用推理，状态行如实写明（沿用大脑列表口径）。
 * - 平台 / 云端 Agent 不是模型连接，不进本列表。
 */
object ModelListRows {

    /** 已添加模型一行（MainActivity 据此渲染，不在 Activity 里拼文案）。 */
    data class ModelListRow(
        val brainId: String,
        /** 分组标题：模型 API / CLI 会员 / 订阅授权。 */
        val groupTitle: String,
        val title: String,
        /** 当前模型名；CLI 行为 null（身份是 cliBinary，已在状态文案里）。 */
        val detailText: String?,
        val statusText: String,
        /** 这条连接是否正被当前大脑使用（只标注，不提供切换动作）。 */
        val isCurrent: Boolean,
    )

    /**
     * 连接 → 分组标题；平台 / 云端 Agent 不是模型，返回 null 不入列。
     * cli-membership 的 kind 是 SUBSCRIPTION（借会员额度），但呈现上
     * 它就是“CLI 会员”一组，按 brainId 先行判定，不跟订阅授权混排。
     */
    fun groupTitle(config: BrainConfig): String? = when {
        config.brainId == "cli-membership" -> "CLI 会员"
        config.kind == BrainKind.MODEL_API -> "模型 API"
        config.kind == BrainKind.AGENT_RUNTIME -> "CLI 会员"
        config.kind == BrainKind.SUBSCRIPTION -> "订阅授权"
        else -> null
    }

    /** 分组顺序：模型 API → CLI 会员 → 订阅授权。 */
    private fun groupRank(groupTitle: String): Int = when (groupTitle) {
        "模型 API" -> 0
        "CLI 会员" -> 1
        else -> 2
    }

    /**
     * 已添加模型列表：全部已配置连接按分组排序，组内沿用大脑列表的
     * 固定顺序（一线在前、通用 MODEL_API 随后按名称排），呈现口径与
     * 大脑列表分家——同一批连接数据，模型页只做模型管理呈现。
     * [isConfigured] 由调用方接 BrainComponents.isBrainConfigured。
     */
    fun buildRows(
        configs: List<BrainConfig>,
        currentBrainId: String?,
        isConfigured: (String) -> Boolean,
        siwcSignedIn: Boolean,
    ): List<ModelListRow> {
        val fixedOrder = listOf(
            "siwc-chatgpt",
            "cloudflare-workers-ai",
            "deepseek",
            "grok",
            "muse-spark",
            "claude",
            "qwen",
            "kimi",
            "grok-membership",
            "gemini",
            "cli-membership",
        )
        val visible = configs.filter { config ->
            groupTitle(config) != null &&
                // 未配 CLI 二进制的 CLI 行不渲染（同大脑列表诚实规则）。
                !(config.brainId == "cli-membership" && config.cliBinary.isNullOrBlank())
        }
        val ordered = visible.sortedWith(
            compareBy<BrainConfig> { groupRank(groupTitle(it) ?: "") }
                .thenBy { config ->
                    val fixed = fixedOrder.indexOf(config.brainId)
                    when {
                        fixed >= 0 -> fixed
                        config.adapterId == BrainLoginRows.GENERIC_ADAPTER_ID -> fixedOrder.size
                        else -> fixedOrder.size + 1
                    }
                }
                .thenBy { it.label },
        )
        return ordered.map { config ->
            val group = groupTitle(config) ?: return@map null
            val row = when {
                config.brainId == "siwc-chatgpt" -> ModelListRow(
                    brainId = config.brainId,
                    groupTitle = group,
                    title = config.label,
                    detailText = config.model.takeIf { it.isNotBlank() },
                    statusText = BrainLoginRows.siwcStatusText(siwcSignedIn),
                    isCurrent = config.brainId == currentBrainId,
                )
                config.brainId == "cli-membership" -> ModelListRow(
                    brainId = config.brainId,
                    groupTitle = group,
                    title = config.label,
                    // 身份是 cliBinary：状态文案里已带二进制名，详情不重复。
                    detailText = null,
                    statusText = BrainLoginRows.cliStatusText(config),
                    isCurrent = config.brainId == currentBrainId,
                )
                config.brainId == "gemini" -> ModelListRow(
                    brainId = config.brainId,
                    groupTitle = group,
                    title = config.label,
                    detailText = null,
                    statusText = BrainLoginRows.GEMINI_LOGIN_ONLY_NOTE,
                    isCurrent = false,
                )
                DeviceCodeConfigs.isDeviceCodeBrainId(config.brainId) -> ModelListRow(
                    brainId = config.brainId,
                    groupTitle = group,
                    title = config.label,
                    detailText = config.model.takeIf { it.isNotBlank() },
                    statusText = if (isConfigured(config.brainId)) "已登录" else "未登录",
                    isCurrent = config.brainId == currentBrainId,
                )
                else -> ModelListRow(
                    brainId = config.brainId,
                    groupTitle = group,
                    title = config.label,
                    detailText = config.model.takeIf { it.isNotBlank() },
                    statusText = if (isConfigured(config.brainId)) "已配置" else "未配置",
                    isCurrent = config.brainId == currentBrainId,
                )
            }
            row
        }.filterNotNull()
    }
}
