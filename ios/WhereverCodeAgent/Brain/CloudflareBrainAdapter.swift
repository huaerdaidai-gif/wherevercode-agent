import Foundation

/// Cloudflare API Token local storage (only the token itself —
/// never the Account ID, which is not a secret and lives in
/// BrainConfig.extra).
public protocol CloudflareCredentialStore {
    /// Store the user-entered token (overwrites the old value).
    func saveToken(_ token: String) throws
    /// Read the token; nil = not configured.
    func loadToken() -> String?
    /// Clear the local token (sign-out / token rotation).
    func clearToken()
}

/// Keychain-backed store, identical in shape to the SIWC token
/// store: the token is entered by the user on the device and stored
/// only in the Keychain-backed [BrainKeyStore]. It never enters the
/// repo, chat, the app package or platform servers. Revoking here
/// only clears the local copy — fully invalidating a token requires
/// deleting it in the Cloudflare Dashboard (which needs the Token
/// ID, deliberately not stored).
public final class KeystoreCloudflareCredentialStore: CloudflareCredentialStore {
    /// The token's fixed authRef in BrainKeyStore.
    public static let authRef = "cloudflare:token"

    private let keyStore: BrainKeyStore

    public init(keyStore: BrainKeyStore = BrainKeyStore()) {
        self.keyStore = keyStore
    }

    public func saveToken(_ token: String) throws {
        if token.trimmingCharacters(in: .whitespaces).isEmpty {
            throw BrainErrorInfo(
                code: "invalid_token",
                message: "Cloudflare API Token 不能为空",
                retryable: false)
        }
        try keyStore.putSecret(authRef: Self.authRef, value: token)
    }

    public func loadToken() -> String? {
        guard let value = keyStore.getSecret(authRef: Self.authRef),
              !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        return value
    }

    public func clearToken() {
        keyStore.deleteSecret(authRef: Self.authRef)
    }
}

/// Cloudflare Workers AI BrainProvider — REAL implementation.
/// Ported from Android `CloudflareBrainAdapter.kt` on main
/// @4496658e.
///
/// Flow: configuration comes from the caller (BrainBootstrap reads
/// BrainConfig): Account ID, baseUrl, model. The token comes from
/// [CloudflareCredentialStore] (user-entered on device, Keychain).
/// Any of the three missing = NotConfigured — never guessed, never
/// a hardcoded fallback. Requests go to the official
/// OpenAI-compatible endpoint (see [CloudflareApiClient]).
///
/// Model notes (verified against the official pricing page,
/// 2026-10-20 edition):
/// - [suggestedDefaultModel] `@cf/meta/llama-3.1-8b-instruct-fp8-fast`
///   is the cheapest input tier. The full `-fp8-fast` id matters:
///   the plain id costs 6x more on input. It is only a suggestion
///   written into the user-editable config at first setup — the
///   adapter never falls back to it at request time; a blank
///   configured model is an error.
/// - Quota: 10,000 Neurons/day shared account-wide; the Free plan
///   overage only fails, never bills. Overage (429 / 3036, also
///   third-party-reported 4006) maps to QuotaExceeded with the
///   wording "per official docs resets daily 00:00 UTC, may last
///   longer in individual cases" — never "certainly restored".
public final class CloudflareBrainAdapter: BrainProvider {
    public static let providerId = "cloudflare-workers-ai"
    /// Stable M2 adapter id and brain id for the Cloudflare brain.
    public static let adapterId = "cloudflare-workers-ai"
    public static let brainId = "cloudflare-workers-ai"
    /// BrainKeyStore authRef holding the Cloudflare API Token.
    public static let authRef = "cloudflare:token"
    /// BrainConfig.extra key holding the Cloudflare Account ID.
    public static let extraAccountId = "account_id"
    /// Official OpenAI-compatible endpoint account prefix.
    public static let accountsBaseUrl = "https://api.cloudflare.com/client/v4/accounts"
    /// First-setup suggested model (see header; suggestion only).
    public static let suggestedDefaultModel = "@cf/meta/llama-3.1-8b-instruct-fp8-fast"
    /// Reasoning suggestion (user-selected, not a default).
    public static let suggestedReasoningModel = "@cf/openai/gpt-oss-120b"

