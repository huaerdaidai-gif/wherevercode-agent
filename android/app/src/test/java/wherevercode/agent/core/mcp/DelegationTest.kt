package wherevercode.agent.core.mcp

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.security.CredentialVerifyResult
import wherevercode.agent.core.security.DeviceKeys
import wherevercode.agent.core.security.RevocationStore
import wherevercode.agent.core.security.TaskCredentialIssuer
import wherevercode.agent.core.security.toWire
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * Delegation（小米 12 中间调度者）测试（JUnit4 纯 JVM 测试）。
 *
 * 下一跳与撤销存储一律用测试替身：[CapturingNextHop] / [FakeRevocationStore]，
 * 不触网。凭证签发 / 验签用 A 路按契约实现的真实 API。
 * 真机验证见回报里的“局域网真机验证步骤清单”，在设备端跑。
 */
// 测试替身：捕获下一跳请求的假下一跳。
private class CapturingNextHop(
    var scripted: Flow<HandEvent> = emptyFlow(),
) : NextHopCall {
    var captured: HandCallRequest? = null
    var calls = 0

    override fun call(request: HandCallRequest): Flow<HandEvent> {
        captured = request
        calls++
        return scripted
    }
}

// 测试替身：内存撤销存储。
private class FakeRevocationStore : RevocationStore {
    private val revoked = mutableSetOf<String>()
    private val revokedTasks = mutableSetOf<String>()
    private val revokedHosts = mutableMapOf<String, Long>()

    override fun revoke(credentialId: String) {
        revoked.add(credentialId)
    }

    override fun isRevoked(credentialId: String): Boolean = credentialId in revoked

    override fun revokeTask(taskId: String) {
        revokedTasks.add(taskId)
    }

    override fun isTaskRevoked(taskId: String): Boolean = taskId in revokedTasks

    override fun revokeHost(hostId: String, revokedAtEpochSeconds: Long) {
        revokedHosts[hostId] = revokedAtEpochSeconds
    }

    override fun isHostRevoked(hostId: String, issuedAtEpochSeconds: Long): Boolean {
        val revokedAt = revokedHosts[hostId] ?: return false
        return issuedAtEpochSeconds < revokedAt
    }
}

class DelegationTest {

    private val signingKey = "test-signing-key".toByteArray(Charsets.UTF_8)
    private val macKeyPair = DeviceKeys.generateKeyPair()

    private val macPeer = DiscoveredPeer(
        deviceId = "mac",
        displayName = "Mac",
        host = "192.168.1.5",
        port = 8443,
        fingerprintSha256 = "cd".repeat(32),
        capabilities = listOf("hand"),
    )

    private var nowSeconds = 1_000_000L

    private fun incomingCredentialWire(ttlSeconds: Long = 900L): String {
        val credential = TaskCredentialIssuer.issue(
            taskId = "task-1",
            targetDeviceId = "xiaomi12",
            folder = "/proj",
            signingKey = signingKey,
            ttlSeconds = ttlSeconds,
            nowEpochSeconds = nowSeconds,
        )
        return credential.toWire()
    }

    private fun incoming(
        credentialWire: String = incomingCredentialWire(),
        lastSeq: Long = 41L,
        remainingTimeoutMs: Long = 60_000L,
    ) = IncomingDelegatedCall(
        taskId = "task-1",
        agentId = "muse",
        sourceDeviceId = "k70",
        targetDeviceId = "mac",
        folder = "/proj",
        action = "read",
        args = mapOf("path" to "README.md"),
        credentialWire = credentialWire,
        lastSeq = lastSeq,
        remainingTimeoutMs = remainingTimeoutMs,
        peer = macPeer,
        peerPublicKey = macKeyPair.publicKey,
    )

    private fun delegator(nextHop: NextHopCall) = Delegator(
        nextHop = nextHop,
        ownDeviceId = "xiaomi12",
        signingKey = signingKey,
        revocation = FakeRevocationStore(),
        nowEpochSeconds = { nowSeconds },
    )

