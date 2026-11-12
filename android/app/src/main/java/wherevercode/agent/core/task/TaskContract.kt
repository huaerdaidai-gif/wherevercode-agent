package wherevercode.agent.core.task

/**
 * Task assignment contract, ported from the old project's
 * `task_assignment_contract.py` (blob 7ea1a322, main fd6b43d2).
 *
 * Old -> new name mapping:
 * - TASK_STATES -> TASK_STATES (9 legacy states, kept for mapping input)
 * - RECORD_FIELDS -> RECORD_FIELDS (17 fields, same names and order)
 * - MUTABLE_FIELDS -> MUTABLE_FIELDS
 * - SERVER_OWNED_FIELDS -> SCHEDULER_OWNED_FIELDS (renamed: in the
 *   decentralised project there is no centre server; the LOCAL SCHEDULER
 *   owns these fields and a client may never write them)
 * - TaskAssignmentStoreProtocol -> TaskStore (interface, same four methods)
 * - StaleTaskUpdate -> StaleTaskUpdate
 * - TaskAlreadyExists -> TaskAlreadyExists
 * - TaskDependencyError -> TaskDependencyError
 * - normalize_record_fields(record) -> normalizeRecordFields(record)
 *
 * Local extensions (NOT in RECORD_FIELDS, documented here, never sent
 * to the old platform as record fields): `brain_id` (which configured
 * brain owns the task; switching brains only changes this field),
 * `needs_attention` (the task_states.py attention flag, independent of
 * state), `lease_owner` / `lease_expires_at` (600s claim lease, P0:
 * 10 minutes — the old 90s did not cover one real brain turn),
 * `member_id` / `attempt` / `attempt_id` (C3 second stage, member
 * framework: which team member holds the task, which claim attempt
 * this is, and the attempt's id — the old ExecutionHostTaskStore
 * `attempt` / `attempt_id` binding, same 600s lease semantics; P0
 * made the plain claim path bump attempt too, so both runners are
 * fenced by attemptId). Optional `handDeviceId`, `handAuthorizationId`,
 * and `handFolder` persist the user's selected Hand route on the same
 * task row. These are routing references only; they are not credentials.
 * The old TASK_STATES `waiting`/`ready` must be mapped through
 * TaskStates.canonical() first; do not build a second state machine.
 */
object TaskContract {
    val TASK_STATES: List<String> = listOf(
        "waiting", "ready", "assigned", "working", "blocked",
        "review", "completed", "failed", "stopped",
    )

    val RECORD_FIELDS: List<String> = listOf(
        "task_id", "project_id", "session_id", "title", "description",
        "state", "assigned_agent_id", "parent_task_id", "dependencies",
        "priority", "created_at", "started_at", "finished_at",
        "updated_at", "result", "error", "version",
    )

    val MUTABLE_FIELDS: Set<String> = setOf(
        "title", "description", "state", "assigned_agent_id",
        "parent_task_id", "dependencies", "priority", "result", "error",
    )

    /** Fields owned by the scheduler; a client can never supply them. */
    val SCHEDULER_OWNED_FIELDS: Set<String> = setOf(
        "task_id", "created_at", "started_at", "finished_at",
        "updated_at", "version",
    )

    /** Local extensions, see the file doc comment. */
    val LOCAL_FIELDS: Set<String> = setOf(
        "brain_id", "needs_attention", "lease_owner", "lease_expires_at",
        "member_id", "attempt", "attempt_id",
    )

    /**
     * Fields owned by ControlPlane (M2); a client can never write them.
     * `goal_version` / `auth_epoch` are the two monotonic counters from
     * M1 decision 2 (GoalVersion / AuthorizationEpoch); only ControlPlane
     * bumps them, always inside the same Room transaction as the state
     * change. `updateTask` drops these keys from `changes` and checks
     * them against [TaskRecord.goalVersion] / [TaskRecord.authEpoch]
     * instead. They are NOT in LOCAL_FIELDS.
     */
    val CONTROLLED_FIELDS: Set<String> = setOf("goal_version", "auth_epoch")

    /** Return a copy of [record] restricted to known fields. Unknown fields are dropped. */
    fun normalizeRecordFields(record: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (field in RECORD_FIELDS) {
            if (field in record) out[field] = record[field]
        }
        return out
    }
}

class TaskAlreadyExists(val taskId: String) :
    IllegalStateException("task already exists: $taskId")

class StaleTaskUpdate(
    val taskId: String,
    val expectedVersion: Long,
    val currentVersion: Long,
) : IllegalStateException(
    "stale update for task $taskId: expected version $expectedVersion, current $currentVersion",
)

class TaskDependencyError(message: String) : IllegalArgumentException(message)

