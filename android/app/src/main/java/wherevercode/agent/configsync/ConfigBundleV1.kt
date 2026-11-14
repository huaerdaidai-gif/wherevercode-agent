package wherevercode.agent.configsync

import org.json.JSONArray
import org.json.JSONObject

/** Explicit, allowlisted wire DTO. Never serialize app/store models by reflection. */
data class ConfigBundleV1(
    val bundleId: String,
    val createdAtEpochSeconds: Long,
    val sourcePlatform: String,
    val payload: BundlePayloadV1,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("schemaVersion", SCHEMA_VERSION)
        put("bundleId", bundleId)
        put("createdAtEpochSeconds", createdAtEpochSeconds)
        put("sourcePlatform", sourcePlatform)
        put("payload", payload.toJson())
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        const val FORMAT = "WCA-ConfigBundle"
        const val SCHEMA_VERSION = 1

        fun fromJsonString(json: String): ConfigBundleV1 = fromJson(JSONObject(json))

        fun fromJson(json: JSONObject): ConfigBundleV1 {
            require(json.optString("format") == FORMAT) { "Unsupported bundle format" }
            require(json.optInt("schemaVersion", -1) == SCHEMA_VERSION) { "Unsupported schema version" }
            val payload = json.optJSONObject("payload") ?: error("Missing payload")
            return ConfigBundleV1(
                bundleId = json.requiredString("bundleId"),
                createdAtEpochSeconds = json.getLong("createdAtEpochSeconds"),
                sourcePlatform = json.requiredString("sourcePlatform"),
                payload = BundlePayloadV1.fromJson(payload),
            )
        }
    }
}

data class BundlePayloadV1(
    val devices: List<BundleDeviceV1> = emptyList(),
    val agents: List<BundleAgentV1> = emptyList(),
    val projects: List<BundleProjectV1> = emptyList(),
    val brains: List<BundleBrainV1> = emptyList(),
    val currentBrainId: String? = null,
    val appSettings: BundleAppSettingsV1 = BundleAppSettingsV1(),
) {
    fun toJson() = JSONObject().apply {
        put("devices", devices.toJsonArray { it.toJson() })
        put("agents", agents.toJsonArray { it.toJson() })
        put("projects", projects.toJsonArray { it.toJson() })
        put("brains", brains.toJsonArray { it.toJson() })
        putNullable("currentBrainId", currentBrainId)
        put("appSettings", appSettings.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject) = BundlePayloadV1(
            devices = json.array("devices") { BundleDeviceV1.fromJson(it) },
            agents = json.array("agents") { BundleAgentV1.fromJson(it) },
            projects = json.array("projects") { BundleProjectV1.fromJson(it) },
            brains = json.array("brains") { BundleBrainV1.fromJson(it) },
            currentBrainId = json.optionalString("currentBrainId"),
            appSettings = BundleAppSettingsV1.fromJson(json.optJSONObject("appSettings") ?: JSONObject()),
        )
    }
}

data class BundleDeviceV1(
    val sourceDeviceId: String,
    val displayName: String,
    val host: String,
    val port: Int,
) {
    fun toJson() = JSONObject().apply {
        put("sourceDeviceId", sourceDeviceId); put("displayName", displayName)
        put("host", host); put("port", port); put("pairingState", "reauthorizationRequired")
    }
    companion object {
        fun fromJson(json: JSONObject) = BundleDeviceV1(
            json.requiredString("sourceDeviceId"), json.requiredString("displayName"),
            json.requiredString("host"), json.getInt("port"),
        ).also { require(json.requiredString("pairingState") == "reauthorizationRequired") }
    }
}

