package wherevercode.agent.core.mcp

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintWriter
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.core.control.ExecutionFence
import wherevercode.agent.core.control.FenceRequest
import wherevercode.agent.core.control.LocalExecutionFence
import wherevercode.agent.core.security.AuditEntry
import wherevercode.agent.core.security.AuditLog
import wherevercode.agent.core.security.CommandLimits
import wherevercode.agent.core.security.FileAuditLog
import wherevercode.agent.core.security.FileAuthorizationStore
import wherevercode.agent.core.security.LimitError
import wherevercode.agent.core.security.ReplayGuard
import wherevercode.agent.core.security.utcNowIso
import wherevercode.agent.core.workspace.FileWorkspaceAdapter
import wherevercode.agent.core.workspace.WorkspaceAdapter
import wherevercode.agent.core.workspace.WorkspaceException

/**
 * HandServer: on-device MCP server, stdio skeleton (stage 2).
 *
 * Ported from the old project's `cross_device_mcp.py`
 * `CrossDeviceMcpServer.handle()` method dispatch
 * (blob a49e787e, main fd6b43d2) and `cross_device_hand.py`
 * `CrossDeviceHand` (blob 3ff2eb57).
 *
 * Pure JVM (System.in / System.out), no Android dependency.
 * Reads JSON-RPC lines from stdin, dispatches list/read/write/run
 * through the Hand + the guards (authorization, credential, replay,
 * command allowlist, path root, workspace protection), and writes
 * results to stdout.
 *
 * NO authorization => NO silent local execution: a call without a
 * matching authorization record (or a failing credential check) is
 * refused with a Chinese actionable reason. The old
 * `resolve_cross_device_task()` rule is kept: never fall back to local
 * execution when the cross-device call is refused.
 *
 * Gate (M2, M6): every tools/call passes the single [ExecutionFence]
 * — grant AND credential AND epoch/goal-version AND control status —
 * instead of the old two-step `credentialCheck` lambda +
 * `auth.isAuthorized` call. The stdio `main()` below plugs in a
 * [LocalExecutionFence] （本地模式： callers are local to the device,
 * so no TaskCredential is required, but grant + control status +
 * task state are still checked). The LAN transport (stage 4) plugs a
 * full [ExecutionFence] with the signing key instead.
 */
class HandError(val code: String, message: String) : RuntimeException(message)

/** The four-action hand. Implementations execute list/read/write/run on one authorised folder. */
interface HandExecutor {
    fun call(action: String, args: Map<String, String>, taskId: String): Map<String, Any?>
}

/**
 * Local hand execution. Mirrors CrossDeviceHand: path resolution,
 * MAX_TEXT_BYTES / NUL checks, write guard + parent re-check, run
 * allowlist first (a refused command never reaches the lock, the
 * snapshot or the process table), 20,000-char display truncation.
 */
