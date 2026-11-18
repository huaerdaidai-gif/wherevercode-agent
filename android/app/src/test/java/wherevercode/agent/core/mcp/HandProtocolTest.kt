package wherevercode.agent.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandProtocolTest {

    private fun envelope() = HandEnvelope(
        seq = 42,
        taskId = "task-1",
        agentId = "muse",
        sourceDeviceId = "device-a",
        targetDeviceId = "device-b",
        folder = "/projects/demo",
        action = "read",
        args = mapOf("path" to "README.md"),
    )

    @Test
    fun `nullable workspace notice is omitted from response arguments`() {
        val json = org.json.JSONObject(HandProtocol.toJson(envelope().copy(type = "cross_device_result")))
        json.put("args", org.json.JSONObject("""{"output":"ok","workspace_notice":null}"""))
        assertEquals(mapOf("output" to "ok"), HandProtocol.parseJson(json.toString())?.args)
    }

    @Test
    fun `envelope toJson and parseJson round-trip`() {
        val parsed = HandProtocol.parseJson(HandProtocol.toJson(envelope()))
        assertEquals(envelope(), parsed)
    }

    @Test
    fun `parseJson returns null for malformed envelopes`() {
        assertNull(HandProtocol.parseJson(""))
        assertNull(HandProtocol.parseJson("not json"))
        assertNull(HandProtocol.parseJson("{\"type\":\"something-else\",\"version\":1}"))
        assertNull(HandProtocol.parseJson("{\"type\":\"cross_device_tool_call\",\"version\":2}"))
        assertNull(HandProtocol.parseJson(HandProtocol.toJson(envelope().copy(action = "delete"))))
        assertNull(HandProtocol.parseJson(HandProtocol.toJson(envelope().copy(taskId = ""))))
    }

    @Test
    fun `error messages are Chinese and actionable`() {
        assertTrue(
            HandProtocol.Errors.message(HandProtocol.Errors.NOT_AUTHORIZED)
                .contains("请先在项目设备上授权"),
        )
        assertTrue(
            HandProtocol.Errors.message(HandProtocol.Errors.WRONG_TASK)
                .contains("不属于本任务"),
        )
        assertTrue(
            HandProtocol.Errors.message(HandProtocol.Errors.STALE_SEQUENCE)
                .contains("序号已过期"),
        )
        assertTrue(
            HandProtocol.Errors.message(HandProtocol.Errors.COMMAND_NOT_ALLOWED)
                .contains("不允许的命令"),
        )
        assertTrue(
            HandProtocol.Errors.message(HandProtocol.Errors.PATH_NOT_ALLOWED)
                .contains("不在授权文件夹内"),
        )
        assertEquals("cross_device_not_authorized", HandProtocol.Errors.NOT_AUTHORIZED)
        assertEquals("command_not_allowed", HandProtocol.Errors.COMMAND_NOT_ALLOWED)
        assertEquals("path_not_allowed", HandProtocol.Errors.PATH_NOT_ALLOWED)
    }

    @Test
    fun `mcp tools are exactly the four hand tools`() {
        assertEquals(listOf("list", "read", "write", "run"), HandProtocol.MCP_TOOLS.map { it.name })
        assertEquals("2024-11-05", HandProtocol.MCP_PROTOCOL_VERSION)
        assertEquals("cross_device_hand", HandProtocol.MCP_SERVER_NAME)
    }

    @Test
    fun `M2 M11 - isSessionSyncEnvelope accepts a legal envelope and rejects the rest`() {
        fun envelope(
            type: String = HandProtocol.SESSION_SYNC_TYPE,
            version: Int = 1,
            seq: Long = 3,
            sessionId: String = "sess-1",
            messageId: String = "msg-1",
            messageType: String = "CONTROL",
        ) = org.json.JSONObject().apply {
            put("type", type)
            put("version", version)
            put("seq", seq)
            put("session_id", sessionId)
            put("message_id", messageId)
            put("message_type", messageType)
        }
        assertTrue(HandProtocol.isSessionSyncEnvelope(envelope()))
        assertTrue(
            HandProtocol.isSessionSyncEnvelope(
                envelope().apply { remove("message_type") },
            ),
        )
        assertFalse(HandProtocol.isSessionSyncEnvelope(envelope(version = 2)))
        assertFalse(HandProtocol.isSessionSyncEnvelope(envelope(seq = -1)))
        assertFalse(HandProtocol.isSessionSyncEnvelope(envelope(sessionId = "")))
        assertFalse(HandProtocol.isSessionSyncEnvelope(envelope(messageId = "")))
        assertFalse(HandProtocol.isSessionSyncEnvelope(envelope(type = "cross_device_tool_call")))
        assertFalse(HandProtocol.isSessionSyncEnvelope(envelope(messageType = "SHOUT")))
        assertFalse(HandProtocol.isSessionSyncEnvelope("not json"))
        assertFalse(HandProtocol.isSessionSyncEnvelope("{}"))
        // String overload accepts the legal envelope too.
        assertTrue(HandProtocol.isSessionSyncEnvelope(envelope().toString()))
        assertEquals(
            listOf("CHAT", "CONTROL", "TASK", "EVENT", "DELIVERY"),
            HandProtocol.SESSION_MESSAGE_TYPES,
        )
    }

    @Test
    fun `M2 M11 - a session-sync envelope is not a hand action and is never dispatched as a tool call`() {
        assertTrue(HandProtocol.SESSION_SYNC_TYPE !in HandProtocol.HAND_ACTIONS)
        val json = org.json.JSONObject().apply {
            put("type", HandProtocol.SESSION_SYNC_TYPE)
            put("version", 1)
            put("seq", 3)
            put("session_id", "sess-1")
            put("message_id", "msg-1")
            put("message_type", "CONTROL")
            put("task_id", "task-1")
            put("agent_id", "muse")
            put("source_device_id", "device-a")
            put("target_device_id", "device-b")
            put("folder", "/projects/demo")
            put("action", "read")
        }.toString()
        assertNull(HandProtocol.parseJson(json))
    }
}

