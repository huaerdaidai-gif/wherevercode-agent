package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind

/**
 * Agent 开关（maintainer 2026-11-12 定：每个 Agent 都要有开/关，支持热插拔）。
 *
 * 覆盖：
 * - 新记录默认启用（热插拔“加”：加完立刻可用）；
 * - enabledAgents 只留启用的（选择器、建议共用同一口径）；
 * - setAgentEnabled 翻开关并持久化、未知 id 返回 false；
 * - 停用原因文案（泵留队、发送守门共用）。
 *
 * 纯内存存储，不碰 Android 框架；SharedPreferences 版往返与老记录
 * 兼容见 SharedPreferencesAgentStoreTest。
 */
class AgentToggleTest {

    private fun apiRecord(agentId: String = "deepseek-1") = AgentRecord(
        agentId = agentId,
        displayName = "DeepSeek 闪电",
        kind = AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = BrainKind.MODEL_API,
        adapterId = "deepseek",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-flash",
        authRef = "key:deepseek-1",
    )

    @Test
    fun `new record defaults to enabled`() {
        assertTrue(apiRecord().enabled)
        assertTrue(apiRecord().copy(enabled = false).validate().isEmpty())
    }

    @Test
    fun `enabledAgents keeps only enabled in order`() {
        val agents = listOf(
            apiRecord("a"),
            apiRecord("b").copy(enabled = false),
            apiRecord("c"),
            apiRecord("d").copy(enabled = false),
        )
        assertEquals(listOf("a", "c"), agents.enabledAgents().map { it.agentId })
        assertEquals(emptyList<String>(), emptyList<AgentRecord>().enabledAgents().map { it.agentId })
    }

    @Test
    fun `setAgentEnabled disables and persists, other fields intact`() {
        val store = InMemoryAgentStore()
        store.saveAgent(apiRecord("a"))
        store.saveAgent(apiRecord("b"))
        assertTrue(store.setAgentEnabled("a", false))
        val back = store.getAgent("a")!!
        assertFalse(back.enabled)
        assertEquals("DeepSeek 闪电", back.displayName)
        assertEquals("deepseek", back.adapterId)
        assertEquals("https://api.deepseek.com", back.baseUrl)
        // 另一条不受影响。
        assertTrue(store.getAgent("b")!!.enabled)
        assertEquals(listOf("b"), store.listAgents().enabledAgents().map { it.agentId })
    }

    @Test
    fun `setAgentEnabled re-enables a disabled agent`() {
        val store = InMemoryAgentStore()
        store.saveAgent(apiRecord("a").copy(enabled = false))
        assertFalse(store.getAgent("a")!!.enabled)
        assertTrue(store.setAgentEnabled("a", true))
        assertTrue(store.getAgent("a")!!.enabled)
        assertEquals(listOf("a"), store.listAgents().enabledAgents().map { it.agentId })
    }

    @Test
    fun `setAgentEnabled unknown id returns false`() {
        val store = InMemoryAgentStore()
        assertFalse(store.setAgentEnabled("no-such-agent", false))
        assertFalse(store.setAgentEnabled("no-such-agent", true))
        assertTrue(store.listAgents().isEmpty())
    }

    @Test
    fun `setAgentEnabled same state returns true and keeps record`() {
        val store = InMemoryAgentStore()
        store.saveAgent(apiRecord("a"))
        assertTrue(store.setAgentEnabled("a", true))
        assertTrue(store.getAgent("a")!!.enabled)
    }

    @Test
    fun `disabledAgentReason names the agent and tells how to re-enable`() {
        val reason = disabledAgentReason(apiRecord("a").copy(enabled = false))
        assertTrue(reason.contains("DeepSeek 闪电"))
        assertTrue(reason.contains("已停用"))
        assertTrue(reason.contains("重新启用"))
    }
}
