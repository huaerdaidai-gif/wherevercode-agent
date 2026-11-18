package wherevercode.agent.brain.generic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自定义免费服务商扩展口：序列化往返、id 生成、合并顺序。 */
class CustomFreeProviderStoreTest {

    private fun preset(
        id: String = "custom-my-lab",
        mode: FreeModelListMode = FreeModelListMode.FIXED,
        models: List<String> = listOf("lab-7b", "lab-32b"),
    ) = FreeProviderPreset(
        id = id,
        displayName = "我的实验室",
        fixedModels = models,
        listMode = mode,
        baseUrlTemplate = "https://lab.example.com/v1",
        keyHelp = "在实验室控制台领 key。",
        note = "自加的服务商。",
    )

    @Test
    fun `json round trip keeps every field`() {
        val original = preset()
        val restored = CustomFreeProviders.fromJson(CustomFreeProviders.toJson(original))
        assertEquals(original, restored)
    }

    @Test
    fun `live custom provider needs no fixed models`() {
        val live = preset(mode = FreeModelListMode.LIVE, models = emptyList())
        assertEquals(live, CustomFreeProviders.fromJson(CustomFreeProviders.toJson(live)))
    }

    @Test
    fun `broken entries are dropped, not guessed`() {
        val noModels = CustomFreeProviders.toJson(preset()).apply { remove("fixedModels") }
        assertNull(CustomFreeProviders.fromJson(noModels))
        val blank = CustomFreeProviders.toJson(preset()).apply { put("baseUrlTemplate", "") }
        assertNull(CustomFreeProviders.fromJson(blank))
    }

    @Test
    fun `custom ids are slugged, prefixed and de-duplicated`() {
        assertEquals("custom-my-lab", CustomFreeProviders.customId("My Lab!", emptySet()))
        assertEquals(
            "custom-my-lab-2",
            CustomFreeProviders.customId("My Lab!", setOf("custom-my-lab")),
        )
        assertTrue(CustomFreeProviders.customId("实验室", emptySet()).startsWith("custom-"))
    }

    @Test
    fun `merged keeps builtins first and customs after`() {
        val customs = listOf(preset())
        val merged = CustomFreeProviders.merged(FreeProviderPresets.PRESETS, customs)
        assertEquals(FreeProviderPresets.PRESETS.size + 1, merged.size)
        assertEquals(customs, merged.takeLast(1))
        assertTrue(CustomFreeProviders.isCustom(merged.last()))
    }
}
