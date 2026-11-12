package wherevercode.agent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * 配对状态机测试（JUnit4 纯 JVM 测试）。
 * 存储用内存实现（[InMemoryPairingTrustStore]，测试替身的一种，
 * 仅用于测试；生产实现接到 AuthorizationStore）。
 */
class PairingTest {

    private val fingerprint = "ab".repeat(32)
    private val otherFingerprint = "cd".repeat(32)

    private fun peer(fp: String = fingerprint) =
        DiscoveredPeer("xiaomi12", "小米 12", "192.168.1.7", 8443, fp, listOf("hand"))

    private fun manager() = PairingManager(InMemoryPairingTrustStore(), nowEpochSeconds = { 1_000L })

    @Test
    fun unconfirmed_doesNotTrust() {
        val m = manager()
        m.requestPairing(peer())
        assertFalse(m.isTrusted(peer()))
        val reason = m.trustFailureReason(peer())
        assertTrue(reason!!.contains("配对未完成"))
    }

    @Test
    fun confirmOnce_trusts() {
        val m = manager()
        m.requestPairing(peer())
        m.confirm("xiaomi12")
        assertTrue(m.isTrusted(peer()))
        assertNull(m.trustFailureReason(peer()))
    }

    @Test
    fun revokeAfterConfirm_refuses() {
        val m = manager()
        m.requestPairing(peer())
        m.confirm("xiaomi12")
        m.revoke("xiaomi12", "测试：不再信任该设备")
        assertFalse(m.isTrusted(peer()))
        val reason = m.trustFailureReason(peer())
        assertTrue(reason!!.contains("已撤销"))
        assertTrue(reason.contains("测试：不再信任该设备"))
    }

    @Test
    fun reject_doesNotTrust_thenReRequestCanConfirm() {
        val m = manager()
        m.requestPairing(peer())
        m.reject("xiaomi12", "对方拒绝了配对")
        assertFalse(m.isTrusted(peer()))
        assertTrue(m.trustFailureReason(peer())!!.contains("已拒绝"))
        // 重新发起配对后可以再次确认。
        m.requestPairing(peer())
        m.confirm("xiaomi12")
        assertTrue(m.isTrusted(peer()))
    }

    @Test
    fun fingerprintChanged_requiresRepairing() {
        val m = manager()
        m.requestPairing(peer(fingerprint))
        m.confirm("xiaomi12")
        assertTrue(m.isTrusted(peer(fingerprint)))
        // 对端指纹变了（重装 / 换密钥）：旧信任失效。
        assertFalse(m.isTrusted(peer(otherFingerprint)))
        assertTrue(m.trustFailureReason(peer(otherFingerprint))!!.contains("指纹已变化"))
        // 重新配对、核对新指纹后恢复信任。
        val record = m.requestPairing(peer(otherFingerprint))
        assertEquals(PairingState.PENDING, record.state)
        m.confirm("xiaomi12")
        assertTrue(m.isTrusted(peer(otherFingerprint)))
    }

    @Test
    fun confirmTwice_trustsIdempotently() {
        val m = manager()
        m.requestPairing(peer())
        m.confirm("xiaomi12")
        // 已确认且指纹一致时再次请求配对，直接返回已确认记录，不重复弹窗。
        val again = m.requestPairing(peer())
        assertEquals(PairingState.CONFIRMED, again.state)
        assertTrue(m.isTrusted(peer()))
    }

    @Test(expected = IllegalStateException::class)
    fun confirmWithoutRequest_throws() {
        manager().confirm("xiaomi12")
    }

    @Test(expected = IllegalStateException::class)
    fun revokeTwice_throws() {
        val m = manager()
        m.requestPairing(peer())
        m.confirm("xiaomi12")
        m.revoke("xiaomi12", "第一次撤销")
        m.revoke("xiaomi12", "第二次撤销")
    }

    @Test(expected = IllegalStateException::class)
    fun sameAccountAutoTrust_isRefused() {
        // 同账号默认放行已否决：必须本机点一次确认，没有例外。
        manager().requestSameAccountAutoTrust("xiaomi12")
    }

    @Test
    fun migratingStore_movesMemoryRecordsToPersistentWhenItAppears() {
        // V5 加设备修复（2026-11-11）：大脑建好前配对写内存，大脑建
        // 好（Room 出现）后记录迁进持久化，重启后信任仍在。
        val memory = InMemoryPairingTrustStore()
        var persistent: PairingTrustStore? = null
        val store = MigratingPairingTrustStore(memory) { persistent }
        val manager = PairingManager(store, nowEpochSeconds = { 1_000L })
        manager.requestPairing(peer())
        manager.confirm("xiaomi12")
        assertTrue(manager.isTrusted(peer()))

        persistent = InMemoryPairingTrustStore()
        assertTrue(manager.isTrusted(peer()))
        assertEquals(PairingState.CONFIRMED, persistent.load("xiaomi12")?.state)

        // 迁移后的写入直接进持久化，不再回内存。
        manager.revoke("xiaomi12", "测试撤销")
        assertEquals(PairingState.REVOKED, persistent.load("xiaomi12")?.state)
        assertEquals(PairingState.CONFIRMED, memory.load("xiaomi12")?.state)

        // “重启”：新 Manager 接同一持久化存储，信任状态仍在。
        val restarted = PairingManager(persistent, nowEpochSeconds = { 1_000L })
        assertFalse(restarted.isTrusted(peer()))
        assertTrue(restarted.trustFailureReason(peer())!!.contains("已撤销"))
    }

    @Test
    fun migratingStore_withoutPersistent_behavesLikeMemory() {
        val store = MigratingPairingTrustStore { null }
        val manager = PairingManager(store, nowEpochSeconds = { 1_000L })
        manager.requestPairing(peer())
        manager.confirm("xiaomi12")
        assertTrue(manager.isTrusted(peer()))
        assertEquals(1, store.all().size)
    }
}
