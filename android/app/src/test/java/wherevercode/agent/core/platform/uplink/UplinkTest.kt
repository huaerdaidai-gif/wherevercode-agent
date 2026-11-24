package wherevercode.agent.core.platform.uplink

import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.platform.InMemoryTaskDispatchPersistence
import wherevercode.agent.core.platform.TaskAgentDirectory
import wherevercode.agent.core.platform.TaskDispatchService
import wherevercode.agent.core.platform.TaskDispatchStore
import wherevercode.agent.core.platform.TaskEventRecord

class UplinkTest {
    private val config = UplinkConfig(
        url = "ws://127.0.0.1:1/seat", token = "t", appInstanceId = "inst-1",
        agentId = "agent-1", appVersion = "0.5.1",
    )

    private fun ev(seq: Long, task: String = "t1", kind: String = "claimed") =
        UplinkEvent(task, seq, kind, "local-app", 1.0, "{}")

    /** 假连接：记录发出的帧，可预置“第 n 次发送起失败”。 */
    private class FakeConn(private val failAfter: Int = Int.MAX_VALUE, private val inbound: List<String> = emptyList()) :
        UplinkConnection {
        val sent = CopyOnWriteArrayList<String>()
        private val queue = ArrayDeque(inbound)
        var closed = false
        var pings = 0
        override fun sendText(text: String): Boolean {
            if (sent.size >= failAfter) return false
            sent += text
            return true
        }
        override fun sendPing(): Boolean { pings++; return true }
        override fun readText(): String? = queue.removeFirstOrNull()
        override fun close() { closed = true }
    }

    private fun types(c: FakeConn) = c.sent.map { JSONObject(it).getString("type") }
    private fun seqs(c: FakeConn) =
        c.sent.map { JSONObject(it) }.filter { it.getString("type") == "task_event" }.map { it.getLong("seq") }

    private fun newSession(ingest: (JSONObject) -> Boolean = { true }, cfg: UplinkConfig = config) =
        UplinkSession(cfg, UplinkOutbox(), { listOf("ch-a", "ch-b") }, ingest)

    // ---- 帧编解码 --------------------------------------------------------

    @Test fun seatRegisterFrameHasContractFields() {
        val o = JSONObject(UplinkFrames.seatRegister("inst-1", "agent-1", listOf("ch-a"), "android", "0.5.1"))
        assertEquals("seat_register", o.getString("type"))
        assertEquals("inst-1", o.getString("app_instance_id"))
        assertEquals("agent-1", o.getString("agent_id"))
        assertEquals("ch-a", o.getJSONArray("channels").getString(0))
        assertEquals("android", o.getString("platform"))
        assertEquals("0.5.1", o.getString("app_version"))
    }

    @Test fun taskAckAndEventFrames() {
        assertEquals("task_ack", JSONObject(UplinkFrames.taskAck("abc")).getString("type"))
        assertEquals("abc", JSONObject(UplinkFrames.taskAck("abc")).getString("task_id"))
        val e = JSONObject(UplinkFrames.taskEvent(UplinkEvent("abc", 7, "submitted", "me", 12.5, """{"text":"x"}""")))
        assertEquals("task_event", e.getString("type"))
        assertEquals("abc", e.getString("task_id"))
        assertEquals(7L, e.getLong("seq"))
        assertEquals("submitted", e.getString("kind"))
        assertEquals("me", e.getString("actor"))
        assertEquals(12.5, e.getDouble("at"), 0.0)
        assertEquals("x", e.getJSONObject("payload").getString("text"))
    }

    @Test fun decodeInboundVariants() {
        val d = UplinkFrames.decodeInbound("""{"type":"task_dispatch","task":{"task_id":"t9","goal":"g"}}""")
        assertTrue(d is UplinkInbound.Dispatch)
        assertEquals("t9", (d as UplinkInbound.Dispatch).task.getString("task_id"))
        assertEquals(UplinkInbound.EventAck("t9", 3), UplinkFrames.decodeInbound("""{"type":"task_event_ack","task_id":"t9","seq":3}"""))
        assertEquals(UplinkInbound.Unknown("zzz"), UplinkFrames.decodeInbound("""{"type":"zzz"}"""))
        assertTrue(UplinkFrames.decodeInbound("not json") is UplinkInbound.Invalid)
        assertTrue(UplinkFrames.decodeInbound("""{"x":1}""") is UplinkInbound.Invalid)
        assertTrue(UplinkFrames.decodeInbound("""{"type":"task_dispatch"}""") is UplinkInbound.Invalid)
        assertTrue(UplinkFrames.decodeInbound("""{"type":"task_dispatch","task":{}}""") is UplinkInbound.Invalid)
    }

    // ---- seq 与幂等 ------------------------------------------------------

