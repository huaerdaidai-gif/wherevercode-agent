package wherevercode.agent.core.agent

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import wherevercode.agent.brain.ToolDef
import wherevercode.agent.brain.ToolOutcome
import wherevercode.agent.core.extension.mcp.HttpMcpTransport
import wherevercode.agent.core.extension.mcp.McpClient
import wherevercode.agent.core.extension.mcp.McpException
import wherevercode.agent.core.extension.mcp.McpJsonRpc
import wherevercode.agent.core.extension.mcp.McpJsonRpc.toMap
import wherevercode.agent.core.extension.mcp.McpTool
import wherevercode.agent.core.extension.mcp.McpToolResult
import wherevercode.agent.core.extension.mcp.McpToolSource

/**
 * 服务端没有提供所要的工具：调用方必须把这个缺口如实告诉用户，
 * 绝不能伪造一个成功结果。缺的是哪个工具、去哪补（服务端部署），
 * 都在消息里写清。
 */
class PlatformMcpGapException(message: String) : Exception(message)

/**
 * 本地拒绝执行：参数不合法、凭证缺失、或任务的项目与当前聊天绑定
 * 不符（跨项目越权）。拒绝发生在任何网络调用之前。
 */
class PlatformMcpRefusedException(message: String) : Exception(message)

/** 平台 MCP 连接状态（真实 tools/list 的结果，不是写死的清单）。 */
data class PlatformMcpStatus(
    val connected: Boolean,
    val serverName: String? = null,
    val protocolVersion: String? = null,
    val tools: List<McpTool> = emptyList(),
    val lastError: String? = null,
)

/** 平台任务的一行（服务端工具 JSON 的宽松解析：缺字段不抛错，只空着）。 */
data class PlatformTask(
    val taskId: String,
    val title: String? = null,
    val state: String? = null,
    val assignee: String? = null,
    val raw: JSONObject = JSONObject(),
)

/**
 * 任务生命周期能力探针：按真实 tools/list 逐项核对服务端到底提供
 * 了哪些工具。`present` 为 false 的那项就是缺口——调用方调它只会
 * 得到 [PlatformMcpGapException]，绝不会得到伪造的成功。
 */
data class PlatformTaskLifecycleProbe(
    val tools: List<String>,
    val channelList: Boolean,
    val channelJoin: Boolean,
    val channelMessages: Boolean,
    val channelPost: Boolean,
    val listMyTasks: Boolean,
    val getTask: Boolean,
    val claimTask: Boolean,
    val reportProgress: Boolean,
    val submitResult: Boolean,
    val releaseTask: Boolean,
    val attachDeliveryDiff: Boolean,
    val reportStatus: Boolean,
    val startTask: Boolean,
) {
    /** 缺口清单（给界面/日志直接展示用）。 */
    fun missing(): List<String> {
        val out = mutableListOf<String>()
        if (!channelList) out += "agent_channel_list"
        if (!channelJoin) out += "agent_channel_join"
        if (!channelMessages) out += "agent_channel_messages"
        if (!channelPost) out += "agent_channel_post"
        if (!listMyTasks) out += "list_my_tasks"
        if (!getTask) out += "get_task"
        if (!claimTask) out += "claim_task"
        if (!reportProgress) out += "report_progress"
        if (!submitResult) out += "submit_result"
        if (!releaseTask) out += "release_task"
        if (!attachDeliveryDiff) out += "attach_delivery_diff"
        if (!reportStatus) out += "report_status"
        if (!startTask) out += "start_task"
        return out
    }
}

