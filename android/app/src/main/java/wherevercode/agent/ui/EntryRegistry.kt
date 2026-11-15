package wherevercode.agent.ui

/**
 * 入口注册表（V5 导航整改最终定稿，2026-11-15）。
 *
 * 一级菜单严格只有三项：对话、项目、控制。
 * 旧入口从三个主入口的子页面内逐层访问。
 * 设置不再是一级菜单：点击抽屉底部账号头像进入独立设置页面。
 * 工作台统一改名「项目」，控制台统一改名「控制」。
 * 70 屏旗舰功能全部保留，通过业务导航逐层访问。
 * 与 iOS 共享导航命名、信息架构和行为契约。
 *
 * 去重规则：
 * - 同一功能在多张图出现多次只做一份。
 * - 不单做、并进别处：智能工作简报并进控制台总览；自动交付与成果归档
 *   并进任务详情的交付页。
 * - 底部五标签导航不要（V5 结构是极简首页 + 抽屉 + 按需工作台）。
 * - 会话从对话入口内访问。
 * - 设置从抽屉底部账号头像进入，不在一级菜单。
 */
object EntryRegistry {

    /** 入口所在位置（首页 / 抽屉分组 / 非导航功能）。 */
    enum class Group {
        /** 首页问候区建议 pill（点按填输入框）。 */
        HOME_SUGGESTION,
        /** 首页顶栏（搜索 / 大脑 / 新建）。 */
        HOME_TOP,
        /** V5 默认一级入口。 */
        DRAWER_TOP,
        /** 抽屉工作空间分组。 */
        DRAWER_WORKSPACE,
        /** 抽屉扩展与资源分组。 */
        DRAWER_EXTENSIONS,
        /** 非导航功能：不占抽屉行，从 parentId 的入口进入。 */
        FEATURE,
    }

    enum class Status {
        /** 已实现：点按真可用。 */
        IMPLEMENTED,
        /** 待开发：点按只给提示，绝不伪装。 */
        TODO,
    }

    enum class TargetKind {
        /** target = DrawerController 入口 id。 */
        DRAWER,
        /** target = 填入输入框的意图文本。 */
        SUGGESTION,
        /** target = 屏 id（从父入口带参数打开，如任务详情带任务 id）。 */
        SCREEN,
        /** target = 应用内动作 id（如 cycle_theme；见 MainActivity.openRegistryEntry）。 */
        ACTION,
        /** 无目标；点按只显示 todoMessage。 */
        TODO,
    }

    data class Entry(
        /** 稳定 id，不改名；测试按此断言去重后清单一一对应。 */
        val id: String,
        /** 中文标题。 */
        val title: String,
        val group: Group,
        val status: Status,
        val targetKind: TargetKind,
        /** 按 targetKind 解释；TODO 时为空串。 */
        val target: String,
        /** 字标（无 drawable 时用；沿用 App 已有的单字字标惯例）。 */
        val iconGlyph: String,
        /** drawable 资源名（有才填；为空时用 iconGlyph）。 */
        val iconDrawable: String?,
        /** FEATURE 组必填：从哪个导航入口进入（须是本表已有的 id）。 */
        val parentId: String?,
        /** TODO 时必填：点按提示文案，须含“待开发”。 */
        val todoMessage: String?,
        /** 备注（部分实现 / 后续批次 / 合并说明）。 */
        val note: String?,
    )

