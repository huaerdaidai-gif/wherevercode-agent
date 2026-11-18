package wherevercode.agent.ui

import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigValidator
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.devicecode.DeviceCodeConfigs
import wherevercode.agent.core.agent.AgentLoginState

/**
 * 设置页“大脑”分节的行文案与切换判定（V5 C3-C）。
 *
 * 纯逻辑，无 Android 依赖，可单测；MainActivity 只调它，不在
 * Activity 里散拼文案。状态文案一律取真实值（配置、登录态、上次
 * 验证摘要），绝不写死“已连接”。
 *
 * 诚实规则（审计 §3.3/§4.2）：
 * - CLI 大脑的身份是 [BrainConfig.cliBinary]，不是 `model` 字段
 *   （种子把二进制名填进 `model` 只是为了满足“模型不能为空”
 *   的校验，适配器从不把 `model` 发给 CLI）。
 * - 额度耗尽不等于可用：loginState 是 LOGGED_IN 但上次验证摘要
 *   写了额度不足时，行显示“已登录，但额度不足”，且不可切换。
 * - CLI 验证说明只写动作与代价（点“验证一次”确认登录，会消耗
 *   一次会员调用），不写超时与缓冲细节；文案不许出现“沙箱内
 *   未发现”、不许写“实时流式”。
 * - 通用 MODEL_API 大脑（adapterId [GENERIC_ADAPTER_ID]）的推理
 *   已接线（GenericOpenAiBrainAdapter 已注册）：已配置的通用大脑
 *   可以点按设为全局大脑（maintainer 2026-11-11 决定放开切换），也可以
 *   在 Agent 页选同名 Agent 对话。第二阶段起支持工具调用：切为
 *   全局大脑后可执行任务（改文件、跑命令），写操作会先请用户确认；
 *   工具调用尚未在真机上验证，切换提示与状态行都会如实写明这一点，
 *   不把“未验证”说成“已验证”。
 * - 未配置 CLI 时（cliBinary 为空）大脑列表里不出现 CLI 行、CLI
 *   整节隐藏，不拿“未发现”占设置页（沙箱里没有 CLI 是正常态，
 *   不是要报错的事）。
 */
object BrainLoginRows {

    /**
     * 通用 MODEL_API 大脑的 adapterId：GenericOpenAiBrainAdapter 已以
     * 它注册，引擎经 brainIdOverride 可调用；已配置的通用大脑可以设
     * 为全局大脑（maintainer 2026-11-11 决定放开切换），第二阶段起支持
     * 工具调用（GenericOpenAiBrainAdapter 的能力声明，toolCalling=true；
     * 尚未在真机上验证，toolCallingVerified 仍为空）。列表行按已配置/未配置如实写状态，不再无条件写“已配置”。
     */
    const val GENERIC_ADAPTER_ID: String = "model-api-generic"

    /** CLI“验证一次”按钮文案：必须写明会消耗一次会员调用。 */
    const val CLI_VERIFY_BUTTON_TEXT: String = "验证一次（会消耗一次会员调用）"

    /**
     * CLI 验证说明：只写动作，不写超时与缓冲细节（“120 秒”“600 秒”
     * “非逐行实时”都是实现细节，不出现在用户可见处）；代价披露在
     * [CLI_VERIFY_BUTTON_TEXT] 上。
     */
    const val CLI_VERIFY_NOTE: String = "点“验证一次”确认该 CLI 已登录。"

    /** SIWC 登录进行中文案：登录中不要退出设置页，可取消。 */
    const val SIWC_SIGNING_IN_NOTE: String =
        "正在登录 ChatGPT：登录中不要退出设置页；点“取消登录”可随时停止。"

    /** 设备码登录进行中文案：登录中不要退出设置页，可取消。 */
    const val DEVICE_CODE_SIGNING_IN_NOTE: String =
        "正在等你在浏览器里完成授权：登录中不要退出设置页；点“取消登录”可随时停止。"

