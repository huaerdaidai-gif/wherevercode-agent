package wherevercode.agent.core.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 侧统一扩展注册表测试（纯 JVM，内存后端）。
 *
 * 断言：七类齐全；Hand 侧身份（hand-mcp-stdio / healthcheck）
 * 与 Hand 同名同类；启用开关可改且落盘；用户行可注册/删除，
 * 内置行删不掉、id 冲突注册不上。
 */
class ExtensionRegistryTest {

    private fun registry() = ExtensionRegistry(InMemoryExtensionStore())

    @Test
    fun `seven kinds exist with the Hand side ids`() {
        val reg = registry()
        assertEquals(
            setOf("adapter", "connector", "plugin", "mcp", "wakeup", "protocol", "brain"),
            ExtensionKind.values().map { it.id }.toSet(),
        )
        // Hand 侧实测的 id（2026-11-11 VPS `hand_server.py extensions`）：
        assertNotNull(reg.get(ExtensionKind.MCP, "hand-mcp-stdio"))
        assertNotNull(reg.get(ExtensionKind.PLUGIN, "healthcheck"))
        assertNotNull(reg.get(ExtensionKind.ADAPTER, "example-agent"))
        assertNotNull(reg.get(ExtensionKind.PROTOCOL, "mcp"))
        assertNotNull(reg.get(ExtensionKind.BRAIN, "chatgpt-membership"))
        assertNotNull(reg.get(ExtensionKind.WAKEUP, "polling"))
        assertNotNull(reg.get(ExtensionKind.CONNECTOR, "tailscale"))
    }

    @Test
    fun `builtin rows are all enabled by default and local-utils plugin is present`() {
        val reg = registry()
        val plugin = reg.get(ExtensionKind.PLUGIN, "local-utils")!!
        assertEquals("本地小工具", plugin.displayName)
        assertEquals("1", plugin.version)
        assertTrue(plugin.enabled)
        assertEquals("builtin", plugin.source)
        assertTrue(reg.all().all { it.enabled })
    }

    @Test
    fun `hand mcp row carries stdio transport config`() {
        val reg = registry()
        val mcp = reg.get(ExtensionKind.MCP, "hand-mcp-stdio")!!
        assertEquals("stdio", mcp.extra["transport"])
        assertEquals("python3 hand_server.py mcp", mcp.extra["command"])
    }

    @Test
    fun `counts by kind cover all seven kinds`() {
        val counts = registry().countsByKind()
        assertEquals(7, counts.size)
        assertEquals(6, counts[ExtensionKind.ADAPTER])
        assertEquals(2, counts[ExtensionKind.PLUGIN])
        assertEquals(1, counts[ExtensionKind.MCP])
        assertEquals(8, counts[ExtensionKind.BRAIN])
    }

    @Test
    fun `setEnabled persists through the store`() {
        val store = InMemoryExtensionStore()
        val reg = ExtensionRegistry(store)
        assertTrue(reg.setEnabled(ExtensionKind.PLUGIN, "local-utils", false))
        assertFalse(reg.get(ExtensionKind.PLUGIN, "local-utils")!!.enabled)
        // 新实例从同一后端读回：开关还在。
        assertFalse(ExtensionRegistry(store).get(ExtensionKind.PLUGIN, "local-utils")!!.enabled)
        // 改回去。
        assertTrue(reg.setEnabled(ExtensionKind.PLUGIN, "local-utils", true))
        assertTrue(ExtensionRegistry(store).get(ExtensionKind.PLUGIN, "local-utils")!!.enabled)
    }

    @Test
    fun `setEnabled on unknown id returns false`() {
        assertFalse(registry().setEnabled(ExtensionKind.MCP, "no-such-server", false))
    }

    @Test
    fun `user row registers and builtin id conflict is rejected`() {
        val reg = registry()
        val user = ExtensionEntry(
            id = "my-http-server", kind = ExtensionKind.MCP, displayName = "我的 HTTP 服务",
            source = "user", extra = mapOf("transport" to "http", "url" to "http://127.0.0.1:8080/mcp"),
        )
        assertTrue(reg.register(user))
        assertEquals("我的 HTTP 服务", reg.get(ExtensionKind.MCP, "my-http-server")!!.displayName)
        // 同类同 id 再注册不上（即使是内置 id）。
        val clash = user.copy(id = "hand-mcp-stdio")
        assertFalse(reg.register(clash))
        // 非 user 来源的注册不上。
        assertFalse(reg.register(user.copy(id = "other", source = "builtin")))
    }

    @Test
    fun `user row can be removed but builtin row cannot`() {
        val reg = registry()
        val user = ExtensionEntry(
            id = "temp-server", kind = ExtensionKind.MCP, displayName = "临时",
            source = "user", extra = mapOf("transport" to "http", "url" to "http://127.0.0.1:1/"),
        )
        assertTrue(reg.register(user))
        assertTrue(reg.remove(ExtensionKind.MCP, "temp-server"))
        assertNull(reg.get(ExtensionKind.MCP, "temp-server"))
        assertFalse(reg.remove(ExtensionKind.MCP, "hand-mcp-stdio"))
        assertNotNull(reg.get(ExtensionKind.MCP, "hand-mcp-stdio"))
    }

    @Test
    fun `overlay json round trips through SharedPreferences shape`() {
        val store = InMemoryExtensionStore()
        val reg = ExtensionRegistry(store)
        reg.setEnabled(ExtensionKind.MCP, "hand-mcp-stdio", false)
        val saved = store.loadOverlay()
        val entry = saved["mcp"]?.get("hand-mcp-stdio")
        assertNotNull(entry)
        assertFalse(entry!!.enabled)
        // JSON 形态与 Hand extensions.json 同形：version + extensions。
        val json = entry.toJson()
        assertEquals("hand-mcp-stdio", json.getString("id"))
        assertEquals("mcp", json.getString("kind"))
        assertEquals("stdio", json.getJSONObject("extra").getString("transport"))
        val restored = ExtensionEntry.fromJson(json)!!
        assertEquals(entry, restored)
    }

    @Test
    fun `fromJson rejects unknown kind and blank id`() {
        val badKind = ExtensionEntry(
            id = "x", kind = ExtensionKind.MCP, displayName = "x", source = "user",
        ).toJson().apply { put("kind", "no-such-kind") }
        assertNull(ExtensionEntry.fromJson(badKind))
        val blankId = ExtensionEntry(
            id = "x", kind = ExtensionKind.MCP, displayName = "x", source = "user",
        ).toJson().apply { put("id", "") }
        assertNull(ExtensionEntry.fromJson(blankId))
    }
}
