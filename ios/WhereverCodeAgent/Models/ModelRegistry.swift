// ModelRegistry.swift
// WhereverCode Agent — 独立模型注册表与配置存储
// 依据 Funk 2026-11-19 指示与 ~/awm-work/agy-ios-b3-brief.md：
// - 与大脑（BrainConfigStore / brain.configs_json）彻底脱钩，建立模型独立存储；
// - 独立存储键：model.configs_json（配置列表）、model.current_model_id（当前模型）、model.deleted_builtin_ids_json（墓碑）；
// - 结构对齐独立模型配置管理，已添加模型列表增删改查全走本存储；
// - 运行时通过 CompositeBrainConfigStore 提供只读合并视图注入 App 引擎。

import Foundation
import SwiftUI

// MARK: - 1. 模型独立配置实体

public struct ModelConfig: Equatable, Codable, Identifiable, Sendable {
    public typealias ID = String
    public var id: String { modelId }
    public var modelId: String
    public var kind: BrainKind
    public var adapterId: String
    public var label: String
    public var baseUrl: String?
    public var model: String
    public var authRef: String?
    public var extra: [String: String] = [:]
    public var fallbackModelIds: [String] = []
    public var loginState: AgentLoginState? = nil
    public var lastTestedAtEpochSeconds: Int64? = nil
    public var lastTestSummary: String? = nil
    public var cliBinary: String? = nil

    public init(
        modelId: String,
        kind: BrainKind = .modelApi,
        adapterId: String = GenericOpenAiBrainAdapter.adapterId,
        label: String,
        baseUrl: String? = nil,
        model: String,
        authRef: String? = nil,
        extra: [String: String] = [:],
        fallbackModelIds: [String] = [],
        loginState: AgentLoginState? = nil,
        lastTestedAtEpochSeconds: Int64? = nil,
        lastTestSummary: String? = nil,
        cliBinary: String? = nil
    ) {
        self.modelId = modelId
        self.kind = kind
        self.adapterId = adapterId
        self.label = label
        self.baseUrl = baseUrl
        self.model = model
        self.authRef = authRef
        self.extra = extra
        self.fallbackModelIds = fallbackModelIds
        self.loginState = loginState
        self.lastTestedAtEpochSeconds = lastTestedAtEpochSeconds
        self.lastTestSummary = lastTestSummary
        self.cliBinary = cliBinary
    }

    public func toBrainConfig() -> BrainConfig {
        BrainConfig(
            brainId: modelId,
            kind: kind,
            adapterId: adapterId,
            label: label,
            baseUrl: baseUrl,
            model: model,
            authRef: authRef,
            extra: extra,
            fallbackBrainIds: fallbackModelIds,
            loginState: loginState,
            lastTestedAtEpochSeconds: lastTestedAtEpochSeconds,
            lastTestSummary: lastTestSummary,
            cliBinary: cliBinary
        )
    }

    public static func fromBrainConfig(_ brain: BrainConfig) -> ModelConfig {
        ModelConfig(
            modelId: brain.brainId,
            kind: brain.kind,
            adapterId: brain.adapterId,
            label: brain.label,
            baseUrl: brain.baseUrl,
            model: brain.model,
            authRef: brain.authRef,
            extra: brain.extra,
            fallbackModelIds: brain.fallbackBrainIds,
            loginState: brain.loginState,
            lastTestedAtEpochSeconds: brain.lastTestedAtEpochSeconds,
            lastTestSummary: brain.lastTestSummary,
            cliBinary: brain.cliBinary
        )
    }
}

// MARK: - 2. 模型配置 Store 协议

public protocol ModelConfigStore: AnyObject {
    func listConfigs() -> [ModelConfig]
    func getConfig(modelId: String) -> ModelConfig?
    func saveConfig(_ config: ModelConfig)
    func deleteConfig(modelId: String)
    func currentModelId() -> String?
    func setCurrentModelId(_ modelId: String?)
    func deletedModelIds() -> Set<String>
    func markModelDeleted(modelId: String)
    func clearModelDeleted(modelId: String)
}

