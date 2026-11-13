package wherevercode.agent.core.dispatch

import java.time.Instant
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.core.control.ControlPlane
import wherevercode.agent.core.control.ControlResult
import wherevercode.agent.core.control.ControlStatus
import wherevercode.agent.core.control.ControlStore
import wherevercode.agent.core.security.AuditEntry
import wherevercode.agent.core.security.AuditLog
import wherevercode.agent.core.security.RevocationStore
import wherevercode.agent.core.security.SecretRedactor
import wherevercode.agent.core.task.ControlVersionStale
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.StaleTaskUpdate
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStore
import wherevercode.agent.core.task.TaskOrigins

/**
 * 认领口：生产用 [SchedulerClaimPort]（复用 App 的 [TaskScheduler]
 * 成员认领：每成员一次一单、冻结/非 RUNNING 不可认领、attempt+1、
 * 600 秒租约），不另写一套认领规则。
 */
interface DispatchClaimPort {
    fun claim(taskId: String, agentId: String, attemptId: String, nowEpochSeconds: Long): TaskRecord?
    fun start(taskId: String, attemptId: String): TaskRecord?
}

class SchedulerClaimPort(private val scheduler: TaskScheduler) : DispatchClaimPort {
    override fun claim(taskId: String, agentId: String, attemptId: String, nowEpochSeconds: Long): TaskRecord? =
        scheduler.claimMcpTaskForMember(taskId, agentId, attemptId, nowEpochSeconds)

    override fun start(taskId: String, attemptId: String): TaskRecord? = scheduler.startTask(taskId, attemptId)
}

/**
 * MCP 派工服务核心（纯 JVM 逻辑，不依赖 Android / 网络）。
 *
 * - 任务：只经 [TaskStore] 建/改（与 App 其他入口同一张表、同一状态机）。
 * - 控制：暂停/恢复/停止/改目标一律经 App 的 [ControlPlane]
 *   （同一 operationId 去重、待确认语义、goalVersion 计数器）。
 * - 权限：角色能力 + 任务级范围（派工方只控自己建的任务，执行方
 *   只动指派给自己的任务）。
 * - 幂等：同一主体 + 工具 + idempotency_key 只执行一次；键复用但参数
 *   不同返回 idempotency_conflict。只记成功结果（失败可用同键重试）。
 * - 停止围栏：停止请求后（STOPPING / cancel pending / cancelled）、
 *   授权撤销后、重新认领后（旧 attempt_id），执行方的进度/结果一律拒绝。
 * - 审计：每次调用一行（主体、工具、任务、成败、错误码），参数与
 *   正文不进审计。
 *
 * 线程：所有调用在一把服务锁里串行执行——RoomTaskStore 的锁与
 * ControlPlane 事务的取锁顺序相反（见文档“交叉问题”），串行化避免
 * 本模块自己触发这个死锁。
 */
