package wherevercode.agent.core.task

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 合并结果（合并任务的回报口径，调用方从成员回传里取）：
 * - [MergeResult.Merged]：已合进集成线（分支 + commit）。
 * - [MergeResult.Conflict]：合并不了——冲突文件清单 + 原因；
 *   调用方不得自行消解冲突，必须等用户裁决。
 */
sealed class MergeResult {
    data class Merged(val branch: String, val commit: String?) : MergeResult()
    data class Conflict(val conflictingFiles: List<String>, val detail: String) : MergeResult()

    fun toJson(): String {
        val obj = JSONObject()
        when (this) {
            is Merged -> {
                obj.put("kind", "merged")
                obj.put("branch", branch)
                if (commit != null) obj.put("commit", commit)
            }
            is Conflict -> {
                obj.put("kind", "conflict")
                obj.put("files", JSONArray(conflictingFiles))
                obj.put("detail", detail)
            }
        }
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): MergeResult? {
            return try {
                val obj = JSONObject(json)
                when (obj.optString("kind", "")) {
                    "merged" -> Merged(
                        branch = obj.optString("branch", ""),
                        commit = obj.optString("commit").takeIf { it.isNotEmpty() },
                    )
                    "conflict" -> {
                        val files = ArrayList<String>()
                        val arr = obj.optJSONArray("files")
                        if (arr != null) {
                            for (i in 0 until arr.length()) files += arr.optString(i, "")
                        }
                        Conflict(
                            conflictingFiles = files.filter { it.isNotEmpty() },
                            detail = obj.optString("detail", ""),
                        )
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * 任务拆分协调器（2026-11-11，大脑总管自动拆分）。
 *
 * 定序：提出拆分 → 批准拆分（门）→ 子任务并行执行 → 全部完成 →
 * 合并任务 → 合并完成 / 合并冲突进 blocked 等用户裁决。
 *
 * 与 P2 流水线的对齐：
 * - 拆分是任务之外的协调态（[DecompositionState]），不改 P0 状态机；
 *   父任务本身仍走 P0 态（拆分期间父任务在 working 态等子任务）。
 * - 子任务是真正的 [TaskRecord]（parentTaskId 指向父任务，state
 *   queued 起步）：认领/租约/幂等/一成员一次一任务全走 P0，不另造锁。
 * - 每个子任务一个分支 `task/<子任务 id>`（P0 TaskPacket 的分支规则）；
 *   合并目标是一条集成线 `integration/<父任务 id>`。
 * - 隔离靠派生 plan：子任务的 plan 把其他子任务的文件全写进
 *   filesOutOfScope，机械范围检查（P2）拦越界改动（见
 *   [TaskDecomposer.derivedPlan]）。
 * - 合并本身是**一个派给家设备成员的合并任务**：App 没有 git，
 *   合并不了代码，只能由家设备上的成员在检出里合。协调器只负责
 *   建合并任务、收合并结果、落状态与事件；合并任务的派发与执行
 *   是调用方（P1 能力路由 / 自主运行器）的活，与 P2 的规划者/
 *   审查者 runner 未接线同款诚实边界。
 *
 * 完成只认子任务终态 + 合并结果：子任务失败、合并冲突时父任务进
 * blocked + needsAttention，等用户裁决——绝不静默覆盖、绝不丢改动。
 */
class DecompositionOrchestrator(
    private val taskStore: TaskStore,
    private val decompositionStore: DecompositionStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    companion object {
        /** 集成线分支：`integration/<父任务 id>`。 */
        fun integrationBranch(parentTaskId: String): String = "integration/$parentTaskId"

        /** 子任务分支：`task/<子任务 id>`（P0 TaskPacket 的分支规则）。 */
        fun subtaskBranch(subtaskTaskId: String): String = "task/$subtaskTaskId"
    }

    sealed class DecompositionResult {
        data class Ok(val state: DecompositionState, val note: String? = null) : DecompositionResult()
        data class Rejected(val reason: String) : DecompositionResult()
    }

    sealed class MergeStartResult {
        data class Started(val mergeTask: TaskRecord, val state: DecompositionState) : MergeStartResult()
        data class Rejected(val reason: String) : MergeStartResult()
    }

    /** 子任务当前快照（状态只认 tasks 表那一行）。 */
    data class SubtaskStatus(
        val subtaskTaskId: String,
        val state: String,
        val files: List<String>,
        val branch: String,
    )

    /** 合并就绪检查的结果。 */
    sealed class MergeReadiness {
        /** 全部子任务已完成，可以建合并任务。 */
        data class Ready(val subtaskIds: List<String>, val branches: List<String>) : MergeReadiness()

        /** 还有子任务未完成：pending 是未终态的子任务清单。 */
        data class NotReady(val pending: List<SubtaskStatus>) : MergeReadiness()

        /** 合并不了：子任务失败/已取消，或拆分本身已阻塞/已合并。 */
        data class Blocked(val reason: String, val failedSubtasks: List<SubtaskStatus>) : MergeReadiness()
    }

    /** 频道/详情页读的拆分视图（只读快照）。 */
    data class DecompositionViewState(
        val taskId: String,
        val status: String,
        val integrationBranch: String,
        val baseBranch: String,
        val subtasks: List<SubtaskStatus>,
        val mergeResult: MergeResult?,
    )

    fun viewState(taskId: String): DecompositionViewState? {
        val state = decompositionStore.getState(taskId) ?: return null
        return DecompositionViewState(
            taskId = taskId,
            status = state.status,
            integrationBranch = state.integrationBranch,
            baseBranch = state.baseBranch,
            subtasks = subtaskStatuses(taskId),
            mergeResult = state.mergeResult,
        )
    }

    /**
     * 提出拆分（纯计算 + 父任务校验，不落盘）：按 plan 调拆分器。
     * 已有进行中拆分的父任务不再提第二个（先取消再重拆）。
     */
    fun proposeDecomposition(
        parentTaskId: String,
        plan: PlanArtifact,
        availableFiles: List<String> = emptyList(),
    ): DecompositionProposal {
        val parent = taskStore.getTask(parentTaskId)
            ?: return DecompositionProposal.Unsplittable("任务不存在：$parentTaskId")
        if (TaskStates.isTerminal(parent.state)) {
            return DecompositionProposal.Unsplittable("终态任务不能拆分")
        }
        val existing = decompositionStore.getState(parentTaskId)
        if (existing != null && existing.status in setOf(
                DecompositionStatus.PROPOSED,
                DecompositionStatus.APPROVED,
                DecompositionStatus.ACTIVE,
                DecompositionStatus.MERGING,
                DecompositionStatus.BLOCKED,
            )
        ) {
            return DecompositionProposal.Unsplittable(
                "已有进行中的拆分（状态：${DecompositionStatus.labelOf(existing.status)}），先取消再重拆",
            )
        }
        return TaskDecomposer.decompose(plan, availableFiles)
    }

    /**
     * 提交拆分方案（落盘为 proposed，等批准门）：只存方案，不建子任务。
     * baseBranch 是合并基底分支（调用方供，如 main；合并任务描述里也写明）。
     */
    fun submitDecomposition(
        parentTaskId: String,
        proposal: DecompositionProposal.Splittable,
        baseBranch: String,
        actor: String = "user",
    ): DecompositionResult {
        val parent = taskStore.getTask(parentTaskId)
            ?: return DecompositionResult.Rejected("任务不存在：$parentTaskId")
        if (TaskStates.isTerminal(parent.state)) {
            return DecompositionResult.Rejected("终态任务不能拆分")
        }
        if (baseBranch.isBlank()) {
            return DecompositionResult.Rejected("合并基底分支不能为空（调用方须给真实默认分支）")
        }
        if (proposal.subtasks.size < 2) {
            return DecompositionResult.Rejected("拆分至少要有 2 个子任务")
        }
        val existing = decompositionStore.getState(parentTaskId)
        if (existing != null && existing.status in setOf(
                DecompositionStatus.PROPOSED,
                DecompositionStatus.APPROVED,
                DecompositionStatus.ACTIVE,
                DecompositionStatus.MERGING,
                DecompositionStatus.BLOCKED,
            )
        ) {
            return DecompositionResult.Rejected(
                "已有进行中的拆分（状态：${DecompositionStatus.labelOf(existing.status)}），先取消再重拆",
            )
        }
        val now = nowSeconds()
        val state = DecompositionState(
            taskId = parentTaskId,
            status = DecompositionStatus.PROPOSED,
            integrationBranch = integrationBranch(parentTaskId),
            baseBranch = baseBranch.trim(),
            proposalJson = TaskDecomposer.proposalToJson(proposal),
            createdAtEpochSeconds = existing?.createdAtEpochSeconds ?: now,
            updatedAtEpochSeconds = now,
        )
        decompositionStore.saveState(
            state,
            decompositionEvent(parent, TaskEventKind.DECOMPOSITION_PROPOSED, parent.state, actor, "提出拆分：${proposal.subtasks.size} 个子任务（待批准）"),
        )
        return DecompositionResult.Ok(state, "已提出拆分：${proposal.subtasks.size} 个子任务，待批准")
    }

    /**
     * 批准拆分（门）：建子任务 TaskRecord（queued，parentTaskId 指向父任务）
     * + 子任务清单行，状态 → active。
     *
     * 幂等续建：批准中途失败（如某子任务创建抛错）时状态仍是 proposed，
     * 已建的子任务行还在；再次批准只补建缺的，不重复建。
     */
    fun approveDecomposition(parentTaskId: String, approver: String = "user"): DecompositionResult {
        val state = decompositionStore.getState(parentTaskId)
            ?: return DecompositionResult.Rejected("任务未提出拆分")
        if (state.status != DecompositionStatus.PROPOSED) {
            return DecompositionResult.Rejected("当前拆分状态是${DecompositionStatus.labelOf(state.status)}，没有待批准的拆分")
        }
        val proposal = state.proposal
            ?: return DecompositionResult.Rejected("拆分方案损坏，无法批准")
        val parent = taskStore.getTask(parentTaskId)
            ?: return DecompositionResult.Rejected("任务不存在：$parentTaskId")
        if (TaskStates.isTerminal(parent.state)) {
            return DecompositionResult.Rejected("终态任务不能拆分")
        }
        val now = nowSeconds()
        val existingRows = decompositionStore.listSubtasks(parentTaskId)
            .associateBy { it.subtaskIndex }
        var created = 0
        for (spec in proposal.subtasks.sortedBy { it.index }) {
            if (existingRows.containsKey(spec.index)) continue
            val childId = UUID.randomUUID().toString()
            val child = TaskRecord(
                taskId = childId,
                projectId = parent.projectId,
                title = "${parent.title}（拆分 ${spec.index + 1}/${proposal.subtasks.size}：${spec.moduleLabel}）",
                description = buildString {
                    append(spec.objective)
                    append("\n\n文件范围（只改这些文件）：\n")
                    spec.files.forEach { append("- $it\n") }
                    append("\n父任务：$parentTaskId\n分支：${subtaskBranch(childId)}")
                },
                state = "queued",
                parentTaskId = parentTaskId,
                priority = parent.priority,
                brainId = parent.brainId,
                createdAt = now,
                updatedAt = now,
            )
            try {
                taskStore.createTask(child)
            } catch (e: Exception) {
                return DecompositionResult.Rejected(
                    "子任务 ${spec.index + 1} 创建失败：${e.message}（已建 $created 个，再次批准会接着建，不重复建）",
                )
            }
            val record = DecompositionSubtaskRecord(
                subtaskTaskId = childId,
                parentTaskId = parentTaskId,
                subtaskIndex = spec.index,
                files = spec.files,
                branch = subtaskBranch(childId),
                objective = spec.objective,
                createdAtEpochSeconds = now,
                moduleLabel = spec.moduleLabel,
            )
            try {
                decompositionStore.insertSubtask(
                    record,
                    decompositionEvent(
                        parent, TaskEventKind.SUBTASK_CREATED, parent.state, approver,
                        "创建子任务 ${spec.index + 1}/${proposal.subtasks.size}：${spec.moduleLabel}，${spec.files.size} 个文件，分支 ${record.branch}",
                    ),
                )
            } catch (e: Exception) {
                return DecompositionResult.Rejected(
                    "子任务 ${spec.index + 1} 清单落盘失败：${e.message}（任务行已建，再次批准会接着建）",
                )
            }
            created++
        }
        val next = state.copy(status = DecompositionStatus.ACTIVE, updatedAtEpochSeconds = now)
        decompositionStore.saveState(
            next,
            decompositionEvent(parent, TaskEventKind.DECOMPOSITION_APPROVED, parent.state, approver, "批准拆分：${proposal.subtasks.size} 个子任务开始执行（本次新建 $created 个）"),
        )
        return DecompositionResult.Ok(next, "拆分已批准：${proposal.subtasks.size} 个子任务执行中")
    }

    /**
     * 子任务终态上报（执行器/泵在子任务落终态后调）：只观察、不推子任务
     * 的态——子任务自己的状态转移是执行器的活。
     * - 子任务完成 → 父任务事件流记一条，返回最新合并就绪检查
     *   （调用方可接着调 [startMerge]）。
     * - 子任务失败/取消 → 拆分进 blocked + 父任务进 blocked（working
     *   态时）或挂 needsAttention，返回 [MergeReadiness.Blocked]。
     */
    fun noteSubtaskFinished(parentTaskId: String, subtaskTaskId: String): MergeReadiness {
        val state = decompositionStore.getState(parentTaskId)
            ?: return MergeReadiness.Blocked("父任务没有拆分记录", emptyList())
        val parent = taskStore.getTask(parentTaskId)
            ?: return MergeReadiness.Blocked("父任务不存在", emptyList())
        val child = taskStore.getTask(subtaskTaskId)
            ?: return MergeReadiness.Blocked("子任务不存在：$subtaskTaskId", emptyList())
        val now = nowSeconds()
        return when {
            child.state == "completed" -> {
                decompositionStore.saveState(
                    state.copy(updatedAtEpochSeconds = now),
                    decompositionEvent(parent, TaskEventKind.SUBTASK_COMPLETED, parent.state, child.memberId ?: child.assignedAgentId, "子任务完成：${child.title}"),
                )
                checkMergeReadiness(parentTaskId)
            }
            child.state == "failed" || child.state == "cancelled" -> {
                val failed = subtaskStatuses(parentTaskId).filter { it.state == "failed" || it.state == "cancelled" }
                val reason = "子任务失败：${child.title}（${child.error?.take(200) ?: child.state}），合并不了，等用户裁决"
                blockParentTask(parent, reason)
                val next = state.copy(status = DecompositionStatus.BLOCKED, updatedAtEpochSeconds = now)
                // 两条事件、两个事实：子任务失败（SUBTASK_FAILED）与
                // 拆分因此阻塞（DECOMPOSITION_BLOCKED）。saveState 一
                // 次只带一条事件，所以同一状态存两次（upsert 幂等）。
                decompositionStore.saveState(
                    next,
                    decompositionEvent(parent, TaskEventKind.SUBTASK_FAILED, parent.state, child.memberId ?: child.assignedAgentId, reason),
                )
                decompositionStore.saveState(
                    next,
                    decompositionEvent(parent, TaskEventKind.DECOMPOSITION_BLOCKED, parent.state, child.memberId ?: child.assignedAgentId, reason),
                )
                MergeReadiness.Blocked(reason, failed)
            }
            else -> checkMergeReadiness(parentTaskId)
        }
    }

    /** 合并就绪检查：全部子任务 completed 才能建合并任务。 */
    fun checkMergeReadiness(parentTaskId: String): MergeReadiness {
        val state = decompositionStore.getState(parentTaskId)
            ?: return MergeReadiness.Blocked("父任务没有拆分记录", emptyList())
        if (state.status == DecompositionStatus.MERGED) {
            return MergeReadiness.Blocked("拆分已合并，无需再合", emptyList())
        }
        if (state.status == DecompositionStatus.BLOCKED) {
            val failed = subtaskStatuses(parentTaskId).filter { it.state == "failed" || it.state == "cancelled" }
            return MergeReadiness.Blocked("拆分已阻塞，等用户裁决", failed)
        }
        val statuses = subtaskStatuses(parentTaskId)
        val failed = statuses.filter { it.state == "failed" || it.state == "cancelled" }
        if (failed.isNotEmpty()) {
            return MergeReadiness.Blocked("子任务失败，合并不了", failed)
        }
        val pending = statuses.filter { it.state != "completed" }
        if (pending.isNotEmpty()) {
            return MergeReadiness.NotReady(pending)
        }
        if (statuses.isEmpty()) {
            return MergeReadiness.NotReady(emptyList())
        }
        return MergeReadiness.Ready(
            subtaskIds = statuses.map { it.subtaskTaskId },
            branches = statuses.map { it.branch },
        )
    }

    /**
     * 建合并任务（状态 → merging）：合并任务是普通 TaskRecord（queued，
     * parentTaskId 指向父任务），描述里写明基底分支、集成线、待合分支
     * 与合并规则。派发与执行是调用方的活（P1 能力路由选家设备成员，
     * 远端泵执行）；协调器只建任务、不派发。
     */
    fun startMerge(parentTaskId: String): MergeStartResult {
        val state = decompositionStore.getState(parentTaskId)
            ?: return MergeStartResult.Rejected("父任务没有拆分记录")
        if (state.status != DecompositionStatus.ACTIVE) {
            return MergeStartResult.Rejected("当前拆分状态是${DecompositionStatus.labelOf(state.status)}，不能开始合并")
        }
        val readiness = checkMergeReadiness(parentTaskId)
        if (readiness !is MergeReadiness.Ready) {
            return MergeStartResult.Rejected("合并就绪检查未过：$readiness")
        }
        val parent = taskStore.getTask(parentTaskId)
            ?: return MergeStartResult.Rejected("父任务不存在：$parentTaskId")
        val now = nowSeconds()
        val mergeTask = TaskRecord(
            taskId = UUID.randomUUID().toString(),
            projectId = parent.projectId,
            title = "合并集成：${parent.title}",
            description = buildMergeInstruction(
                parentTaskId = parentTaskId,
                baseBranch = state.baseBranch,
                integrationBranch = state.integrationBranch,
                branches = readiness.branches,
            ),
            state = "queued",
            parentTaskId = parentTaskId,
            priority = parent.priority,
            brainId = parent.brainId,
            createdAt = now,
            updatedAt = now,
        )
        try {
            taskStore.createTask(mergeTask)
        } catch (e: Exception) {
            return MergeStartResult.Rejected("合并任务创建失败：${e.message}")
        }
        val next = state.copy(status = DecompositionStatus.MERGING, updatedAtEpochSeconds = now)
        decompositionStore.saveState(
            next,
            decompositionEvent(parent, TaskEventKind.MERGE_STARTED, parent.state, "orchestrator", "开始合并：${readiness.branches.size} 个子任务分支合到 ${state.integrationBranch}"),
        )
        return MergeStartResult.Started(mergeTask, next)
    }

    /**
     * 收合并结果（合并任务落终态后，调用方从成员回传里取结果调）：
     * 只认“任务态与结果配对”——completed 配 Merged、failed 配
     * Conflict，配不上直接拒绝，不猜。
     * - Merged → 拆分态 merged，事件记合并完成（分支 + commit）。
     * - Conflict → 拆分态 blocked + 父任务进 blocked（working 态时）
     *   或挂 needsAttention，等用户裁决；合并任务本身的 failed 态
     *   是执行器落的，这里不动它。
     */
    fun recordMergeResult(
        mergeTaskId: String,
        result: MergeResult,
        actor: String = "merger",
    ): DecompositionResult {
        val mergeTask = taskStore.getTask(mergeTaskId)
            ?: return DecompositionResult.Rejected("合并任务不存在：$mergeTaskId")
        val parentTaskId = mergeTask.parentTaskId
            ?: return DecompositionResult.Rejected("合并任务没有父任务")
        val state = decompositionStore.getState(parentTaskId)
            ?: return DecompositionResult.Rejected("父任务没有拆分记录")
        if (state.status != DecompositionStatus.MERGING) {
            return DecompositionResult.Rejected("当前拆分状态是${DecompositionStatus.labelOf(state.status)}，不在合并中")
        }
        val parent = taskStore.getTask(parentTaskId)
            ?: return DecompositionResult.Rejected("父任务不存在：$parentTaskId")
        val now = nowSeconds()
        return when (result) {
            is MergeResult.Merged -> {
                if (mergeTask.state != "completed") {
                    return DecompositionResult.Rejected("合并任务当前是 ${mergeTask.state}，不是 completed，与“已合并”不符")
                }
                val next = state.copy(
                    status = DecompositionStatus.MERGED,
                    mergeResultJson = result.toJson(),
                    updatedAtEpochSeconds = now,
                )
                decompositionStore.saveState(
                    next,
                    decompositionEvent(parent, TaskEventKind.MERGE_COMPLETED, parent.state, actor, "合并完成：${result.branch}${result.commit?.let { " @ $it" } ?: ""}"),
                )
                DecompositionResult.Ok(next, "合并完成：${result.branch}")
            }
            is MergeResult.Conflict -> {
                if (mergeTask.state != "failed") {
                    return DecompositionResult.Rejected("合并任务当前是 ${mergeTask.state}，不是 failed，与“合并冲突”不符")
                }
                val reason = "合并冲突，等用户裁决：${result.detail.take(400)}" +
                    if (result.conflictingFiles.isNotEmpty()) "（冲突文件：${result.conflictingFiles.take(5).joinToString("、")}）" else ""
                blockParentTask(parent, reason)
                val next = state.copy(
                    status = DecompositionStatus.BLOCKED,
                    mergeResultJson = result.toJson(),
                    updatedAtEpochSeconds = now,
                )
                decompositionStore.saveState(
                    next,
                    decompositionEvent(parent, TaskEventKind.MERGE_CONFLICT, parent.state, actor, reason),
                )
                DecompositionResult.Ok(next, reason)
            }
        }
    }

    /**
     * 取消拆分：只在 proposed / active / blocked 时可取消；merging
     * （合并任务在途）与 merged 不可取消。子任务是独立的 P0 任务，
     * 不随拆分取消而自动取消——调用方按需逐个取消子任务。
     */
    fun cancelDecomposition(parentTaskId: String, actor: String = "user"): DecompositionResult {
        val state = decompositionStore.getState(parentTaskId)
            ?: return DecompositionResult.Rejected("父任务没有拆分记录")
        if (state.status !in setOf(
                DecompositionStatus.PROPOSED,
                DecompositionStatus.ACTIVE,
                DecompositionStatus.BLOCKED,
            )
        ) {
            return DecompositionResult.Rejected("当前拆分状态是${DecompositionStatus.labelOf(state.status)}，不能取消")
        }
        val parent = taskStore.getTask(parentTaskId)
            ?: return DecompositionResult.Rejected("父任务不存在：$parentTaskId")
        val now = nowSeconds()
        val next = state.copy(status = DecompositionStatus.CANCELLED, updatedAtEpochSeconds = now)
        decompositionStore.saveState(
            next,
            decompositionEvent(parent, TaskEventKind.DECOMPOSITION_CANCELLED, parent.state, actor, "取消拆分（子任务行不受影响，按需逐个取消）"),
        )
        return DecompositionResult.Ok(next, "拆分已取消")
    }

    /** 合并任务的描述：基底、集成线、待合分支、合并规则（执行者照单做）。 */
    fun buildMergeInstruction(
        parentTaskId: String,
        baseBranch: String,
        integrationBranch: String,
        branches: List<String>,
    ): String = buildString {
        append("你是合并者。把以下子任务分支逐个合进集成线 $integrationBranch（从 $baseBranch 建集成线）。\n")
        append("\n父任务：$parentTaskId")
        append("\n基底分支：$baseBranch")
        append("\n集成线：$integrationBranch")
        append("\n待合分支（按顺序，一个一个合）：\n")
        branches.forEachIndexed { i, branch -> append("${i + 1}. $branch\n") }
        append("\n规则：一次合一个，合完一个再合下一个；遇到冲突立刻停下，不要强行解决、不要覆盖、不要丢任何一方的改动；")
        append("回报时写清已合的分支、集成线 commit，或冲突文件清单与冲突原因。不要改基底分支，不要开 PR，不要合并到 $baseBranch。")
    }

    private fun subtaskStatuses(parentTaskId: String): List<SubtaskStatus> {
        val rows = decompositionStore.listSubtasks(parentTaskId)
        return rows.map { row ->
            val task = taskStore.getTask(row.subtaskTaskId)
            SubtaskStatus(
                subtaskTaskId = row.subtaskTaskId,
                state = task?.state ?: "missing",
                files = row.files,
                branch = row.branch,
            )
        }
    }

    /**
     * 父任务进 blocked（working 态时）或挂 needsAttention（其他非终态时）：
     * 合并不了、子任务失败，都要让用户在任务列表里一眼看见，不静默。
     * 终态父任务只记事件，不再动它。
     */
    private fun blockParentTask(parent: TaskRecord, reason: String): TaskRecord? {
        if (TaskStates.isTerminal(parent.state)) return parent
        return try {
            if (parent.state == "working") {
                updateTaskState(
                    parent, mapOf(
                        "state" to "blocked",
                        "error" to reason.take(4000),
                        "needs_attention" to true,
                    ),
                )
            } else if (!parent.needsAttention) {
                updateTaskState(
                    parent, mapOf(
                        "needs_attention" to true,
                        "error" to reason.take(4000),
                    ),
                )
            } else {
                parent
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateTaskState(task: TaskRecord, changes: Map<String, Any?>): TaskRecord? {
        val current = taskStore.getTask(task.taskId) ?: return null
        return try {
            taskStore.updateTask(task.taskId, changes, current.version, current.goalVersion, current.authEpoch)
        } catch (_: Exception) {
            null
        }
    }

    private fun decompositionEvent(
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