    private let credentialStore: CloudflareCredentialStore
    private let apiClient: CloudflareApiClient
    private let accountIdProvider: () -> String?
    private let baseUrlProvider: () -> String?
    private let configuredModelProvider: () -> String?

    public init(
        credentialStore: CloudflareCredentialStore,
        onMalformedLine: @escaping (String) -> Void = { _ in },
        apiClient: CloudflareApiClient? = nil,
        accountIdProvider: @escaping () -> String? = { nil },
        baseUrlProvider: @escaping () -> String? = { nil },
        configuredModelProvider: @escaping () -> String? = { nil }
    ) {
        self.credentialStore = credentialStore
        self.apiClient = apiClient ?? CloudflareApiClient(onMalformedLine: onMalformedLine)
        self.accountIdProvider = accountIdProvider
        self.baseUrlProvider = baseUrlProvider
        self.configuredModelProvider = configuredModelProvider
    }

    public var providerId: String { Self.providerId }
    public var displayName: String { "Cloudflare Workers AI" }

    public var capabilities: BrainProviderCapabilities {
        BrainProviderCapabilities(
            supportsStreaming: true,
            supportsTools: true,
            supportsSystemMessages: true,
            requiresFullHistory: true,
            supportsTemperature: true,
            supportsMaxOutputTokens: true,
            billsUserPlan: false,
            limitsNote: "免费层每天 10,000 Neurons，全账户共享；超额只失败、不计费。" +
                "按官方说法每日 00:00 UTC 重置，个别情况下可能持续更久。" +
                "模型须在设置里配置；默认建议 " + Self.suggestedDefaultModel + "。")
    }

    public func isConfigured() -> Bool {
        credentialStore.loadToken() != nil
            && !(accountIdProvider() ?? "").trimmingCharacters(in: .whitespaces).isEmpty
            && !(configuredModelProvider() ?? "").trimmingCharacters(in: .whitespaces).isEmpty
    }

    public func infer(request: BrainRequest) async throws -> BrainResponse {
        // Non-streaming path: collect the stream. Tool calls cannot
        // be represented in a BrainResponse, so a turn that calls
        // tools is an explicit Unsupported.
        var text = ""
        var model = ""
        var usage: BrainUsage?
        for try await event in streamInfer(request: request) {
            switch event {
            case .delta(let delta):
                text += delta
            case .toolCall(_, let name, _):
                throw BrainError.unsupported(
                    "infer() 不支持工具调用（本次回复要求调用工具 \(name)）；请用 streamInfer()。")
            case .completed(let response):
                model = response.model
                usage = response.usage
            case .failed(let error):
                throw error
            }
        }
        return BrainResponse(text: text, model: model, usage: usage, billedToUserPlan: false)
    }

