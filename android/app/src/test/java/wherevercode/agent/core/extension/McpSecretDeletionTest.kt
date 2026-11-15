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

/**
 * R3 C-2 回归：删除 MCP 服务器时同步删除其 Keystore 凭据。
 *
 * 修前：MainActivity.deleteMcpServer 只断开 + 删注册表行，封存进
 * 密钥库（生产为 Keystore 包装的 EncryptedSharedPreferences）的
 * `mcp:<id>:header:*` / `mcp:<id>:env:*` 条目无人删除，服务器删了
 * 凭据还留在库里。修后：删行前捕获条目、删行成功后按同一命名规则
 * 经 [McpSecretSealer.deleteSecretsFor] 同步删除。
 *
 * 本测试的 [deleteServer] 三步（捕获条目 → remove → purge）与
 * MainActivity.deleteMcpServer 的实际顺序逐字对应；密钥库用内存
 * 假实现（与 McpTokenProtectionTest 同一套路，JVM 单测不碰真
 * Keystore）。
 */
class McpSecretDeletionTest {

    private class MemorySecrets : AgentSecretStore {
        val values = LinkedHashMap<String, String>()
        override fun put(ref: String, value: String) { values[ref] = value }
        override fun get(ref: String): String? = values[ref]
        override fun delete(ref: String) { values.remove(ref) }
        override fun contains(ref: String): Boolean = ref in values
    }

    /** 删除指定引用时抛错、其余正常：验证失败不中断、不外抛、只报名字。 */
    private class FlakyDeleteSecrets(
        private val inner: MemorySecrets,
        private val failOn: String,
    ) : AgentSecretStore {
        override fun put(ref: String, value: String) = inner.put(ref, value)
        override fun get(ref: String): String? = inner.get(ref)
        override fun delete(ref: String) {
            if (ref == failOn) throw IllegalStateException("keystore down")
            inner.delete(ref)
        }
        override fun contains(ref: String): Boolean = inner.contains(ref)
    }

    private class DiskStore : ExtensionStore {
        var saved: Map<String, Map<String, ExtensionEntry>> = emptyMap()
        override fun loadOverlay() = saved
        override fun saveOverlay(entries: Map<String, Map<String, ExtensionEntry>>) { saved = entries }
    }

    private val platformToken = "v5_" + "P1a2t3f4o5r6m7T8o9k0e1n2"
    private val platformApiKey = "plat-api-key-987654321"
    private val ghToken = "ghp_" + "g".repeat(36)
    private val gh2Token = "ghp_" + "h".repeat(36)

    private fun importJson() = """
        {"mcpServers": {
          "platform": {"url": "https://mcp.example.invalid/mcp",
                       "headers": {"Authorization": "Bearer $platformToken", "X-Api-Key": "$platformApiKey"}},
          "gh": {"command": "npx", "args": ["-y", "server-github"],
                 "env": {"GITHUB_TOKEN": "$ghToken"}},
          "gh2": {"command": "npx", "args": ["-y", "server-github-2"],
                  "env": {"GITHUB_TOKEN": "$gh2Token"}}
        }}
    """.trimIndent()

    private class Fixture(val secrets: AgentSecretStore, val sealer: McpSecretSealer, val registry: ExtensionRegistry)

    private fun fixture(secrets: AgentSecretStore = MemorySecrets()): Fixture {
        val sealer = McpSecretSealer(secrets)
        val registry = ExtensionRegistry(SecretSealingExtensionStore(DiskStore(), sealer))
        McpServersConfig.importToRegistry(importJson(), registry)
        return Fixture(secrets, sealer, registry)
    }

    /** 与 MainActivity.deleteMcpServer 相同的删除顺序：捕获条目 → 删行 → 删行成功才删凭据。 */
    private fun deleteServer(f: Fixture, serverId: String): Boolean {
        val entry = f.registry.get(ExtensionKind.MCP, serverId)
        val removed = f.registry.remove(ExtensionKind.MCP, serverId)
        if (removed && entry != null) {
            assertEquals(emptyList<String>(), f.sealer.deleteSecretsFor(entry))
        }
        return removed
    }

    @Test
    fun `deleting a server makes its credentials unreadable`() {
        val f = fixture()
        // 删前：三台服务器的凭据都在库里、可解回。
        assertEquals("Bearer $platformToken", f.secrets.get("mcp:platform:header:authorization"))
        assertEquals(platformApiKey, f.secrets.get("mcp:platform:header:x-api-key"))

        assertTrue(deleteServer(f, "platform"))

        // 配置行已删。
        assertNull(f.registry.get(ExtensionKind.MCP, "platform"))
        // 凭据条目已删：直读为空，经解析器也解不出来（删除后不可读取）。
        assertFalse(f.secrets.contains("mcp:platform:header:authorization"))
        assertFalse(f.secrets.contains("mcp:platform:header:x-api-key"))
        assertNull(f.secrets.get("mcp:platform:header:authorization"))
        val resolver = SecretStoreMcpCredentialResolver(f.secrets)
        assertNull(resolver.resolve("mcp:platform:header:authorization"))
        assertNull(resolver.resolve("mcp:platform:header:x-api-key"))
    }

