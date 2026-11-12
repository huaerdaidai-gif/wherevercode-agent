package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.task.TaskStepStatus

/**
 * 成果聚合纯逻辑（V5 C2 批 3，第 2 步；纯 JVM JUnit 4，无 Robolectric）：
 * 按任务分组、路径去重、空态文案。
 *
 * Fixture 风格复用 [TaskDetailArtifactsTest]：[TaskRecord] + [TaskStep]
 * 直接构造假数据，不读任何真实任务库。
 * 不写“行数统计”测试——无此数据（勘查 §3.3 G3-1）。
 */
class ResultsAggregationTest {

    private fun task(taskId: String, title: String, updatedAt: Long) =
        TaskRecord(taskId = taskId, title = title, state = "done", updatedAt = updatedAt)

    private fun writeStep(taskId: String, argsSummary: String, toolName: String = "write_file") =
        TaskStep(
            stepId = "s-$taskId-$argsSummary",
            taskId = taskId,
            toolCallId = "c1",
            toolName = toolName,
            argsSummary = argsSummary,
            status = TaskStepStatus.SUCCESS,
        )

    @Test
    fun `groups artifacts by task ordered by updatedAt desc`() {
        val tasks = listOf(
            task("t1", "算数", 100),
            task("t2", "报告", 300),
        )
        val steps = mapOf(
            "t1" to listOf(writeStep("t1", "path=calc.py, content=x")),
            "t2" to listOf(writeStep("t2", "path=report.md, content=y")),
        )
        val summary = ResultsAggregation.aggregate(tasks, steps)
        assertEquals(2, summary.taskCount)
        assertEquals(2, summary.tasksWithArtifacts)
        assertEquals(2, summary.distinctArtifactCount)
        // 任务列表页同款：updatedAt 倒序。
        assertEquals(listOf("t2", "t1"), summary.tasks.map { it.task.taskId })
        assertEquals(listOf("report.md"), summary.tasks[0].artifactPaths)
        assertEquals(listOf("calc.py"), summary.tasks[1].artifactPaths)
    }

    @Test
    fun `dedupes the same path written by several tasks`() {
        val tasks = listOf(
            task("t1", "第一版", 100),
            task("t2", "第二版", 200),
        )
        val steps = mapOf(
            "t1" to listOf(writeStep("t1", "path=shared.py, content=a")),
            "t2" to listOf(
                writeStep("t2", "path=shared.py, content=b"),
                writeStep("t2", "path=extra.py, content=c"),
            ),
        )
        val summary = ResultsAggregation.aggregate(tasks, steps)
        assertEquals(2, summary.distinctArtifactCount)
        assertEquals(listOf("shared.py", "extra.py"), summary.artifactsByPath.map { it.path })
        // 同一路径被两个任务写过：去重计数只算一次，但两个任务都列出。
        assertEquals(listOf("第二版", "第一版"), summary.artifactsByPath[0].taskTitles)
        assertEquals(listOf("第二版"), summary.artifactsByPath[1].taskTitles)
    }

    @Test
    fun `dedupes repeated writes inside one task`() {
        val tasks = listOf(task("t1", "反复写", 100))
        val steps = mapOf(
            "t1" to listOf(
                writeStep("t1", "path=main.py, content=a"),
                writeStep("t1", "path=main.py, content=b"),
            ),
        )
        val summary = ResultsAggregation.aggregate(tasks, steps)
        assertEquals(1, summary.distinctArtifactCount)
        assertEquals(listOf("main.py"), summary.tasks[0].artifactPaths)
    }

    @Test
    fun `empty task list has honest empty message`() {
        val summary = ResultsAggregation.aggregate(emptyList(), emptyMap())
        assertEquals(0, summary.taskCount)
        assertEquals(0, summary.totalTaskCount)
        assertEquals(0, summary.tasksWithArtifacts)
        assertEquals(0, summary.distinctArtifactCount)
        val message = ResultsAggregation.emptyMessage(
            summary.totalTaskCount,
            summary.tasksWithArtifacts,
        )
        assertTrue(message != null && message.contains("任务库为空"))
    }

    @Test
    fun `tasks without write_file steps have honest empty message`() {
        val tasks = listOf(task("t1", "只跑命令", 100))
        val steps = mapOf(
            "t1" to listOf(
                writeStep("t1", "command=python3 calc.py", toolName = "run_command"),
            ),
        )
        val summary = ResultsAggregation.aggregate(tasks, steps)
        // V5 C3 chat-bugfix（页头口径）：无产物任务不进成果列表。
        assertEquals(0, summary.taskCount)
        assertEquals(1, summary.totalTaskCount)
        assertEquals(0, summary.tasksWithArtifacts)
        assertEquals(0, summary.distinctArtifactCount)
        assertTrue(summary.tasks.isEmpty())
        val message = ResultsAggregation.emptyMessage(
            summary.totalTaskCount,
            summary.tasksWithArtifacts,
        )
        assertTrue(message != null && message.contains("没有写文件步骤"))
    }

    @Test
    fun `result text paths are not counted as artifacts`() {
        // TaskRecord.result 是自由文本，不可解析；聚合只认步骤的 path=。
        val tasks = listOf(
            task("t1", "口头成果", 100),
        )
        val steps = mapOf(
            "t1" to listOf(
                TaskStep(
                    stepId = "s1",
                    taskId = "t1",
                    toolCallId = "c1",
                    toolName = "write_file",
                    argsSummary = "content=x",
                    status = TaskStepStatus.SUCCESS,
                    resultSummary = "已写入 path=out/report.md",
                ),
            ),
        )
        val summary = ResultsAggregation.aggregate(tasks, steps)
        assertEquals(0, summary.distinctArtifactCount)
        assertEquals(0, summary.tasksWithArtifacts)
    }

    @Test
    fun `no empty message when artifacts exist`() {
        val tasks = listOf(task("t1", "有产物", 100))
        val steps = mapOf(
            "t1" to listOf(writeStep("t1", "path=a.py, content=x")),
        )
        val summary = ResultsAggregation.aggregate(tasks, steps)
        assertNull(
            ResultsAggregation.emptyMessage(
                summary.totalTaskCount,
                summary.tasksWithArtifacts,
            ),
        )
    }

    @Test
    fun `task without steps is excluded from the by-task list`() {
        // V5 C3 chat-bugfix（页头口径）：无产物任务不进成果列表。
        val tasks = listOf(
            task("t1", "有产物", 200),
            task("t2", "无步骤", 100),
        )
        val steps = mapOf(
            "t1" to listOf(writeStep("t1", "path=a.py, content=x")),
        )
        val summary = ResultsAggregation.aggregate(tasks, steps)
        assertEquals(1, summary.tasks.size)
        assertEquals(1, summary.tasksWithArtifacts)
        assertEquals(2, summary.totalTaskCount)
        assertEquals("t1", summary.tasks[0].task.taskId)
    }
}
