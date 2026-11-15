import Foundation

/// Agent registry record, mirroring the Android baseline
/// `core/agent/AgentRecord.kt` field for field. Secrets never live
/// in the record: `authRef` / `webhookSecretRef` are reference names
/// only; values live in the credential vault (Keychain), exactly
/// the Android rule.
public enum AgentKind: String, Codable, CaseIterable, Equatable, Sendable {
    case cloud = "CLOUD"
    case device = "DEVICE"
    case localModel = "LOCAL_MODEL"
}

public enum AgentAccess: String, Codable, CaseIterable, Equatable, Sendable {
    case cli = "CLI"
    case app = "APP"
    case connector = "CONNECTOR"
    case webhook = "WEBHOOK"
    case api = "API"
    case manual = "MANUAL"
}

public enum AgentLoginState: String, Codable, CaseIterable, Equatable, Sendable {
    case unknown = "UNKNOWN"
    case loggedIn = "LOGGED_IN"
    case notLoggedIn = "NOT_LOGGED_IN"
}

public struct AgentRecord: Equatable, Codable, Identifiable, Sendable {
    public var id: String { agentId }
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
        agentId: String,
        displayName: String,
        kind: AgentKind = .cloud,
        access: AgentAccess = .api,
        brainKind: String = "MODEL_API",
        adapterId: String = "model-api-generic",
        baseUrl: String? = nil,
        authRef: String? = nil,
        lastTestSummary: String? = nil,
        memberConnectionState: String = "NOT_CONNECTED",
        deviceId: String? = nil,
        deviceFolder: String? = nil
    ) {
        self.agentId = agentId
        self.displayName = displayName
        self.kind = kind
        self.access = access
        self.brainKind = brainKind
        self.adapterId = adapterId
        self.baseUrl = baseUrl
        self.authRef = authRef
        self.lastTestSummary = lastTestSummary
        self.memberConnectionState = memberConnectionState
        self.deviceId = deviceId
        self.deviceFolder = deviceFolder
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

    /// 级联删除指定设备及其所属子 Agent：
    /// 严格只清理 kind == .device 且与该设备绑定的记录，绝不触碰云端 Agent (kind == .cloud)、本地模型 (kind == .localModel)、项目与模型配置。
    public func deleteDeviceCascade(deviceId: String) throws {
        var agents = try listAgents()
        agents.removeAll { ag in
            guard ag.kind == .device else { return false }
            return ag.agentId == deviceId || ag.deviceId == deviceId || ag.agentId.hasPrefix("\(deviceId):")
        }
        try kv.setJSON(storageKey, agents)
    }

    /// 安全迁移历史设备记录：
    /// 1. 稳定身份规范化：补全缺失的 deviceId 字段；
    /// 2. 识别历史演示模板设备并清晰标注，绝不盲目按设备名称猜测合并；
    /// 3. 严格保护云端 Agent、本地模型不受任何影响。
    public func migrateLegacyDeviceRecords() throws {
        var agents = try listAgents()
        var modified = false
        for i in 0..<agents.count {
            guard agents[i].kind == .device else { continue }
            // 稳定身份回填
            if agents[i].deviceId == nil || agents[i].deviceId?.isEmpty == true {
                agents[i].deviceId = agents[i].agentId
                modified = true
            }
            // 历史模板设备诚实状态标注
            let aid = agents[i].agentId
            let isPrototype = aid == "device-mac-workstation" || aid == "device-redmi-k70" || aid == "device-xiaomi-12" || aid == "device-iphone-local"
            if isPrototype && agents[i].authRef == nil && agents[i].memberConnectionState == "NOT_CONNECTED" {
                if agents[i].lastTestSummary == nil || agents[i].lastTestSummary == "本地登记 · 未验证在线" {
                    agents[i].lastTestSummary = "演示模板设备 · 点击可配置真实地址或删除"
                    modified = true
                }
            }
        }
        if modified {
            try kv.setJSON(storageKey, agents)
        }
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
