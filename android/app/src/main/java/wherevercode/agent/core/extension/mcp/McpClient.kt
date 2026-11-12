package wherevercode.agent.core.extension.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import wherevercode.agent.core.extension.mcp.McpJsonRpc.McpNotification

/**
 * MCP 客户端（自主按 JSON-RPC 2.0 实现，不引 MCP SDK）。
 *
 * 协议面 MCP 2025-11-25（连接器设计报告、SPEC §2.1）：
 * 一次会话：initialize → notifications/initialized → tools/list →
 * tools/call（可多次）→ close。三大原语全实现：
 * - 工具：分页 tools/list、tools/call；保留 annotations/outputSchema、
 *   structuredContent 和完整多模态 content blocks。
 * - 资源：分页 resources/list、resources/templates/list、resources/read、
 *   resources/subscribe / resources/unsubscribe。
 * - 提示：分页 prompts/list、prompts/get（模板 + 参数 → messages[]）。
 * - Argument completion：completion/complete。
 * - 通知：服务端发来的 notifications/…（目录变化、资源更新）由
 *   传输层交给本类的回调：tools/list_changed → 自动重拉工具；
 *   resources/list_changed → 自动重拉资源；prompts/list_changed →
 *   自动重拉提示；resources/updated → 交给 [onResourceUpdated]；
 *   notifications/progress 根据 progressToken 回传给在途工具调用。
 *
 * 能力门（主流做法，实测验证：官方 filesystem 服务器对未声明的
 * prompts/resources/subscribe 直接回 -32601）：先看 initialize
 * 回包的 capabilities，没声明的原语不调；调了还回 -32601 时按
 * “该原语不存在”处理（返回空列表 / 抛 mcp_not_supported），不当
 * 致命错误。老服务器（如 Hand 的 2024-11-05）只声明 tools，也能
 * 正常用，不会被新原语拖垮。
 *
 * 协议版本：先报 2025-11-25；服务端回的版本如实记录，不合已知版本
 * 也不拒——新版协议向前兼容时应能继续用。
 *
 * 会话过期（Streamable HTTP 的 404）：[requestWithSessionRetry] 会
 * 重新 initialize 一次再重试本次请求（只重试一次）。
 *
 * 超时、取消与重试（2026-11-13，MCP 能力补齐）：
 * - 每次调用都可带 [DEFAULT_TIMEOUT_MS] 起的 `timeoutMs`（透传给
 *   传输层的读超时；取消是协作式的——取消只保证调用方不再等结果，
 *   底层阻塞读由读超时兜底，不会无限挂起）。
 * - 幂等读（ping / tools/list / resources/list / prompts/list）遇
 *   瞬时传输失败（连接失败、超时、HTTP 层错误、发送失败）自动重试
 *   一次（500ms 后）；取消从不重试，会话过期走已有的重建一次。
 * - `tools/call` 绝不自动重试：建任务、发消息这类调用不是幂等的，
 *   重试可能造成重复执行；失败时把真实错误原样抛给调用方，由用户
 *   或调用方决定是否重发。
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    /** 人类可读的显示名（2025-11-25 新增字段；没有时为空）。 */
    val title: String = "",
    /** Server supplied MCP annotations; retained so approval/UI policy can inspect them. */
    val annotations: JSONObject? = null,
    /** Optional result schema advertised by the server. */
    val outputSchema: JSONObject? = null,
)

data class McpToolResult(
    val text: String,
    val isError: Boolean,
    /** Preserve every content block, including image/audio/resource blocks. */
    val content: List<JSONObject> = emptyList(),
    /** Structured tool output from servers using the newer MCP result shape. */
    val structuredContent: JSONObject? = null,
) {
    /** Keep the legacy plain-text path, while retaining rich blocks for Agent consumers. */
    fun toAgentData(): String {
        val hasRichContent = content.any { it.optString("type", "text") != "text" }
        if (!hasRichContent && structuredContent == null) return text
        return JSONObject().apply {
            put("text", text)
            put("content", org.json.JSONArray().apply { content.forEach { put(JSONObject(it.toString())) } })
            structuredContent?.let { put("structuredContent", JSONObject(it.toString())) }
        }.toString()
    }
}

