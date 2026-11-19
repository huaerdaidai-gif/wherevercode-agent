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
 * 模型页解耦布局回归（2026-11-19，Funk 定：大脑已砍开放一边，模型页
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
     * 批B B7 行进入式（与 iOS 同形态）后的新规口径，兼防 197 缺陷 #2
     * 回归：手工表单收进可折叠容器（默认 gone），入口是「添加通用模型
     * API（OpenAI 兼容）」行；字段必须都在这个容器里（点行即展开）。
     * 197 缺陷的根源是「预设预填后表单仍藏着」——现由 MainActivity 在
     * 预设预填与编辑已有模型时自动展开容器（expandModelManualForm）
     * 防住，不再靠表单常显。
     */
    @Test
    fun `model form fields live in the collapsible container behind the entry row`() {
        val root = LayoutInflater.from(themed()).inflate(R.layout.screen_models, null)
        val entry = root.findViewById<TextView>(R.id.buttonModelManualForm)
        assertNotNull("手工添加入口行必须存在", entry)
        assertTrue(entry.text.toString().contains("添加通用模型 API"))
        val container = root.findViewById<View>(R.id.modelManualFormContainer)
        assertNotNull("手工表单容器必须存在", container)
        assertTrue(
            "容器默认收起（行进入式）",
            container.visibility == View.GONE,
        )
        for (id in listOf(
            R.id.modelFormLabel, R.id.modelFormBaseUrl, R.id.modelFormModel,
            R.id.modelFormApiKey, R.id.buttonSaveModel,
        )) {
            var v: View? = root.findViewById(id)
            assertNotNull(v)
            var insideContainer = false
            while (v != null) {
                if (v === container) { insideContainer = true; break }
                v = v.parent as? View
            }
            assertTrue("表单字段必须在可折叠容器内（id=$id）", insideContainer)
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
