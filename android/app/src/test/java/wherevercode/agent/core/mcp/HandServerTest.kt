package wherevercode.agent.core.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.control.ControlStatus
import wherevercode.agent.core.control.ExecutionFence
import wherevercode.agent.core.control.InMemoryControlStore
import wherevercode.agent.core.control.LocalExecutionFence
import wherevercode.agent.core.security.InMemoryAuditLog
import wherevercode.agent.core.security.InMemoryAuthorizationStore
import wherevercode.agent.core.security.InMemoryRevocationStore
import wherevercode.agent.core.security.TaskCredentialIssuer
import wherevercode.agent.core.security.toWire
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore

/**
 * HandServer control tests (M2, plan §2.1). Pure JVM: fake hand +
 * in-memory stores + fences. No Room, no network.
 */
class HandServerTest {

    private class FakeTaskStore(vararg tasks: TaskRecord) : TaskStore {
        private val map = tasks.associateBy { it.taskId }.toMutableMap()
        override fun createTask(record: TaskRecord): TaskRecord {
            map[record.taskId] = record
            return record
        }

        override fun listTasks(projectId: String?, sessionId: String?): List<TaskRecord> =
            map.values.toList()

        override fun getTask(taskId: String): TaskRecord? = map[taskId]

        override fun updateTask(
            taskId: String,
            changes: Map<String, Any?>,
            expectedVersion: Long,
            expectedGoalVersion: Long,
            expectedAuthEpoch: Long,
        ): TaskRecord? = map[taskId]

        override fun expireLeases(nowEpochSeconds: Long): List<String> = emptyList()

        override fun deleteTask(taskId: String): Boolean = map.remove(taskId) != null
    }

    private class CapturingHand : HandExecutor {
        var calls = 0
        val actions = mutableListOf<String>()

        override fun call(action: String, args: Map<String, String>, taskId: String): Map<String, Any?> {
            calls++
            actions += action
            return mapOf("ok" to true, "action" to action)
        }
    }

    private val signingKey = "test-signing-key".toByteArray(Charsets.UTF_8)
    private val now = 1_700_000_000L
    private lateinit var hand: CapturingHand
    private lateinit var authStore: InMemoryAuthorizationStore
    private lateinit var controlStore: InMemoryControlStore
    private lateinit var fence: ExecutionFence

    @Before
    fun setUp() {
        hand = CapturingHand()
        authStore = InMemoryAuthorizationStore()
        authStore.grant("agent-1", "/proj")
        controlStore = InMemoryControlStore()
        fence = ExecutionFence(
            taskStore = FakeTaskStore(TaskRecord(taskId = "t1", title = "t1", state = "working")),
            authStore = authStore,
            revocationStore = InMemoryRevocationStore(),
            controlStore = controlStore,
            signingKey = signingKey,
            nowEpochSeconds = { now },
        )
    }

    private fun server(fence: ExecutionFence = this.fence) =
        HandServer(hand, fence, InMemoryAuditLog(), ownDeviceId = "dev-b")

    private fun credentialWire(): String = TaskCredentialIssuer.issue(
        userId = "user-1", taskId = "t1", attempt = "a1",
        sourceDeviceId = "dev-a", targetDeviceId = "dev-b", agentId = "agent-1",
        folder = "/proj", signingKey = signingKey, epoch = 1, goalVersion = 1,
        nowEpochSeconds = now,
    ).toWire()

    private fun toolCallLine(
        id: Int,
        seq: Long,
        action: String,
        credentialWire: String? = credentialWire(),
        args: Map<String, String> = mapOf("path" to "README.md"),
    ): String {
        val envelope = JSONObject()
        envelope.put("type", "cross_device_tool_call")
        envelope.put("version", 1)
        envelope.put("seq", seq)
        envelope.put("task_id", "t1")
        envelope.put("agent_id", "agent-1")
        envelope.put("source_device_id", "dev-a")
        envelope.put("target_device_id", "dev-b")
        envelope.put("folder", "/proj")
        envelope.put("action", action)
        val argsObj = JSONObject()
        args.forEach { (k, v) -> argsObj.put(k, v) }
        envelope.put("args", argsObj)
        if (credentialWire != null) envelope.put("credential", credentialWire)
        val params = JSONObject()
        params.put("name", action)
        params.put("arguments", envelope)
        val request = JSONObject()
        request.put("jsonrpc", "2.0")
        request.put("id", id)
        request.put("method", "tools/call")
        request.put("params", params)
        return request.toString()
    }

