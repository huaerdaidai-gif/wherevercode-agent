package wherevercode.agent.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamParserTest {

    @Test
    fun `SSE text delta and DONE`() {
        val malformed = mutableListOf<String>()
        val events = StreamParser.parseSseText(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n" +
                "data: [DONE]\n",
        ) { malformed += it }
        assertTrue(malformed.isEmpty())
        assertEquals(3, events.size)
        assertEquals(BrainEvent.TextDelta("你好"), events[0])
        assertEquals(BrainEvent.TextDelta("，世界"), events[1])
        assertEquals(BrainEvent.Done("stop"), events[2])
    }

    @Test
    fun `SSE reasoning delta and tool call`() {
        val events = StreamParser.parseSseText(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先想想\"}}]}\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"a.txt\\\"}\"}}]}}]}\n" +
                "data: {\"choices\":[{\"finish_reason\":\"tool_calls\"}]}\n",
        )
        assertEquals(BrainEvent.ReasoningDelta("先想想"), events[0])
        val call = events[1] as BrainEvent.ToolCall
        assertEquals("c1", call.id)
        assertEquals("read_file", call.name)
        assertEquals("a.txt", call.args["path"])
        assertEquals(BrainEvent.Done("tool_calls"), events[2])
    }

    @Test
    fun `tool_calls are parsed before finish_reason in the same chunk`() {
        // 2026-11-09 regression: the old parser returned Done on finish_reason
        // first and dropped the tool call, which produced empty args and then
        // invalid_action downstream.
        val events = StreamParser.parseSseText(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"c1\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"README.txt\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n",
        )
        val call = events.single() as BrainEvent.ToolCall
        assertEquals("c1", call.id)
        assertEquals("README.txt", call.args["path"])
    }

    @Test
    fun `SSE usage carries provider numbers only`() {
        val events = StreamParser.parseSseText(
            "data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}\n",
        )
        assertEquals(BrainEvent.Usage(10, 20), events[0])
    }

    @Test
    fun `NDJSON CLI stream-json shapes`() {
        val malformed = mutableListOf<String>()
        val events = StreamParser.parseNdjsonText(
            "{\"type\":\"text\",\"text\":\"hi\"}\n" +
                "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"read_file\",\"input\":{\"path\":\"a.txt\"}}\n" +
                "{\"type\":\"result\",\"finish_reason\":\"stop\"}\n",
        ) { malformed += it }
        assertTrue(malformed.isEmpty())
        assertEquals(BrainEvent.TextDelta("hi"), events[0])
        val call = events[1] as BrainEvent.ToolCall
        assertEquals("read_file", call.name)
        assertEquals("a.txt", call.args["path"])
        assertEquals(BrainEvent.Done("stop"), events[2])
    }

    @Test
    fun `NDJSON error maps to Error event`() {
        val events = StreamParser.parseNdjsonText(
            "{\"type\":\"error\",\"code\":\"cli_failed\",\"message\":\"CLI 退出码 1\"}\n",
        )
        val error = events[0] as BrainEvent.Error
        assertEquals("cli_failed", error.code)
        assertEquals("CLI 退出码 1", error.message)
    }

    @Test
    fun `malformed lines go to the callback verbatim, never silently dropped`() {
        val malformed = mutableListOf<String>()
        val events = StreamParser.parseSseText(
            "data: {not json\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n" +
                "garbage line\n",
        ) { malformed += it }
        assertEquals(1, events.size)
        assertEquals(2, malformed.size)
        assertEquals("data: {not json", malformed[0])
        assertEquals("garbage line", malformed[1])
    }

    @Test
    fun `truncateForLog caps at 2000 chars`() {
        val long = "x".repeat(5000)
        val truncated = StreamParser.truncateForLog(long)
        assertTrue(truncated.length < 5000)
        assertTrue(truncated.startsWith("x".repeat(2000)))
        assertEquals("short", StreamParser.truncateForLog("short"))
    }
}
