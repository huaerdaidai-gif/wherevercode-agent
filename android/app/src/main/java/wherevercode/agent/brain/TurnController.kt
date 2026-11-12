package wherevercode.agent.brain

import java.util.concurrent.atomic.AtomicReference

/**
 * 2026-11-09 用户控制（暂停 / 停止 / 改目标）的状态载体。
 *
 * 语义与 [BrainEngine] 的 P1 §5.4 控制态一致：
 * - PAUSED / STOPPED / GOAL_CHANGED 都不是"自动续跑"：引擎在下一个
 *   安全检查点停止本轮，并把原因如实回给用户；
 * - 新的一轮由调用方 [begin] 重置为 RUNNING；旧轮次的暂停/停止不会
 *   泄漏到下一轮。
 *
 * 纯 Kotlin（AtomicReference），无 Android 依赖，可直接单测。
 */
class TurnController {

    private val state = AtomicReference(TurnControlState.RUNNING)

    fun snapshot(): TurnControlState = state.get()

    fun isRunning(): Boolean = state.get() == TurnControlState.RUNNING

    /** 新的一轮开始：清掉上一轮遗留的暂停/停止/改目标。 */
    fun begin() {
        state.set(TurnControlState.RUNNING)
    }

    /** 请求暂停本轮。已停止/已改目标时不再降级。 */
    fun pause(): Boolean = state.compareAndSet(TurnControlState.RUNNING, TurnControlState.PAUSED)

    /**
     * 阶段 3（U4）：用户恢复已暂停的本轮。仅 PAUSED → RUNNING；运行中 /
     * 已停止 / 已改目标时返回 false。
     *
     * 语义说明：引擎在下一个安全检查点看到 PAUSED 即停轮（`turn_paused`，
     * 不自动续跑），本轮的流已经结束；恢复不是“唤醒已停的流”，而是
     * 调用方在同一会话里开一个继续轮次（复用对话历史，接着跑剩余步骤）。
     * 旧轮次的任务行保持 cancelled（历史记录），新轮次开新任务行。
     * 因此本方法只在“上一轮确实以 PAUSED 结束、且之后没有新轮次把
     * 状态复位”的情况下返回 true；调用方须以轮次终态为准绳，不能只
     * 看控制器快照。
     */
    fun resume(): Boolean = state.compareAndSet(TurnControlState.PAUSED, TurnControlState.RUNNING)

    /** 请求停止本轮（不可被暂停/改目标覆盖）。重复请求返回 false。 */
    fun stop(): Boolean {
        while (true) {
            val current = state.get()
            if (current == TurnControlState.STOPPED) return false
            if (state.compareAndSet(current, TurnControlState.STOPPED)) return true
        }
    }

    /** 旧目标作废：本轮停止，等用户输入新目标（不自动续跑旧目标）。 */
    fun changeGoal(): Boolean {
        while (true) {
            val current = state.get()
            if (current == TurnControlState.STOPPED || current == TurnControlState.GOAL_CHANGED) {
                return false
            }
            if (state.compareAndSet(current, TurnControlState.GOAL_CHANGED)) return true
        }
    }

    /** 本轮结束/取消后复位，供下一轮复用。 */
    fun reset() {
        state.set(TurnControlState.RUNNING)
    }
}
