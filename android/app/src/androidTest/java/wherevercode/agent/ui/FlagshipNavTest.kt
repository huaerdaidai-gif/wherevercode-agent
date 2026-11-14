package wherevercode.agent.ui

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import wherevercode.agent.ui.flagship.FlagshipActivity
import wherevercode.agent.MainActivity
import wherevercode.agent.ui.flagship.EXTRA_FLAGSHIP_ENTRY

/** Regression: the legacy flagship entry must not create a second app shell. */
@RunWith(AndroidJUnit4::class)
class FlagshipNavTest {
    @Test
    fun legacyFlagshipEntryOpensRealChatHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        context.startActivity(
            Intent(context, FlagshipActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        // The home exists underneath onboarding; dismiss overlays before evidence.
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val permission = device.findObject(By.res("com.android.permissioncontroller", "permission_deny_button"))
            if (permission != null) { permission.click(); device.waitForIdle(); continue }
            val skip = device.findObject(By.text("稍后再看"))
                ?: device.findObject(By.text("跳过，先体验基础功能"))
                ?: device.findObject(By.text("跳过"))
            if (skip != null) { skip.click(); device.waitForIdle(); continue }
            if (device.hasObject(By.res(context.packageName, "buttonMenu"))) break
            Thread.sleep(500)
        }
        assertTrue(
            "legacy entry should open the existing chat-first home",
            device.wait(Until.hasObject(By.text("今天想做什么？")), 30_000),
        )
        assertFalse(
            "duplicate flagship home must not appear",
            device.hasObject(By.text("今天想做些什么？")),
        )
        val output = File(context.getExternalFilesDir(null), "unified-ui-evidence").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(output, "android-unified-home.png")))
        device.findObject(By.res(context.packageName, "buttonMenu")).click()
        device.waitForIdle()
        assertFalse("70-screen entry must not appear", device.hasObject(By.textContains("70 屏")))
        assertTrue(device.takeScreenshot(File(output, "android-drawer.png")))
        device.pressBack()
        for ((entry, name) in listOf(
            "nav_history" to "history", "nav_projects" to "projects",
            "nav_running_tasks" to "tasks", "nav_team" to "agents",
            "nav_devices" to "devices", "nav_team_channel" to "channels",
            "nav_console" to "console", "nav_workbench" to "settings",
        )) {
            context.startActivity(Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_FLAGSHIP_ENTRY, entry)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            instrumentation.waitForIdleSync()
            device.waitForIdle()
            assertTrue("header remains available on $entry",
                device.wait(Until.hasObject(By.res(context.packageName, "buttonMenu")), 10_000))
            assertTrue(device.takeScreenshot(File(output, "android-$name.png")))
            device.dumpWindowHierarchy(File(output, "android-$name.xml"))
        }
        File(output, "navigation.txt").writeText(
            "legacy FlagshipActivity -> MainActivity; duplicate shell absent\\n",
        )
    }
}
