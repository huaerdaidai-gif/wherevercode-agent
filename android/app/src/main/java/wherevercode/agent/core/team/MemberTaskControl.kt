package wherevercode.agent.core.team

import wherevercode.agent.brain.TurnController
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore

/**
 * 成员任务控制（V5 Phase B C3 第二阶段，成员框架）。
 *
 * 接 [TurnController] 现有的三动作（暂停 / 停止 / 改目标），不另造
 * 控制原语；TurnController 只控本轮本机执行，成员任务的控制行
 * （member_task_controls）把暂停/新目标持久化，Hand 接线后由执行
 * 端在下一个安全检查点生效（暂停 = 请求 + 能力协商语义，旧设备不
 * 支持就拒绝，不静默假装——老项目 pause/resume 的 409 规则）。
 *
 * 状态机（执行层 5 态，沿用老项目 execution_host 的 5 态）：
 * queued / running / completed / failed / cancelled。App 内的
 * canonical 态按此映射：queued/assigned → QUEUED（还没跑），
 * working/blocked/review → RUNNING（进行中），completed/failed/
 * cancelled → 同名终态。计划层的 9 态（waiting/ready/stopped 等）
 * 是另一套，不拼成一套。
 *
 * 暂停是独立布尔字段，不是状态：[MemberTaskControlEntity.paused]
 * 与任务 state 分开存；已暂停的排队任务在分派时被明确拒绝
 * （TASK_PAUSED），不是静默跳过。
 *
 * 取消语义（老项目 Q2）：取消后已落盘的改动保留、artifact 不再
 * 附加——[MemberReturnReceiver] 对终态任务一律拒绝新回传，但取消
 * 前已落盘的回传记录保留、可读（见 MemberTaskControlTest）。
 */
enum class MemberExecutionState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** canonical 态 → 执行层 5 态（assigned 还没跑，算 QUEUED；requeued / rework 等再认领，也算 QUEUED）。 */
fun executionStateOf(task: TaskRecord): MemberExecutionState = when (task.state) {
    "queued", "assigned", "requeued", "rework" -> MemberExecutionState.QUEUED
    "working", "blocked", "review" -> MemberExecutionState.RUNNING
    "completed" -> MemberExecutionState.COMPLETED
    "failed" -> MemberExecutionState.FAILED
    "cancelled" -> MemberExecutionState.CANCELLED
    else -> throw IllegalArgumentException("未知任务状态：${task.state}")
}

class MemberTaskControl(
    private val taskStore: TaskStore,
    private val memberStore: TeamMemberStore,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val lock = Any()
    private val controllers = HashMap<String, TurnController>()

    private fun controllerFor(taskId: String): TurnController =
        synchronized(lock) { controllers.getOrPut(taskId) { TurnController() } }

    /** 任务是否已暂停（控制行上的独立布尔字段）。 */
    fun isPaused(taskId: String): Boolean =
        memberStore.getControl(taskId)?.paused == true

    fun executionState(taskId: String): MemberExecutionState? =
        taskStore.getTask(taskId)?.let { executionStateOf(it) }

    /**
     * 请求暂停。终态任务返回 false；已停止/已改目标的轮次不再降级
     * 为暂停（TurnController 语义）。成功时控制行 paused=true。
     */
    fun pauseTask(taskId: String): Boolean {
        val task = taskStore.getTask(taskId) ?: return false
        if (executionStateOf(task) in
            setOf(
                MemberExecutionState.COMPLETED,
                MemberExecutionState.FAILED,
                MemberExecutionState.CANCELLED,
            )
        ) return false
        if (!controllerFor(taskId).pause()) return false
        memberStore.saveControl(
            MemberTaskControlEntity(
                taskId = taskId,
                memberId = task.memberId ?: "",
                paused = true,
                pendingGoal = memberStore.getControl(taskId)?.pendingGoal,
                updatedAtEpochSeconds = nowSeconds(),
            ),
        )
        return true
    }

    /** 恢复已暂停的任务：仅 PAUSED → RUNNING；其余返回 false。 */
    fun resumeTask(taskId: String): Boolean {
        val task = taskStore.getTask(taskId) ?: return false
        if (!controllerFor(taskId).resume()) return false
        memberStore.saveControl(
            MemberTaskControlEntity(
                taskId = taskId,
                memberId = task.memberId ?: "",
                paused = false,
                pendingGoal = memberStore.getControl(taskId)?.pendingGoal,
                updatedAtEpochSeconds = nowSeconds(),
            ),
        )
        return true
    }

    /**
     * 请求停止：控制器置 STOPPED，任务行走 canonical 转移到
     * cancelled（终态）。已终态返回 false。取消后控制行保留
     * （paused=false），已落盘的改动与回传记录不受影响。
     */
    fun stopTask(taskId: String): Boolean {
        val task = taskStore.getTask(taskId) ?: return false
        if (executionStateOf(task) in
            setOf(
                MemberExecutionState.COMPLETED,
                MemberExecutionState.FAILED,
                MemberExecutionState.CANCELLED,
            )
        ) return false
        // 先写任务行终态，再置控制器：任务行是权威；若任务行写
        // 失败，控制器也不应先行置 STOPPED（否则下次 stop 会因
        // “已停止”返回 false，而任务行却还不是终态）。
        val updated = try {
            taskStore.updateTask(
                taskId,
                mapOf("state" to "cancelled"),
                task.version,
                task.goalVersion,
                task.authEpoch,
            )
        } catch (_: Exception) {
            null
        } ?: return false
        controllerFor(taskId).stop()
        memberStore.saveControl(
            MemberTaskControlEntity(
                taskId = taskId,
                memberId = updated.memberId ?: "",
                paused = false,
                pendingGoal = memberStore.getControl(taskId)?.pendingGoal,
                updatedAtEpochSeconds = nowSeconds(),
            ),
        )
        return true
    }

    /**
     * 改目标：旧目标作废，本任务停在当前态、等用户输入新目标
     * （不自动续跑旧目标）；新目标存进控制行 pendingGoal。
     * 已停止/已改目标时返回 false；任务 state 本身不动。
     */
    fun changeGoal(taskId: String, newGoal: String): Boolean {
        require(newGoal.isNotBlank()) { "新目标不能为空" }
        val task = taskStore.getTask(taskId) ?: return false
        if (executionStateOf(task) in
            setOf(
                MemberExecutionState.COMPLETED,
                MemberExecutionState.FAILED,
                MemberExecutionState.CANCELLED,
            )
        ) return false
        if (!controllerFor(taskId).changeGoal()) return false
        memberStore.saveControl(
            MemberTaskControlEntity(
                taskId = taskId,
                memberId = task.memberId ?: "",
                paused = memberStore.getControl(taskId)?.paused ?: false,
                pendingGoal = newGoal,
                updatedAtEpochSeconds = nowSeconds(),
            ),
        )
        return true
    }
}
