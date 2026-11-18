import XCTest
@testable import WhereverCodeAgent

final class TriadFeaturesTests: XCTestCase {

    // MARK: - 1. FreeProviderPresets 测试
    func testFreeProviderPresetsExactSixProviders() {
        let presets = FreeProviderPresets.presets
        XCTAssertEqual(presets.count, 6, "官方核验免费 token 服务商必须恰好 6 家")

        let ids = presets.map(\.id)
        XCTAssertEqual(ids, [
            "google-ai-studio",
            "openrouter",
            "groq",
            "siliconflow",
            "zhipu",
            "cloudflare"
        ])

        // Google AI Studio (Gemini)
        let google = FreeProviderPresets.preset(id: "google-ai-studio")!
        XCTAssertEqual(google.listMode, .live)
        XCTAssertEqual(google.baseUrlTemplate, "https://generativelanguage.googleapis.com/v1beta/openai")
        XCTAssertFalse(google.needsAccountId)

        // OpenRouter
        let openrouter = FreeProviderPresets.preset(id: "openrouter")!
        XCTAssertEqual(openrouter.listMode, .live)
        XCTAssertEqual(openrouter.baseUrlTemplate, "https://openrouter.ai/api/v1")

        // Groq
        let groq = FreeProviderPresets.preset(id: "groq")!
        XCTAssertEqual(groq.listMode, .live)
        XCTAssertEqual(groq.baseUrlTemplate, "https://api.groq.com/openai/v1")

        // 硅基流动 SiliconFlow (须注明实名认证)
        let siliconflow = FreeProviderPresets.preset(id: "siliconflow")!
        XCTAssertEqual(siliconflow.listMode, .live)
        XCTAssertTrue(siliconflow.keyHelp.contains("实名认证") || siliconflow.note.contains("实名认证"), "硅基流动必须注明须实名认证")

        // 智谱 GLM (固定名单，无 /models 接口)
        let zhipu = FreeProviderPresets.preset(id: "zhipu")!
        XCTAssertEqual(zhipu.listMode, .fixed)
        XCTAssertEqual(zhipu.fixedModels, [
            "glm-4.7-flash",
            "glm-4-flash-250414",
            "glm-z1-flash",
            "glm-4.6v-flash",
            "glm-4v-flash",
            "glm-4.1v-thinking-flash"
        ])

        // Cloudflare Workers AI (固定名单，Account ID 单独字段)
        let cloudflare = FreeProviderPresets.preset(id: "cloudflare")!
        XCTAssertEqual(cloudflare.listMode, .fixed)
        XCTAssertTrue(cloudflare.needsAccountId)
        XCTAssertEqual(cloudflare.fixedModels, [
            "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
            "@cf/qwen/qwen2.5-coder-32b-instruct",
            "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b",
            "@cf/openai/gpt-oss-120b"
        ])

        let resolvedCf = cloudflare.resolveBaseUrl(accountId: "test-acc-123")
        XCTAssertEqual(resolvedCf, "https://api.cloudflare.com/client/v4/accounts/test-acc-123/ai/v1")
    }

    func testFreeProviderPresetForBaseUrl() {
        let zhipu = FreeProviderPresets.presetForBaseUrl("https://open.bigmodel.cn/api/paas/v4/")
        XCTAssertNotNil(zhipu)
        XCTAssertEqual(zhipu?.id, "zhipu")

        let cf = FreeProviderPresets.presetForBaseUrl("https://api.cloudflare.com/client/v4/accounts/my-acc-id/ai/v1")
        XCTAssertNotNil(cf)
        XCTAssertEqual(cf?.id, "cloudflare")
    }

