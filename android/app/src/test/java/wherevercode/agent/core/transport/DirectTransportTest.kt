package wherevercode.agent.core.transport

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 阶段 B（V5 紧急开发）：DirectTransport——App 直连远程 Hand 的传输。
 *
 * 红测试：DirectTransport / RemoteHello / parseHello 尚不存在。
 * 全部用本机回环 ServerSocket 做真 TCP，不触网、不冒充 VPS：
 * 服务端按脚本回放帧，断言客户端的发送、接收、hello 解析与拒绝语义。
 * 线格式与 LanTransport 相同（4 字节大端长度 + 负载），服务端实现
 * （server/hand_server.py）须按同一格式回包。
 */
class DirectTransportTest {

    private val peer = DiscoveredPeer(
        deviceId = "remote-host",
        displayName = "远程设备",
        host = "127.0.0.1",
        port = 0, // 由测试服务端实际端口覆盖
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )

    private var server: ServerSocket? = null
    private var serverThread: Thread? = null

    @After
    fun tearDown() {
        try { server?.close() } catch (_: Exception) { }
        serverThread?.join(1000)
    }

    /** 启动一个回环服务端：读一帧请求，按 [handler] 回包。 */
    private fun startServer(handler: (request: ByteArray, out: OutputStream) -> Unit): Int {
        val srv = ServerSocket(0)
        server = srv
        serverThread = Thread {
            try {
                srv.accept().use { socket ->
                    val request = readFrame(socket.inputStream)
                    if (request != null) handler(request, socket.outputStream)
                }
            } catch (_: Exception) {
                // 测试结束关 socket 时的正常异常。
            }
        }.also { it.isDaemon = true; it.start() }
        return srv.localPort
    }

