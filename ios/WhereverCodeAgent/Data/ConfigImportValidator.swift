import Foundation

public enum ImportPlanAction: String, Equatable { case add, unchanged, conflict }
public struct PlannedImportItem: Equatable {
    public let category: String
    public let id: String
    public let action: ImportPlanAction
    public let conflictsWith: [String]
    public init(category: String, id: String, action: ImportPlanAction, conflictsWith: [String] = []) {
        self.category = category; self.id = id; self.action = action; self.conflictsWith = conflictsWith
    }
}
public struct ImportPlan: Equatable {
    public let bundleId: String
    public let bundleNonce: String
    public let items: [PlannedImportItem]
    public let warnings: [String]
    public var addCount: Int { items.filter { $0.action == .add }.count }
    public var unchangedCount: Int { items.filter { $0.action == .unchanged }.count }
    public var conflictCount: Int { items.filter { $0.action == .conflict }.count }
}
public struct ConfigImportRejected: Error, Equatable {
    public let code: String
    public init(_ code: String) { self.code = code }
}

/// Side-effect-free schema/security validation and dry-run diff planner.
public enum ConfigImportValidator {
    public static let maxJSONBytes = 2_000_000
    private static let sensitiveKey = try! NSRegularExpression(pattern: "(?i)(api[_-]?key|token|secret|auth[_-]?ref|password|passwd|private[_-]?key|credential(value)?)")
    private static let secretValue = try! NSRegularExpression(pattern: "(?i)(sk-[A-Za-z0-9_-]{12,}|Bearer\\s+\\S+|-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----)")

