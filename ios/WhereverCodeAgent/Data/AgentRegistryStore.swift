import Foundation

/// Agent registry record, mirroring the Android baseline
/// `core/agent/AgentRecord.kt` field for field. Secrets never live
/// in the record: `authRef` / `webhookSecretRef` are reference names
/// only; values live in the credential vault (Keychain), exactly
/// the Android rule.
public enum AgentKind: String, Codable, Equatable {
    case cloud = "CLOUD"
    case device = "DEVICE"
    case localModel = "LOCAL_MODEL"
}

public enum AgentAccess: String, Codable, Equatable {
    case cli = "CLI"
    case app = "APP"
    case connector = "CONNECTOR"
    case webhook = "WEBHOOK"
    case api = "API"
    case manual = "MANUAL"
}

public enum AgentLoginState: String, Codable, Equatable {
    case unknown = "UNKNOWN"
    case loggedIn = "LOGGED_IN"
    case notLoggedIn = "NOT_LOGGED_IN"
}

public struct AgentRecord: Equatable, Codable {
    public var agentId: String
    public var displayName: String
    public var kind: AgentKind
    public var access: AgentAccess
    /// Brain kind wire value (e.g. "API_KEY", "MEMBERSHIP"); kept as
    /// a string at the registry layer, as Android serializes it.
    public var brainKind: String
    public var adapterId: String
    public var binary: String?
    public var baseUrl: String?
    public var model: String?
    public var authRef: String?
    public var capabilities: [String] = []
    public var quotaRule: String?
    public var dispatchable: Bool = true
    public var enabled: Bool = true
    public var knownLimits: String?
    public var addHint: String?
    public var loginState: AgentLoginState = .unknown
    public var lastTestedAtEpochSeconds: Int64?
    public var lastTestSummary: String?
    public var memberRole: String?
    public var memberConnectionState: String = "NOT_CONNECTED"
    public var authorizedProjectIds: [String] = []
    public var lastMemberStateUpdatedAtEpochSeconds: Int64?
    // Device-agent fields.
    public var deviceId: String?
    public var deviceFolder: String?
    // Platform-agent fields (the platform connection item).
    public var platformAgentId: String?
    public var platformHost: String?
    public var platformChannelId: String?
    public var webhookUrl: String?
    public var webhookSecretRef: String?
    public var directGithubRead: Bool = false
    // Cloud-agent fields.
    public var cloudRoles: [String] = []
    public var canDispatch: Bool = true
    public var cloudHomeDeviceId: String?
    public var createdAtEpochSeconds: Int64 = 0
    public var updatedAtEpochSeconds: Int64 = 0

    public init(
        agentId: String, displayName: String, kind: AgentKind,
        access: AgentAccess, brainKind: String, adapterId: String
    ) {
        self.agentId = agentId
        self.displayName = displayName
        self.kind = kind
        self.access = access
        self.brainKind = brainKind
        self.adapterId = adapterId
    }
}

/// The platform connection item as a registry record — the exact
/// shape Android creates from PlatformAgentForm (adapterId
/// "platform-agent", kind CLOUD, access WEBHOOK).
public extension AgentRecord {
    static let platformAdapterId = "platform-agent"

    static func platformAgent(
        agentId: String,
        displayName: String,
        platformAgentId: String,
        platformHost: String,
        platformChannelId: String? = nil
    ) -> AgentRecord {
        var record = AgentRecord(
            agentId: agentId, displayName: displayName, kind: .cloud,
            access: .webhook, brainKind: "NONE", adapterId: platformAdapterId)
        record.platformAgentId = platformAgentId
        record.platformHost = platformHost
        record.platformChannelId = platformChannelId
        return record
    }
}

/// Registry persistence with the Android AgentStore contract
/// (listAgents / getAgent / saveAgent / deleteAgent). Android
/// persists the whole registry as one JSON list in
/// SharedPreferences; iOS keeps the same shape in the kv table.
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
        var record = record
        let timestamp = Int64(Date().timeIntervalSince1970)
        if record.createdAtEpochSeconds == 0 { record.createdAtEpochSeconds = timestamp }
        record.updatedAtEpochSeconds = timestamp
        var agents = try listAgents()
        if let index = agents.firstIndex(where: { $0.agentId == record.agentId }) {
            agents[index] = record
        } else {
            agents.append(record)
        }
        try kv.setJSON(storageKey, agents)
    }

    public func deleteAgent(agentId: String) throws {
        var agents = try listAgents()
        agents.removeAll { $0.agentId == agentId }
        try kv.setJSON(storageKey, agents)
    }

    /// The Agent switch (Android AgentToggle): takes effect
    /// immediately, no restart.
    public func setEnabled(agentId: String, enabled: Bool) throws {
        guard var record = try getAgent(agentId: agentId) else { return }
        record.enabled = enabled
        try saveAgent(record)
    }

    /// The first platform-agent record, if any — the platform
    /// connection item the channel and platform tasks hang off
    /// (Android reads it exactly this way).
    public func platformAgent() throws -> AgentRecord? {
        try listAgents().first { $0.adapterId == AgentRecord.platformAdapterId }
    }
}
