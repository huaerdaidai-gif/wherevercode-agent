import Foundation
import SwiftUI

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
        reload()
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
        conversationID = nil
        data.chatPrefs.currentSessionId = nil
        draft = ""
        messages = []
        streaming = ""
        error = nil
    }

    public func open(_ conversation: Conversation) {
        guard !isSending else { return }
        conversationID = conversation.id
        data.chatPrefs.currentSessionId = conversation.id
        selectedBrain = conversation.brainId ?? appState.brainConfigStore.currentBrainId() ?? ""
        error = nil
        draft = ""
        streaming = ""
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

    public func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isSending else { return }
        guard !selectedBrain.isEmpty else {
            error = "请在模型与连接中配置并选择模型"
            return
        }
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
                conversationID = conversation.id
                data.chatPrefs.currentSessionId = conversation.id
            }
            guard let id = conversationID else { return }
            draft = ""
            let engine = appState.makeBrainEngine(
                conversations: data.conversations,
                tools: ChatUnavailableTools(),
                eventLog: eventLog,
                approvalGate: DenyApprovalGate()
            )
            for try await event in engine.chat(
                conversationId: id,
                userText: text,
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

                                Text(connectionSummaryText)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                                    .padding(.horizontal, 24)

                                HStack(spacing: 10) {
                                    suggestion("检查项目并修复问题", icon: "wrench.and.screwdriver")
                                    suggestion("解释这段代码", icon: "doc.text.magnifyingglass")
                                }
                                .padding(.top, 8)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 32)
                        } else {
                            ForEach(model.messages) { message in
                                VStack(alignment: message.role == "user" ? .trailing : .leading, spacing: 6) {
                                    HStack {
                                        Text(message.role == "user" ? "你" : "Agent (\(message.source))")
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                        Spacer()
                                    }
                                    Text(message.content)
                                        .textSelection(.enabled)
                                        .padding(14)
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
                }
                .onChange(of: model.messages.count) { _, _ in
                    if let lastId = model.messages.last?.id {
                        withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
                    }
                }
            }

            // 底部 Claude 风格悬浮胶囊输入栏
            VStack(spacing: 8) {
                if !model.attachedFiles.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(model.attachedFiles, id: \.self) { file in
                                HStack(spacing: 4) {
                                    Image(systemName: "paperclip")
                                    Text(file)
                                    Button {
                                        model.attachedFiles.removeAll { $0 == file }
                                    } label: {
                                        Image(systemName: "xmark.circle.fill")
                                    }
                                }
                                .font(.caption)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(Color(red: 0.94, green: 0.93, blue: 0.90), in: Capsule())
                            }
                        }
                    }
                }

                HStack(alignment: .center, spacing: 12) {
                    Menu {
                        Button { onSelectRoute("files") } label: {
                            Label("导入/浏览本地沙盒文件", systemImage: "folder")
                        }
                        Button { onSelectRoute("projects") } label: {
                            Label("项目 · 查看已保存位置", systemImage: "shippingbox")
                        }
                        Button { onSelectRoute("compose") } label: {
                            Label("转为正式任务派单 (compose)", systemImage: "checklist")
                        }
                        Button { onSelectRoute("voice") } label: {
                            Label("语音与多模态附件 (voice)", systemImage: "waveform")
                        }
                        Button { model.newConversation() } label: {
                            Label("新建空白对话", systemImage: "square.and.pencil")
                        }
                        Button { model.showHistory = true } label: {
                            Label("查看历史会话 (history)", systemImage: "clock.arrow.circlepath")
                        }
                    } label: {
                        Text("＋")
                            .font(.system(size: 22, weight: .regular))
                            .foregroundStyle(Color(red: 0.35, green: 0.34, blue: 0.32))
                            .frame(width: 34, height: 34)
                    }
                    .accessibilityIdentifier("home.plusMenu")

                    TextField("输入或长按说话", text: $model.draft, axis: .vertical)
                        .lineLimit(1...5)
                        .font(.system(size: 16))
                        .accessibilityIdentifier("chat.input")
                        .submitLabel(.send)
                        .onSubmit {
                            model.startSending()
                        }

                    Button {
                        onSelectRoute("voice")
                    } label: {
                        Image(systemName: "mic")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityLabel("语音输入")

                    if model.isSending {
                        Button {
                            model.stopSending()
                        } label: {
                            Image(systemName: "stop.fill")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 34, height: 34)
                                .background(Color.black, in: Circle())
                        }
                        .accessibilityIdentifier("chat.stop")
                        .accessibilityLabel("停止当前生成")
                    } else {
                        Button {
                            model.startSending()
                        } label: {
                            Image(systemName: model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "waveform" : "arrow.up")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(width: 34, height: 34)
                                .background(Color(red: 0.08, green: 0.08, blue: 0.08), in: Circle())
                        }
                        .accessibilityIdentifier("chat.send")
                    }
                }
                .padding(.horizontal, 15)
                .padding(.vertical, 10)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 30))
                .overlay(
                    RoundedRectangle(cornerRadius: 30)
                        .stroke(Color(red: 0.89, green: 0.88, blue: 0.86), lineWidth: 1)
                )
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 14)
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
            NavigationStack {
                List {
                    Section("当前推理大脑 (Brain)") {
                        Picker("选择大脑", selection: $model.selectedBrain) {
                            Text("未选择模型").tag("")
                            ForEach(appState.brainSettingsModel.configs) { cfg in
                                Text("\(cfg.label) (\(cfg.model.isEmpty ? "未设模型" : cfg.model))").tag(cfg.brainId)
                            }
                        }
                        Button("管理模型与连接 (models)") {
                            showAgentSelectorSheet = false
                            onSelectRoute("models")
                        }
                    }
                    Section("执行目标环境区分（严禁伪装本地执行）") {
                        Picker("执行目标", selection: $model.selectedExecutionTarget) {
                            Text("iPhone 本机大脑对话 (Sandbox 只读/受限)").tag("local_brain")
                            Text("远端设备 Hand 执行 (需配对授权)").tag("remote_hand")
                            Text("云端 Agent 执行 (V5 平台 MCP)").tag("cloud_mcp")
                        }
                    }
                }
                .navigationTitle("Agent 与模型切换")
            }
        }
    }

    @ViewBuilder
    private func suggestion(_ title: String, icon: String) -> some View {
        Button {
            model.draft = title
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
