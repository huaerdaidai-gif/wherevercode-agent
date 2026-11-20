package wherevercode.agent.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelFeedRebindTest {

    @Test
    fun `no subscription yet always needs a bind`() {
        assertTrue(channelFeedRebindNeeded(null, "ch-default"))
        assertTrue(channelFeedRebindNeeded(null, "dm-42"))
    }

    @Test
    fun `same channel keeps the subscription`() {
        assertFalse(channelFeedRebindNeeded("ch-default", "ch-default"))
        assertFalse(channelFeedRebindNeeded("dm-42", "dm-42"))
    }

    @Test
    fun `switching channels or dms needs a rebind`() {
        assertTrue(channelFeedRebindNeeded("ch-default", "ch-other"))
        assertTrue(channelFeedRebindNeeded("ch-default", "dm-42"))
        assertTrue(channelFeedRebindNeeded("dm-42", "ch-default"))
    }
}
