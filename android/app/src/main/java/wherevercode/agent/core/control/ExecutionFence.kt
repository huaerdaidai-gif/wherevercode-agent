package wherevercode.agent.core.control

import wherevercode.agent.core.security.AuthorizationStore
import wherevercode.agent.core.security.CredentialCheck
import wherevercode.agent.core.security.CredentialExpectation
import wherevercode.agent.core.security.InMemoryRevocationStore
import wherevercode.agent.core.security.RevocationStore
import wherevercode.agent.core.security.TaskCredentialIssuer
import wherevercode.agent.core.security.parseTaskCredential
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStore

/**
 * ExecutionFence (C6): the ONE gate for execution writes.
 *
 * A write (write / run) is allowed only when ALL of these hold:
 * grant exists AND credential valid AND epoch current AND goal-version
 * current AND control status allows. Any failure returns a DISTINCT
 * error code and a Chinese actionable reason; the fence NEVER silently
 * falls back (no local execution on refusal).
 *
 * `readOnly` is DERIVED from the authoritative task / authorization
 * records only (U1): a caller-supplied readOnly flag
 * ([FenceRequest.callerReadOnly]) is accepted in the request and then
 * ignored — it can neither grant nor revoke anything. Reads
 * (list / read) are always allowed through the fence; the derived
 * readOnly flag on the result tells the caller the task is currently
 * read-only (paused / stopping / terminal).
 *
 * Binding gap, stated honestly (M1 §1.2): the v2 credential binds
 * user / attempt / source, but the current hand envelope carries none
 * of the three. When the caller does not supply an expectation for one
 * of them, that single binding check is skipped (the credential's own
 * claim is used as the expectation); every other check still runs.
 * The LAN increment must carry user/attempt/source in the envelope and
 * then pass them here — until then a supplied-but-wrong value is still
 * refused, an absent value is not invented.
 */
data class FenceRequest(
    val taskId: String,
    val agentId: String,
    val folder: String,
    /** "list" | "read" | "write" | "run". */
    val action: String,
    val credentialWire: String? = null,
    val targetDeviceId: String? = null,
    val userId: String? = null,
    val attempt: String? = null,
    val sourceDeviceId: String? = null,
    /** Caller-reported read-only flag. IGNORED by design (U1). */
    val callerReadOnly: Boolean = false,
)

data class FenceResult(
    val allowed: Boolean,
    val errorCode: String? = null,
    val messageZh: String? = null,
    /** Derived from authoritative records only (U1). */
    val readOnly: Boolean = false,
) {
    companion object {
        fun allow(readOnly: Boolean = false): FenceResult = FenceResult(true, readOnly = readOnly)

        fun deny(errorCode: String, messageZh: String, readOnly: Boolean = false): FenceResult =
            FenceResult(false, errorCode, messageZh, readOnly)
    }
}

object FenceErrors {
    const val NO_GRANT: String = "fence_no_grant"
    const val CREDENTIAL_MISSING: String = "fence_credential_missing"
    const val CREDENTIAL_INVALID: String = "fence_credential_invalid"
    const val CREDENTIAL_EXPIRED: String = "fence_credential_expired"
    const val CREDENTIAL_REVOKED: String = "fence_credential_revoked"
    const val CREDENTIAL_BINDING: String = "fence_credential_binding"
    const val EPOCH_STALE: String = "fence_epoch_stale"
    const val GOAL_VERSION_STALE: String = "fence_goal_version_stale"
    const val TASK_NOT_FOUND: String = "fence_task_not_found"
    const val TASK_NOT_RUNNING: String = "fence_task_not_running"
    const val PAUSED: String = "fence_paused"
    const val PAUSING: String = "fence_pausing"
    const val STOPPING: String = "fence_stopping"
}

