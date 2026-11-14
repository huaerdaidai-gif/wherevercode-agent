import Foundation

public final class StandardBrainAdapter: BrainAdapter {
    public let adapterId: String
    public let displayName: String
    public let capabilities: BrainCapabilities
    private let keyStore: BrainKeyStore

    public init(adapterId: String, displayName: String, capabilities: BrainCapabilities, keyStore: BrainKeyStore) {
        self.adapterId = adapterId
        self.displayName = displayName
        self.capabilities = capabilities
        self.keyStore = keyStore
    }

    public func listModels(config: BrainConfig) async throws -> [BrainModelDescriptor] {
        guard let authRef = config.authRef, let key = keyStore.loadKey(for: authRef), !key.isEmpty else {
            throw NSError(domain: "StandardBrainAdapter", code: 401, userInfo: [NSLocalizedDescriptionKey: "未配置 API Key：请先在设置里填写你自己的 Key（只存本机 Keychain）。"])
        }
        guard let base = config.baseUrl, let url = URL(string: base.hasSuffix("/") ? "\(base)models" : "\(base)/models") else {
            throw NSError(domain: "StandardBrainAdapter", code: 400, userInfo: [NSLocalizedDescriptionKey: "未配置服务地址：请先在设置里填写 baseUrl。"])
        }
        var req = URLRequest(url: url)
        req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw NSError(domain: "StandardBrainAdapter", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "模型列表请求失败（HTTP \(http.statusCode)）"])
        }
        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let list = obj["data"] as? [[String: Any]] else {
            return []
        }
        return list.compactMap { item in
            guard let id = item["id"] as? String else { return nil }
            return BrainModelDescriptor(id: id, displayName: id)
        }
    }

    public func streamInfer(config: BrainConfig, messages: [ConversationMessage], tools: [String]) -> AsyncThrowingStream<BrainStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            if config.adapterId == "siwc-m2" {
                continuation.finish(throwing: NSError(
                    domain: "SiwcM2BrainAdapter",
                    code: 401,
                    userInfo: [NSLocalizedDescriptionKey: "未登录 ChatGPT：请先使用 ChatGPT 登录，再发送消息。"]
                ))
                return
            }
            guard let authRef = config.authRef, let key = self.keyStore.loadKey(for: authRef), !key.isEmpty else {
                continuation.finish(throwing: NSError(
                    domain: "StandardBrainAdapter",
                    code: 401,
                    userInfo: [NSLocalizedDescriptionKey: "未配置 \(config.label) API Key：请先在设置里填写你自己的 Key（只存本机 Keychain）。"]
                ))
                return
            }
            guard let model = config.model, !model.isEmpty else {
                continuation.finish(throwing: NSError(
                    domain: "StandardBrainAdapter",
                    code: 400,
                    userInfo: [NSLocalizedDescriptionKey: "未选择 \(config.label) 模型：请先刷新模型列表并选择一个。"]
                ))
                return
            }
            Task {
                do {
                    guard let base = config.baseUrl,
                          let url = URL(string: base.hasSuffix("/") ? "\(base)chat/completions" : "\(base)/chat/completions") else {
                        throw NSError(domain: "StandardBrainAdapter", code: 400, userInfo: [NSLocalizedDescriptionKey: "未配置服务地址 baseUrl。"])
                    }
                    var req = URLRequest(url: url)
                    req.httpMethod = "POST"
                    req.setValue("application/json", forHTTPHeaderField: "Content-Type")
                    req.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
                    let payloadMessages = messages.map { ["role": $0.role, "content": $0.content] }
                    let body: [String: Any] = ["model": model, "messages": payloadMessages, "stream": false]
                    req.httpBody = try JSONSerialization.data(withJSONObject: body)
                    let (data, resp) = try await URLSession.shared.data(for: req)
                    if let http = resp as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                        throw NSError(domain: "StandardBrainAdapter", code: http.statusCode, userInfo: [NSLocalizedDescriptionKey: "\(config.label) 请求失败（HTTP \(http.statusCode)）"])
                    }
                    if let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                       let choices = obj["choices"] as? [[String: Any]],
                       let msg = choices.first?["message"] as? [String: Any],
                       let content = msg["content"] as? String {
                        continuation.yield(.textDelta(content))
                        continuation.yield(.done)
                        continuation.finish()
                    } else {
                        throw NSError(domain: "StandardBrainAdapter", code: 500, userInfo: [NSLocalizedDescriptionKey: "\(config.label) 返回了空回复（没有文字或工具调用）。已停止本轮。"])
                    }
                } catch {
                    continuation.finish(throwing: error)
                }
            }
        }
    }
}

