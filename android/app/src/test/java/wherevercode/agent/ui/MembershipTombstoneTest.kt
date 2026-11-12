package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigStore
import wherevercode.agent.brain.SharedPreferencesBrainConfigStore
import wherevercode.agent.brain.siwc.BrainBootstrap

/**
 * 会员入口的完全删除 + 更新后不复活（maintainer 2026-11-12 08:41/08:42 定）。
 *
 * 覆盖这次新加的全部四个会员入口（Qwen / Kimi / Grok 会员 /
 * Gemini），不只覆盖原有大脑：
 * 1. 墓碑（deleted_brain_ids_json）里必须有删掉的每一个 ID；
 * 2. 播种逻辑每次先查墓碑：有墓碑的 ID 一律跳过、绝不重新播种；
 * 3. “模拟更新后启动”：对同一个 store（同一份
 *    SharedPreferences 文件——App 更新只换 APK，不动应用数据
 *    目录）再走一遍播种，确认删掉的入口没有复活；
 * 4. 已删的能从列表末尾重新添加（清墓碑 + 写回种子）。
 *
 * 全程 fake/内存 + Robolectric 的 SharedPreferences：零真实调用，
 * 不登录、不调任何厂商端点。
 */
@RunWith(RobolectricTestRunner::class)
class MembershipTombstoneTest {

    private val membershipIds = listOf("qwen", "kimi", "grok-membership", "gemini")

    /** In-memory BrainConfigStore with working tombstones (same contract as the real store). */
    private class FakeStore : BrainConfigStore {
        val configs = mutableMapOf<String, BrainConfig>()
        val tombstones = mutableSetOf<String>()
        var current: String? = null

        override fun listConfigs(): List<BrainConfig> = configs.values.toList()
        override fun getConfig(brainId: String): BrainConfig? = configs[brainId]
        override fun saveConfig(config: BrainConfig) {
            configs[config.brainId] = config
        }

        override fun deleteConfig(brainId: String) {
            configs.remove(brainId)
            if (current == brainId) current = null
        }

        override fun currentBrainId(): String? = current
        override fun setCurrentBrainId(brainId: String?) {
            current = brainId
        }

        override fun deletedBrainIds(): Set<String> = tombstones.toSet()
        override fun markBrainDeleted(brainId: String) {
            tombstones.add(brainId)
        }

        override fun clearBrainDeleted(brainId: String) {
            tombstones.remove(brainId)
        }
    }

    /**
     * One app launch's seeding pass: exactly what BrainBootstrap.build()
     * does for a first-line brain — register the seeded config, and
     * persist the seed when there is no stored config. A deleted brain
     * yields null from seededConfig, so nothing is registered or saved.
     */
    private fun seedingPass(store: BrainConfigStore, brainId: String) {
        val seeded = BrainBootstrap.seededConfig(store, brainId) ?: return
        store.saveConfig(seeded)
    }

    @Test
    fun `all four membership brains are seedable`() {
        assertEquals(
            membershipIds.toSet(),
            membershipIds.filter { BrainBootstrap.seedConfigFor(it) != null }.toSet(),
        )
        for (id in membershipIds) {
            assertTrue(
                "$id must be in the seedable set",
                id in BrainBootstrap.SEEDABLE_BRAIN_IDS,
            )
        }
    }

    @Test
    fun `deleted membership brains stay deleted across a simulated update restart`() {
        val store = FakeStore()
        // First launch: seed all four (first run).
        for (id in membershipIds) seedingPass(store, id)
        assertEquals(membershipIds.toSet(), store.configs.keys)

        // The user deletes every membership entry: config + tombstone
        // (the Keystore token wipe is the delete path's other half,
        // covered by DeviceCodeManagerTest's signOut/clear tests).
        for (id in membershipIds) {
            store.deleteConfig(id)
            store.markBrainDeleted(id)
        }
        assertEquals(membershipIds.toSet(), store.deletedBrainIds())
        assertTrue(store.configs.isEmpty())

        // Simulated update restart: the app is updated (code replaced)
        // and launched again against the SAME store — the same
        // SharedPreferences file an APK update leaves untouched.
        // Every seeding path must consult the tombstones first.
        for (id in membershipIds) seedingPass(store, id)
        assertTrue(
            "no deleted membership brain may resurrect after an update restart",
            store.configs.isEmpty(),
        )
        assertNull(BrainBootstrap.seededConfig(store, "qwen"))
        assertNull(BrainBootstrap.seededConfig(store, "kimi"))
        assertNull(BrainBootstrap.seededConfig(store, "grok-membership"))
        assertNull(BrainBootstrap.seededConfig(store, "gemini"))

        // And a second restart still does not resurrect them.
        for (id in membershipIds) seedingPass(store, id)
        assertTrue(store.configs.isEmpty())
    }

