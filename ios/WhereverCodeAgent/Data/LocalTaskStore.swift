import Foundation

/// Local task store, mirroring the Android baseline
/// `core/task/TaskStore.kt` (Room `tasks` table) + `TaskEventLog.kt`
/// (`task_events` audit table) on main @4496658e.
///
/// - The `tasks` columns mirror TaskEntity one for one; timestamps
///   are epoch seconds (Android stores Long millis in some columns
///   and seconds in others; iOS normalizes on seconds and says so).
/// - Every state change is validated by `TaskStates.checkTransition`
///   and writes a task_events row in the same transaction — the
///   audit trail is never silent.
/// - `version` is bumped on every update (optimistic concurrency,
///   same as the Android DAO's `version = version + 1`).
public struct LocalTask: Equatable, Codable, Identifiable, Sendable {
    public var id: String { taskId }
    public var taskId: String
    public var projectId: String?
    public var sessionId: String?
    public var title: String = ""
    public var description: String?
    public var state: String = "queued"
    public var assignedAgentId: String?
    public var parentTaskId: String?
    public var dependencies: [String] = []
    public var priority: Int = 0
    public var createdAt: Int64 = 0
    public var startedAt: Int64?
    public var finishedAt: Int64?
    public var updatedAt: Int64 = 0
    public var result: String?
    public var error: String?
    public var version: Int64 = 1
    public var brainId: String?
    public var needsAttention: Bool = false
    public var isPaused: Bool = false
    public var leaseOwner: String?
    public var leaseExpiresAt: Int64?
    public var memberId: String?
    public var attempt: Int = 0
    public var attemptId: String?
    public var goalVersion: Int64 = 1
    public var authEpoch: Int64 = 1

