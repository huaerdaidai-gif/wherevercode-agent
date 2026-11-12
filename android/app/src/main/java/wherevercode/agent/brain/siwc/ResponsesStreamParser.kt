package wherevercode.agent.brain.siwc

import org.json.JSONObject

/**
 * Responses API SSE 解析器（ResponsesStreamParser）。
 *
 * 解析 OpenAI Responses API 的流式 Server-Sent Events。事件形状已从官方文档核实：
 * - 流式事件一览（官方 API 参考的流式事件表，经 DeepSeek 的官方格式复述交叉核对，
 *   https://platform.openai.com/docs/api-reference/responses-streaming ，
 *   https://api-docs.deepseek.com/guides/responses_api/ ）：
 *   `response.created` / `response.in_progress` / `response.output_item.added` /
 *   `response.output_item.done` / `response.content_part.added` /
 *   `response.content_part.done` / `response.output_text.delta`（`delta` 字段）/
 *   `response.output_text.done`（`text` 字段）/
 *   `response.function_call_arguments.delta`（`delta` 字段）/
 *   `response.function_call_arguments.done`（`arguments` 字段）/
 *   `response.completed`（`response` 对象，内含 `usage`）/ `response.incomplete` /
 *   `response.failed`（`response.error`）/ 流级 `error` 事件。
 *   每个事件带单调递增的 `sequence_number`；流以 `response.completed` /
 *   `response.incomplete` / `response.failed` 其中之一终结。
 * - `usage` 字段：`input_tokens` / `output_tokens` / `total_tokens`
 *   （另有 `input_tokens_details.cached_tokens`、
 *   `output_tokens_details.reasoning_tokens`，本解析器不消费，仅保留总数）。
 * - SIWC Preview 限制（官方，
 *   https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations ）：
 *   本解析器不处理请求体，但与之配合的 [ResponsesApiClient] 强制 `store=false`、
 *   `stream=true`、全量历史、`instructions` 承载系统指引。
 *
 * 与 M2 的 Chat Completions 解析器（`StreamParser`，只认 `choices/delta`）的区别：
 * Responses API 是命名事件（`event: <type>` + `data: {...}`），不是 data-only；
 * 文本增量在 `response.output_text.delta.delta`，不是 `choices[0].delta.content`。
 *
 * 诚实性规则（与 M2 StreamParser 一致）：
 * - 未知事件形状 / 缺字段 / 非法 JSON 一律走 [onMalformedLine]，绝不猜测、绝不静默丢弃。
 * - 推理类增量（`response.reasoning_text.delta` /
 *   `response.reasoning_summary_text.delta`）是已知形状，但故意不作为回复文本发出：
 *   推理过程不是给用户的回答；丢弃它们不是“静默丢弃未知形状”，而是已知的、
 *   有文档记载的设计选择（见 [KNOWN_IGNORED_EVENTS]）。
 * - 只有服务器真实发来的文本才会变成 [ResponsesStreamEvent.TextDelta]；
 *   未收到真实 delta 时绝不产出文本。
 *
 * 终结语义：
 * - 收到 `response.completed` / `response.incomplete` / `response.failed` /
 *   流级 `error` 后解析器进入终结态，后续行不再产出事件。
 * - `[DONE]`：官方流式说明与多个官方格式复述均称 Responses 流**没有** `[DONE]`
 *   哨兵，以终结事件为准；但个别兼容实现会在终结事件后补一个 `data: [DONE]`。
 *   本解析器接受它（不报错、不产出事件），但绝不把它当作终结的必要条件。
 *   该点标记为 UNVERIFIED（见文件尾的 UNVERIFIED 清单）。
 */
