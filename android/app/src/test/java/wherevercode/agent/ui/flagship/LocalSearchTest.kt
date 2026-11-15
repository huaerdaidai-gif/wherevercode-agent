package wherevercode.agent.ui.flagship

import org.junit.Assert.*
import org.junit.Test
import wherevercode.agent.brain.*
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.project.Project

class LocalSearchTest {
    @Test fun `body result preserves conversation id and redacts secrets`() {
        val conversations = InMemoryConversationStore()
        val conversation = conversations.createConversation(null, "无关键词标题")
        conversations.addMessage(Message("m1", conversation.id, "user", "needle api_key=sk-abcdefghijklmnopqrstuvwxyz1234567890", "user", 1))
        conversations.addMessage(Message("m2", conversation.id, "assistant", "needle duplicate", "model", 2))
        val results = LocalSearch(conversations, FakeTaskStore(), { emptyList() }).search("NEEDLE")
        assertEquals(1, results.size)
        assertEquals(conversation.id, results.single().id)
        assertFalse(results.single().snippet.contains("sk-abcdefghijklmnopqrstuvwxyz"))
    }
    @Test fun `tasks projects and routes keep exact identities`() {
        val tasks = FakeTaskStore()
        tasks.createTask(TaskRecord(taskId = "task-exact", title = "needle task", result = "needle output"))
        val search = LocalSearch(InMemoryConversationStore(), tasks, { listOf(Project(projectId = "project-exact", name = "needle project")) })
        val results = search.search("needle")
        assertEquals(setOf("task-exact", "project-exact"), results.map { it.id }.toSet())
        assertEquals("taskdetail", search.search("TASKDETAIL").single().id)
        assertTrue(search.search(" ").isEmpty())
        assertTrue(search.search("unmatched-value").isEmpty())
    }
}
