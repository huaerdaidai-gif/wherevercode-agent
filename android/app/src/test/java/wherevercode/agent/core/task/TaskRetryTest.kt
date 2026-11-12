package wherevercode.agent.core.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重试副本构造（2026-11-11）：副本必须保留执行者，否则泵不认领、
 * 本地执行器还会错抢到手机上本地跑。
 */
class TaskRetryTest {
    private val now = 1_700_000_000L

    private fun original() = TaskRecord(
        taskId = "692638e2-a118-439f-888d-01905db57aed",
        projectId = "proj-1",
        title = "wherevercode-agent：新建分支并提交",
        description = "在 wherevercode-agent 仓库新建分支",
        state = "failed",
        assignedAgentId = "codex",
        memberId = "member-codex",
        brainId = "zhipu",
        result = "未能完成操作。",
        error = "未能完成操作。",
        createdAt = now - 1000,
        updatedAt = now - 100,
        version = 3,
    )

    @Test
    fun `retry copy keeps the executor and project`() {
        val copy = TaskRetry.buildRetryCopy(original(), now)
        assertEquals("queued", copy.state)
        assertEquals("codex", copy.assignedAgentId)
        assertEquals("member-codex", copy.memberId)
        assertEquals("proj-1", copy.projectId)
        assertEquals("zhipu", copy.brainId)
        assertEquals(original().title, copy.title)
        assertEquals(original().description, copy.description)
    }

    @Test
    fun `retry copy links back to the original and clears the outcome`() {
        val copy = TaskRetry.buildRetryCopy(original(), now)
        assertEquals(original().taskId, copy.parentTaskId)
        assertNotEquals(original().taskId, copy.taskId)
        assertEquals(1L, copy.version)
        assertEquals(now, copy.createdAt)
        assertEquals(now, copy.updatedAt)
        assertNull(copy.sessionId)
        assertNull(copy.result)
        assertNull(copy.error)
    }

    @Test
    fun `retry copy satisfies the remote pump claim predicate`() {
        // 与 RemoteTaskPump.findRemoteQueued 同款谓词：
        // queued + sessionId 为空 + assignedAgentId 非空 + 非本地成员。
        val copy = TaskRetry.buildRetryCopy(original(), now)
        assertEquals("queued", copy.state)
        assertNull(copy.sessionId)
        assertTrue(copy.assignedAgentId != null)
        assertTrue(copy.assignedAgentId != "local" && copy.assignedAgentId != "local-brain")
    }

    @Test
    fun `retry copy is not claimable by the local runner`() {
        // LocalTaskRunner.pickNext 只认 assignedAgentId == null 或
        // LocalTaskRunner.LOCAL_AGENT_ID（"local"）。
        val copy = TaskRetry.buildRetryCopy(original(), now)
        assertTrue(
            copy.assignedAgentId != null &&
                copy.assignedAgentId != LocalTaskRunner.LOCAL_AGENT_ID,
        )
    }
}
