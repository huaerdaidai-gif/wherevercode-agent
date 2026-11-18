import Foundation

/// Generic OpenAI-compatible API key storage (Kotlin
/// `GenericOpenAiCredentialStore`). One store serves every
/// user-created generic brain: the key is looked up by its authRef
/// (`model-api:<brainId>`). The key is entered by the user on the
/// device and stored only in the Keychain-backed [BrainKeyStore].
/// It is never in `BrainConfig`, never in logs.
public protocol GenericOpenAiCredentialStore {
    func saveApiKey(authRef: String, apiKey: String) throws
    func loadApiKey(authRef: String) -> String?
    func clearApiKey(authRef: String)
}

public final class KeystoreGenericOpenAiCredentialStore: GenericOpenAiCredentialStore {
    public static let authRefPrefix = "model-api:"

    private let keyStore: BrainKeyStore

    public init(keyStore: BrainKeyStore = BrainKeyStore()) {
        self.keyStore = keyStore
    }

    public func saveApiKey(authRef: String, apiKey: String) throws {
        if !authRef.hasPrefix(Self.authRefPrefix) {
            throw BrainErrorInfo(
                code: "invalid_auth_ref",
                message: "通用模型 API 的密钥引用名必须以 model-api: 开头",
                retryable: false)
        }
        if apiKey.trimmingCharacters(in: .whitespaces).isEmpty {
            throw BrainErrorInfo(
                code: "invalid_key", message: "API Key 不能为空", retryable: false)
        }
        try keyStore.putSecret(authRef: authRef, value: apiKey)
    }

    public func loadApiKey(authRef: String) -> String? {
        // A foreign or blank ref is "no key", never a crash: the
        // adapter reports not_configured.
        if !authRef.hasPrefix(Self.authRefPrefix) { return nil }
        guard let value = keyStore.getSecret(authRef: authRef),
              !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return value
    }

    public func clearApiKey(authRef: String) {
        if authRef.hasPrefix(Self.authRefPrefix) {
            keyStore.deleteSecret(authRef: authRef)
        }
    }
}

/// Generic OpenAI-compatible brain. Ported from Android
/// `GenericOpenAiBrainAdapter.kt` on main @4496658e.
///
/// One adapter instance, registered under adapterId
/// `model-api-generic`, serves EVERY user-created generic brain:
/// configs are saved per label with brainId `model-api-<slug>` and
/// the same adapterId. The engine calls `chatStream` with only a
/// model (no brain id), so the adapter resolves its config through
/// `brainIdProvider`, which defaults to the current brain id;
/// `forBrain` binds one explicit brain id for per-row calls.
///
/// - baseUrl: user-editable, no vendor default; validated with
///   [BrainConfigValidator] at call time (https-only, local-model
///   exception), so a stored config that predates validation still
///   fails loudly;
/// - model: user-editable, from the live `/models` list; blank
///   errors at request time, never a silent substitute;
/// - API key: only in [GenericOpenAiCredentialStore] under authRef
///   `model-api:<brainId>`.
///
/// toolCalling = true; toolCallingVerified stays unset — support is
/// claimed by the adapter, real-device verification is separate.
public final class GenericOpenAiBrainAdapter: BrainAdapter {
    /// Stable adapter id for every generic OpenAI-compatible brain.
    /// Must stay identical to the id the settings form saves configs
    /// with.
    public static let adapterId = "model-api-generic"

    private let configStore: BrainConfigStore
    private let credentialStore: GenericOpenAiCredentialStore
    private let apiClient: GenericOpenAiApiClient
    private let brainIdProvider: () -> String?

    public init(
        configStore: BrainConfigStore,
        credentialStore: GenericOpenAiCredentialStore,
        apiClient: GenericOpenAiApiClient = GenericOpenAiApiClient(),
        brainIdProvider: (() -> String?)? = nil
    ) {
        self.configStore = configStore
        self.credentialStore = credentialStore
        self.apiClient = apiClient
        self.brainIdProvider = brainIdProvider ?? { configStore.currentBrainId() }
    }

    /// A view of this adapter bound to one explicit brain id.
    public func forBrain(_ brainId: String) -> GenericOpenAiBrainAdapter {
        GenericOpenAiBrainAdapter(
            configStore: configStore,
            credentialStore: credentialStore,
            apiClient: apiClient,
            brainIdProvider: { brainId })
    }

    public var adapterId: String { Self.adapterId }
    public var kind: BrainKind { .modelApi }
    public var label: String { "通用模型 API" }

    public func capabilities() -> BrainCapabilities {
        BrainCapabilities(
            streaming: true,
            toolCalling: true,
            vision: false,
            maxContextTokens: nil,
            notes: "OpenAI 兼容 /chat/completions；模型从账户的 /models 列表选择，不写死；" +
                "Key 只存本机 Keystore。第二阶段支持工具调用（写操作走审批）。")
    }

