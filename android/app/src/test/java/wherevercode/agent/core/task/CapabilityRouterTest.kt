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

/**
 * P1 确定性自动派活——打分路由单测（2026-11-11）。
 * 纯逻辑，不碰 Android、不碰网络。
 */
class CapabilityRouterTest {

    private val now = 1_700_000_000L

    private fun deviceAgent(
        agentId: String,
        deviceId: String = "remote-host",
        capabilities: List<String> = listOf("dev"),
        loginState: AgentLoginState = AgentLoginState.LOGGED_IN,
        dispatchable: Boolean = true,
        hostedProjectIds: Set<String> = setOf("wherevercode-agent"),
        activeTaskCount: Int = 0,
        lastSeen: Long = now - 60,
        pairingConfirmed: Boolean = true,
        authorizedProjectIds: List<String> = emptyList(),
    ) = CapabilityCard(
        agentId = agentId,
        displayName = agentId,
        deviceId = deviceId,
        deviceDisplayName = "远程设备",
        kind = AgentKind.DEVICE,
        cliBinary = agentId,
        modelOrMembership = agentId,
        tools = capabilities,
        loginState = loginState,
        dispatchable = dispatchable,
        authorizedProjectIds = authorizedProjectIds,
        costClass = CostClass.FREE,
        lastSeenEpochSeconds = lastSeen,
        pairingConfirmed = pairingConfirmed,
        repos = listOf(RepoCheckout("/workspace", "main", "abc123", "hand")),
        hostedProjectIds = hostedProjectIds,
        activeTaskCount = activeTaskCount,
        updatedAtEpochSeconds = now,
    )

    private fun cloudAgent(agentId: String = "manus") = CapabilityCard(
        agentId = agentId,
        displayName = "Manus",
        deviceId = null,
        deviceDisplayName = null,
        kind = AgentKind.CLOUD,
        cliBinary = null,
        modelOrMembership = "Manus 2.0",
        tools = listOf("dev"),
        loginState = AgentLoginState.LOGGED_IN,
        dispatchable = true,
        costClass = CostClass.METERED,
        lastSeenEpochSeconds = 0,
        pairingConfirmed = true,
        repos = emptyList(),
        hostedProjectIds = emptySet(),
        activeTaskCount = 0,
        updatedAtEpochSeconds = now,
    )

    private fun requirement(
        projectId: String? = "wherevercode-agent",
        taskType: TaskType = TaskType.CODE,
        pinnedAgentId: String? = null,
        reviewCoderAgentId: String? = null,
        allowMetered: Boolean = false,
    ) = RoutingRequirement(
        projectId = projectId,
        taskType = taskType,
        pinnedAgentId = pinnedAgentId,
        reviewCoderAgentId = reviewCoderAgentId,
        allowMetered = allowMetered,
        nowEpochSeconds = now,
    )

    @Test
    fun `route picks idle fresh-online agent with matching capability`() {
        val cards = listOf(
            deviceAgent("example-agent", activeTaskCount = 1, lastSeen = now - 3600),
            deviceAgent("codebuddy", activeTaskCount = 0, lastSeen = now - 60),
        )
        val decision = CapabilityRouter.route(cards, requirement(), projectHasDeviceLocation = true)
        assertEquals("codebuddy", decision.chosenAgentId)
    }

    @Test
    fun `repo hard filter excludes device without the project checkout`() {
        val cards = listOf(
            deviceAgent("example-agent", hostedProjectIds = setOf("other-project")),
            deviceAgent("codebuddy", hostedProjectIds = setOf("wherevercode-agent")),
        )
        val decision = CapabilityRouter.route(cards, requirement(), projectHasDeviceLocation = true)
        assertEquals("codebuddy", decision.chosenAgentId)
        val grok = decision.candidates.first { it.agentId == "example-agent" }
        assertEquals("仓库不在该设备上", grok.excludedReason)
    }

    @Test
    fun `github-only project excludes all device agents with hint`() {
        val cards = listOf(deviceAgent("example-agent", hostedProjectIds = emptySet()))
        val decision = CapabilityRouter.route(cards, requirement(), projectHasDeviceLocation = false)
        assertNull(decision.chosenAgentId)
        assertTrue(decision.repoHint?.contains("未检出") == true)
    }

