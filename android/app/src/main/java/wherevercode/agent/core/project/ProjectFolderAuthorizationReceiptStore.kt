package wherevercode.agent.core.project

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.security.MessageDigest

/** Minimal local evidence for a verified Hand folder grant. Raw responses,
 * credentials, and task envelopes are deliberately not retained. */
data class ProjectFolderAuthorizationReceipt(
    val peerFingerprintSha256: String,
    val agentId: String,
    val requestedFolder: String,
    val canonicalFolder: String,
    val alreadyAuthorized: Boolean,
    val receivedAtEpochSeconds: Long,
    val responseSha256: String,
)

/**
 * The App side of a Hand project-folder grant (MainActivity's authorization
 * path): verify the Hand's receipt is bound to this exact request (agent
 * identity and requested folder), then keep minimal local evidence keyed by
 * the paired Hand's certificate fingerprint and the canonical folder.
 * Nothing is recorded when verification fails.
 */
fun verifyAndRecordProjectFolderAuthorization(
    rawAck: String,
    peerFingerprintSha256: String,
    agentId: String,
    requestedFolder: String,
    receipts: ProjectFolderAuthorizationReceiptStore,
    receivedAtEpochSeconds: Long,
): ProjectFolderAuthorizationAck {
    val ack = parseProjectFolderAuthorizationAck(rawAck, requestedFolder, agentId)
    receipts.record(
        peerFingerprintSha256 = peerFingerprintSha256,
        agentId = agentId,
        requestedFolder = requestedFolder,
        rawAck = rawAck,
        ack = ack,
        receivedAtEpochSeconds = receivedAtEpochSeconds,
    )
    return ack
}

interface ProjectFolderAuthorizationReceiptStore {
    fun record(peerFingerprintSha256: String, agentId: String, requestedFolder: String, rawAck: String,
               ack: ProjectFolderAuthorizationAck, receivedAtEpochSeconds: Long)
    fun get(peerFingerprintSha256: String, agentId: String, canonicalFolder: String): ProjectFolderAuthorizationReceipt?
}

class KeystoreProjectFolderAuthorizationReceiptStore private constructor(
    private val prefs: android.content.SharedPreferences,
) : ProjectFolderAuthorizationReceiptStore {
    override fun record(
        peerFingerprintSha256: String,
        agentId: String,
        requestedFolder: String,
        rawAck: String,
        ack: ProjectFolderAuthorizationAck,
        receivedAtEpochSeconds: Long,
    ) {
        require(peerFingerprintSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Hand 指纹格式无效" }
        val receipt = ProjectFolderAuthorizationReceipt(
            peerFingerprintSha256.lowercase(), agentId, requestedFolder, ack.folder,
            ack.already, receivedAtEpochSeconds, sha256(rawAck),
        )
        val saved = prefs.edit().putString(key(receipt.peerFingerprintSha256, agentId, ack.folder), JSONObject()
            .put("peer", receipt.peerFingerprintSha256)
            .put("agent", agentId)
            .put("requested", requestedFolder)
            .put("folder", ack.folder)
            .put("already", ack.already)
            .put("received_at", receivedAtEpochSeconds)
            .put("response_sha256", receipt.responseSha256)
            .toString()).commit()
        check(saved) { "无法安全保存 Hand 授权回执" }
    }

    override fun get(peerFingerprintSha256: String, agentId: String, canonicalFolder: String): ProjectFolderAuthorizationReceipt? {
        val raw = prefs.getString(key(peerFingerprintSha256.lowercase(), agentId, canonicalFolder), null) ?: return null
        return try {
            val json = JSONObject(raw)
            ProjectFolderAuthorizationReceipt(
                json.getString("peer"), json.getString("agent"), json.getString("requested"),
                json.getString("folder"), json.getBoolean("already"), json.getLong("received_at"),
                json.getString("response_sha256"),
            )
        } catch (_: Exception) { null }
    }

    private fun key(peer: String, agent: String, folder: String): String =
        "receipt_${sha256("$peer\u0000$agent\u0000$folder") }"

    companion object {
        /** Same store over caller-supplied preferences (JVM tests; production uses [create]). */
        @androidx.annotation.VisibleForTesting
        internal fun forPreferences(prefs: android.content.SharedPreferences): KeystoreProjectFolderAuthorizationReceiptStore =
            KeystoreProjectFolderAuthorizationReceiptStore(prefs)

        fun create(context: Context): KeystoreProjectFolderAuthorizationReceiptStore {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val prefs = EncryptedSharedPreferences.create(
                context, "wherevercode_hand_authorization_receipts", masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return KeystoreProjectFolderAuthorizationReceiptStore(prefs)
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
