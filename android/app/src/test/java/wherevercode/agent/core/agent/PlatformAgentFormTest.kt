package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind

/**
 * 平台 Agent 表单（V5，2026-11-11）：老项目能加 example agent，V5 也要能加。
 * 纯逻辑，不碰 Android、不读存储、不发网络。
 */
class PlatformAgentFormTest {

    private fun build(
        name: String = "Example Agent",
        agentId: String = "example-agent",
        platformAgentId: String? = "example-agent",
        platformHost: String? = "example.invalid",
        platformChannelId: String? = null,
        webhookUrl: String? = "https://example.com/hook",
        webhookSecretRef: String? = null,
        directGithubRead: Boolean = true,
        existing: AgentRecord? = null,
    ) = PlatformAgentForm.buildRecord(
        name = name,
        agentId = agentId,
        platformAgentId = platformAgentId,
        platformHost = platformHost,
        platformChannelId = platformChannelId,
        webhookUrl = webhookUrl,
        webhookSecretRef = webhookSecretRef,
        directGithubRead = directGithubRead,
        capabilities = listOf("review"),
        dispatchable = true,
        knownLimits = null,
        existing = existing,
    )

    @Test
    fun `valid platform agent builds a webhook record`() {
        val result = build()
        val record = (result as PlatformAgentForm.Result.Ok).record
        assertEquals("example-agent", record.agentId)
        assertEquals("Example Agent", record.displayName)
        assertEquals(AgentKind.CLOUD, record.kind)
        assertEquals(AgentAccess.WEBHOOK, record.access)
        assertEquals(BrainKind.PLATFORM_AGENT, record.brainKind)
        assertEquals("platform-agent", record.adapterId)
        assertEquals("example-agent", record.platformAgentId)
        assertEquals("example.invalid", record.platformHost)
        assertEquals("https://example.invalid", record.baseUrl)
        assertEquals("https://example.invalid/api/open/v1/mcp", PlatformAgent.mcpEndpoint(record.platformHost))
        // 未填写频道时不注入任何服务器标识。
        assertEquals(null, record.platformChannelId)
        assertTrue(!PlatformAgent.isSharedChannel(record.platformChannelId))
        assertEquals("https://example.com/hook", record.webhookUrl)
        assertTrue(record.directGithubRead)
        assertTrue(record.validate().isEmpty())
        assertTrue(PlatformAgent.isPlatformAgentRecord(record))
    }

    @Test
    fun `public build requires a platform host when no local default is configured`() {
        val result = build(platformHost = "  ")
        assertTrue(result is PlatformAgentForm.Result.Rejected)
    }

    @Test
    fun `endpoint follows the platform host field`() {
        val record = (build(platformHost = "us.example") as PlatformAgentForm.Result.Ok).record
        assertEquals("us.example", record.platformHost)
        assertEquals("https://us.example", record.baseUrl)
        assertEquals("https://us.example/api/open/v1/mcp", PlatformAgent.mcpEndpoint(record.platformHost))
    }

    @Test
    fun `platform host with a scheme is rejected`() {
        val result = build(platformHost = "https://example.invalid")
        val rejected = result as PlatformAgentForm.Result.Rejected
        assertTrue(rejected.message.contains("平台地址只填主机名"))
    }

    @Test
    fun `dedicated channel id is stored when filled`() {
        val record = (build(platformChannelId = "chan-v5-dedicated") as PlatformAgentForm.Result.Ok).record
        assertEquals("chan-v5-dedicated", record.platformChannelId)
        assertEquals("chan-v5-dedicated", PlatformAgent.effectiveChannelId(record.platformChannelId))
        assertTrue(!PlatformAgent.isSharedChannel(record.platformChannelId))
    }

    @Test
    fun `blank channel id stays null without a configured default`() {
        val record = (build(platformChannelId = "   ") as PlatformAgentForm.Result.Ok).record
        assertEquals(null, record.platformChannelId)
        assertEquals("", PlatformAgent.effectiveChannelId(record.platformChannelId))
        assertTrue(!PlatformAgent.isSharedChannel(record.platformChannelId))
    }

    @Test
    fun `editing keeps a dedicated channel when the field still carries it`() {
        val first = (build(platformChannelId = "chan-v5-dedicated") as PlatformAgentForm.Result.Ok).record
        val second = (build(platformChannelId = "chan-v5-dedicated", existing = first) as PlatformAgentForm.Result.Ok).record
        assertEquals("chan-v5-dedicated", second.platformChannelId)
    }

    @Test
    fun `editing clears back to the shared group when the channel field is emptied`() {
        val first = (build(platformChannelId = "chan-v5-dedicated") as PlatformAgentForm.Result.Ok).record
        val second = (build(platformChannelId = "  ", existing = first) as PlatformAgentForm.Result.Ok).record
        assertEquals(null, second.platformChannelId)
        assertTrue(PlatformAgent.isSharedChannel(second.platformChannelId))
    }

    @Test
    fun `channel id with a space is rejected`() {
        val result = build(platformChannelId = "chan 1")
        val rejected = result as PlatformAgentForm.Result.Rejected
        assertTrue(rejected.message.contains("专用频道 ID 不能含空格"))
    }

    @Test
    fun `missing platform agent id is rejected with a usable message`() {
        val result = build(platformAgentId = "  ")
        val rejected = result as PlatformAgentForm.Result.Rejected
        assertTrue(rejected.message.contains("平台 Agent ID"))
    }

    @Test
    fun `blank name is rejected`() {
        val result = build(name = "   ")
        assertTrue((result as PlatformAgentForm.Result.Rejected).message.contains("名字"))
    }

    @Test
    fun `webhook url without a scheme is rejected`() {
        val result = build(webhookUrl = "example.com/hook")
        assertTrue((result as PlatformAgentForm.Result.Rejected).message.contains("webhook"))
    }

    @Test
    fun `webhook url is optional`() {
        val record = (build(webhookUrl = null) as PlatformAgentForm.Result.Ok).record
        assertEquals(null, record.webhookUrl)
        assertTrue(record.validate().isEmpty())
    }

    @Test
    fun `platform record never carries a cli binary or device`() {
        val record = (build() as PlatformAgentForm.Result.Ok).record
        assertEquals(null, record.binary)
        assertEquals(null, record.deviceId)
        assertEquals(null, record.authRef)
    }

    @Test
    fun `agent name normalization matches example-agent to example agent`() {
        assertTrue(PlatformAgent.sameAgent("example-agent", "example agent"))
        assertTrue(PlatformAgent.sameAgent("Example_Agent", "example-agent"))
        assertTrue(!PlatformAgent.sameAgent("example-agent", "grok"))
        assertEquals("@example-agent 在吗", PlatformAgent.buildWireText("example-agent", "在吗"))
    }
}
