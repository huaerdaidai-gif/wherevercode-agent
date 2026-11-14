import Foundation

public struct BrainConfig: Codable, Equatable, Identifiable, Sendable {
    public var id: String { brainId }
    public let brainId: String
    public var kind: BrainKind
    public var adapterId: String
    public var label: String
    public var baseUrl: String?
    public var model: String?
    public var authRef: String?
    public var extra: [String: String]
    public var fallbackBrainIds: [String]
    public var loginState: String
    public var lastTestedAtEpochSeconds: Int64?
    public var lastTestSummary: String?
    public var cliBinary: String?

    public init(
        brainId: String,
        kind: BrainKind = .modelApi,
        adapterId: String,
        label: String,
        baseUrl: String? = nil,
        model: String? = nil,
        authRef: String? = nil,
        extra: [String: String] = [:],
        fallbackBrainIds: [String] = [],
        loginState: String = "NOT_CONFIGURED",
        lastTestedAtEpochSeconds: Int64? = nil,
        lastTestSummary: String? = nil,
        cliBinary: String? = nil
    ) {
        self.brainId = brainId
        self.kind = kind
        self.adapterId = adapterId
        self.label = label
        self.baseUrl = baseUrl
        self.model = model
        self.authRef = authRef
        self.extra = extra
        self.fallbackBrainIds = fallbackBrainIds
        self.loginState = loginState
        self.lastTestedAtEpochSeconds = lastTestedAtEpochSeconds
        self.lastTestSummary = lastTestSummary
        self.cliBinary = cliBinary
    }

    public func validate() throws {
        guard !brainId.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw NSError(domain: "BrainConfig", code: 1, userInfo: [NSLocalizedDescriptionKey: "大脑 id 不能为空"])
        }
        guard !label.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw NSError(domain: "BrainConfig", code: 2, userInfo: [NSLocalizedDescriptionKey: "大脑名字不能为空"])
        }
        guard !adapterId.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw NSError(domain: "BrainConfig", code: 3, userInfo: [NSLocalizedDescriptionKey: "适配器 id 不能为空"])
        }
        if fallbackBrainIds.contains(brainId) {
            throw NSError(domain: "BrainConfig", code: 4, userInfo: [NSLocalizedDescriptionKey: "fallback 列表不能包含自己"])
        }
        if let authRef {
            if authRef.trimmingCharacters(in: .whitespaces).isEmpty {
                throw NSError(domain: "BrainConfig", code: 5, userInfo: [NSLocalizedDescriptionKey: "密钥引用名不能为空字符串"])
            }
            if authRef.hasPrefix("sk-") || authRef.lowercased().hasPrefix("bearer ") {
                throw NSError(domain: "BrainConfig", code: 6, userInfo: [NSLocalizedDescriptionKey: "authRef 只能是引用名，不能是密钥本身"])
            }
        }
        if let baseUrl, !baseUrl.isEmpty {
            let lower = baseUrl.lowercased()
            let allowedLocal = lower.hasPrefix("http://127.0.0.1") || lower.hasPrefix("http://localhost")
            if !lower.hasPrefix("https://") && !allowedLocal {
                throw NSError(domain: "BrainConfig", code: 7, userInfo: [NSLocalizedDescriptionKey: "服务地址只允许 https（本地模型允许 http://127.0.0.1 或 http://localhost）：\(baseUrl)"])
            }
        }
    }
}

public protocol BrainConfigStore: AnyObject {
    func listConfigs() -> [BrainConfig]
    func getConfig(brainId: String) -> BrainConfig?
    func saveConfig(_ config: BrainConfig)
    func deleteConfig(brainId: String)
    func currentBrainId() -> String?
    func setCurrentBrainId(_ brainId: String?)
    func deletedBrainIds() -> Set<String>
    func markBrainDeleted(brainId: String)
    func clearBrainDeleted(brainId: String)
}

public final class KeyValueBrainConfigStore: BrainConfigStore {
    private let kv: KeyValueStore
    private let lock = NSLock()
    private let configsKey = "brain.configs_json"
    private let currentKey = "brain.current_brain_id"
    private let deletedKey = "brain.deleted_brain_ids_json"

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    public func listConfigs() -> [BrainConfig] {
        lock.lock()
        defer { lock.unlock() }
        return (try? kv.getJSON(configsKey, as: [BrainConfig].self)) ?? []
    }

    public func getConfig(brainId: String) -> BrainConfig? {
        listConfigs().first { $0.brainId == brainId }
    }

    public func saveConfig(_ config: BrainConfig) {
        lock.lock()
        defer { lock.unlock() }
        var list = (try? kv.getJSON(configsKey, as: [BrainConfig].self)) ?? []
        if let idx = list.firstIndex(where: { $0.brainId == config.brainId }) {
            list[idx] = config
        } else {
            list.append(config)
        }
        try? kv.setJSON(configsKey, list)
    }

    public func deleteConfig(brainId: String) {
        lock.lock()
        defer { lock.unlock() }
        let list = ((try? kv.getJSON(configsKey, as: [BrainConfig].self)) ?? []).filter { $0.brainId != brainId }
        try? kv.setJSON(configsKey, list)
        if (try? kv.get(currentKey)) == brainId {
            try? kv.set(currentKey, nil)
        }
    }

    public func currentBrainId() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return try? kv.get(currentKey)
    }

    public func setCurrentBrainId(_ brainId: String?) {
        lock.lock()
        defer { lock.unlock() }
        try? kv.set(currentKey, brainId)
    }

    public func deletedBrainIds() -> Set<String> {
        lock.lock()
        defer { lock.unlock() }
        let arr = (try? kv.getJSON(deletedKey, as: [String].self)) ?? []
        return Set(arr)
    }

    public func markBrainDeleted(brainId: String) {
        var set = deletedBrainIds()
        set.insert(brainId)
        lock.lock()
        defer { lock.unlock() }
        try? kv.setJSON(deletedKey, Array(set))
    }

    public func clearBrainDeleted(brainId: String) {
        var set = deletedBrainIds()
        set.remove(brainId)
        lock.lock()
        defer { lock.unlock() }
        try? kv.setJSON(deletedKey, Array(set))
    }
}

public final class BrainKeyStore {
    private let service = "wherevercode_brain_keys"

    public init() {}

    public func saveKey(_ key: String, for authRef: String) throws {
        let store = KeychainStore(service: service, account: authRef)
        try store.saveToken(key)
    }

    public func loadKey(for authRef: String) -> String? {
        let store = KeychainStore(service: service, account: authRef)
        return try? store.loadToken()
    }

    public func deleteKey(for authRef: String) {
        let store = KeychainStore(service: service, account: authRef)
        try? store.deleteToken()
    }
}
