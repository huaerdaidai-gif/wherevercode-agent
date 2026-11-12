package wherevercode.agent.brain.devicecode

import android.content.Context
import org.json.JSONObject
import wherevercode.agent.brain.BrainKeyStore
import wherevercode.agent.brain.siwc.BrainKeyStoreSecretStore
import wherevercode.agent.brain.siwc.SiwcSecretStore

/**
 * Keystore-backed storage for one vendor's device-code tokens
 * (2026-11-12, membership add-all).
 *
 * Tokens live ONLY here: one JSON blob per vendor under the
 * vendor's authRef (`"devicecode:<vendorId>"`) in the same
 * Keystore-backed EncryptedSharedPreferences as every other brain
 * credential ([BrainKeyStore]). The config holds only the authRef,
 * never a token value; deletion ([clearTokens], called by the
 * brain-delete path) wipes the blob, so deleting the brain deletes
 * the login with it — no residue, no resurrection.
 *
 * The store is a thin JSON wrapper over a [SiwcSecretStore] port so
 * tests can substitute an in-memory fake with zero Android crypto.
 */
class DeviceCodeTokenStore(
    private val secrets: SiwcSecretStore,
    private val authRef: String,
) {
    fun saveTokens(tokens: DeviceCodeTokens) {
        secrets.put(authRef, toJson(tokens))
    }

    fun loadTokens(): DeviceCodeTokens? {
        val raw = secrets.get(authRef) ?: return null
        return fromJson(raw)
    }

    fun hasTokens(): Boolean = loadTokens() != null

    fun clearTokens() {
        secrets.delete(authRef)
    }

    private fun toJson(tokens: DeviceCodeTokens): String {
        val obj = JSONObject()
        obj.put("access_token", tokens.accessToken)
        obj.put("refresh_token", tokens.refreshToken)
        obj.put("expires_in", tokens.expiresInSeconds)
        obj.put("scope", tokens.scope)
        obj.put("obtained_at_millis", tokens.obtainedAtMillis)
        return obj.toString()
    }

    private fun fromJson(raw: String): DeviceCodeTokens? {
        return try {
            val obj = JSONObject(raw)
            val accessToken = obj.optString("access_token", "")
            if (accessToken.isBlank()) return null
            DeviceCodeTokens(
                accessToken = accessToken,
                refreshToken = obj.optString("refresh_token", "").ifBlank { null },
                expiresInSeconds = obj.optLong("expires_in", 3600L),
                scope = obj.optString("scope", ""),
                obtainedAtMillis = obj.optLong("obtained_at_millis", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Production store: Keystore-backed, one entry per vendor authRef. */
        fun create(context: Context, authRef: String): DeviceCodeTokenStore =
            DeviceCodeTokenStore(
                BrainKeyStoreSecretStore(BrainKeyStore(context.applicationContext)),
                authRef,
            )
    }
}