    public static func validate(_ data: Data, existing: BundlePayloadV1 = .init()) throws -> ImportPlan {
        guard data.count <= maxJSONBytes else { throw ConfigImportRejected("sizeLimit") }
        let raw: Any
        do { raw = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) }
        catch { throw ConfigImportRejected("malformedJson") }
        try scan(raw)
        guard let header = raw as? [String: Any], header["format"] as? String == ConfigBundleV1.formatName,
              (header["schemaVersion"] as? Int) == ConfigBundleV1.currentSchemaVersion else {
            throw ConfigImportRejected("unsupportedSchema")
        }
        guard header["secretsPolicy"] as? String == ConfigBundleV1.nonSecretOnlyPolicy else {
            throw ConfigImportRejected("secretsPolicyViolation")
        }
        let bundle: ConfigBundleV1
        do { bundle = try ConfigBundleV1.decode(data) }
        catch { throw ConfigImportRejected("invalidSchema") }
        guard !bundle.bundleId.isEmpty, bundle.bundleNonce.range(of: "^[A-Za-z0-9_-]{16,128}$", options: .regularExpression) != nil else {
            throw ConfigImportRejected("invalidMetadata")
        }
        guard ["android", "ios"].contains(bundle.sourcePlatform) else { throw ConfigImportRejected("invalidMetadata") }
        guard bundle.secretsPolicy == ConfigBundleV1.nonSecretOnlyPolicy else { throw ConfigImportRejected("secretsPolicyViolation") }
        let p = bundle.payload
        try unique(p.devices.map(\.sourceDeviceId), "duplicateDeviceId")
        try unique(p.agents.map(\.agentId), "duplicateAgentId")
        try unique(p.projects.map(\.projectId), "duplicateProjectId")
        try unique(p.brains.map(\.brainId), "duplicateBrainId")
        let deviceIds = Set(p.devices.map(\.sourceDeviceId))
        let projectIds = Set(p.projects.map(\.projectId))
        let brainIds = Set(p.brains.map(\.brainId))
        for agent in p.agents {
            if let deviceId = agent.deviceId, !deviceIds.contains(deviceId) { throw ConfigImportRejected("unclosedReference") }
            if agent.authorizedProjectIds.contains(where: { !projectIds.contains($0) }) { throw ConfigImportRejected("unclosedReference") }
        }
        for brain in p.brains {
            if brain.fallbackBrainIds.contains(where: { !brainIds.contains($0) }) { throw ConfigImportRejected("unclosedReference") }
            try rejectUserInfo(brain.baseUrl)
        }
        if let current = p.currentBrainId, !brainIds.contains(current) { throw ConfigImportRejected("unclosedReference") }
        for project in p.projects {
            try rejectUserInfo(project.repoUrl)
            for home in project.homes {
                if home.homeKind.caseInsensitiveCompare("DEVICE") == .orderedSame && !deviceIds.contains(home.hostId) {
                    throw ConfigImportRejected("unclosedReference")
                }
                if home.pathHint.contains("\0") || home.pathHint.replacingOccurrences(of: "\\", with: "/").split(separator: "/").contains("..") {
                    throw ConfigImportRejected("unsafePath")
                }
            }
        }
        var warnings: [String] = []
        for device in p.devices where !device.host.isEmpty { warnings.append("Device endpoint requires user review: \(device.sourceDeviceId)") }
        for project in p.projects where project.homes.contains(where: { !$0.pathHint.isEmpty }) {
            warnings.append("Project path hint requires local rebinding: \(project.projectId)")
        }
        return ImportPlan(bundleId: bundle.bundleId, bundleNonce: bundle.bundleNonce,
                          items: makeItems(p, existing), warnings: warnings)
    }

    private static func scan(_ value: Any, key: String? = nil, depth: Int = 0) throws {
        guard depth <= 64 else { throw ConfigImportRejected("nestingLimit") }
        if let key, !["secretsPolicy", "credentialState", "pairingState"].contains(key), matches(sensitiveKey, key) {
            throw ConfigImportRejected("secretDetected")
        }
        if let dictionary = value as? [String: Any] {
            for (child, item) in dictionary { try scan(item, key: child, depth: depth + 1) }
        } else if let array = value as? [Any] {
            for item in array { try scan(item, key: key, depth: depth + 1) }
        } else if let string = value as? String, matches(secretValue, string) {
            throw ConfigImportRejected("secretDetected")
        }
    }
    private static func matches(_ regex: NSRegularExpression, _ value: String) -> Bool {
        regex.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)) != nil
    }
    private static func unique(_ values: [String], _ code: String) throws {
        guard values.allSatisfy({ !$0.isEmpty }), Set(values).count == values.count else { throw ConfigImportRejected(code) }
    }
    private static func rejectUserInfo(_ value: String?) throws {
        guard let value else { return }
        guard let components = URLComponents(string: value) else { throw ConfigImportRejected("invalidUrl") }
        if components.user != nil || components.password != nil { throw ConfigImportRejected("urlUserInfo") }
    }
    private static func makeItems(_ incoming: BundlePayloadV1, _ existing: BundlePayloadV1) -> [PlannedImportItem] {
        var result: [PlannedImportItem] = []
        func append<T: Equatable>(_ category: String, _ new: [T], _ old: [T], _ id: (T) -> String, _ name: (T) -> String) {
            let prior = Dictionary(old.map { (id($0), $0) }, uniquingKeysWith: { first, _ in first })
            for item in new {
                let key = id(item)
                let action: ImportPlanAction
                if let old = prior[key] { action = old == item ? .unchanged : .conflict } else { action = .add }
                let nameMatches = old.filter { id($0) != key && name($0).caseInsensitiveCompare(name(item)) == .orderedSame }.map(id)
                let edges = action == .unchanged ? [] : (prior[key] != nil ? [key] : nameMatches)
                let finalAction: ImportPlanAction = action == .add && !nameMatches.isEmpty ? .conflict : action
                result.append(PlannedImportItem(category: category, id: key, action: finalAction, conflictsWith: edges))
            }
        }
        append("device", incoming.devices, existing.devices, \.sourceDeviceId, \.displayName)
        append("agent", incoming.agents, existing.agents, \.agentId, \.displayName)
        append("project", incoming.projects, existing.projects, \.projectId, \.name)
        append("brain", incoming.brains, existing.brains, \.brainId, \.label)
        if incoming.appSettings != BundleAppSettingsV1() {
            let action: ImportPlanAction = existing.appSettings == BundleAppSettingsV1() ? .add : incoming.appSettings == existing.appSettings ? .unchanged : .conflict
            result.append(PlannedImportItem(category: "appSettings", id: "brainDisplayName", action: action))
        }
        return result
    }
}
