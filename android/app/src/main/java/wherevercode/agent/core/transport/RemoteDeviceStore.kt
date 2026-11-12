package wherevercode.agent.core.transport

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingState

/**
 * 阶段 B（V5 紧急开发）：手动添加的远程设备。
 *
 * 远程设备不在局域网里，mDNS 发现够不着：用户手动填地址添加
 * （如远程设备 的 Tailscale IP + Hand 端口）。本记录只存地址与
 * 显示名，不存任何密钥、Token、密码；对端公钥只进配对记录
 * （[wherevercode.agent.core.security.PairingRecord]），
 * 本机私钥只进 [wherevercode.agent.core.security.DeviceIdentityStore]。
 */
data class RemoteDevice(
    val deviceId: String,
    val displayName: String,
    /** 主机：IP 或主机名，不含 scheme、不含端口。 */
    val host: String,
    val port: Int,
    /**
     * 公网直连地址（V5，2026-11-11，maintainer 点名通信走美国公网 IP）：
     * IP 或主机名，不含 scheme、不含端口，与 [host] 同端口。
     * 空 = 未填，只走 [host]（内网/Tailnet/LAN）。填了则连接优先
     * 走它，连不上（超时/拒绝）自动回落到 [host]，不用用户手动
     * 切换。只存地址，不存任何密钥、Token、密码；配对与密封协议
     * 不动，公网接口上配对同样生效（未配对照样被拒）。
     */
    val publicHost: String = "",
    val addedAtEpochSeconds: Long = 0,
    /**
     * 最后一次 hello 成功的时间（epoch 秒）。0 = 从未在线。
     * 只在真实 hello 成功时更新（配对/重新发现/探测），绝不拿添加
     * 时间冒充；没有心跳探测，所以它不等同于“现在在线”。
     */
    val lastSeenEpochSeconds: Long = 0,
    /**
     * 最后一次 hello 回包里的 client_status（服务端批准状态）：
     * "APPROVED" / "PENDING" / "REVOKED" / ""（旧版服务端没给，
     * 不知道）。设备列表由此显示“对方待批准”，不再靠猜。
     */
    val lastServerStatus: String = "",
    /**
     * Host 能力位（老项目 HOST_CAPABILITIES）。缺省全关（fail closed）：
     * 没有能力记录的旧设备一律当作不能托管项目，加项目流程不会向它
     * 提供入口。经 Hand 添加的设备取 [handServerCapabilities]。
     */
    val capabilities: HostCapabilities = HostCapabilities(),
    /** 协议能力（老项目 HOST_PROTOCOL_CAPABILITIES 的名字子集）。 */
    val protocolCapabilities: Set<String> = emptySet(),
    /**
     * Hand 自更新（2026-11-12）：上次 hello 见到的远端 Hand 代码版本
     * （文件内容 SHA-256 前 12 位）。"" = 未知（旧版 Hand 没报，或
     * 从未 hello 成功）。只在真实 hello 成功时更新，绝不编造。
     */
    val handVersion: String = "",
) {
    /** 是否填了公网直连地址。 */
    fun hasPublicAddress(): Boolean = publicHost.isNotBlank()

    /** 列表用的走线标记（只标公网/内网，不贴完整 IP）。 */
    fun routeLabel(): String = if (hasPublicAddress()) "公网优先" else "内网"

    /**
     * 配上配对记录里的指纹，拼成 Hand 层用的对端描述。
     * 填了公网直连地址时 peer.host = 公网地址（优先），
     * peer.fallbackHost = 原内网地址；没填时与以前完全一致。
     */
    fun toDiscoveredPeer(fingerprintSha256: String): DiscoveredPeer =
        DiscoveredPeer(
            deviceId = deviceId,
            displayName = displayName,
            host = publicHost.trim().ifBlank { host },
            port = port,
            fingerprintSha256 = fingerprintSha256,
            capabilities = listOf("hand"),
            fallbackHost = if (hasPublicAddress() && publicHost.trim() != host.trim()) host else null,
        )
}

