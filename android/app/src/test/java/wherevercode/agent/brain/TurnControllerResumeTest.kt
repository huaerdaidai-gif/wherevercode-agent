package wherevercode.agent.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3（U4）：TurnController.resume() 只在 PAUSED → RUNNING 时成功。
 * 恢复语义见 TurnController.resume() 文档：引擎停轮后恢复是同一会话的
 * 继续轮次，旧轮次任务行保持 cancelled；本测试只覆盖状态机本身。
 */
class TurnControllerResumeTest {

    @Test
    fun `resume from RUNNING is refused`() {
        val controller = TurnController()
        assertFalse(controller.resume())
        assertTrue(controller.isRunning())
    }

    @Test
    fun `pause then resume returns to RUNNING`() {
        val controller = TurnController()
        assertTrue(controller.pause())
        assertFalse(controller.isRunning())
        assertTrue(controller.resume())
        assertTrue(controller.isRunning())
    }

    @Test
    fun `resume twice only succeeds once`() {
        val controller = TurnController()
        controller.pause()
        assertTrue(controller.resume())
        assertFalse(controller.resume())
    }

    @Test
    fun `resume after stop is refused`() {
        val controller = TurnController()
        assertTrue(controller.stop())
        assertFalse(controller.resume())
        assertFalse(controller.isRunning())
    }

    @Test
    fun `resume after changeGoal is refused`() {
        val controller = TurnController()
        assertTrue(controller.changeGoal())
        assertFalse(controller.resume())
    }

    @Test
    fun `begin after pause clears the paused state`() {
        val controller = TurnController()
        controller.pause()
        controller.begin()
        assertFalse("新一轮 begin() 已复位，旧暂停不再可恢复", controller.resume())
        assertTrue(controller.isRunning())
    }
}
