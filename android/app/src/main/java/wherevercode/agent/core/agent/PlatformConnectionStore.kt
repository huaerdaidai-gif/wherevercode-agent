package wherevercode.agent.core.agent

import android.content.Context
import android.content.SharedPreferences
import java.net.URI

/** App-level platform address; the credential remains in [PlatformAgentCredentialStore]. */
class PlatformConnectionStore(
    context: Context,
) {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Stored app value wins, then the first platform Agent record, then the build default. */
    fun platformHost(firstPlatformAgentHost: String?, buildDefaultHost: String): String =
        preferences.getString(KEY_PLATFORM_HOST, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: firstPlatformAgentHost?.trim()?.takeIf { it.isNotEmpty() }
            ?: buildDefaultHost.trim()

    fun savePlatformHost(host: String) {
        val value = host.trim()
        require(value.isNotEmpty()) { "平台地址不能为空" }
        preferences.edit().putString(KEY_PLATFORM_HOST, value).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "platform_connection"
        const val KEY_PLATFORM_HOST = "platform_host"

        /** Accept a host or an origin and return the host-only record value. */
        fun hostFromAddress(address: String): String? {
            val raw = address.trim()
            if (raw.isEmpty()) return null
            // 内网例外（频道三层二期）：http 只对内网地址放行，与端点
            // 协议口径一致（PlatformAgent.isLanAddress）；公网 http 一律拒。
            val wantsHttp = raw.startsWith("http://", ignoreCase = true)
            val withScheme = when {
                raw.startsWith("https://", ignoreCase = true) -> raw
                wantsHttp -> raw
                else -> "https://$raw"
            }
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            if (uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                uri.rawPath.orEmpty().let { it.isNotEmpty() && it != "/" }
            ) return null
            val host = buildString {
                append(uri.host)
                if (uri.port >= 0) append(":${uri.port}")
            }
            if (wantsHttp && !PlatformAgent.isLanAddress(host)) return null
            return host.takeIf { PlatformAgent.platformHostIssue(it) == null }
        }
    }
}
