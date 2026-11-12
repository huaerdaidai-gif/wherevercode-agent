package wherevercode.agent.ui

import android.os.Looper
import android.view.ContextThemeWrapper
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import wherevercode.agent.R
import wherevercode.agent.core.project.Project

/**
 * 聊天选项目对话框（2026-11-11，maintainer：新对话清空绑定后聊天里选不了项目）。
 * Robolectric，CI 跑。点条目即 onPick 对应项目 id（调用方切换绑定）；
 * 无项目时不编假条目，给“去项目页添加”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class ChatProjectPickerDialogTest {

    // 带 App 主题的上下文：AppCompat AlertDialog 在裸 Application 上下文
    // 下没有主题属性，show 时会抛 Resources$NotFoundException。
    private val context = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        R.style.Theme_WhereverCode,
    )

    @Test
    fun `picking an item calls onPick with that project id`() {
        val picked = mutableListOf<String>()
        val projects = listOf(
            Project(projectId = "wherevercode-agent", name = "wherevercode-agent"),
            Project(projectId = "wherevercode-agent-2", name = "wherevercode-agent 2"),
        )
        val dialog = ChatProjectPickerDialog.show(
            context = context,
            projects = projects,
            homeLabel = { project ->
                if (project.projectId == "wherevercode-agent-2") "GitHub · o/r" else "未指定家"
            },
            onPick = { picked += it },
            onOpenProjects = {},
        )
        assertTrue(dialog.isShowing)
        val listView = dialog.listView as ListView
        val row = listView.adapter.getView(1, null, listView)
        listView.performItemClick(row, 1, listView.adapter.getItemId(1))
        assertEquals(listOf("wherevercode-agent-2"), picked)
    }

    @Test
    fun `empty list offers go-to-projects instead of fake entries`() {
        var opened = false
        val dialog = ChatProjectPickerDialog.show(
            context = context,
            projects = emptyList(),
            homeLabel = { "x" },
            onPick = { throw AssertionError("无项目时不应有可点条目") },
            onOpenProjects = { opened = true },
        )
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        // AppCompat 对话框按钮点击经 Handler 发消息才调回调，Robolectric 下
        // 主 Looper 暂停，不 idle 就断言不到（真机上 Handler 正常跑，没问题）。
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(opened)
    }
}
