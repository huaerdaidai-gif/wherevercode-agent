package wherevercode.agent.core.control

import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStore

/**
 * CancellationToken (C5): one cancel token per task.
 *
 * - [request] marks cancel_pending and records stop_requested_at.
 * - [confirm] clears the pending flag (the terminal state itself is
 *   written by ControlPlane.confirmStop, not here).
 *
 * 120s ([STOP_OBSERVE_TIMEOUT_SECONDS]) is an OBSERVE threshold, not a
 * forced transition (U4): after 120s without confirmation the task
 * stays pending and keeps blocking new dispatch — it is never forced
 * into a terminal state by the clock.
 *
 * The pending flag is persisted in control_state, so it survives a
 * restart. [request] on an already-terminal task is a no-op and
 * returns false (when a [taskStore] is wired; without one the request
 * is recorded unconditionally).
 */
class CancellationToken(
    private val store: ControlStore,
    private val taskStore: TaskStore? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    companion object {
        /** "Waiting for confirmation" observe threshold (seconds). Not a forced stop. */
        const val STOP_OBSERVE_TIMEOUT_SECONDS: Long = 120
    }

    /** Returns true when the token was set; false when the task is already terminal. */
    fun request(taskId: String): Boolean {
        val task = taskStore?.getTask(taskId)
        if (task != null && TaskStates.isTerminal(task.state)) return false
        // Idempotent: a second request keeps the first timestamp.
        if (store.isCancelPending(taskId)) return true
        store.setCancelPending(taskId, pending = true, requestedAt = nowEpochSeconds())
        return true
    }

    /** Returns true when a pending token was cleared; false when none was pending. */
    fun confirm(taskId: String): Boolean {
        if (!store.isCancelPending(taskId)) return false
        store.setCancelPending(taskId, pending = false, requestedAt = null)
        return true
    }

    fun isRequested(taskId: String): Boolean = store.isCancelPending(taskId)

    fun requestedAt(taskId: String): Long? = store.cancelRequestedAt(taskId)

    /** True when the request is older than the 120s observe threshold and still unconfirmed. */
    fun isPastObserveThreshold(taskId: String): Boolean {
        val at = store.cancelRequestedAt(taskId) ?: return false
        return store.isCancelPending(taskId) && nowEpochSeconds() - at > STOP_OBSERVE_TIMEOUT_SECONDS
    }
}
