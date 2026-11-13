package wherevercode.agent.core.control

import java.util.Collections
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteControlSender
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.security.AuthorizationStore
import wherevercode.agent.core.security.RevocationStore
import wherevercode.agent.core.task.TaskControlCounterStore
import wherevercode.agent.core.task.LocalTaskRunner
import wherevercode.agent.core.task.TaskOrigins
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStore

/**
 * ControlPlane: the single entry point for user-sovereignty control
 * commands (M2, plan §1.1 C1). This file pins the PUBLIC API that the
 * session layer (S4 ControlInstructionRouter) calls; the method
 * signatures, [ControlAction], [ControlStatus] and [ControlResult]
 * must NOT change. The A route implements the bodies (and may extend
 * the constructor with ControlStore / GoalVersion / AuthorizationEpoch /
 * CancellationToken / ExecutionFence / ControlAuditLog); the B route
 * treats this file as READ-ONLY and calls it only through
 * [ControlCommandPort] (tests use a fake, never this skeleton: the
 * bodies below are TODO and throw).
 *
 * Semantics (plan §4, GPT 08:10 decisions U1–U12):
 * - Six actions only (U10): PAUSE / RESUME / STOP / CHANGE_GOAL /
 *   REVOKE / TAKEOVER. Person-swap is a later explicit action and is
 *   refused here, never bypassing the gate.
 * - Every call takes a client-generated stable [operationId] (U2); the
 *   same id retried executes once (server-side persistent dedup).
 * - Each command runs in one Room transaction: state change +
 *   counter bump + audit row (C8). [ControlResult.confirmed] is false
 *   while a stop/pause awaits confirmation from an in-flight executor
 *   (U4/U6); unconfirmed stops stay pending and block new dispatch.
 * - TAKEOVER (U7) = freeze auto-dispatch + revoke/fence old execution
 *   rights + keep logs/artifacts + hand control to the user; an
 *   external process that has not stopped must be shown unconfirmed.
 *
 * Control works even when the brain is unavailable: this class never
 * calls a model (user-sovereignty layer above the brain).
 */
enum class ControlAction {
    PAUSE,
    RESUME,
    STOP,
    CHANGE_GOAL,
    REVOKE,
    TAKEOVER,
}

/** Per-task control status (C2). CANCELLED lives in TaskStates' terminal states. */
enum class ControlStatus {
    RUNNING,
    PAUSING,
    PAUSED,
    STOPPING,
}

data class ControlResult(
    val ok: Boolean,
    val confirmed: Boolean,
    val status: ControlStatus,
    val errorCode: String? = null,
    val reason: String? = null,
)

/** Narrow port the session layer depends on. */
interface ControlCommandPort {
    fun pause(taskId: String, operationId: String, reason: String): ControlResult
    fun resume(taskId: String, operationId: String, reason: String): ControlResult
    fun stop(taskId: String, operationId: String, reason: String): ControlResult
    fun retarget(taskId: String, newGoal: String, operationId: String, reason: String): ControlResult
    fun revoke(taskId: String, operationId: String, reason: String): ControlResult
    fun takeover(taskId: String, operationId: String, reason: String): ControlResult
    fun getControlStatus(taskId: String): ControlStatus
}

