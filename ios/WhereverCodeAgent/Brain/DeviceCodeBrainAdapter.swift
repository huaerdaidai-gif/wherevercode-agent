// DeviceCodeBrainAdapter.swift
// Ported from the Android app (brain/devicecode/
// DeviceCodeBrainAdapter.kt) — membership brain adapter for one
// device-code vendor.
//
// Inference reuses the generic OpenAI-compatible client
// (GenericOpenAiApiClient): Qwen's inference endpoint is OpenAI
// compatible (`portal.qwen.ai/v1/chat/completions`) and Kimi's
// coding endpoint is OpenAI compatible
// (`api.kimi.com/coding/v1`); the only difference from an
// API-Key brain is where the Bearer token comes from — the
// vendor's device-code token store, refreshed before use, never
// a user-typed key. No second copy of the chat-completions
// protocol is written here.
//
// Honesty notes (do not soften):
// - Kimi's inference path is `/chat/completions` on the coding
//   endpoint; the vendor docs describe the endpoint as Responses
//   API as well. It must be walked through on a real device with
//   the maintainer's own account before it counts as delivered.
// - Grok's inference endpoint (`api.x.ai/v1`) is the third-party
//   route, not the official CLI path (`cli-chat-proxy.grok.com`);
//   a 403 there is the documented allowlist risk, not a bug in
//   this adapter — the adapter reports it verbatim.
// - Tool calling is OFF for membership brains in this first cut
//   (capabilities.toolCalling = false): the engine will not send
//   tools, and a model-emitted tool call is refused honestly
//   rather than executed with a membership token.

import Foundation

public final class DeviceCodeBrainAdapter: BrainAdapter {
    public let config: DeviceCodeConfig
    private let manager: DeviceCodeManager
    private let configStore: BrainConfigStore
    private let apiClient: GenericOpenAiApiClient
    private let brainIdProvider: () -> String?

    public init(
        config: DeviceCodeConfig,
        manager: DeviceCodeManager,
        configStore: BrainConfigStore,
        apiClient: GenericOpenAiApiClient = GenericOpenAiApiClient(),
        brainIdProvider: (() -> String?)? = nil
    ) {
        self.config = config
        self.manager = manager
        self.configStore = configStore
        self.apiClient = apiClient
        // Android's default: the registry's current brain id.
        self.brainIdProvider = brainIdProvider ?? { configStore.currentBrainId() }
    }

    public var adapterId: String { config.adapterId }
    public var kind: BrainKind { .subscription }
    public var label: String { config.displayName }

    public func capabilities() -> BrainCapabilities {
        BrainCapabilities(
            streaming: true,
            toolCalling: false,
            vision: false,
            maxContextTokens: nil,
            notes: "\(config.displayName)会员 token 推理（\(config.inferenceBaseUrl)）；"
                + "token 只存本机 Keychain，过期自动用 refresh_token 续。"
                + (config.experimental ? " \(config.experimentalNote)" : "")
                + (config.complianceNote.isEmpty ? "" : " \(config.complianceNote)"))
    }

    /// A view of this adapter bound to one explicit brain id.
    public func forBrain(_ brainId: String) -> DeviceCodeBrainAdapter {
        DeviceCodeBrainAdapter(
            config: config,
            manager: manager,
            configStore: configStore,
            apiClient: apiClient,
            brainIdProvider: { brainId })
    }

    public func validateCredentials() async -> Result<Void, Error> {
        guard let resolved = resolve() else {
            return .failure(BrainErrorInfo(
                code: "not_configured",
                message: "未配置\(config.displayName)大脑。",
                retryable: false))
        }
        let token: String
        do {
            token = try await manager.ensureFreshAccessToken()
        } catch let error as BrainErrorInfo {
            return .failure(error)
        } catch {
            return .failure(BrainErrorInfo(
                code: "auth_failed",
                message: "\(config.displayName) 未登录。",
                retryable: false))
        }
        switch await apiClient.listModels(apiKey: token, baseUrl: resolved.baseUrl) {
        case .success:
            return .success(())
        case .failure(let info):
            return .failure(info)
        }
    }

