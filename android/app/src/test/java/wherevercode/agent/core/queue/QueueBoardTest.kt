package wherevercode.agent.core.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.task.TaskRecord

/**
 * 队列与调度看板纯逻辑测试（核心功能推进第 2 项，2026-11-15）。
 *
 * 覆盖：全状态分组口径、排队 FIFO 编号、运行中/终态排序、未知状态
 * 归其他不丢弃、空列表、计数。
 */
class QueueBoardTest {

    private fun task(
        id: String,
        state: String,
        createdAt: Long = 0L,
        startedAt: Long? = null,
        finishedAt: Long? = null,
        updatedAt: Long = 0L,
    ) = TaskRecord(
        taskId = id,
        title = "任务$id",
        state = state,
        createdAt = createdAt,
        startedAt = startedAt,
        finishedAt = finishedAt,
        updatedAt = updatedAt,
    )

    @Test
    fun `empty list yields empty board`() {
        val board = QueueBoard.build(emptyList())
        assertTrue(board.isEmpty)
        assertEquals(0, board.totalCount)
        assertTrue(board.groups.isEmpty())
        assertEquals(0, board.runningCount)
        assertEquals(0, board.queuedCount)
    }

    @Test
    fun `every canonical state lands in its documented group`() {
        val board = QueueBoard.build(
            listOf(
                task("q1", "queued"),
                task("q2", "requeued"),
                task("q3", "rework"),
                task("r1", "assigned"),
                task("r2", "working"),
                task("v1", "review"),
                task("b1", "blocked"),
                task("p1", "paused"),
                task("c1", "completed"),
                task("f1", "failed"),
                task("x1", "cancelled"),
            ),
        )
        assertEquals(11, board.totalCount)
        assertEquals(3, board.countOf(QueueGroup.QUEUED))
        assertEquals(2, board.countOf(QueueGroup.RUNNING))
        assertEquals(1, board.countOf(QueueGroup.REVIEW))
        assertEquals(1, board.countOf(QueueGroup.BLOCKED))
        assertEquals(1, board.countOf(QueueGroup.PAUSED))
        assertEquals(1, board.countOf(QueueGroup.COMPLETED))
        assertEquals(1, board.countOf(QueueGroup.FAILED))
        assertEquals(1, board.countOf(QueueGroup.CANCELLED))
        assertEquals(0, board.countOf(QueueGroup.OTHER))
        // 展示顺序：排队中在前，终态在后。
        assertEquals(
            listOf(
                QueueGroup.QUEUED, QueueGroup.RUNNING, QueueGroup.REVIEW,
                QueueGroup.BLOCKED, QueueGroup.PAUSED, QueueGroup.COMPLETED,
                QueueGroup.FAILED, QueueGroup.CANCELLED,
            ),
            board.groups.map { it.group },
        )
    }

    @Test
    fun `queued group is FIFO by creation time with 1-based positions`() {
        val board = QueueBoard.build(
            listOf(
                task("third", "queued", createdAt = 30),
                task("first", "queued", createdAt = 10),
                task("second", "requeued", createdAt = 20),
            ),
        )
        val queued = board.groups.single { it.group == QueueGroup.QUEUED }
        assertEquals(listOf("first", "second", "third"), queued.items.map { it.task.taskId })
        assertEquals(listOf(1, 2, 3), queued.items.map { it.queuePosition })
        assertEquals(3, board.queuedCount)
    }

    @Test
    fun `non-queued items have no queue position`() {
        val board = QueueBoard.build(
            listOf(
                task("w", "working", startedAt = 5),
                task("c", "completed", finishedAt = 9),
            ),
        )
        board.groups.forEach { group ->
            group.items.forEach { assertNull(it.queuePosition) }
        }
    }

    @Test
    fun `running group sorts by start time descending with updatedAt fallback`() {
        val board = QueueBoard.build(
            listOf(
                task("early", "working", startedAt = 50, updatedAt = 60),
                task("late", "working", startedAt = 100, updatedAt = 90),
                task("assignedOnly", "assigned", startedAt = null, updatedAt = 120),
            ),
        )
        val running = board.groups.single { it.group == QueueGroup.RUNNING }
        // assignedOnly 无 startedAt → 退 updatedAt=120 排最前；其余按 startedAt 倒序。
        assertEquals(
            listOf("assignedOnly", "late", "early"),
            running.items.map { it.task.taskId },
        )
        assertEquals(3, board.runningCount)
    }

    @Test
    fun `terminal groups sort by finish time descending`() {
        val board = QueueBoard.build(
            listOf(
                task("old", "completed", finishedAt = 10, updatedAt = 10),
                task("new", "completed", finishedAt = 99, updatedAt = 99),
                task("noFinish", "failed", finishedAt = null, updatedAt = 50),
                task("failedOld", "failed", finishedAt = 40, updatedAt = 40),
            ),
        )
        val completed = board.groups.single { it.group == QueueGroup.COMPLETED }
        assertEquals(listOf("new", "old"), completed.items.map { it.task.taskId })
        val failed = board.groups.single { it.group == QueueGroup.FAILED }
        // failed 组：noFinish 退 updatedAt=50 > failedOld 的 finishedAt=40。
        assertEquals(listOf("noFinish", "failedOld"), failed.items.map { it.task.taskId })
    }

    @Test
    fun `unknown states are kept in other group and never dropped`() {
        val board = QueueBoard.build(
            listOf(
                task("legacy", "waiting", updatedAt = 10),
                task("bogus", "some_future_state", updatedAt = 20),
                task("known", "queued", createdAt = 1),
            ),
        )
        assertEquals(3, board.totalCount)
        val other = board.groups.single { it.group == QueueGroup.OTHER }
        // 其他组按 updatedAt 倒序。
        assertEquals(listOf("bogus", "legacy"), other.items.map { it.task.taskId })
        // 其他组排在最后展示。
        assertEquals(QueueGroup.OTHER, board.groups.last().group)
    }

    @Test
    fun `groupOf matches documented mapping`() {
        assertEquals(QueueGroup.QUEUED, QueueBoard.groupOf("queued"))
        assertEquals(QueueGroup.QUEUED, QueueBoard.groupOf("requeued"))
        assertEquals(QueueGroup.QUEUED, QueueBoard.groupOf("rework"))
        assertEquals(QueueGroup.RUNNING, QueueBoard.groupOf("assigned"))
        assertEquals(QueueGroup.RUNNING, QueueBoard.groupOf("working"))
        assertEquals(QueueGroup.REVIEW, QueueBoard.groupOf("review"))
        assertEquals(QueueGroup.BLOCKED, QueueBoard.groupOf("blocked"))
        assertEquals(QueueGroup.PAUSED, QueueBoard.groupOf("paused"))
        assertEquals(QueueGroup.COMPLETED, QueueBoard.groupOf("completed"))
        assertEquals(QueueGroup.FAILED, QueueBoard.groupOf("failed"))
        assertEquals(QueueGroup.CANCELLED, QueueBoard.groupOf("cancelled"))
        assertEquals(QueueGroup.OTHER, QueueBoard.groupOf("waiting"))
        assertEquals(QueueGroup.OTHER, QueueBoard.groupOf(""))
    }
}
