package wherevercode.agent.ui

import wherevercode.agent.R

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import wherevercode.agent.brain.ApprovalRecord
import wherevercode.agent.brain.EngineEvent
import wherevercode.agent.core.control.ControlAuditEntry
import wherevercode.agent.core.task.PipelineOrchestrator
import wherevercode.agent.core.task.PipelineStage
import wherevercode.agent.core.task.PipelinePlanStatus
import wherevercode.agent.core.task.ReviewVerdict
import wherevercode.agent.core.task.TaskEvent
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.task.TaskStepStatus
import wherevercode.agent.core.task.workingDirectoryFromEvents

/**
 * 任务详情页（阶段 3，U3/批 3a）。
 *
 * 点任务卡进详情：状态 + 步骤 + 日志 + 产物 + 审批 + 逐任务控制。
 * 全部内容来自真实数据源，不伪造：
 * - 任务行：TaskStore；步骤：本进程 TaskStepStore（重启后不再显示，
 *   页内明标）；日志：EngineEvent，按任务 sessionId 过滤，分派任务
 *   （sessionId 为空）没有会话日志，页内明标；
 * - 产物：[TaskDetailArtifacts.extractPaths] 从 write_file 步骤取
 *   `path=`，点开进项目页查看（ProjectScreenController.openFileAt）；
 * - 审批：ApprovalBroker 历史中 target 命中产物路径或任务标题的记录
 *   （审批按工具调用记录，不按任务归属——匹配是尽力而为，页内明标），
 *   并给出全量历史条数。
 *
 * 渲染形状复用老项目（maintainer 13:35 规定方向）：产物清单形状沿用
 * `renderExecutionTaskFiles`（v6-core.js:627），逐行 diff 形状沿用
 * `artifact_diff`（task_review.py:206）；行数统计（+N/−M）待批 4
 * （U2）接 WorkspaceAdapter ChangeSet，本轮只列路径、不编数字。
 */
/**
 * 流水线操作（P2）：详情页流水线区里的按钮动作；MainActivity 接线
 * 到 PipelineOrchestrator（后台线程跑，跑完刷新详情）。
 */
enum class PipelineAction {
    ENABLE,
    SUBMIT_PLAN,
    APPROVE_PLAN,
    RUN_MECHANICAL_CHECKS,
    APPROVE_REVIEW,
    REQUEST_REWORK,
}

