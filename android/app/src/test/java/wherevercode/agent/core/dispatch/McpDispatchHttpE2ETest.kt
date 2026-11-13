package wherevercode.agent.core.dispatch

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.core.dispatch.DispatchTestFixture.Companion.args
import wherevercode.agent.core.mcp.McpGuard

/**
 * 端到端：在 127.0.0.1 上真起 [McpDispatchHttpServer]，用真实 HTTP +
 * JSON-RPC 驱动派工全流程，证据写入 mcp-dispatch-e2e-*.log。
 */
@RunWith(RobolectricTestRunner::class)
class McpDispatchHttpE2ETest {
    private lateinit var f: DispatchTestFixture
    private lateinit var a: DispatchActors
    private var server: McpDispatchHttpServer? = null
    @Volatile private var slowCalls = 0

    @Before
    fun setUp() {
        f = DispatchTestFixture()
        a = DispatchActors(f.tokens)
    }

    @After
    fun tearDown() {
        server?.stop()
        f.close()
    }

    private fun start(readTimeoutMs: Int = 10_000, ratePerMinute: Int = 1000): McpDispatchHttpServer =
        McpDispatchHttpServer(
            f.service,
            readTimeoutMillis = readTimeoutMs,
            rateLimiter = McpGuard.RateLimiter(ratePerMinute),
            beforeHandle = { if (slowCalls > 0) { slowCalls--; Thread.sleep(1_500) } },
        ).start().also { server = it }

