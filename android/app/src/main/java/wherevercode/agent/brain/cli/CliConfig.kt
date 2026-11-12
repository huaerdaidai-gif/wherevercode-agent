package wherevercode.agent.brain.cli

/**
 * One row of the CLI configuration table (V5 Phase B C3, second stage:
 * generic CLI runner).
 *
 * A CLI is either VERIFIED or UNVERIFIED — there is no middle state:
 * - VERIFIED means its exact invocation (argv template + prompt channel
 *   + output shape) has been checked against the vendor CLI reference
 *   and the on-device call path, exactly as AgentRunner already verifies
 *   codebuddy / codex. Only verified rows may run a prompt.
 * - UNVERIFIED means the CLI is only known by name: it appears in
 *   AgentDiscovery's directory (binary name + display name), but no
 *   prompt template has been verified for it. CliRunner refuses to run
 *   it (CliFailureKind.UNSUPPORTED_BINARY) instead of inventing one —
 *   no `codex auth status` style guesses (audit section 4.2). Adding a
 *   CLI is adding a row here; turning a row verified means filling in
 *   its template from a real on-device check, never from a guess.
 *
 * Placeholders in [argsTemplate]:
 * - `{prompt}` — the prompt text (an argv element for codebuddy; unused
 *   for codex, whose prompt goes on stdin, see [promptViaStdin]).
 * - `{workspace}` — the agent workspace directory (codex `--cd`).
 * The binary path itself is argv[0] and is resolved by the caller
 * (AgentRunner finds the real file); it is never a template literal,
 * and no Termux path is ever spelled here — the app sandbox cannot see
 * Termux, and this table must not pretend otherwise.
 *
 * Timeouts are per-CLI so a slower CLI can be given a longer budget
 * later; the two verified rows keep the historic 120s probe / 600s
 * chat budgets (CliRunner.PROBE_TIMEOUT_SECONDS /
 * CliRunner.CHAT_TIMEOUT_SECONDS).
 */
enum class CliParseMode {
    /** NDJSON stream (codebuddy / codex stream-json output): parsed by StreamParser. */
    STREAM_JSON,

    /** Plain-text lines: every non-empty line is the answer, verbatim. */
    PLAIN_TEXT,
}

data class CliConfig(
    /** Binary name as found on PATH / in the sandbox bin dirs. */
    val binary: String,
    /** Stable id, mirroring AgentDiscovery's directory entries. */
    val agentId: String,
    /** Display name, mirroring AgentDiscovery's directory entries. */
    val displayName: String,
    /** True only when the invocation below was verified on-device. */
    val verified: Boolean,
    /**
     * argv after argv[0], with `{prompt}` / `{workspace}` placeholders.
     * Null for unverified rows: no template, no run.
     */
    val argsTemplate: List<String>? = null,
    /** True when the prompt is fed on stdin instead of argv (codex). */
    val promptViaStdin: Boolean = false,
    /** How to read the CLI's stdout. Null for unverified rows: unknown. */
    val parseMode: CliParseMode? = null,
    /** Probe ("验证一次") budget, seconds. */
    val probeTimeoutSeconds: Long = CliRunner.PROBE_TIMEOUT_SECONDS,
    /** Real-turn budget, seconds. */
    val chatTimeoutSeconds: Long = CliRunner.CHAT_TIMEOUT_SECONDS,
) {
    /**
     * Build the full argv for one call, or null when this row has no
     * verified template. The prompt is passed as an argv element (never
     * through a shell), so no quoting/escaping is needed.
     */
    fun buildArgv(binaryPath: String, prompt: String, workspaceDir: String): List<String>? {
        val template = argsTemplate ?: return null
        val args = template.map { arg ->
            when (arg) {
                "{prompt}" -> prompt
                "{workspace}" -> workspaceDir
                else -> arg
            }
        }
        return listOf(binaryPath) + args
    }
}

/** The CLI configuration table: every CLI AgentDiscovery knows, in its order. */
object CliConfigs {

    /** Codex CLI — verified invocation from AgentRunner (server's verified CodexRunner). */
    val CODEX: CliConfig = CliConfig(
        binary = "codex",
        agentId = "codex-cli",
        displayName = "Codex CLI",
        verified = true,
        argsTemplate = listOf(
            "exec", "--json", "--skip-git-repo-check",
            "--sandbox", "workspace-write", "--cd", "{workspace}", "-",
        ),
        promptViaStdin = true,
        parseMode = CliParseMode.STREAM_JSON,
    )

