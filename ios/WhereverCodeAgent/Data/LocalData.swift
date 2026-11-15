import Foundation
import SwiftUI
import PhotosUI
import UniformTypeIdentifiers

/// The local data layer facade: opens the shared database and
/// vends every store. Android keeps conversations, tasks, sessions
/// and skills in one shared file (`wherevercode-tasks.db`, the
/// Room TaskDatabase) with prefs alongside; iOS mirrors that with
/// one SQLite file and a kv table inside it.
///
/// Schema policy matches the baseline's CREATE-only migrations:
/// tables are created with IF NOT EXISTS and `userVersion` tracks
/// the schema generation.
public final class LocalData: ObservableObject {
    public let database: SQLiteDatabase
    public let conversations: SQLiteConversationStore
    public let tasks: LocalTaskStore
    public let kv: KeyValueStore
    public let agents: AgentRegistryStore
    public let projects: ProjectStore
    public let cursors: ReplyCursorStore
    public let chatPrefs: ChatHistoryPrefs
    public let eventLog = InMemoryEventLog()
    public let memoryStore = InMemoryMemoryStore()
    public let skillStore = SkillStore()
    public let approvalStore = InteractiveApprovalStore()
    public let contextManager = ContextManager()

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

    public convenience init(directoryURL: URL? = nil) {
        let baseDir = directoryURL ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        try? FileManager.default.createDirectory(at: baseDir, withIntermediateDirectories: true)
        let dbPath = baseDir.appendingPathComponent(Self.databaseFileName).path
        let db = (try? SQLiteDatabase(path: dbPath)) ?? (try! SQLiteDatabase.inMemory())
        try! self.init(database: db)
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


/// Until native workspace tools are wired, advertise none and refuse all
/// execution. A model response must never be treated as an executed action.
struct ChatUnavailableTools: ToolRegistry {
    var workspaceRoot: String? { nil }
    func toolsForCapabilities(_ capabilities: BrainCapabilities?, authorizedTools: Set<String>) -> [ToolDef] { [] }
    func gitHostToolsFor(project: Project?, location: ProjectLocation?) -> [ToolDef] { [] }
    func allToolNames() -> Set<String> { [] }
    func validateOnly(_ action: BrainAction, _ context: ToolContext) -> ToolOutcome? {
        ToolOutcome(ok: false, summary: "iOS 尚未接入工作区执行工具", errorCode: "not_implemented")
    }
    func execute(_ action: BrainAction, _ context: ToolContext, stopped: @escaping () -> Bool) async throws -> ToolOutcome {
        ToolOutcome(ok: false, summary: "iOS 尚未接入工作区执行工具", errorCode: "not_implemented")
    }
    func isReadOnlyExtensionTool(_ name: String) -> Bool { false }
}

@MainActor
public final class LocalChatModel: ObservableObject {
    @Published public var draft: String = ""
    @Published public var messages: [ConversationMessage] = []
    @Published public var history: [Conversation] = []
    @Published public var selectedBrain: String = ""
    @Published public var selectedExecutionTarget: String = "local_brain"
    @Published public var activeProjectId: String? = nil
    @Published public var attachedFiles: [String] = []
    @Published public var error: String? = nil
    @Published public var streaming: String = ""
    @Published public var isSending: Bool = false
    @Published public var showHistory: Bool = false
    /// Project source / working path bound to this conversation
    /// (independent of the brain/model selection).
    @Published public var projectSource: ProjectSourceBinding = .localDefault
    /// Execution permission for this conversation. Only modes the
    /// current chat path can honour are selectable.
    @Published public var permissionMode: ChatPermissionMode = .readOnly

    public private(set) var conversationID: String?
    public unowned let appState: AppState
    public let eventLog: InMemoryEventLog
    public let data: LocalData
    private var currentTurn: Task<Void, Never>?

    public init(appState: AppState, data: LocalData = .shared) {
        self.appState = appState
        self.data = data
        self.eventLog = data.eventLog
        self.selectedBrain = appState.brainConfigStore.currentBrainId() ?? ""
        self.conversationID = data.chatPrefs.currentSessionId
        if let id = conversationID, let conversation = try? data.conversations.getConversation(id: id) {
            self.selectedBrain = conversation.brainId ?? self.selectedBrain
        }
        restoreSessionConfig()
        reload()
    }

    // MARK: Session config + draft persistence (P0)

    private var draftKey: String { "chat.draft.\(conversationID ?? "new")" }
    private var configKey: String? { conversationID.map { "chat.sessionConfig.\($0)" } }

    private func restoreSessionConfig() {
        let cfg = configKey.flatMap { try? data.kv.getJSON($0, as: ChatSessionConfig.self) }
        projectSource = cfg?.source ?? .localDefault
        permissionMode = cfg?.permission ?? .readOnly
        draft = (try? data.kv.get(draftKey)) ?? nil ?? ""
    }

    public func persistSessionConfig() {
        guard let key = configKey else { return }
        try? data.kv.setJSON(key, ChatSessionConfig(source: projectSource, permission: permissionMode))
    }

    public func persistDraft() {
        try? data.kv.set(draftKey, draft.isEmpty ? nil : draft)
    }

    /// Brains that can actually serve a chat right now.
    public var usableBrains: [BrainConfig] {
        appState.brainSettingsModel.configs.filter {
            appState.brainSettingsModel.unusableReason(for: $0) == nil
        }
    }

    /// Resolve the brain to send with. A stale binding (e.g. the
    /// "siwc-chatgpt" membership brain that has no iOS adapter) falls
    /// back to the current usable brain instead of failing with
    /// "找不到大脑配置".
    private func resolveBrainForSend() -> (String?, String?) {
        guard !selectedBrain.isEmpty else {
            return (nil, "请在模型与连接中配置并选择模型")
        }
        if let cfg = appState.brainRegistry.configFor(selectedBrain),
           appState.brainRegistry.get(cfg.adapterId) != nil {
            if cfg.authRef == nil || appState.brainSettingsModel.hasKey(for: cfg) {
                return (selectedBrain, nil)
            }
        }
        let usable = usableBrains
        if let current = appState.brainConfigStore.currentBrainId(),
           usable.contains(where: { $0.brainId == current }) {
            return (current, nil)
        }
        if let first = usable.first { return (first.brainId, nil) }
        if let cfg = appState.brainSettingsModel.configs.first(where: { $0.brainId == selectedBrain }),
           let reason = appState.brainSettingsModel.unusableReason(for: cfg) {
            return (nil, "「\(cfg.label)」不可用：\(reason)。请到 设置 › 模型 连接一个可用的大脑。")
        }
        if let cfg = appState.brainRegistry.configFor(selectedBrain),
           appState.brainRegistry.get(cfg.adapterId) != nil {
            return (selectedBrain, nil)
        }
        return (nil, "还没有可用的大脑：请到 设置 › 模型 填写 API Key 并设为当前大脑。")
    }

    public func reload() {
        do {
            history = try data.conversations.listConversations()
            messages = try conversationID.map { try data.conversations.listMessages(conversationId: $0) } ?? []
        } catch {
            self.error = error.localizedDescription
        }
    }

    public func reloadHistory() {
        reload()
    }

    public func newConversation() {
        guard !isSending else {
            error = "请等待当前回复完成后新建对话"
            return
        }
        persistDraft()
        conversationID = nil
        data.chatPrefs.currentSessionId = nil
        draft = ""
        projectSource = .localDefault
        permissionMode = .readOnly
        messages = []
        streaming = ""
        error = nil
    }

    public func open(_ conversation: Conversation) {
        guard !isSending else { return }
        persistDraft()
        conversationID = conversation.id
        data.chatPrefs.currentSessionId = conversation.id
        selectedBrain = conversation.brainId ?? appState.brainConfigStore.currentBrainId() ?? ""
        error = nil
        streaming = ""
        restoreSessionConfig()
        showHistory = false
        reload()
    }

    public func forkConversation(_ conversation: Conversation) {
        guard let forked = try? data.conversations.createConversation(
            brainId: conversation.brainId ?? selectedBrain,
            title: "\(conversation.title) (分叉)"
        ) else { return }
        let oldMsgs = (try? data.conversations.listMessages(conversationId: conversation.id)) ?? []
        for m in oldMsgs {
            let copy = ConversationMessage(
                conversationId: forked.id,
                role: m.role,
                content: m.content,
                source: m.source,
                taskId: m.taskId,
                toolCalls: m.toolCalls,
                toolCallId: m.toolCallId
            )
            try? data.conversations.appendMessage(copy)
        }
        reload()
        open(forked)
    }

    public func startSending() {
        guard currentTurn == nil, !isSending else { return }
        currentTurn = Task {
            await send()
            currentTurn = nil
        }
    }

    public func stopSending() {
        currentTurn?.cancel()
        currentTurn = nil
        isSending = false
        if let cid = conversationID {
            eventLog.append(conversationId: cid, kind: "STOPPED", detail: "已停止：剩余工具调用不再执行。")
        }
    }

    public func stopGenerating() {
        stopSending()
    }

    public func appendSystemNotice(_ notice: String) {
        do {
            if conversationID == nil {
                let conv = try data.conversations.createConversation(brainId: selectedBrain, title: "系统控制")
                conversationID = conv.id
            }
            guard let cid = conversationID else { return }
            let msg = ConversationMessage(
                conversationId: cid,
                role: "assistant",
                content: notice,
                source: "codex_control"
            )
            try data.conversations.appendMessage(msg)
            reload()
        } catch {}
    }

    public func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isSending else { return }

        // 自然语言任务控制：暂停、停止、继续
        if text == "暂停" || text == "暂停任务" {
            draft = ""
            if let working = (try? data.tasks.listTasks())?.first(where: { $0.state == "working" }) {
                _ = try? data.tasks.setPaused(taskId: working.taskId, isPaused: true)
                eventLog.append(conversationId: conversationID ?? "conv", kind: "TASK_PAUSED", detail: "任务已通过自然语言暂停。")
                appendSystemNotice("已暂停任务「\(working.title)」。可通过自然语言输入「继续」恢复执行，或输入「停止」取消任务。")
            } else {
                appendSystemNotice("当前没有正在执行中的任务。")
            }
            return
        }

        if text == "继续" || text == "继续执行" || text == "恢复" {
            draft = ""
            if let paused = (try? data.tasks.listTasks())?.first(where: { $0.isPaused }) {
                _ = try? data.tasks.setPaused(taskId: paused.taskId, isPaused: false)
                eventLog.append(conversationId: conversationID ?? "conv", kind: "TASK_RESUMED", detail: "任务已通过自然语言恢复。")
                appendSystemNotice("已恢复任务「\(paused.title)」的执行。")
            } else {
                appendSystemNotice("当前没有已暂停的任务。")
            }
            return
        }

        if text == "停止" || text == "停止任务" || text == "取消任务" {
            draft = ""
            stopSending()
            if let working = (try? data.tasks.listTasks())?.first(where: { $0.state == "working" }) {
                _ = try? data.tasks.transition(taskId: working.taskId, to: "cancelled", note: "用户自然语言终止")
                appendSystemNotice("已终止任务「\(working.title)」。")
            } else {
                appendSystemNotice("当前生成与任务已全部停止。")
            }
            return
        }

        let (resolvedBrain, brainError) = resolveBrainForSend()
        guard let resolvedBrain else {
            error = brainError
            return
        }
        selectedBrain = resolvedBrain
        isSending = true
        error = nil
        streaming = ""
        defer {
            isSending = false
            streaming = ""
            reload()
        }
        do {
            if conversationID == nil {
                let conversation = try data.conversations.createConversation(
                    brainId: selectedBrain,
                    title: String(text.prefix(48))
                )
                try? data.kv.set(draftKey, nil)
                conversationID = conversation.id
                data.chatPrefs.currentSessionId = conversation.id
                persistSessionConfig()
            }
            guard let id = conversationID else { return }
            draft = ""
            persistDraft()
            let outgoing = text + attachmentContext()
            attachedFiles = []

            // 自然语言发起真实 Agent 任务：检测编程、修复、测试、执行等意图并在本地账本建单
            let isTaskIntent = text.contains("任务") || text.contains("实现") || text.contains("编写") || text.contains("修复") || text.contains("测试") || text.contains("开发")
            var activeTaskId: String? = nil
            if isTaskIntent {
                let taskTitle = String(text.prefix(32))
                if let task = try? data.tasks.createTask(
                    LocalTask(
                        title: taskTitle,
                        description: text,
                        priority: 0,
                        brainId: selectedBrain
                    )
                ) {
                    activeTaskId = task.taskId
                    _ = try? data.tasks.transition(taskId: task.taskId, to: "working", note: "自然语言发起 Agent 任务")
                    eventLog.append(conversationId: id, kind: "TASK_DISPATCH", detail: "已在本机 SQLite 登记任务 \(task.taskId)")
                }
            }

            let engine = appState.makeBrainEngine(
                conversations: data.conversations,
                tools: ChatUnavailableTools(),
                eventLog: eventLog,
                approvalGate: DenyApprovalGate()
            )
            for try await event in engine.chat(
                conversationId: id,
                userText: outgoing,
                brainIdOverride: selectedBrain,
                allowFailover: false
            ) {
                try Task.checkCancellation()
                switch event {
                case .textDelta(let delta): streaming += delta
                case .error(let info): error = info.message
                default: break
                }
                reload()
            }

            if let tid = activeTaskId {
                _ = try? data.tasks.transition(taskId: tid, to: "completed", note: "Agent 任务轮次完成")
            }
        } catch is CancellationError {
            self.error = "回复已停止"
        } catch {
            self.error = SecretRedactor.redact(error.localizedDescription)
            if messages.last?.content != text { draft = text }
        }
    }

    public func send(userText: String) async {
        draft = userText
        await send()
    }

    /// Text attachments are inlined (bounded); binary attachments are
    /// named honestly as not sent — the chat path has no upload API.
    private func attachmentContext() -> String {
        guard !attachedFiles.isEmpty else { return "" }
        var out = "\n\n[附件]"
        for name in attachedFiles {
            let url = ChatAttachmentStore.url(for: name)
            if let d = try? Data(contentsOf: url), d.count <= 64 * 1024,
               let text = String(data: d, encoding: .utf8) {
                out += "\n--- \(name) ---\n\(text)"
            } else {
                out += "\n- \(name)（图片或二进制文件，当前大脑通道不上传内容）"
            }
        }
        return out
    }
}

public struct AgentTaskInlineCard: View {
    public let taskId: String
    @ObservedObject private var localData: LocalData = .shared
    public var onNavigateRoute: (String) -> Void

