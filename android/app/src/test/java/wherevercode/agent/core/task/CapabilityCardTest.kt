package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentLoginState
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.project.ProjectHomeKind
import wherevercode.agent.core.project.ProjectLocation
import wherevercode.agent.core.transport.RemoteDevice
import wherevercode.agent.core.transport.RemoteHello

/**
 * P1 确定性自动派活——能力卡组装单测（2026-11-11）。
 * 纯逻辑，不碰 Android、不碰网络。
 */
class CapabilityCardTest {

    private val now = 1_700_000_000L

    private fun agent(
        agentId: String,
        kind: AgentKind = AgentKind.DEVICE,
        deviceId: String? = "remote-host",
        capabilities: List<String> = listOf("dev"),
        loginState: AgentLoginState = AgentLoginState.LOGGED_IN,
        dispatchable: Boolean = true,
    ) = AgentRecord(
        agentId = agentId,
        displayName = agentId,
        kind = kind,
        access = if (kind == AgentKind.CLOUD) AgentAccess.API else AgentAccess.CLI,
        brainKind = BrainKind.MODEL_API,
        adapterId = "cli",
        binary = agentId,
        capabilities = capabilities,
        loginState = loginState,
        dispatchable = dispatchable,
        deviceId = deviceId,
        deviceFolder = "/workspace",
    )

    private fun device(deviceId: String = "remote-host", lastSeen: Long = now - 60) =
        RemoteDevice(
            deviceId = deviceId,
            displayName = "远程设备",
            host = "192.0.2.10",
            port = 18743,
            lastSeenEpochSeconds = lastSeen,
            lastServerStatus = "APPROVED",
        )

    private fun location(projectId: String, hostId: String, path: String) =
        ProjectLocation(
            locationId = "loc-$projectId-$hostId",
            projectId = projectId,
            hostId = hostId,
            workspacePath = path,
            homeKind = ProjectHomeKind.DEVICE,
        )

    @Test
    fun `build fills card from stores`() {
        val cards = CapabilityCards.build(
            agents = listOf(agent("example-agent")),
            devices = listOf(device()),
            pairedDeviceIds = setOf("remote-host"),
            allLocations = listOf(location("wherevercode-agent", "remote-host", "/workspace/example-project")),
            tasks = listOf(
                TaskRecord(taskId = "t1", state = "working", assignedAgentId = "example-agent"),
                TaskRecord(taskId = "t2", state = "completed", assignedAgentId = "example-agent"),
            ),
            evalEntries = emptyList(),
            nowEpochSeconds = now,
        )
        assertEquals(1, cards.size)
        val card = cards.first()
        assertEquals("example-agent", card.agentId)
        assertEquals("remote-host", card.deviceId)
        assertEquals(now - 60, card.lastSeenEpochSeconds)
        assertTrue(card.pairingConfirmed)
        assertEquals(CostClass.FREE, card.costClass)
        assertEquals(setOf("wherevercode-agent"), card.hostedProjectIds)
        assertEquals(1, card.activeTaskCount)
        assertEquals(1, card.repos.size)
        assertEquals("/workspace/example-project", card.repos.first().path)
        // 本机位置记录不知道分支/commit：不编造。
        assertNull(card.repos.first().branch)
        assertNull(card.repos.first().commit)
        assertEquals("phone", card.repos.first().source)
    }

    @Test
    fun `hello snapshot overlays branch and commit on the same path`() {
        val helloRepos = mapOf(
            "remote-host" to mapOf(
                "example-agent" to listOf(
                    RepoCheckout("/workspace/example-project", "main", "abc123456789", "hand"),
                ),
            ),
        )
        val cards = CapabilityCards.build(
            agents = listOf(agent("example-agent")),
            devices = listOf(device()),
            pairedDeviceIds = setOf("remote-host"),
            allLocations = listOf(location("wherevercode-agent", "remote-host", "/workspace/example-project")),
            tasks = emptyList(),
            evalEntries = emptyList(),
            helloRepos = helloRepos,
            nowEpochSeconds = now,
        )
        val repo = cards.first().repos.first()
        assertEquals("main", repo.branch)
        assertEquals("abc123456789", repo.commit)
        assertEquals("hand", repo.source)
    }