    /** 设备码登录弹框标题。 */
    const val DEVICE_CODE_LOGIN_DIALOG_TITLE: String = "会员登录（设备码）"

    /** Gemini 仅登录行的说明（登录不能当大脑用推理）。 */
    const val GEMINI_LOGIN_ONLY_NOTE: String =
        "仅登录，不能当大脑用推理：会员 token 调不了 Gemini API；" +
            "稳妥路线是 API Key（AI Studio）或 Vertex AI。"

    /**
     * 设备码登录弹框正文：验证码 + 验证地址 + 合规/风险行。只拼
     * 已核验的公开信息，不写 token、不写密钥。`displayName`、
     * `verificationUri`、`userCode`、`complianceNote`、
     * `experimentalNote` 均来自调用方（厂商配置表）。
     */
    fun deviceCodeLoginDialogText(
        displayName: String,
        verificationUriComplete: String,
        userCode: String,
        complianceNote: String,
        experimentalNote: String,
    ): String = buildString {
        append("在浏览器打开下面的地址，登录你的 $displayName 账号并输入验证码，完成授权：\n\n")
        append("验证码：$userCode\n")
        append("地址：$verificationUriComplete\n")
        if (experimentalNote.isNotBlank()) append("\n$experimentalNote")
        if (complianceNote.isNotBlank()) append("\n$complianceNote")
        append("\n授权完成后 App 会自动继续，不用再点什么；点“取消登录”可随时停止。")
    }

    /** 设备码大脑“编辑模型…”的弹框提示：模型名以厂商模型目录为准，不写死。 */
    const val DEVICE_CODE_MODEL_EDIT_HINT: String =
        "填厂商模型目录里的模型名（不要凭印象编）；留空表示未选择，未选择时不能切换为当前大脑。"

    /** 通用 MODEL_API 保存后的诚实说明：已接线，可设为全局大脑执行任务，
     * 也可以去 Agent 页添加同名 Agent 对话；写操作会先请用户确认。
     * 工具调用尚未在真机上验证。 */
    const val GENERIC_SAVED_NOTE: String =
        "通用模型 API 已接线：可在大脑列表里点按设为当前大脑，可执行改文件、跑命令等任务" +
            "（写操作会先请你确认），或去 Agent 页添加同名 Agent 对话。" +
            "工具调用尚未在真机上验证。"

    /** 大脑列表一行（MainActivity 据此渲染，不在 Activity 里拼文案）。 */
    data class BrainListRow(
        val brainId: String,
        val title: String,
        val typeLabel: String,
        val statusText: String,
        /** 当前模型；CLI 大脑为 null（身份是 cliBinary，不是 model）。 */
        val modelText: String?,
        val isCurrent: Boolean,
        val canSwitch: Boolean,
        /** 不可切换时的原因；可切换时是简短动作提示。 */
        val switchHint: String,
    )

    /** 切换判定：只切已配置的脑，未配置的给原因、不静默。 */
    sealed interface SwitchDecision {
        data object Allowed : SwitchDecision
        data class Blocked(val reason: String) : SwitchDecision
    }

    fun switchDecision(row: BrainListRow): SwitchDecision =
        if (row.canSwitch) SwitchDecision.Allowed
        else SwitchDecision.Blocked(row.switchHint)

    /** 登陆类型名（复用 BrainKind，不另发明类型）。平台/云端 Agent
     *  不是大脑，设置页大脑列表里不会出现它们；这里只补穷举分支，
     *  文案与“添加 Agent”表单类型名一致。 */
    fun kindLabel(kind: BrainKind): String = when (kind) {
        BrainKind.MODEL_API -> "模型 API"
        BrainKind.SUBSCRIPTION -> "订阅授权"
        BrainKind.AGENT_RUNTIME -> "本机 CLI"
        BrainKind.PLATFORM_AGENT -> "平台 Agent"
        BrainKind.CLOUD_AGENT -> "云端 Agent"
    }

