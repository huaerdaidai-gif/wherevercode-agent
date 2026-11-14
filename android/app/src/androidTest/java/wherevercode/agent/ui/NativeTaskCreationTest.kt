package wherevercode.agent.ui

import android.content.Intent
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase

/** Real Android UI -> Room. No data clearing, provider changes, fake execution or write approval. */
@RunWith(AndroidJUnit4::class)
class NativeTaskCreationTest {
    @Test fun explicitFormCreatesOneRealTaskAndCancelKeepsChannelDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Explicit opt-in required: -e allowTaskCreation true",
            InstrumentationRegistry.getArguments().getString("allowTaskCreation") == "true")
        val context = instrumentation.targetContext
        assertEquals("Only the isolated debug package may run this test", "wherevercode.agent.ci", context.packageName)
        val device = UiDevice.getInstance(instrumentation)
        val output = File(context.getExternalFilesDir(null), "issue8-native-evidence").apply { mkdirs() }
        fun shot(name: String) { assertTrue(device.takeScreenshot(File(output, "$name.png"))) }
        fun findText(value: String): UiObject2 = device.wait(Until.findObject(By.text(value)), 120_000)
            ?: error("UI text not available: $value")
        fun findId(value: String): UiObject2 = device.wait(Until.findObject(By.res(context.packageName, value)), 120_000)
            ?: error("UI id not available: $value")
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        device.wait(Until.hasObject(By.text("今天想做什么？")), 120_000)
        device.findObject(By.text("稍后再看"))?.click()
        shot("C01-home")
        findId("buttonMenu").click()
        assertTrue(device.wait(Until.hasObject(By.text("团队频道")), 30_000))
        shot("C02-drawer")
        device.findObjects(By.text("任务")).last().click()
        findId("buttonCreateTask").click()
        val goal = "ISSUE8_UI_${UUID.randomUUID()}: Only reply received. Do not invoke tools or modify files."
        val field = device.wait(Until.findObject(By.clazz(EditText::class.java)), 30_000) ?: error("No goal field")
        // Accessibility ACTION_SET_TEXT avoids typing through an IME; it still edits the real native field.
        field.text = goal
        shot("C13-create-task")
        device.findObject(By.res("android", "button1")).click()
        val db = TaskDatabase.create(context)
        try {
            val store = RoomTaskStore(db)
            val deadline = System.currentTimeMillis() + 60_000
            while (store.listTasks().none { it.description == goal } && System.currentTimeMillis() < deadline) Thread.sleep(200)
            val rows = store.listTasks().filter { it.description == goal }
            assertEquals("One explicit submission must create exactly one task", 1, rows.size)
            assertNull("A dispatched task must not be a chat mirror", rows.single().sessionId)
            File(output, "task-record.txt").writeText("taskId=${rows.single().taskId}\nstate=${rows.single().state}\nsessionId=null\n" +
                "Scope: native form to durable TaskStore only; executor completion and remote delivery are not asserted.\n")
            shot("C06-created-task")
            device.pressBack()
            findId("buttonMenu").click()
            findText("团队频道").click()
            val channelInput = device.wait(Until.findObject(By.clazz(EditText::class.java)), 60_000) ?: error("No channel input")
            val draft = "ISSUE8_UNSUBMITTED_${UUID.randomUUID()}"
            channelInput.text = draft
            findText("作为任务发送").click()
            findText("创建任务").click()
            assertTrue(device.wait(Until.hasObject(By.text(draft)), 30_000))
            shot("C04-task-draft-form")
            device.findObject(By.res("android", "button2")).click()
            assertTrue(device.wait(Until.hasObject(By.text(draft)), 30_000))
            assertTrue("Cancelled channel task must not create a row", store.listTasks().none { it.description == draft })
            shot("C04-kept-draft")
        } finally { db.close() }
    }
}
