package wherevercode.agent.brain

import wherevercode.agent.core.security.SecretRedactor

/**
 * MemoryStore: long-term memory in three layers (stage 2, new — the old
 * project had no cross-session memory, only in-conversation compression).
 *
 * Layers:
 * 1. FACT — user-confirmed facts (preferences, project info).
 * 2. TASK_SUMMARY — conclusions of completed tasks.
 * 3. DEVICE_AUTHORIZATION — device list and authorization state.
 *    NEVER any key, token or password.
 *
 * Write rule (hard): memory is written ONLY when the user explicitly
 * confirms, or when a task settles at completion. The brain must NEVER
 * upgrade chat content into a "fact" on its own. [addFact] refuses
 * unconfirmed writes; [addTaskSummary] requires the task-settlement flag.
 * Anything that looks like a secret is refused outright.
 */
enum class MemoryKind {
    FACT,
    TASK_SUMMARY,
    DEVICE_AUTHORIZATION,
}

data class MemoryEntry(
    val id: String,
    val kind: MemoryKind,
    val key: String,
    val value: String,
    val source: String,
    val createdAtEpochSeconds: Long,
    val confirmedByUser: Boolean,
)

interface MemoryStore {
    /** Returns false (no write) unless [confirmedByUser] is true. */
    fun addFact(entry: MemoryEntry, confirmedByUser: Boolean): Boolean

    /** Task settlement only: [settled] must be true, otherwise no write. */
    fun addTaskSummary(taskId: String, summary: String, settled: Boolean): Boolean

    fun addDeviceAuthorization(deviceId: String, note: String): Boolean
    fun list(kind: MemoryKind): List<MemoryEntry>
    fun get(kind: MemoryKind, key: String): MemoryEntry?
    fun remove(kind: MemoryKind, key: String): Boolean

    /** One text block for ContextManager, annotated with source + time. */
    fun summarize(): String
}

class InMemoryMemoryStore : MemoryStore {
    private val lock = Any()
    private val entries = LinkedHashMap<Pair<MemoryKind, String>, MemoryEntry>()

    override fun addFact(entry: MemoryEntry, confirmedByUser: Boolean): Boolean {
        if (!confirmedByUser) return false
        if (SecretRedactor.containsSecret(entry.value) || SecretRedactor.containsSecret(entry.key)) {
            return false
        }
        synchronized(lock) {
            entries[entry.kind to entry.key] = entry.copy(confirmedByUser = true)
        }
        return true
    }

    override fun addTaskSummary(taskId: String, summary: String, settled: Boolean): Boolean {
        if (!settled) return false
        if (SecretRedactor.containsSecret(summary)) return false
        val entry = MemoryEntry(
            id = java.util.UUID.randomUUID().toString(),
            kind = MemoryKind.TASK_SUMMARY,
            key = taskId,
            value = summary,
            source = "task:$taskId",
            createdAtEpochSeconds = System.currentTimeMillis() / 1000,
            confirmedByUser = false,
        )
        synchronized(lock) { entries[entry.kind to entry.key] = entry }
        return true
    }

    override fun addDeviceAuthorization(deviceId: String, note: String): Boolean {
        if (SecretRedactor.containsSecret(note)) return false
        val entry = MemoryEntry(
            id = java.util.UUID.randomUUID().toString(),
            kind = MemoryKind.DEVICE_AUTHORIZATION,
            key = deviceId,
            value = note,
            source = "device:$deviceId",
            createdAtEpochSeconds = System.currentTimeMillis() / 1000,
            confirmedByUser = true,
        )
        synchronized(lock) { entries[entry.kind to entry.key] = entry }
        return true
    }

    override fun list(kind: MemoryKind): List<MemoryEntry> =
        synchronized(lock) { entries.values.filter { it.kind == kind } }

    override fun get(kind: MemoryKind, key: String): MemoryEntry? =
        synchronized(lock) { entries[kind to key] }

    override fun remove(kind: MemoryKind, key: String): Boolean =
        synchronized(lock) { entries.remove(kind to key) != null }

    override fun summarize(): String {
        val lines = synchronized(lock) { entries.values.toList() }
        if (lines.isEmpty()) return ""
        return lines.joinToString("\n") { entry ->
            "- [${entry.kind}] ${entry.key}：${entry.value}（来源：${entry.source}）"
        }
    }
}
