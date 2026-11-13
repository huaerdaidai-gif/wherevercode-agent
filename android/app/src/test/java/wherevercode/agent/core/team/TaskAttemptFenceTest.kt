package wherevercode.agent.core.team

import wherevercode.agent.core.task.testTaskScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.task.LocalTaskRunner
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskExecutionResult
import wherevercode.agent.core.task.TaskExecutor
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStepEvent
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * P0 attempt 围栏（2026-11-11），Robolectric + 真实 Room：
 *
 * 执行中途租约过期 → 扫回队 → 另一个执行器重认领（attempt+1）后，
 * 旧执行器的终态写必须被丢弃：任务停在新认领的 assigned/attempt 2，
 * 旧结果不落盘、不记技能、不刷 UI。本地（LocalTaskRunner）与远端
 * （RemoteTaskDispatcher）同款——围栏在各自的 updateTask 里，不在
 * 调度器里。
 */
@RunWith(RobolectricTestRunner::class)
class TaskAttemptFenceTest {
    private var db: TaskDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    /** 本地执行器：执行中途模拟租约过期 + 重认领，再返回“成功”。 */
    private class ExpiringExecutor(
        private val store: RoomTaskStore,
        private val scheduler: TaskScheduler,
    ) : TaskExecutor {
        override suspend fun execute(
            task: TaskRecord,
            onStep: (TaskStepEvent) -> Unit,
        ): TaskExecutionResult {
            val now = System.currentTimeMillis() / 1000
            val expired = store.expireLeases(now + RoomTaskStore.LEASE_SECONDS + 1)
            check(expired == listOf(task.taskId)) { "扫应回队本任务，实际 $expired" }
            val reclaimed = scheduler.claimTask(task.taskId, "agent-2")
            checkNotNull(reclaimed) { "回队后重认领应成功" }
            check(reclaimed.attempt == task.attempt + 1) { "重认领 attempt 应 +1" }
            return TaskExecutionResult(success = true, summary = "旧执行器的结果")
        }
    }

    @Test
    fun `local stale executor finish is dropped after lease expiry and re-claim`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = TaskDatabase.inMemory(context)
        db = database
        val taskStore = RoomTaskStore(database)
        val scheduler = testTaskScheduler(taskStore, AgentDiscovery(context))
        val runner = LocalTaskRunner(
            taskStore,
            scheduler,
            InMemoryTaskStepStore(),
            ExpiringExecutor(taskStore, scheduler),
            pollIntervalMs = 60_000,
            leaseRenewIntervalMs = 60_000,
        )
        taskStore.createTask(
            TaskRecord(
                taskId = "t1", title = "写 calculator.py",
                description = "写 calculator.py", state = "queued",
            ),
        )
        runner.pumpOnce()
        runner.stop()
        val task = taskStore.getTask("t1")!!
        assertEquals("assigned", task.state)
        assertEquals(2, task.attempt)
        assertEquals("agent-2", task.assignedAgentId)
        // 旧执行器的“成功”没落盘：任务仍在 agent-2 手里待执行。
        assertNull(task.result)
        assertNull(task.finishedAt)
    }

    // -- 远端 -----------------------------------------------------------

    private val peer = DiscoveredPeer(
        deviceId = "remote-host",
        displayName = "远程设备",
        host = "192.0.2.10",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )
    private val target = RemoteTarget(peer = peer, agentId = "example-agent", folder = "/workspace")

    /** 测试替身：按脚本回放 Hand 事件，runAgent 入口先跑钩子（模拟过期+重认领）。 */
    private class FencePort(
        private val events: List<HandEvent>,
        private val onRun: (String) -> Unit,
    ) : RemoteAgentChatPort {
        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> {
            onRun(taskId)
            return flowOf(*events.toTypedArray())
        }

        override fun sendControl(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()
    }

    @Test
    fun `remote stale dispatcher finish is dropped after lease expiry and re-claim`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = TaskDatabase.inMemory(context)
        db = database
        val taskStore = RoomTaskStore(database)
        val scheduler = testTaskScheduler(taskStore, AgentDiscovery(context))
        val stepStore = InMemoryTaskStepStore()
        val port = FencePort(
            listOf(HandEvent.Result(3, "calculator.py 已写好，测试通过")),
            onRun = { taskId ->
                val now = System.currentTimeMillis() / 1000
                val expired = taskStore.expireLeases(now + RoomTaskStore.LEASE_SECONDS + 1)
                check(expired == listOf(taskId)) { "扫应回队本任务，实际 $expired" }
                val reclaimed = scheduler.claimTask(taskId, "agent-2")
                checkNotNull(reclaimed) { "回队后重认领应成功" }
            },
        )
        val dispatcher = RemoteTaskDispatcher(taskStore, scheduler, stepStore, port)
        val pump = RemoteTaskPump(
            taskStore = taskStore,
            scheduler = scheduler,
            stepStore = stepStore,
            dispatcher = dispatcher,
            targetResolver = { _, agentId -> if (agentId == "example-agent") target else null },
        )
        taskStore.createTask(
            TaskRecord(
                taskId = "task-remote-1",
                title = "写 calculator.py",
                description = "写一个 calculator.py 并跑测试",
                state = "queued",
                assignedAgentId = "example-agent",
                createdAt = 1_700_000_000L,
                updatedAt = 1_700_000_000L,
                version = 1,
            ),
        )
        pump.pumpOnce()
        // 泵改异步后（任务拆分，2026-11-11）：pumpOnce 只认领并启动
        // 执行，租约过期 + 重认领发生在启动的协程里；等重认领落盘
        // （attempt 2、agent-2 接手）再断言，10 秒内不到即失败。
        val deadline = System.nanoTime() + 10_000L * 1_000_000L
        var task = taskStore.getTask("task-remote-1")!!
        while (!(task.attempt == 2 && task.assignedAgentId == "agent-2")) {
            if (System.nanoTime() >= deadline) {
                throw AssertionError(
                    "重认领在 10 秒内未落盘，当前态=${task.state} attempt=${task.attempt} agent=${task.assignedAgentId}",
                )
            }
            delay(50)
            task = taskStore.getTask("task-remote-1")!!
        }
        assertEquals("assigned", task.state)
        assertEquals(2, task.attempt)
        assertEquals("agent-2", task.assignedAgentId)
        // 旧执行器的“测试通过”没落盘：任务仍在 agent-2 手里待执行。
        assertNull(task.result)
    }
}
