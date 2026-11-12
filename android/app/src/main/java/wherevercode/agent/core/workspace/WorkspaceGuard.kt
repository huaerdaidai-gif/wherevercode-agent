package wherevercode.agent.core.workspace

/**
 * WorkspaceGuard: write guard classification (stage 2).
 *
 * Ported from the old project's `execution_host_workspace_guard.py`
 * `command_may_write()` / `gemini_read_only_tool_blocked()` (blob
 * 66006e3a, main fd6b43d2). Same classification, same fail-closed rule:
 * an ambiguous command is treated as "may write", so the caller takes
 * the protected path (snapshot first) instead of assuming read-only.
 *
 * Old -> new name mapping:
 * - command_may_write(command) -> commandMayWrite(command)
 * - gemini_read_only_tool_blocked(command) -> readOnlyToolBlocked(label)
 */
object WorkspaceGuard {
    enum class CommandClass {
        READ_ONLY,
        MAY_WRITE,
    }

    private val SHELL_METACHARS: List<String> = listOf(
        ";", "|", "&", ">", "<", "`", "\$(", "\n", "\r",
    )

    private val READ_ONLY_COMMANDS: Set<String> = setOf(
        "pwd", "ls", "cat", "head", "tail", "less", "more", "grep", "rg",
        "file", "stat", "wc", "git", "echo", "printf", "which",
        "true", "false", "uname", "date",
    )

    /**
     * True when the command MAY write (or is ambiguous): the caller must
     * snapshot first. False only for the small proven read-only set.
     * Ported verbatim from command_may_write().
     */
    fun commandMayWrite(command: String): Boolean {
        if (SHELL_METACHARS.any { it in command }) return true
        val parts = splitShell(command) ?: return true
        if (parts.isEmpty()) return false
        val executable = parts[0].substringAfterLast("/")
        if (executable == "env" || executable == "printenv") return parts.size > 1
        if (executable == "find") {
            val dangerous = setOf("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fls")
            return parts.drop(1).any { arg ->
                arg in dangerous || arg.startsWith("-fprint") || arg.startsWith("-fprint0")
            }
        }
        if (executable == "git") {
            return !(parts.size > 1 && parts[1] in setOf("status", "log", "diff", "show", "rev-parse"))
        }
        if (executable in setOf("python", "python3") && parts.size >= 3 &&
            parts[1] == "-m" && parts[2] == "unittest"
        ) {
            return true
        }
        if (executable == "node" && parts.size >= 2 && parts[1] == "--test") return true
        return executable !in READ_ONLY_COMMANDS
    }

    fun classify(command: String): CommandClass =
        if (commandMayWrite(command)) CommandClass.MAY_WRITE else CommandClass.READ_ONLY

    /**
     * Whether a read-only run must stop on this tool label.
     * Labels: "读取 <path>" / "搜索 <pattern>" / "查找文件 <pattern>" are
     * provably non-mutating; "修改 ..." always blocks; "运行 ..." blocks
     * when the command may write; anything unknown blocks (fail closed).
     * Ported from gemini_read_only_tool_blocked().
     */
    fun readOnlyToolBlocked(label: String): Boolean {
        val value = label
        if (value.startsWith("修改")) return true
        if (value.startsWith("运行")) return commandMayWrite(value.removePrefix("运行").trim())
        return !(value.startsWith("读取") || value.startsWith("搜索") || value.startsWith("查找文件"))
    }

    /** Minimal shell-word split (quotes aware); null when unparseable. */
    fun splitShell(command: String): List<String>? {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaped = false
        var hasToken = false
        for (ch in command) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' && !inSingle -> escaped = true
                ch == '\'' && !inDouble -> {
                    inSingle = !inSingle
                    hasToken = true
                }
                ch == '"' && !inSingle -> {
                    inDouble = !inDouble
                    hasToken = true
                }
                ch.isWhitespace() && !inSingle && !inDouble -> {
                    if (hasToken || current.isNotEmpty()) {
                        out += current.toString()
                        current.clear()
                        hasToken = false
                    }
                }
                else -> {
                    current.append(ch)
                    hasToken = true
                }
            }
        }
        if (escaped || inSingle || inDouble) return null
        if (hasToken || current.isNotEmpty()) out += current.toString()
        return out
    }
}
