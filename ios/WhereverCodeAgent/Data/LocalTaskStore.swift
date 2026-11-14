import Foundation

public struct LocalTask: Codable, Equatable, Identifiable, Sendable {
    public var id: String { taskId }
    public let taskId: String
    public var projectId: String?
    public var sessionId: String?
    public var title: String
    public var description: String?
    public var state: String
    public var assignedAgentId: String?
    public var parentTaskId: String?
    public var dependencies: [String]
    public var priority: Int
    public var createdAt: Int64
    public var startedAt: Int64?
    public var finishedAt: Int64?
    public var updatedAt: Int64
    public var result: String?
    public var error: String?
    public var version: Int
    public var brainId: String?
    public var needsAttention: Bool
    public var isPaused: Bool
    public var leaseOwner: String?
    public var leaseExpiresAt: Int64?
    public var memberId: String?
    public var attempt: Int
    public var attemptId: String?
    public var goalVersion: Int
    public var authEpoch: Int

    public init(
        taskId: String = UUID().uuidString,
        projectId: String? = nil,
        sessionId: String? = nil,
        title: String,
        description: String? = nil,
        state: String = "queued",
        assignedAgentId: String? = nil,
        parentTaskId: String? = nil,
        dependencies: [String] = [],
        priority: Int = 0,
        createdAt: Int64 = Int64(Date().timeIntervalSince1970),
        startedAt: Int64? = nil,
        finishedAt: Int64? = nil,
        updatedAt: Int64 = Int64(Date().timeIntervalSince1970),
        result: String? = nil,
        error: String? = nil,
        version: Int = 1,
        brainId: String? = nil,
        needsAttention: Bool = false,
        isPaused: Bool = false,
        leaseOwner: String? = nil,
        leaseExpiresAt: Int64? = nil,
        memberId: String? = nil,
        attempt: Int = 0,
        attemptId: String? = nil,
        goalVersion: Int = 1,
        authEpoch: Int = 1
    ) {
        self.taskId = taskId
        self.projectId = projectId
        self.sessionId = sessionId
        self.title = title
        self.description = description
        self.state = state
        self.assignedAgentId = assignedAgentId
        self.parentTaskId = parentTaskId
        self.dependencies = dependencies
        self.priority = priority
        self.createdAt = createdAt
        self.startedAt = startedAt
        self.finishedAt = finishedAt
        self.updatedAt = updatedAt
        self.result = result
        self.error = error
        self.version = version
        self.brainId = brainId
        self.needsAttention = needsAttention
        self.isPaused = isPaused
        self.leaseOwner = leaseOwner
        self.leaseExpiresAt = leaseExpiresAt
        self.memberId = memberId
        self.attempt = attempt
        self.attemptId = attemptId
        self.goalVersion = goalVersion
        self.authEpoch = authEpoch
    }
}

public struct TaskEvent: Codable, Equatable, Identifiable, Sendable {
    public let id: Int64
    public let taskId: String
    public let kind: String
    public let fromState: String?
    public let toState: String?
    public let note: String?
    public let createdAtEpochSeconds: Int64

    public init(
        id: Int64 = 0,
        taskId: String,
        kind: String,
        fromState: String? = nil,
        toState: String? = nil,
        note: String? = nil,
        createdAtEpochSeconds: Int64 = Int64(Date().timeIntervalSince1970)
    ) {
        self.id = id
        self.taskId = taskId
        self.kind = kind
        self.fromState = fromState
        self.toState = toState
        self.note = note
        self.createdAtEpochSeconds = createdAtEpochSeconds
    }
}

public enum LocalTaskStoreError: Error, LocalizedError, Equatable {
    case duplicateTask(String)
    case unknownTask(String)

    public var errorDescription: String? {
        switch self {
        case .duplicateTask(let id): return "任务已存在：\(id)"
        case .unknownTask(let id): return "找不到任务：\(id)"
        }
    }
}

public final class LocalTaskStore {
    private let db: SQLiteDatabase

