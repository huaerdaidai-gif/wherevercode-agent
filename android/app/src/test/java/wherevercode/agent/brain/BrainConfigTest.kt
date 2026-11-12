package wherevercode.agent.brain

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.agent.AgentLoginState

@RunWith(RobolectricTestRunner::class)
class BrainConfigTest {

    private fun config(
        baseUrl: String? = "https://api.example.com/v1",
        authRef: String? = "key:openai-1",
        extra: Map<String, String> = emptyMap(),
        kind: BrainKind = BrainKind.MODEL_API,
    ) = BrainConfig(
        brainId = "brain-1",
        kind = kind,
        adapterId = "openai-compatible",
        label = "我的模型",
        baseUrl = baseUrl,
        model = "model-1",
        authRef = authRef,
        extra = extra,
    )

    @Test
    fun `https baseUrl from user config is valid`() {
        assertTrue(BrainConfigValidator.validate(config()).isEmpty())
    }

    @Test
    fun `http baseUrl is refused except local model`() {
        val errors = BrainConfigValidator.validate(config(baseUrl = "http://api.example.com/v1"))
        assertTrue(errors.any { it.contains("只允许 https") })
        assertTrue(BrainConfigValidator.validate(config(baseUrl = "http://127.0.0.1:8080/v1")).isEmpty())
        assertTrue(BrainConfigValidator.validate(config(baseUrl = "http://localhost:8080/v1")).isEmpty())
    }

    @Test
    fun `baseUrl from task content or chat is refused`() {
        val errors = BrainConfigValidator.validate(config(), BaseUrlSource.TASK_CONTENT)
        assertTrue(errors.any { it.contains("只能来自用户配置") })
        val chatErrors = BrainConfigValidator.validate(config(), BaseUrlSource.CHAT_MESSAGE)
        assertTrue(chatErrors.any { it.contains("只能来自用户配置") })
    }

    @Test
    fun `authRef must be a reference name, never a secret value`() {
        val errors = BrainConfigValidator.validate(config(authRef = "sk-" + "test-fixture-value"))
        assertTrue(errors.any { it.contains("不能是密钥本身") })
        assertTrue(BrainConfigValidator.validate(config(authRef = "key:openai-1")).isEmpty())
    }

    @Test
    fun `extra must never carry a secret`() {
        val errors = BrainConfigValidator.validate(
            config(extra = mapOf("api_key" to "sk-abcdefghijklmnop123456")),
        )
        assertTrue(errors.any { it.contains("不能放密钥") })
    }

    @Test
    fun `subscription has no baseUrl and no key field`() {
        val errors = BrainConfigValidator.validate(
            config(kind = BrainKind.SUBSCRIPTION, baseUrl = "https://api.example.com", authRef = "key:x"),
        )
        assertTrue(errors.any { it.contains("没有服务地址") })
        assertTrue(errors.any { it.contains("没有密钥输入框") })
    }

    @Test
    fun `fallback list must not contain itself`() {
        val bad = config().copy(fallbackBrainIds = listOf("brain-1"))
        assertTrue(BrainConfigValidator.validate(bad).any { it.contains("不能包含自己") })
    }

    @Test
    fun `exported config contains the authRef name only, never a secret value`() {
        val exported = BrainConfigValidator.exportConfig(
            config(extra = mapOf("api_key" to "sk-abcdefghijklmnop123456", "note" to "hello")),
        )
        assertEquals("key:openai-1", exported["authRef"])
        @Suppress("UNCHECKED_CAST")
        val extra = exported["extra"] as Map<String, String>
        assertFalse(extra.containsKey("api_key"))
        assertEquals("hello", extra["note"])
        assertFalse(exported.toString().contains("sk-abcdefghijklmnop123456"))
    }

    @Test
    fun `new login fields default to null - zero migration`() {
        val cfg = config()
        assertNull(cfg.loginState)
        assertNull(cfg.lastTestedAtEpochSeconds)
        assertNull(cfg.lastTestSummary)
        assertNull(cfg.cliBinary)
    }

