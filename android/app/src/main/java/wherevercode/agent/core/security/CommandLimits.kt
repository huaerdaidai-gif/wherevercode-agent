package wherevercode.agent.core.security

import java.util.concurrent.Semaphore

/**
 * CommandLimits + RunSlotGuard, ported VERBATIM from the old project's
 * `cross_device_limits.py` (blob 72cd7f07, main fd6b43d2).
 *
 * Old -> new name mapping:
 * - DEFAULT_ALLOWED_COMMANDS -> DEFAULT_ALLOWED_COMMANDS (same 20 names, same order)
 * - GIT_ALLOWED_SUBCOMMANDS -> GIT_ALLOWED_SUBCOMMANDS
 * - validate_command(argv) -> validateCommand(argv)
 * - CommandLimits -> CommandLimits (check_command/check_timeout/check_output)
 * - RunSlotGuard -> RunSlotGuard
 * - LimitError -> LimitError
 *
 * The `run` action may only invoke build / test / view commands.
 * Deliberately absent: shells (sh, bash, zsh), downloaders (curl, wget),
 * destructive tools (rm, mv, chmod, chown), privilege escalation
 * (sudo, su), and anything that fetches and executes remote code.
 * git is allowlisted but only for read-only subcommands; inline-code
 * flags (`python -c`, `node -e`) are refused — an allowlisted
 * interpreter must run a file, not an inline string smuggled past the
 * list. All refusals carry a Chinese reason.
 */
val DEFAULT_ALLOWED_COMMANDS: List<String> = listOf(
    "python", "python3", "pytest", "npm", "node", "npx",
    "go", "cargo", "make", "git",
    "ls", "cat", "head", "tail", "grep", "find", "wc", "echo", "pwd",
    "sleep",
)

val GIT_ALLOWED_SUBCOMMANDS: List<String> = listOf("status", "diff", "log", "show")

private val CODE_FLAGS: Map<String, Set<String>> = mapOf(
    "python" to setOf("-c"),
    "python3" to setOf("-c"),
    "node" to setOf("-e", "--eval"),
)

class LimitError(val code: String, message: String) : IllegalArgumentException(message)

/**
 * 2026-11-13 安全加固：白名单命令自身就能执行任意程序或写任意文件的参数。
 * 白名单只看 argv[0] 时，`find . -exec sh -c …` 等于绕过整张白名单；
 * `git diff --output=/任意路径` 能把文件写到工作区之外。
 */
private val FIND_DANGEROUS_ACTIONS: Set<String> = setOf(
    "-exec", "-execdir", "-ok", "-okdir", "-delete", "-fls", "-fprint", "-fprint0", "-fprintf",
)

/** git 只读子命令里仍会写文件 / 调外部程序的选项：精确匹配或 `选项=值`。 */
private val GIT_DANGEROUS_OPTIONS: Set<String> = setOf(
    "--output", "--exec", "--upload-pack", "--receive-pack",
)

/** git 前缀匹配的危险选项（没有同前缀的无害选项）。`--no-ext-diff` 等否定形式不受影响。 */
private val GIT_DANGEROUS_OPTION_PREFIXES: List<String> = listOf("--ext-diff", "--textconv")

/**
 * 命令参数级拒绝原因（null = 通过）。与 argv[0] 白名单、git 子命令白名单
 * 叠加生效，ToolRegistry 与 HandServer 共用（经 [CommandLimits.checkCommand]）。
 */
fun dangerousArgumentReason(argv: List<String>): String? {
    if (argv.isEmpty()) return null
    val name = argv[0].substringAfterLast("/")
    val rest = argv.drop(1)
    when (name) {
        "find" -> {
            val hit = rest.firstOrNull { it in FIND_DANGEROUS_ACTIONS }
            if (hit != null) return "不允许的命令：find 不允许 $hit（会执行其它程序或删除/写入文件）"
        }
        "git" -> {
            val hit = rest.firstOrNull { arg ->
                arg.substringBefore('=') in GIT_DANGEROUS_OPTIONS ||
                    GIT_DANGEROUS_OPTION_PREFIXES.any { arg.startsWith(it) }
            }
            if (hit != null) return "不允许的 git 参数：${hit.substringBefore('=')}（可能写文件或调用外部程序）"
        }
        "python", "python3" -> {
            // `-c` 的合并写法：`-cprint(1)`、`-uc`、`-Bc` 等短选项簇。只看
            // 脚本/模块名之前的选项（之后的参数属于脚本自己）。
            var i = 0
            scan@ while (i < rest.size) {
                val arg = rest[i]
                i++
                if (!arg.startsWith("-") || arg.startsWith("--") || arg == "-") break
                // 短选项簇逐字符看：c = 内联代码；m = 模块（其后都是模块参数）；
                // W / X 带参数：簇里余下的字符是它的值；簇以 W/X 结尾时下一个
                // argv 是它的值，跳过。
                for ((pos, ch) in arg.drop(1).withIndex()) {
                    when (ch) {
                        'c' -> return "不允许的命令：$name 不允许内联代码执行"
                        'm' -> break@scan
                        'W', 'X' -> {
                            if (pos == arg.length - 2) i++
                            continue@scan
                        }
                    }
                }
            }
        }
        "node" -> {
            for (arg in rest) {
                if (!arg.startsWith("-")) break
                val evalFlag = arg == "-e" || arg == "-p" || arg == "--eval" || arg == "--print" ||
                    arg.startsWith("--eval=") || arg.startsWith("--print=") ||
                    (!arg.startsWith("--") && arg.length > 2 && arg.drop(1).all { it.isLetter() } &&
                        (arg.contains('e') || arg.contains('p')))
                if (evalFlag) return "不允许的命令：node 不允许内联代码执行"
            }
        }
    }
    return null
}

