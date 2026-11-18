import Foundation

/// CloudflareM2BrainAdapter: the bridge between the Cloudflare
/// BrainProvider and the M2 BrainAdapter contract. Ported from
/// Android `CloudflareM2BrainAdapter.kt` on main @4496658e.
/// Mirrors [SiwcM2BrainAdapter]; the two contracts stay unmerged.
///
/// Mapping rules (all explicit, nothing silently dropped):
/// - system messages become instructions (the provider sends them
///   as the first system message, which Workers AI supports);
/// - tool messages become TOOL-role messages with the content
///   passed through unchanged and the call id carried in toolCallId
///   (no "工具…执行结果：" prefix — the engine already backfills
///   per its paradigm);
/// - assistant messages map 1:1 with their structured toolCalls;
/// - temperature / maxTokens: Cloudflare supports both; the
///   provider rejects out-of-range values loudly;
/// - model: blank means "not chosen yet"; the provider resolves it
///   from the configured model and errors clearly when there is
///   none. No hardcoded fallback.
/// - QuotaExceeded is non-retryable: the notice is emitted as text
///   first, then the error — no same-brain retry, no silent
///   fallback to another pool, no auto-pay.
public final class CloudflareM2BrainAdapter: BrainAdapter {
    /// Stable M2 adapter id and brain id for the Cloudflare brain.
    public static let adapterId = "cloudflare-workers-ai"
    public static let brainId = "cloudflare-workers-ai"
    /// BrainKeyStore authRef holding the Cloudflare API Token.
    public static let authRef = "cloudflare:token"

    /// Quota notice, emitted as text before the error (the error
    /// carries the same wording). Reset timing per the verified
    /// wording: officially daily 00:00 UTC, but it may last longer
    /// in individual cases — never "certainly restored at 00:00".
    public static let quotaNotice =
        "Cloudflare 今日免费额度已用完（每天 10,000 Neurons，全账户共享）。" +
        "按官方说法每日 00:00 UTC 重置，但个别情况下可能持续更久" +
        "（最长到首次使用后 24 小时）。"

    private let provider: BrainProvider
    /// Currently configured model (for listModels); nil = not
    /// configured.
    private let configuredModel: () -> String?

    public init(provider: BrainProvider, configuredModel: @escaping () -> String? = { nil }) {
        self.provider = provider
        self.configuredModel = configuredModel
    }

    public var adapterId: String { Self.adapterId }
    public var kind: BrainKind { .modelApi }
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
            message: "未配置 Cloudflare：请在控制台的 Cloudflare 设置里填写 Account ID、API Token 与模型。",
            retryable: false))
    }

    public func listModels() async -> Result<[ModelInfo], Error> {
        // Workers AI has no verified model-catalogue probe wired
        // here, so the honest answer is the single user-configured
        // model (or an empty list when none is set), never an
        // invented catalogue.
        if let model = configuredModel(),
           !model.trimmingCharacters(in: .whitespaces).isEmpty {
            return .success([ModelInfo(id: model, displayName: model)])
        }
        return .success([])
    }

    public func chatStream(request: ChatRequest) -> AsyncThrowingStream<BrainEvent, Error> {
        let provider = self.provider
        return AsyncThrowingStream { continuation in
            let task = Task {
                let brainRequest = Self.mapRequest(request)
                var failed = false
                do {
                    for try await event in provider.streamInfer(request: brainRequest) {
                        if failed { break }
                        switch event {
                    case .delta(let text):
                        continuation.yield(.textDelta(text))
                    case .toolCall(let id, let name, let argumentsJson):
                        // Identical to DeepSeekBrainAdapter: invalid
                        // JSON is not guessed at and not sent as an
                        // empty-object call — it fails the turn.
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
                    case .completed(let response):
                        if let usage = response.usage {
                            continuation.yield(.usage(
                                inputTokens: usage.inputTokens.map { Int($0) },
                                outputTokens: usage.outputTokens.map { Int($0) }))
                        }
                        continuation.yield(.done(finishReason: "stop"))
                    case .failed(let error):
                        if error.kind == .quotaExceeded {
                            continuation.yield(.textDelta(Self.quotaNotice))
                        }
                        continuation.yield(Self.mapError(error))
                        continuation.yield(.done(finishReason: "error"))
                    }
                    }
                } catch {
                    // Providers must emit .failed, not throw; a throw
                    // propagates as a retryable transport failure at
                    // the engine (bridge contract).
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
                messages.append(BrainMessage(
                    role: .assistant, content: message.content,
                    toolCalls: message.toolCalls))
            case "tool":
                // Backfill paradigm: the tool result passes through
                // unchanged as a TOOL-role message carrying its
                // toolCallId; the compatible endpoint pairs by it.
                messages.append(BrainMessage(
                    role: .tool, content: message.content,
                    toolCallId: message.toolCallId))
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
            },
            temperature: request.temperature,
            maxOutputTokens: request.maxTokens)
    }

    static func mapError(_ error: BrainError) -> BrainEvent {
        switch error.kind {
        case .notConfigured:
            return .error(
                code: "not_configured",
                message: error.message.isEmpty ? "未配置 Cloudflare。" : error.message,
                retryable: false)
        case .authFailed:
            return .error(
                code: "auth_failed",
                message: error.message.isEmpty ? "Cloudflare 授权失败，请检查 API Token。" : error.message,
                retryable: false)
        case .quotaExceeded:
            return .error(
                code: "quota_exceeded",
                message: error.message.isEmpty ? "Cloudflare 今日免费额度已用完。" : error.message,
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
