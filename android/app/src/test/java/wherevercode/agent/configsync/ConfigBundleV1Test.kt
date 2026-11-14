package wherevercode.agent.configsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBundleV1Test {
    private fun fixture(): String = checkNotNull(javaClass.classLoader?.getResourceAsStream("config-bundle-v1-minimal.json")) {
        "Shared WCA v1 fixture missing"
    }.bufferedReader().use { it.readText() }

    @Test fun decodesSharedGoldenFixtureAndReencodesEquivalentBundle() {
        val input = ConfigBundleV1.fromJsonString(fixture())
        assertEquals(ConfigBundleV1.FORMAT, input.toJson().getString("format"))
        assertEquals("fixture-bundle-0001", input.bundleId)
        assertEquals("device-demo-1", input.payload.devices.single().sourceDeviceId)
        assertEquals("agent-demo", input.payload.agents.single().agentId)
        assertEquals("demo-project", input.payload.projects.single().projectId)
        assertEquals("brain-demo", input.payload.brains.single().brainId)
        assertEquals(3, input.payload.appSettings.freedomLevel)
        assertEquals("reauthorizationRequired", input.payload.brains.single().toJson().getString("credentialState"))
        val roundTrip = ConfigBundleV1.fromJsonString(input.toJsonString())
        assertEquals(input, roundTrip)
    }

    @Test fun rejectsUnknownFormatAndSchemaVersion() {
        val valid = fixture()
        assertTrue(runCatching { ConfigBundleV1.fromJsonString(valid.replace("WCA-ConfigBundle", "other")) }.isFailure)
        assertTrue(runCatching { ConfigBundleV1.fromJsonString(valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")) }.isFailure)
    }
}