class ResponsesStreamParser(
    private val onMalformedLine: (String) -> Unit = {},
) {

    /** 已组装的回复全文（仅由真实 delta / output_text.done 的真实文本累积）。 */
    val accumulatedText: String
        get() = textBuffer.toString()

    /** 是否已收到终结事件（completed / incomplete / failed / error）。 */
    var sawTerminal: Boolean = false
        private set

    /** 是否在流中见过 `data: [DONE]`（仅作记录，不作为终结条件）。 */
    var sawDoneSentinel: Boolean = false
        private set

    private val textBuffer = StringBuilder()

    /** 每个输出项已累积的工具参数（按 itemId）。 */
    private val toolArgsBuffer = HashMap<String, StringBuilder>()

    /** 每个输出项的工具调用信息（由 output_item.added 登记）。 */
    private val toolCallInfo = HashMap<String, ToolCallInfo>()

    /** 已发出过 ToolCallDone 的 itemId（去重：arguments.done 与 output_item.done 可能重复）。 */
    private val toolCallDoneEmitted = HashSet<String>()

    /** 每个内容部分已收到的文本量（用于 output_text.done 的去重判断）。 */
    private val textLengthByPart = HashMap<String, Int>()

    private var pendingEventType: String? = null
    private var pendingData: StringBuilder? = null

    private data class ToolCallInfo(
        val callId: String?,
        val name: String?,
    )

    /**
     * 喂入 SSE 流的一行（不含换行符）。返回该行产出的事件（通常 0 或 1 个）。
     * 空行表示一个 SSE 事件结束，触发分发。
     */
    fun feedLine(line: String): List<ResponsesStreamEvent> {
        if (sawTerminal) return emptyList()
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return dispatchPending()
        if (trimmed.startsWith(":")) return emptyList() // SSE 注释 / 心跳，忽略。
        if (trimmed.startsWith("event:")) {
            // 新事件开始：若上一事件的数据已缓冲但未分发（缺少空行分隔），先分发它。
            val flushed = if (pendingData != null) dispatchPending() else emptyList()
            pendingEventType = trimmed.removePrefix("event:").trim().ifEmpty { null }
            return flushed
        }
        if (trimmed.startsWith("data:")) {
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]") {
                // [DONE] 绝不可能是上一事件 JSON 的续行：若有挂起事件未分发
                //（上游省略了事件间的空行），先分发它，再记哨兵。
                val flushed = if (pendingData != null) dispatchPending() else emptyList()
                sawDoneSentinel = true
                return flushed
            }
            val buf = pendingData ?: StringBuilder().also { pendingData = it }
            if (buf.isNotEmpty()) buf.append('\n')
            buf.append(payload)
            return emptyList()
        }
        // 既不是 event: 也不是 data: 也不是注释：未知行，走 malformed，绝不猜测。
        onMalformedLine(line)
        return emptyList()
    }

    /**
     * 流结束时调用（EOF / 连接关闭）。若至今未收到终结事件，返回一个 Failed，
     * 调用方据此判定“流被截断”——绝不把已收到的 partial text 当作成功。
     * 若已终结，返回空列表。
     */
    fun finish(): List<ResponsesStreamEvent> {
        val flushed = dispatchPending()
        if (sawTerminal) return flushed
        val failure = ResponsesStreamEvent.Failed(
            code = "stream_ended_without_terminal",
            message = "Responses 流在未收到 response.completed / response.failed / " +
                "response.incomplete 前结束（可能为网络中断或代理截断），已收到的文本不作成功回复。",
            retryable = true,
        )
        return flushed + failure
    }

    /** 便捷方法：解析整段 SSE 文本（含 finish 的截断判定）。 */
    companion object {
        fun parseSseText(
            text: String,
            onMalformedLine: (String) -> Unit = {},
        ): List<ResponsesStreamEvent> {
            val parser = ResponsesStreamParser(onMalformedLine)
            val events = ArrayList<ResponsesStreamEvent>()
            for (line in text.lineSequence()) {
                events += parser.feedLine(line)
            }
            events += parser.finish()
            return events
        }

        /**
         * 已知但故意不作为回复事件发出的事件类型。
         * - 生命周期 / 结构事件：仅用于跟踪状态，不携带用户可见内容。
         * - 推理事件：推理文本不是回答，不向用户展示。
         * - web_search / code_interpreter 等服务端工具事件：SIWC Preview 不支持这些
         *   托管工具（官方 Preview 限制页），出现即视为已知但不处理；若将来支持，
         *   应在此处改为显式事件，而不是静默扩展。
         */
        val KNOWN_IGNORED_EVENTS: Set<String> = setOf(
            "response.created",
            "response.in_progress",
            "response.output_item.added",
            "response.content_part.added",
            "response.content_part.done",
            "response.reasoning_summary_part.added",
            "response.reasoning_summary_part.done",
            "response.reasoning_summary_text.delta",
            "response.reasoning_summary_text.done",
            "response.reasoning_text.delta",
            "response.reasoning_text.done",
            "response.web_search_call.in_progress",
            "response.web_search_call.searching",
            "response.web_search_call.completed",
            "response.custom_tool_call_input.delta",
            "response.custom_tool_call_input.done",
        )
    }

    private fun dispatchPending(): List<ResponsesStreamEvent> {
        val data = pendingData?.toString()
        val eventType = pendingEventType
        pendingData = null
        pendingEventType = null
        if (data == null) return emptyList()
        if (data == "[DONE]") {
            sawDoneSentinel = true
            return emptyList()
        }
        val rawLine = "event: ${eventType ?: ""}\ndata: $data"
        val json = try {
            JSONObject(data)
        } catch (_: Exception) {
            onMalformedLine(rawLine)
            return emptyList()
        }
        val type = eventType ?: json.optString("type", "")
        if (type.isEmpty()) {
            onMalformedLine(rawLine)
            return emptyList()
        }
        return handleEvent(type, json, rawLine)
    }

    private fun handleEvent(
        type: String,
        json: JSONObject,
        rawLine: String,
    ): List<ResponsesStreamEvent> {
        when (type) {
            "response.output_text.delta" -> {
                if (!json.has("delta")) {
                    onMalformedLine(rawLine)
                    return emptyList()
                }
                val delta = json.optString("delta", "")
                val partKey = partKey(json)
                textBuffer.append(delta)
                textLengthByPart[partKey] = (textLengthByPart[partKey] ?: 0) + delta.length
                return listOf(
                    ResponsesStreamEvent.TextDelta(
                        text = delta,
                        itemId = json.optString("item_id", "").ifEmpty { null },
                        outputIndex = json.optInt("output_index").takeIf { json.has("output_index") },
                        contentIndex = json.optInt("content_index").takeIf { json.has("content_index") },
                    ),
                )
            }
            "response.output_text.done" -> {
                val text = json.optString("text", "")
                val partKey = partKey(json)
                val already = textLengthByPart[partKey] ?: 0
                // 只有当该内容部分此前未收到任何 delta、而 done 自带完整文本时，
                // 才把 done 的真实文本作为增量发出（服务器真实文本，不是伪造）。
                // 已收到 delta 的情况不再重复发出，避免 UI 文本翻倍。
                if (text.isNotEmpty() && already == 0) {
                    textBuffer.append(text)
                    textLengthByPart[partKey] = text.length
                    return listOf(
                        ResponsesStreamEvent.TextDelta(
                            text = text,
                            itemId = json.optString("item_id", "").ifEmpty { null },
                            outputIndex = json.optInt("output_index").takeIf { json.has("output_index") },
                            contentIndex = json.optInt("content_index").takeIf { json.has("content_index") },
                        ),
                    )
                }
                return emptyList()
            }
            "response.output_item.added" -> {
                val item = json.optJSONObject("item")
                if (item != null) {
                    val itemId = json.optString("item_id", "").ifEmpty { item.optString("id", "") }
                    if (item.optString("type", "") == "function_call" && itemId.isNotEmpty()) {
                        toolCallInfo[itemId] = ToolCallInfo(
                            // UNVERIFIED：流式 added 项里调用 id 的字段名。
                            // input 侧 function_call 项用 `call_id`（DeepSeek 官方格式复述有例证）；
                            // 此处优先 call_id，退回 id；若两者皆无，调用方仍可凭 itemId 关联。
                            callId = item.optString("call_id", "").ifEmpty {
                                item.optString("id", "").ifEmpty { null }
                            },
                            name = item.optString("name", "").ifEmpty { null },
                        )
                    }
                }
                return emptyList()
            }
            "response.output_item.done" -> {
                val item = json.optJSONObject("item") ?: return emptyList()
                if (item.optString("type", "") != "function_call") return emptyList()
                val itemId = json.optString("item_id", "").ifEmpty { item.optString("id", "") }
                if (itemId.isEmpty() || toolCallDoneEmitted.contains(itemId)) return emptyList()
                // 某些实现只在 output_item.done 里给出完整 arguments；
                // 若 arguments.done 已发过，此处不再重复。
                val arguments = item.optString("arguments", "")
                val buffered = toolArgsBuffer[itemId]?.toString() ?: ""
                val finalArgs = arguments.ifEmpty { buffered }
                if (finalArgs.isEmpty()) return emptyList()
                toolCallDoneEmitted += itemId
                val info = toolCallInfo[itemId]
                return listOf(
                    ResponsesStreamEvent.ToolCallDone(
                        itemId = itemId,
                        outputIndex = json.optInt("output_index").takeIf { json.has("output_index") },
                        name = info?.name ?: item.optString("name", "").ifEmpty { null },
                        arguments = finalArgs,
                        callId = info?.callId,
                    ),
                )
            }
            "response.function_call_arguments.delta" -> {
                if (!json.has("delta")) {
                    onMalformedLine(rawLine)
                    return emptyList()
                }
                val delta = json.optString("delta", "")
                val itemId = json.optString("item_id", "").ifEmpty { null }
                if (itemId != null) {
                    val buf = toolArgsBuffer.getOrPut(itemId) { StringBuilder() }
                    buf.append(delta)
                }
                return listOf(
                    ResponsesStreamEvent.ToolCallArgumentsDelta(
                        itemId = itemId,
                        outputIndex = json.optInt("output_index").takeIf { json.has("output_index") },
                        delta = delta,
                    ),
                )
            }
            "response.function_call_arguments.done" -> {
                if (!json.has("arguments")) {
                    onMalformedLine(rawLine)
                    return emptyList()
                }
                val arguments = json.optString("arguments", "")
                val itemId = json.optString("item_id", "").ifEmpty { null }
                if (itemId != null) toolCallDoneEmitted += itemId
                val info = itemId?.let { toolCallInfo[it] }
                return listOf(
                    ResponsesStreamEvent.ToolCallDone(
                        itemId = itemId,
                        outputIndex = json.optInt("output_index").takeIf { json.has("output_index") },
                        name = info?.name,
                        arguments = arguments,
                        callId = info?.callId,
                    ),
                )
            }
            "response.completed" -> {
                sawTerminal = true
                val response = json.optJSONObject("response")
                val usage = response?.optJSONObject("usage")?.let { parseUsage(it) }
                val fullText = response?.optString("output_text", "")
                    ?.ifEmpty { null }
                    ?: textBuffer.toString().ifEmpty { null }
                return listOf(
                    ResponsesStreamEvent.Completed(
                        usage = usage,
                        responseId = response?.optString("id", "")?.ifEmpty { null },
                        fullText = fullText,
                    ),
                )
            }
            "response.incomplete" -> {
                sawTerminal = true
                val response = json.optJSONObject("response")
                val details = response?.optJSONObject("incomplete_details")
                val reason = details?.optString("reason", "")?.ifEmpty { null }
                return listOf(
                    ResponsesStreamEvent.Failed(
                        code = "incomplete",
                        message = "回复未完成（incomplete）" +
                            (reason?.let { "，原因：$it" } ?: "") +
                            "；未完成内容不作成功回复。",
                        retryable = false,
                    ),
                )
            }
            "response.failed" -> {
                sawTerminal = true
                val response = json.optJSONObject("response")
                val error = response?.optJSONObject("error")
                val code = error?.optString("code", "")?.ifEmpty { null } ?: "response_failed"
                val message = error?.optString("message", "")?.ifEmpty { null }
                    ?: "Responses 请求失败（response.failed，未给出错误详情）。"
                // UNVERIFIED：官方未在可读到的参考页中逐条列出 response.failed 的
                // 可重试错误码；仅把 server_error 视为可重试（第三方官方格式复述
                // 称其为 transient），其余一律不可重试，宁可少重试、不错重试。
                return listOf(
                    ResponsesStreamEvent.Failed(
                        code = code,
                        message = message,
                        retryable = code == "server_error",
                    ),
                )
            }
            "error" -> {
                sawTerminal = true
                // 流级 error 两种写法都接受：{"code","message",...} 与 {"error":{...}}。
                val nested = json.optJSONObject("error")
                val code = json.optString("code", "").ifEmpty { nested?.optString("code", "") ?: "" }
                    .ifEmpty { "stream_error" }
                val message = json.optString("message", "").ifEmpty { nested?.optString("message", "") ?: "" }
                    .ifEmpty { "流式传输发生错误（error 事件，未给出详情）。" }
                return listOf(
                    ResponsesStreamEvent.Failed(
                        code = code,
                        message = message,
                        retryable = code == "server_error",
                    ),
                )
            }
            in KNOWN_IGNORED_EVENTS -> return emptyList()
            else -> {
                onMalformedLine(rawLine)
                return emptyList()
            }
        }
    }

    private fun partKey(json: JSONObject): String {
        val itemId = json.optString("item_id", "")
        val outputIndex = if (json.has("output_index")) json.optInt("output_index").toString() else ""
        val contentIndex = if (json.has("content_index")) json.optInt("content_index").toString() else ""
        return "$itemId:$outputIndex:$contentIndex"
    }

    private fun parseUsage(usage: JSONObject): ResponsesUsage {
        return ResponsesUsage(
            inputTokens = usage.optLong("input_tokens").takeIf { usage.has("input_tokens") },
            outputTokens = usage.optLong("output_tokens").takeIf { usage.has("output_tokens") },
            totalTokens = usage.optLong("total_tokens").takeIf { usage.has("total_tokens") },
        )
    }
}

