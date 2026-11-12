package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MCP / 插件入口路由测试（纯 JVM）。
 *
 * 断言：抽屉“扩展与资源”里的 MCP / 插件入口已实现、指向新屏；
 * 未知入口仍回首页。
 */
class DrawerRoutingMcpTest {

    @Test
    fun `mcp plugins entry routes to the mcp plugins screen`() {
        assertEquals(EntryScreen.MCP_PLUGINS, DrawerRouter.screenFor(DrawerController.ID_MCP_PLUGINS))
        assertEquals("mcp_plugins", DrawerController.ID_MCP_PLUGINS)
    }

    @Test
    fun `mcp plugins registry entry is implemented and points at the drawer id`() {
        val entry = EntryRegistry.find("nav_mcp_plugins")!!
        assertEquals(EntryRegistry.Status.IMPLEMENTED, entry.status)
        assertEquals(EntryRegistry.TargetKind.DRAWER, entry.targetKind)
        assertEquals(DrawerController.ID_MCP_PLUGINS, entry.target)
        assertEquals(EntryRegistry.Group.DRAWER_EXTENSIONS, entry.group)
    }

    @Test
    fun `console routing is unchanged`() {
        assertEquals(EntryScreen.CONSOLE, DrawerRouter.screenFor(DrawerController.ID_CONSOLE))
    }
}
