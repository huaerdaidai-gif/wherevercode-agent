import CryptoKit
import Foundation

/// Project registry, mirroring the Android baseline
/// `core/project/ProjectStore.kt` on main @4496658e.
///
/// A project is a logical identity (`Project`), independent of any
/// checkout. Its homes (`ProjectLocation`) are the places the code
/// actually lives: a device folder, a git host (GitHub/GitLab/
/// Gitee — the repo itself is the home, worked through the API,
/// never cloned), or a local folder. Switching the active home
/// never loses tasks or conversations (they key off projectId).

public struct Project: Equatable, Codable {
    /// ASCII slug generated from the name (see `slugProjectId`).
    public var projectId: String
    public var name: String
    /// Optional canonical git source (credential-free https URL).
    public var repoUrl: String?
    public var defaultBranch: String = "main"
    /// Canonical commit (40/64 lowercase hex) or nil when unverified.
    /// The App never verifies or invents it.
    public var canonicalCommit: String?
    public var origin: String = "REMOTE"
    public var createdAtEpochSeconds: Int64 = 0

    public init(projectId: String, name: String, repoUrl: String? = nil) {
        self.projectId = projectId
        self.name = name
        self.repoUrl = repoUrl
    }
}

public struct ProjectLocation: Equatable, Codable {
    public var locationId: String
    public var projectId: String
    /// Host device id; for git homes the site wire ("github" /
    /// "gitlab" / "gitee"), not a device.
    public var hostId: String
    /// Device homes: the folder's absolute path (normalized). Git
    /// homes: the namespace path ("owner/repo"; GitLab may nest).
    public var workspacePath: String
    public var sourceType: String = "REMOTE_DEVICE"
    public var origin: String = "REMOTE"
    public var availability: String = "AVAILABLE"
    public var lastSeenEpochSeconds: Int64 = 0
    /// DEVICE / GITHUB / GITLAB / GITEE / LOCAL.
    public var homeKind: String = "DEVICE"

    public static let githubHomeHostId = "github"
    public static let gitlabHomeHostId = "gitlab"
    public static let giteeHomeHostId = "gitee"

    public init(projectId: String, hostId: String, workspacePath: String, homeKind: String = "DEVICE") {
        self.locationId = projectLocationId(projectId: projectId, hostId: hostId, workspacePath: workspacePath)
        self.projectId = projectId
        self.hostId = hostId
        self.workspacePath = normalizeFolderPath(workspacePath)
        self.homeKind = homeKind
    }

    /// The git host wire for git homes, nil for device/local homes.
    public func gitHost() -> String? {
        switch homeKind {
        case "GITHUB": return Self.githubHomeHostId
        case "GITLAB": return Self.gitlabHomeHostId
        case "GITEE": return Self.giteeHomeHostId
        default: return nil
        }
    }
}

public struct ProjectWithHomes: Equatable {
    public var project: Project
    public var homes: [ProjectLocation]
    public var activeHome: ProjectLocation?
}

/// Folder normalization: trim whitespace, strip trailing "/"
/// (the root "/" itself is kept). Ported from the baseline.
public func normalizeFolderPath(_ folder: String) -> String {
    var result = folder.trimmingCharacters(in: .whitespacesAndNewlines)
    if result == "/" { return "/" }
    while result.hasSuffix("/") { result.removeLast() }
    return result.isEmpty && folder.contains("/") ? "/" : result
}

/// Deterministic location id: "loc-" + first 32 hex of SHA-256
/// over "projectId\0hostId\0normalizedPath" (UTF-8) — the same
/// derivation as the baseline, so the same triple is the same
/// location on both platforms.
public func projectLocationId(projectId: String, hostId: String, workspacePath: String) -> String {
    let material = "\(projectId)\u{0}\(hostId)\u{0}\(normalizeFolderPath(workspacePath))"
    let digest = SHA256.hash(data: Data(material.utf8))
    let hex = digest.map { String(format: "%02x", $0) }.joined()
    return "loc-" + String(hex.prefix(32))
}

/// Project name → ASCII slug; names with no ASCII letters/digits
/// fall back to "project". Ported from the baseline.
public func slugProjectId(_ name: String) -> String {
    let mapped = name.lowercased().map { character -> Character in
        if let ascii = character.asciiValue, CharacterSet.alphanumerics.contains(UnicodeScalar(ascii)) {
            return character
        }
        return "-"
    }
    var slug = ""
    var lastWasDash = true // also trims leading dashes
    for character in mapped {
        if character == "-" {
            if !lastWasDash { slug.append(character) }
            lastWasDash = true
        } else {
            slug.append(character)
            lastWasDash = false
        }
    }
    while slug.hasSuffix("-") { slug.removeLast() }
    return slug.isEmpty ? "project" : slug
}

/// Local-workspace path validity (baseline rule): relative, no
/// leading "/", no "", ".", ".." segments, no backslash or NUL.
public func isValidLocalWorkspacePath(_ path: String) -> Bool {
    if path.isEmpty || path.hasPrefix("/") { return false }
    if path.contains("\\") || path.contains("\u{0}") { return false }
    let parts = path.split(separator: "/", omittingEmptySubsequences: false)
    if parts.isEmpty { return false }
    return parts.allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
}

public final class ProjectStore {
    private let db: SQLiteDatabase
    private let kv: KeyValueStore

    public init(db: SQLiteDatabase, kv: KeyValueStore) {
        self.db = db
        self.kv = kv
    }

