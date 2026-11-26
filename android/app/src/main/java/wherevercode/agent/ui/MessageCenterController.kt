package wherevercode.agent.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import wherevercode.agent.R
import wherevercode.agent.core.message.MessageCenterEntry
import wherevercode.agent.core.message.MessageCenterSection
import wherevercode.agent.core.message.MessageCenterSnapshot

/**
 * 消息中心控制器（P3-MSG 三期新增，独立于 TeamChannelController）：
 * 聚合频道里的 @我 / 未读 / 任务回执 三类条目，外加待落地任务区
 * （9.19）。消息条目只读——不发送、不派任务、不执行代码；数据由
 * MainActivity 用 [MessageCenterLoader] 只读拉回后 [render]，点条目
 * 经 [onOpenEntry] 跳到对应频道；待落地任务点按经 [onLandTask] 落地。
 *
 * 条目呈现复用已摘入基线的共用对话框组件：列表宿主与频道页/单聊页
 * 共用 [MessageListView]（对话组件收敛），不另造滚动列表。
 */
class MessageCenterController(
    private val context: Context,
    private val onRefresh: () -> Unit,
    private val onOpenEntry: (MessageCenterEntry) -> Unit,
    /** 待落地任务被点按：由调用方弹频道选择并执行落地（9.19）。 */
    private val onLandTask: (wherevercode.agent.core.message.PendingTaskEntry) -> Unit = {},
    /** 关闭消息中心（回到上一个界面，由调用方决定落点）。 */
    private val onClose: () -> Unit,
) {
    val root: LinearLayout = UiKit.vertical(context).apply {
        visibility = View.GONE
    }

    /** 条目列表宿主（共用对话框组件，与频道页同款 padding）。 */
    private val messageListView: MessageListView = MessageListView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
        content.setPadding(dp(16), dp(8), dp(16), dp(20))
    }

    private val bodyContainer: LinearLayout = messageListView.content
    private val statusLine: TextView = UiKit.text(context, "", 11f, R.color.text_tertiary)

    init {
        val titleRow = UiKit.horizontal(context)
        titleRow.addView(
            UiKit.text(context, "消息中心", 20f, R.color.text_primary, bold = true).apply {
                setPadding(dp(16), dp(12), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        titleRow.addView(
            UiKit.text(context, "关闭", 13f, R.color.text_secondary, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    rightMargin = dp(10)
                    topMargin = dp(10)
                }
                setOnClickListener { onClose() }
            },
        )
        titleRow.addView(
            UiKit.text(context, "刷新", 13f, R.color.text_primary, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    rightMargin = dp(16)
                    topMargin = dp(10)
                }
                setOnClickListener { onRefresh() }
            },
        )
        root.addView(titleRow)
        root.addView(
            UiKit.text(
                context,
                "聚合频道里的 @我、未读与任务回执；点条目回到对应频道，待落地任务点按选择频道落地。",
                11f,
                R.color.text_tertiary,
            ).apply { setPadding(dp(16), dp(2), dp(16), dp(4)) },
        )
        statusLine.setPadding(dp(16), 0, dp(16), dp(2))
        root.addView(statusLine)
        root.addView(messageListView)
    }

    /** 渲染一个聚合快照；null 表示尚无数据（保留加载提示）。 */
    fun render(snapshot: MessageCenterSnapshot?, loading: Boolean = false, notice: String? = null) {
        statusLine.text = when {
            !notice.isNullOrBlank() -> notice
            loading -> "正在读取频道消息…"
            else -> ""
        }
        bodyContainer.removeAllViews()
        if (snapshot == null) {
            bodyContainer.addView(note(if (loading) "正在读取…" else "还没有数据，点右上角“刷新”。"))
            return
        }
        section(MessageCenterSection.MENTION, "＠我", snapshot.mentions) { entry ->
            val alias = entry.matchedAlias.orEmpty()
            "被 @%s · 来自 %s".format(alias, entry.senderName)
        }
        section(MessageCenterSection.UNREAD, "未读", snapshot.unread) { entry ->
            "来自 %s".format(entry.senderName)
        }
        section(MessageCenterSection.TASK_RECEIPT, "任务回执", snapshot.taskReceipts) { entry ->
            val title = entry.taskTitle?.takeIf { it.isNotBlank() }
            val task = entry.taskId.orEmpty()
            if (title != null) "任务 %s · %s".format(task, title) else "任务 %s".format(task)
        }
        pendingSection(snapshot.pendingTasks)
    }

    /** 待落地任务区（9.19）：点条目选频道落地，落地后任务进该频道任务页。 */
    private fun pendingSection(tasks: List<wherevercode.agent.core.message.PendingTaskEntry>) {
        bodyContainer.addView(
            UiKit.text(context, "待落地任务 · ${tasks.size}", 14f, R.color.text_primary, bold = true)
                .apply { setPadding(0, dp(10), 0, dp(4)) },
        )
        if (tasks.isEmpty()) {
            bodyContainer.addView(note("暂无待落地任务。派单未指定频道且本地不止一个频道时，任务会先挂在这里。"))
            return
        }
        tasks.forEach { task -> bodyContainer.addView(pendingRow(task)) }
    }

    private fun pendingRow(task: wherevercode.agent.core.message.PendingTaskEntry): LinearLayout {
        val row = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onLandTask(task) }
        }
        row.addView(
            UiKit.text(context, task.title.ifBlank { "（无标题）" }, 13f, R.color.text_primary, bold = true),
        )
        if (task.goal.isNotBlank()) {
            row.addView(UiKit.text(context, task.goal.take(300), 12f, R.color.text_secondary))
        }
        row.addView(
            UiKit.text(
                context,
                "执行人 %s · %s · %s · 点按选择频道落地".format(
                    task.assigneeName.ifBlank { "未指定" },
                    task.stateLabel,
                    formatTime(task.createdAtSeconds),
                ),
                10f,
                R.color.text_tertiary,
            ),
        )
        return row
    }

    private fun section(
        section: MessageCenterSection,
        title: String,
        entries: List<MessageCenterEntry>,
        subtitle: (MessageCenterEntry) -> String,
    ) {
        bodyContainer.addView(
            UiKit.text(context, "$title · ${entries.size}", 14f, R.color.text_primary, bold = true)
                .apply { setPadding(0, dp(10), 0, dp(4)) },
        )
        if (entries.isEmpty()) {
            bodyContainer.addView(note(emptyHint(section)))
            return
        }
        entries.forEach { entry ->
            bodyContainer.addView(entryRow(entry, subtitle(entry)))
        }
    }

    private fun emptyHint(section: MessageCenterSection): String = when (section) {
        MessageCenterSection.MENTION -> "暂无 @我 的消息。"
        MessageCenterSection.UNREAD -> "暂无未读。已读状态由频道页自己推进，这里不会替你消费。"
        MessageCenterSection.TASK_RECEIPT -> "暂无带任务归属的消息。"
        MessageCenterSection.PENDING_TASK -> "暂无待落地任务。"
    }

    private fun entryRow(entry: MessageCenterEntry, subtitleText: String): LinearLayout {
        val row = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(5) }
            // 入口可达可测：点击整行跳到对应频道（跳转语义见 MainActivity）。
            isClickable = true
            isFocusable = true
            setOnClickListener { onOpenEntry(entry) }
        }
        row.addView(
            UiKit.text(context, subtitleText, 11f, R.color.text_tertiary, bold = true),
        )
        row.addView(
            UiKit.text(context, entry.text.take(300).ifBlank { "（无正文）" }, 13f, R.color.text_primary),
        )
        row.addView(
            UiKit.text(
                context,
                // 全量对齐批：元信息行与 iOS 同式——只到「点按回到频道」，
                // 不带频道 ID 截断尾巴。
                "%s · %s · 点按回到频道".format(
                    entry.channelName,
                    formatTime(entry.createdAtSeconds),
                ),
                10f,
                R.color.text_tertiary,
            ),
        )
        return row
    }

    private fun note(text: String): TextView =
        UiKit.text(context, text, 12f, R.color.text_tertiary).apply {
            setPadding(0, dp(6), 0, dp(6))
        }

    private fun formatTime(seconds: Double): String =
        UiKit.shortTime(seconds.toLong().takeIf { it > 0 })

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}
