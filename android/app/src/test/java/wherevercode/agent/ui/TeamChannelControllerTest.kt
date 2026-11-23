package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.agent.ChannelConnectionState

@RunWith(RobolectricTestRunner::class)
class TeamChannelControllerTest {
    private fun visibleTexts(view: View): List<TextView> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val out = if (view is TextView) mutableListOf(view) else mutableListOf()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) out += visibleTexts(view.getChildAt(i))
        }
        return out
    }

    @Test
    fun `unconfigured channel hides dialog chrome and shows only guidance and agent management`() {
        var openedAgents = 0
        val controller = TeamChannelController(
            RuntimeEnvironment.getApplication(),
            onOpenChatWithAgent = {}, onPost = {}, onMarkRead = {}, onNewRound = {},
            onRefresh = {}, onOpenAgentManagement = { openedAgents++ },
            // 传 onCreateTask 才会建发送方式 radios（与生产调用方一致）。
            onCreateTask = { _, _ -> },
        )
        controller.root.visibility = View.VISIBLE
        controller.render(
            TeamChannelSnapshot(
                configured = false,
                setupHint = "频道还没法连：先去 Agent 页加一个平台 Agent，并在表单的平台凭证栏填入 App 的平台凭证（由你的平台管理员签发）。",
                channelId = "test",
                connectionState = ChannelConnectionState.STOPPED,
                connectionError = null,
                members = emptyList(), messages = emptyList(),
                unreadCount = 0, unreadCapped = false, roundsUsed = 0,
                notice = null,
            ),
        )
        val texts = visibleTexts(controller.root).map { it.text.toString() }
        // 照片走查批 D：未配时只留页头与引导（与 iOS 未配 Token 视图同构）。
        assertTrue(texts.contains("频道"))
        assertTrue(texts.any { it.contains("频道还没法连") })
        assertTrue(texts.contains("前往 Agent 管理"))
        assertFalse(
            texts.any {
                it.contains("频道未连接") || it.contains("第 1/") || it.contains("重新连接") ||
                    it.contains("刷新消息") || it.contains("仅讨论") || it.contains("转为正式任务") ||
                    it.contains("只读协作工具")
            },
        )
        visibleTexts(controller.root).first { it.text == "前往 Agent 管理" }.performClick()
        assertEquals(1, openedAgents)
        // 配好后对话框铬整套回来。
        controller.render(
            TeamChannelSnapshot(
                configured = true,
                setupHint = "",
                channelId = "test",
                connectionState = ChannelConnectionState.LIVE,
                connectionError = null,
                members = emptyList(), messages = emptyList(),
                unreadCount = 0, unreadCapped = false, roundsUsed = 0,
                notice = null,
            ),
        )
        val liveTexts = visibleTexts(controller.root).map { it.text.toString() }
        assertTrue(liveTexts.any { it.contains("直播推送") })
        assertTrue(liveTexts.contains("仅讨论"))
        assertTrue(liveTexts.contains("转为正式任务"))
        assertFalse(liveTexts.any { it.contains("只读协作工具") || it.contains("只读扇出") })
    }

    @Test
    fun `polling keeps TLS failure safe and folds raw diagnostic behind the diagnostic toggle`() {
        val controller = TeamChannelController(
            RuntimeEnvironment.getApplication(),
            onOpenChatWithAgent = {}, onPost = {}, onMarkRead = {}, onNewRound = {},
            onRefresh = {},
        )
        controller.root.visibility = View.VISIBLE
        val raw = "javax.net.ssl.SSLHandshakeException: CertPathValidatorException: Trust anchor for certification path not found"
        controller.render(
            TeamChannelSnapshot(
                configured = true,
                setupHint = "",
                channelId = "test",
                connectionState = ChannelConnectionState.POLLING,
                connectionError = raw,
                members = emptyList(), messages = emptyList(),
                unreadCount = 0, unreadCapped = false, roundsUsed = 0,
                notice = null,
            ),
        )
        val texts = visibleTexts(controller.root).map { it.text.toString() }
        assertTrue(texts.any { it.contains("轮询兜底") && it.contains("安全连接未建立") })
        assertTrue(texts.contains("查看连接诊断"))
        assertFalse(texts.any { it.contains("Trust anchor") })
        visibleTexts(controller.root).first { it.text == "查看连接诊断" }.performClick()
        assertTrue(visibleTexts(controller.root).any { it.text.contains("Trust anchor") })
    }
}
