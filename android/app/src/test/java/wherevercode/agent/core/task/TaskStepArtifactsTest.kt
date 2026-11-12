package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 步骤 → 产物路径（P2 自动执行，2026-11-11）：只认 write_file 步骤的
 * path= 参数；其余步骤不算产物，不编文件。
 */
@RunWith(RobolectricTestRunner::class)
class TaskStepArtifactsTest {
    private fun step(tool: String, args: String) = TaskStep(
        stepId = "s-${tool}-${args.hashCode()}",
        taskId = "t1",
        toolCallId = "c1",
        toolName = tool,
        argsSummary = args,
    )

    @Test
    fun extractPathsOnlyWriteFile() {
        val steps = listOf(
            step("write_file", "path=calc.py, content=..."),
            step("run_command", "command=pytest, path=calc.py"),
            step("write_file", "path=calc_test.py, content=..."),
            step("write_file", "path=calc.py, content=..."),
        )
        assertEquals(listOf("calc.py", "calc_test.py"), TaskStepArtifacts.extractPaths(steps))
    }

    @Test
    fun extractPathsEmptyWithoutWriteFile() {
        assertEquals(emptyList<String>(), TaskStepArtifacts.extractPaths(listOf(step("run_command", "command=ls"))))
        assertEquals(emptyList<String>(), TaskStepArtifacts.extractFromText("no path here"))
    }
}
