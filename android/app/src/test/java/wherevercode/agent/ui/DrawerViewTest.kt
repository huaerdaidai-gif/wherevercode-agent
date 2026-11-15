package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
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
 * DrawerView rendering test (V5 导航整改最终定稿, 2026-11-15).
 *
 * 一级菜单严格只有四项：对话、项目、控制。
 * 抽屉中部显示最近会话列表。
 * 底部固定：账号头像（左，→设置）+ ＋ 新对话（右）。
 */
@RunWith(RobolectricTestRunner::class)
class DrawerViewTest {

    private fun view(
        controller: DrawerController = DrawerController(),
        activeTarget: String? = null,
        userLabel: String = "未登录",
        themeLabel: String = "浅色",
        recentSessions: List<DrawerView.RecentSession> = emptyList(),
        selected: MutableList<String> = mutableListOf(),
    ): DrawerView = DrawerView(
        RuntimeEnvironment.getApplication(),
        controller,
        onEntrySelected = { selected += it },
        onPrefsChanged = {},
        activeTargetProvider = { activeTarget },
        userLabelProvider = { userLabel },
        themeLabelProvider = { themeLabel },
        recentSessionsProvider = { recentSessions },
    )

    @Test
    fun `top group exposes exactly four primary entries in order`() {
        val drawerView = view()
        assertEquals(
            listOf("nav_chat", "nav_projects", "nav_channels", "nav_control"),
            drawerView.renderedGroupIds("top"),
        )
    }

    @Test
    fun `top group labels are correct`() {
        val drawerView = view()
        val topRows = drawerView.renderedRows().filter { it.group == "top" }
        assertEquals(4, topRows.size)
        assertEquals("对话", topRows[0].label)
        assertEquals("项目", topRows[1].label)
        assertEquals("频道", topRows[2].label)
        assertEquals("控制", topRows[3].label)
    }

    @Test
    fun `bottom row shows user label and new chat button`() {
        val drawerView = view(userLabel = "已登录")
        val bottom = drawerView.renderedRows().filter { it.group == "bottom" }
        assertEquals(1, bottom.size)
        assertEquals("已登录", bottom[0].label)
        assertEquals("未登录", view().renderedRows().first { it.group == "bottom" }.label)
    }

    @Test
    fun `default drawer renders four primary rows plus bottom bar`() {
        val drawerView = view()
        // 3 个一级入口 + 1 个底部用户行 = 4 行（无会话时不渲染会话行）
        val topAndBottom = drawerView.renderedRows().filter { it.group == "top" || it.group == "bottom" }
        assertEquals(5, topAndBottom.size)
    }

    @Test
    fun `recent sessions are rendered when provided`() {
        val sessions = listOf(
            DrawerView.RecentSession("s1", "开发App", "3分前"),
            DrawerView.RecentSession("s2", "导入项目", "1小时前"),
            DrawerView.RecentSession("s3", "团队协作", "昨天"),
        )
        val drawerView = view(recentSessions = sessions)
        val sessionRows = drawerView.renderedRows().filter { it.group == "sessions" }
        assertEquals(3, sessionRows.size)
        assertEquals("开发App", sessionRows[0].label)
        assertEquals("3分前", sessionRows[0].trailing)
    }

