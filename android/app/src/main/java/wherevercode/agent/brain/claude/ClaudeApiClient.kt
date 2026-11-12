package wherevercode.agent.brain.claude

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
import wherevercode.agent.brain.ChatMessage
import wherevercode.agent.brain.ChatRequest
import wherevercode.agent.brain.ModelInfo

/**
 * Anthropic Messages API client (`/v1/messages`).
 *
 * Wire facts (Anthropic Messages API, public docs):
 * - Base URL `https://api.anthropic.com`; chat at `/v1/messages`, models
 *   at `/v1/models`.
 * - Auth is NOT a Bearer token: the key goes in the `x-api-key` header,
 *   plus a mandatory `anthropic-version: 2023-06-01` header. A Bearer
 *   header is rejected, so this client must not reuse the generic
 *   OpenAI-compatible transport.
 * - `max_tokens` is required on every request; `system` is a top-level
 *   parameter, not a message; `stream: true` gives SSE ending in
 *   `message_stop` (no `[DONE]` sentinel).
 * - Tool calling uses the native Messages format: an assistant message
 *   with `tool_use` content blocks (each has an id, a name and an `input`
 *   JSON *object*), followed by a user message with `tool_result` blocks
 *   carrying the matching `tool_use_id`. There is no `tool_calls[]` /
 *   `role="tool"` shape here.
 * - Messages must strictly alternate user/assistant roles, the first
 *   message must be user, and the last must be user (or a tool_result).
 *   [buildClaudeRequestBody] merges consecutive same-role messages and
 *   drops a leading/trailing assistant message instead of sending a
 *   request Anthropic would 400.
 * - The client never hardcodes a key and never chooses a paid model by
 *   itself: the model comes from the user's [wherevercode.agent.brain.BrainConfig].
 *
 * Security: the API key is passed in per call, only in the `x-api-key`
 * header. It is never stored, logged, or included in error text.
 */
