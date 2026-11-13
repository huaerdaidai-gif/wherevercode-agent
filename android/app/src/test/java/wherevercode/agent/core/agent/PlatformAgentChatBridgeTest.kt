package wherevercode.agent.core.agent

import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind

/**
 * 平台 Agent 聊天桥接（V5，2026-11-11）：发带收件方前缀的消息、
 * 轮询等目标 Agent 回复。假传输，不发真实调用。
 */
class PlatformAgentChatBridgeTest {

    private fun grokBotAgent() = AgentRecord(
        agentId = "example-agent",
        displayName = "Example Agent",
        kind = AgentKind.CLOUD,
        access = AgentAccess.WEBHOOK,
        brainKind = BrainKind.PLATFORM_AGENT,
        adapterId = "platform-agent",
        baseUrl = "https://example.invalid",
        platformAgentId = "example-agent",
        platformHost = "example.invalid",
        platformChannelId = "chan-1",
    )

    private class ScriptedTransport(
        private val postedId: String = "m0",
        private val replies: List<List<PlatformChannelMessage>> = listOf(
            listOf(
                PlatformChannelMessage(
                    id = "m1",
                    agentId = "a-grok",
                    agentName = "example agent",
                    text = "在的，有什么事？",
                    createdAtSeconds = 1794300000.0,
                ),
            ),
        ),
        private var failPolls: Int = 0,
        private val failAfterIds: Set<String> = emptySet(),
    ) : PlatformChannelTransport {
        data class Post(val channelId: String, val text: String)
        val posts = mutableListOf<Post>()
        var polls = 0
        val pollAfterIds = mutableListOf<String?>()
        override suspend fun postMessage(channelId: String, text: String): String {
            posts += Post(channelId, text)
            return postedId
        }
        override suspend fun pollMessages(
            channelId: String,
            afterId: String?,
            limit: Int,
            onlyUnread: Boolean,
            markRead: Boolean,
        ): List<PlatformChannelMessage> {
            polls += 1
            pollAfterIds += afterId
            if (afterId != null && afterId in failAfterIds) {
                // 模拟服务端对已不存在的消息 id 的报错原文（V5 端点
                // `_message_cursor`，message_not_found）。
                throw IOException("消息不存在")
            }
            if (failPolls > 0) {
                failPolls -= 1
                throw IOException("fake network failure")
            }
            return replies.getOrElse(polls - 1) { emptyList() }
        }
    }

    private fun bridge(
        transport: PlatformChannelTransport,
        token: String? = "test-token-not-real",
        timeoutMs: Long = 5_000,
        pollIntervalMs: Long = 10,
        onFactory: ((String) -> Unit)? = null,
        cursorStore: PlatformReplyCursorStore = InMemoryPlatformReplyCursorStore(),
    ) = PlatformAgentChatBridge(
        tokenProvider = { token },
        transportFactory = { _, endpoint ->
            onFactory?.invoke(endpoint)
            transport
        },
        timeoutMs = timeoutMs,
        pollIntervalMs = pollIntervalMs,
        cursorStore = cursorStore,
    )

    @Test
    fun `reply from the target agent is returned and nothing is faked`() = runBlocking {
        val transport = ScriptedTransport()
        val events = bridge(transport).runChat("conv-1", grokBotAgent(), "在吗").toList()

        assertEquals(1, transport.posts.size)
        assertEquals("chan-1", transport.posts[0].channelId)
        // 收件方在正文前缀里（平台 API 没有收件方字段）。
        assertEquals("@example-agent 在吗", transport.posts[0].text)
        val result = events.filterIsInstance<PlatformChatEvent.Result>().single()
        assertEquals("在的，有什么事？", result.text)
        assertTrue(events.none { it is PlatformChatEvent.Failed })
    }

