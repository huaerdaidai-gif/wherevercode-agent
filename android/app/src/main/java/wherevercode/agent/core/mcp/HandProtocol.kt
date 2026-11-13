package wherevercode.agent.core.mcp

import org.json.JSONObject

/**
 * HandProtocol: cross-device hand envelope + MCP tool shapes (stage 2).
 *
 * Ported from the old project's `cross_device_mcp.py`
 * (blob a49e787e, main fd6b43d2):
 * - MCP protocol version "2024-11-05", server name "cross_device_hand"
 * - exactly 4 tools list/read/write/run (_MCP_TOOLS shape)
 * - envelope {"type":"cross_device_tool_call","version":1,"seq",
 *   "task_id","agent_id","source_device_id","target_device_id",
 *   "folder","action","args"} (CONTROL_TYPE)
 * - _FAILURE_MESSAGES: Chinese actionable messages, ported in full
 *
 * The envelope and error-code constants below are the CROSS-ROAD
 * CONTRACT: the other road (B) calls these APIs, so their signatures
 * must not change. `args` is Map<String, String> by contract; for the
 * `run` action the argv list travels JSON-encoded in args["argv"]
 * (e.g. "[\"git\",\"status\"]") and timeouts as args["timeout"].
 */
data class HandEnvelope(
    val type: String = "cross_device_tool_call",
    val version: Int = 1,
    val seq: Long,
    val taskId: String,
    val agentId: String,
    val sourceDeviceId: String,
    val targetDeviceId: String,
    val folder: String,
    val action: String,
    val args: Map<String, String> = emptyMap(),
    /**
     * Hand 自更新（2026-11-12）：设备管理动作的显式确认位。Hand 只在
     * confirmed=true 时执行 update_hand（防误发/防旧代码误触）。
     * 普通工具调用一律 false——Hand 侧 _handle_connection 只在
     * wherevercode_device_admin 分支读它，工具调用忽略。
     */
    val confirmed: Boolean = false,
)

/** One MCP tool definition (same shape as the old _MCP_TOOLS entries). */
data class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
)

object HandProtocol {
    const val MCP_PROTOCOL_VERSION: String = "2024-11-05"
    const val MCP_SERVER_NAME: String = "cross_device_hand"
    const val HAND_PROTOCOL_VERSION: Int = 1
    // V5 跨设备开发（2026-11-12，D2）：authorize_project_folder 是
    // 管理动作（把项目文件夹记入 Agent 的 auth.json），不执行命令；
    // 与 list/read/write/run 同走密封通道——parseJson 按本表校验
    // action，缺了它连请求信封都解析不出来（Hand 侧同理）。
    val HAND_ACTIONS: List<String> = listOf("list", "read", "write", "run", "authorize_project_folder")

    /**
     * M2 M11: session-sync envelope type (WACF §5.1). Constants and
     * validation only — no sending / transport code is added here.
     * A session-sync envelope is NOT one of the four hand actions and
     * must never be dispatched as a tool call (parseJson returns null
     * for it).
     */
    const val SESSION_SYNC_TYPE: String = "wherevercode_session_sync"

    /**
     * Hand 自更新（2026-11-12，App 更新后自动同步已连接设备）：
     * 设备管理密封消息类型（与 wherevercode_ws_token 并列，走同一条
     * 密封通道、同一套配对信任；不带任务凭证——设备管理没有 task）。
     * Hand 只在 APPROVED 之后处理它（_handle_connection 分支）。
     */
    const val DEVICE_ADMIN_TYPE: String = "wherevercode_device_admin"
    const val DEVICE_ADMIN_REPLY_TYPE: String = "wherevercode_device_admin_reply"

    /** WACF messageType five-class whitelist (§5.1 field 4). */
    val SESSION_MESSAGE_TYPES: List<String> =
        listOf("CHAT", "CONTROL", "TASK", "EVENT", "DELIVERY")

