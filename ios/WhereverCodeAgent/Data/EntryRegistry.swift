import Foundation

// MARK: - 双端对齐 89 条唯一功能入口主清单 (EntryRegistry v1.0)
// 依据 2026-11-15 谷歌网盘《WhereverCode V5 双端唯一功能主清单 v1.0（2026-11-15）.docx》
// 融合 Muse Android 89 条审计 (5d66f471) 与 AGY iOS 70 屏旗舰独立审计 (5ecff306)

public enum EntryRegistry {
    public enum Group: String, CaseIterable, Sendable {
        case homeSuggestion = "首页建议"
        case homeTop = "首页顶栏"
        case drawerTop = "一级入口"
        case drawerWorkspace = "工作空间"
        case drawerExtensions = "扩展"
        case feature = "功能（从父级内进入）"
    }

    public enum Status: String, Sendable {
        case implemented = "已实现"
        case todo = "待开发"
    }

    public struct Entry: Identifiable, Equatable, Sendable {
        public let id: String
        public let title: String
        public let group: Group
        public let status: Status
        public let parentId: String?
        public let todoMessage: String?
        public let routeId: String

        public init(
            id: String,
            title: String,
            group: Group,
            status: Status,
            parentId: String?,
            todoMessage: String?,
            routeId: String
        ) {
            self.id = id
            self.title = title
            self.group = group
            self.status = status
            self.parentId = parentId
            self.todoMessage = todoMessage
            self.routeId = routeId
        }
    }

