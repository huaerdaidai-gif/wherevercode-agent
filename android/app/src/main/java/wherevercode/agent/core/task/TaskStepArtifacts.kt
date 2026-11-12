package wherevercode.agent.core.task

/**
 * 任务步骤 → 产物路径（P2 自动执行，2026-11-11）。
 *
 * 从 [ui.TaskDetailArtifacts] 搬下来的唯一实现（原地在 ui 层，
 * core 层不能反向 import ui）：只从 `write_file` 步骤的
 * `argsSummary` 里取 `path=<工作区相对路径>`（TurnTaskRecorder.formatArgs
 * 的写法：`key=value` 以 ", " 连接，如 `path=calc.py, content=...`）。
 * 绝不从结果文字里猜路径、绝不编造文件；没有写文件步骤的任务，
 * 产物列表就是空。
 *
 * 流水线 runner 的审查输入用它当 changedFiles（机械检查的文件范围
 * 项）；详情页仍走 ui 的 [wherevercode.agent.ui.TaskDetailArtifacts]，
 * 那边已改成 delegate 到这里，不再各存一份正则。
 */
object TaskStepArtifacts {

    private val PATH_ARG = Regex("(?:^|, )path=([^,]+)")

    /**
     * 从执行步骤里提取产物文件路径（去重、保序）。
     * 只有 `write_file` 步骤算产物；`run_command` 等不产生文件。
     */
    fun extractPaths(steps: List<TaskStep>): List<String> {
        val out = LinkedHashSet<String>()
        for (step in steps) {
            if (step.toolName != "write_file") continue
            extractFromText(step.argsSummary).forEach { out += it }
        }
        return out.toList()
    }

    /** 从一段 `key=value, ...` 文本里提取全部 `path=` 值。 */
    fun extractFromText(text: String): List<String> =
        PATH_ARG.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && !it.contains('\n') }
            .toList()
}