    @Test
    fun `messages from other agents are ignored`() = runBlocking {
        val transport = ScriptedTransport(
            replies = listOf(
                listOf(
                    PlatformChannelMessage(
                        id = "m1",
                        agentId = "a-muse",
                        agentName = "muse",
                        text = "我先回一句",
                        createdAtSeconds = 1794300000.0,
                    ),
                ),
                listOf(
                    PlatformChannelMessage(
                        id = "m2",
                        agentId = "a-grok",
                        agentName = "example agent",
                        text = "轮到我回了。",
                        createdAtSeconds = 1794300001.0,
                    ),
                ),
            ),
        )
        val events = bridge(transport).runChat("conv-1", grokBotAgent(), "在吗").toList()

        val result = events.filterIsInstance<PlatformChatEvent.Result>().single()
        assertEquals("轮到我回了。", result.text)
    }

    @Test
    fun `no token fails honestly and posts nothing`() = runBlocking {
        val transport = ScriptedTransport()
        val events = bridge(transport, token = null)
            .runChat("conv-1", grokBotAgent(), "在吗").toList()

        assertTrue(transport.posts.isEmpty())
        val failed = events.filterIsInstance<PlatformChatEvent.Failed>().single()
        assertTrue(failed.message.contains("未配置平台 Agent 凭证"))
    }

    @Test
    fun `no channel configured fails before sending a platform request`() = runBlocking {
        val transport = ScriptedTransport()
        val agent = grokBotAgent().copy(platformChannelId = null)
        val events = bridge(transport).runChat("conv-1", agent, "在吗").toList()

        assertTrue(transport.posts.isEmpty())
        assertTrue(events.filterIsInstance<PlatformChatEvent.Failed>().single().message.contains("未配置平台频道"))
    }

    @Test
    fun `endpoint passed to the transport follows the agent platform host`() = runBlocking {
        val transport = ScriptedTransport()
        var seenEndpoint: String? = null
        val agent = grokBotAgent().copy(platformHost = "us.example")
        bridge(transport, onFactory = { seenEndpoint = it })
            .runChat("conv-1", agent, "在吗").toList()

        assertEquals("https://us.example/api/open/v1/mcp", seenEndpoint)
    }

    @Test
    fun `endpoint remains unset when no agent host is configured`() = runBlocking {
        val transport = ScriptedTransport()
        var seenEndpoint: String? = null
        val agent = grokBotAgent().copy(platformHost = null)
        bridge(transport, onFactory = { seenEndpoint = it })
            .runChat("conv-1", agent, "在吗").toList()

        assertEquals(null, seenEndpoint)
    }

    @Test
    fun `timeout fails honestly and never invents a reply`() = runBlocking {
        val transport = ScriptedTransport(replies = listOf(emptyList()))
        val events = bridge(transport, timeoutMs = 100, pollIntervalMs = 10)
            .runChat("conv-1", grokBotAgent(), "在吗").toList()

        assertEquals(1, transport.posts.size)
        assertTrue(events.none { it is PlatformChatEvent.Result })
        val failed = events.filterIsInstance<PlatformChatEvent.Failed>().single()
        assertTrue(failed.message.contains("超时"))
    }

    @Test
    fun `over-long prompt is refused before posting`() = runBlocking {
        val transport = ScriptedTransport()
        val events = bridge(transport)
            .runChat("conv-1", grokBotAgent(), "x".repeat(5000)).toList()

        assertTrue(transport.posts.isEmpty())
        val failed = events.filterIsInstance<PlatformChatEvent.Failed>().single()
        assertTrue(failed.message.contains("太长"))
    }

