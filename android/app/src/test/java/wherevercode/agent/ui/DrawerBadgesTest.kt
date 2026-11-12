package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import wherevercode.agent.core.task.TaskRecord

/**
 * 抽屉角标纯逻辑测试（V5 C2-3-R2；R4 改：0 显示“0”，只有未知不显示）：
 * 运行中只算 assigned / working；待审批只有 0 或 1；0 与未知不再同等对待。
 */
class DrawerBadgesTest {

    private fun task(state: String) = TaskRecord(taskId = "t-$state", state = state)

    @Test
    fun `running counts only assigned and working`() {
        val tasks = listOf(
            task("assigned"), task("working"), task("working"),
            task("queued"), task("done"), task("failed"), task("reviewing"),
        )
        assertEquals(3, DrawerBadges.runningCount(tasks))
    }

    @Test
    fun `running count is zero when nothing is assigned or working`() {
        assertEquals(0, DrawerBadges.runningCount(listOf(task("queued"), task("done"))))
        assertEquals(0, DrawerBadges.runningCount(emptyList()))
    }

    @Test
    fun `pending count is one only while an approval waits`() {
        assertEquals(1, DrawerBadges.pendingCount(true))
        assertEquals(0, DrawerBadges.pendingCount(false))
    }

    @Test
    fun `badge shows zero and hides only on unknown`() {
        assertEquals(0, DrawerBadges(runningTasks = 0).badgeFor("nav_running_tasks"))
        assertNull(DrawerBadges().badgeFor("nav_running_tasks"))
        assertEquals(0, DrawerBadges(pendingApprovals = 0).badgeFor("nav_pending_approval"))
        assertNull(DrawerBadges().badgeFor("nav_pending_approval"))
        assertEquals(3, DrawerBadges(runningTasks = 3).badgeFor("nav_running_tasks"))
        assertEquals(1, DrawerBadges(pendingApprovals = 1).badgeFor("nav_pending_approval"))
    }

    @Test
    fun `running and pending badges are independent`() {
        val badges = DrawerBadges(runningTasks = 0, pendingApprovals = 5)
        assertEquals(0, badges.badgeFor("nav_running_tasks"))
        assertEquals(5, badges.badgeFor("nav_pending_approval"))
        val unknownRunning = DrawerBadges(pendingApprovals = 2)
        assertNull(unknownRunning.badgeFor("nav_running_tasks"))
        assertEquals(2, unknownRunning.badgeFor("nav_pending_approval"))
    }

    @Test
    fun `badge only applies to the two badged rows`() {
        val badges = DrawerBadges(runningTasks = 3, pendingApprovals = 1)
        assertNull(badges.badgeFor("nav_chat"))
        assertNull(badges.badgeFor("nav_theme"))
        assertNull(badges.badgeFor("nav_console"))
    }
}