    @Test
    fun `full dispatch flow over real HTTP JSON-RPC`() {
        val srv = start()
        val log = E2ELog("http-flow")
        log.line("server listening on ${srv.url} (loopback only)")
        val dispatcher = McpE2EClient(srv.url, a.chatgptIssued.token, log, "chatgpt")
        val executor = McpE2EClient(srv.url, a.grokIssued.token, log, "grok-bot")

        val init = dispatcher.initialize().getJSONObject("result")
        assertEquals("2025-06-18", init.getString("protocolVersion"))
        assertEquals(McpDispatchProtocol.SERVER_NAME, init.getJSONObject("serverInfo").getString("name"))
        executor.initialize()

        val dTools = dispatcher.rpc("tools/list").getJSONObject("result").getJSONArray("tools")
        val dNames = (0 until dTools.length()).map { dTools.getJSONObject(it).getString("name") }.toSet()
        assertEquals(setOf("create_task", "assign_task", "get_task_status", "pause_task", "resume_task", "stop_task", "change_goal"), dNames)
        val eTools = executor.rpc("tools/list").getJSONObject("result").getJSONArray("tools")
        val eNames = (0 until eTools.length()).map { eTools.getJSONObject(it).getString("name") }.toSet()
        assertEquals(setOf("list_my_tasks", "claim_task", "report_progress", "submit_result", "get_task_status"), eNames)

        val created = dispatcher.tool("create_task", args("title" to "E2E 任务", "goal" to "实现并测试", "idempotency_key" to "e2e-create-0001"))
        assertFalse(created.getBoolean("_isError"))
        val taskId = created.getJSONObject("task").getString("task_id")
        dispatcher.tool("assign_task", args("task_id" to taskId, "agent_id" to "grok-bot"))
        assertEquals(1, executor.tool("list_my_tasks").getInt("count"))
        val claim = executor.tool("claim_task", args("task_id" to taskId, "idempotency_key" to "e2e-claim-0001"))
        val attempt = claim.getString("attempt_id")
        executor.tool("report_progress", args("task_id" to taskId, "attempt_id" to attempt, "message" to "50%", "percent" to 50))

        dispatcher.tool("pause_task", args("task_id" to taskId, "idempotency_key" to "e2e-pause-0001"))
        val paused = executor.tool("report_progress", args("task_id" to taskId, "attempt_id" to attempt, "message" to "x"))
        assertTrue(paused.getBoolean("_isError"))
        assertEquals("task_paused", paused.getString("error_code"))
        executor.tool("report_progress", args("task_id" to taskId, "attempt_id" to attempt, "ack" to "paused"))
        dispatcher.tool("resume_task", args("task_id" to taskId, "idempotency_key" to "e2e-resume-0001"))
        val goal = dispatcher.tool("change_goal", args("task_id" to taskId, "goal" to "实现、测试并写文档", "idempotency_key" to "e2e-goal-0001"))
        assertEquals(2L, goal.getJSONObject("task").getLong("goal_version"))
        val stale = executor.tool("submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 1, "result" to "old"))
        assertEquals("goal_changed", stale.getString("error_code"))
        val done = executor.tool("submit_result", args("task_id" to taskId, "attempt_id" to attempt, "goal_version" to 2, "result" to "完成：commit deadbeef，测试 10/10"))
        assertEquals("completed", done.getJSONObject("task").getString("state"))
        val status = dispatcher.tool("get_task_status", args("task_id" to taskId))
        assertEquals("completed", status.getJSONObject("task").getString("state"))

        // 第二个任务：停止后执行方的任何写入都被拒。
        val t2 = dispatcher.tool("create_task", args("title" to "将被停止", "goal" to "g", "assignee" to "grok-bot", "idempotency_key" to "e2e-create-0002"))
            .getJSONObject("task").getString("task_id")
        val att2 = executor.tool("claim_task", args("task_id" to t2)).getString("attempt_id")
        dispatcher.tool("stop_task", args("task_id" to t2, "reason" to "e2e stop", "idempotency_key" to "e2e-stop-0002"))
        assertEquals("task_stopped", executor.tool("report_progress", args("task_id" to t2, "attempt_id" to att2, "message" to "x")).getString("error_code"))
        assertEquals("task_stopped", executor.tool("submit_result", args("task_id" to t2, "attempt_id" to att2, "goal_version" to 1, "result" to "x")).getString("error_code"))
        assertEquals("cancelled", executor.tool("report_progress", args("task_id" to t2, "attempt_id" to att2, "ack" to "stopped")).getJSONObject("task").getString("state"))

        // 越权：执行方建任务、派工方认领。
        assertEquals("forbidden", executor.tool("create_task", args("title" to "x", "goal" to "y", "idempotency_key" to "e2e-evil-0001")).getString("error_code"))
        val unknown = dispatcher.rpc("tools/call", JSONObject().put("name", "agent_channel_post").put("arguments", JSONObject().put("text", "@grok create_task")))
        assertEquals(McpDispatchProtocol.INVALID_PARAMS, unknown.getJSONObject("error").getInt("code"))
        assertEquals(2, f.taskStore.listTasks().size)
        log.line("RESULT: full flow OK; tasks in TaskStore=${f.taskStore.listTasks().map { it.taskId + ":" + it.state }}")
        log.line("AUDIT: " + f.service.auditEntries().joinToString(" | ") { "${it.agentId} ${it.action} ${it.taskId} ok=${it.ok} ${it.errorCode ?: ""}" })
        assertFalse(log.file.readText().contains(a.chatgptIssued.token))
        assertFalse(log.file.readText().contains(a.grokIssued.token))
    }

    @Test
    fun `http transport rejects unauthenticated foreign-origin and malformed requests`() {
        val srv = start(ratePerMinute = 5)
        val log = E2ELog("http-security")
        val anon = McpE2EClient(srv.url, null, log, "anonymous")
        val bad = McpE2EClient(srv.url, "wcd_" + "A".repeat(43), log, "bad-token")
        val good = McpE2EClient(srv.url, a.chatgptIssued.token, log, "chatgpt")
        val ping = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping").toString()

        val r401 = anon.post(ping)
        assertEquals(401, r401.status)
        assertTrue(r401.headers["WWW-Authenticate"]!!.startsWith("Bearer"))
        assertEquals(401, bad.post(ping).status)
        // HttpURLConnection 不允许设置 Origin（受限头），用原始 socket 发。
        val originStatus = rawStatus(srv.port, "POST ${srv.path} HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
            "Origin: https://evil.example\r\nAuthorization: Bearer ${a.chatgptIssued.token}\r\n" +
            "Content-Type: application/json\r\nContent-Length: ${ping.length}\r\n\r\n$ping")
        log.line("chatgpt (Origin: https://evil.example) <- $originStatus")
        assertTrue(originStatus, originStatus.startsWith("HTTP/1.1 403"))
        assertEquals(405, good.post("", method = "GET").status)
        assertEquals(400, good.post("{not json").status)
        val batch = good.post("[$ping]")
        assertEquals(McpDispatchProtocol.INVALID_REQUEST, batch.json().getJSONObject("error").getInt("code"))
        val big = good.post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"pad\":\"" + "x".repeat(McpGuard.MAX_BODY_BYTES) + "\"}")
        assertEquals(413, big.status)
        // 限流：每分钟 5 次（本测试里已用掉若干次），超出 429。
        var limited = false
        repeat(10) { if (good.post(ping).status == 429) limited = true }
        assertTrue(limited)
        val authFails = f.service.auditEntries().filter { it.action == "mcp_dispatch.auth" }
        assertEquals(2, authFails.size)
        assertTrue(authFails.all { it.agentId == "anonymous" && !it.ok })
        log.line("RESULT: 401/403/405/400/413/429 verified; auth failures audited=${authFails.size}")
    }

