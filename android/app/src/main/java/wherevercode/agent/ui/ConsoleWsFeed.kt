package wherevercode.agent.ui

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import wherevercode.agent.core.mcp.ConsoleWsClient
import wherevercode.agent.core.mcp.WsConnection
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * 控制台 WS 事件（解析后的一条真实远端事件；只含渲染需要的信息）。
 * [detail] 是一句中文，供 MainActivity 原样写进 eventLog
 *（kind 固定 "remote_agent_event"，conversationId 留空字符串，
 * 走全局事件流——控制台读 entries(null).takeLast(40) 直接看到，
 * 渲染零改动）。
 */
data class ConsoleWsEvent(
    /** 事件名：device.info / agent.run.started / progress / finished。 */
    val eventName: String,
    val deviceId: String,
    val deviceDisplayName: String,
    val taskId: String,
    val agentId: String,
    /** 中文一行，如“remote-device · example-agent 开始执行任务 xxx”。 */
    val detail: String,
    val epochSeconds: Long,
)

/**
 * 解析一帧 WS 事件 JSON。
 * `{"v":1,"type":"event","event":"<名>","ts":<unix 秒>,"device_id":"<Hand>","data":{...}}`。
 * 只收真实事件：v!=1、type!=event、未知事件名、JSON 坏了、run 事件缺
 * task_id/agent_id、progress 消息为空，一律返回 null（丢弃并记日志，
 * 不崩溃、不进 eventLog——绝不造假事件）。
 */
