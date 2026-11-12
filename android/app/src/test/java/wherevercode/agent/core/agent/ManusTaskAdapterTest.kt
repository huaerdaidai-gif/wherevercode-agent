package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind

/**
 * Manus 云端任务型适配器测试（V5 Phase B C3，Manus 404 修复，GPT 8d8ed67e）。
 *
 * 先写失败回归测试，再修复。全部用假传输，不发真实请求、零计费调用。
 * 官方依据（2026-11-10 直读官方文档复核）：
 * - Base URL https://api.manus.ai；v2 为当前版，v1 已废弃。
 * - 认证：`x-manus-api-key` 请求头（或 OAuth2 Bearer，本 App 只用 API Key）。
 * - 只读验证：GET /v2/user.me（只读，不需要 OAuth scope）。
 * - Manus API 是任务型 API，没有 /models 端点；"Manus 2.0 Lite" 是任务
 *   档位 agent_profile（lite），不是 OpenAI model ID。
 */
class ManusTaskAdapterTest {

    private class FakeTransport(
        var handler: (ManusHttpRequest) -> ManusHttpResponse,
    ) : ManusTransport {
        val requests = mutableListOf<ManusHttpRequest>()
        override fun call(request: ManusHttpRequest): ManusHttpResponse {
            requests.add(request)
            return handler(request)
        }
    }

    private fun okUserMe(body: String = """{"ok":true,"request_id":"req_1","message":"ok","user_id":"u_123"}""") =
        ManusHttpResponse(200, body)

    private fun adapterReturning(response: ManusHttpResponse): Pair<ManusTaskAdapter, FakeTransport> {
        val fake = FakeTransport { response }
        return ManusTaskAdapter(fake) to fake
    }

    @Test
    fun `probe hits GET v2 user_me not models`() {
        val (adapter, fake) = adapterReturning(okUserMe())
        val result = adapter.testAuth("fake-key", "https://api.manus.ai")
        assertTrue(result is ManusResult.Success)
        assertEquals(1, fake.requests.size)
        val request = fake.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://api.manus.ai/v2/user.me", request.url)
        assertFalse("探针绝不能打 /models", request.url.contains("/models"))
        assertNull("user.me 是 GET，无请求体", request.body)
    }

    @Test
    fun `probe uses x-manus-api-key header not Bearer`() {
        val (adapter, fake) = adapterReturning(okUserMe())
        adapter.testAuth("fake-key", "https://api.manus.ai")
        val headers = fake.requests.single().headers
        assertEquals("fake-key", headers["x-manus-api-key"])
        assertFalse("Manus API Key 认证不用 Authorization 头", headers.containsKey("Authorization"))
    }

    @Test
    fun `probe 200 ok returns user id`() {
        val (adapter, _) = adapterReturning(okUserMe())
        val result = adapter.testAuth("fake-key", null)
        assertTrue(result is ManusResult.Success)
        assertEquals("u_123", (result as ManusResult.Success).value.userId)
    }

    @Test
    fun `probe 401 is Unauthorized`() {
        val (adapter, _) = adapterReturning(
            ManusHttpResponse(401, """{"ok":false,"error":{"code":"unauthenticated","message":"bad key"}}"""),
        )
        val result = adapter.testAuth("bad-key", null)
        assertEquals(ManusResult.Failure(ManusError.Unauthorized), result)
    }

    @Test
    fun `probe 403 is Forbidden`() {
        val (adapter, _) = adapterReturning(
            ManusHttpResponse(403, """{"ok":false,"error":{"code":"permission_denied","message":"no scope"}}"""),
        )
        val result = adapter.testAuth("fake-key", null)
        assertEquals(ManusResult.Failure(ManusError.Forbidden), result)
    }

