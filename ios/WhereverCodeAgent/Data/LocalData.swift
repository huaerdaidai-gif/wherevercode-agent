import Foundation

/// The local data layer facade: opens the shared database and
/// vends every store. Android keeps conversations, tasks, sessions
/// and skills in one shared file (`wherevercode-tasks.db`, the
/// Room TaskDatabase) with prefs alongside; iOS mirrors that with
/// one SQLite file and a kv table inside it.
///
/// Schema policy matches the baseline's CREATE-only migrations:
/// tables are created with IF NOT EXISTS and `userVersion` tracks
/// the schema generation.
public final class LocalData {
    public let database: SQLiteDatabase
    public let conversations: SQLiteConversationStore
    public let tasks: LocalTaskStore
    public let kv: KeyValueStore
    public let agents: AgentRegistryStore
    public let projects: ProjectStore
    public let cursors: ReplyCursorStore
    public let chatPrefs: ChatHistoryPrefs

    public static let databaseFileName = "wherevercode-tasks.db"

    public init(database: SQLiteDatabase) throws {
        self.database = database
        try Self.createSchema(database)
        let kv = KeyValueStore(db: database)
        self.kv = kv
        self.conversations = SQLiteConversationStore(db: database)
        self.tasks = LocalTaskStore(db: database)
        self.agents = AgentRegistryStore(kv: kv)
        self.projects = ProjectStore(db: database, kv: kv)
        self.cursors = ReplyCursorStore(kv: kv)
        self.chatPrefs = ChatHistoryPrefs(kv: kv)
    }

    /// The app-wide instance (Application Support). Created lazily
    /// on first use so app launch never blocks on disk.
    public static let shared: LocalData = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let path = base.appendingPathComponent(databaseFileName).path
        // The data layer is foundational; a failure to open is a
        // programming-environment error, surfaced loudly in debug
        // and degrading to an in-memory database in production so
        // the App still opens (its platform features keep working).
        if let database = try? SQLiteDatabase(path: path), let data = try? LocalData(database: database) {
            return data
        }
        let fallback = try! SQLiteDatabase.inMemory()
        return try! LocalData(database: fallback)
    }()

    /// Fresh in-memory stack (tests, previews).
    public static func inMemory() throws -> LocalData {
        try LocalData(database: SQLiteDatabase.inMemory())
    }

    private static func createSchema(_ db: SQLiteDatabase) throws {
        let statements = [
            // ConversationStore (baseline ConversationDatabase).
            """
            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                brainId TEXT,
                title TEXT NOT NULL,
                createdAtEpochSeconds INTEGER NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS index_conversations_createdAtEpochSeconds ON conversations (createdAtEpochSeconds)",
            """
            CREATE TABLE IF NOT EXISTS conversation_messages (
                id TEXT PRIMARY KEY,
                conversationId TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                source TEXT NOT NULL,
                createdAtEpochSeconds INTEGER NOT NULL,
                taskId TEXT,
                toolCallsJson TEXT NOT NULL DEFAULT '[]',
                toolCallId TEXT
            )
            """,
            "CREATE INDEX IF NOT EXISTS index_conversation_messages_conversationId ON conversation_messages (conversationId)",
            "CREATE INDEX IF NOT EXISTS index_conversation_messages_taskId ON conversation_messages (taskId)",
            // Local task engine (baseline TaskStore TaskEntity).
            """
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
            """,
            // Task audit trail (baseline TaskEventLog).
            """
            CREATE TABLE IF NOT EXISTS task_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                taskId TEXT NOT NULL,
                kind TEXT NOT NULL,
                fromState TEXT,
                toState TEXT,
                note TEXT,
                createdAtEpochSeconds INTEGER NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS index_task_events_taskId_createdAtEpochSeconds ON task_events (taskId, createdAtEpochSeconds)",
            // Projects & homes (baseline ProjectStore).
            """
            CREATE TABLE IF NOT EXISTS projects (
                projectId TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                repoUrl TEXT,
                defaultBranch TEXT NOT NULL DEFAULT 'main',
                canonicalCommit TEXT,
                origin TEXT NOT NULL DEFAULT 'REMOTE',
                createdAtEpochSeconds INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
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
            """,
            "CREATE INDEX IF NOT EXISTS index_project_locations_projectId ON project_locations (projectId)",
            // Key-value prefs (baseline SharedPreferences stores).
            """
            CREATE TABLE IF NOT EXISTS kv (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """,
        ]
        for statement in statements {
            try db.execute(statement)
        }
        if db.userVersion < 1 { db.userVersion = 1 }
    }
}
