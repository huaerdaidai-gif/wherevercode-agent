package wherevercode.agent.core.install

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * SSH 一键安装：App 经 SSH 连上目标机、上传安装包、跑安装脚本。
 *
 * 安全（§25.2）：
 * - 密码/私钥只放内存、用完即清，绝不持久化、绝不明文存盘。
 * - 主机密钥首次连接时向用户展示 SHA-256 指纹、由用户确认才继续
 *   （TOFU）；拒绝即断开，不静默信任。
 * - 每次 SSH 连接须用户在设备管理里显式点“连接并安装”才发起。
 */
data class SshExecResult(val exitCode: Int, val stdout: String, val stderr: String)

/** 一条 SSH 会话（可单测：假实现；真实现用 JSch）。 */
interface SshSession {
    fun upload(bytes: ByteArray, remotePath: String, mode: Int)
    fun exec(command: String): SshExecResult
    fun close()
}

/** 建 SSH 会话的工厂（可单测：假实现）。 */
interface SshSessionFactory {
    /**
     * @param password 密码认证（可空）；与 privateKey 二选一。
     * @param privateKey 私钥内容（PEM/OpenSSH，可空）；与 password 二选一。
     * @param privateKeyPassphrase 私钥口令（可空）。
     * @param onHostKey 未知主机密钥时回调（指纹 SHA-256 hex）；返回 true 才继续。
     */
    @Throws(Exception::class)
    fun connect(
        target: SshTarget,
        password: CharArray?,
        privateKey: ByteArray?,
        privateKeyPassphrase: CharArray?,
        onHostKey: (fingerprintSha256: String) -> Boolean,
    ): SshSession
}

/** 一键安装结果。 */
sealed class InstallOutcome {
    data class Installed(
        val deviceId: String,
        val fingerprint: String,
        val port: Int,
        val version: String,
        val host: String,
    ) : InstallOutcome()

    data class Failed(val message: String) : InstallOutcome()
}

object SshInstaller {
    /** 远端临时安装包目录前缀（/tmp 下，装完尽量删）。 */
    const val REMOTE_BUNDLE_PREFIX: String = "/tmp/wherevercode-v5-hand-install-"

    /** 安装脚本成功时打印的行前缀。 */
    const val INSTALLED_PREFIX: String = "V5_HAND_INSTALLED"

    /** 从安装脚本输出里解析 V5_HAND_INSTALLED 行（key=value 空格分隔）。 */
    fun parseInstalledLine(output: String): Map<String, String>? {
        val line = output.lines().firstOrNull { it.trimStart().startsWith(INSTALLED_PREFIX) }
            ?: return null
        val rest = line.substringAfter(INSTALLED_PREFIX).trim()
        if (rest.isEmpty()) return null
        val map = mutableMapOf<String, String>()
        for (part in rest.split(" ")) {
            val eq = part.indexOf('=')
            if (eq > 0) map[part.substring(0, eq)] = part.substring(eq + 1)
        }
        return map.ifEmpty { null }
    }

