package wherevercode.agent.core.mcp

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import wherevercode.agent.core.security.DeviceKeys
import wherevercode.agent.core.security.PeerTrustChecker
import wherevercode.agent.core.transport.DirectTransport
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * D2 集成验证（2026-11-12）：App → 真 Hand 授权全流程，真实密封连接。
 *
 * 本地起本仓库的 server/hand_server.py（不动 live Hand、不部署），
 * 走 App 自己的 HandClient + DirectTransport + 密封信封路径：
 *  1. 阴性对照：未授权时拿项目文件夹列目录 → Blocked wrong_folder；
 *  2. authorizeProjectFolder → Result authorized=true, already=false；
 *  3. 读 state dir 的 auth.json → authorized_project_folders 真进了项目文件夹；
 *  4. 再拿项目文件夹跑一次 run（/bin/echo）→ Result 输出含 prompt，
 *     证明 verify_credential 不再报 wrong_folder。
 *
 * 两边单测各自绿不算数——本测试是唯一覆盖 App 信封形状与 Hand 落盘
 * 拼起来的测试。Hand 服务路径由系统属性 v5.hand.server.py 指定，
 * 默认仓库 server/hand_server.py；
 * 文件不存在时直接失败（绝不静默跳过，否则验证是假的）。
 */
class AuthorizeProjectFolderE2ETest {

    private val ownKeyPair = DeviceKeys.generateKeyPair()
    private lateinit var root: File
    private lateinit var agentDir: File
    private lateinit var projectDir: File
    private lateinit var stateDir: File
    private var serverProcess: Process? = null
    private var port: Int = 0

    private fun serverScript(): File {
        val fromProp = System.getProperty("v5.hand.server.py")
        val repoRoot = File(System.getProperty("user.dir")).let { cwd ->
            generateSequence(cwd) { it.parentFile }
                .firstOrNull { File(it, "server/hand_server.py").isFile }
        }
        val candidates = listOfNotNull(
            fromProp?.takeIf { it.isNotBlank() }?.let { File(it) },
            repoRoot?.let { File(it, "server/hand_server.py") },
        )
        val found = candidates.firstOrNull { it.isFile }
        return found ?: throw AssertionError(
            "Hand 服务端文件不存在，集成验证无法跑（试过：${candidates.joinToString { it.absolutePath }}；" +
                "可用 -Dv5.hand.server.py=<路径> 指定）",
        )
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("v5-e2e-hand-").toFile()
        agentDir = File(root, "fenshen").apply { mkdirs() }
        projectDir = File(root, "wherevercode-agent").apply { mkdirs() }
        stateDir = File(root, "state").apply { mkdirs() }
        val agentCanon = agentDir.canonicalPath
        val nowSeconds = System.currentTimeMillis() / 1000
        File(stateDir, "agents.json").writeText(
            JSONObject().apply {
                put("e2e-agent", JSONObject().apply {
                    put("display_name", "E2E Agent")
                    put("kind", "agent")
                    put("folder", agentCanon)
                    put("command", org.json.JSONArray().apply {
                        put("/bin/echo")
                        put("{prompt}")
                    })
                })
            }.toString(),
        )
        File(stateDir, "auth.json").writeText(
            JSONObject().apply {
                put("e2e-agent", org.json.JSONArray().apply { put(agentCanon) })
            }.toString(),
        )
        File(stateDir, "paired_clients.json").writeText(
            JSONObject().apply {
                put("k70", JSONObject().apply {
                    put("public_key_b64", b64(ownKeyPair.publicKey))
                    put("status", "APPROVED")
                    put("added_at", nowSeconds)
                })
            }.toString(),
        )
        port = freePort()
        val process = ProcessBuilder(
            "python3",
            serverScript().absolutePath,
            "--state-dir", stateDir.absolutePath,
            "--device-id", "test-hand",
            "--display-name", "TestHand",
            "--host", "127.0.0.1",
            "--port", port.toString(),
            "serve",
        ).redirectErrorStream(true).start()
        serverProcess = process
        // 等服务端 ready（打印 listening on），最多 30 秒。
        val deadline = System.currentTimeMillis() + 30_000
        val reader = process.inputStream.bufferedReader()
        var ready = false
        while (System.currentTimeMillis() < deadline) {
            if (!reader.ready()) {
                if (!process.isAlive) {
                    fail("Hand 服务端启动即退出，集成验证无法跑")
                }
                Thread.sleep(100)
                continue
            }
            val line = reader.readLine() ?: break
            if (line.contains("listening on")) {
                ready = true
                break
            }
        }
        if (!ready) {
            fail("Hand 服务端 30 秒内未 ready（未打印 listening on）")
        }
    }