    /// 双端唯一事实主清单：共 89 条核心条目 (去重去重不删功能，稳定ID全局唯一)
    public static let ENTRIES: [Entry] = [
        // MARK: - 一、对话分组 (15 条)
        Entry(id: "home_dev_app", title: "开发一个 App", group: .homeSuggestion, status: .implemented, parentId: nil, todoMessage: nil, routeId: "chat"),
        Entry(id: "home_import_project", title: "导入项目并交付", group: .homeSuggestion, status: .implemented, parentId: nil, todoMessage: nil, routeId: "projects"),
        Entry(id: "home_organize_team", title: "组织多 Agent 团队", group: .homeSuggestion, status: .implemented, parentId: nil, todoMessage: nil, routeId: "agents"),
        Entry(id: "home_automation", title: "自动化任务", group: .homeSuggestion, status: .implemented, parentId: nil, todoMessage: nil, routeId: "automation"),
        Entry(id: "home_search", title: "会话", group: .homeTop, status: .implemented, parentId: nil, todoMessage: nil, routeId: "history"),
        Entry(id: "home_new", title: "新建", group: .homeTop, status: .implemented, parentId: nil, todoMessage: nil, routeId: "chat"),
        Entry(id: "nav_chat", title: "对话", group: .drawerTop, status: .implemented, parentId: nil, todoMessage: nil, routeId: "chat"),
        Entry(id: "nav_history", title: "会话", group: .drawerWorkspace, status: .implemented, parentId: nil, todoMessage: nil, routeId: "history"),
        Entry(id: "feature_auto_exec", title: "对话中自动执行", group: .feature, status: .todo, parentId: "nav_chat", todoMessage: "对话中自动执行待开发（自主执行；实时进度展示已实现：聊天时间线工具卡）", routeId: "conversation"),
        Entry(id: "feature_quick_actions", title: "快速操作与自定义快捷入口", group: .feature, status: .implemented, parentId: "nav_chat", todoMessage: nil, routeId: "compose"),
        Entry(id: "feature_global_search", title: "全局搜索", group: .feature, status: .todo, parentId: "nav_chat", todoMessage: "全局搜索待开发（跨会话、项目与频道搜索尚未开发）", routeId: "search"),
        Entry(id: "feature_voice_multimodal", title: "语音与多模态", group: .feature, status: .implemented, parentId: "nav_chat", todoMessage: nil, routeId: "voice"),
        Entry(id: "feature_long_memory", title: "长期记忆", group: .feature, status: .todo, parentId: "nav_chat", todoMessage: "长期记忆待开发（尚未开发）", routeId: "memory"),
        Entry(id: "feature_context_manager", title: "上下文管理", group: .feature, status: .todo, parentId: "nav_chat", todoMessage: "上下文管理待开发（上下文在对话流程内，独立管理页尚未开发）", routeId: "context"),
        Entry(id: "feature_deep_research", title: "深度研究", group: .feature, status: .todo, parentId: "nav_chat", todoMessage: "深度研究待开发（尚未开发）", routeId: "research"),

        // MARK: - 二、项目分组 (23 条)
        Entry(id: "nav_projects", title: "项目", group: .drawerTop, status: .implemented, parentId: nil, todoMessage: nil, routeId: "projects"),
        Entry(id: "nav_running_tasks", title: "任务", group: .drawerWorkspace, status: .implemented, parentId: nil, todoMessage: nil, routeId: "tasks"),
        Entry(id: "nav_results", title: "成果", group: .drawerWorkspace, status: .implemented, parentId: nil, todoMessage: nil, routeId: "artifacts"),
        Entry(id: "feature_task_detail", title: "任务详情", group: .feature, status: .implemented, parentId: "nav_running_tasks", todoMessage: nil, routeId: "taskdetail"),
        Entry(id: "feature_project_files", title: "项目文件与代码", group: .feature, status: .implemented, parentId: "nav_projects", todoMessage: nil, routeId: "files"),
        Entry(id: "feature_project_overview", title: "项目概览", group: .feature, status: .implemented, parentId: "nav_projects", todoMessage: nil, routeId: "projectdetail"),
        Entry(id: "feature_change_diff", title: "变更对比", group: .feature, status: .implemented, parentId: "nav_projects", todoMessage: nil, routeId: "diff"),
        Entry(id: "feature_terminal_logs", title: "终端与日志", group: .feature, status: .todo, parentId: "nav_projects", todoMessage: "终端与日志待开发（iOS沙盒禁止本地任意Shell，须远端Hand执行）", routeId: "terminal"),
        Entry(id: "feature_git_worktree", title: "Git 与工作树", group: .feature, status: .todo, parentId: "nav_projects", todoMessage: "Git 与工作树待开发（iOS无本地CLI，远端Hand同步待接入）", routeId: "git"),
        Entry(id: "feature_run_preview", title: "运行预览", group: .feature, status: .todo, parentId: "nav_projects", todoMessage: "运行预览待开发（尚未开发）", routeId: "preview"),
        Entry(id: "feature_browser_workbench", title: "浏览器工作台", group: .feature, status: .todo, parentId: "nav_projects", todoMessage: "浏览器工作台待开发（服务端浏览器快照服务未接入）", routeId: "browser"),
        Entry(id: "feature_test_matrix", title: "测试矩阵", group: .feature, status: .todo, parentId: "nav_projects", todoMessage: "测试矩阵待开发（仅作为内置契约核对视图，机械检查未运行）", routeId: "testmatrix"),
        Entry(id: "feature_plan_checkpoints", title: "计划与检查点", group: .feature, status: .todo, parentId: "nav_running_tasks", todoMessage: "计划与检查点待开发（已在任务流水线内呈现，独立配置页尚未开发）", routeId: "plan"),
        Entry(id: "feature_queue_schedule", title: "队列与调度", group: .feature, status: .todo, parentId: "nav_running_tasks", todoMessage: "队列与调度待开发（尚未开发）", routeId: "queue"),
        Entry(id: "feature_code_review", title: "代码审查", group: .feature, status: .todo, parentId: "nav_running_tasks", todoMessage: "代码审查待开发（已在任务流水线内呈现，独立配置页尚未开发）", routeId: "review"),
        Entry(id: "feature_create_task", title: "新建任务", group: .feature, status: .implemented, parentId: "nav_running_tasks", todoMessage: nil, routeId: "compose"),
        Entry(id: "feature_auto_loop", title: "Auto-loop 自主循环", group: .feature, status: .todo, parentId: "nav_projects", todoMessage: "Auto-loop 自主循环待开发（尚未开发）", routeId: "autoloop"),
        Entry(id: "feature_loop_guardrails", title: "循环安全护栏", group: .feature, status: .todo, parentId: "nav_projects", todoMessage: "循环安全护栏待开发（尚未开发）", routeId: "loopguard"),
        Entry(id: "feature_workflow_templates", title: "工作流模板", group: .feature, status: .todo, parentId: "nav_projects", todoMessage: "工作流模板待开发（尚未开发）", routeId: "templates"),
        Entry(id: "feature_automation_center", title: "自动化中心", group: .feature, status: .todo, parentId: "nav_projects", todoMessage: "自动化中心待开发（尚未开发）", routeId: "automation"),
        Entry(id: "feature_create_automation", title: "创建自动化", group: .feature, status: .todo, parentId: "nav_projects", todoMessage: "创建自动化待开发（新建自动化规则 U66 尚未开发）", routeId: "automaker"),
        Entry(id: "feature_triggers_wakeup", title: "触发器与唤醒", group: .feature, status: .todo, parentId: "nav_projects", todoMessage: "触发器与唤醒待开发（含启停唤醒 U67，尚未开发）", routeId: "triggers"),
        Entry(id: "feature_code_editor", title: "代码编辑器", group: .feature, status: .implemented, parentId: "nav_projects", todoMessage: nil, routeId: "editor"),

        // MARK: - 三、频道分组 (7 条)
        Entry(id: "nav_channels", title: "频道", group: .drawerTop, status: .implemented, parentId: nil, todoMessage: nil, routeId: "channels"),
        Entry(id: "nav_team_channel", title: "团队频道", group: .drawerExtensions, status: .implemented, parentId: nil, todoMessage: nil, routeId: "channel"),
        Entry(id: "feature_channel_manage", title: "频道管理", group: .feature, status: .todo, parentId: "nav_channels", todoMessage: "频道管理待开发（创建/改名/删除/成员权限）", routeId: "channels"),
        Entry(id: "feature_channel_files", title: "频道文件", group: .feature, status: .todo, parentId: "nav_channels", todoMessage: "频道文件待开发（附件/网盘引用/预览下载）", routeId: "channels"),
        Entry(id: "feature_direct_message", title: "私聊服务", group: .feature, status: .todo, parentId: "nav_channels", todoMessage: "私聊服务待开发（与指定人员/Agent 独立私聊通道）", routeId: "channel"),
        Entry(id: "feature_team_space", title: "团队空间", group: .feature, status: .todo, parentId: "nav_channels", todoMessage: "团队空间待开发（成员与频道同页的统一空间 + 创建频道尚未开发）", routeId: "team"),
        Entry(id: "feature_message_center", title: "消息中心", group: .feature, status: .todo, parentId: "nav_channels", todoMessage: "消息中心待开发（尚未开发）", routeId: "notifications"),

        // MARK: - 四、控制分组 (35 条)
        Entry(id: "feature_brain", title: "大脑", group: .feature, status: .implemented, parentId: "nav_feature_center", todoMessage: nil, routeId: "models"),
        Entry(id: "nav_control", title: "控制", group: .drawerTop, status: .implemented, parentId: nil, todoMessage: nil, routeId: "control"),
        Entry(id: "nav_team", title: "Agent 与团队", group: .drawerWorkspace, status: .implemented, parentId: nil, todoMessage: nil, routeId: "agents"),
        Entry(id: "nav_devices", title: "设备", group: .drawerWorkspace, status: .implemented, parentId: nil, todoMessage: nil, routeId: "devices"),
        Entry(id: "nav_tools", title: "工具与连接", group: .drawerWorkspace, status: .implemented, parentId: nil, todoMessage: nil, routeId: "connections"),
        Entry(id: "nav_pending_approval", title: "待审批", group: .drawerWorkspace, status: .implemented, parentId: nil, todoMessage: nil, routeId: "approvals"),
        Entry(id: "nav_mcp_plugins", title: "MCP / 插件", group: .drawerExtensions, status: .implemented, parentId: "nav_tools", todoMessage: nil, routeId: "mcp"),
        Entry(id: "nav_models_skills", title: "模型与技能", group: .drawerExtensions, status: .todo, parentId: "nav_tools", todoMessage: "模型与技能待开发（工具中心后续批次）", routeId: "models"),
        Entry(id: "nav_custom_modules", title: "自定义模块", group: .drawerExtensions, status: .todo, parentId: nil, todoMessage: "自定义模块待开发（第二步自定义引擎）", routeId: "extensions"),
        Entry(id: "feature_topology", title: "团队拓扑", group: .feature, status: .todo, parentId: "nav_team", todoMessage: "团队拓扑待开发（Agent 与设备连线图 + 在线/离线 + 消息/任务通道）", routeId: "orchestrator"),
        Entry(id: "feature_usage_cost", title: "用量与成本", group: .feature, status: .todo, parentId: "nav_control", todoMessage: "用量与成本待开发（只是界面，不产生计费调用）", routeId: "usage"),
        Entry(id: "feature_run_logs", title: "运行日志", group: .feature, status: .implemented, parentId: "nav_control", todoMessage: nil, routeId: "activity"),
        Entry(id: "feature_unified_access", title: "统一接入与通信中心", group: .feature, status: .todo, parentId: "nav_tools", todoMessage: "统一接入待开发（MCP / HTTP API / WebSocket / CLI/Worker / Webhook 适配器层）", routeId: "connections"),
        Entry(id: "feature_agent_detail", title: "Agent 详情", group: .feature, status: .implemented, parentId: "nav_team", todoMessage: nil, routeId: "agentdetail"),
        Entry(id: "feature_orchestration", title: "协作编排", group: .feature, status: .todo, parentId: "nav_team", todoMessage: "协作编排待开发（Agent 拓扑与任务分配策略尚未开发）", routeId: "delegate"),
        Entry(id: "feature_import_agent", title: "导入现有 Agent", group: .feature, status: .todo, parentId: "nav_team", todoMessage: "导入现有 Agent 待开发（从外部目录导入配置与技能尚未开发）", routeId: "agentimport"),
        Entry(id: "feature_session_handoff", title: "Agent 会话续接", group: .feature, status: .todo, parentId: "nav_team", todoMessage: "Agent 会话续接待开发（从上一次任务中提取上下文尚未开发）", routeId: "agentresume"),
        Entry(id: "feature_agent_compare", title: "多 Agent 对比", group: .feature, status: .todo, parentId: "nav_team", todoMessage: "多 Agent 对比待开发（尚未开发）", routeId: "agentcompare"),
        Entry(id: "feature_visual_orchestration", title: "可视化编排", group: .feature, status: .todo, parentId: "nav_team", todoMessage: "可视化编排待开发（尚未开发）", routeId: "orchestrator"),
        Entry(id: "feature_agent_persona", title: "Agent 个性与配置", group: .feature, status: .todo, parentId: "nav_team", todoMessage: "Agent 个性与配置待开发（提示词、温度与系统人设配置尚未开发）", routeId: "personas"),
        Entry(id: "feature_device_detail", title: "设备详情", group: .feature, status: .implemented, parentId: "nav_devices", todoMessage: nil, routeId: "devicedetail"),
        Entry(id: "feature_cross_device_relay", title: "跨设备接力", group: .feature, status: .todo, parentId: "nav_devices", todoMessage: "跨设备接力待开发（跨端任务接力与状态同步尚未开发）", routeId: "handoff"),
        Entry(id: "feature_network", title: "通信网络", group: .feature, status: .todo, parentId: "nav_tools", todoMessage: "通信网络待开发（传输路径与离线队列尚未开发）", routeId: "network"),
        Entry(id: "feature_network_path", title: "网络路径详情", group: .feature, status: .todo, parentId: "nav_tools", todoMessage: "网络路径详情待开发（尚未开发）", routeId: "routes"),
        Entry(id: "feature_model_wizard", title: "连接模型向导", group: .feature, status: .todo, parentId: "nav_tools", todoMessage: "连接模型向导待开发（尚未开发）", routeId: "connectmodel"),
        Entry(id: "feature_model_routing", title: "智能模型路由", group: .feature, status: .implemented, parentId: "nav_tools", todoMessage: nil, routeId: "modelroute"),
        Entry(id: "feature_plugin_install", title: "插件权限与安装", group: .feature, status: .todo, parentId: "nav_mcp_plugins", todoMessage: "插件权限与安装待开发（插件启停已有，权限与安装页尚未开发）", routeId: "extensiondetail"),
        Entry(id: "feature_harness_runtime", title: "Harness 运行时", group: .feature, status: .todo, parentId: "nav_mcp_plugins", todoMessage: "Harness 运行时待开发（尚未开发）", routeId: "harness"),
        Entry(id: "feature_extension_manifest", title: "扩展 Manifest 导入", group: .feature, status: .todo, parentId: "nav_mcp_plugins", todoMessage: "扩展 Manifest 导入待开发（通过 Manifest 规范导入自定义能力）", routeId: "extensions"),
        Entry(id: "feature_skills_automation", title: "技能与自动化", group: .feature, status: .todo, parentId: "nav_models_skills", todoMessage: "技能与自动化待开发（技能记忆在任务流程内，独立页尚未开发）", routeId: "skills"),
        Entry(id: "feature_events_notifications", title: "事件与通知", group: .feature, status: .todo, parentId: "nav_control", todoMessage: "事件与通知待开发（事件在控制总览内，独立页尚未开发）", routeId: "activity"),
        Entry(id: "feature_cross_sync", title: "跨端同步", group: .feature, status: .todo, parentId: "nav_control", todoMessage: "跨端同步待开发（尚未开发）", routeId: "sync"),
        Entry(id: "feature_recovery_rollback", title: "恢复与回滚", group: .feature, status: .todo, parentId: "nav_control", todoMessage: "恢复与回滚待开发（尚未开发）", routeId: "recovery"),
        Entry(id: "feature_skill_market", title: "技能市场", group: .feature, status: .todo, parentId: "nav_models_skills", todoMessage: "技能市场待开发（尚未开发）", routeId: "skillsmarket"),
        Entry(id: "feature_skill_generate", title: "技能自生成", group: .feature, status: .todo, parentId: "nav_models_skills", todoMessage: "技能自生成待开发（技能捕获在任务流程内，自生成页尚未开发）", routeId: "skillbuilder"),

        // MARK: - 五、账号/设置（独立收纳，7 条）
        Entry(id: "nav_help", title: "帮助", group: .drawerWorkspace, status: .implemented, parentId: nil, todoMessage: nil, routeId: "onboarding"),
        Entry(id: "nav_workbench", title: "设置", group: .drawerWorkspace, status: .implemented, parentId: nil, todoMessage: nil, routeId: "settings"),
        Entry(id: "nav_theme", title: "界面与主题", group: .drawerWorkspace, status: .implemented, parentId: nil, todoMessage: nil, routeId: "settings"),
        Entry(id: "feature_permissions", title: "执行权限 L0–L4", group: .feature, status: .todo, parentId: "nav_workbench", todoMessage: "执行权限待开发（L0–L4 + 适用范围 + 授权范围 + 自动审批策略 + 安全边界；L4 须等 M1 P1-1~P1-4 与配对门禁完成）", routeId: "audit"),
        Entry(id: "feature_wakeup_policy", title: "唤醒与自治策略", group: .feature, status: .todo, parentId: "nav_workbench", todoMessage: "唤醒与自治策略待开发（尚未开发）", routeId: "wakepolicy"),
        Entry(id: "feature_autonomy", title: "自主度（监督 / 全自动）", group: .feature, status: .implemented, parentId: "nav_workbench", todoMessage: nil, routeId: "settings"),
        Entry(id: "feature_app_update", title: "应用更新", group: .feature, status: .implemented, parentId: "nav_workbench", todoMessage: nil, routeId: "settings"),

        // MARK: - 六、功能中心（跨导航总索引，2 条）
        Entry(id: "nav_feature_center", title: "功能中心", group: .drawerExtensions, status: .implemented, parentId: nil, todoMessage: nil, routeId: "feature_center"),
        Entry(id: "feature_nesting", title: "收纳系统与自定义引擎", group: .feature, status: .todo, parentId: "nav_feature_center", todoMessage: "收纳系统与自定义引擎待开发（极简/标准/专家模式、自定义首页尚未开发）", routeId: "settings"),
    ]

