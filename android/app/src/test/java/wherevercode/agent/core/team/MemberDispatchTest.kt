package wherevercode.agent.core.team

import wherevercode.agent.core.task.testTaskScheduler
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.autonomy.AutonomyMode
import wherevercode.agent.core.autonomy.AutonomyStore
import wherevercode.agent.core.autonomy.DefaultAutonomyGate
import wherevercode.agent.core.control.InMemoryControlAuditLog
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler

/**
 * 成员分派（V5 Phase B C3 第二阶段，成员框架），Robolectric。
 *
 * 全程替身：Room 任务库 + 内存成员库 + 真实 TaskScheduler（不跑
 * 真实 CLI、不做 Hand 连接）。覆盖：未连接拒绝、授权拒绝、认领
 * 租约 600 秒 + attempt 绑定、一次一任务、暂停拒绝、租约过期回队
 * （requeued）后 attempt 保留、重认领 attempt+1。
 */
@RunWith(RobolectricTestRunner::class)
class MemberDispatchTest {
    private var db: TaskDatabase? = null
    private val now = 1_700_000_000L

    @After
    fun tearDown() {
        db?.close()
    }

    private data class Env(
        val taskStore: RoomTaskStore,
        val memberStore: InMemoryTeamMemberStore,
        val manager: MemberManager,
        val dispatcher: MemberDispatcher,
        val control: MemberTaskControl,
        val scheduler: TaskScheduler,
    )

    private fun setup(): Env {
        val context = RuntimeEnvironment.getApplication()
        val database = TaskDatabase.inMemory(context)
        db = database
        val taskStore = RoomTaskStore(database)
        val scheduler = testTaskScheduler(taskStore, AgentDiscovery(context))
        val memberStore = InMemoryTeamMemberStore()
        val manager = MemberManager(memberStore, InMemoryAuditLog(), nowSeconds = { now })
        val dispatcher = MemberDispatcher(taskStore, memberStore, scheduler, nowSeconds = { now })
        val control = MemberTaskControl(taskStore, memberStore, nowSeconds = { now })
        return Env(taskStore, memberStore, manager, dispatcher, control, scheduler)
    }

    private fun connectedMember(manager: MemberManager): TeamMember {
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
        return manager.setConnectionState("codex-mac", MemberConnectionState.CONNECTED)
    }

    private fun queuedTask(taskStore: RoomTaskStore, taskId: String, projectId: String = "proj-a") {
        taskStore.createTask(
            TaskRecord(taskId = taskId, projectId = projectId, title = "任务 $taskId"),
        )
    }

    @Test
    fun `unconnected member dispatch is honestly refused`() {
        val (taskStore, _, manager, dispatcher, _) = setup()
        manager.addMember(
            TeamMember.remoteCliMember(
                memberId = "codex-mac",
                agentId = "codex-cli",
                displayName = "Mac 上的 Codex",
                transport = MemberTransport.LAN,
                endpointLabel = "Mac",
                shapeStatus = MemberShapeStatus.VERIFIED,
            ),
        )
        manager.grantProject("codex-mac", "proj-a", "user")
        queuedTask(taskStore, "t1")
        val result = dispatcher.dispatch("t1", "codex-mac")
        assertTrue(result is MemberDispatchResult.Refused)
        val refused = result as MemberDispatchResult.Refused
        assertEquals(MemberDispatchRefusal.MEMBER_NOT_CONNECTED, refused.reason)
        assertTrue(refused.detail.contains("未连接（等待安排）"))
        assertEquals("queued", taskStore.getTask("t1")?.state)
    }

