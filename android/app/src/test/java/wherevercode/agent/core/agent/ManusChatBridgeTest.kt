package wherevercode.agent.core.agent

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind

/**
 * 阶段 C（V5 紧急开发）：Manus 聊天桥接——聊天消息 → 建 Manus 任务 →
 * 轮询 → 结果回填；多轮跟进同一任务。
 *
 * 先写失败测试再修。全部用假传输，不发真实请求、零计费调用、不要真实 Key。
 * 官方依据（2026-11-10 直读 live 官方文档复核）：
 * - 首条消息：POST /v2/task.create，存 task_id；
 *   跟进：POST /v2/task.sendMessage（task.sendMessage 页：
 *   "Sends a follow-up message to a task for multi-turn conversation.
 *   Use this after task.create to continue talking"）。
 * - 进度：GET /v2/task.detail（status running/stopped/waiting/error/deleted）；
 *   结果：GET /v2/task.listMessages 取最新文本。
 * - 额度：官方错误表无专用额度耗尽码；429 为 rate_limited，
 *   其他额度类错误以服务端原样 code + message 保留在失败文案里。
 */
class ManusChatBridgeTest {

    private class FakeTransport(
        var handler: (ManusHttpRequest) -> ManusHttpResponse,
    ) : ManusTransport {
        val requests = mutableListOf<ManusHttpRequest>()
        override fun call(request: ManusHttpRequest): ManusHttpResponse {
            requests.add(request)
            return handler(request)
        }
    }

    private class MemoryTaskIdStore : ManusTaskIdStore {
        val map = HashMap<String, String>()
        override fun get(conversationId: String): String? = map[conversationId]
        override fun put(conversationId: String, taskId: String) {
            map[conversationId] = taskId
        }
        override fun clear(conversationId: String) {
            map.remove(conversationId)
        }
    }

    private fun manusAgent() = AgentRecord(
        agentId = "manus",
        displayName = "Manus",
        kind = AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = BrainKind.MODEL_API,
        adapterId = "manus",
        baseUrl = "https://api.manus.ai",
        model = "standard",
        authRef = "manus:api-key",
    )

    private fun okJson(body: String) = ManusHttpResponse(200, body)

