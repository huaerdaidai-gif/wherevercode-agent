// ModelDecoupleTests.swift
// WhereverCode Agent — iOS 模型页与大脑脱钩单元测试
// 依据 Funk 2026-11-19 指示与 ~/agy-ios-model-decouple-brief.md：
// 1. 验证模型独立存储键 "model.configs_json" 与 "brain.configs_json" 完全隔离；
// 2. 验证模型配置独立增删改查与分组（模型 API、本地模型、CLI 会员、订阅授权）；
// 3. 验证大脑存储与模型存储互不影响、双向零污染。

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
            adapterId: "generic",
            label: "Test GPT-4o",
            baseUrl: "https://api.openai.com/v1",
            model: "gpt-4o",
            authRef: "model:test-model-01:key"
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

    // MARK: - 2. 模型增删改查生命周期测试

    func testModelConfigCrudLifecycle() throws {
        let data = try LocalData.inMemory()
        let modelStore = KeyValueModelConfigStore(kv: data.kv)

        // 1. 添加
        let config1 = ModelConfig(
            modelId: "m1",
            kind: .modelApi,
            adapterId: "generic",
            label: "Claude 3.5 Sonnet",
            baseUrl: "https://api.anthropic.com/v1",
            model: "claude-3-5-sonnet",
            authRef: "model:m1:key",
            extra: ["tier": "free"]
        )
        modelStore.saveConfig(config1)
        XCTAssertEqual(modelStore.listConfigs().count, 1)
        XCTAssertEqual(modelStore.getConfig(modelId: "m1")?.label, "Claude 3.5 Sonnet")

        // 2. 当前模型切换
        modelStore.setCurrentModelId("m1")
        XCTAssertEqual(modelStore.currentModelId(), "m1")
        XCTAssertEqual(try data.kv.get(KeyValueModelConfigStore.keyCurrent), "m1")

        // 3. 修改
        var updated = config1
        updated.label = "Claude 3.5 Sonnet (Updated)"
        modelStore.saveConfig(updated)
        XCTAssertEqual(modelStore.listConfigs().count, 1)
        XCTAssertEqual(modelStore.getConfig(modelId: "m1")?.label, "Claude 3.5 Sonnet (Updated)")

        // 4. 删除
        modelStore.deleteConfig(modelId: "m1")
        XCTAssertEqual(modelStore.listConfigs().count, 0)
        XCTAssertNil(modelStore.getConfig(modelId: "m1"))
        XCTAssertNil(modelStore.currentModelId(), "删除当前模型后，当前模型标识自动置空")
    }

    // MARK: - 3. 模型列表四分组与本地模型判定测试

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
            adapterId: "generic",
            label: "OpenRouter",
            baseUrl: "https://openrouter.ai/api/v1",
            model: "meta-llama/llama-3"
        )
        // (b) 本地模型 (127.0.0.1 / localhost)
        let localModel = ModelConfig(
            modelId: "local-1",
            kind: .modelApi,
            adapterId: "generic",
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
        XCTAssertEqual(model.modelApis.count, 1)
        XCTAssertEqual(model.modelApis.first?.modelId, "cloud-1")
        XCTAssertEqual(model.localModels.count, 1)
        XCTAssertEqual(model.localModels.first?.modelId, "local-1")
        XCTAssertEqual(model.cliBrains.count, 1)
        XCTAssertEqual(model.cliBrains.first?.modelId, "cli-1")
        XCTAssertEqual(model.subscriptions.count, 1)
        XCTAssertEqual(model.subscriptions.first?.modelId, "sub-1")
    }
}
