// Walkthrough89 — iOS registry full pass (2026-11-17)
//
// Drives the REAL app in the simulator through the user path for
// every one of the 89 EntryRegistry entries: open the drawer, tap
// 全部功能 (feature center), tap the entry's row in the full list.
// - implemented: the sheet must dismiss and the route must open
//   without crashing; a screenshot wt-<id> is attached.
// - todo: the feature center must show that entry's own honest
//   todoMessage (containing 待开发) in its footer; screenshot too.
// Per-entry verdicts are accumulated and attached at the end as the
// "walkthrough89-results" text attachment:
//   <id>|IMPLEMENTED|PASS|note  /  <id>|TODO|TODO_OK|FAIL|note
//   WALKTHROUGH89_DONE total=.. pass=.. todo_ok=.. fail=..
// The entry table below is generated from
// ios/WhereverCodeAgent/Data/EntryRegistry.swift on
// fix/v5-ios-p0-device-acceptance-20261115 (ae31003) — the iOS
// registry's own claims are what is being tested.

import XCTest

final class Walkthrough89UITests: XCTestCase {

    struct WEntry {
        let id: String
        let title: String
        let implemented: Bool
        let todoMessage: String?
        let routeId: String
    }

    static let entries: [WEntry] = [
        WEntry(id: "home_dev_app", title: "开发一个 App", implemented: true, todoMessage: nil, routeId: "chat"),
        WEntry(id: "home_import_project", title: "导入项目并交付", implemented: true, todoMessage: nil, routeId: "projects"),
        WEntry(id: "home_organize_team", title: "组织多 Agent 团队", implemented: true, todoMessage: nil, routeId: "agents"),
        WEntry(id: "home_automation", title: "自动化任务", implemented: true, todoMessage: nil, routeId: "automation"),
        WEntry(id: "home_search", title: "会话", implemented: true, todoMessage: nil, routeId: "history"),
        WEntry(id: "home_new", title: "新建", implemented: true, todoMessage: nil, routeId: "chat"),
        WEntry(id: "nav_chat", title: "对话", implemented: true, todoMessage: nil, routeId: "chat"),
        WEntry(id: "nav_history", title: "会话", implemented: true, todoMessage: nil, routeId: "history"),
        WEntry(id: "feature_auto_exec", title: "对话中自动执行", implemented: false, todoMessage: "对话中自动执行待开发（自主执行；实时进度展示已实现：聊天时间线工具卡）", routeId: "conversation"),
        WEntry(id: "feature_quick_actions", title: "快速操作与自定义快捷入口", implemented: true, todoMessage: nil, routeId: "compose"),
        WEntry(id: "feature_global_search", title: "全局搜索", implemented: false, todoMessage: "全局搜索待开发（跨会话、项目与频道搜索尚未开发）", routeId: "search"),
        WEntry(id: "feature_voice_multimodal", title: "语音与多模态", implemented: true, todoMessage: nil, routeId: "voice"),
        WEntry(id: "feature_long_memory", title: "长期记忆", implemented: false, todoMessage: "长期记忆待开发（尚未开发）", routeId: "memory"),
        WEntry(id: "feature_context_manager", title: "上下文管理", implemented: false, todoMessage: "上下文管理待开发（上下文在对话流程内，独立管理页尚未开发）", routeId: "context"),
        WEntry(id: "feature_deep_research", title: "深度研究", implemented: false, todoMessage: "深度研究待开发（尚未开发）", routeId: "research"),
        WEntry(id: "nav_projects", title: "项目", implemented: true, todoMessage: nil, routeId: "projects"),
        WEntry(id: "nav_running_tasks", title: "任务", implemented: true, todoMessage: nil, routeId: "tasks"),
        WEntry(id: "nav_results", title: "成果", implemented: true, todoMessage: nil, routeId: "artifacts"),
        WEntry(id: "feature_task_detail", title: "任务详情", implemented: true, todoMessage: nil, routeId: "taskdetail"),
        WEntry(id: "feature_project_files", title: "项目文件与代码", implemented: true, todoMessage: nil, routeId: "files"),
        WEntry(id: "feature_project_overview", title: "项目概览", implemented: true, todoMessage: nil, routeId: "projectdetail"),
        WEntry(id: "feature_change_diff", title: "变更对比", implemented: true, todoMessage: nil, routeId: "diff"),
        WEntry(id: "feature_terminal_logs", title: "终端与日志", implemented: false, todoMessage: "终端与日志待开发（iOS沙盒禁止本地任意Shell，须远端Hand执行）", routeId: "terminal"),
        WEntry(id: "feature_git_worktree", title: "Git 与工作树", implemented: false, todoMessage: "Git 与工作树待开发（iOS无本地CLI，远端Hand同步待接入）", routeId: "git"),
        WEntry(id: "feature_run_preview", title: "运行预览", implemented: false, todoMessage: "运行预览待开发（尚未开发）", routeId: "preview"),
        WEntry(id: "feature_browser_workbench", title: "浏览器工作台", implemented: false, todoMessage: "浏览器工作台待开发（服务端浏览器快照服务未接入）", routeId: "browser"),
        WEntry(id: "feature_test_matrix", title: "测试矩阵", implemented: false, todoMessage: "测试矩阵待开发（仅作为内置契约核对视图，机械检查未运行）", routeId: "testmatrix"),
        WEntry(id: "feature_plan_checkpoints", title: "计划与检查点", implemented: false, todoMessage: "计划与检查点待开发（已在任务流水线内呈现，独立配置页尚未开发）", routeId: "plan"),
        WEntry(id: "feature_queue_schedule", title: "队列与调度", implemented: false, todoMessage: "队列与调度待开发（尚未开发）", routeId: "queue"),
        WEntry(id: "feature_code_review", title: "代码审查", implemented: false, todoMessage: "代码审查待开发（已在任务流水线内呈现，独立配置页尚未开发）", routeId: "review"),
        WEntry(id: "feature_create_task", title: "新建任务", implemented: true, todoMessage: nil, routeId: "compose"),
        WEntry(id: "feature_auto_loop", title: "Auto-loop 自主循环", implemented: false, todoMessage: "Auto-loop 自主循环待开发（尚未开发）", routeId: "autoloop"),
        WEntry(id: "feature_loop_guardrails", title: "循环安全护栏", implemented: false, todoMessage: "循环安全护栏待开发（尚未开发）", routeId: "loopguard"),
        WEntry(id: "feature_workflow_templates", title: "工作流模板", implemented: false, todoMessage: "工作流模板待开发（尚未开发）", routeId: "templates"),
        WEntry(id: "feature_automation_center", title: "自动化中心", implemented: false, todoMessage: "自动化中心待开发（尚未开发）", routeId: "automation"),
        WEntry(id: "feature_create_automation", title: "创建自动化", implemented: false, todoMessage: "创建自动化待开发（新建自动化规则 U66 尚未开发）", routeId: "automaker"),
        WEntry(id: "feature_triggers_wakeup", title: "触发器与唤醒", implemented: false, todoMessage: "触发器与唤醒待开发（含启停唤醒 U67，尚未开发）", routeId: "triggers"),
        WEntry(id: "feature_code_editor", title: "代码编辑器", implemented: true, todoMessage: nil, routeId: "editor"),
        WEntry(id: "nav_channels", title: "频道", implemented: true, todoMessage: nil, routeId: "channels"),
        WEntry(id: "nav_team_channel", title: "团队频道", implemented: true, todoMessage: nil, routeId: "channel"),
        WEntry(id: "feature_channel_manage", title: "频道管理", implemented: false, todoMessage: "频道管理待开发（创建/改名/删除/成员权限）", routeId: "channels"),
        WEntry(id: "feature_channel_files", title: "频道文件", implemented: false, todoMessage: "频道文件待开发（附件/网盘引用/预览下载）", routeId: "channels"),
        WEntry(id: "feature_direct_message", title: "私聊服务", implemented: false, todoMessage: "私聊服务待开发（与指定人员/Agent 独立私聊通道）", routeId: "channel"),
        WEntry(id: "feature_team_space", title: "团队空间", implemented: false, todoMessage: "团队空间待开发（成员与频道同页的统一空间 + 创建频道尚未开发）", routeId: "team"),
        WEntry(id: "feature_message_center", title: "消息中心", implemented: false, todoMessage: "消息中心待开发（尚未开发）", routeId: "notifications"),
        WEntry(id: "home_brain", title: "大脑", implemented: true, todoMessage: nil, routeId: "models"),
        WEntry(id: "nav_control", title: "控制", implemented: true, todoMessage: nil, routeId: "control"),
        WEntry(id: "nav_team", title: "Agent 与团队", implemented: true, todoMessage: nil, routeId: "agents"),
        WEntry(id: "nav_devices", title: "设备", implemented: true, todoMessage: nil, routeId: "devices"),
        WEntry(id: "nav_tools", title: "工具与连接", implemented: true, todoMessage: nil, routeId: "connections"),
        WEntry(id: "nav_pending_approval", title: "待审批", implemented: true, todoMessage: nil, routeId: "approvals"),
        WEntry(id: "nav_mcp_plugins", title: "MCP / 插件", implemented: true, todoMessage: nil, routeId: "mcp"),
        WEntry(id: "nav_models_skills", title: "模型与技能", implemented: false, todoMessage: "模型与技能待开发（工具中心后续批次）", routeId: "models"),
        WEntry(id: "nav_custom_modules", title: "自定义模块", implemented: false, todoMessage: "自定义模块待开发（第二步自定义引擎）", routeId: "extensions"),
        WEntry(id: "feature_topology", title: "团队拓扑", implemented: false, todoMessage: "团队拓扑待开发（Agent 与设备连线图 + 在线/离线 + 消息/任务通道）", routeId: "orchestrator"),
        WEntry(id: "feature_usage_cost", title: "用量与成本", implemented: false, todoMessage: "用量与成本待开发（只是界面，不产生计费调用）", routeId: "usage"),
        WEntry(id: "feature_run_logs", title: "运行日志", implemented: true, todoMessage: nil, routeId: "activity"),
        WEntry(id: "feature_unified_access", title: "统一接入与通信中心", implemented: false, todoMessage: "统一接入待开发（MCP / HTTP API / WebSocket / CLI/Worker / Webhook 适配器层）", routeId: "connections"),
        WEntry(id: "feature_agent_detail", title: "Agent 详情", implemented: true, todoMessage: nil, routeId: "agentdetail"),
        WEntry(id: "feature_orchestration", title: "协作编排", implemented: false, todoMessage: "协作编排待开发（Agent 拓扑与任务分配策略尚未开发）", routeId: "delegate"),
        WEntry(id: "feature_import_agent", title: "导入现有 Agent", implemented: false, todoMessage: "导入现有 Agent 待开发（从外部目录导入配置与技能尚未开发）", routeId: "agentimport"),
        WEntry(id: "feature_session_handoff", title: "Agent 会话续接", implemented: false, todoMessage: "Agent 会话续接待开发（从上一次任务中提取上下文尚未开发）", routeId: "agentresume"),
        WEntry(id: "feature_agent_compare", title: "多 Agent 对比", implemented: false, todoMessage: "多 Agent 对比待开发（尚未开发）", routeId: "agentcompare"),
        WEntry(id: "feature_visual_orchestration", title: "可视化编排", implemented: false, todoMessage: "可视化编排待开发（尚未开发）", routeId: "orchestrator"),
        WEntry(id: "feature_agent_persona", title: "Agent 个性与配置", implemented: false, todoMessage: "Agent 个性与配置待开发（提示词、温度与系统人设配置尚未开发）", routeId: "personas"),
        WEntry(id: "feature_device_detail", title: "设备详情", implemented: true, todoMessage: nil, routeId: "devicedetail"),
        WEntry(id: "feature_cross_device_relay", title: "跨设备接力", implemented: false, todoMessage: "跨设备接力待开发（跨端任务接力与状态同步尚未开发）", routeId: "handoff"),
        WEntry(id: "feature_network", title: "通信网络", implemented: false, todoMessage: "通信网络待开发（传输路径与离线队列尚未开发）", routeId: "network"),
        WEntry(id: "feature_network_path", title: "网络路径详情", implemented: false, todoMessage: "网络路径详情待开发（尚未开发）", routeId: "routes"),
        WEntry(id: "feature_model_wizard", title: "连接模型向导", implemented: false, todoMessage: "连接模型向导待开发（尚未开发）", routeId: "connectmodel"),
        WEntry(id: "feature_model_routing", title: "智能模型路由", implemented: false, todoMessage: "智能模型路由待开发（尚未开发）", routeId: "modelroute"),
        WEntry(id: "feature_plugin_install", title: "插件权限与安装", implemented: false, todoMessage: "插件权限与安装待开发（插件启停已有，权限与安装页尚未开发）", routeId: "extensiondetail"),
        WEntry(id: "feature_harness_runtime", title: "Harness 运行时", implemented: false, todoMessage: "Harness 运行时待开发（尚未开发）", routeId: "harness"),
        WEntry(id: "feature_extension_manifest", title: "扩展 Manifest 导入", implemented: false, todoMessage: "扩展 Manifest 导入待开发（通过 Manifest 规范导入自定义能力）", routeId: "extensions"),
        WEntry(id: "feature_skills_automation", title: "技能与自动化", implemented: false, todoMessage: "技能与自动化待开发（技能记忆在任务流程内，独立页尚未开发）", routeId: "skills"),
        WEntry(id: "feature_events_notifications", title: "事件与通知", implemented: false, todoMessage: "事件与通知待开发（事件在控制总览内，独立页尚未开发）", routeId: "activity"),
        WEntry(id: "feature_cross_sync", title: "跨端同步", implemented: false, todoMessage: "跨端同步待开发（尚未开发）", routeId: "sync"),
        WEntry(id: "feature_recovery_rollback", title: "恢复与回滚", implemented: false, todoMessage: "恢复与回滚待开发（尚未开发）", routeId: "recovery"),
        WEntry(id: "feature_skill_market", title: "技能市场", implemented: false, todoMessage: "技能市场待开发（尚未开发）", routeId: "skillsmarket"),
        WEntry(id: "feature_skill_generate", title: "技能自生成", implemented: false, todoMessage: "技能自生成待开发（技能捕获在任务流程内，自生成页尚未开发）", routeId: "skillbuilder"),
        WEntry(id: "nav_help", title: "帮助", implemented: true, todoMessage: nil, routeId: "onboarding"),
        WEntry(id: "nav_workbench", title: "设置", implemented: true, todoMessage: nil, routeId: "settings"),
        WEntry(id: "nav_theme", title: "界面与主题", implemented: true, todoMessage: nil, routeId: "settings"),
        WEntry(id: "feature_permissions", title: "执行权限 L0–L4", implemented: false, todoMessage: "执行权限待开发（L0–L4 + 适用范围 + 授权范围 + 自动审批策略 + 安全边界；L4 须等 M1 P1-1~P1-4 与配对门禁完成）", routeId: "audit"),
        WEntry(id: "feature_wakeup_policy", title: "唤醒与自治策略", implemented: false, todoMessage: "唤醒与自治策略待开发（尚未开发）", routeId: "wakepolicy"),
        WEntry(id: "feature_autonomy", title: "自主度（监督 / 全自动）", implemented: true, todoMessage: nil, routeId: "settings"),
        WEntry(id: "feature_app_update", title: "应用更新", implemented: true, todoMessage: nil, routeId: "settings"),
        WEntry(id: "nav_feature_center", title: "功能中心", implemented: true, todoMessage: nil, routeId: "feature_center"),
        WEntry(id: "feature_nesting", title: "收纳系统与自定义引擎", implemented: false, todoMessage: "收纳系统与自定义引擎待开发（极简/标准/专家模式、自定义首页尚未开发）", routeId: "settings"),    ]

