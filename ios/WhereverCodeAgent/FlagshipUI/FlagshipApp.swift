import SwiftUI

struct FlagshipRoute: Identifiable, Hashable {
    let id: String
    let title: String
    let group: String
    let p0: Bool
}
enum FlagshipRoutes {
    static let all: [FlagshipRoute] = [
        .init(id: "chat", title: "对话首页", group: "对话", p0: true),
        .init(id: "conversation", title: "执行中的对话", group: "对话", p0: true),
        .init(id: "history", title: "历史会话", group: "对话", p0: true),
        .init(id: "search", title: "全局搜索", group: "对话", p0: false),
        .init(id: "compose", title: "新建任务", group: "任务", p0: true),
        .init(id: "tasks", title: "任务中心", group: "任务", p0: true),
        .init(id: "taskdetail", title: "任务详情", group: "任务", p0: true),
        .init(id: "plan", title: "计划与检查点", group: "任务", p0: false),
        .init(id: "queue", title: "队列与调度", group: "任务", p0: false),
        .init(id: "approvals", title: "审批中心", group: "任务", p0: true),
        .init(id: "review", title: "代码审查", group: "任务", p0: false),
        .init(id: "artifacts", title: "交付成果", group: "任务", p0: true),
        .init(id: "projects", title: "项目列表", group: "开发", p0: true),
        .init(id: "projectdetail", title: "项目概览", group: "开发", p0: true),
        .init(id: "files", title: "文件浏览器", group: "开发", p0: true),
        .init(id: "editor", title: "代码编辑器", group: "开发", p0: true),
        .init(id: "diff", title: "变更对比", group: "开发", p0: true),
        .init(id: "terminal", title: "终端与日志", group: "开发", p0: true),
        .init(id: "git", title: "Git 与工作树", group: "开发", p0: false),
        .init(id: "preview", title: "运行预览", group: "开发", p0: false),
        .init(id: "team", title: "团队空间", group: "协作", p0: false),
        .init(id: "channels", title: "团队频道", group: "协作", p0: false),
        .init(id: "channel", title: "频道会话", group: "协作", p0: false),
        .init(id: "agents", title: "Agent 列表", group: "协作", p0: true),
        .init(id: "agentdetail", title: "Agent 详情", group: "协作", p0: true),
        .init(id: "delegate", title: "协作编排", group: "协作", p0: false),
        .init(id: "devices", title: "设备列表", group: "连接", p0: true),
        .init(id: "devicedetail", title: "设备详情", group: "连接", p0: false),
        .init(id: "models", title: "模型与提供方", group: "连接", p0: true),
        .init(id: "mcp", title: "MCP 与插件", group: "连接", p0: false),
        .init(id: "skills", title: "技能与自动化", group: "连接", p0: false),
        .init(id: "connections", title: "账号与连接", group: "连接", p0: false),
        .init(id: "dashboard", title: "全局工作台", group: "系统", p0: true),
        .init(id: "activity", title: "事件与通知", group: "系统", p0: false),
        .init(id: "settings", title: "设置", group: "系统", p0: true),
        .init(id: "onboarding", title: "首次使用", group: "系统", p0: true),
        .init(id: "connectmodel", title: "连接模型向导", group: "连接", p0: true),
        .init(id: "modelroute", title: "智能模型路由", group: "连接", p0: false),
        .init(id: "agentimport", title: "导入现有 Agent", group: "连接", p0: false),
        .init(id: "agentresume", title: "Agent 会话续接", group: "协作", p0: false),
        .init(id: "autoloop", title: "Auto-loop 自主循环", group: "自动化", p0: false),
        .init(id: "loopguard", title: "循环安全护栏", group: "自动化", p0: false),
        .init(id: "memory", title: "长期记忆", group: "智能", p0: false),
        .init(id: "skillsmarket", title: "技能市场", group: "智能", p0: false),
        .init(id: "skillbuilder", title: "技能自生成", group: "智能", p0: false),
        .init(id: "context", title: "上下文管理", group: "智能", p0: false),
        .init(id: "research", title: "深度研究", group: "智能", p0: false),
        .init(id: "browser", title: "浏览器工作台", group: "开发", p0: false),
        .init(id: "testmatrix", title: "测试矩阵", group: "开发", p0: false),
        .init(id: "agentcompare", title: "多 Agent 对比", group: "协作", p0: false),
        .init(id: "orchestrator", title: "可视化编排", group: "协作", p0: false),
        .init(id: "handoff", title: "跨设备接力", group: "协作", p0: false),
        .init(id: "audit", title: "权限与审计", group: "系统", p0: false),
        .init(id: "notifications", title: "消息中心", group: "系统", p0: false),
        .init(id: "voice", title: "语音与多模态", group: "对话", p0: false),
        .init(id: "personas", title: "Agent 个性与配置", group: "智能", p0: false),
        .init(id: "usage", title: "成本与额度", group: "系统", p0: false),
        .init(id: "templates", title: "工作流模板", group: "自动化", p0: false),
        .init(id: "sync", title: "跨端同步", group: "系统", p0: false),
        .init(id: "recovery", title: "恢复与回滚", group: "系统", p0: false),
        .init(id: "automation", title: "自动化中心", group: "自动化", p0: false),
        .init(id: "automaker", title: "创建自动化", group: "自动化", p0: false),
        .init(id: "triggers", title: "触发器与唤醒", group: "自动化", p0: false),
        .init(id: "wakepolicy", title: "唤醒与自治策略", group: "自动化", p0: false),
        .init(id: "network", title: "通信网络", group: "连接", p0: false),
        .init(id: "routes", title: "网络路径详情", group: "连接", p0: false),
        .init(id: "extensions", title: "扩展中心", group: "扩展", p0: false),
        .init(id: "extensiondetail", title: "插件权限与安装", group: "扩展", p0: false),
        .init(id: "harness", title: "Harness 运行时", group: "扩展", p0: false),
        .init(id: "baseline", title: "Claude 18 屏验收", group: "系统", p0: false),
    ]
    static func find(_ id: String) -> FlagshipRoute { all.first(where: {$0.id == id}) ?? all[0] }
}
enum CapabilityState { case planned, implementedUnverified, readyVerified, disconnected, failed, unsupported }
struct CapabilityStatus { var state: CapabilityState = .planned; var evidence: String? = nil }
protocol FlagshipCapabilityProvider {
    func status(for route: String) -> CapabilityStatus
    func openExistingFeature(_ route: String) -> Bool
}
struct PlannedCapabilities: FlagshipCapabilityProvider {
    func status(for route: String) -> CapabilityStatus { CapabilityStatus() }
    func openExistingFeature(_ route: String) -> Bool { false }
}
struct FlagshipApp: View {
    var capabilities: any FlagshipCapabilityProvider = PlannedCapabilities()
    @State private var current = "chat"
    @State private var showDrawer = false
    @State private var query = ""
    private let ink = Color(red: 0.16, green: 0.16, blue: 0.15)
    private let accent = Color(red: 0.78, green: 0.47, blue: 0.34)
    private let warm = Color(red: 0.98, green: 0.976, blue: 0.965)
    private var page: FlagshipRoute { FlagshipRoutes.find(current) }
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("☰") { showDrawer.toggle() }
                Text(page.title).fontWeight(.semibold).frame(maxWidth: .infinity, alignment: .leading)
                Button("控制台") { current = "dashboard"; showDrawer = false }
            }.padding()
            if showDrawer {
                TextField("查找 70 个页面", text: $query).textFieldStyle(.roundedBorder).padding(.horizontal)
                List {
                    ForEach(Array(Set(FlagshipRoutes.all.map(\.group))).sorted(), id: \.self) { group in
                        Section(group) {
                            ForEach(FlagshipRoutes.all.filter { $0.group == group && (query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) || $0.id.localizedCaseInsensitiveContains(query)) }) { route in
                                Button { current = route.id; showDrawer = false } label: {
                                    HStack { Text(route.title); Spacer(); Text(isVerified(route.id) ? "已验证" : "待开发").foregroundStyle(.secondary).font(.caption) }
                                }
                            }
                        }
                    }
                }.listStyle(.plain)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        Text(current == "chat" ? "今天想做些什么？" : page.title).font(.largeTitle)
                        Text(statusText).foregroundStyle(accent)
                        VStack(alignment: .leading, spacing: 14) {
                            Text("\(page.group) · \(page.id)").font(.caption).foregroundStyle(.secondary)
                            Text(isVerified(current) ? "此功能已通过真机验证，可进入现有实现。" : "此页面已预留。接入真实能力并完成真机验证后才能标记可用。")
                            Button("打开功能") { _ = capabilities.openExistingFeature(current) }.disabled(!isVerified(current))
                        }.padding().frame(maxWidth: .infinity, alignment: .leading).background(.white, in: RoundedRectangle(cornerRadius: 18))
                        if current == "dashboard" {
                            Text("运行总览").font(.headline)
                            ForEach(["项目", "任务队列", "设备", "模型与 Agent", "审批与审计"], id: \.self) { item in
                                HStack { Text(item); Spacer(); Text("未接入").foregroundStyle(.secondary) }; Divider()
                            }
                        }
                        if current == "chat" { Text("聊天发送必须接入现有模型会话服务；当前脚手架不伪造回复。") .foregroundStyle(.secondary) }
                    }.padding(20).frame(maxWidth: .infinity, alignment: .leading)
                }.frame(maxHeight: .infinity)
                HStack {
                    nav("对话", "chat"); nav("项目", "projects"); nav("任务", "tasks"); nav("控制台", "dashboard"); nav("更多", "settings")
                }.padding(.vertical, 12).background(.white)
            }
        }.foregroundStyle(ink).background(warm).tint(accent)
    }
    private func isVerified(_ route: String) -> Bool {
        let status = capabilities.status(for: route)
        return status.state == .readyVerified && !(status.evidence ?? "").isEmpty
    }
    private var statusText: String {
        let status = capabilities.status(for: current)
        if isVerified(current) { return "已验证" }
        switch status.state {
        case .implementedUnverified: return "待验收"
        case .disconnected: return "未连接"
        case .failed: return "运行失败"
        case .unsupported: return "此平台暂不支持"
        default: return "待开发"
        }
    }
    private func nav(_ title: String, _ id: String) -> some View {
        Button(title) { current = id }.font(.caption).frame(maxWidth: .infinity)
    }
}
