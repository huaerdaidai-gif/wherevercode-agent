import Foundation

/// Versioned, explicit transfer representation. Do not encode app/store models here.
public struct ConfigBundleV1: Codable, Equatable {
    public static let formatName = "WCA-ConfigBundle"
    public static let currentSchemaVersion = 1
    public static let nonSecretOnlyPolicy = "nonSecretOnly"

    public let format: String
    public let schemaVersion: Int
    public let bundleId: String
    public let bundleNonce: String
    public let createdAtEpochSeconds: Int64
    public let sourcePlatform: String
    public let secretsPolicy: String
    public let payload: BundlePayloadV1

    public init(bundleId: String, bundleNonce: String, createdAtEpochSeconds: Int64, sourcePlatform: String,
                secretsPolicy: String = ConfigBundleV1.nonSecretOnlyPolicy, payload: BundlePayloadV1) {
        self.format = Self.formatName
        self.schemaVersion = Self.currentSchemaVersion
        self.bundleId = bundleId
        self.bundleNonce = bundleNonce
        self.createdAtEpochSeconds = createdAtEpochSeconds
        self.sourcePlatform = sourcePlatform
        self.secretsPolicy = secretsPolicy
        self.payload = payload
    }

    public static func decode(_ data: Data) throws -> ConfigBundleV1 {
        let value = try JSONDecoder().decode(ConfigBundleV1.self, from: data)
        guard value.format == formatName, value.schemaVersion == currentSchemaVersion else {
            throw BundleV1Error.unsupportedFormatOrVersion
        }
        guard value.secretsPolicy == nonSecretOnlyPolicy else { throw BundleV1Error.unsupportedFormatOrVersion }
        return value
    }

    public func encode() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return try encoder.encode(self)
    }
}

public enum BundleV1Error: Error { case unsupportedFormatOrVersion }

public struct BundlePayloadV1: Codable, Equatable {
    public var devices: [BundleDeviceV1]
    public var agents: [BundleAgentV1]
    public var projects: [BundleProjectV1]
    public var brains: [BundleBrainV1]
    public var currentBrainId: String?
    public var appSettings: BundleAppSettingsV1

    public init(devices: [BundleDeviceV1] = [], agents: [BundleAgentV1] = [], projects: [BundleProjectV1] = [],
                brains: [BundleBrainV1] = [], currentBrainId: String? = nil, appSettings: BundleAppSettingsV1 = .init()) {
        self.devices = devices; self.agents = agents; self.projects = projects; self.brains = brains
        self.currentBrainId = currentBrainId; self.appSettings = appSettings
    }
}

public struct BundleDeviceV1: Codable, Equatable {
    public let sourceDeviceId: String
    public let displayName: String
    public let host: String
    public let port: Int
    public let pairingState: String
    public init(sourceDeviceId: String, displayName: String, host: String, port: Int) {
        self.sourceDeviceId = sourceDeviceId; self.displayName = displayName; self.host = host; self.port = port
        self.pairingState = "reauthorizationRequired"
    }
    private enum CodingKeys: String, CodingKey { case sourceDeviceId, displayName, host, port, pairingState }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        sourceDeviceId = try c.decode(String.self, forKey: .sourceDeviceId)
        displayName = try c.decode(String.self, forKey: .displayName)
        host = try c.decode(String.self, forKey: .host); port = try c.decode(Int.self, forKey: .port)
        pairingState = try c.decode(String.self, forKey: .pairingState)
        guard pairingState == "reauthorizationRequired" else { throw BundleV1Error.unsupportedFormatOrVersion }
    }
}

public struct BundleAgentV1: Codable, Equatable {
    public let agentId: String
    public let displayName: String
    public let kind: String
    public let access: String
    public let brainKind: String
    public let adapterId: String
    public let enabled: Bool
    public let capabilities: [String]
    public let authorizedProjectIds: [String]
    public let deviceId: String?
    public let credentialState: String

