package wherevercode.agent.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord

/**
 * 阶段 C（V5 紧急开发）：Manus 路由——已配置 Key 的 Manus 真走
 * Manus 聊天端口，不再是阶段 A 的 NotRoutable；未配置时仍诚实拒绝，
 * 不建任务、不计费、绝不回落 ChatGPT。
 */
class AgentChatRouterManusTest {

    private fun manusAgent() = AgentRecord(
        agentId = "manus",
        displayName = "Manus",
        kind = AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = BrainKind.MODEL_API,
        adapterId = "manus",
        baseUrl = "https://api.manus.ai",
        model = "standard",
        authRef = "manus:api-key",
    )

    private fun deepseekConfig() = BrainConfig(
        brainId = "deepseek",
        kind = BrainKind.MODEL_API,
        adapterId = "deepseek",
        label = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-chat",
        authRef = "deepseek:api-key",
    )

    private fun configured(vararg ids: String): (String) -> Boolean = { it in ids }

    @Test
    fun `manus agent with a configured key routes to UseManus`() {
        val agent = manusAgent()
        val route = AgentChatRouter.resolve(
            agent,
            listOf(deepseekConfig()),
            configured("deepseek"),
            isManusConfigured = { true },
        )

        val useManus = route as AgentChatRoute.UseManus
        assertEquals("manus", useManus.agent.agentId)
    }

    @Test
    fun `manus agent without a key is refused honestly and builds nothing`() {
        val route = AgentChatRouter.resolve(
            manusAgent(),
            listOf(deepseekConfig()),
            configured("deepseek"),
            isManusConfigured = { false },
        )

        val blocked = route as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("Manus"))
        assertTrue(blocked.message.contains("Key"))
        assertTrue("须写明本次不建任务、不计费", blocked.message.contains("不计费"))
    }

    @Test
    fun `manus agent without a key is refused by default`() {
        val route = AgentChatRouter.resolve(
            manusAgent(),
            listOf(deepseekConfig()),
            configured("deepseek"),
        )
        assertTrue(route is AgentChatRoute.NotRoutable)
    }

    @Test
    fun `old manus record detected by host routes to Manus not deepseek`() {
        // 旧记录：adapterId 仍是 deepseek、model 仍是 "Manus 2.0 Lite"，
        // 但主机是 api.manus.ai——按 ManusTaskAdapter.isManusRecord
        // 识别，绝不能把它当 DeepSeek 大脑调。
        val old = manusAgent().copy(adapterId = "deepseek", model = "Manus 2.0 Lite")
        val route = AgentChatRouter.resolve(
            old,
            listOf(deepseekConfig()),
            configured("deepseek"),
            isManusConfigured = { true },
        )
        assertTrue(route is AgentChatRoute.UseManus)
    }

    @Test
    fun `describeRoute for UseManus names the Manus task channel`() {
        val agent = manusAgent()
        val text = AgentChatRouter.describeRoute(agent, AgentChatRoute.UseManus(agent))
        assertTrue(text.contains("Manus"))
        assertTrue(text.contains("任务"))
    }
}