    /** Claude Code — listed by discovery, invocation not verified: no template. */
    val CLAUDE: CliConfig = CliConfig(
        binary = "claude",
        agentId = "claude-code-cli",
        displayName = "Claude Code",
        verified = false,
    )

    /** Muse Code — listed by discovery, invocation not verified: no template. */
    val MUSE: CliConfig = CliConfig(
        binary = "muse",
        agentId = "muse-code-cli",
        displayName = "Muse Code",
        verified = false,
    )

    /** Gemini CLI — listed by discovery, invocation not verified: no template. */
    val GEMINI: CliConfig = CliConfig(
        binary = "gemini",
        agentId = "gemini-cli",
        displayName = "Gemini CLI",
        verified = false,
    )

    /** CodeBuddy — verified invocation from AgentRunner (server's verified CodeBuddyRunner). */
    val CODEBUDDY: CliConfig = CliConfig(
        binary = "codebuddy",
        agentId = "codebuddy-cli",
        displayName = "CodeBuddy",
        verified = true,
        argsTemplate = listOf(
            "-p", "--output-format", "stream-json",
            "--permission-mode", "acceptEdits", "{prompt}",
        ),
        promptViaStdin = false,
        parseMode = CliParseMode.STREAM_JSON,
    )

    /** Antigravity — listed by discovery, invocation not verified: no template. */
    val AGY: CliConfig = CliConfig(
        binary = "agy",
        agentId = "agy",
        displayName = "Antigravity",
        verified = false,
    )

    /**
     * Qwen Code — listed by discovery, invocation not verified: no
     * template. Membership login is the vendor's own flow (`qwen`
     * → `/auth` → Qwen Account with Subscription, browser login;
     * Plus/Pro subscription required since the free OAuth tier ended
     * 2026-04-15). V5 never reads, stores, or relays the CLI's OAuth
     * token — the CLI runs its own login and does its own work.
     */
    val QWEN: CliConfig = CliConfig(
        binary = "qwen",
        agentId = "qwen-code-cli",
        displayName = "Qwen Code",
        verified = false,
    )

    /**
     * Kimi Code CLI — listed by discovery, invocation not verified:
     * no template. Membership login is the vendor's own flow (`kimi`
     * → `/login` → Kimi Code OAuth device-code). The subscription
     * here is the Kimi Code subscription, not kimi.com web-chat
     * membership in general. Same red line as Qwen: no CLI token is
     * ever read, stored, or relayed by V5.
     */
    val KIMI: CliConfig = CliConfig(
        binary = "kimi",
        agentId = "kimi-code-cli",
        displayName = "Kimi Code",
        verified = false,
    )

    /**
     * Ark CLI (Volcano Engine) — listed by discovery, invocation not
     * verified: no template. Membership login is the vendor's own
     * flow (`arkcli auth login`, Volcano SSO browser login). This is
     * the Ark Coding Plan subscription, not Doubao App membership.
     * Same red line: no CLI token is ever read, stored, or relayed.
     */
    val ARKCLI: CliConfig = CliConfig(
        binary = "arkcli",
        agentId = "ark-cli",
        displayName = "Ark CLI",
        verified = false,
    )

    /**
     * Example Agent — listed by discovery, invocation not verified: no
     * template. Membership login is the vendor's own flow (`grok
     * login`, browser OAuth, or `--device-auth` device-code). Same
     * red line: no CLI token is ever read, stored, or relayed.
     * (Example Agent is the CLI Agent on the US VPS; Example Agent is a
     * different entity — a phone App, not a CLI.)
     */
    val GROK: CliConfig = CliConfig(
        binary = "grok",
        agentId = "example-agent-cli",
        displayName = "Example Agent",
        verified = false,
    )

    /** All rows, in AgentDiscovery directory order. */
    val ALL: List<CliConfig> = listOf(CODEX, CLAUDE, MUSE, GEMINI, CODEBUDDY, AGY, QWEN, KIMI, ARKCLI, GROK)

    /** Verified rows only: the only binaries CliRunner will run. */
    val VERIFIED: List<CliConfig> = ALL.filter { it.verified }

    /** Row for a binary name, or null when the table does not know it at all. */
    fun forBinary(binary: String): CliConfig? = ALL.firstOrNull { it.binary == binary }
}
