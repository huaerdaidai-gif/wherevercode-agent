// ModelDecoupleTests.swift
// WhereverCode Agent — iOS 模型页与大脑脱钩单元测试
// 依据 Funk 2026-11-19 指示与 ~/awm-work/agy-ios-b3-brief.md：
// 1. 验证模型独立存储键 "model.configs_json" 与 "brain.configs_json" 完全隔离；
// 2. 验证模型配置独立增删改查生命周期、adapterId、authRef 规范与墓碑机制；
// 3. 验证模型三分组（模型 API、CLI 会员、订阅授权，本地模型并入模型 API）；
// 4. 验证 CompositeBrainConfigStore 运行时合并视图（读取合并、当前模型代理、写入与墓碑透传 brainStore）；
// 5. 验证 ModelMigrationManager 启动幂等按 ID 补齐、一次性同步与崩溃安全顺序；
// 6. 验证密钥引用计数：三处均不再引用时才清理 Keychain 密钥。

import XCTest
@testable import WhereverCodeAgent

@MainActor
final class ModelDecoupleTests: XCTestCase {

    // MARK: - 1. 独立存储键隔离测试（model.configs_json vs brain.configs_json）

    func testStorageKeysAreCompletelyIsolated() throws {
        let data = try LocalData.inMemory()
        let modelStore = KeyValueModelConfigStore(kv: data.kv)
        let brainStore = KeyValueBrainConfigStore(kv: data.kv)

        // 初始状态下两端皆为空
        XCTAssertEqual(modelStore.listConfigs().count, 0)
        XCTAssertEqual(brainStore.listConfigs().count, 0)
        XCTAssertNil(try data.kv.get(KeyValueModelConfigStore.keyConfigs))
        XCTAssertNil(try data.kv.get(KeyValueBrainConfigStore.keyConfigs))

        // 写入模型存储
        let testModel = ModelConfig(
            modelId: "test-model-01",
            kind: .modelApi,
            adapterId: GenericOpenAiBrainAdapter.adapterId,
            label: "Test GPT-4o",
            baseUrl: "https://api.openai.com/v1",
            model: "gpt-4o",
            authRef: "model-api:test-model-01"
        )
        modelStore.saveConfig(testModel)

        // 断言：模型存储已落盘，键名为 "model.configs_json"
        let modelRaw = try data.kv.get(KeyValueModelConfigStore.keyConfigs)
        XCTAssertNotNil(modelRaw)
        XCTAssertTrue(modelRaw?.contains("test-model-01") == true)
        XCTAssertEqual(modelStore.listConfigs().count, 1)

        // 断言：大脑存储键 "brain.configs_json" 仍然为 nil，零写入、零污染
        let brainRaw = try data.kv.get(KeyValueBrainConfigStore.keyConfigs)
        XCTAssertNil(brainRaw, "写入模型配置不得向 brain.configs_json 写入任何数据")
        XCTAssertEqual(brainStore.listConfigs().count, 0, "模型存储写入不得改变大脑配置列表")

        // 写入大脑存储
        let testBrain = BrainConfig(
            brainId: "test-brain-01",
            kind: .modelApi,
            adapterId: "deepseek",
            label: "Test DeepSeek Brain",
            baseUrl: "https://api.deepseek.com",
            model: "deepseek-chat",
            authRef: "brain:test-brain-01:key"
        )
        brainStore.saveConfig(testBrain)

        // 断言：大脑配置独立写入 "brain.configs_json"
        let brainRawAfter = try data.kv.get(KeyValueBrainConfigStore.keyConfigs)
        XCTAssertNotNil(brainRawAfter)
        XCTAssertTrue(brainRawAfter?.contains("test-brain-01") == true)
        XCTAssertEqual(brainStore.listConfigs().count, 1)

        // 断言：模型存储不受大脑写入影响
        XCTAssertEqual(modelStore.listConfigs().count, 1)
        XCTAssertEqual(modelStore.listConfigs().first?.modelId, "test-model-01")
    }

