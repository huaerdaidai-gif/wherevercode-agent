package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.ApprovalOutcome
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.task.TaskStepStatus

/**
 * 任务详情页纯逻辑（阶段 3，U3/批 3a）：
 * 产物路径提取 + 审批结果文案。
 *
 * 回归覆盖（maintainer 12:53 / 13:35）：渲染形状复用老项目
 * renderExecutionTaskFiles / artifact_diff 的约定；提取规则本身
 * 是新写 Kotlin，针对 TurnTaskRecorder.formatArgs 的
 * `path=..., ...` 写法断言。
 */
class TaskDetailArtifactsTest {

    private fun writeStep(
        argsSummary: String,
        resultSummary: String? = null,
        toolName: String = "write_file",
    ) = TaskStep(
        stepId = "s1",
        taskId = "t1",
        toolCallId = "c1",
        toolName = toolName,
        argsSummary = argsSummary,
        status = TaskStepStatus.SUCCESS,
        resultSummary = resultSummary,
    )

    @Test
    fun `extracts path from write_file args`() {
        val paths = TaskDetailArtifacts.extractPaths(
            listOf(writeStep("path=calc.py, content=print(1)")),
        )
        assertEquals(listOf("calc.py"), paths)
    }

    @Test
    fun `extracts nested paths and dedupes`() {
        val steps = listOf(
            writeStep("path=src/main.py, content=x"),
            writeStep("path=src/util.py, content=y"),
            writeStep("path=src/main.py, content=z"),
        )
        assertEquals(listOf("src/main.py", "src/util.py"), TaskDetailArtifacts.extractPaths(steps))
    }

    @Test
    fun `ignores non write_file steps`() {
        val steps = listOf(
            writeStep("path=calc.py, content=x"),
            writeStep("command=python3 calc.py", toolName = "run_command"),
            writeStep("path=README.txt", toolName = "read_file"),
        )
        assertEquals(listOf("calc.py"), TaskDetailArtifacts.extractPaths(steps))
    }

    @Test
    fun `empty steps and missing path give empty list`() {
        assertTrue(TaskDetailArtifacts.extractPaths(emptyList()).isEmpty())
        assertTrue(
            TaskDetailArtifacts.extractPaths(listOf(writeStep("content=x"))).isEmpty(),
        )
    }

    @Test
    fun `paths with spaces survive until the comma separator`() {
        val paths = TaskDetailArtifacts.extractPaths(
            listOf(writeStep("path=my notes/todo list.txt, content=x")),
        )
        assertEquals(listOf("my notes/todo list.txt"), paths)
    }

    @Test
    fun `result summary paths are NOT picked up args only`() {
        // 保守规则：只从结构化的 argsSummary 取 path=；结果文字是
        // 自由文本（"已写入 …" 之类），从中猜路径会编造产物。
        val paths = TaskDetailArtifacts.extractPaths(
            listOf(writeStep("content=x", resultSummary = "已写入 path=out/report.md")),
        )
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `extractFromText finds every path arg`() {
        assertEquals(
            listOf("a.py", "b.py"),
            TaskDetailArtifacts.extractFromText("path=a.py, other=1, path=b.py"),
        )
        assertTrue(TaskDetailArtifacts.extractFromText("no path here").isEmpty())
    }

    @Test
    fun `approval outcome labels distinguish timeout from user denial`() {
        assertEquals("已批准", approvalOutcomeLabel(ApprovalOutcome.APPROVED, false))
        assertEquals("用户拒绝", approvalOutcomeLabel(ApprovalOutcome.DENIED_USER, false))
        assertEquals(
            "超时拒绝（60 秒内未决定）",
            approvalOutcomeLabel(ApprovalOutcome.DENIED_TIMEOUT, false),
        )
        assertEquals(
            "未决定（无审批界面/已关闭）",
            approvalOutcomeLabel(ApprovalOutcome.DENIED_UNAVAILABLE, false),
        )
        assertTrue(
            approvalOutcomeLabel(ApprovalOutcome.APPROVED, true).contains("沿用已批准的决定"),
        )
    }
}
