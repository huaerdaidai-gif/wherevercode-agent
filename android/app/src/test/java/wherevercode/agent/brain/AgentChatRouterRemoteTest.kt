package wherevercode.agent.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.siwc.BrainBootstrap
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentLoginState
import wherevercode.agent.core.agent.AgentRecord

/**
 * 阶段 B（V5 紧急开发）：远程设备 Agent 终于有路可走。
 *
 * 红测试：AgentChatRoute.UseRemoteAgent / resolve 的 isRemoteAgent
 * 参数 / deviceLabel 的远程分支尚不存在。
 *
 * 规则（阶段 A 的延续）：远程 Agent 不再一律 NotRoutable“未接入”；
 * 只有配对已确认、设备已添加的才走 UseRemoteAgent，其余仍诚实报错，
 * 绝不静默回落 ChatGPT。
 */
class AgentChatRouterRemoteTest {

    private fun remoteAgent() = AgentRecord(
        agentId = "example-agent",
        displayName = "Example Agent",
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.AGENT_RUNTIME,
        adapterId = "example-agent",
        binary = "example-agent",
        deviceId = "remote-host",
        deviceFolder = "/workspace",
    )

    private fun unconnectedRemoteAgent() = AgentRecord(
        agentId = "example-agent",
        displayName = "Example Agent",
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.AGENT_RUNTIME,
        adapterId = "example-agent",
        binary = "example-agent",
        deviceId = "remote-host",
        deviceFolder = "/workspace",
    )

    @Test
    fun `paired remote agent routes to UseRemoteAgent not NotRoutable`() {
        val route = AgentChatRouter.resolve(
            remoteAgent(),
            configs = emptyList(),
            isBrainConfigured = { false },
            isRemoteAgent = { it.agentId == "example-agent" },
        )
        assertTrue(route is AgentChatRoute.UseRemoteAgent)
        assertEquals("example-agent", (route as AgentChatRoute.UseRemoteAgent).agent.agentId)
    }

    @Test
    fun `unpaired remote agent still errors honestly and sends nothing`() {
        val route = AgentChatRouter.resolve(
            unconnectedRemoteAgent(),
            configs = emptyList(),
            isBrainConfigured = { false },
            isRemoteAgent = { false },
        )
        assertTrue(route is AgentChatRoute.NotRoutable)
        assertTrue((route as AgentChatRoute.NotRoutable).message.contains("未接入"))
    }

    @Test
    fun `remote agent without device id is not routable as remote`() {
        val noDevice = remoteAgent().copy(deviceId = null)
        val route = AgentChatRouter.resolve(
            noDevice,
            configs = emptyList(),
            isBrainConfigured = { false },
            isRemoteAgent = { true },
        )
        // 没有 deviceId 就没有目标设备：不能走远程路，也不能冒充本地 CLI。
        assertTrue(route is AgentChatRoute.NotRoutable)
    }

    @Test
    fun `local cli agent is unaffected by remote routing`() {
        val localCli = AgentRecord(
            agentId = "codex",
            displayName = "Codex",
            kind = AgentKind.DEVICE,
            access = AgentAccess.CLI,
            brainKind = BrainKind.AGENT_RUNTIME,
            adapterId = "codex",
            binary = "codex",
        )
        val route = AgentChatRouter.resolve(
            localCli,
            configs = listOf(
                BrainConfig(
                    brainId = BrainBootstrap.CLI_BRAIN_ID,
                    kind = BrainKind.SUBSCRIPTION,
                    adapterId = "cli-membership",
                    label = "CLI 会员额度",
                    baseUrl = null,
                    model = "codex",
                    authRef = null,
                    loginState = AgentLoginState.LOGGED_IN,
                    cliBinary = "codex",
                ),
            ),
            isBrainConfigured = { it == BrainBootstrap.CLI_BRAIN_ID },
            isRemoteAgent = { false },
        )
        assertTrue(route is AgentChatRoute.UseBrain)
        assertEquals(BrainBootstrap.CLI_BRAIN_ID, (route as AgentChatRoute.UseBrain).brainId)
    }

    @Test
    fun `device label distinguishes remote from local cli`() {
        // deviceId 在 = 远程设备（配对与否由路由/横幅后缀表达，
        // deviceLabel 本身只说位置，不编在线态）。
        assertEquals("远程设备", AgentChatRouter.deviceLabel(remoteAgent()))
        assertEquals("远程设备", AgentChatRouter.deviceLabel(unconnectedRemoteAgent()))
        val localCli = remoteAgent().copy(deviceId = null, agentId = "codex", binary = "codex")
        assertEquals("本机（CLI）", AgentChatRouter.deviceLabel(localCli))
    }

    @Test
    fun `describeRoute for remote agent names the hand channel`() {
        val route = AgentChatRoute.UseRemoteAgent(remoteAgent())
        val text = AgentChatRouter.describeRoute(remoteAgent(), route)
        assertTrue(text.contains("Example Agent"))
        assertTrue(text.contains("远程"))
    }
}
