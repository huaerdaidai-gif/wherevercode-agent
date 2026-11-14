import Foundation
import SwiftUI

public final class LocalData: ObservableObject {
    public static let shared = LocalData()

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

    public init(directoryURL: URL? = nil) {
        let baseDir = directoryURL ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
        try? FileManager.default.createDirectory(at: baseDir, withIntermediateDirectories: true)
        let dbPath = baseDir.appendingPathComponent("wherevercode-tasks.db").path
        let db = (try? SQLiteDatabase(path: dbPath)) ?? (try! SQLiteDatabase(path: ":memory:"))
        self.database = db
        let kvStore = try! KeyValueStore(db: db)
        self.kv = kvStore
        self.conversations = try! SQLiteConversationStore(db: db)
        self.tasks = try! LocalTaskStore(db: db)
        self.agents = AgentRegistryStore(kv: kvStore)
        self.projects = try! ProjectStore(db: db, kv: kvStore)
        self.cursors = ReplyCursorStore(kv: kvStore)
        self.chatPrefs = ChatHistoryPrefs(kv: kvStore)
    }
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
    public let appState: AppState
    public let eventLog: InMemoryEventLog
    public let data: LocalData
    private var currentTurn: Task<Void, Never>?

    public init(appState: AppState, data: LocalData = .shared) {
        self.appState = appState
        self.data = data
        self.eventLog = data.eventLog
        self.selectedBrain = appState.brainSettingsModel.currentBrainId ?? appState.brainSettingsModel.configs.first?.brainId ?? "deepseek"
        reloadHistory()
    }

    public func reloadHistory() {
        history = (try? data.conversations.listConversations()) ?? []
        if let cid = conversationID {
            messages = (try? data.conversations.listMessages(conversationId: cid)) ?? []
        }
    }

    public func newConversation() {
        guard !isSending else {
            error = "请等待当前回复完成后新建对话"
            return
        }
        conversationID = nil
        messages = []
        error = nil
        streaming = ""
        try? data.chatPrefs.setCurrentSessionId(nil)
    }

    public func open(_ conversation: Conversation) {
        conversationID = conversation.id
        if let bid = conversation.brainId {
            selectedBrain = bid
        }
        messages = (try? data.conversations.listMessages(conversationId: conversation.id)) ?? []
        try? data.chatPrefs.setCurrentSessionId(conversation.id)
        showHistory = false
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
        reloadHistory()
        open(forked)
    }

    public func stopSending() {
        currentTurn?.cancel()
        currentTurn = nil
        isSending = false
        if let cid = conversationID {
            eventLog.append(conversationId: cid, kind: "STOPPED", detail: "已停止：剩余工具调用不再执行。")
        }
    }

    public func startSending() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isSending else { return }
        draft = ""
        error = nil
        isSending = true
        currentTurn = Task {
            await send(userText: text)
        }
    }

    public func send(userText: String) async {
        defer { isSending = false }
        do {
            let conv: Conversation
            if let cid = conversationID, let existing = try data.conversations.getConversation(id: cid) {
                conv = existing
            } else {
                let title = String(userText.prefix(28))
                conv = try data.conversations.createConversation(brainId: selectedBrain.isEmpty ? nil : selectedBrain, title: title)
                conversationID = conv.id
                try? data.chatPrefs.setCurrentSessionId(conv.id)
            }
            let engine = appState.makeBrainEngine(
                conversations: data.conversations,
                tools: DefaultiOSToolRegistry(),
                eventLog: eventLog,
                approvalGate: DenyApprovalGate()
            )
            _ = try await engine.chat(
                conversationId: conv.id,
                userText: userText,
                brainIdOverride: selectedBrain.isEmpty ? nil : selectedBrain
            )
            reloadHistory()
        } catch {
            self.error = error.localizedDescription
            reloadHistory()
        }
    }
}

/// Claude 极简风格对话首页（严格遵循设计冻结要求：顶部仅左右两颗圆形按钮、中部 ✳ 星号与问候、底部悬浮胶囊输入栏，无底部 Tab Bar、无功能宫格、无首页统计卡片）
public struct LocalChatHomeView: View {
    @ObservedObject public var appState: AppState
    @ObservedObject public var model: LocalChatModel
    public var onOpenDrawer: () -> Void
    public var onSelectRoute: (String) -> Void

    @State private var greetingDismissed = false
    @State private var showAgentSelectorSheet = false
    @State private var showVoiceSheet = false
    @State private var showDocumentPicker = false
    @State private var query = ""