class McpDispatchService(
    private val taskStore: TaskStore,
    @Volatile private var controlPlane: ControlPlane,
    private val controlStore: ControlStore,
    private val revocationStore: RevocationStore,
    private val claimPort: DispatchClaimPort,
    val tokens: DispatchTokenRegistry,
    private val state: DispatchStateStore,
    private val audit: AuditLog,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) {
    private val lock = Any()

    fun configureRemoteControl(
        targetResolver: ((String?) -> wherevercode.agent.core.mcp.RemoteTarget?)?,
        controlSender: wherevercode.agent.core.mcp.RemoteControlSender?,
    ) = controlPlane.configureRemoteControl(targetResolver, controlSender)

    fun useControlPlane(sharedControlPlane: ControlPlane) {
        synchronized(lock) { controlPlane = sharedControlPlane }
    }

    fun toolsFor(principal: DispatchPrincipal): List<DispatchToolDef> = DispatchTools.visibleTo(principal)

    /** 记一条非工具调用的拒绝（鉴权失败、未知工具等）。不含令牌。 */
    fun auditRejected(actor: String, action: String, errorCode: String, taskId: String = "") {
        writeAudit(actor, action, taskId, ok = false, errorCode = errorCode)
    }

    fun auditEntries(): List<AuditEntry> = audit.readEntries()

    /**
     * 执行一次工具调用。成功返回结果对象；失败抛 [DispatchException]
     * （错误码稳定、消息不含机密）。未知工具抛 [UnknownToolException]。
     */
    fun call(principal: DispatchPrincipal, tool: String, args: JSONObject): JSONObject {
        val def = DispatchTools.BY_NAME[tool]
        if (def == null) {
            auditRejected(principal.auditId, "mcp_dispatch.$tool", "unknown_tool")
            throw UnknownToolException(tool)
        }
        synchronized(lock) {
            var taskId = args.optString("task_id", "")
            try {
                val visible = DispatchTools.visibleTo(principal).any { it.name == tool }
                if (!visible) throw DispatchException(DispatchErrors.FORBIDDEN, "当前身份无权调用 $tool")
                val result = withIdempotency(principal, def, args) {
                    // Claim acquires the scheduler lock before the task lock.
                    // Worker writes only need the task lock, shared with ControlPlane.
                    when (tool) {
                        DispatchTools.REPORT_PROGRESS, DispatchTools.SUBMIT_RESULT ->
                            taskStore.withTaskLock { dispatch(principal, tool, args) }
                        else -> dispatch(principal, tool, args)
                    }
                }
                result.optJSONObject("task")?.optString("task_id")?.takeIf { it.isNotEmpty() }?.let { taskId = it }
                writeAudit(principal.auditId, "mcp_dispatch.$tool", taskId, ok = true, errorCode = null,
                    replayed = result.optBoolean("replayed", false))
                return result
            } catch (e: DispatchException) {
                writeAudit(principal.auditId, "mcp_dispatch.$tool", taskId, ok = false, errorCode = e.code)
                throw e
            } catch (e: StaleTaskUpdate) {
                writeAudit(principal.auditId, "mcp_dispatch.$tool", taskId, ok = false, errorCode = DispatchErrors.VERSION_CONFLICT)
                throw DispatchException(DispatchErrors.VERSION_CONFLICT, "任务已被其他写入更新，请重新读取后重试")
            } catch (e: ControlVersionStale) {
                writeAudit(principal.auditId, "mcp_dispatch.$tool", taskId, ok = false, errorCode = DispatchErrors.VERSION_CONFLICT)
                throw DispatchException(DispatchErrors.VERSION_CONFLICT, "任务控制版本已变化，请重新读取后重试")
            } catch (e: TaskStates.InvalidStateTransition) {
                writeAudit(principal.auditId, "mcp_dispatch.$tool", taskId, ok = false, errorCode = "invalid_transition")
                throw DispatchException("invalid_transition", "任务当前状态不允许该操作（${e.fromState} → ${e.toState}）")
            } catch (e: Exception) {
                writeAudit(principal.auditId, "mcp_dispatch.$tool", taskId, ok = false, errorCode = DispatchErrors.INTERNAL)
                throw DispatchException(DispatchErrors.INTERNAL, "内部错误：" + SecretRedactor.redact(e.message ?: e.javaClass.simpleName, 200))
            }
        }
    }

    // -- idempotency ---------------------------------------------------------

    private fun withIdempotency(
        principal: DispatchPrincipal,
        def: DispatchToolDef,
        args: JSONObject,
        work: () -> JSONObject,
    ): JSONObject {
        val key = args.optString("idempotency_key", "")
        if (key.isEmpty()) {
            if (def.requiresIdempotencyKey) {
                throw DispatchException(DispatchErrors.INVALID_ARGUMENT, "${def.name} 必须带 idempotency_key")
            }
            return work()
        }
        if (!def.mutating) return work()
        if (!IDEM_KEY_RE.matches(key)) {
            throw DispatchException(DispatchErrors.INVALID_ARGUMENT, "idempotency_key 须为 8–128 位 [A-Za-z0-9._:-]")
        }
        val scope = "${principal.auditId}|${def.name}|$key"
        val fingerprint = DispatchTokenRegistry.sha256Hex(canonicalJson(args, skip = setOf("idempotency_key")))
        val existing = state.idempotency(scope)
        if (existing != null) {
            if (existing.fingerprint != fingerprint) {
                throw DispatchException(DispatchErrors.IDEMPOTENCY_CONFLICT, "同一 idempotency_key 已用于不同参数的请求")
            }
            val response = JSONObject(existing.responseJson)
            val savedProject = response.optJSONObject("task")?.let { optText(it, "project_id", 128) }
            if (!principal.canAccessProject(savedProject)) throw forbiddenTask()
            if (def.name in CONTROL_TOOLS) return work().put("replayed", true)
            if (def.name == DispatchTools.CLAIM_TASK || def.name == DispatchTools.REPORT_PROGRESS ||
                def.name == DispatchTools.SUBMIT_RESULT
            ) {
                val task = requireTask(args)
                requireExecutorScope(principal, task)
                if (def.name == DispatchTools.CLAIM_TASK) {
                    executorFence(task)
                    requireAttempt(response, task)
                    return claimResponse(principal, task, resumed = true).put("replayed", true)
                }
                val recorded = response.getJSONObject("task")
                if (recorded.optInt("attempt") != task.attempt) {
                    throw DispatchException(DispatchErrors.STALE_ATTEMPT, "执行轮次已变化")
                }
                if (def.name == DispatchTools.REPORT_PROGRESS && !response.has("acknowledged")) {
                    executorFence(task)
                    requireAttempt(args, task)
                    checkGoalVersion(args, task, required = false)
                    return response.put("task", snapshot(task, principal))
                        .put("lease_expires_at", task.leaseExpiresAt ?: JSONObject.NULL)
                        .put("replayed", true)
                }
            }
            return response.put("replayed", true)
        }
        val result = work()
        state.putIdempotency(IdempotencyRecord(scope, fingerprint, result.toString(), nowEpochSeconds()))
        return result
    }

    // -- routing -------------------------------------------------------------

    private fun dispatch(principal: DispatchPrincipal, tool: String, args: JSONObject): JSONObject = when (tool) {
        DispatchTools.CREATE_TASK -> createTask(principal, args)
        DispatchTools.ASSIGN_TASK -> assignTask(principal, args)
        DispatchTools.GET_TASK_STATUS -> getTaskStatus(principal, args)
        DispatchTools.PAUSE_TASK -> control(principal, args, "pause")
        DispatchTools.RESUME_TASK -> control(principal, args, "resume")
        DispatchTools.STOP_TASK -> control(principal, args, "stop")
        DispatchTools.CHANGE_GOAL -> changeGoal(principal, args)
        DispatchTools.LIST_MY_TASKS -> listMyTasks(principal)
        DispatchTools.CLAIM_TASK -> claimTask(principal, args)
        DispatchTools.REPORT_PROGRESS -> reportProgress(principal, args)
        DispatchTools.SUBMIT_RESULT -> submitResult(principal, args)
        else -> throw UnknownToolException(tool)
    }

    // -- dispatcher tools ----------------------------------------------------

    private fun createTask(principal: DispatchPrincipal, args: JSONObject): JSONObject {
        val title = requireText(args, "title", 200)
        val goal = requireText(args, "goal", 8000)
        val projectId = optText(args, "project_id", 128)
        if (!principal.canAccessProject(projectId)) throw forbiddenTask()
        val priority = if (args.has("priority")) args.optInt("priority", 0).coerceIn(-100, 100) else 0
        val assignee = optText(args, "assignee", 64)?.let { resolveExecutor(it, projectId) }
        val taskId = TaskOrigins.MCP_DISPATCH_PREFIX + newId().take(24)
        val created = taskStore.createTask(
            TaskRecord(
                taskId = taskId,
                projectId = projectId,
                title = SecretRedactor.redact(title, 200),
                description = SecretRedactor.redact(goal, 8000),
                state = "queued",
                priority = priority,
                assignedAgentId = assignee,
            ),
        )
        state.putTaskMeta(DispatchTaskMeta(taskId, principal.id, principal.role, nowEpochSeconds()))
        taskStore.recordTaskEvent(taskId, EVENT_MCP_CREATED, "经 MCP 派工创建", principal.auditId)
        return JSONObject().put("ok", true).put("task", snapshot(created, principal))
    }

    private fun assignTask(principal: DispatchPrincipal, args: JSONObject): JSONObject {
        val task = requireTask(args)
        requireDispatchScope(principal, task)
        checkExpectedVersion(args, task)
        val agentId = resolveExecutor(requireText(args, "agent_id", 64), task.projectId)
        if (TaskStates.isTerminal(task.state)) throw terminal(task)
        if (!TaskStates.isClaimable(task.state)) {
            throw DispatchException(DispatchErrors.TASK_IN_FLIGHT, "任务正在执行（${task.state}），请先停止再改派")
        }
        val updated = taskStore.updateTask(
            task.taskId, mapOf("assigned_agent_id" to agentId),
            task.version, task.goalVersion, task.authEpoch,
        ) ?: throw notFound(task.taskId)
        taskStore.recordTaskEvent(task.taskId, EVENT_MCP_ASSIGNED, "指派给 $agentId", principal.auditId)
        return JSONObject().put("ok", true).put("task", snapshot(updated, principal))
    }

    private fun getTaskStatus(principal: DispatchPrincipal, args: JSONObject): JSONObject {
        val task = requireTask(args)
        val dispatcherOk = principal.can(DispatchCapability.DISPATCH) && hasDispatchScope(principal, task)
        val executorOk = principal.can(DispatchCapability.EXECUTE) && isAssignedTo(principal, task)
        if (!dispatcherOk && !executorOk) throw forbiddenTask()
        val progress = JSONArray()
        state.progress(task.taskId).forEach { n ->
            progress.put(JSONObject().apply {
                put("ts", n.ts); put("actor", n.actor); put("message", n.message)
                put("percent", n.percent ?: JSONObject.NULL)
            })
        }
        return JSONObject().put("ok", true).put("task", snapshot(task, principal)).put("progress", progress)
    }

    private fun control(principal: DispatchPrincipal, args: JSONObject, action: String): JSONObject {
        val task = requireTask(args)
        requireDispatchScope(principal, task)
        val reason = optText(args, "reason", 500)?.let { SecretRedactor.redact(it, 500) } ?: ""
        val opId = operationId(principal, action, args)
        val result = when (action) {
            "pause" -> controlPlane.pause(task.taskId, opId, reason)
            "resume" -> controlPlane.resume(task.taskId, opId, reason)
            "stop" -> controlPlane.stop(task.taskId, opId, reason)
            else -> throw IllegalArgumentException(action)
        }
        return controlResponse(principal, task.taskId, result)
    }

    private fun changeGoal(principal: DispatchPrincipal, args: JSONObject): JSONObject {
        val task = requireTask(args)
        requireDispatchScope(principal, task)
        checkExpectedVersion(args, task)
        val goal = SecretRedactor.redact(requireText(args, "goal", 8000), 8000)
        val reason = optText(args, "reason", 500)?.let { SecretRedactor.redact(it, 500) } ?: ""
        val result = controlPlane.retarget(task.taskId, goal, operationId(principal, "change_goal", args), reason)
        return controlResponse(principal, task.taskId, result)
    }

    private fun controlResponse(principal: DispatchPrincipal, taskId: String, result: ControlResult): JSONObject {
        if (!result.ok) {
            throw DispatchException(result.errorCode ?: "control_failed", result.reason ?: "控制命令失败")
        }
        val task = taskStore.getTask(taskId) ?: throw notFound(taskId)
        return JSONObject()
            .put("ok", true)
            .put("confirmed", result.confirmed)
            .put("control_status", result.status.name)
            .put("reason", result.reason ?: "")
            .put("task", snapshot(task, principal))
    }

    // -- executor tools ------------------------------------------------------

    private fun listMyTasks(principal: DispatchPrincipal): JSONObject {
        val mine = taskStore.listTasks()
            .filter { isAssignedTo(principal, it) && !TaskStates.isTerminal(it.state) }
            .sortedWith(compareByDescending<TaskRecord> { it.priority }.thenBy { it.createdAt })
        val arr = JSONArray()
        mine.forEach { arr.put(snapshot(it, principal)) }
        return JSONObject().put("ok", true).put("tasks", arr).put("count", mine.size)
    }

    private fun claimTask(principal: DispatchPrincipal, args: JSONObject): JSONObject {
        val task = requireTask(args)
        requireExecutorScope(principal, task)
        if (revocationStore.isTaskRevoked(task.taskId)) throw revoked()
        if (TaskStates.isTerminal(task.state)) throw terminal(task)
        when (controlPlane.getControlStatus(task.taskId)) {
            ControlStatus.STOPPING -> throw stopped()
            ControlStatus.PAUSING, ControlStatus.PAUSED -> throw paused()
            ControlStatus.RUNNING -> Unit
        }
        // 断线恢复：同一执行方已认领（租约仍归我）时，返回当前 attempt，不再开新 attempt。
        if ((task.state == "assigned" || task.state == "working") &&
            task.leaseOwner.equals(principal.id, ignoreCase = true) && task.attemptId != null &&
            hasLiveLease(task)
        ) {
            val current = if (task.state == "assigned") startClaim(task.taskId, task.attemptId) else task
            return claimResponse(principal, current, resumed = true)
        }
        if (!TaskStates.isClaimable(task.state)) {
            throw DispatchException(DispatchErrors.CLAIM_REJECTED, "任务当前不可认领（${task.state}）")
        }
        val attemptId = newId()
        claimPort.claim(task.taskId, principal.id, attemptId, nowEpochSeconds())
            ?: throw DispatchException(
                DispatchErrors.CLAIM_REJECTED,
                "认领被调度器拒绝：你手上已有执行中的任务，或有任务处于冻结（暂停/停止待确认）",
            )
        val started = startClaim(task.taskId, attemptId)
        return claimResponse(principal, started, resumed = false)
    }

    private fun startClaim(taskId: String, attemptId: String): TaskRecord {
        return claimPort.start(taskId, attemptId) ?: run {
            val current = taskStore.getTask(taskId) ?: throw notFound(taskId)
            executorFence(current)
            throw DispatchException(DispatchErrors.CLAIM_REJECTED, "认领已变化，任务未启动")
        }
    }

    private fun claimResponse(principal: DispatchPrincipal, task: TaskRecord, resumed: Boolean): JSONObject =
        JSONObject()
            .put("ok", true)
            .put("resumed", resumed)
            .put("attempt_id", task.attemptId)
            .put("goal", task.description ?: "")
            .put("goal_version", task.goalVersion)
            .put("lease_expires_at", task.leaseExpiresAt ?: JSONObject.NULL)
            .put("task", snapshot(task, principal))

    private fun reportProgress(principal: DispatchPrincipal, args: JSONObject): JSONObject {
        val task = requireTask(args)
        requireExecutorScope(principal, task)
        requireAttempt(args, task)
        when (val ack = args.optString("ack", "")) {
            "" -> Unit
            "stopped" -> return ackStopped(principal, task)
            "paused" -> return ackPaused(principal, task)
            else -> throw DispatchException(DispatchErrors.INVALID_ARGUMENT, "ack 只能是 paused 或 stopped（收到 $ack）")
        }
        executorFence(task)
        checkGoalVersion(args, task, required = false)
        val message = optText(args, "message", 2000)?.let { SecretRedactor.redact(it, 2000) } ?: ""
        val percent = if (args.has("percent")) args.optInt("percent").coerceIn(0, 100) else null
        val renewed = taskStore.updateTask(
            task.taskId,
            mapOf("lease_expires_at" to (nowEpochSeconds() + RoomTaskStore.LEASE_SECONDS)),
            task.version, task.goalVersion, task.authEpoch,
        ) ?: throw notFound(task.taskId)
        if (message.isNotEmpty()) {
            taskStore.recordTaskEvent(task.taskId, EVENT_MCP_PROGRESS, message, principal.auditId)
        }
        state.appendProgress(task.taskId, ProgressNote(nowEpochSeconds(), principal.auditId, task.attemptId, message, percent))
        return JSONObject()
            .put("ok", true)
            .put("directive", "continue")
            .put("lease_expires_at", renewed.leaseExpiresAt ?: JSONObject.NULL)
            .put("task", snapshot(renewed, principal))
    }

    private fun ackStopped(principal: DispatchPrincipal, task: TaskRecord): JSONObject {
        if (task.state == "cancelled") {
            return JSONObject().put("ok", true).put("acknowledged", "stopped").put("task", snapshot(task, principal))
        }
        val status = controlPlane.getControlStatus(task.taskId)
        if (status != ControlStatus.STOPPING && !controlStore.isCancelPending(task.taskId)) {
            throw DispatchException(DispatchErrors.INVALID_ARGUMENT, "任务没有待确认的停止请求")
        }
        val result = controlPlane.confirmRemoteStopped(task.taskId, "mcp:${principal.id}:ack-stop:${task.attemptId}", "执行方确认已停止")
        if (!result.ok) throw DispatchException(result.errorCode ?: "control_failed", result.reason ?: "确认停止失败")
        val now = taskStore.getTask(task.taskId) ?: throw notFound(task.taskId)
        return JSONObject().put("ok", true).put("acknowledged", "stopped").put("task", snapshot(now, principal))
    }

    private fun ackPaused(principal: DispatchPrincipal, task: TaskRecord): JSONObject {
        when (controlPlane.getControlStatus(task.taskId)) {
            ControlStatus.PAUSED -> Unit
            ControlStatus.PAUSING -> {
                val r = controlPlane.confirmRemotePaused(task.taskId, "mcp:${principal.id}:ack-pause:${newId()}", "执行方确认已暂停")
                if (!r.ok) throw DispatchException(r.errorCode ?: "control_failed", r.reason ?: "确认暂停失败")
            }
            else -> throw DispatchException(DispatchErrors.INVALID_ARGUMENT, "任务没有待确认的暂停请求")
        }
        val now = taskStore.getTask(task.taskId) ?: throw notFound(task.taskId)
        return JSONObject().put("ok", true).put("acknowledged", "paused").put("task", snapshot(now, principal))
    }

    private fun submitResult(principal: DispatchPrincipal, args: JSONObject): JSONObject {
        val task = requireTask(args)
        requireExecutorScope(principal, task)
        requireAttempt(args, task)
        executorFence(task)
        checkGoalVersion(args, task, required = true)
        checkExpectedVersion(args, task)
        val status = args.optString("status", "completed").ifEmpty { "completed" }
        if (status != "completed" && status != "failed") {
            throw DispatchException(DispatchErrors.INVALID_ARGUMENT, "status 只能是 completed 或 failed")
        }
        val result = SecretRedactor.redact(requireText(args, "result", 20000), 20000)
        if (task.state != "working") {
            throw DispatchException("invalid_transition", "任务不在执行中（${task.state}），不能交付")
        }
        val final = if (status == "completed") {
            val review = taskStore.updateTask(
                task.taskId, mapOf("state" to "review", "result" to result),
                task.version, task.goalVersion, task.authEpoch,
            ) ?: throw notFound(task.taskId)
            taskStore.updateTask(
                task.taskId, mapOf("state" to "completed", "lease_expires_at" to null),
                review.version, review.goalVersion, review.authEpoch,
            ) ?: throw notFound(task.taskId)
        } else {
            taskStore.updateTask(
                task.taskId, mapOf("state" to "failed", "error" to result, "lease_expires_at" to null),
                task.version, task.goalVersion, task.authEpoch,
            ) ?: throw notFound(task.taskId)
        }
        return JSONObject().put("ok", true).put("task", snapshot(final, principal))
    }

    // -- fences & scope ------------------------------------------------------

    /** 停止围栏 + 暂停 + 撤销：执行方任何写入之前都过这里。 */
    private fun executorFence(task: TaskRecord) {
        if (revocationStore.isTaskRevoked(task.taskId)) throw revoked()
        if (task.state == "cancelled") throw stopped()
        if (TaskStates.isTerminal(task.state)) throw terminal(task)
        if (controlStore.isCancelPending(task.taskId)) throw stopped()
        when (controlPlane.getControlStatus(task.taskId)) {
            ControlStatus.STOPPING -> throw stopped()
            ControlStatus.PAUSING, ControlStatus.PAUSED -> throw paused()
            ControlStatus.RUNNING -> Unit
        }
    }

    private fun hasLiveLease(task: TaskRecord): Boolean =
        task.leaseExpiresAt?.let { it > nowEpochSeconds() } == true

    private fun requireAttempt(args: JSONObject, task: TaskRecord) {
        val attemptId = requireText(args, "attempt_id", 64)
        if (task.attemptId != attemptId || !hasLiveLease(task)) {
            throw DispatchException(DispatchErrors.STALE_ATTEMPT, "attempt_id 不是该任务当前的认领，写入被拒绝")
        }
    }

    private fun checkGoalVersion(args: JSONObject, task: TaskRecord, required: Boolean) {
        if (!args.has("goal_version")) {
            if (required) throw DispatchException(DispatchErrors.INVALID_ARGUMENT, "必须带 goal_version")
            return
        }
        val gv = args.optLong("goal_version", -1)
        val current = controlPlane.currentGoalVersion(task.taskId)
        if (gv != current) {
            throw DispatchException(
                DispatchErrors.GOAL_CHANGED, "任务目标已更新，请按新目标执行",
                JSONObject().put("goal_version", current).put("goal", task.description ?: ""),
            )
        }
    }

    private fun checkExpectedVersion(args: JSONObject, task: TaskRecord) {
        if (!args.has("expected_version")) return
        val expected = args.optLong("expected_version", -1)
        if (expected != task.version) {
            throw DispatchException(
                DispatchErrors.VERSION_CONFLICT, "任务版本已变化（期望 $expected，当前 ${task.version}）",
                JSONObject().put("current_version", task.version),
            )
        }
    }

    private fun hasDispatchScope(principal: DispatchPrincipal, task: TaskRecord): Boolean {
        if (!principal.canAccessProject(task.projectId)) return false
        if (principal.role == DispatchRole.OWNER) return true
        val meta = state.taskMeta(task.taskId) ?: return false
        return meta.createdBy == principal.id && meta.createdByRole == principal.role
    }

    private fun requireDispatchScope(principal: DispatchPrincipal, task: TaskRecord) {
        if (!hasDispatchScope(principal, task)) throw forbiddenTask()
    }

    private fun isAssignedTo(principal: DispatchPrincipal, task: TaskRecord): Boolean =
        TaskOrigins.isMcpDispatchTask(task.taskId) && principal.canAccessProject(task.projectId) &&
            task.assignedAgentId?.equals(principal.id, ignoreCase = true) == true

    private fun requireExecutorScope(principal: DispatchPrincipal, task: TaskRecord) {
        if (!isAssignedTo(principal, task)) throw forbiddenTask()
    }

    private fun resolveExecutor(agentId: String, projectId: String?): String {
        if (!DispatchPrincipal.isValidId(agentId)) throw DispatchException(DispatchErrors.INVALID_ARGUMENT, "agent_id 格式不对")
        return tokens.canonicalExecutorId(agentId, projectId)
            ?: throw DispatchException(DispatchErrors.UNKNOWN_AGENT, "执行方未登记或无权执行该项目任务")
    }

    private fun requireTask(args: JSONObject): TaskRecord {
        val taskId = requireText(args, "task_id", 128)
        return taskStore.getTask(taskId) ?: throw notFound(taskId)
    }

    private fun operationId(principal: DispatchPrincipal, action: String, args: JSONObject): String {
        val key = args.optString("idempotency_key", "").ifEmpty { newId() }
        return "mcp:${principal.auditId}:$action:$key"
    }

    // -- snapshot ------------------------------------------------------------

    private fun snapshot(task: TaskRecord, viewer: DispatchPrincipal): JSONObject {
        val meta = state.taskMeta(task.taskId)
        val status = controlPlane.getControlStatus(task.taskId)
        return JSONObject().apply {
            put("task_id", task.taskId)
            put("title", task.title)
            put("goal", task.description ?: "")
            put("state", task.state)
            put("assigned_agent_id", task.assignedAgentId ?: JSONObject.NULL)
            put("project_id", task.projectId ?: JSONObject.NULL)
            put("priority", task.priority)
            put("version", task.version)
            put("goal_version", controlPlane.currentGoalVersion(task.taskId))
            put("attempt", task.attempt)
            // attempt_id 是执行方写入的围栏凭据，只给被指派的执行方和 owner 看。
            if (isAssignedTo(viewer, task) || viewer.role == DispatchRole.OWNER) {
                put("attempt_id", task.attemptId ?: JSONObject.NULL)
            }
            put("lease_expires_at", task.leaseExpiresAt ?: JSONObject.NULL)
            put("control_status", status.name)
            put("stop_pending", controlStore.isCancelPending(task.taskId))
            put("dispatch_frozen", controlStore.isDispatchFrozen(task.taskId))
            put("revoked", revocationStore.isTaskRevoked(task.taskId))
            put("result", task.result ?: JSONObject.NULL)
            put("error", task.error ?: JSONObject.NULL)
            put("created_at", task.createdAt)
            put("updated_at", task.updatedAt)
            put("created_by", meta?.let { "${it.createdByRole.name.lowercase()}:${it.createdBy}" } ?: JSONObject.NULL)
        }
    }

    // -- helpers -------------------------------------------------------------

    private fun requireText(args: JSONObject, key: String, max: Int): String {
        val v = optText(args, key, max) ?: throw DispatchException(DispatchErrors.INVALID_ARGUMENT, "缺少参数 $key")
        return v
    }

    private fun optText(args: JSONObject, key: String, max: Int): String? {
        if (!args.has(key) || args.isNull(key)) return null
        val raw = args.opt(key)
        if (raw !is String) throw DispatchException(DispatchErrors.INVALID_ARGUMENT, "参数 $key 须为字符串")
        val v = raw.trim()
        if (v.isEmpty()) return null
        if (v.length > max) throw DispatchException(DispatchErrors.INVALID_ARGUMENT, "参数 $key 超过 $max 字")
        return v
    }

    private fun writeAudit(actor: String, action: String, taskId: String, ok: Boolean, errorCode: String?, replayed: Boolean = false) {
        try {
            audit.record(
                AuditEntry(
                    ts = Instant.ofEpochSecond(nowEpochSeconds()).toString(),
                    taskId = SecretRedactor.redact(taskId, 128),
                    agentId = actor,
                    action = SecretRedactor.redact(action, 64),
                    ok = ok,
                    errorCode = errorCode,
                    result = if (replayed) mapOf("changed" to false) else null,
                ),
            )
        } catch (_: Exception) {
            // 审计写失败不吞掉调用结果，但也绝不把参数写进别处。
        }
    }

    private fun notFound(taskId: String) = DispatchException(DispatchErrors.TASK_NOT_FOUND, "任务不存在：${taskId.take(128)}")
    private fun forbiddenTask() = DispatchException(DispatchErrors.FORBIDDEN, "无权操作该任务（不是你创建或指派给你的任务）")
    private fun stopped() = DispatchException(DispatchErrors.TASK_STOPPED, "任务已停止或正在停止，写入被拒绝；请停止执行并用 ack=stopped 确认",
        JSONObject().put("directive", "stop"))
    private fun paused() = DispatchException(DispatchErrors.TASK_PAUSED, "任务已暂停或正在暂停，请停下并用 ack=paused 确认",
        JSONObject().put("directive", "pause"))
    private fun revoked() = DispatchException(DispatchErrors.TASK_REVOKED, "任务授权已撤销，写入被拒绝")
    private fun terminal(task: TaskRecord) = DispatchException(DispatchErrors.TASK_TERMINAL, "任务已结束（${task.state}）")

    companion object {
        private val CONTROL_TOOLS = setOf(DispatchTools.PAUSE_TASK, DispatchTools.RESUME_TASK, DispatchTools.STOP_TASK)
        const val EVENT_MCP_CREATED = "mcp_created"
        const val EVENT_MCP_ASSIGNED = "mcp_assigned"
        const val EVENT_MCP_PROGRESS = "mcp_progress"
        private val IDEM_KEY_RE = Regex("^[A-Za-z0-9._:-]{8,128}$")

        /** 键排序的规范 JSON（用于幂等指纹）。 */
        fun canonicalJson(value: Any?, skip: Set<String> = emptySet()): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().filter { it !in skip }.sorted()
                .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalJson(value.opt(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.opt(it)) }
            is String -> JSONObject.quote(value)
            is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }
}

class UnknownToolException(val tool: String) : Exception("未知工具：${tool.take(64)}")
