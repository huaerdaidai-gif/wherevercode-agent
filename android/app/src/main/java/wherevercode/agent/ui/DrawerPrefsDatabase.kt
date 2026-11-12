package wherevercode.agent.ui

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.ui.DrawerController.DrawerPrefs

/**
 * Drawer customisation persistence (V5 Phase B C2, batch 2).
 *
 * One single-row table in the shared [TaskDatabase]
 * (`wherevercode-tasks.db`), following the C1 conventions
 * (see [wherevercode.agent.brain.ConversationDatabase]):
 * - the row id is fixed to `"default"`; there is exactly one row;
 * - order / hidden / favourites / nesting are stored as JSON strings
 *   (org.json, like `toolCallsJson`); a corrupt cell decodes to an
 *   empty collection and never breaks reads;
 * - the migration is CREATE-only, no SQL DEFAULT (see
 *   [TaskDatabase.MIGRATION_3_4]); the entity declares no
 *   `@ColumnInfo(defaultValue)`.
 *
 * Callers must call [RoomDrawerPrefsStore] off the main thread: Room
 * forbids main-thread database access and these methods are
 * synchronous (same rule as
 * [wherevercode.agent.core.task.RoomTaskStore]).
 */
@Entity(tableName = "drawer_prefs")
data class DrawerPrefsEntity(
    @PrimaryKey val id: String = DEFAULT_ID,
    val orderJson: String = "[]",
    val hiddenJson: String = "[]",
    val favoritesJson: String = "[]",
    val nestedJson: String = "{}",
    val moreExpanded: Boolean = false,
) {
    companion object {
        const val DEFAULT_ID = "default"
    }
}

@Dao
interface DrawerPrefsDao {
    @Query("SELECT * FROM drawer_prefs WHERE id = :id")
    fun get(id: String): DrawerPrefsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: DrawerPrefsEntity)

    @Query("DELETE FROM drawer_prefs WHERE id = :id")
    fun deleteById(id: String): Int

    /** Probe query: forces Room to open the file (and run migrations). */
    @Query("SELECT COUNT(*) FROM drawer_prefs")
    fun countAll(): Int
}

/**
 * Pure [DrawerPrefs] <-> [DrawerPrefsEntity] codec (no Android, no
 * Room — unit-testable on a plain JVM).
 *
 * Decoding discards unknown ids: only ids in [knownIds] survive
 * (default: the ten built-in entries). [DrawerController.applyPrefs]
 * discards them again as defence in depth, so a stale row can never
 * resurrect an entry that no longer exists.
 */
object DrawerPrefsCodec {

    fun encode(prefs: DrawerPrefs): DrawerPrefsEntity {
        val order = JSONArray()
        prefs.order.forEach { order.put(it) }
        val hidden = JSONArray()
        prefs.hidden.forEach { hidden.put(it) }
        val favorites = JSONArray()
        prefs.favorites.forEach { favorites.put(it) }
        val nested = JSONObject()
        prefs.nestedUnder.forEach { (child, parent) -> nested.put(child, parent) }
        return DrawerPrefsEntity(
            id = DrawerPrefsEntity.DEFAULT_ID,
            orderJson = order.toString(),
            hiddenJson = hidden.toString(),
            favoritesJson = favorites.toString(),
            nestedJson = nested.toString(),
            moreExpanded = prefs.moreExpanded,
        )
    }

    fun decode(
        entity: DrawerPrefsEntity?,
        knownIds: Set<String> = DrawerController.DEFAULT_ORDER.toSet(),
    ): DrawerPrefs {
        if (entity == null) return DrawerPrefs()
        return DrawerPrefs(
            order = decodeStringList(entity.orderJson).filter { it in knownIds },
            hidden = decodeStringList(entity.hiddenJson).filter { it in knownIds }.toSet(),
            favorites = decodeStringList(entity.favoritesJson).filter { it in knownIds }.toSet(),
            nestedUnder = decodeStringMap(entity.nestedJson)
                .filterKeys { it in knownIds }
                .filterValues { it in knownIds }
                .filter { it.key != it.value },
            moreExpanded = entity.moreExpanded,
        )
    }

    private fun decodeStringList(json: String): List<String> {
        val out = mutableListOf<String>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val value = array.optString(i, "")
                if (value.isNotEmpty()) out += value
            }
        } catch (_: Exception) {
            // A corrupt cell never breaks reads; treat as empty.
            out.clear()
        }
        return out
    }

    private fun decodeStringMap(json: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key, "")
                if (key.isNotEmpty() && value.isNotEmpty()) out[key] = value
            }
        } catch (_: Exception) {
            // A corrupt cell never breaks reads; treat as empty.
            out.clear()
        }
        return out
    }
}

/** Room-backed drawer-prefs store. Behaviour mirrors the codec: a missing row loads as defaults, a corrupt row loads as empty collections (never throws for bad JSON). */
class RoomDrawerPrefsStore(private val db: TaskDatabase) {
    private val dao: DrawerPrefsDao = db.drawerPrefsDao()
    private val lock = Any()

    fun load(): DrawerPrefs =
        synchronized(lock) { DrawerPrefsCodec.decode(dao.get(DrawerPrefsEntity.DEFAULT_ID)) }

    fun save(prefs: DrawerPrefs) {
        synchronized(lock) { dao.upsert(DrawerPrefsCodec.encode(prefs)) }
    }

    fun clear() {
        synchronized(lock) { dao.deleteById(DrawerPrefsEntity.DEFAULT_ID) }
    }
}
