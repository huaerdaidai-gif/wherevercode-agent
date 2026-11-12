package wherevercode.agent.core.security

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * AuthorizationStore: per-agent + per-folder authorization records.
 *
 * Ported from the old project's `cross_device_auth.py`
 * (CrossDeviceAuthorizationStore, blob 81f7a77d, main fd6b43d2).
 *
 * M2 (M5): [RoomAuthorizationStore] (Room grants table + device_grant
 * row, same database as TaskStore) is the AUTHORITATIVE control-side
 * implementation. [FileAuthorizationStore] and
 * [SharedPreferencesAuthorizationStore] below are LEGACY: they keep
 * working for the pure-JVM stdio HandServer (which has no Room
 * environment) but are no longer the control authority. Do not add new
 * control-side readers of the legacy stores.
 *
 * Old -> new name mapping:
 * - grant(agent_id, folder) -> grant(agentId, folder)
 * - grant_device() -> grantDevice()
 * - revoke_device() -> revokeDevice()
 * - device_authorized() -> deviceAuthorized()
 * - revoke(agent_id, folder) -> revoke(agentId, folder)
 * - is_authorized(agent_id, folder) -> isAuthorized(agentId, folder)
 * - list_grants() -> listGrants()
 *
 * Iron rule, unchanged: there is NO same-account default allow. A call
 * is authorized only with the whole-device consent (granted once, on
 * this device, by the owner) or an exact agent+folder record. The
 * server's approval of a call never substitutes for a record here.
 * Records hold only agent ids and folder paths — no credentials, no
 * file contents, no task text.
 */
data class AuthGrant(
    val agentId: String,
    val folder: String,
    val grantedAt: String,
    val grantedBy: String = "local",
)

interface AuthorizationStore {
    fun grant(agentId: String, folder: String, grantedBy: String = "local"): AuthGrant
    fun grantDevice(grantedBy: String = "local"): Boolean
    fun revokeDevice(): Boolean
    fun deviceAuthorized(): Boolean
    fun revoke(agentId: String, folder: String): Boolean
    fun isAuthorized(agentId: String, folder: String): Boolean
    fun listGrants(): List<AuthGrant>
}

/** Canonical folder identity: the real path, so alias spellings match. */
fun canonicalFolder(folder: String): String {
    val file = File(folder)
    return try {
        file.canonicalPath
    } catch (_: Exception) {
        file.absolutePath
    }
}

fun utcNowIso(): String {
    val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return format.format(java.util.Date())
}

class InMemoryAuthorizationStore : AuthorizationStore {
    private val lock = Any()
    private val grants = mutableListOf<AuthGrant>()
    private var deviceGrant: Boolean = false

    override fun grant(agentId: String, folder: String, grantedBy: String): AuthGrant {
        require(agentId.isNotEmpty()) { "cross_device_auth_bad_agent" }
        val record = AuthGrant(agentId, canonicalFolder(folder), utcNowIso(), grantedBy)
        synchronized(lock) {
            grants.removeAll { it.agentId == agentId && it.folder == record.folder }
            grants += record
        }
        return record
    }

    override fun grantDevice(grantedBy: String): Boolean {
        synchronized(lock) { deviceGrant = true }
        return true
    }

    override fun revokeDevice(): Boolean = synchronized(lock) {
        val had = deviceGrant
        deviceGrant = false
        had
    }

    override fun deviceAuthorized(): Boolean = synchronized(lock) { deviceGrant }

    override fun revoke(agentId: String, folder: String): Boolean = synchronized(lock) {
        val target = canonicalFolder(folder)
        val before = grants.size
        grants.removeAll { it.agentId == agentId && it.folder == target }
        grants.size < before
    }

    override fun isAuthorized(agentId: String, folder: String): Boolean = synchronized(lock) {
        if (deviceGrant) return true
        val target = canonicalFolder(folder)
        grants.any { it.agentId == agentId && it.folder == target }
    }

    override fun listGrants(): List<AuthGrant> = synchronized(lock) { grants.toList() }
}

/**
 * File-backed store (same JSON shape as the old `cross_device_auth.py`
 * file: {"schema_version":1,"grants":[...],"device_grant":{...}}).
 * Written atomically (temp file + replace). Used by the pure-JVM
 * HandServer; the Android app uses [SharedPreferencesAuthorizationStore].
 */
class FileAuthorizationStore(private val file: File) : AuthorizationStore {
    private val lock = Any()

