package wherevercode.agent.core.a2a

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.agent.PlatformHttpClient

/**
 * A2A 最小实现单测：卡片解析、三个 JSON-RPC 方法往返、错误映射。
 * 全是 FakeTransport，不发真实请求、不碰真实第三方。
 */
class A2aClientTest {

    private class FakeHttp(
        var handler: (url: String, token: String?, body: String?) -> String =
            { _, _, _ -> "{}" },
    ) : PlatformHttpClient {
        var lastUrl: String = ""
        var lastToken: String? = null
        var lastBody: String? = null

        override fun postJson(url: String, bearerToken: String, bodyJson: String): String {
            lastUrl = url
            lastToken = bearerToken
            lastBody = bodyJson
            return handler(url, bearerToken, bodyJson)
        }

        override fun getJson(url: String, bearerToken: String?): String {
            lastUrl = url
            lastToken = bearerToken
            lastBody = null
            return handler(url, bearerToken, null)
        }
    }

    private val cardJson = """
        {
          "name": "WhereverCode V5",
          "description": "V5 平台端点",
          "url": "https://192.0.2.10/api/open/v1/a2a",
          "version": "1.2.0",
          "capabilities": {"streaming": true, "pushNotifications": false,
                           "stateTransitionHistory": true},
          "defaultInputModes": ["text"],
          "skills": [
            {"id": "task-execution", "name": "执行开发任务",
             "description": "接任务、写代码", "tags": ["code", "task"]}
          ],
          "authentication": {"schemes": ["Bearer"]}
        }
    """.trimIndent()

    private fun taskJson(state: String = "submitted"): String = """
        {
          "id": "task-123", "contextId": "task-123", "kind": "task",
          "status": {"state": "$state", "timestamp": "2026-11-11T22:00:00Z"},
          "artifacts": [
            {"artifactId": "delivery", "name": "交付证据",
             "parts": [{"kind": "text", "text": "branch: task/task-123 / commit: abc123"}]}
          ],
          "metadata": {"taskRef": "v5:task-123", "assignee": "codex",
                       "projectId": "v5", "round": 1, "maxRounds": 3,
                       "title": "修一个 bug"}
        }
    """.trimIndent()

    private fun envelope(resultJson: String): String =
        """{"jsonrpc": "2.0", "id": "1", "result": $resultJson}"""

    private fun errorEnvelope(message: String): String =
        """{"jsonrpc": "2.0", "id": "1",
            "error": {"code": -32602, "message": "$message"}}"""

    private fun client(fake: FakeHttp, token: String? = "v5_test-token") =
        A2aClient(
            tokenProvider = { token },
            httpClient = fake,
            endpoint = "https://192.0.2.10/api/open/v1/a2a",
            cardUrl = "https://192.0.2.10/.well-known/agent.json",
        )

    // -- Agent 卡 --

    @Test
    fun `card parses name url skills and streaming`() {
        val card = AgentCardParser.parse(cardJson)
        assertEquals("WhereverCode V5", card.name)
        assertEquals("https://192.0.2.10/api/open/v1/a2a", card.url)
        assertEquals("1.2.0", card.version)
        assertTrue(card.streaming)
        assertFalse(card.pushNotifications)
        assertEquals(1, card.skills.size)
        assertEquals("task-execution", card.skills[0].id)
        assertEquals(listOf("code", "task"), card.skills[0].tags)
        assertEquals(listOf("Bearer"), card.authSchemes)
    }

