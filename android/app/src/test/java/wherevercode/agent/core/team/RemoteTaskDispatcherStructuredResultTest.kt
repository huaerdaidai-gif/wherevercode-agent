package wherevercode.agent.core.team

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.AgentDiscovery
import wherevercode.agent.core.mcp.HandClient
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteResultProtocol
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.task.InMemoryTaskStepStore
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.testTaskScheduler
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * T6 结构化结果协议接线测试（2026-11-15，S5）：远端任务终态由协议
 * status/code 决定，正文不再参与成败判定；旧协议（无 status）走
 * 分类器兜底但带显式标记；断线/超时落“结果未知”而不是普通失败。
 *
 * 覆盖任务书 9 类用例（对应 T6-S2 §10）：
 * 1 明确成功；2 明确失败；3 明确取消；4 执行器异常；5 旧协议兜底；
 * 6 正文含“失败”但结构化为成功；7 正文含“成功”但结构化为失败；
 * 8 未知/非法状态；9 超时与断线（结果未知 vs 服务端确认超时）。
 *
 * 测试脚手架与 [RemoteTaskDispatcherArtifactTest] 同款（直接 execute，
 * 不走泵）。
 */
@RunWith(RobolectricTestRunner::class)
class RemoteTaskDispatcherStructuredResultTest {

    private var db: TaskDatabase? = null
    private val target = RemoteTarget(
        peer = DiscoveredPeer(
            deviceId = "remote-host", displayName = "远程设备",
            host = "192.0.2.10", port = 18743,
            fingerprintSha256 = "ab".repeat(32), capabilities = listOf("hand"),
        ),
        agentId = "example-agent", folder = "/workspace",
    )