    public func listModels() async -> Result<[ModelInfo], Error> {
        guard let resolved = resolve() else {
            return .failure(BrainErrorInfo(
                code: "not_configured",
                message: "未配置\(config.displayName)大脑。",
                retryable: false))
        }
        let token: String
        do {
            token = try await manager.ensureFreshAccessToken()
        } catch let error as BrainErrorInfo {
            return .failure(error)
        } catch {
            return .failure(BrainErrorInfo(
                code: "auth_failed",
                message: "\(config.displayName) 未登录。",
                retryable: false))
        }
        switch await apiClient.listModels(apiKey: token, baseUrl: resolved.baseUrl) {
        case .success(let models):
            return .success(models)
        case .failure(let info):
            return .failure(info)
        }
    }

    public func chatStream(request: ChatRequest) -> AsyncThrowingStream<BrainEvent, Error> {
        let manager = self.manager
        let apiClient = self.apiClient
        let config = self.config
        let resolved = resolve()
        return AsyncThrowingStream { continuation in
            let task = Task {
                guard let resolved else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未配置\(config.displayName)大脑：请先在设置里登录。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                let model = !request.model.trimmingCharacters(in: .whitespaces).isEmpty
                    ? request.model
                    : (!resolved.config.model.trimmingCharacters(in: .whitespaces).isEmpty
                        ? resolved.config.model : nil)
                guard let model else {
                    continuation.yield(.error(
                        code: "not_configured",
                        message: "未选择模型：请先在设置里“\(config.displayName)”的编辑页里填模型名（以厂商模型目录为准）。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                var currentToken: String
                do {
                    currentToken = try await manager.ensureFreshAccessToken()
                } catch let error as BrainErrorInfo {
                    continuation.yield(.error(error))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                } catch {
                    continuation.yield(.error(
                        code: "auth_failed",
                        message: "\(config.displayName) 未登录。",
                        retryable: false))
                    continuation.yield(.done(finishReason: "error"))
                    continuation.finish()
                    return
                }
                var turnRequest = request
                turnRequest.model = model
                // One refresh-and-retry on 401: the token may have
                // died between ensureFreshAccessToken and the call.
                var refreshedOnce = false
                while true {
                    var sawAuthFailed = false
                    var failed = false
                    for await event in apiClient.streamChat(
                        request: turnRequest, apiKey: currentToken,
                        baseUrl: resolved.baseUrl
                    ) {
                        if failed { break }
                        switch event {
                        case .text(let text):
                            continuation.yield(.textDelta(text))
                        case .reasoning(let text):
                            continuation.yield(.reasoningDelta(text))
                        case .toolCall(_, let name, _):
                            // Membership brains do not do tool
                            // calling in this first cut: refuse
                            // honestly, never execute with a
                            // membership token.
                            failed = true
                            continuation.yield(.error(
                                code: "unsupported",
                                message: "\(config.displayName)会员大脑暂不支持工具调用：本次请求要求调用工具 \(name)，已拒绝，未执行。",
                                retryable: false))
                            continuation.yield(.done(finishReason: "error"))
                        case .usage(let input, let output):
                            continuation.yield(.usage(
                                inputTokens: input, outputTokens: output))
                        case .finished(let reason):
                            continuation.yield(.done(finishReason: reason))
                        case .failed(let code, let message, let retryable):
                            if code == "auth_failed" && !refreshedOnce {
                                sawAuthFailed = true
                            } else {
                                failed = true
                                continuation.yield(.error(
                                    code: code, message: message,
                                    retryable: retryable))
                                continuation.yield(.done(finishReason: "error"))
                            }
                        }
                    }
                    if !sawAuthFailed || failed {
                        continuation.finish()
                        return
                    }
                    // 401 once: refresh and retry the whole turn.
                    refreshedOnce = true
                    do {
                        currentToken = try await manager.refreshTokens().accessToken
                    } catch let error as BrainErrorInfo {
                        continuation.yield(.error(error))
                        continuation.yield(.done(finishReason: "error"))
                        continuation.finish()
                        return
                    } catch {
                        continuation.yield(.error(
                            code: "auth_failed",
                            message: "\(config.displayName) 授权已失效，请重新登录。",
                            retryable: false))
                        continuation.yield(.done(finishReason: "error"))
                        continuation.finish()
                        return
                    }
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private struct Resolved {
        let config: BrainConfig
        let baseUrl: String
    }

    private func resolve() -> Resolved? {
        guard let brainId = brainIdProvider(),
              let config = configStore.getConfig(brainId: brainId),
              config.adapterId == adapterId
        else { return nil }
        let baseUrl = (config.baseUrl ?? "").trimmingCharacters(in: .whitespaces)
        if baseUrl.isEmpty { return nil }
        return Resolved(config: config, baseUrl: baseUrl)
    }
}
