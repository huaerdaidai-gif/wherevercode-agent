package wherevercode.agent.core.dispatch

import java.io.File
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.core.dispatch.DispatchTestFixture.Companion.args
import wherevercode.agent.core.security.auditEntryToJson
import wherevercode.agent.core.task.TaskRecord

@RunWith(RobolectricTestRunner::class)
class McpDispatchServiceTest {
    private lateinit var f: DispatchTestFixture
    private lateinit var a: DispatchActors

    @Before
    fun setUp() {
        f = DispatchTestFixture()
        a = DispatchActors(f.tokens)
    }

    @After
    fun tearDown() = f.close()

    private fun create(by: DispatchPrincipal = a.chatgpt, key: String = "create-0001", goal: String = "修复登录页崩溃"): String =
        f.call(by, "create_task", args("title" to "登录修复", "goal" to goal, "idempotency_key" to key))
            .getJSONObject("task").getString("task_id")

    private fun assign(taskId: String, to: String = "grok-bot", by: DispatchPrincipal = a.chatgpt) =
        f.call(by, "assign_task", args("task_id" to taskId, "agent_id" to to))

    private fun claim(taskId: String, by: DispatchPrincipal = a.grok): JSONObject =
        f.call(by, "claim_task", args("task_id" to taskId))

    // ---- happy path ---------------------------------------------------------

    @Test
    fun `happy path create assign list claim progress submit status`() {
        val created = f.call(a.chatgpt, "create_task",
            args("title" to "登录修复", "goal" to "修复登录页崩溃", "idempotency_key" to "create-0001"))
        val task = created.getJSONObject("task")
        val taskId = task.getString("task_id")
        assertEquals("queued", task.getString("state"))
        assertEquals("agent:chatgpt", task.getString("created_by"))
        // 落在 App 的 TaskStore（同一张 tasks 表），不是平行任务库。
        val row = f.taskStore.getTask(taskId)!!
        assertEquals("修复登录页崩溃", row.description)

        val assigned = assign(taskId).getJSONObject("task")
        assertEquals("grok-bot", assigned.getString("assigned_agent_id"))

        val mine = f.call(a.grok, "list_my_tasks")
        assertEquals(1, mine.getInt("count"))
        assertEquals(taskId, mine.getJSONArray("tasks").getJSONObject(0).getString("task_id"))
        assertEquals(0, f.call(a.codex, "list_my_tasks").getInt("count"))

        val claimed = claim(taskId)
        val attemptId = claimed.getString("attempt_id")
        assertEquals("working", claimed.getJSONObject("task").getString("state"))
        assertEquals(1L, claimed.getLong("goal_version"))
        assertEquals("grok-bot", f.taskStore.getTask(taskId)!!.leaseOwner)

        val progress = f.call(a.grok, "report_progress",
            args("task_id" to taskId, "attempt_id" to attemptId, "message" to "定位到空指针", "percent" to 50, "goal_version" to 1))
        assertEquals("continue", progress.getString("directive"))

        val submitted = f.call(a.grok, "submit_result",
            args("task_id" to taskId, "attempt_id" to attemptId, "goal_version" to 1, "result" to "commit abc123；单测 12/12 通过"))
        assertEquals("completed", submitted.getJSONObject("task").getString("state"))

        val status = f.call(a.chatgpt, "get_task_status", args("task_id" to taskId))
        assertEquals("completed", status.getJSONObject("task").getString("state"))
        assertEquals("commit abc123；单测 12/12 通过", status.getJSONObject("task").getString("result"))
        assertEquals(1, status.getJSONArray("progress").length())
        assertEquals(0, f.call(a.grok, "list_my_tasks").getInt("count"))

        // 每次调用一行审计，正文/目标不进审计。
        val actions = f.service.auditEntries().map { it.action }
        listOf("create_task", "assign_task", "list_my_tasks", "claim_task", "report_progress", "submit_result", "get_task_status")
            .forEach { assertTrue("audit has $it", "mcp_dispatch.$it" in actions) }
        val auditText = f.service.auditEntries().joinToString("\n") { auditEntryToJson(it).toString() }
        assertFalse(auditText.contains("修复登录页崩溃"))
        assertFalse(auditText.contains("abc123"))
        assertTrue(auditText.contains("agent:chatgpt"))
    }