extension ModelConfigStore {
    public func deletedModelIds() -> Set<String> { [] }
    public func markModelDeleted(modelId: String) {}
    public func clearModelDeleted(modelId: String) {}
}

// MARK: - 3. 基于 KeyValueStore 的模型存储实现 (Key: model.configs_json)

public final class KeyValueModelConfigStore: ModelConfigStore, @unchecked Sendable {
    public static let keyConfigs = "model.configs_json"
    public static let keyCurrent = "model.current_model_id"
    public static let keyDeleted = "model.deleted_builtin_ids_json"

    private let kv: KeyValueStore
    private let lock = NSLock()

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    public func listConfigs() -> [ModelConfig] {
        lock.lock(); defer { lock.unlock() }
        guard let json = try? kv.get(Self.keyConfigs),
              let data = json.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { return [] }
        return array.compactMap { Self.parseConfig($0) }
    }

    public func getConfig(modelId: String) -> ModelConfig? {
        lock.lock(); defer { lock.unlock() }
        return unlockedList().first { $0.modelId == modelId }
    }

    public func saveConfig(_ config: ModelConfig) {
        lock.lock(); defer { lock.unlock() }
        let configs = unlockedList().filter { $0.modelId != config.modelId } + [config]
        persist(configs)
    }

    public func deleteConfig(modelId: String) {
        lock.lock(); defer { lock.unlock() }
        persist(unlockedList().filter { $0.modelId != modelId })
        if unlockedCurrent() == modelId { unlockedSetCurrent(nil) }
    }

    public func currentModelId() -> String? {
        lock.lock(); defer { lock.unlock() }
        return unlockedCurrent()
    }

    public func setCurrentModelId(_ modelId: String?) {
        lock.lock(); defer { lock.unlock() }
        unlockedSetCurrent(modelId)
    }

    public func deletedModelIds() -> Set<String> {
        lock.lock(); defer { lock.unlock() }
        return unlockedDeletedModelIds()
    }

    public func markModelDeleted(modelId: String) {
        lock.lock(); defer { lock.unlock() }
        var ids = unlockedDeletedModelIds()
        ids.insert(modelId)
        unlockedSaveDeletedModelIds(ids)
    }

    public func clearModelDeleted(modelId: String) {
        lock.lock(); defer { lock.unlock() }
        var ids = unlockedDeletedModelIds()
        ids.remove(modelId)
        unlockedSaveDeletedModelIds(ids)
    }

    private func unlockedList() -> [ModelConfig] {
        guard let json = try? kv.get(Self.keyConfigs),
              let data = json.data(using: .utf8),
              let array = (try? JSONSerialization.jsonObject(with: data)) as? [Any]
        else { return [] }
        return array.compactMap { Self.parseConfig($0) }
    }

    private func unlockedCurrent() -> String? {
        (try? kv.get(Self.keyCurrent))?.trimmingCharacters(in: .whitespaces)
    }

    private func unlockedSetCurrent(_ modelId: String?) {
        let value = modelId?.trimmingCharacters(in: .whitespaces)
        try? kv.set(Self.keyCurrent, (value?.isEmpty ?? true) ? nil : value)
    }

    private func unlockedDeletedModelIds() -> Set<String> {
        guard let json = try? kv.get(Self.keyDeleted),
              let data = json.data(using: .utf8),
              let list = (try? JSONSerialization.jsonObject(with: data)) as? [String]
        else { return [] }
        return Set(list)
    }

    private func unlockedSaveDeletedModelIds(_ ids: Set<String>) {
        if let data = try? JSONSerialization.data(withJSONObject: Array(ids), options: []),
           let str = String(data: data, encoding: .utf8) {
            try? kv.set(Self.keyDeleted, str)
        }
    }

    private func persist(_ configs: [ModelConfig]) {
        let dicts = configs.map { Self.serializeConfig($0) }
        guard let data = try? JSONSerialization.data(withJSONObject: dicts, options: []),
              let str = String(data: data, encoding: .utf8)
        else { return }
        try? kv.set(Self.keyConfigs, str)
    }

