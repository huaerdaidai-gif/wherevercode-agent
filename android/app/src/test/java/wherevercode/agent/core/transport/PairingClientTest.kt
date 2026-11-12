package wherevercode.agent.core.transport

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Base64
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import wherevercode.agent.core.security.DeviceKeys

/**
 * 密封配对确认（PairingClient）的测试。
 *
 * 联网用例跑的是**真实的 Hand**（仓库里的 hand_server.py，Python 子进程）：
 * Kotlin 客户端 ↔ Python 服务端的密封配对确认全流程。
 * 回包解读（批准/码错/旧版 Hand）是纯函数，直接单测，不走网络。
 *
 * 沙箱限制（实测三重证实）：本机 VM 拦 JVM 的 loopback TCP——Java 的
 * ServerSocket.accept() 永不返回，Java 客户端的连接也到不了 Python
 * 服务端（Hand 审计日志里根本没见到连接），同机 Python 回环正常。
 * 所以联网用例先跑 [assumeLoopback] 探针：2 秒内 Java 回环 accept
 * 通了才跑，通不过就跳过（JUnit Assume，记 skipped 不记失败）——
 * CI（GitHub Actions）里回环正常，测试照常跑；本地跳过不是通过。
 */
class PairingClientTest {

    /** 真 Hand（子进程 + 临时 state dir），每个用例一个全新未配对状态。 */
    private class RealHand(val port: Int) : AutoCloseable {
        val pairCode: String
        val serverPublicKey: ByteArray
        val process: Process
        val stateDir: File

        init {
            val handPath = findHandScript()
            stateDir = Files.createTempDirectory("pairing-test-hand-").toFile()
            process = ProcessBuilder(
                "python3", handPath,
                "--state-dir", stateDir.absolutePath,
                "--device-id", "test-hand",
                "--display-name", "Test Hand",
                "--host", "127.0.0.1",
                "--port", port.toString(),
                "serve",
            ).redirectErrorStream(true).start()
            // 等 Hand 打印配对码（即已监听）；20 秒不到就当启动失败。
            // 失败时把 Hand 已吐的输出拼进异常消息——CI 上没有测试报告
            // artifact，控制台日志是唯一的诊断口，绝不能盲修。
            val reader = process.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + 20_000
            var code: String? = null
            val seen = mutableListOf<String>()
            while (System.currentTimeMillis() < deadline) {
                if (!reader.ready()) {
                    if (!process.isAlive) break
                    Thread.sleep(100)
                    continue
                }
                val line = reader.readLine() ?: break
                seen.add(line)
                if (line.startsWith("pair code: ")) {
                    code = Regex("""(\d{6})""").find(line)?.groupValues?.get(1)
                    break
                }
            }
            pairCode = code ?: throw IllegalStateException(
                "真 Hand 20 秒内没打印配对码（$handPath，工作目录 ${File(".").absolutePath}，" +
                    "进程存活=${process.isAlive}）。Hand 输出：\n" +
                    seen.takeLast(20).joinToString("\n"),
            )
            serverPublicKey = readServerPublicKey(stateDir)
        }

        override fun close() {
            try {
                process.destroy()
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: Exception) {
            }
            try {
                stateDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }

        companion object {
            fun start(): RealHand {
                val port = ServerSocket(0).use { it.localPort }
                return RealHand(port)
            }

            private fun readServerPublicKey(stateDir: File): ByteArray {
                val keyFile = File(stateDir, "server_key.json")
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline) {
                    if (keyFile.exists()) {
                        val obj = JSONObject(keyFile.readText())
                        return Base64.getDecoder().decode(obj.getString("public_key_b64"))
                    }
                    Thread.sleep(100)
                }
                throw IllegalStateException("真 Hand 没生成 server_key.json")
            }

            /** 找仓库里的 hand_server.py：Gradle（android/ 为工作目录）与本地管线（仓库根为工作目录）都兼顾。
             * CI 里单元测试的工作目录不可靠（同仓库的 PythonUnittestAndCwdRegressionTest
             * 就是为这事立的回归），所以从 user.dir 逐级向上找，两条相对路径都试。
             */
            private fun findHandScript(): String {
                val prop = System.getProperty("wherevercode.hand.path", "")
                if (prop.isNotBlank()) {
                    val file = File(prop)
                    if (file.isFile) return file.absolutePath
                }
                var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
                while (dir != null) {
                    for (relative in listOf(
                        "app/src/main/assets/install/hand_server.py",
                        "android/app/src/main/assets/install/hand_server.py",
                    )) {
                        val file = File(dir, relative)
                        if (file.isFile) return file.absolutePath
                    }
                    dir = dir.parentFile
                }
                throw IllegalStateException(
                    "找不到 hand_server.py（工作目录 ${File(".").absolutePath}，" +
                        "已从该目录逐级向上找 app/src/main/assets/install/hand_server.py 与 " +
                        "android/app/src/main/assets/install/hand_server.py）",
                )
            }
        }
    }

