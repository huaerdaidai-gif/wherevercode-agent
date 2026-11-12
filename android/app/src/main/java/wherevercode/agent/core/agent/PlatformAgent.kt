package wherevercode.agent.core.agent

import wherevercode.agent.BuildConfig

/**
 * 平台 Agent（Webhook 唤醒，V5，2026-11-11）。
 *
 * 外部平台 Agent 通过 HTTPS MCP 端点收发任务消息。端点主机、默认频道
 * 和可选的私有证书 pin 由用户填写或维护者通过本地 Gradle 配置提供。
 *
 * 本类型只发任务消息，不传代码、不推代码。
 *
 * 收件方口径（2026-11-11 实测 `tools/list` + `agent_channel_list`）：
 * - 平台 MCP `agent_channel_post` 的参数只有 `channel_id` + `text`
 *   + 可选 `reply_to`，**没有收件方字段**。收件方只能写在正文里：
 *   本类型发消息时正文以 `@<平台 Agent 名> ` 开头（群里通行写法，
 *   如 `@Muse`、`@chatgpt`）。
 * - 回复匹配按 `agent_name` 归一化比对：平台群成员里 Example Agent 的
 *   `agent_name` 是 `"example agent"`（带空格），而表单里用户填的平台
 *   Agent ID 常是 `"example-agent"`（带横杠）——[normalizeAgentName]
 *   去掉空格/横杠/下划线再比大小写，两边才对得上。不要编造 ID：
 *   平台 Agent 的标识以表单值与群成员列表实际的为准。
 * - 默认频道仅在维护者通过本地配置提供时启用。公开构建不会预置频道
 *   标识，用户也可以在 Agent 记录中填写频道 ID。
 *
 * 端点配置：
 * - 端点由平台地址字段拼出：`https://<platformHost>/api/open/v1/mcp`
 *   （见 [mcpEndpoint]）。公开构建没有预置端点；可通过本地 Gradle 属性
 *   提供默认主机，或由用户在 Agent 表单中填写。
 *
 * 凭证红线：平台凭证（App 在平台上注册的 Agent 的 Bearer 令牌）
 * 与 webhook 签名密钥只存本机 Keystore（见
 * [PlatformAgentCredentialStore]），绝不进记录、不进代码、不进
 * 任何文件；本文件不含任何真实凭证值。
 */
object PlatformAgent {
    /** 适配器 id：平台 Agent 记录的唯一标识（见 [isPlatformAgentRecord]）。 */
    const val ADAPTER_ID: String = "platform-agent"

    /** 平台 MCP 端点路径（固定后缀；主机由平台地址字段定，见 [mcpEndpoint]）。 */
    const val MCP_PATH: String = "/api/open/v1/mcp"

    /**
     * 平台 MCP 端点：由平台地址字段拼出 `https://<host>/api/open/v1/mcp`。
     * 主机名为空/未填时使用本地构建配置；公开构建缺省为空。
     */
    fun mcpEndpoint(platformHost: String?): String {
        val host = platformHost?.trim().takeIf { !it.isNullOrBlank() }
            ?: DEFAULT_PLATFORM_HOST
        return host.takeIf { it.isNotBlank() }?.let { "https://$it$MCP_PATH" }.orEmpty()
    }

    /** 默认平台地址（表单可改；端点由它拼出，见 [mcpEndpoint]）。 */
    val DEFAULT_PLATFORM_HOST: String = BuildConfig.PLATFORM_HOST.trim()

    /** 可选的构建时默认频道 ID；公开构建为空。 */
    val DEFAULT_CHANNEL_ID: String = BuildConfig.PLATFORM_DEFAULT_CHANNEL.trim()

    /**
     * 实际收发频道：记录填了频道 ID 就使用它，否则使用本地配置默认值。
     */
    fun effectiveChannelId(platformChannelId: String?): String =
        platformChannelId?.trim().takeIf { !it.isNullOrBlank() }
            ?: DEFAULT_CHANNEL_ID

    /** 该记录是否仍走共享“第一批”群（未配专用频道、或配的就是它）。 */
    fun isSharedChannel(platformChannelId: String?): Boolean =
        DEFAULT_CHANNEL_ID.isNotBlank() && effectiveChannelId(platformChannelId) == DEFAULT_CHANNEL_ID

    /**
     * 平台地址栏说明：公开构建没有预置服务地址。
     */
    /**
     * 可选的构建时频道 ID；公开构建为空。
     */
    val V5_CHANNEL_ID: String = BuildConfig.PLATFORM_V5_CHANNEL.trim()

    const val ENDPOINT_NOTE: String =
        "公开版不会预置平台地址。请填写你的平台主机名；端点为 https://<平台地址>/api/open/v1/mcp。"

    /**
     * 专用频道栏说明：空着时仅使用本地构建配置的默认频道；公开构建不
     * 提供默认值，用户可在这里填写平台提供的频道 ID。
     */
    const val CHANNEL_NOTE: String =
        "专用频道 ID（可选；公开版不预置频道 ID）。"

    /** 平台消息单条上限（`agent_channel_post` 的 `text` maxLength）。 */
    const val MAX_POST_CHARS: Int = 4000

    /** 是否平台 Agent 记录（不认 brainKind，只认 adapterId，与 Manus 同款做法）。 */
    fun isPlatformAgentRecord(record: AgentRecord): Boolean =
        record.adapterId.equals(ADAPTER_ID, ignoreCase = true)

    /**
     * Agent 名归一化：去空白、横杠、下划线，转小写。
     * `"example-agent"`、`"example agent"`、`"Example_Agent"` 都归一为 `"exampleagent"`。
     */
    fun normalizeAgentName(name: String): String =
        name.lowercase().filter { it != ' ' && it != '-' && it != '_' }

    /** 两条 Agent 名是否指同一位（归一化后相等）。 */
    fun sameAgent(a: String, b: String): Boolean =
        normalizeAgentName(a) == normalizeAgentName(b)

    /** 发给该 Agent 的正文前缀：`@<平台 Agent ID> `（群里通行写法）。 */
    fun addressPrefix(platformAgentId: String): String = "@$platformAgentId "

    /** 发往平台的完整正文：收件方前缀 + 用户原文。 */
    fun buildWireText(platformAgentId: String, text: String): String =
        addressPrefix(platformAgentId) + text
}
