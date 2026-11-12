package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 引导三屏视图测试（V5 Phase B C2，批 3，R3）。
 *
 * Robolectric——视图需要 Android Context（与 DrawerViewTest 同款
 * 原因）。跑在 CI（`./gradlew testDebugUnitTest`）。
 *
 * 只覆盖渲染与点按发出的回调：每屏的原型图文案、方向行选中态、
 * ADB 按钮只做引导动作（不推进、不记完成）、各屏跳过记完成、
 * 3/3 状态行按宿主传入的真实值渲染（含“未检测”分支）。
 * 宿主的真实动作（打开系统开发者设置、撤遮罩回首页、读大脑/
 * 执行环境/设备状态）在 MainActivity 接线层，见代码，不在此测。
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingViewTest {

    private class MemoryStore : OnboardingController.Store {
        var completed = false
        var storedDirectionId: String? = null
        override fun isCompleted(): Boolean = completed
        override fun setCompleted() {
            completed = true
        }
        override fun directionId(): String? = storedDirectionId
        override fun setDirectionId(id: String?) {
            storedDirectionId = id
        }
    }

    private class Harness(
        readiness: OnboardingController.ReadinessRows = OnboardingController.ReadinessRows(
            OnboardingController.Readiness.NOT_CHECKED,
            OnboardingController.Readiness.NOT_CHECKED,
            OnboardingController.Readiness.NOT_CHECKED,
        ),
    ) {
        val store = MemoryStore()
        val controller = OnboardingController(store)
        var finished = 0
        var adbGuide = 0
        var importRestore = 0
        val view = OnboardingView(
            RuntimeEnvironment.getApplication(),
            controller,
            readinessProvider = { readiness },
            onFinished = { finished++ },
            onAdbGuide = { adbGuide++ },
            onImportRestore = { importRestore++ },
        )

        fun texts(): List<String> {
            val out = mutableListOf<String>()
            collect(view.root, out)
            return out
        }

        private fun collect(v: View, out: MutableList<String>) {
            if (v is TextView) out += v.text.toString()
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
            }
        }

        fun tap(label: String) {
            val target = findText(view.root, label)
                ?: throw AssertionError("no clickable text '$label' in ${texts()}")
            // 真机点按落在行容器上：沿父链找到最近的可点祖先再点。
            var v: View = target
            while (!v.isClickable && v.parent is View) v = v.parent as View
            v.performClick()
        }

        private fun findText(v: View, label: String): TextView? {
            if (v is TextView && v.text.toString() == label) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    findText(v.getChildAt(i), label)?.let { return it }
                }
            }
            return null
        }
    }

    @Test
    fun `welcome renders prototype copy and start advances to direction`() {
        val h = Harness()

        assertTrue(h.texts().contains("WhereverCode-agent"))
        assertTrue(h.texts().contains("你的超级 Agent"))
        assertTrue(h.texts().contains("对话 · 执行 · 协作 · 交付"))
        assertTrue(h.texts().contains("在手机上掌控你的 AI 团队"))
        assertTrue(h.texts().contains("开始使用 →"))

        h.tap("开始使用 →")

        assertEquals(OnboardingController.Step.DIRECTION, h.controller.step())
        assertEquals(0, h.finished)
        assertFalse(h.store.completed)
    }

    @Test
    fun `welcome later skips the whole flow and import only reports todo`() {
        val h = Harness()
        h.tap("已有账号或配置？导入 / 恢复")

        assertEquals(1, h.importRestore)
        assertEquals(0, h.finished)
        assertFalse(h.store.completed)
        assertEquals(OnboardingController.Step.WELCOME, h.controller.step())

        h.tap("稍后再看")

        assertEquals(1, h.finished)
        assertTrue(h.store.completed)
    }

    @Test
    fun `direction screen lists five directions and tap selects and persists`() {
        val h = Harness()
        h.tap("开始使用 →")

        assertTrue(h.texts().contains("‹  1/3  选择你的使用方向"))
        listOf("轻松体验", "开发创造", "学习研究", "工作经营", "设备指挥").forEach { title ->
            assertTrue("missing direction $title", h.texts().contains(title))
        }
        h.tap("开发创造")

        assertEquals("create", h.store.storedDirectionId)
        assertEquals(OnboardingController.Direction.CREATE, h.controller.selectedDirection())

        h.tap("下一步 →")
        assertEquals(OnboardingController.Step.ADB, h.controller.step())
    }

    @Test
    fun `direction skip completes without a direction`() {
        val h = Harness()
        h.tap("开始使用 →")
        h.tap("跳过")

        assertEquals(1, h.finished)
        assertTrue(h.store.completed)
        assertEquals(null, h.store.storedDirectionId)
    }

    @Test
    fun `adb screen shows real steps and guide button only guides`() {
        val h = Harness()
        h.tap("开始使用 →")
        h.tap("下一步 →")

        assertTrue(h.texts().contains("‹  2/3  解锁手机执行能力"))
        assertTrue(h.texts().contains("推荐开启：无线调试（ADB）"))
        assertTrue(h.texts().contains("无需 Root，无需 Termux，即可让 Agent 获得更强的执行能力。"))
        assertTrue(h.texts().contains("1  开启“开发者选项”"))
        assertTrue(h.texts().contains("2  打开“无线调试”"))
        assertTrue(h.texts().contains("3  在本 App 中一键配对"))
        assertTrue(h.texts().contains("本步只打开系统设置做引导；无线配对接线在后续批次，暂未实现。"))

        h.tap("一键引导设置")

        // 只做引导动作：不推进、不记完成、不伪装已配对。
        assertEquals(1, h.adbGuide)
        assertEquals(OnboardingController.Step.ADB, h.controller.step())
        assertFalse(h.store.completed)
        assertEquals(0, h.finished)
    }

    @Test
    fun `adb continue advances and adb skip completes`() {
        val h = Harness()
        h.tap("开始使用 →")
        h.tap("下一步 →")
        h.tap("我已按步骤开启，继续 →")

        assertEquals(OnboardingController.Step.DONE, h.controller.step())
        assertFalse(h.store.completed)

        h.controller.setStep(OnboardingController.Step.ADB)
        h.view.refresh()
        h.tap("跳过，先体验基础功能")

        assertEquals(1, h.finished)
        assertTrue(h.store.completed)
    }

    @Test
    fun `done screen renders honest readiness rows and enter finishes`() {
        val h = Harness(
            readiness = OnboardingController.ReadinessRows(
                brain = OnboardingController.Readiness.AVAILABLE,
                execEnv = OnboardingController.Readiness.NOT_CHECKED,
                device = OnboardingController.Readiness.NOT_CONFIGURED,
            ),
        )
        h.controller.setStep(OnboardingController.Step.DONE)
        h.view.refresh()

        assertTrue(h.texts().contains("‹  3/3  准备完成"))
        assertTrue(h.texts().contains("基础环境已就绪！"))
        assertTrue(h.texts().contains("Agent 大脑"))
        assertTrue(h.texts().contains("基础执行环境"))
        assertTrue(h.texts().contains("设备连接"))
        assertTrue(h.texts().contains("可用"))
        assertTrue(h.texts().contains("未检测"))
        assertTrue(h.texts().contains("未配置"))
        assertTrue(h.texts().contains("进入工作台 →"))

        h.tap("进入工作台 →")

        assertEquals(1, h.finished)
        assertTrue(h.store.completed)
    }

    @Test
    fun `done screen with nothing configured shows unconfigured not fake available`() {
        val h = Harness(
            readiness = OnboardingController.ReadinessRows(
                brain = OnboardingController.Readiness.NOT_CONFIGURED,
                execEnv = OnboardingController.Readiness.NOT_CONFIGURED,
                device = OnboardingController.Readiness.NOT_CONFIGURED,
            ),
        )
        h.controller.setStep(OnboardingController.Step.DONE)
        h.view.refresh()

        assertFalse(h.texts().contains("可用"))
        assertEquals(3, h.texts().count { it == "未配置" })
    }
}
