import Foundation

/// BrainConfig: how the user configures a brain (stage 2), ported from
/// the Android baseline `brain/BrainConfig.kt` on main @4496658e.
///
/// Key rules:
/// - The secret lives ONLY on the phone, in the Keychain
///   ([BrainKeyStore]). The config holds only `authRef` (a reference
///   name such as "key:openai-1"), never the value. `exportConfig`
///   exports the reference name only.
/// - baseUrl comes ONLY from user configuration. It must never come
///   from task content or chat messages ([BaseUrlSource] is checked by
///   the validator).
/// - There is NO default brain: `currentBrainId()` is nil until the
///   user configures one, and the UI must show "未配置大脑". Nothing in
///   code pre-fills any vendor as the default.

/// Adapter-id prefix for device-code membership brains (Qwen / Kimi /
/// Grok membership): adapter ids are `"devicecode-<vendorId>"`.
public let deviceCodeAdapterIdPrefix = "devicecode-"

/// `extra` key marking a login-only brain: `extra["login_only"] ==
/// "true"` means the brain exists only as a login entry — signing in
/// never makes it usable for inference.
public let loginOnlyExtraKey = "login_only"

public struct BrainConfig: Equatable {
    public var brainId: String
    public var kind: BrainKind
    public var adapterId: String
    public var label: String
    public var baseUrl: String?
    public var model: String
    public var authRef: String?
    public var extra: [String: String] = [:]
    public var fallbackBrainIds: [String] = []
    /// Login state, last verification time / summary, and CLI binary.
    /// All default to nil: old stored JSON without these keys reads
    /// back with nils, no migration. Credentials are still only ever
    /// referenced by `authRef`, never stored here.
    public var loginState: AgentLoginState? = nil
    public var lastTestedAtEpochSeconds: Int64? = nil
    public var lastTestSummary: String? = nil
    public var cliBinary: String? = nil

    public init(
        brainId: String,
        kind: BrainKind,
        adapterId: String,
        label: String,
        baseUrl: String?,
        model: String,
        authRef: String?,
        extra: [String: String] = [:],
        fallbackBrainIds: [String] = [],
        loginState: AgentLoginState? = nil,
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
}

public enum BaseUrlSource {
    case userConfig
    case taskContent
    case chatMessage
}

public enum BrainConfigValidator {
    private static let secretLikeValue = try! NSRegularExpression(
        pattern: "(sk-[A-Za-z0-9_-]{12,}|Bearer\\s+\\S+|-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----)",
        options: [.caseInsensitive])
    private static let secretLikeKey = try! NSRegularExpression(
        pattern: "(API[_-]?KEY|ACCESS[_-]?KEY|SECRET|TOKEN|PASSWORD|PASSWD|PRIVATE[_-]?KEY)",
        options: [.caseInsensitive])

