package wherevercode.agent.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import wherevercode.agent.R

/**
 * 成果聚合屏（V5 C2 批 3，第 2 步）。
 *
 * 结构与 [SessionListController] 同款：控制器 + `root` + `render()`，
 * `MainActivity` 只保留 `showEntry` 路由与数据组装（`refreshResults`）。
 * 固定头（标题 / 说明 / 汇总数字 / 视图切换）建一次；`render()` 只重建
 * 其下的列表段，切换“按任务 / 按文件”不丢滚动位置以外的状态。
 *
 * 默认按任务分组；“按文件查看”是同一数据集按路径分组，零新增 IO。
 * 汇总数字（任务数 / 有产物任务数 / 产物文件去重数）由
 * [ResultsAggregation.aggregate] 纯计算，不新开数据源。
 * 路径行点开走 [onOpenFile]（即 `MainActivity.openDetailFile`，
 * 进项目页查看；文件若已不存在，查看器如实报错）。
 *
 * 诚实边界写进 UI，不只进报告：只列写文件步骤的路径；行数统计待批 4；
 * 未校验文件是否仍存在；run_command 产物与任务结果文本不在此列。
 */
class ResultsScreenController(
    private val context: Context,
    private val onOpenFile: (String) -> Unit,
    private val onOpenTasks: () -> Unit,
) {
    val root: ScrollView = ScrollView(context).apply {
        visibility = View.GONE
        isFillViewport = true
    }

    private val content: LinearLayout = UiKit.vertical(context).apply {
        setPadding(dp(16), dp(8), dp(16), dp(20))
    }

    /** 汇总数字行：每次 render() 重建。 */
    private val summaryContainer: LinearLayout = UiKit.vertical(context)

    /** 只重建的列表段：任务卡 / 文件分组 / 空态。固定头不在其中。 */
    private val listContainer: LinearLayout = UiKit.vertical(context)

    private val byTaskChip: TextView = modeChip("按任务查看")
    private val byFileChip: TextView = modeChip("按文件查看")

    enum class ViewMode { BY_TASK, BY_FILE }

    /** 当前视图模式（测试用；旋转重建后回到默认按任务，不持久化）。 */
    var viewMode: ViewMode = ViewMode.BY_TASK
        private set

    private var summary: ResultsSummary? = null

    init {
        root.addView(content)
        content.addView(
            UiKit.text(context, "成果", 20f, R.color.text_primary, bold = true).apply {
                setPadding(0, dp(4), 0, dp(4))
            },
        )
        content.addView(
            UiKit.text(
                context,
                "跨任务的产物汇总：只列写文件步骤产出的文件路径，点开进项目页查看。",
                11f,
                R.color.text_tertiary,
            ).apply { setPadding(0, 0, 0, dp(8)) },
        )
        content.addView(summaryContainer)
        val toggle = UiKit.horizontal(context).apply {
            setPadding(0, dp(4), 0, dp(10))
        }
        byTaskChip.setOnClickListener { setMode(ViewMode.BY_TASK) }
        byFileChip.setOnClickListener { setMode(ViewMode.BY_FILE) }
        toggle.addView(byTaskChip)
        toggle.addView(byFileChip)
        content.addView(toggle)
        content.addView(listContainer)
        refreshModeChips()
    }

    /** 大脑未建好时：只说暂不可读，不伪造空汇总。 */
    fun renderLoading() {
        summary = null
        summaryContainer.removeAllViews()
        listContainer.removeAllViews()
        listContainer.addView(note("大脑加载中，成果暂不可读。"))
    }

    /** 读任务库失败时：明说失败，不渲染空汇总。 */
    fun renderError() {
        summary = null
        summaryContainer.removeAllViews()
        listContainer.removeAllViews()
        listContainer.addView(note("读取持久任务库失败；详见运行事件/日志。"))
        listContainer.addView(goTasksButton())
    }

    fun render(summary: ResultsSummary) {
        this.summary = summary
        summaryContainer.removeAllViews()
        summaryContainer.addView(
            UiKit.text(context, "任务：${summary.taskCount} 个", 13f, R.color.text_primary, bold = true),
        )
        summaryContainer.addView(
            UiKit.text(context, "有产物任务：${summary.tasksWithArtifacts} 个", 13f, R.color.text_primary),
        )
        summaryContainer.addView(
            UiKit.text(context, "产物文件（去重）：${summary.distinctArtifactCount} 个", 13f, R.color.text_primary),
        )
        renderList()
    }

    fun setMode(mode: ViewMode) {
        if (viewMode == mode) return
        viewMode = mode
        refreshModeChips()
        renderList()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val current = summary ?: return
        val empty = ResultsAggregation.emptyMessage(
            current.totalTaskCount,
            current.tasksWithArtifacts,
        )
        if (empty != null) {
            listContainer.addView(note(empty))
            listContainer.addView(goTasksButton())
            return
        }
        when (viewMode) {
            ViewMode.BY_TASK -> current.tasks.forEach { listContainer.addView(taskCard(it)) }
            ViewMode.BY_FILE -> current.artifactsByPath.forEach { listContainer.addView(pathGroup(it)) }
        }
        listContainer.addView(
            note(
                "只列写文件步骤的路径；行数统计（+N/−M）待批 4（U2）接改动记录后显示。" +
                    "未校验文件是否仍存在，点开后由项目页如实报错；" +
                    "run_command 产物与任务结果文本不在此列。",
            ),
        )
    }

    private fun taskCard(taskResults: TaskResults): LinearLayout {
        val task = taskResults.task
        val card = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val top = UiKit.horizontal(context)
        top.addView(
            UiKit.text(
                context,
                task.title.ifBlank { task.taskId },
                14f,
                R.color.text_primary,
                bold = true,
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        top.addView(
            UiKit.text(context, task.state, 10f, R.color.text_secondary).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(9), dp(4), dp(9), dp(4))
            },
        )
        card.addView(top)
        card.addView(
            UiKit.text(
                context,
                "更新：${UiKit.shortTime(task.updatedAt)}",
                10f,
                R.color.text_tertiary,
            ).apply { setPadding(0, dp(5), 0, 0) },
        )
        if (taskResults.artifactPaths.isEmpty()) {
            card.addView(
                UiKit.text(context, "本任务没有写文件步骤，未产生产物文件。", 11f, R.color.text_tertiary).apply {
                    setPadding(0, dp(6), 0, 0)
                },
            )
        } else {
            card.addView(
                UiKit.text(
                    context,
                    "产物文件（${taskResults.artifactPaths.size}）：",
                    11f,
                    R.color.text_primary,
                    bold = true,
                ).apply { setPadding(0, dp(8), 0, dp(4)) },
            )
            taskResults.artifactPaths.forEach { path -> card.addView(artifactRow(path)) }
        }
        return card
    }

    private fun pathGroup(pathResults: PathResults): LinearLayout {
        val group = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        group.addView(artifactRow(pathResults.path))
        group.addView(
            UiKit.text(
                context,
                "出自：${pathResults.taskTitles.joinToString("、")}",
                11f,
                R.color.text_secondary,
            ).apply { setPadding(0, dp(6), 0, 0) },
        )
        return group
    }

    private fun artifactRow(path: String): TextView =
        UiKit.text(context, "▸ $path", 12f, R.color.accent, mono = true).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener { onOpenFile(path) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(5) }
        }

    private fun note(text: String): TextView =
        UiKit.text(context, text, 11f, R.color.text_tertiary).apply {
            setBackgroundResource(R.drawable.bg_system_note)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }

    private fun goTasksButton(): TextView =
        UiKit.text(context, "去任务页", 12f, R.color.accent, bold = true).apply {
            setBackgroundResource(R.drawable.bg_chip_accent)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onOpenTasks() }
        }

    private fun modeChip(label: String): TextView =
        UiKit.text(context, label, 12f, R.color.text_primary, bold = true).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(8) }
        }

    private fun refreshModeChips() {
        byTaskChip.setBackgroundResource(
            if (viewMode == ViewMode.BY_TASK) R.drawable.bg_chip_accent else R.drawable.bg_chip,
        )
        byFileChip.setBackgroundResource(
            if (viewMode == ViewMode.BY_FILE) R.drawable.bg_chip_accent else R.drawable.bg_chip,
        )
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}
