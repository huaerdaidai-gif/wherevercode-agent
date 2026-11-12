package wherevercode.agent.core.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * [TransportAdapter] 的局域网实现（阶段 2 B 路，审计 §6.2 候选 B）。
 *
 * - 发现：mDNS（NsdManager，服务类型 `_wherevercode._tcp`），TXT 记录只含
 *   设备 id 与能力（见 [Discovery]），不含密钥与任何敏感信息。
 * - 直连：HTTPS，证书以设备公钥指纹 pinning，不走公共 CA；首次连接的指纹
 *   核对走 [wherevercode.agent.core.security.PairingManager]。
 * - 超时：连接 5s、读 30s（[CONNECT_TIMEOUT_MS] / [READ_TIMEOUT_MS]）。
 * - 进度流式回传：一次 POST，服务端以长度前缀帧流式回包。
 *
 * 本文件绝不出现平台服务器中继地址：新链路不依赖任何服务器。
 *
 * 注意：
 * - mDNS 组播需要 `CHANGE_WIFI_MULTICAST_STATE` 权限（Manifest 待补，见回报），
 *   拿不到锁时仍尽力发现，但可能不稳定。
 * - 本机服务注册（让别人发现本机，`NsdManager.registerService`）不在本文件范围，
 *   由 Hand 服务端实现。
 * - 每连接只发一次请求信封（见 [TransportAdapter] 约定）。
 */
class LanTransport(context: Context) : TransportAdapter {

    companion object {
        private const val TAG = "LanTransport"

        /** 连接超时：5 秒。 */
        const val CONNECT_TIMEOUT_MS = 5_000

        /** 读超时：30 秒（每次读操作，不是整通话上限）。 */
        const val READ_TIMEOUT_MS = 30_000

        /** Hand 服务端在设备上暴露的路径（服务端实现须提供该端点）。 */
        const val HAND_PATH = "/wherevercode/hand"

        /** 单帧上限 4MB（Hand 输出上限见审计 §2 #18：20,000 字符，4MB 足够有余）。 */
        const val MAX_FRAME_BYTES = 4 * 1024 * 1024
    }

    private val appContext = context.applicationContext

    // ------------------------------------------------------------------ 发现

