package wherevercode.agent.core.dispatch

import org.json.JSONObject

/**
 * MCP 派工模块（feat/mcp-dispatch-20261113）的基础模型。
 *
 * 本模块是新项目自己的、独立于老 V5 平台服务的 MCP 服务端：派工方
 * （owner 或被授予 dispatch 能力的 Agent，如 ChatGPT）经它创建 /
 * 指派 / 暂停 / 恢复 / 停止 / 改目标；执行方（agent / device）经它
 * 领单、报进度、交结果。所有任务都落在 App 现有的 [TaskStore]
 * （Room `tasks` 表）里，控制命令一律经 App 现有的 ControlPlane，
 * 不另建任务库。
 *
 * 角色（[DispatchRole]）与能力（[DispatchCapability]）：
 * - OWNER：手机主人，拥有全部能力，可操作任何任务。
 * - AGENT：云端/本地 Agent，按令牌上的能力拿 dispatch 和/或 execute。
 * - DEVICE：执行设备（Hand 主机等），只能 execute，永远拿不到 dispatch。
 *
 * 任务级权限：派工 Agent 只能看/控自己创建的任务；执行方只能操作
 * 明确指派给自己的任务。频道消息不会变成任务——只有被授权调用方
 * 显式调 create_task 才会建任务。
 */
enum class DispatchRole { OWNER, AGENT, DEVICE }

enum class DispatchCapability { DISPATCH, EXECUTE }

data class DispatchPrincipal(
    val id: String,
    val role: DispatchRole,
    val capabilities: Set<DispatchCapability>,
    val projectIds: Set<String> = emptySet(),
) {
    fun can(capability: DispatchCapability): Boolean =
        role == DispatchRole.OWNER || capability in capabilities

    fun canAccessProject(projectId: String?): Boolean =
        role == DispatchRole.OWNER || projectId == null || projectId in projectIds

    /** 审计里的主体标识：`role:id`，不含任何令牌信息。 */
    val auditId: String get() = "${role.name.lowercase()}:$id"

    companion object {
        private val ID_RE = Regex("^[A-Za-z0-9_.-]{1,64}$")

        fun isValidId(id: String): Boolean = ID_RE.matches(id)

        /** 角色允许的能力上限：DEVICE 永远不能派工。 */
        fun allowedCapabilities(role: DispatchRole): Set<DispatchCapability> = when (role) {
            DispatchRole.OWNER -> DispatchCapability.values().toSet()
            DispatchRole.AGENT -> DispatchCapability.values().toSet()
            DispatchRole.DEVICE -> setOf(DispatchCapability.EXECUTE)
        }
    }
}

/**
 * 工具级错误：[code] 是稳定的机器可读错误码（如 `forbidden`、
 * `task_stopped`、`version_conflict`），[message] 是给调用方看的说明。
 * 二者都绝不含令牌或其他机密。
 */
class DispatchException(
    val code: String,
    message: String,
    val data: JSONObject? = null,
) : Exception(message)

object DispatchErrors {
    const val INVALID_ARGUMENT = "invalid_argument"
    const val FORBIDDEN = "forbidden"
    const val TASK_NOT_FOUND = "task_not_found"
    const val VERSION_CONFLICT = "version_conflict"
    const val IDEMPOTENCY_CONFLICT = "idempotency_conflict"
    const val TASK_STOPPED = "task_stopped"
    const val TASK_PAUSED = "task_paused"
    const val TASK_TERMINAL = "task_terminal"
    const val TASK_REVOKED = "task_revoked"
    const val TASK_IN_FLIGHT = "task_in_flight"
    const val STALE_ATTEMPT = "stale_attempt"
    const val GOAL_CHANGED = "goal_changed"
    const val CLAIM_REJECTED = "claim_rejected"
    const val UNKNOWN_AGENT = "unknown_agent"
    const val INTERNAL = "internal_error"
}
