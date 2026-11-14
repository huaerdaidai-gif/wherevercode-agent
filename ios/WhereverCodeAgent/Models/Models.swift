import Foundation

public struct ChannelMessage: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let agentId: String
    public let agentName: String
    public let text: String
    public let replyTo: String?
    public let createdAtSeconds: Double

    public init(
        id: String,
        agentId: String,
        agentName: String,
        text: String,
        replyTo: String? = nil,
        createdAtSeconds: Double = Date().timeIntervalSince1970
    ) {
        self.id = id
        self.agentId = agentId
        self.agentName = agentName
        self.text = text
        self.replyTo = replyTo
        self.createdAtSeconds = createdAtSeconds
    }
}

public struct ChannelInfo: Codable, Equatable, Identifiable, Hashable, Sendable {
    public let id: String
    public let name: String
    public let participants: [String]
    public let lastMessageText: String?

    public init(id: String, name: String, participants: [String] = [], lastMessageText: String? = nil) {
        self.id = id
        self.name = name
        self.participants = participants
        self.lastMessageText = lastMessageText
    }
}

public struct AgentTask: Codable, Equatable, Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let detail: String
    public let status: String
    public let version: Int

    public init(id: String, title: String, detail: String, status: String, version: Int = 1) {
        self.id = id
        self.title = title
        self.detail = detail
        self.status = status
        self.version = version
    }
}

public enum ReservedFeature: String, CaseIterable, Identifiable, Sendable {
    case approval
    case pauseTask
    case resumeTask
    case stopTask
    case retargetTask
    case deviceManagement
    case brainChat
    case singleAgentChat

    public var id: String { rawValue }

    public var notImplementedReason: String {
        switch self {
        case .approval:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有审批工具，无法真实执行；任务提交前请先在频道里人工确认。"
        case .pauseTask:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有暂停任务工具，无法真实执行。"
        case .resumeTask:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有恢复任务工具，无法真实执行。"
        case .stopTask:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有停止任务工具，无法真实执行；不要的任务请用「放手任务」交回。"
        case .retargetTask:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有改目标工具，无法真实执行；请放手任务后由调度重新派发。"
        case .deviceManagement:
            return "服务端无此接口，阻塞中：V5 平台的 13 个 MCP 工具里没有设备 / Agent 列表与配对工具，本页读不到真实设备；设备需先在 Android 端或平台侧添加。"
        case .brainChat:
            return "大脑对话已通过本地 SQLite 会话与 BrainEngine 接入。"
        case .singleAgentChat:
            return "未实现：单兵对话尚未接入，先用团队频道与任务页。"
        }
    }
}

public enum IssueTokenResult: Equatable, Sendable {
    case issued(token: String, tokenId: String, agentId: String, name: String)
    case failed(String)
}
