package wherevercode.agent

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Launch smoke test for the UI V2 shell. It intentionally does not wait for
 * the background brain wiring (Room/Keystore/network); it proves the Activity
 * itself inflates, binds the V5 home + drawer chrome and survives onCreate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class MainActivitySmokeTest {

    @After
    fun tearDown() {
        AgentGraph.resetForTests()
        AndroidApprovalGate.resetForTests()
    }

    @Test
    fun `activity launches on the v5 home with drawer chrome`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
        val activity = controller.get()

        // 2026-11-16 照 iOS 首页顶栏：汉堡 / 搜索 / 新建（✎）+ 抽屉
        // 宿主 / 遮罩是首页 chrome。品牌行与铃铛已删（品牌在抽屉头，
        // 铃铛是死入口）；大脑状态仍在输入框大脑胶囊与设置页。
        assertNotNull(activity.findViewById<View>(R.id.buttonMenu))
        assertNotNull(activity.findViewById<View>(R.id.buttonSearch))
        assertNotNull(activity.findViewById<View>(R.id.buttonNew))
        assertEquals(
            0,
            activity.resources.getIdentifier("topBrandLogo", "id", activity.packageName),
        )
        assertEquals(
            0,
            activity.resources.getIdentifier("buttonBell", "id", activity.packageName),
        )
        assertNotNull(activity.findViewById<View>(R.id.drawerContainer))
        assertNotNull(activity.findViewById<View>(R.id.drawerScrim))
        // 设置清理 2（2026-11-11，Claude 标准）：聊天输入框里常驻当前大脑
        // 胶囊，点按打开设置页大脑列表切换。
        val capsule = activity.findViewById<TextView>(R.id.brainCapsule)
        assertNotNull(capsule)
        assertTrue(capsule.isClickable)
        val container = activity.findViewById<android.widget.FrameLayout>(R.id.screenContainer)
        // 5 个屏（聊天/项目/任务/Agent/设置）+ 1 个任务详情浮层（阶段 3 批 3a，
        // 复用设置浮层的宿主模式）+ 1 个会话列表（阶段 3 批 3c，聊天 Tab 首屏）
        // + 2 个 V5 C2 批 2 占位屏（成果、帮助）+ 1 个 V5 控制台（2026-11-11，
        // 按需展开的专业工作台，抽屉“控制台”进入）+ 1 个 MCP/插件屏
        // （2026-11-11，抽屉“扩展与资源”→“MCP / 插件”进入）+ 1 个团队频道屏
        // （P3 频道，抽屉“扩展与资源”→“团队频道”进入）；
        // 初始入口是新对话（首页），聊天屏 VISIBLE，其余全部 GONE。
        assertEquals(13, container.childCount)
        assertEquals(View.VISIBLE, container.getChildAt(0).visibility)
        for (index in 1 until container.childCount) {
            assertEquals("secondary screen $index starts hidden", View.GONE, container.getChildAt(index).visibility)
        }

        // V5-UI：首屏保持轻量问候 + 四个建议 pill（点按填输入框；2026-11-15
        // F1 修复后全部渲染，不再截断为两个）。问候文案 2026-11-16 与
        // iOS 统一为“今天想做什么？”，不带真人实名（隐私口径不变）。
        val texts = collectTexts(activity.window.decorView)
        assertTrue(texts.contains("WhereverCode"))
        assertTrue(texts.contains("今天想做什么？"))
        assertTrue(texts.contains("开发一个 App"))
        assertTrue(texts.contains("导入项目并交付"))
        assertTrue(texts.contains("组织多 Agent 团队"))
        assertTrue(texts.contains("自动化任务"))

        controller.pause().stop().destroy()
    }

    /**
     * 2026-11-16 照 iOS：输入框上方来源胶囊常驻，未绑定项目时显示
     * “本机 · App 沙盒 ⌄”；顶栏新建钮存在且可点（点按开新对话）。
     */
    @Test
    fun `source capsule shows local sandbox by default and new button exists`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
        val activity = controller.get()

        val capsule = activity.findViewById<TextView>(R.id.sourceCapsule)
        assertNotNull(capsule)
        assertEquals("本机 · App 沙盒 ⌄", capsule.text.toString())
        assertTrue(capsule.isClickable)
        val newButton = activity.findViewById<View>(R.id.buttonNew)
        assertNotNull(newButton)
        assertTrue(newButton.isClickable)

        controller.pause().stop().destroy()
    }

    private fun collectTexts(root: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out.add(v.text.toString())
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return out
    }
}
