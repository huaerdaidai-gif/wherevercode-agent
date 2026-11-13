package wherevercode.agent.ui

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.R

@RunWith(RobolectricTestRunner::class)
class ChatTurnViewTest {

    private fun texts(root: View): List<TextView> {
        val out = mutableListOf<TextView>()
        fun walk(view: View) {
            if (view is TextView) out += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        return out
    }

    private fun icon(root: View, description: String): ImageView? {
        var found: ImageView? = null
        fun walk(view: View) {
            if (view is ImageView && view.contentDescription?.toString() == description) {
                found = view
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        return found
    }

    private fun described(root: View, description: String): View? {
        var found: View? = null
        fun walk(view: View) {
            if (view.contentDescription?.toString() == description) found = view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        return found
    }

    /** 只收整条祖先链都 VISIBLE 的 TextView 文本（折叠起来的卡片不算）。 */
    private fun visibleTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun shown(view: View): Boolean {
            var current: View? = view
            while (current != null) {
                if (current.visibility != View.VISIBLE) return false
                current = current.parent as? View
            }
            return true
        }
        fun walk(view: View) {
            if (view is TextView && shown(view)) out += view.text.toString()
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(root)
        return out
    }

    private fun doneTurn(
        id: String = "turn-1",
        userText: String = "读 README",
        assistantText: String = "最终答案",
        statusNote: String? = null,
        attachments: List<ChatAttachmentUi> = emptyList(),
        notes: List<String> = emptyList(),
        status: TurnStatus = TurnStatus.DONE,
        toolCalls: List<ChatToolCallUi> = emptyList(),
        speaker: String = "",
        speakerIsBrain: Boolean = true,
        usage: TurnUsage? = null,
        routeLabel: String? = null,
    ): ChatTurnUi = ChatTurnUi(
        id = id,
        userText = userText,
        assistantText = assistantText,
        reasoning = "",
        protocolRedacted = false,
        toolCalls = toolCalls,
        notes = notes,
        status = status,
        error = null,
        revision = 1,
        statusNote = statusNote,
        attachments = attachments,
        usage = usage,
        speaker = speaker,
        speakerIsBrain = speakerIsBrain,
        routeLabel = routeLabel,
    )

    private fun themedContext() = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        R.style.Theme_WhereverCode,
    )

    @Test
    fun `assistant bubble contains prose only - tool call and result are separate views`() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_WhereverCode,
        )
        val view = ChatTurnView(context)
        view.bind(
            ChatTurnUi(
                id = "turn-1",
                userText = "读 README",
                assistantText = "最终答案",
                reasoning = "先看看目录",
                protocolRedacted = true,
                toolCalls = listOf(
                    ChatToolCallUi(
                        id = "call-1",
                        name = "read_file",
                        args = "{\n  \"path\": \"README.md\"\n}",
                        status = ToolCallStatus.SUCCESS,
                        resultSummary = "读取成功，42 字节",
                    ),
                ),
                notes = emptyList(),
                status = TurnStatus.DONE,
                error = null,
                revision = 1,
            ),
        )

        val rendered = texts(view)
        val assistant = rendered.filter { it.text.toString() == "最终答案" }
        assertTrue("assistant prose should be exactly one bubble", assistant.size == 1)
        assertFalse(assistant.single().text.toString().contains("read_file"))
        assertFalse(assistant.single().text.toString().contains("README.md"))
        assertTrue(rendered.any { it.text.toString() == "read_file" })
        assertTrue(rendered.any { it.text.toString().contains("README.md") })
        assertTrue(rendered.any { it.text.toString().contains("读取成功") })
        assertTrue(rendered.any { it.text.toString().contains("思考过程") })
        assertTrue(rendered.any { it.text.toString().contains("已隔离原始工具协议") })
        assertFalse(assistant.single().text.toString().contains("DSML"))
    }

    @Test
    fun `user-only turn does not render an empty agent header`() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_WhereverCode,
        )
        val view = ChatTurnView(context)
        view.bind(
            ChatTurnUi(
                id = "turn-user",
                userText = "只发一句话",
                assistantText = "",
                reasoning = "",
                protocolRedacted = false,
                toolCalls = emptyList(),
                notes = emptyList(),
                status = TurnStatus.DONE,
                error = null,
                revision = 1,
            ),
        )

