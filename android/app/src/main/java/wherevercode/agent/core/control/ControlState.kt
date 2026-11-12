package wherevercode.agent.core.control

/**
 * ControlState (C2): the per-task control state machine.
 *
 * status ∈ RUNNING / PAUSING / PAUSED / STOPPING (see [ControlStatus]
 * in ControlPlane.kt — the enum is declared there and NOT re-declared
 * here). CANCELLED lives in TaskStates' terminal states, not here.
 *
 * PAUSED is only reached when every controlled executor has confirmed
 * the stop, or the local controlled execution has actually terminated
 * (U6): an external execution that cannot be confirmed stays PAUSING /
 * unconfirmed — never reported as paused. A task whose executor is
 * still running is "in flight" (assigned / working); queued, blocked
 * and review tasks have no executor in flight.
 */
data class ControlStateRow(
    val taskId: String,
    val status: ControlStatus = ControlStatus.RUNNING,
    val cancelPending: Boolean = false,
    val stopRequestedAt: Long? = null,
    val dispatchFrozen: Boolean = false,
    val updatedAt: Long = 0,
)

/** True when the task has an executor currently running (assigned / working). */
fun isTaskInFlight(taskState: String): Boolean =
    taskState == "assigned" || taskState == "working"

/** True when writes may proceed under this control status (only RUNNING). */
fun controlStatusAllowsWrite(status: ControlStatus): Boolean = status == ControlStatus.RUNNING
