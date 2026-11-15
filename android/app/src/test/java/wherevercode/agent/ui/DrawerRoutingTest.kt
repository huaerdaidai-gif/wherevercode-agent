package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉路由表测试（V5 导航整改最终定稿，2026-11-15）。
 *
 * 一级菜单只有四项：对话、项目、控制。
 * 旧入口 id 保留兼容路由。
 */
class DrawerRoutingTest {

    @Test
    fun `four primary entries route to their screens`() {
        val expected = mapOf(
            DrawerController.ID_CHAT to EntryScreen.HOME,
            DrawerController.ID_PROJECTS to EntryScreen.PROJECTS,
            DrawerController.ID_CHANNELS to EntryScreen.CHANNELS,
            DrawerController.ID_CONTROL to EntryScreen.CONSOLE,
        )
        assertEquals(DrawerController.DEFAULT_ORDER.toSet(), expected.keys)
        expected.forEach { (id, screen) ->
            assertEquals("entry $id", screen, DrawerRouter.screenFor(id))
        }
    }

    @Test
    fun `old entry ids still route correctly for backward compatibility`() {
        assertEquals(EntryScreen.HOME, DrawerRouter.screenFor(DrawerController.ID_NEW_CONVERSATION))
        assertEquals(EntryScreen.HISTORY, DrawerRouter.screenFor(DrawerController.ID_HISTORY_SEARCH))
        assertEquals(EntryScreen.TASKS, DrawerRouter.screenFor(DrawerController.ID_TASKS))
        assertEquals(EntryScreen.AGENTS, DrawerRouter.screenFor(DrawerController.ID_AGENTS_TEAM))
        assertEquals(EntryScreen.DEVICES, DrawerRouter.screenFor(DrawerController.ID_DEVICES))
        assertEquals(EntryScreen.TOOLS, DrawerRouter.screenFor(DrawerController.ID_TOOLS_CONNECTIONS))
        assertEquals(EntryScreen.RESULTS, DrawerRouter.screenFor(DrawerController.ID_RESULTS))
        assertEquals(EntryScreen.HELP, DrawerRouter.screenFor(DrawerController.ID_HELP))
        assertEquals(EntryScreen.SETTINGS, DrawerRouter.screenFor(DrawerController.ID_SETTINGS))
        assertEquals(EntryScreen.CONSOLE, DrawerRouter.screenFor(DrawerController.ID_CONSOLE))
    }

    @Test
    fun `console entry routes to console and control is in default order`() {
        assertEquals(EntryScreen.CONSOLE, DrawerRouter.screenFor(DrawerController.ID_CONSOLE))
        assertEquals(EntryScreen.CONSOLE, DrawerRouter.screenFor(DrawerController.ID_CONTROL))
        assertTrue(DrawerController.DEFAULT_ORDER.contains(DrawerController.ID_CONTROL))
        assertFalse(DrawerController.DEFAULT_ORDER.contains(DrawerController.ID_CONSOLE))
    }

    @Test
    fun `unknown entry id routes home`() {
        assertEquals(EntryScreen.HOME, DrawerRouter.screenFor("no_such_entry"))
        assertEquals(EntryScreen.HOME, DrawerRouter.screenFor(""))
    }

    @Test
    fun `devices and agents route distinctly`() {
        assertEquals(EntryScreen.DEVICES, DrawerRouter.screenFor(DrawerController.ID_DEVICES))
        assertEquals(EntryScreen.AGENTS, DrawerRouter.screenFor(DrawerController.ID_AGENTS_TEAM))
    }

    @Test
    fun `settings is not in default order but still routes`() {
        assertFalse(DrawerController.DEFAULT_ORDER.contains(DrawerController.ID_SETTINGS))
        assertEquals(EntryScreen.SETTINGS, DrawerRouter.screenFor(DrawerController.ID_SETTINGS))
    }
}