    // MARK: - 序列化与反序列化工具

    public static func parseConfig(_ any: Any) -> ModelConfig? {
        guard let dict = any as? [String: Any],
              let modelId = (dict["modelId"] as? String) ?? (dict["brainId"] as? String),
              let kindRaw = dict["kind"] as? String,
              let kind = BrainKind(rawValue: kindRaw),
              let adapterId = dict["adapterId"] as? String,
              let label = dict["label"] as? String,
              let model = dict["model"] as? String
        else { return nil }

        let baseUrl = dict["baseUrl"] as? String
        let authRef = dict["authRef"] as? String
        let extra = (dict["extra"] as? [String: String]) ?? [:]
        let fallbackModelIds = (dict["fallbackModelIds"] as? [String]) ?? (dict["fallbackBrainIds"] as? [String]) ?? []

        var loginState: AgentLoginState? = nil
        if let rawState = dict["loginState"] as? String {
            loginState = AgentLoginState(rawValue: rawState)
        }
        let lastTestedAt = (dict["lastTestedAtEpochSeconds"] as? NSNumber)?.int64Value
        let lastTestSummary = dict["lastTestSummary"] as? String
        let cliBinary = dict["cliBinary"] as? String

        return ModelConfig(
            modelId: modelId,
            kind: kind,
            adapterId: adapterId,
            label: label,
            baseUrl: baseUrl,
            model: model,
            authRef: authRef,
            extra: extra,
            fallbackModelIds: fallbackModelIds,
            loginState: loginState,
            lastTestedAtEpochSeconds: lastTestedAt,
            lastTestSummary: lastTestSummary,
            cliBinary: cliBinary
        )
    }

    public static func serializeConfig(_ config: ModelConfig) -> [String: Any] {
        var dict: [String: Any] = [
            "modelId": config.modelId,
            "kind": config.kind.rawValue,
            "adapterId": config.adapterId,
            "label": config.label,
            "model": config.model,
            "extra": config.extra,
            "fallbackModelIds": config.fallbackModelIds
        ]
        if let baseUrl = config.baseUrl { dict["baseUrl"] = baseUrl }
        if let authRef = config.authRef { dict["authRef"] = authRef }
        if let loginState = config.loginState { dict["loginState"] = loginState.rawValue }
        if let lastTestedAt = config.lastTestedAtEpochSeconds { dict["lastTestedAtEpochSeconds"] = lastTestedAt }
        if let lastTestSummary = config.lastTestSummary { dict["lastTestSummary"] = lastTestSummary }
        if let cliBinary = config.cliBinary { dict["cliBinary"] = cliBinary }
        return dict
    }
}

// MARK: - 4. 模型页 ViewModel

@MainActor
public final class ModelSettingsModel: ObservableObject {
    @Published public private(set) var configs: [ModelConfig] = []
    @Published public private(set) var currentModelId: String?
    @Published public var routingSettings: ModelRoutingSettings = ModelRoutingSettings()

    public let configStore: ModelConfigStore
    public let keyStore: BrainKeyStore
    public let routingStore: ModelRoutingStore
    public let customProviderStore: CustomFreeProviderStore
    public var brainConfigStore: BrainConfigStore? = nil
    public var agentRegistryStore: AgentRegistryStore? = nil
    public var registryProvider: () -> BrainAdapterRegistry = { BrainAdapterRegistry() }

    public var onChange: () -> Void = {}

    public init(
        configStore: ModelConfigStore,
        keyStore: BrainKeyStore,
        routingStore: ModelRoutingStore = UserDefaultsModelRoutingStore(),
        customProviderStore: CustomFreeProviderStore = KeyValueCustomFreeProviderStore(),
        brainConfigStore: BrainConfigStore? = nil,
        agentRegistryStore: AgentRegistryStore? = nil
    ) {
        self.configStore = configStore
        self.keyStore = keyStore
        self.routingStore = routingStore
        self.customProviderStore = customProviderStore
        self.brainConfigStore = brainConfigStore
        self.agentRegistryStore = agentRegistryStore
        reload()
    }

