package wherevercode.agent.core.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery

/**
 * Dispatch minimal chain (phase 1, U3): queued -> claimed -> running ->
 * done, through the real [TaskScheduler] + [LocalTaskRunner] with a fake
 * [TaskStore] and a fake executor.
 *
 * Robolectric only because [TaskScheduler] needs a real [AgentDiscovery]
 * (it takes a Context); the claim path never calls discovery, only
 * matchAgent does, and no test here does.
 */
@RunWith(RobolectricTestRunner::class)
class DispatchClaimExecuteTest {

    private class FakeExecutor(
        private val result: TaskExecutionResult,
        private val scriptedSteps: List<Pair<String, String>> = listOf(
            "write_file" to "已写入 calc.py",
            "run_command" to "2 passed",
        ),
    ) : TaskExecutor {
        override suspend fun execute(
            task: TaskRecord,
            onStep: (TaskStepEvent) -> Unit,
        ): TaskExecutionResult {
            for ((tool, summary) in scriptedSteps) {
                onStep(TaskStepEvent.Started("call-$tool", tool, "path=calc.py"))
                onStep(TaskStepEvent.Finished("call-$tool", true, summary))
            }
            return result
        }
    }

    private class HangingExecutor : TaskExecutor {
        override suspend fun execute(
            task: TaskRecord,
            onStep: (TaskStepEvent) -> Unit,
        ): TaskExecutionResult {
            delay(60_000)
            return TaskExecutionResult(success = true, summary = "不应到达")
        }
    }

    private lateinit var taskStore: DispatchFakeTaskStore
    private lateinit var stepStore: InMemoryTaskStepStore
    private lateinit var scheduler: TaskScheduler
    private lateinit var runner: LocalTaskRunner

    @Before
    fun setUp() {
        taskStore = DispatchFakeTaskStore()
        stepStore = InMemoryTaskStepStore()
        val discovery = AgentDiscovery(RuntimeEnvironment.getApplication())
        scheduler = TaskScheduler(taskStore, discovery, null)
        runner = LocalTaskRunner(
            taskStore,
            scheduler,
            stepStore,
            FakeExecutor(TaskExecutionResult(success = true, summary = "calculator.py 已完成，测试 2 passed")),
            pollIntervalMs = 60_000,
            leaseRenewIntervalMs = 60_000,
        )
    }

    @After
    fun tearDown() {
        runner.stop()
    }

    private fun queuedDispatched(id: String, goal: String = "写 calculator.py") =
        taskStore.createTask(
            TaskRecord(taskId = id, title = goal, description = goal, state = "queued"),
        )

    @Test
    fun `queued task is claimed and executed to completed`() = runBlocking {
        queuedDispatched("t1")
        runner.pumpOnce()
        val task = taskStore.getTask("t1")
        assertNotNull(task)
        assertEquals("completed", task?.state)
        assertEquals(LocalTaskRunner.LOCAL_AGENT_ID, task?.assignedAgentId)
        assertEquals("calculator.py 已完成，测试 2 passed", task?.result)
        assertNotNull("完成态带 finishedAt", task?.finishedAt)
        val steps = stepStore.listSteps("t1")
        assertEquals(2, steps.size)
        assertEquals(listOf("write_file", "run_command"), steps.map { it.toolName })
        assertTrue("步骤全部成功", steps.all { it.status == TaskStepStatus.SUCCESS })
        assertEquals("已写入 calc.py", steps[0].resultSummary)
    }

    @Test
    fun `failing executor marks the task failed with the real error`() = runBlocking {
        runner = LocalTaskRunner(
            taskStore,
            scheduler,
            stepStore,
            FakeExecutor(
                TaskExecutionResult(
                    success = false,
                    summary = "",
                    error = "python_unavailable：本机无可用 Python",
                ),
            ),
            pollIntervalMs = 60_000,
            leaseRenewIntervalMs = 60_000,
        )
        queuedDispatched("t1")
        runner.pumpOnce()
        val task = taskStore.getTask("t1")
        assertEquals("failed", task?.state)
        assertEquals("python_unavailable：本机无可用 Python", task?.error)
        assertNull("失败态无 result", task?.result)
    }

    @Test
    fun `one task at a time and turn tasks are never claimed`() = runBlocking {
        // 轮次镜像（带 sessionId）：runner 永不认领。
        taskStore.createTask(
            TaskRecord(
                taskId = "turn-1",
                title = "聊天轮次",
                description = "聊天轮次",
                sessionId = "conv-1",
                state = "queued",
            ),
        )
        queuedDispatched("d1")
        queuedDispatched("d2")
        runner.pumpOnce()
        assertEquals("completed", taskStore.getTask("d1")?.state)
        assertEquals("queued", taskStore.getTask("d2")?.state)
        assertEquals("queued", taskStore.getTask("turn-1")?.state)
        runner.pumpOnce()
        assertEquals("completed", taskStore.getTask("d2")?.state)
        assertEquals("queued", taskStore.getTask("turn-1")?.state)
    }

    @Test
    fun `pumpOnce does nothing when nothing is queued`() = runBlocking {
        runner.pumpOnce()
        assertFalse(runner.isExecuting())
        assertTrue(taskStore.listTasks().isEmpty())
    }

    @Test
    fun `cancel stops an in-flight execution and marks it cancelled`() = runBlocking {
        runner = LocalTaskRunner(
            taskStore,
            scheduler,
            stepStore,
            HangingExecutor(),
            pollIntervalMs = 60_000,
            leaseRenewIntervalMs = 60_000,
        )
        queuedDispatched("t1")
        runner.start()
        withTimeout(10_000) {
            while (!runner.isExecuting("t1")) delay(100)
        }
        assertTrue(runner.cancel("t1"))
        withTimeout(10_000) {
            while (taskStore.getTask("t1")?.state != "cancelled") delay(100)
        }
        assertEquals("cancelled", taskStore.getTask("t1")?.state)
        assertEquals("任务被用户停止。", taskStore.getTask("t1")?.result)
        runner.stop()
    }
}
