package wherevercode.agent.brain.cloudflare

import org.json.JSONObject

/**
 * Workers AI SSE 解析器（CloudflareStreamParser）。
 *
 * 解析官方 OpenAI 兼容端点 `/ai/v1/chat/completions` 在 `stream: true`
 * 时返回的 Server-Sent Events（P1-2，2026-11-09 由原生 `/ai/run` 切
 * 过来；原生流的 `{"response": "…"}` / 无 id `tool_calls` 形状已废止）。
 * 事件形状（标准 OpenAI chat completions 流，与 DeepSeek 路径同形，
 * 累积逻辑照抄 [wherevercode.agent.brain.deepseek.DeepSeekStreamParser]
 * 的逐 index 累积做法——跨 chunk 拆分的工具参数必须拼完整再发出，
 * 否则就是缺参调用）：
 * - 每行 `data: {"choices": [{"delta": {"content": "<文本片段>"}}]}`；
 * - 工具调用分片到达：`delta.tool_calls[]` 每片含 `index`，首片含
 *   `id` 与 `function.name`，`function.arguments` 是拆成多段的 JSON
 *   字符串；`finish_reason: "tool_calls"` 的 chunk 到达时才一次性
 *   发出完整调用；
 * - 流以 `data: [DONE]` 终结；末尾 chunk 可能带 `usage`（token 数，
 *   须请求 `stream_options.include_usage` 才有）。
 *
 * 诚实性规则（与 ResponsesStreamParser 一致）：
 * - 未知形状 / 缺字段 / 非法 JSON 一律走 [onMalformedLine]，绝不猜测、绝不静默丢弃。
 * - 只有服务器真实发来的文本才会变成 [CloudflareStreamEvent.TextDelta]；
 *   未收到真实 delta 时绝不产出文本。
 * - 服务端发的工具调用 id 原样进 [CloudflareStreamEvent.ToolCallDone.callId]；
 *   只有某调用全程缺 id 时才回落客户端自造 `cf-call-N`（与旧行为、
 *   与 DeepSeek 解析器的 `deepseek-call-N` 回落一致），绝不编造 name
 *   或 arguments。
 * - `reasoning_content`（推理模型才发）本解析器直接忽略、不发文本；
 *   8B 默认模型不发该字段，忽略与否不影响本轮行为。
 *
 * 终结语义：
 * - 收到 `data: [DONE]` 后解析器进入终结态，后续行不再产出事件。
 * - 流在未收到 `[DONE]` 前结束（EOF）时，[finish] 返回一个 Failed，
 *   调用方据此判定“流被截断”——绝不把已收到的 partial text 当作成功。
 *
 * UNVERIFIED（文档未明确、代码中已按保守策略处理）：
 * 1. `usage` 是否随流式 chunk 返回：须 `stream_options.include_usage`
 *    才有；兼容端点是否接受该字段、是否真的回 usage，均待 K70 实测。
 *    没有 usage 时 Completed.usage 为 null（不估算）。
 * 2. `tool_calls` 的分片粒度：按 OpenAI 惯例逐 index 累积；服务端若
 *    一次发完整调用同样处理（首片即含全部 arguments）。
 */