class LocalHand(
    workspaceRoot: File,
    private val taskRoot: File,
    private val workspaceAdapter: WorkspaceAdapter,
    private val limits: CommandLimits = CommandLimits(),
    private val auditLog: AuditLog? = null,
    private val agentId: String = "hand",
    val enabled: Boolean = true,
) : HandExecutor {
    private val workspace: File = workspaceRoot.canonicalFile
    private var roundId: String? = null
    private var finished: Boolean = false

    init {
        require(workspace.isDirectory) { "hand_workspace_missing: $workspace" }
        val taskRootPath = taskRoot.canonicalFile.path
        require(workspace.path != taskRootPath && !workspace.path.startsWith("$taskRootPath/")) {
            "hand_task_root_inside_workspace"
        }
    }

    fun resolve(rawPath: String): Pair<String, File> {
        if (rawPath.isEmpty() || rawPath == ".") return "" to workspace
        return workspaceAdapter.resolveWorkspacePath(rawPath)
    }

    fun begin(taskId: String): String {
        if (roundId == null || finished) {
            roundId = workspaceAdapter.beginRound(taskId = taskId, owner = "hand").roundId
            finished = false
        }
        return roundId!!
    }

    fun finish(): Map<String, Any?> {
        val id = roundId ?: return mapOf("changed" to emptyList<String>(), "created" to emptyList<String>(), "deleted" to emptyList<String>())
        if (finished) return mapOf("changed" to emptyList<String>(), "created" to emptyList<String>(), "deleted" to emptyList<String>())
        val changeSet = workspaceAdapter.finishRound(id)
        finished = true
        return mapOf(
            "changed" to changeSet.files.filter { it.change.name == "M" }.map { it.path }.sorted(),
            "created" to changeSet.files.filter { it.change.name == "A" }.map { it.path }.sorted(),
            "deleted" to changeSet.files.filter { it.change.name == "D" }.map { it.path }.sorted(),
        )
    }

    fun undo(): Map<String, Any?> {
        val id = roundId ?: return mapOf("restored" to emptyList<String>(), "already_undone" to true)
        val result = workspaceAdapter.undo(id, confirm = true)
        return mapOf(
            "restored" to result.restored,
            "deleted" to result.deleted,
            "not_restored" to result.notRestored,
            "already_undone" to result.alreadyUndone,
        )
    }

    override fun call(action: String, args: Map<String, String>, taskId: String): Map<String, Any?> {
        if (!enabled) throw HandError("cross_device_disabled", HandProtocol.Errors.message("cross_device_disabled"))
        if (action !in HandProtocol.HAND_ACTIONS) {
            throw HandError("hand_unknown_action", "未知动作：$action")
        }
        return try {
            val result = when (action) {
                "list" -> list(args)
                "read" -> read(args)
                "write" -> write(args, taskId)
                else -> run(args, taskId)
            }
            audit(taskId, action, args, ok = true, errorCode = null, result = result)
            result
        } catch (e: HandError) {
            audit(taskId, action, args, ok = false, errorCode = e.code, result = null)
            throw e
        } catch (e: WorkspaceException) {
            audit(taskId, action, args, ok = false, errorCode = e.code, result = null)
            throw HandError(e.code, e.message ?: e.code)
        }
    }

    private fun list(args: Map<String, String>): Map<String, Any?> {
        val (rel, path) = resolve(args["path"] ?: "")
        if (!path.isDirectory) throw HandError("hand_not_a_directory", "不是文件夹：${args["path"] ?: ""}")
        val entries = path.listFiles()?.sortedBy { it.name }?.map { entry ->
            mapOf("name" to entry.name, "kind" to if (entry.isDirectory) "directory" else "file")
        } ?: emptyList()
        return mapOf("path" to rel, "entries" to entries)
    }

    private fun read(args: Map<String, String>): Map<String, Any?> {
        val raw = args["path"] ?: ""
        val (rel, path) = resolve(raw)
        if (rel.isEmpty()) throw HandError("hand_not_a_file", "根目录本身不是文件：$raw")
        if (!path.isFile) throw HandError("hand_not_found", "文件不存在：$raw")
        val data = try {
            path.readBytes()
        } catch (e: Exception) {
            throw HandError("hand_read_failed", "读取失败：$raw")
        }
        if (data.size > MAX_TEXT_BYTES || data.contains(0)) {
            throw HandError("hand_not_text", "不是文本文件或超过上限：$raw")
        }
        return mapOf("path" to rel, "content" to data.toString(Charsets.UTF_8))
    }

    private fun write(args: Map<String, String>, taskId: String): Map<String, Any?> {
        val raw = args["path"] ?: ""
        val content = args["content"] ?: throw HandError("hand_bad_content", "缺少 content：$raw")
        if (content.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            throw HandError("hand_too_large", "内容超过上限：$raw")
        }
        val (rel, path) = resolve(raw)
        if (rel.isEmpty()) throw HandError("hand_not_a_file", "根目录本身不是文件：$raw")
        begin(taskId)
        workspaceAdapter.guardWrite(path)
        val (_, path2) = resolve(raw)
        if (path2 != path) throw HandError("hand_parent_changed", "写入路径在检查后发生变化：$raw")
        try {
            path.parentFile?.mkdirs()
            path.writeText(content, Charsets.UTF_8)
        } catch (e: Exception) {
            throw HandError("hand_write_failed", "写入失败：$raw")
        }
        return mapOf("path" to rel, "bytes" to content.toByteArray(Charsets.UTF_8).size)
    }

    private fun run(args: Map<String, String>, taskId: String): Map<String, Any?> {
        val argv = parseArgv(args["argv"])
            ?: throw HandError("hand_bad_argv", "不允许的命令：参数必须是非空字符串列表")
        try {
            limits.checkCommand(argv)
        } catch (e: LimitError) {
            throw HandError(e.code, e.message ?: "不允许的命令")
        }
        val (_, cwd) = resolve(args["cwd"] ?: "")
        if (!cwd.isDirectory) throw HandError("hand_not_a_directory", "不是文件夹：${args["cwd"] ?: ""}")
        val timeout = try {
            limits.checkTimeout(args["timeout"]?.toDoubleOrNull())
        } catch (e: LimitError) {
            throw HandError(e.code, e.message ?: "超时不合法")
        }
        if (!limits.runSlots.tryAcquire()) {
            throw HandError(
                "hand_concurrency_limited",
                "并发超限：同时运行的命令已达上限 ${limits.maxConcurrentRuns} 个",
            )
        }
        try {
            begin(taskId)
            workspaceAdapter.guardCommand(argv.joinToString(" "))
            val process = ProcessBuilder(argv).directory(cwd).start()
            val finished = process.waitFor(timeout.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw HandError("hand_timeout", "命令超时")
            }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            try {
                limits.checkOutput(stdout, stderr)
            } catch (e: LimitError) {
                throw HandError(e.code, e.message ?: "输出超限")
            }
            return mapOf(
                "exit_code" to process.exitValue(),
                "stdout" to stdout.take(MAX_OUTPUT_CHARS),
                "stderr" to stderr.take(MAX_OUTPUT_CHARS),
            )
        } catch (e: HandError) {
            throw e
        } catch (e: Exception) {
            throw HandError("hand_run_failed", "运行失败：${e.message ?: "未知错误"}")
        } finally {
            limits.runSlots.release()
        }
    }

    private fun parseArgv(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        return try {
            val array = JSONArray(raw)
            val list = (0 until array.length()).map { array.optString(it, "") }
            if (list.any { it.isEmpty() }) null else list
        } catch (_: Exception) {
            null
        }
    }

    private fun audit(
        taskId: String,
        action: String,
        args: Map<String, String>,
        ok: Boolean,
        errorCode: String?,
        result: Map<String, Any?>?,
    ) {
        val log = auditLog ?: return
        val meta = mutableMapOf<String, Any?>()
        if (action == "read" || action == "write") {
            result?.get("bytes")?.let { meta["bytes"] = it }
        }
        if (action == "list") {
            @Suppress("UNCHECKED_CAST")
            (result?.get("entries") as? List<*>)?.let { meta["entries"] = it.size }
        }
        if (action == "run") {
            result?.get("exit_code")?.let { meta["exit_code"] = it }
        }
        try {
            log.record(
                AuditEntry(
                    ts = utcNowIso(),
                    taskId = taskId,
                    agentId = agentId,
                    action = action,
                    path = args["path"],
                    command = parseArgv(args["argv"]),
                    ok = ok,
                    errorCode = errorCode,
                    result = meta.ifEmpty { null },
                ),
            )
        } catch (_: Exception) {
            // Audit must never break the hand call itself.
        }
    }

    companion object {
        const val MAX_TEXT_BYTES: Int = 2 * 1024 * 1024
        const val MAX_OUTPUT_CHARS: Int = 20000
    }
}

