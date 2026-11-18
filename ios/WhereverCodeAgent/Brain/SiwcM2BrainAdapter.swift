import Foundation

/// SiwcM2BrainAdapter: the bridge between a SIWC BrainProvider and
/// the M2 BrainAdapter contract the BrainEngine trusts. Ported from
/// Android `SiwcM2BrainAdapter.kt` on main @4496658e.
///
/// The two contracts are deliberately NOT merged:
/// - [BrainProvider] stays the SIWC-facing layer (Preview
///   capabilities, BrainRequest/BrainResponse, BrainError).
/// - [BrainAdapter] stays the engine-facing layer
///   (ChatRequest/BrainEvent, registry). The engine never sees a
///   vendor branch.
///
/// Mapping rules (all explicit, nothing silently dropped):
/// - system messages become `BrainRequest.instructions` (SIWC
///   Preview has no system role); tool messages become USER-role
///   messages carrying the tool result as text; user/assistant map
///   1:1.
/// - temperature / maxTokens: SIWC Preview rejects them — an
///   Unsupported error, never silently ignored.
/// - model: blank means "not chosen yet" (BrainRequest.model =
///   nil); the provider resolves it from the account's model
///   directory, never from a hardcoded default.
/// - Errors: AuthFailed / QuotaExceeded / Unsupported /
///   NotConfigured are non-retryable; only Transport is retryable.
public final class SiwcM2BrainAdapter: BrainAdapter {
    /// Stable M2 adapter id and brain id for the SIWC brain.
    public static let adapterId = "siwc-chatgpt"
    public static let brainId = "siwc-chatgpt"
    /// BrainKeyStore authRef holding the SIWC tokens.
    public static let authRef = "siwc:tokens"

    private let provider: BrainProvider

    public init(provider: BrainProvider) {
        self.provider = provider
    }

    public var adapterId: String { Self.adapterId }
    public var kind: BrainKind { .subscription }
    public var label: String { provider.displayName }

    public func capabilities() -> BrainCapabilities {
        BrainCapabilities(
            streaming: provider.capabilities.supportsStreaming,
            toolCalling: provider.capabilities.supportsTools,
            vision: false,
            maxContextTokens: nil,
            notes: provider.capabilities.limitsNote)
    }

    public func validateCredentials() async -> Result<Void, Error> {
        if provider.isConfigured() {
            return .success(())
        }
        return .failure(BrainErrorInfo(
            code: "not_configured",
            message: "未登录 ChatGPT：请先使用 ChatGPT 登录，再发送消息。",
            retryable: false))
    }

    public func listModels() async -> Result<[ModelInfo], Error> {
        switch await provider.listModels() {
        case .success(let slugs):
            return .success(slugs.map { ModelInfo(id: $0, displayName: $0) })
        case .failure(let error):
            return .failure(error)
        }
    }

    public func chatStream(request: ChatRequest) -> AsyncThrowingStream<BrainEvent, Error> {
        let provider = self.provider
        return AsyncThrowingStream { continuation in
            let task = Task {
                // SIWC Preview rejects temperature / max_output_tokens.
                // Fail loudly.
                if request.temperature != nil || request.maxTokens != nil {
                    continuation.yield(.error(
                        code: "unsupported",
                        message: "SIWC Preview 不支持 temperature / max_output_tokens，请去掉这两个参数。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                let brainRequest = Self.mapRequest(request)
                do {
                    for try await event in provider.streamInfer(request: brainRequest) {
                        switch event {
                        case .delta(let text):
                            continuation.yield(.textDelta(text))
                        case .toolCall(let id, let name, let argumentsJson):
                            continuation.yield(.toolCall(BrainToolCall(
                                id: id, name: name, args: Self.parseToolArgs(argumentsJson))))
                        case .completed(let response):
                            if let usage = response.usage {
                                continuation.yield(.usage(
                                    inputTokens: usage.inputTokens.map { Int($0) },
                                    outputTokens: usage.outputTokens.map { Int($0) }))
                            }
                            continuation.yield(.done(finishReason: "stop"))
                        case .failed(let error):
                            continuation.yield(Self.mapError(error))
                            continuation.yield(.done(finishReason: "error"))
                        }
                    }
                } catch {
                    // Providers must emit .failed, not throw. If one
                    // throws anyway, the throw propagates: the engine
                    // treats it as a retryable transport failure
                    // (brain_call_failed), per the bridge contract.
                    continuation.finish(throwing: error)
                    return
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    static func mapRequest(_ request: ChatRequest) -> BrainRequest {
        var instructions = ""
        var messages: [BrainMessage] = []
        for message in request.messages {
            switch message.role {
            case "system":
                if !instructions.isEmpty { instructions += "\n" }
                instructions += message.content
            case "assistant":
                messages.append(BrainMessage(role: .assistant, content: message.content))
            case "tool":
                // Minimal mapping: render the tool result as text.
                // The Responses API function_call_output shape lands
                // later.
                messages.append(BrainMessage(
                    role: .user,
                    content: "工具\(message.name ?? "")执行结果：\(message.content)"))
            default:
                messages.append(BrainMessage(role: .user, content: message.content))
            }
        }
        return BrainRequest(
            messages: messages,
            instructions: instructions.trimmingCharacters(in: .whitespaces).isEmpty
                ? nil : instructions,
            model: request.model.trimmingCharacters(in: .whitespaces).isEmpty
                ? nil : request.model,
            tools: request.tools.map { tool in
                BrainTool(
                    name: tool.name,
                    description: tool.description,
                    parametersJson: JSONValue.stringify(tool.inputSchema))
            })
    }

    /// Parse a tool-call arguments JSON string into a map. If it is
    /// somehow malformed here, return an empty map and let the
    /// engine's ToolRegistry fail the call at structure validation
    /// rather than inventing arguments or crashing the stream.
    static func parseToolArgs(_ argumentsJson: String) -> [String: JSONValue] {
        if argumentsJson.trimmingCharacters(in: .whitespaces).isEmpty { return [:] }
        return JSONValue.parseObject(argumentsJson) ?? [:]
    }

    static func mapError(_ error: BrainError) -> BrainEvent {
        switch error.kind {
        case .notConfigured:
            return .error(
                code: "not_configured",
                message: error.message.isEmpty ? "未登录 ChatGPT。" : error.message,
                retryable: false)
        case .authFailed:
            return .error(
                code: "auth_failed",
                message: error.message.isEmpty ? "ChatGPT 授权失败，请重新登录。" : error.message,
                retryable: false)
        case .quotaExceeded:
            return .error(
                code: "quota_exceeded",
                message: error.message.isEmpty
                    ? "ChatGPT 套餐额度已耗尽或本次请求不在套餐覆盖内。" : error.message,
                retryable: false)
        case .unsupported:
            return .error(
                code: "unsupported",
                message: error.message.isEmpty ? "该大脑不支持本次请求。" : error.message,
                retryable: false)
        case .transport:
            return .error(
                code: "transport_error",
                message: error.message.isEmpty ? "网络或服务器错误，请稍后重试。" : error.message,
                retryable: true)
        case .notImplemented:
            return .error(
                code: "not_implemented",
                message: error.message.isEmpty ? "该大脑尚未实现。" : error.message,
                retryable: false)
        }
    }
}
