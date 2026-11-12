package wherevercode.agent.core.security

import java.io.File
import org.json.JSONObject

/**
 * AuditLog: append-only JSONL audit log, ported from the old project's
 * `cross_device_audit.py` (blob d4030b10, main fd6b43d2).
 *
 * Old -> new name mapping:
 * - CrossDeviceAuditLog -> AuditLog (interface) + FileAuditLog / InMemoryAuditLog
 * - record(task_id, agent_id, action, path, argv, ok, error_code, result) -> record(AuditEntry)
 * - read_entries() -> readEntries()
 *
 * One JSON line per call: time, Agent, task, action, path or command,
 * and the outcome. FILE CONTENTS NEVER ENTER THE LOG — only metadata.
 * Extra result keys outside the allowlist are dropped, not stored.
 * Allowed result keys (ported verbatim): bytes, exit_code, entries,
 * changed, created, deleted.
 */
data class AuditEntry(
    val ts: String,
    val taskId: String,
    val agentId: String,
    val action: String,
    val path: String? = null,
    val command: List<String>? = null,
    val ok: Boolean,
    val errorCode: String? = null,
    /** Metadata only; filtered to ALLOWED_RESULT_KEYS on write. */
    val result: Map<String, Any?>? = null,
    /**
     * M2 M10: session this audit line belongs to, when it comes from
     * the session centre. Null by default — existing entries are
     * untouched. File contents / message bodies never enter the log.
     */
    val sessionId: String? = null,
)

interface AuditLog {
    fun record(entry: AuditEntry): AuditEntry
    fun readEntries(): List<AuditEntry>
}

val ALLOWED_RESULT_KEYS: Set<String> = setOf(
    "bytes", "exit_code", "entries", "changed", "created", "deleted",
    // M2 M10: session-centre counts / ids (metadata only, never a body).
    "message_id", "delivered_count", "pending_count",
)

fun filterAuditResult(result: Map<String, Any?>?): Map<String, Any?>? {
    if (result == null) return null
    return result.filterKeys { it in ALLOWED_RESULT_KEYS }
}

fun auditEntryToJson(entry: AuditEntry): JSONObject {
    val obj = JSONObject()
    obj.put("ts", entry.ts)
    obj.put("task_id", entry.taskId)
    obj.put("agent_id", entry.agentId)
    obj.put("action", entry.action)
    if (entry.path != null) obj.put("path", entry.path) else obj.put("path", JSONObject.NULL)
    if (entry.command != null) {
        val array = org.json.JSONArray()
        entry.command.forEach { array.put(it) }
        obj.put("command", array)
    } else {
        obj.put("command", JSONObject.NULL)
    }
    obj.put("ok", entry.ok)
    if (entry.errorCode != null) obj.put("error_code", entry.errorCode) else obj.put("error_code", JSONObject.NULL)
    if (entry.sessionId != null) obj.put("session_id", entry.sessionId) else obj.put("session_id", JSONObject.NULL)
    val filtered = filterAuditResult(entry.result)
    if (filtered != null) {
        val resultObj = JSONObject()
        for ((key, value) in filtered) {
            when (value) {
                null -> resultObj.put(key, JSONObject.NULL)
                is Number, is Boolean, is String -> resultObj.put(key, value)
                is List<*> -> {
                    val array = org.json.JSONArray()
                    value.forEach { array.put(it?.toString() ?: "") }
                    resultObj.put(key, array)
                }
                else -> resultObj.put(key, value.toString())
            }
        }
        obj.put("result", resultObj)
    } else {
        obj.put("result", JSONObject.NULL)
    }
    return obj
}

fun auditEntryFromJson(obj: JSONObject): AuditEntry? {
    return try {
        val command = if (obj.isNull("command")) {
            null
        } else {
            val array = obj.optJSONArray("command") ?: return null
            (0 until array.length()).map { array.optString(it, "") }
        }
        val result = if (obj.isNull("result")) {
            null
        } else {
            val resultObj = obj.optJSONObject("result") ?: return null
            val map = mutableMapOf<String, Any?>()
            val keys = resultObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = if (resultObj.isNull(key)) null else resultObj.get(key)
            }
            map
        }
        AuditEntry(
            ts = obj.optString("ts", ""),
            taskId = obj.optString("task_id", ""),
            agentId = obj.optString("agent_id", ""),
            action = obj.optString("action", ""),
            path = if (obj.isNull("path")) null else obj.optString("path", ""),
            command = command,
            ok = obj.optBoolean("ok", false),
            errorCode = if (obj.isNull("error_code")) null else obj.optString("error_code", ""),
            result = result,
            sessionId = if (obj.isNull("session_id")) null
            else obj.optString("session_id", "").ifEmpty { null },
        )
    } catch (_: Exception) {
        null
    }
}

class InMemoryAuditLog : AuditLog {
    private val lock = Any()
    private val entries = mutableListOf<AuditEntry>()

    override fun record(entry: AuditEntry): AuditEntry {
        val stored = entry.copy(result = filterAuditResult(entry.result))
        synchronized(lock) { entries += stored }
        return stored
    }

    override fun readEntries(): List<AuditEntry> = synchronized(lock) { entries.toList() }
}

class FileAuditLog(private val file: File) : AuditLog {
    private val lock = Any()

    override fun record(entry: AuditEntry): AuditEntry {
        val stored = entry.copy(result = filterAuditResult(entry.result))
        val line = auditEntryToJson(stored).toString() + "\n"
        synchronized(lock) {
            file.parentFile?.mkdirs()
            file.appendText(line, Charsets.UTF_8)
        }
        return stored
    }

    override fun readEntries(): List<AuditEntry> {
        if (!file.exists()) return emptyList()
        val out = mutableListOf<AuditEntry>()
        for (line in file.readLines(Charsets.UTF_8)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                auditEntryFromJson(JSONObject(trimmed))?.let { out += it }
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }
}
