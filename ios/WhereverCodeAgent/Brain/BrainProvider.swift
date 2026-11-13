import Foundation

/// BrainProvider — a switchable inference brain. Ported from
/// Android `brain/siwc/BrainProvider.kt` on main @4496658e.
///
/// Design principles:
/// - The super Agent is a dispatcher; the brain is a replaceable
///   member, not a fixed role.
/// - Only officially supported authorization is used. No passwords,
///   no session cookies, no reverse-engineered clients, no sharing
///   one user's plan with another user.
/// - Providers declare their capabilities; callers must respect
///   them. A provider that cannot do something must fail loudly
///   ([BrainError] unsupported) rather than silently degrading.
/// - No mock inference. If there is no available brain, report "no
///   available brain" rather than returning a fabricated answer.
///
/// This layer is deliberately NOT merged with the engine-facing
/// [BrainAdapter] contract: providers (SIWC, Cloudflare) speak
/// BrainRequest/BrainResponse/BrainError; M2 bridge adapters convert
/// to ChatRequest/BrainEvent for the engine.
public protocol BrainProvider {
    /// Stable provider id, e.g. "siwc-chatgpt". Never user-visible.
    var providerId: String { get }
    /// Human-readable name, e.g. "ChatGPT (Sign in with ChatGPT)".
    var displayName: String { get }
    /// What this provider can and cannot do.
    var capabilities: BrainProviderCapabilities { get }
    /// True when the provider is configured and ready.
    func isConfigured() -> Bool
    /// Run one inference turn and return the full text. Throws
    /// [BrainError] on failure; never returns a fake response.
    func infer(request: BrainRequest) async throws -> BrainResponse
    /// Stream one inference turn as events. Providers must emit a
    /// `.failed` event, not throw, for expected failures.
    func streamInfer(request: BrainRequest) -> AsyncThrowingStream<BrainStreamEvent, Error>
    /// The account's model directory, when the provider has one.
    /// Providers without a catalogue return an empty list — honest,
    /// not a failure.
    func listModels() async -> Result<[String], Error>
    /// Revoke authorization and delete all locally stored
    /// credentials. After revoke, isConfigured must return false.
    func revoke() async
}

public extension BrainProvider {
    func listModels() async -> Result<[String], Error> { .success([]) }
}

/// A single message in the conversation history (Kotlin
/// `BrainMessage`).
public struct BrainMessage: Equatable {
    public var role: BrainRole
    public var content: String
    /// The id of the tool call this tool-role message answers. Null
    /// for non-tool messages and for legacy tool messages recorded
    /// before ids were carried (providers that require pairing must
    /// downgrade those instead of sending an unpaired tool message).
    public var toolCallId: String? = nil
    /// Assistant tool calls, in wire order. (Kotlin `BrainToolCall`
    /// — identical shape to the engine's [ChatToolCall].)
    public var toolCalls: [ChatToolCall] = []

    public init(
        role: BrainRole,
        content: String,
        toolCallId: String? = nil,
        toolCalls: [ChatToolCall] = []
    ) {
        self.role = role
        self.content = content
        self.toolCallId = toolCallId
        self.toolCalls = toolCalls
    }
}

/// Message roles. Note: there is no SYSTEM role. System-level
/// guidance goes in [BrainRequest.instructions]. TOOL is the
/// official tool-result role; providers that have no tool role
/// (SIWC / Responses API) must never receive it — their bridge maps
/// tool results to USER instead.
public enum BrainRole: String, Equatable {
    case user = "USER"
    case assistant = "ASSISTANT"
    case developer = "DEVELOPER"
    case tool = "TOOL"
}

/// One inference request (Kotlin `BrainRequest`).
public struct BrainRequest: Equatable {
    /// Full conversation history.
    public var messages: [BrainMessage]
    /// System-level guidance, sent as `instructions` where the
    /// provider supports it.
    public var instructions: String? = nil
    /// Model identifier. Null means provider default.
    public var model: String? = nil
    /// Function / custom tools.
    public var tools: [BrainTool] = []
    /// Optional sampling knobs. Providers that do not support them
    /// must reject with unsupported, not silently ignore them.
    public var temperature: Double? = nil
    public var maxOutputTokens: Int? = nil

    public init(
        messages: [BrainMessage],
        instructions: String? = nil,
        model: String? = nil,
        tools: [BrainTool] = [],
        temperature: Double? = nil,
        maxOutputTokens: Int? = nil
    ) {
        self.messages = messages
        self.instructions = instructions
        self.model = model
        self.tools = tools
        self.temperature = temperature
        self.maxOutputTokens = maxOutputTokens
    }
}

