package wherevercode.agent

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.ui.AgentScreenController

/**
 * 沙箱扫描与诊断区已删（2026-11-11，maintainer 拍 K70 屏幕发火）：
 * Agent 页不再有“扫描发现（仅 App 沙箱内）”区段（Gemini CLI /
 * CodeBuddy / Antigravity 的“沙箱内未发现”卡），也不再有
 * “诊断 / 高级工具”区段（诊断与安装文案 + 运行探针 / 测 Node
 * 版本 / 安装 CodeBuddy 三个按钮 + 电池优化按钮 + 诊断输出）。
 * 超级 Agent 是调度，调设备上已有的 Agent；在沙箱里装 Agent 是
 * 已废弃的错误方向。已添加的 Agent 列表与设备区必须原样保留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SandboxScanRemovedTest {

    @After
    fun tearDown() {
        AgentGraph.resetForTests()
        AndroidApprovalGate.resetForTests()
    }

    private fun collectTexts(view: View): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) out.add(v.text.toString())
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(view)
        return out
    }

    @Test
    fun `agent list renders without sandbox scan section`() {
        val context = RuntimeEnvironment.getApplication()
        val list = LinearLayout(context)
        val deviceInfo = LinearLayout(context)
        val controller = AgentScreenController(context, list, deviceInfo)
        controller.renderAddedAgents(emptyList())
        controller.renderDevices(emptyList(), emptyList(), {}, {}, {}, {})
        val text = collectTexts(list).joinToString("\n")
        assertFalse(text.contains("扫描发现"))
        assertFalse(text.contains("沙箱内"))
        assertFalse(text.contains("诊断"))
        assertFalse(text.contains("CodeBuddy"))
        assertFalse(text.contains("Antigravity"))
        assertFalse(text.contains("Gemini CLI"))
        // 已添加的 Agent 区与设备区仍在。
        assertTrue(text.contains("已添加的 Agent"))
        assertTrue(text.contains("设备"))
    }

    @Test
    fun `activity has no diagnostics texts but keeps agent list`() {
        // 注意：被删视图的 R.id 已不存在，不能用 findViewById 断言为 null
        // （编译不过）；改断言整屏文本里不再出现这些文案。
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .resume()
            .visible()
        val activity = controller.get()
        try {
            val texts = collectTexts(activity.window.decorView)
            assertFalse(texts.any { it.contains("诊断 / 高级工具") })
            assertFalse(texts.any { it.contains("诊断与安装") })
            assertFalse(texts.any { it.contains("运行探针") })
            assertFalse(texts.any { it.contains("测 Node 版本") })
            assertFalse(texts.any { it.contains("安装 CodeBuddy") })
            assertFalse(texts.any { it.contains("打开电池优化设置") })
            assertFalse(texts.any { it.contains("诊断结果会显示在这里") })
            assertFalse(texts.any { it.contains("扫描发现") })
            assertFalse(texts.any { it.contains("沙箱内未发现") })
            // Agent 列表与添加入口、设备区仍在。
            assertNotNull(activity.findViewById<View>(R.id.agentList))
            assertNotNull(activity.findViewById<View>(R.id.buttonAddAgent))
            assertNotNull(activity.findViewById<View>(R.id.deviceSectionHeader))
            assertNotNull(activity.findViewById<View>(R.id.deviceInfo))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
