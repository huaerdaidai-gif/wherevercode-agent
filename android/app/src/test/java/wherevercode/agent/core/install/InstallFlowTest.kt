package wherevercode.agent.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallFlowTest {
    private fun bundle(): HandInstallBundle {
        val py = "# hand\n".toByteArray()
        return HandInstallBundle(
            version = HandInstallBundleLoader.versionOf(py),
            installSh = "#!/bin/sh\n".toByteArray(),
            installPs1 = "# ps1\n".toByteArray(),
            uninstallSh = "#!/bin/sh\n".toByteArray(),
            handServerPy = py,
        )
    }

    @Test
    fun `first step is select os`() {
        assertEquals(InstallStep.SelectOs, InstallFlow.firstStep())
    }

    @Test
    fun `ssh method leads to the ssh form`() {
        val step = InstallFlow.stepAfterMethod(DeviceOs.MAC, InstallMethod.SSH, bundle())
        assertTrue(step is InstallStep.SshForm)
        assertEquals(DeviceOs.MAC, (step as InstallStep.SshForm).os)
    }

    @Test
    fun `local command method shows the unix command`() {
        val step = InstallFlow.stepAfterMethod(DeviceOs.LINUX, InstallMethod.LOCAL_COMMAND, bundle())
        assertTrue(step is InstallStep.ShowCommand)
        val cmd = (step as InstallStep.ShowCommand).command
        assertTrue(cmd.contains("v5-install-hand.sh"))
        assertTrue(cmd.contains(bundle().version))
    }

    @Test
    fun `powershell method shows the powershell command`() {
        val step = InstallFlow.stepAfterMethod(DeviceOs.WINDOWS, InstallMethod.POWERSHELL, bundle())
        assertTrue(step is InstallStep.ShowCommand)
        assertTrue((step as InstallStep.ShowCommand).command.contains("v5-install-hand.ps1"))
    }

    @Test
    fun `existing hand method is guidance`() {
        val step = InstallFlow.stepAfterMethod(DeviceOs.MAC, InstallMethod.EXISTING_HAND, bundle())
        assertTrue(step is InstallStep.Guidance)
    }

    @Test
    fun `ios guidance does not promise silent install`() {
        val step = InstallFlow.stepAfterMethod(DeviceOs.IOS, InstallMethod.GUIDANCE, bundle())
        assertTrue(step is InstallStep.Guidance)
        val text = (step as InstallStep.Guidance).text
        assertTrue(text.contains("不承诺静默安装"))
    }

    @Test
    fun `installed and failed are terminal`() {
        assertTrue(InstallFlow.isTerminal(InstallStep.Installed("d", "f", 18743, "h", "n")))
        assertTrue(InstallFlow.isTerminal(InstallStep.Failed("x")))
        assertFalse(InstallFlow.isTerminal(InstallStep.SelectOs))
    }
}
