package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 确定性自动派活——eval 日志 + 记分板单测（2026-11-11）。
 * 纯逻辑，不碰 Android、不碰网络。
 */
class EvalLogTest {

    private fun task(
        taskId: String = "task-1",
        title: String = "写一个登录页",
        description: String? = null,
        assignedAgentId: String? = "example-agent",
        attempt: Int = 1,
    ) = TaskRecord(
        taskId = taskId,
        title = title,
        description = description,
        state = "completed",
        assignedAgentId = assignedAgentId,
        attempt = attempt,
    )

    private fun entry(
        taskId: String,
        agentId: String = "example-agent",
        taskType: TaskType = TaskType.CODE,
        outcome: EvalOutcome = EvalOutcome.COMPLETED,
        finishedAtMs: Long = 2000,
        wallClockMs: Long? = 1000,
        reworkRounds: Int = 0,
        assignedVia: String = "manual",
    ) = EvalLogEntry(
        taskId = taskId,
        taskType = taskType,
        agentId = agentId,
        deviceId = "remote-host",
        mode = "single",
        startedAtMs = 1000,
        finishedAtMs = finishedAtMs,
        wallClockMs = wallClockMs,
        reworkRounds = reworkRounds,
        attempt = reworkRounds + 1,
        outcome = outcome,
        assignedVia = assignedVia,
    )

    @Test
    fun `recorder writes a complete entry for a finished task`() {
        val store = InMemoryEvalLogStore()
        val recorder = EvalLogRecorder(store)
        val recorded = recorder.recordTerminal(
            task(),
            EvalOutcome.COMPLETED,
            startedAtMs = 1000,
            deviceId = "remote-host",
        )
        assertEquals("task-1", recorded?.taskId)
        assertEquals(TaskType.CODE, recorded?.taskType)
        assertEquals("example-agent", recorded?.agentId)
        assertEquals("remote-host", recorded?.deviceId)
        assertEquals("single", recorded?.mode)
        // recorder 自身默认 fallback 是 "manual"；"local" 是 LocalTaskRunner
        // 调用点显式传的 assignedViaFallback，不是 recorder 的默认值。
        assertEquals("manual", recorded?.assignedVia)
        assertEquals(0, recorded?.reworkRounds)
        assertEquals(1, store.listEntries().size)
    }

    @Test
    fun `rework rounds are attempt minus one`() {
        val store = InMemoryEvalLogStore()
        val recorder = EvalLogRecorder(store)
        val recorded = recorder.recordTerminal(task(attempt = 3), EvalOutcome.COMPLETED)
        assertEquals(2, recorded?.reworkRounds)
    }

