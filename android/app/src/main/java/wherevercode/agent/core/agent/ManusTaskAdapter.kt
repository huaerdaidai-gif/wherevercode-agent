package wherevercode.agent.core.agent

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manus 云端任务型适配器（V5 Phase B C3，Manus 404 修复，GPT 8d8ed67e）。
 *
 * 根因（2026-11-10 直读官方文档复核，https://open.manus.ai/docs）：
 * - Manus API 是任务型 API，不是聊天补全 API：Base URL
 *   `https://api.manus.ai`，v2 为当前版，v1 已废弃；认证头是
 *   `x-manus-api-key`（不是 Bearer），且**没有 `/models` 端点**。
 *   旧探针 `GET {baseUrl}/models` 在 api.manus.ai 上恒为 404——
 *   无论用户填 `https://api.manus.ai` 还是 `.../v2`，`modelsUrl()`
 *   都会拼出不存在的路径；认证头也错了，但 404 先发生（路径不存在）。
 * - "Manus 2.0 Lite" 是任务档位 `agent_profile`（`lite`），不是
 *   OpenAI model ID，不能当 model ID 传入任何 OpenAI 兼容调用。
 *
 * 本文件含只读验证（GET /v2/user.me）与档位映射，及任务创建/查询/
 * 继续/停止/轮询的客户端方法，全部用可注入的假传输做单测，
 * 绝不在本批做任何真实调用。密钥只以参数传入，不记日志、
 * 不进任何返回对象与摘要。
 *
 * 幂等说明：官方 v2 文档（2026-11-10 复核）没有服务端幂等键请求头，
 * 故 [createTask] 的幂等是客户端的：同一 adapter 实例内，同一
 * clientOperationId 只创建一次、重试直接返回已建任务；App 重启后
 * 新实例无法去重，调用方必须在创建成功后立即持久化 task_id，
 * 绝不能靠“再建一次”恢复。
 */
sealed class ManusError {
    /** 401 / error.code == "unauthenticated"。 */
    object Unauthorized : ManusError()

    /** 403 / error.code == "permission_denied"。 */
    object Forbidden : ManusError()

    /** 404 / error.code == "not_found"。 */
    object NotFound : ManusError()

    /** 429 / error.code == "rate_limited"。 */
    object RateLimited : ManusError()

    /** 400 / error.code == "invalid_argument"（含服务端返回的说明）。 */
    data class InvalidArgument(val detail: String) : ManusError()

    /** 其他 HTTP 状态（5xx 等，含服务端返回的说明）。 */
    data class HttpError(val code: Int, val detail: String) : ManusError()

    /** 传输失败（DNS/连接/超时等，含异常消息，不含密钥）。 */
    data class NetworkError(val detail: String) : ManusError()

    /** 200 但响应体无法解析（不是 JSON，或缺少必需字段）。 */
    data class InvalidResponse(val detail: String) : ManusError()

    /** 轮询超时（pollUntilTerminal 专用；不是服务端错误）。 */
    object Timeout : ManusError()

    /** 不含密钥的中文说明（只写状态分类，不贴响应正文）。 */
    fun describe(): String = when (this) {
        Unauthorized -> "密钥被拒绝（401 未认证），请检查 Manus API Key。"
        Forbidden -> "无权限（403）：密钥缺少所需权限，请检查 Key 权限。"
        NotFound -> "请求的资源不存在（404）。"
        RateLimited -> "请求过于频繁（429 限速），请稍后再试。"
        is InvalidArgument -> "请求参数无效（400）：$detail"
        is HttpError -> "连接失败：HTTP $code（${detail.ifBlank { "无错误说明" }}）。"
        is NetworkError -> "连接失败：$detail。"
        is InvalidResponse -> "连接失败：响应无法解析（$detail）。"
        Timeout -> "请求超时，请稍后再试。"
    }
}

