package wherevercode.agent.ui

import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.R

/**
 * 模型页解耦布局回归（2026-11-19，定版：大脑已砍开放一边，模型页
 * 不许拿大脑分节改名充数）：
 * - screen_models 是独立一页：添加块（两预设分开、自动发现、智能
 *   路由、本页表单）在上、已添加块在下，全页无“大脑”字样。
 * - screen_settings 恢复原样：预设块回到高级配置之后（不再夹在
 *   大脑名称与高级配置之间），列表标题仍是“已添加的大脑”。
 * 全程 fake：只充气布局断言，零真实调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class ModelPageLayoutTest {

    private fun themed(): ContextThemeWrapper = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        R.style.Theme_WhereverCode,
    )

    /** 布局里全部文案（按文档顺序；含 EditText 的 hint）。 */
    private fun allTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) {
                out += v.text.toString()
                if (v is EditText) out += v.hint?.toString().orEmpty()
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test
    fun `model page is its own two block page without brain wording`() {
        val root = LayoutInflater.from(themed()).inflate(R.layout.screen_models, null)
        // 添加块控件齐全：两预设分开、自动发现、智能路由、本页表单。
        assertNotNull(root.findViewById<View>(R.id.buttonModelCommonProviders))
        assertNotNull(root.findViewById<View>(R.id.buttonModelFreeProviders))
        assertNotNull(root.findViewById<View>(R.id.buttonModelDiscover))
        assertNotNull(root.findViewById<View>(R.id.checkModelRoutingPage))
        assertNotNull(root.findViewById<View>(R.id.checkModelRoutingAllowPaid))
        assertNotNull(root.findViewById<View>(R.id.buttonModelRoutingPool))
        assertNotNull(root.findViewById<View>(R.id.modelFormLabel))
        assertNotNull(root.findViewById<View>(R.id.modelFormBaseUrl))
        assertNotNull(root.findViewById<View>(R.id.modelFormModel))
        assertNotNull(root.findViewById<View>(R.id.modelFormApiKey))
        assertNotNull(root.findViewById<View>(R.id.buttonTestModelModels))
        assertNotNull(root.findViewById<View>(R.id.buttonSaveModel))
        // 已添加块：模型自己的列表容器。
        assertNotNull(root.findViewById<View>(R.id.modelListContainer))

        val texts = allTexts(root)
        val joined = texts.joinToString("\n")
        assertTrue(joined.contains("添加模型"))
        assertTrue(joined.contains("已添加的模型"))
        assertTrue(joined.contains("常用模型服务商"))
        assertTrue(joined.contains("免费 token 模型服务商"))
        assertTrue(joined.contains("自动发现设备上的模型"))
        // 模型页不借大脑：全页文案不出现“大脑”。
        assertFalse("模型页文案不得出现“大脑”：$joined", joined.contains("大脑"))
        // 两块顺序：添加块在已添加块之前。
        assertTrue(texts.indexOf("添加模型") < texts.indexOf("已添加的模型"))
    }

    /**
     * 197 缺陷 #2 的布局面锁死：旧页把预设要填的表单藏在
     * visibility=gone 的折叠容器里，点选填了个寂寞。新页表单必须
     * 从字段到根整条祖先链全可见——谁再把表单包进折叠区，此测试
     * 先红。
     */
    @Test
    fun `model form fields are not hidden inside a collapsed container`() {
        val root = LayoutInflater.from(themed()).inflate(R.layout.screen_models, null)
        for (id in listOf(
            R.id.modelFormLabel, R.id.modelFormBaseUrl, R.id.modelFormModel,
            R.id.modelFormApiKey, R.id.buttonSaveModel,
        )) {
            var v: View? = root.findViewById(id)
            assertNotNull(v)
            while (v != null) {
                assertTrue(
                    "模型表单字段及其祖先必须可见（id=$id）",
                    v.visibility == View.VISIBLE,
                )
                v = v.parent as? View
            }
        }
    }

    @Test
    fun `settings page restored preset block after advanced config and brain list title intact`() {
        val root = LayoutInflater.from(themed()).inflate(R.layout.screen_settings, null)
        val texts = allTexts(root)
        val joined = texts.joinToString("\n")
        // 大脑分节原样：标题仍是“已添加的大脑”，没有被改写成模型。
        assertTrue(joined.contains("已添加的大脑（点按一行切换）"))
        assertFalse(joined.contains("已添加的模型"))
        // 预设块回到原位：在“高级配置”折叠之后（不再夹在大脑名称与
        // 高级配置之间）。
        val advancedIdx = texts.indexOfFirst { it.startsWith("高级配置") }
        val presetIdx = texts.indexOf("从服务商预设添加模型")
        assertTrue("高级配置应在预设块之前", advancedIdx >= 0 && presetIdx > advancedIdx)
    }
}
