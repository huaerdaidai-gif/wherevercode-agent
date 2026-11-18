import Foundation

/// 智能路由（对齐 Android ModelRouting.kt）：
/// - 以免费为主、付费可选：路由默认只在免费模型之间切换；付费模型
///   只有在用户主动打开 [allowPaid] 后才进池。
/// - v1 只做优先级顺序 + 故障切换：遇可重试失败（402 额度/429 限流/5xx 等，
///   retryable 由适配器判定）按池顺序切到下一个并重发，用户无感；
///   401/400 等不可重试错误不切（立即报出）。
/// - 总开关默认关；关时引擎行为与从前一字不变。
/// - 池只收 MODEL_API 模型连接；大脑/会员/Agent 不进模型路由池
///   （概念分离：模型是模型、Agent 是 Agent）。
public struct ModelRoutingSettings: Codable, Equatable, Sendable {
    public var enabled: Bool = false
    public var allowPaid: Bool = false
    /// 路由池顺序（brainId）：靠前优先被切到；池外模型不参与。
    public var orderedBrainIds: [String] = []

    public init(enabled: Bool = false, allowPaid: Bool = false, orderedBrainIds: [String] = []) {
        self.enabled = enabled
        self.allowPaid = allowPaid
        self.orderedBrainIds = orderedBrainIds
    }
}

public enum ModelRouting {

    /// 免费档标记：从免费预设/自动发现添加的配置带 tier=free。
    public static func isFree(config: BrainConfig) -> Bool {
        config.extra[FreeProviderPresets.extraTier] == FreeProviderPresets.tierFree
    }

    /// 本轮的切换顺序：池内、仍存在、MODEL_API、档次合规（未开付费时只留免费）、排除当前模型本身。池顺序即优先级。
    public static func fallbackOrder(
        settings: ModelRoutingSettings,
        configs: [BrainConfig],
        currentBrainId: String
    ) -> [String] {
        guard settings.enabled else { return [] }
        let byId = Dictionary(uniqueKeysWithValues: configs.map { ($0.brainId, $0) })
        return settings.orderedBrainIds.filter { id in
            if id == currentBrainId { return false }
            guard let config = byId[id] else { return false }
            if config.kind != .modelApi { return false }
            return settings.allowPaid || isFree(config: config)
        }
    }

    public static func policyFor(
        settings: ModelRoutingSettings,
        configs: [BrainConfig],
        currentBrainId: String
    ) -> FailoverPolicy {
        FailoverPolicy(fallbackBrainIds: fallbackOrder(settings: settings, configs: configs, currentBrainId: currentBrainId))
    }
}

public protocol ModelRoutingStore: Sendable {
    func load() -> ModelRoutingSettings
    func save(_ settings: ModelRoutingSettings)
}

public final class UserDefaultsModelRoutingStore: ModelRoutingStore, @unchecked Sendable {
    private let storageKey = "wherevercode_model_routing"
    private let userDefaults: UserDefaults

    public init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    public func load() -> ModelRoutingSettings {
        guard let data = userDefaults.data(forKey: storageKey),
              let settings = try? JSONDecoder().decode(ModelRoutingSettings.self, from: data) else {
            return ModelRoutingSettings()
        }
        return settings
    }

    public func save(_ settings: ModelRoutingSettings) {
        if let data = try? JSONEncoder().encode(settings) {
            userDefaults.set(data, forKey: storageKey)
        }
    }
}