    @Test
    fun `cloud agent is metered and needs no pairing or last seen`() {
        val cards = CapabilityCards.build(
            agents = listOf(agent("manus", kind = AgentKind.CLOUD, deviceId = null)),
            devices = emptyList(),
            pairedDeviceIds = emptySet(),
            allLocations = emptyList(),
            tasks = emptyList(),
            evalEntries = emptyList(),
            nowEpochSeconds = now,
        )
        val card = cards.first()
        assertEquals(CostClass.METERED, card.costClass)
        assertTrue(card.pairingConfirmed)
        assertEquals(0L, card.lastSeenEpochSeconds)
        assertTrue(card.repos.isEmpty())
        assertTrue(card.hostedProjectIds.isEmpty())
    }

    @Test
    fun `success rates from eval log land on the card for display only`() {
        val entries = listOf(
            EvalLogEntry(
                taskId = "t1", taskType = TaskType.CODE, agentId = "example-agent", deviceId = "remote-host",
                mode = "single", startedAtMs = 1000, finishedAtMs = 2000, wallClockMs = 1000,
                reworkRounds = 0, attempt = 1, outcome = EvalOutcome.COMPLETED, assignedVia = "manual",
            ),
            EvalLogEntry(
                taskId = "t2", taskType = TaskType.CODE, agentId = "example-agent", deviceId = "remote-host",
                mode = "single", startedAtMs = 1000, finishedAtMs = 2000, wallClockMs = 1000,
                reworkRounds = 1, attempt = 2, outcome = EvalOutcome.FAILED, assignedVia = "manual",
            ),
        )
        val cards = CapabilityCards.build(
            agents = listOf(agent("example-agent")),
            devices = listOf(device()),
            pairedDeviceIds = setOf("remote-host"),
            allLocations = emptyList(),
            tasks = emptyList(),
            evalEntries = entries,
            nowEpochSeconds = now,
        )
        assertEquals(0.5, cards.first().successRateByTaskType["CODE"]!!, 0.001)
    }

    @Test
    fun `in memory hello snapshot stores repos per device and agent`() {
        val store = InMemoryCapabilityCardStore()
        val hello = RemoteHello(
            deviceId = "remote-host",
            displayName = "远程设备",
            publicKey = ByteArray(32),
            agents = listOf(
                wherevercode.agent.core.transport.RemoteAgentInfo(
                    agentId = "example-agent",
                    displayName = "Example Agent",
                    kind = "cli",
                    folder = "/workspace/example-project",
                    repos = listOf(
                        wherevercode.agent.core.transport.HelloRepoCheckout(
                            "/workspace/example-project", "main", "abc123456789",
                        ),
                    ),
                ),
            ),
        )
        store.noteHello("remote-host", hello)
        val repos = store.helloRepos()["remote-host"]?.get("example-agent")
        assertEquals(1, repos?.size)
        assertEquals("main", repos?.first()?.branch)
        assertEquals("hand", repos?.first()?.source)
    }

    @Test
    fun `hello without repos stores nothing`() {
        val store = InMemoryCapabilityCardStore()
        val hello = RemoteHello(
            deviceId = "remote-host",
            displayName = "远程设备",
            publicKey = ByteArray(32),
            agents = listOf(
                wherevercode.agent.core.transport.RemoteAgentInfo(
                    agentId = "example-agent",
                    displayName = "Example Agent",
                    kind = "cli",
                    folder = "/workspace/example-project",
                ),
            ),
        )
        store.noteHello("remote-host", hello)
        assertTrue(store.helloRepos().isEmpty())
    }
}