    public init(taskId: String, onNavigateRoute: @escaping (String) -> Void) {
        self.taskId = taskId
        self.onNavigateRoute = onNavigateRoute
    }

    public var body: some View {
        let task = (try? localData.tasks.listTasks())?.first(where: { $0.taskId == taskId })
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "cpu")
                    .font(.caption)
                    .foregroundStyle(Color(red: 0.82, green: 0.49, blue: 0.35))
                Text(task?.title ?? "Agent 执行任务")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                Spacer()
                Text(task?.state ?? "working")
                    .font(.caption2.bold())
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color.orange.opacity(0.15), in: Capsule())
                    .foregroundStyle(.orange)
            }

            if let t = task {
                Text("状态：\(t.state) · 优先级：P\(t.priority) · 独立暂停位：\(t.isPaused ? "已暂停" : "运行中")")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 8) {
                Button {
                    if let t = task {
                        _ = try? localData.tasks.setPaused(taskId: t.taskId, isPaused: !t.isPaused)
                    }
                } label: {
                    Text(task?.isPaused == true ? "继续执行" : "暂停任务")
                        .font(.caption2.weight(.medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Color(red: 0.92, green: 0.91, blue: 0.88), in: Capsule())
                        .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                }

                Button {
                    onNavigateRoute("tasks")
                } label: {
                    Text("任务中心")
                        .font(.caption2.weight(.medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Color(red: 0.92, green: 0.91, blue: 0.88), in: Capsule())
                        .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                }

                Button {
                    onNavigateRoute("artifacts")
                } label: {
                    Text("交付成果")
                        .font(.caption2.weight(.medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Color(red: 0.92, green: 0.91, blue: 0.88), in: Capsule())
                        .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                }
            }
        }
        .padding(12)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
        .overlay(
            RoundedRectangle(cornerRadius: 14)
                .stroke(Color(red: 0.89, green: 0.88, blue: 0.85), lineWidth: 1)
        )
    }
}

