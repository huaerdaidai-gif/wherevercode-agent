package wherevercode.agent.core.agent

import wherevercode.agent.brain.BrainKind

/**
 * “云端 Agent”表单的纯逻辑（V5，2026-11-11）。
 *
 * 与 [DeviceAgentForm] / [PlatformAgentForm] 同款做法：只做记录
 * 拼装与校验，不碰 Android、不读存储、不发网络、不生成钥匙——
 * MainActivity 的表单只负责取界面值、调它、按结果落盘；每 Agent
 * 一个的随机 Bearer 钥匙由 MainActivity 在保存时现生成、写进
 * Keystore（见 [CloudAgentCredentialStore]），绝不经过这里。
 *
 * 字段（老项目“添加云端 Agent”对话框，照抄机制）：
 * - 在哪干活：云端，或归到某台已加的设备下（只是一个标注：归到
 *   设备下就和那台设备的 Agent 分一组，不改变权限）；
 * - 角色多选：开发、审查（默认勾上）、递代码；
 * - “允许它派活”开关：开着时也能给其他 Agent 派活，关掉就只能
 *   领活、读、交结果。
 *
 * 记录形态：kind = CLOUD（在哪干活不改变 kind：归到设备下也只是
 * 分组，[cloudHomeDeviceId] 只做分组、永不参与路由），access =
 * CONNECTOR（对方把 App 加为自定义 MCP 连接器、自己来领单），
 * brainKind = CLOUD_AGENT（新增），adapterId = `"cloud-agent"`。
 * 钥匙引用名（[AgentRecord.authRef]）由调用方在保存时填入
 * （`"cloud-agent:<agentId>:mcp-token"`），本函数只校验它已由
 * 调用方给出、且不是钥匙值本身（[AgentRecord.validate] 兜底）。
 */
object CloudAgentForm {

    /** 表单提交结果：合法记录，或直接展示给用户的拒绝原因。 */
    sealed class Result {
        data class Ok(val record: AgentRecord) : Result()
        data class Rejected(val message: String) : Result()
    }

    fun buildRecord(
        name: String,
        agentId: String,
        cloudHomeDeviceId: String?,
        roles: List<String>,
        canDispatch: Boolean,
        keyRef: String?,
        capabilities: List<String>,
        dispatchable: Boolean,
        knownLimits: String?,
        existing: AgentRecord?,
    ): Result {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return Result.Rejected("名字不能为空。")
        }
        val cleanRoles = roles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanRoles.isEmpty()) {
            return Result.Rejected("角色至少勾一个：开发、审查、递代码。")
        }
        val unknown = cleanRoles.firstOrNull { it !in CloudAgent.ALL_ROLES }
        if (unknown != null) {
            return Result.Rejected("未知角色：$unknown。")
        }
        val ref = keyRef?.trim().takeIf { !it.isNullOrBlank() }
            ?: return Result.Rejected("保存失败：云端 Agent 的钥匙引用名缺失，请重试保存。")
        val record = AgentRecord(
            agentId = agentId,
            displayName = trimmedName,
            kind = AgentKind.CLOUD,
            access = AgentAccess.CONNECTOR,
            brainKind = BrainKind.CLOUD_AGENT,
            adapterId = CloudAgent.ADAPTER_ID,
            binary = null,
            baseUrl = null,
            model = null,
            authRef = ref,
            capabilities = capabilities,
            quotaRule = existing?.quotaRule ?: AgentRecord.defaultQuotaRule(AgentKind.CLOUD),
            dispatchable = dispatchable,
            // 开关（2026-11-12）：编辑不重置开关——停用的改完仍是停用。
            enabled = existing?.enabled ?: true,
            knownLimits = knownLimits,
            addHint = existing?.addHint,
            loginState = existing?.loginState ?: AgentLoginState.UNKNOWN,
            lastTestedAtEpochSeconds = existing?.lastTestedAtEpochSeconds,
            lastTestSummary = existing?.lastTestSummary,
            cloudRoles = cleanRoles,
            canDispatch = canDispatch,
            cloudHomeDeviceId = cloudHomeDeviceId?.trim().takeIf { !it.isNullOrBlank() },
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