/** Returns (allowed, chineseReasonOrNull) for one `run` argv. */
fun validateCommand(argv: List<String>?): Pair<Boolean, String?> {
    if (argv == null || argv.isEmpty() || argv.any { it.isEmpty() }) {
        return false to "不允许的命令：参数必须是非空字符串列表"
    }
    val name = argv[0].substringAfterLast("/")
    if (name !in DEFAULT_ALLOWED_COMMANDS) {
        return false to "不允许的命令：$name"
    }
    if (name == "git") {
        if (argv.size < 2 || argv[1] !in GIT_ALLOWED_SUBCOMMANDS) {
            val sub = if (argv.size > 1) argv[1] else ""
            return false to "不允许的 git 子命令：$sub（仅允许 ${GIT_ALLOWED_SUBCOMMANDS.joinToString("、")}）"
        }
    }
    val flags = CODE_FLAGS[name] ?: emptySet()
    if (argv.drop(1).any { it in flags }) {
        return false to "不允许的命令：$name 不允许内联代码执行"
    }
    dangerousArgumentReason(argv)?.let { return false to it }
    return true to null
}

class CommandLimits(
    val allowedCommands: List<String> = DEFAULT_ALLOWED_COMMANDS,
    val timeoutSeconds: Double = 30.0,
    val maxTimeoutSeconds: Double = 300.0,
    val maxConcurrentRuns: Int = 2,
    val outputMaxBytes: Int = 200000,
    val replayWindowSeconds: Int = 300,
) {
    /** One shared concurrency gate for `run` actions on this device. */
    val runSlots: RunSlotGuard = RunSlotGuard(maxConcurrentRuns)

    /** Raises LimitError (code + Chinese reason) when argv is refused. */
    fun checkCommand(argv: List<String>?) {
        if (argv == null || argv.isEmpty() || argv.any { it.isEmpty() }) {
            throw LimitError("hand_bad_argv", "不允许的命令：参数必须是非空字符串列表")
        }
        val name = argv[0].substringAfterLast("/")
        if (name !in allowedCommands) {
            throw LimitError("hand_command_not_allowed", "不允许的命令：$name")
        }
        if (name == "git") {
            if (argv.size < 2 || argv[1] !in GIT_ALLOWED_SUBCOMMANDS) {
                val sub = if (argv.size > 1) argv[1] else ""
                throw LimitError(
                    "hand_command_not_allowed",
                    "不允许的 git 子命令：$sub（仅允许 ${GIT_ALLOWED_SUBCOMMANDS.joinToString("、")}）",
                )
            }
        }
        val flags = CODE_FLAGS[name] ?: emptySet()
        if (argv.drop(1).any { it in flags }) {
            throw LimitError("hand_command_not_allowed", "不允许的命令：$name 不允许内联代码执行")
        }
        dangerousArgumentReason(argv)?.let { throw LimitError("hand_command_not_allowed", it) }
    }

    /** Resolve the effective timeout, or raise LimitError. A larger requested timeout is refused, not silently clamped. */
    fun checkTimeout(requested: Double?): Double {
        if (requested == null) return timeoutSeconds
        if (requested.isNaN() || requested <= 0) {
            throw LimitError("hand_bad_timeout", "不允许的超时：超时必须大于 0")
        }
        if (requested > maxTimeoutSeconds) {
            throw LimitError(
                "hand_timeout_exceeded",
                "超时超限：请求的超时 ${formatNumber(requested)} 秒超过上限 ${formatNumber(maxTimeoutSeconds)} 秒",
            )
        }
        return requested
    }

    /** Raise LimitError when combined output exceeds the byte cap. */
    fun checkOutput(stdout: String, stderr: String) {
        val total = (stdout).toByteArray(Charsets.UTF_8).size +
            (stderr).toByteArray(Charsets.UTF_8).size
        if (total > outputMaxBytes) {
            throw LimitError(
                "hand_output_too_large",
                "输出超限：输出共 $total 字节，超过上限 $outputMaxBytes 字节",
            )
        }
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}

/** Non-blocking concurrency gate for `run` actions on one device. */
class RunSlotGuard(private val maxConcurrent: Int = 2) {
    private val semaphore = Semaphore(maxConcurrent)

    fun tryAcquire(): Boolean = semaphore.tryAcquire()

    fun release() {
        semaphore.release()
    }

    fun acquireOrRaise() {
        if (!tryAcquire()) {
            throw LimitError(
                "hand_concurrency_limited",
                "并发超限：同时运行的命令已达上限 $maxConcurrent 个",
            )
        }
    }
}
