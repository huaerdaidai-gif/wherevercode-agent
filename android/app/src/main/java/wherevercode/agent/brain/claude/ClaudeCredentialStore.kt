package wherevercode.agent.brain.claude

import android.content.Context
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.brain.siwc.BrainKeyStoreSecretStore
import wherevercode.agent.brain.siwc.SiwcSecretStore

/**
 * Claude API key storage.
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
class KeystoreClaudeCredentialStore(
    private val secrets: SiwcSecretStore,
) : ClaudeCredentialStore {

    override fun saveApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "Claude API Key 不能为空" }
        secrets.put(AUTH_REF, apiKey)
    }

    override fun loadApiKey(): String? =
        secrets.get(AUTH_REF)?.takeIf { it.isNotBlank() }

    override fun clearApiKey() {
        secrets.delete(AUTH_REF)
    }

    companion object {
        const val AUTH_REF: String = "claude:api-key"

        fun create(context: Context): KeystoreClaudeCredentialStore {
            val appContext = context.applicationContext
            return KeystoreClaudeCredentialStore(
                BrainKeyStoreSecretStore(BrainKeyStore(appContext)),
            )
        }
    }
}

interface ClaudeCredentialStore {
    fun saveApiKey(apiKey: String)
    fun loadApiKey(): String?
    fun clearApiKey()
}