/** Token 用量：只取服务器真实返回的数字，绝不估算。 */
data class ResponsesUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    val totalTokens: Long?,
)

/**
 * Responses 流的归一化事件（本层自有类型）。
 *
 * 到 M2 [BrainEvent] 的映射（由桥接层 SiwcM2BrainAdapter 实现，本文件不依赖 M2 树，
 * 故只以注释约定映射关系，不直接 import）：
 * - TextDelta -> BrainEvent.TextDelta(text)
 * - ToolCallArgumentsDelta -> 桥接层累积，待 ToolCallDone 后一次性发 BrainEvent.ToolCall
 * - ToolCallDone -> BrainEvent.ToolCall(id = callId ?: itemId ?: 生成值, name, args = 解析 arguments JSON 字符串)
 * - Completed -> BrainEvent.Done("stop") + BrainEvent.Usage(inputTokens, outputTokens)
 * - Failed -> BrainEvent.Error(code, message, retryable)
 * 关键不变量：Failed 出现时，已发出的 TextDelta 只是“部分文本”，桥接层必须丢弃
 * 部分文本、不把它当作成功回复展示。
 */
sealed class ResponsesStreamEvent {
    /** 一段真实的助手文本增量（仅来自服务器真实 delta / output_text.done 的真实文本）。 */
    data class TextDelta(
        val text: String,
        val itemId: String?,
        val outputIndex: Int?,
        val contentIndex: Int?,
    ) : ResponsesStreamEvent()

