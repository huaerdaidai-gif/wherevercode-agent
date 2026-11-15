package wherevercode.agent.brain

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Same engine memory interface, atomically persisted on device. Failed writes never report success. */
class PersistentMemoryStore(private val preferences: SharedPreferences) : MemoryStore {
    private val lock = Any()
    private var current: InMemoryMemoryStore? = null

    private fun loaded(): InMemoryMemoryStore {
        current?.let { return it }
        val store = InMemoryMemoryStore()
        preferences.getString("entries_v1", null)?.let { raw ->
            val root = JSONObject(raw)
            check(root.getInt("version") == 1) { "不支持的记忆数据版本" }
            val rows = root.getJSONArray("entries")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                store.restorePersisted(MemoryEntry(row.getString("id"), MemoryKind.valueOf(row.getString("kind")),
                    row.getString("key"), row.getString("value"), row.getString("source"),
                    row.getLong("createdAt"), row.getBoolean("confirmed")))
            }
        }
        current = store
        return store
    }

    private fun mutate(block: (InMemoryMemoryStore) -> Boolean): Boolean = synchronized(lock) {
        val candidate = InMemoryMemoryStore()
        MemoryKind.values().forEach { kind -> loaded().list(kind).forEach(candidate::restorePersisted) }
        if (!block(candidate)) return@synchronized false
        val rows = JSONArray()
        MemoryKind.values().forEach { kind -> candidate.list(kind).forEach { entry ->
            rows.put(JSONObject().put("id", entry.id).put("kind", entry.kind.name).put("key", entry.key)
                .put("value", entry.value).put("source", entry.source).put("createdAt", entry.createdAtEpochSeconds)
                .put("confirmed", entry.confirmedByUser))
        } }
        check(preferences.edit().putString("entries_v1", JSONObject().put("version", 1).put("entries", rows).toString()).commit()) {
            "记忆保存失败，请检查本地存储空间"
        }
        current = candidate
        true
    }
    override fun addFact(entry: MemoryEntry, confirmedByUser: Boolean) = mutate { it.addFact(entry, confirmedByUser) }
    override fun addTaskSummary(taskId: String, summary: String, settled: Boolean) = mutate { it.addTaskSummary(taskId, summary, settled) }
    override fun addDeviceAuthorization(deviceId: String, note: String) = mutate { it.addDeviceAuthorization(deviceId, note) }
    override fun list(kind: MemoryKind) = synchronized(lock) { loaded().list(kind) }
    override fun get(kind: MemoryKind, key: String) = synchronized(lock) { loaded().get(kind, key) }
    override fun remove(kind: MemoryKind, key: String) = mutate { it.remove(kind, key) }
    override fun summarize() = synchronized(lock) { loaded().summarize() }
}
