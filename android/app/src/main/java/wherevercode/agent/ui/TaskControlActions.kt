package wherevercode.agent.ui

import java.util.UUID
import wherevercode.agent.core.control.ControlAction
import wherevercode.agent.core.control.ControlCommandPort
import wherevercode.agent.core.control.ControlResult
import wherevercode.agent.core.control.ControlStatus
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStore

/** Synchronous UI boundary. Call off the main thread; never infer execution from a tap. */
class TaskControlActions(
    private val tasks: TaskStore,
    private val controls: ControlCommandPort,
) {
    fun request(taskId: String, action: ControlAction, goal: String? = null): ControlResult {
        val task = tasks.getTask(taskId)
        if (task == null || task.sessionId != null) {
            return ControlResult(false, false, ControlStatus.RUNNING,
                "ui_task_not_dispatchable", "任务不存在或是聊天轮次，请从原会话控制。")
        }
        val operationId = UUID.randomUUID().toString()
        return when (action) {
            ControlAction.PAUSE -> controls.pause(taskId, operationId, "用户在任务页暂停")
            ControlAction.RESUME -> controls.resume(taskId, operationId, "用户在任务页继续")
            ControlAction.STOP -> controls.stop(taskId, operationId, "用户在任务页停止")
            ControlAction.CHANGE_GOAL -> {
                // Until executor retarget receipts are durable, only change unclaimed work.
                // Paused remote processes may still contain the old prompt in memory.
                if (!TaskStates.isClaimable(task.state)) {
                    ControlResult(false, false, controls.getControlStatus(taskId),
                        "ui_goal_requires_unclaimed", "执行中的目标不能直接替换；请停止确认后重试新目标。")
                } else controls.retarget(taskId, goal.orEmpty(), operationId, "用户在任务页改目标")
            }
            else -> ControlResult(false, false, controls.getControlStatus(taskId),
                "ui_action_unsupported", "此操作请从权限中心执行。")
        }
    }

    /** Keep the row, receipts and audit trail while an executor may still be running. */
    fun deleteAfterConfirmedStop(taskId: String): Boolean = tasks.withTaskLock {
        val task = tasks.getTask(taskId) ?: return@withTaskLock true
        if (!TaskStates.isTerminal(task.state)) {
            val result = request(taskId, ControlAction.STOP)
            if (!result.ok || !result.confirmed) return@withTaskLock false
            val stopped = tasks.getTask(taskId) ?: return@withTaskLock true
            if (!TaskStates.isTerminal(stopped.state)) return@withTaskLock false
        }
        tasks.deleteTask(taskId)
    }

    companion object {
        fun message(result: ControlResult): String = when {
            !result.ok -> "操作未生效：${result.reason ?: result.errorCode.orEmpty()}"
            !result.confirmed -> "请求已受理，执行器尚未确认。${result.reason.orEmpty()}"
            else -> "控制平面已确认：${result.reason.orEmpty()}"
        }
    }
}
