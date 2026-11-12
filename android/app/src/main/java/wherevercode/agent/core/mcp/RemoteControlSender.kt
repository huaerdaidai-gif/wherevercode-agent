package wherevercode.agent.core.mcp

/**
 * 阶段 B（V5 紧急开发）：控制指令的远端投递口。
 *
 * [wherevercode.agent.core.control.ControlPlane] 本地改完状态后，
 * 经本接口把同一条控制指令发往正在执行该任务的远端 Hand；远端
 * 回执（confirmed）到后，ControlPlane 再把本地控制态标为已确认
 * （PAUSED / 已停止）。回执不到：本地态保持待确认，不强行终态。
 *
 * 投递是 fire-and-forget + 回调：ControlPlane 的方法是同步的，
 * 不能在里面等网络；[send] 立即返回，远端回执到时调 [onResult]。
 */
data class RemoteControlResult(
    val confirmed: Boolean,
    val message: String,
    val code: String? = null,
)

interface RemoteControlSender {
    fun send(
        target: RemoteTarget,
        taskId: String,
        command: RemoteControlCommand,
        onResult: (RemoteControlResult) -> Unit,
    )
}
