package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.SharedPreferencesBrainConfigStore
import wherevercode.agent.brain.siwc.BrainBootstrap

/**
 * 大脑登陆方式补全（2026-11-12，maintainer 定）的单测：
 * - 新增一种登陆方式能用：Claude Messages API 适配器见
 *   ClaudeBrainAdapterTest；这里钉住“每种登陆类型都有可见的管理
 *   入口”（manageActions）与“本机模型可无 Key”（validateGenericForm）。
 * - 删除大脑登陆时配置与凭证一起清干净：墓碑（deletedBrainIds）
 *   让一线大脑删后不再复活；deleteWarning 按实写 Key 的有无。
 * - 各类型大脑都能删：SUBSCRIPTION / MODEL_API / AGENT_RUNTIME
 *   全部给“删除…”动作，不再只开放通用大脑。
 *
 * 全程 fake/内存：零真实调用（不登录、不调 /models、不跑 CLI）。
 * 凭证本身（Keystore 删 Key）由 MainActivity.deleteBrain 执行，
 * 其 Keystore 直删行为已有 BrainConfigTest 的 deleteSecret 覆盖；
 * 这里钉住删除的另一半——配置 + 墓碑 + 重新添加。
 */
@RunWith(RobolectricTestRunner::class)
class BrainLoginMethodsTest {

    private fun config(
        brainId: String,
        kind: BrainKind = BrainKind.MODEL_API,
        adapterId: String = "model-api-generic",
        label: String = brainId,
        authRef: String? = "model-api:$brainId",
    ) = BrainConfig(
        brainId = brainId,
        kind = kind,
        adapterId = adapterId,
        label = label,
        baseUrl = "https://api.example.com/v1",
        model = "model-1",
        authRef = authRef,
    )

    // ---- 1. 各类型大脑都有管理动作（增删改添看得见） ----

    @Test
    fun `generic brain gets edit and delete`() {
        assertEquals(
            listOf("编辑…", "删除…"),
            BrainLoginRows.manageActions(config("model-api-zhipu")),
        )
    }

    @Test
    fun `first-line api brains get edit and delete`() {
        for ((brainId, adapterId, label) in listOf(
            Triple("deepseek", "deepseek", "DeepSeek API"),
            Triple("grok", "model-api-grok", "Grok"),
            Triple("muse-spark", "model-api-muse", "Muse Spark"),
            Triple("claude", "model-api-claude", "Claude API"),
            Triple("cloudflare-workers-ai", "cloudflare-workers-ai", "Cloudflare Workers AI"),
        )) {
            assertEquals(
                "$label should be editable and deletable",
                listOf("编辑…", "删除…"),
                BrainLoginRows.manageActions(config(brainId, adapterId = adapterId, label = label)),
            )
        }
    }

    @Test
    fun `siwc gets sign out and delete`() {
        assertEquals(
            listOf("退出登录", "删除…"),
            BrainLoginRows.manageActions(
                config(
                    "siwc-chatgpt",
                    kind = BrainKind.SUBSCRIPTION,
                    adapterId = "siwc-chatgpt",
                    label = "ChatGPT (Sign in with ChatGPT)",
                    authRef = "siwc:tokens",
                ),
            ),
        )
    }

    @Test
    fun `cli brain gets delete`() {
        assertEquals(
            listOf("删除…"),
            BrainLoginRows.manageActions(
                config(
                    "cli-membership",
                    kind = BrainKind.AGENT_RUNTIME,
                    adapterId = "cli-membership",
                    label = "CLI 会员额度",
                    authRef = null,
                ),
            ),
        )
    }

    @Test
    fun `delete warning mentions the key only when there is one`() {
        val withKey = BrainLoginRows.deleteWarning(
            config("deepseek", adapterId = "deepseek", label = "DeepSeek API", authRef = "deepseek:api-key"),
            isCurrent = false,
            agentNames = emptyList(),
        )
        assertTrue(withKey.contains("Keystore"))
        assertTrue(withKey.contains("一起删除"))
        val withoutKey = BrainLoginRows.deleteWarning(
            config("cli-membership", kind = BrainKind.AGENT_RUNTIME, label = "CLI 会员额度", authRef = null),
            isCurrent = false,
            agentNames = emptyList(),
        )
        assertFalse(withoutKey.contains("Keystore"))
        assertTrue(withoutKey.contains("配置会彻底删除"))
    }

    @Test
    fun `delete warning tells the current brain will be cleared not switched`() {
        val warning = BrainLoginRows.deleteWarning(
            config("deepseek", adapterId = "deepseek", label = "DeepSeek API"),
            isCurrent = true,
            agentNames = listOf("Codex"),
        )
        assertTrue(warning.contains("当前大脑清空"))
        assertTrue(warning.contains("不会自动切到别的脑"))
        assertTrue(warning.contains("Codex"))
    }

    // ---- 2. 本机模型可无 Key（新增登陆方式：免 Key） ----

    @Test
    fun `local model url needs no api key`() {
        for (baseUrl in listOf("http://127.0.0.1:11434", "http://localhost:11434")) {
            val errors = BrainLoginRows.validateGenericForm(
                label = "本地模型",
                baseUrl = baseUrl,
                model = "qwen3:8b",
                apiKey = "",
                hasStoredKey = false,
            )
            assertTrue("local $baseUrl should not require a key: $errors", errors.isEmpty())
        }
    }