    /**
     * True for a well-formed session-sync envelope: type is
     * [SESSION_SYNC_TYPE], version == 1, seq >= 0, sessionId and
     * messageId are non-empty, and message_type (when present) is in
     * the five-class whitelist.
     */
    fun isSessionSyncEnvelope(obj: JSONObject): Boolean {
        if (obj.optString("type", "") != SESSION_SYNC_TYPE) return false
        if (obj.optInt("version", -1) != 1) return false
        if (obj.optLong("seq", -1) < 0) return false
        if (obj.optString("session_id", "").isEmpty()) return false
        if (obj.optString("message_id", "").isEmpty()) return false
        val messageType = obj.optString("message_type", "")
        if (messageType.isNotEmpty() && messageType !in SESSION_MESSAGE_TYPES) return false
        return true
    }

    /** String overload: malformed JSON is not a session-sync envelope. */
    fun isSessionSyncEnvelope(json: String): Boolean =
        try {
            isSessionSyncEnvelope(JSONObject(json))
        } catch (_: Exception) {
            false
        }

    /**
     * Response envelope types (B 路，HandClient 回包；须与
     * [wherevercode.agent.core.mcp.HandClient] 的 TYPE_* 常量保持一致）。
     * [parseJson] 接受请求类型与这三种回包类型；未知类型仍返回 null。
     */
    val RESPONSE_TYPES: List<String> = listOf(
        "cross_device_progress",
        "cross_device_result",
        "cross_device_error",
    )

    private const val PROJECT_DEVICE_NOTE: String =
        "这些操作发生在项目所在设备上，不在本机执行；文件路径和命令都在项目所在设备的文件夹里解析。"

