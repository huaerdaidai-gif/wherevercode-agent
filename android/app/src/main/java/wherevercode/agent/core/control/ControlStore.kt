package wherevercode.agent.core.control

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import wherevercode.agent.core.task.DispatchFreezePort
import wherevercode.agent.core.task.TaskDatabase

/**
 * ControlStore (C8): Room tables + DAO for the control plane, sharing
 * the task database `wherevercode-tasks.db` (U5: one Room database, so
 * a control command, its counter bump and its audit row commit or roll
 * back together).
 *
 * Tables:
 * - control_counters: one row per task, the two monotonic counters
 *   (goal_version / auth_epoch), initial value 1 (U3).
 * - control_state: one row per task (status, cancel_pending,
 *   stop_requested_at, dispatch_frozen).
 * - control_audit_log: one row per control command (see
 *   [ControlAuditLog]; written in the same transaction as the command).
 * - control_operation_ids: client-generated operationId dedup (U2) —
 *   the same id retried executes once; the stored result is returned.
 *
 * Two implementations: [InMemoryControlStore] (pure JVM, used by the
 * control-plane / fence unit tests; its [runTransaction] snapshots and
 * restores state on failure, so tests can observe rollback) and
 * [RoomControlStore] (production, `db.runInTransaction`).
 */

// ---------------------------------------------------------------------------
// Room entities + DAOs
// ---------------------------------------------------------------------------

@Entity(tableName = "control_counters")
data class ControlCounterEntity(
    @PrimaryKey val taskId: String,
    val goalVersion: Long = 1,
    val authEpoch: Long = 1,
)

@Entity(tableName = "control_state")
data class ControlStateEntity(
    @PrimaryKey val taskId: String,
    val status: String = "RUNNING",
    val cancelPending: Boolean = false,
    val stopRequestedAt: Long? = null,
    val dispatchFrozen: Boolean = false,
    val updatedAt: Long = 0,
)

@Entity(tableName = "control_audit_log")
data class ControlAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val actor: String,
    val action: String,
    val goalVersion: Long,
    val epoch: Long,
    val operationId: String,
    val ok: Boolean,
    val errorCode: String? = null,
    val reason: String? = null,
    val ts: Long,
)

@Entity(tableName = "control_operation_ids")
data class ControlOperationEntity(
    @PrimaryKey val operationId: String,
    val taskId: String,
    val action: String,
    val ok: Boolean,
    val confirmed: Boolean,
    val status: String,
    val errorCode: String? = null,
    val reason: String? = null,
    val recordedAt: Long,
)

@Dao
interface ControlDao {
    @Query("SELECT * FROM control_counters WHERE taskId = :taskId")
    fun getCounter(taskId: String): ControlCounterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertCounter(entity: ControlCounterEntity)

    @Query("SELECT * FROM control_state WHERE taskId = :taskId")
    fun getState(taskId: String): ControlStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertState(entity: ControlStateEntity)

    @Query("SELECT * FROM control_operation_ids WHERE operationId = :operationId")
    fun getOperation(operationId: String): ControlOperationEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertOperation(entity: ControlOperationEntity)

    @Query("DELETE FROM control_counters WHERE taskId = :taskId")
    fun deleteCounter(taskId: String): Int

    @Query("DELETE FROM control_state WHERE taskId = :taskId")
    fun deleteState(taskId: String): Int

    @Query("DELETE FROM control_operation_ids WHERE taskId = :taskId")
    fun deleteOperationsByTask(taskId: String): Int
}

@Dao
interface ControlAuditDao {
    @Insert
    fun insert(entity: ControlAuditEntity): Long

    @Query("SELECT * FROM control_audit_log WHERE taskId = :taskId ORDER BY id ASC")
    fun listForTask(taskId: String): List<ControlAuditEntity>

    @Query("SELECT * FROM control_audit_log ORDER BY id ASC")
    fun listAll(): List<ControlAuditEntity>

    @Query("DELETE FROM control_audit_log WHERE taskId = :taskId")
    fun deleteByTask(taskId: String): Int
}

// ---------------------------------------------------------------------------
// Store interface
// ---------------------------------------------------------------------------

/** A persisted control-operation result (operationId dedup, U2). */
data class StoredOperation(
    val operationId: String,
    val taskId: String,
    val action: String,
    val result: ControlResult,
)

interface ControlStore : DispatchFreezePort {
    override fun <T> runDispatchTransaction(block: () -> T): T = runTransaction(block)
    /** Current GoalVersion for [taskId]; 1 when no row exists yet (U3). */
    fun goalVersion(taskId: String): Long

