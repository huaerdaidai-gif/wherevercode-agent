package wherevercode.agent.core.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.core.mcp.WsConnection

/**
 * P3 频道消息流测试（2026-11-11）：首轮轮询 + WS 推送 + 去重 +
 * 断线轮询兜底（after_id 游标捡回断档消息）。
 */
@RunWith(RobolectricTestRunner::class)
class ChannelFeedTest {

    /** 假 transport：按 after_id 切片返回，像真服务端一样按时间升序。 */
    private class FakeTransport(
        val messages: MutableList<PlatformChannelMessage> = mutableListOf(),
    ) : PlatformChannelTransport {
        data class PollCall(val afterId: String?, val limit: Int)

        val pollCalls = mutableListOf<PollCall>()
        var posted = mutableListOf<String>()

        override suspend fun postMessage(channelId: String, text: String): String {
            posted += text
            val id = "m-posted-${posted.size}"
            messages += message(id, text)
            return id
        }

        override suspend fun pollMessages(
            channelId: String,
            afterId: String?,
            limit: Int,
            onlyUnread: Boolean,
            markRead: Boolean,
        ): List<PlatformChannelMessage> {
            pollCalls += PollCall(afterId, limit)
            val start = if (afterId == null) 0 else {
                val index = messages.indexOfFirst { it.id == afterId }
                if (index < 0) messages.size else index + 1
            }
            return messages.drop(start).take(limit)
        }
    }

    /** 假 WS：run() 时把脚本发完就返回（= 连接掉了）。 */
    private class ScriptedWs(
        private val texts: List<String>,
    ) : WsConnection {
        override fun run(onText: (String) -> Unit) {
            texts.forEach(onText)
        }

        override fun close() {}
        override val isOpen: Boolean get() = false
    }

    /** 挂起的 WS：run() 阻塞到 close()（= 一直活着）。 */
    private class HangingWs : WsConnection {
        private val latch = CountDownLatch(1)

        override fun run(onText: (String) -> Unit) {
            latch.await(30, TimeUnit.SECONDS)
        }

        override fun close() {
            latch.countDown()
        }

        override val isOpen: Boolean get() = true
    }

    private fun eventJson(id: String, text: String): String =
        """{"type":"message","channel_id":"ch-1","message":{"id":"$id","agent_id":"example-agent","agent_name":"Example Agent","text":"$text","created_at":1794390000.0}}"""

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test
    fun `initial poll then ws push delivers each message once`() {
        val transport = FakeTransport(
            mutableListOf(message("m1", "在吗"), message("m2", "在的")),
        )
        val delivered = mutableListOf<List<PlatformChannelMessage>>()
        val latch = CountDownLatch(1)
        val wsUses = AtomicInteger(0)
        val feed = ChannelFeed(
            channelId = "ch-1",
            transport = transport,
            wsFactory = { _, _ ->
                if (wsUses.getAndIncrement() == 0) {
                    // 第一轮 WS：推一条新的 + 一条重复的（重复的不能二次交付）。
                    ScriptedWs(
                        listOf(eventJson("m3", "新来的"), eventJson("m2", "在的")),
                    )
                } else {
                    HangingWs()
                }
            },
            onMessages = { batch ->
                synchronized(delivered) { delivered += batch }
                if (delivered.flatten().map { it.id }.toSet() == setOf("m1", "m2", "m3")) {
                    latch.countDown()
                }
            },
            pollIntervalMs = 100,
            reconnectAfterMs = 300,
        )
        feed.start()
        try {
            assertTrue(latch.await(30, TimeUnit.SECONDS))
            val ids = delivered.flatten().map { it.id }
            assertEquals(listOf("m1", "m2", "m3"), ids)
        } finally {
            feed.stop()
        }
    }

    @Test
    fun `ws drop falls back to polling with after_id cursor`() {
        val transport = FakeTransport(mutableListOf(message("m1", "在吗")))
        val delivered = mutableListOf<PlatformChannelMessage>()
        val latch = CountDownLatch(1)
        val feed = ChannelFeed(
            channelId = "ch-1",
            transport = transport,
            wsFactory = { _, _ -> ScriptedWs(emptyList()) }, // 一连就掉
            onMessages = { batch ->
                synchronized(delivered) { delivered += batch }
                if (delivered.any { it.id == "m2" }) latch.countDown()
            },
            pollIntervalMs = 100,
            reconnectAfterMs = 300,
        )
        feed.start()
        try {
            // 首轮已拉到 m1；随后另一条消息到了（模拟别的 Agent 发帖）。
            assertTrue(waitUntil(30_000) { delivered.any { it.id == "m1" } })
            transport.messages += message("m2", "刚到的")
            assertTrue("断档消息没被轮询捡回来", latch.await(30, TimeUnit.SECONDS))
            assertEquals(listOf("m1", "m2"), delivered.map { it.id })
            // 兜底轮询确实用了游标（不是每次都从头拉）。
            assertTrue(transport.pollCalls.any { it.afterId == "m1" })
        } finally {
            feed.stop()
        }
    }

    @Test
    fun `stop ends the feed without delivering more`() {
        val transport = FakeTransport(mutableListOf(message("m1", "在吗")))
        val delivered = mutableListOf<PlatformChannelMessage>()
        val feed = ChannelFeed(
            channelId = "ch-1",
            transport = transport,
            wsFactory = { _, _ -> HangingWs() },
            onMessages = { batch -> synchronized(delivered) { delivered += batch } },
            pollIntervalMs = 100,
            reconnectAfterMs = 300,
        )
        feed.start()
        assertTrue(waitUntil(30_000) { delivered.any { it.id == "m1" } })
        feed.stop()
        assertEquals(ChannelConnectionState.STOPPED, feed.connectionState)
        transport.messages += message("m2", "后来的")
        Thread.sleep(500)
        assertTrue(delivered.none { it.id == "m2" })
    }

    @Test
    fun `postMessage goes through the transport`() {
        val transport = FakeTransport(mutableListOf())
        runBlocking { transport.postMessage("ch-1", "hello") }
        assertEquals(listOf("hello"), transport.posted)
        assertEquals("m-posted-1", transport.messages.single().id)
    }
}

/** 测试里用的频道消息构造器（FakeTransport 与测试方法共用，顶层函数）。 */
private fun message(id: String, text: String) = PlatformChannelMessage(
    id = id,
    agentId = "example-agent",
    agentName = "Example Agent",
    text = text,
    createdAtSeconds = 1_794_390_000.0,
)
