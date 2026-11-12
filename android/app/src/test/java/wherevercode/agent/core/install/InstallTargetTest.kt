package wherevercode.agent.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallTargetTest {
    @Test
    fun `mac lists ssh and local command and existing hand`() {
        val methods = installMethodsFor(DeviceOs.MAC)
        assertTrue(methods.contains(InstallMethod.SSH))
        assertTrue(methods.contains(InstallMethod.LOCAL_COMMAND))
        assertTrue(methods.contains(InstallMethod.EXISTING_HAND))
        assertFalse(methods.contains(InstallMethod.POWERSHELL))
    }

    @Test
    fun `linux lists ssh and local command and existing hand`() {
        val methods = installMethodsFor(DeviceOs.LINUX)
        assertTrue(methods.contains(InstallMethod.SSH))
        assertTrue(methods.contains(InstallMethod.LOCAL_COMMAND))
        assertTrue(methods.contains(InstallMethod.EXISTING_HAND))
        assertFalse(methods.contains(InstallMethod.POWERSHELL))
    }

    @Test
    fun `vps lists ssh and local command and existing hand`() {
        val methods = installMethodsFor(DeviceOs.VPS)
        assertTrue(methods.contains(InstallMethod.SSH))
        assertTrue(methods.contains(InstallMethod.LOCAL_COMMAND))
        assertTrue(methods.contains(InstallMethod.EXISTING_HAND))
    }

    @Test
    fun `windows lists powershell first and never ssh`() {
        val methods = installMethodsFor(DeviceOs.WINDOWS)
        assertEquals(InstallMethod.POWERSHELL, methods.first())
        assertFalse(methods.contains(InstallMethod.SSH))
        assertFalse(methods.contains(InstallMethod.LOCAL_COMMAND))
    }

    @Test
    fun `android lists qr pair and existing hand`() {
        val methods = installMethodsFor(DeviceOs.ANDROID)
        assertTrue(methods.contains(InstallMethod.QR_PAIR))
        assertTrue(methods.contains(InstallMethod.EXISTING_HAND))
        assertFalse(methods.contains(InstallMethod.SSH))
    }

    @Test
    fun `ios only offers guidance and existing hand`() {
        val methods = installMethodsFor(DeviceOs.IOS)
        assertTrue(methods.contains(InstallMethod.GUIDANCE))
        assertTrue(methods.contains(InstallMethod.EXISTING_HAND))
        assertFalse(methods.contains(InstallMethod.SSH))
    }

    @Test
    fun `cloud only offers adapter`() {
        val methods = installMethodsFor(DeviceOs.CLOUD)
        assertEquals(listOf(InstallMethod.ADAPTER), methods)
    }

    @Test
    fun `ssh target rejects empty host and empty username`() {
        assertTrue(SshTarget(host = "", port = 22, username = "test-user").validate().isNotEmpty())
        assertTrue(SshTarget(host = "198.51.100.10", port = 22, username = "").validate().isNotEmpty())
    }

    @Test
    fun `ssh target rejects out of range ports`() {
        assertTrue(SshTarget(host = "198.51.100.10", port = 0, username = "test-user").validate().isNotEmpty())
        assertTrue(SshTarget(host = "198.51.100.10", port = 70000, username = "test-user").validate().isNotEmpty())
        assertTrue(SshTarget(host = "198.51.100.10", port = 22, username = "test-user").validate().isEmpty())
    }

    @Test
    fun `ssh target rejects ssh port equal to hand port`() {
        assertTrue(SshTarget(host = "198.51.100.10", port = 18743, username = "test-user").validate().isNotEmpty())
    }
}
