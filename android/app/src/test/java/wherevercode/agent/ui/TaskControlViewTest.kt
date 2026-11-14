package wherevercode.agent.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.core.task.TaskRecord

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskControlViewTest {
    @Test fun pendingStopIsVisibleAlongsideTheUnchangedExecutionState() {
        val app = RuntimeEnvironment.getApplication()
        val controller = TaskDetailController(app, {}, {}, {}, { View(app) })
        controller.render(TaskRecord(taskId = "remote", title = "Remote work", state = "working",
            createdAt = 1, updatedAt = 1, version = 1), emptyList(), emptyList(), emptyList(), 0,
            emptyList(), controlStatus = "停止请求待执行器确认")
        fun texts(view: View): List<String> = when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }
        val labels = texts(controller.root)
        assertTrue(labels.contains("执行控制：停止请求待执行器确认"))
        assertFalse(labels.any { it == "执行控制：任务已结束" })
    }

    @Test fun nativeManifestEntryInvokesItsCallback() {
        val app = RuntimeEnvironment.getApplication()
        var opened = false
        val controller = McpPluginScreenController(app, { _, _ -> }, {}, { _, _ -> }, {},
            onManifests = { opened = true })
        fun find(view: View): TextView? {
            if (view is TextView && view.text == "扩展清单 v1 · 查看 / 导入") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        val entry = find(controller.root)
        assertNotNull(entry)
        entry!!.performClick()
        assertTrue(opened)
    }
}
