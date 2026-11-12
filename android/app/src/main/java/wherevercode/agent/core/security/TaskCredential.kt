package wherevercode.agent.core.security

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * TaskCredential: task-scoped short-lived credential (stage 2, v2 in M2).
 *
 * Ported from the old project's `cross_device_credentials.py`
 * (`issue()` / `verify()`, blob f5a6d56e, main fd6b43d2), adapted to the
 * stage-2 cross-road contract, then restored to the full binding set
 * per the M1 report §1.2 decision 2 (P1-1 fix, M2).
 *
 * v2 binding (all of it, no more, no less): user_id, task_id, attempt,
 * source_device_id, target_device_id, agent_id, folder, iat, exp, jti,
 * scope (the grant the issue relied on: agent_id + folder), epoch
 * (AuthorizationEpoch snapshot at issue), goal_version (GoalVersion
 * snapshot at issue). The signature is HMAC-SHA256 over the canonical
 * payload with SHA-256(signingKey). The credential itself carries no
 * secret and must still be treated as a bearer token: anyone holding it
 * can spend it until it expires or is revoked, so it travels only over
 * authenticated channels and is never logged.
 *
 * Wire format is v2; [parseTaskCredential] explicitly rejects v1 (no
 * implicit shim, GPT 08:10 U3). Field names follow the M1 report §1.2
 * fixed spec: user_id / task_id / attempt / source_device_id /
 * target_device_id / agent_id / folder / iat / exp / jti / scope /
 * epoch / goal_version.
 *
 * Check order (same as the old verify, extended): signature, expiry,
 * revocation (credential, then task, then host), then the caller's
 * expectations — a forged token is never reported as merely expired,
 * and a revoked token is never reported as mismatched.
 * [CredentialVerifyResult] keeps the old v1 codes for the legacy
 * [TaskCredentialIssuer.verify] path (used by Delegation, unchanged);
 * the v2 path returns [CredentialCheck] with distinct codes for the
 * new bindings (epoch / goal-version / host / task revocation / grant).
 */
data class TaskCredential(
    val taskId: String,
    val credentialId: String,
    val targetDeviceId: String,
    val folder: String,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val signature: ByteArray,
    /** v2: user the credential is bound to (M1 §1.2 `user_id`). */
    val userId: String = "",
    /** v2: task attempt the credential is bound to. */
    val attempt: String = "",
    /** v2: device that issued / forwarded the credential. */
    val sourceDeviceId: String = "",
    /** v2: agent the credential is bound to. */
    val agentId: String = "",
    /** v2: agent_id of the grant the issue relied on. */
    val scopeAgentId: String = "",
    /** v2: folder of the grant the issue relied on. */
    val scopeFolder: String = "",
    /** v2: AuthorizationEpoch snapshot at issue time. */
    val epoch: Long = 1,
    /** v2: GoalVersion snapshot at issue time. */
    val goalVersion: Long = 1,
)

enum class CredentialVerifyResult {
    OK,
    INVALID,
    EXPIRED,
    REVOKED,
    WRONG_TASK,
    WRONG_TARGET,
    WRONG_FOLDER,
}

fun CredentialVerifyResult.messageZh(): String = when (this) {
    CredentialVerifyResult.OK -> "凭证有效"
    CredentialVerifyResult.INVALID -> "凭证无效：凭证格式不正确或签名不匹配"
    CredentialVerifyResult.EXPIRED -> "凭证已过期：请重新发起任务后重试"
    CredentialVerifyResult.REVOKED -> "凭证已被收回：任务已结束或设备授权已被收回"
    CredentialVerifyResult.WRONG_TASK -> "凭证与任务不匹配：该凭证不属于当前任务"
    CredentialVerifyResult.WRONG_TARGET -> "凭证与目标设备不匹配：该凭证不属于当前项目设备"
    CredentialVerifyResult.WRONG_FOLDER -> "凭证与文件夹不匹配：该凭证不属于当前项目文件夹"
}

/** Distinct outcome codes for the v2 verification path. */
enum class CredentialCheck {
    OK,
    INVALID,
    EXPIRED,
    REVOKED,
    TASK_REVOKED,
    HOST_REVOKED,
    WRONG_TASK,
    WRONG_TARGET,
    WRONG_FOLDER,
    WRONG_USER,
    WRONG_ATTEMPT,
    WRONG_SOURCE,
    WRONG_AGENT,
    WRONG_SCOPE,
    GRANT_MISSING,
    STALE_EPOCH,
    STALE_GOAL_VERSION,
}

