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
import wherevercode.agent.core.platform.LocalFirstPlatformHttpClient

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
    /** 回复的消息 id（平台 reply_to，频道补全二期话题串用）。 */
    val replyTo: String? = null,
    /** 已撤回（平台 recalled；正文已被平台清空，界面显示占位）。 */
    val recalled: Boolean = false,
    /** 已置顶（平台 channel_pins）。 */
    val pinned: Boolean = false,
    /** 表情反应聚合（平台 channel_reactions）。 */
    val reactions: List<ChannelReaction> = emptyList(),
    /** 附件引用（频道补全二期·引用制：只存引用，不存二进制）。 */
    val attachmentRef: String? = null,
    /** 转发来源标注（“来源频道 · 原发送者”）。 */
    val forwardOf: String? = null,
)

/** 一条表情反应的聚合（平台 reactions 数组一项）。 */
data class ChannelReaction(
    val emoji: String,
    val count: Int,
    val mine: Boolean = false,
)

/** 频道列表一项（平台 agent_channel_list 输出；kind=dm 为私聊）。 */
data class ChannelSummary(
    val id: String,
    val name: String,
    val kind: String = "channel",
    val archived: Boolean = false,
    /** 新成员可看全部历史（№12；平台 history_visible，缺失时默认可见）。 */
    val historyVisible: Boolean = true,
    val participantIds: List<String> = emptyList(),
    /** 参与者明细（平台为准的频道成员名单，与 iOS ChannelParticipant 同口径）。 */
    val participantDetails: List<ChannelParticipant> = emptyList(),
    /** 最后消息时间（epoch 秒；0 表示无数据，切换器行尾时间用）。 */
    val lastMessageAtSeconds: Double = 0.0,
    /** 最后消息正文（切换器行预览用；空表示无数据）。 */
    val lastMessageText: String = "",
)

/**
 * 频道的一位参与者（平台 `agent_channel_list` 输出的 participants 一项）。
 * 这是平台口径的成员名单：成员以平台为准，不以本机加过的 Agent 为准。
 * [agentName] 缺失时回落 agentId（与 iOS 同口径），不编造名字。
 */
data class ChannelParticipant(
    val agentId: String,
    val agentName: String,
    val role: String? = null,
    val isAgent: Boolean? = null,
    val isSelf: Boolean = false,
)

/** 一位成员对某条消息的已读状态（平台 agent_channel_readers 输出）。 */
data class ChannelReader(
    val agentId: String,
    val agentName: String,
    val read: Boolean,
)

/** 平台消息 JSON → [PlatformChannelMessage]（含二/三期新字段；缺字段按旧形状容错）。 */
fun parsePlatformChannelMessage(obj: JSONObject): PlatformChannelMessage {
    val reactions = mutableListOf<ChannelReaction>()
    val rawReactions = obj.optJSONArray("reactions")
    if (rawReactions != null) {
        for (i in 0 until rawReactions.length()) {
            val r = rawReactions.optJSONObject(i) ?: continue
            val emoji = r.optString("emoji", "")
            if (emoji.isBlank()) continue
            reactions += ChannelReaction(
                emoji = emoji,
                count = r.optInt("count", 0),
                mine = r.optBoolean("mine", false),
            )
        }
    }
    return PlatformChannelMessage(
        id = obj.optString("id", ""),
        agentId = obj.optString("agent_id", ""),
        agentName = obj.optString("agent_name", ""),
        text = obj.optString("text", ""),
        createdAtSeconds = obj.optDouble("created_at", 0.0),
        replyTo = obj.optString("reply_to", "").takeIf { it.isNotBlank() },
        recalled = obj.optBoolean("recalled", false),
        pinned = obj.optBoolean("pinned", false),
        reactions = reactions,
        attachmentRef = obj.optString("attachment_ref", "").takeIf { it.isNotBlank() },
        forwardOf = obj.optString("forward_of", "").takeIf { it.isNotBlank() },
    )
}

