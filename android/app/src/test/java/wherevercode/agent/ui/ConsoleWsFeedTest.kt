package wherevercode.agent.ui

import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.mcp.WsConnection
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * ConsoleWsFeed 测试（JUnit4 纯 JVM 测试）。
 *
 * 事件解析是纯函数单测；Feed 的重连退避与切走停连用假 WS 连接
 * （不触网）：run() 按脚本调 onText，或立即返回模拟断线。
 */
private class FakeWsConnection(
    private val scriptedTexts: List<String> = emptyList(),
    /** true = 发完脚本后一直阻塞到 close()（模拟不断的连接）；false = 立即返回（模拟断线）。 */
    private val blockForever: Boolean = true,
) : WsConnection {
    var closeCalled = false
    private val latch = CountDownLatch(1)

    override fun run(onText: (String) -> Unit) {
        scriptedTexts.forEach { onText(it) }
        if (blockForever) latch.await()
    }

    override fun close() {
        closeCalled = true
        latch.countDown()
    }

    override val isOpen: Boolean get() = !closeCalled
}

class ConsoleWsFeedTest {

    private val peer = DiscoveredPeer(
        deviceId = "remote-device",
        displayName = "remote-device",
        host = "192.0.2.10",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )

    private fun eventJson(
        event: String,
        data: String,
        v: Int = 1,
        type: String = "event",
        ts: Long = 1_790_000_000L,
    ): String =
        """{"v":$v,"type":"$type","event":"$event","ts":$ts,"device_id":"remote-device","data":$data}"""

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    // ── 事件解析 ────────────────────────────────────────────────

    @Test
    fun parse_deviceInfo() {
        val event = parseConsoleWsEvent(
            eventJson(
                "device.info",
                """{"device_id":"remote-device","display_name":"remote-device","agents":[{"agent_id":"example-agent","display_name":"Example Agent"},{"agent_id":"codex","display_name":"Codex"}],"server_time":1790000000}""",
            ),
            "remote-device",
        )
        assertEquals("device.info", event?.eventName)
        assertEquals("remote-device 已连接，Agent：Example Agent、Codex", event?.detail)
        assertEquals(1_790_000_000L, event?.epochSeconds)
    }

    @Test
    fun parse_deviceInfo_noAgents() {
        val event = parseConsoleWsEvent(
            eventJson("device.info", """{"device_id":"remote-device","display_name":"remote-device","agents":[]}"""),
            "remote-device",
        )
        assertEquals("remote-device 已连接（暂无 Agent）", event?.detail)
    }

    @Test
    fun parse_runStarted() {
        val event = parseConsoleWsEvent(
            eventJson("agent.run.started", """{"task_id":"task-7","agent_id":"example-agent","folder":"/proj"}"""),
            "remote-device",
        )
        assertEquals("remote-device · example-agent 开始执行任务 task-7", event?.detail)
        assertEquals("task-7", event?.taskId)
        assertEquals("example-agent", event?.agentId)
    }

    @Test
    fun parse_runProgress_usesMessageVerbatim() {
        val event = parseConsoleWsEvent(
            eventJson("agent.run.progress", """{"task_id":"task-7","agent_id":"codex","message":"正在读 README"}"""),
            "remote-device",
        )
        assertEquals("remote-device · codex：正在读 README", event?.detail)
    }

    @Test
    fun parse_runFinished_statuses() {
        val ok = parseConsoleWsEvent(
            eventJson("agent.run.finished", """{"task_id":"t1","agent_id":"example-agent","status":"ok","detail":""}"""),
            "remote-device",
        )
        assertEquals("remote-device · example-agent 完成（ok）任务 t1", ok?.detail)
        val err = parseConsoleWsEvent(
            eventJson("agent.run.finished", """{"task_id":"t1","agent_id":"codex","status":"error","detail":"超时退出"}"""),
            "remote-device",
        )
        assertEquals("remote-device · codex 失败（error）任务 t1：超时退出", err?.detail)
        val timeout = parseConsoleWsEvent(
            eventJson("agent.run.finished", """{"task_id":"t1","agent_id":"codex","status":"timeout"}"""),
            "remote-device",
        )
        assertEquals("remote-device · codex 超时（timeout）任务 t1", timeout?.detail)
        val stopped = parseConsoleWsEvent(
            eventJson("agent.run.finished", """{"task_id":"t1","agent_id":"codex","status":"stopped"}"""),
            "remote-device",
        )
        assertEquals("remote-device · codex 已停止（stopped）任务 t1", stopped?.detail)
    }

    @Test
    fun parse_dropsBadFrames() {
        // v!=1。
        assertNull(parseConsoleWsEvent(eventJson("agent.run.started", "{}", v = 2), "remote-device"))
        // type!=event。
        assertNull(parseConsoleWsEvent(eventJson("agent.run.started", "{}", type = "ping"), "remote-device"))
        // 未知事件名。
        assertNull(parseConsoleWsEvent(eventJson("agent.run.bogus", "{}"), "remote-device"))
        // 坏 JSON。
        assertNull(parseConsoleWsEvent("{not json", "remote-device"))
        // run 事件缺 task_id。
        assertNull(
            parseConsoleWsEvent(
                eventJson("agent.run.started", """{"agent_id":"example-agent"}"""),
                "remote-device",
            ),
        )
        // progress 消息为空（无信息，不进 eventLog）。
        assertNull(
            parseConsoleWsEvent(
                eventJson("agent.run.progress", """{"task_id":"t1","agent_id":"codex","message":""}"""),
                "remote-device",
            ),
        )
    }

