package wherevercode.agent.configsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ConfigImportValidatorTest {
    private fun fixture(name: String): String = checkNotNull(javaClass.classLoader?.getResourceAsStream(name))
        .bufferedReader().use { it.readText() }

    @Test fun acceptsAbsentNullableAndEmptySectionsFixtures() {
        val nullableJson = fixture("config-bundle-v1-absent-nullable.json")
        val nullableBundle = ConfigBundleV1.fromJsonString(nullableJson)
        assertEquals(null, nullableBundle.payload.projects.single().repoUrl)
        assertEquals(null, nullableBundle.payload.appSettings.brainDisplayName)
        val nullable = ConfigImportValidator.validate(nullableJson)
        assertEquals(1, nullable.addCount)
        assertEquals(0, nullable.conflictCount)
        val empty = ConfigImportValidator.validate(fixture("config-bundle-v1-empty-sections.json"))
        assertTrue(empty.items.isEmpty())
    }

    @Test fun producesDryRunDiffWithoutChangingExistingSnapshot() {
        val input = ConfigBundleV1.fromJsonString(fixture("config-bundle-v1-minimal.json"))
        val existing = input.payload.copy()
        val before = existing
        val plan = ConfigImportValidator.validate(fixture("config-bundle-v1-minimal.json"), existing)
        assertEquals(0, plan.addCount)
        assertTrue(plan.unchangedCount > 0)
        assertEquals(before, existing)
        assertTrue(plan.warnings.any { it.contains("path hint") })
        assertTrue(plan.warnings.any { it.contains("endpoint") })
    }

    @Test fun sameNameDifferentIdCreatesConflictEdge() {
        val existing = BundlePayloadV1(projects = listOf(BundleProjectV1("local-project", "示例项目")))
        val plan = ConfigImportValidator.validate(fixture("config-bundle-v1-minimal.json"), existing)
        val project = plan.items.single { it.category == "project" }
        assertEquals(PlanAction.CONFLICT, project.action)
        assertEquals(listOf("local-project"), project.conflictsWith)
    }

    @Test fun rejectsUnknownSchemaAndSecretsPolicy() {
        val source = JSONObject(fixture("config-bundle-v1-empty-sections.json"))
        val badVersion = JSONObject(source.toString()).put("schemaVersion", 99)
        assertRejected("unsupportedSchema", badVersion)
        val badPolicy = JSONObject(source.toString()).put("secretsPolicy", "allowSecrets")
        assertRejected("secretsPolicyViolation", badPolicy)
    }

    @Test fun rejectsMalformedJsonAndMissingRequiredSection() {
        assertTrue(runCatching { ConfigImportValidator.validate("{broken") }.exceptionOrNull() is ConfigImportRejected)
        val missing = JSONObject(fixture("config-bundle-v1-empty-sections.json"))
        missing.getJSONObject("payload").remove("agents")
        assertTrue(runCatching { ConfigImportValidator.validate(missing.toString()) }.exceptionOrNull() is ConfigImportRejected)
    }

    @Test fun rejectsUnclosedReferencesAndUrlUserInfo() {
        val source = JSONObject(fixture("config-bundle-v1-minimal.json"))
        val payload = source.getJSONObject("payload")
        payload.getJSONArray("agents").getJSONObject(0).put("authorizedProjectIds", org.json.JSONArray().put("missing"))
        assertRejected("unclosedReference", source)
        val withUserInfo = JSONObject(fixture("config-bundle-v1-minimal.json"))
        withUserInfo.getJSONObject("payload").getJSONArray("projects").getJSONObject(0)
            .put("repoUrl", "https://user:password@example.invalid/repo")
        assertRejected("urlUserInfo", withUserInfo)
    }

    @Test fun rejectsSecretLikeKeysAndValuesAndPathTraversal() {
        val secretKey = JSONObject(fixture("config-bundle-v1-empty-sections.json"))
        secretKey.getJSONObject("payload").put("apiKey", "fake-secret")
        assertRejected("secretDetected", secretKey)
        val secretValue = JSONObject(fixture("config-bundle-v1-empty-sections.json"))
        secretValue.getJSONObject("payload").put("note", "Bearer fake-token")
        assertRejected("secretDetected", secretValue)
        val path = JSONObject(fixture("config-bundle-v1-minimal.json"))
        path.getJSONObject("payload").getJSONArray("projects").getJSONObject(0).getJSONArray("homes")
            .getJSONObject(0).put("pathHint", "../../private")
        assertRejected("unsafePath", path)
    }

    private fun assertRejected(code: String, value: JSONObject) {
        val failure = runCatching { ConfigImportValidator.validate(value.toString()) }.exceptionOrNull()
        assertTrue("expected rejection $code, got $failure", failure is ConfigImportRejected && failure.code == code)
    }
}