    /** Current AuthorizationEpoch for [taskId]; 1 when no row exists yet (U3). */
    fun authEpoch(taskId: String): Long

    /** Bump GoalVersion by 1 (ControlPlane only) and return the new value. */
    fun bumpGoalVersion(taskId: String): Long

    /** Bump AuthorizationEpoch by 1 (ControlPlane only) and return the new value. */
    fun bumpAuthEpoch(taskId: String): Long

    /** Stored control status, or null when the task has no control row (means RUNNING). */
    fun getControlStatus(taskId: String): ControlStatus?

    fun setControlStatus(taskId: String, status: ControlStatus)

    override fun isDispatchFrozen(taskId: String): Boolean
    fun setDispatchFrozen(taskId: String, frozen: Boolean)

    override fun controlStatus(taskId: String): String?

    fun isCancelPending(taskId: String): Boolean
    fun setCancelPending(taskId: String, pending: Boolean, requestedAt: Long?)
    fun cancelRequestedAt(taskId: String): Long?

    fun getOperation(operationId: String): StoredOperation?
    fun recordOperation(operationId: String, taskId: String, action: String, result: ControlResult)

    /**
     * Run [block] as one transaction (state change + counter bump +
     * audit + dedup row commit or roll back together). The Room
     * implementation uses `db.runInTransaction`; the in-memory one
     * snapshots and restores on failure.
     */
    fun <T> runTransaction(block: () -> T): T
}

// ---------------------------------------------------------------------------
// In-memory implementation (pure JVM; tests + non-Room hosts)
// ---------------------------------------------------------------------------

