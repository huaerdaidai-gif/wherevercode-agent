package wherevercode.agent.core.agent

import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import wherevercode.agent.BuildConfig

/** Optional certificate pinning for a privately configured platform endpoint. */
object PlatformEndpointPinning {
    data class Pin(val host: String, val sha256Hex: String) {
        fun isValid(): Boolean = host.isNotBlank() && sha256Hex.matches(Regex("[0-9a-fA-F]{64}"))
    }

    /** Empty defaults keep public builds on Android's normal HTTPS trust store. */
    val configuredPin: Pin? by lazy {
        Pin(BuildConfig.PINNED_PLATFORM_HOST.trim(), BuildConfig.PINNED_PLATFORM_SHA256.trim())
            .takeIf { it.isValid() }
    }

    fun isPinnedHost(host: String?, pin: Pin? = configuredPin): Boolean =
        pin?.isValid() == true && host?.trim().equals(pin.host, ignoreCase = true)

    fun isPinnedUrl(url: String, pin: Pin? = configuredPin): Boolean {
        val parsed = try {
            URL(url)
        } catch (_: Exception) {
            return false
        }
        return parsed.protocol.equals("https", ignoreCase = true) && isPinnedHost(parsed.host, pin)
    }

    fun sha256Hex(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun sha256Colon(cert: X509Certificate): String =
        sha256Hex(cert).chunked(2).joinToString(":") { it.uppercase() }

    fun sha256Base64(cert: X509Certificate): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(cert.encoded),
        )

    fun matchesPinnedFingerprint(chain: Array<X509Certificate>?, pin: Pin): Boolean {
        if (!pin.isValid()) return false
        val leaf = chain?.firstOrNull() ?: return false
        return try {
            sha256Hex(leaf).equals(pin.sha256Hex, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    @Throws(CertificateException::class)
    fun verifyPinnedChain(chain: Array<X509Certificate>?, pin: Pin) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("The configured platform endpoint did not provide a certificate.")
        if (!pin.isValid() || !matchesPinnedFingerprint(chain, pin)) {
            throw CertificateException("The configured platform endpoint certificate pin did not match.")
        }
        leaf.checkValidity()
    }

    private fun defaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** System CA trust is mandatory; a configured pin only narrows the accepted certificate. */
    fun pinnedTrustManager(pin: Pin): X509TrustManager {
        require(pin.isValid()) { "A valid configured host and SHA-256 pin are required." }
        val default = defaultTrustManager()
        return object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = default.acceptedIssuers

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                default.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                verifyPinnedChain(chain, pin)
                default.checkServerTrusted(chain, authType)
            }
        }
    }

    fun socketFactory(pin: Pin): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(pinnedTrustManager(pin)), null)
        return context.socketFactory
    }
}
