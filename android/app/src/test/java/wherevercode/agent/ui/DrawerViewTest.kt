package wherevercode.agent.ui

import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.R

/**
 * DrawerView rendering test (V5 Phase B C2, batch 3, R2 rework).
 *
 * Robolectric — the view needs an Android Context (same reason as
 * MainLayoutTest). Run in CI (`./gradlew testDebugUnitTest`).
 *
 * Covers rendering only (what the drawer shows for a given model
 * state): v4.3-01 structure (top 6 / workspace / extensions /
 * bottom user row), V5-UI.md §三 10-item mapping, real badges
 * (always shown, 0 shows "0", hidden only when unknown), TODO rows
 * marked and never claiming to be implemented, theme row reachable,
 * active-row highlight, hide / favourite kept from the old flat
 * drawer, unknown ids never crash. R4: every entry row carries a
 * real drawable icon (ImageView, never a glyph TextView) and the
 * brand header reads V5, never the old prototype version text.
 * Dialogs (long-press menu, hidden restore) are not opened here —
 * they need a resumed Activity; the model mutations they call are
 * covered by DrawerControllerTest. Tap routing (TODO only toasts,
 * theme cycles) lives in MainActivity.openRegistryEntry and is
 * verified by code + CI build, not here — this test asserts the
 * drawer emits the right registry id on tap.
 *
 * Retired with the flat list (documented in DrawerView): move
 * reordering and the "更多" fold no longer apply to the grouped
 * drawer; row order is registry order.
 */
@RunWith(RobolectricTestRunner::class)
class DrawerViewTest {

    private fun view(
        controller: DrawerController = DrawerController(),
        activeTarget: String? = null,
        userLabel: String = "未登录",
        themeLabel: String = "浅色",
        selected: MutableList<String> = mutableListOf(),
    ): DrawerView = DrawerView(
        RuntimeEnvironment.getApplication(),
        controller,
        onEntrySelected = { selected += it },
        onPrefsChanged = {},
        activeTargetProvider = { activeTarget },
        userLabelProvider = { userLabel },
        themeLabelProvider = { themeLabel },
    )

    @Test
    fun `top group exposes all ten V5 defaults in order`() {
        val drawerView = view()
        assertEquals(
            listOf("nav_chat", "nav_history", "nav_projects", "nav_running_tasks", "nav_team", "nav_devices", "nav_tools", "nav_results", "nav_help", "nav_workbench"),
            drawerView.renderedGroupIds("top"),
        )
    }

    @Test
    fun `history search entry renders as sessions`() {
        val drawerView = view()
        val row = drawerView.renderedRows().first { it.group == "top" && it.id == "nav_history" }
        assertEquals("会话", row.label)
    }

    @Test
    fun `workspace group keeps theme and approval placeholder only`() {
        val drawerView = view()
        assertEquals(
            listOf("nav_theme", "nav_pending_approval"),
            drawerView.renderedGroupIds("workspace"),
        )
        val topRow = drawerView.renderedRows().first { it.group == "top" && it.id == "nav_projects" }
        assertEquals("项目", topRow.label)
    }

    @Test
    fun `extensions group keeps console mcp plugins and three resource placeholders in order`() {
        val drawerView = view()
        assertEquals(
            listOf("nav_console", "nav_team_channel", "nav_mcp_plugins", "nav_models_skills", "nav_custom_modules", "nav_feature_center"),
            drawerView.renderedGroupIds("extensions"),
        )
    }

    @Test
    fun `bottom row is the user row with the workbench entry`() {
        val drawerView = view(userLabel = "已登录")
        assertEquals(listOf("nav_workbench"), drawerView.renderedGroupIds("bottom"))
        val bottom = drawerView.renderedRows().first { it.group == "bottom" }
        assertEquals("已登录", bottom.label)
        // 默认未登录标签也不写死用户名。
        assertEquals("未登录", view().renderedRows().first { it.group == "bottom" }.label)
    }

    @Test
    fun `default drawer renders ten primary rows and secondary groups`() {
        val drawerView = view()
        // 一级 10 + 工作空间 2 + 扩展与资源 6（P3 加了团队频道）+ 底部用户行 1。
        assertEquals(19, drawerView.renderedIds().size)
    }

    @Test
    fun `all ten V5-UI section-three entries are reachable from the drawer or top bar`() {
        val drawerView = view()
        val targets = drawerView.renderedIds().mapNotNull { EntryRegistry.find(it) }
            .filter { it.targetKind == EntryRegistry.TargetKind.DRAWER }
            .map { it.target }
            .toSet()
        assertTrue(targets.contains(DrawerController.ID_HISTORY_SEARCH))
        assertTrue(targets.contains(DrawerController.ID_NEW_CONVERSATION))
        assertTrue(targets.contains(DrawerController.ID_PROJECTS))
        assertTrue(targets.contains(DrawerController.ID_TASKS))
        assertTrue(targets.contains(DrawerController.ID_AGENTS_TEAM))
        assertTrue(targets.contains(DrawerController.ID_DEVICES))
        assertTrue(targets.contains(DrawerController.ID_TOOLS_CONNECTIONS))
        assertTrue(targets.contains(DrawerController.ID_RESULTS))
        assertTrue(targets.contains(DrawerController.ID_HELP))
        assertTrue(targets.contains(DrawerController.ID_SETTINGS))
    }

