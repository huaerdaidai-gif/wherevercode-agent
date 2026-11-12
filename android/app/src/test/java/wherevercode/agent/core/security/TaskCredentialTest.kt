package wherevercode.agent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Base64

class TaskCredentialTest {
    private val signingKey = "test-signing-key".toByteArray(Charsets.UTF_8)
    private val revocation = InMemoryRevocationStore()
    private val now = 1_700_000_000L

    private fun issue(
        taskId: String = "task-1",
        target: String = "device-b",
        folder: String = "/projects/demo",
        ttl: Long = 900,
    ) = TaskCredentialIssuer.issue(taskId, target, folder, signingKey, ttl, now)

    private fun verify(
        credential: TaskCredential,
        taskId: String = "task-1",
        target: String = "device-b",
        folder: String = "/projects/demo",
        at: Long = now + 10,
    ) = TaskCredentialIssuer.verify(credential, taskId, target, folder, signingKey, revocation, at)

    @Test
    fun `issue then verify is OK`() {
        assertEquals(CredentialVerifyResult.OK, verify(issue()))
    }

    @Test
    fun `expired credential is refused`() {
        val credential = issue(ttl = 60)
        assertEquals(CredentialVerifyResult.EXPIRED, verify(credential, at = now + 61))
    }

    @Test
    fun `revoked credential is refused`() {
        val credential = issue()
        revocation.revoke(credential.credentialId)
        assertEquals(CredentialVerifyResult.REVOKED, verify(credential))
    }

    @Test
    fun `wrong task is refused`() {
        assertEquals(CredentialVerifyResult.WRONG_TASK, verify(issue(), taskId = "task-2"))
    }

    @Test
    fun `wrong target device is refused`() {
        assertEquals(CredentialVerifyResult.WRONG_TARGET, verify(issue(), target = "device-c"))
    }

    @Test
    fun `wrong folder is refused`() {
        assertEquals(CredentialVerifyResult.WRONG_FOLDER, verify(issue(), folder = "/projects/other"))
    }

    @Test
    fun `tampered signature is refused as invalid`() {
        val credential = issue()
        val tampered = credential.copy(signature = credential.signature.copyOf().also {
            it[0] = (it[0].toInt() xor 0x01).toByte()
        })
        assertEquals(CredentialVerifyResult.INVALID, verify(tampered))
    }

    @Test
    fun `tampered folder is refused as invalid (signature covers it)`() {
        val credential = issue()
        val tampered = credential.copy(folder = "/projects/evil")
        assertEquals(CredentialVerifyResult.INVALID, verify(tampered))
    }

    @Test
    fun `toWire and parse round-trip`() {
        val credential = issue()
        val parsed = parseTaskCredential(credential.toWire())
        assertEquals(credential.taskId, parsed?.taskId)
        assertEquals(credential.credentialId, parsed?.credentialId)
        assertEquals(credential.targetDeviceId, parsed?.targetDeviceId)
        assertEquals(credential.folder, parsed?.folder)
        assertEquals(credential.issuedAtEpochSeconds, parsed?.issuedAtEpochSeconds)
        assertEquals(credential.expiresAtEpochSeconds, parsed?.expiresAtEpochSeconds)
        assertEquals(CredentialVerifyResult.OK, verify(parsed!!))
    }

    @Test
    fun `parse garbage returns null`() {
        assertNull(parseTaskCredential(""))
        assertNull(parseTaskCredential("not-base64!!!"))
        assertNull(parseTaskCredential("aGVsbG8="))
    }

    // -- M2 v2: full binding, epoch / goal-version, host revocation. -----

    private val authStore = InMemoryAuthorizationStore()
    private val user = "user-1"
    private val attempt = "attempt-1"
    private val source = "device-a"
    private val agent = "agent-1"

    private fun issueV2(
        taskId: String = "task-1",
        target: String = "device-b",
        folder: String = "/projects/demo",
        epoch: Long = 1,
        goalVersion: Long = 1,
        ttl: Long = 900,
        at: Long = now,
    ) = TaskCredentialIssuer.issue(
        userId = user,
        taskId = taskId,
        attempt = attempt,
        sourceDeviceId = source,
        targetDeviceId = target,
        agentId = agent,
        folder = folder,
        signingKey = signingKey,
        ttlSeconds = ttl,
        epoch = epoch,
        goalVersion = goalVersion,
        nowEpochSeconds = at,
    )

    private fun expectation(
        epoch: Long = 1,
        goalVersion: Long = 1,
        taskId: String = "task-1",
        target: String = "device-b",
        folder: String = "/projects/demo",
    ) = CredentialExpectation(
        userId = user,
        taskId = taskId,
        attempt = attempt,
        sourceDeviceId = source,
        targetDeviceId = target,
        agentId = agent,
        folder = folder,
        epoch = epoch,
        goalVersion = goalVersion,
    )

    private fun verifyV2(
        credential: TaskCredential,
        expected: CredentialExpectation = expectation(),
        at: Long = now + 10,
    ) = TaskCredentialIssuer.verifyV2(credential, expected, signingKey, revocation, authStore, at)

