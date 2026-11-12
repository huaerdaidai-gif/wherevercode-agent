package wherevercode.agent.core.task

/**
 * Canonical task-state contract, ported verbatim from the old project's
 * `task_states.py` (blob 4aed35a8, main fd6b43d2). Pure functions only:
 * no I/O, no storage.
 *
 * Old -> new name mapping:
 * - CANONICAL_STATES -> CANONICAL_STATES
 * - TERMINAL_STATES -> TERMINAL_STATES
 * - TRANSITIONS -> TRANSITIONS
 * - InvalidStateTransition -> InvalidStateTransition
 * - check_transition(from_state, to_state) -> checkTransition(fromState, toState)
 * - canonical(system, raw_state) -> canonical(system, rawState)
 *
 * The old `canonical()` covered 11 server sub-systems. This port keeps
 * only the three rows the new project starts from
 * (task_assignment / conversation_task / execution_host), per the stage-1
 * audit: the other rows (cloud_agent / collab_run / phase_f / team_workflow
 * / ...) belong to the old centre-server world and are added back only
 * when the autonomous-team mode (stage 6) needs them. Unknown systems
 * and unknown states still raise, never guessed.
 */
/**
 * P0 耐久任务引擎（2026-11-11）：加 `rework` 与 `requeued` 两个规范态。
 * - `rework`：审查打回后的待返工态。返工必须经过它：review → rework →
 *   assigned → working，不再 review → working 直跳（旧表那条边已删）。
 * - `requeued`：租约过期回队后的态。过期回收不再悄悄回 queued，而是落到
 *   `requeued`（可再认领，与 queued 同口径）；attempt 耗尽则进 failed 死信。
 * - 扫的回队边（assigned/working → requeued）与死信边（assigned → failed）
 *   是合法边：回收不再绕过转移表。
 */
object TaskStates {
    val CANONICAL_STATES: List<String> = listOf(
        "queued", "assigned", "working", "blocked",
        "review", "rework", "requeued", "completed", "failed", "cancelled",
    )

    val TERMINAL_STATES: Set<String> = setOf("completed", "failed", "cancelled")

    /**
     * Legal transitions. null as a source means creation, which may only
     * go to "queued". Terminal states have no outgoing edges.
     * Ported verbatim from task_states.TRANSITIONS, plus the P0
     * additions (rework / requeued — see the file doc comment).
     */
    val TRANSITIONS: Map<String?, Set<String>> = mapOf(
        null to setOf("queued"),
        "queued" to setOf("assigned", "cancelled"),
        "requeued" to setOf("assigned", "cancelled"),
        "rework" to setOf("assigned", "cancelled"),
        "assigned" to setOf("working", "queued", "requeued", "failed", "cancelled"),
        "working" to setOf("blocked", "review", "failed", "cancelled", "requeued"),
        "blocked" to setOf("working", "cancelled"),
        "review" to setOf("rework", "completed", "failed", "cancelled"),
        "completed" to emptySet(),
        "failed" to emptySet(),
        "cancelled" to emptySet(),
    )

    data class CanonicalMapping(
        val state: String,
        val needsAttention: Boolean,
        val rawState: String,
        val system: String,
    )

    private val MAPPING: Map<String, Map<String, Pair<String, Boolean>>> = mapOf(
        "task_assignment" to mapOf(
            "waiting" to ("queued" to false),
            "ready" to ("queued" to false),
            "assigned" to ("assigned" to false),
            "working" to ("working" to false),
            "blocked" to ("blocked" to false),
            "review" to ("review" to false),
            "completed" to ("completed" to false),
            "failed" to ("failed" to false),
            "stopped" to ("cancelled" to false),
        ),
        "conversation_task" to mapOf(
            "queued" to ("queued" to false),
            "running" to ("working" to false),
            "completed" to ("completed" to false),
            "failed" to ("failed" to false),
            "stopped" to ("cancelled" to false),
        ),
        "execution_host" to mapOf(
            "queued" to ("queued" to false),
            "running" to ("working" to false),
            "completed" to ("completed" to false),
            "failed" to ("failed" to false),
            "cancelled" to ("cancelled" to false),
        ),
    )

    class InvalidStateTransition(val fromState: String?, val toState: String?) :
        IllegalArgumentException("invalid state transition: $fromState -> $toState")

    /**
     * Returns true if the transition is legal, else throws.
     * null fromState means creation, which may only go to "queued".
     * An unknown fromState raises IllegalArgumentException; a known
     * source with a forbidden (or unknown) target raises
     * InvalidStateTransition.
     */
    fun checkTransition(fromState: String?, toState: String): Boolean {
        if (fromState == null) {
            if (toState != "queued") throw InvalidStateTransition(fromState, toState)
            return true
        }
        if (fromState !in CANONICAL_STATES) {
            throw IllegalArgumentException("unknown canonical state: $fromState")
        }
        if (toState !in (TRANSITIONS[fromState] ?: emptySet())) {
            throw InvalidStateTransition(fromState, toState)
        }
        return true
    }

    /**
     * Map one system's raw state to the canonical state.
     * Unknown systems and unknown raw states raise; nothing is guessed.
     */
    fun canonical(system: String, rawState: String): CanonicalMapping {
        val mapping = MAPPING[system]
            ?: throw IllegalArgumentException("unknown system: $system")
        val (state, needsAttention) = mapping[rawState]
            ?: throw IllegalArgumentException("unknown state $rawState for system $system")
        return CanonicalMapping(state, needsAttention, rawState, system)
    }

    fun isTerminal(state: String): Boolean = state in TERMINAL_STATES

    /**
     * 可认领态：queued（新任务）/ requeued（租约过期回队）/ rework
     * （审查打回待返工）。三者都是“等认领”的入口态；认领过滤
     * （调度器、两台泵、成员分派）一律用它，不再各写 `state == "queued"`。
     */
    fun isClaimable(state: String): Boolean =
        state == "queued" || state == "requeued" || state == "rework"
}
