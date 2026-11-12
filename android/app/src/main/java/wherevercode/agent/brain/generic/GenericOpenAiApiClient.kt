package wherevercode.agent.brain.generic

import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.brain.BrainConfigValidator
import wherevercode.agent.brain.ChatMessage
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ModelInfo

/**
 * Generic OpenAI-compatible API client (`/chat/completions` + `/models`).
 *
 * Faithful port of [wherevercode.agent.brain.deepseek.DeepSeekApiClient]:
 * same transport shape, same URL normalisation, same request-body builder
 * (native tool_calls / tool_call_id pairing), same HTTP error mapping —
 * only the wording is vendor-neutral ("模型 API" instead of a vendor
 * name) and there is no vendor default base URL or suggested model: the
 * base URL and model always come from the user's
 * [wherevercode.agent.brain.BrainConfig]. Do not invent a second wire
 * protocol: every OpenAI-compatible provider speaks this shape, and the
 * per-provider differences (if any) belong in the adapter layer, not
 * in a forked client.
 *
 * Security: the API key is passed in per call, only in the Authorization
 * header. It is never stored, logged, or included in error text.
 */
class GenericOpenAiApiClient(
    private val transport: GenericOpenAiTransport = HttpsGenericOpenAiTransport(),
    private val onMalformedLine: (String) -> Unit = {},
) {

    /** Stream one chat completion. Failures become [GenericOpenAiEvent.Failed]. */
    fun streamChat(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String,
    ): Flow<GenericOpenAiEvent> = flow {
        // 本机模型可无 Key（不发 Authorization 头）；非本机地址无 Key
        // 仍拒绝，不静默调一个没 Key 的远端。
        if (apiKey.isBlank() && !BrainConfigValidator.isLocalModelUrl(baseUrl)) {
            emit(
                GenericOpenAiEvent.Failed(
                    code = "not_configured",
                    message = "未配置模型 API Key：请先在设置里填写你自己的 Key。",
                    retryable = false,
                ),
            )
            return@flow
        }
        if (request.model.isBlank()) {
            emit(
                GenericOpenAiEvent.Failed(
                    code = "not_configured",
                    message = "未选择模型：请先在设置里选择或刷新模型列表。",
                    retryable = false,
                ),
            )
            return@flow
        }
        val body = buildGenericRequestBody(request)
        val url = chatCompletionsUrl(baseUrl)
        val connection = try {
            transport.post(url, apiKey, body.toString())
        } catch (e: IOException) {
            emit(
                GenericOpenAiEvent.Failed(
                    code = "transport_error",
                    message = "连接模型 API 失败（${ioReason(e)}）：${e.message}",
                    retryable = true,
                ),
            )
            return@flow
        }
        try {
            if (connection.statusCode != 200) {
                val detail = parseErrorBody(connection.readErrorBody())
                val error = mapHttpError(connection.statusCode, detail)
                emit(error)
                return@flow
            }
            val parser = GenericOpenAiStreamParser(onMalformedLine)
            val reader = connection.openBodyReader()
            try {
                while (currentCoroutineContext().isActive) {
                    val line = try {
                        reader.readLine()
                    } catch (e: IOException) {
                        emit(
                            GenericOpenAiEvent.Failed(
                                code = "transport_error",
                                message = "读取模型 API 流失败（${ioReason(e)}）：${e.message}",
                                retryable = true,
                            ),
                        )
                        return@flow
                    } ?: break
                    for (event in parser.feedLine(line)) emit(event)
                    if (parser.terminal) break
                }
            } finally {
                try {
                    reader.close()
                } catch (_: IOException) {
                    // Closing must not hide the main result.
                }
            }
            for (event in parser.finish()) emit(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(
                GenericOpenAiEvent.Failed(
                    code = "transport_error",
                    message = "模型 API 请求失败：${e.message}",
                    retryable = true,
                ),
            )
        } finally {
            try {
                connection.close()
            } catch (_: Exception) {
                // Closing must not hide the main result.
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Probe the model catalogue. This is a read-only GET; no inference cost. */
    suspend fun listModels(apiKey: String, baseUrl: String): Result<List<ModelInfo>> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank() && !BrainConfigValidator.isLocalModelUrl(baseUrl)) {
                return@withContext Result.failure(
                    IllegalStateException("未配置模型 API Key。"),
                )
            }
            val connection = try {
                transport.get(modelsUrl(baseUrl), apiKey)
            } catch (e: IOException) {
                return@withContext Result.failure(
                    IllegalStateException("连接模型 API 失败（网络异常）：${e.message}"),
                )
            }
            try {
                if (connection.statusCode != 200) {
                    val detail = parseErrorBody(connection.readErrorBody())
                    val error = mapHttpError(connection.statusCode, detail)
                    return@withContext Result.failure(IllegalStateException(error.message))
                }
                val text = connection.openBodyReader().use { it.readText() }
                val root = JSONObject(text)
                val data = root.optJSONArray("data") ?: JSONArray()
                val models = mutableListOf<ModelInfo>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.stringOrEmpty("id")
                    if (id.isNotBlank()) {
                        models += ModelInfo(id = id, displayName = id)
                    }
                }
                Result.success(models)
            } catch (e: Exception) {
                Result.failure(IllegalStateException("解析模型 API 模型列表失败：${e.message}"))
            } finally {
                try {
                    connection.close()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }

    private fun mapHttpError(status: Int, detail: String): GenericOpenAiEvent.Failed = when (status) {
        -1 -> GenericOpenAiEvent.Failed(
            code = "transport_error",
            message = "连接模型 API 失败或超时（未收到 HTTP 响应）。$detail",
            retryable = true,
        )
        401 -> GenericOpenAiEvent.Failed(
            code = "auth_failed",
            message = "模型 API 授权失败（401）：API Key 无效或已失效。$detail",
            retryable = false,
        )
        402 -> GenericOpenAiEvent.Failed(
            code = "insufficient_balance",
            message = "模型 API 账户余额不足（402）：请充值或换用其他大脑。$detail",
            retryable = false,
        )
        400 -> GenericOpenAiEvent.Failed(
            code = "invalid_request",
            message = "模型 API 拒绝了请求（400）：$detail",
            retryable = false,
        )
        404 -> GenericOpenAiEvent.Failed(
            code = "model_not_found",
            message = "模型 API 找不到该模型（404）：请在设置里刷新模型列表并重新选择。$detail",
            retryable = false,
        )
        422 -> GenericOpenAiEvent.Failed(
            code = "invalid_request",
            message = "模型 API 无法处理该请求（422）：$detail",
            retryable = false,
        )
        429 -> GenericOpenAiEvent.Failed(
            code = "rate_limited",
            message = "模型 API 请求过于频繁（429）：请稍后再试。$detail",
            retryable = true,
        )
        in 500..599 -> GenericOpenAiEvent.Failed(
            code = "transport_error",
            message = "模型 API 服务暂不可用（HTTP $status）：$detail",
            retryable = true,
        )
        else -> GenericOpenAiEvent.Failed(
            code = "transport_error",
            message = "模型 API 请求失败（HTTP $status）：$detail",
            retryable = true,
        )
    }

    private fun parseErrorBody(body: String?): String {
        if (body.isNullOrBlank()) return ""
        return try {
            val root = JSONObject(body)
            val error = root.optJSONObject("error")
            val message = error?.stringOrEmpty("message")?.takeIf { it.isNotBlank() }
                ?: root.stringOrEmpty("message")
            val code = error?.stringOrEmpty("code")?.takeIf { it.isNotBlank() }
            buildString {
                if (code != null) append("（错误码：$code）")
                if (message.isNotBlank()) append(" $message")
            }.trim()
        } catch (_: Exception) {
            body.take(300)
        }
    }

    companion object {
        internal fun ioReason(e: IOException): String =
            if (e is SocketTimeoutException) "超时" else "网络异常"

        internal fun chatCompletionsUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

        internal fun modelsUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/chat/completions")) {
                base.removeSuffix("/chat/completions") + "/models"
            } else if (base.endsWith("/models")) {
                base
            } else {
                "$base/models"
            }
        }

        /**
         * Build the exact OpenAI-compatible request body.
         *
         * Ported from DeepSeek's builder: assistant tool calls keep their
         * ids and the tool result carries the matching `tool_call_id`. A
         * pre-fix persisted tool message with no id is downgraded to a
         * user-visible result note instead of being sent as an invalid
         * `role="tool"` message (which OpenAI-compatible servers reject).
         * The adapter rejects tool requests in stage 1, so this builder
         * normally sees no tools — the tool branch is kept for parity
         * with the DeepSeek client, not as a second protocol.
         */
        internal fun buildGenericRequestBody(request: ChatRequest): JSONObject {
            val body = JSONObject()
            body.put("model", request.model)
            body.put("stream", true)
            val messages = JSONArray()
            for (message in request.messages) {
                messages.put(toWireMessage(message))
            }
            body.put("messages", messages)
            if (request.tools.isNotEmpty()) {
                val tools = JSONArray()
                for (tool in request.tools) {
                    val parameters = try {
                        JSONObject(tool.inputSchema as Map<*, *>)
                    } catch (_: Exception) {
                        JSONObject().put("type", "object").put("properties", JSONObject())
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
            request.temperature?.let { body.put("temperature", it) }
            request.maxTokens?.let { body.put("max_tokens", it) }
            return body
        }

        private fun toWireMessage(message: ChatMessage): JSONObject {
            return when (message.role) {
                "system", "user" -> {
                    val obj = JSONObject().put("role", message.role)
                    if (message.images.isNotEmpty()) {
                        // V5 按主流改（maintainer 2026-11-11）：带图片的消息在 wire
                        // 上是 content parts 数组（OpenAI / xAI 同款格式）：
                        // [{type:text,…},{type:image_url,…}]。引擎只在适配器
                        // 报告 vision=true 时才让图片走到这里，见 BrainEngine。
                        val parts = JSONArray()
                        parts.put(
                            JSONObject()
                                .put("type", "text")
                                .put("text", message.content),
                        )
                        for (image in message.images) {
                            parts.put(
                                JSONObject()
                                    .put("type", "image_url")
                                    .put(
                                        "image_url",
                                        JSONObject().put("url", image.dataUrl),
                                    ),
                            )
                        }
                        obj.put("content", parts)
                    } else {
                        obj.put("content", message.content)
                    }
                    obj
                }
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
                    obj
                }
                "tool" -> {
                    if (message.toolCallId.isNullOrBlank()) {
                        // Legacy persisted history: a tool message without an
                        // id cannot be paired and would make the server reject
                        // the whole request. Keep the information as a user
                        // message instead of sending an invalid request.
                        JSONObject()
                            .put("role", "user")
                            .put("content", "[工具结果]\n" + message.content)
                    } else {
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", message.toolCallId)
                            .put("content", message.content)
                    }
                }
                else -> JSONObject()
                    .put("role", "user")
                    .put("content", message.content)
            }
        }
    }
}

/** Android org.json returns "null" for JSON null via optString(name, fallback). */
private fun JSONObject.stringOrEmpty(name: String): String =
    if (isNull(name)) "" else optString(name, "")

/**
 * Injectable HTTP transport. Production uses [HttpsGenericOpenAiTransport];
 * tests use a fake that returns canned SSE. The key crosses only [post]/[get]
 * and must never be stored or logged by an implementation.
 */
interface GenericOpenAiTransport {
    @Throws(IOException::class)
    fun post(url: String, apiKey: String, requestBody: String): GenericOpenAiConnection

    @Throws(IOException::class)
    fun get(url: String, apiKey: String): GenericOpenAiConnection
}

interface GenericOpenAiConnection : Closeable {
    val statusCode: Int
    fun openBodyReader(): BufferedReader
    fun readErrorBody(): String?
}

/** HttpsURLConnection implementation; no extra HTTP dependency. */
class HttpsGenericOpenAiTransport(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 120_000,
) : GenericOpenAiTransport {

    override fun post(url: String, apiKey: String, requestBody: String): GenericOpenAiConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        // 本机模型无 Key 时不发 Authorization 头（不发 "Bearer " 空值）。
        if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        return HttpsGenericOpenAiConnection(connection)
    }

    override fun get(url: String, apiKey: String): GenericOpenAiConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
        return HttpsGenericOpenAiConnection(connection)
    }
}

private class HttpsGenericOpenAiConnection(
    private val connection: HttpURLConnection,
) : GenericOpenAiConnection {

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
            // ignore
        }
    }
}
