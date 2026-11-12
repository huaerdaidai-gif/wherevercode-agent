package wherevercode.agent.core.control

/**
 * ControlAuditLog (C7): the control audit trail.
 *
 * One row per control command (including refused ones, with ok=false),
 * written in the same transaction as the command itself, so a failed
 * audit write rolls the command back. Rows carry metadata only — never
 * credential material and never file/message contents (M16 rule).
 */
data class ControlAuditEntry(
    val id: Long = 0,
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

interface ControlAuditLog {
    fun record(entry: ControlAuditEntry): ControlAuditEntry
    fun listForTask(taskId: String): List<ControlAuditEntry>
    fun listAll(): List<ControlAuditEntry>
}

/** Pure-JVM implementation (tests + non-Room hosts). */
class InMemoryControlAuditLog : ControlAuditLog {
    private val lock = Any()
    private val entries = mutableListOf<ControlAuditEntry>()
    private var nextId = 1L

    override fun record(entry: ControlAuditEntry): ControlAuditEntry {
        val stored = entry.copy(id = nextId++)
        synchronized(lock) { entries += stored }
        return stored
    }

    override fun listForTask(taskId: String): List<ControlAuditEntry> =
        synchronized(lock) { entries.filter { it.taskId == taskId } }

    override fun listAll(): List<ControlAuditEntry> =
        synchronized(lock) { entries.toList() }
}

/** Room implementation (same database as TaskStore / ControlStore). */
class RoomControlAuditLog(
    private val dao: ControlAuditDao,
) : ControlAuditLog {
    override fun record(entry: ControlAuditEntry): ControlAuditEntry {
        val id = dao.insert(
            ControlAuditEntity(
                taskId = entry.taskId,
                actor = entry.actor,
                action = entry.action,
                goalVersion = entry.goalVersion,
                epoch = entry.epoch,
                operationId = entry.operationId,
                ok = entry.ok,
                errorCode = entry.errorCode,
                reason = entry.reason,
                ts = entry.ts,
            ),
        )
        return entry.copy(id = id)
    }

    override fun listForTask(taskId: String): List<ControlAuditEntry> =
        dao.listForTask(taskId).map { it.toEntry() }

    override fun listAll(): List<ControlAuditEntry> =
        dao.listAll().map { it.toEntry() }

    private fun ControlAuditEntity.toEntry(): ControlAuditEntry = ControlAuditEntry(
        id = id,
        taskId = taskId,
        actor = actor,
        action = action,
        goalVersion = goalVersion,
        epoch = epoch,
        operationId = operationId,
        ok = ok,
        errorCode = errorCode,
        reason = reason,
        ts = ts,
    )
}
