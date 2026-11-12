package wherevercode.agent.core.task

/**
 * Per-task execution steps (phase 1, U3 dispatch 最小链).
 *
 * Why a separate store, not a column on [TaskRecord]: the task row is a
 * Room entity at schema version 2 with a shipped migration chain; adding
 * a steps column would force a 2 -> 3 migration for data that is
 * append-only and display-only. Steps live in their own Room table
 * (task_steps, see TaskStepDatabase; V5 C1) instead, and a task row
 * survives a process restart together with its steps.
 *
 * A step is one tool call inside one task's execution: the tool name,
 * a redacted argument summary, and the redacted result summary. Steps
 * are written by [TurnTaskRecorder] (chat turns mirrored as tasks) and
 * by [LocalTaskRunner] (dispatched tasks executed locally). Nothing
 * here ever claims a step ran when it did not: a step is created
 * RUNNING when the tool call is issued and only moves to SUCCESS /
 * FAILED when its result arrives; a step still RUNNING when its task
 * finishes is closed as CANCELLED by whoever finishes the task.
 *
 * Pure Kotlin (no Android imports) so the behaviour is unit-testable.
 */
data class TaskStep(
    val stepId: String,
    val taskId: String,
    val toolCallId: String,
    val toolName: String,
    val argsSummary: String = "",
    val status: String = TaskStepStatus.RUNNING,
    val resultSummary: String? = null,
    val startedAtEpochSeconds: Long = 0,
    val finishedAtEpochSeconds: Long? = null,
)

object TaskStepStatus {
    const val RUNNING: String = "running"
    const val SUCCESS: String = "success"
    const val FAILED: String = "failed"
    const val CANCELLED: String = "cancelled"

    val ALL: Set<String> = setOf(RUNNING, SUCCESS, FAILED, CANCELLED)
}

interface TaskStepStore {
    fun addStep(step: TaskStep): TaskStep
    fun finishStep(
        taskId: String,
        toolCallId: String,
        status: String,
        resultSummary: String?,
    ): TaskStep?

    fun listSteps(taskId: String): List<TaskStep>

    /** Close every still-running step of [taskId] as [status] (default CANCELLED). */
    fun closeRunningSteps(taskId: String, status: String = TaskStepStatus.CANCELLED): Int
}

class InMemoryTaskStepStore : TaskStepStore {
    private val lock = Any()
    private val steps = LinkedHashMap<String, TaskStep>()

    override fun addStep(step: TaskStep): TaskStep {
        synchronized(lock) {
            steps[step.stepId] = step
            return step
        }
    }

    override fun finishStep(
        taskId: String,
        toolCallId: String,
        status: String,
        resultSummary: String?,
    ): TaskStep? {
        require(status in TaskStepStatus.ALL) { "未知步骤状态：$status" }
        synchronized(lock) {
            val current = steps.values.firstOrNull {
                it.taskId == taskId && it.toolCallId == toolCallId
            } ?: return null
            if (current.status != TaskStepStatus.RUNNING) return current
            val finished = current.copy(
                status = status,
                resultSummary = resultSummary,
                finishedAtEpochSeconds = System.currentTimeMillis() / 1000,
            )
            steps[current.stepId] = finished
            return finished
        }
    }

    override fun listSteps(taskId: String): List<TaskStep> =
        synchronized(lock) {
            // 只按开始时间排序，不以 stepId（随机 UUID）作第二键：同一秒内
            // 连续的工具调用会被随机打乱（CI 42558012978：约 50% 概率失败）。
            // sortedWith 是稳定排序，同时间戳保持插入顺序（steps 是 LinkedHashMap）。
            steps.values.filter { it.taskId == taskId }
                .sortedWith(compareBy { it.startedAtEpochSeconds })
        }

    override fun closeRunningSteps(taskId: String, status: String): Int {
        require(status in TaskStepStatus.ALL) { "未知步骤状态：$status" }
        synchronized(lock) {
            var closed = 0
            val now = System.currentTimeMillis() / 1000
            for ((id, step) in steps) {
                if (step.taskId == taskId && step.status == TaskStepStatus.RUNNING) {
                    steps[id] = step.copy(status = status, finishedAtEpochSeconds = now)
                    closed += 1
                }
            }
            return closed
        }
    }
}