data class McpServerInfo(
    val name: String,
    val version: String,
    val protocolVersion: String,
    /** initialize 回包的 capabilities 原样保留（能力门用）。 */
    val capabilities: JSONObject? = null,
)

data class McpResource(
    val uri: String,
    val name: String,
    val title: String = "",
    val description: String = "",
    val mimeType: String = "",
)

data class McpResourceTemplate(
    val uriTemplate: String,
    val name: String,
    val title: String = "",
    val description: String = "",
    val mimeType: String = "",
)

data class McpResourceContent(
    val uri: String,
    val mimeType: String = "",
    val text: String? = null,
    /** 二进制内容（base64，`blob` 字段；文本内容时为空）。 */
    val blob: String? = null,
)

data class McpPromptArgument(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
)

data class McpPrompt(
    val name: String,
    val description: String = "",
    val arguments: List<McpPromptArgument> = emptyList(),
)

data class McpPromptMessage(
    val role: String,
    val text: String,
    /** Full prompt content, retained for image/audio/resource content blocks. */
    val content: JSONObject? = null,
)

data class McpPromptResult(
    val description: String = "",
    val messages: List<McpPromptMessage> = emptyList(),
)

data class McpProgress(
    val progressToken: String,
    val progress: Double,
    val total: Double? = null,
    val message: String? = null,
)

data class McpCompletion(
    val values: List<String>,
    val total: Int? = null,
    val hasMore: Boolean = false,
)