object RemoteDeviceValidation {
    /**
     * 服务端报的 device-id 原样采用：Hand 服务端 `--device-id` 默认取
     * 本机 hostname（如小米 12 的 `Android-x`），含大写字母与点；
     * 凭证的 target_device_id 须与服务端 id 严格一致，App 不能因为
     * 含大写就拒绝添加——否则这台设备永远加不上（2026-11-11 真机）。
     * id 只做 Room / SharedPreferences 的键与凭证字段，绝不拼文件
     * 路径，所以大写与点是安全的；空格仍拒绝。
     */
    private val ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    /** 校验。返回可读错误列表；空 = 合法。 */
    fun validate(device: RemoteDevice): List<String> {
        val errors = mutableListOf<String>()
        if (device.deviceId.isBlank()) errors += "设备 id 不能为空"
        else if (!ID_REGEX.matches(device.deviceId)) errors += "设备 id 只能用字母、数字、-、_、.，且以字母或数字开头"
        if (device.displayName.isBlank()) errors += "设备名字不能为空"
        validateHost(device.host, "设备地址", errors)
        // 公网直连地址可空（空 = 只走内网地址）；填了就按同一口径校验。
        if (device.publicHost.isNotBlank()) {
            validateHost(device.publicHost, "公网直连地址", errors)
        }
        if (device.port !in 1..65535) errors += "端口必须在 1~65535 之间"
        return errors
    }

    /** 主机字段的通用校验（内网地址与公网直连地址同一口径）。 */
    private fun validateHost(value: String, label: String, errors: MutableList<String>) {
        val host = value.trim()
        if (host.isEmpty()) errors += "${label}不能为空"
        else if (host.contains("://")) errors += "${label}不要带 http:// 或 https://：只填 IP 或主机名（如 192.0.2.10）"
        else if (host.any { it.isWhitespace() }) errors += "${label}不能含空白字符"
        else if (host.contains("/")) errors += "${label}不能含 /：只填 IP 或主机名，端口填在端口栏"
    }
}

interface RemoteDeviceStore {
    fun listDevices(): List<RemoteDevice>
    fun getDevice(deviceId: String): RemoteDevice?
    fun saveDevice(device: RemoteDevice)
    fun deleteDevice(deviceId: String)
}

/** 内存实现（单测与无持久化场景用；不跨进程、不跨重启）。 */
class InMemoryRemoteDeviceStore : RemoteDeviceStore {
    private val lock = Any()
    private val devices = LinkedHashMap<String, RemoteDevice>()

    override fun listDevices(): List<RemoteDevice> =
        synchronized(lock) { devices.values.toList() }

    override fun getDevice(deviceId: String): RemoteDevice? =
        synchronized(lock) { devices[deviceId] }

    override fun saveDevice(device: RemoteDevice) {
        val errors = RemoteDeviceValidation.validate(device)
        require(errors.isEmpty()) { errors.joinToString("；") }
        synchronized(lock) { devices[device.deviceId] = device }
    }

    override fun deleteDevice(deviceId: String) {
        synchronized(lock) { devices.remove(deviceId) }
    }
}

