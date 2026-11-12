package wherevercode.agent.core.agent

import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.generic.GenericOpenAiBrainAdapter

/**
 * 添加 Agent → 大脑接线（V5 紧急开发，2026-11-11）。
 *
 * 根因：添加 Agent 表单只存 AgentRecord，从不建 BrainConfig；聊天路由
 * （AgentChatRouter.findBrainConfig）却必须凭 BrainConfig 才能把消息
 * 发给该 Agent——于是表单上“已保存”，聊天里永远“未接入”。
 * 本对象是接线的纯决策部分（无 Android、无 IO，可单测）：给定一条
 * 表单刚建好的记录与现存大脑配置，算出该配的通用大脑配置与接好线
 * 的记录；存配置、注册配置、写 Keystore 由调用方（MainActivity）做。
 *
 * 规则：
 * - MODEL_API（非 Manus）：配一个通用大脑（adapterId
 *   "model-api-generic"，与设置页 saveGenericBrain 同路），名字、
 *   地址、模型、密钥引用都从这条记录来；brainId 碰撞安全。
 * - Manus：走任务桥接（ManusChatBridge），不配大脑。
 * - CLI / 订阅：走各自大脑（CLI 会员大脑 / SIWC），不在此配。
 *
 * 密钥引用名必须是 "model-api:<brainId>"：通用大脑的
 * KeystoreGenericOpenAiCredentialStore 只认这个前缀的引用名
 * （loadApiKey 对其他前缀一律返回 null），表单上用户随手填的
 * "key:xxx" 在通用大脑这里是读不到 key 的——接线时必须改写。
 */
object AgentBrainWiring {

    /** 通用大脑的 Keystore 引用名前缀（与 KeystoreGenericOpenAiCredentialStore 同值）。 */
    const val AUTH_REF_PREFIX: String = "model-api:"

    /** 该记录是否走 Manus 任务桥接（不配通用大脑）。 */
    fun isManus(record: AgentRecord): Boolean = ManusTaskAdapter.isManusRecord(record)

    /** 该记录是不是本机模型（地址 127.0.0.1/localhost，可无 Key 聊天）。 */
    fun isLocalModel(record: AgentRecord): Boolean =
        record.baseUrl?.let { AgentRecord.isLocalModelUrl(it) } == true

    /** 该记录要能聊天是否必须有 Key：非本机模型的 MODEL_API（非 Manus）必须有。 */
    fun requiresKey(record: AgentRecord): Boolean =
        record.brainKind == BrainKind.MODEL_API && !isManus(record) && !isLocalModel(record)

    /**
     * 为记录配通用大脑配置；不需要/不能配时返回 null（Manus、
     * CLI、订阅见上）。MODEL_API 要求地址与模型非空——表单校验
     * （AgentRecord.validate）已保证，空值时这里返回 null 而不造
     * 一个坏配置。
     */
    fun brainConfigFor(record: AgentRecord, existingConfigs: List<BrainConfig>): BrainConfig? {
        if (record.brainKind != BrainKind.MODEL_API) return null
        if (isManus(record)) return null
        val baseUrl = record.baseUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val model = record.model?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val brainId = uniqueBrainId(record.displayName, existingConfigs, baseUrl, model)
        return BrainConfig(
            brainId = brainId,
            kind = BrainKind.MODEL_API,
            adapterId = GenericOpenAiBrainAdapter.ADAPTER_ID,
            label = record.displayName,
            baseUrl = baseUrl,
            model = model,
            authRef = genericAuthRef(brainId),
            fallbackBrainIds = emptyList(),
        )
    }

    /**
     * 接好线后的记录：适配器指到通用大脑，密钥引用指到通用大脑的
     * Key 引用名——聊天路由按这两项找配置、找 Key，缺一不可。
     */
    fun wiredRecord(record: AgentRecord, config: BrainConfig): AgentRecord =
        record.copy(adapterId = config.adapterId, authRef = config.authRef)

    /**
     * 通用大脑 id：名字 slug 化（"model-api-<slug>"），与
     * BrainLoginRows.genericBrainId 同算法——两边必须保持一致，
     * 否则设置页与添加 Agent 页会给同一个名字算出不同的大脑 id。
     * （AgentBrainWiringTest 钉住两者对同一批名字返回同一结果。）
     */
    fun genericBrainId(label: String): String {
        // 只留 ASCII 小写字母/数字/连字符：isLetterOrDigit() 对汉字也
        // 返回 true，旧写法给中文名算出的大脑 id 含中文；与
        // BrainLoginRows.genericBrainId 同算法，两边必须保持一致。
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
     * 碰撞安全的 brainId：同名配置不存在 → 直接用；已存在且端点
     * （地址+模型）相同 → 复用（编辑同一 Agent 不另起大脑）；已存在
     * 且端点不同 → 加 -2、-3……后缀，绝不覆盖别人的大脑。中文名
     * slug 为空时回退 custom，同样走后缀规则。
     */
    fun uniqueBrainId(
        label: String,
        existingConfigs: List<BrainConfig>,
        baseUrl: String,
        model: String,
    ): String {
        val base = genericBrainId(label)
        val existing = existingConfigs.firstOrNull { it.brainId == base }
            ?: return base
        if (sameEndpoint(existing, baseUrl, model)) return base
        var suffix = 2
        while (true) {
            val candidate = "$base-$suffix"
            val clash = existingConfigs.firstOrNull { it.brainId == candidate }
                ?: return candidate
            if (sameEndpoint(clash, baseUrl, model)) return candidate
            suffix += 1
        }
    }

    private fun sameEndpoint(config: BrainConfig, baseUrl: String, model: String): Boolean =
        config.baseUrl?.trim()?.trimEnd('/') == baseUrl.trim().trimEnd('/') &&
            config.model.trim() == model.trim()
}
