package wherevercode.agent.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshInstallerTest {
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

    private class FakeSession(private val installOutput: SshExecResult) : SshSession {
        val uploads = mutableMapOf<String, ByteArray>()
        val commands = mutableListOf<String>()

        override fun upload(bytes: ByteArray, remotePath: String, mode: Int) {
            uploads[remotePath] = bytes
        }

        override fun exec(command: String): SshExecResult {
            commands += command
            return if (command.contains("v5-install-hand.sh")) installOutput else SshExecResult(0, "", "")
        }

        override fun close() {}
    }

    private class FakeFactory(private val session: FakeSession) : SshSessionFactory {
        var connectedTarget: SshTarget? = null

        override fun connect(
            target: SshTarget,
            password: CharArray?,
            privateKey: ByteArray?,
            privateKeyPassphrase: CharArray?,
            onHostKey: (String) -> Boolean,
        ): SshSession {
            connectedTarget = target
            return session
        }
    }

    @Test
    fun `install succeeds and parses the installed line`() {
        val fingerprint = "a".repeat(64)
        val session = FakeSession(
            SshExecResult(
                0,
                "…\nV5_HAND_INSTALLED device_id=mac fingerprint=$fingerprint port=18744 version=abc123 state_dir=/tmp/x python=/usr/bin/python3 service=systemd\n",
                "",
            ),
        )
        val factory = FakeFactory(session)
        val outcome = SshInstaller.install(
            factory = factory,
            target = SshTarget("198.51.100.10", 22, "test-user"),
            password = "secret".toCharArray(),
            privateKey = null,
            privateKeyPassphrase = null,
            bundle = bundle(),
            port = 18744,
            deviceId = null,
            displayName = "我的 Mac",
            onHostKey = { true },
            onProgress = {},
        )
        assertTrue(outcome is InstallOutcome.Installed)
        val installed = outcome as InstallOutcome.Installed
        assertEquals("mac", installed.deviceId)
        assertEquals(fingerprint, installed.fingerprint)
        assertEquals(18744, installed.port)
        assertEquals("198.51.100.10", installed.host)
        assertEquals(SshTarget("198.51.100.10", 22, "test-user"), factory.connectedTarget)
        assertTrue(session.uploads.keys.any { it.endsWith("hand_server.py") })
        assertTrue(session.uploads.keys.any { it.endsWith("v5-install-hand.sh") })
        assertTrue(session.commands.any { it.contains("v5-install-hand.sh") })
        assertTrue(session.commands.any { it.startsWith("rm -rf") })
    }

    @Test
    fun `install fails when the script exits non-zero`() {
        val session = FakeSession(SshExecResult(1, "", "python3: command not found"))
        val outcome = SshInstaller.install(
            factory = FakeFactory(session),
            target = SshTarget("198.51.100.10", 22, "test-user"),
            password = "secret".toCharArray(),
            privateKey = null,
            privateKeyPassphrase = null,
            bundle = bundle(),
            port = 18743,
            deviceId = null,
            displayName = "我的 Mac",
            onHostKey = { true },
            onProgress = {},
        )
        assertTrue(outcome is InstallOutcome.Failed)
        assertTrue((outcome as InstallOutcome.Failed).message.contains("python3: command not found"))
    }

    @Test
    fun `install fails when the script reports success but no fingerprint`() {
        val session = FakeSession(SshExecResult(0, "V5_HAND_INSTALLED device_id=mac port=18743\n", ""))
        val outcome = SshInstaller.install(
            factory = FakeFactory(session),
            target = SshTarget("198.51.100.10", 22, "test-user"),
            password = "secret".toCharArray(),
            privateKey = null,
            privateKeyPassphrase = null,
            bundle = bundle(),
            port = 18743,
            deviceId = null,
            displayName = "我的 Mac",
            onHostKey = { true },
            onProgress = {},
        )
        assertTrue(outcome is InstallOutcome.Failed)
    }

    @Test
    fun `install fails early on an invalid target`() {
        val session = FakeSession(SshExecResult(0, "", ""))
        val outcome = SshInstaller.install(
            factory = FakeFactory(session),
            target = SshTarget("", 22, "test-user"),
            password = "secret".toCharArray(),
            privateKey = null,
            privateKeyPassphrase = null,
            bundle = bundle(),
            port = 18743,
            deviceId = null,
            displayName = "我的 Mac",
            onHostKey = { true },
            onProgress = {},
        )
        assertTrue(outcome is InstallOutcome.Failed)
        assertTrue(session.commands.isEmpty())
    }

    @Test
    fun `parseInstalledLine returns null when the line is absent`() {
        assertNull(SshInstaller.parseInstalledLine("hello\nworld\n"))
    }

    @Test
    fun `parseInstalledLine extracts key value pairs`() {
        val map = SshInstaller.parseInstalledLine(
            "V5_HAND_INSTALLED device_id=mac fingerprint=${"b".repeat(64)} port=18743 version=abc123",
        )
        assertEquals("mac", map?.get("device_id"))
        assertEquals("b".repeat(64), map?.get("fingerprint"))
        assertEquals("18743", map?.get("port"))
    }
}
