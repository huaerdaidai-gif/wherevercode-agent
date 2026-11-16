package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量解析与摘要（AgentUsageParser / AgentUsage）的纯逻辑测试。
 * 样本形状与 Hand 侧 agent_usage 回包一致（Codex 实测字段：
 * planType=plus → plan=Plus、300 分钟窗口=5h、10080=weekly、credits）。
 */
class AgentUsageTest {

    private val sampleJson = """
        [
          {"agent_id":"codex","provider":"codex","status":"ok","plan":"Plus",
           "windows":[
             {"kind":"5h","window_minutes":300,"used_percent":0,"resets_at":1794882581},
             {"kind":"weekly","window_minutes":10080,"used_percent":100,"resets_at":1795186737}
           ],
           "credits_balance":"0","credits_unlimited":false,
           "queried_at":1794864904,"detail":""},
          {"agent_id":"agy","provider":"agy","status":"unsupported","plan":null,
           "windows":[],"credits_balance":null,"credits_unlimited":false,
           "queried_at":1794864904,"detail":"该 Agent 暂无用量查询规格（未声明支持）"}
        ]
    """.trimIndent()

    @Test
    fun `parses codex usage into the display schema`() {
        val usages = AgentUsageParser.parseList(sampleJson)
        assertEquals(2, usages.size)
        val codex = usages[0]
        assertTrue(codex.isOk)
        assertEquals("Plus", codex.plan)
        assertEquals(0.0, codex.fiveHourWindow()!!.usedPercent, 0.001)
        assertEquals(100.0, codex.weeklyWindow()!!.usedPercent, 0.001)
        assertEquals(1795186737L, codex.weeklyWindow()!!.resetsAtEpochSeconds)
        assertEquals("0", codex.creditsBalance)
        assertEquals("Plus · 5 小时 0% · 每周 100% · 积分 0", codex.summaryLine())
    }

    @Test
    fun `non ok status keeps detail and no fake numbers`() {
        val agy = AgentUsageParser.parseList(sampleJson)[1]
        assertEquals("unsupported", agy.status)
        assertNull(agy.plan)
        assertTrue(agy.windows.isEmpty())
        assertEquals("该 Agent 暂无用量查询规格（未声明支持）", agy.summaryLine())
    }

    @Test
    fun `bad json parses to empty list`() {
        assertTrue(AgentUsageParser.parseList("not json").isEmpty())
        assertTrue(AgentUsageParser.parseList("{}").isEmpty())
        assertTrue(AgentUsageParser.parseList("[]").isEmpty())
    }

    @Test
    fun `percent formatting drops trailing zero`() {
        assertEquals("0%", AgentUsage.formatPercent(0.0))
        assertEquals("100%", AgentUsage.formatPercent(100.0))
        assertEquals("12.5%", AgentUsage.formatPercent(12.5))
    }
}