class McpClient(
    private val transport: McpTransport,
    private val clientName: String = CLIENT_NAME,
    private val clientVersion: String = CLIENT_VERSION,
    scope: CoroutineScope? = null,
    /**
     * Roots 提供方（MCP 2025-11-25）：给了才在 initialize 的
     * capabilities 里声明 roots，服务端问 `roots/list` 时按它应答；
     * 不给就不声明，行为与原来一致（服务端不会问）。
     */
    private val rootsProvider: McpRootsProvider? = null,
) {
    /** 通知刷新用的协程域：没传就自己建一个，close() 时一起取消；外传的不归我管。 */
    private val ownedScope: Boolean = scope == null
    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var serverInfo: McpServerInfo? = null
        private set
    var tools: List<McpTool> = emptyList()
        private set
    var resources: List<McpResource> = emptyList()
        private set
    var resourceTemplates: List<McpResourceTemplate> = emptyList()
        private set
    var prompts: List<McpPrompt> = emptyList()
        private set

    /** 收到服务端通知时调（先于内部自动刷新；管理器/UI 可据此更新）。 */
    var onNotification: ((McpNotification) -> Unit)? = null

    /** 目录自动刷新完成时调（通知触发的重拉结束后；管理器据此同步缓存）。 */
    var onCatalogRefreshed: (() -> Unit)? = null

    /** 已订阅资源的内容变了（notifications/resources/updated 的 uri）。 */
    var onResourceUpdated: ((String) -> Unit)? = null

    /** MCP progress notifications for calls that supplied a progress callback. */
    var onProgress: ((McpProgress) -> Unit)? = null

    private val progressSequence = AtomicLong(0)
    private val activeProgressCallbacks = ConcurrentHashMap<String, (McpProgress) -> Unit>()

    /** 订阅中的资源 URI（resources/subscribe 成功后记在这里）。 */
    val subscribedResources: Set<String>
        get() = subscribed.toSet()
    private val subscribed = mutableSetOf<String>()

    /**
     * 建连：initialize 后发 notifications/initialized（不回包）。
     * 返回服务端的 serverInfo。传输/协议失败抛 [McpException]。
     */
    suspend fun initialize(): McpServerInfo {
        transport.setNotificationListener { notification -> handleNotification(notification) }
        transport.setServerRequestListener { request -> handleServerRequest(request) }
        transport.start()
        val params = JSONObject().apply {
            put("protocolVersion", PROTOCOL_VERSION)
            val capabilities = JSONObject()
            if (rootsProvider != null) {
                // 声明 roots：服务端随后可发 roots/list 来问根目录。
                capabilities.put("roots", JSONObject().apply {
                    put("listChanged", true)
                })
            }
            put("capabilities", capabilities)
            put("clientInfo", JSONObject().apply {
                put("name", clientName)
                put("version", clientVersion)
            })
        }
        val result = requestWithSessionRetry("initialize", params) as? JSONObject
            ?: throw McpException("initialize 回包不是对象", "mcp_bad_response")
        val info = result.optJSONObject("serverInfo")
        val server = McpServerInfo(
            name = info?.optString("name", "") ?: "",
            version = info?.optString("version", "") ?: "",
            protocolVersion = result.optString("protocolVersion", ""),
            capabilities = result.optJSONObject("capabilities"),
        )
        serverInfo = server
        transport.notify("notifications/initialized", JSONObject())
        return server
    }

    suspend fun ping(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        retryIdempotentRead(timeoutMs) { t -> requestWithSessionRetry("ping", JSONObject(), t) }
    }

    /**
     * 发一条原始 JSON-RPC 请求并返回 result（不解析成具体类型）。
     * 供测试钩子（如本仓库 minimal_mcp_server.py 的 test/… 方法）
     * 与未来扩展方法调用；服务端回 -32601 等 RPC 错误时抛
     * [McpException]（code=mcp_rpc_error）。会话过期同样自动重建
     * 一次再重试。原始请求不自动重试（调用方自担幂等性）。
     */
    suspend fun rawRequest(
        method: String,
        params: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Any? =
        requestWithSessionRetry(method, McpJsonRpc.mapToJson(params), timeoutMs)

    /** 列出工具；结果缓存到 [tools]。幂等读：瞬时传输失败重试一次。 */
    suspend fun listTools(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<McpTool> {
        val parsed = collectPages("tools/list", "tools", timeoutMs) { item ->
            val name = item.optString("name", "")
            if (name.isBlank()) return@collectPages null
            McpTool(
                name = name,
                description = item.optString("description", ""),
                inputSchema = item.optJSONObject("inputSchema") ?: JSONObject(),
                title = item.optString("title", ""),
                annotations = item.optJSONObject("annotations"),
                outputSchema = item.optJSONObject("outputSchema"),
            )
        }
        tools = parsed
        return parsed
    }

    /**
     * 调一个工具。isError=true 是工具自己的失败（参数/权限/不存在），
     * 以结果返回，不抛错；传输与 JSON-RPC 层失败抛 [McpException]。
     *
     * 绝不自动重试（见类注释）：建任务、发消息等调用不是幂等的。
     * 取消（[CancellationException]）原样传播，不转成失败结果。
     */
    suspend fun callTool(
        name: String,
        arguments: Map<String, Any?>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        progress: ((McpProgress) -> Unit)? = null,
    ): McpToolResult {
        val progressToken = progress?.let {
            "${clientName}-${progressSequence.incrementAndGet()}".also { token ->
                activeProgressCallbacks[token] = it
            }
        }
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", McpJsonRpc.mapToJson(arguments))
            progressToken?.let { token -> put("_meta", JSONObject().put("progressToken", token)) }
        }
        val result = try {
            requestWithSessionRetry("tools/call", params, timeoutMs) as? JSONObject
                ?: throw McpException("tools/call 回包不是对象", "mcp_bad_response")
        } finally {
            progressToken?.let(activeProgressCallbacks::remove)
        }
        val content = result.optJSONArray("content")
        val blocks = if (content == null) emptyList() else (0 until content.length()).mapNotNull { i ->
            content.optJSONObject(i)?.let { JSONObject(it.toString()) }
        }
        val text = buildString {
            for (part in blocks) {
                if (part.optString("type", "") == "text") {
                    if (isNotEmpty()) append("\n")
                    append(part.optString("text", ""))
                }
            }
        }
        return McpToolResult(
            text = text,
            isError = result.optBoolean("isError", false),
            content = blocks,
            structuredContent = result.optJSONObject("structuredContent")?.let { JSONObject(it.toString()) },
        )
    }

    // -- 资源（resources）：MCP 三大原语之一 ---------------------------

    /** 服务端是否声明了 resources 能力（没声明就不调，见类注释）。 */
    fun supportsResources(): Boolean = serverInfo?.capabilities?.has("resources") == true

    /** 服务端是否声明了 resources.subscribe（可订阅资源更新）。 */
    fun supportsResourceSubscribe(): Boolean {
        val resources = serverInfo?.capabilities?.optJSONObject("resources") ?: return false
        return resources.optBoolean("subscribe", false)
    }

    /** 列出资源；结果缓存到 [resources]。服务端没声明该能力时直接返回空列表。幂等读：瞬时传输失败重试一次。 */
    suspend fun listResources(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<McpResource> {
        if (!supportsResources()) return emptyList<McpResource>().also { resources = it }
        val parsed = try {
            collectPages("resources/list", "resources", timeoutMs) { item ->
                val uri = item.optString("uri", "")
                if (uri.isBlank()) null else McpResource(
                    uri = uri,
                    name = item.optString("name", ""),
                    title = item.optString("title", ""),
                    description = item.optString("description", ""),
                    mimeType = item.optString("mimeType", ""),
                )
            }
        } catch (e: McpException) {
            if (e.code == "mcp_rpc_error") return emptyList<McpResource>().also { resources = it }
            throw e
        }
        resources = parsed
        return parsed
    }

    /** List URI templates with the same cursor handling as resources/list. */
    suspend fun listResourceTemplates(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<McpResourceTemplate> {
        if (!supportsResources()) return emptyList<McpResourceTemplate>().also { resourceTemplates = it }
        val parsed = try {
            collectPages("resources/templates/list", "resourceTemplates", timeoutMs) { item ->
                val uriTemplate = item.optString("uriTemplate", "")
                if (uriTemplate.isBlank()) null else McpResourceTemplate(
                    uriTemplate = uriTemplate,
                    name = item.optString("name", ""),
                    title = item.optString("title", ""),
                    description = item.optString("description", ""),
                    mimeType = item.optString("mimeType", ""),
                )
            }
        } catch (e: McpException) {
            if (e.code == "mcp_rpc_error") return emptyList<McpResourceTemplate>().also { resourceTemplates = it }
            throw e
        }
        resourceTemplates = parsed
        return parsed
    }

    /** 读一个资源的内容（`contents[]`，文本进 text、二进制进 blob）。 */
    suspend fun readResource(uri: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<McpResourceContent> {
        val params = JSONObject().apply { put("uri", uri) }
        val result = requestWithSessionRetry("resources/read", params, timeoutMs) as? JSONObject
            ?: throw McpException("resources/read 回包不是对象", "mcp_bad_response")
        val array = result.optJSONArray("contents") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val contentUri = item.optString("uri", "")
            if (contentUri.isBlank()) return@mapNotNull null
            McpResourceContent(
                uri = contentUri,
                mimeType = item.optString("mimeType", ""),
                text = item.optString("text", "").takeIf { item.has("text") },
                blob = item.optString("blob", "").takeIf { item.has("blob") },
            )
        }
    }

    /** 订阅一个资源：内容变时服务端发 notifications/resources/updated。 */
    suspend fun subscribeResource(uri: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        if (!supportsResourceSubscribe()) {
            throw McpException("MCP 服务器不支持订阅资源（未声明 resources.subscribe）", "mcp_not_supported")
        }
        val params = JSONObject().apply { put("uri", uri) }
        try {
            requestWithSessionRetry("resources/subscribe", params, timeoutMs)
        } catch (e: McpException) {
            if (e.code == "mcp_rpc_error") {
                throw McpException("MCP 服务器不支持订阅资源（resources/subscribe 被拒）", "mcp_not_supported")
            }
            throw e
        }
        subscribed.add(uri)
    }

    /** 取消订阅一个资源。 */
    suspend fun unsubscribeResource(uri: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        if (!supportsResourceSubscribe()) {
            subscribed.remove(uri)
            return
        }
        val params = JSONObject().apply { put("uri", uri) }
        try {
            requestWithSessionRetry("resources/unsubscribe", params, timeoutMs)
        } catch (e: McpException) {
            if (e.code != "mcp_rpc_error") throw e
            // -32601：服务端不支持，本地照样取消订阅。
        }
        subscribed.remove(uri)
    }

    // -- 提示（prompts）：MCP 三大原语之一 ----------------------------

    /** 服务端是否声明了 prompts 能力（没声明就不调，见类注释）。 */
    fun supportsPrompts(): Boolean = serverInfo?.capabilities?.has("prompts") == true

    /** 列出提示模板；结果缓存到 [prompts]。服务端没声明该能力时直接返回空列表。幂等读：瞬时传输失败重试一次。 */
    suspend fun listPrompts(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<McpPrompt> {
        if (!supportsPrompts()) return emptyList<McpPrompt>().also { prompts = it }
        val parsed = try {
            collectPages("prompts/list", "prompts", timeoutMs) { item ->
                val name = item.optString("name", "")
                if (name.isBlank()) return@collectPages null
                val argsJson = item.optJSONArray("arguments")
                val args = if (argsJson == null) {
                    emptyList()
                } else {
                    (0 until argsJson.length()).mapNotNull { j ->
                        val arg = argsJson.optJSONObject(j) ?: return@mapNotNull null
                        val argName = arg.optString("name", "")
                        if (argName.isBlank()) return@mapNotNull null
                        McpPromptArgument(
                            name = argName,
                            description = arg.optString("description", ""),
                            required = arg.optBoolean("required", false),
                        )
                    }
                }
                McpPrompt(
                    name = name,
                    description = item.optString("description", ""),
                    arguments = args,
                )
            }
        } catch (e: McpException) {
            if (e.code == "mcp_rpc_error") return emptyList<McpPrompt>().also { prompts = it }
            throw e
        }
        prompts = parsed
        return parsed
    }

    /** 取一个提示模板（填好参数，返回 messages[]）。 */
    suspend fun getPrompt(
        name: String,
        arguments: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): McpPromptResult {
        val params = JSONObject().apply {
            put("name", name)
            put("arguments", McpJsonRpc.mapToJson(arguments))
        }
        val result = requestWithSessionRetry("prompts/get", params, timeoutMs) as? JSONObject
            ?: throw McpException("prompts/get 回包不是对象", "mcp_bad_response")
        val messagesJson = result.optJSONArray("messages")
        val messages = if (messagesJson == null) {
            emptyList()
        } else {
            (0 until messagesJson.length()).mapNotNull { i ->
                val item = messagesJson.optJSONObject(i) ?: return@mapNotNull null
                val role = item.optString("role", "")
                val content = item.optJSONObject("content") ?: return@mapNotNull null
                val text = if (content.optString("type", "") == "text") {
                    content.optString("text", "")
                } else {
                    ""
                }
                if (role.isBlank()) return@mapNotNull null
                McpPromptMessage(role = role, text = text, content = JSONObject(content.toString()))
            }
        }
        return McpPromptResult(
            description = result.optString("description", ""),
            messages = messages,
        )
    }

    /** Request MCP completion suggestions for a prompt or resource argument. */
    suspend fun complete(
        reference: JSONObject,
        argumentName: String,
        argumentValue: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): McpCompletion {
        require(argumentName.isNotBlank()) { "completion argument name is required" }
        require(reference.has("type")) { "completion reference type is required" }
        val params = JSONObject().apply {
            put("ref", JSONObject(reference.toString()))
            put("argument", JSONObject().put("name", argumentName).put("value", argumentValue))
        }
        val result = try {
            retryIdempotentRead(timeoutMs) { t ->
                requestWithSessionRetry("completion/complete", params, t) as? JSONObject
                    ?: throw McpException("completion/complete 回包不是对象", "mcp_bad_response")
            }
        } catch (e: McpException) {
            if (e.code == "mcp_rpc_error") throw McpException("MCP 服务器不支持 completion/complete", "mcp_not_supported")
            throw e
        }
        val completion = result.optJSONObject("completion") ?: JSONObject()
        val values = completion.optJSONArray("values")?.let { array ->
            (0 until array.length()).mapNotNull { i -> array.optString(i, "").takeIf(String::isNotEmpty) }
        } ?: emptyList()
        return McpCompletion(
            values = values,
            total = if (completion.has("total")) completion.optInt("total") else null,
            hasMore = completion.optBoolean("hasMore", false),
        )
    }

    // -- 通知 ----------------------------------------------------------

    /**
     * 服务端通知入口（传输层回调，非 suspend 上下文：内部用 [scope]
     * 起协程做需要等回包的刷新）。目录变化类通知触发自动重拉；其余
     * 原样交给 [onNotification]；资源更新另交 [onResourceUpdated]。
     */
    fun handleNotification(notification: McpNotification) {
        try {
            onNotification?.invoke(notification)
        } catch (_: Exception) {
        }
        when (notification.method) {
            "notifications/tools/list_changed" -> scope.launch {
                try {
                    listTools()
                } catch (_: Exception) {
                } finally {
                    try {
                        onCatalogRefreshed?.invoke()
                    } catch (_: Exception) {
                    }
                }
            }
            "notifications/resources/list_changed" -> scope.launch {
                try {
                    listResources()
                } catch (_: Exception) {
                } finally {
                    try {
                        onCatalogRefreshed?.invoke()
                    } catch (_: Exception) {
                    }
                }
            }
            "notifications/prompts/list_changed" -> scope.launch {
                try {
                    listPrompts()
                } catch (_: Exception) {
                } finally {
                    try {
                        onCatalogRefreshed?.invoke()
                    } catch (_: Exception) {
                    }
                }
            }
            "notifications/resources/updated" -> {
                val uri = notification.params?.optString("uri", "") ?: ""
                if (uri.isNotBlank()) {
                    try {
                        onResourceUpdated?.invoke(uri)
                    } catch (_: Exception) {
                    }
                }
            }
            "notifications/progress" -> {
                val params = notification.params ?: return
                val token = params.opt("progressToken")?.toString()?.takeIf { it.isNotBlank() } ?: return
                val progress = McpProgress(
                    progressToken = token,
                    progress = params.optDouble("progress", 0.0),
                    total = if (params.has("total")) params.optDouble("total") else null,
                    message = params.optString("message", "").takeIf { it.isNotBlank() },
                )
                try {
                    activeProgressCallbacks[token]?.invoke(progress)
                    onProgress?.invoke(progress)
                } catch (_: Exception) {
                }
            }
            else -> {
                // 其余通知（progress、message 等）：只转交 onNotification。
            }
        }
    }

    // -- 服务端请求 ------------------------------------------------------

    /**
     * 服务端发来的请求（有 id、有 method）。目前只实现 `roots/list`：
     * 按 [rootsProvider] 应答根目录；没声明 roots 或方法未知时回
     * -32601（Method not found），不断连、不静默丢弃——服务端在等
     * 这个请求的应答。
     *
     * 传输层在自己的读线程上调这里：应答走 [scope] 起协程发，不阻塞
     * 读线程；id 为空的请求无法应答，直接跳过。
     */
    fun handleServerRequest(request: McpJsonRpc.Response.ServerRequest) {
        val id = request.id ?: return
        scope.launch {
            try {
                when (request.method) {
                    "roots/list" -> {
                        val provider = rootsProvider
                        if (provider == null) {
                            transport.respondError(id, -32601, "Method not found: roots/list")
                            return@launch
                        }
                        val rootsJson = org.json.JSONArray().apply {
                            for (root in provider.roots()) {
                                if (root.uri.isBlank()) continue
                                put(JSONObject().apply {
                                    put("uri", root.uri)
                                    if (root.name.isNotBlank()) put("name", root.name)
                                })
                            }
                        }
                        transport.respond(id, JSONObject().apply {
                            put("roots", rootsJson)
                        })
                    }
                    else -> {
                        transport.respondError(id, -32601, "Method not found: ${request.method}")
                    }
                }
            } catch (_: Exception) {
                // 应答发不出去（如传输已断）：记不住、也不抛到读线程。
            }
        }
    }

    // -- 幂等读重试 ------------------------------------------------------

    /**
     * 幂等读（ping / tools/list / resources/list / prompts/list）的
     * 一次重试：瞬时传输失败（连接失败、超时、HTTP 层错误、发送失败）
     * 时 500ms 后再试一次；第二次还失败就原样抛。取消从不重试
     * （[CancellationException] 原样传播）；会话过期不在这里处理，
     * 由 [requestWithSessionRetry] 的重建会话负责；服务端 RPC 错误
     * （-32601 等，code=mcp_rpc_error）也不重试——重发救不回来。
     * tools/call 不走这里（非幂等，见类注释）。
     */
    private suspend fun <T> retryIdempotentRead(
        timeoutMs: Long,
        block: suspend (Long) -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block(timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: McpException) {
                if (e.code !in TRANSIENT_READ_RETRY_CODES || attempt >= 1) throw e
                attempt++
                delay(READ_RETRY_DELAY_MS)
            }
        }
    }

    /** Transparently follows MCP opaque cursors; repeated cursors fail closed. */
    private suspend fun <T> collectPages(
        method: String,
        arrayKey: String,
        timeoutMs: Long,
        parse: (JSONObject) -> T?,
    ): List<T> {
        val collected = mutableListOf<T>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(MAX_CATALOG_PAGES) {
            val params = JSONObject().apply { cursor?.let { put("cursor", it) } }
            val result = retryIdempotentRead(timeoutMs) { t ->
                requestWithSessionRetry(method, params, t) as? JSONObject
                    ?: throw McpException("$method 回包不是对象", "mcp_bad_response")
            }
            val array = result.optJSONArray(arrayKey)
            if (array != null) {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    parse(item)?.let(collected::add)
                }
            }
            val next = result.optString("nextCursor", "").takeIf { it.isNotBlank() }
            if (next == null) return collected
            if (!seenCursors.add(next)) throw McpException("$method 返回重复分页游标", "mcp_bad_pagination")
            cursor = next
        }
        throw McpException("$method 超过分页上限", "mcp_bad_pagination")
    }

    // -- 会话过期重试 ---------------------------------------------------

    /**
     * 发一条请求。Streamable HTTP 会话过期（mcp_session_expired）时
     * 重新 initialize 一次再重试本次请求（只重试一次；initialize 本身
     * 过期不重试，避免死循环）。
     */
    private suspend fun requestWithSessionRetry(method: String, params: JSONObject?, timeoutMillis: Long = 60_000): Any? {
        try {
            return transport.request(method, params, timeoutMillis)
        } catch (e: McpException) {
            if (e.code != "mcp_session_expired" || method == "initialize") throw e
            // 会话过期：重建会话（initialize 会重新拿会话 id、重启 GET 流）。
            initialize()
            return transport.request(method, params, timeoutMillis)
        }
    }

    suspend fun close() {
        try {
            transport.close()
        } catch (_: Exception) {
        }
        if (ownedScope) scope.cancel()
    }

    companion object {
        const val CLIENT_NAME: String = "wherevercode-agent"
        const val CLIENT_VERSION: String = "0.2.0-ui2"
        const val PROTOCOL_VERSION: String = "2025-11-25"
        /** 默认单次调用超时（毫秒）；传输层读超时与之同值。 */
        const val DEFAULT_TIMEOUT_MS: Long = 60_000
        /** 幂等读重试前等多久（毫秒）。 */
        const val READ_RETRY_DELAY_MS: Long = 500
        /** Prevent a malicious or broken server from making catalog sync unbounded. */
        const val MAX_CATALOG_PAGES: Int = 100
        /** 幂等读值得重试一次的瞬时传输失败码（会话过期除外，由重建会话负责）。 */
        val TRANSIENT_READ_RETRY_CODES: Set<String> = setOf(
            "mcp_connect_failed",
            "mcp_timeout",
            "mcp_http_error",
            "mcp_send_failed",
        )
        /** 见过的协议版本：其余版本不拒，只记录。 */
        val KNOWN_PROTOCOL_VERSIONS: Set<String> = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
    }
}
