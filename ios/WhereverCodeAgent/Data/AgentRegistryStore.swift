import Foundation

public enum AgentKind: String, Codable, CaseIterable, Sendable {
    case cloud = "CLOUD"
    case device = "DEVICE"
    case localModel = "LOCAL_MODEL"
    case cli = "CLI"
    case app = "APP"
    case connector = "CONNECTOR"
    case webhook = "WEBHOOK"
}

public enum AgentAccess: String, Codable, CaseIterable, Sendable {
    case api = "API"
    case manual = "MANUAL"
    case unknown = "UNKNOWN"
}

public enum AgentLoginState: String, Codable, CaseIterable, Sendable {
    case loggedIn = "LOGGED_IN"
    case notLoggedIn = "NOT_LOGGED_IN"
}

public struct AgentRecord: Codable, Equatable, Identifiable, Sendable {
    public var id: String { agentId }
    public let agentId: String
    public var displayName: String
    public var kind: AgentKind
    public var access: AgentAccess
    public var brainKind: String
    public var adapterId: String
    public var binary: String?
    public var baseUrl: String?
    public var model: String?
    public var authRef: String?
    public var capabilities: [String]
    public var quotaRule: String?
    public var dispatchable: Bool
    public var enabled: Bool
    public var knownLimits: String?
    public var addHint: String?
    public var loginState: AgentLoginState
    public var lastTestedAtEpochSeconds: Int64?
    public var lastTestSummary: String?
    public var memberRole: String?
    public var memberConnectionState: String
    public var authorizedProjectIds: [String]
    public var lastMemberStateUpdatedAtEpochSeconds: Int64?
    public var deviceId: String?
    public var deviceFolder: String?
    public var platformAgentId: String?
    public var platformHost: String?
    public var platformChannelId: String?
    public var webhookUrl: String?
    public var webhookSecretRef: String?
    public var directGithubRead: Bool
    public var cloudRoles: [String]
    public var canDispatch: Bool
    public var cloudHomeDeviceId: String?
    public var createdAtEpochSeconds: Int64
    public var updatedAtEpochSeconds: Int64

    public init(
        agentId: String,
        displayName: String,
        kind: AgentKind = .cloud,
        access: AgentAccess = .api,
        brainKind: String = "MODEL_API",
        adapterId: String = "model-api-generic",
        binary: String? = nil,
        baseUrl: String? = nil,
        model: String? = nil,
        authRef: String? = nil,
        capabilities: [String] = ["chat", "dev"],
        quotaRule: String? = nil,
        dispatchable: Bool = true,
        enabled: Bool = true,
        knownLimits: String? = nil,
        addHint: String? = nil,
        loginState: AgentLoginState = .notLoggedIn,
        lastTestedAtEpochSeconds: Int64? = nil,
        lastTestSummary: String? = nil,
        memberRole: String? = "developer",
        memberConnectionState: String = "DISCONNECTED",
        authorizedProjectIds: [String] = [],
        lastMemberStateUpdatedAtEpochSeconds: Int64? = nil,
        deviceId: String? = nil,
        deviceFolder: String? = nil,
        platformAgentId: String? = nil,
        platformHost: String? = nil,
        platformChannelId: String? = nil,
        webhookUrl: String? = nil,
        webhookSecretRef: String? = nil,
        directGithubRead: Bool = false,
        cloudRoles: [String] = [],
        canDispatch: Bool = false,
        cloudHomeDeviceId: String? = nil,
        createdAtEpochSeconds: Int64 = Int64(Date().timeIntervalSince1970),
        updatedAtEpochSeconds: Int64 = Int64(Date().timeIntervalSince1970)
    ) {
        self.agentId = agentId
        self.displayName = displayName
        self.kind = kind
        self.access = access
        self.brainKind = brainKind
        self.adapterId = adapterId
        self.binary = binary
        self.baseUrl = baseUrl
        self.model = model
        self.authRef = authRef
        self.capabilities = capabilities
        self.quotaRule = quotaRule
        self.dispatchable = dispatchable
        self.enabled = enabled
        self.knownLimits = knownLimits
        self.addHint = addHint
        self.loginState = loginState
        self.lastTestedAtEpochSeconds = lastTestedAtEpochSeconds
        self.lastTestSummary = lastTestSummary
        self.memberRole = memberRole
        self.memberConnectionState = memberConnectionState
        self.authorizedProjectIds = authorizedProjectIds
        self.lastMemberStateUpdatedAtEpochSeconds = lastMemberStateUpdatedAtEpochSeconds
        self.deviceId = deviceId
        self.deviceFolder = deviceFolder
        self.platformAgentId = platformAgentId
        self.platformHost = platformHost
        self.platformChannelId = platformChannelId
        self.webhookUrl = webhookUrl
        self.webhookSecretRef = webhookSecretRef
        self.directGithubRead = directGithubRead
        self.cloudRoles = cloudRoles
        self.canDispatch = canDispatch
        self.cloudHomeDeviceId = cloudHomeDeviceId
        self.createdAtEpochSeconds = createdAtEpochSeconds
        self.updatedAtEpochSeconds = updatedAtEpochSeconds
    }

    public static func platformAgent(
        agentId: String,
        displayName: String,
        platformAgentId: String,
        platformHost: String,
        platformChannelId: String?
    ) -> AgentRecord {
        AgentRecord(
            agentId: agentId,
            displayName: displayName,
            kind: .cloud,
            access: .api,
            brainKind: "PLATFORM_AGENT",
            adapterId: "platform-mcp",
            capabilities: ["channel", "task_claim", "task_submit"],
            dispatchable: true,
            enabled: true,
            loginState: .loggedIn,
            memberConnectionState: "CONNECTED",
            platformAgentId: platformAgentId,
            platformHost: platformHost,
            platformChannelId: platformChannelId
        )
    }
}

public final class AgentRegistryStore {
    private let kv: KeyValueStore
    private let storageKey = "agents.records.v1"

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    public func listAgents() throws -> [AgentRecord] {
        try kv.getJSON(storageKey, as: [AgentRecord].self) ?? []
    }

    public func getAgent(agentId: String) throws -> AgentRecord? {
        try listAgents().first { $0.agentId == agentId }
    }

    public func saveAgent(_ record: AgentRecord) throws {
        var list = try listAgents()
        if let idx = list.firstIndex(where: { $0.agentId == record.agentId }) {
            list[idx] = record
        } else {
            list.append(record)
        }
        try kv.setJSON(storageKey, list)
    }

    public func setEnabled(agentId: String, enabled: Bool) throws {
        var list = try listAgents()
        if let idx = list.firstIndex(where: { $0.agentId == agentId }) {
            list[idx].enabled = enabled
            list[idx].updatedAtEpochSeconds = Int64(Date().timeIntervalSince1970)
            try kv.setJSON(storageKey, list)
        }
    }

    public func deleteAgent(agentId: String) throws {
        let list = try listAgents().filter { $0.agentId != agentId }
        try kv.setJSON(storageKey, list)
    }
}
