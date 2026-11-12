package wherevercode.agent.brain.cli

import java.util.Collections
import wherevercode.agent.AgentRunResult
import wherevercode.agent.AgentRunner
import wherevercode.agent.brain.BrainEvent
import wherevercode.agent.brain.StreamParser

/** Failure kinds for one CLI call, feeding the adapter's login-state decision. */
enum class CliFailureKind {
    NOT_INSTALLED,
    AUTH,
    QUOTA,
    TRANSPORT,
    TIMEOUT,
    UNSUPPORTED_BINARY,
}

sealed class CliCallResult {
    /**
     * The CLI finished (exit 0). [text] is the accumulated answer text;
     * [events] the parsed stream events in order (per-line Done framing
     * excluded — the adapter emits its own Done).
     */
    data class Success(val text: String, val events: List<BrainEvent>) : CliCallResult()
    data class Failure(val kind: CliFailureKind, val summary: String) : CliCallResult()
}

/**
 * Minimal execution layer for the CLI membership brain (C3-D).
 *
 * Rules (audit section 4.2):
 * - Whitelist only: the only runnable binaries are the VERIFIED rows of
 *   the CLI configuration table ([CliConfigs]) — today codebuddy
 *   (`-p --output-format stream-json --permission-mode acceptEdits`)
 *   and codex (`exec --json ...`). Any other binary name is refused as
 *   [CliFailureKind.UNSUPPORTED_BINARY] — no probe command is invented
 *   for it (no `codex auth status`, no `codebuddy auth`, nothing
 *   unproven). The four other CLIs AgentDiscovery knows (claude, muse,
 *   gemini, agy) are in the table as UNVERIFIED rows (name + binary
 *   only, no template) and are refused the same way, without spawning
 *   a process.
 * - The only trusted login signal is a real CLI call. `--version` is
 *   never consulted here and never proves a login.
 * - The probe prompt ([PROBE_PROMPT]) is one fixed, read-only word that
 *   asks for nothing and writes no file. It still consumes one
 *   membership call by design, so callers must only run it when the user
 *   explicitly taps "验证一次" — this class never polls, never runs at
 *   startup, and never retries on its own.
 * - Output parsing follows the row's [CliConfig.parseMode]: stream-json
 *   rows reuse [StreamParser] (known stream-json shapes only); a
 *   plain-text row surfaces every line verbatim.
 *   A line that is valid JSON but of unknown shape is protocol framing
 *   (e.g. system/init) and stays dropped; a line that is not JSON at all
 *   is the CLI's own plain-text answer and is surfaced verbatim
 *   (truncated to 500 chars). Nothing is silently dropped as "unknown"
 *   while still being the answer.
 * - The CLI process runs synchronously (AgentRunner blocks until it
 *   exits), so [run] returns only after the run finishes: the parsed
 *   events are delivered in the result, not live. The adapter emits them
 *   as TextDelta events after the run; live per-line streaming is a
 *   later batch (it needs the reader thread to feed a Channel instead
 *   of a callback). This is a known stage-1 limitation, not a claim of
 *   live streaming.
 * - The process spawn itself is injectable ([invoke]) so tests use a
 *   fake and never run a real CLI.
 */