/** 平台频道 JSON → [ChannelSummary]。 */
fun parseChannelSummary(obj: JSONObject): ChannelSummary {
    val details = mutableListOf<ChannelParticipant>()
    val raw = obj.optJSONArray("participants")
    if (raw != null) {
        for (i in 0 until raw.length()) {
            when (val item = raw.opt(i)) {
                is JSONObject -> {
                    val pid = item.optString("agent_id", "")
                        .ifBlank { item.optString("id", "") }
                    if (pid.isBlank()) continue
                    details += ChannelParticipant(
                        agentId = pid,
                        agentName = item.optString("agent_name", "")
                            .ifBlank { item.optString("name", "") }
                            .ifBlank { pid },
                        role = item.optString("role", "").takeIf { it.isNotBlank() },
                        isAgent = if (item.has("is_agent") && !item.isNull("is_agent")) {
                            item.optBoolean("is_agent")
                        } else {
                            null
                        },
                        isSelf = item.optBoolean("is_self", false),
                    )
                }
                // 容错：participants 也可能是纯字符串数组（iOS 同口径）。
                is String -> if (item.isNotBlank()) {
                    details += ChannelParticipant(agentId = item, agentName = item)
                }
            }
        }
    }
    return ChannelSummary(
        id = obj.optString("id", ""),
        name = obj.optString("name", ""),
        kind = obj.optString("kind", "channel").ifBlank { "channel" },
        archived = obj.optBoolean("archived", false),
        historyVisible = obj.optBoolean("history_visible", true),
        participantIds = details.map { it.agentId },
        participantDetails = details,
        lastMessageAtSeconds = obj.optDouble("last_message_at", 0.0),
        lastMessageText = obj.optString("last_message_text", "")
            .ifBlank { obj.optJSONObject("last_message")?.optString("text", "") ?: "" },
    )
}

