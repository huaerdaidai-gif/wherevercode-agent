package wherevercode.agent.ui

/**
 * V5 导航整改最终定稿（2026-11-15）：抽屉入口 → 屏幕的路由表。
 *
 * 一级菜单严格只有四项：对话、项目、频道、控制。
 * - 对话 (ID_CHAT) → 聊天首页（自然语言 Agent 工作界面）
 * - 项目 (ID_PROJECTS) → 项目列表页
 * - 频道 (ID_CHANNELS) → 频道 / 私聊双分区首页
 * - 控制 (ID_CONTROL) → 控制首页（平台状态及 Agent/设备/模型/连接器等）
 *
 * 旧入口 id 保留兼容：从三个主入口的子页面内逐层访问。
 * 未知 id 路由到首页（对话），不崩溃。
 */
enum class EntryScreen {
    /** 对话首页（聊天）。 */
    HOME,
    /** 会话历史列表。 */
    HISTORY,
    /** 项目列表与项目详情。 */
    PROJECTS,
    /** 任务列表与任务详情。 */
    TASKS,
    /** Agent 与团队管理。 */
    AGENTS,
    /** 设备列表。 */
    DEVICES,
    /** 工具与连接。 */
    TOOLS,
    /** 成果聚合。 */
    RESULTS,
    /** 帮助。 */
    HELP,
    /** 设置（独立页面，从抽屉底部账号头像进入）。 */
    SETTINGS,
    /** 控制台（改名「控制」，平台状态与监控）。 */
    CONSOLE,
    /** MCP / 插件管理。 */
    MCP_PLUGINS,
    /** 频道 / 私聊双分区首页。 */
    CHANNELS,
    /** 频道聊天详情：消息 / 文件 / 任务。 */
    TEAM_CHANNEL,
}

object DrawerRouter {
    fun screenFor(entryId: String): EntryScreen = when (entryId) {
        // ── 四项一级入口 ──
        DrawerController.ID_CHAT -> EntryScreen.HOME
        DrawerController.ID_PROJECTS -> EntryScreen.PROJECTS
        DrawerController.ID_CHANNELS -> EntryScreen.CHANNELS
        DrawerController.ID_CONTROL -> EntryScreen.CONSOLE
        // ── 旧入口 id（保留兼容，从子页面内逐层访问）──
        DrawerController.ID_NEW_CONVERSATION -> EntryScreen.HOME
        DrawerController.ID_HISTORY_SEARCH -> EntryScreen.HISTORY
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