class CliRunner(
    private val invoke: (prompt: String, binary: String, timeoutSeconds: Long, onRawLine: (String) -> Unit) -> AgentRunResult,
) {

    /** Build the production runner over a real [AgentRunner]. */
    constructor(agentRunner: AgentRunner) : this(
        invoke = { prompt, binary, timeoutSeconds, onRawLine ->
            agentRunner.runPromptDetailed(prompt, binary, {}, timeoutSeconds, onRawLine)
        },
    )

    /**
     * Run one real CLI call and return its parsed outcome. [binary] must
     * be a VERIFIED row of [CliConfigs]; anything else fails fast without
     * spawning a process. [timeoutSeconds] overrides the row's chat
     * budget; null means the row's own [CliConfig.chatTimeoutSeconds].
     */
    fun run(prompt: String, binary: String, timeoutSeconds: Long? = null): CliCallResult {
        val config = CliConfigs.forBinary(binary)
        if (config == null || !config.verified) {
            return CliCallResult.Failure(
                CliFailureKind.UNSUPPORTED_BINARY,
                if (config == null) {
                    "该 CLI（$binary）暂无已验证的调用方式：第一阶段只支持 codebuddy / codex 的已验证调用，不发明其他 CLI 的探针命令。"
                } else {
                    "该 CLI（$binary）已列入配置表，但调用方式未核实：不发明探针命令、不跑提示词，等真机核实后再接线。"
                },
            )
        }
        val effectiveTimeout = timeoutSeconds ?: config.chatTimeoutSeconds
        // Lines arrive from AgentRunner's reader thread; both lists are
        // synchronized because the callback runs off the caller's thread.
        val events = Collections.synchronizedList(mutableListOf<BrainEvent>())
        val textParts = Collections.synchronizedList(mutableListOf<String>())
        val result = try {
            invoke(prompt, binary, effectiveTimeout) { rawLine ->
                collectLine(rawLine, events, textParts, config.parseMode)
            }
        } catch (e: Exception) {
            return CliCallResult.Failure(
                CliFailureKind.TRANSPORT,
                "CLI 调用异常：${e.message ?: "未知错误"}。",
            )
        }
        return when (result) {
            is AgentRunResult.Success -> {
                // Fall back to the raw tail when no line parsed into text
                // (e.g. the CLI only emitted framing lines): the answer
                // must not come back empty while output exists.
                val text = textParts.joinToString("").ifBlank { result.lines.joinToString("\n") }
                CliCallResult.Success(text, events.toList())
            }
            is AgentRunResult.Failed -> {
                val tail = (result.text + "\n" + result.lines.joinToString("\n")).take(2000)
                classifyFailure(result.exitCode, tail)
            }
            is AgentRunResult.Timeout -> CliCallResult.Failure(
                CliFailureKind.TIMEOUT,
                "CLI 调用超时（>${effectiveTimeout}s），已停止；本次未确认登录状态。",
            )
            is AgentRunResult.NotInstalled -> CliCallResult.Failure(
                CliFailureKind.NOT_INSTALLED,
                result.reason,
            )
        }
    }

    private fun collectLine(
        rawLine: String,
        events: MutableList<BrainEvent>,
        textParts: MutableList<String>,
        parseMode: CliParseMode?,
    ) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return
        if (parseMode == CliParseMode.PLAIN_TEXT) {
            // Plain-text CLI: every non-empty line is the answer, verbatim.
            val text = trimmed.take(500)
            events += BrainEvent.TextDelta(text)
            textParts += text
            return
        }
        // STREAM_JSON (verified rows today; null is defensive only —
        // unverified rows are refused before any line is collected).
        val event = StreamParser.parseNdjsonLine(trimmed)
        if (event != null) {
            when (event) {
                is BrainEvent.TextDelta -> {
                    events += event
                    textParts += event.text
                }
                is BrainEvent.ReasoningDelta -> events += event
                is BrainEvent.Usage -> events += event
                // Tool calls / errors from the CLI are kept as events;
                // the adapter decides (stage 1: tool calls are rejected
                // as unsupported, never executed by the engine).
                is BrainEvent.ToolCall -> events += event
                is BrainEvent.Error -> events += event
                // Per-line Done is framing (e.g. {"type":"result"});
                // the adapter emits its own Done at the end.
                is BrainEvent.Done -> Unit
                is BrainEvent.ToolResult -> Unit
            }
            return
        }
        // Not JSON, or JSON of unknown shape. JSON-shaped lines are
        // protocol framing and stay dropped; plain-text lines are the
        // CLI's own answer and are surfaced verbatim (truncated).
        if (!trimmed.startsWith("{")) {
            val text = trimmed.take(500)
            events += BrainEvent.TextDelta(text)
            textParts += text
        }
    }

    /**
     * Classify a non-zero exit by the CLI's own output text. Only
     * explicit auth / quota wording counts as such — anything else is a
     * transport-class failure (UNKNOWN login state at the adapter), never
     * a guessed login verdict.
     */
    private fun classifyFailure(exitCode: Int, tail: String): CliCallResult.Failure {
        val lower = tail.lowercase()
        return when {
            AUTH_PHRASES.any { lower.contains(it) } -> CliCallResult.Failure(
                CliFailureKind.AUTH,
                "CLI 明确报告未登录（exit=$exitCode）：请先在该 CLI 里完成登录，再点“验证一次”。",
            )
            QUOTA_PHRASES.any { lower.contains(it) } -> CliCallResult.Failure(
                CliFailureKind.QUOTA,
                "CLI 报告会员额度问题（exit=$exitCode）：登录本身已验证，但本次调用未完成（额度/限速）。",
            )
            else -> CliCallResult.Failure(
                CliFailureKind.TRANSPORT,
                "CLI 调用失败（exit=$exitCode）。最后输出：\n" + tail.take(500),
            )
        }
    }

    companion object {
        /**
         * Fixed probe prompt: one read-only word. It asks for nothing and
         * writes no file; the point is only that the CLI runs once under
         * the user's own membership login. It still consumes one
         * membership call — that is why the probe only runs on an
         * explicit user tap (see the class doc).
         */
        const val PROBE_PROMPT: String = "ok"

        /** Probe timeout: a one-word probe should answer far faster than a real turn. */
        const val PROBE_TIMEOUT_SECONDS: Long = 120

        /** Chat timeout: same as AgentRunner's historic 600s turn budget. */
        const val CHAT_TIMEOUT_SECONDS: Long = 600

        /** The only binaries with a verified invocation: the VERIFIED rows of [CliConfigs]. */
        val SUPPORTED_BINARIES: Set<String> = CliConfigs.VERIFIED.map { it.binary }.toSet()

        /**
         * Explicit not-logged-in wording in a CLI's own output (matched
         * case-insensitively against the tail). Deliberately short: only
         * phrases that name the login itself, never a bare error code
         * other than the standard HTTP 401.
         */
        val AUTH_PHRASES: List<String> = listOf(
            "not logged in",
            "not authenticated",
            "unauthenticated",
            "unauthorized",
            "authentication failed",
            "auth failed",
            "please log in",
            "please login",
            "please sign in",
            "login required",
            "sign in required",
            "401",
            "未登录",
            "请先登录",
            "需要登录",
            "登录已过期",
        )

        /** Explicit quota / rate-limit wording in a CLI's own output. */
        val QUOTA_PHRASES: List<String> = listOf(
            "quota",
            "usage limit",
            "rate limit",
            "insufficient_quota",
            "plan limit",
            "额度不足",
            "额度已",
            "用量已",
        )
    }
}
