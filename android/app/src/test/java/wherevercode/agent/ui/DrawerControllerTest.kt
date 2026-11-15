package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import wherevercode.agent.ui.DrawerController.DrawerEntry

/**
 * 抽屉模型测试（V5 导航整改最终定稿，2026-11-15）。
 *
 * 一级菜单严格只有三项：对话、项目、控制。
 * 旧入口 id 保留为路由常量，但不再出现在 DEFAULT_ORDER 中。
 * 纯 JVM 测试，不依赖 Android。
 */
class DrawerControllerTest {

    private fun ids(controller: DrawerController): List<String> =
        controller.visibleTopLevel().map { it.id }

    @Test
    fun `default entries are exactly the three V5 primary entries in order`() {
        val entries = DrawerController.defaultEntries()

        assertEquals(3, entries.size)
        assertEquals(DrawerController.DEFAULT_ORDER, entries.map { it.id })
        assertEquals(
            listOf("对话", "项目", "控制"),
            entries.map { it.label },
        )
        assertTrue(entries.all { it.isBasic })
        assertTrue(entries.all { it.children.isEmpty() })
    }

    @Test
    fun `visible top level defaults to all three in order`() {
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
        assertEquals(2, controller.visibleTopLevel().size)
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
        assertFalse(controller.nest("no_such_entry", DrawerController.ID_CONTROL))
        assertFalse(controller.nest(DrawerController.ID_CHAT, "no_such_entry"))
        assertFalse(controller.unnest("no_such_entry"))
        assertEquals(DrawerController.DEFAULT_ORDER, ids(controller))
    }

    @Test
    fun `move reorders top level and clamps out of range index`() {
        val controller = DrawerController()

        assertTrue(controller.move(DrawerController.ID_CONTROL, 0))
        assertEquals(DrawerController.ID_CONTROL, ids(controller).first())
        assertEquals(3, ids(controller).size)

        assertTrue(controller.move(DrawerController.ID_CHAT, 99))
        assertEquals(DrawerController.ID_CHAT, ids(controller).last())

        assertTrue(controller.move(DrawerController.ID_CHAT, -5))
        assertEquals(DrawerController.ID_CHAT, ids(controller).first())
    }

    @Test
    fun `favorite pins to front preserving custom order among favorites`() {
        val controller = DrawerController()

        controller.toggleFavorite(DrawerController.ID_PROJECTS)
        controller.toggleFavorite(DrawerController.ID_CHAT)

        val visible = ids(controller)
        assertEquals(DrawerController.ID_CHAT, visible[0])
        assertEquals(DrawerController.ID_PROJECTS, visible[1])
        assertEquals(3, visible.size)

        controller.toggleFavorite(DrawerController.ID_PROJECTS)
        controller.toggleFavorite(DrawerController.ID_CHAT)
        assertEquals(DrawerController.DEFAULT_ORDER, ids(controller))
    }

    @Test
    fun `favorite does not resurrect a hidden entry`() {
        val controller = DrawerController()

        controller.hide(DrawerController.ID_CONTROL)
        controller.toggleFavorite(DrawerController.ID_CONTROL)

        assertTrue(controller.isFavorite(DrawerController.ID_CONTROL))
        assertFalse(DrawerController.ID_CONTROL in ids(controller))
    }

    @Test
    fun `nest moves child out of top level into parent children`() {
        val controller = DrawerController()

        assertTrue(controller.nest(DrawerController.ID_CONTROL, DrawerController.ID_PROJECTS))
        assertFalse(DrawerController.ID_CONTROL in ids(controller))
        assertEquals(
            listOf(DrawerController.ID_CONTROL),
            controller.childrenOf(DrawerController.ID_PROJECTS).map { it.id },
        )
        assertTrue(controller.isNested(DrawerController.ID_CONTROL))
        assertEquals(2, controller.visibleTopLevel().size)

        assertTrue(controller.unnest(DrawerController.ID_CONTROL))
        assertFalse(controller.isNested(DrawerController.ID_CONTROL))
        assertEquals(DrawerController.ID_CONTROL, ids(controller).last())
    }