    override suspend fun discover(timeoutMs: Long): List<DiscoveredPeer> {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: run {
                Log.w(TAG, "取不到 NsdManager，无法发现局域网设备")
                return emptyList()
            }
        val found = Collections.synchronizedList(mutableListOf<DiscoveredPeer>())
        val seenIds = Collections.synchronizedSet(mutableSetOf<String>())
        val multicastLock = acquireMulticastLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                try {
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val txt = serviceInfo.attributes
                                ?.let { Discovery.parseTxtRecord(it) } ?: return
                            val host = serviceInfo.host?.hostAddress ?: return
                            val peer = Discovery.toDiscoveredPeer(
                                serviceInfo.serviceName, host, serviceInfo.port, txt,
                            ) ?: return
                            if (seenIds.add(peer.deviceId)) found.add(peer)
                        }

                        override fun onResolveFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int,
                        ) {
                        }
                    })
                } catch (_: Exception) {
                    // 单个服务解析失败不影响其他服务。
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS 发现启动失败：errorCode=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        try {
            nsd.discoverServices(Discovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(timeoutMs)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS 发现异常：${e.message}")
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (_: Exception) {
            }
        }
        return found.toList()
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        return try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            wifi.createMulticastLock(TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "拿不到组播锁（缺 CHANGE_WIFI_MULTICAST_STATE 权限），发现可能不稳定")
            null
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ 连接

    override suspend fun connect(peer: DiscoveredPeer): TransportConnection =
        LanTransportConnection(peer)

    override suspend fun close() {
        // 本实现不在传输层持有长效资源；已建立的连接各自负责关闭。
    }

    private inner class LanTransportConnection(
        private val peer: DiscoveredPeer,
    ) : TransportConnection {

        @Volatile
        private var closed = false

        private var requestBytes: ByteArray? = null
        private var started = false
        private var connection: HttpsURLConnection? = null

        override suspend fun send(bytes: ByteArray) {
            check(!started) { "该连接已开始接收回包，不能再发送请求：每连接只发一次请求信封" }
            check(requestBytes == null) { "该连接已发送过请求信封，不能重复发送" }
            requestBytes = bytes
        }

        override fun incoming(): Flow<ByteArray> = flow {
            val body = requestBytes
                ?: throw IllegalStateException("请先调用 send() 发送请求信封，再读取回包")
            started = true
            val framed = ByteArrayOutputStream()
                .also { writeFrame(it, body) }.toByteArray()
            val conn = openPostConnection(framed.size)
            connection = conn
            try {
                conn.outputStream.use { it.write(framed); it.flush() }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw IOException("目标设备返回 HTTP $code：请检查目标设备的 Hand 是否在运行")
                }
                conn.inputStream.use { input ->
                    while (!closed) {
                        val frame = try {
                            readFrame(input)
                        } catch (e: IOException) {
                            // 自己调 close() 断开的属于正常结束，不当错误抛。
                            if (closed) break else throw e
                        } ?: break
                        emit(frame)
                    }
                }
            } finally {
                try {
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
        }.flowOn(Dispatchers.IO)

        override suspend fun close() {
            closed = true
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }

        private fun openPostConnection(contentLength: Int): HttpsURLConnection {
            val url = URL("https://${peer.host}:${peer.port}$HAND_PATH")
            val conn = url.openConnection() as HttpsURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(contentLength)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.sslSocketFactory = pinnedSocketFactory(peer.fingerprintSha256)
            conn.hostnameVerifier = pinnedHostnameVerifier(peer.fingerprintSha256)
            return conn
        }

        private fun pinnedSocketFactory(expectedFingerprintSha256: String): SSLSocketFactory {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(
                null,
                arrayOf(PinnedTrustManager(expectedFingerprintSha256)),
                SecureRandom(),
            )
            return sslContext.socketFactory
        }
    }
}

/**
 * 只认指纹的 TrustManager：对方证书必须是自签名设备证书，
 * 其公钥 SHA-256 必须等于配对时记下的指纹。不走公共 CA，
 * 不校验主机名（主机名校验由 [pinnedHostnameVerifier] 同样用指纹做）。
 */
private class PinnedTrustManager(
    private val expectedFingerprintSha256: String,
) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        // 本 TrustManager 只在客户端用，不验证对方的客户端证书。
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty()) {
            throw CertificateException("对方没有提供证书，无法核对指纹：请检查目标设备")
        }
        val presented = chain[0].publicKey.encoded
        if (!Discovery.verifyPinnedFingerprint(presented, expectedFingerprintSha256)) {
            throw CertificateException(
                "对方证书指纹与配对记录不符：请在目标设备上核对指纹，" +
                    "确认无误后重新配对（可能遭遇中间人，或设备密钥已更换）",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * 主机名校验同样用指纹做：自签名设备证书没有公共 DNS 名，
 * 信任来自配对时用户核对过的指纹，而不是主机名。
 */
private fun pinnedHostnameVerifier(expectedFingerprintSha256: String): HostnameVerifier =
    HostnameVerifier { _, session ->
        try {
            val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                ?: return@HostnameVerifier false
            Discovery.verifyPinnedFingerprint(cert.publicKey.encoded, expectedFingerprintSha256)
        } catch (_: Exception) {
            false
        }
    }

// ------------------------------------------------------------------ 帧编解码
// 线格式：4 字节大端长度 + 负载。请求与回包都用同一格式；回包是连续多帧，
// 直到服务端发完终态帧关闭流。

internal fun writeFrame(out: OutputStream, payload: ByteArray) {
    require(payload.size <= LanTransport.MAX_FRAME_BYTES) {
        "帧过大（${payload.size} 字节，上限 ${LanTransport.MAX_FRAME_BYTES}）"
    }
    out.write(
        byteArrayOf(
            (payload.size shr 24).toByte(),
            (payload.size shr 16).toByte(),
            (payload.size shr 8).toByte(),
            payload.size.toByte(),
        ),
    )
    out.write(payload)
    out.flush()
}

/** 读一帧；流干净结束时返回 null（服务端已关闭）。 */
internal fun readFrame(input: InputStream): ByteArray? {
    val header = ByteArray(4)
    var read = 0
    while (read < 4) {
        val n = input.read(header, read, 4 - read)
        if (n == -1) {
            if (read == 0) return null
            throw EOFException("回包帧头不完整：只读到 $read/4 字节")
        }
        read += n
    }
    val length = ((header[0].toInt() and 0xFF) shl 24) or
        ((header[1].toInt() and 0xFF) shl 16) or
        ((header[2].toInt() and 0xFF) shl 8) or
        (header[3].toInt() and 0xFF)
    if (length < 0 || length > LanTransport.MAX_FRAME_BYTES) {
        throw IOException("回包帧长度异常（$length）：可能不是 WhereverCode 帧，或对方发错了数据")
    }
    val payload = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val n = input.read(payload, offset, length - offset)
        if (n == -1) throw EOFException("回包帧体不完整：只读到 $offset/$length 字节")
        offset += n
    }
    return payload
}