/// Claude 极简风格对话首页（严格遵循设计冻结要求：顶部圆按钮、中部 ✳ 星号与问候、底部悬浮胶囊输入栏，无底部 Tab Bar、无功能宫格、无首页统计卡片）
public struct LocalChatHomeView: View {
    @ObservedObject public var appState: AppState
    @ObservedObject public var model: LocalChatModel
    public var onOpenDrawer: () -> Void
    public var onSelectRoute: (String) -> Void

    @State private var greetingDismissed = false
    @State private var showAgentSelectorSheet = false
    @State private var query = ""
    @FocusState private var inputFocused: Bool

    public init(
        appState: AppState,
        model: LocalChatModel,
        onOpenDrawer: @escaping () -> Void = {},
        onSelectRoute: @escaping (String) -> Void = { _ in }
    ) {
        self.appState = appState
        self.model = model
        self.onOpenDrawer = onOpenDrawer
        self.onSelectRoute = onSelectRoute
    }

    public init(model: LocalChatModel) {
        self.appState = model.appState
        self.model = model
        self.onOpenDrawer = {}
        self.onSelectRoute = { _ in }
    }

    private var connectionSummaryText: String {
        let platformLabel: String
        switch appState.connectionStatus {
        case .connected(let detail): platformLabel = "平台已连接 (\(detail))"
        case .checking: platformLabel = "平台连接检查中…"
        case .notConfigured: platformLabel = "平台未配置（本地优先）"
        case .failed: platformLabel = "平台未连接（离线/本地模式）"
        case .unknown: platformLabel = "平台未检查"
        }
        return "模式：大脑对话 · 当前设备：本机 · \(platformLabel)"
    }

