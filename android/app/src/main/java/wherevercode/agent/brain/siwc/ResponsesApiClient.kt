package wherevercode.agent.brain.siwc

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

/**
 * Responses API 客户端（ResponsesApiClient）。
 *
 * 只做一件事：用调用方传入的、用户经 SIWC 正规授权的 OAuth token，
 * 向官方 Responses API 发一次流式请求，并把 SSE 行交给 [ResponsesStreamParser]。
 *
 * 已核实的官方事实（2026-11-08 核实）：
 * - 端点 `POST https://api.openai.com/v1/responses`，`Authorization: Bearer <token>`
 *   （SIWC Cookbook：SDK“用该 profile 的 OAuth access token 向公开 Responses API
 *   发请求”，https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt ；
 *   官方 API 参考总览：Bearer 认证，
 *   https://platform.openai.com/docs/api-reference/responses-streaming.md）。
 * - SIWC Preview 请求要求（官方，
 *   https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations）：
 *   `store: false`、`stream: true`、`input` 为数组且每次带全量上下文；
 *   用 `instructions` 或 developer 消息承载系统指引，显式
 *   `{type: "message", role: "system"}` 会被拒绝；省略 `previous_response_id`；
 *   不得带 `background` / `conversation` / `max_output_tokens` / `max_tool_calls` /
 *   `metadata` / `moderation` / `multi_agent` / `prompt` / `prompt_cache_retention` /
 *   `safety_identifier` / `temperature` / `top_logprobs` / `top_p` / `truncation` /
 *   `user`。
 * - 资格背景：SIWC 计划用量在发布时仅开放给开源项目、本地运行的个人项目与
 *   入选的私有应用（SIWC Cookbook）；本项目为私有仓库，资格仍不明确。
 *   因此本客户端只做本地实现，不做真实 E2E、不调套餐接口、不用真实账户凭据。
 *
 * 红线（本层强制执行）：
 * - token 由调用方传入；本层绝不存储 token、不读 Keystore、不记日志、不把 token
 *   写进任何异常信息或请求体之外的任何地方。
 * - 绝不使用 API Key、绝不使用额外付费 API；本层只调 Responses API 这一条路。
 * - 调用方请求了 Preview 不支持的字段（temperature / max_output_tokens），
 *   必须抛 [ResponsesApiError.Unsupported]，绝不静默忽略、绝不偷偷降级。
 * - system 角色消息必须抛 Unsupported（请调用方改放 instructions），绝不偷偷
 *   把它改写成 developer 消息。
 * - 未收到真实 delta 时绝不产出文本；失败时不把 partial text 当成功
 *   （见 [streamResponse] 的终结处理）。
 *
 * 取消：调用方取消 Flow 收集时，读取循环通过 `isActive` 检查退出，
 * `finally` 关闭 reader 与连接；不再有后台线程持有连接。
 */