class InMemoryControlStore(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ControlStore {
    private val lock = Any()
    private val counters = mutableMapOf<String, ControlCounterEntity>()
    private val states = mutableMapOf<String, ControlStateEntity>()
    private val operations = mutableMapOf<String, StoredOperation>()

    override fun goalVersion(taskId: String): Long =
        synchronized(lock) { counters[taskId]?.goalVersion ?: 1 }

    override fun authEpoch(taskId: String): Long =
        synchronized(lock) { counters[taskId]?.authEpoch ?: 1 }

    override fun bumpGoalVersion(taskId: String): Long = synchronized(lock) {
        val current = counters[taskId] ?: ControlCounterEntity(taskId)
        val next = current.copy(goalVersion = current.goalVersion + 1)
        counters[taskId] = next
        next.goalVersion
    }

    override fun bumpAuthEpoch(taskId: String): Long = synchronized(lock) {
        val current = counters[taskId] ?: ControlCounterEntity(taskId)
        val next = current.copy(authEpoch = current.authEpoch + 1)
        counters[taskId] = next
        next.authEpoch
    }

    override fun getControlStatus(taskId: String): ControlStatus? =
        synchronized(lock) {
            states[taskId]?.status?.let { runCatching { ControlStatus.valueOf(it) }.getOrNull() }
        }

    override fun setControlStatus(taskId: String, status: ControlStatus) {
        synchronized(lock) {
            val current = states[taskId] ?: ControlStateEntity(taskId)
            states[taskId] = current.copy(status = status.name, updatedAt = nowEpochSeconds())
        }
    }

    override fun isDispatchFrozen(taskId: String): Boolean =
        synchronized(lock) { states[taskId]?.dispatchFrozen ?: false }

    override fun setDispatchFrozen(taskId: String, frozen: Boolean) {
        synchronized(lock) {
            val current = states[taskId] ?: ControlStateEntity(taskId)
            states[taskId] = current.copy(dispatchFrozen = frozen, updatedAt = nowEpochSeconds())
        }
    }

    override fun controlStatus(taskId: String): String? =
        synchronized(lock) { states[taskId]?.status }

    override fun isCancelPending(taskId: String): Boolean =
        synchronized(lock) { states[taskId]?.cancelPending ?: false }

    override fun setCancelPending(taskId: String, pending: Boolean, requestedAt: Long?) {
        synchronized(lock) {
            val current = states[taskId] ?: ControlStateEntity(taskId)
            states[taskId] = current.copy(
                cancelPending = pending,
                stopRequestedAt = if (pending) requestedAt else null,
                updatedAt = nowEpochSeconds(),
            )
        }
    }

    override fun cancelRequestedAt(taskId: String): Long? =
        synchronized(lock) { states[taskId]?.stopRequestedAt }

    override fun getOperation(operationId: String): StoredOperation? =
        synchronized(lock) { operations[operationId] }

    override fun recordOperation(operationId: String, taskId: String, action: String, result: ControlResult) {
        synchronized(lock) {
            operations[operationId] = StoredOperation(operationId, taskId, action, result)
        }
    }

    override fun <T> runTransaction(block: () -> T): T = synchronized(lock) {
        // Snapshot/restore gives the in-memory store real rollback
        // semantics, so a failing audit write rolls the command back
        // exactly like the Room transaction does in production.
        val snapshotCounters: Map<String, ControlCounterEntity>
        val snapshotStates: Map<String, ControlStateEntity>
        val snapshotOperations: Map<String, StoredOperation>
        snapshotCounters = counters.toMap()
        snapshotStates = states.toMap()
        snapshotOperations = operations.toMap()
        try {
            block()
        } catch (e: Exception) {
            counters.clear()
            counters.putAll(snapshotCounters)
            states.clear()
            states.putAll(snapshotStates)
            operations.clear()
            operations.putAll(snapshotOperations)
            throw e
        }
    }
}

// ---------------------------------------------------------------------------
// Room implementation (production; same database as TaskStore)
// ---------------------------------------------------------------------------

class RoomControlStore(
    private val db: TaskDatabase,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : ControlStore {
    private val dao: ControlDao = db.controlDao()

    override fun goalVersion(taskId: String): Long = dao.getCounter(taskId)?.goalVersion ?: 1

    override fun authEpoch(taskId: String): Long = dao.getCounter(taskId)?.authEpoch ?: 1

    override fun bumpGoalVersion(taskId: String): Long {
        val current = dao.getCounter(taskId) ?: ControlCounterEntity(taskId)
        val next = current.copy(goalVersion = current.goalVersion + 1)
        dao.upsertCounter(next)
        return next.goalVersion
    }

    override fun bumpAuthEpoch(taskId: String): Long {
        val current = dao.getCounter(taskId) ?: ControlCounterEntity(taskId)
        val next = current.copy(authEpoch = current.authEpoch + 1)
        dao.upsertCounter(next)
        return next.authEpoch
    }

    override fun getControlStatus(taskId: String): ControlStatus? =
        dao.getState(taskId)?.status?.let { runCatching { ControlStatus.valueOf(it) }.getOrNull() }

    override fun setControlStatus(taskId: String, status: ControlStatus) {
        val current = dao.getState(taskId) ?: ControlStateEntity(taskId)
        dao.upsertState(current.copy(status = status.name, updatedAt = nowEpochSeconds()))
    }

    override fun isDispatchFrozen(taskId: String): Boolean =
        dao.getState(taskId)?.dispatchFrozen ?: false

    override fun setDispatchFrozen(taskId: String, frozen: Boolean) {
        val current = dao.getState(taskId) ?: ControlStateEntity(taskId)
        dao.upsertState(current.copy(dispatchFrozen = frozen, updatedAt = nowEpochSeconds()))
    }

    override fun controlStatus(taskId: String): String? = dao.getState(taskId)?.status

    override fun isCancelPending(taskId: String): Boolean =
        dao.getState(taskId)?.cancelPending ?: false

    override fun setCancelPending(taskId: String, pending: Boolean, requestedAt: Long?) {
        val current = dao.getState(taskId) ?: ControlStateEntity(taskId)
        dao.upsertState(
            current.copy(
                cancelPending = pending,
                stopRequestedAt = if (pending) requestedAt else null,
                updatedAt = nowEpochSeconds(),
            ),
        )
    }

    override fun cancelRequestedAt(taskId: String): Long? = dao.getState(taskId)?.stopRequestedAt

    override fun getOperation(operationId: String): StoredOperation? =
        dao.getOperation(operationId)?.let {
            StoredOperation(
                operationId = it.operationId,
                taskId = it.taskId,
                action = it.action,
                result = ControlResult(
                    ok = it.ok,
                    confirmed = it.confirmed,
                    status = ControlStatus.valueOf(it.status),
                    errorCode = it.errorCode,
                    reason = it.reason,
                ),
            )
        }

    override fun recordOperation(operationId: String, taskId: String, action: String, result: ControlResult) {
        dao.insertOperation(
            ControlOperationEntity(
                operationId = operationId,
                taskId = taskId,
                action = action,
                ok = result.ok,
                confirmed = result.confirmed,
                status = result.status.name,
                errorCode = result.errorCode,
                reason = result.reason,
                recordedAt = nowEpochSeconds(),
            ),
        )
    }

    override fun <T> runTransaction(block: () -> T): T =
        db.runInTransaction(java.util.concurrent.Callable { block() })
}