sealed class ManusResult<out T> {
    data class Success<T>(val value: T) : ManusResult<T>()
    data class Failure(val error: ManusError) : ManusResult<Nothing>()
}

/** GET /v2/user.me 成功返回的身份（只读验证用；摘要里只写“认证通过”，不贴身份）。 */
data class ManusAuthInfo(
    val userId: String?,
    val displayName: String?,
)

/** POST /v2/task.create 成功返回的任务标识（调用方必须立即持久化 task_id）。 */
data class ManusTaskCreated(
    val taskId: String,
    val taskTitle: String?,
    val taskUrl: String?,
)

/** GET /v2/task.detail 返回的任务状态（status 为官方枚举之一）。 */
data class ManusTaskStatus(
    val taskId: String,
    val status: String,
    val title: String?,
    val hasRunningBackgroundJobs: Boolean,
    val creditUsage: Int?,
)

/** GET /v2/task.listMessages 返回的一条事件消息（text 可能为空，如 status_update）。 */
data class ManusTaskMessage(
    val id: String?,
    val type: String,
    val text: String?,
    /** 该消息带的产物附件（assistant_message.attachments；官方 Attachments 页）。 */
    val attachments: List<ManusTaskAttachment> = emptyList(),
    /** tool_used.tool（工具名；仅 tool_used 消息有）。 */
    val toolName: String? = null,
    /** tool_used.status（"success" 等；仅 tool_used 消息有）。 */
    val toolStatus: String? = null,
    /** status_update.agent_status（"running" 等；仅 status_update 消息有）。 */
    val agentStatus: String? = null,
    /** tool_used / status_update 的 description（工具/状态的详细说明）。 */
    val toolDetail: String? = null,
    /** 消息时间戳（毫秒；官方字段）。 */
    val timestamp: Long? = null,
)

/**
 * Manus 任务产出的一份文件（task.listMessages 消息的 attachments[]，
 * 官方 Attachments 页：`attachments[].path` 标识执行环境里的引用，
 * `attachments[].url` 是可下载的 HTTP/HTTPS 链接——临时有效，
 * 失效时重调 listMessages 刷新；v2 没有 file.list 端点）。
 */
data class ManusTaskAttachment(
    val filename: String?,
    val type: String?,
    val url: String?,
    val path: String?,
    val contentType: String?,
    val fileUid: String?,
    val versionUid: String?,
) {
    /** 去重键：file_uid 优先，其次 url，再次 path。 */
    fun dedupeKey(): String = fileUid?.takeIf { it.isNotBlank() }
        ?: url?.takeIf { it.isNotBlank() }
        ?: path?.takeIf { it.isNotBlank() }
        ?: filename?.takeIf { it.isNotBlank() } ?: "file"

    /** 显示名：filename 优先，其次 path 末段，再次 url 末段（去查询串）。 */
    fun displayName(): String {
        filename?.takeIf { it.isNotBlank() }?.let { return it }
        path?.takeIf { it.isNotBlank() }?.let {
            val last = it.substringAfterLast('/').takeIf { s -> s.isNotBlank() }
            if (last != null) return last
        }
        url?.takeIf { it.isNotBlank() }?.let {
            val last = it.substringAfterLast('/').substringBefore('?').substringBefore('#')
            if (last.isNotBlank()) {
                return try {
                    java.net.URLDecoder.decode(last, "UTF-8")
                } catch (_: Exception) {
                    last
                }
            }
        }
        fileUid?.takeIf { it.isNotBlank() }?.let { return it }
        return "文件"
    }
}

/** GET /v2/task.listMessages 返回的一页消息（cursor 分页）。 */
data class ManusMessagesPage(
    val taskId: String,
    val messages: List<ManusTaskMessage>,
    val hasMore: Boolean,
    val nextCursor: String?,
)