    /** run 流与 get_artifact 均可脚本化；默认 get_artifact 恒“暂不可读”（非终态）。 */
    private class ScriptedPort(
        private val runEvents: List<HandEvent>,
        private val fetches: List<() -> Flow<HandEvent>> = emptyList(),
    ) : RemoteAgentChatPort {
        override fun runAgent(
            target: RemoteTarget, taskId: String, text: String,
            timeoutSeconds: Long, goalVersion: Long, epoch: Long,
        ): Flow<HandEvent> = flowOf(*runEvents.toTypedArray())

        override fun getArtifact(target: RemoteTarget, taskId: String, attemptId: String): Flow<HandEvent> {
            val next = fetches.firstOrNull() ?: return flowOf(
                HandEvent.Blocked("暂时无法读取", "artifact_unavailable"),
            )
            return next()
        }

        override fun sendControl(
            target: RemoteTarget, taskId: String, command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()
    }

    /** runAgent 流本身抛异常（执行器异常路径，映射表 #4）。 */
    private class ThrowingPort : RemoteAgentChatPort {
        override fun runAgent(
            target: RemoteTarget, taskId: String, text: String,
            timeoutSeconds: Long, goalVersion: Long, epoch: Long,
        ): Flow<HandEvent> = flow { throw IllegalStateException("boom") }

        override fun sendControl(
            target: RemoteTarget, taskId: String, command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()
    }

    @After
    fun tearDown() {
        db?.close()
    }

    private fun run(
        port: RemoteAgentChatPort,
        id: String = "task-${System.nanoTime()}",
    ): Pair<RemoteTaskOutcome, TaskRecord> = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val database = TaskDatabase.inMemory(context)
        db = database
        val store = RoomTaskStore(database)
        val scheduler = testTaskScheduler(store, AgentDiscovery(context))
        store.createTask(
            TaskRecord(taskId = id, title = id, state = "queued", assignedAgentId = "example-agent"),
        )
        val claimed = checkNotNull(scheduler.claimTaskForMember(id, "example-agent", "att-1"))
        val dispatcher = RemoteTaskDispatcher(
            store, scheduler, InMemoryTaskStepStore(), port,
            // 有界重取间隔压到 0：断线恢复失败路径立刻终态，不拖慢测试。
            artifactRetryDelaysMs = listOf(0L),
        )
        val outcome = dispatcher.execute(claimed, target)
        outcome to checkNotNull(store.getTask(id))
    }

    // 1. 明确成功（正文含“失败”字样也照样成功——回归关键用例，任务书用例 6）。
    @Test
    fun `structured succeeded wins over failure-looking body`() = runBlocking {
        val (outcome, task) = run(
            ScriptedPort(
                listOf(
                    HandEvent.Result(
                        seq = 2,
                        output = "修复完成：登录失败的问题已解决，失败用例全部通过，没有发现回归。",
                        status = RemoteResultProtocol.STATUS_SUCCEEDED,
                    ),
                ),
            ),
        )
        assertTrue(outcome is RemoteTaskOutcome.Completed)
        assertEquals(true, (outcome as RemoteTaskOutcome.Completed).fromStructured)
        assertEquals("completed", task.state)
        assertTrue(task.result!!.contains("登录失败的问题已解决"))
    }

    // 2. 明确失败（正文含“成功”字样也照样失败——回归关键用例，任务书用例 7）。
    @Test
    fun `structured failed wins over success-looking body`() = runBlocking {
        val (outcome, task) = run(
            ScriptedPort(
                listOf(
                    HandEvent.Result(
                        seq = 2,
                        output = "测试成功跑完 3 个用例，构建成功。但退出码非 0。",
                        status = RemoteResultProtocol.STATUS_FAILED,
                        errorCode = RemoteResultProtocol.CODE_AGENT_EXIT_NONZERO,
                        errorMessage = "Agent 执行失败（退出码 3）。",
                    ),
                ),
            ),
        )
        assertTrue(outcome is RemoteTaskOutcome.Failed)
        outcome as RemoteTaskOutcome.Failed
        assertEquals(RemoteResultProtocol.CODE_AGENT_EXIT_NONZERO, outcome.errorCode)
        assertEquals("failed", task.state)
        assertTrue(task.error!!.startsWith("[error_code=agent_exit_nonzero]"))
        assertTrue(task.error!!.contains("Agent 执行失败（退出码 3）"))
    }

    // 3. 明确取消：只有 remote_controlled_stop 这个出口能落 cancelled。
    @Test
    fun `remote controlled stop lands cancelled with the code`() = runBlocking {
        val (outcome, task) = run(
            ScriptedPort(
                listOf(HandEvent.Blocked("已按控制指令停止。", RemoteTaskDispatcher.REMOTE_CONTROLLED_STOP_CODE)),
            ),
        )
        assertTrue(outcome is RemoteTaskOutcome.Cancelled)
        assertEquals(RemoteTaskDispatcher.REMOTE_CONTROLLED_STOP_CODE, (outcome as RemoteTaskOutcome.Cancelled).errorCode)
        assertEquals("cancelled", task.state)
    }

    // 3b. Result 帧带 status=cancelled（T4 若采此出口同样落 cancelled）。
    @Test
    fun `structured cancelled result lands cancelled`() = runBlocking {
        val (outcome, task) = run(
            ScriptedPort(
                listOf(
                    HandEvent.Result(
                        seq = 2,
                        output = "",
                        status = RemoteResultProtocol.STATUS_CANCELLED,
                        errorMessage = "已按控制指令停止。",
                    ),
                ),
            ),
        )
        assertTrue(outcome is RemoteTaskOutcome.Cancelled)
        assertEquals("cancelled", task.state)
        assertEquals("已按控制指令停止。", (outcome as RemoteTaskOutcome.Cancelled).note)
    }

    // 4. 执行器异常：App 侧链路抛非取消异常 → remote_executor_error。
    @Test
    fun `executor exception lands failed with executor error code`() = runBlocking {
        val (outcome, task) = run(ThrowingPort())
        assertTrue(outcome is RemoteTaskOutcome.Failed)
        assertEquals(
            RemoteResultProtocol.CODE_REMOTE_EXECUTOR_ERROR,
            (outcome as RemoteTaskOutcome.Failed).errorCode,
        )
        assertEquals("failed", task.state)
        assertTrue(task.error!!.startsWith("[error_code=remote_executor_error]"))
        assertTrue(task.error!!.contains("远端执行异常"))
    }

    // 5. 旧协议兜底：无 status。失败文本 → failed + 双标记；成功文本 →
    //    completed + fromStructured=false（行为与既有 verdict 测试一致）。
    @Test
    fun `legacy fallback failure is marked with legacy verdict fallback`() = runBlocking {
        val (outcome, task) = run(
            ScriptedPort(
                listOf(
                    HandEvent.Result(
                        seq = 2,
                        output = "未能完成操作：实际仓库在 `/Users/x/Projects/wherevercode-agent`。",
                    ),
                ),
            ),
        )
        assertTrue(outcome is RemoteTaskOutcome.Failed)
        assertEquals(
            RemoteResultProtocol.CODE_LEGACY_VERDICT_FALLBACK,
            (outcome as RemoteTaskOutcome.Failed).errorCode,
        )
        assertEquals("failed", task.state)
        assertTrue(task.error!!.startsWith("[error_code=legacy_verdict_fallback] [source=fallback]"))
        assertTrue(task.error!!.contains("未能完成操作"))
    }

    @Test
    fun `legacy fallback success is completed but not structured`() = runBlocking {
        val (outcome, task) = run(
            ScriptedPort(listOf(HandEvent.Result(seq = 2, output = "已完成：分支 test/x 已提交。"))),
        )
        assertTrue(outcome is RemoteTaskOutcome.Completed)
        assertEquals(false, (outcome as RemoteTaskOutcome.Completed).fromStructured)
        assertEquals("completed", task.state)
        assertTrue(task.result!!.contains("已完成"))
    }

    // 8. 未知/非法 status：保守失败，绝不降级成功。
    @Test
    fun `unknown status value is conservative failure`() = runBlocking {
        val (outcome, task) = run(
            ScriptedPort(listOf(HandEvent.Result(seq = 2, output = "done", status = "banana"))),
        )
        assertTrue(outcome is RemoteTaskOutcome.Failed)
        assertEquals(
            RemoteResultProtocol.CODE_INVALID_PROTOCOL_STATUS,
            (outcome as RemoteTaskOutcome.Failed).errorCode,
        )
        assertEquals("failed", task.state)
        assertTrue(task.error!!.startsWith("[error_code=invalid_protocol_status]"))
        assertTrue(task.error!!.contains("status=banana"))
    }

    // 8b. 自相矛盾（succeeded 却带 error_code）：同样按非法处理。
    @Test
    fun `succeeded with error code is contradictory failure`() = runBlocking {
        val (outcome, task) = run(
            ScriptedPort(
                listOf(
                    HandEvent.Result(
                        seq = 2, output = "done",
                        status = RemoteResultProtocol.STATUS_SUCCEEDED,
                        errorCode = "agent_exit_nonzero",
                    ),
                ),
            ),
        )
        assertTrue(outcome is RemoteTaskOutcome.Failed)
        assertEquals(
            RemoteResultProtocol.CODE_INVALID_PROTOCOL_STATUS,
            (outcome as RemoteTaskOutcome.Failed).errorCode,
        )
        assertEquals("failed", task.state)
    }

    // 9a. 客户端断线（transport_interrupted）且交付恢复失败：结果未知。
    @Test
    fun `transport interruption with failed recovery lands unknown not plain failure`() = runBlocking {
        val (outcome, task) = run(
            ScriptedPort(
                listOf(HandEvent.Blocked("传输中断：Read timed out", HandClient.TRANSPORT_INTERRUPTED)),
                // get_artifact 恒 artifact_unavailable（非终态）→ 有界恢复耗尽。
            ),
        )
        assertTrue(outcome is RemoteTaskOutcome.Failed)
        assertEquals(
            RemoteResultProtocol.CODE_REMOTE_RESULT_UNKNOWN,
            (outcome as RemoteTaskOutcome.Failed).errorCode,
        )
        assertEquals("failed", task.state)
        assertTrue(task.error!!.startsWith("[error_code=remote_result_unknown]"))
        assertTrue("文案必须显式写结果未知", task.error!!.contains("结果未知"))
        assertFalse("不得写成确定失败文案", task.error!!.contains("任务失败"))
    }

    // 9b. 服务端确认超时（进程已被杀）：确定失败，与结果未知严格区分。
    @Test
    fun `server confirmed agent timeout lands failed with agent_timeout`() = runBlocking {
        val (outcome, task) = run(
            ScriptedPort(
                listOf(HandEvent.Blocked("Agent 执行超时（600 秒）：已终止。", RemoteResultProtocol.CODE_AGENT_TIMEOUT)),
            ),
        )
        assertTrue(outcome is RemoteTaskOutcome.Failed)
        assertEquals(
            RemoteResultProtocol.CODE_AGENT_TIMEOUT,
            (outcome as RemoteTaskOutcome.Failed).errorCode,
        )
        assertEquals("failed", task.state)
        assertTrue(task.error!!.startsWith("[error_code=agent_timeout]"))
        assertFalse(task.error!!.contains("结果未知"))
    }

    // 9c. Host 确认“无交付记录”（artifact_missing）：确认失败，非未知。
    @Test
    fun `host confirmed missing delivery lands failed with the terminal code`() = runBlocking {
        val (outcome, task) = run(
            ScriptedPort(
                runEvents = emptyList(),
                fetches = listOf { flowOf(HandEvent.Blocked("没有产物", "artifact_missing")) },
            ),
        )
        assertTrue(outcome is RemoteTaskOutcome.Failed)
        assertEquals("artifact_missing", (outcome as RemoteTaskOutcome.Failed).errorCode)
        assertEquals("failed", task.state)
        assertFalse(task.error!!.contains("结果未知"))
    }
}