class ControlPlane(
    private val taskStore: TaskStore,
    @Suppress("UNUSED_PARAMETER") private val authStore: AuthorizationStore,
    private val revocationStore: RevocationStore,
    private val controlStore: ControlStore = InMemoryControlStore(),
    private val auditLog: ControlAuditLog = InMemoryControlAuditLog(),
    counterSync: TaskControlCounterStore? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val actor: String = "user",
    /**
     * 阶段 B（V5 紧急开发）：远端控制接线（可选；不传时行为与原来
     * 完全一致）。[remoteTargetResolver] 由任务的 assignedAgentId
     * 找到它正在执行的远端目标；[remoteControlSender] 把控制指令
     * 发往该目标。本地控制语义不变：远端回执到之前，本地态保持
     * 待确认（PAUSING/STOPPING），绝不先写已确认。
     */
    @Volatile private var remoteTargetResolver: ((String?) -> RemoteTarget?)? = null,
    @Volatile private var remoteControlSender: RemoteControlSender? = null,
    @Volatile private var localExecutionIsActive: ((String) -> Boolean)? = null,
    @Volatile private var localExecutionControl: ((String, ControlAction) -> Boolean)? = null,
) : ControlCommandPort {
    private val counters: TaskControlCounterStore? =
        counterSync ?: (taskStore as? TaskControlCounterStore)
    private val goalVersion = GoalVersion(controlStore)
    private val authEpoch = AuthorizationEpoch(controlStore)
    private val token = CancellationToken(controlStore, taskStore, nowEpochSeconds)

    @Volatile private var taskTargetResolver: ((TaskRecord) -> RemoteTarget?)? = null

    fun configureTaskTargetResolver(resolver: (TaskRecord) -> RemoteTarget?) {
        taskTargetResolver = resolver
    }

    fun configureRemoteControl(
        targetResolver: ((String?) -> RemoteTarget?)?,
        controlSender: RemoteControlSender?,
    ) {
        remoteTargetResolver = targetResolver
        remoteControlSender = controlSender
    }

    /** Connect local task controls to the active executor's safe-point signal. */
    fun configureLocalExecutionControl(
        isExecuting: ((String) -> Boolean)?,
        handler: ((String, ControlAction) -> Boolean)?,
    ) {
        localExecutionIsActive = isExecuting
        localExecutionControl = handler
    }

    // -- ControlCommandPort -------------------------------------------------

    override fun pause(taskId: String, operationId: String, reason: String): ControlResult {
        val result = execute("PAUSE", taskId, operationId, reason) { task ->
            if (TaskStates.isTerminal(task.state)) {
                return@execute ControlResult(
                    ok = false, confirmed = false, status = getControlStatus(taskId),
                    errorCode = "control_terminal_task", reason = "任务已结束（${task.state}），无法暂停",
                )
            }
            when (getControlStatus(taskId)) {
                ControlStatus.PAUSED -> ControlResult(
                    ok = true, confirmed = true, status = ControlStatus.PAUSED,
                    reason = "任务已处于暂停状态",
                )
                ControlStatus.STOPPING -> ControlResult(
                    ok = false, confirmed = false, status = ControlStatus.STOPPING,
                    errorCode = "control_stopping", reason = "任务正在停止，无法暂停",
                )
                else -> {
                    // Pause freezes new dispatch first (U7 pattern: freeze
                    // before touching execution), then asks the executor.
                    controlStore.setDispatchFrozen(taskId, true)
                    if (isTaskInFlight(task.state)) {
                        controlStore.setControlStatus(taskId, ControlStatus.PAUSING)
                        ControlResult(
                            ok = true, confirmed = false, status = ControlStatus.PAUSING,
                            reason = "任务执行中，已请求暂停，等待执行方确认停止",
                        )
                    } else {
                        controlStore.setControlStatus(taskId, ControlStatus.PAUSED)
                        ControlResult(
                            ok = true, confirmed = true, status = ControlStatus.PAUSED,
                            reason = "任务已暂停",
                        )
                    }
                }
            }
        }
        maybeSendRemoteControl(ControlAction.PAUSE, taskId, operationId, result)
        if (result.status == ControlStatus.PAUSING && localExecutionIsActive?.invoke(taskId) != false) {
            localExecutionControl?.invoke(taskId, ControlAction.PAUSE)
        }
        return result
    }

    override fun resume(taskId: String, operationId: String, reason: String): ControlResult {
        val result = execute("RESUME", taskId, operationId, reason) { task ->
            if (TaskStates.isTerminal(task.state)) {
                return@execute ControlResult(
                    ok = false, confirmed = false, status = getControlStatus(taskId),
                    errorCode = "control_terminal_task", reason = "任务已结束（${task.state}），无法恢复",
                )
            }
            when (getControlStatus(taskId)) {
                ControlStatus.RUNNING -> ControlResult(
                    ok = true, confirmed = true, status = ControlStatus.RUNNING,
                    reason = "任务已在运行",
                )
                ControlStatus.STOPPING -> ControlResult(
                    ok = false, confirmed = false, status = ControlStatus.STOPPING,
                    errorCode = "control_stopping", reason = "任务正在停止，无法恢复",
                )
                ControlStatus.PAUSING -> ControlResult(
                    ok = false, confirmed = false, status = ControlStatus.PAUSING,
                    errorCode = "control_pausing", reason = "暂停尚未确认，不能恢复",
                )
                else -> {
                    if (canRecoverExpiredAttempt(task)) releasePausedAttempt(task)
                    token.confirm(taskId)
                    controlStore.setDispatchFrozen(taskId, false)
                    controlStore.setControlStatus(taskId, ControlStatus.RUNNING)
                    ControlResult(
                        ok = true, confirmed = true, status = ControlStatus.RUNNING,
                        reason = "任务已恢复，可重新派单",
                    )
                }
            }
        }
        maybeSendRemoteControl(ControlAction.RESUME, taskId, operationId, result)
        return result
    }

    override fun stop(taskId: String, operationId: String, reason: String): ControlResult {
        val result = execute("STOP", taskId, operationId, reason) { task ->
            val status = getControlStatus(taskId)
            if (TaskStates.isTerminal(task.state)) {
                // Already ended: a repeat stop after our own stop is a
                // no-op success; any other terminal state refuses.
                if (status == ControlStatus.STOPPING) {
                    return@execute ControlResult(
                        ok = true, confirmed = true, status = ControlStatus.STOPPING,
                        reason = "任务已停止",
                    )
                }
                return@execute ControlResult(
                    ok = false, confirmed = false, status = status,
                    errorCode = "control_terminal_task", reason = "任务已结束（${task.state}），无需停止",
                )
            }
            if (status == ControlStatus.STOPPING) {
                return@execute ControlResult(
                    ok = true, confirmed = false, status = ControlStatus.STOPPING,
                    reason = "任务正在停止，等待执行方确认",
                )
            }
            controlStore.setDispatchFrozen(taskId, true)
            if (isTaskInFlight(task.state)) {
                token.request(taskId)
                controlStore.setControlStatus(taskId, ControlStatus.STOPPING)
                ControlResult(
                    ok = true, confirmed = false, status = ControlStatus.STOPPING,
                    reason = "已请求停止，等待执行方确认；未确认前阻止新派单",
                )
            } else {
                // No executor in flight: stop takes effect at once.
                taskStore.updateTask(
                    taskId,
                    mapOf("state" to "cancelled"),
                    task.version, task.goalVersion, task.authEpoch,
                )
                token.confirm(taskId)
                controlStore.setDispatchFrozen(taskId, false)
                controlStore.setControlStatus(taskId, ControlStatus.STOPPING)
                ControlResult(
                    ok = true, confirmed = true, status = ControlStatus.STOPPING,
                    reason = "任务已停止",
                )
            }
        }
        maybeSendRemoteControl(ControlAction.STOP, taskId, operationId, result)
        if (result.status == ControlStatus.STOPPING && localExecutionIsActive?.invoke(taskId) != false) {
            localExecutionControl?.invoke(taskId, ControlAction.STOP)
        }
        return result
    }

    override fun retarget(taskId: String, newGoal: String, operationId: String, reason: String): ControlResult {
        val result = execute("CHANGE_GOAL", taskId, operationId, reason) { task ->
            if (newGoal.isBlank()) {
                return@execute ControlResult(
                    ok = false, confirmed = false, status = getControlStatus(taskId),
                    errorCode = "control_bad_goal", reason = "新目标不能为空",
                )
            }
            if (TaskStates.isTerminal(task.state)) {
                return@execute ControlResult(
                    ok = false, confirmed = false, status = getControlStatus(taskId),
                    errorCode = "control_terminal_task", reason = "任务已结束（${task.state}），无法改目标",
                )
            }
            val newGoalVersion = bumpGoalVersion(task)
            counters?.updateControlCounters(taskId, newGoalVersion, task.authEpoch)
            taskStore.updateTask(
                taskId,
                mapOf("description" to newGoal),
                task.version, newGoalVersion, task.authEpoch,
            )
            ControlResult(
                ok = true, confirmed = true, status = getControlStatus(taskId),
                reason = "目标已更新，goalVersion=$newGoalVersion",
            )
        }
        maybeSendRemoteControl(ControlAction.CHANGE_GOAL, taskId, operationId, result, newGoal)
        return result
    }

    override fun revoke(taskId: String, operationId: String, reason: String): ControlResult {
        val result = execute("REVOKE", taskId, operationId, reason) { task ->
            val newEpoch = bumpAuthEpoch(task)
            revocationStore.revokeTask(taskId)
            counters?.updateControlCounters(taskId, task.goalVersion, newEpoch)
            ControlResult(
                ok = true, confirmed = true, status = getControlStatus(taskId),
                reason = "已撤销任务授权，epoch=$newEpoch，旧凭证失效",
            )
        }
        maybeSendRemoteControl(ControlAction.REVOKE, taskId, operationId, result)
        return result
    }

    override fun takeover(taskId: String, operationId: String, reason: String): ControlResult {
        val result = execute("TAKEOVER", taskId, operationId, reason) { task ->
            if (TaskStates.isTerminal(task.state)) {
                return@execute ControlResult(
                    ok = false, confirmed = false, status = getControlStatus(taskId),
                    errorCode = "control_terminal_task", reason = "任务已结束（${task.state}），无需接管",
                )
            }
            // Takeover = freeze auto-dispatch + revoke/fence old execution
            // rights + keep logs/artifacts + hand control to the user (U7).
            val newEpoch = bumpAuthEpoch(task)
            revocationStore.revokeTask(taskId)
            counters?.updateControlCounters(taskId, task.goalVersion, newEpoch)
            controlStore.setDispatchFrozen(taskId, true)
            taskStore.updateTask(
                taskId,
                mapOf("needs_attention" to true),
                task.version, task.goalVersion, newEpoch,
            )
            if (isTaskInFlight(task.state)) {
                token.request(taskId)
                controlStore.setControlStatus(taskId, ControlStatus.PAUSING)
                ControlResult(
                    ok = true, confirmed = false, status = ControlStatus.PAUSING,
                    reason = "已接管：自动派单已冻结，旧执行权已撤销；外部执行尚未确认停止",
                )
            } else {
                controlStore.setControlStatus(taskId, ControlStatus.PAUSED)
                ControlResult(
                    ok = true, confirmed = true, status = ControlStatus.PAUSED,
                    reason = "已接管：自动派单已冻结，旧执行权已撤销，无在途执行",
                )
            }
        }
        maybeSendRemoteControl(ControlAction.TAKEOVER, taskId, operationId, result)
        return result
    }

    override fun getControlStatus(taskId: String): ControlStatus =
        controlStore.getControlStatus(taskId) ?: ControlStatus.RUNNING

    // -- Remote delivery (stage B): after the local state change, the
    // same command goes to the remote executor that actually runs the
    // task. The local result is returned as-is; the remote reply only
    // ever confirms a pending state, never rewrites a local decision. --

    /** operationIds whose remote command was already sent (dedup: a replayed operationId never re-sends). */
    private val sentRemoteControls = Collections.synchronizedSet(mutableSetOf<String>())

    private fun maybeSendRemoteControl(
        action: ControlAction,
        taskId: String,
        operationId: String,
        result: ControlResult,
        newGoal: String? = null,
    ) {
        val sender = remoteControlSender ?: return
        val resolver = remoteTargetResolver ?: return
        if (!result.ok) return
        // 只有本地确实需要远端配合的才发：pause/stop 只在“在途未确认”时发；
        // resume/改目标/撤销/接管只要本地成功就发（远端无该任务时服务端
        // 回 confirmed no-op，不当错误）。
        val needsSend = when (action) {
            ControlAction.PAUSE -> !result.confirmed && result.status == ControlStatus.PAUSING
            ControlAction.STOP -> !result.confirmed && result.status == ControlStatus.STOPPING
            ControlAction.RESUME -> result.status == ControlStatus.RUNNING
            ControlAction.CHANGE_GOAL -> true
            ControlAction.REVOKE -> true
            ControlAction.TAKEOVER -> true
        }
        if (!needsSend) return
        val task = taskStore.getTask(taskId) ?: return
        if (getControlStatus(taskId) != result.status) return
        val taskResolver = taskTargetResolver
        val target = (if (taskResolver != null) taskResolver(task) else resolver(task.assignedAgentId)) ?: return
        if (!sentRemoteControls.add(operationId)) return
        // 快照读任务行（账本权威）：任务行与 ControlStore 计数器正常
        // 是一起涨的；任务行若领先，以任务行为准，绝不发一个比任务行
        // 旧的快照（远端会当成过期拒绝）。
        val command = RemoteControlCommand(
            action = action,
            operationId = operationId,
            newGoal = newGoal,
            goalVersion = task.goalVersion,
            epoch = task.authEpoch,
        )
        try {
            sender.send(target, taskId, command) { remoteResult ->
                if (!remoteResult.confirmed) {
                    sentRemoteControls.remove(operationId)
                    return@send
                }
                // 确认用派生的新 operationId：execute() 按 operationId
                // 去重，复用原 operationId 会被当成重放而直接返回旧结果。
                val confirmOpId = "$operationId:remote-confirm"
                when (action) {
                    ControlAction.PAUSE -> confirmRemotePaused(taskId, confirmOpId, remoteResult.message)
                    ControlAction.STOP -> confirmRemoteStopped(taskId, confirmOpId, remoteResult.message)
                    ControlAction.REVOKE -> confirmRemoteStopped(taskId, confirmOpId, remoteResult.message)
                    ControlAction.TAKEOVER -> confirmRemoteStopped(taskId, confirmOpId, remoteResult.message)
                    else -> Unit
                }
            }
        } catch (_: Exception) {
            // 发送本身失败：不记已发送，同一 operationId 重试时可再发；
            // 本地态保持待确认，不伪造已送达。
            sentRemoteControls.remove(operationId)
        }
    }

    /**
     * 远端确认暂停：与 [confirmPaused] 不同，本方法不要求本地账本
     * 已脱离在途态——远端进程已停是事实（SIGSTOP 回执），本地账本
     * 的 "working" 只是还没收到回传的终态。只在 PAUSING 时转 PAUSED。
     */
    fun confirmRemotePaused(taskId: String, operationId: String, reason: String): ControlResult =
        execute("CONFIRM_REMOTE_PAUSE", taskId, operationId, reason) { _ ->
            when (getControlStatus(taskId)) {
                ControlStatus.PAUSED -> ControlResult(
                    ok = true, confirmed = true, status = ControlStatus.PAUSED,
                    reason = "任务已处于暂停状态",
                )
                ControlStatus.PAUSING -> {
                    controlStore.setControlStatus(taskId, ControlStatus.PAUSED)
                    ControlResult(
                        ok = true, confirmed = true, status = ControlStatus.PAUSED,
                        reason = "暂停已确认：远端执行已实际停止",
                    )
                }
                else -> ControlResult(
                    ok = false, confirmed = false, status = getControlStatus(taskId),
                    errorCode = "control_not_pausing", reason = "任务未处于暂停中",
                )
            }
        }

    /**
     * 远端确认停止：与 [confirmStop] 不同，不要求本地账本已脱离
     * 在途态——远端进程已停是事实。任务已终态时保留结果，不覆盖。
     */
    fun confirmRemoteStopped(taskId: String, operationId: String, reason: String): ControlResult =
        execute("CONFIRM_REMOTE_STOP", taskId, operationId, reason) { task ->
            val status = getControlStatus(taskId)
            if (TaskStates.isTerminal(task.state)) {
                return@execute ControlResult(
                    ok = true, confirmed = true, status = status,
                    reason = "任务已结束（${task.state}），保留结果",
                )
            }
            if (status != ControlStatus.STOPPING && status != ControlStatus.PAUSING) {
                return@execute ControlResult(
                    ok = false, confirmed = false, status = status,
                    errorCode = "control_not_stopping", reason = "任务未处于停止/暂停中",
                )
            }
            try {
                taskStore.updateTask(
                    taskId,
                    mapOf("state" to "cancelled"),
                    task.version, task.goalVersion, task.authEpoch,
                )
            } catch (_: Exception) {
                // 账本写入失败（如泵已先一步落了终态）：控制态仍按回执走，
                // 不把一次已确认的停止说成未确认。
            }
            token.confirm(taskId)
            controlStore.setDispatchFrozen(taskId, false)
            ControlResult(
                ok = true, confirmed = true, status = status,
                reason = "停止已确认：远端执行已实际停止，任务已取消",
            )
        }

    // -- Confirmation (U4 / U6): the executor (or a lease-expiry sweep)
    // calls these when the in-flight work has actually stopped. ----------

    /**
     * Confirm a PAUSING task has really stopped: only when the task is
     * no longer in flight (or its lease expired) does it become PAUSED.
     * An external execution that cannot be confirmed stays PAUSING.
     */
    fun confirmPaused(taskId: String, operationId: String, reason: String): ControlResult =
        execute("CONFIRM_PAUSE", taskId, operationId, reason) { task ->
            when (getControlStatus(taskId)) {
                ControlStatus.PAUSED -> ControlResult(
                    ok = true, confirmed = true, status = ControlStatus.PAUSED,
                    reason = "任务已处于暂停状态",
                )
                ControlStatus.PAUSING -> {
                    if (TaskStates.isTerminal(task.state) || !isTaskInFlight(task.state) || leaseExpired(task)) {
                        if (isTaskInFlight(task.state) && leaseExpired(task)) releasePausedAttempt(task)
                        controlStore.setControlStatus(taskId, ControlStatus.PAUSED)
                        controlStore.setDispatchFrozen(taskId, false)
                        ControlResult(
                            ok = true, confirmed = true, status = ControlStatus.PAUSED,
                            reason = "暂停已确认：执行已实际终止",
                        )
                    } else {
                        ControlResult(
                            ok = true, confirmed = false, status = ControlStatus.PAUSING,
                            reason = "执行方尚未确认停止，保持待确认",
                        )
                    }
                }
                else -> ControlResult(
                    ok = false, confirmed = false, status = getControlStatus(taskId),
                    errorCode = "control_not_pausing", reason = "任务未处于暂停中",
                )
            }
        }

    /**
     * Confirm a STOPPING task has really stopped and land the terminal
     * state. If the task already ended naturally, its result is kept
     * (never overwritten by the stop). After the 120s observe threshold
     * without confirmation the task STAYS stopping and keeps blocking
     * new dispatch — it is never forced into a terminal state (U4).
     */
    fun confirmStop(taskId: String, operationId: String, reason: String): ControlResult =
        execute("CONFIRM_STOP", taskId, operationId, reason) { task ->
            val status = getControlStatus(taskId)
            if (status != ControlStatus.STOPPING) {
                if (TaskStates.isTerminal(task.state)) {
                    return@execute ControlResult(
                        ok = true, confirmed = true, status = status,
                        reason = "任务已结束（${task.state}），保留结果",
                    )
                }
                return@execute ControlResult(
                    ok = false, confirmed = false, status = status,
                    errorCode = "control_not_stopping", reason = "任务未处于停止中",
                )
            }
            if (TaskStates.isTerminal(task.state)) {
                // The task ended naturally before the stop was confirmed:
                // keep the result, do not overwrite it with a cancel.
                token.confirm(taskId)
                controlStore.setDispatchFrozen(taskId, false)
                return@execute ControlResult(
                    ok = true, confirmed = true, status = ControlStatus.STOPPING,
                    reason = "任务已自然结束（${task.state}），保留结果",
                )
            }
            if (!isTaskInFlight(task.state) || leaseExpired(task)) {
                taskStore.updateTask(
                    taskId,
                    mapOf("state" to "cancelled", "lease_owner" to null, "lease_expires_at" to null),
                    task.version, task.goalVersion, task.authEpoch,
                )
                token.confirm(taskId)
                controlStore.setDispatchFrozen(taskId, false)
                ControlResult(
                    ok = true, confirmed = true, status = ControlStatus.STOPPING,
                    reason = "停止已确认：任务已取消",
                )
            } else {
                val waited = token.requestedAt(taskId)?.let { nowEpochSeconds() - it } ?: 0
                ControlResult(
                    ok = true, confirmed = false, status = ControlStatus.STOPPING,
                    reason = "执行方尚未确认停止（已等待 ${waited}s），保持待确认并阻止新派单",
                )
            }
        }

    /**
     * Finalize local and MCP pause/stop requests once an executor's lease
     * expires. Called after each lease sweep; the sweep preserves pending
     * control states so they cannot be silently requeued first.
     */
    fun reconcileExpiredControls() {
        val expired = try {
            taskStore.listTasks().filter { task ->
                canRecoverExpiredAttempt(task)
            }
        } catch (_: Exception) {
            return
        }
        for (task in expired) {
            when (getControlStatus(task.taskId)) {
                ControlStatus.PAUSING -> confirmPaused(
                    task.taskId, "lease-pause-${java.util.UUID.randomUUID()}",
                    "执行器租约过期，已安全回队等待恢复",
                )
                ControlStatus.STOPPING -> confirmStop(
                    task.taskId, "lease-stop-${java.util.UUID.randomUUID()}",
                    "执行器租约过期，已确认停止",
                )
                else -> Unit
            }
        }
    }

    /** Current counters, for callers that snapshot them (claim / issue). */
    fun currentGoalVersion(taskId: String): Long =
        maxOf(goalVersion.current(taskId), taskStore.getTask(taskId)?.goalVersion ?: 0)

    fun currentAuthEpoch(taskId: String): Long =
        maxOf(authEpoch.current(taskId), taskStore.getTask(taskId)?.authEpoch ?: 0)

    /**
     * 阶段 B：计数器以任务行为准。正常情况下任务行与 ControlStore
     * 计数器是一起涨的（都从 1 起）；任务行若领先（从别处恢复而来），
     * bump 从任务行的值起算，并把 ControlStore 计数器追平到新值，
     * 不让 ExecutionFence 读到落后的旧值。循环次数有界（落后多少
     * 补多少，正常情况 0 次——bump 本身已 +1）。
     */
    private fun bumpGoalVersion(task: TaskRecord): Long {
        val target = maxOf(goalVersion.current(task.taskId), task.goalVersion) + 1
        while (goalVersion.current(task.taskId) < target) {
            goalVersion.bump(task.taskId)
        }
        return target
    }

    private fun bumpAuthEpoch(task: TaskRecord): Long {
        val target = maxOf(authEpoch.current(task.taskId), task.authEpoch) + 1
        while (authEpoch.current(task.taskId) < target) {
            authEpoch.bump(task.taskId)
        }
        return target
    }

    // -- Internals ----------------------------------------------------------

    private fun leaseExpired(task: TaskRecord): Boolean {
        val lease = task.leaseExpiresAt ?: return true
        return lease <= nowEpochSeconds()
    }

    private fun canRecoverExpiredAttempt(task: TaskRecord): Boolean =
        task.sessionId == null && isTaskInFlight(task.state) &&
            task.leaseExpiresAt != null && leaseExpired(task) &&
            (isLocalDispatchedTask(task) || TaskOrigins.isMcpDispatchTask(task.taskId))

    private fun releasePausedAttempt(task: TaskRecord) {
        taskStore.updateTask(
            task.taskId,
            mapOf(
                "state" to "requeued",
                "assigned_agent_id" to task.assignedAgentId.takeIf { TaskOrigins.isMcpDispatchTask(task.taskId) },
                "lease_owner" to null,
                "lease_expires_at" to null,
                "member_id" to null,
                "attempt_id" to null,
                "needs_attention" to true,
            ),
            task.version, task.goalVersion, task.authEpoch,
        )
    }

    private fun isLocalDispatchedTask(task: TaskRecord): Boolean =
        task.sessionId == null &&
            task.assignedAgentId.equals(LocalTaskRunner.LOCAL_AGENT_ID, ignoreCase = true)

    /**
     * One command, one transaction: dedup check, state change, counter
     * bump and audit row commit or roll back together. A repeated
     * operationId returns the stored result without re-executing (U2).
     * Control commands are never queued behind the ordinary dispatch
     * queue — they run even while dispatch is frozen.
     */
    private fun execute(
        action: String,
        taskId: String,
        operationId: String,
        reason: String,
        work: (TaskRecord) -> ControlResult,
    ): ControlResult {
        val result: ControlResult = try {
            taskStore.withTaskLock {
                controlStore.runTransaction {
                val replay = controlStore.getOperation(operationId)
                if (replay != null) return@runTransaction replay.result
                val task = taskStore.getTask(taskId)
                if (task == null) {
                    val notFound = ControlResult(
                        ok = false, confirmed = false, status = getControlStatus(taskId),
                        errorCode = "control_task_not_found", reason = "任务不存在：$taskId",
                    )
                    recordAudit(action, taskId, operationId, notFound, reason)
                    recordOperation(operationId, taskId, action, notFound)
                    return@runTransaction notFound
                }
                val outcome = work(task)
                recordAudit(action, taskId, operationId, outcome, reason)
                recordOperation(operationId, taskId, action, outcome)
                outcome
                }
            }
        } catch (e: Exception) {
            ControlResult(
                ok = false, confirmed = false, status = getControlStatus(taskId),
                errorCode = "control_failed", reason = "控制命令执行失败：${e.message}",
            )
        }
        return result
    }

    private fun recordAudit(
        action: String,
        taskId: String,
        operationId: String,
        result: ControlResult,
        callerReason: String,
    ) {
        // The audit row keeps the caller's reason together with the
        // outcome, so a later reader sees why the command was issued.
        val auditReason = if (callerReason.isBlank()) result.reason
        else if (result.reason.isNullOrBlank()) callerReason
        else "${result.reason}（原因：$callerReason）"
        auditLog.record(
            ControlAuditEntry(
                taskId = taskId,
                actor = actor,
                action = action,
                goalVersion = controlStore.goalVersion(taskId),
                epoch = controlStore.authEpoch(taskId),
                operationId = operationId,
                ok = result.ok,
                errorCode = result.errorCode,
                reason = auditReason,
                ts = nowEpochSeconds(),
            ),
        )
    }

    private fun recordOperation(operationId: String, taskId: String, action: String, result: ControlResult) {
        controlStore.recordOperation(operationId, taskId, action, result)
    }
}
