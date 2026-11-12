package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.core.agent.PlatformChannelMessage

/**
 * 频道历史记录、分页与搜索测试（2026-11-13）。
 * 客户端层：只覆盖本机已加载的消息；全量历史走服务端 before_id / q
 * （待接线）。全是纯函数，无 IO。
 */
@RunWith(RobolectricTestRunner::class)
class ChannelHistoryTest {

    private fun message(id: String, agentName: String, text: String, at: Double) =
        PlatformChannelMessage(
            id = id,
            agentId = agentName,
            agentName = agentName,
            text = text,
            createdAtSeconds = at,
        )

    private fun sample() = listOf(
        message("m1", "Codex", "登录闪退我看了，是空指针。", 1.0),
        message("m2", "Example Agent", "大家早。", 2.0),
        message("m3", "Codex", "已补判空，测试过了。", 3.0),
        message("m4", "Manus", "包已编好，等你测。", 4.0),
    )

    @Test
    fun `search matches text and sender name case-insensitively`() {
        val messages = sample()
        assertEquals(listOf("m1"), ChannelHistory.search(messages, "空指针").map { it.id })
        assertEquals(
            listOf("m1", "m3"),
            ChannelHistory.search(messages, "codex").map { it.id },
        )
        assertEquals(listOf("m2"), ChannelHistory.search(messages, "大家早").map { it.id })
        assertTrue(ChannelHistory.search(messages, "不存在").isEmpty())
        // 空查询返回原列表。
        assertEquals(messages, ChannelHistory.search(messages, "   "))
    }

    @Test
    fun `merge dedupes by id and sorts by time`() {
        val merged = ChannelHistory.merge(
            listOf(message("m2", "Example Agent", "大家早。", 2.0)),
            listOf(
                message("m1", "Codex", "登录闪退我看了。", 1.0),
                message("m2", "Example Agent", "大家早。", 2.0),
                message("", "Nobody", "没 id 的丢掉。", 3.0),
                message("m3", "Codex", "已补判空。", 3.0),
            ),
        )
        assertEquals(listOf("m1", "m2", "m3"), merged.map { it.id })
    }

    @Test
    fun `merge keeps only the newest five hundred`() {
        val many = (1..600).map { message("m$it", "Codex", "第 $it 条", it.toDouble()) }
        val merged = ChannelHistory.merge(emptyList(), many)
        assertEquals(ChannelHistory.MAX_KEPT_MESSAGES, merged.size)
        assertEquals("m101", merged.first().id)
        assertEquals("m600", merged.last().id)
    }

    @Test
    fun `pageOlder returns the page before the cursor`() {
        val messages = sample()
        assertEquals(listOf("m3", "m4"), ChannelHistory.pageOlder(messages, null, 2).map { it.id })
        assertEquals(listOf("m1", "m2"), ChannelHistory.pageOlder(messages, "m3", 2).map { it.id })
        assertEquals(listOf("m1"), ChannelHistory.pageOlder(messages, "m2", 2).map { it.id })
        assertEquals(emptyList<PlatformChannelMessage>(), ChannelHistory.pageOlder(messages, "m1", 2))
        // 找不着的游标按最新一页算。
        assertEquals(listOf("m3", "m4"), ChannelHistory.pageOlder(messages, "nope", 2).map { it.id })
    }

    @Test
    fun `visibleWindow shows the newest slice and olderRemaining counts the rest`() {
        val messages = sample()
        assertEquals(listOf("m3", "m4"), ChannelHistory.visibleWindow(messages, 2).map { it.id })
        assertEquals(2, ChannelHistory.olderRemaining(messages, 2))
        assertEquals(0, ChannelHistory.olderRemaining(messages, 4))
        assertEquals(0, ChannelHistory.olderRemaining(messages, 99))
        assertTrue(ChannelHistory.visibleWindow(messages, 0).isEmpty())
    }

    @Test
    fun `countUnreadAfter counts messages after the last read id`() {
        val messages = sample()
        assertEquals(4, ChannelHistory.countUnreadAfter(messages, null))
        assertEquals(4, ChannelHistory.countUnreadAfter(messages, ""))
        assertEquals(4, ChannelHistory.countUnreadAfter(messages, "nope"))
        assertEquals(2, ChannelHistory.countUnreadAfter(messages, "m2"))
        assertEquals(0, ChannelHistory.countUnreadAfter(messages, "m4"))
    }
}