    private var filteredHistory: [Conversation] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return model.history }
        return model.history.filter { $0.title.localizedCaseInsensitiveContains(q) }
    }

    public var body: some View {
        VStack(spacing: 0) {
            // 顶部极简按钮栏（左：四父级抽屉按钮；右：搜索会话 / 新对话 / 模型上下文切换）
            HStack(spacing: 8) {
                Button(action: onOpenDrawer) {
                    Image(systemName: "line.3.horizontal")
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                        .frame(width: 44, height: 44)
                        .background(Color.white, in: Circle())
                        .overlay(Circle().stroke(Color(red: 0.87, green: 0.86, blue: 0.84), lineWidth: 1))
                }
                .accessibilityIdentifier("navigation.menu")
                .accessibilityLabel("打开导航")

                Spacer()

                if model.selectedBrain.isEmpty {
                    Button {
                        onSelectRoute("models")
                    } label: {
                        Text("还没有连接 AI 模型　连接 ›")
                            .font(.caption)
                            .foregroundStyle(Color(red: 0.72, green: 0.34, blue: 0.16))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Color(red: 0.98, green: 0.94, blue: 0.90), in: Capsule())
                    }
                }

                Spacer()

                Button {
                    model.showHistory = true
                } label: {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                        .frame(width: 38, height: 38)
                        .background(Color.white, in: Circle())
                        .overlay(Circle().stroke(Color(red: 0.87, green: 0.86, blue: 0.84), lineWidth: 1))
                }
                .accessibilityLabel("搜索会话")

                Button {
                    model.newConversation()
                } label: {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                        .frame(width: 38, height: 38)
                        .background(Color.white, in: Circle())
                        .overlay(Circle().stroke(Color(red: 0.87, green: 0.86, blue: 0.84), lineWidth: 1))
                }
                .accessibilityLabel("新对话")

                Button {
                    showAgentSelectorSheet = true
                } label: {
                    Image(systemName: "sparkles")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                        .frame(width: 44, height: 44)
                        .background(Color.white, in: Circle())
                        .overlay(Circle().stroke(Color(red: 0.87, green: 0.86, blue: 0.84), lineWidth: 1))
                }
                .accessibilityIdentifier("home.agentSelectorButton")
                .accessibilityLabel("切换大脑模型与 Agent 上下文")
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 8)

            // 主体消息流或 Claude 风格极简留白问候区
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 16) {
                        if model.messages.isEmpty && !model.isSending && !greetingDismissed {
                            Spacer(minLength: 88)
                            VStack(spacing: 14) {
                                Text("✳")
                                    .font(.system(size: 52))
                                    .foregroundStyle(Color(red: 0.82, green: 0.49, blue: 0.35))

                                Text("今天想做什么？")
                                    .font(.system(size: 27, weight: .regular, design: .serif))
                                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))

                                VStack(spacing: 8) {
                                    HStack(spacing: 8) {
                                        suggestion("开发一个 App", icon: "sparkles", fillPrefix: "开发一个 App：")
                                        suggestion("导入项目并交付", icon: "folder", fillPrefix: "导入项目并交付：")
                                    }
                                    HStack(spacing: 8) {
                                        suggestion("组织多 Agent 团队", icon: "person.2", fillPrefix: "组织多 Agent 团队：")
                                        suggestion("自动化任务", icon: "bolt.horizontal", fillPrefix: "自动化任务：")
                                    }
                                }
                                .padding(.top, 8)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 32)
                        } else {
                            ForEach(model.messages) { message in
                                VStack(alignment: message.role == "user" ? .trailing : .leading, spacing: 6) {
                                    HStack {
                                        if message.role == "user" { Spacer() }
                                        Text(message.role == "user" ? "你" : "Assistant")
                                            .font(.system(size: 12, weight: .medium))
                                            .foregroundStyle(.secondary)
                                        if message.role != "user" { Spacer() }
                                    }
                                    Text(message.content)
                                        .font(.system(size: 15))
                                        .lineSpacing(4)
                                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                                        .textSelection(.enabled)
                                        .padding(.horizontal, 14)
                                        .padding(.vertical, 11)
                                        .background(
                                            message.role == "user"
                                                ? Color(red: 0.93, green: 0.92, blue: 0.89)
                                                : Color.white
                                        )
                                        .clipShape(RoundedRectangle(cornerRadius: 16))
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 16)
                                                .stroke(Color(red: 0.89, green: 0.88, blue: 0.85), lineWidth: 1)
                                        )

                                    if let tid = message.taskId {
                                        AgentTaskInlineCard(taskId: tid, onNavigateRoute: onSelectRoute)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: message.role == "user" ? .trailing : .leading)
                                .id(message.id)
                            }
                        }

                        if model.isSending {
                            HStack(spacing: 8) {
                                ProgressView()
                                Text(model.streaming.isEmpty ? "正在等待模型回复…" : model.streaming)
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 8)
                        }

                        if let err = model.error {
                            HStack(alignment: .top, spacing: 8) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .foregroundStyle(.orange)
                                Text(err)
                                    .font(.footnote)
                                    .foregroundStyle(.primary)
                                Spacer()
                            }
                            .padding(12)
                            .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 16)
                    .contentShape(Rectangle())
                    .onTapGesture { inputFocused = false }
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: model.messages.count) { _, _ in
                    if let lastId = model.messages.last?.id {
                        withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
                    }
                }
            }

            // Codex 参考输入框：来源选择器在上，输入框随键盘上移
            ChatComposerBar(model: model, focused: $inputFocused, onSelectRoute: onSelectRoute)
        }
        .background(Color(red: 0.98, green: 0.976, blue: 0.968).ignoresSafeArea())
        .sheet(isPresented: $model.showHistory) {
            NavigationStack {
                List {
                    Section {
                        Button("新建对话") {
                            model.newConversation()
                            model.showHistory = false
                        }
                    }
                    Section("历史会话") {
                        if filteredHistory.isEmpty {
                            Text("暂无保存的本地会话").foregroundStyle(.secondary)
                        } else {
                            ForEach(filteredHistory) { conv in
                                Button {
                                    model.open(conv)
                                } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(conv.title.isEmpty ? conv.id : conv.title).font(.headline)
                                        Text("大脑：\(conv.brainId ?? "未指定")")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                }
                .navigationTitle("会话")
                .searchable(text: $query, prompt: "搜索历史会话")
                .toolbar {
                    Button("完成") { model.showHistory = false }
                }
            }
        }
        .sheet(isPresented: $showAgentSelectorSheet) {
            ModelAndAgentSheet(model: model, onManage: { onSelectRoute("models") })
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("完成") { inputFocused = false }
            }
        }
    }

    @ViewBuilder
    private func suggestion(_ title: String, icon: String, route: String? = nil, fillPrefix: String? = nil) -> some View {
        Button {
            if let fillPrefix {
                model.draft = fillPrefix
                inputFocused = true
            } else if let route {
                onSelectRoute(route)
            } else {
                model.draft = "\(title)："
                inputFocused = true
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.caption)
                Text(title)
                    .font(.caption)
            }
            .foregroundStyle(Color(red: 0.25, green: 0.24, blue: 0.22))
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color.white, in: Capsule())
            .overlay(Capsule().stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1))
        }
    }
}

public struct LocalWorkbenchView: View {
    public enum Section: String, CaseIterable, Equatable {
        case projects
        case agents
        case console
    }

