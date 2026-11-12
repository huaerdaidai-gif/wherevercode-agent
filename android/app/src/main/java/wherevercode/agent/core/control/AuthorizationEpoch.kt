package wherevercode.agent.core.control

/**
 * AuthorizationEpoch (C4): the per-task authorization counter.
 * Incremented on authorization changes (REVOKE / TAKEOVER); initial
 * value 1, same migration rule as GoalVersion (U3). Only ControlPlane
 * bumps it; credentials carry an epoch snapshot and the fence refuses
 * any credential whose epoch is not the current one, so a revoke
 * invalidates previously issued credentials even after a restart.
 */
class AuthorizationEpoch(private val store: ControlStore) {
    companion object {
        const val INITIAL: Long = 1
    }

    fun current(taskId: String): Long = store.authEpoch(taskId)

    /** ControlPlane only. Returns the new value. */
    fun bump(taskId: String): Long = store.bumpAuthEpoch(taskId)
}
