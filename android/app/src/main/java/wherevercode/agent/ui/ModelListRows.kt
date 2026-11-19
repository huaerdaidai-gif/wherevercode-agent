package wherevercode.agent.ui

import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.devicecode.DeviceCodeConfigs
import wherevercode.agent.brain.generic.CustomFreeProviders
import wherevercode.agent.brain.generic.FreeProviderPresets
import wherevercode.agent.brain.generic.GenericOpenAiPresets

/**
 * 模型页“已添加模型”的行数据（纯逻辑，无 Android 依赖，可单测）。
 *
 * 与设置页大脑列表彻底分家（2026-11-19，定版：大脑已砍开放一边，
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
        /** 自定义服务商（用户本机自加、不在内置名单内）落成的条目：列表与详情都要标注。 */
        val isCustomProvider: Boolean = false,
    )

    /** 列表行里自定义条目的标注文案（本页自己的常量，不借大脑侧字符串）。 */
    const val CUSTOM_TAG: String = "自定义"

    /**
     * 来源标记的 extra 键（Bug② 定版，B2）：单独记来源，与服务商
     * 预设 id（provider_preset）分开写、互不覆盖——旧写法把
     * "custom-manual" 塞进 provider_preset，与自定义服务商的
     * custom-<slug> 命名空间混在一起，已弃用。
     */
    const val EXTRA_MODEL_ORIGIN: String = "model_origin"

    /** 来源值：模型页手工表单新建的名单外条目——显示「自定义」标。 */
    const val ORIGIN_MANUAL: String = "manual"

    /** 来源值：预设落成（免费预设流程落配置，或预设预填表单保存）。 */
    const val ORIGIN_PRESET: String = "preset"

    /** 来源值：设备发现（扫描已配对设备上的模型端点后勾选添加）。 */
    const val ORIGIN_DEVICE_DISCOVERED: String = "device-discovered"

    /** 模型页保存表单名称为空时本页自己的报错（不借大脑侧「请填写大脑名称」）。 */
    const val NAME_REQUIRED_ERROR: String = "请填写模型名称"

    /** 共用校验器里的大脑口径名称错误原文（只用于在本页侧替换，不改其本体）。 */
    private const val BRAIN_NAME_REQUIRED_ERROR: String = "请填写大脑名称"

    /**
     * 这条连接是不是自定义服务商落成的，两个口径同时认：
     * - 自定义预设落配置时 extra["provider_preset"] 写的是 "custom-…"
     *   开头的预设 id（见 [CustomFreeProviders.ID_PREFIX] 与落配置
     *   路径）；内置预设写的是自家 id，不带此前缀。
     * - 手工表单新建的名单外条目写独立来源键
     *   extra["model_origin"] = "manual"（Bug② 定版，B2）。
     * 没有来源键的旧条目不回填、不显示标记；预设与设备发现落成的
     * 条目来源键不是 manual，不显示标记。
     */
    fun isCustomProvider(config: BrainConfig): Boolean =
        config.extra[FreeProviderPresets.EXTRA_PRESET]
            ?.startsWith(CustomFreeProviders.ID_PREFIX) == true ||
            config.extra[EXTRA_MODEL_ORIGIN] == ORIGIN_MANUAL

    /**
     * 模型页手工表单新建条目要写入的来源 extra：表单本身不记来源，
     * 保存时按服务地址反查内置名单区分。常用预设点选只是把名称与
     * 服务地址预填进同一张表单（见 MainActivity 的常用服务商选择
     * 器），地址命中任一内置名单（常用组 [GenericOpenAiPresets] /
     * 免费组 [FreeProviderPresets]）就是名单内服务商，写
     * [ORIGIN_PRESET]、不显示「自定义」；两边都查不到 = 名单外
     * 手打条目，写 [ORIGIN_MANUAL] 让 [isCustomProvider] 认得出、
     * 与内置条目分开呈现。免费预设与设备发现的独立落配置流程
     * 各自写明来源、不经本函数；编辑已有条目走 copy 保留原
     * extra，也不经本函数（编辑不覆盖原标记）。
     */
    fun newFormEntryExtra(baseUrl: String?): Map<String, String> {
        val url = baseUrl.orEmpty()
        val inBuiltinLists = GenericOpenAiPresets.presetForBaseUrl(url) != null ||
            FreeProviderPresets.presetForBaseUrl(url) != null
        return mapOf(
            EXTRA_MODEL_ORIGIN to if (inBuiltinLists) ORIGIN_PRESET else ORIGIN_MANUAL,
        )
    }

    /**
     * 模型页管理单的详情：与大脑侧详情同底（模型 + 服务地址，只读
     * 复用 [BrainLoginRows.brainManageDetail]），自定义条目多一行
     * 来源标注——内置名单是逐家核验过的，自定义是用户自加的，
     * 呈现上必须分开（同预设名单的标注纪律）。
     */
    fun manageDetail(config: BrainConfig): String = buildString {
        append(BrainLoginRows.brainManageDetail(config))
        if (isCustomProvider(config)) {
            append("\n来源：${CUSTOM_TAG}服务商（本机自加，不在内置名单内）")
        }
    }

    /** 模型页删除确认标题（本页自己的口径，全程说「模型」）。 */
    fun deleteTitle(config: BrainConfig): String = "删除模型“${config.label}”？"

    /**
     * 模型页删除二次确认的文案（纯函数，单测覆盖；与大脑侧
     * [BrainLoginRows.deleteWarning] 分家——那张确认框说的是删大脑，
     * 本页删的是模型连接，必须全程「模型」口径）。后果写清三件：
     * 配置与 Key 一起删、删后无法恢复；删的若是当前在用的模型，
     * 当前模型清空、不会自动切到别的模型；正用它的 Agent 之后发
     * 消息会提示未接入。并且明确：只删这一条模型配置，不删
     * Agent、不删设备。
     */
    fun deleteWarning(
        config: BrainConfig,
        isCurrent: Boolean,
        agentNames: List<String>,
    ): String = buildString {
        if (config.authRef.isNullOrBlank()) {
            append("删除模型“${config.label}”？这条模型配置会彻底删除，删后无法恢复。\n")
        } else {
            append("删除模型“${config.label}”？这条模型配置与它存在本机 Keystore 里的 Key 会一起删除，删后无法恢复。\n")
        }
        if (isCurrent) {
            append("它是当前在用的模型：删除后当前模型清空，不会自动切到别的模型，请在列表里另选一个。\n")
        }
        if (agentNames.isNotEmpty()) {
            append("以下 Agent 正用这个模型：${agentNames.joinToString("、")}。删除后它们发消息时会提示未接入（Agent 记录本身不动，重新添加这个模型或改选 Agent 即可恢复）。\n")
        }
        append("删除只影响这一条模型配置，不会删除 Agent，也不会删除设备。")
    }

    /**
     * 模型页保存表单的校验文案：共用校验器返回的错误里，大脑口径的
     * 名称错误换成本页自己的 [NAME_REQUIRED_ERROR]，其余原样。
     * 只在本页侧替换呈现，不改 BrainLoginRows 的原字符串。
     */
    fun formErrors(errors: List<String>): List<String> =
        errors.map { if (it == BRAIN_NAME_REQUIRED_ERROR) NAME_REQUIRED_ERROR else it }

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
            "muse-spark",
            "grok",
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
                    statusText = if (isConfigured(config.brainId)) "已填 Key" else "未填 Key",
                    isCurrent = config.brainId == currentBrainId,
                )
            }
            row.copy(isCustomProvider = isCustomProvider(config))
        }.filterNotNull()
    }
}