    public init(
        taskId: String = UUID().uuidString,
        projectId: String? = nil,
        sessionId: String? = nil,
        title: String = "",
        description: String? = nil,
        state: String = "queued",
        assignedAgentId: String? = nil,
        parentTaskId: String? = nil,
        dependencies: [String] = [],
        priority: Int = 0,
        createdAt: Int64 = 0,
        startedAt: Int64? = nil,
        finishedAt: Int64? = nil,
        updatedAt: Int64 = 0,
        result: String? = nil,
        error: String? = nil,
        version: Int64 = 1,
        brainId: String? = nil,
        needsAttention: Bool = false,
        isPaused: Bool = false,
        leaseOwner: String? = nil,
        leaseExpiresAt: Int64? = nil,
        memberId: String? = nil,
        attempt: Int = 0,
        attemptId: String? = nil,
        goalVersion: Int64 = 1,
        authEpoch: Int64 = 1
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

public struct TaskEvent: Equatable, Codable, Identifiable, Sendable {
    public var id: Int64 = 0
    public var taskId: String
    /// e.g. "created", "state", "updated".
    public var kind: String
    public var fromState: String?
    public var toState: String?
    public var note: String?
    public var createdAtEpochSeconds: Int64

    public init(taskId: String, kind: String, fromState: String?, toState: String?, note: String?, createdAtEpochSeconds: Int64) {
        self.taskId = taskId
        self.kind = kind
        self.fromState = fromState
        self.toState = toState
        self.note = note
        self.createdAtEpochSeconds = createdAtEpochSeconds
    }
}

public enum LocalTaskStoreError: Error, Equatable {
    case duplicateTask(String)
    case unknownTask(String)
}

public final class LocalTaskStore {
    private let db: SQLiteDatabase

    public init(db: SQLiteDatabase) {
        self.db = db
        try? db.execute("""
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
        try? db.execute("""
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
    }

    private func now() -> Int64 { Int64(Date().timeIntervalSince1970) }

    private func task(from row: [String: SQLiteValue]) -> LocalTask {
        var task = LocalTask(taskId: row["taskId"]?.textValue ?? "")
        task.projectId = row["projectId"]?.textValue
        task.sessionId = row["sessionId"]?.textValue
        task.title = row["title"]?.textValue ?? ""
        task.description = row["description"]?.textValue
        task.state = row["state"]?.textValue ?? "queued"
        task.assignedAgentId = row["assignedAgentId"]?.textValue
        task.parentTaskId = row["parentTaskId"]?.textValue
        if let json = row["dependenciesJson"]?.textValue,
           let data = json.data(using: .utf8),
           let decoded = try? JSONDecoder().decode([String].self, from: data) {
            task.dependencies = decoded
        }
        task.priority = Int(row["priority"]?.intValue ?? 0)
        task.createdAt = row["createdAt"]?.intValue ?? 0
        task.startedAt = row["startedAt"]?.intValue
        task.finishedAt = row["finishedAt"]?.intValue
        task.updatedAt = row["updatedAt"]?.intValue ?? 0
        task.result = row["result"]?.textValue
        task.error = row["error"]?.textValue
        task.version = row["version"]?.intValue ?? 1
        task.brainId = row["brainId"]?.textValue
        task.needsAttention = (row["needsAttention"]?.intValue ?? 0) != 0
        task.leaseOwner = row["leaseOwner"]?.textValue
        task.leaseExpiresAt = row["leaseExpiresAt"]?.intValue
        task.memberId = row["memberId"]?.textValue
        task.attempt = Int(row["attempt"]?.intValue ?? 0)
        task.attemptId = row["attemptId"]?.textValue
        task.goalVersion = row["goalVersion"]?.intValue ?? 1
        task.authEpoch = row["authEpoch"]?.intValue ?? 1
        return task
    }

    private func parameters(for task: LocalTask) -> [SQLiteValue] {
        let dependenciesJson: String
        if let data = try? JSONEncoder().encode(task.dependencies),
           let json = String(data: data, encoding: .utf8) {
            dependenciesJson = json
        } else {
            dependenciesJson = "[]"
        }
        return [
            .text(task.taskId),
            task.projectId.map(SQLiteValue.text) ?? .null,
            task.sessionId.map(SQLiteValue.text) ?? .null,
            .text(task.title),
            task.description.map(SQLiteValue.text) ?? .null,
            .text(task.state),
            task.assignedAgentId.map(SQLiteValue.text) ?? .null,
            task.parentTaskId.map(SQLiteValue.text) ?? .null,
            .text(dependenciesJson),
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
            .int(task.goalVersion),
            .int(task.authEpoch),
        ]
    }

    private static let columns = """
        taskId, projectId, sessionId, title, description, state, assignedAgentId,
        parentTaskId, dependenciesJson, priority, createdAt, startedAt, finishedAt,
        updatedAt, result, error, brainId, needsAttention, leaseOwner, leaseExpiresAt,
        memberId, attempt, attemptId, goalVersion, authEpoch
        """

    /// Creates a task. Creation is a transition from nil, so the
    /// initial state must be "queued" (TaskStates contract).
    @discardableResult
    public func createTask(_ task: LocalTask) throws -> LocalTask {
        try TaskStates.checkTransition(fromState: nil, toState: task.state)
        var task = task
        let timestamp = now()
        if task.createdAt == 0 { task.createdAt = timestamp }
        task.updatedAt = timestamp
        try db.transaction {
            if try getTask(taskId: task.taskId) != nil {
                throw LocalTaskStoreError.duplicateTask(task.taskId)
            }
            try db.execute(
                "INSERT INTO tasks (\(Self.columns), version) VALUES (\(Array(repeating: "?", count: 25).joined(separator: ", ")), 1)",
                parameters(for: task))
            try appendEventLocked(TaskEvent(
                taskId: task.taskId, kind: "created", fromState: nil,
                toState: task.state, note: nil, createdAtEpochSeconds: timestamp))
        }
        return task
    }

    public func getTask(taskId: String) throws -> LocalTask? {
        try db.query("SELECT * FROM tasks WHERE taskId = ?", [.text(taskId)]).first.map(task(from:))
    }

    /// All tasks, optionally filtered by project and/or session
    /// (the Android DAO's listFiltered semantics).
    public func listTasks(projectId: String? = nil, sessionId: String? = nil) throws -> [LocalTask] {
        try db.query(
            """
            SELECT * FROM tasks
            WHERE (? IS NULL OR projectId = ?) AND (? IS NULL OR sessionId = ?)
            ORDER BY createdAt ASC, rowid ASC
            """,
            [projectId.map(SQLiteValue.text) ?? .null, projectId.map(SQLiteValue.text) ?? .null,
             sessionId.map(SQLiteValue.text) ?? .null, sessionId.map(SQLiteValue.text) ?? .null]
        ).map(task(from:))
    }

    /// Full-row update with version bump (Android DAO semantics).
    /// Does not change state; use `transition` for state changes so
    /// the audit trail stays complete.
    public func updateTask(_ task: LocalTask) throws {
        guard try getTask(taskId: task.taskId) != nil else {
            throw LocalTaskStoreError.unknownTask(task.taskId)
        }
        var task = task
        task.updatedAt = now()
        try db.execute(
            """
            UPDATE tasks SET projectId = ?, sessionId = ?, title = ?, description = ?,
            state = ?, assignedAgentId = ?, parentTaskId = ?, dependenciesJson = ?,
            priority = ?, createdAt = ?, startedAt = ?, finishedAt = ?, updatedAt = ?,
            result = ?, error = ?, version = version + 1, brainId = ?, needsAttention = ?,
            leaseOwner = ?, leaseExpiresAt = ?, memberId = ?, attempt = ?, attemptId = ?,
            goalVersion = ?, authEpoch = ? WHERE taskId = ?
            """,
            Array(parameters(for: task).dropFirst()) + [.text(task.taskId)])
    }

    /// Validated state transition: checks the TaskStates table,
    /// maintains startedAt/finishedAt, bumps version, and appends
    /// the audit event — all in one transaction.
    @discardableResult
    public func transition(taskId: String, to newState: String, note: String? = nil) throws -> LocalTask {
        try db.transaction {
            guard var task = try getTask(taskId: taskId) else {
                throw LocalTaskStoreError.unknownTask(taskId)
            }
            try TaskStates.checkTransition(fromState: task.state, toState: newState)
            let from = task.state
            let timestamp = now()
            task.state = newState
            if newState == "working", task.startedAt == nil { task.startedAt = timestamp }
            if TaskStates.isTerminal(newState) { task.finishedAt = timestamp }
            task.updatedAt = timestamp
            try db.execute(
                "UPDATE tasks SET state = ?, startedAt = ?, finishedAt = ?, updatedAt = ?, version = version + 1 WHERE taskId = ?",
                [.text(newState),
                 task.startedAt.map(SQLiteValue.int) ?? .null,
                 task.finishedAt.map(SQLiteValue.int) ?? .null,
                 .int(timestamp),
                 .text(taskId)])
            try appendEventLocked(TaskEvent(
                taskId: taskId, kind: "state", fromState: from,
                toState: newState, note: note, createdAtEpochSeconds: timestamp))
            task.version += 1
            return task
        }
    }

    /// 切换任务的独立 isPaused 暂停标记，并记录审计事件
    @discardableResult
    public func setPaused(taskId: String, isPaused: Bool) throws -> LocalTask {
        try db.transaction {
            guard var task = try getTask(taskId: taskId) else {
                throw LocalTaskStoreError.unknownTask(taskId)
            }
            let timestamp = now()
            task.isPaused = isPaused
            task.updatedAt = timestamp
            try db.execute(
                "UPDATE tasks SET isPaused = ?, updatedAt = ?, version = version + 1 WHERE taskId = ?",
                [.int(isPaused ? 1 : 0), .int(timestamp), .text(taskId)]
            )
            try appendEventLocked(TaskEvent(
                taskId: taskId,
                kind: isPaused ? "TASK_PAUSED" : "TASK_RESUMED",
                fromState: task.state,
                toState: task.state,
                note: isPaused ? "isPaused = true" : "isPaused = false",
                createdAtEpochSeconds: timestamp
            ))
            return task
        }
    }

    public func listEvents(taskId: String) throws -> [TaskEvent] {
        try db.query(
            """
            SELECT * FROM task_events WHERE taskId = ?
            ORDER BY createdAtEpochSeconds ASC, id ASC
            """,
            [.text(taskId)]).map { row in
                TaskEvent(
                    taskId: row["taskId"]?.textValue ?? "",
                    kind: row["kind"]?.textValue ?? "",
                    fromState: row["fromState"]?.textValue,
                    toState: row["toState"]?.textValue,
                    note: row["note"]?.textValue,
                    createdAtEpochSeconds: row["createdAtEpochSeconds"]?.intValue ?? 0)
            }
    }

    /// Appends an event; caller holds the transaction (if any).
    private func appendEventLocked(_ event: TaskEvent) throws {
        try db.execute(
            """
            INSERT INTO task_events (taskId, kind, fromState, toState, note, createdAtEpochSeconds)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            [.text(event.taskId), .text(event.kind),
             event.fromState.map(SQLiteValue.text) ?? .null,
             event.toState.map(SQLiteValue.text) ?? .null,
             event.note.map(SQLiteValue.text) ?? .null,
             .int(event.createdAtEpochSeconds)])
    }
}
