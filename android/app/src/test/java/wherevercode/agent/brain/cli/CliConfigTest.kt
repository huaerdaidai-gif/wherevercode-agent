package wherevercode.agent.brain.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLI configuration table tests (V5 Phase B C3, second stage: generic
 * CLI runner). All static: the table is pure data, so no test ever
 * spawns a process. Pins the verified/unverified split, the
 * byte-identical argv of the two verified invocations (they must match
 * AgentRunner's historic calls exactly), and the mirror of
 * AgentDiscovery's directory (same ten binaries, ids and names).
 */
class CliConfigTest {

    @Test
    fun `table lists the ten discovery CLIs in discovery order`() {
        assertEquals(
            listOf(
                "codex", "claude", "muse", "gemini", "codebuddy", "agy",
                "qwen", "kimi", "arkcli", "grok",
            ),
            CliConfigs.ALL.map { it.binary },
        )
    }

    @Test
    fun `only codebuddy and codex are verified`() {
        assertEquals(
            setOf("codebuddy", "codex"),
            CliConfigs.VERIFIED.map { it.binary }.toSet(),
        )
        assertEquals(
            setOf("codebuddy", "codex"),
            CliRunner.SUPPORTED_BINARIES,
        )
    }

    @Test
    fun `unverified rows carry no template and no parse mode`() {
        for (binary in listOf("claude", "muse", "gemini", "agy", "qwen", "kimi", "arkcli", "grok")) {
            val config = CliConfigs.forBinary(binary)!!
            assertFalse("${binary} must be unverified", config.verified)
            assertNull("${binary} must have no args template", config.argsTemplate)
            assertNull("${binary} must have no parse mode", config.parseMode)
            assertNull(
                "${binary} must build no argv",
                config.buildArgv("/bin/$binary", "ok", "/workspace"),
            )
        }
    }

    @Test
    fun `row ids and display names mirror AgentDiscovery directory`() {
        assertEquals("codex-cli", CliConfigs.forBinary("codex")!!.agentId)
        assertEquals("Codex CLI", CliConfigs.forBinary("codex")!!.displayName)
        assertEquals("claude-code-cli", CliConfigs.forBinary("claude")!!.agentId)
        assertEquals("Claude Code", CliConfigs.forBinary("claude")!!.displayName)
        assertEquals("muse-code-cli", CliConfigs.forBinary("muse")!!.agentId)
        assertEquals("Muse Code", CliConfigs.forBinary("muse")!!.displayName)
        assertEquals("gemini-cli", CliConfigs.forBinary("gemini")!!.agentId)
        assertEquals("Gemini CLI", CliConfigs.forBinary("gemini")!!.displayName)
        assertEquals("codebuddy-cli", CliConfigs.forBinary("codebuddy")!!.agentId)
        assertEquals("CodeBuddy", CliConfigs.forBinary("codebuddy")!!.displayName)
        assertEquals("agy", CliConfigs.forBinary("agy")!!.agentId)
        assertEquals("Antigravity", CliConfigs.forBinary("agy")!!.displayName)
        assertEquals("qwen-code-cli", CliConfigs.forBinary("qwen")!!.agentId)
        assertEquals("Qwen Code", CliConfigs.forBinary("qwen")!!.displayName)
        assertEquals("kimi-code-cli", CliConfigs.forBinary("kimi")!!.agentId)
        assertEquals("Kimi Code", CliConfigs.forBinary("kimi")!!.displayName)
        assertEquals("ark-cli", CliConfigs.forBinary("arkcli")!!.agentId)
        assertEquals("Ark CLI", CliConfigs.forBinary("arkcli")!!.displayName)
        assertEquals("example-agent-cli", CliConfigs.forBinary("grok")!!.agentId)
        assertEquals("Example Agent", CliConfigs.forBinary("grok")!!.displayName)
    }

    @Test
    fun `codebuddy argv is byte-identical to AgentRunner historic invocation`() {
        val config = CliConfigs.forBinary("codebuddy")!!
        assertEquals(
            listOf(
                "/x/codebuddy", "-p",
                "--output-format", "stream-json",
                "--permission-mode", "acceptEdits", "hello",
            ),
            config.buildArgv("/x/codebuddy", "hello", "/workspace"),
        )
        assertFalse("codebuddy takes the prompt on argv, not stdin", config.promptViaStdin)
        assertEquals(CliParseMode.STREAM_JSON, config.parseMode)
    }

    @Test
    fun `codex argv is byte-identical to AgentRunner historic invocation`() {
        val config = CliConfigs.forBinary("codex")!!
        assertEquals(
            listOf(
                "/x/codex", "exec", "--json", "--skip-git-repo-check",
                "--sandbox", "workspace-write", "--cd", "/workspace", "-",
            ),
            config.buildArgv("/x/codex", "hello", "/workspace"),
        )
        assertTrue("codex takes the prompt on stdin", config.promptViaStdin)
        assertEquals(CliParseMode.STREAM_JSON, config.parseMode)
    }

    @Test
    fun `unknown binary has no row`() {
        assertNull(CliConfigs.forBinary("foobar"))
    }

    @Test
    fun `verified rows keep the historic 120s probe and 600s chat budgets`() {
        for (config in CliConfigs.VERIFIED) {
            assertEquals(120L, config.probeTimeoutSeconds)
            assertEquals(600L, config.chatTimeoutSeconds)
        }
    }
}
