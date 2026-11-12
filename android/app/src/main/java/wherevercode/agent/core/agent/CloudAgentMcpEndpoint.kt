package wherevercode.agent.core.agent

/**
 * 云端 Agent 要连的 App 侧 MCP 端点（V5，2026-11-11）。
 *
 * 方向：云端 Agent 把 App 加为自定义 MCP 连接器、自己来领单——
 * 是 Agent 来连 App，不是 App 去调 Agent。这是 MCP 服务端的工作，
 * 与 MCP 客户端（App 去连别人的 MCP 服务）是两面。
 *
 * 现状（诚实缺口，不要硬造一个假端点）：
 * - App 内没有任何监听式 MCP 服务端：`core/mcp/HandServer` 是协议
 *   处理（无监听 socket），`LoopbackCallbackServer` 只是 OAuth 回环
 *   回调（127.0.0.1 临时端口）。[publicUrl] 因此返回 null。
 * - 手机 App 没有公网地址：就算本机起了监听，Grok / ChatGPT /
 *   Manus / Muse 这些云端 App 也连不进来。能连进来的路只有两条，
 *   都还没接线：(a) 公网中继/隧道（手机常驻出网通道）；(b) 平台
 *   转发（平台 MCP 做中继，把云端 Agent 的领单请求转给 App）。
 * - 所以注册、角色与设置说明先行（本分支已做），端点暴露另算。
 *
 * 接线点（以后做 MCP 服务端时从这里接，不另起炉灶）：
 * 1. 监听/中继：按上面 (a) 或 (b) 二选一，给出真实可达地址后填进
 *    [publicUrl]（须是云端 Agent 真能连到的地址，LAN 地址不算——
 *    云端 Agent 不在局域网里）。
 * 2. 鉴权：每个请求验 `Authorization: Bearer <每 Agent 钥匙>`，
 *    钥匙对 [CloudAgentCredentialStore] 里的值；验不过 401。
 *    令牌形状与 [McpGuard] 的 `wco_` 前缀一致；限流与路径脱敏
 *    复用 `McpGuard`（每分钟 60 次、`/api/open/v1/mcp/` 前缀）。
 * 3. 角色 enforcement：`tools/list` 按记录的 [AgentRecord.cloudRoles]
 *    与 [AgentRecord.canDispatch] 过滤（`McpGuard.filterToolsForRole`
 *    已有角色过滤的现成做法）；关掉“允许它派活”时不暴露派活工具。
 * 4. 工具面：领单至少要 `list_my_tasks` / `claim_task` /
 *    `report_progress` / `submit_result` / `release_task`（与平台
 *    MCP 同名同参，云端 Agent 领单的口径与平台一致）。
 * 5. 分工（maintainer 2026-11-10 08:33–08:34，Example Agent 接入方向）：
 *    webhook 是实时唤醒通道，MCP 是通讯通道，App 是工具提供方——
 *    被 webhook 叫醒的云端 Agent（如 Example Agent）经 MCP 领单、读授权
 *    项目、交状态/日志/diff/测试结果/消息；双向消息、追加指令、
 *    暂停/停止/改目标也走这个通道。工具受授权 epoch + 执行围栏
 *    约束，无授权不写、有审计轨迹。只做最小闭环需要的 MCP 面，
 *    不做完整 MCP 平台。老项目 `cross_device_mcp.py`（stdio
 *    JSON-RPC 2.0，list/read/write/run）是已验证的设计参照，
 *    只学思路、代码自主写。
 */
object CloudAgentMcpEndpoint {
    /** MCP 路径（与平台 MCP 同形，见 McpGuard.MCP_PATH_PREFIX）。 */
    const val MCP_PATH: String = "/api/open/v1/mcp"

    /**
     * 云端 Agent 应填的连接地址；现在没有可达的地址，返回 null。
     * 调用方（设置说明）必须如实显示“尚未开通”，绝不能拿
     * `http://127.0.0.1`、LAN 地址或平台 MCP 端点冒充——前两者云端
     * Agent 连不到，后者是平台的端点、不是 App 的。
     */
    fun publicUrl(): String? = null

    /** 端点未开通时，设置说明里展示的缺口文案。 */
    fun gapNote(): String =
        "连接地址尚未开通：手机 App 没有公网地址，云端 Agent 现在还连不进来。" +
            "要等 MCP 服务端接线（公网中继或平台转发）后，这里才会显示真实地址。" +
            "下面这把钥匙已经生成并只存在本机 Keystore，可先留着；地址开通后，" +
            "把地址填进对方 App 的自定义连接器、钥匙放进 Authorization: Bearer 请求头即可。"
}
