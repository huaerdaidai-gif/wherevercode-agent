package wherevercode.agent.core.agent

import android.content.Context
import wherevercode.agent.brain.BrainKeyStore
import java.security.SecureRandom
import java.util.Base64

/**
 * 密钥值存储口（Keystore 包装的最小接口；单测用内存假实现）。
 * 键永远是引用名，值永远不进日志、不进记录、不进返回值摘要。
 */
interface AgentSecretStore {
    fun put(ref: String, value: String)
    fun get(ref: String): String?
    fun delete(ref: String)
    fun contains(ref: String): Boolean
}

/** [AgentSecretStore] 的 [BrainKeyStore]（EncryptedSharedPreferences）实现。 */
class BrainKeyStoreAgentSecretStore(
    private val keyStore: BrainKeyStore,
) : AgentSecretStore {
    override fun put(ref: String, value: String) = keyStore.putSecret(ref, value)
    override fun get(ref: String): String? = keyStore.getSecret(ref)
    override fun delete(ref: String) = keyStore.deleteSecret(ref)
    override fun contains(ref: String): Boolean = keyStore.hasSecret(ref)
}

/**
 * 平台 Agent 的凭证存储（V5，2026-11-11）。
 *
 * 两类值，全走本机 Keystore（与各大脑同一套 [BrainKeyStore]）：
 * - App 的平台凭证：App 在用户配置的平台服务上注册的 Agent 的
 *   Bearer 令牌，固定引用名 [PLATFORM_TOKEN_REF]。它是 App 自己的
 *   平台身份，所有平台 Agent 共用这一份——平台凭证买的是“App 能
 *   在平台上发消息”，不买模型额度、不借会员。
 * - webhook 签名密钥：每平台 Agent 一个，引用名
 *   `"platform-agent:<agentId>:webhook-secret"`（见
 *   [webhookSecretRefFor]），值只在表单里由用户填、只供“发一条
 *   测试通知”签名用。
 *
 * 凭证红线：这里只做存取，绝不产生、绝不打印、绝不把任何凭证值
 * 写进代码、测试或任何文件；令牌由 maintainer 自己在网站上创建、在
 * App 里填。
 */
class PlatformAgentCredentialStore(
    private val secrets: AgentSecretStore,
) {
    fun savePlatformToken(token: String) {
        require(token.isNotBlank()) { "平台 Agent 凭证不能为空" }
        secrets.put(PLATFORM_TOKEN_REF, token)
    }

    fun loadPlatformToken(): String? = secrets.get(PLATFORM_TOKEN_REF)?.takeIf { it.isNotBlank() }

    fun clearPlatformToken() {
        secrets.delete(PLATFORM_TOKEN_REF)
    }

    fun saveWebhookSecret(agentId: String, secret: String) {
        require(secret.isNotBlank()) { "webhook 签名密钥不能为空" }
        secrets.put(webhookSecretRefFor(agentId), secret)
    }

    fun loadWebhookSecret(agentId: String): String? =
        secrets.get(webhookSecretRefFor(agentId))?.takeIf { it.isNotBlank() }

    fun clearWebhookSecret(agentId: String) {
        secrets.delete(webhookSecretRefFor(agentId))
    }

    companion object {
        /** App 平台凭证的固定引用名（记录里不存它，见 AgentRecord.validate）。 */
        const val PLATFORM_TOKEN_REF: String = "platform-agent:platform-token"

        /** 某平台 Agent 的 webhook 签名密钥引用名。 */
        fun webhookSecretRefFor(agentId: String): String =
            "platform-agent:$agentId:webhook-secret"

        fun create(context: Context): PlatformAgentCredentialStore =
            PlatformAgentCredentialStore(
                BrainKeyStoreAgentSecretStore(BrainKeyStore(context.applicationContext)),
            )
    }
}

/**
 * 云端 Agent 的凭证存储（V5，2026-11-11）。
 *
 * 每云端 Agent 一个随机 Bearer 钥匙，保存时现生成（见
 * [generateKey]）、只存本机 Keystore，引用名
 * `"cloud-agent:<agentId>:mcp-token"`（见 [keyRefFor]），记录里
 * 只存引用名（[AgentRecord.authRef]）。钥匙展示在设置说明里，让
 * 用户去对方 App 的自定义连接器里填——展示是功能本身（用户自己
 * 的钥匙、自己复制），但钥匙值绝不进日志、记录、代码与任何文件。
 */
class CloudAgentCredentialStore(
    private val secrets: AgentSecretStore,
) {
    /** 保存一把钥匙（调用方传入已生成的值；生成见 [generateKey]）。 */
    fun saveKey(agentId: String, key: String) {
        require(key.isNotBlank()) { "云端 Agent 钥匙不能为空" }
        secrets.put(keyRefFor(agentId), key)
    }

    fun loadKey(agentId: String): String? =
        secrets.get(keyRefFor(agentId))?.takeIf { it.isNotBlank() }

    fun clearKey(agentId: String) {
        secrets.delete(keyRefFor(agentId))
    }

    companion object {
        /** 钥匙前缀：与平台 MCP 守卫（McpGuard）认的令牌形状一致。 */
        const val KEY_PREFIX: String = "wco_"

        /** 某云端 Agent 的钥匙引用名。 */
        fun keyRefFor(agentId: String): String = "cloud-agent:$agentId:mcp-token"

        /**
         * 生成一把随机 Bearer 钥匙：`wco_` + 32 字节随机数（base64url，
         * 无填充）。只在保存时调用一次；调用方须立即存进 Keystore，
         * 不得把它写进日志或文件。
         */
        fun generateKey(random: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return KEY_PREFIX + encoded
        }

        fun create(context: Context): CloudAgentCredentialStore =
            CloudAgentCredentialStore(
                BrainKeyStoreAgentSecretStore(BrainKeyStore(context.applicationContext)),
            )
    }
}
