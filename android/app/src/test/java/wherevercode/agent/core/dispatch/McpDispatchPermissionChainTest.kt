package wherevercode.agent.core.dispatch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.core.dispatch.DispatchTestFixture.Companion.args
import wherevercode.agent.core.task.TaskRecord

/**
 * App side of create_task / assign_task / get_task_status permission checks
 * and the claim → progress → deliver chain (V5 MCP dispatch).
 */
@RunWith(RobolectricTestRunner::class)
class McpDispatchPermissionChainTest {
    private lateinit var f: DispatchTestFixture
    private lateinit var a: DispatchActors
    private var now = 0L

    @Before
    fun setUp() {
        now = System.currentTimeMillis() / 1000
        f = DispatchTestFixture(nowEpochSeconds = { now })
        a = DispatchActors(f.tokens)
    }

    @After
    fun tearDown() = f.close()

    private fun create(by: DispatchPrincipal = a.chatgpt, key: String = "perm-create-1"): String =
        f.call(by, "create_task", args("title" to "t", "goal" to "修复登录", "idempotency_key" to key))
            .getJSONObject("task").getString("task_id")

    private fun events(taskId: String) = f.db.taskEventDao().listByTask(taskId, 100)

    @Test
    fun `owner cannot assign an App-native task over MCP and the row is untouched`() {
        f.taskStore.createTask(TaskRecord(taskId = "local-native-1", title = "本机任务", state = "queued"))
        f.taskStore.createTask(TaskRecord(taskId = "mcp-chat-like", title = "chat", state = "queued", sessionId = "s1"))
        assertEquals(DispatchErrors.FORBIDDEN,
            f.fail(a.owner, "assign_task", args("task_id" to "local-native-1", "agent_id" to "grok-bot")).code)
        assertNull(f.taskStore.getTask("local-native-1")!!.assignedAgentId)
        assertEquals(DispatchErrors.FORBIDDEN,
            f.fail(a.owner, "assign_task", args("task_id" to "mcp-chat-like", "agent_id" to "grok-bot")).code)
        assertNull(f.taskStore.getTask("mcp-chat-like")!!.assignedAgentId)
        // The owner can still assign an MCP-created task.
        val taskId = create()
        assertEquals("grok-bot",
            f.call(a.owner, "assign_task", args("task_id" to taskId, "agent_id" to "grok-bot"))
                .getJSONObject("task").getString("assigned_agent_id"))
    }

