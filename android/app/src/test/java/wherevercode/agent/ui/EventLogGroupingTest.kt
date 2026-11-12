package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.brain.EngineEvent

/**
 * 运行事件分组折叠（2026-11-11，maintainer 22:25 密度要求）。
 *
 * 分组键 = conversationId（空 →“本机”）+ 时间簇（相邻 > 10 分钟
 * 另起一组）；无实质内容事件只计数、不占行；组按时间倒序。
 */
class EventLogGroupingTest {
    private val base = 1_700_000_000L

    private fun event(
        kind: String,
        detail: String,
        epochSeconds: Long,
        conversationId: String = "session-1",
    ) = EngineEvent(0, conversationId, kind, detail, epochSeconds)

    @Test
    fun `groups by conversation and ten minute gap`() {
        val events = listOf(
            event("turn_done", "brain=x tools=1 switched=false", base, "a"),
            event("turn_done", "brain=x tools=0 switched=false", base + 60, "a"),
            // 同一会话、间隔 > 10 分钟 → 另起一组。
            event("turn_done", "brain=x tools=2 switched=false", base + 60 + 601, "a"),
            event("turn_done", "brain=x tools=0 switched=false", base + 120, "b"),
        )
        val groups = EventLogGrouping.groupEvents(events)
        assertEquals(3, groups.size)
        // 倒序：最新组在前（a@661 1 条、b@120 1 条、a@base 2 条）。
        assertEquals(1, groups[0].events.size)
        assertEquals(1, groups[1].events.size)
        assertEquals(2, groups[2].events.size)
        // 组内事件按时间倒序。
        assertEquals(base + 60, groups[2].events[0].epochSeconds)
        assertEquals(base, groups[2].events[1].epochSeconds)
    }

    @Test
    fun `blank conversation id groups under local device label`() {
        val groups = EventLogGrouping.groupEvents(
            listOf(event("turn_done", "brain=x tools=0 switched=false", base, "")),
        )
        assertEquals(1, groups.size)
        assertTrue(groups[0].title.startsWith("本机 · "))
    }

    @Test
    fun `events without substance are counted not rendered`() {
        val events = listOf(
            event("turn_done", "brain=x tools=1 switched=false", base),
            // 未知 kind 且 detail 为空：无摘要、无 detail → 无实质内容。
            event("some_future_kind", "", base + 10),
            event("turn_done", "brain=x tools=0 switched=false", base + 20),
        )
        val groups = EventLogGrouping.groupEvents(events)
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].events.size)
        assertEquals(1, groups[0].hiddenCount)
    }

    @Test
    fun `error with blank detail still has substance via fallback summary`() {
        val groups = EventLogGrouping.groupEvents(
            listOf(event("error", "", base)),
        )
        assertEquals(1, groups.size)
        assertEquals(0, groups[0].hiddenCount)
    }

    @Test
    fun `unknown kind with detail has substance`() {
        val groups = EventLogGrouping.groupEvents(
            listOf(event("some_future_kind", "scope=project-write", base)),
        )
        assertEquals(1, groups.size)
        assertEquals(0, groups[0].hiddenCount)
    }

    @Test
    fun `conversation with only insubstantial events forms no group`() {
        val groups = EventLogGrouping.groupEvents(
            listOf(event("some_future_kind", "", base)),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `default visible group count is three`() {
        assertEquals(3, EventLogGrouping.DEFAULT_VISIBLE_GROUPS)
        assertEquals(600L, EventLogGrouping.CLUSTER_GAP_SECONDS)
    }

    @Test
    fun `input order does not matter`() {
        val events = listOf(
            event("turn_done", "brain=x tools=0 switched=false", base + 120),
            event("turn_done", "brain=x tools=1 switched=false", base),
        )
        val groups = EventLogGrouping.groupEvents(events)
        assertEquals(1, groups.size)
        assertEquals(base + 120, groups[0].events[0].epochSeconds)
        assertEquals(base, groups[0].events[1].epochSeconds)
    }

    @Test
    fun `hasSubstance is false only when summary and detail are both blank`() {
        assertFalse(
            EventLogGrouping.hasSubstance(event("some_future_kind", "  ", base)),
        )
        assertTrue(
            EventLogGrouping.hasSubstance(event("turn_done", "brain=x tools=0 switched=false", base)),
        )
    }
}
