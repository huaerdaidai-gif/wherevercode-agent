package wherevercode.agent.core.team

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.agent.ManusChatEvent
import wherevercode.agent.core.agent.ManusChatPort
import wherevercode.agent.core.agent.PlatformChannelMessage
import wherevercode.agent.core.agent.PlatformChannelTransport
import wherevercode.agent.core.mcp.HandEvent
import wherevercode.agent.core.mcp.RemoteAgentChatPort
import wherevercode.agent.core.mcp.RemoteControlCommand
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.transport.DiscoveredPeer

/**
 * P3 @ 中转测试（2026-11-11，maintainer 22:19 范围改：全部 Agent 拉入频道，
 * 设备/云端 Agent 由调度器当代理中转）。
 *
 * 证明五件事：① 只有带 @ 的消息才中转（@ 是寻址机制，不 @ 不群发）；
 * ② 平台 Agent 跳过（端点直连，不走代理）；③ 回复带结构化归属头
 * （发送者 + 任务，任务从被 @ 的消息继承）贴回频道；④ 转不成如实记
 * FAILED、不贴、不假装回了；⑤ 防回环——同一条消息只转一次，本机贴回
 * 的回复永不再触发中转。
 */
@RunWith(RobolectricTestRunner::class)
class ChannelAgentRelayTest {

    private fun peer(deviceId: String) = DiscoveredPeer(
        deviceId = deviceId,
        displayName = deviceId,
        host = "127.0.0.1",
        port = 18743,
        fingerprintSha256 = "ab".repeat(32),
        capabilities = listOf("hand"),
    )

    private fun target(agentId: String, deviceId: String) =
        RemoteTarget(peer = peer(deviceId), agentId = agentId, folder = "/work")

    private fun record(
        agentId: String,
        displayName: String = agentId,
        kind: AgentKind = AgentKind.DEVICE,
        brainKind: BrainKind = BrainKind.AGENT_RUNTIME,
        adapterId: String = "test-adapter",
        deviceId: String? = "mac",
    ) = AgentRecord(
        agentId = agentId,
        displayName = displayName,
        kind = kind,
        access = AgentAccess.CLI,
        brainKind = brainKind,
        adapterId = adapterId,
        binary = "test",
        deviceId = deviceId,
    )

    private fun message(id: String, text: String) = PlatformChannelMessage(
        id = id,
        agentId = "example-agent",
        agentName = "Example Agent",
        text = text,
        createdAtSeconds = 1_794_390_000.0,
    )

