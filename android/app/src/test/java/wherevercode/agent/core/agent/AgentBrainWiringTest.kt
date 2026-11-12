package wherevercode.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigValidator
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.ui.BrainLoginRows

/**
 * 添加 Agent → 大脑接线（V5 紧急开发，2026-11-11）。
 *
 * 复现 maintainer 2026-11-11 00:56 的报障：K70 上“添加 Agent 不能用”——
 * 表单只存 AgentRecord，从不建 BrainConfig，聊天路由找不到配置，
 * Agent 永远“未接入”。这些测试先于修复写成（红）：接线是纯函数，
 * 不依赖 Android；密钥值、网络调用一律不进测试。
 */
class AgentBrainWiringTest {

    private fun apiAgent(
        name: String = "DeepSeek",
        baseUrl: String? = "https://api.deepseek.com",
        model: String? = "deepseek-chat",
        adapterId: String = "deepseek",
        kind: BrainKind = BrainKind.MODEL_API,
        agentId: String = "deepseek",
    ) = AgentRecord(
        agentId = agentId,
        displayName = name,
        kind = AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = kind,
        adapterId = adapterId,
        baseUrl = baseUrl,
        model = model,
    )

    private fun genericConfig(
        brainId: String,
        baseUrl: String,
        model: String,
        label: String = "DeepSeek",
    ) = BrainConfig(
        brainId = brainId,
        kind = BrainKind.MODEL_API,
        adapterId = "model-api-generic",
        label = label,
        baseUrl = baseUrl,
        model = model,
        authRef = "model-api:$brainId",
    )

    @Test
    fun `genericBrainId matches BrainLoginRows for the same labels`() {
        val labels = listOf(
            "OpenRouter", "DeepSeek 闪电", "深度求索", "Grok", "My--Agent",
            "", "   ", "a".repeat(50), "ChatGPT (SIWC)", "Ollama 本地",
        )
        for (label in labels) {
            assertEquals(
                "brainId must stay identical between the add-agent form and settings for '$label'",
                BrainLoginRows.genericBrainId(label),
                AgentBrainWiring.genericBrainId(label),
            )
        }
    }

    @Test
    fun `model api agent gets a generic brain with matching fields`() {
        val config = AgentBrainWiring.brainConfigFor(apiAgent(), emptyList())

        assertEquals("model-api-deepseek", config!!.brainId)
        assertEquals(BrainKind.MODEL_API, config.kind)
        assertEquals("model-api-generic", config.adapterId)
        assertEquals("DeepSeek", config.label)
        assertEquals("https://api.deepseek.com", config.baseUrl)
        assertEquals("deepseek-chat", config.model)
        assertEquals("model-api:model-api-deepseek", config.authRef)
        assertTrue(config.fallbackBrainIds.isEmpty())
        assertTrue(BrainConfigValidator.validate(config).isEmpty())
    }

    @Test
    fun `wired record points at the generic brain and keeps the rest`() {
        val record = apiAgent()
        val config = AgentBrainWiring.brainConfigFor(record, emptyList())!!
        val wired = AgentBrainWiring.wiredRecord(record, config)

        assertEquals("model-api-generic", wired.adapterId)
        assertEquals("model-api:model-api-deepseek", wired.authRef)
        assertEquals("deepseek", wired.agentId)
        assertEquals("DeepSeek", wired.displayName)
        assertEquals("https://api.deepseek.com", wired.baseUrl)
        assertEquals("deepseek-chat", wired.model)
    }

