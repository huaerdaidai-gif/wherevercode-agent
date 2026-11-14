import Foundation

/// Brain adapter contract + registry (Universal Brain Engine, stage 2),
/// ported from the Android baseline `brain/BrainAdapter.kt` on main
/// @4496658e. Adapters self-register; the engine only trusts the
/// registry, and no `if (vendor == "...")` hard-coding is allowed
/// anywhere in the engine.
///
/// The [BrainKind] values are NOT interchangeable (this must also be
/// shown in UI copy):
/// - MODEL_API: an HTTP model API billed per call. A key is enough to
///   call it; streaming and native tool calling are normal; a leaked
///   key is a leaked bill.
/// - AGENT_RUNTIME: an already-logged-in CLI/App on the device. There
///   is no key concept; every call spawns a process and the output is
///   the CLI's stream-json, not a native chat API.
/// - SUBSCRIPTION: borrowing an AGENT_RUNTIME login's membership quota
///   as the brain. One CLI call per decision, slow, quota-bound. It
///   must never be presented as an API call: no key, no baseUrl, no
///   `/models`.
/// - PLATFORM_AGENT: an Agent on the platform, reached through the
///   platform channel / webhook wake; not a model API, not a CLI.
/// - CLOUD_AGENT: a cloud Agent that takes work through an MCP
///   connector; not a model API, not a CLI.

public enum BrainKind: String, Codable, Equatable, CaseIterable {
    case modelApi = "MODEL_API"
    case agentRuntime = "AGENT_RUNTIME"
    case subscription = "SUBSCRIPTION"
    case platformAgent = "PLATFORM_AGENT"
    case cloudAgent = "CLOUD_AGENT"

    /// The Kotlin enum name, used in persisted JSON.
    public var name: String { rawValue }

    public init?(name: String) { self.init(rawValue: name) }
}

/// A JSON value, standing in for Kotlin's `Any?` inside
/// `Map<String, Any?>` (tool arguments, tool schemas). Codable and
/// Equatable so messages and calls can be compared and persisted.
public enum JSONValue: Equatable, Codable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container, debugDescription: "Unsupported JSON value")
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }

    /// Build from a Foundation JSON object (JSONSerialization output).
    public init(anyObject value: Any) {
        switch value {
        case is NSNull:
            self = .null
        case let number as NSNumber:
            // NSNumber bridges both booleans and numbers; CFBoolean is
            // the only reliable discriminator.
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                self = .bool(number.boolValue)
            } else {
                self = .number(number.doubleValue)
            }
        case let string as String:
            self = .string(string)
        case let array as [Any]:
            self = .array(array.map { JSONValue(anyObject: $0) })
        case let object as [String: Any]:
            self = .object(object.mapValues { JSONValue(anyObject: $0) })
        default:
            self = .string(String(describing: value))
        }
    }

    /// Back to a Foundation object for JSONSerialization.
    public var anyObject: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let value): return value
        case .number(let value): return value
        case .string(let value): return value
        case .array(let value): return value.map { $0.anyObject }
        case .object(let value): return value.mapValues { $0.anyObject }
        }
    }

    public var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    public var boolValue: Bool? {
        if case .bool(let value) = self { return value }
        return nil
    }

    /// Kotlin `"$value"` rendering used by the engine's call-signature
    /// normalisation: strings raw, booleans `true`/`false`, null
    /// `null`, arrays `[a, b]`, objects `{k=v}`.
    public var keyString: String {
        switch self {
        case .null: return "null"
        case .bool(let value): return value ? "true" : "false"
        case .number(let value):
            if value == value.rounded() && abs(value) < 9.0e15 {
                return String(Int64(value))
            }
            return String(value)
        case .string(let value): return value
        case .array(let value):
            return "[" + value.map { $0.keyString }.joined(separator: ", ") + "]"
        case .object(let value):
            let body = value.keys.sorted()
                .map { "\($0)=\(value[$0]?.keyString ?? "null")" }
                .joined(separator: ", ")
            return "{\(body)}"
        }
    }

    /// Serialise a JSON object to a string (sorted keys, so the output
    /// is deterministic for tests and signatures).
    public static func stringify(_ object: [String: JSONValue]) -> String {
        guard let data = try? JSONSerialization.data(
            withJSONObject: JSONValue.object(object).anyObject,
            options: [.sortedKeys]),
            let text = String(data: data, encoding: .utf8)
        else { return "{}" }
        return text
    }

    /// Parse a JSON object string; nil when the text is not a JSON
    /// object (same tolerance as the Kotlin `JSONObject(...)` call
    /// sites, which catch and bail out).
    public static func parseObject(_ text: String) -> [String: JSONValue]? {
        guard let data = text.data(using: .utf8),
              let raw = try? JSONSerialization.jsonObject(with: data),
              let object = raw as? [String: Any]
        else { return nil }
        return object.mapValues { JSONValue(anyObject: $0) }
    }
}

