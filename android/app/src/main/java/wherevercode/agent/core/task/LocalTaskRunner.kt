package wherevercode.agent.core.task

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import wherevercode.agent.brain.SettledTaskEvidence
import wherevercode.agent.brain.SkillRefiner
import wherevercode.agent.core.team.RemoteMemberVerdict
import wherevercode.agent.core.team.RemoteMemberVerdictClassifier
import wherevercode.agent.core.team.classifyRemoteMemberOutput

/**
 * Local task execution result. [summary] is the human-readable outcome
 * (final text for a brain turn); [error] is set only on failure.
 * Neither field is ever invented: a success carries what the executor
 * really produced, a failure carries the real error.
 */
data class TaskExecutionResult(
    val success: Boolean,
    val summary: String,
    val error: String? = null,
)

/** One tool call inside a dispatched task's execution, as seen by the runner. */
sealed class TaskStepEvent {
    data class Started(
        val toolCallId: String,
        val toolName: String,
        val argsSummary: String,
    ) : TaskStepEvent()

    data class Finished(
        val toolCallId: String,
        val ok: Boolean,
        val resultSummary: String?,
    ) : TaskStepEvent()
}

/**
 * What actually runs a claimed task. The runner owns claim / start /
 * step recording / terminal state; the executor only does the work.
 * Production supplies the brain executor (BrainBootstrap: one
 * `engine.chat()` turn over the task goal); tests supply a fake.
 */
interface TaskExecutor {
    suspend fun execute(
        task: TaskRecord,
        onStep: (TaskStepEvent) -> Unit,
    ): TaskExecutionResult
}

/**
 * The dispatch minimal chain (phase 1, U3): queued -> claimed ->
 * running -> done, for tasks the local App can execute itself.
 *
 * Rules (all enforced here, never in callers):
 * - Only ONE task executes at a time ([TaskScheduler] single-task rule).
 * - The runner only claims tasks with `sessionId == null`. Chat turns
 *   mirrored as tasks always carry a sessionId (see TurnTaskRecorder),
 *   so the runner can never steal a live chat turn's task. A task
 *   created by `dispatch_task` has no sessionId and is claimable.
 * - The runner never starts while other work is in hand
 *   ([TaskScheduler.activeWorkCount]): a dispatched task waits until
 *   the current chat turn's task finishes, so two engine turns never
 *   fight over the one process-wide TurnController.
 * - Terminal states follow [TaskStates]: `working -> review ->
 *   completed` (no direct `working -> completed` edge exists) and
 *   `working -> failed` / `working -> cancelled`.
 * - The 600s claim lease is renewed every 180s (1/3 of the lease)
 *   while a task runs, so a long task is never yanked back to
 *   requeued mid-execution. The pump also sweeps expired leases
 *   (P0, see [pumpOnceLocked]): the sweep only touches dispatched
 *   tasks (`sessionId IS NULL` in the store query), so a
 *   still-running chat turn's task — which always carries a
 *   sessionId and never renews its lease — can never be reclaimed.
 *   That guard is what makes the sweep safe; the old rule ("no
 *   caller expires leases today") left dead leases unreclaimed
 *   forever, which is exactly the durability gap P0 closes.
 * - Every state write is fenced by attemptId (P0): the runner
 *   captures the attemptId it claimed under, and a write is dropped
 *   when the task has since been re-queued and re-claimed under a
 *   new attempt — the version lock alone cannot fence this, because
 *   the write re-reads the newest version, and the state machine
 *   cannot either, because assigned -> working -> review -> completed
 *   is legal on the new attempt too.
 * - A `success == true` result is not trusted on its own: the
 *   production executor reports success when the turn finished
 *   without a `BrainEvent.Error`, and tool failures only arrive as
 *   `ToolResult(ok = false)` — so a brain that writes “failed” in
 *   its final text would still land completed. [finishTask] re-checks
 *   a success result's text with [RemoteMemberVerdict] first; a
 *   failure headline (or empty result) lands failed instead
 *   (2026-11-11, 22:46 local retry).
 * - Cross-device execution (LAN / pairing / HandClient) is NOT here:
 *   this runner executes locally only. A queued task whose goal needs
 *   another device stays queued and visible — never silently dropped
 *   and never pretended to be running.
 *
 * [onTasksChanged] fires on claim / start / each finished step /
 * terminal state, so MainActivity can refresh the task list without
 * polling. It runs on the runner's IO thread; the UI wraps it.
 */
