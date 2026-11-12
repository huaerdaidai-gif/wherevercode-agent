package wherevercode.agent.core.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import wherevercode.agent.core.control.ControlAction
import wherevercode.agent.core.project.DispatchFolderSource
import wherevercode.agent.core.security.DeviceKeys
import wherevercode.agent.core.security.PeerTrustChecker
import wherevercode.agent.core.security.RemoteCredentialKeys
import wherevercode.agent.core.transport.DiscoveredPeer
import wherevercode.agent.core.transport.TransportAdapter

/**
 * 阶段 B（V5 紧急开发）：HandClient 的生产调用方。
 *
 * 审计结论曾是“HandClient 完整且有单测，但 main 源码零调用方”——
 * 本文件就是那个调用方：聊天与任务分派不再各自造协议，
 * 都走同一 [HandClient]、同一密封信封、同一任务凭证。
 *
 * 调用形态（与 VPS 侧 server/hand_server.py 约定）：
 * - 聊天/任务执行：action=run，args["argv"] = ["agent", agentId, 文本]，
 *   args["timeout"] = 秒数。服务端按 agents.json 查到该 Agent 的
 *   真实执行入口（如 Example Agent 的 run-grok.sh）并执行，stdout
 *   按行以 cross_device_progress 流回，最后以 cross_device_result
 *   回终态输出；失败以 cross_device_error 回中文原因与 code。
 * - 控制：action=run，args["argv"] = ["__control__", 动作, operationId,
 *   新目标（改目标时）]，args["control"] = {"goal_version":N,"epoch":N}。
 *   服务端对运行中的进程做 SIGSTOP/SIGCONT/SIGTERM，改目标时停掉
 *   旧执行并记下新目标与新 goalVersion。
 * - 两类调用的 args["control"] 都带栅栏快照（goalVersion/epoch）：
 *   服务端用它校验本次调用是不是拿着旧目标来的——改目标后，
 *   旧快照的调用会被拒绝（STALE_GOAL_VERSION），不是静默执行旧目标。
 *   凭证本身是 HandClient 的 legacy v2 凭证（签名/过期/任务/
 *   目标/文件夹绑定），快照是其补充，不是替代。
 *
 * 绝不回落本地执行：本类没有任何本地执行路径；信任、密钥、连接
 * 任一步失败都以 [HandEvent.Blocked] 终态返回中文原因。
 */
data class RemoteTarget(
    /** 对端设备（host/port/指纹来自添加记录 + 配对记录）。 */
    val peer: DiscoveredPeer,
    /** 远端 Agent id（如 "example-agent"，以 hello 清单为准，不写死）。 */
    val agentId: String,
    /** 远端工作文件夹（hello 清单里的 folder，凭证绑定的 folder）。 */
    val folder: String,
    /**
     * 文件夹的来源（V5 跨设备开发，2026-11-12，D1：加字段）：
     * PROJECT = 任务的项目在该设备上的检出文件夹；AGENT_FALLBACK =
     * 无检出链接，回落到 Agent 文件夹（今日行为）。缺省回落态，旧
     * 构造点不动。
     */
    val folderSource: DispatchFolderSource = DispatchFolderSource.AGENT_FALLBACK,
)

/** 下发给远端的控制命令（经 ControlPlane 组装，带执行栅栏快照）。 */
data class RemoteControlCommand(
    val action: ControlAction,
    val operationId: String,
    val newGoal: String? = null,
    val goalVersion: Long = 0,
    val epoch: Long = 0,
)

/** 远端 Agent 的聊天/任务/控制口：调用方只认接口，不认 HandClient。 */
interface RemoteAgentChatPort {
    /**
     * 在远端执行一次 Agent 调用（聊天一句话，或一个分派任务的目标）。
     * 流式回 [HandEvent]：Progress → Result/Blocked 终态。
     */
    fun runAgent(
        target: RemoteTarget,
        taskId: String,
        text: String,
        timeoutSeconds: Long = 300,
        goalVersion: Long = 1,
        epoch: Long = 1,
    ): Flow<HandEvent>