class ResponsesApiClient(
    private val transport: ResponsesTransport = HttpsResponsesTransport(),
    private val baseUrl: String = RESPONSES_URL,
    private val onMalformedLine: (String) -> Unit = {},
) {

    /**
     * 发起一次流式 Responses 请求。
     *
     * 错误语义（调用方据此向用户提示，绝不把失败包装成假回复）：
     * - 请求发出前的问题（空 token、不支持的字段、不合法的工具定义）：
     *   直接抛 [ResponsesApiError]（NotConfigured / Unsupported），不产生任何事件。
     * - HTTP 非 200（连接已建立、服务器已拒绝）：抛 [ResponsesApiError]
     *   （401 AuthFailed / 403 QuotaExceeded / 429 QuotaExceeded /
     *   400 Unsupported / 5xx 与 Cloudflare 5xx Transport），不产生任何事件。
     * - 流中途的失败事件（response.failed / response.incomplete / error 事件）：
     *   以 [ResponsesStreamEvent.Failed] 事件发出、流正常结束；调用方必须丢弃
     *   已收到的 partial text。之所以不抛异常，是因为此时部分 TextDelta 已发出，
     *   抛异常无法收回它们；以 Failed 事件收尾是唯一诚实的做法。
     * - 流在未收到终结事件前断开（EOF / 网络中断）：
     *   若此前未发出过任何事件，抛 [ResponsesApiError.Transport]；
     *   若已发出过事件，以 [ResponsesStreamEvent.Failed]（code =
     *   `stream_ended_without_terminal`，retryable = true）收尾。
     */
    fun streamResponse(
        request: ResponsesApiRequest,
        accessToken: String,
    ): Flow<ResponsesStreamEvent> = flow {
        if (accessToken.isBlank()) {
            throw ResponsesApiError.NotConfigured(
                "未提供 SIWC OAuth token：请用户先通过“使用 ChatGPT 登录”完成授权，" +
                    "本层不持有、不生成 token。",
            )
        }
        // 先在本地强制 Preview 限制：不支持的字段在这里就拒绝，绝不发到服务器。
        val requestBody = buildResponsesRequestBody(request)
        val connection = try {
            transport.open(baseUrl, accessToken, requestBody.toString())
        } catch (e: IOException) {
            throw ResponsesApiError.Transport("连接 Responses API 失败（网络异常）：${e.message}", e)
        }
        try {
            if (connection.statusCode != 200) {
                throw mapHttpError(connection)
            }
            val parser = ResponsesStreamParser(onMalformedLine)
            var emittedAny = false
            val reader = connection.openBodyReader()
            try {
                while (currentCoroutineContext().isActive) {
                    val line = try {
                        reader.readLine()
                    } catch (e: IOException) {
                        throw ResponsesApiError.Transport("读取 Responses 流失败（网络异常）：${e.message}", e)
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
                if (event is ResponsesStreamEvent.Failed && !parser.sawTerminal && !emittedAny) {
                    // 合成的断流失败（finish() 因未见终结事件而生成），且此前未收到
                    // 任何真实内容：抛 Transport，而不是发一个孤零零的 Failed 事件
                    // 让调用方误以为“收到过回复”。已见终结事件的 Failed 是服务器
                    // 真实发来的失败事件，必须原样发出，不走这条分支。
                    throw ResponsesApiError.Transport(event.message)
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
     * HTTP 状态码 -> [ResponsesApiError]。
     *
     * UNVERIFIED（官方文档未明确 SIWC 路由的状态码语义，以下是本层本地策略，
     * 绝非“官方规定”）：
     * - 403 映射为 QuotaExceeded（资格不足 / 计划不符 / 额度耗尽同一族）：
     *   官方只说明了资格范围（开源 / 本地个人 / 入选私有），未说明资格不足时
     *   服务器返回 401 / 403 / 429 中的哪一个；本层把 403 归入“不可重试的
     *   资格/额度类失败”，提示文案明确写“可能为资格不足”，不把推测写成定论。
     * - 429 映射为 QuotaExceeded（不可重试）：429 可能是瞬时限流（可重试），
     *   也可能是额度耗尽（不可重试）；两者在状态码上无法区分，骨架的
     *   QuotaExceeded 语义为不可重试，本层从严取不可重试；若服务器在错误体
     *   或 Retry-After 中给出可重试信号，调用方应在重试前人工确认。
     * - Cloudflare 520–527 映射为 Transport（可重试）：这是通用网关错误语义，
     *   未在 OpenAI 官方文档中针对 SIWC 路由核实。
     */
    internal fun mapHttpError(connection: ResponsesConnection): ResponsesApiError {
        val status = connection.statusCode
        val (serverCode, serverMessage) = parseErrorBody(connection.readErrorBody())
        val detail = buildString {
            if (serverCode != null) append("（服务器错误码：$serverCode）")
            if (serverMessage != null) append(" $serverMessage")
        }
        return when (status) {
            401 -> ResponsesApiError.AuthFailed(
                "ChatGPT 授权失败（401）：token 无效或已过期，请重新登录。$detail",
            )
            403 -> ResponsesApiError.QuotaExceeded(
                "请求被拒绝（403）：可能为 SIWC 资格不足、计划不符或额度受限，" +
                    "请检查该 ChatGPT 账号的计划与授权范围；不可重试。$detail",
            )
            429 -> ResponsesApiError.QuotaExceeded(
                "额度或速率受限（429）：请稍后再试或检查计划用量；本层按不可重试处理。$detail",
            )
            400 -> ResponsesApiError.Unsupported(
                "请求被服务器拒绝（400）：$detail",
                serverCode = serverCode,
            )
            500, 502, 503, 504,
            520, 521, 522, 523, 524, 525, 526, 527,
            -> ResponsesApiError.Transport(
                "Responses 服务暂不可用（HTTP $status），可重试。$detail",
            )
            else -> ResponsesApiError.Transport(
                "Responses 请求失败（HTTP $status），可重试。$detail",
            )
        }
    }

    /** 解析 OpenAI 风格错误体 {"error": {"message", "type", "param", "code"}}。 */
    internal fun parseErrorBody(errorBody: String?): Pair<String?, String?> {
        if (errorBody.isNullOrBlank()) return null to null
        return try {
            val error = JSONObject(errorBody).optJSONObject("error") ?: return null to null
            val code = error.optString("code", "").ifEmpty { null }
            val message = error.optString("message", "").ifEmpty { null }
            code to message
        } catch (_: Exception) {
            null to null
        }
    }

    companion object {
        /** 官方 Responses API 端点（SIWC Cookbook 与官方 API 参考一致）。 */
        const val RESPONSES_URL: String = "https://api.openai.com/v1/responses"
    }
}

/**
 * 构建 Responses 请求体，并强制 SIWC Preview 限制。
 *
 * 已核实（官方 Preview 限制页）：
 * - 必须含 `store: false`、`stream: true`；
 * - `input` 为数组，每次带全量历史；`instructions` 承载系统指引；
 * - 不得带 `temperature` / `max_output_tokens` / `previous_response_id` 等字段。
 *
 * UNVERIFIED：
 * - 工具的命名空间分组形状：官方 Preview 限制页只说“把 function / custom 工具
 *   分组进命名空间或经 additional_tools 输入项提供”，未给出具体 JSON 形状。
 *   本函数按 Responses API 通用形状发送 `{"type": "function", "name", "description",
 *   "parameters"}`，不编造 namespace 字段；若 SIWC 路由要求命名空间包装，
 *   服务器会以 400 拒绝，调用方届时按服务器返回的字段名修正——绝不先猜一个
 *   namespace 形状写进去。
 * - `input` 消息的 `content`：官方允许字符串或内容块数组；本函数用字符串形式
 *   （最简、且与 SIWC Cookbook 的 `input: copiedText` 字符串例证一致）。
 */
internal fun buildResponsesRequestBody(request: ResponsesApiRequest): JSONObject {
    request.temperature?.let {
        throw ResponsesApiError.Unsupported(
            "SIWC Preview 不支持 temperature（官方 Preview 限制）；调用方请求了 temperature=$it，" +
                "已拒绝，未静默忽略。",
        )
    }
    request.maxOutputTokens?.let {
        throw ResponsesApiError.Unsupported(
            "SIWC Preview 不支持 max_output_tokens（官方 Preview 限制）；调用方请求了 " +
                "max_output_tokens=$it，已拒绝，未静默忽略。",
        )
    }
    require(request.model.isNotBlank()) { "model 不能为空" }
    require(request.messages.isNotEmpty() || !request.instructions.isNullOrBlank()) {
        "input 与 instructions 不能同时为空"
    }
    val body = JSONObject()
    body.put("model", request.model)
    body.put("store", false)
    body.put("stream", true)
    request.instructions?.let { body.put("instructions", it) }
    val input = JSONArray()
    for (message in request.messages) {
        when (message.role) {
            "user", "assistant", "developer" -> {
                input.put(
                    JSONObject()
                        .put("role", message.role)
                        .put("content", message.content),
                )
            }
            "system" -> throw ResponsesApiError.Unsupported(
                "SIWC Preview 拒绝显式 system 消息（官方 Preview 限制）；" +
                    "系统指引请放 instructions，不要以 system 消息发送。",
            )
            // 防御性分支：SIWC 桥接层永不发出 TOOL（工具结果在桥接层已
            // 转为 USER 消息），Responses API 也没有 tool 角色；一旦出现
            // 说明调用链出错，明确拒绝，绝不静默降级或伪造。
            "tool" -> throw ResponsesApiError.Unsupported(
                "SIWC 不支持 tool 角色消息（Responses API 无 tool 角色）；" +
                    "工具结果须由桥接层转为 USER 消息。",
            )
            else -> throw ResponsesApiError.Unsupported("不支持的消息角色：${message.role}")
        }
    }
    body.put("input", input)
    if (request.tools.isNotEmpty()) {
        val tools = JSONArray()
        for (tool in request.tools) {
            val parameters = try {
                JSONObject(tool.parametersJson)
            } catch (e: Exception) {
                throw ResponsesApiError.Unsupported(
                    "工具 ${tool.name} 的 parametersJson 不是合法 JSON 对象：${e.message}",
                )
            }
            tools.put(
                JSONObject()
                    .put("type", "function")
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("parameters", parameters),
            )
        }
        body.put("tools", tools)
    }
    return body
}

/** 一次 Responses 请求（调用方组装；token 不在这里，在 [ResponsesApiClient.streamResponse] 参数里）。 */
data class ResponsesApiRequest(
    /** 模型标识（如调用方从 listModels 拿到的 slug；null 由调用方在之前填默认模型，本层不猜）。 */
    val model: String,
    /** 全量对话历史（SIWC Preview 要求每次带全量，不支持 previous_response_id）。 */
    val messages: List<ResponsesInputMessage>,
    /** 系统级指引：发为 `instructions`，不用 system 消息。 */
    val instructions: String? = null,
    /** function 工具；SIWC Preview 下仅 function / custom 工具，托管工具不支持。 */
    val tools: List<ResponsesTool> = emptyList(),
    /** 调用方请求的 temperature：本层必须拒绝（Preview 不支持），绝不静默忽略。 */
    val temperature: Double? = null,
    /** 调用方请求的 maxOutputTokens：本层必须拒绝（Preview 不支持），绝不静默忽略。 */
    val maxOutputTokens: Int? = null,
)

/** 一条输入消息。role 只允许 user / assistant / developer（system 请走 instructions）。 */
data class ResponsesInputMessage(
    val role: String,
    val content: String,
)

/** 一个 function 工具；parametersJson 必须是 JSON 对象字符串。 */
data class ResponsesTool(
    val name: String,
    val description: String,
    val parametersJson: String,
)

/**
 * Responses API 错误（与骨架 [BrainError] 一一对应，供桥接层 SiwcM2BrainAdapter
 * 直译：NotConfigured -> 未配置；AuthFailed -> auth_failed 不可重试；
 * QuotaExceeded -> quota_exceeded 不可重试；Unsupported -> 不支持；
 * Transport -> 可重试。本文件不 import 骨架包，因骨架尚未合入 M2 工程树。）
 */
sealed class ResponsesApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 未配置 / 未提供 token（例如用户尚未登录）。 */
    class NotConfigured(message: String) : ResponsesApiError(message)

    /** 授权失败或过期（HTTP 401）；用户须重新登录，不可重试。 */
    class AuthFailed(message: String, cause: Throwable? = null) : ResponsesApiError(message, cause)

    /** 计划 / 额度 / 资格不足（HTTP 403 / 429）；不可重试。 */
    class QuotaExceeded(message: String) : ResponsesApiError(message)

    /** 请求了 Preview 不支持的能力（temperature / max_output_tokens / system 消息等）。
     * [serverCode] 是服务器在 400 错误体里返回的 code（如 model_not_found），
     * 本地拒绝时为 null；调用方据此判断是否可"重新发现模型后重试一次"。 */
    class Unsupported(message: String, val serverCode: String? = null) : ResponsesApiError(message)

    /** 网络或服务器错误（HTTP 5xx / Cloudflare 5xx / IO 异常）；可重试。 */
    class Transport(message: String, cause: Throwable? = null) : ResponsesApiError(message, cause)
}

/**
 * 可注入的 HTTP 传输层：生产用 [HttpsResponsesTransport]，单测用 fake 实现。
 * token 只经过 [open] 的参数，不由本层存储；实现者同样不得存储或记录它。
 */
interface ResponsesTransport {
    /** 打开 POST 连接。网络失败时抛 [IOException]（由调用方译为 Transport）。 */
    @Throws(IOException::class)
    fun open(url: String, accessToken: String, requestBody: String): ResponsesConnection
}

/** 一条已打开的 HTTP 连接（调用方负责 [close]）。 */
interface ResponsesConnection : Closeable {
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
class HttpsResponsesTransport(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 120_000,
) : ResponsesTransport {

    override fun open(url: String, accessToken: String, requestBody: String): ResponsesConnection {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        // token 只放进 Authorization 头；本类不存储、不记录、不写进异常。
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        return HttpsResponsesConnection(connection)
    }
}

private class HttpsResponsesConnection(
    private val connection: HttpsURLConnection,
) : ResponsesConnection {

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
