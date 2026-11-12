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