    @Test
    fun `executor status shows only its own progress while dispatcher sees all`() {
        val taskId = create()
        f.call(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "grok-bot"))
        val first = f.call(a.grok, "claim_task", args("task_id" to taskId)).getString("attempt_id")
        f.call(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to first, "message" to "grok 进度"))
        // Lease lapses, task is requeued and reassigned to another executor.
        f.taskStore.expireLeases(now + 10_000)
        f.call(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "codex"))
        val second = f.call(a.codex, "claim_task", args("task_id" to taskId)).getString("attempt_id")
        f.call(a.codex, "report_progress", args("task_id" to taskId, "attempt_id" to second, "message" to "codex 进度"))

        val codexView = f.call(a.codex, "get_task_status", args("task_id" to taskId)).getJSONArray("progress")
        assertEquals(1, codexView.length())
        assertEquals("codex 进度", codexView.getJSONObject(0).getString("message"))
        assertEquals(2, f.call(a.chatgpt, "get_task_status", args("task_id" to taskId)).getJSONArray("progress").length())
        assertEquals(2, f.call(a.owner, "get_task_status", args("task_id" to taskId)).getJSONArray("progress").length())
        // The previous assignee loses read access entirely.
        assertEquals(DispatchErrors.FORBIDDEN, f.fail(a.grok, "get_task_status", args("task_id" to taskId)).code)
    }

    @Test
    fun `status flags an assignee whose execute credential was revoked`() {
        val taskId = create()
        assertTrue(f.call(a.chatgpt, "get_task_status", args("task_id" to taskId)).getJSONObject("task").isNull("assignee_active"))
        f.call(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "codex"))
        assertTrue(f.call(a.chatgpt, "get_task_status", args("task_id" to taskId)).getJSONObject("task").getBoolean("assignee_active"))
        f.tokens.revokePrincipal("codex")
        assertFalse(f.call(a.chatgpt, "get_task_status", args("task_id" to taskId)).getJSONObject("task").getBoolean("assignee_active"))
        // Dispatcher can reassign the unclaimed task to a live executor.
        f.call(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "grok-bot"))
        assertTrue(f.call(a.chatgpt, "get_task_status", args("task_id" to taskId)).getJSONObject("task").getBoolean("assignee_active"))
    }

    @Test
    fun `claim to deliver chain records claim and delivery events and dispatcher reads result`() {
        val taskId = create()
        f.call(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "grok-bot"))
        val claimed = f.call(a.grok, "claim_task", args("task_id" to taskId))
        val attempt = claimed.getString("attempt_id")
        // Claim retry is a resume, not a second claim event.
        f.call(a.grok, "claim_task", args("task_id" to taskId))
        f.call(a.grok, "submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "result" to "commit abc"))
        val kinds = events(taskId).map { it.kind }
        assertEquals(1, kinds.count { it == McpDispatchService.EVENT_MCP_CLAIMED })
        assertEquals(1, kinds.count { it == McpDispatchService.EVENT_MCP_DELIVERED })
        val delivered = events(taskId).first { it.kind == McpDispatchService.EVENT_MCP_DELIVERED }
        assertEquals("agent:grok-bot", delivered.actor)
        assertFalse("result body never goes into the event", delivered.reason.orEmpty().contains("abc"))
        val st = f.call(a.chatgpt, "get_task_status", args("task_id" to taskId)).getJSONObject("task")
        assertEquals("completed", st.getString("state"))
        assertEquals("commit abc", st.getString("result"))
        // After delivery nothing more can be written or claimed.
        assertEquals(DispatchErrors.TASK_TERMINAL, f.fail(a.grok, "claim_task", args("task_id" to taskId)).code)
        assertEquals(DispatchErrors.TASK_TERMINAL,
            f.fail(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "codex")).code)
    }

    @Test
    fun `failed delivery is visible to dispatcher as error and recorded`() {
        val taskId = create()
        f.call(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "hand-mac"))
        val attempt = f.call(a.hand, "claim_task", args("task_id" to taskId)).getString("attempt_id")
        f.call(a.hand, "submit_result",
            args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "status" to "failed", "result" to "编译失败"))
        val st = f.call(a.chatgpt, "get_task_status", args("task_id" to taskId)).getJSONObject("task")
        assertEquals("failed", st.getString("state"))
        assertEquals("编译失败", st.getString("error"))
        val delivered = events(taskId).single { it.kind == McpDispatchService.EVENT_MCP_DELIVERED }
        assertEquals("device:hand-mac", delivered.actor)
        assertTrue(delivered.reason.orEmpty().contains("failed"))
    }

    @Test
    fun `executor cannot deliver before claiming or with a lapsed lease`() {
        val taskId = create()
        f.call(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "grok-bot"))
        assertEquals(DispatchErrors.STALE_ATTEMPT,
            f.fail(a.grok, "submit_result", args("task_id" to taskId, "attempt_id" to "guess-attempt", "goal_version" to 1, "result" to "x")).code)
        val attempt = f.call(a.grok, "claim_task", args("task_id" to taskId)).getString("attempt_id")
        now += 10_000 // lease lapses without renewal
        assertEquals(DispatchErrors.STALE_ATTEMPT,
            f.fail(a.grok, "submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "result" to "x")).code)
        assertTrue(events(taskId).none { it.kind == McpDispatchService.EVENT_MCP_DELIVERED })
        assertEquals("working", f.taskStore.getTask(taskId)!!.state)
    }
}