    // MARK: - 2. 模型增删改查生命周期与门禁测试 (adapterId, authRef, 墓碑)

    func testModelConfigCrudLifecycle() throws {
        let data = try LocalData.inMemory()
        let modelStore = KeyValueModelConfigStore(kv: data.kv)
        let keyStore = BrainKeyStore()
        let model = ModelSettingsModel(configStore: modelStore, keyStore: keyStore)

        // 1. 通过 ViewModel addModelConfig 添加（验证 adapterId 与 authRef 门禁）
        model.addModelConfig(
            label: "Custom GLM",
            baseUrl: "https://open.bigmodel.cn/api/paas/v4",
            model: "glm-4",
            apiKey: "dummy-key-glm"
        )
        let configs = modelStore.listConfigs()
        XCTAssertEqual(configs.count, 1)
        let added = try XCTUnwrap(configs.first)
        XCTAssertEqual(added.label, "Custom GLM")
        XCTAssertEqual(added.adapterId, GenericOpenAiBrainAdapter.adapterId, "adapterId 必须为 GenericOpenAiBrainAdapter.adapterId")
        XCTAssertEqual(added.authRef, "model-api:\(added.modelId)", "authRef 格式必须为 model-api:<modelId>")
        XCTAssertTrue(model.hasKey(for: added))

        // 2. 当前模型切换
        modelStore.setCurrentModelId(added.modelId)
        XCTAssertEqual(modelStore.currentModelId(), added.modelId)
        XCTAssertEqual(try data.kv.get(KeyValueModelConfigStore.keyCurrent), added.modelId)

        // 3. 修改
        var updated = added
        updated.label = "Custom GLM (Updated)"
        modelStore.saveConfig(updated)
        XCTAssertEqual(modelStore.listConfigs().count, 1)
        XCTAssertEqual(modelStore.getConfig(modelId: added.modelId)?.label, "Custom GLM (Updated)")

        // 4. 删除（验证墓碑写入与当前模型清空）
        model.deleteConfig(modelId: added.modelId)
        XCTAssertEqual(modelStore.listConfigs().count, 0)
        XCTAssertNil(modelStore.getConfig(modelId: added.modelId))
        XCTAssertNil(modelStore.currentModelId(), "删除当前模型后，当前模型标识自动置空")
        XCTAssertTrue(modelStore.deletedModelIds().contains(added.modelId), "删除模型后写入 model.deleted_builtin_ids_json 墓碑")
    }

    // MARK: - 3. 模型列表三分组与本地模型收口测试

    func testModelGroupingCategories() throws {
        let data = try LocalData.inMemory()
        let store = KeyValueModelConfigStore(kv: data.kv)
        let keyStore = BrainKeyStore()
        let model = ModelSettingsModel(configStore: store, keyStore: keyStore)

        // 添加 4 类不同模型配置
        // (a) 云端模型 API
        let cloudApi = ModelConfig(
            modelId: "cloud-1",
            kind: .modelApi,
            adapterId: GenericOpenAiBrainAdapter.adapterId,
            label: "OpenRouter",
            baseUrl: "https://openrouter.ai/api/v1",
            model: "meta-llama/llama-3"
        )
        // (b) 本地模型 (127.0.0.1 / localhost)
        let localModel = ModelConfig(
            modelId: "local-1",
            kind: .modelApi,
            adapterId: GenericOpenAiBrainAdapter.adapterId,
            label: "Ollama Local",
            baseUrl: "http://127.0.0.1:11434/v1",
            model: "llama3.2"
        )
        // (c) CLI 会员
        let cliModel = ModelConfig(
            modelId: "cli-1",
            kind: .agentRuntime,
            adapterId: "cli",
            label: "Local CLI Runtime",
            model: "cli-agent"
        )
        // (d) 订阅授权
        let subModel = ModelConfig(
            modelId: "sub-1",
            kind: .subscription,
            adapterId: "chatgpt",
            label: "ChatGPT Subscription",
            model: "chatgpt-4"
        )

        store.saveConfig(cloudApi)
        store.saveConfig(localModel)
        store.saveConfig(cliModel)
        store.saveConfig(subModel)
        model.reload()

        XCTAssertEqual(model.configs.count, 4)
        // 门禁 4：本地模型已并入「模型 API」组，modelApis 包含 2 个条目
        XCTAssertEqual(model.modelApis.count, 2)
        XCTAssertTrue(model.modelApis.contains { $0.modelId == "cloud-1" })
        XCTAssertTrue(model.modelApis.contains { $0.modelId == "local-1" })

        // 门禁 3：复用 BrainConfigValidator 判定本地模型
        XCTAssertEqual(model.localModels.count, 1)
        XCTAssertEqual(model.localModels.first?.modelId, "local-1")

        // 三分组其余两组
        XCTAssertEqual(model.cliBrains.count, 1)
        XCTAssertEqual(model.cliBrains.first?.modelId, "cli-1")
        XCTAssertEqual(model.subscriptions.count, 1)
        XCTAssertEqual(model.subscriptions.first?.modelId, "sub-1")
    }

