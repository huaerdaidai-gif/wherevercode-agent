package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTextSanitizerTest {

    @Test
    fun `complete DSML block is removed and never becomes prose`() {
        val raw = """
            好的，我先读文件。
            <｜DSML｜function_calls>
            <｜DSML｜invoke name="read_file">
            <｜DSML｜parameter name="path" string="true">README.md</｜DSML｜parameter>
            </｜DSML｜invoke>
            </｜DSML｜function_calls>
            然后继续回答。
        """.trimIndent()

        val result = ChatTextSanitizer.sanitize(raw)

        assertTrue(result.protocolRedacted)
        assertTrue(result.text.contains("好的，我先读文件。"))
        assertTrue(result.text.contains("然后继续回答。"))
        assertFalse(result.text.contains("DSML"))
        assertFalse(result.text.contains("read_file"))
        assertFalse(result.text.contains("README.md"))
        assertFalse(result.text.contains("function_calls"))
    }

    @Test
    fun `incomplete streamed DSML block is hidden until the closing tag arrives`() {
        val partial = "看这里 <｜DSML｜function_calls>\n<｜DSML｜invoke name=\"read_file\">"
        val result = ChatTextSanitizer.sanitize(partial)
        assertEquals("看这里", result.text)
        assertTrue(result.protocolRedacted)
    }

    @Test
    fun `a split opening marker at the stream tail is hidden`() {
        val result = ChatTextSanitizer.sanitize("看这里 <｜DSM")
        assertEquals("看这里", result.text)
        assertTrue(result.protocolRedacted)
    }

    @Test
    fun `think block is extracted into reasoning, not prose`() {
        val result = ChatTextSanitizer.sanitize("<think>先检查目录</think>目录是空的。")
        assertEquals("目录是空的。", result.text)
        assertEquals("先检查目录", result.reasoning)
    }

    @Test
    fun `ordinary code and comparisons are not damaged`() {
        val raw = "if (a < b) {\n  print(\"<div class=\\\"x\\\">|管道|</div>\")\n}"
        val result = ChatTextSanitizer.sanitize(raw)
        assertEquals(raw, result.text)
        assertFalse(result.protocolRedacted)
    }

    @Test
    fun `ascii DSML bars are handled too`() {
        val raw = "前文 <|DSML|function_calls><|DSML|invoke name=\"list_files\"></|DSML|invoke></|DSML|function_calls> 后文"
        val result = ChatTextSanitizer.sanitize(raw)
        assertEquals("前文  后文", result.text)
        assertTrue(result.protocolRedacted)
    }

    @Test
    fun `special sentence tokens are stripped`() {
        val result = ChatTextSanitizer.sanitize("<｜begin▁of▁sentence｜>你好<｜end▁of▁sentence｜>")
        assertEquals("你好", result.text)
    }

    /**
     * V5 修复（2026-11-11）：老项目 v6-core.js:520 同规则——首个仅为
     * 「记住：」的行，从该行起全部截掉（Agent 记忆提示不是回答正文）。
     */
    @Test
    fun `strips bare memory marker line and everything after it`() {
        val result = ChatTextSanitizer.sanitize("这是回答。\n记住：\n明天开会\n带上电脑")
        assertEquals("这是回答。", result.text)
    }

    @Test
    fun `strips half-width colon memory marker too`() {
        val result = ChatTextSanitizer.sanitize("回答。\n记住:\n后面的不要")
        assertEquals("回答。", result.text)
    }

    @Test
    fun `keeps lines that mention memory inline`() {
        val raw = "记住：明天开会\n请记住：带上电脑"
        val result = ChatTextSanitizer.sanitize(raw)
        assertEquals(raw, result.text)
    }

    /**
     * V5 修复（2026-11-11 回复杂音）：agy 的 glog 启动行不进正文，真回复完整保留。
     * 主修复在 Hand 侧分开抓 stderr，这里是 App 侧纵深。
     */
    @Test
    fun `drops agy glog startup line and keeps the real reply`() {
        val raw = "ERROR: logging before google.Init: I1111 08:40:06.036062 1 server_zerog.go:46] Initialized Android local timezone to Asia/Shanghai\n在的！请问有什么我可以帮你的？"
        val result = ChatTextSanitizer.sanitize(raw)
        assertEquals("在的！请问有什么我可以帮你的？", result.text)
        assertFalse(result.text.contains("google.Init"))
        assertFalse(result.text.contains("server_zerog"))
    }

    @Test
    fun `drops bare glog lines from any Go binary`() {
        val raw = "I1111 08:47:05.558401 1 server_zerog.go:46] Initialized Android local timezone to Asia/Shanghai\n在的！"
        val result = ChatTextSanitizer.sanitize(raw)
        assertEquals("在的！", result.text)
    }

    @Test
    fun `drops codex stdin notice and version banner`() {
        val raw = "Reading additional input from stdin...\nOpenAI Codex v0.166.0-alpha.5.1\n在的。"
        val result = ChatTextSanitizer.sanitize(raw)
        assertEquals("在的。", result.text)
    }

    @Test
    fun `drops claude-code warning lines`() {
        val raw = "[claude-code:unrecognized_model] {\"model\":\"deepseek-flash\",\"query_source\":\"sdk\"}\n在的，有什么需要我帮忙的？"
        val result = ChatTextSanitizer.sanitize(raw)
        assertEquals("在的，有什么需要我帮忙的？", result.text)
    }

    /**
     * 分层界定：codex 的配置 dump / transcript 行格式太像正常正文，App 侧
     * 不碰，全靠 Hand 侧 stderr 分离。这里锁定“App 侧不删它们”，防止以后
     * 有人把过滤放宽、误伤正文。
     */
    @Test
    fun `does not strip codex config or transcript lines in app filter`() {
        val raw = "workdir: /Users/example-user/v5-codex-work\nmodel: gpt-6-luna\ntokens used\n2,947\n在的。"
        val result = ChatTextSanitizer.sanitize(raw)
        assertEquals(raw, result.text)
    }
}