class ClaudeApiClient(
    private val transport: ClaudeTransport = HttpsClaudeTransport(),
    private val onMalformedLine: (String) -> Unit = {},
) {

    /** Stream one chat completion. Failures become [ClaudeEvent.Failed]. */
    fun streamChat(
        request: ChatRequest,
        apiKey: String,
        baseUrl: String,
    ): Flow<ClaudeEvent> = flow {
        if (apiKey.isBlank()) {
            emit(
                ClaudeEvent.Failed(
                    code = "not_configured",
                    message = "未配置 Claude API Key：请先在设置里填写你自己的 Key。",
                    retryable = false,
                ),
            )
            return@flow
        }
        if (request.model.isBlank()) {
            emit(
                ClaudeEvent.Failed(
                    code = "not_configured",
                    message = "未选择 Claude 模型：请先在设置里选择或刷新模型列表。",
                    retryable = false,
                ),
            )
            return@flow
        }
        val body = buildClaudeRequestBody(request)
        val url = messagesUrl(baseUrl)
        val connection = try {
            transport.post(url, apiKey, body.toString())
        } catch (e: IOException) {
            emit(
                ClaudeEvent.Failed(
                    code = "transport_error",
                    message = "连接 Claude 失败（${ioReason(e)}）：${e.message}",
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
            val parser = ClaudeStreamParser(onMalformedLine)
            val reader = connection.openBodyReader()
            try {
                while (currentCoroutineContext().isActive) {
                    val line = try {
                        reader.readLine()
                    } catch (e: IOException) {
                        emit(
                            ClaudeEvent.Failed(
                                code = "transport_error",
                                message = "读取 Claude 流失败（${ioReason(e)}）：${e.message}",
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
                ClaudeEvent.Failed(
                    code = "transport_error",
                    message = "Claude 请求失败：${e.message}",
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
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("未配置 Claude API Key。"),
                )
            }
            val connection = try {
                transport.get(modelsUrl(baseUrl), apiKey)
            } catch (e: IOException) {
                return@withContext Result.failure(
                    IllegalStateException("连接 Claude 失败（网络异常）：${e.message}"),
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
                Result.failure(IllegalStateException("解析 Claude 模型列表失败：${e.message}"))
            } finally {
                try {
                    connection.close()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }

    private fun mapHttpError(status: Int, detail: String): ClaudeEvent.Failed = when (status) {
        -1 -> ClaudeEvent.Failed(
            code = "transport_error",
            message = "连接 Claude 失败或超时（未收到 HTTP 响应）。$detail",
            retryable = true,
        )
        401 -> ClaudeEvent.Failed(
            code = "auth_failed",
            message = "Claude 授权失败（401）：API Key 无效或已失效。$detail",
            retryable = false,
        )
        400 -> ClaudeEvent.Failed(
            code = "invalid_request",
            message = "Claude 拒绝了请求（400）：$detail",
            retryable = false,
        )
        404 -> ClaudeEvent.Failed(
            code = "model_not_found",
            message = "Claude 找不到该模型（404）：请在设置里刷新模型列表并重新选择。$detail",
            retryable = false,
        )
        429 -> ClaudeEvent.Failed(
            code = "rate_limited",
            message = "Claude 请求过于频繁（429）：请稍后再试。$detail",
            retryable = true,
        )
        529 -> ClaudeEvent.Failed(
            code = "overloaded",
            message = "Claude 服务过载（529）：请稍后再试。$detail",
            retryable = true,
        )
        in 500..599 -> ClaudeEvent.Failed(
            code = "transport_error",
            message = "Claude 服务暂不可用（HTTP $status）：$detail",
            retryable = true,
        )
        else -> ClaudeEvent.Failed(
            code = "transport_error",
            message = "Claude 请求失败（HTTP $status）：$detail",
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
            // Anthropic errors carry `type` (e.g. invalid_request_error);
            // a proxy may re-wrap in OpenAI shape with `code` instead.
            val code = error?.stringOrEmpty("type")?.takeIf { it.isNotBlank() }
                ?: error?.stringOrEmpty("code")?.takeIf { it.isNotBlank() }
            buildString {
                if (code != null) append("（错误：$code）")
                if (message.isNotBlank()) append(" $message")
            }.trim()
        } catch (_: Exception) {
            body.take(300)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.anthropic.com"
        const val ANTHROPIC_VERSION: String = "2023-06-01"
        const val DEFAULT_MAX_TOKENS: Int = 4096

        internal fun ioReason(e: IOException): String =
            if (e is SocketTimeoutException) "超时" else "网络异常"

        /** Suggested model at first configuration only; never a silent fallback. */
        val SUGGESTED_MODELS: List<String> = listOf("claude-sonnet-4-5")

        internal fun messagesUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/v1/messages")) base else "$base/v1/messages"
        }

        internal fun modelsUrl(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/v1/messages")) {
                base.removeSuffix("/v1/messages") + "/v1/models"
            } else if (base.endsWith("/v1/models")) {
                base
            } else {
                "$base/v1/models"
            }
        }

        /**
         * Build the exact Messages API request body.
         *
         * Differences from the OpenAI-compatible shape, all required by
         * Anthropic:
         * - `system` is a top-level string, not a message; system messages
         *   are collected and joined.
         * - Content is an array of blocks (`text` / `tool_use` /
         *   `tool_result`); tool input is a JSON *object*, not a string.
         * - Roles must strictly alternate, first = user, last = user:
         *   consecutive same-role messages are merged; a leading or
         *   trailing assistant message is dropped rather than sent as a
         *   request Anthropic would 400.
         * - `max_tokens` is required (default [DEFAULT_MAX_TOKENS]).
         * - Tools are `{name, description, input_schema}` — no
         *   `type: function` wrapper.
         */
        internal fun buildClaudeRequestBody(request: ChatRequest): JSONObject {
            val body = JSONObject()
            body.put("model", request.model)
            body.put("max_tokens", request.maxTokens ?: DEFAULT_MAX_TOKENS)
            body.put("stream", true)
            val systemParts = mutableListOf<String>()
            // (role, content blocks) in wire order.
            val wire = mutableListOf<Pair<String, JSONArray>>()
            for (message in request.messages) {
                val (role, blocks) = toWireBlocks(message, systemParts) ?: continue
                if (wire.isNotEmpty() && wire.last().first == role) {
                    val merged = wire.last().second
                    for (i in 0 until blocks.length()) merged.put(blocks.get(i))
                } else {
                    wire.add(role to blocks)
                }
            }
            // The first message must be user: drop a leading assistant turn
            // (a stale tool round with no result) instead of 400ing.
            while (wire.isNotEmpty() && wire.first().first != "user") wire.removeAt(0)
            // The last message must be user (or a tool_result, which is user
            // role): drop a trailing assistant message with unanswered tool
            // calls rather than sending an incomplete round.
            while (wire.isNotEmpty() && wire.last().first != "user") wire.removeAt(wire.size - 1)
            if (systemParts.isNotEmpty()) {
                body.put("system", systemParts.joinToString("\n\n"))
            }
            val messages = JSONArray()
            for ((role, blocks) in wire) {
                messages.put(JSONObject().put("role", role).put("content", blocks))
            }
            body.put("messages", messages)
            if (request.tools.isNotEmpty()) {
                val tools = JSONArray()
                for (tool in request.tools) {
                    val schema = try {
                        JSONObject(tool.inputSchema as Map<*, *>)
                    } catch (_: Exception) {
                        JSONObject().put("type", "object").put("properties", JSONObject())
                    }
                    tools.put(
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("input_schema", schema),
                    )
                }
                body.put("tools", tools)
            }
            request.temperature?.let { body.put("temperature", it) }
            return body
        }

        /**
         * Map one engine message to (wire role, content blocks). Returns
         * null for a message that carries nothing sendable (an assistant
         * turn with no text and no tool calls), or for a system message
         * (collected into [systemParts] instead).
         */
        private fun toWireBlocks(
            message: ChatMessage,
            systemParts: MutableList<String>,
        ): Pair<String, JSONArray>? {
            return when (message.role) {
                "system" -> {
                    if (message.content.isNotBlank()) systemParts += message.content
                    null
                }
                "user" -> {
                    val blocks = JSONArray()
                    blocks.put(JSONObject().put("type", "text").put("text", message.content))
                    "user" to blocks
                }
                "assistant" -> {
                    val blocks = JSONArray()
                    if (message.content.isNotBlank()) {
                        blocks.put(JSONObject().put("type", "text").put("text", message.content))
                    }
                    for (call in message.toolCalls) {
                        val input = try {
                            JSONObject(call.argumentsJson.ifBlank { "{}" })
                        } catch (_: Exception) {
                            JSONObject()
                        }
                        blocks.put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", call.id)
                                .put("name", call.name)
                                .put("input", input),
                        )
                    }
                    if (blocks.length() == 0) null else "assistant" to blocks
                }
                "tool" -> {
                    if (message.toolCallId.isNullOrBlank()) {
                        // Legacy persisted history: a tool message without an
                        // id cannot be paired and would make Claude reject
                        // the whole request. Keep the information as a user
                        // message instead of sending an invalid request.
                        val blocks = JSONArray()
                        blocks.put(
                            JSONObject().put("type", "text")
                                .put("text", "[工具结果]\n" + message.content),
                        )
                        "user" to blocks
                    } else {
                        val blocks = JSONArray()
                        blocks.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", message.toolCallId)
                                .put("content", message.content),
                        )
                        "user" to blocks
                    }
                }
                else -> {
                    val blocks = JSONArray()
                    blocks.put(JSONObject().put("type", "text").put("text", message.content))
                    "user" to blocks
                }
            }
        }
    }
}

/** Android org.json returns "null" for JSON null via optString(name, fallback). */
private fun JSONObject.stringOrEmpty(name: String): String =
    if (isNull(name)) "" else optString(name, "")

/**
 * Injectable HTTP transport. Production uses [HttpsClaudeTransport]; tests
 * use a fake that returns canned SSE. The key crosses only [post]/[get] and
 * must never be stored or logged by an implementation.
 */
interface ClaudeTransport {
    @Throws(IOException::class)
    fun post(url: String, apiKey: String, requestBody: String): ClaudeConnection

    @Throws(IOException::class)
    fun get(url: String, apiKey: String): ClaudeConnection
}

interface ClaudeConnection : Closeable {
    val statusCode: Int
    fun openBodyReader(): BufferedReader
    fun readErrorBody(): String?
}

/**
 * HttpsURLConnection implementation; no extra HTTP dependency.
 * Anthropic auth is `x-api-key` + `anthropic-version`, NOT Bearer.
 */
class HttpsClaudeTransport(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 120_000,
) : ClaudeTransport {

    override fun post(url: String, apiKey: String, requestBody: String): ClaudeConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "text/event-stream")
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", ClaudeApiClient.ANTHROPIC_VERSION)
        connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
        return HttpsClaudeConnection(connection)
    }

    override fun get(url: String, apiKey: String): ClaudeConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", ClaudeApiClient.ANTHROPIC_VERSION)
        return HttpsClaudeConnection(connection)
    }
}

private class HttpsClaudeConnection(
    private val connection: HttpURLConnection,
) : ClaudeConnection {

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