class McpGuardTest {

    @Test
    fun `tokenShape never leaks the secret part`() {
        assertEquals("empty", McpGuard.tokenShape(null))
        assertEquals("empty", McpGuard.tokenShape(""))
        val shaped = McpGuard.tokenShape("wco_abcdefgh1234567890")
        assertTrue(shaped.startsWith("wco hint=wco_abcd"))
        assertTrue(!shaped.contains("1234567890"))
        val other = McpGuard.tokenShape("hsurr:placeholder")
        assertTrue(other.startsWith("not-a-token"))
        assertTrue(!other.contains("placeholder"))
    }

    @Test
    fun `redactMcpPath hides the bearer segment`() {
        assertEquals(
            "/api/open/v1/mcp/[redacted]/tools/call",
            McpGuard.redactMcpPath("/api/open/v1/mcp/wco_secret123/tools/call"),
        )
        assertEquals("/other/path", McpGuard.redactMcpPath("/other/path"))
    }

    @Test
    fun `rate limiter allows 60 per minute then names the wait`() {
        val limiter = McpGuard.RateLimiter()
        repeat(60) {
            assertTrue(limiter.check("agent-1", 1_700_000_000L) is McpGuard.RateLimitResult.Allowed)
        }
        val limited = limiter.check("agent-1", 1_700_000_000L)
        assertTrue(limited is McpGuard.RateLimitResult.Limited)
        val wait = (limited as McpGuard.RateLimitResult.Limited).retryAfterSeconds
        assertTrue(wait in 1..60)
        assertTrue(McpGuard.rateLimitMessage(wait).contains("请等待 $wait 秒后重试"))
        // Another agent is unaffected, and the next minute resets.
        assertTrue(limiter.check("agent-2", 1_700_000_000L) is McpGuard.RateLimitResult.Allowed)
        assertTrue(limiter.check("agent-1", 1_700_000_060L) is McpGuard.RateLimitResult.Allowed)
    }

    @Test
    fun `tools list filtering hides decide_task and write tools from review-only agents`() {
        val tools = HandProtocol.MCP_TOOLS + McpToolDef(
            name = "decide_task",
            description = "总管决策",
            inputSchema = emptyMap(),
        )
        val dev = McpGuard.filterToolsForRole(tools, setOf("dev"), supervisorOn = true)
        assertTrue(dev.any { it.name == "decide_task" })
        val review = McpGuard.filterToolsForRole(tools, setOf("review"), supervisorOn = false)
        assertTrue(review.none { it.name == "decide_task" })
        assertTrue(review.none { it.name == "write" })
        assertTrue(review.none { it.name == "run" })
        assertTrue(review.any { it.name == "read" })
    }
}
