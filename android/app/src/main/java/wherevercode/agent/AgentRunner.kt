package wherevercode.agent

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import wherevercode.agent.brain.cli.CliConfigs

/**
 * Runs one real agent CLI in the sandbox workspace and streams its output.
 *
 * CodeBuddy flags match the server's verified CodeBuddyRunner (agent_runners.py,
 * checked 2026-11-06 against the vendor CLI reference on workbuddy.ai):
 *   codebuddy -p --output-format stream-json --permission-mode acceptEdits "<prompt>"
 * The prompt lives in argv only. Login is the user's own account, via the
 * in-app browser flow; no API key is used (per the task).
 *
 * Codex fallback copies the server's verified CodexRunner invocation:
 *   codex exec --json --skip-git-repo-check --sandbox workspace-write --cd <dir> -
 * with the prompt on stdin.
 */

/**
 * Structured outcome of one CLI invocation (C3-D hook).
 *
 * [AgentRunner.runPrompt] keeps returning its historic human-readable
 * summary string; [AgentRunner.runPromptDetailed] returns one of these so
 * callers that need the exit code / a login-state decision (e.g. the CLI
 * membership brain adapter and its probe) do not have to parse the summary
 * text. The summary strings inside [Success] / [Failed] / [Timeout] are
 * byte-identical to what [AgentRunner.runPrompt] used to return, so the
 * chat fallback path is unchanged.
 */
sealed class AgentRunResult {
    data class Success(val text: String, val lines: List<String>) : AgentRunResult()
    data class Failed(val exitCode: Int, val text: String, val lines: List<String>) : AgentRunResult()
    data class Timeout(val text: String, val lines: List<String>) : AgentRunResult()
    data class NotInstalled(val reason: String) : AgentRunResult()
}

class AgentRunner(private val context: Context, private val runtime: RuntimeEnv) {