    /** 一段工具调用参数增量（JSON 字符串片段，需拼接）。 */
    data class ToolCallArgumentsDelta(
        val itemId: String?,
        val outputIndex: Int?,
        val delta: String,
    ) : ResponsesStreamEvent()

    /** 一次工具调用完成：arguments 为完整 JSON 字符串（调用方负责校验 JSON 合法性）。 */
    data class ToolCallDone(
        val itemId: String?,
        val outputIndex: Int?,
        val name: String?,
        val arguments: String,
        val callId: String?,
    ) : ResponsesStreamEvent()

    /** 回复完成：usage 仅为服务器返回的真实数字；fullText 为已组装的真实全文。 */
    data class Completed(
        val usage: ResponsesUsage?,
        val responseId: String?,
        val fullText: String?,
    ) : ResponsesStreamEvent()

    /** 回复失败：调用方必须丢弃已收到的 partial text，不把它当成功。 */
    data class Failed(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : ResponsesStreamEvent()
}

/*
 * UNVERIFIED 清单（文档未明确、代码中已按保守策略处理）：
 * 1. `data: [DONE]` 是否会出现：官方流式说明与 DeepSeek/Tako 的官方格式复述称
 *    Responses 流没有 [DONE] 哨兵，以终结事件为准；另一家兼容实现（melious）称终结
 *    事件后还有 [DONE]。本解析器两者都接受，但终结只认终结事件。
 * 2. `response.output_item.added` 里 function_call 项的调用 id 字段名（call_id vs id）：
 *    input 侧有例证用 call_id，流式 added 项未在可读到的官方页中直接核实；
 *    本解析器优先 call_id、退回 id。
 * 3. `response.failed` 的可重试错误码全表：官方未在可读到的参考页逐条列出；
 *    仅 server_error 视为可重试，其余不可重试。
 * 4. `response.incomplete` 是否携带 usage：DeepSeek 的官方格式复述称其携带完整
 *    response 对象（含 usage），但本解析器不依赖它（incomplete 直接按失败处理）。
 * 5. 流级 `error` 事件的 JSON 字段（平铺 code/message 还是嵌套 error 对象）：
 *    第三方官方格式复述称含 code 字段；本解析器两种写法都接受。
 * 6. SIWC 路由下工具的命名空间分组形状：官方 Preview 限制页只说“把 function /
 *    custom 工具分组进命名空间或经 additional_tools 输入项提供”，未给出具体
 *    JSON 形状；本层工具发送见 ResponsesApiClient 的注释，不编造 namespace 字段。
 */
