package wherevercode.agent.brain.muse

import android.content.Context
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.brain.siwc.BrainKeyStoreSecretStore
import wherevercode.agent.brain.siwc.SiwcSecretStore

/**
 * Muse (Meta Model API) key storage.
 *
 * The key is the user's own Meta Model API key, entered by the user on
 * the phone and stored only in the same Keystore-backed [BrainKeyStore]
 * used by the other brains (SV `wherevercode_brain_keys`). It is never
 * in `BrainConfig`, never in logs, never in an APK, and never in the
 * repository. The base URL and model are plain configuration, not
 * secrets. The key is never sent to Muse (the assistant) or to any
 * other party — it only ever travels in the Authorization header of
 * the user's own calls to `api.meta.ai`.
 *
 * [SiwcSecretStore] keeps this class JVM-testable without Robolectric;
 * the production factory wraps [BrainKeyStore].
 */
class KeystoreMuseCredentialStore(
    private val secrets: SiwcSecretStore,
) : MuseCredentialStore {

    override fun saveApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "Muse API Key 不能为空" }
        secrets.put(AUTH_REF, apiKey)
    }

    override fun loadApiKey(): String? =
        secrets.get(AUTH_REF)?.takeIf { it.isNotBlank() }

    override fun clearApiKey() {
        secrets.delete(AUTH_REF)
    }

    companion object {
        const val AUTH_REF: String = "muse:api-key"

        fun create(context: Context): KeystoreMuseCredentialStore {
            val appContext = context.applicationContext
            return KeystoreMuseCredentialStore(
                BrainKeyStoreSecretStore(BrainKeyStore(appContext)),
            )
        }
    }
}

interface MuseCredentialStore {
    fun saveApiKey(apiKey: String)
    fun loadApiKey(): String?
    fun clearApiKey()
}
