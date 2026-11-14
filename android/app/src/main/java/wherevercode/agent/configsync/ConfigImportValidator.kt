package wherevercode.agent.configsync

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Pure dry-run validation. This class has no store, Context, or write dependency. */
object ConfigImportValidator {
    const val MAX_JSON_CHARS = 2_000_000
    private val sensitiveKey = Regex("(?i)(api[_-]?key|token|secret|auth[_-]?ref|password|passwd|private[_-]?key|credential(value)?)")
    private val secretValue = Regex("(?i)(sk-[A-Za-z0-9_-]{12,}|Bearer\\s+\\S+|-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----)")

    fun validate(jsonText: String, existing: BundlePayloadV1 = BundlePayloadV1()): ImportPlan {
        if (jsonText.length > MAX_JSON_CHARS) reject("sizeLimit", "Bundle exceeds the JSON size limit")
        if (jsonText.toByteArray(Charsets.UTF_8).size > MAX_JSON_CHARS) reject("sizeLimit", "Bundle exceeds the JSON size limit")
        val root = try { JSONObject(jsonText) } catch (_: Exception) { reject("malformedJson", "Invalid JSON") }
        scanSecrets(root)
        val bundle = try { ConfigBundleV1.fromJson(root) } catch (e: Exception) {
            val message = e.message.orEmpty()
            val code = when {
                message.contains("version", true) || message.contains("format", true) -> "unsupportedSchema"
                message.contains("policy", true) -> "secretsPolicyViolation"
                else -> "invalidSchema"
            }
            reject(code, "Bundle schema or policy is invalid")
        }
        if (bundle.bundleId.isBlank() || !bundle.bundleNonce.matches(Regex("^[A-Za-z0-9_-]{16,128}$"))) {
            reject("invalidMetadata", "Missing or malformed bundle identity/nonce")
        }
        if (bundle.sourcePlatform !in setOf("android", "ios")) reject("invalidMetadata", "Unknown source platform")
        if (bundle.secretsPolicy != ConfigBundleV1.SECRETS_POLICY_NON_SECRET_ONLY) reject("secretsPolicyViolation", "Only non-secret bundles are accepted")
        val p = bundle.payload
        unique(p.devices.map { it.sourceDeviceId }, "duplicateDeviceId")
        unique(p.agents.map { it.agentId }, "duplicateAgentId")
        unique(p.projects.map { it.projectId }, "duplicateProjectId")
        unique(p.brains.map { it.brainId }, "duplicateBrainId")
        val deviceIds = p.devices.map { it.sourceDeviceId }.toSet()
        val projectIds = p.projects.map { it.projectId }.toSet()
        val brainIds = p.brains.map { it.brainId }.toSet()
        p.agents.forEach { agent ->
            if (agent.deviceId != null && agent.deviceId !in deviceIds) reject("unclosedReference", "Agent device reference is unresolved")
            if (agent.authorizedProjectIds.any { it !in projectIds }) reject("unclosedReference", "Agent project reference is unresolved")
        }
        p.brains.forEach { brain ->
            if (brain.fallbackBrainIds.any { it !in brainIds }) reject("unclosedReference", "Brain fallback reference is unresolved")
            rejectUserInfo(brain.baseUrl, "baseUrl")
        }
        if (p.currentBrainId != null && p.currentBrainId !in brainIds) reject("unclosedReference", "Current brain reference is unresolved")
        p.projects.forEach { project ->
            rejectUserInfo(project.repoUrl, "repoUrl")
            project.homes.forEach { home ->
                if (home.homeKind.equals("DEVICE", ignoreCase = true) && home.hostId !in deviceIds) {
                    reject("unclosedReference", "Project device-home reference is unresolved")
                }
                if (home.pathHint.contains('\u0000') || home.pathHint.split('/', '\\').any { it == ".." }) {
                    reject("unsafePath", "Project path hint contains traversal or NUL")
                }
            }
        }
        val warnings = buildList {
            p.devices.filter { it.host.isNotBlank() }.forEach { add("Device endpoint is sensitive and requires user review: ${it.sourceDeviceId}") }
            p.projects.flatMap { project -> project.homes.filter { it.pathHint.isNotBlank() }.map { project.projectId } }
                .forEach { add("Project path hint is local-sensitive and requires rebinding: $it") }
        }
        return ImportPlan(bundle.bundleId, bundle.bundleNonce, items(p, existing), warnings)
    }