    val ENTRIES: List<Entry> = listOf(
        // ── 首页问候区建议 pill（v4.3-01；点按填输入框，等用户补齐再发）──
        Entry("home_dev_app", "开发一个 App", Group.HOME_SUGGESTION,
            Status.IMPLEMENTED, TargetKind.SUGGESTION, "开发一个 App：",
            "</>", null, null, null, null),
        Entry("home_import_project", "导入项目并交付", Group.HOME_SUGGESTION,
            Status.IMPLEMENTED, TargetKind.SUGGESTION, "导入项目并交付：",
            "↓", null, null, null, null),
        Entry("home_organize_team", "组织多 Agent 团队", Group.HOME_SUGGESTION,
            Status.IMPLEMENTED, TargetKind.SUGGESTION, "组织多 Agent 团队：",
            "团", null, null, null, null),
        Entry("home_automation", "自动化任务", Group.HOME_SUGGESTION,
            Status.IMPLEMENTED, TargetKind.SUGGESTION, "自动化任务：",
            "✦", null, null, null, null),
        // ── 首页顶栏 ──
        Entry("home_search", "会话", Group.HOME_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_HISTORY_SEARCH,
            "搜", "ic_search", null, null,
            "会话也可从对话入口内访问。"),
        Entry("home_brain", "大脑", Group.HOME_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TOOLS_CONNECTIONS,
            "●", null, null, null, "点按进模型与连接（设置页工具与连接分节）。"),
        Entry("home_new", "新建", Group.HOME_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_NEW_CONVERSATION,
            "＋", null, null, null, null),
        // ══════════════════════════════════════════════════════════════
        // V5 导航整改最终定稿：一级菜单严格只有四项
        // ══════════════════════════════════════════════════════════════
        // ── 对话：自然语言 Agent 工作界面（参考 Claude/Codex）──
        Entry("nav_chat", "对话", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_CHAT,
            "聊", null, null, null,
            "对话是执行入口，不要求用户必须先进入任务中心创建正式任务。"),
        // ── 项目：真实项目列表（参考 ChatGPT 项目页面布局）──
        Entry("nav_projects", "项目", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_PROJECTS,
            "项", null, null, null,
            "进入项目后提供任务、代码、文件、Artifacts、审批、执行记录、交付结果等功能。"),
        // ── 频道：频道 / 私聊双分区与真实消息协作 ──
        Entry("nav_channels", "频道", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_CHANNELS,
            "频", null, null, null,
            "频道与私聊独立一级入口；频道内部收纳消息、文件与任务。"),
        // ── 控制：真实平台状态及 Agent/设备/模型/连接器/调度/监控/事件/权限审计 ──
        Entry("nav_control", "控制", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_CONTROL,
            "控", null, null, null,
            "控制功能不因 UI 极简而删减；控制台统一改名「控制」。"),
        // ── 从对话入口内逐层访问的子入口 ──
        Entry("nav_history", "会话", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_HISTORY_SEARCH,
            "搜", "ic_search", null, null, "从对话入口内访问会话历史。"),
        Entry("nav_running_tasks", "任务", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TASKS,
            "行", "ic_clock", null, null, "角标显示真实运行中的任务数；从项目入口内访问。"),
        Entry("nav_results", "成果", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_RESULTS,
            "果", null, null, null, "成果聚合从项目入口内访问。"),
        // ── 从控制入口内逐层访问的子入口 ──
        Entry("nav_team", "Agent 与团队", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_AGENTS_TEAM,
            "团", null, null, null,
            "Agent 数据形状按老项目 agent_directory.py（见本文件头注）；从控制入口内访问。"),
        Entry("nav_devices", "设备", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_DEVICES,
            "设", null, null, null, "从控制入口内访问。"),
        Entry("nav_tools", "工具与连接", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TOOLS_CONNECTIONS,
            "工", null, null, null, "工具中心从控制入口内访问。"),
        Entry("nav_help", "帮助", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_HELP,
            "？", null, null, null, "从设置页内访问。"),
        Entry("nav_workbench", "设置", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_SETTINGS,
            "台", null, null, null,
            "设置不再是一级菜单；点击抽屉底部账号头像进入独立设置页面。"),
        Entry("nav_console", "控制台", Group.DRAWER_EXTENSIONS,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_CONSOLE,
            "控", null, null, null,
            "V5 控制台（已改名「控制」一级入口）：六大页面一份；" +
            "智能工作简报并进总览，不单做；用量与成本仍待开发（无真实用量数据）。"),
        Entry("nav_team_channel", "团队频道", Group.DRAWER_EXTENSIONS,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TEAM_CHANNEL,
            "频", null, null, null,
            "V5 团队频道（P3，2026-11-11）：有界团队聊天 + 只读扇出；" +
            "本机 App + 最多 2 位成员，短消息、3 轮一议、建议-only；" +
            "新消息走 WS 实时推送，断线才轮询兜底。"),
        // ── 抽屉工作空间分组 ──
        Entry("nav_theme", "界面与主题", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.ACTION, "cycle_theme",
            "◐", null, null, null,
            "抽屉内切换主题（跟随系统 → 浅色 → 深色循环，见 MainActivity.cycleTheme；第二步的主题选择器/字体/布局不在此入口）。"),
        Entry("nav_pending_approval", "待审批", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_CONSOLE,
            "审", null, null, null, "打开控制台的待审批模块（当前待决 + 审批历史）。"),
        // ── 抽屉扩展与资源分组 ──
        Entry("nav_mcp_plugins", "MCP / 插件", Group.DRAWER_EXTENSIONS,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_MCP_PLUGINS,
            "插", null, "nav_tools", null,
            "MCP 服务器与插件管理（2026-11-11 第一版）：列表、启用/停用、" +
            "连接状态与工具数；归属工具与连接中心。"),
        Entry("nav_models_skills", "模型与技能", Group.DRAWER_EXTENSIONS,
            Status.TODO, TargetKind.TODO, "",
            "模", null, "nav_tools", "模型与技能待开发（工具中心后续批次）", "归属工具与连接中心。"),
        Entry("nav_custom_modules", "自定义模块", Group.DRAWER_EXTENSIONS,
            Status.TODO, TargetKind.TODO, "",
            "自", null, null, "自定义模块待开发（第二步自定义引擎）", null),
        Entry("nav_feature_center", "功能中心", Group.DRAWER_EXTENSIONS,
            Status.TODO, TargetKind.TODO, "",
            "功", null, null, "功能中心待开发（第二步收纳系统：全部功能一键收纳/极简/标准/专家三模式）", null),
        // ── 非导航功能（从父入口进入）──
        Entry("feature_task_detail", "任务详情", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.SCREEN, "task_detail",
            "任", null, "nav_running_tasks", null,
            "阶段流水线+进度+执行团队+产出+暂停/修改目标/重派/取消+审批已实现（任务详情浮层）；自动交付归档并进交付页，后续批次。"),
        Entry("feature_team_chat", "团队群聊", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "群", null, "nav_team", "团队群聊待开发（群聊/任务/文件/成员四页签，对应 WACF 消息账本）", null),
        Entry("feature_permissions", "执行权限 L0–L4", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "权", null, "nav_workbench", "执行权限待开发（L0–L4 + 适用范围 + 授权范围 + 自动审批策略 + 安全边界；L4 须等 M1 P1-1~P1-4 与配对门禁完成）", null),
        Entry("feature_project_files", "项目文件与代码", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.SCREEN, "project_files",
            "码", null, "nav_projects", null,
            "文件树只读查看已实现；代码编辑器与变更 Diff 待开发。"),
        Entry("feature_topology", "团队拓扑", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "拓", null, "nav_team", "团队拓扑待开发（Agent 与设备连线图 + 在线/离线 + 消息/任务通道）", null),
        Entry("feature_usage_cost", "用量与成本", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "费", null, "nav_console", "用量与成本待开发（只是界面，不产生计费调用）", null),
        Entry("feature_run_logs", "运行日志", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_CONSOLE,
            "志", null, "nav_console", null,
            "控制台的执行日志模块：全局真实事件流；5 档时称 Matrix 实时事件流。"),
        Entry("feature_unified_access", "统一接入与通信中心", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "接", null, "nav_tools", "统一接入待开发（MCP / HTTP API / WebSocket / CLI/Worker / Webhook 适配器层）", null),
        Entry("feature_auto_exec", "对话中自动执行", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "▶", null, "nav_chat", "对话中自动执行待开发（自主执行；实时进度展示已实现：聊天时间线工具卡）", null),
        Entry("feature_quick_actions", "快速操作与自定义快捷入口", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "快", null, "nav_chat", "快速操作待开发（新建任务/添加项目/添加 Agent/连接设备/安装插件/添加 MCP + 自定义快捷入口；智能选择=团队下拉已实现）",
            "入口形态见 v4.3-02（快速操作面板 + 底部团队就绪状态条）。"),
        Entry("feature_nesting", "收纳系统与自定义引擎", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "收", null, "nav_feature_center", "收纳系统待开发（第二步：全部功能一键收纳/极简/标准/专家三模式/自定义首页/模块管理）", null),
    )

