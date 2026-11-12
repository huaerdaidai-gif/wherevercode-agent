package wherevercode.agent.brain.claude

import org.json.JSONObject

/**
 * Stateful SSE parser for Anthropic's Messages API stream.
 *
 * Wire facts (Anthropic Messages API, public docs):
 * - The stream is SSE: `event: <type>` lines plus `data: {...}` lines,
 *   events separated by blank lines; there is NO `[DONE]` sentinel — the
 *   stream ends with a `message_stop` event.
 * - Event types: `message_start` (carries `message.usage.input_tokens`),
 *   `content_block_start` (a `text` block or a `tool_use` block with id +
 *   name), `content_block_delta` (`text_delta` with `text`, or
 *   `input_json_delta` with `partial_json` fragments of the tool input),
 *   `content_block_stop`, `message_delta` (carries `delta.stop_reason`
 *   and `usage.output_tokens`), `message_stop`, `ping`.
 * - A tool call's `input` arrives as JSON fragments across deltas and is
 *   accumulated by content-block index, exactly like the OpenAI-compatible
 *   parsers: a line-at-a-time parser that requires complete JSON in one
 *   chunk would produce empty/missing arguments.
 *
 * Honesty rules (same as DeepSeekStreamParser): malformed lines are
 * reported through [onMalformedLine], never guessed; a stream that ends
 * without `message_stop`/`message_delta` is a failure, and no partial
 * tool calls are executed as if the turn had completed.
 */