    public init(
        appState: AppState,
        model: LocalChatModel,
        onOpenDrawer: @escaping () -> Void,
        onSelectRoute: @escaping (String) -> Void
    ) {
        self.appState = appState
        self.model = model
        self.onOpenDrawer = onOpenDrawer
        self.onSelectRoute = onSelectRoute
    }

    private var activeBrainLabel: String {
        if let cfg = appState.brainSettingsModel.configs.first(where: { $0.brainId == model.selectedBrain }) {
            return cfg.label
        }
        return "未选择模型"
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

    public var body: some View {
        VStack(spacing: 0) {
            // 顶部极简双圆按钮栏（左：四父级抽屉按钮；右：Agent/模型上下文按钮）
            HStack {
                Button(action: onOpenDrawer) {
                    Image(systemName: "line.3.horizontal")
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                        .frame(width: 44, height: 44)
                        .background(Color.white, in: Circle())
                        .overlay(Circle().stroke(Color(red: 0.87, green: 0.86, blue: 0.84), lineWidth: 1))
                }
                .accessibilityIdentifier("home.drawerButton")
                .accessibilityLabel("打开导航抽屉")

                Spacer()

                // 中部紧凑状态胶囊（仅在有活动会话或未连接模型时提示，不破坏留白）
                if appState.brainSettingsModel.currentBrainId == nil {
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
            .padding(.horizontal, 20)
            .padding(.top, 10)
            .padding(.bottom, 8)

            // 主体消息流或 Claude 风格极简留白问候区
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 16) {
                        if model.messages.isEmpty && !greetingDismissed {
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

                                // 轻量单行胶囊建议（非功能宫格、非统计卡片）
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
                                Text("正在等待模型回复…")
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
                    // 左侧 ＋ 多模态与任务菜单
                    Menu {
                        Button {
                            onSelectRoute("files")
                        } label: {
                            Label("导入/浏览本地沙盒文件", systemImage: "folder")
                        }
                        Button {
                            onSelectRoute("projects")
                        } label: {
                            Label("项目 · 查看已保存位置", systemImage: "shippingbox")
                        }
                        Button {
                            onSelectRoute("compose")
                        } label: {
                            Label("转为正式任务派单 (compose)", systemImage: "checklist")
                        }
                        Button {
                            onSelectRoute("voice")
                        } label: {
                            Label("语音与多模态附件 (voice)", systemImage: "waveform")
                        }
                        Button {
                            model.newConversation()
                        } label: {
                            Label("新建空白对话", systemImage: "square.and.pencil")
                        }
                        Button {
                            model.showHistory = true
                        } label: {
                            Label("查看历史会话 (history)", systemImage: "clock.arrow.circlepath")
                        }
                    } label: {
                        Text("＋")
                            .font(.system(size: 22, weight: .regular))
                            .foregroundStyle(Color(red: 0.35, green: 0.34, blue: 0.32))
                            .frame(width: 34, height: 34)
                    }
                    .accessibilityIdentifier("home.plusMenu")

                    // 输入框
                    TextField("输入或长按说话", text: $model.draft, axis: .vertical)
                        .lineLimit(1...5)
                        .font(.system(size: 16))
                        .submitLabel(.send)
                        .onSubmit {
                            model.startSending()
                        }

                    // 语音按钮
                    Button {
                        onSelectRoute("voice")
                    } label: {
                        Image(systemName: "mic")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityLabel("语音输入")

                    // 发送或停止按钮
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
                        .accessibilityIdentifier("home.sendButton")
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
                    if model.history.isEmpty {
                        Text("暂无会话记录")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(model.history) { conv in
                            Button {
                                model.open(conv)
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(conv.title)
                                        .font(.body.weight(.medium))
                                    Text("模型：\(conv.brainId ?? "默认") · ID: \(conv.id.prefix(8))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .swipeActions {
                                Button("分叉") {
                                    model.forkConversation(conv)
                                }
                                .tint(.indigo)
                            }
                        }
                    }
                }
                .navigationTitle("历史会话")
                .searchable(text: $query, prompt: "搜索历史会话")
            }
        }
        .sheet(isPresented: $showAgentSelectorSheet) {
            NavigationStack {
                List {
                    Section("当前推理大脑 (Brain)") {
                        Picker("选择大脑", selection: $model.selectedBrain) {
                            ForEach(appState.brainSettingsModel.configs) { cfg in
                                Text("\(cfg.label) (\(cfg.model ?? "未设模型"))").tag(cfg.brainId)
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
                    Text("暂无本地项目")
                        .foregroundStyle(.secondary)
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
                    Text("暂无已登记 Agent")
                        .foregroundStyle(.secondary)
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
                    Text("当前进程暂无模型事件")
                        .foregroundStyle(.secondary)
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
