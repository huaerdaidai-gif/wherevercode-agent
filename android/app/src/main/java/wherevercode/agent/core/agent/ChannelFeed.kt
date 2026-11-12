package wherevercode.agent.core.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import wherevercode.agent.core.mcp.WsConnection

/** 频道连接状态（频道页顶栏如实显示；LIVE = WS 推送，POLLING = 断线兜底）。 */
enum class ChannelConnectionState {
    CONNECTING, LIVE, POLLING, STOPPED,
}

/**
 * 解析端点 WS 事件 JSON。`{"type":"message", "message": {...}}` 返回
 * 一条消息；hello / ping / 未知类型返回 null（调用方忽略）。
 * 消息字段与 [PlatformChannelMessage] 一一对应；id 为空的直接丢弃。
 */
fun parseChannelWsEvent(text: String): PlatformChannelMessage? {
    val obj = try {
        JSONObject(text)
    } catch (_: Exception) {
        return null
    }
    if (obj.optString("type") != "message") return null
    val msg = obj.optJSONObject("message") ?: return null
    val id = msg.optString("id").takeIf { it.isNotBlank() } ?: return null
    return PlatformChannelMessage(
        id = id,
        agentId = msg.optString("agent_id"),
        agentName = msg.optString("agent_name"),
        text = msg.optString("text"),
        createdAtSeconds = msg.optDouble("created_at", 0.0),
    )
}

/**
 * V5 频道消息流（P3，2026-11-11）：WS 推送为主，轮询只作断线兜底。
 *
 * 规则（maintainer 19:43、21:01 两次定过：不搞过时的技术，轮询不留作主路径）：
 * - 正常时：先拉一页最新消息，再起 WS；新消息由服务端推过来，
 *   [onMessages] 在工作线程回调，调用方自己切 UI 线程。
 * - WS 一断：状态切 POLLING，每 15 秒轮询一次（after_id = 已见的
 *   最新一条，断档的消息靠游标捡回来——与 example-agent 回复游标修复
 *   同款思路），45 秒后重新连 WS；连上立刻回 LIVE。
 * - 去重：WS 推来的与轮询拉到的可能重叠，按 id 去重（会话内最多记
 *   1000 条，超了丢最旧的）。
 * - 本类不发消息、不标已读、不管轮次上限——那是频道页控制器的事。
 * - [stop] 后工作线程退出；[start] 幂等（已在跑时直接返回）。
 */
class ChannelFeed(
    private val channelId: String,
    private val transport: PlatformChannelTransport,
    private val wsFactory: (onText: (String) -> Unit, onClosed: (String) -> Unit) -> WsConnection,
    private val onMessages: (List<PlatformChannelMessage>) -> Unit,
    private val onConnectionChange: (ChannelConnectionState, String?) -> Unit = { _, _ -> },
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val reconnectAfterMs: Long = RECONNECT_AFTER_MS,
) {
    @Volatile
    var connectionState: ChannelConnectionState = ChannelConnectionState.STOPPED
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var running = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var currentWs: WsConnection? = null

    private val seenIds = LinkedHashSet<String>()
    private var lastSeenId: String? = null

    fun start() {
        if (running) return
        running = true
        setState(ChannelConnectionState.CONNECTING, null)
        worker = Thread({ loop() }, "channel-feed-$channelId").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        currentWs?.close()
        worker?.interrupt()
        setState(ChannelConnectionState.STOPPED, null)
    }

    private fun loop() {
        pollOnce()
        while (running) {
            setState(ChannelConnectionState.CONNECTING, null)
            val ws = try {
                wsFactory(::onWsText) { reason -> lastError = reason }
            } catch (e: Exception) {
                lastError = (e.message ?: e.javaClass.simpleName).take(200)
                null
            }
            if (ws == null || !running) {
                pollFallback()
                continue
            }
            currentWs = ws
            try {
                ws.run(::onWsText) // 阻塞直到关闭/出错/本地 close
            } catch (e: Exception) {
                lastError = (e.message ?: e.javaClass.simpleName).take(200)
            } finally {
                currentWs = null
            }
            if (!running) break
            setState(ChannelConnectionState.POLLING, lastError)
            pollFallback()
        }
        setState(ChannelConnectionState.STOPPED, null)
    }

    /** 断线兜底：轮询 [reconnectAfterMs]，到点回主循环重连 WS。 */
    private fun pollFallback() {
        val deadline = System.currentTimeMillis() + reconnectAfterMs
        while (running && System.currentTimeMillis() < deadline) {
            pollOnce()
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun pollOnce() {
        val messages = try {
            runBlocking { transport.pollMessages(channelId, lastSeenId, POLL_LIMIT) }
        } catch (e: Exception) {
            lastError = (e.message ?: e.javaClass.simpleName).take(200)
            return
        }
        deliver(messages)
    }

    private fun onWsText(text: String) {
        if (connectionState != ChannelConnectionState.LIVE) {
            setState(ChannelConnectionState.LIVE, null)
        }
        val message = parseChannelWsEvent(text) ?: return
        deliver(listOf(message))
    }

    /** 去重 + 更新游标 + 交给调用方（调用方在工作线程收到回调）。 */
    private fun deliver(messages: List<PlatformChannelMessage>) {
        if (messages.isEmpty()) return
        val fresh = messages.filter { it.id.isNotBlank() && seenIds.add(it.id) }
        if (fresh.isEmpty()) return
        if (seenIds.size > MAX_SEEN_IDS) {
            val drop = seenIds.size - MAX_SEEN_IDS
            val iterator = seenIds.iterator()
            repeat(drop) { if (iterator.hasNext()) { iterator.next(); iterator.remove() } }
        }
        lastSeenId = fresh.last().id
        onMessages(fresh)
    }

    private fun setState(state: ChannelConnectionState, error: String?) {
        connectionState = state
        if (error != null) lastError = error
        onConnectionChange(state, error)
    }

    companion object {
        const val POLL_LIMIT: Int = 20
        const val POLL_INTERVAL_MS: Long = 15_000L
        const val RECONNECT_AFTER_MS: Long = 45_000L
        const val MAX_SEEN_IDS: Int = 1000
    }
}