        val rendered = texts(view).map { it.text.toString() }
        assertTrue(rendered.contains("只发一句话"))
        assertFalse(rendered.contains("AGENT"))
        assertFalse(rendered.contains("完成"))
    }

    @Test
    fun `error turns show an explicit error card, not a blank bubble`() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_WhereverCode,
        )
        val view = ChatTurnView(context)
        view.bind(
            ChatTurnUi(
                id = "turn-2",
                userText = "执行",
                assistantText = "",
                reasoning = "",
                protocolRedacted = false,
                toolCalls = emptyList(),
                notes = emptyList(),
                status = TurnStatus.ERROR,
                error = "deepseek_http_500：服务不可用",
                revision = 1,
            ),
        )

        val rendered = texts(view)
        assertTrue(rendered.any { it.text.toString().contains("deepseek_http_500") })
        assertTrue(rendered.any { it.text.toString() == "错误" })
    }

    @Test
    fun `finished turn shows one action icon row and no usage line`() {
        val view = ChatTurnView(themedContext())
        view.bind(doneTurn(id = "turn-meta"))

        // V5 聊天清理 20261111：用量行已拿掉（用量是技术细节，不占聊天
        // 位置）；回复下方是一排小图标，不再是文字链接。
        val rendered = texts(view).map { it.text.toString() }
        assertFalse(rendered.any { it.contains("用量") })
        assertFalse(rendered.any { it == "复制" })
        assertFalse(rendered.any { it == "重新生成" })
        val descriptions = listOf("复制", "分享", "朗读", "赞", "踩", "重新生成")
        val icons = descriptions.map { icon(view, it) }
        icons.forEach { assertTrue(it != null) }
        val parent = icons.first()!!.parent as View
        assertTrue(parent.visibility == View.VISIBLE)
        icons.forEach { assertTrue(it!!.parent == parent) }
    }

    @Test
    fun `running turn hides the action icon row`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-running",
                userText = "执行",
                assistantText = "半截回复",
                status = TurnStatus.RUNNING,
            ),
        )

        val copy = icon(view, "复制")
        assertTrue(copy != null)
        // 运行中整行隐藏（父容器 GONE），避免中途复制半截回复。
        assertTrue((copy!!.parent as View).visibility == View.GONE)
    }

    @Test
    fun `running turn shows only the latest status note as one line`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-status",
                userText = "帮我开发一个计算器",
                assistantText = "",
                statusNote = "Manus 任务已创建，正在执行…",
                status = TurnStatus.RUNNING,
            ),
        )

        // 进行中的状态只占一行：最新一条出现，旧状态不贴成气泡。
        val rendered = texts(view).map { it.text.toString() }
        assertEquals(1, rendered.count { it == "Manus 任务已创建，正在执行…" })
        assertFalse(rendered.any { it == "正在向 Manus 发送跟进消息…" })
    }

    @Test
    fun `finished turn drops the status line when the reply arrives`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-status-done",
                statusNote = "Manus 任务已创建，正在执行…",
            ),
        )
        val rendered = texts(view).map { it.text.toString() }
        assertFalse(rendered.any { it == "Manus 任务已创建，正在执行…" })
    }

    @Test
    fun `finished turn keeps the route label while the status line drops`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-route-label",
                speaker = "Example Agent",
                speakerIsBrain = false,
                routeLabel = "直连 Example Agent（大脑已关闭）",
                statusNote = "正在启动 Example Agent…已等待 0 秒",
            ),
        )

        // 路线标注是持久的：轮次结束后仍在，不像 transient 状态行那样消失。
        val rendered = texts(view).map { it.text.toString() }
        assertTrue(rendered.any { it == "直连 Example Agent（大脑已关闭）" })
        assertFalse(rendered.any { it == "正在启动 Example Agent…已等待 0 秒" })
    }

    @Test
    fun `system-only turn still renders its notes as cards`() {
        val view = ChatTurnView(themedContext())
        view.bind(doneTurn(id = "turn-system", userText = "", assistantText = "", notes = listOf("上一轮还在执行。")))

        val rendered = texts(view).map { it.text.toString() }
        assertTrue(rendered.contains("上一轮还在执行。"))
    }

    @Test
    fun `attachment renders as a card with file name and type`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-artifact",
                assistantText = "已经做好了，点卡片打开。",
                attachments = listOf(
                    ChatAttachmentUi(
                        key = "file-1",
                        name = "calculator.html",
                        url = "https://example.invalid/calculator.html",
                    ),
                ),
            ),
        )

        val rendered = texts(view).map { it.text.toString() }
        assertTrue(rendered.contains("calculator.html"))
        assertTrue(rendered.contains("HTML · 临时链接"))
        assertTrue(described(view, "打开附件：calculator.html") != null)
    }

    @Test
    fun `no attachments means no artifact card`() {
        val view = ChatTurnView(themedContext())
        view.bind(doneTurn(id = "turn-no-artifact"))

        val rendered = texts(view).map { it.text.toString() }
        assertFalse(rendered.any { it.contains("临时链接") })
        assertNull(described(view, "打开附件：calculator.html"))
    }

    @Test
    fun `attachment without a link still shows the card without a temp-link note`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-artifact-nolink",
                attachments = listOf(ChatAttachmentUi(key = "file-1", name = "notes.txt", url = null)),
            ),
        )

        val rendered = texts(view).map { it.text.toString() }
        assertTrue(rendered.contains("notes.txt"))
        assertTrue(rendered.contains("文本"))
        assertFalse(rendered.any { it.contains("临时链接") })
    }

    @Test
    fun `like and dislike are mutually exclusive local toggles`() {
        val view = ChatTurnView(themedContext())
        view.bind(doneTurn(id = "turn-feedback"))

        val like = icon(view, "赞")!!
        val dislike = icon(view, "踩")!!
        like.performClick()
        assertTrue(like.isSelected)
        assertFalse(dislike.isSelected)
        dislike.performClick()
        assertFalse(like.isSelected)
        assertTrue(dislike.isSelected)
        dislike.performClick()
        assertFalse(like.isSelected)
        assertFalse(dislike.isSelected)
    }

    @Test
    fun `running turn shows the placeholder in the assistant bubble`() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_WhereverCode,
        )
        val view = ChatTurnView(context)
        view.bind(
            ChatTurnUi(
                id = "turn-placeholder",
                userText = "读 README",
                assistantText = "",
                reasoning = "",
                protocolRedacted = false,
                toolCalls = emptyList(),
                notes = emptyList(),
                status = TurnStatus.RUNNING,
                error = null,
                revision = 1,
                placeholder = "正在连接 DeepSeek…",
            ),
        )

        val rendered = texts(view)
        // V5 C2-3-R1b（v6-app.js:510）：占位先贴在助手气泡里（副文色）。
        val placeholder = rendered.single { it.text.toString() == "正在连接 DeepSeek…" }
        assertTrue(placeholder.visibility == View.VISIBLE)
    }

    @Test
    fun `tool steps render before the answer bubble`() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_WhereverCode,
        )
        val view = ChatTurnView(context)
        view.bind(
            ChatTurnUi(
                id = "turn-order",
                userText = "读 README",
                assistantText = "最终答案",
                reasoning = "",
                protocolRedacted = false,
                toolCalls = listOf(
                    ChatToolCallUi(
                        id = "call-1",
                        name = "read_file",
                        args = "{\"path\": \"README.md\"}",
                        status = ToolCallStatus.SUCCESS,
                        resultSummary = "读取成功",
                    ),
                ),
                notes = emptyList(),
                status = TurnStatus.DONE,
                error = null,
                revision = 1,
            ),
        )

        val rendered = texts(view)
        // V5 C2-3-R1b（v6-collab.js:412）：步骤行插在回答气泡前。
        val toolIndex = rendered.indexOfFirst { it.text.toString() == "read_file" }
        val answerIndex = rendered.indexOfFirst { it.text.toString() == "最终答案" }
        assertTrue(toolIndex >= 0)
        assertTrue(answerIndex >= 0)
        assertTrue(toolIndex < answerIndex)
    }

    @Test
    fun `tool card shows the Chinese action label with the tool name`() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_WhereverCode,
        )
        val view = ChatTurnView(context)
        view.bind(
            ChatTurnUi(
                id = "turn-label",
                userText = "读 README",
                assistantText = "最终答案",
                reasoning = "",
                protocolRedacted = false,
                toolCalls = listOf(
                    ChatToolCallUi(
                        id = "call-1",
                        name = "read_file",
                        args = "{\"path\": \"README.md\"}",
                        status = ToolCallStatus.SUCCESS,
                        resultSummary = "读取成功",
                    ),
                ),
                notes = emptyList(),
                status = TurnStatus.DONE,
                error = null,
                revision = 1,
            ),
        )

        val rendered = texts(view)
        // V5 C2-3-R1b（agent_runners.py:61）：中文动作标签 + 工具名原样保留。
        assertTrue(rendered.any { it.text.toString() == "读取文件" })
        assertTrue(rendered.any { it.text.toString() == "read_file" })
    }

    @Test
    fun `finished turn with two tool calls folds them under one group header`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-group",
                toolCalls = listOf(
                    ChatToolCallUi(id = "call-1", name = "read_file", args = "{}", status = ToolCallStatus.SUCCESS),
                    ChatToolCallUi(id = "call-2", name = "Bash", args = "{}", status = ToolCallStatus.SUCCESS),
                ),
            ),
        )

        // 老项目流式参照（只学思路）：终态轮次工具 ≥2 个收成一行汇总，默认折叠。
        val rendered = texts(view).map { it.text.toString() }
        assertTrue(rendered.contains("已读取 1 个文件、运行 1 条命令"))
        assertFalse(visibleTexts(view).any { it == "read_file" })
        assertFalse(visibleTexts(view).any { it == "Bash" })

        // 点汇总头展开，逐张卡片出现；再点收起。
        val header = described(view, "工具组")!!
        header.performClick()
        assertTrue(visibleTexts(view).any { it == "read_file" })
        assertTrue(visibleTexts(view).any { it == "Bash" })
        header.performClick()
        assertFalse(visibleTexts(view).any { it == "read_file" })
    }

    @Test
    fun `provider JSON error is summarized with raw diagnostic folded and retry available`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(userText = "继续", assistantText = "", status = TurnStatus.ERROR).copy(
                error = "调用 ChatGPT 失败：{\"error\":{\"code\":\"unsupported_country_region_territory\",\"message\":\"unsupported\"}}",
            ),
        )
        val shown = visibleTexts(view).map { it.text.toString() }
        assertTrue(shown.any { it.contains("当前不支持此账号所在的地区") })
        assertTrue(shown.contains("查看原始诊断"))
        assertTrue(shown.contains("重试本轮"))
        assertFalse(shown.any { it.contains("unsupported_country_region_territory") })
        visibleTexts(view).first { it.text == "查看原始诊断" }.performClick()
        assertTrue(visibleTexts(view).any { it.text.contains("unsupported_country_region_territory") })
    }

    @Test
    fun `running turn with two tool calls stays unfolded`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-group-live",
                status = TurnStatus.RUNNING,
                toolCalls = listOf(
                    ChatToolCallUi(id = "call-1", name = "read_file", args = "{}", status = ToolCallStatus.SUCCESS),
                    ChatToolCallUi(id = "call-2", name = "Bash", args = "{}", status = ToolCallStatus.RUNNING),
                ),
            ),
        )

        // 进行中的那组保持展开（老项目 trailing-live 规则），不收汇总头。
        val header = described(view, "工具组")
        assertTrue(header == null || header.visibility == View.GONE)
        assertTrue(visibleTexts(view).any { it == "read_file" })
        assertTrue(visibleTexts(view).any { it == "Bash" })
    }

    @Test
    fun `single tool call never folds`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-group-one",
                toolCalls = listOf(
                    ChatToolCallUi(id = "call-1", name = "read_file", args = "{}", status = ToolCallStatus.SUCCESS),
                ),
            ),
        )

        val header = described(view, "工具组")
        assertTrue(header == null || header.visibility == View.GONE)
        assertTrue(visibleTexts(view).any { it == "read_file" })
    }

    @Test
    fun `group header counts kinds and failures`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-group-counts",
                toolCalls = listOf(
                    ChatToolCallUi(id = "call-1", name = "read_file", args = "{}", status = ToolCallStatus.SUCCESS),
                    ChatToolCallUi(id = "call-2", name = "write_file", args = "{}", status = ToolCallStatus.SUCCESS),
                    ChatToolCallUi(id = "call-3", name = "Bash", args = "{}", status = ToolCallStatus.FAILED),
                ),
            ),
        )

        val rendered = texts(view).map { it.text.toString() }
        assertTrue(rendered.contains("已读取 1 个文件、修改 1 个文件、运行 1 条命令（1 条失败）"))
    }

    @Test
    fun `brain turn with usage shows the usage line and never unknown`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-usage",
                speaker = "DeepSeek",
                speakerIsBrain = true,
                usage = TurnUsage(100, 50),
            ),
        )

        // 大脑说话且真有数字：显示用量行；“未知”二字永不出现。
        val rendered = texts(view).map { it.text.toString() }
        assertTrue(rendered.contains("用量：输入 100 tokens · 输出 50 tokens"))
        assertFalse(rendered.any { it.contains("未知") })
    }

    @Test
    fun `usage with only input shows only input`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-usage-half",
                speaker = "DeepSeek",
                speakerIsBrain = true,
                usage = TurnUsage(100, null),
            ),
        )

        val rendered = texts(view).map { it.text.toString() }
        assertTrue(rendered.contains("用量：输入 100 tokens"))
        assertFalse(rendered.any { it.contains("未知") })
    }

    @Test
    fun `agent turn never shows the usage line even with numbers`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-usage-agent",
                speaker = "Example Agent",
                speakerIsBrain = false,
                usage = TurnUsage(100, 50),
            ),
        )

        // 远端 Agent 没有用量概念：哪怕时间线里误存了数字也不显示——
        // 跟 Example Agent 对话还显示大脑用量，就是 maintainer 说的错信息。
        val rendered = texts(view).map { it.text.toString() }
        assertFalse(rendered.any { it.contains("用量") })
    }

    @Test
    fun `running turn hides the usage line`() {
        val view = ChatTurnView(themedContext())
        view.bind(
            doneTurn(
                id = "turn-usage-running",
                status = TurnStatus.RUNNING,
                speaker = "DeepSeek",
                speakerIsBrain = true,
                usage = TurnUsage(100, 50),
            ),
        )

        val rendered = texts(view).map { it.text.toString() }
        assertFalse(rendered.any { it.contains("用量") })
    }

    @Test
    fun `turn header shows the speaker name`() {
        val view = ChatTurnView(themedContext())
        view.bind(doneTurn(id = "turn-speaker", speaker = "Example Agent", speakerIsBrain = false))

        // 团队模式下就靠轮次抬头分清是哪位成员在回，不是千篇一律的“AGENT”。
        val rendered = texts(view).map { it.text.toString() }
        assertTrue(rendered.contains("Example Agent"))
        assertFalse(rendered.contains("AGENT"))
    }

    @Test
    fun `turn header falls back to AGENT when the speaker is unknown`() {
        val view = ChatTurnView(themedContext())
        view.bind(doneTurn(id = "turn-speaker-unknown"))

        val rendered = texts(view).map { it.text.toString() }
        assertTrue(rendered.contains("AGENT"))
    }
}
