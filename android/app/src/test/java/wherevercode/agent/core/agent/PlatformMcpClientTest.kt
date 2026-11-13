package wherevercode.agent.core.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import wherevercode.agent.core.extension.mcp.McpClient
import wherevercode.agent.core.extension.mcp.McpException
import wherevercode.agent.core.extension.mcp.McpJsonRpc
import wherevercode.agent.core.extension.mcp.McpJsonRpc.McpNotification
import wherevercode.agent.core.extension.mcp.McpTransport

/**
 * 平台 MCP 客户端（2026-11-13，MCP 能力补齐）。
 *
 * 用脚本化假传输冒充平台端点：initialize / tools/list / tools/call
 * 全是脚本控制的回包。不连真实平台（issue_agent_token 未部署、
 * App 凭证 scope 未核实，真机联调另做）。
 * - 连接与工具发现是真实 tools/list 的结果；
 * - 缺失的工具报缺口异常，绝不伪造成功；
 * - 跨项目调用在本地就拒绝，不发网；
 * - 令牌永不出现在错误消息里。
 */
class PlatformMcpClientTest {

    private class ScriptedTransport(
        private val tools: List<String>,
        private val callScript: (name: String, call: Int) -> JSONObject,
    ) : McpTransport {
        val methods = mutableListOf<String>()
        val toolCalls = mutableListOf<String>()

        override suspend fun start() {}

        override suspend fun request(method: String, params: JSONObject?, timeoutMillis: Long): Any? {
            methods += method
            return when (method) {
                "initialize" -> JSONObject().apply {
                    put("protocolVersion", "2025-11-25")
                    put("capabilities", JSONObject())
                    put("serverInfo", JSONObject().apply {
                        put("name", "wherevercode-platform")
                        put("version", "5")
                    })
                }
                "tools/list" -> JSONObject().apply {
                    put("tools", org.json.JSONArray().apply {
                        for (name in tools) {
                            put(JSONObject().apply {
                                put("name", name)
                                put("description", "platform $name")
                                put("inputSchema", JSONObject().apply { put("type", "object") })
                            })
                        }
                    })
                }
                "tools/call" -> {
                    val name = params?.optString("name", "").orEmpty()
                    toolCalls += name
                    val call = toolCalls.count { it == name }
                    callScript(name, call)
                }
                else -> error("unexpected $method")
            }
        }

        override suspend fun notify(method: String, params: JSONObject?) {}
        override suspend fun close() {}
        override fun setNotificationListener(listener: ((McpNotification) -> Unit)?) {}
        override fun setServerRequestListener(listener: ((McpJsonRpc.Response.ServerRequest) -> Unit)?) {}
        override suspend fun respond(id: Long, result: JSONObject) {}
        override suspend fun respondError(id: Long, code: Int, message: String) {}
    }

