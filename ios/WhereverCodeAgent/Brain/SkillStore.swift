import Foundation

public struct SkillDescriptor: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public var name: String
    public var summary: String
    public var triggerHint: String
    public var version: String
    public var enabled: Bool
    public var source: String

    public init(
        id: String,
        name: String,
        summary: String,
        triggerHint: String,
        version: String = "1.0.0",
        enabled: Bool = true,
        source: String = "builtin"
    ) {
        self.id = id
        self.name = name
        self.summary = summary
        self.triggerHint = triggerHint
        self.version = version
        self.enabled = enabled
        self.source = source
    }
}

public final class SkillStore: ObservableObject {
    @Published public private(set) var skills: [SkillDescriptor] = [
        SkillDescriptor(id: "ios-swiftui-audit", name: "SwiftUI 原位架构审计", summary: "扫描 iOS 视图树、路由覆盖率与状态诚实性门禁", triggerHint: "审计 / UI 验收"),
        SkillDescriptor(id: "mcp-task-claim", name: "V5 平台任务认领与续租", summary: "通过 MCP 领取任务、发送 2 小时续租心跳并提交 Git 分支/PR 结果", triggerHint: "任务认领 / 交活"),
        SkillDescriptor(id: "hand-envelope-verify", name: "跨端 Hand 密封信封校验", summary: "X25519 + HKDF-SHA256 + AES-256-GCM 跨设备任务凭证与目录授权核验", triggerHint: "远程设备 / 配对")
    ]

    public init() {}

    public func toggleSkill(id: String) {
        guard let idx = skills.firstIndex(where: { $0.id == id }) else { return }
        skills[idx].enabled.toggle()
    }

    public func addCustomSkill(name: String, summary: String, triggerHint: String) {
        let slug = name.lowercased().replacingOccurrences(of: " ", with: "-")
        skills.append(SkillDescriptor(id: "user-\(slug)-\(skills.count + 1)", name: name, summary: summary, triggerHint: triggerHint, source: "user_generated"))
    }
}
