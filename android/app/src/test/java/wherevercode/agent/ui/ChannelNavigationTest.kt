package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.agent.ChannelConnectionState
import wherevercode.agent.core.team.TeamChannelMember

/** Test-only snapshots; no sample records are shipped in production. */
@RunWith(RobolectricTestRunner::class)
class ChannelNavigationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun snapshot(configured: Boolean = true) = TeamChannelSnapshot(
        configured = configured, setupHint = "未配置频道", channelId = "test-channel",
        connectionState = ChannelConnectionState.STOPPED, connectionError = null,
        members = listOf(TeamChannelMember("app", "Local", true), TeamChannelMember("alpha", "Alpha")),
        messages = emptyList(), unreadCount = 0, unreadCapped = false, roundsUsed = 0,
        fanoutRuns = emptyList(), fanoutRunning = false, notice = null,
    )

    private fun children(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) }
        else emptyList()

    private fun click(root: View, text: String) = children(root)
        .filterIsInstance<TextView>().first { it.text.toString() == text }.performClick()

    @Test
    fun `failed sends retain draft and duplicate taps do not resend while pending`() {
        val sent = mutableListOf<String>()
        val controller = TeamChannelController(context, {}, { sent += it }, {}, {}, {}, {})
        controller.render(snapshot())
        val input = children(controller.root).filterIsInstance<EditText>().first { it.hint.toString().startsWith("给频道") }
        input.setText("hello")
        click(controller.root, "发送")
        click(controller.root, "发送")
        assertEquals(listOf("hello"), sent)
        assertEquals("hello", input.text.toString())
        controller.postResult("hello", false)
        click(controller.root, "发送")
        assertEquals(listOf("hello", "hello"), sent)
        controller.postResult("hello", true)
        assertEquals("", input.text.toString())
    }

    @Test
    fun `long press on Agent inserts mention without opening another session`() {
        var opened = false
        val controller = TeamChannelController(context, { opened = true }, {}, {}, {}, {}, {})
        controller.render(snapshot())
        val chip = children(controller.root).filterIsInstance<TextView>().first { it.text.toString() == "Alpha" }
        assertTrue(chip.performLongClick())
        assertFalse(opened)
        assertEquals("@Alpha ", children(controller.root).filterIsInstance<EditText>().first { it.hint.toString().startsWith("给频道") }.text.toString())
    }

    @Test
    fun `unconfigured channel cannot send and file tab states unavailability`() {
        var sent = false
        val controller = TeamChannelController(context, {}, { sent = true }, {}, {}, {}, {})
        controller.render(snapshot(false))
        children(controller.root).filterIsInstance<EditText>().first { it.hint.toString().startsWith("给频道") }.setText("hello")
        click(controller.root, "发送")
        assertFalse(sent)
        children(controller.root).first { it.tag == "channel_tab_files" }.performClick()
        assertTrue(children(controller.root).filterIsInstance<TextView>().any {
            it.text.toString().contains("附件上传、项目文件、网盘引用、预览和下载暂不可用")
        })
    }

    @Test
    fun `private tab opens existing Agent session and hides transport-only app`() {
        val opened = mutableListOf<String>()
        val controller = ChannelHomeController(context, {}, { opened += it }, {})
        controller.render(snapshot())
        children(controller.root).first { it.tag == "channel_home_private" }.performClick()
        assertFalse(children(controller.root).any { it.tag == "channel_private_app" })
        children(controller.root).first { it.tag == "channel_private_alpha" }.performClick()
        assertEquals(listOf("alpha"), opened)
    }
}
