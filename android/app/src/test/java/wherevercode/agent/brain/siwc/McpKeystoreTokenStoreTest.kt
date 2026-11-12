package wherevercode.agent.brain.siwc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.extension.mcp.McpTokens

class McpKeystoreTokenStoreTest {
    private class MemorySecrets : McpSecretStore {
        val rows = mutableMapOf<String, String>()
        override fun put(authRef: String, value: String) { rows[authRef] = value }
        override fun get(authRef: String): String? = rows[authRef]
        override fun delete(authRef: String) { rows.remove(authRef) }
    }

    @Test
    fun `tokens survive store recreation and are isolated per server`() {
        val secrets = MemorySecrets()
        val expiry = 1_800_000_000_000L
        val token = McpTokens("access-one", "refresh-one", expiry, "Bearer")
        KeystoreMcpTokenStore(secrets, "server-one").save(token)

        val restored = KeystoreMcpTokenStore(secrets, "server-one")
        assertEquals(token, restored.load())
        assertNull(KeystoreMcpTokenStore(secrets, "server-two").load())
        assertTrue(secrets.rows.keys.single().startsWith("mcp-oauth:"))
        assertTrue("raw server ID must not be used as storage key", secrets.rows.keys.none { it.contains("server-one") })
    }

    @Test
    fun `corrupt token record fails closed and clear removes only this server`() {
        val secrets = MemorySecrets()
        val first = KeystoreMcpTokenStore(secrets, "server-one")
        val second = KeystoreMcpTokenStore(secrets, "server-two")
        first.save(McpTokens("access-one"))
        second.save(McpTokens("access-two"))
        secrets.rows[secrets.rows.keys.first()] = "{bad-json"

        // Corrupt ciphertext/record is treated as signed out, never a usable token.
        assertNull(first.load())
        first.clear()
        assertEquals("access-two", second.load()!!.accessToken)
    }
}