    /**
     * 通用 MODEL_API 大脑 id：名称 slug 化（"model-api-<slug>"）；
     * 空白名称回 "model-api-custom"。第二阶段注册同名适配器后，
     * 已存的通用配置无需迁移即可接线。
     */
    fun genericBrainId(label: String): String {
        // 只留 ASCII 小写字母/数字/连字符：isLetterOrDigit() 对汉字也
        // 返回 true，旧写法给中文名算出的大脑 id 含中文；与
        // AgentBrainWiring.genericBrainId、MainActivity.slugAgentId
        // 同规则，三边必须保持一致。
        val slug = label.trim().lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(40)
            .trim('-')
        return "model-api-" + slug.ifBlank { "custom" }
    }

    /** 通用大脑的 Keystore 引用名：只存引用，密钥本身进 Keystore。 */
    fun genericAuthRef(brainId: String): String = "model-api:$brainId"

    /**
     * 额度是否耗尽：只看状态位不够——LOGGED_IN 且上次验证摘要
     * 写了额度不足时，脑子在但跑不动。
     */
    fun isQuotaExhausted(config: BrainConfig): Boolean =
        config.loginState == AgentLoginState.LOGGED_IN &&
            (config.lastTestSummary?.contains("额度不足") == true ||
                config.lastTestSummary?.contains("quota", ignoreCase = true) == true)

    /** CLI 会员额度整节是否显示：只有配了 CLI 二进制（Agent 表单
     * 写入）时才出现；没配时整节隐藏，不拿“未配置/未发现”占设置
     * 页。沙箱里没有 CLI 是正常态，不报错、不占位。 */
    fun shouldShowCliSection(config: BrainConfig?): Boolean =
        config != null && !config.cliBinary.isNullOrBlank()

    /** CLI 大脑行状态：身份是 cliBinary；未验证时如实写“未知”。
     * 未选 CLI 时只写“未选择 CLI”，不提沙箱（该行此时本就不渲染，
     * 见 [buildRows]）。 */
    fun cliStatusText(config: BrainConfig): String {
        val binary = config.cliBinary?.takeIf { it.isNotBlank() }
            ?: return "未选择 CLI"
        return when (config.loginState) {
            AgentLoginState.LOGGED_IN ->
                if (isQuotaExhausted(config)) "已登录，但额度不足（$binary）"
                else "已登录（已验证，$binary）"
            AgentLoginState.NOT_LOGGED_IN -> "未登录（$binary 未登录）"
            else -> "登录状态未知（$binary，需点“验证一次”确认）"
        }
    }

    /** SIWC status is authentication only; the model is displayed in its own field. */
    fun siwcStatusText(signedIn: Boolean): String =
        if (signedIn) "认证通过" else "未连接"

    /** SIWC 当前模型行。 */
    fun siwcCurrentModelText(model: String?): String =
        if (model.isNullOrBlank()) "当前模型：未选择" else "当前模型：$model"

    /** Avoid repeating the selected model next to the brain-list row. */
    fun siwcModelPickerText(model: String?): String =
        if (model.isNullOrBlank()) "尚未选择模型，请刷新目录后选择。"
        else "可以在这里更换当前模型。"

    /** SIWC 目录刷新结果行（失败/空目录都诚实显示，不回退写死模型）。 */
    fun siwcModelsText(models: List<String>): String =
        if (models.isEmpty()) "未取到模型目录：请重试刷新。"
        else "可用模型：" + models.joinToString("、")

    /** SIWC 选模型写回：只换 model，其余字段原样（与 modelStore.saveModel 同路）。 */
    fun withSiwcModel(config: BrainConfig, model: String): BrainConfig =
        config.copy(model = model)