    private fun load(): JSONObject {
        if (!file.exists()) return JSONObject().put("schema_version", 1).put("grants", JSONArray())
        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject().put("schema_version", 1).put("grants", JSONArray())
        }
    }

    private fun save(data: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(data.toString(), Charsets.UTF_8)
        tmp.renameTo(file)
    }

    private fun grantsOf(data: JSONObject): MutableList<AuthGrant> {
        val out = mutableListOf<AuthGrant>()
        val array = data.optJSONArray("grants") ?: return out
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            out += AuthGrant(
                agentId = obj.optString("agent_id", ""),
                folder = obj.optString("folder", ""),
                grantedAt = obj.optString("granted_at", ""),
                grantedBy = obj.optString("granted_by", "local"),
            )
        }
        return out
    }

    private fun persist(grants: List<AuthGrant>, deviceGrant: Boolean) {
        val data = JSONObject()
        data.put("schema_version", 1)
        val array = JSONArray()
        for (grant in grants) {
            val obj = JSONObject()
            obj.put("agent_id", grant.agentId)
            obj.put("folder", grant.folder)
            obj.put("granted_at", grant.grantedAt)
            obj.put("granted_by", grant.grantedBy)
            array.put(obj)
        }
        data.put("grants", array)
        if (deviceGrant) {
            val device = JSONObject()
            device.put("granted_at", utcNowIso())
            device.put("granted_by", "local")
            data.put("device_grant", device)
        }
        save(data)
    }

    override fun grant(agentId: String, folder: String, grantedBy: String): AuthGrant {
        require(agentId.isNotEmpty()) { "cross_device_auth_bad_agent" }
        val record = AuthGrant(agentId, canonicalFolder(folder), utcNowIso(), grantedBy)
        synchronized(lock) {
            val grants = grantsOf(load()).filterNot {
                it.agentId == agentId && it.folder == record.folder
            }.toMutableList()
            grants += record
            persist(grants, load().has("device_grant"))
        }
        return record
    }

    override fun grantDevice(grantedBy: String): Boolean {
        synchronized(lock) {
            persist(grantsOf(load()), true)
        }
        return true
    }

    override fun revokeDevice(): Boolean = synchronized(lock) {
        val had = load().has("device_grant")
        if (had) persist(grantsOf(load()), false)
        had
    }

    override fun deviceAuthorized(): Boolean = synchronized(lock) { load().has("device_grant") }

    override fun revoke(agentId: String, folder: String): Boolean = synchronized(lock) {
        val target = canonicalFolder(folder)
        val grants = grantsOf(load())
        val kept = grants.filterNot { it.agentId == agentId && it.folder == target }
        if (kept.size == grants.size) return false
        persist(kept, load().has("device_grant"))
        true
    }

    override fun isAuthorized(agentId: String, folder: String): Boolean = synchronized(lock) {
        val data = load()
        if (data.has("device_grant")) return true
        val target = canonicalFolder(folder)
        grantsOf(data).any { it.agentId == agentId && it.folder == target }
    }

    override fun listGrants(): List<AuthGrant> = synchronized(lock) { grantsOf(load()) }
}

/** Android implementation: SharedPreferences (private mode, this app only). */
class SharedPreferencesAuthorizationStore(context: Context) : AuthorizationStore {
    private val prefs = context.getSharedPreferences("wherevercode_authorization", Context.MODE_PRIVATE)
    private val memory = InMemoryAuthorizationStore()

    init {
        // Rehydrate the in-memory view from persisted JSON once.
        val json = prefs.getString(KEY_JSON, null)
        if (json != null) {
            try {
                val data = JSONObject(json)
                if (data.optBoolean("device_grant", false)) memory.grantDevice()
                val array = data.optJSONArray("grants")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        memory.grant(obj.optString("agent_id", ""), obj.optString("folder", ""))
                    }
                }
            } catch (_: Exception) {
                // Corrupt persisted state: start empty, never crash startup.
            }
        }
    }

    private fun persist() {
        val data = JSONObject()
        data.put("device_grant", memory.deviceAuthorized())
        val array = JSONArray()
        for (grant in memory.listGrants()) {
            val obj = JSONObject()
            obj.put("agent_id", grant.agentId)
            obj.put("folder", grant.folder)
            obj.put("granted_at", grant.grantedAt)
            obj.put("granted_by", grant.grantedBy)
            array.put(obj)
        }
        data.put("grants", array)
        prefs.edit().putString(KEY_JSON, data.toString()).apply()
    }

    override fun grant(agentId: String, folder: String, grantedBy: String): AuthGrant {
        val record = memory.grant(agentId, folder, grantedBy)
        persist()
        return record
    }

    override fun grantDevice(grantedBy: String): Boolean {
        val result = memory.grantDevice(grantedBy)
        persist()
        return result
    }

    override fun revokeDevice(): Boolean {
        val result = memory.revokeDevice()
        persist()
        return result
    }

    override fun deviceAuthorized(): Boolean = memory.deviceAuthorized()

    override fun revoke(agentId: String, folder: String): Boolean {
        val result = memory.revoke(agentId, folder)
        persist()
        return result
    }

    override fun isAuthorized(agentId: String, folder: String): Boolean =
        memory.isAuthorized(agentId, folder)

    override fun listGrants(): List<AuthGrant> = memory.listGrants()

    companion object {
        const val KEY_JSON: String = "authorization_json"
    }
}
