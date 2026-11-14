import Foundation

public struct EngineEvent: Codable, Equatable, Identifiable, Sendable {
    public var id: Int64 { seq }
    public let seq: Int64
    public let conversationId: String
    public let kind: String
    public let detail: String
    public let epochSeconds: Int64

    public init(seq: Int64, conversationId: String, kind: String, detail: String, epochSeconds: Int64 = Int64(Date().timeIntervalSince1970)) {
        self.seq = seq
        self.conversationId = conversationId
        self.kind = kind
        self.detail = detail
        self.epochSeconds = epochSeconds
    }
}

public protocol EventLog: AnyObject {
    func append(conversationId: String, kind: String, detail: String)
    func listEvents() -> [EngineEvent]
}

public final class InMemoryEventLog: EventLog, ObservableObject {
    @Published private var items: [EngineEvent] = []
    private var seq: Int64 = 0
    private let lock = NSLock()

    public init() {}

    public func append(conversationId: String, kind: String, detail: String) {
        lock.lock()
        seq += 1
        let ev = EngineEvent(seq: seq, conversationId: conversationId, kind: kind, detail: SecretRedactor.redact(detail))
        items.insert(ev, at: 0)
        lock.unlock()
    }

    public func listEvents() -> [EngineEvent] {
        lock.lock()
        defer { lock.unlock() }
        return items
    }
}

public enum TurnControlState: String, Codable, Equatable, Sendable {
    case running = "RUNNING"
    case paused = "PAUSED"
    case stopped = "STOPPED"
    case goalChanged = "GOAL_CHANGED"
}

public struct BrainTurnResult: Equatable, Sendable {
    public let assistantText: String
    public let toolCallsExecuted: Int
    public let brainId: String
    public let switchedBrain: Bool

    public init(assistantText: String, toolCallsExecuted: Int, brainId: String, switchedBrain: Bool) {
        self.assistantText = assistantText
        self.toolCallsExecuted = toolCallsExecuted
        self.brainId = brainId
        self.switchedBrain = switchedBrain
    }
}

public final class BrainEngine {
    public let adapters: BrainAdapterRegistry
    public let conversations: ConversationStore
    public let contextManager: ContextManager
    public let memory: MemoryStore
    public let skillStore: SkillStore
    public let tools: ToolRegistry
    public let eventLog: EventLog
    public let approvalGate: ApprovalGate
    public var maxToolRounds: Int = 6
    public var maxToolCallsPerTurn: Int = 12
    public var maxTurnMillis: Int64 = 180_000
    public var controlState: TurnControlState = .running

    public init(
        adapters: BrainAdapterRegistry,
        conversations: ConversationStore,
        contextManager: ContextManager = ContextManager(),
        memory: MemoryStore = InMemoryMemoryStore(),
        skillStore: SkillStore = SkillStore(),
        tools: ToolRegistry = DefaultiOSToolRegistry(),
        eventLog: EventLog,
        approvalGate: ApprovalGate = DenyApprovalGate()
    ) {
        self.adapters = adapters
        self.conversations = conversations
        self.contextManager = contextManager
        self.memory = memory
        self.skillStore = skillStore
        self.tools = tools
        self.eventLog = eventLog
        self.approvalGate = approvalGate
    }

    public func chat(
        conversationId: String,
        userText: String,
        brainIdOverride: String?
    ) async throws -> BrainTurnResult {
        let userMsg = ConversationMessage(conversationId: conversationId, role: "user", content: userText, source: "ios_user")
        try conversations.appendMessage(userMsg)
        eventLog.append(conversationId: conversationId, kind: "USER_TURN", detail: "收到用户输入：\(userText.prefix(80))")

        guard let brainId = brainIdOverride ?? adapters.listConfigs().first?.brainId,
              let config = adapters.config(for: brainId) else {
            eventLog.append(conversationId: conversationId, kind: "brain_not_configured", detail: "未配置大脑：请先在设置里配置一个大脑，再发送消息。")
            throw NSError(domain: "BrainEngine", code: 400, userInfo: [NSLocalizedDescriptionKey: "未配置大脑：请先在设置里配置一个大脑，再发送消息。"])
        }

        guard let adapter = adapters.adapter(for: config.adapterId) else {
            eventLog.append(conversationId: conversationId, kind: "brain_call_failed", detail: "找不到大脑适配器 \(config.adapterId)")
            throw NSError(domain: "BrainEngine", code: 404, userInfo: [NSLocalizedDescriptionKey: "找不到大脑配置 \(brainId)：请检查大脑设置。"])
        }

        var accumulated = ""
        let history = try conversations.listMessages(conversationId: conversationId)
        let trimmed = contextManager.trimIfNeeded(history)
        let stream = adapter.streamInfer(config: config, messages: trimmed.messages, tools: Array(tools.registeredToolNames))
        for try await ev in stream {
            switch ev {
            case .textDelta(let delta):
                accumulated += delta
            case .reasoningDelta:
                break
            case .toolCall(let call):
                eventLog.append(conversationId: conversationId, kind: "TOOL_CALL", detail: "调用工具 \(call.name)")
            case .usage(let inTok, let outTok):
                eventLog.append(conversationId: conversationId, kind: "USAGE", detail: "Tokens in=\(inTok), out=\(outTok)")
            case .error(_, let msg, _):
                eventLog.append(conversationId: conversationId, kind: "brain_call_failed", detail: msg)
                throw NSError(domain: "BrainEngine", code: 500, userInfo: [NSLocalizedDescriptionKey: msg])
            case .done:
                break
            }
        }

        let reply = ConversationMessage(conversationId: conversationId, role: "assistant", content: accumulated, source: brainId)
        try conversations.appendMessage(reply)
        eventLog.append(conversationId: conversationId, kind: "TURN_DONE", detail: "大脑 \(brainId) 完成回复（\(accumulated.count) 字符）")
        return BrainTurnResult(assistantText: accumulated, toolCallsExecuted: 0, brainId: brainId, switchedBrain: false)
    }
}
