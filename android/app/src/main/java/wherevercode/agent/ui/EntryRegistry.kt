package wherevercode.agent.ui

/**
 * 入口注册表（双端对齐 89 条唯一功能入口主清单 EntryRegistry v1.0）。
 *
 * 依据 2026-11-15 谷歌网盘《WhereverCode V5 双端唯一功能主清单 v1.0（2026-11-15）.docx》
 * 与 iOS EntryRegistry.swift 100% 对齐：共 89 条核心条目（去重不删功能，稳定 ID 全局唯一）。
 *
 * 状态规矩：已实现点按真可用；待开发点按只给提示（todoMessage），绝不伪装已实现。
 * TODO 入口的 todoMessage 必须非空且含“待开发”。
 */
object EntryRegistry {

    /** 入口所在位置（首页建议 / 首页顶栏 / 抽屉父级 / 工作空间 / 扩展 / 功能）。 */
    enum class Group(val label: String) {
        HOME_SUGGESTION("首页建议"),
        HOME_TOP("首页顶栏"),
        DRAWER_TOP("一级入口"),
        DRAWER_WORKSPACE("工作空间"),
        DRAWER_EXTENSIONS("扩展"),
        FEATURE("功能（从父级内进入）"),
    }

    enum class Status(val label: String) {
        IMPLEMENTED("已实现"),
        TODO("待开发"),
    }

    enum class TargetKind {
        /** target = DrawerController 入口 id。 */
        DRAWER,
        /** target = 填入输入框的意图文本。 */
        SUGGESTION,
        /** target = 屏 id（从父入口带参数打开，如任务详情）。 */
        SCREEN,
        /** target = 应用内动作 id（如 cycle_theme / feature_center / create_task）。 */
        ACTION,
        /** 无目标；点按只显示 todoMessage。 */
        TODO,
    }

    data class Entry(
        val id: String,
        val title: String,
        val group: Group,
        val status: Status,
        val targetKind: TargetKind,
        val target: String,
        val iconGlyph: String,
        val iconDrawable: String?,
        val parentId: String?,
        val todoMessage: String?,
        val note: String?,
    )

