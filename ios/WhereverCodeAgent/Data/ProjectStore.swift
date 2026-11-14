import Foundation

public struct Project: Codable, Equatable, Identifiable, Sendable {
    public var id: String { projectId }
    public let projectId: String
    public var name: String
    public var repoUrl: String?
    public var defaultBranch: String
    public var canonicalCommit: String?
    public var origin: String
    public var createdAtEpochSeconds: Int64

    public init(
        projectId: String = UUID().uuidString,
        name: String,
        repoUrl: String? = nil,
        defaultBranch: String = "main",
        canonicalCommit: String? = nil,
        origin: String = "LOCAL_SANDBOX",
        createdAtEpochSeconds: Int64 = Int64(Date().timeIntervalSince1970)
    ) {
        self.projectId = projectId
        self.name = name
        self.repoUrl = repoUrl
        self.defaultBranch = defaultBranch
        self.canonicalCommit = canonicalCommit
        self.origin = origin
        self.createdAtEpochSeconds = createdAtEpochSeconds
    }
}

public struct ProjectLocation: Codable, Equatable, Identifiable, Sendable {
    public var id: String { locationId }
    public let locationId: String
    public let projectId: String
    public var hostId: String
    public var workspacePath: String
    public var sourceType: String
    public var origin: String
    public var availability: String
    public var lastSeenEpochSeconds: Int64
    public var homeKind: String

    public init(
        locationId: String = UUID().uuidString,
        projectId: String,
        hostId: String = "iphone-local",
        workspacePath: String,
        sourceType: String = "IOS_SANDBOX_BOOKMARK",
        origin: String = "LOCAL",
        availability: String = "AVAILABLE",
        lastSeenEpochSeconds: Int64 = Int64(Date().timeIntervalSince1970),
        homeKind: String = "DEVICE"
    ) {
        self.locationId = locationId
        self.projectId = projectId
        self.hostId = hostId
        self.workspacePath = workspacePath
        self.sourceType = sourceType
        self.origin = origin
        self.availability = availability
        self.lastSeenEpochSeconds = lastSeenEpochSeconds
        self.homeKind = homeKind
    }
}

public struct ProjectWithHomes: Codable, Equatable, Identifiable, Sendable {
    public var id: String { project.projectId }
    public let project: Project
    public let homes: [ProjectLocation]
    public let activeHome: ProjectLocation?

    public init(project: Project, homes: [ProjectLocation], activeHome: ProjectLocation?) {
        self.project = project
        self.homes = homes
        self.activeHome = activeHome
    }
}

public final class ProjectStore {
    private let db: SQLiteDatabase
    private let kv: KeyValueStore

    public init(db: SQLiteDatabase, kv: KeyValueStore) throws {
        self.db = db
        self.kv = kv
        try db.execute("""
        CREATE TABLE IF NOT EXISTS projects (
            projectId TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            repoUrl TEXT,
            defaultBranch TEXT NOT NULL DEFAULT 'main',
            canonicalCommit TEXT,
            origin TEXT NOT NULL DEFAULT 'REMOTE',
            createdAtEpochSeconds INTEGER NOT NULL DEFAULT 0
        )
        """)
        try db.execute("""
        CREATE TABLE IF NOT EXISTS project_locations (
            locationId TEXT PRIMARY KEY,
            projectId TEXT NOT NULL,
            hostId TEXT NOT NULL,
            workspacePath TEXT NOT NULL,
            sourceType TEXT NOT NULL DEFAULT 'REMOTE_DEVICE',
            origin TEXT NOT NULL DEFAULT 'REMOTE',
            availability TEXT NOT NULL DEFAULT 'AVAILABLE',
            lastSeenEpochSeconds INTEGER NOT NULL DEFAULT 0,
            homeKind TEXT NOT NULL DEFAULT 'DEVICE'
        )
        """)
        try db.execute("CREATE INDEX IF NOT EXISTS index_project_locations_projectId ON project_locations (projectId)")
    }

    public func saveProject(_ project: Project) throws {
        try db.execute(
            """
            INSERT INTO projects (projectId, name, repoUrl, defaultBranch, canonicalCommit, origin, createdAtEpochSeconds)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(projectId) DO UPDATE SET name = excluded.name, repoUrl = excluded.repoUrl,
                defaultBranch = excluded.defaultBranch, canonicalCommit = excluded.canonicalCommit,
                origin = excluded.origin
            """,
            parameters: [
                .text(project.projectId),
                .text(project.name),
                project.repoUrl.map(SQLiteValue.text) ?? .null,
                .text(project.defaultBranch),
                project.canonicalCommit.map(SQLiteValue.text) ?? .null,
                .text(project.origin),
                .int(project.createdAtEpochSeconds)
            ]
        )
    }

