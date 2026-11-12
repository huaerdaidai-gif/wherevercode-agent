package wherevercode.agent.core.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.core.extension.mcp.McpClient
import wherevercode.agent.core.extension.mcp.McpException
import wherevercode.agent.core.extension.mcp.McpJsonRpc
import wherevercode.agent.core.extension.mcp.McpJsonRpc.McpNotification
import wherevercode.agent.core.extension.mcp.McpTransport
import wherevercode.agent.core.mcp.WsConnection

/**
 * MCP 工具箱的事件订阅（2026-11-13，MCP 能力补齐）。
 *
 * 复用 ChannelFeed 的 WS+轮询兜底：WS 推来的消息既到本地监听，
 * 也以 `notifications/channel/message` 通知形式转交给已连的 MCP
 * 客户端（方法名带 channel，与服务端 MCP 通知区分，不冒充）。
 */
@RunWith(RobolectricTestRunner::class)
class McpChannelEventFeedTest {

    private class FakeTransport(
        val messages: MutableList<PlatformChannelMessage> = mutableListOf(),
    ) : PlatformChannelTransport {
        override suspend fun postMessage(channelId: String, text: String): String = ""
        override suspend fun pollMessages(
            channelId: String,
            afterId: String?,
            limit: Int,
            onlyUnread: Boolean,
            markRead: Boolean,
        ): List<PlatformChannelMessage> {
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

    /** 只会 initialize 的假 MCP 传输（事件订阅测试不需要调工具）。 */
    private class InitOnlyTransport : McpTransport {
        override suspend fun start() {}
        override suspend fun request(method: String, params: JSONObject?, timeoutMillis: Long): Any? {
            if (method == "initialize") {
                return JSONObject().apply {
                    put("protocolVersion", "2025-11-25")
                    put("capabilities", JSONObject())
                    put("serverInfo", JSONObject().apply {
                        put("name", "fake")
                        put("version", "1")
                    })
                }
            }
            throw McpException("unexpected $method", "mcp_rpc_error")
        }

        override suspend fun notify(method: String, params: JSONObject?) {}
        override suspend fun close() {}
        override fun setNotificationListener(listener: ((McpNotification) -> Unit)?) {}
        override fun setServerRequestListener(listener: ((McpJsonRpc.Response.ServerRequest) -> Unit)?) {}
        override suspend fun respond(id: Long, result: JSONObject) {}
        override suspend fun respondError(id: Long, code: Int, message: String) {}
    }

    private fun eventJson(id: String, text: String): String =
        """{"type":"message","channel_id":"ch-1","message":{"id":"$id","agent_id":"example-agent","agent_name":"Example Agent","text":"$text","created_at":1794390000.0}}"""

    private fun message(id: String, text: String) = PlatformChannelMessage(
        id = id,
        agentId = "example-agent",
        agentName = "Example Agent",
        text = text,
        createdAtSeconds = 1794390000.0,
    )

    @Test
    fun `ws push reaches the listener and the MCP client as a notification`() = runBlocking {
        val client = McpClient(InitOnlyTransport())
        val notified = AtomicReference<McpNotification>()
        client.onNotification = { notified.set(it) }
        client.initialize()
        val heard = mutableListOf<PlatformChannelMessage>()
        val latch = CountDownLatch(1)
        val feed = McpChannelEventFeed(
            channelId = "ch-1",
            transport = FakeTransport(),
            wsFactory = { _, _ -> ScriptedWs(listOf(eventJson("m1", "在的"))) },
            mcpClient = client,
            onChannelMessage = { message ->
                synchronized(heard) { heard += message }
                latch.countDown()
            },
        )
        try {
            feed.start()
            assertTrue("WS 推送的消息应在 5 秒内到达", latch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("m1"), heard.map { it.id })
            assertEquals("在的", heard.single().text)
            // 注意顺序：同一条消息先交本地监听（latch 在此松开），
            // 再转交 MCP 客户端——测试线程被唤醒时转交可能还没跑，
            // 须按时限轮询通知，不能读一次就判失败。
            val deadline = System.currentTimeMillis() + 5_000
            var notification = notified.get()
            while (notification == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                notification = notified.get()
            }
            assertTrue("MCP 客户端应收到频道消息通知", notification != null)
            assertEquals(
                McpChannelEventFeed.CHANNEL_MESSAGE_NOTIFICATION,
                notification?.method,
            )
            assertEquals("ch-1", notification?.params?.optString("channel_id"))
            assertEquals("m1", notification?.params?.optJSONObject("message")?.optString("id"))
            assertEquals("在的", notification?.params?.optJSONObject("message")?.optString("text"))
        } finally {
            feed.stop()
            client.close()
            assertEquals(ChannelConnectionState.STOPPED, feed.connectionState)
        }
    }

    @Test
    fun `feed without an MCP client still delivers to the listener`() {
        val heard = mutableListOf<PlatformChannelMessage>()
        val latch = CountDownLatch(1)
        val feed = McpChannelEventFeed(
            channelId = "ch-1",
            transport = FakeTransport(),
            wsFactory = { _, _ -> ScriptedWs(listOf(eventJson("m1", "在的"))) },
            mcpClient = null,
            onChannelMessage = { message ->
                synchronized(heard) { heard += message }
                latch.countDown()
            },
        )
        try {
            feed.start()
            assertTrue("没有 MCP 客户端时本地监听也应收到消息", latch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("m1"), heard.map { it.id })
        } finally {
            feed.stop()
        }
    }

    @Test
    fun `poll fallback delivers messages when ws closes at once`() {
        val heard = mutableListOf<PlatformChannelMessage>()
        val latch = CountDownLatch(1)
        val feed = McpChannelEventFeed(
            channelId = "ch-1",
            transport = FakeTransport(mutableListOf(message("m1", "在吗"))),
            wsFactory = { _, _ -> ScriptedWs(emptyList()) },
            mcpClient = null,
            onChannelMessage = { msg ->
                synchronized(heard) { heard += msg }
                latch.countDown()
            },
        )
        try {
            feed.start()
            assertTrue("初始轮询应在 5 秒内交付消息", latch.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("m1"), heard.map { it.id })
        } finally {
            feed.stop()
        }
    }
}