    @Test
    fun `suggested assignment is detected from the routing log`() {
        val routingLog = InMemoryRoutingDecisionLog()
        val store = InMemoryEvalLogStore()
        val recorder = EvalLogRecorder(store, routingLog)
        val decision = CapabilityRouter.route(
            listOf(
                CapabilityCard(
                    agentId = "example-agent", displayName = "Example Agent", deviceId = "remote-host",
                    deviceDisplayName = "远程设备", kind = wherevercode.agent.core.agent.AgentKind.DEVICE,
                    cliBinary = "grok", modelOrMembership = "grok", tools = listOf("dev"),
                    loginState = wherevercode.agent.core.agent.AgentLoginState.LOGGED_IN,
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
        routingLog.record(decision)
        routingLog.attachTask(decision.decisionId, "task-1")
        val recorded = recorder.recordTerminal(task(), EvalOutcome.COMPLETED)
        assertEquals("suggested", recorded?.assignedVia)
        assertEquals(decision.decisionId, recorded?.routingDecisionId)
    }

    @Test
    fun `manual override keeps the decision id but marks manual`() {
        val routingLog = InMemoryRoutingDecisionLog()
        val store = InMemoryEvalLogStore()
        val recorder = EvalLogRecorder(store, routingLog)
        val decision = CapabilityRouter.route(
            listOf(
                CapabilityCard(
                    agentId = "example-agent", displayName = "Example Agent", deviceId = "remote-host",
                    deviceDisplayName = "远程设备", kind = wherevercode.agent.core.agent.AgentKind.DEVICE,
                    cliBinary = "grok", modelOrMembership = "grok", tools = listOf("dev"),
                    loginState = wherevercode.agent.core.agent.AgentLoginState.LOGGED_IN,
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
        routingLog.record(decision)
        // 决策建议 example-agent，但任务实际派给了 codebuddy（用户改派）。
        routingLog.attachTask(decision.decisionId, "task-9")
        val recorded = recorder.recordTerminal(
            task(taskId = "task-9", assignedAgentId = "codebuddy"),
            EvalOutcome.COMPLETED,
        )
        assertEquals("manual", recorded?.assignedVia)
        assertEquals(decision.decisionId, recorded?.routingDecisionId)
    }

    @Test
    fun `scorecard aggregates per agent and task type`() {
        val entries = listOf(
            entry("t1", outcome = EvalOutcome.COMPLETED, wallClockMs = 1000),
            entry("t2", outcome = EvalOutcome.FAILED, wallClockMs = 2000, reworkRounds = 1),
            entry("t3", outcome = EvalOutcome.COMPLETED, wallClockMs = 3000),
            entry("t4", taskType = TaskType.REVIEW, outcome = EvalOutcome.COMPLETED),
        )
        val summary = EvalScorecard.summarize(entries)["example-agent"]!!
        val code = summary[TaskType.CODE]!!
        assertEquals(3, code.total)
        assertEquals(2, code.completed)
        assertEquals(1, code.failed)
        assertEquals(2.0 / 3, code.successRate!!, 0.001)
        assertEquals(2000L, code.avgWallClockMs)
        assertEquals(1.0 / 3, code.avgReworkRounds, 0.001)
        assertEquals(1.0, summary[TaskType.REVIEW]!!.successRate!!, 0.001)
    }

    @Test
    fun `scorecard skips missing wall clock instead of inventing one`() {
        val entries = listOf(entry("t1", wallClockMs = null))
        val summary = EvalScorecard.summarize(entries)["example-agent"]!![TaskType.CODE]!!
        assertNull(summary.avgWallClockMs)
        assertEquals(1.0, summary.successRate!!, 0.001)
    }

    @Test
    fun `consecutive failures count trailing failures until a success`() {
        val entries = listOf(
            entry("t1", outcome = EvalOutcome.COMPLETED, finishedAtMs = 1000),
            entry("t2", outcome = EvalOutcome.FAILED, finishedAtMs = 2000),
            entry("t3", outcome = EvalOutcome.FAILED, finishedAtMs = 3000),
            entry("t4", outcome = EvalOutcome.CANCELLED, finishedAtMs = 4000),
            entry("t5", outcome = EvalOutcome.FAILED, finishedAtMs = 5000),
        )
        val failures = EvalScorecard.consecutiveFailures(entries, 1_700_000_000)
        assertEquals(3, failures["example-agent"]?.count)
        assertEquals(5L, failures["example-agent"]?.lastFailureAtEpochSeconds)
    }

    @Test
    fun `consecutive failures reset after a success`() {
        val entries = listOf(
            entry("t1", outcome = EvalOutcome.FAILED, finishedAtMs = 1000),
            entry("t2", outcome = EvalOutcome.FAILED, finishedAtMs = 2000),
            entry("t3", outcome = EvalOutcome.COMPLETED, finishedAtMs = 3000),
        )
        val failures = EvalScorecard.consecutiveFailures(entries, 1_700_000_000)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `verdict updates the entry without touching other fields`() {
        val store = InMemoryEvalLogStore()
        store.record(entry("t1"))
        store.recordVerdict("t1", UserVerdict.APPROVED)
        val updated = store.listEntries().first()
        assertEquals(UserVerdict.APPROVED, updated.userVerdict)
        assertEquals(EvalOutcome.COMPLETED, updated.outcome)
    }

    @Test
    fun `eval json round trip preserves fields`() {
        val original = entry("t1", assignedVia = "suggested").copy(
            routingDecisionId = "d-1",
            userVerdict = UserVerdict.CHANGES_REQUESTED,
        )
        val parsed = EvalLog.parseEntry(EvalLog.toJson(original))
        assertEquals(original, parsed)
    }

    @Test
    fun `in memory store caps at two hundred entries`() {
        val store = InMemoryEvalLogStore()
        for (i in 0 until 210) {
            store.record(entry("t$i", finishedAtMs = 1000L + i))
        }
        val entries = store.listEntries()
        assertEquals(200, entries.size)
        assertEquals("t10", entries.first().taskId)
        assertEquals("t209", entries.last().taskId)
    }
}