    public func reload() {
        configs = configStore.listConfigs()
        currentModelId = configStore.currentModelId()
        routingSettings = routingStore.load()
    }

    public func saveRoutingSettings(_ settings: ModelRoutingSettings) {
        routingSettings = settings
        routingStore.save(settings)
    }

    public func hasKey(for config: ModelConfig) -> Bool {
        guard let authRef = config.authRef else { return false }
        return keyStore.getSecret(authRef: authRef) != nil
    }

    public func setKey(_ key: String, for config: ModelConfig) {
        guard let authRef = config.authRef else { return }
        try? keyStore.putSecret(authRef: authRef, value: key)
    }

    public func addModelConfig(
        label: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        extra: [String: String] = [:]
    ) {
        let modelId = UUID().uuidString
        let authRef = "model-api:\(modelId)"
        if !apiKey.isEmpty {
            try? keyStore.putSecret(authRef: authRef, value: apiKey)
        }
        let config = ModelConfig(
            modelId: modelId,
            kind: .modelApi,
            adapterId: GenericOpenAiBrainAdapter.adapterId,
            label: label,
            baseUrl: baseUrl,
            model: model,
            authRef: authRef,
            extra: extra
        )
        configStore.saveConfig(config)
        reload()
        onChange()
    }

    public func saveConfig(_ config: ModelConfig) {
        configStore.saveConfig(config)
        reload()
        onChange()
    }

    public func deleteConfig(modelId: String) {
        if let config = configStore.getConfig(modelId: modelId), let authRef = config.authRef {
            // 密钥引用计数：仅当 brainStore、modelStore、AgentStore 三处均不再引用该 authRef 时，才清理 Keychain 密钥；任一处仍引用则保留
            let otherModelRefs = configStore.listConfigs().contains { $0.modelId != modelId && $0.authRef == authRef }
            let brainRefs = brainConfigStore?.listConfigs().contains { $0.authRef == authRef } ?? false
            let agentRefs = (try? agentRegistryStore?.listAgents())?.contains { $0.authRef == authRef } ?? false

            if !otherModelRefs && !brainRefs && !agentRefs {
                keyStore.deleteSecret(authRef: authRef)
            }
        }
        configStore.deleteConfig(modelId: modelId)
        configStore.markModelDeleted(modelId: modelId)
        reload()
        onChange()
    }

    public func setCurrent(modelId: String?) {
        if let modelId, let config = configStore.getConfig(modelId: modelId),
           unusableReason(for: config) != nil {
            return
        }
        configStore.setCurrentModelId(modelId)
        reload()
        onChange()
    }

    public func unusableReason(for config: ModelConfig) -> String? {
        if registryProvider().get(config.adapterId) == nil {
            return config.kind == .subscription
                ? "会员登录在 iOS 上尚未接入，不能作为当前模型"
                : "此模型的接入模块未加载"
        }
        if config.kind != .subscription && !hasKey(for: config) {
            return "尚未填写 API Key"
        }
        return nil
    }

    // MARK: - 模型分组（对齐三分组：模型 API / CLI 会员 / 订阅授权，本地模型并入模型 API）

    public var modelApis: [ModelConfig] {
        configs.filter { $0.kind == .modelApi }
    }

    public var localModels: [ModelConfig] {
        configs.filter { $0.kind == .modelApi && BrainConfigValidator.isLocalModelUrl($0.baseUrl ?? "") }
    }

    public var cliBrains: [ModelConfig] {
        configs.filter { $0.kind == .agentRuntime }
    }

    public var subscriptions: [ModelConfig] {
        configs.filter { $0.kind == .subscription }
    }
}

// MARK: - 5. 运行时只读合并视图 CompositeBrainConfigStore

public final class CompositeBrainConfigStore: BrainConfigStore, @unchecked Sendable {
    public let modelStore: ModelConfigStore
    public let brainStore: BrainConfigStore

    public init(modelStore: ModelConfigStore, brainStore: BrainConfigStore) {
        self.modelStore = modelStore
        self.brainStore = brainStore
    }

