package wherevercode.agent.core.security

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * Room tests (Robolectric — the DAOs need an Android Context).
 * Run in CI (`./gradlew testDebugUnitTest`); they cannot run on a plain
 * JVM without Robolectric, so locally they only need to compile.
 *
 * Covers the V5 Phase B C1 (W2) pairings table: DAO round-trip
 * (including the BLOB public key) + [RoomPairingStore] behaviour parity
 * with [InMemoryPairingTrustStore], exercised end-to-end through
 * [PairingManager] so the persisted state machine is what is tested.
 */
@RunWith(RobolectricTestRunner::class)
class PairingRoomStoreTest {
    private lateinit var db: TaskDatabase
    private lateinit var store: RoomPairingStore

    private val fingerprint = "ab".repeat(32)

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        store = RoomPairingStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun record(
        deviceId: String = "xiaomi12",
        state: PairingState = PairingState.CONFIRMED,
        peerPublicKey: ByteArray? = byteArrayOf(1, 2, 3, 4),
        reason: String? = null,
    ) = PairingRecord(
        deviceId = deviceId,
        displayName = "小米 12",
        fingerprintSha256 = fingerprint,
        peerPublicKey = peerPublicKey,
        state = state,
        reason = reason,
        updatedAtEpochSeconds = 1_000L,
    )

    private fun peer() =
        DiscoveredPeer("xiaomi12", "小米 12", "192.168.1.7", 8443, fingerprint, listOf("hand"))

    @Test
    fun `save load remove and all round-trip`() {
        assertNull(store.load("xiaomi12"))
        assertTrue(store.all().isEmpty())
        store.save(record())
        store.save(record(deviceId = "k70", state = PairingState.PENDING, peerPublicKey = null))
        val back = store.load("xiaomi12")!!
        assertEquals("小米 12", back.displayName)
        assertEquals(fingerprint, back.fingerprintSha256)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), back.peerPublicKey)
        assertEquals(PairingState.CONFIRMED, back.state)
        assertEquals(1_000L, back.updatedAtEpochSeconds)
        assertNull(store.load("k70")!!.peerPublicKey)
        assertEquals(setOf("xiaomi12", "k70"), store.all().map { it.deviceId }.toSet())
        // Saving again overwrites (upsert), it never duplicates.
        store.save(record(state = PairingState.REVOKED, reason = "不再信任"))
        assertEquals(2, store.all().size)
        assertEquals(PairingState.REVOKED, store.load("xiaomi12")!!.state)
        assertEquals("不再信任", store.load("xiaomi12")!!.reason)
        store.remove("xiaomi12")
        assertNull(store.load("xiaomi12"))
        assertEquals(listOf("k70"), store.all().map { it.deviceId })
        // Removing an unknown device is a no-op.
        store.remove("missing")
    }

    @Test
    fun `pairing manager over the room store confirms revokes and trusts`() {
        val manager = PairingManager(store, nowEpochSeconds = { 1_000L })
        manager.requestPairing(peer())
        assertFalse(manager.isTrusted(peer()))
        manager.confirm("xiaomi12", byteArrayOf(9, 8, 7))
        assertTrue(manager.isTrusted(peer()))
        assertArrayEquals(byteArrayOf(9, 8, 7), manager.peerPublicKey("xiaomi12"))
        manager.revoke("xiaomi12", "测试：不再信任该设备")
        assertFalse(manager.isTrusted(peer()))
        assertTrue(manager.trustFailureReason(peer())!!.contains("已撤销"))
        // The revoked record is still in the store after the round-trip.
        assertEquals(PairingState.REVOKED, store.load("xiaomi12")!!.state)
    }

    @Test
    fun `an unrecognised stored state decodes fail-closed as rejected`() {
        db.pairingDao().upsert(
            PairingEntity(
                deviceId = "xiaomi12",
                displayName = "小米 12",
                fingerprintSha256 = fingerprint,
                peerPublicKey = null,
                state = "BOGUS",
                reason = null,
                updatedAtEpochSeconds = 1_000L,
            ),
        )
        val back = store.load("xiaomi12")!!
        assertEquals(PairingState.REJECTED, back.state)
        assertFalse(PairingManager(store).isTrusted(peer()))
    }
}