    @Test
    fun `remote url still requires an api key`() {
        val errors = BrainLoginRows.validateGenericForm(
            label = "远端模型",
            baseUrl = "https://api.example.com/v1",
            model = "model-1",
            apiKey = "",
            hasStoredKey = false,
        )
        assertTrue(errors.any { it.contains("API Key") })
    }

    @Test
    fun `remote url with a stored key passes`() {
        val errors = BrainLoginRows.validateGenericForm(
            label = "远端模型",
            baseUrl = "https://api.example.com/v1",
            model = "model-1",
            apiKey = "",
            hasStoredKey = true,
        )
        assertTrue(errors.isEmpty())
    }

    // ---- 3. 墓碑：删了不再复活，删了能重新添加 ----

    private fun store() = SharedPreferencesBrainConfigStore(RuntimeEnvironment.getApplication())

    @Test
    fun `tombstone round-trips in the store`() {
        val s = store()
        assertTrue(s.deletedBrainIds().isEmpty())
        s.markBrainDeleted("deepseek")
        assertEquals(setOf("deepseek"), s.deletedBrainIds())
        s.markBrainDeleted("grok")
        assertEquals(setOf("deepseek", "grok"), s.deletedBrainIds())
        s.clearBrainDeleted("deepseek")
        assertEquals(setOf("grok"), s.deletedBrainIds())
    }

    @Test
    fun `seedConfigFor covers all ten first-line brains`() {
        assertEquals(
            setOf(
                "siwc-chatgpt",
                "cloudflare-workers-ai",
                "deepseek",
                "muse-spark",
                "grok",
                "claude",
                "qwen",
                "kimi",
                "grok-membership",
                "gemini",
            ),
            BrainBootstrap.SEEDABLE_BRAIN_IDS,
        )
        val claude = BrainBootstrap.seedConfigFor("claude")
        assertEquals("model-api-claude", claude?.adapterId)
        assertEquals("Claude API", claude?.label)
        assertEquals("claude:api-key", claude?.authRef)
        assertEquals("https://api.anthropic.com", claude?.baseUrl)
        assertEquals("claude-sonnet-4-5", claude?.model)
        assertNull(BrainBootstrap.seedConfigFor("model-api-custom"))
        assertNull(BrainBootstrap.seedConfigFor("unknown-brain"))
    }

    @Test
    fun `seededConfig returns the seed on first run`() {
        val s = store()
        val seed = BrainBootstrap.seededConfig(s, "claude")
        assertEquals("claude", seed?.brainId)
        assertEquals("model-api-claude", seed?.adapterId)
    }

    @Test
    fun `seededConfig returns null for a tombstoned brain`() {
        val s = store()
        s.markBrainDeleted("deepseek")
        assertNull(BrainBootstrap.seededConfig(s, "deepseek"))
        // Other brains are unaffected.
        assertEquals("grok", BrainBootstrap.seededConfig(s, "grok")?.brainId)
    }

    @Test
    fun `seededConfig prefers the stored config over the seed`() {
        val s = store()
        val stored = config("deepseek", adapterId = "deepseek", label = "DeepSeek API")
            .copy(model = "deepseek-chat")
        s.saveConfig(stored)
        val out = BrainBootstrap.seededConfig(s, "deepseek")
        assertEquals("deepseek-chat", out?.model)
    }

    @Test
    fun `restoreSeededBrain refuses non-seedable brains`() {
        val s = store()
        assertFalse(BrainBootstrap.restoreSeededBrain(s, "model-api-custom"))
        assertFalse(BrainBootstrap.restoreSeededBrain(s, "unknown-brain"))
        assertTrue(s.deletedBrainIds().isEmpty())
        assertNull(s.getConfig("model-api-custom"))
    }

    @Test
    fun `restoreSeededBrain clears the tombstone and writes the seed back`() {
        val s = store()
        s.markBrainDeleted("grok")
        assertTrue(BrainBootstrap.restoreSeededBrain(s, "grok"))
        assertTrue(s.deletedBrainIds().isEmpty())
        val restored = s.getConfig("grok")
        assertEquals("model-api-grok", restored?.adapterId)
        assertEquals("Grok", restored?.label)
        // The seed carries no credential: authRef is only a reference name.
        assertEquals("grok:api-key", restored?.authRef)
    }

    @Test
    fun `buildRows orders first-line brains including grok muse and claude`() {
        val rows = BrainLoginRows.buildRows(
            configs = listOf(
                config("claude", adapterId = "model-api-claude", label = "Claude API"),
                config("model-api-zhipu", label = "zhipu"),
                config("grok", adapterId = "model-api-grok", label = "Grok"),
                config("deepseek", adapterId = "deepseek", label = "DeepSeek API"),
                config("muse-spark", adapterId = "model-api-muse", label = "Muse Spark"),
                config("siwc-chatgpt", kind = BrainKind.SUBSCRIPTION, adapterId = "siwc-chatgpt", label = "ChatGPT"),
            ),
            currentBrainId = null,
            isConfigured = { false },
            siwcSignedIn = false,
        )
        assertEquals(
            listOf("siwc-chatgpt", "deepseek", "grok", "muse-spark", "claude", "model-api-zhipu"),
            rows.map { it.brainId },
        )
    }
}
