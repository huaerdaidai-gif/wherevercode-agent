package wherevercode.agent.core.task

import java.util.UUID

/**
 * P2 流水线协调器（2026-11-11）：规划 → 编码 → 干净上下文审查。
 *
 * 定序（调研报告 P2、老项目 team_workflows 五段 + 队长 + 2 轮上限
 * 学思路）：固定三段顺序，不是自由编排；单 Agent 仍是默认，流水线
 * 按任务 opt-in（[enablePipeline] 后才走；不开的任务与 P0 行为完全一致）。
 *
 * 三段与 P0 状态机的对齐：
 * - plan 段：任务态 stays queued；编码认领被门禁挡住（plan 未批准
 *   时 [canStartCoding] 为 false，两台泵都查它）。
 * - code 段：plan 已批准；编码者拿到的任务包由 [coderPacket] 从
 *   plan 填验收标准与文件清单（P0 TaskPacket 的空位）；一个任务
 *   同时只有一个写手——P0 的认领/租约/单任务规则保证，不另造锁。
 * - review 段：编码完成不停在 completed——执行器经
 *   [PipelineRunnerHook.shouldHoldAtReview] 把成功结果停在 review
 *   态（[advanceCodeToReview] 把阶段推到 review）；机械检查先跑，
 *   不过直接打回，不进 LLM 审查；审查 verdict 两值（approve /
 *   changes_requested），2 轮返工上限，超了进 failed 待用户定。
 *
 * 完成只认机械检查 + 审查 verdict：Agent 自己在结果文本里说的
 * “完成了”、勾选、口头保证，一律不算完成（老项目 §5.17 的 bug，
 * 这里没有文本勾选的读口——[recordReview] 只接受显式的 verdict
 * 值；机械检查 FAILED 时 approve 直接拒绝）。
 *
 * 审查输入干净（代码强制隔离）：[buildReviewInput] 的签名里根本
 * 没有聊天记录参数——审查者拿到的只有 plan + diff + 机械检查结果
 * （+ 任务包的目标/验收标准）；想塞聊天记录也无处塞。
 */
