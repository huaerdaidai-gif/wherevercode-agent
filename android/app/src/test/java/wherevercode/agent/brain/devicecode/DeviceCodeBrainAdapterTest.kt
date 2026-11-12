package wherevercode.agent.brain.devicecode

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigStore
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatMessage
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.oauth.OAuthHttpResponse
import wherevercode.agent.brain.oauth.OAuthHttpTransport
import wherevercode.agent.brain.siwc.SiwcSecretStore

/**
 * Device-code brain adapter tests (2026-11-12, membership add-all).
 *
 * No-network paths only: the inference happy path reuses
 * GenericOpenAiApiClient (covered by its own tests) and must be
 * walked through on a real device with maintainer's own account before it
 * counts as delivered. Here we pin the honest failure modes —
 * not-logged-in, blank model, login-only Gemini — and the
 * capabilities flags.
 */
class DeviceCodeBrainAdapterTest {

    private class FakeSecrets : SiwcSecretStore {
        val values = mutableMapOf<String, String>()
        override fun put(ref: String, value: String) {
            values[ref] = value
        }

        override fun get(ref: String): String? = values[ref]
        override fun delete(ref: String) {
            values.remove(ref)
        }
    }

    private class FakeTransport : OAuthHttpTransport {
        override fun postForm(url: String, params: Map<String, String>): OAuthHttpResponse =
            OAuthHttpResponse(500, "")

        override fun get(url: String): OAuthHttpResponse = OAuthHttpResponse(404, "")
    }

    private class FakeStore(
        private val configs: MutableMap<String, BrainConfig> = mutableMapOf(),
        private var current: String? = null,
    ) : BrainConfigStore {
        override fun listConfigs(): List<BrainConfig> = configs.values.toList()
        override fun getConfig(brainId: String): BrainConfig? = configs[brainId]
        override fun saveConfig(config: BrainConfig) {
            configs[config.brainId] = config
        }

        override fun deleteConfig(brainId: String) {
            configs.remove(brainId)
        }

        override fun currentBrainId(): String? = current
        override fun setCurrentBrainId(brainId: String?) {
            current = brainId
        }
    }

    private fun qwenConfig(model: String = "qwen-plus") = BrainConfig(
        brainId = "qwen",
        kind = BrainKind.SUBSCRIPTION,
        adapterId = "devicecode-qwen",
        label = "Qwen 会员",
        baseUrl = DeviceCodeConfigs.QWEN.inferenceBaseUrl,
        model = model,
        authRef = "devicecode:qwen",
    )

    private fun adapter(
        store: FakeStore,
        secrets: FakeSecrets,
        nowMillis: Long = 1_000_000L,
    ): DeviceCodeBrainAdapter {
        val tokenStore = DeviceCodeTokenStore(secrets, "devicecode:qwen")
        val manager = DeviceCodeManager(
            config = DeviceCodeConfigs.QWEN,
            tokenStore = tokenStore,
            http = FakeTransport(),
            clockMillis = { nowMillis },
        )
        return DeviceCodeBrainAdapter(
            config = DeviceCodeConfigs.QWEN,
            manager = manager,
            configStore = store,
        )
    }

    private fun request(model: String = "qwen-plus") = ChatRequest(
        messages = listOf(ChatMessage("user", "你好")),
        model = model,
    )

    @Test
    fun `capabilities report streaming without tool calling`() {
        val capabilities = adapter(FakeStore(), FakeSecrets()).capabilities()
        assertTrue(capabilities.streaming)
        assertFalse("membership brains do not do tool calling in this first cut", capabilities.toolCalling)
        assertTrue(capabilities.notes.contains("portal.qwen.ai"))
    }

    @Test
    fun `chatStream without login fails as auth_failed`() = runTest {
        val store = FakeStore()
        store.saveConfig(qwenConfig())
        store.setCurrentBrainId("qwen")
        val events = adapter(store, FakeSecrets()).chatStream(request()).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("auth_failed", error.code)
        assertFalse(error.retryable)
        assertTrue(events.last() is BrainEvent.Done)
    }

    @Test
    fun `chatStream with login but blank model fails as not_configured`() = runTest {
        val store = FakeStore()
        store.saveConfig(qwenConfig(model = ""))
        store.setCurrentBrainId("qwen")
        val secrets = FakeSecrets()
        DeviceCodeTokenStore(secrets, "devicecode:qwen").saveTokens(
            DeviceCodeTokens("live", "r", 3600, "", 1_000_000L),
        )
        val events = adapter(store, secrets).chatStream(request(model = "")).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("not_configured", error.code)
        assertTrue(error.message.contains("编辑模型"))
    }

    @Test
    fun `chatStream without a config fails as not_configured`() = runTest {
        val events = adapter(FakeStore(), FakeSecrets()).chatStream(request()).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("not_configured", error.code)
    }

    @Test
    fun `validateCredentials without login fails`() = runTest {
        val store = FakeStore()
        store.saveConfig(qwenConfig())
        store.setCurrentBrainId("qwen")
        val result = adapter(store, FakeSecrets()).validateCredentials()
        assertTrue(result.isFailure)
    }

    @Test
    fun `Grok adapter capabilities carry the experimental note`() {
        val store = FakeStore()
        val tokenStore = DeviceCodeTokenStore(FakeSecrets(), "devicecode:grok-membership")
        val manager = DeviceCodeManager(
            config = DeviceCodeConfigs.GROK,
            tokenStore = tokenStore,
            http = FakeTransport(),
        )
        val grok = DeviceCodeBrainAdapter(
            config = DeviceCodeConfigs.GROK,
            manager = manager,
            configStore = store,
        )
        assertTrue(grok.capabilities().notes.contains("403"))
        assertEquals("devicecode-grok-membership", grok.adapterId)
        assertEquals(BrainKind.SUBSCRIPTION, grok.kind)
    }

    @Test
    fun `login-only Gemini adapter refuses every call honestly`() = runTest {
        val gemini = wherevercode.agent.brain.devicecode.LoginOnlyBrainAdapter(
            wherevercode.agent.brain.oauth.VendorOAuthConfigs.GEMINI,
        )
        assertFalse(gemini.capabilities().streaming)
        assertTrue(gemini.listModels().getOrThrow().isEmpty())
        assertTrue(gemini.validateCredentials().isFailure)
        val events = gemini.chatStream(request()).toList()
        val error = events.filterIsInstance<BrainEvent.Error>().single()
        assertEquals("not_supported", error.code)
        assertTrue(error.message.contains("不能当大脑用推理"))
        assertFalse(error.retryable)
    }
}
