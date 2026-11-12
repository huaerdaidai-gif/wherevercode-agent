package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具中文动作标签测试（V5 C2-3-R1b：老项目 agent_runners.py:61）。
 *
 * 纯 JVM 测试：老映射六项 + 新注册表四项逐一断言；未列出的工具名
 * 回落为工具名本身，绝不编造。
 */
class ToolActionLabelsTest {

    @Test
    fun `old tool names map to Chinese actions`() {
        assertEquals("读取", ToolActionLabels.labelFor("Read"))
        assertEquals("修改", ToolActionLabels.labelFor("Edit"))
        assertEquals("修改", ToolActionLabels.labelFor("Write"))
        assertEquals("运行", ToolActionLabels.labelFor("Bash"))
        assertEquals("搜索", ToolActionLabels.labelFor("Grep"))
        assertEquals("查找文件", ToolActionLabels.labelFor("Glob"))
    }

    @Test
    fun `new registry tools map to Chinese actions`() {
        assertEquals("读取文件", ToolActionLabels.labelFor("read_file"))
        assertEquals("修改文件", ToolActionLabels.labelFor("write_file"))
        assertEquals("运行命令", ToolActionLabels.labelFor("run_command"))
        assertEquals("列出文件", ToolActionLabels.labelFor("list_files"))
    }

    @Test
    fun `unlisted tools fall back to their own name`() {
        assertEquals("dispatch_task", ToolActionLabels.labelFor("dispatch_task"))
        assertEquals("ask_user", ToolActionLabels.labelFor("ask_user"))
        assertEquals("some_future_tool", ToolActionLabels.labelFor("some_future_tool"))
    }
}