    public let section: Section
    @ObservedObject public var data: LocalData = .shared

    public init(section: Section) {
        self.section = section
    }

    private var projectsList: [ProjectWithHomes] { (try? data.projects.listProjects()) ?? [] }
    private var agentsList: [AgentRecord] { (try? data.agents.listAgents()) ?? [] }
    private var eventsList: [EngineEvent] { data.eventLog.listEvents() }

    public var body: some View {
        List {
            switch section {
            case .projects:
                if projectsList.isEmpty {
                    Text("暂无本地项目").foregroundStyle(.secondary)
                } else {
                    ForEach(projectsList) { item in
                        DisclosureGroup(item.project.name) {
                            ForEach(item.homes) { loc in
                                Text("\(loc.hostId): \(loc.workspacePath) (\(loc.availability))")
                                    .font(.caption)
                                    .textSelection(.enabled)
                            }
                        }
                    }
                }
            case .agents:
                if agentsList.isEmpty {
                    Text("暂无已登记 Agent").foregroundStyle(.secondary)
                } else {
                    ForEach(agentsList) { agent in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(agent.displayName).font(.headline)
                            Text("\(agent.kind.rawValue) · \(agent.memberConnectionState)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            case .console:
                if eventsList.isEmpty {
                    Text("当前进程暂无模型事件").foregroundStyle(.secondary)
                } else {
                    ForEach(eventsList) { ev in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("[#\(ev.seq)] \(ev.kind)").font(.caption.bold())
                            Text(ev.detail).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle(section == .projects ? "项目" : (section == .agents ? "团队（Agent / 成员）" : "控制台事件"))
    }
}

// MARK: - Project sources (extensible registry, P0)
//
// Sources are NOT a fixed enum: each is a `ProjectSourceProvider`
// registered in `ProjectSourceRegistry`. Built-ins cover 本机 / 局域网
// 电脑 / NAT·远程设备 / VPS / 网盘 / GitHub; new kinds (WebDAV, SSH,
// GitLab, 自建服务 …) register a provider — or the user adds a custom
// source in the picker — without touching the composer, the binding
// format or session restore. A binding stores only `providerId` +
// target ids, so unknown providers restore as "不可用" rather than
// crash. Selecting a source never grants command execution.

public enum ProjectSourceStatus: Equatable, Sendable {
    case ready
    case needsAuth(String)
    case offline(String)
    case unsupported(String)

    public var text: String {
        switch self {
        case .ready: return "可用"
        case .needsAuth(let m), .offline(let m), .unsupported(let m): return m
        }
    }
    public var isReady: Bool { if case .ready = self { return true } else { return false } }
}

public struct ProjectSourceTarget: Identifiable, Equatable, Sendable {
    public var id: String
    public var title: String
    public var subtitle: String
    public var path: String?
    public init(id: String, title: String, subtitle: String = "", path: String? = nil) {
        self.id = id; self.title = title; self.subtitle = subtitle; self.path = path
    }
}

@MainActor
public protocol ProjectSourceProvider {
    var id: String { get }
    var title: String { get }
    var icon: String { get }
    /// Real connection / authorisation state.
    func status(data: LocalData) -> ProjectSourceStatus
    /// Devices / accounts / projects the user can choose (step 3-4).
    func targets(data: LocalData) -> [ProjectSourceTarget]
    /// Whether choosing this source can ever enable command execution.
    var canExecute: Bool { get }
}

/// Device-backed sources (LAN / NAT / VPS): targets are paired device
/// agents from the agent registry, filtered by an access predicate.
public struct DeviceProjectSource: ProjectSourceProvider {
    public let id: String
    public let title: String
    public let icon: String
    public let matches: @Sendable (AgentRecord) -> Bool
    public var canExecute: Bool { true }

    public func targets(data: LocalData) -> [ProjectSourceTarget] {
        let agents = ((try? data.agents.listAgents()) ?? []).filter { $0.kind == .device && $0.enabled && matches($0) }
        return agents.map {
            ProjectSourceTarget(id: $0.agentId, title: $0.displayName,
                                subtitle: $0.memberConnectionState == "CONNECTED" ? "在线" : "离线 / 未连接",
                                path: $0.deviceFolder)
        }
    }

    public func status(data: LocalData) -> ProjectSourceStatus {
        targets(data: data).isEmpty ? .needsAuth("尚未配对设备：到 控制 › 设备 添加并配对") : .ready
    }
}

public struct LocalProjectSource: ProjectSourceProvider {
    public let id = "local"
    public let title = "本机"
    public let icon = "iphone"
    public var canExecute: Bool { false }
    public func status(data: LocalData) -> ProjectSourceStatus { .ready }
    public func targets(data: LocalData) -> [ProjectSourceTarget] {
        var out = [ProjectSourceTarget(id: "sandbox", title: "App 沙盒", subtitle: "iPhone 本机文件（只读对话）", path: "/")]
        for p in (try? data.projects.listProjects()) ?? [] {
            out.append(ProjectSourceTarget(id: p.project.projectId, title: p.project.name,
                                           subtitle: p.activeHome?.workspacePath ?? "未设置位置",
                                           path: p.activeHome?.workspacePath))
        }
        return out
    }
}

/// Account-backed sources whose authorisation flow is not on iOS yet.
public struct PendingAccountSource: ProjectSourceProvider {
    public let id: String
    public let title: String
    public let icon: String
    public let reason: String
    public var canExecute: Bool { false }
    public func status(data: LocalData) -> ProjectSourceStatus { .unsupported(reason) }
    public func targets(data: LocalData) -> [ProjectSourceTarget] { [] }
}

/// A user-added source (e.g. WebDAV / SSH / 自建 Git) — stored in kv,
/// shown honestly as "未接入" until a provider for its scheme exists.
public struct CustomSourceRecord: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var title: String
    public var endpoint: String
}

public struct CustomProjectSource: ProjectSourceProvider {
    public let record: CustomSourceRecord
    public var id: String { record.id }
    public var title: String { record.title }
    public var icon: String { "link" }
    public var canExecute: Bool { false }
    public func status(data: LocalData) -> ProjectSourceStatus {
        .unsupported("自定义来源：已保存地址，暂无对应接入模块")
    }
    public func targets(data: LocalData) -> [ProjectSourceTarget] {
        [ProjectSourceTarget(id: record.id, title: record.title, subtitle: record.endpoint, path: record.endpoint)]
    }
}

public struct ProjectSourceDescriptor: Identifiable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let available: Bool
    public let note: String

    public init(id: String, title: String, available: Bool, note: String) {
        self.id = id
        self.title = title
        self.available = available
        self.note = note
    }
}

@MainActor
public final class ProjectSourceRegistry {
    public static let shared = ProjectSourceRegistry()
    private var builtIns: [ProjectSourceProvider] = []
    private let customKey = "chat.customProjectSources"

    /// 双端共用项目来源类型注册表（与 Android ProjectSourceRegistry 6 项 ID 严格对齐，支持动态扩展）
    public private(set) var sharedSources: [ProjectSourceDescriptor] = [
        ProjectSourceDescriptor(
            id: "local_folder",
            title: "本机文件夹",
            available: true,
            note: "本机或同步/挂载到本机的文件夹都是文件夹"
        ),
        ProjectSourceDescriptor(
            id: "device_folder",
            title: "远程设备上的文件夹",
            available: true,
            note: "已配对设备上经 Hand 授权的文件夹"
        ),
        ProjectSourceDescriptor(
            id: "git_host",
            title: "git 仓库地址",
            available: true,
            note: "登记地址、经该站 API 读；厂商可扩展，不写死 GitHub/GitLab/Gitee 名单"
        ),
        ProjectSourceDescriptor(
            id: "drive_sync",
            title: "网盘同步文件夹",
            available: true,
            note: "同步到本机/设备后按文件夹处理，不单列入口"
        ),
        ProjectSourceDescriptor(
            id: "nas_mount",
            title: "NAS 挂载文件夹",
            available: true,
            note: "挂在某台设备上按设备文件夹处理，不单列入口"
        ),
        ProjectSourceDescriptor(
            id: "custom",
            title: "自定义来源",
            available: false,
            note: "自定义来源待开发（注册表预留扩展位，新增来源只加注册项、不改流程）"
        )
    ]

    public func sources() -> [ProjectSourceDescriptor] {
        sharedSources
    }

    @discardableResult
    public func registerSource(_ source: ProjectSourceDescriptor) -> Bool {
        if sharedSources.contains(where: { $0.id == source.id }) { return false }
        sharedSources.append(source)
        return true
    }

    public func findSource(_ id: String) -> ProjectSourceDescriptor? {
        sharedSources.first { $0.id == id }
    }

    public init() {
        builtIns = [
            LocalProjectSource(),
            DeviceProjectSource(id: "lan", title: "局域网电脑", icon: "desktopcomputer",
                                matches: { ($0.baseUrl ?? "").contains("192.168.") || ($0.baseUrl ?? "").contains(".local") || $0.baseUrl == nil }),
            DeviceProjectSource(id: "remote", title: "NAT / 远程设备", icon: "network",
                                matches: { $0.access == .connector || $0.access == .webhook }),
            DeviceProjectSource(id: "vps", title: "VPS", icon: "server.rack",
                                matches: { $0.access == .cli || $0.access == .api }),
            PendingAccountSource(id: "clouddrive", title: "网盘", icon: "externaldrive.badge.icloud",
                                 reason: "网盘授权尚未在 iOS 接入"),
            PendingAccountSource(id: "github", title: "GitHub", icon: "chevron.left.forwardslash.chevron.right",
                                 reason: "GitHub 授权尚未在 iOS 接入"),
        ]
    }

    /// Register an additional provider (new source kinds plug in here).
    public func register(_ provider: ProjectSourceProvider) {
        builtIns.removeAll { $0.id == provider.id }
        builtIns.append(provider)
    }

    public func customRecords(data: LocalData) -> [CustomSourceRecord] {
        (try? data.kv.getJSON(customKey, as: [CustomSourceRecord].self)) ?? nil ?? []
    }

    public func addCustom(title: String, endpoint: String, data: LocalData) {
        var list = customRecords(data: data)
        list.append(CustomSourceRecord(id: "custom-\(UUID().uuidString.prefix(8))", title: title, endpoint: endpoint))
        try? data.kv.setJSON(customKey, list)
    }

    public func removeCustom(id: String, data: LocalData) {
        try? data.kv.setJSON(customKey, customRecords(data: data).filter { $0.id != id })
    }

    public func all(data: LocalData) -> [ProjectSourceProvider] {
        builtIns + customRecords(data: data).map { CustomProjectSource(record: $0) }
    }

    public func provider(id: String, data: LocalData) -> ProjectSourceProvider? {
        let mappedId: String
        switch id {
        case "local_folder", "drive_sync": mappedId = "local"
        case "device_folder", "nas_mount": mappedId = "lan"
        case "git_host": mappedId = "github"
        default: mappedId = id
        }
        return all(data: data).first { $0.id == mappedId }
    }
}

public struct ProjectSourceBinding: Codable, Equatable, Sendable {
    public var providerId: String
    public var targetId: String?
    public var targetTitle: String?
    public var path: String?

    public static let localDefault = ProjectSourceBinding(providerId: "local", targetId: "sandbox", targetTitle: "App 沙盒", path: "/")

    public var chipLabel: String { targetTitle ?? providerId }
}

public enum ChatPermissionMode: String, Codable, CaseIterable, Identifiable, Sendable {
    case readOnly, askEveryTime, autoInWorkspace
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .readOnly: return "只读对话"
        case .askEveryTime: return "每次审批"
        case .autoInWorkspace: return "工作区内自动"
        }
    }
}

public struct ChatSessionConfig: Codable, Equatable, Sendable {
    public var source: ProjectSourceBinding
    public var permission: ChatPermissionMode
}

public enum ChatAttachmentStore {
    public static var dir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let d = base.appendingPathComponent("chat-attachments", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }
    public static func url(for name: String) -> URL { dir.appendingPathComponent(name) }
    @discardableResult
    public static func save(_ data: Data, name: String) -> String {
        let safe = name.replacingOccurrences(of: "/", with: "_")
        try? data.write(to: url(for: safe))
        return safe
    }
}

// MARK: - Codex-style composer (P0)


public struct ProjectSourceChip: View {
    @ObservedObject var model: LocalChatModel
    @State private var showPicker = false

    public var body: some View {
        let provider = ProjectSourceRegistry.shared.provider(id: model.projectSource.providerId, data: model.data)
        Button { showPicker = true } label: {
            HStack(spacing: 5) {
                Image(systemName: provider?.icon ?? "questionmark.folder")
                Text(provider.map { "\($0.title) · \(model.projectSource.chipLabel)" } ?? "来源不可用")
                    .lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold))
            }
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(Color(red: 0.30, green: 0.29, blue: 0.27))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Color.white.opacity(0.9), in: Capsule())
            .overlay(Capsule().stroke(Color(red: 0.89, green: 0.88, blue: 0.86), lineWidth: 1))
        }
        .accessibilityIdentifier("chat.projectSource")
        .sheet(isPresented: $showPicker) {
            ProjectSourcePickerSheet(model: model)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }
}

/// 选择来源 → 连接或授权 → 选择设备/账号 → 选择项目或路径 → 确认
public struct ProjectSourcePickerSheet: View {
    @ObservedObject var model: LocalChatModel
    @Environment(\.dismiss) private var dismiss
    @State private var customTitle = ""
    @State private var customEndpoint = ""
    @State private var refresh = 0

