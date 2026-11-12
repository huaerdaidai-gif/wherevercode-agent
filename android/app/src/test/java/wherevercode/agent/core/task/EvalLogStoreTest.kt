package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentLoginState
import wherevercode.agent.core.transport.HelloRepoCheckout
import wherevercode.agent.core.transport.RemoteAgentInfo
import wherevercode.agent.core.transport.RemoteHello

/**
 * P1 确定性自动派活——SharedPreferences 存储单测（2026-11-11）。
 * Robolectric：ApplicationProvider 的 Context 是真实的
 * SharedPreferences 实现，纯 JVM 单测里 android.content 不可用。
 */
@RunWith(RobolectricTestRunner::class)
class EvalLogStoreTest {

    private fun context() = RuntimeEnvironment.getApplication().applicationContext

    private fun entry(taskId: String) = EvalLogEntry(
        taskId = taskId,
        taskType = TaskType.CODE,
        agentId = "example-agent",
        deviceId = "remote-host",
        mode = "single",
        startedAtMs = 1000,
        finishedAtMs = 2000,
        wallClockMs = 1000,
        reworkRounds = 0,
        attempt = 1,
        outcome = EvalOutcome.COMPLETED,
        assignedVia = "manual",
    )

    @Test
    fun `shared preferences eval log round trip and verdict`() {
        val store = SharedPreferencesEvalLogStore(context())
        store.record(entry("t1"))
        store.record(entry("t2").copy(outcome = EvalOutcome.FAILED))
        assertEquals(2, store.listEntries().size)
        store.recordVerdict("t1", UserVerdict.APPROVED)
        val updated = store.listEntries().first { it.taskId == "t1" }
        assertEquals(UserVerdict.APPROVED, updated.userVerdict)
        // 同一任务再记一条：覆盖，不重复。
        store.record(entry("t1").copy(outcome = EvalOutcome.FAILED))
        assertEquals(2, store.listEntries().size)
        assertEquals(EvalOutcome.FAILED, store.listEntries().first { it.taskId == "t1" }.outcome)
    }

    @Test
    fun `shared preferences eval log survives a new store instance`() {
        val first = SharedPreferencesEvalLogStore(context())
        first.record(entry("t1"))
        val second = SharedPreferencesEvalLogStore(context())
        assertEquals(1, second.listEntries().size)
        assertEquals("t1", second.listEntries().first().taskId)
    }

    @Test
    fun `shared preferences routing log round trip with attach`() {
        val log = SharedPreferencesRoutingDecisionLog(context())
        val decision = CapabilityRouter.route(
            listOf(
                CapabilityCard(
                    agentId = "example-agent", displayName = "Example Agent", deviceId = "remote-host",
                    deviceDisplayName = "远程设备", kind = AgentKind.DEVICE,
                    cliBinary = "grok", modelOrMembership = "grok", tools = listOf("dev"),
                    loginState = AgentLoginState.LOGGED_IN,
                    dispatchable = true, costClass = CostClass.FREE,
                    lastSeenEpochSeconds = 1_700_000_000 - 60, pairingConfirmed = true,
                    repos = emptyList(), hostedProjectIds = setOf("wherevercode-agent"),
                    activeTaskCount = 0, updatedAtEpochSeconds = 1_700_000_000,
                ),
            ),
            RoutingRequirement(
                projectId = "wherevercode-agent", taskType = TaskType.CODE,
                nowEpochSeconds = 1_700_000_000,
            ),
            projectHasDeviceLocation = true,
        )
        log.record(decision)
        assertNull(log.decisionForTask("task-1"))
        log.attachTask(decision.decisionId, "task-1")
        val reloaded = SharedPreferencesRoutingDecisionLog(context())
        val found = reloaded.decisionForTask("task-1")
        assertEquals(decision.decisionId, found?.decisionId)
        assertEquals("example-agent", found?.chosenAgentId)
        assertEquals(1, found?.candidates?.size)
    }

    @Test
    fun `shared preferences capability card snapshot round trip`() {
        val store = SharedPreferencesCapabilityCardStore(context())
        val hello = RemoteHello(
            deviceId = "remote-host",
            displayName = "远程设备",
            publicKey = ByteArray(32),
            agents = listOf(
                RemoteAgentInfo(
                    agentId = "example-agent",
                    displayName = "Example Agent",
                    kind = "cli",
                    folder = "/workspace/example-project",
                    repos = listOf(HelloRepoCheckout("/workspace/example-project", "main", "abc123456789")),
                ),
            ),
        )
        store.noteHello("remote-host", hello)
        val reloaded = SharedPreferencesCapabilityCardStore(context())
        val repos = reloaded.helloRepos()["remote-host"]?.get("example-agent")
        assertEquals(1, repos?.size)
        assertEquals("main", repos?.first()?.branch)
        assertEquals("abc123456789", repos?.first()?.commit)
        assertEquals("hand", repos?.first()?.source)
        // 没有 repos 的 hello 不覆盖已有快照。
        val emptyHello = hello.copy(agents = listOf(hello.agents.first().copy(repos = emptyList())))
        reloaded.noteHello("remote-host", emptyHello)
        val kept = SharedPreferencesCapabilityCardStore(context()).helloRepos()
        assertTrue(kept["remote-host"]?.get("example-agent")?.isNotEmpty() == true)
    }
}
