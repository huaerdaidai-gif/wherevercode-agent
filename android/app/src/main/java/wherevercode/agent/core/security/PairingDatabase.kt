package wherevercode.agent.core.security

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import wherevercode.agent.core.task.TaskDatabase

/**
 * Pairing persistence (V5 Phase B C1, W2): Room table for the pairing
 * trust records that [PairingTrustStore] describes.
 *
 * One table in the shared [TaskDatabase] (`wherevercode-tasks.db`):
 * `pairings`, one row per [PairingRecord], keyed by deviceId.
 *
 * Mapping notes (same conventions as SessionDatabase.kt):
 * - `state` stores the [PairingState] name (PENDING / CONFIRMED /
 *   REJECTED / REVOKED); an unknown stored name reads back as PENDING
 *   is never silently trusted — see [PairingEntity.toRecord]: an
 *   unrecognised state decodes to REJECTED, the fail-closed state.
 * - `peerPublicKey` is the raw X25519 public key bytes (BLOB, null
 *   until exchanged). Keys are public material, not secrets; they are
 *   stored exactly as exchanged on the pinned TLS channel.
 */
@Entity(tableName = "pairings")
data class PairingEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val fingerprintSha256: String,
    /** The peer's X25519 public key, null until exchanged. */
    val peerPublicKey: ByteArray? = null,
    /** [PairingState] name. */
    val state: String,
    val reason: String? = null,
    val updatedAtEpochSeconds: Long,
) {
    // ByteArray needs value equality for tests that compare rows.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingEntity) return false
        return deviceId == other.deviceId &&
            displayName == other.displayName &&
            fingerprintSha256 == other.fingerprintSha256 &&
            (peerPublicKey == null && other.peerPublicKey == null ||
                peerPublicKey != null && other.peerPublicKey != null &&
                peerPublicKey.contentEquals(other.peerPublicKey)) &&
            state == other.state &&
            reason == other.reason &&
            updatedAtEpochSeconds == other.updatedAtEpochSeconds
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + fingerprintSha256.hashCode()
        result = 31 * result + (peerPublicKey?.contentHashCode() ?: 0)
        result = 31 * result + state.hashCode()
        result = 31 * result + (reason?.hashCode() ?: 0)
        result = 31 * result + updatedAtEpochSeconds.hashCode()
        return result
    }
}

fun PairingRecord.toEntity(): PairingEntity =
    PairingEntity(
        deviceId = deviceId,
        displayName = displayName,
        fingerprintSha256 = fingerprintSha256,
        peerPublicKey = peerPublicKey,
        state = state.name,
        reason = reason,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )

fun PairingEntity.toRecord(): PairingRecord =
    PairingRecord(
        deviceId = deviceId,
        displayName = displayName,
        fingerprintSha256 = fingerprintSha256,
        peerPublicKey = peerPublicKey,
        // Fail closed: a state this build does not recognise can never
        // decode to CONFIRMED (which would grant trust); it decodes to
        // REJECTED, so PairingManager treats the device as untrusted.
        state = runCatching { PairingState.valueOf(state) }
            .getOrDefault(PairingState.REJECTED),
        reason = reason,
        updatedAtEpochSeconds = updatedAtEpochSeconds,
    )

@Dao
interface PairingDao {
    /** REPLACE: saving a record upserts it, matching the in-memory store. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(record: PairingEntity)

    @Query("SELECT * FROM pairings WHERE deviceId = :deviceId")
    fun load(deviceId: String): PairingEntity?

    @Query("DELETE FROM pairings WHERE deviceId = :deviceId")
    fun remove(deviceId: String): Int

    @Query("SELECT * FROM pairings ORDER BY updatedAtEpochSeconds ASC, rowid ASC")
    fun all(): List<PairingEntity>
}

/**
 * Room-backed [PairingTrustStore]. Behaviour matches
 * [InMemoryPairingTrustStore] exactly: [save] upserts, [load] returns
 * null for an unknown device, [remove] is a no-op for an unknown device.
 *
 * The state machine itself lives in [PairingManager] and is unchanged;
 * this class only persists the records it hands over.
 *
 * Callers must call this off the main thread: Room forbids main-thread
 * database access and these methods are synchronous (same rule as
 * [wherevercode.agent.core.task.RoomTaskStore]).
 */
class RoomPairingStore(db: TaskDatabase) : PairingTrustStore {
    private val dao: PairingDao = db.pairingDao()
    private val lock = Any()

    override fun load(deviceId: String): PairingRecord? =
        synchronized(lock) { dao.load(deviceId)?.toRecord() }

    override fun save(record: PairingRecord) {
        synchronized(lock) { dao.upsert(record.toEntity()) }
    }

    override fun remove(deviceId: String) {
        synchronized(lock) { dao.remove(deviceId) }
    }

    override fun all(): List<PairingRecord> =
        synchronized(lock) { dao.all().map { it.toRecord() } }
}
