package wherevercode.agent.core.task

/**
 * 停止/取消后的写入防护（2026-11-13 安全加固）。
 *
 * 问题：[TaskStates] 的转移表让终态（completed / failed / cancelled）
 * 没有出边，所以“改状态”的写在终态上会被拒；但**不带 state 的写**
 * （result / error / attempt / lease 续租 / 换 assignee …）照样落盘。
 * 典型时序：用户点“停止”→ 任务进 cancelled，结果写“任务被用户停止”；
 * 随后还在跑的执行者（LocalTaskRunner 流水线保留分支、RemoteTaskDispatcher、
 * MemberDelivery 回传）把迟到的 result 写上去，覆盖掉取消说明，用户看到
 * 一个“已取消但有成品结果”的任务；租约续期也会把终态任务的租约续上。
 *
 * 规则：任务已在终态时，只允许下面这些“善后”写：
 * - title / description（用户改名、补说明）；
 * - needs_attention（挂/摘提醒旗）；
 * - lease_owner / lease_expires_at **清空**（置 null）。
 * 其余字段的写一律抛 [TerminalTaskWriteRejected]，什么都不落。
 * 带 state 的写仍交给底层 store（转移表本来就会拒绝）。
 *
 * 实现为 [TaskStore] 装饰器，不改 [RoomTaskStore] 本体（那是单兵/任务
 * 核心逻辑）。检查与写入之间的竞态由底层乐观锁兜底：并发的取消会让
 * version 变化，底层写抛 StaleTaskUpdate。
 */
class TerminalTaskWriteRejected(val taskId: String, val state: String, val fields: Set<String>) :
    IllegalStateException("任务 $taskId 已是终态 $state，拒绝写入 ${fields.sorted().joinToString(",")}")

object TerminalWritePolicy {
    /** 终态任务上仍允许改的字段（与值无关）。 */
    val ALWAYS_ALLOWED_ON_TERMINAL: Set<String> = setOf("title", "description", "needs_attention")

    /** 终态任务上只允许清空（置 null）的字段。 */
    val CLEAR_ONLY_ON_TERMINAL: Set<String> = setOf("lease_owner", "lease_expires_at")

    /** 返回被拒的字段；空集合 = 放行。非终态任务、或写里带 state 时一律放行（交给转移表）。 */
    fun rejectedFields(current: TaskRecord, changes: Map<String, Any?>): Set<String> {
        if (!TaskStates.isTerminal(current.state)) return emptySet()
        if ("state" in changes) return emptySet()
        val rejected = mutableSetOf<String>()
        for ((key, value) in changes) {
            if (key in TaskContract.CONTROLLED_FIELDS) continue // 底层本来就丢弃
            if (key !in TaskContract.MUTABLE_FIELDS && key !in TaskContract.LOCAL_FIELDS) continue // 未知键底层丢弃
            if (key in ALWAYS_ALLOWED_ON_TERMINAL) continue
            if (key in CLEAR_ONLY_ON_TERMINAL && value == null) continue
            rejected += key
        }
        return rejected
    }
}

/**
 * [TaskStore] 装饰器：终态任务上拒绝迟到的业务写。同时透传
 * [TaskControlCounterStore]（ControlPlane 按 `as?` 取计数口），不丢能力。
 */
class TerminalWriteGuardTaskStore(
    private val delegate: TaskStore,
) : TaskStore, TaskControlCounterStore {

    override fun createTask(record: TaskRecord): TaskRecord = delegate.createTask(record)

    override fun listTasks(projectId: String?, sessionId: String?): List<TaskRecord> =
        delegate.listTasks(projectId, sessionId)

    override fun getTask(taskId: String): TaskRecord? = delegate.getTask(taskId)

    override fun updateTask(
        taskId: String,
        changes: Map<String, Any?>,
        expectedVersion: Long,
        expectedGoalVersion: Long,
        expectedAuthEpoch: Long,
    ): TaskRecord? {
        val current = delegate.getTask(taskId)
        if (current != null) {
            val rejected = TerminalWritePolicy.rejectedFields(current, changes)
            if (rejected.isNotEmpty()) {
                throw TerminalTaskWriteRejected(taskId, current.state, rejected)
            }
        }
        return delegate.updateTask(taskId, changes, expectedVersion, expectedGoalVersion, expectedAuthEpoch)
    }

    override fun expireLeases(nowEpochSeconds: Long): List<String> = delegate.expireLeases(nowEpochSeconds)

    override fun recordTaskEvent(taskId: String, kind: String, reason: String?, actor: String?) =
        delegate.recordTaskEvent(taskId, kind, reason, actor)

    override fun deleteTask(taskId: String): Boolean = delegate.deleteTask(taskId)

    override fun updateControlCounters(taskId: String, goalVersion: Long, authEpoch: Long): TaskRecord? =
        (delegate as? TaskControlCounterStore)?.updateControlCounters(taskId, goalVersion, authEpoch)
}