    @Test
    fun `create with assignee assigns immediately and owner sees everything`() {
        val t = f.call(a.chatgpt, "create_task",
            args("title" to "t", "goal" to "g", "assignee" to "GROK-BOT", "idempotency_key" to "create-assignee"))
            .getJSONObject("task")
        assertEquals("grok-bot", t.getString("assigned_agent_id"))
        val st = f.call(a.owner, "get_task_status", args("task_id" to t.getString("task_id")))
        assertEquals("queued", st.getJSONObject("task").getString("state"))
    }

    // ---- authorization ------------------------------------------------------

    @Test
    fun `executor-only agent and device cannot dispatch and do not see dispatch tools`() {
        assertEquals(DispatchErrors.FORBIDDEN,
            f.fail(a.grok, "create_task", args("title" to "x", "goal" to "y", "idempotency_key" to "evil-0001")).code)
        val taskId = create()
        assertEquals(DispatchErrors.FORBIDDEN, f.fail(a.hand, "stop_task", args("task_id" to taskId)).code)
        assertEquals(DispatchErrors.FORBIDDEN, f.fail(a.grok, "assign_task", args("task_id" to taskId, "agent_id" to "grok-bot")).code)
        val grokTools = f.service.toolsFor(a.grok).map { it.name }.toSet()
        assertFalse("create_task" in grokTools)
        assertFalse("stop_task" in grokTools)
        assertTrue("claim_task" in grokTools)
        val chatgptTools = f.service.toolsFor(a.chatgpt).map { it.name }.toSet()
        assertTrue("create_task" in chatgptTools)
        assertFalse("claim_task" in chatgptTools)
        assertEquals(DispatchTools.ALL.size, f.service.toolsFor(a.owner).size)
        assertEquals(0, f.taskStore.listTasks().count { it.title == "x" })
    }

    @Test
    fun `device role can never be issued dispatch capability`() {
        assertThrows(IllegalArgumentException::class.java) {
            f.tokens.issue("rogue-device", DispatchRole.DEVICE, setOf(DispatchCapability.DISPATCH))
        }
    }

    @Test
    fun `dispatcher cannot touch another dispatchers task or app-native tasks`() {
        val taskId = create()
        listOf("get_task_status", "pause_task", "stop_task", "resume_task").forEach { tool ->
            assertEquals(tool, DispatchErrors.FORBIDDEN, f.fail(a.muse, tool, args("task_id" to taskId)).code)
        }
        assertEquals(DispatchErrors.FORBIDDEN, f.fail(a.muse, "change_goal", args("task_id" to taskId, "goal" to "hijack")).code)
        assertEquals(DispatchErrors.FORBIDDEN, f.fail(a.muse, "assign_task", args("task_id" to taskId, "agent_id" to "codex")).code)
        // App 自己的任务（不是经 MCP 建的）：只有 owner 能看。
        f.taskStore.createTask(TaskRecord(taskId = "app-native-1", title = "聊天任务", state = "queued"))
        assertEquals(DispatchErrors.FORBIDDEN, f.fail(a.chatgpt, "get_task_status", args("task_id" to "app-native-1")).code)
        assertEquals("queued", f.call(a.owner, "get_task_status", args("task_id" to "app-native-1")).getJSONObject("task").getString("state"))
        assertEquals("修复登录页崩溃", f.taskStore.getTask(taskId)!!.description)
    }