class PipelineOrchestrator(
    private val taskStore: TaskStore,
    private val scheduler: TaskScheduler,
    private val pipelineStore: PipelineStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    companion object {
        /** 返工上限 2 轮（老项目 LEAD_PLAN_MAX_RETRIES = 2，调研报告 P2 同数）。 */
        const val MAX_REWORK_ROUNDS: Int = 2

        /** 机械门打回时 verdict 行的 reviewer 标记。 */
        const val MECHANICAL_REVIEWER: String = "mechanical"
    }

    sealed class PipelineResult {
        data class Ok(val state: PipelineState, val note: String? = null) : PipelineResult()
        data class Rejected(val reason: String) : PipelineResult()
    }

    /** 详情页/卡片读的流水线视图（只读快照）。 */
    data class PipelineViewState(
        val taskId: String,
        val enabled: Boolean,
        val stage: String,
        val planStatus: String,
        val plan: PlanArtifact?,
        val reworkRounds: Int,
        val maxReworkRounds: Int = MAX_REWORK_ROUNDS,
        val coderAgentId: String?,
        val reviewerAgentId: String?,
        val verdicts: List<PipelineVerdictRecord>,
        val lastMechanical: MechanicalCheckResult?,
    )

    /** 审查者候选（agent + 设备；审查者优先选与编码者不同的 Agent/设备）。 */
    data class ReviewerCandidate(
        val agentId: String,
        val deviceId: String?,
        val label: String,
    )

    fun viewState(taskId: String, lastMechanical: MechanicalCheckResult? = null): PipelineViewState? {
        val state = pipelineStore.getState(taskId) ?: return null
        val verdicts = pipelineStore.listVerdicts(taskId)
        return PipelineViewState(
            taskId = taskId,
            enabled = state.enabled,
            stage = state.stage,
            planStatus = state.planStatus,
            plan = state.draftPlan,
            reworkRounds = state.reworkRounds,
            coderAgentId = state.coderAgentId,
            reviewerAgentId = state.reviewerAgentId,
            verdicts = verdicts,
            lastMechanical = lastMechanical
                ?: verdicts.lastOrNull()?.mechanical,
        )
    }

    /** 启用流水线（opt-in）：仅非终态任务；阶段落在 plan，等 plan。 */
    fun enablePipeline(taskId: String, actor: String = "user"): PipelineResult {
        val task = taskStore.getTask(taskId) ?: return PipelineResult.Rejected("任务不存在：$taskId")
        if (TaskStates.isTerminal(task.state)) {
            return PipelineResult.Rejected("终态任务不能启用流水线")
        }
        val existing = pipelineStore.getState(taskId)
        if (existing?.enabled == true) return PipelineResult.Ok(existing, "流水线已启用")
        val now = nowSeconds()
        val state = (existing ?: PipelineState(taskId = taskId, createdAtEpochSeconds = now))
            .copy(enabled = true, stage = PipelineStage.PLAN, updatedAtEpochSeconds = now)
        pipelineStore.saveState(state, pipelineEvent(task, TaskEventKind.PIPELINE_ENABLED, task.state, actor, "启用流水线：先出 plan，用户批准后才编码"))
        return PipelineResult.Ok(state)
    }

    /** 提交 plan 草稿：阶段须在 plan；plan 校验不过直接拒绝，不存坏 plan。 */
    fun submitPlan(taskId: String, plan: PlanArtifact, plannerId: String? = null): PipelineResult {
        val state = pipelineStore.getState(taskId)
            ?: return PipelineResult.Rejected("任务未启用流水线")
        if (!state.enabled) return PipelineResult.Rejected("任务未启用流水线")
        if (state.stage != PipelineStage.PLAN) {
            return PipelineResult.Rejected("当前阶段是${PipelineStage.labelOf(state.stage)}，不能提交 plan")
        }
        val problems = plan.validate()
        if (problems.isNotEmpty()) {
            return PipelineResult.Rejected("plan 不可批准：${problems.joinToString("；")}")
        }
        val task = taskStore.getTask(taskId)
        val now = nowSeconds()
        val next = state.copy(
            planStatus = PipelinePlanStatus.DRAFT,
            planJson = plan.copy(plannerId = plannerId ?: plan.plannerId, createdAtEpochSeconds = now).toJson(),
            updatedAtEpochSeconds = now,
        )
        pipelineStore.saveState(
            next,
            task?.let { pipelineEvent(it, TaskEventKind.PIPELINE_PLAN_SUBMITTED, it.state, plannerId ?: "planner", "提交 plan 草稿（待用户批准）") },
        )
        return PipelineResult.Ok(next)
    }

    /** 批准 plan（用户门）：草稿 → 已批准，阶段 plan → code，编码方可认领。 */
    fun approvePlan(taskId: String, approver: String = "user"): PipelineResult {
        val state = pipelineStore.getState(taskId)
            ?: return PipelineResult.Rejected("任务未启用流水线")
        if (state.stage != PipelineStage.PLAN) {
            return PipelineResult.Rejected("当前阶段是${PipelineStage.labelOf(state.stage)}，没有待批准的 plan")
        }
        if (state.planStatus != PipelinePlanStatus.DRAFT) {
            return PipelineResult.Rejected("没有待批准的 plan 草稿")
        }
        val plan = state.draftPlan
            ?: return PipelineResult.Rejected("plan 草稿损坏，无法批准")
        val problems = plan.validate()
        if (problems.isNotEmpty()) {
            return PipelineResult.Rejected("plan 不可批准：${problems.joinToString("；")}")
        }
        val task = taskStore.getTask(taskId)
        val now = nowSeconds()
        val next = state.copy(
            planStatus = PipelinePlanStatus.APPROVED,
            stage = PipelineStage.CODE,
            updatedAtEpochSeconds = now,
        )
        pipelineStore.saveState(
            next,
            task?.let { pipelineEvent(it, TaskEventKind.PIPELINE_PLAN_APPROVED, it.state, approver, "plan 已批准，进入编码") },
        )
        return PipelineResult.Ok(next)
    }

    /** 编码认领门：只在 plan 已批准（阶段 code）时放行；未启用流水线的任务一律放行。 */
    fun canStartCoding(taskId: String): Boolean {
        val state = pipelineStore.getState(taskId) ?: return true
        if (!state.enabled) return true
        return state.stage == PipelineStage.CODE
    }

    /**
     * 给编码者的任务包：plan 批准后，把 plan 的验收标准、文件清单、
     * 预期输出填进包里（P0 TaskPacket 的空位）；未启用/未批准时返回
     * 原包（验收标准空着，不编）。
     */
    fun coderPacket(task: TaskRecord): TaskPacket {
        val state = pipelineStore.getState(task.taskId)
        val plan = state?.approvedPlan ?: return TaskPacket.fromTask(task)
        return TaskPacket.fromTask(task).withPlan(plan)
    }

    /** 给规划者的提示词：任务包 + “只出 plan，不写代码”。 */
    fun buildPlannerPrompt(task: TaskRecord): String = buildString {
        append(TaskPacket.fromTask(task).toPromptText())
        append("\n\n你是规划者，只出 plan，不写代码。")
        append("输出一份 plan：目标、要改的文件清单、实施步骤、验收标准、测试命令。")
        append("不要写任何代码、不要改任何文件；plan 交给用户批准后才进编码。")
    }

    /** 机械检查（纯函数入口，调用方供改动文件清单与测试 verdict）。 */
    fun runMechanicalChecks(
        taskId: String,
        changedFiles: List<String>,
        testVerdict: PipelineTestVerdict,
    ): MechanicalCheckResult? {
        val plan = pipelineStore.getState(taskId)?.approvedPlan ?: return null
        return PipelineMechanicalChecks.run(plan, changedFiles, testVerdict, nowSeconds())
    }

    /**
     * 记录一次审查 verdict（审查者/用户调用；机械门打回走同一口）。
     *
     * - approve：机械检查 FAILED 时直接拒绝（不浪费的反面——不许
     *   带着失败的机械检查通过）；任务 review → completed，阶段 → done。
     * - changes_requested：轮次未满 2 轮 → 返工轮次 +1，走 P0
     *   [TaskScheduler.requestRework]（review → rework），阶段回 code；
     *   已满 2 轮 → 任务 review → failed（needsAttention，待用户定），
     *   不许无限返工。
     * verdict 行必写（append-only 历史）；reason/checklist 为空的打回
     * 拒绝——打回必须带具体、可检查的清单。
     */
    fun recordReview(
        taskId: String,
        verdict: String,
        reviewer: String,
        checklist: List<String> = emptyList(),
        mechanical: MechanicalCheckResult? = null,
    ): PipelineResult {
        if (!ReviewVerdict.isValid(verdict)) {
            return PipelineResult.Rejected("未知 verdict：$verdict（只认 approve / changes_requested）")
        }
        val state = pipelineStore.getState(taskId)
            ?: return PipelineResult.Rejected("任务未启用流水线")
        if (state.stage != PipelineStage.REVIEW) {
            return PipelineResult.Rejected("当前阶段是${PipelineStage.labelOf(state.stage)}，不在审查")
        }
        val task = taskStore.getTask(taskId)
            ?: return PipelineResult.Rejected("任务不存在：$taskId")
        if (task.state != "review") {
            return PipelineResult.Rejected("任务不在 review 态（当前 ${task.state}），不能审查")
        }
        if (verdict == ReviewVerdict.CHANGES_REQUESTED && checklist.isEmpty()) {
            return PipelineResult.Rejected("打回须带具体、可检查的返工清单，不能空着打回")
        }
        if (verdict == ReviewVerdict.APPROVE && mechanical != null && !mechanical.passed) {
            return PipelineResult.Rejected("机械检查未通过（${mechanical.failed.joinToString("；") { it.name }}），审查不得通过")
        }
        val now = nowSeconds()
        val record = PipelineVerdictRecord(
            verdictId = newPipelineVerdictId(),
            taskId = taskId,
            round = state.reworkRounds,
            verdict = verdict,
            reviewer = reviewer,
            checklist = checklist,
            mechanicalJson = mechanical?.toJson(),
            createdAtEpochSeconds = now,
        )
        if (verdict == ReviewVerdict.APPROVE) {
            // 批准不改 result：编码者的结果摘要留在任务行上（审查通过
            // 不是重写交付）；只推 completed。
            val done = updateTaskState(task, "completed")
                ?: return PipelineResult.Rejected("任务落 completed 失败（可能已被并发改动）")
            val next = state.copy(stage = PipelineStage.DONE, reviewerAgentId = reviewer, updatedAtEpochSeconds = now)
            pipelineStore.saveStateAndRecordVerdict(
                next,
                record,
                pipelineEvent(done, TaskEventKind.PIPELINE_REVIEW_APPROVED, "completed", reviewer, "审查通过（第 ${state.reworkRounds} 轮返工后）"),
            )
            return PipelineResult.Ok(next, "审查通过，任务已完成")
        }
        // changes_requested
        if (state.reworkRounds >= MAX_REWORK_ROUNDS) {
            val failed = updateTaskState(
                task, "failed",
                mapOf(
                    "error" to "返工已达 $MAX_REWORK_ROUNDS 轮上限，仍未通过审查，转人工定夺",
                    "needs_attention" to true,
                ),
            ) ?: return PipelineResult.Rejected("任务落 failed 失败（可能已被并发改动）")
            pipelineStore.recordVerdict(
                record,
                pipelineEvent(failed, TaskEventKind.PIPELINE_REWORK_EXHAUSTED, "failed", reviewer, "返工已达 $MAX_REWORK_ROUNDS 轮上限，转人工定夺"),
            )
            return PipelineResult.Ok(state, "返工已达 $MAX_REWORK_ROUNDS 轮上限，任务进 failed 待用户定")
        }
        val reworked = scheduler.requestRework(taskId, checklist.joinToString("；").take(4000))
            ?: return PipelineResult.Rejected("打回返工失败：任务不在 review 态")
        val next = state.copy(
            stage = PipelineStage.CODE,
            reworkRounds = state.reworkRounds + 1,
            reviewerAgentId = reviewer,
            updatedAtEpochSeconds = now,
        )
        pipelineStore.saveStateAndRecordVerdict(
            next,
            record,
            pipelineEvent(reworked, TaskEventKind.PIPELINE_REWORK_REQUESTED, "rework", reviewer, "打回返工（第 ${next.reworkRounds}/$MAX_REWORK_ROUNDS 轮）：${checklist.take(3).joinToString("；")}"),
        )
        return PipelineResult.Ok(next, "已打回返工（第 ${next.reworkRounds}/$MAX_REWORK_ROUNDS 轮）")
    }

    /**
     * 用户在审查页手动打回（P0 留下的返工按钮接线）：流水线任务走
     * [recordReview]（计返工轮次、守 2 轮上限）；未启用流水线的任务
     * 走 P0 [TaskScheduler.requestRework] 原样（行为不变）。
     */
    fun userRequestRework(taskId: String, reason: String): PipelineResult {
        val reasonLines = reason.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (reasonLines.isEmpty()) return PipelineResult.Rejected("打回须写原因（返工清单），不能空着打回")
        val state = pipelineStore.getState(taskId)
        if (state?.enabled != true) {
            val reworked = scheduler.requestRework(taskId, reason.take(4000))
                ?: return PipelineResult.Rejected("打回返工失败：任务不在 review 态")
            return PipelineResult.Ok(state ?: PipelineState(taskId = taskId, enabled = false), "已打回返工")
        }
        return recordReview(taskId, ReviewVerdict.CHANGES_REQUESTED, "user", reasonLines)
    }

    /**
     * 编码完成后的阶段推进（执行器 hold 回调）：阶段 code → review。
     * 任务态本身已由执行器停在 review（见 [PipelineRunnerHook]）。
     */
    fun advanceCodeToReview(taskId: String): Boolean {
        val state = pipelineStore.getState(taskId) ?: return false
        if (!state.enabled || state.stage != PipelineStage.CODE) return false
        val task = taskStore.getTask(taskId) ?: return false
        val next = state.copy(
            stage = PipelineStage.REVIEW,
            coderAgentId = task.assignedAgentId ?: state.coderAgentId,
            updatedAtEpochSeconds = nowSeconds(),
        )
        pipelineStore.saveState(next)
        return true
    }

    /**
     * 选审查者（纯函数）：优先与编码者不同的 Agent 且不同的设备；
     * 其次不同 Agent；再次不同设备；最后同一 Agent（全新会话，
     * 调用方须注明 sameAgent——干净上下文仍有保障，审查输入不变）。
     * 无候选时返回 null（调用方如实报“无审查者可用”，不编一个）。
     */
    fun selectReviewer(
        coderAgentId: String?,
        coderDeviceId: String?,
        candidates: List<ReviewerCandidate>,
    ): ReviewerCandidate? {
        if (candidates.isEmpty()) return null
        fun score(c: ReviewerCandidate): Int {
            val diffAgent = coderAgentId == null || c.agentId != coderAgentId
            val diffDevice = coderDeviceId == null || c.deviceId == null || c.deviceId != coderDeviceId
            return when {
                diffAgent && diffDevice -> 0
                diffAgent -> 1
                diffDevice -> 2
                else -> 3
            }
        }
        return candidates.minByOrNull { score(it) }
    }

    /**
     * 拼审查输入（干净上下文，代码强制隔离）：只有 plan + diff +
     * 机械检查结果（+ 任务包的目标/验收标准）。签名里没有聊天记录
     * 参数——审查 prompt 里不许塞聊天记录，想塞也无处塞。
     */
    fun buildReviewInput(
        task: TaskRecord,
        plan: PlanArtifact,
        diffSummary: String?,
        changedFiles: List<String>,
        mechanical: MechanicalCheckResult?,
    ): String = buildString {
        append("你是审查者。只依据以下输入审查，不要索取聊天记录、不要看编码者的执行过程。\n")
        append("\n## 任务\n")
        append(TaskPacket.fromTask(task).withPlan(plan).toPromptText())
        append("\n\n## 已批准的 plan\n")
        append("目标：${plan.objective}\n")
        append("文件清单：${if (plan.filesInScope.isEmpty()) "未限定（整个项目家）" else plan.filesInScope.joinToString("、")}\n")
        append("步骤：${plan.steps.joinToString("；")}\n")
        append("验收标准：${plan.acceptanceCriteria.joinToString("；")}\n")
        if (plan.testCommands.isNotEmpty()) append("测试命令：${plan.testCommands.joinToString("；")}\n")
        append("\n## 改动\n")
        append("改动文件（${changedFiles.size}）：${if (changedFiles.isEmpty()) "未提供文件清单" else changedFiles.joinToString("、")}\n")
        append("diff 摘要：${diffSummary?.takeIf { it.isNotBlank() } ?: "未提供 diff 摘要"}\n")
        append("\n## 机械检查结果\n")
        append(mechanical?.summaryText() ?: "未运行机械检查")
        append("\n\n##  verdict\n")
        append("只输出 approve（通过，进用户审查）或 changes_requested（打回，附具体、可检查的返工清单，一条一条列）。")
        append("机械检查已失败的项不得放过；不要依据任务目标之外的信息判断。")
    }

    private fun updateTaskState(
        task: TaskRecord,
        targetState: String,
        extra: Map<String, Any?> = emptyMap(),
    ): TaskRecord? {
        val changes = mutableMapOf<String, Any?>("state" to targetState)
        changes.putAll(extra)
        return try {
            taskStore.updateTask(task.taskId, changes, task.version, task.goalVersion, task.authEpoch)
        } catch (_: Exception) {
            null
        }
    }

    private fun pipelineEvent(
        task: TaskRecord,
        kind: String,
        toState: String,
        actor: String?,
        reason: String?,
    ): TaskEvent = TaskEvent(
        eventId = UUID.randomUUID().toString(),
        taskId = task.taskId,
        attemptId = task.attemptId,
        kind = kind,
        fromState = task.state,
        toState = toState,
        actor = actor,
        reason = reason,
        createdAtEpochSeconds = nowSeconds(),
    )
}

