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
 * 在配对 + hello 成功后传 hello 列表与已存记录进来，拿回新建记录
 * 再落盘。手动表单保留作兜底（DeviceAgentForm 不动）。
 *
 * 去重口径（按 deviceId + agentId）：
 * - 已有同 (deviceId, agentId) 的记录 → 已在，保留不动（手工填的
 *   测试结果、登录态、备注一律不覆盖）；
 * - 同 agentId 已属另一台设备（或本机记录）→ 冲突，跳过不建：
 *   AgentStore 全局按 agentId 存，一台设备一个 agentId 只能有一条
 *   记录，覆盖会毁掉另一台设备的 Agent（见结果里的 skippedCollision）；
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
    /** 同 agentId 已属另一台设备，存不下，已跳过。 */
    val skippedCollision: List<String>,
)

object DeviceAgentDiscovery {

    /** 自动发现的记录打的来源标记（addHint 字段，入库后可查）。 */
    const val AUTO_DISCOVERED_HINT: String = "添加设备时自动发现"

    /**
     * 按 hello 列表算出该新建的记录。返回（新建记录，去重结果）。
     * 新建记录全部通过 [AgentRecord.validate]（binary 取 agentId，
     * 与 DeviceAgentForm 的默认口径一致）。
     */
    fun discover(
        deviceId: String,
        helloAgents: List<RemoteAgentInfo>,
        existing: List<AgentRecord>,
    ): Pair<List<AgentRecord>, DeviceAgentDiscoveryResult> {
        val created = mutableListOf<AgentRecord>()
        val createdIds = mutableListOf<String>()
        val alreadyPresent = mutableListOf<String>()
        val skippedUnavailable = mutableListOf<String>()
        val skippedCollision = mutableListOf<String>()
        val byId = existing.associateBy { it.agentId }
        for (info in helloAgents) {
            if (!info.available || info.folder.isBlank()) {
                skippedUnavailable += info.agentId
                continue
            }
            val old = byId[info.agentId]
            if (old != null) {
                if (old.deviceId == deviceId) {
                    alreadyPresent += info.agentId
                } else {
                    skippedCollision += info.agentId
                }
                continue
            }
            created += AgentRecord(
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
        return created to DeviceAgentDiscoveryResult(
            created = createdIds,
            alreadyPresent = alreadyPresent,
            skippedUnavailable = skippedUnavailable,
            skippedCollision = skippedCollision,
        )
    }

    /** 发现结果的一行实话（配对成功 / 重新发现后的 toast 用）。 */
    fun summarize(result: DeviceAgentDiscoveryResult): String {
        val parts = mutableListOf<String>()
        if (result.created.isNotEmpty()) parts += "新建 ${result.created.size} 个 Agent"
        if (result.alreadyPresent.isNotEmpty()) parts += "${result.alreadyPresent.size} 个已在"
        if (result.skippedUnavailable.isNotEmpty()) parts += "${result.skippedUnavailable.size} 个不可用已跳过"
        if (result.skippedCollision.isNotEmpty()) parts += "${result.skippedCollision.size} 个与别台设备重名已跳过"
        if (parts.isEmpty()) return "没有发现可用 Agent"
        return parts.joinToString("，")
    }
}