    private fun isError(response: String?): Boolean {
        val obj = JSONObject(response ?: "")
        val result = obj.optJSONObject("result") ?: return true
        return result.optBoolean("isError", true)
    }

    private fun responseText(response: String?): String {
        val obj = JSONObject(response ?: "")
        val result = obj.optJSONObject("result") ?: return ""
        val content = result.optJSONArray("content") ?: return ""
        return (0 until content.length())
            .map { content.optJSONObject(it)?.optString("text", "") ?: "" }
            .joinToString("")
    }

    @Test
    fun `fence denial reaches hand zero times`() {
        authStore.revoke("agent-1", "/proj")
        val response = server().handleLine(toolCallLine(1, 1, "write"))
        assertTrue(isError(response))
        assertEquals(0, hand.calls)
        assertTrue(responseText(response).contains("授权"))
    }

    @Test
    fun `PAUSED task write is denied, read is allowed`() {
        controlStore.setControlStatus("t1", ControlStatus.PAUSED)
        val writeResponse = server().handleLine(toolCallLine(1, 1, "write"))
        assertTrue(isError(writeResponse))
        assertEquals(0, hand.calls)
        assertTrue(responseText(writeResponse).contains("已暂停"))
        val readResponse = server().handleLine(toolCallLine(2, 2, "read"))
        assertEquals(false, isError(readResponse))
        assertEquals(1, hand.calls)
        assertEquals(listOf("read"), hand.actions)
    }

    @Test
    fun `stdio local mode allows without credential when granted, denies without grant`() {
        // P1-3 closure: stdio no longer has an empty credential stub; it
        // runs through LocalExecutionFence （本地模式）.
        val localFence = LocalExecutionFence(
            taskStore = FakeTaskStore(TaskRecord(taskId = "t1", title = "t1", state = "working")),
            authStore = authStore,
            controlStore = controlStore,
        )
        val localServer = server(localFence)
        val allowed = localServer.handleLine(toolCallLine(1, 1, "write", credentialWire = null))
        assertEquals(false, isError(allowed))
        assertEquals(1, hand.calls)
        authStore.revoke("agent-1", "/proj")
        val denied = localServer.handleLine(toolCallLine(2, 2, "write", credentialWire = null))
        assertTrue(isError(denied))
        assertEquals(1, hand.calls)
    }

    @Test
    fun `LAN fence without credential denies`() {
        val response = server().handleLine(toolCallLine(1, 1, "write", credentialWire = null))
        assertTrue(isError(response))
        assertEquals(0, hand.calls)
        assertTrue(responseText(response).contains("凭证"))
    }

    @Test
    fun `stale seq is rejected before the fence`() {
        val s = server()
        val first = s.handleLine(toolCallLine(1, 5, "write"))
        assertEquals(false, isError(first))
        assertEquals(1, hand.calls)
        // Same seq again: replay guard refuses before the fence runs.
        val replay = s.handleLine(toolCallLine(2, 5, "write"))
        assertTrue(isError(replay))
        assertTrue(responseText(replay).contains("stale_sequence"))
        assertEquals(1, hand.calls)
    }

    @Test
    fun `control denial error text is Chinese and actionable`() {
        controlStore.setControlStatus("t1", ControlStatus.PAUSED)
        val response = server().handleLine(toolCallLine(1, 1, "write"))
        val text = responseText(response)
        assertTrue("错误文案须为中文", text.contains("已暂停"))
        assertTrue("错误文案须可操作（请先恢复）", text.contains("请先恢复"))
        assertTrue(text.contains("fence_paused"))
    }
}
