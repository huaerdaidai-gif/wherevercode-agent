package wherevercode.agent.ui

import android.view.View
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

/**
 * DrawerView rendering test for converged 4-parent drawer structure.
 */
@RunWith(RobolectricTestRunner::class)
class DrawerViewTest {

    private fun view(
        controller: DrawerController = DrawerController(),
        activeTarget: String? = null,
        userLabel: String = "未登录",
        themeLabel: String = "浅色",
        recentSessions: List<DrawerView.SessionItem> = emptyList(),
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
    fun `four parent menus are rendered in order`() {
        val drawerView = view()
        assertEquals(
            listOf("nav_chat", "nav_projects", "nav_channels", "nav_control"),
            drawerView.renderedGroupIds("parent"),
        )
    }

    @Test
    fun `recent conversations are rendered from provider`() {
        val sessions = listOf(
            DrawerView.SessionItem("s1", "测试会话 1"),
            DrawerView.SessionItem("s2", "测试会话 2"),
        )
        val drawerView = view(recentSessions = sessions)
        val recentIds = drawerView.renderedGroupIds("recent")
        assertEquals(listOf("session_s1", "session_s2"), recentIds)
    }

    @Test
    fun `bottom row is the user row with workbench entry`() {
        val drawerView = view(userLabel = "已登录")
        assertEquals(listOf("settings"), drawerView.renderedGroupIds("bottom"))
        val bottom = drawerView.renderedRows().first { it.group == "bottom" }
        assertEquals("已登录", bottom.label)
        assertEquals("未登录", view().renderedRows().first { it.group == "bottom" }.label)
    }

    @Test
    fun `feature center entry is present at bottom`() {
        val drawerView = view()
        val featureCenter = drawerView.renderedRows().firstOrNull { it.group == "feature_center" }
        assertTrue(featureCenter != null)
        assertEquals("nav_feature_center", featureCenter?.id)
        assertTrue(featureCenter?.label?.contains("全部功能") == true)
        assertTrue(featureCenter?.label?.contains("89") == true)
    }

    @Test
    fun `badges show counts for control`() {
        val drawerView = view()
        drawerView.setBadges(DrawerBadges(runningTasks = 3, pendingApprovals = 1))
        assertEquals(4, drawerView.renderedRowBadge("nav_control"))
    }

    @Test
    fun `brand header displays WhereverCode only`() {
        val drawerView = view()
        val texts = mutableListOf<String>()
        fun collect(view: View) {
            if (view is TextView) texts += view.text.toString()
            if (view is LinearLayout) {
                for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
        }
        collect(drawerView.root.getChildAt(0))
        // 双端统一（批A A4）：标题只留 WhereverCode，不带 Agent 后缀与版本串
        // （版本在「设置 → 关于」）；iOS 抽屉标题同为 WhereverCode。
        assertTrue(texts.any { it.trim() == "WhereverCode" })
        assertFalse(texts.any { it.contains("WhereverCode Agent") })
    }

    @Test
    fun `active row is highlighted and parent rows have no chevron`() {
        val drawerView = view(activeTarget = DrawerController.ID_PROJECTS)
        assertTrue(drawerView.renderedRowIsActive("nav_projects"))
        assertNull(drawerView.renderedRowTrailing("nav_projects"))
        assertFalse(drawerView.renderedRowIsActive("nav_chat"))
        // 双端统一（批A A3）：四父级行一律无 ›，与 iOS 一致（旧口径是
        // 非选中行带 ›，已废止）。
        assertNull(drawerView.renderedRowTrailing("nav_chat"))
    }

    @Test
    fun `tap emits registry id`() {
        val selected = mutableListOf<String>()
        val drawerView = view(selected = selected)
        assertTrue(drawerView.clickRowForTest("nav_control"))
        assertTrue(drawerView.clickRowForTest("nav_projects"))
        assertTrue(drawerView.clickRowForTest("nav_feature_center"))
        assertEquals(listOf("nav_control", "nav_projects", "nav_feature_center"), selected)
    }
}