    /**
     * 列出远端文件夹（Hand list）。target.folder 是该 Agent 在设备上
     * 的授权文件夹；[relativePath] 是它下面的相对子路径（"" = 文件夹
     * 本身）。回包同 [runAgent]：Result 的 output 为 JSON 数组
     * `[{name, is_dir}]`（见 `parseFolderListJson`），失败为 Blocked。
     * 绝不回落本地执行。
     *
     * 默认实现直接拒绝：只有真正接 Hand 的端口才实现它
     * （[HandRemoteAgentChatPort]）；测试替身不实现也不影响编译，
     * 它们本来就不会被要求列文件夹。
     */
    fun listFolder(
        target: RemoteTarget,
        taskId: String,
        relativePath: String = "",
    ): Flow<HandEvent> = flowOf(
        HandEvent.Blocked("此端口不支持列文件夹", HandProtocol.Errors.COMMAND_NOT_ALLOWED),
    )

    /**
     * V5 跨设备开发（2026-11-12，D2 新口径）：把一个项目文件夹授权给
     * 远端设备上的某 Agent。
     *
     * 调用约定（App 侧定义，Hand 侧对应实现已集成验证，见 DELIVERY）：
     * - 动作名 `authorize_project_folder`，走现有 Hand 密封通道
     *   （[HandClient] 的 action 白名单已放行本动作）；
     * - 参数：args["agent_id"] = 目标 Agent id，args["folder"] = 要
     *   授权的项目文件夹（绝对路径，精确相等，D3）；
     * - 信封 folder = 待授权的项目文件夹（`folder` 参数），凭证绑
     *   它——Hand 的 verify 例外把信封 folder 临时并入授权表再校验，
     *   凭证 folder 与信封 folder 须一致；taskId 用 `authorize-<hex>`
     *   （非任务调用，与任务 id 命名空间分开）；
     * - Hand 侧把该文件夹逐项记入该 Agent 的 `auth.json` 新 key
     *   `authorized_project_folders`（与现有三路授权合并），回
     *   cross_device_result；尚未实现该动作的 Hand 回
     *   cross_device_error，调用方按 Blocked 如实报告同步失败。
     * 不走 project_folders.json、不走 project-folder CLI——那两个
     * 方案已被新口径取代。
     * 注意：信封 folder 绝不能填 Agent 已授权的文件夹
     * （target.folder）——Hand 从不读 args["folder"]，那样只会把
     * 已授权的文件夹再授权一遍、回 already=true 的“假成功”，项目
     * 文件夹实际没进授权表（2026-11-12 集成缺口，已修）。
     *
     * 默认实现直接拒绝：只有真正接 Hand 的端口才实现它
     * （[HandRemoteAgentChatPort]）；测试替身不实现也不影响编译。
     */
    fun authorizeProjectFolder(
        target: RemoteTarget,
        folder: String,
    ): Flow<HandEvent> = flowOf(
        HandEvent.Blocked("此端口不支持授权项目文件夹", HandProtocol.Errors.COMMAND_NOT_ALLOWED),
    )

    /** 下发一条控制指令（暂停/继续/停止/改目标）。回包同 [runAgent]。 */
    fun sendControl(
        target: RemoteTarget,
        taskId: String,
        command: RemoteControlCommand,
    ): Flow<HandEvent>

    /**
     * V5 修复（2026-11-11，CodeBuddy 卡死轮次）：立即掐断指定任务
     * 的在途远端连接。协程 cancel() 中断不了阻塞 socket 读——停止
     * 只 cancel job 时，轮次要等读超时（180 秒）才终态，期间用户
     * 开不了新对话；关掉连接，阻塞读立刻抛，轮次马上终态。
     * 默认 no-op：测试替身不必接线；[HandRemoteAgentChatPort] 按
     * 任务 id 关掉 HandClient 登记的连接。
     */
    fun abortRun(taskId: String) {}
}