    public init(db: SQLiteDatabase) throws {
        self.db = db
        try db.execute("""
        CREATE TABLE IF NOT EXISTS tasks (
            taskId TEXT PRIMARY KEY,
            projectId TEXT,
            sessionId TEXT,
            title TEXT NOT NULL DEFAULT '',
            description TEXT,
            state TEXT NOT NULL DEFAULT 'queued',
            assignedAgentId TEXT,
            parentTaskId TEXT,
            dependenciesJson TEXT NOT NULL DEFAULT '[]',
            priority INTEGER NOT NULL DEFAULT 0,
            createdAt INTEGER NOT NULL DEFAULT 0,
            startedAt INTEGER,
            finishedAt INTEGER,
            updatedAt INTEGER NOT NULL DEFAULT 0,
            result TEXT,
            error TEXT,
            version INTEGER NOT NULL DEFAULT 1,
            brainId TEXT,
            needsAttention INTEGER NOT NULL DEFAULT 0,
            leaseOwner TEXT,
            leaseExpiresAt INTEGER,
            memberId TEXT,
            attempt INTEGER NOT NULL DEFAULT 0,
            attemptId TEXT,
            goalVersion INTEGER NOT NULL DEFAULT 1,
            authEpoch INTEGER NOT NULL DEFAULT 1
        )
        """)
        try db.execute("""
        CREATE TABLE IF NOT EXISTS task_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            taskId TEXT NOT NULL,
            kind TEXT NOT NULL,
            fromState TEXT,
            toState TEXT,
            note TEXT,
            createdAtEpochSeconds INTEGER NOT NULL
        )
        """)
        try db.execute("CREATE INDEX IF NOT EXISTS index_task_events_taskId_createdAtEpochSeconds ON task_events (taskId, createdAtEpochSeconds)")
    }