/**
 * 执行器侧的流水线钩子（P2 接线点）：两台执行器 + 成员回传在
 * 完成/认领时查它；hook 为 null 时行为与 P0 完全一致。
 */
interface PipelineRunnerHook {
    /** plan 未批准的流水线任务：不认领编码（false = 跳过，等 plan）。 */
    fun canStartCoding(taskId: String): Boolean

    /** 编码成功的流水线任务：停在 review，不自动 completed。 */
    fun shouldHoldAtReview(taskId: String): Boolean

    /** 已停在 review：把流水线阶段 code → review。 */
    fun onCodeFinished(taskId: String)

    /**
     * 给编码者的提示词：默认是 P0 任务包渲染文本；生产实现（协调器）
     * 覆盖为 plan 批准后的包（验收标准与文件清单已填进包里）。
     */
    fun coderPrompt(task: TaskRecord): String =
        TaskPacket.fromTask(task).toPromptText()
}

/** 生产实现：钩子直接读协调器（协调器读 PipelineStore）。 */
class PipelineRunnerHookImpl(
    private val orchestrator: PipelineOrchestrator,
) : PipelineRunnerHook {
    override fun canStartCoding(taskId: String): Boolean =
        orchestrator.canStartCoding(taskId)

    override fun shouldHoldAtReview(taskId: String): Boolean {
        val state = orchestrator.viewState(taskId) ?: return false
        return state.enabled && state.stage == PipelineStage.CODE
    }

    override fun onCodeFinished(taskId: String) {
        orchestrator.advanceCodeToReview(taskId)
    }

    override fun coderPrompt(task: TaskRecord): String =
        orchestrator.coderPacket(task).toPromptText()
}
