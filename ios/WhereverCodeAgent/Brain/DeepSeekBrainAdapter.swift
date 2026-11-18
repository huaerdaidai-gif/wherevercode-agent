import Foundation

/// Storage for the DeepSeek API key (Kotlin
/// `DeepSeekCredentialStore`). The key is entered by the user on the
/// device and stored only in the Keychain-backed [BrainKeyStore]
/// (service `wherevercode_brain_keys`). It is never in
/// `BrainConfig`, never in logs, never in the app bundle.
public protocol DeepSeekCredentialStore {
    func saveApiKey(_ apiKey: String) throws
    func loadApiKey() -> String?
    func clearApiKey()
}

public final class KeystoreDeepSeekCredentialStore: DeepSeekCredentialStore {
    public static let authRef = "deepseek:api-key"

    private let keyStore: BrainKeyStore

    public init(keyStore: BrainKeyStore = BrainKeyStore()) {
        self.keyStore = keyStore
    }

    public func saveApiKey(_ apiKey: String) throws {
        if apiKey.trimmingCharacters(in: .whitespaces).isEmpty {
            throw BrainErrorInfo(
                code: "invalid_key", message: "DeepSeek API Key 不能为空", retryable: false)
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

/// DeepSeek API brain (OpenAI-compatible). Ported from Android
/// `DeepSeekBrainAdapter.kt` on main @4496658e.
///
/// A direct [BrainAdapter]: there is exactly one wire protocol
/// (`/chat/completions`), and the engine-facing contract is the only
/// one that needs vendor-specific mapping. Configuration comes from
/// the caller (bootstrap reads it from [BrainConfig]):
/// - baseUrl: user-editable, default `https://api.deepseek.com`;
/// - model: user-editable, populated from the live `/models` list;
/// - API key: only in [DeepSeekCredentialStore] (Keychain).
///
/// No key is ever hardcoded, and no model is silently substituted
/// when the configured one is blank.
public final class DeepSeekBrainAdapter: BrainAdapter {
    public static let adapterId = "deepseek"
    public static let brainId = "deepseek"
    public static let authRef = "deepseek:api-key"
    public static let defaultBaseUrl = DeepSeekApiClient.defaultBaseUrl

    private let credentialStore: DeepSeekCredentialStore
    private let baseUrlProvider: () -> String?
    private let configuredModelProvider: () -> String?
    private let apiClient: DeepSeekApiClient

    public init(
        credentialStore: DeepSeekCredentialStore,
        baseUrlProvider: @escaping () -> String? = { DeepSeekApiClient.defaultBaseUrl },
        configuredModelProvider: @escaping () -> String? = { nil },
        apiClient: DeepSeekApiClient = DeepSeekApiClient()
    ) {
        self.credentialStore = credentialStore
        self.baseUrlProvider = baseUrlProvider
        self.configuredModelProvider = configuredModelProvider
        self.apiClient = apiClient
    }

    public var adapterId: String { Self.adapterId }
    public var kind: BrainKind { .modelApi }
    public var label: String { "DeepSeek API" }

    public func capabilities() -> BrainCapabilities {
        BrainCapabilities(
            streaming: true,
            toolCalling: true,
            vision: false,
            maxContextTokens: nil,
            notes: "OpenAI 兼容 /chat/completions；工具调用使用标准 tool_calls/tool_call_id 配对。" +
                "模型从账户的 /models 列表选择，不写死；Key 只存本机 Keystore。")
    }

    public func validateCredentials() async -> Result<Void, Error> {
        guard credentialStore.loadApiKey() != nil else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 DeepSeek API Key。", retryable: false))
        }
        guard let baseUrl = Self.nonBlank(baseUrlProvider()) else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 DeepSeek 服务地址。", retryable: false))
        }
        switch await apiClient.listModels(apiKey: credentialStore.loadApiKey() ?? "", baseUrl: baseUrl) {
        case .success: return .success(())
        case .failure(let error): return .failure(error)
        }
    }

    public func listModels() async -> Result<[ModelInfo], Error> {
        guard let apiKey = credentialStore.loadApiKey() else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 DeepSeek API Key。", retryable: false))
        }
        guard let baseUrl = Self.nonBlank(baseUrlProvider()) else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 DeepSeek 服务地址。", retryable: false))
        }
        switch await apiClient.listModels(apiKey: apiKey, baseUrl: baseUrl) {
        case .success(let models): return .success(models)
        case .failure(let error): return .failure(error)
        }
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
                        message: "未配置 DeepSeek API Key：请在控制台的 DeepSeek 设置里填写你自己的 Key。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                guard let baseUrl = Self.nonBlank(baseUrlProvider()) else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未配置 DeepSeek 服务地址：请在设置里填写 baseUrl。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                let model = Self.nonBlank(request.model) ?? Self.nonBlank(configuredModelProvider())
                guard let model else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未选择 DeepSeek 模型：请先刷新模型列表并选择一个。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                var effectiveRequest = request
                effectiveRequest.model = model
                for await event in apiClient.streamChat(
                    request: effectiveRequest, apiKey: apiKey, baseUrl: baseUrl) {
                    switch event {
                    case .text(let text):
                        continuation.yield(.textDelta(text))
                    case .reasoning(let text):
                        continuation.yield(.reasoningDelta(text))
                    case .toolCall(let id, let name, let argumentsJson):
                        if let args = Self.parseToolArgs(argumentsJson) {
                            continuation.yield(.toolCall(BrainToolCall(id: id, name: name, args: args)))
                        } else {
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
                        continuation.yield(.error(code: code, message: message, retryable: retryable))
                        continuation.yield(.done(finishReason: "error"))
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Clear the locally stored API key (the user can also delete
    /// it server-side).
    public func revoke() {
        credentialStore.clearApiKey()
    }

    /// nil = malformed JSON; an empty object is valid.
    static func parseToolArgs(_ argumentsJson: String) -> [String: JSONValue]? {
        let raw = argumentsJson.trimmingCharacters(in: .whitespaces)
        if raw.isEmpty { return [:] }
        return JSONValue.parseObject(raw)
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return value
    }
}
