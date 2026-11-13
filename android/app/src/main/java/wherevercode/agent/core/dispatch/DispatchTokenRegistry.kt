package wherevercode.agent.core.dispatch

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 签发结果：[token] 明文只在这里出现一次，调用方显示/交付后即丢，绝不持久化。 */
data class IssuedDispatchToken(
    val token: String,
    val tokenId: String,
    val principal: DispatchPrincipal,
)

/**
 * 派工令牌：`wcd_` + 43 位 base64url（32 字节随机）。只存 SHA-256
 * 哈希（与平台 `v5_` 令牌同一口径）；校验时对所有记录逐一做
 * [MessageDigest.isEqual] 常量时间比较，不因命中提前返回。
 * 令牌只在内存里交给调用方一次，日志 / 审计 / 错误信息里一律不带。
 */
class DispatchTokenRegistry(
    private val state: DispatchStateStore,
    private val random: SecureRandom = SecureRandom(),
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun issue(
        principalId: String,
        role: DispatchRole,
        capabilities: Set<DispatchCapability>,
        label: String = "",
        projectIds: Set<String> = emptySet(),
    ): IssuedDispatchToken {
        require(DispatchPrincipal.isValidId(principalId)) { "principal id 只能用字母、数字、点、横杠和下划线（1–64 位）" }
        val allowed = DispatchPrincipal.allowedCapabilities(role)
        val effective = if (role == DispatchRole.OWNER) allowed else capabilities
        require(effective.all { it in allowed }) { "角色 $role 不能拿 ${effective - allowed} 能力" }
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val token = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val idBytes = ByteArray(8).also { random.nextBytes(it) }
        val tokenId = "dt_" + idBytes.joinToString("") { "%02x".format(it) }
        state.putToken(
            DispatchTokenRecord(
                tokenId = tokenId,
                principalId = principalId,
                role = role,
                capabilities = effective,
                hashHex = sha256Hex(token),
                label = label.take(100),
                createdAt = nowEpochSeconds(),
                projectIds = projectIds.toSet(),
            ),
        )
        return IssuedDispatchToken(token, tokenId, DispatchPrincipal(principalId, role, effective, projectIds.toSet()))
    }

    /** 验令牌；不合格式、不存在、已吊销一律返回 null（不区分原因，不回显令牌）。 */
    fun authenticate(presented: String?): DispatchPrincipal? {
        if (presented == null) return null
        if (!presented.startsWith(TOKEN_PREFIX) || presented.length !in 20..200) return null
        val presentedHash = sha256(presented)
        var match: DispatchTokenRecord? = null
        for (record in state.tokens()) {
            val stored = hexToBytes(record.hashHex)
            // 常量时间比较，且不提前 break：耗时与命中位置无关。
            if (MessageDigest.isEqual(stored, presentedHash) && !record.revoked && match == null) {
                match = record
            }
        }
        val rec = match ?: return null
        return DispatchPrincipal(rec.principalId, rec.role, rec.capabilities, rec.projectIds)
    }

    fun revoke(tokenId: String): Boolean {
        val rec = state.tokens().firstOrNull { it.tokenId == tokenId } ?: return false
        state.putToken(rec.copy(revoked = true))
        return true
    }

    fun revokePrincipal(principalId: String): Int {
        var n = 0
        state.tokens().filter { it.principalId == principalId && !it.revoked }.forEach {
            state.putToken(it.copy(revoked = true)); n++
        }
        return n
    }

    /** Revoke every active credential, optionally limited to one role. */
    fun revokeAll(role: DispatchRole? = null): Int {
        val active = state.tokens().filter { !it.revoked && (role == null || it.role == role) }
        active.forEach { state.putToken(it.copy(revoked = true)) }
        return active.size
    }

    /** An assignment requires an active execution credential scoped to the task's project. */
    fun canonicalExecutorId(principalId: String, projectId: String?): String? =
        state.tokens().firstOrNull {
            it.principalId.equals(principalId, ignoreCase = true) && !it.revoked &&
                DispatchCapability.EXECUTE in it.capabilities &&
                (it.role == DispatchRole.OWNER || projectId == null || projectId in it.projectIds)
        }?.principalId

    companion object {
        const val TOKEN_PREFIX: String = "wcd_"

        fun sha256(text: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

        fun sha256Hex(text: String): String = sha256(text).joinToString("") { "%02x".format(it) }

        private fun hexToBytes(hex: String): ByteArray =
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