fun CredentialCheck.messageZh(): String = when (this) {
    CredentialCheck.OK -> "凭证有效"
    CredentialCheck.INVALID -> "凭证无效：凭证格式不正确或签名不匹配"
    CredentialCheck.EXPIRED -> "凭证已过期：请重新发起任务后重试"
    CredentialCheck.REVOKED -> "凭证已被收回：任务已结束或设备授权已被收回"
    CredentialCheck.TASK_REVOKED -> "凭证已被收回：该任务的授权已被收回"
    CredentialCheck.HOST_REVOKED -> "凭证已被收回：该设备的授权已被收回"
    CredentialCheck.WRONG_TASK -> "凭证与任务不匹配：该凭证不属于当前任务"
    CredentialCheck.WRONG_TARGET -> "凭证与目标设备不匹配：该凭证不属于当前项目设备"
    CredentialCheck.WRONG_FOLDER -> "凭证与文件夹不匹配：该凭证不属于当前项目文件夹"
    CredentialCheck.WRONG_USER -> "凭证与用户不匹配：该凭证不属于当前用户"
    CredentialCheck.WRONG_ATTEMPT -> "凭证与任务尝试不匹配：该凭证不属于当前尝试"
    CredentialCheck.WRONG_SOURCE -> "凭证与来源设备不匹配：该凭证不是由当前来源设备签发"
    CredentialCheck.WRONG_AGENT -> "凭证与 Agent 不匹配：该凭证不属于当前 Agent"
    CredentialCheck.WRONG_SCOPE -> "凭证授权范围不匹配：该凭证的授权范围与当前调用不符"
    CredentialCheck.GRANT_MISSING -> "授权已不存在：签发凭证时依据的授权已被收回，请重新授权"
    CredentialCheck.STALE_EPOCH -> "凭证已过期（授权已变更）：授权版本已更新，请重新发起任务"
    CredentialCheck.STALE_GOAL_VERSION -> "凭证已过期（目标已变更）：任务目标已更新，请重新发起任务"
}

/**
 * What a v2 verification expects. Every field is compared against the
 * credential; [targetDeviceId] may be null, in which case the target
 * binding is skipped (the caller does not know its own device id —
 * e.g. the stdio HandServer; the LAN path always passes it).
 * [attempt] must be non-blank: per M1 §1.2, a missing expected attempt
 * is refused, never defaulted.
 */
data class CredentialExpectation(
    val userId: String,
    val taskId: String,
    val attempt: String,
    val sourceDeviceId: String,
    val targetDeviceId: String?,
    val agentId: String,
    val folder: String,
    val epoch: Long,
    val goalVersion: Long,
)

interface RevocationStore {
    fun revoke(credentialId: String)
    fun isRevoked(credentialId: String): Boolean
    /** Revoke every credential issued for [taskId] (M2; checked by verifyV2). */
    fun revokeTask(taskId: String)
    fun isTaskRevoked(taskId: String): Boolean
    /**
     * Revoke a host at [revokedAtEpochSeconds]. Only credentials issued
     * BEFORE that moment are killed; credentials issued after survive
     * (old `revoke_host` time semantics, M1 §1.2).
     */
    fun revokeHost(hostId: String, revokedAtEpochSeconds: Long = System.currentTimeMillis() / 1000)
    fun isHostRevoked(hostId: String, issuedAtEpochSeconds: Long): Boolean
}

class InMemoryRevocationStore : RevocationStore {
    private val revoked = mutableSetOf<String>()
    private val revokedTasks = mutableSetOf<String>()
    private val revokedHosts = mutableMapOf<String, Long>()
    private val lock = Any()

    override fun revoke(credentialId: String) {
        synchronized(lock) { revoked += credentialId }
    }

    override fun isRevoked(credentialId: String): Boolean =
        synchronized(lock) { credentialId in revoked }

    override fun revokeTask(taskId: String) {
        synchronized(lock) { revokedTasks += taskId }
    }

    override fun isTaskRevoked(taskId: String): Boolean =
        synchronized(lock) { taskId in revokedTasks }

    override fun revokeHost(hostId: String, revokedAtEpochSeconds: Long) {
        synchronized(lock) { revokedHosts[hostId] = revokedAtEpochSeconds }
    }