/**
 * 平台 MCP 客户端（2026-11-13，MCP 能力补齐）。
 *
 * 此前 App 调平台端点只走 [PlatformChannelMcpTransport]：裸 JSON-RPC
 * `tools/call`，没有 initialize、没有 tools/list、没有会话——频道
 * 两工具能调，但工具发现、任务生命周期、事件订阅全都没有。本类把
 * 平台端点接进真正的 [McpClient]（initialize → tools/list →
 * tools/call，与通用 MCP 服务器同一条链路）：
 * - 连接与工具发现是真实的：工具列表只来自服务端 tools/list；
 * - 任务生命周期（领单/进度/交付/放弃）逐项先核对工具在不在，
 *   缺了就报 [PlatformMcpGapException]，绝不伪造成功；
 * - 创建任务没有已核实的服务端工具契约（`start_task` 的参数未在
 *   服务端 tools/list 的 inputSchema 里核实过），本类不提供
 *   createTask 捷径——[probeTaskLifecycle] 会报告它在不在，调用
 *   方确认契约后再用 [callTool] 直接调；
 * - 令牌每次建连时向 [tokenProvider] 现取，只进 Authorization 请求
 *   头，永不写盘、永不进日志与错误消息（[sanitize] 兜底遮掉）；
 * - 跨项目越权：任务工具调用前，若本地任务库能查到该任务的项目，
 *   且与当前聊天绑定项目不符，本地直接拒绝（服务端仍是最终裁决，
 *   本地不知道的任务放行、由服务端裁决）；
 * - 同时实现 [McpToolSource]：大脑工具链可以像消费任何 MCP 服务器
 *   一样消费平台端点（工具名 `mcp_platform_<tool>`），只读工具
 *   自动执行、改写工具走审批——与现有单兵工具调用链兼容。
 *
 * 凭证缺口（服务端依赖，不在本类里绕过）：App 的平台凭证若没有
 * agent 作用域，tools/list 会 401；`issue_agent_token` 未部署、
 * App 凭证未授 owner scope 时，令牌签发走 [PlatformTokenIssuer]
 * 同样失败——这些都要服务端部署/授权，等 Funk 批准。
 */
