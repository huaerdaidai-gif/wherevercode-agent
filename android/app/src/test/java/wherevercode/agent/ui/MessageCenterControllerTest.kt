package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.message.MessageCenterEntry
import wherevercode.agent.core.message.MessageCenterSection
import wherevercode.agent.core.message.MessageCenterSnapshot

/**
 * 消息中心视图测试（Robolectric）：三区呈现、条目可点且回调带条目、
 * 入口可达可测（刷新/关闭钮回调）。跳转语义由 MainActivity 承担
 * （点条目切到对应频道页），本测试锁定“点条目 → onOpenEntry(条目)”。
 */
@RunWith(RobolectricTestRunner::class)
class MessageCenterControllerTest {
    private fun visibleTexts(view: View): List<TextView> {
        if (view.visibility != View.VISIBLE) return emptyList()
        val out = if (view is TextView) mutableListOf(view) else mutableListOf()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) out += visibleTexts(view.getChildAt(i))
        }
        return out
    }

    private fun entry(
        section: MessageCenterSection,
        id: String,
        text: String,
        at: Double,
        alias: String? = null,
        taskId: String? = null,
        taskTitle: String? = null,
    ) = MessageCenterEntry(
        section = section,
        channelId = "ch1",
        channelName = "构建频道",
        messageId = id,
        senderName = "协调者",
        text = text,
        createdAtSeconds = at,
        matchedAlias = alias,
        taskId = taskId,
        taskTitle = taskTitle,
    )

    private fun controller(
        onOpen: (MessageCenterEntry) -> Unit = {},
        onRefresh: () -> Unit = {},
        onClose: () -> Unit = {},
    ): MessageCenterController = MessageCenterController(
        RuntimeEnvironment.getApplication(),
        onRefresh = onRefresh,
        onOpenEntry = onOpen,
        onClose = onClose,
    )

    @Test
    fun `renders three sections with counts and empty hints`() {
        val controller = controller()
        controller.root.visibility = View.VISIBLE
        controller.render(
            MessageCenterSnapshot(
                mentions = listOf(
                    entry(MessageCenterSection.MENTION, "m1", "@本机 App 帮忙", 10.0, alias = "本机 App"),
                ),
                unread = emptyList(),
                taskReceipts = listOf(
                    entry(
                        MessageCenterSection.TASK_RECEIPT, "m2", "已修复", 11.0,
                        taskId = "a1b2c3d4", taskTitle = "修复登录",
                    ),
                ),
            ),
        )
        val texts = visibleTexts(controller.root).map { it.text.toString() }
        assertTrue(texts.contains("＠我 · 1"))
        assertTrue(texts.contains("未读 · 0"))
        assertTrue(texts.contains("任务回执 · 1"))
        assertTrue(texts.contains("@本机 App 帮忙"))
        assertTrue(texts.contains("任务 a1b2c3d4 · 修复登录"))
        assertTrue(texts.any { it.contains("暂无未读") })
        // 条目标注来源频道与回跳提示（可达可测）。
        assertTrue(texts.any { it.contains("构建频道") && it.contains("点按回到频道") })
    }

    @Test
    fun `tapping an entry row reports that entry and toolbar callbacks fire`() {
        val clicked = mutableListOf<MessageCenterEntry>()
        var refreshed = 0
        var closed = 0
        val controller = controller(
            onOpen = { clicked += it },
            onRefresh = { refreshed++ },
            onClose = { closed++ },
        )
        controller.root.visibility = View.VISIBLE
        val mention = entry(MessageCenterSection.MENTION, "m1", "@本机 App 帮忙", 10.0, alias = "本机 App")
        controller.render(
            MessageCenterSnapshot(mentions = listOf(mention), unread = emptyList(), taskReceipts = emptyList()),
        )
        // 点条目行 → 回调带回同一条目（MainActivity 由此跳到对应频道）。
        val row = visibleTexts(controller.root).first { it.text == "@本机 App 帮忙" }.parent as View
        row.performClick()
        assertEquals(listOf(mention), clicked)
        // 刷新钮。
        visibleTexts(controller.root).first { it.text == "刷新" }.performClick()
        assertEquals(1, refreshed)
        // 关闭钮。
        visibleTexts(controller.root).first { it.text == "关闭" }.performClick()
        assertEquals(1, closed)
    }

    @Test
    fun `empty snapshot shows guidance and loading state keeps notice`() {
        val controller = controller()
        controller.root.visibility = View.VISIBLE
        controller.render(MessageCenterSnapshot())
        val texts = visibleTexts(controller.root).map { it.text.toString() }
        assertTrue(texts.any { it.contains("暂无 @我") })
        assertTrue(texts.any { it.contains("暂无未读") })
        assertTrue(texts.any { it.contains("暂无带任务归属") })
        controller.render(null, loading = true, notice = null)
        assertTrue(
            visibleTexts(controller.root).map { it.text.toString() }.any { it.contains("正在读取") },
        )
    }
}