    override fun isHostRevoked(hostId: String, issuedAtEpochSeconds: Long): Boolean =
        synchronized(lock) {
            val revokedAt = revokedHosts[hostId] ?: return false
            issuedAtEpochSeconds < revokedAt
        }
}

object TaskCredentialIssuer {
    const val DEFAULT_TTL_SECONDS: Long = 900L

    /**
     * Full v2 issue: every binding field is explicit. `attempt` comes
     * from the task ledger's current attempt (M1 §1.2); `epoch` /
     * `goalVersion` are snapshots of the current counters at issue time.
     */
    fun issue(
        userId: String,
        taskId: String,
        attempt: String,
        sourceDeviceId: String,
        targetDeviceId: String,
        agentId: String,
        folder: String,
        signingKey: ByteArray,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
        epoch: Long = 1,
        goalVersion: Long = 1,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): TaskCredential {
        require(taskId.isNotBlank()) { "cross_device_credential_bad_task_id" }
        require(targetDeviceId.isNotBlank()) { "cross_device_credential_bad_target" }
        require(folder.isNotBlank()) { "cross_device_credential_bad_folder" }
        require(signingKey.isNotEmpty()) { "cross_device_credential_bad_secret" }
        require(ttlSeconds > 0) { "cross_device_credential_bad_ttl" }
        val credentialId = java.util.UUID.randomUUID().toString()
        val issuedAt = nowEpochSeconds
        val expiresAt = nowEpochSeconds + ttlSeconds
        val credential = TaskCredential(
            taskId = taskId,
            credentialId = credentialId,
            targetDeviceId = targetDeviceId,
            folder = folder,
            issuedAtEpochSeconds = issuedAt,
            expiresAtEpochSeconds = expiresAt,
            signature = ByteArray(0),
            userId = userId,
            attempt = attempt,
            sourceDeviceId = sourceDeviceId,
            agentId = agentId,
            scopeAgentId = agentId,
            scopeFolder = folder,
            epoch = epoch,
            goalVersion = goalVersion,
        )
        return credential.copy(signature = sign(credential, signingKey))
    }

    /**
     * Legacy issue (pre-v2 callers, e.g. Delegation): only the old
     * task/target/folder binding is known. The credential is still a
     * v2 wire credential (signed over the full v2 payload, new fields
     * defaulted); the old [verify] path checks exactly the old binding
     * and nothing more. New callers must use the full [issue] above.
     */
    fun issue(
        taskId: String,
        targetDeviceId: String,
        folder: String,
        signingKey: ByteArray,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): TaskCredential = issue(
        userId = "",
        taskId = taskId,
        attempt = "",
        sourceDeviceId = "",
        targetDeviceId = targetDeviceId,
        agentId = "",
        folder = folder,
        signingKey = signingKey,
        ttlSeconds = ttlSeconds,
        epoch = 1,
        goalVersion = 1,
        nowEpochSeconds = nowEpochSeconds,
    )

    /**
     * Legacy verify (unchanged semantics: signature, expiry, per-
     * credential revocation, task/target/folder binding). Used by
     * Delegation; new code must use [verifyV2].
     */
    fun verify(
        credential: TaskCredential,
        expectedTaskId: String,
        expectedTargetDeviceId: String,
        expectedFolder: String,
        signingKey: ByteArray,
        revocation: RevocationStore,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): CredentialVerifyResult {
        if (credential.taskId.isBlank() || credential.credentialId.isBlank() ||
            credential.targetDeviceId.isBlank() || credential.folder.isBlank() ||
            credential.signature.isEmpty() || signingKey.isEmpty()
        ) {
            return CredentialVerifyResult.INVALID
        }
        val expectedSignature = sign(credential, signingKey)
        if (!constantTimeEquals(expectedSignature, credential.signature)) {
            return CredentialVerifyResult.INVALID
        }
        if (credential.expiresAtEpochSeconds <= nowEpochSeconds) {
            return CredentialVerifyResult.EXPIRED
        }
        if (revocation.isRevoked(credential.credentialId)) {
            return CredentialVerifyResult.REVOKED
        }
        if (credential.taskId != expectedTaskId) return CredentialVerifyResult.WRONG_TASK
        if (credential.targetDeviceId != expectedTargetDeviceId) return CredentialVerifyResult.WRONG_TARGET
        if (canonicalFolderLexical(credential.folder) != canonicalFolderLexical(expectedFolder)) {
            return CredentialVerifyResult.WRONG_FOLDER
        }
        return CredentialVerifyResult.OK
    }

