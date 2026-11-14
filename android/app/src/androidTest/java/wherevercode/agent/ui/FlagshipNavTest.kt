package wherevercode.agent.ui

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import wherevercode.agent.ui.flagship.FlagshipActivity

/**
 * 70 屏旗舰导航（双端组装第一轮）模拟器验收：
 * 壳可启动、抽屉可打开并含 70 路由搜索、待开发页如实显示「待开发」、
 * P0 路由（tasks）经「打开功能」深链回既有 MainActivity 真实屏。
 * 不建任务、不清数据、不伪造执行结果。
 */
@RunWith(AndroidJUnit4::class)
class FlagshipNavTest {

    private fun outputDir(context: Context): File =
        File(context.getExternalFilesDir(null), "issue8-native-evidence").apply { mkdirs() }

    private fun shot(device: UiDevice, output: File, name: String) {
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
    }

    @Test
    fun flagshipShellNavigatesAndDeepLinks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val output = outputDir(context)

        context.startActivity(
            Intent(context, FlagshipActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertTrue(
            "flagship home headline",
            device.wait(Until.hasObject(By.text("今天想做些什么？")), 30_000),
        )
        shot(device, output, "flagship-home")

        device.findObject(By.text("☰"))?.click()
        assertTrue(
            "flagship drawer search",
            device.wait(Until.hasObject(By.textContains("查找 70 个页面")), 15_000),
        )
        shot(device, output, "flagship-drawer")

        // 待开发路由：全局搜索（search）无既有实现，必须如实显示「待开发」。
        device.findObject(By.text("全局搜索"))?.click()
        assertTrue(
            "planned route shows 待开发",
            device.wait(Until.hasObject(By.text("待开发")), 15_000),
        )
        shot(device, output, "flagship-planned")

        // 底部主导航切到 tasks（P0，已验证）页。
        device.findObject(By.text("任务"))?.click()
        assertTrue(
            "tasks route page",
            device.wait(Until.hasObject(By.text("任务中心")), 15_000),
        )
        assertTrue(
            "tasks route shows 已验证",
            device.wait(Until.hasObject(By.text("已验证")), 15_000),
        )
        shot(device, output, "flagship-tasks")

        // 「打开功能」深链回既有 MainActivity（首页问候语与旗舰壳不同字）。
        device.findObject(By.text("打开功能"))?.click()
        val reached = device.wait(Until.hasObject(By.text("今天想做什么？")), 30_000)
        if (!reached) {
            // 首次启动可能有系统通知权限弹窗挡路，关掉后重试等待。
            (device.findObject(By.text("Don’t allow")) ?: device.findObject(By.text("Don't allow")))?.click()
            assertTrue(
                "deep link reaches existing MainActivity home",
                device.wait(Until.hasObject(By.text("今天想做什么？")), 30_000),
            )
        }
        shot(device, output, "flagship-deeplink")

        File(output, "flagship-nav.txt").writeText(
            "routes visited: chat(home), search(planned->待开发), tasks(READY_VERIFIED)\n" +
                "deep link: tasks -> MainActivity home reached\n",
        )
    }
}
