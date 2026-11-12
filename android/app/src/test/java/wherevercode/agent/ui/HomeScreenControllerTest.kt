package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.ui.HomeScreenController.PlusMenuAction
import wherevercode.agent.ui.HomeScreenController.PlusMenuItem

/**
 * 首页模型测试（Phase B C2，骨架）。
 *
 * 依据：V5-UI.md §二——初次问候可关闭，开始聊天后转为消息流；
 * 底部固定输入框；任务状态卡默认折叠。
 * 纯 JVM 测试，不依赖 Android。
 */
class HomeScreenControllerTest {

    @Test
    fun `greeting and suggestions visible by default`() {
        val home = HomeScreenController()

        assertTrue(home.greetingVisible())
        assertTrue(home.suggestionsVisible())
    }

    @Test
    fun `dismiss greeting hides greeting only`() {
        val home = HomeScreenController()

        home.dismissGreeting()

        assertFalse(home.greetingVisible())
        assertTrue(home.suggestionsVisible())
    }

    @Test
    fun `restore greeting brings it back before conversation starts`() {
        val home = HomeScreenController()
        home.dismissGreeting()

        assertTrue(home.restoreGreeting())
        assertTrue(home.greetingVisible())
    }

    @Test
    fun `dismiss suggestions hides suggestions only`() {
        val home = HomeScreenController()

        home.dismissSuggestions()

        assertFalse(home.suggestionsVisible())
        assertTrue(home.greetingVisible())
    }

    @Test
    fun `starting a conversation hides greeting and suggestions for good`() {
        val home = HomeScreenController()

        home.onConversationStarted()

        assertFalse(home.greetingVisible())
        assertFalse(home.suggestionsVisible())
        // 开始聊天后首页已是消息流，恢复问候是无操作。
        assertFalse(home.restoreGreeting())
        assertFalse(home.greetingVisible())
    }

    @Test
    fun `canSend requires non-blank input and not sending`() {
        val home = HomeScreenController()

        assertFalse(home.canSend())
        home.setInput("   ")
        assertFalse(home.canSend())
        home.setInput("帮我看看项目")
        assertTrue(home.canSend())
    }

    @Test
    fun `beginSend with blank input is a no-op`() {
        val home = HomeScreenController()

        assertFalse(home.beginSend())
        assertFalse(home.state().isSending)
    }

    @Test
    fun `beginSend enters sending and blocks a second send`() {
        val home = HomeScreenController()
        home.setInput("你好")

        assertTrue(home.beginSend())
        assertTrue(home.state().isSending)
        assertFalse(home.canSend())
        assertFalse(home.beginSend())
    }

    @Test
    fun `finishSend clears input and marks conversation started`() {
        val home = HomeScreenController()
        home.setInput("你好")
        home.beginSend()

        home.finishSend()

        assertEquals("", home.state().inputText)
        assertFalse(home.state().isSending)
        assertTrue(home.state().conversationStarted)
        assertFalse(home.greetingVisible())
        assertFalse(home.canSend())
    }

    @Test
    fun `cancelSend keeps input and does not start conversation`() {
        val home = HomeScreenController()
        home.setInput("你好")
        home.beginSend()

        home.cancelSend()

        assertEquals("你好", home.state().inputText)
        assertFalse(home.state().isSending)
        assertFalse(home.state().conversationStarted)
        assertTrue(home.greetingVisible())
        assertTrue(home.canSend())
    }

    @Test
    fun `clearInput empties the input box`() {
        val home = HomeScreenController()
        home.setInput("草稿")

        home.clearInput()

        assertEquals("", home.state().inputText)
        assertFalse(home.canSend())
    }

    @Test
    fun `task card is collapsed by default and toggles both ways`() {
        val home = HomeScreenController()

        assertTrue(home.taskCardCollapsed())
        home.toggleTaskCard()
        assertFalse(home.taskCardCollapsed())
        home.toggleTaskCard()
        assertTrue(home.taskCardCollapsed())
    }

    @Test
    fun `plus menu toggles and closes on conversation start and send`() {
        val home = HomeScreenController()

        assertFalse(home.plusMenuOpen())
        home.togglePlusMenu()
        assertTrue(home.plusMenuOpen())
        home.togglePlusMenu()
        assertFalse(home.plusMenuOpen())

        home.togglePlusMenu()
        home.onConversationStarted()
        assertFalse(home.plusMenuOpen())

        home.setInput("hi")
        home.togglePlusMenu()
        assertTrue(home.beginSend())
        assertFalse(home.plusMenuOpen())
    }

