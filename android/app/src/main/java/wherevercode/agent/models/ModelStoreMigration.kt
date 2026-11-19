package wherevercode.agent.models

import wherevercode.agent.brain.BrainConfigStore
import wherevercode.agent.brain.BrainKind

/**
 * 差异 9（B3）：大脑存储 → 独立模型存储的迁移。
 *
 * 两段，均为复制不移动——大脑存储一个字节都不改（迁移前后大脑
 * 存储字节相等是验收项，见单测）：
 *
 * 1. 幂等按 ID 补齐（每次启动执行）：大脑存储中属于模型页三分组
 *    （MODEL_API / AGENT_RUNTIME / SUBSCRIPTION；平台 / 云端 Agent
 *    不是模型连接，不迁）的条目，若 id 不在模型存储中、且不在
 *    模型存储的已删内置墓碑中，则复制进模型存储（保留原 id 与
 *    extra 全量）。早期版本迁移漏拷的字段靠这段在每次启动补齐，
 *    不会被一次性完成标记锁死。
 *
 * 2. 一次性状态同步（受 `model.migration_v1_done` 保护，仅一次）：
 *    当前选中项（仅当它在被迁集合内、且模型存储尚未设当前项）、
 *    fallback 列表（过滤到被迁集合）、已删内置墓碑（大脑侧墓碑
 *    同步进模型侧墓碑）。崩溃安全：先落盘配置数据与当前项，
 *    最后才写 migration_v1_done = true——中途崩溃下次启动重跑，
 *    两段都是幂等，结果不变。
 *
 * 失败不抛：装配层在启动路径上调用本迁移，迁移异常绝不能让
 * App 起不来（与 BrainBootstrap 的探针回退同哲学）；返回 false
 * 表示本轮未完成，下次启动会再试。
 */
object ModelStoreMigration {

    /** 被迁集合的 kind：模型页三分组对应的三种连接，平台 / 云端 Agent 除外。 */
    val MIGRATED_KINDS: Set<BrainKind> = setOf(
        BrainKind.MODEL_API,
        BrainKind.AGENT_RUNTIME,
        BrainKind.SUBSCRIPTION,
    )

    fun run(modelStore: ModelConfigStore, brainStore: BrainConfigStore): Boolean {
        return try {
            topUp(modelStore, brainStore)
            if (!modelStore.isMigrationV1Done()) {
                syncOneTimeState(modelStore, brainStore)
                // 完成标记最后写：数据与状态都落盘后才置位。
                modelStore.markMigrationV1Done()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 第 1 段：幂等补齐。已在模型存储的 id 不覆盖（模型页的编辑优先）。 */
    private fun topUp(modelStore: ModelConfigStore, brainStore: BrainConfigStore) {
        val existing = modelStore.listEntries().map { it.modelId }.toSet()
        val tombstoned = modelStore.deletedBuiltinIds()
        for (config in brainStore.listConfigs()) {
            if (config.kind !in MIGRATED_KINDS) continue
            if (config.brainId in existing) continue
            if (config.brainId in tombstoned) continue
            modelStore.saveEntry(ModelEntry.fromBrainConfig(config))
        }
    }

    /** 第 2 段：一次性状态同步（当前项 / fallback 过滤 / 墓碑同步）。 */
    private fun syncOneTimeState(modelStore: ModelConfigStore, brainStore: BrainConfigStore) {
        val migratedIds = brainStore.listConfigs()
            .filter { it.kind in MIGRATED_KINDS }
            .map { it.brainId }
            .toSet()
        // 当前项：仅当大脑当前项在被迁集合内、且模型侧还没设当前项。
        val brainCurrent = brainStore.currentBrainId()
        if (brainCurrent != null && brainCurrent in migratedIds &&
            modelStore.currentModelId() == null
        ) {
            modelStore.setCurrentModelId(brainCurrent)
        }
        // fallback：只保留指向被迁集合的 id，其余滤掉（模型存储里的
        // fallback 只能在模型条目之间成立）。
        for (entry in modelStore.listEntries()) {
            val filtered = entry.fallbackModelIds.filter { it in migratedIds }
            if (filtered != entry.fallbackModelIds) {
                modelStore.saveEntry(entry.copy(fallbackModelIds = filtered))
            }
        }
        // 墓碑：大脑侧已删内置的墓碑同步进模型侧，补齐时不复活。
        for (id in brainStore.deletedBrainIds()) {
            modelStore.markBuiltinDeleted(id)
        }
    }
}
