package wherevercode.agent.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import wherevercode.agent.R
import wherevercode.agent.core.queue.QueueBoardItem
import wherevercode.agent.core.queue.QueueBoardResult
import wherevercode.agent.core.queue.QueueGroup

/**
 * 队列与调度看板对话框（核心功能推进第 2 项，2026-11-15）。
 *
 * 内容与壳分开（同 GlobalSearchDialog 的模式）：调用方（MainActivity）
 * 在后台线程用 TaskStore.listTasks() 读全量任务、经纯逻辑 QueueBoard
 * 分组排序、并把 Agent / 项目显示名解析好再传入；本类只渲染。点任务行
 * 回调任务 id，由调用方走既有 openTaskDetail 打开详情（暂停/停止/重试/
 * 改目标等全套操作都在详情里）；排队中组的「取消」按钮回调任务 id，
 * 由调用方二次确认后走既有 stopTask 围栏路径，本类不直接写任务状态。
 *
 * 调度口径如实标注在界面顶部：本页只展示队列与状态，不改变调度顺序；
 * 认领与执行仍由既有调度器/执行器负责。
 */
object QueueBoardDialog {

    fun show(
        context: Context,
        board: QueueBoardResult,
        agentNames: Map<String, String>,
        projectNames: Map<String, String>,
        onOpenTask: (taskId: String) -> Unit,
        onCancelQueued: (taskId: String) -> Unit,
    ): AlertDialog {
        val dp = { value: Int -> UiKit.dp(context, value) }
        val container = UiKit.vertical(context).apply {
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        container.addView(
            UiKit.text(
                context,
                "共 ${board.totalCount} 个任务 · 运行中 ${board.runningCount} · 排队中 ${board.queuedCount}",
                13f,
                R.color.text_secondary,
            ),
        )
        container.addView(
            UiKit.text(
                context,
                "任务由既有执行器认领执行（本地分派按创建顺序逐个认领）；" +
                    "本页只展示队列与状态，不改变调度顺序。排队中的任务可直接取消，点任务行进详情。",
                11f,
                R.color.text_tertiary,
            ).apply { UiKit.margin(this, topDp = 4, bottomDp = 2) },
        )
        val column = UiKit.vertical(context)
        val scroll = ScrollView(context).apply { addView(column) }
        container.addView(scroll)

        val dialog = AlertDialog.Builder(context)
            .setTitle("队列与调度")
            .setView(container)
            .setNegativeButton("关闭", null)
            .create()

        if (board.isEmpty) {
            column.addView(
                UiKit.text(
                    context,
                    "暂时没有任务。对话任务与分派任务会在有记录时显示在这里。",
                    13f,
                    R.color.text_secondary,
                ).apply { UiKit.margin(this, topDp = 12, bottomDp = 12) },
            )
        } else {
            for (group in board.groups) {
                renderGroup(column, group.group, group.items, agentNames, projectNames,
                    onOpen = { taskId ->
                        dialog.dismiss()
                        onOpenTask(taskId)
                    },
                    onCancel = { taskId ->
                        dialog.dismiss()
                        onCancelQueued(taskId)
                    },
                )
            }
        }
        dialog.show()
        return dialog
    }

    private fun renderGroup(
        column: LinearLayout,
        group: QueueGroup,
        items: List<QueueBoardItem>,
        agentNames: Map<String, String>,
        projectNames: Map<String, String>,
        onOpen: (taskId: String) -> Unit,
        onCancel: (taskId: String) -> Unit,
    ) {
        if (items.isEmpty()) return
        val context = column.context
        column.addView(
            UiKit.text(context, "${group.title}（${items.size}）", 13f, R.color.accent, bold = true)
                .apply { UiKit.margin(this, topDp = 12, bottomDp = 2) },
        )
        for (item in items) {
            val task = item.task
            val row = UiKit.horizontal(context).apply {
                setPadding(0, UiKit.dp(context, 8), 0, UiKit.dp(context, 8))
                isClickable = true
                setOnClickListener { onOpen(task.taskId) }
            }
            val texts = UiKit.vertical(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(
                UiKit.text(context, task.title.ifBlank { task.taskId }, 14f, R.color.text_primary),
            )
            texts.addView(
                UiKit.text(
                    context,
                    subtitle(item, agentNames, projectNames),
                    12f,
                    R.color.text_secondary,
                ).apply { UiKit.margin(this, topDp = 2) },
            )
            row.addView(texts)
            if (group == QueueGroup.QUEUED) {
                row.addView(
                    UiKit.text(context, "取消", 12f, R.color.text_primary, bold = true).apply {
                        setBackgroundResource(R.drawable.bg_chip)
                        setPadding(
                            UiKit.dp(context, 12), UiKit.dp(context, 7),
                            UiKit.dp(context, 12), UiKit.dp(context, 7),
                        )
                        setOnClickListener { onCancel(task.taskId) }
                    },
                )
            }
            column.addView(row)
            column.addView(View(context).apply {
                setBackgroundColor(UiKit.color(context, R.color.divider))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1,
                )
            })
        }
    }

    /** 行副文案：状态中文（taskStateLabel 唯一口径）· 排队位次 · 目标 Agent · 项目 · 更新时间。 */
    private fun subtitle(
        item: QueueBoardItem,
        agentNames: Map<String, String>,
        projectNames: Map<String, String>,
    ): String {
        val task = item.task
        val parts = mutableListOf<String>()
        parts += taskStateLabel(task.state)
        item.queuePosition?.let { parts += "第 $it 位" }
        task.assignedAgentId?.takeIf { it.isNotBlank() }?.let { agentId ->
            parts += agentNames[agentId] ?: agentId
        }
        task.projectId?.takeIf { it.isNotBlank() }?.let { projectId ->
            parts += projectNames[projectId] ?: projectId
        }
        parts += UiKit.shortTime(task.updatedAt)
        return parts.joinToString(" · ")
    }
}