    @Test
    fun `card without name fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentCardParser.parse(JSONObject(cardJson).apply { remove("name") })
        }
    }

    @Test
    fun `card without url fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentCardParser.parse(JSONObject(cardJson).apply { remove("url") })
        }
    }

    // -- fetchCard --

    @Test
    fun `fetchCard GETs the card url without a token`() {
        val fake = FakeHttp { _, _, _ -> cardJson }
        val card = client(fake).fetchCard()
        assertEquals("https://192.0.2.10/.well-known/agent.json", fake.lastUrl)
        assertEquals(null, fake.lastToken)
        assertEquals("WhereverCode V5", card.name)
    }

    @Test
    fun `fetchCard rejects an unparseable card`() {
        val fake = FakeHttp { _, _, _ -> "not json" }
        assertThrows(IOException::class.java) { client(fake).fetchCard() }
    }

    // -- sendMessage --

    @Test
    fun `sendMessage posts assignee and goal and returns the task`() {
        val fake = FakeHttp { _, _, _ -> envelope(taskJson()) }
        val task = client(fake).sendMessage(
            assigneeId = "codex",
            goal = "修一个 bug",
            projectId = "v5",
            idempotencyKey = "a2a-test-1",
        )
        assertEquals("https://192.0.2.10/api/open/v1/a2a", fake.lastUrl)
        assertEquals("v5_test-token", fake.lastToken)
        val body = JSONObject(fake.lastBody!!)
        assertEquals("message/send", body.getString("method"))
        val params = body.getJSONObject("params")
        assertEquals("codex", params.getJSONObject("metadata").getString("assignee"))
        assertEquals("a2a-test-1", params.getJSONObject("metadata").getString("idempotencyKey"))
        val parts = params.getJSONObject("message").getJSONArray("parts")
        assertEquals("修一个 bug", parts.getJSONObject(0).getString("text"))
        assertEquals("task-123", task.id)
        assertEquals(A2aTaskState.SUBMITTED, task.state)
        assertEquals("codex", task.assignee)
        assertEquals("修一个 bug", task.title)
    }

    @Test
    fun `sendMessage maps a server error to IOException`() {
        val fake = FakeHttp { _, _, _ -> errorEnvelope("assignee 不存在：ghost") }
        val err = assertThrows(IOException::class.java) {
            client(fake).sendMessage("ghost", "修一个 bug")
        }
        assertTrue(err.message!!.contains("assignee 不存在"))
        assertFalse(err.message!!.contains("v5_test-token"))
    }

    @Test
    fun `calls without a token fail before any request`() {
        val fake = FakeHttp { _, _, _ -> envelope(taskJson()) }
        val err = assertThrows(IOException::class.java) {
            client(fake, token = null).getTask("task-123")
        }
        assertTrue(err.message!!.contains("未配置平台 Agent 凭证"))
        assertEquals("", fake.lastUrl)
    }

    // -- getTask --

    @Test
    fun `getTask maps working state and flattens artifacts`() {
        val fake = FakeHttp { _, _, _ -> envelope(taskJson("working")) }
        val task = client(fake).getTask("task-123", historyLength = 5)
        val body = JSONObject(fake.lastBody!!)
        assertEquals("tasks/get", body.getString("method"))
        assertEquals(5, body.getJSONObject("params").getInt("historyLength"))
        assertEquals(A2aTaskState.WORKING, task.state)
        assertEquals(listOf("branch: task/task-123 / commit: abc123"), task.artifacts)
        assertEquals(1, task.round)
        assertEquals(3, task.maxRounds)
    }

    @Test
    fun `state mapping covers every server state`() {
        assertEquals(A2aTaskState.SUBMITTED, A2aTaskParser.stateOf("submitted"))
        assertEquals(A2aTaskState.WORKING, A2aTaskParser.stateOf("working"))
        assertEquals(A2aTaskState.COMPLETED, A2aTaskParser.stateOf("completed"))
        assertEquals(A2aTaskState.CANCELED, A2aTaskParser.stateOf("canceled"))
        assertEquals(A2aTaskState.INPUT_REQUIRED, A2aTaskParser.stateOf("input-required"))
        assertEquals(A2aTaskState.UNKNOWN, A2aTaskParser.stateOf("something-new"))
    }

    // -- resubscribe --

    @Test
    fun `resubscribe returns snapshot and stream path`() {
        val fake = FakeHttp { _, _, _ ->
            envelope(
                """{"taskId": "task-123", "task": ${taskJson("working")},
                    "streamPath": "/api/open/v1/tasks/ws",
                    "note": "断线后调一次 tasks/get 补快照"}""",
            )
        }
        val resub = client(fake).resubscribe("task-123")
        val body = JSONObject(fake.lastBody!!)
        assertEquals("tasks/resubscribe", body.getString("method"))
        assertEquals("task-123", resub.task.id)
        assertEquals(A2aTaskState.WORKING, resub.task.state)
        assertEquals("/api/open/v1/tasks/ws", resub.streamPath)
        assertTrue(resub.note.contains("tasks/get"))
    }

    // -- 端点拼法 --

    @Test
    fun `endpoint and card url follow the platform host rule`() {
        assertEquals(
            "https://192.0.2.10/api/open/v1/a2a",
            A2aClient.a2aEndpoint("192.0.2.10"),
        )
        assertEquals(
            "https://192.0.2.10/.well-known/agent.json",
            A2aClient.agentCardUrl("192.0.2.10"),
        )
        assertEquals(
            "",
            A2aClient.a2aEndpoint(null),
        )
    }
}
