package wherevercode.agent.core.queue

import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStates

/**
 * 队列与调度看板纯逻辑（核心功能推进第 2 项，2026-11-15；纯 Kotlin，可单测）。
 *
 * 数据口径：输入是 [TaskStore][wherevercode.agent.core.task.TaskStore]
 * 的原始任务行（listTasks() 全量，含聊天轮次行与分派执行行），本类只做
 * 分组与排序，不做任务库那种“同一逻辑任务归并成一行”的展示归并
 * （那是 TaskLibraryGrouping 的口径，两个页面口径不同、不互相冒充）。
 *
 * 分组口径（以 TaskRecord.state 的实际值为准，未知状态不吞）：
 * - 排队中 QUEUED：可认领三态（[TaskStates.isClaimable]：queued /
 *   requeued / rework），即“等执行器认领”的任务。按 createdAt 升序
 *   （FIFO，与 LocalTaskRunner.pickNext 的 minByOrNull { createdAt }
 *   同一口径），组内编号 queuePosition 从 1 起，即“第 N 位”。
 * - 运行中 RUNNING：assigned（已认领未开工）/ working。按开始时间
 *   排序：startedAt 倒序，startedAt 为空时退 updatedAt。
 * - 待审核 REVIEW：review。按 updatedAt 倒序。
 * - 已阻塞 BLOCKED：blocked。按 updatedAt 倒序。
 * - 已暂停 PAUSED：paused（KanbanStatus 有此标签；本地执行器暂停实际
 *   落 requeued，但其他路径/旧数据可能带此态，单列不吞）。
 * - 已完成 COMPLETED / 失败 FAILED / 已取消 CANCELLED：终态三组，
 *   按 finishedAt 倒序（为空退 updatedAt），最近落终态的在前。
 * - 其他 OTHER：以上都不认识的状态值（如旧数据里的原始态），单列
 *   展示，绝不丢弃、绝不猜测归类。按 updatedAt 倒序。
 *
 * 展示顺序：排队中 → 运行中 → 待审核 → 已阻塞 → 已暂停 → 已完成 →
 * 失败 → 已取消 → 其他；空组不出现在结果里。
 *
 * 调度口径（诚实）：本类只整理与展示队列，不认领、不执行、不改任何
 * 任务状态，也不改变调度顺序；认领与执行仍由既有调度器/执行器负责
 * （本地分派由 LocalTaskRunner 按 createdAt FIFO 逐个认领）。
 */

enum class QueueGroup(val title: String) {
    QUEUED("排队中"),
    RUNNING("运行中"),
    REVIEW("待审核"),
    BLOCKED("已阻塞"),
    PAUSED("已暂停"),
    COMPLETED("已完成"),
    FAILED("失败"),
    CANCELLED("已取消"),
    OTHER("其他"),
}

/** 看板中的一条任务：原任务行 + 排队位次（仅排队中组有，其余为 null）。 */
data class QueueBoardItem(
    val task: TaskRecord,
    val queuePosition: Int?,
)

/** 一个非空分组（组内已按该组规则排序）。 */
data class QueueBoardGroup(
    val group: QueueGroup,
    val items: List<QueueBoardItem>,
) {
    val count: Int get() = items.size
}

/** 看板结果：只含非空组，按展示顺序排列。 */
data class QueueBoardResult(
    val groups: List<QueueBoardGroup>,
    val totalCount: Int,
) {
    fun countOf(group: QueueGroup): Int =
        groups.firstOrNull { it.group == group }?.count ?: 0

    val runningCount: Int get() = countOf(QueueGroup.RUNNING)

    val queuedCount: Int get() = countOf(QueueGroup.QUEUED)

    val isEmpty: Boolean get() = totalCount == 0
}

object QueueBoard {

    /** 状态值 → 分组。状态按小写匹配（与 KanbanStatus 的口径一致）。 */
    fun groupOf(state: String): QueueGroup {
        val normalized = state.lowercase()
        return when {
            TaskStates.isClaimable(normalized) -> QueueGroup.QUEUED
            normalized == "assigned" || normalized == "working" -> QueueGroup.RUNNING
            normalized == "review" -> QueueGroup.REVIEW
            normalized == "blocked" -> QueueGroup.BLOCKED
            normalized == "paused" -> QueueGroup.PAUSED
            normalized == "completed" -> QueueGroup.COMPLETED
            normalized == "failed" -> QueueGroup.FAILED
            normalized == "cancelled" -> QueueGroup.CANCELLED
            else -> QueueGroup.OTHER
        }
    }

    fun build(tasks: List<TaskRecord>): QueueBoardResult {
        if (tasks.isEmpty()) return QueueBoardResult(emptyList(), 0)
        val byGroup = tasks.groupBy { groupOf(it.state) }
        val groups = QueueGroup.entries.mapNotNull { group ->
            val members = byGroup[group]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val sorted = sortGroup(group, members)
            val items = sorted.mapIndexed { index, task ->
                QueueBoardItem(
                    task = task,
                    queuePosition = if (group == QueueGroup.QUEUED) index + 1 else null,
                )
            }
            QueueBoardGroup(group, items)
        }
        return QueueBoardResult(groups = groups, totalCount = tasks.size)
    }

    private fun sortGroup(group: QueueGroup, members: List<TaskRecord>): List<TaskRecord> =
        when (group) {
            // FIFO：创建时间升序（与执行器认领口径一致），同刻按 id 稳定。
            QueueGroup.QUEUED -> members.sortedWith(
                compareBy<TaskRecord> { it.createdAt }.thenBy { it.taskId },
            )
            // 运行中：最近开始（或最近更新）的在前。
            QueueGroup.RUNNING -> members.sortedWith(
                compareByDescending<TaskRecord> { it.startedAt ?: it.updatedAt }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.taskId },
            )
            // 终态：最近落终态的在前（finishedAt 为空退 updatedAt）。
            QueueGroup.COMPLETED, QueueGroup.FAILED, QueueGroup.CANCELLED ->
                members.sortedWith(
                    compareByDescending<TaskRecord> { it.finishedAt ?: it.updatedAt }
                        .thenBy { it.taskId },
                )
            // 其余（待审核/已阻塞/已暂停/其他）：最近更新的在前。
            else -> members.sortedWith(
                compareByDescending<TaskRecord> { it.updatedAt }.thenBy { it.taskId },
            )
        }
}
