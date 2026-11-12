package wherevercode.agent.core.extension.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.extension.mcp.McpJsonRpc.toList
import wherevercode.agent.core.extension.mcp.McpJsonRpc.toMap

/**
 * MCP JSON-RPC 消息层测试（纯 JVM）。
 *
 * 只测本客户端自己造消息与解析回包的形状；协议本身的端到端
 * 往返见 McpClientTest（真实子进程服务器）。
 */
class McpJsonRpcTest {

    @Test
    fun `request carries jsonrpc version id method and params`() {
        val params = JSONObject().apply { put("a", 1) }
        val req = McpJsonRpc.request(7, "tools/list", params)
        assertEquals("2.0", req.getString("jsonrpc"))
        assertEquals(7, req.getLong("id"))
        assertEquals("tools/list", req.getString("method"))
        assertEquals(1, req.getJSONObject("params").getInt("a"))
    }

    @Test
    fun `notification has no id`() {
        val note = McpJsonRpc.notification("notifications/initialized", JSONObject())
        assertEquals("2.0", note.getString("jsonrpc"))
        assertEquals("notifications/initialized", note.getString("method"))
        assertTrue(!note.has("id"))
    }

    @Test
    fun `parse result response`() {
        val resp = McpJsonRpc.parseResponse(
            """{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}""",
        )
        val result = resp as McpJsonRpc.Response.Result
        assertEquals(3L, result.id)
        assertTrue((result.result as JSONObject).has("tools"))
    }

    @Test
    fun `parse error response keeps code and message`() {
        val resp = McpJsonRpc.parseResponse(
            """{"jsonrpc":"2.0","id":3,"error":{"code":-32601,"message":"Method not found: nope"}}""",
        )
        val error = resp as McpJsonRpc.Response.Error
        assertEquals(3L, error.id)
        assertEquals(-32601, error.code)
        assertTrue(error.message.contains("nope"))
    }

    @Test
    fun `server notification without id parses as notification`() {
        val resp = McpJsonRpc.parseResponse(
            """{"jsonrpc":"2.0","method":"notifications/message","params":{}}""",
        )
        val notification = resp as McpJsonRpc.Response.Notification
        assertEquals("notifications/message", notification.notification.method)
        assertEquals(0, notification.notification.params!!.length())
    }

    @Test
    fun `non json line is ignored`() {
        assertTrue(McpJsonRpc.parseResponse("hello, not json") is McpJsonRpc.Response.Ignored)
    }

    @Test
    fun `object converts to map recursively`() {
        val obj = JSONObject(
            """{"a":1,"b":{"c":"x"},"d":[1,2],"e":null,"f":true}""",
        )
        val map = with(McpJsonRpc) { obj.toMap() }
        assertEquals(1, map["a"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("x", (map["b"] as Map<String, Any?>)["c"])
        assertEquals(listOf(1, 2), map["d"])
        assertNull(map["e"])
        assertEquals(true, map["f"])
    }

    @Test
    fun `map converts to json object recursively`() {
        val json = McpJsonRpc.mapToJson(
            mapOf("a" to 1, "b" to mapOf("c" to "x"), "d" to listOf(1, 2), "e" to null),
        )
        assertEquals(1, json.getInt("a"))
        assertEquals("x", json.getJSONObject("b").getString("c"))
        assertEquals(2, json.getJSONArray("d").length())
        assertTrue(json.isNull("e"))
    }

    @Test
    fun `array converts to list`() {
        val array = JSONObject("""{"v":[1,"x",null]}""").getJSONArray("v")
        val list = with(McpJsonRpc) { array.toList() }
        assertEquals(listOf(1, "x", null), list)
    }
}
