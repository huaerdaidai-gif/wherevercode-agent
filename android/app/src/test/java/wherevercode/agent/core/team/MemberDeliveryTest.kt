package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.task.DispatchFakeTaskStore
import wherevercode.agent.core.task.TaskRecord

/**
 * 成员回传（V5 Phase B C3 第二阶段，成员框架），纯 JVM。
 *
 * 全程替身：内存任务库 + 内存成员库，不跑真实 CLI、不做 Hand
 * 连接。覆盖：交付记录 8 键往返、日志/diff/测试结果分开记、
 * 未连接拒绝、attempt 不匹配、成员不匹配、取消后拒绝新回传
 * 但旧记录可读、文件超限抛错、重复回传拒绝、状态回传
 * finishTask（完成经 review、失败直达、终态拒绝）。
 */
class MemberDeliveryTest {
    private val now = 1_700_000_000L

    private data class Env(
        val tasks: DispatchFakeTaskStore,
        val members: InMemoryTeamMemberStore,
        val manager: MemberManager,
        val receiver: MemberReturnReceiver,
    )

    private fun setup(): Env {
        val tasks = DispatchFakeTaskStore()
        val members = InMemoryTeamMemberStore()
        val manager = MemberManager(members, InMemoryAuditLog())
        val receiver = MemberReturnReceiver(tasks, members, nowSeconds = { now })
        return Env(tasks, members, manager, receiver)
    }

    private fun connectedMember(
        manager: MemberManager,
        memberId: String = "codex-mac",
        agentId: String = "codex-cli",
    ) {
        manager.addMember(
            TeamMember.remoteCliMember(
                memberId = memberId,
                agentId = agentId,
                displayName = "Mac 上的 Codex",
                transport = MemberTransport.LAN,
                endpointDeviceId = "mac-1",
                endpointLabel = "Mac",
                shapeStatus = MemberShapeStatus.VERIFIED,
            ),
        )
        manager.grantProject(memberId, "proj-a", "user")
        manager.setConnectionState(memberId, MemberConnectionState.CONNECTED)
    }

    /** 建一个已被成员认领的任务（assigned 态，attempt 1）。 */
    private fun assignedTask(
        tasks: DispatchFakeTaskStore,
        taskId: String,
        memberId: String = "codex-mac",
        attemptId: String = "att-1",
    ) {
        tasks.createTask(TaskRecord(taskId = taskId, projectId = "proj-a", title = "任务 $taskId"))
        tasks.updateTask(
            taskId,
            mapOf(
                "state" to "assigned",
                "assigned_agent_id" to memberId,
                "member_id" to memberId,
                "attempt" to 1,
                "attempt_id" to attemptId,
            ),
            1, 1, 1,
        )
    }

    private fun fullReturn(
        taskId: String = "t1",
        memberId: String = "codex-mac",
        attemptId: String = "att-1",
    ) = MemberTaskReturn(
        taskId = taskId,
        memberId = memberId,
        attemptId = attemptId,
        delivery = MemberDeliveryRecord(
            branch = "feat/x",
            commit = "abc1234",
            prUrl = "https://example.test/pr/1",
            totalPages = 3,
            pagesReceived = 3,
            truncated = false,
            files = listOf("a.kt", "b.kt"),
            summary = "改了两处",
        ),
        logs = listOf("start", "done"),
        diffSummary = "+10 -2",
        testVerdict = MemberTestVerdict.PASSED,
        testSummary = "12 passed",
    )

