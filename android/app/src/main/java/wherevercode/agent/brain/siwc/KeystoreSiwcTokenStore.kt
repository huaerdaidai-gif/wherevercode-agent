package wherevercode.agent.brain.siwc

import android.content.Context
import org.json.JSONObject
import wherevercode.agent.brain.BrainKeyStore

/**
 * [SiwcTokenStore] on top of M2's [BrainKeyStore] (Keystore-backed
 * EncryptedSharedPreferences `"wherevercode_brain_keys"`).
 *
 * - One record, [AUTH_REF] = `"siwc:tokens"` (the authRef the M2
 *   `BrainConfig` for `siwc-chatgpt` points at). The whole [SiwcTokens]
 *   value is serialized as one JSON string and stored as one secret; the
 *   store never sees the individual token values.
 * - No new encryption is created. No token value is ever logged.
 * - A corrupt or incomplete record loads as null (treated as signed out);
 *   the next successful sign-in overwrites it. The corrupt value is left
 *   in place rather than deleted, so a transient write failure cannot
 *   silently destroy a good record.
 *
 * The [SiwcSecretStore] port keeps this class unit-testable without
 * Robolectric: tests pass an in-memory fake, while production uses
 * [BrainKeyStoreSecretStore] (see [create]).
 */
class KeystoreSiwcTokenStore(
    private val secrets: SiwcSecretStore,
) : SiwcTokenStore {

    override fun saveTokens(tokens: SiwcTokens) {
        secrets.put(AUTH_REF, toJson(tokens))
    }

    override fun loadTokens(): SiwcTokens? {
        val raw = secrets.get(AUTH_REF) ?: return null
        return try {
            fromJson(raw)
        } catch (_: Exception) {
            null
        }
    }

    override fun clearTokens() {
        secrets.delete(AUTH_REF)
    }

    private fun toJson(tokens: SiwcTokens): String {
        val json = JSONObject()
        json.put(FIELD_ACCESS_TOKEN, tokens.accessToken)
        tokens.refreshToken?.let { json.put(FIELD_REFRESH_TOKEN, it) }
        tokens.idToken?.let { json.put(FIELD_ID_TOKEN, it) }
        json.put(FIELD_CLIENT_ID, tokens.clientId)
        json.put(FIELD_HOST_ID, tokens.hostId)
        json.put(FIELD_EXPIRES_AT_MILLIS, tokens.accessTokenExpiresAtMillis)
        tokens.accountLabel?.let { json.put(FIELD_ACCOUNT_LABEL, it) }
        return json.toString()
    }

    /**
     * Parse a stored record. Returns null when a required field
     * (access token, client id, host id) is missing or blank, or the
     * expiry is absent — a partial record is not usable credentials.
     */
    private fun fromJson(raw: String): SiwcTokens? {
        val json = JSONObject(raw)
        val accessToken = json.optString(FIELD_ACCESS_TOKEN, "")
        val clientId = json.optString(FIELD_CLIENT_ID, "")
        val hostId = json.optString(FIELD_HOST_ID, "")
        if (accessToken.isBlank() || clientId.isBlank() || hostId.isBlank()) {
            return null
        }
        if (!json.has(FIELD_EXPIRES_AT_MILLIS)) return null
        return SiwcTokens(
            accessToken = accessToken,
            refreshToken = json.optString(FIELD_REFRESH_TOKEN, "").ifBlank { null },
            idToken = json.optString(FIELD_ID_TOKEN, "").ifBlank { null },
            clientId = clientId,
            hostId = hostId,
            accessTokenExpiresAtMillis = json.optLong(FIELD_EXPIRES_AT_MILLIS),
            accountLabel = json.optString(FIELD_ACCOUNT_LABEL, "").ifBlank { null },
        )
    }

    companion object {
        /** Fixed authRef for the SIWC token record (see M2 BrainConfig). */
        const val AUTH_REF = "siwc:tokens"

        const val FIELD_ACCESS_TOKEN = "access_token"
        const val FIELD_REFRESH_TOKEN = "refresh_token"
        const val FIELD_ID_TOKEN = "id_token"
        const val FIELD_CLIENT_ID = "client_id"
        const val FIELD_HOST_ID = "host_id"
        const val FIELD_EXPIRES_AT_MILLIS = "expires_at_millis"
        const val FIELD_ACCOUNT_LABEL = "account_label"

        /** Production wiring: Keystore-backed secrets for the given [Context]. */
        fun create(context: Context): KeystoreSiwcTokenStore {
            val appContext = context.applicationContext
            return KeystoreSiwcTokenStore(
                BrainKeyStoreSecretStore(BrainKeyStore(appContext)),
            )
        }
    }
}
