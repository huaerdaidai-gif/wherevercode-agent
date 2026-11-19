package wherevercode.agent.models

import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigStore

/**
 * 差异 9 决策 1（B3）：运行时只读合并视图。
 *
 * 读：模型存储优先，大脑存储兜底——
 * - [listConfigs]：模型存储全部条目 + 大脑存储中 id 不在模型存储
 *   的条目（同 id 时模型存储优先，只出现一次）；
 * - [getConfig]：先查模型存储，未命中再查大脑存储；
 * - [currentBrainId]（必须代理）：模型存储的当前模型优先，未设
 *   时回落大脑存储的当前大脑——模型页切换当前模型后，引擎装配
 *   立即读到新当前项。
 *
 * 写：一律透传底层大脑存储，绝不写模型存储——saveConfig /
 * deleteConfig / setCurrentBrainId / 墓碑三法都只落 brainStore。
 * 模型存储的增删改只走模型页自己的 [ModelConfigStore]，大脑分节
 * 的增删改只走 brainStore 本体；本类只是装配层给引擎 / 适配器 /
 * 路由的一张合并读视图，不当任何一边的写入口。
 */
class CompositeBrainConfigStore(
    private val modelStore: ModelConfigStore,
    private val brainStore: BrainConfigStore,
) : BrainConfigStore {

    override fun listConfigs(): List<BrainConfig> {
        val models = modelStore.listEntries().map { it.toBrainConfig() }
        val modelIds = models.map { it.brainId }.toSet()
        return models + brainStore.listConfigs().filter { it.brainId !in modelIds }
    }

    override fun getConfig(brainId: String): BrainConfig? =
        modelStore.getEntry(brainId)?.toBrainConfig() ?: brainStore.getConfig(brainId)

    override fun saveConfig(config: BrainConfig) {
        brainStore.saveConfig(config)
    }

    override fun deleteConfig(brainId: String) {
        brainStore.deleteConfig(brainId)
    }

    override fun currentBrainId(): String? =
        modelStore.currentModelId() ?: brainStore.currentBrainId()

    override fun setCurrentBrainId(brainId: String?) {
        brainStore.setCurrentBrainId(brainId)
    }

    override fun deletedBrainIds(): Set<String> = brainStore.deletedBrainIds()

    override fun markBrainDeleted(brainId: String) {
        brainStore.markBrainDeleted(brainId)
    }

    override fun clearBrainDeleted(brainId: String) {
        brainStore.clearBrainDeleted(brainId)
    }
}
