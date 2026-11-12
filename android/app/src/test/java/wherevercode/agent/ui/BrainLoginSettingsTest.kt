package wherevercode.agent.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.R
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentLoginState

/**
 * V5 C3-C：设置页大脑多模式登陆 UI（纯逻辑 + 布局接线）。
 *
 * 全程 fake：只断言 [BrainLoginRows] 的行文案/切换判定/表单校验，
 * 与 screen_settings.xml 的新 id 是否存在；零真实调用（不登录、
 * 不调 /models、不跑 CLI）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class BrainLoginSettingsTest {

    private fun config(
        brainId: String,
        kind: BrainKind = BrainKind.MODEL_API,
        adapterId: String = brainId,
        label: String = brainId,
        baseUrl: String? = null,
        model: String = "",
        authRef: String? = null,
        loginState: AgentLoginState? = null,
        lastTestSummary: String? = null,
        cliBinary: String? = null,
    ) = BrainConfig(
        brainId = brainId,
        kind = kind,
        adapterId = adapterId,
        label = label,
        baseUrl = baseUrl,
        model = model,
        authRef = authRef,
        loginState = loginState,
        lastTestSummary = lastTestSummary,
        cliBinary = cliBinary,
    )

    private fun seededConfigs() = listOf(
        config(
            "siwc-chatgpt", BrainKind.SUBSCRIPTION, "siwc-chatgpt",
            "ChatGPT (Sign in with ChatGPT)", model = "", authRef = "siwc:tokens",
        ),
        config(
            "cloudflare-workers-ai", BrainKind.MODEL_API, "cloudflare-workers-ai",
            "Cloudflare Workers AI", baseUrl = "https://api.cloudflare.com",
            model = "@cf/meta/llama-3.1-8b-instruct-fp8-fast", authRef = "cloudflare:token",
        ),
        config(
            "deepseek", BrainKind.MODEL_API, "deepseek",
            "DeepSeek API", baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash", authRef = "deepseek:api-key",
        ),
        config(
            "cli-membership", BrainKind.SUBSCRIPTION, "cli-membership",
            "CLI 会员额度", model = "codex", cliBinary = "codex",
        ),
    )

    private fun themed(): ContextThemeWrapper = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        R.style.Theme_WhereverCode,
    )

    private fun assertIdAbsent(name: String) {
        assertEquals(
            0,
            RuntimeEnvironment.getApplication().resources.getIdentifier(
                name,
                "id",
                RuntimeEnvironment.getApplication().packageName,
            ),
        )
    }

    @Test
    fun `brain list renders four seeded brains in fixed order with kind labels`() {
        val rows = BrainLoginRows.buildRows(
            configs = seededConfigs(),
            currentBrainId = "deepseek",
            isConfigured = { it == "deepseek" || it == "cloudflare-workers-ai" },
            siwcSignedIn = true,
        )
        assertEquals(
            listOf("siwc-chatgpt", "cloudflare-workers-ai", "deepseek", "cli-membership"),
            rows.map { it.brainId },
        )
        assertEquals("订阅授权", rows[0].typeLabel)
        assertEquals("模型 API", rows[1].typeLabel)
        assertEquals("模型 API", rows[2].typeLabel)
        assertEquals("订阅授权", rows[3].typeLabel)
    }

    @Test
    fun `switch is allowed only for a configured brain`() {
        val rows = BrainLoginRows.buildRows(
            configs = seededConfigs(),
            currentBrainId = null,
            isConfigured = { it == "deepseek" },
            siwcSignedIn = false,
        )
        val deepseek = rows.first { it.brainId == "deepseek" }
        assertTrue(deepseek.canSwitch)
        assertEquals(
            BrainLoginRows.SwitchDecision.Allowed,
            BrainLoginRows.switchDecision(deepseek),
        )
        val cloudflare = rows.first { it.brainId == "cloudflare-workers-ai" }
        assertFalse(cloudflare.canSwitch)
        val blocked = BrainLoginRows.switchDecision(cloudflare)
        assertTrue(blocked is BrainLoginRows.SwitchDecision.Blocked)
        assertTrue((blocked as BrainLoginRows.SwitchDecision.Blocked).reason.contains("配置"))
        // 未登录的 SIWC 也不可切换，给的是“先登录”而不是静默失败。
        val siwc = rows.first { it.brainId == "siwc-chatgpt" }
        assertFalse(siwc.canSwitch)
        assertTrue(siwc.switchHint.contains("登录"))
    }

    @Test
    fun `switching to the current brain is marked current`() {
        val rows = BrainLoginRows.buildRows(
            configs = seededConfigs(),
            currentBrainId = "deepseek",
            isConfigured = { true },
            siwcSignedIn = true,
        )
        assertTrue(rows.first { it.brainId == "deepseek" }.isCurrent)
        assertFalse(rows.first { it.brainId == "siwc-chatgpt" }.isCurrent)
    }

    @Test
    fun `siwc status keeps authentication and model as separate facts`() {
        assertEquals(
            "认证通过",
            BrainLoginRows.siwcStatusText(signedIn = true),
        )
        assertEquals("当前模型：未选择", BrainLoginRows.siwcCurrentModelText(""))
        assertEquals("尚未选择模型，请刷新目录后选择。", BrainLoginRows.siwcModelPickerText(""))
        assertEquals(
            "可用模型：gpt-6.1-sol、gpt-6.1",
            BrainLoginRows.siwcModelsText(listOf("gpt-6.1-sol", "gpt-6.1")),
        )
        assertEquals("未取到模型目录：请重试刷新。", BrainLoginRows.siwcModelsText(emptyList()))
    }

    @Test
    fun `siwc model row indicates current selection without repeating model`() {
        val rows = BrainLoginRows.buildRows(
            configs = seededConfigs(),
            currentBrainId = "siwc-chatgpt",
            isConfigured = { true },
            siwcSignedIn = true,
        )
        val row = rows.first { it.brainId == "siwc-chatgpt" }
        assertEquals("当前正在使用", row.switchHint)
        assertEquals(null, row.modelText)
    }

    @Test
    fun `siwc status shows the chosen model and signed-out state`() {
        assertEquals(
            "认证通过",
            BrainLoginRows.siwcStatusText(signedIn = true),
        )
        assertEquals("当前模型：gpt-6.1-sol", BrainLoginRows.siwcCurrentModelText("gpt-6.1-sol"))
        assertEquals("可以在这里更换当前模型。", BrainLoginRows.siwcModelPickerText("gpt-6.1-sol"))
        assertEquals("未连接", BrainLoginRows.siwcStatusText(signedIn = false))
    }

    @Test
    fun `siwc model write-back only changes the model field`() {
        val before = seededConfigs().first { it.brainId == "siwc-chatgpt" }
        val after = BrainLoginRows.withSiwcModel(before, "gpt-6.1-sol")
        assertEquals("gpt-6.1-sol", after.model)
        assertEquals(before.brainId, after.brainId)
        assertEquals(before.kind, after.kind)
        assertEquals(before.adapterId, after.adapterId)
        assertEquals(before.label, after.label)
        assertEquals(before.authRef, after.authRef)
        assertEquals(before.baseUrl, after.baseUrl)
    }

    @Test
    fun `generic form rejects a blank baseUrl and a plain http baseUrl`() {
        val blankUrl = BrainLoginRows.validateGenericForm(
            label = "自建模型", baseUrl = "", model = "llama-3",
            apiKey = "key", hasStoredKey = false,
        )
        assertTrue(blankUrl.any { it.contains("baseUrl") })
        val httpUrl = BrainLoginRows.validateGenericForm(
            label = "自建模型", baseUrl = "http://example.com", model = "llama-3",
            apiKey = "key", hasStoredKey = false,
        )
        assertTrue(httpUrl.any { it.contains("https") })
        val blankModel = BrainLoginRows.validateGenericForm(
            label = "自建模型", baseUrl = "https://example.com", model = "",
            apiKey = "key", hasStoredKey = false,
        )
        assertTrue(blankModel.any { it.contains("模型不能为空") })
    }

    @Test
    fun `generic form accepts a valid https form and reuses a stored key`() {
        assertEquals(
            emptyList<String>(),
            BrainLoginRows.validateGenericForm(
                label = "自建模型", baseUrl = "https://example.com", model = "llama-3",
                apiKey = "key", hasStoredKey = false,
            ),
        )
        // 已存过 Key 时，Key 框留空也能保存（照抄 saveDeepSeek 的逻辑）。
        assertEquals(
            emptyList<String>(),
            BrainLoginRows.validateGenericForm(
                label = "自建模型", baseUrl = "https://example.com", model = "llama-3",
                apiKey = "", hasStoredKey = true,
            ),
        )
        // 无已存 Key 且 Key 框空白：必须拦住。
        val noKey = BrainLoginRows.validateGenericForm(
            label = "自建模型", baseUrl = "https://example.com", model = "llama-3",
            apiKey = "", hasStoredKey = false,
        )
        assertTrue(noKey.any { it.contains("API Key") })
        // 本地模型允许 http://127.0.0.1（校验器同款例外）。
        assertEquals(
            emptyList<String>(),
            BrainLoginRows.validateGenericForm(
                label = "本地模型", baseUrl = "http://127.0.0.1:8080", model = "local",
                apiKey = "key", hasStoredKey = false,
            ),
        )
    }

    @Test
    fun `generic brain id is a slug of the label`() {
        assertEquals("model-api-custom", BrainLoginRows.genericBrainId("  "))
        assertEquals("model-api-my-brain", BrainLoginRows.genericBrainId("My Brain!"))
        assertEquals(
            "model-api:model-api-my-brain",
            BrainLoginRows.genericAuthRef("model-api-my-brain"),
        )
    }

    @Test
    fun `generic brain can be switched when configured, not when unconfigured`() {
        // 通用大脑放开切换（maintainer 2026-11-11）：已配置即可切，与其它脑
        // 同口径；未配置给“先配置”而不是旧的“仅支持 Agent 对话”。
        val generic = config(
            "model-api-zhipu", BrainKind.MODEL_API, BrainLoginRows.GENERIC_ADAPTER_ID,
            "zhipu", baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-5.3", authRef = "model-api:model-api-zhipu",
        )
        val configured = BrainLoginRows.buildRows(
            configs = listOf(generic),
            currentBrainId = null,
            isConfigured = { true },
            siwcSignedIn = false,
        )
        assertEquals(1, configured.size)
        assertEquals("已配置", configured[0].statusText)
        assertTrue(configured[0].canSwitch)
        assertEquals("设为当前大脑", configured[0].switchHint)
        assertEquals(
            BrainLoginRows.SwitchDecision.Allowed,
            BrainLoginRows.switchDecision(configured[0]),
        )
        val unconfigured = BrainLoginRows.buildRows(
            configs = listOf(generic),
            currentBrainId = null,
            isConfigured = { false },
            siwcSignedIn = false,
        )
        assertEquals("未配置", unconfigured[0].statusText)
        assertFalse(unconfigured[0].canSwitch)
        assertTrue(unconfigured[0].switchHint.contains("配置"))
        assertFalse(unconfigured[0].switchHint.contains("Agent 对话"))
    }

    @Test
    fun `generic brain manage actions are edit and delete, first-class api brains too`() {
        // 大脑登陆补全：一线 MODEL_API 大脑也有编辑/删除。删后写墓碑，
        // 启动播种跳过已删大脑，不再复活，所以给删除入口是诚实的。
        val generic = config(
            "model-api-zhipu", BrainKind.MODEL_API, BrainLoginRows.GENERIC_ADAPTER_ID,
            "zhipu", baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-5.3", authRef = "model-api:model-api-zhipu",
        )
        assertTrue(BrainLoginRows.isGenericBrain(generic))
        assertEquals(listOf("编辑…", "删除…"), BrainLoginRows.manageActions(generic))
        val detail = BrainLoginRows.brainManageDetail(generic)
        assertTrue(detail.contains("glm-5.3"))
        assertTrue(detail.contains("open.bigmodel.cn"))
        val deepseek = config(
            "deepseek", BrainKind.MODEL_API, "deepseek",
            "DeepSeek API", baseUrl = "https://api.deepseek.com",
            model = "deepseek-flash", authRef = "deepseek:api-key",
        )
        assertFalse(BrainLoginRows.isGenericBrain(deepseek))
        assertEquals(listOf("编辑…", "删除…"), BrainLoginRows.manageActions(deepseek))
    }

    @Test
    fun `delete warning states key removal, current-brain clearing, and affected agents`() {
        val generic = config(
            "model-api-zhipu", BrainKind.MODEL_API, BrainLoginRows.GENERIC_ADAPTER_ID,
            "zhipu", baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-5.3", authRef = "model-api:model-api-zhipu",
        )
        val warning = BrainLoginRows.deleteWarning(
            generic, isCurrent = true, agentNames = listOf("zhipu"),
        )
        assertTrue(warning.contains("Key"))
        assertTrue(warning.contains("当前大脑清空"))
        assertTrue(warning.contains("不会自动切到别的脑"))
        assertTrue(warning.contains("zhipu"))
        val plain = BrainLoginRows.deleteWarning(
            generic, isCurrent = false, agentNames = emptyList(),
        )
        assertTrue(plain.contains("Key"))
        assertFalse(plain.contains("当前大脑"))
        assertFalse(plain.contains("Agent"))
    }

    @Test
    fun `cli row shows cliBinary as its identity, not the model field`() {
        // 种子把二进制名填进 model（满足校验），但行身份必须是 cliBinary：
        // 故意让两者不同，行里只能出现 cliBinary。
        val cli = config(
            "cli-membership", BrainKind.SUBSCRIPTION, "cli-membership",
            "CLI 会员额度", model = "codex",
            loginState = AgentLoginState.LOGGED_IN, cliBinary = "claude",
        )
        val rows = BrainLoginRows.buildRows(
            configs = listOf(cli),
            currentBrainId = null,
            isConfigured = { true },
            siwcSignedIn = false,
        )
        assertEquals(1, rows.size)
        assertNull(rows[0].modelText)
        assertTrue(rows[0].statusText.contains("claude"))
        assertFalse(rows[0].statusText.contains("codex"))
    }

    @Test
    fun `cli quota-exhausted login shows quota text and cannot switch`() {
        val cli = config(
            "cli-membership", BrainKind.SUBSCRIPTION, "cli-membership",
            "CLI 会员额度", model = "codex",
            loginState = AgentLoginState.LOGGED_IN,
            lastTestSummary = "已登录，但会员额度不足/受限，本次验证未完成推理：quota",
            cliBinary = "codex",
        )
        assertTrue(BrainLoginRows.isQuotaExhausted(cli))
        val rows = BrainLoginRows.buildRows(
            configs = listOf(cli),
            currentBrainId = null,
            // isBrainConfigured 对 LOGGED_IN 仍为真：只看状态位会误判可用。
            isConfigured = { true },
            siwcSignedIn = false,
        )
        assertEquals("已登录，但额度不足（codex）", rows[0].statusText)
        assertFalse(rows[0].canSwitch)
        assertTrue(rows[0].switchHint.contains("额度不足"))
        // 未验证的 CLI 同样不可切换，行写“未知”而不是“已连接”。
        val unknown = config(
            "cli-membership", BrainKind.SUBSCRIPTION, "cli-membership",
            "CLI 会员额度", model = "codex",
            loginState = AgentLoginState.UNKNOWN, cliBinary = "codex",
        )
        assertEquals(
            "登录状态未知（codex，需点“验证一次”确认）",
            BrainLoginRows.cliStatusText(unknown),
        )
    }

    @Test
    fun `cli row is skipped when no cli binary is configured`() {
        // 设置清理 2：沙箱里没有 CLI 是正常态——大脑列表里不出现 CLI 行，
        // 不占一行写“未发现”。
        val unconfigured = config(
            "cli-membership", BrainKind.SUBSCRIPTION, "cli-membership",
            "CLI 会员额度", model = "",
            loginState = AgentLoginState.UNKNOWN, cliBinary = null,
        )
        val rows = BrainLoginRows.buildRows(
            configs = listOf(unconfigured),
            currentBrainId = null,
            isConfigured = { false },
            siwcSignedIn = false,
        )
        assertTrue(rows.isEmpty())
        // 配了二进制后行出现，身份是 cliBinary。
        val configured = unconfigured.copy(cliBinary = "codex", model = "codex")
        val rows2 = BrainLoginRows.buildRows(
            configs = listOf(configured),
            currentBrainId = null,
            isConfigured = { true },
            siwcSignedIn = false,
        )
        assertEquals(1, rows2.size)
        assertTrue(rows2[0].statusText.contains("codex"))
        assertFalse(rows2[0].statusText.contains("沙箱"))
    }

    @Test
    fun `cli verify copy states the membership cost without implementation details`() {
        // 设置清理 2：按钮仍写明会消耗一次会员调用；说明只写动作，
        // 超时（120/600 秒）与缓冲细节（“ok”探针、非逐行实时）与沙箱
        // 文案一律不出现在用户可见处。
        assertTrue(BrainLoginRows.CLI_VERIFY_BUTTON_TEXT.contains("会消耗一次会员调用"))
        assertFalse(BrainLoginRows.CLI_VERIFY_NOTE.contains("120 秒"))
        assertFalse(BrainLoginRows.CLI_VERIFY_NOTE.contains("600 秒"))
        assertFalse(BrainLoginRows.CLI_VERIFY_NOTE.contains("“ok”"))
        assertFalse(BrainLoginRows.CLI_VERIFY_NOTE.contains("非逐行实时"))
        assertFalse(BrainLoginRows.CLI_VERIFY_NOTE.contains("实时流式"))
        assertFalse(BrainLoginRows.CLI_VERIFY_NOTE.contains("沙箱"))
    }

    @Test
    fun `settings layout has the new brain login rows and keeps the old ids`() {
        val root = LayoutInflater.from(themed()).inflate(R.layout.screen_settings, null)
        // 新行：统一大脑列表、SIWC 模型行、通用 MODEL_API 表单、CLI 行、换号/取消、
        // 高级配置折叠（设置清理 2）；大脑登陆补全：Grok / Muse Spark / Claude 三节。
        val newIds = listOf(
            R.id.brainListContainer,
            R.id.buttonBrainAdvanced,
            R.id.brainAdvancedContainer,
            R.id.siwcModelStatus,
            R.id.buttonRefreshSiwcModels,
            R.id.buttonPickSiwcModel,
            R.id.buttonSwitchChatGptAccount,
            R.id.buttonCancelSignIn,
            R.id.genericBrainLabel,
            R.id.genericBaseUrl,
            R.id.genericModel,
            R.id.genericApiKey,
            R.id.genericModels,
            R.id.buttonTestGenericModels,
            R.id.buttonSaveGenericBrain,
            R.id.grokStatus,
            R.id.grokBaseUrl,
            R.id.grokModel,
            R.id.grokApiKey,
            R.id.grokModels,
            R.id.buttonRefreshGrokModels,
            R.id.buttonSaveGrok,
            R.id.buttonClearGrok,
            R.id.museStatus,
            R.id.museBaseUrl,
            R.id.museModel,
            R.id.museApiKey,
            R.id.museModels,
            R.id.buttonRefreshMuseModels,
            R.id.buttonSaveMuse,
            R.id.buttonClearMuse,
            R.id.claudeStatus,
            R.id.claudeBaseUrl,
            R.id.claudeModel,
            R.id.claudeApiKey,
            R.id.claudeModels,
            R.id.buttonRefreshClaudeModels,
            R.id.buttonSaveClaude,
            R.id.buttonClearClaude,
            R.id.cliBrainStatus,
            R.id.cliBrainBinary,
            R.id.cliBrainNote,
            R.id.buttonVerifyCli,
        )
        newIds.forEach { assertNotNull(root.findViewById<View>(it)) }
        // 旧 id 全部保留（Cloudflare/DeepSeek 两节与大脑显示名只移位置、不改 id）。
        val oldIds = listOf(
            R.id.brainStatus,
            R.id.buttonSignInChatGpt,
            R.id.buttonSignOut,
            R.id.brainNameRow,
            R.id.brainDisplayName,
            R.id.cloudflareStatus,
            R.id.cfAccountId,
            R.id.cfApiToken,
            R.id.cfModel,
            R.id.buttonSaveCloudflare,
            R.id.buttonClearCloudflare,
            R.id.deepseekStatus,
            R.id.dsBaseUrl,
            R.id.dsModel,
            R.id.dsApiKey,
            R.id.deepseekModels,
            R.id.buttonRefreshDeepSeekModels,
            R.id.buttonSaveDeepSeek,
            R.id.buttonClearDeepSeek,
        )
        oldIds.forEach { assertNotNull(root.findViewById<View>(it)) }
        // “验证一次”按钮文案与集中映射一致。
        assertEquals(
            BrainLoginRows.CLI_VERIFY_BUTTON_TEXT,
            root.findViewById<Button>(R.id.buttonVerifyCli).text.toString(),
        )
    }

    @Test
    fun `cli row has no key or baseUrl input`() {
        // CLI 大脑无 key、无 baseUrl：设置页不许给它渲染这两项输入框。
        assertIdAbsent("cliApiKey")
        assertIdAbsent("cliBaseUrl")
        assertIdAbsent("cliModel")
    }
}
