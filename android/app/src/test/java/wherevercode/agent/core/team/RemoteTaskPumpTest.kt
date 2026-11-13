package wherevercode.agent.core.team

import wherevercode.agent.core.task.testTaskScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.control.ControlAction
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.task.EvalLogRecorder
import wherevercode.agent.core.task.EvalOutcome
import wherevercode.agent.core.task.InMemoryEvalLogStore
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.ui.TurnTaskRecorder
import java.util.Collections
import java.util.UUID

/**
 * 阶段 B（V5 紧急开发）：远程任务认领 → 执行 → 回传。
 *
 * 红测试：RemoteTaskDispatcher / RemoteTaskPump 尚不存在。
 *
 * 规则：
 * - 泵只认领指派给远程 Agent 的 queued 任务（sessionId 为 null 的
 *   分派任务）；本地任务（未指派/指派给 local）一律不动，留给
 *   LocalTaskRunner——两边绝不抢同一个任务。
 * - 进度流式记为任务步骤，结果落 review → completed，错误落 failed，
 *   控制停止落 cancelled；全程写同一 TaskStore，不另起账本。
 * - 执行中途目标变了（goalVersion 变了）：本次结果作废，任务
 *   cancelled（与 TurnTaskRecorder GOAL_CHANGED → cancelled 一致），
 *   不把旧目标的结果当成新目标的交付。
 * - 找不到远程目标的任务：保持 queued，needs_attention + 可见错误，
 *   绝不静默丢弃、绝不假装已执行。
 */
@RunWith(RobolectricTestRunner::class)
class RemoteTaskPumpTest {

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