    /**
     * 联网用例的探针：JVM 回环 accept 2 秒内通了才跑，通不过就跳过
     * （Assume，记 skipped）。不在 @BeforeClass 里做——纯函数用例
     * （回包解读、配对码格式、连不上）本地也能跑，不能跟着跳过。
     */
    private fun assumeLoopback() {
        Assume.assumeTrue("JVM loopback 被沙箱拦住，联网用例跳过（CI 里跑）", loopbackWorks())
    }

    private fun loopbackWorks(): Boolean {
        return try {
            val server = ServerSocket(0)
            val port = server.localPort
            var accepted = false
            val t = thread(isDaemon = true) {
                try {
                    server.accept().close()
                    accepted = true
                } catch (_: Exception) {
                } finally {
                    try {
                        server.close()
                    } catch (_: Exception) {
                    }
                }
            }
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 1000)
                }
            } catch (_: Exception) {
                return false
            }
            t.join(2000)
            accepted
        } catch (_: Exception) {
            false
        }
    }

    private fun peer(hand: RealHand) = DiscoveredPeer(
        deviceId = "test-hand",
        displayName = "Test Hand",
        host = "127.0.0.1",
        port = hand.port,
        fingerprintSha256 = "0".repeat(64),
        capabilities = listOf("hand"),
    )

    @Test
    fun `pair code must be six digits`() {
        assertTrue(PairingClient.isValidPairCode("123456"))
        assertFalse(PairingClient.isValidPairCode("12345"))
        assertFalse(PairingClient.isValidPairCode("1234567"))
        assertFalse(PairingClient.isValidPairCode("12345a"))
        assertFalse(PairingClient.isValidPairCode(""))
    }

    @Test
    fun `right code is approved by the real hand`() {
        assumeLoopback()
        RealHand.start().use { hand ->
            val phone = DeviceKeys.generateKeyPair()
            val result = runBlocking {
                PairingClient.sendPairConfirm(
                    peer(hand), "phone-test", phone.privateKey, hand.serverPublicKey, hand.pairCode,
                )
            }
            assertEquals(PairConfirmResult.Approved, result)
        }
    }

    @Test
    fun `wrong code is rejected with the server reason`() {
        assumeLoopback()
        RealHand.start().use { hand ->
            val phone = DeviceKeys.generateKeyPair()
            val wrong = if (hand.pairCode != "000000") "000000" else "111111"
            val result = runBlocking {
                PairingClient.sendPairConfirm(
                    peer(hand), "phone-test", phone.privateKey, hand.serverPublicKey, wrong,
                )
            }
            assertTrue(result is PairConfirmResult.Rejected)
            assertTrue((result as PairConfirmResult.Rejected).reason.contains("配对码不对"))
        }
    }

    @Test
    fun `reply interpreter approves a pair approved frame`() {
        val reply = JSONObject().apply {
            put("type", "cross_device_pair_approved")
            put("args", JSONObject().apply { put("device_id", "test-hand") })
        }
        assertEquals(PairConfirmResult.Approved, PairingClient.interpretPairReply(reply))
    }

    @Test
    fun `reply interpreter rejects a wrong pair code with the server reason`() {
        val reply = JSONObject().apply {
            put("type", "cross_device_error")
            put("args", JSONObject().apply {
                put("code", "pair_code_wrong")
                put("message", "配对码不对（已错 1 次，错 5 次锁定 1 小时）")
            })
        }
        val result = PairingClient.interpretPairReply(reply)
        assertTrue(result is PairConfirmResult.Rejected)
        assertTrue((result as PairConfirmResult.Rejected).reason.contains("配对码不对"))
    }

    @Test
    fun `old hand without pair confirm is a clear failure not a silent one`() {
        val reply = JSONObject().apply {
            put("type", "cross_device_error")
            put("args", JSONObject().apply {
                put("code", "cross_device_not_authorized")
                put("message", "远端尚未批准此手机")
            })
        }
        val result = PairingClient.interpretPairReply(reply)
        assertTrue(result is PairConfirmResult.Failed)
        assertTrue((result as PairConfirmResult.Failed).reason.contains("太旧"))
    }

    @Test
    fun `reply interpreter fails on an unknown reply type`() {
        val reply = JSONObject().apply { put("type", "cross_device_something_else") }
        val result = PairingClient.interpretPairReply(reply)
        assertTrue(result is PairConfirmResult.Failed)
        assertTrue((result as PairConfirmResult.Failed).reason.contains("无法识别"))
    }

    @Test
    fun `unreachable hand fails with reason`() {
        val phone = DeviceKeys.generateKeyPair()
        val server = DeviceKeys.generateKeyPair()
        val deadPeer = DiscoveredPeer(
            deviceId = "test-hand",
            displayName = "Test Hand",
            host = "127.0.0.1",
            port = 1,
            fingerprintSha256 = "0".repeat(64),
            capabilities = listOf("hand"),
        )
        val result = runBlocking {
            PairingClient.sendPairConfirm(
                deadPeer, "phone-test", phone.privateKey, server.publicKey, "123456",
            )
        }
        assertTrue(result is PairConfirmResult.Failed)
    }
}
