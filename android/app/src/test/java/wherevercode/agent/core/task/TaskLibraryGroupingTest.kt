package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务库展示层归并（2026-11-11，任务 692638e2 教训）。
 */
class TaskLibraryGroupingTest {
    private val execId = "692638e2-a118-439f-888d-01905db57aed"

    private fun execTask(updatedAt: Long = 1_700_000_100) = TaskRecord(
        taskId = execId,
        title = "wherevercode-agent：新建 test/team-check-20261111 分支并提交测试文件（不开 PR、不合并）",
        description = "在 wherevercode-agent 仓库新建分支 test/team-check-20261111，新建文件 team-check-20261111.txt",
        state = "failed",
        assignedAgentId = "codex",
        createdAt = 1_700_000_000,
        updatedAt = updatedAt,
        version = 3,
    )

    private fun turnTask(updatedAt: Long = 1_700_000_050) = TaskRecord(
        taskId = "turn-1",
        sessionId = "session-1",
        title = "把这个任务派给 Mac 上的 Codex：在 wherevercode-agen",
        description = "把这个任务派给 Mac 上的 Codex：在 wherevercode-agent 仓库新建分支 test/team-check-20261111",
        state = "completed",
        result = "结果：任务已成功派给 Mac 上的 Codex，进入待认领队列：\n\n- **任务 ID**：692638e2-a118-439f-888d-01905db57aed\n- **指派对象**：codex",
        createdAt = 1_699_999_900,
        updatedAt = updatedAt,
        version = 2,
    )

    @Test
    fun `real 692638e2 pair merges into one group`() {
        val groups = TaskLibraryGrouping.groupLibraryTasks(listOf(execTask(), turnTask()))
        assertEquals(1, groups.size)
        assertEquals(execId, groups[0].primary.taskId)
        assertEquals("turn-1", groups[0].dispatchTurn?.taskId)
        assertTrue(groups[0].retries.isEmpty())
    }

    @Test
    fun `retry child attaches under the original task`() {
        val original = execTask()
        val retry = TaskRecord(
            taskId = "retry-1",
            title = original.title,
            description = original.description,
            state = "queued",
            assignedAgentId = "codex",
            parentTaskId = execId,
            createdAt = 1_700_000_200,
            updatedAt = 1_700_000_200,
            version = 1,
        )
        val groups = TaskLibraryGrouping.groupLibraryTasks(listOf(original, retry))
        assertEquals(1, groups.size)
        assertEquals(execId, groups[0].primary.taskId)
        assertNull(groups[0].dispatchTurn)
        assertEquals(listOf("retry-1"), groups[0].retries.map { it.taskId })
    }

    @Test
    fun `orphan turn and orphan retry each form their own group`() {
        val groups = TaskLibraryGrouping.groupLibraryTasks(
            listOf(
                turnTask().copy(
                    result = "结果：任务已成功派给 Mac 上的 Codex：\n\n- **任务 ID**：11111111-2222-3333-4444-555555555555",
                ),
                TaskRecord(
                    taskId = "retry-orphan",
                    title = "x",
                    state = "queued",
                    parentTaskId = "missing-parent",
                    createdAt = 1_700_000_300,
                    updatedAt = 1_700_000_300,
                    version = 1,
                ),
            ),
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun `group order matches the old list order by primary updatedAt desc`() {
        val older = execTask(updatedAt = 1_700_000_100)
        val newer = TaskRecord(
            taskId = "other-1",
            title = "另一任务",
            state = "completed",
            createdAt = 1_700_000_150,
            updatedAt = 1_700_000_500,
            version = 1,
        )
        val groups = TaskLibraryGrouping.groupLibraryTasks(listOf(older, turnTask(), newer))
        assertEquals(listOf("other-1", execId), groups.map { it.primary.taskId })
    }

    @Test
    fun `legacy retry without parent id or uuid merges by identical text and terminal time`() {
        // 2026-11-11 22:46 真机行：旧代码造的重试副本既无 parentTaskId，
        // result 里也没有指向原任务的 UUID，只有 title/description 与
        // 原任务完全相同、创建时间晚于原任务落终态。
        val original = execTask().copy(
            state = "failed",
            createdAt = 1_700_000_000,
            updatedAt = 1_700_001_200,
            finishedAt = 1_700_001_200,
        )
        val legacyRetry = TaskRecord(
            taskId = "legacy-retry-1",
            title = original.title,
            description = original.description,
            state = "completed",
            result = "未能完成操作：当前上下文没有可用的 Git 仓库连接。",
            createdAt = 1_700_002_760,
            updatedAt = 1_700_002_760,
            version = 1,
        )
        val groups = TaskLibraryGrouping.groupLibraryTasks(listOf(original, legacyRetry))
        assertEquals(1, groups.size)
        assertEquals(execId, groups[0].primary.taskId)
        assertEquals(listOf("legacy-retry-1"), groups[0].retries.map { it.taskId })
    }

    @Test
    fun `identical text does not merge when the earlier row is not terminal`() {
        val earlier = execTask().copy(
            state = "working",
            createdAt = 1_700_000_000,
            updatedAt = 1_700_001_200,
            finishedAt = null,
        )
        val later = TaskRecord(
            taskId = "later-1",
            title = earlier.title,
            description = earlier.description,
            state = "queued",
            createdAt = 1_700_002_760,
            updatedAt = 1_700_002_760,
            version = 1,
        )
        val groups = TaskLibraryGrouping.groupLibraryTasks(listOf(earlier, later))
        assertEquals(2, groups.size)
    }

    @Test
    fun `identical text does not merge when the later row predates the terminal time`() {
        val original = execTask().copy(
            state = "failed",
            createdAt = 1_700_000_000,
            updatedAt = 1_700_001_200,
            finishedAt = 1_700_001_200,
        )
        val earlier = TaskRecord(
            taskId = "earlier-1",
            title = original.title,
            description = original.description,
            state = "completed",
            createdAt = 1_700_000_600,
            updatedAt = 1_700_000_600,
            version = 1,
        )
        val groups = TaskLibraryGrouping.groupLibraryTasks(listOf(original, earlier))
        assertEquals(2, groups.size)
    }

    @Test
    fun `empty input gives empty groups`() {
        assertTrue(TaskLibraryGrouping.groupLibraryTasks(emptyList()).isEmpty())
    }
}
