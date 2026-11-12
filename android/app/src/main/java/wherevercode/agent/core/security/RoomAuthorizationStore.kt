package wherevercode.agent.core.security

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import wherevercode.agent.core.task.TaskDatabase

/**
 * RoomAuthorizationStore (C9): [AuthorizationStore] backed by Room.
 *
 * Tables (same database as TaskStore, `wherevercode-tasks.db`):
 * - grants: one row per (agent_id, folder), plus
 * - device_grant: a single row (id = 1) for whole-device consent.
 *
 * This is the AUTHORITATIVE control-side implementation (M2): it
 * replaces SharedPreferences/File as the source the fence and the
 * control plane consult. The File/SharedPreferences implementations
 * are legacy; they keep working for the stdio HandServer, which has
 * no Room environment, but they are no longer the control authority.
 * Records hold only agent ids and folder paths — no credentials, no
 * file contents, no task text. The iron rule is unchanged: there is
 * NO same-account default allow.
 */

@Entity(tableName = "grants", primaryKeys = ["agentId", "folder"])
data class GrantEntity(
    val agentId: String,
    val folder: String,
    val grantedAt: String,
    val grantedBy: String = "local",
)

@Entity(tableName = "device_grant")
data class DeviceGrantEntity(
    @PrimaryKey val id: Int = 1,
    val grantedAt: String,
    val grantedBy: String = "local",
)

@Dao
interface GrantDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertGrant(entity: GrantEntity)

    @Query("DELETE FROM grants WHERE agentId = :agentId AND folder = :folder")
    fun deleteGrant(agentId: String, folder: String): Int

    @Query("SELECT * FROM grants WHERE agentId = :agentId AND folder = :folder")
    fun getGrant(agentId: String, folder: String): GrantEntity?

    @Query("SELECT * FROM grants")
    fun getAllGrants(): List<GrantEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertDeviceGrant(entity: DeviceGrantEntity)

    @Query("SELECT * FROM device_grant WHERE id = 1")
    fun getDeviceGrant(): DeviceGrantEntity?

    @Query("DELETE FROM device_grant WHERE id = 1")
    fun deleteDeviceGrant(): Int
}

class RoomAuthorizationStore(private val db: TaskDatabase) : AuthorizationStore {
    private val dao: GrantDao = db.grantDao()

    override fun grant(agentId: String, folder: String, grantedBy: String): AuthGrant {
        require(agentId.isNotEmpty()) { "cross_device_auth_bad_agent" }
        val record = AuthGrant(agentId, canonicalFolder(folder), utcNowIso(), grantedBy)
        dao.upsertGrant(
            GrantEntity(
                agentId = record.agentId,
                folder = record.folder,
                grantedAt = record.grantedAt,
                grantedBy = record.grantedBy,
            ),
        )
        return record
    }

    override fun grantDevice(grantedBy: String): Boolean {
        dao.upsertDeviceGrant(DeviceGrantEntity(grantedAt = utcNowIso(), grantedBy = grantedBy))
        return true
    }

    override fun revokeDevice(): Boolean {
        val had = dao.getDeviceGrant() != null
        if (had) dao.deleteDeviceGrant()
        return had
    }

    override fun deviceAuthorized(): Boolean = dao.getDeviceGrant() != null

    override fun revoke(agentId: String, folder: String): Boolean {
        val target = canonicalFolder(folder)
        return dao.deleteGrant(agentId, target) > 0
    }

    override fun isAuthorized(agentId: String, folder: String): Boolean {
        if (deviceAuthorized()) return true
        val target = canonicalFolder(folder)
        return dao.getGrant(agentId, target) != null
    }

    override fun listGrants(): List<AuthGrant> =
        dao.getAllGrants().map {
            AuthGrant(it.agentId, it.folder, it.grantedAt, it.grantedBy)
        }
}
