package wherevercode.agent.ui

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 对话消息列表宿主（对话组件收敛）：单聊与频道共用的列表核心，
 * 结构与贴底口径钉在这里。
 */
@RunWith(RobolectricTestRunner::class)
class MessageListViewTest {

    @Test
    fun `content lives inside the scroll view and an empty list counts as near bottom`() {
        val host = MessageListView(RuntimeEnvironment.getApplication())
        assertSame(host.scrollView, host.content.parent)
        assertSame(host, host.scrollView.parent)
        assertTrue(host.scrollView.isFillViewport)
        assertTrue(host.isNearBottom())
    }
}
