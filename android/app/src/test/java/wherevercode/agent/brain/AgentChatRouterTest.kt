package wherevercode.agent.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentLoginState
import wherevercode.agent.core.agent.AgentRecord

/**
 * 阶段 A（V5 紧急开发）：聊天按 Agent 路由。
 *
 * 复现 maintainer 2026-11-10 19:30 的 bug：选了云端 Agent 再发“在吗”，
 * 消息根本没发给那个 Agent——它发给了全局大脑 ChatGPT（SIWC），
 * 其订阅额度耗尽，OpenAI 原样返回
 * "The ChatGPT user has reached their Subscription Sharing usage limit"。
 *
 * 这些测试先于修复写成（红）；路由决策是纯函数，不依赖 Android。
 * 约定：[AgentChatRoute.NotRoutable] 绝不静默回落到任何大脑，
 * 消息未发出时必须在文案里明示原因。
 */
class AgentChatRouterTest {

    private fun deepseekConfig() = BrainConfig(
        brainId = "deepseek",
        kind = BrainKind.MODEL_API,
        adapterId = "deepseek",
        label = "DeepSeek API",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-flash",
        authRef = "deepseek:api-key",
    )

    private fun siwcConfig() = BrainConfig(
        brainId = "siwc-chatgpt",
        kind = BrainKind.SUBSCRIPTION,
        adapterId = "siwc-chatgpt",
        label = "ChatGPT (Sign in with ChatGPT)",
        baseUrl = null,
        model = "gpt-6.1-sol",
        authRef = "siwc:tokens",
    )

    private fun cliConfig(binary: String?, loginState: AgentLoginState? = AgentLoginState.LOGGED_IN) =
        BrainConfig(
            brainId = "cli-membership",
            kind = BrainKind.SUBSCRIPTION,
            adapterId = "cli-membership",
            label = "CLI 会员额度",
            baseUrl = null,
            model = binary ?: "",
            authRef = null,
            loginState = loginState,
            cliBinary = binary,
        )

    private fun genericConfig(
        label: String,
        baseUrl: String = "https://api.x.ai/v1",
        model: String = "grok-4",
    ) = BrainConfig(
        brainId = "model-api-" + label.lowercase(),
        kind = BrainKind.MODEL_API,
        adapterId = "model-api-generic",
        label = label,
        baseUrl = baseUrl,
        model = model,
        authRef = "model-api:model-api-" + label.lowercase(),
    )

    private fun deepseekAgent() = AgentRecord(
        agentId = "deepseek",
        displayName = "DeepSeek",
        kind = AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = BrainKind.MODEL_API,
        adapterId = "deepseek",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-flash",
        authRef = "deepseek:api-key",
    )

    private fun configured(vararg ids: String): (String) -> Boolean = { it in ids }

    @Test
    fun `no agent selected keeps the existing global brain path`() {
        val route = AgentChatRouter.resolve(null, listOf(deepseekConfig()), configured("deepseek"))

        assertEquals(AgentChatRoute.UseGlobalBrain, route)
    }

    @Test
    fun `deepseek agent routes to the deepseek brain not the global brain`() {
        val route = AgentChatRouter.resolve(
            deepseekAgent(),
            listOf(deepseekConfig(), siwcConfig()),
            configured("deepseek", "siwc-chatgpt"),
        )

        assertEquals(AgentChatRoute.UseBrain("deepseek", "DeepSeek API"), route)
    }

