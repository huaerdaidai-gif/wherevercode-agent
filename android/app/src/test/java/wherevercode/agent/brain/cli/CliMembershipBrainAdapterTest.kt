package wherevercode.agent.brain.cli

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.AgentRunResult
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainConfigStore
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.brain.ChatMessage
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ToolDef
import wherevercode.agent.core.agent.AgentLoginState

/**
 * CLI membership brain adapter tests (C3-D). All fake: the scripted
 * runner stands in for a real CLI process, so no test ever spawns a
 * process or spends membership quota. The probe prompt, the three-way
 * writeback and the tool refusal are pinned here.
 */
class CliMembershipBrainAdapterTest {

    private class FakeConfigStore(var config: BrainConfig? = null) : BrainConfigStore {
        val saved = mutableListOf<BrainConfig>()
        override fun listConfigs(): List<BrainConfig> = listOfNotNull(config)
        override fun getConfig(brainId: String): BrainConfig? =
            config?.takeIf { it.brainId == brainId }
        override fun saveConfig(config: BrainConfig) {
            this.config = config
            saved += config
        }
        override fun deleteConfig(brainId: String) {
            if (config?.brainId == brainId) config = null
        }
        override fun currentBrainId(): String? = null
        override fun setCurrentBrainId(brainId: String?) {}
    }

    private class ScriptedRunner(
        var result: AgentRunResult,
        var lines: List<String> = emptyList(),
    ) {
        var calls = 0
            private set
        var lastPrompt: String? = null
            private set
        var lastBinary: String? = null
            private set

        fun toCliRunner(): CliRunner = CliRunner { prompt, binary, _, onRawLine ->
            calls += 1
            lastPrompt = prompt
            lastBinary = binary
            lines.forEach { onRawLine(it) }
            result
        }
    }

    private fun cliConfig(binary: String? = "codebuddy") = BrainConfig(
        brainId = "cli-membership",
        kind = BrainKind.SUBSCRIPTION,
        adapterId = "cli-membership",
        label = "CLI 会员额度",
        baseUrl = null,
        model = binary ?: "",
        authRef = null,
        cliBinary = binary,
    )

    private fun adapter(
        store: FakeConfigStore,
        runner: ScriptedRunner,
    ) = CliMembershipBrainAdapter(
        configStore = store,
        cliRunner = runner.toCliRunner(),
        clock = { 1_700_000_000L },
    )

    private fun successResult() = AgentRunResult.Success(
        "Agent 完成（exit=0）。最后输出：\nok",
        listOf("ok"),
    )

    private fun failedResult(tail: String) = AgentRunResult.Failed(
        1,
        "Agent 失败（exit=1）。最后输出：\n$tail",
        listOf(tail),
    )

    private fun chatRequest(
        text: String = "hi",
        tools: List<ToolDef> = emptyList(),
    ) = ChatRequest(
        messages = listOf(ChatMessage("user", text)),
        model = "codebuddy",
        tools = tools,
    )

    @Test
    fun `adapter identity is cli-membership SUBSCRIPTION with tools off`() = runTest {
        val a = adapter(FakeConfigStore(cliConfig()), ScriptedRunner(successResult()))
        assertEquals("cli-membership", a.adapterId)
        assertEquals(BrainKind.SUBSCRIPTION, a.kind)
        assertEquals("CLI 会员额度", a.label)
        assertFalse("toolCalling must be false: the CLI brain does not do tools in stage 1", a.capabilities().toolCalling)
        assertTrue(a.capabilities().streaming)
    }

    @Test
    fun `validateCredentials success writes LOGGED_IN with timestamp and summary`() = runTest {
        val store = FakeConfigStore(cliConfig())
        val runner = ScriptedRunner(successResult())
        val result = adapter(store, runner).validateCredentials()
        assertTrue(result.isSuccess)
        // The probe is one fixed read-only word against the configured binary.
        assertEquals("ok", runner.lastPrompt)
        assertEquals("codebuddy", runner.lastBinary)
        val saved = store.config
        assertEquals(AgentLoginState.LOGGED_IN, saved?.loginState)
        assertEquals(1_700_000_000L, saved?.lastTestedAtEpochSeconds)
        assertTrue(saved?.lastTestSummary?.contains("验证成功") == true)
    }

    @Test
    fun `validateCredentials auth failure writes NOT_LOGGED_IN`() = runTest {
        val store = FakeConfigStore(cliConfig())
        val result = adapter(
            store,
            ScriptedRunner(failedResult("Please log in to continue")),
        ).validateCredentials()
        assertTrue(result.isFailure)
        assertEquals(AgentLoginState.NOT_LOGGED_IN, store.config?.loginState)
        assertTrue(store.config?.lastTestSummary?.contains("未登录") == true)
        assertTrue((store.config?.lastTestedAtEpochSeconds ?: 0) > 0)
    }

