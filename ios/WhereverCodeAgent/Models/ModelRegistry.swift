// ModelRegistry.swift
// WhereverCode Agent — 独立模型注册表与配置存储
// 依据 Funk 2026-11-19 指示与 ~/agy-ios-model-decouple-brief.md：
// - 与大脑（BrainConfigStore / brain.configs_json）彻底脱钩，建立模型独立存储；
// - 独立存储键：model.configs_json（配置列表）与 model.current_model_id（当前模型）；
// - 绝不读取、绝不写入 brain.configs_json；
// - 结构对齐独立模型配置管理，已添加模型列表增删改查全走本存储。

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
        adapterId: String = "generic",
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
}

// MARK: - 2. 模型配置 Store 协议

public protocol ModelConfigStore: AnyObject {
    func listConfigs() -> [ModelConfig]
    func getConfig(modelId: String) -> ModelConfig?
    func saveConfig(_ config: ModelConfig)
    func deleteConfig(modelId: String)
    func currentModelId() -> String?
    func setCurrentModelId(_ modelId: String?)
}

// MARK: - 3. 基于 KeyValueStore 的模型存储实现 (Key: model.configs_json)

public final class KeyValueModelConfigStore: ModelConfigStore, @unchecked Sendable {
    public static let keyConfigs = "model.configs_json"
    public static let keyCurrent = "model.current_model_id"

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

    public var onChange: () -> Void = {}

    public init(
        configStore: ModelConfigStore,
        keyStore: BrainKeyStore,
        routingStore: ModelRoutingStore = UserDefaultsModelRoutingStore(),
        customProviderStore: CustomFreeProviderStore = KeyValueCustomFreeProviderStore()
    ) {
        self.configStore = configStore
        self.keyStore = keyStore
        self.routingStore = routingStore
        self.customProviderStore = customProviderStore
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
        let authRef = "model:\(modelId):key"
        if !apiKey.isEmpty {
            try? keyStore.putSecret(authRef: authRef, value: apiKey)
        }
        let config = ModelConfig(
            modelId: modelId,
            kind: .modelApi,
            adapterId: "generic",
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
            keyStore.deleteSecret(authRef: authRef)
        }
        configStore.deleteConfig(modelId: modelId)
        reload()
        onChange()
    }

    // MARK: - 模型分组（对齐原 addedModelsSection 分组口径）

    public var modelApis: [ModelConfig] {
        configs.filter { $0.kind == .modelApi && !isLocalUrl($0.baseUrl) }
    }

    public var localModels: [ModelConfig] {
        configs.filter { $0.kind == .modelApi && isLocalUrl($0.baseUrl) }
    }

    public var cliBrains: [ModelConfig] {
        configs.filter { $0.kind == .agentRuntime }
    }

    public var subscriptions: [ModelConfig] {
        configs.filter { $0.kind == .subscription }
    }

    private func isLocalUrl(_ urlStr: String?) -> Bool {
        guard let urlStr = urlStr?.lowercased() else { return false }
        return urlStr.contains("localhost") || urlStr.contains("127.0.0.1") || urlStr.contains("192.168.") || urlStr.contains("10.") || urlStr.contains(".local")
    }
}
