package wherevercode.agent.core.extension.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import wherevercode.agent.brain.ToolDef
import wherevercode.agent.brain.ToolOutcome
import wherevercode.agent.core.extension.ExtensionEntry
import wherevercode.agent.core.extension.ExtensionKind
import wherevercode.agent.core.extension.ExtensionRegistry
import wherevercode.agent.core.extension.mcp.McpJsonRpc.toMap

/**
 * MCP 服务器管理器：注册表里的 kind=mcp 行 ↔ 活的 [McpClient]。
 *
 * - 配置只认注册表（id / transport / command / url / enabled），不在
 *   别处另起一份；启用开关改注册表，断开随停用走。
 * - 工具只在真实连通后才出现：[toolDefs] 只含已连接服务器缓存下来
 *   的工具；没连过的服务器不向大脑列工具，不伪装在线。
 * - 连接是显式动作（屏幕上的“连接”/“刷新”触发），启动时不自动连——
 *   stdio 会拉起子进程、HTTP 会出网，都不该在用户没点之前发生。
 * - 工具名进大脑时加前缀 `mcp_<serverId>_<tool>`（serverId 里的 `-`
 *   转 `_`；OpenAI 兼容接口的函数名只允许字母数字下划线与连字符，
 *   冒号不允许，故不用 `mcp:server:tool` 形态）。
 *
 * 只读判定（本版规则，与 Hand MCP 工具的语义一致）：list / read
 * 是只读（自动执行、可缓存）；其余一律走审批（write / run 会改
 * 设备上的文件或派 Agent 执行，必须用户确认）。
 */
enum class McpServerState {
    /** 从未连过（或 App 重启后尚未重连）。 */
    UNKNOWN,

    /** 已 initialize + tools/list 成功，工具缓存有效。 */
    CONNECTED,

    /** 最近一次连接失败（原因见 [McpServerStatus.lastError]）。 */
    FAILED,
}

data class McpServerStatus(
    val state: McpServerState,
    val toolCount: Int = 0,
    val resourceCount: Int = 0,
    val promptCount: Int = 0,
    val lastError: String? = null,
    val lastCheckedAtEpochMillis: Long? = null,
    val serverName: String? = null,
    val protocolVersion: String? = null,
    val resourceTemplateCount: Int = 0,
)

data class McpServerView(
    val entry: ExtensionEntry,
    val status: McpServerStatus,
)

/** 大脑工具链看到的 MCP 工具来源（测试可替身，见 McpClientTest 之外的桥接测试）。 */
interface McpToolSource {
    fun toolDefs(): List<ToolDef>
    fun isReadOnly(toolName: String): Boolean
    fun needsApproval(toolName: String): Boolean
    suspend fun execute(toolName: String, args: Map<String, Any?>): ToolOutcome
}

