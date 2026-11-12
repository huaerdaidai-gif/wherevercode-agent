package wherevercode.agent.ui

/**
 * 入口注册表（V5 C2-3-R1d，maintainer 2026-11-10 补充六：去重汇集）。
 *
 * 唯一事实来源：V5 原型图、v2 七图、V4.3 八张图里去重后的全部功能入口
 * 都登记在这里，一份，不重复造轮子。首页（问候建议 pill + 顶栏）与
 * 抽屉（R2）都从这份注册表取入口；抽屉不得另起一份注册表。
 *
 * 去重规则（maintainer 原话 + 补充六清单）：
 * - 同一功能在多张图出现多次只做一份：任务详情、控制台（六大页面）、
 *   执行权限 L0–L4、团队群聊、项目管理、工具与连接中心、Agent 与设备
 *   管理、团队拓扑、用量与成本、运行日志、项目文件与代码各一份。
 * - 不单做、并进别处：智能工作简报并进控制台总览；自动交付与成果归档
 *   并进任务详情的交付页（与成果聚合共用）；我的工作台与功能管理/设置
 *   合并为一份设置/工作台（开关只留一套）。
 * - 底部五标签导航不要（V5 结构是极简首页 + 抽屉 + 按需工作台）；图里
 *   的 America VPS 只是设备列表里的一个节点，验收不依赖它。
 * - 会话从顶栏与 V5 一级抽屉入口均可到达。
 * - 工作台行与底部齿轮是同一入口（nav_workbench）的两个位置，不是两
 *   个入口；项目行与“我的项目”同理（同一 nav_projects 入口的快捷方式）。
 *
 * 状态规矩（Phase B C2 已有规矩）：已实现点按真可用；待开发点按只给
 * 提示（todoMessage），绝不伪装已实现。TODO 入口的 todoMessage 必须
 * 非空且含“待开发”。
 *
 * Agent 数据形状（补充六“不重复造轮子”）：团队管理功能后续批次落地时，
 * Agent 记录按老项目 agent_directory.py 的完整结构来，不重新定义。
 * 已只读核实（old wherevercode main，agent_directory.py，
 * sha 57eee083…，10,412 字节）：每条目字段为 id / name / kind
 * （device|cloud）/ access（cli|app|connector|webhook|api|manual）/
 * wake（cli|app|poll|realtime|api|manual）/ wake_label / capabilities
 * （默认 ['dev','review']）/ quota_rule / requires_repo / vendor /
 * runner / binary / binary_env / platform / dispatchable / known_limits；
 * 云端另有 add_hint，API 类另有 base_url；共 14 条（设备 8 条：
 * codex-cli、claude-code-cli、muse-code-cli、gemini-cli、codebuddy-cli、
 * agy、local-model、claude-desktop-code；云端 6 条：connector、webhook、
 * jules、manus、mistral、manual）。新 App 不照搬该文件的服务端
 * 派单逻辑，只复用字段形状。
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
            "会话也可从 V5 一级抽屉入口到达。"),
        Entry("home_brain", "大脑", Group.HOME_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TOOLS_CONNECTIONS,
            "●", null, null, null, "点按进模型与连接（设置页工具与连接分节）。"),
        Entry("home_new", "新建", Group.HOME_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_NEW_CONVERSATION,
            "＋", null, null, null, null),
        // ── V5 默认一级入口（V5-UI.md §三）──
        Entry("nav_chat", "新对话", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_NEW_CONVERSATION,
            "聊", null, null, null, null),
        Entry("nav_history", "会话", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_HISTORY_SEARCH,
            "搜", "ic_search", null, null, null),
        Entry("nav_projects", "项目", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_PROJECTS,
            "项", null, null, null, "工作空间组的“我的项目”是同一入口的快捷方式。"),
        Entry("nav_running_tasks", "任务", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TASKS,
            "行", "ic_clock", null, null, "角标显示真实运行中的任务数。"),
        Entry("nav_team", "团队（Agent / 成员）", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_AGENTS_TEAM,
            "团", null, null, null,
            "Agent 数据形状按老项目 agent_directory.py（见本文件头注）。"),
        Entry("nav_devices", "设备", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_DEVICES,
            "设", null, null, null, null),
        Entry("nav_tools", "工具与连接", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TOOLS_CONNECTIONS,
            "工", null, null, null, "工具中心（每项启用开关）待开发，当前进设置页的工具与连接分节。"),
        Entry("nav_results", "成果", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_RESULTS,
            "果", null, null, null, "成果聚合（批 3 B3-2）已实现；自动交付归档并进任务详情交付页，后续批次。"),
        Entry("nav_help", "帮助", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_HELP,
            "？", null, null, null, "V5-UI.md §三默认一级入口。"),
        Entry("nav_workbench", "设置", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_SETTINGS,
            "台", null, null, null, "底部设置按钮是同一入口的快捷方式。"),
        Entry("nav_console", "控制台", Group.DRAWER_EXTENSIONS,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_CONSOLE,
            "控", null, null, null,
            "V5 控制台（2026-11-11）：按需展开的专业工作台，按 PROJECT.md §7 / V5-UI.md §三；" +
            "六大页面一份；智能工作简报并进总览，不单做；用量与成本仍待开发（无真实用量数据）。"),
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
