package wherevercode.agent.brain

import java.security.MessageDigest
import wherevercode.agent.core.security.SecretRedactor

/**
 * 2026-11-09 审批安全：一次授权的"操作指纹"。
 *
 * 为什么需要：
 * - 授权与执行之间存在时间窗。若 Action 的 args 在授权后被改动（例如
 *   同一 map 被复用/改写），执行的就可能不是用户批准的那个操作。
 *   执行前用同一函数重算指纹，不一致即拒绝（approval_action_changed）。
 * - 审批对话框只展示指纹对应的参数；展示层与执行层用同一份规范化
 *   输入，避免"看的是一个、跑的是另一个"。
 *
 * 规范化规则（与 JSON 键序无关）：
 * - Map 按 key 排序；List 按顺序；String 带长度前缀，避免分隔符歧义；
 * - null / Boolean / Number 各自带类型标签；
 * - 结果取 SHA-256 十六进制。
 */
object ApprovalFingerprint {

    fun of(action: Action): String = of(action.tool, action.args)

    fun of(tool: String, args: Map<String, Any?>): String {
        val canonical = canonical(tool, args)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 扁平化前的完整规范串；公开以便测试直接断言键序无关性。 */
    fun canonical(tool: String, args: Map<String, Any?>): String {
        val out = StringBuilder()
        out.append("tool=").append(encodeString(tool))
        out.append(";args=")
        encodeValue(out, args)
        return out.toString()
    }

    /**
     * 执行前冻结参数：把 Map/List 递归复制成不可变快照。
     * 之后的任何外部改动都不会影响本次执行。
     */
    fun freeze(action: Action): Action = action.copy(args = freezeArgs(action.args))

    fun freezeArgs(args: Map<String, Any?>): Map<String, Any?> {
        val copy = LinkedHashMap<String, Any?>(args.size)
        for ((key, value) in args) copy[key] = freezeValue(value)
        return java.util.Collections.unmodifiableMap(copy)
    }

    private fun freezeValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            val copy = LinkedHashMap<String, Any?>(value.size)
            for ((key, item) in value) copy[key.toString()] = freezeValue(item)
            java.util.Collections.unmodifiableMap(copy)
        }
        is List<*> -> java.util.Collections.unmodifiableList(value.map { freezeValue(it) })
        is Array<*> -> java.util.Collections.unmodifiableList(value.map { freezeValue(it) })
        else -> value
    }

    private fun encodeValue(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> encodeString(out, value)
            is Boolean -> out.append("b:").append(if (value) '1' else '0')
            is Number -> out.append("n:").append(value.toString())
            is Map<*, *> -> {
                val entries = value.entries
                    .map { it.key.toString() to it.value }
                    .sortedBy { it.first }
                out.append("m{")
                for ((key, item) in entries) {
                    encodeString(out, key)
                    out.append('=')
                    encodeValue(out, item)
                    out.append(';')
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append("l[")
                for (item in value) {
                    encodeValue(out, item)
                    out.append(';')
                }
                out.append(']')
            }
            is Array<*> -> encodeValue(out, value.toList())
            else -> encodeString(out, value.toString())
        }
    }

    private fun encodeString(out: StringBuilder, value: String) {
        out.append("s:").append(value.length).append(':').append(value)
    }

    private fun encodeString(value: String): String {
        val out = StringBuilder()
        encodeString(out, value)
        return out.toString()
    }
}

/**
 * 审批弹窗文案（2026-11-09 敏感信息展示修复）。
 *
 * 规则：
 * - 只展示"用户需要据此决策"的最小信息：工具、工作区内的目标路径、
 *   run_command 的 argv（脱敏）、写入内容长度；
 * - 绝不展示文件正文、API Key、环境变量、命令输出；
 * - 所有外部字符串先过 [SecretRedactor] 再截断，避免 sk-… / Bearer…
 *   之类的内容出现在弹窗里。
 *
 * 纯函数：不依赖 Android，可直接单测。
 */
object ApprovalPromptFormatter {

    private const val MAX_FIELD_CHARS = 160

    fun title(action: Action): String = "确认 Agent 工具操作"

    fun message(action: Action, timeoutSeconds: Long = 60): String = buildString {
        append("工具：").append(toolLabel(action.tool))
        targetSummary(action)?.let { append("\n\n").append(it) }
        append("\n\n本次操作需要你的明确批准。")
        append("\n弹窗不会展示文件正文、API Key、环境变量或命令输出。")
        append("\n$timeoutSeconds 秒内未操作将自动拒绝。")
    }

    fun toolLabel(tool: String): String = when (tool) {
        "write_file" -> "write_file（写入/覆盖文件）"
        "run_command" -> "run_command（运行命令）"
        "dispatch_task" -> "dispatch_task（派发任务）"
        "read_file" -> "read_file（读取文件）"
        "list_files" -> "list_files（列出目录）"
        "ask_user" -> "ask_user（向用户提问）"
        else -> tool
    }

    private fun targetSummary(action: Action): String? {
        return when (action.tool) {
            "write_file" -> {
                val path = safeText(action.args["path"] as? String) ?: return null
                val content = action.args["content"] as? String
                val size = content?.toByteArray(Charsets.UTF_8)?.size
                val sizeText = if (size != null) "（内容 $size 字节，不在弹窗展示）" else ""
                "目标文件：$path$sizeText"
            }
            "read_file", "list_files" -> {
                val path = safeText(action.args["path"] as? String) ?: return null
                "目标路径：$path"
            }
            "run_command" -> {
                val argv = action.args["argv"]
                val rendered = when (argv) {
                    is List<*> -> argv.joinToString(" ") { safeText(it?.toString()) ?: "" }
                    is String -> argv
                    else -> ""
                }
                if (rendered.isBlank()) {
                    "命令：未提供"
                } else {
                    "命令：" + safeText(rendered, 400)
                }
            }
            "dispatch_task" -> {
                val goal = safeText(action.args["goal"] as? String) ?: return null
                "任务目标：$goal"
            }
            else -> null
        }
    }

    /** 脱敏 + 压缩空白 + 截断。null/空白返回 null（弹窗里写"未提供"更诚实）。 */
    fun safeText(value: String?, maxChars: Int = MAX_FIELD_CHARS): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val redacted = SecretRedactor.redact(raw, 4_000)
        val flattened = redacted.replace(Regex("[\\r\\n\\t]+"), " ")
        val limit = maxChars.coerceAtLeast(1)
        return if (flattened.length > limit) flattened.take(limit) + "…" else flattened
    }
}