class LocalTaskRunner(
    private val taskStore: TaskStore,
    private val scheduler: TaskScheduler,
    private val stepStore: TaskStepStore,
    private val executor: TaskExecutor,
    private val pollIntervalMs: Long = 10_000,
    private val leaseRenewIntervalMs: Long = 180_000,
    /**
     * V5 技能记忆（2026-11-11）：任务落终态时捕获技能（成功才记，
     * 失败记坑）。默认 null（老调用方与测试不动）；生产由
     * BrainBootstrap 注入 SkillRefiner(RoomSkillStore)。
     * 捕获失败绝不影响任务终态（内部 try/catch）。
     */
    private val skillRefiner: SkillRefiner? = null,
    /**
     * P1 eval 日志（2026-11-11）：任务落终态时记一条 eval
     * （类型/Agent/耗时/返工/成败）。默认 null（老调用方与测试不动）；
     * 生产由 BrainBootstrap 注入 EvalLogRecorder。记录失败绝不影响
     * 任务终态（内部 try/catch，与技能捕获同款）。
     */
    private val evalRecorder: EvalLogRecorder? = null,
    /**
     * P2 流水线钩子（2026-11-11）：默认 null（老调用方与测试不动，
     * 行为与 P0 完全一致）；生产由 BrainBootstrap 注入
     * PipelineRunnerHookImpl。钩子只做两件事：plan 未批准的流水线
     * 任务不认领（[pickNext] 查它），编码成功的流水线任务停在
     * review、不自动 completed（[finishTask] 查它）。
     */
    private val pipelineHook: PipelineRunnerHook? = null,
    /**
     * P0 round-4 instrumentation seam: when this returns true the pump
     * skips claiming entirely. Default false (all existing callers and
     * production behaviour unchanged). BrainBootstrap wires it to a
     * DEBUG-only hold file so the .ci instrumentation can hold a task
     * in `queued` deterministically while it verifies queued-state
     * control receipts (pause/resume/stop) against the real
     * ControlPlane; release builds never consult any file.
     */
    private val claimHold: () -> Boolean = { false },
) {
    companion object {
        /** The agent id a locally executed task is claimed under. */
        const val LOCAL_AGENT_ID: String = "local"
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var loopJob: Job? = null

    @Volatile
    private var executingJob: Job? = null

    @Volatile
    private var executingTaskId: String? = null

    /**
     * True from the moment [pumpOnce] starts until it finishes. The
     * chat path ([ChatViewModel]) refuses a new turn while this is
     * true: claiming a task and starting its execution are not atomic,
     * and a chat turn started in between would share the one
     * process-wide TurnController with the dispatched execution.
     */
    @Volatile
    private var busy: Boolean = false

    @Volatile
    var onTasksChanged: (() -> Unit)? = null

    /** Called only after a cooperative pause/stop reached the engine safe point. */
    @Volatile
    var onControlCheckpoint: ((String, String) -> Unit)? = null

    /** Reconcile persisted local controls after expired leases are swept. */
    @Volatile
    var onLeaseSweep: (() -> Unit)? = null

    fun isExecuting(): Boolean = executingTaskId != null

    fun isExecuting(taskId: String): Boolean = executingTaskId == taskId

    /** True while the runner is claiming or executing a task. */
    fun isBusy(): Boolean = busy

    fun executingTaskId(): String? = executingTaskId

    /** Start the background pump loop (idempotent). */
    fun start() {
        synchronized(lock) {
            if (loopJob?.isActive == true) return
            loopJob = scope.launch {
                while (isActive) {
                    try {
                        pumpOnce()
                    } catch (_: CancellationException) {
                        if (!isActive) break
                    } catch (_: Exception) {
                        // One task's failure never kills the loop.
                    }
                    try {
                        delay(pollIntervalMs)
                    } catch (_: CancellationException) {
                        break
                    }
                }
            }
        }
    }

    /** Stop the loop and cancel any in-flight execution (its task is marked cancelled). */
    fun stop() {
        synchronized(lock) {
            loopJob?.cancel()
            executingJob?.cancel()
            loopJob = null
        }
    }

    /**
     * Cancel the currently executing task. Returns false when [taskId]
     * is not the task in flight (queued tasks are cancelled by a direct
     * store update from the UI, not here).
     */
    fun cancel(taskId: String): Boolean {
        if (executingTaskId != taskId) return false
        executingJob?.cancel()
        return true
    }

    /**
     * Claim one queued dispatched task and run it to a terminal state.
     * Returns immediately when another task is in flight or no
     * dispatched task is queued. Sequential: the loop never runs two.
     */
    suspend fun pumpOnce() {
        if (executingJob?.isActive == true) return
        busy = true
        try {
            pumpOnceLocked()
        } finally {
            busy = false
        }
    }

    private suspend fun pumpOnceLocked() {
        if (executingJob?.isActive == true) return
        // P0：每轮先扫过期租约（死执行器的任务回队/入死信），再取活。
        // 扫失败不杀泵；扫只碰分派任务（sessionId 为 null），聊天轮次
        // 任务永不被扫（见 RoomTaskStore.expireLeases）。
        try {
            scheduler.releaseExpiredLeases()
        } catch (_: Exception) {
            // Sweep failure never kills the pump; next round retries.
        }
        try {
            onLeaseSweep?.invoke()
        } catch (_: Exception) {
            // Control reconciliation is retried on the next sweep.
        }
        if (claimHold()) return
        val candidate = pickNext() ?: return
        if (scheduler.activeWorkCount() != 0) return
        val claimed = scheduler.claimTask(candidate.taskId, LOCAL_AGENT_ID) ?: return
        val started = scheduler.startTask(claimed.taskId) ?: return
        onTasksChanged?.invoke()
        val job = scope.launch { runTask(started) }
        executingJob = job
        executingTaskId = started.taskId
        try {
            job.join()
        } catch (_: CancellationException) {
            // The job was cancelled: runTask already recorded the
            // task as cancelled. The loop must survive this.
        }
        executingJob = null
        executingTaskId = null
    }

    private fun pickNext(): TaskRecord? {
        val tasks = try {
            taskStore.listTasks()
        } catch (_: Exception) {
            return null
        }
        return tasks
            .filter { TaskStates.isClaimable(it.state) && it.sessionId == null }
            // The MCP namespace is reserved for the dispatcher and its
            // assigned executor. Do not let a queued/unassigned MCP task
            // become eligible for the local brain.
            .filterNot { TaskOrigins.isMcpDispatchTask(it.taskId) }
            // P2：plan 未批准的流水线任务不认领编码（等用户批准 plan）；
            // 未启用流水线的任务钩子一律放行。
            .filter { pipelineHook?.canStartCoding(it.taskId) ?: true }
            // 阶段 3（A1 P1）：已指派给别的 Agent 的任务，本地不代执行——
            // 保持 queued 等该 Agent 认领（跨设备执行是 P2/下一阶段）。
            // 只有未指派、或指派给本地执行器（LOCAL_AGENT_ID）的任务才认领。
            // 阶段 B：远程任务由 RemoteTaskPump 处理，见 listSkippedRemoteTasks。
            .filter { it.assignedAgentId == null || it.assignedAgentId == LOCAL_AGENT_ID }
            .minByOrNull { it.createdAt }
    }

    /**
     * 阶段 B（V5 紧急开发）：本轮次被本地执行器跳过的远程任务
     * （queued、未带 sessionId、已指派给远程成员）。供 UI / 诊断
     * 展示“等远端泵处理”，不由本地执行器认领或静默丢弃。
     * 远程任务的目标解析不到时，远端泵会把任务标 needs_attention
     * 并写明原因（不是这里）。
     */
    fun listSkippedRemoteTasks(
        isRemoteMember: (String) -> Boolean,
    ): List<TaskRecord> {
        val tasks = try {
            taskStore.listTasks()
        } catch (_: Exception) {
            return emptyList()
        }
        return tasks.filter { task ->
            TaskStates.isClaimable(task.state) && task.sessionId == null &&
                !TaskOrigins.isMcpDispatchTask(task.taskId) &&
                task.assignedAgentId != null &&
                isRemoteMember(task.assignedAgentId!!)
        }
    }

    private suspend fun runTask(task: TaskRecord) {
        // P0 围栏令牌：本次执行认领到的 attemptId；全部状态写都按它
        // 核对（见 updateTask），任务被回队重认领后旧执行器的写丢弃。
        val attemptId = task.attemptId
        // P1 eval：执行耗时从这里起算（认领后、执行前；不含排队等待）。
        val startedAtMs = System.currentTimeMillis()
        try {
            coroutineScope {
                val renewJob = launch {
                    while (isActive) {
                        delay(leaseRenewIntervalMs)
                        renewLease(task.taskId, attemptId)
                    }
                }
                try {
                    val result = executor.execute(task) { event ->
                        recordStepEvent(task.taskId, event)
                    }
                    finishTask(task.taskId, result, attemptId, startedAtMs)
                } catch (e: CancellationException) {
                    stepStore.closeRunningSteps(task.taskId)
                    finishAsCancelled(task.taskId, "任务被用户停止。", attemptId, startedAtMs)
                    throw e
                } catch (e: Exception) {
                    finishAsFailed(task.taskId, e.message ?: "执行异常：未知错误", attemptId, startedAtMs)
                } finally {
                    renewJob.cancel()
                }
            }
        } catch (_: CancellationException) {
            // Terminal state is already recorded as cancelled above;
            // swallow so the pump loop survives a user stop.
        }
    }

    private fun recordStepEvent(taskId: String, event: TaskStepEvent) {
        when (event) {
            is TaskStepEvent.Started -> {
                stepStore.addStep(
                    TaskStep(
                        stepId = UUID.randomUUID().toString(),
                        taskId = taskId,
                        toolCallId = event.toolCallId,
                        toolName = event.toolName,
                        argsSummary = event.argsSummary.take(500),
                        status = TaskStepStatus.RUNNING,
                        startedAtEpochSeconds = nowSeconds(),
                    ),
                )
            }
            is TaskStepEvent.Finished -> {
                stepStore.finishStep(
                    taskId = taskId,
                    toolCallId = event.toolCallId,
                    status = if (event.ok) TaskStepStatus.SUCCESS else TaskStepStatus.FAILED,
                    resultSummary = event.resultSummary?.take(1000),
                )
            }
        }
        onTasksChanged?.invoke()
    }

    /**
     * V5 技能记忆：任务落终态后从任务 + 步骤里抽技能存下。
     * 成功：走通的做法（工具序列 + 关键步骤）；失败：在最相关的
     * 已有技能上记坑。任何异常都吞掉——技能捕获绝不能把任务
     * 终态搞坏。
     */
    private fun captureSkill(taskId: String, success: Boolean, error: String?) {
        val refiner = skillRefiner ?: return
        try {
            val task = taskStore.getTask(taskId) ?: return
            val steps =
                try {
                    stepStore.listSteps(taskId)
                } catch (_: Exception) {
                    emptyList()
                }
            val successful = steps.filter { it.status == TaskStepStatus.SUCCESS }
            val evidence =
                SettledTaskEvidence(
                    taskId = taskId,
                    projectId = task.projectId,
                    goal = (task.description ?: task.title).trim(),
                    success = success,
                    error = error?.take(500),
                    toolsUsed = successful.map { it.toolName }.distinct(),
                    keySteps =
                        successful.take(6).map { step ->
                            val detail =
                                step.resultSummary?.take(100)?.ifBlank { null }
                                    ?: step.argsSummary.take(100)
                            "${step.toolName} $detail".trim()
                        }.filter { it.isNotBlank() },
                )
            refiner.onTaskSettled(evidence)
        } catch (_: Exception) {
            // 技能捕获失败不影响任务终态。
        }
    }

    private fun finishTask(taskId: String, result: TaskExecutionResult, attemptId: String?, startedAtMs: Long) {
        stepStore.closeRunningSteps(taskId)
        val controlCode = when {
            result.error?.startsWith("turn_paused") == true -> "paused"
            result.error?.startsWith("turn_stopped") == true -> "stopped"
            else -> null
        }
        if (controlCode != null) {
            // Pause and stop are confirmed only after the brain reports its
            // next safe point. A blocked/non-cooperative tool remains in its
            // real in-flight state until it returns; no false acknowledgement.
            if (controlCode == "paused") {
                finishAsPaused(taskId, "任务已在安全检查点暂停，可恢复后继续。", attemptId)
            } else {
                finishAsCancelled(taskId, "任务已在安全检查点停止。", attemptId, startedAtMs)
            }
            onControlCheckpoint?.invoke(taskId, controlCode)
            return
        }
        // 终态写真的落盘了才记技能、才刷 UI：围栏丢弃（任务已被回队
        // 重认领）时，本次执行的结果不是这行任务的终态，不记技能。
        // 2026-11-11（22:46 本地重试复现）：生产执行器只按“轮次跑完
        // 且无 BrainEvent.Error”报 success=true；工具报错只走
        // ToolResult(ok=false)，不影响 success——大脑在正文里写失败
        // 也会被记成完成。与远端路径同款：对 success 的结果文本也过
        // 一遍 RemoteMemberVerdict，开头明示失败（或空结果）→ 落
        // failed（error 写原文），不再走 working→review→completed。
        // 技能捕获与终态通知都以复核后的 effective 为准。
        val effective = if (result.success) {
            when (
                val verdict = classifyRemoteMemberOutput(
                    result.summary,
                    RemoteMemberVerdictClassifier.LOCAL_EMPTY_OUTPUT_REASON,
                )
            ) {
                is RemoteMemberVerdict.Failed -> TaskExecutionResult(
                    success = false,
                    summary = result.summary,
                    error = verdict.reason,
                )
                RemoteMemberVerdict.Success -> result
            }
        } else {
            result
        }
        val landed: Boolean = if (effective.success) {
            // working -> review -> completed: TaskStates has no direct
            // working -> completed edge. If the first step fails the
            // task stays working (visible, not faked complete).
            val current = try {
                taskStore.getTask(taskId)
            } catch (_: Exception) {
                null
            }
            if (current?.state == "assigned") {
                updateTask(taskId, mapOf("state" to "working"), attemptId)
            }
            if (updateTask(taskId, mapOf("state" to "review"), attemptId) == null) {
                false
            } else if (pipelineHook?.shouldHoldAtReview(taskId) == true) {
                // P2：流水线任务的编码完成停在 review，不自动 completed——
                // 审查（机械检查 + 干净上下文审查 + 用户）走完才由
                // PipelineOrchestrator.recordReview 推 completed。阶段
                // code → review 由钩子推进；这不是终态，不记技能。
                // 编码者的结果摘要照常落到任务行上（审查时看得到），
                // 写不上也不影响 hold（best-effort）。
                updateTask(taskId, mapOf("result" to result.summary.take(4000)), attemptId)
                pipelineHook.onCodeFinished(taskId)
                onTasksChanged?.invoke()
                return
            } else {
                updateTask(
                    taskId,
                    mapOf(
                        "state" to "completed",
                        "result" to result.summary.take(4000),
                    ),
                    attemptId,
                ) != null
            }

        } else {
            updateTask(
                taskId,
                mapOf(
                    "state" to "failed",
                    "error" to (effective.error ?: "执行失败").take(4000),
                ),
                attemptId,
            ) != null
        }
        if (!landed) return
        // V5 技能记忆：终态已落，抽技能（成功记做法，失败记坑）。
        captureSkill(taskId, effective.success, effective.error)
        // P1 eval 日志：终态已落，记一条（失败不影响终态）。
        recordEval(taskId, if (effective.success) EvalOutcome.COMPLETED else EvalOutcome.FAILED, startedAtMs)
        onTasksChanged?.invoke()
    }

    private fun finishAsFailed(taskId: String, error: String, attemptId: String?, startedAtMs: Long) {
        stepStore.closeRunningSteps(taskId)
        val landed = updateTask(
            taskId,
            mapOf("state" to "failed", "error" to error.take(4000)),
            attemptId,
        ) != null
        if (!landed) return
        // V5 技能记忆：异常失败也记坑（若有相关技能）。
        captureSkill(taskId, false, error)
        // P1 eval 日志。
        recordEval(taskId, EvalOutcome.FAILED, startedAtMs)
        onTasksChanged?.invoke()
    }

    private fun finishAsCancelled(taskId: String, note: String, attemptId: String?, startedAtMs: Long) {
        val landed = updateTask(
            taskId,
            mapOf("state" to "cancelled", "result" to note),
            attemptId,
        ) != null
        if (!landed) return
        // P1 eval 日志（取消也是终态结论，记一条；outcome=CANCELLED）。
        recordEval(taskId, EvalOutcome.CANCELLED, startedAtMs)
        onTasksChanged?.invoke()
    }

    /** Keep a safely paused task resumable; cancellation is terminal. */
    private fun finishAsPaused(taskId: String, note: String, attemptId: String?) {
        val landed = updateTask(taskId, mapOf("state" to "requeued", "result" to note), attemptId) != null
        if (landed) onTasksChanged?.invoke()
    }

    /**
     * P1 eval 日志：终态已落才记（与技能捕获同款：任何异常都吞掉，
     * 记录绝不能把任务终态搞坏）。本地执行无设备，deviceId 为空；
     * assignedViaFallback="local"（路由日志里没决策时就记 local）。
     */
    private fun recordEval(taskId: String, outcome: EvalOutcome, startedAtMs: Long) {
        val recorder = evalRecorder ?: return
        try {
            val task = taskStore.getTask(taskId) ?: return
            recorder.recordTerminal(
                task,
                outcome,
                mode = "single",
                startedAtMs = startedAtMs,
                deviceId = "",
                assignedViaFallback = "local",
            )
        } catch (_: Exception) {
            // eval 记录失败不影响任务终态。
        }
    }

    private fun renewLease(taskId: String, attemptId: String?) {
        val now = nowSeconds()
        updateTask(
            taskId,
            mapOf("lease_expires_at" to (now + RoomTaskStore.LEASE_SECONDS)),
            attemptId,
        )
    }

    /**
     * Write with the record's current version/counters (optimistic
     * lock). Returns null instead of throwing: a stale write must not
     * kill the pump loop, and the task stays visible in its last
     * real state.
     *
     * P0 attempt fence: [expectedAttemptId] is the attemptId this
     * execution claimed under. When the task's current attemptId
     * differs, the task has been re-queued and re-claimed under a new
     * attempt — this execution is stale and its write is dropped
     * (returns null, same as a stale version). Both null means a
     * legacy row claimed before attempt fencing: allowed.
     */
    private fun updateTask(
        taskId: String,
        changes: Map<String, Any?>,
        expectedAttemptId: String? = null,
    ): TaskRecord? {
        return try {
            val current = taskStore.getTask(taskId) ?: return null
            if (current.attemptId != expectedAttemptId) return null
            taskStore.updateTask(
                taskId,
                changes,
                current.version,
                current.goalVersion,
                current.authEpoch,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}