fun parseConsoleWsEvent(json: String, deviceDisplayName: String): ConsoleWsEvent? {
    return try {
        val obj = JSONObject(json)
        if (obj.optInt("v", -1) != 1) return null
        if (obj.optString("type", "") != "event") return null
        val eventName = obj.optString("event", "")
        if (eventName !in setOf(
                "device.info", "agent.run.started",
                "agent.run.progress", "agent.run.finished",
            )
        ) {
            return null
        }
        val ts = obj.optLong("ts", 0L).takeIf { it > 0 }
            ?: (System.currentTimeMillis() / 1000)
        val deviceId = obj.optString("device_id", "")
        val data = obj.optJSONObject("data")
        val display = data?.optString("display_name", "")?.takeIf { it.isNotBlank() }
            ?: deviceDisplayName.ifBlank { deviceId }
        val taskId = data?.optString("task_id", "") ?: ""
        val agentId = data?.optString("agent_id", "") ?: ""
        val detail = when (eventName) {
            "device.info" -> {
                val agents = data?.optJSONArray("agents")
                val names = mutableListOf<String>()
                if (agents != null) {
                    for (i in 0 until agents.length()) {
                        val entry = agents.optJSONObject(i) ?: continue
                        val name = entry.optString("display_name", "")
                            .takeIf { it.isNotBlank() }
                            ?: entry.optString("agent_id", "")
                        if (name.isNotBlank()) names += name
                    }
                }
                if (names.isEmpty()) "$display 已连接（暂无 Agent）"
                else "$display 已连接，Agent：${names.joinToString("、")}"
            }
            "agent.run.started" -> {
                if (taskId.isBlank() || agentId.isBlank()) return null
                "$display · $agentId 开始执行任务 $taskId"
            }
            "agent.run.progress" -> {
                if (taskId.isBlank() || agentId.isBlank()) return null
                val message = data?.optString("message", "") ?: ""
                if (message.isBlank()) return null
                "$display · $agentId：$message"
            }
            "agent.run.finished" -> {
                if (taskId.isBlank() || agentId.isBlank()) return null
                val status = data?.optString("status", "") ?: ""
                val outcome = when (status) {
                    "ok" -> "完成（ok）"
                    "error" -> "失败（error）"
                    "timeout" -> "超时（timeout）"
                    "stopped" -> "已停止（stopped）"
                    else -> "结束（$status）"
                }
                val extra = data?.optString("detail", "")?.takeIf { it.isNotBlank() }
                "$display · $agentId ${outcome}任务 $taskId" +
                    (extra?.let { "：$it" } ?: "")
            }
            else -> return null
        }
        ConsoleWsEvent(
            eventName = eventName,
            deviceId = deviceId,
            deviceDisplayName = display,
            taskId = taskId,
            agentId = agentId,
            detail = detail,
            epochSeconds = ts,
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * 控制台 WS 订阅（V5 控制台实时推送的 App 一半，2026-11-11）。
 *
 * 每台已配对 Hand 设备各维护一条 WS：先经密封通道领 token
 *（[tokenProvider]，单次使用、120 秒过期，每次（重）连都重新领），
 * 再连 `/events`；收到事件 → [onEvent]（MainActivity 写 eventLog 并
 * 立即调 refreshConsole，不等 5 秒轮询）；断线自动重连，退避
 * 2s / 5s / 10s / 30s 封顶；只在控制台打开时重连，切走即停（[stop]）。
 *
 * 兜底（必须，写死在这里）：WS 只是加速器，不是唯一数据源。
 * 5 秒轮询 ticker 由 MainActivity 永远保持运行（安全网，不关）；
 * WS 断了轮询照样每 5 秒更新，控制台绝不因此变空白。
 * 本类只负责“事件到了立刻推一次刷新”，从不关轮询、从不停轮询。
 *
 * 诚实边界：WS 状态只做内部状态（[WsFeedState]，供日志/诊断），
 * 绝不据此在界面上写设备“在线/离线”——WS 通只说明事件流通，
 * 不等于设备在线；设备“在线/离线”没有心跳数据就不写（控制台
 * DELIVERY 定死）。本类不碰任何 UI，只调 [onEvent] 与 [onLog]。
 *
 * 线程：每台设备一个守护线程跑阻塞的 [WsConnection.run]；
 * [tokenProvider] 是 suspend 函数，在工作线程里用 runBlocking 调。
 */
class ConsoleWsFeed(
    private val tokenProvider: suspend (DiscoveredPeer) -> String?,
    private val connectionFactory: (host: String, port: Int, token: String) -> WsConnection =
        { host, port, token -> ConsoleWsClient(host, port, token) },
    private val onEvent: (ConsoleWsEvent) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** 内部状态（只供日志/诊断，绝不渲染成“在线/离线”）。 */
    enum class WsFeedState {
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        STOPPED,
    }

    companion object {
        /** 重连退避：2s / 5s / 10s / 30s 封顶（下标越界后一直取末项）。 */
        val BACKOFF_MS: List<Long> = listOf(2_000L, 5_000L, 10_000L, 30_000L)

        fun backoffMs(failureIndex: Int): Long =
            BACKOFF_MS[failureIndex.coerceIn(0, BACKOFF_MS.size - 1)]

        /** 一条连接活过这么久才断，算稳定连接，下次失败从 2s 重新退避。 */
        const val STABLE_CONNECTION_MS: Long = 30_000L
    }

    private val lock = Any()

    @Volatile
    private var running = false

    private val workers = mutableMapOf<String, DeviceWorker>()

    /** 启动（会先停掉旧的再按新设备列表起；控制台打开时调）。 */
    fun start(devices: List<DiscoveredPeer>) {
        synchronized(lock) {
            stopLocked()
            if (devices.isEmpty()) {
                onLog("ConsoleWsFeed: 无已配对设备，不启动 WS")
                return
            }
            running = true
            for (peer in devices) {
                val worker = DeviceWorker(peer)
                workers[peer.deviceId] = worker
                worker.thread.start()
            }
            onLog("ConsoleWsFeed: 已启动，设备 ${devices.size} 台")
        }
    }

    /** 停止全部设备连接（控制台切走 / Activity 销毁时调）；幂等。 */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    /** 各设备当前内部状态（只供日志/诊断）。 */
    fun states(): Map<String, WsFeedState> =
        synchronized(lock) { workers.mapValues { it.value.state } }

    private fun stopLocked() {
        if (!running && workers.isEmpty()) return
        running = false
        val snapshot = workers.values.toList()
        workers.clear()
        for (worker in snapshot) {
            try {
                worker.connection?.close()
            } catch (_: Exception) {
            }
            worker.thread.interrupt()
        }
        // join 带上限：不在调用线程（可能是 UI 线程）久等；
        // 没等到的线程是守护线程，看到 running=false 后自己退出。
        for (worker in snapshot) {
            try {
                worker.thread.join(500)
            } catch (_: Exception) {
            }
        }
    }

    private inner class DeviceWorker(val peer: DiscoveredPeer) {
        @Volatile
        var state: WsFeedState = WsFeedState.CONNECTING

        @Volatile
        var connection: WsConnection? = null

        val thread: Thread = Thread({ runLoop() }, "console-ws-${peer.deviceId}").apply {
            isDaemon = true
        }

        private fun runLoop() {
            var failureIndex = 0
            while (running) {
                state = if (failureIndex == 0) WsFeedState.CONNECTING else WsFeedState.RECONNECTING
                val token = try {
                    runBlocking { tokenProvider(peer) }
                } catch (e: Exception) {
                    onLog("ConsoleWsFeed: ${peer.displayName} 领 token 失败：${e.message}")
                    null
                }
                if (!running) break
                if (token == null) {
                    onLog("ConsoleWsFeed: ${peer.displayName} 未领到 token，退避重连")
                    if (!sleepBackoff(failureIndex)) break
                    failureIndex++
                    continue
                }
                val conn: WsConnection? = try {
                    connectionFactory(peer.host, peer.port, token)
                } catch (e: Exception) {
                    onLog("ConsoleWsFeed: ${peer.displayName} 建 WS 客户端失败：${e.message}")
                    null
                }
                if (conn == null) {
                    if (!sleepBackoff(failureIndex)) break
                    failureIndex++
                    continue
                }
                connection = conn
                state = WsFeedState.CONNECTED
                val connectedAt = System.currentTimeMillis()
                try {
                    conn.run { text -> handleText(text) }
                } catch (e: Exception) {
                    onLog("ConsoleWsFeed: ${peer.displayName} WS 异常结束：${e.message}")
                } finally {
                    connection = null
                }
                if (!running) break
                // 活过 30 秒才断算稳定连接，下次失败从 2s 重新退避；
                // 否则退避逐级加大（2s/5s/10s/30s 封顶）。
                val livedMs = System.currentTimeMillis() - connectedAt
                val delayIndex = failureIndex
                if (livedMs >= STABLE_CONNECTION_MS) failureIndex = 0 else failureIndex++
                onLog("ConsoleWsFeed: ${peer.displayName} WS 断开（存活 ${livedMs}ms），退避重连")
                if (!sleepBackoff(delayIndex)) break
            }
            state = WsFeedState.STOPPED
        }

        /**
         * 按 [failureIndex] 睡一轮退避；返回 false 表示中途已停
         * （running=false 或线程被中断），调用方直接退出循环。
         */
        private fun sleepBackoff(failureIndex: Int): Boolean {
            if (!running) return false
            return try {
                sleepMillis(backoffMs(failureIndex))
                running
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        private fun handleText(text: String) {
            val event = parseConsoleWsEvent(text, peer.displayName)
            if (event == null) {
                onLog("ConsoleWsFeed: ${peer.displayName} 丢弃一帧非事件/坏事件（不进 eventLog）")
                return
            }
            try {
                onEvent(event)
            } catch (e: Exception) {
                onLog("ConsoleWsFeed: 事件回调失败：${e.message}")
            }
        }
    }
}
