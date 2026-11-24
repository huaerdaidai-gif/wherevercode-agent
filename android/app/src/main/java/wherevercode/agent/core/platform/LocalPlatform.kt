package wherevercode.agent.core.platform

import android.content.Context
import java.io.File
import java.net.URI
import java.util.UUID
import wherevercode.agent.BuildConfig
import wherevercode.agent.core.agent.PlatformAgent
import wherevercode.agent.core.agent.PlatformAgentCredentialStore
import wherevercode.agent.core.agent.PlatformConnectionStore
import wherevercode.agent.core.agent.SharedPreferencesAgentStore
import wherevercode.agent.core.platform.uplink.UplinkConfig
import wherevercode.agent.core.platform.uplink.UplinkRuntime

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
            val taskFile = File(context.applicationContext.filesDir, "local_platform_tasks.json")
            service = LocalChannelService(
                FileLocalChannelPersistence(file),
                taskPersistence = FileTaskDispatchPersistence(taskFile),
            )
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

    /** 频道工具全集（与 iOS LocalPlatform.toolNames 同序同名）。 */
    val CHANNEL_TOOL_NAMES: List<String> = listOf(
        "agent_channel_list",
        "agent_channel_join",
        "agent_channel_open_dm",
        "agent_channel_post",
        "agent_channel_messages",
        "agent_channel_create",
        "agent_channel_rename",
        "agent_channel_set_archived",
        "agent_channel_set_history_visible",
        "agent_channel_delete",
        "agent_channel_add_member",
        "agent_channel_remove_member",
        "agent_channel_set_member_role",
        "agent_channel_create_webhook",
        "agent_channel_list_webhooks",
        "agent_channel_delete_webhook",
        "agent_channel_recall",
        "agent_channel_delete_message",
        "agent_channel_pin",
        "agent_channel_react",
        "agent_channel_readers",
    )

    /**
     * tools/list 名单（W12 角色化暴露）：频道工具全量 + 任务工具
     * 按调用方作用域过滤（干活侧只见 9 个，owner 见 16 个）。
     */
    fun toolNames(callerScope: String): List<String> =
        CHANNEL_TOOL_NAMES + TaskDispatchService.toolNames(callerScope)

    /** 机主令牌（W12）：持它从别的设备连入即 owner 作用域。 */
    fun lanOwnerToken(): String? = lanRegistry?.issueOwner()?.token

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

    // ---- 跨设备层（频道三层三期） --------------------------------------
    // 宿主侧：App 以自己的平台凭证建 WS 席位连到云端平台——云端派单
    // 落本地账本、本地事件回流云端镜像。没配平台凭证就不连，不假装
    // 在线；凭证只在武装时现取现用，不缓存明文、不进日志。

    /** App 席位在云端平台上的 agent_id（与 iOS SeatUplinkConfig 默认同口径）。 */
    const val APP_SEAT_AGENT_ID: String = "local-app"

    private const val SEAT_INSTANCE_ID_KEY: String = "seat_app_instance_id"

    @Volatile
    private var uplinkRuntime: UplinkRuntime? = null

    @Volatile
    private var uplinkKey: String? = null

    /** 席位上行是否已武装（有在跑的上行客户端）。 */
    fun seatUplinkArmed(): Boolean = uplinkRuntime != null

    /**
     * 按当前平台连接配置武装/解除席位上行：平台凭证在就连（地址按
     * 平台连接口径解析：已存地址 → 首个平台 Agent 记录 → 构建默认），
     * 没凭证或地址无效就停。MainActivity 启动与凭证变更后调用。
     */
    fun armSeatUplink(context: Context) {
        try {
            armSeatUplinkOrThrow(context)
        } catch (_: Exception) {
            // 席位是尽力而为：凭证存储/连接配置的任何异常都不许拖垮
            // App 启动（ Robolectric 等无 Keystore 环境同理）。
            stopSeatUplink()
        }
    }

    private fun armSeatUplinkOrThrow(context: Context) {
        val appContext = context.applicationContext
        val token = PlatformAgentCredentialStore.create(appContext)
            .loadPlatformToken()?.trim().orEmpty()
        if (token.isEmpty()) {
            stopSeatUplink()
            return
        }
        val firstPlatformAgentHost = try {
            SharedPreferencesAgentStore(appContext).listAgents()
                .firstOrNull { PlatformAgent.isPlatformAgentRecord(it) }?.platformHost
        } catch (_: Exception) {
            null
        }
        val host = try {
            PlatformConnectionStore(appContext).platformHost(
                firstPlatformAgentHost = firstPlatformAgentHost,
                buildDefaultHost = PlatformAgent.DEFAULT_PLATFORM_HOST,
            )
        } catch (_: Exception) {
            firstPlatformAgentHost?.trim()?.takeIf { it.isNotEmpty() }
                ?: PlatformAgent.DEFAULT_PLATFORM_HOST
        }
        val url = PlatformAgent.seatEndpoint(host)
        if (url.isEmpty()) {
            stopSeatUplink()
            return
        }
        startSeatUplink(url, token, seatAppInstanceId(appContext))
    }

    /** 启动席位上行（同一地址+凭证不重复拨号；换了就停旧连新）。未 install 直接返回。 */
    @Synchronized
    fun startSeatUplink(url: String, token: String, appInstanceId: String) {
        val svc = service ?: return
        val key = url + "\n" + token
        if (uplinkKey == key && uplinkRuntime != null) return
        uplinkRuntime?.stop()
        val runtime = UplinkRuntime(
            svc,
            UplinkConfig(
                url = url,
                token = token,
                appInstanceId = appInstanceId,
                agentId = APP_SEAT_AGENT_ID,
                appVersion = BuildConfig.VERSION_NAME,
            ),
        )
        uplinkRuntime = runtime
        uplinkKey = key
        runtime.start()
    }

    /** 停止席位上行（幂等）。 */
    @Synchronized
    fun stopSeatUplink() {
        uplinkRuntime?.stop()
        uplinkRuntime = null
        uplinkKey = null
    }

    /** 持久的 App 实例 id（席位登记用；首次生成后存平台连接偏好，不随重启变）。 */
    private fun seatAppInstanceId(context: Context): String {
        val prefs = context.getSharedPreferences(
            PlatformConnectionStore.PREFERENCES_NAME, Context.MODE_PRIVATE,
        )
        prefs.getString(SEAT_INSTANCE_ID_KEY, null)?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(SEAT_INSTANCE_ID_KEY, created).apply()
        return created
    }
}

/** 任务派工账本的文件持久化：与频道账本同一写法（临时文件 + 改名）。 */
class FileTaskDispatchPersistence(private val file: File) : TaskDispatchPersistence {
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
