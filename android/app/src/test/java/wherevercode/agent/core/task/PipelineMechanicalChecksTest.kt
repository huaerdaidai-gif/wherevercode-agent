package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P2 机械检查（2026-11-11）：先于 LLM 审查，全是代码、零 token。
 *
 * Robolectric：MechanicalCheckResult 的 JSON 往返用 org.json。
 * 覆盖：范围通过 / 越界 FAILED / 无清单 NOT_RUN；测试 PASSED /
 * FAILED / plan 要求测试但未跑 FAILED / plan 无测试命令 NOT_RUN；
 * 构建/lint 恒 NOT_RUN（本机无执行器，不谎报通过）。
 */
@RunWith(RobolectricTestRunner::class)
class PipelineMechanicalChecksTest {

    private fun plan(
        filesInScope: List<String> = listOf("a.py", "b.py"),
        filesOutOfScope: List<String> = emptyList(),
        testCommands: List<String> = emptyList(),
    ) = PlanArtifact(
        objective = "改 a",
        filesInScope = filesInScope,
        filesOutOfScope = filesOutOfScope,
        steps = listOf("改"),
        acceptanceCriteria = listOf("能用"),
        testCommands = testCommands,
    )

    @Test
    fun `in-scope changes and passed tests pass`() {
        val result = PipelineMechanicalChecks.run(
            plan(testCommands = listOf("pytest")),
            changedFiles = listOf("a.py"),
            testVerdict = PipelineTestVerdict.PASSED,
        )
        assertTrue(result.passed)
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `out-of-scope file fails the scope check`() {
        val result = PipelineMechanicalChecks.run(
            plan(),
            changedFiles = listOf("a.py", "secret.py"),
            testVerdict = PipelineTestVerdict.PASSED,
        )
        assertFalse(result.passed)
        assertEquals(1, result.failed.size)
        assertTrue(result.failed[0].detail.contains("secret.py"))
    }

    @Test
    fun `touching a forbidden file fails even when in scope`() {
        val result = PipelineMechanicalChecks.run(
            plan(filesInScope = listOf("a.py", "keys.py"), filesOutOfScope = listOf("keys.py")),
            changedFiles = listOf("keys.py"),
            testVerdict = PipelineTestVerdict.PASSED,
        )
        assertFalse(result.passed)
        assertTrue(result.failed.any { it.name == "禁改文件" })
    }

    @Test
    fun `failed tests fail the gate`() {
        val result = PipelineMechanicalChecks.run(
            plan(),
            changedFiles = listOf("a.py"),
            testVerdict = PipelineTestVerdict.FAILED,
        )
        assertFalse(result.passed)
        assertTrue(result.failed.any { it.name == "测试" })
    }

    @Test
    fun `plan requires tests but none ran fails`() {
        val result = PipelineMechanicalChecks.run(
            plan(testCommands = listOf("pytest")),
            changedFiles = listOf("a.py"),
            testVerdict = PipelineTestVerdict.NOT_RUN,
        )
        assertFalse(result.passed)
        assertTrue(result.failed.any { it.name == "测试" })
    }

    @Test
    fun `no test commands and no test run is NOT_RUN not a failure`() {
        val result = PipelineMechanicalChecks.run(
            plan(),
            changedFiles = listOf("a.py"),
            testVerdict = PipelineTestVerdict.NOT_RUN,
        )
        assertTrue(result.passed)
        assertTrue(result.notRun.any { it.name == "测试" })
    }

    @Test
    fun `no changed files list means scope check is NOT_RUN`() {
        val result = PipelineMechanicalChecks.run(
            plan(),
            changedFiles = emptyList(),
            testVerdict = PipelineTestVerdict.PASSED,
        )
        assertTrue(result.passed)
        assertTrue(result.notRun.any { it.name == "文件范围" })
    }

    @Test
    fun `build lint is always NOT_RUN never a fake pass`() {
        val result = PipelineMechanicalChecks.run(
            plan(),
            changedFiles = listOf("a.py"),
            testVerdict = PipelineTestVerdict.PASSED,
        )
        val build = result.checks.single { it.name == "构建/lint" }
        assertEquals(MechanicalOutcome.NOT_RUN, build.outcome)
        assertTrue(build.detail.contains("无构建/lint 执行器"))
    }

    @Test
    fun `unscoped plan passes scope check`() {
        val result = PipelineMechanicalChecks.run(
            plan(filesInScope = emptyList()),
            changedFiles = listOf("anything.py"),
            testVerdict = PipelineTestVerdict.PASSED,
        )
        assertTrue(result.passed)
    }
}
