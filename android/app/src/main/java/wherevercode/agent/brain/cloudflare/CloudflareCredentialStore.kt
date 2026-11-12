package wherevercode.agent.brain.cloudflare

import android.content.Context
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.brain.siwc.BrainKeyStoreSecretStore
import wherevercode.agent.brain.siwc.SiwcSecretStore

/**
 * Cloudflare 凭据存储（CloudflareCredentialStore）。
 *
 * 只存一件事：用户自己的 Cloudflare API Token。存储方式与 SIWC 完全一致：
 * M2 的 [BrainKeyStore]（Keystore-backed EncryptedSharedPreferences，
 * 库名 `wherevercode_brain_keys`）。Account ID 不是密钥（它出现在
 * Dashboard 地址栏与 Workers AI 页面上），存在 [BrainConfig] 的
 * `extra["account_id"]` 里，不进本存储。
 *
 * - Token 由用户本人在设备端输入（App 内 Cloudflare 设置页），只存本机；
 *   绝不进仓库、聊天、APK 分发包或平台服务器（自助协议 §2.3 保密责任）。
 * - 本类不做任何网络调用；revoke 只清本地副本——要彻底作废一个 Token，
 *   用户须在 Cloudflare Dashboard → My Profile → API Tokens 里删除它
 *   （删除需要 Token ID，本 App 不存 Token ID，无法代删；见
 *   [CloudflareBrainAdapter.revoke] 的注释）。
 *
 * [SiwcSecretStore] 端口让本类无需 Robolectric 即可单测（与
 * [KeystoreSiwcTokenStore] 同一个端口）：测试传内存 fake，生产用
 * [BrainKeyStoreSecretStore]（见 [create]）。
 */
class KeystoreCloudflareCredentialStore(
    private val secrets: SiwcSecretStore,
) : CloudflareCredentialStore {

    override fun saveToken(token: String) {
        require(token.isNotBlank()) { "Cloudflare API Token 不能为空" }
        secrets.put(AUTH_REF, token)
    }

    override fun loadToken(): String? =
        secrets.get(AUTH_REF)?.takeIf { it.isNotBlank() }

    override fun clearToken() {
        secrets.delete(AUTH_REF)
    }

    companion object {
        /** Cloudflare Token 在 BrainKeyStore 里的固定引用名（见 M2 BrainConfig）。 */
        const val AUTH_REF = "cloudflare:token"

        /** Production wiring: Keystore-backed secrets for the given [Context]. */
        fun create(context: Context): KeystoreCloudflareCredentialStore {
            val appContext = context.applicationContext
            return KeystoreCloudflareCredentialStore(
                BrainKeyStoreSecretStore(BrainKeyStore(appContext)),
            )
        }
    }
}

/** Cloudflare API Token 的本地存储（只存 Token 本身，绝不是 Account ID）。 */
interface CloudflareCredentialStore {
    /** 存下用户输入的 Token（覆盖旧值）。 */
    fun saveToken(token: String)

    /** 读出 Token；没有返回 null（视为未配置）。 */
    fun loadToken(): String?

    /** 清除本地 Token（退出 / 换 Token 时）。 */
    fun clearToken()
}