    public var body: some View {
        NavigationStack {
            List {
                Section("选择来源") {
                    ForEach(ProjectSourceRegistry.shared.all(data: model.data), id: \.id) { p in
                        NavigationLink {
                            ProjectSourceTargetList(model: model, provider: p) { dismiss() }
                        } label: {
                            HStack {
                                Image(systemName: p.icon).frame(width: 24)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.title)
                                    Text(p.status(data: model.data).text)
                                        .font(.caption)
                                        .foregroundStyle(p.status(data: model.data).isReady ? Color.secondary : Color.orange)
                                }
                                Spacer()
                                if model.projectSource.providerId == p.id {
                                    Image(systemName: "checkmark").foregroundStyle(.blue)
                                }
                            }
                        }
                    }
                    .id(refresh)
                }
                Section {
                    TextField("名称（如 公司 WebDAV）", text: $customTitle)
                    TextField("地址（https:// / ssh:// …）", text: $customEndpoint)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("添加自定义来源") {
                        ProjectSourceRegistry.shared.addCustom(title: customTitle, endpoint: customEndpoint, data: model.data)
                        customTitle = ""; customEndpoint = ""; refresh += 1
                    }
                    .disabled(customTitle.isEmpty || customEndpoint.isEmpty)
                } header: {
                    Text("扩展来源")
                } footer: {
                    Text("来源可扩展：新增类型以接入模块注册，未接入的来源会如实显示状态。选择任何来源都不会自动获得命令执行权限。")
                }
            }
            .navigationTitle("项目来源")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }
    }
}