/// Capabilities are self-reported by the adapter. The engine trusts
/// only self-report + `/models` probing, never guesses. Unknown =
/// null, and the UI must show "未知" (unknown) instead of inventing a
/// value.
public struct BrainCapabilities: Equatable {
    public var streaming: Bool
    public var toolCalling: Bool
    public var vision: Bool = false
    public var maxContextTokens: Int? = nil
    public var notes: String = ""
    /// Measured tool-calling grade, separate from the adapter's claim:
    /// null = unverified, true = verified working on a real device,
    /// false = verified unsupported. Written from real-device results,
    /// never hard-coded.
    public var toolCallingVerified: Bool? = nil

    public init(
        streaming: Bool,
        toolCalling: Bool,
        vision: Bool = false,
        maxContextTokens: Int? = nil,
        notes: String = "",
        toolCallingVerified: Bool? = nil
    ) {
        self.streaming = streaming
        self.toolCalling = toolCalling
        self.vision = vision
        self.maxContextTokens = maxContextTokens
        self.notes = notes
        self.toolCallingVerified = toolCallingVerified
    }
}

/// An image attached to a message for the brain. Valid for the current
/// turn only: the bytes never enter conversation history. `dataUrl` is
/// a downsampled JPEG data URL produced by the sender.
public struct ChatImage: Equatable {
    public var dataUrl: String
    public init(dataUrl: String) { self.dataUrl = dataUrl }
}

public struct ChatMessage: Equatable {
    /// user | assistant | tool | system
    public var role: String
    public var content: String
    public var toolCallId: String? = nil
    public var name: String? = nil
    /// Assistant tool calls, in wire order. Empty for every
    /// non-assistant message and for text-only assistant messages. The
    /// legacy `content` echo is kept so adapters without native tool
    /// support still receive something readable.
    public var toolCalls: [ChatToolCall] = []
    /// Images attached to this turn's user message. Adapters without
    /// vision never receive them (the engine filters per round), so an
    /// empty or non-empty list here never proves the model saw them.
    public var images: [ChatImage] = []

    public init(
        role: String,
        content: String,
        toolCallId: String? = nil,
        name: String? = nil,
        toolCalls: [ChatToolCall] = [],
        images: [ChatImage] = []
    ) {
        self.role = role
        self.content = content
        self.toolCallId = toolCallId
        self.name = name
        self.toolCalls = toolCalls
        self.images = images
    }
}

public struct ToolDef: Equatable {
    public var name: String
    public var description: String
    /// JSON-Schema-style object.
    public var inputSchema: [String: JSONValue] = [:]

    public init(name: String, description: String, inputSchema: [String: JSONValue] = [:]) {
        self.name = name
        self.description = description
        self.inputSchema = inputSchema
    }
}

public struct ModelInfo: Equatable {
    public var id: String
    public var displayName: String
    public var contextTokens: Int? = nil

    public init(id: String, displayName: String, contextTokens: Int? = nil) {
        self.id = id
        self.displayName = displayName
        self.contextTokens = contextTokens
    }
}

public struct ChatRequest: Equatable {
    public var messages: [ChatMessage]
    public var model: String
    public var tools: [ToolDef] = []
    public var maxTokens: Int? = nil
    public var temperature: Double? = nil

    public init(
        messages: [ChatMessage],
        model: String,
        tools: [ToolDef] = [],
        maxTokens: Int? = nil,
        temperature: Double? = nil
    ) {
        self.messages = messages
        self.model = model
        self.tools = tools
        self.maxTokens = maxTokens
        self.temperature = temperature
    }
}

/// A tool call emitted by an adapter (Kotlin `BrainEvent.ToolCall`).
public struct BrainToolCall: Equatable {
    public var id: String
    public var name: String
    public var args: [String: JSONValue]

    public init(id: String, name: String, args: [String: JSONValue]) {
        self.id = id
        self.name = name
        self.args = args
    }