    /** 测试替身：按 agentId 回放 Hand 事件脚本，记下收到的调用。 */
    private class ScriptedHandPort(
        private val scripts: Map<String, List<HandEvent>>,
        private val hanging: Set<String> = emptySet(),
    ) : RemoteAgentChatPort {
        data class Call(val target: RemoteTarget, val taskId: String, val text: String)

        val calls = mutableListOf<Call>()

        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> {
            calls += Call(target, taskId, text)
            if (target.agentId in hanging) {
                return flow { delay(60_000) }
            }
            return flowOf(*(scripts[target.agentId] ?: emptyList()).toTypedArray())
        }

        override fun sendControl(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()
    }

    /** 测试替身：按 agentId 回放 Manus 事件脚本。 */
    private class ScriptedManusPort(
        private val scripts: Map<String, List<ManusChatEvent>>,
    ) : ManusChatPort {
        val calls = mutableListOf<Triple<String, String, String>>()

        override fun runChat(
            conversationId: String,
            agent: AgentRecord,
            prompt: String,
        ): Flow<ManusChatEvent> {
            calls += Triple(conversationId, agent.agentId, prompt)
            return flowOf(*(scripts[agent.agentId] ?: emptyList()).toTypedArray())
        }

        override fun stopTask(conversationId: String, agent: AgentRecord): Boolean = false

        override fun forgetConversation(conversationId: String) = Unit
    }

    /** 测试替身：记下贴回频道的消息，返回假 id。 */
    private class FakeTransport : PlatformChannelTransport {
        val posts = mutableListOf<Pair<String, String>>()

        override suspend fun postMessage(channelId: String, text: String): String {
            posts += channelId to text
            return "m-posted-${posts.size}"
        }

        override suspend fun pollMessages(
            channelId: String,
            afterId: String?,
            limit: Int,
            onlyUnread: Boolean,
            markRead: Boolean,
        ): List<PlatformChannelMessage> = emptyList()
    }

    private fun relay(
        handPort: ScriptedHandPort,
        manusPort: ScriptedManusPort,
        transport: FakeTransport,
        targets: Map<String, RemoteTarget> = mapOf(
            "codex" to target("codex", "mac"),
            "codebuddy" to target("codebuddy", "remote-device"),
        ),
        manusConfigured: Set<String> = setOf("manus"),
        relayedIds: MutableSet<String> = mutableSetOf(),
        relayOutputIds: MutableSet<String> = mutableSetOf(),
        timeoutSeconds: Long = 5,
    ) = ChannelAgentRelay(
        handPort = handPort,
        manusPort = manusPort,
        transport = transport,
        channelId = "chan-1",
        resolveTarget = { agent -> targets[agent.agentId] },
        isManusConfigured = { agent -> agent.agentId in manusConfigured },
        relayedIds = relayedIds,
        relayOutputIds = relayOutputIds,
        timeoutSeconds = timeoutSeconds,
        timeoutMarginSeconds = 0,
    )

    private val agents = listOf(
        record("codex", "Codex"),
        record("codebuddy", "CodeBuddy", deviceId = "remote-device"),
        record("agy", "agy", deviceId = "xiaomi12"),
        record("manus", "Manus", kind = AgentKind.CLOUD, brainKind = BrainKind.CLOUD_AGENT, adapterId = "manus", deviceId = null),
        record("example-agent", "Example Agent", kind = AgentKind.CLOUD, brainKind = BrainKind.PLATFORM_AGENT, adapterId = "platform-agent", deviceId = null),
    )

    @Test
    fun `findMentions matches at-id and at-display-name case-insensitively`() {
        val relay = relay(ScriptedHandPort(emptyMap()), ScriptedManusPort(emptyMap()), FakeTransport())
        assertEquals(listOf("codex"), relay.findMentions("@codex 在吗", agents).map { it.agentId })
        assertEquals(listOf("codex"), relay.findMentions("@Codex 这个方案行不行？", agents).map { it.agentId })
        assertEquals(listOf("codebuddy"), relay.findMentions("@CodeBuddy 帮我看看", agents).map { it.agentId })
        assertEquals(listOf("codex", "codebuddy"), relay.findMentions("@codex @codebuddy 都看看", agents).map { it.agentId })
    }

    @Test
    fun `findMentions ignores plain text and platform agents`() {
        val relay = relay(ScriptedHandPort(emptyMap()), ScriptedManusPort(emptyMap()), FakeTransport())
        assertTrue(relay.findMentions("codex 在吗", agents).isEmpty())
        assertTrue(relay.findMentions("大家在吗", agents).isEmpty())
        // @example-agent 是平台 Agent（端点直连），不走代理。
        assertTrue(relay.findMentions("@example-agent 在吗", agents).isEmpty())
        assertTrue(relay.findMentions("@Example Agent 在吗", agents).isEmpty())
    }

    @Test
    fun `findMentions skips disabled agents`() {
        // Agent 开关（2026-11-12）：停用的 Agent 不接 @ 中转——
        // 频道里 @ 它也不回，重新启用后才恢复。
        val relay = relay(ScriptedHandPort(emptyMap()), ScriptedManusPort(emptyMap()), FakeTransport())
        val mixed = agents.map { if (it.agentId == "codex") it.copy(enabled = false) else it }
        assertTrue(relay.findMentions("@codex 在吗", mixed).isEmpty())
        assertEquals(listOf("codebuddy"), relay.findMentions("@codex @codebuddy 都看看", mixed).map { it.agentId })
    }

    @Test
    fun `relay posts the hand reply back with the agent name prefix`() = runBlocking {
        val transport = FakeTransport()
        val handPort = ScriptedHandPort(
            mapOf("codex" to listOf(HandEvent.Result(1, "行，但要先补测试。"))),
        )
        val outcomes = relay(handPort, ScriptedManusPort(emptyMap()), transport)
            .relay(message("m1", "@codex 这个方案行不行？"), agents)
        assertEquals(1, outcomes.size)
        assertEquals(RelayStatus.RELAYED, outcomes.single().status)
        assertEquals("codex", outcomes.single().agentId)
        // 2026-11-12 团队对话分串：中转回复带结构化归属头（发送者 +
        // 任务），不再是裸 "[显示名] " 前缀；来源消息无任务标签时只写发送者。
        assertEquals(listOf("chan-1" to "【Codex】\n行，但要先补测试。"), transport.posts)
        // 转达正文写明这是 @ 转达、不是派活。
        assertTrue(handPort.calls.single().text.contains("@ 转达"))
        assertTrue(handPort.calls.single().text.contains("不要改任何文件、不要建任务、不要认领派活"))
        assertTrue(handPort.calls.single().taskId.startsWith("relay-m1-codex"))
    }

    @Test
    fun `relay reply inherits the task from the source message header`() = runBlocking {
        // 2026-11-12 团队对话分串：被 @ 的那条消息带任务标签时，
        // 中转回复继承它的任务（发送者换成本人、任务不变），回复
        // 进同一任务串，不串到别的任务里。
        val transport = FakeTransport()
        val handPort = ScriptedHandPort(
            mapOf("codex" to listOf(HandEvent.Result(1, "行，先补测试。"))),
        )
        val source = message(
            "m1",
            "【Example Agent · 任务 abc12345 · 修登录闪退】\n@codex 这个方案行不行？",
        )
        val outcomes = relay(handPort, ScriptedManusPort(emptyMap()), transport)
            .relay(source, agents)
        assertEquals(RelayStatus.RELAYED, outcomes.single().status)
        assertEquals(
            listOf("chan-1" to "【Codex · 任务 abc12345 · 修登录闪退】\n行，先补测试。"),
            transport.posts,
        )
    }

    @Test
    fun `relay does not run twice for the same message and never relays its own output`() = runBlocking {
        val transport = FakeTransport()
        val handPort = ScriptedHandPort(
            mapOf("codex" to listOf(HandEvent.Result(1, "在。"))),
        )
        val relayedIds = mutableSetOf<String>()
        val relayOutputIds = mutableSetOf<String>()
        val r = relay(
            handPort,
            ScriptedManusPort(emptyMap()),
            transport,
            relayedIds = relayedIds,
            relayOutputIds = relayOutputIds,
        )
        val msg = message("m1", "@codex 在吗")
        assertEquals(1, r.relay(msg, agents).size)
        assertEquals(1, transport.posts.size)
        // 同一条消息再来一次：不转。
        assertTrue(r.relay(msg, agents).isEmpty())
        assertEquals(1, transport.posts.size)
        assertEquals(1, handPort.calls.size)
        // 本机贴回的回复（id 已记进 relayOutputIds）再进 feed：不转。
        val output = message("m-posted-1", "【Codex】\n在。@codebuddy 你呢？")
        assertTrue(r.relay(output, agents).isEmpty())
        assertEquals(1, transport.posts.size)
    }

    @Test
    fun `relay reports blocked and unresolvable agents as failed without posting`() = runBlocking {
        val transport = FakeTransport()
        val handPort = ScriptedHandPort(
            mapOf("codex" to listOf(HandEvent.Blocked("未配对", "NOT_AUTHORIZED"))),
        )
        val outcomes = relay(handPort, ScriptedManusPort(emptyMap()), transport)
            .relay(message("m1", "@codex @agy 都看看"), agents)
        assertEquals(2, outcomes.size)
        assertEquals(RelayStatus.FAILED, outcomes[0].status)
        assertTrue(outcomes[0].detail!!.contains("未配对"))
        // agy 没有可解析的 Hand 目标（测试里 targets 表没它），也不是 Manus。
        assertEquals(RelayStatus.FAILED, outcomes[1].status)
        assertTrue(outcomes[1].detail!!.contains("中转不到它"))
        assertTrue(transport.posts.isEmpty())
        assertEquals(1, handPort.calls.size)
    }

    @Test
    fun `relay reaches manus through the manus port`() = runBlocking {
        val transport = FakeTransport()
        val manusPort = ScriptedManusPort(
            mapOf("manus" to listOf(ManusChatEvent.Result("我看完了，没问题。"))),
        )
        val outcomes = relay(ScriptedHandPort(emptyMap()), manusPort, transport)
            .relay(message("m1", "@manus 帮我审一下"), agents)
        assertEquals(RelayStatus.RELAYED, outcomes.single().status)
        assertEquals(listOf("chan-1" to "【Manus】\n我看完了，没问题。"), transport.posts)
        assertEquals("team-channel-relay-manus", manusPort.calls.single().first)
    }

    @Test
    fun `relay reports a manus failure without posting`() = runBlocking {
        val transport = FakeTransport()
        val manusPort = ScriptedManusPort(
            mapOf("manus" to listOf(ManusChatEvent.Failed("Manus 额度已用完"))),
        )
        val outcomes = relay(ScriptedHandPort(emptyMap()), manusPort, transport)
            .relay(message("m1", "@manus 在吗"), agents)
        assertEquals(RelayStatus.FAILED, outcomes.single().status)
        assertTrue(outcomes.single().detail!!.contains("额度已用完"))
        assertTrue(transport.posts.isEmpty())
    }

    @Test
    fun `relay times out a hanging branch`() = runBlocking {
        val transport = FakeTransport()
        val handPort = ScriptedHandPort(emptyMap(), hanging = setOf("codex"))
        val outcomes = relay(handPort, ScriptedManusPort(emptyMap()), transport, timeoutSeconds = 1)
            .relay(message("m1", "@codex 在吗"), agents)
        assertEquals(RelayStatus.FAILED, outcomes.single().status)
        assertTrue(outcomes.single().detail!!.contains("没有回终态"))
        assertTrue(transport.posts.isEmpty())
    }

    @Test
    fun `relay truncates a long reply to 2000 chars`() = runBlocking {
        val transport = FakeTransport()
        val handPort = ScriptedHandPort(
            mapOf("codex" to listOf(HandEvent.Result(1, "x".repeat(2500)))),
        )
        relay(handPort, ScriptedManusPort(emptyMap()), transport)
            .relay(message("m1", "@codex 详细说说"), agents)
        val posted = transport.posts.single().second
        // 归属头 "【Codex】\n" 与旧 "[Codex] " 同长（8 字），截断后总长不变。
        assertEquals("【Codex】\n".length + TeamChatLimits.RELAY_REPLY_CHARS, posted.length)
        assertTrue(posted.startsWith("【Codex】\nxxx"))
    }
}
