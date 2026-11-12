package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import wherevercode.agent.ui.DrawerController.DrawerEntry

/**
 * 抽屉模型测试（Phase B C2，骨架）。
 *
 * 依据：V5-UI.md §三——默认一级入口 10 项；可隐藏/排序/收藏/嵌套；
 * 非基础能力折叠到“更多”。
 * 纯 JVM 测试，不依赖 Android。
 */
class DrawerControllerTest {

    private fun ids(controller: DrawerController): List<String> =
        controller.visibleTopLevel().map { it.id }

    @Test
    fun `default entries are exactly the ten V5 entries in order`() {
        val entries = DrawerController.defaultEntries()

        assertEquals(10, entries.size)
        assertEquals(DrawerController.DEFAULT_ORDER, entries.map { it.id })
        assertEquals(
            listOf(
                "新对话", "会话", "项目", "任务", "Agent 与团队",
                "设备", "工具与连接", "成果", "帮助", "设置",
            ),
            entries.map { it.label },
        )
        assertTrue(entries.all { it.isBasic })
        assertTrue(entries.all { it.children.isEmpty() })
    }

    @Test
    fun `visible top level defaults to all ten in order`() {
        val controller = DrawerController()

        assertEquals(DrawerController.DEFAULT_ORDER, ids(controller))
        assertTrue(controller.moreEntries().isEmpty())
        assertFalse(controller.moreExpanded)
    }

    @Test
    fun `hide removes from visible top level and show restores it`() {
        val controller = DrawerController()

        assertTrue(controller.hide(DrawerController.ID_PROJECTS))
        assertFalse(DrawerController.ID_PROJECTS in ids(controller))
        assertEquals(9, controller.visibleTopLevel().size)
        // 重复隐藏是无操作。
        assertFalse(controller.hide(DrawerController.ID_PROJECTS))

        assertTrue(controller.show(DrawerController.ID_PROJECTS))
        assertEquals(DrawerController.DEFAULT_ORDER, ids(controller))
        assertFalse(controller.show(DrawerController.ID_PROJECTS))
    }

    @Test
    fun `hide show favorite move nest on unknown id are no-ops`() {
        val controller = DrawerController()

        assertFalse(controller.hide("no_such_entry"))
        assertFalse(controller.show("no_such_entry"))
        assertFalse(controller.toggleFavorite("no_such_entry"))
        assertFalse(controller.move("no_such_entry", 0))
        assertFalse(controller.nest("no_such_entry", DrawerController.ID_SETTINGS))
        assertFalse(controller.nest(DrawerController.ID_HELP, "no_such_entry"))
        assertFalse(controller.unnest("no_such_entry"))
        assertEquals(DrawerController.DEFAULT_ORDER, ids(controller))
    }

    @Test
    fun `move reorders top level and clamps out of range index`() {
        val controller = DrawerController()

        assertTrue(controller.move(DrawerController.ID_SETTINGS, 0))
        assertEquals(DrawerController.ID_SETTINGS, ids(controller).first())
        assertEquals(10, ids(controller).size)

        assertTrue(controller.move(DrawerController.ID_NEW_CONVERSATION, 99))
        assertEquals(DrawerController.ID_NEW_CONVERSATION, ids(controller).last())

        assertTrue(controller.move(DrawerController.ID_NEW_CONVERSATION, -5))
        assertEquals(DrawerController.ID_NEW_CONVERSATION, ids(controller).first())
    }

    @Test
    fun `favorite pins to front preserving custom order among favorites`() {
        val controller = DrawerController()

        controller.toggleFavorite(DrawerController.ID_TASKS)
        controller.toggleFavorite(DrawerController.ID_PROJECTS)

        val visible = ids(controller)
        assertEquals(DrawerController.ID_PROJECTS, visible[0])
        assertEquals(DrawerController.ID_TASKS, visible[1])
        assertEquals(10, visible.size)

        // 取消收藏后恢复默认顺序。
        controller.toggleFavorite(DrawerController.ID_TASKS)
        controller.toggleFavorite(DrawerController.ID_PROJECTS)
        assertEquals(DrawerController.DEFAULT_ORDER, ids(controller))
    }

    @Test
    fun `favorite does not resurrect a hidden entry`() {
        val controller = DrawerController()

        controller.hide(DrawerController.ID_DEVICES)
        controller.toggleFavorite(DrawerController.ID_DEVICES)

        assertTrue(controller.isFavorite(DrawerController.ID_DEVICES))
        assertFalse(DrawerController.ID_DEVICES in ids(controller))
    }

    @Test
    fun `nest moves child out of top level into parent children`() {
        val controller = DrawerController()

        assertTrue(controller.nest(DrawerController.ID_HELP, DrawerController.ID_SETTINGS))
        assertFalse(DrawerController.ID_HELP in ids(controller))
        assertEquals(
            listOf(DrawerController.ID_HELP),
            controller.childrenOf(DrawerController.ID_SETTINGS).map { it.id },
        )
        assertTrue(controller.isNested(DrawerController.ID_HELP))
        assertEquals(9, controller.visibleTopLevel().size)

        // 取消嵌套后回到一级末尾。
        assertTrue(controller.unnest(DrawerController.ID_HELP))
        assertFalse(controller.isNested(DrawerController.ID_HELP))
        assertEquals(DrawerController.ID_HELP, ids(controller).last())
    }

