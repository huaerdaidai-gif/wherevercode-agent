package wherevercode.agent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 配对快照单测（V5 修复，2026-11-11）：UI 线程的路由判定只读快照，
 * 不碰 Room——快照本身必须如实反映信任库的当前状态。
 */
class PairingSnapshotTest {
    private val fingerprint = "bb".repeat(32)

    private fun record(deviceId: String, state: PairingState) = PairingRecord(
        deviceId = deviceId,
        displayName = deviceId,
        fingerprintSha256 = fingerprint,
        peerPublicKey = ByteArray(32) { 7 },
        state = state,
        updatedAtEpochSeconds = 1_000L,
    )

    @Test
    fun `refresh loads confirmed records and load returns them`() {
        val store = InMemoryPairingTrustStore()
        store.save(record("remote-device", PairingState.CONFIRMED))
        store.save(record("mac", PairingState.CONFIRMED))
        val snapshot = PairingSnapshotCache()
        assertNull(snapshot.load("remote-device"))
        snapshot.refresh(store)
        assertEquals(PairingState.CONFIRMED, snapshot.load("remote-device")!!.state)
        assertEquals(PairingState.CONFIRMED, snapshot.load("mac")!!.state)
        assertNull(snapshot.load("unknown-device"))
    }

    @Test
    fun `refresh reflects revocation`() {
        val store = InMemoryPairingTrustStore()
        store.save(record("remote-device", PairingState.CONFIRMED))
        val snapshot = PairingSnapshotCache()
        snapshot.refresh(store)
        assertEquals(PairingState.CONFIRMED, snapshot.load("remote-device")!!.state)
        store.save(record("remote-device", PairingState.REVOKED))
        snapshot.refresh(store)
        assertEquals(PairingState.REVOKED, snapshot.load("remote-device")!!.state)
    }

    @Test
    fun `refresh drops removed records`() {
        val store = InMemoryPairingTrustStore()
        store.save(record("remote-device", PairingState.CONFIRMED))
        val snapshot = PairingSnapshotCache()
        snapshot.refresh(store)
        assertEquals(PairingState.CONFIRMED, snapshot.load("remote-device")!!.state)
        store.remove("remote-device")
        snapshot.refresh(store)
        assertNull(snapshot.load("remote-device"))
    }
}
