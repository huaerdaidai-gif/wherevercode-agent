package wherevercode.agent.core.task

/**
 * Kanban 状态机（主流做法）：任务的看板列。
 *
 * 主流脊柱：Todo → In Progress → In Review → Done（+ Blocked，返工 = 反向流转）。
 * 来源：GitHub Projects v2（Status 单选：Todo / In Progress / Done）、
 * Linear / Jira / OpenProject（报告 D1）；通用规范 §4.2。
 *
 * V5 的规范状态（[TaskStates.CANONICAL_STATES]，源自老项目 task_states.py）
 * 是另一套名字；这里只做映射，不改状态机本身：
 * - queued（未领单）→ TODO
 * - assigned / working（已指派 / 进行中）→ IN_PROGRESS
 * - review（待审核）→ IN_REVIEW
 * - rework（返工：审查打回，待重新认领）→ IN_REVIEW（审查结果，
 *   认领后进 IN_PROGRESS）
 * - requeued（已回队：租约过期，等再认领）→ TODO
 * - completed（已完成）→ DONE
 * - blocked（阻塞）→ BLOCKED（独立列，不是状态机之外的状态）
 * - failed / cancelled 是终态，不进看板列（显示为失败 / 已停止）。
 *
 * 返工 = 反向流转：review → rework → assigned → working
 * （P0 之前是 review → working 直跳，无 rework 态）。
 */
enum class KanbanColumn(val wire: String, val label: String) {
    TODO("todo", "待办"),
    IN_PROGRESS("in_progress", "进行中"),
    IN_REVIEW("in_review", "待审核"),
    DONE("done", "已完成"),
    BLOCKED("blocked", "已阻塞");

    companion object {
        fun fromWire(wire: String?): KanbanColumn? =
            entries.firstOrNull { it.wire == wire }
    }
}

object KanbanStatus {

    /** 规范状态 → 看板列；failed / cancelled 是终态，不进看板列，返回 null。 */
    fun columnOf(canonicalState: String): KanbanColumn? = when (canonicalState.lowercase()) {
        "queued", "requeued" -> KanbanColumn.TODO
        "assigned", "working" -> KanbanColumn.IN_PROGRESS
        "review", "rework" -> KanbanColumn.IN_REVIEW
        "completed" -> KanbanColumn.DONE
        "blocked" -> KanbanColumn.BLOCKED
        else -> null
    }

    /**
     * 所有状态的中文显示（含终态与别名）；看板列的标签与 [columnOf] 一致。
     * 是 [wherevercode.agent.ui.taskStateLabel] 的唯一事实来源。
     */
    fun labelOf(state: String): String = when (state.lowercase()) {
        "queued", "pending" -> "待办"
        // assigned 是“已指派/已接收”（Agent 领了单、还没开工），与 working
        // 的“进行中”区分开；看板列上两者同属 IN_PROGRESS（见 columnOf）。
        // 旧 taskStateLabel 里 claimed → 已接收，assigned 沿用同一标签。
        "assigned", "claimed" -> "已接收"
        "working", "running" -> "进行中"
        "review" -> "待审核"
        "rework" -> "返工"
        "requeued" -> "已回队"
        "blocked" -> "已阻塞"
        "completed", "complete", "done" -> "已完成"
        "paused" -> "已暂停"
        "cancel_requested", "stopping" -> "正在停止"
        "failed", "error" -> "失败"
        "cancelled", "canceled", "stopped" -> "已停止"
        else -> "处理中"
    }

    /** 返工 = 反向流转：review → rework（打回），rework → working（重做开始）。 */
    fun isRework(fromState: String, toState: String): Boolean =
        (fromState.lowercase() == "review" && toState.lowercase() == "rework") ||
            (fromState.lowercase() == "rework" && toState.lowercase() == "working")
}