    public func streamInfer(request: BrainRequest) -> AsyncThrowingStream<BrainStreamEvent, Error> {
        let credentialStore = self.credentialStore
        let apiClient = self.apiClient
        let accountIdProvider = self.accountIdProvider
        let baseUrlProvider = self.baseUrlProvider
        let configuredModelProvider = self.configuredModelProvider
        return AsyncThrowingStream { continuation in
            let task = Task {
                // Capability enforcement first: never silently drop.
                if let temperature = request.temperature, temperature < 0.0 || temperature > 5.0 {
                    continuation.yield(.failed(.unsupported(
                        "Cloudflare Workers AI 的 temperature 范围是 0–5，" +
                            "调用方请求了 temperature=\(temperature)，已拒绝，未静默改写。")))
                    continuation.finish()
                    return
                }
                if let maxTokens = request.maxOutputTokens, maxTokens < 1 {
                    continuation.yield(.failed(.unsupported(
                        "Cloudflare Workers AI 的 max_tokens 必须 ≥ 1，" +
                            "调用方请求了 max_tokens=\(maxTokens)，已拒绝，未静默改写。")))
                    continuation.finish()
                    return
                }
                guard let apiToken = credentialStore.loadToken(),
                      !apiToken.trimmingCharacters(in: .whitespaces).isEmpty else {
                    continuation.yield(.failed(.notConfigured(
                        "未配置 Cloudflare API Token：请在控制台的 Cloudflare 设置里" +
                            "填写你自己的 API Token（Dashboard → Workers AI → Use REST API 创建）。")))
                    continuation.finish()
                    return
                }
                guard let accountId = accountIdProvider(),
                      !accountId.trimmingCharacters(in: .whitespaces).isEmpty else {
                    continuation.yield(.failed(.notConfigured(
                        "未配置 Cloudflare Account ID：请在控制台的 Cloudflare 设置里填写 Account ID。")))
                    continuation.finish()
                    return
                }
                guard let baseUrl = baseUrlProvider(),
                      !baseUrl.trimmingCharacters(in: .whitespaces).isEmpty else {
                    continuation.yield(.failed(.notConfigured(
                        "Cloudflare 大脑配置缺少 baseUrl，请检查大脑设置。")))
                    continuation.finish()
                    return
                }
                // Model resolution: explicit request > configured
                // model. A blank configuration is an error — never
                // a fallback to any hardcoded model.
                let requestedModel = request.model.flatMap {
                    $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0
                }
                let configuredModel = configuredModelProvider().flatMap {
                    $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0
                }
                guard let model = requestedModel ?? configuredModel else {
                    continuation.yield(.failed(.notConfigured(
                        "未配置 Cloudflare 模型：请在控制台的 Cloudflare 设置里填写模型 ID，" +
                            "例如 " + Self.suggestedDefaultModel + "。")))
                    continuation.finish()
                    return
                }
                let apiRequest = CloudflareApiRequest(
                    model: model,
                    messages: request.messages.map { message in
                        CloudflareInputMessage(
                            role: message.role.rawValue.lowercased(),
                            content: message.content,
                            toolCallId: message.toolCallId,
                            toolCalls: message.toolCalls.map { call in
                                CloudflareToolCall(
                                    id: call.id, name: call.name,
                                    argumentsJson: call.argumentsJson)
                            })
                    },
                    instructions: request.instructions,
                    tools: request.tools.map { tool in
                        CloudflareTool(
                            name: tool.name, description: tool.description,
                            parametersJson: tool.parametersJson)
                    },
                    temperature: request.temperature,
                    maxOutputTokens: request.maxOutputTokens)
                do {
                    var fullText = ""
                    var toolCallIndex = 0
                    for try await event in apiClient.streamResponse(
                        request: apiRequest, accountId: accountId,
                        apiToken: apiToken, baseUrl: baseUrl) {
                        switch event {
                        case .textDelta(let text):
                            fullText += text
                            continuation.yield(.delta(text))
                        case .toolCallDone(let name, let arguments, let callId):
                            toolCallIndex += 1
                            continuation.yield(.toolCall(
                                id: callId ?? "cf-call-\(toolCallIndex)",
                                name: name, argumentsJson: arguments))
                        case .completed(let usage, let eventFullText):
                            continuation.yield(.completed(BrainResponse(
                                text: eventFullText ?? fullText,
                                model: apiRequest.model,
                                usage: usage.map { BrainUsage(
                                    inputTokens: $0.inputTokens,
                                    outputTokens: $0.outputTokens) },
                                billedToUserPlan: false)))
                        case .failed(_, let message, _):
                            // Stream-level failures carry no
                            // verified error code on the compatible
                            // endpoint; the parser only generates
                            // them for truncation — always Transport.
                            continuation.yield(.failed(.transport(message)))
                        }
                    }
                } catch let error as BrainError {
                    continuation.yield(.failed(error))
                } catch {
                    continuation.yield(.failed(.transport(error.localizedDescription)))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    public func revoke() async {
        // Clears only the local token. Fully invalidating a token
        // requires deleting it in Dashboard → My Profile → API
        // Tokens (needs the Token ID, which this app does not
        // store). After clearing, isConfigured() is false.
        credentialStore.clearToken()
    }
}
