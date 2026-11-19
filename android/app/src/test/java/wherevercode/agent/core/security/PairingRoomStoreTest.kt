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
        deviceId: String = "testdevice",
        state: PairingState = PairingState.CONFIRMED,
        peerPublicKey: ByteArray? = byteArrayOf(1, 2, 3, 4),
        reason: String? = null,
    ) = PairingRecord(
        deviceId = deviceId,
        displayName = "测试设备",
        fingerprintSha256 = fingerprint,
        peerPublicKey = peerPublicKey,
        state = state,
        reason = reason,
        updatedAtEpochSeconds = 1_000L,
    )

    private fun peer() =
        DiscoveredPeer("testdevice", "测试设备", "192.168.1.10", 8443, fingerprint, listOf("hand"))

    @Test
    fun `save load remove and all round-trip`() {
        assertNull(store.load("testdevice"))
        assertTrue(store.all().isEmpty())
        store.save(record())
        store.save(record(deviceId = "k70", state = PairingState.PENDING, peerPublicKey = null))
        val back = store.load("testdevice")!!
        assertEquals("测试设备", back.displayName)
        assertEquals(fingerprint, back.fingerprintSha256)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), back.peerPublicKey)
        assertEquals(PairingState.CONFIRMED, back.state)
        assertEquals(1_000L, back.updatedAtEpochSeconds)
        assertNull(store.load("k70")!!.peerPublicKey)
        assertEquals(setOf("testdevice", "k70"), store.all().map { it.deviceId }.toSet())
        // Saving again overwrites (upsert), it never duplicates.
        store.save(record(state = PairingState.REVOKED, reason = "不再信任"))
        assertEquals(2, store.all().size)
        assertEquals(PairingState.REVOKED, store.load("testdevice")!!.state)
        assertEquals("不再信任", store.load("testdevice")!!.reason)
        store.remove("testdevice")
        assertNull(store.load("testdevice"))
        assertEquals(listOf("k70"), store.all().map { it.deviceId })
        // Removing an unknown device is a no-op.
        store.remove("missing")
    }

    @Test
    fun `pairing manager over the room store confirms revokes and trusts`() {
        val manager = PairingManager(store, nowEpochSeconds = { 1_000L })
        manager.requestPairing(peer())
        assertFalse(manager.isTrusted(peer()))
        manager.confirm("testdevice", byteArrayOf(9, 8, 7))
        assertTrue(manager.isTrusted(peer()))
        assertArrayEquals(byteArrayOf(9, 8, 7), manager.peerPublicKey("testdevice"))
        manager.revoke("testdevice", "测试：不再信任该设备")
        assertFalse(manager.isTrusted(peer()))
        assertTrue(manager.trustFailureReason(peer())!!.contains("已撤销"))
        // The revoked record is still in the store after the round-trip.
        assertEquals(PairingState.REVOKED, store.load("testdevice")!!.state)
    }

    @Test
    fun `an unrecognised stored state decodes fail-closed as rejected`() {
        db.pairingDao().upsert(
            PairingEntity(
                deviceId = "testdevice",
                displayName = "测试设备",
                fingerprintSha256 = fingerprint,
                peerPublicKey = null,
                state = "BOGUS",
                reason = null,
                updatedAtEpochSeconds = 1_000L,
            ),
        )
        val back = store.load("testdevice")!!
        assertEquals(PairingState.REJECTED, back.state)
        assertFalse(PairingManager(store).isTrusted(peer()))
    }
}