    /// 查找条目（含旧别名兼容：nav_console -> nav_control; feature_team_chat -> nav_team_channel）
    public static func find(_ id: String) -> Entry? {
        if let direct = ENTRIES.first(where: { $0.id == id }) {
            return direct
        }
        // 兼容别名映射
        switch id {
        case "home_brain":
            return ENTRIES.first { $0.id == "feature_brain" }
        case "nav_console":
            return ENTRIES.first { $0.id == "nav_control" }
        case "feature_team_chat":
            return ENTRIES.first { $0.id == "nav_team_channel" }
        default:
            return nil
        }
    }

    public static func byGroup(_ group: Group) -> [Entry] {
        ENTRIES.filter { $0.group == group }
    }

    public static func validate() -> [String] {
        var errors: [String] = []
        let ids = ENTRIES.map(\.id)
        if Set(ids).count != ENTRIES.count {
            errors.append("存在重复入口 id: 总数 \(ENTRIES.count), 唯一数 \(Set(ids).count)")
        }
        for e in ENTRIES {
            if e.status == .todo {
                guard let msg = e.todoMessage, msg.contains("待开发") else {
                    errors.append("待开发入口缺提示：\(e.id)")
                    continue
                }
            }
            if e.group == .feature {
                guard let pid = e.parentId, find(pid) != nil else {
                    errors.append("功能入口父入口不存在：\(e.id), parentId: \(e.parentId ?? "nil")")
                    continue
                }
            }
        }
        return errors
    }
}
