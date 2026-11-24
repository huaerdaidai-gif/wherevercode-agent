package wherevercode.agent.core.platform

import org.json.JSONObject

/** 派单侧建单时查 Agent 是否存在、取显示名（start_task 的 agent/reviewer 校验）。 */
fun interface TaskAgentDirectory {
    fun agentName(agentId: String): String?
}

/**
 * 任务派工服务（spec §2）：16 个工具的统一入口，由
 * [LocalChannelService.dispatch] 的一行钩子按工具名路由进来，
 * 内网门面经频道服务转发、同一条路径。
 *
 * W1 脚手架阶段：工具名表与路由就位，具体工具行为按 W3–W9
 * 逐包落地；未落地的工具如实抛「任务派工尚未启用」，不假成功。
 * 角色化暴露（干活侧只见 9 个）在 W12 接入 tools/list 与本入口。
 */
class TaskDispatchService(
    @Suppress("unused") private val store: TaskDispatchStore,
    @Suppress("unused") private val directory: TaskAgentDirectory,
    @Suppress("unused") private val bridge: TaskChannelBridge?,
    @Suppress("unused") private val clock: () -> Double,
) {
    companion object {
        /** 干活侧 9 个（spec §6 的顺序即 tools/list 顺序）。 */
        val WORKER_TOOLS = listOf(
            "list_my_tasks", "claim_task", "release_task", "report_progress",
            "report_status", "submit_result", "attach_delivery_diff",
            "get_delivery_diff", "heartbeat",
        )

        /** 派单侧 7 个（owner 作用域）。 */
        val OWNER_TOOLS = listOf(
            "start_task", "get_task", "list_tasks", "decide_task",
            "update_task", "withdraw_task", "reopen_task",
        )

        val ALL_TOOLS: Set<String> = (WORKER_TOOLS + OWNER_TOOLS).toSet()

        fun isTaskTool(tool: String): Boolean = tool in ALL_TOOLS
    }

    @Synchronized
    fun dispatch(tool: String, args: JSONObject, callerId: String, callerName: String): JSONObject {
        if (!isTaskTool(tool)) throw TaskDispatchException("未知工具")
        throw TaskDispatchException("任务派工尚未启用")
    }
}