    @Test fun outboxIsOrderedAndIdempotent() {
        val box = UplinkOutbox()
        assertTrue(box.enqueue(ev(3)))
        assertTrue(box.enqueue(ev(5)))
        assertFalse(box.enqueue(ev(5))) // 重复
        assertFalse(box.enqueue(ev(4))) // 低于水位：不乱序插入
        assertEquals(listOf(3L, 5L), box.pendingInOrder().map { it.seq })
        assertTrue(box.ack("t1", 3))
        assertFalse(box.ack("t1", 3))
        assertFalse(box.enqueue(ev(3))) // 已确认的不复活
        assertEquals(listOf(5L), box.pendingInOrder().map { it.seq })
    }

    @Test fun outboxDropsOldestAndCounts() {
        val box = UplinkOutbox(maxPending = 2)
        for (i in 1L..4L) box.enqueue(ev(i))
        assertEquals(listOf(3L, 4L), box.pendingInOrder().map { it.seq })
        assertEquals(2L, box.dropped)
    }

    @Test fun ledgerSeqIsMonotonicAndCloudTaskKeepsId() {
        var now = 100.0
        val store = TaskDispatchStore(InMemoryTaskDispatchPersistence()) { now }
        val svc = TaskDispatchService(store, TaskAgentDirectory { it }, null, { now })
        val got = mutableListOf<UplinkEvent>()
        svc.eventSink = { e: TaskEventRecord, t -> UplinkEventMapper.map(e, t)?.let { got += it } }

        val cloud = JSONObject().put("task_id", "cloud-task-001").put("goal", "做点事").put("title", "T")
        assertTrue(svc.ingestCloudTask(cloud, "ch-a", "local-app", "本机 App"))
        assertFalse(svc.ingestCloudTask(cloud, "ch-a", "local-app", "本机 App")) // 幂等
        assertEquals(1, store.tasks.size)
        val task = store.tasks["cloud-task-001"]!!
        assertEquals("queued", task.state)
        assertEquals("local-app", task.assigneeId)
        assertEquals("ch-a", task.channelId)

        now += 1
        svc.dispatch("claim_task", JSONObject().put("task_id", "cloud-task-001"), "local-app", "本机 App", "owner")
        now += 1
        svc.dispatch("report_progress", JSONObject().put("task_id", "cloud-task-001").put("text", "半程"), "local-app", "本机 App", "owner")
        assertEquals(listOf("claimed", "progress"), got.map { it.kind })
        assertTrue(got[0].seq < got[1].seq)
        assertEquals("cloud-task-001", got[0].taskId)
    }

    @Test fun rejectsBadCloudTaskIdAndTruncatesGoal() {
        val store = TaskDispatchStore(InMemoryTaskDispatchPersistence()) { 1.0 }
        val svc = TaskDispatchService(store, TaskAgentDirectory { it }, null, { 1.0 })
        try {
            svc.ingestCloudTask(JSONObject().put("task_id", "../x"), null, "local-app", "A")
            throw AssertionError("应拒绝非法 task_id")
        } catch (_: wherevercode.agent.core.platform.TaskDispatchException) {
        }
        val long = "a".repeat(20_500)
        svc.ingestCloudTask(JSONObject().put("task_id", "big1").put("goal", long), null, "local-app", "A")
        assertEquals(20_000, store.tasks["big1"]!!.goal.length)
    }

    @Test fun mapperOnlyForwardsContractKinds() {
        val store = TaskDispatchStore(InMemoryTaskDispatchPersistence()) { 1.0 }
        val svc = TaskDispatchService(store, TaskAgentDirectory { it }, null, { 1.0 })
        svc.ingestCloudTask(JSONObject().put("task_id", "m1").put("goal", "g"), null, "local-app", "A")
        val created = store.events.first { it.kind == "created" }
        assertNull(UplinkEventMapper.map(created, store.tasks["m1"]!!))
    }

    // ---- 会话 / 重连补发 -------------------------------------------------

    @Test fun registerIsFirstFrameThenBufferedEventsReplayInOrder() {
        val s = newSession()
        s.publish(ev(2)); s.publish(ev(1).copy(seq = 3)); s.publish(ev(2).copy(seq = 4)) // 离线期间攒 3 条
        assertEquals(3, s.outbox.size())
        val c = FakeConn()
        assertTrue(s.onOpen(c))
        assertEquals(listOf("seat_register", "task_event", "task_event", "task_event"), types(c))
        assertEquals(listOf(2L, 3L, 4L), seqs(c))
        assertEquals(0, s.outbox.size())
    }

