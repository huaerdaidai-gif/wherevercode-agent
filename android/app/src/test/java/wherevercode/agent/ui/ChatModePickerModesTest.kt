package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 对话模式切换列表（大脑降级，2026-11-17）：主线模式切换只列单兵
 * 对话与团队对话；模型对话不在主线露出，只从「全部功能」的大脑
 * 枢纽进入。ChatMode.BRAIN 本身保留为模型对话的内部模式 id（经
 * 枢纽进入的模型对话仍按 BRAIN 推导，见 ChatMode.forConversation），
 * 界面文案是「模型对话」——原「大脑对话」模式已按 Funk 2026-11-17
 * 定版取消：主线对话是自选模式，用户选哪个 Agent 或哪个模型，
 * 就由它对话（见 MainActivity 对话方选择器）。
 */
class ChatModePickerModesTest {

    @Test
    fun `picker modes exclude brain`() {
        assertEquals(
            listOf(ChatMode.SINGLE_AGENT, ChatMode.TEAM),
            ChatMode.pickerModes(),
        )
        assertFalse(ChatMode.pickerModes().contains(ChatMode.BRAIN))
    }

    @Test
    fun `brain mode still derived for model conversation`() {
        assertEquals(ChatMode.BRAIN, ChatMode.forConversation(null))
        assertEquals("模型对话", ChatMode.BRAIN.label)
    }
}
