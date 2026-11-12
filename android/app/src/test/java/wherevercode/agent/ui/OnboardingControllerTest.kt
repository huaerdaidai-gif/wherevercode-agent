package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引导三屏模型测试（V5 Phase B C2，批 3，R3）。
 *
 * 依据：V5 原型图上排前 4 屏（欢迎 → 1/3 方向 → 2/3 ADB → 3/3
 * 完成 → 首页）。纯 JVM 测试，不依赖 Android。
 */
class OnboardingControllerTest {

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

    private fun controller(store: MemoryStore = MemoryStore()): OnboardingController =
        OnboardingController(store)

    @Test
    fun `shows on first launch and never again after finish`() {
        val store = MemoryStore()
        val onboarding = controller(store)

        assertTrue(onboarding.shouldShow())
        onboarding.finish()

        assertFalse(onboarding.shouldShow())
        assertTrue(store.completed)
    }

    @Test
    fun `next walks welcome direction adb done then finishes`() {
        val onboarding = controller()

        assertEquals(OnboardingController.Step.WELCOME, onboarding.step())
        assertTrue(onboarding.next())
        assertEquals(OnboardingController.Step.DIRECTION, onboarding.step())
        assertTrue(onboarding.next())
        assertEquals(OnboardingController.Step.ADB, onboarding.step())
        assertTrue(onboarding.next())
        assertEquals(OnboardingController.Step.DONE, onboarding.step())
        assertFalse(onboarding.next())
        assertFalse(onboarding.shouldShow())
    }

    @Test
    fun `back walks back and stops at welcome`() {
        val onboarding = controller()
        onboarding.next()
        onboarding.next()

        assertTrue(onboarding.back())
        assertEquals(OnboardingController.Step.DIRECTION, onboarding.step())
        assertTrue(onboarding.back())
        assertEquals(OnboardingController.Step.WELCOME, onboarding.step())
        assertFalse(onboarding.back())
        assertEquals(OnboardingController.Step.WELCOME, onboarding.step())
    }

    @Test
    fun `skip from any step marks completed`() {
        OnboardingController.Step.values().forEach { step ->
            val store = MemoryStore()
            val onboarding = controller(store)
            onboarding.setStep(step)

            onboarding.skip()

            assertFalse("step $step should complete on skip", onboarding.shouldShow())
        }
    }

    @Test
    fun `selecting a direction persists its id immediately`() {
        val store = MemoryStore()
        val onboarding = controller(store)

        assertTrue(onboarding.selectDirection("create"))
        assertEquals("create", store.storedDirectionId)
        assertEquals(OnboardingController.Direction.CREATE, onboarding.selectedDirection())
    }

    @Test
    fun `illegal direction id is rejected and leaves stored value alone`() {
        val store = MemoryStore()
        val onboarding = controller(store)
        onboarding.selectDirection("learn")

        assertFalse(onboarding.selectDirection("not-a-direction"))
        assertFalse(onboarding.selectDirection(""))
        assertEquals("learn", store.storedDirectionId)
        assertEquals(OnboardingController.Direction.LEARN, onboarding.selectedDirection())
    }

    @Test
    fun `illegal stored direction falls back to unselected`() {
        val store = MemoryStore()
        store.storedDirectionId = "bogus"
        val onboarding = controller(store)

        assertNull(onboarding.selectedDirection())
        // 下一步不选方向也能走完；方向保持未选，不伪造一个默认值。
        assertTrue(onboarding.next())
        assertNull(onboarding.selectedDirection())
    }

    @Test
    fun `all five prototype directions exist with verbatim copy`() {
        val directions = OnboardingController.Direction.values()
        assertEquals(5, directions.size)
        assertEquals(
            listOf("easy", "create", "learn", "work", "device"),
            directions.map { it.id },
        )
        assertEquals("轻松体验", OnboardingController.Direction.EASY.title)
        assertEquals("聊天、问答、日常助手", OnboardingController.Direction.EASY.subtitle)
        assertEquals("开发创造", OnboardingController.Direction.CREATE.title)
        assertEquals("代码、项目、测试、构建", OnboardingController.Direction.CREATE.subtitle)
        assertEquals("学习研究", OnboardingController.Direction.LEARN.title)
        assertEquals("资料整理、分析与报告", OnboardingController.Direction.LEARN.subtitle)
        assertEquals("工作经营", OnboardingController.Direction.WORK.title)
        assertEquals("任务、流程与团队协作", OnboardingController.Direction.WORK.subtitle)
        assertEquals("设备指挥", OnboardingController.Direction.DEVICE.title)
        assertEquals("连接设备、远程执行与调度", OnboardingController.Direction.DEVICE.subtitle)
    }

    @Test
    fun `readiness labels never claim available without a check`() {
        assertEquals("可用", OnboardingController.readinessLabel(OnboardingController.Readiness.AVAILABLE))
        assertEquals("未配置", OnboardingController.readinessLabel(OnboardingController.Readiness.NOT_CONFIGURED))
        assertEquals("未检测", OnboardingController.readinessLabel(OnboardingController.Readiness.NOT_CHECKED))
    }

    @Test
    fun `welcome copy matches the prototype verbatim`() {
        assertEquals("WhereverCode-agent", OnboardingController.WELCOME_TITLE)
        assertEquals("V5", OnboardingController.WELCOME_VERSION)
        assertEquals("你的超级 Agent", OnboardingController.WELCOME_HEADLINE)
        assertEquals("对话 · 执行 · 协作 · 交付", OnboardingController.WELCOME_FEATURES)
        assertEquals("在手机上掌控你的 AI 团队", OnboardingController.WELCOME_SUBTITLE)
        assertEquals("开始使用 →", OnboardingController.WELCOME_START)
        assertEquals("已有账号或配置？导入 / 恢复", OnboardingController.WELCOME_IMPORT)
    }

    @Test
    fun `adb steps are real instructions not a pairing claim`() {
        assertEquals(
            listOf(
                "1  开启“开发者选项”",
                "2  打开“无线调试”",
                "3  在本 App 中一键配对",
            ),
            OnboardingController.ADB_STEPS,
        )
        assertEquals("推荐开启：无线调试（ADB）", OnboardingController.ADB_RECOMMEND)
        assertEquals(
            "无需 Root，无需 Termux，即可让 Agent 获得更强的执行能力。",
            OnboardingController.ADB_BENEFIT,
        )
    }

    @Test
    fun `done copy matches the prototype verbatim`() {
        assertEquals("准备完成", OnboardingController.DONE_TITLE)
        assertEquals("基础环境已就绪！", OnboardingController.DONE_HEADLINE)
        assertEquals(
            "你现在可以开始与 Agent 对话、执行任务、管理项目或继续完善配置。",
            OnboardingController.DONE_SUBTITLE,
        )
        assertEquals("进入工作台 →", OnboardingController.DONE_ENTER)
    }
}
