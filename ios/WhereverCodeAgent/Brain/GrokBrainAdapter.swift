import Foundation

/// Grok API key storage (user's own xAI key, entered on device,
/// Keychain only).
public protocol GrokCredentialStore {
    func saveApiKey(_ apiKey: String) throws
    func loadApiKey() -> String?
    func clearApiKey()
}

public final class KeystoreGrokCredentialStore: GrokCredentialStore {
    public static let authRef = "grok:api-key"

    private let keyStore: BrainKeyStore

    public init(keyStore: BrainKeyStore = BrainKeyStore()) {
        self.keyStore = keyStore
    }

    public func saveApiKey(_ apiKey: String) throws {
        if apiKey.trimmingCharacters(in: .whitespaces).isEmpty {
            throw BrainErrorInfo(
                code: "invalid_key", message: "Grok API Key 不能为空", retryable: false)
        }
        try keyStore.putSecret(authRef: Self.authRef, value: apiKey)
    }

    public func loadApiKey() -> String? {
        guard let value = keyStore.getSecret(authRef: Self.authRef),
              !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return value
    }

    public func clearApiKey() {
        keyStore.deleteSecret(authRef: Self.authRef)
    }
}

/// Grok brain (xAI API, OpenAI-compatible). Ported from Android
/// `GrokBrainAdapter.kt` on main @4496658e.
///
/// A first-class, configurable MODEL_API brain over the shared
/// [GenericOpenAiApiClient] — the only Grok-specific parts are the
/// endpoint, the suggested model and the credential store.
///
/// Honest scope: this reaches the Grok **model** (grok-4.7), not
/// the Grok Bot agent (phone App + cloud computer), which has no
/// public chat API — the capabilities note says so in the UI. The
/// xAI API is separately billed and does NOT share any Grok/X
/// subscription quota. Only the chat prompt is sent; no project
/// file or repository is ever sent anywhere by this adapter.
public final class GrokBrainAdapter: BrainAdapter {
    public static let adapterId = "model-api-grok"
    public static let brainId = "grok"
    public static let authRef = "grok:api-key"
    /// Official xAI API base URL. Fixed default, user-editable.
    public static let defaultBaseUrl = "https://api.x.ai/v1"
    /// Official flagship model. First-run suggestion only.
    public static let suggestedModel = "grok-4.7"

    private let credentialStore: GrokCredentialStore
    private let baseUrlProvider: () -> String?
    private let configuredModelProvider: () -> String?
    private let apiClient: GenericOpenAiApiClient

    public init(
        credentialStore: GrokCredentialStore,
        baseUrlProvider: @escaping () -> String? = { GrokBrainAdapter.defaultBaseUrl },
        configuredModelProvider: @escaping () -> String? = { nil },
        apiClient: GenericOpenAiApiClient = GenericOpenAiApiClient()
    ) {
        self.credentialStore = credentialStore
        self.baseUrlProvider = baseUrlProvider
        self.configuredModelProvider = configuredModelProvider
        self.apiClient = apiClient
    }

    public var adapterId: String { Self.adapterId }
    public var kind: BrainKind { .modelApi }
    public var label: String { "Grok" }

    public func capabilities() -> BrainCapabilities {
        BrainCapabilities(
            streaming: true,
            toolCalling: true,
            vision: true,
            maxContextTokens: nil,
            notes: "xAI API /chat/completions（Grok 模型）；" +
                "工具调用使用标准 tool_calls/tool_call_id 配对；模型从账户的 /models 列表选择，不写死；" +
                "支持图片输入（vision，image_url parts，JPEG/PNG 单张 20 MiB 以内）；" +
                "Key 只存本机 Keystore。注意：这是 Grok 模型，不是 Grok Bot 本人（手机 App + 云电脑的那个 Agent 没有公开聊天 API，本适配器够不着它；代码不许下到 Grok Bot）。")
    }

    public func validateCredentials() async -> Result<Void, Error> {
        guard let apiKey = credentialStore.loadApiKey() else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 Grok API Key。", retryable: false))
        }
        guard let baseUrl = Self.nonBlank(baseUrlProvider()) else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 Grok 服务地址。", retryable: false))
        }
        switch await apiClient.listModels(apiKey: apiKey, baseUrl: baseUrl) {
        case .success: return .success(())
        case .failure(let error): return .failure(error)
        }
    }

    public func listModels() async -> Result<[ModelInfo], Error> {
        guard let apiKey = credentialStore.loadApiKey() else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 Grok API Key。", retryable: false))
        }
        guard let baseUrl = Self.nonBlank(baseUrlProvider()) else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 Grok 服务地址。", retryable: false))
        }
        let result = await apiClient.listModels(apiKey: apiKey, baseUrl: baseUrl)
        return result.mapError { $0 as Error }
    }

    public func chatStream(request: ChatRequest) -> AsyncThrowingStream<BrainEvent, Error> {
        let credentialStore = self.credentialStore
        let baseUrlProvider = self.baseUrlProvider
        let configuredModelProvider = self.configuredModelProvider
        let apiClient = self.apiClient
        return AsyncThrowingStream { continuation in
            let task = Task {
                guard let apiKey = credentialStore.loadApiKey(),
                      !apiKey.trimmingCharacters(in: .whitespaces).isEmpty else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未配置 Grok API Key：请在设置里填写你自己的 xAI API Key（只存本机 Keystore，绝不发给任何人）。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                guard let baseUrl = Self.nonBlank(baseUrlProvider()) else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未配置 Grok 服务地址：请在设置里填写 baseUrl。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                let model = Self.nonBlank(request.model) ?? Self.nonBlank(configuredModelProvider())
                guard let model else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未选择 Grok 模型：请先刷新模型列表并选择一个。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                var effectiveRequest = request
                effectiveRequest.model = model
                var failed = false
                do {
                    for try await event in apiClient.streamChat(
                        request: effectiveRequest, apiKey: apiKey, baseUrl: baseUrl) {
                        if failed { break }
                        switch event {
                        case .text(let text):
                            continuation.yield(.textDelta(text))
                        case .reasoning(let text):
                            continuation.yield(.reasoningDelta(text))
                        case .toolCall(let id, let name, let argumentsJson):
                            if let args = DeepSeekBrainAdapter.parseToolArgs(argumentsJson) {
                                continuation.yield(.toolCall(
                                    BrainToolCall(id: id, name: name, args: args)))
                            } else {
                                failed = true
                                continuation.yield(.error(
                                    code: "tool_arguments_invalid",
                                    message: "模型返回的工具参数不是合法 JSON（\(name)），已停止本轮。",
                                    retryable: false))
                                continuation.yield(.done(finishReason: "error"))
                            }
                        case .usage(let input, let output):
                            continuation.yield(.usage(inputTokens: input, outputTokens: output))
                        case .finished(let reason):
                            continuation.yield(.done(finishReason: reason))
                        case .failed(let code, let message, let retryable):
                            continuation.yield(.error(
                                code: code, message: message, retryable: retryable))
                            continuation.yield(.done(finishReason: "error"))
                        }
                    }
                } catch {
                    continuation.yield(.error(
                        code: "brain_call_failed",
                        message: "Grok 调用失败：\(error.localizedDescription)",
                        retryable: true))
                    continuation.yield(.done(finishReason: "error"))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Clear the locally stored API key.
    public func revoke() {
        credentialStore.clearApiKey()
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return value
    }
}