    public func createTask(_ task: LocalTask) throws -> LocalTask {
        guard TaskStates.canonicalStates.contains(task.state) else {
            throw TaskStates.TaskStateError.unknownCanonicalState(task.state)
        }
        return try db.inTransaction {
            if try getTask(taskId: task.taskId) != nil {
                throw LocalTaskStoreError.duplicateTask(task.taskId)
            }
            let depsJson = String(data: (try? JSONEncoder().encode(task.dependencies)) ?? Data("[]".utf8), encoding: .utf8) ?? "[]"
            try db.execute(
                """
                INSERT INTO tasks (taskId, projectId, sessionId, title, description, state, assignedAgentId,
                parentTaskId, dependenciesJson, priority, createdAt, startedAt, finishedAt,
                updatedAt, result, error, brainId, needsAttention, leaseOwner, leaseExpiresAt,
                memberId, attempt, attemptId, goalVersion, authEpoch, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                parameters: [
                    .text(task.taskId),
                    task.projectId.map(SQLiteValue.text) ?? .null,
                    task.sessionId.map(SQLiteValue.text) ?? .null,
                    .text(task.title),
                    task.description.map(SQLiteValue.text) ?? .null,
                    .text(task.state),
                    task.assignedAgentId.map(SQLiteValue.text) ?? .null,
                    task.parentTaskId.map(SQLiteValue.text) ?? .null,
                    .text(depsJson),
                    .int(Int64(task.priority)),
                    .int(task.createdAt),
                    task.startedAt.map(SQLiteValue.int) ?? .null,
                    task.finishedAt.map(SQLiteValue.int) ?? .null,
                    .int(task.updatedAt),
                    task.result.map(SQLiteValue.text) ?? .null,
                    task.error.map(SQLiteValue.text) ?? .null,
                    task.brainId.map(SQLiteValue.text) ?? .null,
                    .int(task.needsAttention ? 1 : 0),
                    task.leaseOwner.map(SQLiteValue.text) ?? .null,
                    task.leaseExpiresAt.map(SQLiteValue.int) ?? .null,
                    task.memberId.map(SQLiteValue.text) ?? .null,
                    .int(Int64(task.attempt)),
                    task.attemptId.map(SQLiteValue.text) ?? .null,
                    .int(Int64(task.goalVersion)),
                    .int(Int64(task.authEpoch)),
                    .int(Int64(task.version))
                ]
            )
            try appendEventLocked(TaskEvent(taskId: task.taskId, kind: "CREATED", fromState: nil, toState: task.state, note: task.title))
            return task
        }
    }

    public func getTask(taskId: String) throws -> LocalTask? {
        let rows = try db.query("SELECT * FROM tasks WHERE taskId = ?", parameters: [.text(taskId)])
        guard let row = rows.first else { return nil }
        return task(from: row)
    }

    public func listTasks(projectId: String? = nil, sessionId: String? = nil) throws -> [LocalTask] {
        let rows = try db.query(
            """
            SELECT * FROM tasks
            WHERE (? IS NULL OR projectId = ?) AND (? IS NULL OR sessionId = ?)
            ORDER BY createdAt ASC, rowid ASC
            """,
            parameters: [
                projectId.map(SQLiteValue.text) ?? .null,
                projectId.map(SQLiteValue.text) ?? .null,
                sessionId.map(SQLiteValue.text) ?? .null,
                sessionId.map(SQLiteValue.text) ?? .null
            ]
        )
        return rows.map(task(from:))
    }

    public func transition(taskId: String, to nextState: String, note: String? = nil) throws -> LocalTask {
        try db.inTransaction {
            guard var current = try getTask(taskId: taskId) else {
                throw LocalTaskStoreError.unknownTask(taskId)
            }
            try TaskStates.validateTransition(from: current.state, to: nextState)
            let now = Int64(Date().timeIntervalSince1970)
            let started = current.startedAt ?? (nextState == "working" ? now : nil)
            let finished = TaskStates.terminalStates.contains(nextState) ? now : current.finishedAt
            try db.execute(
                "UPDATE tasks SET state = ?, startedAt = ?, finishedAt = ?, updatedAt = ?, version = version + 1 WHERE taskId = ?",
                parameters: [
                    .text(nextState),
                    started.map(SQLiteValue.int) ?? .null,
                    finished.map(SQLiteValue.int) ?? .null,
                    .int(now),
                    .text(taskId)
                ]
            )
            try appendEventLocked(TaskEvent(
                taskId: taskId,
                kind: "STATE_TRANSITION",
                fromState: current.state,
                toState: nextState,
                note: note,
                createdAtEpochSeconds: now
            ))
            current.state = nextState
            current.startedAt = started
            current.finishedAt = finished
            current.updatedAt = now
            current.version += 1
            return current
        }
    }

    public func updateTask(_ task: LocalTask) throws {
        let depsJson = String(data: (try? JSONEncoder().encode(task.dependencies)) ?? Data("[]".utf8), encoding: .utf8) ?? "[]"
        try db.execute(
            """
            UPDATE tasks SET projectId = ?, sessionId = ?, title = ?, description = ?,
            state = ?, assignedAgentId = ?, parentTaskId = ?, dependenciesJson = ?,
            priority = ?, createdAt = ?, startedAt = ?, finishedAt = ?, updatedAt = ?,
            result = ?, error = ?, version = version + 1, brainId = ?, needsAttention = ?,
            leaseOwner = ?, leaseExpiresAt = ?, memberId = ?, attempt = ?, attemptId = ?,
            goalVersion = ?, authEpoch = ? WHERE taskId = ?
            """,
            parameters: [
                task.projectId.map(SQLiteValue.text) ?? .null,
                task.sessionId.map(SQLiteValue.text) ?? .null,
                .text(task.title),
                task.description.map(SQLiteValue.text) ?? .null,
                .text(task.state),
                task.assignedAgentId.map(SQLiteValue.text) ?? .null,
                task.parentTaskId.map(SQLiteValue.text) ?? .null,
                .text(depsJson),
                .int(Int64(task.priority)),
                .int(task.createdAt),
                task.startedAt.map(SQLiteValue.int) ?? .null,
                task.finishedAt.map(SQLiteValue.int) ?? .null,
                .int(Int64(Date().timeIntervalSince1970)),
                task.result.map(SQLiteValue.text) ?? .null,
                task.error.map(SQLiteValue.text) ?? .null,
                task.brainId.map(SQLiteValue.text) ?? .null,
                .int(task.needsAttention ? 1 : 0),
                task.leaseOwner.map(SQLiteValue.text) ?? .null,
                task.leaseExpiresAt.map(SQLiteValue.int) ?? .null,
                task.memberId.map(SQLiteValue.text) ?? .null,
                .int(Int64(task.attempt)),
                task.attemptId.map(SQLiteValue.text) ?? .null,
                .int(Int64(task.goalVersion)),
                .int(Int64(task.authEpoch)),
                .text(task.taskId)
            ]
        )
    }

    public func listEvents(taskId: String) throws -> [TaskEvent] {
        let rows = try db.query(
            "SELECT * FROM task_events WHERE taskId = ? ORDER BY createdAtEpochSeconds ASC, id ASC",
            parameters: [.text(taskId)]
        )
        return rows.map { row in
            TaskEvent(
                id: row["id"]?.int64Value ?? 0,
                taskId: row["taskId"]?.stringValue ?? taskId,
                kind: row["kind"]?.stringValue ?? "",
                fromState: row["fromState"]?.stringValue,
                toState: row["toState"]?.stringValue,
                note: row["note"]?.stringValue,
                createdAtEpochSeconds: row["createdAtEpochSeconds"]?.int64Value ?? 0
            )
        }
    }

    private func appendEventLocked(_ event: TaskEvent) throws {
        try db.execute(
            """
            INSERT INTO task_events (taskId, kind, fromState, toState, note, createdAtEpochSeconds)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            parameters: [
                .text(event.taskId),
                .text(event.kind),
                event.fromState.map(SQLiteValue.text) ?? .null,
                event.toState.map(SQLiteValue.text) ?? .null,
                event.note.map(SQLiteValue.text) ?? .null,
                .int(event.createdAtEpochSeconds)
            ]
        )
    }

    private func task(from row: [String: SQLiteValue]) -> LocalTask {
        let depsStr = row["dependenciesJson"]?.stringValue ?? "[]"
        let deps = (try? JSONDecoder().decode([String].self, from: Data(depsStr.utf8))) ?? []
        return LocalTask(
            taskId: row["taskId"]?.stringValue ?? "",
            projectId: row["projectId"]?.stringValue,
            sessionId: row["sessionId"]?.stringValue,
            title: row["title"]?.stringValue ?? "",
            description: row["description"]?.stringValue,
            state: row["state"]?.stringValue ?? "queued",
            assignedAgentId: row["assignedAgentId"]?.stringValue,
            parentTaskId: row["parentTaskId"]?.stringValue,
            dependencies: deps,
            priority: Int(row["priority"]?.int64Value ?? 0),
            createdAt: row["createdAt"]?.int64Value ?? 0,
            startedAt: row["startedAt"]?.int64Value,
            finishedAt: row["finishedAt"]?.int64Value,
            updatedAt: row["updatedAt"]?.int64Value ?? 0,
            result: row["result"]?.stringValue,
            error: row["error"]?.stringValue,
            version: Int(row["version"]?.int64Value ?? 1),
            brainId: row["brainId"]?.stringValue,
            needsAttention: (row["needsAttention"]?.int64Value ?? 0) != 0,
            isPaused: false,
            leaseOwner: row["leaseOwner"]?.stringValue,
            leaseExpiresAt: row["leaseExpiresAt"]?.int64Value,
            memberId: row["memberId"]?.stringValue,
            attempt: Int(row["attempt"]?.int64Value ?? 0),
            attemptId: row["attemptId"]?.stringValue,
            goalVersion: Int(row["goalVersion"]?.int64Value ?? 1),
            authEpoch: Int(row["authEpoch"]?.int64Value ?? 1)
        )
    }
}
