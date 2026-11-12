package wherevercode.agent.brain

import org.json.JSONArray
import org.json.JSONObject

/**
 * StreamParser: SSE and NDJSON -> BrainEvent (stage 2).
 *
 * - SSE: OpenAI-compatible `data: {...}` lines + `data: [DONE]`.
 * - NDJSON: one JSON object per line, the shape each CLI's stream-json
 *   emits (CodeBuddy / Codex / Claude / Gemini).
 * Uses org.json only (bundled with Android, no extra dependency).
 *
 * A line that fails to parse is NEVER silently dropped: it is handed to
 * [onMalformedLine] verbatim, and the caller logs it (truncated to 2000
 * chars, see [truncateForLog]). Only known shapes are mapped; anything
 * else goes through the malformed callback.
 */
object StreamParser {

    /** Truncate text for log lines. Callers must use this before logging a malformed line. */
    fun truncateForLog(text: String, limit: Int = 2000): String =
        if (text.length <= limit) text else text.take(limit) + "…[truncated]"

    /** Parse one SSE `data:` line. Returns null when the line carries no event (blank / keep-alive / [DONE] handled by caller via Done). */
    fun parseSseLine(
        line: String,
        onMalformedLine: (String) -> Unit = {},
    ): BrainEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
        if (!trimmed.startsWith("data:")) {
            onMalformedLine(line)
            return null
        }
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") return BrainEvent.Done("stop")
        val json = try {
            JSONObject(payload)
        } catch (_: Exception) {
            onMalformedLine(line)
            return null
        }
        return parseChatCompletionChunk(json, onMalformedLine, line)
    }

    /** Parse a whole SSE body (multiple `data:` lines). */
    fun parseSseText(
        text: String,
        onMalformedLine: (String) -> Unit = {},
    ): List<BrainEvent> =
        text.lineSequence()
            .mapNotNull { parseSseLine(it, onMalformedLine) }
            .toList()

    /** Parse one NDJSON line (one JSON object per line, CLI stream-json). */
    fun parseNdjsonLine(
        line: String,
        onMalformedLine: (String) -> Unit = {},
    ): BrainEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val json = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            onMalformedLine(line)
            return null
        }
        // Some CLIs emit OpenAI-shaped chunks inside NDJSON; accept those too.
        if (json.has("choices")) return parseChatCompletionChunk(json, onMalformedLine, line)
        return parseCliEvent(json, onMalformedLine, line)
    }

    fun parseNdjsonText(
        text: String,
        onMalformedLine: (String) -> Unit = {},
    ): List<BrainEvent> =
        text.lineSequence()
            .mapNotNull { parseNdjsonLine(it, onMalformedLine) }
            .toList()

    /** Known OpenAI-compatible chunk shape. Unknown shapes -> malformed callback, never guessed. */
    fun parseChatCompletionChunk(
        json: JSONObject,
        onMalformedLine: (String) -> Unit = {},
        rawLine: String = "",
    ): BrainEvent? {
        if (json.has("usage") && !json.has("choices")) {
            val usage = json.optJSONObject("usage")
            if (usage != null) {
                return BrainEvent.Usage(
                    inputTokens = usage.optInt("prompt_tokens").takeIf { usage.has("prompt_tokens") },
                    outputTokens = usage.optInt("completion_tokens").takeIf { usage.has("completion_tokens") },
                )
            }
        }
        val choices = json.optJSONArray("choices") ?: run {
            onMalformedLine(rawLine.ifEmpty { json.toString() })
            return null
        }
        if (choices.length() == 0) {
            onMalformedLine(rawLine.ifEmpty { json.toString() })
            return null
        }
        val choice = choices.optJSONObject(0) ?: run {
            onMalformedLine(rawLine.ifEmpty { json.toString() })
            return null
        }
        if (json.has("usage")) {
            val usage = json.optJSONObject("usage")
            if (usage != null) {
                return BrainEvent.Usage(
                    inputTokens = usage.optInt("prompt_tokens").takeIf { usage.has("prompt_tokens") },
                    outputTokens = usage.optInt("completion_tokens").takeIf { usage.has("completion_tokens") },
                )
            }
        }
        val finishReason = choice.optString("finish_reason", "")
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        if (delta == null) {
            if (finishReason.isNotEmpty()) return BrainEvent.Done(finishReason)
            onMalformedLine(rawLine.ifEmpty { json.toString() })
            return null
        }
        val reasoning = delta.optString("reasoning_content", "")
        if (reasoning.isNotEmpty()) return BrainEvent.ReasoningDelta(reasoning)
        val content = delta.optString("content", "")
        if (content.isNotEmpty()) return BrainEvent.TextDelta(content)
        // 2026-11-09: check tool_calls BEFORE finish_reason. OpenAI-compatible
        // providers may put both in the same chunk; the old order returned
        // Done and silently dropped the call (a direct cause of empty args /
        // invalid_action). Multi-chunk argument accumulation is handled by
        // DeepSeekStreamParser; this single-line parser still only accepts a
        // complete arguments string in one chunk.
        val toolCalls = delta.optJSONArray("tool_calls")
        if (toolCalls != null && toolCalls.length() > 0) {
            val call = toolCalls.optJSONObject(0) ?: run {
                onMalformedLine(rawLine.ifEmpty { json.toString() })
                return null
            }
            val function = call.optJSONObject("function")
            val name = function?.optString("name", "") ?: call.optString("name", "")
            if (name.isEmpty()) {
                onMalformedLine(rawLine.ifEmpty { json.toString() })
                return null
            }
            val argsRaw = function?.optString("arguments", "") ?: ""
            val args = if (argsRaw.isBlank()) emptyMap() else try {
                jsonObjectToMap(JSONObject(argsRaw))
            } catch (_: Exception) {
                onMalformedLine(rawLine.ifEmpty { json.toString() })
                return null
            }
            return BrainEvent.ToolCall(
                id = call.optString("id", newCallId()),
                name = name,
                args = args,
            )
        }
        if (finishReason.isNotEmpty()) return BrainEvent.Done(finishReason)
        onMalformedLine(rawLine.ifEmpty { json.toString() })
        return null
    }

    /**
     * Known CLI stream-json shapes only:
     * - {"type":"text","text":"..."} -> TextDelta
     * - {"type":"reasoning","text":"..."} -> ReasoningDelta
     * - {"type":"tool_use"|"tool_call","id","name","input":{...}} -> ToolCall
     * - {"type":"result","..."} -> Done("stop")
     * - {"type":"error","message":"...","code":"..."} -> Error(retryable=false)
     * - {"type":"usage","input_tokens":..,"output_tokens":..} -> Usage
     * Anything else is malformed, never guessed.
     */
    fun parseCliEvent(
        json: JSONObject,
        onMalformedLine: (String) -> Unit = {},
        rawLine: String = "",
    ): BrainEvent? {
        val malformed = { onMalformedLine(rawLine.ifEmpty { json.toString() }); null }
        return when (val type = json.optString("type", "")) {
            "text", "assistant_stream", "content" -> {
                val text = json.optString("text", "")
                if (text.isEmpty()) malformed() else BrainEvent.TextDelta(text)
            }
            "reasoning", "thinking" -> {
                val text = json.optString("text", "")
                if (text.isEmpty()) malformed() else BrainEvent.ReasoningDelta(text)
            }
            "tool_use", "tool_call" -> {
                val name = json.optString("name", "")
                val input = json.optJSONObject("input")
                if (name.isEmpty() || input == null) malformed()
                else BrainEvent.ToolCall(
                    id = json.optString("id", newCallId()),
                    name = name,
                    args = jsonObjectToMap(input),
                )
            }
            "result", "done" -> BrainEvent.Done(json.optString("finish_reason", "stop"))
            "error" -> BrainEvent.Error(
                code = json.optString("code", "cli_error"),
                message = json.optString("message", "CLI 调用失败"),
                retryable = json.optBoolean("retryable", false),
            )
            "usage" -> BrainEvent.Usage(
                inputTokens = json.optInt("input_tokens").takeIf { json.has("input_tokens") },
                outputTokens = json.optInt("output_tokens").takeIf { json.has("output_tokens") },
            )
            else -> malformed()
        }
    }

    /** Recursive JSONObject -> Map<String, Any?> conversion. */
    fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = jsonValue(obj.get(key))
        }
        return out
    }

    private fun jsonValue(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> (0 until value.length()).map { jsonValue(value.get(it)) }
        else -> value
    }

    private var callCounter = 0
    private fun newCallId(): String {
        callCounter += 1
        return "call-$callCounter"
    }
}