    /** 默认假服务端：建任务 → stopped → 一条回答；跟进/停止均成功。 */
    private fun scriptedHandler(status: String = "stopped"): (ManusHttpRequest) -> ManusHttpResponse =
        { request ->
            when {
                request.url.endsWith("/v2/task.create") ->
                    okJson("""{"ok":true,"task_id":"task-1","task_title":"hi","task_url":"https://manus.im/app/task-1"}""")
                request.url.contains("/v2/task.detail") ->
                    okJson("""{"ok":true,"task":{"id":"task-1","status":"$status","title":"hi","has_running_background_jobs":false}}""")
                request.url.contains("/v2/task.listMessages") ->
                    okJson("""{"ok":true,"task_id":"task-1","messages":[{"id":"m1","type":"message","content":"你好，我是 Manus。"}],"has_more":false}""")
                request.url.endsWith("/v2/task.sendMessage") -> okJson("""{"ok":true}""")
                request.url.endsWith("/v2/task.stop") -> okJson("""{"ok":true}""")
                else -> ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such"}}""")
            }
        }

    private fun bridge(
        fake: FakeTransport,
        store: MemoryTaskIdStore = MemoryTaskIdStore(),
        apiKey: String? = "fake-key-not-real",
        pollIntervalMs: Long = 5,
        timeoutMs: Long = 600_000,
        // 默认 0 = 不宽限（旧行为，终态立即认，测试跑得快）；需要测
        // 宽限的测试显式传生产值 30_000。
        terminalGraceMs: Long = 0,
    ) = ManusChatBridge(
        adapter = ManusTaskAdapter(fake),
        apiKeyProvider = { apiKey },
        taskIdStore = store,
        pollIntervalMs = pollIntervalMs,
        timeoutMs = timeoutMs,
        sleeper = {},
        terminalGraceMs = terminalGraceMs,
    )

    private fun resultText(events: List<ManusChatEvent>): String =
        events.filterIsInstance<ManusChatEvent.Result>().joinToString("") { it.text }

    private fun failedText(events: List<ManusChatEvent>): String =
        events.filterIsInstance<ManusChatEvent.Failed>().joinToString("\n") { it.message }

    @Test
    fun `first message creates a task polls to stopped and returns the answer`() = runBlocking {
        val fake = FakeTransport(scriptedHandler())
        val events = bridge(fake).runChat("conv-1", manusAgent(), "在吗").toList()

        assertTrue(resultText(events).contains("你好，我是 Manus。"))
        assertTrue(failedText(events).isEmpty())
        val creates = fake.requests.filter { it.url.endsWith("/v2/task.create") }
        assertEquals(1, creates.size)
        assertTrue(creates.single().body!!.contains("在吗"))
        assertTrue(fake.requests.any { it.url.contains("/v2/task.detail") })
        assertTrue(fake.requests.any { it.url.contains("/v2/task.listMessages") })
        assertTrue("进度事件应如实出现", events.any { it is ManusChatEvent.Progress })
    }

    @Test
    fun `follow-up reuses the same task via sendMessage not a new task`() = runBlocking {
        // 基线感知：listMessages 第 1 次是第 1 轮的轮询（回 m1），
        // 第 2 次是第 2 轮的基线（只有旧 m1），第 3 次起是第 2 轮的
        // 轮询（旧 m1 + 新 m2）。第 2 轮的回答必须是新 m2，绝不能
        // 再把旧 m1 当成回答（2026-11-11 污染/错位回归）。
        var listCalls = 0
        val fake = FakeTransport { request ->
            when {
                request.url.endsWith("/v2/task.create") ->
                    okJson("""{"ok":true,"task_id":"task-1","task_title":"hi","task_url":"https://manus.im/app/task-1"}""")
                request.url.contains("/v2/task.detail") ->
                    okJson("""{"ok":true,"task":{"id":"task-1","status":"stopped","title":"hi","has_running_background_jobs":false}}""")
                request.url.contains("/v2/task.listMessages") -> {
                    listCalls++
                    when (listCalls) {
                        1 -> okJson("""{"ok":true,"task_id":"task-1","messages":[{"id":"m1","type":"message","content":"你好，我是 Manus。"}],"has_more":false}""")
                        2 -> okJson("""{"ok":true,"task_id":"task-1","messages":[{"id":"m1","type":"message","content":"你好，我是 Manus。"}],"has_more":false}""")
                        else -> okJson("""{"ok":true,"task_id":"task-1","messages":[
                            |{"id":"m2","type":"assistant_message","timestamp":2,"assistant_message":{"content":"继续的回复。"}},
                            |{"id":"m1","type":"message","content":"你好，我是 Manus。"}
                            |],"has_more":false}""".trimMargin())
                    }
                }
                request.url.endsWith("/v2/task.sendMessage") -> okJson("""{"ok":true}""")
                request.url.endsWith("/v2/task.stop") -> okJson("""{"ok":true}""")
                else -> ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such"}}""")
            }
        }
        val store = MemoryTaskIdStore()
        val port = bridge(fake, store)
        val first = port.runChat("conv-1", manusAgent(), "在吗").toList()
        assertTrue(resultText(first).contains("你好，我是 Manus。"))
        val events = port.runChat("conv-1", manusAgent(), "继续").toList()

        assertEquals("第 2 轮的回答必须是新回复", "继续的回复。", resultText(events))
        assertFalse("第 2 轮绝不能再贴第 1 轮的旧回复", resultText(events).contains("你好，我是 Manus。"))
        assertEquals("跟进绝不能再建任务", 1, fake.requests.count { it.url.endsWith("/v2/task.create") })
        val followUps = fake.requests.filter { it.url.endsWith("/v2/task.sendMessage") }
        assertEquals(1, followUps.size)
        assertTrue(followUps.single().body!!.contains("task-1"))
        assertTrue(followUps.single().body!!.contains("继续"))
        assertEquals("task-1", store.get("conv-1"))
    }

    @Test
    fun `waiting returns the agent question and keeps the task for follow-up`() = runBlocking {
        val fake = FakeTransport(scriptedHandler(status = "waiting"))
        val store = MemoryTaskIdStore()
        val port = bridge(fake, store)
        val events = port.runChat("conv-1", manusAgent(), "在吗").toList()

        assertTrue(resultText(events).contains("你好，我是 Manus。"))
        assertEquals("waiting 仍保留任务", "task-1", store.get("conv-1"))
        port.runChat("conv-1", manusAgent(), "好的").toList()
        assertEquals("waiting 后的跟进走 sendMessage", 1, fake.requests.count { it.url.endsWith("/v2/task.sendMessage") })
    }

    @Test
    fun `timeout keeps the task and reports honestly`() = runBlocking {
        val fake = FakeTransport(scriptedHandler(status = "running"))
        val store = MemoryTaskIdStore()
        val port = bridge(fake, store, timeoutMs = 30)
        val events = port.runChat("conv-1", manusAgent(), "在吗").toList()

        val failed = failedText(events)
        assertTrue(failed.contains("Manus"))
        assertTrue(failed.contains("超时"))
        assertFalse("超时不是 ChatGPT 的错", failed.contains("ChatGPT"))
        assertEquals("超时仍保留任务，稍后可跟进", "task-1", store.get("conv-1"))
    }

    @Test
    fun `401 reports Manus unauthenticated with original code`() = runBlocking {
        val fake = FakeTransport {
            ManusHttpResponse(
                401,
                """{"ok":false,"error":{"code":"unauthenticated","message":"bad key"}}""",
            )
        }
        val events = bridge(fake).runChat("conv-1", manusAgent(), "在吗").toList()

        val failed = failedText(events)
        assertTrue(failed.contains("Manus"))
        assertTrue("原始 code 须保留供诊断", failed.contains("unauthenticated"))
        assertFalse(failed.contains("ChatGPT"))
        assertTrue(resultText(events).isEmpty())
    }

    @Test
    fun `429 rate limited is attributed to Manus not ChatGPT`() = runBlocking {
        val fake = FakeTransport {
            ManusHttpResponse(
                429,
                """{"ok":false,"error":{"code":"rate_limited","message":"too many"}}""",
            )
        }
        val events = bridge(fake).runChat("conv-1", manusAgent(), "在吗").toList()

        val failed = failedText(events)
        assertTrue(failed.contains("Manus"))
        assertTrue(failed.contains("429") || failed.contains("rate_limited"))
        assertFalse(failed.contains("ChatGPT"))
    }

    @Test
    fun `missing api key sends no request`() = runBlocking {
        val fake = FakeTransport(scriptedHandler())
        val events = bridge(fake, apiKey = null).runChat("conv-1", manusAgent(), "在吗").toList()

        val failed = failedText(events)
        assertTrue(failed.contains("Manus"))
        assertTrue(failed.contains("Key"))
        assertEquals("无 Key 时绝不发任何请求", 0, fake.requests.size)
    }

    @Test
    fun `stopTask stops the stored manus task`() = runBlocking {
        val fake = FakeTransport(scriptedHandler())
        val port = bridge(fake)
        port.runChat("conv-1", manusAgent(), "在吗").toList()

        assertTrue(port.stopTask("conv-1", manusAgent()))
        val stops = fake.requests.filter { it.url.endsWith("/v2/task.stop") }
        assertEquals(1, stops.size)
        assertTrue(stops.single().body!!.contains("task-1"))
        assertFalse("未知会话无任务可停", port.stopTask("conv-unknown", manusAgent()))
    }

    @Test
    fun `forgetConversation makes the next message create a new task`() = runBlocking {
        val fake = FakeTransport(scriptedHandler())
        val port = bridge(fake)
        port.runChat("conv-1", manusAgent(), "在吗").toList()
        port.forgetConversation("conv-1")
        port.runChat("conv-1", manusAgent(), "新目标").toList()

        assertEquals("改目标后下一条应建新任务", 2, fake.requests.count { it.url.endsWith("/v2/task.create") })
        assertEquals("跟进绝不能在忘掉后出现", 0, fake.requests.count { it.url.endsWith("/v2/task.sendMessage") })
    }

    /**
     * 回归：K70 真机上 Manus 发"在吗"后报"没有返回可显示的内容"。
     * 根因是 listMessages 解析只读顶层 content，而官方 v2 真实格式
     * 把正文嵌在 assistant_message.content 里。本测试用官方格式的
     * 载荷（status_update + assistant_message + user_message），
     * 断言取到 assistant_message 的回复，而不是用户自己的话。
     */
    @Test
    fun `greeting returns the assistant reply from real v2 listMessages format`() = runBlocking {
        val fake = FakeTransport { request ->
            when {
                request.url.endsWith("/v2/task.create") ->
                    okJson("""{"ok":true,"task_id":"task-1","task_title":"hi","task_url":"https://manus.im/app/task-1"}""")
                request.url.contains("/v2/task.detail") ->
                    okJson("""{"ok":true,"task":{"id":"task-1","status":"stopped","title":"hi","has_running_background_jobs":false}}""")
                request.url.contains("/v2/task.listMessages") ->
                    okJson("""{"ok":true,"task_id":"task-1","messages":[
                        |{"id":"m3","type":"status_update","timestamp":3,"status_update":{"agent_status":"stopped","brief":"已完成","description":"任务已完成"}},
                        |{"id":"m2","type":"assistant_message","timestamp":2,"assistant_message":{"content":"在的，有什么可以帮你的？"}},
                        |{"id":"m1","type":"user_message","timestamp":1,"user_message":{"content":"在吗","message_type":"text"}}
                        |],"has_more":false}""".trimMargin())
                request.url.endsWith("/v2/task.sendMessage") -> okJson("""{"ok":true}""")
                request.url.endsWith("/v2/task.stop") -> okJson("""{"ok":true}""")
                else -> ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such"}}""")
            }
        }
        val events = bridge(fake).runChat("conv-1", manusAgent(), "在吗").toList()

        assertEquals("在的，有什么可以帮你的？", resultText(events))
        assertTrue("不应报没有可显示的内容", failedText(events).isEmpty())
        assertFalse("绝不能把用户自己发的在吗当成回复", resultText(events).contains("在吗"))
    }

    @Test
    fun `no assistant message falls back to status update and records a diagnostic`() = runBlocking {
        val diagnostics = mutableListOf<String>()
        val fake = FakeTransport { request ->
            when {
                request.url.endsWith("/v2/task.create") ->
                    okJson("""{"ok":true,"task_id":"task-1","task_title":"hi","task_url":"https://manus.im/app/task-1"}""")
                request.url.contains("/v2/task.detail") ->
                    okJson("""{"ok":true,"task":{"id":"task-1","status":"stopped","title":"hi","has_running_background_jobs":false}}""")
                request.url.contains("/v2/task.listMessages") ->
                    okJson("""{"ok":true,"task_id":"task-1","messages":[
                        |{"id":"m2","type":"status_update","timestamp":2,"status_update":{"agent_status":"stopped","brief":"已完成","description":"任务已完成"}},
                        |{"id":"m1","type":"user_message","timestamp":1,"user_message":{"content":"在吗","message_type":"text"}}
                        |],"has_more":false}""".trimMargin())
                else -> ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such"}}""")
            }
        }
        val port = ManusChatBridge(
            adapter = ManusTaskAdapter(fake),
            apiKeyProvider = { "fake-key-not-real" },
            taskIdStore = MemoryTaskIdStore(),
            pollIntervalMs = 5,
            timeoutMs = 600_000,
            sleeper = {},
            onDiagnostic = { diagnostics.add(it) },
        )
        val events = port.runChat("conv-1", manusAgent(), "在吗").toList()

        // 没有 assistant_message 时回落到 status_update 的 brief，不静默丢掉。
        assertEquals("已完成", resultText(events))
        assertTrue("回落时应记一条诊断", diagnostics.isNotEmpty())
        assertTrue(diagnostics.single().contains("status_update"))
    }

    /**
     * V5 修复（2026-11-11）：轮询不再只等终态才取一次结果——每轮的新
     * 消息当场流出（进度去重、快照按消息 id 去重、附件去重），终态
     * Result 带全轮累计的附件。
     */
    @Test
    fun `streaming emits progress tool snapshot and attachments before the terminal result`() = runBlocking {
        var polls = 0
        val fake = FakeTransport { request ->
            when {
                request.url.endsWith("/v2/task.create") ->
                    okJson("""{"ok":true,"task_id":"task-1","task_title":"hi","task_url":"https://manus.im/app/task-1"}""")
                request.url.contains("/v2/task.detail") -> {
                    val status = if (polls >= 1) "stopped" else "running"
                    okJson("""{"ok":true,"task":{"id":"task-1","status":"$status","title":"hi","has_running_background_jobs":false}}""")
                }
                request.url.contains("/v2/task.listMessages") -> {
                    polls++
                    if (polls == 1) {
                        okJson("""{"ok":true,"task_id":"task-1","messages":[
                            |{"id":"m2","type":"assistant_message","timestamp":2,"assistant_message":{"content":"报告草稿"}},
                            |{"id":"m1","type":"status_update","timestamp":1,"status_update":{"agent_status":"running","brief":"正在写报告","description":"执行中"}}
                            |],"has_more":false}""".trimMargin())
                    } else {
                        okJson("""{"ok":true,"task_id":"task-1","messages":[
                            |{"id":"m4","type":"assistant_message","timestamp":4,"assistant_message":{"content":"报告已完成。","attachments":[{"type":"file","filename":"report.md","url":"https://manus.im/dl/abc","path":"/home/user/report.md","file_uid":"f_1","version_uid":"v_1"}]}},
                            |{"id":"m3","type":"tool_used","timestamp":3,"tool_used":{"tool":"shell","status":"success","brief":"已运行 cat","description":"读取文件"}},
                            |{"id":"m2","type":"assistant_message","timestamp":2,"assistant_message":{"content":"报告草稿"}},
                            |{"id":"m1","type":"status_update","timestamp":1,"status_update":{"agent_status":"running","brief":"正在写报告","description":"执行中"}}
                            |],"has_more":false}""".trimMargin())
                    }
                }
                request.url.endsWith("/v2/task.sendMessage") -> okJson("""{"ok":true}""")
                request.url.endsWith("/v2/task.stop") -> okJson("""{"ok":true}""")
                else -> ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such"}}""")
            }
        }
        val events = bridge(fake).runChat("conv-1", manusAgent(), "写个报告").toList()

        // 顺序：创建进度 → 状态进度 → 快照 → 工具 → 新快照 → 附件 → 终态结果。
        val kinds = events.map { it::class.simpleName }
        assertTrue(kinds.indexOf("Progress") < kinds.indexOf("TextSnapshot"))
        assertTrue(kinds.indexOf("TextSnapshot") < kinds.indexOf("ToolUsed"))
        assertTrue(kinds.indexOf("ToolUsed") < kinds.indexOf("Result"))
        assertTrue("状态进度只发一次（连续相同去重）", events.filterIsInstance<ManusChatEvent.Progress>().count { it.message == "正在写报告" } == 1)
        assertTrue("同一条快照只发一次（按消息 id 去重）", events.filterIsInstance<ManusChatEvent.TextSnapshot>().count { it.messageId == "m2" } == 1)
        val tool = events.filterIsInstance<ManusChatEvent.ToolUsed>().single()
        assertEquals("shell", tool.tool)
        assertEquals("已运行 cat", tool.brief)
        assertEquals("读取文件", tool.detail)
        assertTrue(tool.ok)
        val attachments = events.filterIsInstance<ManusChatEvent.Attachments>().single().files
        assertEquals(1, attachments.size)
        assertEquals("report.md", attachments.single().filename)
        val result = events.filterIsInstance<ManusChatEvent.Result>().single()
        assertEquals("报告已完成。", result.text)
        assertEquals("终态 Result 带全轮累计的附件", 1, result.attachments.size)
        assertTrue(failedText(events).isEmpty())
    }

    /**
     * V5 修复（2026-11-11）：快照已流出、终态页却空了（防御分支），
     * 不报“没有返回可显示的内容”——内容已经在对话里了。
     */
    @Test
    fun `stopped with streamed snapshots but no terminal text does not report no content`() = runBlocking {
        var polls = 0
        val fake = FakeTransport { request ->
            when {
                request.url.endsWith("/v2/task.create") ->
                    okJson("""{"ok":true,"task_id":"task-1","task_title":"hi","task_url":"https://manus.im/app/task-1"}""")
                request.url.contains("/v2/task.detail") -> {
                    val status = if (polls >= 1) "stopped" else "running"
                    okJson("""{"ok":true,"task":{"id":"task-1","status":"$status","title":"hi","has_running_background_jobs":false}}""")
                }
                request.url.contains("/v2/task.listMessages") -> {
                    polls++
                    if (polls == 1) {
                        okJson("""{"ok":true,"task_id":"task-1","messages":[
                            |{"id":"m1","type":"assistant_message","timestamp":1,"assistant_message":{"content":"草稿"}}
                            |],"has_more":false}""".trimMargin())
                    } else {
                        okJson("""{"ok":true,"task_id":"task-1","messages":[],"has_more":false}""")
                    }
                }
                else -> ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such"}}""")
            }
        }
        val events = bridge(fake).runChat("conv-1", manusAgent(), "写个报告").toList()

        assertTrue(events.any { it is ManusChatEvent.TextSnapshot })
        assertTrue("快照已流出，绝不报没有内容", failedText(events).isEmpty())
        assertEquals(1, events.filterIsInstance<ManusChatEvent.Result>().size)
    }

    /**
     * 回归：2026-11-11 K70 污染——用户只发一句“在吗”，回复气泡却装了
     * 整段任务历史（问候 + 模型身份 + Agent 身份 + 计算器任务进度）。
     * 根因：桥接每轮新建去重状态，listMessages 又返回整任务历史，
     * 旧 assistant_message 全被当成新快照重发。
     * 修后：跟进时先快照高水位，本轮只流出快照之后的新消息。
     */
    @Test
    fun `follow-up with prior history returns only the new greeting never the history`() = runBlocking {
        val history = """{"ok":true,"task_id":"task-1","messages":[
            |{"id":"h9","type":"user_message","timestamp":9,"user_message":{"content":"在吗","message_type":"text"}},
            |{"id":"h8","type":"assistant_message","timestamp":8,"assistant_message":{"content":"计算器文件已经写好，我再做一次文件和脚本完整性检查。","attachments":[{"type":"file","filename":"calculator.html","url":"https://example.invalid/calc.html","path":"/home/user/calculator.html","file_uid":"f_calc","version_uid":"v_calc"}]}},
            |{"id":"h7","type":"assistant_message","timestamp":7,"assistant_message":{"content":"我来直接做一个简洁、可运行的网页计算器。"}},
            |{"id":"h6","type":"user_message","timestamp":6,"user_message":{"content":"帮我开发一个包含加减乘除的计算器小程序","message_type":"text"}},
            |{"id":"h5","type":"assistant_message","timestamp":5,"assistant_message":{"content":"我是 Manus 的智能体。"}},
            |{"id":"h4","type":"user_message","timestamp":4,"user_message":{"content":"你是哪个agent","message_type":"text"}},
            |{"id":"h3","type":"assistant_message","timestamp":3,"assistant_message":{"content":"我是 Manus 的 AI 助理。"}},
            |{"id":"h2","type":"user_message","timestamp":2,"user_message":{"content":"你是哪个模型","message_type":"text"}},
            |{"id":"h1","type":"assistant_message","timestamp":1,"assistant_message":{"content":"在。"}},
            |{"id":"h0","type":"user_message","timestamp":0,"user_message":{"content":"在吗","message_type":"text"}}
            |],"has_more":false}""".trimMargin()
        val historyPlusNew = """{"ok":true,"task_id":"task-1","messages":[
            |{"id":"m-new","type":"assistant_message","timestamp":10,"assistant_message":{"content":"在的，有什么可以帮你的？"}},
            |{"id":"h9","type":"user_message","timestamp":9,"user_message":{"content":"在吗","message_type":"text"}},
            |{"id":"h8","type":"assistant_message","timestamp":8,"assistant_message":{"content":"计算器文件已经写好，我再做一次文件和脚本完整性检查。","attachments":[{"type":"file","filename":"calculator.html","url":"https://example.invalid/calc.html","path":"/home/user/calculator.html","file_uid":"f_calc","version_uid":"v_calc"}]}},
            |{"id":"h7","type":"assistant_message","timestamp":7,"assistant_message":{"content":"我来直接做一个简洁、可运行的网页计算器。"}},
            |{"id":"h6","type":"user_message","timestamp":6,"user_message":{"content":"帮我开发一个包含加减乘除的计算器小程序","message_type":"text"}},
            |{"id":"h5","type":"assistant_message","timestamp":5,"assistant_message":{"content":"我是 Manus 的智能体。"}},
            |{"id":"h4","type":"user_message","timestamp":4,"user_message":{"content":"你是哪个agent","message_type":"text"}},
            |{"id":"h3","type":"assistant_message","timestamp":3,"assistant_message":{"content":"我是 Manus 的 AI 助理。"}},
            |{"id":"h2","type":"user_message","timestamp":2,"user_message":{"content":"你是哪个模型","message_type":"text"}},
            |{"id":"h1","type":"assistant_message","timestamp":1,"assistant_message":{"content":"在。"}},
            |{"id":"h0","type":"user_message","timestamp":0,"user_message":{"content":"在吗","message_type":"text"}}
            |],"has_more":false}""".trimMargin()
        var listCalls = 0
        var detailCalls = 0
        val fake = FakeTransport { request ->
            when {
                request.url.endsWith("/v2/task.create") ->
                    okJson("""{"ok":true,"task_id":"task-1","task_title":"hi","task_url":"https://manus.im/app/task-1"}""")
                request.url.contains("/v2/task.detail") -> {
                    detailCalls++
                    val status = if (detailCalls >= 2) "stopped" else "running"
                    okJson("""{"ok":true,"task":{"id":"task-1","status":"$status","title":"hi","has_running_background_jobs":false}}""")
                }
                request.url.contains("/v2/task.listMessages") -> {
                    listCalls++
                    // 第 1 次是跟进后的基线（只有历史），第 2 次起是轮询（历史 + 新问候）。
                    if (listCalls == 1) okJson(history) else okJson(historyPlusNew)
                }
                request.url.endsWith("/v2/task.sendMessage") -> okJson("""{"ok":true}""")
                request.url.endsWith("/v2/task.stop") -> okJson("""{"ok":true}""")
                else -> ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such"}}""")
            }
        }
        val store = MemoryTaskIdStore().also { it.put("conv-1", "task-1") }
        val events = bridge(fake, store).runChat("conv-1", manusAgent(), "在吗").toList()

        val snapshots = events.filterIsInstance<ManusChatEvent.TextSnapshot>()
        assertEquals("本轮只应流出一条新快照", 1, snapshots.size)
        assertEquals("在的，有什么可以帮你的？", snapshots.single().text)
        assertEquals("终态正文只应是新问候", "在的，有什么可以帮你的？", resultText(events))
        val allText = events.joinToString("\n") {
            when (it) {
                is ManusChatEvent.TextSnapshot -> it.text
                is ManusChatEvent.Result -> it.text
                is ManusChatEvent.Progress -> it.message
                else -> ""
            }
        }
        assertFalse("历史里的计算器绝不能重发", allText.contains("计算器"))
        assertFalse("历史里的模型身份绝不能重发", allText.contains("AI 助理"))
        assertFalse("历史里的 Agent 身份绝不能重发", allText.contains("智能体"))
        assertTrue("上一轮的产物绝不能重发", events.filterIsInstance<ManusChatEvent.Attachments>().isEmpty())
        assertTrue(failedText(events).isEmpty())
    }

    /**
     * 回归：2026-11-11 K70 错位一轮——“你是哪个模型”拿到“在。”，
     * “你是哪个agent”拿到模型身份，计算器请求拿到 Agent 身份。
     * 根因：sendMessage 刚发完时状态还停在上一轮的终态（waiting），
     * 第一轮轮询就认了终态，取到的还是上一轮的回复。
     * 修后：宽限内不认终态，先等 Manus 跑起来（seenRunning），再认终态。
     */
    @Test
    fun `consecutive questions get their own replies no one-turn lag`() = runBlocking {
        var detailCalls = 0
        var sentFollowUp = false
        var baselineDone = false
        val fake = FakeTransport { request ->
            when {
                request.url.endsWith("/v2/task.create") ->
                    okJson("""{"ok":true,"task_id":"task-1","task_title":"hi","task_url":"https://manus.im/app/task-1"}""")
                request.url.contains("/v2/task.detail") -> {
                    detailCalls++
                    // 第 1 轮：running → stopped；第 2 轮：第 1 次是上一轮
                    // 残留的 waiting（必须被宽限跳过），然后 running → stopped。
                    val status = when (detailCalls) {
                        1 -> "running"
                        2 -> "stopped"
                        3 -> "waiting"
                        4 -> "running"
                        else -> "stopped"
                    }
                    okJson("""{"ok":true,"task":{"id":"task-1","status":"$status","title":"hi","has_running_background_jobs":false}}""")
                }
                request.url.contains("/v2/task.listMessages") -> {
                    if (!sentFollowUp) {
                        okJson("""{"ok":true,"task_id":"task-1","messages":[
                            |{"id":"q1","type":"assistant_message","timestamp":2,"assistant_message":{"content":"我是 Manus 的 AI 助理。"}},
                            |{"id":"u-q1","type":"user_message","timestamp":1,"user_message":{"content":"你是哪个模型","message_type":"text"}}
                            |],"has_more":false}""".trimMargin())
                    } else if (!baselineDone) {
                        baselineDone = true
                        okJson("""{"ok":true,"task_id":"task-1","messages":[
                            |{"id":"u-q2","type":"user_message","timestamp":3,"user_message":{"content":"你是哪个agent","message_type":"text"}},
                            |{"id":"q1","type":"assistant_message","timestamp":2,"assistant_message":{"content":"我是 Manus 的 AI 助理。"}},
                            |{"id":"u-q1","type":"user_message","timestamp":1,"user_message":{"content":"你是哪个模型","message_type":"text"}}
                            |],"has_more":false}""".trimMargin())
                    } else {
                        okJson("""{"ok":true,"task_id":"task-1","messages":[
                            |{"id":"q2","type":"assistant_message","timestamp":4,"assistant_message":{"content":"我是 Manus 的智能体。"}},
                            |{"id":"u-q2","type":"user_message","timestamp":3,"user_message":{"content":"你是哪个agent","message_type":"text"}},
                            |{"id":"q1","type":"assistant_message","timestamp":2,"assistant_message":{"content":"我是 Manus 的 AI 助理。"}},
                            |{"id":"u-q1","type":"user_message","timestamp":1,"user_message":{"content":"你是哪个模型","message_type":"text"}}
                            |],"has_more":false}""".trimMargin())
                    }
                }
                request.url.endsWith("/v2/task.sendMessage") -> {
                    sentFollowUp = true
                    okJson("""{"ok":true}""")
                }
                request.url.endsWith("/v2/task.stop") -> okJson("""{"ok":true}""")
                else -> ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such"}}""")
            }
        }
        val store = MemoryTaskIdStore()
        val port = bridge(fake, store, terminalGraceMs = 30_000)
        val first = port.runChat("conv-1", manusAgent(), "你是哪个模型").toList()
        assertEquals("第 1 轮应拿到模型身份", "我是 Manus 的 AI 助理。", resultText(first))
        val second = port.runChat("conv-1", manusAgent(), "你是哪个agent").toList()

        assertEquals("第 2 轮应拿到 Agent 身份，不是上一轮的模型身份", "我是 Manus 的智能体。", resultText(second))
        assertFalse(
            "第 2 轮的任何事件里都不应出现上一轮的模型身份",
            second.any {
                (it is ManusChatEvent.TextSnapshot && it.text.contains("AI 助理")) ||
                    (it is ManusChatEvent.Result && it.text.contains("AI 助理"))
            },
        )
        assertTrue("必须等过残留 waiting（多轮 getTask），不能第一轮就认终态", detailCalls >= 5)
        assertTrue(failedText(second).isEmpty())
    }

    /**
     * 回归：2026-11-11 K70 状态刷屏——“Manus is running”/
     * “Manus finished working”来回翻，每翻一次就一个新气泡，一轮刷出
     * 9 个以上。根因：去重只管连续相同，翻转的文案每次都不同，全发了。
     * 修后：通用运行/完成翻转文案不发气泡（轮次本身已有运行中指示）；
     * 有信息量的 brief（“正在写报告”）照常发，但整轮只发一次。
     */
    @Test
    fun `running-finished status flips do not spam progress bubbles`() = runBlocking {
        var listCalls = 0
        var detailCalls = 0
        fun statusPage(vararg ids: String): String {
            val all = mapOf(
                "s1" to """{"id":"s1","type":"status_update","timestamp":1,"status_update":{"agent_status":"running","brief":"Manus is running","description":"working"}}""",
                "s2" to """{"id":"s2","type":"status_update","timestamp":2,"status_update":{"agent_status":"stopped","brief":"Manus finished working","description":"done"}}""",
                "s-info" to """{"id":"s-info","type":"status_update","timestamp":3,"status_update":{"agent_status":"running","brief":"正在写报告","description":"执行中"}}""",
                "s3" to """{"id":"s3","type":"status_update","timestamp":4,"status_update":{"agent_status":"running","brief":"Manus is running","description":"working"}}""",
                "s4" to """{"id":"s4","type":"status_update","timestamp":5,"status_update":{"agent_status":"stopped","brief":"Manus finished working","description":"done"}}""",
                "a1" to """{"id":"a1","type":"assistant_message","timestamp":6,"assistant_message":{"content":"好了。"}}""",
                "u0" to """{"id":"u0","type":"user_message","timestamp":0,"user_message":{"content":"做个计算器","message_type":"text"}}""",
            )
            return """{"ok":true,"task_id":"task-1","messages":[${ids.joinToString(",") { all.getValue(it) }}],"has_more":false}"""
        }
        val fake = FakeTransport { request ->
            when {
                request.url.endsWith("/v2/task.create") ->
                    okJson("""{"ok":true,"task_id":"task-1","task_title":"hi","task_url":"https://manus.im/app/task-1"}""")
                request.url.contains("/v2/task.detail") -> {
                    detailCalls++
                    val status = if (detailCalls >= 5) "stopped" else "running"
                    okJson("""{"ok":true,"task":{"id":"task-1","status":"$status","title":"hi","has_running_background_jobs":false}}""")
                }
                request.url.contains("/v2/task.listMessages") -> {
                    listCalls++
                    when (listCalls) {
                        1 -> okJson(statusPage("s1", "u0"))
                        2 -> okJson(statusPage("s2", "s1", "u0"))
                        3 -> okJson(statusPage("s3", "s-info", "s2", "s1", "u0"))
                        4 -> okJson(statusPage("s4", "s3", "s-info", "s2", "s1", "u0"))
                        else -> okJson(statusPage("a1", "s4", "s3", "s-info", "s2", "s1", "u0"))
                    }
                }
                request.url.endsWith("/v2/task.sendMessage") -> okJson("""{"ok":true}""")
                request.url.endsWith("/v2/task.stop") -> okJson("""{"ok":true}""")
                else -> ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such"}}""")
            }
        }
        val events = bridge(fake).runChat("conv-1", manusAgent(), "做个计算器").toList()

        val progresses = events.filterIsInstance<ManusChatEvent.Progress>().map { it.message }
        assertFalse("通用 running 翻转绝不能成气泡", progresses.any { it.contains("Manus is running") })
        assertFalse("通用 finished 翻转绝不能成气泡", progresses.any { it.contains("Manus finished working") })
        assertEquals("有信息量的进度整轮只发一次", 1, progresses.count { it == "正在写报告" })
        assertEquals("终态正文是助手回答", "好了。", resultText(events))
        assertTrue(failedText(events).isEmpty())
    }

    /**
     * 回归：终态宽限曾无条件作用于所有轮次——新建任务（create）状态
     * 一开始就是 stopped 时，桥接在无睡眠的紧循环里空转满 30 秒宽限
     * 才认终态，测试里直接 OutOfMemoryError。根因：新建任务没有
     * “上一轮残留终态”可言，宽限只对跟进轮次有意义。
     * 修后：宽限只对跟进轮次生效，新建任务的终态立即认。
     */
    @Test
    fun `create turn with immediately stopped status does not wait out the grace period`() = runBlocking {
        var detailCalls = 0
        val fake = FakeTransport { request ->
            when {
                request.url.endsWith("/v2/task.create") ->
                    okJson("""{"ok":true,"task_id":"task-1","task_title":"hi","task_url":"https://manus.im/app/task-1"}""")
                request.url.contains("/v2/task.detail") -> {
                    detailCalls++
                    okJson("""{"ok":true,"task":{"id":"task-1","status":"stopped","title":"hi","has_running_background_jobs":false}}""")
                }
                request.url.contains("/v2/task.listMessages") -> okJson(
                    """{"ok":true,"task_id":"task-1","messages":[
                    |{"id":"a1","type":"assistant_message","timestamp":2,"assistant_message":{"content":"好了。"}},
                    |{"id":"u0","type":"user_message","timestamp":1,"user_message":{"content":"在吗","message_type":"text"}}
                    |],"has_more":false}""".trimMargin(),
                )
                request.url.endsWith("/v2/task.sendMessage") -> okJson("""{"ok":true}""")
                request.url.endsWith("/v2/task.stop") -> okJson("""{"ok":true}""")
                else -> ManusHttpResponse(404, """{"ok":false,"error":{"code":"not_found","message":"no such"}}""")
            }
        }
        // 直接构造，用生产默认宽限 30 秒：新建任务必须照样立即认终态。
        val port = ManusChatBridge(
            adapter = ManusTaskAdapter(fake),
            apiKeyProvider = { "fake-key-not-real" },
            taskIdStore = MemoryTaskIdStore(),
            pollIntervalMs = 5,
            timeoutMs = 600_000,
            sleeper = {},
            terminalGraceMs = 30_000,
        )
        val events = port.runChat("conv-1", manusAgent(), "在吗").toList()

        assertEquals("好了。", resultText(events))
        assertTrue("新建任务没有上一轮残留，终态应立即认，不应空转宽限", detailCalls <= 2)
        assertTrue(failedText(events).isEmpty())
    }
}
