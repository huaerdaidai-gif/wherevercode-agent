import Foundation

/// 自定义免费服务商（预留扩展口，对齐 Android CustomFreeProviders.kt）：
/// 名单上没有的免费服务商，用户填名称 + 地址 + 名单形态即可加，
/// 存本机后与内置预设走同一条流程。
/// 内置名单（官方核验）与自定义（用户自加）分开存储、呈现标注，不合并。
public enum CustomFreeProviders {

    public static func isCustom(_ preset: FreeProviderPreset) -> Bool {
        preset.id.hasPrefix("custom-")
    }

    /// 根据用户输入的名称生成稳定的 slug id，防重名 collision
    public static func customId(_ name: String, existing: Set<String>) -> String {
        let lowered = name.lowercased()
        let scalars = lowered.unicodeScalars.map { scalar -> Character in
            CharacterSet.alphanumerics.contains(scalar) ? Character(scalar) : "-"
        }
        let slug = String(scalars).split(separator: "-", omittingEmptySubsequences: true).joined(separator: "-")
        let base = "custom-" + (slug.isEmpty ? "provider" : slug)

        var candidate = base
        var counter = 2
        while existing.contains(candidate) {
            candidate = "\(base)-\(counter)"
            counter += 1
        }
        return candidate
    }

    /// 内置名单在前，自定义名单在后
    public static func merged(builtins: [FreeProviderPreset], customs: [FreeProviderPreset]) -> [FreeProviderPreset] {
        builtins + customs
    }
}

public protocol CustomFreeProviderStore: Sendable {
    func list() -> [FreeProviderPreset]
    func add(_ preset: FreeProviderPreset)
    func remove(id: String)
}

public final class KeyValueCustomFreeProviderStore: CustomFreeProviderStore, @unchecked Sendable {
    private let storageKey = "wherevercode_custom_free_providers"
    private let userDefaults: UserDefaults

    public init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    public func list() -> [FreeProviderPreset] {
        guard let data = userDefaults.data(forKey: storageKey) else { return [] }
        return (try? JSONDecoder().decode([FreeProviderPreset].self, from: data)) ?? []
    }

    public func add(_ preset: FreeProviderPreset) {
        var current = list().filter { $0.id != preset.id }
        current.append(preset)
        if let data = try? JSONEncoder().encode(current) {
            userDefaults.set(data, forKey: storageKey)
        }
    }

    public func remove(id: String) {
        let current = list().filter { $0.id != id }
        if let data = try? JSONEncoder().encode(current) {
            userDefaults.set(data, forKey: storageKey)
        }
    }
}
