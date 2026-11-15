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
            Status.IMPLEMENTED, TargetKind.ACTION, "open_feature_center",
            "功", null, null, null, "打开「全部功能」列表：集中列出全部注册表入口；待开发项点开只给诚实提示。"),
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
        // ── 70 屏补漏（2026-11-15）：按钮先行，全部先以待开发可见入口落地 ──
        // ── 父级：对话 ──
        Entry("feature_global_search", "全局搜索", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "搜", null, "nav_chat", "全局搜索待开发（跨会话、项目与频道统一搜索尚未开发）", "原型页 4。"),
        Entry("feature_voice_multimodal", "语音与多模态", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "音", null, "nav_chat", "语音与多模态待开发（语音输入与多模态消息尚未开发）", "原型页 55。"),
        // ── 父级：项目 ──
        Entry("feature_project_overview", "项目概览", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "览", null, "nav_projects", "项目概览待开发（项目信息分散在项目页内，独立概览页尚未开发）", "原型页 14。"),
        Entry("feature_change_diff", "变更对比", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "差", null, "nav_projects", "变更对比待开发（Diff 能力已有，独立对比页尚未开发）", "原型页 17。"),
        Entry("feature_terminal_logs", "终端与日志", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "终", null, "nav_projects", "终端与日志待开发（独立终端页尚未开发，运行日志在控制台）", "原型页 18。"),
        Entry("feature_git_worktree", "Git 与工作树", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "树", null, "nav_projects", "Git 与工作树待开发（独立 Git 与工作树页尚未开发）", "原型页 19。"),
        Entry("feature_run_preview", "运行预览", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "预", null, "nav_projects", "运行预览待开发（独立预览页尚未开发）", "原型页 20。"),
        Entry("feature_browser_workbench", "浏览器工作台", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "浏", null, "nav_projects", "浏览器工作台待开发（尚未开发）", "原型页 48。"),
        Entry("feature_test_matrix", "测试矩阵", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "测", null, "nav_projects", "测试矩阵待开发（独立测试矩阵页尚未开发）", "原型页 49。"),
        // ── 父级：任务 ──
        Entry("feature_plan_checkpoints", "计划与检查点", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "划", null, "nav_running_tasks", "计划与检查点待开发（独立计划页尚未开发）", "原型页 8。"),
        Entry("feature_queue_schedule", "队列与调度", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "队", null, "nav_running_tasks", "队列与调度待开发（独立队列页尚未开发）", "原型页 9。"),
        Entry("feature_code_review", "代码审查", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "审", null, "nav_running_tasks", "代码审查待开发（独立审查页尚未开发）", "原型页 11。"),
        // ── 父级：Agent 与团队 ──
        Entry("feature_team_space", "团队空间", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "空", null, "nav_team", "团队空间待开发（尚未开发）", "原型页 21。"),
        Entry("feature_agent_detail", "Agent 详情", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "详", null, "nav_team", "Agent 详情待开发（Agent 信息在团队页内，独立详情页尚未开发）", "原型页 25。"),
        Entry("feature_orchestration", "协作编排", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "编", null, "nav_team", "协作编排待开发（尚未开发）", "原型页 26。"),
        Entry("feature_import_agent", "导入现有 Agent", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "导", null, "nav_team", "导入现有 Agent 待开发（尚未开发）", "原型页 39。"),
        Entry("feature_session_handoff", "Agent 会话续接", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "续", null, "nav_team", "Agent 会话续接待开发（尚未开发）", "原型页 40。"),
        Entry("feature_agent_compare", "多 Agent 对比", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "比", null, "nav_team", "多 Agent 对比待开发（尚未开发）", "原型页 50。"),
        Entry("feature_visual_orchestration", "可视化编排", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "图", null, "nav_team", "可视化编排待开发（尚未开发）", "原型页 51。"),
        Entry("feature_agent_persona", "Agent 个性与配置", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "性", null, "nav_team", "Agent 个性与配置待开发（尚未开发）", "原型页 56。"),
        // ── 父级：设备 ──
        Entry("feature_device_detail", "设备详情", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "备", null, "nav_devices", "设备详情待开发（设备信息在设备页内，独立详情页尚未开发）", "原型页 28。"),
        Entry("feature_cross_device_relay", "跨设备接力", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "力", null, "nav_devices", "跨设备接力待开发（尚未开发）", "原型页 52。"),
        Entry("feature_network", "通信网络", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "网", null, "nav_devices", "通信网络待开发（含网络路由与离线队列 U68，尚未开发）", "原型页 65。"),
        Entry("feature_network_path", "网络路径详情", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "路", null, "nav_devices", "网络路径详情待开发（尚未开发）", "原型页 66。"),
        // ── 父级：工具与连接 ──
        Entry("feature_model_wizard", "连接模型向导", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "向", null, "nav_tools", "连接模型向导待开发（模型在设置页配置，向导尚未开发）", "原型页 37。"),
        Entry("feature_model_routing", "智能模型路由", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "由", null, "nav_tools", "智能模型路由待开发（尚未开发）", "原型页 38。"),
        // ── 父级：MCP / 插件 ──
        Entry("feature_plugin_install", "插件权限与安装", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "装", null, "nav_mcp_plugins", "插件权限与安装待开发（插件启停已有，权限与安装页尚未开发）", "原型页 68。"),
        Entry("feature_harness_runtime", "Harness 运行时", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "运", null, "nav_mcp_plugins", "Harness 运行时待开发（尚未开发）", "原型页 69。"),
        // ── 父级：模型与技能 ──
        Entry("feature_skills_automation", "技能与自动化", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "技", null, "nav_models_skills", "技能与自动化待开发（技能记忆在任务流程内，独立页尚未开发）", "原型页 31。"),
        // ── 父级：控制 ──
        Entry("feature_events_notifications", "事件与通知", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "件", null, "nav_control", "事件与通知待开发（事件在控制总览内，独立页尚未开发）", "原型页 34。"),
        Entry("feature_message_center", "消息中心", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "息", null, "nav_control", "消息中心待开发（尚未开发）", "原型页 54。"),
        Entry("feature_cross_sync", "跨端同步", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "同", null, "nav_control", "跨端同步待开发（尚未开发）", "原型页 59。"),
        Entry("feature_recovery_rollback", "恢复与回滚", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "复", null, "nav_control", "恢复与回滚待开发（尚未开发）", "原型页 60。"),
        // ── 父级：功能中心 ──
        Entry("feature_auto_loop", "Auto-loop 自主循环", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "环", null, "nav_feature_center", "Auto-loop 自主循环待开发（尚未开发）", "原型页 41。"),
        Entry("feature_loop_guardrails", "循环安全护栏", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "栏", null, "nav_feature_center", "循环安全护栏待开发（尚未开发）", "原型页 42。"),
        Entry("feature_long_memory", "长期记忆", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "忆", null, "nav_feature_center", "长期记忆待开发（尚未开发）", "原型页 43。"),
        Entry("feature_skill_market", "技能市场", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "市", null, "nav_feature_center", "技能市场待开发（尚未开发）", "原型页 44。"),
        Entry("feature_skill_generate", "技能自生成", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "生", null, "nav_feature_center", "技能自生成待开发（技能捕获在任务流程内，自生成页尚未开发）", "原型页 45。"),
        Entry("feature_context_manager", "上下文管理", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "境", null, "nav_feature_center", "上下文管理待开发（上下文在对话流程内，独立管理页尚未开发）", "原型页 46。"),
        Entry("feature_deep_research", "深度研究", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "研", null, "nav_feature_center", "深度研究待开发（尚未开发）", "原型页 47。"),
        Entry("feature_workflow_templates", "工作流模板", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "模", null, "nav_feature_center", "工作流模板待开发（尚未开发）", "原型页 58。"),
        Entry("feature_automation_center", "自动化中心", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "动", null, "nav_feature_center", "自动化中心待开发（尚未开发）", "原型页 61。"),
        Entry("feature_create_automation", "创建自动化", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "建", null, "nav_feature_center", "创建自动化待开发（新建自动化规则 U66 尚未开发）", "原型页 62。"),
        Entry("feature_triggers_wakeup", "触发器与唤醒", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "唤", null, "nav_feature_center", "触发器与唤醒待开发（含启停唤醒 U67，尚未开发）", "原型页 63。"),
        Entry("feature_wakeup_policy", "唤醒与自治策略", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "策", null, "nav_feature_center", "唤醒与自治策略待开发（尚未开发）", "原型页 64。"),
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
