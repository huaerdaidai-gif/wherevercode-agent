package wherevercode.agent.ui

import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStep

/**
 * 成果聚合的纯逻辑（V5 C2 批 3，第 2 步；无 Android 依赖，可单测）。
 *
 * 数据来源与任务列表页 [refreshTasks] 的双循环完全一致：
 * `listTasks(null, null)` 取全量任务，逐任务 `listSteps(taskId)`，
 * 再对每任务的步骤跑 [TaskDetailArtifacts.extractPaths]。
 * 只有 `write_file` 步骤算产物；`run_command` 等不产生文件，
 * 任务 `result` 自由文本里的成果没有结构化记录，聚合屏看不到。
 *
 * 诚实边界（勘查 §3.3，缺口清单——不要编造）：
 * - 无行数统计（+N/−M）：[TaskStep] 无 insertions/deletions 字段，
 *   待批 4（U2）接 ChangeSet；此处只列路径。
 * - 无产物文件存在性校验：列出的路径是“某任务某步骤写过的文件”，
 *   不是“现在存在的文件”；点开后由项目页查看器如实报错。
 * - 无大小/类型/时间元数据：只有步骤的 startedAt/finishedAt，
 *   不冒充文件 mtime；按路径字符串去重，不做文件类型检测。
 * - 路径提取沿用 [TaskDetailArtifacts.extractPaths] 原样，
 *   `path=` 正则的歧义（路径含逗号被截断）与详情页一致，不另修。
 */
data class TaskResults(
    val task: TaskRecord,
    val artifactPaths: List<String>,
)

/** 一条产物路径出自哪些任务（同一路径被多个任务写过时全部列出）。 */
data class PathResults(
    val path: String,
    val taskTitles: List<String>,
)

data class ResultsSummary(
    /** 列出的任务数——按页头口径，只含真正产出文件的任务。 */
    val taskCount: Int,
    val tasksWithArtifacts: Int,
    val distinctArtifactCount: Int,
    /** 按任务分组：与任务列表页一致，按 updatedAt 倒序；只含产出文件的任务。 */
    val tasks: List<TaskResults>,
    /** 按文件分组：去重后的全部产物路径，按首次出现顺序。 */
    val artifactsByPath: List<PathResults>,
    /** 喂进来的全部任务数（含无产物任务），只供空态文案区分
     * “库空”与“有任务但无产物”。 */
    val totalTaskCount: Int = 0,
)

object ResultsAggregation {

    /**
     * 按任务聚合产物路径，并算出三行汇总数字与按文件分组。
     * 同一路径被多个任务写过时，去重计数只算一次，[PathResults]
     * 里列出全部写过它的任务；“最新一次写入”是哪个任务无持久化
     * 记录可查，这里不宣称，只按任务 updatedAt 倒序列出。
     *
     * V5 C3 chat-bugfix：只保留真正产出文件的任务（页头口径）。
     * 之前全部任务都列出，无产物任务以“本任务没有写文件步骤”充数——
     * 聊天轮次变的任务（每轮一个 Task 行）因此全跑进了成果页。
     */
    fun aggregate(
        tasks: List<TaskRecord>,
        stepsByTask: Map<String, List<TaskStep>>,
    ): ResultsSummary {
        val grouped = tasks.sortedByDescending { it.updatedAt }.map { task ->
            TaskResults(task, TaskDetailArtifacts.extractPaths(stepsByTask[task.taskId] ?: emptyList()))
        }.filter { it.artifactPaths.isNotEmpty() }
        val distinctPaths = LinkedHashSet<String>()
        grouped.forEach { distinctPaths.addAll(it.artifactPaths) }
        val byPath = distinctPaths.map { path ->
            PathResults(
                path = path,
                taskTitles = grouped
                    .filter { it.artifactPaths.contains(path) }
                    .map { it.task.title.ifBlank { it.task.taskId } },
            )
        }
        return ResultsSummary(
            taskCount = grouped.size,
            tasksWithArtifacts = grouped.size,
            distinctArtifactCount = distinctPaths.size,
            tasks = grouped,
            artifactsByPath = byPath,
            totalTaskCount = tasks.size,
        )
    }

    /**
     * 空态文案：有内容时返回 null，由调用方渲染汇总与列表；
     * 无任务 / 有任务但无产物时返回诚实说明（不得伪装有产物）。
     * [totalTaskCount] 是喂进来的全部任务数（调用方传
     * [ResultsSummary.totalTaskCount]，不是列出的任务数）。
     */
    fun emptyMessage(totalTaskCount: Int, tasksWithArtifacts: Int): String? {
        if (totalTaskCount == 0) {
            return "任务库为空。聊天轮次会自动写入任务库；这里只显示 TaskStore 中" +
                "真实存在的任务，不伪造任务行。"
        }
        if (tasksWithArtifacts == 0) {
            return "已有 $totalTaskCount 个任务，但都没有写文件步骤，未产生产物文件。" +
                "产物只来自 write_file 步骤；run_command 产物与任务结果文本不在此列。"
        }
        return null
    }
}
