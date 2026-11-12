package wherevercode.agent.core.agent

import org.json.JSONObject

/**
 * 平台令牌签发（V5，2026-11-12，maintainer：App 没有 token 生成按钮）。
 *
 * 背景：maintainer 要用 ChatGPT 登陆 V5 MCP 服务端（OAuth 授权页要填一个
 * V5 agent token），但 App 里没有任何地方能生成 token，只能去 VPS
 * 终端跑 `v5adm.py issue-token`。本类就是设置页“平台令牌”分节的
 * 纯逻辑：调平台 MCP 的 owner 工具 `issue_agent_token` 签发。
 *
 * 服务端口径（与 `v5adm.py issue-token` 同款语义）：
 * - 工具是 owner 专用（服务端对非 owner 调用只回“未知工具”，不
 *   明说权限问题——见 [mapError] 的翻译）；签发目标须是已存在且
 *   启用的 Agent（如 `chatgpt`），不存在就报“Agent 不存在”。
 * - 令牌由服务端现生成（`v5_` + 43 位 base64url），服务端只存
 *   SHA-256 哈希；明文只在本次调用的返回里出现一次。
 * - 令牌继承目标 Agent 的权限（scope 在 Agent 身上，不在令牌上）。
 *
 * 安全红线（与平台凭证同一口径）：
 * - 签发出的令牌只在内存里交给调用方显示一次，绝不写进 Keystore、
 *   记录、日志或任何文件；[IssueTokenResult.Issued] 只带令牌值与
 *   元数据（token_id / agent_id / name），调用方显示完即丢。
 * - 失败消息里绝不带令牌值（服务端报错本身不含令牌；[mapError]
 *   只取前 200 字，且“未知工具”一律翻成通用提示）。
 * - 本类不产生、不缓存 App 自己的平台凭证：凭证每次调用时由
 *   [PlatformChannelMcpTransport] 向 tokenProvider 现取。
 */
sealed class IssueTokenResult {
    /** 签发成功：令牌明文只在此出现一次，调用方显示完即丢，绝不持久化。 */
    data class Issued(
        val token: String,
        val tokenId: String,
        val agentId: String,
        val name: String,
    ) : IssueTokenResult()

    /** 签发失败：消息可直接展示给用户，绝不含令牌值。 */
    data class Failed(val message: String) : IssueTokenResult()
}

class PlatformTokenIssuer(
    /** 一次 MCP `tools/call`（通常是 [PlatformChannelMcpTransport.callTool]）。 */
    private val callTool: (String, JSONObject) -> JSONObject,
) {
    /**
     * 给 [agentId] 签发一个新令牌（名 [name]，用来分辨令牌）。
     * 同步调用（内部是阻塞 HTTP），调用方须在后台线程跑。
     */
    fun issueToken(agentId: String, name: String): IssueTokenResult {
        val target = agentId.trim()
        val label = name.trim()
        if (target.isBlank()) return IssueTokenResult.Failed("Agent ID 不能为空（如 chatgpt）。")
        if (!AGENT_ID_RE.matches(target)) {
            return IssueTokenResult.Failed("Agent ID 只能用字母、数字、横杠和下划线。")
        }
        if (label.isBlank()) return IssueTokenResult.Failed("令牌名称不能为空。")
        if (label.length > MAX_NAME_CHARS) {
            return IssueTokenResult.Failed("令牌名称不能超过 $MAX_NAME_CHARS 字。")
        }
        val result = try {
            callTool(
                TOOL_NAME,
                JSONObject().apply {
                    put("agent_id", target)
                    put("name", label)
                },
            )
        } catch (e: Exception) {
            return IssueTokenResult.Failed(mapError(e))
        }
        val token = result.optString("token", "")
        if (token.isBlank()) return IssueTokenResult.Failed("平台没有返回令牌（签发失败）。")
        return IssueTokenResult.Issued(
            token = token,
            tokenId = result.optString("token_id", ""),
            agentId = result.optString("agent_id", target),
            name = result.optString("name", label),
        )
    }

    companion object {
        /** 服务端 owner 工具名（V5 平台端点，2026-11-12 新增）。 */
        const val TOOL_NAME: String = "issue_agent_token"

        /** 默认签发对象：maintainer 要登陆的 ChatGPT（平台上已有的 Agent）。 */
        const val DEFAULT_AGENT_ID: String = "chatgpt"

        /** 默认令牌名称。 */
        const val DEFAULT_NAME: String = "App 生成"

        /** 服务端 `create_agent_token` 的名称上限（100 字）。 */
        const val MAX_NAME_CHARS: Int = 100

        private val AGENT_ID_RE: Regex = Regex("^[A-Za-z0-9_-]{1,120}$")

        /**
         * 把传输/平台错误翻译成用户能看懂的话。
         *
         * 服务端把“非 owner 调 owner 工具”和“工具不存在”都回成
         * “请求失败：未知工具”（不泄露工具是否存在），所以这里不
         * 敢断言是哪一种：两种可能都写进提示里。
         */
        fun mapError(e: Exception): String {
            val raw = e.message?.trim().takeIf { !it.isNullOrBlank() } ?: "签发失败"
            return when {
                raw.contains("未配置平台 Agent 凭证") ->
                    "未连接平台：请先在平台 Agent 设置里填写平台凭证。"
                raw.contains("未知工具") ->
                    "签发失败：平台端点不支持签发令牌，或当前平台凭证没有 owner 权限。" +
                        "请确认服务端已更新（issue_agent_token 工具）且 App 的平台凭证是 owner 权限。"
                raw.contains("不存在") -> "签发失败：$raw"
                else -> "签发失败：${raw.take(200)}"
            }
        }
    }
}
