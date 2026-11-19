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

    public var isDeviceHost: Bool {
        kind == .device && access == .app && agentId == deviceId
    }

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

    private let deletedDevicesKey = "devices.deleted.v1"

    public func markDeviceDeleted(deviceId: String) throws {
        var deleted = (try? kv.getJSON(deletedDevicesKey, as: [String].self)) ?? []
        if !deleted.contains(deviceId) {
            deleted.append(deviceId)
            try kv.setJSON(deletedDevicesKey, deleted)
        }
    }

    public func isDeviceDeleted(deviceId: String) -> Bool {
        let deleted = (try? kv.getJSON(deletedDevicesKey, as: [String].self)) ?? []
        return deleted.contains(deviceId)
    }

    public func unmarkDeviceDeleted(deviceId: String) throws {
        var deleted = (try? kv.getJSON(deletedDevicesKey, as: [String].self)) ?? []
        deleted.removeAll { $0 == deviceId }
        try kv.setJSON(deletedDevicesKey, deleted)
    }

    public func deletedDeviceIds() -> [String] {
        (try? kv.getJSON(deletedDevicesKey, as: [String].self)) ?? []
    }

    public func getAgent(agentId: String) throws -> AgentRecord? {
        try listAgents().first { $0.agentId == agentId }
    }

    public func saveAgent(_ record: AgentRecord) throws {
        var record = record
        let timestamp = Int64(Date().timeIntervalSince1970)
        if record.createdAtEpochSeconds == 0 { record.createdAtEpochSeconds = timestamp }
        record.updatedAtEpochSeconds = timestamp
        if record.kind == .device {
            let devId = record.deviceId ?? record.agentId
            try? unmarkDeviceDeleted(deviceId: devId)
        }
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

    /// 安全删除指定设备并写入墓碑（保证重启与局域网探测绝不复活）；
    /// 严格遵循核心原则：删除设备绝不连带删除任何云端/自定义 Agent 或模型！
    /// 仅清理该设备宿主记录并清理配对公钥，名下 AgentRecord 一字不改（展示态派生不落盘）。
    public func deleteDeviceCascade(deviceId: String) throws {
        try markDeviceDeleted(deviceId: deviceId)
        HandIdentityManager.deleteDeviceCredentials(deviceId: deviceId)
        var agents = try listAgents()
        agents.removeAll { $0.agentId == deviceId }
        try kv.setJSON(storageKey, agents)
    }

    /// 安全迁移历史设备记录：
    /// 1. 墓碑拦截：已删除设备绝对不复活；
    /// 2. 稳定身份规范化：补全缺失的 deviceId 字段；
    /// 3. 识别历史演示模板设备并清晰标注，绝不盲目按设备名称猜测合并；
    /// 4. 严格保护云端 Agent、本地模型、设备上的子 Agent 不受任何影响。
    ///
    /// 本迁移只处理「设备宿主记录」（kind == .device 且 access == .app，
    /// 且不是 `<deviceId>:<agentId>` 形式的子 Agent），绝不回填或改写
    /// access == .cli 的设备子 Agent。
    public func migrateLegacyDeviceRecords() throws {
        let deleted = deletedDeviceIds()
        var agents = try listAgents()

        // 宿主形状：配对产生的设备记录（.app 接入），排除设备上的子 Agent（.cli）。
        func isHostShape(_ ag: AgentRecord) -> Bool {
            ag.kind == .device && ag.access == .app && !ag.agentId.contains(":")
        }

        var modified = false

        // 1. 稳定身份回填：先规范宿主形状的 deviceId，供后续墓碑判定使用。
        for i in agents.indices where isHostShape(agents[i]) {
            if agents[i].deviceId == nil || agents[i].deviceId?.isEmpty == true {
                agents[i].deviceId = agents[i].agentId
                modified = true
            }
        }

        // 2. 墓碑拦截：只删已删除设备的宿主记录。
        if !deleted.isEmpty {
            let before = agents.count
            agents.removeAll { ag in
                guard ag.isDeviceHost else { return false }
                return deleted.contains(ag.agentId)
            }
            if agents.count != before { modified = true }
        }

        // 3. 概念分离硬规则：agy 是 Agent，不得登记为一台设备。
        let beforeAgy = agents.count
        agents.removeAll { ag in
            ag.isDeviceHost &&
            (ag.agentId.lowercased() == "agy" || ag.displayName.lowercased() == "agy")
        }
        if agents.count != beforeAgy { modified = true }

        // 4. 历史模板设备诚实状态标注：只标注宿主记录。
        for i in agents.indices where agents[i].isDeviceHost {
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

    /// 开源纯净模式：彻底清空所有预存、缓存或残留的 Agent 与设备数据
    public func clearAllAgents() throws {
        try kv.setJSON(storageKey, [AgentRecord]())
        try kv.setJSON(deletedDevicesKey, [String]())
    }
}
