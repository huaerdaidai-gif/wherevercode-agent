package wherevercode.agent.brain.deepseek

import org.json.JSONObject

/**
 * Stateful SSE parser for the OpenAI-compatible `/chat/completions` stream
 * DeepSeek serves.
 *
 * Why a dedicated parser (2026-11-09 root-cause fix): the generic
 * [wherevercode.agent.brain.StreamParser] parses one SSE line at a time and
 * (a) returned `Done` on `finish_reason` before looking at `delta.tool_calls`,
 * (b) read arguments from a single chunk, and (c) handled only one tool call.
 * Standard OpenAI-compatible streams split a tool call across chunks:
 *
 * ```
 * data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_0",
 *        "function":{"name":"list_files","arguments":""}}]}}]}
 * data: {"choices":[{"delta":{"tool_calls":[{"index":0,
 *        "function":{"arguments":"{\"path\":"}}]}}]}
 * data: {"choices":[{"delta":{"tool_calls":[{"index":0,
 *        "function":{"arguments":"\"\"}"}}]}}]}
 * data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
 * data: [DONE]
 * ```
 *
 * A line-at-a-time parser that requires complete JSON in one chunk therefore
 * produces empty/missing arguments → `invalid_action`; this parser accumulates
 * by call index and emits complete calls at the terminal event.
 *
 * Honesty rules: malformed lines are reported through [onMalformedLine],
 * never guessed; a stream that ends without `[DONE]` is a failure, and no
 * partial tool calls are executed as if the turn had completed.
 */