    // MARK: - 4. CompositeBrainConfigStore 运行时合并视图测试

    func testCompositeBrainConfigStore() throws {
        let data = try LocalData.inMemory()
        let modelStore = KeyValueModelConfigStore(kv: data.kv)
        let brainStore = KeyValueBrainConfigStore(kv: data.kv)
        let composite = CompositeBrainConfigStore(modelStore: modelStore, brainStore: brainStore)

        // 1. brainStore 有 b1, b2；modelStore 有 b1 (重名), m1
        let brain1 = BrainConfig(
            brainId: "common-1",
            kind: .modelApi,
            adapterId: "deepseek",
            label: "DeepSeek (from BrainStore)",
            baseUrl: "https://api.deepseek.com",
            model: "deepseek-chat",
            authRef: "brain:common-1"
        )
        let brain2 = BrainConfig(
            brainId: "brain-only",
            kind: .modelApi,
            adapterId: "claude",
            label: "Claude (from BrainStore)",
            baseUrl: "https://api.anthropic.com",
            model: "claude-3-opus",
            authRef: "brain:only"
        )
        brainStore.saveConfig(brain1)
        brainStore.saveConfig(brain2)

        let model1 = ModelConfig(
            modelId: "common-1",
            kind: .modelApi,
            adapterId: GenericOpenAiBrainAdapter.adapterId,
            label: "DeepSeek (from ModelStore - OVERRIDDEN)",
            baseUrl: "https://api.deepseek.com/v1",
            model: "deepseek-reasoner",
            authRef: "model-api:common-1"
        )
        let model2 = ModelConfig(
            modelId: "model-only",
            kind: .modelApi,
            adapterId: GenericOpenAiBrainAdapter.adapterId,
            label: "Model Only",
            baseUrl: "https://api.openai.com",
            model: "gpt-4o",
            authRef: "model-api:only"
        )
        modelStore.saveConfig(model1)
        modelStore.saveConfig(model2)

        // 断言 listConfigs()：同 id 时模型存储优先，brainStore 独有项并入
        let list = composite.listConfigs()
        XCTAssertEqual(list.count, 3)
        let commonItem = try XCTUnwrap(list.first { $0.brainId == "common-1" })
        XCTAssertEqual(commonItem.label, "DeepSeek (from ModelStore - OVERRIDDEN)", "同 ID 时模型存储优先")
        XCTAssertTrue(list.contains { $0.brainId == "brain-only" })
        XCTAssertTrue(list.contains { $0.brainId == "model-only" })

        // 断言 getConfig(brainId:)
        XCTAssertEqual(composite.getConfig(brainId: "common-1")?.model, "deepseek-reasoner")
        XCTAssertEqual(composite.getConfig(brainId: "brain-only")?.model, "claude-3-opus")
        XCTAssertEqual(composite.getConfig(brainId: "model-only")?.model, "gpt-4o")

        // 断言 currentBrainId 代理：modelStore.currentModelId ?? brainStore.currentBrainId
        brainStore.setCurrentBrainId("brain-only")
        XCTAssertEqual(composite.currentBrainId(), "brain-only")
        modelStore.setCurrentModelId("model-only")
        XCTAssertEqual(composite.currentBrainId(), "model-only", "模型页设置当前项优先生效")

        // 断言所有写与墓碑透传 brainStore，不污染 modelStore
        let newBrain = BrainConfig(
            brainId: "new-brain-write",
            kind: .modelApi,
            adapterId: "generic",
            label: "New Brain",
            baseUrl: "https://example.com",
            model: "test-m",
            authRef: nil
        )
        composite.saveConfig(newBrain)
        XCTAssertNotNil(brainStore.getConfig(brainId: "new-brain-write"))
        XCTAssertNil(modelStore.getConfig(modelId: "new-brain-write"), "写操作不得污染 modelStore")

        composite.markBrainDeleted(brainId: "tombstone-test")
        XCTAssertTrue(brainStore.deletedBrainIds().contains("tombstone-test"))
        XCTAssertFalse(modelStore.deletedModelIds().contains("tombstone-test"), "墓碑操作不得污染 modelStore")
    }