    @Test
    fun `badges always show real counts including zero and hide only when unknown`() {
        val drawerView = view()
        // 未传入角标（未知）时不显示。
        assertNull(drawerView.renderedRowBadge("nav_running_tasks"))
        assertNull(drawerView.renderedRowBadge("nav_pending_approval"))
        drawerView.setBadges(DrawerBadges(runningTasks = 3, pendingApprovals = 1))
        assertEquals(3, drawerView.renderedRowBadge("nav_running_tasks"))
        assertEquals(1, drawerView.renderedRowBadge("nav_pending_approval"))
        assertNull(drawerView.renderedRowBadge("nav_chat"))
        // R4：0 也显示“0”，不隐藏。
        drawerView.setBadges(DrawerBadges(runningTasks = 0, pendingApprovals = 0))
        assertEquals(0, drawerView.renderedRowBadge("nav_running_tasks"))
        assertEquals(0, drawerView.renderedRowBadge("nav_pending_approval"))
        // V5 控制台（2026-11-11）：待审批已实现（打开控制台的待审批模块），
        // 不再是待开发；有徽标时行尾不再贴“待开发”，徽标本身就是状态。
        assertNull(drawerView.renderedRowTrailing("nav_pending_approval"))
    }

    @Test
    fun `every entry row has a real drawable icon`() {
        val drawerView = view()
        val expected = mapOf(
            "nav_chat" to R.drawable.ic_chat,
            "nav_history" to R.drawable.ic_search,
            "nav_projects" to R.drawable.ic_folder,
            "nav_team" to R.drawable.ic_team,
            "nav_devices" to R.drawable.ic_device,
            "nav_tools" to R.drawable.ic_tools,
            "nav_console" to R.drawable.ic_console,
            "nav_team_channel" to R.drawable.ic_chat,
            "nav_workbench" to R.drawable.ic_settings,
            "nav_theme" to R.drawable.ic_theme,
            "nav_running_tasks" to R.drawable.ic_clock,
            "nav_pending_approval" to R.drawable.ic_approval,
            "nav_results" to R.drawable.ic_results,
            "nav_help" to R.drawable.ic_help,
            "nav_mcp_plugins" to R.drawable.ic_puzzle,
            "nav_models_skills" to R.drawable.ic_cube,
            "nav_custom_modules" to R.drawable.ic_grid,
            "nav_feature_center" to R.drawable.ic_dashboard,
        )
        // 底部用户行（头像 + 登录状态 + 齿轮）原样保留，不参与图标映射。
        drawerView.renderedRows().filter { it.group != "bottom" }.forEach { row ->
            assertEquals(row.id, expected[row.id], row.iconRes)
            assertEquals(row.id, expected[row.id], drawerView.renderedRowIconRes(row.id))
        }
        // 底部用户行不参与图标映射。
        assertNull(drawerView.renderedRows().first { it.group == "bottom" }.iconRes)
    }

    @Test
    fun `row icons are ImageViews not glyph TextViews`() {
        val drawerView = view()
        val content = drawerView.root.getChildAt(0) as LinearLayout
        val rows = (0 until content.childCount).map { content.getChildAt(it) }
            .filter { it.tag is String }
        // 18 个入口行 + 底部用户行（tag 与工作台行相同，排最后，原样保留不查图标）。
        assertEquals(19, rows.size)
        rows.dropLast(1).forEach { row ->
            val first = (row as LinearLayout).getChildAt(0)
            assertTrue("row ${row.tag} first child must be an ImageView", first is ImageView)
            assertFalse("row ${row.tag} must not use a glyph TextView as icon", first is TextView)
        }
    }