    private static func matches(_ regex: NSRegularExpression, in value: String) -> Bool {
        regex.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)) != nil
    }

    public static func looksLikeSecretValue(_ value: String) -> Bool {
        matches(secretLikeValue, in: value)
    }

    static func looksLikeSecretKey(_ key: String) -> Bool {
        matches(secretLikeKey, in: key)
    }

    /// Validate a config. Returns human-actionable errors; empty =
    /// valid.
    public static func validate(
        _ config: BrainConfig,
        baseUrlSource: BaseUrlSource = .userConfig
    ) -> [String] {
        var errors: [String] = []
        if config.brainId.trimmingCharacters(in: .whitespaces).isEmpty { errors.append("大脑 id 不能为空") }
        if config.label.trimmingCharacters(in: .whitespaces).isEmpty { errors.append("大脑名字不能为空") }
        if config.adapterId.trimmingCharacters(in: .whitespaces).isEmpty { errors.append("适配器 id 不能为空") }
        if config.fallbackBrainIds.contains(config.brainId) { errors.append("fallback 列表不能包含自己") }
        // SIWC exception (ChatGPT subscription via Sign in with
        // ChatGPT): authRef is allowed (it points at the SIWC token
        // store entry, never a secret value), and model may be blank —
        // blank means "not chosen yet", never a provider default; the
        // adapter resolves it at runtime from the account's own model
        // directory and persists the resolved model.
        let isSiwc = config.adapterId == "siwc-chatgpt"
        // Device-code membership brains (Qwen / Kimi / Grok): the
        // membership token is the user's own OAuth token in the local
        // device-code token store, so authRef is allowed, cliBinary is
        // not required, the model may be blank, and a filled baseUrl
        // must be a valid https address.
        let isDeviceCode = config.adapterId.hasPrefix(deviceCodeAdapterIdPrefix)
        // Login-only brains (Gemini): no model, no cliBinary, no
        // authRef — validation only passes the empty login entry; the
        // inference layer refuses chat calls honestly.
        let isLoginOnly = config.extra[loginOnlyExtraKey] == "true"
        if config.model.trimmingCharacters(in: .whitespaces).isEmpty
            && !isSiwc && !isDeviceCode && !isLoginOnly {
            errors.append("模型不能为空")
        }
        // baseUrl: only from user configuration, https only
        // (local-model exception).
        if config.kind == .modelApi {
            let url = config.baseUrl
            if url == nil || url!.trimmingCharacters(in: .whitespaces).isEmpty {
                errors.append("模型 API 需要填写服务地址 baseUrl")
            } else {
                if baseUrlSource != .userConfig {
                    errors.append("服务地址只能来自用户配置，不能来自任务内容或聊天消息")
                }
                if !isAllowedBaseUrl(url!) {
                    errors.append("服务地址只允许 https（本地模型允许 http://127.0.0.1 或 http://localhost）：\(url!)")
                }
            }
        }
        if config.kind == .subscription, let url = config.baseUrl,
           !url.trimmingCharacters(in: .whitespaces).isEmpty, !isDeviceCode {
            errors.append("订阅授权没有服务地址：请去掉 baseUrl，改选已登录的 CLI")
        }
        if isDeviceCode, let url = config.baseUrl,
           !url.trimmingCharacters(in: .whitespaces).isEmpty, !isAllowedBaseUrl(url) {
            errors.append("设备码会员大脑的推理地址只允许 https：\(url)")
        }
        if config.kind == .subscription && config.authRef != nil && !isSiwc && !isDeviceCode {
            errors.append("订阅授权没有密钥输入框：请去掉 authRef，改用已登录 CLI 的会员额度")
        }
        // A SUBSCRIPTION brain that is not SIWC borrows a logged-in
        // CLI's membership quota, so it must name the CLI binary; an
        // AGENT_RUNTIME brain likewise must name its binary.
        if config.kind == .subscription && !isSiwc && !isDeviceCode && !isLoginOnly
            && (config.cliBinary ?? "").trimmingCharacters(in: .whitespaces).isEmpty {
            errors.append("订阅授权（非 ChatGPT 登录）需要已登录的 CLI：请填写 cliBinary")
        }
        if config.kind == .agentRuntime
            && (config.cliBinary ?? "").trimmingCharacters(in: .whitespaces).isEmpty {
            errors.append("本机 CLI 大脑需要填写 CLI 二进制名 cliBinary")
        }
        // authRef is a reference name, never a secret value.
        if let ref = config.authRef {
            if ref.trimmingCharacters(in: .whitespaces).isEmpty { errors.append("密钥引用名不能为空字符串") }
            if looksLikeSecretValue(ref) { errors.append("authRef 只能是引用名，不能是密钥本身") }
        }
        // extra must never carry a secret value.
        for (key, value) in config.extra {
            if looksLikeSecretKey(key) || looksLikeSecretValue(value) {
                errors.append("extra 里不能放密钥（字段 \(key)）")
            }
        }
        return errors
    }

    /// Local-model URL: the host must be exactly 127.0.0.1 or
    /// localhost (optionally with a port / path) — the same rule as
    /// the local exception in `isAllowedBaseUrl`.
    public static func isLocalModelUrl(_ url: String) -> Bool {
        let lower = url.trimmingCharacters(in: .whitespaces).lowercased()
        return lower == "http://127.0.0.1" || lower == "http://localhost"
            || lower.hasPrefix("http://127.0.0.1/") || lower.hasPrefix("http://127.0.0.1:")
            || lower.hasPrefix("http://localhost/") || lower.hasPrefix("http://localhost:")
    }

    public static func isAllowedBaseUrl(_ url: String) -> Bool {
        let lower = url.trimmingCharacters(in: .whitespaces).lowercased()
        if lower.hasPrefix("https://") { return true }
        // Local-model exception: the host must be exactly 127.0.0.1 or
        // localhost (optionally with a port / path), never a longer
        // host that merely starts with those strings.
        for host in ["http://127.0.0.1", "http://localhost"] {
            if lower == host { return true }
            if lower.hasPrefix(host + "/") || lower.hasPrefix(host + ":")
                || lower.hasPrefix(host + "?") || lower.hasPrefix(host + "#") {
                return true
            }
        }
        return false
    }

    /// Export a config for backup/sharing: reference names only,
    /// never secret values.
    public static func exportConfig(_ config: BrainConfig) -> [String: JSONValue] {
        let safeExtra = config.extra.filter { !looksLikeSecretKey($0.key) }
        var out: [String: JSONValue] = [
            "brainId": .string(config.brainId),
            "kind": .string(config.kind.name),
            "adapterId": .string(config.adapterId),
            "label": .string(config.label),
            "model": .string(config.model),
            "extra": .object(safeExtra.mapValues { JSONValue.string($0) }),
            "fallbackBrainIds": .array(config.fallbackBrainIds.map { JSONValue.string($0) }),
        ]
        out["baseUrl"] = config.baseUrl.map(JSONValue.string) ?? .null
        out["authRef"] = config.authRef.map(JSONValue.string) ?? .null
        // Login state / verification summary / CLI binary are not
        // secrets, so they are exportable like the other fields.
        out["loginState"] = config.loginState.map { JSONValue.string($0.rawValue) } ?? .null
        out["lastTestedAtEpochSeconds"] = config.lastTestedAtEpochSeconds.map { .number(Double($0)) } ?? .null
        out["lastTestSummary"] = config.lastTestSummary.map(JSONValue.string) ?? .null
        out["cliBinary"] = config.cliBinary.map(JSONValue.string) ?? .null
        return out
    }
}

