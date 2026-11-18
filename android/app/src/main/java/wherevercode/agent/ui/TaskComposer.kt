package wherevercode.agent.ui

import wherevercode.agent.core.agent.AgentStore
import wherevercode.agent.core.project.ProjectStore
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStore

/** Explicit native task submission. No channel message, optimistic delivery or new scheduler. */
object TaskComposer {
    fun submit(
        tasks: TaskStore,
        agents: AgentStore,
        projects: ProjectStore,
        goal: String,
        agentId: String?,
        projectId: String?,
        brainId: String?,
    ): TaskRecord {
        val clean = goal.trim()
        require(clean.isNotEmpty()) { "请填写任务目标" }
        val agent = agentId?.let { id ->
            requireNotNull(agents.getAgent(id)) { "成员已移除，请重新选择" }.also {
                require(it.enabled) { "该成员已停用，请重新选择" }
                require(it.dispatchable) { "该成员仅支持对话，不能接收任务" }
            }
        }
        if (projectId != null) {
            requireNotNull(projects.getProject(projectId)) { "项目已移除，请重新选择" }
        }
        return tasks.createTask(ChatTaskDispatcher.createDispatchedTask(agent, clean, brainId, projectId))
    }
}
