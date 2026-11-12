package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 失败测试（maintainer 2026-11-10 07:46 “无法删除”）：会话列表每行必须有删除入口，
 * 点删除行回调带会话 id；默认会话行也能删当前默认对话。
 * Robolectric，CI 跑；零真实调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class SessionListControllerDeleteTest {

    private fun deleteViews(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(view: View) {
            if (view is TextView && view.text?.toString() == "删除") out += view
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    @Test
    fun `each session row has a delete entry that reports its session id`() {
        val context = RuntimeEnvironment.getApplication()
        val deleted = mutableListOf<String>()
        var defaultDeleted = 0
        val controller = SessionListController(
            context,
            onOpenDefault = {},
            onOpenSession = { _, _ -> },
            onNewConversation = {},
            onDeleteSession = { id, _ -> deleted += id },
            onDeleteDefault = { defaultDeleted += 1 },
        )
        controller.render(
            "当前：WhereverCode会话",
            listOf(
                SessionListController.SessionListItem(
                    "s-1", "CodeBuddy", "私聊", "成员 2 · 更新 t", false,
                ),
            ),
        )
        // 默认行 + 1 条会话行 = 2 个删除入口。
        val deletes = deleteViews(controller.root)
        assertEquals(2, deletes.size)
        deletes[1].performClick()
        assertEquals(listOf("s-1"), deleted)
        assertEquals(0, defaultDeleted)
        deletes[0].performClick()
        assertEquals(1, defaultDeleted)
    }
}
