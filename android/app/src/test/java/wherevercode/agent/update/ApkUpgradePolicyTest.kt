package wherevercode.agent.update

import org.junit.Assert.*
import org.junit.Test

class ApkUpgradePolicyTest {
    private val original = "a".repeat(64)
    private val other = "b".repeat(64)
    private fun identity(version: Long = 182, name: String = "wherevercode.agent", signers: Set<String> = setOf(original)) =
        ApkUpgradeIdentity(name, version, signers)

    @Test fun `Build 182 upgrades with original key and larger counter`() {
        assertNull(apkUpgradeRejection(identity(), identity(183)))
    }
    @Test fun `Build 5 upgrades using same policy`() {
        assertNull(apkUpgradeRejection(identity(5), identity(183)))
    }
    @Test fun `lower and equal versions are rejected even with a different archive checksum`() {
        assertNotNull(apkUpgradeRejection(identity(), identity(7)))
        assertNotNull(apkUpgradeRejection(identity(), identity(182)))
    }
    @Test fun `CI and production packages never replace each other`() {
        assertNotNull(apkUpgradeRejection(identity(), identity(183, "wherevercode.agent.ci")))
        assertNotNull(apkUpgradeRejection(identity(name = "wherevercode.agent.ci"), identity(183)))
    }
    @Test fun `new debug key cannot masquerade as compatible production update`() {
        val reason = apkUpgradeRejection(identity(), identity(183, signers = setOf(other)))!!
        assertTrue(reason.contains("签名"))
        assertTrue(reason.contains("不要卸载"))
    }
    @Test fun `unknown or unsigned archives fail closed`() {
        assertNotNull(apkUpgradeRejection(identity(), identity(183, signers = emptySet())))
        assertNotNull(apkUpgradeRejection(identity(signers = emptySet()), identity(183)))
        assertNotNull(apkUpgradeRejection(identity(), identity(183, signers = setOf("invalid"))))
    }
    @Test fun `every signer must match for multi signed packages`() {
        val both = setOf(original, other)
        assertNull(apkUpgradeRejection(identity(signers = both), identity(183, signers = both)))
        assertNotNull(apkUpgradeRejection(identity(signers = both), identity(183)))
    }
    @Test fun `fingerprint formatting does not cause false conflicts`() {
        assertNull(apkUpgradeRejection(identity(), identity(183, signers = setOf("  " + original.uppercase() + "  "))))
    }
}