    public func validateCredentials() async -> Result<Void, Error> {
        guard let config = resolveConfig() else {
            return .failure(BrainErrorInfo(
                code: "not_configured",
                message: "未配置通用模型 API 大脑：请先在设置里添加一个通用模型 API 大脑。",
                retryable: false))
        }
        guard let baseUrl = Self.nonBlank(config.baseUrl) else {
            return .failure(BrainErrorInfo(
                code: "not_configured",
                message: "未配置服务地址：请先在设置里填写 baseUrl。",
                retryable: false))
        }
        // 本机模型可无 Key；非本机地址无 Key 仍拒绝。
        let apiKey = credentialStore.loadApiKey(authRef: config.authRef ?? "")
        if (apiKey ?? "").trimmingCharacters(in: .whitespaces).isEmpty
            && !BrainConfigValidator.isLocalModelUrl(baseUrl) {
            return .failure(BrainErrorInfo(
                code: "not_configured",
                message: "未配置 API Key：请先在设置里填写你自己的 Key。",
                retryable: false))
        }
        switch await apiClient.listModels(apiKey: apiKey ?? "", baseUrl: baseUrl) {
        case .success: return .success(())
        case .failure(let error): return .failure(error)
        }
    }

    public func listModels() async -> Result<[ModelInfo], Error> {
        guard let config = resolveConfig() else {
            return .failure(BrainErrorInfo(
                code: "not_configured",
                message: "未配置通用模型 API 大脑：请先在设置里添加一个通用模型 API 大脑。",
                retryable: false))
        }
        guard let baseUrl = Self.nonBlank(config.baseUrl) else {
            return .failure(BrainErrorInfo(
                code: "not_configured",
                message: "未配置服务地址：请先在设置里填写 baseUrl。",
                retryable: false))
        }
        let apiKey = credentialStore.loadApiKey(authRef: config.authRef ?? "")
        if (apiKey ?? "").trimmingCharacters(in: .whitespaces).isEmpty
            && !BrainConfigValidator.isLocalModelUrl(baseUrl) {
            return .failure(BrainErrorInfo(
                code: "not_configured",
                message: "未配置 API Key：请先在设置里填写你自己的 Key。",
                retryable: false))
        }
        switch await apiClient.listModels(apiKey: apiKey ?? "", baseUrl: baseUrl) {
        case .success(let models): return .success(models)
        case .failure(let error): return .failure(error)
        }
    }

    public func chatStream(request: ChatRequest) -> AsyncThrowingStream<BrainEvent, Error> {
        let configStore = self.configStore
        let credentialStore = self.credentialStore
        let apiClient = self.apiClient
        let brainIdProvider = self.brainIdProvider
        return AsyncThrowingStream { continuation in
            let task = Task {
                guard let brainId = brainIdProvider(),
                      let config = configStore.getConfig(brainId: brainId),
                      config.adapterId == Self.adapterId else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未配置通用模型 API 大脑：请先在设置里添加一个通用模型 API 大脑。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                guard let baseUrl = Self.nonBlank(config.baseUrl) else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未配置服务地址：请先在设置里填写 baseUrl。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                // 本机模型可无 Key（不发 Authorization 头）；非本机
                // 地址无 Key 仍拒绝。
                let apiKey = credentialStore.loadApiKey(authRef: config.authRef ?? "")
                if (apiKey ?? "").trimmingCharacters(in: .whitespaces).isEmpty
                    && !BrainConfigValidator.isLocalModelUrl(baseUrl) {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未配置 API Key：请先在设置里填写你自己的 Key（只存本机 Keystore）。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                if !BrainConfigValidator.isAllowedBaseUrl(baseUrl) {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "服务地址只允许 https（本地模型允许 http://127.0.0.1 或 http://localhost）：\(baseUrl)",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                let model = Self.nonBlank(request.model) ?? Self.nonBlank(config.model)
                guard let model else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未选择模型：请先刷新模型列表并选择一个。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                var effectiveRequest = request
                effectiveRequest.model = model
                // Set once a malformed tool-call argument fails the
                // turn: later events from the same stream must not
                // leak out as text after the error.
                var failed = false
                for await event in apiClient.streamChat(
                    request: effectiveRequest, apiKey: apiKey ?? "", baseUrl: baseUrl) {
                    if failed { break }
                    switch event {
                    case .text(let text):
                        continuation.yield(.textDelta(text))
                    case .reasoning(let text):
                        continuation.yield(.reasoningDelta(text))
                    case .toolCall(let id, let name, let argumentsJson):
                        if let args = DeepSeekBrainAdapter.parseToolArgs(argumentsJson) {
                            continuation.yield(.toolCall(BrainToolCall(id: id, name: name, args: args)))
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
                        continuation.yield(.error(code: code, message: message, retryable: retryable))
                        continuation.yield(.done(finishReason: "error"))
                    }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Resolve the config this call is for: the brain id from
    /// `brainIdProvider`, and only a config whose adapterId is this
    /// adapter's. A current brain that belongs to another adapter
    /// (e.g. DeepSeek) is NOT this adapter's config.
    private func resolveConfig() -> BrainConfig? {
        guard let brainId = brainIdProvider(),
              let config = configStore.getConfig(brainId: brainId),
              config.adapterId == Self.adapterId else { return nil }
        return config
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return value
    }
}
