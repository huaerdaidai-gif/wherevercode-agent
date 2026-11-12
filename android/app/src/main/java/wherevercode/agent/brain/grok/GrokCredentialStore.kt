package wherevercode.agent.brain.grok

import android.content.Context
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.brain.siwc.BrainKeyStoreSecretStore
import wherevercode.agent.brain.siwc.SiwcSecretStore

/**
 * Grok (xAI API) key storage.
 *
 * The key is the user's own xAI API key (starts with `xai-`, created
 * at console.x.ai), entered by the user on the phone and stored only
 * in the same Keystore-backed [BrainKeyStore] used by the other
 * brains (SV `wherevercode_brain_keys`). It is never in
 * `BrainConfig`, never in logs, never in an APK, and never in the
 * repository. The base URL and model are plain configuration, not
 * secrets. The key is never sent to Muse (the assistant) or to any
 * other party — it only ever travels in the Authorization header of
 * the user's own calls to `api.x.ai`.
 *
 * [SiwcSecretStore] keeps this class JVM-testable without Robolectric;
 * the production factory wraps [BrainKeyStore].
 */
class KeystoreGrokCredentialStore(
    private val secrets: SiwcSecretStore,
) : GrokCredentialStore {

    override fun saveApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "Grok API Key 不能为空" }
        secrets.put(AUTH_REF, apiKey)
    }

    override fun loadApiKey(): String? =
        secrets.get(AUTH_REF)?.takeIf { it.isNotBlank() }

    override fun clearApiKey() {
        secrets.delete(AUTH_REF)
    }

    companion object {
        const val AUTH_REF: String = "grok:api-key"

        fun create(context: Context): KeystoreGrokCredentialStore {
            val appContext = context.applicationContext
            return KeystoreGrokCredentialStore(
                BrainKeyStoreSecretStore(BrainKeyStore(appContext)),
            )
        }
    }
}

interface GrokCredentialStore {
    fun saveApiKey(apiKey: String)
    fun loadApiKey(): String?
    fun clearApiKey()
}
