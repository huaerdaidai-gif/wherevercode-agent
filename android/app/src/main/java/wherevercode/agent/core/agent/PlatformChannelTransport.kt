package wherevercode.agent.core.agent

import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.cert.CertificateException
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * 从平台频道读回的一条消息。
 * [agentId] 与 [agentName] 都保留：回复匹配优先按 `agent_name`
 * 归一化比对（见 [PlatformAgent.sameAgent]），`agent_id` 备用。
 */
data class PlatformChannelMessage(
    val id: String,
    val agentId: String,
    val agentName: String,
    val text: String,
    val createdAtSeconds: Double,
)

/**
 * 平台频道调用口（App → WhereverCode 平台 MCP 端点）。
 * 只做两件事：往频道发一条消息、轮询读回消息。不建频道、不派
 * 任务、不碰项目——那是平台后台的事。实现绝不能记令牌日志。
 */
interface PlatformChannelTransport {
    /** 往 [channelId] 发 [text]，返回发出的消息 id（平台不给时返回 ""）。 */
    suspend fun postMessage(channelId: String, text: String): String

    /**
     * [channelId] 里 [afterId] 之后的消息（null = 最新一页）。
     * [onlyUnread]：只返回已读标记之后的消息（服务端读到哪标到哪，
     * 读完即标为已读）；[markRead]：把已读标记推到本页最后一条。
     * 两者都是服务端 `agent_channel_messages` 的同名参数原样透传。
     */
    suspend fun pollMessages(
        channelId: String,
        afterId: String?,
        limit: Int = 20,
        onlyUnread: Boolean = false,
        markRead: Boolean = false,
    ): List<PlatformChannelMessage>
}

/** 原始 HTTP POST（一次 MCP 调用），返回响应体字符串。 */
interface PlatformHttpClient {
    @Throws(IOException::class)
    fun postJson(url: String, bearerToken: String, bodyJson: String): String

    /**
     * 原始 HTTP GET，返回响应体字符串。默认实现抛错——只有需要 GET 的
     * 调用方（如 A2A Agent 卡是公开文档）才用到；[bearerToken] 为 null
     * 时不发 Authorization 头。
     */
    @Throws(IOException::class)
    fun getJson(url: String, bearerToken: String?): String {
        throw IOException("HTTP GET 未实现。")
    }
}

/**
 * [PlatformHttpClient] 的 [HttpURLConnection] 实现（不引入新依赖）。
 *
 * 证书 pin 仅在本地构建配置同时提供目标主机和 SHA-256 指纹时启用。
 * 其它 HTTPS 连接使用 Android 默认信任库和主机名校验；不安装全信任管理器。
 */