    @Test
    fun `empty sessions show placeholder text`() {
        val drawerView = view(recentSessions = emptyList())
        val content = drawerView.root
        val texts = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) texts += view.text.toString()
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
        }
        collect(content)
        assertTrue(texts.contains("暂无会话记录"))
    }

    @Test
    fun `sessions are limited to eight`() {
        val sessions = (1..15).map {
            DrawerView.RecentSession("s$it", "会话$it", "${it}分前")
        }
        val drawerView = view(recentSessions = sessions)
        val sessionRows = drawerView.renderedRows().filter { it.group == "sessions" }
        assertEquals(8, sessionRows.size)
    }

    @Test
    fun `brand header shows V5 and no old version text`() {
        val drawerView = view()
        val texts = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) texts += view.text.toString()
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
        }
        collect(drawerView.root)
        assertTrue(texts.contains("V5"))
        assertTrue(texts.contains("WhereverCode"))
        val oldVersionText = "V4" + ".3"
        assertFalse(texts.any { it.contains(oldVersionText) || it.contains("v4.3") })
    }

    @Test
    fun `active row is highlighted and loses its chevron`() {
        val drawerView = view(activeTarget = DrawerController.ID_PROJECTS)
        assertTrue(drawerView.renderedRowIsActive("nav_projects"))
        assertFalse(drawerView.renderedRowIsActive("nav_chat"))
        assertEquals("›", drawerView.renderedRowTrailing("nav_chat"))
    }

    @Test
    fun `primary entries have real drawable icons`() {
        val drawerView = view()
        val expected = mapOf(
            "nav_chat" to R.drawable.ic_chat,
            "nav_projects" to R.drawable.ic_folder,
            "nav_channels" to R.drawable.ic_chat,
            "nav_control" to R.drawable.ic_console,
        )
        drawerView.renderedRows().filter { it.group == "top" }.forEach { row ->
            assertEquals(row.id, expected[row.id], row.iconRes)
        }
    }

    @Test
    fun `tap on primary entry emits the registry id`() {
        val selected = mutableListOf<String>()
        val drawerView = view(selected = selected)
        assertTrue(drawerView.clickRowForTest("nav_chat"))
        assertTrue(drawerView.clickRowForTest("nav_projects"))
        assertTrue(drawerView.clickRowForTest("nav_channels"))
        assertTrue(drawerView.clickRowForTest("nav_control"))
        assertEquals(listOf("nav_chat", "nav_projects", "nav_channels", "nav_control"), selected)
        assertFalse(drawerView.clickRowForTest("ghost"))
    }

    @Test
    fun `tap on session row emits session id`() {
        val selected = mutableListOf<String>()
        val sessions = listOf(
            DrawerView.RecentSession("s1", "测试会话", "刚刚"),
        )
        val drawerView = view(recentSessions = sessions, selected = selected)
        assertTrue(drawerView.clickRowForTest("session:s1"))
        assertEquals(listOf("session:s1"), selected)
    }

    @Test
    fun `hidden entry is absent from top group`() {
        val controller = DrawerController()
        val drawerView = view(controller)
        assertTrue(controller.hide(DrawerController.ID_PROJECTS))
        drawerView.refresh()
        assertFalse(drawerView.renderedIds().contains("nav_projects"))
    }

    @Test
    fun `unknown ids in prefs never crash rendering`() {
        val controller = DrawerController()
        controller.applyPrefs(
            DrawerController.DrawerPrefs(
                order = listOf("ghost", DrawerController.ID_CHAT),
                hidden = setOf("phantom"),
                nestedUnder = mapOf("ghost-child" to DrawerController.ID_CHAT),
            ),
        )
        val drawerView = view(controller)
        // 3 primary entries + 1 bottom row = 4 minimum
        assertTrue(drawerView.renderedIds().isNotEmpty())
    }

    @Test
    fun `collapsing sessions group hides session rows`() {
        val sessions = listOf(
            DrawerView.RecentSession("s1", "会话1", "刚刚"),
        )
        val drawerView = view(recentSessions = sessions)
        assertFalse(drawerView.isGroupCollapsed("sessions"))
        assertEquals(1, drawerView.renderedGroupIds("sessions").size)
        drawerView.setGroupCollapsed("sessions", true)
        assertTrue(drawerView.isGroupCollapsed("sessions"))
        assertEquals(0, drawerView.renderedGroupIds("sessions").size)
        drawerView.setGroupCollapsed("sessions", false)
        assertEquals(1, drawerView.renderedGroupIds("sessions").size)
    }

    @Test
    fun `bottom new chat button is present`() {
        val selected = mutableListOf<String>()
        val drawerView = view(selected = selected)
        val content = drawerView.root
        // Find the new chat button by tag
        var found = false
        fun find(view: View) {
            if (view.tag == "nav_new_chat") found = true
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) find(view.getChildAt(i))
            }
        }
        find(content)
        assertTrue("new chat button must be present", found)
        assertTrue(drawerView.clickRowForTest("nav_new_chat"))
        assertTrue(drawerView.clickRowForTest("nav_workbench"))
        assertEquals(listOf("nav_new_chat", "nav_workbench"), selected)
    }
}
