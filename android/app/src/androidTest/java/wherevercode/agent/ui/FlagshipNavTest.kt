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
        File(output, "navigation.txt").writeText(
            "legacy FlagshipActivity -> MainActivity; duplicate shell absent\\n",
        )
    }
}
