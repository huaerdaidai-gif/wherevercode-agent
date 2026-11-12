package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.ui.HomeScreenController.PlusMenuAction
import wherevercode.agent.ui.HomeScreenController.PlusMenuItem

/**
 * Home wiring test (V5 Phase B C2, batch 2, T4).
 *
 * Pure JVM JUnit 4, no Robolectric: covers the exact call sequence
 * MainActivity performs when wiring the home screen (send gate ->
 * finish/cancel, plus-menu mapping, greeting lifecycle). The model
 * itself is covered by HomeScreenControllerTest; this file pins the
 * wiring contract so a MainActivity refactor cannot silently change
 * the order of calls.
 */
class HomeScreenWiringTest {

    @Test
    fun `plus menu maps every item to a real action`() {
        assertEquals(PlusMenuAction.OPEN_PROJECTS, HomeScreenController.actionFor(PlusMenuItem.PROJECT))
        assertEquals(PlusMenuAction.OPEN_TOOLS, HomeScreenController.actionFor(PlusMenuItem.TOOL))
        assertEquals(PlusMenuAction.SELECT_AGENT, HomeScreenController.actionFor(PlusMenuItem.SELECT_AGENT))
        assertEquals(PlusMenuAction.SELECT_DEVICE, HomeScreenController.actionFor(PlusMenuItem.SELECT_DEVICE))
        // V5 按主流改（maintainer 2026-11-11）：加东西组——相机 / 照片 / 文件 / 网页搜索。
        assertEquals(PlusMenuAction.TAKE_PHOTO, HomeScreenController.actionFor(PlusMenuItem.CAMERA))
        assertEquals(PlusMenuAction.PICK_IMAGE, HomeScreenController.actionFor(PlusMenuItem.PHOTO))
        assertEquals(PlusMenuAction.PICK_FILE, HomeScreenController.actionFor(PlusMenuItem.FILE))
        assertEquals(PlusMenuAction.TOGGLE_WEB_SEARCH, HomeScreenController.actionFor(PlusMenuItem.WEB_SEARCH))
        assertEquals(8, HomeScreenController.PLUS_MENU_ITEMS.size)
    }

    @Test
    fun `wiring send sequence marks the conversation started and hides the greeting`() {
        val controller = HomeScreenController()
        assertTrue(controller.greetingVisible())
        assertTrue(controller.suggestionsVisible())
        // sendPrompt(): TextWatcher already mirrored the input.
        controller.setInput("列出工作区文件")
        assertTrue(controller.canSend())
        assertTrue(controller.beginSend())
        // A second send while one is in flight is refused.
        assertFalse(controller.beginSend())
        // Turn reaches a terminal state: observer calls finishSend().
        controller.finishSend()
        assertEquals("", controller.state().inputText)
        assertFalse(controller.state().isSending)
        assertTrue(controller.state().conversationStarted)
        assertFalse(controller.greetingVisible())
        assertFalse(controller.suggestionsVisible())
        // After the conversation started, the greeting cannot come back.
        assertFalse(controller.restoreGreeting())
    }

    @Test
    fun `cancelled send keeps the input and does not start the conversation`() {
        val controller = HomeScreenController()
        controller.setInput("草稿")
        assertTrue(controller.beginSend())
        controller.cancelSend()
        assertEquals("草稿", controller.state().inputText)
        assertFalse(controller.state().isSending)
        assertFalse(controller.state().conversationStarted)
        assertTrue(controller.greetingVisible())
        assertTrue(controller.canSend())
    }

    @Test
    fun `empty input never begins sending`() {
        val controller = HomeScreenController()
        controller.setInput("   ")
        assertFalse(controller.canSend())
        assertFalse(controller.beginSend())
        assertFalse(controller.state().isSending)
    }

    @Test
    fun `entering an existing session marks the conversation started and closes the plus menu`() {
        val controller = HomeScreenController()
        controller.togglePlusMenu()
        assertTrue(controller.plusMenuOpen())
        controller.onConversationStarted()
        assertTrue(controller.state().conversationStarted)
        assertFalse(controller.plusMenuOpen())
        assertFalse(controller.greetingVisible())
    }

    @Test
    fun `reset after a finished send restores the fresh home`() {
        val controller = HomeScreenController()
        controller.dismissGreeting()
        controller.setInput("x")
        controller.beginSend()
        controller.finishSend()
        assertFalse(controller.greetingVisible())
        controller.reset()
        assertTrue(controller.greetingVisible())
        assertTrue(controller.suggestionsVisible())
        assertEquals("", controller.state().inputText)
        assertFalse(controller.state().conversationStarted)
        assertFalse(controller.state().isSending)
        assertTrue(controller.taskCardCollapsed())
    }

    @Test
    fun `task card stays collapsed by default and toggles`() {
        val controller = HomeScreenController()
        assertTrue(controller.taskCardCollapsed())
        controller.toggleTaskCard()
        assertFalse(controller.taskCardCollapsed())
        controller.toggleTaskCard()
        assertTrue(controller.taskCardCollapsed())
    }
}