    @Test
    fun `deepseek agent without a configured deepseek brain errors honestly without fallback`() {
        val route = AgentChatRouter.resolve(
            deepseekAgent(),
            listOf(deepseekConfig(), siwcConfig()),
            configured("siwc-chatgpt"),
        )

        val blocked = route as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("DeepSeek"))
        assertTrue(blocked.message.contains("未配置"))
    }

    @Test
    fun `deepseek agent with a different model than the configured brain is refused not misrouted`() {
        val route = AgentChatRouter.resolve(
            deepseekAgent().copy(model = "deepseek-reasoner"),
            listOf(deepseekConfig()),
            configured("deepseek"),
        )

        val blocked = route as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("模型"))
        assertTrue(blocked.message.contains("不一致"))
    }

    @Test
    fun `deepseek agent with a different baseUrl than the configured brain is refused`() {
        val route = AgentChatRouter.resolve(
            deepseekAgent().copy(baseUrl = "https://api.deepseek.example"),
            listOf(deepseekConfig()),
            configured("deepseek"),
        )

        val blocked = route as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("服务地址"))
        assertTrue(blocked.message.contains("不一致"))
    }

    @Test
    fun `manus agent never routes to a brain and never builds a task`() {
        val manus = AgentRecord(
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
        val route = AgentChatRouter.resolve(
            manus,
            listOf(deepseekConfig(), siwcConfig()),
            configured("deepseek", "siwc-chatgpt"),
        )

        val blocked = route as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("Manus"))
        assertTrue(blocked.message.contains("不计费"))
    }

    @Test
    fun `remote device agent reports not connected instead of pretending to send`() {
        val remote = AgentRecord(
            agentId = "mi12-agent",
            displayName = "小米12 Agent",
            kind = AgentKind.DEVICE,
            access = AgentAccess.API,
            brainKind = BrainKind.MODEL_API,
            adapterId = "deepseek",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
        )
        val route = AgentChatRouter.resolve(
            remote,
            listOf(deepseekConfig()),
            configured("deepseek"),
        )

        val blocked = route as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("未接入"))
    }

    @Test
    fun `codex cli agent routes to the cli membership brain when it is verified for codex`() {
        val codex = AgentRecord(
            agentId = "codex",
            displayName = "Codex",
            kind = AgentKind.DEVICE,
            access = AgentAccess.CLI,
            brainKind = BrainKind.AGENT_RUNTIME,
            adapterId = "codex",
            binary = "codex",
        )
        val route = AgentChatRouter.resolve(
            codex,
            listOf(cliConfig("codex")),
            configured("cli-membership"),
        )

        assertEquals(AgentChatRoute.UseBrain("cli-membership", "CLI 会员额度"), route)
    }

    @Test
    fun `codex cli agent is refused when the cli brain is verified for another binary`() {
        val codex = AgentRecord(
            agentId = "codex",
            displayName = "Codex",
            kind = AgentKind.DEVICE,
            access = AgentAccess.CLI,
            brainKind = BrainKind.AGENT_RUNTIME,
            adapterId = "codex",
            binary = "codex",
        )
        val route = AgentChatRouter.resolve(
            codex,
            listOf(cliConfig("codebuddy")),
            configured("cli-membership"),
        )

        val blocked = route as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("codebuddy"))
        assertTrue(blocked.message.contains("codex"))
    }

    @Test
    fun `cli agent with an unverified invocation is refused`() {
        val claude = AgentRecord(
            agentId = "claude",
            displayName = "Claude Code",
            kind = AgentKind.DEVICE,
            access = AgentAccess.CLI,
            brainKind = BrainKind.AGENT_RUNTIME,
            adapterId = "claude",
            binary = "claude",
        )
        val route = AgentChatRouter.resolve(
            claude,
            listOf(cliConfig("codex")),
            configured("cli-membership"),
        )

        val blocked = route as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("暂无已验证"))
    }

    @Test
    fun `grok agent routes to its matching generic brain`() {
        val grok = AgentRecord(
            agentId = "grok",
            displayName = "Grok",
            kind = AgentKind.CLOUD,
            access = AgentAccess.API,
            brainKind = BrainKind.MODEL_API,
            adapterId = "grok",
            baseUrl = "https://api.x.ai/v1",
            model = "grok-4",
            authRef = "model-api:model-api-grok",
        )
        val route = AgentChatRouter.resolve(
            grok,
            listOf(genericConfig("Grok"), deepseekConfig(), siwcConfig()),
            configured("model-api-grok", "deepseek", "siwc-chatgpt"),
        )

        assertEquals(AgentChatRoute.UseBrain("model-api-grok", "Grok"), route)
    }

    @Test
    fun `grok agent without a matching generic brain errors honestly`() {
        val grok = AgentRecord(
            agentId = "grok",
            displayName = "Grok",
            kind = AgentKind.CLOUD,
            access = AgentAccess.API,
            brainKind = BrainKind.MODEL_API,
            adapterId = "grok",
            baseUrl = "https://api.x.ai/v1",
            model = "grok-4",
        )
        val route = AgentChatRouter.resolve(
            grok,
            listOf(deepseekConfig(), siwcConfig()),
            configured("deepseek", "siwc-chatgpt"),
        )

        val blocked = route as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("未接"))
    }

    @Test
    fun `subscription agent routes to siwc only when signed in`() {
        val chatgpt = AgentRecord(
            agentId = "chatgpt",
            displayName = "ChatGPT",
            kind = AgentKind.CLOUD,
            access = AgentAccess.APP,
            brainKind = BrainKind.SUBSCRIPTION,
            adapterId = "subscription",
        )
        assertEquals(
            AgentChatRoute.UseBrain("siwc-chatgpt", "ChatGPT (Sign in with ChatGPT)"),
            AgentChatRouter.resolve(chatgpt, listOf(siwcConfig()), configured("siwc-chatgpt")),
        )
        val blocked = AgentChatRouter.resolve(
            chatgpt,
            listOf(siwcConfig()),
            configured(),
        ) as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("未配置") || blocked.message.contains("未登录"))
    }

    @Test
    fun `resolving two different agents is independent and never crosses over`() {
        val configs = listOf(deepseekConfig(), genericConfig("Grok"), siwcConfig())
        val isConfigured = configured("deepseek", "model-api-grok", "siwc-chatgpt")
        val first = AgentChatRouter.resolve(deepseekAgent(), configs, isConfigured)
        val second = AgentChatRouter.resolve(
            AgentRecord(
                agentId = "grok",
                displayName = "Grok",
                kind = AgentKind.CLOUD,
                access = AgentAccess.API,
                brainKind = BrainKind.MODEL_API,
                adapterId = "grok",
                baseUrl = "https://api.x.ai/v1",
                model = "grok-4",
                authRef = "model-api:model-api-grok",
            ),
            configs,
            isConfigured,
        )
        val third = AgentChatRouter.resolve(deepseekAgent(), configs, isConfigured)

        assertEquals(AgentChatRoute.UseBrain("deepseek", "DeepSeek API"), first)
        assertEquals(AgentChatRoute.UseBrain("model-api-grok", "Grok"), second)
        assertEquals(first, third)
    }

    @Test
    fun `two generic brains with the same label route by endpoint not by name`() {
        // 添加 Agent 接线（2026-11-11）：同名大脑（中文名常同名）只按名字
        // 分不出来，端点（地址+模型）才是服务身份；旧代码先按名字找，
        // 会把消息发给同名的另一个大脑。
        val first = BrainConfig(
            brainId = "model-api-a",
            kind = BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "A",
            baseUrl = "https://api.x.ai/v1",
            model = "grok-4",
            authRef = "model-api:model-api-a",
        )
        val second = BrainConfig(
            brainId = "model-api-a-2",
            kind = BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "A",
            baseUrl = "https://api.y.ai/v1",
            model = "y-1",
            authRef = "model-api:model-api-a-2",
        )
        val agent = AgentRecord(
            agentId = "a2",
            displayName = "A",
            kind = AgentKind.CLOUD,
            access = AgentAccess.API,
            brainKind = BrainKind.MODEL_API,
            adapterId = "y",
            baseUrl = "https://api.y.ai/v1",
            model = "y-1",
            authRef = "model-api:model-api-a-2",
        )
        val route = AgentChatRouter.resolve(
            agent,
            listOf(first, second),
            configured("model-api-a", "model-api-a-2"),
        )

        assertEquals(AgentChatRoute.UseBrain("model-api-a-2", "A"), route)
    }

    @Test
    fun `two brains with the same adapterId route by endpoint not by name`() {
        val first = BrainConfig(
            brainId = "deepseek",
            kind = BrainKind.MODEL_API,
            adapterId = "deepseek",
            label = "DeepSeek API",
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash",
            authRef = "deepseek:api-key",
        )
        val second = BrainConfig(
            brainId = "deepseek-2",
            kind = BrainKind.MODEL_API,
            adapterId = "deepseek",
            label = "DeepSeek API",
            baseUrl = "https://api.deepseek.example",
            model = "deepseek-reasoner",
            authRef = "deepseek:api-key-2",
        )
        val agent = deepseekAgent().copy(
            baseUrl = "https://api.deepseek.example",
            model = "deepseek-reasoner",
        )
        val route = AgentChatRouter.resolve(
            agent,
            listOf(first, second),
            configured("deepseek", "deepseek-2"),
        )

        assertEquals(AgentChatRoute.UseBrain("deepseek-2", "DeepSeek API"), route)
    }

    @Test
    fun `form added agent with a wired generic brain routes to it`() {
        // 添加 Agent 接线后记录的形态：适配器与密钥引用都指到通用大脑；
        // 路由必须找到它，而不是报“未接入”。
        val config = BrainConfig(
            brainId = "model-api-openrouter",
            kind = BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "openai/gpt-4.1",
            authRef = "model-api:model-api-openrouter",
        )
        val agent = AgentRecord(
            agentId = "openrouter",
            displayName = "OpenRouter",
            kind = AgentKind.CLOUD,
            access = AgentAccess.API,
            brainKind = BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "openai/gpt-4.1",
            authRef = "model-api:model-api-openrouter",
        )
        val route = AgentChatRouter.resolve(
            agent,
            listOf(config),
            configured("model-api-openrouter"),
        )

        assertEquals(AgentChatRoute.UseBrain("model-api-openrouter", "OpenRouter"), route)
    }

    @Test
    fun `platform agent routes to the platform channel when the platform credential is set`() {
        val grokBot = AgentRecord(
            agentId = "example-agent",
            displayName = "Example Agent",
            kind = AgentKind.CLOUD,
            access = AgentAccess.WEBHOOK,
            brainKind = BrainKind.PLATFORM_AGENT,
            adapterId = "platform-agent",
            baseUrl = "https://example.invalid",
            platformAgentId = "example-agent",
            platformHost = "example.invalid",
            platformChannelId = "default-channel-test",
            directGithubRead = true,
        )
        val route = AgentChatRouter.resolve(
            grokBot,
            listOf(deepseekConfig(), siwcConfig()),
            configured("deepseek", "siwc-chatgpt"),
            isPlatformAgentConfigured = { true },
        )

        assertEquals(AgentChatRoute.UsePlatformAgent(grokBot), route)
    }

    @Test
    fun `platform agent without a platform credential errors honestly and sends nothing`() {
        val grokBot = AgentRecord(
            agentId = "example-agent",
            displayName = "Example Agent",
            kind = AgentKind.CLOUD,
            access = AgentAccess.WEBHOOK,
            brainKind = BrainKind.PLATFORM_AGENT,
            adapterId = "platform-agent",
            baseUrl = "https://example.invalid",
            platformAgentId = "example-agent",
        )
        val route = AgentChatRouter.resolve(
            grokBot,
            listOf(deepseekConfig(), siwcConfig()),
            configured("deepseek", "siwc-chatgpt"),
            isPlatformAgentConfigured = { false },
        )

        val blocked = route as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("平台 Agent 凭证"))
        assertTrue(blocked.message.contains("未配置"))
    }

    @Test
    fun `platform agent never falls back to a brain even when brains are configured`() {
        val grokBot = AgentRecord(
            agentId = "example-agent",
            displayName = "Example Agent",
            kind = AgentKind.CLOUD,
            access = AgentAccess.WEBHOOK,
            brainKind = BrainKind.PLATFORM_AGENT,
            adapterId = "platform-agent",
            baseUrl = "https://example.invalid",
            platformAgentId = "example-agent",
        )
        val route = AgentChatRouter.resolve(
            grokBot,
            listOf(deepseekConfig(), siwcConfig()),
            configured("deepseek", "siwc-chatgpt"),
            isPlatformAgentConfigured = { false },
        )

        assertTrue(route is AgentChatRoute.NotRoutable)
        assertTrue(route !is AgentChatRoute.UseBrain)
    }

    @Test
    fun `cloud agent never chats in app and never falls back to a brain`() {
        val grokCloud = AgentRecord(
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
        val route = AgentChatRouter.resolve(
            grokCloud,
            listOf(deepseekConfig(), siwcConfig()),
            configured("deepseek", "siwc-chatgpt"),
        )

        val blocked = route as AgentChatRoute.NotRoutable
        assertTrue(blocked.message.contains("云端 Agent"))
        assertTrue(blocked.message.contains("MCP"))
        assertTrue(route !is AgentChatRoute.UseBrain)
    }

    @Test
    fun `device label names the real location never a fake online state`() {
        assertEquals(
            "云端",
            AgentChatRouter.deviceLabel(deepseekAgent()),
        )
        assertEquals(
            "本机（CLI）",
            AgentChatRouter.deviceLabel(
                AgentRecord(
                    agentId = "codex",
                    displayName = "Codex",
                    kind = AgentKind.DEVICE,
                    access = AgentAccess.CLI,
                    brainKind = BrainKind.AGENT_RUNTIME,
                    adapterId = "codex",
                    binary = "codex",
                ),
            ),
        )
        assertEquals(
            "远程设备（未接入）",
            AgentChatRouter.deviceLabel(
                AgentRecord(
                    agentId = "mi12-agent",
                    displayName = "小米12 Agent",
                    kind = AgentKind.DEVICE,
                    access = AgentAccess.API,
                    brainKind = BrainKind.MODEL_API,
                    adapterId = "deepseek",
                    baseUrl = "https://api.deepseek.com",
                    model = "deepseek-flash",
                ),
            ),
        )
    }
}