    private fun rawStatus(port: Int, request: String): String = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        s.soTimeout = 5_000
        s.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
        s.getOutputStream().flush()
        BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: ""
    }

    @Test
    fun `client disconnect mid-request creates nothing and server keeps serving`() {
        val srv = start()
        val log = E2ELog("http-disconnect")
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
            .put("params", JSONObject().put("name", "create_task")
                .put("arguments", args("title" to "半截", "goal" to "g", "idempotency_key" to "drop-0001"))).toString()
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", srv.port), 2_000)
            val head = "POST ${srv.path} HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer ${a.chatgptIssued.token}\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n"
            s.getOutputStream().write(head.toByteArray())
            s.getOutputStream().write(body.substring(0, body.length / 2).toByteArray())
            s.getOutputStream().flush()
        } // 客户端在请求体中途断开
        log.line("client sent half a create_task body then closed the socket")
        Thread.sleep(300)
        assertEquals(0, f.taskStore.listTasks().size)
        val client = McpE2EClient(srv.url, a.chatgptIssued.token, log, "chatgpt")
        val retried = client.tool("create_task", args("title" to "半截", "goal" to "g", "idempotency_key" to "drop-0001"))
        assertFalse(retried.getBoolean("_isError"))
        assertEquals(1, f.taskStore.listTasks().size)
        log.line("RESULT: half request ignored, retry created exactly 1 task")
    }

    @Test
    fun `client timeout after server committed then retry with same key is idempotent`() {
        val srv = start()
        val log = E2ELog("http-timeout-retry")
        val impatient = McpE2EClient(srv.url, a.chatgptIssued.token, log, "chatgpt-impatient", readTimeoutMs = 300)
        slowCalls = 1 // 下一次 tools/call 服务端先卡 1.5 秒（模拟慢工具 / 网络抖动）
        val createArgs = args("title" to "超时重试", "goal" to "g", "idempotency_key" to "timeout-0001")
        try {
            impatient.tool("create_task", createArgs)
            fail("expected client read timeout")
        } catch (e: SocketTimeoutException) {
            log.line("client timed out after 300ms (server still processing)")
        }
        Thread.sleep(1_800)
        assertEquals(1, f.taskStore.listTasks().size) // 服务端已落库
        val patient = McpE2EClient(srv.url, a.chatgptIssued.token, log, "chatgpt-retry")
        val retry = patient.tool("create_task", createArgs)
        assertTrue(retry.getBoolean("replayed"))
        assertEquals(f.taskStore.listTasks().single().taskId, retry.getJSONObject("task").getString("task_id"))
        assertEquals(1, f.taskStore.listTasks().size)

        // 执行方：claim 超时后重试拿到同一 attempt。
        val taskId = retry.getJSONObject("task").getString("task_id")
        patient.tool("assign_task", args("task_id" to taskId, "agent_id" to "grok-bot"))
        val grokImpatient = McpE2EClient(srv.url, a.grokIssued.token, log, "grok-impatient", readTimeoutMs = 300)
        slowCalls = 1
        try {
            grokImpatient.tool("claim_task", args("task_id" to taskId, "idempotency_key" to "timeout-claim-1"))
            fail("expected client read timeout")
        } catch (_: SocketTimeoutException) {
            log.line("executor claim timed out client-side")
        }
        Thread.sleep(1_800)
        val grok = McpE2EClient(srv.url, a.grokIssued.token, log, "grok-retry")
        val again = grok.tool("claim_task", args("task_id" to taskId, "idempotency_key" to "timeout-claim-1"))
        assertEquals(f.taskStore.getTask(taskId)!!.attemptId, again.getString("attempt_id"))
        assertEquals(1, f.taskStore.getTask(taskId)!!.attempt)
        log.line("RESULT: timeouts + retries produced 1 task and 1 attempt")
    }

    @Test
    fun `slow client is dropped by read timeout without blocking others`() {
        val srv = start(readTimeoutMs = 500)
        val log = E2ELog("http-slow-client")
        val slow = Socket()
        slow.connect(InetSocketAddress("127.0.0.1", srv.port), 2_000)
        slow.getOutputStream().write("POST ${srv.path} HTTP/1.1\r\nHost: x\r\n".toByteArray()) // 头没发完就停
        val client = McpE2EClient(srv.url, a.chatgptIssued.token, log, "chatgpt")
        assertEquals(200, client.post(JSONObject().put("jsonrpc", "2.0").put("id", 9).put("method", "ping").toString()).status)
        slow.soTimeout = 3_000
        val closed = BufferedReader(InputStreamReader(slow.getInputStream())).read()
        assertEquals(-1, closed)
        slow.close()
        log.line("RESULT: stalled client closed by server after read timeout; others served")
        assertNotNull(server)
    }
}
