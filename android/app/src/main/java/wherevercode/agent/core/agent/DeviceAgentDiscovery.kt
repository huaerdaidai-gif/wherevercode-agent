package wherevercode.agent.core.agent

import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.transport.RemoteAgentInfo

/**
 * 添加设备时的 Agent 自动发现（V5，2026-11-11；maintainer 设计纠正）。
 *
 * 设计：添加设备是智能模式——设备一加上，Agent 自动发现，不用一个
 * 一个手工填表单加。数据只来自 Hand hello 回包（真实存在、服务端核查
 * 过入口的 Agent），hello 没报的绝不凭空造。
 *
 * 本对象是纯函数，不碰 Android、不读存储：调用方（MainActivity）
 * 在配对 + hello 成功后传 hello 列表、已存记录与当前设备 id 集合
 * 进来，拿回需落盘的记录（新建 + 改绑）再落盘。手动表单保留作兜底
 * （DeviceAgentForm 不动）。
 *
 * 去重口径（按 deviceId + agentId）：
 * - 已有同 (deviceId, agentId) 的记录 → 已在，保留不动（手工填的
 *   测试结果、登录态、备注一律不覆盖）；
 * - 同 agentId 的旧记录没绑设备（deviceId 为 null）、或绑的设备已
 *   不在当前设备列表里（设备删过）→ 仅当记录带自动发现标记
 *   （AUTO_DISCOVERED_HINT）时改绑到本次扫描的设备：只换 deviceId
 *   与 deviceFolder（文件夹以本设备 hello 报的为准），其余字段原样
 *   保留；手工添加的记录（无标记）绝不改绑，计入 skippedManual。
 *   旧口径对这种情况一律「重名跳过」，绑定冻死在已删设备上：这台
 *   设备永远扫不进它、承载数永远少计，且无任何修复路径（2026-11-19
 *   审计定性为绑定生命周期缺口，本次修复）；
 * - 同 agentId 已属另一台现存设备 → 冲突，跳过不建：AgentStore
 *   全局按 agentId 存，一台设备一个 agentId 只能有一条记录，覆盖
 *   会毁掉另一台设备的 Agent（见结果里的 skippedCollision，汇总文
 *   案里明示，不静默吞）；
 * - hello 里不可用或无文件夹的 → 跳过（路由要求文件夹非空，见
 *   RemoteTargetResolution；没文件夹的记录发不出去，存了也是摆设）。
 */
data class DeviceAgentDiscoveryResult(
    /** 新建的 agentId（调用方按此落盘）。 */
    val created: List<String>,
    /** 已有同 (deviceId, agentId)，保留不动。 */
    val alreadyPresent: List<String>,
    /** hello 里不可用或无文件夹，已跳过。 */
    val skippedUnavailable: List<String>,
    /** 同 agentId 已属另一台现存设备，存不下，已跳过。 */
    val skippedCollision: List<String>,
    /** 旧记录没绑设备或绑的设备已删，本次改绑到扫描设备的 agentId。 */
    val rebound: List<String> = emptyList(),
    /** 同名旧记录是手工添加的（无自动发现标记），本次不改绑、不新建。 */
    val skippedManual: List<String> = emptyList(),
)

object DeviceAgentDiscovery {

    /** 自动发现的记录打的来源标记（addHint 字段，入库后可查）。 */
    const val AUTO_DISCOVERED_HINT: String = "添加设备时自动发现"

