package wherevercode.agent.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 会话列表控制器（阶段 3，U5/批 3c；Robolectric，CI 跑）：
 * 默认会话行 + 具名会话 + 历史对话分区 + 空态提示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class SessionListControllerTest {

    private fun controller(opened: MutableList<String>): SessionListController {
        val context = RuntimeEnvironment.getApplication()
        return SessionListController(
            context,
            onOpenDefault = { opened += "default" },
            onOpenSession = { id, _ -> opened += id },
            onNewConversation = { opened += "new" },
        )
    }

    @Test
    fun `renders default row named sessions and history section`() {
        val opened = mutableListOf<String>()
        val controller = controller(opened)
        controller.render(
            "当前：WhereverCode会话",
            listOf(
                SessionListController.SessionListItem(
                    "s-1", "CodeBuddy", "私聊", "成员 2 · 更新 t", false,
                ),
                SessionListController.SessionListItem(
                    "chat:abc", "chat:abc", "私聊", "成员 2 · 更新 t", true,
                ),
            ),
        )
        val text = collectText(controller.root)
        assertTrue(text.contains("WhereverCode会话"))
        assertTrue(text.contains("CodeBuddy"))
        assertTrue(text.contains("历史对话"))
        assertTrue(text.contains("chat:abc"))
        assertTrue(text.contains("＋ 新对话"))
    }

    @Test
    fun `empty list shows the empty hint`() {
        val controller = controller(mutableListOf())
        controller.render("当前：WhereverCode会话", emptyList())
        assertTrue(collectText(controller.root).contains("还没有其他会话"))
    }

    @Test
    fun `history read error stays distinct from an empty history`() {
        val controller = controller(mutableListOf())
        controller.renderError("读取会话失败。诊断码：IllegalStateException")
        assertTrue(collectText(controller.root).contains("读取会话失败"))
        assertFalse(collectText(controller.root).contains("还没有其他会话"))
        controller.searchField.setText("anything")
        assertTrue(collectText(controller.root).contains("读取会话失败"))
    }

    @Test
    fun `search box is present with the boundary note`() {
        val controller = controller(mutableListOf())
        controller.render("当前：WhereverCode会话", emptyList())
        assertEquals("搜索会话名、类型或任务…", controller.searchField.hint.toString())
        assertTrue(collectText(controller.root).contains("只搜会话名、类型或任务，不搜消息正文。"))
    }

    @Test
    fun `typing a query filters rows but keeps default row and new conversation`() {
        val controller = controller(mutableListOf())
        controller.render(
            "当前：WhereverCode会话",
            listOf(
                SessionListController.SessionListItem(
                    "s-1", "CodeBuddy", "私聊", "成员 2 · 更新 t", false,
                ),
                SessionListController.SessionListItem(
                    "s-2", "任务讨论", "任务", "成员 3 · 更新 t · 任务 task-1", false,
                ),
                SessionListController.SessionListItem(
                    "chat:abc", "chat:abc", "私聊", "成员 2 · 更新 t", true,
                ),
            ),
        )
        controller.searchField.setText("任务")
        val text = collectText(controller.root)
        assertTrue(text.contains("任务讨论"))
        assertTrue(text.contains("会话（1）"))
        assertFalse(text.contains("CodeBuddy"))
        assertFalse(text.contains("chat:abc"))
        assertFalse(text.contains("历史对话"))
        // WhereverCode会话行与“＋ 新对话”按钮不受过滤影响，始终显示。
        assertTrue(text.contains("WhereverCode会话"))
        assertTrue(text.contains("＋ 新对话"))
    }

    @Test
    fun `no match shows the empty state echoing the query`() {
        val controller = controller(mutableListOf())
        controller.render(
            "当前：WhereverCode会话",
            listOf(
                SessionListController.SessionListItem(
                    "s-1", "CodeBuddy", "私聊", "成员 2 · 更新 t", false,
                ),
            ),
        )
        controller.searchField.setText("zzz-no-match")
        val text = collectText(controller.root)
        assertTrue(text.contains("没有匹配“zzz-no-match”的会话。"))
        assertFalse(text.contains("CodeBuddy"))
        assertTrue(text.contains("WhereverCode会话"))
    }

    @Test
    fun `typing keeps focus and text because the search field is never rebuilt`() {
        val controller = controller(mutableListOf())
        controller.render(
            "当前：WhereverCode会话",
            listOf(
                SessionListController.SessionListItem(
                    "s-1", "CodeBuddy", "私聊", "成员 2 · 更新 t", false,
                ),
            ),
        )
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(controller.root)
        val fieldBefore = controller.searchField
        controller.searchField.requestFocus()
        controller.searchField.setText("code")
        // 每次按键只重建列表段：搜索框是同一个实例，焦点与文本都在。
        assertSame(fieldBefore, controller.searchField)
        assertTrue(controller.searchField.hasFocus())
        assertEquals("code", controller.searchField.text.toString())
        assertEquals("code", controller.currentQuery())
        assertTrue(collectText(controller.root).contains("CodeBuddy"))
    }

    @Test
    fun `re-render with fresh data keeps the query and the filter`() {
        val controller = controller(mutableListOf())
        val items = listOf(
            SessionListController.SessionListItem(
                "s-1", "CodeBuddy", "私聊", "成员 2 · 更新 t", false,
            ),
            SessionListController.SessionListItem(
                "s-2", "任务讨论", "任务", "成员 3 · 更新 t · 任务 task-1", false,
            ),
        )
        controller.render("当前：WhereverCode会话", items)
        controller.searchField.setText("任务")
        controller.render("当前：WhereverCode会话", items)
        val text = collectText(controller.root)
        assertEquals("任务", controller.currentQuery())
        assertEquals("任务", controller.searchField.text.toString())
        assertTrue(text.contains("任务讨论"))
        assertFalse(text.contains("CodeBuddy"))
    }

    private fun collectText(view: View): String {
        val sb = StringBuilder()
        fun walk(v: View) {
            if (v is TextView) sb.append(v.text.toString()).append('\n')
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(view)
        return sb.toString()
    }
}