    @Test
    fun `deleting one server leaves other servers credentials intact`() {
        val f = fixture()
        assertTrue(deleteServer(f, "gh"))

        assertNull(f.secrets.get("mcp:gh:env:GITHUB_TOKEN"))
        // 前缀相邻的 gh2（mcp:gh2:…）不能被 mcp:gh: 的删除误伤。
        assertEquals(gh2Token, f.secrets.get("mcp:gh2:env:GITHUB_TOKEN"))
        assertEquals("Bearer $platformToken", f.secrets.get("mcp:platform:header:authorization"))
        assertEquals(platformApiKey, f.secrets.get("mcp:platform:header:x-api-key"))
        val resolver = SecretStoreMcpCredentialResolver(f.secrets)
        assertEquals(gh2Token, resolver.resolve("mcp:gh2:env:GITHUB_TOKEN"))
    }

    @Test
    fun `repeated deletion does not crash and stays deleted`() {
        val f = fixture()
        val entry = f.registry.get(ExtensionKind.MCP, "platform")!!
        assertTrue(deleteServer(f, "platform"))

        // 注册表再删一次：返回 false（已不存在），不崩。
        assertFalse(f.registry.remove(ExtensionKind.MCP, "platform"))
        // 对同一条目重复清凭据：幂等空操作，无失败、不抛。
        assertEquals(emptyList<String>(), f.sealer.deleteSecretsFor(entry))
        assertEquals(emptyList<String>(), f.sealer.deleteSecretsFor(entry))
        assertNull(f.secrets.get("mcp:platform:header:authorization"))
        // 从没存在过的服务器同样不崩。
        assertFalse(f.registry.remove(ExtensionKind.MCP, "never-existed"))
    }

    @Test
    fun `entry without secrets deletes as a no-op`() {
        val f = fixture()
        val plain = ExtensionEntry(
            id = "plain-stdio",
            kind = ExtensionKind.MCP,
            displayName = "plain-stdio",
            source = "user",
            extra = mapOf("transport" to "stdio", "command" to "npx server-plain"),
        )
        assertEquals(emptySet<String>(), f.sealer.refsFor(plain))
        assertEquals(emptyList<String>(), f.sealer.deleteSecretsFor(plain))
        // 非 MCP 行同样空操作。
        val plugin = plain.copy(id = "local-utils", kind = ExtensionKind.PLUGIN)
        assertEquals(emptyList<String>(), f.sealer.deleteSecretsFor(plugin))
    }

    @Test
    fun `refs outside the server namespace are never deleted`() {
        val secrets = MemorySecrets()
        val sealer = McpSecretSealer(secrets)
        secrets.put("shared-custom-ref", "shared-value")
        secrets.put("mcp:other:header:authorization", "other-value")
        secrets.put("mcp:solo:header:authorization", "solo-value")
        val entry = ExtensionEntry(
            id = "solo",
            kind = ExtensionKind.MCP,
            displayName = "solo",
            source = "user",
            extra = mapOf(
                "transport" to "http",
                "url" to "https://mcp.example.invalid/mcp",
                // 手填的命名空间外引用 + 别台服务器的引用：都不归 solo 所有。
                "headersJson" to JSONObject(
                    mapOf(
                        "Authorization" to "keystore:shared-custom-ref",
                        "X-Other" to "keystore:mcp:other:header:authorization",
                    ),
                ).toString(),
            ),
        )
        assertEquals(emptyList<String>(), sealer.deleteSecretsFor(entry))
        // 本服务器按命名规则生成的槽位已清；共用引用与他人引用原样保留。
        assertNull(secrets.get("mcp:solo:header:authorization"))
        assertEquals("shared-value", secrets.get("shared-custom-ref"))
        assertEquals("other-value", secrets.get("mcp:other:header:authorization"))
    }

    @Test
    fun `refsFor follows the sealing naming rules including embedded bearer refs`() {
        val sealer = McpSecretSealer(MemorySecrets())
        val entry = ExtensionEntry(
            id = "mix",
            kind = ExtensionKind.MCP,
            displayName = "mix",
            source = "user",
            extra = mapOf(
                "transport" to "http",
                "url" to "https://mcp.example.invalid/mcp",
                "headersJson" to JSONObject(
                    mapOf(
                        "Authorization" to "keystore:mcp:mix:header:authorization",
                        "X-Trace" to "plain-trace-id",
                        "X-Api-Key" to "Bearer keystore:mcp:mix:header:x-api-key",
                    ),
                ).toString(),
                "envJson" to JSONObject(
                    mapOf(
                        "GITHUB_TOKEN" to "keystore:mcp:mix:env:GITHUB_TOKEN",
                        "LOG_LEVEL" to "info",
                    ),
                ).toString(),
            ),
        )
        assertEquals(
            setOf(
                "mcp:mix:header:authorization",
                "mcp:mix:header:x-api-key",
                "mcp:mix:env:GITHUB_TOKEN",
            ),
            sealer.refsFor(entry),
        )
    }

    @Test
    fun `a failing credential delete is reported by name and does not stop the rest`() {
        val inner = MemorySecrets()
        val f = fixture(inner)
        // 导入已把凭据封存进 inner；删除时换成对一条引用抛错的包装。
        val sealer = McpSecretSealer(
            FlakyDeleteSecrets(inner, failOn = "mcp:platform:header:authorization"),
        )
        val entry = f.registry.get(ExtensionKind.MCP, "platform")!!

        val failures = sealer.deleteSecretsFor(entry)

        // 失败只报引用名（无值）、不抛；其余凭据照常删掉。
        assertEquals(listOf("mcp:platform:header:authorization"), failures)
        assertEquals(failures, sealer.lastDeleteFailures)
        assertFalse(failures.joinToString().contains(platformToken))
        assertEquals("Bearer $platformToken", inner.get("mcp:platform:header:authorization"))
        assertNull(inner.get("mcp:platform:header:x-api-key"))
    }
}
