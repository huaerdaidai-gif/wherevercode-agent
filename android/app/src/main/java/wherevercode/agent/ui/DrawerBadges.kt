package wherevercode.agent.ui

import wherevercode.agent.core.task.TaskRecord

/**
 * 抽屉角标（V5 Phase B C2，批 3，R2；R4 改：0 也显示）。
 *
 * 角标数字绝不写死：运行中任务数 = 任务库里真实处于 assigned /
 * working 的任务数（与 TaskScheduler.activeWorkCount() 同一口径；
 * reviewing 不算在手任务，见队列规矩）；待审批数 = 审批门当前
 * 正在等待用户决定的审批个数——ApprovalBroker 只持有一份待审批，
 * 所以是 0 或 1。任一数读不到时为 null（未知），只有未知不显示
 * 角标；0 显示“0”，绝不伪造数字。宿主（MainActivity）在打开抽屉
 * 时读真实数据源，经 DrawerView.setBadges() 传入；计数口径见
 * MainActivity.refreshDrawerBadges()（任务库 listTasks +
 * runningCount；审批门 pendingApproval() != null → pendingCount）。
 */
data class DrawerBadges(
    val runningTasks: Int? = null,
    val pendingApprovals: Int? = null,
) {
    companion object {
        /** 运行中任务数：assigned / working 才算运行中。 */
        fun runningCount(tasks: List<TaskRecord>): Int =
            tasks.count { it.state == "assigned" || it.state == "working" }

        /** 待审批数：门里正等着的只有 0 或 1 份。 */
        fun pendingCount(hasPendingApproval: Boolean): Int =
            if (hasPendingApproval) 1 else 0
    }

    /** 该入口的角标：未知（null）不显示；0 显示“0”。 */
    fun badgeFor(entryId: String): Int? = when (entryId) {
        "nav_running_tasks" -> runningTasks
        "nav_pending_approval" -> pendingApprovals
        else -> null
    }
}
