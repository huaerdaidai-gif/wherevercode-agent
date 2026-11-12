package wherevercode.agent.core.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import wherevercode.agent.core.agent.PlatformChannelMessage

/**
 * 频道发送回音跟踪测试（2026-11-13）：发完等回音，没回音不自动重发，
 * 只提示用户手动重发。时间用假时钟，不依赖真时间。
 */
@RunWith(RobolectricTestRunner::class)
class ChannelSendTrackerTest {

    private fun message(id: String, text: String, atSeconds: Double) =
        PlatformChannelMessage(
            id = id,
            agentId = "platform-agent",
            agentName = "Example Agent",
            text = text,
            createdAtSeconds = atSeconds,
        )

    @Test
    fun `echo with the same text and time marks the send delivered`() {
        var now = 1_000_000L
        val tracker = ChannelSendTracker(nowMs = { now })
        tracker.markSent("在吗")
        assertEquals(1, tracker.pendingNow().size)
        // 几天前别人发的相同文本不是回音（时间窗外）。
        val old = listOf(message("m0", "在吗", 100.0))
        assertTrue(tracker.matchEcho(old).isEmpty())
        assertEquals(1, tracker.pendingNow().size)
        // 发出后 0.5 秒回来的相同文本是回音。
        val echo = listOf(message("m1", "在吗", 1000.5))
        val delivered = tracker.matchEcho(echo)
        assertEquals(1, delivered.size)
        assertEquals("在吗", delivered.single().text)
        assertTrue(tracker.pendingNow().isEmpty())
        now += 1
    }

    @Test
    fun `different text is not an echo`() {
        val tracker = ChannelSendTracker(nowMs = { 1_000_000L })
        tracker.markSent("在吗")
        val other = listOf(message("m1", "在吗？", 1000.5))
        assertTrue(tracker.matchEcho(other).isEmpty())
        assertEquals(1, tracker.pendingNow().size)
    }

    @Test
    fun `pending older than thirty seconds is stale`() {
        var now = 1_000_000L
        val tracker = ChannelSendTracker(nowMs = { now })
        tracker.markSent("在吗")
        assertTrue(tracker.stalePending().isEmpty())
        now += 29_000L
        assertTrue(tracker.stalePending().isEmpty())
        now += 2_000L
        assertEquals(1, tracker.stalePending().size)
        assertEquals("在吗", tracker.stalePending().single().text)
    }

    @Test
    fun `two identical sends need two echoes`() {
        val tracker = ChannelSendTracker(nowMs = { 1_000_000L })
        tracker.markSent("在吗")
        tracker.markSent("在吗")
        val oneEcho = listOf(message("m1", "在吗", 1000.5))
        assertEquals(1, tracker.matchEcho(oneEcho).size)
        assertEquals(1, tracker.pendingNow().size)
        val secondEcho = listOf(
            message("m1", "在吗", 1000.5),
            message("m2", "在吗", 1000.6),
        )
        assertEquals(1, tracker.matchEcho(secondEcho).size)
        assertTrue(tracker.pendingNow().isEmpty())
    }
}
