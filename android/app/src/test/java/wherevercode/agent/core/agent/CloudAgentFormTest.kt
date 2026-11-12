package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind

/**
 * 云端 Agent 表单（V5，2026-11-11）：老项目“添加云端 Agent”对话框。
 * 纯逻辑，不碰 Android、不读存储、不发网络、不生成钥匙。
 */
class CloudAgentFormTest {

    private fun build(
        name: String = "Grok 云端",
        agentId: String = "grok-cloud",
        cloudHomeDeviceId: String? = null,
        roles: List<String> = listOf("dev", "review"),
        canDispatch: Boolean = true,
        keyRef: String? = "cloud-agent:grok-cloud:mcp-token",
    ) = CloudAgentForm.buildRecord(
        name = name,
        agentId = agentId,
        cloudHomeDeviceId = cloudHomeDeviceId,
        roles = roles,
        canDispatch = canDispatch,
        keyRef = keyRef,
        capabilities = emptyList(),
        dispatchable = true,
        knownLimits = null,
        existing = null,
    )

    @Test
    fun `valid cloud agent builds a connector record with default roles`() {
        val result = build()
        val record = (result as CloudAgentForm.Result.Ok).record
        assertEquals("grok-cloud", record.agentId)
        assertEquals(AgentKind.CLOUD, record.kind)
        assertEquals(AgentAccess.CONNECTOR, record.access)
        assertEquals(BrainKind.CLOUD_AGENT, record.brainKind)
        assertEquals("cloud-agent", record.adapterId)
        assertEquals(listOf("dev", "review"), record.cloudRoles)
        assertTrue(record.canDispatch)
        assertEquals(null, record.cloudHomeDeviceId)
        assertEquals("cloud-agent:grok-cloud:mcp-token", record.authRef)
        assertTrue(record.validate().isEmpty())
        assertTrue(CloudAgent.isCloudAgentRecord(record))
    }

    @Test
    fun `grouping under a device keeps kind cloud and does not set deviceId`() {
        val record = (build(cloudHomeDeviceId = "mac") as CloudAgentForm.Result.Ok).record
        assertEquals("mac", record.cloudHomeDeviceId)
        assertEquals(AgentKind.CLOUD, record.kind)
        assertEquals(null, record.deviceId)
        assertTrue(record.validate().isEmpty())
    }

    @Test
    fun `empty roles are rejected`() {
        val result = build(roles = emptyList())
        assertTrue((result as CloudAgentForm.Result.Rejected).message.contains("至少勾一个"))
    }

    @Test
    fun `unknown role is rejected`() {
        val result = build(roles = listOf("dev", "fly"))
        assertTrue((result as CloudAgentForm.Result.Rejected).message.contains("未知角色"))
    }

    @Test
    fun `missing key ref is rejected`() {
        val result = build(keyRef = null)
        assertTrue((result as CloudAgentForm.Result.Rejected).message.contains("钥匙引用名"))
    }

    @Test
    fun `blank name is rejected`() {
        val result = build(name = "  ")
        assertTrue((result as CloudAgentForm.Result.Rejected).message.contains("名字"))
    }

    @Test
    fun `deliver role and dispatch off are kept`() {
        val record = (build(
            roles = listOf("dev", "review", "deliver"),
            canDispatch = false,
        ) as CloudAgentForm.Result.Ok).record
        assertEquals(listOf("dev", "review", "deliver"), record.cloudRoles)
        assertTrue(!record.canDispatch)
        assertEquals("开发、审查、递代码", CloudAgent.rolesLabel(record.cloudRoles))
        assertTrue(record.validate().isEmpty())
    }
}
