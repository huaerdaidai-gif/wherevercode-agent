package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.task.TaskStepStatus

/**
 * 失败测试（maintainer 2026-11-10 07:49 截图复现）：聊天轮次以任务形态泄漏进成果页。
 *
 * 截图口径：任务 32 个、有产物任务 2 个、产物文件 2 个；列表却把 32 个任务全列出来，
 * 其中 30 个是聊天轮次变的任务，每条“本任务没有写文件步骤，未产生产物文件。”
 * 成果页头写明“只列写文件步骤产出的文件路径”——没有产物的任务不该进成果。
 * 纯 JVM JUnit 4，无 Robolectric；全部假数据，零真实调用。
 */
class ResultsAggregationChatLeakTest {

    private fun chatTask(taskId: String, title: String, updatedAt: Long) =
        TaskRecord(taskId = taskId, title = title, state = "completed", updatedAt = updatedAt)

    private fun writeStep(taskId: String, argsSummary: String) =
        TaskStep(
            stepId = "s-$taskId",
            taskId = taskId,
            toolCallId = "c1",
            toolName = "write_file",
            argsSummary = argsSummary,
            status = TaskStepStatus.SUCCESS,
        )

    @Test
    fun `screenshot 30 chat tasks plus 2 artifact tasks lists only the 2 artifact tasks`() {
        val tasks = mutableListOf<TaskRecord>()
        val steps = mutableMapOf<String, List<TaskStep>>()
        // 30 个聊天轮次变的任务：无任何写文件步骤（截图里“本任务没有写文件步骤”）。
        val chatTitles = listOf(
            "让你做一个包含加减乘除的计算器小程序",
            "需要提供给你什么",
            "让你做一个包含加减乘除的计算机app",
            "需要提供给你什么工具",
            "你不能探测所处的工作环境吗",
            "工作区缺少什么工具",
            "开发一个计算器app，包含加减乘除",
        )
        for (i in 0 until 30) {
            val id = "chat-task-$i"
            tasks += chatTask(id, chatTitles[i % chatTitles.size], 100L + i)
            steps[id] = emptyList()
        }
        // 2 个真正产出文件的任务。
        tasks += chatTask("real-1", "写计算器代码", 1000L)
        steps["real-1"] = listOf(writeStep("real-1", "path=calc.py, content=x"))
        tasks += chatTask("real-2", "写说明文档", 1001L)
        steps["real-2"] = listOf(writeStep("real-2", "path=README.md, content=y"))

        val summary = ResultsAggregation.aggregate(tasks, steps)

        // 按页头口径：成果只含真正产出文件的任务。
        assertEquals(2, summary.tasks.size)
        assertEquals(listOf("real-2", "real-1"), summary.tasks.map { it.task.taskId })
        assertEquals(2, summary.taskCount)
        assertEquals(2, summary.tasksWithArtifacts)
        assertEquals(2, summary.distinctArtifactCount)
    }
}
