package wherevercode.agent.models

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.SharedPreferencesBrainConfigStore

/**
 * 差异 9（B3）验收单测：独立模型存储、幂等迁移、运行时合并视图。
 * 覆盖任务书五项：迁移幂等、墓碑不复活、Composite 模型优先、
 * currentBrainId 代理、迁移前后大脑存储字节不变；另加 Composite
 * 写透传只落大脑存储、迁移只迁三分组两项口径。
 */
@RunWith(RobolectricTestRunner::class)
class ModelStoreB3Test {

    private lateinit var context: Context
    private lateinit var brainStore: SharedPreferencesBrainConfigStore
    private lateinit var modelStore: ModelConfigStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("wherevercode_brain_configs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(ModelConfigStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        brainStore = SharedPreferencesBrainConfigStore(context)
        modelStore = ModelConfigStore(context)
    }

    private fun brainConfig(
        id: String,
        kind: BrainKind = BrainKind.MODEL_API,
        label: String = id,
        model: String = "model-$id",
        authRef: String? = "model-api:$id",
        fallback: List<String> = emptyList(),
    ) = BrainConfig(
        brainId = id,
        kind = kind,
        adapterId = "model-api-generic",
        label = label,
        baseUrl = "https://api.example.com/v1",
        model = model,
        authRef = authRef,
        fallbackBrainIds = fallback,
    )

    /** 大脑存储三键的原始字节（迁移不得改动任何一个）。 */
    private fun brainStoreBytes(): Triple<String?, String?, String?> {
        val prefs = context.getSharedPreferences("wherevercode_brain_configs", Context.MODE_PRIVATE)
        return Triple(
            prefs.getString(SharedPreferencesBrainConfigStore.KEY_CONFIGS, null),
            prefs.getString(SharedPreferencesBrainConfigStore.KEY_CURRENT, null),
            prefs.getString(SharedPreferencesBrainConfigStore.KEY_DELETED, null),
        )
    }

    @Test
    fun `migration copies only the three model kinds`() {
        brainStore.saveConfig(brainConfig("m-api", BrainKind.MODEL_API))
        brainStore.saveConfig(brainConfig("m-cli", BrainKind.AGENT_RUNTIME))
        brainStore.saveConfig(brainConfig("m-sub", BrainKind.SUBSCRIPTION))
        brainStore.saveConfig(brainConfig("m-platform", BrainKind.PLATFORM_AGENT))
        brainStore.saveConfig(brainConfig("m-cloud", BrainKind.CLOUD_AGENT))

        assertTrue(ModelStoreMigration.run(modelStore, brainStore))

        assertEquals(
            setOf("m-api", "m-cli", "m-sub"),
            modelStore.listEntries().map { it.modelId }.toSet(),
        )
        // 复制不移动：大脑存储五条都在。
        assertEquals(5, brainStore.listConfigs().size)
    }

    @Test
    fun `migration is idempotent - running twice yields the same model store`() {
        brainStore.saveConfig(brainConfig("a"))
        brainStore.saveConfig(brainConfig("b", fallback = listOf("a", "ghost")))
        brainStore.setCurrentBrainId("a")

        assertTrue(ModelStoreMigration.run(modelStore, brainStore))
        val afterFirst = modelStore.listEntries()
        val currentFirst = modelStore.currentModelId()
        assertTrue(ModelStoreMigration.run(modelStore, brainStore))

        assertEquals(afterFirst, modelStore.listEntries())
        assertEquals(currentFirst, modelStore.currentModelId())
        assertEquals("a", modelStore.currentModelId())
        // 一次性同步把 fallback 过滤到被迁集合（ghost 不在集合内）。
        assertEquals(listOf("a"), modelStore.getEntry("b")!!.fallbackModelIds)
    }

    @Test
    fun `migration does not overwrite model page edits on later launches`() {
        brainStore.saveConfig(brainConfig("a", model = "old-model"))
        assertTrue(ModelStoreMigration.run(modelStore, brainStore))
        modelStore.saveEntry(modelStore.getEntry("a")!!.copy(model = "edited-model"))

        assertTrue(ModelStoreMigration.run(modelStore, brainStore))
        assertEquals("edited-model", modelStore.getEntry("a")!!.model)
    }

