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

        // V5 C2-3-R4 (v4.3-01)：顶栏是品牌行——汉堡 / ∞ / WhereverCode /
        // V5 / 搜索 / 铃铛 + 抽屉宿主 / 遮罩是首页 chrome。大脑 pill 与
        // 新建已删（大脑状态在设置页大脑分节，新对话走抽屉“对话”入口）。
        assertNotNull(activity.findViewById<View>(R.id.buttonMenu))
        assertNotNull(activity.findViewById<View>(R.id.topBrandLogo))
        assertNotNull(activity.findViewById<View>(R.id.topBrandName))
        assertNotNull(activity.findViewById<View>(R.id.topBrandVersion))
        assertNotNull(activity.findViewById<View>(R.id.buttonSearch))
        assertNotNull(activity.findViewById<View>(R.id.buttonBell))
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
        assertEquals(12, container.childCount)
        assertEquals(View.VISIBLE, container.getChildAt(0).visibility)
        assertEquals(View.GONE, container.getChildAt(1).visibility)
        assertEquals(View.GONE, container.getChildAt(2).visibility)
        assertEquals(View.GONE, container.getChildAt(3).visibility)
        assertEquals(View.GONE, container.getChildAt(4).visibility)
        assertEquals(View.GONE, container.getChildAt(5).visibility)
        assertEquals(View.GONE, container.getChildAt(6).visibility)
        assertEquals(View.GONE, container.getChildAt(7).visibility)
        assertEquals(View.GONE, container.getChildAt(8).visibility)
        assertEquals(View.GONE, container.getChildAt(9).visibility)
        assertEquals(View.GONE, container.getChildAt(10).visibility)

        // V5-UI：首屏保持轻量问候 + 两个建议 pill（点按填输入框）。用户名不写死：无登录用户名时
        // 只显示“你好”。
        val texts = collectTexts(activity.window.decorView)
        assertTrue(texts.contains("WhereverCode"))
        assertTrue(texts.contains("你好"))
        assertTrue(texts.contains("有什么想一起解决的？"))
        assertTrue(texts.contains("开发一个 App"))
        assertTrue(texts.contains("导入项目并交付"))
        assertTrue(texts.none { it == "组织多 Agent 团队" || it == "自动化任务" })

        controller.pause().stop().destroy()
    }

    /**
     * V5 C2-3-R4：铃铛只有图标、没有红点（App 内无真实通知）；点按只
     * 给“通知中心待开发”提示，绝不编造通知数。
     */
    @Test
    fun `bell shows notification center placeholder toast`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
        val activity = controller.get()

        activity.findViewById<View>(R.id.buttonBell).performClick()

        assertEquals("通知中心待开发", ShadowToast.getTextOfLatestToast())

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
