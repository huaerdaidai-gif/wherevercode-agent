package wherevercode.agent.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import wherevercode.agent.AgentGraph
import wherevercode.agent.AndroidApprovalGate
import wherevercode.agent.MainActivity
import wherevercode.agent.R

/**
 * V5 设备点按刷新修复（2026-11-11，真机 bug）：
 * 点设备后列表先空白 5 秒（等 mDNS 发现）才出结果，看着像卡住；
 * 清空又把滚动位置压回顶部，刚操作的设备在“远程设备”区段、首屏
 * 之外，要用户自己下滑才看得到。修后：本地数据（配对 + 远程设备）
 * 先读先画，点完立刻出结果；重画保留滚动位置。
 *
 * 两条断言都以 3 秒为界：旧代码要等 5 秒 mDNS 发现才画，新代码
 * 毫秒级就画——3 秒内看不到设备就是回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class DeviceTapRefreshTest {

    @After
    fun tearDown() {
        AgentGraph.resetForTests()
        AndroidApprovalGate.resetForTests()
    }

    @Test
    fun `entering devices page shows locally added device at once without waiting for lan discovery`() {
        seedRemoteDevices(1)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create().start().resume().visible()
        val activity = controller.get()

        clickDrawerRow(activity, "nav_devices")

        val shown = waitForText(activity, "dev-0", timeoutMs = 3000)
        assertTrue("点进设备页 3 秒内应已看到本地添加的设备 dev-0（不等 5 秒 mDNS）", shown)
        assertTrue(
            collectTexts(activity.window.decorView).any { it.contains("远程设备（手动添加，1）") },
        )

        controller.pause().stop().destroy()
    }

    @Test
    fun `refresh keeps scroll position so the acted-on device stays visible`() {
        seedRemoteDevices(8)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
            .create().start().resume().visible()
        val activity = controller.get()

        clickDrawerRow(activity, "nav_devices")
        assertTrue(waitForText(activity, "dev-7", timeoutMs = 3000))

        val scrollView = activity.findViewById<FrameLayout>(R.id.screenContainer)
            .getChildAt(3) as ScrollView
        // Robolectric 下先强制量一次布局，否则子内容高度未量出、滚动范围是 0。
        scrollView.measure(
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
        )
        scrollView.layout(0, 0, 720, 1600)
        scrollView.scrollTo(0, 400)
        assertEquals(400, scrollView.scrollY)

        activity.findViewById<View>(R.id.buttonDeviceRefresh).performClick()
        Thread.sleep(1200)
        idleMain()

        assertTrue(
            "点刷新后滚动位置应保留（旧代码清空列表会把 scrollY 压回 0，得用户自己下滑）",
            scrollView.scrollY > 0,
        )

        controller.pause().stop().destroy()
    }

    private fun seedRemoteDevices(count: Int) {
        val app = RuntimeEnvironment.getApplication()
        val json = (0 until count).joinToString(",", "[", "]") { i ->
            """{"deviceId":"dev-$i","displayName":"dev-$i","host":"192.168.1.${10 + i}","port":18743,"addedAtEpochSeconds":1794338155}"""
        }
        app.getSharedPreferences("wherevercode_remote_devices", Context.MODE_PRIVATE)
            .edit().putString("devices_json", json).apply()
    }

    private fun clickDrawerRow(activity: MainActivity, tag: String) {
        val row = findByTag(activity.window.decorView, tag)
        assertNotNull("抽屉里应有 tag=$tag 的行", row)
        row!!.performClick()
    }

    private fun findByTag(root: View, tag: String): View? {
        if (root.tag == tag) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findByTag(root.getChildAt(i), tag)?.let { return it }
            }
        }
        return null
    }

    private fun waitForText(activity: MainActivity, needle: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idleMain()
            if (collectTexts(activity.window.decorView).any { it.contains(needle) }) return true
            Thread.sleep(200)
        }
        idleMain()
        return collectTexts(activity.window.decorView).any { it.contains(needle) }
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
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