    @Test
    fun `deleting one membership brain leaves the others alone`() {
        val store = FakeStore()
        for (id in membershipIds) seedingPass(store, id)
        store.deleteConfig("kimi")
        store.markBrainDeleted("kimi")
        // Simulated update restart.
        for (id in membershipIds) seedingPass(store, id)
        assertEquals(setOf("qwen", "grok-membership", "gemini"), store.configs.keys)
        assertEquals(setOf("kimi"), store.deletedBrainIds())
    }

    @Test
    fun `a deleted membership brain can be re-added from the list tail`() {
        val store = FakeStore()
        for (id in membershipIds) seedingPass(store, id)
        store.deleteConfig("qwen")
        store.markBrainDeleted("qwen")
        for (id in membershipIds) seedingPass(store, id)
        assertFalse(store.configs.containsKey("qwen"))

        assertTrue(BrainBootstrap.restoreSeededBrain(store, "qwen"))
        assertTrue("re-adding clears the tombstone", store.deletedBrainIds().isEmpty())
        assertEquals("Qwen 会员", store.getConfig("qwen")?.label)
        // Another brain deleted later can be re-added the same way.
        store.deleteConfig("kimi")
        store.markBrainDeleted("kimi")
        assertTrue(BrainBootstrap.restoreSeededBrain(store, "kimi"))
        assertEquals("Kimi Code 订阅", store.getConfig("kimi")?.label)
        assertFalse("unknown ids cannot be restored", BrainBootstrap.restoreSeededBrain(store, "nope"))
    }

    @Test
    fun `seededConfig returns the stored config, never overwrites it`() {
        val store = FakeStore()
        for (id in membershipIds) seedingPass(store, id)
        val edited = store.getConfig("qwen")!!.copy(model = "qwen-plus")
        store.saveConfig(edited)
        assertEquals("qwen-plus", BrainBootstrap.seededConfig(store, "qwen")?.model)
        // A stored config beats a tombstone only when it exists; the
        // delete path always deletes the config first, so this order
        // (stored wins) cannot resurrect a deleted brain.
        store.markBrainDeleted("qwen")
        assertEquals("qwen-plus", BrainBootstrap.seededConfig(store, "qwen")?.model)
    }

    @Test
    fun `tombstones persist in SharedPreferences under deleted_brain_ids_json`() {
        val store = SharedPreferencesBrainConfigStore(RuntimeEnvironment.getApplication())
        assertTrue(store.deletedBrainIds().isEmpty())
        store.markBrainDeleted("qwen")
        store.markBrainDeleted("gemini")
        assertEquals(setOf("qwen", "gemini"), store.deletedBrainIds())
        // A fresh store over the same SharedPreferences file — the
        // same file an APK update leaves untouched — still sees them.
        val reopened = SharedPreferencesBrainConfigStore(RuntimeEnvironment.getApplication())
        assertEquals(setOf("qwen", "gemini"), reopened.deletedBrainIds())
        // The raw key is exactly the documented one.
        val raw = RuntimeEnvironment.getApplication()
            .getSharedPreferences("wherevercode_brain_configs", 0)
            .getString("deleted_brain_ids_json", null)
        assertTrue(raw!!.contains("qwen"))
        assertTrue(raw.contains("gemini"))
        reopened.clearBrainDeleted("qwen")
        assertEquals(setOf("gemini"), reopened.deletedBrainIds())
    }
}
