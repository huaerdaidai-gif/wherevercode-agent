package wherevercode.agent.core.team

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskRetry
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.transport.DiscoveredPeer
import java.util.UUID

/**
 * 远端汇报 verdict 接线（2026-11-11，任务 692638e2 教训）：
 * Result 只说明“远端回了话”，正文开头是失败表述时必须落 failed，
 * 不能按完成记。另验：重试副本能被泵认领执行（不丢指派、不错设备）。
 *
 * 测试替身与建库方式与 [RemoteTaskPumpTest] 同款。
 */
@RunWith(RobolectricTestRunner::class)
class RemoteTaskDispatcherVerdictTest {

    private var db: TaskDatabase? = null
    private val now = 1_700_000_000L

    @After
    fun tearDown() {
        db?.close()
    }

    private val peer = DiscoveredPeer(
        deviceId = "remote-host",
        displayName = "远程设备",
        host = "192.0.2.10",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )
    private val target = RemoteTarget(peer = peer, agentId = "example-agent", folder = "/workspace")

    private class ScriptedPort(private val events: List<HandEvent>) : RemoteAgentChatPort {
        data class Call(val target: RemoteTarget, val taskId: String, val text: String)
        val calls = mutableListOf<Call>()
        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> {
            calls += Call(target, taskId, text)
            return flowOf(*events.toTypedArray())
        }

        override fun sendControl(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()
    }

    private data class Env(
        val taskStore: RoomTaskStore,
        val scheduler: TaskScheduler,
        val stepStore: InMemoryTaskStepStore,
        val pump: RemoteTaskPump,
        val port: ScriptedPort,
    )

    private fun setup(events: List<HandEvent>): Env {
        val context = RuntimeEnvironment.getApplication()
        val database = TaskDatabase.inMemory(context)
        db = database
        val taskStore = RoomTaskStore(database)
        val scheduler = TaskScheduler(taskStore, AgentDiscovery(context))
        val stepStore = InMemoryTaskStepStore()
        val port = ScriptedPort(events)
        val dispatcher = RemoteTaskDispatcher(taskStore, stepStore, port)
        val pump = RemoteTaskPump(
            taskStore = taskStore,
            scheduler = scheduler,
            stepStore = stepStore,
            dispatcher = dispatcher,
            targetResolver = { _, agentId -> if (agentId == "example-agent") target else null },
        )
        return Env(taskStore, scheduler, stepStore, pump, port)
    }

    private fun attemptId(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * 泵改异步后（任务拆分，2026-11-11）：pumpOnce 只认领并启动执行，
     * 不等终态；等任务落终态再断言（与 RemoteTaskPumpTest.awaitTerminal 同款）。
     */
    private suspend fun awaitTerminal(env: Env, taskId: String, timeoutMs: Long = 10_000L): TaskRecord {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val task = env.taskStore.getTask(taskId)!!
            if (task.state in setOf("completed", "failed", "cancelled")) return task
            delay(50)
        }
        throw AssertionError("任务 $taskId 在 ${timeoutMs}ms 内未落终态，当前态=${env.taskStore.getTask(taskId)?.state}")
    }

    private fun queuedRemoteTask(taskId: String = "task-remote-1"): TaskRecord =
        TaskRecord(
            taskId = taskId,
            title = "写 calculator.py",
            description = "写一个 calculator.py 并跑测试",
            state = "queued",
            assignedAgentId = "example-agent",
            createdAt = now,
            updatedAt = now,
            version = 1,
        )

    @Test
    fun `remote failure text in Result lands failed not completed`() = runBlocking {
        val env = setup(
            listOf(
                HandEvent.Progress(2, "执行中…"),
                HandEvent.Result(
                    3,
                    "未能完成操作。实际仓库在 `/Users/example-user/Projects/wherevercode-agent`，" +
                        "本地只有 `feat/android-ui-v2-20261109` 分支，没有 `main` 或其他默认分支的本地 ref；" +
                        "尝试连接远程以确认默认分支时，因无法解析 `github.com` 失败。",
                ),
            ),
        )
        env.taskStore.createTask(queuedRemoteTask())

        env.pump.pumpOnce()

        val task = awaitTerminal(env, "task-remote-1")
        assertEquals("failed", task.state)
        assertTrue(task.error!!.contains("未能完成操作"))
    }

    @Test
    fun `remote success text in Result still lands completed`() = runBlocking {
        val env = setup(
            listOf(
                HandEvent.Result(2, "已完成：分支 test/x 已提交，提交号 abc123。"),
            ),
        )
        env.taskStore.createTask(queuedRemoteTask())

        env.pump.pumpOnce()

        val task = awaitTerminal(env, "task-remote-1")
        assertEquals("completed", task.state)
        assertTrue(task.result!!.contains("已完成"))
    }

    @Test
    fun `empty remote Result lands failed`() = runBlocking {
        val env = setup(listOf(HandEvent.Result(2, "")))
        env.taskStore.createTask(queuedRemoteTask())

        env.pump.pumpOnce()

        val task = awaitTerminal(env, "task-remote-1")
        assertEquals("failed", task.state)
        assertTrue(task.error!!.contains("远端返回空结果"))
    }

    @Test
    fun `retry copy of a remote task is claimed by the pump and executed`() = runBlocking {
        val env = setup(
            listOf(
                HandEvent.Result(2, "已完成：分支 test/x 已提交，提交号 abc123。"),
            ),
        )
        // 原任务先走合法边落 failed：createTask 不接受 failed 作初始态
        //（null -> failed 不是合法转移），认领→开始→失败与真机路径一致。
        val original = queuedRemoteTask()
        env.taskStore.createTask(original)
        assertNotNull(env.scheduler.claimTaskForMember(original.taskId, "example-agent", attemptId()))
        assertNotNull(env.scheduler.startTask(original.taskId))
        val working = env.taskStore.getTask(original.taskId)!!
        env.taskStore.updateTask(
            original.taskId,
            mapOf("state" to "failed", "error" to "未能完成操作。"),
            working.version, working.goalVersion, working.authEpoch,
        )
        val failedOriginal = env.taskStore.getTask(original.taskId)!!
        val copy = TaskRetry.buildRetryCopy(failedOriginal, now)
        env.taskStore.createTask(copy)

        env.pump.pumpOnce()

        val retried = awaitTerminal(env, copy.taskId)
        assertEquals("completed", retried.state)
        assertEquals(1, env.port.calls.size)
        assertEquals(copy.taskId, env.port.calls[0].taskId)
        assertEquals("example-agent", env.port.calls[0].target.agentId)
        assertEquals(original.taskId, retried.parentTaskId)
    }
}