    public init(agentId: String, displayName: String, kind: String, access: String, brainKind: String,
                adapterId: String, enabled: Bool, capabilities: [String] = [], authorizedProjectIds: [String] = [],
                deviceId: String? = nil) {
        self.agentId = agentId; self.displayName = displayName; self.kind = kind; self.access = access
        self.brainKind = brainKind; self.adapterId = adapterId; self.enabled = enabled
        self.capabilities = capabilities; self.authorizedProjectIds = authorizedProjectIds; self.deviceId = deviceId
        self.credentialState = "reauthorizationRequired"
    }
    private enum CodingKeys: String, CodingKey {
        case agentId, displayName, kind, access, brainKind, adapterId, enabled, capabilities, authorizedProjectIds, deviceId, credentialState
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        agentId = try c.decode(String.self, forKey: .agentId); displayName = try c.decode(String.self, forKey: .displayName)
        kind = try c.decode(String.self, forKey: .kind); access = try c.decode(String.self, forKey: .access)
        brainKind = try c.decode(String.self, forKey: .brainKind); adapterId = try c.decode(String.self, forKey: .adapterId)
        enabled = try c.decode(Bool.self, forKey: .enabled); capabilities = try c.decode([String].self, forKey: .capabilities)
        authorizedProjectIds = try c.decode([String].self, forKey: .authorizedProjectIds)
        deviceId = try c.decodeIfPresent(String.self, forKey: .deviceId)
        credentialState = try c.decode(String.self, forKey: .credentialState)
        guard credentialState == "reauthorizationRequired" else { throw BundleV1Error.unsupportedFormatOrVersion }
    }
}

public struct BundleProjectHomeV1: Codable, Equatable {
    public let homeKind: String
    public let hostId: String
    public let pathHint: String
    public init(homeKind: String, hostId: String, pathHint: String) {
        self.homeKind = homeKind; self.hostId = hostId; self.pathHint = pathHint
    }
}

public struct BundleProjectV1: Codable, Equatable {
    public let projectId: String
    public let name: String
    public let repoUrl: String?
    public let defaultBranch: String
    public let homes: [BundleProjectHomeV1]
    public init(projectId: String, name: String, repoUrl: String? = nil, defaultBranch: String = "main", homes: [BundleProjectHomeV1] = []) {
        self.projectId = projectId; self.name = name; self.repoUrl = repoUrl; self.defaultBranch = defaultBranch; self.homes = homes
    }
}

public struct BundleBrainV1: Codable, Equatable {
    public let brainId: String
    public let kind: String
    public let adapterId: String
    public let label: String
    public let model: String
    public let baseUrl: String?
    public let fallbackBrainIds: [String]
    public let credentialState: String
    public init(brainId: String, kind: String, adapterId: String, label: String, model: String,
                baseUrl: String? = nil, fallbackBrainIds: [String] = []) {
        self.brainId = brainId; self.kind = kind; self.adapterId = adapterId; self.label = label
        self.model = model; self.baseUrl = baseUrl; self.fallbackBrainIds = fallbackBrainIds
        self.credentialState = "reauthorizationRequired"
    }
    private enum CodingKeys: String, CodingKey { case brainId, kind, adapterId, label, model, baseUrl, fallbackBrainIds, credentialState }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        brainId = try c.decode(String.self, forKey: .brainId); kind = try c.decode(String.self, forKey: .kind)
        adapterId = try c.decode(String.self, forKey: .adapterId); label = try c.decode(String.self, forKey: .label)
        model = try c.decode(String.self, forKey: .model); baseUrl = try c.decodeIfPresent(String.self, forKey: .baseUrl)
        fallbackBrainIds = try c.decode([String].self, forKey: .fallbackBrainIds)
        credentialState = try c.decode(String.self, forKey: .credentialState)
        guard credentialState == "reauthorizationRequired" else { throw BundleV1Error.unsupportedFormatOrVersion }
    }
}

/// Deliberately finite settings allowlist; S2 documents each source preference.
public struct BundleAppSettingsV1: Codable, Equatable {
    public var brainDisplayName: String?
    public init(brainDisplayName: String? = nil) {
        self.brainDisplayName = brainDisplayName
    }
}
