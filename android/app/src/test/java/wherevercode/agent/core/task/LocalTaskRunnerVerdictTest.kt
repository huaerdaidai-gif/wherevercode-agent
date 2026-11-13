package wherevercode.agent.core.task

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery

/**
 * 本地执行 verdict 复核（2026-11-11，22:46 本地重试复现）。
 *
 * 根因（已核 BrainBootstrap.taskExecutor）：生产执行器只按“轮次跑完
 * 且无 BrainEvent.Error”报 success=true；工具报错只走
 * ToolResult(ok=false)，不影响 success——大脑在正文里写失败也会被
 * 记成完成（22:46 重试：全部 GitHub 工具报“未知扩展工具”，正文明确
 * 写无法完成操作，卡片仍是 completed）。
 *
 * [LocalTaskRunner.finishTask] 对 success 的结果文本也过一遍
 * RemoteMemberVerdict：开头明示失败（或空结果）→ 落 failed。
 * 脚手架与 [LocalTaskRunnerAssignTest] 同款（fake store + 假执行器）。
 */
@RunWith(RobolectricTestRunner::class)
class LocalTaskRunnerVerdictTest {

    private class ScriptedExecutor(
        private val result: TaskExecutionResult,
    ) : TaskExecutor {
        override suspend fun execute(
            task: TaskRecord,
            onStep: (TaskStepEvent) -> Unit,
        ): TaskExecutionResult = result
    }

    private lateinit var taskStore: DispatchFakeTaskStore
    private lateinit var scheduler: TaskScheduler

    @Before
    fun setUp() {
        taskStore = DispatchFakeTaskStore()
        scheduler = testTaskScheduler(
            taskStore,
            AgentDiscovery(RuntimeEnvironment.getApplication()),
        )
    }

    private fun runnerWith(result: TaskExecutionResult): LocalTaskRunner =
        LocalTaskRunner(
            taskStore,
            scheduler,
            InMemoryTaskStepStore(),
            ScriptedExecutor(result),
            pollIntervalMs = 60_000,
            leaseRenewIntervalMs = 60_000,
        )

    private fun queued(id: String) = taskStore.createTask(
        TaskRecord(
            taskId = id,
            title = "wherevercode-agent：新建分支并提交",
            description = "在 wherevercode-agent 仓库新建分支",
            state = "queued",
            createdAt = 1_700_000_000L,
        ),
    )

    @Test
    fun `success result whose headline says failure lands failed`() = runBlocking {
        queued("local-1")
        val runner = runnerWith(
            TaskExecutionResult(
                success = true,
                summary = "未能完成操作：当前上下文没有可用的 Git 仓库连接；" +
                    "create_github_branch 返回“未知扩展工具”，" +
                    "list_github_files 返回“未知扩展工具”；无法完成操作。",
            ),
        )
        try {
            runner.pumpOnce()
            val task = taskStore.getTask("local-1")!!
            assertEquals("failed", task.state)
            assertTrue(task.error!!.contains("未能完成操作"))
        } finally {
            runner.stop()
        }
    }

    @Test
    fun `success result with a success headline still lands completed`() = runBlocking {
        queued("local-2")
        val runner = runnerWith(
            TaskExecutionResult(success = true, summary = "已完成：分支已提交，提交号 abc123。"),
        )
        try {
            runner.pumpOnce()
            val task = taskStore.getTask("local-2")!!
            assertEquals("completed", task.state)
            assertTrue(task.result!!.contains("已完成"))
        } finally {
            runner.stop()
        }
    }

    @Test
    fun `success result with empty summary lands failed`() = runBlocking {
        queued("local-3")
        val runner = runnerWith(TaskExecutionResult(success = true, summary = ""))
        try {
            runner.pumpOnce()
            val task = taskStore.getTask("local-3")!!
            assertEquals("failed", task.state)
            assertTrue(task.error!!.contains("本地执行未返回任何结果"))
        } finally {
            runner.stop()
        }
    }

    @Test
    fun `explicit failure result still lands failed`() = runBlocking {
        queued("local-4")
        val runner = runnerWith(
            TaskExecutionResult(success = false, summary = "", error = "工具执行异常"),
        )
        try {
            runner.pumpOnce()
            val task = taskStore.getTask("local-4")!!
            assertEquals("failed", task.state)
            assertTrue(task.error!!.contains("工具执行异常"))
        } finally {
            runner.stop()
        }
    }

    @Test
    fun `cooperative pause requeues task and acknowledges at safe point`() = runBlocking {
        val id = "local-pause"
        queued(id)
        val runner = runnerWith(TaskExecutionResult(success = false, summary = "", error = "turn_paused：安全检查点"))
        var acknowledged: String? = null
        runner.onControlCheckpoint = { taskId, outcome ->
            assertEquals(id, taskId)
            acknowledged = outcome
        }
        try {
            runner.pumpOnce()
            assertEquals("requeued", taskStore.getTask(id)!!.state)
            assertEquals("paused", acknowledged)
        } finally {
            runner.stop()
        }
    }

    @Test
    fun `cooperative stop cancels task and acknowledges at safe point`() = runBlocking {
        val id = "local-stop"
        queued(id)
        val runner = runnerWith(TaskExecutionResult(success = false, summary = "", error = "turn_stopped：安全检查点"))
        var acknowledged: String? = null
        runner.onControlCheckpoint = { taskId, outcome ->
            assertEquals(id, taskId)
            acknowledged = outcome
        }
        try {
            runner.pumpOnce()
            assertEquals("cancelled", taskStore.getTask(id)!!.state)
            assertEquals("stopped", acknowledged)
        } finally {
            runner.stop()
        }
    }
}