    @Test
    fun `plus menu lists only real actions, no placeholders`() {
        // V5 按主流改（maintainer 2026-11-11）：加东西组在上、切换上下文组在下。
        assertEquals(
            listOf(
                PlusMenuItem.CAMERA,
                PlusMenuItem.PHOTO,
                PlusMenuItem.FILE,
                PlusMenuItem.WEB_SEARCH,
                PlusMenuItem.PROJECT,
                PlusMenuItem.SELECT_AGENT,
                PlusMenuItem.SELECT_DEVICE,
                PlusMenuItem.TOOL,
            ),
            HomeScreenController.PLUS_MENU_ITEMS,
        )
        // 加东西组：相机 / 照片 / 文件三个大格子 + 网页搜索开关行；
        // 切换上下文组：项目 / 选 Agent / 选设备三个大格子 + 工具与连接行。
        assertEquals(
            listOf(PlusMenuItem.CAMERA, PlusMenuItem.PHOTO, PlusMenuItem.FILE, PlusMenuItem.PROJECT, PlusMenuItem.SELECT_AGENT, PlusMenuItem.SELECT_DEVICE),
            HomeScreenController.PLUS_MENU_ITEMS.filter { it.isTile },
        )
        assertEquals(
            listOf(PlusMenuItem.WEB_SEARCH, PlusMenuItem.TOOL),
            HomeScreenController.PLUS_MENU_ITEMS.filter { !it.isTile },
        )
        assertEquals(
            listOf(PlusMenuItem.CAMERA, PlusMenuItem.PHOTO, PlusMenuItem.FILE, PlusMenuItem.WEB_SEARCH),
            HomeScreenController.PLUS_MENU_ADD_ITEMS,
        )
        assertEquals(
            listOf(PlusMenuItem.PROJECT, PlusMenuItem.SELECT_AGENT, PlusMenuItem.SELECT_DEVICE, PlusMenuItem.TOOL),
            HomeScreenController.PLUS_MENU_CONTEXT_ITEMS,
        )
        // 新项的路由：相机→拍照、照片→选图、文件→选文件、网页搜索→开关。
        assertEquals(PlusMenuAction.TAKE_PHOTO, HomeScreenController.actionFor(PlusMenuItem.CAMERA))
        assertEquals(PlusMenuAction.PICK_IMAGE, HomeScreenController.actionFor(PlusMenuItem.PHOTO))
        assertEquals(PlusMenuAction.PICK_FILE, HomeScreenController.actionFor(PlusMenuItem.FILE))
        assertEquals(PlusMenuAction.TOGGLE_WEB_SEARCH, HomeScreenController.actionFor(PlusMenuItem.WEB_SEARCH))
    }

    @Test
    fun `work mode defaults to chat collab and rejects unknown ids`() {
        val home = HomeScreenController()
        assertEquals(WorkMode.CHAT_COLLAB, home.workMode())
        assertEquals(WorkMode.CHAT_COLLAB.id, home.state().workModeId)

        assertTrue(home.setWorkModeId(WorkMode.DEV.id))
        assertEquals(WorkMode.DEV, home.workMode())

        assertFalse(home.setWorkModeId("bogus-mode"))
        assertEquals(WorkMode.DEV, home.workMode())
    }

    @Test
    fun `reset keeps the work mode`() {
        val home = HomeScreenController()
        home.setWorkModeId(WorkMode.DEV.id)
        home.dismissGreeting()

        home.reset()

        assertEquals(WorkMode.DEV.id, home.state().workModeId)
        assertTrue(home.greetingVisible())
    }

    @Test
    fun `reset restores a fresh home screen`() {
        val home = HomeScreenController()
        home.dismissGreeting()
        home.dismissSuggestions()
        home.setInput("你好")
        home.beginSend()
        home.finishSend()
        home.toggleTaskCard()
        home.togglePlusMenu()

        home.reset()

        assertTrue(home.greetingVisible())
        assertTrue(home.suggestionsVisible())
        assertEquals("", home.state().inputText)
        assertFalse(home.state().isSending)
        assertFalse(home.state().conversationStarted)
        assertTrue(home.taskCardCollapsed())
        assertFalse(home.plusMenuOpen())
    }

    @Test
    fun `greeting is always the fixed plain hello`() {
        // V5 C2-3-R4c（隐私修正）：问候固定“你好”，不读已存身份、
        // 不显示真人名字；没有带名分支可测。
        assertEquals("你好", HomeScreenController.greeting())
    }
}