public enum BrainBootstrap {
    public static let seededConfigs: [BrainConfig] = [
        BrainConfig(
            brainId: "deepseek",
            kind: .modelApi,
            adapterId: "model-api-deepseek",
            label: "DeepSeek",
            baseUrl: "https://api.deepseek.com",
            model: "deepseek-chat",
            authRef: "deepseek:api-key"
        ),
        BrainConfig(
            brainId: "claude",
            kind: .modelApi,
            adapterId: "model-api-claude",
            label: "Claude (Anthropic)",
            baseUrl: "https://api.anthropic.com",
            model: "claude-sonnet-4-5",
            authRef: "claude:api-key"
        ),
        BrainConfig(
            brainId: "grok",
            kind: .modelApi,
            adapterId: "model-api-grok",
            label: "Grok (xAI API)",
            baseUrl: "https://api.x.ai/v1",
            model: "grok-4.7",
            authRef: "grok:api-key"
        ),
        BrainConfig(
            brainId: "muse-spark",
            kind: .modelApi,
            adapterId: "model-api-muse",
            label: "Muse Spark (Meta API)",
            baseUrl: "https://api.meta.ai/v1",
            model: "muse-spark",
            authRef: "muse:api-key"
        ),
        BrainConfig(
            brainId: "cloudflare-workers-ai",
            kind: .modelApi,
            adapterId: "cloudflare-workers-ai",
            label: "Cloudflare Workers AI",
            baseUrl: "https://api.cloudflare.com/client/v4/accounts",
            model: "@cf/meta/llama-3.1-8b-instruct-fp8-fast",
            authRef: "cloudflare:token"
        ),
        BrainConfig(
            brainId: "chatgpt-siwc",
            kind: .subscription,
            adapterId: "siwc-m2",
            label: "ChatGPT (Sign in with ChatGPT)",
            baseUrl: nil,
            model: "gpt-5",
            authRef: nil,
            loginState: "ADAPTER_PENDING"
        )
    ]

    public static func buildRegistry(configStore: BrainConfigStore, keyStore: BrainKeyStore) -> BrainAdapterRegistry {
        let registry = BrainAdapterRegistry()
        let deleted = configStore.deletedBrainIds()
        let existingIds = Set(configStore.listConfigs().map(\.brainId))
        for seed in seededConfigs where !deleted.contains(seed.brainId) && !existingIds.contains(seed.brainId) {
            configStore.saveConfig(seed)
        }

        let adapters: [(String, String, String, Bool)] = [
            ("model-api-deepseek", "DeepSeek", "OpenAI 兼容 /chat/completions；工具调用使用标准 tool_calls/tool_call_id 配对。Key 只存本机 Keychain。", false),
            ("model-api-claude", "Claude", "Anthropic Messages API /v1/messages；工具调用使用原生 tool_use/tool_result 内容块。Key 只存本机 Keychain。", true),
            ("model-api-grok", "Grok", "xAI API /chat/completions（Grok 模型）；支持 vision 图片输入；Key 只存本机 Keychain。", true),
            ("model-api-muse", "Muse Spark", "Meta Model API /chat/completions（Muse Spark 模型）；Key 只存本机 Keychain。", false),
            ("cloudflare-workers-ai", "Cloudflare Workers AI", "免费层每天 10,000 Neurons，全账户共享；默认建议 @cf/meta/llama-3.1-8b-instruct-fp8-fast。", false),
            ("model-api-generic", "通用模型 API", "OpenAI 兼容 /chat/completions；支持 OpenAI、OpenRouter、Gemini、本地局域网 Ollama/LM Studio。", false),
            ("siwc-m2", "ChatGPT 会员大脑", "Sign in with ChatGPT 订阅额度适配器（登录接入开发中，不提供假登录按钮）。", true)
        ]

        for (id, name, note, vision) in adapters {
            registry.registerAdapter(StandardBrainAdapter(
                adapterId: id,
                displayName: name,
                capabilities: BrainCapabilities(streaming: true, toolCalling: true, vision: vision, notes: note),
                keyStore: keyStore
            ))
        }

        for cfg in configStore.listConfigs() {
            registry.registerConfig(cfg)
        }
        return registry
    }

    public static func restoreSeededBrain(configStore: BrainConfigStore, brainId: String) -> Bool {
        guard let seed = seededConfigs.first(where: { $0.brainId == brainId }) else { return false }
        configStore.clearBrainDeleted(brainId: brainId)
        configStore.saveConfig(seed)
        return true
    }
}
