package wherevercode.agent.core.platform.uplink

import org.json.JSONObject

/** 上行配置。url 可配置（开发期可指本地 mock，如 ws://127.0.0.1:8787/seat）。 */
data class UplinkConfig(
    val url: String = DEFAULT_URL,
    val token: String,
    val appInstanceId: String,
    val agentId: String,
    val appVersion: String,
    val platform: String = "android",
    val heartbeatMs: Long = 30_000L,
    /**
     * 契约 v1 没有“事件确认帧”：true（默认）= 成功写入 WS 即视为送达并出队；
     * false = 事件留队直到收到 task_event_ack {task_id, seq}（服务端支持后切换，重连按序补发未确认）。
     */
    val autoAckOnSend: Boolean = true,
) {
    companion object {
        const val DEFAULT_URL = "wss://v5.cococolo.xyz/api/open/v1/seat"
    }
}

/** 指数退避：1s→2s→4s…封顶 60s；连上后 [reset]。 */
class UplinkBackoff(private val initialMs: Long = 1_000L, private val maxMs: Long = 60_000L) {
    private var current = initialMs

    @Synchronized
    fun nextDelayMs(): Long {
        val d = current
        current = minOf(current * 2, maxMs)
        return d
    }

    @Synchronized
    fun reset() {
        current = initialMs
    }
}

interface UplinkConnection {
    /** 发文本帧；失败/已断返回 false。线程安全。 */
    fun sendText(text: String): Boolean

    /** 发 WS ping（心跳）；失败返回 false。 */
    fun sendPing(): Boolean

    /** 阻塞读下一条文本帧；连接结束返回 null。 */
    fun readText(): String?
    fun close()
}

interface UplinkTransport {
    /** 建连+握手，失败抛异常。 */
    fun connect(): UplinkConnection
}

/**
 * 会话逻辑（与传输解耦，便于单测）：连上发 seat_register → 补发缓存 → 处理 task_dispatch
 * （落本地建单后回 task_ack）→ 本地事件入队并按序上发。
 */
class UplinkSession(
    private val config: UplinkConfig,
    val outbox: UplinkOutbox,
    private val channelIds: () -> List<String>,
    /** 落本地建单；返回 true=新建、false=已存在（幂等）；抛异常=落单失败（不回 ack，云端会重投）。 */
    private val ingest: (JSONObject) -> Boolean,
    private val log: (String) -> Unit = {},
) {
    private var conn: UplinkConnection? = null

    @Synchronized
    fun onOpen(connection: UplinkConnection): Boolean {
        val register = UplinkFrames.seatRegister(
            config.appInstanceId, config.agentId, channelIds(), config.platform, config.appVersion,
        )
        if (!connection.sendText(register)) return false
        conn = connection
        flushLocked()
        return true
    }

    @Synchronized
    fun onClosed() {
        conn = null
    }

    @Synchronized
    fun isOnline(): Boolean = conn != null

    /** 本地事件到达：入队（幂等），在线则立刻按序上发。 */
    @Synchronized
    fun publish(event: UplinkEvent) {
        outbox.enqueue(event)
        flushLocked()
    }

    private fun flushLocked() {
        val c = conn ?: return
        for (ev in outbox.pendingInOrder()) {
            if (!c.sendText(UplinkFrames.taskEvent(ev))) {
                conn = null // 写失败 = 断线：留队等重连补发
                return
            }
            if (config.autoAckOnSend) outbox.ack(ev.taskId, ev.seq)
        }
    }

    fun onText(text: String) {
        when (val frame = UplinkFrames.decodeInbound(text)) {
            is UplinkInbound.Dispatch -> {
                val taskId = frame.task.optString("task_id")
                try {
                    ingest(frame.task) // 新建或已存在都 ack（幂等）
                } catch (e: Exception) {
                    log("task_dispatch 落单失败 $taskId：${e.message}")
                    return
                }
                synchronized(this) { conn?.sendText(UplinkFrames.taskAck(taskId)) }
            }
            is UplinkInbound.EventAck -> outbox.ack(frame.taskId, frame.seq)
            is UplinkInbound.Unknown -> log("忽略未知帧 ${frame.type}")
            is UplinkInbound.Invalid -> log("忽略坏帧：${frame.reason}")
        }
    }
}

/** 心跳：每 [intervalMs] 发一次 ping，发失败即停（断线由读循环收尾）。 */
class UplinkHeartbeat(private val intervalMs: Long, private val ping: () -> Boolean) {
    private val lock = Object()

    @Volatile
    private var stopped = false
    private var thread: Thread? = null

    fun start() {
        if (intervalMs <= 0) return
        val t = Thread({
            synchronized(lock) {
                while (!stopped) {
                    try {
                        lock.wait(intervalMs)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    if (stopped) return@Thread
                    if (!ping()) return@Thread
                }
            }
        }, "uplink-heartbeat")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        stopped = true
        synchronized(lock) { lock.notifyAll() }
    }
}

/** 重连循环：断线指数退避 1s→60s，连上（register 已发出）即重置退避。阻塞运行，[stop] 结束。 */
class UplinkClient(
    private val transport: UplinkTransport,
    private val session: UplinkSession,
    private val heartbeatMs: Long = 30_000L,
    private val backoff: UplinkBackoff = UplinkBackoff(),
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val log: (String) -> Unit = {},
) {
    @Volatile
    private var stopped = false

    @Volatile
    private var active: UplinkConnection? = null

    fun stop() {
        stopped = true
        active?.close()
    }

    fun runLoop() {
        while (!stopped) {
            var conn: UplinkConnection? = null
            var heartbeat: UplinkHeartbeat? = null
            try {
                val c = transport.connect()
                conn = c
                active = c
                if (session.onOpen(c)) {
                    backoff.reset()
                    heartbeat = UplinkHeartbeat(heartbeatMs) { c.sendPing() }.also { it.start() }
                    while (!stopped) {
                        val text = c.readText() ?: break
                        session.onText(text)
                    }
                }
            } catch (e: Exception) {
                log("上行连接异常：${e.message}")
            } finally {
                heartbeat?.stop()
                session.onClosed()
                active = null
                runCatching { conn?.close() }
            }
            if (stopped) break
            try {
                sleeper(backoff.nextDelayMs())
            } catch (_: InterruptedException) {
                break
            }
        }
    }
}