    @Test
    fun `tombstoned model entries are not resurrected by migration`() {
        brainStore.saveConfig(brainConfig("builtin-x"))
        assertTrue(ModelStoreMigration.run(modelStore, brainStore))
        assertTrue(modelStore.getEntry("builtin-x") != null)

        // 模型页删内置：删条目 + 记墓碑（MainActivity.deleteModel 口径）。
        modelStore.deleteEntry("builtin-x")
        modelStore.markBuiltinDeleted("builtin-x")

        assertTrue(ModelStoreMigration.run(modelStore, brainStore))
        assertNull(modelStore.getEntry("builtin-x"))
        // 大脑侧的原条目不动（删模型不删大脑）。
        assertTrue(brainStore.getConfig("builtin-x") != null)
    }

    @Test
    fun `brain tombstones sync into model tombstones on first migration`() {
        brainStore.saveConfig(brainConfig("kept"))
        brainStore.markBrainDeleted("deleted-builtin")

        assertTrue(ModelStoreMigration.run(modelStore, brainStore))
        assertTrue("deleted-builtin" in modelStore.deletedBuiltinIds())
    }

    @Test
    fun `migration leaves brain store bytes unchanged`() {
        brainStore.saveConfig(brainConfig("a"))
        brainStore.saveConfig(brainConfig("b", BrainKind.SUBSCRIPTION))
        brainStore.setCurrentBrainId("b")
        brainStore.markBrainDeleted("gone")
        val before = brainStoreBytes()

        assertTrue(ModelStoreMigration.run(modelStore, brainStore))
        assertTrue(ModelStoreMigration.run(modelStore, brainStore))

        assertEquals(before, brainStoreBytes())
    }

    @Test
    fun `one-time current sync does not override a later brain current change`() {
        brainStore.saveConfig(brainConfig("a"))
        brainStore.saveConfig(brainConfig("b"))
        brainStore.setCurrentBrainId("a")
        assertTrue(ModelStoreMigration.run(modelStore, brainStore))
        assertEquals("a", modelStore.currentModelId())

        // 一次性同步已完成：之后大脑侧改当前项，不再往模型侧同步。
        brainStore.setCurrentBrainId("b")
        assertTrue(ModelStoreMigration.run(modelStore, brainStore))
        assertEquals("a", modelStore.currentModelId())
    }

    @Test
    fun `composite prefers the model store on id clash`() {
        brainStore.saveConfig(brainConfig("same", model = "brain-model"))
        brainStore.saveConfig(brainConfig("brain-only", model = "brain-model"))
        modelStore.saveEntry(
            ModelEntry.fromBrainConfig(brainConfig("same", model = "model-model")),
        )
        modelStore.saveEntry(ModelEntry.fromBrainConfig(brainConfig("model-only", model = "model-model")))

        val composite = CompositeBrainConfigStore(modelStore, brainStore)
        assertEquals("model-model", composite.getConfig("same")!!.model)
        assertEquals("brain-model", composite.getConfig("brain-only")!!.model)
        assertEquals("model-model", composite.getConfig("model-only")!!.model)
        val listed = composite.listConfigs()
        assertEquals(
            setOf("same", "brain-only", "model-only"),
            listed.map { it.brainId }.toSet(),
        )
        assertEquals(1, listed.count { it.brainId == "same" })
        assertEquals("model-model", listed.first { it.brainId == "same" }.model)
    }

    @Test
    fun `composite currentBrainId proxies model current first`() {
        brainStore.saveConfig(brainConfig("a"))
        brainStore.setCurrentBrainId("a")
        val composite = CompositeBrainConfigStore(modelStore, brainStore)
        assertEquals("a", composite.currentBrainId())

        modelStore.setCurrentModelId("model-current")
        assertEquals("model-current", composite.currentBrainId())

        modelStore.setCurrentModelId(null)
        assertEquals("a", composite.currentBrainId())
    }

    @Test
    fun `composite writes pass through to the brain store only`() {
        val composite = CompositeBrainConfigStore(modelStore, brainStore)
        composite.saveConfig(brainConfig("written"))
        assertTrue(brainStore.getConfig("written") != null)
        assertNull(modelStore.getEntry("written"))

        composite.setCurrentBrainId("written")
        assertEquals("written", brainStore.currentBrainId())
        assertNull(modelStore.currentModelId())

        composite.markBrainDeleted("written")
        assertTrue("written" in brainStore.deletedBrainIds())
        assertFalse("written" in modelStore.deletedBuiltinIds())

        composite.deleteConfig("written")
        assertNull(brainStore.getConfig("written"))
        assertNull(modelStore.getEntry("written"))
    }
}