    @Test
    fun `happy path records delivery and keeps logs diff verdict separate`() {
        val env = setup()
        connectedMember(env.manager)
        assignedTask(env.tasks, "t1")
        val result = env.receiver.submitReturn(fullReturn())
        assertTrue(result is MemberReturnResult.Recorded)
        val entity = (result as MemberReturnResult.Recorded).record
        assertEquals("t1", entity.taskId)
        assertEquals("att-1", entity.attemptId)
        // 8 键往返。
        val delivery = env.receiver.deliveryRecordOf("t1", "att-1")!!
        assertEquals("feat/x", delivery.branch)
        assertEquals("abc1234", delivery.commit)
        assertEquals("https://example.test/pr/1", delivery.prUrl)
        assertEquals(3, delivery.totalPages)
        assertEquals(3, delivery.pagesReceived)
        assertEquals(false, delivery.truncated)
        assertEquals(listOf("a.kt", "b.kt"), delivery.files)
        assertEquals("改了两处", delivery.summary)
        // 日志/diff/测试 verdict 分开记，不在交付记录里。
        assertEquals(listOf("start", "done"), entity.logsOf())
        assertEquals("+10 -2", entity.diffSummary)
        assertEquals("PASSED", entity.testVerdict)
        assertEquals("12 passed", entity.testSummary)
        assertEquals(now, entity.recordedAtEpochSeconds)
    }

    @Test
    fun `unconnected member return is honestly refused`() {
        val env = setup()
        env.manager.addMember(
            TeamMember.remoteCliMember(
                memberId = "codex-mac",
                agentId = "codex-cli",
                displayName = "Mac 上的 Codex",
                transport = MemberTransport.LAN,
                shapeStatus = MemberShapeStatus.VERIFIED,
            ),
        )
        assignedTask(env.tasks, "t1")
        val result = env.receiver.submitReturn(fullReturn())
        assertTrue(result is MemberReturnResult.Refused)
        assertEquals(
            MemberReturnRefusal.MEMBER_NOT_CONNECTED,
            (result as MemberReturnResult.Refused).reason,
        )
        assertEquals(null, env.receiver.deliveryRecordOf("t1", "att-1"))
    }

    @Test
    fun `attempt mismatch refuses the late return of an old attempt`() {
        val env = setup()
        connectedMember(env.manager)
        assignedTask(env.tasks, "t1", attemptId = "att-1")
        val result = env.receiver.submitReturn(fullReturn(attemptId = "att-2"))
        assertTrue(result is MemberReturnResult.Refused)
        assertEquals(
            MemberReturnRefusal.ATTEMPT_MISMATCH,
            (result as MemberReturnResult.Refused).reason,
        )
    }

    @Test
    fun `member mismatch refuses a return from the wrong member`() {
        val env = setup()
        connectedMember(env.manager, "codex-mac", "codex-cli")
        connectedMember(env.manager, "codex-vps", "codex-cli")
        assignedTask(env.tasks, "t1", memberId = "codex-mac")
        val result = env.receiver.submitReturn(fullReturn(memberId = "codex-vps"))
        assertTrue(result is MemberReturnResult.Refused)
        assertEquals(
            MemberReturnRefusal.MEMBER_MISMATCH,
            (result as MemberReturnResult.Refused).reason,
        )
    }

    @Test
    fun `cancel then resubmit is refused terminal but the earlier record stays readable`() {
        val env = setup()
        connectedMember(env.manager)
        assignedTask(env.tasks, "t1")
        assertTrue(env.receiver.submitReturn(fullReturn()) is MemberReturnResult.Recorded)
        // 取消：已落盘的改动保留。
        val task = env.tasks.getTask("t1")!!
        env.tasks.updateTask("t1", mapOf("state" to "cancelled"), task.version, 1, 1)
        val again = env.receiver.submitReturn(fullReturn())
        assertTrue(again is MemberReturnResult.Refused)
        assertEquals(
            MemberReturnRefusal.TASK_TERMINAL,
            (again as MemberReturnResult.Refused).reason,
        )
        // 取消前已落盘的回传记录保留、可读。
        assertEquals("feat/x", env.receiver.deliveryRecordOf("t1", "att-1")?.branch)
        // 取消后状态回传同样拒绝。
        val finish = env.receiver.finishTask(
            "t1", "codex-mac", "att-1", MemberFinishState.COMPLETED,
        )
        assertTrue(finish is MemberFinishResult.Refused)
        assertEquals(
            MemberReturnRefusal.TASK_TERMINAL,
            (finish as MemberFinishResult.Refused).reason,
        )
    }