private struct ProjectSourceTargetList: View {
    @ObservedObject var model: LocalChatModel
    let provider: ProjectSourceProvider
    let onDone: () -> Void
    @State private var selected: ProjectSourceTarget?
    @State private var path = ""

    var body: some View {
        let status = provider.status(data: model.data)
        let targets = provider.targets(data: model.data)
        List {
            Section("连接状态") {
                Label(status.text, systemImage: status.isReady ? "checkmark.circle" : "exclamationmark.circle")
                    .foregroundStyle(status.isReady ? Color.green : Color.orange)
                if !status.isReady, provider.id == "lan" || provider.id == "remote" || provider.id == "vps" {
                    Button("去添加 / 配对设备") { model.appState.onSelectRoute?("devices"); onDone() }
                }
            }
            if status.isReady {
                Section("选择设备 / 项目") {
                    if targets.isEmpty { Text("没有可选项").foregroundStyle(.secondary) }
                    ForEach(targets) { t in
                        Button {
                            selected = t
                            path = t.path ?? ""
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(t.title).foregroundStyle(.primary)
                                    if !t.subtitle.isEmpty { Text(t.subtitle).font(.caption).foregroundStyle(.secondary) }
                                }
                                Spacer()
                                if selected?.id == t.id { Image(systemName: "checkmark").foregroundStyle(.blue) }
                            }
                        }
                    }
                }
                if selected != nil {
                    Section("工作路径") {
                        TextField("路径", text: $path)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    Section {
                        Button("确认") {
                            model.projectSource = ProjectSourceBinding(
                                providerId: provider.id, targetId: selected?.id,
                                targetTitle: selected?.title, path: path.isEmpty ? nil : path)
                            if !provider.canExecute { model.permissionMode = .readOnly }
                            model.persistSessionConfig()
                            onDone()
                        }
                        .bold()
                    }
                }
            }
        }
        .navigationTitle(provider.title)
    }
}

public struct ModelAndAgentSheet: View {
    @ObservedObject var model: LocalChatModel
    var onManage: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var models: [ModelInfo] = []
    @State private var loading = false
    @State private var loadError: String?

    private var currentConfig: BrainConfig? {
        model.appState.brainSettingsModel.configs.first { $0.brainId == model.selectedBrain }
    }

    public var body: some View {
        NavigationStack {
            List {
                Section("Agent / 大脑") {
                    if model.usableBrains.isEmpty {
                        Text("还没有可用的大脑（需填写 API Key）。会员登录与 API Key 是两种不同授权，ChatGPT 会员在 iOS 尚未接入。")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    ForEach(model.usableBrains) { cfg in
                        Button {
                            model.selectedBrain = cfg.brainId
                            Task { await loadModels() }
                        } label: {
                            HStack {
                                Text(cfg.label).foregroundStyle(.primary)
                                Spacer()
                                if cfg.brainId == model.selectedBrain { Image(systemName: "checkmark").foregroundStyle(.blue) }
                            }
                        }
                    }
                    Button("管理模型与连接") { dismiss(); onManage() }
                }
                Section("模型（从服务商实时获取）") {
                    if loading { ProgressView() }
                    if let loadError { Text(loadError).font(.caption).foregroundStyle(.red) }
                    ForEach(models) { m in
                        Button {
                            if var cfg = currentConfig {
                                cfg.model = m.id
                                model.appState.brainSettingsModel.save(config: cfg, apiKey: "")
                            }
                        } label: {
                            HStack {
                                Text(m.displayName.isEmpty ? m.id : m.displayName).foregroundStyle(.primary)
                                Spacer()
                                if currentConfig?.model == m.id { Image(systemName: "checkmark").foregroundStyle(.blue) }
                            }
                        }
                    }
                }
                Section {
                    LabeledContent("智能等级", value: "该大脑接口未提供")
                    LabeledContent("速度", value: "该大脑接口未提供")
                } header: { Text("高级") } footer: {
                    Text("仅显示当前接入实际支持的参数。")
                }
                Section {
                    ForEach(ChatPermissionMode.allCases) { mode in
                        Button {
                            model.permissionMode = mode
                            model.persistSessionConfig()
                        } label: {
                            HStack {
                                Text(mode.title).foregroundStyle(mode == .readOnly ? .primary : .secondary)
                                Spacer()
                                if model.permissionMode == mode { Image(systemName: "checkmark").foregroundStyle(.blue) }
                            }
                        }
                        .disabled(mode != .readOnly)
                    }
                } header: { Text("执行权限") } footer: {
                    Text("当前对话通道不执行命令，仅「只读对话」可用；执行需在已配对设备上通过审批中心逐项批准。")
                }
            }
            .navigationTitle("模型与权限")
            .navigationBarTitleDisplayMode(.inline)
            .task { await loadModels() }
        }
    }

    private func loadModels() async {
        guard let cfg = currentConfig, let adapter = model.appState.brainRegistry.get(cfg.adapterId) else {
            models = []; return
        }
        loading = true; loadError = nil
        defer { loading = false }
        switch await adapter.listModels() {
        case .success(let list): models = list
        case .failure(let e): models = []; loadError = SecretRedactor.redact(e.localizedDescription)
        }
    }
}

public struct ChatComposerBar: View {
    @ObservedObject var model: LocalChatModel
    var focused: FocusState<Bool>.Binding
    var onSelectRoute: (String) -> Void
    @State private var showModelSheet = false
    @State private var showPhotoPicker = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showFileImporter = false
    @State private var showCamera = false

