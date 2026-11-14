package wherevercode.agent.core.agent

import java.io.File
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises optional pinning with synthetic test certificates and documentation-only hosts. */
class PlatformEndpointPinningTest {
    private fun loadCert(name: String): X509Certificate {
        val path = "wherevercode/agent/core/agent/$name"
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: File("android/app/src/test/resources/$path").inputStream()
        return stream.use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    }

    @Test
    fun `an exact pin does not bypass system certificate trust`() {
        val cert = loadCert("unrelated-cert.pem")
        val pin = PlatformEndpointPinning.Pin("192.0.2.10", PlatformEndpointPinning.sha256Hex(cert))
        try {
            PlatformEndpointPinning.pinnedTrustManager(pin).checkServerTrusted(arrayOf(cert), "RSA")
            throw AssertionError("a matching pin must not make a self-signed certificate trusted")
        } catch (_: CertificateException) {
            // A matching fingerprint is an extra constraint, not a substitute CA.
        }
    }

    @Test
    fun `a pin mismatch falls back to strict system trust`() {
        val cert = loadCert("unrelated-cert.pem")
        val pin = PlatformEndpointPinning.Pin("192.0.2.10", "00".repeat(32))
        try {
            PlatformEndpointPinning.pinnedTrustManager(pin).checkServerTrusted(arrayOf(cert), "RSA")
            throw AssertionError("an untrusted self-signed certificate must be rejected")
        } catch (_: CertificateException) {
            // The default trust manager rejects the self-signed, non-pinned fixture.
        }
    }

    @Test
    fun `an expired certificate is rejected even when its pin matches`() {
        val cert = loadCert("expired-cert.pem")
        val pin = PlatformEndpointPinning.Pin("192.0.2.10", PlatformEndpointPinning.sha256Hex(cert))
        try {
            PlatformEndpointPinning.pinnedTrustManager(pin).checkServerTrusted(arrayOf(cert), "RSA")
            throw AssertionError("an expired pinned certificate must be rejected")
        } catch (_: CertificateExpiredException) {
            // A matching pin does not bypass the X.509 validity period.
        }
    }

    @Test
    fun `only the configured HTTPS host receives the optional socket factory`() {
        val cert = loadCert("unrelated-cert.pem")
        val pin = PlatformEndpointPinning.Pin("192.0.2.10", PlatformEndpointPinning.sha256Hex(cert))
        assertTrue(PlatformEndpointPinning.isPinnedHost(" 192.0.2.10 ", pin))
        assertTrue(PlatformEndpointPinning.isPinnedUrl("https://192.0.2.10/api", pin))
        assertFalse(PlatformEndpointPinning.isPinnedUrl("http://192.0.2.10/api", pin))
        assertFalse(PlatformEndpointPinning.isPinnedUrl("https://192.0.2.11/api", pin))
        assertFalse(PlatformEndpointPinning.isPinnedUrl("not a URL", pin))
        assertNotNull(UrlConnectionPlatformHttpClient.pinnedSocketFactoryFor("https://192.0.2.10/api", pin))
        assertNull(UrlConnectionPlatformHttpClient.pinnedSocketFactoryFor("https://example.invalid/api", pin))
        assertNull(UrlConnectionPlatformHttpClient.pinnedSocketFactoryFor("https://192.0.2.10/api", null))
    }

    @Test
    fun `public builds do not get a pin unless local build properties set one`() {
        assertNull(PlatformEndpointPinning.configuredPin)
    }
}