    @After
    fun tearDown() {
        try {
            serverProcess?.destroy()
            serverProcess?.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        // V5_E2E_KEEP=1 时保留现场（state dir 含 server_key.json 与 audit.jsonl），供排障。
        if (System.getenv("V5_E2E_KEEP") != "1") {
            root.deleteRecursively()
        } else {
            println("[e2e] kept state dir: ${stateDir.absolutePath} root: ${root.absolutePath}")
        }
    }

    private fun serverPublicKey(): ByteArray {
        val keyFile = File(stateDir, "server_key.json")
        assertTrue("服务端应已生成 server_key.json", keyFile.isFile)
        val pubB64 = JSONObject(keyFile.readText()).getString("public_key_b64")
        return Base64.getDecoder().decode(pubB64)
    }

    private fun serverFingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(serverPublicKey()).joinToString("") { "%02x".format(it) }
    }

    private inner class TestTrustChecker(private val serverKey: ByteArray) : PeerTrustChecker {
        override fun isTrusted(peer: DiscoveredPeer): Boolean = true
        override fun trustFailureReason(peer: DiscoveredPeer): String? = null
        override fun peerPublicKey(deviceId: String): ByteArray? = serverKey
    }

    private fun chatPort(): HandRemoteAgentChatPort {
        return HandRemoteAgentChatPort(
            transport = DirectTransport(),
            ownDeviceId = "k70",
            ownKeyPair = ownKeyPair,
            trustChecker = TestTrustChecker(serverPublicKey()),
            nowEpochSeconds = { System.currentTimeMillis() / 1000 },
        )
    }

    private fun testPeer(): DiscoveredPeer = DiscoveredPeer(
        deviceId = "test-hand",
        displayName = "TestHand",
        host = "127.0.0.1",
        port = port,
        // 必须是服务端真指纹（server_key.json 公钥的 SHA-256）：
        // DirectTransport.verifyHelloReply 会核对它，填假指纹时
        // hello 回包直接被拒、调用走传输中断分支，测不到 Hand。
        fingerprintSha256 = serverFingerprint(),
        capabilities = listOf("hand"),
    )

    private fun authProjectFolders(): List<String> {
        val auth = JSONObject(File(stateDir, "auth.json").readText())
        val arr = auth.optJSONArray("e2e-agent") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    @Test(timeout = 120_000)
    fun `authorize then run with the project folder over a real sealed connection`() = runBlocking {
        val t0 = System.currentTimeMillis()
        fun mark(step: String) {
            val line = "[e2e] +${System.currentTimeMillis() - t0}ms " + step.toByteArray(Charsets.UTF_8).joinToString("") {
                if (it in 32..126) it.toInt().toChar().toString() else "?"
            }
            println(line)
            try {
                File("/tmp/e2e-diag.txt").appendText(line + "\n")
            } catch (_: Exception) {
            }
        }
        val chatPort = chatPort()
        val agentTarget = RemoteTarget(peer = testPeer(), agentId = "e2e-agent", folder = agentDir.canonicalPath)
        val projectTarget = RemoteTarget(peer = testPeer(), agentId = "e2e-agent", folder = projectDir.canonicalPath)

        // 1. 阴性对照：授权前拿项目文件夹列目录，应被 wrong_folder 拒绝。
        val before = chatPort.listFolder(projectTarget, "e2e-task-before", "").toList()
        mark("negative-control list returned: $before")
        val beforeBlocked = checkNotNull(before.filterIsInstance<HandEvent.Blocked>().firstOrNull()) {
            "授权前列项目文件夹应被拒绝，实际事件：$before"
        }
        assertEquals(HandProtocol.Errors.WRONG_FOLDER, beforeBlocked.code)

        // 2. 授权：信封 folder 必须是项目文件夹（修法点），凭证随之绑它。
        val authorized = chatPort.authorizeProjectFolder(agentTarget, projectDir.canonicalPath).toList()
        mark("authorize returned: $authorized")
        val result = checkNotNull(authorized.filterIsInstance<HandEvent.Result>().firstOrNull()) {
            "授权应回 Result，实际事件：$authorized"
        }
        val output = JSONObject(result.output)
        assertTrue("授权应成功：${result.output}", output.optBoolean("authorized", false))
        assertTrue("首次授权 already 应为 false（否则是把已授权文件夹再授权一遍的假成功）：${result.output}", !output.optBoolean("already", true))
        assertEquals(projectDir.canonicalPath, output.optString("folder"))

        // 3. 落盘核实：auth.json 的 authorized_project_folders 真进了项目文件夹。
        assertTrue(
            "auth.json 应含项目文件夹，实际：${authProjectFolders()}",
            authProjectFolders().contains(projectDir.canonicalPath),
        )

        // 4. 派活校验：拿项目文件夹跑一次 run，verify_credential 不报 wrong_folder。
        val ran = chatPort.runAgent(projectTarget, "e2e-task-run", "e2e-ping", 60, 1, 1).toList()
        mark("run returned: $ran")
        val runBlocked = ran.filterIsInstance<HandEvent.Blocked>().firstOrNull()
        if (runBlocked != null) {
            fail("项目文件夹派活应成功，实际被拒绝：${runBlocked.code} ${runBlocked.reason}")
        }
        val runResult = checkNotNull(ran.filterIsInstance<HandEvent.Result>().firstOrNull()) {
            "派活应回 Result，实际事件：$ran"
        }
        assertTrue("run 输出应含 prompt 原文，实际：${runResult.output}", runResult.output.contains("e2e-ping"))
    }
}
