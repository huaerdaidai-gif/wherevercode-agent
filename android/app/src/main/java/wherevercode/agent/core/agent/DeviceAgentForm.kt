package wherevercode.agent.core.agent

import wherevercode.agent.brain.BrainKind

/**
 * “设备上的 Agent”表单的纯逻辑（V5，2026-11-11）。
 *
 * 背景：添加 Agent 表单此前只有三类——云端模型 API / 订阅授权 Agent /
 * 本机 CLI Agent；“本机 CLI”指手机沙箱里的二进制，远程设备（Mac /
 * VPS / 小米 12）上的 Agent 没法加，Mac Codex 就卡在这里（maintainer
 * 2026-11-11 03:24 截图）。
 *
 * 本对象只做记录拼装与校验，不碰 Android、不读存储：MainActivity
 * 的表单只负责取界面值、调它、按结果 toast 或落盘。
 *
 * 远端路由的硬要求（见 MainActivity.resolveRemoteTarget）：记录
 * 必须同时有 deviceId 与非空 deviceFolder，否则 isRemoteAgent
 * 为 false、聊天报“未接入”。所以文件夹是必填项，不给空着存；
 * deviceId 必须是在设备页已加过的远程设备 id，原样采用，不重写。
 */
object DeviceAgentForm {

    /** 表单提交结果：合法记录，或直接展示给用户的拒绝原因。 */
    sealed class Result {
        data class Ok(val record: AgentRecord) : Result()
        data class Rejected(val message: String) : Result()
    }

    fun buildRecord(
        name: String,
        agentId: String,
        devicesEmpty: Boolean,
        deviceId: String?,
        binary: String?,
        deviceFolder: String?,
        adapterId: String?,
        capabilities: List<String>,
        dispatchable: Boolean,
        knownLimits: String?,
        existing: AgentRecord?,
    ): Result {
        if (devicesEmpty) {
            return Result.Rejected("还没加设备：请先去设备页添加远程设备，再回来加 Agent。")
        }
        val device = deviceId?.trim().takeIf { !it.isNullOrBlank() }
            ?: return Result.Rejected("请先选择设备。")
        val bin = binary?.trim().takeIf { !it.isNullOrBlank() }
            ?: return Result.Rejected("设备上的 Agent 需要填写 CLI 二进制名，如 codex。")
        val folder = deviceFolder?.trim().takeIf { !it.isNullOrBlank() }
            ?: return Result.Rejected(
                "设备上的 Agent 需要填写该设备上的工作文件夹（如 /Users/example-user）：" +
                    "聊天与任务路由要用，没填的记录发不出去。",
            )
        val record = AgentRecord(
            agentId = agentId,
            displayName = name,
            kind = AgentKind.DEVICE,
            access = AgentAccess.CLI,
            brainKind = BrainKind.AGENT_RUNTIME,
            adapterId = adapterId?.trim().takeIf { !it.isNullOrBlank() } ?: bin,
            binary = bin,
            deviceId = device,
            deviceFolder = folder,
            capabilities = capabilities,
            quotaRule = existing?.quotaRule ?: AgentRecord.defaultQuotaRule(AgentKind.DEVICE),
            dispatchable = dispatchable,
            // 开关（2026-11-12）：编辑不重置开关——停用的改完仍是停用。
            enabled = existing?.enabled ?: true,
            knownLimits = knownLimits,
            addHint = existing?.addHint,
            loginState = existing?.loginState ?: AgentLoginState.UNKNOWN,
            lastTestedAtEpochSeconds = existing?.lastTestedAtEpochSeconds,
            lastTestSummary = existing?.lastTestSummary,
            createdAtEpochSeconds = existing?.createdAtEpochSeconds ?: 0,
            updatedAtEpochSeconds = existing?.updatedAtEpochSeconds ?: 0,
        )
        val errors = record.validate()
        if (errors.isNotEmpty()) {
            return Result.Rejected("保存失败：${errors.joinToString("；")}")
        }
        return Result.Ok(record)
    }
}
