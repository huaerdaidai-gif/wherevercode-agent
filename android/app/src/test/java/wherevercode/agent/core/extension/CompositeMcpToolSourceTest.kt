package wherevercode.agent.core.extension.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.ToolDef
import wherevercode.agent.brain.ToolOutcome

/**
 * 多 MCP 源联合体（2026-11-13，MCP 能力补齐）：通用管理器与平台
 * 客户端汇成一个源给大脑工具链，两路名字空间互不冲突、执行按归属分流。
 */
class CompositeMcpToolSourceTest {

    private class FakeSource(
        private val defs: List<ToolDef>,
        private val readOnly: Set<String> = emptySet(),
        private val executed: MutableList<String> = mutableListOf(),
    ) : McpToolSource {
        override fun toolDefs(): List<ToolDef> = defs
        override fun isReadOnly(toolName: String): Boolean = toolName in readOnly
        override fun needsApproval(toolName: String): Boolean = !isReadOnly(toolName)
        override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolOutcome {
            executed += toolName
            return ToolOutcome(ok = true, summary = "executed $toolName")
        }
    }

    @Test
    fun `toolDefs unions both sources in order`() {
        val a = FakeSource(listOf(ToolDef("mcp_hand_list", "hand list")))
        val b = FakeSource(listOf(ToolDef("mcp_platform_claim_task", "claim")))
        val combo = CompositeMcpToolSource(a, b)
        assertEquals(
            listOf("mcp_hand_list", "mcp_platform_claim_task"),
            combo.toolDefs().map { it.name },
        )
    }

    @Test
    fun `execute routes to the owning source`() = runBlocking {
        val executedA = mutableListOf<String>()
        val executedB = mutableListOf<String>()
        val a = FakeSource(listOf(ToolDef("mcp_hand_list", "hand list")), executed = executedA)
        val b = FakeSource(listOf(ToolDef("mcp_platform_claim_task", "claim")), executed = executedB)
        val combo = CompositeMcpToolSource(a, b)
        val out = combo.execute("mcp_platform_claim_task", mapOf("task_id" to "t1"))
        assertTrue(out.ok)
        assertEquals(listOf("mcp_platform_claim_task"), executedB)
        assertTrue("另一路不应被调用", executedA.isEmpty())
    }

    @Test
    fun `execute unknown tool reports invalid_action and calls nobody`() = runBlocking {
        val a = FakeSource(listOf(ToolDef("mcp_hand_list", "hand list")))
        val b = FakeSource(listOf(ToolDef("mcp_platform_claim_task", "claim")))
        val combo = CompositeMcpToolSource(a, b)
        val out = combo.execute("mcp_platform_nosuch", emptyMap())
        assertFalse(out.ok)
        assertEquals("invalid_action", out.errorCode)
    }

    @Test
    fun `readOnly delegates to the owning source and defaults to approval`() {
        val a = FakeSource(
            listOf(ToolDef("mcp_hand_list", "hand list")),
            readOnly = setOf("mcp_hand_list"),
        )
        val b = FakeSource(listOf(ToolDef("mcp_platform_claim_task", "claim")))
        val combo = CompositeMcpToolSource(a, b)
        assertTrue(combo.isReadOnly("mcp_hand_list"))
        assertFalse(combo.isReadOnly("mcp_platform_claim_task"))
        assertTrue(combo.needsApproval("mcp_platform_claim_task"))
        assertTrue("哪路都不认的名字默认要审批", combo.needsApproval("mcp_platform_nosuch"))
    }
}