    @Test
    fun `nest rejects self nesting and cycles`() {
        val controller = DrawerController()

        assertFalse(controller.nest(DrawerController.ID_HELP, DrawerController.ID_HELP))

        assertTrue(controller.nest(DrawerController.ID_HELP, DrawerController.ID_SETTINGS))
        // SETTINGS 已是 HELP 的父入口，再把 SETTINGS 嵌到 HELP 下会成环。
        assertFalse(controller.nest(DrawerController.ID_SETTINGS, DrawerController.ID_HELP))
        assertFalse(controller.isNested(DrawerController.ID_SETTINGS))
    }

    @Test
    fun `move of a nested entry is a no-op`() {
        val controller = DrawerController()

        assertTrue(controller.nest(DrawerController.ID_HELP, DrawerController.ID_SETTINGS))
        assertFalse(controller.move(DrawerController.ID_HELP, 0))
        assertFalse(DrawerController.ID_HELP in ids(controller))
    }

    @Test
    fun `hiding a parent hides its children too`() {
        val controller = DrawerController()

        controller.nest(DrawerController.ID_HELP, DrawerController.ID_SETTINGS)
        controller.hide(DrawerController.ID_SETTINGS)

        assertTrue(controller.childrenOf(DrawerController.ID_SETTINGS).isEmpty())
        assertTrue(controller.isNested(DrawerController.ID_HELP))
        assertTrue(controller.show(DrawerController.ID_SETTINGS))
        assertEquals(
            listOf(DrawerController.ID_HELP),
            controller.childrenOf(DrawerController.ID_SETTINGS).map { it.id },
        )
    }

    @Test
    fun `non-basic entry folds into more not top level`() {
        val controller = DrawerController()

        controller.addCustomEntry(
            DrawerEntry(id = "matrix", label = "Matrix 事件流", isBasic = false),
        )

        assertFalse("matrix" in ids(controller))
        assertEquals(listOf("matrix"), controller.moreEntries().map { it.id })
        assertEquals(10, controller.visibleTopLevel().size)
    }

    @Test
    fun `duplicate entry registration is rejected`() {
        val controller = DrawerController()

        try {
            controller.addCustomEntry(
                DrawerEntry(id = DrawerController.ID_SETTINGS, label = "设置（重复）"),
            )
            fail("duplicate registration must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
        assertEquals(DrawerController.DEFAULT_ORDER, ids(controller))
    }

    @Test
    fun `more expanded flag defaults folded and can be set`() {
        val controller = DrawerController()

        assertFalse(controller.moreExpanded)
        controller.setMoreExpanded(true)
        assertTrue(controller.moreExpanded)
        controller.setMoreExpanded(false)
        assertFalse(controller.moreExpanded)
    }

    @Test
    fun `prefs round trip preserves order hidden favorites and nesting`() {
        val controller = DrawerController()
        controller.move(DrawerController.ID_SETTINGS, 0)
        controller.hide(DrawerController.ID_DEVICES)
        controller.toggleFavorite(DrawerController.ID_TASKS)
        controller.nest(DrawerController.ID_HELP, DrawerController.ID_SETTINGS)
        controller.setMoreExpanded(true)

        val prefs = controller.toPrefs()
        val restored = DrawerController()
        restored.applyPrefs(prefs)

        assertEquals(ids(controller), ids(restored))
        assertEquals(controller.toPrefs(), restored.toPrefs())
    }

    @Test
    fun `applyPrefs drops unknown ids instead of crashing`() {
        val controller = DrawerController()
        val prefs = DrawerController.DrawerPrefs(
            order = listOf("no_such_entry") + DrawerController.DEFAULT_ORDER,
            hidden = setOf("no_such_entry"),
            favorites = setOf("no_such_entry"),
            nestedUnder = mapOf("no_such_entry" to DrawerController.ID_SETTINGS),
        )

        controller.applyPrefs(prefs)

        assertEquals(DrawerController.DEFAULT_ORDER, ids(controller))
    }

    @Test
    fun `reset restores the ten defaults`() {
        val controller = DrawerController()
        controller.move(DrawerController.ID_SETTINGS, 0)
        controller.hide(DrawerController.ID_DEVICES)
        controller.toggleFavorite(DrawerController.ID_TASKS)
        controller.nest(DrawerController.ID_HELP, DrawerController.ID_SETTINGS)
        controller.setMoreExpanded(true)

        controller.reset()

        assertEquals(DrawerController.DEFAULT_ORDER, ids(controller))
        assertTrue(controller.moreEntries().isEmpty())
        assertFalse(controller.moreExpanded)
        assertFalse(controller.isFavorite(DrawerController.ID_TASKS))
        assertFalse(controller.isNested(DrawerController.ID_HELP))
    }
}
