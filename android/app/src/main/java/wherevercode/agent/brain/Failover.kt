package wherevercode.agent.brain

import org.json.JSONObject

/**
 * Failover: same-brain retry + user-configured brain switching (stage 2,
 * audit section 4.3).
 *
 * Rules:
 * - A retryable error retries ONCE on the same brain.
 * - After that, switch along the USER's explicit fallback order only.
 *   Default: NO automatic switching (an empty fallback list means stay).
 * - retryable=false (e.g. 401, model not found) reports the actionable
 *   reason immediately, never retries.
 * - Capability degradation: toolCalling=false -> text parsing + strict
 *   JSON extraction, labelled as such; streaming=false -> whole text
 *   returned at once, NEVER a fake typewriter animation.
 */
data class FailoverPolicy(
    val maxSameBrainRetries: Int = 1,
    val fallbackBrainIds: List<String> = emptyList(),
)

class FailoverException(
    val code: String,
    override val message: String,
    val retryable: Boolean,
) : Exception(message)

sealed class FailoverOutcome<out T> {
    data class Success<T>(val value: T, val brainId: String, val attempts: Int) : FailoverOutcome<T>()
    data class Failure(val error: BrainEvent.Error, val triedBrains: List<String>) : FailoverOutcome<Nothing>()
}

object Failover {
    fun shouldRetry(error: BrainEvent.Error, attemptsSoFar: Int, policy: FailoverPolicy): Boolean =
        error.retryable && attemptsSoFar < policy.maxSameBrainRetries

    /** First fallback brain not tried yet, or null (empty list = never auto-switch). */
    fun nextFallback(
        currentBrainId: String,
        policy: FailoverPolicy,
        tried: Set<String>,
    ): String? {
        if (policy.fallbackBrainIds.isEmpty()) return null
        return policy.fallbackBrainIds.firstOrNull { it != currentBrainId && it !in tried }
    }

    /** Human-readable degradation notes for the UI when a brain lacks a capability. */
    fun describeDegradation(capabilities: BrainCapabilities): List<String> {
        val notes = mutableListOf<String>()
        if (!capabilities.toolCalling) {
            notes += "该大脑不支持原生工具调用，改用文本解析 + 严格 JSON 提取，解析可能失败。"
        }
        if (!capabilities.streaming) {
            notes += "该大脑不支持流式输出，将整段返回，不使用打字机动画冒充流式。"
        }
        return notes
    }

    /**
     * Strict JSON extraction for toolCalling=false brains: find the FIRST
     * JSON object in the text that names a registered tool, parse it
     * strictly, and return the actions. Anything ambiguous or malformed
     * returns null — never guess a tool call.
     * Expected shape: {"tool":"read_file","args":{"path":"..."},"requires_approval":false}
     * or {"actions":[{...}, ...]}.
     */
    fun extractToolCallsFromText(
        text: String,
        registeredTools: Set<String>,
        taskId: String,
    ): List<Action>? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var end = -1
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return null
        val obj = try {
            JSONObject(text.substring(start, end + 1))
        } catch (_: Exception) {
            return null
        }
        val steps = mutableListOf<JSONObject>()
        if (obj.has("actions")) {
            val array = obj.optJSONArray("actions") ?: return null
            for (i in 0 until array.length()) steps += array.optJSONObject(i) ?: return null
        } else {
            steps += obj
        }
        val actions = mutableListOf<Action>()
        steps.forEachIndexed { index, step ->
            val tool = step.optString("tool", "")
            if (tool !in registeredTools) return null
            val argsObj = step.optJSONObject("args")
            val args: Map<String, Any?> = if (argsObj == null) {
                emptyMap()
            } else {
                StreamParser.jsonObjectToMap(argsObj)
            }
            actions += Action(
                actionId = step.optString("action_id", "text-$index"),
                tool = tool,
                args = args,
                requiresApproval = step.optBoolean("requires_approval", false),
                idempotencyKey = step.optString("idempotency_key", "")
                    .ifEmpty { PlanValidator.idempotencyKey(taskId, index) },
            )
        }
        return actions.ifEmpty { null }
    }
}

/**
 * Generic retry-then-fallback runner. [attempt] throws FailoverException
 * on failure. Each fallback brain gets one attempt (the same-brain retry
 * applies only to the first brain, per the stage-2 rule).
 */
class FailoverRunner(private val policy: FailoverPolicy) {
    suspend fun <T> run(
        firstBrainId: String,
        attempt: suspend (brainId: String) -> T,
    ): FailoverOutcome<T> {
        val tried = mutableListOf<String>()
        var brainId: String? = firstBrainId
        var sameBrainAttempts = 0
        var lastError: BrainEvent.Error? = null
        while (brainId != null) {
            // A retried brain is one attempt, not two entries: the same
            // brain must appear once in triedBrains.
            if (brainId !in tried) tried += brainId
            try {
                val value = attempt(brainId)
                return FailoverOutcome.Success(value, brainId, tried.size + sameBrainAttempts)
            } catch (e: FailoverException) {
                lastError = BrainEvent.Error(e.code, e.message, e.retryable)
                // Non-retryable (e.g. 401, model not found): report the
                // actionable reason immediately — no same-brain retry and
                // no fallback switch.
                if (!lastError.retryable) {
                    return FailoverOutcome.Failure(lastError, tried)
                }
                if (Failover.shouldRetry(lastError, sameBrainAttempts, policy)) {
                    sameBrainAttempts += 1
                    continue
                }
                sameBrainAttempts = 0
                brainId = Failover.nextFallback(brainId, policy, tried.toSet())
            }
        }
        return FailoverOutcome.Failure(
            lastError ?: BrainEvent.Error("failover_failed", "所有大脑均失败", false),
            tried,
        )
    }
}
