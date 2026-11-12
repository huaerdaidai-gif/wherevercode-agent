package wherevercode.agent.core.mcp

/**
 * McpGuard: MCP-side guards, ported from the old project's
 * `mcp_connector.py` (blob 7117f57a, main fd6b43d2).
 *
 * Old -> new name mapping:
 * - token_shape(token) -> tokenShape(token)
 * - redact_mcp_path(path) -> redactMcpPath(path)
 * - RATE_PER_MINUTE = 60 -> RATE_PER_MINUTE
 * - _rate_limit_message(seconds) -> rateLimitMessage(seconds)
 * - MAX_BODY_BYTES / MAX_RESULT_CHARS -> same names
 * - tools/list filtering (decide_task only for the supervisor) ->
 *   filterToolsForRole(tools, roles, supervisorOn)
 *
 * Direction note (audit section 2, #14): the old module is the
 * PLATFORM-side MCP (cloud agents calling the platform). This file only
 * borrows its guard ideas — token shape, path redaction, rate limit,
 * tools/list filtering — for the DEVICE-side hand. The platform base_url
 * is NOT carried over, and no platform route is copied.
 */
object McpGuard {
    const val RATE_PER_MINUTE: Int = 60
    const val MAX_BODY_BYTES: Int = 65_536
    const val MAX_RESULT_CHARS: Int = 6_000
    const val MCP_PATH_PREFIX: String = "/api/open/v1/mcp/"

    /**
     * What a rejected token looked like, without any of its secret part.
     * A real token starts with `wco_`; a credential-vault placeholder
     * that was never substituted keeps its own prefix (`hsurr:` ...).
     * Only the prefix class, the public 8-character hint and the length
     * are reported.
     */
    fun tokenShape(token: String?): String {
        if (token.isNullOrEmpty()) return "empty"
        if (token.startsWith("wco_")) {
            return "wco hint=${token.take(8)}…${token.takeLast(4)} len=${token.length}"
        }
        val head = if (":" in token) token.substringBefore(":").take(8) else ""
        return "not-a-token prefix=${if (head.isNotEmpty()) "$head:" else "?"} len=${token.length}"
    }

    /** Hide a URL bearer token from access logs for every MCP subroute. */
    fun redactMcpPath(path: String): String {
        if (!path.startsWith(MCP_PATH_PREFIX)) return path
        val tail = path.removePrefix(MCP_PATH_PREFIX).split("/", limit = 2)
        if (tail.isEmpty() || tail[0].isEmpty()) return path
        return MCP_PATH_PREFIX + "[redacted]" + if (tail.size > 1) "/${tail[1]}" else ""
    }

    /**
     * What a rate-limited Agent actually reads: the cause, the wait in
     * seconds, and the one correct action — retry the same call after
     * waiting.
     */
    fun rateLimitMessage(seconds: Long): String =
        "已触发限流：每分钟工具调用次数已达上限。请等待 $seconds 秒后重试同一调用，不要加入通道，也不要更换工具。"

    sealed class RateLimitResult {
        object Allowed : RateLimitResult()
        data class Limited(val retryAfterSeconds: Long) : RateLimitResult()
    }

    /**
     * Per-agent rate limiter: [RATE_PER_MINUTE] calls per wall-clock
     * minute. Handshake/event overhead does not go through here; every
     * counted call is a tool call. Thread-safe.
     */
    class RateLimiter(private val ratePerMinute: Int = RATE_PER_MINUTE) {
        private val lock = Any()
        private val buckets = mutableMapOf<String, Pair<Long, Int>>() // agentId -> (minute, count)

        fun check(agentId: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000): RateLimitResult {
            val minute = nowEpochSeconds / 60
            synchronized(lock) {
                val (bucketMinute, count) = buckets[agentId] ?: (minute to 0)
                if (bucketMinute != minute) {
                    buckets[agentId] = minute to 1
                    return RateLimitResult.Allowed
                }
                if (count >= ratePerMinute) {
                    val retryAfter = 60 - (nowEpochSeconds % 60)
                    return RateLimitResult.Limited(retryAfter)
                }
                buckets[agentId] = minute to (count + 1)
                return RateLimitResult.Allowed
            }
        }
    }

    /**
     * Filter tools/list by role. `decide_task` is only listed when the
     * supervisor switch is on (old rule: decide_task only for the owner /
     * supervisor). The four hand tools are listed for every caller; a
     * review-only agent must not be advertised write tools it cannot
     * take — pass its roles in and the hand tools are filtered the same
     * way (dev -> all four, review -> none of the write tools).
     */
    fun filterToolsForRole(
        tools: List<McpToolDef>,
        roles: Set<String>,
        supervisorOn: Boolean,
    ): List<McpToolDef> {
        val isReviewOnly = "review" in roles && "dev" !in roles
        return tools.filter { tool ->
            when (tool.name) {
                "decide_task" -> supervisorOn
                "write", "run" -> !isReviewOnly
                else -> true
            }
        }
    }

    /** Short role note for a caller, ported from _agent_role_note(). */
    fun agentRoleNote(roles: Set<String>): String {
        val parts = mutableListOf<String>()
        if ("dev" in roles) parts += "能领开发任务"
        if ("review" in roles) parts += if (roles - "review" == emptySet<String>()) "只做审查" else "能审查"
        if ("pack" in roles) parts += "能递代码"
        if (parts.isEmpty()) return "仅在线"
        return parts.joinToString("；")
    }
}
