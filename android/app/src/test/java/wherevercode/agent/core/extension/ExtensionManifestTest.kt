package wherevercode.agent.core.extension

import org.junit.Assert.*
import org.junit.Test

class ExtensionManifestTest {
    private fun json(kind: String = "agent", permissions: String = "[]") =
        """{"schemaVersion":1,"id":"example","kind":"$kind","name":"Example","version":"1.0.0","permissions":$permissions}"""

    @Test fun allNineKindsRoundTripWithoutGrantingAuthority() {
        assertEquals(9, ExtensionManifest.KINDS.size)
        ExtensionManifest.KINDS.forEach { kind ->
            val manifest = ExtensionManifest.parse(json(kind))
            assertEquals(manifest, ExtensionManifest.parse(manifest.toJson().toString()))
            assertTrue(manifest.permissions.isEmpty())
        }
    }

    @Test fun rejectsUnsupportedSchemaAndCredentialFields() {
        listOf(json().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            json().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            json().replace("\"id\":\"example\"", "\"id\":\"../escape\""),
            json("unknown"), json().dropLast(1) + ",\"token\":\"secret\"}",
            json(permissions = "[\"root.all\"]"),
            json(permissions = "[\"task.create\",\"task.create\"]"),
            json(permissions = "[true]"), "x".repeat(32769)).forEach { invalid ->
            try { ExtensionManifest.parse(invalid); fail("accepted invalid manifest") }
            catch (_: RuntimeException) { }
        }
    }
}
