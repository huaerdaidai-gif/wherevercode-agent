package wherevercode.agent.core.task

/**
 * Test-only in-memory [TaskStore] for the phase-1 dispatch chain tests.
 * Mirrors the [RoomTaskStore] rules the chain depends on (optimistic
 * version lock, goal/auth counter checks, legal state transitions via
 * [TaskStates]) without Room or Robolectric, so the fake itself stays
 * honest about illegal writes: they throw exactly like production.
 *
 * Named `DispatchFakeTaskStore` (not `FakeTaskStore`) because
 * [TaskSchedulerTest] already has a private nested fake of that name.
 */
class DispatchFakeTaskStore : TaskStore {
    val tasks = LinkedHashMap<String, TaskRecord>()

    /** When true, [createTask] throws: proves the recorder never breaks chat. */
    var failCreate: Boolean = false

    override fun createTask(record: TaskRecord): TaskRecord {
        if (failCreate) throw IllegalStateException("fake store down")
        TaskStates.checkTransition(null, record.state)
        if (record.taskId.isBlank()) throw IllegalArgumentException("task_id 不能为空")
        if (tasks.containsKey(record.taskId)) throw TaskAlreadyExists(record.taskId)
        val now = System.currentTimeMillis() / 1000
        val created = record.copy(
            createdAt = if (record.createdAt == 0L) now else record.createdAt,
            updatedAt = if (record.updatedAt == 0L) now else record.updatedAt,
            version = 1,
        )
        tasks[record.taskId] = created
        return created
    }

    override fun listTasks(projectId: String?, sessionId: String?): List<TaskRecord> =
        tasks.values.filter {
            (projectId == null || it.projectId == projectId) &&
                (sessionId == null || it.sessionId == sessionId)
        }.toList()

    override fun getTask(taskId: String): TaskRecord? = tasks[taskId]

    override fun updateTask(
        taskId: String,
        changes: Map<String, Any?>,
        expectedVersion: Long,
        expectedGoalVersion: Long,
        expectedAuthEpoch: Long,
    ): TaskRecord? {
        val current = tasks[taskId] ?: return null
        if (current.version != expectedVersion) {
            throw StaleTaskUpdate(taskId, expectedVersion, current.version)
        }
        if (current.goalVersion != expectedGoalVersion) {
            throw ControlVersionStale(
                taskId,
                ControlVersionStale.GOAL_VERSION,
                expectedGoalVersion,
                current.goalVersion,
            )
        }
        if (current.authEpoch != expectedAuthEpoch) {
            throw ControlVersionStale(
                taskId,
                ControlVersionStale.AUTH_EPOCH,
                expectedAuthEpoch,
                current.authEpoch,
            )
        }
        var next = current
        for ((key, value) in changes) {
            next = when (key) {
                "title" -> next.copy(title = value as? String ?: next.title)
                "description" -> next.copy(description = value as? String)
                "state" -> {
                    val target = value as? String
                        ?: throw IllegalArgumentException("state 须为字符串")
                    TaskStates.checkTransition(next.state, target)
                    next.copy(state = target)
                }
                "assigned_agent_id" -> next.copy(assignedAgentId = value as? String)
                "parent_task_id" -> next.copy(parentTaskId = value as? String)
                "dependencies" -> {
                    @Suppress("UNCHECKED_CAST")
                    val deps = (value as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    next.copy(dependencies = deps)
                }
                "priority" -> next.copy(priority = (value as? Number)?.toInt() ?: next.priority)
                "result" -> next.copy(result = value as? String)
                "error" -> next.copy(error = value as? String)
                "brain_id" -> next.copy(brainId = value as? String)
                "needs_attention" -> next.copy(needsAttention = value as? Boolean ?: next.needsAttention)
                "lease_owner" -> next.copy(leaseOwner = value as? String)
                "lease_expires_at" -> next.copy(leaseExpiresAt = (value as? Number)?.toLong())
                "member_id" -> next.copy(memberId = value as? String)
                "attempt" -> next.copy(attempt = (value as? Number)?.toInt() ?: next.attempt)
                "attempt_id" -> next.copy(attemptId = value as? String)
                else -> next
            }
        }
        next = next.copy(
            version = current.version + 1,
            updatedAt = System.currentTimeMillis() / 1000,
        )
        // 与 RoomTaskStore 一致：working 起计 startedAt，终态起计 finishedAt。
        if (next.state == "working" && next.startedAt == null) {
            next = next.copy(startedAt = System.currentTimeMillis() / 1000)
        }
        if (TaskStates.isTerminal(next.state) && next.finishedAt == null) {
            next = next.copy(finishedAt = System.currentTimeMillis() / 1000)
        }
        tasks[taskId] = next
        return next
    }

    override fun expireLeases(nowEpochSeconds: Long): List<String> = emptyList()

    override fun deleteTask(taskId: String): Boolean = tasks.remove(taskId) != null
}
