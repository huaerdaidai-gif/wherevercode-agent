package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.task.TaskStates

/**
 * 新建任务表单纯逻辑测试（核心功能推进第 3 项，2026-11-15）。
 *
 * 覆盖：校验（空标题拒绝、未选 Agent 拒绝）、可派单 Agent 过滤
 * （停用 / 不可派单不进列表）、表单值 → 分派任务行的映射与
 * 对话派单不变量一致（sessionId = null、state = queued、
 * assignedAgentId = memberId、brainId = null、FIFO 时间戳）。
 */
class CreateTaskFormTest {

    private fun agent(
        id: String,
        name: String = id,
        enabled: Boolean = true,
        dispatchable: Boolean = true,
        deviceId: String? = null,
    ) = AgentRecord(
        agentId = id,
        displayName = name,
        kind = if (deviceId != null) AgentKind.DEVICE else AgentKind.CLOUD,
        access = AgentAccess.API,
        brainKind = BrainKind.MODEL_API,
        adapterId = "test",
        deviceId = deviceId,
        enabled = enabled,
        dispatchable = dispatchable,
    )

    @Test
    fun `blank title is rejected`() {
        val input = CreateTaskForm.Input("  ", "说明", agent("a1"), null)
        assertEquals("任务标题不能为空。", CreateTaskForm.validate(input))
    }

    @Test
    fun `missing agent is rejected`() {
        val input = CreateTaskForm.Input("标题", "", null, null)
        assertEquals("请选择执行 Agent。", CreateTaskForm.validate(input))
    }

    @Test
    fun `valid input passes validation`() {
        val input = CreateTaskForm.Input("标题", "", agent("a1"), null)
        assertNull(CreateTaskForm.validate(input))
    }

    @Test
    fun `dispatchable agents drop disabled and non dispatchable`() {
        val agents = listOf(
            agent("ok-1"),
            agent("off", enabled = false),
            agent("chat-only", dispatchable = false),
            agent("ok-2", deviceId = "dev-1"),
        )
        assertEquals(
            listOf("ok-1", "ok-2"),
            CreateTaskForm.dispatchableAgents(agents).map { it.agentId },
        )
        assertTrue(CreateTaskForm.dispatchableAgents(emptyList()).isEmpty())
    }

    @Test
    fun `build record maps form values onto dispatched row invariants`() {
        val remote = agent("agent-1", name = "远端 Codex", deviceId = "dev-1")
        val input = CreateTaskForm.Input(
            title = "  修登录页  ",
            description = "  补记住我选项，并跑测试  ",
            agent = remote,
            projectId = "proj-1",
        )
        val record = CreateTaskForm.buildRecord(input, nowSeconds = 1234L)
        // 表单原值（trim 后）落标题 / 说明，不走工厂的 take(40) 派生。
        assertEquals("修登录页", record.title)
        assertEquals("补记住我选项，并跑测试", record.description)
        // 与对话派单一致的分派行不变量：两台执行器的认领前提。
        assertEquals("queued", record.state)
        assertTrue(TaskStates.isClaimable(record.state))
        assertNull(record.sessionId)
        assertEquals("agent-1", record.assignedAgentId)
        assertEquals("agent-1", record.memberId)
        assertEquals("proj-1", record.projectId)
        assertNull(record.brainId)
        assertEquals(1L, record.version)
        assertEquals(1234L, record.createdAt)
        assertEquals(1234L, record.updatedAt)
        assertTrue(record.taskId.isNotBlank())
        assertFalse(record.taskId.startsWith("mcp-", ignoreCase = true))
    }

    @Test
    fun `build record falls back description to title when blank`() {
        val input = CreateTaskForm.Input("修登录页", "   ", agent("a1"), null)
        val record = CreateTaskForm.buildRecord(input, nowSeconds = 7L)
        assertEquals("修登录页", record.title)
        assertEquals("修登录页", record.description)
        assertNull(record.projectId)
    }

    @Test
    fun `build record rejects invalid input instead of silently fixing it`() {
        try {
            CreateTaskForm.buildRecord(
                CreateTaskForm.Input("", "", agent("a1"), null),
                nowSeconds = 1L,
            )
            fail("空标题应抛错")
        } catch (expected: IllegalArgumentException) {
            assertEquals("任务标题不能为空。", expected.message)
        }
        try {
            CreateTaskForm.buildRecord(
                CreateTaskForm.Input("标题", "", null, null),
                nowSeconds = 1L,
            )
            fail("未选 Agent 应抛错")
        } catch (expected: IllegalArgumentException) {
            assertEquals("请选择执行 Agent。", expected.message)
        }
    }

    @Test
    fun `execution hint distinguishes remote device agents`() {
        val remote = agent("a1", name = "远端 Codex", deviceId = "dev-1")
        assertTrue(CreateTaskForm.executionHint(remote).contains("远端泵会发到"))
        val local = agent("a2", name = "本机助手")
        val hint = CreateTaskForm.executionHint(local)
        assertTrue(hint.contains("等 本机助手 认领"))
        assertTrue(hint.contains("本地执行器不代执行"))
    }
}
