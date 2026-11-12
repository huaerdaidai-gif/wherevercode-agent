package wherevercode.agent.brain.cloudflare

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.brain.siwc.BrainError

/**
 * Cloudflare Workers AI 客户端（CloudflareApiClient）。
 *
 * 只做一件事：用调用方传入的、用户自己的 Cloudflare API Token，
 * 向官方 OpenAI 兼容端点发一次流式推理请求，并把 SSE 行交给
 * [CloudflareStreamParser]。
 *
 * 端点选择（P1-2，2026-11-09 查官方文档后定；依据见实施说明）：
 * - 用 `POST https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1/chat/completions`，
 *   `Authorization: Bearer <token>`（官方 OpenAI 兼容端点页，
 *   https://developers.cloudflare.com/workers-ai/configuration/open-ai-compatibility/：
 *   “Most Workers AI text generation models support the OpenAI Chat
 *   Completions API”，示例模型正是 `@cf/meta/llama-3.1-8b-instruct`）。
 * - 不用原生 `/ai/run`：原生输入消息是 RoleScopedChatInput，只有
 *   role/content/name，没有 tool_call_id / tool_calls 字段；传统函数
 *   调用页（https://developers.cloudflare.com/workers-ai/features/function-calling/traditional/）
 *   的响应 tool_calls 是无 id 的 `[{"name", "arguments"}]`。原生路
 *   在协议层无法配对工具调用——这正是 K70 上“模型像没看到结果一样
 *   重复调用”的候选根因之一（现象级一致，未证实因果）。
 * - 请求体与响应流均为标准 OpenAI chat completions 形状，与
 *   DeepSeek 路径同形：assistant 发 `tool_calls[]`（每项含 id），
 *   tool 消息发 `tool_call_id`；`tools` 为嵌套
 *   `[{"type": "function", "function": {...}}]`；流式 SSE 为
 *   `data: {"choices": [{"delta": {...}, "finish_reason": ...}]}`，
 *   以 `data: [DONE]` 终结。
 * - 同步错误体为 OpenAI 形状 `{"error": {"message", "code", "type"}}`；
 *   兼容层也可能沿用 Cloudflare 信封
 *   `{"success": false, "errors": [{"code", "message"}]}`，两种都解析。
 * - 免费额度：每天 10,000 Neurons，全账户共享；Free 计划超额只失败、
 *   不产生账单（定价页，2026-10-20 版）。
 * - 超额：官方错误码页（Updated 2026-11-06）称内部码 `3036`、HTTP 429、
 *   "You have used up your daily free allocation of 10,000 neurons…"。
 *   另有第三方实测称错误码为 `4006` 且封禁可能持续到首次使用后 24 小时，
 *   与官方“00:00 UTC 重置”冲突——本层把 3036 与 4006 都按超额处理，
 *   提示写“按官方说法每日 00:00 UTC 重置，个别情况下可能持续更久”，
 *   绝不写“00:00 UTC 一定恢复”，也绝不高频重试。
 * - 模型需 Workers Paid 计划：内部码 `5035`、HTTP 403、
 *   "This model requires a Workers Paid plan."（官方错误码页）。
 * - 容量不足：内部码 `3040`、HTTP 429，可重试（官方错误码页）。
 * - Token 权限：官方只明说 Account > Workers AI > Read；是否还需 Edit
 *   官方未明说（核查报告 §7 第 5 项），须实测；403 非 5035 时本层按
 *   授权失败提示检查 Token 权限。
 * - temperature：原生模型页称范围 0–5；OpenAI 兼容端点未在官方文档
 *   中另行说明范围，本层仍按 0–5 校验（不静默改写），服务器是最终
 *   裁判，400 即报 Unsupported。待 K70 实测确认。
 *
 * 红线（本层强制执行）：
 * - token 与 account_id 由调用方传入；本层绝不存储、不读 Keystore、
 *   不记日志、不把它们写进任何异常信息或请求体之外的任何地方。
 * - 只调 OpenAI 兼容 `/ai/v1/chat/completions` 这一条路；绝不走 AI Gateway（那需要
 *   `cf-aig-gateway-id` 头且默认记录 prompt 日志），绝不调任何付费端点。
 * - 单用户 BYO：token 只属于配置它的那个用户，只服务该用户；绝不把
 *   一个账户的额度共享给多个用户，绝不转售（自助协议 §2.2.1(a)）。
 * - 未收到真实 delta 时绝不产出文本；失败时不把 partial text 当成功。
 *
 * 取消：调用方取消 Flow 收集时，读取循环通过 `isActive` 检查退出，
 * `finally` 关闭 reader 与连接；不再有后台线程持有连接。
 */