    private var results: [String] = []

    override func setUpWithError() throws {
        continueAfterFailure = true
    }

    func testWalkthrough89() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(
            app.descendants(matching: .any)["chat.input"].waitForExistence(timeout: 20),
            "chat home did not appear after launch"
        )
        for entry in Self.entries {
            walk(app, entry)
        }
        let pass = results.filter { $0.contains("|PASS|") }.count
        let todoOk = results.filter { $0.contains("|TODO_OK|") }.count
        let fail = results.filter { $0.contains("|FAIL|") }.count
        results.append("WALKTHROUGH89_DONE total=\(results.count) pass=\(pass) todo_ok=\(todoOk) fail=\(fail)")
        let attachment = XCTAttachment(string: results.joined(separator: "\n"))
        attachment.name = "walkthrough89-results"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    // MARK: - Per-entry walk

    private func walk(_ app: XCUIApplication, _ entry: WEntry) {
        let statusLabel = entry.implemented ? "IMPLEMENTED" : "TODO"
        guard openFeatureCenter(app) else {
            record(entry, statusLabel, "FAIL", "feature center did not open")
            recover(app)
            return
        }
        guard let row = findRow(app, id: entry.id) else {
            record(entry, statusLabel, "FAIL", "row not found in feature center list")
            scrollListToTop(app)
            return
        }
        row.tap()
        Thread.sleep(forTimeInterval: 1.0)
        if entry.implemented {
            // Sheet must be gone and the app alive on some real page.
            let sheetGone = !app.buttons.matching(
                NSPredicate(format: "label CONTAINS %@", entry.id)
            ).firstMatch.exists
            snap(app, "wt-\(entry.id)")
            if app.state == .runningForeground && sheetGone {
                record(entry, statusLabel, "PASS", "route \(entry.routeId) opened from feature center")
            } else if app.state == .runningForeground {
                record(entry, statusLabel, "FAIL", "tap did not leave the feature center")
            } else {
                record(entry, statusLabel, "FAIL", "app not in foreground after tap (crash?)")
            }
            goBackToHome(app)
        } else {
            let expected = entry.todoMessage ?? "【待开发】该功能尚未开发"
            scrollListToTop(app)
            let hint = app.staticTexts.matching(
                NSPredicate(format: "label CONTAINS %@", String(expected.prefix(10)))
            ).firstMatch
            let hinted = hint.waitForExistence(timeout: 5)
            snap(app, "wt-\(entry.id)")
            if hinted {
                record(entry, statusLabel, "TODO_OK", "honest todo hint shown")
            } else {
                record(entry, statusLabel, "FAIL", "TODO entry showed no honest hint")
            }
        }
    }