    val ENTRIES: List<Entry> = listOf(
        // ── 一、对话分组 (15 条) ──
        Entry("home_dev_app", "开发一个 App", Group.HOME_SUGGESTION,
            Status.IMPLEMENTED, TargetKind.SUGGESTION, "开发一个 App：",
            "✦", "ic_chat", null, null, null),
        Entry("home_import_project", "导入项目并交付", Group.HOME_SUGGESTION,
            Status.IMPLEMENTED, TargetKind.SUGGESTION, "导入项目并交付：",
            "📁", "ic_folder", null, null, null),
        Entry("home_organize_team", "组织多 Agent 团队", Group.HOME_SUGGESTION,
            Status.IMPLEMENTED, TargetKind.SUGGESTION, "组织多 Agent 团队：",
            "👥", "ic_team", null, null, null),
        Entry("home_automation", "自动化任务", Group.HOME_SUGGESTION,
            Status.IMPLEMENTED, TargetKind.SUGGESTION, "自动化任务：",
            "⚡", "ic_clock", null, null, null),
        Entry("home_search", "会话", Group.HOME_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_HISTORY_SEARCH,
            "搜", "ic_search", null, null, "会话也可从 V5 一级抽屉入口到达。"),
        Entry("home_new", "新建", Group.HOME_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_NEW_CONVERSATION,
            "＋", "ic_new_conversation", null, null, null),
        Entry("nav_chat", "对话", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_NEW_CONVERSATION,
            "聊", "ic_chat", null, null, null),
        Entry("nav_history", "会话", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_HISTORY_SEARCH,
            "搜", "ic_search", null, null, null),
        Entry("feature_auto_exec", "对话中自动执行", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "▶", null, "nav_chat",
            "对话中自动执行待开发（自主执行；实时进度展示已实现：聊天时间线工具卡）", null),
        Entry("feature_quick_actions", "快速操作与自定义快捷入口", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.ACTION, "quick_actions",
            "快", null, "nav_chat", null, null),
        Entry("feature_global_search", "全局搜索", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "搜", "ic_search", "nav_chat",
            "全局搜索待开发（跨会话、项目与频道搜索尚未开发）", null),
        Entry("feature_voice_multimodal", "语音与多模态", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.ACTION, "voice",
            "麦", null, "nav_chat", null, null),
        Entry("feature_long_memory", "长期记忆", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "忆", null, "nav_chat",
            "长期记忆待开发（尚未开发）", null),
        Entry("feature_context_manager", "上下文管理", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "文", null, "nav_chat",
            "上下文管理待开发（上下文在对话流程内，独立管理页尚未开发）", null),
        Entry("feature_deep_research", "深度研究", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "研", null, "nav_chat",
            "深度研究待开发（尚未开发）", null),

        // ── 二、项目分组 (23 条) ──
        Entry("nav_projects", "项目", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_PROJECTS,
            "项", "ic_folder", null, null, "工作空间组的“我的项目”是同一入口的快捷方式。"),
        Entry("nav_running_tasks", "任务", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TASKS,
            "行", "ic_clock", null, null, "角标显示真实运行中的任务数。"),
        Entry("nav_results", "成果", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_RESULTS,
            "果", "ic_results", null, null, "成果聚合已实现；自动交付归档并进任务详情交付页。"),
        Entry("feature_task_detail", "任务详情", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.SCREEN, "task_detail",
            "任", null, "nav_running_tasks", null, "阶段流水线+进度+执行团队+产出+暂停/修改目标/取消+审批已实现。"),
        Entry("feature_project_files", "项目文件与代码", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.SCREEN, "project_files",
            "码", null, "nav_projects", null, "文件树只读查看已实现。"),
        Entry("feature_project_overview", "项目概览", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.SCREEN, "project_detail",
            "览", null, "nav_projects", null, null),
        Entry("feature_change_diff", "变更对比", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.SCREEN, "diff",
            "比", null, "nav_projects", null, null),
        Entry("feature_terminal_logs", "终端与日志", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "终", null, "nav_projects",
            "终端与日志待开发（远端 Hand 执行尚未接入）", null),
        Entry("feature_git_worktree", "Git 与工作树", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "枝", null, "nav_projects",
            "Git 与工作树待开发（远端 Hand 同步尚未接入）", null),
        Entry("feature_run_preview", "运行预览", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "视", null, "nav_projects",
            "运行预览待开发（尚未开发）", null),
        Entry("feature_browser_workbench", "浏览器工作台", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "览", null, "nav_projects",
            "浏览器工作台待开发（服务端浏览器快照服务未接入）", null),
        Entry("feature_test_matrix", "测试矩阵", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "阵", null, "nav_projects",
            "测试矩阵待开发（仅作为内置契约核对视图，机械检查未运行）", null),
        Entry("feature_plan_checkpoints", "计划与检查点", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "划", null, "nav_running_tasks",
            "计划与检查点待开发（已在任务流水线内呈现，独立配置页尚未开发）", null),
        Entry("feature_queue_schedule", "队列与调度", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "队", null, "nav_running_tasks",
            "队列与调度待开发（尚未开发）", null),
        Entry("feature_code_review", "代码审查", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "审", null, "nav_running_tasks",
            "代码审查待开发（已在任务流水线内呈现，独立配置页尚未开发）", null),
        Entry("feature_create_task", "新建任务", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.ACTION, "create_task",
            "建", null, "nav_running_tasks", null, null),
        Entry("feature_auto_loop", "Auto-loop 自主循环", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "环", null, "nav_projects",
            "Auto-loop 自主循环待开发（尚未开发）", null),
        Entry("feature_loop_guardrails", "循环安全护栏", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "栏", null, "nav_projects",
            "循环安全护栏待开发（尚未开发）", null),
        Entry("feature_workflow_templates", "工作流模板", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "模", null, "nav_projects",
            "工作流模板待开发（尚未开发）", null),
        Entry("feature_automation_center", "自动化中心", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "自", null, "nav_projects",
            "自动化中心待开发（尚未开发）", null),
        Entry("feature_create_automation", "创建自动化", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "创", null, "nav_projects",
            "创建自动化待开发（新建自动化规则 U66 尚未开发）", null),
        Entry("feature_triggers_wakeup", "触发器与唤醒", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "唤", null, "nav_projects",
            "触发器与唤醒待开发（含启停唤醒 U67，尚未开发）", null),
        Entry("feature_code_editor", "代码编辑器", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.SCREEN, "code_editor",
            "编", null, "nav_projects", null, null),

        // ── 三、频道分组 (7 条) ──
        Entry("nav_channels", "频道", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TEAM_CHANNEL,
            "频", "ic_chat", null, null, null),
        Entry("nav_team_channel", "频道", Group.DRAWER_EXTENSIONS,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TEAM_CHANNEL,
            "频", "ic_chat", null, null, "有界团队聊天 + 只读扇出。"),
        Entry("feature_channel_manage", "频道管理", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.ACTION, "channel_manage",
            "管", null, "nav_channels", null, "支持新建、改名、归档及成员管理；无法确认频道 owner 时不提供删除。"),
        Entry("feature_channel_files", "频道文件", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "件", null, "nav_channels",
            "频道文件待开发（附件/网盘引用/预览下载）", null),
        Entry("feature_direct_message", "私聊服务", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "私", null, "nav_channels",
            "私聊服务待开发（与指定人员/Agent 独立私聊通道）", null),
        Entry("feature_team_space", "团队空间", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "空", null, "nav_channels",
            "团队空间待开发（成员与频道同页的统一空间 + 创建频道尚未开发）", null),
        Entry("feature_message_center", "消息中心", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerRouter.ID_MESSAGE_CENTER,
            "消", null, "nav_channels", null, null),

        // ── 四、控制分组 (35 条) ──
        Entry("feature_brain", "大脑", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TOOLS_CONNECTIONS,
            "●", null, "nav_feature_center", null, "点按进模型与连接（设置页大脑分节）。"),
        Entry("nav_control", "控制", Group.DRAWER_TOP,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_CONSOLE,
            "控", "ic_console", null, null, "全局控制与运行工作台。"),
        Entry("nav_team", "Agent 与团队", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_AGENTS_TEAM,
            "团", "ic_team", null, null, "Agent 管理与成员列表。"),
        Entry("nav_devices", "设备", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_DEVICES,
            "设", "ic_device", null, null, "设备列表与授权目录。"),
        Entry("nav_tools", "工具与连接", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_TOOLS_CONNECTIONS,
            "工", "ic_tools", null, null, "模型连接与平台 Token 配置。"),
        Entry("nav_pending_approval", "待审批", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_CONSOLE,
            "审", "ic_approval", null, null, "打开控制台的待审批模块。"),
        Entry("nav_mcp_plugins", "MCP / 插件", Group.DRAWER_EXTENSIONS,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_MCP_PLUGINS,
            "插", "ic_puzzle", "nav_tools", null, "MCP 服务器与插件管理。"),
        Entry("nav_models_skills", "模型与技能", Group.DRAWER_EXTENSIONS,
            Status.TODO, TargetKind.TODO, "",
            "模", "ic_cube", "nav_tools",
            "模型与技能待开发（工具中心后续批次）", "归属工具与连接中心。"),
        Entry("nav_custom_modules", "自定义模块", Group.DRAWER_EXTENSIONS,
            Status.TODO, TargetKind.TODO, "",
            "自", "ic_grid", null,
            "自定义模块待开发（第二步自定义引擎）", null),
        Entry("feature_topology", "团队拓扑", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "拓", null, "nav_team",
            "团队拓扑待开发（Agent 与设备连线图 + 在线/离线 + 消息/任务通道）", null),
        Entry("feature_usage_cost", "用量与成本", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "费", null, "nav_control",
            "用量与成本待开发（只是界面，不产生计费调用）", null),
        Entry("feature_run_logs", "运行日志", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_CONSOLE,
            "志", null, "nav_control", null, "控制台执行日志模块。"),
        Entry("feature_unified_access", "统一接入与通信中心", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "接", null, "nav_tools",
            "统一接入待开发（MCP / HTTP API / WebSocket / CLI/Worker / Webhook 适配器层）", null),
        Entry("feature_agent_detail", "Agent 详情", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.SCREEN, "agent_detail",
            "细", null, "nav_team", null, null),
        Entry("feature_orchestration", "协作编排", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "编", null, "nav_team",
            "协作编排待开发（Agent 拓扑与任务分配策略尚未开发）", null),
        Entry("feature_import_agent", "导入现有 Agent", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "导", null, "nav_team",
            "导入现有 Agent 待开发（从外部目录导入配置与技能尚未开发）", null),
        Entry("feature_session_handoff", "Agent 会话续接", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "续", null, "nav_team",
            "Agent 会话续接待开发（从上一次任务中提取上下文尚未开发）", null),
        Entry("feature_agent_compare", "多 Agent 对比", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "比", null, "nav_team",
            "多 Agent 对比待开发（尚未开发）", null),
        Entry("feature_visual_orchestration", "可视化编排", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "图", null, "nav_team",
            "可视化编排待开发（尚未开发）", null),
        Entry("feature_agent_persona", "Agent 个性与配置", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "格", null, "nav_team",
            "Agent 个性与配置待开发（提示词、温度与系统人设配置尚未开发）", null),
        Entry("feature_device_detail", "设备详情", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.SCREEN, "device_detail",
            "备", null, "nav_devices", null, null),
        Entry("feature_cross_device_relay", "跨设备接力", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "力", null, "nav_devices",
            "跨设备接力待开发（跨端任务接力与状态同步尚未开发）", null),
        Entry("feature_network", "通信网络", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "网", null, "nav_tools",
            "通信网络待开发（传输路径与离线队列尚未开发）", null),
        Entry("feature_network_path", "网络路径详情", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "径", null, "nav_tools",
            "网络路径详情待开发（尚未开发）", null),
        Entry("feature_model_wizard", "连接模型向导", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "导", null, "nav_tools",
            "连接模型向导待开发（尚未开发）", null),
        Entry("feature_model_routing", "智能模型路由", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_MODELS,
            "由", null, "nav_tools",
            null, null),
        Entry("feature_plugin_install", "插件权限与安装", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "权", null, "nav_mcp_plugins",
            "插件权限与安装待开发（插件启停已有，权限与安装页尚未开发）", null),
        Entry("feature_harness_runtime", "Harness 运行时", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "套", null, "nav_mcp_plugins",
            "Harness 运行时待开发（尚未开发）", null),
        Entry("feature_extension_manifest", "扩展 Manifest 导入", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "单", null, "nav_mcp_plugins",
            "扩展 Manifest 导入待开发（通过 Manifest 规范导入自定义能力）", null),
        Entry("feature_skills_automation", "技能与自动化", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "技", null, "nav_models_skills",
            "技能与自动化待开发（技能记忆在任务流程内，独立页尚未开发）", null),
        Entry("feature_events_notifications", "事件与通知", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "讯", null, "nav_control",
            "事件与通知待开发（事件在控制总览内，独立页尚未开发）", null),
        Entry("feature_cross_sync", "跨端同步", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "步", null, "nav_control",
            "跨端同步待开发（尚未开发）", null),
        Entry("feature_recovery_rollback", "恢复与回滚", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "滚", null, "nav_control",
            "恢复与回滚待开发（尚未开发）", null),
        Entry("feature_skill_market", "技能市场", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "市", null, "nav_models_skills",
            "技能市场待开发（尚未开发）", null),
        Entry("feature_skill_generate", "技能自生成", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "生", null, "nav_models_skills",
            "技能自生成待开发（技能捕获在任务流程内，自生成页尚未开发）", null),

        // ── 五、账号/设置（独立收纳，7 条）──
        Entry("nav_help", "帮助", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_HELP,
            "？", "ic_help", null, null, "帮助与使用指南。"),
        Entry("nav_workbench", "设置", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_SETTINGS,
            "设", "ic_settings", null, null, "系统设置与账号配置。"),
        Entry("nav_theme", "界面与主题", Group.DRAWER_WORKSPACE,
            Status.IMPLEMENTED, TargetKind.ACTION, "cycle_theme",
            "◐", "ic_theme", null, null, "切换深色/浅色/跟随系统。"),
        Entry("feature_permissions", "执行权限 L0–L4", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "限", null, "nav_workbench",
            "执行权限待开发（L0–L4 + 适用范围 + 授权范围 + 自动审批策略 + 安全边界；L4 须等 M1 P1-1~P1-4 与配对门禁完成）", null),
        Entry("feature_wakeup_policy", "唤醒与自治策略", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "策", null, "nav_workbench",
            "唤醒与自治策略待开发（尚未开发）", null),
        Entry("feature_autonomy", "自主度（监督 / 全自动）", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_SETTINGS,
            "主", null, "nav_workbench", null, null),
        Entry("feature_app_update", "应用更新", Group.FEATURE,
            Status.IMPLEMENTED, TargetKind.DRAWER, DrawerController.ID_SETTINGS,
            "更", null, "nav_workbench", null, null),

        // ── 六、功能中心（跨导航总索引，2 条）──
        Entry("nav_feature_center", "功能中心", Group.DRAWER_EXTENSIONS,
            Status.IMPLEMENTED, TargetKind.ACTION, "feature_center",
            "功", "ic_dashboard", null, null, "全功能索引目录。"),
        Entry("feature_nesting", "收纳系统与自定义引擎", Group.FEATURE,
            Status.TODO, TargetKind.TODO, "",
            "收", null, "nav_feature_center",
            "收纳系统与自定义引擎待开发（极简 / 标准 / 专家模式、自定义首页尚未开发）", null),
    )