    /**
     * 通用 MODEL_API 表单校验：复用 [BrainConfigValidator]（https-only、
     * 本地模型例外、模型不能为空），再加“无已存 Key 时 Key 必填”。
     * 返回空 = 可保存；不贴真实 Key，只返回错误文案。
     */
    fun validateGenericForm(
        label: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        hasStoredKey: Boolean,
    ): List<String> {
        val errors = mutableListOf<String>()
        if (label.isBlank()) errors += "请填写大脑名称"
        val brainId = genericBrainId(label)
        val config = BrainConfig(
            brainId = brainId,
            kind = BrainKind.MODEL_API,
            adapterId = GENERIC_ADAPTER_ID,
            label = label,
            baseUrl = baseUrl.takeIf { it.isNotBlank() },
            model = model,
            authRef = genericAuthRef(brainId),
        )
        errors += BrainConfigValidator.validate(config)
        // 本机模型例外（2026-11-12）：地址是 127.0.0.1/localhost 时可无
        // Key——引擎层与 isBrainConfigured 早已支持（见
        // GenericOpenAiBrainAdapter/GenericOpenAiApiClient），此前只有
        // 表单挡着。非本机地址仍要求 Key。
        val isLocal = BrainConfigValidator.isLocalModelUrl(baseUrl)
        if (apiKey.isBlank() && !hasStoredKey && !isLocal) errors += "请填写 API Key（只存本机 Keystore）"
        return errors
    }

    /**
     * 是否通用 MODEL_API 大脑（编辑走通用表单）。一线大脑（ChatGPT /
     * Cloudflare / DeepSeek / Grok / Muse Spark / Claude）与 CLI 大脑
     * 不在此列：它们各有自己的配置节（编辑时定位到对应小节）；设备码
     * 会员大脑（Qwen / Kimi / Grok 会员）与 Gemini 也不在此列：模型在
     * 管理对话框里改，登录在管理对话框里做。
     */
    fun isGenericBrain(config: BrainConfig): Boolean =
        config.adapterId == GENERIC_ADAPTER_ID

    /**
     * 每行大脑的管理动作（设置页大脑行右侧“···”；长按与点“···”
     * 是同一入口，2026-11-11 maintainer：管理动作必须有可见入口，不藏
     * 手势）。2026-11-12 起全部类型都能删，不再只开放通用大脑：
     * 删一线大脑不会再“复活”——BrainBootstrap 只在墓碑为空时播种，
     * 删后列表末尾有“＋ 重新添加”可恢复。按登陆类型给动作：
     * - 设备码会员大脑（Qwen / Kimi / Grok 会员）：“登录…”+
     *   “编辑模型…”+“退出登录”+“删除…”（登录走设备码流，模型在
     *   对话框里填厂商模型目录里的名字）；
     * - Gemini（仅登录）：只有“删除…”（还没法登录：需开发者先注册
     *   V5 自有的 Desktop OAuth client；登录了也不能当大脑）；
     * - 订阅（SIWC）：“退出登录”+“删除…”；
     * - 模型 API（通用 / DeepSeek / Grok / Muse Spark / Claude /
     *   Cloudflare）：“编辑…”+“删除…”；
     * - 本机 CLI 与其他：“删除…”（配置来自 Agent 表单，无单独编辑面）。
     * 点按仍是切换，不在管理单里。
     */
    fun manageActions(config: BrainConfig): List<String> = when {
        config.brainId == "gemini" -> listOf("删除…")
        DeviceCodeConfigs.isDeviceCodeBrainId(config.brainId) ->
            listOf("登录…", "编辑模型…", "退出登录", "删除…")
        config.kind == BrainKind.SUBSCRIPTION -> listOf("退出登录", "删除…")
        config.kind == BrainKind.MODEL_API -> listOf("编辑…", "删除…")
        else -> listOf("删除…")
    }

    /** 长按管理对话框的详情行：模型与服务地址，只写已填的真实值。 */
    fun brainManageDetail(config: BrainConfig): String = buildString {
        append("模型：${config.model.takeIf { it.isNotBlank() } ?: "未填"}\n")
        append("服务地址：${config.baseUrl?.takeIf { it.isNotBlank() } ?: "未填"}")
    }

