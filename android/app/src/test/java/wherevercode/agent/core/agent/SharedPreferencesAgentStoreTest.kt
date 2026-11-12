package wherevercode.agent.core.agent

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.brain.BrainKind

/**
 * SharedPreferencesAgentStore（阶段 3，A1 P1）：JSON 持久化往返、
 * 坏行容错。Robolectric（需要真实 Context 拿 SharedPreferences）。
 */
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesAgentStoreTest {

    private fun store(): SharedPreferencesAgentStore {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(
            SharedPreferencesAgentStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
        return SharedPreferencesAgentStore(context)
    }

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
        capabilities = listOf("dev", "review"),
        quotaRule = "按厂商额度",
        knownLimits = "只读工作区，不能写代码",
    )

    @Test
    fun `round trip preserves all fields`() {
        val store = store()
        store.saveAgent(apiRecord())
        val back = store.getAgent("deepseek-1")
        assertEquals("DeepSeek 闪电", back?.displayName)
        assertEquals(AgentKind.CLOUD, back?.kind)
        assertEquals(AgentAccess.API, back?.access)
        assertEquals(BrainKind.MODEL_API, back?.brainKind)
        assertEquals("deepseek", back?.adapterId)
        assertEquals("https://api.deepseek.com", back?.baseUrl)
        assertEquals("deepseek-flash", back?.model)
        assertEquals("key:deepseek-1", back?.authRef)
        assertEquals(listOf("dev", "review"), back?.capabilities)
        assertEquals("按厂商额度", back?.quotaRule)
        assertEquals("只读工作区，不能写代码", back?.knownLimits)
        assertTrue(back?.dispatchable == true)
        assertEquals(AgentLoginState.UNKNOWN, back?.loginState)
    }

    @Test
    fun `second store instance sees the same data`() {
        val first = store()
        first.saveAgent(apiRecord())
        val second = SharedPreferencesAgentStore(RuntimeEnvironment.getApplication())
        assertEquals(1, second.listAgents().size)
        assertEquals("deepseek-1", second.listAgents()[0].agentId)
    }

    @Test
    fun `getAgent falls back to case-insensitive match`() {
        // 2026-11-12 回复归属修复：与内存版同口径——注册 "codex"，
        // 派活方传显示名 "Codex" 时也应查到（见 AgentStoreTest）。
        val store = store()
        store.saveAgent(apiRecord(agentId = "codex").copy(displayName = "Codex"))
        assertEquals("codex", store.getAgent("Codex")?.agentId)
        assertEquals("codex", store.getAgent("CODEX")?.agentId)
        assertEquals("codex", store.getAgent("codex")?.agentId)
        assertNull(store.getAgent("unknown"))
    }

    @Test
    fun `overwrite and delete work`() {
        val store = store()
        store.saveAgent(apiRecord())
        store.saveAgent(apiRecord().copy(displayName = "改名"))
        assertEquals(1, store.listAgents().size)
        assertEquals("改名", store.getAgent("deepseek-1")?.displayName)
        store.deleteAgent("deepseek-1")
        assertNull(store.getAgent("deepseek-1"))
    }

    @Test
    fun `corrupt json is tolerated as an empty list`() {
        val context = RuntimeEnvironment.getApplication()
        val store = store()
        context.getSharedPreferences(
            SharedPreferencesAgentStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().putString(SharedPreferencesAgentStore.KEY_AGENTS, "这不是 JSON").commit()
        assertTrue(store.listAgents().isEmpty())
    }

    @Test
    fun `one bad row does not destroy the good rows`() {
        val context = RuntimeEnvironment.getApplication()
        val store = store()
        val json = """[{"agentId":"good-1","displayName":"好的","kind":"CLOUD","access":"API","brainKind":"MODEL_API","adapterId":"deepseek","baseUrl":"https://api.deepseek.com","model":"deepseek-flash"},{"agentId":"bad-1","displayName":"坏的","kind":"NOPE","access":"API","brainKind":"MODEL_API","adapterId":"x"}]"""
        context.getSharedPreferences(
            SharedPreferencesAgentStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        ).edit().putString(SharedPreferencesAgentStore.KEY_AGENTS, json).commit()
        val agents = store.listAgents()
        assertEquals(1, agents.size)
        assertEquals("good-1", agents[0].agentId)
    }

    @Test
    fun `save refuses invalid records and keeps the old data`() {
        val store = store()
        store.saveAgent(apiRecord())
        assertThrows(IllegalArgumentException::class.java) {
            store.saveAgent(apiRecord().copy(agentId = "Bad Id"))
        }
        assertEquals(1, store.listAgents().size)
    }

    @Test
    fun `round trip preserves enabled flag`() {
        // Agent 开关（2026-11-12）：停用态要落盘，重启 App 后仍是停用。
        val store = store()
        store.saveAgent(apiRecord())
        store.saveAgent(apiRecord("codex-1").copy(displayName = "Codex", enabled = false))
        assertTrue(store.getAgent("deepseek-1")!!.enabled)
        assertEquals(false, store.getAgent("codex-1")!!.enabled)
    }

    @Test
    fun `record without enabled field reads as enabled`() {
        // Agent 开关（2026-11-12）：老记录没有该字段时按启用读——
        // 升级不断掉已有 Agent（绝不因升级被误关）。
        val context = RuntimeEnvironment.getApplication()
        val store = store()
        store.saveAgent(apiRecord())
        val prefs = context.getSharedPreferences(
            SharedPreferencesAgentStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val raw = prefs.getString(SharedPreferencesAgentStore.KEY_AGENTS, null)!!
        val array = org.json.JSONArray(raw)
        array.getJSONObject(0).remove("enabled")
        prefs.edit().putString(SharedPreferencesAgentStore.KEY_AGENTS, array.toString()).commit()
        assertEquals(true, store.getAgent("deepseek-1")!!.enabled)
    }
}
