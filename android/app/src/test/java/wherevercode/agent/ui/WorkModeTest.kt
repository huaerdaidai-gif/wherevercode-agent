package wherevercode.agent.ui

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 工作模式接线口子测试（V5 C2-3-R1，maintainer 02:42 叠加纠正）。
 *
 * 只断言模式 id 的唯一事实来源与持久化位：6 种模式 id 唯一、默认对话协作、
 * 只有对话协作已实现、非法存量回默认。切换 UI、终端/编辑器/监控/
 * 自动驾驶/黑客流式都是后续批次，不在此断言。
 */
@RunWith(RobolectricTestRunner::class)
class WorkModeTest {

    private fun testPrefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("test_work_mode", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    @Test
    fun `six modes have unique ids and only chat collab is implemented`() {
        val ids = WorkMode.values().map { it.id }
        assertEquals(6, ids.distinct().size)
        assertEquals(WorkMode.CHAT_COLLAB, WorkMode.DEFAULT)
        assertTrue(WorkMode.CHAT_COLLAB.implemented)
        assertTrue(WorkMode.values().filter { it != WorkMode.CHAT_COLLAB }.none { it.implemented })
    }

    @Test
    fun `unknown mode id resolves to null`() {
        assertNull(WorkMode.fromId("bogus-mode"))
        assertNull(WorkMode.fromId(null))
        assertEquals(WorkMode.DEV, WorkMode.fromId("dev"))
    }

    @Test
    fun `mode id persists and unknown stored values fall back to default`() {
        val prefs = testPrefs()
        assertEquals(WorkMode.DEFAULT, WorkMode.loadModeId(prefs))

        WorkMode.saveModeId(prefs, WorkMode.DEV)
        assertEquals(WorkMode.DEV, WorkMode.loadModeId(prefs))

        prefs.edit().putString(WorkMode.KEY_MODE_ID, "bogus-mode").commit()
        assertEquals(WorkMode.DEFAULT, WorkMode.loadModeId(prefs))
    }
}