    // MARK: - 5. 迁移测试（启动幂等按 ID 补齐 + 一次性状态迁移 + 崩溃安全）

    func testModelMigrationManagerLifecycle() throws {
        let data = try LocalData.inMemory()
        let brainStore = KeyValueBrainConfigStore(kv: data.kv)
        let modelStore = KeyValueModelConfigStore(kv: data.kv)

        // 构造存量 brainStore
        let b1 = BrainConfig(
            brainId: "builtin-deepseek",
            kind: .modelApi,
            adapterId: "deepseek",
            label: "DeepSeek R1",
            baseUrl: "https://api.deepseek.com",
            model: "deepseek-chat",
            authRef: "brain:builtin-deepseek",
            extra: ["preset": "official"],
            fallbackBrainIds: ["builtin-claude", "cloud-platform-agent"]
        )
        let b2 = BrainConfig(
            brainId: "builtin-claude",
            kind: .modelApi,
            adapterId: "claude",
            label: "Claude 3.5",
            baseUrl: "https://api.anthropic.com",
            model: "claude-3-5",
            authRef: "brain:builtin-claude"
        )
        let platformAgent = BrainConfig(
            brainId: "cloud-platform-agent",
            kind: .platformAgent,
            adapterId: "platform",
            label: "Platform Agent",
            baseUrl: nil,
            model: "platform-v1",
            authRef: nil
        )
        brainStore.saveConfig(b1)
        brainStore.saveConfig(b2)
        brainStore.saveConfig(platformAgent)
        brainStore.setCurrentBrainId("builtin-deepseek")
        brainStore.markBrainDeleted(brainId: "old-deleted-builtin")

        // 1. 首次启动迁移
        ModelMigrationManager.runStartupMigration(
            kv: data.kv,
            brainStore: brainStore,
            modelStore: modelStore
        )

        // 验证一次性迁移完成标记
        XCTAssertEqual(try data.kv.get(ModelMigrationManager.keyMigrationV1Done), "true")

        // 验证条目复制进 modelStore：平台 Agent 排除、保留原 ID 与 extra
        let migrated = modelStore.listConfigs()
        XCTAssertEqual(migrated.count, 2)
        XCTAssertTrue(migrated.contains { $0.modelId == "builtin-deepseek" })
        XCTAssertTrue(migrated.contains { $0.modelId == "builtin-claude" })
        XCTAssertFalse(migrated.contains { $0.modelId == "cloud-platform-agent" }, "必须排除平台 Agent")

        let migratedD1 = try XCTUnwrap(migrated.first { $0.modelId == "builtin-deepseek" })
        XCTAssertEqual(migratedD1.extra["preset"], "official")
        // fallbackModelIds 过滤到被迁集合内（排除 platformAgent）
        XCTAssertEqual(migratedD1.fallbackModelIds, ["builtin-claude"])

        // 验证当前选中项与墓碑同步
        XCTAssertEqual(modelStore.currentModelId(), "builtin-deepseek")
        XCTAssertTrue(modelStore.deletedModelIds().contains("old-deleted-builtin"))

        // 2. 模拟用户在模型页删除了 builtin-claude 并写入墓碑
        modelStore.deleteConfig(modelId: "builtin-claude")
        modelStore.markModelDeleted(modelId: "builtin-claude")
        XCTAssertEqual(modelStore.listConfigs().count, 1)

        // 3. 再次启动（幂等补齐）：不受一次性标记锁死，且绝不复活墓碑条目
        ModelMigrationManager.runStartupMigration(
            kv: data.kv,
            brainStore: brainStore,
            modelStore: modelStore
        )

        XCTAssertEqual(modelStore.listConfigs().count, 1, "已被删除并在墓碑的条目不得复活")
        XCTAssertNil(modelStore.getConfig(modelId: "builtin-claude"))
    }

