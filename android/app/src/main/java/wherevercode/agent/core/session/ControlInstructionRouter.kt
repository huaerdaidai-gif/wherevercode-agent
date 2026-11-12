package wherevercode.agent.core.session

import wherevercode.agent.core.control.ControlAction
import wherevercode.agent.core.control.ControlCommandPort
import wherevercode.agent.core.control.ControlResult
import wherevercode.agent.core.security.AuditLog
import wherevercode.agent.core.security.AuditEntry
import wherevercode.agent.core.security.AuthorizationStore
import wherevercode.agent.core.security.utcNowIso
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore

/**
 * ControlInstructionRouter (M2 S4): the ONLY exit for control
 * instructions found in private or group chats.
 *
 * Order of work (plan §1.1 S4 / §1.3):
 *  1. The instruction must arrive as a message the SessionLedger
 *     already persisted — a direct call with an unrecorded messageId
 *     is refused, so there is no path that writes the task ledger
 *     without going through S3.
 *  2. Deterministic re-parse of the command (shape only) + reason
 *     required. Person swap (换人) is explicitly refused (U10).
 *  3. Sender identity + authorization: the session owner may always
 *     control; anyone else only when the delegation switch
 *     [delegatedControlEnabled] is on AND the device grant is present
 *     (the switch defaults to off — proxy control is refused by
 *     default). The AuthorizationStore is read-only here.
 *  4. Record the INTENT in the task ledger via
 *     TaskStore.updateTask with the new 5-arg signature, passing the
 *     current row's version / goalVersion / authEpoch as the expected
 *     values. The write only sets needs_attention: the task STATE
 *     itself never moves here — this router never executes pause /
 *     stop, it only records and hands over. A stale version propagates
 *     as StaleTaskUpdate / ControlVersionStale and nothing is written.
 *  5. Hand over to the ControlPlane through [ControlCommandPort]
 *     (the six action methods only). A failed / unconfirmed handover
 *     still leaves the recorded intent in place.
 *
 * "Intent recorded" means, together: the task row's version bumped by
 * one with needs_attention set, and the CONTROL session row (verb +
 * reason) exists in the ledger. The router itself writes no session
 * rows and never touches a model adapter.
 */
data class RouteResult(
    val ok: Boolean,
    /** ControlPlane's answer (null when the handover never ran). */
    val controlResult: ControlResult? = null,
    /** Task row after the intent write (null when nothing was written). */
    val task: TaskRecord? = null,
    val errorCode: String? = null,
    val reason: String? = null,
    /** Direct addressing is preserved: sender -> recipient, no brain. */
    val recipientId: String? = null,
)

