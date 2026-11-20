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

/**
 * 照片批 F：设置首页与 iOS 设置页同构——用户卡（平台状态现取）+
 * 平台连接/账户/应用三节菜单；行点击路由到分节、独立页或诚实
 * 「待开发」；大脑行在应用节最末（角落），点开进大脑分节。
 */
@RunWith(RobolectricTestRunner::class)
class SettingsHomeControllerTest {

    private class Recorder {
        var backCount = 0
        var section: SettingsSections.Section? = null
        var screen: String? = null
        var themeCount = 0
        var todo: String? = null
    }

    private fun controller(rec: Recorder, status: String = "平台未配置（本地优先）") =
        SettingsHomeController(
            RuntimeEnvironment.getApplication(),
            onBack = { rec.backCount++ },
            onOpenSection = { rec.section = it },
            onOpenScreen = { rec.screen = it },
            onCycleTheme = { rec.themeCount++ },
            onTodo = { rec.todo = it },
            platformStatus = { status },
        )

    private fun allTexts(view: View): List<String> {
        val out = mutableListOf<String>()
        if (view is TextView) out += view.text.toString()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) out += allTexts(view.getChildAt(i))
        }
        return out
    }

    /** 点某一行：找到标题 TextView，点它的父行容器。 */
    private fun clickRow(root: View, title: String) {
        fun find(view: View): View? {
            if (view is TextView && view.text.toString() == title) {
                return view.parent as? View
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    find(view.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
        val row = find(root) ?: error("row not found: $title")
        row.performClick()
    }

    @Test
    fun `home renders the three sections and the platform status`() {
        val c = controller(Recorder(), status = "平台已配置（凭证已存本机）")
        val texts = allTexts(c.root)
        assertTrue(texts.any { it == "设置" })
        assertTrue(texts.any { it == "平台账户" })
        assertTrue(texts.any { it == "平台已配置（凭证已存本机）" })
        assertTrue(texts.any { it == "平台连接" })
        assertTrue(texts.any { it == "账户" })
        assertTrue(texts.any { it == "应用" })
        assertTrue(texts.any { it == "模型配置" })
        assertTrue(texts.any { it == "仓库授权" })
        assertTrue(texts.any { it == "大脑" })
    }

    @Test
    fun `brain row is the last row of the app section`() {
        val c = controller(Recorder())
        val texts = allTexts(c.root)
        val appHeader = texts.indexOf("应用")
        val brain = texts.indexOf("大脑")
        assertTrue(appHeader >= 0 && brain > appHeader)
        assertEquals("大脑", texts.last { it in setOf("模型配置", "仓库授权", "大脑", "关于", "开发者选项") })
    }

    @Test
    fun `rows route to sections screens theme and honest todos`() {
        val rec = Recorder()
        val c = controller(rec)
        clickRow(c.root, "大脑")
        assertEquals(SettingsSections.Section.BRAIN, rec.section)
        clickRow(c.root, "跨端同步")
        assertEquals(SettingsSections.Section.HAND, rec.section)
        clickRow(c.root, "连接管理")
        assertEquals(SettingsSections.Section.CONNECTIONS, rec.section)
        clickRow(c.root, "模型配置")
        assertEquals(DrawerController.ID_MODELS, rec.screen)
        clickRow(c.root, "插件与工具")
        assertEquals(DrawerController.ID_MCP_PLUGINS, rec.screen)
        clickRow(c.root, "切换深色模式")
        assertEquals(1, rec.themeCount)
        clickRow(c.root, "Webhook 唤醒与自动触发")
        assertEquals("Webhook 唤醒与自动触发", rec.todo)
    }
}