    @Test
    fun `manus agent gets no generic brain by adapter id or by host`() {
        assertNull(
            AgentBrainWiring.brainConfigFor(
                apiAgent(adapterId = "manus", baseUrl = "https://api.manus.ai", model = "standard"),
                emptyList(),
            ),
        )
        assertNull(
            AgentBrainWiring.brainConfigFor(
                apiAgent(adapterId = "deepseek", baseUrl = "https://api.manus.ai", model = "standard"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `cli agent gets no generic brain`() {
        val cli = AgentRecord(
            agentId = "codex",
            displayName = "Codex",
            kind = AgentKind.DEVICE,
            access = AgentAccess.CLI,
            brainKind = BrainKind.AGENT_RUNTIME,
            adapterId = "codex",
            binary = "codex",
        )
        assertNull(AgentBrainWiring.brainConfigFor(cli, emptyList()))
    }

    @Test
    fun `subscription agent gets no generic brain`() {
        val sub = apiAgent(
            kind = BrainKind.SUBSCRIPTION,
            adapterId = "subscription",
            baseUrl = null,
            model = null,
        )
        assertNull(AgentBrainWiring.brainConfigFor(sub, emptyList()))
    }

    @Test
    fun `model api agent without baseUrl or model gets no generic brain`() {
        assertNull(AgentBrainWiring.brainConfigFor(apiAgent(baseUrl = null), emptyList()))
        assertNull(AgentBrainWiring.brainConfigFor(apiAgent(baseUrl = "  "), emptyList()))
        assertNull(AgentBrainWiring.brainConfigFor(apiAgent(model = null), emptyList()))
        assertNull(AgentBrainWiring.brainConfigFor(apiAgent(model = "  "), emptyList()))
    }

    @Test
    fun `same endpoint reuses the existing brain id instead of suffixing`() {
        val existing = listOf(
            genericConfig("model-api-deepseek", "https://api.deepseek.com", "deepseek-chat"),
        )
        val config = AgentBrainWiring.brainConfigFor(apiAgent(), existing)

        assertEquals("model-api-deepseek", config!!.brainId)
    }

    @Test
    fun `same name with a different endpoint gets a suffixed brain id`() {
        val existing = listOf(
            genericConfig("model-api-deepseek", "https://api.deepseek.com", "deepseek-chat"),
        )
        val other = apiAgent(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4.1",
            adapterId = "openai",
            agentId = "openai",
        )
        val config = AgentBrainWiring.brainConfigFor(other, existing)

        assertEquals("model-api-deepseek-2", config!!.brainId)
        assertEquals("model-api:model-api-deepseek-2", config.authRef)
    }

    @Test
    fun `suffixed endpoint is reused and the next different endpoint suffixes again`() {
        val existing = listOf(
            genericConfig("model-api-deepseek", "https://api.deepseek.com", "deepseek-chat"),
            genericConfig("model-api-deepseek-2", "https://api.openai.com/v1", "gpt-4.1"),
        )
        val sameAsSecond = apiAgent(
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4.1",
            adapterId = "openai",
            agentId = "openai",
        )
        assertEquals(
            "model-api-deepseek-2",
            AgentBrainWiring.brainConfigFor(sameAsSecond, existing)!!.brainId,
        )
        val third = apiAgent(
            baseUrl = "https://api.x.ai/v1",
            model = "grok-4",
            adapterId = "grok",
            agentId = "grok",
        )
        assertEquals(
            "model-api-deepseek-3",
            AgentBrainWiring.brainConfigFor(third, existing)!!.brainId,
        )
    }

    @Test
    fun `chinese name falls back to custom and suffixes on clash`() {
        val first = AgentBrainWiring.brainConfigFor(
            apiAgent(name = "深度求索", agentId = "shendu"),
            emptyList(),
        )
        assertEquals("model-api-custom", first!!.brainId)
        val existing = listOf(
            genericConfig("model-api-custom", "https://api.deepseek.com", "deepseek-chat", "深度求索"),
        )
        val second = AgentBrainWiring.brainConfigFor(
            apiAgent(
                name = "深度求索",
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4.1",
                adapterId = "openai",
                agentId = "openai",
            ),
            existing,
        )
        assertEquals("model-api-custom-2", second!!.brainId)
    }

    @Test
    fun `requiresKey is true only for a remote model api agent`() {
        assertTrue(AgentBrainWiring.requiresKey(apiAgent()))
        assertFalse(
            AgentBrainWiring.requiresKey(
                apiAgent(baseUrl = "http://127.0.0.1:11434/v1", model = "llama3"),
            ),
        )
        assertFalse(
            AgentBrainWiring.requiresKey(
                apiAgent(adapterId = "manus", baseUrl = "https://api.manus.ai", model = "standard"),
            ),
        )
        assertFalse(
            AgentBrainWiring.requiresKey(
                apiAgent(kind = BrainKind.SUBSCRIPTION, adapterId = "subscription", baseUrl = null, model = null),
            ),
        )
    }

    @Test
    fun `isLocalModel follows the local address rule`() {
        assertTrue(
            AgentBrainWiring.isLocalModel(
                apiAgent(baseUrl = "http://127.0.0.1:11434/v1", model = "llama3"),
            ),
        )
        assertTrue(
            AgentBrainWiring.isLocalModel(
                apiAgent(baseUrl = "http://localhost:1234/v1", model = "local"),
            ),
        )
        assertFalse(AgentBrainWiring.isLocalModel(apiAgent()))
        assertFalse(
            AgentBrainWiring.isLocalModel(
                apiAgent(baseUrl = "http://192.168.1.5:11434/v1", model = "llama3"),
            ),
        )
    }
}
