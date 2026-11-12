package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P2 plan artifact（2026-11-11）：结构化 plan 的校验与 JSON 往返。
 *
 * Robolectric：PlanArtifact 用 org.json（Android 框架类），本地 JVM
 * 单元测试里是桩，必须走 Robolectric（与 TaskPacketTest 同因）。
 */
@RunWith(RobolectricTestRunner::class)
class PipelinePlanTest {

    private fun goodPlan() = PlanArtifact(
        objective = "给计算器加减法",
        filesInScope = listOf("calculator.py", "test_calculator.py"),
        steps = listOf("实现 add", "补单测"),
        acceptanceCriteria = listOf("add(1, 2) == 3", "单测全过"),
        testCommands = listOf("pytest test_calculator.py"),
    )

    @Test
    fun `valid plan passes validation`() {
        assertTrue(goodPlan().validate().isEmpty())
    }

    @Test
    fun `plan without objective steps or criteria is rejected with reasons`() {
        val problems = PlanArtifact(objective = "  ", steps = emptyList(), acceptanceCriteria = emptyList()).validate()
        assertEquals(3, problems.size)
        assertTrue(problems.any { it.contains("目标") })
        assertTrue(problems.any { it.contains("步骤") })
        assertTrue(problems.any { it.contains("验收标准") })
    }

    @Test
    fun `plan json round trip keeps every field`() {
        val plan = goodPlan().copy(expectedOutput = "加减法可用", plannerId = "claude-mac")
        val restored = PlanArtifact.fromJson(plan.toJson())
        assertEquals(plan.objective, restored.objective)
        assertEquals(plan.filesInScope, restored.filesInScope)
        assertEquals(plan.steps, restored.steps)
        assertEquals(plan.acceptanceCriteria, restored.acceptanceCriteria)
        assertEquals(plan.testCommands, restored.testCommands)
        assertEquals("加减法可用", restored.expectedOutput)
        assertEquals("claude-mac", restored.plannerId)
    }

    @Test
    fun `unknown plan version is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlanArtifact.fromJson("""{"version": 99, "objective": "x"}""")
        }
    }

    @Test
    fun `fromLines trims blanks and drops empty lines`() {
        val plan = PlanArtifact.fromLines(
            objective = "  做加法  ",
            steps = listOf("实现 add", "  ", ""),
            acceptanceCriteria = listOf("add(1, 2) == 3"),
            testCommands = listOf("", "pytest"),
            filesInScope = listOf("calculator.py", ""),
        )
        assertEquals("做加法", plan.objective)
        assertEquals(listOf("实现 add"), plan.steps)
        assertEquals(listOf("pytest"), plan.testCommands)
        assertEquals(listOf("calculator.py"), plan.filesInScope)
        assertTrue(plan.validate().isEmpty())
    }

    @Test
    fun `withPlan fills packet acceptance criteria and file scope from approved plan`() {
        val task = TaskRecord(taskId = "t1", title = "加法", description = "做加法", state = "queued")
        val packet = TaskPacket.fromTask(task).withPlan(goodPlan())
        assertEquals(listOf("add(1, 2) == 3", "单测全过"), packet.acceptanceCriteria)
        assertEquals(listOf("calculator.py", "test_calculator.py"), packet.filesInScope)
        assertTrue(packet.toPromptText().contains("add(1, 2) == 3"))
        assertTrue(packet.toPromptText().contains("calculator.py"))
    }

    @Test
    fun `withPlan keeps packet values when plan leaves them empty`() {
        val task = TaskRecord(taskId = "t1", title = "加法", description = "做加法", state = "queued")
        val plan = PlanArtifact(objective = "做加法", steps = listOf("做"), acceptanceCriteria = listOf("能用"))
        val packet = TaskPacket.fromTask(task).withPlan(plan)
        assertEquals(listOf("能用"), packet.acceptanceCriteria)
        assertTrue(packet.filesInScope.isEmpty())
    }

    @Test
    fun `mechanical result json round trip`() {
        val result = MechanicalCheckResult(
            checks = listOf(
                MechanicalCheck("文件范围", MechanicalOutcome.PASSED, "全部在清单内"),
                MechanicalCheck("构建/lint", MechanicalOutcome.NOT_RUN, "本机无执行器"),
            ),
        )
        val restored = MechanicalCheckResult.fromJson(result.toJson())
        assertTrue(restored.passed)
        assertEquals(2, restored.checks.size)
        assertEquals(1, restored.notRun.size)
    }
}