    /**
     * 删除二次确认的文案（纯函数，单测覆盖）。[isCurrent] 是删的
     * 是否当前大脑；[agentNames] 是正用这个大脑的 Agent 名（调用方按
     * authRef 匹配算出）。三件事必须写清：配置与 Key 一起删；删当前
     * 脑后当前大脑清空、不会偷偷切到别的脑；用它的 Agent 发消息时会
     * 提示未接入（Agent 记录本身不动，重新添加大脑或改选 Agent 即可
     * 恢复）。
     */
    fun deleteWarning(
        config: BrainConfig,
        isCurrent: Boolean,
        agentNames: List<String>,
    ): String = buildString {
        if (config.authRef.isNullOrBlank()) {
            append("删除大脑“${config.label}”？配置会彻底删除，删后无法恢复。\n")
        } else {
            append("删除大脑“${config.label}”？配置与它存在本机 Keystore 里的 Key 会一起删除，删后无法恢复。\n")
        }
        if (isCurrent) {
            append("它是当前大脑：删除后当前大脑清空，不会自动切到别的脑，请在列表里另选一个。\n")
        }
        if (agentNames.isNotEmpty()) {
            append("以下 Agent 正用这个大脑：${agentNames.joinToString("、")}。删除后它们发消息时会提示未接入（Agent 记录本身不动，重新添加大脑或改选 Agent 即可恢复）。")
        }
    }

