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
import wherevercode.agent.ui.HomeScreenController.PlusMenuAction

/**
 * PlusMenuSheet rendering test (V5 输入框 + 菜单重做，Claude 参照，maintainer 2026-11-11）。
 *
 * Robolectric — 抽屉是纯 View 搭建，需要 Android Context。
 * 只断言渲染与点按路由：两组分区（添加到对话 / 切换上下文）、
 * 加东西的格子（相机/照片/文件）+ 网页搜索开关行、
 * 切换上下文的格子（项目/选 Agent/选设备）+ 工具与连接行；
 * 死选项文案（未实现 / 待开发 / 终端 / 参数与权限 / 附件）
 * 一个字也不许出现。“网页搜索”自 2026-11-11 按主流改后是真实能力，
 * 不再是死选项。
 */
@RunWith(RobolectricTestRunner::class)
class PlusMenuSheetTest {

    private fun textsOf(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out += v.text.toString()
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }

    private fun findByDescription(root: View, desc: String): View? {
        if (root.contentDescription?.toString() == desc) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findByDescription(root.getChildAt(i), desc)?.let { return it }
            }
        }
        return null
    }

    private fun sheet(actions: MutableList<PlusMenuAction>): View =
        PlusMenuSheet.buildContent(
            RuntimeEnvironment.getApplication(),
            onDismiss = {},
            onAction = { actions += it },
        )

    @Test
    fun `sheet shows two sections add items and context items, no dead labels`() {
        val texts = textsOf(sheet(mutableListOf()))

        // 两组分区标题。
        assertTrue(texts.contains("添加到对话"))
        assertTrue(texts.contains("切换上下文"))
        // 加东西组：相机 / 照片 / 文件 + 网页搜索开关。
        assertTrue(texts.contains("相机"))
        assertTrue(texts.contains("照片"))
        assertTrue(texts.contains("文件"))
        assertTrue(texts.contains("网页搜索"))
        assertTrue(texts.contains("已关闭"))
        // 切换上下文组：项目 / 选 Agent / 选设备 + 工具与连接行。
        assertTrue(texts.contains("项目"))
        assertTrue(texts.contains("选 Agent"))
        assertTrue(texts.contains("选设备"))
        assertTrue(texts.contains("工具与连接"))
        val joined = texts.joinToString("")
        for (dead in listOf("未实现", "待开发", "终端", "参数与权限", "附件")) {
            assertTrue("must not show dead label: $dead", !joined.contains(dead))
        }
    }

    @Test
    fun `web search toggle shows enabled state when on`() {
        val texts = textsOf(
            PlusMenuSheet.buildContent(
                RuntimeEnvironment.getApplication(),
                onDismiss = {},
                onAction = {},
                webSearchEnabled = true,
            ),
        )
        assertTrue(texts.contains("网页搜索"))
        assertTrue(texts.contains("已开启"))
    }

    @Test
    fun `tapping a tile or row fires its action`() {
        val actions = mutableListOf<PlusMenuAction>()
        val root = sheet(actions)

        findByDescription(root, "plus_tile_project")!!.performClick()
        findByDescription(root, "plus_tile_agent")!!.performClick()
        findByDescription(root, "plus_tile_device")!!.performClick()
        findByDescription(root, "plus_row_tools")!!.performClick()
        findByDescription(root, "plus_tile_camera")!!.performClick()
        findByDescription(root, "plus_tile_photo")!!.performClick()
        findByDescription(root, "plus_tile_file")!!.performClick()
        findByDescription(root, "plus_row_web_search")!!.performClick()

        assertEquals(
            listOf(
                PlusMenuAction.OPEN_PROJECTS,
                PlusMenuAction.SELECT_AGENT,
                PlusMenuAction.SELECT_DEVICE,
                PlusMenuAction.OPEN_TOOLS,
                PlusMenuAction.TAKE_PHOTO,
                PlusMenuAction.PICK_IMAGE,
                PlusMenuAction.PICK_FILE,
                PlusMenuAction.TOGGLE_WEB_SEARCH,
            ),
            actions,
        )
    }

    @Test
    fun `close button dismisses without firing an action`() {
        val actions = mutableListOf<PlusMenuAction>()
        var dismissed = 0
        val root = PlusMenuSheet.buildContent(
            RuntimeEnvironment.getApplication(),
            onDismiss = { dismissed++ },
            onAction = { actions += it },
        )

        findByDescription(root, "plus_close")!!.performClick()

        assertEquals(1, dismissed)
        assertTrue(actions.isEmpty())
    }
}