    private fun readFrame(input: InputStream): ByteArray? {
        val header = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = input.read(header, read, 4 - read)
            if (n == -1) {
                if (read == 0) return null
                throw EOFException("帧头不完整")
            }
            read += n
        }
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        val payload = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(payload, offset, length - offset)
            if (n == -1) throw EOFException("帧体不完整")
            offset += n
        }
        return payload
    }

    private fun writeFrame(out: OutputStream, payload: ByteArray) {
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

    /** 启动一个回环服务端：按顺序读 [frameCount] 帧请求，再按 [handler] 回包。 */
    private fun startServerFrames(
        frameCount: Int,
        handler: (requests: List<ByteArray>, out: OutputStream) -> Unit,
    ): Int {
        val srv = ServerSocket(0)
        server = srv
        serverThread = Thread {
            try {
                srv.accept().use { socket ->
                    val requests = mutableListOf<ByteArray>()
                    repeat(frameCount) {
                        val frame = readFrame(socket.inputStream) ?: return@use
                        requests.add(frame)
                    }
                    handler(requests, socket.outputStream)
                }
            } catch (_: Exception) {
                // 测试结束关 socket 时的正常异常。
            }
        }.also { it.isDaemon = true; it.start() }
        return srv.localPort
    }

    private fun helloJson(
        deviceId: String = "remote-host",
        agentsJson: String = """[{"agent_id":"example-agent","display_name":"Example Agent","kind":"cli","capabilities":["dev"],"folder":"/workspace"}]""",
        publicKeyB64: String = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        version: Int = 1,
    ): String {
        val obj = JSONObject()
        obj.put("type", "wherevercode_hello")
        obj.put("version", version)
        obj.put("device_id", deviceId)
        obj.put("display_name", "远程设备")
        obj.put("public_key_b64", publicKeyB64)
        obj.put("agents", org.json.JSONArray(agentsJson))
        return obj.toString()
    }

    @Test
    fun `hello round trip parses device agents and public key`() = runBlocking {
        val port = startServer { _, out ->
            writeFrame(out, helloJson().toByteArray(Charsets.UTF_8))
        }
        val hello = DirectTransport().fetchHello(
            peer.copy(port = port),
            ownDeviceId = "k70",
            ownPublicKey = ByteArray(32) { 3 },
        )
        assertEquals("remote-host", hello.deviceId)
        assertEquals("远程设备", hello.displayName)
        assertEquals(32, hello.publicKey.size)
        assertEquals(1, hello.agents.size)
        assertEquals("example-agent", hello.agents[0].agentId)
        assertEquals("Example Agent", hello.agents[0].displayName)
        assertEquals("cli", hello.agents[0].kind)
        assertEquals("/workspace", hello.agents[0].folder)
    }

    @Test
    fun `hello with client_status PENDING parses to pending`() = runBlocking {
        // V5 修复（2026-11-11，hello 通、run 就 reset）：hello 回包
        // 带本机的配对状态；PENDING 必须原样读出来，探测才能报
        // “待批准”，而不是只看 hello 通就标“远端可用”。
        val obj = JSONObject(helloJson())
        obj.put("client_status", "PENDING")
        val port = startServer { _, out ->
            writeFrame(out, obj.toString().toByteArray(Charsets.UTF_8))
        }
        val hello = DirectTransport().fetchHello(
            peer.copy(port = port),
            ownDeviceId = "k70",
            ownPublicKey = ByteArray(32) { 3 },
        )
        assertEquals("PENDING", hello.clientStatus)
    }

    @Test
    fun `hello with client_status APPROVED parses to approved`() = runBlocking {
        val obj = JSONObject(helloJson())
        obj.put("client_status", "APPROVED")
        val port = startServer { _, out ->
            writeFrame(out, obj.toString().toByteArray(Charsets.UTF_8))
        }
        val hello = DirectTransport().fetchHello(
            peer.copy(port = port),
            ownDeviceId = "k70",
            ownPublicKey = ByteArray(32) { 3 },
        )
        assertEquals("APPROVED", hello.clientStatus)
    }

    @Test
    fun `hello without client_status defaults to empty not pending`() = runBlocking {
        // 旧版服务端没给 client_status：不知道，不得当成 PENDING
        // 挡路，也不得当成 APPROVED；调用方按老样子走。
        val port = startServer { _, out ->
            writeFrame(out, helloJson().toByteArray(Charsets.UTF_8))
        }
        val hello = DirectTransport().fetchHello(
            peer.copy(port = port),
            ownDeviceId = "k70",
            ownPublicKey = ByteArray(32) { 3 },
        )
        assertEquals("", hello.clientStatus)
    }

    @Test
    fun `hello with hand_version parses version and update_supported`() = runBlocking {
        // Hand 自更新（2026-11-12）：hello 回包带远端 Hand 代码版本
        // 与是否支持自更新，App 据此比对、提示更新。
        val obj = JSONObject(helloJson())
        obj.put("hand_version", "237ad2080ea3")
        obj.put("update_supported", true)
        val port = startServer { _, out ->
            writeFrame(out, obj.toString().toByteArray(Charsets.UTF_8))
        }
        val hello = DirectTransport().fetchHello(
            peer.copy(port = port),
            ownDeviceId = "k70",
            ownPublicKey = ByteArray(32) { 3 },
        )
        assertEquals("237ad2080ea3", hello.handVersion)
        assertTrue(hello.updateSupported)
    }

    @Test
    fun `hello without hand_version defaults to empty and false`() = runBlocking {
        // 旧版 Hand 不报版本：不知道，不得当成最新、也不得当成落后。
        val port = startServer { _, out ->
            writeFrame(out, helloJson().toByteArray(Charsets.UTF_8))
        }
        val hello = DirectTransport().fetchHello(
            peer.copy(port = port),
            ownDeviceId = "k70",
            ownPublicKey = ByteArray(32) { 3 },
        )
        assertEquals("", hello.handVersion)
        assertFalse(hello.updateSupported)
    }

    @Test
    fun `hello sends own device id and public key`() = runBlocking {
        var seenRequest: ByteArray? = null
        val port = startServer { request, out ->
            seenRequest = request
            writeFrame(out, helloJson().toByteArray(Charsets.UTF_8))
        }
        DirectTransport().fetchHello(peer.copy(port = port), "k70", ByteArray(32) { 3 })
        val req = JSONObject(String(seenRequest!!, Charsets.UTF_8))
        assertEquals("wherevercode_hello", req.getString("type"))
        assertEquals(1, req.getInt("version"))
        assertEquals("k70", req.getString("device_id"))
        assertTrue(req.getString("public_key_b64").isNotBlank())
    }

    @Test
    fun `hello with wrong version is rejected`() = runBlocking {
        val port = startServer { _, out ->
            writeFrame(out, helloJson(version = 2).toByteArray(Charsets.UTF_8))
        }
        try {
            DirectTransport().fetchHello(peer.copy(port = port), "k70", ByteArray(32) { 3 })
            fail("应拒绝 version != 1 的 hello")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("版本"))
        }
    }

    @Test
    fun `hello with mismatched device id is rejected`() = runBlocking {
        val port = startServer { _, out ->
            writeFrame(out, helloJson(deviceId = "someone-else").toByteArray(Charsets.UTF_8))
        }
        try {
            DirectTransport().fetchHello(peer.copy(port = port), "k70", ByteArray(32) { 3 })
            fail("设备 id 与添加记录不一致时应拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("设备"))
        }
    }

    @Test
    fun `hello with short public key is rejected`() = runBlocking {
        val port = startServer { _, out ->
            writeFrame(
                out,
                helloJson(publicKeyB64 = java.util.Base64.getEncoder().encodeToString(ByteArray(16)))
                    .toByteArray(Charsets.UTF_8),
            )
        }
        try {
            DirectTransport().fetchHello(peer.copy(port = port), "k70", ByteArray(32) { 3 })
            fail("公钥不是 32 字节时应拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("公钥"))
        }
    }

    @Test
    fun `hello with no agents parses to empty list not failure`() = runBlocking {
        val port = startServer { _, out ->
            writeFrame(out, helloJson(agentsJson = "[]").toByteArray(Charsets.UTF_8))
        }
        val hello = DirectTransport().fetchHello(peer.copy(port = port), "k70", ByteArray(32) { 3 })
        assertTrue(hello.agents.isEmpty())
    }

    @Test
    fun `hello repos parse branch and commit for capability cards`() = runBlocking {
        // P1 能力卡（2026-11-11）：Hand 广播的 git 检出进 hello 解析。
        val agentsJson = """[{"agent_id":"example-agent","display_name":"Example Agent","kind":"cli","capabilities":["dev"],"folder":"/workspace/example-project","repos":[{"path":"/workspace/example-project","branch":"main","commit":"abc123456789"}]}]"""
        val port = startServer { _, out ->
            writeFrame(out, helloJson(agentsJson = agentsJson).toByteArray(Charsets.UTF_8))
        }
        val hello = DirectTransport().fetchHello(peer.copy(port = port), "k70", ByteArray(32) { 3 })
        assertEquals(1, hello.agents[0].repos.size)
        assertEquals("/workspace/example-project", hello.agents[0].repos[0].path)
        assertEquals("main", hello.agents[0].repos[0].branch)
        assertEquals("abc123456789", hello.agents[0].repos[0].commit)
    }

    @Test
    fun `hello without repos parses to empty repos not failure`() = runBlocking {
        // 旧版服务端不发 repos：解析成功，repos 为空（未知，不编造）。
        val port = startServer { _, out ->
            writeFrame(out, helloJson().toByteArray(Charsets.UTF_8))
        }
        val hello = DirectTransport().fetchHello(peer.copy(port = port), "k70", ByteArray(32) { 3 })
        assertTrue(hello.agents[0].repos.isEmpty())
    }

    @Test
    fun `hello with malformed repos entry skips only that entry`() = runBlocking {
        // 一条坏 repos 记录绝不能让整个 hello 失败：坏的跳过，好的保留。
        val agentsJson = """[{"agent_id":"example-agent","display_name":"Example Agent","kind":"cli","capabilities":["dev"],"folder":"/workspace","repos":[{"branch":"main"},{"path":"/workspace","branch":"dev","commit":"deadbeefcafe"}]}]"""
        val port = startServer { _, out ->
            writeFrame(out, helloJson(agentsJson = agentsJson).toByteArray(Charsets.UTF_8))
        }
        val hello = DirectTransport().fetchHello(peer.copy(port = port), "k70", ByteArray(32) { 3 })
        assertEquals(1, hello.agents[0].repos.size)
        assertEquals("/workspace", hello.agents[0].repos[0].path)
        assertEquals("dev", hello.agents[0].repos[0].branch)
    }

    @Test
    fun `call sends hello first then sealed, skips hello reply`() = runBlocking {
        // V5 修复（2026-11-11，Grok/CodeBuddy 对话“空响应”）：调用连接
        // 必须先发 hello 帧再发密封信封；服务端先回 hello 回包（明文），
        // 再回密封帧；客户端跳过 hello 回包，只把密封帧交给上层。
        val serverPubKey = ByteArray(32) { 7 }
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(serverPubKey).joinToString("") { "%02x".format(it) }
        var seenRequests: List<ByteArray>? = null
        val port = startServerFrames(2) { requests, out ->
            seenRequests = requests
            writeFrame(
                out,
                helloJson(
                    publicKeyB64 = java.util.Base64.getEncoder().encodeToString(serverPubKey),
                ).toByteArray(Charsets.UTF_8),
            )
            writeFrame(out, "sealed-result".toByteArray(Charsets.UTF_8))
        }
        val conn = DirectTransport()
            .connect(peer.copy(port = port, fingerprintSha256 = fingerprint))
        conn.sendHello("k70", ByteArray(32) { 3 })
        conn.send("ping".toByteArray(Charsets.UTF_8))
        val frames = conn.incoming().toList()
        assertEquals(listOf("sealed-result"), frames.map { String(it, Charsets.UTF_8) })
        // 服务端收到的第一帧是 hello，第二帧才是密封信封。
        val reqs = seenRequests!!
        assertEquals(2, reqs.size)
        val helloReq = JSONObject(String(reqs[0], Charsets.UTF_8))
        assertEquals("wherevercode_hello", helloReq.getString("type"))
        assertEquals("k70", helloReq.getString("device_id"))
        assertEquals("ping", String(reqs[1], Charsets.UTF_8))
        conn.close()
    }

    @Test
    fun `hello reply with wrong device id is rejected`() = runBlocking {
        val port = startServerFrames(2) { _, out ->
            writeFrame(out, helloJson(deviceId = "someone-else").toByteArray(Charsets.UTF_8))
            writeFrame(out, "sealed-result".toByteArray(Charsets.UTF_8))
        }
        val conn = DirectTransport().connect(peer.copy(port = port))
        conn.sendHello("k70", ByteArray(32) { 3 })
        conn.send("ping".toByteArray(Charsets.UTF_8))
        try {
            conn.incoming().toList()
            fail("hello 回包设备 id 不符时应拒绝")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("设备"))
        } finally {
            conn.close()
        }
    }

    @Test
    fun `hello reply with wrong fingerprint is rejected`() = runBlocking {
        // hello 回包的公钥指纹与配对记录不符：fail closed。
        val port = startServerFrames(2) { _, out ->
            writeFrame(
                out,
                helloJson(
                    publicKeyB64 = java.util.Base64.getEncoder()
                        .encodeToString(ByteArray(32) { 8 }),
                ).toByteArray(Charsets.UTF_8),
            )
            writeFrame(out, "sealed-result".toByteArray(Charsets.UTF_8))
        }
        val conn = DirectTransport().connect(peer.copy(port = port))
        conn.sendHello("k70", ByteArray(32) { 3 })
        conn.send("ping".toByteArray(Charsets.UTF_8))
        try {
            conn.incoming().toList()
            fail("hello 回包指纹不符时应拒绝")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("指纹"))
        } finally {
            conn.close()
        }
    }

    @Test
    fun `send then incoming streams frames until server closes`() = runBlocking {
        val port = startServer { request, out ->
            assertEquals("ping", String(request, Charsets.UTF_8))
            writeFrame(out, "frame-1".toByteArray(Charsets.UTF_8))
            writeFrame(out, "frame-2".toByteArray(Charsets.UTF_8))
        }
        val conn = DirectTransport().connect(peer.copy(port = port))
        conn.send("ping".toByteArray(Charsets.UTF_8))
        val frames = conn.incoming().toList()
        assertEquals(listOf("frame-1", "frame-2"), frames.map { String(it, Charsets.UTF_8) })
        conn.close()
    }

    @Test
    fun `incoming without send fails honestly`() = runBlocking {
        val port = startServer { _, _ -> }
        val conn = DirectTransport().connect(peer.copy(port = port))
        try {
            conn.incoming().toList()
            fail("未 send 就读回包应抛错")
        } catch (_: IllegalStateException) {
            // 预期：每连接只发一次请求信封，先 send 再读。
        } finally {
            conn.close()
        }
    }

    @Test
    fun `call falls back to fallback host when preferred host is unreachable`() = runBlocking {
        // V5 公网直连（2026-11-11）：首选地址（公网）连不上时自动
        // 回落备用地址（内网），本次调用继续走通，不用用户手动切换。
        // 首选用文档网段 192.0.2.1（不可达，5 秒连接超时后回落），
        // 回落用本机回环服务端。
        val serverPubKey = ByteArray(32) { 7 }
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(serverPubKey).joinToString("") { "%02x".format(it) }
        val port = startServerFrames(2) { _, out ->
            writeFrame(
                out,
                helloJson(
                    publicKeyB64 = java.util.Base64.getEncoder().encodeToString(serverPubKey),
                ).toByteArray(Charsets.UTF_8),
            )
            writeFrame(out, "sealed-result".toByteArray(Charsets.UTF_8))
        }
        val fallbackPeer = peer.copy(
            host = "192.0.2.1",
            port = port,
            fallbackHost = "127.0.0.1",
            fingerprintSha256 = fingerprint,
        )
        val fallbackLog = mutableListOf<String>()
        val conn = DirectTransport(onFallback = { fallbackLog += it }).connect(fallbackPeer)
        conn.sendHello("k70", ByteArray(32) { 3 })
        conn.send("ping".toByteArray(Charsets.UTF_8))
        val frames = conn.incoming().toList()
        assertEquals(listOf("sealed-result"), frames.map { String(it, Charsets.UTF_8) })
        // 回落被如实记录（不是静默）：一条记录，提到回落。
        assertEquals(1, fallbackLog.size)
        assertTrue(fallbackLog[0].contains("回落"))
        conn.close()
    }

    @Test
    fun `hello falls back to fallback host when preferred host is unreachable`() = runBlocking {
        val port = startServer { _, out ->
            writeFrame(out, helloJson().toByteArray(Charsets.UTF_8))
        }
        val fallbackLog = mutableListOf<String>()
        val hello = DirectTransport(onFallback = { fallbackLog += it }).fetchHello(
            peer.copy(host = "192.0.2.1", port = port, fallbackHost = "127.0.0.1"),
            ownDeviceId = "k70",
            ownPublicKey = ByteArray(32) { 3 },
        )
        assertEquals("remote-host", hello.deviceId)
        assertEquals(1, fallbackLog.size)
        assertTrue(fallbackLog[0].contains("回落"))
    }

    @Test
    fun `both addresses failing reports both failures`() = runBlocking {
        // 两路都拒接（同一已关闭端口）：错误里须同时提到两路都失败，
        // 而不是只报首选、让用户以为没试过回落。
        val closedPort = ServerSocket(0).also { it.close() }.localPort
        val fallbackLog = mutableListOf<String>()
        try {
            DirectTransport(onFallback = { fallbackLog += it }).connect(
                peer.copy(host = "127.0.0.1", port = closedPort, fallbackHost = "127.0.0.2"),
            )
            fail("两路都连不上时应抛错")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("均连接失败"))
        }
        assertEquals(1, fallbackLog.size)
    }

    @Test
    fun `connect to closed port fails with io error not silent empty`() = runBlocking {
        val closedPort = ServerSocket(0).also { it.close() }.localPort
        try {
            DirectTransport().connect(peer.copy(port = closedPort))
            fail("连不上时应抛错，不静默返回空连接")
        } catch (_: java.io.IOException) {
            // 预期。
        }
    }

    @Test
    fun `oversized frame from server is rejected`() = runBlocking {
        val port = startServer { _, out ->
            // 帧头谎报 5MB（上限 4MB），不发帧体。
            out.write(byteArrayOf(0, 80, 0, 0))
            out.flush()
        }
        val conn = DirectTransport().connect(peer.copy(port = port))
        conn.send("ping".toByteArray(Charsets.UTF_8))
        try {
            conn.incoming().toList()
            fail("超大帧应拒绝")
        } catch (_: java.io.IOException) {
            // 预期。
        } finally {
            conn.close()
        }
    }

    @Test
    fun `discover returns no peers for direct transport`() = runBlocking {
        // 直连设备是手动添加的，不走 mDNS：discover 永远为空，
        // 设备列表由 RemoteDeviceStore 提供，不伪造发现结果。
        assertTrue(DirectTransport().discover(100).isEmpty())
    }

    @Test
    fun `hello socket timeout is bounded`() {
        // 占住端口但永不回包：fetchHello 必须在读超时内失败，而不是无限等。
        val srv = ServerSocket(0)
        server = srv
        serverThread = Thread {
            try {
                srv.accept().use { Thread.sleep(60_000) }
            } catch (_: Exception) {
            }
        }.also { it.isDaemon = true; it.start() }
        val started = System.currentTimeMillis()
        try {
            runBlocking {
                DirectTransport().fetchHello(peer.copy(port = srv.localPort), "k70", ByteArray(32) { 3 })
            }
            fail("服务端不回包时应在超时内失败")
        } catch (_: Exception) {
            val elapsed = System.currentTimeMillis() - started
            assertTrue("hello 应在 35 秒内超时，实际 ${elapsed}ms", elapsed < 35_000)
        }
    }

    @Test
    fun `call connection survives a slow first frame beyond the hello timeout`() = runBlocking {
        // V5 修复（2026-11-11，CodeBuddy“在吗”两次“传输中断：
        // Read timed out”）：服务端只在 CLI 出 stdout 行时才发进度帧，
        // CodeBuddy 冷启动约 43 秒无任何输出；调用连接的读超时若还是
        // hello 的 30 秒，首帧到达前就断了。这里服务端 40 秒后才回
        // 第一帧（已超过旧的 30 秒读超时）：旧实现在 30 秒抛
        // SocketTimeoutException，新实现须正常收到该帧。
        // 注意：服务端不回 hello 回包，第一帧即密封帧（容忍分支）。
        val port = startServerFrames(2) { _, out ->
            Thread.sleep(40_000)
            writeFrame(out, "slow-first-frame".toByteArray(Charsets.UTF_8))
        }
        val conn = DirectTransport().connect(peer.copy(port = port))
        conn.sendHello("k70", ByteArray(32) { 3 })
        conn.send("ping".toByteArray(Charsets.UTF_8))
        val frames = conn.incoming().toList()
        assertEquals(listOf("slow-first-frame"), frames.map { String(it, Charsets.UTF_8) })
        conn.close()
    }
}