    @Test
    fun `never-seen device is excluded`() {
        val cards = listOf(deviceAgent("example-agent", lastSeen = 0))
        val decision = CapabilityRouter.route(cards, requirement(), projectHasDeviceLocation = true)
        assertNull(decision.chosenAgentId)
        assertEquals("设备从未在线", decision.candidates.first().excludedReason)
    }

    @Test
    fun `unpaired device is excluded`() {
        val cards = listOf(deviceAgent("example-agent", pairingConfirmed = false))
        val decision = CapabilityRouter.route(cards, requirement(), projectHasDeviceLocation = true)
        assertNull(decision.chosenAgentId)
        assertEquals("设备未配对确认", decision.candidates.first().excludedReason)
    }

    @Test
    fun `not logged in is excluded but unknown is not`() {
        val cards = listOf(
            deviceAgent("example-agent", loginState = AgentLoginState.NOT_LOGGED_IN),
            deviceAgent("agy", loginState = AgentLoginState.UNKNOWN),
        )
        val decision = CapabilityRouter.route(cards, requirement(), projectHasDeviceLocation = true)
        assertEquals("agy", decision.chosenAgentId)
    }

    @Test
    fun `not dispatchable is excluded`() {
        val cards = listOf(deviceAgent("example-agent", dispatchable = false))
        val decision = CapabilityRouter.route(cards, requirement(), projectHasDeviceLocation = true)
        assertNull(decision.chosenAgentId)
        assertEquals("不可派单（仅对话）", decision.candidates.first().excludedReason)
    }

    @Test
    fun `metered cloud agent excluded unless explicitly allowed`() {
        val cards = listOf(cloudAgent())
        val denied = CapabilityRouter.route(cards, requirement(allowMetered = false))
        assertNull(denied.chosenAgentId)
        assertEquals("计费 Agent，需明确允许", denied.candidates.first().excludedReason)
        val allowed = CapabilityRouter.route(cards, requirement(allowMetered = true))
        assertEquals("manus", allowed.chosenAgentId)
    }

    @Test
    fun `pinned agent wins when it passes gates`() {
        val cards = listOf(
            deviceAgent("example-agent", activeTaskCount = 0, lastSeen = now - 60),
            deviceAgent("codebuddy", activeTaskCount = 0, lastSeen = now - 60),
        )
        val decision = CapabilityRouter.route(
            cards,
            requirement(pinnedAgentId = "example-agent"),
            projectHasDeviceLocation = true,
        )
        assertEquals("example-agent", decision.chosenAgentId)
        assertEquals("用户已指定其他 Agent", decision.candidates.first { it.agentId == "codebuddy" }.excludedReason)
    }

    @Test
    fun `pinned agent failing gates is not silently replaced`() {
        val cards = listOf(deviceAgent("example-agent", lastSeen = 0))
        val decision = CapabilityRouter.route(
            cards,
            requirement(pinnedAgentId = "example-agent"),
            projectHasDeviceLocation = true,
        )
        assertNull(decision.chosenAgentId)
        assertTrue(decision.reason.contains("设备从未在线"))
    }

    @Test
    fun `cooling excludes agent with three consecutive failures`() {
        val cards = listOf(
            deviceAgent("example-agent"),
            deviceAgent("codebuddy"),
        )
        val cooldowns = mapOf(
            "example-agent" to ConsecutiveFailures(3, now - 60),
        )
        val decision = CapabilityRouter.route(cards, requirement(), cooldowns, projectHasDeviceLocation = true)
        assertEquals("codebuddy", decision.chosenAgentId)
        assertTrue(
            decision.candidates.first { it.agentId == "example-agent" }.excludedReason?.contains("冷却") == true,
        )
    }

    @Test
    fun `cooling expires after thirty minutes`() {
        val cards = listOf(deviceAgent("example-agent"))
        val cooldowns = mapOf(
            "example-agent" to ConsecutiveFailures(3, now - 31 * 60),
        )
        val decision = CapabilityRouter.route(cards, requirement(), cooldowns, projectHasDeviceLocation = true)
        assertEquals("example-agent", decision.chosenAgentId)
    }

