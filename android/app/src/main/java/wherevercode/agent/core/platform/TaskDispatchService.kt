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
 * 作用域（spec §6）：owner 见全部 16 个工具；agent 只见干活侧
 * 9 个，调派单侧工具按「未知工具」拒绝（与工具不存在同口径，
 * 不泄露存在性）。tools/list 的名单由 [toolNames] 按作用域给，
 * 门面（进程内/内网）共用这一份。
 *
 * 工具行为按 W4–W11 逐包落地；未落地的工具如实抛「任务派工
 * 尚未启用」，不假成功。
 */
class TaskDispatchService(
    val store: TaskDispatchStore,
    private val directory: TaskAgentDirectory,
    private val bridge: TaskChannelBridge?,
    private val clock: () -> Double,
    private val leaseSeconds: Double = TaskDispatchSpec.LEASE_SECONDS,
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

        const val SCOPE_OWNER = "owner"
        const val SCOPE_AGENT = "agent"

        fun isTaskTool(tool: String): Boolean = tool in ALL_TOOLS

        /** tools/list 的名单：owner = 干活 9 + 派单 7（顺序同 spec §6）。 */
        fun toolNames(callerScope: String): List<String> =
            if (callerScope == SCOPE_OWNER) WORKER_TOOLS + OWNER_TOOLS else WORKER_TOOLS
    }

    @Synchronized
    fun dispatch(
        tool: String,
        args: JSONObject,
        callerId: String,
        callerName: String,
        callerScope: String,
    ): JSONObject {
        if (!isTaskTool(tool)) throw TaskDispatchException("未知工具")
        if (tool in OWNER_TOOLS && callerScope != SCOPE_OWNER) {
            throw TaskDispatchException("未知工具")
        }
        throw TaskDispatchException("任务派工尚未启用")
    }

    /** UI 软删除（spec §2.17）：不是 MCP 工具，只供任务页调用。W11 落地。 */
    @Synchronized
    fun uiDeleteTask(args: JSONObject, callerId: String): JSONObject {
        throw TaskDispatchException("任务派工尚未启用")
    }
}