class CloudflareStreamParser(
    private val onMalformedLine: (String) -> Unit = {},
) {

    private data class PendingCall(
        var id: String? = null,
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    /** 已组装的回复全文（仅由真实 delta 累积）。 */
    val accumulatedText: String
        get() = textBuffer.toString()

    /** 是否已收到 `data: [DONE]` 终结哨兵。 */
    var sawTerminal: Boolean = false
        private set

    private val textBuffer = StringBuilder()
    private var usage: CloudflareUsage? = null
    private var pendingData: StringBuilder? = null
    private val calls = LinkedHashMap<Int, PendingCall>()
    private var emittedCalls = false
    private var finished = false
    private var sawFinishReason = false
    private var callCounter = 0

    /**
     * 喂入 SSE 流的一行（不含换行符）。返回该行产出的事件（通常 0 或 1 个）。
     * 空行表示一个 SSE 事件结束，触发分发。
     */
    fun feedLine(line: String): List<CloudflareStreamEvent> {
        if (sawTerminal) return emptyList()
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return dispatchPending()
        if (trimmed.startsWith(":")) return emptyList() // SSE 注释 / 心跳，忽略。
        if (trimmed.startsWith("event:")) {
            // 兼容端点流是 data-only，不带 event: 行；若出现，视为
            // 未知行走 malformed（绝不按 Responses API 的命名事件去猜）。
            onMalformedLine(line)
            return emptyList()
        }
        if (!trimmed.startsWith("data:")) {
            // 既不是 data: 也不是注释：未知行，走 malformed，绝不猜测。
            onMalformedLine(line)
            return emptyList()
        }
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") {
            // [DONE] 绝不可能是上一事件 JSON 的续行：若有挂起数据未分发
            //（上游省略了事件间的空行），先分发它，再记终结。
            val flushed = if (pendingData != null) dispatchPending() else emptyList()
            sawTerminal = true
            if (finished) return flushed
            finished = true
            return flushed + finishEvents()
        }
        val buf = pendingData ?: StringBuilder().also { pendingData = it }
        if (buf.isNotEmpty()) buf.append('\n')
        buf.append(payload)
        // 标准 SSE 用空行分隔事件，但不少 OpenAI 兼容网关会省略空行。
        // 缓冲内容已是完整 JSON 对象时立即分发；否则继续攒（真正的
        // 多行 JSON 事件体仍能拼完整，见多行分片测试）。
        return try {
            JSONObject(buf.toString())
            dispatchPending()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 流结束时调用（EOF / 连接关闭）。若至今未收到 `[DONE]`，返回一个 Failed，
     * 调用方据此判定“流被截断”——绝不把已收到的 partial text 当作成功。
     * 若已终结，返回空列表。
     */
    fun finish(): List<CloudflareStreamEvent> {
        if (finished && pendingData == null) return emptyList()
        // 先分发挂起 chunk 再标终结：dispatchPending 用 finished 判断
        // 是否忽略迟到的重复 choices，末尾的 finish_reason / 工具调用
        // chunk 在 EOF 时仍须被解读。
        val flushed = dispatchPending()
        finished = true
        if (sawTerminal || sawFinishReason) return flushed
        val failure = CloudflareStreamEvent.Failed(
            code = "stream_ended_without_terminal",
            message = "Cloudflare 流在未收到 data: [DONE] 前结束（可能为网络中断或代理截断），" +
                "已收到的文本不作成功回复。",
            retryable = true,
        )
        return flushed + failure
    }

    /** 便捷方法：解析整段 SSE 文本（含 finish 的截断判定）。 */
    companion object {
        fun parseSseText(
            text: String,
            onMalformedLine: (String) -> Unit = {},
        ): List<CloudflareStreamEvent> {
            val parser = CloudflareStreamParser(onMalformedLine)
            val events = ArrayList<CloudflareStreamEvent>()
            for (line in text.lineSequence()) {
                events += parser.feedLine(line)
            }
            events += parser.finish()
            return events
        }
    }

    private fun dispatchPending(): List<CloudflareStreamEvent> {
        val data = pendingData?.toString()
        pendingData = null
        if (data.isNullOrBlank()) return emptyList()
        if (data == "[DONE]") {
            sawTerminal = true
            if (finished) return emptyList()
            finished = true
            return finishEvents()
        }
        val json = try {
            JSONObject(data)
        } catch (_: Exception) {
            onMalformedLine("data: $data")
            return emptyList()
        }
        val events = ArrayList<CloudflareStreamEvent>()

        json.optJSONObject("error")?.let { error ->
            val code = error.stringOrEmpty("code").ifEmpty { "provider_error" }
            val message = error.stringOrEmpty("message").ifEmpty { "Cloudflare 返回错误" }
            events += CloudflareStreamEvent.Failed(code = code, message = message, retryable = false)
            finished = true
            return events
        }

        // usage：只取服务器真实返回的数字，绝不估算。Completed 事件
        // 发出时才带上（见 finishEvents）。
        json.optJSONObject("usage")?.let { usageJson ->
            usage = parseUsage(usageJson)
        }

        // finish_reason 已终结本轮后，唯一合法的迟到 chunk 是 usage-only
        // chunk；不再从迟到 / 重复 chunk 里解读 choices。
        if (finished) return events
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            // usage-only 的末尾 chunk 合法；其他未知形状走 malformed。
            if (!json.has("usage")) onMalformedLine("data: $data")
            return events
        }
        val choice = choices.optJSONObject(0)
        if (choice == null) {
            onMalformedLine("data: $data")
            return events
        }
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
        if (delta != null) {
            // Android 的 org.json optString(name, fallback) 在值为 JSON null
            // 时返回字面 "null"；一律用 stringOrEmpty 守，否则 null content
            // 会变成假 "null" 文本。
            val content = delta.stringOrEmpty("content")
            if (content.isNotEmpty()) {
                textBuffer.append(content)
                events += CloudflareStreamEvent.TextDelta(text = content)
            }
            // reasoning_content 直接忽略（见类注释），不发文本、不记 malformed。
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
                }
            }
        }
        // 同样的 org.json 陷阱：finish_reason 在每个非终结流式 chunk 里
        // 都是 JSON null；不用 isNull 守，第一 chunk 就会被误判为终结，
        // 其余内容与工具调用全被丢掉。
        val finishReason = choice.stringOrEmpty("finish_reason")
        if (finishReason.isNotEmpty()) {
            sawFinishReason = true
            events += finishEvents()
            finished = true
        }
        return events
    }

    /**
     * 发出已累积完整的工具调用（一次），再发 Completed。终结时（[DONE] 或
     * finish_reason）调用；[emittedCalls] 保证幂等。
     */
    private fun finishEvents(): List<CloudflareStreamEvent> {
        val out = mutableListOf<CloudflareStreamEvent>()
        if (!emittedCalls && calls.isNotEmpty()) {
            emittedCalls = true
            for ((index, pending) in calls) {
                if (pending.name.isBlank()) {
                    onMalformedLine("tool_calls[$index] missing function name")
                    continue
                }
                val id = pending.id?.takeIf { it.isNotBlank() } ?: run {
                    callCounter += 1
                    "cf-call-$callCounter"
                }
                val args = pending.arguments.toString().ifBlank { "{}" }
                out += CloudflareStreamEvent.ToolCallDone(
                    name = pending.name,
                    arguments = args,
                    callId = id,
                )
            }
        }
        out += CloudflareStreamEvent.Completed(
            usage = usage,
            fullText = textBuffer.toString().ifEmpty { null },
        )
        return out
    }

    private fun parseUsage(usageJson: JSONObject): CloudflareUsage {
        val input = when {
            usageJson.has("prompt_tokens") -> usageJson.optLong("prompt_tokens")
            usageJson.has("input_tokens") -> usageJson.optLong("input_tokens")
            else -> null
        }
        val output = when {
            usageJson.has("completion_tokens") -> usageJson.optLong("completion_tokens")
            usageJson.has("output_tokens") -> usageJson.optLong("output_tokens")
            else -> null
        }
        val total = usageJson.optLong("total_tokens").takeIf { usageJson.has("total_tokens") }
        return CloudflareUsage(
            inputTokens = input,
            outputTokens = output,
            totalTokens = total,
        )
    }

    /** Android org.json returns "null" for JSON null via optString(name, fallback); never use that here. */
    private fun JSONObject.stringOrEmpty(name: String): String =
        if (isNull(name)) "" else optString(name, "")
}