    /**
     * v2 verify: legacy checks first (signature, expiry, revocation —
     * now credential + task + host), then the full binding
     * (user/task/attempt/source/target/agent/folder/scope), then that
     * the grant the issue relied on still exists, then epoch and
     * goal-version against the caller's current counters.
     */
    fun verifyV2(
        credential: TaskCredential,
        expected: CredentialExpectation,
        signingKey: ByteArray,
        revocation: RevocationStore,
        authStore: AuthorizationStore,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): CredentialCheck {
        if (credential.taskId.isBlank() || credential.credentialId.isBlank() ||
            credential.targetDeviceId.isBlank() || credential.folder.isBlank() ||
            credential.signature.isEmpty() || signingKey.isEmpty()
        ) {
            return CredentialCheck.INVALID
        }
        val expectedSignature = sign(credential, signingKey)
        if (!constantTimeEquals(expectedSignature, credential.signature)) {
            return CredentialCheck.INVALID
        }
        if (credential.expiresAtEpochSeconds <= nowEpochSeconds) {
            return CredentialCheck.EXPIRED
        }
        if (revocation.isRevoked(credential.credentialId)) {
            return CredentialCheck.REVOKED
        }
        if (revocation.isTaskRevoked(credential.taskId)) {
            return CredentialCheck.TASK_REVOKED
        }
        if (revocation.isHostRevoked(credential.targetDeviceId, credential.issuedAtEpochSeconds)) {
            return CredentialCheck.HOST_REVOKED
        }
        // Expectations. A missing expected attempt is refused (M1 §1.2).
        if (expected.attempt.isBlank()) return CredentialCheck.WRONG_ATTEMPT
        if (credential.userId != expected.userId) return CredentialCheck.WRONG_USER
        if (credential.taskId != expected.taskId) return CredentialCheck.WRONG_TASK
        if (credential.attempt != expected.attempt) return CredentialCheck.WRONG_ATTEMPT
        if (credential.sourceDeviceId != expected.sourceDeviceId) return CredentialCheck.WRONG_SOURCE
        if (expected.targetDeviceId != null && credential.targetDeviceId != expected.targetDeviceId) {
            return CredentialCheck.WRONG_TARGET
        }
        if (credential.agentId != expected.agentId) return CredentialCheck.WRONG_AGENT
        if (canonicalFolderLexical(credential.folder) != canonicalFolderLexical(expected.folder)) {
            return CredentialCheck.WRONG_FOLDER
        }
        if (credential.scopeAgentId != expected.agentId ||
            canonicalFolderLexical(credential.scopeFolder) != canonicalFolderLexical(expected.folder)
        ) {
            return CredentialCheck.WRONG_SCOPE
        }
        // The grant the issue relied on must still exist (issue-time
        // presence is not enough).
        if (!authStore.isAuthorized(credential.scopeAgentId, credential.scopeFolder)) {
            return CredentialCheck.GRANT_MISSING
        }
        if (credential.epoch != expected.epoch) return CredentialCheck.STALE_EPOCH
        if (credential.goalVersion != expected.goalVersion) return CredentialCheck.STALE_GOAL_VERSION
        return CredentialCheck.OK
    }

    /** Canonical payload (v2): all bound fields joined in fixed order (the signed bytes). */
    fun canonicalPayload(credential: TaskCredential): ByteArray =
        listOf(
            credential.userId,
            credential.taskId,
            credential.attempt,
            credential.sourceDeviceId,
            credential.targetDeviceId,
            credential.agentId,
            credential.folder,
            credential.issuedAtEpochSeconds.toString(),
            credential.expiresAtEpochSeconds.toString(),
            credential.credentialId,
            credential.scopeAgentId,
            credential.scopeFolder,
            credential.epoch.toString(),
            credential.goalVersion.toString(),
        ).joinToString("|").toByteArray(Charsets.UTF_8)