class UrlConnectionPlatformHttpClient(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 60_000,
) : PlatformHttpClient {
    companion object {
        /**
         * 仅固定已配置的主机返回 pinning 工厂；其它主机、
         * 非 https、解析失败的 URL 一律返回 null（走系统默认校验）。
         * 本函数就是“非平台主机不受影响”的接线点，见
         * [PlatformEndpointPinningTest]。公开出来就是为了让测试能
         * 直接断言分发，不用发真实请求。
         */
        fun pinnedSocketFactoryFor(
            url: String,
            pin: PlatformEndpointPinning.Pin? = PlatformEndpointPinning.configuredPin,
        ): SSLSocketFactory? {
            if (pin == null || !PlatformEndpointPinning.isPinnedUrl(url, pin)) return null
            return PlatformEndpointPinning.socketFactory(pin)
        }

        /**
         * 非 2xx 时的错误消息（公开出来就是为了让测试能直接断言，不用发
         * 真实请求）：优先取服务端错误体里的文字（MCP 信封
         * `result.content[0].text`，如“Agent 不存在或已停用”），取不到
         * 再回落到只有状态码。只改消息文本，不改控制流；令牌绝不会出现
         * 在服务端错误体里（服务端只在成功返回里给令牌明文）。
         */
        fun httpErrorMessage(status: Int, body: String): String {
            val detail = try {
                JSONObject(body).optJSONObject("result")
                    ?.optJSONArray("content")
                    ?.optJSONObject(0)?.optString("text", "")
                    ?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }?.take(200)
            return if (detail != null) "平台返回 HTTP $status：$detail"
            else "平台返回 HTTP $status"
        }

        /** Translate common connection failures without exposing raw URLs or request data. */
        fun networkFailureMessage(error: IOException): String? {
            var cause: Throwable? = error
            while (cause != null) {
                if (cause is CertificateException) {
                    val detail = cause.message.orEmpty().lowercase()
                    return when {
                        cause is java.security.cert.CertificateExpiredException ->
                            "平台 TLS 证书已过期，请检查服务器证书。"
                        cause is java.security.cert.CertificateNotYetValidException ->
                            "平台 TLS 证书尚未生效，请检查服务器时间和证书有效期。"
                        "pin did not match" in detail || "pin mismatch" in detail ->
                            "平台 TLS 证书指纹与本地配置不一致，请核对 pin 配置。"
                        else -> "平台 TLS 证书验证失败；系统证书链校验未通过。"
                    }
                }
                if (cause is SSLPeerUnverifiedException) {
                    return "平台 TLS 主机名校验失败，请确认平台地址与证书域名一致。"
                }
                if (cause is UnknownHostException) return "无法解析平台地址，请检查主机名和网络连接。"
                if (cause is SocketTimeoutException) return "连接平台超时，请检查网络后重试。"
                if (cause is ConnectException) return "无法连接平台，请检查地址、端口和服务状态。"
                if (cause is SSLHandshakeException) return "平台 TLS 握手失败，请检查证书链和服务器 TLS 配置。"
                cause = cause.cause
            }
            return null
        }
    }

    override fun postJson(url: String, bearerToken: String, bodyJson: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            pinnedSocketFactoryFor(url)?.let { connection.sslSocketFactory = it }
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            connection.setRequestProperty(
                "User-Agent",
                "WhereverCode-Android/1.0 (PlatformAgent)",
            )
            connection.outputStream.use { it.write(bodyJson.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            if (status !in 200..299) throw IOException(httpErrorMessage(status, body))
            return body
        } catch (e: IOException) {
            val message = networkFailureMessage(e) ?: throw e
            throw IOException(message, e)
        } finally {
            connection.disconnect()
        }
    }

    override fun getJson(url: String, bearerToken: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection) {
            pinnedSocketFactoryFor(url)?.let { connection.sslSocketFactory = it }
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json")
            if (!bearerToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $bearerToken")
            }
            connection.setRequestProperty(
                "User-Agent",
                "WhereverCode-Android/1.0 (PlatformAgent)",
            )
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            if (status !in 200..299) throw IOException("平台返回 HTTP $status")
            return body
        } catch (e: IOException) {
            val message = networkFailureMessage(e) ?: throw e
            throw IOException(message, e)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * [PlatformChannelTransport] 的平台 MCP 实现（JSON-RPC `tools/call`，
 * 与 `custom.wherevercode` 连接器同一端点、同一信封形状）。
 * 令牌每次调用时向 [tokenProvider] 现取，只进 Authorization 请求头，
 * 永不进请求体、日志与错误消息。
 *
 * 参数口径（2026-11-11 实测 `tools/list` 的 inputSchema）：
 * - `agent_channel_post`：`channel_id` + `text`（最长 4000 字）+
 *   可选 `reply_to`——**没有收件方字段**，收件方只能写在正文里
 *   （见 [PlatformAgent.buildWireText] 的 `@<Agent> ` 前缀）。
 * - `agent_channel_messages`：`channel_id`，可选 `limit` / `after_id` /
 *   `only_unread` / `mark_read`（已读标记，见 [pollMessages]）。
 * 响应形状：MCP 信封 `{"jsonrpc":"2.0","result":{"content":
 * [{"type":"text","text":"{…工具 JSON…}"}],"isError":false}}`；
 * 工具 JSON 里 `messages` 数组每项含 `id` / `agent_id` /
 * `agent_name` / `text` / `created_at`。信封已解包的形状也接受。
 */
class PlatformChannelMcpTransport(
    private val tokenProvider: () -> String?,
    private val httpClient: PlatformHttpClient = UrlConnectionPlatformHttpClient(),
    private val endpoint: String = PlatformAgent.mcpEndpoint(null),
) : PlatformChannelTransport {

    override suspend fun postMessage(channelId: String, text: String): String {
        val result = toolsCall(
            "agent_channel_post",
            JSONObject().apply {
                put("channel_id", channelId)
                put("text", text)
            },
        )
        return result.optString("id", "").ifBlank {
            result.optJSONObject("message")?.optString("id", "").orEmpty()
        }
    }

    override suspend fun pollMessages(
        channelId: String,
        afterId: String?,
        limit: Int,
        onlyUnread: Boolean,
        markRead: Boolean,
    ): List<PlatformChannelMessage> {
        val args = JSONObject().apply {
            put("channel_id", channelId)
            put("limit", limit)
            if (!afterId.isNullOrBlank()) put("after_id", afterId)
            if (onlyUnread) put("only_unread", true)
            if (markRead) put("mark_read", true)
        }
        val result = toolsCall("agent_channel_messages", args)
        val out = mutableListOf<PlatformChannelMessage>()
        val messages: JSONArray = result.optJSONArray("messages") ?: return out
        for (i in 0 until messages.length()) {
            val obj = messages.optJSONObject(i) ?: continue
            out += PlatformChannelMessage(
                id = obj.optString("id", ""),
                agentId = obj.optString("agent_id", ""),
                agentName = obj.optString("agent_name", ""),
                text = obj.optString("text", ""),
                createdAtSeconds = obj.optDouble("created_at", 0.0),
            )
        }
        return out
    }

    /**
     * 一次通用的 MCP `tools/call`（供平台令牌签发等不走频道消息的
     * 调用用，见 [PlatformTokenIssuer]）。返回工具的 JSON；传输失败、
     * 平台报错或响应无法解析时抛 [IOException]，消息里绝不带令牌。
     */
    fun callTool(name: String, arguments: JSONObject): JSONObject = toolsCall(name, arguments)

    /**
     * 一次 MCP `tools/call`，返回工具的 JSON（第一个 content 项的
     * `text` 解析后）。传输失败、平台报错或响应无法解析时抛
     * [IOException]，消息里绝不带令牌。
     */
    private fun toolsCall(name: String, arguments: JSONObject): JSONObject {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw IOException("未配置平台 Agent 凭证。")
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", UUID.randomUUID().toString())
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", name)
                put("arguments", arguments)
            })
        }.toString()
        val raw = httpClient.postJson(endpoint, token, body)
        val envelope = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw IOException("平台返回了无法解析的响应。")
        }
        val result = if (envelope.has("result")) {
            envelope.optJSONObject("result") ?: JSONObject()
        } else {
            envelope
        }
        if (result.optBoolean("isError", false)) {
            val detail = result.optJSONArray("content")
                ?.optJSONObject(0)?.optString("text", "")
                ?.takeIf { it.isNotBlank() } ?: "平台调用失败"
            throw IOException(detail.take(300))
        }
        val contentText = result.optJSONArray("content")
            ?.optJSONObject(0)?.optString("text", "").orEmpty()
        if (contentText.isBlank()) throw IOException("平台返回为空。")
        return try {
            JSONObject(contentText)
        } catch (_: Exception) {
            throw IOException("平台返回了无法解析的工具结果。")
        }
    }
}
