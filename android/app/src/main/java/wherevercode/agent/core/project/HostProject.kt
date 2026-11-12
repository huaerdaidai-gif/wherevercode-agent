package wherevercode.agent.core.project

import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingState
import wherevercode.agent.core.transport.RemoteDevice

/**
 * 设备上的项目列表（host project listing）。
 *
 * 老项目 `project_locations.py` 文件头 docstring 的关键句：
 * “Locations reference existing registered projects. Workspace paths stay
 * private in this store except in the host project listing consumed by
 * the Project Browser.”——项目浏览器消费的就是设备上的项目列表。
 *
 * V5 里一台 Hand 设备报的是它的 Agent 清单（hello 回包），每个 Agent
 * 的文件夹就是一个候选项目文件夹（Agent 在该文件夹里工作）。
 * 列表本身不产生任何位置：加项目是用户明确点的授权动作；
 * 没有位置的设备不会出现在项目列表里（老项目 EXECUTION_HOST_V1：
 * “A Mac with no registered ProjectLocation remains absent from
 * Project Browser project lists.”）。
 */
data class HostProjectEntry(
    val agentId: String,
    val displayName: String,
    /** 该 Agent 在设备上的工作文件夹（Hand 凭证绑定的 folder）。 */
    val folder: String,
    /**
     * 是该项目的当前家（远程文件夹选择器里置顶、默认选中、标“当前家”）。
     * 只有项目之家条目才为 true；Agent 文件夹条目一律 false。
     */
    val isCurrentHome: Boolean = false,
    /**
     * 是该项目在本设备上已有的 DEVICE 家（非 Agent 文件夹；不含当前家）。
     * Agent 文件夹条目一律 false。
     */
    val isProjectHome: Boolean = false,
)

data class HostProjectListing(
    val hostId: String,
    val entries: List<HostProjectEntry>,
)

/** 由已存的 Agent 记录建某设备的项目列表（只取有文件夹的 Agent）。 */
fun hostProjectListing(hostId: String, agents: List<AgentRecord>): HostProjectListing {
    val entries = agents
        .filter { it.deviceId == hostId && !it.deviceFolder.isNullOrBlank() }
        .map {
            HostProjectEntry(
                agentId = it.agentId,
                displayName = it.displayName,
                folder = it.deviceFolder!!,
            )
        }
        .sortedBy { it.displayName }
    return HostProjectListing(hostId = hostId, entries = entries)
}

/**
 * 远程文件夹选择器的候选集（2026-11-12，maintainer 05:32 手机实证）：
 * 只列 Agent 文件夹会把项目仓库文件夹漏掉——项目 wherevercode-agent
 * 的 DEVICE 家已是 Mac /Users/example-user/Projects/wherevercode-agent，
 * 弹框里却只剩 Claude/Codex/OpenCode 三个 Agent 工作文件夹，仓库
 * 文件夹根本选不到；文案还写“将在已开放文件夹中创建”，把 Agent
 * 文件夹当成项目创建目标。
 *
 * 候选规则（按顺序）：
 * 1. 该项目在本设备上的当前 DEVICE 家（有则置顶、默认选中）；
 * 2. 该项目在本设备上的其他 DEVICE 家（按文件夹排序）；
 * 3. 该设备上各 Agent 的工作文件夹（原 hostProjectListing 口径，
 *    按显示名排序；与上面重复的文件夹只出现一次，算作家、不算 Agent
 *    文件夹）。
 *
 * 只有**本项目**的家能进候选：[ProjectStore.saveLocation] 的占用检查
 * （[ProjectStore.homeOccupiedBy]）规定同一文件夹只能是一个项目的家，
 * 别项目的家列出来也选不成，点了只会报“该文件夹已是项目 X 的家”，
 * 所以不列。新建项目（无家）时只有第 3 类。
 *
 * Hand 的已授权项目文件夹（`authorized_project_folders`）不在这里：
 * Hand 的 hello 回包只报 Agent 清单（agent_id/display_name/folder），
 * 不带已授权列表；Hand 服务端 `authorized_folders()` 只在服务端校验
 * 时读 `auth.json`，从不发给 App。App 侧能拿到的“已授权项目文件夹”
 * 的唯一真实来源就是 ProjectStore 里的 DEVICE 家——本函数用的正是
 * 它（调用方从 [ProjectStore.locationsForProject] 取）。要列出 Hand
 * 上全部已授权文件夹，须另加 Hand 端点/协议扩展，不在这里硬编。
 *
 * @param currentHomeFolder 该项目在本设备上的当前 DEVICE 家文件夹
 *   （可为 null = 新项目或本设备上无家）。
 * @param otherHomeFolders 该项目在本设备上的其他 DEVICE 家文件夹
 *   （不含当前家）。
 */
fun remoteFolderPickerListing(
    hostId: String,
    agents: List<AgentRecord>,
    currentHomeFolder: String?,
    otherHomeFolders: List<String> = emptyList(),
): HostProjectListing {
    val homeFolders = buildList {
        currentHomeFolder?.takeIf { it.isNotBlank() }?.let { add(normalizeFolderPath(it)) }
        otherHomeFolders.map { normalizeFolderPath(it) }
            .filter { it.isNotBlank() }
            .sorted()
            .forEach { if (it !in this) add(it) }
    }
    val normalizedCurrent = currentHomeFolder?.takeIf { it.isNotBlank() }?.let { normalizeFolderPath(it) }
    val homeEntries = homeFolders.map { folder ->
        HostProjectEntry(
            agentId = "",
            displayName = "",
            folder = folder,
            isCurrentHome = normalizedCurrent != null && folder == normalizedCurrent,
            isProjectHome = true,
        )
    }
    val agentEntries = agents
        .filter { it.deviceId == hostId && !it.deviceFolder.isNullOrBlank() }
        .map {
            HostProjectEntry(
                agentId = it.agentId,
                displayName = it.displayName,
                folder = normalizeFolderPath(it.deviceFolder!!),
            )
        }
        .filter { it.folder !in homeFolders }
        .sortedBy { it.displayName }
    return HostProjectListing(hostId = hostId, entries = homeEntries + agentEntries)
}

