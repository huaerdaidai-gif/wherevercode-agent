package wherevercode.agent.core.security

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import wherevercode.agent.core.task.TaskDatabase

/**
 * RoomRevocationStore (C10): [RevocationStore] backed by Room.
 *
 * Tables (same database as TaskStore, `wherevercode-tasks.db`):
 * - revoked_credentials: one row per revoked credential id (jti).
 * - revoked_tasks: one row per revoked task (all its credentials die).
 * - revoked_hosts: one row per revoked host, with the revocation
 *   moment — only credentials issued BEFORE that moment are killed
 *   (old `revoke_host` time semantics).
 *
 * Revocations persist across restarts: a credential revoked before a
 * restart is still refused after it.
 */

@Entity(tableName = "revoked_credentials")
data class RevokedCredentialEntity(
    @PrimaryKey val credentialId: String,
    val revokedAt: Long,
)

@Entity(tableName = "revoked_tasks")
data class RevokedTaskEntity(
    @PrimaryKey val taskId: String,
    val revokedAt: Long,
)

@Entity(tableName = "revoked_hosts")
data class RevokedHostEntity(
    @PrimaryKey val hostId: String,
    val revokedAt: Long,
)

@Dao
interface RevocationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertCredential(entity: RevokedCredentialEntity)

    @Query("SELECT * FROM revoked_credentials WHERE credentialId = :credentialId")
    fun getCredential(credentialId: String): RevokedCredentialEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTask(entity: RevokedTaskEntity)

    @Query("SELECT * FROM revoked_tasks WHERE taskId = :taskId")
    fun getTask(taskId: String): RevokedTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertHost(entity: RevokedHostEntity)

    @Query("SELECT * FROM revoked_hosts WHERE hostId = :hostId")
    fun getHost(hostId: String): RevokedHostEntity?
}

class RoomRevocationStore(
    private val db: TaskDatabase,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : RevocationStore {
    private val dao: RevocationDao = db.revocationDao()

    override fun revoke(credentialId: String) {
        dao.insertCredential(RevokedCredentialEntity(credentialId, nowEpochSeconds()))
    }

    override fun isRevoked(credentialId: String): Boolean =
        dao.getCredential(credentialId) != null

    override fun revokeTask(taskId: String) {
        dao.insertTask(RevokedTaskEntity(taskId, nowEpochSeconds()))
    }

    override fun isTaskRevoked(taskId: String): Boolean =
        dao.getTask(taskId) != null

    override fun revokeHost(hostId: String, revokedAtEpochSeconds: Long) {
        dao.upsertHost(RevokedHostEntity(hostId, revokedAtEpochSeconds))
    }

    override fun isHostRevoked(hostId: String, issuedAtEpochSeconds: Long): Boolean {
        val revokedAt = dao.getHost(hostId)?.revokedAt ?: return false
        return issuedAtEpochSeconds < revokedAt
    }
}
