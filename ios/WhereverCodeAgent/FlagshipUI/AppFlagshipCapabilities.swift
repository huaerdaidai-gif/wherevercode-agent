import Foundation

/// iOS 版 70 屏能力适配器（双端组装第一轮）。
///
/// 状态只由现有 iOS 实现驱动：已有真实页面、但未经真机验收的标
/// implementedUnverified；没有对应 iOS 页面的统一 planned（待开发），
/// 不把数据层存在误报成页面已实现（项目/文件/编辑器在 iOS 只有
/// ProjectStore 数据层，没有视图）。openExistingFeature 把 route
/// 映射到现有页签，经回调切页签，不复制业务实现。
struct AppFlagshipCapabilities: FlagshipCapabilityProvider {
    var onOpenTab: (AppTab) -> Void

    func status(for route: String) -> CapabilityStatus {
        if Self.unverified.contains(route) {
            return CapabilityStatus(state: .implementedUnverified)
        }
        return CapabilityStatus(state: .planned)
    }

    func openExistingFeature(_ route: String) -> Bool {
        guard let tab = Self.routeToTab[route] else { return false }
        onOpenTab(tab)
        return true
    }

    /// 已有真实 iOS 页面、未经真机验收的 route。
    /// taskdetail 的真实页面是 TasksView.swift 里的 TaskDetailView
    /// （任务列表 NavigationLink 的目的地，含领取 / 汇报 / 心跳 /
    /// 放手 / 交活），入口在 tasks 页签内，故与 tasks 同列；旗舰层
    /// 本身未经真机验收，只标 implementedUnverified，不标已验证。
    static let unverified: Set<String> = [
        "chat", "conversation", "history", "channels", "channel",
        "tasks", "taskdetail", "devices", "settings", "models", "connectmodel",
    ]

    /// route → 现有页签（仅限已有真实页面的 route）。
    static let routeToTab: [String: AppTab] = [
        "chat": .chat, "conversation": .chat, "history": .chat,
        "channels": .chat, "channel": .chat,
        "tasks": .tasks, "taskdetail": .tasks,
        "devices": .devices,
        "settings": .settings, "models": .settings, "connectmodel": .settings,
    ]
}