    /**
     * 统一大脑列表：一线大脑按固定顺序（ChatGPT / Cloudflare /
     * DeepSeek / Grok / Muse Spark / Claude / Qwen 会员 / Kimi Code
     * 订阅 / Grok 会员（实验性）/ Gemini（仅登录）/ CLI），通用 MODEL_API
     * 大脑随后按名称排，其余最后。
     * [isConfigured] 由调用方接 BrainComponents.isBrainConfigured。
     * 未配 CLI 二进制的 CLI 行直接跳过（不渲染“未发现”行）；
     * 通用大脑行与其它脑同口径：已配置可切、未配置不可切，状态按
     * 实写，不再无条件写“已配置”。
     * Gemini 行永远不可切换（仅登录，不能当大脑用推理）；设备码
     * 会员大脑行：已登录且填了模型才可切，否则提示去“···”里登录
     * 或填模型。
     */
    fun buildRows(
        configs: List<BrainConfig>,
        currentBrainId: String?,
        isConfigured: (String) -> Boolean,
        siwcSignedIn: Boolean,
    ): List<BrainListRow> {
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
        val visible = configs.filterNot { config ->
            // 未配 CLI 二进制的 CLI 行不渲染：沙箱里没有 CLI 是正常态，
            // 不占一行写“未发现”（设置清理 2，2026-11-11）。
            config.brainId == "cli-membership" && config.cliBinary.isNullOrBlank()
        }
        val ordered = visible.sortedWith(
            compareBy<BrainConfig> { config ->
                val fixed = fixedOrder.indexOf(config.brainId)
                when {
                    fixed >= 0 -> fixed
                    config.adapterId == GENERIC_ADAPTER_ID -> fixedOrder.size
                    else -> fixedOrder.size + 1
                }
            }.thenBy { it.label },
        )
        return ordered.map { config ->
            val isCurrent = config.brainId == currentBrainId
            when {
                config.brainId == "siwc-chatgpt" -> BrainListRow(
                    brainId = config.brainId,
                    title = config.label,
                    typeLabel = kindLabel(config.kind),
                    statusText = siwcStatusText(siwcSignedIn),
                    modelText = config.model.takeIf { it.isNotBlank() },
                    isCurrent = isCurrent,
                    canSwitch = siwcSignedIn,
                    switchHint = when {
                        isCurrent -> "当前正在使用"
                        siwcSignedIn -> "设为当前大脑"
                        else -> "请先登录 ChatGPT 再切换"
                    },
                )
                config.brainId == "cli-membership" -> {
                    val configured = isConfigured(config.brainId)
                    val quota = isQuotaExhausted(config)
                    BrainListRow(
                        brainId = config.brainId,
                        title = config.label,
                        typeLabel = kindLabel(config.kind),
                        statusText = cliStatusText(config),
                        // 身份是 cliBinary：model 字段只填了二进制名做种子，
                        // 不把它当成模型展示。
                        modelText = null,
                        isCurrent = isCurrent,
                        canSwitch = configured && !quota,
                        switchHint = when {
                            isCurrent -> "当前正在使用"
                            quota -> "已登录，但额度不足，暂不可切换；额度恢复后可切换"
                            config.loginState == AgentLoginState.NOT_LOGGED_IN ->
                                "该 CLI 未登录：请先在 CLI 里完成登录，再点“验证一次”"
                            config.cliBinary.isNullOrBlank() -> "未选择 CLI：请先在 Agent 页添加一个 CLI Agent"
                            !configured -> "登录状态未知：请先点“验证一次”确认"
                            else -> "设为当前大脑"
                        },
                    )
                }
                config.brainId == "gemini" -> BrainListRow(
                    brainId = config.brainId,
                    title = config.label,
                    typeLabel = kindLabel(config.kind),
                    statusText = GEMINI_LOGIN_ONLY_NOTE,
                    modelText = null,
                    isCurrent = false,
                    // 仅登录：永远不可切换为当前大脑。
                    canSwitch = false,
                    switchHint = "Gemini 会员登录不能当大脑用推理；" +
                        "登录入口待开发者注册 V5 自有的 Desktop OAuth client 后开放",
                )
                DeviceCodeConfigs.isDeviceCodeBrainId(config.brainId) -> {
                    val configured = isConfigured(config.brainId)
                    BrainListRow(
                        brainId = config.brainId,
                        title = config.label,
                        typeLabel = kindLabel(config.kind),
                        statusText = if (configured) "已登录" else "未登录",
                        modelText = config.model.takeIf { it.isNotBlank() },
                        isCurrent = isCurrent,
                        canSwitch = configured,
                        switchHint = when {
                            isCurrent -> "当前正在使用"
                            configured -> "设为当前大脑"
                            else -> "请先在“···”里点“登录…”完成设备码登录，并填好模型"
                        },
                    )
                }
                config.adapterId == GENERIC_ADAPTER_ID -> {
                    val configured = isConfigured(config.brainId)
                    BrainListRow(
                        brainId = config.brainId,
                        title = config.label,
                        typeLabel = kindLabel(config.kind),
                        statusText = if (configured) "已配置" else "未配置",
                        modelText = config.model.takeIf { it.isNotBlank() },
                        isCurrent = isCurrent,
                        // 通用大脑已配置即可设为全局大脑（maintainer 2026-11-11
                        // 决定放开切换），与其它脑同口径；第二阶段起支
                        // 持工具调用（切换提示见 MainActivity.switchBrain，
                        // 状态行见 refreshBrainStatus）。
                        canSwitch = configured,
                        switchHint = when {
                            isCurrent -> "当前正在使用"
                            configured -> "设为当前大脑"
                            else -> "请先在下方配置并保存"
                        },
                    )
                }
                else -> {
                    val configured = isConfigured(config.brainId)
                    BrainListRow(
                        brainId = config.brainId,
                        title = config.label,
                        typeLabel = kindLabel(config.kind),
                        statusText = if (configured) "已配置" else "未配置",
                        modelText = config.model.takeIf { it.isNotBlank() },
                        isCurrent = isCurrent,
                        canSwitch = configured,
                        switchHint = when {
                            isCurrent -> "当前正在使用"
                            configured -> "设为当前大脑"
                            else -> "请先在下方配置并保存"
                        },
                    )
                }
            }
        }
    }
}