    @Test
    fun `new fields round-trip through the SharedPreferences store`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("wherevercode_brain_configs", Context.MODE_PRIVATE)
            .edit().clear().apply()
        val store = SharedPreferencesBrainConfigStore(context)
        val cfg = config().copy(
            loginState = AgentLoginState.LOGGED_IN,
            lastTestedAtEpochSeconds = 1_791_234_567L,
            lastTestSummary = "LOGGED_IN",
            cliBinary = "codebuddy",
        )
        store.saveConfig(cfg)
        val loaded = store.listConfigs().single()
        assertEquals(cfg, loaded)
        assertEquals(AgentLoginState.LOGGED_IN, store.getConfig("brain-1")!!.loginState)
        assertEquals(1_791_234_567L, store.getConfig("brain-1")!!.lastTestedAtEpochSeconds)
        assertEquals("codebuddy", store.getConfig("brain-1")!!.cliBinary)
    }

    @Test
    fun `old stored JSON without the new fields reads back with null defaults`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("wherevercode_brain_configs", Context.MODE_PRIVATE)
        prefs.edit().putString(
            SharedPreferencesBrainConfigStore.KEY_CONFIGS,
            """[{"brainId":"legacy-1","kind":"MODEL_API","adapterId":"openai-compatible","label":"old brain","baseUrl":"https://api.example.com/v1","model":"model-1","authRef":"key:openai-1","extra":{},"fallbackBrainIds":[]}]""",
        ).apply()
        val loaded = SharedPreferencesBrainConfigStore(context).listConfigs().single()
        assertEquals("legacy-1", loaded.brainId)
        assertNull(loaded.loginState)
        assertNull(loaded.lastTestedAtEpochSeconds)
        assertNull(loaded.lastTestSummary)
        assertNull(loaded.cliBinary)
    }

    @Test
    fun `exportConfig includes the new login fields`() {
        val exported = BrainConfigValidator.exportConfig(
            config().copy(
                loginState = AgentLoginState.LOGGED_IN,
                lastTestedAtEpochSeconds = 1_791_234_567L,
                lastTestSummary = "LOGGED_IN",
                cliBinary = "codebuddy",
            ),
        )
        assertEquals("LOGGED_IN", exported["loginState"])
        assertEquals(1_791_234_567L, exported["lastTestedAtEpochSeconds"])
        assertEquals("LOGGED_IN", exported["lastTestSummary"])
        assertEquals("codebuddy", exported["cliBinary"])
    }

    @Test
    fun `subscription brain other than SIWC requires a cliBinary`() {
        val noBinary = BrainConfig(
            brainId = "cli-brain",
            kind = BrainKind.SUBSCRIPTION,
            adapterId = "cli-membership",
            label = "CLI 会员大脑",
            baseUrl = null,
            model = "cli",
            authRef = null,
            cliBinary = null,
        )
        assertTrue(BrainConfigValidator.validate(noBinary).any { it.contains("cliBinary") })
        val withBinary = noBinary.copy(cliBinary = "codebuddy")
        assertTrue(BrainConfigValidator.validate(withBinary).isEmpty())
    }

    @Test
    fun `agent runtime brain requires a cliBinary`() {
        val noBinary = BrainConfig(
            brainId = "runtime-brain",
            kind = BrainKind.AGENT_RUNTIME,
            adapterId = "codebuddy",
            label = "本机 CLI 大脑",
            baseUrl = null,
            model = "cli",
            authRef = null,
            cliBinary = null,
        )
        assertTrue(BrainConfigValidator.validate(noBinary).any { it.contains("cliBinary") })
        assertTrue(BrainConfigValidator.validate(noBinary.copy(cliBinary = "codebuddy")).isEmpty())
    }

    @Test
    fun `siwc subscription brain does not require a cliBinary`() {
        val siwc = BrainConfig(
            brainId = "siwc-chatgpt",
            kind = BrainKind.SUBSCRIPTION,
            adapterId = "siwc-chatgpt",
            label = "ChatGPT",
            baseUrl = null,
            model = "",
            authRef = "siwc:tokens",
            cliBinary = null,
        )
        assertTrue(BrainConfigValidator.validate(siwc).isEmpty())
    }

    @Test
    fun `deleteConfig on the real store removes the brain and clears current when it was current`() {
        // 删除通用大脑坏条目（2026-11-11，maintainer： "智谱"坏条目要真删得掉，
        // 不要只开个框）：SharedPreferences 实现的 deleteConfig 是真删——
        // 配置从列表里拿掉，是当前脑时 current 一并清空。Keystore 的 Key
        // 与引擎注册表注销是 deleteBrain 里另外两步（见
        // BrainAdapterRegistryTest 的 unregister 覆盖与 deleteSecret 的
        // remove().apply() 直删），这里只钉住配置这一腿。
        val store = SharedPreferencesBrainConfigStore(RuntimeEnvironment.getApplication())
        val bad = BrainConfig(
            brainId = "model-api-custom",
            kind = BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "智谱",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm5.3",
            authRef = "model-api:model-api-custom",
        )
        val good = BrainConfig(
            brainId = "model-api-zhipu",
            kind = BrainKind.MODEL_API,
            adapterId = "model-api-generic",
            label = "zhipu",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-5.3",
            authRef = "model-api:model-api-zhipu",
        )
        store.saveConfig(bad)
        store.saveConfig(good)
        store.setCurrentBrainId("model-api-custom")
        store.deleteConfig("model-api-custom")
        assertNull(store.getConfig("model-api-custom"))
        assertEquals(listOf("model-api-zhipu"), store.listConfigs().map { it.brainId })
        assertNull(store.currentBrainId())
        // 删一个不存在的 id 不动别的配置、不抛错。
        store.deleteConfig("model-api-nope")
        assertEquals(listOf("model-api-zhipu"), store.listConfigs().map { it.brainId })
    }

    @Test
    fun `no default brain - a fresh store reports null current brain`() {
        // The store contract: currentBrainId() is null until the user
        // configures one. The SharedPreferences implementation is covered
        // by the same rule; this test pins the null default at the type
        // level via a fresh in-memory stand-in.
        val store = object : BrainConfigStore {
            private var current: String? = null
            private val configs = mutableListOf<BrainConfig>()
            override fun listConfigs() = configs.toList()
            override fun getConfig(brainId: String) = configs.firstOrNull { it.brainId == brainId }
            override fun saveConfig(config: BrainConfig) {
                configs.removeAll { it.brainId == config.brainId }
                configs += config
            }
            override fun deleteConfig(brainId: String) {
                configs.removeAll { it.brainId == brainId }
                if (current == brainId) current = null
            }
            override fun currentBrainId() = current
            override fun setCurrentBrainId(brainId: String?) {
                current = brainId
            }
        }
        assertNull(store.currentBrainId())
        store.saveConfig(config())
        assertNull(store.currentBrainId())
        store.setCurrentBrainId("brain-1")
        assertEquals("brain-1", store.currentBrainId())
    }

    @Test
    fun `device-code subscription brain may hold an authRef and inference baseUrl with blank model`() {
        val qwen = BrainConfig(
            brainId = "qwen",
            kind = BrainKind.SUBSCRIPTION,
            adapterId = "devicecode-qwen",
            label = "Qwen 会员",
            baseUrl = "https://portal.qwen.ai/v1/chat/completions",
            model = "",
            authRef = "devicecode:qwen",
        )
        assertTrue(BrainConfigValidator.validate(qwen).isEmpty())
    }

    @Test
    fun `device-code brain with a non-https inference baseUrl is refused`() {
        val qwen = BrainConfig(
            brainId = "qwen",
            kind = BrainKind.SUBSCRIPTION,
            adapterId = "devicecode-qwen",
            label = "Qwen 会员",
            baseUrl = "http://portal.qwen.ai/v1/chat/completions",
            model = "",
            authRef = "devicecode:qwen",
        )
        assertTrue(
            BrainConfigValidator.validate(qwen).any { it.contains("只允许 https") },
        )
    }

    @Test
    fun `login-only subscription brain needs no model key or cli`() {
        val gemini = BrainConfig(
            brainId = "gemini",
            kind = BrainKind.SUBSCRIPTION,
            adapterId = "oauth-gemini",
            label = "Gemini（仅登录）",
            baseUrl = null,
            model = "",
            authRef = null,
            extra = mapOf("login_only" to "true"),
        )
        assertTrue(BrainConfigValidator.validate(gemini).isEmpty())
    }

    @Test
    fun `plain subscription brain without a cli still needs one`() {
        val errors = BrainConfigValidator.validate(
            config(kind = BrainKind.SUBSCRIPTION, baseUrl = null, authRef = null),
        )
        assertTrue(errors.any { it.contains("需要已登录的 CLI") })
    }
}
