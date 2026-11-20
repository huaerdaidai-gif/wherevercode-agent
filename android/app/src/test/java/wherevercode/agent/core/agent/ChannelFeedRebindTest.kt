package wherevercode.agent.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 推送主模式整改（2026-11-20）：WS 订阅绑「当前打开的频道/私聊」
 * 的重绑决策。口径——推送是主模式、轮询只在 WS 断开时于
 * [ChannelFeed] 内部兜底；看哪个频道就订哪个频道，切换即重绑，
 * 没切就不动现有订阅（不无谓断连重连）。
 */
class ChannelFeedRebindTest {

    @Test
    fun `no subscription yet needs binding`() {
        assertTrue(channelFeedRebindNeeded(null, "ch-default"))
        assertTrue(channelFeedRebindNeeded(null, "dm-42"))
    }

    @Test
    fun `bound to the open channel stays`() {
        assertFalse(channelFeedRebindNeeded("ch-default", "ch-default"))
        assertFalse(channelFeedRebindNeeded("dm-42", "dm-42"))
    }

    @Test
    fun `switching channel or dm rebinds`() {
        assertTrue(channelFeedRebindNeeded("ch-default", "ch-other"))
        assertTrue(channelFeedRebindNeeded("ch-default", "dm-42"))
        assertTrue(channelFeedRebindNeeded("dm-42", "ch-default"))
    }
}