open class ExecutionFence(
    protected val taskStore: TaskStore?,
    protected val authStore: AuthorizationStore,
    protected val revocationStore: RevocationStore,
    protected val controlStore: ControlStore? = null,
    protected val signingKey: ByteArray? = null,
    protected val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    open fun check(request: FenceRequest): FenceResult {
        // Reads always pass the fence; the derived readOnly flag still
        // tells the caller whether the task is currently read-only.
        if (request.action == "list" || request.action == "read") {
            return FenceResult.allow(readOnly = derivedReadOnly(request.taskId))
        }

        val task = taskStore?.getTask(request.taskId)
        if (taskStore != null && task == null) {
            return FenceResult.deny(
                FenceErrors.TASK_NOT_FOUND,
                "任务不存在：${request.taskId}，请确认任务 ID 后重试",
            )
        }
        // 1. grant must exist.
        if (!authStore.isAuthorized(request.agentId, request.folder)) {
            return FenceResult.deny(
                FenceErrors.NO_GRANT,
                "没有授权：请先在项目设备上授权该 Agent 使用此文件夹，再重新下达任务",
            )
        }
        // 2. credential must be present, parseable (v2) and valid.
        val wire = request.credentialWire
        if (wire.isNullOrBlank()) {
            return FenceResult.deny(
                FenceErrors.CREDENTIAL_MISSING,
                "缺少任务凭证：请重新发起任务以获取凭证",
            )
        }
        val credential = parseTaskCredential(wire)
            ?: return FenceResult.deny(
                FenceErrors.CREDENTIAL_INVALID,
                "任务凭证无效：凭证格式不正确或版本不受支持，请重新发起任务",
            )
        val key = signingKey
            ?: return FenceResult.deny(
                FenceErrors.CREDENTIAL_INVALID,
                "任务凭证无法校验：本设备未配置凭证签名密钥，请联系管理员",
            )
        val currentEpoch = controlStore?.authEpoch(request.taskId) ?: task?.authEpoch ?: 1
        val currentGoalVersion = controlStore?.goalVersion(request.taskId) ?: task?.goalVersion ?: 1
        val expectation = CredentialExpectation(
            // Missing expectations fall back to the credential's own claim
            // for that field (partial binding; see the file doc comment).
            userId = request.userId ?: credential.userId,
            taskId = request.taskId,
            attempt = request.attempt ?: credential.attempt,
            sourceDeviceId = request.sourceDeviceId ?: credential.sourceDeviceId,
            targetDeviceId = request.targetDeviceId,
            agentId = request.agentId,
            folder = request.folder,
            epoch = currentEpoch,
            goalVersion = currentGoalVersion,
        )
        when (
            TaskCredentialIssuer.verifyV2(
                credential = credential,
                expected = expectation,
                signingKey = key,
                revocation = revocationStore,
                authStore = authStore,
                nowEpochSeconds = nowEpochSeconds(),
            )
        ) {
            CredentialCheck.OK -> Unit
            CredentialCheck.INVALID -> return FenceResult.deny(
                FenceErrors.CREDENTIAL_INVALID,
                "任务凭证无效：签名不匹配，请重新发起任务",
            )
            CredentialCheck.EXPIRED -> return FenceResult.deny(
                FenceErrors.CREDENTIAL_EXPIRED,
                "任务凭证已过期：请重新发起任务后重试",
            )
            CredentialCheck.REVOKED,
            CredentialCheck.TASK_REVOKED,
            CredentialCheck.HOST_REVOKED,
            -> return FenceResult.deny(
                FenceErrors.CREDENTIAL_REVOKED,
                "任务凭证已被收回：任务已结束或授权已被收回，请重新授权后下达任务",
            )
            CredentialCheck.WRONG_TASK -> return FenceResult.deny(
                FenceErrors.CREDENTIAL_BINDING,
                "凭证与任务不匹配：该凭证不属于当前任务",
            )
            CredentialCheck.WRONG_TARGET -> return FenceResult.deny(
                FenceErrors.CREDENTIAL_BINDING,
                "凭证与目标设备不匹配：该凭证不属于当前项目设备",
            )
            CredentialCheck.WRONG_FOLDER -> return FenceResult.deny(
                FenceErrors.CREDENTIAL_BINDING,
                "凭证与文件夹不匹配：该凭证不属于当前项目文件夹",
            )
            CredentialCheck.WRONG_USER,
            CredentialCheck.WRONG_ATTEMPT,
            CredentialCheck.WRONG_SOURCE,
            CredentialCheck.WRONG_AGENT,
            CredentialCheck.WRONG_SCOPE,
            -> return FenceResult.deny(
                FenceErrors.CREDENTIAL_BINDING,
                "凭证绑定不匹配：凭证与当前调用的身份信息不符，请重新发起任务",
            )
            CredentialCheck.GRANT_MISSING -> return FenceResult.deny(
                FenceErrors.NO_GRANT,
                "授权已不存在：签发凭证时依据的授权已被收回，请重新授权",
            )
            CredentialCheck.STALE_EPOCH -> return FenceResult.deny(
                FenceErrors.EPOCH_STALE,
                "凭证已过期（授权已变更）：授权版本已更新，请重新发起任务",
            )
            CredentialCheck.STALE_GOAL_VERSION -> return FenceResult.deny(
                FenceErrors.GOAL_VERSION_STALE,
                "凭证已过期（目标已变更）：任务目标已更新，请重新发起任务",
            )
        }
        // 3. control status must allow writes.
        val status = controlStore?.getControlStatus(request.taskId) ?: ControlStatus.RUNNING
        if (status != ControlStatus.RUNNING) {
            return when (status) {
                ControlStatus.PAUSED -> FenceResult.deny(
                    FenceErrors.PAUSED,
                    "任务已暂停：写操作被拒绝，请先恢复任务",
                    readOnly = true,
                )
                ControlStatus.PAUSING -> FenceResult.deny(
                    FenceErrors.PAUSING,
                    "任务正在暂停：写操作被拒绝，请等待暂停确认",
                    readOnly = true,
                )
                ControlStatus.STOPPING -> FenceResult.deny(
                    FenceErrors.STOPPING,
                    "任务正在停止：写操作被拒绝",
                    readOnly = true,
                )
                ControlStatus.RUNNING -> error("unreachable")
            }
        }
        // 4. task must not be terminal.
        if (task != null && TaskStates.isTerminal(task.state)) {
            return FenceResult.deny(
                FenceErrors.TASK_NOT_RUNNING,
                "任务已结束（${task.state}）：写操作被拒绝",
                readOnly = true,
            )
        }
        return FenceResult.allow()
    }

    /** Read-only is derived from authoritative records only (U1). */
    protected fun derivedReadOnly(taskId: String): Boolean {
        val status = controlStore?.getControlStatus(taskId) ?: ControlStatus.RUNNING
        if (status != ControlStatus.RUNNING) return true
        val task = taskStore?.getTask(taskId) ?: return false
        return TaskStates.isTerminal(task.state)
    }
}

