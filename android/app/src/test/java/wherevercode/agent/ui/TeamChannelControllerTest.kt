package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
    fun `polling keeps TLS failure safe and folds raw diagnostic behind recovery controls`() {
        var reconnects = 0
        val controller = TeamChannelController(
            RuntimeEnvironment.getApplication(),
            onOpenChatWithAgent = {}, onPost = {}, onMarkRead = {}, onNewRound = {},
            onStartFanout = {}, onRefresh = {}, onReconnect = { reconnects++ },
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
                fanoutRuns = emptyList(), fanoutRunning = false, notice = null,
            ),
        )
        val texts = visibleTexts(controller.root).map { it.text.toString() }
        assertTrue(texts.any { it.contains("轮询兜底") && it.contains("安全连接未建立") })
        assertTrue(texts.contains("查看连接诊断"))
        assertFalse(texts.any { it.contains("Trust anchor") })
        visibleTexts(controller.root).first { it.text == "重新连接" }.performClick()
        assertTrue(reconnects == 1)
        visibleTexts(controller.root).first { it.text == "查看连接诊断" }.performClick()
        assertTrue(visibleTexts(controller.root).any { it.text.contains("Trust anchor") })
    }
}
