package wherevercode.agent.ui

/** Result of isolating model protocol text from human-visible chat text. */
data class SanitizedChatText(
    val text: String,
    /** Thinking extracted from a raw `<think>…</think>` block, if any. */
    val reasoning: String = "",
    /** True when DSML / tool-protocol markup was removed. */
    val protocolRedacted: Boolean = false,
)

/**
 * DeepSeek V3.2 and deepseek-flash can emit their internal DSML tool-call
 * markup inside the normal `content` field when the tool schema is absent
 * from a request (or after a tool result). That markup must never be shown
 * as if the model had written it to the user.
 *
 * The real format (deepseek-ai V3.2 `encoding_dsv32.py`) is:
 *
 *     <｜DSML｜function_calls>
 *     <｜DSML｜invoke name="read_file">
 *     <｜DSML｜parameter name="path" string="true">README.md</｜DSML｜parameter>
 *     </｜DSML｜invoke>
 *     </｜DSML｜function_calls>
 *
 * This sanitizer removes whole blocks, stray tags and special tokens. It is
 * deliberately conservative: ordinary prose and code that merely contains
 * `<`, `>` or `|` is left alone. A block whose closing tag has not arrived
 * yet is hidden from its opening marker to the end of the current buffer, so
 * a streaming turn never flickers raw protocol into the bubble.
 *
 * Pure Kotlin (no Android imports): unit-testable on the JVM.
 */
object ChatTextSanitizer {

    private const val BAR = "[｜|]"

    private val thinkBlock = Regex("(?is)<think\\b[^>]*>(.*?)(?:</think\\s*>|$)")

    private val functionCallsBlock = Regex(
        "(?is)<\\s*$BAR\\s*DSML\\s*$BAR\\s*function_calls\\b[^>]*>.*?" +
            "(?:<\\s*/\\s*$BAR\\s*DSML\\s*$BAR\\s*function_calls\\s*>|$)",
    )

    private val invokeBlock = Regex(
        "(?is)<\\s*$BAR\\s*DSML\\s*$BAR\\s*invoke\\b[^>]*>.*?" +
            "(?:<\\s*/\\s*$BAR\\s*DSML\\s*$BAR\\s*invoke\\s*>|$)",
    )

    private val parameterBlock = Regex(
        "(?is)<\\s*$BAR\\s*DSML\\s*$BAR\\s*parameter\\b[^>]*>.*?" +
            "(?:<\\s*/\\s*$BAR\\s*DSML\\s*$BAR\\s*parameter\\s*>|$)",
    )

    private val dsmlTag = Regex("(?is)<\\s*/?\\s*$BAR\\s*DSML\\s*$BAR[^>]*>")

    private val dsmlBareToken = Regex("(?i)$BAR\\s*DSML\\s*$BAR")

    /** `<｜begin▁of▁sentence｜>`, `<｜User｜>`, `<｜Assistant｜>`, … */
    private val specialToken = Regex("<[｜|][^<>\\n]{1,48}[｜|]>")

    /** A partial opening marker at the end of the current stream buffer. */
    private val partialTag = Regex("(?is)<\\s*/?\\s*$BAR[^>]*$")
    private val partialNamedTag = Regex("(?is)<\\s*/?\\s*D(?:S(?:M(?:L)?)?)?[^>]*$")

    /** 独立一行的「记住：」/「记住:」标记（前后可带空白，行内不带别的内容）。 */
    private val memoryMarkerLine = Regex("(?m)^\\s*记住[：:]\\s*$")

    /**
     * CLI 启动杂音行（2026-11-11 回复杂音修复，App 侧纵深）。
     *
     * 根因在 Hand：Hand 曾把子进程 stderr 合进 stdout（stderr=STDOUT），
     * 各 CLI 的启动横幅/日志/对话 transcript/token 统计全进了回复正文。
     * Hand 侧已改成分开抓（回复只取 stdout，stderr 只进服务端日志），这
     * 里只滤“无歧义”的杂音行作第二道防线；codex 的配置 dump（workdir:/
     * model:/provider:/session id:/tokens used）和 transcript 行（user/
     * codex）格式太像正常正文，App 侧不碰，全靠 Hand 侧分离。
     */
    private val cliNoiseLines = listOf(
        // agy（Go glog）启动行：整行都是日志，不是回复。
        Regex("(?m)^\\s*ERROR: logging before google\\.Init:.*$"),
        // 通用 glog 行：I1111 08:47:05.558401 1 server_zerog.go:46] …
        Regex("(?m)^\\s*[EWI]\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+[\\w./-]+\\.go:\\d+\\].*$"),
        // codex 从 stdin 读提示时的提示行。
        Regex("(?m)^\\s*Reading additional input from stdin\\.\\.\\.\\s*$"),
        // codex 版本横幅：OpenAI Codex v0.166.0-alpha.5.1。
        Regex("(?m)^\\s*OpenAI Codex v[0-9][\\w.+-]*\\s*$"),
        // claude 的模型告警行：[claude-code:unrecognized_model] {…}。
        Regex("(?m)^\\s*\\[claude-code:[^\\]\\n]+\\].*$"),
    )

    /** 逐行删 CLI 启动杂音行，只删行、不动正文；不设 protocolRedacted。 */
    fun stripCliStartupNoise(raw: String): String {
        var out = raw
        for (regex in cliNoiseLines) {
            out = regex.replace(out, "")
        }
        return out
    }

    /**
     * 老项目 v6-core.js:520 stripAssistantMemoryMarker：找到首个仅为
     * 「记住：」的行，从该行起全部截掉；没有这样的行时原样返回。
     */
    fun stripMemoryMarker(raw: String): String {
        val match = memoryMarkerLine.find(raw) ?: return raw
        return raw.substring(0, match.range.first).trimEnd()
    }

    fun sanitize(raw: String): SanitizedChatText {
        if (raw.isEmpty()) return SanitizedChatText("")

        // 老项目 v6-core.js:520 stripAssistantMemoryMarker 同规则：
        // 首个仅为「记住：」/「记住:」的行，从该行起全部截掉（Agent 记忆
        // 提示不是回答正文，排版前过滤）。「请记住：…」「记住：明天开会」
        // 这种行内带内容的行不动。
        var working = stripMemoryMarker(raw)
        val reasoning = StringBuilder()
        thinkBlock.findAll(working).forEach { match ->
            val thought = match.groupValues.getOrNull(1).orEmpty()
            if (thought.isNotBlank()) {
                if (reasoning.isNotEmpty()) reasoning.append('\n')
                reasoning.append(thought.trim())
            }
        }
        working = thinkBlock.replace(working, "")

        var redacted = false

        fun remove(regex: Regex) {
            if (regex.containsMatchIn(working)) {
                working = regex.replace(working, "")
                redacted = true
            }
        }

        remove(functionCallsBlock)
        remove(invokeBlock)
        remove(parameterBlock)
        remove(dsmlTag)
        remove(dsmlBareToken)
        remove(specialToken)
        // Hide an opening marker that is still incomplete at the stream tail.
        remove(partialTag)
        remove(partialNamedTag)
        // CLI 启动杂音行（App 侧纵深；主修复在 Hand 侧分开抓 stderr）。
        working = stripCliStartupNoise(working)

        return SanitizedChatText(
            text = working.trim(),
            reasoning = reasoning.toString().trim(),
            protocolRedacted = redacted,
        )
    }
}
