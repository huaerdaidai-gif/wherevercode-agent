package wherevercode.agent.brain.devicecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.oauth.VendorOAuthConfigs

/**
 * Device-code vendor table tests (2026-11-12, membership add-all).
 * Pins the verified public facts per vendor (endpoints, public
 * client ids, inference URLs) and the "blocked eight are absent"
 * rule — a vendor enters a table only after verification, and the
 * blocked vendors stay out of BOTH tables (device-code and
 * authorization-code).
 */
class DeviceCodeConfigsTest {

    @Test
    fun `table holds exactly Qwen Kimi Grok membership`() {
        assertEquals(
            listOf("qwen", "kimi", "grok-membership"),
            DeviceCodeConfigs.all.map { it.vendorId },
        )
    }

    @Test
    fun `Qwen config matches the verified public facts`() {
        val config = DeviceCodeConfigs.QWEN
        assertEquals("https://chat.qwen.ai/api/v1/oauth2/device/code", config.deviceCodeUrl)
        assertEquals("https://chat.qwen.ai/api/v1/oauth2/token", config.tokenUrl)
        assertEquals("f0304373b74a44d2b584a3fb70ca9e56", config.clientId)
        assertEquals(
            listOf("openid", "profile", "email", "model.completion"),
            config.scopes,
        )
        assertEquals("https://portal.qwen.ai/v1/chat/completions", config.inferenceBaseUrl)
        assertFalse(config.experimental)
        assertFalse(config.needsDeviceHeaders)
        assertEquals("devicecode:qwen", config.authRef)
        assertEquals("devicecode-qwen", config.adapterId)
        assertTrue(config.complianceNote.contains("QwenLM/qwen-code"))
    }

    @Test
    fun `Kimi config matches the verified public facts`() {
        val config = DeviceCodeConfigs.KIMI
        assertEquals("https://auth.kimi.com/api/oauth/device_authorization", config.deviceCodeUrl)
        assertEquals("https://auth.kimi.com/api/oauth/token", config.tokenUrl)
        assertEquals("17e5f671-d194-4dfb-9706-5516cb48c098", config.clientId)
        assertTrue("Kimi sends no scope", config.scopes.isEmpty())
        assertEquals("https://api.kimi.com/coding/v1", config.inferenceBaseUrl)
        assertTrue(config.needsDeviceHeaders)
        assertFalse(config.experimental)
        assertEquals("Kimi Code 订阅", config.displayName)
        assertTrue(config.complianceNote.contains("Kimi Code 订阅"))
    }

    @Test
    fun `Grok is experimental and says so`() {
        val config = DeviceCodeConfigs.GROK
        assertEquals("https://auth.x.ai/oauth2/device/code", config.deviceCodeUrl)
        assertEquals("https://auth.x.ai/oauth2/token", config.tokenUrl)
        assertEquals("grok-cli", config.clientId)
        assertTrue(config.experimental)
        assertTrue(config.experimentalNote.contains("403"))
        assertTrue(config.experimentalNote.contains("随时失效"))
        assertEquals("grok-membership", config.brainId)
    }

    @Test
    fun `blocked vendors are in neither table`() {
        val deviceIds = DeviceCodeConfigs.all.map { it.vendorId }
        val oauthIds = VendorOAuthConfigs.all.map { it.vendorId }
        // Blocked eight (2026-11-12 verification): none of them may
        // appear in either login table. "grok" as a bare id is not a
        // table entry either — the Grok API brain keeps "grok", the
        // membership brain is "grok-membership".
        for (blocked in listOf("claude", "deepseek", "zhipu", "meta", "copilot", "wenxin", "hunyuan", "doubao")) {
            assertFalse("blocked vendor $blocked must not be in the device-code table", deviceIds.contains(blocked))
            assertFalse("blocked vendor $blocked must not be in the OAuth table", oauthIds.contains(blocked))
        }
        assertNull(DeviceCodeConfigs.byId("claude"))
        assertNull(DeviceCodeConfigs.byId("deepseek"))
        assertNull(DeviceCodeConfigs.byId("gemini"))
    }

    @Test
    fun `Gemini is login-only in the OAuth table, not a device-code vendor`() {
        val gemini = VendorOAuthConfigs.GEMINI
        assertFalse(gemini.inferenceSupported)
        assertNull(gemini.fixedClientId)
        assertNull(DeviceCodeConfigs.byId("gemini"))
    }
}
