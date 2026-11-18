package wherevercode.agent

import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import wherevercode.agent.brain.generic.GenericOpenAiPresets

/**
 * 模型页「常用模型服务商」点选回归（2026-11-19 真机走查缺陷 #2）。
 *
 * 197 的病灶：模型页还是设置页里一截时，预设点选把名称/地址填进
 * 「高级配置」折叠区（visibility=gone）里的设置页表单，用户只看到
 * 对话框关掉、页面毫无变化（真机 4 次复现）。脱钩后模型页独立成
 * 页：点选必须把名称与服务地址填进本页可见的手工表单。本测试走
 * 真实点击路径锁死：点按钮 → 列表 8 家齐全 → 点一家 → 本页表单
 * 当场被填。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class ModelPagePresetFillTest {

    @After
    fun tearDown() {
        AgentGraph.resetForTests()
        AndroidApprovalGate.resetForTests()
    }

    @Test
    fun `picking a common provider preset fills the model page form`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
        val activity = controller.get()

        val button = activity.findViewById<View>(R.id.buttonModelCommonProviders)
        assertNotNull("模型页应有「常用模型服务商」按钮", button)
        button.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        // MainActivity 用 AppCompat AlertDialog，经 ShadowDialog 取。
        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull("点按钮应弹出常用服务商列表", dialog)
        assertTrue(dialog!!.isShowing)
        val listView = dialog.listView
        val presets = GenericOpenAiPresets.PRESETS
        assertEquals("常用预设名单应有 ${presets.size} 家", presets.size, listView.adapter.count)
        val names = (0 until listView.adapter.count).map {
            listView.adapter.getItem(it).toString()
        }
        assertEquals(presets.map { it.displayName }, names)

        // 点第一家（OpenAI）：对话框关闭，本页表单当场被填。
        val row = listView.adapter.getView(0, null, listView)
        listView.performItemClick(row, 0, listView.adapter.getItemId(0))
        shadowOf(Looper.getMainLooper()).idle()

        val label = activity.findViewById<EditText>(R.id.modelFormLabel)
        val baseUrl = activity.findViewById<EditText>(R.id.modelFormBaseUrl)
        assertEquals(presets[0].displayName, label.text.toString())
        assertEquals(presets[0].baseUrl, baseUrl.text.toString())

        controller.pause().stop().destroy()
    }

    @Test
    fun `picking the last common preset also fills the form`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
        val activity = controller.get()

        activity.findViewById<View>(R.id.buttonModelCommonProviders).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowDialog.getLatestDialog() as? AlertDialog
        assertNotNull(dialog)
        val listView = dialog!!.listView
        val presets = GenericOpenAiPresets.PRESETS
        val last = presets.lastIndex
        val row = listView.adapter.getView(last, null, listView)
        listView.performItemClick(row, last, listView.adapter.getItemId(last))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            presets[last].displayName,
            activity.findViewById<EditText>(R.id.modelFormLabel).text.toString(),
        )
        assertEquals(
            presets[last].baseUrl,
            activity.findViewById<EditText>(R.id.modelFormBaseUrl).text.toString(),
        )

        controller.pause().stop().destroy()
    }
}