    /**
     * 按 hello 列表算出该新建/改绑的记录。返回（需落盘的记录，去重
     * 结果）：第一项含新建记录与改绑后的旧记录，调用方全部落盘。
     * 新建记录全部通过 [AgentRecord.validate]（binary 取 agentId，
     * 与 DeviceAgentForm 的默认口径一致）。
     *
     * [knownDeviceIds] 是当前设备列表的全部 deviceId（应含本次扫描
     * 的 [deviceId]）：旧记录绑的设备不在此集合里，即视为设备已删，
     * 走改绑而不是冲突跳过。
     */
    fun discover(
        deviceId: String,
        helloAgents: List<RemoteAgentInfo>,
        existing: List<AgentRecord>,
        knownDeviceIds: Set<String>,
    ): Pair<List<AgentRecord>, DeviceAgentDiscoveryResult> {
        val toSave = mutableListOf<AgentRecord>()
        val createdIds = mutableListOf<String>()
        val alreadyPresent = mutableListOf<String>()
        val skippedUnavailable = mutableListOf<String>()
        val skippedCollision = mutableListOf<String>()
        val rebound = mutableListOf<String>()
        val skippedManual = mutableListOf<String>()
        val byId = existing.associateBy { it.agentId }
        for (info in helloAgents) {
            if (!info.available || info.folder.isBlank()) {
                skippedUnavailable += info.agentId
                continue
            }
            val old = byId[info.agentId]
            if (old != null) {
                val boundDeviceId = old.deviceId
                when {
                    boundDeviceId == deviceId -> alreadyPresent += info.agentId
                    // 没绑设备、或绑的设备已删：只有带自动发现标记的记录才
                    // 改绑到本次扫描的设备（Bug① 守卫，2026-11-19 定版）；
                    // 手工添加的云端 Agent 严禁改绑——计入 skippedManual，
                    // 原记录一字不动，也不新建第二条。改绑只换 deviceId
                    // 与 deviceFolder，其余字段（手工填的测试结果、登录态、
                    // 备注、密钥引用）原样保留。
                    boundDeviceId == null || boundDeviceId !in knownDeviceIds -> {
                        if (old.addHint == AUTO_DISCOVERED_HINT) {
                            toSave += old.copy(deviceId = deviceId, deviceFolder = info.folder)
                            rebound += info.agentId
                        } else {
                            skippedManual += info.agentId
                        }
                    }
                    // 绑在另一台现存设备上：真冲突，跳过并明示。
                    else -> skippedCollision += info.agentId
                }
                continue
            }
            toSave += AgentRecord(
                agentId = info.agentId,
                displayName = info.displayName,
                kind = AgentKind.DEVICE,
                access = AgentAccess.CLI,
                brainKind = BrainKind.AGENT_RUNTIME,
                adapterId = info.agentId,
                binary = info.agentId,
                capabilities = info.capabilities,
                quotaRule = AgentRecord.defaultQuotaRule(AgentKind.DEVICE),
                addHint = AUTO_DISCOVERED_HINT,
                deviceId = deviceId,
                deviceFolder = info.folder,
            )
            createdIds += info.agentId
        }
        return toSave to DeviceAgentDiscoveryResult(
            created = createdIds,
            alreadyPresent = alreadyPresent,
            skippedUnavailable = skippedUnavailable,
            skippedCollision = skippedCollision,
            rebound = rebound,
            skippedManual = skippedManual,
        )
    }

    /** 发现结果的一行实话（配对成功 / 重新发现后的 toast 用）。 */
    fun summarize(result: DeviceAgentDiscoveryResult): String {
        val parts = mutableListOf<String>()
        if (result.created.isNotEmpty()) parts += "新建 ${result.created.size} 个 Agent"
        if (result.rebound.isNotEmpty()) parts += "${result.rebound.size} 个改绑到本设备"
        if (result.alreadyPresent.isNotEmpty()) parts += "${result.alreadyPresent.size} 个已在"
        if (result.skippedUnavailable.isNotEmpty()) parts += "${result.skippedUnavailable.size} 个不可用已跳过"
        if (result.skippedCollision.isNotEmpty()) parts += "${result.skippedCollision.size} 个与别台设备重名已跳过"
        if (result.skippedManual.isNotEmpty()) parts += "${result.skippedManual.size} 个手工添加已跳过"
        if (parts.isEmpty()) return "没有发现可用 Agent"
        return parts.joinToString("，")
    }
}
