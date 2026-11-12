package wherevercode.agent.brain.deepseek

import android.content.Context
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.brain.siwc.BrainKeyStoreSecretStore
import wherevercode.agent.brain.siwc.SiwcSecretStore

/**
 * DeepSeek API key storage.
 *
 * The key is entered by the user on the device and stored only in the same
 * Keystore-backed [BrainKeyStore] used by the other brains (SV
 * `wherevercode_brain_keys`). It is never in `BrainConfig`, never in logs,
 * never in an APK, and never in the repository. The base URL and model are
 * plain configuration, not secrets.
 *
 * [SiwcSecretStore] keeps this class JVM-testable without Robolectric; the
 * production factory wraps [BrainKeyStore].
 */
class KeystoreDeepSeekCredentialStore(
    private val secrets: SiwcSecretStore,
) : DeepSeekCredentialStore {

    override fun saveApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "DeepSeek API Key 不能为空" }
        secrets.put(AUTH_REF, apiKey)
    }

    override fun loadApiKey(): String? =
        secrets.get(AUTH_REF)?.takeIf { it.isNotBlank() }

    override fun clearApiKey() {
        secrets.delete(AUTH_REF)
    }

    companion object {
        const val AUTH_REF: String = "deepseek:api-key"

        fun create(context: Context): KeystoreDeepSeekCredentialStore {
            val appContext = context.applicationContext
            return KeystoreDeepSeekCredentialStore(
                BrainKeyStoreSecretStore(BrainKeyStore(appContext)),
            )
        }
    }
}

interface DeepSeekCredentialStore {
    fun saveApiKey(apiKey: String)
    fun loadApiKey(): String?
    fun clearApiKey()
}