    fun find(id: String): Entry? = ENTRIES.firstOrNull { it.id == id }

    fun byGroup(group: Group): List<Entry> = ENTRIES.filter { it.group == group }

    /** 首页问候区 4 个建议 pill（按注册表顺序）。 */
    fun homeSuggestions(): List<Entry> = byGroup(Group.HOME_SUGGESTION)

    /**
     * 注册表自检（供回归测试与 R2 复用时调用）：返回全部违规描述，
     * 空表 = 通过。规则：id 唯一；TODO 必有含“待开发”的提示；
     * 已实现必有非空目标；FEATURE 组必有已存在的父入口。
     */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val ids = ENTRIES.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { dup ->
            errors += "重复 id：$dup"
        }
        ENTRIES.forEach { entry ->
            if (entry.status == Status.TODO) {
                val msg = entry.todoMessage
                if (msg.isNullOrBlank() || !msg.contains("待开发")) {
                    errors += "待开发入口缺提示：${entry.id}"
                }
            } else {
                if (entry.target.isBlank()) {
                    errors += "已实现入口缺目标：${entry.id}"
                }
            }
            if (entry.group == Group.FEATURE) {
                val parent = entry.parentId
                if (parent.isNullOrBlank()) {
                    errors += "功能入口缺父入口：${entry.id}"
                } else if (find(parent) == null) {
                    errors += "功能入口父入口不存在：${entry.id} -> $parent"
                }
            }
        }
        return errors
    }
}
