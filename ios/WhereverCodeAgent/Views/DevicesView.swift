import SwiftUI

/// 设备管理与详情视图（覆盖 devices 与 devicedetail 路由）：
/// - 支持在本地 SQLite/KV 注册表 (AgentRegistryStore) 中真实添加、配对登记、授权工作目录和删除设备；
/// - 登记后的设备会立即出现在对话输入框上方的「项目来源」选择器（局域网电脑 / NAT·远程设备 / VPS）中；
/// - 保留誠实状态说明：V5 平台云端无设备管理接口（服务端无此接口，阻塞中），本地已保存记录需通过网络探测确认在线状态，绝不伪装在线。
public struct DevicesView: View {
    public var onNavigateRoute: (String) -> Void
    @State private var devices: [AgentRecord] = []
    @State private var loadError: String?
    @State private var showAddSheet = false
    @State private var selectedDevice: AgentRecord? = nil
    @State private var statusBanner: String? = nil

    public init() {
        self.onNavigateRoute = { _ in }
    }

    public init(appState: AppState, onNavigateRoute: @escaping (String) -> Void = { _ in }) {
        self.onNavigateRoute = onNavigateRoute
    }

    private let prototypeTemplates: [(id: String, name: String, subtitle: String, access: AgentAccess, host: String, folder: String)] = [
        ("device-mac-workstation", "Mac · 开发工作站", "macOS · 局域网开发主机", .app, "mac-workstation.local:8765", "~/Projects/wherevercode-agent"),
        ("device-redmi-k70", "Redmi K70 · Android Worker", "Android · 移动执行节点", .connector, "192.168.1.70:8765", "/sdcard/Projects"),
        ("device-xiaomi-12", "小米 12 · 本地模型", "Android / Termux · 本地推理节点", .api, "192.168.1.12:8080", "~/AI-Workspace"),
        ("device-iphone-local", "iPhone · 移动工作台", "iOS · 本机沙盒与审批中心", .app, "127.0.0.1", "Documents/wherevercode-agent")
    ]

    public var body: some View {
        List {
            if let statusBanner {
                Section {
                    Label(statusBanner, systemImage: "info.circle.fill")
                        .font(.caption)
                        .foregroundStyle(.blue)
                }
            }

            Section {
                Button {
                    showAddSheet = true
                } label: {
                    Label("＋ 添加 / 配对设备", systemImage: "plus.circle.fill")
                        .font(.subheadline.weight(.semibold))
                }
                .accessibilityIdentifier("devices.addButton")
            } header: {
                Text("添加与配对")
            } footer: {
                Text("添加局域网电脑、NAT/远程设备或 VPS 后，可在对话输入框上方的项目来源中直接选用。")
            }

            registeredDevicesSection
            templatesSection
            shortcutsSection
        }
        .navigationTitle("设备")
        .onAppear(perform: reload)
        .sheet(isPresented: $showAddSheet) {
            AddDeviceSheet { added in
                reload()
                statusBanner = "已登记设备「\(added.displayName)」，可在对话来源中选择。"
            }
        }
        .sheet(item: $selectedDevice) { dev in
            DeviceDetailSheet(
                device: dev,
                onUpdated: { reload() },
                onNavigateRoute: { r in
                    selectedDevice = nil
                    onNavigateRoute(r)
                }
            )
        }
    }

