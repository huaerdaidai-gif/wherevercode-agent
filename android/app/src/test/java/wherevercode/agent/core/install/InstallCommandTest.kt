package wherevercode.agent.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallCommandTest {
    @Test
    fun `unix local command has version port and bundle dir`() {
        val cmd = InstallCommand.unixLocalCommand("abc123", "/tmp/bundle", 18744, "mac", "我的 Mac")
        assertTrue(cmd.contains("cd '/tmp/bundle'"))
        assertTrue(cmd.contains("sh v5-install-hand.sh"))
        assertTrue(cmd.contains("--version 'abc123'"))
        assertTrue(cmd.contains("--port 18744"))
        assertTrue(cmd.contains("--device-id 'mac'"))
        assertTrue(cmd.contains("--display-name '我的 Mac'"))
    }

    @Test
    fun `unix local command omits optional device id and display name`() {
        val cmd = InstallCommand.unixLocalCommand("abc123", "/tmp/bundle")
        assertTrue(cmd.contains("--port 18743"))
        assertTrue(!cmd.contains("--device-id"))
        assertTrue(!cmd.contains("--display-name"))
    }

    @Test
    fun `windows local command uses powershell`() {
        val cmd = InstallCommand.windowsLocalCommand("abc123", "C:\\Users\\x\\V5Hand")
        assertTrue(cmd.contains("powershell -ExecutionPolicy Bypass -File .\\v5-install-hand.ps1"))
        assertTrue(cmd.contains("-Version 'abc123'"))
        assertTrue(cmd.contains("-Port 18743"))
    }

    @Test
    fun `ssh install command matches unix local command`() {
        assertEquals(
            InstallCommand.unixLocalCommand("v1", "/tmp/b", 18743, "d", "n"),
            InstallCommand.sshInstallCommand("v1", "/tmp/b", 18743, "d", "n"),
        )
    }

    @Test
    fun `uninstall command runs the uninstall script`() {
        assertEquals(
            "cd '/tmp/b' && sh v5-uninstall-hand.sh",
            InstallCommand.uninstallCommand("/tmp/b"),
        )
    }

    @Test
    fun `shell quote escapes single quotes`() {
        assertEquals("'a'\\''b'", InstallCommand.shellQuote("a'b"))
    }

    @Test
    fun `powershell quote doubles single quotes`() {
        assertEquals("'a''b'", InstallCommand.psQuote("a'b"))
    }
}