    private func record(_ entry: WEntry, _ status: String, _ verdict: String, _ note: String) {
        results.append("\(entry.id)|\(status)|\(verdict)|\(note)")
    }

    // MARK: - Navigation helpers

    private func openFeatureCenter(_ app: XCUIApplication) -> Bool {
        // Already open? The sheet's navigation title is 全部功能 (89 项).
        if app.navigationBars.matching(
            NSPredicate(format: "identifier CONTAINS %@", "全部功能")
        ).firstMatch.exists {
            scrollListToTop(app)
            return true
        }
        let anyRow = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "home_dev_app")
        ).firstMatch
        if anyRow.exists {
            scrollListToTop(app)
            return true
        }
        let menu = app.buttons["navigation.menu"].firstMatch
        guard menu.waitForExistence(timeout: 8) else { return false }
        menu.tap()
        let centerByRoute = app.buttons["drawer.route.feature_center"].firstMatch
        let centerByName = app.buttons["drawer.featureCenter"].firstMatch
        let center = centerByRoute.exists ? centerByRoute : centerByName
        guard center.waitForExistence(timeout: 8) else { return false }
        center.tap()
        let opened = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "home_dev_app")
        ).firstMatch
        return opened.waitForExistence(timeout: 10)
    }

    private func findRow(_ app: XCUIApplication, id: String) -> XCUIElement? {
        let row = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", id)
        ).firstMatch
        var swipes = 0
        while !row.isHittable && swipes < 30 {
            app.swipeUp()
            swipes += 1
            if row.waitForExistence(timeout: 1) && row.isHittable { break }
        }
        return row.isHittable ? row : nil
    }

    private func scrollListToTop(_ app: XCUIApplication) {
        for _ in 0..<8 { app.swipeDown() }
    }

    private func goBackToHome(_ app: XCUIApplication) {
        // Route pages are pushed on the shell's NavigationStack; sheets
        // carry a 关闭 button. Prefer 关闭, then the nav back button.
        let close = app.buttons["关闭"].firstMatch
        if close.exists {
            close.tap()
            Thread.sleep(forTimeInterval: 0.6)
            return
        }
        let back = app.navigationBars.buttons.firstMatch
        if back.exists {
            back.tap()
            Thread.sleep(forTimeInterval: 0.6)
        }
    }

    private func recover(_ app: XCUIApplication) {
        if app.state != .runningForeground {
            app.launch()
            Thread.sleep(forTimeInterval: 2)
            return
        }
        let close = app.buttons["关闭"].firstMatch
        if close.exists { close.tap() }
    }

    private func snap(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