    @Test
    fun `connected authorized dispatch claims with 600s lease and attempt binding`() {
        val (taskStore, _, manager, dispatcher, _) = setup()
        connectedMember(manager)
        queuedTask(taskStore, "t1")
        val result = dispatcher.dispatch("t1", "codex-mac")
        assertTrue(result is MemberDispatchResult.Assigned)
        val assigned = result as MemberDispatchResult.Assigned
        assertEquals("t1", assigned.assignment.taskId)
        assertEquals("codex-mac", assigned.assignment.memberId)
        assertEquals("proj-a", assigned.assignment.projectId)
        assertEquals(1, assigned.assignment.attempt)
        assertEquals(32, assigned.assignment.attemptId.length)
        assertEquals(now + RoomTaskStore.LEASE_SECONDS, assigned.assignment.leaseExpiresAt)
        val task = taskStore.getTask("t1")!!
        assertEquals("assigned", task.state)
        assertEquals("codex-mac", task.memberId)
        assertEquals(1, task.attempt)
        assertEquals(assigned.assignment.attemptId, task.attemptId)
        assertEquals("codex-mac", task.leaseOwner)
        assertEquals(now + RoomTaskStore.LEASE_SECONDS, task.leaseExpiresAt)
    }

    @Test
    fun `dispatch without project authorization is refused`() {
        val (taskStore, _, manager, dispatcher, _) = setup()
        connectedMember(manager)
        queuedTask(taskStore, "t1", projectId = "proj-b")
        val result = dispatcher.dispatch("t1", "codex-mac")
        assertTrue(result is MemberDispatchResult.Refused)
        assertEquals(
            MemberDispatchRefusal.PROJECT_NOT_AUTHORIZED,
            (result as MemberDispatchResult.Refused).reason,
        )
        assertEquals("queued", taskStore.getTask("t1")?.state)
    }

    @Test
    fun `quota exhausted dispatch is refused explicitly never silently paid`() {
        val env = setup()
        connectedMember(env.manager)
        queuedTask(env.taskStore, "t1")
        env.manager.updateQuotaState("codex-mac", MemberQuotaState.FREE_EXHAUSTED)
        val result = env.dispatcher.dispatch("t1", "codex-mac")
        assertTrue(result is MemberDispatchResult.Refused)
        assertEquals(
            MemberDispatchRefusal.QUOTA_EXHAUSTED,
            (result as MemberDispatchResult.Refused).reason,
        )
        assertEquals("queued", env.taskStore.getTask("t1")?.state)
    }

    @Test
    fun `one member holds one task at a time`() {
        val (taskStore, _, manager, dispatcher, _) = setup()
        connectedMember(manager)
        queuedTask(taskStore, "t1")
        queuedTask(taskStore, "t2")
        assertTrue(dispatcher.dispatch("t1", "codex-mac") is MemberDispatchResult.Assigned)
        val second = dispatcher.dispatch("t2", "codex-mac")
        assertTrue(second is MemberDispatchResult.Refused)
        assertEquals(
            MemberDispatchRefusal.MEMBER_BUSY,
            (second as MemberDispatchResult.Refused).reason,
        )
        assertEquals("queued", taskStore.getTask("t2")?.state)
    }

    @Test
    fun `paused task dispatch is refused with a visible reason`() {
        val env = setup()
        val (taskStore, _, manager, dispatcher, control) = env
        connectedMember(manager)
        queuedTask(taskStore, "t1")
        assertTrue(control.pauseTask("t1"))
        val result = dispatcher.dispatch("t1", "codex-mac")
        assertTrue(result is MemberDispatchResult.Refused)
        assertEquals(
            MemberDispatchRefusal.TASK_PAUSED,
            (result as MemberDispatchResult.Refused).reason,
        )
        assertEquals("queued", taskStore.getTask("t1")?.state)
        // 恢复后可正常分派。
        assertTrue(control.resumeTask("t1"))
        assertTrue(dispatcher.dispatch("t1", "codex-mac") is MemberDispatchResult.Assigned)
    }

    @Test
    fun `member dispatcher cannot route MCP task to a same named remote agent`() {
        val (taskStore, _, manager, dispatcher, _) = setup()
        connectedMember(manager)
        taskStore.createTask(
            TaskRecord(
                taskId = "mcp-codex-cli-collision", projectId = "proj-a", title = "MCP task",
                state = "queued", assignedAgentId = "codex-cli",
            ),
        )

        val result = dispatcher.dispatch("mcp-codex-cli-collision", "codex-mac")

        assertTrue(result is MemberDispatchResult.Refused)
        assertEquals("queued", taskStore.getTask("mcp-codex-cli-collision")?.state)
    }