    @Test
    fun `orphaned reply from before this round is picked up via the stored cursor`() = runBlocking {
        // 还原 2026-11-11 Example Agent 那次：上一轮等待中途断掉，对方在断档里
        // 回了一条；新一轮发帖后，桥接先从游标往后找，捡回这条回复。
        // 频道里对方名字是 "Example Agent"，表单里是 "example-agent"——归一化后
        // 是同一位，大小写/空格不是问题。
        val cursorStore = InMemoryPlatformReplyCursorStore()
        cursorStore.put("chan-1", "example-agent", "m-old")
        val orphan = PlatformChannelMessage(
            id = "m-orphan",
            agentId = "a-grok",
            agentName = "Example Agent",
            text = "在的，刚回来看到了。",
            createdAtSeconds = 1794300000.0,
        )
        val transport = ScriptedTransport(replies = listOf(listOf(orphan)))
        val events = bridge(transport, cursorStore = cursorStore)
            .runChat("conv-1", grokBotAgent(), "在吗").toList()

        assertEquals("m-old", transport.pollAfterIds.firstOrNull())
        val result = events.filterIsInstance<PlatformChatEvent.Result>().single()
        assertEquals("在的，刚回来看到了。", result.text)
        assertEquals("m-orphan", result.messageId)
        assertEquals("m-orphan", cursorStore.get("chan-1", "example-agent"))
        assertTrue(events.none { it is PlatformChatEvent.Failed })
    }

    @Test
    fun `transient poll failure retries instead of failing the round`() = runBlocking {
        val reply = PlatformChannelMessage(
            id = "m1",
            agentId = "a-grok",
            agentName = "example agent",
            text = "在的，有什么事？",
            createdAtSeconds = 1794300000.0,
        )
        val transport = ScriptedTransport(
            replies = listOf(emptyList(), emptyList(), listOf(reply)),
            failPolls = 2,
        )
        val events = bridge(transport).runChat("conv-1", grokBotAgent(), "在吗").toList()

        assertEquals(3, transport.polls)
        val result = events.filterIsInstance<PlatformChatEvent.Result>().single()
        assertEquals("在的，有什么事？", result.text)
        assertTrue(events.none { it is PlatformChatEvent.Failed })
        // 第一次失败记一条重试进度，不刷屏。
        assertEquals(
            1,
            events.filterIsInstance<PlatformChatEvent.Progress>()
                .count { it.message.contains("重试") },
        )
    }

    @Test
    fun `stale cursor falls back to the posted id`() = runBlocking {
        val cursorStore = InMemoryPlatformReplyCursorStore()
        cursorStore.put("chan-1", "example-agent", "stale-id")
        val reply = PlatformChannelMessage(
            id = "m1",
            agentId = "a-grok",
            agentName = "example agent",
            text = "在的，有什么事？",
            createdAtSeconds = 1794300000.0,
        )
        val transport = ScriptedTransport(
            replies = listOf(emptyList(), listOf(reply)),
            failAfterIds = setOf("stale-id"),
        )
        val events = bridge(transport, cursorStore = cursorStore)
            .runChat("conv-1", grokBotAgent(), "在吗").toList()

        assertEquals(listOf("stale-id", "m0"), transport.pollAfterIds)
        val result = events.filterIsInstance<PlatformChatEvent.Result>().single()
        assertEquals("在的，有什么事？", result.text)
        assertEquals("m1", cursorStore.get("chan-1", "example-agent"))
    }

    @Test
    fun `transient failure on the first poll keeps the cursor and still finds the orphan`() = runBlocking {
        // 首轮轮询是网络抖动（不是“消息不存在”）时，游标不能清：
        // 重试仍从游标往后找，断档回复不能丢。
        val cursorStore = InMemoryPlatformReplyCursorStore()
        cursorStore.put("chan-1", "example-agent", "m-old")
        val orphan = PlatformChannelMessage(
            id = "m-orphan",
            agentId = "a-grok",
            agentName = "Example Agent",
            text = "在的，刚回来看到了。",
            createdAtSeconds = 1794300000.0,
        )
        val transport = ScriptedTransport(
            replies = listOf(emptyList(), listOf(orphan)),
            failPolls = 1,
        )
        val events = bridge(transport, cursorStore = cursorStore)
            .runChat("conv-1", grokBotAgent(), "在吗").toList()

        assertEquals(listOf("m-old", "m-old"), transport.pollAfterIds)
        val result = events.filterIsInstance<PlatformChatEvent.Result>().single()
        assertEquals("在的，刚回来看到了。", result.text)
        assertEquals("m-orphan", result.messageId)
    }
}
