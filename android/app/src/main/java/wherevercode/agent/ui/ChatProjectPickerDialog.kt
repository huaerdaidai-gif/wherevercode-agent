package wherevercode.agent.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.project.chatProjectPickerItems

/**
 * 聊天选项目对话框（2026-11-11，maintainer：新对话清空绑定后聊天里选不了项目）。
 *
 * 内容与 Dialog 壳分开：[show] 建 Dialog 并 show 出来，调用方只传回调；
 * 单测可直接断言条目与点击（见 ChatProjectPickerDialogTest）。点条目
 * 即 [onPick]（项目 id），调用方负责切换绑定并刷新横幅；无项目时不编
 * 假条目，给“去项目页添加”入口。
 */
object ChatProjectPickerDialog {

    fun show(
        context: Context,
        projects: List<Project>,
        homeLabel: (Project) -> String,
        onPick: (projectId: String) -> Unit,
        onOpenProjects: () -> Unit,
    ): AlertDialog {
        if (projects.isEmpty()) {
            return AlertDialog.Builder(context)
                .setTitle("选择项目")
                .setMessage("还没有注册项目。")
                .setPositiveButton("去项目页添加") { _, _ -> onOpenProjects() }
                .setNegativeButton("取消", null)
                .create()
                .also { it.show() }
        }
        val items = chatProjectPickerItems(projects, homeLabel)
        return AlertDialog.Builder(context)
            .setTitle("选择项目")
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                onPick(items[which].second)
            }
            .setNeutralButton("打开项目页") { _, _ -> onOpenProjects() }
            .setNegativeButton("取消", null)
            .create()
            .also { it.show() }
    }
}