    @Test
    fun `lease expiry returns task to requeued and keeps attempt for re-claim`() {
        val (taskStore, _, manager, dispatcher, _) = setup()
        connectedMember(manager)
        queuedTask(taskStore, "t1")
        val first = dispatcher.dispatch("t1", "codex-mac") as MemberDispatchResult.Assigned
        assertEquals(1, first.assignment.attempt)
        val expired = taskStore.expireLeases(now + RoomTaskStore.LEASE_SECONDS + 1)
        assertEquals(listOf("t1"), expired)
        val back = taskStore.getTask("t1")!!
        assertEquals("requeued", back.state)
        assertEquals(null, back.memberId)
        assertEquals(null, back.attemptId)
        assertEquals(1, back.attempt)
        assertEquals(true, back.needsAttention)
        val second = dispatcher.dispatch("t1", "codex-mac") as MemberDispatchResult.Assigned
        assertEquals(2, second.assignment.attempt)
    }

    @Test
    fun `dispatch of a non-queued task is refused`() {
        val (taskStore, _, manager, dispatcher, _) = setup()
        connectedMember(manager)
        queuedTask(taskStore, "t1")
        assertTrue(dispatcher.dispatch("t1", "codex-mac") is MemberDispatchResult.Assigned)
        val again = dispatcher.dispatch("t1", "codex-mac")
        assertTrue(again is MemberDispatchResult.Refused)
        assertEquals(
            MemberDispatchRefusal.MEMBER_BUSY,
            (again as MemberDispatchResult.Refused).reason,
        )
    }

    // -- 自主度门（2026-11-11，maintainer 22:43 两种模式） -------------------------

    private fun gatedDispatcher(
        taskStore: RoomTaskStore,
        memberStore: InMemoryTeamMemberStore,
        scheduler: TaskScheduler,
        mode: AutonomyMode?,
    ): Pair<MemberDispatcher, InMemoryControlAuditLog> {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("test_autonomy_dispatch", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val store = AutonomyStore(context, "test_autonomy_dispatch")
        if (mode != null) store.applyMode(mode)
        val log = InMemoryControlAuditLog()
        val gate = DefaultAutonomyGate(store, log, nowEpochSeconds = { now })
        val dispatcher = MemberDispatcher(
            taskStore, memberStore, scheduler,
            nowSeconds = { now }, autonomyGate = gate,
        )
        return dispatcher to log
    }

    @Test
    fun `supervision mode dispatch waits for user confirmation and stays queued`() {
        val (taskStore, memberStore, manager, _, _, scheduler) = setup()
        connectedMember(manager)
        queuedTask(taskStore, "t1")
        val (gated, log) = gatedDispatcher(taskStore, memberStore, scheduler, null)
        val result = gated.dispatch("t1", "codex-mac")
        assertTrue(result is MemberDispatchResult.Refused)
        val refused = result as MemberDispatchResult.Refused
        assertEquals(MemberDispatchRefusal.NEEDS_USER_CONFIRMATION, refused.reason)
        assertTrue(refused.detail.contains("需用户确认"))
        assertEquals("queued", taskStore.getTask("t1")?.state)
        assertTrue(log.listAll().isEmpty())
    }

    @Test
    fun `full auto dispatch claims and records the auto decision`() {
        val (taskStore, memberStore, manager, _, _, scheduler) = setup()
        connectedMember(manager)
        queuedTask(taskStore, "t1")
        val (gated, log) = gatedDispatcher(taskStore, memberStore, scheduler, AutonomyMode.FULL_AUTO)
        val result = gated.dispatch("t1", "codex-mac")
        assertTrue(result is MemberDispatchResult.Assigned)
        val entries = log.listForTask("t1")
        assertEquals(1, entries.size)
        assertEquals("auto_dispatch", entries[0].action)
        assertEquals(DefaultAutonomyGate.ACTOR, entries[0].actor)
        assertTrue(entries[0].reason?.contains("高自主") == true)
    }

}
