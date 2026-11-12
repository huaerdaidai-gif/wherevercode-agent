package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * V5 Phase B C2（批 2，第 3 步，T5）：抽屉路由表测试（纯 JVM JUnit 4）。
 * 入口 id → 屏幕的映射逐项断言；未知 id 路由到首页，不崩溃。
 * 只断言路由函数返回的屏幕 key，不启动 Activity、不碰 UI。
 */
class DrawerRoutingTest {

    @Test
    fun `all ten default entries route to their screens`() {
        val expected = mapOf(
            DrawerController.ID_NEW_CONVERSATION to EntryScreen.HOME,
            DrawerController.ID_HISTORY_SEARCH to EntryScreen.HISTORY,
            DrawerController.ID_PROJECTS to EntryScreen.PROJECTS,
            DrawerController.ID_TASKS to EntryScreen.TASKS,
            DrawerController.ID_AGENTS_TEAM to EntryScreen.AGENTS,
            DrawerController.ID_DEVICES to EntryScreen.DEVICES,
            DrawerController.ID_TOOLS_CONNECTIONS to EntryScreen.TOOLS,
            DrawerController.ID_RESULTS to EntryScreen.RESULTS,
            DrawerController.ID_HELP to EntryScreen.HELP,
            DrawerController.ID_SETTINGS to EntryScreen.SETTINGS,
        )
        assertEquals(DrawerController.DEFAULT_ORDER.toSet(), expected.keys)
        expected.forEach { (id, screen) ->
            assertEquals("entry $id", screen, DrawerRouter.screenFor(id))
        }
    }

    @Test
    fun `console entry routes to the console workbench`() {
        // V5 控制台（2026-11-11）：抽屉“扩展与资源”分组的 nav_console
        // 指向 DrawerController.ID_CONSOLE，不在 10 项默认一级入口里。
        assertEquals(EntryScreen.CONSOLE, DrawerRouter.screenFor(DrawerController.ID_CONSOLE))
        assertFalse(DrawerController.DEFAULT_ORDER.contains(DrawerController.ID_CONSOLE))
    }

    @Test
    fun `unknown entry id routes home`() {
        assertEquals(EntryScreen.HOME, DrawerRouter.screenFor("no_such_entry"))
        assertEquals(EntryScreen.HOME, DrawerRouter.screenFor(""))
    }

    @Test
    fun `devices and agents route distinctly although they share one root view`() {
        // 设备入口与 Agent 入口共用 screenAgents 根视图（showEntry 内处理），
        // 但路由表本身必须区分两者，以便设备入口附加“滚动到设备区段”。
        assertEquals(EntryScreen.DEVICES, DrawerRouter.screenFor(DrawerController.ID_DEVICES))
        assertEquals(EntryScreen.AGENTS, DrawerRouter.screenFor(DrawerController.ID_AGENTS_TEAM))
    }
}