class ControlInstructionRouter(
    private val taskStore: TaskStore,
    /** Ledger receipt check: the message must already exist here. */
    private val messageDao: SessionMessageDao,
    private val controlPort: ControlCommandPort,
    private val authStore: AuthorizationStore,
    private val auditLog: AuditLog? = null,
    /**
     * Supervisor-delegation switch (总管代处理开关). Default OFF: only
     * the session owner may issue control; a non-owner member needs
     * this switch on AND the device grant.
     */
    private val delegatedControlEnabled: Boolean = false,
) {
    /**
     * Route one already-persisted CONTROL message. Never throws for a
     * bad instruction, a refused sender, a missing task, or a failed
     * handover — those come back as [RouteResult.ok] = false. The two
     * exceptions that DO propagate are the optimistic-lock failures
     * from updateTask (StaleTaskUpdate / ControlVersionStale: nothing
     * was written) and the missing-ledger-receipt IllegalStateException
     * (a direct write attempt bypassing S3).
     */
    fun route(message: SessionMessage, session: SessionRecord): RouteResult {
        // 1. Ledger receipt: no S3 row, no route.
        if (messageDao.getById(message.messageId) == null) {
            throw IllegalStateException(
                "control instruction must pass through SessionLedger " +
                    "before routing: message ${message.messageId} is not recorded",
            )
        }
        // 2. Deterministic re-parse (shape only).
        val parsed = parseControlCommand(message.content)
        if (parsed is CommandParse.Rejected) {
            val result = RouteResult(
                ok = false,
                errorCode = parsed.errorCode,
                reason = parsed.reason,
                recipientId = message.recipientId,
            )
            audit(message, result)
            return result
        }
        if (parsed is CommandParse.NotACommand) {
            val noReasonVerb = controlVerbWithoutReason(message.content)
            val result = if (noReasonVerb != null) {
                RouteResult(
                    ok = false,
                    errorCode = "control_reason_required",
                    reason = "控制指令必须写明原因",
                    recipientId = message.recipientId,
                )
            } else {
                RouteResult(
                    ok = false,
                    errorCode = "not_a_control_instruction",
                    reason = "不是一条控制指令",
                    recipientId = message.recipientId,
                )
            }
            audit(message, result)
            return result
        }
        val instruction = (parsed as CommandParse.Command).instruction
        if (instruction.reason.isBlank()) {
            val result = RouteResult(
                ok = false,
                errorCode = "control_reason_required",
                reason = "控制指令必须写明原因",
                recipientId = message.recipientId,
            )
            audit(message, result)
            return result
        }

        // 3. Sender identity + authorization.
        val isOwner = message.senderId == session.ownerId
        val delegatedOk = delegatedControlEnabled && authStore.deviceAuthorized()
        if (!isOwner && !delegatedOk) {
            val result = RouteResult(
                ok = false,
                errorCode = "sender_not_authorized",
                reason = "发送者无权发出控制指令",
                recipientId = message.recipientId,
            )
            audit(message, result)
            return result
        }

        // 4. Load the task, then record the intent with the current
        // counters as the expected versions.
        val task = taskStore.getTask(instruction.taskId)
            ?: run {
                val result = RouteResult(
                    ok = false,
                    errorCode = "task_not_found",
                    reason = "任务不存在：${instruction.taskId}",
                    recipientId = message.recipientId,
                )
                audit(message, result)
                return result
            }
        val updated = taskStore.updateTask(
            taskId = task.taskId,
            changes = mapOf("needs_attention" to true),
            expectedVersion = task.version,
            expectedGoalVersion = task.goalVersion,
            expectedAuthEpoch = task.authEpoch,
        ) ?: run {
            val result = RouteResult(
                ok = false,
                errorCode = "task_not_found",
                reason = "任务不存在：${instruction.taskId}",
                recipientId = message.recipientId,
            )
            audit(message, result)
            return result
        }

        // 5. Hand over to ControlPlane. Only record + hand over: the
        // task state itself is untouched here.
        val operationId = message.operationId
            ?: message.idempotencyKey
            ?: message.messageId
        val controlResult = try {
            when (instruction.verb) {
                ControlVerb.PAUSE -> controlPort.pause(task.taskId, operationId, instruction.reason)
                ControlVerb.RESUME -> controlPort.resume(task.taskId, operationId, instruction.reason)
                ControlVerb.STOP -> controlPort.stop(task.taskId, operationId, instruction.reason)
                ControlVerb.CHANGE_GOAL -> controlPort.retarget(
                    task.taskId,
                    instruction.reason,
                    operationId,
                    instruction.reason,
                )
                ControlVerb.REVOKE -> controlPort.revoke(task.taskId, operationId, instruction.reason)
                ControlVerb.TAKEOVER -> controlPort.takeover(task.taskId, operationId, instruction.reason)
            }
        } catch (e: Exception) {
            val result = RouteResult(
                ok = false,
                errorCode = "control_failed",
                reason = "控制交接失败：${e.message}",
                task = updated,
                recipientId = message.recipientId,
            )
            audit(message, result)
            return result
        }
        val result = RouteResult(
            ok = controlResult.ok,
            controlResult = controlResult,
            task = updated,
            errorCode = controlResult.errorCode,
            reason = controlResult.reason,
            recipientId = message.recipientId,
        )
        audit(message, result)
        return result
    }

    /** ControlAction is the port's vocabulary; ControlVerb is the
     * session layer's. Kept in sync by construction (six verbs). */
    @Suppress("unused")
    fun verbToAction(verb: ControlVerb): ControlAction =
        when (verb) {
            ControlVerb.PAUSE -> ControlAction.PAUSE
            ControlVerb.RESUME -> ControlAction.RESUME
            ControlVerb.STOP -> ControlAction.STOP
            ControlVerb.CHANGE_GOAL -> ControlAction.CHANGE_GOAL
            ControlVerb.REVOKE -> ControlAction.REVOKE
            ControlVerb.TAKEOVER -> ControlAction.TAKEOVER
        }

    private fun audit(message: SessionMessage, result: RouteResult) {
        val log = auditLog ?: return
        log.record(
            AuditEntry(
                ts = utcNowIso(),
                taskId = message.taskId ?: "",
                agentId = message.senderId,
                action = "control_route",
                ok = result.ok,
                errorCode = result.errorCode,
                // Metadata only: message id, never the body.
                result = mapOf("message_id" to message.messageId),
            ),
        )
    }
}
