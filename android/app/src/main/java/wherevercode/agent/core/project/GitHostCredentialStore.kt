package wherevercode.agent.core.project

import android.content.Context
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.brain.siwc.BrainKeyStoreSecretStore
import wherevercode.agent.brain.siwc.SiwcSecretStore

/**
 * git 托管站点授权（token）存储：每个 host 各自一条。
 *
 * token 由用户在 App 里自己填写，存在本机 Keystore 支撑的
 * [BrainKeyStore]（与各大脑同一套，SV `wherevercode_brain_keys`）。
 * 绝不进聊天、绝不进日志、绝不进 APK、绝不进仓库。
 * 授权只用于调该站 API（读/写/推分支/开 PR 或合并请求），不作他用。
 * 绝不发给 Hand：Hand 设备上的 Agent 不需要手机的 token。
 *
 * GitHub 沿用旧键 `github:token`（已配的用户升级后不用重填）；
 * GitLab、Gitee 各一条新键。
 *
 * [SiwcSecretStore] 让本类不依赖 Robolectric 即可做 JVM 单测；
 * 生产工厂包 [BrainKeyStore]。
 */
class KeystoreGitHostCredentialStore(
    private val secrets: SiwcSecretStore,
) : GitHostCredentialStore {

    override fun saveToken(host: GitHost, token: String) {
        require(token.isNotBlank()) { "${host.displayName} 授权不能为空" }
        secrets.put(host.tokenRef, token)
    }

    override fun loadToken(host: GitHost): String? =
        secrets.get(host.tokenRef)?.takeIf { it.isNotBlank() }

    override fun clearToken(host: GitHost) {
        secrets.delete(host.tokenRef)
    }

    override fun hasToken(host: GitHost): Boolean = loadToken(host) != null

    companion object {
        fun create(context: Context): KeystoreGitHostCredentialStore {
            val appContext = context.applicationContext
            return KeystoreGitHostCredentialStore(
                BrainKeyStoreSecretStore(BrainKeyStore(appContext)),
            )
        }
    }
}

interface GitHostCredentialStore {
    fun saveToken(host: GitHost, token: String)
    fun loadToken(host: GitHost): String?
    fun clearToken(host: GitHost)
    fun hasToken(host: GitHost): Boolean = loadToken(host) != null
}