class McpServerManager(
    private val registry: ExtensionRegistry,
    private val credentialResolver: McpCredentialResolver = NoopMcpCredentialResolver,
    private val authProviderFactory: ((ExtensionEntry) -> McpAuthProvider?)? = null,
    /**
     * Roots 提供方（MCP 2025-11-25）：给了就透传给每个新建的客户端，
     * 客户端在 initialize 里声明 roots、按它应答 roots/list。
     * 不给就不声明，行为与原来一致。
     */
    private val rootsProvider: McpRootsProvider? = null,
    private val clientFactory: (ExtensionEntry) -> McpClient = { entry ->
        defaultClient(entry, credentialResolver, authProviderFactory, rootsProvider)
    },
    /**
     * 断线重连跑在哪个协程域上：不传就自建一个（closeAll() 时不取消，
     * 管理器本身不持有 Activity 生命期；重连任务随停用/断开/重连
     * 成功而取消，不泄漏）。测试可传自己的域。
     */
    scope: CoroutineScope? = null,
    /** 断线重连的退避间隔（第 1..N 次）；超过 N 次就停，不无限重试。 */
    private val reconnectDelays: List<Long> = ReconnectPolicy.DEFAULT_DELAYS,
    /**
     * 大脑调工具的单次超时（毫秒；2026-11-13 补齐）：透传给
     * [McpClient.callTool]（传输层读超时同值）。超时后按真实错误
     * 返回，不自动重发（tools/call 非幂等）；传输层掉线才走断线重连。
     */
    private val toolCallTimeoutMs: Long = McpClient.DEFAULT_TIMEOUT_MS,
) : McpToolSource {

    private val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = mutableMapOf<String, McpClient>()
    private val cachedTools = mutableMapOf<String, List<McpTool>>()
    private val cachedResources = mutableMapOf<String, List<McpResource>>()
    private val cachedResourceTemplates = mutableMapOf<String, List<McpResourceTemplate>>()
    private val cachedPrompts = mutableMapOf<String, List<McpPrompt>>()
    private val statuses = mutableMapOf<String, McpServerStatus>()
    /** 待重连的任务（serverId → Job）；重连成功/停用/断开时取消。 */
    private val reconnectJobs = mutableMapOf<String, Job>()

    /** 已订阅资源的内容变了（服务端 notifications/resources/updated）。 */
    var onResourceUpdated: ((serverId: String, uri: String) -> Unit)? = null
    var onProgress: ((serverId: String, progress: McpProgress) -> Unit)? = null

    fun servers(): List<McpServerView> =
        registry.all(ExtensionKind.MCP).map { entry ->
            McpServerView(entry, statuses[entry.id] ?: McpServerStatus(McpServerState.UNKNOWN))
        }

    fun statusOf(serverId: String): McpServerStatus =
        statuses[serverId] ?: McpServerStatus(McpServerState.UNKNOWN)

    fun isConnected(serverId: String): Boolean =
        statusOf(serverId).state == McpServerState.CONNECTED && clients.containsKey(serverId)

    /**
     * 连接一台服务器：initialize → tools/list → resources/list →
     * prompts/list，全程真实走协议。成功缓存工具/资源/提示与状态；
     * 失败记 FAILED + 原因，旧缓存清掉（不拿旧工具冒充在线）。
     * resources/prompts 是能力门的：服务端没声明就不调（返回空列表，
     * 不拖垮连接）；服务端谎报能力回 -32601 时也按空列表处理。
     * 连上后客户端收到目录变化通知会自动重拉，管理器通过
     * onCatalogRefreshed 把缓存同步回来（见 [syncCaches]）。
     */
    suspend fun connect(serverId: String): McpServerStatus {
        val entry = registry.get(ExtensionKind.MCP, serverId)
            ?: return McpServerStatus(McpServerState.FAILED, lastError = "注册表里没有这个 MCP 服务器：$serverId")
                .also { statuses[serverId] = it }
        if (!entry.enabled) {
            return McpServerStatus(McpServerState.FAILED, lastError = "服务器未启用")
                .also { statuses[serverId] = it }
        }
        disconnectQuietly(serverId)
        val client = try {
            clientFactory(entry)
        } catch (e: Exception) {
            return failed(serverId, "创建 MCP 客户端失败：${e.message ?: "未知错误"}")
        }
        val status = try {
            val info = client.initialize()
            val tools = client.listTools()
            val resources = try {
                client.listResources()
            } catch (_: Exception) {
                emptyList()
            }
            val resourceTemplates = try {
                client.listResourceTemplates()
            } catch (_: Exception) {
                emptyList()
            }
            val prompts = try {
                client.listPrompts()
            } catch (_: Exception) {
                emptyList()
            }
            clients[serverId] = client
            cachedTools[serverId] = tools
            cachedResources[serverId] = resources
            cachedResourceTemplates[serverId] = resourceTemplates
            cachedPrompts[serverId] = prompts
            client.onCatalogRefreshed = { syncCaches(serverId, client) }
            client.onResourceUpdated = { uri ->
                try {
                    onResourceUpdated?.invoke(serverId, uri)
                } catch (_: Exception) {
                }
            }
            McpServerStatus(
                state = McpServerState.CONNECTED,
                toolCount = tools.size,
                resourceCount = resources.size,
                promptCount = prompts.size,
                resourceTemplateCount = resourceTemplates.size,
                lastCheckedAtEpochMillis = System.currentTimeMillis(),
                serverName = info.name,
                protocolVersion = info.protocolVersion,
            )
        } catch (e: McpException) {
            try {
                client.close()
            } catch (_: Exception) {
            }
            failed(serverId, e.message ?: "连接失败")
        } catch (e: Exception) {
            try {
                client.close()
            } catch (_: Exception) {
            }
            failed(serverId, "连接失败：${e.message ?: "未知错误"}")
        }
        statuses[serverId] = status
        if (status.state == McpServerState.CONNECTED) cancelReconnect(serverId)
        return status
    }

    /**
     * 把一台已连服务器的客户端缓存同步回管理器（通知触发自动重拉后
     * 调）。只同步，不重新拉取；客户端已断开时不同步。
     */
    fun syncCaches(serverId: String, client: McpClient) {
        if (!clients.containsKey(serverId)) return
        cachedTools[serverId] = client.tools
        cachedResources[serverId] = client.resources
        cachedResourceTemplates[serverId] = client.resourceTemplates
        cachedPrompts[serverId] = client.prompts
        val old = statuses[serverId] ?: return
        statuses[serverId] = old.copy(
            toolCount = client.tools.size,
            resourceCount = client.resources.size,
            promptCount = client.prompts.size,
            resourceTemplateCount = client.resourceTemplates.size,
            lastCheckedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    /** 已连服务器缓存的资源（未连接时为空列表）。 */
    fun resources(serverId: String): List<McpResource> =
        cachedResources[serverId] ?: emptyList()

    /** 已连服务器缓存的资源 URI 模板（未连接时为空列表）。 */
    fun resourceTemplates(serverId: String): List<McpResourceTemplate> =
        cachedResourceTemplates[serverId] ?: emptyList()

    /** 已连服务器缓存的提示模板（未连接时为空列表）。 */
    fun prompts(serverId: String): List<McpPrompt> =
        cachedPrompts[serverId] ?: emptyList()

    /** 读一台已连服务器上的资源内容。 */
    suspend fun readResource(serverId: String, uri: String): List<McpResourceContent> {
        val client = clients[serverId]
            ?: throw McpException("MCP 服务器未连接：$serverId（请先在扩展页连接）", "mcp_not_connected")
        return client.readResource(uri)
    }

    /** 取一台已连服务器上的提示模板（填好参数）。 */
    suspend fun getPrompt(serverId: String, name: String, arguments: Map<String, Any?> = emptyMap()): McpPromptResult {
        val client = clients[serverId]
            ?: throw McpException("MCP 服务器未连接：$serverId（请先在扩展页连接）", "mcp_not_connected")
        return client.getPrompt(name, arguments)
    }

    suspend fun complete(
        serverId: String,
        reference: org.json.JSONObject,
        argumentName: String,
        argumentValue: String,
    ): McpCompletion {
        val client = clients[serverId]
            ?: throw McpException("MCP 服务器未连接：$serverId（请先在扩展页连接）", "mcp_not_connected")
        return client.complete(reference, argumentName, argumentValue)
    }

    /** 订阅一台已连服务器上的资源（内容变时 onResourceUpdated 回调）。 */
    suspend fun subscribeResource(serverId: String, uri: String) {
        val client = clients[serverId]
            ?: throw McpException("MCP 服务器未连接：$serverId（请先在扩展页连接）", "mcp_not_connected")
        client.subscribeResource(uri)
    }

    /** 取消订阅一台已连服务器上的资源。 */
    suspend fun unsubscribeResource(serverId: String, uri: String) {
        val client = clients[serverId] ?: return
        client.unsubscribeResource(uri)
    }

    private fun failed(serverId: String, message: String): McpServerStatus {
        cachedTools.remove(serverId)
        cachedResources.remove(serverId)
        cachedResourceTemplates.remove(serverId)
        cachedPrompts.remove(serverId)
        clients.remove(serverId)
        return McpServerStatus(
            state = McpServerState.FAILED,
            lastError = message,
            lastCheckedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    suspend fun disconnect(serverId: String) {
        disconnectQuietly(serverId)
        statuses[serverId] = McpServerStatus(McpServerState.UNKNOWN)
    }

    private suspend fun disconnectQuietly(serverId: String) {
        cancelReconnect(serverId)
        val client = clients.remove(serverId)
        cachedTools.remove(serverId)
        cachedResources.remove(serverId)
        cachedResourceTemplates.remove(serverId)
        cachedPrompts.remove(serverId)
        if (client != null) {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    suspend fun closeAll() {
        for (serverId in clients.keys.toList()) disconnectQuietly(serverId)
        statuses.clear()
    }

    /** 改启用开关；停用即断开。 */
    suspend fun setEnabled(serverId: String, enabled: Boolean): Boolean {
        if (!enabled) disconnectQuietly(serverId)
        if (!enabled) statuses[serverId] = McpServerStatus(McpServerState.UNKNOWN)
        return registry.setEnabled(ExtensionKind.MCP, serverId, enabled)
    }

    // -- 断线自动重连 ---------------------------------------------------

    /**
     * 断线后自动重连（只针对“已连上、后来掉线”的服务器）。
     *
     * 触发：大脑调工具时传输层报错（见 [TRANSPORT_FAILURE_CODES]），
     * 或用户手动断开后不触发（断开即取消）。退避见 [ReconnectPolicy]：
     * 5 秒 → 15 秒 → 30 秒 → 60 秒 → 60 秒，共 5 次；5 次都连不上
     * 就停，状态保持 FAILED + 原因，不无限重试、不偷偷烧流量。
     * 停用/删除/手动断开/重连成功都会取消待重连；重启后不自动重连
     * （连接是显式动作，stdio 会拉子进程、HTTP 会出网，不在用户
     * 没点之前发生——此前的设计不变）。
     */
    fun scheduleReconnect(serverId: String) {
        if (reconnectJobs.containsKey(serverId)) return
        val entry = registry.get(ExtensionKind.MCP, serverId) ?: return
        if (!entry.enabled) return
        val job = scope.launch {
            try {
                var attempt = 0
                while (true) {
                    val delayMs = ReconnectPolicy.nextDelay(attempt, reconnectDelays) ?: break
                    delay(delayMs)
                    val current = registry.get(ExtensionKind.MCP, serverId) ?: break
                    if (!current.enabled) break
                    attempt++
                    val status = try {
                        connect(serverId)
                    } catch (_: Exception) {
                        McpServerStatus(McpServerState.FAILED, lastError = "重连失败")
                    }
                    if (status.state == McpServerState.CONNECTED) break
                }
            } finally {
                reconnectJobs.remove(serverId)
            }
        }
        reconnectJobs[serverId] = job
    }

    /** 取消一台服务器的待重连（停用/断开/重连成功时调）。 */
    fun cancelReconnect(serverId: String) {
        reconnectJobs.remove(serverId)?.cancel()
    }

    /** 是否正在等断线重连（UI 可据此显示“重连中”，测试用）。 */
    fun isReconnectPending(serverId: String): Boolean =
        reconnectJobs[serverId]?.isActive == true

    // -- McpToolSource：大脑工具链的视角 -------------------------------

    override fun toolDefs(): List<ToolDef> {
        val defs = mutableListOf<ToolDef>()
        for ((serverId, tools) in cachedTools) {
            val entry = registry.get(ExtensionKind.MCP, serverId) ?: continue
            if (!entry.enabled) continue
            if (statusOf(serverId).state != McpServerState.CONNECTED) continue
            for (tool in tools) {
                val schema = with(McpJsonRpc) { tool.inputSchema.toMap() }
                defs += ToolDef(
                    name = toolNameFor(serverId, tool.name),
                    description = "MCP ${entry.displayName} · ${tool.description}",
                    inputSchema = schema,
                )
            }
        }
        return defs
    }

    override fun isReadOnly(toolName: String): Boolean {
        val (_, tool) = parseToolName(toolName, cachedTools.keys) ?: return false
        return tool in READ_ONLY_TOOLS
    }

    override fun needsApproval(toolName: String): Boolean = !isReadOnly(toolName)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolOutcome {
        val parsed = parseToolName(toolName, cachedTools.keys + clients.keys)
            ?: return ToolOutcome(ok = false, summary = "未知 MCP 工具：$toolName", errorCode = "invalid_action")
        val (sanitizedServerId, tool) = parsed
        // parseToolName 返回的是 sanitize 后的 serverId；clients/cachedTools
        // 的 key 是注册表原始 id，先映回原始 id。
        val serverId = (cachedTools.keys + clients.keys)
            .firstOrNull { sanitizeId(it) == sanitizedServerId } ?: sanitizedServerId
        val client = clients[serverId]
            ?: return ToolOutcome(ok = false, summary = "MCP 服务器未连接：$serverId（请先在扩展页连接）", errorCode = "mcp_not_connected")
        return try {
            val result = client.callTool(tool, args, toolCallTimeoutMs) { progress ->
                try {
                    onProgress?.invoke(serverId, progress)
                } catch (_: Exception) {
                }
            }
            ToolOutcome(
                ok = !result.isError,
                summary = if (result.isError) {
                    "MCP 工具 $tool 返回错误：${result.text.take(500)}"
                } else {
                    "MCP 工具 $tool 已执行"
                },
                data = result.toAgentData().take(ToolRegistryMaxOutput.MAX_OUTPUT_CHARS),
                errorCode = if (result.isError) "mcp_tool_error" else null,
            )
        } catch (e: McpException) {
            statuses[serverId] = failed(serverId, e.message ?: "调用失败")
            // 传输层掉线（不是认证/协议问题）才断线重连：401/未知工具
            // 重连也救不回来，不浪费重试。
            if (e.code in TRANSPORT_FAILURE_CODES) scheduleReconnect(serverId)
            ToolOutcome(ok = false, summary = "MCP 调用失败：${e.message ?: "未知错误"}", errorCode = "mcp_unavailable")
        } catch (e: Exception) {
            ToolOutcome(ok = false, summary = "MCP 调用失败：${e.message ?: "未知错误"}", errorCode = "mcp_unavailable")
        }
    }

    companion object {
        /** Hand MCP 工具里只读的两个（list / read）；其余一律审批。 */
        val READ_ONLY_TOOLS: Set<String> = setOf("list", "read")

        /**
         * 触发断线重连的失败码（传输层掉线）：连接失败、超时、HTTP 层
         * 错误、会话过期、传输未启动、进程退出、发送失败。401 未认证、
         * -32601 方法不存在、工具报错都不重连（重连救不回来）。
         */
        val TRANSPORT_FAILURE_CODES: Set<String> = setOf(
            "mcp_connect_failed",
            "mcp_timeout",
            "mcp_http_error",
            "mcp_session_expired",
            "mcp_not_started",
            "mcp_exited",
            "mcp_send_failed",
        )

        fun sanitizeId(raw: String): String =
            raw.map { c -> if (c.isLetterOrDigit() || c == '_') c else '_' }.joinToString("")

        fun toolNameFor(serverId: String, toolName: String): String =
            "mcp_${sanitizeId(serverId)}_${sanitizeId(toolName)}"

        /**
         * 反解析 `mcp_<serverId>_<tool>`。serverId 本身可能含下划线，
         * 故按“最长已注册 serverId 前缀”匹配；未注册时退回“最后一段
         * 是工具名”。
         */
        fun parseToolName(prefixed: String, knownServerIds: Set<String> = emptySet()): Pair<String, String>? {
            if (!prefixed.startsWith("mcp_")) return null
            val rest = prefixed.removePrefix("mcp_")
            val candidates = knownServerIds.map { sanitizeId(it) }.sortedByDescending { it.length }
            for (server in candidates) {
                if (rest.startsWith(server + "_")) {
                    return server to rest.removePrefix(server + "_")
                }
            }
            val idx = rest.lastIndexOf('_')
            if (idx <= 0 || idx == rest.length - 1) return null
            return rest.substring(0, idx) to rest.substring(idx + 1)
        }
    }
}

/** 断线重连退避策略（纯函数，可单测）：第 attempt 次（从 0 起）等多久。 */
object ReconnectPolicy {
    /** 默认退避：5 秒 → 15 秒 → 30 秒 → 60 秒 → 60 秒，共 5 次。 */
    val DEFAULT_DELAYS: List<Long> = listOf(5_000L, 15_000L, 30_000L, 60_000L, 60_000L)

    /** 第 attempt 次重连前等多久（毫秒）；超过次数返回 null = 不再重连。 */
    fun nextDelay(attempt: Int, delays: List<Long> = DEFAULT_DELAYS): Long? =
        if (attempt < 0 || attempt >= delays.size) null else delays[attempt]
}

/** 与 ToolRegistry.MAX_OUTPUT_CHARS 同值，避免 manager 直接依赖 ToolRegistry。 */
private object ToolRegistryMaxOutput {
    const val MAX_OUTPUT_CHARS: Int = 20000
}

/** 按注册表配置建默认客户端（transport=stdio|http|sse）。 */
fun defaultClient(
    entry: ExtensionEntry,
    credentialResolver: McpCredentialResolver = NoopMcpCredentialResolver,
    authProviderFactory: ((ExtensionEntry) -> McpAuthProvider?)? = null,
    rootsProvider: McpRootsProvider? = null,
): McpClient {
    val transport = entry.extra["transport"] ?: "stdio"
    return when (transport) {
        "http", "sse" -> {
            val url = entry.extra["url"].orEmpty()
            if (url.isBlank()) throw McpException("MCP 服务器未填地址（url）", "mcp_bad_url")
            val headers = parseStringMap(entry.extra["headersJson"]).mapValues { (_, value) ->
                McpServersConfig.resolveValue(value, credentialResolver)
            }
            // 认证优先级：App 侧 OAuth 工厂 > 配置里的 Authorization
            // Bearer 头 > X-Api-Key 头；三者都没有就是无认证。
            val authProvider = authProviderFactory?.invoke(entry)
                ?: authProviderForHeaders(headers)
            val mcpTransport = if (transport == "sse") {
                SseMcpTransport(url, headers, authProvider)
            } else {
                HttpMcpTransport(url, headers, authProvider)
            }
            McpClient(mcpTransport, rootsProvider = rootsProvider)
        }
        else -> {
            val command = entry.extra["command"].orEmpty()
            if (command.isBlank()) throw McpException("MCP 服务器未填启动命令（command）", "mcp_bad_command")
            // mcpServers 格式：command + args（数组）；老配置只有 command 一个
            // 字符串时按空白切（兼容旧行；引号内空白暂不支持）。
            val args = parseStringList(entry.extra["argsJson"]) ?: splitCommand(command).drop(1)
            val fullCommand = if (entry.extra["argsJson"] != null) {
                listOf(command) + args
            } else {
                splitCommand(command)
            }
            val env = parseStringMap(entry.extra["envJson"]).mapValues { (_, value) ->
                McpServersConfig.resolveValue(value, credentialResolver)
            }
            val cwd = entry.extra["cwd"]
            McpClient(StdioMcpTransport(fullCommand, env, cwd), rootsProvider = rootsProvider)
        }
    }
}

/**
 * 按配置里的认证头建成认证提供方（不含 OAuth 工厂那路）：
 * `Authorization: Bearer <token>` → [BearerTokenAuthProvider]；
 * `X-Api-Key: <key>`（大小写不限，`X-API-Key` 等同形也认）→
 * [ApiKeyAuthProvider]；都没有返回 null。头本身仍照常发给服务端
 * （headers 原样传给传输层），提供方只负责 401 时的处理与去重。
 */
fun authProviderForHeaders(headers: Map<String, String>): McpAuthProvider? {
    for ((name, value) in headers) {
        if (name.equals("Authorization", ignoreCase = true)) {
            // Bearer 不区分大小写（RFC 6750）；Basic 等其它 scheme 不认。
            val token = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE)
                .find(value.trim())?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (token.isNotBlank()) return BearerTokenAuthProvider(token)
        }
    }
    for ((name, value) in headers) {
        if (name.equals("X-Api-Key", ignoreCase = true) && value.isNotBlank()) {
            return ApiKeyAuthProvider(name, value)
        }
    }
    return null
}

/** 解析 extra 里存的 JSON 数组字符串（没有/解析失败返回 null）。 */
private fun parseStringList(json: String?): List<String>? {
    if (json.isNullOrBlank()) return null
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { arr.optString(it, "") }
    } catch (_: Exception) {
        null
    }
}

/** 解析 extra 里存的 JSON 对象字符串（没有/解析失败返回空 map）。 */
private fun parseStringMap(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val obj = org.json.JSONObject(json)
        val map = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = obj.optString(key, "")
        }
        map
    } catch (_: Exception) {
        emptyMap()
    }
}

/** 按空白切命令（不用 shell；引号内空白暂不支持——配置里用不到引号的简单命令）。 */
fun splitCommand(command: String): List<String> =
    command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
