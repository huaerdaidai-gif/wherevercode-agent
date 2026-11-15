package wherevercode.agent.core.agent

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Task A (2026-11-15): real-path TLS integration tests for platform endpoint
 * pinning. Every scenario runs against a loopback TLS server on 127.0.0.1
 * and a real [HttpsURLConnection] (or the real [ChannelWsClient] for the
 * WebSocket path); nothing connects to the network.
 *
 * Certificate fixtures are pre-generated PEM test resources (see
 * `tls-*.pem` next to this test's resource path): a test CA, a CA-signed
 * `localhost` server certificate, a CA-signed certificate whose SAN names
 * a different host, a CA-signed expired certificate, and a self-signed
 * `localhost` certificate. The CA private key is intentionally NOT in the
 * repo; the fixtures are static and long-lived (2026-2036, expired one
 * 2020-2021).
 *
 * Trust setup: the JVM/Android system trust store cannot contain the test
 * CA, so [installTestTrust] points `javax.net.ssl.trustStore` at a store
 * holding only the test CA (this is what [PlatformEndpointPinning]'s
 * internal system trust manager reads), and installs an
 * [HttpsURLConnection] default socket factory backed by a standard
 * [TrustManagerFactory] over the same store, which is exactly the
 * "no pin configured" production wiring (the production client installs
 * no factory of its own in that case). Both are restored in [tearDown].
 *
 * Shared-JVM provider hygiene: Gradle runs the whole unit-test suite in
 * one JVM, and every Robolectric test class that ran earlier in that JVM
 * leaves the Conscrypt JCE provider registered at position 1 (plus BC at
 * the end). From then on `SSLContext.getInstance("TLS")` — used by this
 * class's servers and factories and by the production pinning code —
 * silently resolves to Conscrypt instead of SunJSSE, and Conscrypt's
 * handshake dies on JDK 17 with an InaccessibleObjectException (it
 * reflectively opens `java.net.InetAddress.holder()`), so each loopback
 * server closes the connection right after the handshake and every
 * positive scenario fails with "Unexpected end of file from server"
 * while the rejection scenarios still pass on any handshake failure.
 * [installTestTrust] therefore removes the Conscrypt provider for the
 * duration of this class (the JDK reference TLS stack is what a JVM
 * run of this class means to exercise; on Android the platform TLS
 * stack is unaffected) and [restoreTrust] re-inserts it at its
 * original position so later test classes see the JVM as they left it.
 */
class PlatformEndpointTlsIntegrationTest {

    companion object {
        private const val RESOURCE_DIR = "wherevercode/agent/core/agent"
        private const val BODY_MARKER = "tls-loopback-ok"
        private const val TRUST_STORE_PASSWORD = "tls-integration-test"

        private lateinit var caCert: X509Certificate
        private var previousDefaultFactory: SSLSocketFactory? = null
        private var removedConscrypt: Provider? = null
        private var removedConscryptPosition: Int = -1

        private fun resourceText(name: String): String {
            val path = "$RESOURCE_DIR/$name"
            val stream: InputStream = javaClass.classLoader?.getResourceAsStream(path)
                ?: File("android/app/src/test/resources/$path").inputStream()
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }

        fun loadCert(name: String): X509Certificate =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(resourceText(name).byteInputStream()) as X509Certificate

        private fun loadKey(name: String): PrivateKey {
            val der = resourceText(name)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val bytes = Base64.getDecoder().decode(der)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
        }

        private fun caTrustStore(): KeyStore {
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, null)
            store.setCertificateEntry("test-ca", caCert)
            return store
        }

        /** A standard (non-permissive) trust-manager factory trusting only the test CA. */
        fun caSocketFactory(): SSLSocketFactory {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(caTrustStore())
            val context = SSLContext.getInstance("TLS")
            context.init(null, tmf.trustManagers, null)
            return context.socketFactory
        }

    private fun serverContext(certName: String, keyName: String, chainCa: Boolean): SSLContext {
        val leaf = loadCert(certName)
        val chain = if (chainCa) arrayOf(leaf, caCert) else arrayOf(leaf)
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry("server", loadKey(keyName), "server-key".toCharArray(), chain)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(store, "server-key".toCharArray())
        val context = SSLContext.getInstance("TLS")
        context.init(kmf.keyManagers, null, null)
        return context
    }

        @BeforeClass
        @JvmStatic
        fun installTestTrust() {
            // Must run before any SSLContext is created in this class:
            // drop the Robolectric-inserted Conscrypt provider (see the
            // class doc) so every TLS context below — and the production
            // pinning factory exercised by these tests — uses SunJSSE.
            Security.getProvider("Conscrypt")?.let { conscrypt ->
                removedConscryptPosition = Security.getProviders().indexOf(conscrypt) + 1
                removedConscrypt = conscrypt
                Security.removeProvider("Conscrypt")
            }
            caCert = loadCert("tls-ca-cert.pem")
            // (a) javax.net.ssl.trustStore: read by TrustManagerFactory.init(null),
            // which is what PlatformEndpointPinning.pinnedTrustManager uses for
            // its mandatory system-CA check.
            val file = File.createTempFile("tls-test-truststore", ".p12")
            file.deleteOnExit()
            caTrustStore().store(file.outputStream(), TRUST_STORE_PASSWORD.toCharArray())
            System.setProperty("javax.net.ssl.trustStore", file.absolutePath)
            System.setProperty("javax.net.ssl.trustStorePassword", TRUST_STORE_PASSWORD)
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
            // (b) HttpsURLConnection default factory: production code with no
            // pin configured installs nothing, so this default IS the system
            // behaviour under test in the no-pin scenarios.
            previousDefaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
            HttpsURLConnection.setDefaultSSLSocketFactory(caSocketFactory())
        }

        @AfterClass
        @JvmStatic
        fun restoreTrust() {
            previousDefaultFactory?.let { HttpsURLConnection.setDefaultSSLSocketFactory(it) }
            System.clearProperty("javax.net.ssl.trustStore")
            System.clearProperty("javax.net.ssl.trustStorePassword")
            System.clearProperty("javax.net.ssl.trustStoreType")
            // Put the Conscrypt provider back where installTestTrust
            // found it, so Robolectric classes running later in this JVM
            // observe the same provider list as if this class had not
            // touched it.
            removedConscrypt?.let { conscrypt ->
                if (Security.getProvider("Conscrypt") == null) {
                    val position = if (removedConscryptPosition > 0) removedConscryptPosition else 1
                    Security.insertProviderAt(conscrypt, position)
                }
                removedConscrypt = null
                removedConscryptPosition = -1
            }
        }
    }

    // ------------------------------------------------------------------
    // Loopback TLS servers
    // ------------------------------------------------------------------

    /** Minimal HTTPS server: one fixed JSON body per request, real TLS handshake. */
    private class LoopbackHttpsServer(certName: String, keyName: String, chainCa: Boolean) {
        private val serverSocket: javax.net.ssl.SSLServerSocket
        @Volatile private var running = true

        val port: Int get() = serverSocket.localPort

        init {
            val context = serverContext(certName, keyName, chainCa)
            serverSocket = context.serverSocketFactory.createServerSocket() as javax.net.ssl.SSLServerSocket
            serverSocket.bind(InetSocketAddress("127.0.0.1", 0))
            Thread {
                while (running) {
                    try {
                        val socket = serverSocket.accept()
                        Thread { serve(socket) }.apply { isDaemon = true }.start()
                    } catch (_: Exception) {
                        if (running) continue else break
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(socket: Socket) {
            try {
                socket.soTimeout = 10_000
                val input = socket.getInputStream()
                val header = ByteArray(16 * 1024)
                var read = 0
                // Read until end of the request header block.
                while (read < header.size - 4) {
                    val n = input.read(header, read, 1)
                    if (n < 0) break
                    read += n
                    if (read >= 4 && header.copyOfRange(read - 4, read)
                            .contentEquals("\r\n\r\n".toByteArray())
                    ) break
                }
                val body = "{\"ok\":true,\"marker\":\"tls-loopback-ok\"}"
                val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                socket.getOutputStream().write(response.toByteArray())
                socket.getOutputStream().flush()
            } catch (_: Exception) {
                // Failed handshakes (the rejection scenarios) land here; the
                // server stays up for the client-side assertion.
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }

        fun close() {
            running = false
            try { serverSocket.close() } catch (_: Exception) {}
        }
    }


    // ------------------------------------------------------------------
    // Client helpers
    // ------------------------------------------------------------------

    /** Real HTTPS GET through a production pinned socket factory. */
    private fun getWithPinnedFactory(url: String, pin: PlatformEndpointPinning.Pin): String {
        val factory = UrlConnectionPlatformHttpClient.pinnedSocketFactoryFor(url, pin)
        assertNotNull("pinned host must receive a pinned socket factory", factory)
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = factory!!
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        // Deliberately no setHostnameVerifier: the platform default
        // hostname verification is part of what is being tested.
        val status = connection.responseCode
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
        assertEquals(200, status)
        return body
    }

    private fun pinFor(cert: X509Certificate): PlatformEndpointPinning.Pin =
        PlatformEndpointPinning.Pin("localhost", PlatformEndpointPinning.sha256Hex(cert))

    private fun assertRequestRejected(scenario: String, block: () -> Unit): Throwable {
        try {
            block()
        } catch (error: Throwable) {
            return error
        }
        throw AssertionError("$scenario: the TLS request must be rejected")
    }

    private fun chainOf(error: Throwable): List<Throwable> {
        val out = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && current !in out) {
            out += current
            current = current.cause
        }
        return out
    }

    private fun chainText(error: Throwable): String =
        chainOf(error).joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }

    // ------------------------------------------------------------------
    // Six scenarios
    // ------------------------------------------------------------------

    /** Scenario 1: trusted chain + correct pin, but the certificate SAN
     *  names a different host than the request host -> must be rejected
     *  by the default hostname verifier. */
    @Test
    fun `wrong hostname is rejected even with trusted chain and correct pin`() {
        val cert = loadCert("tls-wronghost-cert.pem")
        val server = LoopbackHttpsServer("tls-wronghost-cert.pem", "tls-wronghost-key.pem", chainCa = true)
        try {
            val url = "https://localhost:${server.port}/api/open/v1/mcp"
            val error = assertRequestRejected("wrong hostname") {
                getWithPinnedFactory(url, pinFor(cert))
            }
            assertTrue(
                "expected hostname verification failure, got: ${chainText(error)}",
                chainOf(error).any { it is SSLPeerUnverifiedException } ||
                    chainText(error).contains("doesn't match", ignoreCase = true) ||
                    chainText(error).contains("No subject alternative", ignoreCase = true),
            )
        } finally {
            server.close()
        }
    }

    /** Scenario 2: self-signed certificate + correct pin -> rejected,
     *  because a pin must not bypass system CA trust. */
    @Test
    fun `self signed certificate is rejected even with correct pin`() {
        val cert = loadCert("tls-selfsigned-cert.pem")
        val server = LoopbackHttpsServer("tls-selfsigned-cert.pem", "tls-selfsigned-key.pem", chainCa = false)
        try {
            val url = "https://localhost:${server.port}/api/open/v1/mcp"
            val error = assertRequestRejected("self-signed with correct pin") {
                getWithPinnedFactory(url, pinFor(cert))
            }
            assertTrue(
                "expected a handshake/trust failure, got: ${chainText(error)}",
                chainOf(error).any { it is SSLHandshakeException || it is CertificateException } ||
                    chainText(error).contains("PKIX", ignoreCase = true),
            )
        } finally {
            server.close()
        }
    }

    /** Scenario 3: expired (CA-signed) certificate + correct pin -> rejected. */
    @Test
    fun `expired certificate is rejected even with correct pin`() {
        val cert = loadCert("tls-expired-cert.pem")
        val server = LoopbackHttpsServer("tls-expired-cert.pem", "tls-expired-key.pem", chainCa = true)
        try {
            val url = "https://localhost:${server.port}/api/open/v1/mcp"
            val error = assertRequestRejected("expired certificate") {
                getWithPinnedFactory(url, pinFor(cert))
            }
            assertTrue(
                "expected an expiry failure, got: ${chainText(error)}",
                chainOf(error).any { it is CertificateExpiredException } ||
                    chainText(error).contains("expired", ignoreCase = true),
            )
        } finally {
            server.close()
        }
    }

    /** Scenario 4: certificate trusted by the (test) system CA, but the
     *  configured pin does not match -> rejected for the pin itself.
     *  Unlike the phase-1 unit test, the chain here IS trusted, so the pin
     *  mismatch is the isolated cause of rejection. */
    @Test
    fun `wrong pin is rejected for a system trusted certificate`() {
        loadCert("tls-localhost-cert.pem")
        val server = LoopbackHttpsServer("tls-localhost-cert.pem", "tls-localhost-key.pem", chainCa = true)
        try {
            val url = "https://localhost:${server.port}/api/open/v1/mcp"
            val wrongPin = PlatformEndpointPinning.Pin("localhost", "00".repeat(32))
            val error = assertRequestRejected("wrong pin") {
                getWithPinnedFactory(url, wrongPin)
            }
            assertTrue(
                "expected a pin mismatch failure, got: ${chainText(error)}",
                chainText(error).contains("pin did not match", ignoreCase = true),
            )
        } finally {
            server.close()
        }
    }

    /** Scenario 5: trusted certificate + correct pin -> the real HTTPS
     *  request succeeds and returns the server's body. */
    @Test
    fun `trusted certificate with correct pin completes a real https request`() {
        val cert = loadCert("tls-localhost-cert.pem")
        val server = LoopbackHttpsServer("tls-localhost-cert.pem", "tls-localhost-key.pem", chainCa = true)
        try {
            val url = "https://localhost:${server.port}/api/open/v1/mcp"
            val body = getWithPinnedFactory(url, pinFor(cert))
            assertTrue("expected the loopback server body, got: $body", body.contains(BODY_MARKER))
        } finally {
            server.close()
        }
    }

    /** Scenario 6a: no pin configured -> the production HTTP client uses
     *  the platform default TLS behaviour; a certificate trusted by the
     *  system store (here: the test CA store) is accepted end to end. */
    @Test
    fun `no pin configured accepts a system trusted certificate through the production client`() {
        val server = LoopbackHttpsServer("tls-localhost-cert.pem", "tls-localhost-key.pem", chainCa = true)
        try {
            val url = "https://localhost:${server.port}/api/open/v1/mcp"
            val body = UrlConnectionPlatformHttpClient().getJson(url, null)
            assertTrue("expected the loopback server body, got: $body", body.contains(BODY_MARKER))
        } finally {
            server.close()
        }
    }

    /** Scenario 6b: no pin configured -> a self-signed certificate is
     *  rejected by the platform default TLS behaviour. */
    @Test
    fun `no pin configured rejects a self signed certificate through the production client`() {
        val server = LoopbackHttpsServer("tls-selfsigned-cert.pem", "tls-selfsigned-key.pem", chainCa = false)
        try {
            val url = "https://localhost:${server.port}/api/open/v1/mcp"
            assertRequestRejected("no pin, self-signed") {
                UrlConnectionPlatformHttpClient().getJson(url, null)
            }
        } finally {
            server.close()
        }
    }

    // ------------------------------------------------------------------
    // WebSocket path (ChannelWsClient): hostname verification must also
    // hold on the WSS path, which enables HTTPS endpoint identification
    // on the SSLSocket before startHandshake.
    // ------------------------------------------------------------------

    private fun wsClient(port: Int): ChannelWsClient =
        ChannelWsClient(
            host = "localhost",
            port = port,
            channelId = "tls-test-channel",
            tokenProvider = { "test-token" },
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            socketFactory = { host, p ->
                (caSocketFactory().createSocket() as SSLSocket).also {
                    it.connect(InetSocketAddress(host, p), 5_000)
                }
            },
        )

    /** Minimal TLS WebSocket server: completes the RFC 6455 upgrade and
     *  sends one unmasked text frame. */
    private inner class LoopbackWsServer(certName: String, keyName: String) {
        private val serverSocket: javax.net.ssl.SSLServerSocket
        @Volatile private var running = true
        val port: Int get() = serverSocket.localPort

        init {
            val context = serverContext(certName, keyName, chainCa = true)
            serverSocket = context.serverSocketFactory.createServerSocket() as javax.net.ssl.SSLServerSocket
            serverSocket.bind(InetSocketAddress("127.0.0.1", 0))
            Thread {
                while (running) {
                    try {
                        val socket = serverSocket.accept()
                        Thread { serve(socket) }.apply { isDaemon = true }.start()
                    } catch (_: Exception) {
                        if (running) continue else break
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(socket: Socket) {
            try {
                socket.soTimeout = 10_000
                val input = socket.getInputStream()
                val raw = StringBuilder()
                val buf = ByteArray(1)
                while (!raw.endsWith("\r\n\r\n")) {
                    val n = input.read(buf)
                    if (n < 0) return
                    raw.append(buf[0].toInt().toChar())
                    if (raw.length > 16 * 1024) return
                }
                val key = Regex("Sec-WebSocket-Key:\\s*(\\S+)", RegexOption.IGNORE_CASE)
                    .find(raw)?.groupValues?.get(1) ?: return
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
                )
                val out = socket.getOutputStream()
                out.write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                        "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII),
                )
                val payload = "ws-tls-ok".toByteArray(Charsets.UTF_8)
                out.write(byteArrayOf(0x81.toByte(), payload.size.toByte()))
                out.write(payload)
                out.flush()
                // Keep the socket open until the client goes away.
                try { while (input.read() >= 0) { } } catch (_: Exception) {}
            } catch (_: Exception) {
                // Handshake failures (wrong-host scenario) land here.
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }

        fun close() {
            running = false
            try { serverSocket.close() } catch (_: Exception) {}
        }
    }

    @Test
    fun `websocket path accepts a trusted certificate with the right hostname`() {
        val server = LoopbackWsServer("tls-localhost-cert.pem", "tls-localhost-key.pem")
        try {
            val received = CountDownLatch(1)
            val texts = mutableListOf<String>()
            val client = wsClient(server.port)
            val thread = Thread {
                client.run { text -> texts += text; received.countDown() }
            }.apply { isDaemon = true }
            thread.start()
            assertTrue("expected one WS text frame over TLS", received.await(10, TimeUnit.SECONDS))
            assertEquals(listOf("ws-tls-ok"), texts)
            client.close()
        } finally {
            server.close()
        }
    }

    @Test
    fun `websocket path rejects a certificate for the wrong hostname`() {
        val server = LoopbackWsServer("tls-wronghost-cert.pem", "tls-wronghost-key.pem")
        try {
            val texts = mutableListOf<String>()
            var closedReason: String? = null
            val client = wsClient(server.port)
            client.onClosed = { closedReason = it }
            // run() is synchronous here: the TLS handshake must fail before
            // any frame is read, and run() reports the close reason.
            client.run { text -> texts += text }
            assertTrue("no WS text may arrive over a wrong-host certificate", texts.isEmpty())
            assertTrue("expected a close reason, got none", !closedReason.isNullOrBlank())
        } finally {
            server.close()
        }
    }
}
