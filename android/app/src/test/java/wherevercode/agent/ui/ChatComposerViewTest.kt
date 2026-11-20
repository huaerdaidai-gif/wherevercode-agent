package wherevercode.agent.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.R

/**
 * 对话输入区组件（对话组件收敛）：单聊与频道共用的输入区核心，
 * 两页的接线契约钉在这里——发送请求只从组件出、语义留在页面。
 */
@RunWith(RobolectricTestRunner::class)
class ChatComposerViewTest {

    private fun context() = RuntimeEnvironment.getApplication()

    @Test
    fun `stacked composer keeps the chat home input contract`() {
        val composer = ChatComposerView(context())
        assertEquals(ChatComposerView.Arrangement.STACKED, composer.arrangement)
        val input = composer.findViewById<EditText>(R.id.inputPrompt)
        assertNotNull(input)
        assertSame(composer.input, input)
        assertEquals("向 Agent 提问或分派任务", input.hint.toString())
        assertTrue(
            input.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEND,
        )
        val density = context().resources.displayMetrics.density
        assertTrue(input.minHeight <= (36 * density + 0.5f).toInt())
        assertEquals(14f, input.textSize / density, 0.01f)
        // 下层行沿用 inputLowerRow id，发送钮是它的直接子视图。
        val lowerRow = composer.findViewById<View>(R.id.inputLowerRow)
        assertNotNull(lowerRow)
        assertSame(lowerRow, composer.sendButton.parent)
        assertSame(composer, input.parent)
    }

    @Test
    fun `send button click and ime action both raise onSendRequest`() {
        val composer = ChatComposerView(context())
        var sent = 0
        composer.onSendRequest = { sent++ }
        composer.sendButton.performClick()
        assertEquals(1, sent)
        composer.input.onEditorAction(EditorInfo.IME_ACTION_SEND)
        assertEquals(2, sent)
    }

    @Test
    fun `setSendEnabled drives enabled and alpha like the chat page did`() {
        val composer = ChatComposerView(context())
        composer.setSendEnabled(false)
        assertFalse(composer.sendButton.isEnabled)
        assertEquals(0.4f, composer.sendButton.alpha, 0.001f)
        composer.setSendEnabled(true)
        assertTrue(composer.sendButton.isEnabled)
        assertEquals(1f, composer.sendButton.alpha, 0.001f)
    }

    @Test
    fun `leading controls land in the lower row before spacer and send`() {
        val composer = ChatComposerView(context())
        val plus = TextView(context()).apply { text = "＋" }
        val picker = TextView(context()).apply { text = "选择对话方 ⌄" }
        composer.addLeadingControl(plus)
        composer.addLeadingControl(picker)
        val lowerRow = composer.lowerRow!!
        assertSame(lowerRow, plus.parent)
        assertSame(lowerRow, picker.parent)
        assertTrue(lowerRow.indexOfChild(plus) < lowerRow.indexOfChild(picker))
        assertTrue(lowerRow.indexOfChild(picker) < lowerRow.indexOfChild(composer.sendButton))
    }

    @Test
    fun `inline composer has caption row label send and click wiring`() {
        val composer = ChatComposerView(context(), ChatComposerView.Arrangement.INLINE)
        assertNotNull(composer.sendLabel)
        assertEquals("发送", composer.sendLabel!!.text.toString())
        assertSame(composer.sendLabel, composer.sendButton)
        // 说明行默认隐藏，设了才出现（频道说明行口径）。
        composer.caption = "频道消息 · 所有成员可见"
        assertEquals("频道消息 · 所有成员可见", composer.caption)
        composer.caption = null
        assertEquals(null, composer.caption)
        var sent = 0
        composer.onSendRequest = { sent++ }
        composer.sendButton.performClick()
        assertEquals(1, sent)
        // 频道输入框最多 4 行（沿用频道现状）。
        assertEquals(4, composer.input.maxLines)
    }
}
