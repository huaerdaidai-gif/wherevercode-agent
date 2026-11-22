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
    @Published public var selectedAgent: AgentRecord? = nil
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
        self.selectedBrain = ""
        self.selectedAgent = nil
        self.conversationID = data.chatPrefs.currentSessionId
        if let id = conversationID, let conversation = try? data.conversations.getConversation(id: id) {
            self.selectedBrain = conversation.brainId ?? ""
        }
        restoreSessionConfig()
        reload()
    }

    // MARK: - Filtered Messages for UI (Single Agent / Speaker)

    /// Messages visible to user in the chat UI.
    /// Internal protocol messages (tool calls, tool results) are filtered out so that
    /// the user never sees multiple duplicate "Assistant" bubbles for a single turn.
    public var visibleMessages: [ConversationMessage] {
        messages.filter { msg in
            if msg.role == "tool" { return false }
            if msg.source.hasPrefix("tool-call:") || msg.source.hasPrefix("tool:") { return false }
            if msg.role != "user" && msg.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return false }
            return true
        }
    }

    // MARK: Session config + draft persistence + default resources (P0)

    public var defaultBrainId: String {
        get { (try? data.kv.get("chat.defaultBrainId")) ?? nil ?? "" }
        set {
            try? data.kv.set("chat.defaultBrainId", newValue.isEmpty ? nil : newValue)
            appState.modelSettingsModel.configStore.setCurrentModelId(newValue.isEmpty ? nil : newValue)
            appState.modelSettingsModel.reload()
            appState.modelSettingsModel.onChange()
        }
    }

    public var defaultAgentId: String? {
        get { (try? data.kv.get("chat.defaultAgentId")) ?? nil }
        set {
            try? data.kv.set("chat.defaultAgentId", (newValue?.isEmpty ?? true) ? nil : newValue)
        }
    }

    public var defaultSelectionId: String {
        if let defAid = defaultAgentId, !defAid.isEmpty {
            return "agent:\(defAid)"
        }
        if !defaultBrainId.isEmpty {
            return "brain:\(defaultBrainId)"
        }
        return ""
    }

    public enum SelectionType {
        case brain
        case agent
    }

    public func setDefaultSelection(type: SelectionType, id: String) {
        switch type {
        case .brain:
            defaultAgentId = nil
            defaultBrainId = id
            selectBrainInCurrentSession(id)
        case .agent:
            defaultBrainId = ""
            defaultAgentId = id
            if let ag = (try? data.agents.getAgent(agentId: id)) {
                selectAgentInCurrentSession(ag)
            }
        }
    }

    private var draftKey: String { "chat.draft.\(conversationID ?? "new")" }
    private var configKey: String? { conversationID.map { "chat.sessionConfig.\($0)" } }

    private func restoreSessionConfig() {
        let cfg = configKey.flatMap { try? data.kv.getJSON($0, as: ChatSessionConfig.self) }
        projectSource = cfg?.source ?? .localDefault
        permissionMode = cfg?.permission ?? .readOnly

        // Strictly restore either Agent OR Brain (mutually exclusive)
        if let aid = cfg?.agentId, !aid.isEmpty, let ag = try? data.agents.getAgent(agentId: aid) {
            selectedAgent = ag
            selectedBrain = cfg?.brainId ?? defaultBrainId
        } else if let bid = cfg?.brainId, !bid.isEmpty {
            selectedBrain = bid
            selectedAgent = nil
        } else if let convId = conversationID, let conv = try? data.conversations.getConversation(id: convId), let bid = conv.brainId, !bid.isEmpty {
            selectedBrain = bid
            selectedAgent = nil
        } else if let defAid = defaultAgentId, !defAid.isEmpty, let ag = try? data.agents.getAgent(agentId: defAid) {
            selectedAgent = ag
            selectedBrain = defaultBrainId
        } else if !defaultBrainId.isEmpty {
            selectedBrain = defaultBrainId
            selectedAgent = nil
        } else {
            selectedBrain = ""
            selectedAgent = nil
        }
        draft = (try? data.kv.get(draftKey)) ?? nil ?? ""
    }

    public func persistSessionConfig() {
        guard let key = configKey else { return }
        try? data.kv.setJSON(key, ChatSessionConfig(
            source: projectSource,
            permission: permissionMode,
            brainId: selectedAgent == nil ? (selectedBrain.isEmpty ? nil : selectedBrain) : nil,
            agentId: selectedAgent?.agentId
        ))
        if let cid = conversationID {
            try? data.conversations.updateConversationBrain(
                id: cid,
                brainId: selectedAgent == nil ? (selectedBrain.isEmpty ? nil : selectedBrain) : nil
            )
        }
    }

    public func selectBrainInCurrentSession(_ brainId: String) {
        selectedBrain = brainId
        selectedAgent = nil
        persistSessionConfig()
    }

    public func selectAgentInCurrentSession(_ agent: AgentRecord?) {
        selectedAgent = agent
        persistSessionConfig()
    }

    public func setAsDefaultBrain(_ brainId: String) {
        setDefaultSelection(type: .brain, id: brainId)
    }

    public func setAsDefaultAgent(_ agent: AgentRecord?) {
        if let ag = agent {
            setDefaultSelection(type: .agent, id: ag.agentId)
        } else {
            defaultAgentId = nil
            selectAgentInCurrentSession(nil)
        }
    }

    public func persistDraft() {
        try? data.kv.set(draftKey, draft.isEmpty ? nil : draft)
    }

    /// Brains that can actually serve a chat right now.
    public var usableBrains: [BrainConfig] {
        let configs = appState.compositeBrainConfigStore.listConfigs()
        return configs.filter { cfg in
            let modelCfg = ModelConfig.fromBrainConfig(cfg)
            return appState.modelSettingsModel.unusableReason(for: modelCfg) == nil
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
            if cfg.authRef == nil || appState.modelSettingsModel.hasKey(for: ModelConfig.fromBrainConfig(cfg)) {
                return (selectedBrain, nil)
            }
        }
        let usable = usableBrains
        if let current = appState.modelSettingsModel.currentModelId,
           usable.contains(where: { $0.brainId == current }) {
            return (current, nil)
        }
        if let first = usable.first { return (first.brainId, nil) }
        if let cfg = appState.compositeBrainConfigStore.getConfig(brainId: selectedBrain),
           let reason = appState.modelSettingsModel.unusableReason(for: ModelConfig.fromBrainConfig(cfg)) {
            return (nil, "「\(cfg.label)」不可用：\(reason)。请到 设置 › 模型 连接一个可用的模型。")
        }
        if let cfg = appState.brainRegistry.configFor(selectedBrain),
           appState.brainRegistry.get(cfg.adapterId) != nil {
            return (selectedBrain, nil)
        }
        return (nil, "还没有可用的模型：请到 设置 › 模型 填写 API Key 并设为当前模型。")
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

    // MARK: - 新建项目待绑定（本机 / 远程来源离开聊天去建项目时）

    private var pendingProjectBindKey: String { "chat.pendingProjectBindIds" }

    /// 离开聊天去建项目（文件页 / 设备页）前记下当前项目 id 集合；
    /// 回到聊天时若有新项目出现，自动绑最新建的那个（与安卓
    /// 「建好自动切过去」同口径，见 consumePendingProjectBindIfNeeded）。
    public func markPendingProjectBind() {
        let ids = ((try? data.projects.listProjects()) ?? []).map { $0.project.projectId }
        try? data.kv.setJSON(pendingProjectBindKey, ids)
    }

    /// 显式选定项目（名单点选 / git 当场建好）时清掉待绑定标记，
    /// 防它日后误绑无关的新项目。
    public func clearPendingProjectBind() {
        try? data.kv.setJSON(pendingProjectBindKey, nil as [String]?)
    }

    /// 聊天页出现时消费待绑定标记：有新建项目则绑最新一个并清
    /// 标记；还没有新项目则保留标记（可能还没建完）。
    public func consumePendingProjectBindIfNeeded() {
        guard let known = (try? data.kv.getJSON(pendingProjectBindKey, as: [String].self)) ?? nil else { return }
        let projects = (try? data.projects.listProjects()) ?? []
        let fresh = projects.filter { !known.contains($0.project.projectId) }
        guard let newest = fresh.max(by: { $0.project.createdAtEpochSeconds < $1.project.createdAtEpochSeconds }) else { return }
        clearPendingProjectBind()
        projectSource = ProjectSourceBinding(
            providerId: "local", targetId: newest.project.projectId,
            targetTitle: newest.project.name, path: newest.activeHome?.workspacePath)
        permissionMode = .readOnly
        persistSessionConfig()
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
        // 新对话继承当前对话的项目绑定（2026-11-22 定，对齐
        // GPT/Claude 项目模式：从项目里开新对话就属于该项目），
        // 不再重置为沙盒。新对话建立后，绑定随其会话配置落盘，
        // 每条对话各自保留自己的绑定。
        if let defAid = defaultAgentId, !defAid.isEmpty, let ag = try? data.agents.getAgent(agentId: defAid) {
            selectedAgent = ag
            selectedBrain = defaultBrainId
        } else if !defaultBrainId.isEmpty {
            selectedBrain = defaultBrainId
            selectedAgent = nil
        } else {
            selectedBrain = ""
            selectedAgent = nil
        }
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

    public func deleteConversation(_ conversation: Conversation) {
        guard !isSending else {
            error = "正在回复中，无法删除会话"
            return
        }
        let id = conversation.id
        _ = try? data.conversations.deleteConversation(id: id)
        try? data.kv.set("chat.draft.\(id)", nil)
        try? data.kv.set("chat.sessionConfig.\(id)", nil)
        if conversationID == id {
            conversationID = nil
            data.chatPrefs.currentSessionId = nil
            messages = []
            streaming = ""
            draft = ""
            selectedBrain = defaultBrainId
            if let defAid = defaultAgentId, !defAid.isEmpty {
                selectedAgent = (try? data.agents.getAgent(agentId: defAid))
            } else {
                selectedAgent = nil
            }
        }
        reload()
    }

    public func deleteConversations(at offsets: IndexSet, in list: [Conversation]) {
        for index in offsets {
            guard index < list.count else { continue }
            deleteConversation(list[index])
        }
    }

    public func startSending() {
        guard currentTurn == nil, !isSending else { return }
        currentTurn = Task {
            await send()
            currentTurn = nil
        }
    }

    public func stopSending() {
        if let agent = selectedAgent, agent.kind == .device, let hostRaw = agent.baseUrl {
            let clean = hostRaw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
            let parts = clean.split(separator: ":")
            let host = String(parts[0])
            let port = parts.count >= 2 ? (Int(parts[1]) ?? 18743) : 18743
            let targetDev = agent.deviceId ?? ""
            let folder = agent.deviceFolder ?? ""
            let realAgentId = agent.agentId.contains(":") ? String(agent.agentId.split(separator: ":").last!) : agent.agentId
            Task {
                _ = try? await HandClient.shared.sendControl(host: host, port: port, targetDeviceId: targetDev, folder: folder, agentId: realAgentId, action: "stop")
            }
        }
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
            if let agent = selectedAgent, agent.kind == .device, let hostRaw = agent.baseUrl {
                let clean = hostRaw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
                let parts = clean.split(separator: ":")
                let host = String(parts[0])
                let port = parts.count >= 2 ? (Int(parts[1]) ?? 18743) : 18743
                let targetDev = agent.deviceId ?? ""
                let folder = agent.deviceFolder ?? ""
                let realAgentId = agent.agentId.contains(":") ? String(agent.agentId.split(separator: ":").last!) : agent.agentId
                Task {
                    _ = try? await HandClient.shared.sendControl(host: host, port: port, targetDeviceId: targetDev, folder: folder, agentId: realAgentId, action: "pause")
                }
            }
            if let working = (try? data.tasks.listTasks())?.first(where: { $0.state == "working" }) {
                _ = try? data.tasks.setPaused(taskId: working.taskId, isPaused: true)
                eventLog.append(conversationId: conversationID ?? "conv", kind: "TASK_PAUSED", detail: "任务已通过自然语言暂停。")
                appendSystemNotice("已暂停任务「\(working.title)」。可通过自然语言输入「继续」恢复执行，或输入「停止」取消任务。")
            } else {
                appendSystemNotice("已向设备发送暂停指令。")
            }
            return
        }

        if text == "继续" || text == "继续执行" || text == "恢复" {
            draft = ""
            if let agent = selectedAgent, agent.kind == .device, let hostRaw = agent.baseUrl {
                let clean = hostRaw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
                let parts = clean.split(separator: ":")
                let host = String(parts[0])
                let port = parts.count >= 2 ? (Int(parts[1]) ?? 18743) : 18743
                let targetDev = agent.deviceId ?? ""
                let folder = agent.deviceFolder ?? ""
                let realAgentId = agent.agentId.contains(":") ? String(agent.agentId.split(separator: ":").last!) : agent.agentId
                Task {
                    _ = try? await HandClient.shared.sendControl(host: host, port: port, targetDeviceId: targetDev, folder: folder, agentId: realAgentId, action: "resume")
                }
            }
            if let paused = (try? data.tasks.listTasks())?.first(where: { $0.isPaused }) {
                _ = try? data.tasks.setPaused(taskId: paused.taskId, isPaused: false)
                eventLog.append(conversationId: conversationID ?? "conv", kind: "TASK_RESUMED", detail: "任务已通过自然语言恢复。")
                appendSystemNotice("已恢复任务「\(paused.title)」的执行。")
            } else {
                appendSystemNotice("已向设备发送恢复指令。")
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

        if let agent = selectedAgent {
            // 单选模式：当前明确选定了 Agent，由目标 Agent 独立且完整回复，绝不触发大脑二次回复
            isSending = true
            error = nil
            streaming = ""
            var activeTaskId: String? = nil
            defer {
                isSending = false
                streaming = ""
                reload()
            }
            do {
                if conversationID == nil {
                    let conversation = try data.conversations.createConversation(
                        brainId: nil,
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

                let taskTitle = String(text.prefix(32))
                if let task = try? data.tasks.createTask(
                    LocalTask(
                        title: taskTitle,
                        description: text,
                        priority: 0,
                        brainId: agent.displayName
                    )
                ) {
                    activeTaskId = task.taskId
                    _ = try? data.tasks.transition(taskId: task.taskId, to: "working", note: "分派给 \(agent.displayName)")
                    eventLog.append(conversationId: id, kind: "TASK_DISPATCH", detail: "已在本机 SQLite 登记任务 \(task.taskId)")
                }

                guard agent.kind == .device else {
                    let errMsg = "当前选中的 Agent 类型「\(agent.kind.rawValue)」在 iOS 端尚未开放直接对话。"
                    self.error = errMsg
                    if let tid = activeTaskId {
                        _ = try? data.tasks.transition(taskId: tid, to: "failed", note: errMsg)
                    }
                    let assistantMsg = ConversationMessage(
                        conversationId: id,
                        role: "assistant",
                        content: errMsg,
                        source: agent.displayName,
                        taskId: activeTaskId
                    )
                    try? data.conversations.appendMessage(assistantMsg)
                    reload()
                    return
                }

                guard let hostRaw = agent.baseUrl ?? ((try? data.agents.listAgents())?.first(where: { $0.deviceId == agent.deviceId && $0.baseUrl != nil })?.baseUrl), !hostRaw.isEmpty else {
                    let errMsg = "设备「\(agent.deviceId ?? "未知")」离线或未配置连接地址，无法执行任务。"
                    if let tid = activeTaskId {
                        _ = try? data.tasks.transition(taskId: tid, to: "failed", note: errMsg)
                    }
                    self.error = errMsg
                    let assistantMsg = ConversationMessage(
                        conversationId: id,
                        role: "assistant",
                        content: errMsg,
                        source: agent.displayName,
                        taskId: activeTaskId
                    )
                    try? data.conversations.appendMessage(assistantMsg)
                    reload()
                    return
                }

                let clean = hostRaw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
                let parts = clean.split(separator: ":")
                let host = String(parts[0])
                let port = parts.count >= 2 ? (Int(parts[1]) ?? 18743) : 18743
                let targetDev = agent.deviceId ?? ""

                let projectFolder = (projectSource.targetId == targetDev || projectSource.providerId == "device") ? (projectSource.path ?? "") : ""
                let rawFolder = !projectFolder.isEmpty ? projectFolder :
                                ((agent.deviceFolder?.isEmpty == false) ? agent.deviceFolder! :
                                ((try? data.agents.listAgents())?.first(where: { $0.deviceId == targetDev && $0.deviceFolder?.isEmpty == false })?.deviceFolder ?? ""))
                let folder = !rawFolder.isEmpty ? rawFolder : (agent.deviceFolder ?? "")

                let realAgentId = agent.agentId.contains(":") ? String(agent.agentId.split(separator: ":").last!) : agent.agentId

                var attachmentNotice = ""
                for name in attachedFiles {
                    let url = ChatAttachmentStore.url(for: name)
                    if let d = try? Data(contentsOf: url) {
                        let relPath = "attachments/\(name)"
                        _ = try? await HandClient.shared.writeFile(
                            host: host,
                            port: port,
                            targetDeviceId: targetDev,
                            folder: folder,
                            agentId: realAgentId,
                            path: relPath,
                            content: d.base64EncodedString(),
                            encoding: "base64"
                        )
                        attachmentNotice += "\n- 附件已上传至项目路径：\(relPath)"
                    }
                }

                let finalPrompt = outgoing + (attachmentNotice.isEmpty ? "" : "\n\n[已传输附件]" + attachmentNotice)

                let userMsg = ConversationMessage(conversationId: id, role: "user", content: finalPrompt, source: "user", taskId: activeTaskId)
                try? data.conversations.appendMessage(userMsg)
                reload()

                let output = try await HandClient.shared.runAgent(
                    host: host,
                    port: port,
                    targetDeviceId: targetDev,
                    folder: folder,
                    agentId: realAgentId,
                    prompt: finalPrompt,
                    timeoutSeconds: 60.0
                ) { progress in
                    Task { @MainActor in
                        self.streaming = progress
                    }
                }

                let assistantMsg = ConversationMessage(
                    conversationId: id,
                    role: "assistant",
                    content: output.isEmpty ? "（任务已执行完毕，无控制台输出）" : output,
                    source: agent.displayName,
                    taskId: activeTaskId
                )
                try? data.conversations.appendMessage(assistantMsg)

                if let tid = activeTaskId {
                    _ = try? data.tasks.transition(taskId: tid, to: "completed", note: "Agent 执行完毕")
                }
            } catch is CancellationError {
                self.error = "回复已停止"
                if let tid = activeTaskId {
                    _ = try? data.tasks.transition(taskId: tid, to: "cancelled", note: "任务已取消")
                }
                reload()
            } catch {
                let errorText = SecretRedactor.redact(error.localizedDescription)
                self.error = errorText
                if let tid = activeTaskId {
                    _ = try? data.tasks.transition(taskId: tid, to: "failed", note: errorText)
                }
                let failMsg = ConversationMessage(
                    conversationId: conversationID ?? "conv",
                    role: "assistant",
                    content: "任务执行失败：\(errorText)",
                    source: agent.displayName,
                    taskId: activeTaskId
                )
                try? data.conversations.appendMessage(failMsg)
                reload()
                if messages.last?.content != text { draft = text }
            }
            return
        }

        // selectedAgent == nil: 大脑直接对话与推理模式
        let (resolvedBrain, brainError) = resolveBrainForSend()
        guard let resolvedBrain else {
            error = brainError
            return
        }
        selectedBrain = resolvedBrain
        isSending = true
        error = nil
        streaming = ""
        var activeTaskId: String? = nil
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

            let trimmedText = text.trimmingCharacters(in: .whitespacesAndNewlines)
            let lowerText = trimmedText.lowercased()
            let isGreetingOrSmallTalk = lowerText == "在吗" || lowerText == "你好" || lowerText == "在不在" ||
                                        lowerText == "hi" || lowerText == "hello" || lowerText.hasPrefix("你好") ||
                                        lowerText == "在吗？" || lowerText == "在吗?" || lowerText == "你是谁" || lowerText == "你能做什么"

            let isTaskIntent = !isGreetingOrSmallTalk && (
                text.contains("任务") || text.contains("实现") || text.contains("编写") ||
                text.contains("修复") || text.contains("测试") || text.contains("开发") ||
                text.contains("运行") || text.contains("执行") || text.contains("构建") ||
                text.contains("代码") || text.contains("写") || text.contains("改")
            )

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

            let toolRegistry: ToolRegistry
            if isGreetingOrSmallTalk {
                toolRegistry = ChatUnavailableTools()
            } else {
                let targetDevObj = (try? data.agents.listAgents())?.first(where: { $0.deviceId == projectSource.targetId && $0.memberConnectionState == "CONNECTED" }) ??
                                   (try? data.agents.listAgents())?.first(where: { $0.kind == .device && $0.memberConnectionState == "CONNECTED" })

                if let dev = targetDevObj, let hostRaw = dev.baseUrl ?? ((try? data.agents.listAgents())?.first(where: { $0.deviceId == dev.deviceId && $0.baseUrl != nil })?.baseUrl) {
                    let clean = hostRaw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
                    let parts = clean.split(separator: ":")
                    let host = String(parts[0])
                    let port = parts.count >= 2 ? (Int(parts[1]) ?? 18743) : 18743

                    let targetDevId = dev.deviceId ?? dev.agentId
                    let projectFolder = (projectSource.targetId == targetDevId || projectSource.providerId == "device") ? (projectSource.path ?? "") : ""
                    let rawFolder = !projectFolder.isEmpty ? projectFolder :
                                    ((dev.deviceFolder?.isEmpty == false) ? dev.deviceFolder! : "")
                    let folder = !rawFolder.isEmpty ? rawFolder : (dev.deviceFolder ?? "")

                    let boundAgentId = (try? data.agents.listAgents())?.first(where: { $0.deviceId == targetDevId && $0.agentId != targetDevId })?.agentId ?? ""
                    let realAgentId = boundAgentId.contains(":") ? String(boundAgentId.split(separator: ":").last!) : boundAgentId

                    toolRegistry = HandCrossDeviceToolRegistry(host: host, port: port, targetDeviceId: targetDevId, agentId: realAgentId, defaultFolder: folder)
                } else {
                    toolRegistry = ChatUnavailableTools()
                }
            }

            let engine = appState.makeBrainEngine(
                conversations: data.conversations,
                tools: toolRegistry,
                eventLog: eventLog,
                approvalGate: DenyApprovalGate()
            )
            let images = collectImageDataUrls()
            for try await event in engine.chat(
                conversationId: id,
                userText: outgoing,
                brainIdOverride: selectedBrain,
                allowFailover: isGreetingOrSmallTalk,
                taskId: activeTaskId,
                imageDataUrls: images
            ) {
                try Task.checkCancellation()
                switch event {
                case .textDelta(let delta): streaming += delta
                case .error(let info): error = info.message
                default: break
                }
            }

            if let tid = activeTaskId {
                _ = try? data.tasks.transition(taskId: tid, to: "completed", note: "Agent 任务轮次完成")
            }
        } catch is CancellationError {
            self.error = "回复已停止"
            if let tid = activeTaskId {
                _ = try? data.tasks.transition(taskId: tid, to: "cancelled", note: "任务已取消")
            }
            reload()
        } catch {
            let errorText = SecretRedactor.redact(error.localizedDescription)
            self.error = errorText
            if let tid = activeTaskId {
                _ = try? data.tasks.transition(taskId: tid, to: "failed", note: errorText)
                let failMsg = ConversationMessage(
                    conversationId: conversationID ?? "conv",
                    role: "assistant",
                    content: "任务执行失败：\(errorText)",
                    source: "系统",
                    taskId: tid
                )
                try? data.conversations.appendMessage(failMsg)
            }
            reload()
            if messages.last?.content != text { draft = text }
        }
    }

    public func send(userText: String) async {
        draft = userText
        await send()
    }

    /// 提取本轮所有图片附件为 Data URL 供大脑多模态使用
    private func collectImageDataUrls() -> [String] {
        var urls: [String] = []
        for name in attachedFiles {
            let lower = name.lowercased()
            if lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg") || lower.hasSuffix(".png") || lower.hasSuffix(".webp") || lower.hasSuffix(".gif") {
                let url = ChatAttachmentStore.url(for: name)
                if let d = try? Data(contentsOf: url), d.count <= 5 * 1024 * 1024 {
                    let mime: String
                    if lower.hasSuffix(".png") { mime = "image/png" }
                    else if lower.hasSuffix(".webp") { mime = "image/webp" }
                    else if lower.hasSuffix(".gif") { mime = "image/gif" }
                    else { mime = "image/jpeg" }
                    urls.append("data:\(mime);base64,\(d.base64EncodedString())")
                }
            }
        }
        return urls
    }

    /// Text attachments are inlined (bounded); binary attachments are
    /// named honestly with file sizes.
    private func attachmentContext() -> String {
        guard !attachedFiles.isEmpty else { return "" }
        var out = "\n\n[附件]"
        for name in attachedFiles {
            let url = ChatAttachmentStore.url(for: name)
            if let d = try? Data(contentsOf: url) {
                if d.count <= 64 * 1024, let text = String(data: d, encoding: .utf8) {
                    out += "\n--- \(name) ---\n\(text)"
                } else {
                    let lower = name.lowercased()
                    let isImg = lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg") || lower.hasSuffix(".png") || lower.hasSuffix(".webp") || lower.hasSuffix(".gif")
                    let kind = isImg ? "图片" : "二进制文件"
                    out += "\n- \(name)（\(kind)，\(d.count / 1024) KB，已作为多模态图像/任务附件传递）"
                }
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

/// Claude 极简风格对话首页（严格遵循设计冻结要求：顶部圆按钮、中部 ∞ 品牌字标与问候、底部悬浮胶囊输入栏，无底部 Tab Bar、无功能宫格、无首页统计卡片）
public struct LocalChatHomeView: View {
    @ObservedObject public var appState: AppState
    @ObservedObject public var model: LocalChatModel
    public var onOpenDrawer: () -> Void
    public var onSelectRoute: (String) -> Void

    @State private var greetingDismissed = false
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

    // 照片走查批 B：状态行字段与安卓统一为 [当前设备 · 对话方 · 平台状态]——
    // 删「模式：模型对话」，对话方取与作曲区选择器同一来源（见 partnerLabel）。
    private var statusPartnerName: String {
        if let ag = model.selectedAgent { return ag.displayName }
        let cfg = model.appState.compositeBrainConfigStore.getConfig(brainId: model.selectedBrain)
        if let cfg { return cfg.model.isEmpty ? cfg.label : cfg.model }
        return "WhereverCode"
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
        return "当前设备：本机 · 对话方：\(statusPartnerName) · \(platformLabel)"
    }

    private var filteredHistory: [Conversation] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return model.history }
        return model.history.filter { $0.title.localizedCaseInsensitiveContains(q) }
    }

    public var body: some View {
        VStack(spacing: 0) {
            // 顶部极简按钮栏（左：四父级抽屉按钮；右：搜索会话 / 删除会话 / 新对话）
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

                if model.conversationID != nil {
                    Button {
                        if let convId = model.conversationID, let conv = model.history.first(where: { $0.id == convId }) {
                            model.deleteConversation(conv)
                        } else {
                            model.newConversation()
                        }
                    } label: {
                        Image(systemName: "trash")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(Color.red.opacity(0.8))
                            .frame(width: 38, height: 38)
                            .background(Color.white, in: Circle())
                            .overlay(Circle().stroke(Color(red: 0.87, green: 0.86, blue: 0.84), lineWidth: 1))
                    }
                    .accessibilityLabel("删除当前会话")
                }

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
                .accessibilityIdentifier("chat.newConversation")
                .accessibilityLabel("新对话")
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 8)

            Text(connectionSummaryText)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
                .padding(.horizontal, 16)
                .accessibilityIdentifier("chat.connectionSummary")

            // 主体消息流或 Claude 风格极简留白问候区
            // 对话组件收敛：列表脚手架与频道页共用 DialogMessageListView
            // （消息数变化滚到最后一条、点内容区收键盘，口径不变）。
            DialogMessageListView(
                spacing: 16,
                horizontalPadding: 20,
                bottomPadding: 16,
                scrollTrigger: model.visibleMessages.count,
                scrollTargetID: model.visibleMessages.last?.id,
                onTapContent: { inputFocused = false }
            ) {
                        if model.visibleMessages.isEmpty && !model.isSending && !greetingDismissed {
                            Spacer(minLength: 88)
                            VStack(spacing: 14) {
                                Text("∞")
                                    .font(.system(size: 52, weight: .medium))
                                    .foregroundStyle(Color(red: 123 / 255.0, green: 92 / 255.0, blue: 1.0))

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
                            ForEach(model.visibleMessages) { message in
                                VStack(alignment: message.role == "user" ? .trailing : .leading, spacing: 6) {
                                    HStack {
                                        if message.role == "user" { Spacer() }
                                        Text(messageSenderName(message))
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

            } trailing: {
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
            .scrollDismissesKeyboard(.interactively)

            // Codex 参考输入框：来源选择器在上，输入框随键盘上移
            ChatComposerBar(model: model, focused: $inputFocused, onSelectRoute: onSelectRoute)
        }
        .background(Color(red: 0.98, green: 0.976, blue: 0.968).ignoresSafeArea())
        .onAppear { model.consumePendingProjectBindIfNeeded() }
        .sheet(isPresented: $model.showHistory) {
            NavigationStack {
                List {
                    Section {
                        Button("新建对话") {
                            model.newConversation()
                            model.showHistory = false
                        }
                    }
                    Section {
                        if filteredHistory.isEmpty {
                            Text("暂无保存的本地会话").foregroundStyle(.secondary)
                        } else {
                            ForEach(filteredHistory) { conv in
                                HStack {
                                    Button {
                                        model.open(conv)
                                    } label: {
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(conv.title.isEmpty ? conv.id : conv.title)
                                                .font(.headline)
                                                .foregroundStyle(.primary)
                                            Text("模型：\(conv.brainId ?? "未指定")")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    Spacer()
                                    Button {
                                        model.deleteConversation(conv)
                                    } label: {
                                        Image(systemName: "trash")
                                            .font(.system(size: 14))
                                            .foregroundStyle(Color.red.opacity(0.8))
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel("删除会话")
                                }
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button(role: .destructive) {
                                        model.deleteConversation(conv)
                                    } label: {
                                        Label("删除", systemImage: "trash")
                                    }
                                }
                                .contextMenu {
                                    Button(role: .destructive) {
                                        model.deleteConversation(conv)
                                    } label: {
                                        Label("删除会话", systemImage: "trash")
                                    }
                                    Button {
                                        model.forkConversation(conv)
                                    } label: {
                                        Label("分叉会话", systemImage: "arrow.triangle.branch")
                                    }
                                }
                            }
                            .onDelete { offsets in
                                model.deleteConversations(at: offsets, in: filteredHistory)
                            }
                        }
                    } header: { Text("历史会话").textCase(nil) }
                }
                .navigationTitle("会话")
                .searchable(text: $query, prompt: "搜索历史会话")
                .toolbar {
                    Button("完成") { model.showHistory = false }
                }
            }
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

    private func messageSenderName(_ message: ConversationMessage) -> String {
        if message.role == "user" { return "你" }
        if message.source == "codex_control" { return "系统" }
        if message.source.hasPrefix("brain:") {
            let bid = String(message.source.dropFirst(6))
            if let cfg = model.appState.compositeBrainConfigStore.getConfig(brainId: bid) {
                return cfg.label
            }
            return bid
        }
        if !message.source.isEmpty && message.source != "assistant" && message.source != "user" && !message.source.hasPrefix("tool") {
            return message.source
        }
        if let ag = model.selectedAgent {
            return ag.displayName
        }
        if let cfg = model.appState.compositeBrainConfigStore.getConfig(brainId: model.selectedBrain) {
            return cfg.label
        }
        return "Assistant"
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
    public var brainId: String?
    public var agentId: String?

    public init(
        source: ProjectSourceBinding,
        permission: ChatPermissionMode,
        brainId: String? = nil,
        agentId: String? = nil
    ) {
        self.source = source
        self.permission = permission
        self.brainId = brainId
        self.agentId = agentId
    }
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


/// 聊天项目显示口径（2026-11-22 双端统一，对齐 GPT/Claude 项目
/// 模式）：聊天只认项目——chip 与选择器都按「提供方 · 项目名」
/// 显示，提供方取项目当前家的位置种类（本机 / 设备名 / Git 托管名）。
public enum ChatProjectDisplay {
    /// 经本机来源绑到某个项目时返回该项目；沙盒与设备来源返回 nil。
    public static func boundProject(for binding: ProjectSourceBinding, data: LocalData) -> ProjectWithHomes? {
        guard binding.providerId == "local", let tid = binding.targetId, tid != "sandbox" else { return nil }
        return (try? data.projects.listProjects())?.first { $0.project.projectId == tid }
    }

    public static func providerTitle(for home: ProjectLocation?, data: LocalData) -> String {
        switch home?.homeKind {
        case "LOCAL": return "本机"
        case "GITHUB": return "GitHub"
        case "GITLAB": return "GitLab"
        case "GITEE": return "Gitee"
        case "DEVICE":
            if home?.hostId == "iphone-local" { return "本机" }
            if let hostId = home?.hostId,
               let agent = (try? data.agents.listAgents())?.first(where: { $0.deviceId == hostId }) {
                return agent.displayName
            }
            return "设备"
        default: return "本机"
        }
    }

    public static func icon(for home: ProjectLocation?) -> String {
        switch home?.homeKind {
        case "LOCAL": return "iphone"
        case "DEVICE": return home?.hostId == "iphone-local" ? "iphone" : "desktopcomputer"
        case "GITHUB", "GITLAB", "GITEE": return "chevron.left.forwardslash.chevron.right"
        default: return "folder"
        }
    }

    public static func homeLabel(for project: ProjectWithHomes, data: LocalData) -> String {
        guard let home = project.activeHome else { return "未指定家" }
        return "\(providerTitle(for: home, data: data)) · \(home.workspacePath)"
    }
}

public struct ProjectSourceChip: View {
    @ObservedObject var model: LocalChatModel
    @State private var showPicker = false

    private var display: (icon: String, text: String) {
        if let project = ChatProjectDisplay.boundProject(for: model.projectSource, data: model.data) {
            let home = project.activeHome
            return (ChatProjectDisplay.icon(for: home),
                    "\(ChatProjectDisplay.providerTitle(for: home, data: model.data)) · \(project.project.name)")
        }
        let provider = ProjectSourceRegistry.shared.provider(id: model.projectSource.providerId, data: model.data)
        return (provider?.icon ?? "questionmark.folder",
                provider.map { "\($0.title) · \(model.projectSource.chipLabel)" } ?? "来源不可用")
    }

    public var body: some View {
        let display = display
        Button { showPicker = true } label: {
            HStack(spacing: 5) {
                Image(systemName: display.icon)
                Text(display.text)
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
            ProjectPickerSheet(model: model)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
    }
}

/// 聊天选项目（2026-11-22 双端统一，对齐 GPT/Claude 项目模式）：
/// 聊天层只选项目——沙盒（无项目）+ 已建项目名单 + 新建项目入口。
/// 来源（本机 / 设备 / git）只在新建项目时选：「新建项目」在单内
/// 进来源步（与项目页「打开文件夹」对话框同三项同文案），git
/// 一项当场登记、建好自动绑到当前对话；本机 / 远程两项与项目页
/// 同去向（文件页 / 设备页）。
public struct ProjectPickerSheet: View {
    @ObservedObject var model: LocalChatModel
    @Environment(\.dismiss) private var dismiss

    private var projects: [ProjectWithHomes] {
        (try? model.data.projects.listProjects()) ?? []
    }

    private var isSandbox: Bool {
        model.projectSource.providerId == "local"
            && (model.projectSource.targetId ?? "sandbox") == "sandbox"
    }

    private func isCurrent(_ project: ProjectWithHomes) -> Bool {
        model.projectSource.providerId == "local"
            && model.projectSource.targetId == project.project.projectId
    }

    public var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // 顶部说明与安卓卡片对话框同文案（2026-11-22 顶部文字双端统一）。
                Text(projects.isEmpty
                    ? "还没有建好的项目。先聊也行，建好后自动切过去。"
                    : "选一个项目作为当前对话的项目。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                List {
                Section {
                    Button {
                        model.clearPendingProjectBind()
                        model.projectSource = .localDefault
                        model.permissionMode = .readOnly
                        model.persistSessionConfig()
                        dismiss()
                    } label: {
                        HStack {
                            Image(systemName: "iphone").frame(width: 24)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("App 沙盒（无项目）").foregroundStyle(.primary)
                                Text("本机 · 只读对话").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if isSandbox {
                                Image(systemName: "checkmark").foregroundStyle(.blue)
                            }
                        }
                    }
                    ForEach(projects) { project in
                        Button {
                            model.clearPendingProjectBind()
                            model.projectSource = ProjectSourceBinding(
                                providerId: "local", targetId: project.project.projectId,
                                targetTitle: project.project.name,
                                path: project.activeHome?.workspacePath)
                            model.permissionMode = .readOnly
                            model.persistSessionConfig()
                            dismiss()
                        } label: {
                            HStack {
                                Image(systemName: ChatProjectDisplay.icon(for: project.activeHome)).frame(width: 24)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(project.project.name).foregroundStyle(.primary)
                                    Text(ChatProjectDisplay.homeLabel(for: project, data: model.data))
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if isCurrent(project) {
                                    Image(systemName: "checkmark").foregroundStyle(.blue)
                                }
                            }
                        }
                    }
                }
                Section {
                    NavigationLink {
                        ProjectSourceStepView(model: model)
                    } label: {
                        HStack {
                            Image(systemName: "plus").frame(width: 24).foregroundStyle(.blue)
                            Text("新建项目").foregroundStyle(.blue)
                        }
                    }
                }
                }
            }
            .navigationTitle("选择项目")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { dismiss() } } }
        }
    }
}

/// 新建项目·选来源（与项目页「打开文件夹」对话框同三项同文案）：
/// 来源只在新建项目时选。本机 / 远程两项与项目页同去向（文件页 /
/// 设备页）；git 一项在本单内完成登记，建好自动绑到当前对话。
public struct ProjectSourceStepView: View {
    @ObservedObject var model: LocalChatModel
    @Environment(\.dismiss) private var dismiss

    public var body: some View {
        VStack(spacing: 0) {
            // 顶部说明与安卓卡片对话框同文案、同位置（标题之下）。
            Text("选一个位置打开它作为项目之家。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
            List {
                Section {
                    Button {
                        model.markPendingProjectBind()
                        dismiss()
                        model.appState.onSelectRoute?("files")
                    } label: {
                        sourceRow("本机文件夹", detail: "本机工作区里的文件夹；挂载、网盘同步下来的也是文件夹。")
                    }
                    Button {
                        model.markPendingProjectBind()
                        dismiss()
                        model.appState.onSelectRoute?("devices")
                    } label: {
                        sourceRow("远程设备上的文件夹", detail: "连一台已配对设备（局域网、远程都一样），再打开上面的文件夹。")
                    }
                    NavigationLink {
                        GitProjectFormView(model: model)
                    } label: {
                        sourceRow("git 仓库地址", detail: "粘 GitHub / GitLab / Gitee / Hugging Face / Git URL 登记位置，经 API 读，不拉代码。")
                    }
                } header: { Text("打开文件夹").textCase(nil) }
            }
        }
        .navigationTitle("新建项目")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func sourceRow(_ title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
            Text(detail).font(.footnote).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 4)
    }
}

/// 新建项目·git 登记表单（与项目页新建表单同字段同口径，共用
/// createProjectFromGitForm）：建好自动绑到当前对话并关单。
public struct GitProjectFormView: View {
    @ObservedObject var model: LocalChatModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var repo = ""
    @State private var branch = "main"
    @State private var path = ""

    public var body: some View {
        Form {
            Section {
                TextField("项目名称（如 wherevercode-agent）", text: $name)
                TextField("Git 仓库地址（可选）", text: $repo)
                TextField("默认分支", text: $branch)
                TextField("工作区路径（如 Documents/workspace）", text: $path)
            } header: { Text("项目基本信息").textCase(nil) }
        }
        .navigationTitle("新建项目")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("创建") {
                    if let created = createProjectFromGitForm(
                        name: name,
                        repoUrl: repo.isEmpty ? nil : repo,
                        branch: branch,
                        path: path,
                        data: model.data
                    ) {
                        model.clearPendingProjectBind()
                        model.projectSource = ProjectSourceBinding(
                            providerId: "local", targetId: created.project.projectId,
                            targetTitle: created.project.name,
                            path: created.activeHome?.workspacePath)
                        model.permissionMode = .readOnly
                        model.persistSessionConfig()
                    }
                    dismiss()
                }
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }
}

/// git 登记表单建项目（项目页与聊天新建项目共用）：建项目本体 +
/// 本机位置（hostId iphone-local），返回建好的项目；名为空或
/// 保存失败返回 nil。
public func createProjectFromGitForm(name: String, repoUrl: String?, branch: String, path: String, data: LocalData) -> ProjectWithHomes? {
    let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !clean.isEmpty else { return nil }
    let proj = Project(
        name: clean,
        repoUrl: repoUrl,
        defaultBranch: branch,
        origin: "LOCAL_SANDBOX"
    )
    do {
        try data.projects.saveProject(proj)
    } catch {
        return nil
    }
    let loc = ProjectLocation(
        projectId: proj.projectId,
        hostId: "iphone-local",
        workspacePath: path.isEmpty ? "Documents/\(clean)" : path
    )
    _ = try? data.projects.addLocation(loc)
    return (try? data.projects.listProjects())?.first { $0.project.projectId == proj.projectId }
        ?? ProjectWithHomes(project: proj, homes: [loc], activeHome: loc)
}

/// 对话方选择器（严格遵循定版规范：仅列出可选 Agent，整节移除已配置模型，单选规则仅提 Agent）
public struct ModelAndAgentSheet: View {
    @ObservedObject var model: LocalChatModel
    var onManageAgents: () -> Void
    @Environment(\.dismiss) private var dismiss

    private var availableAgents: [AgentRecord] {
        let allAgents = (try? model.data.agents.listAgents()) ?? []
        return allAgents.filter { $0.enabled && !$0.isDeviceHost && $0.kind != .localModel }
    }

    private func suggestedAgent(from agents: [AgentRecord]) -> (agent: AgentRecord, reason: String)? {
        let candidates = agents.filter { $0.kind != .device || $0.memberConnectionState == "CONNECTED" }
        if let target = model.projectSource.targetId,
           let match = candidates.first(where: { $0.kind == .device && $0.deviceId == target && $0.dispatchable }) {
            return (match, "与当前项目使用同一台已连接设备")
        }
        if let match = candidates.first(where: { $0.agentId == model.defaultAgentId }) {
            return (match, "你设置的默认 Agent")
        }
        if candidates.count == 1, let match = candidates.first {
            return (match, "当前唯一符合建议条件的 Agent")
        }
        return nil
    }

    public var body: some View {
        let agents = availableAgents
        NavigationStack {
            List {
                Section {
                    if let suggestion = suggestedAgent(from: agents) {
                        Button {
                            model.selectAgentInCurrentSession(suggestion.agent)
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Label(suggestion.agent.displayName, systemImage: "sparkles")
                                Text(suggestion.reason)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    } else {
                        Text("暂无明确建议，请手动选择 Agent。")
                            .foregroundStyle(.secondary)
                    }
                } header: { Text("智能建议").textCase(nil) }

                Section {
                    if agents.isEmpty {
                        Text("暂无已启用的 Agent：可在「Agent 与团队」中添加，或在设备配对后连接。")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(agents) { ag in
                            let isSelected = (model.selectedAgent?.agentId == ag.agentId)
                            HStack {
                                Button {
                                    model.selectAgentInCurrentSession(ag)
                                } label: {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(ag.displayName + (ag.dispatchable ? "" : "（仅对话）"))
                                            .foregroundStyle(.primary)
                                        let kindDesc = ag.kind == .device ? "设备 Agent" : (ag.platformAgentId != nil ? "平台 Agent" : "云端 Agent")
                                        Text("\(kindDesc) · \(ag.deviceId ?? (ag.platformHost ?? "本地/云端"))")
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                        if model.defaultSelectionId == "agent:\(ag.agentId)" {
                                            Text("默认 Agent").font(.caption2).foregroundStyle(.green)
                                        }
                                    }
                                }
                                Spacer()
                                if isSelected {
                                    Image(systemName: "checkmark").foregroundStyle(.blue)
                                }
                                if model.defaultSelectionId != "agent:\(ag.agentId)" {
                                    Button("设为默认") {
                                        model.setDefaultSelection(type: .agent, id: ag.agentId)
                                    }
                                    .font(.caption2)
                                    .buttonStyle(.bordered)
                                }
                            }
                        }
                    }
                } header: { Text("可选 Agent（设备 / 平台 / 云端）").textCase(nil) }

                Section {
                    Button("管理 Agent") { dismiss(); onManageAgents() }
                } footer: {
                    Text("单选规则：对话框仅保留一个勾选的 Agent，由用户自选对话方。")
                }
            }
            .navigationTitle("选择对话方")
            .navigationBarTitleDisplayMode(.inline)
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

    private var partnerLabel: String {
        if let ag = model.selectedAgent {
            return ag.displayName + (ag.dispatchable ? "" : "（仅对话）")
        }
        let cfg = model.appState.compositeBrainConfigStore.getConfig(brainId: model.selectedBrain)
        guard let cfg else { return "选择对话方" }
        return cfg.model.isEmpty ? cfg.label : cfg.model
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ProjectSourceChip(model: model)

            // 对话组件收敛：卡片内部与频道页共用 DialogComposerView
            // （stacked 排布）：附件条走 above、「＋」菜单与对话方选择
            // 走 leading、麦克风走 accessory；发送状态钮三态（停止/
            // 语音/发送）由组件状态机承接，回调与标识逐字不变。
            DialogComposerView(
                text: $model.draft,
                placeholder: "向 Agent 提问或分派任务",
                layout: .stacked,
                sendButtonStyle: .blackCircle,
                isSending: model.isSending,
                focused: focused,
                onTextChange: { model.persistDraft() },
                onSend: { model.startSending() },
                onStop: { model.stopSending() },
                onEmptyAction: { onSelectRoute("voice") },
                emptyActionIcon: "waveform",
                emptyActionLabel: "语音会话",
                inputAccessibilityID: "chat.input",
                sendAccessibilityID: "chat.send",
                stopAccessibilityID: "chat.stop"
            ) {
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
            } leading: {
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
                        Text(partnerLabel).lineLimit(1)
                        Image(systemName: "chevron.down").font(.system(size: 9, weight: .semibold))
                    }
                    .font(.system(size: 13, weight: .medium))
                }
                .accessibilityIdentifier("home.agentSelectorButton")
            } accessory: {
                Button {
                    focused.wrappedValue = true
                } label: {
                    Image(systemName: "mic").font(.system(size: 16, weight: .medium))
                }
                .accessibilityLabel("语音输入（使用键盘听写）")
            } below: {
                EmptyView()
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
            ModelAndAgentSheet(
                model: model,
                onManageAgents: { onSelectRoute("agents") }
            )
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
