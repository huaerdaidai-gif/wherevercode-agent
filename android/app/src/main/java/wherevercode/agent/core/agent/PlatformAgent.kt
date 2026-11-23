package wherevercode.agent.core.agent

import wherevercode.agent.BuildConfig
import java.net.URI

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
 * - 内网例外（频道三层二期）：平台地址是私有/环回/尾网地址时走
 *   `http://`——同网设备上的 App 内网服务没有公网证书，强上 HTTPS
 *   只会全灭；与 2026-11-18 自动发现「私有走 http、公网走 https」
 *   定版同口径。公网地址仍只许 HTTPS。
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
     * 平台 MCP 端点：由平台地址字段拼出 `<协议>://<host>/api/open/v1/mcp`。
     * 协议按地址定：内网地址（[isLanAddress]）用 http，其余用 https。
     * 主机名为空/未填时使用本地构建配置；公开构建缺省为空。
     */
    fun mcpEndpoint(platformHost: String?): String {
        val host = platformHost?.trim().takeIf { !it.isNullOrBlank() }
            ?: DEFAULT_PLATFORM_HOST
        return host.takeIf { it.isNotBlank() && platformHostIssue(it) == null }
            ?.let { "${if (isLanAddress(it)) "http" else "https"}://$it$MCP_PATH" }.orEmpty()
    }

    /**
     * 平台地址是否内网地址：私有 IPv4 段（10/8、172.16/12、192.168/16）、
     * 运营商级 NAT 段 100.64/10（Tailscale 等尾网）、环回（127/8、
     * localhost）与 .local 局域网名。只认字面形状，解析不了的一律
     * 按公网处理（走 https），不给拼错的地址开 http 口子。
     */
    fun isLanAddress(platformHost: String?): Boolean {
        val hostname = platformHost?.trim()?.substringBefore(':').orEmpty()
        if (hostname.isEmpty()) return false
        if (hostname.equals("localhost", ignoreCase = true)) return true
        if (hostname.endsWith(".local", ignoreCase = true)) return true
        val parts = hostname.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it < 0 || it > 255 }) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 127 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 100 && octets[1] in 64..127 -> true
            else -> false
        }
    }

    /**
     * 内网 WS 目标：把平台地址字段拆成（主机名，端口），没写端口时
     * 用 http 默认 80。只供 [isLanAddress] 为真的地址使用（频道 WS
     * 明文通道）；公网仍走 443 + TLS，不经这里。
     */
    fun lanWsTarget(platformHost: String?): Pair<String, Int> {
        val host = platformHost?.trim().orEmpty()
        val hostname = host.substringBefore(':').ifBlank { host }
        val port = host.substringAfter(':', "").toIntOrNull()
            ?.takeIf { it in 1..65535 } ?: 80
        return hostname to port
    }

    /** Return an actionable validation error for the host-only platform address field. */
    fun platformHostIssue(value: String?): String? {
        val host = value?.trim().orEmpty()
        if (host.isBlank()) return "平台地址不能为空。"
        if (host.any { it.isWhitespace() } || host.any { it in "/?#@" } || host.endsWith(':')) {
            return "平台地址只填主机名或主机名:端口，不要填写协议、路径或凭证。"
        }
        val parsed = try {
            URI("https://$host")
        } catch (_: Exception) {
            return "平台地址格式无效，请填写主机名或主机名:端口。"
        }
        if (parsed.host.isNullOrBlank() || parsed.rawUserInfo != null ||
            parsed.rawPath.orEmpty().isNotEmpty() || parsed.rawQuery != null || parsed.rawFragment != null ||
            parsed.port == 0 || parsed.port > 65535
        ) {
            return "平台地址格式无效，请填写主机名或主机名:端口。"
        }
        return null
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
        "公开版不会预置平台地址。请填受信任 HTTPS 证书对应的域名（可带端口），不要填协议或路径；自签名证书会被拒绝。端点为 https://<平台地址>/api/open/v1/mcp。内网例外：填局域网/尾网地址（如 192.168.1.7:18744）时自动走 http 连同网设备上的内网服务。"

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