/// A function / custom tool the brain may call.
public struct BrainTool: Equatable {
    public var name: String
    public var description: String
    /// JSON Schema for the tool's parameters, as a JSON string.
    public var parametersJson: String

    public init(name: String, description: String, parametersJson: String) {
        self.name = name
        self.description = description
        self.parametersJson = parametersJson
    }
}

/// One inference response (non-streaming).
public struct BrainResponse: Equatable {
    public var text: String
    public var model: String
    /// Usage in the provider's own units, when reported.
    public var usage: BrainUsage? = nil
    /// True when billed to the user's own plan (SIWC).
    public var billedToUserPlan: Bool = false

    public init(text: String, model: String, usage: BrainUsage? = nil, billedToUserPlan: Bool = false) {
        self.text = text
        self.model = model
        self.usage = usage
        self.billedToUserPlan = billedToUserPlan
    }
}

/// Token usage, when the provider reports it.
public struct BrainUsage: Equatable {
    public var inputTokens: Int64? = nil
    public var outputTokens: Int64? = nil

    public init(inputTokens: Int64? = nil, outputTokens: Int64? = nil) {
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
    }
}

/// One streaming event from a provider.
public enum BrainStreamEvent: Equatable {
    case delta(String)
    case toolCall(id: String, name: String, argumentsJson: String)
    case completed(BrainResponse)
    case failed(BrainError)
}

/// What a provider can and cannot do (Kotlin siwc
/// `BrainCapabilities`; named BrainProviderCapabilities here to
/// avoid clashing with the engine-facing [BrainCapabilities]).
public struct BrainProviderCapabilities: Equatable {
    public var supportsStreaming: Bool
    public var supportsTools: Bool
    /// False for SIWC Preview: no explicit system messages.
    public var supportsSystemMessages: Bool
    /// True when the provider needs full history in every request.
    public var requiresFullHistory: Bool
    public var supportsTemperature: Bool
    public var supportsMaxOutputTokens: Bool
    /// True when the provider bills the user's own plan (SIWC).
    public var billsUserPlan: Bool
    /// Human-readable note on the provider's limits.
    public var limitsNote: String = ""

    public init(
        supportsStreaming: Bool,
        supportsTools: Bool,
        supportsSystemMessages: Bool,
        requiresFullHistory: Bool,
        supportsTemperature: Bool,
        supportsMaxOutputTokens: Bool,
        billsUserPlan: Bool,
        limitsNote: String = ""
    ) {
        self.supportsStreaming = supportsStreaming
        self.supportsTools = supportsTools
        self.supportsSystemMessages = supportsSystemMessages
        self.requiresFullHistory = requiresFullHistory
        self.supportsTemperature = supportsTemperature
        self.supportsMaxOutputTokens = supportsMaxOutputTokens
        self.billsUserPlan = billsUserPlan
        self.limitsNote = limitsNote
    }
}

/// Errors a [BrainProvider] may throw / emit. Never fake a success.
/// (Kotlin's sealed `BrainError` class hierarchy, modelled as a
/// struct with a kind so Swift callers can switch exhaustively.)
public struct BrainError: Error, Equatable {
    public enum Kind: String, Equatable {
        case notConfigured
        case authFailed
        case quotaExceeded
        case unsupported
        case transport
        case notImplemented
    }

    public var kind: Kind
    public var message: String
    /// Machine-readable server code for auth failures (e.g.
    /// `invalid_grant`); never a secret.
    public var errorCode: String? = nil

    public init(kind: Kind, message: String, errorCode: String? = nil) {
        self.kind = kind
        self.message = message
        self.errorCode = errorCode
    }

    public static func notConfigured(_ message: String) -> BrainError {
        BrainError(kind: .notConfigured, message: message)
    }
    public static func authFailed(_ message: String, errorCode: String? = nil) -> BrainError {
        BrainError(kind: .authFailed, message: message, errorCode: errorCode)
    }
    public static func quotaExceeded(_ message: String) -> BrainError {
        BrainError(kind: .quotaExceeded, message: message)
    }
    public static func unsupported(_ message: String) -> BrainError {
        BrainError(kind: .unsupported, message: message)
    }
    public static func transport(_ message: String) -> BrainError {
        BrainError(kind: .transport, message: message)
    }
    public static func notImplemented(_ message: String) -> BrainError {
        BrainError(kind: .notImplemented, message: message)
    }
}