/** [ManusTaskAdapter.pollUntilTerminal] 的轮询结果。 */
sealed class ManusPollOutcome {
    /** 主 Agent 已停；backgroundJobsRunning 为 true 时后台工作可能继续、任务可能被再次唤醒。 */
    data class Stopped(val backgroundJobsRunning: Boolean) : ManusPollOutcome()

    /** Agent 在等用户输入或确认（需 sendMessage / confirmAction 跟进）。 */
    object Waiting : ManusPollOutcome()

    /** 任务 hit 不可恢复的错误。 */
    data class Errored(val detail: String) : ManusPollOutcome()

    /** 任务已被删除。 */
    object Deleted : ManusPollOutcome()

    /** 超时未到终态。 */
    object Timeout : ManusPollOutcome()

    /** 调用方取消。 */
    object Cancelled : ManusPollOutcome()

    /** 轮询中某次请求失败（见 error 分类）。 */
    data class Failed(val error: ManusError) : ManusPollOutcome()
}

/** 发给传输层的一次 HTTP 请求（请求头由适配器组装，测试可断言）。 */
data class ManusHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
)

/** 传输层返回的 HTTP 响应（只含状态码与正文，不含密钥）。 */
data class ManusHttpResponse(
    val statusCode: Int,
    val body: String,
)

/** 可注入的 HTTP 传输：单测用假传输，生产用 [DefaultManusTransport]。 */
fun interface ManusTransport {
    fun call(request: ManusHttpRequest): ManusHttpResponse
}

/** 默认传输：JDK HttpURLConnection；只发适配器组装好的请求头，不记日志。 */
class DefaultManusTransport : ManusTransport {
    override fun call(request: ManusHttpRequest): ManusHttpResponse {
        val conn = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = 10_000
            readTimeout = 15_000
            for ((name, value) in request.headers) setRequestProperty(name, value)
            if (request.body != null) {
                doOutput = true
                outputStream.bufferedWriter().use { it.write(request.body) }
            }
        }
        val code = conn.responseCode
        val body = try {
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.readText() ?: ""
        } catch (_: Exception) {
            ""
        }
        return ManusHttpResponse(code, body)
    }
}