class CloudflareApiClient(
    private val transport: CloudflareTransport = HttpsCloudflareTransport(),
    private val onMalformedLine: (String) -> Unit = {},
) {

    /**
     * 发起一次流式 Workers AI 请求。
     *
     * 错误语义（调用方据此向用户提示，绝不把失败包装成假回复）：
     * - 请求发出前的问题（空 token / 空 account_id / 空模型 / 不支持的字段）：
     *   直接抛 [BrainError]（NotConfigured / Unsupported），不产生任何事件。
     * - HTTP 非 200：抛 [BrainError]（401 AuthFailed / 403-5035 Unsupported /
     *   403-其他 AuthFailed / 429-3036 或 4006 QuotaExceeded /
     *   429-3040 Transport / 429-其他（无额度错误码）Transport——按限速处理 /
     *   400 Unsupported / 5xx Transport），不产生任何事件。
     * - 流中途的失败（[CloudflareStreamEvent.Failed]）：以 Failed 事件发出、
     *   流正常结束；调用方必须丢弃已收到的 partial text。
     * - 流在未收到 `[DONE]` 前断开（EOF / 网络中断）：
     *   若此前未发出过任何事件，抛 [BrainError.Transport]；
     *   若已发出过事件，以 [CloudflareStreamEvent.Failed]（code =
     *   `stream_ended_without_terminal`，retryable = true）收尾。
     */
    fun streamResponse(
        request: CloudflareApiRequest,
        accountId: String,
        apiToken: String,
        baseUrl: String,
    ): Flow<CloudflareStreamEvent> = flow {
        if (apiToken.isBlank()) {
            throw BrainError.NotConfigured(
                "未提供 Cloudflare API Token：请用户先在控制台的 Cloudflare 设置里" +
                    "填写自己的 API Token，本层不持有、不生成 token。",
            )
        }
        if (accountId.isBlank()) {
            throw BrainError.NotConfigured(
                "未提供 Cloudflare Account ID：请用户先在控制台的 Cloudflare 设置里" +
                    "填写自己的 Account ID。",
            )
        }
        if (baseUrl.isBlank()) {
            throw BrainError.NotConfigured(
                "未提供 Cloudflare 服务地址：请检查 Cloudflare 大脑配置的 baseUrl。",
            )
        }
        // 先在本地强制请求限制：不支持的字段在这里就拒绝，绝不发到服务器。
        val requestBody = buildCloudflareRequestBody(request)
        val url = buildChatCompletionsUrl(baseUrl, accountId)
        val connection = try {
            transport.open(url, apiToken, requestBody.toString())
        } catch (e: IOException) {
            throw BrainError.Transport("连接 Cloudflare Workers AI 失败（网络异常）：${e.message}", e)
        }
        try {
            if (connection.statusCode != 200) {
                throw mapHttpError(connection, request.model)
            }
            val parser = CloudflareStreamParser(onMalformedLine)
            var emittedAny = false
            val reader = connection.openBodyReader()
            try {
                while (currentCoroutineContext().isActive) {
                    val line = try {
                        reader.readLine()
                    } catch (e: IOException) {
                        throw BrainError.Transport("读取 Cloudflare 流失败（网络异常）：${e.message}", e)
                    } ?: break
                    for (event in parser.feedLine(line)) {
                        emittedAny = true
                        emit(event)
                    }
                    if (parser.sawTerminal) break
                }
            } finally {
                try {
                    reader.close()
                } catch (_: IOException) {
                    // 关闭时的异常不掩盖主流程结果。
                }
            }
            for (event in parser.finish()) {
                if (event is CloudflareStreamEvent.Failed && !parser.sawTerminal && !emittedAny) {
                    // 合成的断流失败（finish() 因未见 [DONE] 而生成），且此前未收到
                    // 任何真实内容：抛 Transport，而不是发一个孤零零的 Failed 事件
                    // 让调用方误以为“收到过回复”。已发出过事件的 Failed 必须原样
                    // 发出，不走这条分支。
                    throw BrainError.Transport(event.message)
                }
                emittedAny = true
                emit(event)
            }
        } finally {
            try {
                connection.close()
            } catch (_: Exception) {
                // 关闭时的异常不掩盖主流程结果。
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * HTTP 状态码 -> [BrainError]。
     *
     * 官方错误码页（https://developers.cloudflare.com/workers-ai/platform/errors/，
     * Updated 2026-11-06）：
     * - 3036 / 429：每日免费额度用完（Account limited）。
     * - 5035 / 403：模型需要 Workers Paid 计划。
     * - 3040 / 429：容量不足（可重试）。
     * 第三方兼容：个别实测报告超额时错误码为 4006（与官方 3036 对不上，
     * 见核查报告 §1.4 / §7 第 2 项）；本层把 4006 与 3036 同等处理，
     * 提示文案写清重置时间的不确定性，绝不写“00:00 UTC 一定恢复”。
     */
    internal fun mapHttpError(connection: CloudflareConnection, model: String): BrainError {
        val status = connection.statusCode
        val (serverCode, serverMessage) = parseErrorBody(connection.readErrorBody())
        val detail = buildString {
            if (serverCode != null) append("（服务器错误码：$serverCode）")
            if (serverMessage != null) append(" $serverMessage")
        }
        val isQuotaCode = serverCode == "3036" || serverCode == "4006" ||
            (serverMessage?.contains("daily free allocation") == true)
        return when (status) {
            401 -> BrainError.AuthFailed(
                "Cloudflare 授权失败（401）：API Token 无效或已过期，" +
                    "请检查控制台 Cloudflare 设置里的 Token。$detail",
            )
            403 -> if (serverCode == "5035") {
                BrainError.Unsupported(
                    "模型 $model 需要 Workers Paid 计划，免费层不可用（403 / 5035）。" +
                        "请在设置里换一个免费层模型（例如 " +
                        CloudflareBrainAdapter.SUGGESTED_DEFAULT_MODEL + "）。$detail",
                )
            } else {
                BrainError.AuthFailed(
                    "Cloudflare 请求被拒绝（403）：可能是 Token 缺少 Workers AI 权限，" +
                        "请检查 Token 是否有 Account > Workers AI > Read 权限，" +
                        "并确认 Account ID 与 Token 属于同一账号。$detail",
                )
            }
            429 -> when {
                isQuotaCode -> BrainError.QuotaExceeded(
                    "Cloudflare 今日免费额度已用完（每天 10,000 Neurons，全账户共享）。" +
                        "按官方说法每日 00:00 UTC 重置，但有实测称个别情况下可能持续更久" +
                        "（最长到首次使用后 24 小时）；本轮不再重试。$detail",
                )
                serverCode == "3040" -> BrainError.Transport(
                    "Cloudflare 容量不足（429 / 3040），可重试。$detail",
                )
                // 无额度错误码的 429：官方错误码页把超额与容量不足都给了
                // 明确的内部码（3036 / 3040）；不带码的 429 最可能是每分钟
                // 请求数超限（文本生成默认 300 请求/分钟/账户），按可重试
                // 的限速处理，不按“额度用完”报（否则会误导用户以为当天
                // 不能再用，并错误触发 SIWC 回落）。
                else -> BrainError.Transport(
                    "Cloudflare 请求速率受限（429，未带额度错误码）：可能是每分钟" +
                        "请求数超限，可重试。$detail",
                )
            }
            400 -> BrainError.Unsupported(
                "请求被 Cloudflare 拒绝（400）：$detail",
            )
            404 -> BrainError.Unsupported(
                "Cloudflare 找不到该模型（404）：请在设置里检查模型 ID 是否正确，" +
                    "例如 " + CloudflareBrainAdapter.SUGGESTED_DEFAULT_MODEL + "。$detail",
            )
            500, 502, 503, 504,
            520, 521, 522, 523, 524, 525, 526, 527,
            -> BrainError.Transport(
                "Cloudflare 服务暂不可用（HTTP $status），可重试。$detail",
            )
            else -> BrainError.Transport(
                "Cloudflare 请求失败（HTTP $status），可重试。$detail",
            )
        }
    }

    /**
     * 解析错误体。兼容两种形状：
     * - OpenAI 形状 `{"error": {"code", "message"}}`（兼容端点同步错误）；
     * - Cloudflare 信封 `{"success": false, "errors": [{"code": 3036, "message": "…"}]}`。
     * code 可能是整数也可能是字符串；message 缺失时返回 null。
     */
    internal fun parseErrorBody(errorBody: String?): Pair<String?, String?> {
        if (errorBody.isNullOrBlank()) return null to null
        return try {
            val root = JSONObject(errorBody)
            root.optJSONObject("error")?.let { error ->
                val code = when (val raw = error.opt("code")) {
                    is Number -> raw.toString()
                    is String -> raw.ifEmpty { null }
                    else -> null
                }
                val message = error.optString("message", "").ifEmpty { null }
                return code to message
            }
            val errors = root.optJSONArray("errors") ?: return null to null
            if (errors.length() == 0) return null to null
            val first = errors.optJSONObject(0) ?: return null to null
            val code = when (val raw = first.opt("code")) {
                is Number -> raw.toString()
                is String -> raw.ifEmpty { null }
                else -> null
            }
            val message = first.optString("message", "").ifEmpty { null }
            code to message
        } catch (_: Exception) {
            null to null
        }
    }

    companion object {
        /** 官方 OpenAI 兼容端点的账号级前缀（chat completions 路径拼在后面）。 */
        const val ACCOUNTS_BASE_URL: String = "https://api.cloudflare.com/client/v4/accounts"

        /**
         * 拼出一次推理的完整 URL：`{baseUrl}/{accountId}/ai/v1/chat/completions`。
         * baseUrl 尾部斜杠会被去掉；模型 ID 不在 URL 里，改在请求体的
         * `model` 字段（OpenAI chat completions 形状）。
         */
        fun buildChatCompletionsUrl(baseUrl: String, accountId: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return "$base/$accountId/ai/v1/chat/completions"
        }
    }
}

/**
 * 构建 Workers AI OpenAI 兼容端点的请求体（stream: true 固定，本客户端只做流式）。
 *
 * 形状（官方 OpenAI 兼容端点页 + 标准 chat completions 格式，与 DeepSeek
 * 路径同形，见 DeepSeekApiClient.buildDeepSeekRequestBody）：
 * - 顶层：`model`（配置的完整模型 ID）、`stream: true`、
 *   `stream_options: {"include_usage": true}`（流式 usage 须显式索取；
 *   兼容层按文档会保留未知字段，若服务端不支持该字段应忽略——待 K70
 *   实测确认 usage 是否返回）。
 * - `messages` 数组：`instructions`（BrainRequest 的系统指引）发为第一条
 *   system 消息；BrainRole.DEVELOPER 同样发为 system 消息（Workers AI
 *   没有 developer 角色）。
 * - assistant 消息：有 toolCalls 时发 `tool_calls` 数组（每项
 *   `{"id", "type": "function", "function": {"name", "arguments"}}`，
 *   arguments 为 JSON 字符串），content 置 null；无调用时只发 content。
 *   一轮 N 个调用 = 一条 assistant 消息带 N 个 tool_calls，后跟 N 条
 *   tool 消息——绝不拆成 N 组 assistant/tool 对（P1-2）。
 * - tool 消息：发 `{"role": "tool", "tool_call_id", "content"}`。无 id
 *   的旧持久化 tool 消息无法配对，降级为一条用户可见的结果说明
 *   （`"[工具结果]\n..."`），绝不发无 tool_call_id 的 role="tool"
 *   消息（兼容端点会整单拒绝，与 DeepSeek 的处理一致）。
 * - `tools` 为嵌套数组 `[{"type": "function", "function": {"name",
 *   "description", "parameters"}}]`，并带 `tool_choice: "auto"`。
 * - `temperature` 范围 0–5（原生模型页口径；兼容端点未另行说明，
 *   服务器是最终裁判）、`max_tokens` 整数 ≥ 1；超出范围直接拒绝，
 *   绝不静默截断或改写。
 * - 不传 `max_tokens` 时用服务器默认值（llama 模型页称默认 256）——
 *   输出长度默认即受限，这也是省额度的一道天然上限（输出 token 比
 *   输入贵约 8 倍，见核查报告 §2.4）。
 */
internal fun buildCloudflareRequestBody(request: CloudflareApiRequest): JSONObject {
    if (request.model.isBlank()) {
        throw BrainError.Unsupported("model 不能为空")
    }
    request.temperature?.let {
        if (it < 0.0 || it > 5.0) {
            throw BrainError.Unsupported(
                "Cloudflare Workers AI 的 temperature 范围是 0–5，调用方请求了 temperature=$it，" +
                    "已拒绝，未静默改写。",
            )
        }
    }
    request.maxOutputTokens?.let {
        if (it < 1) {
            throw BrainError.Unsupported(
                "Cloudflare Workers AI 的 max_tokens 必须 ≥ 1，调用方请求了 max_tokens=$it，" +
                    "已拒绝，未静默改写。",
            )
        }
    }
    if (request.messages.isEmpty() && request.instructions.isNullOrBlank()) {
        throw BrainError.Unsupported("input 与 instructions 不能同时为空")
    }
    val body = JSONObject()
    body.put("model", request.model)
    body.put("stream", true)
    body.put(
        "stream_options",
        JSONObject().put("include_usage", true),
    )
    request.temperature?.let { body.put("temperature", it) }
    request.maxOutputTokens?.let { body.put("max_tokens", it) }
    val messages = JSONArray()
    request.instructions?.takeIf { it.isNotBlank() }?.let {
        messages.put(JSONObject().put("role", "system").put("content", it))
    }
    for (message in request.messages) {
        when (message.role) {
            "user" -> messages.put(
                JSONObject().put("role", "user").put("content", message.content),
            )
            "assistant" -> {
                val obj = JSONObject().put("role", "assistant")
                if (message.toolCalls.isNotEmpty()) {
                    obj.put("content", JSONObject.NULL)
                    val calls = JSONArray()
                    for (call in message.toolCalls) {
                        calls.put(
                            JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", call.name)
                                        .put("arguments", call.argumentsJson.ifBlank { "{}" }),
                                ),
                        )
                    }
                    obj.put("tool_calls", calls)
                } else {
                    obj.put("content", message.content)
                }
                messages.put(obj)
            }
            // P1-2 配对：tool 结果带本次调用的 tool_call_id；无 id 的旧
            // 记录降级为用户可见的结果说明，绝不发无 id 的 role="tool"。
            "tool" -> {
                if (message.toolCallId.isNullOrBlank()) {
                    messages.put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "[工具结果]\n" + message.content),
                    )
                } else {
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", message.toolCallId)
                            .put("content", message.content),
                    )
                }
            }
            // BrainRequest 没有 SYSTEM 角色；DEVELOPER 是系统级指引，
            // Workers AI 没有 developer 角色，发为 system。
            "developer" -> messages.put(
                JSONObject().put("role", "system").put("content", message.content),
            )
            "system" -> throw BrainError.Unsupported(
                "Cloudflare 适配器内部错误：system 角色应走 instructions，不要直接进 messages。",
            )
            else -> throw BrainError.Unsupported("不支持的消息角色：${message.role}")
        }
    }
    body.put("messages", messages)
    if (request.tools.isNotEmpty()) {
        val tools = JSONArray()
        for (tool in request.tools) {
            val parameters = try {
                JSONObject(tool.parametersJson)
            } catch (e: Exception) {
                throw BrainError.Unsupported(
                    "工具 ${tool.name} 的 parametersJson 不是合法 JSON 对象：${e.message}",
                )
            }
            tools.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", parameters),
                    ),
            )
        }
        body.put("tools", tools)
        body.put("tool_choice", "auto")
    }
    return body
}