    /**
     * 经 SSH 一键安装。
     * 流程：连接 → 传安装包到远端临时目录 → 跑安装脚本 → 解析结果行。
     * 任何一步失败即 Failed（附最后输出，不伪造成功）。
     */
    fun install(
        factory: SshSessionFactory,
        target: SshTarget,
        password: CharArray?,
        privateKey: ByteArray?,
        privateKeyPassphrase: CharArray?,
        bundle: HandInstallBundle,
        port: Int,
        deviceId: String?,
        displayName: String?,
        onHostKey: (fingerprintSha256: String) -> Boolean,
        onProgress: (String) -> Unit,
    ): InstallOutcome {
        val targetErrors = target.validate()
        if (targetErrors.isNotEmpty()) return InstallOutcome.Failed(targetErrors.joinToString("；"))
        val remoteDir = REMOTE_BUNDLE_PREFIX + (0..7).map { "0123456789abcdef".random() }.joinToString("")
        var session: SshSession? = null
        return try {
            onProgress("正在连接 ${target.host}:${target.port}…")
            session = factory.connect(target, password, privateKey, privateKeyPassphrase, onHostKey)
            onProgress("已连接，正在上传安装包…")
            session.exec("mkdir -p $remoteDir")
            session.upload(bundle.installSh, "$remoteDir/v5-install-hand.sh", 0x1c0)
            session.upload(bundle.uninstallSh, "$remoteDir/v5-uninstall-hand.sh", 0x1c0)
            session.upload(bundle.handServerPy, "$remoteDir/hand_server.py", 0x180)
            onProgress("安装包已上传，正在安装 Hand…")
            val command = InstallCommand.sshInstallCommand(
                version = bundle.version,
                remoteBundleDir = remoteDir,
                port = port,
                deviceId = deviceId,
                displayName = displayName,
            )
            val result = session.exec(command)
            val parsed = parseInstalledLine(result.stdout + "\n" + result.stderr)
            if (result.exitCode == 0 && parsed != null) {
                val fingerprint = parsed["fingerprint"].orEmpty()
                val installedDeviceId = parsed["device_id"].orEmpty()
                val installedPort = parsed["port"]?.toIntOrNull() ?: port
                if (fingerprint.length == 64 && installedDeviceId.isNotBlank()) {
                    InstallOutcome.Installed(
                        deviceId = installedDeviceId,
                        fingerprint = fingerprint,
                        port = installedPort,
                        version = parsed["version"] ?: bundle.version,
                        host = target.host,
                    )
                } else {
                    InstallOutcome.Failed("安装脚本说成功了，但没给可用的设备 id / 指纹（输出：${tailOf(result.stdout, 500)}）")
                }
            } else {
                val tail = tailOf(result.stdout + "\n" + result.stderr, 800)
                InstallOutcome.Failed("安装失败（exit=${result.exitCode}）：$tail")
            }
        } catch (e: Exception) {
            InstallOutcome.Failed("安装失败：${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                session?.exec("rm -rf $remoteDir")
            } catch (_: Exception) {
            }
            try {
                session?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun tailOf(text: String, maxChars: Int): String {
        val t = text.trim()
        return if (t.length <= maxChars) t else "…" + t.takeLast(maxChars)
    }
}

/** 主机密钥仓库：未知密钥回调用户确认（TOFU），只记本次会话、不落盘。 */
class CallbackHostKeyRepository(
    private val onUnknownHostKey: (fingerprintSha256: String) -> Boolean,
) : HostKeyRepository {
    /** 主机 → 原始密钥字节（直接存字节，不走 HostKey.getKey() 的 String）。 */
    private val known = LinkedHashMap<String, ByteArray>()

    override fun check(host: String, key: ByteArray): Int {
        val existing = known[host]
        if (existing != null) {
            return if (existing.contentEquals(key)) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02x".format(it) }
        return if (onUnknownHostKey(fingerprint)) {
            known[host] = key.copyOf()
            HostKeyRepository.OK
        } else {
            HostKeyRepository.NOT_INCLUDED
        }
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        // HostKey.getKey() 是 base64 字符串；add 不由本仓库主动用，忽略即可。
    }

    override fun remove(host: String, type: String?) {
        known.remove(host)
    }

    override fun remove(host: String, type: String?, key: ByteArray?) {
        known.remove(host)
    }

    override fun getKnownHostsRepositoryID(): String = "wherevercode-v5-install-memory"

    override fun getHostKey(): Array<HostKey> = known.mapNotNull { (host, key) ->
        try {
            HostKey(host, key)
        } catch (_: Exception) {
            null
        }
    }.toTypedArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> {
        val key = host?.let { known[it] } ?: return emptyArray()
        return try {
            arrayOf(HostKey(host, key))
        } catch (_: Exception) {
            emptyArray()
        }
    }
}

/** JSch 实现的 SSH 会话。 */
class JschSshSession(private val session: Session) : SshSession {
    override fun upload(bytes: ByteArray, remotePath: String, mode: Int) {
        val channel = session.openChannel("sftp") as ChannelSftp
        try {
            channel.connect(15000)
            channel.put(ByteArrayInputStream(bytes), remotePath)
            channel.chmod(mode, remotePath)
        } finally {
            try {
                channel.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    override fun exec(command: String): SshExecResult {
        val channel = session.openChannel("exec") as ChannelExec
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        try {
            channel.setCommand(command)
            channel.inputStream = null
            channel.setOutputStream(stdout)
            channel.setErrStream(stderr)
            channel.connect(15000)
            while (!channel.isClosed) {
                Thread.sleep(100)
            }
            return SshExecResult(
                exitCode = channel.exitStatus,
                stdout = stdout.toString(Charsets.UTF_8),
                stderr = stderr.toString(Charsets.UTF_8),
            )
        } finally {
            try {
                channel.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    override fun close() {
        try {
            session.disconnect()
        } catch (_: Exception) {
        }
    }
}

/** JSch 实现的会话工厂。 */
class JschSshSessionFactory : SshSessionFactory {
    override fun connect(
        target: SshTarget,
        password: CharArray?,
        privateKey: ByteArray?,
        privateKeyPassphrase: CharArray?,
        onHostKey: (fingerprintSha256: String) -> Boolean,
    ): SshSession {
        val jsch = JSch()
        jsch.setHostKeyRepository(CallbackHostKeyRepository(onHostKey))
        if (privateKey != null) {
            val passphrase = privateKeyPassphrase?.let { String(it).toByteArray(Charsets.UTF_8) }
            jsch.addIdentity("v5-install-key", privateKey, null, passphrase)
        }
        val session: Session = jsch.getSession(target.username, target.host, target.port)
        if (password != null) {
            session.setPassword(String(password))
        }
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (privateKey != null) "publickey" else "password")
        session.setTimeout(30000)
        try {
            session.connect(30000)
        } catch (e: JSchException) {
            try {
                session.disconnect()
            } catch (_: Exception) {
            }
            throw e
        }
        return JschSshSession(session)
    }
}