    public func saveProject(_ project: Project) throws {
        var project = project
        if project.createdAtEpochSeconds == 0 {
            project.createdAtEpochSeconds = Int64(Date().timeIntervalSince1970)
        }
        try db.execute(
            """
            INSERT INTO projects (projectId, name, repoUrl, defaultBranch, canonicalCommit, origin, createdAtEpochSeconds)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(projectId) DO UPDATE SET name = excluded.name, repoUrl = excluded.repoUrl,
                defaultBranch = excluded.defaultBranch, canonicalCommit = excluded.canonicalCommit,
                origin = excluded.origin
            """,
            [.text(project.projectId), .text(project.name),
             project.repoUrl.map(SQLiteValue.text) ?? .null,
             .text(project.defaultBranch),
             project.canonicalCommit.map(SQLiteValue.text) ?? .null,
             .text(project.origin), .int(project.createdAtEpochSeconds)])
    }

    public func getProject(projectId: String) throws -> ProjectWithHomes? {
        guard let project = try projectRow(projectId: projectId) else { return nil }
        let homes = try locations(projectId: projectId)
        return ProjectWithHomes(project: project, homes: homes, activeHome: activeHome(of: homes, projectId: projectId))
    }

    public func listProjects() throws -> [ProjectWithHomes] {
        let ids = try db.query("SELECT projectId FROM projects ORDER BY createdAtEpochSeconds ASC, rowid ASC")
            .compactMap { $0["projectId"]?.textValue }
        return try ids.compactMap { try getProject(projectId: $0) }
    }

    public func deleteProject(projectId: String) throws {
        try db.transaction {
            try db.execute("DELETE FROM project_locations WHERE projectId = ?", [.text(projectId)])
            try db.execute("DELETE FROM projects WHERE projectId = ?", [.text(projectId)])
            try kv.set(activeHomeKey(projectId), nil)
        }
    }

    @discardableResult
    public func addLocation(_ location: ProjectLocation) throws -> ProjectLocation {
        var location = location
        location.workspacePath = normalizeFolderPath(location.workspacePath)
        location.locationId = projectLocationId(
            projectId: location.projectId, hostId: location.hostId, workspacePath: location.workspacePath)
        try db.execute(
            """
            INSERT INTO project_locations
            (locationId, projectId, hostId, workspacePath, sourceType, origin, availability, lastSeenEpochSeconds, homeKind)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(locationId) DO UPDATE SET availability = excluded.availability,
                lastSeenEpochSeconds = excluded.lastSeenEpochSeconds
            """,
            [.text(location.locationId), .text(location.projectId), .text(location.hostId),
             .text(location.workspacePath), .text(location.sourceType), .text(location.origin),
             .text(location.availability), .int(location.lastSeenEpochSeconds), .text(location.homeKind)])
        return location
    }

    public func removeLocation(locationId: String) throws {
        try db.execute("DELETE FROM project_locations WHERE locationId = ?", [.text(locationId)])
    }

    public func setActiveHome(projectId: String, locationId: String?) throws {
        try kv.set(activeHomeKey(projectId), locationId)
    }

    private func activeHomeKey(_ projectId: String) -> String { "project.activeHome.\(projectId)" }

    private func activeHome(of homes: [ProjectLocation], projectId: String) -> ProjectLocation? {
        if let locationId = try? kv.get(activeHomeKey(projectId)),
           let match = homes.first(where: { $0.locationId == locationId }) {
            return match
        }
        return homes.first
    }

    private func projectRow(projectId: String) throws -> Project? {
        try db.query("SELECT * FROM projects WHERE projectId = ?", [.text(projectId)]).first.map { row in
            var project = Project(
                projectId: row["projectId"]?.textValue ?? "",
                name: row["name"]?.textValue ?? "",
                repoUrl: row["repoUrl"]?.textValue)
            project.defaultBranch = row["defaultBranch"]?.textValue ?? "main"
            project.canonicalCommit = row["canonicalCommit"]?.textValue
            project.origin = row["origin"]?.textValue ?? "REMOTE"
            project.createdAtEpochSeconds = row["createdAtEpochSeconds"]?.intValue ?? 0
            return project
        }
    }

    private func locations(projectId: String) throws -> [ProjectLocation] {
        try db.query(
            "SELECT * FROM project_locations WHERE projectId = ? ORDER BY rowid ASC",
            [.text(projectId)]).map { row in
                var location = ProjectLocation(
                    projectId: row["projectId"]?.textValue ?? "",
                    hostId: row["hostId"]?.textValue ?? "",
                    workspacePath: row["workspacePath"]?.textValue ?? "",
                    homeKind: row["homeKind"]?.textValue ?? "DEVICE")
                location.locationId = row["locationId"]?.textValue ?? location.locationId
                location.sourceType = row["sourceType"]?.textValue ?? "REMOTE_DEVICE"
                location.origin = row["origin"]?.textValue ?? "REMOTE"
                location.availability = row["availability"]?.textValue ?? "AVAILABLE"
                location.lastSeenEpochSeconds = row["lastSeenEpochSeconds"]?.intValue ?? 0
                return location
            }
    }
}

/// The active git home of a project, when the CURRENT home is a
/// git home (baseline rule, 2026-11-12): dispatch routes by the
/// current home, never by "the project has some git home".
public func activeGitHome(_ projectWithHomes: ProjectWithHomes) -> (Project, ProjectLocation)? {
    guard let active = projectWithHomes.activeHome, active.gitHost() != nil else { return nil }
    return (projectWithHomes.project, active)
}