    @Test fun reconnectResendsOnlyUnsentAfterMidFlushDrop() {
        val s = newSession()
        for (i in 1L..4L) s.publish(ev(i))
        val c1 = FakeConn(failAfter = 3) // register + 2 个事件后写失败
        s.onOpen(c1)
        assertEquals(listOf(1L, 2L), seqs(c1))
        assertFalse(s.isOnline())
        assertEquals(listOf(3L, 4L), s.outbox.pendingInOrder().map { it.seq })
        val c2 = FakeConn()
        s.onOpen(c2)
        assertEquals(listOf(3L, 4L), seqs(c2)) // 不重不漏
    }

    @Test fun explicitAckModeKeepsUntilAckedAndReplaysUnacked() {
        val s = newSession(cfg = config.copy(autoAckOnSend = false))
        s.onOpen(FakeConn())
        s.publish(ev(1)); s.publish(ev(2))
        s.onText("""{"type":"task_event_ack","task_id":"t1","seq":1}""")
        assertEquals(listOf(2L), s.outbox.pendingInOrder().map { it.seq })
        s.onClosed()
        val c2 = FakeConn()
        s.onOpen(c2)
        assertEquals(listOf(2L), seqs(c2)) // 只补未确认的
    }

    @Test fun publishWhileOnlineSendsImmediatelyAndDuplicateSeqIsIgnored() {
        val s = newSession()
        val c = FakeConn()
        s.onOpen(c)
        s.publish(ev(1)); s.publish(ev(1))
        assertEquals(listOf(1L), seqs(c))
    }

    @Test fun dispatchIngestsThenAcksSameTaskId() {
        val ingested = mutableListOf<String>()
        val s = newSession(ingest = { ingested += it.getString("task_id"); true })
        val c = FakeConn()
        s.onOpen(c)
        s.onText("""{"type":"task_dispatch","task":{"task_id":"cloud-1","goal":"g"}}""")
        assertEquals(listOf("cloud-1"), ingested)
        val ack = JSONObject(c.sent.last())
        assertEquals("task_ack", ack.getString("type"))
        assertEquals("cloud-1", ack.getString("task_id"))
    }

    @Test fun duplicateDispatchStillAcksAndFailedIngestDoesNot() {
        val s1 = newSession(ingest = { false }) // 已存在：幂等 → 照常 ack
        val c1 = FakeConn(); s1.onOpen(c1)
        s1.onText("""{"type":"task_dispatch","task":{"task_id":"dup"}}""")
        assertEquals("task_ack", JSONObject(c1.sent.last()).getString("type"))

        val s2 = newSession(ingest = { throw IllegalStateException("boom") })
        val c2 = FakeConn(); s2.onOpen(c2)
        s2.onText("""{"type":"task_dispatch","task":{"task_id":"bad"}}""")
        assertEquals(listOf("seat_register"), types(c2)) // 没 ack，云端会重投
        s2.onText("garbage") // 坏帧不崩
    }

    // ---- 退避 / 重连循环 / 心跳 -------------------------------------------

    @Test fun backoffDoublesToCapAndResets() {
        val b = UplinkBackoff()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L), List(8) { b.nextDelayMs() })
        b.reset()
        assertEquals(1000L, b.nextDelayMs())
    }

    @Test fun clientRetriesWithBackoffAndResetsAfterConnect() {
        val delays = mutableListOf<Long>()
        var attempts = 0
        lateinit var client: UplinkClient
        val conns = mutableListOf<FakeConn>()
        val transport = object : UplinkTransport {
            override fun connect(): UplinkConnection {
                attempts++
                if (attempts <= 3) throw java.io.IOException("down")
                return FakeConn().also { conns += it } // 第 4 次连上，读到 null 即断开
            }
        }
        val session = newSession()
        client = UplinkClient(transport, session, heartbeatMs = 0, sleeper = { d ->
            delays += d
            if (delays.size >= 5) client.stop()
        })
        client.runLoop()
        // 失败 3 次：1s、2s、4s；第 4 次连上并 register 成功 → 退避重置，断开后再睡 1s；第 5 次又失败…
        assertEquals(listOf(1000L, 2000L, 4000L, 1000L, 1000L), delays.take(5).let { it })
        assertTrue(types(conns[0]).first() == "seat_register")
    }

    @Test fun heartbeatPingsPeriodicallyAndStops() {
        val c = FakeConn()
        val hb = UplinkHeartbeat(20) { c.sendPing() }
        hb.start()
        Thread.sleep(150)
        hb.stop()
        val n = c.pings
        assertTrue("应至少 2 次 ping，实际 $n", n >= 2)
        Thread.sleep(60)
        assertEquals(n, c.pings)
        assertEquals(30_000L, UplinkConfig(token = "t", appInstanceId = "i", agentId = "a", appVersion = "v").heartbeatMs)
        assertEquals("wss://v5.cococolo.xyz/api/open/v1/seat", UplinkConfig.DEFAULT_URL)
    }
}
