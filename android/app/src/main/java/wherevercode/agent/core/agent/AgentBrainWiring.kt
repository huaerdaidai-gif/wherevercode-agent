package wherevercode.agent.core.agent

import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigStore
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
     * 差异 18 方案 A（B2）：本轮发送用的内存合成配置；不需要合成
     * 时返回 null（调用方照旧走落盘配置）。
     *
     * 添加「云端模型 API」Agent 不再落盘 BrainConfig（旧做法的病灶：
     * 删 Agent 后模型条目残留）；发送时用记录自带的 baseUrl / model /
     * authRef 现场组装一份只活在本轮的配置，由调用方注册进引擎
     * 注册表（纯内存），轮次结束即注销。
     *
     * 已有落盘配置能服务该 Agent 时返回 null：[brainConfigFor] 的
     * 复用规则算出的 brainId 与一条落盘配置同 id 且端点一致，说明
     * 该 Agent 本来就有落盘配置（修复前建的 Agent，或用户在设置里
     * 建了同名同端点的大脑）——发送路径照旧用它，行为不变。
     *
     * 密钥引用以记录自带的为准：Key 是保存 Agent 时按记录的
     * authRef 写进 Keystore 的；发送时按名字重算 brainId 可能因
     * 后来新增的同名大脑多出后缀，与存 Key 时的引用名对不上，
     * 故合成配置的 authRef 优先用记录自带的（带 [AUTH_REF_PREFIX]
     * 前缀时；通用大脑的 Keystore 只认此前缀）。
     */
    fun sessionConfigFor(record: AgentRecord, existingConfigs: List<BrainConfig>): BrainConfig? {
        if (record.deviceId != null) return null
        val minted = brainConfigFor(record, existingConfigs) ?: return null
        val stored = existingConfigs.firstOrNull { it.brainId == minted.brainId }
        if (stored != null && sameEndpoint(stored, minted.baseUrl ?: "", minted.model)) {
            return null
        }
        val recordAuthRef = record.authRef?.takeIf { it.startsWith(AUTH_REF_PREFIX) }
        return if (recordAuthRef != null && recordAuthRef != minted.authRef) {
            minted.copy(authRef = recordAuthRef)
        } else {
            minted
        }
    }

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

/**
 * 差异 18 方案 A（B2）：合成配置的存储视图——合成配置那个 brainId
 * 的读取返回内存合成配置，其余读写全部委托真实存储。
 *
 * 通用适配器的注册实例在装配时绑定真实存储、按全局当前大脑取
 * 配置；Agent 轮次里把注册实例临时换成绑定本视图 + 显式 brainId
 * 的适配器，适配器才读得到该 Agent 的合成配置（全局当前大脑正
 * 指向别的大脑时也不受影响——B0 核出的 latent 场景）。这就是
 * B0 推荐的「发送路径设内存覆盖」：覆盖只活一轮，轮次结束恢复
 * 注册实例。B3 的 CompositeBrainConfigStore 落地后可由它承接
 * 同一职责。
 */
class SessionBrainConfigStore(
    private val base: BrainConfigStore,
    private val synthesized: BrainConfig,
) : BrainConfigStore {
    override fun listConfigs(): List<BrainConfig> =
        base.listConfigs().filter { it.brainId != synthesized.brainId } + synthesized

    override fun getConfig(brainId: String): BrainConfig? =
        if (brainId == synthesized.brainId) synthesized else base.getConfig(brainId)

    override fun saveConfig(config: BrainConfig) = base.saveConfig(config)

    override fun deleteConfig(brainId: String) = base.deleteConfig(brainId)

    override fun currentBrainId(): String? = base.currentBrainId()

    override fun setCurrentBrainId(brainId: String?) = base.setCurrentBrainId(brainId)

    override fun deletedBrainIds(): Set<String> = base.deletedBrainIds()

    override fun markBrainDeleted(brainId: String) = base.markBrainDeleted(brainId)

    override fun clearBrainDeleted(brainId: String) = base.clearBrainDeleted(brainId)
}