    public func addLocation(_ location: ProjectLocation) throws -> ProjectLocation {
        try db.execute(
            """
            INSERT INTO project_locations
            (locationId, projectId, hostId, workspacePath, sourceType, origin, availability, lastSeenEpochSeconds, homeKind)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(locationId) DO UPDATE SET availability = excluded.availability,
                lastSeenEpochSeconds = excluded.lastSeenEpochSeconds
            """,
            parameters: [
                .text(location.locationId),
                .text(location.projectId),
                .text(location.hostId),
                .text(location.workspacePath),
                .text(location.sourceType),
                .text(location.origin),
                .text(location.availability),
                .int(location.lastSeenEpochSeconds),
                .text(location.homeKind)
            ]
        )
        return location
    }

    public func setActiveHome(projectId: String, locationId: String?) throws {
        try kv.set("project.activeHome.\(projectId)", locationId)
    }

    public func listProjects() throws -> [ProjectWithHomes] {
        let rows = try db.query("SELECT projectId FROM projects ORDER BY createdAtEpochSeconds ASC, rowid ASC")
        return try rows.compactMap { row in
            guard let pid = row["projectId"]?.stringValue else { return nil }
            return try getProject(projectId: pid)
        }
    }

    public func getProject(projectId: String) throws -> ProjectWithHomes? {
        guard let proj = try projectRow(projectId: projectId) else { return nil }
        let locs = try locations(projectId: projectId)
        let active = activeHome(of: locs, projectId: projectId)
        return ProjectWithHomes(project: proj, homes: locs, activeHome: active)
    }

    public func deleteProject(projectId: String) throws {
        try db.inTransaction {
            try db.execute("DELETE FROM project_locations WHERE projectId = ?", parameters: [.text(projectId)])
            try db.execute("DELETE FROM projects WHERE projectId = ?", parameters: [.text(projectId)])
            try kv.set("project.activeHome.\(projectId)", nil)
        }
    }

    public func removeLocation(locationId: String) throws {
        try db.execute("DELETE FROM project_locations WHERE locationId = ?", parameters: [.text(locationId)])
    }

    private func projectRow(projectId: String) throws -> Project? {
        let rows = try db.query("SELECT * FROM projects WHERE projectId = ?", parameters: [.text(projectId)])
        guard let r = rows.first else { return nil }
        return Project(
            projectId: r["projectId"]?.stringValue ?? projectId,
            name: r["name"]?.stringValue ?? "",
            repoUrl: r["repoUrl"]?.stringValue,
            defaultBranch: r["defaultBranch"]?.stringValue ?? "main",
            canonicalCommit: r["canonicalCommit"]?.stringValue,
            origin: r["origin"]?.stringValue ?? "REMOTE",
            createdAtEpochSeconds: r["createdAtEpochSeconds"]?.int64Value ?? 0
        )
    }

    private func locations(projectId: String) throws -> [ProjectLocation] {
        let rows = try db.query("SELECT * FROM project_locations WHERE projectId = ? ORDER BY rowid ASC", parameters: [.text(projectId)])
        return rows.map { r in
            ProjectLocation(
                locationId: r["locationId"]?.stringValue ?? "",
                projectId: r["projectId"]?.stringValue ?? projectId,
                hostId: r["hostId"]?.stringValue ?? "",
                workspacePath: r["workspacePath"]?.stringValue ?? "",
                sourceType: r["sourceType"]?.stringValue ?? "REMOTE_DEVICE",
                origin: r["origin"]?.stringValue ?? "REMOTE",
                availability: r["availability"]?.stringValue ?? "AVAILABLE",
                lastSeenEpochSeconds: r["lastSeenEpochSeconds"]?.int64Value ?? 0,
                homeKind: r["homeKind"]?.stringValue ?? "DEVICE"
            )
        }
    }

    private func activeHome(of homes: [ProjectLocation], projectId: String) -> ProjectLocation? {
        if let activeId = try? kv.get("project.activeHome.\(projectId)"),
           let match = homes.first(where: { $0.locationId == activeId }) {
            return match
        }
        return homes.first
    }
}
