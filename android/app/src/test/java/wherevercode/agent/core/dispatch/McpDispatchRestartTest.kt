package wherevercode.agent.core.dispatch

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.dispatch.DispatchTestFixture.Companion.args

/**
 * App 重启恢复：任务（Room 文件库）、控制状态（停止/暂停待确认）、
 * 令牌哈希、任务归属、幂等记录、进度备注、审计都在重启后保留，
 * 停止围栏重启后依然生效。
 */
@RunWith(RobolectricTestRunner::class)
class McpDispatchRestartTest {
    private lateinit var dir: File
    private lateinit var dbName: String

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("dispatch-restart").toFile()
        dbName = "dispatch-restart-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.getApplication().deleteDatabase(dbName)
        dir.deleteRecursively()
    }

    private fun boot() = DispatchTestFixture(dbName, File(dir, "dispatch-state.json"), File(dir, "dispatch-audit.jsonl"))

    @Test
    fun `stop fence tokens idempotency and progress survive an app restart`() {
        val f1 = boot()
        val actors = DispatchActors(f1.tokens)
        val grokToken = actors.grokIssued.token
        val chatgptToken = actors.chatgptIssued.token
        val createArgs = args("title" to "重启测试", "goal" to "跨重启的任务", "idempotency_key" to "restart-create-1")
        val taskId = f1.call(actors.chatgpt, "create_task", createArgs).getJSONObject("task").getString("task_id")
        f1.call(actors.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "grok-bot"))
        val attempt = f1.call(actors.grok, "claim_task", args("task_id" to taskId)).getString("attempt_id")
        f1.call(actors.grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "message" to "进行中", "percent" to 30))
        f1.call(actors.chatgpt, "stop_task", args("task_id" to taskId, "idempotency_key" to "restart-stop-1"))
        f1.close()

        // ---- App 重启 ----
        val f2 = boot()
        val grok = f2.tokens.authenticate(grokToken)
        val chatgpt = f2.tokens.authenticate(chatgptToken)
        assertNotNull(grok); assertNotNull(chatgpt)
        val row = f2.taskStore.getTask(taskId)!!
        assertEquals("working", row.state)
        assertEquals(attempt, row.attemptId)
        // 停止围栏重启后仍然生效。
        assertEquals(DispatchErrors.TASK_STOPPED,
            f2.fail(grok!!, "submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "result" to "重启后偷交")).code)
        // 幂等记录重启后仍在：重试 create 不会建第二个任务。
        val replay = f2.call(chatgpt!!, "create_task", createArgs)
        assertTrue(replay.getBoolean("replayed"))
        assertEquals(taskId, replay.getJSONObject("task").getString("task_id"))
        assertEquals(1, f2.taskStore.listTasks().size)
        // 任务归属与进度备注重启后仍在。
        val status = f2.call(chatgpt, "get_task_status", args("task_id" to taskId))
        assertEquals("STOPPING", status.getJSONObject("task").getString("control_status"))
        assertEquals(1, status.getJSONArray("progress").length())
        // 执行方确认停止 → cancelled。
        val ack = f2.call(grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "ack" to "stopped"))
        assertEquals("cancelled", ack.getJSONObject("task").getString("state"))
        // 审计是追加式的：两次启动的记录都在同一文件里。
        val actions = f2.service.auditEntries().map { it.action }
        assertTrue(actions.count { it == "mcp_dispatch.create_task" } >= 2)
        assertTrue("mcp_dispatch.stop_task" in actions)
        f2.close()
    }

    @Test
    fun `paused task stays paused across restart and resumes afterwards`() {
        val f1 = boot()
        val actors = DispatchActors(f1.tokens)
        val taskId = f1.call(actors.chatgpt, "create_task",
            args("title" to "t", "goal" to "g", "assignee" to "grok-bot", "idempotency_key" to "restart-pause-1"))
            .getJSONObject("task").getString("task_id")
        val attempt = f1.call(actors.grok, "claim_task", args("task_id" to taskId)).getString("attempt_id")
        f1.call(actors.chatgpt, "pause_task", args("task_id" to taskId))
        f1.call(actors.grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "ack" to "paused"))
        val grokToken = actors.grokIssued.token
        val chatgptToken = actors.chatgptIssued.token
        f1.close()

        val f2 = boot()
        val grok = f2.tokens.authenticate(grokToken)!!
        val chatgpt = f2.tokens.authenticate(chatgptToken)!!
        assertEquals(DispatchErrors.TASK_PAUSED,
            f2.fail(grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "message" to "x")).code)
        f2.call(chatgpt, "resume_task", args("task_id" to taskId))
        // 断线重连后的执行方：重新 claim 拿回同一 attempt 继续。
        val again = f2.call(grok, "claim_task", args("task_id" to taskId))
        assertTrue(again.getBoolean("resumed"))
        assertEquals(attempt, again.getString("attempt_id"))
        val done = f2.call(grok, "submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "result" to "ok"))
        assertEquals("completed", done.getJSONObject("task").getString("state"))
        f2.close()
    }
}