public enum BrainKeyStoreError: Error, Equatable {
    case blankAuthRef
    case authRefLooksLikeSecret
}

/// Keychain-backed secret storage. Secrets go here and nowhere else:
/// never in BrainConfig, never in logs, never in task results, never
/// in exported configs.
public final class BrainKeyStore {
    /// Keychain service mirroring Android's encrypted prefs file name.
    public static let service = "wherevercode_brain_keys"

    public init() {}

    private func store(for authRef: String) -> KeychainStore {
        KeychainStore(service: Self.service, account: authRef)
    }

    public func putSecret(authRef: String, value: String) throws {
        guard !authRef.trimmingCharacters(in: .whitespaces).isEmpty else {
            throw BrainKeyStoreError.blankAuthRef
        }
        guard !BrainConfigValidator.looksLikeSecretValue(authRef) else {
            throw BrainKeyStoreError.authRefLooksLikeSecret
        }
        try store(for: authRef).saveToken(value)
    }

    public func getSecret(authRef: String) -> String? {
        try? store(for: authRef).loadToken()
    }

    public func hasSecret(authRef: String) -> Bool {
        store(for: authRef).hasToken
    }

    public func deleteSecret(authRef: String) {
        try? store(for: authRef).deleteToken()
    }
}

public protocol BrainConfigStore {
    func listConfigs() -> [BrainConfig]
    func getConfig(brainId: String) -> BrainConfig?
    func saveConfig(_ config: BrainConfig)
    func deleteConfig(brainId: String)
    /// Nil = no brain configured yet (UI shows "未配置大脑"). No
    /// default brain.
    func currentBrainId() -> String?
    func setCurrentBrainId(_ brainId: String?)

    /// Deleted-brain tombstones: a first-line brain the user deleted
    /// must NOT come back on the next app start — including after an
    /// app update (an update replaces code only; the store holding
    /// the tombstones survives it). The bootstrap reseeds first-line
    /// brains only when their id is not in this set. Default
    /// implementations are no-ops so in-memory/test stores keep
    /// working unchanged; the production store persists the set.
    func deletedBrainIds() -> Set<String>
    func markBrainDeleted(brainId: String)
    func clearBrainDeleted(brainId: String)
}

extension BrainConfigStore {
    public func deletedBrainIds() -> Set<String> { [] }
    public func markBrainDeleted(brainId: String) {}
    public func clearBrainDeleted(brainId: String) {}
}

/// Production store over the shared key-value table (the iOS
/// counterpart of Android's SharedPreferences store). Configs persist
/// as one JSON array in the same shape Android writes.
public final class KeyValueBrainConfigStore: BrainConfigStore {
    public static let keyConfigs = "brain.configs_json"
    public static let keyCurrent = "brain.current_brain_id"
    public static let keyDeleted = "brain.deleted_brain_ids_json"

    private let kv: KeyValueStore
    private let lock = NSLock()

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    public func listConfigs() -> [BrainConfig] {
        lock.lock(); defer { lock.unlock() }
        guard let json = try? kv.get(Self.keyConfigs), let raw = json,
              let data = raw.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { return [] }
        return array.compactMap { Self.parseConfig($0) }
    }

    public func getConfig(brainId: String) -> BrainConfig? {
        lock.lock(); defer { lock.unlock() }
        return unlockedList().first { $0.brainId == brainId }
    }

    public func saveConfig(_ config: BrainConfig) {
        lock.lock(); defer { lock.unlock() }
        let configs = unlockedList().filter { $0.brainId != config.brainId } + [config]
        persist(configs)
    }

    public func deleteConfig(brainId: String) {
        lock.lock(); defer { lock.unlock() }
        persist(unlockedList().filter { $0.brainId != brainId })
        if unlockedCurrent() == brainId { unlockedSetCurrent(nil) }
    }

    public func currentBrainId() -> String? {
        lock.lock(); defer { lock.unlock() }
        return unlockedCurrent()
    }

