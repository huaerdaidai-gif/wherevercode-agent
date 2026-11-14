import Foundation

public enum BrainKind: String, Codable, CaseIterable, Sendable {
    case modelApi = "MODEL_API"
    case agentRuntime = "AGENT_RUNTIME"
    case subscription = "SUBSCRIPTION"
    case platformAgent = "PLATFORM_AGENT"
    case cloudAgent = "CLOUD_AGENT"
}

public enum JSONValue: Codable, Equatable, Sendable {
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])
    case null

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else if let o = try? c.decode([String: JSONValue].self) { self = .object(o) }
        else { throw DecodingError.dataCorruptedError(in: c, debugDescription: "Unsupported JSON value") }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .bool(let b): try c.encode(b)
        case .number(let n): try c.encode(n)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        case .null: try c.encodeNil()
        }
    }
}

public struct BrainCapabilities: Codable, Equatable, Sendable {
    public let streaming: Bool
    public let toolCalling: Bool
    public let vision: Bool
    public let maxContextTokens: Int
    public let notes: String
    public let toolCallingVerified: Bool

    public init(
        streaming: Bool = true,
        toolCalling: Bool = true,
        vision: Bool = false,
        maxContextTokens: Int = 128_000,
        notes: String = "",
        toolCallingVerified: Bool = true
    ) {
        self.streaming = streaming
        self.toolCalling = toolCalling
        self.vision = vision
        self.maxContextTokens = maxContextTokens
        self.notes = notes
        self.toolCallingVerified = toolCallingVerified
    }
}

public struct BrainModelDescriptor: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let displayName: String
    public let contextTokens: Int?

    public init(id: String, displayName: String, contextTokens: Int? = nil) {
        self.id = id
        self.displayName = displayName
        self.contextTokens = contextTokens
    }
}

public enum BrainStreamEvent: Equatable, Sendable {
    case textDelta(String)
    case reasoningDelta(String)
    case toolCall(ChatToolCall)
    case usage(inputTokens: Int, outputTokens: Int)
    case error(code: String, message: String, retryable: Bool)
    case done
}

public protocol BrainAdapter: AnyObject {
    var adapterId: String { get }
    var displayName: String { get }
    var capabilities: BrainCapabilities { get }
    func listModels(config: BrainConfig) async throws -> [BrainModelDescriptor]
    func streamInfer(config: BrainConfig, messages: [ConversationMessage], tools: [String]) -> AsyncThrowingStream<BrainStreamEvent, Error>
}

public final class BrainAdapterRegistry {
    private let lock = NSLock()
    private var adapters: [String: BrainAdapter] = [:]
    private var adapterOrder: [String] = []
    private var configs: [String: BrainConfig] = [:]
    private var configOrder: [String] = []

    public init() {}

    public func registerAdapter(_ adapter: BrainAdapter) {
        lock.lock()
        defer { lock.unlock() }
        if adapters[adapter.adapterId] == nil {
            adapterOrder.append(adapter.adapterId)
        }
        adapters[adapter.adapterId] = adapter
    }

    public func adapter(for id: String) -> BrainAdapter? {
        lock.lock()
        defer { lock.unlock() }
        return adapters[id]
    }

    public func registerConfig(_ config: BrainConfig) {
        lock.lock()
        defer { lock.unlock() }
        if configs[config.brainId] == nil {
            configOrder.append(config.brainId)
        }
        configs[config.brainId] = config
    }

    public func unregisterConfig(brainId: String) {
        lock.lock()
        defer { lock.unlock() }
        configs.removeValue(forKey: brainId)
        configOrder.removeAll { $0 == brainId }
    }

    public func listConfigs() -> [BrainConfig] {
        lock.lock()
        defer { lock.unlock() }
        return configOrder.compactMap { configs[$0] }
    }

    public func config(for brainId: String) -> BrainConfig? {
        lock.lock()
        defer { lock.unlock() }
        return configs[brainId]
    }
}