    @Test
    fun `files over 200 throws instead of silently truncating`() {
        val env = setup()
        connectedMember(env.manager)
        assignedTask(env.tasks, "t1")
        var failed = false
        try {
            env.receiver.submitReturn(
                fullReturn().copy(
                    delivery = MemberDeliveryRecord(files = (1..201).map { "f$it.kt" }),
                ),
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(null, env.receiver.deliveryRecordOf("t1", "att-1"))
    }

    @Test
    fun `duplicate submit for the same attempt returns the stored record without re-executing`() {
        val env = setup()
        connectedMember(env.manager)
        assignedTask(env.tasks, "t1")
        assertTrue(env.receiver.submitReturn(fullReturn()) is MemberReturnResult.Recorded)
        val again = env.receiver.submitReturn(fullReturn())
        assertTrue(again is MemberReturnResult.Duplicate)
        val duplicate = again as MemberReturnResult.Duplicate
        assertEquals("feat/x", duplicate.record.branch)
        assertEquals(listOf("start", "done"), duplicate.record.logsOf())
        // 只有一条记录落盘（重复投递不重执行、不重复写）。
        assertEquals("feat/x", env.receiver.deliveryRecordOf("t1", "att-1")?.branch)
        assertEquals(listOf("a.kt", "b.kt"), env.receiver.deliveryRecordOf("t1", "att-1")?.files)
    }

    @Test
    fun `finishTask completed walks assigned working review completed`() {
        val env = setup()
        connectedMember(env.manager)
        assignedTask(env.tasks, "t1")
        assertTrue(env.receiver.submitReturn(fullReturn()) is MemberReturnResult.Recorded)
        val finished = env.receiver.finishTask(
            "t1", "codex-mac", "att-1",
            MemberFinishState.COMPLETED, result = "done",
        )
        assertTrue(finished is MemberFinishResult.Finished)
        val task = (finished as MemberFinishResult.Finished).task
        assertEquals("completed", task.state)
        assertEquals("done", task.result)
        assertEquals("completed", env.tasks.getTask("t1")?.state)
        // 终态后再推同一 attempt 的终态：返回已存的完成任务（第一次
        // 写赢），不重执行、不改终态、不报终态拒绝。
        val again = env.receiver.finishTask(
            "t1", "codex-mac", "att-1", MemberFinishState.FAILED,
        )
        assertTrue(again is MemberFinishResult.Finished)
        val againTask = (again as MemberFinishResult.Finished).task
        assertEquals("completed", againTask.state)
        assertEquals("done", againTask.result)
    }

    @Test
    fun `finishTask failed goes straight from working with the error`() {
        val env = setup()
        connectedMember(env.manager)
        assignedTask(env.tasks, "t1")
        val task = env.tasks.getTask("t1")!!
        env.tasks.updateTask("t1", mapOf("state" to "working"), task.version, 1, 1)
        val finished = env.receiver.finishTask(
            "t1", "codex-mac", "att-1",
            MemberFinishState.FAILED, error = "tests failed",
        )
        assertTrue(finished is MemberFinishResult.Finished)
        assertEquals("failed", (finished as MemberFinishResult.Finished).task.state)
        assertEquals("tests failed", finished.task.error)
    }

    @Test
    fun `finishTask attempt mismatch is refused`() {
        val env = setup()
        connectedMember(env.manager)
        assignedTask(env.tasks, "t1", attemptId = "att-1")
        val finished = env.receiver.finishTask(
            "t1", "codex-mac", "att-2", MemberFinishState.COMPLETED,
        )
        assertTrue(finished is MemberFinishResult.Refused)
        assertEquals(
            MemberReturnRefusal.ATTEMPT_MISMATCH,
            (finished as MemberFinishResult.Refused).reason,
        )
        assertEquals("assigned", env.tasks.getTask("t1")?.state)
    }
}
