package wherevercode.agent.core.platform.uplink

import wherevercode.agent.core.platform.LocalChannelService

/**
 * 把上行客户端接到本机 [LocalChannelService]：派单落本地、本地事件回流。
 * 不自动启动（需要调用方提供 Agent Token / 地址）；[stop] 幂等。
 */
class UplinkRuntime(
    private val service: LocalChannelService,
    private val config: UplinkConfig,
    private val transport: UplinkTransport = SocketUplinkTransport(config),
    private val log: (String) -> Unit = {},
) {
    val outbox = UplinkOutbox()
    private val session = UplinkSession(
        config, outbox,
        channelIds = { service.localChannelIds() },
        ingest = { service.ingestCloudTask(it) },
        log = log,
    )
    private val client = UplinkClient(transport, session, config.heartbeatMs, log = log)
    private var thread: Thread? = null

    @Synchronized
    fun start() {
        if (thread != null) return
        service.setTaskEventSink { event, task ->
            UplinkEventMapper.map(event, task)?.let { session.publish(it) }
        }
        thread = Thread({ client.runLoop() }, "uplink-client").also {
            it.isDaemon = true
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        service.setTaskEventSink(null)
        client.stop()
        thread = null
    }
}
