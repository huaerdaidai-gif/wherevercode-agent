package wherevercode.agent.ui

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.R
import wherevercode.agent.brain.BrainConfig
import wherevercode.agent.brain.BrainKind

/**
 * 大脑列表行视图（2026-11-11，maintainer：大脑增删改添要看得见）。
 *
 * 管理以前只藏在长按里，设置页上没有任何提示，maintainer 根本找不到——
 * 藏着的等于没有。每行行尾加可见的“···”，点它就打开与长按同一张
 * 管理单；点行仍是切换；长按仍通。Robolectric，CI 跑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class BrainListRowViewTest {

    private val context = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        R.style.Theme_WhereverCode,
    )

    private fun row(
        brainId: String = "model-api-zhipu",
        title: String = "zhipu",
        statusText: String = "已配置",
        isCurrent: Boolean = false,
    ) = BrainLoginRows.BrainListRow(
        brainId = brainId,
        title = title,
        typeLabel = "模型 API",
        statusText = statusText,
        modelText = "glm-5.3",
        isCurrent = isCurrent,
        canSwitch = true,
        switchHint = "设为当前大脑",
    )

    private fun genericConfig() = BrainConfig(
        brainId = "model-api-zhipu",
        kind = BrainKind.MODEL_API,
        adapterId = BrainLoginRows.GENERIC_ADAPTER_ID,
        label = "zhipu",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-5.3",
        authRef = "model-api:model-api-zhipu",
    )

    private fun firstLineConfig() = BrainConfig(
        brainId = "deepseek",
        kind = BrainKind.MODEL_API,
        adapterId = "deepseek",
        label = "DeepSeek API",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-flash",
        authRef = "deepseek:api-key",
    )

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findText(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    @Test
    fun `generic row shows manage button and clicking it calls onManage`() {
        var managed: BrainConfig? = null
        var switched = false
        val view = BrainListRowView.build(
            context = context,
            row = row(),
            config = genericConfig(),
            onSwitch = { switched = true },
            onManage = { managed = it },
        )
        val button = findText(view, "···") ?: throw AssertionError("大脑行没有“···”管理入口")
        button.performClick()
        assertEquals("model-api-zhipu", managed?.brainId)
        assertFalse(switched)
    }

    @Test
    fun `first-line row shows manage button and clicking it calls onManage`() {
        // 一线大脑也有可见“···”：点开是详情（只看、不给删除入口），
        // 不是点了没反应——点了没反应的按钮就是不诚实 UI。
        var managed: BrainConfig? = null
        val view = BrainListRowView.build(
            context = context,
            row = row(brainId = "deepseek", title = "DeepSeek API"),
            config = firstLineConfig(),
            onSwitch = {},
            onManage = { managed = it },
        )
        val button = findText(view, "···") ?: throw AssertionError("一线大脑行没有“···”")
        button.performClick()
        assertEquals("deepseek", managed?.brainId)
    }

    @Test
    fun `clicking the row body switches and does not open manage`() {
        var managed: BrainConfig? = null
        var switched = false
        val view = BrainListRowView.build(
            context = context,
            row = row(),
            config = genericConfig(),
            onSwitch = { switched = true },
            onManage = { managed = it },
        )
        view.performClick()
        assertTrue(switched)
        assertNull(managed)
    }

    @Test
    fun `long press on the row opens manage`() {
        var managed: BrainConfig? = null
        val view = BrainListRowView.build(
            context = context,
            row = row(),
            config = genericConfig(),
            onSwitch = {},
            onManage = { managed = it },
        )
        assertTrue(view.performLongClick())
        assertEquals("model-api-zhipu", managed?.brainId)
    }

    @Test
    fun `current row shows check mark`() {
        val view = BrainListRowView.build(
            context = context,
            row = row(isCurrent = true),
            config = genericConfig(),
            onSwitch = {},
            onManage = {},
        )
        assertNotNull(findText(view, "✓"))
        val other = BrainListRowView.build(
            context = context,
            row = row(isCurrent = false),
            config = genericConfig(),
            onSwitch = {},
            onManage = {},
        )
        assertNull(findText(other, "✓"))
    }

    @Test
    fun `row without a config hides the manage button`() {
        val view = BrainListRowView.build(
            context = context,
            row = row(),
            config = null,
            onSwitch = {},
            onManage = { throw AssertionError("没有配置时不应有管理入口") },
        )
        assertNull(findText(view, "···"))
    }
}
