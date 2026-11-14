package wherevercode.agent.ui

import android.content.Context
import android.content.Intent
import android.widget.Button
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
import wherevercode.agent.core.control.ControlStatus
import wherevercode.agent.core.control.RoomControlStore
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.core.task.TaskStates

/**
 * Real Android UI -> Room -> local runner / control plane. No data
 * clearing, provider changes, fake execution or write approval.
 *
 * Three opt-in flows:
 * 1. Native form creates exactly one durable task; a cancelled
 *    channel draft creates none.
 * 2. A created task is really claimed by LocalTaskRunner and reaches
 *    a recorded outcome (the cloud emulator has no brain configured,
 *    so the honest outcome is a failed result carrying
 *    brain_not_configured; any terminal or review state with a
 *    preceding claim counts, a task stuck in queued does not).
 * 3. Task page pause / resume / stop produce confirmed control-plane
 *    receipts, read back from the same Room control store the app
 *    uses, and stop lands the task in cancelled.
 */
@RunWith(AndroidJUnit4::class)
class NativeTaskCreationTest {

    private fun requireOptIn() {
        assumeTrue(
            "Explicit opt-in required: -e allowTaskCreation true",
            InstrumentationRegistry.getArguments().getString("allowTaskCreation") == "true",
        )
    }

    private fun outputDir(context: Context): File =
        File(context.getExternalFilesDir(null), "issue8-native-evidence").apply { mkdirs() }

    private fun shot(device: UiDevice, output: File, name: String) {
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
    }

    private fun findText(device: UiDevice, value: String, timeoutMs: Long = 120_000): UiObject2 =
        device.wait(Until.findObject(By.text(value)), timeoutMs)
            ?: error("UI text not available: $value")

    private fun findId(device: UiDevice, context: Context, value: String): UiObject2 =
        device.wait(Until.findObject(By.res(context.packageName, value)), 120_000)
            ?: error("UI id not available: $value")

    /** Records every visible text so a blocked launch is diagnosable from the evidence artifact. */
    private fun dumpUiTexts(device: UiDevice, output: File, name: String) {
        val texts = mutableListOf<String>()
        for (clazz in listOf("android.widget.TextView", "android.widget.Button", "android.widget.EditText")) {
            for (obj in device.findObjects(By.clazz(clazz))) {
                val t = obj.text?.trim().orEmpty()
                if (t.isNotEmpty()) texts += "$clazz: $t"
            }
        }
        File(output, "$name.txt").writeText(texts.joinToString("\n").ifEmpty { "(no visible text)" })
        runCatching { device.dumpWindowHierarchy(File(output, "$name.xml")) }
    }