data class BundleAgentV1(
    val agentId: String,
    val displayName: String,
    val kind: String,
    val access: String,
    val brainKind: String,
    val adapterId: String,
    val enabled: Boolean,
    val capabilities: List<String> = emptyList(),
    val authorizedProjectIds: List<String> = emptyList(),
    val deviceId: String? = null,
) {
    fun toJson() = JSONObject().apply {
        put("agentId", agentId); put("displayName", displayName); put("kind", kind)
        put("access", access); put("brainKind", brainKind); put("adapterId", adapterId)
        put("enabled", enabled); put("capabilities", capabilities.toJsonArray())
        put("authorizedProjectIds", authorizedProjectIds.toJsonArray()); putNullable("deviceId", deviceId)
        put("credentialState", "reauthorizationRequired")
    }
    companion object {
        fun fromJson(json: JSONObject) = BundleAgentV1(
            agentId = json.requiredString("agentId"), displayName = json.requiredString("displayName"),
            kind = json.requiredString("kind"), access = json.requiredString("access"),
            brainKind = json.requiredString("brainKind"), adapterId = json.requiredString("adapterId"),
            enabled = json.getBoolean("enabled"), capabilities = json.stringArray("capabilities"),
            authorizedProjectIds = json.stringArray("authorizedProjectIds"), deviceId = json.optionalString("deviceId"),
        ).also { require(json.requiredString("credentialState") == "reauthorizationRequired") }
    }
}

data class BundleProjectHomeV1(val homeKind: String, val hostId: String, val pathHint: String) {
    fun toJson() = JSONObject().apply { put("homeKind", homeKind); put("hostId", hostId); put("pathHint", pathHint) }
    companion object { fun fromJson(j: JSONObject) = BundleProjectHomeV1(j.requiredString("homeKind"), j.requiredString("hostId"), j.requiredString("pathHint")) }
}

data class BundleProjectV1(
    val projectId: String,
    val name: String,
    val repoUrl: String? = null,
    val defaultBranch: String = "main",
    val homes: List<BundleProjectHomeV1> = emptyList(),
) {
    fun toJson() = JSONObject().apply {
        put("projectId", projectId); put("name", name); putNullable("repoUrl", repoUrl)
        put("defaultBranch", defaultBranch); put("homes", homes.toJsonArray { it.toJson() })
    }
    companion object {
        fun fromJson(j: JSONObject) = BundleProjectV1(j.requiredString("projectId"), j.requiredString("name"),
            j.optionalString("repoUrl"), j.optString("defaultBranch", "main"),
            j.array("homes") { BundleProjectHomeV1.fromJson(it) })
    }
}

data class BundleBrainV1(
    val brainId: String,
    val kind: String,
    val adapterId: String,
    val label: String,
    val model: String,
    val baseUrl: String? = null,
    val fallbackBrainIds: List<String> = emptyList(),
) {
    fun toJson() = JSONObject().apply {
        put("brainId", brainId); put("kind", kind); put("adapterId", adapterId)
        put("label", label); put("model", model); putNullable("baseUrl", baseUrl)
        put("fallbackBrainIds", fallbackBrainIds.toJsonArray())
        put("credentialState", "reauthorizationRequired")
    }
    companion object {
        fun fromJson(j: JSONObject) = BundleBrainV1(j.requiredString("brainId"), j.requiredString("kind"),
            j.requiredString("adapterId"), j.requiredString("label"), j.requiredString("model"),
            j.optionalString("baseUrl"), j.stringArray("fallbackBrainIds"))
            .also { require(j.requiredString("credentialState") == "reauthorizationRequired") }
    }
}

data class BundleAppSettingsV1(
    val brainDisplayName: String? = null,
) {
    fun toJson() = JSONObject().apply {
        putNullable("brainDisplayName", brainDisplayName)
    }
    companion object {
        fun fromJson(j: JSONObject) = BundleAppSettingsV1(
            brainDisplayName = j.optionalString("brainDisplayName"),
        )
    }
}

private fun JSONObject.requiredString(key: String): String = get(key).let { value ->
    require(value is String) { "Invalid string field: $key" }; value
}
private fun JSONObject.optionalString(key: String): String? = if (!has(key) || isNull(key)) null else requiredString(key)
private fun JSONObject.putNullable(key: String, value: String?) { if (value != null) put(key, value) }
private fun List<String>.toJsonArray() = JSONArray().also { array -> forEach(array::put) }
private fun <T> List<T>.toJsonArray(encode: (T) -> JSONObject) = JSONArray().also { array -> forEach { array.put(encode(it)) } }
private fun JSONObject.stringArray(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { index ->
        val value = array.get(index)
        require(value is String) { "Invalid string array item: $key" }
        value
    }
}
private fun <T> JSONObject.array(key: String, decode: (JSONObject) -> T): List<T> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { index -> decode(array.getJSONObject(index)) }
}
