package wherevercode.agent.core.transport

import java.util.Base64
import org.json.JSONObject

/**
 * 配对链接（Paseo 式连接，2026-11-17）：Hand 侧 `pair-offer` 生成的
 * 一次性配对凭据的 App 侧解析与校验（纯逻辑，无网络、无 Android 依赖，
 * 可直接单测）。
 *
 * 链接形态（二维码内容与此相同，不另设格式）：
 * `wherevercode://pair?d=<base64url(offer JSON)>`；用户从终端复制时
 * 也可能只粘贴裸 base64url，两种都认。offer JSON 字段见设计文档
 * step6-paseo-connect-design.md §3.1：server_id + Hand 公钥 + 指纹 +
 * 地址提示 + 一次性 secret + 过期时间。
 *
 * 信任根是公钥，不是地址：本对象自算公钥 SHA-256 与 offer 自报的
 * fingerprint 比对，不符即拒；地址（hosts）只是连接提示，真正连上后
 * 还要在 [PairingClient.sendPairOfferConfirm] 里与 hello 回包的公钥
 * 再比对一次。offer 里没有任何 Token/密码；secret 只经密封帧发送
 * （见 PairingClient），绝不写日志。
 */
data class PairingOffer(
    val serverId: String,
    val displayName: String,
    /** Hand 的 X25519 公钥（32 字节）。 */
    val publicKey: ByteArray,
    /** 公钥 SHA-256 小写 hex（已与自算值核对一致）。 */
    val fingerprintSha256: String,
    val port: Int,
    /** 地址提示（局域网/Tailnet/公网），按序尝试 hello。 */
    val hosts: List<String>,
    val offerId: String,
    /** 一次性配对秘密（128 位 hex）。只进密封帧，不进日志。 */
    val secret: String,
    val expiresAtEpochSeconds: Long,
    /** 中继地址（V5 自有中继未就绪前 Hand 恒发 null）；非空也只是提示。 */
    val relayUrl: String? = null,
) {
    /** 按地址提示顺序拼候选对端（指纹用已核对的自算值）。 */
    fun candidatePeers(): List<DiscoveredPeer> = hosts.map { host ->
        DiscoveredPeer(
            deviceId = serverId,
            displayName = displayName,
            host = host,
            port = port,
            fingerprintSha256 = fingerprintSha256,
            capabilities = listOf("hand"),
        )
    }
}

/** offer 解析结果：成功带 offer，失败带中文原因（不猜、不半收）。 */
sealed interface PairingOfferParse {
    data class Ok(val offer: PairingOffer) : PairingOfferParse
    data class Failed(val reason: String) : PairingOfferParse
}

object PairingOfferParser {
    const val LINK_PREFIX = "wherevercode://pair?d="
    const val OFFER_TYPE = "wherevercode-pair"
    const val OFFER_VERSION = 1
    private val SECRET_REGEX = Regex("^[0-9a-fA-F]{32}$")
    private val OFFER_ID_REGEX = Regex("^[0-9a-fA-F]{8,32}$")

    /**
     * 解析扫码/粘贴得到的文本。[nowEpochSeconds] 注入以便单测过期。
     * 校验顺序：形态 → Base64 → JSON → 版本/类型 → 必填字段 →
     * 公钥长度 → 指纹自算比对 → secret 形态 → 未过期。
     */
    fun parse(
        rawText: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): PairingOfferParse {
        val text = rawText.trim()
        if (text.isEmpty()) return PairingOfferParse.Failed("配对链接是空的：请重新扫码或粘贴")
        val encoded = if (text.startsWith(LINK_PREFIX)) {
            text.removePrefix(LINK_PREFIX).trim()
        } else {
            text
        }
        val jsonBytes = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return PairingOfferParse.Failed("这不是有效的配对链接：请确认复制的是 Hand 生成的完整链接")
        }
        val obj = try {
            JSONObject(String(jsonBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            return PairingOfferParse.Failed("配对链接内容损坏：请在目标设备上重新生成配对链接")
        }
        if (obj.optString("type", "") != OFFER_TYPE) {
            return PairingOfferParse.Failed("配对链接类型不对：这不是 WhereverCode 的配对链接")
        }
        if (obj.optInt("v", -1) != OFFER_VERSION) {
            return PairingOfferParse.Failed("配对链接版本不受支持：请升级 App 或目标设备的 Hand")
        }
        val serverId = obj.optString("server_id", "").trim()
        if (serverId.isEmpty()) return PairingOfferParse.Failed("配对链接缺少设备 id：请重新生成")
        val publicKey = try {
            val raw = obj.optString("public_key_b64", "")
            if (raw.isBlank()) return PairingOfferParse.Failed("配对链接缺少设备公钥：请重新生成")
            Base64.getDecoder().decode(raw)
        } catch (e: IllegalArgumentException) {
            return PairingOfferParse.Failed("配对链接里的公钥不是合法编码：请重新生成")
        }
        if (publicKey.size != 32) {
            return PairingOfferParse.Failed("配对链接里的公钥长度不对：请重新生成")
        }
        val claimedFingerprint = obj.optString("fingerprint", "").trim().lowercase()
        val actualFingerprint = Discovery.fingerprintSha256Hex(publicKey)
        if (!Discovery.isValidFingerprintSha256(claimedFingerprint) ||
            !claimedFingerprint.equals(actualFingerprint, ignoreCase = true)
        ) {
            return PairingOfferParse.Failed("配对链接的指纹与公钥对不上（链接可能被篡改）：已拒绝，请重新生成")
        }
        val secret = obj.optString("secret", "").trim()
        if (!SECRET_REGEX.matches(secret)) {
            return PairingOfferParse.Failed("配对链接缺少有效的一次性凭据：请重新生成")
        }
        val offerId = obj.optString("offer_id", "").trim()
        if (!OFFER_ID_REGEX.matches(offerId)) {
            return PairingOfferParse.Failed("配对链接缺少有效的 offer id：请重新生成")
        }
        val expiresAt = obj.optLong("expires_at", 0L)
        if (expiresAt <= 0L) return PairingOfferParse.Failed("配对链接缺少过期时间：请重新生成")
        if (expiresAt < nowEpochSeconds) {
            return PairingOfferParse.Failed("配对链接已过期（10 分钟）：请在目标设备上重新生成配对链接")
        }
        val port = obj.optInt("port", 0)
        if (port !in 1..65535) return PairingOfferParse.Failed("配对链接里的端口不对：请重新生成")
        val hosts = mutableListOf<String>()
        val hostsArray = obj.optJSONArray("hosts")
        if (hostsArray != null) {
            for (i in 0 until hostsArray.length()) {
                val host = hostsArray.optString(i, "").trim()
                if (host.isNotEmpty() && !host.contains("://") && host.none { it.isWhitespace() }) {
                    hosts += host
                }
            }
        }
        val relayUrl = obj.optJSONObject("relay")?.optString("url", "")?.trim()
            ?.takeIf { it.startsWith("wss://") }
        return PairingOfferParse.Ok(
            PairingOffer(
                serverId = serverId,
                displayName = obj.optString("display_name", "").trim().ifBlank { serverId },
                publicKey = publicKey,
                fingerprintSha256 = actualFingerprint,
                port = port,
                hosts = hosts,
                offerId = offerId,
                secret = secret.lowercase(),
                expiresAtEpochSeconds = expiresAt,
                relayUrl = relayUrl,
            ),
        )
    }
}
