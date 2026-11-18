package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainKind

/**
 * 会员入口在设置页大脑列表里的呈现（2026-11-12，membership add-all）：
 * 管理动作（登录/改模型/退出/删除）、列表顺序、Gemini 行永远不可
 * 切、设备码行提示、删除文案按 authRef 有无区分。
 * 纯逻辑，无 Android 依赖；零真实调用。
 */
class MembershipLoginRowsTest {

    private fun config(
        brainId: String,
        kind: BrainKind = BrainKind.SUBSCRIPTION,
        adapterId: String = "devicecode-$brainId",
        label: String = brainId,
        authRef: String? = "devicecode:$brainId",
        model: String = "",
        baseUrl: String? = null,
        cliBinary: String? = null,
    ) = BrainConfig(
        brainId = brainId,
        kind = kind,
        adapterId = adapterId,
        label = label,
        baseUrl = baseUrl,
        model = model,
        authRef = authRef,
        cliBinary = cliBinary,
    )

    private fun seededConfigs() = listOf(
        config("siwc-chatgpt", adapterId = "siwc-chatgpt", label = "ChatGPT (Sign in with ChatGPT)", authRef = "siwc:tokens"),
        config("cloudflare-workers-ai", kind = BrainKind.MODEL_API, adapterId = "cloudflare-workers-ai", label = "Cloudflare Workers AI", authRef = "cloudflare:token", model = "m", baseUrl = "https://api.cloudflare.com"),
        config("deepseek", kind = BrainKind.MODEL_API, adapterId = "deepseek", label = "DeepSeek API", authRef = "deepseek:api-key", model = "deepseek-flash", baseUrl = "https://api.deepseek.com"),
        config("qwen", label = "Qwen 会员", baseUrl = "https://portal.qwen.ai/v1/chat/completions"),
        config("kimi", label = "Kimi Code 订阅", baseUrl = "https://api.kimi.com/coding/v1"),
        config("grok-membership", label = "Grok 会员（实验性）", baseUrl = "https://api.x.ai/v1"),
        config("gemini", adapterId = "oauth-gemini", label = "Gemini（仅登录）", authRef = null),
        config("cli-membership", adapterId = "cli-membership", label = "CLI 会员额度", authRef = null, model = "codex", cliBinary = "codex"),
    )

    @Test
    fun `device-code brains get login edit-model sign-out delete`() {
        for (brainId in listOf("qwen", "kimi", "grok-membership")) {
            assertEquals(
                "$brainId must offer the full device-code manage set",
                listOf("登录…", "编辑模型…", "退出登录", "删除…"),
                BrainLoginRows.manageActions(config(brainId)),
            )
        }
    }

    @Test
    fun `gemini gets delete only`() {
        assertEquals(
            listOf("删除…"),
            BrainLoginRows.manageActions(config("gemini", adapterId = "oauth-gemini", authRef = null)),
        )
    }

    @Test
    fun `siwc keeps sign-out and delete`() {
        assertEquals(
            listOf("退出登录", "删除…"),
            BrainLoginRows.manageActions(
                config("siwc-chatgpt", adapterId = "siwc-chatgpt", authRef = "siwc:tokens"),
            ),
        )
    }

    @Test
    fun `brain list renders all eight seeded brains in fixed order`() {
        val rows = BrainLoginRows.buildRows(
            configs = seededConfigs(),
            currentBrainId = null,
            isConfigured = { false },
            siwcSignedIn = false,
        )
        assertEquals(
            listOf(
                "siwc-chatgpt", "cloudflare-workers-ai", "deepseek",
                "qwen", "kimi", "grok-membership", "gemini", "cli-membership",
            ),
            rows.map { it.brainId },
        )
    }

    @Test
    fun `gemini row can never switch`() {
        val rows = BrainLoginRows.buildRows(
            configs = seededConfigs(),
            currentBrainId = null,
            isConfigured = { it == "gemini" },
            siwcSignedIn = false,
        )
        val gemini = rows.first { it.brainId == "gemini" }
        assertFalse(gemini.canSwitch)
        assertFalse(gemini.isCurrent)
        assertTrue(gemini.statusText.contains("不能当大脑用推理"))
        assertTrue(gemini.switchHint.contains("不能当大脑用推理"))
    }

    @Test
    fun `device-code row asks for login and model when unconfigured`() {
        val rows = BrainLoginRows.buildRows(
            configs = seededConfigs(),
            currentBrainId = null,
            isConfigured = { false },
            siwcSignedIn = false,
        )
        val qwen = rows.first { it.brainId == "qwen" }
        assertEquals("未登录", qwen.statusText)
        assertFalse(qwen.canSwitch)
        assertTrue(qwen.switchHint.contains("登录…"))
        val kimi = rows.first { it.brainId == "kimi" }
        assertEquals("未登录", kimi.statusText)
    }

    @Test
    fun `device-code row is switchable once configured`() {
        val rows = BrainLoginRows.buildRows(
            configs = seededConfigs(),
            currentBrainId = null,
            isConfigured = { it == "qwen" },
            siwcSignedIn = false,
        )
        val qwen = rows.first { it.brainId == "qwen" }
        assertEquals("已登录", qwen.statusText)
        assertTrue(qwen.canSwitch)
        assertEquals("设为当前大脑", qwen.switchHint)
    }

    @Test
    fun `delete warning differs with and without a stored credential`() {
        val withKey = BrainLoginRows.deleteWarning(config("qwen"), false, emptyList())
        assertTrue(withKey.contains("Keystore 里的 Key 会一起删除"))
        val withoutKey = BrainLoginRows.deleteWarning(
            config("gemini", adapterId = "oauth-gemini", authRef = null),
            false,
            emptyList(),
        )
        assertTrue(withoutKey.contains("配置会彻底删除"))
        assertFalse(withoutKey.contains("Keystore"))
    }

    @Test
    fun `device-code login dialog shows code url and risk notes`() {
        val text = BrainLoginRows.deviceCodeLoginDialogText(
            displayName = "Grok 会员（实验性）",
            verificationUriComplete = "https://auth.x.ai/device?code=ABCD-1234",
            userCode = "ABCD-1234",
            complianceNote = "Grok 会员登录是实验性的：可能 403、可能随时失效。",
            experimentalNote = "实验性：client id 与 scope 只见于第三方源码。",
        )
        assertTrue(text.contains("ABCD-1234"))
        assertTrue(text.contains("https://auth.x.ai/device?code=ABCD-1234"))
        assertTrue(text.contains("可能 403"))
        assertTrue(text.contains("Grok 会员（实验性）"))
    }
}