    public func setCurrentBrainId(_ brainId: String?) {
        lock.lock(); defer { lock.unlock() }
        unlockedSetCurrent(brainId)
    }

    public func deletedBrainIds() -> Set<String> {
        lock.lock(); defer { lock.unlock() }
        guard let json = try? kv.get(Self.keyDeleted), let raw = json,
              let data = raw.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { return [] }
        return Set(array.compactMap { ($0 as? String) }
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty })
    }

    public func markBrainDeleted(brainId: String) {
        lock.lock(); defer { lock.unlock() }
        var ids = unlockedDeleted()
        if ids.insert(brainId).inserted { persistDeleted(ids) }
    }

    public func clearBrainDeleted(brainId: String) {
        lock.lock(); defer { lock.unlock() }
        var ids = unlockedDeleted()
        if ids.remove(brainId) != nil { persistDeleted(ids) }
    }

    // MARK: - Unlocked internals

    private func unlockedList() -> [BrainConfig] {
        guard let json = try? kv.get(Self.keyConfigs), let raw = json,
              let data = raw.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { return [] }
        return array.compactMap { Self.parseConfig($0) }
    }

    private func unlockedCurrent() -> String? {
        (try? kv.get(Self.keyCurrent)) ?? nil
    }

    private func unlockedSetCurrent(_ brainId: String?) {
        try? kv.set(Self.keyCurrent, brainId)
    }

    private func unlockedDeleted() -> Set<String> {
        guard let json = try? kv.get(Self.keyDeleted), let raw = json,
              let data = raw.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { return [] }
        return Set(array.compactMap { ($0 as? String) }
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty })
    }

    private func persistDeleted(_ ids: Set<String>) {
        let array = ids.sorted().map { $0 as Any }
        if let data = try? JSONSerialization.data(withJSONObject: array),
           let text = String(data: data, encoding: .utf8) {
            try? kv.set(Self.keyDeleted, text)
        }
    }

    private func persist(_ configs: [BrainConfig]) {
        let array = configs.map { Self.serialize($0) }
        if let data = try? JSONSerialization.data(withJSONObject: array),
           let text = String(data: data, encoding: .utf8) {
            try? kv.set(Self.keyConfigs, text)
        }
    }

    private static func serialize(_ config: BrainConfig) -> [String: Any] {
        var obj: [String: Any] = [
            "brainId": config.brainId,
            "kind": config.kind.name,
            "adapterId": config.adapterId,
            "label": config.label,
            "model": config.model,
            "extra": config.extra,
            "fallbackBrainIds": config.fallbackBrainIds,
        ]
        obj["baseUrl"] = config.baseUrl ?? NSNull()
        obj["authRef"] = config.authRef ?? NSNull()
        obj["loginState"] = config.loginState?.rawValue ?? NSNull()
        obj["lastTestedAtEpochSeconds"] = config.lastTestedAtEpochSeconds.map { NSNumber(value: $0) } ?? NSNull()
        obj["lastTestSummary"] = config.lastTestSummary ?? NSNull()
        obj["cliBinary"] = config.cliBinary ?? NSNull()
        return obj
    }

    private static func parseConfig(_ raw: Any) -> BrainConfig? {
        guard let obj = raw as? [String: Any] else { return nil }
        func optString(_ key: String) -> String? {
            if obj[key] is NSNull { return nil }
            return obj[key] as? String
        }
        var extra: [String: String] = [:]
        if let extraObj = obj["extra"] as? [String: Any] {
            for (key, value) in extraObj { extra[key] = (value as? String) ?? "" }
        }
        var fallback: [String] = []
        if let fallbackArray = obj["fallbackBrainIds"] as? [Any] {
            fallback = fallbackArray.compactMap { $0 as? String }.filter { !$0.isEmpty }
        }
        // An unknown loginState name is treated as absent rather than
        // failing the whole config list (same tolerance as Android).
        let loginState = optString("loginState")
            .flatMap { $0.isEmpty ? nil : AgentLoginState(rawValue: $0) }
        return BrainConfig(
            brainId: (obj["brainId"] as? String) ?? "",
            kind: BrainKind(name: (obj["kind"] as? String) ?? "MODEL_API") ?? .modelApi,
            adapterId: (obj["adapterId"] as? String) ?? "",
            label: (obj["label"] as? String) ?? "",
            baseUrl: optString("baseUrl"),
            model: (obj["model"] as? String) ?? "",
            authRef: optString("authRef"),
            extra: extra,
            fallbackBrainIds: fallback,
            loginState: loginState,
            lastTestedAtEpochSeconds: (obj["lastTestedAtEpochSeconds"] as? NSNumber)?.int64Value,
            lastTestSummary: optString("lastTestSummary"),
            cliBinary: optString("cliBinary"))
    }
}
