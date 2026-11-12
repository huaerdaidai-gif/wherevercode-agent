package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 端侧小模型路由 v1——分流记录进路由决策日志的往返单测（2026-11-11）。
 * Robolectric：ApplicationProvider 的 Context 是真实的
 * SharedPreferences 实现，纯 JVM 单测里 android.content 不可用。
 * 覆盖 ChatViewModel.maybeRunTriage 的记日志路径：
 * record(decision.copy(triage = …)) 按 decisionId 去重覆盖，
 * 旧条目（无 triage 键）读回为 null。
 */
@RunWith(RobolectricTestRunner::class)
class RoutingDecisionTriageTest {

    private fun context() = RuntimeEnvironment.getApplication().applicationContext

    private fun decision(
        decisionId: String,
        triage: TriageRecord? = null,
    ) = RoutingDecision(
        decisionId = decisionId,
        createdAtEpochSeconds = 1000,
        projectId = null,
        taskType = TaskType.CODE,
        pinnedAgentId = null,
        candidates = listOf(
            CandidateScore(agentId = "codex", score = 90, breakdown = mapOf("在线" to 30)),
        ),
        chosenAgentId = "codex",
        reason = "综合评分最高",
        triage = triage,
    )

    @Test
    fun `routing decision triage round trip`() {
        val log = SharedPreferencesRoutingDecisionLog(context())
        val triage = TriageRecord(
            suggestedMode = TriageMode.PIPELINE,
            reason = "多模块重构",
            modelLabel = "zhipu · glm-4.7-flash",
            latencyMs = 1234,
        )
        log.record(decision("d1", triage))
        val back = log.listDecisions().first { it.decisionId == "d1" }
        assertEquals(triage, back.triage)
        assertEquals("codex", back.chosenAgentId)
    }

    @Test
    fun `old decision without triage reads back as null`() {
        val log = SharedPreferencesRoutingDecisionLog(context())
        log.record(decision("d2"))
        val back = log.listDecisions().first { it.decisionId == "d2" }
        assertNull(back.triage)
    }

    @Test
    fun `re-recording same decision id updates triage in place`() {
        val log = SharedPreferencesRoutingDecisionLog(context())
        log.record(decision("d3"))
        val triage = TriageRecord(
            suggestedMode = TriageMode.SINGLE,
            reason = "单文件小改",
            modelLabel = "zhipu · glm-4.7-flash",
            latencyMs = 456,
        )
        log.record(decision("d3", triage))
        val decisions = log.listDecisions().filter { it.decisionId == "d3" }
        assertEquals(1, decisions.size)
        assertEquals(triage, decisions.first().triage)
    }
}
