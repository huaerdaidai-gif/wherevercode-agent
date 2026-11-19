package wherevercode.agent.brain.generic

/**
 * 免费 token 模型服务商预设（三件套之三，2026-11-18 定案）：
 * 项目主打开源免费——用户自己去服务商注册、领免费 API key、填进
 * App，即可开始干活；key 只存本机 Keystore，这里永远不预填 key。
 *
 * 与 [GenericOpenAiPresets]（常用模型服务商）是两个独立功能、两份
 * 独立数据，绝不合并：常用组是「填服务器地址」，本组是「填注册好
 * 的免费 key」。
 *
 * 名单逐家按官方文档/定价页核验（2026-11-18，证据见项目核验记录
 * preset-verification.md）。入选标准经 2026-11-18 23:26 裁定：
 * 免费 API 真能用即可，不强制有 GET /models 列表接口——没有列表
 * 接口的两家（智谱、Cloudflare）以 [FreeModelListMode.FIXED] 内置
 * 官方免费模型名单入列，名单来源写在各条注记里，后续可随官方目录
 * 修订。剔除（核验不通过，不列）：Cerebras（须先绑支付方式、$5
 * 试用 30 天过期）、Mistral（免费模式不含 API 访问）、Hugging
 * Face（免费用户额度已取消）、GitHub Models（服务已于 2026-07-30
 * 整体退役）。
 *
 * 配置落点：从本组添加的模型连接统一写 extra 标记
 * [EXTRA_TIER] = [TIER_FREE]、[EXTRA_PRESET] = 预设 id，供智能路由
 * 的「免费优先、付费须用户开启」守卫识别档次（见路由实现）。
 */
enum class FreeModelListMode {
    /** 填 key 后调 GET /models 实时拉取，用户勾选。 */
    LIVE,

    /** 无列表接口：用内置名单（官方免费款），用户勾选。 */
    FIXED,
}

data class FreeProviderPreset(
    val id: String,
    val displayName: String,
    /** FIXED 组的名单；LIVE 组为空。 */
    val fixedModels: List<String> = emptyList(),
    val listMode: FreeModelListMode,
    /** 含 `{account_id}` 占位符时，用户须多填一项（仅 Cloudflare）。 */
    val baseUrlTemplate: String,
    /** 领 key 的路径说明（呈现给用户，不含任何真实 key）。 */
    val keyHelp: String,
    val note: String = "",
) {
    val needsAccountId: Boolean get() = baseUrlTemplate.contains("{account_id}")

    /** 把用户填的 Account ID 代入模板；不需要时原样返回。 */
    fun resolveBaseUrl(accountId: String? = null): String {
        val base = baseUrlTemplate.trim().trimEnd('/')
        return if (needsAccountId) {
            base.replace("{account_id}", accountId?.trim().orEmpty())
        } else {
            base
        }
    }
}

object FreeProviderPresets {

    const val EXTRA_TIER: String = "tier"
    const val EXTRA_PRESET: String = "provider_preset"
    const val TIER_FREE: String = "free"

    val PRESETS: List<FreeProviderPreset> = listOf(
        FreeProviderPreset(
            id = "google-ai-studio",
            displayName = "Google AI Studio（Gemini）",
            listMode = FreeModelListMode.LIVE,
            baseUrlTemplate = "https://generativelanguage.googleapis.com/v1beta/openai",
            keyHelp = "用 Google 账号登录 AI Studio 即可领免费 key，不用绑卡。",
            note = "免费层可用 Gemini Flash 类模型；免费限额数字只在登录后可见，官方不保证。",
        ),
        FreeProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            listMode = FreeModelListMode.LIVE,
            baseUrlTemplate = "https://openrouter.ai/api/v1",
            keyHelp = "在 OpenRouter 注册后在控制台建 key，不用绑卡。",
            note = "免费模型是 ID 以 :free 结尾的款；未充值账户 50 次/天，靠智能路由在免费家之间切换可绕开单家上限。",
        ),
        FreeProviderPreset(
            id = "groq",
            displayName = "Groq",
            listMode = FreeModelListMode.LIVE,
            baseUrlTemplate = "https://api.groq.com/openai/v1",
            keyHelp = "在 Groq 控制台注册后建 key，不用绑卡。",
            note = "速度最快；免费限额按模型逐个定（约 1,000 次/天量级），阵列以实时拉取为准。",
        ),
        FreeProviderPreset(
            id = "siliconflow",
            displayName = "硅基流动 SiliconFlow",
            listMode = FreeModelListMode.LIVE,
            baseUrlTemplate = "https://api.siliconflow.cn/v1",
            keyHelp = "手机号注册后在控制台建 key；用免费模型须先完成实名认证（身份证 + 人脸）。",
            note = "国产编码模型免费阵容全；列表含向量/图像等非对话模型，请选对话模型（名字带 GLM、DeepSeek、Qwen 等）。",
        ),
        FreeProviderPreset(
            id = "zhipu",
            displayName = "智谱 GLM",
            listMode = FreeModelListMode.FIXED,
            // 名单 = 官方价目表（2026-11-11 更新）标「免费」的对话/视觉
            // 模型 API 代码；图像/视频生成款（CogView/CogVideo）不入
            // 对话名单。智谱没有 GET /models 接口，故为内置名单。
            fixedModels = listOf(
                "glm-4.7-flash",
                "glm-4-flash-250414",
                "glm-z1-flash",
                "glm-4.6v-flash",
                "glm-4v-flash",
                "glm-4.1v-thinking-flash",
            ),
            baseUrlTemplate = "https://open.bigmodel.cn/api/paas/v4",
            keyHelp = "手机号在智谱开放平台注册后建 key，调用不强制实名、不用绑卡。",
            note = "智谱没有模型列表接口，名单为内置的官方免费款（2026-11 核）；可用性以实际调用为准。",
        ),
        FreeProviderPreset(
            id = "cloudflare",
            displayName = "Cloudflare Workers AI",
            listMode = FreeModelListMode.FIXED,
            // 名单 = Cloudflare 官方模型目录中的常用对话/编码款
            // （@cf/ 前缀为官方模型 ID 格式）。Cloudflare 的 OpenAI
            // 兼容面没有 /models 端点，故为内置名单。
            fixedModels = listOf(
                "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
                "@cf/qwen/qwen2.5-coder-32b-instruct",
                "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b",
                "@cf/openai/gpt-oss-120b",
            ),
            baseUrlTemplate = "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1",
            keyHelp = "在 Cloudflare 控制台建 API Token（Workers AI 权限），并填你的 Account ID；不用绑卡。",
            note = "每天免费 10,000 Neurons（UTC 零点重置）；没有模型列表接口，名单为内置常用款。",
        ),
    )

    fun preset(id: String): FreeProviderPreset? = PRESETS.firstOrNull { it.id == id }

    /** 按 baseUrl 反查预设（尾斜杠宽容；Cloudflare 模板按前缀匹配）。 */
    fun presetForBaseUrl(baseUrl: String): FreeProviderPreset? {
        val wanted = baseUrl.trim().trimEnd('/')
        return PRESETS.firstOrNull { preset ->
            val template = preset.baseUrlTemplate.trimEnd('/')
            if (preset.needsAccountId) {
                val prefix = template.substringBefore("{account_id}")
                val suffix = template.substringAfter("{account_id}")
                wanted.startsWith(prefix) && wanted.endsWith(suffix) && wanted.length > prefix.length + suffix.length
            } else {
                template == wanted
            }
        }
    }
}