    @Test
    fun `v2 issue then verifyV2 is OK`() {
        authStore.grant(agent, "/projects/demo")
        assertEquals(CredentialCheck.OK, verifyV2(issueV2()))
    }

    @Test
    fun `v2 wrong user is refused`() {
        authStore.grant(agent, "/projects/demo")
        val credential = issueV2()
        assertEquals(
            CredentialCheck.WRONG_USER,
            verifyV2(credential, expectation().copy(userId = "user-2")),
        )
    }

    @Test
    fun `v2 wrong attempt is refused`() {
        authStore.grant(agent, "/projects/demo")
        assertEquals(
            CredentialCheck.WRONG_ATTEMPT,
            verifyV2(issueV2(), expectation().copy(attempt = "attempt-2")),
        )
    }

    @Test
    fun `v2 missing expected attempt is refused`() {
        authStore.grant(agent, "/projects/demo")
        assertEquals(
            CredentialCheck.WRONG_ATTEMPT,
            verifyV2(issueV2(), expectation().copy(attempt = "")),
        )
    }

    @Test
    fun `v2 wrong source device is refused`() {
        authStore.grant(agent, "/projects/demo")
        assertEquals(
            CredentialCheck.WRONG_SOURCE,
            verifyV2(issueV2(), expectation().copy(sourceDeviceId = "device-x")),
        )
    }

    @Test
    fun `v2 wrong agent is refused`() {
        authStore.grant(agent, "/projects/demo")
        assertEquals(
            CredentialCheck.WRONG_AGENT,
            verifyV2(issueV2(), expectation().copy(agentId = "agent-2")),
        )
    }

    @Test
    fun `v2 credential issued for another folder is refused`() {
        // Scope is the grant the issue relied on (agent + folder): a
        // credential issued for another folder fails the folder binding
        // (its scope never matched this call).
        authStore.grant(agent, "/projects/demo")
        authStore.grant(agent, "/projects/other")
        val credential = issueV2(folder = "/projects/other")
        assertEquals(CredentialCheck.WRONG_FOLDER, verifyV2(credential))
    }

    @Test
    fun `v2 grant revoked after issue is refused`() {
        authStore.grant(agent, "/projects/demo")
        val credential = issueV2()
        assertEquals(CredentialCheck.OK, verifyV2(credential))
        authStore.revoke(agent, "/projects/demo")
        assertEquals(CredentialCheck.GRANT_MISSING, verifyV2(credential))
    }

    @Test
    fun `v2 stale epoch is refused`() {
        authStore.grant(agent, "/projects/demo")
        assertEquals(
            CredentialCheck.STALE_EPOCH,
            verifyV2(issueV2(epoch = 1), expectation(epoch = 2)),
        )
    }

    @Test
    fun `v2 stale goal version is refused`() {
        authStore.grant(agent, "/projects/demo")
        assertEquals(
            CredentialCheck.STALE_GOAL_VERSION,
            verifyV2(issueV2(goalVersion = 1), expectation(goalVersion = 2)),
        )
    }

    @Test
    fun `v2 wire parses, v1 wire is explicitly rejected`() {
        val v2 = issueV2()
        val parsed = parseTaskCredential(v2.toWire())
        assertNotNull(parsed)
        assertEquals(v2.credentialId, parsed?.credentialId)
        assertEquals(v2.userId, parsed?.userId)
        assertEquals(v2.attempt, parsed?.attempt)
        assertEquals(v2.sourceDeviceId, parsed?.sourceDeviceId)
        assertEquals(v2.agentId, parsed?.agentId)
        assertEquals(v2.epoch, parsed?.epoch)
        assertEquals(v2.goalVersion, parsed?.goalVersion)
        // A v1-shaped wire (same fields, v:1) is rejected, no shim.
        val v1Json = """{"v":1,"task_id":"task-1","credential_id":"${v2.credentialId}","target_device_id":"device-b","folder":"/projects/demo","iat":$now,"exp":${now + 900},"sig_b64":"AAAA"}"""
        val v1Wire = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(v1Json.toByteArray(Charsets.UTF_8))
        assertNull(parseTaskCredential(v1Wire))
    }

    @Test
    fun `v2 revoke_host kills credentials issued before revocation only`() {
        authStore.grant(agent, "/projects/demo")
        val before = issueV2(at = now)
        revocation.revokeHost("device-b", revokedAtEpochSeconds = now + 100)
        assertEquals(CredentialCheck.HOST_REVOKED, verifyV2(before, at = now + 110))
        val after = issueV2(at = now + 200)
        assertEquals(CredentialCheck.OK, verifyV2(after, at = now + 210))
    }

    @Test
    fun `v2 task revocation kills every credential of that task`() {
        authStore.grant(agent, "/projects/demo")
        val credential = issueV2()
        assertEquals(CredentialCheck.OK, verifyV2(credential))
        revocation.revokeTask("task-1")
        assertEquals(CredentialCheck.TASK_REVOKED, verifyV2(credential))
        // Another task's credential is unaffected.
        val other = issueV2(taskId = "task-2")
        assertEquals(CredentialCheck.OK, verifyV2(other, expectation(taskId = "task-2")))
    }
}