class DeepSeekStreamParser(
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
    private var finished = false
    private var sawTerminal = false
    private var sawFinishReason = false
    private var sawAnyEvent = false
    private var sawText = false
    private var sawReasoning = false
    private var emittedToolCall = false
    private var callCounter = 0

    /** True after `data: [DONE]`. */
    val terminal: Boolean get() = sawTerminal

    /**
     * Feed one physical line (without the newline). Blank lines dispatch a
     * pending SSE event; `data:` lines are buffered so a JSON payload split
     * over several lines is still parsed as one event.
     */
    fun feedLine(line: String): List<DeepSeekEvent> {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return dispatchPending()
        if (trimmed.startsWith(":")) return emptyList() // heartbeat/comment
        if (!trimmed.startsWith("data:")) {
            onMalformedLine(line)
            return emptyList()
        }
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") {
            val flushed = if (pendingData != null) dispatchPending() else emptyList()
            sawTerminal = true
            if (finished) return flushed
            finished = true
            return flushed + finishEvents()
        }
        val buf = pendingData ?: StringBuilder().also { pendingData = it }
        if (buf.isNotEmpty()) buf.append('\n')
        buf.append(payload)
        // Standard SSE separates events with a blank line, but several
        // OpenAI-compatible gateways omit it. If the buffered payload is
        // already a complete JSON object, dispatch it now; otherwise keep
        // buffering for a genuinely multi-line JSON payload.
        return try {
            JSONObject(buf.toString())
            dispatchPending()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Call at EOF. A stream without `[DONE]` is a retryable failure. */
    fun finish(): List<DeepSeekEvent> {
        if (finished && pendingData == null) return emptyList()
        // Dispatch the pending chunk BEFORE marking finished: dispatchPending
        // uses `finished` to ignore late duplicate choices, and a final
        // finish_reason/tool-call chunk must still be interpreted at EOF.
        val flushed = dispatchPending()
        finished = true
        if (sawTerminal || sawFinishReason) return flushed
        return flushed + if (sawAnyEvent) {
            listOf(
                DeepSeekEvent.Failed(
                    code = "stream_ended_without_terminal",
                    message = "DeepSeek 流在收到 [DONE] 前结束（网络中断或代理截断），已收到的内容不作为成功回复。",
                    retryable = true,
                ),
            )
        } else {
            listOf(
                DeepSeekEvent.Failed(
                    code = "stream_empty",
                    message = "DeepSeek 未返回任何内容（流为空）。",
                    retryable = true,
                ),
            )
        }
    }

    private fun dispatchPending(): List<DeepSeekEvent> {
        val data = pendingData?.toString()
        pendingData = null
        if (data.isNullOrBlank()) return emptyList()
        if (data == "[DONE]") {
            sawTerminal = true
            finished = true
            return finishEvents()
        }
        val json = try {
            JSONObject(data)
        } catch (_: Exception) {
            onMalformedLine("data: $data")
            return emptyList()
        }
        val out = mutableListOf<DeepSeekEvent>()

        json.optJSONObject("error")?.let { error ->
            val code = error.stringOrEmpty("code").ifEmpty { "provider_error" }
            val message = error.stringOrEmpty("message").ifEmpty { "DeepSeek 返回错误" }
            out += DeepSeekEvent.Failed(code = code, message = message, retryable = false)
            finished = true
            return out
        }

        json.optJSONObject("usage")?.let { usage ->
            val input = when {
                usage.has("prompt_tokens") && !usage.isNull("prompt_tokens") ->
                    usage.optInt("prompt_tokens")
                usage.has("input_tokens") && !usage.isNull("input_tokens") ->
                    usage.optInt("input_tokens")
                else -> null
            }
            val output = when {
                usage.has("completion_tokens") && !usage.isNull("completion_tokens") ->
                    usage.optInt("completion_tokens")
                usage.has("output_tokens") && !usage.isNull("output_tokens") ->
                    usage.optInt("output_tokens")
                else -> null
            }
            if (input != null || output != null) {
                out += DeepSeekEvent.Usage(input, output)
                sawAnyEvent = true
            }
        }

        // A finish_reason already ended the turn; the only valid late chunk
        // is the usage-only chunk OpenAI-compatible providers send before
        // [DONE]. Do not re-emit choices from a late/duplicate chunk.
        if (finished) return out
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            // A usage-only final chunk is valid; anything else is unknown.
            if (!json.has("usage")) onMalformedLine("data: $data")
            return out
        }
        val choice = choices.optJSONObject(0)
        if (choice == null) {
            onMalformedLine("data: $data")
            return out
        }
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        if (delta != null) {
            val reasoning = delta.stringOrEmpty("reasoning_content")
            if (reasoning.isNotEmpty()) {
                out += DeepSeekEvent.Reasoning(reasoning)
                sawReasoning = true
                sawAnyEvent = true
            }
            // Android's org.json optString(name, fallback) returns the literal
            // string "null" when the value is JSON null. Always guard isNull,
            // otherwise a null/absent content becomes a fake "null" token.
            val content = delta.stringOrEmpty("content")
            if (content.isNotEmpty()) {
                out += DeepSeekEvent.Text(content)
                if (content.isNotBlank()) sawText = true
                sawAnyEvent = true
            }
            val toolDeltas = delta.optJSONArray("tool_calls")
            if (toolDeltas != null) {
                for (i in 0 until toolDeltas.length()) {
                    val call = toolDeltas.optJSONObject(i) ?: continue
                    val index = call.optInt("index", i)
                    val pending = calls.getOrPut(index) { PendingCall() }
                    call.stringOrEmpty("id").takeIf { it.isNotEmpty() }?.let { pending.id = it }
                    val fn = call.optJSONObject("function")
                    if (fn != null) {
                        fn.stringOrEmpty("name").takeIf { it.isNotEmpty() }?.let { pending.name = it }
                        when (val rawArgs = fn.opt("arguments")) {
                            null, JSONObject.NULL -> Unit
                            is String -> pending.arguments.append(rawArgs)
                            is JSONObject -> pending.arguments.append(rawArgs.toString())
                            else -> pending.arguments.append(rawArgs.toString())
                        }
                    }
                    sawAnyEvent = true
                }
            }
        }
        // Same Android org.json trap as above: finish_reason is JSON null in
        // every non-terminal streaming chunk. Without isNull the first chunk
        // is treated as terminal and the rest of the stream (all content and
        // tool calls) is thrown away — the K70 blank-bubble bug.
        val finishReason = choice.stringOrEmpty("finish_reason")
        if (finishReason.isNotEmpty()) {
            sawFinishReason = true
            out += finishEvents(finishReason)
            finished = true
        }
        return out
    }

    /**
     * Emit complete calls once, then the terminal event. Called at
     * `finish_reason` and again at `[DONE]`; [emittedCalls] makes it
     * idempotent.
     */
    private fun finishEvents(reason: String = "stop"): List<DeepSeekEvent> {
        val out = mutableListOf<DeepSeekEvent>()
        if (!emittedCalls && calls.isNotEmpty()) {
            emittedCalls = true
            for ((index, pending) in calls) {
                if (pending.name.isBlank()) {
                    onMalformedLine("tool_calls[$index] missing function name")
                    continue
                }
                val id = pending.id?.takeIf { it.isNotBlank() } ?: run {
                    callCounter += 1
                    "deepseek-call-$callCounter"
                }
                val args = pending.arguments.toString().ifBlank { "{}" }
                out += DeepSeekEvent.ToolCall(id = id, name = pending.name, argumentsJson = args)
                emittedToolCall = true
            }
        }
        // A successful terminal event requires a user-visible answer (text)
        // or at least one executable tool call. A stream that only carries
        // reasoning/usage/finish_reason is NOT a successful empty reply:
        // treat it as a retryable failure so the UI shows a clear error
        // instead of leaving a blank bubble.
        if (sawText || emittedToolCall) {
            out += DeepSeekEvent.Finished(reason)
        } else {
            out += if (sawReasoning) {
                DeepSeekEvent.Failed(
                    code = "reasoning_only",
                    message = "DeepSeek 只返回了推理内容，没有返回可显示的回答。已停止本轮。",
                    retryable = true,
                )
            } else {
                DeepSeekEvent.Failed(
                    code = "empty_response",
                    message = "DeepSeek 返回了空回复（没有文字或工具调用）。已停止本轮。",
                    retryable = true,
                )
            }
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
        ): List<DeepSeekEvent> {
            val parser = DeepSeekStreamParser(onMalformedLine)
            val events = ArrayList<DeepSeekEvent>()
            for (line in text.lineSequence()) events += parser.feedLine(line)
            events += parser.finish()
            return events
        }
    }
}

/** Normalised events from one DeepSeek stream. */
sealed class DeepSeekEvent {
    data class Text(val text: String) : DeepSeekEvent()
    data class Reasoning(val text: String) : DeepSeekEvent()
    data class ToolCall(val id: String, val name: String, val argumentsJson: String) : DeepSeekEvent()
    data class Usage(val inputTokens: Int?, val outputTokens: Int?) : DeepSeekEvent()
    data class Finished(val reason: String) : DeepSeekEvent()
    data class Failed(val code: String, val message: String, val retryable: Boolean) : DeepSeekEvent()
}
