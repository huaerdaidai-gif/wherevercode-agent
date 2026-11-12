package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.task.DispatchFakeTaskStore
import wherevercode.agent.core.task.TaskRecord

/**
 * 成员任务控制（V5 Phase B C3 第二阶段，成员框架），纯 JVM。
 *
 * 全程替身：内存任务库 + 内存成员库 + TurnController（真实逻辑，
 * 不跑真实 CLI）。覆盖：暂停/恢复（暂停是独立布尔字段、不是
 * 状态）、停止（终态、二次停止与停后暂停拒绝）、改目标（存
 * pendingGoal、状态不动、二次改目标拒绝、改目标后仍可停止）、
 * canonical 态 → 执行层 5 态全映射、取消后已落盘改动保留。
 */
class MemberTaskControlTest {
    private val now = 1_700_000_000L

    private data class Env(
        val tasks: DispatchFakeTaskStore,
        val members: InMemoryTeamMemberStore,
        val control: MemberTaskControl,
        val receiver: MemberReturnReceiver,
    )

    private fun setup(): Env {
        val tasks = DispatchFakeTaskStore()
        val members = InMemoryTeamMemberStore()
        val control = MemberTaskControl(tasks, members, nowSeconds = { now })
        val receiver = MemberReturnReceiver(tasks, members, nowSeconds = { now })
        return Env(tasks, members, control, receiver)
    }

    /** 把任务推到指定 canonical 态（经合法跃迁）。 */
    private fun taskInState(tasks: DispatchFakeTaskStore, taskId: String, target: String) {
        tasks.createTask(TaskRecord(taskId = taskId, projectId = "proj-a", title = "任务 $taskId"))
        val path = when (target) {
            "queued" -> emptyList()
            "assigned" -> listOf("assigned")
            "working" -> listOf("assigned", "working")
            "blocked" -> listOf("assigned", "working", "blocked")
            "review" -> listOf("assigned", "working", "review")
            "completed" -> listOf("assigned", "working", "review", "completed")
            "failed" -> listOf("assigned", "working", "failed")
            "cancelled" -> listOf("assigned", "cancelled")
            else -> throw IllegalArgumentException("未知目标态：$target")
        }
        for (state in path) {
            val current = tasks.getTask(taskId)!!
            tasks.updateTask(taskId, mapOf("state" to state), current.version, 1, 1)
        }
    }

    /** 带成员认领的工作中任务（供回传/取消测试用）。 */
    private fun workingMemberTask(env: Env, taskId: String) {
        val manager = MemberManager(env.members, InMemoryAuditLog())
        manager.addMember(
            TeamMember.remoteCliMember(
                memberId = "codex-mac",
                agentId = "codex-cli",
                displayName = "Mac 上的 Codex",
                transport = MemberTransport.LAN,
                endpointDeviceId = "mac-1",
                endpointLabel = "Mac",
                shapeStatus = MemberShapeStatus.VERIFIED,
            ),
        )
        manager.grantProject("codex-mac", "proj-a", "user")
        manager.setConnectionState("codex-mac", MemberConnectionState.CONNECTED)
        env.tasks.createTask(TaskRecord(taskId = taskId, projectId = "proj-a", title = "任务 $taskId"))
        var current = env.tasks.getTask(taskId)!!
        env.tasks.updateTask(
            taskId,
            mapOf(
                "state" to "assigned",
                "assigned_agent_id" to "codex-mac",
                "member_id" to "codex-mac",
                "attempt" to 1,
                "attempt_id" to "att-1",
            ),
            current.version, 1, 1,
        )
        current = env.tasks.getTask(taskId)!!
        env.tasks.updateTask(taskId, mapOf("state" to "working"), current.version, 1, 1)
    }

    @Test
    fun `pause and resume keep the task RUNNING because pause is a boolean not a state`() {
        val env = setup()
        taskInState(env.tasks, "t1", "working")
        assertTrue(env.control.pauseTask("t1"))
        assertTrue(env.control.isPaused("t1"))
        // 暂停 ≠ 状态：执行层仍是 RUNNING。
        assertEquals(MemberExecutionState.RUNNING, env.control.executionState("t1"))
        assertEquals("working", env.tasks.getTask("t1")?.state)
        assertTrue(env.control.resumeTask("t1"))
        assertFalse(env.control.isPaused("t1"))
        // 恢复后还能再暂停。
        assertTrue(env.control.pauseTask("t1"))
        assertTrue(env.control.isPaused("t1"))
    }

