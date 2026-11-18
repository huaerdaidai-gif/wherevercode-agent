package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainKind

/**
 * 模型页“已添加模型”列表（2026-11-19 解耦）：模型自己的列表逻辑，
 * 与设置页大脑列表分家——按连接类型分组（模型 API / CLI 会员 /
 * 订阅授权）、状态取真实值、无切换大脑语义。全程 fake，零真实调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class ModelListRowsTest {

    private fun config(
        brainId: String,
        kind: BrainKind = BrainKind.MODEL_API,
        adapterId: String = brainId,
        label: String = brainId,
        baseUrl: String? = null,
        model: String = "",
        authRef: String? = null,
        cliBinary: String? = null,
        extra: Map<String, String> = emptyMap(),
    ) = BrainConfig(
        brainId = brainId,
        kind = kind,
        adapterId = adapterId,
        label = label,
        baseUrl = baseUrl,
        model = model,
        authRef = authRef,
        cliBinary = cliBinary,
        extra = extra,
    )

    private fun rows(
        configs: List<BrainConfig>,
        current: String? = null,
        configured: Set<String> = emptySet(),
        siwcSignedIn: Boolean = false,
    ) = ModelListRows.buildRows(
        configs = configs,
        currentBrainId = current,
        isConfigured = { it in configured },
        siwcSignedIn = siwcSignedIn,
    )

    @Test
    fun `groups are ordered model api then cli then subscription`() {
        val result = rows(
            listOf(
                config("siwc-chatgpt", BrainKind.SUBSCRIPTION, label = "ChatGPT", authRef = "siwc:tokens"),
                config("cli-membership", BrainKind.SUBSCRIPTION, label = "CLI 会员额度", model = "codex", cliBinary = "codex"),
                config("deepseek", BrainKind.MODEL_API, label = "DeepSeek API", model = "deepseek-flash", authRef = "deepseek:api-key"),
            ),
        )
        assertEquals(listOf("deepseek", "cli-membership", "siwc-chatgpt"), result.map { it.brainId })
        assertEquals(listOf("模型 API", "CLI 会员", "订阅授权"), result.map { it.groupTitle })
    }

    @Test
    fun `platform and cloud agents are not models and never listed`() {
        val result = rows(
            listOf(
                config("grok-bot", BrainKind.PLATFORM_AGENT, label = "Grok Bot"),
                config("manus", BrainKind.CLOUD_AGENT, label = "Manus"),
                config("deepseek", BrainKind.MODEL_API, label = "DeepSeek API", authRef = "deepseek:api-key"),
            ),
        )
        assertEquals(listOf("deepseek"), result.map { it.brainId })
        assertNull(ModelListRows.groupTitle(config("grok-bot", BrainKind.PLATFORM_AGENT)))
    }

    @Test
    fun `cli row hidden until a binary is configured`() {
        val withoutBinary = rows(
            listOf(config("cli-membership", BrainKind.SUBSCRIPTION, label = "CLI 会员额度", cliBinary = null)),
        )
        assertTrue(withoutBinary.isEmpty())
        val withBinary = rows(
            listOf(config("cli-membership", BrainKind.SUBSCRIPTION, label = "CLI 会员额度", model = "codex", cliBinary = "codex")),
        )
        assertEquals(1, withBinary.size)
        assertEquals("CLI 会员", withBinary[0].groupTitle)
        // 身份是 cliBinary：详情不重复 model 字段。
        assertNull(withBinary[0].detailText)
        assertTrue(withBinary[0].statusText.contains("codex"))
    }

    @Test
    fun `status text reflects real configuration state`() {
        val result = rows(
            listOf(
                config("deepseek", BrainKind.MODEL_API, label = "DeepSeek API", model = "deepseek-flash", authRef = "deepseek:api-key"),
                config("my-ollama", BrainKind.MODEL_API, adapterId = "model-api-generic", label = "本机模型", model = "llama-3"),
            ),
            configured = setOf("deepseek"),
        )
        val byId = result.associateBy { it.brainId }
        assertEquals("已配置", byId.getValue("deepseek").statusText)
        assertEquals("deepseek-flash", byId.getValue("deepseek").detailText)
        assertEquals("未配置", byId.getValue("my-ollama").statusText)
    }

    @Test
    fun `device code membership reports login state`() {
        val loggedOut = rows(listOf(config("qwen", BrainKind.SUBSCRIPTION, label = "千问", model = "qwen3-coder")))
        assertEquals("未登录", loggedOut[0].statusText)
        assertEquals("订阅授权", loggedOut[0].groupTitle)
        val loggedIn = rows(
            listOf(config("qwen", BrainKind.SUBSCRIPTION, label = "千问", model = "qwen3-coder")),
            configured = setOf("qwen"),
        )
        assertEquals("已登录", loggedIn[0].statusText)
    }

    @Test
    fun `siwc status follows sign in and gemini stays login only`() {
        val result = rows(
            listOf(
                config("siwc-chatgpt", BrainKind.SUBSCRIPTION, label = "ChatGPT", authRef = "siwc:tokens"),
                config("gemini", BrainKind.SUBSCRIPTION, label = "Gemini", authRef = "gemini:oauth"),
            ),
            current = "gemini",
            siwcSignedIn = true,
        )
        val byId = result.associateBy { it.brainId }
        assertEquals(BrainLoginRows.siwcStatusText(true), byId.getValue("siwc-chatgpt").statusText)
        assertEquals(BrainLoginRows.GEMINI_LOGIN_ONLY_NOTE, byId.getValue("gemini").statusText)
        // Gemini 仅登录、不能设为当前大脑：永不标当前。
        assertFalse(byId.getValue("gemini").isCurrent)
    }

    @Test
    fun `custom provider rows are flagged and builtin presets are not`() {
        val custom = config(
            "my-cloud",
            BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "某某云 · m1",
            model = "m1",
            authRef = "generic:my-cloud",
            extra = mapOf("provider_preset" to "custom-provider"),
        )
        val builtin = config(
            "groq-llama",
            BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "Groq · llama",
            model = "llama",
            authRef = "generic:groq-llama",
            extra = mapOf("provider_preset" to "groq"),
        )
        val noPreset = config(
            "plain",
            BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "手工模型",
            model = "m",
            authRef = "generic:plain",
        )
        assertTrue(ModelListRows.isCustomProvider(custom))
        assertFalse(ModelListRows.isCustomProvider(builtin))
        assertFalse(ModelListRows.isCustomProvider(noPreset))
        val byId = rows(listOf(custom, builtin, noPreset)).associateBy { it.brainId }
        assertTrue(byId.getValue("my-cloud").isCustomProvider)
        assertFalse(byId.getValue("groq-llama").isCustomProvider)
        assertFalse(byId.getValue("plain").isCustomProvider)
    }

    @Test
    fun `manage detail marks custom provider only`() {
        val custom = config(
            "my-cloud",
            BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "某某云 · m1",
            model = "m1",
            baseUrl = "https://api.example.com/v1",
            extra = mapOf("provider_preset" to "custom-provider"),
        )
        val builtin = config(
            "groq-llama",
            BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "Groq · llama",
            model = "llama",
            baseUrl = "https://api.groq.com/openai/v1",
            extra = mapOf("provider_preset" to "groq"),
        )
        assertTrue(ModelListRows.manageDetail(custom).contains("来源：${ModelListRows.CUSTOM_TAG}服务商"))
        assertFalse(ModelListRows.manageDetail(builtin).contains(ModelListRows.CUSTOM_TAG))
        // 底文案与大脑侧详情一致（模型 + 服务地址，只读复用未改）。
        assertEquals(
            BrainLoginRows.brainManageDetail(builtin),
            ModelListRows.manageDetail(builtin),
        )
    }

    @Test
    fun `model delete confirmation never says brain`() {
        val config = config(
            "my-model",
            BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "我的模型",
            model = "m1",
            authRef = "generic:my-model",
        )
        val title = ModelListRows.deleteTitle(config)
        assertTrue(title.startsWith("删除模型"))
        assertFalse(title.contains("大脑"))
        val warning = ModelListRows.deleteWarning(
            config,
            isCurrent = true,
            agentNames = listOf("写手甲"),
        )
        assertTrue(warning.startsWith("删除模型“我的模型”？"))
        assertFalse(warning.contains("大脑"))
        // 后果说清：只删这条模型配置，不动 Agent 与设备。
        assertTrue(warning.contains("不会删除 Agent"))
        assertTrue(warning.contains("不会删除设备"))
        assertTrue(warning.contains("写手甲"))
        // 无 Key 引用、无 Agent 使用时也不出现大脑字样。
        val bare = ModelListRows.deleteWarning(
            config.copy(authRef = null),
            isCurrent = false,
            agentNames = emptyList(),
        )
        assertFalse(bare.contains("大脑"))
        assertTrue(bare.contains("不会删除设备"))
    }

    @Test
    fun `model form errors rename the brain name error only`() {
        val mapped = ModelListRows.formErrors(
            listOf("请填写大脑名称", "请填写 API Key（只存本机 Keystore）"),
        )
        assertEquals("请填写模型名称", mapped[0])
        assertEquals("请填写 API Key（只存本机 Keystore）", mapped[1])
        assertFalse(mapped.any { it.contains("大脑") })
        // 大脑侧原字符串本体未动（设置页仍报大脑名称）。
        assertTrue(
            BrainLoginRows.validateGenericForm("", "https://api.example.com/v1", "m", "k", false)
                .contains("请填写大脑名称"),
        )
    }

    @Test
    fun `current connection is only annotated`() {
        val result = rows(
            listOf(config("deepseek", BrainKind.MODEL_API, label = "DeepSeek API", authRef = "deepseek:api-key")),
            current = "deepseek",
        )
        assertTrue(result[0].isCurrent)
    }
}