    @Test
    fun `nest rejects self nesting and cycles`() {
        val controller = DrawerController()

        assertFalse(controller.nest(DrawerController.ID_CHAT, DrawerController.ID_CHAT))

        assertTrue(controller.nest(DrawerController.ID_CONTROL, DrawerController.ID_PROJECTS))
        assertFalse(controller.nest(DrawerController.ID_PROJECTS, DrawerController.ID_CONTROL))
        assertFalse(controller.isNested(DrawerController.ID_PROJECTS))
    }

    @Test
    fun `move of a nested entry is a no-op`() {
        val controller = DrawerController()

        assertTrue(controller.nest(DrawerController.ID_CONTROL, DrawerController.ID_PROJECTS))
        assertFalse(controller.move(DrawerController.ID_CONTROL, 0))
        assertFalse(DrawerController.ID_CONTROL in ids(controller))
    }

    @Test
    fun `hiding a parent hides its children too`() {
        val controller = DrawerController()

        controller.nest(DrawerController.ID_CONTROL, DrawerController.ID_PROJECTS)
        controller.hide(DrawerController.ID_PROJECTS)

        assertTrue(controller.childrenOf(DrawerController.ID_PROJECTS).isEmpty())
        assertTrue(controller.isNested(DrawerController.ID_CONTROL))
        assertTrue(controller.show(DrawerController.ID_PROJECTS))
        assertEquals(
            listOf(DrawerController.ID_CONTROL),
            controller.childrenOf(DrawerController.ID_PROJECTS).map { it.id },
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
        assertEquals(3, controller.visibleTopLevel().size)
    }

    @Test
    fun `duplicate entry registration is rejected`() {
        val controller = DrawerController()

        try {
            controller.addCustomEntry(
                DrawerEntry(id = DrawerController.ID_CHAT, label = "对话（重复）"),
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
        controller.move(DrawerController.ID_CONTROL, 0)
        controller.hide(DrawerController.ID_PROJECTS)
        controller.toggleFavorite(DrawerController.ID_CHAT)
        controller.nest(DrawerController.ID_PROJECTS, DrawerController.ID_CONTROL)
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
            nestedUnder = mapOf("no_such_entry" to DrawerController.ID_CHAT),
        )

        controller.applyPrefs(prefs)

        assertEquals(DrawerController.DEFAULT_ORDER, ids(controller))
    }

    @Test
    fun `reset restores the three defaults`() {
        val controller = DrawerController()
        controller.move(DrawerController.ID_CONTROL, 0)
        controller.hide(DrawerController.ID_PROJECTS)
        controller.toggleFavorite(DrawerController.ID_CHAT)
        controller.nest(DrawerController.ID_PROJECTS, DrawerController.ID_CONTROL)
        controller.setMoreExpanded(true)

        controller.reset()

        assertEquals(DrawerController.DEFAULT_ORDER, ids(controller))
        assertTrue(controller.moreEntries().isEmpty())
        assertFalse(controller.moreExpanded)
        assertFalse(controller.isFavorite(DrawerController.ID_CHAT))
        assertFalse(controller.isNested(DrawerController.ID_PROJECTS))
    }

    @Test
    fun `old entry ids are preserved as routing constants`() {
        // 旧入口 id 保留为路由常量，不在 DEFAULT_ORDER 中。
        assertTrue(DrawerController.ID_NEW_CONVERSATION !in DrawerController.DEFAULT_ORDER)
        assertTrue(DrawerController.ID_HISTORY_SEARCH !in DrawerController.DEFAULT_ORDER)
        assertTrue(DrawerController.ID_TASKS !in DrawerController.DEFAULT_ORDER)
        assertTrue(DrawerController.ID_AGENTS_TEAM !in DrawerController.DEFAULT_ORDER)
        assertTrue(DrawerController.ID_DEVICES !in DrawerController.DEFAULT_ORDER)
        assertTrue(DrawerController.ID_TOOLS_CONNECTIONS !in DrawerController.DEFAULT_ORDER)
        assertTrue(DrawerController.ID_RESULTS !in DrawerController.DEFAULT_ORDER)
        assertTrue(DrawerController.ID_HELP !in DrawerController.DEFAULT_ORDER)
        assertTrue(DrawerController.ID_SETTINGS !in DrawerController.DEFAULT_ORDER)
    }
}
