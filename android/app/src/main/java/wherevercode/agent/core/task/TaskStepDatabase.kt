package wherevercode.agent.core.task

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Task-step persistence (V5 Phase B C1, W2): Room table for the
 * append-only execution steps that [TaskStepStore] describes.
 *
 * One table in the shared [TaskDatabase] (`wherevercode-tasks.db`):
 * `task_steps`, one row per [TaskStep]. Columns follow the [TaskStep]
 * fields exactly; `status` stores the [TaskStepStatus] string
 * (running / success / failed / cancelled).
 *
 * Ordering is by `startedAtEpochSeconds` with `rowid` as the tiebreak,
 * so same-second steps keep insertion order — the in-memory store's
 * stable-sort behaviour (see TaskSteps.kt's CI 42558012978 note).
 */
@Entity(
    tableName = "task_steps",
    indices = [Index("taskId")],
)
data class TaskStepEntity(
    @PrimaryKey val stepId: String,
    val taskId: String,
    val toolCallId: String,
    val toolName: String,
    val argsSummary: String = "",
    val status: String = TaskStepStatus.RUNNING,
    val resultSummary: String? = null,
    val startedAtEpochSeconds: Long = 0,
    val finishedAtEpochSeconds: Long? = null,
)

fun TaskStep.toEntity(): TaskStepEntity =
    TaskStepEntity(
        stepId = stepId,
        taskId = taskId,
        toolCallId = toolCallId,
        toolName = toolName,
        argsSummary = argsSummary,
        status = status,
        resultSummary = resultSummary,
        startedAtEpochSeconds = startedAtEpochSeconds,
        finishedAtEpochSeconds = finishedAtEpochSeconds,
    )

fun TaskStepEntity.toStep(): TaskStep =
    TaskStep(
        stepId = stepId,
        taskId = taskId,
        toolCallId = toolCallId,
        toolName = toolName,
        argsSummary = argsSummary,
        status = status,
        resultSummary = resultSummary,
        startedAtEpochSeconds = startedAtEpochSeconds,
        finishedAtEpochSeconds = finishedAtEpochSeconds,
    )

@Dao
interface TaskStepDao {
    /**
     * REPLACE, not ABORT: the in-memory store overwrites a step with the
     * same stepId, and re-adding is the idempotent retry path. (A
     * REPLACE deletes + reinserts, so the row's rowid — the same-second
     * ordering tiebreak — moves to the end; a re-added step is a new
     * write, so that is the honest order.)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(step: TaskStepEntity)

    @Query(
        "SELECT * FROM task_steps WHERE taskId = :taskId AND toolCallId = :toolCallId " +
            "ORDER BY startedAtEpochSeconds ASC, rowid ASC LIMIT 1",
    )
    fun firstByTaskAndToolCall(taskId: String, toolCallId: String): TaskStepEntity?

    @Query(
        "SELECT * FROM task_steps WHERE taskId = :taskId " +
            "ORDER BY startedAtEpochSeconds ASC, rowid ASC",
    )
    fun listByTask(taskId: String): List<TaskStepEntity>

    @Query(
        "UPDATE task_steps SET status = :status, resultSummary = :resultSummary, " +
            "finishedAtEpochSeconds = :finishedAt " +
            "WHERE stepId = :stepId",
    )
    fun finishStep(
        stepId: String,
        status: String,
        resultSummary: String?,
        finishedAt: Long,
    ): Int

    /**
     * Close every still-running step of a task. Returns the number of
     * rows closed. Only RUNNING rows move: a finished step is never
     * re-closed, matching [InMemoryTaskStepStore.closeRunningSteps].
     */
    @Query(
        "UPDATE task_steps SET status = :status, finishedAtEpochSeconds = :now " +
            "WHERE taskId = :taskId AND status = 'running'",
    )
    fun closeRunningSteps(taskId: String, status: String, now: Long): Int

    @Query("DELETE FROM task_steps WHERE taskId = :taskId")
    fun deleteByTask(taskId: String): Int
}

/**
 * Room-backed [TaskStepStore]. Behaviour matches [InMemoryTaskStepStore]
 * exactly: [finishStep] returns the unchanged step when it is no longer
 * RUNNING (and null when there is no such step), [closeRunningSteps]
 * only closes RUNNING steps, and both validate the status against
 * [TaskStepStatus.ALL].
 *
 * Callers must call this off the main thread: Room forbids main-thread
 * database access and these methods are synchronous (same rule as
 * [RoomTaskStore]).
 */
class RoomTaskStepStore(private val db: TaskDatabase) : TaskStepStore {
    private val dao: TaskStepDao = db.taskStepDao()
    private val lock = Any()

    override fun addStep(step: TaskStep): TaskStep {
        synchronized(lock) { dao.insert(step.toEntity()) }
        return step
    }

    override fun finishStep(
        taskId: String,
        toolCallId: String,
        status: String,
        resultSummary: String?,
    ): TaskStep? {
        require(status in TaskStepStatus.ALL) { "未知步骤状态：$status" }
        synchronized(lock) {
            val current = dao.firstByTaskAndToolCall(taskId, toolCallId) ?: return null
            if (current.status != TaskStepStatus.RUNNING) return current.toStep()
            val finished = current.copy(
                status = status,
                resultSummary = resultSummary,
                finishedAtEpochSeconds = System.currentTimeMillis() / 1000,
            )
            dao.finishStep(
                stepId = finished.stepId,
                status = finished.status,
                resultSummary = finished.resultSummary,
                finishedAt = finished.finishedAtEpochSeconds ?: 0,
            )
            return finished.toStep()
        }
    }

    override fun listSteps(taskId: String): List<TaskStep> =
        synchronized(lock) { dao.listByTask(taskId).map { it.toStep() } }

    override fun closeRunningSteps(taskId: String, status: String): Int {
        require(status in TaskStepStatus.ALL) { "未知步骤状态：$status" }
        synchronized(lock) {
            return dao.closeRunningSteps(taskId, status, System.currentTimeMillis() / 1000)
        }
    }
}
