package wherevercode.agent.brain.generic

import android.content.Context
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.brain.siwc.BrainKeyStoreSecretStore
import wherevercode.agent.brain.siwc.SiwcSecretStore

/**
 * Generic OpenAI-compatible API key storage.
 *
 * One store serves every user-created generic brain: the key is looked up
 * by its authRef (`model-api:<brainId>`, the same reference name
 * `saveGenericBrain` writes via [BrainKeyStore.putSecret] and
 * [wherevercode.agent.ui.BrainLoginRows.genericAuthRef] builds — the
 * three spellings must stay identical, or a saved key becomes
 * unreachable). The key is entered by the user on the device and stored
 * only in the same Keystore-backed [BrainKeyStore] used by the other
 * brains. It is never in `BrainConfig`, never in logs, never in an APK,
 * and never in the repository. The base URL and model are plain
 * configuration, not secrets.
 *
 * [SiwcSecretStore] keeps this class JVM-testable without Robolectric; the
 * production factory wraps [BrainKeyStore].
 */
class KeystoreGenericOpenAiCredentialStore(
    private val secrets: SiwcSecretStore,
) : GenericOpenAiCredentialStore {

    override fun saveApiKey(authRef: String, apiKey: String) {
        require(authRef.startsWith(AUTH_REF_PREFIX)) { "通用模型 API 的密钥引用名必须以 model-api: 开头" }
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        secrets.put(authRef, apiKey)
    }

    override fun loadApiKey(authRef: String): String? {
        // A foreign or blank ref is "no key", never a crash: the adapter
        // reports not_configured and the settings row stays unconfigured.
        if (!authRef.startsWith(AUTH_REF_PREFIX)) return null
        return secrets.get(authRef)?.takeIf { it.isNotBlank() }
    }

    override fun clearApiKey(authRef: String) {
        if (authRef.startsWith(AUTH_REF_PREFIX)) secrets.delete(authRef)
    }

    companion object {
        /** All generic-brain key refs share this prefix; see the class doc. */
        const val AUTH_REF_PREFIX: String = "model-api:"

        /** The Keystore reference name for one generic brain's key. */
        fun authRefFor(brainId: String): String = "model-api:$brainId"

        fun create(context: Context): KeystoreGenericOpenAiCredentialStore {
            val appContext = context.applicationContext
            return KeystoreGenericOpenAiCredentialStore(
                BrainKeyStoreSecretStore(BrainKeyStore(appContext)),
            )
        }
    }
}

interface GenericOpenAiCredentialStore {
    fun saveApiKey(authRef: String, apiKey: String)
    fun loadApiKey(authRef: String): String?
    fun clearApiKey(authRef: String)
}