    /** 测试替身：按脚本回放 Hand 事件，记下收到的调用。 */
    private class ScriptedPort(
        private val events: List<HandEvent>,
        private val onRun: ((taskId: String) -> Unit)? = null,
    ) : RemoteAgentChatPort {
        data class Call(val target: RemoteTarget, val taskId: String, val text: String)
        // 泵改异步后（任务拆分，2026-11-11）两个成员的执行可能并发
        // 调 runAgent，调用记录用同步列表。
        val calls = Collections.synchronizedList(mutableListOf<Call>())
        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> {
            calls += Call(target, taskId, text)
            onRun?.invoke(taskId)
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
        val evalLogStore: InMemoryEvalLogStore,
    )

    private fun setup(
        events: List<HandEvent>,
        onRun: ((String) -> Unit)? = null,
        isAgentEnabled: (String) -> Boolean = { true },
        // 2026-11-12 回复归属修复：可注入生产口径的 isRemoteMember
        //（按注册表查）与 agentExists（孤儿标记用）；不传时沿用泵的
        // 默认口径（除 local/local-brain 外一律视为远程、不判孤儿）。
        isRemoteMember: ((String) -> Boolean)? = null,
        agentExists: ((String) -> Boolean)? = null,
    ): Env {
        val context = RuntimeEnvironment.getApplication()
        val database = TaskDatabase.inMemory(context)
        db = database
        val taskStore = RoomTaskStore(database)
        val scheduler = testTaskScheduler(taskStore, AgentDiscovery(context))
        val stepStore = InMemoryTaskStepStore()
        val port = ScriptedPort(events, onRun)
        val dispatcher = RemoteTaskDispatcher(taskStore, stepStore, port)
        // P1 eval 日志：泵配记录器；不配时行为与原来完全一致。
        val evalLogStore = InMemoryEvalLogStore()
        val pump = RemoteTaskPump(
            taskStore = taskStore,
            scheduler = scheduler,
            stepStore = stepStore,
            dispatcher = dispatcher,
            targetResolver = { _, agentId ->
                when (agentId) {
                    "example-agent" -> target
                    "codex-mac" -> RemoteTarget(peer = peer, agentId = "codex-mac", folder = "/Users/example-user")
                    else -> null
                }
            },
            isRemoteMember = isRemoteMember
                ?: { it != "local" && it != TurnTaskRecorder.LOCAL_AGENT_ID },
            evalRecorder = EvalLogRecorder(evalLogStore),
            // Agent 开关（2026-11-12）：测试可注入启用态，默认全启用。
            isAgentEnabled = isAgentEnabled,
            agentExists = agentExists,
        )
        return Env(taskStore, scheduler, stepStore, pump, port, evalLogStore)
    }

    /**
     * 泵改异步后（任务拆分，2026-11-11）：pumpOnce 只认领并启动执行，
     * 不等终态；等任务落终态（completed/failed/cancelled）再断言，
     * 10 秒内不到终态即失败。
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

    /**
     * eval 在任务落终态后才记（同一协程里 execute 返回后记），等终态
     * 不等于等 eval 写完；直接等 eval 条目出现再断言。
     */
    private suspend fun awaitEvalEntries(env: Env, count: Int, timeoutMs: Long = 10_000L) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (env.evalLogStore.listEntries().size >= count) return
            delay(50)
        }
        throw AssertionError("eval 条目在 ${timeoutMs}ms 内未到 $count 条，当前=${env.evalLogStore.listEntries().size}")
    }

    private fun attemptId(): String = UUID.randomUUID().toString().replace("-", "")

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
    fun `pump claims remote task and lands completed with result`() = runBlocking {
        val env = setup(
            listOf(
                HandEvent.Progress(2, "已读 3 个文件", 40),
                HandEvent.Result(3, "calculator.py 已写好，测试通过"),
            ),
        )
        env.taskStore.createTask(queuedRemoteTask())

        env.pump.pumpOnce()

        val task = awaitTerminal(env, "task-remote-1")
        assertEquals("completed", task.state)
        assertTrue(task.result!!.contains("测试通过"))
        assertEquals(1, env.port.calls.size)
        assertEquals("task-remote-1", env.port.calls[0].taskId)
        assertEquals("example-agent", env.port.calls[0].target.agentId)
        assertTrue(env.port.calls[0].text.contains("calculator.py"))
        // 进度记为步骤，可见。
        assertTrue(env.stepStore.listSteps("task-remote-1").isNotEmpty())
    }

    @Test
    fun `remote error lands failed with honest reason`() = runBlocking {
        val env = setup(
            listOf(
                HandEvent.Blocked("Agent 脚本不存在：请检查 VPS 上的 Example Agent", "hand_not_found"),
            ),
        )
        env.taskStore.createTask(queuedRemoteTask())

        env.pump.pumpOnce()

        val task = awaitTerminal(env, "task-remote-1")
        assertEquals("failed", task.state)
        assertTrue(task.error!!.contains("Agent 脚本不存在"))
    }

    @Test
    fun `completed remote task writes one eval entry`() = runBlocking {
        // P1 eval 日志（2026-11-11）：远端任务落终态，泵记一条。
        val env = setup(
            listOf(
                HandEvent.Result(3, "calculator.py 已写好，测试通过"),
            ),
        )
        env.taskStore.createTask(queuedRemoteTask())

        env.pump.pumpOnce()

        awaitEvalEntries(env, 1)
        val entries = env.evalLogStore.listEntries()
        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("task-remote-1", entry.taskId)
        assertEquals("example-agent", entry.agentId)
        assertEquals("remote-host", entry.deviceId)
        assertEquals(EvalOutcome.COMPLETED, entry.outcome)
        assertEquals("single", entry.mode)
        assertEquals("manual", entry.assignedVia)
        assertTrue(entry.wallClockMs != null && entry.wallClockMs!! >= 0)
    }

    @Test
    fun `failed remote task writes one eval entry`() = runBlocking {
        // P1 eval 日志：失败也记一条（成败都进 eval，才能算成功率）。
        val env = setup(
            listOf(
                HandEvent.Blocked("Agent 脚本不存在：请检查 VPS 上的 Example Agent", "hand_not_found"),
            ),
        )
        env.taskStore.createTask(queuedRemoteTask())

        env.pump.pumpOnce()

        awaitEvalEntries(env, 1)
        val entries = env.evalLogStore.listEntries()
        assertEquals(1, entries.size)
        assertEquals(EvalOutcome.FAILED, entries.first().outcome)
    }

    @Test
    fun `control stop lands cancelled not failed`() = runBlocking {
        val env = setup(
            listOf(
                HandEvent.Progress(2, "执行中…"),
                HandEvent.Blocked("已按控制指令停止", RemoteTaskDispatcher.REMOTE_CONTROLLED_STOP_CODE),
            ),
        )
        env.taskStore.createTask(queuedRemoteTask())

        env.pump.pumpOnce()

        val task = awaitTerminal(env, "task-remote-1")
        assertEquals("cancelled", task.state)
    }

    @Test
    fun `goal changed mid-run discards stale result`() = runBlocking {
        lateinit var env: Env
        env = setup(
            listOf(
                HandEvent.Progress(2, "执行中…"),
                HandEvent.Result(3, "按旧目标写好的 calculator.py"),
            ),
            onRun = { taskId ->
                // 进度发出后、结果到达前，用户改了目标（走 ControlPlane
                // 的同一路径：先 bump goalVersion 计数器，再改描述）。
                val current = env.taskStore.getTask(taskId)!!
                env.taskStore.updateControlCounters(taskId, 2, current.authEpoch)
                val bumped = env.taskStore.getTask(taskId)!!
                env.taskStore.updateTask(
                    taskId,
                    mapOf("description" to "改成写 README"),
                    bumped.version,
                    2,
                    bumped.authEpoch,
                )
            },
        )
        env.taskStore.createTask(queuedRemoteTask())

        env.pump.pumpOnce()

        val task = awaitTerminal(env, "task-remote-1")
        assertEquals("cancelled", task.state)
        assertTrue(task.result!!.contains("目标已变更"))
        assertFalse(task.result!!.contains("calculator.py"))
    }

    @Test
    fun `unroutable remote task stays queued with visible attention flag`() = runBlocking {
        val env = setup(listOf(HandEvent.Result(2, "ok")))
        // 指派给一个解析不到目标的 Agent。
        env.taskStore.createTask(queuedRemoteTask().copy(assignedAgentId = "ghost-agent"))

        env.pump.pumpOnce()

        val task = env.taskStore.getTask("task-remote-1")!!
        assertEquals("queued", task.state)
        assertTrue(task.needsAttention)
        assertTrue(task.error!!.contains("ghost-agent"))
        assertEquals(0, env.port.calls.size)
    }

    /**
     * Agent 开关（2026-11-12）：关掉的 Agent 不接派活——任务留
     * queued + needs_attention + 可见原因，不认领、不调 Hand。
     */
    @Test
    fun `disabled agent task stays queued with visible attention flag`() = runBlocking {
        val env = setup(
            listOf(HandEvent.Result(2, "ok")),
            isAgentEnabled = { false },
        )
        env.taskStore.createTask(queuedRemoteTask())

        val claimed = env.pump.pumpOnce()

        assertTrue(claimed)
        val task = env.taskStore.getTask("task-remote-1")!!
        assertEquals("queued", task.state)
        assertTrue(task.needsAttention)
        assertTrue(task.error!!.contains("已停用"))
        assertTrue(task.error!!.contains("重新启用"))
        assertEquals(0, env.port.calls.size)
    }

    /**
     * Agent 开关热插拔（2026-11-12）：开关翻完不用重启——同一台泵，
     * 关着时留队，打开后下一轮即认领并跑完；泵每轮现查启用态。
     */
    @Test
    fun `re-enabled agent task is claimed on the next pump round`() = runBlocking {
        var enabled = false
        val env = setup(
            listOf(HandEvent.Result(3, "calculator.py 已写好，测试通过")),
            isAgentEnabled = { enabled },
        )
        env.taskStore.createTask(queuedRemoteTask())

        env.pump.pumpOnce()
        assertEquals("queued", env.taskStore.getTask("task-remote-1")!!.state)
        assertEquals(0, env.port.calls.size)

        enabled = true
        env.pump.pumpOnce()
        val done = awaitTerminal(env, "task-remote-1")
        assertEquals("completed", done.state)
        assertEquals(1, env.port.calls.size)
    }

    @Test
    fun `pump never touches local or unassigned queued tasks`() = runBlocking {
        val env = setup(listOf(HandEvent.Result(2, "ok")))
        env.taskStore.createTask(queuedRemoteTask(taskId = "task-local").copy(assignedAgentId = null))
        env.taskStore.createTask(queuedRemoteTask(taskId = "task-local-2").copy(assignedAgentId = "local"))

        env.pump.pumpOnce()

        assertEquals("queued", env.taskStore.getTask("task-local")?.state)
        assertEquals("queued", env.taskStore.getTask("task-local-2")?.state)
        assertEquals(0, env.port.calls.size)
    }

    /**
     * 任务拆分（2026-11-11）：泵的门禁改按成员——同一成员在忙才挡，
     * 不同成员在忙不挡。原“另一任务在途泵什么都不做”是全局门禁，
     * 已随 TaskScheduler.claimTaskForMember 的按成员门禁一起改掉。
     */
    @Test
    fun `pump does not claim while the same member is busy`() = runBlocking {
        val env = setup(listOf(HandEvent.Result(2, "ok")))
        env.taskStore.createTask(queuedRemoteTask())
        // 同一成员（example-agent）的另一任务已在途：泵这次什么都不做。
        env.taskStore.createTask(queuedRemoteTask(taskId = "task-busy"))
        assertNotNull(env.scheduler.claimTaskForMember("task-busy", "example-agent", attemptId()))

        env.pump.pumpOnce()

        assertEquals("queued", env.taskStore.getTask("task-remote-1")?.state)
        assertEquals(0, env.port.calls.size)
    }

    @Test
    fun `pump claims while a different member is busy`() = runBlocking {
        val env = setup(listOf(HandEvent.Result(2, "ok")))
        env.taskStore.createTask(queuedRemoteTask())
        // 另一成员（codex-mac）的任务在途：不挡 example-agent 的任务。
        env.taskStore.createTask(queuedRemoteTask(taskId = "task-busy").copy(assignedAgentId = "codex-mac"))
        assertNotNull(env.scheduler.claimTaskForMember("task-busy", "codex-mac", attemptId()))

        env.pump.pumpOnce()

        val task = awaitTerminal(env, "task-remote-1")
        assertEquals("completed", task.state)
        assertEquals(1, env.port.calls.size)
        assertEquals("task-remote-1", env.port.calls[0].taskId)
    }

    @Test
    fun `pump runs two members tasks in parallel`() = runBlocking {
        val env = setup(listOf(HandEvent.Result(2, "ok")))
        env.taskStore.createTask(queuedRemoteTask(taskId = "task-grok"))
        env.taskStore.createTask(queuedRemoteTask(taskId = "task-codex").copy(assignedAgentId = "codex-mac"))

        // pumpOnce 一轮只认领一个成员的任务（见 RemoteTaskPump.pumpOnce）；
        // 两个成员要调两次，生产里 start() 循环每 500ms 调一次。
        env.pump.pumpOnce()
        env.pump.pumpOnce()

        val grok = awaitTerminal(env, "task-grok")
        val codex = awaitTerminal(env, "task-codex")
        assertEquals("completed", grok.state)
        assertEquals("completed", codex.state)
        assertEquals(2, env.port.calls.size)
        assertEquals(setOf("task-grok", "task-codex"), env.port.calls.map { it.taskId }.toSet())
    }

    @Test
    fun `pump skips chat turn tasks with session id`() = runBlocking {
        val env = setup(listOf(HandEvent.Result(2, "ok")))
        env.taskStore.createTask(
            queuedRemoteTask().copy(sessionId = "session-1"),
        )

        env.pump.pumpOnce()

        assertEquals("queued", env.taskStore.getTask("task-remote-1")?.state)
        assertEquals(0, env.port.calls.size)
    }

    // 2026-11-12 回复归属修复：孤儿标记 + 大小写忙闲。

    @Test
    fun `task assigned to an unknown agent is marked needs_attention instead of silently queued`() = runBlocking {
        // 指派了不存在 Agent 的排队任务不再静默等——泵标
        // needs_attention + 原因，任务库里看得到为什么没派；只写一次。
        // 生产口径：isRemoteMember 与 agentExists 都按注册表查。
        val known = { id: String -> id == "example-agent" || id == "codex-mac" }
        val env = setup(
            listOf(HandEvent.Result(2, "ok")),
            isRemoteMember = known,
            agentExists = known,
        )
        env.taskStore.createTask(queuedRemoteTask("task-ghost").copy(assignedAgentId = "ghost-agent"))

        assertTrue("标孤儿是本轮可见的工作", env.pump.pumpOnce())

        val task = env.taskStore.getTask("task-ghost")!!
        assertEquals("queued", task.state)
        assertTrue(task.needsAttention)
        assertTrue(task.error!!.contains("ghost-agent"))
        assertTrue(task.error!!.contains("不存在"))
        assertEquals(0, env.port.calls.size)
        // 第二轮不再重复写。
        assertFalse(env.pump.pumpOnce())
        assertEquals(task.error, env.taskStore.getTask("task-ghost")?.error)
    }

    @Test
    fun `agent busy check is case-insensitive across Codex and codex`() = runBlocking {
        // 同一 Agent 的 "Codex"/"codex" 两行不被当成两个成员：
        // "Codex" 在手时，"codex" 的排队任务不认领、不派活。
        // 在手任务先建 queued、再经调度器认领+启动（Room 不许新行
        // 直接是 working 态）。
        val env = setup(listOf(HandEvent.Result(2, "ok")))
        env.taskStore.createTask(
            TaskRecord(
                taskId = "task-busy",
                title = "在手",
                state = "queued",
                assignedAgentId = "Codex",
                createdAt = now,
                updatedAt = now,
                version = 1,
            ),
        )
        assertNotNull(env.scheduler.claimTaskForMember("task-busy", "Codex", attemptId()))
        assertNotNull(env.scheduler.startTask("task-busy"))
        env.taskStore.createTask(queuedRemoteTask("task-queued").copy(assignedAgentId = "codex"))

        assertFalse("成员在忙，本轮不应认领", env.pump.pumpOnce())

        val queued = env.taskStore.getTask("task-queued")!!
        assertEquals("queued", queued.state)
        assertFalse(queued.needsAttention)
        assertNull(queued.error)
        assertEquals(0, env.port.calls.size)
    }
}
