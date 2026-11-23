package wherevercode.agent.core.platform

import android.content.Context
import java.io.File
import java.net.URI

/**
 * 本地平台运行时（频道三层一期·本地层）：App 内嵌平台服务的唯一
 * 持有者。MainActivity.onCreate 里 [install] 一次，之后频道链路用
 * [LOCAL_HOST] 这个伪主机走 [LocalFirstPlatformHttpClient] 在进程内
 * 直达 [LocalChannelService]——不经网络、不填地址、不要令牌。
 *
 * 远程平台仍是原来的路：设置里存了远程凭证时频道端点优先远程，
 * 本地层只是新装/未配置时的底座（平台自由切换是三层三期）。
 */
object LocalPlatform {
    /** 本地平台伪主机（只在本机进程内有意义，永远不会真的解析 DNS）。 */
    const val LOCAL_HOST: String = "local.platform"

    /** 本地调用凭证（进程内固定值，不是秘密；只用来区分本机 App 身份）。 */
    const val LOCAL_TOKEN: String = "local-app-token"

    /** 本机 App 在本地频道里的成员身份（与频道成员表 buildChannelMembers 同 id）。 */
    const val APP_AGENT_ID: String = "local-app"
    const val APP_AGENT_NAME: String = "本机 App"

    @Volatile
    private var service: LocalChannelService? = null

    /** 安装本地服务（幂等；已装直接返回）。须在任何频道调用之前完成。 */
    fun install(context: Context) {
        if (service != null) return
        synchronized(this) {
            if (service != null) return
            val file = File(context.applicationContext.filesDir, "local_platform_channels.json")
            service = LocalChannelService(FileLocalChannelPersistence(file))
            val tokenFile = File(context.applicationContext.filesDir, "lan_platform_tokens.json")
            val tokenPersistence = FileLocalChannelPersistence(tokenFile)
            lanRegistry = LanTokenRegistry(
                load = { tokenPersistence.load() },
                save = { tokenPersistence.save(it) },
            )
        }
    }

    /** 测试专用：直接装入一个服务实例。 */
    fun installForTest(instance: LocalChannelService) {
        service = instance
    }

    fun serviceOrNull(): LocalChannelService? = service

    fun isLocalHost(host: String?): Boolean =
        host?.trim()?.equals(LOCAL_HOST, ignoreCase = true) == true

    fun isLocalEndpoint(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return false
        val parsed = try {
            URI(raw)
        } catch (_: Exception) {
            return false
        }
        return parsed.host?.equals(LOCAL_HOST, ignoreCase = true) == true
    }

    fun defaultChannelId(): String? = serviceOrNull()?.defaultChannelId()?.takeIf { it.isNotBlank() }

    // ---- 内网层（频道三层二期） ------------------------------------------
    // 宿主侧：把本地账本经 LanPlatformServer 挂到局域网。默认关，
    // 设置里手动开；进程被杀即停，不假装常驻。

    @Volatile
    private var lanRegistry: LanTokenRegistry? = null

    @Volatile
    private var lanServer: LanPlatformServer? = null

    /** 内网成员令牌注册表（install 后可用；未 install 返回 null）。 */
    fun lanRegistryOrNull(): LanTokenRegistry? = lanRegistry

    /** 内网服务是否正在监听。 */
    fun lanServerRunning(): Boolean = lanServer?.isRunning == true

    /** 启动内网服务（幂等）；返回是否已在监听。 */
    @Synchronized
    fun startLanServer(): Boolean {
        val registry = lanRegistry ?: return false
        val existing = lanServer
        if (existing != null && existing.isRunning) return true
        val server = existing ?: LanPlatformServer({ service }, registry).also { lanServer = it }
        return try {
            server.start()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 停止内网服务（幂等）。 */
    @Synchronized
    fun stopLanServer() {
        lanServer?.stop()
    }
}

/** 本地账本的文件持久化：临时文件写完再改名，避免半截文件毁账本。 */
class FileLocalChannelPersistence(private val file: File) : LocalChannelPersistence {
    override fun load(): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    override fun save(text: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