class ManusTaskAdapter(
    private val transport: ManusTransport = DefaultManusTransport(),
) {
    /**
     * 客户端幂等表：clientOperationId → 已建任务（同一实例内去重）。
     * 官方文档没有服务端幂等键；App 重启后新实例无法去重，调用方
     * 必须在创建成功后立即持久化 task_id。
     */
    private val createdByOperationId = mutableMapOf<String, ManusTaskCreated>()

    companion object {
        /** 官方 Base URL（Introduction 页，2026-11-10 复核）。 */
        const val DEFAULT_BASE_URL = "https://api.manus.ai"

        /** 官方任务状态枚举（task.detail 页）。 */
        const val STATUS_RUNNING = "running"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_WAITING = "waiting"
        const val STATUS_ERROR = "error"
        const val STATUS_DELETED = "deleted"
        val TASK_STATUSES: Set<String> = setOf(
            STATUS_RUNNING, STATUS_STOPPED, STATUS_WAITING, STATUS_ERROR, STATUS_DELETED,
        )

        /** 官方 agent_profile 稳定值（task.create 页）。 */
        val KNOWN_PROFILES: Set<String> = setOf(
            "standard", "lite", "max",
            "max-medium", "max-high", "max-extra", "max-ultra",
        )

        /**
         * 归一化 Manus 服务地址：空 → 默认地址；去尾斜杠；去掉末尾
         * 的 `/v2`（用户填 `https://api.manus.ai` 与 `.../v2` 都合法，
         * 路径由适配器统一拼 `/v2/...`，绝不拼出 `/v2/v2/...`）。
         */
        fun manusBaseUrl(baseUrl: String?): String {
            val raw = baseUrl?.trim().orEmpty()
            if (raw.isBlank()) return DEFAULT_BASE_URL
            var base = raw.trimEnd('/')
            if (base.endsWith("/v2/user.me")) base = base.removeSuffix("/v2/user.me")
            if (base.endsWith("/v2")) base = base.removeSuffix("/v2")
            return base.trimEnd('/')
        }

        /**
         * 是否 Manus 记录（旧记录无损迁移用）：adapterId == "manus"，
         * 或 baseUrl 主机是 api.manus.ai。旧记录即使 adapterId 仍是
         * deepseek、model 仍是 "Manus 2.0 Lite"，只要主机对上就改走
         * Manus 探针，不要求用户重新添加。
         */
        fun isManusRecord(record: AgentRecord): Boolean {
            if (record.adapterId.equals("manus", ignoreCase = true)) return true
            val base = record.baseUrl?.trim()?.lowercase() ?: return false
            val host = base.removePrefix("https://").removePrefix("http://")
                .substringBefore('/').substringBefore(':')
            return host == "api.manus.ai"
        }

        /**
         * 把用户填的档位/模型名映射为官方 `agent_profile` 稳定值。
         * "Manus 2.0 Lite" / "2.0-lite" / "lite" → "lite"；"2.0" /
         * "1.6"（无后缀的版本号）→ "standard"（官方：无后缀即 standard）；
         * 空白 → null（调用方省略该字段，走服务端默认 standard）。
         * 未知值（如 "gpt-5"、"deepseek-flash"）→ null，绝不原样传入
         * 任何调用——"2.0 Lite" 不是 model ID，不能当 model ID 用。
         */
        fun agentProfileFor(model: String?): String? {
            val raw = model?.trim()?.lowercase() ?: return null
            if (raw.isBlank()) return null
            val tokens = raw.split(Regex("[^a-z0-9]+"))
                .filter { it.isNotBlank() && it != "manus" && !it.matches(Regex("\\d+(\\.\\d+)*")) }
            if (tokens.isEmpty()) {
                // 只剩版本号（如 "2.0"、"manus-1.6"）：官方定义为 standard。
                return if (raw.any { it.isDigit() }) "standard" else null
            }
            return tokens.joinToString("-").takeIf { it in KNOWN_PROFILES }
        }

        /** 用户输入是不是已知的 Manus 档位（表单校验用）。 */
        fun isKnownManusProfileInput(model: String): Boolean =
            agentProfileFor(model) != null
    }

    /**
     * 只读验证认证与可达性：GET /v2/user.me + `x-manus-api-key`。
     * 不创建任务、不产生计费调用。成功只证明密钥有效，不证明可执行。
     */
    fun testAuth(apiKey: String, baseUrl: String?): ManusResult<ManusAuthInfo> {
        return when (val result = requestJson("GET", "/v2/user.me", apiKey, baseUrl, null)) {
            is ManusResult.Success -> {
                val json = result.value
                ManusResult.Success(
                    ManusAuthInfo(
                        userId = json.optString("user_id").takeIf { it.isNotBlank() },
                        displayName = json.optString("name").takeIf { it.isNotBlank() },
                    ),
                )
            }
            is ManusResult.Failure -> result
        }
    }

    /**
     * 创建任务：POST /v2/task.create（异步执行，返回 task_id 与 task_url）。
     * 任务创建会消耗 Manus credits——本批只写客户端方法与单测，
     * 绝不真实调用；真实调用须 maintainer 明确同意后才做。
     * [agentProfile] 是档位名（"Manus 2.0 Lite" 会再映射为 lite），
     * 未知值省略该字段、走服务端默认 standard，绝不原样传入。
     * [clientOperationId] 是客户端幂等键：同一 adapter 实例内同一
     * operationId 只创建一次，重试直接返回已建任务，不再发请求。
     */
    fun createTask(
        apiKey: String,
        baseUrl: String?,
        prompt: String,
        agentProfile: String? = null,
        projectId: String? = null,
        clientOperationId: String = UUID.randomUUID().toString(),
    ): ManusResult<ManusTaskCreated> {
        if (prompt.isBlank()) {
            return ManusResult.Failure(ManusError.InvalidArgument("任务内容不能为空。"))
        }
        createdByOperationId[clientOperationId]?.let { return ManusResult.Success(it) }
        val body = JSONObject().apply {
            put("message", JSONObject().put("content", prompt))
            agentProfileFor(agentProfile)?.let { put("agent_profile", it) }
            if (!projectId.isNullOrBlank()) put("project_id", projectId)
        }.toString()
        return when (val result = requestJson("POST", "/v2/task.create", apiKey, baseUrl, body)) {
            is ManusResult.Success -> {
                val taskId = result.value.optString("task_id").takeIf { it.isNotBlank() }
                    ?: return ManusResult.Failure(
                        ManusError.InvalidResponse("创建任务成功但响应缺少 task_id"),
                    )
                val created = ManusTaskCreated(
                    taskId = taskId,
                    taskTitle = result.value.optString("task_title").takeIf { it.isNotBlank() },
                    taskUrl = result.value.optString("task_url").takeIf { it.isNotBlank() },
                )
                createdByOperationId[clientOperationId] = created
                ManusResult.Success(created)
            }
            is ManusResult.Failure -> result
        }
    }

    /**
     * 查询任务状态与元数据：GET /v2/task.detail?task_id=...。
     * 注意 `stopped` 不一定是终态：[ManusTaskStatus.hasRunningBackgroundJobs]
     * 为 true 时后台工作可能继续、任务可能被再次唤醒。
     */
    fun getTask(
        apiKey: String,
        baseUrl: String?,
        taskId: String,
    ): ManusResult<ManusTaskStatus> {
        if (taskId.isBlank()) {
            return ManusResult.Failure(ManusError.InvalidArgument("任务 id 不能为空。"))
        }
        val path = "/v2/task.detail?task_id=" + URLEncoder.encode(taskId, "UTF-8")
        return when (val result = requestJson("GET", path, apiKey, baseUrl, null)) {
            is ManusResult.Success -> {
                val task = result.value.optJSONObject("task")
                    ?: return ManusResult.Failure(
                        ManusError.InvalidResponse("响应缺少 task"),
                    )
                val status = task.optString("status")
                if (status !in TASK_STATUSES) {
                    return ManusResult.Failure(
                        ManusError.InvalidResponse("未知任务状态：$status"),
                    )
                }
                ManusResult.Success(
                    ManusTaskStatus(
                        taskId = task.optString("id").takeIf { it.isNotBlank() } ?: taskId,
                        status = status,
                        title = task.optString("title").takeIf { it.isNotBlank() },
                        hasRunningBackgroundJobs = task.optBoolean("has_running_background_jobs", false),
                        creditUsage = if (task.has("credit_usage")) task.optInt("credit_usage") else null,
                    ),
                )
            }
            is ManusResult.Failure -> result
        }
    }

    /**
     * 轮询任务进度：GET /v2/task.listMessages?task_id=...&order=desc&limit=...。
     * cursor 分页：上一页的 next_cursor 传回取下一页；传空取第一页。
     */
    fun listMessages(
        apiKey: String,
        baseUrl: String?,
        taskId: String,
        cursor: String? = null,
        limit: Int = 50,
    ): ManusResult<ManusMessagesPage> {
        if (taskId.isBlank()) {
            return ManusResult.Failure(ManusError.InvalidArgument("任务 id 不能为空。"))
        }
        val query = StringBuilder("/v2/task.listMessages?task_id=")
            .append(URLEncoder.encode(taskId, "UTF-8"))
            .append("&order=desc&limit=").append(limit.coerceIn(1, 200))
        if (!cursor.isNullOrBlank()) {
            query.append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
        }
        return when (val result = requestJson("GET", query.toString(), apiKey, baseUrl, null)) {
            is ManusResult.Success -> {
                val json = result.value
                val messages = json.optJSONArray("messages")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        array.optJSONObject(i)?.let { item ->
                            val type = item.optString("type").takeIf { it.isNotBlank() } ?: "message"
                            val nested = if (type.isNotBlank()) item.optJSONObject(type) else null
                            ManusTaskMessage(
                                id = item.optString("id").takeIf { it.isNotBlank() },
                                type = type,
                                text = messageText(item),
                                attachments = parseAttachments(nested, item),
                                toolName = nested?.optString("tool")?.takeIf { it.isNotBlank() },
                                toolStatus = nested?.optString("status")?.takeIf { it.isNotBlank() },
                                agentStatus = nested?.optString("agent_status")?.takeIf { it.isNotBlank() },
                                toolDetail = nested?.optString("description")?.takeIf { it.isNotBlank() },
                                timestamp = if (item.has("timestamp")) item.optLong("timestamp") else null,
                            )
                        }
                    }
                } ?: emptyList()
                ManusResult.Success(
                    ManusMessagesPage(
                        taskId = json.optString("task_id").takeIf { it.isNotBlank() } ?: taskId,
                        messages = messages,
                        hasMore = json.optBoolean("has_more", false),
                        nextCursor = json.optString("next_cursor").takeIf { it.isNotBlank() },
                    ),
                )
            }
            is ManusResult.Failure -> result
        }
    }

    /**
     * 继续会话：POST /v2/task.sendMessage（多轮跟进；已停止的任务
     * 用它恢复）。[agentProfile] 为本轮及后续轮次的档位覆盖，
     * 空表示保持任务当前档位。
     */
    fun sendMessage(
        apiKey: String,
        baseUrl: String?,
        taskId: String,
        text: String,
        agentProfile: String? = null,
    ): ManusResult<Unit> {
        if (taskId.isBlank()) {
            return ManusResult.Failure(ManusError.InvalidArgument("任务 id 不能为空。"))
        }
        if (text.isBlank()) {
            return ManusResult.Failure(ManusError.InvalidArgument("消息内容不能为空。"))
        }
        val body = JSONObject().apply {
            put("task_id", taskId)
            put("message", JSONObject().put("content", text))
            agentProfileFor(agentProfile)?.let { put("agent_profile", it) }
        }.toString()
        return when (val result = requestJson("POST", "/v2/task.sendMessage", apiKey, baseUrl, body)) {
            is ManusResult.Success -> ManusResult.Success(Unit)
            is ManusResult.Failure -> result
        }
    }

    /**
     * 停止任务：POST /v2/task.stop。任务状态变为 `stopped`，仍可用
     * [sendMessage] 恢复。
     */
    fun stopTask(
        apiKey: String,
        baseUrl: String?,
        taskId: String,
    ): ManusResult<Unit> {
        if (taskId.isBlank()) {
            return ManusResult.Failure(ManusError.InvalidArgument("任务 id 不能为空。"))
        }
        val body = JSONObject().put("task_id", taskId).toString()
        return when (val result = requestJson("POST", "/v2/task.stop", apiKey, baseUrl, body)) {
            is ManusResult.Success -> ManusResult.Success(Unit)
            is ManusResult.Failure -> result
        }
    }

    /**
     * 轮询直到任务到达终态：running 继续轮询；stopped / waiting /
     * error / deleted 返回对应结果；超时、取消、请求失败分别返回
     * [ManusPollOutcome.Timeout] / Cancelled / Failed。
     * [sleeper] 与 [isCancelled] 均可注入（单测用假实现，不真睡、
     * 不真等）；生产默认每 [pollIntervalMs] 轮询一次。
     */
    fun pollUntilTerminal(
        apiKey: String,
        baseUrl: String?,
        taskId: String,
        pollIntervalMs: Long = 5_000,
        timeoutMs: Long = 600_000,
        sleeper: (Long) -> Unit = { Thread.sleep(it) },
        isCancelled: () -> Boolean = { false },
    ): ManusPollOutcome {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            when (val result = getTask(apiKey, baseUrl, taskId)) {
                is ManusResult.Success -> {
                    when (result.value.status) {
                        STATUS_STOPPED -> return ManusPollOutcome.Stopped(
                            result.value.hasRunningBackgroundJobs,
                        )
                        STATUS_WAITING -> return ManusPollOutcome.Waiting
                        STATUS_ERROR -> return ManusPollOutcome.Errored("任务执行出错")
                        STATUS_DELETED -> return ManusPollOutcome.Deleted
                        STATUS_RUNNING -> { /* 继续轮询 */ }
                    }
                }
                is ManusResult.Failure -> return ManusPollOutcome.Failed(result.error)
            }
            if (isCancelled()) return ManusPollOutcome.Cancelled
            if (System.currentTimeMillis() >= deadline) return ManusPollOutcome.Timeout
            sleeper(pollIntervalMs)
        }
    }

    /**
     * 一次带认证的 Manus API 调用：组装 `x-manus-api-key` 请求头
     *（绝不用 Authorization Bearer）、发请求、按官方错误码分流。
     * 返回的 JSON 已校验 ok == true；失败只含错误分类，不含密钥与正文。
     */
    private fun requestJson(
        method: String,
        path: String,
        apiKey: String,
        baseUrl: String?,
        body: String?,
    ): ManusResult<JSONObject> {
        if (apiKey.isBlank()) {
            return ManusResult.Failure(ManusError.InvalidArgument("未提供密钥。"))
        }
        val base = manusBaseUrl(baseUrl)
        if (!base.startsWith("https://")) {
            return ManusResult.Failure(
                ManusError.NetworkError("服务地址只允许 https（Manus 云端 API）。"),
            )
        }
        val headers = mutableMapOf(
            "Accept" to "application/json",
            "x-manus-api-key" to apiKey,
        )
        if (body != null) headers["Content-Type"] = "application/json"
        val request = ManusHttpRequest(method, "$base$path", headers, body)
        val response = try {
            transport.call(request)
        } catch (e: Exception) {
            return ManusResult.Failure(ManusError.NetworkError(e.message ?: "网络错误"))
        }
        if (response.statusCode != 200) {
            return ManusResult.Failure(mapErrorResponse(response.statusCode, response.body))
        }
        val json = try {
            JSONObject(response.body)
        } catch (_: Exception) {
            return ManusResult.Failure(ManusError.InvalidResponse("响应不是 JSON"))
        }
        if (!json.optBoolean("ok", false)) {
            return ManusResult.Failure(mapErrorResponse(response.statusCode, response.body))
        }
        return ManusResult.Success(json)
    }

    /**
     * 一条消息带的产物附件（官方 Attachments 页）：先读与 type 同名的嵌套
     * 对象里的 `attachments`（`assistant_message.attachments`），没有再读
     * 顶层 `attachments`。v2 没有 file.list 端点，产物只从这里来。
     */
    private fun parseAttachments(nested: JSONObject?, item: JSONObject): List<ManusTaskAttachment> {
        val array = nested?.optJSONArray("attachments")
            ?: item.optJSONArray("attachments")
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { a ->
                ManusTaskAttachment(
                    filename = a.optString("filename").takeIf { it.isNotBlank() },
                    type = a.optString("type").takeIf { it.isNotBlank() },
                    url = a.optString("url").takeIf { it.isNotBlank() },
                    path = a.optString("path").takeIf { it.isNotBlank() },
                    contentType = a.optString("content_type").takeIf { it.isNotBlank() },
                    fileUid = a.optString("file_uid").takeIf { it.isNotBlank() },
                    versionUid = a.optString("version_uid").takeIf { it.isNotBlank() },
                )
            }
        }
    }

    /**
     * 一条事件消息的文本：按官方 v2 task.listMessages 格式（2026-11-11
     * 直读 live 文档复核），正文嵌在与 type 同名的对象里，不在顶层 content：
     * assistant_message.content / user_message.content /
     * error_message.content / explanation.content 是字符串；
     * status_update 取 brief（无则 description）；
     * tool_used 取 brief（无则 description）。
     * 顶层 content（字符串或 parts 数组）仅作兼容兜底——旧单测用的
     * 扁平格式与个别服务端变体可能仍有它。
     */
    private fun messageText(item: JSONObject): String? {
        val type = item.optString("type")
        // 先按 type 找同名嵌套对象里的正文（官方格式）。
        val nested = if (type.isNotBlank()) item.optJSONObject(type) else null
        if (nested != null) {
            // assistant_message / user_message / error_message / explanation：content 为字符串。
            nested.opt("content")?.let { content ->
                contentText(content)?.let { return it }
            }
            // status_update / tool_used：brief 为主，description 为辅。
            nested.optString("brief").takeIf { it.isNotBlank() }?.let { return it }
            nested.optString("description").takeIf { it.isNotBlank() }?.let { return it }
        }
        // 兜底：顶层 content（旧扁平格式；字符串或 parts 数组）。
        item.opt("content")?.let { content ->
            contentText(content)?.let { return it }
        }
        return null
    }

    /** content 字段的文本：字符串直接取；parts 数组拼各段 text；对象取其 text。 */
    private fun contentText(content: Any): String? {
        return when (content) {
            is String -> content.takeIf { it.isNotBlank() }
            is JSONArray -> (0 until content.length())
                .mapNotNull { content.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }
                .joinToString("\n").takeIf { it.isNotBlank() }
            is JSONObject -> content.optString("text").takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /**
     * 把错误响应映射为 [ManusError]：先看官方 error.code
     *（unauthenticated / permission_denied / not_found / rate_limited /
     * invalid_argument），再按 HTTP 状态码分流；正文只取 code/message
     * 两个字段，不贴完整正文。
     */
    internal fun mapErrorResponse(statusCode: Int, body: String): ManusError {
        val (code, message) = parseErrorBody(body)
        return when (code) {
            "unauthenticated" -> ManusError.Unauthorized
            "permission_denied" -> ManusError.Forbidden
            "not_found" -> ManusError.NotFound
            "rate_limited" -> ManusError.RateLimited
            "invalid_argument" -> ManusError.InvalidArgument(message.ifBlank { "参数无效" })
            else -> when (statusCode) {
                401 -> ManusError.Unauthorized
                403 -> ManusError.Forbidden
                404 -> ManusError.NotFound
                429 -> ManusError.RateLimited
                // 未知 code 原样保留在 detail 里（如以后新增的额度类错误码），
                // 不吞掉——官方错误表里没有专门的额度耗尽码，真实码须能被看见。
                else -> ManusError.HttpError(
                    statusCode,
                    listOfNotNull(code, message.ifBlank { null }).joinToString(": "),
                )
            }
        }
    }

    private fun parseErrorBody(body: String): Pair<String?, String> {
        return try {
            val error = JSONObject(body).optJSONObject("error") ?: return null to ""
            error.optString("code").ifBlank { null } to error.optString("message")
        } catch (_: Exception) {
            null to ""
        }
    }
}

/**
 * 默认 Manus 只读探针（供 [AgentConnectionTester] 注入）：
 * GET /v2/user.me + `x-manus-api-key`。无密钥时不发请求。
 */
fun defaultManusProbe(baseUrl: String, secret: String?): ManusResult<ManusAuthInfo> {
    if (secret.isNullOrBlank()) {
        return ManusResult.Failure(ManusError.InvalidArgument("未提供密钥，无法测试。"))
    }
    return ManusTaskAdapter().testAuth(secret, baseUrl)
}
