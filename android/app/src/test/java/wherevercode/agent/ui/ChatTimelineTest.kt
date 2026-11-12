package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {

    @Test
    fun `tool call and result are separate layers from assistant prose`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("读 README", "DeepSeek")
        timeline.appendAssistantText(turnId, "我先读文件。")
        timeline.addToolCall(turnId, "call-1", "read_file", mapOf("path" to "README.md"))
        timeline.addToolResult(turnId, "call-1", true, "读取成功，42 字节")
        timeline.appendAssistantText(turnId, "文件内容是蓝莓派 42 号。")
        timeline.finishTurn(turnId, TurnStatus.DONE)

        val turn = timeline.snapshot().turns.single()
        assertEquals("我先读文件。文件内容是蓝莓派 42 号。", turn.assistantText)
        assertFalse(turn.assistantText.contains("read_file"))
        assertFalse(turn.assistantText.contains("README.md"))
        assertEquals(1, turn.toolCalls.size)
        val call = turn.toolCalls.single()
        assertEquals("read_file", call.name)
        assertTrue(call.args.contains("README.md"))
        assertEquals(ToolCallStatus.SUCCESS, call.status)
        assertTrue(call.resultSummary!!.contains("读取成功"))
    }

    @Test
    fun `raw DSML never enters the assistant text`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("执行", "DeepSeek")
        timeline.appendAssistantText(
            turnId,
            "开始。\n<｜DSML｜function_calls><｜DSML｜invoke name=\"read_file\">" +
                "<｜DSML｜parameter name=\"path\" string=\"true\">README.md</｜DSML｜parameter>" +
                "</｜DSML｜invoke></｜DSML｜function_calls>",
        )
        timeline.appendAssistantText(turnId, "继续。")
        timeline.finishTurn(turnId, TurnStatus.DONE)

        val turn = timeline.snapshot().turns.single()
        assertFalse(turn.assistantText.contains("DSML"))
        assertFalse(turn.assistantText.contains("README.md"))
        assertTrue(turn.assistantText.contains("开始。"))
        assertTrue(turn.assistantText.contains("继续。"))
        assertTrue(turn.protocolRedacted)
    }

    @Test
    fun `reasoning is a separate field from prose`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("想一下", "DeepSeek")
        timeline.appendReasoning(turnId, "先列目录")
        timeline.appendAssistantText(turnId, "目录里有 A。")
        timeline.finishTurn(turnId, TurnStatus.DONE)

        val turn = timeline.snapshot().turns.single()
        assertEquals("目录里有 A。", turn.assistantText)
        assertTrue(turn.reasoning.contains("先列目录"))
        assertFalse(turn.assistantText.contains("先列目录"))
    }

    @Test
    fun `running tools are cancelled when the turn ends`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("跑命令", "DeepSeek")
        timeline.addToolCall(turnId, "call-1", "run_command", mapOf("argv" to listOf("python3", "x.py")))
        timeline.finishTurn(turnId, TurnStatus.CANCELLED)

        val call = timeline.snapshot().turns.single().toolCalls.single()
        assertEquals(ToolCallStatus.CANCELLED, call.status)
    }

    @Test
    fun `system notes are standalone turns`() {
        val timeline = ChatTimeline()
        timeline.beginSystemTurn("上一轮还在执行。")
        val state = timeline.snapshot()
        assertEquals(1, state.turns.size)
        assertEquals(TurnStatus.DONE, state.turns.single().status)
        assertEquals("上一轮还在执行。", state.turns.single().notes.single())
        assertFalse(state.running)
    }

    @Test
    fun `placeholder is set when the turn begins`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("读 README", "DeepSeek")

        val turn = timeline.snapshot().turns.single()
        assertEquals("正在连接 DeepSeek…", turn.placeholder)
        assertEquals("", turn.assistantText)
        assertEquals(TurnStatus.RUNNING, turn.status)
        assertEquals(turnId, turn.id)
    }

    @Test
    fun `first non-empty delta clears the placeholder`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("读 README", "DeepSeek")
        timeline.appendAssistantText(turnId, "文件内容是蓝莓派。")
        timeline.finishTurn(turnId, TurnStatus.DONE)

        val turn = timeline.snapshot().turns.single()
        assertEquals(null, turn.placeholder)
        assertEquals("文件内容是蓝莓派。", turn.assistantText)
    }

    @Test
    fun `error before any text rewrites the placeholder and clears the error field`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("读 README", "DeepSeek")
        timeline.finishTurn(turnId, TurnStatus.ERROR, "deepseek_http_500")

        val turn = timeline.snapshot().turns.single()
        assertEquals(null, turn.placeholder)
        assertEquals("deepseek_http_500", turn.assistantText)
        assertEquals(null, turn.error)
        assertEquals(TurnStatus.ERROR, turn.status)
    }

    @Test
    fun `cancelled finish clears the placeholder without persisting it`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("读 README", "DeepSeek")
        timeline.finishTurn(turnId, TurnStatus.CANCELLED)

        val turn = timeline.snapshot().turns.single()
        assertEquals(null, turn.placeholder)
        assertEquals("", turn.assistantText)
        assertEquals(null, turn.error)
    }

    @Test
    fun `open secret tail at a chunk boundary is masked in the snapshot`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("读密钥", "DeepSeek")
        timeline.appendAssistantText(turnId, "{\"api_key\": \"sk-abc")
        timeline.finishTurn(turnId, TurnStatus.DONE)

        val turn = timeline.snapshot().turns.single()
        assertFalse(turn.assistantText.contains("sk-abc"))
        assertEquals(null, turn.placeholder)
    }

    @Test
    fun `status note keeps only the latest line and never becomes a note`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("帮我开发一个计算器", "Manus")
        timeline.appendStatusNote(turnId, "正在向 Manus 发送跟进消息…")
        timeline.appendStatusNote(turnId, "Manus 任务已创建，正在执行…")
        timeline.appendNote(turnId, "工具已执行，但模型没有给出最终文字回复；请查看上方工具结果。")

        val turn = timeline.snapshot().turns.single()
        assertEquals("Manus 任务已创建，正在执行…", turn.statusNote)
        assertEquals(1, turn.notes.size)
        assertTrue(turn.notes.single().contains("工具已执行"))
    }

    @Test
    fun `attachments dedupe by key and survive the snapshot`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("帮我开发一个计算器", "Manus")
        val first = ChatAttachmentUi("file-1", "calculator.html", "https://example.invalid/a")
        timeline.addAttachments(turnId, listOf(first))
        timeline.addAttachments(turnId, listOf(first))
        timeline.addAttachments(
            turnId,
            listOf(ChatAttachmentUi("file-2", "notes.txt", null)),
        )

        val turn = timeline.snapshot().turns.single()
        assertEquals(2, turn.attachments.size)
        assertEquals("calculator.html", turn.attachments[0].name)
        assertEquals("https://example.invalid/a", turn.attachments[0].url)
        assertEquals("notes.txt", turn.attachments[1].name)
        assertEquals(null, turn.attachments[1].url)
    }

    @Test
    fun `setUsage stores the last numbers and never estimates`() {
        val timeline = ChatTimeline()
        val turnId = timeline.beginUserTurn("读 README", "DeepSeek")
        timeline.setUsage(turnId, 100, 50)

        var turn = timeline.snapshot().turns.single()
        assertEquals(TurnUsage(100, 50), turn.usage)

        // 后一次覆盖前一次；只存返回的数字，绝不估算、绝不补零。
        timeline.setUsage(turnId, null, 20)
        turn = timeline.snapshot().turns.single()
        assertEquals(TurnUsage(null, 20), turn.usage)
    }

    @Test
    fun `beginUserTurn records the speaker and whether it is a brain`() {
        val timeline = ChatTimeline()
        val brainTurn = timeline.beginUserTurn("读 README", "DeepSeek")
        val agentTurn = timeline.beginUserTurn("在吗", "Example Agent", false)

        val turns = timeline.snapshot().turns
        val brain = turns.single { it.id == brainTurn }
        assertEquals("DeepSeek", brain.speaker)
        assertTrue(brain.speakerIsBrain)
        val agent = turns.single { it.id == agentTurn }
        assertEquals("Example Agent", agent.speaker)
        assertFalse(agent.speakerIsBrain)
        assertEquals("正在连接 Example Agent…", agent.placeholder)
    }
}