fun mapToJson(map: Map<String, Any?>): JSONObject {
    val obj = JSONObject()
    for ((key, value) in map) {
        when (value) {
            null -> obj.put(key, JSONObject.NULL)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                obj.put(key, mapToJson(value as Map<String, Any?>))
            }
            is List<*> -> {
                val array = JSONArray()
                for (item in value) {
                    when (item) {
                        null -> array.put(JSONObject.NULL)
                        is Map<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            array.put(mapToJson(item as Map<String, Any?>))
                        }
                        else -> array.put(item)
                    }
                }
                obj.put(key, array)
            }
            else -> obj.put(key, value)
        }
    }
    return obj
}

/**
 * JSON-RPC 2.0 stdio server. Methods: initialize, ping, tools/list,
 * tools/call. Notifications (no id) get no reply.
 *
 * Every tools/call goes through the single [ExecutionFence] (grant AND
 * credential AND epoch/goal-version AND control status). The fence is
 * injected by the caller: stdio uses [LocalExecutionFence] （本地模式）,
 * the LAN transport uses a full [ExecutionFence] with the signing key.
 * [ownDeviceId] is the expected credential target for the LAN fence;
 * null means the target binding is skipped (see ExecutionFence).
 */
class HandServer(
    private val hand: HandExecutor,
    private val fence: ExecutionFence,
    private val audit: AuditLog,
    private val replayGuard: ReplayGuard = ReplayGuard(),
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out,
    private val ownDeviceId: String? = null,
) {
    fun serve() {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val writer = PrintWriter(java.io.OutputStreamWriter(output, Charsets.UTF_8), true)
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val text = line!!.trim()
            if (text.isEmpty()) continue
            val response = handleLine(text) ?: continue
            writer.println(response)
            writer.flush()
        }
    }

    /** Returns the response JSON string, or null for notifications (no reply). */
    fun handleLine(line: String): String? {
        val request = try {
            JSONObject(line)
        } catch (_: Exception) {
            return errorResponse(null, -32700, "Parse error", "invalid_envelope")
        }
        val id = if (request.isNull("id")) null else request.opt("id")
        val method = request.optString("method", "")
        if (method.isEmpty()) {
            return errorResponse(id, -32600, "Invalid Request", "invalid_envelope")
        }
        if (id == null) {
            // Notification: no reply (e.g. notifications/initialized).
            return null
        }
        return when (method) {
            "initialize" -> successResponse(
                id,
                mapOf(
                    "protocolVersion" to HandProtocol.MCP_PROTOCOL_VERSION,
                    "serverInfo" to mapOf(
                        "name" to HandProtocol.MCP_SERVER_NAME,
                        "version" to HandProtocol.HAND_PROTOCOL_VERSION.toString(),
                    ),
                    "capabilities" to mapOf("tools" to emptyMap<String, Any?>()),
                ),
            )
            "ping" -> successResponse(id, emptyMap())
            "tools/list" -> {
                val tools = HandProtocol.MCP_TOOLS.map { tool ->
                    mapOf(
                        "name" to tool.name,
                        "description" to tool.description,
                        "inputSchema" to tool.inputSchema,
                    )
                }
                successResponse(id, mapOf("tools" to tools))
            }
            "tools/call" -> handleToolCall(id, request.optJSONObject("params"))
            else -> errorResponse(id, -32601, "Method not found: $method", "relay_error")
        }
    }

    private fun handleToolCall(id: Any?, params: JSONObject?): String {
        if (params == null) {
            return errorResponse(id, -32602, "Invalid params", "invalid_envelope")
        }
        val name = params.optString("name", "")
        if (name !in HandProtocol.HAND_ACTIONS) {
            return errorResponse(id, -32602, "未知工具：$name", "relay_error")
        }
        val arguments = params.optJSONObject("arguments")
        if (arguments == null) {
            return errorResponse(id, -32602, "缺少 arguments", "invalid_envelope")
        }
        val envelope = HandProtocol.parseJson(arguments.toString())
        if (envelope == null || envelope.action != name) {
            return errorResponse(id, -32602, "跨设备信封格式不正确，请重新下达任务。", "invalid_envelope")
        }
        val now = System.currentTimeMillis() / 1000
        if (!replayGuard.checkAndRecord(envelope.seq, now)) {
            return toolError(id, HandProtocol.Errors.STALE_SEQUENCE)
        }
        // The fence is the single gate: grant AND credential AND
        // epoch/goal-version AND control status. A refusal never reaches
        // the hand — hand.call is invoked zero times on denial.
        val credentialWire = if (arguments.isNull("credential")) null else arguments.optString("credential", "")
        val fenceResult = fence.check(
            FenceRequest(
                taskId = envelope.taskId,
                agentId = envelope.agentId,
                folder = envelope.folder,
                action = envelope.action,
                credentialWire = credentialWire,
                targetDeviceId = ownDeviceId,
            ),
        )
        if (!fenceResult.allowed) {
            return toolError(
                id,
                fenceResult.errorCode ?: HandProtocol.Errors.NOT_AUTHORIZED,
                fenceResult.messageZh,
            )
        }
        return try {
            val result = hand.call(envelope.action, envelope.args, envelope.taskId)
            val content = JSONObject()
            content.put("type", "text")
            content.put("text", mapToJson(result).toString())
            val resultObj = JSONObject()
            val contentArray = JSONArray()
            contentArray.put(content)
            resultObj.put("content", contentArray)
            resultObj.put("isError", false)
            successResponseRaw(id, resultObj)
        } catch (e: HandError) {
            toolError(id, e.code, e.message)
        }
    }

    private fun toolError(id: Any?, code: String, detail: String? = null): String {
        val message = detail ?: HandProtocol.Errors.message(code)
        val content = JSONObject()
        content.put("type", "text")
        content.put("text", "$message（$code）")
        val resultObj = JSONObject()
        val contentArray = JSONArray()
        contentArray.put(content)
        resultObj.put("content", contentArray)
        resultObj.put("isError", true)
        return successResponseRaw(id, resultObj)
    }

    private fun successResponse(id: Any?, result: Map<String, Any?>): String =
        successResponseRaw(id, mapToJson(result))

    private fun successResponseRaw(id: Any?, result: JSONObject): String {
        val obj = JSONObject()
        obj.put("jsonrpc", "2.0")
        if (id != null) obj.put("id", id) else obj.put("id", JSONObject.NULL)
        obj.put("result", result)
        return obj.toString()
    }

    private fun errorResponse(id: Any?, code: Int, message: String, errorCode: String): String {
        val obj = JSONObject()
        obj.put("jsonrpc", "2.0")
        if (id != null) obj.put("id", id) else obj.put("id", JSONObject.NULL)
        val error = JSONObject()
        error.put("code", code)
        error.put("message", message)
        val data = JSONObject()
        data.put("code", errorCode)
        data.put("message_zh", HandProtocol.Errors.message(errorCode))
        error.put("data", data)
        obj.put("error", error)
        return obj.toString()
    }
}

