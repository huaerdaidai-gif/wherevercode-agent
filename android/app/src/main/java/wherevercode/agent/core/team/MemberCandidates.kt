package wherevercode.agent.core.team

import wherevercode.agent.AgentDiscovery
import wherevercode.agent.brain.cli.CliConfigs

/**
 * 发现接线（V5 Phase B C3 第二阶段，成员框架）。
 *
 * 只消费 [AgentDiscovery.availableAgents] 已有的 `--version` 探针
 * 结果，不新增任何探针命令，不 spawn 任何进程，不读任何登录文件。
 *
 * 诚实边界（发现结果能证明什么、不能证明什么）：
 * - 发现只看得到 App 沙箱内的 CLI（filesDir/bin、node/bin、
 *   nativeLibraryDir、PATH）。K70 Termux 里的 Codex/CodeBuddy、
 *   Mac 上的 Codex、远程设备 上的 Codex/CodeBuddy，沙箱一律看不
 *   见——发现结果里没有它们是正常的，不是“没装”。
 * - 一条发现结果只证明“沙箱内该二进制存在、--version 能跑”，
 *   不证明登录、不证明能力、不证明可派发。候选成员一律
 *   NOT_CONNECTED、无授权项目，[TeamMember.shapeStatus] 只取
 *   CliConfigs 对应行的 verified（codex/codebuddy 为 VERIFIED，
 *   其余四 CLI 为 UNVERIFIED），不给未核实项编能力。
 * - 云端成员（Manus、LeChat、Grok 等）与桌面 App 成员（Mac 端
 *   Workbuddy）永远不会出现在这里：它们不走 CLI 发现。Workbuddy
 *   尤其不得被写成一条发现行——它不是 CLI，没有二进制名可探。
 *   Example Agent CLI（`grok`，官方开源）若出现在发现结果里，同样
 *   只作候选：形态 UNVERIFIED，不假设在任何设备上已装、不装。
 * - 候选只是候选：调用方须经 [MemberManager.addMember] 才能入库；
 *   入库后仍是 NOT_CONNECTED，派发照样拒绝（见 MemberDispatcher）。
 */
object MemberCandidates {

    /**
     * 把发现结果映射为成员候选。顺序与发现目录一致（codex、claude、
     * muse、gemini、codebuddy、agy）；未安装的条目同样映射为候选
     * （installed=false 只写进显示，不决定能否入库——入库后仍是
     * 未连接，派发时再按连接状态拒绝，不在这里预拦）。
     */
    fun fromDiscovery(reports: List<AgentDiscovery.AgentReport>): List<TeamMember> =
        reports.map { report ->
            val config = CliConfigs.ALL.firstOrNull { it.agentId == report.agentId }
            TeamMember(
                memberId = report.agentId,
                agentId = report.agentId,
                displayName = report.displayName,
                role = MemberRole.DEVELOPER,
                kind = MemberKind.DEVICE_CLI,
                transport = MemberTransport.LOCAL_SANDBOX,
                endpointLabel = "本机沙箱",
                authKind = MemberAuthKind.CLI_LOGIN,
                shapeStatus = if (config?.verified == true) {
                    MemberShapeStatus.VERIFIED
                } else {
                    MemberShapeStatus.UNVERIFIED
                },
                connectionState = MemberConnectionState.NOT_CONNECTED,
                connectivity = MemberConnectivity.NOT_CHECKED,
            )
        }

    /** Codex 的发现候选（沙箱内）；Mac/VPS 的 Codex 不在这里。 */
    fun codexCandidate(reports: List<AgentDiscovery.AgentReport>): TeamMember? =
        fromDiscovery(reports).firstOrNull { it.agentId == "codex-cli" }
}
