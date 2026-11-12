package wherevercode.agent.core.extension.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import wherevercode.agent.core.extension.mcp.McpJsonRpc.McpNotification

/**
 * MCP 客户端超时、取消与重试（2026-11-13，MCP 能力补齐）。
 *
 * 用脚本化假传输（不走真实服务器）：按方法与调用次数决定回包或抛错。
 * - 幂等读（tools/list）遇瞬时传输失败重试一次；
 * - tools/call 绝不自动重试（非幂等，重试可能重复执行）；
 * - 超时参数透传给传输层；
 * - 取消原样传播为 CancellationException，不被重试吞掉。
 */
class McpClientRetryTest {

    private class ScriptedTransport(
        private val script: suspend (method: String, call: Int) -> Any?,
    ) : McpTransport {
        val calls = mutableListOf<String>()
        val timeoutsSeen = mutableListOf<Long>()

        override suspend fun start() {}

        override suspend fun request(method: String, params: JSONObject?, timeoutMillis: Long): Any? {
            calls += method
            timeoutsSeen += timeoutMillis
            val n = calls.count { it == method }
            return script(method, n)
        }

        override suspend fun notify(method: String, params: JSONObject?) {}
        override suspend fun close() {}
        override fun setNotificationListener(listener: ((McpNotification) -> Unit)?) {}
        override fun setServerRequestListener(listener: ((McpJsonRpc.Response.ServerRequest) -> Unit)?) {}
        override suspend fun respond(id: Long, result: JSONObject) {}
        override suspend fun respondError(id: Long, code: Int, message: String) {}
    }

    private fun initResult(): JSONObject = JSONObject().apply {
        put("protocolVersion", "2025-11-25")
        put("capabilities", JSONObject())
        put("serverInfo", JSONObject().apply {
            put("name", "fake")
            put("version", "1")
        })
    }

    private fun toolsResult(vararg names: String): JSONObject = JSONObject().apply {
        put("tools", org.json.JSONArray().apply {
            for (name in names) {
                put(JSONObject().apply {
                    put("name", name)
                    put("description", "fake $name")
                    put("inputSchema", JSONObject().apply { put("type", "object") })
                })
            }
        })
    }

    private fun toolCallResult(text: String, isError: Boolean = false): JSONObject = JSONObject().apply {
        put("content", org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
        })
        put("isError", isError)
    }

    @Test
    fun `listTools retries once on transient timeout then succeeds`() = runBlocking {
        val transport = ScriptedTransport { method, call ->
            when (method) {
                "initialize" -> initResult()
                "tools/list" -> {
                    if (call == 1) throw McpException("timeout", "mcp_timeout")
                    toolsResult("add", "echo")
                }
                else -> error("unexpected $method")
            }
        }
        val client = McpClient(transport)
        try {
            client.initialize()
            val tools = client.listTools()
            assertEquals(listOf("add", "echo"), tools.map { it.name }.sorted())
            assertEquals("tools/list 应只调两次（失败一次 + 重试一次）", 2, transport.calls.count { it == "tools/list" })
        } finally {
            client.close()
        }
    }

    @Test
    fun `listTools gives up after the one retry`() = runBlocking {
        val transport = ScriptedTransport { method, _ ->
            when (method) {
                "initialize" -> initResult()
                "tools/list" -> throw McpException("timeout", "mcp_timeout")
                else -> error("unexpected $method")
            }
        }
        val client = McpClient(transport)
        try {
            client.initialize()
            try {
                client.listTools()
                fail("应抛错")
            } catch (e: McpException) {
                assertEquals("mcp_timeout", e.code)
            }
            assertEquals("tools/list 最多调两次", 2, transport.calls.count { it == "tools/list" })
        } finally {
            client.close()
        }
    }

    @Test
    fun `callTool does not retry a transient timeout`() = runBlocking {
        val transport = ScriptedTransport { method, _ ->
            when (method) {
                "initialize" -> initResult()
                "tools/call" -> throw McpException("timeout", "mcp_timeout")
                else -> error("unexpected $method")
            }
        }
        val client = McpClient(transport)
        try {
            client.initialize()
            try {
                client.callTool("add", mapOf("a" to 2, "b" to 3))
                fail("应抛错")
            } catch (e: McpException) {
                assertEquals("mcp_timeout", e.code)
            }
            assertEquals("tools/call 绝不能自动重试（非幂等）", 1, transport.calls.count { it == "tools/call" })
        } finally {
            client.close()
        }
    }

    @Test
    fun `callTool passes the timeout through to the transport`() = runBlocking {
        val transport = ScriptedTransport { method, _ ->
            when (method) {
                "initialize" -> initResult()
                "tools/call" -> toolCallResult("5")
                else -> error("unexpected $method")
            }
        }
        val client = McpClient(transport)
        try {
            client.initialize()
            val result = client.callTool("add", mapOf("a" to 2, "b" to 3), timeoutMs = 5_000)
            assertFalse(result.isError)
            assertEquals("5", result.text)
            assertTrue("超时参数应透传给传输层", transport.timeoutsSeen.contains(5_000L))
        } finally {
            client.close()
        }
    }

    @Test
    fun `cancellation during listTools propagates and is not retried`() = runBlocking {
        val transport = ScriptedTransport { method, _ ->
            when (method) {
                "initialize" -> initResult()
                "tools/list" -> {
                    delay(10_000)
                    toolsResult("add")
                }
                else -> error("unexpected $method")
            }
        }
        val client = McpClient(transport)
        try {
            client.initialize()
            val job = launch { client.listTools() }
            delay(100)
            job.cancel()
            job.join()
            assertTrue("取消后 job 应是已取消状态", job.isCancelled)
            assertEquals("取消不重试：tools/list 只应调一次", 1, transport.calls.count { it == "tools/list" })
        } finally {
            client.close()
        }
    }

    @Test
    fun `rpc errors are not retried`() = runBlocking {
        val transport = ScriptedTransport { method, _ ->
            when (method) {
                "initialize" -> initResult()
                "tools/list" -> throw McpException("MCP 服务端错误 -32601：Method not found", "mcp_rpc_error")
                else -> error("unexpected $method")
            }
        }
        val client = McpClient(transport)
        try {
            client.initialize()
            try {
                client.listTools()
                fail("应抛错")
            } catch (e: McpException) {
                assertEquals("mcp_rpc_error", e.code)
            }
            assertEquals("服务端 RPC 错误不重试", 1, transport.calls.count { it == "tools/list" })
        } finally {
            client.close()
        }
    }

    @Test
    fun `cancellation exception is never converted to a timeout failure`() = runBlocking {
        val transport = ScriptedTransport { method, _ ->
            when (method) {
                "initialize" -> initResult()
                "tools/call" -> throw CancellationException("cancelled by test")
                else -> error("unexpected $method")
            }
        }
        val client = McpClient(transport)
        try {
            client.initialize()
            try {
                client.callTool("add", mapOf("a" to 1))
                fail("应抛取消异常")
            } catch (e: CancellationException) {
                // 预期：取消原样传播，不转成 McpException。
            }
            assertEquals(1, transport.calls.count { it == "tools/call" })
        } finally {
            client.close()
        }
    }
}
