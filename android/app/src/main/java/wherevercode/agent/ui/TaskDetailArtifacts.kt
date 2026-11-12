package wherevercode.agent.ui

import wherevercode.agent.brain.ApprovalOutcome
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.task.TaskStepArtifacts

/**
 * 任务详情页的纯逻辑（阶段 3，U3/批 3a；无 Android 依赖，可单测）。
 *
 * 产物路径规则：只从 `write_file` 步骤的 `argsSummary` 里取
 * `path=<工作区相对路径>`（TurnTaskRecorder.formatArgs 的写法：
 * `key=value` 以 ", " 连接，如 `path=calc.py, content=...`）。
 * 绝不从结果文字里猜路径、绝不编造文件；没有写文件步骤的任务，
 * 产物列表就是空。
 *
 * 渲染形状复用老项目（maintainer 13:35：复用老项目成熟设计是规定方向）：
 * - 文件清单形状：`renderExecutionTaskFiles`（v6-core.js:627）——
 *   files[] + insertions/deletions 总数 + 新增/删除/修改 kind；
 *   本 App 的行数统计待批 4（U2）接 WorkspaceAdapter ChangeSet，
 *   批 3a 只列路径，行数缺失时不明示数字。
 * - 逐行 diff 形状：`artifact_diff`（task_review.py:206）——
 *   {path, insertions, deletions, binary, lines[{kind, old_line,
 *   new_line, text}]}；批 3a 不解析 patch（App 内无 git patch
 *   输入），只沿用该形状作为批 4 diff 视图的约定。
 */
object TaskDetailArtifacts {

    /**
     * 从执行步骤里提取产物文件路径（去重、保序）。
     * 只有 `write_file` 步骤算产物；`run_command` 等不产生文件。
     * 唯一实现在 core 的 [TaskStepArtifacts]，这里只 delegate（core
     * 层不能反向 import ui，runner 的审查输入直接用 core 那份）。
     */
    fun extractPaths(steps: List<TaskStep>): List<String> =
        TaskStepArtifacts.extractPaths(steps)

    /** 从一段 `key=value, ...` 文本里提取全部 `path=` 值。 */
    fun extractFromText(text: String): List<String> =
        TaskStepArtifacts.extractFromText(text)
}

/**
 * 审批结果的界面文案（与 ApprovalBroker 的四种 outcome 一一对应；
 * 超时拒绝与主动拒绝必须可区分，见 U4 验收标准）。
 */
fun approvalOutcomeLabel(outcome: ApprovalOutcome, cached: Boolean): String {
    if (cached) return "已批准（沿用已批准的决定，未重复弹窗）"
    return when (outcome) {
        ApprovalOutcome.APPROVED -> "已批准"
        ApprovalOutcome.DENIED_USER -> "用户拒绝"
        ApprovalOutcome.DENIED_TIMEOUT -> "超时拒绝（60 秒内未决定）"
        ApprovalOutcome.DENIED_UNAVAILABLE -> "未决定（无审批界面/已关闭）"
    }
}