    // MARK: - 6. 密钥引用计数测试

    func testKeyStoreReferenceCountingOnModelDelete() throws {
        let data = try LocalData.inMemory()
        let brainStore = KeyValueBrainConfigStore(kv: data.kv)
        let modelStore = KeyValueModelConfigStore(kv: data.kv)
        let keyStore = BrainKeyStore()
        let model = ModelSettingsModel(
            configStore: modelStore,
            keyStore: keyStore,
            brainConfigStore: brainStore,
            agentRegistryStore: data.agents
        )

        let sharedAuthRef = "shared-secret:test"
        try keyStore.putSecret(authRef: sharedAuthRef, value: "super-secret-key")
        XCTAssertNotNil(keyStore.getSecret(authRef: sharedAuthRef))

        // 场景 A：brainStore 仍引用 sharedAuthRef，删除 modelStore 条目时 Keychain 保留
        let brain = BrainConfig(
            brainId: "brain-sharing-key",
            kind: .modelApi,
            adapterId: "generic",
            label: "Brain Shared",
            baseUrl: "https://api.example.com",
            model: "m1",
            authRef: sharedAuthRef
        )
        brainStore.saveConfig(brain)

        let modelEntry = ModelConfig(
            modelId: "model-sharing-key",
            kind: .modelApi,
            adapterId: GenericOpenAiBrainAdapter.adapterId,
            label: "Model Shared",
            baseUrl: "https://api.example.com",
            model: "m1",
            authRef: sharedAuthRef
        )
        modelStore.saveConfig(modelEntry)

        model.deleteConfig(modelId: "model-sharing-key")
        XCTAssertNil(modelStore.getConfig(modelId: "model-sharing-key"))
        XCTAssertNotNil(keyStore.getSecret(authRef: sharedAuthRef), "brainStore 仍在使用，Keychain 凭据必须保留")

        // 场景 B：brainStore 清理，且无 Agent 引用，删除独有模型配置时清理 Keychain
        brainStore.deleteConfig(brainId: "brain-sharing-key")

        let soloAuthRef = "solo-secret:test"
        try keyStore.putSecret(authRef: soloAuthRef, value: "solo-key")
        let soloModel = ModelConfig(
            modelId: "solo-model",
            kind: .modelApi,
            adapterId: GenericOpenAiBrainAdapter.adapterId,
            label: "Solo",
            baseUrl: "https://api.example.com",
            model: "m2",
            authRef: soloAuthRef
        )
        modelStore.saveConfig(soloModel)
        XCTAssertNotNil(keyStore.getSecret(authRef: soloAuthRef))

        model.deleteConfig(modelId: "solo-model")
        XCTAssertNil(modelStore.getConfig(modelId: "solo-model"))
        XCTAssertNil(keyStore.getSecret(authRef: soloAuthRef), "三处均无引用时，Keychain 凭据必须安全释放")
    }

