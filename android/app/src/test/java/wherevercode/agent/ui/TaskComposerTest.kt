package wherevercode.agent.ui

import android.widget.RadioButton
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import wherevercode.agent.R
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.*
import wherevercode.agent.core.project.*
import wherevercode.agent.core.task.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskComposerTest {
    private lateinit var host: android.app.Activity
    private val context get() = host
    private lateinit var db: TaskDatabase
    private lateinit var tasks: RoomTaskStore
    private val agents = InMemoryAgentStore()
    private val projects = InMemoryProjectStore()
    private val member = AgentRecord(agentId = "remote", displayName = "Remote Agent", kind = AgentKind.CLOUD,
        access = AgentAccess.API, brainKind = BrainKind.MODEL_API, adapterId = "generic-openai",
        baseUrl = "https://example.invalid/v1", model = "example-model", authRef = "test:ref")

    @Before fun setup() {
        host = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        context.setTheme(R.style.Theme_WhereverCode)
        db = TaskDatabase.inMemory(context)
        tasks = RoomTaskStore(db)
        agents.saveAgent(member)
        projects.saveProject(Project("project", "Example Project"))
    }
    @After fun close() { db.close(); host.finish() }

    @Test fun explicitTaskCreatesADispatchableRoomRowWithoutClaimingDelivery() {
        val row = TaskComposer.submit(tasks, agents, projects, "  inspect project  ", "remote", "project", "model")
        val stored = tasks.getTask(row.taskId)!!
        assertEquals("queued", stored.state)
        assertNull(stored.sessionId)
        assertEquals("remote", stored.assignedAgentId)
        assertEquals("project", stored.projectId)
        assertEquals("inspect project", stored.description)
        assertEquals(1, tasks.listTasks().size)
    }

    @Test fun staleOrDisabledSelectionsDoNotCreateTasks() {
        agents.saveAgent(member.copy(enabled = false))
        assertThrows(IllegalArgumentException::class.java) {
            TaskComposer.submit(tasks, agents, projects, "goal", "remote", "project", null)
        }
        agents.saveAgent(member.copy(dispatchable = false))
        assertThrows(IllegalArgumentException::class.java) {
            TaskComposer.submit(tasks, agents, projects, "goal", "remote", "project", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskComposer.submit(tasks, agents, projects, "goal", null, "removed", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaskComposer.submit(tasks, agents, projects, " ", null, null, null)
        }
        assertTrue(tasks.listTasks().isEmpty())
    }

    @Test fun submitIsSingleFlightAndFailureKeepsGoalAndSelections() {
        var calls = 0
        var done: ((String?) -> Unit)? = null
        val form = CreateTaskDialog(context, projects.listProjects(), agents.listAgents(),
            initialGoal = "goal", initialProjectId = "project", initialAgentId = "remote") { goal, agent, project, finish ->
            assertEquals("goal", goal)
            assertEquals("remote", agent)
            assertEquals("project", project)
            calls++
            done = finish
        }
        form.show()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        try {
            val submit = form.dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            submit.performClick()
            assertFalse(submit.isEnabled)
            assertEquals(1, calls)
            done!!("write failed")
            assertTrue(form.dialog.isShowing)
            assertEquals("goal", form.goalInput.text.toString())
            assertEquals(1, form.agentInput.selectedItemPosition)
            assertEquals(1, form.projectInput.selectedItemPosition)
            assertTrue(submit.isEnabled)
            submit.performClick()
            assertEquals(2, calls)
            done!!(null)
            assertFalse(form.dialog.isShowing)
        } finally { form.dialog.dismiss() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun channelDiscussionAndExplicitTaskUseSeparateCallbacksAndKeepDraftUntilCreated() {
        var posts = 0
        var drafts = 0
        var created: (() -> Unit)? = null
        val channel = TeamChannelController(context, {}, { posts++ }, {}, {}, {}, {},
            onCreateTask = { text, finish -> assertEquals("draft goal", text); drafts++; created = finish })
        val views = descendants(channel.root)
        views.filterIsInstance<android.widget.EditText>().first().setText("draft goal")
        views.filterIsInstance<RadioButton>().single { it.text == "作为任务发送" }.performClick()
        views.filterIsInstance<TextView>().single { it.text == "创建任务" }.performClick()
        assertEquals(1, drafts)
        assertEquals(0, posts)
        assertEquals("draft goal", views.filterIsInstance<android.widget.EditText>().first().text.toString())
        created!!()
        assertEquals("", views.filterIsInstance<android.widget.EditText>().first().text.toString())
    }
}
