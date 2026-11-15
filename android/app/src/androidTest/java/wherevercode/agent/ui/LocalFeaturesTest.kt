package wherevercode.agent.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import wherevercode.agent.MainActivity
import wherevercode.agent.brain.MemoryKind
import wherevercode.agent.brain.PersistentMemoryStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.RoomSkillStore
import wherevercode.agent.ui.flagship.EXTRA_V5_ROUTE

/** Only isolated CI data: genuine UI mutations, independently read back from production persistence. */
@RunWith(AndroidJUnit4::class)
class LocalFeaturesTest {
    @Test fun memorySkillsAndLocalSearchUseRealPersistentStores() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("wherevercode.agent.ci", context.packageName)
        context.getSharedPreferences("onboarding_v5", 0).edit().putBoolean("onboarding_completed", true).commit()
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        device.executeShellCommand("svc power stayon true")
        val output = File(context.getExternalFilesDir(null), "v5-stage2-evidence").apply { mkdirs() }
        fun text(value: String): UiObject2 = device.wait(Until.findObject(By.text(value)), 120_000) ?: error("Missing UI: $value")
        fun route(id: String) {
            context.startActivity(Intent(context, MainActivity::class.java).putExtra(EXTRA_V5_ROUTE, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            device.waitForIdle()
        }
        fun clickVisibleDescription(description: String) {
            device.wait(Until.hasObject(By.desc(description)), 30_000)
            repeat(10) {
                val target = device.findObject(By.desc(description))
                val bounds = target?.visibleBounds
                if (target != null && bounds != null && bounds.width() > 8 && bounds.height() > 8 &&
                    bounds.top >= 0 && bounds.bottom < device.displayHeight - 8) {
                    target.click()
                    return
                }
                device.swipe(180, 680, 180, 350, 20)
                device.waitForIdle()
            }
            error("Delete action is not visible: $description")
        }
        fun awaitDeletion(absent: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 10_000
            while (!absent() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Deletion must finish in the persistent store", absent())
        }
        fun fields(): List<UiObject2> = device.findObjects(By.clazz("android.widget.EditText"))
        fun hideKeyboard() {
            if (device.executeShellCommand("dumpsys input_method").contains("mInputShown=true")) device.pressBack()
            device.waitForIdle()
        }
        fun shot(name: String) { assertTrue(device.takeScreenshot(File(output, "$name.png"))) }
        val marker = "stage2-${System.currentTimeMillis()}"
        route("memory")
        text("事实名称")
        fields()[0].text = marker
        fields()[1].text = "本地持久记忆验收"
        hideKeyboard()
        text("确认事实…").click()
        text("确认保存").click()
        assertTrue(device.wait(Until.hasObject(By.textContains(marker)), 30_000))
        val reopened = PersistentMemoryStore(context.getSharedPreferences("brain_memory", 0))
        assertEquals("本地持久记忆验收", reopened.get(MemoryKind.FACT, marker)?.value)
        shot("memory-persisted")
        route("skills")
        text("技能名称")
        fields()[0].text = marker
        fields()[1].text = "人工记录，不声明执行成功"
        fields()[2].text = marker
        hideKeyboard()
        text("保存人工技能记录").click()
        text(marker)
        val db = TaskDatabase.create(context)
        try {
            val store = RoomSkillStore(db)
            val saved = store.listForProject("").single { it.name == marker }
            assertEquals(0, saved.successCount)
            assertEquals(0, saved.failureCount)
            assertTrue(saved.evidenceTaskIds.isEmpty())
            shot("skills-persisted")
            // Search routes are genuine metadata. Search no-match state must not invent data.
            route("search")
            text("搜索本地内容")
            fields().single().text = "TASKDETAIL"
            hideKeyboard()
            text("页面 · 任务详情").click()
            text("路由：taskdetail")
            shot("search-exact-route")
            route("memory")
            text("事实名称")
            // Test-created record is the only memory entry in this isolated test package on a clean run.
            clickVisibleDescription("删除记忆 $marker")
            text("删除").click()
            awaitDeletion { PersistentMemoryStore(context.getSharedPreferences("brain_memory", 0)).get(MemoryKind.FACT, marker) == null }
            route("skills")
            text("技能名称")
            clickVisibleDescription("删除技能 ${saved.id}")
            text("删除").click()
            awaitDeletion { store.get(saved.id) == null }
            File(output, "result.txt").writeText("PASS: UI memory add/confirm/persist/delete; UI skill create/Room/zero evidence/delete; exact route search.\nNo model, cloud sync or automation execution claimed.\n")
        } finally { db.close() }
    }
}
