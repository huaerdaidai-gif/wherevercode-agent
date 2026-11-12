package wherevercode.agent.ui

import android.os.Looper
import android.view.View
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import wherevercode.agent.AgentGraph
import wherevercode.agent.AndroidApprovalGate
import wherevercode.agent.MainActivity
import wherevercode.agent.R
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentAccess
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord

/**
 * 输入框大脑胶囊（maintainer 2026-11-11：能清理就清理掉）：
 * 只在跟大脑对话时显示；跟远程 Agent 对话时隐藏——推理不走全局大脑，
 * 显示 DeepSeek 是误导。Agent 下拉已显示对话方，信息不丢。
 *
 * 三条回归：
 * - 大脑会话（默认，未选 Agent）：胶囊可见。
 * - 切到 Agent 会话：胶囊隐藏（GONE，不占位）。
 * - 切回新对话（大脑）：胶囊重新可见，状态跟着对话方变。
 *
 * Agent 切换走真实 ChatViewModel.switchSession / newConversation，不
 * mock 行为；publish 是 postValue，主线程 idle 后断言（带 3 秒轮询，
 * 与 DeviceTapRefreshTest 同款）。MainActivity 的 chatViewModel 是
 * private，用反射取（仅测试取用，不改生产代码结构）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class BrainCapsuleVisibilityTest {

    @After
    fun tearDown() {
        AgentGraph.resetForTests()
        AndroidApprovalGate.resetForTests()
    }

    @Test
    fun `brain session shows brain capsule`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create().start().resume().visible()
        val activity = controller.get()
        idleMain()

        val capsule = activity.findViewById<TextView>(R.id.brainCapsule)
        assertNotNull(capsule)
        assertEquals(View.VISIBLE, capsule.visibility)

        controller.pause().stop().destroy()
    }

    @Test
    fun `agent session hides brain capsule and new conversation shows it again`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create().start().resume().visible()
        val activity = controller.get()
        idleMain()

        val capsule = activity.findViewById<TextView>(R.id.brainCapsule)
        assertNotNull(capsule)
        assertTrue(
            "默认大脑会话应显示胶囊",
            waitForCapsuleVisibility(activity, View.VISIBLE),
        )

        // 切到 Agent 会话：走真实 switchSession + publish → renderChat →
        // updateChatRouteBanner → refreshBrainCapsule。
        val viewModel = chatViewModelOf(activity)
        assertTrue(viewModel.switchSession("session-agy", "Antigravity (agy)", fakeAgent()))
        assertTrue(
            "Agent 会话不应显示大脑胶囊",
            waitForCapsuleVisibility(activity, View.GONE),
        )
        assertEquals(
            "Agent 会话里 Agent 下拉仍应显示对话方",
            "Antigravity (agy) ⌄",
            activity.findViewById<TextView>(R.id.buttonAgentSelect).text.toString(),
        )

        // 切回新对话（大脑）：胶囊重新可见。
        assertTrue(viewModel.newConversation())
        assertTrue(
            "切回大脑会话应重新显示胶囊",
            waitForCapsuleVisibility(activity, View.VISIBLE),
        )

        controller.pause().stop().destroy()
    }

    private fun chatViewModelOf(activity: MainActivity): ChatViewModel {
        val field = MainActivity::class.java.getDeclaredField("chatViewModel")
        field.isAccessible = true
        return field.get(activity) as ChatViewModel
    }

    private fun fakeAgent(): AgentRecord = AgentRecord(
        agentId = "agy-test",
        displayName = "Antigravity (agy)",
        kind = AgentKind.DEVICE,
        access = AgentAccess.CLI,
        brainKind = BrainKind.AGENT_RUNTIME,
        adapterId = "test-adapter",
        binary = "agy",
    )

    private fun waitForCapsuleVisibility(
        activity: MainActivity,
        expected: Int,
        timeoutMs: Long = 3000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idleMain()
            if (activity.findViewById<TextView>(R.id.brainCapsule).visibility == expected) {
                return true
            }
            Thread.sleep(100)
        }
        idleMain()
        return activity.findViewById<TextView>(R.id.brainCapsule).visibility == expected
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