    @Test
    fun forward_newCredentialSameTaskShorterTtl_seqContinues() = runBlocking {
        val nextHop = CapturingNextHop(
            flowOf(
                HandEvent.Progress(42, "Mac 开始", null),
                HandEvent.Result(43, "mac-done"),
            ),
        )
        val events = delegator(nextHop).forward(incoming()).toList()
        // 转授：同一任务 id，TTL 缩短（900 − 60 回程预留 = 840）。
        val captured = nextHop.captured!!
        assertEquals("task-1", captured.taskId)
        assertEquals(840L, captured.credentialTtlSeconds)
        assertEquals(macPeer, captured.peer)
        // seq 续接：上游最大 41 → 下一跳从 42 开始。
        assertEquals(42L, captured.firstSeq)
        // 进度与结果原样转发，seq 不变。
        assertEquals(
            listOf(
                HandEvent.Progress(42, "Mac 开始", null),
                HandEvent.Result(43, "mac-done"),
            ),
            events,
        )
    }

    @Test
    fun forward_timeoutTakesMinimumRemaining() = runBlocking {
        val neverEnding: Flow<HandEvent> = flow { awaitCancellation() }
        val nextHop = CapturingNextHop(neverEnding)
        val startedAtMs = System.currentTimeMillis()
        // 上游剩余超时 500ms，转授后凭证剩余 840s：取小，不得延长。
        val events = delegator(nextHop)
            .forward(incoming(remainingTimeoutMs = 500L)).toList()
        val elapsedMs = System.currentTimeMillis() - startedAtMs
        assertTrue("应在约 500ms 超时，实际 ${elapsedMs}ms", elapsedMs < 10_000)
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertTrue(blocked.reason.contains("超时"))
    }

    @Test
    fun forward_nextHopBlocked_returnsReasonUpstream() = runBlocking {
        val nextHop = CapturingNextHop(
            flowOf(
                HandEvent.Blocked(
                    "Mac 不在线：请检查 Mac 是否开机",
                    HandProtocol.Errors.NOT_AUTHORIZED,
                ),
            ),
        )
        val events = delegator(nextHop).forward(incoming()).toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertTrue(blocked.reason.contains("Mac 不在线"))
    }

    @Test
    fun forward_nextHopThrows_returnsChineseReason() = runBlocking {
        val throwingHop = NextHopCall { throw RuntimeException("boom") }
        val events = delegator(throwingHop).forward(incoming()).toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertTrue(blocked.reason.contains("转发失败"))
        assertTrue(blocked.reason.contains("boom"))
    }

    @Test
    fun forward_unparseableCredential_rejected_noNextHop() = runBlocking {
        val nextHop = CapturingNextHop()
        val events = delegator(nextHop)
            .forward(incoming(credentialWire = "not-a-credential")).toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertEquals(HandProtocol.Errors.INVALID_CREDENTIAL, blocked.code)
        assertEquals(0, nextHop.calls)
    }

    @Test
    fun forward_expiredCredential_rejected_noNextHop() = runBlocking {
        val nextHop = CapturingNextHop()
        // 凭证 TTL 1 秒，2 秒后再转授：已过期。
        val wire = incomingCredentialWire(ttlSeconds = 1L)
        nowSeconds += 2
        val events = delegator(nextHop).forward(incoming(credentialWire = wire)).toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertEquals(HandProtocol.Errors.EXPIRED_CREDENTIAL, blocked.code)
        assertTrue(blocked.reason.contains("过期"))
        assertEquals(0, nextHop.calls)
    }

    @Test
    fun forward_tooLittleTimeLeft_refused_noNextHop() = runBlocking {
        val nextHop = CapturingNextHop()
        // 上游凭证只剩 100 秒，少于 120 秒转授下限：拒绝。
        val wire = incomingCredentialWire(ttlSeconds = 100L)
        val events = delegator(nextHop).forward(incoming(credentialWire = wire)).toList()
        assertEquals(1, events.size)
        val blocked = events[0] as HandEvent.Blocked
        assertTrue(blocked.reason.contains("剩余时间不足"))
        assertEquals(0, nextHop.calls)
    }

    @Test
    fun verifyReasonMapping_sanity() {
        // 仅确认枚举值存在、契约一致（转授拒绝文案依赖它）。
        assertEquals(
            CredentialVerifyResult.OK,
            CredentialVerifyResult.valueOf("OK"),
        )
    }
}
