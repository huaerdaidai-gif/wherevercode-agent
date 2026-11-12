package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 凭证存储（V5，2026-11-11）：Keystore 包装的存取口。
 * 内存假实现，不碰 Android Keystore、不含任何真实凭证值。
 */
class AgentCredentialStoresTest {

    private class MemorySecrets : AgentSecretStore {
        val map = HashMap<String, String>()
        override fun put(ref: String, value: String) { map[ref] = value }
        override fun get(ref: String): String? = map[ref]
        override fun delete(ref: String) { map.remove(ref) }
        override fun contains(ref: String): Boolean = map.containsKey(ref)
    }

    @Test
    fun `platform token round trips under its fixed ref and clears`() {
        val store = PlatformAgentCredentialStore(MemorySecrets())
        assertNull(store.loadPlatformToken())
        store.savePlatformToken("test-platform-token-not-real")
        assertEquals("test-platform-token-not-real", store.loadPlatformToken())
        store.clearPlatformToken()
        assertNull(store.loadPlatformToken())
    }

    @Test
    fun `webhook secret is per agent and keyed by agent id`() {
        val secrets = MemorySecrets()
        val store = PlatformAgentCredentialStore(secrets)
        store.saveWebhookSecret("example-agent", "test-webhook-secret-not-real")
        assertEquals("test-webhook-secret-not-real", store.loadWebhookSecret("example-agent"))
        assertNull(store.loadWebhookSecret("other-bot"))
        assertEquals(
            "platform-agent:example-agent:webhook-secret",
            PlatformAgentCredentialStore.webhookSecretRefFor("example-agent"),
        )
        store.clearWebhookSecret("example-agent")
        assertNull(store.loadWebhookSecret("example-agent"))
    }

    @Test
    fun `cloud agent key ref is per agent and generated keys carry the wco prefix`() {
        val secrets = MemorySecrets()
        val store = CloudAgentCredentialStore(secrets)
        assertEquals("cloud-agent:grok-cloud:mcp-token", CloudAgentCredentialStore.keyRefFor("grok-cloud"))

        val first = CloudAgentCredentialStore.generateKey()
        val second = CloudAgentCredentialStore.generateKey()
        assertTrue(first.startsWith("wco_"))
        assertTrue(first.length > 40)
        assertTrue(first != second)

        store.saveKey("grok-cloud", first)
        assertEquals(first, store.loadKey("grok-cloud"))
        assertNull(store.loadKey("other-cloud"))
        store.clearKey("grok-cloud")
        assertNull(store.loadKey("grok-cloud"))
    }

    @Test
    fun `blank secrets are refused`() {
        val store = PlatformAgentCredentialStore(MemorySecrets())
        try {
            store.savePlatformToken("  ")
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 预期：空凭证不存。
        }
        val cloud = CloudAgentCredentialStore(MemorySecrets())
        try {
            cloud.saveKey("grok-cloud", "")
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 预期：空钥匙不存。
        }
    }
}
