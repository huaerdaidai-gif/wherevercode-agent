package wherevercode.agent

import android.content.Context
import java.io.File

/**
 * Agent discovery, ported from the server's `execution_host_agents.available_agents()`
 * (`agent_directory.py` is the single registry there; this table mirrors it).
 *
 * Same probe shape as the server:
 *   binary present? -> can it run `--version`? -> login state is UNKNOWN until
 *   one real run proves it (same honesty rule the server applies to agy:
 *   a credential file alone never proves a valid login).
 *
 * Key difference from the server, stated plainly: the app sandbox and Termux are
 * different UIDs and cannot see each other's files. The CLIs already running in
 * Termux on K70 / Xiaomi 12 (codex, claude, agy) are NOT visible or reusable here;
 * each agent must be installed again inside this app's sandbox. The discovery
 * *logic* is reused; the *binaries* are not.
 *
 * Only the app's own directories are scanned (filesDir/bin, nativeLibraryDir, PATH).
 */
class AgentDiscovery(private val context: Context) {

    enum class LoginState { UNKNOWN, LOGGED_IN, NOT_LOGGED_IN }

    data class AgentReport(
        val agentId: String,
        val displayName: String,
        val installed: Boolean,
        val version: String?,
        val loginState: LoginState,
        val unavailableReason: String?,
    )

    private data class Entry(
        val agentId: String,
        val displayName: String,
        val binary: String,
        val binaryEnv: String,
    )

    // Mirrors AGENT_DIRECTORY device CLI entries (binary + env override).
    // Keep in the same order as CliConfigs.ALL: codex, claude, muse,
    // gemini, codebuddy, agy, qwen, kimi, arkcli, grok.
    private val directory = listOf(
        Entry("codex-cli", "Codex CLI", "codex", "CODEX_BIN"),
        Entry("claude-code-cli", "Claude Code", "claude", "CLAUDE_BIN"),
        Entry("muse-code-cli", "Muse Code", "muse", "MUSE_BIN"),
        Entry("gemini-cli", "Gemini CLI", "gemini", "GEMINI_BIN"),
        Entry("codebuddy-cli", "CodeBuddy", "codebuddy", "CODEBUDDY_BIN"),
        Entry("agy", "Antigravity", "agy", "AGY_BIN"),
        Entry("qwen-code-cli", "Qwen Code", "qwen", "QWEN_BIN"),
        Entry("kimi-code-cli", "Kimi Code", "kimi", "KIMI_BIN"),
        Entry("ark-cli", "Ark CLI", "arkcli", "ARKCLI_BIN"),
        Entry("example-agent-cli", "Example Agent", "grok", "GROK_BIN"),
    )

    /** Extra binaries, same idea as the server's WHEREVERCODE_EXTRA_AGENT_BINS. */
    private fun extraBinaries(): List<String> =
        System.getenv("WHEREVERCODE_EXTRA_AGENT_BINS")
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun searchDirs(): List<File> {
        val dirs = mutableListOf<File>()
        dirs += File(context.filesDir, "bin")
        dirs += File(context.filesDir, "node/bin")
        context.applicationInfo.nativeLibraryDir?.let { dirs += File(it) }
        System.getenv("PATH")?.split(":")?.map { File(it) }?.let { dirs += it }
        return dirs.filter { it.isDirectory }
    }

    private fun findBinary(entry: Entry): File? {
        val override = System.getenv(entry.binaryEnv)?.trim().orEmpty()
        if (override.isNotEmpty()) {
            val f = File(override)
            if (f.isFile && f.canExecute()) return f
        }
        val name = entry.binary
        if (name.contains("/")) {
            val f = File(name)
            if (f.isFile && f.canExecute()) return f
            return null
        }
        return searchDirs()
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    private fun runVersion(binary: File): String? = try {
        val proc = ProcessBuilder(binary.absolutePath, "--version")
            .redirectErrorStream(true).start()
        val done = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!done) {
            proc.destroyForcibly()
            null
        } else {
            proc.inputStream.bufferedReader().readText().trim().take(200).ifEmpty { null }
        }
    } catch (_: Exception) {
        null
    }

    fun availableAgents(): List<AgentReport> {
        val reports = directory.map { entry ->
            val binary = findBinary(entry)
            if (binary == null) {
                AgentReport(
                    entry.agentId, entry.displayName, false, null,
                    LoginState.UNKNOWN, "沙箱内未发现（Termux/远端不在此列）",
                )
            } else {
                val version = runVersion(binary)
                AgentReport(
                    entry.agentId, entry.displayName, true, version,
                    // Login is unknown until a real -p run succeeds; never claim more.
                    LoginState.UNKNOWN,
                    if (version == null) "${entry.displayName}已安装但无法运行 --version，请重装" else null,
                )
            }
        }
        val known = directory.map { it.binary }.toSet()
        val extras = extraBinaries().filter { it !in known }.map { name ->
            val found = searchDirs().map { File(it, name) }
                .firstOrNull { it.isFile && it.canExecute() }
            AgentReport(
                "extra-$name", name, found != null,
                found?.let { runVersion(it) }, LoginState.UNKNOWN,
                if (found == null) "未发现 $name" else null,
            )
        }
        return reports + extras
    }

    /**
     * 阶段 3（A1 P1）：连接测试用的二进制探测。在沙箱搜索目录里找
     * [binary] 并跑 `--version`，返回版本输出；找不到或跑不出返回
     * null。只证明二进制可运行，不证明登录（见 AgentConnectionTester）。
     */
    fun probeBinary(binary: String): String? {
        val name = binary.trim()
        if (name.isEmpty()) return null
        val file = if (name.contains("/")) {
            File(name).takeIf { it.isFile && it.canExecute() }
        } else {
            searchDirs().map { File(it, name) }
                .firstOrNull { it.isFile && it.canExecute() }
        } ?: return null
        return runVersion(file)
    }

    fun report(): String {        val lines = StringBuilder("已装 Agent（App 沙箱内）：\n")
        for (r in availableAgents()) {
            lines.append("- ${r.displayName}：")
            if (!r.installed) {
                lines.append("沙箱内未发现（${r.unavailableReason}）\n")
            } else {
                lines.append("已安装")
                if (r.version != null) lines.append("，版本 ${r.version}")
                lines.append("，登录状态：未知（需一次真实运行确认）\n")
            }
        }
        lines.append("注意：Termux 里的 Agent 与本 App 沙箱互不可见，需在 App 内重装。")
        return lines.toString()
    }
}
