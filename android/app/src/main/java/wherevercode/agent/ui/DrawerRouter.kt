package wherevercode.agent.ui

/**
 * V5 Phase B C2（批 2，第 3 步）：抽屉入口 → 屏幕的路由表（唯一事实来源）。
 *
 * MainActivity.showEntry() 按此表显示根视图；四个页面控制器本体一字
 * 不动，只换挂载点。纯 JVM，不含 Android 依赖，可直接单测。
 * 未知 id 路由到首页，不崩溃。
 *
 * 注意：设备入口与 Agent 入口共用 screenAgents 根视图，但路由表必须
 * 区分两者——设备入口路由后要附加“滚动到设备区段”（见 showEntry）。
 * 同理，工具与连接入口与设置入口共用 screenSettings 根视图，但前者
 * 路由后要附加“滚动到大脑分节顶部”。模型入口有自己的根视图
 * screenModels（2026-11-19 解耦：不再与设置页共用一屏）。
 */
enum class EntryScreen {
    HOME,
    HISTORY,
    PROJECTS,
    TASKS,
    AGENTS,
    DEVICES,
    /** V5 模型页：控制菜单下与设备、Agent 并列；与设置页共用 screenSettings 根视图。 */
    MODELS,
    TOOLS,
    RESULTS,
    HELP,
    SETTINGS,
    /** V5 控制台 / 控制：按需展开的专业工作台。 */
    CONSOLE,
    /** V5 MCP / 插件：扩展管理屏。 */
    MCP_PLUGINS,
    /** V5 团队频道：有界团队聊天 + 只读扇出。 */
    TEAM_CHANNEL,
    /**
     * P3-MSG 三期：消息中心（@我 / 未读 / 任务回执聚合）。
     * 入口走频道页菜单与字面 id 路由；注册表 feature_message_center
     * 保持 TODO，翻转留到组装步。
     */
    MESSAGE_CENTER,
}

object DrawerRouter {
    /** 消息中心的路由 id（注册表翻转前的可达入口，见 EntryScreen.MESSAGE_CENTER）。 */
    const val ID_MESSAGE_CENTER: String = "message_center"

    fun screenFor(entryId: String): EntryScreen = when (entryId) {
        DrawerController.ID_NEW_CONVERSATION, "nav_chat", "chat" -> EntryScreen.HOME
        DrawerController.ID_HISTORY_SEARCH, "nav_history", "history" -> EntryScreen.HISTORY
        DrawerController.ID_PROJECTS, "nav_projects", "projects" -> EntryScreen.PROJECTS
        DrawerController.ID_TASKS, "nav_running_tasks", "tasks" -> EntryScreen.TASKS
        DrawerController.ID_AGENTS_TEAM, "nav_team", "agents" -> EntryScreen.AGENTS
        DrawerController.ID_DEVICES, "nav_devices", "devices" -> EntryScreen.DEVICES
        DrawerController.ID_MODELS, "nav_models", "models" -> EntryScreen.MODELS
        DrawerController.ID_TOOLS_CONNECTIONS, "nav_tools", "connections", "feature_brain" -> EntryScreen.TOOLS
        DrawerController.ID_RESULTS, "nav_results", "artifacts" -> EntryScreen.RESULTS
        DrawerController.ID_HELP, "nav_help", "help", "onboarding" -> EntryScreen.HELP
        DrawerController.ID_SETTINGS, "nav_workbench", "settings" -> EntryScreen.SETTINGS
        DrawerController.ID_CONSOLE, "nav_control", "nav_console", "control", "console", "nav_pending_approval", "feature_run_logs" -> EntryScreen.CONSOLE
        DrawerController.ID_MCP_PLUGINS, "nav_mcp_plugins", "mcp" -> EntryScreen.MCP_PLUGINS
        DrawerController.ID_TEAM_CHANNEL, "nav_channels", "nav_team_channel", "channels", "channel" -> EntryScreen.TEAM_CHANNEL
        ID_MESSAGE_CENTER, "feature_message_center", "notifications" -> EntryScreen.MESSAGE_CENTER
        else -> EntryScreen.HOME
    }
}
