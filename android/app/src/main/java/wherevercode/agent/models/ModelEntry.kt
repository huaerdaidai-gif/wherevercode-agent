package wherevercode.agent.models

import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentLoginState

/**
 * 差异 9（B3）：独立模型存储的一条模型连接，与 [BrainConfig] 字段
 * 一一对应（id 在本存储里叫 modelId，与 iOS ModelConfig 对称），
 * [extra] 全量原样保留（包含 B2 的 `model_origin` 来源标记）。
 *
 * 模型存储与大脑存储彻底分家：模型页的增删改只读写本存储
 * （[ModelConfigStore]），设置页「大脑」分节只读写大脑存储；
 * 运行时消费经 [CompositeBrainConfigStore] 合并两边（模型优先）。
 * 密钥仍然只以 [authRef] 引用名出现，值只进 Keystore，与大脑侧
 * 同一条纪律（新建通用模型时 authRef 统一为 `model-api:<id>`，
 * 由保存路径的 BrainLoginRows.genericAuthRef 铸造，本类不另铸）。
 */
data class ModelEntry(
    val modelId: String,
    val kind: BrainKind,
    val adapterId: String,
    val label: String,
    val baseUrl: String?,
    val model: String,
    val authRef: String?,
    val extra: Map<String, String> = emptyMap(),
    val fallbackModelIds: List<String> = emptyList(),
    val loginState: AgentLoginState? = null,
    val lastTestedAtEpochSeconds: Long? = null,
    val lastTestSummary: String? = null,
    val cliBinary: String? = null,
) {
    /** 运行时视图：同一条连接的大脑配置形态（id 回到 brainId）。 */
    fun toBrainConfig(): BrainConfig = BrainConfig(
        brainId = modelId,
        kind = kind,
        adapterId = adapterId,
        label = label,
        baseUrl = baseUrl,
        model = model,
        authRef = authRef,
        extra = extra,
        fallbackBrainIds = fallbackModelIds,
        loginState = loginState,
        lastTestedAtEpochSeconds = lastTestedAtEpochSeconds,
        lastTestSummary = lastTestSummary,
        cliBinary = cliBinary,
    )

    companion object {
        /** 大脑存储里的一条配置 → 模型条目（迁移复制与 UI 写入共用）。 */
        fun fromBrainConfig(config: BrainConfig): ModelEntry = ModelEntry(
            modelId = config.brainId,
            kind = config.kind,
            adapterId = config.adapterId,
            label = config.label,
            baseUrl = config.baseUrl,
            model = config.model,
            authRef = config.authRef,
            extra = config.extra,
            fallbackModelIds = config.fallbackBrainIds,
            loginState = config.loginState,
            lastTestedAtEpochSeconds = config.lastTestedAtEpochSeconds,
            lastTestSummary = config.lastTestSummary,
            cliBinary = config.cliBinary,
        )
    }
}