/**
 * 平台频道调用口（App → WhereverCode 平台 MCP 端点）。
 * 本接口只做两件事：往频道发一条消息、轮询读回消息。不派任务、
 * 不碰项目。频道管理（建/改名/归档/删/成员增删）是频道补全一期在
 * [PlatformChannelMcpTransport] 上另加的方法组，不进本接口——
 * 平台服务端尚未提供对应工具，见那里的说明。实现绝不能记令牌日志。
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
    private val httpClient: PlatformHttpClient = LocalFirstPlatformHttpClient(),
    private val endpoint: String = PlatformAgent.mcpEndpoint(null),
) : PlatformChannelTransport {

    override suspend fun postMessage(channelId: String, text: String): String =
        postMessageEx(channelId, text)

    /**
     * 发帖（频道补全二/四期扩展）：[replyTo] 回复引用、[effectKey] 幂等键
     * （同键重复发帖平台只落一条，返回原消息）、[attachmentRef] 附件引用
     * （引用制：网盘/投递引用，不传二进制）、[forwardOf] 转发来源标注。
     */
    fun postMessageEx(
        channelId: String,
        text: String,
        replyTo: String? = null,
        effectKey: String? = null,
        attachmentRef: String? = null,
        forwardOf: String? = null,
    ): String {
        val result = toolsCall(
            "agent_channel_post",
            JSONObject().apply {
                put("channel_id", channelId)
                put("text", text)
                if (!replyTo.isNullOrBlank()) put("reply_to", replyTo)
                if (!effectKey.isNullOrBlank()) put("effect_key", effectKey)
                if (!attachmentRef.isNullOrBlank()) put("attachment_ref", attachmentRef)
                if (!forwardOf.isNullOrBlank()) put("forward_of", forwardOf)
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
        return parseMessageList(result)
    }

    /** 频道内搜索（平台 q 透传，频道补全一期补齐安卓侧）。 */
    fun searchMessages(
        channelId: String,
        query: String,
        limit: Int = 50,
        sender: String? = null,
        fromTs: Long? = null,
        toTs: Long? = null,
        kind: String? = null,
    ): List<PlatformChannelMessage> = fetchMessages(
        channelId, limit = limit, query = query,
        sender = sender, fromTs = fromTs, toTs = toTs, kind = kind,
    )

    /**
     * 通用过滤读（№12 历史标配）：参数原样透传服务端
     * `agent_channel_messages`——[beforeId] 往前翻旧页、[afterId]
     * 往后翻新页、[query] 关键词、[sender] 发言人（成员编号或
     * 显示名精确匹配）、[fromTs]/[toTs] 时间窗（Unix 秒，from
     * 含、to 不含）、[kind] 分类（image / file / link）。
     */
    fun fetchMessages(
        channelId: String,
        limit: Int = 50,
        beforeId: String? = null,
        afterId: String? = null,
        query: String? = null,
        sender: String? = null,
        fromTs: Long? = null,
        toTs: Long? = null,
        kind: String? = null,
    ): List<PlatformChannelMessage> {
        val args = JSONObject().apply {
            put("channel_id", channelId)
            put("limit", limit)
            if (!beforeId.isNullOrBlank()) put("before_id", beforeId)
            if (!afterId.isNullOrBlank()) put("after_id", afterId)
            if (!query.isNullOrBlank()) put("q", query)
            if (!sender.isNullOrBlank()) put("sender", sender)
            if (fromTs != null) put("from_ts", fromTs)
            if (toTs != null) put("to_ts", toTs)
            if (!kind.isNullOrBlank()) put("kind", kind)
        }
        return parseMessageList(toolsCall("agent_channel_messages", args))
    }

    /** 只看置顶消息（平台 pinned_only）。 */
    fun pollPinnedMessages(channelId: String): List<PlatformChannelMessage> {
        val args = JSONObject().apply {
            put("channel_id", channelId)
            put("limit", 100)
            put("pinned_only", true)
        }
        return parseMessageList(toolsCall("agent_channel_messages", args))
    }

    private fun parseMessageList(result: JSONObject): List<PlatformChannelMessage> {
        val out = mutableListOf<PlatformChannelMessage>()
        val messages: JSONArray = result.optJSONArray("messages") ?: return out
        for (i in 0 until messages.length()) {
            val obj = messages.optJSONObject(i) ?: continue
            out += parsePlatformChannelMessage(obj)
        }
        return out
    }

    // ---- 消息动作（频道补全二期：撤回/删除/置顶/反应）----
    // 与管理工具同口径：平台未提供工具时原样抛回真实错误，绝不假成功。

    /** 撤回自己的一条消息（平台留占位、正文清空）。 */
    fun recallMessage(channelId: String, messageId: String): JSONObject = toolsCall(
        "agent_channel_recall",
        JSONObject().apply {
            put("channel_id", channelId)
            put("message_id", messageId)
        },
    )

    /** 删除一条消息（发送者或频道管理员；平台审计留痕）。 */
    fun deleteMessage(channelId: String, messageId: String): JSONObject = toolsCall(
        "agent_channel_delete_message",
        JSONObject().apply {
            put("channel_id", channelId)
            put("message_id", messageId)
        },
    )

    /** 置顶 / 取消置顶。 */
    fun setMessagePinned(channelId: String, messageId: String, pinned: Boolean): JSONObject = toolsCall(
        "agent_channel_pin",
        JSONObject().apply {
            put("channel_id", channelId)
            put("message_id", messageId)
            put("pinned", pinned)
        },
    )

    /** 表情反应开关（on=false 为取消自己的这枚反应）。 */
    fun reactMessage(channelId: String, messageId: String, emoji: String, on: Boolean): JSONObject = toolsCall(
        "agent_channel_react",
        JSONObject().apply {
            put("channel_id", channelId)
            put("message_id", messageId)
            put("emoji", emoji)
            put("on", on)
        },
    )

    /** 某条消息的成员已读名单（频道补全三期·已读回执）。 */
    fun channelReaders(channelId: String, messageId: String): List<ChannelReader> {
        val result = toolsCall(
            "agent_channel_readers",
            JSONObject().apply {
                put("channel_id", channelId)
                put("message_id", messageId)
            },
        )
        val out = mutableListOf<ChannelReader>()
        val readers = result.optJSONArray("readers") ?: return out
        for (i in 0 until readers.length()) {
            val r = readers.optJSONObject(i) ?: continue
            out += ChannelReader(
                agentId = r.optString("agent_id", ""),
                agentName = r.optString("agent_name", ""),
                read = r.optBoolean("read", false),
            )
        }
        return out
    }

    /** 频道列表（含 kind/archived/成员；私聊与团队空间共用，频道补全三期）。 */
    fun listChannels(): List<ChannelSummary> {
        val result = toolsCall("agent_channel_list", JSONObject())
        val out = mutableListOf<ChannelSummary>()
        val channels = result.optJSONArray("channels") ?: return out
        for (i in 0 until channels.length()) {
            val c = channels.optJSONObject(i) ?: continue
            val summary = parseChannelSummary(c)
            if (summary.id.isNotBlank()) out += summary
        }
        return out
    }

    /** 打开与某位成员的私聊（已有则返回原私聊频道；私聊只给频道成员）。 */
    fun openDm(agentId: String): JSONObject = toolsCall(
        "agent_channel_open_dm",
        JSONObject().apply { put("agent_id", agentId) },
    )

    /** 给频道建一个入帖 Webhook（令牌只在本次返回里出现一次，用户自管）。 */
    fun createChannelWebhook(channelId: String): JSONObject = toolsCall(
        "agent_channel_create_webhook",
        JSONObject().apply {
            put("channel_id", channelId)
            // 服务端创建接口必填 name（实测缺了回 409）；App 不向用户
            // 索取名字，统一用固定名，用户可在列表里按创建时间区分。
            put("name", "WhereverCode App")
        },
    )

    // ---- 频道管理（频道补全一期）----
    // 工具名按既有 agent_channel_* 命名惯例。注意：当前平台服务端尚未
    // 提供这些工具（平台服务端不在本仓），调用会原样抛回平台的真实
    // 错误（IOException），调用方必须如实呈现，绝不能假装成功；
    // 服务端补齐这些工具后，本层无需改动即可用。

    /** 新建频道，返回平台给出的频道 JSON（形状由平台决定，含 id 时可读）。 */
    fun createChannel(name: String): JSONObject = toolsCall(
        "agent_channel_create",
        JSONObject().apply { put("name", name) },
    )

    /** 频道改名。 */
    fun renameChannel(channelId: String, name: String): JSONObject = toolsCall(
        "agent_channel_rename",
        JSONObject().apply {
            put("channel_id", channelId)
            put("name", name)
        },
    )

    /** 新成员历史可见开关（№12；仅 owner/协调员，服务端校验并原样报错）。 */
    fun setHistoryVisible(channelId: String, visible: Boolean): JSONObject = toolsCall(
        "agent_channel_set_history_visible",
        JSONObject().apply {
            put("channel_id", channelId)
            put("visible", visible)
        },
    )

    /** 归档 / 移出归档。 */
    fun setChannelArchived(channelId: String, archived: Boolean): JSONObject = toolsCall(
        "agent_channel_set_archived",
        JSONObject().apply {
            put("channel_id", channelId)
            put("archived", archived)
        },
    )

    /** 删除频道（破坏性动作：调用方必须先经用户确认）。 */
    fun deleteChannel(channelId: String): JSONObject = toolsCall(
        "agent_channel_delete",
        JSONObject().apply { put("channel_id", channelId) },
    )

    /** 邀请一位 Agent 加入频道。 */
    fun addChannelMember(channelId: String, agentId: String): JSONObject = toolsCall(
        "agent_channel_add_member",
        JSONObject().apply {
            put("channel_id", channelId)
            put("agent_id", agentId)
        },
    )

    /** 把一位 Agent 移出频道。 */
    fun removeChannelMember(channelId: String, agentId: String): JSONObject = toolsCall(
        "agent_channel_remove_member",
        JSONObject().apply {
            put("channel_id", channelId)
            put("agent_id", agentId)
        },
    )

    /** 设置成员角色（owner / coordinator / member；仅频道 owner 可用，服务端校验）。 */
    fun setChannelMemberRole(channelId: String, memberId: String, role: String): JSONObject = toolsCall(
        "agent_channel_set_member_role",
        JSONObject().apply {
            put("channel_id", channelId)
            put("member_id", memberId)
            put("role", role)
        },
    )

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

    /** 列出频道的入帖 Webhook（不含 secret，库里只存哈希）。 */
    fun listChannelWebhooks(channelId: String): JSONObject = toolsCall(
        "agent_channel_list_webhooks",
        JSONObject().apply { put("channel_id", channelId) },
    )

    /** 删除一个入帖 Webhook（删后原地址立即失效）。 */
    fun deleteChannelWebhook(channelId: String, webhookId: String): JSONObject = toolsCall(
        "agent_channel_delete_webhook",
        JSONObject().apply {
            put("channel_id", channelId)
            put("webhook_id", webhookId)
        },
    )
}