    @Test
    fun parse_finishedUnknownStatus_stillRecordedHonestly() {
        val event = parseConsoleWsEvent(
            eventJson("agent.run.finished", """{"task_id":"t1","agent_id":"codex","status":"weird"}"""),
            "remote-device",
        )
        assertEquals("remote-device · codex 结束（weird）任务 t1", event?.detail)
    }

    // ── 退避 ────────────────────────────────────────────────────

    @Test
    fun backoffMs_sequenceAndCap() {
        assertEquals(2_000L, ConsoleWsFeed.backoffMs(0))
        assertEquals(5_000L, ConsoleWsFeed.backoffMs(1))
        assertEquals(10_000L, ConsoleWsFeed.backoffMs(2))
        assertEquals(30_000L, ConsoleWsFeed.backoffMs(3))
        assertEquals(30_000L, ConsoleWsFeed.backoffMs(99))
    }

    @Test
    fun reconnectBackoff_afterInstantDisconnects() {
        val sleeps = mutableListOf<Long>()
        val feed = ConsoleWsFeed(
            tokenProvider = { "a".repeat(64) },
            connectionFactory = { _, _, _ -> FakeWsConnection(blockForever = false) },
            onEvent = {},
            sleepMillis = { sleeps.add(it) },
        )
        feed.start(listOf(peer))
        try {
            assertTrue(waitUntil(5_000) { sleeps.size >= 4 })
        } finally {
            feed.stop()
        }
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L), sleeps.take(4))
    }

    @Test
    fun tokenFailure_backsOffToo() {
        val sleeps = mutableListOf<Long>()
        val feed = ConsoleWsFeed(
            tokenProvider = { null },
            connectionFactory = { _, _, _ -> FakeWsConnection(blockForever = false) },
            onEvent = {},
            sleepMillis = { sleeps.add(it) },
        )
        feed.start(listOf(peer))
        try {
            assertTrue(waitUntil(5_000) { sleeps.size >= 3 })
        } finally {
            feed.stop()
        }
        assertEquals(listOf(2_000L, 5_000L, 10_000L), sleeps.take(3))
    }

    // ── 事件投递与停连 ──────────────────────────────────────────

    @Test
    fun eventsDelivered_badFrameDropped_stateConnected() {
        val received = mutableListOf<ConsoleWsEvent>()
        val fake = FakeWsConnection(
            scriptedTexts = listOf(
                """{"v":9,"type":"event","event":"agent.run.started","ts":1,"device_id":"remote-device","data":{}}""",
                eventJson("agent.run.started", """{"task_id":"task-7","agent_id":"example-agent"}"""),
                eventJson("agent.run.progress", """{"task_id":"task-7","agent_id":"example-agent","message":"ok"}"""),
            ),
            blockForever = true,
        )
        val feed = ConsoleWsFeed(
            tokenProvider = { "a".repeat(64) },
            connectionFactory = { _, _, _ -> fake },
            onEvent = { received.add(it) },
        )
        feed.start(listOf(peer))
        try {
            assertTrue(waitUntil(5_000) { received.size >= 2 })
            assertTrue(waitUntil(5_000) { feed.states()[peer.deviceId] == ConsoleWsFeed.WsFeedState.CONNECTED })
        } finally {
            feed.stop()
        }
        // 坏帧（v=9）被丢弃，只收到两条真事件。
        assertEquals(2, received.size)
        assertEquals("remote-device · example-agent 开始执行任务 task-7", received[0].detail)
        assertEquals("remote-device · example-agent：ok", received[1].detail)
    }

    @Test
    fun stop_closesConnectionAndEndsWorkers() {
        val fake = FakeWsConnection(blockForever = true)
        val feed = ConsoleWsFeed(
            tokenProvider = { "a".repeat(64) },
            connectionFactory = { _, _, _ -> fake },
            onEvent = {},
        )
        feed.start(listOf(peer))
        assertTrue(waitUntil(5_000) { feed.states()[peer.deviceId] == ConsoleWsFeed.WsFeedState.CONNECTED })
        feed.stop()
        assertTrue(fake.closeCalled)
        assertTrue(waitUntil(5_000) { feed.states().isEmpty() })
        // 再停一次也安全（幂等）。
        feed.stop()
    }

    @Test
    fun startTwice_restartsCleanly() {
        val fake1 = FakeWsConnection(blockForever = true)
        val fake2 = FakeWsConnection(blockForever = true)
        val created = mutableListOf<FakeWsConnection>()
        val feed = ConsoleWsFeed(
            tokenProvider = { "a".repeat(64) },
            connectionFactory = { _, _, _ ->
                val fake = if (created.isEmpty()) fake1 else fake2
                created.add(fake)
                fake
            },
            onEvent = {},
        )
        feed.start(listOf(peer))
        assertTrue(waitUntil(5_000) { feed.states()[peer.deviceId] == ConsoleWsFeed.WsFeedState.CONNECTED })
        feed.start(listOf(peer))
        assertTrue(waitUntil(5_000) { created.size >= 2 })
        assertTrue("旧连接应被关掉", fake1.closeCalled)
        assertTrue(waitUntil(5_000) { feed.states()[peer.deviceId] == ConsoleWsFeed.WsFeedState.CONNECTED })
        feed.stop()
        assertTrue(fake2.closeCalled)
    }
}