    private fun runStreamingDetailed(
        argv: List<String>,
        stdinText: String?,
        onLine: (String) -> Unit,
        timeoutSeconds: Long = 600,
        onRawLine: (String) -> Unit = {},
    ): AgentRunResult {
        val proc = ProcessBuilder(argv)
            .directory(runtime.workspaceDir)
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = context.filesDir.absolutePath
                environment()["PATH"] = listOf(
                    File(context.filesDir, "node/bin").absolutePath,
                    File(context.filesDir, "bin").absolutePath,
                    System.getenv("PATH") ?: "/system/bin:/system/xbin",
                ).joinToString(":")
            }
            .start()
        if (stdinText != null) {
            proc.outputStream.bufferedWriter().use { it.write(stdinText) }
        }
        val reader = proc.inputStream.bufferedReader()
        val lastLines = ArrayDeque<String>()
        val outputLock = Any()
        val readerThread = Thread {
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line!!
                    // Full line for stream-json parsers (C3-D); onLine keeps
                    // its historic 500-char truncation for the chat timeline.
                    onRawLine(text)
                    onLine(text.take(500))
                    synchronized(outputLock) {
                        lastLines.addLast(text)
                        if (lastLines.size > 20) lastLines.removeFirst()
                    }
                }
            } catch (_: Exception) {
            }
        }.apply {
            name = "agent-cli-output-reader"
            isDaemon = true
            start()
        }
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            // The process may still have buffered output after destroyForcibly()
            // returns. Reap it and let the reader publish that output before
            // taking the timeout snapshot.
            proc.waitFor()
            readerThread.join(5_000)
            val tail = synchronized(outputLock) { lastLines.toList() }
            val captureNote = if (readerThread.isAlive) "\n[输出流未关闭，当前尾部可能不完整]" else ""
            return AgentRunResult.Timeout(
                "Agent 超时（>${timeoutSeconds}s），已杀掉$captureNote",
                tail,
            )
        }
        // waitFor() only observes the process exit; it does not wait for the
        // independent stdout reader. Without this join, a fast CLI can exit
        // while its final lines are still queued, so the returned transcript
        // (and final chat result) is intermittently empty or truncated.
        readerThread.join(5_000)
        val tail = synchronized(outputLock) { lastLines.toList() }
        val captureNote = if (readerThread.isAlive) "\n[输出流未关闭，当前尾部可能不完整]" else ""
        val code = proc.exitValue()
        val tailText = tail.joinToString("\n").take(3000) + captureNote
        return if (code == 0) {
            AgentRunResult.Success("Agent 完成（exit=0）。最后输出：\n$tailText", tail)
        } else {
            AgentRunResult.Failed(code, "Agent 失败（exit=$code）。最后输出：\n$tailText", tail)
        }
    }

    /** CodeBuddy first, Codex as fallback. Returns a human-readable summary. */
    fun runPrompt(prompt: String, onLine: (String) -> Unit): String =
        when (val result = runPromptDetailed(prompt, null, onLine)) {
            is AgentRunResult.Success -> result.text
            is AgentRunResult.Failed -> result.text
            is AgentRunResult.Timeout -> result.text
            is AgentRunResult.NotInstalled -> result.reason
        }

    /**
     * Structured version of [runPrompt] (C3-D hook for the CLI membership
     * brain adapter and its probe).
     *
     * - binary == null: the historic fallback order (CodeBuddy first,
     *   Codex second), with the same onLine progress lines as [runPrompt].
     * - binary == "codebuddy" / "codex": only that CLI, with its already
     *   verified invocation (same argv [runPrompt] uses). Any other name
     *   returns [AgentRunResult.NotInstalled]: no probe command is
     *   invented for CLIs whose invocation has not been verified on-device
     *   (audit section 4.2 — no `codex auth status` style guesses).
     * - [onRawLine] receives every output line untruncated (from the
     *   reader thread); [onLine] keeps its historic 500-char truncation.
     *   Callers that parse stream-json must use [onRawLine].
     */
    fun runPromptDetailed(
        prompt: String,
        binary: String? = null,
        onLine: (String) -> Unit = {},
        timeoutSeconds: Long = 600,
        onRawLine: (String) -> Unit = {},
    ): AgentRunResult {
        if (binary == null) {
            val codebuddy = runtime.codeBuddyBinary()
            if (codebuddy != null) {
                onLine("用 CodeBuddy 跑：${codebuddy.absolutePath}")
                return runVerifiedBinary(
                    "codebuddy", codebuddy.absolutePath, prompt,
                    onLine, timeoutSeconds, onRawLine,
                )
            }
            onLine("CodeBuddy 未安装，改试 Codex。")
            val codex = findOnPath("codex")
                ?: return AgentRunResult.NotInstalled("CodeBuddy 和 Codex 都没装：先点“安装 Agent”。")
            onLine("用 Codex 跑：$codex")
            return runVerifiedBinary(
                "codex", codex, prompt, onLine, timeoutSeconds, onRawLine,
            )
        }
        return when (binary) {
            "codebuddy" -> {
                val codebuddy = runtime.codeBuddyBinary()
                    ?: return AgentRunResult.NotInstalled("CodeBuddy 未安装：沙箱内未找到 codebuddy 二进制。")
                onLine("用 CodeBuddy 跑：${codebuddy.absolutePath}")
                runVerifiedBinary(
                    "codebuddy", codebuddy.absolutePath, prompt,
                    onLine, timeoutSeconds, onRawLine,
                )
            }
            "codex" -> {
                val codex = findOnPath("codex")
                    ?: return AgentRunResult.NotInstalled("Codex 未安装：沙箱内未找到 codex 二进制。")
                onLine("用 Codex 跑：$codex")
                runVerifiedBinary(
                    "codex", codex, prompt, onLine, timeoutSeconds, onRawLine,
                )
            }
            else -> AgentRunResult.NotInstalled(
                "该 CLI（$binary）暂无已验证的调用方式：第一阶段只支持 codebuddy / codex 的已验证调用，不发明其他 CLI 的探针命令。",
            )
        }
    }

    /**
     * Run one VERIFIED CLI row: the argv comes from the configuration
     * table ([CliConfigs]) so the template lives in exactly one place.
     * The argv produced here is byte-identical to the historic hardcoded
     * invocations (codebuddy `-p --output-format stream-json
     * --permission-mode acceptEdits <prompt>` on argv, codex `exec
     * --json ... --cd <workspace> -` with the prompt on stdin) — the
     * table is pinned by CliConfigTest. A row without a verified
     * template is refused the same way as an unknown binary.
     */
    private fun runVerifiedBinary(
        binary: String,
        binaryPath: String,
        prompt: String,
        onLine: (String) -> Unit,
        timeoutSeconds: Long,
        onRawLine: (String) -> Unit,
    ): AgentRunResult {
        val config = CliConfigs.forBinary(binary)
        val argv = config?.buildArgv(binaryPath, prompt, runtime.workspaceDir.absolutePath)
            ?: return AgentRunResult.NotInstalled(
                "该 CLI（$binary）暂无已验证的调用方式：第一阶段只支持 codebuddy / codex 的已验证调用，不发明其他 CLI 的探针命令。",
            )
        return runStreamingDetailed(
            argv,
            stdinText = if (config.promptViaStdin) prompt else null,
            onLine = onLine,
            timeoutSeconds = timeoutSeconds,
            onRawLine = onRawLine,
        )
    }

    private fun findOnPath(name: String): String? {
        val override = System.getenv("CODEX_BIN")?.trim().orEmpty()
        if (override.isNotEmpty() && File(override).canExecute()) return override
        val dirs = listOf(
            File(context.filesDir, "node/bin"),
            File(context.filesDir, "bin"),
        ) + (System.getenv("PATH")?.split(":")?.map { File(it) } ?: emptyList())
        return dirs.map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }
}
