package wherevercode.agent.core.team

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 中转去重存储 + 权限门测试（2026-11-13）。
 *
 * 证明三件事：① 去重存储（内存与键值两款）记了就认得、超量丢最旧的；
 * ② 参与者名单已知时，非成员发来的 @ 不中转、@ 非成员不转；
 * ③ 持久化去重口记了已中转的 id 后，同一条不再中转（App 重启后
 * 也一样——存储本身跨重启，MainActivity 接线后生效）。
 */
@RunWith(RobolectricTestRunner::class)
class ChannelRelayDedupTest {

    private class MapKeyValueStore : ChannelKeyValueStore {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            map[key] = value
        }
    }

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
        deviceId: String? = "mac",
    ) = AgentRecord(
        agentId = agentId,
        displayName = displayName,
        kind = kind,
        access = AgentAccess.CLI,
        brainKind = brainKind,
        adapterId = "test-adapter",
        binary = "test",
        deviceId = deviceId,
    )

    private fun message(id: String, text: String, agentId: String = "example-agent") =
        PlatformChannelMessage(
            id = id,
            agentId = agentId,
            agentName = agentId,
            text = text,
            createdAtSeconds = 1_794_390_000.0,
        )

    private class GateHandPort : RemoteAgentChatPort {
        val calls = mutableListOf<String>()
        override fun runAgent(
            target: RemoteTarget,
            taskId: String,
            text: String,
            timeoutSeconds: Long,
            goalVersion: Long,
            epoch: Long,
        ): Flow<HandEvent> {
            calls += target.agentId
            return flowOf(HandEvent.Result(1, "行，但要先补测试。"))
        }

        override fun sendControl(
            target: RemoteTarget,
            taskId: String,
            command: RemoteControlCommand,
        ): Flow<HandEvent> = flowOf()
    }

    private class GateManusPort : ManusChatPort {
        override fun runChat(
            conversationId: String,
            agent: AgentRecord,
            prompt: String,
        ): Flow<ManusChatEvent> = flowOf()

        override fun stopTask(conversationId: String, agent: AgentRecord): Boolean = false
        override fun forgetConversation(conversationId: String) = Unit
    }

    private class GateTransport : PlatformChannelTransport {
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
        handPort: GateHandPort = GateHandPort(),
        transport: GateTransport = GateTransport(),
        participantIds: Set<String>? = null,
        dedupStore: ChannelRelayDedupStore? = null,
    ) = ChannelAgentRelay(
        handPort = handPort,
        manusPort = GateManusPort(),
        transport = transport,
        channelId = "chan-1",
        resolveTarget = { agent -> target(agent.agentId, "mac") },
        isManusConfigured = { false },
        relayedIds = mutableSetOf(),
        relayOutputIds = mutableSetOf(),
        timeoutSeconds = 5,
        timeoutMarginSeconds = 0,
        participantIds = participantIds,
        dedupStore = dedupStore,
    )

    private val agents = listOf(
        record("codex", "Codex"),
        record("example-agent", "Example Agent", kind = AgentKind.CLOUD, brainKind = BrainKind.PLATFORM_AGENT, deviceId = null),
    )

    @Test
    fun `in-memory dedup store remembers and prunes the oldest`() {
        val store = InMemoryChannelRelayDedupStore(maxKept = 2)
        assertFalse(store.isRelayed("m1"))
        store.markRelayed("m1")
        assertTrue(store.isRelayed("m1"))
        store.markRelayed("")
        assertFalse(store.isRelayed(""))
        store.markRelayed("m2")
        store.markRelayed("m3")
        // 超量丢最旧的（m1），新的还在。
        assertFalse(store.isRelayed("m1"))
        assertTrue(store.isRelayed("m2"))
        assertTrue(store.isRelayed("m3"))
        assertFalse(store.isRelayOutput("m1"))
        store.markRelayOutput("m-posted-1")
        assertTrue(store.isRelayOutput("m-posted-1"))
    }

    @Test
    fun `key-value dedup store round-trips through the store`() {
        val kv = MapKeyValueStore()
        val store = KeyValueChannelRelayDedupStore(kv, "chan-1", maxKept = 2)
        store.markRelayed("m1")
        store.markRelayed("m2")
        assertTrue(store.isRelayed("m1"))
        assertTrue(store.isRelayed("m2"))
        // 另一个频道互不干扰。
        val other = KeyValueChannelRelayDedupStore(kv, "chan-2")
        assertFalse(other.isRelayed("m1"))
        // 重新打开同一频道（模拟 App 重启后新建实例）仍认得。
        val reopened = KeyValueChannelRelayDedupStore(kv, "chan-1", maxKept = 2)
        assertTrue(reopened.isRelayed("m1"))
        reopened.markRelayed("m3")
        assertFalse(reopened.isRelayed("m1"))
        assertTrue(reopened.isRelayed("m3"))
    }

    @Test
    fun `relay skips messages from non-member senders when the roster is known`() = runBlocking {
        val handPort = GateHandPort()
        val outcomes = relay(
            handPort = handPort,
            participantIds = setOf("example-agent", "codex"),
        ).relay(message("m1", "@codex 这个方案行不行？", agentId = "outsider"), agents)
        assertTrue(outcomes.isEmpty())
        assertTrue(handPort.calls.isEmpty())
    }

    @Test
    fun `relay fails the target when it is not a member`() = runBlocking {
        val handPort = GateHandPort()
        // 发送者是成员，@ 的 codex 不在名单里 → 不转，如实记 FAILED。
        val outcomes = relay(
            handPort = handPort,
            participantIds = setOf("example-agent"),
        ).relay(message("m1", "@codex 这个方案行不行？"), agents)
        assertEquals(1, outcomes.size)
        assertEquals(RelayStatus.FAILED, outcomes.single().status)
        assertTrue(outcomes.single().detail!!.contains("不在频道成员里"))
        assertTrue(handPort.calls.isEmpty())
    }

    @Test
    fun `relay without a roster behaves as before`() = runBlocking {
        val handPort = GateHandPort()
        val transport = GateTransport()
        val outcomes = relay(handPort = handPort, transport = transport, participantIds = null)
            .relay(message("m1", "@codex 这个方案行不行？"), agents)
        assertEquals(1, outcomes.size)
        assertEquals(RelayStatus.RELAYED, outcomes.single().status)
        assertEquals(listOf("codex"), handPort.calls)
        assertEquals(1, transport.posts.size)
    }

    @Test
    fun `relay consults the persistent dedup store`() = runBlocking {
        val store = InMemoryChannelRelayDedupStore()
        store.markRelayed("m1")
        val handPort = GateHandPort()
        val outcomes = relay(handPort = handPort, dedupStore = store)
            .relay(message("m1", "@codex 这个方案行不行？"), agents)
        assertTrue(outcomes.isEmpty())
        assertTrue(handPort.calls.isEmpty())
        // 没记过的照常转，转完记进存储（回流 id 也记）。
        val transport = GateTransport()
        val ok = relay(handPort = handPort, transport = transport, dedupStore = store)
            .relay(message("m2", "@codex 这个方案行不行？"), agents)
        assertEquals(RelayStatus.RELAYED, ok.single().status)
        assertTrue(store.isRelayed("m2"))
        assertTrue(store.isRelayOutput("m-posted-1"))
    }
}
