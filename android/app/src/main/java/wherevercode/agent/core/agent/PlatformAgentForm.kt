package wherevercode.agent.core.agent

import wherevercode.agent.brain.BrainKind

/**
 * “平台 Agent”表单的纯逻辑（V5，2026-11-11）。
 *
 * 与 [DeviceAgentForm] 同款做法：只做记录拼装与校验，不碰
 * Android、不读存储、不发网络——MainActivity 的表单只负责取界面
 * 值、调它、按结果 toast 或落盘；密钥值（平台凭证、webhook 签名
 * 密钥）由 MainActivity 另行写进 Keystore，绝不经过这里。
 *
 * 字段（maintainer 点名要的纠正性功能，老项目能加 example agent，V5 也要能加）：
 * - 名字、平台 Agent ID、平台地址（公开构建不预置地址，
 *   端点由它拼出 `https://<host>/api/open/v1/mcp`，见 [PlatformAgent]）；
 * - 专用频道 ID（可选；空着即仍走共享“第一批”群——平台上目前没有
 *   V5 专用频道，见 [PlatformAgent.CHANNEL_NOTE]）；
 * - 可选的 webhook 地址 + 签名密钥引用名（只存引用名，值在 Keystore；
 *   供表单里的“发一条测试通知”用）；
 * - 直接读 GitHub 开关（该 Agent 能直接读 GitHub 审查完整代码）。
 *
 * 不搬平台后台的整套管理页：webhook 唤醒的配置已经在平台上存着了，
 * App 侧只要能寻址到这个 Agent、能发任务、能收回复。
 *
 * 记录形态：kind = CLOUD，access = WEBHOOK（该 Agent 经 webhook 被
 * 平台唤醒），brainKind = PLATFORM_AGENT（新增，不复用 MODEL_API /
 * AGENT_RUNTIME——它既不是模型 API 也不是 CLI），adapterId =
 * `"platform-agent"`，baseUrl = 平台地址（`https://<host>`）。
 * deviceId / binary 一律为空：它不在任何设备上，也不是 CLI。
 */
object PlatformAgentForm {

    /** 表单提交结果：合法记录，或直接展示给用户的拒绝原因。 */
    sealed class Result {
        data class Ok(val record: AgentRecord) : Result()
        data class Rejected(val message: String) : Result()
    }

    fun buildRecord(
        name: String,
        agentId: String,
        platformAgentId: String?,
        platformHost: String?,
        platformChannelId: String?,
        webhookUrl: String?,
        webhookSecretRef: String?,
        directGithubRead: Boolean,
        capabilities: List<String>,
        dispatchable: Boolean,
        knownLimits: String?,
        existing: AgentRecord?,
    ): Result {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return Result.Rejected("名字不能为空。")
        }
        val target = platformAgentId?.trim().takeIf { !it.isNullOrBlank() }
            ?: return Result.Rejected("平台 Agent ID 不能为空，如 example-agent。")
        val host = platformHost?.trim().takeIf { !it.isNullOrBlank() }
            ?: PlatformAgent.DEFAULT_PLATFORM_HOST
        PlatformAgent.platformHostIssue(host)?.let { return Result.Rejected(it) }
        // 专用频道：填了就走该频道，空着即仍走共享“第一批”群
        // （null，见 PlatformAgent.effectiveChannelId），绝不伪造
        // 一个不存在的频道 ID、绝不把空着说成已走专用频道。
        val channelId = platformChannelId?.trim().takeIf { !it.isNullOrBlank() }
        val hook = webhookUrl?.trim().takeIf { !it.isNullOrBlank() }
        if (hook != null && !hook.startsWith("https://") && !hook.startsWith("http://")) {
            return Result.Rejected("webhook 地址须以 https:// 或 http:// 开头。")
        }
        val secretRef = webhookSecretRef?.trim().takeIf { !it.isNullOrBlank() }
        val record = AgentRecord(
            agentId = agentId,
            displayName = trimmedName,
            kind = AgentKind.CLOUD,
            access = AgentAccess.WEBHOOK,
            brainKind = BrainKind.PLATFORM_AGENT,
            adapterId = PlatformAgent.ADAPTER_ID,
            binary = null,
            baseUrl = host.takeIf { it.isNotBlank() }?.let { "https://$it" },
            model = null,
            authRef = null,
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
            platformAgentId = target,
            platformHost = host.takeIf { it.isNotBlank() },
            platformChannelId = channelId,
            webhookUrl = hook,
            webhookSecretRef = secretRef,
            directGithubRead = directGithubRead,
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
