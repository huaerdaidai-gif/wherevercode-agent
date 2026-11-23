package wherevercode.agent.core.platform

import java.util.concurrent.CountDownLatch
import org.json.JSONObject
import wherevercode.agent.core.mcp.WsConnection

/**
 * 本地频道的「WS 连接」（频道三层一期·本地层）：与远程
 * ChannelWsClient 同一个 [WsConnection] 形状，但不建任何套接字——
 * 订阅 [LocalChannelService] 的本地消息总线，新帖以与平台 WS 完全
 * 相同的事件 JSON（`{"type":"message","message":{...}}`）交给
 * ChannelFeed，于是频道页的推送/兜底/去重逻辑本地远程一视同仁，
 * 本地推送是进程内直达（比网络 WS 更快，不冒充网络连接）。
 *
 * [run] 阻塞到 [close] 或线程被中断（ChannelFeed 回前台重连靠中断）。
 */
class LocalChannelWsConnection(
    private val channelId: String,
    private val serviceProvider: () -> LocalChannelService? = { LocalPlatform.serviceOrNull() },
) : WsConnection {
    private val latch = CountDownLatch(1)

    @Volatile
    private var open = false

    @Volatile
    private var listener: ((JSONObject) -> Unit)? = null

    override fun run(onText: (String) -> Unit) {
        val service = serviceProvider() ?: return
        val forwarding: (JSONObject) -> Unit = { event -> onText(event.toString()) }
        listener = forwarding
        open = true
        service.addMessageListener(channelId, forwarding)
        try {
            latch.await()
        } catch (_: InterruptedException) {
            // 回前台重连/停订都会中断工作线程：当作连接关闭返回。
        }
        close()
    }

    override fun close() {
        val current = listener
        listener = null
        open = false
        if (current != null) {
            serviceProvider()?.removeMessageListener(channelId, current)
        }
        latch.countDown()
    }

    override val isOpen: Boolean
        get() = open
}