/** 一次 Cloudflare 推理请求（调用方组装；token / account_id 不在这里）。 */
data class CloudflareApiRequest(
    /** 模型完整 ID，如 `@cf/meta/llama-3.1-8b-instruct-fp8-fast`（必须带 -fp8-fast 后缀）。 */
    val model: String,
    /** 全量对话历史（Workers AI 是无状态 REST，每次带全量）。 */
    val messages: List<CloudflareInputMessage>,
    /** 系统级指引：发为第一条 system 消息。 */
    val instructions: String? = null,
    /** function 工具（OpenAI 嵌套形状，见 buildCloudflareRequestBody）。 */
    val tools: List<CloudflareTool> = emptyList(),
    /** temperature：0–5，null 时不传（用服务器默认 0.6）。 */
    val temperature: Double? = null,
    /** max_output_tokens：映射为 max_tokens，null 时不传（用服务器默认）。 */
    val maxOutputTokens: Int? = null,
)

/** 一个 function 工具（OpenAI 嵌套形状，见 buildCloudflareRequestBody）；parametersJson 必须是 JSON 对象字符串。 */
data class CloudflareTool(
    val name: String,
    val description: String,
    val parametersJson: String,
)

/** 一条输入消息。role 只允许 user / assistant / developer / tool（developer 发为 system，tool 带 tool_call_id 发出）。 */
data class CloudflareInputMessage(
    val role: String,
    val content: String,
    /** tool 角色消息回答的调用 id；assistant 以外的消息恒为 null。无 id 的旧 tool 消息由构造器降级处理。 */
    val toolCallId: String? = null,
    /** assistant 消息携带的工具调用（线序）；非 assistant 消息恒为空。 */
    val toolCalls: List<CloudflareToolCall> = emptyList(),
)

