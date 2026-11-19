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
 * 输入框大脑胶囊（2026-11-17 定版 supersede 2026-11-11 旧口径）：
 * 大脑已砍出主线——对话改用户自选 Agent/模型，没有大脑在背后路由，
 * 主线聊天里不存在「大脑会话」，胶囊一律不显示（refreshBrainCapsule
 * 恒 GONE）。对话方只由「选择对话方」选择器呈现，信息不丢。
 *
 * 两条回归：
 * - 默认新对话：胶囊隐藏（GONE）。
 * - 切到 Agent 会话、再切回新对话：胶囊始终隐藏；选择器文案随对话方变。
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
    fun `mainline conversation never shows brain capsule`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create().start().resume().visible()
        val activity = controller.get()
        idleMain()

        val capsule = activity.findViewById<TextView>(R.id.brainCapsule)
        assertNotNull(capsule)
        assertEquals(View.GONE, capsule.visibility)

        controller.pause().stop().destroy()
    }

    @Test
    fun `agent session and new conversation keep brain capsule hidden`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create().start().resume().visible()
        val activity = controller.get()
        idleMain()

        val capsule = activity.findViewById<TextView>(R.id.brainCapsule)
        assertNotNull(capsule)
        assertTrue(
            "默认新对话不应显示大脑胶囊",
            waitForCapsuleVisibility(activity, View.GONE),
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

        // 切回新对话：胶囊仍隐藏，选择器回到未选态文案。
        assertTrue(viewModel.newConversation())
        assertTrue(
            "切回新对话仍不应显示大脑胶囊",
            waitForCapsuleVisibility(activity, View.GONE),
        )
        assertEquals(
            "新对话里选择器应回到未选态",
            "选择对话方 ⌄",
            activity.findViewById<TextView>(R.id.buttonAgentSelect).text.toString(),
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