    public func listConfigs() -> [BrainConfig] {
        let models = modelStore.listConfigs().map { $0.toBrainConfig() }
        let modelIds = Set(models.map(\.brainId))
        let brains = brainStore.listConfigs().filter { !modelIds.contains($0.brainId) }
        return models + brains
    }

    public func getConfig(brainId: String) -> BrainConfig? {
        if let m = modelStore.getConfig(modelId: brainId) {
            return m.toBrainConfig()
        }
        return brainStore.getConfig(brainId: brainId)
    }

    public func currentBrainId() -> String? {
        modelStore.currentModelId() ?? brainStore.currentBrainId()
    }

    // 所有写操作与墓碑方法一律透传 brainStore
    public func saveConfig(_ config: BrainConfig) {
        brainStore.saveConfig(config)
    }

    public func deleteConfig(brainId: String) {
        brainStore.deleteConfig(brainId: brainId)
    }

    public func setCurrentBrainId(_ brainId: String?) {
        brainStore.setCurrentBrainId(brainId)
    }

    public func deletedBrainIds() -> Set<String> {
        brainStore.deletedBrainIds()
    }

    public func markBrainDeleted(brainId: String) {
        brainStore.markBrainDeleted(brainId: brainId)
    }

    public func clearBrainDeleted(brainId: String) {
        brainStore.clearBrainDeleted(brainId: brainId)
    }
}

// MARK: - 6. 模型迁移管理器 ModelMigrationManager

public final class ModelMigrationManager {
    public static let keyMigrationV1Done = "model.migration_v1_done"

    public static func runStartupMigration(
        kv: KeyValueStore,
        brainStore: BrainConfigStore,
        modelStore: ModelConfigStore
    ) {
        let isDone = (try? kv.get(keyMigrationV1Done)) == "true"
        let brainConfigs = brainStore.listConfigs()

        // 属于模型页三分组：MODEL_API, AGENT_RUNTIME, SUBSCRIPTION
        let targetGroupBrains = brainConfigs.filter { cfg in
            cfg.kind == .modelApi || cfg.kind == .agentRuntime || cfg.kind == .subscription
        }
        let targetIds = Set(targetGroupBrains.map(\.brainId))

        if !isDone {
            // 一次性状态迁移（受 model.migration_v1_done 保护，仅 false 时执行）

            // 1. 同步已删除内置模型墓碑
            let brainDeleted = brainStore.deletedBrainIds()
            for deletedId in brainDeleted {
                modelStore.markModelDeleted(modelId: deletedId)
            }

            // 2. 复制配置进 modelStore，同时 fallbackModelIds 过滤到被迁集合内
            let existingModelIds = Set(modelStore.listConfigs().map(\.modelId))
            let deletedTombstones = modelStore.deletedModelIds()

            for brain in targetGroupBrains {
                if !existingModelIds.contains(brain.brainId) && !deletedTombstones.contains(brain.brainId) {
                    var m = ModelConfig.fromBrainConfig(brain)
                    m.fallbackModelIds = m.fallbackModelIds.filter { targetIds.contains($0) }
                    modelStore.saveConfig(m)
                }
            }

            // 3. 同步当前选中项（仅当它在被迁集合内）
            if let curBrain = brainStore.currentBrainId(), targetIds.contains(curBrain) {
                if modelStore.currentModelId() == nil {
                    modelStore.setCurrentModelId(curBrain)
                }
            }

            // 4. 崩溃安全顺序：先落盘配置数据与当前项，最后才写 model.migration_v1_done = true
            try? kv.set(keyMigrationV1Done, "true")
        } else {
            // 每次启动幂等按 ID 补齐：不受一次性标记锁死
            let existingModelIds = Set(modelStore.listConfigs().map(\.modelId))
            let deletedTombstones = modelStore.deletedModelIds()

            for brain in targetGroupBrains {
                if !existingModelIds.contains(brain.brainId) && !deletedTombstones.contains(brain.brainId) {
                    let m = ModelConfig.fromBrainConfig(brain)
                    modelStore.saveConfig(m)
                }
            }
        }
    }
}