/**
 * Thrown by [TaskStore.updateTask] when the caller's expected
 * goal-version / auth-epoch does not match the stored counters
 * (M2: old-version writes are rejected, nothing is written).
 * [field] is one of [GOAL_VERSION] / [AUTH_EPOCH].
 */
class ControlVersionStale(
    val taskId: String,
    val field: String,
    val expected: Long,
    val current: Long,
) : IllegalStateException(
    "stale $field for task $taskId: expected $expected, current $current",
) {
    companion object {
        const val GOAL_VERSION: String = "goal_version"
        const val AUTH_EPOCH: String = "auth_epoch"
    }
}

/**
 * One task row. Field names follow RECORD_FIELDS; `brainId`,
 * `needsAttention` and the lease fields are the local extensions
 * documented in [TaskContract].
 */
data class TaskRecord(
    val taskId: String,
    val projectId: String? = null,
    val sessionId: String? = null,
    val title: String = "",
    val description: String? = null,
    val state: String = "queued",
    val assignedAgentId: String? = null,
    val parentTaskId: String? = null,
    val dependencies: List<String> = emptyList(),
    val priority: Int = 0,
    val createdAt: Long = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val updatedAt: Long = 0,
    val result: String? = null,
    val error: String? = null,
    val version: Long = 1,
    val brainId: String? = null,
    val needsAttention: Boolean = false,
    val leaseOwner: String? = null,
    val leaseExpiresAt: Long? = null,
    /** C3 second stage: the team member holding this task (memberId). */
    val memberId: String? = null,
    /** C3 second stage: claim attempt count (old `attempt`, starts 0). */
    val attempt: Int = 0,
    /** C3 second stage: this attempt's id (old `attempt_id`, uuid hex). */
    val attemptId: String? = null,
    /** M2 control counters (snapshot taken at claim time). */
    val goalVersion: Long = 1,
    val authEpoch: Long = 1,
    /** Optional Hand executor selected in the App; correlation fields only. */
    val handDeviceId: String? = null,
    val handAuthorizationId: String? = null,
    val handFolder: String? = null,
) {
    /**
     * 该任务所在的看板列（主流 Kanban，见 [KanbanStatus]）。
     * failed / cancelled 是终态，不进看板列，返回 null。
     */
    val kanbanColumn: KanbanColumn?
        get() = KanbanStatus.columnOf(state)
}

/**
 * Minimal store interface the scheduler and tools depend on.
 * Implementations must validate identifiers and fields and raise
 * [TaskAlreadyExists], [StaleTaskUpdate] or [TaskDependencyError] as
 * appropriate. State changes must pass TaskStates.checkTransition.
 *
 * M2: [updateTask] additionally takes [expectedGoalVersion] and
 * [expectedAuthEpoch]; a mismatch throws [ControlVersionStale] and
 * nothing is written. Keys in [TaskContract.CONTROLLED_FIELDS] inside
 * `changes` are dropped, never applied.
 */
interface TaskStore {
    fun createTask(record: TaskRecord): TaskRecord
    fun listTasks(projectId: String? = null, sessionId: String? = null): List<TaskRecord>
    fun getTask(taskId: String): TaskRecord?
    fun updateTask(
        taskId: String,
        changes: Map<String, Any?>,
        expectedVersion: Long,
        expectedGoalVersion: Long,
        expectedAuthEpoch: Long,
    ): TaskRecord?
    /** Expire claim leases older than [nowEpochSeconds]; returns the expired task ids. */
    fun expireLeases(nowEpochSeconds: Long): List<String>

    /**
     * 记一条非状态转移的审计事件（V5 跨设备开发，2026-11-12，A5）：
     * 派活文件夹回落（[TaskEventKind.FOLDER_FALLBACK]）不改任务态，
     * 不能走 [updateTask] 的“状态变了才记事件”分支，故单独立口。
     * 默认不记（测试替身沿用）；生产 [RoomTaskStore] 落盘，toState
     * 取任务当时态。任务不存在时默认什么都不做。
     */
    fun recordTaskEvent(
        taskId: String,
        kind: String,
        reason: String?,
        actor: String? = null,
    ) {
    }

    /**
     * Delete a task and every task-scoped row that belongs to it: the
     * task row, its steps, its control rows (state / counters /
     * operation ids / audit log), its member returns and member task
     * controls, plus — when the tables exist (P0 `task_events`, P2
     * `pipeline_verdicts`) — its event and verdict rows.
     *
     * Chat is never touched: conversation messages and sessions stay,
     * even when they reference this taskId. Repos, branches and files
     * are never touched: this only deletes TaskStore records.
     * Returns true when a task row was removed, false when the task
     * did not exist. Callers stop a running task first (see
     * MainActivity.deleteTaskRecord); the store itself deletes
     * regardless of state, so a stop that races a finish cannot leave
     * a ghost row behind.
     */
    fun deleteTask(taskId: String): Boolean
}