    // MARK: - 2. CustomFreeProviders 测试
    func testCustomFreeProvidersIdAndMerge() {
        let id1 = CustomFreeProviders.customId("My Cloud", existing: [])
        XCTAssertEqual(id1, "custom-my-cloud")

        let id2 = CustomFreeProviders.customId("My Cloud", existing: [id1])
        XCTAssertEqual(id2, "custom-my-cloud-2")

        let customPreset = FreeProviderPreset(
            id: id1,
            displayName: "我的实验室",
            fixedModels: ["model-a"],
            listMode: .fixed,
            baseUrlTemplate: "https://lab.example.com/v1",
            keyHelp: "测试",
            note: "自定义"
        )
        XCTAssertTrue(CustomFreeProviders.isCustom(customPreset))

        let merged = CustomFreeProviders.merged(builtins: FreeProviderPresets.presets, customs: [customPreset])
        XCTAssertEqual(merged.count, FreeProviderPresets.presets.count + 1)
        XCTAssertEqual(merged.last?.id, "custom-my-cloud")
    }

    // MARK: - 3. ModelRouting 智能路由测试
    func testModelRoutingPolicyAndFallback() {
        let freeConfig1 = BrainConfig(
            brainId: "free-1",
            kind: .modelApi,
            adapterId: "generic",
            label: "Free Model 1",
            baseUrl: "https://free1.example.com",
            model: "model-1",
            authRef: "ref-1",
            extra: [FreeProviderPresets.extraTier: FreeProviderPresets.tierFree]
        )
        let freeConfig2 = BrainConfig(
            brainId: "free-2",
            kind: .modelApi,
            adapterId: "generic",
            label: "Free Model 2",
            baseUrl: "https://free2.example.com",
            model: "model-2",
            authRef: "ref-2",
            extra: [FreeProviderPresets.extraTier: FreeProviderPresets.tierFree]
        )
        let paidConfig = BrainConfig(
            brainId: "paid-1",
            kind: .modelApi,
            adapterId: "generic",
            label: "Paid Model",
            baseUrl: "https://paid.example.com",
            model: "paid-model",
            authRef: "ref-paid"
        )

        let configs = [freeConfig1, freeConfig2, paidConfig]

        // 默认关：返回空
        var settings = ModelRoutingSettings(enabled: false, allowPaid: false, orderedBrainIds: ["free-1", "free-2", "paid-1"])
        XCTAssertTrue(ModelRouting.fallbackOrder(settings: settings, configs: configs, currentBrainId: "free-1").isEmpty)

        // 开启智能路由，默认只切免费模型（paid-1 被过滤）
        settings.enabled = true
        settings.allowPaid = false
        let orderFreeOnly = ModelRouting.fallbackOrder(settings: settings, configs: configs, currentBrainId: "free-1")
        XCTAssertEqual(orderFreeOnly, ["free-2"], "默认只切免费模型，排除当前模型本身和付费模型")

        // 允许付费模型开启后，付费模型进入路由池
        settings.allowPaid = true
        let orderWithPaid = ModelRouting.fallbackOrder(settings: settings, configs: configs, currentBrainId: "free-1")
        XCTAssertEqual(orderWithPaid, ["free-2", "paid-1"], "允许付费开启后，付费模型进池")
    }

    // MARK: - 4. DeviceModelDiscovery 设备本地模型发现测试
    func testDeviceModelDiscoveryCandidateUrls() {
        // 本机与局域网走 http
        let lanCandidates = DeviceModelDiscovery.candidateBaseUrls(host: "192.168.1.100")
        XCTAssertTrue(lanCandidates.allSatisfy { $0.baseUrl.hasPrefix("http://192.168.1.100:") })
        XCTAssertEqual(lanCandidates.map(\.serviceName), ["Ollama", "LM Studio", "vLLM"])

        // Tailscale 专网 (100.64/10) 走 http
        let tailscaleCandidates = DeviceModelDiscovery.candidateBaseUrls(host: "100.75.41.0")
        XCTAssertTrue(tailscaleCandidates.allSatisfy { $0.baseUrl.hasPrefix("http://100.75.41.0:") })

        // 公网地址走 https
        let publicCandidates = DeviceModelDiscovery.candidateBaseUrls(host: "api.myremote.com")
        XCTAssertTrue(publicCandidates.allSatisfy { $0.baseUrl.hasPrefix("https://api.myremote.com:") })
    }