class ClaudeStreamParser(
    private val onMalformedLine: (String) -> Unit = {},
) {

    private data class PendingCall(
        var id: String? = null,
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private val calls = LinkedHashMap<Int, PendingCall>()
    private var pendingData: StringBuilder? = null
    private var emittedCalls = false
    private var emittedFinished = false
    private var finished = false
    private var sawTerminal = false
    private var sawFinishReason = false
    private var sawAnyEvent = false
    private var sawText = false
    private var emittedToolCall = false
    private var callCounter = 0
    private var lastStopReason = "end_turn"

    /** True after `message_stop`. */
    val terminal: Boolean get() = sawTerminal

    /**
     * Feed one physical line (without the newline). Blank lines dispatch a
     * pending SSE event; `data:` lines are buffered so a JSON payload split
     * over several lines is still parsed as one event.
     */
    fun feedLine(line: String): List<ClaudeEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return dispatchPending()
        if (trimmed.startsWith(":")) return emptyList() // heartbeat/comment
        if (trimmed.startsWith("event:")) return emptyList() // type also in data JSON
        if (!trimmed.startsWith("data:")) {
            onMalformedLine(line)
            return emptyList()
        }
        val payload = trimmed.removePrefix("data:").trim()
        val buf = pendingData ?: StringBuilder().also { pendingData = it }
        if (buf.isNotEmpty()) buf.append('\n')
        buf.append(payload)
        // Standard SSE separates events with a blank line, but several
        // gateways omit it. If the buffered payload is already a complete
        // JSON object, dispatch it now; otherwise keep buffering for a
        // genuinely multi-line JSON payload.
        return try {
            JSONObject(buf.toString())
            dispatchPending()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Call at EOF. A stream without `message_stop` is a retryable failure. */
    fun finish(): List<ClaudeEvent> {
        if (finished && pendingData == null) return emptyList()
        // Dispatch the pending chunk BEFORE marking finished: dispatchPending
        // uses `finished` to ignore late duplicate events, and a final
        // message_delta chunk must still be interpreted at EOF.
        val flushed = dispatchPending()
        finished = true
        if (sawTerminal || sawFinishReason) return flushed
        return flushed + if (sawAnyEvent) {
            listOf(
                ClaudeEvent.Failed(
                    code = "stream_ended_without_terminal",
                    message = "Claude 流在收到 message_stop 前结束（网络中断或代理截断），已收到的内容不作为成功回复。",
                    retryable = true,
                ),
            )
        } else {
            listOf(
                ClaudeEvent.Failed(
                    code = "stream_empty",
                    message = "Claude 未返回任何内容（流为空）。",
                    retryable = true,
                ),
            )
        }
    }

    private fun dispatchPending(): List<ClaudeEvent> {
        val data = pendingData?.toString()
        pendingData = null
        if (data.isNullOrBlank()) return emptyList()
        val json = try {
            JSONObject(data)
        } catch (_: Exception) {
            onMalformedLine("data: $data")
            return emptyList()
        }
        val out = mutableListOf<ClaudeEvent>()

        // An error event can arrive mid-stream (e.g. overloaded).
        json.optJSONObject("error")?.let { error ->
            val type = error.stringOrEmpty("type").ifEmpty { "provider_error" }
            val message = error.stringOrEmpty("message").ifEmpty { "Claude 返回错误" }
            out += ClaudeEvent.Failed(
                code = type,
                message = message,
                retryable = type == "overloaded_error",
            )
            finished = true
            return out
        }

        val type = json.stringOrEmpty("type")
        when (type) {
            "message_start" -> {
                val usage = json.optJSONObject("message")?.optJSONObject("usage")
                val input = usage?.let {
                    if (it.has("input_tokens") && !it.isNull("input_tokens")) it.optInt("input_tokens") else null
                }
                if (input != null) {
                    out += ClaudeEvent.Usage(input, null)
                    sawAnyEvent = true
                }
            }
            "content_block_start" -> {
                val index = json.optInt("index", 0)
                val block = json.optJSONObject("content_block") ?: return out
                if (block.stringOrEmpty("type") == "tool_use") {
                    val pending = calls.getOrPut(index) { PendingCall() }
                    block.stringOrEmpty("id").takeIf { it.isNotEmpty() }?.let { pending.id = it }
                    block.stringOrEmpty("name").takeIf { it.isNotEmpty() }?.let { pending.name = it }
                    sawAnyEvent = true
                }
            }
            "content_block_delta" -> {
                if (finished) return out
                val index = json.optInt("index", 0)
                val delta = json.optJSONObject("delta") ?: return out
                when (delta.stringOrEmpty("type")) {
                    "text_delta" -> {
                        val text = delta.stringOrEmpty("text")
                        if (text.isNotEmpty()) {
                            out += ClaudeEvent.Text(text)
                            if (text.isNotBlank()) sawText = true
                            sawAnyEvent = true
                        }
                    }
                    "input_json_delta" -> {
                        val fragment = delta.stringOrEmpty("partial_json")
                        if (fragment.isNotEmpty()) {
                            val pending = calls.getOrPut(index) { PendingCall() }
                            pending.arguments.append(fragment)
                            sawAnyEvent = true
                        }
                    }
                    // thinking_delta / signature_delta: extended thinking is
                    // not enabled in our requests; ignore silently.
                    else -> Unit
                }
            }
            "content_block_stop" -> {
                // All fragments for this block have arrived; the call is
                // emitted at the terminal event (message_delta/message_stop)
                // so an abruptly cut stream never executes a partial call.
            }
            "message_delta" -> {
                val delta = json.optJSONObject("delta")
                val usage = json.optJSONObject("usage")
                val output = usage?.let {
                    if (it.has("output_tokens") && !it.isNull("output_tokens")) it.optInt("output_tokens") else null
                }
                if (output != null) {
                    out += ClaudeEvent.Usage(null, output)
                    sawAnyEvent = true
                }
                val stopReason = delta?.stringOrEmpty("stop_reason").orEmpty()
                if (stopReason.isNotEmpty()) {
                    lastStopReason = stopReason
                    sawFinishReason = true
                    out += finishEvents(stopReason)
                    finished = true
                }
            }
            "message_stop" -> {
                sawTerminal = true
                out += finishEvents(lastStopReason)
                finished = true
            }
            "ping" -> Unit
            else -> {
                // Unknown future event types are ignored silently: Anthropic
                // may add event types, and an unknown type carries no text
                // or tool call we could act on.
            }
        }
        return out
    }

    /**
     * Emit complete calls once, then the terminal event. Called at
     * `message_delta` (stop_reason) and again at `message_stop`;
     * [emittedFinished] makes it idempotent.
     */
    private fun finishEvents(reason: String = "end_turn"): List<ClaudeEvent> {
        if (emittedFinished) return emptyList()
        emittedFinished = true
        val out = mutableListOf<ClaudeEvent>()
        if (!emittedCalls && calls.isNotEmpty()) {
            emittedCalls = true
            for ((index, pending) in calls) {
                if (pending.name.isBlank()) {
                    onMalformedLine("tool_use[$index] missing name")
                    continue
                }
                val id = pending.id?.takeIf { it.isNotBlank() } ?: run {
                    callCounter += 1
                    "claude-call-$callCounter"
                }
                val args = pending.arguments.toString().ifBlank { "{}" }
                out += ClaudeEvent.ToolCall(id = id, name = pending.name, argumentsJson = args)
                emittedToolCall = true
            }
        }
        // A successful terminal event requires a user-visible answer (text)
        // or at least one executable tool call. A stream that only carries
        // usage/stop_reason is NOT a successful empty reply: treat it as a
        // retryable failure so the UI shows a clear error instead of leaving
        // a blank bubble.
        if (sawText || emittedToolCall) {
            out += ClaudeEvent.Finished(reason)
        } else {
            out += ClaudeEvent.Failed(
                code = "empty_response",
                message = "Claude 返回了空回复（没有文字或工具调用）。已停止本轮。",
                retryable = true,
            )
        }
        return out
    }

    /** Android org.json returns "null" for JSON null via optString(name, fallback); never use that here. */
    private fun JSONObject.stringOrEmpty(name: String): String =
        if (isNull(name)) "" else optString(name, "")

    companion object {
        /** Convenience for tests and non-stream responses. */
        fun parseSseText(
            text: String,
            onMalformedLine: (String) -> Unit = {},
        ): List<ClaudeEvent> {
            val parser = ClaudeStreamParser(onMalformedLine)
            val events = ArrayList<ClaudeEvent>()
            for (line in text.lineSequence()) events += parser.feedLine(line)
            events += parser.finish()
            return events
        }
    }
}

/** Normalised events from one Claude stream. */
sealed class ClaudeEvent {
    data class Text(val text: String) : ClaudeEvent()
    data class ToolCall(val id: String, val name: String, val argumentsJson: String) : ClaudeEvent()
    data class Usage(val inputTokens: Int?, val outputTokens: Int?) : ClaudeEvent()
    data class Finished(val reason: String) : ClaudeEvent()
    data class Failed(val code: String, val message: String, val retryable: Boolean) : ClaudeEvent()
}
