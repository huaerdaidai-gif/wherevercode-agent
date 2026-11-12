package wherevercode.agent.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HandInstallBundleTest {
    private fun reader(files: Map<String, ByteArray>) = AssetReader { path ->
        files[path.removePrefix("install/")]
    }

    @Test
    fun `loads all four files and versions from hand_server py hash`() {
        val bundle = HandInstallBundleLoader.load(
            reader(
                mapOf(
                    "v5-install-hand.sh" to "#!/bin/sh\n".toByteArray(),
                    "v5-install-hand.ps1" to "# ps1\n".toByteArray(),
                    "v5-uninstall-hand.sh" to "#!/bin/sh\n".toByteArray(),
                    "hand_server.py" to "# hand\n".toByteArray(),
                ),
            ),
        )
        assertNotNull(bundle)
        assertEquals(HandInstallBundleLoader.versionOf("# hand\n".toByteArray()), bundle!!.version)
        assertEquals(12, bundle.version.length)
        assertEquals("#!/bin/sh\n", bundle.installSh.toString(Charsets.UTF_8))
    }

    @Test
    fun `missing any file returns null`() {
        assertNull(
            HandInstallBundleLoader.load(
                reader(
                    mapOf(
                        "v5-install-hand.sh" to "#!/bin/sh\n".toByteArray(),
                        "v5-install-hand.ps1" to "# ps1\n".toByteArray(),
                        "v5-uninstall-hand.sh" to "#!/bin/sh\n".toByteArray(),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `empty file returns null`() {
        assertNull(
            HandInstallBundleLoader.load(
                reader(
                    mapOf(
                        "v5-install-hand.sh" to "#!/bin/sh\n".toByteArray(),
                        "v5-install-hand.ps1" to "# ps1\n".toByteArray(),
                        "v5-uninstall-hand.sh" to "#!/bin/sh\n".toByteArray(),
                        "hand_server.py" to ByteArray(0),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `version changes when hand_server py changes`() {
        val v1 = HandInstallBundleLoader.versionOf("a".toByteArray())
        val v2 = HandInstallBundleLoader.versionOf("b".toByteArray())
        assertTrue(v1 != v2)
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