class TaskDetailController(
    private val context: Context,
    private val onBack: () -> Unit,
    private val onOpenFile: (String) -> Unit,
    private val onRefresh: () -> Unit,
    private val controlsProvider: (TaskRecord) -> View,
) {
    val root: ScrollView = ScrollView(context).apply {
        visibility = View.GONE
        isFillViewport = true
        // 2026-11-11（任务 692638e2 截图）：根视图不透明背景——详情
        // 浮层盖在任务屏之上时，底层任务列表不再从透明根视图透出来
        // 与详情文字互压。R.color.bg 与窗口底色一致，夜间自动跟随
        // （values-night/colors.xml 同名深色值）。
        setBackgroundResource(R.color.bg)
    }

    private val content: LinearLayout = UiKit.vertical(context).apply {
        setPadding(dp(16), dp(8), dp(16), dp(20))
    }

    init {
        root.addView(content)
    }

    fun render(
        task: TaskRecord,
        steps: List<TaskStep>,
        events: List<EngineEvent>,
        approvals: List<ApprovalRecord>,
        totalApprovalCount: Int,
        artifactPaths: List<String>,
        taskEvents: List<TaskEvent> = emptyList(),
        pipeline: PipelineOrchestrator.PipelineViewState? = null,
        onPipelineAction: ((PipelineAction) -> Unit)? = null,
        /** 自主度（2026-11-11）：大脑总管的自动决定（控制审计里 actor = 大脑总管的行）。 */
        autoDecisions: List<ControlAuditEntry> = emptyList(),
        controlStatus: String? = null,
    ) {
        content.removeAllViews()

        val back = UiKit.text(context, "‹ 返回任务列表", 12f, R.color.accent, bold = true).apply {
            setPadding(0, dp(6), 0, dp(4))
            setOnClickListener { onBack() }
        }
        content.addView(back)
        val top = UiKit.horizontal(context)
        top.addView(
            UiKit.text(context, task.title.ifBlank { task.taskId }, 17f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        top.addView(stateChip(task.state))
        content.addView(top)

        content.addView(section("状态"))
        content.addView(info("状态：${taskStateLabel(task.state)}"))
        controlStatus?.let { content.addView(info("执行控制：$it")) }
        // V5 跨设备开发（2026-11-12，D4 新口径 (b)）：任务详情“工作
        // 目录”行，从状态轨迹事件反查（不新增 Room 字段/迁移）。
        // 项目检出命中时显示“项目检出”，无检出回落时显示“Agent 文件
        // 夹回落”；两类事件都没有时不显示，不编一个。
        workingDirectoryFromEvents(taskEvents)?.let { workingDir ->
            content.addView(
                info("工作目录：${workingDir.folder}（${if (workingDir.isProjectCheckout) "项目检出" else "Agent 文件夹回落"}）"),
            )
        }
        // 看板列（主流 Kanban，见 KanbanStatus）：终态（失败/已停止）不进看板列，
        // 显示终态标签。返工是反向流转（待审核 → 返工 → 进行中），状态机已允许。
        val kanbanText = task.kanbanColumn?.label ?: taskStateLabel(task.state)
        content.addView(info("看板：$kanbanText"))
        task.attempt.takeIf { it > 0 }?.let { content.addView(info("认领次数：$it")) }
        task.leaseExpiresAt?.let { content.addView(info("租约到期：${UiKit.shortTime(it)}")) }
        content.addView(info("创建：${UiKit.shortTime(task.createdAt)}"))
        content.addView(info("更新：${UiKit.shortTime(task.updatedAt)}"))
        task.startedAt?.let { content.addView(info("开始：${UiKit.shortTime(it)}")) }
        task.finishedAt?.let { content.addView(info("结束：${UiKit.shortTime(it)}")) }
        task.description?.takeIf { it.isNotBlank() }?.let {
            content.addView(body(it))
        }
        task.result?.takeIf { it.isNotBlank() }?.let {
            content.addView(section("结果"))
            content.addView(body(it))
        }

        // P2：流水线（规划→编码→审查）——阶段子状态 + plan + 返工轮次
        // + verdict 历史 + 机械检查结果；单 Agent 任务（pipeline 为 null）
        // 只留一行说明，不挡原有内容。
        content.addView(section("流水线（规划→编码→审查）"))
        if (pipeline == null || !pipeline.enabled) {
            content.addView(note("单 Agent 模式（默认）：点“启用流水线”后先出 plan，用户批准才编码；审查走机械检查 + 干净上下文审查，返工上限 2 轮。"))
            if (onPipelineAction != null && !TaskStates.isTerminal(task.state)) {
                content.addView(pipelineButton("启用流水线") { onPipelineAction(PipelineAction.ENABLE) })
            }
        } else {
            content.addView(info("阶段：${PipelineStage.labelOf(pipeline.stage)} · plan：${PipelinePlanStatus.labelOf(pipeline.planStatus)} · 返工 ${pipeline.reworkRounds}/${pipeline.maxReworkRounds} 轮"))
            pipeline.plan?.let { plan ->
                content.addView(info("plan 目标：${plan.objective.take(200)}"))
                content.addView(info("文件清单 ${plan.filesInScope.size} 个 · 步骤 ${plan.steps.size} 条 · 验收标准 ${plan.acceptanceCriteria.size} 条 · 测试命令 ${plan.testCommands.size} 条"))
                if (plan.testCommands.isNotEmpty()) {
                    content.addView(info("测试命令：${plan.testCommands.joinToString("；").take(300)}"))
                }
            }
            pipeline.coderAgentId?.let { content.addView(info("编码者：$it")) }
            pipeline.reviewerAgentId?.let { content.addView(info("审查者：$it")) }
            pipeline.lastMechanical?.let { mechanical ->
                content.addView(info("机械检查：${mechanical.summaryText().lines().firstOrNull() ?: ""}"))
                mechanical.failed.forEach { content.addView(info("✗ ${it.name}：${it.detail}")) }
                mechanical.notRun.forEach { content.addView(info("○ ${it.name}：${it.detail}")) }
            }
            if (pipeline.verdicts.isEmpty()) {
                content.addView(note("暂无审查 verdict：verdict 只取“通过 / 打回返工”两值，历史追加记录、不改不删。"))
            } else {
                pipeline.verdicts.forEach { verdict ->
                    val line = buildString {
                        append("${UiKit.shortTime(verdict.createdAtEpochSeconds)} · ${verdict.reviewer} · ${ReviewVerdict.labelOf(verdict.verdict)}（第 ${verdict.round} 轮）")
                        if (verdict.checklist.isNotEmpty()) append("\n${verdict.checklist.take(3).joinToString("；")}")
                        verdict.mechanical?.let { append("\n机械检查：${if (it.passed) "通过" else "不通过"}") }
                    }
                    content.addView(info(line))
                }
            }
            if (onPipelineAction != null) {
                val row = UiKit.horizontal(context)
                when (pipeline.stage) {
                    PipelineStage.PLAN -> {
                        // 草稿失败（runner 自动起草未出可用 plan）时仍可手动提交：
                        // submitPlan 只要求阶段在 plan，不要求 planStatus 是 NONE。
                        if (pipeline.planStatus == PipelinePlanStatus.NONE ||
                            pipeline.planStatus == PipelinePlanStatus.FAILED
                        ) {
                            row.addView(pipelineButton("提交 plan 草稿") { onPipelineAction(PipelineAction.SUBMIT_PLAN) })
                        }
                        if (pipeline.planStatus == PipelinePlanStatus.DRAFT) {
                            row.addView(pipelineButton("批准 plan") { onPipelineAction(PipelineAction.APPROVE_PLAN) })
                        }
                    }
                    PipelineStage.REVIEW -> {
                        row.addView(pipelineButton("运行机械检查") { onPipelineAction(PipelineAction.RUN_MECHANICAL_CHECKS) })
                        row.addView(pipelineButton("通过审查") { onPipelineAction(PipelineAction.APPROVE_REVIEW) })
                        row.addView(pipelineButton("打回返工") { onPipelineAction(PipelineAction.REQUEST_REWORK) })
                    }
                }
                if (row.childCount > 0) content.addView(row)
            }
        }
        task.error?.takeIf { it.isNotBlank() }?.let {
            content.addView(section("错误"))
            content.addView(
                UiKit.text(context, it, 12f, R.color.danger).apply {
                    setPadding(0, dp(4), 0, 0)
                },
            )
        }

        content.addView(section("步骤（${steps.size}）"))
        if (steps.isEmpty()) {
            content.addView(note("暂无步骤：步骤仅保留在本进程内，重启后任务行保留、步骤不再显示。"))
        } else {
            steps.forEach { step -> content.addView(stepRow(step)) }
            content.addView(note("步骤仅保留在本进程内，重启后任务行保留、步骤不再显示。"))
        }

        content.addView(section("进展记录"))
        if (task.sessionId == null) {
            content.addView(note("此任务暂无进展记录。"))
        } else if (events.isEmpty()) {
            content.addView(note("此会话暂无进展记录。"))
        } else {
            events.takeLast(100).forEach { event -> content.addView(taskEventRow(context, event)) }
            if (events.size > 100) content.addView(note("仅显示最近 100 条。"))
        }

        // P0：状态轨迹（审计）——每次状态转移、每次租约过期回队/入死信
        // 都落盘一条，只增不改；旧任务在事件日志落地前创建时为空。
        content.addView(section("状态轨迹（审计）"))
        if (taskEvents.isEmpty()) {
            content.addView(note("暂无状态轨迹：此任务在事件日志落地前创建，或尚无转移。"))
        } else {
            taskEvents.forEach { event -> content.addView(taskStateEventRow(context, event)) }
            if (taskEvents.size >= 200) content.addView(note("仅显示最近 200 条。"))
        }

        content.addView(section("产物文件（${artifactPaths.size}）"))
        if (artifactPaths.isEmpty()) {
            content.addView(note("本任务没有写文件步骤，未产生产物文件。"))
        } else {
            artifactPaths.forEach { path -> content.addView(artifactRow(path)) }
            content.addView(note("行数统计（+N/−M）待批 4（U2）接改动记录后显示；此处只列文件路径。"))
        }

        content.addView(section("审批（与本任务相关 ${approvals.size} 条，共 $totalApprovalCount 条）"))
        if (approvals.isEmpty()) {
            content.addView(note("本任务没有匹配的审批记录：审批按工具调用记录，不按任务归属；完整历史见任务页底部。"))
        } else {
            approvals.forEach { record -> content.addView(approvalRow(record)) }
        }

        // 自主度（2026-11-11）：全权模式下大脑总管的自动决定（plan 自动批 /
        // 自动派发 / verdict 自动采纳 / 写操作自动放行），每条带规则与时间；
        // 监督模式下这里是空的——每一道都由用户点了。不同意某条自动决定，
        // 用下面的控制停止、重试或改目标打回。
        content.addView(section("自动决定（${autoDecisions.size}）"))
        if (autoDecisions.isEmpty()) {
            content.addView(note("本任务没有大脑总管的自动决定：监督模式下每一道都由你点；全权模式的自动批、自动派、自动采纳会记在这里。"))
        } else {
            autoDecisions.forEach { entry -> content.addView(autoDecisionRow(entry)) }
        }

        content.addView(section("控制"))
        content.addView(controlsProvider(task))
        val diagnostics = UiKit.text(context, "查看诊断详情", 11f, R.color.text_tertiary).apply {
            setPadding(0, dp(14), 0, dp(6))
        }
        val diagnosticBody = UiKit.vertical(context).apply {
            visibility = View.GONE
            setBackgroundResource(R.drawable.bg_system_note)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        diagnosticBody.addView(info("任务：${task.taskId}", mono = true))
        task.sessionId?.let { diagnosticBody.addView(info("会话：$it", mono = true)) }
        task.assignedAgentId?.let { diagnosticBody.addView(info("执行者：$it", mono = true)) }
        content.addView(diagnostics)
        content.addView(diagnosticBody)
        diagnostics.setOnClickListener {
            diagnosticBody.visibility = if (diagnosticBody.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            diagnostics.text = if (diagnosticBody.visibility == View.VISIBLE) "收起诊断详情" else "查看诊断详情"
        }
        val refresh = UiKit.text(context, "刷新详情", 11f, R.color.text_primary, bold = true).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { onRefresh() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        content.addView(refresh)
    }

    private fun pipelineButton(label: String, onClick: () -> Unit): TextView =
        UiKit.text(context, label, 11f, R.color.text_primary, bold = true).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
        }

    private fun section(title: String): TextView =
        UiKit.text(context, title, 14f, R.color.text_primary, bold = true).apply {
            setPadding(0, dp(16), 0, dp(6))
        }

    private fun info(text: String, mono: Boolean = false): TextView =
        UiKit.text(context, text, 11f, R.color.text_secondary, mono = mono).apply {
            setPadding(0, dp(3), 0, 0)
        }

    private fun body(text: String): TextView =
        UiKit.text(context, text, 12f, R.color.text_secondary).apply {
            setPadding(0, dp(4), 0, 0)
        }

    private fun note(text: String): TextView =
        UiKit.text(context, text, 11f, R.color.text_tertiary).apply {
            setBackgroundResource(R.drawable.bg_system_note)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }

    private fun stateChip(state: String): TextView {
        val color = when (state) {
            "completed" -> R.color.success
            "failed" -> R.color.danger
            "running", "claimed" -> R.color.accent
            "queued" -> R.color.text_secondary
            else -> R.color.text_secondary
        }
        return UiKit.text(context, taskStateLabel(state), 10f, color).apply {
            setBackgroundResource(
                if (state == "completed") R.drawable.bg_chip_success else R.drawable.bg_chip,
            )
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
    }

    private fun stepRow(step: TaskStep): TextView {
        val mark = when (step.status) {
            TaskStepStatus.SUCCESS -> "✓"
            TaskStepStatus.FAILED -> "✗"
            TaskStepStatus.CANCELLED -> "○"
            else -> "…"
        }
        val line = buildString {
            append("$mark ${step.toolName}")
            if (step.argsSummary.isNotBlank()) append(" ${step.argsSummary}")
            step.resultSummary?.takeIf { it.isNotBlank() }?.let { append(" — $it") }
            if (step.startedAtEpochSeconds > 0) append("\n${UiKit.shortTime(step.startedAtEpochSeconds)}")
            step.finishedAtEpochSeconds?.let { append(" → ${UiKit.shortTime(it)}") }
        }
        return UiKit.text(context, line, 11f, R.color.text_secondary, mono = true).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(5) }
        }
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

    private fun approvalRow(record: ApprovalRecord): TextView {
        val line = buildString {
            append("${UiKit.shortTime(record.atEpochSeconds)} · ${record.tool}")
            record.target?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            append(" · ${approvalOutcomeLabel(record.outcome, record.cached)}")
        }
        return UiKit.text(context, line, 11f, R.color.text_secondary).apply {
            setBackgroundResource(R.drawable.bg_system_note)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(5) }
        }
    }

    /** 自动决定行：时间 · 动作 · 规则（审计 reason 只含规则，不含用户内容）。 */
    private fun autoDecisionRow(entry: ControlAuditEntry): TextView {
        val line = buildString {
            append("${UiKit.shortTime(entry.ts)} · ${entry.action}")
            entry.reason?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        }
        return UiKit.text(context, line, 11f, R.color.text_secondary).apply {
            setBackgroundResource(R.drawable.bg_system_note)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(5) }
        }
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}
