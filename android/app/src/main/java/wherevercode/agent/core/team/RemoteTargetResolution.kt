package wherevercode.agent.core.team

import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.project.DispatchFolder
import wherevercode.agent.core.project.DispatchFolderSource
import wherevercode.agent.core.security.PairingRecord
import wherevercode.agent.core.security.PairingState
import wherevercode.agent.core.security.PairingTrustStore
import wherevercode.agent.core.transport.RemoteDevice

/**
 * 远端 Agent 目标的纯判定（V5 修复，2026-11-11）。
 *
 * 旧 `MainActivity.resolveRemoteTarget` 在 UI 线程直读 Room 信任库，
 * Room 抛主线程异常后被 catch 吞成“未配对”，而测试在后台线程读库
 * 能通过——同一台设备两边结论相反。判定本身改成纯函数：调用方在
 * UI 线程传内存快照里的记录，在后台线程传直读库的记录，两边用同一
 * 口径，不再各自读库。
 *
 * 判定口径（与 `HandRemoteAgentChatPort.blockedReason`、
 * `AgentConnectionTester.testDevice` 对齐）：设备在册 + 配对
 * CONFIRMED + 对方公钥非空（建 HandClient 真正需要的条件；旧代码
 * 的 `pairingManager.isTrusted` 二次读库是空转——它拿记录里的指纹
 * 跟记录自己比，恒真）+ Agent 有执行文件夹。
 */
fun resolveRemoteTargetFromRecords(
    agent: AgentRecord,
    device: RemoteDevice?,
    pairing: PairingRecord?,
    /**
     * V5 跨设备开发（2026-11-12，A3）：项目文件夹覆盖（由
     * [wherevercode.agent.core.project.resolveDispatchFolder] 算出）。
     * 非空时本次执行的 folder 就是它（source 随它走）；空时沿用
     * Agent 文件夹（今日行为，source 为 AGENT_FALLBACK）。空值仍
     * 返回 null 的门禁不变（Agent 无文件夹 = 不可路由）。
     */
    folderOverride: DispatchFolder? = null,
): RemoteTarget? {
    val deviceId = agent.deviceId ?: return null
    val found = device ?: return null
    if (found.deviceId != deviceId) return null
    val record = pairing ?: return null
    if (record.deviceId != deviceId) return null
    if (record.state != PairingState.CONFIRMED) return null
    if (record.peerPublicKey == null) return null
    val overrideFolder = folderOverride?.folder?.takeIf { it.isNotBlank() }
    val folder = overrideFolder
        ?: agent.deviceFolder?.takeIf { it.isNotBlank() }
        ?: return null
    val folderSource = if (overrideFolder != null) {
        folderOverride.source
    } else {
        DispatchFolderSource.AGENT_FALLBACK
    }
    return RemoteTarget(
        peer = found.toDiscoveredPeer(record.fingerprintSha256),
        agentId = agent.agentId,
        folder = folder,
        folderSource = folderSource,
    )
}

/**
 * 配对记录的统一读取（后台线程用）：持久库优先，读不到再读迁移库。
 * 对话通道与测试通道现在用同一读取顺序，不再各自读库。
 */
fun loadPairingRecord(
    deviceId: String,
    persistent: PairingTrustStore?,
    fallback: PairingTrustStore,
): PairingRecord? {
    val fromPersistent = try {
        persistent?.load(deviceId)
    } catch (_: Exception) {
        null
    }
    if (fromPersistent != null) return fromPersistent
    return try {
        fallback.load(deviceId)
    } catch (_: Exception) {
        null
    }
}
