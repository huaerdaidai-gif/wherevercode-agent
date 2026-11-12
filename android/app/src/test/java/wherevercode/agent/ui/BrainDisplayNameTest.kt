package wherevercode.agent.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 大脑显示名（V5 C2-3-R4c，maintainer 05:16/05:22 拍板）：默认 WhereverCode，
 * 可自定义，空白恢复默认；默认会话标签按“<名>会话”走设置。
 * Robolectric（CI 跑）：SharedPreferences 落盘重读 + 默认标签同步。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BrainDisplayNameTest {

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("test_brain_display_name", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test
    fun `default name is WhereverCode`() {
        assertEquals("WhereverCode", BrainDisplayName.DEFAULT_NAME)
        assertEquals("WhereverCode", BrainDisplayName.resolve(null))
        assertEquals("WhereverCode", BrainDisplayName.resolve(""))
        assertEquals("WhereverCode", BrainDisplayName.resolve("   "))
    }

    @Test
    fun `custom name is kept trimmed`() {
        assertEquals("小助手", BrainDisplayName.resolve("  小助手  "))
    }

    @Test
    fun `session label follows the display name`() {
        assertEquals("WhereverCode会话", BrainDisplayName.sessionLabel(null))
        assertEquals("WhereverCode会话", BrainDisplayName.sessionLabel("   "))
        assertEquals("小助手会话", BrainDisplayName.sessionLabel("小助手"))
    }

    @Test
    fun `custom name persists and reads back`() {
        val prefs = prefs()
        BrainDisplayName.save(prefs, "  小助手  ")
        assertEquals("小助手", BrainDisplayName.load(prefs))
        assertEquals("小助手会话", BrainDisplayName.sessionLabel(BrainDisplayName.load(prefs)))
    }

    @Test
    fun `blank input restores the default`() {
        val prefs = prefs()
        BrainDisplayName.save(prefs, "小助手")
        assertEquals("小助手", BrainDisplayName.load(prefs))
        BrainDisplayName.save(prefs, "   ")
        assertEquals("WhereverCode", BrainDisplayName.load(prefs))
        assertFalse(prefs.contains(BrainDisplayName.KEY))
    }

    @Test
    fun `viewModel default label follows the brain name`() {
        val viewModel = ChatViewModel()
        assertEquals("WhereverCode会话", viewModel.currentSessionLabel())
        assertTrue(viewModel.isDefaultSession())
        viewModel.setDefaultSessionLabel(BrainDisplayName.sessionLabel("小助手"))
        assertEquals("小助手会话", viewModel.currentSessionLabel())
        assertTrue(viewModel.isDefaultSession())
    }

    @Test
    fun `renaming the brain does not rename a switched session`() {
        val viewModel = ChatViewModel()
        assertTrue(viewModel.switchSession("s-1", "CodeBuddy"))
        assertFalse(viewModel.isDefaultSession())
        viewModel.setDefaultSessionLabel(BrainDisplayName.sessionLabel("小助手"))
        assertEquals("CodeBuddy", viewModel.currentSessionLabel())
        assertEquals("小助手会话", viewModel.defaultSessionLabel)
    }

    @Test
    fun `opening persisted history replaces a stale cached timeline`() {
        val viewModel = ChatViewModel()
        assertTrue(viewModel.switchSession("conversation-a", "会话 A"))
        viewModel.restoreSessionTurns(listOf(SavedTurn("旧缓存", "旧回复", "DONE", null)))
        assertTrue(viewModel.switchSession("conversation-b", "会话 B"))
        viewModel.replaceSessionTurns(listOf(SavedTurn("数据库正文", "数据库回复", "DONE", null)))
        assertEquals("数据库正文", viewModel.stateNow().turns.single().userText)
        assertEquals("数据库回复", viewModel.stateNow().turns.single().assistantText)

        viewModel.replaceSessionTurns(emptyList())
        assertTrue(viewModel.stateNow().turns.isEmpty())
    }
}
