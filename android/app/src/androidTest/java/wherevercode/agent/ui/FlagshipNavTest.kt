package wherevercode.agent.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import wherevercode.agent.MainActivity
import wherevercode.agent.ui.flagship.FlagshipRoutes
import wherevercode.agent.ui.flagship.V5Bindings
import wherevercode.agent.ui.flagship.V5Navigation

/** Native traversal proves navigation only. It does not claim backend or 70 feature verification. */
@RunWith(AndroidJUnit4::class)
class FlagshipNavTest {
    @Test fun fourParentsAndAllSeventyRoutesAreReachableInMainActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        context.getSharedPreferences("onboarding_v5", 0).edit()
            .putBoolean("onboarding_completed", true).commit()
        val output = File(context.getExternalFilesDir(null), "v5-evidence").apply { mkdirs() }
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.desc("打开抽屉")), 30_000))
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(output, "home.png")))
        assertFalse(device.hasObject(By.text("控制台")))
        val evidence = File(output, "traversal.csv")
        evidence.writeText("route_id,result,scope\n")
        V5Navigation.parents.forEach { parent ->
            device.findObject(By.desc("打开抽屉")).click()
            V5Navigation.parents.forEach { assertTrue(device.wait(Until.hasObject(By.text(it)), 10_000)) }
            assertTrue(device.takeScreenshot(File(output, "drawer.png")))
            device.findObject(By.text(parent)).click()
            assertTrue(device.wait(Until.hasObject(By.text("搜索功能或路由")), 10_000))
            FlagshipRoutes.all.filter { V5Navigation.parentByRoute[it.id] == parent }.forEach { route ->
                val input = device.findObject(By.clazz("android.widget.EditText"))
                assertNotNull("search field for ${route.id}", input)
                input.text = route.id
                device.waitForIdle()
                assertTrue("route ${route.id}", device.wait(Until.hasObject(By.text(route.title)), 10_000))
                device.findObject(By.text(route.title)).click()
                assertTrue(device.wait(Until.hasObject(By.text("路由：${route.id}")), 10_000))
                assertFalse(device.hasObject(By.text("已验证")))
                assertEquals(route.id in V5Bindings.all, device.hasObject(By.text("进入已有功能")))
                evidence.appendText("${route.id},PASS,navigation_only\n")
                if (route.id in setOf("dashboard", "usage", "settings", "projects", "voice")) {
                    assertTrue(device.takeScreenshot(File(output, "${route.id}.png")))
                }
                device.findObject(By.text("返回")).click()
                assertTrue(device.wait(Until.hasObject(By.text("搜索功能或路由")), 10_000))
            }
            device.findObject(By.text("返回")).click()
            assertTrue(device.wait(Until.hasObject(By.desc("打开抽屉")), 10_000))
        }
        // Prove an existing feature is executable through this Activity, with no historical READY gate.
        device.findObject(By.desc("打开抽屉")).click()
        device.findObject(By.text("工作台")).click()
        device.findObject(By.clazz("android.widget.EditText")).text = "tasks"
        assertTrue(device.wait(Until.hasObject(By.text("任务中心")), 10_000))
        device.findObject(By.text("任务中心")).click()
        device.findObject(By.text("进入已有功能")).click()
        assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "buttonCreateTask")), 15_000))
        assertTrue(device.takeScreenshot(File(output, "native-tasks.png")))
    }
}