/** assistant 消息携带的一次工具调用（OpenAI wire 形状）。 */
data class CloudflareToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * 可注入的 HTTP 传输层：生产用 [HttpsCloudflareTransport]，单测用 fake 实现。
 * token 只经过 [open] 的参数，不由本层存储；实现者同样不得存储或记录它。
 */
interface CloudflareTransport {
    /** 打开 POST 连接。网络失败时抛 [IOException]（由调用方译为 Transport）。 */
    @Throws(IOException::class)
    fun open(url: String, apiToken: String, requestBody: String): CloudflareConnection
}

/** 一条已打开的 HTTP 连接（调用方负责 [close]）。 */
interface CloudflareConnection : Closeable {
    val statusCode: Int

    /** 200 时的响应体读取器（SSE 文本，UTF-8）。 */
    fun openBodyReader(): BufferedReader

    /** 非 200 时的错误体文本；读不到时返回 null。 */
    fun readErrorBody(): String?
}

/**
 * 基于 [HttpsURLConnection] 的默认传输实现。不引入 OkHttp 等新依赖（M2 已有约定）。
 * 超时为本地选择，未在官方文档中核实：连接 30 秒，单次读取 120 秒
 * （SSE 是长连接，读取超时按“两次事件之间的最大静默”理解，不按整流总时长）。
 */
class HttpsCloudflareTransport(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 120_000,
) : CloudflareTransport {

    override fun open(url: String, apiToken: String, requestBody: String): CloudflareConnection {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        // token 只放进 Authorization 头；本类不存储、不记录、不写进异常。
        connection.setRequestProperty("Authorization", "Bearer $apiToken")
        connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        return HttpsCloudflareConnection(connection)
    }
}

private class HttpsCloudflareConnection(
    private val connection: HttpsURLConnection,
) : CloudflareConnection {

    override val statusCode: Int
        get() = try {
            connection.responseCode
        } catch (_: IOException) {
            -1
        }

    override fun openBodyReader(): BufferedReader =
        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))

    override fun readErrorBody(): String? = try {
        connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    override fun close() {
        try {
            connection.disconnect()
        } catch (_: Exception) {
            // 关闭时的异常不掩盖主流程结果。
        }
    }
}
