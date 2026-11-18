import Foundation

/// Claude API key storage. The key is entered by the user on the
/// device and stored only in the Keychain-backed [BrainKeyStore].
/// It is never in BrainConfig, never in logs.
public protocol ClaudeCredentialStore {
    func saveApiKey(_ apiKey: String) throws
    func loadApiKey() -> String?
    func clearApiKey()
}

public final class KeystoreClaudeCredentialStore: ClaudeCredentialStore {
    /// The key's fixed authRef in BrainKeyStore.
    public static let authRef = "claude:api-key"

    private let keyStore: BrainKeyStore

    public init(keyStore: BrainKeyStore = BrainKeyStore()) {
        self.keyStore = keyStore
    }

    public func saveApiKey(_ apiKey: String) throws {
        if apiKey.trimmingCharacters(in: .whitespaces).isEmpty {
            throw BrainErrorInfo(
                code: "invalid_key", message: "Claude API Key 不能为空", retryable: false)
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

/// Claude API brain (Anthropic Messages API). Ported from Android
/// `ClaudeBrainAdapter.kt` on main @4496658e.
///
/// A direct [BrainAdapter] rather than a provider + bridge pair:
/// there is exactly one wire protocol (`/v1/messages`), and the
/// engine-facing contract is the only one that needs vendor
/// mapping. A dedicated adapter exists because Anthropic has no
/// OpenAI-compatible endpoint — feeding an Anthropic key into the
/// generic adapter would build a connection that can never work.
///
/// Configuration comes from the caller: baseUrl (default
/// `https://api.anthropic.com`), model (from the live `/v1/models`
/// list), API key only in [ClaudeCredentialStore]. No key is ever
/// hardcoded, and no model is silently substituted when the
/// configured one is blank.
public final class ClaudeBrainAdapter: BrainAdapter {
    public static let adapterId = "model-api-claude"
    public static let brainId = "claude"
    public static let authRef = "claude:api-key"
    public static let defaultBaseUrl = ClaudeApiClient.defaultBaseUrl
    public static let suggestedModel = "claude-sonnet-4-5"

    private let credentialStore: ClaudeCredentialStore
    private let baseUrlProvider: () -> String?
    private let configuredModelProvider: () -> String?
    private let apiClient: ClaudeApiClient

    public init(
        credentialStore: ClaudeCredentialStore,
        baseUrlProvider: @escaping () -> String? = { ClaudeApiClient.defaultBaseUrl },
        configuredModelProvider: @escaping () -> String? = { nil },
        apiClient: ClaudeApiClient = ClaudeApiClient()
    ) {
        self.credentialStore = credentialStore
        self.baseUrlProvider = baseUrlProvider
        self.configuredModelProvider = configuredModelProvider
        self.apiClient = apiClient
    }

    public var adapterId: String { Self.adapterId }
    public var kind: BrainKind { .modelApi }
    public var label: String { "Claude API" }

    public func capabilities() -> BrainCapabilities {
        BrainCapabilities(
            streaming: true,
            toolCalling: true,
            vision: false,
            maxContextTokens: nil,
            notes: "Anthropic Messages API /v1/messages；工具调用使用原生 tool_use/tool_result 内容块。" +
                "模型从账户的 /v1/models 列表选择，不写死；Key 只存本机 Keystore。")
    }

    public func validateCredentials() async -> Result<Void, Error> {
        guard let apiKey = credentialStore.loadApiKey() else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 Claude API Key。", retryable: false))
        }
        guard let baseUrl = nonBlank(baseUrlProvider()) else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 Claude 服务地址。", retryable: false))
        }
        switch await apiClient.listModels(apiKey: apiKey, baseUrl: baseUrl) {
        case .success: return .success(())
        case .failure(let error): return .failure(error)
        }
    }

    public func listModels() async -> Result<[ModelInfo], Error> {
        guard let apiKey = credentialStore.loadApiKey() else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 Claude API Key。", retryable: false))
        }
        guard let baseUrl = nonBlank(baseUrlProvider()) else {
            return .failure(BrainErrorInfo(
                code: "not_configured", message: "未配置 Claude 服务地址。", retryable: false))
        }
        return await apiClient.listModels(apiKey: apiKey, baseUrl: baseUrl)
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
                        message: "未配置 Claude API Key：请先在设置里填写你自己的 Key。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                guard let baseUrl = nonBlank(baseUrlProvider()) else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未配置 Claude 服务地址：请在设置里填写 baseUrl。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                let model = nonBlank(request.model) ?? nonBlank(configuredModelProvider())
                guard let model else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未选择 Claude 模型：请先刷新模型列表并选择一个。",
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
                        message: "Claude 调用失败：\(error.localizedDescription)",
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

    private func nonBlank(_ value: String?) -> String? { Self.nonBlank(value) }
}