    @Test
    fun `stop cancels the task and a second stop or pause after stop is refused`() {
        val env = setup()
        taskInState(env.tasks, "t1", "working")
        assertTrue(env.control.stopTask("t1"))
        assertEquals("cancelled", env.tasks.getTask("t1")?.state)
        assertEquals(MemberExecutionState.CANCELLED, env.control.executionState("t1"))
        assertFalse(env.control.stopTask("t1"))
        assertFalse(env.control.pauseTask("t1"))
        assertFalse(env.control.resumeTask("t1"))
        // 控制行保留（paused=false），不是删掉。
        assertFalse(env.control.isPaused("t1"))
    }

    @Test
    fun `changeGoal stores pendingGoal keeps state and allows stop afterwards`() {
        val env = setup()
        taskInState(env.tasks, "t1", "working")
        assertTrue(env.control.changeGoal("t1", "改成做 B"))
        assertEquals("改成做 B", env.members.getControl("t1")?.pendingGoal)
        // 任务 state 本身不动。
        assertEquals("working", env.tasks.getTask("t1")?.state)
        assertEquals(MemberExecutionState.RUNNING, env.control.executionState("t1"))
        // 二次改目标拒绝（已改过目标的轮次）。
        assertFalse(env.control.changeGoal("t1", "再改成 C"))
        assertEquals("改成做 B", env.members.getControl("t1")?.pendingGoal)
        // 改目标后仍可停止。
        assertTrue(env.control.stopTask("t1"))
        assertEquals("cancelled", env.tasks.getTask("t1")?.state)
    }

    @Test
    fun `changeGoal after stop is refused and pause after goal change is refused`() {
        val env = setup()
        taskInState(env.tasks, "t1", "working")
        assertTrue(env.control.stopTask("t1"))
        assertFalse(env.control.changeGoal("t1", "改成做 B"))

        val env2 = setup()
        taskInState(env2.tasks, "t2", "working")
        assertTrue(env2.control.changeGoal("t2", "改成做 B"))
        assertFalse(env2.control.pauseTask("t2"))
    }

    @Test
    fun `executionStateOf maps every canonical state to the five execution states`() {
        val env = setup()
        val cases = mapOf(
            "queued" to MemberExecutionState.QUEUED,
            "assigned" to MemberExecutionState.QUEUED,
            "working" to MemberExecutionState.RUNNING,
            "blocked" to MemberExecutionState.RUNNING,
            "review" to MemberExecutionState.RUNNING,
            "completed" to MemberExecutionState.COMPLETED,
            "failed" to MemberExecutionState.FAILED,
            "cancelled" to MemberExecutionState.CANCELLED,
        )
        for ((state, expected) in cases) {
            taskInState(env.tasks, "t-$state", state)
            assertEquals(
                "canonical $state 应映射为 $expected",
                expected,
                env.control.executionState("t-$state"),
            )
        }
        // 终态任务不能暂停。
        assertFalse(env.control.pauseTask("t-completed"))
        assertFalse(env.control.pauseTask("t-failed"))
        assertFalse(env.control.pauseTask("t-cancelled"))
    }

    @Test
    fun `cancel keeps landed changes and blocks new artifacts`() {
        val env = setup()
        workingMemberTask(env, "t1")
        assertTrue(
            env.receiver.submitReturn(
                MemberTaskReturn(
                    taskId = "t1",
                    memberId = "codex-mac",
                    attemptId = "att-1",
                    delivery = MemberDeliveryRecord(branch = "feat/x", commit = "abc1234"),
                    logs = listOf("done"),
                    testVerdict = MemberTestVerdict.PASSED,
                ),
            ) is MemberReturnResult.Recorded,
        )
        assertTrue(env.control.stopTask("t1"))
        assertEquals("cancelled", env.tasks.getTask("t1")?.state)
        // 取消后新回传拒绝。
        val again = env.receiver.submitReturn(
            MemberTaskReturn(
                taskId = "t1",
                memberId = "codex-mac",
                attemptId = "att-1",
                delivery = MemberDeliveryRecord(branch = "feat/y"),
            ),
        )
        assertTrue(again is MemberReturnResult.Refused)
        assertEquals(
            MemberReturnRefusal.TASK_TERMINAL,
            (again as MemberReturnResult.Refused).reason,
        )
        // 取消前已落盘的回传与改动保留、可读。
        assertEquals("feat/x", env.receiver.deliveryRecordOf("t1", "att-1")?.branch)
    }
}