    @Test
    fun `validateCredentials transport timeout and not-installed failures write UNKNOWN`() = runTest {
        // Transport: a non-zero exit with no auth wording proves nothing.
        val transportStore = FakeConfigStore(cliConfig())
        val transport = adapter(
            transportStore,
            ScriptedRunner(failedResult("connection reset by peer")),
        ).validateCredentials()
        assertTrue(transport.isFailure)
        assertEquals(AgentLoginState.UNKNOWN, transportStore.config?.loginState)
        // Timeout: killed, login unproven.
        val timeoutStore = FakeConfigStore(cliConfig())
        val timeout = adapter(
            timeoutStore,
            ScriptedRunner(AgentRunResult.Timeout("Agent 超时（>120s），已杀掉", emptyList())),
        ).validateCredentials()
        assertTrue(timeout.isFailure)
        assertEquals(AgentLoginState.UNKNOWN, timeoutStore.config?.loginState)
        assertTrue(timeoutStore.config?.lastTestSummary?.contains("超时") == true)
        // Not installed: no binary, no verdict.
        val missingStore = FakeConfigStore(cliConfig())
        val missing = adapter(
            missingStore,
            ScriptedRunner(AgentRunResult.NotInstalled("CodeBuddy 未安装：沙箱内未找到 codebuddy 二进制。")),
        ).validateCredentials()
        assertTrue(missing.isFailure)
        assertEquals(AgentLoginState.UNKNOWN, missingStore.config?.loginState)
        assertTrue(missingStore.config?.lastTestSummary?.contains("未安装") == true)
    }

    @Test
    fun `validateCredentials quota failure keeps LOGGED_IN but still fails`() = runTest {
        val store = FakeConfigStore(cliConfig())
        val result = adapter(
            store,
            ScriptedRunner(failedResult("quota exceeded for today")),
        ).validateCredentials()
        // Quota wording proves a login exists (only the quota is gone),
        // so the state is LOGGED_IN — but the brain cannot run, so the
        // probe itself still fails.
        assertTrue(result.isFailure)
        assertEquals(AgentLoginState.LOGGED_IN, store.config?.loginState)
        assertTrue(store.config?.lastTestSummary?.contains("额度") == true)
    }

    @Test
    fun `validateCredentials without binary fails without a writeback`() = runTest {
        val store = FakeConfigStore(cliConfig(binary = null))
        val runner = ScriptedRunner(successResult())
        val result = adapter(store, runner).validateCredentials()
        assertTrue(result.isFailure)
        assertTrue("no CLI call without a configured binary", runner.calls == 0)
        assertTrue(store.saved.isEmpty())
        // No config at all: same failure, no crash.
        val emptyStore = FakeConfigStore(null)
        val empty = adapter(emptyStore, runner).validateCredentials()
        assertTrue(empty.isFailure)
    }

    @Test
    fun `listModels returns an empty list`() = runTest {
        val a = adapter(FakeConfigStore(cliConfig()), ScriptedRunner(successResult()))
        val models = a.listModels()
        assertTrue(models.isSuccess)
        assertTrue(models.getOrNull()?.isEmpty() == true)
    }

    @Test
    fun `chatStream maps CLI text lines to TextDelta then Done`() = runTest {
        val a = adapter(
            FakeConfigStore(cliConfig()),
            ScriptedRunner(
                successResult(),
                lines = listOf("""{"type":"text","text":"你好"}"""),
            ),
        )
        val events = a.chatStream(chatRequest()).toList()
        assertEquals(
            listOf(BrainEvent.TextDelta("你好"), BrainEvent.Done("stop")),
            events,
        )
    }

    @Test
    fun `chatStream rejects tool requests without calling the CLI`() = runTest {
        val runner = ScriptedRunner(successResult())
        val a = adapter(FakeConfigStore(cliConfig()), runner)
        val request = chatRequest(
            tools = listOf(ToolDef("read_file", "read a file")),
        )
        val events = a.chatStream(request).toList()
        assertTrue("no CLI call when tools are requested", runner.calls == 0)
        assertEquals(2, events.size)
        val error = events[0] as BrainEvent.Error
        assertEquals("unsupported", error.code)
        assertTrue(error.message.contains("不支持工具调用"))
        assertEquals(BrainEvent.Done("error"), events[1])
    }

    @Test
    fun `chatStream rejects CLI tool calls as unsupported`() = runTest {
        val a = adapter(
            FakeConfigStore(cliConfig()),
            ScriptedRunner(
                successResult(),
                lines = listOf(
                    """{"type":"tool_use","id":"call-1","name":"read_file","input":{}}""",
                ),
            ),
        )
        val events = a.chatStream(chatRequest()).toList()
        assertEquals(2, events.size)
        val error = events[0] as BrainEvent.Error
        assertEquals("unsupported", error.code)
        assertTrue(error.message.contains("read_file"))
        assertEquals(BrainEvent.Done("error"), events[1])
    }

    @Test
    fun `chatStream maps CLI failure to an Error event`() = runTest {
        val a = adapter(
            FakeConfigStore(cliConfig()),
            ScriptedRunner(failedResult("connection reset by peer")),
        )
        val events = a.chatStream(chatRequest()).toList()
        assertEquals(2, events.size)
        val error = events[0] as BrainEvent.Error
        assertEquals("transport_error", error.code)
        assertTrue(error.retryable)
        assertEquals(BrainEvent.Done("error"), events[1])
    }
}
