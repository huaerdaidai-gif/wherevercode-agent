package wherevercode.agent.core.extension

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.agent.AgentSecretStore
import wherevercode.agent.core.extension.mcp.McpSecretSealer
import wherevercode.agent.core.extension.mcp.McpServersConfig
import wherevercode.agent.core.extension.mcp.SecretSealingExtensionStore
import wherevercode.agent.core.extension.mcp.SecretStoreMcpCredentialResolver
import wherevercode.agent.core.security.SecretRedactor

/**
 * 2026-11-13 安全加固回归：MCP 令牌保护。
 *
 * 修前：MCP 行的 Authorization / X-Api-Key / *_TOKEN 原样写进未加密的
 * SharedPreferences（extra.headersJson / envJson），导出也带明文；App 里
 * 没有 keystore: 引用的解析器。修后：落盘前封存进密钥库（生产为
 * Keystore 包装的 EncryptedSharedPreferences），行里只留引用；建客户端时解回。
 */
class McpTokenProtectionTest {

    private class MemorySecrets : AgentSecretStore {
        val values = LinkedHashMap<String, String>()
        override fun put(ref: String, value: String) { values[ref] = value }
        override fun get(ref: String): String? = values[ref]
        override fun delete(ref: String) { values.remove(ref) }
        override fun contains(ref: String): Boolean = ref in values
    }

    private class BrokenSecrets : AgentSecretStore {
        override fun put(ref: String, value: String) = throw IllegalStateException("keystore down")
        override fun get(ref: String): String? = throw IllegalStateException("keystore down")
        override fun delete(ref: String) = Unit
        override fun contains(ref: String): Boolean = false
    }

    /** 记录“落盘”内容的后端：断言磁盘上没有明文。 */
    private class DiskStore(initial: Map<String, Map<String, ExtensionEntry>> = emptyMap()) : ExtensionStore {
        var saved: Map<String, Map<String, ExtensionEntry>> = initial
        override fun loadOverlay() = saved
        override fun saveOverlay(entries: Map<String, Map<String, ExtensionEntry>>) { saved = entries }
        fun dump(): String = saved.values.flatMap { it.values }.joinToString("\n") { it.toJson().toString() }
    }

    private val token = "v5_" + "A1b2C3d4E5f6G7h8I9j0KLMNOP"

    private fun importJson() = """
        {"mcpServers": {
          "platform": {"url": "https://mcp.example.invalid/api/open/v1/mcp",
                       "headers": {"Authorization": "Bearer $token", "Accept": "application/json"}},
          "gh": {"command": "npx", "args": ["-y", "server-github"],
                 "env": {"GITHUB_TOKEN": "ghp_${"x".repeat(36)}", "LOG_LEVEL": "info", "HOME_TOKEN": "${'$'}{HOME_TOKEN}"}}
        }}
    """.trimIndent()

    @Test
    fun `imported plaintext credentials never reach disk and resolve back for the client`() {
        val secrets = MemorySecrets()
        val disk = DiskStore()
        val registry = ExtensionRegistry(SecretSealingExtensionStore(disk, McpSecretSealer(secrets)))
        McpServersConfig.importToRegistry(importJson(), registry)

        val dumped = disk.dump()
        assertFalse("令牌明文不得落盘", dumped.contains(token))
        assertFalse(dumped.contains("ghp_xxxx"))
        assertTrue(dumped.contains("keystore:mcp:platform:header:authorization"))
        assertTrue("非凭证头保持原样", dumped.contains("application/json"))
        assertTrue("env 占位不封存", dumped.contains("\${HOME_TOKEN}"))
        assertTrue(dumped.contains("info"))

        val resolver = SecretStoreMcpCredentialResolver(secrets)
        val stored = disk.saved["mcp"]!!["platform"]!!
        val header = JSONObject(stored.extra["headersJson"]!!).getString("Authorization")
        assertEquals("Bearer $token", McpServersConfig.resolveValue(header, resolver))
    }

    @Test
    fun `export after a restart carries references, not secrets`() {
        val secrets = MemorySecrets()
        val disk = DiskStore()
        McpServersConfig.importToRegistry(
            importJson(),
            ExtensionRegistry(SecretSealingExtensionStore(disk, McpSecretSealer(secrets))),
        )
        // 模拟 App 重启：新注册表从同一份“磁盘”读。
        val reloaded = ExtensionRegistry(SecretSealingExtensionStore(disk, McpSecretSealer(secrets)))
        val exported = McpServersConfig.exportFromRegistry(reloaded)
        assertFalse(exported.contains(token))
        assertTrue(exported.contains("keystore:mcp:gh:env:GITHUB_TOKEN"))
    }

    @Test
    fun `legacy plaintext rows are migrated on first load`() {
        val legacy = ExtensionEntry(
            id = "legacy",
            kind = ExtensionKind.MCP,
            displayName = "legacy",
            source = "user",
            extra = mapOf(
                "transport" to "http",
                "url" to "https://mcp.example.invalid/mcp",
                "headersJson" to JSONObject(mapOf("X-Api-Key" to "plain-legacy-key-123")).toString(),
            ),
        )
        val disk = DiskStore(mapOf("mcp" to mapOf("legacy" to legacy)))
        val secrets = MemorySecrets()
        ExtensionRegistry(SecretSealingExtensionStore(disk, McpSecretSealer(secrets)))
        assertFalse("旧明文读取后应被迁移", disk.dump().contains("plain-legacy-key-123"))
        assertEquals("plain-legacy-key-123", secrets.get("mcp:legacy:header:x-api-key"))
    }

    @Test
    fun `keystore failure keeps the config instead of dropping it`() {
        val disk = DiskStore()
        val sealer = McpSecretSealer(BrokenSecrets())
        McpServersConfig.importToRegistry(importJson(), ExtensionRegistry(SecretSealingExtensionStore(disk, sealer)))
        assertEquals(2, disk.saved["mcp"]?.size)
        assertTrue(sealer.lastFailures.isNotEmpty())
        assertFalse("失败记录里只有名字，没有值", sealer.lastFailures.joinToString().contains(token))
        assertNull(SecretStoreMcpCredentialResolver(BrokenSecrets()).resolve("mcp:platform:header:authorization"))
    }

    @Test
    fun `redactor masks bare platform and github tokens`() {
        val line = "connect failed with $token and ghp_${"a".repeat(36)} and github_pat_${"b".repeat(30)}"
        val redacted = SecretRedactor.redact(line, 10_000)
        assertFalse(redacted.contains(token))
        assertFalse(redacted.contains("ghp_aaaa"))
        assertFalse(redacted.contains("github_pat_bbbb"))
        assertTrue(SecretRedactor.containsSecret("wco_${"z".repeat(20)}"))
        assertEquals("v5 channel ok", SecretRedactor.redact("v5 channel ok"))
    }
}
