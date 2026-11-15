package wherevercode.agent.ui.flagship

import wherevercode.agent.brain.ConversationStore
import wherevercode.agent.core.task.TaskStore
import wherevercode.agent.core.project.Project
import wherevercode.agent.core.security.SecretRedactor

/** Reads existing stores only. No network indexing, query history or duplicate business database. */
data class LocalSearchResult(val kind: String, val id: String, val title: String, val snippet: String)
class LocalSearch(private val conversations: ConversationStore, private val tasks: TaskStore,
                  private val projects: () -> List<Project>) {
    fun search(query: String): List<LocalSearchResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        fun match(value: String) = value.contains(q, ignoreCase = true)
        fun snippet(value: String): String {
            val safe = SecretRedactor.redact(value, value.length)
            val start = (safe.indexOf(q, ignoreCase = true) - 40).coerceAtLeast(0)
            return safe.substring(start, (start + 200).coerceAtMost(safe.length))
        }
        return buildList {
            conversations.listConversations().forEach { conversation ->
                val message = conversations.listMessages(conversation.id).firstOrNull { match(it.content) }
                if (match(conversation.title) || message != null) add(LocalSearchResult("会话", conversation.id,
                    SecretRedactor.redact(conversation.title), message?.let { snippet(it.content) } ?: "标题匹配"))
            }
            tasks.listTasks().forEach { task ->
                val text = listOfNotNull(task.title, task.description, task.result, task.error).joinToString("\n")
                if (match(text) || match(task.taskId)) add(LocalSearchResult("任务", task.taskId,
                    SecretRedactor.redact(task.title), "${task.state} · ${snippet(text)}"))
            }
            projects().forEach { project ->
                val text = listOfNotNull(project.name, project.projectId, project.repoUrl).joinToString(" · ")
                if (match(text)) add(LocalSearchResult("项目", project.projectId, SecretRedactor.redact(project.name), snippet(text)))
            }
            FlagshipRoutes.all.filter { match(it.id) || match(it.title) }.forEach {
                add(LocalSearchResult("页面", it.id, it.title, "${V5Navigation.parentByRoute[it.id]} · ${it.id}"))
            }
        }
    }
}