    /** 查找条目（含旧别名兼容：nav_console -> nav_control; feature_team_chat -> nav_team_channel; home_brain -> feature_brain）。 */
    fun find(id: String): Entry? {
        val direct = ENTRIES.firstOrNull { it.id == id }
        if (direct != null) return direct
        return when (id) {
            "nav_console" -> ENTRIES.firstOrNull { it.id == "nav_control" }
            "feature_team_chat" -> ENTRIES.firstOrNull { it.id == "nav_team_channel" }
            "home_brain" -> ENTRIES.firstOrNull { it.id == "feature_brain" }
            else -> null
        }
    }

    fun byGroup(group: Group): List<Entry> = ENTRIES.filter { it.group == group }

    /**
     * 「全部功能」每行副标题。有中文说明或待开发提示就用它；
     * 否则不把英文 route id（target）露出来，改用中文分组名。
     */
    fun listSubtitle(entry: Entry): String {
        val note = entry.note?.takeIf { it.isNotBlank() }
        if (note != null) return note
        val todo = entry.todoMessage?.takeIf { it.isNotBlank() }
        if (todo != null) return todo
        val target = entry.target
        if (target.isNotBlank() && !isEnglishRouteId(target)) return target
        return entry.group.label
    }

    /** 纯英文 route / screen / action id（如 code_editor、new_conversation）。 */
    fun isEnglishRouteId(value: String): Boolean =
        value.matches(Regex("[A-Za-z][A-Za-z0-9_./:-]*"))

    /** 首页问候区 4 个建议 pill（按注册表顺序：开发一个 App / 导入项目并交付 / 组织多 Agent 团队 / 自动化任务）。 */
    fun homeSuggestions(): List<Entry> = byGroup(Group.HOME_SUGGESTION)

    /**
     * 注册表自检：返回全部违规描述，空表 = 通过。
     * 规则：id 唯一（89 项）；TODO 必有含“待开发”的提示；
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