/**
 * stdio entry point.
 * Usage: HandServerKt <stateDir> <workspaceRoot> <taskRoot>
 *   or:  HandServerKt grant <stateDir> <agentId> <folder>
 * The server runs with cross-device explicitly enabled (starting it IS
 * the opt-in); calls still need an authorization record on this device.
 */
fun main(args: Array<String>) {
    if (args.size == 4 && args[0] == "grant") {
        val store = FileAuthorizationStore(File(args[1], "auth.json"))
        val record = store.grant(args[2], args[3])
        println("已授权：${record.agentId} 可在 ${record.folder} 上使用 hand")
        return
    }
    require(args.size == 3) {
        "用法：HandServerKt <stateDir> <workspaceRoot> <taskRoot>，或 HandServerKt grant <stateDir> <agentId> <folder>"
    }
    val stateDir = File(args[0])
    val workspaceRoot = File(args[1])
    val taskRoot = File(args[2])
    val auth = FileAuthorizationStore(File(stateDir, "auth.json"))
    val audit = FileAuditLog(File(stateDir, "audit.jsonl"))
    val workspaceAdapter = FileWorkspaceAdapter(workspaceRoot, taskRoot)
    val hand = LocalHand(
        workspaceRoot = workspaceRoot,
        taskRoot = taskRoot,
        workspaceAdapter = workspaceAdapter,
        auditLog = audit,
        enabled = true,
    )
    // 本地模式 (P1-3 closure): stdio callers are local to the device, so
    // no TaskCredential is required — but grant + control status are
    // still checked by LocalExecutionFence. The old "no credential
    // configured" empty implementation is gone: there is no path that
    // skips the fence. Task-state checking needs a TaskStore; the pure-
    // JVM stdio entry has no Room environment, so that check is skipped
    // here and must be wired by the Android/LAN entry that owns the DB.
    // The LAN transport (stage 4) MUST plug a full ExecutionFence with
    // the signing key instead of this local fence.
    val server = HandServer(
        hand = hand,
        fence = LocalExecutionFence(authStore = auth),
        audit = audit,
    )
    server.serve()
}
