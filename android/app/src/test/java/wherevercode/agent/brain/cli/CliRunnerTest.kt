package wherevercode.agent.brain.cli

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.AgentRunResult
import wherevercode.agent.brain.BrainEvent

/**
 * CliRunner tests (C3-D). All fake: the injected invoke lambda stands in
 * for a real CLI process, so no test ever spawns a process. Pins line
 * parsing (stream-json vs plain text), auth classification and timeout
 * mapping.
 */
class CliRunnerTest {

    private fun runner(
        result: AgentRunResult,
        lines: List<String> = emptyList(),
    ): CliRunner = CliRunner { _, _, _, onRawLine ->
        lines.forEach { onRawLine(it) }
        result
    }

    private fun successResult() = AgentRunResult.Success(
        "Agent 完成（exit=0）。最后输出：\nok",
        listOf("ok"),
    )

    @Test
    fun `stream-json text lines become TextDelta events`() {
        val result = runner(
            successResult(),
            lines = listOf(
                """{"type":"text","text":"hello"}""",
                """{"type":"text","text":"world"}""",
            ),
        ).run("hi", "codebuddy")
        val success = result as CliCallResult.Success
        assertEquals("helloworld", success.text)
        assertEquals(
            listOf(BrainEvent.TextDelta("hello"), BrainEvent.TextDelta("world")),
            success.events,
        )
    }

    @Test
    fun `plain text lines are surfaced as text`() {
        val result = runner(
            successResult(),
            lines = listOf("hello plain"),
        ).run("hi", "codex")
        val success = result as CliCallResult.Success
        assertTrue(success.text.contains("hello plain"))
        assertEquals(listOf(BrainEvent.TextDelta("hello plain")), success.events)
    }

    @Test
    fun `auth wording in failed output classifies as AUTH`() {
        val result = runner(
            AgentRunResult.Failed(
                1,
                "Agent 失败（exit=1）。最后输出：\nPlease log in to continue",
                listOf("Please log in to continue"),
            ),
        ).run("ok", "codebuddy")
        val failure = result as CliCallResult.Failure
        assertEquals(CliFailureKind.AUTH, failure.kind)
        assertTrue(failure.summary.contains("未登录"))
    }

    @Test
    fun `timeout maps to TIMEOUT`() {
        val result = runner(
            AgentRunResult.Timeout("Agent 超时（>120s），已杀掉", emptyList()),
        ).run("ok", "codebuddy", timeoutSeconds = 120)
        val failure = result as CliCallResult.Failure
        assertEquals(CliFailureKind.TIMEOUT, failure.kind)
        assertTrue(failure.summary.contains("超时"))
    }

    @Test
    fun `unverified table rows are refused without spawning a process`() {
        for (binary in listOf("claude", "muse", "gemini", "agy")) {
            var calls = 0
            val result = CliRunner { _, _, _, _ ->
                calls += 1
                successResult()
            }.run("ok", binary)
            val failure = result as CliCallResult.Failure
            assertEquals(CliFailureKind.UNSUPPORTED_BINARY, failure.kind)
            assertEquals("no process may spawn for $binary", 0, calls)
            assertTrue(failure.summary.contains("未核实"))
        }
    }

    @Test
    fun `unknown binary is refused without spawning a process`() {
        var calls = 0
        val result = CliRunner { _, _, _, _ ->
            calls += 1
            successResult()
        }.run("ok", "foobar")
        val failure = result as CliCallResult.Failure
        assertEquals(CliFailureKind.UNSUPPORTED_BINARY, failure.kind)
        assertEquals(0, calls)
    }

    @Test
    fun `default timeout comes from the row chat budget and explicit timeout wins`() {
        var seenTimeout: Long? = null
        val capturing = CliRunner { _, _, timeoutSeconds, onRawLine ->
            seenTimeout = timeoutSeconds
            onRawLine("""{"type":"text","text":"ok"}""")
            successResult()
        }
        capturing.run("hi", "codebuddy")
        assertEquals(600L, seenTimeout)
        capturing.run("ok", "codex", timeoutSeconds = 120)
        assertEquals(120L, seenTimeout)
    }
}
