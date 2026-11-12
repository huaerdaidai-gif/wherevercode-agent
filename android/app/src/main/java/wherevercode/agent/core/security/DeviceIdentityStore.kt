package wherevercode.agent.core.security

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import wherevercode.agent.core.transport.Discovery

/**
 * 阶段 B（V5 紧急开发）：本机设备身份（X25519 密钥对 + 设备 id）。
 *
 * 配对与密封信封都需要“本机是谁”：[HandClient] 用本机私钥与对方
 * 公钥协商会话密钥；hello 握手向对方出示本机公钥；服务端凭它认出
 * 本机（pair-approve 后才放行密封调用）。
 *
 * 私钥只进 [KeyValueSecrets]（生产实现包一层 BrainKeyStore，即
 * EncryptedSharedPreferences，绝不进 SharedPreferences 明文、
 * 绝不进日志、绝不进代码与报告）。设备 id 与公钥不是秘密，
 * 可显示、可核对。
 */
data class DeviceIdentity(
    val deviceId: String,
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)

/** 最小的字符串密钥存储口：生产用 BrainKeyStore 适配，测试用内存实现。 */
interface KeyValueSecrets {
    fun get(ref: String): String?
    fun put(ref: String, value: String)
}

class DeviceIdentityStore(
    private val secrets: KeyValueSecrets,
    private val newDeviceId: () -> String = { "device-" + UUID.randomUUID().toString().replace("-", "").take(8) },
) {
    companion object {
        const val DEVICE_ID_REF: String = "wherevercode-device-identity:device-id"
        const val PRIVATE_KEY_REF: String = "wherevercode-device-identity:private-key"
    }

    /**
     * 取回已存的身份；没有就生成一对新密钥并存下（只存一次，
     * 之后不再变：换了密钥等于换了设备，旧配对全部作废）。
     */
    fun getOrCreate(): DeviceIdentity {
        val storedId = secrets.get(DEVICE_ID_REF)?.trim().takeIf { !it.isNullOrBlank() }
        val storedPrivate = secrets.get(PRIVATE_KEY_REF)?.trim().takeIf { !it.isNullOrBlank() }
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?.takeIf { it.size == 32 }
        if (storedId != null && storedPrivate != null) {
            return DeviceIdentity(
                deviceId = storedId,
                publicKey = DeviceKeys.publicKeyFromPrivate(storedPrivate),
                privateKey = storedPrivate,
            )
        }
        val keyPair = DeviceKeys.generateKeyPair()
        val deviceId = storedId ?: newDeviceId()
        secrets.put(DEVICE_ID_REF, deviceId)
        secrets.put(PRIVATE_KEY_REF, Base64.getEncoder().encodeToString(keyPair.privateKey))
        return DeviceIdentity(
            deviceId = deviceId,
            publicKey = keyPair.publicKey,
            privateKey = keyPair.privateKey,
        )
    }

    /** 本机公钥的 SHA-256 指纹（小写 hex）：配对时用户核对的值。 */
    fun fingerprintSha256Hex(): String =
        Discovery.fingerprintSha256Hex(getOrCreate().publicKey)

    /** 本机公钥的 Base64（标准编码）：VPS 上 pair-approve 时用的值。 */
    fun publicKeyBase64(): String =
        Base64.getEncoder().encodeToString(getOrCreate().publicKey)
}

/**
 * 阶段 B：任务凭证的签名密钥，由会话密钥派生（两端同算，无需另传秘密）。
 *
 * [HandClient] 发信封时用 [TaskCredentialIssuer] 签发凭证，签名密钥
 * 是构造时传入的 [signingKey]；远端 Hand 服务端验签时需要同一个密钥。
 * 密钥不另行交换：双方都已算出会话密钥（X25519 + HKDF），再用
 * HKDF-SHA256（盐 = SHA-256(info)，info 见 [INFO]）派生出专用的
 * 凭证签名密钥——与会话密钥本身不同（用途分离），与旧平台的任何
 * 密钥无关。Kotlin 侧与 Python 服务端（server/hand_server.py）
 * 必须用同一 info/盐算出同一密钥，否则验签永远失败。
 */
object RemoteCredentialKeys {
    const val INFO: String = "wherevercode-credential-signing/v1"

    fun signingKey(sessionKey: ByteArray): ByteArray {
        require(sessionKey.size == 32) { "cross_device_crypto_bad_session_key" }
        val info = INFO.toByteArray(Charsets.UTF_8)
        val salt = MessageDigest.getInstance("SHA-256").digest(info)
        return DeviceKeys.hkdfSha256(sessionKey, salt, info, 32)
    }
}
