package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.EngineEvent

class TaskEventSummaryTest {
    private fun event(kind: String, detail: String) =
        EngineEvent(1, "conversation", kind, detail, 1_700_000_000)

    @Test
    fun `turn completion uses plain language and omits internal ids`() {
        val result = TaskEventSummaryMapper.from(
            event("turn_done", "brain=siwc-chatgpt tools=0 switched=false"),
        )
        assertEquals("回复已完成", result.title)
        assertEquals("本轮未调用工具 · 使用当前模型", result.summary)
        assertFalse(result.title.contains("siwc-chatgpt"))
        assertFalse(result.summary.contains("tools="))
    }

    @Test
    fun `workspace recovery status is summarized`() {
        val result = TaskEventSummaryMapper.from(
            event("workspace_recovery", "lock_available=true removed=2 kept=1"),
        )
        assertEquals("工作区检查", result.title)
        assertEquals("清理了 2 个过期执行目录 · 保留 1 个目录", result.summary)
    }

    @Test
    fun `states are localized`() {
        assertEquals("已完成", taskStateLabel("completed"))
        assertEquals("进行中", taskStateLabel("running"))
    }

    @Test
    fun `review blocked and assigned have their own labels`() {
        // 主流补齐（2026-11-11）：待审核与已阻塞在界面上要看得见，
        // 不能都掉进“处理中”。
        assertEquals("待审核", taskStateLabel("review"))
        assertEquals("已阻塞", taskStateLabel("blocked"))
        assertEquals("已接收", taskStateLabel("assigned"))
        assertEquals("待办", taskStateLabel("queued"))
        assertEquals("已停止", taskStateLabel("cancelled"))
        assertEquals("失败", taskStateLabel("failed"))
    }

    @Test
    fun `unknown kinds show the kind name and the first detail line`() {
        // 2026-11-11 有意改动：未知 kind 不再显示光秃“执行记录”，
        // 标题直接用 kind 原文，摘要取 detail 首行截 120 字；
        // detail 为空时摘要为空，由事件分组当作无实质内容过滤。
        val result = TaskEventSummaryMapper.from(event("permission_gate", "scope=project-write"))
        assertEquals("permission_gate", result.title)
        assertEquals("scope=project-write", result.summary)
        val blank = TaskEventSummaryMapper.from(event("some_future_kind", ""))
        assertEquals("some_future_kind", blank.title)
        assertEquals("", blank.summary)
    }

    @Test
    fun `error summary carries the first sentence of the redacted reason`() {
        // 2026-11-11 有意改动：error 摘要不再只给“去看详情”，而是
        // 带脱敏后的原因首句（BrainEngine.log() 写时已脱敏一次，
        // mapper 这里再脱敏一次兜底）。
        val result = TaskEventSummaryMapper.from(
            event("error", "brain_not_configured: 未配置大脑：请先在设置里配置一个大脑，再发送消息。"),
        )
        assertEquals("执行遇到问题", result.title)
        assertEquals("brain_not_configured: 未配置大脑：请先在设置里配置一个大脑，再发送消息", result.summary)
    }

    @Test
    fun `error summary redacts secrets in the reason`() {
        val result = TaskEventSummaryMapper.from(
            event("error", "调用失败：api_key=sk-abcdefghijklmnop0123456789，请重试。"),
        )
        assertEquals("执行遇到问题", result.title)
        assertFalse(result.summary.contains("sk-abcdefghijklmnop"))
        assertTrue(result.summary.contains("[REDACTED]"))
    }

    @Test
    fun `every real event kind has a real title and summary`() {
        val cases = mapOf(
            "empty_workspace_no_read" to ("工作区为空" to "工作区已知为空，本轮不再提供 read_file"),
            "failover_retry" to ("正在重试" to "同大脑重试第 1 次"),
            "cache_hit_denied" to ("缓存未采用" to "read_file 缓存命中但重检拒绝：stale"),
            "ask_user_awaiting" to ("等待用户回答" to "请问用哪个分支？"),
            "ask_user_invalid" to ("回答未被接受" to "缺少必需参数 path"),
            "web_search_failed" to ("联网搜索失败" to "网络超时"),
            "remote_agent_event" to ("远端动态" to "remote-device · example-agent 开始执行任务 x"),
            "tool_arg_repaired" to ("工具参数已补全" to "read_file 缺少 path，已按本轮 list_files 结果补为 a.py（调用 1）"),
            "tool_call_repeated" to ("重复调用已拦截" to "模型重复调用了同一工具（write_file，参数相同），已有文字，返回文字并停止"),
            "sse_malformed_line" to ("收到畸形数据行" to "data: {broken"),
            "user_message" to ("已发送请求" to "帮我新建一个分支"),
            "tools_unused" to ("本轮未调用工具" to "tools 在场但模型本轮未调用工具"),
            "brain_switched" to ("已切换模型" to "大脑已切换为 zhipu，任务与历史不变"),
            "control_routed" to ("控制指令已处理" to "已暂停：不再发新工具调用，等用户恢复指令。"),
            "tool_result" to ("工具步骤已返回" to "write_file ok=true 已写入 a.py"),
            "tool_result_cached" to ("工具步骤已返回（缓存）" to "read_file key=abc 原轮次=2"),
        )
        for ((kind, expected) in cases) {
            val result = TaskEventSummaryMapper.from(event(kind, expected.second))
            assertEquals(kind, expected.first, result.title)
            assertEquals(kind, expected.second, result.summary)
        }
    }

    @Test
    fun `error with blank detail falls back to the detail toggle hint`() {
        val result = TaskEventSummaryMapper.from(event("error", ""))
        assertEquals("执行遇到问题", result.title)
        assertEquals("可展开记录详情查看原因。", result.summary)
    }
}