/**
 * 设备上的一个可打开的项目来源（V5，2026-11-11；maintainer 设计纠正）：
 * 某台已配对设备上某个 Agent 的工作文件夹。项目页列出它，点开即经
 * Hand list 浏览——不用先注册项目。数据只来自已存 Agent 记录（hello
 * 发现时存下的 deviceFolder），hello 没报的来源不会出现。
 *
 * 可用性（2026-11-11 项目页清理）：不可用的来源只灰显、永不自动删除
 * （与设备列表同一口径）。[available] 由配对状态判定（见
 * [deviceSourceAvailability]）；[unavailableReason] 是灰显时注明的
 * 原因，可用时为 null。
 */
data class DeviceProjectSource(
    val deviceId: String,
    val deviceDisplayName: String,
    val agentId: String,
    val agentDisplayName: String,
    /** 该 Agent 在设备上的工作文件夹（Hand 凭证绑定的 folder）。 */
    val folder: String,
    /** 配对状态决定的可用性：不可用只灰显，不删除、不隐藏。 */
    val available: Boolean = true,
    /** 不可用时的原因（与设备列表的状态文案同一口径），可用时为 null。 */
    val unavailableReason: String? = null,
)

/**
 * 设备来源的可用性（纯函数，单测覆盖；与设备列表
 * [wherevercode.agent.core.transport.buildRemoteDeviceRows] 同一口径）：
 * CONFIRMED 才可用；其余一律不可用、只灰显不删除。配对状态只说信任，
 * 不说在线——CONFIRMED 但失联的设备（如 Mac）这里仍算可用，在线态
 * 须由配对分支的最后在线/探测缓存接进来（接线点在调用方传的
 * availability 解析器里，不要在这里另起一套在线判定）。
 *
 * 另与设备列表不同的一点：CONFIRMED 但对方公钥为空时也算不可用——
 * 路由判定（resolveRemoteTargetFromRecords）建 HandClient 需要对方
 * 公钥，没它点开也走不通，灰显比点开报错诚实。
 */
fun deviceSourceAvailability(
    device: RemoteDevice?,
    record: PairingRecord?,
): Pair<Boolean, String?> {
    if (device == null) return false to "设备未添加（不可用）"
    return when (record?.state) {
        PairingState.CONFIRMED ->
            if (record.peerPublicKey != null) true to null
            else false to "配对未完成（不可用）"
        PairingState.PENDING -> false to "待确认（不可用）"
        PairingState.REJECTED -> false to "已拒绝（不可用）"
        PairingState.REVOKED -> false to "已撤销（不可用）"
        null -> false to "未配对（不可用）"
    }
}

/**
 * 由已存的 Agent 记录建“设备上的项目来源”列表。调用方先按
 * resolveRemoteTarget 过滤出可路由的记录（已配对 + 有文件夹），
 * 这里只做拼装与排序，不再判配对、不读网络。
 *
 * 可用性（2026-11-11 项目页清理）：[availability] 按设备 id 返回
 * （可用，不可用原因）；缺省全可用（保持老调用方行为）。调用方
 * （MainActivity）传配对快照的判定——不可用的来源也列出来灰显，
 * 不再像以前那样直接丢掉。配对状态之外的在线态（最后在线/探测
 * 缓存）由调用方在解析器里拼，不改这里。
 */
fun deviceProjectSources(
    devices: List<RemoteDevice>,
    agents: List<AgentRecord>,
    availability: (deviceId: String) -> Pair<Boolean, String?> = { true to null },
): List<DeviceProjectSource> {
    val names = devices.associate { it.deviceId to it.displayName }
    return agents
        .filter { it.kind == AgentKind.DEVICE && it.deviceId != null && !it.deviceFolder.isNullOrBlank() }
        .map {
            val (available, reason) = availability(it.deviceId!!)
            DeviceProjectSource(
                deviceId = it.deviceId!!,
                deviceDisplayName = names[it.deviceId] ?: it.deviceId!!,
                agentId = it.agentId,
                agentDisplayName = it.displayName,
                folder = it.deviceFolder!!,
                available = available,
                unavailableReason = reason,
            )
        }
        .sortedWith(compareBy({ it.deviceDisplayName }, { it.agentDisplayName }))
}

/**
 * 加项目流程的可用设备（2026-11-11 真机根因，K70）：
 * 有 Agent 文件夹能路由到它（已配对 + Hand 在跑 + 有文件夹）的设备
 * 就能托管项目。静态能力位 projectHost 在旧存储记录里缺失——K70
 * 三台已配对设备的记录都没有 capabilities 键，缺省 false——只看
 * 静态位会把真能托管的设备挡在门外，弹错“还没有能托管项目的已配对
 * 设备”。能路由本身就是 Hand 服务端在跑的活证据，比静态位更可信。
 * 没有可路由文件夹的设备不进列表（进了也选不出家文件夹）。
 *
 * @param routableDeviceIds 至少有一个 Agent 文件夹能路由到的设备 id
 * （调用方按 resolveRemoteTarget 算出）。
 */
fun projectHostUsableDevices(
    devices: List<RemoteDevice>,
    routableDeviceIds: Set<String>,
): List<RemoteDevice> = devices.filter { it.deviceId in routableDeviceIds }
