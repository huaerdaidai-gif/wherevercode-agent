package wherevercode.agent.core.transport

import java.security.MessageDigest

/**
 * mDNS 发现的纯逻辑（阶段 2 B 路）。
 *
 * 本文件不依赖 [android.net.nsd.NsdManager] 实例：解析 TXT 记录、
 * 校验指纹格式、拼 [DiscoveredPeer] 都是纯函数，可直接单元测试。
 * [LanTransport] 只负责调 NsdManager 拿到原始服务信息，再调这里拼装。
 *
 * 服务类型：`_wherevercode._tcp`（局域网直连，审计 §6.2 候选 B）。
 *
 * TXT 记录约定（只含设备 id 与能力，不含密钥与任何敏感信息）：
 * - `id`：设备 id（必填）。
 * - `fp`：对端 TLS 身份公钥的 SHA-256 指纹，小写 hex 64 位（必填）。
 *   指纹是公钥的摘要、可公开显示，供用户在配对时核对；不是密钥本身，
 *   私钥、会话密钥一律不进 TXT。
 * - `caps`：能力清单，逗号分隔，如 `hand,relay`（可空）。
 * 地址（host/port）由 mDNS 解析得到，不进 TXT。
 */
object Discovery {

    const val SERVICE_TYPE = "_wherevercode._tcp"
    const val TXT_DEVICE_ID = "id"
    const val TXT_FINGERPRINT = "fp"
    const val TXT_CAPABILITIES = "caps"

    /** TXT 解析后的结果。解析失败返回 null（调用方直接丢弃该服务）。 */
    data class ParsedTxtRecord(
        val deviceId: String,
        val fingerprintSha256: String,
        val capabilities: List<String>,
    )

    private val HEX_64_REGEX = Regex("^[0-9a-fA-F]{64}$")

    /** 指纹格式校验：64 位十六进制（大小写均可，存时统一转小写）。 */
    fun isValidFingerprintSha256(value: String): Boolean =
        HEX_64_REGEX.matches(value)

    /** 公钥字节 → SHA-256 指纹（小写 hex）。 */
    fun fingerprintSha256Hex(publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * pinning 校验的纯函数：对方出示的公钥摘要是否等于配对时记下的指纹。
     * [LanTransport] 的 TrustManager 与 HostnameVerifier 都调它；
     * 比较时忽略大小写，空值一律不通过。
     */
    fun verifyPinnedFingerprint(
        presentedPublicKey: ByteArray,
        expectedFingerprintSha256: String,
    ): Boolean {
        if (!isValidFingerprintSha256(expectedFingerprintSha256)) return false
        return fingerprintSha256Hex(presentedPublicKey)
            .equals(expectedFingerprintSha256, ignoreCase = true)
    }

    /** 解析 NsdManager 给的原始 TXT 属性（值是字节数组）。 */
    fun parseTxtRecord(attributes: Map<String, ByteArray>): ParsedTxtRecord? {
        val strings = attributes.mapValues { (_, v) ->
            String(v, Charsets.UTF_8).trim()
        }
        return parseTxtRecordStrings(strings)
    }

    /** 解析已解码为字符串的 TXT 属性。 */
    fun parseTxtRecordStrings(attributes: Map<String, String>): ParsedTxtRecord? {
        val deviceId = attributes[TXT_DEVICE_ID]?.trim().orEmpty()
        if (deviceId.isEmpty()) return null
        val fingerprint = attributes[TXT_FINGERPRINT]?.trim().orEmpty()
        if (!isValidFingerprintSha256(fingerprint)) return null
        val capabilities = attributes[TXT_CAPABILITIES]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        return ParsedTxtRecord(
            deviceId = deviceId,
            fingerprintSha256 = fingerprint.lowercase(),
            capabilities = capabilities,
        )
    }

    /**
     * 把 mDNS 解析结果拼成 [DiscoveredPeer]。
     * host 为空、端口不在 1..65535 时返回 null（丢弃该服务）。
     */
    fun toDiscoveredPeer(
        serviceName: String,
        host: String,
        port: Int,
        txt: ParsedTxtRecord,
    ): DiscoveredPeer? {
        if (host.isBlank()) return null
        if (port !in 1..65535) return null
        return DiscoveredPeer(
            deviceId = txt.deviceId,
            displayName = serviceName,
            host = host.trim(),
            port = port,
            fingerprintSha256 = txt.fingerprintSha256,
            capabilities = txt.capabilities,
        )
    }
}
