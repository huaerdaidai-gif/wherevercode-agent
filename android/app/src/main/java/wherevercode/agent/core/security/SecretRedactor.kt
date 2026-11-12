package wherevercode.agent.core.security

/**
 * SecretRedactor: erase secrets from event / chat / log text.
 *
 * Ported from the old project's `agent_runners.py`
 * `_EVENT_SECRET_PATTERNS` / `_event_text()` / `_stream_event_text()`
 * (blob 8ac0c322, main fd6b43d2).
 *
 * Old -> new name mapping:
 * - _EVENT_SECRET_NAME -> SECRET_NAME (same regex)
 * - _EVENT_SECRET_PATTERNS -> SECRET_PATTERNS (same 8 patterns, same order)
 * - _event_text(value, limit=2000) -> redact(text, limit=2000)
 * - _stream_event_text(value, limit=2000) -> redactStreamChunk(text, limit=2000)
 *
 * The patterns and their group-count replacement rules are ported
 * exactly: a pattern with 4 groups keeps groups 1+2, redacts group 3,
 * keeps group 4; 2 groups keeps group 1; 1 group keeps group 1;
 * 0 groups replaces the whole match with [REDACTED].
 *
 * Streaming rule: a quoted credential can be incomplete until a later
 * transport chunk arrives, so the open tail is masked BEFORE the
 * ordinary complete-value redactor runs.
 *
 * EVERY string that goes into chat, the event log, the audit log or a
 * task result must pass through here first.
 */
object SecretRedactor {
    const val REDACTED: String = "[REDACTED]"
    const val DEFAULT_LIMIT: Int = 2000

    private const val SECRET_NAME: String =
        "(?:[A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?KEY|SECRET(?:[_-]?ACCESS[_-]?KEY)?|SESSION[_-]?TOKEN|TOKEN|PASSWORD|PASSWD|PRIVATE[_-]?KEY))"

    private data class PatternRule(val regex: Regex, val groups: Int)

    // Same 8 patterns as _EVENT_SECRET_PATTERNS, same order.
    private val SECRET_PATTERNS: List<PatternRule> = listOf(
        PatternRule(
            Regex("(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----"),
            0,
        ),
        PatternRule(
            Regex("(?i)(\\bauthorization\\b\\s*[\"\\']?\\s*[:=]\\s*[\"\\']?)(?:bearer\\s+|basic\\s+)?[^\"\\'\\s,;}]+"),
            1,
        ),
        PatternRule(
            Regex("(?i)([\"\\']?\\b$SECRET_NAME\\b[\"\\']?\\s*[:=]\\s*)([\"\\'])(.*?)(\\2)"),
            4,
        ),
        PatternRule(
            Regex("(?i)([\"\\']?\\b$SECRET_NAME\\b[\"\\']?\\s*[:=]\\s*)(?![\"\\'])([^\\s,;}]+)"),
            2,
        ),
        PatternRule(
            Regex("(?i)(--(?:api[-_]?key|access[-_]?key|secret|token|password|passwd)(?:=|\\s+))([\"\\'])(.*?)(\\2)"),
            4,
        ),
        PatternRule(
            Regex("(?i)(--(?:api[-_]?key|access[-_]?key|secret|token|password|passwd)(?:=|\\s+))([\"\\']?)([^\\s\"\\']+)(\\2)"),
            4,
        ),
        PatternRule(
            Regex("(?i)\\bsk-[A-Za-z0-9_-]{12,}\\b"),
            0,
        ),
        PatternRule(
            Regex("(?i)\\bBearer\\s+[A-Za-z0-9._-]{8,}"),
            0,
        ),
    )

    private val OPEN_TAIL_SECRET_NAME = Regex(
        "(?i)([\"\\']?\\b$SECRET_NAME\\b[\"\\']?\\s*[:=]\\s*[\"\\'])[^\"\\']*$",
    )
    private val OPEN_TAIL_BEARER_OR_SK = Regex(
        "(?i)\\b(?:sk-[A-Za-z0-9_-]*|Bearer\\s+[A-Za-z0-9._-]*)$",
    )

    /** Redact secrets and keep the last [limit] chars (same as _event_text). */
    fun redact(text: String, limit: Int = DEFAULT_LIMIT): String {
        var value = text
        for ((regex, groups) in SECRET_PATTERNS) {
            value = when (groups) {
                4 -> regex.replace(value) { match ->
                    match.groupValues[1] + match.groupValues[2] + REDACTED + match.groupValues[4]
                }
                2 -> regex.replace(value) { match ->
                    match.groupValues[1] + REDACTED
                }
                1 -> regex.replace(value) { match ->
                    match.groupValues[1] + REDACTED
                }
                else -> regex.replace(value, REDACTED)
            }
        }
        val safeLimit = maxOf(0, limit)
        return if (safeLimit == 0) "" else value.takeLast(safeLimit)
    }

    /**
     * Streaming variant: mask an open (incomplete) credential tail first,
     * then run the ordinary redactor. Use for every incremental chunk.
     */
    fun redactStreamChunk(text: String, limit: Int = DEFAULT_LIMIT): String {
        var value = OPEN_TAIL_SECRET_NAME.replace(text) { match ->
            match.groupValues[1] + REDACTED
        }
        value = OPEN_TAIL_BEARER_OR_SK.replace(value, REDACTED)
        return redact(value, limit)
    }

    /** True when the text contains anything that looks like a secret. */
    fun containsSecret(text: String): Boolean {
        if (redact(text, Int.MAX_VALUE) != text) return true
        if (OPEN_TAIL_SECRET_NAME.containsMatchIn(text)) return true
        if (OPEN_TAIL_BEARER_OR_SK.containsMatchIn(text)) return true
        return false
    }
}
