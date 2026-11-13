package wherevercode.agent.core.agent

import org.json.JSONObject
import wherevercode.agent.core.extension.mcp.McpClient
import wherevercode.agent.core.extension.mcp.McpJsonRpc.McpNotification
import wherevercode.agent.core.mcp.WsConnection

/**
 * MCP 工具箱的事件订阅（2026-11-13，MCP 能力补齐）。
 *
 * 缺口：服务端有频道 WS 推送端点 `GET /api/open/v1/channel/ws`，
 * 但 MCP 工具箱里没有任何订阅——此前只有团队频道页在用
 * [ChannelWsClient]（见 MainActivity.startTeamChannelFeed），
 * 调 MCP 工具的一方收不到推送，只能轮询。
 *
 * 本类不另造一套 WS 逻辑，直接复用 [ChannelFeed]（WS 推送为主、
 * 断线 15 秒轮询兜底、45 秒后重连 WS、按 id 去重——与频道页同一
 * 套行为），只加一层转交：每条新消息除了交给本地监听
 * [onChannelMessage]，还会以 MCP 通知形式交给已连的 [McpClient]
 *（[McpClient.handleNotification]，方法名
 * `notifications/channel/message`）——MCP 层的 `onNotification`
 * 观察者不用改订阅方式就能收到频道推送。
 *
 * 诚实区分来源：`notifications/channel/message` 不是服务端 MCP
 * 通知，是频道 WS 推来的消息经本类转交的——方法名本身就带
 * channel，调用方据此前缀区分，不冒充服务端发来的 MCP 通知。
 * 订阅不到消息时不伪造：WS 断了就走 ChannelFeed 的轮询兜底，
 * 状态经 [onConnectionChange] 如实报（LIVE / POLLING /
 * CONNECTING / STOPPED），调用方看得到现在是推送还是兜底。
 *
 * 线程：ChannelFeed 在自己的工作线程回调 [onChannelMessage]；
 * 转交 [McpClient.handleNotification] 是同步回调（与传输层通知
 * 同款），不在此切线程——调用方自己决定要不要换线程。
 * 令牌只进 [ChannelWsClient] 的 Authorization 请求头，永不进日志。
 */
class McpChannelEventFeed(
    private val channelId: String,
    transport: PlatformChannelTransport,
    wsFactory: (onText: (String) -> Unit, onClosed: (String) -> Unit) -> WsConnection,
    /**
     * 已连的 MCP 客户端（可空）：给了就把频道事件以通知形式转交
     * 给它；不给就只走本地监听 [onChannelMessage]。
     */
    private val mcpClient: McpClient? = null,
    /** 每条新频道消息（去重后）的监听。 */
    var onChannelMessage: ((PlatformChannelMessage) -> Unit)? = null,
    /** 连接状态变化的监听（LIVE = WS 推送，POLLING = 断线兜底）。 */
    var onConnectionChange: ((ChannelConnectionState, String?) -> Unit)? = null,
) {
    private val feed = ChannelFeed(
        channelId = channelId,
        transport = transport,
        wsFactory = wsFactory,
        onMessages = { messages ->
            for (message in messages) {
                try {
                    onChannelMessage?.invoke(message)
                } catch (_: Exception) {
                }
                forwardToMcp(message)
            }
        },
        onConnectionChange = { state, error ->
            try {
                onConnectionChange?.invoke(state, error)
            } catch (_: Exception) {
            }
        },
    )

    val connectionState: ChannelConnectionState
        get() = feed.connectionState

    val lastError: String?
        get() = feed.lastError

    /** 开始订阅（幂等；已在跑时直接返回）。 */
    fun start() = feed.start()

    /** 停止订阅（工作线程退出，WS 关闭）。 */
    fun stop() = feed.stop()

    /**
     * 把一条频道消息以 MCP 通知形式交给已连客户端。
     * 通知方法名带 channel，与服务端 MCP 通知区分，不冒充。
     */
    private fun forwardToMcp(message: PlatformChannelMessage) {
        val client = mcpClient ?: return
        val params = JSONObject().apply {
            put("channel_id", channelId)
            put("message", JSONObject().apply {
                put("id", message.id)
                put("agent_id", message.agentId)
                put("agent_name", message.agentName)
                put("text", message.text)
                put("created_at", message.createdAtSeconds)
            })
        }
        try {
            client.handleNotification(McpNotification(CHANNEL_MESSAGE_NOTIFICATION, params))
        } catch (_: Exception) {
        }
    }

    companion object {
        /** 频道 WS 消息转交 MCP 层的通知方法名（不是服务端 MCP 通知）。 */
        const val CHANNEL_MESSAGE_NOTIFICATION: String = "notifications/channel/message"

        /**
         * 按平台频道建一个订阅（与 MainActivity.startTeamChannelFeed
         * 同款接线：平台 MCP 传输 + [ChannelWsClient]；本类只多一个
         * 转交 MCP 客户端的口）。
         *
         * 调用方（MainActivity / 频道工人）在频道页打开、平台凭证
         * 就绪后调一次；关闭时调 [stop]。平台主机与频道 ID 由调用方
         * 从平台 Agent 设置里取（见 teamChannelEndpoint）。
         */
        fun forPlatformChannel(
            host: String,
            channelId: String,
            tokenProvider: () -> String?,
            mcpClient: McpClient? = null,
            endpoint: String = PlatformAgent.mcpEndpoint(host),
        ): McpChannelEventFeed {
            val transport = PlatformChannelMcpTransport(
                tokenProvider = tokenProvider,
                endpoint = endpoint,
            )
            return McpChannelEventFeed(
                channelId = channelId,
                transport = transport,
                wsFactory = { _, onClosed ->
                    ChannelWsClient(
                        host = host,
                        port = 443,
                        channelId = channelId,
                        tokenProvider = tokenProvider,
                    ).also { it.onClosed = onClosed }
                },
                mcpClient = mcpClient,
            )
        }
    }
}