    val MCP_TOOLS: List<McpToolDef> = listOf(
        McpToolDef(
            name = "list",
            description = "列出项目所在设备上文件夹里的文件和子文件夹。$PROJECT_DEVICE_NOTE",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf(
                        "type" to "string",
                        "description" to "项目所在设备上的相对路径，空表示文件夹根目录",
                    ),
                ),
                "additionalProperties" to false,
            ),
        ),
        McpToolDef(
            name = "read",
            description = "读取项目所在设备上一个文本文件的内容。$PROJECT_DEVICE_NOTE",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf("path" to mapOf("type" to "string")),
                "required" to listOf("path"),
                "additionalProperties" to false,
            ),
        ),
        McpToolDef(
            name = "write",
            description = "在项目所在设备上新建或覆盖一个文本文件。$PROJECT_DEVICE_NOTE",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "path" to mapOf("type" to "string"),
                    "content" to mapOf("type" to "string"),
                ),
                "required" to listOf("path", "content"),
                "additionalProperties" to false,
            ),
        ),
        McpToolDef(
            name = "run",
            description = "在项目所在设备上运行一条命令（不用 shell，参数按列表传）。$PROJECT_DEVICE_NOTE",
            inputSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "argv" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    "cwd" to mapOf("type" to "string"),
                    "timeout" to mapOf("type" to "number"),
                ),
                "required" to listOf("argv"),
                "additionalProperties" to false,
            ),
        ),
    )

    object Errors {
        const val NOT_AUTHORIZED: String = "cross_device_not_authorized"
        const val WRONG_TASK: String = "wrong_task"
        const val STALE_SEQUENCE: String = "stale_sequence"
        const val INVALID_CREDENTIAL: String = "invalid_credential"
        const val EXPIRED_CREDENTIAL: String = "expired_credential"
        const val REVOKED_CREDENTIAL: String = "revoked_credential"
        const val WRONG_TARGET: String = "wrong_target"
        const val WRONG_FOLDER: String = "wrong_folder"
        const val COMMAND_NOT_ALLOWED: String = "command_not_allowed"
        const val PATH_NOT_ALLOWED: String = "path_not_allowed"

        // Ported in full from cross_device_mcp._FAILURE_MESSAGES.
        private val MESSAGES: Map<String, String> = mapOf(
            "invalid" to "任务连接凭证无效，请重新下达任务。",
            "expired" to "任务连接已过期，请重新下达任务。",
            "revoked" to "任务或设备授权已撤销，请重新授权后下达任务。",
            "wrong_task" to "连接凭证不属于本任务，请重新下达任务。",
            "wrong_target" to "连接凭证不属于项目设备，请重新选择设备。",
            "wrong_folder" to "连接凭证不允许访问该项目文件夹。",
            "cross_device_disabled" to "跨设备功能未开启：请先开启跨设备功能后再试。",
            "cross_device_not_authorized" to "项目设备还没有授权这个 Agent 使用该文件夹：请先在项目设备上授权，再重新下达任务。",
            "cross_device_needs_update" to "项目设备的版本太旧，不支持跨设备：请先更新项目设备，再重新下达任务。",
            "cross_device_offline" to "项目设备当前离线或无法连接：请确认项目设备在线后再试。",
            "cross_device_relay_unavailable" to "跨设备中继未配置，无法连接项目设备：请确认本设备已正确接入跨设备服务后再试。",
            "cross_device_task_not_running" to "任务已不在运行，跨设备调用已停止。",
            "cross_device_account_mismatch" to "项目设备不属于同一账号，跨设备调用已被拒绝。",
            "cross_device_agent_not_supported" to "设备程序需要升级：当前适配器尚未接入跨设备工具。",
            "cross_device_target_invalid" to "跨设备任务缺少项目设备或文件夹，无法执行：请重新指定项目设备和文件夹。",
            "cross_device_stale_sequence" to "跨设备调用序号已过期，已返回缓存结果或拒绝重复执行。",
            "stale_sequence" to "跨设备调用序号已过期，已返回缓存结果或拒绝重复执行。",
            "invalid_credential" to "任务连接凭证无效，请重新下达任务。",
            "expired_credential" to "任务连接已过期，请重新下达任务。",
            "revoked_credential" to "任务或设备授权已撤销，请重新授权后下达任务。",
            "command_not_allowed" to "不允许的命令：请使用白名单内的命令（构建、测试、查看类命令）。",
            "path_not_allowed" to "不允许的路径：该路径不在授权文件夹内。",
            "relay_error" to "跨设备调用失败：请稍后重试。",
        )

        /** Chinese actionable message for a code (ported: cross_device_failure_message). */
        fun message(code: String): String =
            MESSAGES[code] ?: "跨设备调用失败：请稍后重试。"
    }

    fun toJson(envelope: HandEnvelope): String {
        val obj = JSONObject()
        obj.put("type", envelope.type)
        obj.put("version", envelope.version)
        obj.put("seq", envelope.seq)
        obj.put("task_id", envelope.taskId)
        obj.put("agent_id", envelope.agentId)
        obj.put("source_device_id", envelope.sourceDeviceId)
        obj.put("target_device_id", envelope.targetDeviceId)
        obj.put("folder", envelope.folder)
        obj.put("action", envelope.action)
        val argsObj = JSONObject()
        envelope.args.forEach { (key, value) -> argsObj.put(key, value) }
        obj.put("args", argsObj)
        obj.put("confirmed", envelope.confirmed)
        return obj.toString()
    }

    /**
     * Parse an envelope. Returns null for malformed JSON, a wrong type /
     * version, missing fields, or an action outside list/read/write/run.
     * Accepts the request type `cross_device_tool_call` and the three
     * response types in [RESPONSE_TYPES]. Nothing is guessed.
     */
    fun parseJson(json: String): HandEnvelope? {
        return try {
            val obj = JSONObject(json)
            val type = obj.optString("type", "")
            if (type != "cross_device_tool_call" && type !in RESPONSE_TYPES) return null
            if (obj.optInt("version", -1) != 1) return null
            val action = obj.optString("action", "")
            if (action !in HAND_ACTIONS) return null
            val argsObj = obj.optJSONObject("args")
            val args = LinkedHashMap<String, String>()
            if (argsObj != null) {
                val keys = argsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!argsObj.isNull(key)) args[key] = argsObj.optString(key, "")
                }
            }
            HandEnvelope(
                type = obj.optString("type", "cross_device_tool_call"),
                version = obj.optInt("version", 1),
                seq = obj.optLong("seq", -1),
                taskId = obj.optString("task_id", ""),
                agentId = obj.optString("agent_id", ""),
                sourceDeviceId = obj.optString("source_device_id", ""),
                targetDeviceId = obj.optString("target_device_id", ""),
                folder = obj.optString("folder", ""),
                action = action,
                args = args,
            ).takeIf {
                it.seq >= 0 && it.taskId.isNotEmpty() && it.agentId.isNotEmpty() &&
                    it.sourceDeviceId.isNotEmpty() && it.targetDeviceId.isNotEmpty() &&
                    it.folder.isNotEmpty()
            }
        } catch (_: Exception) {
            null
        }
    }
}