class HandRemoteAgentChatPort(
    private val transport: TransportAdapter,
    private val ownDeviceId: String,
    private val ownKeyPair: DeviceKeys.KeyPair,
    private val trustChecker: PeerTrustChecker,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : RemoteAgentChatPort {

    /**
     * V5 修复（2026-11-11，CodeBuddy 卡死轮次）：在途 Hand 连接
     * 登记表（本端口持有唯一一份；每次建 HandClient 都传同一份，
     * 否则每次新建的 client 各记各的，abort 找不到连接）。
     */
    private val activeCalls = ActiveHandCallRegistry()

    override fun abortRun(taskId: String) {
        activeCalls.abort(taskId)
    }

    companion object {
        /** 控制伪命令的 argv[0]：服务端据此把 run 调用识别为控制指令。 */
        const val CONTROL_ARGV_HEAD: String = "__control__"

        /** Agent 执行伪命令的 argv[0]：服务端据此查 agents.json 执行。 */
        const val AGENT_ARGV_HEAD: String = "agent"

        fun controlWireName(action: ControlAction): String = when (action) {
            ControlAction.PAUSE -> "pause"
            ControlAction.RESUME -> "resume"
            ControlAction.STOP -> "stop"
            ControlAction.CHANGE_GOAL -> "change_goal"
            // 撤销/接管在远端没有独立动作：其实质是停掉正在执行的旧执行。
            ControlAction.REVOKE -> "stop"
            ControlAction.TAKEOVER -> "stop"
        }
    }

    override fun runAgent(
        target: RemoteTarget,
        taskId: String,
        text: String,
        timeoutSeconds: Long,
        goalVersion: Long,
        epoch: Long,
    ): Flow<HandEvent> {
        val blocked = blockedReason(target.peer)
        if (blocked != null) {
            return flowOf(HandEvent.Blocked(blocked, HandProtocol.Errors.NOT_AUTHORIZED))
        }
        val client = clientFor(target.peer) ?: return flowOf(
            HandEvent.Blocked(
                "会话密钥协商失败：对方公钥无效，请重新配对",
                HandProtocol.Errors.NOT_AUTHORIZED,
            ),
        )
        val argv = JSONArray().apply {
            put(AGENT_ARGV_HEAD)
            put(target.agentId)
            put(text)
        }
        return client.call(
            HandCallRequest(
                taskId = taskId,
                agentId = target.agentId,
                peer = target.peer,
                folder = target.folder,
                action = "run",
                args = mapOf(
                    "argv" to argv.toString(),
                    "timeout" to timeoutSeconds.toString(),
                    "control" to fenceSnapshot(goalVersion, epoch),
                ),
            ),
        )
    }

    override fun sendControl(
        target: RemoteTarget,
        taskId: String,
        command: RemoteControlCommand,
    ): Flow<HandEvent> {
        val blocked = blockedReason(target.peer)
        if (blocked != null) {
            return flowOf(HandEvent.Blocked(blocked, HandProtocol.Errors.NOT_AUTHORIZED))
        }
        val client = clientFor(target.peer) ?: return flowOf(
            HandEvent.Blocked(
                "会话密钥协商失败：对方公钥无效，请重新配对",
                HandProtocol.Errors.NOT_AUTHORIZED,
            ),
        )
        val argv = JSONArray().apply {
            put(CONTROL_ARGV_HEAD)
            put(controlWireName(command.action))
            put(command.operationId)
            put(command.newGoal ?: "")
        }
        return client.call(
            HandCallRequest(
                taskId = taskId,
                agentId = target.agentId,
                peer = target.peer,
                folder = target.folder,
                action = "run",
                args = mapOf(
                    "argv" to argv.toString(),
                    "timeout" to "30",
                    "control" to fenceSnapshot(command.goalVersion, command.epoch),
                ),
            ),
        )
    }

    override fun listFolder(
        target: RemoteTarget,
        taskId: String,
        relativePath: String,
    ): Flow<HandEvent> {
        val blocked = blockedReason(target.peer)
        if (blocked != null) {
            return flowOf(HandEvent.Blocked(blocked, HandProtocol.Errors.NOT_AUTHORIZED))
        }
        val client = clientFor(target.peer) ?: return flowOf(
            HandEvent.Blocked(
                "会话密钥协商失败：对方公钥无效，请重新配对",
                HandProtocol.Errors.NOT_AUTHORIZED,
            ),
        )
        // list 不带 control 栅栏快照：服务端只在 args 有 control 时才
        // 校验 epoch/goalVersion；列文件夹是只读查询，不参与执行栅栏。
        return client.call(
            HandCallRequest(
                taskId = taskId,
                agentId = target.agentId,
                peer = target.peer,
                folder = target.folder,
                action = "list",
                args = mapOf("path" to relativePath),
            ),
        )
    }

    override fun authorizeProjectFolder(
        target: RemoteTarget,
        folder: String,
    ): Flow<HandEvent> {
        val blocked = blockedReason(target.peer)
        if (blocked != null) {
            return flowOf(HandEvent.Blocked(blocked, HandProtocol.Errors.NOT_AUTHORIZED))
        }
        val client = clientFor(target.peer) ?: return flowOf(
            HandEvent.Blocked(
                "会话密钥协商失败：对方公钥无效，请重新配对",
                HandProtocol.Errors.NOT_AUTHORIZED,
            ),
        )
        // D2：授权动作本身不执行命令，不带 control 栅栏快照。信封
        // folder 必须是待授权的项目文件夹（folder 参数），凭证绑它——
        // Hand 的 verify 例外正是为这个形状准备的：它把信封 folder
        // 临时并入授权表再校验，凭证 folder 与信封 folder 须一致。
        // 旧写法曾把信封 folder 填成 Agent 已授权的文件夹
        // （target.folder），只把项目文件夹放 args["folder"]，Hand
        // 从不读 args["folder"]，结果是把已授权的文件夹再授权一遍、
        // 回 already=true 的“假成功”，项目文件夹实际没进授权表。
        return client.call(
            HandCallRequest(
                taskId = "authorize-${UUID.randomUUID().toString().replace("-", "")}",
                agentId = target.agentId,
                peer = target.peer,
                folder = folder,
                action = "authorize_project_folder",
                args = mapOf(
                    "agent_id" to target.agentId,
                    "folder" to folder,
                ),
            ),
        )
    }

    /**
     * 本次调用被拦下的中文原因；null = 可以建 HandClient。
     * 与 [HandClient] 的判定口径一致（未配对 / 配对记录里没有公钥），
     * 只是在建 client 之前先判——签名密钥须由会话密钥派生，
     * 没有对方公钥时连 client 都建不出来。
     */
    private fun blockedReason(peer: DiscoveredPeer): String? {
        if (!trustChecker.isTrusted(peer)) {
            return trustChecker.trustFailureReason(peer)
                ?: "未配对：请先在目标设备上点一次确认，完成配对后再试"
        }
        if (trustChecker.peerPublicKey(peer.deviceId) == null) {
            return "配对记录里没有对方的公钥（配对时未完成密钥交换）：请删除配对并重新配对"
        }
        return null
    }

    /**
     * 建本次调用的 HandClient。签名密钥由会话密钥派生
     * （[RemoteCredentialKeys]，两端同算），不是另存的秘密。
     * 调用前须先过 [blockedReason]：这里假设对方公钥已存在。
     */
    private fun clientFor(peer: DiscoveredPeer): HandClient? {
        val peerPublicKey = trustChecker.peerPublicKey(peer.deviceId) ?: return null
        val sessionKey = try {
            DeviceKeys.deriveSessionKey(ownKeyPair.privateKey, peerPublicKey)
        } catch (_: Exception) {
            return null
        }
        return HandClient(
            transport = transport,
            ownDeviceId = ownDeviceId,
            ownKeyPair = ownKeyPair,
            signingKey = RemoteCredentialKeys.signingKey(sessionKey),
            trustChecker = trustChecker,
            nowEpochSeconds = nowEpochSeconds,
            activeCalls = activeCalls,
        )
    }

    private fun fenceSnapshot(goalVersion: Long, epoch: Long): String {
        val obj = JSONObject()
        obj.put("goal_version", goalVersion)
        obj.put("epoch", epoch)
        return obj.toString()
    }
}
