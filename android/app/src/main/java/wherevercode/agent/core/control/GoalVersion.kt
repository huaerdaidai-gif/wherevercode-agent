package wherevercode.agent.core.control

/**
 * GoalVersion (C3): the per-task goal counter. Monotonically increasing
 * Long, initial value 1 (U3, GPT 08:10). Bumped only by ControlPlane
 * (on CHANGE_GOAL), always inside the same Room transaction as the
 * state change; old task rows (pre-M2) migrate to 1.
 *
 * The counter lives in [ControlStore] (control_counters table); this
 * class is the narrow typed view over it. Callers that need the
 * authoritative value read [current]; only ControlPlane calls [bump].
 */
class GoalVersion(private val store: ControlStore) {
    companion object {
        const val INITIAL: Long = 1
    }

    fun current(taskId: String): Long = store.goalVersion(taskId)

    /** ControlPlane only. Returns the new value. */
    fun bump(taskId: String): Long = store.bumpGoalVersion(taskId)
}