    // MARK: - 7. B3 收尾追加：独立路由与自定义服务商存储隔离测试

    func testIndependentRoutingAndCustomProviderStores() throws {
        let suiteName = "test-independent-stores-\(UUID().uuidString)"
        let userDefaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { userDefaults.removePersistentDomain(forName: suiteName) }

        // 1. 验证路由存储物理隔离与首次单向幂等复制
        let legacyRoutingKey = IndependentModelRoutingStore.keyLegacy
        let newRoutingKey = IndependentModelRoutingStore.keyStorage
        let legacyRoutingData = try JSONEncoder().encode(ModelRoutingSettings(enabled: true, allowPaid: false, orderedBrainIds: ["brain-1"]))
        userDefaults.set(legacyRoutingData, forKey: legacyRoutingKey)

        let routingStore = IndependentModelRoutingStore(userDefaults: userDefaults)
        let loaded = routingStore.load()
        XCTAssertTrue(loaded.enabled, "首次从旧键复制成功")
        XCTAssertEqual(loaded.orderedBrainIds, ["brain-1"])

        // 修改新存储
        routingStore.save(ModelRoutingSettings(enabled: false, allowPaid: true, orderedBrainIds: ["brain-2"]))
        XCTAssertFalse(routingStore.load().enabled)
        // 旧键保持不变
        let legacyAfter = userDefaults.data(forKey: legacyRoutingKey)
        XCTAssertEqual(legacyAfter, legacyRoutingData, "新键写操作绝不得污染旧键 wherevercode_model_routing")

        // 2. 验证自定义服务商存储物理隔离与首次单向幂等复制
        let legacyProviderKey = IndependentCustomFreeProviderStore.keyLegacy
        let newProviderKey = IndependentCustomFreeProviderStore.keyStorage
        let legacyPreset = FreeProviderPreset(
            id: "custom-legacy",
            name: "Legacy Custom",
            defaultBaseUrl: "https://legacy.example.com",
            defaultModel: "legacy-v1",
            apiKeyPlaceholder: "key",
            keyHelpUrl: "",
            signupGuideUrl: "",
            verifyUrl: "",
            catalogUrl: "",
            modelsUrl: "",
            recommendedModelHint: "",
            catalogHint: "",
            docUrl: "",
            catalogParserType: "static"
        )
        let legacyProviderData = try JSONEncoder().encode([legacyPreset])
        userDefaults.set(legacyProviderData, forKey: legacyProviderKey)

        let providerStore = IndependentCustomFreeProviderStore(userDefaults: userDefaults)
        let initialList = providerStore.list()
        XCTAssertEqual(initialList.count, 1)
        XCTAssertEqual(initialList.first?.id, "custom-legacy")

        // 新增新服务商
        let newPreset = FreeProviderPreset(
            id: "custom-new",
            name: "New Provider",
            defaultBaseUrl: "https://new.example.com",
            defaultModel: "new-v1",
            apiKeyPlaceholder: "key",
            keyHelpUrl: "",
            signupGuideUrl: "",
            verifyUrl: "",
            catalogUrl: "",
            modelsUrl: "",
            recommendedModelHint: "",
            catalogHint: "",
            docUrl: "",
            catalogParserType: "static"
        )
        providerStore.add(newPreset)
        XCTAssertEqual(providerStore.list().count, 2)
        // 旧键保持不变
        let legacyProviderAfter = userDefaults.data(forKey: legacyProviderKey)
        XCTAssertEqual(legacyProviderAfter, legacyProviderData, "新键增删绝不得污染旧键 wherevercode_custom_free_providers")
    }
}
