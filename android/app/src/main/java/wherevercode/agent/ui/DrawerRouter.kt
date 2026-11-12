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
 * 路由后要附加“滚动到大脑分节顶部”。
 */
enum class EntryScreen {
    HOME,
    HISTORY,
    PROJECTS,
    TASKS,
    AGENTS,
    DEVICES,
    TOOLS,
    RESULTS,
    HELP,
    SETTINGS,
    /** V5 控制台：按需展开的专业工作台（抽屉“扩展与资源”分组进入）。 */
    CONSOLE,
    /** V5 MCP / 插件：扩展管理屏（抽屉“扩展与资源”分组进入）。 */
    MCP_PLUGINS,
    /** V5 团队频道：有界团队聊天 + 只读扇出（抽屉“扩展与资源”分组进入）。 */
    TEAM_CHANNEL,
}

object DrawerRouter {
    fun screenFor(entryId: String): EntryScreen = when (entryId) {
        DrawerController.ID_NEW_CONVERSATION -> EntryScreen.HOME
        DrawerController.ID_HISTORY_SEARCH -> EntryScreen.HISTORY
        DrawerController.ID_PROJECTS -> EntryScreen.PROJECTS
        DrawerController.ID_TASKS -> EntryScreen.TASKS
        DrawerController.ID_AGENTS_TEAM -> EntryScreen.AGENTS
        DrawerController.ID_DEVICES -> EntryScreen.DEVICES
        DrawerController.ID_TOOLS_CONNECTIONS -> EntryScreen.TOOLS
        DrawerController.ID_RESULTS -> EntryScreen.RESULTS
        DrawerController.ID_HELP -> EntryScreen.HELP
        DrawerController.ID_SETTINGS -> EntryScreen.SETTINGS
        DrawerController.ID_CONSOLE -> EntryScreen.CONSOLE
        DrawerController.ID_MCP_PLUGINS -> EntryScreen.MCP_PLUGINS
        DrawerController.ID_TEAM_CHANNEL -> EntryScreen.TEAM_CHANNEL
        else -> EntryScreen.HOME
    }
}
