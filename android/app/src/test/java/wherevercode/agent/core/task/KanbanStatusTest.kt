package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主流 Kanban 映射（2026-11-11，主流补齐）：
 * V5 任务状态机（queued/assigned/working/review/blocked/completed/
 * failed/cancelled，见 TaskStates.kt）映射到主流看板列
 * Todo → In Progress → In Review → Done（+ Blocked，返工 = 反向流转）。
 * 状态机本身不动（内部状态名不变）；Kanban 列只是呈现层的映射。
 */
class KanbanStatusTest {

    @Test
    fun `queued maps to todo`() {
        assertEquals(KanbanColumn.TODO, KanbanStatus.columnOf("queued"))
    }

    @Test
    fun `assigned and working map to in progress`() {
        assertEquals(KanbanColumn.IN_PROGRESS, KanbanStatus.columnOf("assigned"))
        assertEquals(KanbanColumn.IN_PROGRESS, KanbanStatus.columnOf("working"))
    }

    @Test
    fun `review maps to in review`() {
        assertEquals(KanbanColumn.IN_REVIEW, KanbanStatus.columnOf("review"))
    }

    @Test
    fun `completed maps to done`() {
        assertEquals(KanbanColumn.DONE, KanbanStatus.columnOf("completed"))
    }

    @Test
    fun `blocked maps to blocked column`() {
        assertEquals(KanbanColumn.BLOCKED, KanbanStatus.columnOf("blocked"))
    }

    @Test
    fun `failed and cancelled are terminal and have no kanban column`() {
        assertNull(KanbanStatus.columnOf("failed"))
        assertNull(KanbanStatus.columnOf("cancelled"))
        assertNull(KanbanStatus.columnOf("bogus-state"))
    }

    @Test
    fun `labels cover every spec state`() {
        // 规范 8 状态（TaskStates.kt）都有中文显示，不掉进“处理中”。
        assertEquals("待办", KanbanStatus.labelOf("queued"))
        assertEquals("已接收", KanbanStatus.labelOf("assigned"))
        assertEquals("进行中", KanbanStatus.labelOf("working"))
        assertEquals("待审核", KanbanStatus.labelOf("review"))
        assertEquals("返工", KanbanStatus.labelOf("rework"))
        assertEquals("已回队", KanbanStatus.labelOf("requeued"))
        assertEquals("已阻塞", KanbanStatus.labelOf("blocked"))
        assertEquals("已完成", KanbanStatus.labelOf("completed"))
        assertEquals("失败", KanbanStatus.labelOf("failed"))
        assertEquals("已停止", KanbanStatus.labelOf("cancelled"))
        assertEquals("已暂停", KanbanStatus.labelOf("paused"))
    }

    @Test
    fun `column labels are the kanban names`() {
        assertEquals("待办", KanbanColumn.TODO.label)
        assertEquals("进行中", KanbanColumn.IN_PROGRESS.label)
        assertEquals("待审核", KanbanColumn.IN_REVIEW.label)
        assertEquals("已完成", KanbanColumn.DONE.label)
        assertEquals("已阻塞", KanbanColumn.BLOCKED.label)
    }

    @Test
    fun `rework is review flowing back to working`() {
        // P0：返工是显式态。review → rework → assigned → working；
        // review → working 直边已删，不再算返工。
        assertTrue(KanbanStatus.isRework("review", "rework"))
        assertTrue(KanbanStatus.isRework("rework", "working"))
        assertFalse(KanbanStatus.isRework("review", "working"))
        assertFalse(KanbanStatus.isRework("review", "assigned"))
        assertFalse(KanbanStatus.isRework("working", "review"))
        assertFalse(KanbanStatus.isRework("queued", "working"))
    }

    @Test
    fun `requeued maps to todo and rework maps to in review`() {
        assertEquals(KanbanColumn.TODO, KanbanStatus.columnOf("requeued"))
        assertEquals(KanbanColumn.IN_REVIEW, KanbanStatus.columnOf("rework"))
        assertEquals(KanbanColumn.TODO, TaskRecord(taskId = "t", state = "requeued").kanbanColumn)
        assertEquals(KanbanColumn.IN_REVIEW, TaskRecord(taskId = "t", state = "rework").kanbanColumn)
    }
}
