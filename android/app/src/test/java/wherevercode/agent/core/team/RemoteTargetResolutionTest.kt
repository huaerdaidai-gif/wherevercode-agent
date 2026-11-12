package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingState
import wherevercode.agent.core.transport.RemoteDevice

/**
 * 纯判定单测（V5 修复，2026-11-11）：测试通过的设备，对话必须能
 * 认出来。maintainer 的真机用例：deviceId remote-device、host
 * 192.0.2.10:18743、配对 CONFIRMED、文件夹 /workspace/example-project。
 */
class RemoteTargetResolutionTest {
    private val fingerprint = "bb".repeat(32)

    private fun grokBuild(folder: String? = "/workspace/example-project", deviceId: String? = "remote-device") =
        AgentRecord(
            agentId = "example-agent",
            displayName = "Example Agent",
            kind = AgentKind.DEVICE,
            access = AgentAccess.CLI,
            brainKind = BrainKind.AGENT_RUNTIME,
            adapterId = "example-agent",
            binary = "example-agent",
            deviceId = deviceId,
            deviceFolder = folder,
        )

    private fun usServer() = RemoteDevice(
        deviceId = "remote-device",
        displayName = "remote-host",
        host = "192.0.2.10",
        port = 18743,
        addedAtEpochSeconds = 1_794_334_408L,
    )

    private fun pairing(
        state: PairingState = PairingState.CONFIRMED,
        peerPublicKey: ByteArray? = ByteArray(32) { 7 },
    ) = PairingRecord(
        deviceId = "remote-device",
        displayName = "remote-host",
        fingerprintSha256 = fingerprint,
        peerPublicKey = peerPublicKey,
        state = state,
        updatedAtEpochSeconds = 1_794_334_408L,
    )

    @Test
    fun `tested device resolves to a remote target`() {
        val target = resolveRemoteTargetFromRecords(grokBuild(), usServer(), pairing())
        assertNotNull(target)
        assertEquals("example-agent", target!!.agentId)
        assertEquals("/workspace/example-project", target.folder)
        assertEquals("remote-device", target.peer.deviceId)
    }

    @Test
    fun `missing pairing record resolves to null`() {
        assertNull(resolveRemoteTargetFromRecords(grokBuild(), usServer(), null))
    }

    @Test
    fun `pending pairing resolves to null`() {
        assertNull(
            resolveRemoteTargetFromRecords(grokBuild(), usServer(), pairing(state = PairingState.PENDING)),
        )
    }

    @Test
    fun `pairing without a peer public key resolves to null`() {
        assertNull(resolveRemoteTargetFromRecords(grokBuild(), usServer(), pairing(peerPublicKey = null)))
    }

    @Test
    fun `agent without a folder resolves to null`() {
        assertNull(resolveRemoteTargetFromRecords(grokBuild(folder = null), usServer(), pairing()))
        assertNull(resolveRemoteTargetFromRecords(grokBuild(folder = "  "), usServer(), pairing()))
    }

    @Test
    fun `missing device resolves to null`() {
        assertNull(resolveRemoteTargetFromRecords(grokBuild(), null, pairing()))
    }

    @Test
    fun `agent without a device resolves to null`() {
        assertNull(resolveRemoteTargetFromRecords(grokBuild(deviceId = null), usServer(), pairing()))
    }

    @Test
    fun `device id mismatch resolves to null`() {
        val other = usServer().copy(deviceId = "mac")
        assertNull(resolveRemoteTargetFromRecords(grokBuild(), other, pairing()))
    }
}
