package wherevercode.agent.core.task

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery

/**
 * LocalTaskRunner 指派过滤（阶段 3，A1 P1 / B 线第 1 步）：
 * 已指派给别的 Agent 的任务本地不代执行，保持 queued 等该 Agent
 * 认领；只有未指派、或指派给本地执行器（"local"）的任务才认领。
 *
 * Robolectric：与 DispatchClaimExecuteTest 同因——TaskScheduler
 * 需要真实 AgentDiscovery（要 Context）；认领路径本身不调 discovery。
 */
@RunWith(RobolectricTestRunner::class)
class LocalTaskRunnerAssignTest {

    private class OkExecutor : TaskExecutor {
        override suspend fun execute(
            task: TaskRecord,
            onStep: (TaskStepEvent) -> Unit,
        ): TaskExecutionResult {
            onStep(TaskStepEvent.Started("call-write", "write_file", "path=x.py"))
            onStep(TaskStepEvent.Finished("call-write", true, "已写入 x.py"))
            return TaskExecutionResult(success = true, summary = "完成")
        }
    }

    private lateinit var taskStore: DispatchFakeTaskStore
    private lateinit var scheduler: TaskScheduler
    private lateinit var runner: LocalTaskRunner

    @Before
    fun setUp() {
        taskStore = DispatchFakeTaskStore()
        val discovery = AgentDiscovery(RuntimeEnvironment.getApplication())
        scheduler = TaskScheduler(taskStore, discovery, null)
        runner = LocalTaskRunner(
            taskStore,
            scheduler,
            InMemoryTaskStepStore(),
            OkExecutor(),
            pollIntervalMs = 60_000,
            leaseRenewIntervalMs = 60_000,
        )
    }

    @After
    fun tearDown() {
        runner.stop()
    }

    private fun queued(
        id: String,
        assignedAgentId: String? = null,
        createdAt: Long = 1_700_000_000L,
    ) = taskStore.createTask(
        TaskRecord(
            taskId = id,
            title = "任务 $id",
            description = "任务 $id",
            state = "queued",
            assignedAgentId = assignedAgentId,
            createdAt = createdAt,
        ),
    )

    @Test
    fun `task assigned to another agent stays queued`() = runBlocking {
        queued("other-1", assignedAgentId = "codex")
        runner.pumpOnce()
        runner.pumpOnce()
        val task = taskStore.getTask("other-1")
        assertEquals("queued", task?.state)
        assertEquals("codex", task?.assignedAgentId)
        assertNull("未被认领，故无租约", task?.leaseOwner)
    }

    @Test
    fun `task assigned to local is claimed and executed`() = runBlocking {
        queued("local-1", assignedAgentId = LocalTaskRunner.LOCAL_AGENT_ID)
        runner.pumpOnce()
        val task = taskStore.getTask("local-1")
        assertEquals("completed", task?.state)
        assertEquals(LocalTaskRunner.LOCAL_AGENT_ID, task?.assignedAgentId)
        assertEquals("完成", task?.result)
    }

    @Test
    fun `unassigned task runs while another-agent task waits`() = runBlocking {
        // 别的 Agent 的任务创建时间更早：pickNext 必须跳过它，而不是卡住。
        queued("other-1", assignedAgentId = "codex", createdAt = 1_700_000_000L)
        queued("mine-1", assignedAgentId = null, createdAt = 1_700_000_001L)
        runner.pumpOnce()
        assertEquals("completed", taskStore.getTask("mine-1")?.state)
        assertEquals("queued", taskStore.getTask("other-1")?.state)
        assertEquals("codex", taskStore.getTask("other-1")?.assignedAgentId)
    }
}