    private fun scanSecrets(value: Any?, key: String? = null, depth: Int = 0) {
        if (depth > 64) reject("nestingLimit", "Bundle nesting exceeds the limit")
        if (key != null && key !in setOf("secretsPolicy", "credentialState", "pairingState") && sensitiveKey.containsMatchIn(key)) {
            reject("secretDetected", "Secret-like field is forbidden in a non-secret bundle")
        }
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) { val child = keys.next(); scanSecrets(value.get(child), child, depth + 1) }
            }
            is JSONArray -> for (i in 0 until value.length()) scanSecrets(value.get(i), key, depth + 1)
            is String -> if (secretValue.containsMatchIn(value)) reject("secretDetected", "Secret-like value is forbidden in a non-secret bundle")
        }
    }

    private fun rejectUserInfo(value: String?, field: String) {
        if (value == null) return
        val uri = try { URI(value) } catch (_: Exception) { reject("invalidUrl", "$field is invalid") }
        if (uri.rawUserInfo != null) reject("urlUserInfo", "$field must not contain URL userinfo")
    }

    private fun unique(ids: List<String>, code: String) {
        if (ids.any { it.isBlank() } || ids.size != ids.toSet().size) reject(code, "Identifiers must be non-empty and unique")
    }

    private fun items(incoming: BundlePayloadV1, existing: BundlePayloadV1): List<PlannedItem> = buildList {
        fun <T> addAll(category: String, incoming: List<T>, prior: List<T>, idOf: (T) -> String, nameOf: (T) -> String) {
            val byId = prior.associateBy(idOf)
            incoming.forEach { value ->
                val id = idOf(value)
                val old = byId[id]
                val nameMatches = prior.filter { idOf(it) != id && nameOf(it).equals(nameOf(value), ignoreCase = true) }.map(idOf)
                val conflicts = if (old != null && old != value) listOf(id) else nameMatches
                val action = when { old != null && old == value -> PlanAction.UNCHANGED; old != null || nameMatches.isNotEmpty() -> PlanAction.CONFLICT; else -> PlanAction.ADD }
                add(PlannedItem(category, id, action, conflicts))
            }
        }
        addAll("device", incoming.devices, existing.devices, { it.sourceDeviceId }, { it.displayName })
        addAll("agent", incoming.agents, existing.agents, { it.agentId }, { it.displayName })
        addAll("project", incoming.projects, existing.projects, { it.projectId }, { it.name })
        addAll("brain", incoming.brains, existing.brains, { it.brainId }, { it.label })
        if (incoming.appSettings != BundleAppSettingsV1()) add(PlannedItem("appSettings", "brainDisplayName", if (existing.appSettings == BundleAppSettingsV1()) PlanAction.ADD else if (incoming.appSettings == existing.appSettings) PlanAction.UNCHANGED else PlanAction.CONFLICT))
    }

    private fun reject(code: String, safeMessage: String): Nothing = throw ConfigImportRejected(code, safeMessage)
}

public data class ImportPlan(
    val bundleId: String,
    val bundleNonce: String,
    val items: List<PlannedItem>,
    val warnings: List<String>,
) {
    val addCount: Int get() = items.count { it.action == PlanAction.ADD }
    val unchangedCount: Int get() = items.count { it.action == PlanAction.UNCHANGED }
    val conflictCount: Int get() = items.count { it.action == PlanAction.CONFLICT }
}
public data class PlannedItem(val category: String, val id: String, val action: PlanAction, val conflictsWith: List<String> = emptyList())
public enum class PlanAction { ADD, UNCHANGED, CONFLICT }
public class ConfigImportRejected(val code: String, message: String) : IllegalArgumentException(message)