    private fun toolResult(text: String, isError: Boolean = false): JSONObject = JSONObject().apply {
        put("content", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        })
        put("isError", isError)
    }

    private val fullTools = listOf(
        "agent_channel_messages",
        "agent_channel_post",
        "list_my_tasks",
        "get_task",
        "claim_task",
        "report_progress",
        "submit_result",
        "release_task",
    )

    private fun buildClient(
        tools: List<String> = fullTools,
        token: String = "v5_test_token_not_real",
        callScript: (name: String, call: Int) -> JSONObject = { name, _ ->
            when (name) {
                "claim_task" -> toolResult("""{"task_id":"t1","state":"working","title":"修 bug"}""")
                "list_my_tasks" -> toolResult("""{"tasks":[{"task_id":"t1","state":"queued","title":"修 bug"}]}""")
                "get_task" -> toolResult("""{"task_id":"t1","state":"queued","title":"修 bug"}""")
                "report_progress", "submit_result", "release_task" -> toolResult("""{"ok":true}""")
                "agent_channel_post" -> toolResult("""{"id":"m1"}""")
                "agent_channel_messages" -> toolResult("""{"messages":[]}""")
                else -> toolResult("未知工具：$name", isError = true)
            }
        },
        localTaskProject: ((String) -> String?)? = null,
        currentProjectId: (() -> String?)? = null,
    ): Pair<PlatformMcpClient, ScriptedTransport> {
        val transport = ScriptedTransport(tools, callScript)
        val client = PlatformMcpClient(
            tokenProvider = { token },
            endpoint = "https://example.invalid/api/open/v1/mcp",
            clientFactory = { McpClient(transport) },
            localTaskProject = localTaskProject,
            currentProjectId = currentProjectId,
        )
        return client to transport
    }

    @Test
    fun `connect returns the real tool list from the server`() = runBlocking {
        val (client, _) = buildClient()
        try {
            val status = client.connect()
            assertTrue(status.connected)
            assertEquals("wherevercode-platform", status.serverName)
            assertEquals("2025-11-25", status.protocolVersion)
            assertEquals(fullTools.sorted(), status.tools.map { it.name }.sorted())
            assertEquals(fullTools.sorted(), client.liveTools.map { it.name }.sorted())
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `connect without a credential reports not connected honestly`() = runBlocking {
        val transport = ScriptedTransport(fullTools) { _, _ -> toolResult("{}") }
        val client = PlatformMcpClient(
            tokenProvider = { null },
            endpoint = "https://example.invalid/api/open/v1/mcp",
            clientFactory = { McpClient(transport) },
        )
        val status = client.connect()
        assertFalse(status.connected)
        assertTrue(status.lastError?.contains("未配置平台 Agent 凭证") == true)
        assertFalse("无凭证时绝不能发网", transport.methods.contains("initialize"))
    }

    @Test
    fun `probe reports missing tools as gaps`() = runBlocking {
        val (client, _) = buildClient()
        try {
            assertTrue(client.connect().connected)
            val probe = client.probeTaskLifecycle()
            assertEquals(fullTools.sorted(), probe.tools)
            assertTrue(probe.claimTask)
            assertTrue(probe.reportProgress)
            assertTrue(probe.submitResult)
            assertFalse("服务端没给 attach_delivery_diff 就必须报缺", probe.attachDeliveryDiff)
            assertFalse("服务端没给 report_status 就必须报缺", probe.reportStatus)
            assertFalse("服务端没给 start_task 就必须报缺", probe.startTask)
            assertFalse("服务端没给 agent_channel_list 就必须报缺", probe.channelList)
            assertTrue(probe.missing().contains("attach_delivery_diff"))
            assertTrue(probe.missing().contains("start_task"))
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `claimTask calls the real tool and returns the task`() = runBlocking {
        val (client, transport) = buildClient()
        try {
            assertTrue(client.connect().connected)
            val task = client.claimTask("t1")
            assertEquals("t1", task.taskId)
            assertEquals("working", task.state)
            assertEquals("修 bug", task.title)
            assertEquals(listOf("claim_task"), transport.toolCalls)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `claimTask for a tool the server lacks throws a gap error never a fake success`() = runBlocking {
        val (client, transport) = buildClient(tools = listOf("agent_channel_messages"))
        try {
            assertTrue(client.connect().connected)
            try {
                client.claimTask("t1")
                fail("服务端没有 claim_task，应报缺口")
            } catch (e: PlatformMcpGapException) {
                assertTrue(e.message?.contains("claim_task") == true)
            }
            assertTrue("缺口工具绝不能发网", transport.toolCalls.isEmpty())
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `project guard refuses a task from another project before any network call`() = runBlocking {
        val (client, transport) = buildClient(
            localTaskProject = { "proj-other" },
            currentProjectId = { "proj-now" },
        )
        try {
            assertTrue(client.connect().connected)
            try {
                client.claimTask("t1")
                fail("跨项目任务应在本地拒绝")
            } catch (e: PlatformMcpRefusedException) {
                assertTrue(e.message?.contains("另一项目") == true)
            }
            assertTrue("本地拒绝后绝不能发网", transport.toolCalls.isEmpty())
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `project guard passes a task from the same project`() = runBlocking {
        val (client, transport) = buildClient(
            localTaskProject = { "proj-now" },
            currentProjectId = { "proj-now" },
        )
        try {
            assertTrue(client.connect().connected)
            val task = client.claimTask("t1")
            assertEquals("t1", task.taskId)
            assertEquals(listOf("claim_task"), transport.toolCalls)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `token never appears in error messages`() = runBlocking {
        val token = "v5_SECRET_TOKEN_xyz_not_real"
        val (client, _) = buildClient(
            token = token,
            callScript = { _, _ -> throw McpException("401 bad token $token", "mcp_unauthorized") },
        )
        try {
            assertTrue(client.connect().connected)
            try {
                client.claimTask("t1")
                fail("应抛错")
            } catch (e: McpException) {
                assertFalse("错误消息里绝不能带令牌", e.message?.contains(token) == true)
                assertTrue("令牌应被遮掉", e.message?.contains("[已隐藏的凭证]") == true)
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `reportProgress rejects blank text locally`() = runBlocking {
        val (client, _) = buildClient()
        try {
            assertTrue(client.connect().connected)
            try {
                client.reportProgress("t1", "   ")
                fail("空进度应在本地拒绝")
            } catch (e: PlatformMcpRefusedException) {
                assertTrue(e.message?.contains("不能为空") == true)
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `submitResult rejects a bad verdict locally`() = runBlocking {
        val (client, _) = buildClient()
        try {
            assertTrue(client.connect().connected)
            try {
                client.submitResult("t1", "做完了", verdict = "maybe")
                fail("非法 verdict 应在本地拒绝")
            } catch (e: PlatformMcpRefusedException) {
                assertTrue(e.message?.contains("verdict") == true)
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `execute exposes platform tools to the brain chain with real results`() = runBlocking {
        val (client, _) = buildClient()
        try {
            assertTrue(client.connect().connected)
            val defs = client.toolDefs()
            assertTrue(defs.any { it.name == "mcp_platform_claim_task" })
            assertTrue(defs.any { it.name == "mcp_platform_agent_channel_messages" })
            assertTrue("读频道消息是只读", client.isReadOnly("mcp_platform_agent_channel_messages"))
            assertFalse("领单要审批", client.isReadOnly("mcp_platform_claim_task"))
            assertTrue(client.needsApproval("mcp_platform_claim_task"))
            val ok = client.execute("mcp_platform_claim_task", mapOf("task_id" to "t1"))
            assertTrue(ok.ok)
            val posted = client.execute("mcp_platform_agent_channel_post", mapOf("channel_id" to "c1", "text" to "hi"))
            assertTrue("频道发送应真实执行并返回成功", posted.ok)
            val unknown = client.execute("mcp_platform_nosuch", emptyMap())
            assertFalse(unknown.ok)
            assertEquals("invalid_action", unknown.errorCode)
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `execute without connecting reports not connected`() = runBlocking {
        val (client, _) = buildClient()
        val outcome = client.execute("mcp_platform_claim_task", mapOf("task_id" to "t1"))
        assertFalse(outcome.ok)
        assertEquals("mcp_not_connected", outcome.errorCode)
    }
}
