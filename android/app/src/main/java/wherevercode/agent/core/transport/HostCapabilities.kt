package wherevercode.agent.core.transport

import org.json.JSONObject

/**
 * Host 能力位（老项目 `project_locations.py` 的 HOST_CAPABILITIES /
 * HOST_PROTOCOL_CAPABILITIES，源码自主写，只学思路与协议）。
 *
 * 能力决定一台设备能干什么，不决定它是什么：
 * - project_host：能托管项目文件夹（列/读/写项目内容）；
 * - execution_host：能执行 Agent；
 * - workspace_read / workspace_write：能读 / 能写工作区；
 * - task_execution：能接任务执行；
 * - control_plane：控制面（V5 的 Hand 设备永远不是控制面）。
 * 老项目规矩：注册本身不产生任何位置；新 Host 须由主人明确绑定到
 * 项目后，心跳才能发布该项目的仓库目录（`project_binding_required`）。
 * V5 里“绑定”就是用户在加设备后明确点的“添加项目”。
 *
 * 能力缺省全关（fail closed）：没有能力记录的旧设备一律当作不能
 * 托管项目，加项目流程不会向它提供入口。
 */
data class HostCapabilities(
    val controlPlane: Boolean = false,
    val projectHost: Boolean = false,
    val executionHost: Boolean = false,
    val workspaceRead: Boolean = false,
    val workspaceWrite: Boolean = false,
    val projectCreate: Boolean = false,
    val taskExecution: Boolean = false,
    val collabPublish: Boolean = false,
    val gitFetchBase: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("control_plane", controlPlane)
        put("project_host", projectHost)
        put("execution_host", executionHost)
        put("workspace_read", workspaceRead)
        put("workspace_write", workspaceWrite)
        put("project_create", projectCreate)
        put("task_execution", taskExecution)
        put("collab_publish", collabPublish)
        put("git_fetch_base", gitFetchBase)
    }

    /**
     * 已开的能力（中文名，供设备列表显示“能力：N 项”）。
     * 全关时返回空列表（调用方显示“未知”，不编造）。
     */
    fun enabledNames(): List<String> {
        val names = mutableListOf<String>()
        if (controlPlane) names += "控制面"
        if (projectHost) names += "托管项目"
        if (executionHost) names += "执行主机"
        if (workspaceRead) names += "读工作区"
        if (workspaceWrite) names += "写工作区"
        if (projectCreate) names += "建项目"
        if (taskExecution) names += "执行任务"
        if (collabPublish) names += "发协作"
        if (gitFetchBase) names += "拉 git 基线"
        return names
    }

    /** 已开的能力数。 */
    fun enabledCount(): Int = enabledNames().size

    companion object {
        fun fromJson(obj: JSONObject?): HostCapabilities {
            if (obj == null) return HostCapabilities()
            return HostCapabilities(
                controlPlane = obj.optBoolean("control_plane", false),
                projectHost = obj.optBoolean("project_host", false),
                executionHost = obj.optBoolean("execution_host", false),
                workspaceRead = obj.optBoolean("workspace_read", false),
                workspaceWrite = obj.optBoolean("workspace_write", false),
                projectCreate = obj.optBoolean("project_create", false),
                taskExecution = obj.optBoolean("task_execution", false),
                collabPublish = obj.optBoolean("collab_publish", false),
                gitFetchBase = obj.optBoolean("git_fetch_base", false),
            )
        }
    }
}

/** 协议能力（老项目 HOST_PROTOCOL_CAPABILITIES 的 9 个名字）。 */
object HostProtocolCapabilities {
    val ALL: Set<String> = setOf(
        "open_project_root",
        "roots_hot_reload",
        "conversation_resume",
        "task_pause_resume",
        "task_input_files",
        "repair_workspace_reuse",
        "session_workspace_status",
        "task_steer",
        "assistant_stream",
    )

    /** 只保留已知的协议能力名（未知名字丢弃，不存进记录）。 */
    fun valid(names: Collection<String>): Set<String> =
        names.filter { it in ALL }.toSortedSet()
}

/**
 * V5 Hand 服务端的能力：它提供 list/read/write/run + 控制
 * （暂停/继续/停止/改目标）+ 流式进度回包。不是控制面，不建项目，
 * 不发 collab、不拉取 git 基线——这些一律关着，不冒充。
 */
fun handServerCapabilities(): HostCapabilities = HostCapabilities(
    projectHost = true,
    executionHost = true,
    workspaceRead = true,
    workspaceWrite = true,
    taskExecution = true,
)

/** V5 Hand 协议实际做到的协议能力（以服务端代码为准，不冒充没有的）。 */
fun handServerProtocolCapabilities(): Set<String> = setOf(
    "open_project_root",
    "task_pause_resume",
    "task_steer",
    "assistant_stream",
)