    private var brainLabel: String {
        let cfg = model.appState.brainSettingsModel.configs.first { $0.brainId == model.selectedBrain }
        guard let cfg else { return "选择模型" }
        return cfg.model.isEmpty ? cfg.label : cfg.model
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ProjectSourceChip(model: model)

            VStack(alignment: .leading, spacing: 6) {
                if !model.attachedFiles.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(model.attachedFiles, id: \.self) { file in
                                HStack(spacing: 4) {
                                    Image(systemName: "paperclip")
                                    Text(file).lineLimit(1)
                                    Button { model.attachedFiles.removeAll { $0 == file } } label: {
                                        Image(systemName: "xmark.circle.fill")
                                    }
                                }
                                .font(.caption)
                                .padding(.horizontal, 8).padding(.vertical, 4)
                                .background(Color(red: 0.94, green: 0.93, blue: 0.90), in: Capsule())
                            }
                        }
                    }
                }

                TextField("向 Agent 提问或分派任务", text: $model.draft, axis: .vertical)
                    .lineLimit(1...8)
                    .font(.system(size: 16))
                    .focused(focused)
                    .accessibilityIdentifier("chat.input")
                    .onChange(of: model.draft) { _, _ in model.persistDraft() }

                HStack(spacing: 10) {
                    Menu {
                        Button { showCamera = true } label: { Label("相机", systemImage: "camera") }
                        Button { showPhotoPicker = true } label: { Label("照片", systemImage: "photo") }
                        Button { showFileImporter = true } label: { Label("文件", systemImage: "doc") }
                        Button { onSelectRoute("mcp") } label: { Label("插件", systemImage: "puzzlepiece.extension") }
                    } label: {
                        Image(systemName: "plus")
                            .font(.system(size: 17, weight: .medium))
                            .frame(width: 32, height: 32)
                    }
                    .accessibilityIdentifier("home.plusMenu")

                    Button { showModelSheet = true } label: {
                        HStack(spacing: 3) {
                            Text(brainLabel).lineLimit(1)
                            Image(systemName: "chevron.down").font(.system(size: 9, weight: .semibold))
                        }
                        .font(.system(size: 13, weight: .medium))
                    }
                    .accessibilityIdentifier("home.agentSelectorButton")

                    Spacer()

                    Button {
                        focused.wrappedValue = true
                    } label: {
                        Image(systemName: "mic").font(.system(size: 16, weight: .medium))
                    }
                    .accessibilityLabel("语音输入（使用键盘听写）")

                    if model.isSending {
                        Button { model.stopSending() } label: {
                            Image(systemName: "stop.fill")
                                .font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
                                .frame(width: 32, height: 32).background(Color.black, in: Circle())
                        }
                        .accessibilityIdentifier("chat.stop")
                    } else if model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Button { onSelectRoute("voice") } label: {
                            Image(systemName: "waveform")
                                .font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                                .frame(width: 32, height: 32).background(Color.black, in: Circle())
                        }
                        .accessibilityLabel("语音会话")
                    } else {
                        Button { model.startSending() } label: {
                            Image(systemName: "arrow.up")
                                .font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                                .frame(width: 32, height: 32).background(Color.black, in: Circle())
                        }
                        .accessibilityIdentifier("chat.send")
                    }
                }
                .foregroundStyle(Color(red: 0.35, green: 0.34, blue: 0.32))
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)
            .padding(.bottom, 8)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 24))
            .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color(red: 0.89, green: 0.88, blue: 0.86), lineWidth: 1))
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .sheet(isPresented: $showModelSheet) {
            ModelAndAgentSheet(model: model, onManage: { onSelectRoute("models") })
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItems, maxSelectionCount: 5, matching: .images)
        .onChange(of: photoItems) { _, items in
            for item in items {
                Task {
                    if let d = try? await item.loadTransferable(type: Data.self) {
                        let name = ChatAttachmentStore.save(d, name: "photo-\(UUID().uuidString.prefix(6)).jpg")
                        model.attachedFiles.append(name)
                    }
                }
            }
            photoItems = []
        }
        .fileImporter(isPresented: $showFileImporter, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            guard case .success(let urls) = result else { return }
            for url in urls {
                let ok = url.startAccessingSecurityScopedResource()
                defer { if ok { url.stopAccessingSecurityScopedResource() } }
                if let d = try? Data(contentsOf: url) {
                    model.attachedFiles.append(ChatAttachmentStore.save(d, name: url.lastPathComponent))
                }
            }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraCaptureView { data in
                if let data {
                    model.attachedFiles.append(ChatAttachmentStore.save(data, name: "camera-\(UUID().uuidString.prefix(6)).jpg"))
                }
                showCamera = false
            }
            .ignoresSafeArea()
        }
    }
}

#if canImport(UIKit)
import UIKit

struct CameraCaptureView: UIViewControllerRepresentable {
    let onFinish: (Data?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onFinish: (Data?) -> Void
        init(onFinish: @escaping (Data?) -> Void) { self.onFinish = onFinish }
        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            let image = info[.originalImage] as? UIImage
            onFinish(image?.jpegData(compressionQuality: 0.8))
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { onFinish(nil) }
    }
}
#endif
