package wherevercode.agent.brain.generic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免费预设名单的完整性守卫：名单是给所有新用户的开箱路径，写错一家
 * （地址、名单形态、占位符）就是所有人一起踩。逐项断言，不依赖网络。
 */
class FreeProviderPresetsTest {

    @Test
    fun `six verified providers, unique ids`() {
        val ids = FreeProviderPresets.PRESETS.map { it.id }
        assertEquals(6, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            setOf("google-ai-studio", "openrouter", "groq", "siliconflow", "zhipu", "cloudflare"),
            ids.toSet(),
        )
    }

    @Test
    fun `every preset has https base url and key help`() {
        for (preset in FreeProviderPresets.PRESETS) {
            assertTrue(preset.id, preset.baseUrlTemplate.startsWith("https://"))
            assertTrue(preset.id, preset.keyHelp.isNotBlank())
            assertTrue(preset.id, preset.displayName.isNotBlank())
        }
    }

    @Test
    fun `live presets carry no fixed list, fixed presets carry only theirs`() {
        for (preset in FreeProviderPresets.PRESETS) {
            when (preset.listMode) {
                FreeModelListMode.LIVE -> assertTrue(preset.id, preset.fixedModels.isEmpty())
                FreeModelListMode.FIXED -> {
                    assertTrue(preset.id, preset.fixedModels.isNotEmpty())
                    assertEquals(preset.id, preset.fixedModels.size, preset.fixedModels.toSet().size)
                    assertTrue(preset.id, preset.fixedModels.none { it.isBlank() })
                }
            }
        }
    }

    @Test
    fun `only cloudflare needs an account id and it resolves into the url`() {
        val needs = FreeProviderPresets.PRESETS.filter { it.needsAccountId }
        assertEquals(listOf("cloudflare"), needs.map { it.id })
        val cf = FreeProviderPresets.preset("cloudflare")!!
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/abc123/ai/v1",
            cf.resolveBaseUrl("abc123"),
        )
        val groq = FreeProviderPresets.preset("groq")!!
        assertFalse(groq.needsAccountId)
        assertEquals("https://api.groq.com/openai/v1", groq.resolveBaseUrl())
    }

    @Test
    fun `zhipu fixed list is the official free chat models`() {
        val zhipu = FreeProviderPresets.preset("zhipu")!!
        assertTrue(zhipu.fixedModels.contains("glm-4.7-flash"))
        assertTrue(zhipu.fixedModels.contains("glm-4-flash-250414"))
        // 图像/视频生成款不进对话名单。
        assertTrue(zhipu.fixedModels.none { it.contains("cogview") || it.contains("cogvideo") })
    }

    @Test
    fun `preset lookup by id and by base url`() {
        assertEquals("openrouter", FreeProviderPresets.presetForBaseUrl("https://openrouter.ai/api/v1/")?.id)
        assertEquals(
            "cloudflare",
            FreeProviderPresets.presetForBaseUrl("https://api.cloudflare.com/client/v4/accounts/xyz/ai/v1")?.id,
        )
        assertNull(FreeProviderPresets.presetForBaseUrl("https://api.example.com/v1"))
        assertNull(FreeProviderPresets.preset("nope"))
    }

    @Test
    fun `free tier extra markers are stable`() {
        assertEquals("tier", FreeProviderPresets.EXTRA_TIER)
        assertEquals("free", FreeProviderPresets.TIER_FREE)
        assertEquals("provider_preset", FreeProviderPresets.EXTRA_PRESET)
    }
}