    @ViewBuilder
    private var registeredDevicesSection: some View {
        Section("已登记设备（\(devices.count) 台）") {
            if let loadError {
                Text(loadError).foregroundStyle(.red)
            }
            if devices.isEmpty {
                Text("暂无已登记设备。可点击上方「＋ 添加 / 配对设备」或从下方预设模板快速登记。")
                    .font(.subheadline)
                    .foregroundStyle(AgentUI.secondary)
            }
            ForEach(devices, id: \.agentId) { device in
                Button {
                    selectedDevice = device
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Label(device.displayName, systemImage: "desktopcomputer")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.primary)
                            Text("\(device.baseUrl ?? "未填地址") · \(device.lastTestSummary ?? "未验证连接")")
                                .font(.caption)
                                .foregroundStyle(AgentUI.secondary)
                            if let folder = device.deviceFolder, !folder.isEmpty {
                                Text("授权路径：\(folder)")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Text("详情")
                            .font(.caption)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(Color.secondary.opacity(0.12), in: Capsule())
                    }
                }
            }
            .onDelete { offsets in
                for idx in offsets {
                    try? LocalData.shared.agents.deleteAgent(agentId: devices[idx].agentId)
                }
                reload()
            }
        }
    }

    @ViewBuilder
    private var templatesSection: some View {
        Section("常见设备模板（点击查看详情或一键登记）") {
            ForEach(prototypeTemplates, id: \.id) { tpl in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(tpl.name).font(.subheadline)
                        Text(tpl.subtitle).font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("详情") {
                        if let existing = devices.first(where: { $0.agentId == tpl.id }) {
                            selectedDevice = existing
                        } else {
                            let rec = AgentRecord(
                                agentId: tpl.id,
                                displayName: tpl.name,
                                kind: .device,
                                access: tpl.access,
                                baseUrl: tpl.host,
                                lastTestSummary: "本地登记 · 未验证在线",
                                memberConnectionState: "NOT_CONNECTED",
                                deviceId: tpl.id,
                                deviceFolder: tpl.folder
                            )
                            try? LocalData.shared.agents.saveAgent(rec)
                            reload()
                            selectedDevice = rec
                        }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }
        }
    }

    @ViewBuilder
    private var shortcutsSection: some View {
        Section("设备详情与快捷入口") {
            LabeledContent("本机系统", value: "iOS " + UIDevice.current.systemVersion)
            LabeledContent("任务执行", value: "需要授权与实际适配")
            LabeledContent("项目访问", value: "受范围约束")
            HStack(spacing: 10) {
                Button("查看设备任务") { onNavigateRoute("tasks") }
                    .buttonStyle(.bordered)
                Button("Agent 详情") { onNavigateRoute("agents") }
                    .buttonStyle(.bordered)
                Button("权限设置") { onNavigateRoute("settings") }
                    .buttonStyle(.bordered)
            }
            .font(.caption)
            Text("连接状态：真实在线、离线与执行能力需由设备端 Hand 服务确认；平台云端设备列表：\(ReservedFeature.deviceManagement.notImplementedReason)")
                .font(.caption)
                .foregroundStyle(AgentUI.secondary)
        }
    }

    private func reload() {
        do {
            devices = try LocalData.shared.agents.listAgents().filter { $0.kind == .device }
            loadError = nil
        } catch {
            loadError = error.localizedDescription
        }
    }
}

private struct AddDeviceSheet: View {
    var onSaved: (AgentRecord) -> Void
    @Environment(\.dismiss) private var dismiss

    enum DeviceConnectionKind: String, CaseIterable, Identifiable {
        case lan = "局域网电脑"
        case remote = "NAT / 远程设备"
        case vps = "VPS 服务器"
        case localModel = "本地模型设备"
        var id: String { rawValue }
    }