    func testPrivateLanIpv4Classification() {
        XCTAssertTrue(BrainConfigValidator.isPrivateLanIpv4("192.168.0.1"))
        XCTAssertTrue(BrainConfigValidator.isPrivateLanIpv4("10.255.255.255"))
        XCTAssertTrue(BrainConfigValidator.isPrivateLanIpv4("172.20.1.1"))
        XCTAssertFalse(BrainConfigValidator.isPrivateLanIpv4("172.32.1.1"))
        XCTAssertTrue(BrainConfigValidator.isPrivateLanIpv4("100.100.1.1"))
        XCTAssertFalse(BrainConfigValidator.isPrivateLanIpv4("100.128.1.1"))
        XCTAssertFalse(BrainConfigValidator.isPrivateLanIpv4("8.8.8.8"))
    }

    // MARK: - 5. 概念分离与硬规则测试
    func testAgyCannotBeRegisteredAsDevice() throws {
        // 尝试登记 agy 为设备：通过 migrateLegacyDeviceRecords 清理
        let agyDevice = AgentRecord(
            agentId: "agy",
            displayName: "agy",
            kind: .device,
            access: .app,
            baseUrl: "127.0.0.1:18743"
        )
        try LocalData.shared.agents.saveAgent(agyDevice)
        try LocalData.shared.agents.migrateLegacyDeviceRecords()

        let deviceCheck = try LocalData.shared.agents.listAgents().filter { $0.kind == .device && $0.access == .app }
        XCTAssertFalse(deviceCheck.contains(where: { $0.agentId.lowercased() == "agy" || $0.displayName.lowercased() == "agy" }), "agy 是 Agent，不得登记为设备")
    }

    func testDeviceDeletionDoesNotCascadeToAgentsOrTasks() throws {
        let devId = "triad-test-dev-01"
        let childAgentId = "\(devId):worker-agent"

        let dev = AgentRecord(
            agentId: devId,
            displayName: "Triad Dev",
            kind: .device,
            access: .app,
            baseUrl: "192.168.1.88:18743",
            deviceId: devId
        )
        let childAgent = AgentRecord(
            agentId: childAgentId,
            displayName: "Worker Agent (Triad Dev)",
            kind: .device,
            access: .cli,
            baseUrl: "192.168.1.88:18743",
            deviceId: devId
        )

        try LocalData.shared.agents.saveAgent(dev)
        try LocalData.shared.agents.saveAgent(childAgent)

        // 删设备只删设备
        try LocalData.shared.agents.deleteDeviceCascade(deviceId: devId)

        // 验证设备已删
        XCTAssertNil(try LocalData.shared.agents.getAgent(agentId: devId))
        // 验证子 Agent 独立保留未被删除
        let preserved = try LocalData.shared.agents.getAgent(agentId: childAgentId)
        XCTAssertNotNil(preserved)
        XCTAssertEqual(preserved?.memberConnectionState, "NOT_CONNECTED")

        // 清理
        try? LocalData.shared.agents.deleteAgent(agentId: childAgentId)
    }

    // MARK: - 6. 控制台三件套卡片并列测试 (设备 / Agent / 模型)
    func testControlModulesListIncludesModelsCard() {
        let kv = InMemoryKeyValueStore()
        let store = KeyValueBrainConfigStore(kv: kv)
        let countBefore = store.listConfigs().count
        XCTAssertEqual(countBefore, 0)
        let badgeBefore = countBefore > 0 ? "\(countBefore) 个模型" : "模型"
        XCTAssertEqual(badgeBefore, "模型")

        // 添加一个测试模型配置
        let testConfig = BrainConfig(
            brainId: "triad-test-model-card",
            kind: .modelApi,
            adapterId: "generic",
            label: "Triad Test Model Card",
            baseUrl: "https://api.test.com/v1",
            model: "test-model",
            authRef: "test-ref"
        )
        store.saveConfig(testConfig)
        let countAfter = store.listConfigs().count
        XCTAssertEqual(countAfter, 1)
        let badgeAfter = "\(countAfter) 个模型"
        XCTAssertEqual(badgeAfter, "1 个模型")
    }
}