    @Test
    fun `review prefers a different agent than the coder`() {
        val cards = listOf(
            deviceAgent("example-agent", capabilities = listOf("dev", "review")),
            deviceAgent("codebuddy", capabilities = listOf("dev", "review")),
        )
        val decision = CapabilityRouter.route(
            cards,
            requirement(taskType = TaskType.REVIEW, reviewCoderAgentId = "example-agent"),
            projectHasDeviceLocation = true,
        )
        assertEquals("codebuddy", decision.chosenAgentId)
    }

    @Test
    fun `unauthorized project excludes the agent`() {
        val cards = listOf(
            deviceAgent("example-agent", authorizedProjectIds = listOf("other-project")),
        )
        val decision = CapabilityRouter.route(cards, requirement(), projectHasDeviceLocation = true)
        assertNull(decision.chosenAgentId)
        assertEquals("未授权该项目", decision.candidates.first().excludedReason)
    }

    @Test
    fun `tie breaks by agent id lexicographic`() {
        val cards = listOf(
            deviceAgent("codebuddy"),
            deviceAgent("example-agent"),
        )
        val first = CapabilityRouter.route(cards, requirement(), projectHasDeviceLocation = true)
        val second = CapabilityRouter.route(cards.reversed(), requirement(), projectHasDeviceLocation = true)
        assertEquals("codebuddy", first.chosenAgentId)
        assertEquals("codebuddy", second.chosenAgentId)
    }

    @Test
    fun `success rate never affects routing scores`() {
        // 老项目“记分不参政”回归测试：两张卡只差历史成功率，分数必须相同。
        val base = deviceAgent("example-agent")
        val withRate = base.copy(successRateByTaskType = mapOf("CODE" to 1.0))
        val withoutRate = base.copy(successRateByTaskType = mapOf("CODE" to 0.0))
        val scoredWith = CapabilityRouter.route(listOf(withRate), requirement(), projectHasDeviceLocation = true)
        val scoredWithout = CapabilityRouter.route(listOf(withoutRate), requirement(), projectHasDeviceLocation = true)
        assertEquals(
            scoredWith.candidates.first().score,
            scoredWithout.candidates.first().score,
        )
        assertEquals(
            scoredWith.candidates.first().breakdown,
            scoredWithout.candidates.first().breakdown,
        )
    }

    @Test
    fun `no project means no repo filter`() {
        val cards = listOf(deviceAgent("example-agent", hostedProjectIds = emptySet()))
        val decision = CapabilityRouter.route(cards, requirement(projectId = null))
        assertEquals("example-agent", decision.chosenAgentId)
    }

    @Test
    fun `classify task type heuristic`() {
        assertEquals(TaskType.REVIEW, classifyTaskType("审查这段代码", null))
        assertEquals(TaskType.FIX, classifyTaskType("修复登录闪退", null))
        assertEquals(TaskType.RESEARCH, classifyTaskType("调研一下缓存方案", null))
        assertEquals(TaskType.CODE, classifyTaskType("写一个登录页", null))
        assertEquals(TaskType.FIX, classifyTaskType("帮我看看", "一运行就报错"))
    }

    @Test
    fun `routing decision log round trip with attach`() {
        val log = InMemoryRoutingDecisionLog()
        val decision = CapabilityRouter.route(
            listOf(deviceAgent("example-agent")),
            requirement(),
            projectHasDeviceLocation = true,
        )
        log.record(decision)
        log.attachTask(decision.decisionId, "task-1")
        val found = log.decisionForTask("task-1")
        assertEquals(decision.decisionId, found?.decisionId)
        assertEquals("task-1", found?.assignedTaskId)
        assertNull(log.decisionForTask("task-2"))
    }

    // -- 下面这行只是让 AgentRecord 的构造在测试里被引用一次，
    // 防止将来有人把卡片字段与 AgentRecord 脱钩而不自知。
    @Test
    fun `agent record carries the fields cards are built from`() {
        val record = AgentRecord(
            agentId = "example-agent",
            displayName = "Example Agent",
            kind = AgentKind.DEVICE,
            access = AgentAccess.CLI,
            brainKind = BrainKind.MODEL_API,
            adapterId = "cli",
            binary = "grok",
            capabilities = listOf("dev"),
            deviceId = "remote-host",
            deviceFolder = "/workspace/example-project",
        )
        assertEquals("remote-host", record.deviceId)
        assertEquals(listOf("dev"), record.capabilities)
    }
}
