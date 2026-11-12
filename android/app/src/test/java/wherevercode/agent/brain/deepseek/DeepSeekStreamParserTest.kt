package wherevercode.agent.brain.deepseek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests use canned SSE only: no network, no key, no real model call.
 * The tool-call fragments mirror the standard OpenAI/DeepSeek streaming shape
 * that caused the 2026-11-09 K70 `invalid_action` failure.
 */
class DeepSeekStreamParserTest {

    @Test
    fun `text reasoning and usage are passed through`() {
        val events = DeepSeekStreamParser.parseSseText(
            """
            data: {"choices":[{"delta":{"reasoning_content":"先想想"}}]}

            data: {"choices":[{"delta":{"content":"蓝莓派"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2}}

            data: [DONE]

            """.trimIndent(),
        )
        assertTrue(events.contains(DeepSeekEvent.Reasoning("先想想")))
        assertTrue(events.contains(DeepSeekEvent.Text("蓝莓派")))
        assertTrue(events.contains(DeepSeekEvent.Usage(10, 2)))
        assertTrue(events.contains(DeepSeekEvent.Finished("stop")))
    }

    @Test
    fun `fragmented tool arguments are accumulated into one complete call`() {
        val events = DeepSeekStreamParser.parseSseText(
            """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_0","type":"function","function":{"name":"list_files","arguments":""}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"\"}"}}]}}]}

            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

            data: [DONE]

            """.trimIndent(),
        )
        val call = events.filterIsInstance<DeepSeekEvent.ToolCall>().single()
        assertEquals("call_0", call.id)
        assertEquals("list_files", call.name)
        assertEquals("""{"path":""}""", call.argumentsJson)
        assertTrue(events.contains(DeepSeekEvent.Finished("tool_calls")))
        // Exactly one terminal event, even though [DONE] follows finish_reason.
        assertEquals(1, events.filterIsInstance<DeepSeekEvent.Finished>().size)
    }

    @Test
    fun `multiple interleaved tool calls keep their indexes and ids`() {
        val events = DeepSeekStreamParser.parseSseText(
            """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"list_files","arguments":"{\"path\":\"\"}"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_b","function":{"name":"read_file","arguments":"{\"path\":\"README.txt\"}"}}]}}]}

            data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

            data: [DONE]

            """.trimIndent(),
        )
        val calls = events.filterIsInstance<DeepSeekEvent.ToolCall>()
        assertEquals(2, calls.size)
        assertEquals("call_a", calls[0].id)
        assertEquals("list_files", calls[0].name)
        assertEquals("call_b", calls[1].id)
        assertEquals("read_file", calls[1].name)
        assertEquals("""{"path":"README.txt"}""", calls[1].argumentsJson)
    }

    @Test
    fun `tool calls are emitted at DONE even without a finish_reason`() {
        val events = DeepSeekStreamParser.parseSseText(
            """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"list_files","arguments":"{}"}}]}}]}

            data: [DONE]

            """.trimIndent(),
        )
        val call = events.filterIsInstance<DeepSeekEvent.ToolCall>().single()
        assertEquals("c1", call.id)
        assertEquals("{}", call.argumentsJson)
        assertTrue(events.contains(DeepSeekEvent.Finished("stop")))
    }

    @Test
    fun `missing id gets a deterministic generated id`() {
        val events = DeepSeekStreamParser.parseSseText(
            """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"list_files","arguments":"{}"}}]}}]}

            data: [DONE]

            """.trimIndent(),
        )
        val call = events.filterIsInstance<DeepSeekEvent.ToolCall>().single()
        assertTrue(call.id.isNotBlank())
        assertEquals("list_files", call.name)
    }

    @Test
    fun `truncated stream fails without executing partial tool calls`() {
        val events = DeepSeekStreamParser.parseSseText(
            """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"read_file","arguments":"{\"path\":"}}]}}]}

            """.trimIndent(),
        )
        assertTrue(events.none { it is DeepSeekEvent.ToolCall })
        val failure = events.filterIsInstance<DeepSeekEvent.Failed>().single()
        assertEquals("stream_ended_without_terminal", failure.code)
        assertTrue(failure.retryable)
    }

    @Test
    fun `malformed lines are reported verbatim never guessed`() {
        val malformed = mutableListOf<String>()
        val events = DeepSeekStreamParser.parseSseText(
            """
            data: {not json

            data: {"choices":[{"delta":{"content":"ok"}}]}

            data: [DONE]

            """.trimIndent(),
            onMalformedLine = { malformed += it },
        )
        assertTrue(events.contains(DeepSeekEvent.Text("ok")))
        assertEquals(1, malformed.size)
        assertTrue(malformed[0].startsWith("data: {not json"))
    }

    @Test
    fun `empty DONE-only stream is an explicit failure not a blank success`() {
        val events = DeepSeekStreamParser.parseSseText("data: [DONE]\n")
        val failure = events.filterIsInstance<DeepSeekEvent.Failed>().single()
        assertEquals("empty_response", failure.code)
        assertTrue(failure.retryable)
        assertTrue(events.none { it is DeepSeekEvent.Finished })
    }

    @Test
    fun `reasoning-only stream is an explicit failure`() {
        val events = DeepSeekStreamParser.parseSseText(
            """
            data: {"choices":[{"delta":{"reasoning_content":"只有思考"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """.trimIndent(),
        )
        val failure = events.filterIsInstance<DeepSeekEvent.Failed>().single()
        assertEquals("reasoning_only", failure.code)
        assertTrue(events.none { it is DeepSeekEvent.Finished })
    }

    @Test
    fun `provider error event is a failure and stops the stream`() {
        val events = DeepSeekStreamParser.parseSseText(
            """
            data: {"error":{"code":"invalid_request_error","message":"bad tools"}}

            data: [DONE]

            """.trimIndent(),
        )
        val failure = events.filterIsInstance<DeepSeekEvent.Failed>().single()
        assertEquals("invalid_request_error", failure.code)
        assertTrue(failure.message.contains("bad tools"))
        assertTrue(!failure.retryable)
    }
}