/** SharedPreferences 实现（生产用；与 AgentStore 同款 JSON 数组写法）。 */
class SharedPreferencesRemoteDeviceStore(context: Context) : RemoteDeviceStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun listDevices(): List<RemoteDevice> {
        val json = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { parseDevice(array.optJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun getDevice(deviceId: String): RemoteDevice? =
        listDevices().firstOrNull { it.deviceId == deviceId }

    override fun saveDevice(device: RemoteDevice) {
        val errors = RemoteDeviceValidation.validate(device)
        require(errors.isEmpty()) { errors.joinToString("；") }
        val others = listDevices().filter { it.deviceId != device.deviceId }
        persist(others + device)
    }

    override fun deleteDevice(deviceId: String) {
        persist(listDevices().filter { it.deviceId != deviceId })
    }

    private fun persist(devices: List<RemoteDevice>) {
        val array = JSONArray()
        for (device in devices) {
            val obj = JSONObject()
            obj.put("deviceId", device.deviceId)
            obj.put("displayName", device.displayName)
            obj.put("host", device.host)
            obj.put("publicHost", device.publicHost)
            obj.put("port", device.port)
            obj.put("addedAtEpochSeconds", device.addedAtEpochSeconds)
            obj.put("lastSeenEpochSeconds", device.lastSeenEpochSeconds)
            obj.put("lastServerStatus", device.lastServerStatus)
            obj.put("capabilities", device.capabilities.toJson())
            val protocol = JSONArray()
            HostProtocolCapabilities.valid(device.protocolCapabilities).forEach { protocol.put(it) }
            obj.put("protocolCapabilities", protocol)
            array.put(obj)
        }
        prefs.edit().putString(KEY_DEVICES, array.toString()).apply()
    }

    private fun parseDevice(obj: JSONObject?): RemoteDevice? {
        if (obj == null) return null
        return try {
            val protocol = mutableSetOf<String>()
            val protocolArray = obj.optJSONArray("protocolCapabilities")
            if (protocolArray != null) {
                for (i in 0 until protocolArray.length()) {
                    protocol += protocolArray.optString(i, "")
                }
            }
            RemoteDevice(
                deviceId = obj.optString("deviceId", ""),
                displayName = obj.optString("displayName", ""),
                host = obj.optString("host", ""),
                publicHost = obj.optString("publicHost", ""),
                port = obj.optInt("port", 0),
                addedAtEpochSeconds = obj.optLong("addedAtEpochSeconds", 0),
                // 旧记录没有这两个字段时缺省 0 / ""（从未在线 / 状态未知），
                // 不伪造、不迁移失败。
                lastSeenEpochSeconds = obj.optLong("lastSeenEpochSeconds", 0),
                lastServerStatus = obj.optString("lastServerStatus", ""),
                capabilities = HostCapabilities.fromJson(obj.optJSONObject("capabilities")),
                protocolCapabilities = HostProtocolCapabilities.valid(protocol),
            ).takeIf { RemoteDeviceValidation.validate(it).isEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PREFS_NAME: String = "wherevercode_remote_devices"
        const val KEY_DEVICES: String = "devices_json"
    }
}

/**
 * 设备页 / 选设备对话框共用的展示行：手动添加的远程设备 + 它的配对
 * 状态 + 最后在线 + 能力数。纯函数，可单测；状态文案与选设备对话框
 * 的标记一致（非“已配对”一律缀“（不可用）”，绝不显示假在线态）。
 *
 * 离线只灰显保留、永不自动删除：本行只展示，不删记录；删除只走
 * 用户显式操作，离线多久都不自动删。
 */
data class RemoteDeviceRow(
    val device: RemoteDevice,
    val stateLabel: String,
    val paired: Boolean,
    /** 走线标记：公网优先 / 内网（只标走线，不贴完整 IP）。 */
    val routeLabel: String,
    /** 最后一次 hello 成功的时间（epoch 秒），0 = 从未在线。 */
    val lastSeenEpochSeconds: Long = 0,
    /** 已开的能力数（0 = 未知，不编造）。 */
    val capabilityCount: Int = 0,
    /** 已开的能力中文名（列表里展开显示）。 */
    val capabilityNames: List<String> = emptyList(),
    /** 服务端批准状态（hello 回包的 client_status）："" = 未知。 */
    val serverStatus: String = "",
    /** Hand 自更新（2026-11-12）：上次见到的远端 Hand 版本（"" = 未知）。 */
    val handVersion: String = "",
    /** Hand 自更新：远端 Hand 版本落后于 App 内置安装包，需要更新。 */
    val handUpdateAvailable: Boolean = false,
)

fun buildRemoteDeviceRows(
    devices: List<RemoteDevice>,
    pairings: List<PairingRecord>,
    /**
     * Hand 自更新（2026-11-12）：App 内置安装包的 Hand 版本（SHA-256
     * 前 12 位）。"" = 调用方不知道/不关心，不算 handUpdateAvailable。
     */
    bundledHandVersion: String = "",
): List<RemoteDeviceRow> {
    return devices.map { device ->
        val record = pairings.firstOrNull { it.deviceId == device.deviceId }
        val (label, paired) = when (record?.state) {
            PairingState.CONFIRMED -> "已配对" to true
            PairingState.PENDING -> "待确认（不可用）" to false
            PairingState.REJECTED -> "已拒绝（不可用）" to false
            PairingState.REVOKED -> "已撤销（不可用）" to false
            null -> "未配对（不可用）" to false
        }
        RemoteDeviceRow(
            device,
            label,
            paired,
            routeLabel = device.routeLabel(),
            lastSeenEpochSeconds = device.lastSeenEpochSeconds,
            capabilityCount = device.capabilities.enabledCount(),
            capabilityNames = device.capabilities.enabledNames(),
            serverStatus = device.lastServerStatus,
            handVersion = device.handVersion,
            handUpdateAvailable = bundledHandVersion.isNotBlank() &&
                device.handVersion.isNotBlank() &&
                device.handVersion != bundledHandVersion,
        )
    }
}