    private fun launch(device: UiDevice, context: Context, output: File) {
        runCatching { device.executeShellCommand("svc power stayon true") }
        context.startActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        // Give the first frame a moment, then record what is actually
        // on screen before trying to dismiss anything.
        device.wait(Until.hasObject(By.text("今天想做什么？")), 20_000)
        shot(device, output, "launch-state")
        dumpUiTexts(device, output, "launch-ui")
        // Dismiss the first-run onboarding (its skip labels are real
        // TextViews) or any other transient blocker. NOTE: the home
        // shell exists in the hierarchy UNDER the onboarding overlay,
        // so "home elements found" is not proof the home is usable --
        // the only reliable signal is the overlay being GONE.
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            if (device.findObject(By.text("稍后再看")) == null &&
                device.findObject(By.text("今天想做什么？")) != null
            ) return
            // The system notification-permission dialog can sit over
            // the app from first launch (seen in the round-4 evidence:
            // "Allow WhereverCode to send you notifications?"). Answer
            // it like a user would: deny, and the app must cope.
            val permission = device.findObjects(By.clazz(Button::class.java)).firstOrNull { btn ->
                val t = btn.text?.trim().orEmpty()
                t.equals("不允许", ignoreCase = true) ||
                    (t.contains("allow", ignoreCase = true) && t.contains("don", ignoreCase = true)) ||
                    t.contains("t allow", ignoreCase = true)
            } ?: device.findObjects(By.clazz(Button::class.java)).firstOrNull { btn ->
                btn.text?.trim().equals("Allow", ignoreCase = true)
            }
            if (permission != null) {
                permission.click()
                Thread.sleep(1_000)
                continue
            }
            val skip = device.findObject(By.text("稍后再看"))
                ?: device.findObject(By.text("跳过，先体验基础功能"))
                ?: device.findObject(By.text("跳过"))
            if (skip != null) {
                skip.click()
                device.wait(Until.gone(By.text("稍后再看")), 10_000)
            } else {
                Thread.sleep(1_000)
            }
        }
        dumpUiTexts(device, output, "launch-ui-after")
    }

    private fun openTasks(device: UiDevice, context: Context) {
        findId(device, context, "buttonMenu").click()
        assertTrue(device.wait(Until.hasObject(By.text("新对话")), 30_000))
        device.findObjects(By.text("任务")).last().click()
        findId(device, context, "buttonCreateTask")
    }

    /** Submits the native form once and returns the created task id. */
    private fun createTaskViaForm(
        device: UiDevice,
        context: Context,
        store: RoomTaskStore,
        goal: String,
        output: File,
        shotName: String?,
    ): String {
        findId(device, context, "buttonCreateTask").click()
        val field = device.wait(Until.findObject(By.clazz(EditText::class.java)), 30_000)
            ?: error("No goal field")
        // Accessibility ACTION_SET_TEXT avoids typing through an IME; it still edits the real native field.
        field.text = goal
        if (shotName != null) shot(device, output, shotName)
        device.findObject(By.res("android", "button1")).click()
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val rows = store.listTasks().filter { it.description == goal }
            if (rows.size == 1) {
                assertNull("A dispatched task must not be a chat mirror", rows.single().sessionId)
                return rows.single().taskId
            }
            Thread.sleep(200)
        }
        error("Created task row not found for goal: $goal")
    }

    @Test fun explicitFormCreatesOneRealTaskAndCancelKeepsChannelDraft() {
        requireOptIn()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Only the isolated debug package may run this test", "wherevercode.agent.ci", context.packageName)
        val device = UiDevice.getInstance(instrumentation)
        val output = outputDir(context)
        launch(device, context, output)
        shot(device, output, "C01-home")
        val db = TaskDatabase.create(context)
        try {
            val store = RoomTaskStore(db)
            findId(device, context, "buttonMenu").click()
            assertTrue(device.wait(Until.hasObject(By.text("新对话")), 30_000))
            shot(device, output, "C02-drawer")
            device.findObjects(By.text("任务")).last().click()
            val goal = "ISSUE8_UI_${UUID.randomUUID()}: Only reply received. Do not invoke tools or modify files."
            val taskId = createTaskViaForm(device, context, store, goal, output, "C13-create-task")
            File(output, "task-record.txt").writeText(
                "taskId=$taskId\nstate=${store.getTask(taskId)?.state}\nsessionId=null\n" +
                    "Scope: native form to durable TaskStore only; executor completion and remote delivery are not asserted.\n",
            )
            shot(device, output, "C06-created-task")
            device.pressBack()
            findId(device, context, "buttonMenu").click()
            findText(device, "团队频道").click()
            val channelInput = device.wait(Until.findObject(By.clazz(EditText::class.java)), 60_000)
                ?: error("No channel input")
            val draft = "ISSUE8_UNSUBMITTED_${UUID.randomUUID()}"
            channelInput.text = draft
            findText(device, "作为任务发送").click()
            findText(device, "创建任务").click()
            assertTrue(device.wait(Until.hasObject(By.text(draft)), 30_000))
            shot(device, output, "C04-task-draft-form")
            device.findObject(By.res("android", "button2")).click()
            assertTrue(device.wait(Until.hasObject(By.text(draft)), 30_000))
            assertTrue("Cancelled channel task must not create a row", store.listTasks().none { it.description == draft })
            shot(device, output, "C04-kept-draft")
        } finally {
            db.close()
        }
    }

    @Test fun localRunnerClaimsCreatedTaskAndRecordsOutcome() {
        requireOptIn()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Only the isolated debug package may run this test", "wherevercode.agent.ci", context.packageName)
        val device = UiDevice.getInstance(instrumentation)
        val output = outputDir(context)
        launch(device, context, output)
        openTasks(device, context)
        val db = TaskDatabase.create(context)
        try {
            val store = RoomTaskStore(db)
            val goal = "ISSUE8_EXEC_${UUID.randomUUID()}: Only reply received. Do not invoke tools or modify files."
            val taskId = createTaskViaForm(device, context, store, goal, output, null)
            val startedAt = System.currentTimeMillis()
            val transitions = mutableListOf<String>()
            var lastState: String? = null
            var finalState: String? = null
            val deadline = startedAt + 240_000
            while (System.currentTimeMillis() < deadline) {
                val state = store.getTask(taskId)?.state
                if (state != null && state != lastState) {
                    transitions += "$state@${System.currentTimeMillis() - startedAt}ms"
                    lastState = state
                }
                if (state != null && (TaskStates.isTerminal(state) || state == "review")) {
                    finalState = state
                    break
                }
                Thread.sleep(500)
            }
            val record = store.getTask(taskId)
            File(output, "execution-record.txt").writeText(
                "taskId=$taskId\ntransitions=${transitions.joinToString(",")}\n" +
                    "finalState=${finalState ?: record?.state}\n" +
                    "result=${record?.result?.replace('\n', ' ')?.take(400)}\n" +
                    "error=${record?.error?.replace('\n', ' ')?.take(400)}\n" +
                    "Scope: LocalTaskRunner claim and executor outcome in the cloud emulator. " +
                    "No brain is configured there, so a failed outcome carrying the real reason is the honest result; " +
                    "approval is not asserted (no brain, no tool call).\n",
            )
            shot(device, output, "C06-task-detail-result")
            assertNotNull("Task never reached a terminal or review outcome; transitions=$transitions", finalState)
            // Claim proof: the 500 ms sampling can miss the brief
            // assigned/working window when the executor fails fast, so
            // an executor-produced result/error on the row is equally
            // valid proof the runner claimed and ran this task (round-4
            // run bc5d16f8: queued -> failed in 9.5 s carrying the
            // executor's brain_not_configured error, no working sample).
            assertTrue(
                "Task was never claimed by the runner; transitions=$transitions",
                transitions.any { it.startsWith("assigned@") || it.startsWith("working@") } ||
                    !record?.error.isNullOrBlank() || !record?.result.isNullOrBlank(),
            )
        } finally {
            db.close()
        }
    }

    @Test fun taskPagePauseResumeStopProduceConfirmedReceipts() {
        requireOptIn()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("Only the isolated debug package may run this test", "wherevercode.agent.ci", context.packageName)
        val device = UiDevice.getInstance(instrumentation)
        val output = outputDir(context)
        launch(device, context, output)
        openTasks(device, context)
        val db = TaskDatabase.create(context)
        try {
            val store = RoomTaskStore(db)
            val controlStore = RoomControlStore(db)
            val log = StringBuilder()
            var taskId: String? = null
            var paused = false
            // The local runner may claim and finish a task before the
            // pause tap lands; one retry with a fresh task keeps the
            // control assertions deterministic without faking a state.
            for (attempt in 1..2) {
                // Deterministic queueing: the scheduler runs one task
                // at a time, so two blocker tasks ahead of the control
                // task guarantee it is still QUEUED when the pause tap
                // lands -- and ControlPlane.pause confirms a queued
                // task immediately (an in-flight task cannot confirm:
                // its single brain call errors without a checkpoint,
                // which is why the naive create-then-pause flow lost
                // the race in round-4 run bc5d16f8).
                for (blocker in 1..2) {
                    val blockerGoal = "ISSUE8_BLK_${UUID.randomUUID()}: Only reply received. Do not invoke tools or modify files."
                    createTaskViaForm(device, context, store, blockerGoal, output, null)
                    log.append("attempt$attempt blocker$blocker created\n")
                    device.pressBack()
                    findId(device, context, "buttonCreateTask")
                }
                val goal = "ISSUE8_CTL_${UUID.randomUUID()}: Only reply received. Do not invoke tools or modify files."
                val id = createTaskViaForm(device, context, store, goal, output, null)
                taskId = id
                log.append("attempt$attempt taskId=$id\n")
                findText(device, "暂停", 30_000).click()
                val deadline = System.currentTimeMillis() + 120_000
                while (System.currentTimeMillis() < deadline) {
                    val status = controlStore.getControlStatus(id)
                    if (status == ControlStatus.PAUSED) {
                        paused = true
                        break
                    }
                    val state = store.getTask(id)?.state
                    if (state != null && TaskStates.isTerminal(state)) {
                        log.append("attempt$attempt ended early: task reached terminal state $state before pause confirmed\n")
                        break
                    }
                    Thread.sleep(500)
                }
                if (paused) break
                device.pressBack()
                findId(device, context, "buttonCreateTask")
            }
            assertTrue("Pause was not confirmed by the control plane; log=$log", paused)
            val id = taskId!!
            log.append("pause=PAUSED confirmed for $id\n")
            shot(device, output, "C06-task-detail-paused")
            findText(device, "继续", 30_000).click()
            val resumeDeadline = System.currentTimeMillis() + 60_000
            var resumed = false
            while (System.currentTimeMillis() < resumeDeadline) {
                if (controlStore.getControlStatus(id) == ControlStatus.RUNNING) {
                    resumed = true
                    break
                }
                Thread.sleep(500)
            }
            assertTrue("Resume was not confirmed by the control plane", resumed)
            log.append("resume=RUNNING confirmed for $id\n")
            findText(device, "停止", 30_000).click()
            assertTrue(device.wait(Until.hasObject(By.text("停止任务？")), 30_000))
            val confirm = device.wait(Until.findObject(By.clazz(Button::class.java).text("停止")), 30_000)
                ?: error("No stop confirm button")
            confirm.click()
            val stopDeadline = System.currentTimeMillis() + 120_000
            var cancelled = false
            while (System.currentTimeMillis() < stopDeadline) {
                if (store.getTask(id)?.state == "cancelled") {
                    cancelled = true
                    break
                }
                Thread.sleep(500)
            }
            assertTrue("Stop did not land the task in cancelled; state=${store.getTask(id)?.state}", cancelled)
            log.append("stop=cancelled confirmed for $id\n")
            shot(device, output, "C06-task-detail-stopped")
            device.pressBack()
            shot(device, output, "C05-task-list")
            File(output, "control-record.txt").writeText(
                log.toString() +
                    "Scope: task page controls against the production ControlPlane, receipts read from RoomControlStore; " +
                    "final task state read from RoomTaskStore.\n",
            )
        } finally {
            db.close()
        }
    }
}