class PlatformMcpClient(
    /** 平台 Agent 凭证现取（每次建连调一次；null/空 = 未配置）。 */
    private val tokenProvider: () -> String?,
    /** 平台 MCP 端点（默认公开构建的默认平台地址拼出）。 */
    private val endpoint: String = PlatformAgent.mcpEndpoint(null),
    /** 建客户端的工厂（测试可注入假客户端；默认走真实 HTTP 传输）。 */
    private val clientFactory: (String) -> McpClient = { token ->
        McpClient(HttpMcpTransport(endpoint, mapOf("Authorization" to "Bearer $token")))
    },
    /** 本地任务的项目查询（任务 id → 项目 id；不知道时返回 null）。 */
    private val localTaskProject: ((String) -> String?)? = null,
    /** 当前聊天绑定的项目（不知道时返回 null）。 */
    private val currentProjectId: (() -> String?)? = null,
    /** 大脑调平台工具的单次超时（毫秒）。 */
    private val toolCallTimeoutMs: Long = McpClient.DEFAULT_TIMEOUT_MS,
) : McpToolSource {

    private var client: McpClient? = null

    /** 最近一次真实 tools/list 的结果（连接成功后才有效）。 */
    var liveTools: List<McpTool> = emptyList()
        private set

    fun isConnected(): Boolean = client != null

    /** Whether a platform credential is configured, without exposing its value. */
    fun hasConfiguredCredential(): Boolean = tokenProvider()?.isNotBlank() == true

    /**
     * 连接：initialize → tools/list，全程真实走协议。凭证缺失、
     * 401、建连失败都返回 `connected=false` + 原因，不抛错（调用方
     * 按状态展示）。成功后 [liveTools] 即服务端真实工具列表。
     * 每次连接都现取令牌；凭证换了要重新连接。
     */
    suspend fun connect(timeoutMs: Long = McpClient.DEFAULT_TIMEOUT_MS): PlatformMcpStatus {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: return PlatformMcpStatus(
                connected = false,
                lastError = "未配置平台 Agent 凭证：请先在平台 Agent 设置里填写平台凭证。",
            )
        val next = try {
            clientFactory(token)
        } catch (e: Exception) {
            return PlatformMcpStatus(connected = false, lastError = sanitize("创建平台 MCP 客户端失败：${e.message ?: "未知错误"}"))
        }
        return try {
            val info = next.initialize()
            val tools = next.listTools(timeoutMs)
            client?.let { old ->
                try {
                    old.close()
                } catch (_: Exception) {
                }
            }
            client = next
            liveTools = tools
            PlatformMcpStatus(
                connected = true,
                serverName = info.name,
                protocolVersion = info.protocolVersion,
                tools = tools,
            )
        } catch (e: CancellationException) {
            try {
                next.close()
            } catch (_: Exception) {
            }
            throw e
        } catch (e: Exception) {
            try {
                next.close()
            } catch (_: Exception) {
            }
            PlatformMcpStatus(connected = false, lastError = sanitize("连接平台 MCP 失败：${e.message ?: "未知错误"}"))
        }
    }

    /** 断开（关传输、清工具缓存）。 */
    suspend fun disconnect() {
        val old = client
        client = null
        liveTools = emptyList()
        if (old != null) {
            try {
                old.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 重新拉一次真实工具列表（连接后工具变了时用）。 */
    suspend fun refreshTools(timeoutMs: Long = McpClient.DEFAULT_TIMEOUT_MS): List<McpTool> {
        val c = requireClient()
        liveTools = c.listTools(timeoutMs)
        return liveTools
    }

    /**
     * 调一个平台工具（真实 tools/call，超时、取消、错误处理与
     * [McpClient.callTool] 同款：超时抛 [McpException]，取消原样
     * 传播，工具自己的失败以 `isError=true` 返回，不自动重试）。
     * 工具不在最近的工具列表里时先刷新一次，仍没有就报
     * [PlatformMcpGapException]——缺口明说，不伪造成功。
     */
    suspend fun callTool(
        name: String,
        arguments: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = toolCallTimeoutMs,
    ): McpToolResult {
        val tool = name.trim()
        if (tool.isBlank()) throw PlatformMcpRefusedException("工具名不能为空。")
        ensureToolPresent(tool, timeoutMs)
        val c = requireClient()
        return try {
            c.callTool(tool, arguments, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            throw McpException(sanitize(e.message ?: "平台调用失败"), e.code)
        }
    }

    /**
     * 任务生命周期能力探针：跑一次真实 tools/list，逐项核对服务端
     * 到底提供了哪些工具。返回的缺口（[PlatformTaskLifecycleProbe.missing]）
     * 就是尚未完成的服务端依赖——调用方据此如实报告，不猜测。
     */
    suspend fun probeTaskLifecycle(
        timeoutMs: Long = McpClient.DEFAULT_TIMEOUT_MS,
    ): PlatformTaskLifecycleProbe {
        val tools = refreshTools(timeoutMs).map { it.name }.toSet()
        return PlatformTaskLifecycleProbe(
            tools = tools.sorted(),
            channelList = "agent_channel_list" in tools,
            channelJoin = "agent_channel_join" in tools,
            channelMessages = "agent_channel_messages" in tools,
            channelPost = "agent_channel_post" in tools,
            listMyTasks = "list_my_tasks" in tools,
            getTask = "get_task" in tools,
            claimTask = "claim_task" in tools,
            reportProgress = "report_progress" in tools,
            submitResult = "submit_result" in tools,
            releaseTask = "release_task" in tools,
            attachDeliveryDiff = "attach_delivery_diff" in tools,
            reportStatus = "report_status" in tools,
            startTask = "start_task" in tools,
        )
    }

    // -- 频道 ------------------------------------------------------------

    /** 往频道发一条消息（真实工具结果，id 在结果 JSON 里）。 */
    suspend fun postChannelMessage(
        channelId: String,
        text: String,
        timeoutMs: Long = toolCallTimeoutMs,
    ): String {
        if (channelId.isBlank()) throw PlatformMcpRefusedException("频道 ID 不能为空。")
        if (text.isBlank()) throw PlatformMcpRefusedException("消息内容不能为空。")
        val result = callTool(
            "agent_channel_post",
            mapOf("channel_id" to channelId, "text" to text),
            timeoutMs,
        )
        if (result.isError) throw McpException(sanitize("频道发送失败：${result.text.take(300)}"), "mcp_tool_error")
        val json = parseToolJson(result.text)
        return json.optString("id", "").ifBlank {
            json.optJSONObject("message")?.optString("id", "").orEmpty()
        }
    }

    /** 读频道消息（与 [PlatformChannelTransport.pollMessages] 同一形状）。 */
    suspend fun channelMessages(
        channelId: String,
        limit: Int = 20,
        afterId: String? = null,
        onlyUnread: Boolean = false,
        markRead: Boolean = false,
        timeoutMs: Long = toolCallTimeoutMs,
    ): List<PlatformChannelMessage> {
        if (channelId.isBlank()) throw PlatformMcpRefusedException("频道 ID 不能为空。")
        val args = LinkedHashMap<String, Any?>()
        args["channel_id"] = channelId
        args["limit"] = limit
        if (!afterId.isNullOrBlank()) args["after_id"] = afterId
        if (onlyUnread) args["only_unread"] = true
        if (markRead) args["mark_read"] = true
        val result = callTool("agent_channel_messages", args, timeoutMs)
        if (result.isError) throw McpException(sanitize("频道读取失败：${result.text.take(300)}"), "mcp_tool_error")
        val json = parseToolJson(result.text)
        val messages = json.optJSONArray("messages") ?: return emptyList()
        val out = mutableListOf<PlatformChannelMessage>()
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

    // -- 任务生命周期 ------------------------------------------------------

    /** 列出派给本 Agent 的任务（等待接手的和正在做的）。 */
    suspend fun listMyTasks(timeoutMs: Long = toolCallTimeoutMs): List<PlatformTask> {
        val result = callTool("list_my_tasks", emptyMap(), timeoutMs)
        if (result.isError) throw McpException(sanitize("列出任务失败：${result.text.take(300)}"), "mcp_tool_error")
        return parseTaskList(result.text)
    }

    /** 取一个任务的详情（服务端没有该任务时返回 null，不伪造）。 */
    suspend fun getTask(taskId: String, timeoutMs: Long = toolCallTimeoutMs): PlatformTask? {
        val id = requireTaskId(taskId)
        val result = callTool("get_task", mapOf("task_id" to id), timeoutMs)
        if (result.isError) return null
        val json = parseToolJson(result.text)
        val taskJson = json.optJSONObject("task") ?: json
        if (taskJson.optString("task_id", "").isBlank() && taskJson.optString("id", "").isBlank()) return null
        return toPlatformTask(taskJson)
    }

    /** 领取一个任务（2 小时内要报进度或交付，否则任务回到等待接手）。 */
    suspend fun claimTask(taskId: String, timeoutMs: Long = toolCallTimeoutMs): PlatformTask {
        val id = requireTaskId(taskId)
        checkTaskProject(id)
        val result = callTool("claim_task", mapOf("task_id" to id), timeoutMs)
        if (result.isError) throw McpException(sanitize("领取任务失败：${result.text.take(300)}"), "mcp_tool_error")
        val json = parseToolJson(result.text)
        return toPlatformTask(json.optJSONObject("task") ?: json)
    }

    /** 汇报进度（同时续租 2 小时；text 最长 2000 字，超了截断并注明）。 */
    suspend fun reportProgress(
        taskId: String,
        text: String,
        timeoutMs: Long = toolCallTimeoutMs,
    ) {
        val id = requireTaskId(taskId)
        checkTaskProject(id)
        val body = text.trim()
        if (body.isBlank()) throw PlatformMcpRefusedException("进度内容不能为空。")
        val capped = if (body.length > MAX_PROGRESS_CHARS) {
            body.take(MAX_PROGRESS_CHARS) + "…（已截断）"
        } else {
            body
        }
        val result = callTool("report_progress", mapOf("task_id" to id, "text" to capped), timeoutMs)
        if (result.isError) throw McpException(sanitize("汇报进度失败：${result.text.take(300)}"), "mcp_tool_error")
    }

    /**
     * 交付任务（summary 最长 8000 字，超了截断并注明；开发任务可带
     * branch，审查任务可带 verdict=approve/changes_requested）。
     * 开发任务交付后平台自动交给审查方，不通过会自动打回。
     */
    suspend fun submitResult(
        taskId: String,
        summary: String,
        branch: String? = null,
        verdict: String? = null,
        timeoutMs: Long = toolCallTimeoutMs,
    ): PlatformTask {
        val id = requireTaskId(taskId)
        checkTaskProject(id)
        val body = summary.trim()
        if (body.isBlank()) throw PlatformMcpRefusedException("交付说明不能为空。")
        val capped = if (body.length > MAX_SUMMARY_CHARS) {
            body.take(MAX_SUMMARY_CHARS) + "…（已截断）"
        } else {
            body
        }
        if (verdict != null && verdict != "approve" && verdict != "changes_requested") {
            throw PlatformMcpRefusedException("verdict 只能是 approve 或 changes_requested。")
        }
        val args = LinkedHashMap<String, Any?>()
        args["task_id"] = id
        args["summary"] = capped
        if (!branch.isNullOrBlank()) args["branch"] = branch.trim()
        if (verdict != null) args["verdict"] = verdict
        val result = callTool("submit_result", args, timeoutMs)
        if (result.isError) throw McpException(sanitize("交付任务失败：${result.text.take(300)}"), "mcp_tool_error")
        val json = parseToolJson(result.text)
        return toPlatformTask(json.optJSONObject("task") ?: json)
    }

    /** 放弃任务并说明原因（负责人会收到提醒）。 */
    suspend fun releaseTask(
        taskId: String,
        reason: String,
        timeoutMs: Long = toolCallTimeoutMs,
    ) {
        val id = requireTaskId(taskId)
        checkTaskProject(id)
        val body = reason.trim()
        if (body.isBlank()) throw PlatformMcpRefusedException("放弃原因不能为空。")
        val capped = if (body.length > MAX_PROGRESS_CHARS) {
            body.take(MAX_PROGRESS_CHARS) + "…（已截断）"
        } else {
            body
        }
        val result = callTool("release_task", mapOf("task_id" to id, "reason" to capped), timeoutMs)
        if (result.isError) throw McpException(sanitize("放弃任务失败：${result.text.take(300)}"), "mcp_tool_error")
    }

    // -- McpToolSource：大脑工具链的视角 ----------------------------------

    override fun toolDefs(): List<ToolDef> {
        return liveTools.map { tool ->
            ToolDef(
                name = toolNameFor(tool.name),
                description = "MCP 平台 · ${tool.description}",
                inputSchema = with(McpJsonRpc) { tool.inputSchema.toMap() },
            )
        }
    }

    override fun isReadOnly(toolName: String): Boolean {
        val tool = parseToolName(toolName) ?: return false
        return tool in READ_ONLY_TOOLS
    }

    override fun needsApproval(toolName: String): Boolean = !isReadOnly(toolName)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolOutcome {
        val tool = parseToolName(toolName)
            ?: return ToolOutcome(ok = false, summary = "未知 MCP 平台工具：$toolName", errorCode = "invalid_action")
        if (client == null) {
            return ToolOutcome(ok = false, summary = "平台 MCP 未连接（请先连接平台端点）", errorCode = "mcp_not_connected")
        }
        // 脑链只应调用 toolDefs() 里真实展示过的工具：名字从未在服务端
        // tools/list 里出现过，是调用方写错了名字（invalid_action），
        // 不是服务端缺接口（mcp_platform_gap 留给 callTool 直接调用
        // 时“曾见过、现在没了”的缺口）。本地判断，不发网络请求。
        // 顺序：先判未连接（liveTools 为空时也是未连接），再判名字。
        if (liveTools.none { it.name == tool }) {
            return ToolOutcome(ok = false, summary = "未知 MCP 平台工具：$toolName", errorCode = "invalid_action")
        }
        return try {
            val result = callTool(tool, args, toolCallTimeoutMs)
            ToolOutcome(
                ok = !result.isError,
                summary = if (result.isError) {
                    "MCP 平台工具 $tool 返回错误：${sanitize(result.text.take(500))}"
                } else {
                    "MCP 平台工具 $tool 已执行"
                },
                data = result.text.take(MAX_OUTPUT_CHARS),
                errorCode = if (result.isError) "mcp_tool_error" else null,
            )
        } catch (e: PlatformMcpGapException) {
            ToolOutcome(ok = false, summary = e.message ?: "平台端点没有提供该工具", errorCode = "mcp_platform_gap")
        } catch (e: PlatformMcpRefusedException) {
            ToolOutcome(ok = false, summary = e.message ?: "平台调用被拒绝", errorCode = "mcp_refused")
        } catch (e: CancellationException) {
            throw e
        } catch (e: McpException) {
            ToolOutcome(ok = false, summary = sanitize("MCP 平台调用失败：${e.message ?: "未知错误"}"), errorCode = "mcp_unavailable")
        } catch (e: Exception) {
            ToolOutcome(ok = false, summary = sanitize("MCP 平台调用失败：${e.message ?: "未知错误"}"), errorCode = "mcp_unavailable")
        }
    }

    // -- 内部 ---------------------------------------------------------------

    private fun requireClient(): McpClient =
        client ?: throw McpException("平台 MCP 未连接（请先连接平台端点）", "mcp_not_connected")

    private fun requireTaskId(taskId: String): String {
        val id = taskId.trim()
        if (id.isBlank()) throw PlatformMcpRefusedException("任务 ID 不能为空。")
        return id
    }

    /**
     * 跨项目越权本地关：本地任务库能查到该任务的项目、且当前聊天
     * 绑定了另一个项目时，本地直接拒绝（不发网）。本地查不到的
     * 任务（服务端才有的任务）放行、由服务端裁决——本地不知道不
     * 等于越权，不替服务端乱拦。
     */
    private fun checkTaskProject(taskId: String) {
        val local = localTaskProject ?: return
        val current = currentProjectId ?: return
        val rowProject = try {
            local(taskId)
        } catch (_: Exception) {
            null
        } ?: return
        val bound = try {
            current()
        } catch (_: Exception) {
            null
        } ?: return
        if (rowProject != bound) {
            throw PlatformMcpRefusedException(
                "任务 $taskId 属于另一项目（本地任务库记录），与当前聊天项目不符，已拒绝执行。",
            )
        }
    }

    /** 工具不在缓存列表里时刷新一次，仍没有就是服务端缺口。 */
    private suspend fun ensureToolPresent(name: String, timeoutMs: Long) {
        if (liveTools.any { it.name == name }) return
        val c = requireClient()
        liveTools = c.listTools(timeoutMs)
        if (liveTools.none { it.name == name }) {
            throw PlatformMcpGapException(
                "平台端点没有提供 $name 工具（服务端未部署或当前凭证无权看到它），本次调用未执行。",
            )
        }
    }

    /** 工具结果文本解析成 JSON（不是 JSON 时返回空对象，不抛错）。 */
    private fun parseToolJson(text: String): JSONObject {
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun parseTaskList(text: String): List<PlatformTask> {
        val json = parseToolJson(text)
        val array = json.optJSONArray("tasks")
            ?: json.optJSONArray("my_tasks")
            ?: return emptyList()
        val out = mutableListOf<PlatformTask>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out += toPlatformTask(obj)
        }
        return out
    }

    private fun toPlatformTask(json: JSONObject): PlatformTask {
        val id = json.optString("task_id", "").ifBlank { json.optString("id", "") }
        return PlatformTask(
            taskId = id,
            title = json.optString("title", "").takeIf { it.isNotBlank() },
            state = json.optString("state", "").takeIf { it.isNotBlank() },
            assignee = json.optString("assignee", "").takeIf { it.isNotBlank() }
                ?: json.optString("assigned_agent_id", "").takeIf { it.isNotBlank() },
            raw = json,
        )
    }

    /**
     * 错误消息脱敏兜底：万一服务端错误体里原样带回了令牌（正常不
     * 会），把它遮掉。令牌只从 [tokenProvider] 现取现比，不存下来。
     */
    private fun sanitize(message: String): String {
        val token = try {
            tokenProvider()?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } ?: return message
        if (token.length < 8 || !message.contains(token)) return message
        return message.replace(token, "[已隐藏的凭证]")
    }

    companion object {
        /** 大脑工具链里的平台工具名前缀（与通用管理器 `mcp_<server>_<tool>` 同形）。 */
        const val TOOL_PREFIX: String = "mcp_platform_"

        /** 只读的平台工具（自动执行、可缓存）：读频道、读任务、列任务。 */
        val READ_ONLY_TOOLS: Set<String> = setOf(
            "agent_channel_list",
            "agent_channel_messages",
            "list_my_tasks",
            "get_task",
            "list_projects",
            "list_agents",
        )

        /** 进度/放弃原因上限（与服务端 report_progress / release_task 的 2000 字口径一致）。 */
        const val MAX_PROGRESS_CHARS: Int = 2000

        /** 交付说明上限（与服务端 submit_result 的 8000 字口径一致）。 */
        const val MAX_SUMMARY_CHARS: Int = 8000

        /** 大脑工具链看到的单条工具结果上限（与管理器同值）。 */
        const val MAX_OUTPUT_CHARS: Int = 20000

        fun toolNameFor(toolName: String): String = TOOL_PREFIX + toolName

        fun parseToolName(prefixed: String): String? {
            if (!prefixed.startsWith(TOOL_PREFIX)) return null
            val rest = prefixed.removePrefix(TOOL_PREFIX)
            return rest.takeIf { it.isNotBlank() }
        }
    }
}