/**
 * Local-only fence for the stdio HandServer (M6, P1-3 closure).
 * Local callers are on the device, so NO TaskCredential is required —
 * but grant + control status + task state are still checked, and the
 * mode is explicit: this fence must never be used for a LAN call.
 * The caller-supplied readOnly flag is ignored here too (U1).
 */
class LocalExecutionFence(
    taskStore: TaskStore? = null,
    authStore: AuthorizationStore,
    controlStore: ControlStore? = null,
    nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ExecutionFence(
    taskStore = taskStore,
    authStore = authStore,
    revocationStore = InMemoryRevocationStore(),
    controlStore = controlStore,
    signingKey = null,
    nowEpochSeconds = nowEpochSeconds,
) {
    override fun check(request: FenceRequest): FenceResult {
        // 本地模式：不要求任务凭证；其余检查与 ExecutionFence 相同。
        if (request.action == "list" || request.action == "read") {
            return FenceResult.allow(readOnly = derivedReadOnly(request.taskId))
        }
        val task = taskStore?.getTask(request.taskId)
        if (taskStore != null && task == null) {
            return FenceResult.deny(
                FenceErrors.TASK_NOT_FOUND,
                "任务不存在：${request.taskId}，请确认任务 ID 后重试",
            )
        }
        if (!authStore.isAuthorized(request.agentId, request.folder)) {
            return FenceResult.deny(
                FenceErrors.NO_GRANT,
                "没有授权：请先在项目设备上授权该 Agent 使用此文件夹，再重新下达任务",
            )
        }
        val status = controlStore?.getControlStatus(request.taskId) ?: ControlStatus.RUNNING
        if (status != ControlStatus.RUNNING) {
            return when (status) {
                ControlStatus.PAUSED -> FenceResult.deny(
                    FenceErrors.PAUSED,
                    "任务已暂停：写操作被拒绝，请先恢复任务",
                    readOnly = true,
                )
                ControlStatus.PAUSING -> FenceResult.deny(
                    FenceErrors.PAUSING,
                    "任务正在暂停：写操作被拒绝，请等待暂停确认",
                    readOnly = true,
                )
                ControlStatus.STOPPING -> FenceResult.deny(
                    FenceErrors.STOPPING,
                    "任务正在停止：写操作被拒绝",
                    readOnly = true,
                )
                ControlStatus.RUNNING -> error("unreachable")
            }
        }
        if (task != null && TaskStates.isTerminal(task.state)) {
            return FenceResult.deny(
                FenceErrors.TASK_NOT_RUNNING,
                "任务已结束（${task.state}）：写操作被拒绝",
                readOnly = true,
            )
        }
        return FenceResult.allow()
    }
}