    @Test
    fun `executor cannot claim or write another agents task or an unassigned task`() {
        val taskId = create()
        assertEquals(DispatchErrors.FORBIDDEN, f.fail(a.grok, "claim_task", args("task_id" to taskId)).code)
        assign(taskId)
        assertEquals(DispatchErrors.FORBIDDEN, f.fail(a.codex, "claim_task", args("task_id" to taskId)).code)
        assertEquals(DispatchErrors.FORBIDDEN, f.fail(a.codex, "get_task_status", args("task_id" to taskId)).code)
        val attempt = claim(taskId).getString("attempt_id")
        assertEquals(DispatchErrors.FORBIDDEN,
            f.fail(a.codex, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "message" to "x")).code)
        assertEquals(DispatchErrors.FORBIDDEN,
            f.fail(a.codex, "submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "result" to "x")).code)
        // 执行方能查指派给自己的任务，但看不到别人的 attempt_id。
        assertTrue(f.call(a.grok, "get_task_status", args("task_id" to taskId)).getJSONObject("task").has("attempt_id"))
        assertFalse(f.call(a.chatgpt, "get_task_status", args("task_id" to taskId)).getJSONObject("task").has("attempt_id"))
    }

    @Test
    fun `assign only to registered executors`() {
        val taskId = create()
        assertEquals(DispatchErrors.UNKNOWN_AGENT, f.fail(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "nobody")).code)
        assertEquals(DispatchErrors.UNKNOWN_AGENT, f.fail(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "muse")).code)
        f.tokens.revokePrincipal("codex")
        assertEquals(DispatchErrors.UNKNOWN_AGENT, f.fail(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "codex")).code)
        assertEquals("hand-mac", assign(taskId, to = "hand-mac").getJSONObject("task").getString("assigned_agent_id"))
    }

    @Test
    fun `revoked token no longer authenticates`() {
        assertNotNull(f.tokens.authenticate(a.grokIssued.token))
        f.tokens.revoke(a.grokIssued.tokenId)
        assertEquals(null, f.tokens.authenticate(a.grokIssued.token))
        assertEquals(null, f.tokens.authenticate(a.grokIssued.token + "x"))
        assertEquals(null, f.tokens.authenticate("Bearer " + a.chatgptIssued.token))
    }

    // ---- idempotency & concurrency ------------------------------------------

    @Test
    fun `duplicate create with same key creates one task and conflicting reuse is rejected`() {
        val first = f.call(a.chatgpt, "create_task", args("title" to "t", "goal" to "g", "idempotency_key" to "retry-key-1"))
        val second = f.call(a.chatgpt, "create_task", args("title" to "t", "goal" to "g", "idempotency_key" to "retry-key-1"))
        assertEquals(first.getJSONObject("task").getString("task_id"), second.getJSONObject("task").getString("task_id"))
        assertTrue(second.getBoolean("replayed"))
        assertEquals(1, f.taskStore.listTasks().size)
        assertEquals(DispatchErrors.IDEMPOTENCY_CONFLICT,
            f.fail(a.chatgpt, "create_task", args("title" to "t", "goal" to "DIFFERENT", "idempotency_key" to "retry-key-1")).code)
        // 不同主体的同名键互不影响。
        f.call(a.muse, "create_task", args("title" to "t", "goal" to "g", "idempotency_key" to "retry-key-1"))
        assertEquals(2, f.taskStore.listTasks().size)
        // create_task 必须带键；键格式受限。
        assertEquals(DispatchErrors.INVALID_ARGUMENT, f.fail(a.chatgpt, "create_task", args("title" to "t", "goal" to "g")).code)
        assertEquals(DispatchErrors.INVALID_ARGUMENT,
            f.fail(a.chatgpt, "create_task", args("title" to "t", "goal" to "g", "idempotency_key" to "bad key!")).code)
    }

    @Test
    fun `claim retry returns the same attempt and progress dedupes`() {
        val taskId = create(); assign(taskId)
        val c1 = f.call(a.grok, "claim_task", args("task_id" to taskId, "idempotency_key" to "claim-0001"))
        val c2 = f.call(a.grok, "claim_task", args("task_id" to taskId, "idempotency_key" to "claim-0001"))
        assertEquals(c1.getString("attempt_id"), c2.getString("attempt_id"))
        // 断线后不带键重新认领：返回当前 attempt（resumed），不开新 attempt。
        val c3 = f.call(a.grok, "claim_task", args("task_id" to taskId))
        assertTrue(c3.getBoolean("resumed"))
        assertEquals(c1.getString("attempt_id"), c3.getString("attempt_id"))
        assertEquals(1, f.taskStore.getTask(taskId)!!.attempt)
        val p = args("task_id" to taskId, "attempt_id" to c1.getString("attempt_id"), "message" to "step", "idempotency_key" to "prog-0001")
        f.call(a.grok, "report_progress", p)
        f.call(a.grok, "report_progress", JSONObject(p.toString()))
        assertEquals(1, f.call(a.chatgpt, "get_task_status", args("task_id" to taskId)).getJSONArray("progress").length())
    }

    @Test
    fun `expected_version mismatch is rejected without writing`() {
        val taskId = create()
        val v = f.taskStore.getTask(taskId)!!.version
        val e = f.fail(a.chatgpt, "assign_task", args("task_id" to taskId, "agent_id" to "grok-bot", "expected_version" to v + 5))
        assertEquals(DispatchErrors.VERSION_CONFLICT, e.code)
        assertEquals(v, e.data!!.getLong("current_version"))
        assertEquals(null, f.taskStore.getTask(taskId)!!.assignedAgentId)
        assign(taskId)
        assertEquals(DispatchErrors.VERSION_CONFLICT,
            f.fail(a.chatgpt, "change_goal", args("task_id" to taskId, "goal" to "new", "expected_version" to v)).code)
    }

    // ---- pause / resume / change goal ---------------------------------------

    @Test
    fun `pause resume and change goal flow with executor acknowledgements`() {
        val taskId = create(); assign(taskId)
        val attempt = claim(taskId).getString("attempt_id")
        val paused = f.call(a.chatgpt, "pause_task", args("task_id" to taskId, "reason" to "等评审"))
        assertEquals("PAUSING", paused.getString("control_status"))
        assertFalse(paused.getBoolean("confirmed"))
        val rejected = f.fail(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "message" to "继续写"))
        assertEquals(DispatchErrors.TASK_PAUSED, rejected.code)
        assertEquals("pause", rejected.data!!.getString("directive"))
        val ack = f.call(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "ack" to "paused"))
        assertEquals("PAUSED", ack.getJSONObject("task").getString("control_status"))
        assertEquals(DispatchErrors.TASK_PAUSED,
            f.fail(a.grok, "submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "result" to "x")).code)

        val resumed = f.call(a.chatgpt, "resume_task", args("task_id" to taskId))
        assertEquals("RUNNING", resumed.getString("control_status"))
        f.call(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "message" to "继续"))

        val changed = f.call(a.chatgpt, "change_goal", args("task_id" to taskId, "goal" to "改为：修复并补回归测试"))
        assertEquals(2L, changed.getJSONObject("task").getLong("goal_version"))
        assertEquals("改为：修复并补回归测试", f.taskStore.getTask(taskId)!!.description)
        val stale = f.fail(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "message" to "旧目标"))
        assertEquals(DispatchErrors.GOAL_CHANGED, stale.code)
        assertEquals(2L, stale.data!!.getLong("goal_version"))
        assertEquals(DispatchErrors.GOAL_CHANGED,
            f.fail(a.grok, "submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "result" to "x")).code)
        val done = f.call(a.grok, "submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 2, "result" to "按新目标完成"))
        assertEquals("completed", done.getJSONObject("task").getString("state"))
    }

    @Test
    fun `pause of a queued task blocks claim until resume`() {
        val taskId = create(); assign(taskId)
        val r = f.call(a.chatgpt, "pause_task", args("task_id" to taskId))
        assertEquals("PAUSED", r.getString("control_status"))
        assertTrue(r.getBoolean("confirmed"))
        assertEquals(DispatchErrors.TASK_PAUSED, f.fail(a.grok, "claim_task", args("task_id" to taskId)).code)
        f.call(a.chatgpt, "resume_task", args("task_id" to taskId))
        assertEquals("working", claim(taskId).getJSONObject("task").getString("state"))
    }

    // ---- stop fence ---------------------------------------------------------

    @Test
    fun `after stop every executor write is rejected until and after ack`() {
        val taskId = create(); assign(taskId)
        val attempt = claim(taskId).getString("attempt_id")
        val stop = f.call(a.chatgpt, "stop_task", args("task_id" to taskId, "reason" to "需求取消", "idempotency_key" to "stop-0001"))
        assertEquals("STOPPING", stop.getString("control_status"))
        assertFalse(stop.getBoolean("confirmed"))
        assertTrue(stop.getJSONObject("task").getBoolean("stop_pending"))
        assertTrue(f.call(a.chatgpt, "stop_task", args("task_id" to taskId, "reason" to "需求取消", "idempotency_key" to "stop-0001")).getBoolean("replayed"))

        val p = f.fail(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "message" to "还在写"))
        assertEquals(DispatchErrors.TASK_STOPPED, p.code)
        assertEquals("stop", p.data!!.getString("directive"))
        assertEquals(DispatchErrors.TASK_STOPPED,
            f.fail(a.grok, "submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "result" to "偷偷交")).code)
        assertEquals(null, f.taskStore.getTask(taskId)!!.result)

        val ack = f.call(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "ack" to "stopped"))
        assertEquals("cancelled", ack.getJSONObject("task").getString("state"))
        assertEquals(DispatchErrors.TASK_STOPPED,
            f.fail(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "message" to "x")).code)
        assertEquals(DispatchErrors.TASK_STOPPED,
            f.fail(a.grok, "submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "result" to "x")).code)
        assertEquals(DispatchErrors.TASK_TERMINAL, f.fail(a.grok, "claim_task", args("task_id" to taskId)).code)
        assertEquals("control_terminal_task", f.fail(a.chatgpt, "change_goal", args("task_id" to taskId, "goal" to "x")).code)
        assertEquals(null, f.taskStore.getTask(taskId)!!.result)
        assertEquals("cancelled", f.taskStore.getTask(taskId)!!.state)
        // ack 本身可重复（断线重发）。
        f.call(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "ack" to "stopped"))
    }

    @Test
    fun `stop of a queued task cancels at once`() {
        val taskId = create(); assign(taskId)
        val r = f.call(a.chatgpt, "stop_task", args("task_id" to taskId))
        assertTrue(r.getBoolean("confirmed"))
        assertEquals("cancelled", r.getJSONObject("task").getString("state"))
        assertEquals(DispatchErrors.TASK_TERMINAL, f.fail(a.grok, "claim_task", args("task_id" to taskId)).code)
        assertEquals(0, f.call(a.grok, "list_my_tasks").getInt("count"))
    }

    @Test
    fun `revoked task authorization fences executor writes`() {
        val taskId = create(); assign(taskId)
        val attempt = claim(taskId).getString("attempt_id")
        f.controlPlane.revoke(taskId, "user-revoke-1", "用户撤销")
        assertEquals(DispatchErrors.TASK_REVOKED,
            f.fail(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to attempt, "message" to "x")).code)
    }

    @Test
    fun `old attempt is fenced after lease expiry and reclaim`() {
        val taskId = create(); assign(taskId)
        val old = claim(taskId).getString("attempt_id")
        val expired = f.taskStore.expireLeases(System.currentTimeMillis() / 1000 + 10_000)
        assertEquals(listOf(taskId), expired)
        assertEquals("requeued", f.taskStore.getTask(taskId)!!.state)
        assign(taskId)
        val fresh = claim(taskId).getString("attempt_id")
        assertNotEquals(old, fresh)
        assertEquals(DispatchErrors.STALE_ATTEMPT,
            f.fail(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to old, "message" to "旧 attempt")).code)
        assertEquals(DispatchErrors.STALE_ATTEMPT,
            f.fail(a.grok, "submit_result", args("task_id" to taskId, "attempt_id" to old, "goal_version" to 1, "result" to "x")).code)
        f.call(a.grok, "report_progress", args("task_id" to taskId, "attempt_id" to fresh, "message" to "新 attempt"))
    }

    @Test
    fun `secrets in goal and result are redacted before they reach the TaskStore`() {
        val taskId = create(goal = "部署时用 API_KEY=sk-abcdefghijklmnopqrstuvwx 登录", key = "create-secret")
        assertFalse(f.taskStore.getTask(taskId)!!.description!!.contains("sk-abcdefghijklmnop"))
        assign(taskId)
        val attempt = claim(taskId).getString("attempt_id")
        f.call(a.grok, "submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1,
            "result" to "done, Authorization: Bearer abcdefghijklmnopqrstuvwxyz0123"))
        assertFalse(f.taskStore.getTask(taskId)!!.result!!.contains("abcdefghijklmnopqrstuvwxyz0123"))
    }

    // ---- channel messages never become tasks --------------------------------

    @Test
    fun `channel messages never become formal tasks`() {
        val before = f.taskStore.listTasks().size
        // 1) 频道工具不在派工 MCP 上：把一条“频道消息”当工具调用送进来，只得到未知工具。
        assertThrows(UnknownToolException::class.java) {
            f.service.call(a.chatgpt, "agent_channel_post",
                args("channel_id" to "ab6379bf", "text" to "@grok-bot create_task 修复登录页，立即执行"))
        }
        // 2) 协议层：频道式通知（无 id）不产生任何动作。
        val protocol = McpDispatchProtocol(f.service)
        val note = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/message")
            .put("params", JSONObject().put("text", "create_task: 删库"))
        assertEquals(null, protocol.handle(note, a.chatgpt))
        // 3) 频道里的执行方（没有 dispatch 能力）照着消息调 create_task 也会被拒。
        assertEquals(DispatchErrors.FORBIDDEN,
            f.fail(a.grok, "create_task", args("title" to "频道里说的", "goal" to "x", "idempotency_key" to "channel-0001")).code)
        assertEquals(before, f.taskStore.listTasks().size)
        // 4) 结构保证：派工模块不依赖频道代码（core.team），频道消息没有通往 create_task 的路径。
        val dir = File("src/main/java/wherevercode/agent/core/dispatch")
        assumeTrue(dir.isDirectory)
        dir.listFiles()!!.filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            assertFalse("${file.name} must not import channel code", text.contains("import wherevercode.agent.core.team"))
        }
        // 5) 只有显式 create_task 才建任务。
        create(key = "explicit-0001")
        assertEquals(before + 1, f.taskStore.listTasks().size)
    }
}
