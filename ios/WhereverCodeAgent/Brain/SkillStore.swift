import Foundation
import Combine

public enum SkillMemory {
    /// Project id of the global (project-independent) skill scope.
    public static let globalProjectId = ""

    public static func projectIdOrGlobal(_ projectId: String?) -> String {
        projectId ?? globalProjectId
    }
}

public struct SkillDescriptor: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public var name: String
    public var summary: String
    public var isEnabled: Bool
    public var triggerHint: String

    public init(id: String, name: String, summary: String, isEnabled: Bool = true, triggerHint: String) {
        self.id = id
        self.name = name
        self.summary = summary
        self.isEnabled = isEnabled
        self.triggerHint = triggerHint
    }
}

/// Skill store supporting both BrainEngine prompt summarization (PR #13)
/// and observable skill management UI (Build 6).
open class SkillStore: ObservableObject {
    @Published public private(set) var skills: [SkillDescriptor]

    public init(skills: [SkillDescriptor] = SkillStore.defaultSkills) {
        self.skills = skills
    }

    open func summarizeForPrompt(projectId: String?, taskText: String) -> String {
        let enabled = skills.filter(\.isEnabled)
        guard !enabled.isEmpty else { return "" }
        return enabled.map { "- \($0.name): \($0.summary) (触发词: \($0.triggerHint))" }.joined(separator: "\n")
    }

    public func toggleSkill(id: String) {
        guard let idx = skills.firstIndex(where: { $0.id == id }) else { return }
        skills[idx].isEnabled.toggle()
    }

    @discardableResult
    public func addCustomSkill(name: String, summary: String, triggerHint: String) -> SkillDescriptor {
        let descriptor = SkillDescriptor(
            id: "skill-\(UUID().uuidString.prefix(8))",
            name: SecretRedactor.redact(name),
            summary: SecretRedactor.redact(summary),
            isEnabled: true,
            triggerHint: SecretRedactor.redact(triggerHint)
        )
        skills.append(descriptor)
        return descriptor
    }

    public static let defaultSkills: [SkillDescriptor] = [
        SkillDescriptor(
            id: "ios-swift-audit",
            name: "Swift 并发与 Actor 审计",
            summary: "检查 @MainActor 隔离、Sendable 跨线程传递及任务取消安全。",
            isEnabled: true,
            triggerHint: "swift / actor / concurrency"
        ),
        SkillDescriptor(
            id: "mcp-channel-ops",
            name: "V5 团队频道与任务协同",
            summary: "遵循 V5 MCP JSON-RPC 2.0 协议拉取频道消息、认领任务与回报进度。",
            isEnabled: true,
            triggerHint: "mcp / channel / task"
        ),
        SkillDescriptor(
            id: "sqlite-wal-migration",
            name: "SQLite WAL 账本与迁移",
            summary: "维护 conversations、tasks、projects、kv 四类持久化账本一致性。",
            isEnabled: true,
            triggerHint: "sqlite / db / schema"
        ),
        SkillDescriptor(
            id: "security-redaction",
            name: "凭据脱敏与 Keychain 守护",
            summary: "强制走 Keychain WhenUnlockedThisDeviceOnly 并拦截日志中的敏感 Token。",
            isEnabled: true,
            triggerHint: "keychain / token / security"
        ),
    ]
}

public final class EmptySkillStore: SkillStore {
    public init() {
        super.init(skills: [])
    }

    public override func summarizeForPrompt(projectId: String?, taskText: String) -> String { "" }
}
