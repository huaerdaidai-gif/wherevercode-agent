package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind

/**
 * 云端 Agent 设置说明（V5，2026-11-11）：老项目各 App 设置说明
 * （身份验证一律选“无”；连接地址填进对方 App 的自定义连接器，
 * 钥匙放进 Authorization: Bearer 请求头）。纯函数，不读存储。
 */
class CloudAgentSetupSheetTest {

    private fun cloudAgent() = AgentRecord(
        agentId = "grok-cloud",
        displayName = "Grok 云端",
        kind = AgentKind.CLOUD,
        access = AgentAccess.CONNECTOR,
        brainKind = BrainKind.CLOUD_AGENT,
        adapterId = "cloud-agent",
        authRef = "cloud-agent:grok-cloud:mcp-token",
        cloudRoles = listOf("dev", "review"),
        canDispatch = true,
    )

    @Test
    fun `sheet covers all five app wordings with auth none`() {
        val sheet = CloudAgentSetupSheet.build(
            cloudAgent(),
            "wco_test-key-not-real",
            null,
        )

        for (app in listOf("Grok", "ChatGPT", "Manus", "Muse", "其他")) {
            assertTrue("missing section: $app", sheet.contains(app))
        }
        assertTrue(sheet.contains("身份验证选“无”"))
        assertTrue(sheet.contains("Authorization: Bearer wco_test-key-not-real"))
        assertTrue(sheet.contains("开发、审查"))
        assertTrue(sheet.contains("允许它派活：开"))
    }

    @Test
    fun `sheet says the endpoint is not open yet instead of inventing an address`() {
        val sheet = CloudAgentSetupSheet.build(
            cloudAgent(),
            "wco_test-key-not-real",
            null,
        )

        assertTrue(sheet.contains("尚未开通"))
        assertTrue(sheet.contains("没有公网地址"))
        assertTrue(!sheet.contains("127.0.0.1"))
        assertTrue(!sheet.contains("example.invalid/api/open/v1/mcp"))
        assertEquals(null, CloudAgentMcpEndpoint.publicUrl())
    }

    @Test
    fun `sheet shows the real endpoint when one exists`() {
        val sheet = CloudAgentSetupSheet.build(
            cloudAgent(),
            "wco_test-key-not-real",
            "https://relay.example/mcp",
        )

        assertTrue(sheet.contains("https://relay.example/mcp"))
        assertTrue(!sheet.contains("尚未开通"))
    }

    @Test
    fun `manus section carries the extra user agent and chatgpt the schedule rule`() {
        val sheet = CloudAgentSetupSheet.build(
            cloudAgent(),
            "wco_test-key-not-real",
            null,
        )

        assertTrue(sheet.contains("User-Agent: WhereverCode-CloudAgent/1.0"))
        assertTrue(sheet.contains("每天定时"))
        assertTrue(sheet.contains("每 2 小时"))
    }

    @Test
    fun `dispatch off and device grouping are stated`() {
        val sheet = CloudAgentSetupSheet.build(
            cloudAgent().copy(canDispatch = false, cloudHomeDeviceId = "mac"),
            "wco_test-key-not-real",
            null,
        )

        assertTrue(sheet.contains("允许它派活：关"))
        assertTrue(sheet.contains("归到设备 mac 下"))
    }
}