    /** Legacy canonical payload (v1 field set); kept for reference only — v2 signing uses [canonicalPayload]. */
    fun canonicalPayload(
        taskId: String,
        credentialId: String,
        targetDeviceId: String,
        folder: String,
        issuedAtEpochSeconds: Long,
        expiresAtEpochSeconds: Long,
    ): ByteArray =
        listOf(taskId, credentialId, targetDeviceId, folder, issuedAtEpochSeconds.toString(), expiresAtEpochSeconds.toString())
            .joinToString("|").toByteArray(Charsets.UTF_8)

    private fun sign(credential: TaskCredential, signingKey: ByteArray): ByteArray {
        val key = sha256(signingKey)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(canonicalPayload(credential))
    }

    private fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        java.security.MessageDigest.isEqual(a, b)

    /** Lexical folder identity: collapse `.` / `..` / duplicate slashes. No filesystem access. */
    fun canonicalFolderLexical(folder: String): String {
        val parts = folder.replace('\\', '/').split("/").filter { it.isNotEmpty() && it != "." }
        val out = ArrayDeque<String>()
        for (part in parts) {
            if (part == "..") {
                if (out.isNotEmpty()) out.removeLast()
            } else {
                out.addLast(part)
            }
        }
        val prefix = if (folder.startsWith("/")) "/" else ""
        return prefix + out.joinToString("/")
    }
}

/** Wire form (v2): Base64 of a small JSON object (no secret inside, but still a bearer token — never log it). */
fun TaskCredential.toWire(): String {
    val obj = JSONObject()
    obj.put("v", 2)
    obj.put("user_id", userId)
    obj.put("task_id", taskId)
    obj.put("attempt", attempt)
    obj.put("source_device_id", sourceDeviceId)
    obj.put("target_device_id", targetDeviceId)
    obj.put("agent_id", agentId)
    obj.put("folder", folder)
    obj.put("iat", issuedAtEpochSeconds)
    obj.put("exp", expiresAtEpochSeconds)
    obj.put("jti", credentialId)
    val scope = JSONObject()
    scope.put("agent_id", scopeAgentId)
    scope.put("folder", scopeFolder)
    obj.put("scope", scope)
    obj.put("epoch", epoch)
    obj.put("goal_version", goalVersion)
    obj.put("sig_b64", Base64.getEncoder().withoutPadding().encodeToString(signature))
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(obj.toString().toByteArray(Charsets.UTF_8))
}

/** URL-safe Base64 decode that tolerates the missing padding our encoder omits. */
private fun b64UrlDecode(text: String): ByteArray {
    val padded = text + "=".repeat((4 - text.length % 4) % 4)
    return Base64.getUrlDecoder().decode(padded)
}

private fun b64Decode(text: String): ByteArray {
    val padded = text + "=".repeat((4 - text.length % 4) % 4)
    return Base64.getDecoder().decode(padded)
}

/**
 * Parse a v2 wire credential. v1 (or any other version, or a missing
 * version field) is explicitly rejected and returns null — no shim.
 */
fun parseTaskCredential(wire: String): TaskCredential? {
    if (wire.isBlank()) return null
    return try {
        val jsonBytes = b64UrlDecode(wire)
        val obj = JSONObject(String(jsonBytes, Charsets.UTF_8))
        if (obj.optInt("v", -1) != 2) return null
        val signature = b64Decode(obj.optString("sig_b64", ""))
        val scope = obj.optJSONObject("scope")
        TaskCredential(
            taskId = obj.optString("task_id", ""),
            credentialId = obj.optString("jti", ""),
            targetDeviceId = obj.optString("target_device_id", ""),
            folder = obj.optString("folder", ""),
            issuedAtEpochSeconds = obj.optLong("iat", 0),
            expiresAtEpochSeconds = obj.optLong("exp", 0),
            signature = signature,
            userId = obj.optString("user_id", ""),
            attempt = obj.optString("attempt", ""),
            sourceDeviceId = obj.optString("source_device_id", ""),
            agentId = obj.optString("agent_id", ""),
            scopeAgentId = scope?.optString("agent_id", "") ?: "",
            scopeFolder = scope?.optString("folder", "") ?: "",
            epoch = obj.optLong("epoch", 1),
            goalVersion = obj.optLong("goal_version", 1),
        ).takeIf {
            it.taskId.isNotEmpty() && it.credentialId.isNotEmpty() &&
                it.targetDeviceId.isNotEmpty() && it.folder.isNotEmpty() &&
                it.signature.isNotEmpty() && it.expiresAtEpochSeconds > 0
        }
    } catch (_: Exception) {
        null
    }
}