    @Test
    fun `probe 404 is NotFound`() {
        val (adapter, _) = adapterReturning(
            ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such resource"}}"""),
        )
        val result = adapter.testAuth("fake-key", null)
        assertEquals(ManusResult.Failure(ManusError.NotFound), result)
    }

    @Test
    fun `probe 429 is RateLimited`() {
        val (adapter, _) = adapterReturning(
            ManusHttpResponse(429, """{"ok":false,"error":{"code":"rate_limited","message":"too many"}}"""),
        )
        val result = adapter.testAuth("fake-key", null)
        assertEquals(ManusResult.Failure(ManusError.RateLimited), result)
    }

    @Test
    fun `probe network exception is NetworkError`() {
        val fake = FakeTransport { throw RuntimeException("timeout") }
        val result = ManusTaskAdapter(fake).testAuth("fake-key", null)
        assertTrue(result is ManusResult.Failure)
        assertTrue((result as ManusResult.Failure).error is ManusError.NetworkError)
    }

    @Test
    fun `baseUrl with v2 suffix still probes v2 user_me once`() {
        val (adapter, fake) = adapterReturning(okUserMe())
        adapter.testAuth("fake-key", "https://api.manus.ai/v2")
        assertEquals("https://api.manus.ai/v2/user.me", fake.requests.single().url)
    }

    @Test
    fun `blank baseUrl defaults to api manus ai`() {
        val (adapter, fake) = adapterReturning(okUserMe())
        adapter.testAuth("fake-key", null)
        assertEquals("https://api.manus.ai/v2/user.me", fake.requests.single().url)
    }

    @Test
    fun `http baseUrl is rejected without a call`() {
        val fake = FakeTransport { okUserMe() }
        val result = ManusTaskAdapter(fake).testAuth("fake-key", "http://api.manus.ai")
        assertTrue(result is ManusResult.Failure)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `agentProfileFor maps Manus 2_0 Lite to lite`() {
        assertEquals("lite", ManusTaskAdapter.agentProfileFor("Manus 2.0 Lite"))
        assertEquals("lite", ManusTaskAdapter.agentProfileFor("2.0-lite"))
        assertEquals("lite", ManusTaskAdapter.agentProfileFor("lite"))
        assertEquals("standard", ManusTaskAdapter.agentProfileFor("standard"))
        assertEquals("standard", ManusTaskAdapter.agentProfileFor("2.0"))
        assertEquals("max", ManusTaskAdapter.agentProfileFor("max"))
        assertEquals("max-ultra", ManusTaskAdapter.agentProfileFor("Manus 2.0 Max Ultra"))
        assertNull(ManusTaskAdapter.agentProfileFor(null))
        assertNull(ManusTaskAdapter.agentProfileFor(""))
        assertNull(ManusTaskAdapter.agentProfileFor("   "))
    }

    @Test
    fun `agentProfileFor never passes an unknown model id through`() {
        // "Manus 2.0 Lite" 是档位名，不是 model ID；未知值（如 gpt-5）一律
        // 映射为 null（调用方省略该字段、走服务端默认），绝不原样传入。
        assertNull(ManusTaskAdapter.agentProfileFor("gpt-5"))
        assertNull(ManusTaskAdapter.agentProfileFor("deepseek-flash"))
        val lite = ManusTaskAdapter.agentProfileFor("Manus 2.0 Lite")
        assertFalse(lite == "Manus 2.0 Lite")
        assertFalse(lite == "2.0 Lite")
    }

    @Test
    fun `isManusRecord detects adapterId and host`() {
        fun record(adapterId: String, baseUrl: String?) = AgentRecord(
            agentId = "manus-1",
            displayName = "Manus",
            kind = AgentKind.CLOUD,
            access = AgentAccess.API,
            brainKind = BrainKind.MODEL_API,
            adapterId = adapterId,
            baseUrl = baseUrl,
            model = "Manus 2.0 Lite",
            authRef = "key:manus-1",
        )
        assertTrue(ManusTaskAdapter.isManusRecord(record("manus", null)))
        assertTrue(ManusTaskAdapter.isManusRecord(record("deepseek", "https://api.manus.ai")))
        assertTrue(ManusTaskAdapter.isManusRecord(record("deepseek", "https://api.manus.ai/v2")))
        assertFalse(ManusTaskAdapter.isManusRecord(record("deepseek", "https://api.deepseek.com")))
        assertFalse(ManusTaskAdapter.isManusRecord(record("deepseek", "https://api.manus.ai.evil.com")))
        assertFalse(ManusTaskAdapter.isManusRecord(record("deepseek", null)))
    }

    @Test
    fun `defaultManusProbe without secret returns InvalidArgument`() {
        val result = defaultManusProbe("https://api.manus.ai", null)
        assertTrue(result is ManusResult.Failure)
        assertTrue((result as ManusResult.Failure).error is ManusError.InvalidArgument)
    }

    // ---- 任务接口（提交 2）：创建 / 查询 / 继续 / 停止 / 轮询 ----
    // 全部用假传输，绝不真实调用、零计费调用。

    private fun createdTaskBody(taskId: String = "t_123") =
        """{"ok":true,"request_id":"req_2","task_id":"$taskId","task_title":"T","task_url":"https://manus.im/app/$taskId"}"""

    private fun detailBody(status: String, backgroundJobs: Boolean = false) =
        """{"ok":true,"request_id":"req_3","task":{"id":"t_123","status":"$status","title":"T","has_running_background_jobs":$backgroundJobs}}"""

    private fun jsonOf(request: ManusHttpRequest) = org.json.JSONObject(request.body ?: "{}")

    @Test
    fun `createTask posts task_create with lite profile`() {
        val fake = FakeTransport { ManusHttpResponse(200, createdTaskBody()) }
        val adapter = ManusTaskAdapter(fake)
        val result = adapter.createTask("fake-key", null, "做一个计算器", agentProfile = "Manus 2.0 Lite")
        assertTrue(result is ManusResult.Success)
        assertEquals("t_123", (result as ManusResult.Success).value.taskId)
        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.manus.ai/v2/task.create", request.url)
        assertEquals("fake-key", request.headers["x-manus-api-key"])
        assertFalse(request.headers.containsKey("Authorization"))
        val body = jsonOf(request)
        assertEquals("做一个计算器", body.getJSONObject("message").getString("content"))
        assertEquals("lite", body.getString("agent_profile"))
        assertFalse("2.0 Lite 是档位，绝不能当 model 字段传入", body.has("model"))
    }

    @Test
    fun `createTask blank prompt makes no call`() {
        val fake = FakeTransport { ManusHttpResponse(200, createdTaskBody()) }
        val result = ManusTaskAdapter(fake).createTask("fake-key", null, "   ")
        assertTrue(result is ManusResult.Failure)
        assertTrue((result as ManusResult.Failure).error is ManusError.InvalidArgument)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `createTask same operation id creates once`() {
        val fake = FakeTransport { ManusHttpResponse(200, createdTaskBody()) }
        val adapter = ManusTaskAdapter(fake)
        val first = adapter.createTask("fake-key", null, "做一个计算器", clientOperationId = "op-1")
        val second = adapter.createTask("fake-key", null, "做一个计算器", clientOperationId = "op-1")
        assertEquals(1, fake.requests.size)
        assertEquals(
            (first as ManusResult.Success).value.taskId,
            (second as ManusResult.Success).value.taskId,
        )
    }

    @Test
    fun `createTask different operation ids create twice`() {
        val fake = FakeTransport { ManusHttpResponse(200, createdTaskBody()) }
        val adapter = ManusTaskAdapter(fake)
        adapter.createTask("fake-key", null, "任务一", clientOperationId = "op-1")
        adapter.createTask("fake-key", null, "任务二", clientOperationId = "op-2")
        assertEquals(2, fake.requests.size)
    }

    @Test
    fun `createTask 401 is Unauthorized`() {
        val fake = FakeTransport {
            ManusHttpResponse(401, """{"ok":false,"error":{"code":"unauthenticated","message":"bad key"}}""")
        }
        val result = ManusTaskAdapter(fake).createTask("bad-key", null, "做一个计算器")
        assertEquals(ManusResult.Failure(ManusError.Unauthorized), result)
    }

    @Test
    fun `createTask 429 is RateLimited`() {
        val fake = FakeTransport {
            ManusHttpResponse(429, """{"ok":false,"error":{"code":"rate_limited","message":"too many"}}""")
        }
        val result = ManusTaskAdapter(fake).createTask("fake-key", null, "做一个计算器")
        assertEquals(ManusResult.Failure(ManusError.RateLimited), result)
    }

    @Test
    fun `createTask unknown profile is omitted`() {
        val fake = FakeTransport { ManusHttpResponse(200, createdTaskBody()) }
        ManusTaskAdapter(fake).createTask("fake-key", null, "做一个计算器", agentProfile = "gpt-5")
        assertFalse(jsonOf(fake.requests.single()).has("agent_profile"))
    }

    @Test
    fun `getTask parses running status`() {
        val fake = FakeTransport { ManusHttpResponse(200, detailBody("running")) }
        val result = ManusTaskAdapter(fake).getTask("fake-key", null, "t_123")
        assertTrue(result is ManusResult.Success)
        val status = (result as ManusResult.Success).value
        assertEquals("running", status.status)
        assertFalse(status.hasRunningBackgroundJobs)
        val request = fake.requests.single()
        assertEquals("GET", request.method)
        assertTrue(request.url.startsWith("https://api.manus.ai/v2/task.detail?task_id=t_123"))
    }

    @Test
    fun `getTask stopped with background jobs flagged`() {
        val fake = FakeTransport { ManusHttpResponse(200, detailBody("stopped", backgroundJobs = true)) }
        val result = ManusTaskAdapter(fake).getTask("fake-key", null, "t_123")
        assertTrue(result is ManusResult.Success)
        assertTrue((result as ManusResult.Success).value.hasRunningBackgroundJobs)
    }

    @Test
    fun `getTask 404 is NotFound`() {
        val fake = FakeTransport {
            ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such task"}}""")
        }
        val result = ManusTaskAdapter(fake).getTask("fake-key", null, "t_missing")
        assertEquals(ManusResult.Failure(ManusError.NotFound), result)
    }

    @Test
    fun `listMessages parses page and cursor`() {
        val pageBody = """{"ok":true,"task_id":"t_123","messages":[{"id":"m1","type":"assistant_message","content":"hello"}],"has_more":true,"next_cursor":"c2"}"""
        val fake = FakeTransport { ManusHttpResponse(200, pageBody) }
        val adapter = ManusTaskAdapter(fake)
        val first = adapter.listMessages("fake-key", null, "t_123")
        assertTrue(first is ManusResult.Success)
        val page = (first as ManusResult.Success).value
        assertEquals(1, page.messages.size)
        assertEquals("assistant_message", page.messages.single().type)
        assertEquals("hello", page.messages.single().text)
        assertTrue(page.hasMore)
        assertEquals("c2", page.nextCursor)
        assertFalse(fake.requests.single().url.contains("cursor="))
        adapter.listMessages("fake-key", null, "t_123", cursor = "c2")
        assertTrue(fake.requests.last().url.contains("cursor=c2"))
    }

    @Test
    fun `listMessages joins text parts`() {
        val pageBody = """{"ok":true,"task_id":"t_123","messages":[{"id":"m1","type":"assistant_message","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}],"has_more":false}"""
        val fake = FakeTransport { ManusHttpResponse(200, pageBody) }
        val result = ManusTaskAdapter(fake).listMessages("fake-key", null, "t_123")
        assertEquals("a\nb", (result as ManusResult.Success).value.messages.single().text)
    }

    @Test
    fun `listMessages reads nested assistant_message content from real v2 format`() {
        // 回归：官方 v2 task.listMessages 真实格式（2026-11-11 直读 live
        // 文档）把正文嵌在与 type 同名的对象里，顶层没有 content。
        // 旧实现只读顶层 content，真实响应里永远取不到正文，K70 上
        // Manus 发"在吗"后报"没有返回可显示的内容"。
        val pageBody = """{"ok":true,"task_id":"t_123","messages":[
            |{"id":"m3","type":"status_update","timestamp":3,"status_update":{"agent_status":"stopped","brief":"已完成","description":"任务已完成"}},
            |{"id":"m2","type":"assistant_message","timestamp":2,"assistant_message":{"content":"在的，有什么可以帮你的？"}},
            |{"id":"m1","type":"user_message","timestamp":1,"user_message":{"content":"在吗","message_type":"text"}}
            |],"has_more":false}""".trimMargin()
        val fake = FakeTransport { ManusHttpResponse(200, pageBody) }
        val result = ManusTaskAdapter(fake).listMessages("fake-key", null, "t_123")
        val messages = (result as ManusResult.Success).value.messages
        assertEquals(3, messages.size)
        assertEquals("已完成", messages[0].text)
        assertEquals("在的，有什么可以帮你的？", messages[1].text)
        assertEquals("在吗", messages[2].text)
    }

    @Test
    fun `listMessages reads error and explanation nested content`() {
        val pageBody = """{"ok":true,"task_id":"t_123","messages":[
            |{"id":"m2","type":"explanation","timestamp":2,"explanation":{"content":"正在整理结果"}},
            |{"id":"m1","type":"error_message","timestamp":1,"error_message":{"error_type":"internal","content":"服务内部错误"}}
            |],"has_more":false}""".trimMargin()
        val fake = FakeTransport { ManusHttpResponse(200, pageBody) }
        val result = ManusTaskAdapter(fake).listMessages("fake-key", null, "t_123")
        val messages = (result as ManusResult.Success).value.messages
        assertEquals("正在整理结果", messages[0].text)
        assertEquals("服务内部错误", messages[1].text)
    }

    @Test
    fun `sendMessage posts sendMessage`() {
        val fake = FakeTransport { ManusHttpResponse(200, """{"ok":true,"request_id":"req_4","task_id":"t_123"}""") }
        val result = ManusTaskAdapter(fake).sendMessage("fake-key", null, "t_123", "继续")
        assertTrue(result is ManusResult.Success)
        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.manus.ai/v2/task.sendMessage", request.url)
        val body = jsonOf(request)
        assertEquals("t_123", body.getString("task_id"))
        assertEquals("继续", body.getJSONObject("message").getString("content"))
    }

    @Test
    fun `stopTask posts stop`() {
        val fake = FakeTransport { ManusHttpResponse(200, """{"ok":true,"request_id":"req_5"}""") }
        val result = ManusTaskAdapter(fake).stopTask("fake-key", null, "t_123")
        assertTrue(result is ManusResult.Success)
        val request = fake.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.manus.ai/v2/task.stop", request.url)
        assertEquals("t_123", jsonOf(request).getString("task_id"))
    }

    private fun pollingAdapter(vararg detailBodies: String): Pair<ManusTaskAdapter, FakeTransport> {
        var index = 0
        val fake = FakeTransport { request ->
            if (request.url.contains("/v2/task.detail")) {
                val body = detailBodies[minOf(index, detailBodies.size - 1)]
                index += 1
                ManusHttpResponse(200, body)
            } else {
                ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"?"}}""")
            }
        }
        return ManusTaskAdapter(fake) to fake
    }

    @Test
    fun `poll returns Stopped after running`() {
        val (adapter, _) = pollingAdapter(detailBody("running"), detailBody("stopped"))
        var sleeps = 0
        val outcome = adapter.pollUntilTerminal(
            "fake-key", null, "t_123",
            pollIntervalMs = 5_000, timeoutMs = 60_000,
            sleeper = { sleeps += 1 },
        )
        assertEquals(ManusPollOutcome.Stopped(backgroundJobsRunning = false), outcome)
        assertEquals(1, sleeps)
    }

    @Test
    fun `poll stopped with background jobs returns Stopped true`() {
        val (adapter, _) = pollingAdapter(detailBody("stopped", backgroundJobs = true))
        val outcome = adapter.pollUntilTerminal("fake-key", null, "t_123", timeoutMs = 60_000, sleeper = {})
        assertEquals(ManusPollOutcome.Stopped(backgroundJobsRunning = true), outcome)
    }

    @Test
    fun `poll waiting returns Waiting`() {
        val (adapter, _) = pollingAdapter(detailBody("waiting"))
        val outcome = adapter.pollUntilTerminal("fake-key", null, "t_123", timeoutMs = 60_000, sleeper = {})
        assertEquals(ManusPollOutcome.Waiting, outcome)
    }

    @Test
    fun `poll error returns Errored`() {
        val (adapter, _) = pollingAdapter(detailBody("error"))
        val outcome = adapter.pollUntilTerminal("fake-key", null, "t_123", timeoutMs = 60_000, sleeper = {})
        assertTrue(outcome is ManusPollOutcome.Errored)
    }

    @Test
    fun `poll timeout returns Timeout after one poll`() {
        val (adapter, fake) = pollingAdapter(detailBody("running"))
        val outcome = adapter.pollUntilTerminal(
            "fake-key", null, "t_123",
            pollIntervalMs = 5_000, timeoutMs = 0, sleeper = { throw AssertionError("超时不应再睡") },
        )
        assertEquals(ManusPollOutcome.Timeout, outcome)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `poll cancelled returns Cancelled`() {
        val (adapter, fake) = pollingAdapter(detailBody("running"))
        val outcome = adapter.pollUntilTerminal(
            "fake-key", null, "t_123",
            timeoutMs = 60_000, sleeper = {},
            isCancelled = { true },
        )
        assertEquals(ManusPollOutcome.Cancelled, outcome)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun `poll failed request returns Failed`() {
        val fake = FakeTransport {
            ManusHttpResponse(401, """{"ok":false,"error":{"code":"unauthenticated","message":"bad key"}}""")
        }
        val outcome = ManusTaskAdapter(fake).pollUntilTerminal("bad-key", null, "t_123", timeoutMs = 60_000, sleeper = {})
        assertEquals(ManusPollOutcome.Failed(ManusError.Unauthorized), outcome)
    }

    @Test
    fun `timeout error describes in Chinese`() {
        assertTrue(ManusError.Timeout.describe().contains("超时"))
    }

    /**
     * V5 修复（2026-11-11）：官方 v2 没有 file.list 端点，任务产物以
     * attachments[] 挂在 listMessages 的 assistant_message 上。旧解析
     * 只取 id/type/text，产物全丢，App 里永远看不见 Manus 产的文件。
     */
    @Test
    fun `listMessages parses attachments on assistant_message`() {
        val pageBody = """{"ok":true,"task_id":"t_123","messages":[
            |{"id":"m2","type":"assistant_message","timestamp":2,"assistant_message":{"content":"报告已生成。","attachments":[{"type":"file","filename":"report.md","url":"https://manus.im/dl/abc?x=1","path":"/home/user/report.md","content_type":"text/markdown","file_uid":"f_1","version_uid":"v_1"}]}},
            |{"id":"m1","type":"user_message","timestamp":1,"user_message":{"content":"写个报告","message_type":"text"}}
            |],"has_more":false}""".trimMargin()
        val fake = FakeTransport { ManusHttpResponse(200, pageBody) }
        val result = ManusTaskAdapter(fake).listMessages("fake-key", null, "t_123")
        val messages = (result as ManusResult.Success).value.messages
        val attachments = messages[0].attachments
        assertEquals(1, attachments.size)
        val file = attachments.single()
        assertEquals("report.md", file.filename)
        assertEquals("https://manus.im/dl/abc?x=1", file.url)
        assertEquals("/home/user/report.md", file.path)
        assertEquals("f_1", file.fileUid)
        assertEquals("v_1", file.versionUid)
        assertEquals("report.md", file.displayName())
        assertEquals("f_1", file.dedupeKey())
        assertTrue(messages[1].attachments.isEmpty())
    }

    /** V5 修复（2026-11-11）：tool_used 的工具名/状态/详情、status_update 的 agent_status 一并解析，供流式工具卡用。 */
    @Test
    fun `listMessages parses tool_used name status and detail`() {
        val pageBody = """{"ok":true,"task_id":"t_123","messages":[
            |{"id":"m2","type":"tool_used","timestamp":2,"tool_used":{"tool":"shell","status":"success","brief":"已运行 cat","description":"读取文件"}},
            |{"id":"m1","type":"status_update","timestamp":1,"status_update":{"agent_status":"running","brief":"正在执行","description":"执行中"}}
            |],"has_more":false}""".trimMargin()
        val fake = FakeTransport { ManusHttpResponse(200, pageBody) }
        val result = ManusTaskAdapter(fake).listMessages("fake-key", null, "t_123")
        val messages = (result as ManusResult.Success).value.messages
        assertEquals("shell", messages[0].toolName)
        assertEquals("success", messages[0].toolStatus)
        assertEquals("读取文件", messages[0].toolDetail)
        assertEquals("已运行 cat", messages[0].text)
        assertEquals("running", messages[1].agentStatus)
        assertEquals("正在执行", messages[1].text)
    }
}