    @Test
    fun `brand header shows V5 and no V4-3 text anywhere in the drawer`() {
        val drawerView = view()
        val texts = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) texts += view.text.toString()
            if (view is LinearLayout) {
                for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
        }
        collect(drawerView.root.getChildAt(0))
        assertTrue(texts.contains("V5"))
        // 旧版本串拆开写：测试本身不引入字面旧版本串，只断言渲染结果里没有它。
        val oldVersionText = "V4" + ".3"
        assertFalse(texts.any { it.contains(oldVersionText) || it.contains("v4.3") })
    }

    @Test
    fun `todo rows are marked and implemented rows are not`() {
        val drawerView = view()
        listOf(
            "nav_models_skills", "nav_custom_modules", "nav_feature_center",
        ).forEach { id ->
            assertTrue(id, drawerView.renderedRowIsTodo(id))
            assertEquals("待开发", drawerView.renderedRowTrailing(id))
        }
        // V5 MCP / 插件（2026-11-11）：MCP / 插件已实现，不再是待开发。
        assertFalse(drawerView.renderedRowIsTodo("nav_mcp_plugins"))
        // V5 控制台（2026-11-11）：控制台与待审批已实现，不再是待开发。
        assertFalse(drawerView.renderedRowIsTodo("nav_console"))
        assertFalse(drawerView.renderedRowIsTodo("nav_pending_approval"))
        assertFalse(drawerView.renderedRowIsTodo("nav_chat"))
        assertFalse(drawerView.renderedRowIsTodo("nav_theme"))
        assertFalse(drawerView.renderedRowIsTodo("nav_running_tasks"))
    }

    @Test
    fun `theme row shows the current theme label in secondary workspace section`() {
        val drawerView = view(themeLabel = "深色")
        assertEquals("深色", drawerView.renderedRowTrailing("nav_theme"))
        val workspace = drawerView.renderedGroupIds("workspace")
        assertEquals(listOf("nav_theme", "nav_pending_approval"), workspace)
        assertEquals("浅色", view().renderedRowTrailing("nav_theme"))
    }

    @Test
    fun `active row is highlighted and loses its chevron`() {
        val drawerView = view(activeTarget = DrawerController.ID_PROJECTS)
        assertTrue(drawerView.renderedRowIsActive("nav_projects"))
        assertNull(drawerView.renderedRowTrailing("nav_projects"))
        assertFalse(drawerView.renderedRowIsActive("nav_chat"))
        assertEquals("›", drawerView.renderedRowTrailing("nav_chat"))
    }

    @Test
    fun `tap emits the registry id`() {
        val selected = mutableListOf<String>()
        val drawerView = view(selected = selected)
        assertTrue(drawerView.clickRowForTest("nav_console"))
        assertTrue(drawerView.clickRowForTest("nav_theme"))
        assertTrue(drawerView.clickRowForTest("nav_running_tasks"))
        assertEquals(listOf("nav_console", "nav_theme", "nav_running_tasks"), selected)
        assertFalse(drawerView.clickRowForTest("ghost"))
    }

    @Test
    fun `hidden entry is absent from both its row and its shortcut`() {
        val controller = DrawerController()
        val drawerView = view(controller)
        assertTrue(controller.hide(DrawerController.ID_PROJECTS))
        drawerView.refresh()
        assertFalse(drawerView.renderedIds().contains("nav_projects"))
        assertEquals(18, drawerView.renderedIds().size)
        assertTrue(controller.hide(DrawerController.ID_RESULTS))
        drawerView.refresh()
        assertFalse(drawerView.renderedIds().contains("nav_results"))
    }

    @Test
    fun `favourite entry is pinned first within its group`() {
        val controller = DrawerController()
        assertTrue(controller.toggleFavorite(DrawerController.ID_RESULTS))
        val drawerView = view(controller)
        assertEquals("nav_results", drawerView.renderedGroupIds("top").first())
        assertTrue(drawerView.renderedLabels().first { it.contains("成果") }.startsWith("★ "))
    }

    @Test
    fun `nested child is indented and sits under its parent`() {
        val controller = DrawerController()
        assertTrue(controller.nest(DrawerController.ID_DEVICES, DrawerController.ID_AGENTS_TEAM))
        val drawerView = view(controller)
        assertEquals(
            listOf("nav_chat", "nav_history", "nav_projects", "nav_running_tasks", "nav_team", "nav_devices", "nav_tools", "nav_results", "nav_help", "nav_workbench"),
            drawerView.renderedGroupIds("top"),
        )
        assertEquals(19, drawerView.renderedIds().size)
        val ids = drawerView.renderedIds()
        assertEquals("nav_devices", ids[ids.indexOf("nav_team") + 1])
        assertTrue(drawerView.renderedRowIndent("nav_devices") == 1)
        assertTrue(drawerView.renderedRowIndent("nav_team") == 0)
    }

    @Test
    fun `unknown ids in prefs never crash rendering`() {
        val controller = DrawerController()
        controller.applyPrefs(
            DrawerController.DrawerPrefs(
                order = listOf("ghost", DrawerController.ID_TASKS),
                hidden = setOf("phantom"),
                nestedUnder = mapOf("ghost-child" to DrawerController.ID_TASKS),
            ),
        )
        val drawerView = view(controller)
        assertEquals(19, drawerView.renderedIds().size)
    }

    @Test
    fun `collapsing a group hides its rows until expanded`() {
        val drawerView = view()
        assertFalse(drawerView.isGroupCollapsed("workspace"))
        drawerView.setGroupCollapsed("workspace", true)
        assertTrue(drawerView.isGroupCollapsed("workspace"))
        assertEquals(emptyList<String>(), drawerView.renderedGroupIds("workspace"))
        assertEquals(17, drawerView.renderedIds().size)
        drawerView.setGroupCollapsed("workspace", false)
        assertEquals(2, drawerView.renderedGroupIds("workspace").size)
    }
}