/** Token 用量：只取服务器真实返回的数字，绝不估算。 */
data class CloudflareUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
)

/**
 * Cloudflare 流的归一化事件。
 *
 * 到 M2 [BrainEvent] 的映射（由桥接层 CloudflareM2BrainAdapter 实现）：
 * - TextDelta -> BrainEvent.TextDelta(text)
 * - ToolCallDone -> BrainEvent.ToolCall(id = 服务端真实 id，缺失时为 cf-call-N 回落，name, args = 解析 arguments JSON 字符串)
 * - Completed -> BrainEvent.Done("stop") + BrainEvent.Usage(inputTokens, outputTokens)
 * - Failed -> BrainEvent.Error(code, message, retryable)
 * 关键不变量：Failed 出现时，已发出的 TextDelta 只是“部分文本”，桥接层必须丢弃
 * 部分文本、不把它当作成功回复展示。
 */
sealed class CloudflareStreamEvent {
    /** 一段真实的助手文本增量（仅来自服务器真实的 delta.content）。 */
    data class TextDelta(val text: String) : CloudflareStreamEvent()

    /** 一次工具调用：arguments 为完整 JSON 字符串（调用方负责校验 JSON 合法性）。 */
    data class ToolCallDone(
        val name: String,
        val arguments: String,
        val callId: String?,
    ) : CloudflareStreamEvent()

    /** 回复完成（收到 `data: [DONE]` 或 finish_reason）：usage 仅为服务器返回的真实数字。 */
    data class Completed(
        val usage: CloudflareUsage?,
        val fullText: String?,
    ) : CloudflareStreamEvent()

    /** 回复失败：调用方必须丢弃已收到的 partial text，不把它当成功。 */
    data class Failed(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : CloudflareStreamEvent()
}