    public func withArgs(_ args: [String: JSONValue]) -> BrainToolCall {
        BrainToolCall(id: id, name: name, args: args)
    }
}

/// The error payload of a `BrainEvent.error` (Kotlin
/// `BrainEvent.Error`). Usage carries ONLY numbers the provider
/// returned; never an estimate.
public struct BrainErrorInfo: Equatable, Error {
    public var code: String
    public var message: String
    public var retryable: Bool

    public init(code: String, message: String, retryable: Bool) {
        self.code = code
        self.message = message
        self.retryable = retryable
    }
}

/// Normalised stream events from an adapter.
public enum BrainEvent: Equatable {
    case textDelta(String)
    case reasoningDelta(String)
    case toolCall(BrainToolCall)
    case toolResult(id: String, ok: Bool, summary: String)
    case usage(inputTokens: Int?, outputTokens: Int?)
    case error(BrainErrorInfo)
    case done(finishReason: String)

    public static func error(code: String, message: String, retryable: Bool) -> BrainEvent {
        .error(BrainErrorInfo(code: code, message: message, retryable: retryable))
    }
}

public protocol BrainAdapter {
    var adapterId: String { get }
    var kind: BrainKind { get }
    var label: String { get }
    func capabilities() -> BrainCapabilities

    /// Read-only probe: must not produce a billed call beyond the
    /// probe itself and must have no side effects. Failure carries a
    /// human-actionable reason.
    func validateCredentials() async -> Result<Void, Error>

    /// MODEL_API probes `/models`; AGENT_RUNTIME / SUBSCRIPTION return
    /// a fixed list or an empty list (they have no model catalogue).
    func listModels() async -> Result<[ModelInfo], Error>

    /// Streaming chat; tool calls come out as `BrainEvent.toolCall`
    /// and are executed by the engine.
    func chatStream(request: ChatRequest) -> AsyncThrowingStream<BrainEvent, Error>
}

/// Adapter registry. The engine only looks adapters up here;
/// registration is explicit at app start. The important invariant is
/// that there are no hard-coded vendor branches in the engine.
public final class BrainAdapterRegistry {
    public static let shared = BrainAdapterRegistry()

    private let lock = NSLock()
    private var adapters: [String: BrainAdapter] = [:]
    private var adapterOrder: [String] = []
    private var configs: [String: BrainConfig] = [:]
    private var configOrder: [String] = []

    public init() {}

    public func registerAdapter(_ adapter: BrainAdapter) {
        lock.lock(); defer { lock.unlock() }
        if adapters[adapter.adapterId] == nil { adapterOrder.append(adapter.adapterId) }
        adapters[adapter.adapterId] = adapter
    }

    /// Register a batch of adapters.
    public func loadAdapters(_ newAdapters: [BrainAdapter]) {
        newAdapters.forEach { registerAdapter($0) }
    }

    public func get(_ adapterId: String) -> BrainAdapter? {
        lock.lock(); defer { lock.unlock() }
        return adapters[adapterId]
    }

    public func all() -> [BrainAdapter] {
        lock.lock(); defer { lock.unlock() }
        return adapterOrder.compactMap { adapters[$0] }
    }

    public func clearForTests() {
        lock.lock(); defer { lock.unlock() }
        adapters.removeAll()
        adapterOrder.removeAll()
        configs.removeAll()
        configOrder.removeAll()
    }

    /// Brain configs are registered here so the engine can resolve
    /// brainId -> adapter.
    public func registerConfig(_ config: BrainConfig) {
        lock.lock(); defer { lock.unlock() }
        if configs[config.brainId] == nil { configOrder.append(config.brainId) }
        configs[config.brainId] = config
    }

    /// Unregister a brain config (when the brain is deleted). Without
    /// this, a deleted brain stays routable for the rest of the app
    /// session even after its stored config is gone.
    public func unregisterConfig(brainId: String) {
        lock.lock(); defer { lock.unlock() }
        configs.removeValue(forKey: brainId)
        configOrder.removeAll { $0 == brainId }
    }

    public func configFor(_ brainId: String) -> BrainConfig? {
        lock.lock(); defer { lock.unlock() }
        return configs[brainId]
    }

    public func allConfigs() -> [BrainConfig] {
        lock.lock(); defer { lock.unlock() }
        return configOrder.compactMap { configs[$0] }
    }
}