    @State private var connKind: DeviceConnectionKind = .lan
    @State private var name = ""
    @State private var host = ""
    @State private var folder = "~/Projects"
    @State private var pairingSecret = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("设备类型") {
                    Picker("连接类型", selection: $connKind) {
                        ForEach(DeviceConnectionKind.allCases) { k in
                            Text(k.rawValue).tag(k)
                        }
                    }
                }
                Section("连接与配对信息") {
                    TextField("设备名称（如 Mac Studio / 家里电脑）", text: $name)
                    TextField("地址或主机名（如 192.168.1.10:8765）", text: $host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("配对码或共享凭证（可选，仅存本机 Keychain）", text: $pairingSecret)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section("授权工作路径") {
                    TextField("默认工作区目录（如 ~/Projects/app）", text: $folder)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Text("设备操作仅限在已授权的目录范围内执行，危险写入操作需经过审批。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("添加设备")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存配对") {
                        save()
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func save() {
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let id = "device-\(UUID().uuidString.prefix(8).lowercased())"
        let access: AgentAccess
        let normalizedHost: String
        switch connKind {
        case .lan:
            access = .app
            normalizedHost = host.isEmpty ? "192.168.1.100:8765" : host
        case .remote:
            access = .connector
            normalizedHost = host.isEmpty ? "relay.wherevercode.local" : host
        case .vps:
            access = .cli
            normalizedHost = host.isEmpty ? "vps.example.com:8765" : host
        case .localModel:
            access = .api
            normalizedHost = host.isEmpty ? "192.168.1.50:8080" : host
        }
        let authRef: String? = pairingSecret.isEmpty ? nil : "device:\(id):token"
        if let authRef, !pairingSecret.isEmpty {
            try? BrainKeyStore().putSecret(authRef: authRef, value: pairingSecret)
        }
        let record = AgentRecord(
            agentId: id,
            displayName: cleanName,
            kind: .device,
            access: access,
            baseUrl: normalizedHost,
            authRef: authRef,
            lastTestSummary: "已登记（\(connKind.rawValue)）· 待探测连接",
            memberConnectionState: "NOT_CONNECTED",
            deviceId: id,
            deviceFolder: folder.isEmpty ? "/" : folder
        )
        try? LocalData.shared.agents.saveAgent(record)
        onSaved(record)
    }
}

private struct DeviceDetailSheet: View {
    @State var device: AgentRecord
    var onUpdated: () -> Void
    var onNavigateRoute: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var folder: String = ""
    @State private var host: String = ""
    @State private var isProbing = false
    @State private var probeResult: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("设备详情") {
                    LabeledContent("设备", value: device.displayName)
                    LabeledContent("接入方式", value: device.access.rawValue)
                    LabeledContent("在线状态", value: device.memberConnectionState == "CONNECTED" ? "在线" : "未验证 / 离线")
                    LabeledContent("任务执行", value: "需要授权与实际适配")
                    LabeledContent("项目访问", value: "受范围约束")
                }
                Section("地址与目录授权") {
                    TextField("连接地址", text: $host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("授权项目路径", text: $folder)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("保存修改") {
                        device.baseUrl = host
                        device.deviceFolder = folder
                        try? LocalData.shared.agents.saveAgent(device)
                        onUpdated()
                        probeResult = "已更新设备地址与授权路径。"
                    }
                }
                Section("连接检测") {
                    Button(isProbing ? "正在探测…" : "测试设备连接") {
                        Task { await probe() }
                    }
                    .disabled(isProbing)
                    if let probeResult {
                        Text(probeResult)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("关联操作") {
                    Button("查看设备任务") { onNavigateRoute("tasks") }
                    Button("Agent 详情") { onNavigateRoute("agents") }
                    Button("权限设置") { onNavigateRoute("settings") }
                    Button("删除此设备", role: .destructive) {
                        try? LocalData.shared.agents.deleteAgent(agentId: device.agentId)
                        onUpdated()
                        dismiss()
                    }
                }
            }
            .navigationTitle("设备详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .onAppear {
                folder = device.deviceFolder ?? ""
                host = device.baseUrl ?? ""
            }
        }
    }

    private func probe() async {
        isProbing = true
        defer { isProbing = false }
        let raw = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else {
            probeResult = "请先填写设备地址。"
            return
        }
        let urlStr = raw.hasPrefix("http") ? raw : "https://\(raw)"
        guard let url = URL(string: urlStr) else {
            probeResult = "地址格式无效：\(raw)"
            return
        }
        var req = URLRequest(url: url)
        req.timeoutInterval = 4
        do {
            let (_, resp) = try await URLSession.shared.data(for: req)
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            device.lastTestSummary = "探测返回 HTTP \(code)"
            device.memberConnectionState = (200..<500).contains(code) ? "CONNECTED" : "NOT_CONNECTED"
            try? LocalData.shared.agents.saveAgent(device)
            onUpdated()
            probeResult = "已收到响应（HTTP \(code)）。"
        } catch {
            device.lastTestSummary = "未连接（\(error.localizedDescription)）"
            device.memberConnectionState = "NOT_CONNECTED"
            try? LocalData.shared.agents.saveAgent(device)
            onUpdated()
            probeResult = "未连接：\(error.localizedDescription)"
        }
    }
}
